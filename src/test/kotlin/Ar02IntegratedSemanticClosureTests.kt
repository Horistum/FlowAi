package org.flowlang.tests

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.cli.Json
import org.flowlang.conformance.Ar02ClosureLifecycle
import org.flowlang.conformance.Ar02ClosureSemanticEvidence
import org.flowlang.conformance.Ar02FindingClosureCatalog
import org.flowlang.conformance.Ar02FindingClosureDocument
import org.flowlang.conformance.Ar02FindingClosureEvidenceValidator
import org.flowlang.conformance.Ar02IntegratedSemanticClosureChecks
import org.flowlang.conformance.Ar02MutationObservation
import org.flowlang.conformance.Ar02PublicCompatibilityMatrix
import org.flowlang.conformance.Ar02TargetMatrix
import org.flowlang.conformance.Ar02TargetOutcome
import org.flowlang.conformance.Ar02TargetScenario
import org.flowlang.conformance.ArchitectureRecoveryConformanceInventory
import org.flowlang.conformance.ConformanceCheck
import org.flowlang.conformance.ar02PredecessorChecks
import org.flowlang.serialization.FlowYaml
import org.flowlang.targets.builtin.BuiltInTargetProjections

class Ar02IntegratedSemanticClosureTests {
    @Test
    fun integratedClosureMatrixPassesAsOneAtomicBoundary() {
        val checks = closure.checks(predecessors)
        assertEquals(listOf(
            Ar02IntegratedSemanticClosureChecks.FRONTEND_MATRIX,
            Ar02IntegratedSemanticClosureChecks.MUTATION_MATRIX,
            Ar02IntegratedSemanticClosureChecks.TARGET_MATRIX,
            Ar02IntegratedSemanticClosureChecks.PUBLIC_COMPATIBILITY_MATRIX,
            Ar02IntegratedSemanticClosureChecks.FINDING_CLOSURE
        ), checks.map { it.name })
        assertTrue(checks.all { it.passed }, checks.filterNot { it.passed }.joinToString { "${it.name}: ${it.message}" })
    }

    @Test
    fun commonProgramActuallyConvergesThroughAllThreeFrontends() {
        val errors = Ar02ClosureSemanticEvidence.frontendErrors(closure.commonUnits())
        assertTrue(errors.isEmpty(), errors.joinToString())
    }

    @Test
    fun frontendMatrixRejectsChangedApprovalMeaning() {
        val changed = closure.compileFlowSource(
            Ar02IntegratedSemanticClosureChecks.COMMON_SOURCE.replace("Approval required", "Changed approval"), "ar02e-negative-frontend"
        )
        val units = listOf(changed) + closure.commonUnits().drop(1)
        val errors = Ar02ClosureSemanticEvidence.frontendErrors(units)
        assertTrue(errors.any { "disagree" in it }, errors.joinToString())
    }

    @Test
    fun frontendMatrixRejectsMissingFrontendRatherThanComparingTheSameUnitTwice() {
        val units = closure.commonUnits()
        assertTrue(Ar02ClosureSemanticEvidence.frontendErrors(listOf(units[0], units[0], units[2])).isNotEmpty())
    }

    @Test
    fun staleAuthorizationRejectsWorkflowMembershipAndRoutingChanges() {
        assertMutations("workflow-membership", "trigger-routing")
    }

    @Test
    fun staleAuthorizationRejectsFailureAndHandlerChanges() {
        assertMutations("failure-disposition", "handler-identity", "handler-membership", "error-binding", "entry-availability")
    }

    @Test
    fun staleAuthorizationRejectsChangedValuesProducersAndEdges() {
        assertMutations("path-value", "merge-producer", "merge-edge")
    }

    @Test
    fun mutationMatrixRejectsDigestThatIgnoresSemanticChange() {
        val changed = closure.mutationObservations.map {
            if (it.id == "path-value") it.copy(mutatedDigest = it.originalDigest) else it
        }
        assertTrue(Ar02ClosureSemanticEvidence.mutationErrors(changed).any { "path-value" in it })
    }

    @Test
    fun mutationMatrixRejectsAcceptedStaleAuthorizationEvenWithChangedDigest() {
        val changed = closure.mutationObservations.map {
            if (it.id == "failure-disposition") it.copy(staleAuthorizationRejected = false) else it
        }
        assertTrue(Ar02ClosureSemanticEvidence.mutationErrors(changed).any { "stale authorization" in it })
    }

    @Test
    fun mutationMatrixRejectsMissingOrDuplicateWitness() {
        val observations = closure.mutationObservations
        assertTrue(Ar02ClosureSemanticEvidence.mutationErrors(observations.dropLast(1)).isNotEmpty())
        assertTrue(Ar02ClosureSemanticEvidence.mutationErrors(observations + observations.first()).isNotEmpty())
    }

    @Test
    fun realTargetMatrixPreservesAllRegistryBoundaries() {
        val errors = closure.targetMatrixErrors()
        assertTrue(errors.isEmpty(), errors.joinToString())
        assertEquals(targets.size * Ar02TargetScenario.entries.size, closure.targetObservations.size)
    }

