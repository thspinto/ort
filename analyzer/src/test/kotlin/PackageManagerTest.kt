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

package org.ossreviewtoolkit.analyzer

import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.WordSpec

import java.io.File

import org.ossreviewtoolkit.analyzer.managers.*
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.VcsType

class PackageManagerTest : WordSpec({
    val projectDir = File("src/funTest/assets/projects/synthetic/all-managers").absoluteFile

    "findManagedFiles" should {
        "find all managed files" {
            val managedFiles = PackageManager.findManagedFiles(projectDir)

            // The test project contains at least one file per package manager, so the result should also contain an
            // entry for each package manager.
            managedFiles.keys shouldBe PackageManager.ALL.toSet()

            // The keys in expected and actual maps of definition files are different instances of package manager
            // factories. So to compare values use the package manager names as keys instead.
            val managedFilesByName = managedFiles.mapKeys { (manager, _) ->
                manager.managerName
            }

            managedFilesByName["Bower"] shouldBe listOf(File(projectDir, "bower.json"))
            managedFilesByName["Bundler"] shouldBe listOf(File(projectDir, "Gemfile"))
            managedFilesByName["Cargo"] shouldBe listOf(File(projectDir, "Cargo.toml"))
            managedFilesByName["Conan"] shouldBe listOf(File(projectDir, "conanfile.py"))
            managedFilesByName["DotNet"] shouldBe listOf(File(projectDir, "test.csproj"))
            managedFilesByName["GoDep"] shouldBe listOf(File(projectDir, "Gopkg.toml"))
            managedFilesByName["GoMod"] shouldBe listOf(File(projectDir, "go.mod"))
            managedFilesByName["Gradle"] shouldBe listOf(File(projectDir, "build.gradle"))
            managedFilesByName["Maven"] shouldBe listOf(File(projectDir, "pom.xml"))
            managedFilesByName["NPM"] shouldBe listOf(File(projectDir, "package.json"))
            managedFilesByName["NuGet"] shouldBe listOf(File(projectDir, "packages.config"))
            managedFilesByName["PhpComposer"] shouldBe listOf(File(projectDir, "composer.json"))
            managedFilesByName["PIP"] shouldBe listOf(File(projectDir, "setup.py"))
            managedFilesByName["Pipenv"] shouldBe listOf(File(projectDir, "Pipfile.lock"))
            managedFilesByName["Pub"] shouldBe listOf(File(projectDir, "pubspec.yaml"))
            managedFilesByName["SBT"] shouldBe listOf(File(projectDir, "build.sbt"))
            managedFilesByName["Stack"] shouldBe listOf(File(projectDir, "stack.yaml"))
            managedFilesByName["Yarn"] shouldBe listOf(File(projectDir, "package.json"))
        }

        "find only files for active package managers" {
            val managedFiles = PackageManager.findManagedFiles(
                projectDir,
                listOf(Gradle.Factory(), Pip.Factory(), Sbt.Factory())
            )

            managedFiles.size shouldBe 3

            // The keys in expected and actual maps of definition files are different instances of package manager
            // factories. So to compare values use the package manager names as keys instead.
            val managedFilesByName = managedFiles.mapKeys { (manager, _) ->
                manager.managerName
            }

            managedFilesByName["Gradle"] shouldBe listOf(File(projectDir, "build.gradle"))
            managedFilesByName["PIP"] shouldBe listOf(File(projectDir, "setup.py"))
            managedFilesByName["SBT"] shouldBe listOf(File(projectDir, "build.sbt"))
        }

        "find no files if no package managers are active" {
            val managedFiles = PackageManager.findManagedFiles(projectDir, emptyList())

            managedFiles.size shouldBe 0
        }

        "fail if the provided file is not a directory" {
            shouldThrow<IllegalArgumentException> {
                PackageManager.findManagedFiles(File(projectDir, "pom.xml"))
            }
        }
    }

    "processPackageVcs" should {
        "fall back to a GitHub homepage URL if the VCS URL cannot be cloned from" {
            val vcsFromPackage = VcsInfo(
                type = VcsType.GIT,
                url = "https://github.com/oss-review-toolkit/foobar",
                revision = ""
            )
            val homepageUrl = "https://github.com/sbt/junit-interface/"

            PackageManager.processPackageVcs(vcsFromPackage, homepageUrl) shouldBe VcsInfo(
                type = VcsType.GIT,
                url = "https://github.com/sbt/junit-interface.git",
                revision = ""
            )
        }

        "split a GitHub browsing URL into its components" {
            val vcsFromPackage = VcsInfo(
                type = VcsType.GIT,
                url = "https://github.com/hamcrest/JavaHamcrest/hamcrest-core",
                revision = "",
                path = ""
            )

            PackageManager.processPackageVcs(vcsFromPackage) shouldBe VcsInfo(
                type = VcsType.GIT,
                url = "https://github.com/hamcrest/JavaHamcrest.git",
                revision = "",
                path = "hamcrest-core"
            )
        }

        "maintain a known VCS type" {
            val vcsFromPackage = VcsInfo(
                type = VcsType.SUBVERSION,
                url = "http://svn.apache.org/repos/asf/commons/proper/codec/trunk",
                revision = ""
            )
            val homepageUrl = "http://commons.apache.org/proper/commons-codec/"

            PackageManager.processPackageVcs(vcsFromPackage, homepageUrl) shouldBe VcsInfo(
                type = VcsType.SUBVERSION,
                url = "http://svn.apache.org/repos/asf/commons/proper/codec",
                revision = "trunk"
            )
        }

        "maintain an unknown VCS type" {
            val vcsFromPackage = VcsInfo(
                type = VcsType(listOf("darcs")),
                url = "http://hub.darcs.net/ross/transformers",
                revision = ""
            )

            PackageManager.processPackageVcs(vcsFromPackage) shouldBe VcsInfo(
                type = VcsType(listOf("darcs")),
                url = "http://hub.darcs.net/ross/transformers",
                revision = ""
            )
        }
    }
})
