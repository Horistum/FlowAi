package org.flowlang.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.conformance.RealWorldCorpusConformanceChecks
import org.flowlang.conformance.RealWorldCorpusRunner
import org.flowlang.conformance.RealWorldDomain
import org.flowlang.conformance.RealWorldLifecycle
import org.flowlang.conformance.RealWorldPolarity
import org.flowlang.conformance.RealWorldPolarityAuthority
import org.flowlang.conformance.RealWorldResult
import org.flowlang.conformance.RealWorldSourceKind
import org.flowlang.modules.ModuleRegistry
import org.flowlang.roadmap.RoadmapStreamTransitionAuthority
import org.flowlang.targets.builtin.BuiltInTargetProjections

class RealWorldCorpusTests {
    private val rootDir = File(".")
    private val registry = ModuleRegistry.fromDirectory(File(rootDir, "modules"))
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets"))
    private val projections = BuiltInTargetProjections.registry
    private val runner = RealWorldCorpusRunner(rootDir, registry, targets, projections)

    @Test
    fun executableCasePackagesAreCompleteAndLifecycleHonest() {
        val corpus = runner.load()
        assertEquals(9, corpus.cases.size)
        assertEquals(11, corpus.cases.sumOf { it.mutations.size })
        assertTrue(corpus.cases.all { it.definition.lifecycle >= RealWorldLifecycle.MUTATION_VALIDATED })
        assertTrue(corpus.cases.all { it.evidence.status == RealWorldLifecycle.ACCEPTED })
    }

    @Test
    fun boundedDomainsCloseOnlyC01ScopeAndOwnPositiveEvidence() {
        val corpus = runner.load()
        assertEquals(RealWorldDomain.values().map { it.documentValue }, corpus.manifest.scope.domains)
        assertTrue(RealWorldCorpusConformanceChecks.DOMAIN_SCOPE_BOUNDARY in corpus.manifest.invariants)
        val results = corpus.cases.associateWith(runner::evaluate)
        RealWorldDomain.values().forEach { domain ->
            val positive = corpus.cases
                .filter { it.definition.classifiedDomain() == domain }
                .map { results.getValue(it) }
                .filter { RealWorldPolarityAuthority.classify(it) == RealWorldPolarity.REPRESENTABLE }
            assertTrue(positive.isNotEmpty(), "Missing positive baseline for ${domain.documentValue}")
        }
    }

    @Test
    fun sourceCompositionMatchesDeclaredProvenance() {
        val corpus = runner.load()
        val counts = corpus.manifest.counts
        assertEquals(3, counts.productionSources)
        assertEquals(10, counts.exampleSources)
        assertEquals(4, counts.semanticReferenceSources)
        assertEquals(1, counts.productionCases)
        assertEquals(8, counts.exampleCases)
        assertEquals(0, counts.semanticReferenceCases)
        assertEquals(1, corpus.cases.count { it.source.classifiedKind() == RealWorldSourceKind.PRODUCTION_WORKFLOW })
        assertEquals(8, corpus.cases.count { it.source.classifiedKind() == RealWorldSourceKind.OFFICIAL_EXAMPLE })
    }

    @Test
    fun everyCaseRunsThroughTheProductionIntentPipeline() {
        val results = runner.evaluateAll()
        assertTrue(results.all { it.accepted }, results.filterNot { it.accepted }.joinToString("\n") { "${it.caseId}: ${it.mismatches}" })
        assertEquals(
            mapOf(
                "C02" to RealWorldResult.SEMANTIC_ONLY,
                "C06" to RealWorldResult.SUPPORTED_WITH_BINDING,
                "A04" to RealWorldResult.SUPPORTED_WITH_BINDING,
                "P13" to RealWorldResult.SUPPORTED_WITH_BINDING,
                "A06" to RealWorldResult.SUPPORTED_WITH_BINDING,
                "A11" to RealWorldResult.UNSUPPORTED_DYNAMIC_CONSTRUCTION,
                "N01" to RealWorldResult.INVALID_SOURCE_PIPELINE,
                "N05" to RealWorldResult.UNSUPPORTED_DYNAMIC_CONSTRUCTION,
                "N08" to RealWorldResult.SEMANTIC_ONLY
            ),
            results.associate { it.caseId to it.outcome }
        )
    }

