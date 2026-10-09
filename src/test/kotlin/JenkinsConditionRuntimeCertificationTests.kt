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
import org.flowlang.generators.manifest.TargetStructuralProjectionKind

/** Protocol regressions only; the CI runtime job supplies the independent Jenkins observations. */
class JenkinsConditionRuntimeCertificationTests {
    private val mapper = jacksonObjectMapper()
    private val scenarios = listOf(JenkinsCheckoutRuntimeCertification.Scenario.CONDITION_TRUE,
        JenkinsCheckoutRuntimeCertification.Scenario.CONDITION_FALSE)
    private fun prepare(s: JenkinsCheckoutRuntimeCertification.Scenario) =
        JenkinsCheckoutRuntimeCertification.prepare(File("."), "sha256:" + "a".repeat(64), s)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun record(p: JenkinsCheckoutRuntimeCertification.Prepared): ObjectNode {
        val enabled = p.scenario == JenkinsCheckoutRuntimeCertification.Scenario.CONDITION_TRUE
        return mapper.valueToTree(mapOf("status" to "completed", "jenkins" to "2.580.1", "java" to "25.0.1",
            "plugins" to mapOf("git" to "5.10.1", "pipeline-model-definition" to "2.2293.v6e7193cec599", "timestamper" to "1.30"),
            "runs" to p.artifacts.map { (id, bytes) -> mapOf("id" to id, "result" to "SUCCESS", "finished" to true,
                "buildNumber" to 1, "artifactSha256" to sha(bytes), "checkoutErrors" to emptyList<Any>(),
                "checkoutCount" to if (id == "flattened-conditions") 2 else 1,
                "marker" to when (id) {
                    "baseline" -> if (enabled) "selected\n" else "alternate\n"
                    "inverted-conditions" -> if (enabled) "alternate\n" else "selected\n"
                    else -> "alternate\n"
                }) }))
    }
    private fun runs(p: JenkinsCheckoutRuntimeCertification.Prepared, raw: ObjectNode) =
        JenkinsCheckoutRuntimeCertification.observations(p, mapper.writeValueAsBytes(raw))
    private fun assess(p: JenkinsCheckoutRuntimeCertification.Prepared, raw: ObjectNode): CertificationEvidenceViewResult {
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val observations = runs(p, raw)
        val trust = CertificationObservationTrust("c".repeat(32), mapOf("unit-test" to key.public),
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

    @Test fun bothSourceDefaultsRetainCompilerBoundConditionsAndUnchangedBaselineBytes() {
        val prepared = scenarios.map(::prepare)
        for (p in prepared) {
            assertContentEquals(File(JenkinsCheckoutRuntimeCertification.FIXTURE, p.scenario.source).readBytes(), p.bound.resolve(p.bound.fixture))
            assertContentEquals(p.bound.resolve(p.bound.artifact), p.artifacts.getValue("baseline"))
            val source = p.bound.resolve(p.bound.fixture)!!.toString(Charsets.UTF_8)
            val enabled = p.scenario == JenkinsCheckoutRuntimeCertification.Scenario.CONDITION_TRUE
            assertContains(source, "enabled: boolean default $enabled")
            val graph = mapper.readTree(p.bound.resolve(p.bound.canonicalGraph))
            assertEquals(2, graph["nodes"].count { it["kind"].asText() == "CONDITION" })
            assertEquals(setOf(CertificationSubject.Structural(TargetStructuralProjectionKind.CONDITION)),
                p.bound.subjects.filterIsInstance<CertificationSubject.Structural>().toSet())
            val original = p.artifacts.getValue("baseline").toString(Charsets.UTF_8)
            assertContains(original, "defaultValue: $enabled")
            assertEquals(2, original.lineSequence().count { it.trimStart().startsWith("if (") })
            assertFalse(p.artifacts.getValue("flattened-conditions").toString(Charsets.UTF_8).lineSequence().any { it.trimStart().startsWith("if (") })
            assertEquals(2, p.artifacts.getValue("inverted-conditions").toString(Charsets.UTF_8).lineSequence().count { it.trimStart().startsWith("if (!(") })
            assertEquals(3, p.artifacts.values.map(::sha).toSet().size)
        }
        assertNotEquals(prepared[0].bound.fixture.sha256, prepared[1].bound.fixture.sha256)
        assertNotEquals(prepared[0].bound.artifact.sha256, prepared[1].bound.artifact.sha256)
    }

    @Test fun bothDefaultsAdmitOnlyTheirBoundedStructuralObservations() {
        for (s in scenarios) {
            val p = prepare(s); val result = assess(p, record(p))
            assertTrue(result.admission.valid, result.admission.findings.toString())
            val view = assertNotNull(result.view)
            assertEquals(CertificationScenarioShape.STRUCTURAL_OCCURRENCES, view.scenarios.single().shape)
            assertFalse(view.publicSupportPromoted); assertFalse(view.portableExecution)
            assertTrue(view.coverage.filter { it.subject !in p.bound.subjects }.all { it.scenarioIds.isEmpty() })
        }
    }

    @Test fun flattenedFalseDefaultCannotHideBehindAnUnchangedWorkspaceMarker() {
        val p = prepare(scenarios[1]); val raw = record(p)
        assertEquals(raw["runs"][0]["marker"], raw["runs"][1]["marker"])
        val observed = runs(p, raw)
        assertNotEquals(observed[0].observation.sha256, observed[1].observation.sha256)
        (raw["runs"][1] as ObjectNode).put("checkoutCount", 1)
        val result = assess(p, raw)
        assertFalse(result.admission.valid); assertNull(result.view)
    }

    @Test fun wrongOrMissingOrAdditionalChildCannotSatisfyTheOriginalGuard() {
        for (s in scenarios) for (change in listOf<(ObjectNode) -> Unit>(
            { it.putNull("marker").put("checkoutCount", 0) },
            { it.put("marker", if (s == scenarios[0]) "alternate\n" else "selected\n") },
            { it.put("checkoutCount", 2) })) {
            val p = prepare(s); val raw = record(p); change(raw["runs"][0] as ObjectNode)
            val result = assess(p, raw)
            assertFalse(result.admission.valid); assertNull(result.view)
        }
    }

    @Test fun unchangedMutantObservationsAreRejectedForBothDefaults() {
        for (s in scenarios) for (index in 1..2) {
            val p = prepare(s); val raw = record(p)
            val mutant = raw["runs"][index] as ObjectNode
            mutant.set<com.fasterxml.jackson.databind.JsonNode>("marker", raw["runs"][0]["marker"])
            mutant.put("checkoutCount", 1)
            assertFalse(assess(p, raw).admission.valid)
        }
    }

    @Test fun failedBuildsAndCaughtNativeErrorsCannotCertifySuccessfulGuards() {
        for (s in scenarios) for (index in 0..2) {
            val p = prepare(s)
            for (status in listOf("FAILURE", "ABORTED", "UNSTABLE", "NOT_BUILT")) {
                val raw = record(p); (raw["runs"][index] as ObjectNode).put("result", status)
                val result = assess(p, raw)
                assertFalse(result.admission.valid); assertNull(result.view)
            }
            val raw = record(p)
            (raw["runs"][index]["checkoutErrors"] as ArrayNode).addObject().put("type", "java.io.IOException").put("message", "Connection refused")
            assertFalse(assess(p, raw).admission.valid)
        }
    }

    @Test fun malformedNativeStepRecordsFailBeforeSigning() {
        val p = prepare(scenarios[0])
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.remove("finished") }, { it.put("finished", false) }, { it.put("finished", "true") },
            { it.put("checkoutCount", -1) }, { it.put("checkoutCount", 3) }, { it.put("checkoutCount", 4294967297L) },
            { it.put("checkoutCount", 1.1) }, { it.remove("checkoutCount") }, { it.remove("checkoutErrors") },
            { it.put("checkoutErrors", "not-an-array") }, { it.put("marker", "x".repeat(1025)) },
            { it.put("buildNumber", 2) }, { it.put("artifactSha256", "0".repeat(64)) })) {
            val raw = record(p); change(raw["runs"][0] as ObjectNode)
            assertFails { runs(p, raw) }
        }
    }

    @Test fun missingDuplicatedOrCrossScenarioRunsCannotBeAdmitted() {
        val p = prepare(scenarios[0])
        val missing = record(p); (missing["runs"] as ArrayNode).remove(2)
        assertFails { runs(p, missing) }
        val duplicate = record(p); (duplicate["runs"][2] as ObjectNode).put("id", "baseline")
        assertFails { runs(p, duplicate) }
        assertFails { runs(p, record(prepare(scenarios[1]))) }
    }
}
