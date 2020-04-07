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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode

import org.ossreviewtoolkit.analyzer.AbstractPackageManagerFactory
import org.ossreviewtoolkit.analyzer.HTTP_CACHE_PATH
import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.downloader.VersionControlSystem
import org.ossreviewtoolkit.model.EMPTY_JSON_NODE
import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageReference
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.Scope
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.model.jsonMapper
import org.ossreviewtoolkit.utils.CommandLineTool
import org.ossreviewtoolkit.utils.ORT_NAME
import org.ossreviewtoolkit.utils.Os
import org.ossreviewtoolkit.utils.OkHttpClientHelper
import org.ossreviewtoolkit.utils.ProcessCapture
import org.ossreviewtoolkit.utils.collectMessagesAsString
import org.ossreviewtoolkit.utils.getPathFromEnvironment
import org.ossreviewtoolkit.utils.log
import org.ossreviewtoolkit.utils.safeDeleteRecursively
import org.ossreviewtoolkit.utils.showStackTrace
import org.ossreviewtoolkit.utils.textValueOrEmpty

import com.vdurmont.semver4j.Requirement

import java.io.File
import java.io.IOException
import java.lang.IllegalArgumentException
import java.lang.NumberFormatException
import java.net.HttpURLConnection
import java.util.SortedSet

import okhttp3.Request

// The lowest version that supports "--prefer-binary".
const val PIP_VERSION = "18.0"

const val PIPDEPTREE_VERSION = "0.13.2"
private val PHONY_DEPENDENCIES = mapOf(
    "pipdeptree" to "", // A dependency of pipdeptree itself.
    "pkg-resources" to "0.0.0", // Added by a bug with some Ubuntu distributions.
    "setuptools" to "", // A dependency of pipdeptree itself.
    "wheel" to "" // A dependency of pipdeptree itself.
)

private fun isPhonyDependency(name: String, version: String): Boolean =
    PHONY_DEPENDENCIES[name].orEmpty().let { ignoredVersion ->
        PHONY_DEPENDENCIES.containsKey(name) && (ignoredVersion.isEmpty() || version == ignoredVersion)
    }

const val PYDEP_REVISION = "license-and-classifiers"

object VirtualEnv : CommandLineTool {
    override fun command(workingDir: File?) = "virtualenv"

    override fun transformVersion(output: String) =
        // virtualenv could report versions like:
        // 16.6.1
        // virtualenv 20.0.14 from /usr/local/lib/python2.7/dist-packages/virtualenv/__init__.pyc
        output.removePrefix("virtualenv ").substringBefore(' ')

    // Allow to use versions that are known to work. Note that virtualenv bundles a version of pip.
    override fun getVersionRequirement(): Requirement = Requirement.buildIvy("[15.1,20.1[")
}

object PythonVersion : CommandLineTool {
    // To use a specific version of Python on Windows we can use the "py" command with argument "-2" or "-3", see
    // https://docs.python.org/3/installing/#work-with-multiple-versions-of-python-installed-in-parallel.
    override fun command(workingDir: File?) = if (Os.isWindows) "py" else "python3"

    override fun transformVersion(output: String) = output.removePrefix("Python ")

    /**
     * Check all Python files in [workingDir] and return which version of Python they are compatible with. If all files
     * are compatible with Python 3, "3" is returned. If at least one file is incompatible with Python 3, "2" is
     * returned.
     */
    fun getPythonVersion(workingDir: File): Int {
        val scriptFile = File.createTempFile("python_compatibility", ".py")
        scriptFile.writeBytes(javaClass.getResource("/scripts/python_compatibility.py").readBytes())

        try {
            // The helper script itself always has to be run with Python 3.
            val scriptCmd = if (Os.isWindows) {
                run("-3", scriptFile.path, "-d", workingDir.path)
            } else {
                run(scriptFile.path, "-d", workingDir.path)
            }

            return scriptCmd.stdout.toInt()
        } finally {
            if (!scriptFile.delete()) {
                log.warn { "Helper script file '$scriptFile' could not be deleted." }
            }
        }
    }

