package org.flowlang.distribution.reference

import java.io.File
import org.flowlang.artifacts.StandardSurface
import org.flowlang.artifacts.TargetSemanticsMatrixReport
import org.flowlang.targets.TargetRegistryYamlLoader

/** Concrete catalog selection belongs only to this explicit reference composition. */
object ReferenceStandardArtifacts {
    fun targetSemanticsMatrix(rootDir: File = File(".")): TargetSemanticsMatrixReport =
        StandardSurface.targetSemanticsMatrix(
            TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets")),
            ReferenceTargetProjections.nativeCatalogs
        )
}
