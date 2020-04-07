/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.analyzer.managers.utils

import com.fasterxml.jackson.module.kotlin.readValue

import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.analyzer.TOOL_NAME
import org.ossreviewtoolkit.downloader.VcsHost
import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.VcsType
import org.ossreviewtoolkit.model.yamlMapper
import org.ossreviewtoolkit.utils.DiskCache
import org.ossreviewtoolkit.utils.Os
import org.ossreviewtoolkit.utils.collectMessagesAsString
import org.ossreviewtoolkit.utils.getUserOrtDirectory
import org.ossreviewtoolkit.utils.log
import org.ossreviewtoolkit.utils.searchUpwardsForSubdirectory
import org.ossreviewtoolkit.utils.showStackTrace

import java.io.File
import java.net.URL
import java.util.regex.Pattern

import org.apache.maven.artifact.repository.LegacyLocalRepositoryManager
import org.apache.maven.bridge.MavenRepositorySystem
import org.apache.maven.execution.DefaultMavenExecutionRequest
import org.apache.maven.execution.DefaultMavenExecutionResult
import org.apache.maven.execution.MavenExecutionRequest
import org.apache.maven.execution.MavenExecutionRequestPopulator
import org.apache.maven.execution.MavenSession
import org.apache.maven.internal.aether.DefaultRepositorySystemSessionFactory
import org.apache.maven.model.Scm
import org.apache.maven.model.building.ModelBuildingRequest
import org.apache.maven.plugin.LegacySupport
import org.apache.maven.project.MavenProject
import org.apache.maven.project.ProjectBuilder
import org.apache.maven.project.ProjectBuildingException
import org.apache.maven.project.ProjectBuildingRequest
import org.apache.maven.project.ProjectBuildingResult
import org.apache.maven.properties.internal.EnvironmentUtils
import org.apache.maven.session.scope.internal.SessionScope
import org.apache.maven.settings.Proxy

import org.codehaus.plexus.DefaultContainerConfiguration
import org.codehaus.plexus.DefaultPlexusContainer
import org.codehaus.plexus.PlexusConstants
import org.codehaus.plexus.PlexusContainer
import org.codehaus.plexus.classworlds.ClassWorld
import org.codehaus.plexus.logging.BaseLoggerManager

import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.impl.RemoteRepositoryManager
import org.eclipse.aether.impl.RepositoryConnectorProvider
import org.eclipse.aether.repository.MirrorSelector
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.repository.WorkspaceReader
import org.eclipse.aether.resolution.ArtifactDescriptorRequest
import org.eclipse.aether.spi.connector.ArtifactDownload
import org.eclipse.aether.spi.connector.layout.RepositoryLayoutProvider
import org.eclipse.aether.spi.connector.transport.GetTask
import org.eclipse.aether.spi.connector.transport.TransporterProvider
import org.eclipse.aether.transfer.AbstractTransferListener
import org.eclipse.aether.transfer.NoRepositoryConnectorException
import org.eclipse.aether.transfer.NoRepositoryLayoutException
import org.eclipse.aether.transfer.TransferEvent

fun Artifact.identifier() = "$groupId:$artifactId:$version"