    @Test
    fun targetMatrixRejectsUnsupportedMergePromotedToExecutable() {
        val changed = closure.targetObservations.map {
            if (it.target == "jenkins" && it.scenario == Ar02TargetScenario.EXPLICIT_MERGE) {
                it.copy(outcome = Ar02TargetOutcome.EXECUTABLE)
            } else it
        }
        assertTrue(Ar02TargetMatrix.errors(changed, targets, providers).any { "EXPLICIT_MERGE" in it })
    }

    @Test
    fun targetMatrixRejectsRenderingWithoutPropagateRethrow() {
        val changed = closure.targetObservations.map {
            if (it.target == "jenkins" && it.scenario == Ar02TargetScenario.WORKFLOW_FAILURE) {
                it.copy(renderedText = it.renderedText?.replace("throw flowError", ""))
            } else it
        }
        assertTrue(Ar02TargetMatrix.errors(changed, targets, providers).any { "rethrow" in it || "propagate" in it })
    }

    @Test
    fun targetMatrixRejectsUnexpectedCrashAndMissingProviderRows() {
        val observations = closure.targetObservations
        val crash = observations.mapIndexed { index, row -> if (index == 0) row.copy(error = "unexpected parser crash") else row }
        assertTrue(Ar02TargetMatrix.errors(crash, targets, providers).any { "unexpected" in it })
        assertTrue(Ar02TargetMatrix.errors(observations.dropLast(1), targets, providers).any { "exactly once" in it })
    }

    @Test
    fun publicMatrixValidatesTheActualSingleAndMultiWorkflowPayloads() {
        val errors = Ar02PublicCompatibilityMatrix.errors(closure.failureUnit, closure.multiUnit, schema)
        assertTrue(errors.isEmpty(), errors.joinToString())
    }

    @Test
    fun publicMatrixRejectsMissingFailurePolicy() {
        val payload: ObjectNode = Json.mapper.valueToTree(closure.failureUnit.workflowPlanSet)
        (payload.path("workflows").path(0) as ObjectNode).remove("failurePolicy")
        val errors = Ar02PublicCompatibilityMatrix.errors(closure.failureUnit, closure.multiUnit, schema, payload)
        assertTrue(errors.any { "failurePolicy" in it }, errors.joinToString())
    }

    @Test
    fun publicMatrixRejectsSchemaThatStopsRequiringFailurePolicy() {
        val weakened = schema.deepCopy<ObjectNode>()
        val workflow = weakened.path("\$defs").path("workflow") as ObjectNode
        val required = workflow.putArray("required")
        listOf("workflowId", "workflowName", "executionPlan", "canonicalPlan").forEach(required::add)
        val errors = Ar02PublicCompatibilityMatrix.errors(closure.failureUnit, closure.multiUnit, weakened)
        assertTrue(errors.any { "missing failure policy" in it }, errors.joinToString())
    }

    @Test
    fun boundedSchemaEvaluatorFailsClosedOnAnUnsupportedConstraint() {
        val unsupported = schema.deepCopy<ObjectNode>().put("unsupportedConstraint", true)
        val payload = Json.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(closure.failureUnit.workflowPlanSet)
        assertTrue(Ar02PublicCompatibilityMatrix.schemaErrors(payload, unsupported).any { "unsupported schema keyword" in it })
    }

    @Test
    fun findingCatalogMapsProductionDeclarationsToCurrentSuccessfulEvidence() {
        assertEquals(setOf("F-02", "F-08", "F-15"), catalog.findings.map { it.findingId }.toSet())
        val errors = findingErrors(catalog, predecessors)
        assertTrue(errors.isEmpty(), errors.joinToString())
    }

    @Test
    fun closureEvidenceCannotPassWhenReferencedBehaviorFailed() {
        val failingName = catalog.findings.first().negativeChecks.first()
        val failed = predecessors.map { if (it.name == failingName) ConformanceCheck(it.name, false, "injected semantic regression") else it }
        val errors = findingErrors(catalog, failed)
        assertTrue(errors.any { failingName in it && "failed" in it }, errors.joinToString())
        assertFalse(closure.checks(failed).single { it.name == Ar02IntegratedSemanticClosureChecks.FINDING_CLOSURE }.passed)
    }

    @Test
    fun declaredCheckWithoutOneCurrentResultCannotCloseFinding() {
        val referenced = catalog.findings.first().positiveChecks.first()
        assertTrue(findingErrors(catalog, predecessors.filterNot { it.name == referenced }).any { "current execution" in it })
        assertTrue(findingErrors(catalog, predecessors + predecessors.single { it.name == referenced }).any { "current execution" in it })
    }

    @Test
    fun closureEvidenceCannotUsePositiveCheckAsNegativeEvidence() {
        val changed = catalog.copy(findings = catalog.findings.mapIndexed { index, entry ->
            if (index == 0) entry.copy(negativeChecks = entry.positiveChecks) else entry
        })
        assertTrue(findingErrors(changed, predecessors).any { "polarity" in it })
    }

