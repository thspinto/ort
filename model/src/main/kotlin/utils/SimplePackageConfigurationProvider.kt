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

package org.ossreviewtoolkit.model.utils

import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Provenance
import org.ossreviewtoolkit.model.config.PackageConfiguration
import org.ossreviewtoolkit.model.readValue

import java.io.File

/**
 * A provider for [PackageConfiguration]s providing exactly the packages of the given list.
 */
class SimplePackageConfigurationProvider(
    configurations: Collection<PackageConfiguration> = emptyList()
) : PackageConfigurationProvider {
    companion object {
        /**
         * Return a [SimplePackageConfigurationProvider] which provides all [PackageConfiguration]s found recursively
         * in the given [directory]. All non-hidden files within the given [directory] must be package curation files,
         * and the [directory] must not contain multiple [PackageConfiguration]s matching the same [Identifier] and
         * [Provenance].
         */
        fun forDirectory(directory: File): SimplePackageConfigurationProvider {
            val entries = directory.walkBottomUp()
                .filterTo(mutableListOf()) { !it.isHidden && it.isFile }
                .map { it.readValue<PackageConfiguration>() }

            return SimplePackageConfigurationProvider(entries)
        }
    }

    private val configurationsById: Map<Identifier, List<PackageConfiguration>>

    init {
        configurationsById = configurations.groupByTo(HashMap()) { it.id }
    }

    override fun getPackageConfiguration(packageId: Identifier, provenance: Provenance): PackageConfiguration? =
        configurationsById[packageId]?.filter { it.matches(packageId, provenance) }?.let {
            require(it.size <= 1) { "There must be at most one package configuration per Id and provenance." }
            it.singleOrNull()
        }
}