    @Test
    fun dynamicConstructionHasAnIndependentExactBoundary() {
        val results = runner.load().cases.associate { it.definition.id to runner.evaluate(it) }
        assertEquals(listOf(RealWorldCorpusRunner.DYNAMIC_MATRIX_NOT_REPRESENTED), results.getValue("A11").diagnostics)
        assertEquals(listOf(RealWorldCorpusRunner.RUNTIME_PLAN_NOT_REPRESENTED), results.getValue("N05").diagnostics)
        assertTrue(results.getValue("N05").targetAssessments.all { !it.executable })
    }

    @Test
    fun mutationsFlipRepresentableBaselinesInsteadOfRemainingNegative() {
        val corpus = runner.load()
        val baselines = corpus.cases.associateWith(runner::evaluate)
        val flipsByDomain = mutableMapOf<RealWorldDomain, MutableList<String>>()
        corpus.cases.forEach { case ->
            val baseline = baselines.getValue(case)
            if (RealWorldPolarityAuthority.classify(baseline) != RealWorldPolarity.REPRESENTABLE) return@forEach
            runner.evaluateMutations(case).forEach { mutation ->
                val comparison = RealWorldPolarityAuthority.compare(baseline, mutation)
                assertTrue(comparison.flipped, "${mutation.caseId}: ${comparison.reason}")
                flipsByDomain.getOrPut(requireNotNull(case.definition.classifiedDomain())) { mutableListOf() } += mutation.caseId
            }
        }
        RealWorldDomain.values().forEach { domain ->
            assertTrue(flipsByDomain[domain].orEmpty().isNotEmpty(), "Missing polarity flip for ${domain.documentValue}")
        }
        val negativeCase = corpus.cases.single { it.definition.id == "N05" }
        val negativeMutation = runner.evaluateMutations(negativeCase).single()
        assertFalse(RealWorldPolarityAuthority.compare(baselines.getValue(negativeCase), negativeMutation).flipped)
    }

    @Test
    fun allMutationsPreserveExactExpectedDiagnostics() {
        val results = runner.load().cases.flatMap(runner::evaluateMutations)
        assertEquals(11, results.size)
        assertTrue(results.all { it.accepted }, results.filterNot { it.accepted }.joinToString("\n") { "${it.caseId}: ${it.mismatches}" })
        assertTrue(results.all { it.diagnostics.isNotEmpty() })
    }

    @Test
    fun postAdapterConformanceRunsIndependentGates() {
        val checks = RealWorldCorpusConformanceChecks(rootDir, registry, targets, projections).checks()
        val required = setOf(
            RealWorldCorpusConformanceChecks.INVENTORY_CHECK,
            RealWorldCorpusConformanceChecks.LIFECYCLE_CHECK,
            RoadmapStreamTransitionAuthority.CHECK_ID,
            RealWorldCorpusRunner.INTEGRITY_CHECK,
            RealWorldCorpusRunner.DOMAIN_COVERAGE_CHECK,
            RealWorldCorpusConformanceChecks.DOMAIN_REPRESENTABILITY_CHECK,
            RealWorldCorpusConformanceChecks.DYNAMIC_BOUNDARY_CHECK,
            RealWorldCorpusRunner.MUTATION_CHECK,
            RealWorldCorpusRunner.DOMAIN_MUTATION_CHECK
        )
        assertTrue(required.all { name -> checks.any { it.name == name && it.passed } })
        assertEquals(9, checks.count { it.name.startsWith("real-world-corpus.case.") })
        assertTrue(checks.all { it.passed }, checks.filterNot { it.passed }.joinToString { "${it.name}: ${it.message}" })
    }
}
