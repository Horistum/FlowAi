import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.control.AdapterControlClaimStatus
import org.flowlang.adapters.control.AdapterControlDecision
import org.flowlang.adapters.control.AdapterControlEvidenceStatus
import org.flowlang.adapters.control.AdapterControlFamily
import org.flowlang.adapters.control.AdapterControlMaterializationAuthority
import org.flowlang.adapters.control.AdapterControlMaterializationDocument
import org.flowlang.adapters.control.AdapterControlMaterializationLoader
import org.flowlang.adapters.control.AdapterControlRequirementCompleteness
import org.flowlang.adapters.control.AdapterControlScope
import org.flowlang.adapters.control.UnresolvedAdapterControlMaterializationException
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.portfolio.AdapterSupportClass
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.lowering.IntentFailureMetadata
import org.flowlang.lowering.IntentPolicyMetadata
import org.flowlang.lowering.IntentSourceMetadata
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanSchedule
import org.flowlang.planner.PlanTrigger
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterControlMaterializationAuthorityTests {
    private val rootDir = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets"))
    private val authority = AdapterControlMaterializationAuthority(rootDir, targets, BuiltInTargetProjections.registry)

    @Test
    fun builtInControlManifestIsCompleteAndHonest() {
        val report = authority.analyze()

        assertEquals("PASS", report.status, report.findings.joinToString())
        assertEquals(targets.size, report.targetCount)
        assertEquals(targets.size * AdapterControlFamily.entries.size, report.claimCount)
    }

    @Test
    fun loaderRejectsUnsupportedManifestVersion() {
        val root = Files.createTempDirectory("flow-a04-version").toFile()
        try {
            val destination = File(root, AdapterControlMaterializationLoader.PATH)
            destination.parentFile.mkdirs()
            destination.writeText(
                File(rootDir, AdapterControlMaterializationLoader.PATH).readText()
                    .replaceFirst("version: \"1.0\"", "version: \"2.0\"")
            )

            val failure = assertFailsWith<IllegalArgumentException> {
                AdapterControlMaterializationLoader.load(root)
            }
            assertTrue(failure.message.orEmpty().contains("unsupported"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun profileOnlyTargetsRemainUnknownForEveryControlFamily() {
        val profileOnly = AdapterPortfolioLoader.load(rootDir).records
            .filter { it.supportClass == AdapterSupportClass.PROFILE_ONLY }
            .map { it.target }
            .toSet()
        val document = AdapterControlMaterializationLoader.load(rootDir)

        document.targets.filter { it.target in profileOnly }.forEach { record ->
            assertEquals(AdapterControlFamily.entries.toSet(), record.claims.map { it.family }.toSet())
            assertTrue(record.claims.all { it.status == AdapterControlClaimStatus.UNKNOWN })
            assertTrue(record.claims.all { it.semantics.supported.isEmpty() })
        }
    }

    @Test
    fun runtimeCompositionCanUseAnExactSubsetOfDistributionTargets() {
        val subset = mapOf("jenkins" to targets.getValue("jenkins"))
        val subsetAuthority = AdapterControlMaterializationAuthority(
            rootDir = rootDir,
            targets = subset,
            projections = BuiltInTargetProjections.registry
        )

        assertEquals(
            AdapterControlDecision.MATCHED,
            subsetAuthority.assess(ExecutionPlan(flowName = "subset"), "jenkins").decision
        )
    }

    @Test
    fun jenkinsProviderBackedManualApprovalMatchesAtStepScope() {
        val assessment = authority.assess(
            ExecutionPlan(flowName = "approval", nodes = listOf(ApprovalNode(id = "approve-production"))),
            "jenkins"
        )

        assertEquals(AdapterControlDecision.MATCHED, assessment.decision)
        assertEquals(listOf("approval.manual.inline"), assessment.requirements.map { it.semantic })
        assertEquals(listOf(AdapterControlScope.STEP), assessment.requirements.map { it.scope })
        assertTrue(assessment.evidence.all { it.status == AdapterControlEvidenceStatus.SATISFIED })
    }

    @Test
    fun approvalModeIsNotReinterpretedAsManualApproval() {
        val environment = authority.assess(
            ExecutionPlan(
                flowName = "environment-approval",
                nodes = listOf(ApprovalNode(id = "approve-production", mode = "environment"))
            ),
            "jenkins"
        )
        assertEquals(AdapterControlDecision.BLOCKED, environment.decision)
        assertEquals(listOf("approval.environment.resource"), environment.requirements.map { it.semantic })
        assertEquals(listOf(AdapterControlScope.ENVIRONMENT), environment.requirements.map { it.scope })
        assertEquals(listOf(AdapterControlEvidenceStatus.UNSUPPORTED), environment.evidence.map { it.status })

        val custom = authority.assess(
            ExecutionPlan(
                flowName = "custom-approval",
                nodes = listOf(ApprovalNode(id = "approve-production", mode = "four-eyes"))
            ),
            "jenkins"
        )
        assertEquals(AdapterControlDecision.BLOCKED, custom.decision)
        assertEquals(listOf("approval.mode.four-eyes"), custom.requirements.map { it.semantic })
        assertEquals(listOf(AdapterControlScope.UNSPECIFIED), custom.requirements.map { it.scope })
        assertEquals(listOf(AdapterControlEvidenceStatus.UNKNOWN), custom.evidence.map { it.status })
    }

    @Test
    fun detachedErrorHandlerWithoutProtectedBodyIsBlocked() {
        val assessment = authority.assess(
            ExecutionPlan(
                flowName = "detached-handler",
                nodes = listOf(
                    TryPlanNode(
                        id = "detached",
                        body = emptyList(),
                        errorHandler = listOf(ApprovalNode(id = "handler", message = "handle"))
                    )
                )
            ),
            "jenkins"
        )

        assertEquals(AdapterControlDecision.BLOCKED, assessment.decision)
        assertTrue(assessment.requirements.any { it.semantic == "compensation.detached-error-handler" })
        assertTrue(assessment.evidence.any { it.status == AdapterControlEvidenceStatus.UNKNOWN })
    }

    @Test
    fun jenkinsRetryIsBlockedInsteadOfFlattenedIntoSuccess() {
        val plan = ExecutionPlan(
            flowName = "retry",
            nodes = listOf(RetryGroupNode(
                id = "retry-deploy",
                max = 3,
                delay = "10s",
                backoff = "fixed",
                body = listOf(TaskNode(id = "deploy", module = "standard", action = "execute", target = "standard"))
            ))
        )

        val assessment = authority.assess(plan, "jenkins")
        assertEquals(AdapterControlDecision.BLOCKED, assessment.decision)
        assertEquals(
            setOf("retry.attempt-limit", "retry.delay.fixed"),
            assessment.requirements.map { it.semantic }.toSet()
        )
        assertTrue(assessment.requirements.all { it.scope == AdapterControlScope.TASK })
        assertTrue(assessment.evidence.all { it.status == AdapterControlEvidenceStatus.UNSUPPORTED })
        assertFailsWith<UnresolvedAdapterControlMaterializationException> {
            authority.requireMatched(plan, "jenkins")
        }
    }

    @Test
    fun supportedSemanticAtWrongScopeIsBlocked() {
        val document = AdapterControlMaterializationLoader.load(rootDir)
        val malformed = document.mapClaim("jenkins", AdapterControlFamily.APPROVAL) { claim ->
            claim.copy(scopes = setOf(AdapterControlScope.WORKFLOW))
        }
        val scopedAuthority = authorityFor(malformed)

        assertEquals("PASS", scopedAuthority.analyze().status)
        val assessment = scopedAuthority.assess(
            ExecutionPlan(flowName = "approval", nodes = listOf(ApprovalNode(id = "approve"))),
            "jenkins"
        )
        assertEquals(AdapterControlDecision.BLOCKED, assessment.decision)
        assertEquals(listOf(AdapterControlEvidenceStatus.UNSUPPORTED), assessment.evidence.map { it.status })
        assertTrue(assessment.evidence.single().detail.contains("not required scope STEP"))
    }

    @Test
    fun githubCronIsMatchedButAuthoredTimezoneIsBlocked() {
        val cronOnly = ExecutionPlan(
            flowName = "cron",
            triggers = listOf(PlanTrigger(
                id = "nightly",
                type = "SCHEDULE",
                schedule = PlanSchedule(kind = "CRON", expression = "0 2 * * *")
            ))
        )
        assertEquals(AdapterControlDecision.MATCHED, authority.assess(cronOnly, "github-actions").decision)

        val withTimezone = cronOnly.copy(
            triggers = listOf(cronOnly.triggers.single().copy(
                schedule = cronOnly.triggers.single().schedule?.copy(timezone = "Europe/Prague")
            ))
        )
        val assessment = authority.assess(withTimezone, "github-actions")
        assertEquals(AdapterControlDecision.BLOCKED, assessment.decision)
        assertTrue(assessment.requirements.any { it.semantic == "scheduling.timezone" })
        assertTrue(assessment.requirements.all { it.scope == AdapterControlScope.TRIGGER })
    }

    @Test
    fun preservedTimeoutPolicyCannotDisappearOrBecomeFalselySpecific() {
        val plan = ExecutionPlan(
            flowName = "timeout-policy",
            sourceIntent = IntentSourceMetadata(
                policies = listOf(IntentPolicyMetadata("deployment timeout", "TIMEOUT")),
                failure = IntentFailureMetadata()
            )
        )

        val assessment = authority.assess(plan, "jenkins")
        assertEquals(AdapterControlDecision.BLOCKED, assessment.decision)
        assertEquals(listOf("timeout.unspecified"), assessment.requirements.map { it.semantic })
        assertEquals(listOf(AdapterControlScope.UNSPECIFIED), assessment.requirements.map { it.scope })
        assertEquals(
            listOf(AdapterControlRequirementCompleteness.PRESERVED_UNSPECIFIED),
            assessment.requirements.map { it.completeness }
        )
        assertEquals(listOf(AdapterControlEvidenceStatus.UNKNOWN), assessment.evidence.map { it.status })
    }

    @Test
    fun diagnosticReconciliationCannotRetainExecutableCompatibility() {
        val plan = ExecutionPlan(
            flowName = "retry",
            nodes = listOf(RetryGroupNode(
                id = "retry",
                max = 3,
                delay = "10s",
                backoff = "fixed",
                body = emptyList()
            ))
        )
        val assessment = authority.assess(plan, "jenkins")
        val manifest = manifest("jenkins", "retry")

        val reconciled = authority.reconcileDiagnostic(manifest, assessment)
        assertEquals(SupportLevel.UNSUPPORTED, reconciled.compatibility.status)
        assertFalse(reconciled.compatibility.executable)
        assertTrue(reconciled.compatibility.issues.any { it.feature.contains("control.retry") })
        assertEquals(AdapterControlDecision.BLOCKED.name, reconciled.metadata["adapterControlDecision"])
        assertEquals(AdapterControlMaterializationLoader.SUPPORTED_VERSION, reconciled.metadata["adapterControlEvidenceVersion"])
    }

    @Test
    fun diagnosticReconciliationAlwaysRetainsMatchedControlEvidence() {
        val assessment = authority.assess(ExecutionPlan(flowName = "no-controls"), "jenkins")
        assertEquals(AdapterControlDecision.MATCHED, assessment.decision)

        val reconciled = authority.reconcileDiagnostic(manifest("jenkins", "no-controls"), assessment)
        assertEquals(AdapterControlDecision.MATCHED.name, reconciled.metadata["adapterControlDecision"])
        assertEquals("0", reconciled.metadata["adapterControlRequirementCount"])
        assertEquals("0", reconciled.metadata["adapterControlBlockerCount"])
        assertEquals(AdapterControlMaterializationLoader.SUPPORTED_VERSION, reconciled.metadata["adapterControlEvidenceVersion"])
    }

    @Test
    fun diagnosticReconciliationRejectsTargetMismatch() {
        val assessment = authority.assess(ExecutionPlan(flowName = "no-controls"), "jenkins")

        assertFailsWith<IllegalArgumentException> {
            authority.reconcileDiagnostic(manifest("github-actions", "no-controls"), assessment)
        }
    }

    @Test
    fun runtimeRefusesInvalidEvidenceDocument() {
        val document = AdapterControlMaterializationLoader.load(rootDir)
        val duplicateApproval = document.copy(
            targets = document.targets.map { record ->
                if (record.target != "jenkins") record else record.copy(
                    claims = record.claims + record.claims.single { it.family == AdapterControlFamily.APPROVAL }
                )
            }
        )
        val invalidAuthority = authorityFor(duplicateApproval)

        assertTrue(invalidAuthority.analyze().findings.any { it.code == "CONTROL_FAMILY_DUPLICATE" })
        val failure = assertFailsWith<IllegalStateException> {
            invalidAuthority.assess(ExecutionPlan(flowName = "approval"), "jenkins")
        }
        assertTrue(failure.message.orEmpty().contains("CONTROL_FAMILY_DUPLICATE"))
    }

    @Test
    fun runtimeRefusesUnsupportedEvidenceVersion() {
        val invalidAuthority = authorityFor(
            AdapterControlMaterializationLoader.load(rootDir).copy(version = "2.0")
        )

        assertTrue(invalidAuthority.analyze().findings.any { it.code == "CONTROL_MANIFEST_VERSION_UNSUPPORTED" })
        val failure = assertFailsWith<IllegalStateException> {
            invalidAuthority.assess(ExecutionPlan(flowName = "empty"), "jenkins")
        }
        assertTrue(failure.message.orEmpty().contains("CONTROL_MANIFEST_VERSION_UNSUPPORTED"))
    }

    @Test
    fun conflictingRequirementIdentityFailsInsteadOfDroppingOneRequirement() {
        val plan = ExecutionPlan(
            flowName = "duplicate-retry-ids",
            nodes = listOf(
                RetryGroupNode(id = "retry", max = 2, delay = "0s", backoff = "fixed"),
                RetryGroupNode(id = "retry", max = 5, delay = "0s", backoff = "fixed")
            )
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            authority.requirementsFor(plan)
        }
        assertTrue(failure.message.orEmpty().contains("identity collision"))
    }

    @Test
    fun supportedSemanticRequiresIndependentImplementationEvidence() {
        val malformed = AdapterControlMaterializationLoader.load(rootDir)
            .mapClaim("jenkins", AdapterControlFamily.APPROVAL) { claim ->
                claim.copy(
                    evidenceReferences = listOf(
                        "targets/builtin-targets.yaml#targets.jenkins.features.approvals"
                    )
                )
            }

        val report = authority.analyze(malformed)
        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "CONTROL_EVIDENCE_REGISTRY_ONLY" })
        assertTrue(report.findings.any { it.code == "CONTROL_SUPPORTED_IMPLEMENTATION_EVIDENCE_MISSING" })
    }

    @Test
    fun supportedSemanticRequiresIndependentBehaviorEvidence() {
        val malformed = AdapterControlMaterializationLoader.load(rootDir)
            .mapClaim("jenkins", AdapterControlFamily.SCHEDULING) { claim ->
                claim.copy(evidenceReferences = claim.evidenceReferences.filterNot { it.startsWith("src/test/") })
            }

        val report = authority.analyze(malformed)
        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "CONTROL_SUPPORTED_BEHAVIOR_EVIDENCE_MISSING" })
        assertFalse(report.findings.any { it.code == "CONTROL_SUPPORTED_IMPLEMENTATION_EVIDENCE_MISSING" })
    }

    @Test
    fun targetRegistryCannotBeTheOnlyNegativeEvidence() {
        val malformed = AdapterControlMaterializationLoader.load(rootDir)
            .mapClaim("github-actions", AdapterControlFamily.COMPENSATION) { claim ->
                claim.copy(
                    evidenceReferences = listOf(
                        "targets/builtin-targets.yaml#targets.github-actions.projectionRules.standard.rollback"
                    )
                )
            }

        val report = authority.analyze(malformed)
        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "CONTROL_EVIDENCE_REGISTRY_ONLY" })
    }

    @Test
    fun incompleteSemanticPartitionFailsClosed() {
        val document = AdapterControlMaterializationLoader.load(rootDir)
        val approval = document.targets.single { it.target == "jenkins" }
            .claims.single { it.family == AdapterControlFamily.APPROVAL }
        val malformed = document.mapClaim("jenkins", AdapterControlFamily.APPROVAL) { claim ->
            claim.copy(
                semantics = claim.semantics.copy(
                    unsupported = claim.semantics.unsupported - "approval.external"
                )
            )
        }

        val report = authority.analyze(malformed)
        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "CONTROL_SEMANTIC_PARTITION_MISMATCH" })
        assertTrue(approval.semantics.all.contains("approval.external"))
    }

    private fun authorityFor(document: AdapterControlMaterializationDocument) =
        AdapterControlMaterializationAuthority(
            rootDir = rootDir,
            targets = targets,
            projections = BuiltInTargetProjections.registry,
            documentOverride = document
        )

    private fun manifest(target: String, flowName: String) = TargetManifest(
        target = target,
        flowName = flowName,
        compatibility = CompatibilityReport(
            target = target,
            status = SupportLevel.SUPPORTED,
            executable = true,
            readinessEvidenceAvailable = true
        )
    )

    private fun AdapterControlMaterializationDocument.mapClaim(
        target: String,
        family: AdapterControlFamily,
        transform: (org.flowlang.adapters.control.AdapterControlClaim) -> org.flowlang.adapters.control.AdapterControlClaim
    ): AdapterControlMaterializationDocument = copy(
        targets = targets.map { record ->
            if (record.target != target) record else record.copy(
                claims = record.claims.map { claim ->
                    if (claim.family != family) claim else transform(claim)
                }
            )
        }
    )
}