class MavenSupport(workspaceReader: WorkspaceReader) {
    companion object {
        private const val MAX_DISK_CACHE_SIZE_IN_BYTES = 1024L * 1024L * 1024L
        private const val MAX_DISK_CACHE_ENTRY_AGE_SECONDS = 6 * 60 * 60

        // See http://maven.apache.org/pom.html#SCM.
        val SCM_REGEX = Pattern.compile("scm:(?<type>[^:@]+):(?<url>.+)")!!
        val USER_HOST_REGEX = Pattern.compile("scm:(?<user>[^:@]+)@(?<host>[^:]+):(?<url>.+)")!!

        private val remoteArtifactCache =
            DiskCache(
                File(getUserOrtDirectory(), "$TOOL_NAME/cache/remote_artifacts"),
                MAX_DISK_CACHE_SIZE_IN_BYTES, MAX_DISK_CACHE_ENTRY_AGE_SECONDS
            )

        private fun createContainer(): PlexusContainer {
            val configuration = DefaultContainerConfiguration().apply {
                autoWiring = true
                classPathScanning = PlexusConstants.SCANNING_INDEX
                classWorld = ClassWorld("plexus.core", javaClass.classLoader)
            }

            return DefaultPlexusContainer(configuration).apply {
                loggerManager = object : BaseLoggerManager() {
                    override fun createLogger(name: String) = MavenLogger(log.delegate.level)
                }
            }
        }

        fun createProxyFromUrl(proxyUrl: String): Proxy {
            val url = URL(proxyUrl)
            return Proxy().apply {
                protocol = url.protocol
                username = url.userInfo?.substringBefore(':')
                password = url.userInfo?.substringAfter(':')
                host = url.host
                if (url.port != -1) port = url.port
            }
        }

        fun parseLicenses(mavenProject: MavenProject) =
            mavenProject.licenses.mapNotNull {
                if (it.comments?.startsWith("SPDX-License-Identifier:") == true) {
                    it.comments.removePrefix("SPDX-License-Identifier:")
                } else {
                    it.name ?: it.url ?: it.comments
                }?.trim()
            }.toSortedSet()

        /**
         * When asking Maven for the SCM URL of a POM that does not itself define an SCM URL, Maven returns the SCM
         * URL of the first parent POM (if any) that defines one and appends the artifactIds of all child POMs to it,
         * separated by slashes.
         * This behavior is fundamentally broken because it invalidates the SCM URL for all VCS that cannot limit
         * cloning to a specific path within a repository, or use a different syntax for that. Also, the assumption
         * that the source code for a child artifact is stored in a top-level directory named like the artifactId
         * inside the parent artifact's repository is often not correct.
         * To address this, determine the SCM URL of the parent (if any) that is closest to the root POM and whose
         * SCM URL still is a prefix of the child POM's SCM URL.
         */
        fun getOriginalScm(mavenProject: MavenProject): Scm? {
            var scm = mavenProject.scm
            var parent = mavenProject.parent

            while (parent != null) {
                parent.scm?.let { parentScm ->
                    parentScm.connection?.let { parentConnection ->
                        if (parentConnection.isNotBlank() && scm.connection.startsWith(parentConnection)) {
                            scm = parentScm
                        }
                    }
                }

                parent = parent.parent
            }

            return scm
        }

        fun parseScm(scm: Scm?): VcsInfo {
            val connection = scm?.connection.orEmpty()
            val tag = scm?.tag?.takeIf { it != "HEAD" }.orEmpty()

            if (connection.isEmpty()) return VcsInfo.EMPTY

            return SCM_REGEX.matcher(connection).let {
                if (it.matches()) {
                    val type = it.group("type")
                    val url = it.group("url")

                    when {
                        // CVS URLs usually start with ":pserver:" or ":ext:", but as ":" is also the delimiter used by
                        // the Maven SCM plugin, no double ":" is used in the connection string and we need to fix it up
                        // here.
                        type == "cvs" && !url.startsWith(":") -> {
                            VcsInfo(type = VcsType.CVS, url = ":$url", revision = tag)
                        }

                        // Maven does not officially support git-repo as an SCM, see
                        // http://maven.apache.org/scm/scms-overview.html, so come up with a convention to use the URL
                        // fragment for the path to the manifest inside the repository.
                        type == "git-repo" -> {
                            VcsInfo(
                                type = VcsType.GIT_REPO,
                                url = url.substringBefore('?'),
                                revision = tag,
                                path = url.substringAfter('?')
                            )
                        }

                        type == "svn" -> {
                            // With Subversion, a tag actually is a path and not a symbolic revision.
                            val path = tag.takeIf { it.isEmpty() } ?: "tags/$tag"
                            VcsInfo(type = VcsType.SUBVERSION, url = url, revision = "", path = path)
                        }

                        url.startsWith("//") -> {
                            // Work around the common mistake to omit the Maven SCM provider.
                            val fixedUrl = "$type:$url"

                            // Try to detect the Maven SCM provider from the URL only, e.g. by looking at the host or
                            // special URL paths.
                            VcsHost.toVcsInfo(fixedUrl).copy(revision = tag).also {
                                log.info { "Fixed up invalid SCM connection '$connection' without a provider to $it." }
                            }
                        }

                        else -> VcsInfo(type = VcsType(type), url = url, revision = tag)
                    }
                } else {
                    val userHostMatcher = USER_HOST_REGEX.matcher(connection)

                    if (userHostMatcher.matches()) {
                        // Some projects omit the provider and use the SCP-like Git URL syntax, for example
                        // "scm:git@github.com:facebook/facebook-android-sdk.git".
                        val host = userHostMatcher.group("host")
                        val url = userHostMatcher.group("url")

                        VcsInfo(type = VcsType.GIT, url = "https://$host/$url", revision = tag)
                    } else if (connection.startsWith("git://") || connection.endsWith(".git")) {
                        // It is a common mistake to omit the "scm:[provider]:" prefix. Add fall-backs for nevertheless
                        // clear cases.
                        log.warn { "Maven SCM connection URL '$connection' lacks the required 'scm' prefix." }

                        VcsInfo(VcsType.GIT, connection, tag)
                    } else {
                        log.info { "Ignoring Maven SCM connection URL '$connection' of unexpected format." }

                        VcsInfo.EMPTY
                    }
                }
            }
        }

        fun parseVcsInfo(mavenProject: MavenProject) = parseScm(getOriginalScm(mavenProject))
    }

