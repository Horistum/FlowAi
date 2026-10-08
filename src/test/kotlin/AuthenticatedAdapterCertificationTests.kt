package org.flowlang.conformance

import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.*
import org.flowlang.adapters.certification.*

class AuthenticatedAdapterCertificationTests {
    private class Fixture {
        val bound = BoundAdapterCertificationTests.Fixture()
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val challenge = "assessment_" + "a".repeat(32)
        val runs = bound.runs.map { CertificationAuthorizedRun(it.runId, it.scenarioId, it.mutantId, "runner") }
        val trust = CertificationObservationTrust(challenge, mapOf("runner" to key.public), runs)
        fun sign(run: CertificationExecutionObservation, runner: String = "runner", nonce: String = challenge): SignedCertificationObservation {
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(key.private)
            signer.update(CertificationObservationAuthentication.signingBytes(runner, nonce, run))
            return SignedCertificationObservation(runner, nonce, run, signer.sign())
        }
        val signed get() = bound.runs.map { sign(it) }
        fun evaluate(observations: List<SignedCertificationObservation> = signed, policy: CertificationObservationTrust = trust,
            candidate: AdapterCertificationBundle = bound.bundle) = AuthenticatedAdapterCertificationAdmission.evaluate(
                candidate, bound.identity, bound.inputs, bound.provider, observations, policy,
                CertificationEvidenceResolver { bound.externalResolutions++; bound.bytes[it.id] })
        fun rejected(observations: List<SignedCertificationObservation>, code: String, policy: CertificationObservationTrust = trust) {
            val report = evaluate(observations, policy)
            assertFalse(report.valid); assertTrue(report.admittedScenarioIds.isEmpty())
            assertTrue(report.findings.any { it.code == code }, report.findings.toString())
            assertEquals(0, bound.externalResolutions)
        }
    }

    @Test fun signedIndependentObservationsRetainBoundInputAndMutationAdmission() {
        val f = Fixture(); val report = f.evaluate()
        assertTrue(report.valid, report.findings.toString())
        assertEquals(f.bound.inputs.map { it.id }, report.admittedScenarioIds)
        assertTrue(f.bound.externalResolutions > 0)
    }

    @Test fun everyObservationFieldIsCoveredByTheSignature() {
        val f = Fixture(); val signed = f.signed; val old = signed.first(); val run = old.observation
        val changed = listOf(
            run.copy(runId = "different"), run.copy(scenarioId = "different"), run.copy(mutantId = "different"),
            run.copy(adapter = run.adapter.copy(target = "different")), run.copy(adapter = run.adapter.copy(adapterId = "different")),
            run.copy(adapter = run.adapter.copy(version = "different")), run.copy(adapter = run.adapter.copy(implementationSha256 = "b".repeat(64))),
            run.copy(canonicalGraphSha256 = "b".repeat(64)), run.copy(fixtureSha256 = "b".repeat(64)),
            run.copy(artifact = run.artifact.copy(id = "different")), run.copy(artifact = run.artifact.copy(sha256 = "b".repeat(64))),
            run.copy(artifact = run.artifact.copy(sizeBytes = run.artifact.sizeBytes + 1)),
            run.copy(observation = run.observation.copy(id = "different")), run.copy(observation = run.observation.copy(sha256 = "b".repeat(64))),
            run.copy(observation = run.observation.copy(sizeBytes = run.observation.sizeBytes + 1)),
            run.copy(runtimePrerequisites = listOf(run.runtimePrerequisites.single().copy(id = "different"))),
            run.copy(runtimePrerequisites = listOf(run.runtimePrerequisites.single().copy(version = "different"))),
            run.copy(runtimePrerequisites = run.runtimePrerequisites + CertificationRuntimePrerequisite("extra", "1")),
            run.copy(outcome = CertificationRunOutcome.TIMEOUT))
        for (candidate in changed) {
            val forged = SignedCertificationObservation(old.runnerId, old.challenge, candidate, old.signature())
            // Re-pin the run slot so signature verification itself must detect identity-field changes.
            val runs = listOf(CertificationAuthorizedRun(candidate.runId, candidate.scenarioId, candidate.mutantId, "runner")) + f.runs.drop(1)
            val trust = CertificationObservationTrust(f.challenge, mapOf("runner" to f.key.public), runs)
            f.rejected(listOf(forged) + signed.drop(1), "INVALID_RUNNER_SIGNATURE", trust)
        }
    }

    @Test fun runnerAndChallengeAreSignedEvenIfTheCallerRepinsThem() {
        val f = Fixture()
        for ((runner, challenge) in listOf("second-runner" to f.challenge, "runner" to "b".repeat(32))) {
            val observations = f.signed.map { SignedCertificationObservation(runner, challenge, it.observation, it.signature()) }
            val trust = CertificationObservationTrust(challenge, mapOf(runner to f.key.public), f.runs.map { it.copy(runnerId = runner) })
            f.rejected(observations, "INVALID_RUNNER_SIGNATURE", trust)
        }
    }

    @Test fun staleChallengeFailsBeforeEvidenceResolution() {
        val f = Fixture()
        f.rejected(f.bound.runs.map { f.sign(it, nonce = "b".repeat(32)) }, "ASSESSMENT_CHALLENGE_MISMATCH")
    }

    @Test fun candidateCannotIntroduceItsOwnRunnerOrUseAnotherTrustedRunnersSlot() {
        val f = Fixture(); val observations = f.bound.runs.map { f.sign(it, runner = "other") }
        f.rejected(observations, "UNTRUSTED_RUNNER")
        val trust = CertificationObservationTrust(f.challenge, mapOf("runner" to f.key.public, "other" to f.key.public), f.runs)
        f.rejected(observations, "RUN_AUTHORIZATION_MISMATCH", trust)
    }

