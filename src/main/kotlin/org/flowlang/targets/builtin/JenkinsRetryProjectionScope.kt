package org.flowlang.targets.builtin

import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.compiler.CanonicalRetryNode
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetProjectionCapabilityResolver
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.planner.PlanDependencyRelations
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologySupportStatus

/** Adapter-owned bounded projection; the generic target registry remains unchanged. */
class JenkinsRetryProjectionScope(private val catalog: TargetNativeProjectionCatalog?) : TargetProjectionCapabilityResolver {
    override fun resolve(authorization: CompilationAuthorization, target: String, declared: TargetCapability): TargetCapability {
        authorization.requireIntegrity()
        if (target != "jenkins" || declared.target != target || catalog == null || catalog.target != target ||
            catalog.structuralDefinitions.none { it.structure == TargetStructuralProjectionKind.RETRY &&
                it.kind == JenkinsProjectionPayloadKinds.JENKINS_STRUCTURE && it.reference == "retry" }) return declared
        val retries = authorization.graph.nodes.filterIsInstance<CanonicalRetryNode>()
        if (retries.isEmpty() || retries.any { !supports(it.max, it.delay, it.backoff) }) return declared
        // Canonical authorization also binds these concrete task implementations. Native retry
        // creates a new checkout invocation per attempt; it does not clear workspace state.
        val boundRetries = PlanDependencyRelations.flatten(authorization.executionPlan.nodes).filterIsInstance<RetryGroupNode>()
        if (boundRetries.size != retries.size || boundRetries.any { retry ->
            val task = retry.body.singleOrNull() as? TaskNode
            task == null || task.module != "git" || task.action != "checkout"
        }) return declared
        val topology = declared.topologyProfile ?: return declared
        if (topology.target != target || topology.declarations.count { it.kind == ExecutionTopologyKind.ATTEMPT_ISOLATION } != 1)
            return declared
        val scopedTopology = topology.copy(declarations = topology.declarations.map { declaration ->
            if (declaration.kind != ExecutionTopologyKind.ATTEMPT_ISOLATION) declaration else declaration.copy(
                status = ExecutionTopologySupportStatus.SUPPORTED,
                evidenceReference = "src/test/kotlin/JenkinsRetryRuntimeCertificationTests.kt#boundedRetryPreservesAttemptLimitAndEarlySuccess",
                detail = "One native checkout invocation per fixed zero-delay retry attempt; no fresh workspace, value or state isolation claim.")
        })
        return declared.copy(features = declared.features + ("retry.task" to SupportLevel.SUPPORTED),
            topologyProfile = scopedTopology,
            notes = declared.notes + "Jenkins retry is executable only for a single native checkout body with a positive total attempt limit and fixed zero delay; other bodies and policies remain unsupported.")
    }

    companion object {
        fun supports(max: Int, delay: String, backoff: String): Boolean = max > 0 &&
            delay.trim().lowercase() in setOf("0", "0s", "0m", "0h", "0ms") && backoff.equals("fixed", ignoreCase = true)
    }
}
