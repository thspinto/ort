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

package org.ossreviewtoolkit.analyzer.managers

import org.ossreviewtoolkit.analyzer.AbstractPackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.analyzer.managers.utils.MavenSupport
import org.ossreviewtoolkit.analyzer.managers.utils.identifier
import org.ossreviewtoolkit.downloader.VersionControlSystem
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageLinkage
import org.ossreviewtoolkit.model.PackageReference
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.Scope
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.model.createAndLogIssue
import org.ossreviewtoolkit.utils.collectMessagesAsString
import org.ossreviewtoolkit.utils.log
import org.ossreviewtoolkit.utils.searchUpwardsForSubdirectory
import org.ossreviewtoolkit.utils.showStackTrace

import java.io.File

import org.apache.maven.project.ProjectBuilder
import org.apache.maven.project.ProjectBuildingException
import org.apache.maven.project.ProjectBuildingResult

import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.graph.DependencyNode
import org.eclipse.aether.repository.WorkspaceReader
import org.eclipse.aether.repository.WorkspaceRepository

/**
 * The [Maven](https://maven.apache.org/) package manager for Java.
 */
class Maven(
    name: String,
    analysisRoot: File,
    analyzerConfig: AnalyzerConfiguration,
    repoConfig: RepositoryConfiguration
) : PackageManager(name, analysisRoot, analyzerConfig, repoConfig) {
    class Factory : AbstractPackageManagerFactory<Maven>("Maven") {
        override val globsForDefinitionFiles = listOf("pom.xml")

        override fun create(
            analysisRoot: File,
            analyzerConfig: AnalyzerConfiguration,
            repoConfig: RepositoryConfiguration
        ) = Maven(managerName, analysisRoot, analyzerConfig, repoConfig)
    }

    private inner class LocalProjectWorkspaceReader : WorkspaceReader {
        private val workspaceRepository = WorkspaceRepository()

        override fun findArtifact(artifact: Artifact) =
            artifact.takeIf { it.extension == "pom" }?.let {
                localProjectBuildingResults[it.identifier()]?.pomFile?.absoluteFile
            }

        override fun findVersions(artifact: Artifact) =
            // Avoid resolution of (SNAPSHOT) versions for local projects.
            localProjectBuildingResults[artifact.identifier()]?.let { listOf(artifact.version) }.orEmpty()

        override fun getRepository() = workspaceRepository
    }

    private val mvn = MavenSupport(LocalProjectWorkspaceReader())

    private val localProjectBuildingResults = mutableMapOf<String, ProjectBuildingResult>()

    private var sbtMode = false

    /**
     * Enable compatibility mode with POM files generated from SBT using "sbt makePom".
     */
    fun enableSbtMode() = also { sbtMode = true }

    override fun beforeResolution(definitionFiles: List<File>) {
        val projectBuilder = mvn.container.lookup(ProjectBuilder::class.java, "default")
        val projectBuildingRequest = mvn.createProjectBuildingRequest(false)
        val projectBuildingResults = try {
            projectBuilder.build(definitionFiles, false, projectBuildingRequest)
        } catch (e: ProjectBuildingException) {
            e.showStackTrace()

            log.warn {
                "There have been issues building the Maven project models, this could lead to errors during " +
                        "dependency analysis: ${e.collectMessagesAsString()}"
            }

            e.results
        }

        projectBuildingResults.forEach { projectBuildingResult ->
            if (projectBuildingResult.project == null) {
                log.warn {
                    "Project for POM file '${projectBuildingResult.pomFile.absolutePath}' could not be built:\n" +
                            projectBuildingResult.problems.joinToString("\n")
                }
            } else {
                val project = projectBuildingResult.project
                val identifier = "${project.groupId}:${project.artifactId}:${project.version}"

                localProjectBuildingResults[identifier] = projectBuildingResult
            }
        }
    }

    override fun resolveDependencies(definitionFile: File): ProjectAnalyzerResult? {
        val workingDir = definitionFile.parentFile
        val projectBuildingResult = mvn.buildMavenProject(definitionFile)
        val mavenProject = projectBuildingResult.project
        val packages = mutableMapOf<String, Package>()
        val scopes = mutableMapOf<String, Scope>()

        projectBuildingResult.dependencyResolutionResult.dependencyGraph.children.forEach { node ->
            val scopeName = node.dependency.scope
            val scope = scopes.getOrPut(scopeName) {
                Scope(scopeName, sortedSetOf())
            }

            scope.dependencies += parseDependency(node, packages)
        }

        val vcsFromPackage = MavenSupport.parseVcsInfo(mavenProject)

        // If running in SBT mode expect that POM files were generated in a "target" subdirectory and that the correct
        // project directory is the parent directory of this.
        val projectDir = if (sbtMode) {
            workingDir.searchUpwardsForSubdirectory("target") ?: workingDir
        } else {
            workingDir
        }

        val browsableScmUrl = MavenSupport.getOriginalScm(mavenProject)?.url
        val homepageUrl = mavenProject.url
        val vcsFallbackUrls = listOfNotNull(browsableScmUrl, homepageUrl).toTypedArray()

        val project = Project(
            id = Identifier(
                type = managerName,
                namespace = mavenProject.groupId,
                name = mavenProject.artifactId,
                version = mavenProject.version
            ),
            definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path,
            declaredLicenses = MavenSupport.parseLicenses(mavenProject),
            vcs = vcsFromPackage,
            vcsProcessed = processProjectVcs(projectDir, vcsFromPackage, *vcsFallbackUrls),
            homepageUrl = homepageUrl.orEmpty(),
            scopes = scopes.values.toSortedSet()
        )

        return ProjectAnalyzerResult(project, packages.values.mapTo(sortedSetOf()) { it.toCuratedPackage() })
    }

    private fun parseDependency(node: DependencyNode, packages: MutableMap<String, Package>): PackageReference {
        val identifier = node.artifact.identifier()

        try {
            val dependencies = node.children.mapNotNull { child ->
                val toolsJarCoordinates = listOf("com.sun:tools:", "jdk.tools:jdk.tools:")
                if (toolsJarCoordinates.any { child.artifact.identifier().startsWith(it) }) {
                    log.info { "Omitting the Java < 1.9 system dependency on 'tools.jar'." }
                    null
                } else {
                    parseDependency(child, packages)
                }
            }.toSortedSet()

            val localProjects = localProjectBuildingResults.mapValues { it.value.project }

            return if (localProjects.contains(identifier)) {
                val id = Identifier(
                    type = managerName,
                    namespace = node.artifact.groupId,
                    name = node.artifact.artifactId,
                    version = node.artifact.version
                )

                log.info { "Dependency '${id.toCoordinates()}' refers to a local project." }

                PackageReference(id, PackageLinkage.PROJECT_DYNAMIC, dependencies)
            } else {
                val pkg = packages.getOrPut(identifier) {
                    // TODO: Omit the "localProjects" argument here once SBT is implemented independently of Maven as at
                    //       this point we know already that "identifier" is not a local project.
                    mvn.parsePackage(node.artifact, node.repositories, localProjects, sbtMode)
                }

                pkg.toReference(dependencies = dependencies)
            }
        } catch (e: ProjectBuildingException) {
            e.showStackTrace()

            return PackageReference(
                Identifier(managerName, node.artifact.groupId, node.artifact.artifactId, node.artifact.version),
                dependencies = sortedSetOf(),
                issues = listOf(
                    createAndLogIssue(
                        source = managerName,
                        message = "Could not get package information for dependency '$identifier': " +
                                e.collectMessagesAsString()
                    )
                )
            )
        }
    }
}
