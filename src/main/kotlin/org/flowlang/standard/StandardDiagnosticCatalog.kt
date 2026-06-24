package org.flowlang.standard

data class StandardDiagnosticCode(
    val code: String,
    val area: String,
    val severity: String,
    val stability: String = "stable",
    val usedBy: List<String>,
    val description: String
)

data class StandardDiagnosticCatalogReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val diagnosticCatalogVersion: String = "1.0",
    val codes: List<StandardDiagnosticCode>
)

/**
 * Stable diagnostic code catalog for Flow public reports.
 *
 * The catalog is intentionally independent from any specific validator class.
 * Validators, adapters, CI jobs and AI agents should use these codes instead
 * of parsing human-readable messages.
 */
object StandardDiagnosticCatalog {
    val codes: List<StandardDiagnosticCode> = listOf(
        code("INTENT_NAME_EMPTY", "intent", "error", "intent-capability-validation-report.json", "Intent document has an empty name."),
        code("DUPLICATE_INTENT_STEP", "intent", "error", "intent-capability-validation-report.json", "Intent contains duplicate step identifiers."),
        code("UNKNOWN_STEP_DEPENDENCY", "intent", "error", "intent-capability-validation-report.json", "Intent step depends on an unknown step id."),
        code("CYCLIC_STEP_DEPENDENCY", "intent", "error", "intent-capability-validation-report.json", "Intent step dependencies contain a cycle."),
        code("UNKNOWN_SYSTEM_TYPE", "intent", "warning", "intent-capability-validation-report.json", "Intent references a system type outside the loaded module contracts."),
        code("MISSING_REQUIRED_STEP_PARAM", "intent", "error", "intent-capability-validation-report.json", "Intent step misses a parameter required by its standard capability contract."),
        code("UNKNOWN_STEP_PARAM", "intent", "warning", "intent-capability-validation-report.json", "Intent step declares a parameter outside its capability contract."),
        code("UNKNOWN_INTENT_SYSTEM", "intent", "error", "intent-capability-validation-report.json", "Intent step references an undeclared system."),
        code("INTENT_SYSTEM_TYPE_MISMATCH", "intent", "error", "intent-capability-validation-report.json", "Intent step references a system whose type does not satisfy the capability requirement."),
        code("MISSING_REQUIRED_SYSTEM", "intent", "error", "intent-capability-validation-report.json", "Intent requires a system that is not declared."),
        code("MISSING_SYSTEM_CONFIG", "intent", "error", "intent-capability-validation-report.json", "A declared system misses required configuration."),
        code("UNKNOWN_SYSTEM_CONFIG", "intent", "warning", "intent-capability-validation-report.json", "A declared system contains configuration not defined by its module contract."),
        code("SECRET_CONFIG_NOT_SECRET_REF", "intent", "error", "intent-capability-validation-report.json", "Sensitive system configuration must be represented as a secret reference."),
        code("SYSTEM_CONFIG_TYPE_MISMATCH", "intent", "error", "intent-capability-validation-report.json", "System configuration value does not match the required schema type."),

        code("TARGET_NOT_FOUND", "flow-validation", "error", "validation-report.json", "Flow AST action targets an unknown system."),
        code("TARGET_TYPE_INVALID", "flow-validation", "error", "validation-report.json", "Flow AST action targets a system type not accepted by the module action."),
        code("MISSING_PARAM", "flow-validation", "error", "validation-report.json", "Flow AST action misses a required module parameter."),
        code("SAFETY_REQUIRED", "flow-validation", "error", "validation-report.json", "Destructive Flow AST action lacks a safety rule."),

        code("SAFETY_REQUIRES_CLARIFICATION", "safety", "error", "intent-decision-report.json", "Safety policy requires clarification before lowering."),
        code("SAFETY_UNMITIGATED_HIGH_RISK", "safety", "error", "intent-decision-report.json", "Intent contains an unmitigated high risk."),
        code("SAFETY_REQUIRES_APPROVAL", "safety", "error", "intent-capability-validation-report.json", "Safety policy requires approval."),
        code("SAFETY_REQUIRES_DRY_RUN", "safety", "error", "intent-capability-validation-report.json", "Safety policy requires dry-run confirmation."),
        code("SAFETY_REQUIRES_BACKUP", "safety", "error", "intent-capability-validation-report.json", "Safety policy requires a backup."),
        code("SAFETY_REQUIRES_ROLLBACK_PLAN", "safety", "error", "intent-capability-validation-report.json", "Safety policy requires a rollback plan."),
        code("SAFETY_REQUIRES_CHANGE_TICKET", "safety", "error", "intent-capability-validation-report.json", "Safety policy requires a change ticket."),
        code("SAFETY_DESTRUCTIVE_OPERATION", "safety", "error", "intent-capability-validation-report.json", "Destructive operation lacks sufficient safety mitigation."),
        code("SAFETY_EXTERNAL_SIDE_EFFECT", "safety", "error", "intent-capability-validation-report.json", "External side effect lacks approval or notification guardrail."),
        code("SAFETY_CLEANUP_REQUIRES_RETENTION", "safety", "error", "intent-capability-validation-report.json", "Cleanup operation requires explicit retention or safety rule."),

        code("TARGET_UNSUPPORTED_FEATURE", "target", "error", "execution-readiness-report.json", "Target cannot represent a required feature."),
        code("TARGET_STRICT_PARTIAL_FEATURE", "target", "error", "execution-readiness-report.json", "Strict mode treats partial target support as blocking."),
        code("TARGET_PARTIAL_FEATURE", "target", "warning", "execution-readiness-report.json", "Target can represent a feature only partially."),
        code("TARGET_UNSUPPORTED_CAPABILITY", "target", "error", "execution-readiness-report.json", "Target does not support a required capability."),
        code("TARGET_REQUIRES_RUNTIME", "target", "warning", "execution-readiness-report.json", "Target requires Flow runtime or equivalent adapter support for a capability."),
        code("TARGET_PARTIAL_CAPABILITY", "target", "warning", "execution-readiness-report.json", "Target only partially supports a required capability."),
        code("UNKNOWN_TARGET", "target", "error", "execution-readiness-report.json", "Requested target is not present in the target registry."),

        code("ADAPTER_MUST_NOT_READ_INTENT", "adapter", "invariant", "target-adapter-contract.json", "Adapter must not consume or reinterpret human/AI intent artifacts."),
        code("ADAPTER_MUST_PRESERVE_PLAN_NODE_IDS", "adapter", "invariant", "target-adapter-contract.json", "Adapter output must preserve ExecutionPlan node identity."),
        code("ADAPTER_MUST_RESPECT_READINESS", "adapter", "invariant", "target-adapter-contract.json", "Adapter must not generate when readiness blocks generation."),
        code("ADAPTER_SHOULD_EMIT_DIAGNOSTICS", "adapter", "invariant", "target-adapter-contract.json", "Adapter should emit diagnostics for generated, degraded and blocked conversions."),
        code("ADAPTER_CONTRACT_READY", "adapter", "info", "adapter-diagnostics.json", "Adapter contract permits target manifest generation."),
        code("ADAPTER_CONTRACT_DEGRADED", "adapter", "warning", "adapter-diagnostics.json", "Adapter may generate only with documented target limitations."),
        code("ADAPTER_CONTRACT_BLOCKED", "adapter", "error", "adapter-diagnostics.json", "Adapter must not generate target manifest while readiness is blocked."),

        code("ACTION_TARGET_TYPES_MISSING", "module", "warning", "capability-module-contract-report.json", "Module action should declare targetTypes."),
        code("ARTIFACT_REQUIRED_MISSING", "artifact-integrity", "error", "artifact-integrity-report.json", "A required public artifact is missing from the artifact set."),
        code("ARTIFACT_SCHEMA_MISSING", "artifact-integrity", "warning", "artifact-integrity-report.json", "A public JSON artifact is missing an explicit schema declaration."),
        code("ARTIFACT_STANDARD_VERSION_MISMATCH", "artifact-integrity", "error", "artifact-integrity-report.json", "A public artifact declares a different Flow standard version."),
        code("ARTIFACT_DIAGNOSTIC_COVERAGE_FAILED", "artifact-integrity", "error", "artifact-integrity-report.json", "Diagnostic coverage failed for the artifact set."),
        code("DIAGNOSTIC_CODE_UNKNOWN", "diagnostic-coverage", "error", "diagnostic-coverage-report.json", "A public diagnostic report emitted a code outside the standard diagnostic catalog."),
        code("SCHEMA_PUBLIC_OUTPUT_INVALID", "schema", "error", "conformance-manifest.json", "A public output does not satisfy its JSON schema."),
        code("ARCHITECTURE_GOVERNANCE_FILE_MISSING", "architecture", "error", "conformance-manifest.json", "A required architecture governance file is missing."),
        code("ARCHITECTURE_GOVERNANCE_TERM_MISSING", "architecture", "error", "conformance-manifest.json", "A required architecture governance term is missing."),
        code("ARCHITECTURE_FORBIDDEN_DIRECTION_MISSING", "architecture", "error", "conformance-manifest.json", "A forbidden architecture direction is not documented."),
        code("ARCHITECTURE_RUNTIME_PACKAGE_FORBIDDEN", "architecture", "error", "conformance-manifest.json", "Active source exposes a runtime package, which is outside the Flow standard boundary."),
        code("ARCHITECTURE_FORBIDDEN_TERM_IN_SOURCE", "architecture", "error", "conformance-manifest.json", "Active source contains a term that indicates SDK, runtime, plugin or silent fallback drift."),
        code("CONFORMANCE_CHECK_FAILED", "conformance", "error", "conformance-manifest.json", "A required conformance check failed.")
    ).sortedBy { it.code }

    fun report(): StandardDiagnosticCatalogReport = StandardDiagnosticCatalogReport(codes = codes)

    fun markdown(): String = buildString {
        appendLine("# Flow Standard Diagnostic Codes")
        codes.groupBy { it.area }.toSortedMap().forEach { (area, areaCodes) ->
            appendLine()
            appendLine("## $area")
            areaCodes.forEach { code ->
                appendLine("- `${code.code}` (${code.severity}): ${code.description}")
            }
        }
    }

    private fun code(
        code: String,
        area: String,
        severity: String,
        usedBy: String,
        description: String
    ): StandardDiagnosticCode =
        StandardDiagnosticCode(code, area, severity, usedBy = listOf(usedBy), description = description)
}
