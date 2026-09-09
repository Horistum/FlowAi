package org.flowlang.tests

import org.flowlang.frontend.FrontendCompilerComposition

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flowlang.ai.normalization.AiIntentContext
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.AiIntentResponse
import org.flowlang.ai.normalization.ConfidenceScore
import org.flowlang.ai.normalization.IntentClassification
import org.flowlang.ai.normalization.NormalizationMode
import org.flowlang.ai.normalization.NormalizationReport
import org.flowlang.ai.normalization.ScenarioSelectionReport
import org.flowlang.compiler.CompilationFrontend
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.CompilationSource
import org.flowlang.compiler.CompilationStage
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.IntentCompilationInput
import org.flowlang.compiler.ReviewedAiProposalCompilationInput
import org.flowlang.compiler.requireAccepted
import org.flowlang.frontend.ai.ReviewedAiProposal
import org.flowlang.frontend.ai.ReviewedAiProposalFrontend
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry

class ReviewedAiProposalFrontendConvergenceTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"))
    private val compiler = FrontendCompilerComposition.compiler(registry)
    private val aiFrontend = ReviewedAiProposalFrontend(compiler)
    private val intentFrontend = IntentYamlFrontend(compiler)
    private val referenceFile = File("examples/intent/build-test-deploy.intent.yaml")
    private val referenceIntent: IntentDocument = IntentYamlLoader.load(referenceFile)

    @Test
    fun reviewedProposalAndIntentYamlConvergeOnTheSameAuthorizedGraph() {
        val yaml = intentFrontend.compile(referenceFile).requireAccepted()
        val ai = aiFrontend.compile(proposal(response(referenceIntent))).requireAccepted()
        val evidence = ai.requireReviewedAiProposalEvidence()

        assertEquals(CompilationFrontend.REVIEWED_AI_PROPOSAL, ai.source.frontend)
        assertEquals("application/vnd.flow.intent-proposal+json", ai.source.mediaType)
        assertEquals(referenceIntent, evidence.intent)
        assertEquals(referenceIntent, evidence.review.intent)
        assertTrue(evidence.review.accepted)
        assertEquals(evidence.review.validation, evidence.validation)
        assertEquals(referenceIntent, ai.requireIntentCompilationEvidence().intent)
        assertEquals(yaml.graph, ai.graph)
        assertEquals(yaml.graphDigest, ai.graphDigest)
        assertEquals(yaml.executionPlan, ai.executionPlan)
        assertEquals(yaml.canonicalPlan, ai.canonicalPlan)
        assertEquals(ai.source.sha256, ai.validationBinding.sourceSha256)
        assertEquals(ai.graphDigest.value, ai.validationBinding.graphDigest)
        assertEquals(true, ai.validationBinding.proposalReviewValid)
        ai.authorization.requireIntegrity()
    }

    @Test
    fun everyProviderOnlyFacetChangesSourceEvidenceWithoutChangingSemanticGraphIdentity() {
        val baseline = aiFrontend.compile(
            proposal(response(referenceIntent, explanation = listOf("baseline provider explanation")))
        ).requireAccepted()
        val variants = listOf(
            "provider" to proposal(
                response(referenceIntent, explanation = listOf("baseline provider explanation")),
                providerId = "alternate-provider",
                identity = "proposal:provider"
            ),
            "request" to proposal(
                response(referenceIntent, explanation = listOf("baseline provider explanation")),
                request = request(userText = "An alternate authored request with the same accepted intent."),
                identity = "proposal:request"
            ),
            "confidence" to proposal(
                response(
                    referenceIntent,
                    explanation = listOf("baseline provider explanation"),
                    overallConfidence = 0.71
                ),
                identity = "proposal:confidence"
            ),
            "explanation" to proposal(
                response(referenceIntent, explanation = listOf("alternate provider explanation")),
                identity = "proposal:explanation"
            )
        )

        variants.forEach { (facet, proposal) ->
            val alternate = aiFrontend.compile(proposal).requireAccepted()
            assertNotEquals(baseline.source.sha256, alternate.source.sha256, facet)
            assertEquals(baseline.graph, alternate.graph, facet)
            assertEquals(baseline.graphDigest, alternate.graphDigest, facet)
            assertEquals(baseline.executionPlan, alternate.executionPlan, facet)
        }
    }

    @Test
    fun proposalSourceDigestIsIndependentOfMapInsertionOrder() {
        val firstRequest = request(
            knownSystems = linkedMapOf("registry" to "oci", "source" to "git")
        )
        val secondRequest = request(
            knownSystems = linkedMapOf("source" to "git", "registry" to "oci")
        )
        val firstResponse = response(
            referenceIntent,
            entities = linkedMapOf("application" to "orders", "environment" to "prod")
        )
        val secondResponse = response(
            referenceIntent,
            entities = linkedMapOf("environment" to "prod", "application" to "orders")
        )

        val first = aiFrontend.compile(proposal(firstResponse, request = firstRequest, identity = "proposal:first"))
            .requireAccepted()
        val second = aiFrontend.compile(proposal(secondResponse, request = secondRequest, identity = "proposal:second"))
            .requireAccepted()

        assertEquals(first.source.sha256, second.source.sha256)
        assertEquals(first.source.byteCount, second.source.byteCount)
        assertEquals(first.graphDigest, second.graphDigest)
    }

    @Test
    fun compilationRetainsADetachedSnapshotOfCallerOwnedProposalCollections() {
        val knownSystems = linkedMapOf("source" to "git")
        val explanation = mutableListOf("captured explanation")
        val matchedTriggers = mutableListOf("build", "test")
        val firstWorkflowSteps = referenceIntent.workflows.first().steps.toMutableList()
        val workflows = referenceIntent.workflows.toMutableList().also { values ->
            values[0] = values[0].copy(steps = firstWorkflowSteps)
        }
        val mutableIntent = referenceIntent.copy(workflows = workflows)
        val unit = aiFrontend.compile(
            proposal(
                response(
                    mutableIntent,
                    explanation = explanation,
                    scenarioSelection = ScenarioSelectionReport(
                        selectedPack = "build-test",
                        matchedTriggers = matchedTriggers,
                        reason = "fixture"
                    )
                ),
                request = request(knownSystems = knownSystems),
                identity = "proposal:detached-snapshot"
            )
        ).requireAccepted()

        knownSystems["late"] = "mutation"
        explanation += "late mutation"
        matchedTriggers += "late mutation"
        firstWorkflowSteps.clear()
        workflows.clear()

        val evidence = unit.requireReviewedAiProposalEvidence()
        assertEquals(mapOf("source" to "git"), evidence.request.context.knownSystems)
        assertEquals(listOf("captured explanation"), evidence.response.report.explanation)
        assertEquals(listOf("build", "test"), evidence.response.report.scenarioSelection?.matchedTriggers)
        assertEquals(referenceIntent.workflows, evidence.intent.workflows)
        assertEquals(unit.source.sha256, unit.validationBinding.sourceSha256)
        unit.authorization.requireIntegrity()
    }

    @Test
    fun semanticIntentMutationChangesReviewedProposalGraphDigest() {
        val mutatedIntent = IntentYamlLoader.loadText(
            referenceFile.readText().replace("branch: main", "branch: release"),
            "<mutated-reviewed-proposal>"
        )
        val baseline = aiFrontend.compile(proposal(response(referenceIntent))).requireAccepted()
        val alternate = aiFrontend.compile(
            proposal(response(mutatedIntent), identity = "proposal:semantic-mutation")
        ).requireAccepted()

        assertNotEquals(baseline.source.sha256, alternate.source.sha256)
        assertNotEquals(baseline.graphDigest, alternate.graphDigest)
        assertNotEquals(baseline.executionPlan, alternate.executionPlan)
    }

    @Test
    fun rejectedProposalStopsAtReviewAndRetainsExactReviewEvidence() {
        val unsafeIntent = IntentDocument(
            name = "broken-proposal",
            workflows = listOf(
                IntentWorkflow(
                    name = "build",
                    kind = IntentWorkflowKind.BUILD,
                    steps = listOf(
                        IntentStep(
                            id = "build",
                            capability = StandardCapability.BUILD,
                            requires = listOf("ghost")
                        )
                    )
                )
            )
        )
        val response = response(unsafeIntent)
        val result = aiFrontend.compile(proposal(response, identity = "proposal:unsafe"))
        val rejection = assertIs<CompilationResult.Rejected>(result).rejection

        assertEquals(CompilationStage.PROPOSAL_REVIEW, rejection.stage)
        assertEquals(response, rejection.proposal)
        assertEquals(unsafeIntent, rejection.intent)
        assertEquals(unsafeIntent, rejection.proposalReview?.intent)
        assertEquals(rejection.intentValidation, rejection.proposalReview?.validation)
        assertTrue(rejection.diagnostics.any { it.code == "UNKNOWN_STEP_DEPENDENCY" })
        assertNull(rejection.ast)
        assertNull(rejection.flowValidation)
    }

    @Test
    fun graphAuthorizationRejectsFrontendEvidenceShapeMismatches() {
        val yaml = intentFrontend.compile(referenceFile).requireAccepted()
        val ai = aiFrontend.compile(proposal(response(referenceIntent))).requireAccepted()

        assertFailsWith<IllegalArgumentException> {
            org.flowlang.compiler.CanonicalExecutionGraphGate.authorizeCompilation(
                source = yaml.source,
                intentValidation = null,
                proposalReview = null,
                flowValidation = yaml.validation,
                plannerPlan = yaml.executionPlan
            )
        }
        assertFailsWith<IllegalArgumentException> {
            org.flowlang.compiler.CanonicalExecutionGraphGate.authorizeCompilation(
                source = ai.source,
                intentValidation = ai.requireReviewedAiProposalEvidence().validation,
                proposalReview = null,
                flowValidation = ai.validation,
                plannerPlan = ai.executionPlan
            )
        }
    }

    @Test
    fun frontendSpecificInputsCannotBorrowEachOthersProvenance() {
        val response = response(referenceIntent)
        val request = request()
        val aiSource = CompilationSource.fromBytes(
            frontend = CompilationFrontend.REVIEWED_AI_PROPOSAL,
            identity = "proposal:forged",
            bytes = "{}".toByteArray()
        )
        val yamlSource = CompilationSource.fromBytes(
            frontend = CompilationFrontend.INTENT_YAML,
            identity = "intent:forged",
            bytes = "name: forged".toByteArray()
        )

        assertFailsWith<IllegalArgumentException> {
            IntentCompilationInput(aiSource, referenceIntent)
        }
        assertFailsWith<IllegalArgumentException> {
            ReviewedAiProposalCompilationInput(
                source = yamlSource,
                providerId = "fixture",
                request = request,
                response = response
            )
        }
    }

    private fun request(
        userText: String = "Build, test and deploy the reference application.",
        knownSystems: Map<String, String> = emptyMap()
    ): AiIntentRequest = AiIntentRequest(
        userText = userText,
        context = AiIntentContext(knownSystems = knownSystems),
        mode = NormalizationMode.DRAFT
    )

    private fun response(
        intent: IntentDocument,
        entities: Map<String, String> = emptyMap(),
        explanation: List<String> = listOf("fixture proposal"),
        overallConfidence: Double = 0.95,
        scenarioSelection: ScenarioSelectionReport? = null
    ): AiIntentResponse = AiIntentResponse(
        normalizedIntent = intent,
        report = NormalizationReport(
            mode = NormalizationMode.DRAFT,
            classification = IntentClassification(type = "reference", confidence = overallConfidence),
            confidence = ConfidenceScore(
                overallConfidence,
                overallConfidence,
                overallConfidence,
                overallConfidence,
                overallConfidence
            ),
            entities = entities,
            explanation = explanation,
            scenarioSelection = scenarioSelection
        )
    )

    private fun proposal(
        response: AiIntentResponse,
        providerId: String = "fixture-provider",
        request: AiIntentRequest = request(),
        identity: String = "proposal:reference"
    ): ReviewedAiProposal = ReviewedAiProposal(
        providerId = providerId,
        request = request,
        response = response,
        sourceIdentity = identity,
        sourceName = "$identity.json"
    )
}
