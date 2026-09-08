import org.flowlang.frontend.FrontendCompilerComposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flowlang.generators.manifest.ExecutionPlanMaterializationValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentInput
import org.flowlang.intent.IntentObject
import org.flowlang.intent.IntentRef
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.lowering.IntentLoweringAuthority
import org.flowlang.lowering.IntentLoweringDisposition
import org.flowlang.lowering.IntentLoweringReport
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.TaskNode

class ArtifactDerivedIntentLoweringEvidenceTests {
    @Test
    fun reportIsIssuedOnlyAfterTheExecutionPlanExists() {
        val ast = FrontendCompilerComposition.intentPlanner().plan(stableIdentityIntent())
        assertNull(ast.metadata.loweringReport)

        val plan = FlowPlanner(ModuleRegistry()).plan(ast)
        val report = assertNotNull(plan.loweringReport)

        assertEquals(IntentLoweringReport.CONTRACT_VERSION, report.contractVersion)
        assertEquals(IntentLoweringReport.ARTIFACT_KIND, report.artifactKind)
        assertTrue(report.evidenceDigest.matches(Regex("[0-9a-f]{64}")))
        assertEquals(
            report,
            IntentLoweringAuthority.report(plan.copy(loweringReport = null)),
            "The committed report must be reproducible from the concrete execution plan."
        )
    }

    @Test
    fun stableIdentitiesRemainCorrectWhenTopologicalLoweringReordersSourceSteps() {
        val intent = stableIdentityIntent()
        val plan = FlowPlanner(ModuleRegistry()).plan(FrontendCompilerComposition.intentPlanner().plan(intent))
        val report = assertNotNull(plan.loweringReport)

        assertEquals(listOf("compile-artifact", "publish-artifact"), plan.tasks.mapNotNull { it.sourceId })
        assertTrue(report.evidence.any {
            it.sourceIdentity == "step/publish-artifact/param/channel" &&
                it.targetIdentity == "plan/node/source/publish-artifact/param/channel"
        })
        assertTrue(report.evidence.any {
            it.sourceIdentity == "step/publish-artifact/requires/compile-artifact" &&
                it.targetIdentity == "plan/node/source/publish-artifact/dependency/source/compile-artifact"
        })
        assertTrue(report.evidence.none { evidence ->
            Regex("/(?:nodes?|steps?)/?\\d+").containsMatchIn(evidence.sourceIdentity) ||
                Regex("/(?:nodes?|steps?)/?\\d+").containsMatchIn(evidence.targetIdentity)
        })
    }

    @Test
    fun preservedAndTransformedClaimsAreValueBacked() {
        val plan = FlowPlanner(ModuleRegistry()).plan(FrontendCompilerComposition.intentPlanner().plan(stableIdentityIntent()))
        val report = assertNotNull(plan.loweringReport)

        val preserved = report.evidence.single { it.sourceIdentity == "step/compile-artifact/param/phase" }
        assertEquals(IntentLoweringDisposition.PRESERVED, preserved.disposition)
        assertEquals(preserved.sourceDigest, preserved.targetDigest)
        assertNull(preserved.transform)

        val transformed = report.evidence.single { it.sourceIdentity == "step/compile-artifact/param/reference" }
        assertEquals(IntentLoweringDisposition.TRANSFORMED, transformed.disposition)
        assertEquals("intent-value-runtime-binding", transformed.transform)
        assertFalse(transformed.sourceDigest == transformed.targetDigest)

        val identityTransform = report.evidence.single { it.sourceIdentity == "step/compile-artifact/id" }
        assertEquals(IntentLoweringDisposition.TRANSFORMED, identityTransform.disposition)
        assertEquals("step-id-to-source-and-result-identity", identityTransform.transform)
    }

