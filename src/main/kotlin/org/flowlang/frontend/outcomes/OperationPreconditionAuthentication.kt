package org.flowlang.frontend.outcomes

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import org.flowlang.compiler.CanonicalExecutionGraph
import org.flowlang.compiler.CanonicalTaskNode

/** Fresh snapshots have a distinct protocol; an outcome signature cannot authenticate one. */
object OperationPreconditionAuthentication {
    fun signingBytes(keyId: String, payload: ByteArray): ByteArray =
        OperationObservationAuthentication.signingBytes("Horistum operation precondition\u0000v1\u0000", keyId, payload)

    internal fun verify(bundle: PreconditionObservationBundle, trust: PreconditionObservationTrust,
                        contract: OperationPreconditionRequirements, contractSha: String,
                        graph: CanonicalExecutionGraph, now: Instant): List<PreconditionObservation> {
        val grants = trust.grants.associateBy { it.keyId }
        val nodes = graph.nodes.filterIsInstance<CanonicalTaskNode>().map { it.id.value }.toSet()
        val operations = contract.operations.map { it.id }.toSet()
        val ledger = bundle.observations.map { signed ->
            try {
                val grant = grants[signed.keyId] ?: throw UnresolvedOutcome("PRECONDITION_UNTRUSTED_OBSERVER")
                val payload = Base64.getDecoder().decode(signed.payload)
                val signature = Base64.getDecoder().decode(signed.signature)
                require(payload.size <= OperationOutcomeReader.MAX_PAYLOAD && signature.size == 64)
                val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(grant.publicKey)))
                val verifier = Signature.getInstance("Ed25519")
                verifier.initVerify(key); verifier.update(signingBytes(signed.keyId, payload))
                if (!verifier.verify(signature)) throw UnresolvedOutcome("PRECONDITION_INVALID_SIGNATURE")
                val o = OperationPreconditionReader.observation(payload)
                val source = Base64.getDecoder().decode(signed.source)
                if (source.size != o.sourceSizeBytes || source.size > OperationOutcomeReader.MAX_PAYLOAD ||
                    OperationOutcomeReader.sha256(source) != o.sourceSha256) throw UnresolvedOutcome("PRECONDITION_SOURCE_CHANGED")
                if (o.resource.scope !in grant.scopes || o.resource.type !in grant.resourceTypes)
                    throw UnresolvedOutcome("PRECONDITION_OBSERVER_SCOPE")
                if (o.contractSha256 != contractSha || o.graphDigest != contract.graphDigest ||
                    o.assessmentId != contract.assessmentId || o.challenge != contract.challenge)
                    throw UnresolvedOutcome("PRECONDITION_OBSERVATION_CONTEXT")
                if (o.requirementId !in operations || o.nodeId !in nodes) throw UnresolvedOutcome("PRECONDITION_UNKNOWN_OPERATION")
                val at = Instant.parse(o.observedAt)
                if (at < Instant.parse(contract.issuedAt) || at > Instant.parse(contract.expiresAt) || at > now)
                    throw UnresolvedOutcome("PRECONDITION_OBSERVATION_TIME")
                o
            } catch (failure: UnresolvedOutcome) { throw failure }
              catch (_: Exception) { throw UnresolvedOutcome("PRECONDITION_INVALID_OBSERVATION") }
        }
        if (ledger.map { it.id }.distinct().size != ledger.size ||
            ledger.map { it.requirementId }.distinct().size != ledger.size)
            throw UnresolvedOutcome("PRECONDITION_AMBIGUOUS_OBSERVATIONS")
        return ledger
    }
}
