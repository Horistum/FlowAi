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
class JenkinsMatchRuntimeCertificationTests {
    companion object {
        private val scenarios = listOf(JenkinsCheckoutRuntimeCertification.Scenario.MATCH_SELECTED,
            JenkinsCheckoutRuntimeCertification.Scenario.MATCH_DEFAULT, JenkinsCheckoutRuntimeCertification.Scenario.MATCH_EMPTY, JenkinsCheckoutRuntimeCertification.Scenario.MATCH_FAILURE)
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
        val root = mapper.createObjectNode().put("status", "completed").put("jenkins", "2.580.1").put("java", "25.0.1")
        root.putObject("plugins").put("git", "5.10.1").put("pipeline-model-definition", "2.2293.v6e7193cec599")
            .put("timestamper", "1.30").put("workflow-basic-steps", "1079.vce64b_a_929c5a_")
        val records = root.putArray("runs")
        for ((id, bytes) in p.artifacts) {
            val branches: List<String> = when (p.scenario) {
                scenarios[0] -> when (id) {
                    "baseline" -> listOf("selected", "selected")
                    "flattened-cases" -> listOf("alternate", "selected", "alternate", "selected")
                    else -> listOf("selected")
                }
                scenarios[1] -> when (id) {
                    "baseline" -> listOf("alternate", "selected")
                    "substituted-default" -> listOf("selected", "selected")
                    else -> listOf("selected")
                }
                scenarios[2] -> when (id) {
                    "omitted-successor" -> emptyList()
                    "empty-fallthrough" -> listOf("alternate", "selected")
                    else -> listOf("selected")
                }
                else -> when (id) {
                    "baseline" -> listOf("missing-revision")
                    "omitted-failure" -> listOf("alternate", "selected")
                    else -> listOf("missing-revision", "alternate", "selected")
                }
            }
            val index = branches.indexOf("missing-revision") + 1
            val failure = index > 0 && id != "suppressed-failure"
            val row = records.addObject().put("id", id).put("result", if (failure) "FAILURE" else "SUCCESS")
                .put("finished", true).put("buildNumber", 1).put("artifactSha256", sha(bytes)).put("checkoutCount", branches.size)
            if (failure || branches.isEmpty()) row.putNull("marker") else row.put("marker", "selected\n")
            val observedBranches = row.putArray("checkoutBranches")
            branches.forEach { observedBranches.add(it) }
            val errors = row.putArray("checkoutErrors")
            val indices = row.putArray("checkoutErrorIndices")
            if (index > 0) {
                errors.addObject().put("type", "hudson.AbortException").put("message", missing)
                indices.add(index)
            }
            if (failure) row.putObject("terminalError").put("type", "hudson.AbortException").put("message", missing).put("checkoutIndex", index)
            else row.putNull("terminalError")
        }
        return root
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

    @Test fun literalMatchPreservesSelectedDefaultEmptyAndFailure() {
        for (s in scenarios) {
            val p = prepare(s)
            assertContentEquals(p.bound.resolve(p.bound.artifact), p.artifacts.getValue("baseline"))
            val graph = mapper.readTree(p.bound.resolve(p.bound.canonicalGraph))
            assertEquals(1, graph["nodes"].count { it["kind"].asText() == "MATCH" })
            assertContains(p.bound.subjects, CertificationSubject.Structural(TargetStructuralProjectionKind.MATCH))
            assertContains(p.artifacts.getValue("baseline").toString(Charsets.UTF_8), "} else {")
            assertEquals(3, p.artifacts.values.map(::sha).toSet().size)
            val result = assess(p, record(p))
            assertTrue(result.admission.valid, result.admission.findings.toString())
            assertFalse(assertNotNull(result.view).publicSupportPromoted)
            assertFalse(assertNotNull(result.view).portableExecution)
            val matrix = mapper.readTree(AdapterBehaviorMatrix.derive(result, s, CertificationEvidenceResolver { p.evidence[it.id] }).json())
            assertEquals(listOf("MATCH"), matrix["rows"].filter { !it["behavior"].isNull }.map { it["construct"].asText() })
        }
    }

    @Test fun equalResultAndMarkerCannotHideFlattenedOrOmittedBranches() {
        val p = prepare(scenarios[0])
        for (i in 1..2) {
            val raw = record(p); val mutant = raw["runs"][i] as ObjectNode
            assertEquals(raw["runs"][0]["result"], mutant["result"])
            assertEquals(raw["runs"][0]["marker"], mutant["marker"])
            for (field in listOf("checkoutCount", "checkoutBranches")) mutant.set<JsonNode>(field, raw["runs"][0][field])
            assertFalse(assess(p, raw).admission.valid)
        }
        val raw = record(p)
        (raw["runs"][0] as ObjectNode).putArray("checkoutBranches").add("alternate").add("selected")
        assertFalse(assess(p, raw).admission.valid)
    }

    @Test fun defaultBranchCannotBeSubstitutedDespiteEqualSuccessAndFinalMarker() {
        val p = prepare(scenarios[1]); val raw = record(p)
        val baseline = raw["runs"][0] as ObjectNode
        val substituted = raw["runs"][1]
        assertEquals(baseline["result"], substituted["result"])
        assertEquals(baseline["marker"], substituted["marker"])
        assertEquals(baseline["checkoutCount"], substituted["checkoutCount"])
        baseline.set<JsonNode>("checkoutBranches", substituted["checkoutBranches"])
        assertFalse(assess(p, raw).admission.valid)
    }

    @Test fun emptySelectedCaseSuppressesDefaultAndRunsTheSuccessor() {
        val p = prepare(scenarios[2]); val good = record(p)
        assertTrue(runs(p, good).all { it.outcome == CertificationRunOutcome.COMPLETED })
        assertEquals(listOf("selected"), good["runs"][0]["checkoutBranches"].map { it.asText() })
        for (index in 1..2) {
            val raw = record(p); val baseline = raw["runs"][0] as ObjectNode
            for (field in listOf("result", "marker", "checkoutCount", "checkoutBranches", "checkoutErrors", "checkoutErrorIndices", "terminalError"))
                baseline.set<JsonNode>(field, raw["runs"][index][field])
            assertFalse(assess(p, raw).admission.valid)
        }
    }

    @Test fun failureOriginMustPropagateBeforeLaterBodyAndSuccessor() {
        val p = prepare(scenarios[3])
        assertTrue(runs(p, record(p)).all { it.outcome == CertificationRunOutcome.COMPLETED })
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.putNull("terminalError") },
            { (it["terminalError"] as ObjectNode).putNull("checkoutIndex") },
            { (it["terminalError"] as ObjectNode).put("type", "java.io.IOException") },
            { (it["checkoutErrors"][0] as ObjectNode).put("message", "Connection refused") })) {
            val raw = record(p); change(raw["runs"][0] as ObjectNode)
            assertEquals(CertificationRunOutcome.INFRASTRUCTURE_FAILURE, runs(p, raw)[0].outcome)
            assertFalse(assess(p, raw).admission.valid)
        }
        val raw = record(p); val baseline = raw["runs"][0] as ObjectNode
        baseline.put("checkoutCount", 2); (baseline["checkoutBranches"] as ArrayNode).add("alternate")
        assertFalse(assess(p, raw).admission.valid)
    }

    @Test fun malformedMissingOrSubstitutedEvidenceFailsBeforeSigning() {
        val p = prepare(scenarios[3])
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.remove("checkoutBranches") }, { it.putArray("checkoutBranches") },
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

    @Test fun mutantGenerationRequiresOneIntactCompilerMatchBoundary() {
        for (s in scenarios) {
            val p = prepare(s); val original = p.artifacts.getValue("baseline").toString(Charsets.UTF_8)
            for (changed in listOf(original.replace("if (false)", "if (true)"), original + original,
                original.replace("branch: 'selected'", "branch: 'unexpected'")))
                assertFails { JenkinsCheckoutRuntimeCertification.matchArtifacts(changed, changed.toByteArray(), p.scenario) }
        }
    }
}
