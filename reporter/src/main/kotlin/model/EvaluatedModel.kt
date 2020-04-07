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

import com.fasterxml.jackson.annotation.JsonIdentityInfo
import com.fasterxml.jackson.annotation.JsonIdentityReference
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.databind.introspect.ObjectIdInfo
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

import org.ossreviewtoolkit.model.CustomData
import org.ossreviewtoolkit.model.OrtIssue
import org.ossreviewtoolkit.model.OrtResult
import org.ossreviewtoolkit.model.PROPERTY_NAMING_STRATEGY
import org.ossreviewtoolkit.model.RuleViolation
import org.ossreviewtoolkit.model.config.IssueResolution
import org.ossreviewtoolkit.model.config.LicenseFindingCuration
import org.ossreviewtoolkit.model.config.PathExclude
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.model.config.RuleViolationResolution
import org.ossreviewtoolkit.model.config.ScopeExclude
import org.ossreviewtoolkit.reporter.Reporter
import org.ossreviewtoolkit.reporter.ReporterInput
import org.ossreviewtoolkit.reporter.reporters.WebAppReporter
import org.ossreviewtoolkit.reporter.utils.IntIdModule

import java.io.Writer

/**
 * The [EvaluatedModel] represents the outcome of the evaluation of a [ReporterInput]. This means that all additional
 * information contained in the [ReporterInput] is applied to the [OrtResult]:
 *
 * * [PathExclude]s and [ScopeExclude]s from the [RepositoryConfiguration] are applied.
 * * [IssueResolution]s from the [ReporterInput.resolutionProvider] are matched against all [OrtIssue]s contained in the
 *   result.
 * * [RuleViolationResolution]s from the [ReporterInput.resolutionProvider] are matched against all [RuleViolation]s.
 *
 * The current implementation is missing these features:
 *
 * * [LicenseFindingCuration]s are not yet applied to the model.
 *
 * The model also contains useful containers to easily access some content of the [OrtResult], for example a list of
 * all [OrtIssue]s in their [evaluated form][EvaluatedOrtIssue] which contains back-references to its source.
 *
 * The model can be serialized to with the helper functions [toJson] and [toYaml]. It uses a special [JsonMapper] that
 * de-duplicates objects in the result. For this it uses Jackson's [JsonIdentityInfo] to automatically generate [Int]
 * IDs for the objects. All objects for which the model contains containers, like [issues] or [packages], are serialized
 * only once in those containers. All other references to those objects are replaced by the [Int] IDs. This is required
 * because the model contains cyclic dependencies between objects which would otherwise cause stack overflows during
 * serialization, and it also reduces the size of the result file.
 *
 * Use cases for the [EvaluatedModel] are:
 *
 * * Input for the [WebAppReporter], so that it does not have to evaluate the model at runtime. Currently the model is
 *   optimized for this use case.
 * * Input for [Reporter] implementations, so that they do not have to repeatedly implement the application of excludes,
 *   resolutions, and so on.
 * * Input for external tools, so that they do not have to re-implement the logic for evaluating the model.
 *
 * Important notes for working with this model:
 *
 * * The model uses Kotlin data classes with cyclic dependencies, therefore the [hashCode] and [toString] of affected
 *   classes cannot be used, because they would create stack overflows.
 * * When modifying the model make sure that the objects are serialized at the right place. By default Jackson
 *   serializes an Object with [ObjectIdInfo] the first time the serializer sees the object. If this is not desired
 *   because the object shall be serialized as the generated ID, the [JsonIdentityReference] annotation can be used to
 *   enforce this. For example, the list of [EvaluatedOrtIssue]s is serialized before the list of [EvaluatedPackage]s.
 *   Therefore [EvaluatedOrtIssue.pkg] is annotated with [JsonIdentityReference].
 */
data class EvaluatedModel(
    val pathExcludes: List<PathExclude>,
    val scopeExcludes: List<ScopeExclude>,
    val copyrights: List<CopyrightStatement>,
    val licenses: List<LicenseId>,
    val scopes: List<EvaluatedScope>,
    val issueResolutions: List<IssueResolution>,
    val issues: List<EvaluatedOrtIssue>,
    val scanResults: List<EvaluatedScanResult>,
    val packages: List<EvaluatedPackage>,
    val paths: List<EvaluatedPackagePath>,
    val dependencyTrees: List<DependencyTreeNode>,
    val ruleViolationResolutions: List<RuleViolationResolution>,
    val ruleViolations: List<EvaluatedRuleViolation>,
    val statistics: Statistics,
    // TODO: Ideally this would be an instance of RepositoryConfiguration, but for now it has to be a string to not be
    //       converted to JSON when using it as input for the web app reporter.
    val repositoryConfiguration: String,
    val customData: CustomData
) {
    companion object {
        private val INT_ID_TYPES = listOf(
            CopyrightStatement::class.java,
            EvaluatedOrtIssue::class.java,
            EvaluatedPackage::class.java,
            EvaluatedPackagePath::class.java,
            EvaluatedRuleViolation::class.java,
            EvaluatedScanResult::class.java,
            EvaluatedScope::class.java,
            IssueResolution::class.java,
            LicenseId::class.java,
            PathExclude::class.java,
            RuleViolationResolution::class.java,
            ScopeExclude::class.java
        )

        private val MAPPER_CONFIG: ObjectMapper.() -> Unit = {
            registerKotlinModule()

            registerModule(JavaTimeModule())
            registerModule(IntIdModule(INT_ID_TYPES))

            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

            propertyNamingStrategy = PROPERTY_NAMING_STRATEGY
        }

        private val JSON_MAPPER by lazy {
            val factory = JsonFactory().disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
            JsonMapper(factory).apply(MAPPER_CONFIG)
        }

        private val YAML_MAPPER by lazy { YAMLMapper().apply(MAPPER_CONFIG) }

        fun create(input: ReporterInput): EvaluatedModel = EvaluatedModelMapper(input).build()
    }

    /**
     * Serialize this [EvaluatedModel] to JSON and write the output to the [writer], optionally
     * [pretty printed][prettyPrint].
     */
    fun toJson(writer: Writer, prettyPrint: Boolean = true) {
        when {
            prettyPrint -> JSON_MAPPER.writerWithDefaultPrettyPrinter()
            else -> JSON_MAPPER.writer()
        }.writeValue(writer, toSortedTree())
    }

    /**
     * Serialize this [EvaluatedModel] to YAML and write the output to the [writer].
     */
    fun toYaml(writer: Writer): Unit = YAML_MAPPER.writeValue(writer, toSortedTree())

    /**
     * Sort all collections by the generated IDs. This ensures that the IDs match the array index of the elements.
     * The web-app-reporter relies on this for fast lookup of object references.
     */
    private fun toSortedTree(): JsonNode {
        val tree = JSON_MAPPER.valueToTree<ObjectNode>(this)
        tree.forEach { node ->
            if (node is ArrayNode) {
                if (!node.isEmpty && node[0].has("_id")) {
                    val sortedChildren =
                        node.elements().asSequence().toList().sortedBy { it["_id"].intValue() }.toList()
                    node.removeAll()
                    node.addAll(sortedChildren)
                }
            }
        }
        return tree
    }
}
