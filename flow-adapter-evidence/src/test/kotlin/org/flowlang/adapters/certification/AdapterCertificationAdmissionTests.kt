package org.flowlang.adapters.certification

import java.security.MessageDigest
import kotlin.test.*
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetNativeProjectionDefinition
import org.flowlang.generators.manifest.TargetNativeStructuralProjectionDefinition
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.io.InputLimits

class AdapterCertificationAdmissionTests {
    private class Fixture {
        val bytes = linkedMapOf<String, ByteArray>()
        fun evidence(id: String, value: String): CertificationEvidenceReference {
            val content = value.toByteArray()
            bytes[id] = content
            return CertificationEvidenceReference(id, sha(content), content.size)
        }
        val adapter = CertificationAdapterIdentity("test-target", "test-adapter", "1.0", "a".repeat(64))
        val provider = TargetNativeProjectionCatalog.of(adapter.target,
            listOf(TargetNativeProjectionDefinition("test.leaf", "checkout")),
            structuralDefinitions = listOf(TargetNativeStructuralProjectionDefinition(
                TargetStructuralProjectionKind.CONDITION, "test.condition", "if",
                "test-implementation", "test-behavior")))
        val runtime = listOf(CertificationRuntimePrerequisite("test-runner", "1.0"))
        val scenario = CertificationScenario("condition-true",
            evidence("graph", "canonical graph: condition -> checkout"),
            evidence("fixture", "condition=true"),
            evidence("artifact", "if (condition) checkout()"),
            evidence("expected", "checkout=completed"), runtime,
            listOf(CertificationNegativeMutant("inverted-condition",
                evidence("mutant-artifact", "if (!condition) checkout()"),
                evidence("mutant-expected", "checkout=absent"))))
        val coverage = listOf(
            CertificationCoverage(CertificationSubject.Semantic("source.checkout"), listOf(scenario.id), "Only the declared scenario."),
            CertificationCoverage(CertificationSubject.Leaf("test.leaf", "checkout"), listOf(scenario.id), "Only the declared binding.")
        ) + TargetStructuralProjectionKind.entries.map { kind -> CertificationCoverage(
            CertificationSubject.Structural(kind), if (kind == TargetStructuralProjectionKind.CONDITION) listOf(scenario.id) else emptyList(),
            "Other structural scenarios are not certified.") }
        val bundle = AdapterCertificationBundle(adapter, coverage, listOf(scenario), listOf("Synthetic test adapter only."))
        val observations = listOf(
            CertificationExecutionObservation("positive-run", adapter, scenario.id, null, scenario.canonicalGraph.sha256,
                scenario.fixture.sha256, scenario.artifact, evidence("observed", "checkout=completed"), runtime,
                CertificationRunOutcome.COMPLETED),
            CertificationExecutionObservation("negative-run", adapter, scenario.id, "inverted-condition", scenario.canonicalGraph.sha256,
                scenario.fixture.sha256, scenario.negativeMutants.single().artifact, evidence("mutant-observed", "checkout=absent"), runtime,
                CertificationRunOutcome.COMPLETED)
        )
        var resolutions = 0
        fun evaluate(candidate: AdapterCertificationBundle = bundle,
            runs: List<CertificationExecutionObservation> = observations,
            expected: CertificationAdapterIdentity = adapter,
            catalog: TargetNativeProjectionCatalog = provider,
            resolve: CertificationEvidenceResolver = CertificationEvidenceResolver { resolutions++; bytes[it.id] }
        ) = AdapterCertificationAdmission.evaluate(candidate, expected, setOf("source.checkout"), catalog, runs, resolve)

        fun expect(code: String, candidate: AdapterCertificationBundle = bundle,
            runs: List<CertificationExecutionObservation> = observations) {
            val report = evaluate(candidate, runs)
            assertFalse(report.valid)
            assertTrue(report.findings.any { it.code == code }, "$code absent: ${report.findings}")
            assertTrue(report.admittedScenarioIds.isEmpty())
        }
    }

    @Test fun separateSuccessfulBaselineAndKilledMutantAdmitOnlyTheirScenario() {
        val f = Fixture()
        val report = f.evaluate()
        assertTrue(report.valid, report.findings.toString())
        assertEquals(listOf("condition-true"), report.admittedScenarioIds)
        assertEquals(f.bytes.size, f.resolutions)
    }

