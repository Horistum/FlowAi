package org.flowlang.adapters.certification

import java.security.Signature
import java.util.Collections
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog

/**
 * Authenticates runner statements before compiler/rendering-bound integrity admission.
 * A trusted signature attests origin, not runner honesty or behavioral equivalence.
 */
object AuthenticatedAdapterCertificationAdmission {
    fun evaluate(
        bundle: AdapterCertificationBundle,
        expectedAdapter: CertificationAdapterIdentity,
        boundScenarios: List<BoundCertificationScenario>,
        provider: TargetNativeProjectionCatalog,
        observations: List<SignedCertificationObservation>,
        trust: CertificationObservationTrust,
        resolver: CertificationEvidenceResolver
    ): CertificationAdmissionReport {
        val findings = mutableListOf<CertificationAdmissionFinding>()
        fun reject(code: String, subject: String) { findings += CertificationAdmissionFinding(code, subject) }
        fun rejected() = CertificationAdmissionReport(
            Collections.unmodifiableList(findings.distinct().sortedWith(compareBy({ it.code }, { it.subject }))), emptyList())
        if (observations.size > CertificationObservationAuthentication.MAX_RUNS) {
            reject("SIGNED_RUN_BUDGET_EXCEEDED", "observations")
            return rejected()
        }
        val expected = trust.authorizedRuns.associateBy { it.runId }
        val ids = observations.map { it.observation.runId }
        if (ids.toSet() != expected.keys || ids.distinct().size != ids.size)
            reject("AUTHORIZED_RUN_INVENTORY_MISMATCH", "observations")
        observations.forEach { signed ->
            val run = signed.observation
            val authorization = expected[run.runId]
            if (authorization == null || authorization.scenarioId != run.scenarioId ||
                authorization.mutantId != run.mutantId || authorization.runnerId != signed.runnerId)
                reject("RUN_AUTHORIZATION_MISMATCH", run.runId)
            if (signed.challenge != trust.challenge) reject("ASSESSMENT_CHALLENGE_MISMATCH", run.runId)
            if (signed.runnerId !in trust.trustedRunners) reject("UNTRUSTED_RUNNER", run.runId)
        }
        if (findings.isNotEmpty()) return rejected()
        observations.forEach { signed ->
            val statement = runCatching { CertificationObservationAuthentication.signingBytes(
                signed.runnerId, signed.challenge, signed.observation) }.getOrNull()
            if (statement == null) reject("INVALID_SIGNED_STATEMENT", signed.observation.runId)
            else {
                val verified = runCatching {
                    val verifier = Signature.getInstance("Ed25519")
                    verifier.initVerify(trust.trustedRunners.getValue(signed.runnerId))
                    verifier.update(statement)
                    verifier.verify(signed.signature())
                }.getOrDefault(false)
                if (!verified) reject("INVALID_RUNNER_SIGNATURE", signed.observation.runId)
            }
        }
        // No resolver access or partial scenario admission can precede complete authentication.
        if (findings.isNotEmpty()) return rejected()
        return BoundAdapterCertificationAdmission.evaluate(bundle, expectedAdapter, boundScenarios, provider,
            observations.map { it.observation }, resolver)
    }
}
