package com.here.provenanceanalyzer.managers

import com.here.provenanceanalyzer.model.Dependency
import com.here.provenanceanalyzer.PackageManager

import java.nio.file.Path

object PIP : PackageManager(
        "https://pip.pypa.io/",
        "Python",
        // See https://caremad.io/posts/2013/07/setup-vs-requirement/.
        listOf("setup.py", "requirements*.txt")
) {
    override fun resolveDependencies(definitionFiles: List<Path>): Map<Path, Dependency> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}
