package org.flowlang.artifacts

import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.StandardModel
import org.flowlang.targets.TargetRegistryYamlLoader
import java.io.File

data class PublicSurfaceEntry(
    val artifact: String,
    val schema: String,
    val stability: String,
    val area: String,
    val introducedIn: String,
    val changeGate: String,
    val notes: String
)

data class PublicStandardSurfaceReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val surfaceVersion: String = "1.0",
    val status: String,
    val entries: List<PublicSurfaceEntry>,
    val stableArtifacts: List<String>,
    val draftArtifacts: List<String>,
    val experimentalArtifacts: List<String>,
    val internalArtifacts: List<String>,
    val requiredChangeGates: List<String>
)

data class CompatibilityPolicyRule(
    val id: String,
    val category: String,
    val allowedInMinor: Boolean,
    val requiresMigrationNote: Boolean,
    val requiresDeprecationWindow: Boolean,
    val description: String
)

data class CompatibilityMigrationPolicyReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val policyVersion: String = "1.1",
    val status: String,
    val compatibilityRules: List<CompatibilityPolicyRule>,
    val deprecationWindowMinorReleases: Int,
    val migrationArtifacts: List<String>,
    val breakingChangeGate: String
)

data class ReferenceIntentScenario(
    val id: String,
    val title: String,
    val inputText: String,
    val expectedStatus: String,
    val expectedCapabilities: List<String>,
    val expectedRequiredClarifications: List<String>,
    val expectedRejectionCodes: List<String>,
    val expectedEntities: Map<String, String>,
    val notes: String
)

data class ReferenceIntentCorpusReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val corpusVersion: String = "2.0",
    val status: String,
    val scenarios: List<ReferenceIntentScenario>,
    val requiredScenarioIds: List<String>,
    val negativeScenarioIds: List<String>
)

data class ReferenceCorpusHarnessAssertion(
    val id: String,
    val scope: String,
    val blocking: Boolean,
    val description: String
)

data class ReferenceCorpusExecutionHarnessReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val harnessVersion: String = "1.0",
    val status: String,
    val replayStages: List<String>,
    val assertions: List<ReferenceCorpusHarnessAssertion>,
    val requiredScenarioIds: List<String>,
    val negativeScenarioIds: List<String>,
    val mustLowerAccepted: Boolean,
    val mustBlockNegative: Boolean,
    val notes: String
)

data class RequiredClarificationRule(
    val id: String,
    val capability: String,
    val field: String,
    val blocking: Boolean,
    val reason: String
)

data class SafetyPolicyMatrixEntry(
    val capability: String,
    val risk: String,
    val requiredMitigations: List<String>,
    val blockingDiagnostic: String
)

data class ExecutionPlanInvariant(
    val id: String,
    val description: String,
    val blocking: Boolean
)

data class AiTrustBoundaryRule(
    val id: String,
    val rule: String,
    val blocking: Boolean
)

data class StandardExampleBundleEntry(
    val id: String,
    val intentScenarioId: String,
    val expectedStatus: String,
    val expectedArtifacts: List<String>
)

data class CompatibilityPromiseRule(
    val id: String,
    val category: String,
    val promise: String,
    val requiresConformanceVector: Boolean
)

data class PublicContractAliasInvariant(
    val id: String,
    val canonicalField: String,
    val aliasField: String,
    val removalTarget: String,
    val blocking: Boolean,
    val description: String
)

data class TargetSemanticsEntry(
    val feature: String,
    val semanticsByTarget: Map<String, String>,
    val requiredDiagnosticWhenUnsupported: String
)

data class TargetSemanticsMatrixReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val matrixVersion: String = "2.0",
    val status: String,
    val targetIds: List<String>,
    val entries: List<TargetSemanticsEntry>,
    val portabilityRule: String
)

data class StandardExportBundleReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val exportBundleVersion: String = "1.0",
    val status: String,
    val command: String,
    val requiredDirectories: List<String>,
    val requiredFiles: List<String>,
    val requiredArtifacts: List<String>,
    val packageName: String
)