    val container = createContainer()
    private val repositorySystemSession = createRepositorySystemSession(workspaceReader)

    // The MavenSettingsBuilder class is deprecated but internally it uses its successor SettingsBuilder. Calling
    // MavenSettingsBuilder requires less code than calling SettingsBuilder, so use it until it is removed.
    @Suppress("DEPRECATION")
    private fun createMavenExecutionRequest(): MavenExecutionRequest {
        val request = DefaultMavenExecutionRequest()

        val props = System.getProperties()
        EnvironmentUtils.addEnvVars(props)
        request.systemProperties = props

        val populator = container.lookup(MavenExecutionRequestPopulator::class.java, "default")

        val settingsBuilder = container.lookup(org.apache.maven.settings.MavenSettingsBuilder::class.java, "default")
        // TODO: Add a way to configure the location of a user settings file and pass it to the method below which will
        //       merge the user settings with the global settings. The default location of the global settings file is
        //       "${user.home}/.m2/settings.xml". The settings file locations can already be overwritten using the
        //       system properties "org.apache.maven.global-settings" and "org.apache.maven.user-settings".
        val settings = settingsBuilder.buildSettings()

        Os.proxy?.let { proxyUrl ->
            // Maven only uses the first active proxy for both HTTP and HTTPS traffic.
            settings.proxies.add(createProxyFromUrl(proxyUrl))
            log.debug { "Added $proxyUrl as proxy." }
        }

        populator.populateFromSettings(request, settings)
        populator.populateDefaults(request)

        return request
    }

    private fun createRepositorySystemSession(workspaceReader: WorkspaceReader): RepositorySystemSession {
        val mavenRepositorySystem = container.lookup(MavenRepositorySystem::class.java, "default")
        val aetherRepositorySystem = container.lookup(RepositorySystem::class.java, "default")
        val repositorySystemSessionFactory = container.lookup(
            DefaultRepositorySystemSessionFactory::class.java,
            "default"
        )

        val repositorySystemSession = repositorySystemSessionFactory
            .newRepositorySession(createMavenExecutionRequest())

        repositorySystemSession.mirrorSelector = HttpsMirrorSelector(repositorySystemSession.mirrorSelector)

        val localRepository = mavenRepositorySystem.createLocalRepository(
            createMavenExecutionRequest(),
            org.apache.maven.repository.RepositorySystem.defaultUserLocalRepository
        )

        val session = LegacyLocalRepositoryManager.overlay(
            localRepository, repositorySystemSession,
            aetherRepositorySystem
        )

        return DefaultRepositorySystemSession(session).setWorkspaceReader(workspaceReader)
    }

    fun buildMavenProject(pomFile: File): ProjectBuildingResult {
        val projectBuilder = container.lookup(ProjectBuilder::class.java, "default")
        val projectBuildingRequest = createProjectBuildingRequest(true)

        return try {
            wrapMavenSession {
                projectBuilder.build(pomFile, projectBuildingRequest)
            }
        } catch (e: ProjectBuildingException) {
            e.showStackTrace()

            val failedProject = e.results?.find { projectBuildingResult ->
                projectBuildingResult.pomFile == pomFile
            }

            if (failedProject != null) {
                log.warn {
                    "There was an error building '${pomFile.invariantSeparatorsPath}', continuing with the " +
                            "incompletely built project: ${e.collectMessagesAsString()}"
                }
                failedProject
            } else {
                log.error { "Failed to build '${pomFile.invariantSeparatorsPath}': ${e.collectMessagesAsString()}" }
                throw e
            }
        }
    }

