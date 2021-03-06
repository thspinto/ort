/*
 * Copyright (C) 2019 HERE Europe B.V.
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

package org.ossreviewtoolkit.helper.commands

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters

import org.ossreviewtoolkit.helper.CommandWithHelp
import org.ossreviewtoolkit.helper.common.RepositoryPathExcludes
import org.ossreviewtoolkit.helper.common.getRepositoryPathExcludes
import org.ossreviewtoolkit.helper.common.mergePathExcludes
import org.ossreviewtoolkit.model.OrtResult
import org.ossreviewtoolkit.model.readValue
import org.ossreviewtoolkit.model.yamlMapper
import org.ossreviewtoolkit.utils.PARAMETER_ORDER_MANDATORY
import org.ossreviewtoolkit.utils.PARAMETER_ORDER_OPTIONAL
import org.ossreviewtoolkit.utils.safeMkdirs

import java.io.File

@Parameters(
    commandNames = ["export-path-excludes"],
    commandDescription = "Export the path excludes to a path excludes file which maps repository URLs to the path " +
            "excludes for the respective repository."
)
internal class ExportPathExcludesCommand : CommandWithHelp() {
    @Parameter(
        names = ["--path-excludes-file"],
        required = true,
        order = PARAMETER_ORDER_MANDATORY,
        description = "The output path excludes file."
    )
    private lateinit var pathExcludesFile: File

    @Parameter(
        names = ["--ort-result-file"],
        required = true,
        order = PARAMETER_ORDER_MANDATORY,
        description = "The input ORT file from which the path excludes are to be read."
    )
    private lateinit var ortResultFile: File

    @Parameter(
        names = ["--repository-configuration-file"],
        required = true,
        order = PARAMETER_ORDER_MANDATORY,
        description = "Override the repository configuration contained in the given input ORT file."
    )
    private lateinit var repositoryConfigurationFile: File

    @Parameter(
        names = ["--update-only-existing"],
        required = false,
        order = PARAMETER_ORDER_OPTIONAL,
        description = "If enabled, only entries are exported for which an entry with the same pattern already exists."
    )
    private var updateOnlyExisting = false

    override fun runCommand(jc: JCommander): Int {
        val localPathExcludes = ortResultFile
            .readValue<OrtResult>()
            .replaceConfig(repositoryConfigurationFile.readValue())
            .getRepositoryPathExcludes()

        val globalPathExcludes = if (pathExcludesFile.isFile) {
            pathExcludesFile.readValue<RepositoryPathExcludes>()
        } else {
            mapOf()
        }

        globalPathExcludes
            .mergePathExcludes(localPathExcludes, updateOnlyExisting = updateOnlyExisting)
            .writeAsYaml(pathExcludesFile)

        return 0
    }
}

/**
 * Serialize this [RepositoryPathExcludes] to the given [targetFile] as YAML.
 */
internal fun RepositoryPathExcludes.writeAsYaml(targetFile: File) {
    targetFile.parentFile.apply { safeMkdirs() }

    yamlMapper.writeValue(
        targetFile,
        mapValues { (_, pathExcludes) ->
            pathExcludes.sortedBy { it.pattern }
        }.toSortedMap()
    )
}