data class ConformanceLevelEntry(
    val id: String,
    val title: String,
    val requiredChecks: List<String>,
    val requiredArtifacts: List<String>,
    val description: String
)

data class ConformanceLevelsReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val conformanceLevelsVersion: String = "1.0",
    val status: String,
    val defaultLevel: String,
    val levels: List<ConformanceLevelEntry>,
    val levelOrder: List<String>
)

data class StandardExportManifestReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val manifestVersion: String = "1.3",
    val status: String,
    val candidate: String,
    val requiredDocuments: List<String>,
    val requiredJsonArtifacts: List<String>,
    val requiredSchemas: List<String>,
    val requiredDirectories: List<String>,
    val implementationBoundary: List<String>,
    val nonGoals: List<String>,
    val acceptanceCriteria: List<String> = emptyList(),
    val releaseGateChecks: List<String> = emptyList(),
    val evidenceArtifacts: List<String> = emptyList(),
    val selfVerificationCommands: List<String> = emptyList(),
    val bundleVerificationChecks: List<String> = emptyList(),
    val verificationInputs: List<String> = emptyList()
)

object StandardSurface {
    fun publicSurface(): PublicStandardSurfaceReport {
        val entries = StandardModel.artifacts.map { artifact ->
            PublicSurfaceEntry(
                artifact = artifact.artifact,
                schema = artifact.schema,
                stability = artifact.stability,
                area = artifact.area,
                introducedIn = artifact.introducedIn,
                changeGate = artifact.changeGate,
                notes = artifact.notes
            )
        }
        val requiredChangeGates = listOf(
            "public-surface-review",
            "schema-compatibility-review",
            "migration-note-review",
            "negative-conformance-review"
        )
        return PublicStandardSurfaceReport(
            status = StandardSurfaceStatusAuthority.publicSurface(entries, requiredChangeGates),
            entries = entries,
            stableArtifacts = entries.filter { it.stability == "stable" }.map { it.artifact },
            draftArtifacts = entries.filter { it.stability == "draft" }.map { it.artifact },
            experimentalArtifacts = entries.filter { it.stability == "experimental" }.map { it.artifact },
            internalArtifacts = entries.filter { it.stability == "internal" }.map { it.artifact },
            requiredChangeGates = requiredChangeGates
        )
    }

    fun compatibilityMigrationPolicy(): CompatibilityMigrationPolicyReport {
        val rules = listOf(
            CompatibilityPolicyRule("minor.add-optional-field", "minor", true, false, false, "A minor release may add optional fields to public JSON artifacts."),
            CompatibilityPolicyRule("minor.add-conformance-vector", "minor", true, false, false, "A minor release may add stricter conformance vectors when the behavior was already required by the standard."),
            CompatibilityPolicyRule("breaking.remove-public-field", "breaking", false, true, true, "Removing or renaming a public field is a breaking change."),
            CompatibilityPolicyRule("breaking-change-semantics", "breaking", false, true, true, "Changing the meaning of an existing capability, diagnostic or safety invariant is a breaking change.")
        )
        val window = 2
        val migrationArtifacts = listOf(
            "CHANGELOG.md",
            "docs/COMPATIBILITY_POLICY.md",
            "standard-release-profile.json",
            "conformance-manifest.json"
        )
        val gate = "major-version-or-explicit-standard-review"
        return CompatibilityMigrationPolicyReport(
            status = StandardSurfaceStatusAuthority.compatibilityMigrationPolicy(rules, window, migrationArtifacts, gate),
            compatibilityRules = rules,
            deprecationWindowMinorReleases = window,
            migrationArtifacts = migrationArtifacts,
            breakingChangeGate = gate
        )
    }

