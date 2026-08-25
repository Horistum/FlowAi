import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.conformance.DataOrchestrationFalsification
import org.flowlang.conformance.DataOrchestrationRequirement
import org.flowlang.conformance.ExternalCorpusLoader
import org.flowlang.conformance.ExternalFalsificationOutcome

class DataOrchestrationFalsificationTests {
    @Test
    fun repositoryEvidenceProducesMixedDataOrchestrationFalsification() {
        val report = DataOrchestrationFalsification(File(".")).evaluate()
        val outcomes = report.findings.associate { "${it.caseId}:${it.factId}" to it.outcome }

        assertEquals(8, report.caseCount)
        assertEquals(2, report.distinctRepositoryCount)
        assertEquals(4, report.representableCount)
        assertEquals(4, report.modelGapCount)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, outcomes["airflow-task-dependency:task-dependency-order"])
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, outcomes["airflow-conditional-branching:conditional-branching"])
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, outcomes["airflow-scheduled-cadence:daily-orchestration-cadence"])
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, outcomes["airflow-run-data-interval:run-data-interval"])
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, outcomes["airflow-produced-asset:produced-data-identity"])
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, outcomes["airflow-asset-trigger-condition:all-assets-ready-trigger"])
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, outcomes["dagster-asset-dependency:data-asset-dependency"])
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, outcomes["dagster-partition-backfill:partition-backfill-selection"])
    }

    @Test
    fun taskDependencyUsesCanonicalRequiresRatherThanSourceSyntax() {
        val finding = finding("airflow-task-dependency", "task-dependency-order")
        assertEquals(DataOrchestrationRequirement.TASK_DEPENDENCY_ORDER, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("dependency"))
        assertTrue(finding.reason.contains("graph edge"))
    }

    @Test
    fun scheduledCadenceUsesCanonicalIntervalRatherThanAirflowAlias() {
        val finding = finding("airflow-scheduled-cadence", "daily-orchestration-cadence")
        assertEquals(DataOrchestrationRequirement.SCHEDULED_CADENCE, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("Airflow schedule aliases"))
    }

    @Test
    fun freeFormTriggerParamsDoNotMasqueradeAsTypedAssetCondition() {
        val finding = finding("airflow-asset-trigger-condition", "all-assets-ready-trigger")
        assertEquals(DataOrchestrationRequirement.ASSET_TRIGGER_CONDITION, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("free-form"))
        assertTrue(finding.reason.contains("provider-specific"))
    }

    @Test
    fun runDataIntervalRemainsDistinctFromScheduleCadence() {
        val finding = finding("airflow-run-data-interval", "run-data-interval")
        assertEquals(DataOrchestrationRequirement.RUN_DATA_INTERVAL, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("data interval"))
        assertTrue(finding.reason.contains("schedule"))
    }

    @Test
    fun partitionSubsetDoesNotCollapseIntoGenericBatches() {
        val finding = finding("dagster-partition-backfill", "partition-backfill-selection")
        assertEquals(DataOrchestrationRequirement.PARTITION_BACKFILL_SELECTION, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("partition"))
        assertTrue(finding.reason.contains("batches"))
    }

    @Test
    fun conditionalBranchDoesNotCollapseIntoStaticRequiresGraph() {
        val finding = finding("airflow-conditional-branching", "conditional-branching")
        assertEquals(DataOrchestrationRequirement.CONDITIONAL_BRANCHING, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("static requires edges"))
    }

    @Test
    fun assessmentCannotReferenceUnreviewedSemanticObservation() {
        val root = copiedRepositoryRoot()
        val file = File(root, "${ExternalCorpusLoader.CORPUS_ROOT}/cases/airflow-run-data-interval/${DataOrchestrationFalsification.ASSESSMENT_FILE}")
        file.writeText(file.readText().replace("preserve-run-data-interval", "unreviewed-interval-claim"))

        val error = assertFailsWith<IllegalArgumentException> { DataOrchestrationFalsification(root).evaluate() }
        assertTrue(error.message.orEmpty().contains("references unknown semantic observation"))
    }

    @Test
    fun semanticFactCannotSmuggleProviderSpecificKeys() {
        val root = copiedRepositoryRoot()
        val file = File(root, "${ExternalCorpusLoader.CORPUS_ROOT}/cases/airflow-asset-trigger-condition/${DataOrchestrationFalsification.ASSESSMENT_FILE}")
        file.writeText(
            file.readText().replace(
                "condition: all",
                "condition: all\n      airflowDatasetExpression: provider-only"
            )
        )

        val error = assertFailsWith<IllegalArgumentException> { DataOrchestrationFalsification(root).evaluate() }
        assertTrue(error.message.orEmpty().contains("unsupported semantic value keys"))
    }

    @Test
    fun domainEvidenceMustRemainIndependentAcrossRepositories() {
        val root = copiedRepositoryRoot()
        listOf("dagster-asset-dependency", "dagster-partition-backfill").forEach { caseId ->
            val file = File(root, "${ExternalCorpusLoader.CORPUS_ROOT}/cases/$caseId/case.yaml")
            file.writeText(file.readText().replace("repository: dagster-io/dagster", "repository: apache/airflow"))
        }

        val error = assertFailsWith<IllegalArgumentException> { DataOrchestrationFalsification(root).evaluate() }
        assertTrue(error.message.orEmpty().contains("independent repositories"))
    }

    private fun finding(caseId: String, factId: String) = DataOrchestrationFalsification(File(".")).evaluate().findings.single {
        it.caseId == caseId && it.factId == factId
    }

    private fun copiedRepositoryRoot(): File {
        val root = Files.createTempDirectory("flow-ef07-").toFile()
        val source = File(ExternalCorpusLoader.CORPUS_ROOT)
        val target = File(root, ExternalCorpusLoader.CORPUS_ROOT)
        check(source.copyRecursively(target, overwrite = true))
        return root
    }
}
