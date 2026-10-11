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
class JenkinsLoopRuntimeCertificationTests {
    companion object {
        private val scenarios = listOf(JenkinsCheckoutRuntimeCertification.Scenario.LOOP_ORDER,
            JenkinsCheckoutRuntimeCertification.Scenario.LOOP_EMPTY, JenkinsCheckoutRuntimeCertification.Scenario.LOOP_FAILURE)
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
        val error = mapOf("type" to "hudson.AbortException", "message" to missing)
        return mapper.valueToTree(mapOf("status" to "completed", "jenkins" to "2.580.1", "java" to "25.0.1",
            "plugins" to mapOf("git" to "5.10.1", "pipeline-model-definition" to "2.2293.v6e7193cec599", "timestamper" to "1.30",
                "workflow-basic-steps" to "1079.vce64b_a_929c5a_"),
            "runs" to p.artifacts.map { (id, bytes) ->
                val branches = when (p.scenario) {
                    scenarios[0] -> when (id) {
                        "baseline" -> listOf("selected", "selected", "alternate", "selected")
                        "reversed-items" -> listOf("alternate", "selected", "selected", "selected")
                        else -> listOf("selected", "alternate", "selected")
                    }
                    scenarios[1] -> when (id) {
                        "baseline" -> listOf("selected"); "forced-iteration" -> listOf("missing-revision"); else -> emptyList()
                    }
                    else -> when (id) {
                        "baseline" -> listOf("selected", "missing-revision")
                        "omitted-failure" -> listOf("selected", "alternate", "selected")
                        else -> listOf("selected", "missing-revision", "alternate", "selected")
                    }
                }
                val index = branches.indexOf("missing-revision") + 1
                val failure = index > 0 && id != "suppressed-failure"
                mapOf("id" to id, "result" to if (failure) "FAILURE" else "SUCCESS", "finished" to true,
                    "buildNumber" to 1, "artifactSha256" to sha(bytes), "checkoutBranches" to branches,
                    "checkoutErrors" to if (index > 0) listOf(error) else emptyList(),
                    "checkoutErrorIndices" to if (index > 0) listOf(index) else emptyList(),
                    "terminalError" to if (failure) error + ("checkoutIndex" to index) else null,
                    "checkoutCount" to branches.size, "marker" to if (p.scenario == scenarios[1] && id != "baseline") null else "selected\n")
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

    @Test fun literalLoopPreservesOrderEmptyInputAndFailure() {
        for (s in scenarios) {
            val p = prepare(s)
            assertContentEquals(p.bound.resolve(p.bound.artifact), p.artifacts.getValue("baseline"))
            val graph = mapper.readTree(p.bound.resolve(p.bound.canonicalGraph))
            assertEquals(1, graph["nodes"].count { it["kind"].asText() == "LOOP" })
            assertContains(p.bound.subjects, CertificationSubject.Structural(TargetStructuralProjectionKind.LOOP))
            assertContains(p.artifacts.getValue("baseline").toString(Charsets.UTF_8), "for (def item in ")
            assertEquals(3, p.artifacts.values.map(::sha).toSet().size)
            val result = assess(p, record(p))
            assertTrue(result.admission.valid, result.admission.findings.toString())
            assertFalse(assertNotNull(result.view).publicSupportPromoted)
            assertFalse(assertNotNull(result.view).portableExecution)
            val matrix = mapper.readTree(AdapterBehaviorMatrix.derive(result, s, CertificationEvidenceResolver { p.evidence[it.id] }).json())
            assertEquals(listOf("LOOPS"), matrix["rows"].filter { !it["behavior"].isNull }.map { it["construct"].asText() })
        }
    }

    @Test fun equalResultAndMarkerCannotHideReorderedOrDeduplicatedIterations() {
        val p = prepare(scenarios[0])
        for (i in 1..2) {
            val raw = record(p); val mutant = raw["runs"][i] as ObjectNode
            assertEquals(raw["runs"][0]["result"], mutant["result"])
            assertEquals(raw["runs"][0]["marker"], mutant["marker"])
            for (field in listOf("checkoutCount", "checkoutBranches")) mutant.set<JsonNode>(field, raw["runs"][0][field])
            assertFalse(assess(p, raw).admission.valid)
        }
        val raw = record(p)
        (raw["runs"][0] as ObjectNode).putArray("checkoutBranches").add("alternate").add("selected").add("selected").add("selected")
        assertFalse(assess(p, raw).admission.valid)
    }

    @Test fun emptyLoopSkipsItsFailingBodyAndRunsTheSuccessor() {
        val p = prepare(scenarios[1]); val good = record(p)
        assertTrue(runs(p, good).all { it.outcome == CertificationRunOutcome.COMPLETED })
        assertEquals(listOf("selected"), good["runs"][0]["checkoutBranches"].map { it.asText() })
        for (index in 1..2) {
            val raw = record(p); val baseline = raw["runs"][0] as ObjectNode
            for (field in listOf("result", "marker", "checkoutCount", "checkoutBranches", "checkoutErrors", "checkoutErrorIndices", "terminalError"))
                baseline.set<JsonNode>(field, raw["runs"][index][field])
            assertFalse(assess(p, raw).admission.valid)
        }
    }

    @Test fun failureOriginMustPropagateBeforeLaterIterationAndSuccessor() {
        val p = prepare(scenarios[2])
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
        val raw = record(p); val baseline = raw["runs"][0] as ObjectNode
        baseline.put("checkoutCount", 3); (baseline["checkoutBranches"] as ArrayNode).add("alternate")
        assertFalse(assess(p, raw).admission.valid)
    }

    @Test fun malformedMissingOrSubstitutedEvidenceFailsBeforeSigning() {
        val p = prepare(scenarios[2])
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.remove("checkoutBranches") }, { it.putArray("checkoutBranches").add("selected") },
            { it.putArray("checkoutBranches").add("selected").add(2) },
            { it.putArray("checkoutBranches").add("selected").add("x".repeat(257)) },
            { it.remove("terminalError") }, { it.remove("checkoutErrorIndices") },
            { it.putArray("checkoutErrorIndices").add(1).add(1) },
            { it.put("checkoutCount", 4294967297L) }, { it.put("checkoutCount", 5) },
            { it.put("finished", false) }, { it.put("artifactSha256", "0".repeat(64)) })) {
            val raw = record(p); change(raw["runs"][0] as ObjectNode)
            assertFails { runs(p, raw) }
        }
        val raw = record(p); (raw["runs"][1] as ObjectNode).put("id", "baseline")
        assertFails { runs(p, raw) }
    }

    @Test fun mutantGenerationRequiresOneIntactCompilerLoopBoundary() {
        for (s in scenarios) {
            val p = prepare(s); val original = p.artifacts.getValue("baseline").toString(Charsets.UTF_8)
            for (changed in listOf(original.replace("for (def item in", "for (def other in"), original + original,
                original.replace("branch: 'selected'", "branch: 'unexpected'")))
                assertFails { JenkinsCheckoutRuntimeCertification.loopArtifacts(changed, changed.toByteArray(), p.scenario) }
        }
    }
}