    fun referenceIntentCorpus(): ReferenceIntentCorpusReport {
        val scenarios = listOf(
            accepted("build-and-test", "Build and test", "Check out the repository, build the project and run the tests.", listOf("CHECKOUT", "BUILD", "TEST")),
            accepted("deploy-with-approval-and-rollback", "Deployment with approval, verification and rollback", "Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure.", listOf("DEPLOY", "APPROVE", "VERIFY", "ROLLBACK"), mapOf("application" to "billing-api")),
            accepted("cleanup-with-retention", "Cleanup with retention", "Clean up resources older than 30 days.", listOf("CLEANUP")),
            accepted("kubernetes-maintenance-dry-run", "Kubernetes maintenance with dry-run", "Perform kubernetes maintenance in namespace payments as a dry run.", listOf("KUBERNETES_MAINTENANCE"), mapOf("scope" to "payments")),
            accepted("database-migration-with-backup", "Database migration with backup and rollback", "Create a backup of database orders, run migration v42 on database orders, verify and rollback on failure.", listOf("BACKUP", "DATABASE_MIGRATE", "ROLLBACK"), mapOf("database" to "orders")),
            accepted("certificate-renewal-with-window", "Certificate renewal with maintenance window", "Renew certificate api-tls for api.example.com during the Sunday maintenance window and notify owners.", listOf("CERTIFICATE_RENEW", "NOTIFY"), mapOf("certificate" to "api-tls", "window" to "sunday maintenance")),
            accepted("secret-rotation-with-audit", "Secret rotation with audit notification", "Rotate secret api-token, notify the platform channel and verify rollout.", listOf("SECRET_ROTATE", "APPROVE", "NOTIFY", "VERIFY"), mapOf("secret" to "api-token")),
            accepted("backup-with-retention", "Backup with explicit retention", "Back up database invoices every night and retain backups for 30 days.", listOf("BACKUP"), mapOf("database" to "invoices", "retention" to "30 days")),
            accepted("rollback-with-verification", "Rollback with verification", "Rollback application checkout-api to the previous version and verify health afterwards.", listOf("ROLLBACK", "VERIFY"), mapOf("application" to "checkout-api")),
            accepted("portable-build-test-deploy", "Portable build/test/deploy", "Build, test and deploy inventory-api with approval and health verification.", listOf("BUILD_IMAGE", "TEST", "DEPLOY", "APPROVE", "VERIFY"), mapOf("application" to "inventory-api")),
            blockedByClarification("deploy-missing-application", "Deployment with missing application", "Deploy to Kubernetes with health verification.", listOf("DEPLOY"), "entities.application.name"),
            blockedByClarification("deploy-missing-environment", "Deployment with missing environment", "Deploy billing-api with health verification.", listOf("DEPLOY"), "entities.environment"),
            blockedByClarification("cleanup-without-retention", "Cleanup without retention", "Clean up temporary resources.", listOf("CLEANUP"), "safety.cleanup.retention"),
            blockedByClarification("secret-rotation-unnamed-secret", "Secret rotation without a concrete secret name", "Rotate a secret.", listOf("SECRET_ROTATE"), "entities.secret.name"),
            blockedByClarification("certificate-renewal", "Certificate renewal without an identified certificate", "Renew the TLS certificate for the api service.", listOf("CERTIFICATE_RENEW"), "entities.certificate"),
            blockedByClarification("maintenance-missing-window", "Maintenance without window", "Perform Kubernetes maintenance in production.", listOf("KUBERNETES_MAINTENANCE"), "safety.maintenance.window"),
            blockedByClarification("approval-missing-owner", "Approval without owner", "Deploy payment-api to production after approval.", listOf("DEPLOY", "APPROVE"), "approval.owner"),
            blockedByGate("database-migration-without-backup", "Database migration without backup", "Run a database migration on the orders database.", listOf("DATABASE_MIGRATE"), "SAFETY_REQUIRES_BACKUP"),
            blockedByGate("kubernetes-maintenance-without-dry-run", "Kubernetes maintenance without dry-run", "Apply cluster maintenance to production now.", listOf("KUBERNETES_MAINTENANCE"), "SAFETY_REQUIRES_DRY_RUN"),
            blockedByGate("prod-deploy-without-approval", "Production deploy without approval", "Deploy billing-api to production without approval.", listOf("DEPLOY"), "SAFETY_REQUIRES_APPROVAL")
        )
        val requiredScenarioIds = scenarios.map { it.id }
        val negativeScenarioIds = scenarios.filter { it.expectedStatus == "BLOCKED" }.map { it.id }
        return ReferenceIntentCorpusReport(
            status = StandardSurfaceStatusAuthority.referenceIntentCorpus(scenarios, requiredScenarioIds, negativeScenarioIds),
            scenarios = scenarios,
            requiredScenarioIds = requiredScenarioIds,
            negativeScenarioIds = negativeScenarioIds
        )
    }

