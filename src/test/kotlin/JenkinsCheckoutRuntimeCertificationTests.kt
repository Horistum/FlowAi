package org.flowlang.conformance

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import kotlin.test.*
import org.flowlang.adapters.certification.*
import org.flowlang.distribution.reference.ReferenceTargetProjections

/** Synthetic records test admission failures; the dedicated runtime job supplies real observations. */
class JenkinsCheckoutRuntimeCertificationTests {
    private val mapper = jacksonObjectMapper()
    private fun prepare() = JenkinsCheckoutRuntimeCertification.prepare(File("."), "sha256:" + "a".repeat(64))
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun record(p: JenkinsCheckoutRuntimeCertification.Prepared): ObjectNode = mapper.valueToTree(linkedMapOf(
        "status" to "completed", "jenkins" to "2.580.1", "java" to "25.0.1",
        "plugins" to mapOf("git" to "5.10.1", "pipeline-model-definition" to "2.2293.v6e7193cec599", "timestamper" to "1.30"),
        "runs" to p.artifacts.map { (id, bytes) -> mapOf("id" to id, "result" to "SUCCESS", "buildNumber" to 1,
            "artifactSha256" to sha(bytes), "marker" to when (id) { "baseline" -> "selected\n"; "substituted-branch" -> "alternate\n"; else -> null }) }))
    private fun runs(p: JenkinsCheckoutRuntimeCertification.Prepared, record: ObjectNode) =
        JenkinsCheckoutRuntimeCertification.observations(p, mapper.writeValueAsBytes(record))
    private fun admit(p: JenkinsCheckoutRuntimeCertification.Prepared, records: ObjectNode): CertificationAdmissionReport {
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val observations = runs(p, records)
        val trust = CertificationObservationTrust("a".repeat(32), mapOf("unit-test" to key.public),
            observations.map { CertificationAuthorizedRun(it.runId, it.scenarioId, it.mutantId, "unit-test") })
        val signed = observations.map { observation ->
            val signer = Signature.getInstance("Ed25519"); signer.initSign(key.private)
            signer.update(CertificationObservationAuthentication.signingBytes("unit-test", trust.challenge, observation))
            SignedCertificationObservation("unit-test", trust.challenge, observation, signer.sign())
        }
        return AuthenticatedAdapterCertificationAdmission.evaluate(p.bundle, p.bundle.adapter, listOf(p.bound),
            ReferenceTargetProjections.nativeCatalogs.getValue("jenkins"), signed, trust, CertificationEvidenceResolver { p.evidence[it.id] })
    }

    @Test fun fixtureBindsRealCompilerRenderingAndChangesOnlyTheIntendedNativeOperation() {
        val p = prepare()
        assertContentEquals(p.bound.resolve(p.bound.artifact), p.artifacts.getValue("baseline"))
        assertEquals(3, p.artifacts.values.map(::sha).toSet().size)
        assertTrue(p.bound.subjects.any { it is CertificationSubject.Leaf && it.reference == "git" })
        assertTrue(p.bound.subjects.none { it is CertificationSubject.Structural })
        assertTrue(p.artifacts.getValue("baseline").toString(Charsets.UTF_8).contains("git branch: 'selected'"))
        assertFalse(p.artifacts.getValue("omitted-checkout").toString(Charsets.UTF_8).contains("git branch:"))
        assertTrue(p.artifacts.getValue("substituted-branch").toString(Charsets.UTF_8).contains("git branch: 'alternate'"))
        assertTrue(p.bundle.coverage.filter { it.subject !in p.bound.subjects }.all { it.scenarioIds.isEmpty() })
    }

    @Test fun completeIndependentRecordsPassTheExistingAuthenticatedAdmission() {
        val p = prepare(); val report = admit(p, record(p))
        assertTrue(report.valid, report.findings.toString())
        assertEquals(listOf(p.bound.id), report.admittedScenarioIds)
    }

    @Test fun wrongWorkspaceBytesCannotSurviveAsPositiveEvidence() {
        val p = prepare(); val r = record(p)
        (r["runs"][0] as ObjectNode).put("marker", "alternate\n")
        val report = admit(p, r)
        assertFalse(report.valid); assertTrue(report.admittedScenarioIds.isEmpty())
        assertTrue(report.findings.any { it.code == "OBSERVATION_MISMATCH" })
    }

    @Test fun failedRuntimeCannotBeCountedAsAKilledMutant() {
        val p = prepare(); val r = record(p)
        (r["runs"][1] as ObjectNode).put("result", "FAILURE")
        val report = admit(p, r)
        assertFalse(report.valid); assertTrue(report.findings.any { it.code == "RUN_NOT_COMPLETED" })
    }

    @Test fun incompleteDuplicateAndSubstitutedArtifactRecordsFailBeforeSigning() {
        val p = prepare()
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.put("status", "infrastructure-failure") },
            { (it["runs"][0] as ObjectNode).put("id", "omitted-checkout") },
            { (it["runs"] as com.fasterxml.jackson.databind.node.ArrayNode).remove(0) },
            { (it["runs"][0] as ObjectNode).put("artifactSha256", "b".repeat(64)) },
            { (it["runs"][0] as ObjectNode).put("buildNumber", 2) },
            { (it["runs"][0] as ObjectNode).remove("marker") })) {
            val r = record(p); change(r); assertFails { runs(p, r) }
        }
    }

    @Test fun runtimeVersionDriftAndOversizedOrAmbiguousJsonFailClosed() {
        val p = prepare()
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.put("jenkins", "other") }, { it.put("java", "21.0.1") },
            { (it["plugins"] as ObjectNode).put("git", "other") },
            { (it["runs"][0] as ObjectNode).put("marker", "x".repeat(1025)) })) {
            val r = record(p); change(r); assertFails { runs(p, r) }
        }
        val raw = mapper.writeValueAsString(record(p))
        for (bytes in listOf(byteArrayOf(), ByteArray(65537), (raw + "{}").toByteArray(),
            raw.replace("\"status\":\"completed\"", "\"status\":\"failed\",\"status\":\"completed\"").toByteArray()))
            assertFails { JenkinsCheckoutRuntimeCertification.observations(p, bytes) }
    }

    @Test fun mutationRequiresExactlyOneRecognizedTargetOperation() {
        assertFails { JenkinsCheckoutRuntimeCertification.replaceOnce("missing", "git", "echo") }
        assertFails { JenkinsCheckoutRuntimeCertification.replaceOnce("git git", "git", "echo") }
        assertFails { JenkinsCheckoutRuntimeCertification.replaceOnce("git", "", "echo") }
        assertEquals("before echo after", JenkinsCheckoutRuntimeCertification.replaceOnce("before git after", "git", "echo"))
    }
}
