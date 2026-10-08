package org.flowlang.adapters.certification

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.EdECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Collections

/** Caller-owned authorization. Neither this inventory nor trusted keys may come from the candidate bundle. */
data class CertificationAuthorizedRun(val runId: String, val scenarioId: String, val mutantId: String?, val runnerId: String)

/**
 * A fresh unpredictable challenge must be allocated by the assessment owner and never reused.
 * Durable replay prevention and runner key provisioning remain outside Flow.
 */
class CertificationObservationTrust(
    val challenge: String,
    trustedRunners: Map<String, PublicKey>,
    authorizedRuns: List<CertificationAuthorizedRun>
) {
    val trustedRunners: Map<String, PublicKey>
    val authorizedRuns: List<CertificationAuthorizedRun>

    init {
        require(Regex("[A-Za-z0-9_-]{32,128}").matches(challenge)) { "Assessment challenge must contain 32..128 URL-safe characters." }
        require(trustedRunners.size in 1..CertificationObservationAuthentication.MAX_RUNS)
        require(authorizedRuns.size in 1..CertificationObservationAuthentication.MAX_RUNS)
        require(authorizedRuns.map { it.runId }.distinct().size == authorizedRuns.size)
        require(authorizedRuns.map { it.scenarioId to it.mutantId }.distinct().size == authorizedRuns.size)
        require(trustedRunners.keys.all { it.isNotBlank() && it.length <= 256 })
        require(authorizedRuns.all { run ->
            listOfNotNull(run.runId, run.scenarioId, run.mutantId).all { it.isNotBlank() && it.length <= 256 } &&
                run.runnerId in trustedRunners
        })
        val factory = KeyFactory.getInstance("Ed25519")
        this.trustedRunners = Collections.unmodifiableMap(trustedRunners.mapValues { (_, key) ->
            // Reconstruct immutable provider keys; mutable caller key implementations cannot change trust later.
            val copy = factory.generatePublic(X509EncodedKeySpec(key.encoded))
            require(copy is EdECPublicKey && copy.params.name == "Ed25519") { "Runner keys must be Ed25519." }
            copy
        })
        this.authorizedRuns = Collections.unmodifiableList(authorizedRuns.toList())
    }
}

/** Untrusted input. Construction freezes mutable fields but does not authenticate the statement. */
class SignedCertificationObservation(
    val runnerId: String,
    val challenge: String,
    observation: CertificationExecutionObservation,
    signature: ByteArray
) {
    val observation: CertificationExecutionObservation
    private val signatureBytes: ByteArray

    init {
        require(observation.runtimePrerequisites.size <= CertificationObservationAuthentication.MAX_RUNS)
        require(signature.size <= 64) { "Ed25519 signatures cannot exceed 64 bytes." }
        this.observation = observation.copy(runtimePrerequisites = Collections.unmodifiableList(observation.runtimePrerequisites.toList()))
        signatureBytes = signature.copyOf()
    }

    fun signature(): ByteArray = signatureBytes.copyOf()
}

/** Internal signing protocol, not a public Flow artifact schema. Signing remains runner-owned. */
object CertificationObservationAuthentication {
    const val MAX_RUNS = 256
    const val MAX_STATEMENT_BYTES = 65536
    private const val DOMAIN = "Flow adapter execution observation\u0000v1\u0000"

    /** Length-prefixed strict UTF-8; fixed field order; nullable mutant has an explicit presence byte. */
    fun signingBytes(runnerId: String, challenge: String, observation: CertificationExecutionObservation): ByteArray {
        require(observation.runtimePrerequisites.size <= MAX_RUNS)
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.write(DOMAIN.toByteArray(Charsets.UTF_8))
        fun string(value: String) {
            require(value.length <= 4096) { "Signed observation field exceeds its character budget." }
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
            require(bytes.size().toLong() + 4 + encoded.remaining() <= MAX_STATEMENT_BYTES) {
                "Signed observation exceeds its byte budget."
            }
            out.writeInt(encoded.remaining())
            while (encoded.hasRemaining()) out.writeByte(encoded.get().toInt())
        }
        fun reference(value: CertificationEvidenceReference) {
            string(value.id); string(value.sha256); out.writeInt(value.sizeBytes)
        }
        string(runnerId); string(challenge); string(observation.runId)
        with(observation.adapter) { string(target); string(adapterId); string(version); string(implementationSha256) }
        string(observation.scenarioId)
        out.writeBoolean(observation.mutantId != null)
        observation.mutantId?.let(::string)
        string(observation.canonicalGraphSha256); string(observation.fixtureSha256)
        reference(observation.artifact); reference(observation.observation)
        out.writeInt(observation.runtimePrerequisites.size)
        observation.runtimePrerequisites.forEach { string(it.id); string(it.version) }
        string(observation.outcome.name)
        require(bytes.size() <= MAX_STATEMENT_BYTES)
        return bytes.toByteArray()
    }
}
