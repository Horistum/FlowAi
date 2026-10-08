package org.flowlang.adapters.evidence

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.*
import org.flowlang.adapters.certification.*

class CertificationSigningProtocolTests {
    private val run = CertificationExecutionObservation("run", CertificationAdapterIdentity("jenkins", "adapter", "1", "a".repeat(64)),
        "scenario", null, "b".repeat(64), "c".repeat(64), CertificationEvidenceReference("artifact", "d".repeat(64), 7),
        CertificationEvidenceReference("observed☃", "e".repeat(64), 9), listOf(CertificationRuntimePrerequisite("runtime", "2")),
        CertificationRunOutcome.COMPLETED)

    @Test fun independentPythonEd25519VectorPinsEncodingAndVerification() {
        // Generated independently with Python struct.pack('>i') and cryptography Ed25519.
        // Fixture seed bytes 0..31 are public test data, never an operational runner key.
        val bytes = CertificationObservationAuthentication.signingBytes("runner", "A".repeat(32), run)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("aaedaa837b94ad2c8b2f05e3ee0e05b25561a6a59ca3873486d4ff398e5a6e18", digest)
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(
            "MCowBQYDK2VwAyEAA6EHv/POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg=")))
        val signature = Base64.getDecoder().decode("yf5RjQVKXS0WwTTNvLPRnMUhXzeEZ8ZUfE+PG2Mg6xK5bK6l/fabmwEMicUKti59lKoioLKVWR7qp20LCEHLCA==")
        val verifier = Signature.getInstance("Ed25519"); verifier.initVerify(publicKey); verifier.update(bytes)
        assertTrue(verifier.verify(signature))
    }

    @Test fun fieldBoundariesNullabilityAndRuntimeOrderCannotCollide() {
        fun bytes(r: CertificationExecutionObservation) = CertificationObservationAuthentication.signingBytes("runner", "A".repeat(32), r)
        assertFalse(bytes(run.copy(runId = "a", scenarioId = "bc")).contentEquals(bytes(run.copy(runId = "ab", scenarioId = "c"))))
        assertFalse(bytes(run).contentEquals(bytes(run.copy(mutantId = ""))))
        val runtime = listOf(CertificationRuntimePrerequisite("one", "1"), CertificationRuntimePrerequisite("two", "2"))
        assertFalse(bytes(run.copy(runtimePrerequisites = runtime)).contentEquals(bytes(run.copy(runtimePrerequisites = runtime.reversed()))))
        assertFails { bytes(run.copy(runId = "\uD800")) }
    }
}
