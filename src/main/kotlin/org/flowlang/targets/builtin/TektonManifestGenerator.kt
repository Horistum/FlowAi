package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetProjectionIdentityChecks
import org.flowlang.compiler.requireSingleWorkflowFailureProjection
import org.flowlang.generators.manifest.AdapterManifestLowering
import org.flowlang.generators.manifest.AdapterWorkflowProjectionLowering
import org.flowlang.generators.manifest.ReconciledTargetManifestGenerator
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetMappingNote
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetProjectionAuthorization

class TektonManifestGenerator(
    override val nativeProjectionCatalog: TargetNativeProjectionCatalog = TektonNativeProjectionCatalog.catalog
) : ReconciledTargetManifestGenerator() {
    override val target: String = "tekton"

    override fun buildManifest(authorization: TargetProjectionAuthorization): TargetManifest {
        val plan = authorization.plan
        TargetProjectionIdentityChecks.requireNames(
            "projection.inputs", plan.inputs.map { it.name }, AdapterManifestLowering::id)
        val compatibility = authorization.compatibility
        val jobs = AdapterWorkflowProjectionLowering.jobPerTask(
            authorization.compilationAuthorization.requireSingleWorkflowFailureProjection(),
            authorization.compilationAuthorization,
            target,
            compatibility.projectionRules,
            nativeProjectionCatalog
        )
        val resolvedJobs = jobs.ifEmpty {
            listOf(TargetJob(
                id = AdapterManifestLowering.id(plan.flowName),
                name = plan.flowName,
                steps = listOf(AdapterManifestLowering.emptyStep(plan.flowName))
            ))
        }
        val inputs = plan.inputs.map(AdapterManifestLowering::input)
        val partialNote = TargetMappingNote(
            "warning", target, "manifest", "target.partial",
            "Tekton renderer is a partial generator and requires notes-driven target projection before production use."
        )
        val untranslatableConditionNotes = resolvedJobs.mapNotNull { job ->
            val condition = job.metadata["condition"]
            if (condition != null &&
                TektonTargetExpressionTranslator.tektonWhen(condition, inputs, compatibility.expressionSupport) == null
            ) {
                TargetMappingNote(
                    level = "error", target = target, nodeId = job.id, feature = "condition.unsupported",
                    message = "Flow condition cannot be enforced as a native Tekton 'when' guard; without resolution the task would run unconditionally. Use a supported condition shape or an explicit target note before production use: $condition"
                )
            } else null
        }
        return TargetManifest(
            target = target,
            flowName = plan.flowName,
            compatibility = compatibility,
            inputs = inputs,
            jobs = resolvedJobs,
            mappingNotes = AdapterManifestLowering.mappingNotes(compatibility, target) + partialNote + untranslatableConditionNotes,
            metadata = AdapterManifestLowering.metadata(plan, "TektonManifestGenerator") + mapOf(
                "supportLevel" to "partial", "jobPerTask" to "true"
            )
        )
    }
}
