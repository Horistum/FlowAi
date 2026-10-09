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
class JenkinsLocalRecoveryRuntimeCertificationTests {
    companion object {
        private val scenarios = listOf(JenkinsCheckoutRuntimeCertification.Scenario.RECOVERY_FAILURE,
            JenkinsCheckoutRuntimeCertification.Scenario.RECOVERY_SUCCESS)
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
        val failedBody = p.scenario == scenarios[0]
        val error = mapOf("type" to "hudson.AbortException", "message" to missing)
        return mapper.valueToTree(mapOf("status" to "completed", "jenkins" to "2.580.1", "java" to "25.0.1",
            "plugins" to mapOf("git" to "5.10.1", "pipeline-model-definition" to "2.2293.v6e7193cec599", "timestamper" to "1.30"),
            "runs" to p.artifacts.map { (id, bytes) ->
                val rethrow = id == "rethrown-failure"
                mapOf("id" to id, "result" to if (rethrow) "FAILURE" else "SUCCESS", "finished" to true,
                    "buildNumber" to 1, "artifactSha256" to sha(bytes),
                    "checkoutErrors" to if (failedBody) listOf(error) else emptyList(),
                    "checkoutErrorIndices" to if (failedBody) listOf(1) else emptyList(),
                    "terminalError" to if (rethrow) error + ("checkoutIndex" to 1) else null,
                    "checkoutCount" to when (id) { "baseline" -> if (failedBody) 3 else 2; "unconditional-handler" -> 3;
                        "omitted-continuation" -> if (failedBody) 2 else 1; else -> 2 },
                    "marker" to if (failedBody && (rethrow || id == "omitted-continuation")) "alternate\n" else "selected\n")
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

    @Test fun bothPathsBindALocalTryAndKeepOriginalCompilerArtifacts() {
        for (s in scenarios) {
            val p = prepare(s)
            assertContentEquals(File(JenkinsCheckoutRuntimeCertification.FIXTURE, s.source).readBytes(), p.bound.resolve(p.bound.fixture))
            assertContentEquals(p.bound.resolve(p.bound.artifact), p.artifacts.getValue("baseline"))
            val graph = mapper.readTree(p.bound.resolve(p.bound.canonicalGraph))
            assertEquals(1, graph["nodes"].count { it["kind"].asText() == "TRY" })
            assertTrue(graph["workflows"][0]["failurePolicy"]["handler"].isNull)
            assertEquals(setOf(CertificationSubject.Structural(TargetStructuralProjectionKind.ERROR_BOUNDARY)),
                p.bound.subjects.filterIsInstance<CertificationSubject.Structural>().toSet())
            val original = p.artifacts.getValue("baseline").toString(Charsets.UTF_8)
            assertContains(original, "} catch (flowError) {"); assertFalse(original.contains("throw flowError"))
            assertTrue(original.lastIndexOf("git branch: 'selected'") > original.indexOf("git branch: 'alternate'"))
            assertEquals(p.scenario.runIds.size, p.artifacts.values.map(::sha).toSet().size)
            if (s == scenarios[0]) {
                assertFalse(p.artifacts.getValue("omitted-handler").toString(Charsets.UTF_8).contains("git branch: 'alternate'"))
                assertContains(p.artifacts.getValue("rethrown-failure").toString(Charsets.UTF_8), "throw flowError")
            }
        }
    }

    @Test fun successfulAndRecoveredPathsAdmitBoundedLocalRecoveryOnly() {
        for (s in scenarios) {
            val p = prepare(s); val result = assess(p, record(p))
            assertTrue(result.admission.valid, result.admission.findings.toString())
            val view = assertNotNull(result.view)
            assertEquals(CertificationScenarioShape.STRUCTURAL_OCCURRENCES, view.scenarios.single().shape)
            assertFalse(view.publicSupportPromoted); assertFalse(view.portableExecution)
            assertTrue(view.coverage.filter { it.subject !in p.bound.subjects }.all { it.scenarioIds.isEmpty() })
        }
    }

    @Test fun identicalFinalResultAndWorkspaceCannotHideHandlerOrContinuationOmission() {
        for (s in scenarios) {
            val p = prepare(s); val raw = record(p)
            val index = if (s == scenarios[0]) 1 else 2
            assertEquals(raw["runs"][0]["result"], raw["runs"][index]["result"])
            assertEquals(raw["runs"][0]["marker"], raw["runs"][index]["marker"])
            assertNotEquals(raw["runs"][0]["checkoutCount"], raw["runs"][index]["checkoutCount"])
            for (i in 1 until p.scenario.runIds.size) {
                val changed = record(p); val mutant = changed["runs"][i] as ObjectNode
                for (field in listOf("result", "marker", "checkoutCount", "checkoutErrors", "checkoutErrorIndices", "terminalError"))
                    mutant.set<JsonNode>(field, changed["runs"][0][field])
                assertFalse(assess(p, changed).admission.valid)
            }
        }
    }

    @Test fun recoveryCannotResumeTheFailedBodyLoseTheHandlerOrStopBeforeContinuation() {
        val p = prepare(scenarios[0])
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.put("checkoutCount", 4) }, { it.put("checkoutCount", 2) },
            { it.put("marker", "alternate\n").put("checkoutCount", 2) },
            { it.put("result", "FAILURE").set<JsonNode>("terminalError", record(p)["runs"][2]["terminalError"]) },
            { it.putArray("checkoutErrorIndices").add(2) },
            { (it["checkoutErrors"][0] as ObjectNode).put("message", "Connection refused") })) {
            val raw = record(p); change(raw["runs"][0] as ObjectNode)
            val result = assess(p, raw); assertFalse(result.admission.valid); assertNull(result.view)
        }
    }

    @Test fun successfulBodyCannotExecuteItsHandlerOrLoseContinuation() {
        val p = prepare(scenarios[1])
        for (count in listOf(1, 3)) {
            val raw = record(p); (raw["runs"][0] as ObjectNode).put("checkoutCount", count)
            assertFalse(assess(p, raw).admission.valid)
        }
        val raw = record(p); (raw["runs"][0] as ObjectNode).put("marker", "alternate\n")
        assertFalse(assess(p, raw).admission.valid)
    }

    @Test fun expectedRethrowRequiresTheOriginalNativeFailureAndItsOrigin() {
        val p = prepare(scenarios[0]); assertTrue(runs(p, record(p)).all { it.outcome == CertificationRunOutcome.COMPLETED })
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.putNull("terminalError") },
            { (it["terminalError"] as ObjectNode).put("checkoutIndex", 2) },
            { (it["terminalError"] as ObjectNode).put("type", "java.io.IOException") },
            { (it["terminalError"] as ObjectNode).put("message", "Unrelated error") })) {
            val raw = record(p); change(raw["runs"][2] as ObjectNode)
            assertEquals(CertificationRunOutcome.INFRASTRUCTURE_FAILURE, runs(p, raw)[2].outcome)
            assertFalse(assess(p, raw).admission.valid)
        }
    }

    @Test fun malformedErrorPositionsAndTerminalEvidenceFailBeforeSigning() {
        val p = prepare(scenarios[0])
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.remove("checkoutErrorIndices") }, { it.put("checkoutErrorIndices", "1") },
            { it.putArray("checkoutErrorIndices") }, { it.putArray("checkoutErrorIndices").add(1).add(1) },
            { it.putArray("checkoutErrorIndices").add("1") }, { it.putArray("checkoutErrorIndices").add(0) },
            { it.putArray("checkoutErrorIndices").add(4) }, { it.putArray("checkoutErrorIndices").add(4294967297L) },
            { it.putArray("checkoutErrorIndices").add(1.5) }, { it.remove("terminalError") },
            { it.put("terminalError", "none") }, { it.put("checkoutCount", 5) },
            { it.put("checkoutCount", 4294967297L) }, { it.put("finished", "true") })) {
            val raw = record(p); change(raw["runs"][0] as ObjectNode)
            assertFails { runs(p, raw) }
        }
        val unordered = record(p); val row = unordered["runs"][0] as ObjectNode
        (row["checkoutErrors"] as ArrayNode).add(row["checkoutErrors"][0].deepCopy<JsonNode>())
        row.putArray("checkoutErrorIndices").add(2).add(1)
        assertFails { runs(p, unordered) }
    }

    @Test fun incompleteOrUnexpectedOutcomesCannotSupplyMutantEvidence() {
        for (s in scenarios) {
            val p = prepare(s)
            for (index in p.scenario.runIds.indices) {
                for (status in listOf("ABORTED", "UNSTABLE", "NOT_BUILT")) {
                    val raw = record(p); (raw["runs"][index] as ObjectNode).put("result", status)
                    assertFalse(assess(p, raw).admission.valid)
                }
                val raw = record(p); (raw["runs"][index] as ObjectNode).put("finished", false)
                assertFails { runs(p, raw) }
            }
        }
    }

    @Test fun missingDuplicatedCrossScenarioAndSubstitutedRunsAreRejected() {
        for (s in scenarios) {
            val p = prepare(s)
            val missing = record(p); (missing["runs"] as ArrayNode).remove(1)
            assertFails { runs(p, missing) }
            val duplicate = record(p); (duplicate["runs"][1] as ObjectNode).put("id", "baseline")
            assertFails { runs(p, duplicate) }
            val substitution = record(p); (substitution["runs"][0] as ObjectNode).put("artifactSha256", "0".repeat(64))
            assertFails { runs(p, substitution) }
            assertFails { runs(p, record(prepare(scenarios.single { it != s }))) }
        }
    }
}