    fun referenceCorpusExecutionHarness(): ReferenceCorpusExecutionHarnessReport {
        val corpus = referenceIntentCorpus()
        val replayStages = listOf(
            "normalize scenario input with ScenarioPackIntentNormalizer",
            "compare selected scenario output against reference expected capabilities, entities, clarifications and rejection codes",
            "review normalized intent through IntentProposalReview",
            "lower accepted scenarios through intent validation, AST validation and execution-plan planning",
            "prove blocked scenarios are not lowerable"
        )
        val assertions = listOf(
            ReferenceCorpusHarnessAssertion("corpus.actual-normalizer", "all", true, "The reference corpus must be replayed through the real deterministic normalizer, not compared against static metadata."),
            ReferenceCorpusHarnessAssertion("corpus.accepted-lowers", "accepted", true, "Every accepted reference scenario must pass normalization, proposal review, intent validation, AST validation and execution planning."),
            ReferenceCorpusHarnessAssertion("corpus.blocked-stays-blocked", "blocked", true, "Every blocked reference scenario must remain blocked by a required clarification or safety diagnostic."),
            ReferenceCorpusHarnessAssertion("corpus.entities-observed", "all", true, "Expected entities must be observed in the normalization report with canonical values."),
            ReferenceCorpusHarnessAssertion("corpus.no-custom-fallback", "reference", true, "Reference scenarios must select a standard scenario pack rather than the custom fallback."),
            ReferenceCorpusHarnessAssertion("corpus.no-auto-approval", "safety", true, "Production risk may create an approval obligation, but it must not synthesize an APPROVE step or APPROVAL policy without explicit user intent."),
            ReferenceCorpusHarnessAssertion("corpus.rollback-review-lowerable", "rollback", true, "Rollback review intents must remain lowerable; missing application context is a review question, not a blocking clarification."),
            ReferenceCorpusHarnessAssertion("corpus.semantic-capabilities", "capabilities", true, "Capability semantics must stay precise, for example rollout verification uses VERIFY rather than generic VALIDATE."),
            ReferenceCorpusHarnessAssertion("corpus.negative-reason", "blocked", true, "Every blocked reference scenario must declare an expected clarification field or expected rejection code."),
            ReferenceCorpusHarnessAssertion("corpus.plan-not-empty", "accepted", true, "Accepted reference scenarios must produce a non-empty execution plan.")
        )
        val mustLowerAccepted = true
        val mustBlockNegative = true
        return ReferenceCorpusExecutionHarnessReport(
            status = StandardSurfaceStatusAuthority.referenceCorpusExecutionHarness(corpus, replayStages, assertions, mustLowerAccepted, mustBlockNegative),
            replayStages = replayStages,
            assertions = assertions,
            requiredScenarioIds = corpus.requiredScenarioIds,
            negativeScenarioIds = corpus.negativeScenarioIds,
            mustLowerAccepted = mustLowerAccepted,
            mustBlockNegative = mustBlockNegative,
            notes = "v0.7.0 turns the reference intent corpus into an executable standard harness while keeping the core standard free of runtime, SDK and plugin-framework behavior."
        )
    }

