package org.flowlang.tests

import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestGenerator
import org.flowlang.generators.manifest.TargetProjectionAuthorization
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.materialization.ExplicitTargetSelection
import org.flowlang.materialization.TargetDiagnosticMaterializationRequest
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.planner.ExecutionPlan
import org.flowlang.topology.ExecutionTopologyMatchingAuthority
import org.flowlang.topology.ExecutionTopologyProfile

internal fun testTargetSelection(
    target: String,
    targets: Map<String, TargetCapability>,
    source: String = "test:explicit-target"
): ExplicitTargetSelection = TargetSelectionAuthority.fromTestFixture(target, source, targets)

internal fun testTargetSelection(
    target: String,
    source: String = "test:projection-fixture"
): ExplicitTargetSelection = testTargetSelection(
    target = target,
    targets = mapOf(target to TargetCapability(target, "Test projection fixture target.")),
    source = source
)

internal fun testMaterializationRequest(
    plan: ExecutionPlan,
    target: String,
    targets: Map<String, TargetCapability>,
    strict: Boolean = false,
    source: String = "test:materialization"
): TargetMaterializationRequest = TargetMaterializationRequest(
    plan = plan,
    selection = testTargetSelection(target, targets, source),
    strict = strict
)

internal fun testDiagnosticMaterializationRequest(
    plan: ExecutionPlan,
    target: String,
    targets: Map<String, TargetCapability>,
    source: String = "test:diagnostic-materialization"
): TargetDiagnosticMaterializationRequest = TargetDiagnosticMaterializationRequest(
    plan = plan,
    selection = testTargetSelection(target, targets, source)
)

/** Low-level projection fixture support. Production callers cannot construct authorization. */
internal fun TargetManifestGenerator.generate(
    plan: ExecutionPlan,
    compatibility: CompatibilityReport,
    strict: Boolean = false
): TargetManifest = generate(TargetProjectionAuthorization(
    plan = plan,
    selection = testTargetSelection(compatibility.target),
    compatibility = compatibility,
    strict = strict,
    topology = ExecutionTopologyMatchingAuthority.assess(
        plan.topologyRequirements,
        ExecutionTopologyProfile.fullySupported(compatibility.target, "test:${compatibility.target}:topology")
    )
))

/** Low-level provider fixture support. Production callers use TargetManifestGenerationPipeline. */
internal fun TargetProjectionProvider.generate(
    plan: ExecutionPlan,
    compatibility: CompatibilityReport,
    strict: Boolean = false
): TargetManifest = generate(TargetProjectionAuthorization(
    plan = plan,
    selection = testTargetSelection(compatibility.target),
    compatibility = compatibility,
    strict = strict,
    topology = ExecutionTopologyMatchingAuthority.assess(
        plan.topologyRequirements,
        ExecutionTopologyProfile.fullySupported(compatibility.target, "test:${compatibility.target}:topology")
    )
))
