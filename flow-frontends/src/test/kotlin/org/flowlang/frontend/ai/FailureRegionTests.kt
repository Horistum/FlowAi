package org.flowlang.frontend.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.cli.Json
import org.flowlang.compiler.*
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.modules.ModuleRegistry
import org.flowlang.intent.IntentAttempt
import org.flowlang.intent.IntentStatement
import org.flowlang.lowering.IntentLoweringAuthority
import org.flowlang.planner.TryPlanNode
import org.flowlang.planner.RetryGroupNode

class FailureRegionTests {
    private val selectedLiteral = """{"kind":"literal-parameter","selector":{"capability":"VERIFY"},"parameter":"resource","expected":"test-worker","evaluationScope":"SELECTED_OPERATIONS"}"""
    private val selectedProducer = """{"kind":"value-from","producer":{"capability":"BACKUP"},"consumer":{"capability":"RESTORE"},"parameter":"recoveryPoint","evaluationScope":"SELECTED_OPERATIONS"}"""
    private val corpus = File("conformance/failure-regions")
    private val compiler = FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File("modules")))
    private val cases = listOf("infrastructure-readiness", "isolated-data-recovery", "credential-maintenance")
    private fun tree(id: String = cases.first(), suffix: String = "proposal") = Json.mapper.readTree(File(corpus, "$id.$suffix.json")) as ObjectNode
    private fun obj(n: JsonNode) = n as ObjectNode
    private fun array(n: JsonNode) = n as ArrayNode
    private fun intent(p: ObjectNode) = obj(p.path("response").path("normalizedIntent"))
    private fun body(p: ObjectNode) = array(intent(p).path("workflows")[0].path("body"))
    private fun attempt(p: ObjectNode) = obj(body(p).last())
    private fun retry(p: ObjectNode) = obj(attempt(p).path("body")[1])
    private fun workspace(action: (File) -> Unit) {
        val root = createTempDirectory("failure-regions-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
    private fun compile(root: File, p: ObjectNode): CompilationResult {
        val source = File(root, "proposal.json").apply { writeText(p.toString()) }
        return ReviewedAiProposalFrontend(compiler).compile(ExternalAiProposalReader.read(source).proposal)
    }
    private fun review(root: File, p: ObjectNode = tree(), r: ObjectNode = tree(suffix = "requirements")): RequestFidelityReview {
        val unit = assertIs<CompilationResult.Accepted>(compile(root, p), p.toString()).unit
        val source = File(root, "requirements.json").apply { writeText(r.toString()) }
        return RequestFidelityEvaluator.evaluate(RequestRequirementsReader.read(source), unit)
    }
    private fun parameterRequirements(p: ObjectNode, check: String, version: Int = 5): ObjectNode {
        val r = tree(suffix = "requirements").put("formatVersion", version)
        r.set<JsonNode>("request", p["request"].deepCopy())
        r.putArray("requirements").addObject().put("id", "parameter-source")
            .put("sourceQuote", p["request"]["userText"].asText()).set<JsonNode>("check", Json.mapper.readTree(check))
        return r
    }
    @Test fun selectedParameterChecksCoverRetryBodiesWithoutChangingLegacyExecutionMeaning() = workspace { root ->
        val p = tree()
        assertEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, review(root, p, parameterRequirements(p, selectedLiteral)).state)
        for (version in listOf(4, 5)) {
            val legacy = parameterRequirements(p, selectedLiteral, version)
            obj(legacy["requirements"][0]["check"]).remove("evaluationScope")
            assertEquals(RequestFidelityState.REVIEW_REQUIRED, review(root, p, legacy).state)
        }
    }
    @Test fun selectedParameterChecksRejectWrongMissingAndExtraContradictoryNodes() = workspace { root ->
        val wrong = tree().also { obj(retry(it)["body"][0]["params"]).put("resource", "unrelated") }
        val extra = tree().also {
            body(it).addObject().put("kind", "operation").put("id", "extra").put("capability", "VERIFY")
                .putObject("params").put("resource", "unrelated")
        }
        for (p in listOf(wrong, extra)) {
            assertEquals(RequestFidelityState.VIOLATED, review(root, p, parameterRequirements(p, selectedLiteral)).state)
        }
        val absent = selectedLiteral.replace("VERIFY", "RESTORE")
        assertEquals(RequestFidelityState.VIOLATED, review(root, tree(), parameterRequirements(tree(), absent)).state)
    }
    @Test fun selectedParameterScopeDoesNotCertifyFailurePlacement() = workspace { root ->
        val p = tree(); val region = attempt(p).deepCopy()
        body(p).remove(body(p).size() - 1)
        body(p).addAll(array(region["body"])); body(p).addAll(array(region["onFailure"]))
        assertEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, review(root, p, parameterRequirements(p, selectedLiteral)).state)
        assertEquals(RequestFidelityState.VIOLATED, review(root, p).state)
    }
    @Test fun selectedProducerChecksUseResolvedEdgesIntoTheRecoveryHandler() = workspace { root ->
        val p = tree("isolated-data-recovery")
        assertEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, review(root, p, parameterRequirements(p, selectedProducer)).state)
        val legacy = parameterRequirements(p, selectedProducer)
        obj(legacy["requirements"][0]["check"]).remove("evaluationScope")
        assertEquals(RequestFidelityState.REVIEW_REQUIRED, review(root, p, legacy).state)
        obj(attempt(p)["onFailure"][0]["params"]).put("recoveryPoint", "unrelated-baseline")
        assertEquals(RequestFidelityState.VIOLATED, review(root, p, parameterRequirements(p, selectedProducer)).state)
    }
    @Test fun selectedProducerAmbiguityCannotBeHiddenByOneMatchingEdge() = workspace { root ->
        val p = tree("isolated-data-recovery")
        body(p).insert(0, obj(body(p)[0]).deepCopy().put("id", "another-backup").also {
            it.putArray("produces").add("another-baseline")
        })
        assertEquals(RequestFidelityState.REVIEW_REQUIRED, review(root, p, parameterRequirements(p, selectedProducer)).state)
    }
    @Test fun oldVersionsRejectExplicitScopeIncludingTheLegacyDefault() = workspace { root ->
        for (version in 1..4) for (scope in listOf("EXECUTION", "SELECTED_OPERATIONS")) {
            val r = parameterRequirements(tree(), selectedLiteral.replace("SELECTED_OPERATIONS", scope), version)
            val file = File(root, "requirements.json").apply { writeText(r.toString()) }
            assertFailsWith<RequestRequirementsException> { RequestRequirementsReader.read(file) }
        }
    }
    @Test fun unknownNullAndPathNestedSelectedScopesAreRejected() = workspace { root ->
        val checks = listOf(selectedLiteral.replace("SELECTED_OPERATIONS", "anything"),
            selectedLiteral.replace("\"SELECTED_OPERATIONS\"", "null"),
            """{"kind":"on-path","scope":[{"predicate":"allowed"}],"check":$selectedLiteral}""")
        for (check in checks) {
            val file = File(root, "requirements.json").apply { writeText(parameterRequirements(tree(), check).toString()) }
            assertFailsWith<RequestRequirementsException> { RequestRequirementsReader.read(file) }
        }
    }
    @Test fun threeDomainsPreserveCanonicalRegionsThroughBothFrontends() = workspace { root ->
        for (id in cases) {
            val unit = assertIs<CompilationResult.Accepted>(compile(root, tree(id)), id).unit
            val direct = IntentYamlFrontend(compiler).compile(File(corpus, "$id.intent.json")).requireAccepted()
            assertEquals(direct.graphDigest, unit.graphDigest)
            assertEquals(1, unit.graph.nodes.filterIsInstance<CanonicalTryNode>().size)
            assertEquals(1, unit.graph.nodes.filterIsInstance<CanonicalRetryNode>().size)
            assertEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, review(root, tree(id), tree(id, "requirements")).state)
        }
    }
    @Test fun movingFailureWorkToNormalPathCannotSatisfyRegion() = workspace { root ->
        val p = tree(); val region = attempt(p).deepCopy()
        body(p).remove(body(p).size() - 1)
        body(p).addAll(array(region["body"])); body(p).addAll(array(region["onFailure"]))
        assertEquals(RequestFidelityState.VIOLATED, review(root, p).state)
    }
    @Test fun changingRecoveryDispositionViolatesIndependentRequirement() = workspace { root ->
        for (id in cases) {
            val p = tree(id); val region = attempt(p)
            region.put("disposition", if (region["disposition"].asText() == "recover") "propagate" else "recover")
            assertEquals(RequestFidelityState.VIOLATED, review(root, p, tree(id, "requirements")).state)
        }
    }
    @Test fun missingExtraWrongAndReorderedOperationsDoNotPass() = workspace { root ->
        val missing = tree().also { array(attempt(it)["body"]).remove(0) }
        val extra = tree().also { array(attempt(it)["onFailure"]).add(obj(attempt(it)["onFailure"][0]).deepCopy().put("id", "extra").also { n -> obj(n["params"]).put("subject", "extra") }) }
        val wrong = tree().also { obj(attempt(it)["onFailure"][0]["params"]).put("subject", "another-resource") }
        val reordered = tree().also { val b = array(attempt(it)["body"]); val first = b.remove(0); b.add(first) }
        for (p in listOf(missing, extra, wrong, reordered)) assertNotEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, review(root, p).state)
    }
    @Test fun retryBoundsDelayAndRemovedBoundaryCannotPass() = workspace { root ->
        val count = tree().also { retry(it).put("maxAttempts", 4) }
        val delay = tree().also { retry(it).put("delaySeconds", 6) }
        val flat = tree().also { array(attempt(it)["body"]).set(1, retry(it)["body"][0].deepCopy<JsonNode>()) }
        for (p in listOf(count, delay, flat)) assertEquals(RequestFidelityState.VIOLATED, review(root, p).state)
    }
    @Test fun changingAndUnknownEffectsCannotBeRetried() = workspace { root ->
        for (capability in listOf("PROVISION", "SECRET_ROTATE", "RUN_COMMAND", "APPROVE")) {
            val p = tree(); val operation = obj(retry(p)["body"][0]); operation.put("capability", capability)
            assertIs<CompilationResult.Rejected>(compile(root, p), capability)
        }
    }
    @Test fun nestedRetryAndFailureSuppressionInsideRetryAreRejected() = workspace { root ->
        val nested = tree().also { val r = retry(it); r.set<ArrayNode>("body", Json.mapper.createArrayNode().add(r.deepCopy().put("id", "nested"))) }
        val attempt = tree().also {
            val r = retry(it)
            val protected = r["body"][0].deepCopy<JsonNode>()
            val handler = obj(protected).deepCopy().put("id", "fallback")
            val region = Json.mapper.createObjectNode().put("kind", "attempt").put("id", "hidden").put("disposition", "recover")
            region.set<ArrayNode>("body", Json.mapper.createArrayNode().add(protected))
            region.set<ArrayNode>("onFailure", Json.mapper.createArrayNode().add(handler))
            r.set<ArrayNode>("body", Json.mapper.createArrayNode().add(region))
        }
        for (p in listOf(nested, attempt)) assertIs<CompilationResult.Rejected>(compile(root, p))
    }
    @Test fun partialBodyValuesAreUnavailableInHandler() = workspace { root ->
        val p = tree(); obj(attempt(p)["onFailure"][0]["params"]).put("body", "ref:create")
        assertIs<CompilationResult.Rejected>(compile(root, p))
    }
    @Test fun recoveredPathCannotLeakSuccessOnlyValue() = workspace { root ->
        val p = tree(); attempt(p).put("disposition", "recover")
        body(p).addObject().put("kind", "operation").put("id", "after").put("capability", "VERIFY").putObject("params").put("criteria", "ref:create")
        assertIs<CompilationResult.Rejected>(compile(root, p))
    }
    @Test fun propagatedFailureLeavesSuccessValueAvailableOnlyOnNormalContinuation() = workspace { root ->
        val p = tree()
        body(p).addObject().put("kind", "operation").put("id", "after").put("capability", "VERIFY").putObject("params").put("criteria", "ref:create")
        assertIs<CompilationResult.Accepted>(compile(root, p))
    }
    @Test fun forwardAndSiblingOrderingDependenciesAreRejected() = workspace { root ->
        val p = tree(); obj(attempt(p)["onFailure"][0]).putArray("requires").add("create")
        assertIs<CompilationResult.Rejected>(compile(root, p))
    }
    @Test fun ambiguityAndConditionalRegionRequireReview() = workspace { root ->
        val ambiguous = tree().also { body(it).insert(0, obj(attempt(it)["onFailure"][0]).deepCopy().put("id", "duplicate")) }
        assertEquals(RequestFidelityState.REVIEW_REQUIRED, review(root, ambiguous).state)
        val conditional = tree(); val region = attempt(conditional).deepCopy(); body(conditional).remove(body(conditional).size() - 1)
        body(conditional).addObject().put("kind", "branch").put("id", "hiddenGuard").put("when", true).putArray("then").add(region)
        assertEquals(RequestFidelityState.REVIEW_REQUIRED, review(root, conditional).state)
    }
    @Test fun malformedVersionsBoundsAndFieldsAreRejected() = workspace { root ->
        val mutations = listOf<(ObjectNode) -> Unit>(
            { intent(it).put("intentVersion", "3.0") }, { retry(it).put("maxAttempts", 0) },
            { retry(it).put("maxAttempts", 101) }, { retry(it).put("maxAttempts", "3") },
            { retry(it).put("delaySeconds", 3601) }, { retry(it).put("delaySeconds", 0.5) },
            { retry(it).put("backoff", "exponential") }, { attempt(it).remove("disposition") },
            { attempt(it).put("disposition", "ignore") }, { array(attempt(it)["onFailure"]).removeAll() },
            { obj(attempt(it)["onFailure"][0]).put("id", "create") })
        for (mutation in mutations) {
            val result = runCatching { compile(root, tree().also(mutation)) }
            if (result.isFailure) assertIs<ProposalInputException>(result.exceptionOrNull())
            else assertIs<CompilationResult.Rejected>(result.getOrThrow())
        }
    }
    @Test fun oldRequirementsVersionsCannotAdmitNewChecks() = workspace { root ->
        for (version in 1..3) {
            val source = File(root, "requirements.json").apply { writeText(tree(suffix = "requirements").put("formatVersion", version).toString()) }
            assertFailsWith<RequestRequirementsException> { RequestRequirementsReader.read(source) }
        }
    }
    @Test fun originalErrorCannotBeReplacedByAHandlerBinding() = workspace { root ->
        val renamed = tree().also { obj(attempt(it)["onFailure"][0]).put("id", "error") }
        val output = tree().also { obj(attempt(it)["onFailure"][0]).putArray("produces").add("error") }
        val local = tree().also { array(attempt(it)["onFailure"]).addObject().put("kind", "value").put("id", "error").put("value", "masked") }
        for (p in listOf(renamed, output, local)) assertIs<CompilationResult.Rejected>(compile(root, p))
    }
    @Test fun sourcePreservationReceiptDetectsChangedRetryAndFailureTail() = workspace { root ->
        val plan = compile(root, tree()).requireAccepted().executionPlan
        val attempt = plan.nodes.last() as TryPlanNode
        val retry = attempt.body.last() as RetryGroupNode
        val altered = listOf(attempt.copy(errorHandler = attempt.errorHandler.dropLast(1)),
            attempt.copy(body = attempt.body.dropLast(1) + retry.copy(max = retry.max + 1)))
        for (region in altered) {
            val changed = plan.copy(nodes = plan.nodes.dropLast(1) + region)
            assertFailsWith<IllegalArgumentException> { IntentLoweringAuthority.report(changed) }
        }
    }
    @Test fun reviewedFailureBodiesAreDetachedAndTheirIntegrityIsChecked() = workspace { root ->
        val file = File(root, "proposal.json").apply { writeText(tree().toString()) }
        val source = ExternalAiProposalReader.read(file).proposal
        val intent = source.response.normalizedIntent
        val workflow = intent.workflows.single()
        val region = workflow.body!!.last() as IntentAttempt
        val mutable = region.onFailure.toMutableList()
        val proposal = source.copy(response = source.response.copy(normalizedIntent = intent.copy(workflows = listOf(
            workflow.copy(body = workflow.body!!.dropLast(1) + region.copy(onFailure = mutable))))))
        val unit = ReviewedAiProposalFrontend(compiler).compile(proposal).requireAccepted()
        mutable.clear()
        val requirements = RequestRequirementsReader.read(File(corpus, "infrastructure-readiness.requirements.json"))
        assertEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, RequestFidelityEvaluator.evaluate(requirements, unit).state)
        val saved = unit.requireReviewedAiProposalEvidence().response.normalizedIntent.workflows.single().body!!.last() as IntentAttempt
        (saved.onFailure as MutableList<IntentStatement>).clear()
        assertFailsWith<IllegalArgumentException> { RequestFidelityEvaluator.evaluate(requirements, unit) }
    }
}
