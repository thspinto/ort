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
import com.beust.jcommander.converters.FileConverter

import org.ossreviewtoolkit.helper.CommandWithHelp
import org.ossreviewtoolkit.helper.common.sortEntries
import org.ossreviewtoolkit.helper.common.writeAsYaml
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.model.readValue
import org.ossreviewtoolkit.utils.PARAMETER_ORDER_MANDATORY

import java.io.File

@Parameters(
    commandNames = ["sort-repository-configuration"],
    commandDescription = "Sorts all exclude and curation entries of the given repository configuration " +
            "alphabetically. The output is written to the given repository configuration file."
)
internal class SortRepositoryConfigurationCommand : CommandWithHelp() {
    @Parameter(
        required = true,
        order = PARAMETER_ORDER_MANDATORY,
        converter = FileConverter::class,
        description = "The repository configuration file to be sorted."
    )
    private lateinit var repositoryConfigurationFile: File

    override fun runCommand(jc: JCommander): Int {
        repositoryConfigurationFile
            .readValue<RepositoryConfiguration>()
            .sortEntries()
            .writeAsYaml(repositoryConfigurationFile)

        return 0
    }
}
