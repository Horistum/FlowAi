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

/** Synthetic protocol regressions; the separate CI job supplies real runtime evidence. */
class JenkinsApprovalRuntimeCertificationTests {
    companion object {
        private val scenarios = listOf(JenkinsCheckoutRuntimeCertification.Scenario.APPROVAL_APPROVE,
            JenkinsCheckoutRuntimeCertification.Scenario.APPROVAL_REJECT)
        private val captured by lazy { scenarios.associateWith {
            JenkinsCheckoutRuntimeCertification.prepare(File("."), "sha256:" + "a".repeat(64), it)
        } }
    }
    private val mapper = jacksonObjectMapper()
    private val input = CertificationSubject.Leaf("JENKINS_STEP", "input")
    private val rejection = mapOf("type" to "org.jenkinsci.plugins.workflow.steps.FlowInterruptedException",
        "result" to "ABORTED", "causes" to listOf("org.jenkinsci.plugins.workflow.support.steps.input.Rejection"))
    private fun prepare(s: JenkinsCheckoutRuntimeCertification.Scenario) = captured.getValue(s).let {
        it.copy(evidence = it.evidence.toMutableMap())
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun record(p: JenkinsCheckoutRuntimeCertification.Prepared): ObjectNode {
        val denied = p.scenario == scenarios[1]
        return mapper.valueToTree(mapOf<String, Any?>("status" to "completed", "jenkins" to "2.580.1", "java" to "25.0.1",
            "plugins" to mapOf<String, Any?>("git" to "5.10.1", "pipeline-model-definition" to "2.2293.v6e7193cec599",
                "timestamper" to "1.30", "pipeline-input-step" to "534.v352f0a_e98918"),
            "runs" to p.artifacts.map { (id, bytes) ->
                val omitted = id == "omitted-approval"
                val late = id == "late-approval"
                val aborted = denied && (id == "baseline" || late)
                val checkout = !aborted || late
                mapOf<String, Any?>("id" to id, "result" to if (aborted) "ABORTED" else "SUCCESS", "finished" to true,
                    "buildNumber" to 1, "artifactSha256" to sha(bytes),
                    "checkoutErrors" to emptyList<String>(), "checkoutCount" to if (checkout) 1 else 0,
                    "marker" to if (checkout) "selected\n" else null,
                    "approval" to mapOf<String, Any?>("inputCount" to if (omitted) 0 else 1,
                        "inputErrors" to if (denied && !omitted) listOf(rejection) else emptyList(),
                        "pending" to if (omitted) null else mapOf<String, Any?>("message" to "Allow the protected checkout?",
                            "activeInputs" to 1, "paused" to true, "checkoutCount" to if (late) 1 else 0,
                            "marker" to if (late) "selected\n" else null, "building" to true, "complete" to false),
                        "decision" to if (omitted) "none" else if (denied) "reject" else "approve",
                        "terminalError" to if (aborted) rejection + ("inputIndex" to 1) else null))
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
    private fun baseline(raw: ObjectNode) = raw["runs"][0] as ObjectNode
    private fun approval(raw: ObjectNode) = baseline(raw)["approval"] as ObjectNode

    @Test fun bothDecisionsBindTheSameApprovalGraphAndUnchangedCompilerArtifact() {
        val first = prepare(scenarios[0]); val second = prepare(scenarios[1])
        assertContentEquals(first.bound.resolve(first.bound.canonicalGraph), second.bound.resolve(second.bound.canonicalGraph))
        assertContentEquals(first.artifacts.getValue("baseline"), second.artifacts.getValue("baseline"))
        for (s in scenarios) {
            val p = prepare(s)
            assertContentEquals(File(JenkinsCheckoutRuntimeCertification.FIXTURE, s.source).readBytes(), p.bound.resolve(p.bound.fixture))
            assertContentEquals(p.bound.resolve(p.bound.artifact), p.artifacts.getValue("baseline"))
            val graph = mapper.readTree(p.bound.resolve(p.bound.canonicalGraph))
            assertEquals(1, graph["nodes"].count { it["kind"].asText() == "APPROVAL" })
            assertTrue(input in p.bound.subjects)
            assertTrue(CertificationSubject.Semantic("approval.manual") in p.bound.subjects)
            assertTrue(p.bound.subjects.none { it is CertificationSubject.Structural })
            assertEquals(p.scenario.runIds.size, p.artifacts.values.map(::sha).toSet().size)
            val original = p.artifacts.getValue("baseline").toString(Charsets.UTF_8)
            assertTrue(original.indexOf("input message:") < original.indexOf("git branch:"))
            val late = p.artifacts.getValue("late-approval").toString(Charsets.UTF_8)
            assertTrue(late.indexOf("input message:") > late.indexOf("git branch:"))
            assertFalse(p.artifacts.getValue("omitted-approval").toString(Charsets.UTF_8).contains("input message:"))
        }
    }

    @Test fun approvalAndRejectionAdmitOnlyBoundedNativeOccurrences() {
        for (s in scenarios) {
            val p = prepare(s); val result = assess(p, record(p))
            assertTrue(result.admission.valid, result.admission.findings.toString())
            val view = assertNotNull(result.view)
            assertEquals(CertificationScenarioShape.NATIVE_LEAF_ONLY, view.scenarios.single().shape)
            assertFalse(view.publicSupportPromoted); assertFalse(view.portableExecution)
            assertEquals(listOf(p.bound.id), view.coverage.single { it.subject == input }.scenarioIds)
            assertTrue(view.coverage.filter { it.subject !in p.bound.subjects }.all { it.scenarioIds.isEmpty() })
            assertTrue(runs(p, record(p)).all { it.outcome == CertificationRunOutcome.COMPLETED })
        }
    }

    @Test fun finalSuccessAndMarkerCannotHideMissingOrLateApproval() {
        val p = prepare(scenarios[0]); val raw = record(p)
        for (i in 1..2) {
            assertEquals(raw["runs"][0]["result"], raw["runs"][i]["result"])
            assertEquals(raw["runs"][0]["marker"], raw["runs"][i]["marker"])
            assertNotEquals(raw["runs"][0]["approval"], raw["runs"][i]["approval"])
            val changed = record(p); (changed["runs"][i] as ObjectNode).set<JsonNode>("approval", changed["runs"][0]["approval"])
            assertFalse(assess(p, changed).admission.valid)
        }
    }

    @Test fun protectedCheckoutMustWaitForTheMatchingPendingInput() {
        for (s in scenarios) for (change in listOf<(ObjectNode) -> Unit>(
            { (it["pending"] as ObjectNode).put("checkoutCount", 1) },
            { (it["pending"] as ObjectNode).put("marker", "selected\n") },
            { (it["pending"] as ObjectNode).put("message", "Approve a different operation?") },
            { it.put("decision", if (s == scenarios[0]) "reject" else "approve") })) {
            val p = prepare(s); val raw = record(p); change(approval(raw))
            assertFalse(assess(p, raw).admission.valid)
        }
    }

    @Test fun rejectionCannotBeSwallowedOrPermitTheProtectedCheckout() {
        val p = prepare(scenarios[1])
        for (change in listOf<(ObjectNode) -> Unit>(
            { baseline(it).put("checkoutCount", 1).put("marker", "selected\n") },
            { baseline(it).put("result", "SUCCESS"); approval(it).putNull("terminalError") },
            { approval(it).putArray("inputErrors") })) {
            val raw = record(p); change(raw)
            assertFalse(assess(p, raw).admission.valid)
        }
        val survived = record(p)
        (survived["runs"][3] as ObjectNode).put("result", "ABORTED").put("checkoutCount", 0).putNull("marker")
        (survived["runs"][3]["approval"] as ObjectNode).set<JsonNode>("terminalError", survived["runs"][0]["approval"]["terminalError"])
        assertFalse(assess(p, survived).admission.valid)
    }

    @Test fun abortedBuildRequiresRejectionFromTheNativeInput() {
        val p = prepare(scenarios[1])
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.putNull("terminalError") },
            { (it["terminalError"] as ObjectNode).putNull("inputIndex") },
            { (it["terminalError"] as ObjectNode).put("type", "java.io.IOException") },
            { (it["terminalError"] as ObjectNode).putArray("causes").add("jenkins.model.CauseOfInterruption\$UserInterruption") },
            { (it["inputErrors"][0] as ObjectNode).put("result", "FAILURE") })) {
            val raw = record(p); change(approval(raw))
            assertEquals(CertificationRunOutcome.INFRASTRUCTURE_FAILURE, runs(p, raw)[0].outcome)
            assertFalse(assess(p, raw).admission.valid)
        }
        for (status in listOf("FAILURE", "UNSTABLE", "NOT_BUILT")) {
            val raw = record(p); baseline(raw).put("result", status)
            assertFalse(assess(p, raw).admission.valid)
        }
        val approved = prepare(scenarios[0]); val unexpected = record(approved); baseline(unexpected).put("result", "ABORTED")
        assertFalse(assess(approved, unexpected).admission.valid)
    }

    @Test fun malformedPendingDecisionsAndErrorOriginsFailBeforeSigning() {
        val p = prepare(scenarios[1])
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.remove("pending") }, { it.putNull("pending") }, { it.put("inputCount", 4294967297L) },
            { it.put("inputCount", "1") }, { it.put("inputCount", 1.5) }, { it.put("inputCount", 2) },
            { it.put("decision", "automatic") }, { it.put("extra", true) },
            { (it["pending"] as ObjectNode).put("activeInputs", 0) },
            { (it["pending"] as ObjectNode).put("paused", false) },
            { (it["pending"] as ObjectNode).put("paused", "true") },
            { (it["pending"] as ObjectNode).put("checkoutCount", 4294967297L) },
            { (it["pending"] as ObjectNode).put("building", false) },
            { (it["pending"] as ObjectNode).put("complete", true) },
            { (it["pending"] as ObjectNode).put("complete", "false") },
            { (it["terminalError"] as ObjectNode).put("inputIndex", 2) },
            { (it["terminalError"] as ObjectNode).put("inputIndex", 4294967297L) },
            { (it["terminalError"] as ObjectNode).putArray("causes").add(1) },
            { it.remove("terminalError") })) {
            val raw = record(p); change(approval(raw)); assertFails { runs(p, raw) }
        }
        val raw = record(p); baseline(raw).put("finished", false); assertFails { runs(p, raw) }
    }

    @Test fun missingDuplicateCrossDecisionAndSubstitutedRunsAreRejected() {
        for (s in scenarios) {
            val p = prepare(s)
            val missing = record(p); (missing["runs"] as ArrayNode).remove(1); assertFails { runs(p, missing) }
            val duplicate = record(p); (duplicate["runs"][1] as ObjectNode).put("id", "baseline"); assertFails { runs(p, duplicate) }
            val substitution = record(p); baseline(substitution).put("artifactSha256", "0".repeat(64)); assertFails { runs(p, substitution) }
            assertFails { runs(p, record(prepare(scenarios.single { it != s }))) }
        }
    }

    @Test fun approvalBindingCannotBeOmittedOrBorrowedByACheckoutOnlyScenario() {
        val p = prepare(scenarios[0])
        assertFalse(assess(p.copy(bundle = p.bundle.copy(coverage = p.bundle.coverage.filter { it.subject != input })), record(p)).admission.valid)
        val checkout = JenkinsCheckoutRuntimeCertification.prepare(File("."), "sha256:" + "a".repeat(64))
        assertFalse(input in checkout.bound.subjects)
        assertTrue(checkout.bundle.coverage.single { it.subject == input }.scenarioIds.isEmpty())
        val forged = checkout.bundle.copy(coverage = checkout.bundle.coverage.map {
            if (it.subject == input) it.copy(scenarioIds = listOf(checkout.bound.id)) else it })
        val report = BoundAdapterCertificationAdmission.evaluate(forged, forged.adapter, listOf(checkout.bound),
            ReferenceTargetProjections.nativeCatalogs.getValue("jenkins"), emptyList(), CertificationEvidenceResolver { null })
        assertFalse(report.valid)
        assertTrue(report.findings.any { it.code == "BOUND_COVERAGE_MISMATCH" })
    }

    @Test fun inputPluginVersionIsAnExactRuntimePrerequisite() {
        val p = prepare(scenarios[0])
        assertTrue(CertificationRuntimePrerequisite("plugin:pipeline-input-step", "534.v352f0a_e98918") in p.runtime)
        for (version in listOf(null, "other")) {
            val raw = record(p); val plugins = raw["plugins"] as ObjectNode
            if (version == null) plugins.remove("pipeline-input-step") else plugins.put("pipeline-input-step", version)
            assertFails { runs(p, raw) }
        }
    }
}
