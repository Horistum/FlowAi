package org.flowlang.conformance

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import kotlin.test.*
import org.flowlang.adapters.certification.*
import org.flowlang.distribution.reference.ReferenceTargetProjections

/** Synthetic protocol tests complement, but never replace, the real Jenkins failure job. */
class JenkinsFailureRuntimeCertificationTests {
    private val mapper = jacksonObjectMapper()
    private val missingRevision = "Couldn't find any revision to build. Verify the repository and branch configuration for this job."
    private fun prepare() = JenkinsCheckoutRuntimeCertification.prepare(File("."), "sha256:" + "a".repeat(64),
        JenkinsCheckoutRuntimeCertification.Scenario.FAILURE)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun record(p: JenkinsCheckoutRuntimeCertification.Prepared): ObjectNode = mapper.valueToTree(linkedMapOf<String, Any?>(
        "status" to "completed", "jenkins" to "2.580.1", "java" to "25.0.1",
        "plugins" to mapOf("git" to "5.10.1", "pipeline-model-definition" to "2.2293.v6e7193cec599", "timestamper" to "1.30"),
        "runs" to p.artifacts.map { (id, bytes) -> mapOf<String, Any?>("id" to id, "result" to if (id == "baseline") "FAILURE" else "SUCCESS",
            "buildNumber" to 1, "finished" to true, "artifactSha256" to sha(bytes),
            "marker" to if (id == "baseline") null else "selected\n",
            "checkoutCount" to if (id == "suppressed-failure") 2 else 1,
            "checkoutErrors" to if (id == "omitted-failure") emptyList() else listOf(mapOf("type" to "hudson.AbortException", "message" to missingRevision))) }))
    private fun runs(p: JenkinsCheckoutRuntimeCertification.Prepared, record: ObjectNode) =
        JenkinsCheckoutRuntimeCertification.observations(p, mapper.writeValueAsBytes(record))
    private fun admit(p: JenkinsCheckoutRuntimeCertification.Prepared, records: ObjectNode): CertificationAdmissionReport {
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val observations = runs(p, records)
        val trust = CertificationObservationTrust("b".repeat(32), mapOf("unit-test" to key.public),
            observations.map { CertificationAuthorizedRun(it.runId, it.scenarioId, it.mutantId, "unit-test") })
        val signed = observations.map { observation ->
            val signer = Signature.getInstance("Ed25519"); signer.initSign(key.private)
            signer.update(CertificationObservationAuthentication.signingBytes("unit-test", trust.challenge, observation))
            SignedCertificationObservation("unit-test", trust.challenge, observation, signer.sign())
        }
        return AuthenticatedAdapterCertificationAdmission.evaluate(p.bundle, p.bundle.adapter, listOf(p.bound),
            ReferenceTargetProjections.nativeCatalogs.getValue("jenkins"), signed, trust, CertificationEvidenceResolver { p.evidence[it.id] })
    }

    @Test fun fixturePreservesCanonicalFailureDependencyAndUnchangedPositiveArtifact() {
        val p = prepare()
        val graph = mapper.readTree(p.bound.resolve(p.bound.canonicalGraph))
        assertEquals("PROPAGATE", graph["workflows"][0]["failurePolicy"]["disposition"].asText())
        assertEquals(2, graph["nodes"].size()); assertEquals(1, graph["dependencyEdges"].size())
        assertContentEquals(p.bound.resolve(p.bound.artifact), p.artifacts.getValue("baseline"))
        val baseline = p.artifacts.getValue("baseline").toString(Charsets.UTF_8)
        assertTrue(baseline.indexOf("branch: 'missing-revision'") < baseline.indexOf("branch: 'selected'"))
        assertFalse(p.artifacts.getValue("omitted-failure").toString(Charsets.UTF_8).contains("branch: 'missing-revision'"))
        assertTrue(p.artifacts.getValue("suppressed-failure").toString(Charsets.UTF_8).contains("catchError(buildResult: 'SUCCESS'"))
        assertEquals(3, p.artifacts.values.map(::sha).toSet().size)
        assertTrue(p.bound.subjects.none { it is CertificationSubject.Structural })
        assertTrue(p.bundle.coverage.filter { it.subject !in p.bound.subjects }.all { it.scenarioIds.isEmpty() })
    }