    @Test fun completeUncoveredInventoryAdmitsNoExecutableScenario() {
        val f = Fixture()
        val report = f.evaluate(f.bundle.copy(coverage = f.coverage.map { it.copy(scenarioIds = emptyList()) }, scenarios = emptyList()), emptyList())
        assertTrue(report.valid, report.findings.toString())
        assertTrue(report.admittedScenarioIds.isEmpty())
        assertEquals(0, f.resolutions)
    }

    @Test fun wrongTargetVersionOrImplementationDigestCannotReuseEvidence() {
        val f = Fixture()
        listOf(f.adapter.copy(target = "other"), f.adapter.copy(adapterId = "other"),
            f.adapter.copy(version = "2.0"), f.adapter.copy(implementationSha256 = "b".repeat(64))).forEach { changed ->
            f.expect("ADAPTER_MISMATCH", f.bundle.copy(adapter = changed))
            f.expect("ADAPTER_MISMATCH", runs = f.observations.map { it.copy(adapter = changed) })
            assertFalse(f.evaluate(expected = changed).valid)
        }
        assertFalse(f.evaluate(catalog = TargetNativeProjectionCatalog.empty("other")).valid)
        assertEquals(0, f.resolutions)
    }

    @Test fun blankIdentityAndMalformedImplementationDigestAreRejected() {
        val f = Fixture()
        listOf(f.adapter.copy(target = " "), f.adapter.copy(adapterId = ""), f.adapter.copy(version = ""),
            f.adapter.copy(implementationSha256 = "A".repeat(64)), f.adapter.copy(implementationSha256 = "bad")).forEach {
            f.expect("INVALID_ADAPTER_IDENTITY", f.bundle.copy(adapter = it))
        }
    }

    @Test fun coverageCannotDropInventOrDuplicateSemanticStructuralOrLeafRows() {
        val f = Fixture()
        f.coverage.forEach { row ->
            f.expect("COVERAGE_INVENTORY_MISMATCH", f.bundle.copy(coverage = f.coverage - row))
            f.expect("DUPLICATE_IDENTITY", f.bundle.copy(coverage = f.coverage + row))
        }
        listOf(CertificationSubject.Semantic("invented"), CertificationSubject.Leaf("test.leaf", "invented")).forEach {
            f.expect("COVERAGE_INVENTORY_MISMATCH", f.bundle.copy(coverage = f.coverage + CertificationCoverage(it, emptyList(), "Unproven.")))
        }
    }

    @Test fun implementedLeavesCannotSubstituteForMissingParentStructure() {
        val f = Fixture()
        TargetStructuralProjectionKind.entries.filter { it != TargetStructuralProjectionKind.CONDITION }.forEach { kind ->
            f.expect("STRUCTURE_NOT_IMPLEMENTED", f.bundle.copy(coverage = f.coverage.map {
                if (it.subject == CertificationSubject.Structural(kind)) it.copy(scenarioIds = listOf(f.scenario.id)) else it
            }))
        }
    }

    @Test fun unknownAndOrphanScenariosCannotManufactureCoverage() {
        val f = Fixture()
        f.expect("SCENARIO_COVERAGE_MISMATCH", f.bundle.copy(coverage = f.coverage.map { it.copy(scenarioIds = listOf("invented")) }))
        f.expect("SCENARIO_COVERAGE_MISMATCH", f.bundle.copy(coverage = f.coverage.map { it.copy(scenarioIds = emptyList()) }))
        f.expect("DUPLICATE_IDENTITY", f.bundle.copy(scenarios = listOf(f.scenario, f.scenario)))
        f.expect("DUPLICATE_IDENTITY", f.bundle.copy(coverage = f.coverage.map { it.copy(scenarioIds = it.scenarioIds + it.scenarioIds) }))
    }

