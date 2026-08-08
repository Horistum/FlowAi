package org.flowlang.conformance

/**
 * C1.0 owns a new bounded evidence vocabulary rather than extending the closed
 * C0.1 [RealWorldDomain] set. This keeps historical C0.1 evidence immutable.
 */
enum class OperationalEvidenceDomain(val documentValue: String) {
    DATA_PROTECTION("data-protection");

    companion object {
        fun fromDocument(value: String): OperationalEvidenceDomain? =
            entries.singleOrNull { it.documentValue == value }
    }
}

data class OperationalDomainCorpusScope(
    val domains: List<String> = emptyList(),
    val requiredCapabilities: List<String> = emptyList(),
    val primaryUse: String = "",
    val predecessor: String = ""
)

data class OperationalDomainCorpusCounts(
    val sources: Int = 0,
    val executableCases: Int = 0,
    val mutationCases: Int = 0
)

data class OperationalDomainCorpusManifest(
    val kind: String = "",
    val version: String = "",
    val status: String = "",
    val name: String = "",
    val scope: OperationalDomainCorpusScope = OperationalDomainCorpusScope(),
    val sources: List<RealWorldSourceRecord> = emptyList(),
    val schemas: Map<String, String> = emptyMap(),
    val casePackages: List<String> = emptyList(),
    val counts: OperationalDomainCorpusCounts = OperationalDomainCorpusCounts(),
    val invariants: List<String> = emptyList()
)

data class OperationalDomainCaseDefinition(
    val kind: String = "",
    val version: String = "",
    val id: String = "",
    val title: String = "",
    val lifecycle: RealWorldLifecycle = RealWorldLifecycle.CATALOGUED,
    val category: String = "",
    val domain: String = "",
    val sourceRef: String = "",
    val source: RealWorldCaseSource = RealWorldCaseSource(),
    val sourceObservations: String = "",
    val intent: String = "",
    val expected: RealWorldCaseExpectedFiles = RealWorldCaseExpectedFiles(),
    val evidence: String = "",
    val mutations: List<RealWorldMutationRef> = emptyList(),
    val invariants: List<String> = emptyList()
) {
    fun asEvaluationDefinition(): RealWorldCaseDefinition = RealWorldCaseDefinition(
        kind = kind,
        version = version,
        id = id,
        title = title,
        lifecycle = lifecycle,
        category = category,
        domain = domain,
        sourceRef = sourceRef,
        source = source,
        sourceObservations = sourceObservations,
        intent = intent,
        expected = expected,
        evidence = evidence,
        mutations = mutations,
        invariants = invariants
    )
}

data class LoadedOperationalDomainCorpus(
    val manifest: OperationalDomainCorpusManifest,
    val cases: List<LoadedRealWorldCase>
)
