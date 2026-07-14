package org.flowlang.scenarios

import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy

/**
 * Evidence-driven target projection classification for reference scenarios.
 *
 * Scenario meaning remains target-neutral. Projection state is calculated only
 * after core validation and target compatibility evidence exist. No target id or
 * scenario id is allowed to predeclare a successful outcome.
 */
object ReferenceAdapterProjectionMatrix {
    val supportedTargets: Set<String> = setOf("jenkins", "github-actions", "tekton")

    fun evaluate(
        scenario: ReferenceScenario,
        target: String,
        coreBlocked: Boolean,
        compatibility: CompatibilityReport? = null,
        manifest: TargetManifest? = null
    ): ReferenceAdapterProjectionExpectation {
        require(target in supportedTargets) { "Unknown reference target '$target'." }
        val compatibilityBlocked = compatibility?.hasErrors == true
        val outcome = when {
            scenario.negativeCoverage || coreBlocked || compatibilityBlocked -> ReferenceAdapterProjectionOutcome.FAIL_FAST
            manifest == null -> error("Non-blocked reference outcome for '$target' requires concrete manifest evidence.")
            else -> when (TargetRenderPolicy.evaluate(manifest).mode) {
                TargetRenderMode.EXECUTABLE -> ReferenceAdapterProjectionOutcome.EXECUTABLE
                TargetRenderMode.REVIEW_ONLY -> ReferenceAdapterProjectionOutcome.REVIEW_ONLY
                TargetRenderMode.FAIL_FAST -> ReferenceAdapterProjectionOutcome.FAIL_FAST
            }
        }
        return ReferenceAdapterProjectionExpectation(
            scenarioId = scenario.id,
            target = target,
            outcome = outcome,
            executable = outcome == ReferenceAdapterProjectionOutcome.EXECUTABLE,
            rationale = rationaleFor(scenario, target, outcome)
        )
    }

    private fun rationaleFor(
        scenario: ReferenceScenario,
        target: String,
        outcome: ReferenceAdapterProjectionOutcome
    ): String = when (outcome) {
        ReferenceAdapterProjectionOutcome.EXECUTABLE ->
            "$target has complete materialization and renderer payload evidence for scenario '${scenario.id}'."
        ReferenceAdapterProjectionOutcome.REVIEW_ONLY ->
            "$target preserves scenario '${scenario.id}' as a non-executable review artifact because materialization or renderer payload evidence is incomplete."
        ReferenceAdapterProjectionOutcome.FAIL_FAST ->
            "$target rejects scenario '${scenario.id}' before target syntax because core validation, compatibility or manifest evidence is blocking."
    }
}

data class ReferenceAdapterProjectionExpectation(
    val scenarioId: String,
    val target: String,
    val outcome: ReferenceAdapterProjectionOutcome,
    val executable: Boolean,
    val rationale: String
)

enum class ReferenceAdapterProjectionOutcome {
    EXECUTABLE,
    REVIEW_ONLY,
    FAIL_FAST
}
