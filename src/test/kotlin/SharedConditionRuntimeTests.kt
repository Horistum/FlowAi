package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.*
import org.flowlang.adapters.certification.*
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.generators.manifest.TargetStructuralProjectionSupportScope

/** Protocol regressions use synthetic records; only dedicated CI jobs supply native execution evidence. */
class SharedConditionRuntimeTests {
    companion object {
        private val mapper = jacksonObjectMapper()
        fun nativeResults(revision: String = "a".repeat(40), workflow: String = "b".repeat(40)): ObjectNode {
            val jobs = mapper.createObjectNode()
            jobs.set<JsonNode>(SharedConditionFixture.PREPARE_JOB, mapper.valueToTree(mapOf("result" to "success", "outputs" to mapOf(
                "challenge" to "c".repeat(32), "source-revision" to revision, "workflow-revision" to workflow))))
            SharedConditionFixture.values.forEach { value -> SharedConditionFixture.runIds(value).forEach { id ->
                val executed = SharedConditionFixture.executes(value, id)
                jobs.set<JsonNode>(SharedConditionFixture.job(value, id), mapper.valueToTree(mapOf(
                    "result" to if (executed) "success" else "skipped", "outputs" to if (!executed) emptyMap() else mapOf(
                        "observation" to SharedConditionFixture.expected(value, id).toString(Charsets.UTF_8).trimEnd(), "runner-image" to "20261010.1"))))
            } }
            return jobs
        }
    }
    private val envelope get() = File(GitHubActionsCheckoutRuntimeCertification.WORKFLOW).readBytes()
    private fun github(value: Boolean, jobs: ObjectNode = nativeResults()) = GitHubActionsConditionRuntimeCertification.prepare(
        File("."), "a".repeat(40), "b".repeat(40), "20261010.1", envelope, value, mapper.writeValueAsBytes(jobs))
    private fun jenkins(value: Boolean) = JenkinsCheckoutRuntimeCertification.prepare(File("."), "sha256:" + "a".repeat(64),
        if (value) JenkinsCheckoutRuntimeCertification.Scenario.SHARED_CONDITION_TRUE else JenkinsCheckoutRuntimeCertification.Scenario.SHARED_CONDITION_FALSE)

    @Test fun bothProvidersPreserveTheSharedLiteralGuard() {
        for (value in SharedConditionFixture.values) {
            val j = jenkins(value); val g = github(value)
            assertContentEquals(j.bound.resolve(j.bound.fixture), g.bound.resolve(g.bound.fixture))
            assertContentEquals(j.bound.resolve(j.bound.canonicalGraph), g.bound.resolve(g.bound.canonicalGraph))
            assertNotEquals(j.bound.artifact.sha256, g.bound.artifact.sha256)
            assertTrue(g.bound.subjects.contains(CertificationSubject.Structural(TargetStructuralProjectionKind.CONDITION)))
            assertEquals(TargetStructuralProjectionSupportScope.PLAN_SCOPED,
                g.provider.structuralDefinitions.single { it.structure == TargetStructuralProjectionKind.CONDITION }.supportScope)
            SharedConditionFixture.runIds(value).forEach { id ->
                assertContentEquals(SharedConditionFixture.expected(value, id), j.evidence.getValue("expected:$id"))
                assertContentEquals(j.evidence.getValue("expected:$id"), g.evidence.getValue("expected:$id"))
                if (id != "baseline") assertFalse(SharedConditionFixture.expected(value, "baseline").contentEquals(SharedConditionFixture.expected(value, id)))
            }
            assertContentEquals(g.bound.resolve(g.bound.artifact), g.artifacts.getValue("baseline"))
            assertContentEquals(j.bound.resolve(j.bound.artifact), j.artifacts.getValue("baseline"))
            val baseline = j.artifacts.getValue("baseline").toString(Charsets.UTF_8)
            assertTrue(baseline.contains("if ($value) {"))
            assertTrue(j.artifacts.getValue("inverted-condition").toString(Charsets.UTF_8).contains("if (${!value}) {"))
            if (value) assertFalse(j.artifacts.getValue("omitted-checkout").toString(Charsets.UTF_8).contains("checkout scmGit("))
            else {
                assertFalse(j.artifacts.getValue("flattened-condition").toString(Charsets.UTF_8).contains("if ("))
                assertFalse(j.artifacts.getValue("flattened-condition").contentEquals(j.artifacts.getValue("inverted-condition")))
            }
        }
    }