    fun requiredClarificationContract(): List<RequiredClarificationRule> = listOf(
        RequiredClarificationRule("clarify.application", "DEPLOY", "entities.application.name", true, "A deployment target must name the application or service."),
        RequiredClarificationRule("clarify.environment", "DEPLOY", "entities.environment", true, "A production-impacting deployment must name the environment."),
        RequiredClarificationRule("clarify.backup", "DATABASE_MIGRATE", "safety.backup", true, "A database migration must have a backup or restore point."),
        RequiredClarificationRule("clarify.retention", "CLEANUP", "safety.cleanup.retention", true, "A cleanup intent must define retention before lowering."),
        RequiredClarificationRule("clarify.approval-owner", "APPROVE", "approval.owner", true, "A manual approval must identify an owner or approval authority."),
        RequiredClarificationRule("clarify.maintenance-window", "KUBERNETES_MAINTENANCE", "safety.maintenance.window", true, "Production maintenance must define a safe execution window."),
        RequiredClarificationRule("clarify.secret-name", "SECRET_ROTATE", "entities.secret.name", true, "Secret rotation must identify the concrete secret."),
        RequiredClarificationRule("clarify.certificate", "CERTIFICATE_RENEW", "entities.certificate", true, "Certificate renewal must identify the certificate or DNS name.")
    )

    fun safetyPolicyMatrix(): List<SafetyPolicyMatrixEntry> = listOf(
        SafetyPolicyMatrixEntry("DATABASE_MIGRATE", "data-loss", listOf("backup", "restore-point", "rollback-plan"), "SAFETY_REQUIRES_BACKUP"),
        SafetyPolicyMatrixEntry("CLEANUP", "destructive-delete", listOf("retention-policy"), "SAFETY_CLEANUP_REQUIRES_RETENTION"),
        SafetyPolicyMatrixEntry("KUBERNETES_MAINTENANCE", "cluster-outage", listOf("dry-run", "maintenance-window"), "SAFETY_REQUIRES_DRY_RUN"),
        SafetyPolicyMatrixEntry("SECRET_ROTATE", "authentication-breakage", listOf("secret-name", "rollout-verification", "audit-notification"), "SECRET_REQUIRES_SUBJECT"),
        SafetyPolicyMatrixEntry("DEPLOY", "service-outage", listOf("approval", "health-verification", "rollback-plan"), "SAFETY_REQUIRES_APPROVAL"),
        SafetyPolicyMatrixEntry("CERTIFICATE_RENEW", "traffic-interruption", listOf("certificate-identity", "expiry-window", "owner-notification"), "CERTIFICATE_REQUIRES_SUBJECT")
    )

    fun executionPlanSemanticInvariants(): List<ExecutionPlanInvariant> = listOf(
        ExecutionPlanInvariant("plan.node-ids-unique", "Every canonical execution-plan node id must be unique.", true),
        ExecutionPlanInvariant("plan.dependencies-known", "Every task dependency must reference an existing plan node id.", true),
        ExecutionPlanInvariant("plan.no-cycles", "Execution-plan dependencies must form a DAG.", true),
        ExecutionPlanInvariant("plan.rollback-references-existing-work", "Rollback must be represented as an explicit task or error handler, not an implicit renderer behavior.", true),
        ExecutionPlanInvariant("plan.approval-before-destructive", "Approval or safety mitigation must precede destructive production actions.", true),
        ExecutionPlanInvariant("plan.no-target-specific-leakage", "The canonical execution plan must not contain Jenkins/GitHub/Tekton syntax.", true)
    )

    fun aiInputTrustBoundary(): List<AiTrustBoundaryRule> = listOf(
        AiTrustBoundaryRule("ai.proposal-only", "AI output is a proposal and must be normalized into IntentDocument before lowering.", true),
        AiTrustBoundaryRule("ai.required-clarification-blocks", "Required clarifications block lowering and generation.", true),
        AiTrustBoundaryRule("ai.no-critical-defaults", "Critical safety, resource and approval values must not be silently defaulted.", true),
        AiTrustBoundaryRule("ai.validation-before-plan", "Intent validation and capability validation must pass before AST/planner lowering.", true),
        AiTrustBoundaryRule("ai.target-syntax-after-plan", "Target-specific syntax may only be produced from execution-plan/target-manifest artifacts.", true),
        AiTrustBoundaryRule("ai.audit-trace", "Proposal review, decision and diagnostics must be auditable through public artifacts.", true)
    )

