package org.flowlang.frontend.outcomes

import com.fasterxml.jackson.databind.JsonNode
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
import org.flowlang.frontend.ai.RequirementSelector
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes

class RuntimeResourceIdentityTests {
    private val now = Instant.parse("2026-11-11T08:00:00Z")
    private val compiler = FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File("modules")),
        StandardEnvironmentSafetyPolicyNotes.policy(File(".")))
    private fun json(value: Any): JsonNode = Json.mapper.valueToTree(value)
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun at(second: Long) = now.minusSeconds(100).plusSeconds(second).toString()
    private fun case(type: String = "database", block: Case.() -> Unit) {
        val root = createTempDirectory("runtime-identity-").toFile()
        try { Case(root, type).block() } finally { root.deleteRecursively() }
    }
    private inner class Case(val root: File, type: String) {
        val address = ResourceIdentity("isolated", type, "allocated-725")
        private fun op(id: String, capability: String, resource: Any, criteria: String? = null): Map<String, Any> =
            mapOf("kind" to "operation", "id" to id, "capability" to capability, "params" to
                (mapOf("resource" to resource) + if (criteria == null) emptyMap() else mapOf("criteria" to criteria)))
        private val source = json(mapOf("intentVersion" to "3.1", "kind" to "FlowIntentDocument", "name" to "allocated",
            "workflows" to listOf(mapOf("name" to "main", "kind" to "CUSTOM", "body" to listOf(
                mapOf("kind" to "operation", "id" to "approval", "capability" to "APPROVE"),
                op("make", "PROVISION", mapOf("scope" to address.scope, "type" to type)) + mapOf("requires" to listOf("approval")),
                mapOf("kind" to "retry", "id" to "retry", "maxAttempts" to 2, "delaySeconds" to 0, "body" to listOf(
                    op("resolve", "VERIFY", "ref:make.handle", "resolve"), op("consume", "VERIFY", "ref:resolve.handle", "consume"))),
                op("after", "VERIFY", "ref:resolve.handle", "after")))))).toString()
        val unit = assertIs<CompilationResult.Accepted>(IntentYamlFrontend(compiler).compile(File(root, "source.json").apply { writeText(source) })).unit
        private val tasks = unit.graph.nodes.filterIsInstance<CanonicalTaskNode>()
        fun node(name: String) = tasks.single { name in it.semantics.outputs }
        private fun selector(task: CanonicalTaskNode) = RequirementSelector(StandardCapability.valueOf(task.semantics.capability!!.value),
            parameters = task.semantics.parameters["criteria"]?.let { mapOf("criteria" to Json.mapper.readTree(it).textValue()) }.orEmpty())
        var contract = OperationOutcomeRequirements(3, unit.graphDigest.value, "allocated-run", "a".repeat(32), at(0), "reviewer",
            listOf(OutcomeResource("target", disposition = ResourceDisposition.CREATED_IN_RUN, creator = selector(node("make")), owner = "tenant",
                allocation = ResourceAllocation(address.scope, type, "make", listOf("handle"), "allocated-", 100))),
            tasks.map { task -> OperationOutcomeRequirement(task.semantics.outputs.single(), selector(task), "target", "resource",
                postconditions = if (task == node("after")) mapOf("ready" to json(true)) else emptyMap(),
                evidence = AttemptOutcomePolicy(ExpectedOperationStatus.COMPLETED)) }, RunEvidenceRequirement("main", ExpectedOperationStatus.COMPLETED))
        private val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        private val capture = "synthetic signed allocation assertion; no external work".toByteArray()
        var trust = OperationObservationTrust(3, listOf(ObservationGrant("observer", b64(key.public.encoded), listOf(address.scope, "other"),
            listOf(address.type, "other"), ObservationKind.entries, listOf("main"))))
        var signingVersion = 3
        fun requirements() = OperationOutcomeReader.requirements(File(root, "requirements.json").apply { writeBytes(Json.mapper.writeValueAsBytes(contract)) })
        private fun event(id: String, name: String, second: Long, attempt: Int = 1, failed: Boolean = false) = ObservedInvocation(id,
            if (name == "approval") unit.graph.nodes.filterIsInstance<CanonicalApprovalNode>().single().id.value else node(name).id.value,
            attempt, at(second), at(second + 1), if (failed) ObservationStatus.FAILED else ObservationStatus.SUCCEEDED,
            if (failed) "error-$id" else null)
        fun run() = OperationRunObservation(3, "trace", requirements().source.sha256, unit.graphDigest.value, contract.runId, contract.challenge,
            "main", at(0), at(20), true, ObservationStatus.SUCCEEDED, sourceSha256 = OperationOutcomeReader.sha256(capture),
            sourceSizeBytes = capture.size, invocations = listOf(event("approval", "approval", 1), event("make-1", "make", 3),
                event("resolve-1", "resolve", 5), event("consume-1", "consume", 7, failed = true),
                event("resolve-2", "resolve", 9, 2), event("consume-2", "consume", 11, 2), event("after-1", "after", 13)))
        fun claims(run: OperationRunObservation = run()): List<OperationObservation> = run.invocations.filter { it.id != "approval" }.flatMap { event ->
            val result = OperationObservation(3, "result-${event.id}", requirements().source.sha256, unit.graphDigest.value, contract.runId,
                contract.challenge, ObservationKind.RESULT, event.status, event.nodeId, address, event.completedAt,
                OperationOutcomeReader.sha256(capture), capture.size, invocationId = event.id, resourceId = "target", instanceId = "provider-instance-17")
            buildList {
                add(result)
                if (event.nodeId in listOf(node("make").id.value, node("resolve").id.value) && event.status == ObservationStatus.SUCCEEDED)
                    add(result.copy(id = "binding-${event.id}", kind = ObservationKind.BINDING,
                        binding = if (event.id == "make-1") "make" else "resolve", path = listOf("handle")))
                if (event.id == "make-1") {
                    add(result.copy(id = "allocation", kind = ObservationKind.ALLOCATION, binding = "make", path = listOf("handle")))
                    add(result.copy(id = "creation", kind = ObservationKind.CREATION, created = true))
                    add(result.copy(id = "ownership", kind = ObservationKind.OWNERSHIP, owner = "tenant"))
                }
                if (event.id == "after-1") add(result.copy(id = "state", kind = ObservationKind.STATE, observedAt = at(21), facts = mapOf("ready" to json(true))))
            }
        }
        fun sign(value: Any, run: Boolean): SignedOperationObservation {
            val payload = Json.mapper.writeValueAsBytes(value)
            val signer = Signature.getInstance("Ed25519").apply { initSign(key.private)
                update(if (run) OperationObservationAuthentication.runSigningBytes("observer", payload, signingVersion)
                    else OperationObservationAuthentication.signingBytes("observer", payload, signingVersion)) }
            return SignedOperationObservation("observer", b64(payload), b64(signer.sign()), b64(capture))
        }
        fun review(run: OperationRunObservation = run(), claims: List<OperationObservation> = claims(run)): OperationOutcomeReview {
            val bundle = OperationObservationBundle(3, claims.map { sign(it, false) }, sign(run, true))
            return OperationOutcomeReviewer.evaluate(unit, requirements(), OperationOutcomeReader.observations(File(root, "bundle.json").apply {
                writeBytes(Json.mapper.writeValueAsBytes(bundle)) }), OperationOutcomeReader.trust(File(root, "trust.json").apply {
                writeBytes(Json.mapper.writeValueAsBytes(trust)) }), now)
        }
        fun check(code: String, claims: List<OperationObservation> = claims(), state: OperationOutcomeState = OperationOutcomeState.UNRESOLVED,
                  run: OperationRunObservation = run()) {
            val result = review(run, claims)
            assertEquals(state, result.state, result.toString())
            assertTrue(code in result.diagnostics || result.results.any { it.code == code }, result.toString())
        }
    }
    @Test fun allocationPreservesFrozenRequirementsAndEveryConsumerAttempt() {
        for (type in listOf("service", "database")) case(type) {
            val before = requirements().source.sha256
            val result = review()
            assertEquals(OperationOutcomeState.SATISFIED, result.state, result.toString())
            assertEquals(before, result.requirementsSource.sha256)
            assertFalse(Json.mapper.writeValueAsString(contract).contains(address.key))
            assertTrue(result.results.all { it.resource == address && it.instanceId == "provider-instance-17" })
            assertEquals(listOf("resolve-1", "resolve-2"), result.results.single { it.id == "consume" }.resourceLineage.map { it.producerInvocationId })
            assertFalse(result.executionAuthorized)
        }
    }
    @Test fun absentAllocationOrInstanceCannotBeInferredFromSuccessfulResults() = case {
        check("OUTCOME_ALLOCATION_MISSING", claims().filter { it.kind != ObservationKind.ALLOCATION })
        for (id in listOf("allocation", "state", "binding-resolve-1"))
            check("OUTCOME_INSTANCE_MISSING", claims().map { if (it.id == id) it.copy(instanceId = null) else it })
    }
    @Test fun independentCreationOwnershipAndBindingAreAllRequired() = case {
        for (kind in listOf(ObservationKind.CREATION, ObservationKind.OWNERSHIP))
            check("OUTCOME_OBSERVATION_MISSING", claims().filter { it.kind != kind })
        check("OUTCOME_RESOURCE_BINDING_MISSING_OR_AMBIGUOUS", claims().filter { it.id != "binding-resolve-1" })
        check("OUTCOME_RESOURCE_NOT_CREATED", claims().map { if (it.kind == ObservationKind.CREATION) it.copy(created = false) else it }, OperationOutcomeState.VIOLATED)
        check("OUTCOME_OWNER_MISMATCH", claims().map { if (it.kind == ObservationKind.OWNERSHIP) it.copy(owner = "other") else it }, OperationOutcomeState.VIOLATED)
    }
    @Test fun conflictingLateAllocationCannotSelectAConvenientCandidate() = case {
        val original = claims().single { it.kind == ObservationKind.ALLOCATION }
        for (conflict in listOf(original.copy(id = "conflict", resource = address.copy(key = "allocated-other")),
            original.copy(id = "conflict", instanceId = "replacement")))
            check("OUTCOME_ALLOCATION_CONFLICT", claims() + conflict, OperationOutcomeState.VIOLATED)
        assertEquals(OperationOutcomeState.SATISFIED, review(claims = claims() + original.copy(id = "same-assertion")).state)
    }
    @Test fun sameAddressDifferentGenerationIsNotTheAllocatedInstance() = case {
        for (id in listOf("state", "binding-resolve-1", "creation", "result-consume-1"))
            check("OUTCOME_RESOURCE_INSTANCE_MISMATCH", claims().map { if (it.id == id) it.copy(instanceId = "replacement") else it }, OperationOutcomeState.VIOLATED)
    }
    @Test fun authenticatedWrongScopeTypeAndKeyAreViolations() = case {
        for (address in listOf(address.copy(scope = "other"), address.copy(type = "other")))
            check("OUTCOME_ALLOCATION_SCOPE", claims().map { if (it.kind == ObservationKind.ALLOCATION) it.copy(resource = address) else it }, OperationOutcomeState.VIOLATED)
        check("OUTCOME_ALLOCATION_KEY_CONSTRAINT", claims().map { it.copy(resource = address.copy(key = "forbidden")) }, OperationOutcomeState.VIOLATED)
        contract = contract.copy(resources = contract.resources.map { it.copy(allocation = it.allocation!!.copy(maxKeyLength = 10)) })
        check("OUTCOME_ALLOCATION_KEY_CONSTRAINT", state = OperationOutcomeState.VIOLATED)
    }
    @Test fun wrongAllocationProducerOutputAndPathCannotBeRebound() = case {
        val original = claims().single { it.kind == ObservationKind.ALLOCATION }
        for (changed in listOf(original.copy(binding = "other"), original.copy(path = listOf("other")),
            original.copy(nodeId = node("resolve").id.value, invocationId = "resolve-1", observedAt = at(6))))
            check("OUTCOME_ALLOCATION_PRODUCER_SLOT", claims().map { if (it.id == "allocation") changed else it }, OperationOutcomeState.VIOLATED)
    }
    @Test fun allocationNeedsItsOwnGrantAndVersionedSignature() = case {
        trust = trust.copy(grants = trust.grants.map { it.copy(kinds = it.kinds - ObservationKind.ALLOCATION) })
        check("OUTCOME_OBSERVER_SCOPE")
        trust = trust.copy(grants = trust.grants.map { it.copy(kinds = ObservationKind.entries) })
        signingVersion = 2
        check("OUTCOME_INVALID_SIGNATURE")
    }
    @Test fun evidenceMustBindOriginalRequirementsRunAndChallenge() = case {
        val original = claims().single { it.kind == ObservationKind.ALLOCATION }
        for (changed in listOf(original.copy(contractSha256 = "b".repeat(64)), original.copy(runId = "foreign"), original.copy(challenge = "b".repeat(32))))
            check("OUTCOME_OBSERVATION_CONTEXT", claims().map { if (it.id == "allocation") changed else it })
    }
    @Test fun stateAndAllocationTimesRemainIndependent() = case {
        check("OUTCOME_STATE_BEFORE_RUN_COMPLETION", claims().map { if (it.id == "state") it.copy(observedAt = at(14)) else it })
        check("OUTCOME_ALLOCATION_COMPLETION", claims().map { if (it.id == "allocation") it.copy(observedAt = at(21)) else it })
        check("OUTCOME_ALLOCATION_OWNERSHIP_TIME", claims().map { if (it.id == "ownership") it.copy(observedAt = at(3)) else it })
    }
    @Test fun partialFailedCreationNeverPublishesAResolvedAllocation() = case {
        val original = run()
        val failed = original.invocations[1].copy(status = ObservationStatus.FAILED, errorId = "partial")
        val run = original.copy(invocations = listOf(original.invocations[0], failed), status = ObservationStatus.FAILED, errorId = "partial")
        check("OUTCOME_ALLOCATION_CREATOR_FAILED", claims(run).map { if (it.kind != ObservationKind.RESULT) it.copy(status = ObservationStatus.SUCCEEDED) else it }, run = run)
    }
    @Test fun v3RejectsPredeclaredIdentityAndLegacyRejectsAllocationFieldsEvenNull() = case {
        contract = contract.copy(resources = contract.resources.map { it.copy(identity = address) })
        assertFailsWith<OutcomeInputException> { requirements() }
        contract = contract.copy(formatVersion = 2)
        assertFailsWith<OutcomeInputException> { requirements() }
        val file = File(root, "legacy.json").apply { writeText(Json.mapper.writeValueAsString(contract).replace(Regex("\\\"allocation\\\":\\{[^}]*}"), "\"allocation\":null")) }
        assertFailsWith<OutcomeInputException> { OperationOutcomeReader.requirements(file) }
    }
    @Test fun distinctLogicalResourcesCannotAliasOneCanonicalAllocationSlot() = case {
        val second = contract.resources.single().copy(id = "second", creator = contract.resources.single().creator!!.copy(workflow = "main"))
        contract = contract.copy(resources = contract.resources + second,
            operations = contract.operations + contract.operations.single { it.id == "make" }.copy(id = "second-make", resource = "second", selector = second.creator!!))
        val original = claims().single { it.kind == ObservationKind.ALLOCATION }
        check("OUTCOME_ALLOCATION_SLOT_ALIAS", claims() + original.copy(id = "second-allocation", resourceId = "second",
            resource = address.copy(key = "allocated-other"), instanceId = "different-instance"), OperationOutcomeState.VIOLATED)
    }
    @Test fun allocationScopeMustMatchTheActualStructuredCreatorRequest() = case {
        contract = contract.copy(resources = contract.resources.map { it.copy(allocation = it.allocation!!.copy(scope = "other")) })
        check("OUTCOME_ALLOCATION_REQUEST_SCOPE", state = OperationOutcomeState.VIOLATED)
    }
    @Test fun runPayloadVersionCannotBeCoercedByAValidVersionThreeSignature() = case {
        check("OUTCOME_EVIDENCE_VERSION", run = run().copy(formatVersion = 2))
    }
    @Test fun outputSlotMustBeDeclaredByTheCreator() = case {
        contract = contract.copy(resources = contract.resources.map { it.copy(allocation = it.allocation!!.copy(binding = "invented")) })
        check("OUTCOME_ALLOCATION_OUTPUT", state = OperationOutcomeState.VIOLATED)
    }
    @Test fun anAliasAtAnotherCreatorOutputPathCannotReplaceTheFrozenSlot() = case {
        check("OUTCOME_ALLOCATION_VALUE_SLOT", claims().map {
            if (it.id == "binding-make-1") it.copy(path = listOf("alias")) else it }, OperationOutcomeState.VIOLATED)
    }
    @Test fun unusedAllocationObligationsCannotDisappearFromAssessment() = case {
        contract = contract.copy(resources = contract.resources + contract.resources.single().copy(id = "unused",
            allocation = contract.resources.single().allocation!!.copy(path = listOf("unused"))))
        assertFailsWith<OutcomeInputException> { requirements() }
    }
}