    @Test fun wrongTrustedKeyMissingSignatureAndCorruptSignatureAreRejected() {
        val f = Fixture(); val signed = f.signed
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        f.rejected(signed, "INVALID_RUNNER_SIGNATURE", CertificationObservationTrust(f.challenge, mapOf("runner" to other.public), f.runs))
        for (bytes in listOf(byteArrayOf(), ByteArray(64), signed.first().signature().also { it[0] = (it[0].toInt() xor 1).toByte() }))
            f.rejected(listOf(SignedCertificationObservation("runner", f.challenge, signed.first().observation, bytes)) + signed.drop(1),
                "INVALID_RUNNER_SIGNATURE")
    }

    @Test fun omittedDuplicateAndUnexpectedRunsCannotYieldPartialAdmission() {
        val f = Fixture(); val signed = f.signed
        for (observations in listOf(emptyList(), signed.drop(1), signed + signed.first(),
            signed + f.sign(signed.first().observation.copy(runId = "extra"))))
            f.rejected(observations, "AUTHORIZED_RUN_INVENTORY_MISMATCH")
        val changed = signed.first().observation.copy(scenarioId = "other")
        f.rejected(listOf(f.sign(changed)) + signed.drop(1), "RUN_AUTHORIZATION_MISMATCH")
    }

    @Test fun runAndStatementBudgetsFailBeforeResolverIo() {
        val f = Fixture(); val signed = f.signed
        f.rejected(List(257) { signed.first() }, "SIGNED_RUN_BUDGET_EXCEEDED")
        for (run in listOf(signed.first().observation.copy(fixtureSha256 = "x".repeat(4097)),
            signed.first().observation.copy(runtimePrerequisites = List(256) { CertificationRuntimePrerequisite("x".repeat(4096), "1") }),
            signed.first().observation.copy(fixtureSha256 = "\uD800"))) {
            val oversized = SignedCertificationObservation("runner", f.challenge, run, signed.first().signature())
            f.rejected(listOf(oversized) + signed.drop(1), "INVALID_SIGNED_STATEMENT")
        }
    }

    @Test fun mutableInputsCannotChangeSignedObservationsOrTrust() {
        val f = Fixture(); val original = f.signed.first()
        val runtime = original.observation.runtimePrerequisites.toMutableList(); val signature = original.signature()
        val frozen = SignedCertificationObservation("runner", f.challenge, original.observation.copy(runtimePrerequisites = runtime), signature)
        runtime.clear(); signature.fill(0); frozen.signature().fill(0)
        assertEquals(original.observation, frozen.observation); assertContentEquals(original.signature(), frozen.signature())
        assertFailsWith<UnsupportedOperationException> { (frozen.observation.runtimePrerequisites as MutableList).clear() }
        val keys = mutableMapOf("runner" to f.key.public); val runs = f.runs.toMutableList()
        val trust = CertificationObservationTrust(f.challenge, keys, runs); keys.clear(); runs.clear()
        assertTrue(f.evaluate(listOf(frozen) + f.signed.drop(1), trust).valid)
        assertFailsWith<UnsupportedOperationException> { (trust.trustedRunners as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (trust.authorizedRuns as MutableList).clear() }
    }

    @Test fun trustRequiresAnExplicitUniqueInventoryAndEd25519Keys() {
        val f = Fixture()
        for (runs in listOf(emptyList(), f.runs + f.runs.first(), listOf(f.runs.first().copy(runnerId = "unknown")),
            listOf(f.runs.first().copy(runId = " ")), List(257) { f.runs.first() }))
            assertFailsWith<IllegalArgumentException> { CertificationObservationTrust(f.challenge, mapOf("runner" to f.key.public), runs) }
        assertFailsWith<IllegalArgumentException> { CertificationObservationTrust("short", mapOf("runner" to f.key.public), f.runs) }
        assertFailsWith<IllegalArgumentException> { CertificationObservationTrust(f.challenge, emptyMap(), f.runs) }
        val rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair()
        assertFails { CertificationObservationTrust(f.challenge, mapOf("runner" to rsa.public), f.runs) }
        assertFailsWith<IllegalArgumentException> { SignedCertificationObservation("runner", f.challenge, f.bound.runs.first(), ByteArray(65)) }
    }

    @Test fun signedInfrastructureFailureAndSurvivingMutantStillFail() {
        val f = Fixture()
        for (outcome in listOf(CertificationRunOutcome.INFRASTRUCTURE_FAILURE, CertificationRunOutcome.TIMEOUT))
            f.rejected(f.bound.runs.map { f.sign(it.copy(outcome = outcome)) }, "RUN_NOT_COMPLETED")
        val mutant = f.bound.runs.last().copy(observation = f.bound.runs.first().observation)
        f.rejected(listOf(f.sign(f.bound.runs.first()), f.sign(mutant)), "OBSERVATION_MISMATCH")
    }

    @Test fun validSignaturesCannotOverrideCompilerBindingsOrEvidenceBytes() {
        val f = Fixture(); val s = f.bound.scenarios.single()
        val forged = s.copy(fixture = s.fixture.copy(sha256 = "b".repeat(64)))
        val report = f.evaluate(candidate = f.bound.bundle.copy(scenarios = listOf(forged)))
        assertTrue(report.findings.any { it.code == "BOUND_SCENARIO_INPUT_MISMATCH" }); assertEquals(0, f.bound.externalResolutions)
        f.bound.bytes[f.bound.runs.first().observation.id] = "forged".toByteArray()
        val bytesReport = f.evaluate()
        assertFalse(bytesReport.valid); assertTrue(bytesReport.admittedScenarioIds.isEmpty())
        assertTrue(bytesReport.findings.any { it.code == "EVIDENCE_BYTES_MISMATCH" })
    }
}
