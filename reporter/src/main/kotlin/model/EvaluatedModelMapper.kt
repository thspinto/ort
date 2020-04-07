/*
 * Copyright (C) 2017-2020 HERE Europe B.V.
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

package org.ossreviewtoolkit.reporter.model

import org.ossreviewtoolkit.model.CuratedPackage
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.OrtIssue
import org.ossreviewtoolkit.model.PackageReference
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.RuleViolation
import org.ossreviewtoolkit.model.ScanResult
import org.ossreviewtoolkit.model.ScanSummary
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.config.IssueResolution
import org.ossreviewtoolkit.model.config.PathExclude
import org.ossreviewtoolkit.model.config.RuleViolationResolution
import org.ossreviewtoolkit.model.config.ScopeExclude
import org.ossreviewtoolkit.model.utils.FindingsMatcher
import org.ossreviewtoolkit.model.yamlMapper
import org.ossreviewtoolkit.reporter.ReporterInput
import org.ossreviewtoolkit.reporter.utils.StatisticsCalculator
import org.ossreviewtoolkit.utils.ProcessedDeclaredLicense

/**
 * Maps the [reporter input][input] to an [EvaluatedModel].
 */
internal class EvaluatedModelMapper(private val input: ReporterInput) {
    private val packages = mutableMapOf<Identifier, EvaluatedPackage>()
    private val paths = mutableListOf<EvaluatedPackagePath>()
    private val dependencyTrees = mutableListOf<DependencyTreeNode>()
    private val scanResults = mutableListOf<EvaluatedScanResult>()
    private val copyrights = mutableListOf<CopyrightStatement>()
    private val licenses = mutableListOf<LicenseId>()
    private val scopes = mutableListOf<EvaluatedScope>()
    private val issues = mutableListOf<EvaluatedOrtIssue>()
    private val issueResolutions = mutableListOf<IssueResolution>()
    private val pathExcludes = mutableListOf<PathExclude>()
    private val scopeExcludes = mutableListOf<ScopeExclude>()
    private val ruleViolations = mutableListOf<EvaluatedRuleViolation>()
    private val ruleViolationResolutions = mutableListOf<RuleViolationResolution>()

    private val findingsMatcher = FindingsMatcher()

    private data class PackageExcludeInfo(
        var id: Identifier,
        var isExcluded: Boolean,
        val pathExcludes: MutableList<PathExclude> = mutableListOf(),
        val scopeExcludes: MutableList<ScopeExclude> = mutableListOf()
    )

    private val packageExcludeInfo = mutableMapOf<Identifier, PackageExcludeInfo>()

    fun build(): EvaluatedModel {
        createExcludeInfo()

        input.ortResult.analyzer?.result?.projects?.forEach { project ->
            addProject(project)
        }

        input.ortResult.analyzer?.result?.packages?.forEach { curatedPkg ->
            addPackage(curatedPkg)
        }

        input.ortResult.evaluator?.violations?.forEach { ruleViolation ->
            addRuleViolation(ruleViolation)
        }

        input.ortResult.analyzer?.result?.projects?.forEach { project ->
            val pkg = packages.getValue(project.id)
            addDependencyTree(project, pkg)
        }

        input.ortResult.analyzer?.result?.projects?.forEach { project ->
            addShortestPaths(project)
        }

        return EvaluatedModel(
            pathExcludes = pathExcludes,
            scopeExcludes = scopeExcludes,
            issueResolutions = issueResolutions,
            issues = issues,
            copyrights = copyrights,
            licenses = licenses,
            scopes = scopes,
            scanResults = scanResults,
            packages = packages.values.toList(),
            paths = paths,
            dependencyTrees = dependencyTrees,
            ruleViolationResolutions = ruleViolationResolutions,
            ruleViolations = ruleViolations,
            statistics = StatisticsCalculator().getStatistics(input.ortResult, input.resolutionProvider),
            repositoryConfiguration = yamlMapper.writeValueAsString(input.ortResult.repository.config),
            customData = input.ortResult.data
        )
    }

