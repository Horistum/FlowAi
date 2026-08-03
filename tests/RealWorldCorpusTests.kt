package org.flowlang.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.conformance.RealWorldCorpusConformanceChecks
import org.flowlang.conformance.RealWorldCorpusRunner
import org.flowlang.conformance.RealWorldDomain
import org.flowlang.conformance.RealWorldLifecycle
import org.flowlang.conformance.RealWorldResult
import org.flowlang.conformance.RealWorldSourceKind
import org.flowlang.modules.ModuleRegistry
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
        assertEquals(8, corpus.cases.size)
        assertEquals(10, corpus.cases.sumOf { it.mutations.size })
        assertTrue(corpus.cases.all { it.definition.lifecycle >= RealWorldLifecycle.MUTATION_VALIDATED })
        assertTrue(corpus.cases.all { it.evidence.status == RealWorldLifecycle.ACCEPTED })
        assertEquals(
            setOf("C02", "C06", "A04", "A06", "A11", "N01", "N05", "N08"),
            corpus.cases.map { it.definition.id }.toSet()
        )
    }

    @Test
    fun boundedDomainsAreClosedAndEveryDomainOwnsExecutableEvidence() {
        val corpus = runner.load()
        assertEquals(
            RealWorldDomain.values().map { it.documentValue },
            corpus.manifest.scope.domains
        )
        assertTrue(corpus.cases.all { it.definition.classifiedDomain() != null })
        val casesByDomain = corpus.cases.groupBy { it.definition.classifiedDomain() }
        RealWorldDomain.values().forEach { domain ->
            assertTrue(casesByDomain[domain].orEmpty().isNotEmpty(), "Missing accepted case for ${domain.documentValue}")
        }
    }

    @Test
    fun sourceCompositionIsExplicitAndMatchesExecutableCaseProvenance() {
        val corpus = runner.load()
        val counts = corpus.manifest.counts

        assertEquals(3, counts.productionSources)
        assertEquals(9, counts.exampleSources)
        assertEquals(4, counts.semanticReferenceSources)
        assertEquals(1, counts.productionCases)
        assertEquals(7, counts.exampleCases)
        assertEquals(0, counts.semanticReferenceCases)
        assertEquals(counts.sources, counts.productionSources + counts.exampleSources + counts.semanticReferenceSources)
        assertEquals(counts.executableCases, counts.productionCases + counts.exampleCases + counts.semanticReferenceCases)
        assertEquals(1, corpus.cases.count { it.source.classifiedKind() == RealWorldSourceKind.PRODUCTION_WORKFLOW })
        assertEquals(7, corpus.cases.count { it.source.classifiedKind() == RealWorldSourceKind.OFFICIAL_EXAMPLE })
        assertEquals(0, corpus.cases.count { it.source.classifiedKind() == RealWorldSourceKind.OFFICIAL_SEMANTIC_REFERENCE })
    }

    @Test
    fun everyRealWorldCaseRunsThroughTheProductionIntentPipeline() {
        val results = runner.evaluateAll()
        assertTrue(
            results.all { it.accepted },
            results.filterNot { it.accepted }.joinToString("\n") { result ->
                "${result.caseId}: ${result.mismatches.joinToString(" | ")}"
            }
        )
        assertEquals(
            mapOf(
                "C02" to RealWorldResult.SEMANTIC_ONLY,
                "C06" to RealWorldResult.SUPPORTED_WITH_BINDING,
                "A04" to RealWorldResult.SUPPORTED_WITH_BINDING,
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
    fun dataTransformationCasePreservesNamedValueRelations() {
        val case = runner.load().cases.single { it.definition.id == "A04" }
        val result = runner.evaluate(case)

        assertTrue(result.accepted, result.mismatches.joinToString(" | "))
        assertEquals(RealWorldResult.SUPPORTED_WITH_BINDING, result.outcome)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun outputDrivenMatrixRequiresExplicitPlanRepresentation() {
        val case = runner.load().cases.single { it.definition.id == "A11" }
        val result = runner.evaluate(case)

        assertTrue(result.accepted, result.mismatches.joinToString(" | "))
        assertEquals(RealWorldResult.UNSUPPORTED_DYNAMIC_CONSTRUCTION, result.outcome)
        assertEquals(listOf(RealWorldCorpusRunner.DYNAMIC_MATRIX_NOT_REPRESENTED), result.diagnostics)
    }

    @Test
    fun runtimeGeneratedInfrastructureRequiresExplicitPlanRepresentation() {
        val case = runner.load().cases.single { it.definition.id == "N05" }
        val result = runner.evaluate(case)

        assertTrue(result.accepted, result.mismatches.joinToString(" | "))
        assertEquals(RealWorldResult.UNSUPPORTED_DYNAMIC_CONSTRUCTION, result.outcome)
        assertEquals(listOf(RealWorldCorpusRunner.RUNTIME_PLAN_NOT_REPRESENTED), result.diagnostics)
        assertTrue(result.targetAssessments.all { !it.executable })
    }

    @Test
    fun allNegativeMutationsProduceExactExpectedDiagnostics() {
        val corpus = runner.load()
        val results = corpus.cases.flatMap(runner::evaluateMutations)
        assertEquals(10, results.size)
        assertTrue(
            results.all { it.accepted },
            results.filterNot { it.accepted }.joinToString("\n") { result ->
                "${result.caseId}: ${result.mismatches.joinToString(" | ")}"
            }
        )
        assertTrue(results.all { it.diagnostics.isNotEmpty() })
        RealWorldDomain.values().forEach { domain ->
            val domainCases = corpus.cases.filter { it.definition.classifiedDomain() == domain }
            val domainResults = domainCases.flatMap(runner::evaluateMutations)
            assertTrue(domainResults.any { it.accepted && it.diagnostics.isNotEmpty() })
        }
    }

    @Test
    fun postAdapterConformanceChecksExecuteTheCorpusAndMutations() {
        val checks = RealWorldCorpusConformanceChecks(rootDir, registry, targets, projections).checks()
        assertTrue(checks.any { it.name == RealWorldCorpusConformanceChecks.INVENTORY_CHECK && it.passed })
        assertTrue(checks.any { it.name == RealWorldCorpusConformanceChecks.LIFECYCLE_CHECK && it.passed })
        assertTrue(checks.any { it.name == RealWorldCorpusRunner.INTEGRITY_CHECK && it.passed })
        assertTrue(checks.any { it.name == RealWorldCorpusRunner.DOMAIN_COVERAGE_CHECK && it.passed })
        assertTrue(checks.any { it.name == RealWorldCorpusRunner.MUTATION_CHECK && it.passed })
        assertTrue(checks.any { it.name == RealWorldCorpusRunner.DOMAIN_MUTATION_CHECK && it.passed })
        assertEquals(8, checks.count { it.name.startsWith("real-world-corpus.case.") })
        assertTrue(checks.all { it.passed }, checks.filterNot { it.passed }.joinToString { "${it.name}: ${it.message}" })
    }
}
