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
class JenkinsSecretConditionRuntimeTests {
    private val mapper = jacksonObjectMapper()
    private fun prepare() = JenkinsCheckoutRuntimeCertification.prepare(File("."), "sha256:" + "a".repeat(64),
        JenkinsCheckoutRuntimeCertification.Scenario.SECRET_CONDITION)
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

    @Test fun compilerBindsExactSecretIdentitiesAndLeavesBaselineBytesUnchanged() {
        val p = prepare()
        assertContentEquals(File(JenkinsCheckoutRuntimeCertification.FIXTURE, "secret-condition.flow").readBytes(), p.bound.resolve(p.bound.fixture))
        assertContentEquals(p.bound.resolve(p.bound.artifact), p.artifacts.getValue("baseline"))
        val text = p.artifacts.getValue("baseline").toString(Charsets.UTF_8)
        assertContains(text, "FLOW_SECRET_LEFTTOKEN = credentials('leftToken')")
        assertContains(text, "FLOW_SECRET_RIGHTTOKEN = credentials('rightToken')")
        assertContains(text, "env.FLOW_SECRET_LEFTTOKEN == env.FLOW_SECRET_RIGHTTOKEN")
        assertFalse(text.contains("env.leftToken")); assertFalse(text.contains("env.rightToken"))
        assertEquals(3, p.artifacts.values.map(::sha).distinct().size)
        assertFalse(p.artifacts.getValue("omitted-binding").toString(Charsets.UTF_8).contains("credentials('rightToken')"))
        assertContains(p.artifacts.getValue("substituted-binding").toString(Charsets.UTF_8), "FLOW_SECRET_RIGHTTOKEN = credentials('differentToken')")
    }

    @Test fun admittedSecretEvidenceRemainsBoundedAndPreservesEveryObservation() {
        val p = prepare(); val result = assess(p, record(p))
        assertTrue(result.admission.valid, result.admission.findings.toString())
        val matrix = mapper.readTree(AdapterBehaviorMatrix.derive(result, p.scenario, CertificationEvidenceResolver { p.evidence[it.id] }).json())
        assertEquals(listOf("SECRETS"), matrix["rows"].filter { !it["behavior"].isNull }.map { it["construct"].asText() })
        assertFalse(matrix["publicSupportPromoted"].asBoolean()); assertFalse(matrix["portableExecution"].asBoolean())
        assertTrue(matrix["rows"].filter { it["construct"].asText() != "SECRETS" }.all { it["behavior"].isNull })
    }

    @Test fun positiveCheckoutCannotHideWrongCredentialIdentityOrEnvironment() {
        for (field in listOf("credentialsId", "variable")) {
            val p = prepare(); val raw = record(p)
            val binding = raw["runs"][0]["secrets"]["bindings"][0] as ObjectNode
            binding.put(field, if (field == "credentialsId") "differentToken" else "FLOW_SECRET_RIGHTTOKEN")
            if (field == "variable") assertFails { runs(p, raw) }
            else assertFalse(assess(p, raw).admission.valid)
        }
    }

    @Test fun baselineAndMutantsMustHaveDistinctNativeOutcomes() {
        for (index in 0..2) {
            val p = prepare(); val raw = record(p); val row = raw["runs"][index] as ObjectNode
            row.put("checkoutCount", if (index == 0) 0 else 1)
            if (index == 0) row.putNull("marker") else row.put("marker", "selected\n")
            assertFalse(assess(p, raw).admission.valid)
        }
    }

    @Test fun leakedCanariesMalformedBindingsAndUnpinnedPluginsFailBeforeSigning() {
        for (change in listOf<(ObjectNode) -> Unit>(
            { (it["secrets"] as ObjectNode).put("canaryInBuildLog", true) },
            { (it["secrets"] as ObjectNode).put("canaryInBuildLog", "false") },
            { (it["secrets"] as ObjectNode).remove("bindings") },
            { (it["secrets"] as ObjectNode).put("value", "unwanted") },
            { (it["secrets"]["bindings"] as ArrayNode).add(it["secrets"]["bindings"][0]) },
            { (it["secrets"]["bindings"][0] as ObjectNode).put("credentialsId", "unknown") })) {
            val p = prepare(); val raw = record(p); change(raw["runs"][0] as ObjectNode)
            assertFails { runs(p, raw) }
        }
        val p = prepare(); val raw = record(p)
        (raw["plugins"] as ObjectNode).put("credentials-binding", "unverified")
        assertFails { runs(p, raw) }
    }

    @Test fun incompleteOrReplacedRunArtifactsCannotSatisfyTheAssessment() {
        val p = prepare(); val missing = record(p); (missing["runs"] as ArrayNode).remove(2)
        assertFails { runs(p, missing) }
        val duplicate = record(p); (duplicate["runs"][2] as ObjectNode).put("id", "baseline")
        assertFails { runs(p, duplicate) }
        val changed = record(p); (changed["runs"][0] as ObjectNode).put("artifactSha256", "0".repeat(64))
        assertFails { runs(p, changed) }
    }
}
