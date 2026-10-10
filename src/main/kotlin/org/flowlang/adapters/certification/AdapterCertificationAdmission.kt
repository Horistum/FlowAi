package org.flowlang.adapters.certification

import java.security.MessageDigest
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.io.InputLimits

/**
 * Checks scope, bytes and mutation polarity against independently supplied inputs.
 * The caller authenticates the adapter/runner and derives the semantic inventory.
 * This component neither executes artifacts nor authenticates provenance by itself.
 */
object AdapterCertificationAdmission {
    fun evaluate(
        bundle: AdapterCertificationBundle,
        expectedAdapter: CertificationAdapterIdentity,
        semanticCapabilities: Set<String>,
        provider: TargetNativeProjectionCatalog,
        observations: List<CertificationExecutionObservation>,
        resolver: CertificationEvidenceResolver
    ): CertificationAdmissionReport {
        val findings = mutableListOf<CertificationAdmissionFinding>()
        fun reject(code: String, subject: String) { findings += CertificationAdmissionFinding(code, subject) }
        fun unique(values: List<*>, subject: String) {
            if (values.size != values.toSet().size) reject("DUPLICATE_IDENTITY", subject)
        }
        val adapter = bundle.adapter
        if (adapter != expectedAdapter || provider.target != adapter.target) reject("ADAPTER_MISMATCH", "adapter")
        if (listOf(adapter.target, adapter.adapterId, adapter.version).any(String::isBlank) ||
            !digest.matches(adapter.implementationSha256)) reject("INVALID_ADAPTER_IDENTITY", "adapter")
        if (bundle.limitations.isEmpty() || bundle.limitations.any(String::isBlank)) reject("MISSING_LIMITATIONS", "bundle")
        unique(bundle.limitations, "limitations")
        if (semanticCapabilities.any(String::isBlank)) reject("INVALID_SEMANTIC_INVENTORY", "coverage")

        val expectedSubjects: Set<CertificationSubject> = semanticCapabilities.map { CertificationSubject.Semantic(it) }.toSet() +
            TargetStructuralProjectionKind.entries.map { CertificationSubject.Structural(it) } +
            (provider.definitions.map { CertificationSubject.Leaf(it.kind, it.reference) } +
                provider.approvalDefinitions.map { CertificationSubject.Leaf(it.kind, it.reference) })
        unique(bundle.coverage.map { it.subject }, "coverage")
        if (bundle.coverage.map { it.subject }.toSet() != expectedSubjects) reject("COVERAGE_INVENTORY_MISMATCH", "coverage")
        unique(bundle.scenarios.map { it.id }, "scenarios")
        if (bundle.scenarios.any { it.id.isBlank() }) reject("INVALID_SCENARIO_ID", "scenarios")
        val scenarioIds = bundle.scenarios.map { it.id }.toSet()
        val usedScenarios = bundle.coverage.flatMap { it.scenarioIds }.toSet()
        if (usedScenarios != scenarioIds) reject("SCENARIO_COVERAGE_MISMATCH", "coverage")
        bundle.coverage.forEach { row ->
            val subject = row.subject.toString()
            unique(row.scenarioIds, subject)
            if (row.limitation.isBlank()) reject("MISSING_LIMITATIONS", subject)
            if (row.subject is CertificationSubject.Structural && row.scenarioIds.isNotEmpty() &&
                !provider.hasStructuralProjection(row.subject.kind)) reject("STRUCTURE_NOT_IMPLEMENTED", subject)
        }

        val expectedRuns = bundle.scenarios.flatMap { scenario ->
            listOf(scenario.id to null) + scenario.negativeMutants.map { scenario.id to it.id }
        }.toSet()
        unique(observations.map { it.scenarioId to it.mutantId }, "observations")
        unique(observations.map { it.runId }, "runIds")
        if (observations.map { it.scenarioId to it.mutantId }.toSet() != expectedRuns) reject("RUN_INVENTORY_MISMATCH", "observations")
        val runs = observations.associateBy { it.scenarioId to it.mutantId }
        val evidence = linkedMapOf<String, CertificationEvidenceReference>()
        fun register(reference: CertificationEvidenceReference) {
            val previous = evidence.putIfAbsent(reference.id, reference)
            if (previous != null && previous != reference) reject("EVIDENCE_IDENTITY_CONFLICT", reference.id)
        }
        fun runtimeValid(runtime: List<CertificationRuntimePrerequisite>): Boolean = runtime.isNotEmpty() &&
            runtime.none { it.id.isBlank() || it.version.isBlank() } && runtime.map { it.id }.distinct().size == runtime.size

        bundle.scenarios.forEach { scenario ->
            val id = scenario.id
            listOf(scenario.canonicalGraph, scenario.fixture, scenario.artifact, scenario.expectedObservation).forEach(::register)
            if (!runtimeValid(scenario.runtimePrerequisites)) reject("INVALID_RUNTIME_PREREQUISITES", id)
            if (scenario.negativeMutants.isEmpty()) reject("MISSING_NEGATIVE_MUTANT", id)
            unique(scenario.negativeMutants.map { it.id }, "$id.mutants")
            if (scenario.negativeMutants.any { it.id.isBlank() }) reject("INVALID_MUTANT_ID", id)
            scenario.negativeMutants.forEach { mutant ->
                register(mutant.artifact)
                register(mutant.expectedObservation)
                if (mutant.artifact.sha256 == scenario.artifact.sha256) reject("UNCHANGED_MUTANT_ARTIFACT", "$id/${mutant.id}")
                if (mutant.expectedObservation.sha256 == scenario.expectedObservation.sha256) reject("SURVIVING_MUTANT", "$id/${mutant.id}")
            }
            fun inspect(mutantId: String?, artifact: CertificationEvidenceReference, expected: CertificationEvidenceReference) {
                val run = runs[id to mutantId] ?: return
                val subject = if (mutantId == null) id else "$id/$mutantId"
                register(run.artifact)
                register(run.observation)
                if (run.runId.isBlank()) reject("INVALID_RUN_ID", subject)
                if (run.adapter != expectedAdapter || run.adapter != adapter) reject("ADAPTER_MISMATCH", subject)
                if (run.canonicalGraphSha256 != scenario.canonicalGraph.sha256 || run.fixtureSha256 != scenario.fixture.sha256)
                    reject("SCENARIO_INPUT_MISMATCH", subject)
                if (!runtimeValid(run.runtimePrerequisites) || run.runtimePrerequisites.toSet() != scenario.runtimePrerequisites.toSet())
                    reject("RUNTIME_MISMATCH", subject)
                if (run.artifact.sha256 != artifact.sha256 || run.artifact.sizeBytes != artifact.sizeBytes)
                    reject("ARTIFACT_MISMATCH", subject)
                if (run.outcome != CertificationRunOutcome.COMPLETED) reject("RUN_NOT_COMPLETED", subject)
                if (run.observation.id == expected.id) reject("EXPECTED_OBSERVATION_REUSED", subject)
                if (run.observation.sha256 != expected.sha256 || run.observation.sizeBytes != expected.sizeBytes)
                    reject("OBSERVATION_MISMATCH", subject)
            }
            inspect(null, scenario.artifact, scenario.expectedObservation)
            scenario.negativeMutants.forEach { inspect(it.id, it.artifact, it.expectedObservation) }
        }

        // Bound the complete resolution budget before invoking user-supplied I/O.
        if (evidence.size > InputLimits.MAX_FILES || evidence.values.sumOf { it.sizeBytes.toLong().coerceAtLeast(0) } >
            InputLimits.MAX_TOTAL_INPUT_BYTES) reject("EVIDENCE_BUDGET_EXCEEDED", "evidence")
        evidence.values.forEach { reference ->
            if (reference.id.isBlank() || !digest.matches(reference.sha256) || reference.sizeBytes !in 1..InputLimits.MAX_ARTIFACT_BYTES)
                reject("INVALID_EVIDENCE_REFERENCE", reference.id)
        }
        // Malformed candidates must not trigger I/O or accidentally yield a partial admission.
        if (findings.isEmpty()) evidence.values.forEach { reference ->
            val result = runCatching { resolver.resolve(reference) }
            val bytes = result.getOrNull()
            when {
                result.isFailure -> reject("EVIDENCE_RESOLUTION_FAILED", reference.id)
                bytes == null -> reject("EVIDENCE_UNAVAILABLE", reference.id)
                bytes.size != reference.sizeBytes || sha256(bytes) != reference.sha256 -> reject("EVIDENCE_BYTES_MISMATCH", reference.id)
            }
        }
        return CertificationAdmissionReport(
            java.util.Collections.unmodifiableList(findings.distinct().sortedWith(compareBy({ it.code }, { it.subject }))),
            java.util.Collections.unmodifiableList(if (findings.isEmpty()) scenarioIds.sorted() else emptyList())
        )
    }

    private val digest = Regex("[0-9a-f]{64}")
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