    /**
     * Return the absolute path to the Python interpreter for the given [version]. This is helpful as esp. on Windows
     * different Python versions can by installed in arbitrary locations, and the Python executable is even usually
     * called the same in those locations.
     */
    fun getPythonInterpreter(version: Int): String =
        if (Os.isWindows) {
            val scriptFile = File.createTempFile("python_interpreter", ".py")
            scriptFile.writeBytes(javaClass.getResource("/scripts/python_interpreter.py").readBytes())

            try {
                run("-$version", scriptFile.path).stdout
            } finally {
                if (!scriptFile.delete()) {
                    log.warn { "Helper script file '${scriptFile.path}' could not be deleted." }
                }
            }
        } else {
            getPathFromEnvironment("python$version")?.path.orEmpty()
        }
}

/**
 * The [PIP](https://pip.pypa.io/) package manager for Python. Also see
 * [install_requires vs requirements files](https://packaging.python.org/discussions/install-requires-vs-requirements/)
 * and [setup.py vs. requirements.txt](https://caremad.io/posts/2013/07/setup-vs-requirement/).
 */
class Pip(
    name: String,
    analysisRoot: File,
    analyzerConfig: AnalyzerConfiguration,
    repoConfig: RepositoryConfiguration
) : PackageManager(name, analysisRoot, analyzerConfig, repoConfig), CommandLineTool {
    class Factory : AbstractPackageManagerFactory<Pip>("PIP") {
        override val globsForDefinitionFiles = listOf("requirements*.txt", "setup.py")

        override fun create(
            analysisRoot: File,
            analyzerConfig: AnalyzerConfiguration,
            repoConfig: RepositoryConfiguration
        ) = Pip(managerName, analysisRoot, analyzerConfig, repoConfig)
    }

    companion object {
        private val INSTALL_OPTIONS = arrayOf(
            "--no-warn-conflicts",
            "--prefer-binary"
        )

        // TODO: Need to replace this hard-coded list of domains with e.g. a command line option.
        private val TRUSTED_HOSTS = listOf(
            "pypi.org",
            "pypi.python.org" // Legacy
        ).flatMap { listOf("--trusted-host", it) }.toTypedArray()

        /**
         * Return a version string with leading zeros of components stripped.
         */
        fun stripLeadingZerosFromVersion(version: String) =
            version.split(".").joinToString(".") {
                try {
                    it.toInt().toString()
                } catch (e: NumberFormatException) {
                    it
                }
            }
    }

    override fun command(workingDir: File?) = "pip"

    override fun transformVersion(output: String) = output.removePrefix("pip ").substringBefore(' ')

    private fun runPipInVirtualEnv(virtualEnvDir: File, workingDir: File, vararg commandArgs: String) =
        runInVirtualEnv(virtualEnvDir, workingDir, command(workingDir), *TRUSTED_HOSTS, *commandArgs)

    private fun runInVirtualEnv(virtualEnvDir: File, workingDir: File, commandName: String, vararg commandArgs: String):
            ProcessCapture {
        val binDir = if (Os.isWindows) "Scripts" else "bin"
        var command = File(virtualEnvDir, binDir + File.separator + commandName)

        if (Os.isWindows && command.extension.isEmpty()) {
            // On Windows specifying the extension is optional, so try them in order.
            val extensions = Os.env["PATHEXT"]?.splitToSequence(File.pathSeparatorChar).orEmpty()
            val commandWin = extensions.map { File(command.path + it.toLowerCase()) }.find { it.isFile }
            if (commandWin != null) {
                command = commandWin
            }
        }

        // TODO: Maybe work around long shebang paths in generated scripts within a virtualenv by calling the Python
        //       executable in the virtualenv directly, see https://github.com/pypa/virtualenv/issues/997.
        val process = ProcessCapture(workingDir, command.path, *commandArgs)
        log.debug { process.stdout }
        return process
    }

    override fun beforeResolution(definitionFiles: List<File>) =
        VirtualEnv.checkVersion(analyzerConfig.ignoreToolVersions)

    @Suppress("LongMethod")
    override fun resolveDependencies(definitionFile: File): ProjectAnalyzerResult? {
        // For an overview, dependency resolution involves the following steps:
        // 1. Install dependencies via pip (inside a virtualenv, for isolation from globally installed packages).
        // 2. Get meta-data about the local project via pydep (only for setup.py-based projects).
        // 3. Get the hierarchy of dependencies via pipdeptree.
        // 4. Get additional remote package meta-data via PyPIJSON.

        val workingDir = definitionFile.parentFile
        val virtualEnvDir = setupVirtualEnv(workingDir, definitionFile)

        // List all packages installed locally in the virtualenv.
        val pipdeptree = runInVirtualEnv(virtualEnvDir, workingDir, "pipdeptree", "-l", "--json-tree")

        // Install pydep after running any other command but before looking at the dependencies because it
        // downgrades pip to version 7.1.2. Use it to get meta-information from about the project from setup.py. As
        // pydep is not on PyPI, install it from Git instead.
        val pydepUrl = "git+https://github.com/oss-review-toolkit/pydep@$PYDEP_REVISION"
        val pip = if (Os.isWindows) {
            // On Windows, in-place pip up- / downgrades require pip to be wrapped by "python -m", see
            // https://github.com/pypa/pip/issues/1299.
            runInVirtualEnv(
                virtualEnvDir, workingDir, "python", "-m", command(workingDir),
                *TRUSTED_HOSTS, "install", pydepUrl
            )
        } else {
            runPipInVirtualEnv(virtualEnvDir, workingDir, "install", pydepUrl)
        }
        pip.requireSuccess()

        var declaredLicenses: SortedSet<String> = sortedSetOf<String>()

        // First try to get meta-data from "setup.py" in any case, even for "requirements.txt" projects.
        val (setupName, setupVersion, setupHomepage) = if (File(workingDir, "setup.py").isFile) {
            val pydep = if (Os.isWindows) {
                // On Windows, the script itself is not executable, so we need to wrap the call by "python".
                runInVirtualEnv(
                    virtualEnvDir, workingDir, "python",
                    virtualEnvDir.path + "\\Scripts\\pydep-run.py", "info", "."
                )
            } else {
                runInVirtualEnv(virtualEnvDir, workingDir, "pydep-run.py", "info", ".")
            }
            pydep.requireSuccess()

            // What pydep actually returns as "repo_url" is either setup.py's
            // - "url", denoting the "home page for the package", or
            // - "download_url", denoting the "location where the package may be downloaded".
            // So the best we can do is to map this the project's homepage URL.
            jsonMapper.readTree(pydep.stdout).let {
                declaredLicenses = getDeclaredLicenses(it)
                listOf(
                    it["project_name"].textValue(), it["version"].textValueOrEmpty(),
                    it["repo_url"].textValueOrEmpty()
                )
            }
        } else {
            listOf("", "", "")
        }

        // Try to get additional information from any "requirements.txt" file.
        val (requirementsName, requirementsVersion, requirementsSuffix) = if (definitionFile.name != "setup.py") {
            val pythonVersionLines = definitionFile.readLines().filter { it.contains("python_version") }
            if (pythonVersionLines.isNotEmpty()) {
                log.debug {
                    "Some dependencies have Python version requirements:\n$pythonVersionLines"
                }
            }

            // In case of "requirements*.txt" there is no meta-data at all available, so use the parent directory name
            // plus what "*" expands to as the project name and the VCS revision, if any, as the project version.
            val suffix = definitionFile.name.removePrefix("requirements").removeSuffix(".txt")
            val name = definitionFile.parentFile.name + suffix

            val version = VersionControlSystem.getCloneInfo(workingDir).revision

            listOf(name, version, suffix)
        } else {
            listOf("", "", "")
        }

        // Amend information from "setup.py" with that from "requirements.txt".
        val projectName = when (Pair(setupName.isNotEmpty(), requirementsName.isNotEmpty())) {
            Pair(true, false) -> setupName
            // In case of only a requirements file without further meta-data, use the relative path to the analyzer
            // root as a unique project name.
            Pair(false, true) -> definitionFile.relativeTo(analysisRoot).invariantSeparatorsPath
            Pair(true, true) -> "$setupName-requirements$requirementsSuffix"
            else -> throw IllegalArgumentException("Unable to determine a project name for '$definitionFile'.")
        }
        val projectVersion = setupVersion.takeIf { it.isNotEmpty() } ?: requirementsVersion

        val packages = sortedSetOf<Package>()
        val installDependencies = sortedSetOf<PackageReference>()

        if (pipdeptree.isSuccess) {
            val fullDependencyTree = jsonMapper.readTree(pipdeptree.stdout)

            val projectDependencies = if (definitionFile.name == "setup.py") {
                // The tree contains a root node for the project itself and pipdeptree's dependencies are also at the
                // root next to it, as siblings.
                fullDependencyTree.find {
                    it["package_name"].textValue() == projectName
                }?.get("dependencies") ?: run {
                    log.info { "The '$projectName' project does not declare any dependencies." }
                    EMPTY_JSON_NODE
                }
            } else {
                // The tree does not contain a node for the project itself. Its dependencies are on the root level
                // together with the dependencies of pipdeptree itself, which we need to filter out.
                fullDependencyTree.filterNot {
                    isPhonyDependency(it["package_name"].textValue(), it["installed_version"].textValueOrEmpty())
                }
            }

            val packageTemplates = sortedSetOf<Package>()
            parseDependencies(projectDependencies, packageTemplates, installDependencies)

            // Enrich the package templates with additional meta-data from PyPI.
            packageTemplates.mapTo(packages) { pkg ->
                // See https://wiki.python.org/moin/PyPIJSON.
                val pkgRequest = Request.Builder()
                    .get()
                    .url("https://pypi.org/pypi/${pkg.id.name}/${pkg.id.version}/json")
                    .build()

                OkHttpClientHelper.execute(HTTP_CACHE_PATH, pkgRequest).use { response ->
                    val body = response.body?.string()?.trim()

                    if (response.code != HttpURLConnection.HTTP_OK || body.isNullOrEmpty()) {
                        log.warn { "Unable to retrieve PyPI meta-data for package '${pkg.id.toCoordinates()}'." }
                        if (body != null) {
                            log.warn { "The response was '$body' (code ${response.code})." }
                        }

                        // Fall back to returning the original package data.
                        return@use pkg
                    }

                    val pkgData = try {
                        jsonMapper.readTree(body)!!
                    } catch (e: IOException) {
                        e.showStackTrace()

                        log.warn {
                            "Unable to parse PyPI meta-data for package '${pkg.id.toCoordinates()}': " +
                                    e.collectMessagesAsString()
                        }

                        // Fall back to returning the original package data.
                        return@use pkg
                    }

                    pkgData["info"]?.let { pkgInfo ->
                        val pkgDescription = pkgInfo["summary"]?.textValue() ?: pkg.description
                        val pkgHomepage = pkgInfo["home_page"]?.textValue() ?: pkg.homepageUrl

                        val pkgRelease = pkgData["releases"]?.let { pkgReleases ->
                            val pkgVersion = pkgReleases.fieldNames().asSequence().find {
                                stripLeadingZerosFromVersion(it) == pkg.id.version
                            }

                            pkgReleases[pkgVersion]
                        } as? ArrayNode

                        // Amend package information with more details.
                        Package(
                            id = pkg.id,
                            declaredLicenses = getDeclaredLicenses(pkgInfo),
                            description = pkgDescription,
                            homepageUrl = pkgHomepage,
                            binaryArtifact = if (pkgRelease != null) {
                                getBinaryArtifact(pkg, pkgRelease)
                            } else {
                                pkg.binaryArtifact
                            },
                            sourceArtifact = if (pkgRelease != null) {
                                getSourceArtifact(pkgRelease)
                            } else {
                                pkg.sourceArtifact
                            },
                            vcs = pkg.vcs,
                            vcsProcessed = processPackageVcs(pkg.vcs, pkgHomepage)
                        )
                    } ?: run {
                        log.warn {
                            "PyPI meta-data for package '${pkg.id.toCoordinates()}' does not provide any information."
                        }

                        // Fall back to returning the original package data.
                        pkg
                    }
                }
            }
        } else {
            log.error {
                "Unable to determine dependencies for project in directory '$workingDir':\n${pipdeptree.stderr}"
            }
        }

        // TODO: Handle "extras" and "tests" dependencies.
        val scopes = sortedSetOf(
            Scope("install", installDependencies)
        )

        val project = Project(
            id = Identifier(
                type = managerName,
                namespace = "",
                name = projectName,
                version = projectVersion
            ),
            definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path,
            declaredLicenses = declaredLicenses,
            vcs = VcsInfo.EMPTY,
            vcsProcessed = processProjectVcs(workingDir, VcsInfo.EMPTY, setupHomepage),
            homepageUrl = setupHomepage,
            scopes = scopes
        )

        // Remove the virtualenv by simply deleting the directory.
        virtualEnvDir.safeDeleteRecursively()

        return ProjectAnalyzerResult(project, packages.mapTo(sortedSetOf()) { it.toCuratedPackage() })
    }

    private fun getBinaryArtifact(pkg: Package, releaseNode: ArrayNode): RemoteArtifact {
        // Prefer python wheels and fall back to the first entry (probably a sdist).
        val binaryArtifact = releaseNode.asSequence().find {
            it["packagetype"].textValue() == "bdist_wheel"
        } ?: releaseNode[0]

        val url = binaryArtifact["url"]?.textValue() ?: pkg.binaryArtifact.url
        val hash = binaryArtifact["md5_digest"]?.textValue()?.let { Hash.create(it) } ?: pkg.binaryArtifact.hash

        return RemoteArtifact(url, hash)
    }

    private fun getSourceArtifact(releaseNode: ArrayNode): RemoteArtifact {
        val sourceArtifacts = releaseNode.asSequence().filter {
            it["packagetype"].textValue() == "sdist"
        }

        if (sourceArtifacts.count() == 0) return RemoteArtifact.EMPTY

        val sourceArtifact = sourceArtifacts.find {
            it["filename"].textValue().endsWith(".tar.bz2")
        } ?: sourceArtifacts.elementAt(0)

        val url = sourceArtifact["url"]?.textValue() ?: return RemoteArtifact.EMPTY
        val hash = sourceArtifact["md5_digest"]?.textValue() ?: return RemoteArtifact.EMPTY

        return RemoteArtifact(url, Hash.create(hash))
    }

    private fun getDeclaredLicenses(pkgInfo: JsonNode): SortedSet<String> {
        val declaredLicenses = sortedSetOf<String>()

        // Use the top-level license field as well as the license classifiers as the declared licenses.
        setOf(pkgInfo["license"]).mapNotNullTo(declaredLicenses) { license ->
            license?.textValue()?.takeUnless { it.isBlank() || it == "UNKNOWN" }
        }

        // Example license classifier:
        // "License :: OSI Approved :: GNU Library or Lesser General Public License (LGPL)"
        pkgInfo["classifiers"]?.mapNotNullTo(declaredLicenses) {
            val classifier = it.textValue().split(" :: ")
            classifier.takeIf { it.first() == "License" }?.last()
        }

        return declaredLicenses
    }

    private fun setupVirtualEnv(workingDir: File, definitionFile: File): File {
        // Create an out-of-tree virtualenv.
        log.info { "Creating a virtualenv for the '${workingDir.name}' project directory..." }

        // Try to determine the Python version the project requires.
        var projectPythonVersion = PythonVersion.getPythonVersion(workingDir)

        log.info { "Trying to install dependencies using Python $projectPythonVersion..." }

        var virtualEnvDir = createVirtualEnv(workingDir, projectPythonVersion)
        var install = installDependencies(workingDir, definitionFile, virtualEnvDir)

        if (install.isError) {
            log.debug {
                // pip writes the real error message to stdout instead of stderr.
                "First try to install dependencies using Python $projectPythonVersion failed with:\n${install.stdout}"
            }

            // If there was a problem maybe the required Python version was detected incorrectly, so simply try again
            // with the other version.
            projectPythonVersion = when (projectPythonVersion) {
                2 -> 3
                3 -> 2
                else -> throw IllegalArgumentException("Unsupported Python version $projectPythonVersion.")
            }

            log.info { "Falling back to trying to install dependencies using Python $projectPythonVersion..." }

            virtualEnvDir = createVirtualEnv(workingDir, projectPythonVersion)
            install = installDependencies(workingDir, definitionFile, virtualEnvDir)

            if (install.isError) {
                // pip writes the real error message to stdout instead of stderr.
                throw IOException(install.stdout)
            }
        }

        log.info {
            "Successfully installed dependencies for project '$definitionFile' using Python $projectPythonVersion."
        }

        return virtualEnvDir
    }

    private fun createVirtualEnv(workingDir: File, pythonVersion: Int): File {
        val virtualEnvDir = createTempDir(ORT_NAME, "${workingDir.name}-virtualenv")

        val pythonInterpreter = PythonVersion.getPythonInterpreter(pythonVersion)
        ProcessCapture(workingDir, "virtualenv", virtualEnvDir.path, "-p", pythonInterpreter).requireSuccess()

        return virtualEnvDir
    }

    private fun installDependencies(workingDir: File, definitionFile: File, virtualEnvDir: File): ProcessCapture {
        // Ensure to have installed a version of pip that is know to work for us.
        var pip = if (Os.isWindows) {
            // On Windows, in-place pip up- / downgrades require pip to be wrapped by "python -m", see
            // https://github.com/pypa/pip/issues/1299.
            runInVirtualEnv(
                virtualEnvDir, workingDir, "python", "-m", command(workingDir),
                *TRUSTED_HOSTS, "install", "pip==$PIP_VERSION"
            )
        } else {
            runPipInVirtualEnv(virtualEnvDir, workingDir, "install", "pip==$PIP_VERSION")
        }
        pip.requireSuccess()

        // Install pipdeptree inside the virtualenv as that's the only way to make it report only the project's
        // dependencies instead of those of all (globally) installed packages, see
        // https://github.com/naiquevin/pipdeptree#known-issues.
        // We only depend on pipdeptree to be at least version 0.5.0 for JSON output, but we stick to a fixed
        // version to be sure to get consistent results.
        pip = runPipInVirtualEnv(virtualEnvDir, workingDir, "install", "pipdeptree==$PIPDEPTREE_VERSION")
        pip.requireSuccess()

        // TODO: Find a way to make installation of packages with native extensions work on Windows where often the
        //       appropriate compiler is missing / not set up, e.g. by using pre-built packages from
        //       http://www.lfd.uci.edu/~gohlke/pythonlibs/
        pip = if (definitionFile.name == "setup.py") {
            // Note that this only installs required "install" dependencies, not "extras" or "tests" dependencies.
            runPipInVirtualEnv(virtualEnvDir, workingDir, "install", *INSTALL_OPTIONS, ".")
        } else {
            // In "setup.py"-speak, "requirements.txt" just contains required "install" dependencies.
            runPipInVirtualEnv(
                virtualEnvDir, workingDir, "install", *INSTALL_OPTIONS, "-r",
                definitionFile.name
            )
        }

        // TODO: Consider logging a warning instead of an error if the command is run on a file that likely belongs to
        //       a test.
        with(pip) {
            if (isError) {
                log.error { errorMessage }
            }
        }

        return pip
    }

    private fun parseDependencies(
        dependencies: Iterable<JsonNode>,
        allPackages: SortedSet<Package>, installDependencies: SortedSet<PackageReference>
    ) {
        dependencies.forEach { dependency ->
            val name = dependency["package_name"].textValue()
            val version = dependency["installed_version"].textValue()

            val pkg = Package(
                id = Identifier(
                    type = "PyPI",
                    namespace = "",
                    name = name,
                    version = version
                ),
                declaredLicenses = sortedSetOf(),
                description = "",
                homepageUrl = "",
                binaryArtifact = RemoteArtifact.EMPTY,
                sourceArtifact = RemoteArtifact.EMPTY,
                vcs = VcsInfo.EMPTY
            )
            allPackages += pkg

            val packageRef = pkg.toReference()
            installDependencies += packageRef

            parseDependencies(dependency["dependencies"], allPackages, packageRef.dependencies)
        }
    }
}
