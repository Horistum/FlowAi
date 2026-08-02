package org.flowlang.artifacts

import org.flowlang.standard.FlowStandardVersions

data class ContractStabilityEntry(
    val artifact: String,
    val stability: String,
    val schema: String
)

data class StandardFreezeReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val freezeReportVersion: String = "1.0",
    val profile: String = "0.4-public-draft",
    val status: String,
    val stableContracts: List<ContractStabilityEntry>,
    val experimentalContracts: List<ContractStabilityEntry>,
    val deprecatedContracts: List<ContractStabilityEntry>,
    val breakingChangeRules: List<String>,
    val issues: List<String>
)

data class CompatibilityRule(
    val id: String,
    val severity: String,
    val description: String
)

data class CompatibilityPolicyReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val compatibilityPolicyVersion: String = "1.0",
    val policy: String = "flow-public-contract-compatibility",
    val rules: List<CompatibilityRule>,
    val breakingChangeTriggers: List<String>
)

data class CorpusExampleEntry(
    val id: String,
    val intent: String,
    val expectedArtifacts: List<String>,
    val expectedStatus: String
)

data class ReferenceCorpusReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val corpusVersion: String = "1.0",
    val corpus: String = "reference",
    val examples: List<CorpusExampleEntry>
)

data class NegativeConformanceCase(
    val id: String,
    val reason: String,
    val expectedDiagnostic: String,
    val expectedBlocked: Boolean
)

data class NegativeConformanceCorpusReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val corpusVersion: String = "1.0",
    val corpus: String = "negative",
    val cases: List<NegativeConformanceCase>
)

data class TargetConformanceLevel(
    val level: String,
    val requiredArtifacts: List<String>,
    val requiredInvariants: List<String>
)

data class TargetConformanceProfileReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val conformanceProfileVersion: String = "1.0",
    val profile: String = "target-conformance-profile",
    val levels: List<TargetConformanceLevel>,
    val forbiddenInputs: List<String>,
    val requiredDiagnostics: List<String>
)

data class StandardIndexReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val standardIndexVersion: String = "1.0",
    val draft: String = "FLOW_STANDARD_DRAFT_0_4",
    val artifacts: List<String>,
    val schemas: List<String>,
    val conformanceVectors: List<String>,
    val profiles: List<String>
)

data class ConformanceSuiteReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val conformanceSuiteVersion: String = "1.0",
    val suite: String = "flow-0.4-public-draft",
    val vectors: List<String>,
    val requiredChecks: List<String>,
    val referenceCorpus: String,
    val negativeCorpus: String
)

data class FlowStandardDraftReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val draftVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val status: String,
    val purpose: String,
    val publicArtifacts: List<String>,
    val requiredProfiles: List<String>,
    val complianceStatus: String
)

object PublicStandardDraft {
    fun freeze(contractIndex: StandardContractIndexReport): StandardFreezeReport {
        val missingSchemas = contractIndex.contracts.filter { it.required && it.artifact.endsWith(".json") && it.schema.isBlank() }
        val stable = contractIndex.contracts
            .filter { it.required && it.schema.isNotBlank() }
            .map { ContractStabilityEntry(it.artifact, "stable", it.schema) }
            .sortedBy { it.artifact }
        return StandardFreezeReport(
            status = if (missingSchemas.isEmpty()) "PASS" else "FAIL",
            stableContracts = stable,
            experimentalContracts = emptyList(),
            deprecatedContracts = emptyList(),
            breakingChangeRules = compatibilityPolicy().breakingChangeTriggers,
            issues = missingSchemas.map { "Required JSON artifact '${it.artifact}' has no schema." }
        )
    }

