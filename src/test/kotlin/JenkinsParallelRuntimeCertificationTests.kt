package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode
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

/** Synthetic protocol regressions; only the dedicated CI producer supplies runtime evidence. */
class JenkinsParallelRuntimeCertificationTests {
    companion object {
        private val scenarios = listOf(JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_APPROVE,
            JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_REJECT)
        private val captured by lazy { scenarios.associateWith {
            JenkinsCheckoutRuntimeCertification.prepare(File("."), "sha256:" + "a".repeat(64), it)
        } }
    }
    private val mapper = jacksonObjectMapper()
    private val left = "Approve left branch?"
    private val right = "Approve right branch?"
    private fun prepare(s: JenkinsCheckoutRuntimeCertification.Scenario) = captured.getValue(s).let { it.copy(evidence = it.evidence.toMutableMap()) }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun record(p: JenkinsCheckoutRuntimeCertification.Prepared): ObjectNode {
        fun error() = linkedMapOf("type" to "org.jenkinsci.plugins.workflow.steps.FlowInterruptedException", "result" to "ABORTED",
            "causes" to listOf("org.jenkinsci.plugins.workflow.support.steps.input.Rejection"), "inputMessage" to left)
        fun pending(messages: List<String>, early: Boolean) = linkedMapOf("messages" to messages, "paused" to true,
            "checkoutCount" to if (early) 1 else 0, "marker" to if (early) "selected\n" else null, "building" to true, "complete" to false)
        val runs = p.artifacts.map { (id, bytes) ->
            val reject = p.scenario == scenarios[1]
            val omitted = id == "omitted-parallel"
            val early = id == "early-successor"
            val failed = reject && id != "suppressed-rejection"
            linkedMapOf("id" to id, "result" to if (failed) "ABORTED" else "SUCCESS", "finished" to true,
                "buildNumber" to 1, "artifactSha256" to sha(bytes), "marker" to if (!failed || early) "selected\n" else null,
                "checkoutCount" to if (!failed || early) 1 else 0, "checkoutErrors" to emptyList<Any>(),
                "parallel" to linkedMapOf("inputMessages" to if (omitted) emptyList() else listOf(left, right),
                    "initialPending" to if (omitted) null else pending(if (id == "serialized-branches") listOf(left) else listOf(left, right), early),
                    "afterFirst" to if (omitted) null else pending(listOf(right), early),
                    "decisions" to if (omitted) emptyList() else listOf(mapOf("message" to left, "action" to if (reject) "reject" else "approve"),
                        mapOf("message" to right, "action" to "approve")),
                    "inputErrors" to if (reject) listOf(error()) else emptyList(), "terminalError" to if (failed) error() else null))
        }
        return mapper.valueToTree(mapOf("status" to "completed", "jenkins" to "2.580.1", "java" to "25",
            "plugins" to p.runtime.filter { it.id.startsWith("plugin:") }.associate { it.id.removePrefix("plugin:") to it.version }, "runs" to runs))
    }
    private fun runs(p: JenkinsCheckoutRuntimeCertification.Prepared, raw: ObjectNode) = JenkinsCheckoutRuntimeCertification.observations(p, mapper.writeValueAsBytes(raw))
    private fun assess(p: JenkinsCheckoutRuntimeCertification.Prepared, raw: ObjectNode): CertificationEvidenceViewResult {
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val trust = CertificationObservationTrust("f".repeat(32), mapOf("unit-test" to key.public),
            p.scenario.runIds.map { CertificationAuthorizedRun(it, p.bound.id, it.takeUnless { id -> id == "baseline" }, "unit-test") })
        val signed = runs(p, raw).map { observation ->
            val signer = Signature.getInstance("Ed25519"); signer.initSign(key.private)
            signer.update(CertificationObservationAuthentication.signingBytes("unit-test", trust.challenge, observation))
            SignedCertificationObservation("unit-test", trust.challenge, observation, signer.sign())
        }
        return CertificationEvidenceViews.assess(p.bundle, p.bundle.adapter, listOf(p.bound),
            ReferenceTargetProjections.nativeCatalogs.getValue("jenkins"), signed, trust, CertificationEvidenceResolver { p.evidence[it.id] })
    }

