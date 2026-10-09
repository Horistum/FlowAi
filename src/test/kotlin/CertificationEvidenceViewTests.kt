package org.flowlang.conformance

import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import kotlin.test.*
import org.flowlang.adapters.certification.*
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.generators.manifest.TargetStructuralProjectionKind

/** Synthetic signed observations exercise view integrity; real execution remains a separate CI gate. */
class CertificationEvidenceViewTests {
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

    @Test fun generatedViewKeepsEveryUncoveredConstructAndExactBoundedRunIdentity() {
        val f = Fixture(); val result = f.assess()
        assertTrue(result.admission.valid, result.admission.findings.toString())
        val view = assertNotNull(result.view)
        assertFalse(view.publicSupportPromoted); assertFalse(view.portableExecution)
        assertEquals(f.prepared.bundle.coverage.map { it.subject }.toSet(), view.coverage.map { it.subject }.toSet())
        val structures = view.coverage.filter { it.subject is CertificationSubject.Structural }
        assertEquals(TargetStructuralProjectionKind.entries.size, structures.size)
        assertTrue(structures.all { it.status == CertificationOccurrenceStatus.NOT_OBSERVED && it.scenarioIds.isEmpty() })
        assertEquals(f.prepared.bound.subjects, view.coverage.filter {
            it.status == CertificationOccurrenceStatus.OBSERVED_IN_SCENARIO }.map { it.subject }.toSet())
        val scenario = view.scenarios.single()
        assertEquals(CertificationScenarioShape.NATIVE_LEAF_ONLY, scenario.shape)
        assertEquals(f.prepared.bound.fixture, scenario.source)
        assertEquals(f.prepared.bound.canonicalGraph, scenario.canonicalGraph)
        assertEquals(f.prepared.runtime.toSet(), scenario.runtimePrerequisites.toSet())
        assertEquals(f.signed.map { it.observation.runId }.toSet(), scenario.runs.map { it.runId }.toSet())
        assertNull(scenario.runs.first().mutantId)
        scenario.runs.forEach { run ->
            val observation = f.signed.single { it.observation.runId == run.runId }.observation
            assertEquals(observation.artifact, run.artifact); assertEquals(observation.observation, run.observed)
            assertEquals(run.expectedObservation.sha256, run.observed.sha256)
            assertEquals("test-runner", run.runnerId)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(f.key.public.encoded)
                .joinToString("") { "%02x".format(it.toInt() and 255) }, run.runnerKeySha256)
        }
        assertEquals(f.prepared.bundle.limitations.sorted(), view.limitations)
        assertTrue(view.markdown().contains("not proof of general capability support"))
    }

    @Test fun deliberateFailureObservationDoesNotPromoteErrorBoundarySupport() {
        val f = Fixture(JenkinsCheckoutRuntimeCertification.Scenario.FAILURE)
        val view = assertNotNull(f.assess().view)
        assertEquals("jenkins-failure-runtime", view.scenarios.single().id)
        assertEquals(CertificationScenarioShape.NATIVE_LEAF_ONLY, view.scenarios.single().shape)
        assertEquals(3, view.scenarios.single().runs.size)
        assertTrue(view.coverage.filter { it.subject is CertificationSubject.Structural }.all {
            it.status == CertificationOccurrenceStatus.NOT_OBSERVED })
        assertFalse(view.publicSupportPromoted)
    }

    @Test fun invalidSignatureAndMissingRunsCannotPublishAViewOrReadEvidence() {
        val f = Fixture(); var resolutions = 0
        val bad = SignedCertificationObservation(f.signed[0].runnerId, f.trust.challenge,
            f.signed[0].observation, ByteArray(64))
        for (runs in listOf(listOf(bad) + f.signed.drop(1), f.signed.drop(1))) {
            val result = f.assess(observations = runs, resolver = CertificationEvidenceResolver { resolutions++; null })
            assertFalse(result.admission.valid); assertNull(result.view)
        }
        assertEquals(0, resolutions)
    }

    @Test fun signedWrongOutcomeSurvivingMutantAndMissingBytesCannotPublishPartialSuccess() {
        val f = Fixture()
        val baseline = f.signed.first().observation
        val mutant = f.signed[1].observation
        for (changed in listOf(mutant.copy(outcome = CertificationRunOutcome.TIMEOUT),
            mutant.copy(observation = baseline.observation))) {
            val result = f.assess(observations = listOf(f.signed[0], f.sign(changed), f.signed[2]))
            assertFalse(result.admission.valid); assertTrue(result.admission.admittedScenarioIds.isEmpty()); assertNull(result.view)
        }
        val result = f.assess(resolver = CertificationEvidenceResolver { null })
        assertFalse(result.admission.valid); assertNull(result.view)
    }

    @Test fun inventedStructuralCoverageFailsBeforeAViewExists() {
        val f = Fixture()
        val coverage = f.prepared.bundle.coverage.map { row ->
            if (row.subject is CertificationSubject.Structural) row.copy(scenarioIds = listOf(f.prepared.bound.id)) else row
        }
        val result = f.assess(f.prepared.bundle.copy(coverage = coverage))
        assertNull(result.view)
        assertTrue(result.admission.findings.any { it.code == "BOUND_COVERAGE_MISMATCH" })
    }

    @Test fun inputOrderingCannotChangeTheGeneratedJsonOrMarkdown() {
        val f = Fixture()
        val original = assertNotNull(f.assess().view)
        val reversed = f.prepared.bundle.copy(coverage = f.prepared.bundle.coverage.reversed(),
            limitations = f.prepared.bundle.limitations.reversed(), scenarios = f.prepared.bundle.scenarios.map {
                it.copy(runtimePrerequisites = it.runtimePrerequisites.reversed(), negativeMutants = it.negativeMutants.reversed()) })
        val reordered = assertNotNull(f.assess(reversed, f.signed.reversed()).view)
        assertEquals(original.json(), reordered.json()); assertEquals(original.markdown(), reordered.markdown())
    }

    @Test fun resolverMutationCannotSubstituteUnvalidatedCoverageOrLimitations() {
        val f = Fixture()
        val limitations = f.prepared.bundle.limitations.toMutableList()
        val ids = mutableListOf(f.prepared.bound.id)
        val coverage = f.prepared.bundle.coverage.map { if (it.scenarioIds.isNotEmpty()) it.copy(scenarioIds = ids) else it }.toMutableList()
        val scenarios = f.prepared.bundle.scenarios.toMutableList()
        val signed = f.signed.toMutableList()
        val result = f.assess(f.prepared.bundle.copy(coverage = coverage, limitations = limitations, scenarios = scenarios), signed,
            CertificationEvidenceResolver { reference ->
                coverage.clear(); limitations.clear(); limitations += "Forged support"; ids.clear(); scenarios.clear(); signed.clear()
                f.prepared.evidence[reference.id]
            })
        val view = assertNotNull(result.view)
        assertEquals(f.prepared.bundle.coverage.size, view.coverage.size)
        assertEquals(f.prepared.bundle.limitations.sorted(), view.limitations)
        assertEquals(listOf(f.prepared.bound.id), view.scenarios.map { it.id })
        assertTrue(view.coverage.filter { it.status == CertificationOccurrenceStatus.OBSERVED_IN_SCENARIO }
            .all { it.scenarioIds == listOf(f.prepared.bound.id) })
    }

    @Test fun returnedListsAreImmutableAtEveryProjectionLevel() {
        val view = assertNotNull(Fixture().assess().view)
        val json = view.json()
        val lists = listOf(view.coverage, view.scenarios, view.limitations, view.scenarios[0].runs,
            view.scenarios[0].runtimePrerequisites, view.coverage.first { it.scenarioIds.isNotEmpty() }.scenarioIds)
        for (list in lists) assertFailsWith<UnsupportedOperationException> { (list as MutableList<*>).clear() }
        assertEquals(json, view.json())
    }

    @Test fun markdownEscapesProviderTextWhileJsonPreservesTheOriginal() {
        val f = Fixture()
        val text = "Limit | <script> [click](https://example.invalid)\n# Forged **support** & `code`"
        val bundle = f.prepared.bundle.copy(limitations = listOf(text), coverage = f.prepared.bundle.coverage.map { it.copy(limitation = text) })
        val view = assertNotNull(f.assess(bundle).view)
        assertEquals(listOf(text), view.limitations)
        val markdown = view.markdown()
        assertFalse(markdown.contains("<script>")); assertFalse(markdown.contains("[click]"))
        assertFalse(markdown.contains("\n# Forged")); assertFalse(markdown.contains("**support**"))
        assertTrue(markdown.contains("&#124;")); assertTrue(markdown.contains("&#60;script&#62;"))
        val json = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(view.json())
        assertEquals(text, json["limitations"][0].asText())
        assertEquals("authenticated-bounded-observations", json["scope"].asText())
    }
}
