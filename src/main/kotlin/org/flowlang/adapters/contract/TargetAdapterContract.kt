package org.flowlang.adapters.contract

import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.capabilities.ExecutionReadinessStatus
import org.flowlang.capabilities.TargetCapability
import org.flowlang.planner.ExecutionPlan
import org.flowlang.standard.FlowStandardVersions

enum class AdapterArtifactRole { INPUT, OUTPUT, DIAGNOSTIC }

enum class AdapterInvariantSeverity { MUST, SHOULD }

enum class AdapterDiagnosticSeverity { INFO, WARNING, ERROR }

data class TargetAdapterArtifact(
    val name: String,
    val role: AdapterArtifactRole,
    val schema: String = "",
    val required: Boolean
)

data class TargetAdapterInvariant(
    val code: String,
    val severity: AdapterInvariantSeverity,
    val description: String
)

data class AdapterDiagnosticIssue(
    val code: String,
    val severity: AdapterDiagnosticSeverity,
    val artifact: String,
    val message: String
)

data class AdapterDiagnosticsReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val diagnosticsVersion: String = "1.0",
    val flowName: String,
    val target: String,
    val status: ExecutionReadinessStatus,
    val generationAllowed: Boolean,
    val issues: List<AdapterDiagnosticIssue>
)

data class TargetAdapterContractReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val adapterContractVersion: String = "1.0",
    val flowName: String,
    val target: String,
    val planVersion: String,
    val strict: Boolean,
    val generationAllowed: Boolean,
    val productionReady: Boolean,
    val allowedInputArtifacts: List<TargetAdapterArtifact>,
    val expectedOutputArtifacts: List<TargetAdapterArtifact>,
    val forbiddenInputArtifacts: List<String>,
    val invariants: List<TargetAdapterInvariant>,
    val diagnostics: AdapterDiagnosticsReport
)

/**
 * Defines the public target adapter boundary.
 *
 * This is deliberately not an SDK and not a runtime. It is a standard contract:
 * what an adapter may read, what it should emit, and which semantic invariants
 * must remain true while converting an ExecutionPlan into target artifacts.
 */
class TargetAdapterContractAnalyzer(private val targets: Map<String, TargetCapability>) {
    fun analyze(plan: ExecutionPlan, target: String, strict: Boolean = false): TargetAdapterContractReport {
        val readiness = ExecutionReadinessAnalyzer(targets).analyze(plan, target, strict = strict)
        val diagnostics = diagnostics(plan.flowName, target, readiness.readiness, readiness.generationAllowed)
        return TargetAdapterContractReport(
            flowName = plan.flowName,
            target = target,
            planVersion = plan.planVersion,
            strict = strict,
            generationAllowed = readiness.generationAllowed,
            productionReady = readiness.productionReady,
            allowedInputArtifacts = allowedInputs(),
            expectedOutputArtifacts = expectedOutputs(readiness.generationAllowed),
            forbiddenInputArtifacts = forbiddenInputs(),
            invariants = invariants(),
            diagnostics = diagnostics
        )
    }

    private fun allowedInputs(): List<TargetAdapterArtifact> = listOf(
        TargetAdapterArtifact("execution-plan.json", AdapterArtifactRole.INPUT, "schemas/execution-plan.schema.json", required = true),
        TargetAdapterArtifact("canonical-execution-plan.json", AdapterArtifactRole.INPUT, "schemas/execution-plan.schema.json", required = true),
        TargetAdapterArtifact("execution-readiness-report.json", AdapterArtifactRole.INPUT, "schemas/execution-readiness-report.schema.json", required = true),
        TargetAdapterArtifact("target-selection-report.json", AdapterArtifactRole.INPUT, "schemas/target-selection-report.schema.json", required = true),
        TargetAdapterArtifact("target-decision-trace-report.json", AdapterArtifactRole.INPUT, "schemas/target-decision-trace-report.schema.json", required = true)
    )

    private fun expectedOutputs(generationAllowed: Boolean): List<TargetAdapterArtifact> = listOf(
        TargetAdapterArtifact("target-manifest.json", AdapterArtifactRole.OUTPUT, "schemas/target-manifest.schema.json", required = generationAllowed),
        TargetAdapterArtifact("rendered-target-artifact", AdapterArtifactRole.OUTPUT, required = false),
        TargetAdapterArtifact("adapter-diagnostics.json", AdapterArtifactRole.DIAGNOSTIC, "schemas/adapter-diagnostics.schema.json", required = true)
    )

    private fun forbiddenInputs(): List<String> = listOf(
        "human-ai-intent",
        "ai-normalization-report.json",
        "normalized-intent.json",
        "intent-design-report.json",
        "intent-decision-report.json",
        "intent-capability-validation-report.json"
    )

    private fun invariants(): List<TargetAdapterInvariant> = listOf(
        TargetAdapterInvariant(
            "ADAPTER_MUST_NOT_READ_INTENT",
            AdapterInvariantSeverity.MUST,
            "Adapter must consume ExecutionPlan and target reports only; it must not reinterpret human or AI intent."
        ),
        TargetAdapterInvariant(
            "ADAPTER_MUST_PRESERVE_PLAN_NODE_IDS",
            AdapterInvariantSeverity.MUST,
            "Adapter output must preserve ExecutionPlan node identity in manifests or diagnostics."
        ),
        TargetAdapterInvariant(
            "ADAPTER_MUST_RESPECT_READINESS",
            AdapterInvariantSeverity.MUST,
            "Adapter must not emit a target manifest when execution-readiness generationAllowed is false."
        ),
        TargetAdapterInvariant(
            "ADAPTER_SHOULD_EMIT_DIAGNOSTICS",
            AdapterInvariantSeverity.SHOULD,
            "Adapter should emit adapter-diagnostics.json for generated, degraded and blocked conversions."
        )
    )

    private fun diagnostics(
        flowName: String,
        target: String,
        status: ExecutionReadinessStatus,
        generationAllowed: Boolean
    ): AdapterDiagnosticsReport {
        val issues = when {
            generationAllowed && status == ExecutionReadinessStatus.READY -> listOf(
                AdapterDiagnosticIssue(
                    "ADAPTER_CONTRACT_READY",
                    AdapterDiagnosticSeverity.INFO,
                    "target-adapter-contract.json",
                    "Adapter contract permits target manifest generation."
                )
            )
            generationAllowed -> listOf(
                AdapterDiagnosticIssue(
                    "ADAPTER_CONTRACT_DEGRADED",
                    AdapterDiagnosticSeverity.WARNING,
                    "execution-readiness-report.json",
                    "Adapter may generate only with documented target limitations."
                )
            )
            else -> listOf(
                AdapterDiagnosticIssue(
                    "ADAPTER_CONTRACT_BLOCKED",
                    AdapterDiagnosticSeverity.ERROR,
                    "execution-readiness-report.json",
                    "Adapter must not generate target manifest while readiness is blocked."
                )
            )
        }
        return AdapterDiagnosticsReport(
            flowName = flowName,
            target = target,
            status = status,
            generationAllowed = generationAllowed,
            issues = issues
        )
    }
}
