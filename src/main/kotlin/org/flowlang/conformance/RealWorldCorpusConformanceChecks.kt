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
        val checks = mutableListOf(ConformanceCheck(RealWorldCorpusRunner.INTEGRITY_CHECK, true))
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
}
