package org.flowlang.conformance

import java.io.File
import java.security.MessageDigest
import kotlin.test.*
import org.flowlang.adapters.certification.*
import org.flowlang.adapters.testing.AdapterRuntimeTestFixtures
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.distribution.reference.ReferenceAdapterEvidence
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry

class BoundAdapterCertificationTests {
    companion object {
        private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
        private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
        private val source by lazy { File("examples/intent/checkout-build-image.intent.yaml").readBytes() }
        private val compilation by lazy { IntentYamlFrontend(FrontendCompilerComposition.compiler(modules))
            .compileText(source.toString(Charsets.UTF_8), "checkout-build-image.intent.yaml").requireAccepted() }
        private fun selection(target: String) = TargetSelectionAuthority.requireSelected(
            TargetSelectionAuthority.fromCliOption(target, targets), "certification test")
        private fun request(target: String) = TargetMaterializationRequest.fromCompilation(compilation, selection(target))
        private fun capture(target: String = "jenkins", id: String = "checkout-build",
            bytes: ByteArray = source, input: TargetMaterializationRequest = request(target),
            providerTarget: String = target) = BoundCertificationScenario.capture(id, bytes, input,
            ReferenceTargetProjections.pipeline(targets, modules = modules), ReferenceAdapterEvidence.rendering(),
            ReferenceTargetProjections.nativeCatalogs.getValue(providerTarget))
        private val bound by lazy { capture() }
    }