    fun createProjectBuildingRequest(resolveDependencies: Boolean): ProjectBuildingRequest {
        val projectBuildingRequest = createMavenExecutionRequest().projectBuildingRequest

        return projectBuildingRequest.apply {
            isResolveDependencies = resolveDependencies
            repositorySession = repositorySystemSession
            validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
        }
    }

    private fun requestRemoteArtifact(artifact: Artifact, repositories: List<RemoteRepository>): RemoteArtifact {
        remoteArtifactCache.read(artifact.toString())?.let {
            log.debug { "Reading remote artifact for '$artifact' from disk cache." }
            return yamlMapper.readValue(it)
        }

        val repoSystem = container.lookup(RepositorySystem::class.java, "default")
        val remoteRepositoryManager = container.lookup(RemoteRepositoryManager::class.java, "default")
        val repositoryLayoutProvider = container.lookup(RepositoryLayoutProvider::class.java, "default")
        val repositoryConnectorProvider = container.lookup(RepositoryConnectorProvider::class.java, "default")
        val transporterProvider = container.lookup(TransporterProvider::class.java, "default")

        // Create an artifact descriptor to get the list of repositories from the related POM file.
        val artifactDescriptorRequest = ArtifactDescriptorRequest(artifact, repositories, "project")
        val artifactDescriptorResult = repoSystem
            .readArtifactDescriptor(repositorySystemSession, artifactDescriptorRequest)
        val allRepositories = (artifactDescriptorResult.repositories + repositories).distinct()

        // Filter out local repositories, as remote artifacts should never point to files on the local disk.
        val remoteRepositories = allRepositories.filterNot {
            // Some (Linux) file URIs do not start with "file://" but look like "file:/opt/android-sdk-linux".
            it.url.startsWith("file:/")
        }

        if (log.delegate.isDebugEnabled) {
            val localRepositories = allRepositories - remoteRepositories
            if (localRepositories.isNotEmpty()) {
                // No need to use curly-braces-syntax for logging here as the log level check is already done above.
                log.debug("Ignoring local repositories $localRepositories.")
            }
        }

        log.debug { "Searching for '$artifact' in $remoteRepositories." }

        // Check the remote repositories for the availability of the artifact.
        // TODO: Currently only the first hit is stored, could query the rest of the repositories if required.
        remoteRepositories.forEach { repository ->
            val repositoryLayout = try {
                repositoryLayoutProvider.newRepositoryLayout(repositorySystemSession, repository)
            } catch (e: NoRepositoryLayoutException) {
                e.showStackTrace()

                log.warn { "Could not search for '$artifact' in '$repository': ${e.collectMessagesAsString()}" }

                return@forEach
            }

            val remoteLocation = repositoryLayout.getLocation(artifact, false)
            log.debug { "Remote location for '$artifact': $remoteLocation" }

            val snapshot = artifact.isSnapshot
            val policy = remoteRepositoryManager.getPolicy(repositorySystemSession, repository, !snapshot, snapshot)

            val localPath = repositorySystemSession.localRepositoryManager
                .getPathForRemoteArtifact(artifact, repository, "project")
            val downloadFile = File(repositorySystemSession.localRepositoryManager.repository.basedir, localPath)

            val artifactDownload = ArtifactDownload(artifact, "project", downloadFile, policy.checksumPolicy)
            artifactDownload.isExistenceCheck = true
            artifactDownload.listener = object : AbstractTransferListener() {
                override fun transferFailed(event: TransferEvent?) {
                    log.debug { "Transfer failed: $event" }
                }

                override fun transferSucceeded(event: TransferEvent?) {
                    log.debug { "Transfer succeeded: $event" }
                }
            }

            try {
                wrapMavenSession {
                    val repositoryConnector = repositoryConnectorProvider
                        .newRepositoryConnector(repositorySystemSession, repository)
                    repositoryConnector.get(listOf(artifactDownload), null)
                }
            } catch (e: NoRepositoryConnectorException) {
                e.showStackTrace()

                log.warn { "Could not create connector for repository '$repository': ${e.collectMessagesAsString()}" }

                return@forEach
            }

            if (artifactDownload.exception == null) {
                log.debug { "Found '$artifact' in '$repository'." }

                // TODO: Could store multiple checksums in model instead of only the first.
                val checksums = repositoryLayout.getChecksums(artifact, false, remoteLocation)
                log.debug { "Checksums: $checksums" }

                val checksum = checksums.first()
                val tempFile = File.createTempFile("ort", "checksum-${checksum.algorithm}")

                val transporter = transporterProvider.newTransporter(repositorySystemSession, repository)

                @Suppress("TooGenericExceptionCaught")
                val actualChecksum = try {
                    transporter.get(GetTask(checksum.location).setDataFile(tempFile))

                    // Sometimes the checksum file contains a path after the actual checksum, so strip everything after
                    // the first space.
                    tempFile.useLines { it.first().substringBefore(' ') }
                } catch (e: Exception) {
                    e.showStackTrace()

                    log.warn { "Could not get checksum for '$artifact': ${e.collectMessagesAsString()}" }

                    // Fall back to an empty checksum string.
                    ""
                }

                if (!tempFile.delete()) {
                    log.warn { "Unable to delete temporary file '$tempFile'." }
                }

                val downloadUrl = "${repository.url.trimEnd('/')}/$remoteLocation"
                val hash = if (actualChecksum.isBlank()) Hash.NONE else Hash.create(actualChecksum, checksum.algorithm)
                return RemoteArtifact(downloadUrl, hash).also {
                    log.debug { "Writing remote artifact for '$artifact' to disk cache." }
                    remoteArtifactCache.write(artifact.toString(), yamlMapper.writeValueAsString(it))
                }
            } else {
                log.debug {
                    "Could not find '$artifact' in '$repository': " +
                            artifactDownload.exception.collectMessagesAsString()
                }
            }
        }

        log.warn { "Unable to find '$artifact' in any of ${remoteRepositories.map { it.url }}." }

        return RemoteArtifact.EMPTY.also {
            log.debug { "Writing empty remote artifact for '$artifact' to disk cache." }
            remoteArtifactCache.write(artifact.toString(), yamlMapper.writeValueAsString(it))
        }
    }

