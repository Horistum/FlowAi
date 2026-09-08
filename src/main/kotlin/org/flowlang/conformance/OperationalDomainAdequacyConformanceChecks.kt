package org.flowlang.conformance

import java.io.File
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.modules.ModuleRegistry
import org.flowlang.serialization.FlowYaml

class OperationalDomainAdequacyConformanceChecks(
    private val rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, TargetCapability>,
    projections: TargetProjectionRegistry
) {
    private val loader = OperationalDomainCorpusLoader(rootDir)
    private val evaluator = RealWorldCorpusRunner(rootDir, registry, targets, projections)

    fun checks(): List<ConformanceCheck> {
        val produced = mutableListOf<ConformanceCheck>()

        val lifecycleResult = runCatching { OperationalDomainAdequacyRoadmapLifecycleAuthority(rootDir).analyze() }
        val lifecycle = lifecycleResult.getOrNull()
        produced += ConformanceCheck(
            name = LIFECYCLE_CHECK,
            passed = lifecycle?.status == "PASS",
            message = lifecycleResult.exceptionOrNull()?.message
                ?: lifecycle?.errors?.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
        )

        val corpusResult = runCatching(loader::load)
        val corpus = corpusResult.getOrNull()
        produced += ConformanceCheck(
            name = CORPUS_INTEGRITY_CHECK,
            passed = corpus != null,
            message = corpusResult.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        )
        if (corpus == null) return produced

        val scopeErrors = scopeConsistencyErrors(corpus)
        produced += ConformanceCheck(
            name = SCOPE_CONSISTENCY_CHECK,
            passed = scopeErrors.isEmpty(),
            message = scopeErrors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
        )

        val domainErrors = domainCoverageErrors(corpus)
        produced += ConformanceCheck(
            name = DOMAIN_COVERAGE_CHECK,
            passed = domainErrors.isEmpty(),
            message = domainErrors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
        )

        val baselines = corpus.cases.associateWith(::evaluateSafely)
        val capabilityErrors = capabilityCoverageErrors(corpus, baselines)
        produced += ConformanceCheck(
            name = CAPABILITY_COVERAGE_CHECK,
            passed = capabilityErrors.isEmpty(),
            message = capabilityErrors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
        )

        corpus.cases.forEach { case ->
            val evaluation = baselines.getValue(case)
            produced += ConformanceCheck(
                name = "${OperationalDomainCorpusLoader.CASE_CHECK_PREFIX}.${case.definition.id.lowercase()}",
                passed = evaluation.accepted,
                message = evaluation.mismatches.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
            )
        }

        val mutationsByCase = corpus.cases.associateWith { case ->
            runCatching { evaluator.evaluateMutations(case) }.getOrElse { error ->
                listOf(invalidEvaluation(case.definition.id, error.message ?: error.javaClass.simpleName))
            }
        }
        val mutations = mutationsByCase.values.flatten()
        produced += ConformanceCheck(
            name = OperationalDomainCorpusLoader.MUTATION_CHECK,
            passed = mutations.isNotEmpty() && mutations.all { it.accepted },
            message = mutations.flatMap { result -> result.mismatches.map { "${result.caseId}: $it" } }
                .takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
        )

        val polarityErrors = mutationPolarityErrors(corpus, baselines, mutationsByCase)
        produced += ConformanceCheck(
            name = OperationalDomainCorpusLoader.DOMAIN_POLARITY_CHECK,
            passed = polarityErrors.isEmpty(),
            message = polarityErrors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
        )

        val separationErrors = c01BoundaryPreservationErrors()
        produced += ConformanceCheck(
            name = C01_BOUNDARY_CHECK,
            passed = separationErrors.isEmpty(),
            message = separationErrors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
        )
        return produced
    }


    private fun scopeConsistencyErrors(corpus: LoadedOperationalDomainCorpus): List<String> = buildList {
        val workPackageFile = File(rootDir, OperationalDomainAdequacyRoadmapLifecycleAuthority.WORK_PACKAGE)
        val workPackage = runCatching { FlowYaml.readMap(workPackageFile) }.getOrElse { error ->
            add("Cannot verify C1.0 work-package scope: ${error.message ?: error.javaClass.simpleName}.")
            return@buildList
        }
        val scope = (workPackage["scope"] as? Map<*, *>)?.entries
            ?.associate { it.key.toString() to it.value }.orEmpty()
        fun stringList(key: String): List<String> =
            (scope[key] as? Iterable<*>)?.map { it.toString() }.orEmpty()

        if (stringList("domains") != corpus.manifest.scope.domains) {
            add("C1.0 work-package domains must equal the corpus domains.")
        }
        if (stringList("requiredCapabilities") != corpus.manifest.scope.requiredCapabilities) {
            add("C1.0 work-package capabilities must equal the corpus required capabilities.")
        }
        if (scope["evidenceRoot"]?.toString() != OperationalDomainCorpusLoader.CORPUS_ROOT) {
            add("C1.0 work-package evidenceRoot must be '${OperationalDomainCorpusLoader.CORPUS_ROOT}'.")
        }
        val sourceClass = scope["sourceClass"]?.toString().orEmpty()
        if (sourceClass.isBlank() || corpus.manifest.sources.any { it.sourceKind != sourceClass }) {
            add("C1.0 work-package sourceClass must match every admitted corpus source kind.")
        }
    }

    private fun evaluateSafely(case: LoadedRealWorldCase): RealWorldEvaluationResult =
        runCatching {
            evaluator.evaluate(case, OperationalDomainCorpusLoader.CASE_CHECK_PREFIX)
        }.getOrElse { error ->
            invalidEvaluation(case.definition.id, error.message ?: error.javaClass.simpleName)
        }

    private fun domainCoverageErrors(corpus: LoadedOperationalDomainCorpus): List<String> = buildList {
        val expectedDomains = OperationalEvidenceDomain.entries.map { it.documentValue }
        if (corpus.manifest.scope.domains != expectedDomains) {
            add("C1.0 domain vocabulary must be exactly $expectedDomains, got ${corpus.manifest.scope.domains}.")
        }
        val unknown = corpus.cases.filter { OperationalEvidenceDomain.fromDocument(it.definition.domain) == null }
        if (unknown.isNotEmpty()) {
            add("C1.0 cases declare unknown domains: ${unknown.map { "${it.definition.id}=${it.definition.domain}" }.sorted()}.")
        }
        OperationalEvidenceDomain.entries.forEach { domain ->
            if (corpus.cases.none { it.definition.domain == domain.documentValue }) {
                add("C1.0 domain '${domain.documentValue}' has no executable case.")
            }
        }
    }

    private fun capabilityCoverageErrors(
        corpus: LoadedOperationalDomainCorpus,
        baselines: Map<LoadedRealWorldCase, RealWorldEvaluationResult>
    ): List<String> = buildList {
        val required = corpus.manifest.scope.requiredCapabilities.toSet()
        val casesByCapability = required.associateWith { capability ->
            corpus.cases.filter { case -> case.expectedPlan.tasks.any { it.semanticCapability == capability } }
        }
        casesByCapability.forEach { (capability, cases) ->
            if (cases.isEmpty()) {
                add("C1.0 required capability '$capability' has no executable case.")
            } else if (cases.none { RealWorldPolarityAuthority.classify(baselines.getValue(it)) == RealWorldPolarity.REPRESENTABLE }) {
                add("C1.0 required capability '$capability' has no positively representable baseline.")
            }
        }
        val observed = corpus.cases.flatMap { case -> case.expectedPlan.tasks.mapNotNull { it.semanticCapability } }.toSet()
        val missing = required - observed
        if (missing.isNotEmpty()) add("C1.0 expected-plan evidence omits required capabilities: ${missing.sorted()}.")
    }

    private fun mutationPolarityErrors(
        corpus: LoadedOperationalDomainCorpus,
        baselines: Map<LoadedRealWorldCase, RealWorldEvaluationResult>,
        mutations: Map<LoadedRealWorldCase, List<RealWorldEvaluationResult>>
    ): List<String> = buildList {
        corpus.cases.forEach { case ->
            val baseline = baselines.getValue(case)
            val caseMutations = mutations[case].orEmpty()
            if (caseMutations.isEmpty()) {
                add("C1.0 case '${case.definition.id}' has no observed negative mutation.")
            }
            caseMutations.forEach { mutation ->
                val comparison = RealWorldPolarityAuthority.compare(baseline, mutation)
                if (!comparison.flipped) add("${mutation.caseId}: ${comparison.reason}")
            }
        }
    }

    private fun c01BoundaryPreservationErrors(): List<String> = buildList {
        val manifestFile = File(rootDir, RealWorldCorpusRunner.CORPUS_ROOT + "/manifest.yaml")
        val manifest = runCatching {
            RealWorldCorpusSerialization.readYaml(manifestFile, RealWorldCorpusManifest::class.java)
        }.getOrElse { error ->
            add("Cannot verify frozen C0.1 corpus boundary: ${error.message ?: error.javaClass.simpleName}.")
            return@buildList
        }
        val expectedDomains = RealWorldDomain.entries.map { it.documentValue }
        if (manifest.scope.domains != expectedDomains) {
            add("C1.0 must not alter the closed C0.1 domain vocabulary: expected=$expectedDomains actual=${manifest.scope.domains}.")
        }
        if (manifest.casePackages.any { it.contains("DP01") || it.contains("DP02") }) {
            add("C1.0 operational cases must not be inserted into the C0.1 case package inventory.")
        }
        val c01Inventory = File(rootDir, RealWorldCorpusConformanceChecks.INVENTORY_PATH)
        if (!c01Inventory.isFile) {
            add("Cannot verify frozen C0.1 conformance inventory: ${c01Inventory.path} is missing.")
        } else {
            val ids = (FlowYaml.readMap(c01Inventory)["checks"] as? Iterable<*>)?.map { it.toString() }.orEmpty()
            if (ids.any { it.startsWith("conformance.c1.0") || it.startsWith("operational-domain-corpus") }) {
                add("C1.0 checks must remain outside the frozen C0.1 conformance inventory.")
            }
        }
    }

    private fun invalidEvaluation(caseId: String, mismatch: String) = RealWorldEvaluationResult(
        caseId = caseId,
        outcome = RealWorldResult.INVALID_SOURCE_PIPELINE,
        planGenerated = false,
        diagnostics = emptyList(),
        targetAssessments = emptyList(),
        mismatches = listOf(mismatch)
    )

    companion object {
        const val CHECK_PREFIX = "conformance.c1.0."
        const val LIFECYCLE_CHECK = "${CHECK_PREFIX}lifecycle-integrity"
        const val CORPUS_INTEGRITY_CHECK = "${CHECK_PREFIX}corpus-integrity"
        const val SCOPE_CONSISTENCY_CHECK = "${CHECK_PREFIX}scope-consistency"
        const val DOMAIN_COVERAGE_CHECK = "${CHECK_PREFIX}domain-coverage"
        const val CAPABILITY_COVERAGE_CHECK = "${CHECK_PREFIX}capability-coverage"
        const val C01_BOUNDARY_CHECK = "${CHECK_PREFIX}c0.1-boundary-preservation"
    }
}

