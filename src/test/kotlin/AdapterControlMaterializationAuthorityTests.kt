import java.io.File
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
import org.flowlang.adapters.control.AdapterControlMaterializationLoader
import org.flowlang.adapters.control.AdapterControlRequirementCompleteness
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
    fun jenkinsProviderBackedApprovalMatches() {
        val assessment = authority.assess(
            ExecutionPlan(flowName = "approval", nodes = listOf(ApprovalNode(id = "approve-production"))),
            "jenkins"
        )

        assertEquals(AdapterControlDecision.MATCHED, assessment.decision)
        assertEquals(listOf("approval.manual.inline"), assessment.requirements.map { it.semantic })
        assertTrue(assessment.evidence.all { it.status == AdapterControlEvidenceStatus.SATISFIED })
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
        assertTrue(assessment.evidence.all { it.status == AdapterControlEvidenceStatus.UNSUPPORTED })
        assertFailsWith<UnresolvedAdapterControlMaterializationException> {
            authority.requireMatched(plan, "jenkins")
        }
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
        val manifest = TargetManifest(
            target = "jenkins",
            flowName = "retry",
            compatibility = CompatibilityReport(
                target = "jenkins",
                status = SupportLevel.SUPPORTED,
                executable = true,
                readinessEvidenceAvailable = true
            )
        )

        val reconciled = authority.reconcileDiagnostic(manifest, assessment)
        assertEquals(SupportLevel.UNSUPPORTED, reconciled.compatibility.status)
        assertFalse(reconciled.compatibility.executable)
        assertTrue(reconciled.compatibility.issues.any { it.feature.contains("control.retry") })
        assertEquals(AdapterControlDecision.BLOCKED.name, reconciled.metadata["adapterControlDecision"])
    }

    @Test
    fun supportedSemanticRequiresIndependentImplementationEvidence() {
        val document = AdapterControlMaterializationLoader.load(rootDir)
        val malformed = document.copy(
            targets = document.targets.map { record ->
                if (record.target != "jenkins") record else record.copy(
                    claims = record.claims.map { claim ->
                        if (claim.family != AdapterControlFamily.APPROVAL) claim else claim.copy(
                            evidenceReferences = listOf(
                                "targets/builtin-targets.yaml#targets.jenkins.features.approvals"
                            )
                        )
                    }
                )
            }
        )

        val report = authority.analyze(malformed)
        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "CONTROL_EVIDENCE_REGISTRY_ONLY" })
        assertTrue(report.findings.any { it.code == "CONTROL_SUPPORTED_IMPLEMENTATION_EVIDENCE_MISSING" })
    }

    @Test
    fun targetRegistryCannotBeTheOnlyNegativeEvidence() {
        val document = AdapterControlMaterializationLoader.load(rootDir)
        val malformed = document.copy(
            targets = document.targets.map { record ->
                if (record.target != "github-actions") record else record.copy(
                    claims = record.claims.map { claim ->
                        if (claim.family != AdapterControlFamily.COMPENSATION) claim else claim.copy(
                            evidenceReferences = listOf(
                                "targets/builtin-targets.yaml#targets.github-actions.projectionRules.standard.rollback"
                            )
                        )
                    }
                )
            }
        )

        val report = authority.analyze(malformed)
        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "CONTROL_EVIDENCE_REGISTRY_ONLY" })
    }

    @Test
    fun incompleteSemanticPartitionFailsClosed() {
        val document = AdapterControlMaterializationLoader.load(rootDir)
        val jenkins = document.targets.single { it.target == "jenkins" }
        val approval = jenkins.claims.single { it.family == AdapterControlFamily.APPROVAL }
        val malformed = document.copy(
            targets = document.targets.map { record ->
                if (record.target != "jenkins") record else record.copy(
                    claims = record.claims.map { claim ->
                        if (claim.family != AdapterControlFamily.APPROVAL) claim else claim.copy(
                            semantics = claim.semantics.copy(
                                unsupported = claim.semantics.unsupported - "approval.external"
                            )
                        )
                    }
                )
            }
        )

        val report = authority.analyze(malformed)
        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { it.code == "CONTROL_SEMANTIC_PARTITION_MISMATCH" })
        assertTrue(approval.semantics.all.contains("approval.external"))
    }
}
