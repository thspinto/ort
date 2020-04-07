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

import com.fasterxml.jackson.annotation.JsonIdentityReference
import com.fasterxml.jackson.annotation.JsonInclude

import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageCurationResult
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.config.PathExclude
import org.ossreviewtoolkit.model.config.ScopeExclude
import org.ossreviewtoolkit.spdx.SpdxExpression

import java.util.SortedSet

/**
 * The evaluated form of a [Package] used by the [EvaluatedModel].
 */
data class EvaluatedPackage(
    val id: Identifier,
    val isProject: Boolean,
    val definitionFilePath: String,
    val purl: String = id.toPurl(),
    val declaredLicenses: List<LicenseId>,
    val declaredLicensesProcessed: EvaluatedProcessedDeclaredLicense,
    val detectedLicenses: Set<LicenseId>,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val concludedLicense: SpdxExpression? = null,
    val description: String,
    val homepageUrl: String,
    val binaryArtifact: RemoteArtifact,
    val sourceArtifact: RemoteArtifact,
    val vcs: VcsInfo,
    val vcsProcessed: VcsInfo = vcs.normalize(),
    val curations: List<PackageCurationResult>,
    @JsonIdentityReference(alwaysAsId = true)
    val paths: MutableList<EvaluatedPackagePath>,
    val levels: SortedSet<Int>,
    val scopes: MutableSet<EvaluatedScope>,
    val scanResults: List<EvaluatedScanResult>,
    val findings: List<EvaluatedFinding>,
    val isExcluded: Boolean,
    val pathExcludes: List<PathExclude>,
    val scopeExcludes: List<ScopeExclude>,
    val issues: List<EvaluatedOrtIssue>
)
