package org.flowlang.frontend.outcomes

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.cli.Json
import org.flowlang.compiler.*
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.ai.*
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes

class OperationOutcomeTests {
    private val now = Instant.parse("2026-11-08T02:10:00Z")
    private val address = ResourceIdentity("tenant-a/subscription-test", "network", "temporary")
    private val compiler = FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File("modules")),
        StandardEnvironmentSafetyPolicyNotes.policy(File(".")))
    private fun json(value: Any): JsonNode = Json.mapper.valueToTree(value)
    private fun workspace(block: (File) -> Unit) {
        val root = createTempDirectory("outcomes-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun intent(capability: StandardCapability = StandardCapability.VERIFY, parameter: String = "resource",
                       value: Any = address, extra: String = "", inputs: String = "[]"): String = """
        {"intentVersion":"3.0","kind":"FlowIntentDocument","name":"outcomes","inputs":$inputs,
         "workflows":[{"name":"main","kind":"CUSTOM","body":[
          {"kind":"operation","id":"approval","capability":"APPROVE"},
          {"kind":"operation","id":"work","capability":"${capability.name}","requires":["approval"],
           "params":{"$parameter":${json(value)}$extra}}]}]}
    """.trimIndent()
    private fun compile(root: File, source: String): CompilationUnit {
        val result = IntentYamlFrontend(compiler).compile(File(root, "intent.json").apply { writeText(source) })
        return assertIs<CompilationResult.Accepted>(result, result.toString()).unit
    }
    private inner class Case(val root: File, val unit: CompilationUnit,
                             val capability: StandardCapability = StandardCapability.VERIFY,
                             val parameter: String = "resource",
                             val disposition: ResourceDisposition = ResourceDisposition.SHARED,
                             val identity: ResourceIdentity = address) {
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val node = unit.graph.nodes.filterIsInstance<CanonicalTaskNode>().first { it.semantics.capability?.value == capability.name }
        var contract = OperationOutcomeRequirements(1, unit.graphDigest.value, "run-test", "a".repeat(32),
            now.minusSeconds(600).toString(), "reviewer", listOf(OutcomeResource("resource", identity, disposition,
                if (disposition == ResourceDisposition.CREATED_IN_RUN) RequirementSelector(capability) else null,
                if (disposition == ResourceDisposition.MANAGED) "team-ops" else null)),
            listOf(OperationOutcomeRequirement("outcome", RequirementSelector(capability), "resource", parameter,
                postconditions = mapOf("ready" to json(true)))))
        var grants = OperationObservationTrust(1, listOf(ObservationGrant("observer", b64(key.public.encoded),
            listOf(address.scope), listOf(address.type), ObservationKind.entries.filter { it != ObservationKind.ALLOCATION })))
        val source = "captured observer response".toByteArray()
        fun requirements() = OperationOutcomeReader.requirements(File(root, "requirements.json").apply { writeBytes(Json.mapper.writeValueAsBytes(contract)) })
        fun claim(kind: ObservationKind, at: Instant = now.minusSeconds(10)) = OperationObservation(
            1, kind.name.lowercase(), requirements().source.sha256, unit.graphDigest.value, contract.runId, contract.challenge,
            kind, ObservationStatus.SUCCEEDED, node.id.value, identity, at.toString(), OperationOutcomeReader.sha256(source), source.size,
            facts = if (kind == ObservationKind.STATE) mapOf("ready" to json(true)) else emptyMap(),
            created = if (kind == ObservationKind.CREATION) true else null,
            owner = if (kind == ObservationKind.OWNERSHIP) "team-ops" else null)
        fun signed(claim: OperationObservation): SignedOperationObservation {
            val payload = Json.mapper.writeValueAsBytes(claim)
            val signer = Signature.getInstance("Ed25519"); signer.initSign(key.private)
            signer.update(OperationObservationAuthentication.signingBytes("observer", payload))
            return SignedOperationObservation("observer", b64(payload), b64(signer.sign()), b64(source))
        }
        fun baseline() = listOf(claim(ObservationKind.RESULT), claim(ObservationKind.STATE)) + when (disposition) {
            ResourceDisposition.SHARED -> emptyList()
            ResourceDisposition.MANAGED -> listOf(claim(ObservationKind.OWNERSHIP))
            ResourceDisposition.CREATED_IN_RUN -> listOf(claim(ObservationKind.CREATION))
        }
        fun review(claims: List<OperationObservation> = baseline(), mutate: (List<SignedOperationObservation>) -> List<SignedOperationObservation> = { it },
                   clock: Instant = now): OperationOutcomeReview {
            val observations = File(root, "observations.json").apply { writeBytes(Json.mapper.writeValueAsBytes(OperationObservationBundle(1, mutate(claims.map(::signed))))) }
            val trust = File(root, "trust.json").apply { writeBytes(Json.mapper.writeValueAsBytes(grants)) }
            return OperationOutcomeReviewer.evaluate(unit, requirements(), OperationOutcomeReader.observations(observations), OperationOutcomeReader.trust(trust), clock)
        }
    }
    private fun b64(value: ByteArray) = Base64.getEncoder().encodeToString(value)
    private fun assertCode(review: OperationOutcomeReview, code: String, state: OperationOutcomeState = OperationOutcomeState.UNRESOLVED) {
        assertEquals(state, review.state, review.toString())
        assertTrue(review.results.any { it.code == code } || code in review.diagnostics, review.toString())
    }
    @Test fun successfulCommandIsInsufficientWithoutObservedPostconditions() = workspace { root ->
        val c = Case(root, compile(root, intent()))
        assertEquals(OperationOutcomeState.SATISFIED, c.review().state)
        assertCode(c.review(listOf(c.claim(ObservationKind.RESULT))), "OUTCOME_OBSERVATION_MISSING")
        assertCode(c.review(c.baseline().map { if (it.kind == ObservationKind.STATE) it.copy(facts = mapOf("ready" to json(false))) else it }),
            "OUTCOME_POSTCONDITION_FAILED", OperationOutcomeState.VIOLATED)
        assertCode(c.review(c.baseline().map { if (it.kind == ObservationKind.RESULT) it.copy(status = ObservationStatus.FAILED) else it }),
            "OUTCOME_OPERATION_FAILED", OperationOutcomeState.VIOLATED)
        assertFalse(c.review().executionAuthorized)
        assertEquals("unassessed", c.review().workflowExecutionCorrectness)
    }
    @Test fun infrastructureDataAndCredentialOperationsUseTheSameAssessment() = workspace { root ->
        for ((capability, parameter, extra) in listOf(
            Triple(StandardCapability.PROVISION, "resource", ""),
            Triple(StandardCapability.DATA_SYNC, "destination", ",\"source\":\"isolated-snapshot\""),
            Triple(StandardCapability.SECRET_ROTATE, "subject", ""))) {
            val c = Case(root, compile(root, intent(capability, parameter, extra = extra)), capability, parameter, ResourceDisposition.MANAGED)
            assertEquals(OperationOutcomeState.SATISFIED, c.review().state, capability.name)
            assertCode(c.review(c.baseline().filter { it.kind != ObservationKind.OWNERSHIP }), "OUTCOME_OBSERVATION_MISSING")
            assertCode(c.review(c.baseline().map { if (it.kind == ObservationKind.OWNERSHIP) it.copy(owner = "other-team") else it }),
                "OUTCOME_OWNER_MISMATCH", OperationOutcomeState.VIOLATED)
        }
    }
    @Test fun provisioningDoesNotProveCreationAndSharedResourcesCannotBeChanged() = workspace { root ->
        val unit = compile(root, intent(StandardCapability.PROVISION))
        val created = Case(root, unit, StandardCapability.PROVISION, disposition = ResourceDisposition.CREATED_IN_RUN)
        assertEquals(OperationOutcomeState.SATISFIED, created.review().state)
        assertCode(created.review(created.baseline().filter { it.kind != ObservationKind.CREATION }), "OUTCOME_OBSERVATION_MISSING")
        assertCode(created.review(created.baseline().map { if (it.kind == ObservationKind.CREATION) it.copy(created = false) else it }),
            "OUTCOME_RESOURCE_NOT_CREATED", OperationOutcomeState.VIOLATED)
        val shared = Case(root, unit, StandardCapability.PROVISION)
        assertCode(shared.review(), "OUTCOME_SHARED_RESOURCE_CHANGE", OperationOutcomeState.VIOLATED)
    }
    @Test fun scopedIdentityIsComparedToActualCanonicalParameter() = workspace { root ->
        for (wrong in listOf(address.copy(scope = "tenant-b"), address.copy(type = "database"), address.copy(key = "shared"))) {
            val c = Case(root, compile(root, intent(value = wrong)))
            assertCode(c.review(), "OUTCOME_RESOURCE_MISMATCH", OperationOutcomeState.VIOLATED)
        }
    }
    @Test fun temporalEvidenceCannotBeReusedOrReordered() = workspace { root ->
        val c = Case(root, compile(root, intent()))
        assertCode(c.review(clock = now.plusSeconds(301)), "OUTCOME_OBSERVATION_STALE")
        for (at in listOf(now.plusSeconds(1), now.minusSeconds(601)))
            assertCode(c.review(c.baseline().map { it.copy(observedAt = at.toString()) }), "OUTCOME_OBSERVATION_TIME")
        assertCode(c.review(c.baseline().map { if (it.kind == ObservationKind.STATE) it.copy(observedAt = now.minusSeconds(11).toString()) else it }),
            "OUTCOME_STATE_BEFORE_RESULT")
        val managed = Case(root, compile(root, intent(StandardCapability.PROVISION)), StandardCapability.PROVISION, disposition = ResourceDisposition.MANAGED)
        assertCode(managed.review(managed.baseline().map { if (it.kind == ObservationKind.OWNERSHIP) it.copy(observedAt = now.toString()) else it }),
            "OUTCOME_OWNERSHIP_AFTER_RESULT")
    }
    @Test fun signaturesSourcesAndCallerTrustAreIndependentAdmissionGates() = workspace { root ->
        val c = Case(root, compile(root, intent()))
        assertCode(c.review(mutate = { records -> records.map { it.copy(signature = b64(ByteArray(64))) } }), "OUTCOME_INVALID_SIGNATURE")
        assertCode(c.review(mutate = { records -> records.map { it.copy(source = b64("different response".toByteArray())) } }), "OUTCOME_SOURCE_CHANGED")
        assertCode(c.review(mutate = { records -> records.map { it.copy(keyId = "other") } }), "OUTCOME_UNTRUSTED_OBSERVER")
        c.grants = c.grants.copy(grants = c.grants.grants.map { it.copy(scopes = listOf("other-tenant")) })
        assertCode(c.review(), "OUTCOME_OBSERVER_SCOPE")
    }
    @Test fun authenticButMismatchedOrContradictoryObservationsCannotPass() = workspace { root ->
        val c = Case(root, compile(root, intent()))
        val original = c.baseline()
        for (change in listOf<(OperationObservation) -> OperationObservation>(
            { it.copy(runId = "another-run") }, { it.copy(challenge = "b".repeat(32)) },
            { it.copy(contractSha256 = "b".repeat(64)) }, { it.copy(graphDigest = "b".repeat(64)) }))
            assertCode(c.review(original.map(change)), "OUTCOME_OBSERVATION_CONTEXT")
        assertCode(c.review(original + original[0].copy(id = "duplicate")), "OUTCOME_AMBIGUOUS_OBSERVATIONS")
        assertCode(c.review(original.map { it.copy(nodeId = "invented-node") }), "OUTCOME_UNKNOWN_NODE")
        assertCode(c.review(original.map { it.copy(resource = address.copy(key = "undeclared")) }), "OUTCOME_UNDECLARED_RESOURCE")
        c.contract = c.contract.copy(reviewerId = "changed-reviewer")
        assertCode(c.review(original), "OUTCOME_OBSERVATION_CONTEXT")
    }
    @Test fun publicInputHandleRequiresExactAuthenticatedBinding() = workspace { root ->
        val unit = compile(root, intent(value = "ref:handle", inputs = """[{"name":"handle","type":"text","required":true}]"""))
        val c = Case(root, unit)
        assertCode(c.review(), "OUTCOME_RESOURCE_BINDING_MISSING_OR_AMBIGUOUS")
        val binding = c.claim(ObservationKind.BINDING).copy(nodeId = null, binding = "handle")
        assertEquals(OperationOutcomeState.SATISFIED, c.review(c.baseline() + binding).state)
        assertCode(c.review(c.baseline() + binding.copy(nodeId = c.node.id.value)), "OUTCOME_UNKNOWN_BINDING")
        assertCode(c.review(c.baseline() + binding.copy(observedAt = now.toString())), "OUTCOME_BINDING_AFTER_RESULT")
    }
    @Test fun outputHandleMustUseTheActualValueEdge() = workspace { root ->
        val source = intent(value = "ref:probe").replace("{\"kind\":\"operation\",\"id\":\"work\"",
            """{"kind":"operation","id":"probe","capability":"POLICY_CHECK","params":{"policy":"inventory"}},
            {"kind":"operation","id":"work"""")
        val c = Case(root, compile(root, source))
        val producer = c.unit.graph.nodes.filterIsInstance<CanonicalTaskNode>().single { it.semantics.capability?.value == "POLICY_CHECK" }
        val binding = c.claim(ObservationKind.BINDING).copy(nodeId = producer.id.value, binding = "probe")
        assertEquals(OperationOutcomeState.SATISFIED, c.review(c.baseline() + binding).state)
        assertCode(c.review(c.baseline() + binding.copy(nodeId = null)), "OUTCOME_UNKNOWN_BINDING")
    }
    @Test fun factsAreTypedAndNumbersUseExactDecimalComparison() = workspace { root ->
        val c = Case(root, compile(root, intent()))
        assertCode(c.review(c.baseline().map { if (it.kind == ObservationKind.STATE) it.copy(facts = mapOf("ready" to json("true"))) else it }),
            "OUTCOME_POSTCONDITION_FAILED", OperationOutcomeState.VIOLATED)
        c.contract = c.contract.copy(operations = c.contract.operations.map { it.copy(postconditions = mapOf("size" to Json.mapper.readTree("1.0"))) })
        assertEquals(OperationOutcomeState.SATISFIED, c.review(c.baseline().map { if (it.kind == ObservationKind.STATE) it.copy(facts = mapOf("size" to json(1))) else it }).state)
        assertCode(c.review(), "OUTCOME_FACT_MISSING")
    }
    @Test fun capturedInputsCannotBeChangedAfterAdmission() = workspace { root ->
        val c = Case(root, compile(root, intent()))
        val captured = c.requirements()
        File(root, "requirements.json").writeText("{}")
        assertEquals(c.contract, captured.document())
        val mutable = captured.document().operations[0].postconditions as MutableMap
        mutable["ready"] = json(false)
        assertEquals(json(true), captured.document().operations[0].postconditions["ready"])
        assertCode(OperationOutcomeReviewer.evaluate(c.unit, captured, evaluatedAt = now), "OUTCOME_OBSERVATION_MISSING")
    }
    @Test fun strictReadersRejectVersionCoercionUnknownFieldsAndDuplicateKeys() = workspace { root ->
        val c = Case(root, compile(root, intent()))
        val valid = json(c.contract).toString()
        for (bad in listOf(valid.replace("\"formatVersion\":1", "\"formatVersion\":\"1\""),
            valid.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            valid.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
            valid.replace("\"formatVersion\":1", "\"formatVersion\":1,\"unexpected\":true"))) {
            val file = File(root, "bad.json").apply { writeText(bad) }
            assertFailsWith<OutcomeInputException> { OperationOutcomeReader.requirements(file) }
        }
        c.contract = c.contract.copy(graphDigest = "0".repeat(64))
        assertFailsWith<OutcomeInputException> { c.review() }
    }
    @Test fun nestedResourceMapsAndAmbiguousSelectorsRemainBounded() = workspace { root ->
        val c = Case(root, compile(root, intent(StandardCapability.PROVISION, "stack", mapOf("destination" to address))),
            StandardCapability.PROVISION, "stack", ResourceDisposition.MANAGED)
        c.contract = c.contract.copy(operations = c.contract.operations.map { it.copy(path = listOf("destination")) })
        assertEquals(OperationOutcomeState.SATISFIED, c.review().state)
        c.contract = c.contract.copy(operations = c.contract.operations.map { it.copy(path = listOf("missing")) })
        assertCode(c.review(), "OUTCOME_RESOURCE_PATH_MISSING", OperationOutcomeState.VIOLATED)
        val tree = Json.mapper.readTree(intent()) as ObjectNode
        val body = tree.path("workflows")[0].path("body") as com.fasterxml.jackson.databind.node.ArrayNode
        body.add((body[1].deepCopy<JsonNode>() as ObjectNode).put("id", "other"))
        val ambiguous = Case(root, compile(root, tree.toString()))
        assertCode(OperationOutcomeReviewer.evaluate(ambiguous.unit, ambiguous.requirements(), evaluatedAt = now), "OUTCOME_NONUNIQUE_OPERATION")
    }
    @Test fun undeclaredDestructiveWorkCannotHideBehindASatisfiedRead() = workspace { root ->
        val tree = Json.mapper.readTree(intent()) as ObjectNode
        val body = tree.path("workflows")[0].path("body") as com.fasterxml.jackson.databind.node.ArrayNode
        body.add(Json.mapper.readTree("""{"kind":"operation","id":"remove","capability":"DEPROVISION",
          "params":{"target":{"scope":"tenant-a/subscription-test","type":"network","key":"other"}},"requires":["approval"]}"""))
        val c = Case(root, compile(root, tree.toString()))
        val review = c.review()
        assertEquals(OperationOutcomeState.SATISFIED, review.results.single().state)
        assertCode(review, "OUTCOME_UNCOVERED_DESTRUCTIVE_OPERATION")
    }
    @Test fun joinedTextHandleRequiresTheActualMergeOutputRatherThanAnIncomingBranch() = workspace { root ->
        val tree = Json.mapper.readTree(intent(value = "ref:joined", inputs = """[
            {"name":"choose","type":"boolean","required":true},{"name":"handle","type":"text","required":true}]""")) as ObjectNode
        val body = tree.path("workflows")[0].path("body") as com.fasterxml.jackson.databind.node.ArrayNode
        body.insert(1, Json.mapper.readTree("""{"kind":"branch","id":"choice","when":"ref:choose",
            "then":[{"kind":"value","id":"left","value":"ref:handle"}],
            "otherwise":[{"kind":"value","id":"right","value":"fallback-handle"}]}"""))
        body.insert(2, Json.mapper.readTree("""{"kind":"merge","id":"joined","sources":["ref:left","ref:right"]}"""))
        val c = Case(root, compile(root, tree.toString()))
        val merge = c.unit.graph.valueMerges.single()
        val binding = c.claim(ObservationKind.BINDING).copy(nodeId = merge.targetNodeId.value, binding = "joined")
        assertEquals(OperationOutcomeState.SATISFIED, c.review(c.baseline() + binding).state)
        assertCode(c.review(c.baseline() + binding.copy(nodeId = null, binding = "handle")), "OUTCOME_RESOURCE_BINDING_MISSING_OR_AMBIGUOUS")
    }
    @Test fun creationAndRemovalRequireCoveredCreatorAndCorrectTemporalEvidence() = workspace { root ->
        val tree = Json.mapper.readTree(intent(StandardCapability.PROVISION)) as ObjectNode
        val body = tree.path("workflows")[0].path("body") as com.fasterxml.jackson.databind.node.ArrayNode
        body.add(Json.mapper.readTree("""{"kind":"operation","id":"remove","capability":"DEPROVISION",
            "params":{"target":${json(address)}},"requires":["work"]}"""))
        val c = Case(root, compile(root, tree.toString()), StandardCapability.PROVISION, disposition = ResourceDisposition.CREATED_IN_RUN)
        val removal = c.unit.graph.nodes.filterIsInstance<CanonicalTaskNode>().single { it.semantics.capability?.value == "DEPROVISION" }
        c.contract = c.contract.copy(operations = c.contract.operations + OperationOutcomeRequirement("removed", RequirementSelector(StandardCapability.DEPROVISION),
            "resource", "target", postconditions = mapOf("absent" to json(true))))
        fun claims() = c.baseline() + listOf(
            c.claim(ObservationKind.RESULT, now.minusSeconds(5)).copy(id = "remove-result", nodeId = removal.id.value),
            c.claim(ObservationKind.STATE, now.minusSeconds(4)).copy(id = "remove-state", nodeId = removal.id.value, facts = mapOf("absent" to json(true))))
        assertEquals(OperationOutcomeState.SATISFIED, c.review(claims()).state)
        assertCode(c.review(claims().map { if (it.kind == ObservationKind.CREATION) it.copy(observedAt = now.minusSeconds(3).toString()) else it }),
            "OUTCOME_CREATION_AFTER_REMOVAL")
        c.contract = c.contract.copy(operations = c.contract.operations.drop(1))
        assertCode(c.review(claims()), "OUTCOME_CREATOR_NOT_COVERED")
    }
    @Test fun decimalPostconditionsDoNotRoundIntoSuccess() = workspace { root ->
        val c = Case(root, compile(root, intent()))
        val decimal = com.fasterxml.jackson.databind.node.DecimalNode(java.math.BigDecimal("1.00000000000000000001"))
        c.contract = c.contract.copy(operations = c.contract.operations.map { it.copy(postconditions = mapOf("value" to decimal)) })
        assertCode(c.review(c.baseline().map { if (it.kind == ObservationKind.STATE) it.copy(facts = mapOf("value" to json(1))) else it }),
            "OUTCOME_POSTCONDITION_FAILED", OperationOutcomeState.VIOLATED)
    }
    @Test fun captureLimitsAndTrustKeysAreCheckedBeforeAssessment() = workspace { root ->
        val c = Case(root, compile(root, intent()))
        val badTrust = File(root, "bad-trust.json").apply { writeBytes(Json.mapper.writeValueAsBytes(
            c.grants.copy(grants = c.grants.grants.map { it.copy(publicKey = "invalid") }))) }
        assertFailsWith<OutcomeInputException> { OperationOutcomeReader.trust(badTrust) }
        val many = c.contract.copy(operations = (1..65).map { c.contract.operations[0].copy(id = "outcome-$it") })
        val file = File(root, "too-many.json").apply { writeBytes(Json.mapper.writeValueAsBytes(many)) }
        assertFailsWith<OutcomeInputException> { OperationOutcomeReader.requirements(file) }
        val source = c.requirements()
        val parameters = c.node.semantics.parameters
        val mutation = runCatching { (parameters as MutableMap<String, String>)["resource"] = "false" }
        if (mutation.isSuccess) assertFails { OperationOutcomeReviewer.evaluate(c.unit, source, evaluatedAt = now) }
        else assertIs<UnsupportedOperationException>(mutation.exceptionOrNull())
    }
    @Test fun intentAndProposalAgreeWhileUnannotatedSourceRemainsUnresolved() = workspace { root ->
        val source = intent()
        val structured = compile(root, source)
        val template = Json.mapper.readTree(File("conformance/structured-authoring/maintenance-window.proposal.json")) as ObjectNode
        (template["response"] as ObjectNode).set<JsonNode>("normalizedIntent", Json.mapper.readTree(source))
        val proposalFile = File(root, "proposal.json").apply { writeText(template.toString()) }
        val proposal = ReviewedAiProposalFrontend(compiler).compile(ExternalAiProposalReader.read(proposalFile).proposal).requireAccepted()
        val flow = FlowSourceFrontend(compiler).compile(File(root, "same.flow").apply { writeText("""
            use module "standard" version "1.0"
            flow "outcomes" {
              systems { system "standard" { type: standard } }
              steps {
                approve manual { message: "Review operation" } -> approval
                standard.execute standard {
                  operation: "verify"
                  capability: "verify"
                  resource: {scope: "${address.scope}", type: "${address.type}", key: "${address.key}"}
                } -> work
              }
            }
        """.trimIndent()) }).requireAccepted()
        assertEquals(structured.graphDigest, proposal.graphDigest)
        for (unit in listOf(structured, proposal)) assertEquals(OperationOutcomeState.SATISFIED, Case(root, unit).review().state)
        val oracle = Case(root, structured).contract.copy(graphDigest = flow.graphDigest.value)
        val file = File(root, "source-requirements.json").apply { writeBytes(Json.mapper.writeValueAsBytes(oracle)) }
        assertCode(OperationOutcomeReviewer.evaluate(flow, OperationOutcomeReader.requirements(file), evaluatedAt = now), "OUTCOME_NONUNIQUE_OPERATION")
    }
}
