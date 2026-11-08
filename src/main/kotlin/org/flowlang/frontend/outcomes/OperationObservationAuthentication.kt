package org.flowlang.frontend.outcomes

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import org.flowlang.compiler.*
import org.flowlang.effects.EffectOperation

internal class UnresolvedOutcome(val code: String) : RuntimeException()

/** Authentication establishes attribution to a caller-authorized observer, never observation truth. */
object OperationObservationAuthentication {
    private val domain = "Horistum operation observation\u0000v1\u0000".toByteArray(Charsets.UTF_8)

    /** Public signing protocol. The producer signs its original strict-UTF-8 JSON bytes. */
    fun signingBytes(keyId: String, payload: ByteArray): ByteArray {
        require(keyId.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}")) && payload.size <= OperationOutcomeReader.MAX_PAYLOAD)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.write(domain)
            val key = keyId.toByteArray(Charsets.UTF_8)
            stream.writeInt(key.size); stream.write(key); stream.write(payload)
        }
        return output.toByteArray()
    }

    internal fun verify(bundle: OperationObservationBundle, trust: OperationObservationTrust,
                        requirements: OperationOutcomeRequirements, contractSha: String,
                        graph: CanonicalExecutionGraph, now: Instant): List<OperationObservation> {
        val keys = try {
            trust.grants.associate { grant ->
                val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(grant.publicKey)))
                require(key is EdECPublicKey && key.params.name == "Ed25519")
                grant.keyId to (grant to key)
            }
        } catch (_: Exception) { throw OutcomeInputException("OUTCOME_INVALID_TRUST") }
        val nodes = graph.nodes.associateBy { it.id.value }
        val addresses = requirements.resources.map { it.identity }.toSet()
        val observations = bundle.observations.map { signed ->
            try {
                val (grant, key) = keys[signed.keyId] ?: throw UnresolvedOutcome("OUTCOME_UNTRUSTED_OBSERVER")
                val payload = Base64.getDecoder().decode(signed.payload)
                val signature = Base64.getDecoder().decode(signed.signature)
                require(payload.size <= OperationOutcomeReader.MAX_PAYLOAD && signature.size == 64)
                val verifier = Signature.getInstance("Ed25519")
                verifier.initVerify(key); verifier.update(signingBytes(signed.keyId, payload))
                if (!verifier.verify(signature)) throw UnresolvedOutcome("OUTCOME_INVALID_SIGNATURE")
                val observation = OperationOutcomeReader.observation(payload)
                val source = Base64.getDecoder().decode(signed.source)
                if (source.size != observation.sourceSizeBytes || source.size > OperationOutcomeReader.MAX_PAYLOAD ||
                    OperationOutcomeReader.sha256(source) != observation.sourceSha256) throw UnresolvedOutcome("OUTCOME_SOURCE_CHANGED")
                if (observation.kind !in grant.kinds || observation.resource.scope !in grant.scopes ||
                    observation.resource.type !in grant.resourceTypes) throw UnresolvedOutcome("OUTCOME_OBSERVER_SCOPE")
                if (observation.contractSha256 != contractSha || observation.graphDigest != requirements.graphDigest ||
                    observation.runId != requirements.runId || observation.challenge != requirements.challenge)
                    throw UnresolvedOutcome("OUTCOME_OBSERVATION_CONTEXT")
                if (observation.resource !in addresses) throw UnresolvedOutcome("OUTCOME_UNDECLARED_RESOURCE")
                val at = Instant.parse(observation.observedAt)
                if (at < Instant.parse(requirements.startedAt) || at > now) throw UnresolvedOutcome("OUTCOME_OBSERVATION_TIME")
                val node = observation.nodeId?.let { nodes[it] ?: throw UnresolvedOutcome("OUTCOME_UNKNOWN_NODE") }
                if (observation.kind == ObservationKind.BINDING) {
                    val outputs = node?.let { outputBindings(graph, it) } ?: graph.inputs.map { it.name }.toSet()
                    if (observation.binding !in outputs) throw UnresolvedOutcome("OUTCOME_UNKNOWN_BINDING")
                } else if (node !is CanonicalTaskNode) throw UnresolvedOutcome("OUTCOME_NOT_OPERATION")
                if (observation.kind == ObservationKind.CREATION && node!!.semantics.effects.none {
                    it.operation in setOf(EffectOperation.CREATE, EffectOperation.UPSERT)
                }) throw UnresolvedOutcome("OUTCOME_INVALID_CREATOR")
                observation
            } catch (failure: UnresolvedOutcome) { throw failure }
              catch (_: Exception) { throw UnresolvedOutcome("OUTCOME_INVALID_OBSERVATION") }
        }
        if (observations.map { it.id }.distinct().size != observations.size || observations.map {
            listOf(it.kind, it.nodeId, it.resource, it.binding, it.path)
        }.distinct().size != observations.size) throw UnresolvedOutcome("OUTCOME_AMBIGUOUS_OBSERVATIONS")
        return observations
    }

    internal fun outputBindings(graph: CanonicalExecutionGraph, node: CanonicalExecutionNode): Set<String> =
        node.semantics.outputs.toSet() + graph.valueMerges.filter { it.targetNodeId == node.id }.map { it.resultBinding } +
            if (node is CanonicalControlOperationNode && node.operation == CanonicalControlOperationKind.SET)
                setOfNotNull(node.detail?.takeIf { " = " in it }?.substringBefore(" = ")) else emptySet()
}