    fun compatibilityPolicy(): CompatibilityPolicyReport = CompatibilityPolicyReport(
        rules = listOf(
            CompatibilityRule("public-field-removal", "breaking", "A public schema field must not be removed without a deprecated phase."),
            CompatibilityRule("enum-value-removal", "breaking", "A public enum value must not be removed without a deprecated phase."),
            CompatibilityRule("new-required-field", "breaking", "Adding a required field to a public schema is a breaking change."),
            CompatibilityRule("schema-version-required", "error", "Public schemas must carry explicit schema identifiers and versioned filenames."),
            CompatibilityRule("diagnostic-code-stability", "error", "Public diagnostic codes must remain stable once published.")
        ),
        breakingChangeTriggers = listOf(
            "remove-public-field",
            "rename-public-field",
            "remove-enum-value",
            "add-required-field",
            "change-diagnostic-code-meaning",
            "drop-required-artifact"
        )
    )

    fun referenceCorpus(): ReferenceCorpusReport = ReferenceCorpusReport(
        examples = listOf(
            CorpusExampleEntry("deploy-with-approval", "Deploy service with manual approval and health verification.", referenceArtifacts(), "PASS"),
            CorpusExampleEntry("backup-with-schedule", "Backup database with explicit schedule and retention.", referenceArtifacts(), "PASS"),
            CorpusExampleEntry("database-migration-with-backup", "Run database migration with backup and rollback plan.", referenceArtifacts(), "PASS"),
            CorpusExampleEntry("certificate-renewal", "Renew certificate and notify owners.", referenceArtifacts(), "PASS"),
            CorpusExampleEntry("kubernetes-maintenance-dry-run", "Run Kubernetes maintenance with dry-run, approval and verification.", referenceArtifacts(), "PASS"),
            CorpusExampleEntry("cleanup-with-retention", "Clean up old artifacts with explicit retention.", referenceArtifacts(), "PASS"),
            CorpusExampleEntry("rollback", "Rollback deployment with verification.", referenceArtifacts(), "PASS"),
            CorpusExampleEntry("incident-response", "Run incident response runbook and collect diagnostics.", referenceArtifacts(), "PASS")
        )
    )

    fun negativeCorpus(): NegativeConformanceCorpusReport = NegativeConformanceCorpusReport(
        cases = listOf(
            NegativeConformanceCase("cleanup-without-retention", "Destructive cleanup lacks retention.", "SAFETY_CLEANUP_REQUIRES_RETENTION", true),
            NegativeConformanceCase("database-migration-without-backup", "Migration lacks backup or restore point.", "SAFETY_REQUIRES_BACKUP", true),
            NegativeConformanceCase("prod-deploy-without-approval", "Production deploy lacks approval.", "SAFETY_REQUIRES_APPROVAL", true),
            NegativeConformanceCase("unknown-target-capability", "Target cannot represent a required capability.", "TARGET_UNSUPPORTED_CAPABILITY", true),
            NegativeConformanceCase("adapter-reads-intent", "Adapter attempts to consume original intent.", "ADAPTER_MUST_NOT_READ_INTENT", true),
            NegativeConformanceCase("missing-public-schema", "Required public JSON artifact has no schema.", "ARTIFACT_SCHEMA_MISSING", true),
            NegativeConformanceCase("unknown-diagnostic-code", "Public report emits an uncataloged diagnostic.", "DIAGNOSTIC_CODE_UNKNOWN", true),
            NegativeConformanceCase("artifact-integrity-failure", "Required public artifact is missing.", "ARTIFACT_REQUIRED_MISSING", true),
            NegativeConformanceCase("architecture-runtime-drift", "A runtime executor package appears in active source.", "ARCHITECTURE_RUNTIME_PACKAGE_FORBIDDEN", true),
            NegativeConformanceCase("architecture-sdk-drift", "A public SDK or plugin direction appears in active source.", "ARCHITECTURE_FORBIDDEN_TERM_IN_SOURCE", true),
            NegativeConformanceCase("kubernetes-maintenance-without-dry-run", "Cluster maintenance lacks dry-run or safe maintenance window.", "SAFETY_REQUIRES_DRY_RUN", true),
            NegativeConformanceCase("secret-rotation-without-subject", "Secret rotation lacks a concrete secret name.", "SECRET_REQUIRES_SUBJECT", true),
            NegativeConformanceCase("certificate-renewal-without-subject", "Certificate renewal lacks a concrete certificate identity.", "CERTIFICATE_REQUIRES_SUBJECT", true),
            NegativeConformanceCase("unsupported-condition-fallback", "Unsupported target condition must not silently become true or false.", "condition.expression", true),
            NegativeConformanceCase("strict-approval-unsupported", "A target that cannot represent strict manual approval must block or degrade explicitly.", "approval.strict", true),
            NegativeConformanceCase("rollback-unsupported", "A target that cannot preserve rollback semantics must block or degrade explicitly.", "rollback.capability", true)
        )
    )

