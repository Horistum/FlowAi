package org.flowlang.conformance

import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import kotlin.test.*
import org.flowlang.adapters.certification.*
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.generators.manifest.TargetStructuralProjectionKind

/** Synthetic signed protocol inputs, never claimed as real runtime evidence. */
class AdapterBehaviorMatrixTests {
    private class Fixture(scenario: JenkinsCheckoutRuntimeCertification.Scenario = JenkinsCheckoutRuntimeCertification.Scenario.CHECKOUT) {
        val prepared = JenkinsCheckoutRuntimeCertification.prepare(File("."), "sha256:" + "a".repeat(64), scenario)
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val trust = CertificationObservationTrust("c".repeat(32), mapOf("test-runner" to key.public),
            scenario.runIds.map { CertificationAuthorizedRun(it, prepared.bound.id, it.takeUnless { id -> id == "baseline" }, "test-runner") })
        val signed = scenario.runIds.map { id ->
            val spec = prepared.bundle.scenarios.single()
            val mutant = spec.negativeMutants.singleOrNull { it.id == id }
            val expected = mutant?.expectedObservation ?: spec.expectedObservation
            val bytes = prepared.evidence.getValue(expected.id).copyOf()
            val observation = CertificationEvidenceReference("test-observed:$id", expected.sha256, bytes.size)
            prepared.evidence[observation.id] = bytes
            sign(CertificationExecutionObservation(id, prepared.bundle.adapter, prepared.bound.id, mutant?.id,
                prepared.bound.canonicalGraph.sha256, prepared.bound.fixture.sha256, mutant?.artifact ?: spec.artifact,
                observation, prepared.runtime, CertificationRunOutcome.COMPLETED))
        }

        fun sign(observation: CertificationExecutionObservation): SignedCertificationObservation {
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(key.private)
            signer.update(CertificationObservationAuthentication.signingBytes("test-runner", trust.challenge, observation))
            return SignedCertificationObservation("test-runner", trust.challenge, observation, signer.sign())
        }

        fun assess(bundle: AdapterCertificationBundle = prepared.bundle, observations: List<SignedCertificationObservation> = signed,
                   resolver: CertificationEvidenceResolver = CertificationEvidenceResolver { prepared.evidence[it.id] }) =
            CertificationEvidenceViews.assess(bundle, bundle.adapter, listOf(prepared.bound),
                ReferenceTargetProjections.nativeCatalogs.getValue("jenkins"), observations, trust, resolver)
    }

    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    private fun matrix(f: Fixture, result: CertificationEvidenceViewResult = f.assess(),
                       scenario: JenkinsCheckoutRuntimeCertification.Scenario = f.prepared.scenario,
                       resolver: CertificationEvidenceResolver = CertificationEvidenceResolver { f.prepared.evidence[it.id] }) =
        AdapterBehaviorMatrix.derive(result, scenario, resolver)
    private fun rows(value: AdapterBehaviorMatrix) = mapper.readTree(value.json())["rows"]
    private fun behavior(value: AdapterBehaviorMatrix) = rows(value).single { !it["behavior"].isNull }["behavior"]

    @Test fun allTenScenariosRetainExactOraclesSignedRunsAndRuntimeBindings() {
        for (s in JenkinsCheckoutRuntimeCertification.Scenario.entries) {
            val f = Fixture(s); val value = matrix(f); val root = mapper.readTree(value.json())
            assertEquals("authenticated-single-scenario-behavior-matrix", root["scope"].asText())
            assertFalse(root["portableExecution"].asBoolean()); assertFalse(root["publicSupportPromoted"].asBoolean())
            assertEquals(CertificationConstruct.entries.map { it.name }, rows(value).map { it["construct"].asText() })
            val b = behavior(value)
            val expectedCategory = when {
                s.retry -> "RETRY"
                s.approval -> "APPROVALS"
                s.conditional -> "CONDITIONS"
                s.terminalEvidence -> "ERROR_HANDLING"
                s == JenkinsCheckoutRuntimeCertification.Scenario.FAILURE -> "FAILURE_PROPAGATION"
                else -> "NATIVE_CHECKOUT"
            }
            assertEquals(expectedCategory, rows(value).single { !it["behavior"].isNull }["construct"].asText())
            assertEquals(s.id, b["scenarioId"].asText())
            assertEquals(f.prepared.bound.canonicalGraph.sha256, b["canonicalGraph"]["sha256"].asText())
            assertEquals(f.prepared.bound.fixture.sha256, b["source"]["sha256"].asText())
            assertEquals(f.prepared.runtime.size, b["runtimePrerequisites"].size())
            val runs = listOf(b["baseline"]) + b["negativeMutants"].toList()
            assertEquals(s.runIds.toSet(), runs.map { it["evidence"]["runId"].asText() }.toSet())
            for (run in runs) {
                val evidence = run["evidence"]
                val expected = f.prepared.evidence.getValue(evidence["expectedObservation"]["id"].asText())
                assertEquals(expected.toString(Charsets.UTF_8), run["expectedObservationUtf8"].asText())
                assertEquals(run["expectedObservationUtf8"], run["observedUtf8"])
                assertEquals("test-runner", evidence["runnerId"].asText())
                assertTrue(run["matchesOracle"].asBoolean())
                assertEquals(!evidence["mutantId"].isNull, run["distinguishesBaseline"].asBoolean())
            }
        }
    }