    /**
     * Create an instance of [Package] from the information found in a POM file.
     *
     * @param artifact The artifact for which the [Package] will be created.
     * @param repositories A list of remote repositories to search for [artifact].
     * @param localProjects Instances of local [MavenProject]s which have already been created, mapped by their
     *                      identifier. If a project is found in this map no remote repositories will be queried and the
     *                      VCS working tree information will be used to create the [Package].
     * @param sbtMode If enabled assume that the POM files referenced from the [localProjects] were generated by
     *                "sbt makePom" and are therefore located below a "target" subdirectory of the actual project.
     */
    fun parsePackage(
        artifact: Artifact, repositories: List<RemoteRepository>,
        localProjects: Map<String, MavenProject> = emptyMap(), sbtMode: Boolean = false
    ): Package {
        val mavenRepositorySystem = container.lookup(MavenRepositorySystem::class.java, "default")
        val projectBuilder = container.lookup(ProjectBuilder::class.java, "default")
        val projectBuildingRequest = createProjectBuildingRequest(false)

        projectBuildingRequest.remoteRepositories = repositories.map { repo ->
            // As the ID might be used as the key when generating a metadata file name, avoid the URL being used as the
            // ID as the URL is likely to contain characters like ":" which not all file systems support.
            val id = repo.id.takeUnless { it == repo.url } ?: repo.host
            mavenRepositorySystem.createRepository(repo.url, id, true, null, true, null, null)
        } + projectBuildingRequest.remoteRepositories

        val localProject = localProjects[artifact.identifier()]

        val mavenProject = localProject?.also {
            log.info { "'${artifact.identifier()}' refers to a local project." }
        } ?: artifact.let {
            val pomArtifact = mavenRepositorySystem
                .createArtifact(it.groupId, it.artifactId, it.version, "", "pom")

            try {
                wrapMavenSession {
                    projectBuilder.build(pomArtifact, projectBuildingRequest).project
                }
            } catch (e: ProjectBuildingException) {
                e.showStackTrace()

                val failedProject = e.results?.find { projectBuildingResult ->
                    projectBuildingResult.projectId == it.identifier()
                }

                if (failedProject != null) {
                    log.warn {
                        "There was an error building '${it.identifier()}', continuing with the incompletely built " +
                                "project: ${e.collectMessagesAsString()}"
                    }
                    failedProject.project
                } else {
                    log.error { "Failed to build '${it.identifier()}': ${e.collectMessagesAsString()}" }
                    throw e
                }
            }
        }

        val binaryRemoteArtifact = localProject?.let {
            RemoteArtifact.EMPTY
        } ?: requestRemoteArtifact(artifact, repositories)

        val sourceRemoteArtifact = when {
            localProject != null -> RemoteArtifact.EMPTY
            artifact.extension == "pom" -> binaryRemoteArtifact
            else -> {
                val sourceArtifact = artifact.let {
                    DefaultArtifact(it.groupId, it.artifactId, "sources", "jar", it.version)
                }

                requestRemoteArtifact(sourceArtifact, repositories)
            }
        }

        val vcsFromPackage = parseVcsInfo(mavenProject)
        val localDirectory = localProject?.file?.parentFile?.let {
            // TODO: Once SBT is implemented independently of Maven we can completely remove the "localProjects"
            //       parameter to this function as no other caller is actually using it.
            if (sbtMode) {
                it.searchUpwardsForSubdirectory("target") ?: it
            } else {
                it
            }
        }

        val browsableScmUrl = MavenSupport.getOriginalScm(mavenProject)?.url
        val homepageUrl = mavenProject.url
        val vcsFallbackUrls = listOfNotNull(browsableScmUrl, homepageUrl).toTypedArray()

        val vcsProcessed = localDirectory?.let {
            PackageManager.processProjectVcs(it, vcsFromPackage, *vcsFallbackUrls)
        } ?: PackageManager.processPackageVcs(vcsFromPackage, *vcsFallbackUrls)

        return Package(
            id = Identifier(
                type = "Maven",
                namespace = mavenProject.groupId,
                name = mavenProject.artifactId,
                version = mavenProject.version
            ),
            declaredLicenses = parseLicenses(mavenProject),
            description = mavenProject.description.orEmpty(),
            homepageUrl = homepageUrl.orEmpty(),
            binaryArtifact = binaryRemoteArtifact,
            sourceArtifact = sourceRemoteArtifact,
            vcs = vcsFromPackage,
            vcsProcessed = vcsProcessed
        )
    }