    @Test fun omittedAndDuplicateRunIdentitiesFailBeforeEvidenceResolution() {
        val f = Fixture()
        f.observations.forEach { run ->
            f.expect("RUN_INVENTORY_MISMATCH", runs = f.observations - run)
            f.expect("DUPLICATE_IDENTITY", runs = f.observations + run)
        }
        f.expect("RUN_INVENTORY_MISMATCH", runs = f.observations + f.observations.first().copy(scenarioId = "unknown", runId = "extra"))
        f.expect("DUPLICATE_IDENTITY", runs = f.observations.map { it.copy(runId = "same-run") })
        f.expect("INVALID_RUN_ID", runs = f.observations.map { it.copy(runId = " ") })
        assertEquals(0, f.resolutions)
    }

    @Test fun staleGraphFixtureAndRenderedArtifactAreRejected() {
        val f = Fixture()
        f.observations.indices.forEach { index ->
            val original = f.observations[index]
            listOf(original.copy(canonicalGraphSha256 = "b".repeat(64)), original.copy(fixtureSha256 = "c".repeat(64))).forEach { changed ->
                f.expect("SCENARIO_INPUT_MISMATCH", runs = f.observations.mapIndexed { i, run -> if (i == index) changed else run })
            }
            f.expect("ARTIFACT_MISMATCH", runs = f.observations.mapIndexed { i, run -> if (i == index)
                run.copy(artifact = f.evidence("unrelated-$index", "different artifact")) else run })
        }
    }

    @Test fun runtimeVersionsMustMatchForPositiveAndNegativeRuns() {
        val f = Fixture()
        listOf(emptyList(), listOf(CertificationRuntimePrerequisite("test-runner", "2.0")), f.runtime + f.runtime,
            listOf(CertificationRuntimePrerequisite("other-runner", "1.0"))).forEach { runtime ->
            f.observations.indices.forEach { index -> f.expect("RUNTIME_MISMATCH", runs = f.observations.mapIndexed { i, run ->
                if (i == index) run.copy(runtimePrerequisites = runtime) else run }) }
        }
        f.expect("INVALID_RUNTIME_PREREQUISITES", f.bundle.copy(scenarios = listOf(f.scenario.copy(runtimePrerequisites = emptyList()))))
    }

    @Test fun infrastructureFailureAndTimeoutNeverCountAsKilledMutants() {
        val f = Fixture()
        listOf(CertificationRunOutcome.INFRASTRUCTURE_FAILURE, CertificationRunOutcome.TIMEOUT).forEach { outcome ->
            f.observations.indices.forEach { index -> f.expect("RUN_NOT_COMPLETED", runs = f.observations.mapIndexed { i, run ->
                if (i == index) run.copy(outcome = outcome) else run }) }
        }
    }

    @Test fun baselineFailureAndUnrelatedMutantDifferenceBothFailAdmission() {
        val f = Fixture()
        f.observations.indices.forEach { index -> f.expect("OBSERVATION_MISMATCH", runs = f.observations.mapIndexed { i, run ->
            if (i == index) run.copy(observation = f.evidence("wrong-$index", "unrelated behavior")) else run }) }
        f.expect("OBSERVATION_MISMATCH", runs = listOf(f.observations.first(), f.observations.last().copy(observation = f.observations.first().observation)))
    }

    @Test fun absentIneffectiveAndDuplicateMutantsAreRejected() {
        val f = Fixture()
        val mutant = f.scenario.negativeMutants.single()
        f.expect("MISSING_NEGATIVE_MUTANT", f.bundle.copy(scenarios = listOf(f.scenario.copy(negativeMutants = emptyList()))), listOf(f.observations.first()))
        f.expect("UNCHANGED_MUTANT_ARTIFACT", f.bundle.copy(scenarios = listOf(f.scenario.copy(negativeMutants = listOf(mutant.copy(artifact = f.scenario.artifact))))))
        f.expect("SURVIVING_MUTANT", f.bundle.copy(scenarios = listOf(f.scenario.copy(negativeMutants = listOf(mutant.copy(expectedObservation = f.scenario.expectedObservation))))))
        f.expect("DUPLICATE_IDENTITY", f.bundle.copy(scenarios = listOf(f.scenario.copy(negativeMutants = listOf(mutant, mutant)))))
    }