    @Test fun parallelGatesPreserveConcurrentPendingJoinAndRejection() {
        for (s in scenarios) {
            val p = prepare(s)
            assertContentEquals(p.bound.resolve(p.bound.artifact), p.artifacts.getValue("baseline"))
            assertContains(p.bound.subjects, CertificationSubject.Structural(TargetStructuralProjectionKind.PARALLEL))
            assertEquals(p.artifacts.size, p.artifacts.values.map(::sha).distinct().size)
            val result = assess(p, record(p))
            assertTrue(result.admission.valid, result.admission.findings.toString())
            assertFalse(assertNotNull(result.view).publicSupportPromoted)
            assertFalse(assertNotNull(result.view).portableExecution)
            val matrix = mapper.readTree(AdapterBehaviorMatrix.derive(result, s, CertificationEvidenceResolver { p.evidence[it.id] }).json())
            assertEquals(listOf("PARALLELISM"), matrix["rows"].filter { !it["behavior"].isNull }.map { it["construct"].asText() })
        }
    }

    @Test fun equalFinalResultsCannotHideSerializationOmissionOrEarlyContinuation() {
        val p = prepare(scenarios[0])
        for (index in 1..3) {
            val raw = record(p)
            assertEquals(raw["runs"][0]["result"], raw["runs"][index]["result"])
            assertEquals(raw["runs"][0]["marker"], raw["runs"][index]["marker"])
            (raw["runs"][0] as ObjectNode).set<JsonNode>("parallel", raw["runs"][index]["parallel"])
            assertFalse(assess(p, raw).admission.valid)
        }
    }

    @Test fun siblingWaitAndOriginalRejectionAreRequired() {
        val p = prepare(scenarios[1])
        assertTrue(runs(p, record(p)).all { it.outcome == CertificationRunOutcome.COMPLETED })
        val missingSibling = record(p)
        (missingSibling["runs"][0]["parallel"] as ObjectNode).putNull("afterFirst")
        assertFalse(assess(p, missingSibling).admission.valid)
        for (change in listOf<(ObjectNode) -> Unit>({ it.putNull("terminalError") },
            { (it["terminalError"] as ObjectNode).put("inputMessage", right) },
            { (it["terminalError"] as ObjectNode).put("type", "java.io.IOException") })) {
            val raw = record(p); change(raw["runs"][0]["parallel"] as ObjectNode)
            assertEquals(CertificationRunOutcome.INFRASTRUCTURE_FAILURE, runs(p, raw)[0].outcome)
            assertFalse(assess(p, raw).admission.valid)
        }
    }

    @Test fun malformedInventoriesSnapshotsDecisionsAndArtifactBindingsFailClosed() {
        val p = prepare(scenarios[0])
        for (change in listOf<(ObjectNode) -> Unit>({ it.remove("afterFirst") },
            { it.putArray("inputMessages").add(left).add(left) },
            { (it["initialPending"] as ObjectNode).put("paused", false) },
            { (it["initialPending"] as ObjectNode).put("checkoutCount", 4294967297L) },
            { (it["decisions"][0] as ObjectNode).put("action", "skip") },
            { (it["decisions"][1] as ObjectNode).put("message", left) })) {
            val raw = record(p); change(raw["runs"][0]["parallel"] as ObjectNode)
            assertFails { runs(p, raw) }
        }
        val raw = record(p); (raw["runs"][0] as ObjectNode).put("artifactSha256", "0".repeat(64))
        assertFails { runs(p, raw) }
        val original = p.artifacts.getValue("baseline").toString(Charsets.UTF_8)
        for (changed in listOf(original + original, original.replace("failFast: false", "failFast: true"), original.replace(left, "Changed?")))
            assertFails { JenkinsCheckoutRuntimeCertification.parallelArtifacts(changed, changed.toByteArray(), p.scenario) }
    }
}