    @Test fun checkoutNeverCreditsWorkspaceSecretsOrStateContinuity() {
        val value = matrix(Fixture())
        assertEquals("NATIVE_CHECKOUT", rows(value).single { !it["behavior"].isNull }["construct"].asText())
        assertTrue(rows(value).filter { it["construct"].asText() != "NATIVE_CHECKOUT" }.all {
            it["status"].asText() == "NO_BEHAVIORAL_EVIDENCE_IN_ASSESSMENT" && it["behavior"].isNull })
        assertTrue(value.markdown().contains("do not establish artifact transfer"))
    }

    @Test fun finalSuccessCannotHideApprovalOrderingOrLocalRecoveryDifferences() {
        for (s in listOf(JenkinsCheckoutRuntimeCertification.Scenario.APPROVAL_APPROVE,
                         JenkinsCheckoutRuntimeCertification.Scenario.RECOVERY_SUCCESS)) {
            val b = behavior(matrix(Fixture(s)))
            val baseline = mapper.readTree(b["baseline"]["observedUtf8"].asText())
            for (mutant in b["negativeMutants"]) {
                val observed = mapper.readTree(mutant["observedUtf8"].asText())
                assertEquals(baseline["result"], observed["result"])
                assertEquals(baseline["marker"], observed["marker"])
                assertNotEquals(baseline, observed)
                assertTrue(mutant["distinguishesBaseline"].asBoolean())
            }
        }
    }

    @Test fun rejectedAssessmentCannotReadOrPublishAnyMatrix() {
        val f = Fixture(); var resolutions = 0
        val rejected = f.assess(observations = f.signed.drop(1))
        assertFalse(rejected.admission.valid)
        assertFailsWith<IllegalArgumentException> { matrix(f, rejected, resolver = CertificationEvidenceResolver { resolutions++; null }) }
        assertEquals(0, resolutions)
    }

    @Test fun crossScenarioAndInventedAdmissionCannotBorrowExistingEvidence() {
        val f = Fixture(); val result = f.assess()
        assertFailsWith<IllegalArgumentException> { matrix(f, result, JenkinsCheckoutRuntimeCertification.Scenario.FAILURE) }
        assertFailsWith<IllegalArgumentException> { matrix(f, result.copy(admission = result.admission.copy(admittedScenarioIds = emptyList()))) }
        assertFailsWith<IllegalArgumentException> { matrix(f, result.copy(view = null)) }
    }

    @Test fun resolverSubstitutionOrMissingBytesAfterAdmissionFailsClosed() {
        val f = Fixture(); val result = f.assess()
        for (bytes in listOf(null, byteArrayOf(), "{}".toByteArray(), ByteArray(65537))) {
            assertFailsWith<IllegalArgumentException> { matrix(f, result, resolver = CertificationEvidenceResolver { bytes }) }
        }
        val mutantRef = f.signed[1].observation.observation
        val baseline = f.prepared.evidence.getValue(f.signed[0].observation.observation.id)
        assertFailsWith<IllegalArgumentException> { matrix(f, result, resolver = CertificationEvidenceResolver {
            if (it == mutantRef) baseline else f.prepared.evidence[it.id] }) }
    }

    @Test fun inputOrderAndLaterCallerMutationCannotChangeFrozenOutput() {
        val f = Fixture(); val original = matrix(f)
        val reversed = f.prepared.bundle.copy(coverage = f.prepared.bundle.coverage.reversed(),
            limitations = f.prepared.bundle.limitations.reversed(), scenarios = f.prepared.bundle.scenarios.map {
                it.copy(runtimePrerequisites = it.runtimePrerequisites.reversed(), negativeMutants = it.negativeMutants.reversed()) })
        val reordered = matrix(f, f.assess(reversed, f.signed.reversed()))
        assertEquals(original.json(), reordered.json()); assertEquals(original.markdown(), reordered.markdown())
        val json = original.json(); val markdown = original.markdown()
        f.prepared.evidence.values.forEach { it.fill(0) }; f.prepared.evidence.clear()
        assertEquals(json, original.json()); assertEquals(markdown, original.markdown())
    }

    @Test fun markdownEscapesExternalTextAndJsonPreservesExactLimitations() {
        val f = Fixture(); val unsafe = "Limit | <script> [link](https://example.invalid)\n# Support **claim** & `code`"
        val result = f.assess(f.prepared.bundle.copy(limitations = listOf(unsafe)))
        val value = matrix(f, result)
        assertEquals(unsafe, behavior(value)["limitations"][0].asText())
        assertFalse(value.markdown().contains("<script>")); assertFalse(value.markdown().contains("\n# Support"))
        assertFalse(value.markdown().contains("**claim**")); assertTrue(value.markdown().contains("&#124;"))
    }
}
