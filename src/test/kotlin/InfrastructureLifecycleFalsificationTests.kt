import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.conformance.ExternalCorpusLoader
import org.flowlang.conformance.ExternalFalsificationOutcome
import org.flowlang.conformance.InfrastructureLifecycleFalsification
import org.flowlang.conformance.InfrastructureLifecycleRequirement

class InfrastructureLifecycleFalsificationTests {
    @Test
    fun repositoryEvidenceProducesMixedInfrastructureLifecycleFalsification() {
        val report = InfrastructureLifecycleFalsification(File(".")).evaluate()
        val outcomes = report.findings.associate {
            "${it.caseId}:${it.factId}" to it.outcome
        }

        assertEquals(8, report.caseCount)
        assertEquals(2, report.distinctRepositoryCount)
        assertEquals(3, report.representableCount)
        assertEquals(5, report.modelGapCount)
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["opentofu-resource-upsert:resource-existence-upsert"]
        )
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["opentofu-resource-deprovisioning:resource-deprovisioning"]
        )
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["opentofu-resource-reconciliation:desired-state-reconciliation"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["opentofu-change-preview:non-mutating-change-preview"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["opentofu-create-before-destroy:create-before-destroy-replacement"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["opentofu-prevent-destroy:destruction-prohibition"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["crossplane-orphan-delete:retain-on-management-removal"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["crossplane-management-actions:persistent-management-action-policy"]
        )
    }

    @Test
    fun resourceUpsertPreservesNamedTargetAndCanonicalTransition() {
        val finding = finding("opentofu-resource-upsert", "resource-existence-upsert")
        assertEquals(InfrastructureLifecycleRequirement.RESOURCE_EXISTENCE_UPSERT, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("UPSERT"))
    }

    @Test
    fun explicitRemovalUsesApprovalProtectedCanonicalDelete() {
        val finding = finding("opentofu-resource-deprovisioning", "resource-deprovisioning")
        assertEquals(InfrastructureLifecycleRequirement.RESOURCE_DEPROVISIONING, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("Approval-protected"))
        assertTrue(finding.reason.contains("DELETE"))
    }

    @Test
    fun desiredStateReconciliationRemainsDistinctFromCreateOnlyMode() {
        val finding = finding("opentofu-resource-reconciliation", "desired-state-reconciliation")
        assertEquals(InfrastructureLifecycleRequirement.DESIRED_STATE_RECONCILIATION, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("reconciliation"))
        assertTrue(finding.reason.contains("UPSERT"))
    }

    @Test
    fun previewModeDoesNotMasqueradeAsNonMutatingPlan() {
        val finding = finding("opentofu-change-preview", "non-mutating-change-preview")
        assertEquals(InfrastructureLifecycleRequirement.NON_MUTATING_CHANGE_PREVIEW, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("mode=preview"))
        assertTrue(finding.reason.contains("UPSERT"))
    }

    @Test
    fun orderedStepsDoNotInventReplacementIdentity() {
        val finding = finding("opentofu-create-before-destroy", "create-before-destroy-replacement")
        assertEquals(
            InfrastructureLifecycleRequirement.CREATE_BEFORE_DESTROY_REPLACEMENT,
            finding.requirement
        )
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("typed old/new replacement relation"))
        assertTrue(finding.reason.contains("unrelated targets"))
    }

    @Test
    fun approvalAndSafetyTextDoNotBecomeDestructionProhibition() {
        val finding = finding("opentofu-prevent-destroy", "destruction-prohibition")
        assertEquals(InfrastructureLifecycleRequirement.DESTRUCTION_PROHIBITION, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("approval-protected DEPROVISION remains valid"))
        assertTrue(finding.reason.contains("not destruction prohibition"))
    }

    @Test
    fun orphanPolicyDoesNotCollapseIntoDeleteEffect() {
        val finding = finding("crossplane-orphan-delete", "retain-on-management-removal")
        assertEquals(InfrastructureLifecycleRequirement.RETAIN_ON_MANAGEMENT_REMOVAL, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("orphan/retain"))
        assertTrue(finding.reason.contains("DELETE"))
    }

    @Test
    fun oneShotModeDoesNotBecomePersistentControllerPolicy() {
        val finding = finding(
            "crossplane-management-actions",
            "persistent-management-action-policy"
        )
        assertEquals(
            InfrastructureLifecycleRequirement.PERSISTENT_MANAGEMENT_ACTION_POLICY,
            finding.requirement
        )
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("one-shot infrastructure UPSERT"))
        assertTrue(finding.reason.contains("persistent management-action policy"))
    }

    @Test
    fun assessmentCannotReferenceUnreviewedSemanticObservation() {
        val root = copiedRepositoryRoot()
        val file = File(
            root,
            "${ExternalCorpusLoader.CORPUS_ROOT}/cases/opentofu-change-preview/" +
                InfrastructureLifecycleFalsification.ASSESSMENT_FILE
        )
        file.writeText(
            file.readText().replace(
                "preserve-non-mutating-change-preview",
                "unreviewed-preview-claim"
            )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            InfrastructureLifecycleFalsification(root).evaluate()
        }
        assertTrue(error.message.orEmpty().contains("references unknown semantic observation"))
    }

    @Test
    fun semanticFactCannotSmuggleProviderSpecificKeys() {
        val root = copiedRepositoryRoot()
        val file = File(
            root,
            "${ExternalCorpusLoader.CORPUS_ROOT}/cases/crossplane-management-actions/" +
                InfrastructureLifecycleFalsification.ASSESSMENT_FILE
        )
        file.writeText(
            file.readText().replace(
                "actions: observe",
                "actions: observe\n      crossplaneDeletionPolicy: provider-only"
            )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            InfrastructureLifecycleFalsification(root).evaluate()
        }
        assertTrue(error.message.orEmpty().contains("unsupported semantic value keys"))
    }

    @Test
    fun domainEvidenceMustRemainIndependentAcrossRepositories() {
        val root = copiedRepositoryRoot()
        listOf("crossplane-orphan-delete", "crossplane-management-actions").forEach { caseId ->
            val file = File(root, "${ExternalCorpusLoader.CORPUS_ROOT}/cases/$caseId/case.yaml")
            file.writeText(
                file.readText().replace(
                    "repository: crossplane/crossplane",
                    "repository: opentofu/opentofu"
                )
            )
        }

        val error = assertFailsWith<IllegalArgumentException> {
            InfrastructureLifecycleFalsification(root).evaluate()
        }
        assertTrue(error.message.orEmpty().contains("independent repositories"))
    }

    private fun finding(caseId: String, factId: String) =
        InfrastructureLifecycleFalsification(File(".")).evaluate().findings.single {
            it.caseId == caseId && it.factId == factId
        }

    private fun copiedRepositoryRoot(): File {
        val root = Files.createTempDirectory("flow-ef08-").toFile()
        val source = File(ExternalCorpusLoader.CORPUS_ROOT)
        val target = File(root, ExternalCorpusLoader.CORPUS_ROOT)
        check(source.copyRecursively(target, overwrite = true))
        return root
    }
}