    private class Fixture(val inputs: List<BoundCertificationScenario> = listOf(bound)) {
        val provider = ReferenceTargetProjections.nativeCatalogs.getValue("jenkins")
        val identity = CertificationAdapterIdentity("jenkins", "binding-test", "test", "a".repeat(64))
        val bytes = linkedMapOf<String, ByteArray>()
        fun evidence(id: String, value: String): CertificationEvidenceReference {
            val content = value.toByteArray(); bytes[id] = content
            val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) }
            return CertificationEvidenceReference(id, sha, content.size)
        }
        val runtime = listOf(CertificationRuntimePrerequisite("synthetic-contract-test", "1"))
        val scenarios = inputs.map { input -> input.scenario(evidence("${input.id}:expected", "success"), runtime,
            listOf(CertificationNegativeMutant("removed-action", evidence("${input.id}:mutant", "changed artifact"),
                evidence("${input.id}:negative-expected", "missing action")))) }
        val subjects = inputs.flatMap { it.subjects }.toSet() +
            TargetStructuralProjectionKind.entries.map { CertificationSubject.Structural(it) } +
            provider.definitions.map { CertificationSubject.Leaf(it.kind, it.reference) }
        val bundle = AdapterCertificationBundle(identity, subjects.map { subject -> CertificationCoverage(subject,
            inputs.filter { subject in it.subjects }.map { it.id }, "Occurrence only; synthetic test observations.") },
            scenarios, listOf("No runtime certification."))
        val runs = scenarios.flatMap { s -> listOf(
            CertificationExecutionObservation("${s.id}:positive-run", identity, s.id, null, s.canonicalGraph.sha256,
                s.fixture.sha256, s.artifact, evidence("${s.id}:observed", "success"), runtime, CertificationRunOutcome.COMPLETED),
            CertificationExecutionObservation("${s.id}:negative-run", identity, s.id, "removed-action", s.canonicalGraph.sha256,
                s.fixture.sha256, s.negativeMutants.single().artifact, evidence("${s.id}:negative-observed", "missing action"),
                runtime, CertificationRunOutcome.COMPLETED)) }
        var externalResolutions = 0
        fun evaluate(candidate: AdapterCertificationBundle = bundle, captures: List<BoundCertificationScenario> = inputs,
            observations: List<CertificationExecutionObservation> = runs) = BoundAdapterCertificationAdmission.evaluate(
                candidate, identity, captures, provider, observations, CertificationEvidenceResolver {
                    externalResolutions++; bytes[it.id]
                })
        fun rejected(candidate: AdapterCertificationBundle, code: String = "BOUND_COVERAGE_MISMATCH") {
            val report = evaluate(candidate)
            assertFalse(report.valid)
            assertTrue(report.admittedScenarioIds.isEmpty())
            assertTrue(report.findings.any { it.code == code }, report.findings.toString())
            assertEquals(0, externalResolutions)
        }
    }

    @Test fun twoRealAdaptersBindOneCanonicalCompilationToDifferentArtifacts() {
        val github = capture("github-actions")
        assertEquals(bound.graphDigest, github.graphDigest)
        assertEquals(bound.canonicalGraph.sha256, github.canonicalGraph.sha256)
        assertEquals(bound.fixture.sha256, github.fixture.sha256)
        assertNotEquals(bound.artifact.sha256, github.artifact.sha256)
        assertTrue(bound.subjects.any { it is CertificationSubject.Semantic })
        assertTrue(bound.subjects.any { it is CertificationSubject.Leaf })
        assertTrue(bound.subjects.none { it is CertificationSubject.Structural })
        assertEquals(compilation.authorization.graphDigest.value, bound.graphDigest)
    }

    @Test fun changedSourceAndWrongProviderCannotBeCaptured() {
        assertFailsWith<IllegalArgumentException> { capture(bytes = "different source".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { capture(providerTarget = "github-actions") }
        assertFailsWith<IllegalArgumentException> { capture(id = " ") }
    }

    @Test fun compatibilityPlanCannotStandInForAnAuthorizedSource() {
        val legacy = AdapterRuntimeTestFixtures.executionRequest(compilation.authorization.executionPlan, selection("jenkins"))
        assertFailsWith<IllegalArgumentException> { capture(input = legacy) }
    }

    @Test fun capturedBytesAreDefensiveCopies() {
        val bytes = source.copyOf()
        val captured = capture(bytes = bytes)
        bytes.fill(0)
        assertContentEquals(source, captured.resolve(captured.fixture))
        val graph = requireNotNull(captured.resolve(captured.canonicalGraph)); val original = graph.copyOf(); graph.fill(0)
        assertContentEquals(original, captured.resolve(captured.canonicalGraph))
        assertNull(captured.resolve(captured.artifact.copy(sha256 = "f".repeat(64))))
        assertFailsWith<UnsupportedOperationException> { (captured.subjects as MutableSet).clear() }
    }

    @Test fun boundAdmissionUsesCapturedBytesAndIndependentObservationReferences() {
        val f = Fixture()
        // External storage has no graph/source/artifact: admission must resolve the captured bytes.
        val report = f.evaluate()
        assertTrue(report.valid, report.findings.toString())
        assertEquals(listOf(bound.id), report.admittedScenarioIds)
        assertTrue(f.externalResolutions > 0)
    }

    @Test fun eachCapturedInputRejectsSubstitutionBeforeExternalIo() {
        for (field in listOf("graph", "source", "artifact")) {
            val f = Fixture(); val s = f.scenarios.single(); val ref = f.evidence("substituted", "forged")
            val changed = when (field) { "graph" -> s.copy(canonicalGraph = ref); "source" -> s.copy(fixture = ref); else -> s.copy(artifact = ref) }
            f.rejected(f.bundle.copy(scenarios = listOf(changed)), "BOUND_SCENARIO_INPUT_MISMATCH")
        }
    }

    @Test fun inventedStructureOrUnrelatedLeafCannotBorrowScenarioEvidence() {
        val f = Fixture()
        for (row in f.bundle.coverage.filter { it.scenarioIds.isEmpty() }) {
            f.rejected(f.bundle.copy(coverage = f.bundle.coverage.map { if (it == row) it.copy(scenarioIds = listOf(bound.id)) else it }))
        }
    }

    @Test fun everyActualSubjectMustRetainItsScenarioAssociation() {
        val f = Fixture()
        for (row in f.bundle.coverage.filter { it.scenarioIds.isNotEmpty() }) {
            f.rejected(f.bundle.copy(coverage = f.bundle.coverage - row))
            f.rejected(f.bundle.copy(coverage = f.bundle.coverage.map { if (it == row) it.copy(scenarioIds = emptyList()) else it }))
        }
    }

    @Test fun missingDuplicateAndWrongTargetBindingsFailAtomically() {
        val f = Fixture()
        for (inputs in listOf(emptyList(), listOf(bound, bound), listOf(capture("github-actions")))) {
            val report = f.evaluate(captures = inputs)
            assertFalse(report.valid); assertTrue(report.admittedScenarioIds.isEmpty())
        }
        assertEquals(0, f.externalResolutions)
    }

    @Test fun secondScenarioCannotLoseItsOwnCoverageEvenWhenFirstCoversTheSameSubject() {
        val second = capture(id = "second-scenario")
        val f = Fixture(listOf(bound, second))
        val row = f.bundle.coverage.first { it.scenarioIds.size == 2 }
        f.rejected(f.bundle.copy(coverage = f.bundle.coverage.map { if (it == row) it.copy(scenarioIds = listOf(bound.id)) else it }))
    }

    @Test fun bindingDoesNotReplaceRuntimeEvidenceOrMutantPolarityChecks() {
        val f = Fixture()
        val report = f.evaluate(observations = f.runs.map { it.copy(outcome = CertificationRunOutcome.INFRASTRUCTURE_FAILURE) })
        assertFalse(report.valid); assertTrue(report.findings.any { it.code == "RUN_NOT_COMPLETED" })
        assertTrue(report.admittedScenarioIds.isEmpty())
    }
}