    fun standardExampleBundle(): List<StandardExampleBundleEntry> = listOf(
        StandardExampleBundleEntry("example.valid-deploy", "deploy-with-approval-and-rollback", "ACCEPTED", standardExampleArtifacts()),
        StandardExampleBundleEntry("example.blocked-migration", "database-migration-without-backup", "BLOCKED", standardExampleArtifacts()),
        StandardExampleBundleEntry("example.degraded-target", "portable-build-test-deploy", "ACCEPTED", standardExampleArtifacts()),
        StandardExampleBundleEntry("example.safety-rejection", "kubernetes-maintenance-without-dry-run", "BLOCKED", standardExampleArtifacts()),
        StandardExampleBundleEntry("example.portable-plan", "build-and-test", "ACCEPTED", standardExampleArtifacts())
    )

    fun compatibilityPromiseRules(): List<CompatibilityPromiseRule> = listOf(
        CompatibilityPromiseRule("compat.patch-no-valid-intent-break", "patch", "Patch releases must not break previously valid reference intents.", true),
        CompatibilityPromiseRule("compat.minor-additive-capability", "minor", "Minor releases may add capabilities, vectors or optional fields when existing semantics remain stable.", true),
        CompatibilityPromiseRule("compat.public-schema-additive", "minor", "Public schema changes in minor releases should be additive unless the major-version gate is used.", true),
        CompatibilityPromiseRule("compat.breaking-requires-major", "major", "Removing artifacts, required fields or diagnostic meanings requires a major-version or explicit standard review.", true),
        CompatibilityPromiseRule("compat.vector-for-every-gate", "all", "Every release-profile gate must have a public conformance vector.", true)
    )

    fun publicContractAliasInvariants(): List<PublicContractAliasInvariant> = listOf(
        PublicContractAliasInvariant("execution-plan.task.dependencies-alias", "TaskNode.dependsOn", "TaskNode.dependencies", "0.8.0", true, "TaskNode.dependencies is a compatibility alias and must equal TaskNode.dependsOn until the public schema can remove it."),
        PublicContractAliasInvariant("execution-plan.approval.dependencies-alias", "ApprovalNode.dependsOn", "ApprovalNode.dependencies", "0.8.0", true, "ApprovalNode.dependencies is a compatibility alias and must equal ApprovalNode.dependsOn until the public schema can remove it."),
        PublicContractAliasInvariant("normalization-report.entities-alias", "NormalizationReport.entities", "NormalizationReport.extractedEntities", "0.8.0", true, "NormalizationReport.extractedEntities is a compatibility alias and must equal entities until the public schema can remove it."),
        PublicContractAliasInvariant("normalization-report.confidence-derived", "NormalizationReport.confidence", "NormalizationReport.confidenceByArea", "0.8.0", true, "NormalizationReport.confidenceByArea is a derived compatibility view and must remain synchronized with confidence.")
    )