    @Test fun deliberateNativeFailureIsCompletedBehaviorAndBothMutantsAreDistinguished() {
        val p = prepare(); val records = record(p)
        assertTrue(runs(p, records).all { it.outcome == CertificationRunOutcome.COMPLETED })
        val report = admit(p, records)
        assertTrue(report.valid, report.findings.toString())
        assertEquals(listOf("jenkins-failure-runtime"), report.admittedScenarioIds)
    }

    @Test fun dependentCheckoutExecutionCannotBeHiddenBehindAFailedBuildResult() {
        val p = prepare(); val records = record(p)
        (records["runs"][0] as ObjectNode).put("marker", "selected\n").put("checkoutCount", 2)
        val report = admit(p, records)
        assertFalse(report.valid); assertTrue(report.findings.any { it.code == "OBSERVATION_MISMATCH" })
    }

    @Test fun successResultCannotImpersonateTheRequiredFailure() {
        val p = prepare(); val records = record(p)
        (records["runs"][0] as ObjectNode).put("result", "SUCCESS")
        assertFalse(admit(p, records).valid)
    }

    @Test fun infrastructureAndUnexpectedNativeErrorsCannotSatisfyTheFailureScenario() {
        val p = prepare()
        for (change in listOf<(ObjectNode) -> Unit>(
            { (it["checkoutErrors"][0] as ObjectNode).put("type", "java.io.IOException") },
            { (it["checkoutErrors"][0] as ObjectNode).put("message", "Connection refused") },
            { (it["checkoutErrors"] as ArrayNode).removeAll() },
            { it.put("result", "ABORTED") }, { it.put("result", "NOT_BUILT") }, { it.put("result", "UNSTABLE") })) {
            val records = record(p); change(records["runs"][0] as ObjectNode)
            val report = admit(p, records)
            assertFalse(report.valid); assertTrue(report.findings.any { it.code == "RUN_NOT_COMPLETED" })
        }
    }

    @Test fun unsuccessfulMutantsCannotBecomeEvidenceAgainstTheBaseline() {
        val p = prepare()
        for (index in 1..2) for (result in listOf("FAILURE", "ABORTED", "NOT_BUILT")) {
            val records = record(p); (records["runs"][index] as ObjectNode).put("result", result)
            assertFalse(admit(p, records).valid)
        }
    }

    @Test fun unchangedObservableBehaviorMeansTheMutantSurvived() {
        val p = prepare(); val records = record(p)
        val mutant = records["runs"][2] as ObjectNode
        mutant.put("result", "FAILURE").putNull("marker"); mutant.put("checkoutCount", 1)
        val observations = runs(p, records)
        assertEquals(observations[0].observation.sha256, observations[2].observation.sha256)
        val report = admit(p, records)
        assertFalse(report.valid); assertTrue(report.findings.any { it.code == "OBSERVATION_MISMATCH" })
    }

    @Test fun incompleteOrUnboundedNativeStepRecordsFailBeforeSigning() {
        val p = prepare()
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.remove("finished") }, { it.put("finished", false) }, { it.put("finished", "true") },
            { it.remove("checkoutCount") }, { it.put("checkoutCount", -1) }, { it.put("checkoutCount", 3) },
            { it.put("checkoutCount", 4294967297L) }, { it.put("checkoutCount", 1.1) },
            { it.put("buildNumber", 4294967297L) }, { it.remove("checkoutErrors") },
            { it.put("checkoutErrors", "not-an-array") }, { it.put("checkoutCount", 0) },
            { (it["checkoutErrors"][0] as ObjectNode).put("message", "x".repeat(1025)) },
            { (it["checkoutErrors"][0] as ObjectNode).put("type", "") },
            { (it["checkoutErrors"][0] as ObjectNode).put("unexpected", true) })) {
            val records = record(p); change(records["runs"][0] as ObjectNode)
            assertFails { runs(p, records) }
        }
    }
}