    /**
     * Create a [MavenSession] and setup the [LegacySupport] and [SessionScope] because this is required to load
     * extensions using Maven Wagon.
     */
    private fun <R> wrapMavenSession(block: () -> R): R {
        val request = DefaultMavenExecutionRequest()
        val result = DefaultMavenExecutionResult()

        @Suppress("DEPRECATION")
        val mavenSession = MavenSession(container, repositorySystemSession, request, result)

        val legacySupport = container.lookup(LegacySupport::class.java, "default")
        legacySupport.session = mavenSession

        val sessionScope = container.lookup(SessionScope::class.java, "default")
        sessionScope.enter()

        try {
            sessionScope.seed(MavenSession::class.java, mavenSession)
            return block()
        } finally {
            sessionScope.exit()
            legacySupport.session = null
        }
    }
}

/**
 * Several Maven repositories have disabled HTTP access and require HTTPS now. To be able to still analyze old Maven
 * projects that use the HTTP URLs, this [MirrorSelector] implementation automatically creates an HTTPS mirror if a
 * [RemoteRepository] uses a disabled HTTP URL. Without that Maven would abort with an exception as soon as it tries to
 * download an Artifact from any of those repositories.
 *
 * **See also:**
 *
 * [GitHub Security Lab issue](https://github.com/github/security-lab/issues/21)
 * [Medium article](https://medium.com/p/d069d253fe23)
 */
class HttpsMirrorSelector(private val originalMirrorSelector: MirrorSelector?) : MirrorSelector {
    companion object {
        private val DISABLED_HTTP_REPOSITORY_URLS = listOf(
            "http://jcenter.bintray.com",
            "http://repo.maven.apache.org",
            "http://repo1.maven.org",
            "http://repo.spring.io"
        )
    }

    override fun getMirror(repository: RemoteRepository?): RemoteRepository? {
        originalMirrorSelector?.getMirror(repository)?.let { return it }

        if (repository == null || DISABLED_HTTP_REPOSITORY_URLS.none { repository.url.startsWith(it) }) return null

        log.info {
            "HTTP access to ${repository.id} (${repository.url}) was disabled. Automatically switching to HTTPS."
        }

        return RemoteRepository.Builder(
            "${repository.id}-https-mirror",
            repository.contentType,
            "https://${repository.url.removePrefix("http://")}"
        ).apply {
            setRepositoryManager(false)
            setSnapshotPolicy(repository.getPolicy(true))
            setReleasePolicy(repository.getPolicy(false))
            setMirroredRepositories(listOf(repository))
        }.build()
    }
}
