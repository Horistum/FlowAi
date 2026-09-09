package org.flowlang.conformance

import org.flowlang.artifacts.StandardSurface
import org.flowlang.artifacts.StandardSurfaceStatusAuthority
import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.modules.ModuleRegistry
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.targets.builtin.BuiltInNativeProjectionCatalogs
import java.io.File

internal class TargetSemanticsExportChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        checkV048TargetSemanticsMatrix(),
        checkV049StandardExportBundle()
    )

    private fun checkV048TargetSemanticsMatrix(): ConformanceCheck = runCheck("v0.4.8.target-semantics-matrix") {
        val matrix = org.flowlang.distribution.reference.ReferenceStandardArtifacts.targetSemanticsMatrix()
        require(matrix.status == "PASS") { "Target semantics matrix must pass computed validation." }
        require(matrix.targetIds.toSet() == targets.keys) {
            "Target semantics matrix inventory ${matrix.targetIds} disagrees with registry ${targets.keys.sorted()}."
        }
        val features = matrix.entries.map { it.feature }.toSet()
        require(features == setOf("conditions", "approvals", "manual-gates", "strict-manual-approval", "secrets", "artifacts")) {
            "Target semantics matrix may publish only feature families derived by the active evidence authority: $features"
        }

        val referenceConditions = listOf(
            "env == 'prod'",
            "stage != 'dev'",
            "count > 1",
            "name matches '^prod-'",
            "region in ['eu', 'us']"
        )
        fun derivedConditionSupport(target: String): String {
            val capability = targets.getValue(target)
            val unsupported = referenceConditions.count {
                TargetExpressionSupport.unsupportedReason(capability, it) != null
            }
            return when {
                unsupported == 0 -> "native"
                unsupported < referenceConditions.size -> "partial"
                else -> "unsupported-blocked"
            }
        }

        val conditions = matrix.entries.single { it.feature == "conditions" }
        matrix.targetIds.forEach { target ->
            require(conditions.semanticsByTarget.getValue(target) == derivedConditionSupport(target)) {
                "Condition support for '$target' is not derived from TargetExpressionSupport."
            }
        }

        val approvals = matrix.entries.single { it.feature == "approvals" }
        val manualGates = matrix.entries.single { it.feature == "manual-gates" }
        val strictApproval = matrix.entries.single { it.feature == "strict-manual-approval" }
        matrix.targetIds.forEach { target ->
            val provider = BuiltInNativeProjectionCatalogs.byTarget[target]
            val expected = when {
                provider == null -> "not-declared-review-only"
                provider.approvalDefinitions.any { it.capability == "approval.manual" } -> "native"
                else -> "adapter-required-review-only"
            }
            require(approvals.semanticsByTarget.getValue(target) == expected)
            require(manualGates.semanticsByTarget.getValue(target) == expected)
            require(strictApproval.semanticsByTarget.getValue(target) == expected)
        }

        val invalid = matrix.entries.toMutableList().also { entries ->
            val first = entries.first()
            entries[0] = first.copy(semanticsByTarget = first.semanticsByTarget + (matrix.targetIds.first() to "handwritten-optimism"))
        }
        require(StandardSurfaceStatusAuthority.targetSemanticsMatrix(matrix.targetIds, invalid) == "FAIL") {
            "Unknown target-semantics labels must fail the public matrix status authority."
        }
    }

    private fun checkV049StandardExportBundle(): ConformanceCheck = runCheck("v0.4.9.standard-export-bundle") {
        val export = StandardSurface.standardExportBundle()
        val surface = StandardSurface.publicSurface()
        require(export.status == "PASS") { "Standard export bundle must pass computed validation." }
        require(export.command.startsWith("standard-export --out")) { "Standard export bundle must define a CLI export command." }
        require(export.command.contains(FlowStandardVersions.FLOW_STANDARD_VERSION)) {
            "Export command must target the current standard version."
        }
        export.requiredDirectories.forEach { dir ->
            require(File(rootDir, dir.trimEnd('/')).isDirectory) {
                "Standard export bundle requires directory '$dir' which does not exist."
            }
        }
        require(export.requiredDirectories.containsAll(listOf("docs/", "schemas/", "conformance/", "standard/", "targets/", "examples/"))) {
            "Standard export bundle must cover docs, schemas, conformance, standard data, targets and examples."
        }
        require(export.requiredArtifacts.toSet() == surface.stableArtifacts.toSet()) {
            "Standard export bundle artifacts must match the public surface stable artifacts."
        }
        require(export.requiredFiles.contains("standard-index.json"))
        require(export.requiredFiles.contains("public-standard-surface.json"))
        require(export.requiredFiles.contains("conformance-manifest.json"))
        require(export.requiredArtifacts.contains("standard-export-bundle.json"))
        require(
            StandardSurfaceStatusAuthority.standardExportBundle(
                export.requiredDirectories,
                emptyList(),
                export.requiredArtifacts,
                export.packageName
            ) == "FAIL"
        ) { "An export bundle without required files must fail its computed status authority." }
    }
}