    fun targetSemanticsMatrix(rootDir: File = File(".")): TargetSemanticsMatrixReport =
        TargetSemanticsAuthority.build(TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets")))

    fun standardExportBundle(): StandardExportBundleReport {
        val requiredDirectories = listOf("docs/", "schemas/", "conformance/", "standard/", "targets/", "examples/")
        val requiredFiles = listOf(
            "standard-version.txt",
            "conformance-manifest.json",
            "standard-index.json",
            "standard-release-profile.json",
            "conformance-suite.json",
            "public-standard-surface.json",
            "compatibility-migration-policy.json",
            "reference-intent-corpus.json",
            "target-semantics-matrix.json",
            "standard-export-bundle.json",
            "conformance-levels.json",
            "standard-export-manifest.json",
            "conformance-vector-index.json"
        )
        val requiredArtifacts = StandardModel.stableArtifacts()
        val packageName = "flow-standard-${FlowStandardVersions.FLOW_STANDARD_VERSION}"
        return StandardExportBundleReport(
            status = StandardSurfaceStatusAuthority.standardExportBundle(requiredDirectories, requiredFiles, requiredArtifacts, packageName),
            command = "standard-export --out dist/$packageName",
            requiredDirectories = requiredDirectories,
            requiredFiles = requiredFiles,
            requiredArtifacts = requiredArtifacts,
            packageName = packageName
        )
    }

    fun conformanceLevels(): ConformanceLevelsReport {
        val required = StandardModel.candidateCheckIds()
        val artifacts = StandardModel.candidateArtifacts()
        val levels = listOf(
            ConformanceLevelEntry("surface-reader", "Surface Reader", listOf("v0.4.5.standard-surface-freeze"), listOf("public-standard-surface.json", "standard-contract-index.json"), "Implementation can read the frozen public standard surface and public contract index."),
            ConformanceLevelEntry("corpus-runner", "Corpus Runner", listOf("v0.4.7.reference-intent-corpus"), listOf("reference-intent-corpus.json", "conformance-manifest.json"), "Implementation can replay reference intent scenarios and preserve accepted/blocked behavior."),
            ConformanceLevelEntry("target-semantics-reader", "Target Semantics Reader", listOf("v0.4.8.target-semantics-matrix"), listOf("target-semantics-matrix.json", "execution-readiness-report.json"), "Implementation can reason about target portability without silent semantic fallback."),
            ConformanceLevelEntry("standard-candidate", "Public Standard Candidate", required, artifacts, "Implementation satisfies the current public standard candidate surface.")
        )
        val defaultLevel = "standard-candidate"
        val order = levels.map { it.id }
        return ConformanceLevelsReport(
            status = StandardSurfaceStatusAuthority.conformanceLevels(defaultLevel, levels, order),
            defaultLevel = defaultLevel,
            levels = levels,
            levelOrder = order
        )
    }

    fun standardExportManifest(): StandardExportManifestReport {
        val export = standardExportBundle()
        val candidate = "public-standard-candidate"
        val requiredDocuments = listOf(
            "docs/ARCHITECTURE_CONSTITUTION.md",
            "docs/PUBLIC_STANDARD_SURFACE.md",
            "docs/COMPATIBILITY_POLICY.md",
            "docs/REFERENCE_INTENT_CORPUS.md",
            "docs/TARGET_SEMANTICS_MATRIX.md",
            "docs/STANDARD_EXPORT_BUNDLE.md",
            "docs/V0_7_4_ARCHITECTURE_DELTA_ANALYZER.md",
            "docs/V0_7_5_PURPOSE_COVERAGE_RATIO.md",
            "docs/IMPLEMENTER_GUIDE.md",
            "docs/STANDARD_EXPORT_MANIFEST.md"
        )
        val requiredJsonArtifacts = export.requiredFiles.filter { it.endsWith(".json") }
        val requiredSchemas = StandardModel.stableSchemas()
            .plus("schemas/standard-bundle-verification.schema.json")
            .distinct()
            .sorted()
        val implementationBoundary = listOf(
            "Read Standard Intent Model artifacts.",
            "Preserve required clarifications and safety-gate blocking.",
            "Lower only validated intent into the canonical execution plan.",
            "Use target semantics and readiness before rendering target manifests.",
            "Publish conformance evidence without requiring Kotlin internals."
        )
        val nonGoals = listOf(
            "No runtime executor.",
            "No SDK or plugin lifecycle.",
            "No target-specific public DSL.",
            "No silent semantic fallback.",
            "No model-backed AI provider inside the core standard."
        )
        val acceptanceCriteria = listOf(
            "All required public documents, schemas and standard directories are present.",
            "All stable public surface artifacts are covered by the export bundle.",
            "The standard-candidate conformance level requires every current public candidate gate.",
            "The release profile requires the public candidate acceptance gate.",
            "Architecture governance rejects SDK, plugin and runtime-executor drift.",
            "Architecture delta analysis compares the active model against the frozen previous release baseline.",
            "Purpose coverage ratio keeps public standard growth tied to executable automation intent evidence.",
            "Reference intent corpus, target semantics matrix and negative conformance remain active gates."
        )
        val releaseGateChecks = StandardModel.standardExportManifestCheckIds()
        val evidenceArtifacts = listOf(
            "conformance-manifest.json",
            "standard-release-profile.json",
            "public-standard-surface.json",
            "standard-export-bundle.json",
            "conformance-levels.json",
            "standard-export-manifest.json",
            "conformance-vector-index.json"
        )
        val selfVerificationCommands = listOf(
            "./gradlew clean test",
            "./gradlew run --args=\"conformance\"",
            "./gradlew run --args=\"standard-export --out dist/flow-standard-${FlowStandardVersions.FLOW_STANDARD_VERSION}\"",
            "./gradlew run --args=\"standard-verify --bundle dist/flow-standard-${FlowStandardVersions.FLOW_STANDARD_VERSION}\""
        )
        val bundleVerificationChecks = listOf(
            "Every required document in standard-export-manifest.json exists in the exported bundle.",
            "Every stable public surface artifact is included by standard-export-bundle.json.",
            "Every stable public surface schema exists and is referenced by the export manifest.",
            "Every required release-profile check is present and passing in the conformance manifest.",
            "Every evidence artifact declared by the export manifest is emitted by standard-draft or standard-export.",
            "The exported standard-version.txt equals the active Flow standard version.",
            "The standard-verify command emits a PASS standard-bundle-verification report.",
            "The conformance-vector-index.json artifact reconciles public vector files with runner and release-profile checks."
        )
        val verificationInputs = listOf(
            "standard-export-manifest.json",
            "standard-export-bundle.json",
            "public-standard-surface.json",
            "standard-release-profile.json",
            "conformance-manifest.json",
            "conformance-levels.json",
            "conformance-vector-index.json"
        )
        return StandardExportManifestReport(
            status = StandardSurfaceStatusAuthority.standardExportManifest(
                candidate,
                requiredDocuments,
                requiredJsonArtifacts,
                requiredSchemas,
                export.requiredDirectories,
                releaseGateChecks,
                evidenceArtifacts,
                selfVerificationCommands,
                verificationInputs
            ),
            candidate = candidate,
            requiredDocuments = requiredDocuments,
            requiredJsonArtifacts = requiredJsonArtifacts,
            requiredSchemas = requiredSchemas,
            requiredDirectories = export.requiredDirectories,
            implementationBoundary = implementationBoundary,
            nonGoals = nonGoals,
            acceptanceCriteria = acceptanceCriteria,
            releaseGateChecks = releaseGateChecks,
            evidenceArtifacts = evidenceArtifacts,
            selfVerificationCommands = selfVerificationCommands,
            bundleVerificationChecks = bundleVerificationChecks,
            verificationInputs = verificationInputs
        )
    }

    private fun accepted(
        id: String,
        title: String,
        inputText: String,
        capabilities: List<String>,
        entities: Map<String, String> = emptyMap()
    ): ReferenceIntentScenario = ReferenceIntentScenario(
        id,
        title,
        inputText,
        "ACCEPTED",
        capabilities,
        emptyList(),
        emptyList(),
        entities,
        "Free-text intent that the standard normalizes, validates and accepts for lowering."
    )

    private fun blockedByClarification(
        id: String,
        title: String,
        inputText: String,
        capabilities: List<String>,
        clarification: String
    ): ReferenceIntentScenario = ReferenceIntentScenario(
        id,
        title,
        inputText,
        "BLOCKED",
        capabilities,
        listOf(clarification),
        emptyList(),
        emptyMap(),
        "Intent that the standard blocks until a required clarification is answered."
    )

    private fun blockedByGate(
        id: String,
        title: String,
        inputText: String,
        capabilities: List<String>,
        rejectionCode: String
    ): ReferenceIntentScenario = ReferenceIntentScenario(
        id,
        title,
        inputText,
        "BLOCKED",
        capabilities,
        emptyList(),
        listOf(rejectionCode),
        emptyMap(),
        "Intent that the proposal-review gate rejects on a mandated safety obligation."
    )

    private fun standardExampleArtifacts(): List<String> = listOf(
        "normalized-intent.json",
        "intent-decision-report.json",
        "execution-plan.json",
        "execution-readiness-report.json",
        "target-decision-trace-report.json",
        "conformance-vector-index.json"
    )
}