    private fun createExcludeInfo() {
        input.ortResult.analyzer?.result?.projects?.forEach { project ->
            packageExcludeInfo[project.id] = PackageExcludeInfo(project.id, true)
        }

        input.ortResult.analyzer?.result?.packages?.forEach { pkg ->
            packageExcludeInfo[pkg.pkg.id] = PackageExcludeInfo(pkg.pkg.id, true)
        }

        input.ortResult.analyzer?.result?.projects?.forEach { project ->
            val pathExcludes = input.ortResult.getExcludes().findPathExcludes(project, input.ortResult)
            val dependencies = project.collectDependencies()
            if (pathExcludes.isEmpty()) {
                val info = packageExcludeInfo.getValue(project.id)
                if (info.isExcluded) {
                    info.isExcluded = false
                    info.pathExcludes.clear()
                    info.scopeExcludes.clear()
                }
            } else {
                dependencies.forEach { id ->
                    val info = packageExcludeInfo.getOrPut(id) { PackageExcludeInfo(id, true) }

                    if (info.isExcluded) {
                        info.pathExcludes += pathExcludes
                    }
                }
            }
            project.scopes.forEach { scope ->
                val scopeExcludes = input.ortResult.getExcludes().findScopeExcludes(scope)
                val scopeDependencies = scope.collectDependencies()
                if (scopeExcludes.isNotEmpty()) {
                    scopeDependencies.forEach { id ->
                        val info = packageExcludeInfo.getOrPut(id) { PackageExcludeInfo(id, true) }
                        if (info.isExcluded) {
                            info.scopeExcludes += scopeExcludes
                        }
                    }
                } else if (pathExcludes.isEmpty()) {
                    scopeDependencies.forEach { id ->
                        val info = packageExcludeInfo.getOrPut(id) { PackageExcludeInfo(id, true) }
                        info.isExcluded = false
                        info.pathExcludes.clear()
                        info.scopeExcludes.clear()
                    }
                }
            }
        }
    }

    private fun addProject(project: Project) {
        val scanResults = mutableListOf<EvaluatedScanResult>()
        val detectedLicenses = mutableSetOf<LicenseId>()
        val findings = mutableListOf<EvaluatedFinding>()
        val issues = mutableListOf<EvaluatedOrtIssue>()

        val applicablePathExcludes = input.ortResult.getExcludes().findPathExcludes(project, input.ortResult)
        val evaluatedPathExcludes = pathExcludes.addIfRequired(applicablePathExcludes)

        val evaluatedPackage = EvaluatedPackage(
            id = project.id,
            isProject = true,
            definitionFilePath = project.definitionFilePath,
            purl = project.id.toPurl(),
            declaredLicenses = project.declaredLicenses.map { licenses.addIfRequired(LicenseId(it)) },
            declaredLicensesProcessed = project.declaredLicensesProcessed.evaluate(),
            detectedLicenses = detectedLicenses,
            concludedLicense = null,
            description = "",
            homepageUrl = project.homepageUrl,
            binaryArtifact = RemoteArtifact.EMPTY,
            sourceArtifact = RemoteArtifact.EMPTY,
            vcs = project.vcs,
            vcsProcessed = project.vcsProcessed,
            curations = emptyList(),
            paths = mutableListOf(),
            levels = sortedSetOf(0),
            scopes = mutableSetOf(),
            scanResults = scanResults,
            findings = findings,
            isExcluded = applicablePathExcludes.isNotEmpty(),
            pathExcludes = evaluatedPathExcludes,
            scopeExcludes = emptyList(),
            issues = issues
        )

        packages[evaluatedPackage.id] = evaluatedPackage

        issues += addAnalyzerIssues(project.id, evaluatedPackage)

        input.ortResult.getScanResultsForId(project.id).mapTo(scanResults) { result ->
            convertScanResult(result, findings, evaluatedPackage)
        }

        findings.filter { it.type == EvaluatedFindingType.LICENSE }.mapNotNullTo(detectedLicenses) { it.license }
    }

