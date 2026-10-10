package org.flowlang.targets.builtin

import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.compiler.CanonicalRetryNode
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetProjectionCapabilityResolver
import org.flowlang.generators.manifest.TargetStructuralProjectionKind

/** Adapter-owned bounded projection; the generic target registry remains unchanged. */
class JenkinsRetryProjectionScope(private val catalog: TargetNativeProjectionCatalog?) : TargetProjectionCapabilityResolver {
    override fun resolve(authorization: CompilationAuthorization, target: String, declared: TargetCapability): TargetCapability {
        authorization.requireIntegrity()
        if (target != "jenkins" || declared.target != target || catalog == null || catalog.target != target ||
            catalog.structuralDefinitions.none { it.structure == TargetStructuralProjectionKind.RETRY &&
                it.kind == JenkinsProjectionPayloadKinds.JENKINS_STRUCTURE && it.reference == "retry" }) return declared
        val retries = authorization.graph.nodes.filterIsInstance<CanonicalRetryNode>()
        if (retries.isEmpty() || retries.any { !supports(it.max, it.delay, it.backoff) }) return declared
        return declared.copy(features = declared.features + ("retry.task" to SupportLevel.SUPPORTED),
            notes = declared.notes + "Jenkins retry is executable only for an authored total attempt limit with fixed zero delay; other retry policies remain unsupported.")
    }

    companion object {
        fun supports(max: Int, delay: String, backoff: String): Boolean = max > 0 &&
            delay.trim().lowercase() in setOf("0", "0s", "0m", "0h", "0ms") && backoff.equals("fixed", ignoreCase = true)
    }
}
