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

/** Synthetic protocol fixtures only; the dedicated CI job supplies native Jenkins evidence. */
class JenkinsCheckoutResultRuntimeTests {
    private val mapper = jacksonObjectMapper()
    private fun prepare(scenario: JenkinsCheckoutRuntimeCertification.Scenario = JenkinsCheckoutRuntimeCertification.Scenario.RESULT_TRUE) = JenkinsCheckoutRuntimeCertification.prepare(File("."), "sha256:" + "a".repeat(64),
        scenario)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun record(p: JenkinsCheckoutRuntimeCertification.Prepared): ObjectNode {
        val spec = p.bundle.scenarios.single()
        val rows = p.scenario.runIds.map { id ->
            val expected = spec.negativeMutants.singleOrNull { it.id == id }?.expectedObservation ?: spec.expectedObservation
            (mapper.readTree(p.evidence.getValue(expected.id)) as ObjectNode).apply {
                put("id", id); put("artifactSha256", sha(p.artifacts.getValue(id))); put("buildNumber", 1); put("finished", true)
            }
        }
        return mapper.valueToTree(mapOf("status" to "completed", "jenkins" to "2.580.1", "java" to "25",
            "plugins" to p.runtime.filter { it.id.startsWith("plugin:") }.associate { it.id.removePrefix("plugin:") to it.version },
            "runs" to rows))
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

    private val scenarios = JenkinsCheckoutRuntimeCertification.Scenario.entries.filter { it.checkoutResult }

    @Test fun compilerBoundBaselinesAndMutationsRetainDistinctArtifactIdentities() {
        for (scenario in scenarios) {
            val p = prepare(scenario)
            assertContentEquals(File(JenkinsCheckoutRuntimeCertification.FIXTURE, scenario.source).readBytes(), p.bound.resolve(p.bound.fixture))
            assertContentEquals(p.bound.resolve(p.bound.artifact), p.artifacts.getValue("baseline"))
            assertEquals(scenario.runIds.size, p.artifacts.values.map(::sha).distinct().size)
            assertContains(p.artifacts.getValue("baseline").toString(Charsets.UTF_8), "def flowCheckoutSucceeded = true")
            val text = p.artifacts.getValue("baseline").toString(Charsets.UTF_8)
            for (changed in listOf(text.replace("= true", "= false"), text + "          def flowCheckoutSucceeded = true\n"))
                assertFails { JenkinsCheckoutResultCertification.artifacts(changed, changed.toByteArray(), scenario) }
        }
    }

    @Test fun admittedEvidenceFillsOnlyTheBoundedValueObservationRow() {
        for (scenario in scenarios) {
            val p = prepare(scenario); val result = assess(p, record(p))
            assertTrue(result.admission.valid, result.admission.findings.toString())
            val matrix = mapper.readTree(AdapterBehaviorMatrix.derive(result, scenario, CertificationEvidenceResolver { p.evidence[it.id] }).json())
            assertEquals(listOf("VALUE_STATE_CONTINUITY"), matrix["rows"].filter { !it["behavior"].isNull }.map { it["construct"].asText() })
            assertFalse(matrix["publicSupportPromoted"].asBoolean()); assertFalse(matrix["portableExecution"].asBoolean())
        }
    }

    @Test fun alteredNativeBranchesCountsAndMarkersCannotSatisfySignedEvidence() {
        for (scenario in scenarios) for (index in scenario.runIds.indices) {
            val p = prepare(scenario); val raw = record(p)
            (raw["runs"][index]["checkoutBranches"] as ArrayNode).set(0, mapper.valueToTree<JsonNode>("unrelated"))
            assertFalse(assess(p, raw).admission.valid)
            val marker = record(p); (marker["runs"][index] as ObjectNode).put("marker", "unrelated\n")
            assertFalse(assess(p, marker).admission.valid)
            val count = record(p); (count["runs"][index] as ObjectNode).put("checkoutCount", 0)
            assertFails { runs(p, count) }
        }
    }

    @Test fun producerFailureRequiresItsNativeOriginAndNeverInventsAFalseResult() {
        val p = prepare(JenkinsCheckoutRuntimeCertification.Scenario.RESULT_FAILURE)
        assertEquals(CertificationRunOutcome.COMPLETED, runs(p, record(p)).first().outcome)
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.putNull("terminalError") },
            { (it["terminalError"] as ObjectNode).putNull("checkoutIndex") },
            { (it["checkoutErrors"][0] as ObjectNode).put("message", "infrastructure failure") },
            { it.put("result", "ABORTED") })) {
            val raw = record(p); change(raw["runs"][0] as ObjectNode)
            assertEquals(CertificationRunOutcome.INFRASTRUCTURE_FAILURE, runs(p, raw).first().outcome)
            assertFalse(assess(p, raw).admission.valid)
        }
    }

    @Test fun missingReplacedDuplicateAndUnpinnedRuntimeInputsFailBeforeSigning() {
        val p = prepare()
        val missing = record(p); (missing["runs"] as ArrayNode).remove(2); assertFails { runs(p, missing) }
        val duplicate = record(p); (duplicate["runs"][2] as ObjectNode).put("id", "baseline"); assertFails { runs(p, duplicate) }
        val changed = record(p); (changed["runs"][0] as ObjectNode).put("artifactSha256", "0".repeat(64)); assertFails { runs(p, changed) }
        val unfinished = record(p); (unfinished["runs"][0] as ObjectNode).put("finished", false); assertFails { runs(p, unfinished) }
        val plugin = record(p); (plugin["plugins"] as ObjectNode).put("workflow-basic-steps", "unverified"); assertFails { runs(p, plugin) }
    }
}