    private fun addPackage(curatedPkg: CuratedPackage) {
        val pkg = curatedPkg.pkg

        val scanResults = mutableListOf<EvaluatedScanResult>()
        val detectedLicenses = mutableSetOf<LicenseId>()
        val findings = mutableListOf<EvaluatedFinding>()
        val issues = mutableListOf<EvaluatedOrtIssue>()

        val excludeInfo = packageExcludeInfo.getValue(pkg.id)

        val evaluatedPathExcludes = pathExcludes.addIfRequired(excludeInfo.pathExcludes)
        val evaluatedScopeExcludes = scopeExcludes.addIfRequired(excludeInfo.scopeExcludes)

        val evaluatedPackage = EvaluatedPackage(
            id = pkg.id,
            isProject = false,
            definitionFilePath = "",
            purl = pkg.purl,
            declaredLicenses = pkg.declaredLicenses.map { licenses.addIfRequired(LicenseId(it)) },
            declaredLicensesProcessed = pkg.declaredLicensesProcessed.evaluate(),
            detectedLicenses = detectedLicenses,
            concludedLicense = pkg.concludedLicense,
            description = pkg.description,
            homepageUrl = pkg.homepageUrl,
            binaryArtifact = pkg.binaryArtifact,
            sourceArtifact = pkg.sourceArtifact,
            vcs = pkg.vcs,
            vcsProcessed = pkg.vcsProcessed,
            curations = curatedPkg.curations,
            paths = mutableListOf(),
            levels = sortedSetOf(),
            scopes = mutableSetOf(),
            scanResults = scanResults,
            findings = findings,
            isExcluded = excludeInfo.isExcluded,
            pathExcludes = evaluatedPathExcludes,
            scopeExcludes = evaluatedScopeExcludes,
            issues = issues
        )

        packages[evaluatedPackage.id] = evaluatedPackage

        issues += addAnalyzerIssues(pkg.id, evaluatedPackage)

        input.ortResult.getScanResultsForId(pkg.id).mapTo(scanResults) { result ->
            convertScanResult(result, findings, evaluatedPackage)
        }

        findings.filter { it.type == EvaluatedFindingType.LICENSE }.mapNotNullTo(detectedLicenses) { it.license }
    }

    private fun addAnalyzerIssues(id: Identifier, pkg: EvaluatedPackage): List<EvaluatedOrtIssue> {
        val result = mutableListOf<EvaluatedOrtIssue>()

        input.ortResult.analyzer?.result?.issues?.get(id)?.let { analyzerIssues ->
            result += addIssues(analyzerIssues, EvaluatedOrtIssueType.ANALYZER, pkg, null, null)
        }

        input.ortResult.getPackage(id)?.let {
            result += addIssues(it.pkg.collectIssues(), EvaluatedOrtIssueType.ANALYZER, pkg, null, null)
        }

        return result
    }

    private fun addRuleViolation(ruleViolation: RuleViolation) {
        val resolutions = addResolutions(ruleViolation)
        val pkg = packages.getValue(ruleViolation.pkg)
        val license = ruleViolation.license?.let { licenses.addIfRequired(LicenseId(it)) }

        val evaluatedViolation = EvaluatedRuleViolation(
            rule = ruleViolation.rule,
            pkg = pkg,
            license = license,
            licenseSource = ruleViolation.licenseSource,
            severity = ruleViolation.severity,
            message = ruleViolation.message,
            howToFix = ruleViolation.howToFix,
            resolutions = resolutions
        )

        ruleViolations += evaluatedViolation
    }

    private fun convertScanResult(
        result: ScanResult,
        findings: MutableList<EvaluatedFinding>,
        pkg: EvaluatedPackage
    ): EvaluatedScanResult {
        val issues = mutableListOf<EvaluatedOrtIssue>()

        val evaluatedScanResult = EvaluatedScanResult(
            provenance = result.provenance,
            scanner = result.scanner,
            startTime = result.summary.startTime,
            endTime = result.summary.endTime,
            fileCount = result.summary.fileCount,
            packageVerificationCode = result.summary.packageVerificationCode,
            issues = issues
        )

        val actualScanResult = scanResults.addIfRequired(evaluatedScanResult)

        issues += addIssues(
            result.summary.issues,
            EvaluatedOrtIssueType.SCANNER,
            pkg,
            actualScanResult,
            null
        )

        addLicensesAndCopyrights(result.summary, actualScanResult, findings)

        return actualScanResult
    }