    @Test
    fun changedConcreteTargetValueInvalidatesTheReport() {
        val plan = FlowPlanner(ModuleRegistry()).plan(FrontendCompilerComposition.intentPlanner().plan(stableIdentityIntent()))
        val tampered = plan.copy(nodes = plan.nodes.map { node ->
            if (node is TaskNode && node.sourceId == "compile-artifact") {
                node.copy(
                    params = node.params + ("phase" to "\"tampered\""),
                    inputs = node.inputs + ("phase" to "\"tampered\"")
                )
            } else {
                node
            }
        })

        assertContainsLoweringIssue(tampered, "planning.lowering.target-value.mismatch")
    }

    @Test
    fun forgedArtifactDigestIsRejectedEvenWhenEveryEvidenceEntryLooksValid() {
        val plan = FlowPlanner(ModuleRegistry()).plan(FrontendCompilerComposition.intentPlanner().plan(stableIdentityIntent()))
        val report = assertNotNull(plan.loweringReport)
        val forged = plan.copy(loweringReport = report.copy(evidenceDigest = "0".repeat(64)))

        assertContainsLoweringIssue(forged, "planning.lowering.report.stale-or-forged")
    }

    @Test
    fun missingAndDuplicateStableEvidenceCannotBeHidden() {
        val plan = FlowPlanner(ModuleRegistry()).plan(FrontendCompilerComposition.intentPlanner().plan(stableIdentityIntent()))
        val report = assertNotNull(plan.loweringReport)
        val removed = report.evidence.first()
        val missing = plan.copy(loweringReport = report.copy(evidence = report.evidence.drop(1)))
        val duplicate = plan.copy(loweringReport = report.copy(evidence = report.evidence + report.evidence.first()))

        val missingCodes = loweringIssueCodes(missing)
        assertTrue("planning.lowering.evidence.missing" in missingCodes, "Missing ${removed.sourceIdentity} must be detected.")
        assertTrue("planning.lowering.report.stale-or-forged" in missingCodes)
        assertTrue("planning.lowering.evidence.duplicate" in loweringIssueCodes(duplicate))
    }

    @Test
    fun validArtifactDerivedEvidencePassesMaterializationValidation() {
        val plan = FlowPlanner(ModuleRegistry()).plan(FrontendCompilerComposition.intentPlanner().plan(stableIdentityIntent()))
        val loweringIssues = ExecutionPlanMaterializationValidator.validate(plan, ModuleRegistry())
            .filter { it.code.startsWith("planning.lowering.") }

        assertTrue(loweringIssues.isEmpty(), loweringIssues.joinToString { "${it.code}: ${it.message}" })
    }

    private fun assertContainsLoweringIssue(plan: ExecutionPlan, expected: String) {
        val codes = loweringIssueCodes(plan)
        assertTrue(expected in codes, "Expected $expected, found ${codes.sorted()}.")
    }

    private fun loweringIssueCodes(plan: ExecutionPlan): Set<String> =
        ExecutionPlanMaterializationValidator.validate(plan, ModuleRegistry())
            .map { it.code }
            .filter { it.startsWith("planning.lowering.") }
            .toSet()

    private fun stableIdentityIntent(): IntentDocument = IntentDocument(
        name = "artifact-derived-lowering",
        description = "Prove lowering against concrete execution values",
        inputs = listOf(
            IntentInput(
                name = "config",
                type = "object",
                required = true,
                default = IntentObject(mapOf("value" to IntentString("default")))
            )
        ),
        workflows = listOf(
            IntentWorkflow(
                name = "main",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(
                    IntentStep(
                        id = "publish-artifact",
                        capability = StandardCapability.CUSTOM,
                        description = "Publish after compilation",
                        requires = listOf("compile-artifact"),
                        params = mapOf("channel" to IntentString("stable"))
                    ),
                    IntentStep(
                        id = "compile-artifact",
                        capability = StandardCapability.CUSTOM,
                        description = "Compile the source artifact",
                        produces = listOf("artifact"),
                        params = mapOf(
                            "phase" to IntentString("compile"),
                            "reference" to IntentRef(listOf("config", "value"))
                        )
                    )
                )
            )
        )
    )
}