data class OperationalDomainAdequacyConformanceInventory(
    val version: String,
    val checks: List<String>
) {
    init {
        require(version == VERSION) { "C1.0 conformance inventory version '$version' is unsupported; expected '$VERSION'." }
        require(checks.isNotEmpty()) { "C1.0 conformance inventory must declare checks." }
        require(checks.none(String::isBlank)) { "C1.0 conformance inventory contains a blank check id." }
        require(checks.size == checks.toSet().size) { "C1.0 conformance inventory contains duplicate check ids." }
        require(checks.all { it.startsWith("conformance.c1.0.") || it.startsWith("operational-domain-corpus.") }) {
            "C1.0 conformance inventory contains an out-of-scope check id."
        }
    }

    companion object {
        const val PATH = "conformance/operational/operational-adequacy-check-inventory.yaml"
        const val VERSION = "1.0"
        private val KEYS = setOf("version", "checks")

        fun load(rootDir: File = File(".")): OperationalDomainAdequacyConformanceInventory {
            val file = File(rootDir, PATH)
            require(file.isFile) { "C1.0 conformance inventory is missing: ${file.path}" }
            val yaml = FlowYaml.readMap(file)
            require(yaml.keys == KEYS) {
                "$PATH keys must be exactly ${KEYS.sorted()}, got ${yaml.keys.sorted()}."
            }
            val checks = (yaml["checks"] as? Iterable<*>)?.mapIndexed { index, value ->
                (value as? String)?.takeIf(String::isNotBlank)
                    ?: error("$PATH.checks[$index] must be non-blank text.")
            } ?: error("$PATH.checks must be a list.")
            return OperationalDomainAdequacyConformanceInventory(
                version = yaml["version"]?.toString().orEmpty(),
                checks = checks
            )
        }
    }
}

class OperationalDomainAdequacyConformanceRunner(
    private val rootDir: File,
    private val registry: ModuleRegistry,
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry
) {
    fun checks(): List<ConformanceCheck> {
        val produced = OperationalDomainAdequacyConformanceChecks(rootDir, registry, targets, projections).checks()
        val inventoryResult = runCatching { OperationalDomainAdequacyConformanceInventory.load(rootDir) }
        val inventory = inventoryResult.getOrNull()
        val observed = produced.map(ConformanceCheck::name)
        val exact = inventory != null && inventory.checks == observed
        val message = when {
            inventoryResult.isFailure -> inventoryResult.exceptionOrNull()?.message
            !exact -> "declared=${inventory?.checks?.joinToString()} observed=${observed.joinToString()}"
            else -> null
        }
        return listOf(ConformanceCheck(INVENTORY_CHECK, exact, message)) + produced
    }

    companion object {
        const val INVENTORY_CHECK = "conformance.c1.0.inventory-exact"
    }
}
