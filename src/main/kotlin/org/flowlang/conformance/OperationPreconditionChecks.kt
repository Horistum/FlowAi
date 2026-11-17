package org.flowlang.conformance

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Instant
import java.util.Base64
import org.flowlang.cli.Json
import org.flowlang.compiler.CompilationResult
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.ai.ExternalAiProposalReader
import org.flowlang.frontend.ai.ReviewedAiProposalFrontend
import org.flowlang.frontend.outcomes.*
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.serialization.FlowJson

/** Independent cross-domain resource, ownership and generation pins, with signed falsifications. */
internal class OperationPreconditionChecks(private val root: File) {
    private val directory = File(root, "conformance/operation-preconditions")
    private val compiler by lazy { FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File(root, "modules")),
        StandardEnvironmentSafetyPolicyNotes.policy(root)) }
    fun checks(): List<ConformanceCheck> {
        val corpus = FlowJson.readTree(File(directory, "corpus.json"))
        return corpus.path("vectors").map { vector ->
            val outcome = runCatching {
                corpus.path("cases").forEach { case -> verify(case.asText(), vector.path("id").asText(),
                    vector.path("state").asText(), vector.path("code").asText()) }
            }
            ConformanceCheck("ai.preconditions.${vector.path("id").asText()}", outcome.isSuccess, outcome.exceptionOrNull()?.message)
        }
    }
    private fun verify(case: String, variant: String, expectedState: String, expectedCode: String) {
        val temporary = Files.createTempDirectory("precondition-conformance-").toFile()
        try {
            val compiled = ReviewedAiProposalFrontend(compiler).compile(ExternalAiProposalReader.read(File(directory, "$case.proposal.json")).proposal)
            require(compiled is CompilationResult.Accepted) { "Precondition fixture must compile: $case/$compiled" }
            val tree = FlowJson.readTree(File(directory, "$case.requirements.json")) as ObjectNode
            tree.put("graphDigest", compiled.unit.graphDigest.value)
            fun file(name: String, value: Any) = File(temporary, name).apply { writeBytes(Json.mapper.writeValueAsBytes(value)) }
            fun schema(name: String, value: Any) = JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(value),
                FlowJson.readTree(File(root, "schemas/operation-precondition-$name.schema.json")))
            val requirements = OperationPreconditionReader.requirements(file("requirements.json", tree))
            val at = Instant.parse("2026-11-17T01:00:30Z")
            val pending = OperationPreconditionReviewer.evaluate(compiled.unit, requirements, evaluatedAt = at)
            require(pending.state == OperationOutcomeState.UNRESOLVED && pending.results.all { it.nodeId != null })
            val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
            val resources = tree.path("resources").associateBy { it.path("id").asText() }
            val source = "synthetic precondition snapshot".toByteArray()
            val sha = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it.toInt() and 255) }
            var claims = tree.path("operations").map { operation ->
                val resource = resources.getValue(operation.path("resource").asText())
                val identity = resource.path("identity")
                PreconditionObservation(1, operation.path("id").asText() + "-snapshot", requirements.source.sha256,
                    compiled.unit.graphDigest.value, tree.path("assessmentId").asText(), tree.path("challenge").asText(),
                    operation.path("id").asText(), requireNotNull(pending.results.single { it.id == operation.path("id").asText() }.nodeId),
                    ResourceIdentity(identity.path("scope").asText(), identity.path("type").asText(), identity.path("key").asText()),
                    ObservationStatus.SUCCEEDED, "2026-11-17T01:00:20Z", sha, source.size,
                    resource.path("instanceId").asText(), resource.get("owner")?.asText(), operation.path("facts").properties().associate { it.key to it.value })
            }
            claims = when (variant) {
                "missing" -> emptyList()
                "owner" -> claims.map { if (it.owner != null) it.copy(owner = "changed-owner") else it }
                "instance" -> claims.map { it.copy(instanceId = "replacement") }
                "state" -> claims.map { it.copy(facts = it.facts.mapValues { Json.mapper.valueToTree("changed") }) }
                "replay" -> claims.map { it.copy(challenge = "b".repeat(32)) }
                else -> claims
            }
            val bundle = PreconditionObservationBundle(1, claims.map { claim ->
                schema("observation", claim)
                val payload = Json.mapper.writeValueAsBytes(claim)
                val signer = Signature.getInstance("Ed25519").apply {
                    initSign(key.private); update(OperationPreconditionAuthentication.signingBytes("observer", payload))
                }
                SignedOperationObservation("observer", b64(payload), b64(signer.sign()), b64(source))
            })
            val trust = PreconditionObservationTrust(1, listOf(PreconditionObservationGrant("observer", b64(key.public.encoded),
                resources.values.map { it.path("identity").path("scope").asText() }.distinct(),
                resources.values.map { it.path("identity").path("type").asText() }.distinct())))
            val review = OperationPreconditionReviewer.evaluate(compiled.unit, requirements,
                OperationPreconditionReader.observations(file("observations.json", bundle)), OperationPreconditionReader.trust(file("trust.json", trust)),
                if (variant == "expired") at.plusSeconds(61) else at)
            require(review.state.name == expectedState && (expectedCode in review.diagnostics || review.results.any { it.code == expectedCode })) { review.toString() }
            require(!review.executionAuthorized && review.requirementsSource == requirements.source)
            require((review.state == OperationOutcomeState.SATISFIED) == (review.validUntil != null))
            schema("requirements", tree); schema("bundle", bundle); schema("trust", trust); schema("review", review)
        } finally { temporary.deleteRecursively() }
    }
}
