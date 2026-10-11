package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode
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
import org.flowlang.generators.manifest.TargetStructuralProjectionKind

/** Protocol regressions only; the dedicated CI job supplies real Jenkins behavior evidence. */
class JenkinsRetryRuntimeCertificationTests {
    companion object {
        private val scenarios = listOf(JenkinsCheckoutRuntimeCertification.Scenario.RETRY_FAILURE,
            JenkinsCheckoutRuntimeCertification.Scenario.RETRY_SUCCESS)
        private val captured by lazy { scenarios.associateWith {
            JenkinsCheckoutRuntimeCertification.prepare(File("."), "sha256:" + "a".repeat(64), it)
        } }
    }
    private val mapper = jacksonObjectMapper()
    private val missing = "Couldn't find any revision to build. Verify the repository and branch configuration for this job."
    private fun prepare(s: JenkinsCheckoutRuntimeCertification.Scenario) = captured.getValue(s).let {
        it.copy(evidence = it.evidence.toMutableMap())
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun record(p: JenkinsCheckoutRuntimeCertification.Prepared): ObjectNode {
        val failure = p.scenario == scenarios[0]
        val error = mapOf("type" to "hudson.AbortException", "message" to missing)
        return mapper.valueToTree(mapOf("status" to "completed", "jenkins" to "2.580.1", "java" to "25.0.1",
            "plugins" to mapOf("git" to "5.10.1", "pipeline-model-definition" to "2.2293.v6e7193cec599", "timestamper" to "1.30",
                "workflow-basic-steps" to "1079.vce64b_a_929c5a_"),
            "runs" to p.artifacts.map { (id, bytes) ->
                val count = if (failure) when (id) { "baseline" -> 3; "flattened-retry" -> 1; else -> 4 }
                    else when (id) { "baseline" -> 2; "omitted-body" -> 1; else -> 4 }
                mapOf("id" to id, "result" to if (failure) "FAILURE" else "SUCCESS", "finished" to true,
                    "buildNumber" to 1, "artifactSha256" to sha(bytes),
                    "checkoutErrors" to if (failure) List(count) { error } else emptyList(),
                    "checkoutErrorIndices" to if (failure) (1..count).toList() else emptyList(),
                    "terminalError" to if (failure) error + ("checkoutIndex" to count) else null,
                    "checkoutCount" to count, "marker" to if (failure) null else "alternate\n")
            }))
    }
    private fun runs(p: JenkinsCheckoutRuntimeCertification.Prepared, raw: ObjectNode) =
        JenkinsCheckoutRuntimeCertification.observations(p, mapper.writeValueAsBytes(raw))
    private fun assess(p: JenkinsCheckoutRuntimeCertification.Prepared, raw: ObjectNode): CertificationEvidenceViewResult {
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val observations = runs(p, raw)
        val trust = CertificationObservationTrust("f".repeat(32), mapOf("unit-test" to key.public),
            p.scenario.runIds.map { CertificationAuthorizedRun(it, p.bound.id, it.takeUnless { id -> id == "baseline" }, "unit-test") })
        val signed = observations.map { observation ->
            val signer = Signature.getInstance("Ed25519"); signer.initSign(key.private)
            signer.update(CertificationObservationAuthentication.signingBytes("unit-test", trust.challenge, observation))
            SignedCertificationObservation("unit-test", trust.challenge, observation, signer.sign())
        }
        return CertificationEvidenceViews.assess(p.bundle, p.bundle.adapter, listOf(p.bound),
            ReferenceTargetProjections.nativeCatalogs.getValue("jenkins"), signed, trust,
            CertificationEvidenceResolver { p.evidence[it.id] })
    }

    @Test fun boundedRetryPreservesAttemptLimitAndEarlySuccess() {
        for (s in scenarios) {
            val p = prepare(s)
            assertContentEquals(p.bound.resolve(p.bound.artifact), p.artifacts.getValue("baseline"))
            val graph = mapper.readTree(p.bound.resolve(p.bound.canonicalGraph))
            assertEquals(1, graph["nodes"].count { it["kind"].asText() == "RETRY" })
            assertEquals(setOf(CertificationSubject.Structural(TargetStructuralProjectionKind.RETRY)),
                p.bound.subjects.filterIsInstance<CertificationSubject.Structural>().toSet())
            assertContains(p.artifacts.getValue("baseline").toString(Charsets.UTF_8), "retry(3) {")
            assertEquals(3, p.artifacts.values.map(::sha).toSet().size)
            val result = assess(p, record(p))
            assertTrue(result.admission.valid, result.admission.findings.toString())
            assertFalse(assertNotNull(result.view).publicSupportPromoted)
            assertFalse(assertNotNull(result.view).portableExecution)
        }
    }

    @Test fun signedMutantsCannotHideWrongAttemptsBehindEqualResults() {
        for (s in scenarios) {
            val p = prepare(s)
            for (i in 1..2) {
                val raw = record(p); val mutant = raw["runs"][i] as ObjectNode
                assertEquals(raw["runs"][0]["result"], mutant["result"])
                assertEquals(raw["runs"][0]["marker"], mutant["marker"])
                for (field in listOf("checkoutCount", "checkoutErrors", "checkoutErrorIndices", "terminalError"))
                    mutant.set<JsonNode>(field, raw["runs"][0][field])
                assertFalse(assess(p, raw).admission.valid)
            }
        }
    }

    @Test fun exhaustionMustPropagateTheLastNativeFailureWithoutContinuation() {
        val p = prepare(scenarios[0])
        assertTrue(runs(p, record(p)).all { it.outcome == CertificationRunOutcome.COMPLETED })
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.putNull("terminalError") },
            { (it["terminalError"] as ObjectNode).put("checkoutIndex", 1) },
            { (it["terminalError"] as ObjectNode).put("type", "java.io.IOException") },
            { (it["checkoutErrors"][0] as ObjectNode).put("message", "Connection refused") })) {
            val raw = record(p); change(raw["runs"][0] as ObjectNode)
            assertEquals(CertificationRunOutcome.INFRASTRUCTURE_FAILURE, runs(p, raw)[0].outcome)
            assertFalse(assess(p, raw).admission.valid)
        }
        val continued = record(p); (continued["runs"][0] as ObjectNode).put("marker", "alternate\n").put("result", "SUCCESS")
        assertFalse(assess(p, continued).admission.valid)
    }

    @Test fun successCannotRepeatOrOmitTheBody() {
        val p = prepare(scenarios[1])
        for (count in listOf(1, 3, 4)) {
            val raw = record(p); (raw["runs"][0] as ObjectNode).put("checkoutCount", count)
            assertFalse(assess(p, raw).admission.valid)
        }
        val raw = record(p); (raw["runs"][0] as ObjectNode).put("marker", "selected\n")
        assertFalse(assess(p, raw).admission.valid)
    }

    @Test fun malformedMissingOrSubstitutedEvidenceFailsBeforeSigning() {
        val p = prepare(scenarios[0])
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.remove("terminalError") }, { it.remove("checkoutErrorIndices") },
            { it.putArray("checkoutErrorIndices").add(1).add(1).add(3) },
            { it.putArray("checkoutErrorIndices").add(1).add(2) },
            { it.put("checkoutCount", 4294967297L) }, { it.put("checkoutCount", 5) },
            { it.put("finished", false) }, { it.put("artifactSha256", "0".repeat(64)) })) {
            val raw = record(p); change(raw["runs"][0] as ObjectNode)
            assertFails { runs(p, raw) }
        }
        val raw = record(p); (raw["runs"][1] as ObjectNode).put("id", "baseline")
        assertFails { runs(p, raw) }
    }

    @Test fun mutantGenerationRequiresOneIntactCompilerRetryBoundary() {
        val p = prepare(scenarios[0]); val original = p.artifacts.getValue("baseline").toString(Charsets.UTF_8)
        for (changed in listOf(original.replace("retry(3)", "retry(2)"), original + original,
            original.replace("git branch: 'alternate'", "git branch: 'selected'")))
            assertFails { JenkinsCheckoutRuntimeCertification.retryArtifacts(changed, changed.toByteArray(), p.scenario) }
    }
}
