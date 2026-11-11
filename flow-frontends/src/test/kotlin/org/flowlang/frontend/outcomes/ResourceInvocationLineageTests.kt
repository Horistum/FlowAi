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

class ResourceInvocationLineageTests {
    private val now = Instant.parse("2026-11-10T09:00:00Z")
    private val compiler = FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File("modules")),
        StandardEnvironmentSafetyPolicyNotes.policy(File(".")))
    private fun json(value: Any): JsonNode = Json.mapper.valueToTree(value)
    private fun at(second: Long) = now.minusSeconds(100).plusSeconds(second).toString()
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private enum class Shape { CREATE, RETRY, OUTER, EARLY }

    private fun case(shape: Shape = Shape.CREATE, nested: Boolean = false, type: String = "resource", block: Case.() -> Unit) {
        val root = createTempDirectory("resource-lineage-").toFile()
        try { Case(root, shape, nested, type).block() } finally { root.deleteRecursively() }
    }

    private inner class Case(val root: File, val shape: Shape, val nested: Boolean, type: String) {
        val address = ResourceIdentity("isolated-test", type, "declared-target")
        val producerName = if (shape in listOf(Shape.CREATE, Shape.EARLY)) "make" else "resolve"
        val outputPath = if (nested) listOf("handle") else emptyList()
        private val reference = "ref:$producerName" + if (nested) ".handle" else ""
        private val operand: Any = if (shape == Shape.EARLY) address else if (nested) mapOf("target" to reference) else reference
        private fun operation(id: String, capability: String, resource: Any, criteria: String? = null): Map<String, Any> =
            mapOf("kind" to "operation", "id" to id, "capability" to capability,
                "params" to (mapOf("resource" to resource) + if (criteria == null) emptyMap() else mapOf("criteria" to criteria)))
        private val create = operation("make", "PROVISION", address) + mapOf("requires" to listOf("approval"))
        private val resolve = operation("resolve", "VERIFY", address, "resolve")
        private val consume = operation("consume", "VERIFY", operand, "consume")
        private val after = operation("after", "VERIFY", operand, "after")
        private val approval = mapOf("kind" to "operation", "id" to "approval", "capability" to "APPROVE")
        private val retry = mapOf("kind" to "retry", "id" to "retry", "maxAttempts" to 2, "delaySeconds" to 0,
            "body" to if (shape == Shape.OUTER) listOf(consume) else listOf(resolve, consume))
        private val body = when (shape) {
            Shape.CREATE -> listOf(approval, create, consume)
            Shape.EARLY -> listOf(approval, consume, create)
            Shape.RETRY -> listOf(retry, after)
            Shape.OUTER -> listOf(resolve, retry, after)
        }
        val source = json(mapOf("intentVersion" to "3.1", "kind" to "FlowIntentDocument", "name" to "resource-lineage",
            "workflows" to listOf(mapOf("name" to "main", "kind" to "CUSTOM", "body" to body)))).toString()
        val unit = assertIs<CompilationResult.Accepted>(IntentYamlFrontend(compiler).compile(
            File(root, "source.json").apply { writeText(source) })).unit
        private val tasks = unit.graph.nodes.filterIsInstance<CanonicalTaskNode>()
        fun node(name: String) = tasks.single { name in it.semantics.outputs }
        private fun selector(task: CanonicalTaskNode) = RequirementSelector(StandardCapability.valueOf(task.semantics.capability!!.value),
            parameters = task.semantics.parameters["criteria"]?.let { mapOf("criteria" to Json.mapper.readTree(it).textValue()) }.orEmpty())
        private val created = shape in listOf(Shape.CREATE, Shape.EARLY)
        var contract = OperationOutcomeRequirements(2, unit.graphDigest.value, "run-lineage", "a".repeat(32), at(0), "reviewer",
            listOf(OutcomeResource("resource", address, if (created) ResourceDisposition.CREATED_IN_RUN else ResourceDisposition.SHARED,
                creator = if (created) selector(node("make")) else null)),
            tasks.map { task ->
                val name = task.semantics.outputs.single()
                OperationOutcomeRequirement(name, selector(task), "resource", "resource",
                    path = if (nested && name in listOf("consume", "after")) listOf("target") else emptyList(),
                    postconditions = if (name == if (created) "consume" else "after") mapOf("ready" to json(true)) else emptyMap(),
                    evidence = AttemptOutcomePolicy(ExpectedOperationStatus.COMPLETED))
            }, RunEvidenceRequirement("main", ExpectedOperationStatus.COMPLETED))
        private val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        private val capture = "synthetic signed lineage assertion; no external work".toByteArray()
        var trust = OperationObservationTrust(2, listOf(ObservationGrant("observer", b64(key.public.encoded), listOf(address.scope),
            listOf(address.type), ObservationKind.entries.filter { it != ObservationKind.ALLOCATION }, listOf("main"))))
        fun requirements() = OperationOutcomeReader.requirements(File(root, "requirements.json").apply {
            writeBytes(Json.mapper.writeValueAsBytes(contract)) })
        private fun event(id: String, name: String, second: Long, attempt: Int = 1, failed: Boolean = false) = ObservedInvocation(id,
            if (name == "approval") unit.graph.nodes.filterIsInstance<CanonicalApprovalNode>().single().id.value else node(name).id.value,
            attempt, at(second), at(second + 1), if (failed) ObservationStatus.FAILED else ObservationStatus.SUCCEEDED,
            if (failed) "error-$id" else null)
        fun run(): OperationRunObservation {
            val events = when (shape) {
                Shape.CREATE -> listOf(event("approval", "approval", 1), event("make-1", "make", 3), event("consume-1", "consume", 5))
                Shape.EARLY -> listOf(event("approval", "approval", 1), event("consume-1", "consume", 3), event("make-1", "make", 5))
                Shape.RETRY -> listOf(event("resolve-1", "resolve", 1), event("consume-1", "consume", 3, failed = true),
                    event("resolve-2", "resolve", 5, 2), event("consume-2", "consume", 7, 2), event("after-1", "after", 9))
                Shape.OUTER -> listOf(event("resolve-1", "resolve", 1), event("consume-1", "consume", 3, failed = true),
                    event("consume-2", "consume", 5, 2), event("after-1", "after", 7))
            }
            return OperationRunObservation(2, "trace", requirements().source.sha256, unit.graphDigest.value, contract.runId,
                contract.challenge, "main", at(0), at(20), true, ObservationStatus.SUCCEEDED,
                sourceSha256 = OperationOutcomeReader.sha256(capture), sourceSizeBytes = capture.size, invocations = events)
        }
        fun claims(run: OperationRunObservation = run()): List<OperationObservation> = run.invocations.filter { it.id != "approval" }.flatMap { event ->
            val result = OperationObservation(2, "result-${event.id}", requirements().source.sha256, unit.graphDigest.value,
                contract.runId, contract.challenge, ObservationKind.RESULT, event.status, event.nodeId, address, event.completedAt,
                OperationOutcomeReader.sha256(capture), capture.size, invocationId = event.id)
            buildList {
                add(result)
                if (event.nodeId == node(producerName).id.value && event.status == ObservationStatus.SUCCEEDED && shape != Shape.EARLY)
                    add(result.copy(id = "binding-${event.id}", kind = ObservationKind.BINDING, binding = producerName, path = outputPath))
                if (created && event.id == "make-1") add(result.copy(id = "creation", kind = ObservationKind.CREATION, created = true))
                if (event.id == if (created) "consume-1" else "after-1")
                    add(result.copy(id = "final-state", kind = ObservationKind.STATE, facts = mapOf("ready" to json(true)), observedAt = at(21)))
            }
        }
        private fun sign(value: Any, run: Boolean): SignedOperationObservation {
            val payload = Json.mapper.writeValueAsBytes(value)
            val signer = Signature.getInstance("Ed25519").apply {
                initSign(key.private)
                update(if (run) OperationObservationAuthentication.runSigningBytes("observer", payload)
                    else OperationObservationAuthentication.signingBytes("observer", payload, 2))
            }
            return SignedOperationObservation("observer", b64(payload), b64(signer.sign()), b64(capture))
        }
        fun review(run: OperationRunObservation = run(), claims: List<OperationObservation> = claims(run)): OperationOutcomeReview {
            val bundle = OperationObservationBundle(2, claims.map { sign(it, false) }, sign(run, true))
            val evidence = OperationOutcomeReader.observations(File(root, "bundle.json").apply { writeBytes(Json.mapper.writeValueAsBytes(bundle)) })
            val grants = OperationOutcomeReader.trust(File(root, "trust.json").apply { writeBytes(Json.mapper.writeValueAsBytes(trust)) })
            return OperationOutcomeReviewer.evaluate(unit, requirements(), evidence, grants, now)
        }
        fun assertCode(review: OperationOutcomeReview, code: String, state: OperationOutcomeState = OperationOutcomeState.UNRESOLVED) {
            assertEquals(state, review.state, review.toString())
            assertTrue(code in review.diagnostics || review.results.any { it.code == code }, review.toString())
        }
    }

    @Test fun createdHandlesRetainExactProducerAndCreatorAcrossResourceTypes() {
        for (type in listOf("service", "database", "credential")) case(type = type) {
            val result = review()
            assertEquals(OperationOutcomeState.SATISFIED, result.state, result.toString())
            val consumer = result.results.single { it.id == "consume" }
            assertEquals("make-1", consumer.creatorInvocationId)
            assertEquals("make-1", consumer.resourceLineage.single().producerInvocationId)
            assertEquals("consume-1", consumer.resourceLineage.single().consumerInvocationId)
            assertEquals(listOf("make"), result.execution!!.invocationTargets.single { it.nodeId == node("make").id.value }.outputs)
            assertFalse(result.executionAuthorized)
        }
    }
    @Test fun structuredOutputPathsAndNestedOperandPathsRemainDistinct() = case(nested = true) {
        val result = review()
        assertEquals(OperationOutcomeState.SATISFIED, result.state, result.toString())
        assertEquals(listOf("handle"), result.results.single { it.id == "consume" }.resourceLineage.single().path)
        assertCode(review(claims = claims().map { if (it.kind == ObservationKind.BINDING) it.copy(path = listOf("other")) else it }),
            "OUTCOME_RESOURCE_BINDING_MISSING_OR_AMBIGUOUS")
    }
    @Test fun retriesUseTheirOwnProducerAndFollowingWorkUsesTheSuccessfulIteration() = case(Shape.RETRY) {
        val result = review()
        assertEquals(OperationOutcomeState.SATISFIED, result.state, result.toString())
        assertEquals(listOf("resolve-1", "resolve-2"), result.results.single { it.id == "consume" }.resourceLineage.map { it.producerInvocationId })
        assertEquals("resolve-2", result.results.single { it.id == "after" }.resourceLineage.single().producerInvocationId)
    }
    @Test fun aProducerOutsideTheRetryRemainsVisibleToEveryAttempt() = case(Shape.OUTER) {
        val result = review()
        assertEquals(OperationOutcomeState.SATISFIED, result.state, result.toString())
        assertEquals(listOf("resolve-1", "resolve-1"), result.results.single { it.id == "consume" }.resourceLineage.map { it.producerInvocationId })
    }
    @Test fun aMatchingOldAddressCannotReplaceTheLatestProducerInvocation() = case(Shape.RETRY) {
        assertCode(review(claims = claims().filter { it.id != "binding-resolve-2" }), "OUTCOME_RESOURCE_BINDING_MISSING_OR_AMBIGUOUS")
    }
    @Test fun aSuccessfulLastAttemptCannotHideMissingEarlierLineage() = case(Shape.RETRY) {
        assertCode(review(claims = claims().filter { it.id != "binding-resolve-1" }), "OUTCOME_RESOURCE_BINDING_MISSING_OR_AMBIGUOUS")
    }
    @Test fun bindingTimeMustMatchItsActualProducerCompletion() = case {
        for (stamp in listOf(at(3), at(5), at(21))) assertCode(review(claims = claims().map {
            if (it.kind == ObservationKind.BINDING) it.copy(observedAt = stamp) else it }), "OUTCOME_BINDING_COMPLETION")
    }
    @Test fun unknownOutputAndInventedInvocationCannotSupplyAResource() = case {
        assertCode(review(claims = claims().map { if (it.kind == ObservationKind.BINDING) it.copy(binding = "invented") else it }), "OUTCOME_UNKNOWN_BINDING")
        assertCode(review(claims = claims().map { if (it.kind == ObservationKind.BINDING) it.copy(invocationId = "invented") else it }), "OUTCOME_UNKNOWN_INVOCATION")
        assertCode(review(claims = claims().map { if (it.kind == ObservationKind.BINDING) it.copy(status = ObservationStatus.UNKNOWN) else it }), "OUTCOME_BINDING_COMPLETION")
    }
    @Test fun failedProducerCannotPublishEvenAnAuthenticallySignedHandle() = case(Shape.RETRY) {
        val original = run()
        val failed = original.invocations[2].copy(status = ObservationStatus.FAILED, errorId = "failed-producer")
        val run = original.copy(invocations = original.invocations.take(2) + failed, status = ObservationStatus.FAILED, errorId = failed.errorId)
        val claims = claims(run)
        val binding = claims.first { it.kind == ObservationKind.BINDING }.copy(id = "failed-binding", invocationId = failed.id,
            observedAt = failed.completedAt)
        assertCode(review(run, claims + binding), "OUTCOME_BINDING_COMPLETION")
    }
    @Test fun conflictingAddressesAtOneOutputSlotAreAmbiguous() = case {
        val other = address.copy(key = "other")
        contract = contract.copy(resources = contract.resources + OutcomeResource("other", other, ResourceDisposition.SHARED))
        val claims = claims()
        assertCode(review(claims = claims + claims.single { it.kind == ObservationKind.BINDING }.copy(id = "conflict", resource = other)),
            "OUTCOME_AMBIGUOUS_OBSERVATIONS")
    }
    @Test fun theActualDifferentAddressIsReportedAsAViolationNotSubstituted() = case(Shape.RETRY) {
        val other = address.copy(key = "other")
        contract = contract.copy(resources = contract.resources + OutcomeResource("other", other, ResourceDisposition.SHARED))
        val result = review(claims = claims().map { if (it.id == "binding-resolve-2") it.copy(resource = other) else it })
        assertCode(result, "OUTCOME_RESOURCE_MISMATCH", OperationOutcomeState.VIOLATED)
        assertEquals(other, result.results.single { it.id == "consume" }.resourceLineage.last().resource)
    }
    @Test fun bindingDoesNotReplaceIndependentCreationEvidence() = case {
        assertCode(review(claims = claims().filter { it.kind != ObservationKind.CREATION }), "OUTCOME_OBSERVATION_MISSING")
        assertCode(review(claims = claims().map { if (it.kind == ObservationKind.CREATION) it.copy(created = false) else it }),
            "OUTCOME_RESOURCE_NOT_CREATED", OperationOutcomeState.VIOLATED)
    }
    @Test fun failedCreationCanLeaveAResourceWithoutPublishingAnOutput() = case {
        contract = contract.copy(operations = contract.operations.map {
            if (it.id == "consume") it.copy(evidence = it.evidence!!.copy(presence = OperationPresence.WHEN_REACHED)) else it })
        val original = run()
        val creator = original.invocations[1].copy(status = ObservationStatus.FAILED, errorId = "partial-creation")
        val run = original.copy(invocations = listOf(original.invocations[0], creator), status = ObservationStatus.FAILED, errorId = creator.errorId)
        val result = review(run, claims(run).map { if (it.kind == ObservationKind.CREATION) it.copy(status = ObservationStatus.SUCCEEDED) else it })
        assertEquals(OperationOutcomeState.SATISFIED, result.state, result.toString())
        assertEquals("make-1", result.results.single { it.id == "make" }.creatorInvocationId)
        assertEquals("OUTCOME_NOT_REACHED", result.results.single { it.id == "consume" }.code)
        assertTrue(result.results.all { it.resourceLineage.isEmpty() })
    }
    @Test fun evenAReadCannotConsumeAResourceBeforeItsRecordedCreation() = case(Shape.EARLY) {
        assertCode(review(), "OUTCOME_CREATION_AFTER_CONSUMPTION")
    }
    @Test fun bindingScopeGrantIsRequiredIndependentlyOfRunTrust() = case {
        trust = trust.copy(grants = trust.grants.map { it.copy(kinds = it.kinds - ObservationKind.BINDING) })
        assertCode(review(), "OUTCOME_OBSERVER_SCOPE")
    }
}