    fun targetConformanceProfile(): TargetConformanceProfileReport = TargetConformanceProfileReport(
        levels = listOf(
            TargetConformanceLevel("plan-reader", listOf("execution-plan.json"), listOf("ADAPTER_MUST_NOT_READ_INTENT")),
            TargetConformanceLevel("readiness-aware", listOf("execution-plan.json", "execution-readiness-report.json"), listOf("ADAPTER_MUST_RESPECT_READINESS")),
            TargetConformanceLevel("manifest-compatible", listOf("target-manifest.json", "adapter-diagnostics.json"), listOf("ADAPTER_MUST_PRESERVE_PLAN_NODE_IDS")),
            TargetConformanceLevel("diagnostic-compliant", listOf("adapter-diagnostics.json", "diagnostic-coverage-report.json"), listOf("ADAPTER_SHOULD_EMIT_DIAGNOSTICS")),
            TargetConformanceLevel("compliance-ready", listOf("standard-compliance-report.json", "artifact-integrity-report.json"), listOf("ADAPTER_MUST_RESPECT_READINESS"))
        ),
        forbiddenInputs = listOf("human-ai-intent", "ai-normalization-report.json", "normalized-intent.json", "intent-design-report.json", "intent-decision-report.json"),
        requiredDiagnostics = listOf("ADAPTER_CONTRACT_READY", "ADAPTER_CONTRACT_DEGRADED", "ADAPTER_CONTRACT_BLOCKED")
    )

    fun standardIndex(
        bundle: FlowArtifactBundleReport,
        contractIndex: StandardContractIndexReport,
        conformanceManifest: ConformanceManifestReport? = null
    ): StandardIndexReport = StandardIndexReport(
        artifacts = bundle.requiredArtifacts.sorted(),
        schemas = contractIndex.publicSchemas,
        conformanceVectors = conformanceManifest?.vectors.orEmpty().map { it.path }.sorted(),
        profiles = listOf("standard-public-release", "target-conformance-profile", "architecture-governance", "0.4-public-draft")
    )

    fun conformanceSuite(conformanceManifest: ConformanceManifestReport? = null): ConformanceSuiteReport = ConformanceSuiteReport(
        vectors = conformanceManifest?.vectors.orEmpty().map { it.path }.sorted(),
        requiredChecks = conformanceManifest?.requiredChecks.orEmpty().sorted(),
        referenceCorpus = "reference-corpus-index.json",
        negativeCorpus = "negative-conformance-corpus.json"
    )

    fun draft(bundle: FlowArtifactBundleReport, compliance: StandardComplianceReport): FlowStandardDraftReport =
        FlowStandardDraftReport(
            status = compliance.status,
            purpose = "Standardize Human/AI intent into validated execution plans and portable target-adapter artifacts.",
            publicArtifacts = bundle.requiredArtifacts.sorted(),
            requiredProfiles = listOf("standard-public-release", "target-conformance-profile", "architecture-governance"),
            complianceStatus = compliance.status
        )

    private fun referenceArtifacts(): List<String> = listOf(
        "normalized-intent.json",
        "intent-decision-report.json",
        "execution-plan.json",
        "capability-negotiation-report.json",
        "execution-readiness-report.json",
        "target-adapter-contract.json",
        "diagnostic-coverage-report.json",
        "artifact-integrity-report.json",
        "standard-compliance-report.json"
    )
}
