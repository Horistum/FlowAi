package org.flowlang.conformance

import java.io.File
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.modules.ModuleRegistry
import org.flowlang.roadmap.RoadmapStreamTransitionAuthority
import org.flowlang.serialization.FlowYaml

class RealWorldCorpusConformanceChecks(
    private val rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, TargetCapability>,
    projections: TargetProjectionRegistry
) {
    private val runner = RealWorldCorpusRunner(rootDir, registry, targets, projections)

    fun checks(): List<ConformanceCheck> {
        val produced = mutableListOf<ConformanceCheck>()
        val lifecycleResult = runCatching { BoundedDomainCorpusRoadmapLifecycleAuthority(rootDir).analyze() }
        val lifecycle = lifecycleResult.getOrNull()
        produced += ConformanceCheck(
            name = LIFECYCLE_CHECK,
            passed = lifecycle?.status == "PASS",
            message = lifecycleResult.exceptionOrNull()?.message ?: lifecycle?.errors?.takeIf { it.isNotEmpty() }?.joinToString(" | ")
        )

        val transitionResult = runCatching { RoadmapStreamTransitionAuthority(rootDir).analyze() }
        val transition = transitionResult.getOrNull()
        produced += ConformanceCheck(
            name = RoadmapStreamTransitionAuthority.CHECK_ID,
            passed = transition?.status == "PASS",
            message = transitionResult.exceptionOrNull()?.message ?: transition?.errors?.takeIf { it.isNotEmpty() }?.joinToString(" | ")
        )

        val corpusResult = runCatching(runner::load)
        val corpus = corpusResult.getOrNull()
        if (corpus == null) {
            produced += ConformanceCheck(RealWorldCorpusRunner.INTEGRITY_CHECK, false, corpusResult.exceptionOrNull()?.message)
            return withInventoryCheck(produced)
        }

        val compositionErrors = sourceCompositionErrors(corpus)
        produced += ConformanceCheck(
            name = RealWorldCorpusRunner.INTEGRITY_CHECK,
            passed = compositionErrors.isEmpty(),
            message = compositionErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
        )

        val domainErrors = domainCoverageErrors(corpus)
        produced += ConformanceCheck(
            name = RealWorldCorpusRunner.DOMAIN_COVERAGE_CHECK,
            passed = domainErrors.isEmpty(),
            message = domainErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
        )

        val baselineResults = corpus.cases.associateWith(::evaluateSafely)
        val representabilityErrors = domainRepresentabilityErrors(corpus, baselineResults)
        produced += ConformanceCheck(
            name = DOMAIN_REPRESENTABILITY_CHECK,
            passed = representabilityErrors.isEmpty(),
            message = representabilityErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
        )

        val dynamicErrors = dynamicBoundaryErrors(corpus, baselineResults)
        produced += ConformanceCheck(
            name = DYNAMIC_BOUNDARY_CHECK,
            passed = dynamicErrors.isEmpty(),
            message = dynamicErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
        )

        corpus.cases.forEach { case ->
            val evaluation = baselineResults.getValue(case)
            produced += ConformanceCheck(
                name = "real-world-corpus.case.${case.definition.id.lowercase()}",
                passed = evaluation.accepted,
                message = evaluation.mismatches.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            )
        }

        val mutationResultsByCase = corpus.cases.associateWith { case ->
            runCatching { runner.evaluateMutations(case) }.getOrElse { error ->
                listOf(invalidEvaluation(case.definition.id, error.message ?: error.javaClass.simpleName))
            }
        }
        val mutationResults = mutationResultsByCase.values.flatten()
        produced += ConformanceCheck(
            name = RealWorldCorpusRunner.MUTATION_CHECK,
            passed = mutationResults.isNotEmpty() && mutationResults.all { it.accepted },
            message = mutationResults.flatMap { result -> result.mismatches.map { "${result.caseId}: $it" } }
                .takeIf { it.isNotEmpty() }?.joinToString(" | ")
        )

        val polarityErrors = domainMutationPolarityErrors(corpus, baselineResults, mutationResultsByCase)
        produced += ConformanceCheck(
            name = RealWorldCorpusRunner.DOMAIN_MUTATION_CHECK,
            passed = polarityErrors.isEmpty(),
            message = polarityErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
        )
        return withInventoryCheck(produced)
    }

    private fun evaluateSafely(case: LoadedRealWorldCase): RealWorldEvaluationResult =
        runCatching { runner.evaluate(case) }.getOrElse { error ->
            invalidEvaluation(case.definition.id, error.message ?: error.javaClass.simpleName)
        }

    private fun invalidEvaluation(caseId: String, mismatch: String) = RealWorldEvaluationResult(
        caseId = caseId,
        outcome = RealWorldResult.INVALID_SOURCE_PIPELINE,
        planGenerated = false,
        diagnostics = emptyList(),
        targetAssessments = emptyList(),
        mismatches = listOf(mismatch)
    )

    private fun withInventoryCheck(produced: List<ConformanceCheck>): List<ConformanceCheck> {
        val inventoryResult = runCatching {
            val file = File(rootDir, INVENTORY_PATH)
            require(file.isFile) { "Missing C0.1 conformance inventory: ${file.path}" }
            val raw = FlowYaml.readMap(file)
            require(raw["version"]?.toString() == "1.1") { "C0.1 conformance inventory must use version 1.1." }
            (raw["checks"] as? Iterable<*>)?.map { it.toString() }
                ?: error("C0.1 conformance inventory must declare checks.")
        }
        val declared = inventoryResult.getOrNull()
        val observed = produced.map { it.name }
        val exact = declared == observed
        val message = when {
            inventoryResult.isFailure -> inventoryResult.exceptionOrNull()?.message
            !exact -> "declared=${declared?.joinToString()} observed=${observed.joinToString()}"
            else -> null
        }
        return listOf(ConformanceCheck(INVENTORY_CHECK, exact, message)) + produced
    }

    private fun domainCoverageErrors(corpus: LoadedRealWorldCorpus): List<String> = buildList {
        val declaredDomains = corpus.manifest.scope.domains
        val expectedDomains = RealWorldDomain.values().map { it.documentValue }
        if (declaredDomains != expectedDomains) {
            add("Manifest domains must be exactly $expectedDomains, got $declaredDomains.")
        }
        if (DOMAIN_SCOPE_BOUNDARY !in corpus.manifest.invariants) {
            add("Manifest must state that the closed domain vocabulary is limited to C0.1 evidence scope.")
        }
        val unknown = corpus.cases.filter { it.definition.classifiedDomain() == null }
        if (unknown.isNotEmpty()) {
            add("Cases declare unknown domains: ${unknown.map { "${it.definition.id}=${it.definition.domain}" }.sorted()}.")
        }
        val grouped = corpus.cases.mapNotNull { case ->
            case.definition.classifiedDomain()?.let { it to case.definition.id }
        }.groupBy({ it.first }, { it.second })
        RealWorldDomain.values().forEach { domain ->
            if (grouped[domain].isNullOrEmpty()) add("Domain '${domain.documentValue}' has no accepted case package.")
        }
        val duplicates = corpus.cases.groupingBy { it.definition.id }.eachCount().filterValues { it != 1 }
        if (duplicates.isNotEmpty()) add("Accepted case identities are not unique: $duplicates.")
    }

    private fun domainRepresentabilityErrors(
        corpus: LoadedRealWorldCorpus,
        results: Map<LoadedRealWorldCase, RealWorldEvaluationResult>
    ): List<String> = buildList {
        RealWorldDomain.values().forEach { domain ->
            val positive = corpus.cases
                .filter { it.definition.classifiedDomain() == domain }
                .map { results.getValue(it) }
                .filter { RealWorldPolarityAuthority.classify(it) == RealWorldPolarity.REPRESENTABLE }
            if (positive.isEmpty()) {
                add("Domain '${domain.documentValue}' has no positively representable baseline.")
            }
        }
    }

    private fun dynamicBoundaryErrors(
        corpus: LoadedRealWorldCorpus,
        results: Map<LoadedRealWorldCase, RealWorldEvaluationResult>
    ): List<String> = buildList {
        val dynamic = corpus.cases.map { it to results.getValue(it) }
            .filter { (_, result) -> result.outcome == RealWorldResult.UNSUPPORTED_DYNAMIC_CONSTRUCTION }
        if (dynamic.isEmpty()) add("Corpus has no explicit unsupported dynamic-construction baseline.")
        dynamic.forEach { (case, result) ->
            val expectedDiagnostic = when (case.definition.id) {
                "A11" -> RealWorldCorpusRunner.DYNAMIC_MATRIX_NOT_REPRESENTED
                "N05" -> RealWorldCorpusRunner.RUNTIME_PLAN_NOT_REPRESENTED
                else -> null
            }
            if (!result.accepted || !result.planGenerated || expectedDiagnostic == null ||
                result.diagnostics != listOf(expectedDiagnostic) || result.targetAssessments.any { it.executable }
            ) {
                add("Dynamic case '${case.definition.id}' does not preserve exact unsupported-boundary evidence.")
            }
        }
    }

    private fun domainMutationPolarityErrors(
        corpus: LoadedRealWorldCorpus,
        baselines: Map<LoadedRealWorldCase, RealWorldEvaluationResult>,
        mutations: Map<LoadedRealWorldCase, List<RealWorldEvaluationResult>>
    ): List<String> = buildList {
        RealWorldDomain.values().forEach { domain ->
            val representableCases = corpus.cases.filter {
                it.definition.classifiedDomain() == domain &&
                    RealWorldPolarityAuthority.classify(baselines.getValue(it)) == RealWorldPolarity.REPRESENTABLE
            }
            val flips = mutableListOf<String>()
            representableCases.forEach { case ->
                mutations[case].orEmpty().forEach { mutation ->
                    val comparison = RealWorldPolarityAuthority.compare(baselines.getValue(case), mutation)
                    if (comparison.flipped) {
                        flips += mutation.caseId
                    } else {
                        add("${mutation.caseId}: ${comparison.reason}")
                    }
                }
            }
            if (flips.isEmpty()) {
                add("Domain '${domain.documentValue}' has no REPRESENTABLE -> REJECTED mutation proof.")
            }
        }
    }

    private fun sourceCompositionErrors(corpus: LoadedRealWorldCorpus): List<String> {
        val errors = mutableListOf<String>()
        val counts = corpus.manifest.counts
        val sources = corpus.sources.sources
        val scenarios = corpus.scenarios.scenarios
        val cases = corpus.cases
        val actualCounts = mapOf(
            "sources" to sources.size,
            "admittedSources" to sources.count { it.admission == "admitted" },
            "screenedSources" to sources.count { it.admission == "screened" },
            "scenarios" to scenarios.size,
            "admittedScenarios" to scenarios.count { it.admission == "admitted" },
            "screenedScenarios" to scenarios.count { it.admission == "screened" },
            "plannedScenarios" to scenarios.count { it.admission == "planned" },
            "executableCases" to cases.size,
            "mutationCases" to cases.sumOf { it.mutations.size }
        )
        val declaredCounts = mapOf(
            "sources" to counts.sources,
            "admittedSources" to counts.admittedSources,
            "screenedSources" to counts.screenedSources,
            "scenarios" to counts.scenarios,
            "admittedScenarios" to counts.admittedScenarios,
            "screenedScenarios" to counts.screenedScenarios,
            "plannedScenarios" to counts.plannedScenarios,
            "executableCases" to counts.executableCases,
            "mutationCases" to counts.mutationCases
        )
        declaredCounts.forEach { (label, declared) -> compareCount(errors, label, declared, actualCounts.getValue(label)) }

        val unclassifiedSources = sources.filter { it.classifiedKind() == null }
        val unclassifiedCases = cases.filter { it.source.classifiedKind() == null }
        val sourceKinds = sources.mapNotNull { it.classifiedKind() }.groupingBy { it }.eachCount()
        val caseKinds = cases.mapNotNull { it.source.classifiedKind() }.groupingBy { it }.eachCount()
        if (unclassifiedSources.isNotEmpty()) errors += "Unknown source kinds: ${unclassifiedSources.map { it.sourceKind }.distinct().sorted()}."
        if (unclassifiedCases.isNotEmpty()) errors += "Executable cases use unknown primary source kinds: ${unclassifiedCases.map { it.source.sourceKind }.distinct().sorted()}."

        compareCount(errors, "productionSources", counts.productionSources, sourceKinds[RealWorldSourceKind.PRODUCTION_WORKFLOW] ?: 0)
        compareCount(errors, "exampleSources", counts.exampleSources, sourceKinds[RealWorldSourceKind.OFFICIAL_EXAMPLE] ?: 0)
        compareCount(errors, "semanticReferenceSources", counts.semanticReferenceSources, sourceKinds[RealWorldSourceKind.OFFICIAL_SEMANTIC_REFERENCE] ?: 0)
        compareCount(errors, "productionCases", counts.productionCases, caseKinds[RealWorldSourceKind.PRODUCTION_WORKFLOW] ?: 0)
        compareCount(errors, "exampleCases", counts.exampleCases, caseKinds[RealWorldSourceKind.OFFICIAL_EXAMPLE] ?: 0)
        compareCount(errors, "semanticReferenceCases", counts.semanticReferenceCases, caseKinds[RealWorldSourceKind.OFFICIAL_SEMANTIC_REFERENCE] ?: 0)
        if (counts.productionSources + counts.exampleSources + counts.semanticReferenceSources != counts.sources) errors += "Declared source-class counts do not sum to sources=${counts.sources}."
        if (counts.productionCases + counts.exampleCases + counts.semanticReferenceCases != counts.executableCases) errors += "Declared executable-case source-class counts do not sum to executableCases=${counts.executableCases}."
        return errors
    }

    private fun compareCount(errors: MutableList<String>, label: String, declared: Int, actual: Int) {
        if (declared != actual) errors += "Manifest $label=$declared, actual=$actual."
    }

    companion object {
        const val INVENTORY_PATH = "conformance/check-inventory.yaml"
        const val INVENTORY_CHECK = "conformance.c0.1.inventory-integrity"
        const val LIFECYCLE_CHECK = "conformance.c0.1.lifecycle-integrity"
        const val DOMAIN_REPRESENTABILITY_CHECK = "real-world-corpus.domain-representability"
        const val DYNAMIC_BOUNDARY_CHECK = "real-world-corpus.dynamic-boundary-honesty"
        const val DOMAIN_SCOPE_BOUNDARY = "The three declared domains close only the C0.1 evidence scope and do not define an exhaustive automation taxonomy."
    }
}