    @Test fun expectedObservationCannotMasqueradeAsARunnerRecord() {
        val f = Fixture()
        f.expect("EXPECTED_OBSERVATION_REUSED", runs = listOf(f.observations.first().copy(observation = f.scenario.expectedObservation), f.observations.last()))
        f.expect("EXPECTED_OBSERVATION_REUSED", runs = listOf(f.observations.first(), f.observations.last().copy(observation = f.scenario.negativeMutants.single().expectedObservation)))
    }

    @Test fun everyReferencedByteSequenceMustExistAndMatchItsDigest() {
        val f = Fixture()
        f.bytes.keys.toList().forEach { id ->
            val original = f.bytes.getValue(id)
            f.bytes.remove(id)
            f.expect("EVIDENCE_UNAVAILABLE")
            f.bytes[id] = original.map { (it.toInt() xor 1).toByte() }.toByteArray()
            f.expect("EVIDENCE_BYTES_MISMATCH")
            f.bytes[id] = original + 0.toByte()
            f.expect("EVIDENCE_BYTES_MISMATCH")
            f.bytes[id] = original
        }
    }

    @Test fun resolverFailureIsReportedWithoutLeakingExceptionText() {
        val f = Fixture()
        val report = f.evaluate(resolve = CertificationEvidenceResolver { error("sensitive runner configuration") })
        assertFalse(report.valid)
        assertTrue(report.findings.all { it.code == "EVIDENCE_RESOLUTION_FAILED" })
        assertFalse(report.toString().contains("sensitive"))
    }

    @Test fun conflictingReferenceIdsAndMalformedDigestsNeverReachResolver() {
        val f = Fixture()
        f.expect("EVIDENCE_IDENTITY_CONFLICT", f.bundle.copy(scenarios = listOf(f.scenario.copy(
            fixture = f.scenario.fixture.copy(id = f.scenario.canonicalGraph.id)))))
        listOf(f.scenario.expectedObservation.copy(sha256 = "bad"), f.scenario.expectedObservation.copy(id = ""),
            f.scenario.expectedObservation.copy(sizeBytes = 0), f.scenario.expectedObservation.copy(sizeBytes = -1),
            f.scenario.expectedObservation.copy(sizeBytes = InputLimits.MAX_ARTIFACT_BYTES + 1)).forEach { ref ->
            f.expect("INVALID_EVIDENCE_REFERENCE", f.bundle.copy(scenarios = listOf(f.scenario.copy(expectedObservation = ref))))
        }
        assertEquals(0, f.resolutions)
    }

    @Test fun aggregateEvidenceBudgetIsCheckedBeforeAnyIo() {
        val f = Fixture()
        fun large(ref: CertificationEvidenceReference) = ref.copy(sizeBytes = InputLimits.MAX_ARTIFACT_BYTES)
        val scenario = f.scenario.copy(canonicalGraph = large(f.scenario.canonicalGraph), fixture = large(f.scenario.fixture),
            artifact = large(f.scenario.artifact), expectedObservation = large(f.scenario.expectedObservation),
            negativeMutants = f.scenario.negativeMutants.map { it.copy(artifact = large(it.artifact), expectedObservation = large(it.expectedObservation)) })
        f.expect("EVIDENCE_BUDGET_EXCEEDED", f.bundle.copy(scenarios = listOf(scenario)),
            f.observations.map { it.copy(artifact = large(it.artifact), observation = large(it.observation)) })
        assertEquals(0, f.resolutions)
    }

    @Test fun missingLimitationsCannotPublishAnUnqualifiedClaim() {
        val f = Fixture()
        f.expect("MISSING_LIMITATIONS", f.bundle.copy(limitations = emptyList()))
        f.expect("MISSING_LIMITATIONS", f.bundle.copy(coverage = f.coverage.map { it.copy(limitation = " ") }))
    }

    @Test fun orderingDoesNotChangeAdmissionAndReportsCannotBeMutated() {
        val f = Fixture()
        val report = f.evaluate(f.bundle.copy(coverage = f.coverage.reversed()), f.observations.reversed())
        assertEquals(f.evaluate(), report)
        assertFailsWith<UnsupportedOperationException> { (report.admittedScenarioIds as MutableList<String>).add("invented") }
        assertFailsWith<UnsupportedOperationException> { (report.findings as MutableList<CertificationAdmissionFinding>).add(CertificationAdmissionFinding("fake", "fake")) }
    }

    companion object {
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
