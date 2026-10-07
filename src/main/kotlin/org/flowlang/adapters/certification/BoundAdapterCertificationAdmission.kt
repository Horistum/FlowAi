package org.flowlang.adapters.certification

import java.util.Collections
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog

/** Adds compiler/rendering provenance and exact occurrence scope to AR-06A integrity admission. */
object BoundAdapterCertificationAdmission {
    fun evaluate(
        bundle: AdapterCertificationBundle,
        expectedAdapter: CertificationAdapterIdentity,
        boundScenarios: List<BoundCertificationScenario>,
        provider: TargetNativeProjectionCatalog,
        observations: List<CertificationExecutionObservation>,
        resolver: CertificationEvidenceResolver
    ): CertificationAdmissionReport {
        val findings = mutableListOf<CertificationAdmissionFinding>()
        fun reject(code: String, subject: String) { findings += CertificationAdmissionFinding(code, subject) }
        val bound = boundScenarios.associateBy { it.id }
        if (bound.size != boundScenarios.size || bundle.scenarios.map { it.id }.toSet() != bound.keys)
            reject("BOUND_SCENARIO_INVENTORY_MISMATCH", "scenarios")
        boundScenarios.forEach {
            if (it.target != expectedAdapter.target || it.target != provider.target) reject("BOUND_TARGET_MISMATCH", it.id)
        }
        bundle.scenarios.forEach { scenario ->
            bound[scenario.id]?.let {
                if (scenario.canonicalGraph != it.canonicalGraph || scenario.fixture != it.fixture || scenario.artifact != it.artifact)
                    reject("BOUND_SCENARIO_INPUT_MISMATCH", scenario.id)
            }
        }
        val actualSubjects = boundScenarios.flatMap { it.subjects }.toSet()
        bundle.coverage.forEach { row ->
            val expectedIds = boundScenarios.filter { row.subject in it.subjects }.map { it.id }.toSet()
            if (row.scenarioIds.toSet() != expectedIds) reject("BOUND_COVERAGE_MISMATCH", row.subject.toString())
        }
        if (!bundle.coverage.map { it.subject }.containsAll(actualSubjects))
            reject("BOUND_COVERAGE_MISMATCH", "coverage")
        // Fail before external evidence resolution if inputs or scope have been substituted.
        if (findings.isNotEmpty()) return CertificationAdmissionReport(
            Collections.unmodifiableList(findings.distinct().sortedWith(compareBy({ it.code }, { it.subject }))), emptyList())
        val captured = boundScenarios.flatMap { scenario ->
            listOf(scenario.canonicalGraph, scenario.fixture, scenario.artifact).map { it to scenario }
        }.toMap()
        return AdapterCertificationAdmission.evaluate(bundle, expectedAdapter,
            actualSubjects.filterIsInstance<CertificationSubject.Semantic>().map { it.capability }.toSet(),
            provider, observations, CertificationEvidenceResolver { reference ->
                captured[reference]?.resolve(reference) ?: resolver.resolve(reference)
            })
    }
}
