package org.flowlang.conformance

import java.io.File
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.modules.ModuleRegistry

class RealWorldCorpusConformanceChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, TargetCapability>,
    projections: TargetProjectionRegistry
) {
    private val runner = RealWorldCorpusRunner(rootDir, registry, targets, projections)

    fun checks(): List<ConformanceCheck> {
        val corpusResult = runCatching(runner::load)
        val corpus = corpusResult.getOrNull()
        if (corpus == null) {
            return listOf(ConformanceCheck(RealWorldCorpusRunner.INTEGRITY_CHECK, false, corpusResult.exceptionOrNull()?.message))
        }

        val compositionErrors = sourceCompositionErrors(corpus)
        val checks = mutableListOf(
            ConformanceCheck(
                name = RealWorldCorpusRunner.INTEGRITY_CHECK,
                passed = compositionErrors.isEmpty(),
                message = compositionErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            )
        )
        corpus.cases.forEach { case ->
            val result = runCatching { runner.evaluate(case) }
            val evaluation = result.getOrNull()
            checks += ConformanceCheck(
                name = "real-world-corpus.case.${case.definition.id.lowercase()}",
                passed = evaluation?.accepted == true,
                message = result.exceptionOrNull()?.message ?: evaluation?.mismatches?.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            )
        }
        val mutationResults = corpus.cases.flatMap { case ->
            runCatching { runner.evaluateMutations(case) }.getOrElse { error ->
                listOf(RealWorldEvaluationResult(case.definition.id, RealWorldResult.INVALID_SOURCE_PIPELINE, false, emptyList(), emptyList(), listOf(error.message ?: error.javaClass.simpleName)))
            }
        }
        checks += ConformanceCheck(
            name = RealWorldCorpusRunner.MUTATION_CHECK,
            passed = mutationResults.isNotEmpty() && mutationResults.all { it.accepted },
            message = mutationResults.flatMap { result -> result.mismatches.map { "${result.caseId}: $it" } }
                .takeIf { it.isNotEmpty() }?.joinToString(" | ")
        )
        return checks
    }

    private fun sourceCompositionErrors(corpus: LoadedRealWorldCorpus): List<String> {
        val errors = mutableListOf<String>()
        val counts = corpus.manifest.counts
        val unclassifiedSources = corpus.sources.sources.filter { it.classifiedKind() == null }
        val unclassifiedCases = corpus.cases.filter { it.source.classifiedKind() == null }
        val sourceKinds = corpus.sources.sources.mapNotNull { it.classifiedKind() }.groupingBy { it }.eachCount()
        val caseKinds = corpus.cases.mapNotNull { it.source.classifiedKind() }.groupingBy { it }.eachCount()

        if (unclassifiedSources.isNotEmpty()) {
            errors += "Unknown source kinds: ${unclassifiedSources.map { it.sourceKind }.distinct().sorted()}."
        }
        if (unclassifiedCases.isNotEmpty()) {
            errors += "Executable cases use unknown primary source kinds: ${unclassifiedCases.map { it.source.sourceKind }.distinct().sorted()}."
        }

        compareCount(errors, "productionSources", counts.productionSources, sourceKinds[RealWorldSourceKind.PRODUCTION_WORKFLOW] ?: 0)
        compareCount(errors, "exampleSources", counts.exampleSources, sourceKinds[RealWorldSourceKind.OFFICIAL_EXAMPLE] ?: 0)
        compareCount(errors, "semanticReferenceSources", counts.semanticReferenceSources, sourceKinds[RealWorldSourceKind.OFFICIAL_SEMANTIC_REFERENCE] ?: 0)
        compareCount(errors, "productionCases", counts.productionCases, caseKinds[RealWorldSourceKind.PRODUCTION_WORKFLOW] ?: 0)
        compareCount(errors, "exampleCases", counts.exampleCases, caseKinds[RealWorldSourceKind.OFFICIAL_EXAMPLE] ?: 0)
        compareCount(errors, "semanticReferenceCases", counts.semanticReferenceCases, caseKinds[RealWorldSourceKind.OFFICIAL_SEMANTIC_REFERENCE] ?: 0)

        if (counts.productionSources + counts.exampleSources + counts.semanticReferenceSources != counts.sources) {
            errors += "Declared source-class counts do not sum to sources=${counts.sources}."
        }
        if (counts.productionCases + counts.exampleCases + counts.semanticReferenceCases != counts.executableCases) {
            errors += "Declared executable-case source-class counts do not sum to executableCases=${counts.executableCases}."
        }
        return errors
    }

    private fun compareCount(errors: MutableList<String>, label: String, declared: Int, actual: Int) {
        if (declared != actual) errors += "Manifest $label=$declared, actual=$actual."
    }
}