    @Test
    fun closureEvidenceCannotReferenceMissingDeclarationsOrDuplicateProductionFiles() {
        val changed = catalog.copy(findings = catalog.findings.mapIndexed { index, entry ->
            if (index == 0) entry.copy(productionContracts = List(3) { entry.productionContracts.first().copy(symbol = "MissingDeclaration") }) else entry
        })
        val errors = findingErrors(changed, predecessors)
        assertTrue(errors.any { "distinct production" in it })
        assertTrue(errors.any { "declaration is missing" in it })
    }

    @Test
    fun evidenceYamlRejectsUnknownFieldsAndDuplicateKeys() {
        val text = File(root, Ar02FindingClosureCatalog.PATH).readText()
        assertFails { FlowYaml.readStrict(text + "\nunknownEvidence: true\n", Ar02FindingClosureDocument::class.java, "unknown.yaml") }
        assertFails { FlowYaml.readStrict("version: \"1.0\"\n" + text, Ar02FindingClosureDocument::class.java, "duplicate.yaml") }
    }

    @Test
    fun lifecycleRejectsPrematureCompletionWithPendingBoundaries() {
        val snapshot = Ar02ClosureLifecycle.load(root)
        val work = snapshot.workPackage + ("status" to "complete") + ("lifecycle" to mapOf(
            "activationBoundary" to mapOf("status" to "passed"),
            "implementationBoundary" to mapOf("status" to "candidate"),
            "validationBoundary" to mapOf("status" to "candidate"),
            "completionBoundary" to mapOf("status" to "pending")
        ))
        val errors = Ar02ClosureLifecycle.errors(snapshot.copy(workPackage = work))
        assertTrue(errors.any { "completionBoundary is not a passed" in it }, errors.joinToString())
    }

    @Test
    fun lifecycleRequiresStructuredEvidenceInsteadOfMatchingTextElsewhere() {
        val boundary = mapOf(
            "status" to "passed", "conclusion" to "success",
            "unrelated" to mapOf("exactHead" to "1234567890123456789012345678901234567890", "workflowRunId" to 123)
        )
        val errors = Ar02ClosureLifecycle.boundaryErrors("completionBoundary", boundary)
        assertTrue(errors.any { "completionBoundary.exactHead" in it })
        assertTrue(errors.any { "completionBoundary.workflowRunId" in it })
    }

    @Test
    fun lifecycleRejectsReusedJobsAndWrongScalarTypes() {
        val boundary = mapOf(
            "status" to "passed", "conclusion" to "success",
            "workflowRunId" to "123", "workflowRunNumber" to 1,
            "exactHeadJobId" to 456L, "mergeCandidateJobId" to 456L,
            "exactHead" to "1234567890123456789012345678901234567890",
            "syntheticMergeCandidate" to "2345678901234567890123456789012345678901"
        )
        val errors = Ar02ClosureLifecycle.boundaryErrors("completionBoundary", boundary)
        assertTrue(errors.any { "workflowRunId" in it })
        assertTrue(errors.any { "distinguish exact-head" in it })
    }

    @Test
    fun lifecycleAcceptsCurrentCoherentClaimAndRejectsDuplicateYamlKeys() {
        val errors = Ar02ClosureLifecycle.errors(Ar02ClosureLifecycle.load(root))
        assertTrue(errors.isEmpty(), errors.joinToString())
        assertFails { FlowYaml.readMap("status: active\nstatus: complete\n", "duplicate-lifecycle.yaml") }
    }

    private fun assertMutations(vararg names: String) {
        val selected = closure.mutationObservations.filter { it.id in names }
        assertEquals(names.toSet(), selected.map { it.id }.toSet())
        selected.forEach { witness: Ar02MutationObservation ->
            assertTrue(witness.graphChanged && witness.originalDigest != witness.mutatedDigest, witness.id)
            assertTrue(witness.staleDigestRejected && witness.staleAuthorizationRejected, witness.id)
            if (witness.mustRejectGraph) assertTrue(witness.graphRejected, witness.id)
        }
    }

    private fun findingErrors(document: Ar02FindingClosureDocument, results: List<ConformanceCheck>) =
        Ar02FindingClosureEvidenceValidator.errors(document, declared, results, root)

    companion object {
        private val root = File(".")
        private val closure by lazy { Ar02IntegratedSemanticClosureChecks(root) }
        private val predecessors by lazy { ar02PredecessorChecks(root) }
        private val catalog by lazy { Ar02FindingClosureCatalog.load(root) }
        private val declared by lazy { ArchitectureRecoveryConformanceInventory.load(root).checks.toSet() }
        private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File(root, "targets")).keys }
        private val providers by lazy { BuiltInTargetProjections.registry.targetIds }
        private val schema by lazy { Json.mapper.readTree(File(root, "schemas/workflow-execution-plan-set.schema.json")) }
    }
}
