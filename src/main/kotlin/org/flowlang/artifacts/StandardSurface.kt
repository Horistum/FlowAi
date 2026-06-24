package org.flowlang.artifacts

import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.StandardModel

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
    val jenkins: String,
    val githubActions: String,
    val tekton: String,
    val requiredDiagnosticWhenUnsupported: String
)

data class TargetSemanticsMatrixReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val matrixVersion: String = "1.0",
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
        return PublicStandardSurfaceReport(
            status = "PASS",
            entries = entries,
            stableArtifacts = entries.filter { it.stability == "stable" }.map { it.artifact },
            draftArtifacts = entries.filter { it.stability == "draft" }.map { it.artifact },
            experimentalArtifacts = entries.filter { it.stability == "experimental" }.map { it.artifact },
            internalArtifacts = emptyList(),
            requiredChangeGates = listOf(
                "public-surface-review",
                "schema-compatibility-review",
                "migration-note-review",
                "negative-conformance-review"
            )
        )
    }

    fun compatibilityMigrationPolicy(): CompatibilityMigrationPolicyReport = CompatibilityMigrationPolicyReport(
        status = "PASS",
        compatibilityRules = listOf(
            CompatibilityPolicyRule(
                id = "minor.add-optional-field",
                category = "minor",
                allowedInMinor = true,
                requiresMigrationNote = false,
                requiresDeprecationWindow = false,
                description = "A minor release may add optional fields to public JSON artifacts."
            ),
            CompatibilityPolicyRule(
                id = "minor.add-conformance-vector",
                category = "minor",
                allowedInMinor = true,
                requiresMigrationNote = false,
                requiresDeprecationWindow = false,
                description = "A minor release may add stricter conformance vectors when the behavior was already required by the standard."
            ),
            CompatibilityPolicyRule(
                id = "breaking.remove-public-field",
                category = "breaking",
                allowedInMinor = false,
                requiresMigrationNote = true,
                requiresDeprecationWindow = true,
                description = "Removing or renaming a public field is a breaking change."
            ),
            CompatibilityPolicyRule(
                id = "breaking-change-semantics",
                category = "breaking",
                allowedInMinor = false,
                requiresMigrationNote = true,
                requiresDeprecationWindow = true,
                description = "Changing the meaning of an existing capability, diagnostic or safety invariant is a breaking change."
            )
        ),
        deprecationWindowMinorReleases = 2,
        migrationArtifacts = listOf(
            "CHANGELOG.md",
            "docs/COMPATIBILITY_POLICY.md",
            "standard-release-profile.json",
            "conformance-manifest.json"
        ),
        breakingChangeGate = "major-version-or-explicit-standard-review"
    )

    fun referenceIntentCorpus(): ReferenceIntentCorpusReport {
        val scenarios = listOf(
            accepted("build-and-test", "Build and test", "Check out the repository, build the project and run the tests.", listOf("CHECKOUT", "BUILD", "TEST")),
            accepted("deploy-with-approval-and-rollback", "Deployment with approval, verification and rollback", "Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure.", listOf("DEPLOY", "APPROVE", "VERIFY", "ROLLBACK"), mapOf("application" to "billing-api")),
            accepted("cleanup-with-retention", "Cleanup with retention", "Clean up resources older than 30 days.", listOf("CLEANUP")),
            accepted("kubernetes-maintenance-dry-run", "Kubernetes maintenance with dry-run", "Perform kubernetes maintenance in namespace payments as a dry run.", listOf("KUBERNETES_MAINTENANCE"), mapOf("scope" to "payments")),
            accepted("database-migration-with-backup", "Database migration with backup and rollback", "Create a backup of database orders, run migration v42 on database orders, verify and rollback on failure.", listOf("BACKUP", "DATABASE_MIGRATE", "ROLLBACK"), mapOf("database" to "orders")),
            accepted("certificate-renewal-with-window", "Certificate renewal with maintenance window", "Renew certificate api-tls for api.example.com during the Sunday maintenance window and notify owners.", listOf("CERTIFICATE_RENEW", "NOTIFY"), mapOf("certificate" to "api-tls", "window" to "sunday maintenance")),
            accepted("secret-rotation-with-audit", "Secret rotation with audit notification", "Rotate secret api-token, notify the platform channel and verify rollout.", listOf("SECRET_ROTATE", "NOTIFY", "VERIFY"), mapOf("secret" to "api-token")),
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
        return ReferenceIntentCorpusReport(
            status = "PASS",
            scenarios = scenarios,
            requiredScenarioIds = scenarios.map { it.id },
            negativeScenarioIds = scenarios.filter { it.expectedStatus == "BLOCKED" }.map { it.id }
        )
    }


    fun referenceCorpusExecutionHarness(): ReferenceCorpusExecutionHarnessReport {
        val corpus = referenceIntentCorpus()
        return ReferenceCorpusExecutionHarnessReport(
            status = "PASS",
            replayStages = listOf(
                "normalize scenario input with ScenarioPackIntentNormalizer",
                "compare selected scenario output against reference expected capabilities, entities, clarifications and rejection codes",
                "review normalized intent through IntentProposalReview",
                "lower accepted scenarios through intent validation, AST validation and execution-plan planning",
                "prove blocked scenarios are not lowerable"
            ),
            assertions = listOf(
                ReferenceCorpusHarnessAssertion("corpus.actual-normalizer", "all", true, "The reference corpus must be replayed through the real deterministic normalizer, not compared against static metadata."),
                ReferenceCorpusHarnessAssertion("corpus.accepted-lowers", "accepted", true, "Every accepted reference scenario must pass normalization, proposal review, intent validation, AST validation and execution planning."),
                ReferenceCorpusHarnessAssertion("corpus.blocked-stays-blocked", "blocked", true, "Every blocked reference scenario must remain blocked by a required clarification or safety diagnostic."),
                ReferenceCorpusHarnessAssertion("corpus.entities-observed", "all", true, "Expected entities must be observed in the normalization report with canonical values."),
                ReferenceCorpusHarnessAssertion("corpus.no-custom-fallback", "reference", true, "Reference scenarios must select a standard scenario pack rather than the custom fallback."),
                ReferenceCorpusHarnessAssertion("corpus.no-auto-approval", "safety", true, "Production risk may create an approval obligation, but it must not synthesize an APPROVE step or APPROVAL policy without explicit user intent."),
                ReferenceCorpusHarnessAssertion("corpus.rollback-review-lowerable", "rollback", true, "Rollback review intents must remain lowerable; missing application context is a review question, not a blocking clarification."),
                ReferenceCorpusHarnessAssertion("corpus.semantic-capabilities", "capabilities", true, "Capability semantics must stay precise, for example rollout verification uses VERIFY rather than generic VALIDATE."),
                ReferenceCorpusHarnessAssertion("corpus.negative-reason", "blocked", true, "Every blocked reference scenario must declare an expected clarification field or expected rejection code."),
                ReferenceCorpusHarnessAssertion("corpus.plan-not-empty", "accepted", true, "Accepted reference scenarios must produce a non-empty execution plan." )
            ),
            requiredScenarioIds = corpus.requiredScenarioIds,
            negativeScenarioIds = corpus.negativeScenarioIds,
            mustLowerAccepted = true,
            mustBlockNegative = true,
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
        PublicContractAliasInvariant(
            id = "execution-plan.task.dependencies-alias",
            canonicalField = "TaskNode.dependsOn",
            aliasField = "TaskNode.dependencies",
            removalTarget = "0.8.0",
            blocking = true,
            description = "TaskNode.dependencies is a compatibility alias and must equal TaskNode.dependsOn until the public schema can remove it."
        ),
        PublicContractAliasInvariant(
            id = "execution-plan.approval.dependencies-alias",
            canonicalField = "ApprovalNode.dependsOn",
            aliasField = "ApprovalNode.dependencies",
            removalTarget = "0.8.0",
            blocking = true,
            description = "ApprovalNode.dependencies is a compatibility alias and must equal ApprovalNode.dependsOn until the public schema can remove it."
        ),
        PublicContractAliasInvariant(
            id = "normalization-report.entities-alias",
            canonicalField = "NormalizationReport.entities",
            aliasField = "NormalizationReport.extractedEntities",
            removalTarget = "0.8.0",
            blocking = true,
            description = "NormalizationReport.extractedEntities is a compatibility alias and must equal entities until the public schema can remove it."
        ),
        PublicContractAliasInvariant(
            id = "normalization-report.confidence-derived",
            canonicalField = "NormalizationReport.confidence",
            aliasField = "NormalizationReport.confidenceByArea",
            removalTarget = "0.8.0",
            blocking = true,
            description = "NormalizationReport.confidenceByArea is a derived compatibility view and must remain synchronized with confidence."
        )
    )

    fun targetSemanticsMatrix(): TargetSemanticsMatrixReport = TargetSemanticsMatrixReport(
        status = "PASS",
        targetIds = listOf("jenkins", "github-actions", "tekton"),
        entries = listOf(
            semantics("conditions", "native", "partial", "partial", "condition.expression"),
            semantics("approvals", "native", "environment-gate", "partial", "approval.strict"),
            semantics("secrets", "native-binding", "native-binding", "native-binding", "secret.binding"),
            semantics("artifacts", "archive", "upload-artifact", "workspace-result", "artifact.transport"),
            semantics("parallelism", "parallel-stage", "matrix-or-jobs", "dag-tasks", "parallelism.model"),
            semantics("rollback", "explicit-step", "explicit-job", "explicit-task", "rollback.capability"),
            semantics("manual-gates", "input-step", "environment-review", "external-required", "manual.gate"),
            semantics("environment-gates", "stage-env", "environment", "namespace-or-param", "environment.gate"),
            semantics("matrix-builds", "native", "native", "expanded-dag", "matrix.expression"),
            semantics("dynamic-expressions", "groovy-expression", "workflow-expression", "limited-when-expression", "condition.expression"),
            semantics("strict-manual-approval", "native", "environment-gate-not-equivalent", "unsupported-blocked", "approval.strict"),
            semantics("unsupported-condition-fallback", "diagnostic-required", "diagnostic-required", "diagnostic-required", "condition.expression"),
            semantics("rollback-portability", "explicit-step", "explicit-job", "explicit-task-or-blocked", "rollback.capability")
        ),
        portabilityRule = "Unsupported or partial target semantics must produce diagnostics or blocked readiness before rendering."
    )

    fun standardExportBundle(): StandardExportBundleReport = StandardExportBundleReport(
        status = "PASS",
        command = "standard-export --out dist/flow-standard-${FlowStandardVersions.FLOW_STANDARD_VERSION}",
        requiredDirectories = listOf("docs/", "schemas/", "conformance/", "standard/", "targets/", "examples/"),
        requiredFiles = listOf(
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
        ),
        requiredArtifacts = StandardModel.stableArtifacts(),
        packageName = "flow-standard-${FlowStandardVersions.FLOW_STANDARD_VERSION}"
    )

    fun conformanceLevels(): ConformanceLevelsReport {
        val required = StandardModel.candidateCheckIds()
        val artifacts = StandardModel.candidateArtifacts()
        val levels = listOf(
            ConformanceLevelEntry(
                id = "surface-reader",
                title = "Surface Reader",
                requiredChecks = listOf("v0.4.5.standard-surface-freeze"),
                requiredArtifacts = listOf("public-standard-surface.json", "standard-contract-index.json"),
                description = "Implementation can read the frozen public standard surface and public contract index."
            ),
            ConformanceLevelEntry(
                id = "corpus-runner",
                title = "Corpus Runner",
                requiredChecks = listOf("v0.4.7.reference-intent-corpus"),
                requiredArtifacts = listOf("reference-intent-corpus.json", "conformance-manifest.json"),
                description = "Implementation can replay reference intent scenarios and preserve accepted/blocked behavior."
            ),
            ConformanceLevelEntry(
                id = "target-semantics-reader",
                title = "Target Semantics Reader",
                requiredChecks = listOf("v0.4.8.target-semantics-matrix"),
                requiredArtifacts = listOf("target-semantics-matrix.json", "execution-readiness-report.json"),
                description = "Implementation can reason about target portability without silent semantic fallback."
            ),
            ConformanceLevelEntry(
                id = "standard-candidate",
                title = "Public Standard Candidate",
                requiredChecks = required,
                requiredArtifacts = artifacts,
                description = "Implementation satisfies the current public standard candidate surface."
            )
        )
        return ConformanceLevelsReport(
            status = "PASS",
            defaultLevel = "standard-candidate",
            levels = levels,
            levelOrder = levels.map { it.id }
        )
    }

    fun standardExportManifest(): StandardExportManifestReport = StandardExportManifestReport(
        status = "PASS",
        candidate = "public-standard-candidate",
        requiredDocuments = listOf(
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
        ),
        requiredJsonArtifacts = standardExportBundle().requiredFiles.filter { it.endsWith(".json") },
        requiredSchemas = StandardModel.stableSchemas()
            .plus("schemas/standard-bundle-verification.schema.json")
            .distinct()
            .sorted(),
        requiredDirectories = standardExportBundle().requiredDirectories,
        implementationBoundary = listOf(
            "Read Standard Intent Model artifacts.",
            "Preserve required clarifications and safety-gate blocking.",
            "Lower only validated intent into the canonical execution plan.",
            "Use target semantics and readiness before rendering target manifests.",
            "Publish conformance evidence without requiring Kotlin internals."
        ),
        nonGoals = listOf(
            "No runtime executor.",
            "No SDK or plugin lifecycle.",
            "No target-specific public DSL.",
            "No silent semantic fallback.",
            "No model-backed AI provider inside the core standard."
        ),
        acceptanceCriteria = listOf(
            "All required public documents, schemas and standard directories are present.",
            "All stable public surface artifacts are covered by the export bundle.",
            "The standard-candidate conformance level requires every current public candidate gate.",
            "The release profile requires the public candidate acceptance gate.",
            "Architecture governance rejects SDK, plugin and runtime-executor drift.",
            "Architecture delta analysis compares the active model against the frozen previous release baseline.",
            "Purpose coverage ratio keeps public standard growth tied to executable automation intent evidence.",
            "Reference intent corpus, target semantics matrix and negative conformance remain active gates."
        ),
        releaseGateChecks = StandardModel.standardExportManifestCheckIds(),
        evidenceArtifacts = listOf(
            "conformance-manifest.json",
            "standard-release-profile.json",
            "public-standard-surface.json",
            "standard-export-bundle.json",
            "conformance-levels.json",
            "standard-export-manifest.json",
            "conformance-vector-index.json"
        ),
        selfVerificationCommands = listOf(
            "./gradlew clean test",
            "./gradlew run --args=\"conformance\"",
            "./gradlew run --args=\"standard-export --out dist/flow-standard-${FlowStandardVersions.FLOW_STANDARD_VERSION}\"",
            "./gradlew run --args=\"standard-verify --bundle dist/flow-standard-${FlowStandardVersions.FLOW_STANDARD_VERSION}\""
        ),
        bundleVerificationChecks = listOf(
            "Every required document in standard-export-manifest.json exists in the exported bundle.",
            "Every stable public surface artifact is included by standard-export-bundle.json.",
            "Every stable public surface schema exists and is referenced by the export manifest.",
            "Every required release-profile check is present in the conformance manifest.",
            "Every evidence artifact declared by the export manifest is emitted by standard-draft or standard-export.",
            "The exported standard-version.txt equals the active Flow standard version.",
            "The standard-verify command emits a PASS standard-bundle-verification report.",
            "The conformance-vector-index.json artifact reconciles public vector files with runner and release-profile checks."
        ),
        verificationInputs = listOf(
            "standard-export-manifest.json",
            "standard-export-bundle.json",
            "public-standard-surface.json",
            "standard-release-profile.json",
            "conformance-manifest.json",
            "conformance-levels.json",
            "conformance-vector-index.json"
        )
    )


    private fun accepted(id: String, title: String, inputText: String, capabilities: List<String>, entities: Map<String, String> = emptyMap()): ReferenceIntentScenario =
        ReferenceIntentScenario(id, title, inputText, "ACCEPTED", capabilities, emptyList(), emptyList(), entities,
            "Free-text intent that the standard normalizes, validates and accepts for lowering.")

    private fun blockedByClarification(id: String, title: String, inputText: String, capabilities: List<String>, clarification: String): ReferenceIntentScenario =
        ReferenceIntentScenario(id, title, inputText, "BLOCKED", capabilities, listOf(clarification), emptyList(), emptyMap(),
            "Intent that the standard blocks until a required clarification is answered.")

    private fun blockedByGate(id: String, title: String, inputText: String, capabilities: List<String>, rejectionCode: String): ReferenceIntentScenario =
        ReferenceIntentScenario(id, title, inputText, "BLOCKED", capabilities, emptyList(), listOf(rejectionCode), emptyMap(),
            "Intent that the proposal-review gate rejects on a mandated safety obligation.")

    private fun semantics(
        feature: String,
        jenkins: String,
        githubActions: String,
        tekton: String,
        diagnostic: String
    ): TargetSemanticsEntry = TargetSemanticsEntry(feature, jenkins, githubActions, tekton, diagnostic)

    private fun standardExampleArtifacts(): List<String> = listOf(
        "normalized-intent.json",
        "intent-decision-report.json",
        "execution-plan.json",
        "execution-readiness-report.json",
        "target-decision-trace-report.json",
        "conformance-vector-index.json"
    )
}
