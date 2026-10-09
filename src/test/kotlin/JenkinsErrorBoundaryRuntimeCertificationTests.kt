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

/** Protocol regressions; the dedicated CI job supplies actual independent Jenkins observations. */
class JenkinsErrorBoundaryRuntimeCertificationTests {
    companion object {
        private val scenarios = listOf(JenkinsCheckoutRuntimeCertification.Scenario.ERROR_FAILURE,
            JenkinsCheckoutRuntimeCertification.Scenario.ERROR_SUCCESS)
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
                val fails = failedBody && id != "suppressed-propagation"
                mapOf("id" to id, "result" to if (fails) "FAILURE" else "SUCCESS", "finished" to true,
                    "buildNumber" to 1, "artifactSha256" to sha(bytes),
                    "checkoutErrors" to if (failedBody) listOf(error) else emptyList(),
                    "terminalError" to if (fails) error + ("checkoutIndex" to 1) else null,
                    "checkoutCount" to when (id) { "omitted-body" -> 0; "omitted-handler" -> 1; "baseline" -> if (failedBody) 2 else 1; else -> 2 },
                    "marker" to when (id) { "omitted-handler", "omitted-body" -> null; "baseline" -> if (failedBody) "alternate\n" else "selected\n"; else -> "alternate\n" })
            }))
    }
    private fun runs(p: JenkinsCheckoutRuntimeCertification.Prepared, raw: ObjectNode) =
        JenkinsCheckoutRuntimeCertification.observations(p, mapper.writeValueAsBytes(raw))
    private fun assess(p: JenkinsCheckoutRuntimeCertification.Prepared, raw: ObjectNode): CertificationEvidenceViewResult {
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val observations = runs(p, raw)
        val trust = CertificationObservationTrust("e".repeat(32), mapOf("unit-test" to key.public),
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

    @Test fun bothPathsKeepCompilerOwnedPolicyAndUnchangedOriginalArtifacts() {
        for (s in scenarios) {
            val p = prepare(s)
            assertContentEquals(File(JenkinsCheckoutRuntimeCertification.FIXTURE, s.source).readBytes(), p.bound.resolve(p.bound.fixture))
            assertContentEquals(p.bound.resolve(p.bound.artifact), p.artifacts.getValue("baseline"))
            val graph = mapper.readTree(p.bound.resolve(p.bound.canonicalGraph))
            assertTrue(graph["nodes"].none { it["kind"].asText() == "TRY" })
            assertEquals("PROPAGATE", graph["workflows"][0]["failurePolicy"]["disposition"].asText())
            assertTrue(graph["workflows"][0]["failurePolicy"]["handler"]["nodeIds"].size() > 0)
            assertEquals(setOf(CertificationSubject.Structural(TargetStructuralProjectionKind.ERROR_BOUNDARY)),
                p.bound.subjects.filterIsInstance<CertificationSubject.Structural>().toSet())
            val original = p.artifacts.getValue("baseline").toString(Charsets.UTF_8)
            assertContains(original, "} catch (flowError) {"); assertContains(original, "throw flowError")
            if (s == scenarios[0]) {
                assertFalse(p.artifacts.getValue("omitted-handler").toString(Charsets.UTF_8).contains("git branch: 'alternate'"))
                assertFalse(p.artifacts.getValue("suppressed-propagation").toString(Charsets.UTF_8).contains("throw flowError"))
                assertTrue(original.indexOf("git branch: 'missing-revision'") < original.indexOf("git branch: 'selected'"))
            } else {
                assertEquals(2, p.artifacts.getValue("unconditional-handler").toString(Charsets.UTF_8).lineSequence()
                    .count { it.trimStart().startsWith("git branch: 'alternate'") })
                assertFalse(p.artifacts.getValue("omitted-body").toString(Charsets.UTF_8).contains("git branch: 'selected'"))
            }
            assertEquals(3, p.artifacts.values.map(::sha).toSet().size)
        }
    }

    @Test fun bothPathsAdmitOnlyTheirBoundedErrorBoundaryObservations() {
        for (s in scenarios) {
            val p = prepare(s); val result = assess(p, record(p))
            assertTrue(result.admission.valid, result.admission.findings.toString())
            val view = assertNotNull(result.view)
            assertEquals(CertificationScenarioShape.STRUCTURAL_OCCURRENCES, view.scenarios.single().shape)
            assertFalse(view.publicSupportPromoted); assertFalse(view.portableExecution)
            assertTrue(view.coverage.filter { it.subject !in p.bound.subjects }.all { it.scenarioIds.isEmpty() })
        }
    }

    @Test fun failureStatusAloneCannotHideAnOmittedHandlerOrSwallowedPropagation() {
        val p = prepare(scenarios[0]); val raw = record(p)
        assertEquals(raw["runs"][0]["result"], raw["runs"][1]["result"])
        assertEquals(raw["runs"][0]["marker"], raw["runs"][2]["marker"])
        assertEquals(raw["runs"][0]["checkoutCount"], raw["runs"][2]["checkoutCount"])
        assertEquals(3, runs(p, raw).map { it.observation.sha256 }.toSet().size)
        for (index in 1..2) {
            val changed = record(p); val mutant = changed["runs"][index] as ObjectNode
            for (field in listOf("result", "marker", "checkoutCount", "checkoutErrors", "terminalError"))
                mutant.set<JsonNode>(field, changed["runs"][0][field])
            assertFalse(assess(p, changed).admission.valid)
        }
    }

    @Test fun successfulBodyCannotRunItsHandlerLoseItsCheckoutOrCarryAFailure() {
        val p = prepare(scenarios[1])
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.put("marker", "alternate\n").put("checkoutCount", 2) },
            { it.putNull("marker").put("checkoutCount", 0) },
            { it.set<JsonNode>("terminalError", record(prepare(scenarios[0]))["runs"][0]["terminalError"]) })) {
            val raw = record(p); change(raw["runs"][0] as ObjectNode)
            val result = assess(p, raw); assertFalse(result.admission.valid); assertNull(result.view)
        }
    }

    @Test fun unrelatedTerminalFailuresCannotImpersonateTheExpectedNativeError() {
        val p = prepare(scenarios[0])
        for (index in listOf(0, 1)) for (change in listOf<(ObjectNode) -> Unit>(
            { it.put("type", "java.io.IOException") }, { it.put("message", "Different failure") },
            { it.putNull("checkoutIndex") })) {
            val raw = record(p); change(raw["runs"][index]["terminalError"] as ObjectNode)
            assertEquals(CertificationRunOutcome.INFRASTRUCTURE_FAILURE, runs(p, raw)[index].outcome)
            val result = assess(p, raw); assertFalse(result.admission.valid); assertNull(result.view)
        }
        val wrongOrigin = record(p); (wrongOrigin["runs"][0]["terminalError"] as ObjectNode).put("checkoutIndex", 2)
        assertFalse(assess(p, wrongOrigin).admission.valid)
        val wrongNative = record(p); (wrongNative["runs"][0]["checkoutErrors"][0] as ObjectNode).put("message", "Connection refused")
        assertFalse(assess(p, wrongNative).admission.valid)
    }

    @Test fun incompleteOrUnexpectedBuildOutcomesCannotKillAMutant() {
        for (s in scenarios) for (index in 0..2) {
            val p = prepare(s)
            for (status in listOf("ABORTED", "UNSTABLE", "NOT_BUILT")) {
                val raw = record(p); (raw["runs"][index] as ObjectNode).put("result", status)
                assertFalse(assess(p, raw).admission.valid)
            }
            val raw = record(p); (raw["runs"][index] as ObjectNode).put("finished", false)
            assertFails { runs(p, raw) }
        }
    }

    @Test fun malformedTerminalEvidenceAndSubstitutedArtifactsFailBeforeSigning() {
        val p = prepare(scenarios[0])
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.remove("terminalError") }, { it.put("terminalError", "error") },
            { (it["terminalError"] as ObjectNode).remove("checkoutIndex") },
            { (it["terminalError"] as ObjectNode).put("checkoutIndex", "1") },
            { (it["terminalError"] as ObjectNode).put("checkoutIndex", 4294967297L) },
            { (it["terminalError"] as ObjectNode).put("checkoutIndex", 0) },
            { (it["terminalError"] as ObjectNode).put("extra", true) },
            { (it["terminalError"] as ObjectNode).put("message", "x".repeat(1025)) },
            { it.put("artifactSha256", "0".repeat(64)) }, { it.put("finished", "true") },
            { it.put("checkoutCount", 3) }, { it.put("buildNumber", 2) })) {
            val raw = record(p); change(raw["runs"][0] as ObjectNode)
            assertFails { runs(p, raw) }
        }
    }

    @Test fun missingDuplicatedOrCrossScenarioRunsCannotBeAdmitted() {
        for (s in scenarios) {
            val p = prepare(s)
            val missing = record(p); (missing["runs"] as ArrayNode).remove(2)
            assertFails { runs(p, missing) }
            val duplicate = record(p); (duplicate["runs"][2] as ObjectNode).put("id", "baseline")
            assertFails { runs(p, duplicate) }
            assertFails { runs(p, record(prepare(scenarios.single { it != s }))) }
        }
    }
}
