package org.flowlang.conformance

import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.artifacts.StandardSurface
import org.flowlang.modules.ModuleRegistry
import org.flowlang.standard.FlowStandardVersions
import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry

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
        val matrix = StandardSurface.targetSemanticsMatrix()
        require(matrix.status == "PASS") { "Target semantics matrix must pass." }
        require(matrix.targetIds.all { it in targets.keys }) {
            "Target semantics matrix references targets absent from the registry: ${matrix.targetIds.filter { it !in targets.keys }}."
        }
        require(matrix.targetIds.containsAll(listOf("jenkins", "github-actions", "tekton"))) {
            "Target semantics matrix must cover Jenkins, GitHub Actions and Tekton."
        }
        val features = matrix.entries.map { it.feature }.toSet()
        require(features.containsAll(setOf("conditions", "approvals", "secrets", "artifacts", "parallelism", "rollback"))) {
            "Target semantics matrix is missing core portable features."
        }
        val referenceConditions = listOf("env == 'prod'", "stage != 'dev'", "count > 1", "name matches '^prod-'", "region in ['eu', 'us']")
        fun derivedConditionSupport(target: String): String {
            val capability = targets.getValue(target)
            val unsupported = referenceConditions.count { TargetExpressionSupport.unsupportedReason(capability, it) != null }
            return when {
                unsupported == 0 -> "native"
                unsupported < referenceConditions.size -> "partial"
                else -> "unsupported"
            }
        }
        val conditions = matrix.entries.first { it.feature == "conditions" }
        listOf("jenkins", "github-actions", "tekton").forEach { target ->
            val declared = conditions.semanticsByTarget.getValue(target)
            val derived = derivedConditionSupport(target)
            require(declared == derived) {
                "Matrix target '$target' conditions '$declared' disagree with TargetExpressionSupport ('$derived')."
            }
        }
        require(conditions.requiredDiagnosticWhenUnsupported == "condition.expression") {
            "Unsupported condition expressions must surface the condition.expression diagnostic."
        }
        val unsupportedReference = referenceConditions.firstOrNull {
            TargetExpressionSupport.unsupportedReason(targets.getValue("tekton"), it) != null
        }
        require(unsupportedReference != null) {
            "Tekton must report at least one unsupported reference guard expression."
        }
        require(TargetExpressionSupport.unsupportedReason(targets.getValue("jenkins"), unsupportedReference) == null) {
            "Jenkins must natively express the reference guard expression '$unsupportedReference'."
        }
    }

    private fun checkV049StandardExportBundle(): ConformanceCheck = runCheck("v0.4.9.standard-export-bundle") {
        val export = StandardSurface.standardExportBundle()
        val surface = StandardSurface.publicSurface()
        require(export.status == "PASS") { "Standard export bundle must pass." }
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
        require(export.requiredFiles.contains("standard-index.json")) { "Standard export bundle must include standard-index.json." }
        require(export.requiredFiles.contains("public-standard-surface.json")) { "Standard export bundle must include public-standard-surface.json." }
        require(export.requiredFiles.contains("conformance-manifest.json")) { "Standard export bundle must include conformance-manifest.json for self-verification." }
        require(export.requiredArtifacts.contains("standard-export-bundle.json")) { "Standard export bundle must declare itself as a required artifact." }
    }
}
