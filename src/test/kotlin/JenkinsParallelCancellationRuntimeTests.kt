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
class JenkinsParallelCancellationRuntimeTests {
    companion object {
        private val scenarios = listOf(JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_FAIL_FAST_APPROVE,
            JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_CANCEL)
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
        fun error(cancelled: Boolean = false) = linkedMapOf("type" to "org.jenkinsci.plugins.workflow.steps.FlowInterruptedException", "result" to "ABORTED",
            "causes" to listOf(if (cancelled) "org.jenkinsci.plugins.workflow.cps.steps.ParallelStep\$FailFastCause" else "org.jenkinsci.plugins.workflow.support.steps.input.Rejection"), "inputMessage" to if (cancelled) right else left)
        fun pending(messages: List<String>, early: Boolean) = linkedMapOf("messages" to messages, "paused" to true,
            "checkoutCount" to if (early) 1 else 0, "marker" to if (early) "selected\n" else null, "building" to true, "complete" to false)
        val runs = p.artifacts.map { (id, bytes) ->
            val reject = p.scenario == scenarios[1]
            val cancelled = reject && id != "disabled-cancellation"
            val early = id == "early-successor"
            val failed = reject && id != "suppressed-rejection"
            linkedMapOf("id" to id, "result" to if (failed) "ABORTED" else "SUCCESS", "finished" to true,
                "buildNumber" to 1, "artifactSha256" to sha(bytes), "marker" to if (!failed || early) "selected\n" else null,
                "checkoutCount" to if (!failed || early) 1 else 0, "checkoutErrors" to emptyList<Any>(),
                "parallel" to linkedMapOf("inputMessages" to listOf(left, right),
                    "initialPending" to pending(listOf(left, right), early),
                    "afterFirst" to if (cancelled) null else pending(listOf(right), early),
                    "decisions" to listOf(mapOf("message" to left, "action" to if (reject) "reject" else "approve")) +
                        (if (cancelled) emptyList() else listOf(mapOf("message" to right, "action" to "approve"))),
                    "inputErrors" to if (cancelled) listOf(error(), error(true)) else if (reject) listOf(error()) else emptyList(), "terminalError" to if (failed) error() else null))
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

    @Test fun cancellationAndSuccessfulJoinRequireAuthenticatedNativeEvidence() {
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

    @Test fun equalAbortedResultsCannotHideUndecidedSiblingCancellation() {
        val p = prepare(scenarios[1]); val raw = record(p)
        assertEquals(raw["runs"][0]["result"], raw["runs"][1]["result"])
        assertEquals(raw["runs"][0]["marker"], raw["runs"][1]["marker"])
        (raw["runs"][0] as ObjectNode).set<JsonNode>("parallel", raw["runs"][1]["parallel"])
        assertTrue(runs(p, raw).all { it.outcome == CertificationRunOutcome.COMPLETED })
        assertFalse(assess(p, raw).admission.valid)
        for (s in scenarios) {
            val prepared = prepare(s); val early = record(prepared)
            (early["runs"][0] as ObjectNode).set<JsonNode>("parallel", early["runs"].last()["parallel"])
            assertFalse(assess(prepared, early).admission.valid)
        }
    }

    @Test fun cancellationCauseAndOriginalRejectionOriginCannotBeForged() {
        val p = prepare(scenarios[1])
        for (change in listOf<(ObjectNode) -> Unit>(
            { (it["inputErrors"][1] as ObjectNode).putArray("causes").add("org.jenkinsci.plugins.workflow.support.steps.input.Rejection") },
            { (it["terminalError"] as ObjectNode).put("inputMessage", right) },
            { (it["terminalError"] as ObjectNode).put("type", "java.io.IOException") },
            { it.putNull("terminalError") })) {
            val raw = record(p); change(raw["runs"][0]["parallel"] as ObjectNode)
            assertEquals(CertificationRunOutcome.INFRASTRUCTURE_FAILURE, runs(p, raw)[0].outcome)
            assertFalse(assess(p, raw).admission.valid)
        }
        val decided = record(p)
        (decided["runs"][0]["parallel"]["decisions"] as com.fasterxml.jackson.databind.node.ArrayNode)
            .add(mapper.valueToTree<JsonNode>(mapOf("message" to right, "action" to "approve")))
        assertFalse(assess(p, decided).admission.valid)
        val missing = record(p)
        (missing["runs"][0]["parallel"]["inputErrors"] as com.fasterxml.jackson.databind.node.ArrayNode).remove(1)
        assertFalse(assess(p, missing).admission.valid)
    }

    @Test fun cancellationMutantsRequireExactPolicyAndArtifactBytes() {
        val p = prepare(scenarios[1]); val original = p.artifacts.getValue("baseline").toString(Charsets.UTF_8)
        assertContains(original, "failFast: true")
        assertEquals(original.replace("failFast: true", "failFast: false"), p.artifacts.getValue("disabled-cancellation").toString(Charsets.UTF_8))
        for (changed in listOf(original + original, original.replace("failFast: true", "failFast: false"), original.replace(left, "Changed?")))
            assertFails { JenkinsCheckoutRuntimeCertification.parallelArtifacts(changed, changed.toByteArray(), p.scenario) }
        val raw = record(p); (raw["runs"][0] as ObjectNode).put("artifactSha256", "0".repeat(64))
        assertFails { runs(p, raw) }
        val plugins = record(p); (plugins["plugins"] as ObjectNode).put("workflow-cps", "unknown")
        assertFails { runs(p, plugins) }
    }
}
