package org.flowlang.conformance

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.*
import org.flowlang.adapters.certification.*
import org.flowlang.distribution.reference.ReferenceTargetProjections

/** Synthetic protocol regressions; provider execution belongs to the dedicated CI jobs. */
class SharedCanonicalCheckoutRuntimeTests {
    private val mapper = jacksonObjectMapper()
    private val scenario = JenkinsCheckoutRuntimeCertification.Scenario.SHARED_CHECKOUT
    private fun prepare() = JenkinsCheckoutRuntimeCertification.prepare(File("."), "sha256:" + "a".repeat(64), scenario)
    private fun record(p: JenkinsCheckoutRuntimeCertification.Prepared): ObjectNode = mapper.valueToTree(mapOf(
        "status" to "completed", "jenkins" to "2.580.1", "java" to "25",
        "plugins" to p.runtime.filter { it.id.startsWith("plugin:") }.associate { it.id.removePrefix("plugin:") to it.version },
        "runs" to scenario.runIds.map { id -> (mapper.readTree(SharedCheckoutFixture.expected(id)) as ObjectNode).apply {
            put("id", id); put("result", "SUCCESS"); put("finished", true); put("buildNumber", 1)
            put("artifactSha256", GitHubActionsCheckoutRuntimeCertification.sha256(p.artifacts.getValue(id)))
            put("checkoutCount", if (id == "omitted-checkout") 0 else 1); putArray("checkoutErrors")
        } }))

    private fun assess(p: JenkinsCheckoutRuntimeCertification.Prepared, raw: ObjectNode): CertificationEvidenceViewResult {
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val trust = CertificationObservationTrust("c".repeat(32), mapOf("test-runner" to key.public), scenario.runIds.map {
            CertificationAuthorizedRun(it, scenario.id, it.takeUnless { id -> id == "baseline" }, "test-runner") })
        val signed = JenkinsCheckoutRuntimeCertification.observations(p, mapper.writeValueAsBytes(raw)).map { observation ->
            val signer = Signature.getInstance("Ed25519"); signer.initSign(key.private)
            signer.update(CertificationObservationAuthentication.signingBytes("test-runner", trust.challenge, observation))
            SignedCertificationObservation("test-runner", trust.challenge, observation, signer.sign())
        }
        return CertificationEvidenceViews.assess(p.bundle, p.bundle.adapter, listOf(p.bound),
            ReferenceTargetProjections.nativeCatalogs.getValue("jenkins"), signed, trust, CertificationEvidenceResolver { p.evidence[it.id] })
    }

    @Test fun bothProvidersCompileTheIdenticalSourceAndCanonicalGraph() {
        val jenkins = prepare()
        val github = GitHubActionsCheckoutRuntimeCertification.prepare(File("."), "a".repeat(40), "b".repeat(40), "20261010.1",
            File(GitHubActionsCheckoutRuntimeCertification.WORKFLOW).readBytes())
        assertContentEquals(File(SharedCheckoutFixture.SOURCE).readBytes(), jenkins.bound.resolve(jenkins.bound.fixture))
        assertContentEquals(jenkins.bound.resolve(jenkins.bound.fixture), github.bound.resolve(github.bound.fixture))
        assertContentEquals(jenkins.bound.resolve(jenkins.bound.canonicalGraph), github.bound.resolve(github.bound.canonicalGraph))
        assertEquals(jenkins.bound.graphDigest, github.bound.graphDigest)
        assertNotEquals(jenkins.bound.artifact.sha256, github.bound.artifact.sha256)
        assertTrue(jenkins.bound.subjects.none { it is CertificationSubject.Structural })
        for (id in scenario.runIds) assertContentEquals(jenkins.evidence.getValue("expected:$id"), github.evidence.getValue("expected:$id"))
        assertTrue(jenkins.runtime.any { it.id == "network" && it.version == "public-git-egress" })
        assertTrue(JenkinsCheckoutRuntimeCertification.prepare(File("."), "sha256:" + "a".repeat(64)).runtime.none { it.id == "network" })
    }

