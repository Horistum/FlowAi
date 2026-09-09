package org.flowlang.generators.manifest

import org.flowlang.adapters.contract.AdapterCatalog
import org.flowlang.capabilities.TargetCapability
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.materialization.TargetDiagnosticMaterializationRequest
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.modules.ModuleRegistry

fun interface TargetProjectionCapabilityResolver {
    fun resolve(authorization: CompilationAuthorization, target: String, declared: TargetCapability): TargetCapability
}

fun interface TargetProjectionExecutionGate {
    fun requireAuthorized(authorization: CompilationAuthorization, target: String)
}

/** Generic orchestration receives an adapter catalog explicitly; it owns no concrete defaults. */
class TargetManifestGenerationPipeline(
    private val targets: Map<String, TargetCapability>,
    private val projections: AdapterCatalog<TargetProjectionProvider>,
    private val capabilityResolvers: List<TargetProjectionCapabilityResolver> = emptyList(),
    private val executionGates: List<TargetProjectionExecutionGate> = emptyList()
) {
    init {
        require(targets.isNotEmpty()) { "Target manifest pipeline requires a non-empty target registry." }
    }

    private val composedCapabilityResolvers: List<TargetProjectionCapabilityResolver> =
        capabilityResolvers + executionGates.filterIsInstance<TargetProjectionCapabilityResolver>()

    fun effectiveTarget(authorization: CompilationAuthorization, target: String): TargetCapability {
        authorization.requireIntegrity()
        val declared = requireNotNull(targets[target]) { "Unknown target '$target'." }
        return composedCapabilityResolvers.fold(declared) { current, resolver ->
            resolver.resolve(authorization, target, current).also { resolved ->
                require(resolved.target == target) {
                    "Capability resolver for '$target' returned capability '${resolved.target}'."
                }
            }
        }
    }

    fun effectiveTargets(authorization: CompilationAuthorization, target: String): Map<String, TargetCapability> =
        targets + (target to effectiveTarget(authorization, target))

    fun generate(request: TargetMaterializationRequest): TargetManifest {
        val rawPlan = request.plan
        ExecutionPlanMaterializationValidator.requireValid(rawPlan, ModuleRegistry())
        val graphAuthorization = request.authorization.also { it.requireIntegrity() }
        require(graphAuthorization.executionPlan == rawPlan) {
            "Planning validation and canonical graph authorization disagree on the execution plan."
        }
        val provider = projections.requireAdapter(request.target)
        val authority = MandatoryMaterializationAuthority(effectiveTargets(graphAuthorization, request.target))
        val authorization = authority.authorize(request)
        executionGates.forEach { gate -> gate.requireAuthorized(authorization.compilationAuthorization, authorization.target) }
        return provider.generate(authorization.toProjectionContext())
    }

    fun generateDiagnosticEvidence(request: TargetDiagnosticMaterializationRequest): TargetManifest {
        val rawPlan = request.plan
        ExecutionPlanMaterializationValidator.requireValid(rawPlan, ModuleRegistry())
        val graphAuthorization = request.authorization.also { it.requireIntegrity() }
        require(graphAuthorization.executionPlan == rawPlan) {
            "Diagnostic planning validation and canonical graph authorization disagree on the execution plan."
        }
        val provider = projections.requireAdapter(request.target)
        val authority = MandatoryMaterializationAuthority(effectiveTargets(graphAuthorization, request.target))
        return provider.generate(authority.authorizeDiagnosticEvidence(request).toProjectionContext())
    }
}

private fun TargetProjectionAuthorization.toProjectionContext(): TargetProjectionContext = TargetProjectionContext(
    compilationAuthorization = compilationAuthorization,
    target = target,
    compatibility = compatibility,
    strict = strict,
    topology = topology,
    diagnosticEvidence = purpose == TargetProjectionAuthorizationPurpose.DIAGNOSTIC_EVIDENCE
)
