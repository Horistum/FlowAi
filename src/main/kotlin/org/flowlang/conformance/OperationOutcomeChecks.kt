package org.flowlang.conformance

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import org.flowlang.cli.Json
import org.flowlang.compiler.CanonicalApprovalNode
import org.flowlang.compiler.CanonicalTaskNode
import org.flowlang.compiler.CompilationResult
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.ai.*
import org.flowlang.frontend.outcomes.*
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.serialization.FlowJson

/** Missing observations and wrong scoped addresses cannot become successful operation outcomes. */
internal class OperationOutcomeChecks(private val root: File) {
    private val corpus = File(root, "conformance/operation-outcomes")
    private val compiler by lazy { FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File(root, "modules")),
        StandardEnvironmentSafetyPolicyNotes.policy(root)) }
    private val cases = listOf("infrastructure", "data-copy", "credential-rotation")
    fun checks(): List<ConformanceCheck> = listOf(
        check("ai.outcomes.missing-observations") { cases.forEach { verify(it, false) } },
        check("ai.outcomes.wrong-resource") { cases.forEach { verify(it, true) } },
        check("ai.outcomes.schemas") {
            val schema = FlowJson.readTree(File(root, "schemas/operation-outcome-requirements.schema.json"))
            cases.forEach { JsonSchemaSmokeValidator.validate(FlowJson.readTree(File(corpus, "$it.requirements.json")), schema) }
        }
    ) + runtimeChecks()

    private fun runtimeChecks(): List<ConformanceCheck> {
        val directory = File(root, "conformance/runtime-allocation")
        return FlowJson.readTree(File(directory, "corpus.json")).path("cases").map { vector ->
            check("ai.runtime-allocation.${vector.path("id").asText()}") {
                val temp = Files.createTempDirectory("allocation-conformance-").toFile()
                try {
                    val result = ReviewedAiProposalFrontend(compiler).compile(ExternalAiProposalReader.read(File(directory, "database.proposal.json")).proposal)
                    require(result is CompilationResult.Accepted)
                    val unit = result.unit
                    val tree = FlowJson.readTree(File(directory, "database.requirements.json")) as ObjectNode
                    tree.put("graphDigest", unit.graphDigest.value)
                    fun file(name: String, value: Any) = File(temp, name).apply { writeBytes(Json.mapper.writeValueAsBytes(value)) }
                    val captured = OperationOutcomeReader.requirements(file("requirements.json", tree))
                    fun schema(name: String, value: Any) = JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(value),
                        FlowJson.readTree(File(root, "schemas/$name-v3.schema.json")))
                    schema("operation-outcome-requirements", tree)
                    fun node(output: String) = unit.graph.nodes.filterIsInstance<CanonicalTaskNode>().single { output in it.semantics.outputs }.id.value
                    fun at(second: Int) = "2026-11-11T07:00:${second.toString().padStart(2, '0')}Z"
                    val source = "synthetic provider instance capture".toByteArray()
                    val sha = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it.toInt() and 255) }
                    val address = ResourceIdentity("isolated", "database", "db-allocated")
                    val make = OperationObservation(3, "make-result", captured.source.sha256, unit.graphDigest.value, "allocation-conformance", "a".repeat(32),
                        ObservationKind.RESULT, ObservationStatus.SUCCEEDED, node("make"), address, at(4), sha, source.size,
                        invocationId = "make-1", resourceId = "target", instanceId = "native-instance")
                    val consume = make.copy(id = "consume-result", nodeId = node("consume"), invocationId = "consume-1", observedAt = at(6))
                    val allocation = make.copy(id = "allocation", kind = ObservationKind.ALLOCATION, binding = "make", path = listOf("handle"))
                    var claims = listOf(make, consume, allocation, allocation.copy(id = "binding", kind = ObservationKind.BINDING),
                        make.copy(id = "creation", kind = ObservationKind.CREATION, created = true),
                        make.copy(id = "ownership", kind = ObservationKind.OWNERSHIP, owner = "tenant"),
                        consume.copy(id = "state", kind = ObservationKind.STATE, observedAt = at(9), facts = mapOf("ready" to Json.mapper.valueToTree(true))))
                    val variant = vector.path("id").asText()
                    claims = when (variant) {
                        "missing-allocation" -> claims.filter { it.kind != ObservationKind.ALLOCATION }
                        "missing-instance" -> claims.map { it.copy(instanceId = null) }
                        "replacement" -> claims.map { if (it.kind == ObservationKind.STATE) it.copy(instanceId = "replacement") else it }
                        "conflict" -> claims + allocation.copy(id = "conflict", instanceId = "replacement")
                        "wrong-scope" -> claims.map { if (it.kind == ObservationKind.ALLOCATION) it.copy(resource = address.copy(scope = "other")) else it }
                        else -> claims
                    }
                    val run = OperationRunObservation(3, "trace", captured.source.sha256, unit.graphDigest.value, make.runId, make.challenge,
                        "main", at(0), at(8), true, ObservationStatus.SUCCEEDED, sourceSha256 = sha, sourceSizeBytes = source.size,
                        invocations = listOf(ObservedInvocation("approval", unit.graph.nodes.filterIsInstance<CanonicalApprovalNode>().single().id.value, 1, at(1), at(2), ObservationStatus.SUCCEEDED),
                            ObservedInvocation("make-1", node("make"), 1, at(3), at(4), ObservationStatus.SUCCEEDED),
                            ObservedInvocation("consume-1", node("consume"), 1, at(5), at(6), ObservationStatus.SUCCEEDED)))
                    val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                    fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
                    fun signed(value: Any, isRun: Boolean = false): SignedOperationObservation {
                        val payload = Json.mapper.writeValueAsBytes(value)
                        val signature = Signature.getInstance("Ed25519").apply {
                            initSign(key.private)
                            update(if (isRun) OperationObservationAuthentication.runSigningBytes("observer", payload, 3)
                                else OperationObservationAuthentication.signingBytes("observer", payload, 3))
                        }.sign()
                        return SignedOperationObservation("observer", b64(payload), b64(signature), b64(source))
                    }
                    val trust = OperationObservationTrust(3, listOf(ObservationGrant("observer", b64(key.public.encoded), listOf("isolated", "other"), listOf("database"),
                        ObservationKind.entries.filter { variant != "missing-grant" || it != ObservationKind.ALLOCATION }, listOf("main"))))
                    val bundle = OperationObservationBundle(3, claims.map { signed(it) }, signed(run, true))
                    claims.forEach { schema("operation-observation", it) }
                    schema("operation-observation-trust", trust); schema("operation-observation-bundle", bundle); schema("operation-run-observation", run)
                    val review = OperationOutcomeReviewer.evaluate(unit, captured, OperationOutcomeReader.observations(file("bundle.json", bundle)),
                        OperationOutcomeReader.trust(file("trust.json", trust)), Instant.parse(at(10)))
                    require(review.state.name == vector.path("state").asText()) { review.toString() }
                    require(review.results.any { it.code == vector.path("code").asText() } || vector.path("code").asText() in review.diagnostics)
                    require(!review.executionAuthorized && review.requirementsSource == captured.source)
                    schema("operation-outcome-review", review)
                } finally { temp.deleteRecursively() }
            }
        }
    }
    private fun check(name: String, action: () -> Unit) = runCatching(action).let {
        ConformanceCheck(name, it.isSuccess, it.exceptionOrNull()?.message)
    }
    private fun verify(id: String, wrongResource: Boolean) {
        val directory = Files.createTempDirectory("outcomes-conformance-").toFile()
        try {
            val result = ReviewedAiProposalFrontend(compiler).compile(ExternalAiProposalReader.read(File(corpus, "$id.proposal.json")).proposal)
            require(result is CompilationResult.Accepted) { "Outcome fixture must compile: $id/$result" }
            val requirements = FlowJson.readTree(File(corpus, "$id.requirements.json")) as ObjectNode
            requirements.put("graphDigest", result.unit.graphDigest.value)
            if (wrongResource) (requirements.path("resources")[0].path("identity") as ObjectNode).put("scope", "different-scope")
            val file = File(directory, "requirements.json").apply { writeText(requirements.toString()) }
            val review = OperationOutcomeReviewer.evaluate(result.unit, OperationOutcomeReader.requirements(file),
                evaluatedAt = Instant.parse("2026-11-08T02:10:00Z"))
            require(review.state == if (wrongResource) OperationOutcomeState.VIOLATED else OperationOutcomeState.UNRESOLVED) {
                "Invalid outcome acceptance: $id/${review.state}"
            }
            require(!review.executionAuthorized)
        } finally { directory.deleteRecursively() }
    }
}
