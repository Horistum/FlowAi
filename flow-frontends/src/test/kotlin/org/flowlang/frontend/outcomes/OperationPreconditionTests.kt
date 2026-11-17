package org.flowlang.frontend.outcomes

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import java.io.File
import java.math.BigDecimal
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.cli.Json
import org.flowlang.compiler.*
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.ai.RequirementSelector
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes

class OperationPreconditionTests {
    private val now = Instant.parse("2026-11-17T01:00:00Z")
    private val address = ResourceIdentity("isolated", "service", "existing")
    private val compiler = FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File("modules")),
        StandardEnvironmentSafetyPolicyNotes.policy(File(".")))
    private fun json(value: Any): JsonNode = Json.mapper.valueToTree(value)
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun workspace(block: (File) -> Unit) {
        val root = createTempDirectory("preconditions-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun compile(root: File, capability: StandardCapability, parameter: String = "resource", extra: String = "",
                        value: Any = address, inputs: String = "[]"): CompilationUnit {
        val sourceParameter = if (capability == StandardCapability.DATA_SYNC) ",\"source\":\"isolated-snapshot\"" else ""
        val source = """{"intentVersion":"3.0","kind":"FlowIntentDocument","name":"preconditions","inputs":$inputs,
          "workflows":[{"name":"main","kind":"CUSTOM","body":[
           {"kind":"operation","id":"approval","capability":"APPROVE"},
           {"kind":"operation","id":"work","capability":"${capability.name}","requires":["approval"],
             "params":{"$parameter":${json(value)}$sourceParameter}}$extra]}]}"""
        val result = IntentYamlFrontend(compiler).compile(File(root, "intent.json").apply { writeText(source) })
        return assertIs<CompilationResult.Accepted>(result, result.toString()).unit
    }
    private inner class Case(val root: File, capability: StandardCapability = StandardCapability.VERIFY,
                             parameter: String = "resource", extra: String = "", customUnit: CompilationUnit? = null) {
        val unit = customUnit ?: compile(root, capability, parameter, extra)
        val node = unit.graph.nodes.filterIsInstance<CanonicalTaskNode>().first { it.semantics.capability?.value == capability.name }
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val source = "observed provider response".toByteArray()
        var contract = OperationPreconditionRequirements(1, unit.graphDigest.value, "assessment", "a".repeat(32),
            now.minusSeconds(60).toString(), now.plusSeconds(120).toString(), "reviewer",
            listOf(PreconditionResource("target", address, "native-generation-1", PreconditionResourceDisposition.MANAGED, "team")),
            listOf(OperationPrecondition("state", RequirementSelector(capability), "target", parameter,
                facts = mapOf("ready" to json(true)), maxAgeSeconds = 30)))
        var grants = PreconditionObservationTrust(1, listOf(PreconditionObservationGrant("observer", b64(key.public.encoded),
            listOf(address.scope), listOf(address.type))))
        fun file(name: String, value: Any) = File(root, name).apply { writeBytes(Json.mapper.writeValueAsBytes(value)) }
        fun requirements() = OperationPreconditionReader.requirements(file("requirements.json", contract))
        fun claim() = PreconditionObservation(1, "snapshot", requirements().source.sha256, unit.graphDigest.value,
            contract.assessmentId, contract.challenge, "state", node.id.value, address, ObservationStatus.SUCCEEDED,
            now.minusSeconds(10).toString(), OperationOutcomeReader.sha256(source), source.size,
            "native-generation-1", "team", mapOf("ready" to json(true)))
        fun signed(claim: PreconditionObservation, payload: ByteArray = Json.mapper.writeValueAsBytes(claim), outcomeDomain: Boolean = false): SignedOperationObservation {
            val signer = Signature.getInstance("Ed25519").apply {
                initSign(key.private)
                update(if (outcomeDomain) OperationObservationAuthentication.signingBytes("observer", payload)
                    else OperationPreconditionAuthentication.signingBytes("observer", payload))
            }
            return SignedOperationObservation("observer", b64(payload), b64(signer.sign()), b64(source))
        }
        fun review(claims: List<PreconditionObservation> = listOf(claim()), at: Instant = now,
                   mutate: (List<SignedOperationObservation>) -> List<SignedOperationObservation> = { it }): OperationPreconditionReview =
            OperationPreconditionReviewer.evaluate(unit, requirements(),
                OperationPreconditionReader.observations(file("bundle.json", PreconditionObservationBundle(1, mutate(claims.map { signed(it) })))),
                OperationPreconditionReader.trust(file("trust.json", grants)), at)
    }
    private fun assertCode(result: OperationPreconditionReview, code: String, state: OperationOutcomeState = OperationOutcomeState.UNRESOLVED) {
        assertEquals(state, result.state, result.toString())
        assertTrue(code in result.diagnostics || result.results.any { it.code == code }, result.toString())
        assertNull(result.validUntil)
        assertFalse(result.executionAuthorized)
    }
    @Test fun freshSnapshotsBindExistingInstancesAcrossDomains() = workspace { root ->
        for ((capability, parameter) in listOf(StandardCapability.PROVISION to "resource",
                StandardCapability.DATA_SYNC to "destination", StandardCapability.SECRET_ROTATE to "subject")) {
            val c = Case(root, capability, parameter)
            val review = c.review()
            assertEquals(OperationOutcomeState.SATISFIED, review.state, review.toString())
            assertEquals(now.plusSeconds(20).toString(), review.validUntil)
            assertEquals(c.unit.graphDigest.value, review.graphDigest)
            assertFalse(review.executionAuthorized)
            assertEquals("not-established", review.snapshotAtomicity)
        }
    }
    @Test fun independentlyObservedOwnershipInstanceAndStateDriftAreViolations() = workspace { root ->
        val c = Case(root)
        for ((changed, code) in listOf(c.claim().copy(owner = "other-team") to "OWNER_CHANGED",
            c.claim().copy(instanceId = "replacement-at-same-address") to "INSTANCE_CHANGED",
            c.claim().copy(resource = address.copy(key = "other")) to "RESOURCE_MISMATCH",
            c.claim().copy(facts = mapOf("ready" to json(false))) to "STATE_CHANGED"))
            assertCode(c.review(listOf(changed)), "PRECONDITION_$code", OperationOutcomeState.VIOLATED)
    }
    @Test fun missingOrUnavailableEvidenceNeverSatisfiesARequirement() = workspace { root ->
        val c = Case(root)
        assertCode(c.review(emptyList()), "PRECONDITION_OBSERVATION_MISSING")
        for ((changed, code) in listOf(c.claim().copy(owner = null) to "OWNER_MISSING",
            c.claim().copy(instanceId = null) to "INSTANCE_MISSING", c.claim().copy(facts = emptyMap()) to "FACT_MISSING",
            c.claim().copy(status = ObservationStatus.FAILED) to "OBSERVATION_UNAVAILABLE",
            c.claim().copy(status = ObservationStatus.UNKNOWN) to "OBSERVATION_UNAVAILABLE"))
            assertCode(c.review(listOf(changed)), "PRECONDITION_$code")
    }
    @Test fun assessmentWindowAndObservationFreshnessAreBothEnforced() = workspace { root ->
        val c = Case(root)
        assertEquals(OperationOutcomeState.SATISFIED, c.review(at = now.plusSeconds(20)).state)
        assertCode(c.review(at = now.plusSeconds(21)), "PRECONDITION_OBSERVATION_STALE")
        assertCode(c.review(at = now.plusSeconds(121)), "PRECONDITION_ASSESSMENT_EXPIRED")
        for (at in listOf(now.minusSeconds(61), now.plusSeconds(1)))
            assertCode(c.review(listOf(c.claim().copy(observedAt = at.toString()))), "PRECONDITION_OBSERVATION_TIME")
        c.contract = c.contract.copy(expiresAt = now.plusSeconds(5).toString())
        assertEquals(now.plusSeconds(5).toString(), c.review().validUntil)
        assertFailsWith<PreconditionInputException> { c.review(at = now.minusSeconds(61)) }
    }
    @Test fun aSharedResourceMustRemainReadOnly() = workspace { root ->
        for (capability in listOf(StandardCapability.VERIFY, StandardCapability.PROVISION)) {
            val c = Case(root, capability)
            c.contract = c.contract.copy(resources = c.contract.resources.map { it.copy(disposition = PreconditionResourceDisposition.SHARED, owner = null) })
            if (capability == StandardCapability.VERIFY) assertEquals(OperationOutcomeState.SATISFIED, c.review().state)
            else assertCode(c.review(), "PRECONDITION_SHARED_RESOURCE_CHANGE", OperationOutcomeState.VIOLATED)
        }
    }
    @Test fun aDeclarationCannotOmitAnotherChangingOperation() = workspace { root ->
        val extra = """,{"kind":"operation","id":"other","capability":"PROVISION","requires":["approval"],"params":{"resource":${json(address.copy(key = "other"))}}}"""
        val c = Case(root, extra = extra)
        assertCode(c.review(), "PRECONDITION_UNCOVERED_CHANGING_OPERATION")
    }
    @Test fun duplicateSemanticBindingsCannotUseTwoConvenientSnapshots() = workspace { root ->
        val c = Case(root)
        c.contract = c.contract.copy(operations = c.contract.operations + c.contract.operations.single().copy(id = "duplicate"))
        assertCode(c.review(listOf(c.claim(), c.claim().copy(id = "second", requirementId = "duplicate"))), "PRECONDITION_DUPLICATE_OPERATION_BINDING")
    }
    @Test fun theWholeSuppliedEvidenceBundleMustAuthenticate() = workspace { root ->
        val c = Case(root)
        val claim = c.claim()
        assertCode(c.review(listOf(claim, claim.copy(id = "duplicate"))), "PRECONDITION_AMBIGUOUS_OBSERVATIONS")
        for (changed in listOf(claim.copy(challenge = "b".repeat(32)), claim.copy(assessmentId = "other"),
                claim.copy(contractSha256 = "b".repeat(64)), claim.copy(graphDigest = "b".repeat(64))))
            assertCode(c.review(listOf(changed)), "PRECONDITION_OBSERVATION_CONTEXT")
        assertCode(c.review(listOf(claim.copy(requirementId = "unknown"))), "PRECONDITION_UNKNOWN_OPERATION")
        assertCode(c.review(listOf(claim.copy(nodeId = "unknown"))), "PRECONDITION_UNKNOWN_OPERATION")
        c.grants = c.grants.copy(grants = c.grants.grants.map { it.copy(scopes = listOf("different")) })
        assertCode(c.review(), "PRECONDITION_OBSERVER_SCOPE")
    }
    @Test fun signatureSourceAndProtocolSubstitutionAreRejected() = workspace { root ->
        val c = Case(root)
        assertCode(c.review(mutate = { it.map { s -> s.copy(signature = b64(ByteArray(64))) } }), "PRECONDITION_INVALID_SIGNATURE")
        assertCode(c.review(mutate = { it.map { s -> s.copy(source = b64("changed".toByteArray())) } }), "PRECONDITION_SOURCE_CHANGED")
        assertCode(c.review(mutate = { listOf(c.signed(c.claim(), outcomeDomain = true)) }), "PRECONDITION_INVALID_SIGNATURE")
        assertCode(c.review(mutate = { it.map { s -> s.copy(keyId = "unknown") } }), "PRECONDITION_UNTRUSTED_OBSERVER")
    }
    @Test fun scalarComparisonRetainsPrecisionAndNeverCoercesBooleans() = workspace { root ->
        val c = Case(root)
        c.contract = c.contract.copy(operations = c.contract.operations.map { it.copy(facts = mapOf("version" to json(BigDecimal("9007199254740993.001")))) })
        assertEquals(OperationOutcomeState.SATISFIED, c.review(listOf(c.claim().copy(facts = mapOf("version" to json(BigDecimal("9007199254740993.0010")))))).state)
        assertCode(c.review(listOf(c.claim().copy(facts = mapOf("version" to json(BigDecimal("9007199254740993.002")))))), "PRECONDITION_STATE_CHANGED", OperationOutcomeState.VIOLATED)
        c.contract = c.contract.copy(operations = c.contract.operations.map { it.copy(facts = mapOf("ready" to json(true))) })
        assertCode(c.review(listOf(c.claim().copy(facts = mapOf("ready" to json(1))))), "PRECONDITION_STATE_CHANGED", OperationOutcomeState.VIOLATED)
    }
    @Test fun capturedRequirementsRemainImmutableAfterCallerChanges() = workspace { root ->
        val c = Case(root)
        val captured = c.requirements()
        File(root, "requirements.json").writeText("{}")
        val mutable = captured.document().operations.single().facts as MutableMap
        mutable["ready"] = json(false)
        assertEquals(json(true), captured.document().operations.single().facts["ready"])
        c.contract = c.contract.copy(graphDigest = "b".repeat(64))
        val result = OperationPreconditionReviewer.evaluate(c.unit, captured, evaluatedAt = now)
        assertEquals(c.unit.graphDigest.value, result.graphDigest)
        assertCode(result, "PRECONDITION_OBSERVATION_MISSING")
        assertFailsWith<PreconditionInputException> { c.review() }
    }
    @Test fun strictReaderRejectsAmbiguityCoercionAndUnboundedDeclarations() = workspace { root ->
        val c = Case(root)
        val original = Json.mapper.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(c.contract)
        for (bad in listOf(original.replace("\"formatVersion\":1", "\"formatVersion\":\"1\""),
            original.replace("\"formatVersion\":1", "\"formatVersion\":true"),
            original.replaceFirst("{", "{\"unknown\":true,"), original.replaceFirst("{", "{\"formatVersion\":1,"),
            original.replace("\"maxAgeSeconds\":30", "\"maxAgeSeconds\":0"),
            original.replace("\"instanceId\":\"native-generation-1\"", "\"instanceId\":null"),
            original.replace("2026-11-17T01:02:00Z", "2026-11-18T01:02:00Z"))) {
            assertNotEquals(original, bad, "The negative fixture must actually change the input")
            val file = File(root, "invalid.json").apply { writeText(bad) }
            assertFailsWith<PreconditionInputException>(bad) { OperationPreconditionReader.requirements(file) }
        }
        c.contract = c.contract.copy(resources = c.contract.resources + c.contract.resources.single().copy(id = "alias"))
        assertFailsWith<PreconditionInputException> { c.requirements() }
    }
    @Test fun signedMalformedPayloadIsUnresolvedWithoutLeakingItsContents() = workspace { root ->
        val c = Case(root)
        val payload = Json.mapper.writeValueAsString(c.claim()).replaceFirst("{", "{\"private-canary\":true,").toByteArray()
        val result = c.review(mutate = { listOf(c.signed(c.claim(), payload)) })
        assertCode(result, "PRECONDITION_INVALID_OBSERVATION")
        assertFalse(result.toString().contains("private-canary"))
    }
    @Test fun dynamicOperandsNeverAcquireAuthorityFromAnUnrelatedSnapshot() = workspace { root ->
        val unit = compile(root, StandardCapability.VERIFY, value = "ref:handle",
            inputs = """[{"name":"handle","type":"text","required":true}]""")
        assertCode(Case(root, customUnit = unit).review(), "PRECONDITION_DYNAMIC_RESOURCE")
    }
    @Test fun anAmbiguousSelectorCannotChooseTheConvenientOperation() = workspace { root ->
        val extra = """,{"kind":"operation","id":"other","capability":"VERIFY","params":{"resource":${json(address.copy(key = "other"))}}}"""
        assertCode(Case(root, extra = extra).review(), "PRECONDITION_NONUNIQUE_OPERATION")
    }
    @Test fun nestedOperandsMustMatchTheDeclaredAddressAndPath() = workspace { root ->
        val unit = compile(root, StandardCapability.PROVISION, "stack", value = mapOf("destination" to address))
        val c = Case(root, StandardCapability.PROVISION, "stack", customUnit = unit)
        c.contract = c.contract.copy(operations = c.contract.operations.map { it.copy(path = listOf("destination")) })
        assertEquals(OperationOutcomeState.SATISFIED, c.review().state)
        c.contract = c.contract.copy(resources = c.contract.resources.map { it.copy(identity = address.copy(key = "other")) })
        assertCode(c.review(), "PRECONDITION_RESOURCE_MISMATCH", OperationOutcomeState.VIOLATED)
        c.contract = c.contract.copy(operations = c.contract.operations.map { it.copy(path = listOf("absent")) })
        assertCode(c.review(), "PRECONDITION_RESOURCE_PATH_MISSING", OperationOutcomeState.VIOLATED)
    }
    @Test fun aKnownButDifferentNodeCannotSatisfyTheSelectedOperation() = workspace { root ->
        val c = Case(root, extra = """,{"kind":"operation","id":"other","capability":"POLICY_CHECK","params":{"policy":"inventory"}}""")
        val other = c.unit.graph.nodes.filterIsInstance<CanonicalTaskNode>().single { it.id != c.node.id }
        assertCode(c.review(listOf(c.claim().copy(nodeId = other.id.value))), "PRECONDITION_OPERATION_MISMATCH")
    }
    @Test fun duplicateSignedFieldsAndAlteredReviewerIdentityInvalidateEvidence() = workspace { root ->
        val c = Case(root)
        val payload = Json.mapper.writeValueAsString(c.claim()).replaceFirst("{", "{\"formatVersion\":1,").toByteArray()
        assertCode(c.review(mutate = { listOf(c.signed(c.claim(), payload)) }), "PRECONDITION_INVALID_OBSERVATION")
        val claim = c.claim()
        c.contract = c.contract.copy(reviewerId = "another-reviewer")
        assertCode(c.review(listOf(claim)), "PRECONDITION_OBSERVATION_CONTEXT")
    }
    @Test fun freshnessClipsToExpiryBeforeInstantArithmeticCanOverflow() = workspace { root ->
        val c = Case(root)
        c.contract = c.contract.copy(issuedAt = Instant.MAX.minusSeconds(20).toString(), expiresAt = Instant.MAX.toString())
        val claim = c.claim().copy(observedAt = Instant.MAX.minusSeconds(10).toString())
        val result = c.review(listOf(claim), at = Instant.MAX)
        assertEquals(OperationOutcomeState.SATISFIED, result.state)
        assertEquals(Instant.MAX.toString(), result.validUntil)
    }
}