    private fun addDependencyTree(project: Project, pkg: EvaluatedPackage) {
        fun PackageReference.toEvaluatedTreeNode(
            scope: EvaluatedScope,
            path: List<EvaluatedPackage>
        ): DependencyTreeNode {
            val dependency = packages.getOrPut(id) { createEmptyPackage(id) }
            val issues = mutableListOf<EvaluatedOrtIssue>()
            val packagePath = EvaluatedPackagePath(
                pkg = dependency,
                project = pkg,
                scope = scope,
                path = path
            )

            dependency.levels += path.size
            dependency.scopes += scopes.addIfRequired(scope)

            if (this.issues.isNotEmpty()) {
                paths += packagePath
                issues += addIssues(this.issues, EvaluatedOrtIssueType.ANALYZER, dependency, null, packagePath)
            }

            return DependencyTreeNode(
                title = id.toCoordinates(),
                linkage = linkage,
                pkg = dependency,
                children = dependencies.map { it.toEvaluatedTreeNode(scope, path + dependency) },
                pathExcludes = emptyList(),
                scopeExcludes = emptyList(),
                issues = issues
            )
        }

        val scopeTrees = project.scopes.map { scope ->
            val subTrees = scope.dependencies.map {
                it.toEvaluatedTreeNode(scopes.addIfRequired(EvaluatedScope(scope.name)), mutableListOf())
            }

            val applicableScopeExcludes = input.ortResult.getExcludes().findScopeExcludes(scope)
            val evaluatedScopeExcludes = scopeExcludes.addIfRequired(applicableScopeExcludes)

            DependencyTreeNode(
                title = scope.name,
                linkage = null,
                pkg = null,
                children = subTrees,
                pathExcludes = emptyList(),
                scopeExcludes = evaluatedScopeExcludes,
                issues = emptyList()
            )
        }

        val tree = DependencyTreeNode(
            title = project.id.toCoordinates(),
            linkage = null,
            pkg = pkg,
            children = scopeTrees,
            pathExcludes = pkg.pathExcludes,
            scopeExcludes = emptyList(),
            issues = emptyList()
        )

        dependencyTrees += tree
    }

    private fun createEmptyPackage(id: Identifier): EvaluatedPackage {
        val excludeInfo = packageExcludeInfo.getValue(id)

        val evaluatedPathExcludes = pathExcludes.addIfRequired(excludeInfo.pathExcludes)
        val evaluatedScopeExcludes = scopeExcludes.addIfRequired(excludeInfo.scopeExcludes)

        val evaluatedPackage = EvaluatedPackage(
            id = id,
            isProject = false,
            definitionFilePath = "",
            purl = id.toPurl(),
            declaredLicenses = emptyList(),
            declaredLicensesProcessed = EvaluatedProcessedDeclaredLicense(null, emptyList(), emptyList()),
            detectedLicenses = emptySet(),
            concludedLicense = null,
            description = "",
            homepageUrl = "",
            binaryArtifact = RemoteArtifact.EMPTY,
            sourceArtifact = RemoteArtifact.EMPTY,
            vcs = VcsInfo.EMPTY,
            vcsProcessed = VcsInfo.EMPTY,
            curations = emptyList(),
            paths = mutableListOf(),
            levels = sortedSetOf(),
            scopes = mutableSetOf(),
            scanResults = emptyList(),
            findings = emptyList(),
            isExcluded = excludeInfo.isExcluded,
            pathExcludes = evaluatedPathExcludes,
            scopeExcludes = evaluatedScopeExcludes,
            issues = emptyList()
        )

        packages[id] = evaluatedPackage

        return evaluatedPackage
    }

