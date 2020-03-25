/*
 * Copyright (C) 2020 Bosch.IO GmbH
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

package com.here.ort.detekt

import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import io.gitlab.arturbosch.detekt.api.internal.absolutePath

//import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtImportList
import org.jetbrains.kotlin.psi.psiUtil.siblings

class OrtImportOrder : Rule() {
    override val issue = Issue(
        javaClass.simpleName,
        Severity.Style,
        "Reports files that do not follow ORT's order for imports",
        Debt.FIVE_MINS
    )

    override fun visitImportList(importList: KtImportList) {
        super.visitImportList(importList)

        //val ktElement = importList.siblings(withItself = false).filterIsInstance<KtElement>().firstOrNull() ?: return
        //println(importList.siblings(withItself = false).joinToString())
        val sortedImports = importList.imports.sortedBy { it.importPath.toString() }
        if (importList.imports != sortedImports) {
            println(importList.containingKtFile.absolutePath())
            val importStrings = importList.imports.mapTo(mutableListOf()) { it.importPath.toString() }
            val sortedImportStrings = sortedImports.mapTo(mutableListOf()) { it.importPath.toString() }

            val importStringsIterator = importStrings.iterator()
            val sortedImportStringsIterator = sortedImportStrings.iterator()
            while (importStringsIterator.hasNext() && importStringsIterator.hasNext() &&
            importStrings.dropWhile { importString ->
                (importString == sortedImportStrings.first()).also { if (it) sortedImportStrings.removeAt(0) }
            }

            importStrings.dropWhile { importString ->
                (importString == sortedImportStrings.first()).also { if (it) sortedImportStrings.drop(1) }
            }

            println(sortedImports.joinToString("\n") { it.importPath.toString() })
        }
    }
}