    @Test fun mutantsOnlyOmitTheNativeCheckoutOrSubstituteItsImmutableRevision() {
        val p = prepare(); val original = p.artifacts.getValue("baseline").toString(Charsets.UTF_8)
        assertContentEquals(p.bound.resolve(p.bound.artifact), p.artifacts.getValue("baseline"))
        assertTrue(original.contains("checkout scmGit(") && !original.contains("git branch:") && !original.contains("cloneOption("))
        assertTrue(original.contains("name: '${SharedCheckoutFixture.SELECTED}'"))
        assertFalse(p.artifacts.getValue("omitted-checkout").toString(Charsets.UTF_8).contains("checkout scmGit("))
        // Preserve diagnostic comments about the authored input; only the executed SCM selection changes.
        assertEquals(original.replace("branches: [[name: '${SharedCheckoutFixture.SELECTED}']]",
            "branches: [[name: '${SharedCheckoutFixture.ALTERNATE}']]"),
            p.artifacts.getValue("substituted-revision").toString(Charsets.UTF_8))
        assertFails { JenkinsCheckoutRuntimeCertification.sharedCheckoutArtifacts("missing", "missing".toByteArray()) }
        assertFails { JenkinsCheckoutRuntimeCertification.sharedCheckoutArtifacts(original + original, (original + original).toByteArray()) }
    }

    @Test fun authenticatedSharedObservationsProduceOnlyNativeCheckoutEvidence() {
        val p = prepare(); val assessment = assess(p, record(p))
        assertTrue(assessment.admission.valid, assessment.admission.findings.toString())
        val matrix = mapper.readTree(AdapterBehaviorMatrix.derive(assessment, scenario, CertificationEvidenceResolver { p.evidence[it.id] }).json())
        assertEquals(listOf("NATIVE_CHECKOUT"), matrix["rows"].filter { !it["behavior"].isNull }.map { it["construct"].asText() })
        assertFalse(matrix["portableExecution"].asBoolean()); assertFalse(matrix["publicSupportPromoted"].asBoolean())
    }

    @Test fun wrongRevisionWrongFileAndSurvivingMutantsFailTheIndependentOracle() {
        val p = prepare()
        for ((index, field, value) in listOf(Triple(0, "revision", SharedCheckoutFixture.ALTERNATE),
            Triple(0, "markerSha256", SharedCheckoutFixture.ALTERNATE_MARKER), Triple(2, "revision", SharedCheckoutFixture.SELECTED))) {
            val raw = record(p); (raw["runs"][index] as ObjectNode).put(field, value)
            val result = assess(p, raw)
            assertFalse(result.admission.valid)
            assertTrue(result.admission.findings.any { it.code == "OBSERVATION_MISMATCH" })
        }
        val raw = record(p)
        (raw["runs"][1] as ObjectNode).put("revision", SharedCheckoutFixture.SELECTED).put("markerSha256", SharedCheckoutFixture.SELECTED_MARKER)
        assertFalse(assess(p, raw).admission.valid)
    }

    @Test fun failedNativeExecutionOrMalformedInventoryCannotBeSignedAsSuccess() {
        val p = prepare()
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.put("result", "FAILURE") }, { it.put("finished", false) }, { it.put("checkoutCount", 0) },
            { it.put("checkoutCount", 4294967297L) }, { it.putArray("checkoutErrors").add("failed") },
            { it.put("id", "omitted-checkout") }, { it.remove("revision") }, { it.put("markerSha256", "invalid") })) {
            val raw = record(p); change(raw["runs"][0] as ObjectNode)
            assertFails { JenkinsCheckoutRuntimeCertification.observations(p, mapper.writeValueAsBytes(raw)) }
        }
    }

    @Test fun sharedOracleRequiresPairedWellFormedRevisionAndFileEvidence() {
        assertFails { SharedCheckoutFixture.observationBytes(SharedCheckoutFixture.SELECTED, null) }
        assertFails { SharedCheckoutFixture.observationBytes(null, SharedCheckoutFixture.SELECTED_MARKER) }
        assertFails { SharedCheckoutFixture.observationBytes("main", SharedCheckoutFixture.SELECTED_MARKER) }
        assertFails { SharedCheckoutFixture.expected("unexpected") }
        assertContentEquals("{\"revision\":null,\"markerSha256\":null}\n".toByteArray(), SharedCheckoutFixture.expected("omitted-checkout"))
    }
}