    private fun addIssues(
        issues: List<OrtIssue>,
        type: EvaluatedOrtIssueType,
        pkg: EvaluatedPackage,
        scanResult: EvaluatedScanResult?,
        path: EvaluatedPackagePath?
    ): List<EvaluatedOrtIssue> {
        val evaluatedIssues = issues.map { issue ->
            val resolutions = addResolutions(issue)

            EvaluatedOrtIssue(
                timestamp = issue.timestamp,
                type = type,
                source = issue.source,
                message = issue.message,
                severity = issue.severity,
                resolutions = resolutions,
                pkg = pkg,
                scanResult = scanResult,
                path = path
            )
        }

        this.issues += evaluatedIssues

        return evaluatedIssues
    }

    private fun addResolutions(issue: OrtIssue): List<IssueResolution> {
        val matchingResolutions = input.resolutionProvider.getIssueResolutionsFor(issue)

        return issueResolutions.addIfRequired(matchingResolutions)
    }

    private fun addResolutions(ruleViolation: RuleViolation): List<RuleViolationResolution> {
        val matchingResolutions = input.resolutionProvider.getRuleViolationResolutionsFor(ruleViolation)

        return ruleViolationResolutions.addIfRequired(matchingResolutions)
    }

    private fun addLicensesAndCopyrights(
        summary: ScanSummary,
        scanResult: EvaluatedScanResult,
        findings: MutableList<EvaluatedFinding>
    ) {
        val matchedFindings = findingsMatcher.match(
            summary.licenseFindings,
            summary.copyrightFindings
        )

        matchedFindings.forEach { licenseFindings ->
            licenseFindings.copyrights.forEach { copyrightFinding ->
                val actualCopyright = copyrights.addIfRequired(CopyrightStatement(copyrightFinding.statement))

                copyrightFinding.locations.forEach { location ->
                    findings += EvaluatedFinding(
                        type = EvaluatedFindingType.COPYRIGHT,
                        license = null,
                        copyright = actualCopyright,
                        path = location.path,
                        startLine = location.startLine,
                        endLine = location.endLine,
                        scanResult = scanResult
                    )
                }
            }

            val actualLicense = licenses.addIfRequired(LicenseId(licenseFindings.license))

            licenseFindings.locations.forEach { location ->
                findings += EvaluatedFinding(
                    type = EvaluatedFindingType.LICENSE,
                    license = actualLicense,
                    copyright = null,
                    path = location.path,
                    startLine = location.startLine,
                    endLine = location.endLine,
                    scanResult = scanResult
                )
            }
        }
    }

    private fun addShortestPaths(project: Project) {
        project.scopes.forEach { scope ->
            scope.getShortestPaths().forEach { (id, path) ->
                val pkg = packages.getValue(id)

                val packagePath = EvaluatedPackagePath(
                    pkg = pkg,
                    project = packages.getValue(project.id),
                    scope = scopes.addIfRequired(EvaluatedScope(scope.name)),
                    path = path.map { parentId -> packages.getValue(parentId) }
                )

                paths += packagePath
                pkg.paths += packagePath
            }
        }
    }

    private fun ProcessedDeclaredLicense.evaluate(): EvaluatedProcessedDeclaredLicense =
        EvaluatedProcessedDeclaredLicense(
            spdxExpression = spdxExpression,
            mappedLicenses = spdxExpression?.licenses()?.map { licenses.addIfRequired(LicenseId(it)) }.orEmpty(),
            unmappedLicenses = unmapped.map { licenses.addIfRequired(LicenseId(it)) }
        )

    /**
     * Adds the [value] to this list if the list does not already contain an equal item. Returns the item that is
     * contained in the list. This is important to make sure that there is only one instance of equal items used in the
     * model, because when Jackson generates IDs each instance gets a new ID, no matter if they are equal or not.
     */
    private fun <T> MutableList<T>.addIfRequired(value: T): T =
        find { it == value } ?: value.also { add(it) }

    /**
     * Similar to [addIfRequired], but for multiple input values.
     */
    private fun <T> MutableList<T>.addIfRequired(values: Collection<T>): List<T> =
        values.map { addIfRequired(it) }.distinct()
}