    @Test fun envelopeRejectsMovedReversedOrBypassedGuardsAndChangedNativeObservers() {
        for (value in SharedConditionFixture.values) {
            val p = github(value)
            val source = envelope.toString(Charsets.UTF_8)
            val start = "  ${SharedConditionFixture.job(value, "baseline")}:\n"
            val before = source.substringBefore(start) + start
            val rest = source.substringAfter(start)
            for (mutant in listOf(
                rest.replaceFirst("    if: \${{ ($value) }}\n", ""),
                rest.replaceFirst("    if: \${{ ($value) }}", "    if: \${{ (${!value}) }}"),
                rest.replaceFirst("    timeout-minutes: 10", "    timeout-minutes: 10\n    continue-on-error: true"),
                rest.replaceFirst("actions/checkout@v4", "actions/checkout@v6"),
                rest.replaceFirst("subprocess.check_output", "subprocess.getoutput"))) {
                assertFails { GitHubActionsConditionRuntimeCertification.validateEnvelope(File("."), (before + mutant).toByteArray(), p.artifacts, value) }
            }
        }
    }

    @Test fun actualJobInventoryRequiresSuccessOrSkipAndIndependentObservations() {
        for (value in SharedConditionFixture.values) {
            val raw = nativeResults(); val p = github(value, raw)
            val results = GitHubActionsConditionRuntimeCertification.observations(p, value, mapper.writeValueAsBytes(raw))
            SharedConditionFixture.runIds(value).forEach { assertContentEquals(SharedConditionFixture.expected(value, it), results.getValue(it)) }
        }
        val name = SharedConditionFixture.job(true, "baseline")
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.remove(name) }, { (it[name] as ObjectNode).put("result", "failure") },
            { (it[name] as ObjectNode).put("result", "cancelled") }, { (it[name] as ObjectNode).put("result", "skipped") },
            { (it[name]["outputs"] as ObjectNode).remove("observation") },
            { (it[name]["outputs"] as ObjectNode).put("observation", "{}") },
            { (it[SharedConditionFixture.job(false, "baseline")]["outputs"] as ObjectNode).put("observation", "{}") },
            { (it[SharedConditionFixture.PREPARE_JOB]["outputs"] as ObjectNode).put("source-revision", "d".repeat(40)) })) {
            val raw = nativeResults(); change(raw); val p = github(true, raw)
            assertFails { GitHubActionsConditionRuntimeCertification.observations(p, true, mapper.writeValueAsBytes(raw)) }
        }
        val p = github(true)
        assertFails { GitHubActionsConditionRuntimeCertification.observations(p, true, mapper.writeValueAsBytes(nativeResults()) + "\n".toByteArray()) }
    }

    private fun assess(p: GitHubActionsCheckoutRuntimeCertification.Prepared, value: Boolean, raw: ObjectNode): CertificationEvidenceViewResult {
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val runner = GitHubActionsConditionRuntimeCertification.RUNNER
        val trust = CertificationObservationTrust("c".repeat(32), mapOf(runner to key.public), SharedConditionFixture.runIds(value).map {
            CertificationAuthorizedRun(it, p.bound.id, it.takeUnless { id -> id == "baseline" }, runner) })
        val signed = GitHubActionsConditionRuntimeCertification.observations(p, value, mapper.writeValueAsBytes(raw)).map { (id, bytes) ->
            val observation = GitHubActionsCheckoutRuntimeCertification.observation(p, id, bytes)
            val signer = Signature.getInstance("Ed25519"); signer.initSign(key.private)
            signer.update(CertificationObservationAuthentication.signingBytes(runner, trust.challenge, observation))
            SignedCertificationObservation(runner, trust.challenge, observation, signer.sign())
        }
        return CertificationEvidenceViews.assess(p.bundle, p.bundle.adapter, listOf(p.bound), p.provider, signed, trust, CertificationEvidenceResolver { p.evidence[it.id] })
    }

    @Test fun signaturesAdmitOnlyConditionEvidenceAndDoNotReplaceTheBehavioralOracle() {
        for (value in SharedConditionFixture.values) {
            val raw = nativeResults(); val p = github(value, raw); val assessment = assess(p, value, raw)
            assertTrue(assessment.admission.valid, assessment.admission.findings.toString())
            val matrix = mapper.readTree(AdapterBehaviorMatrix.deriveGitHubCondition(assessment, value, CertificationEvidenceResolver { p.evidence[it.id] }).json())
            assertEquals(listOf("CONDITIONS"), matrix["rows"].filter { !it["behavior"].isNull }.map { it["construct"].asText() })
            assertFalse(matrix["publicSupportPromoted"].asBoolean()); assertFalse(matrix["portableExecution"].asBoolean())
            val executedId = if (value) "baseline" else "flattened-condition"
            (raw[SharedConditionFixture.job(value, executedId)]["outputs"] as ObjectNode).put("observation", SharedCheckoutFixture.expected("substituted-revision").toString(Charsets.UTF_8).trimEnd())
            val wrong = assess(github(value, raw), value, raw)
            assertFalse(wrong.admission.valid)
            assertTrue(wrong.admission.findings.any { it.code == "OBSERVATION_MISMATCH" })
        }
    }
}
