package org.flowlang.tests

import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.capabilities.TargetExpressionSupportDeclaration
import java.io.File

object TargetExpressionTestEvidence {
    val targets: Map<String, TargetCapability> by lazy {
        TargetRegistryYamlLoader.loadDirectory(File("targets"))
    }

    fun target(target: String): TargetCapability = targets.getValue(target)

    fun declaration(target: String): TargetExpressionSupportDeclaration =
        target(target).expressionSupport
            ?: error("Test target '$target' has no expression-support evidence.")

    fun compatibility(
        target: String,
        status: SupportLevel = SupportLevel.SUPPORTED
    ): CompatibilityReport = CompatibilityReport(
        target = target,
        status = status,
        expressionSupport = declaration(target)
    )
}
