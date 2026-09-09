package org.flowlang.targets.builtin

import org.flowlang.compiler.requireSingleWorkflowFailureProjection
import org.flowlang.generators.manifest.AdapterManifestLowering
import org.flowlang.generators.manifest.AdapterWorkflowProjectionLowering
import org.flowlang.generators.manifest.ReconciledTargetManifestGenerator
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetProjectionAuthorization

class JenkinsManifestGenerator(
    override val nativeProjectionCatalog: TargetNativeProjectionCatalog = JenkinsNativeProjectionCatalog.catalog
) : ReconciledTargetManifestGenerator() {
    override val target: String = "jenkins"

    override fun buildManifest(authorization: TargetProjectionAuthorization): TargetManifest {
        val plan = authorization.plan
        val compatibility = authorization.compatibility
        val steps = AdapterWorkflowProjectionLowering.nodePreservingSteps(
            projection = authorization.compilationAuthorization.requireSingleWorkflowFailureProjection(),
            authorization = authorization.compilationAuthorization,
            targetName = target,
            projectionRules = compatibility.projectionRules,
            nativeProjections = nativeProjectionCatalog
        )
        return TargetManifest(
            target = target,
            flowName = plan.flowName,
            compatibility = compatibility,
            inputs = plan.inputs.map(AdapterManifestLowering::input),
            triggers = plan.triggers.map(AdapterManifestLowering::trigger),
            jobs = listOf(TargetJob(id = AdapterManifestLowering.id(plan.flowName), name = plan.flowName, steps = steps)),
            mappingNotes = AdapterManifestLowering.mappingNotes(compatibility, target),
            metadata = AdapterManifestLowering.metadata(plan, "JenkinsManifestGenerator") + ("nodePreserving" to "true")
        )
    }
}
