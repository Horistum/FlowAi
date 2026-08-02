package org.flowlang.conformance

import java.io.File
import org.flowlang.planner.PlanDependencyKind

enum class RealWorldResult {
    SUPPORTED,
    SUPPORTED_WITH_BINDING,
    SEMANTIC_ONLY,
    BLOCKED_BY_TARGET_CAPABILITY,
    AMBIGUOUS_SOURCE_INTENT,
    UNSUPPORTED_DYNAMIC_CONSTRUCTION,
    INVALID_SOURCE_PIPELINE
}

enum class RealWorldLifecycle {
    CATALOGUED,
    SOURCE_VERIFIED,
    INTENT_RECONSTRUCTED,
    FIXTURE_AUTHORED,
    PLAN_VALIDATED,
    TARGET_ASSESSED,
    MUTATION_VALIDATED,
    ACCEPTED
}

enum class RealWorldSourceKind(val documentValue: String) {
    PRODUCTION_WORKFLOW("production-workflow"),
    OFFICIAL_EXAMPLE("official-example"),
    OFFICIAL_SEMANTIC_REFERENCE("official-semantic-reference");

    companion object {
        fun fromDocument(value: String): RealWorldSourceKind? =
            values().singleOrNull { it.documentValue == value }
    }
}

data class RealWorldCorpusScope(
    val domains: List<String> = emptyList(),
    val primaryUse: String = "",
    val roadmapRelationship: Map<String, String> = emptyMap()
)

data class RealWorldCorpusCounts(
    val sources: Int = 0,
    val admittedSources: Int = 0,
    val screenedSources: Int = 0,
    val productionSources: Int = 0,
    val exampleSources: Int = 0,
    val semanticReferenceSources: Int = 0,
    val scenarios: Int = 0,
    val admittedScenarios: Int = 0,
    val screenedScenarios: Int = 0,
    val plannedScenarios: Int = 0,
    val executableCases: Int = 0,
    val productionCases: Int = 0,
    val exampleCases: Int = 0,
    val semanticReferenceCases: Int = 0,
    val mutationCases: Int = 0
)

data class RealWorldCorpusManifest(
    val kind: String = "",
    val version: String = "",
    val status: String = "",
    val name: String = "",
    val scope: RealWorldCorpusScope = RealWorldCorpusScope(),
    val catalogs: Map<String, String> = emptyMap(),
    val schemas: Map<String, String> = emptyMap(),
    val casePackages: List<String> = emptyList(),
    val counts: RealWorldCorpusCounts = RealWorldCorpusCounts(),
    val invariants: List<String> = emptyList()
)

data class RealWorldSourceLicense(
    val status: String = "",
    val spdx: String? = null,
    val evidence: String = ""
)

data class RealWorldSourceRecord(
    val id: String = "",
    val platform: String = "",
    val sourceKind: String = "",
    val admission: String = "",
    val repository: String = "",
    val revision: String = "",
    val path: String = "",
    val license: RealWorldSourceLicense = RealWorldSourceLicense(),
    val semanticLoad: List<String> = emptyList()
) {
    fun classifiedKind(): RealWorldSourceKind? = RealWorldSourceKind.fromDocument(sourceKind)
}

data class RealWorldSourceCatalog(
    val kind: String = "",
    val version: String = "",
    val status: String = "",
    val admissionPolicy: Map<String, String> = emptyMap(),
    val sources: List<RealWorldSourceRecord> = emptyList()
)

data class RealWorldScenarioRecord(
    val id: String = "",
    val title: String = "",
    val category: String = "",
    val admission: String = "",
    val lifecycle: RealWorldLifecycle = RealWorldLifecycle.CATALOGUED,
    val casePath: String? = null,
    val sourceRefs: List<String> = emptyList(),
    val dominantSemantics: List<String> = emptyList(),
    val continuityKinds: List<String> = emptyList(),
    val baselineExpectation: RealWorldResult = RealWorldResult.SEMANTIC_ONLY,
    val claim: String = "",
    val sourceCoverage: String? = null,
    val coverageGap: String? = null
)

data class RealWorldScenarioCatalog(
    val kind: String = "",
    val version: String = "",
    val status: String = "",
    val resultVocabulary: List<RealWorldResult> = emptyList(),
    val continuityVocabulary: List<String> = emptyList(),
    val scenarios: List<RealWorldScenarioRecord> = emptyList()
)

data class RealWorldCaseSource(
    val workflow: String = "",
    val provenance: String = "",
    val license: String = ""
)

data class RealWorldCaseExpectedFiles(
    val executionPlan: String = "",
    val targetAssessments: String = ""
)

data class RealWorldMutationRef(
    val id: String = "",
    val definition: String = ""
)

data class RealWorldCaseDefinition(
    val kind: String = "",
    val version: String = "",
    val id: String = "",
    val title: String = "",
    val lifecycle: RealWorldLifecycle = RealWorldLifecycle.CATALOGUED,
    val category: String = "",
    val sourceRef: String = "",
    val source: RealWorldCaseSource = RealWorldCaseSource(),
    val sourceObservations: String = "",
    val intent: String = "",
    val expected: RealWorldCaseExpectedFiles = RealWorldCaseExpectedFiles(),
    val evidence: String = "",
    val mutations: List<RealWorldMutationRef> = emptyList(),
    val invariants: List<String> = emptyList()
)

data class RealWorldCaseProvenance(
    val repository: String = "",
    val revision: String = "",
    val path: String = "",
    val license: String = ""
)

data class RealWorldExpectedTask(
    val sourceId: String = "",
    val semanticCapability: String? = null,
    val nodeKind: String? = null
)

data class RealWorldExpectedRelation(
    val source: String = "",
    val target: String = "",
    val kind: PlanDependencyKind = PlanDependencyKind.ORDERING,
    val channel: String? = null
)

data class RealWorldExpectedOrdering(
    val source: String = "",
    val target: String = ""
)

data class RealWorldExpectedPlan(
    val generated: Boolean = true,
    val exactTaskSources: Boolean = true,
    val expectedOutcome: RealWorldResult = RealWorldResult.SEMANTIC_ONLY,
    val tasks: List<RealWorldExpectedTask> = emptyList(),
    val requiredRelations: List<RealWorldExpectedRelation> = emptyList(),
    val forbiddenOrdering: List<RealWorldExpectedOrdering> = emptyList(),
    val requiredFeatures: List<String> = emptyList(),
    val expectedDiagnostics: List<String> = emptyList()
)

data class RealWorldTargetAssessmentExpectation(
    val target: String = "",
    val allowedResults: List<RealWorldResult> = emptyList(),
    val mustNotBeExecutable: Boolean = false
)

data class RealWorldTargetAssessmentDocument(
    val assessments: List<RealWorldTargetAssessmentExpectation> = emptyList()
)

data class RealWorldEvidence(
    val kind: String = "",
    val version: String = "",
    val caseId: String = "",
    val status: RealWorldLifecycle = RealWorldLifecycle.CATALOGUED,
    val outcome: RealWorldResult = RealWorldResult.SEMANTIC_ONLY,
    val expectedDiagnostics: List<String> = emptyList(),
    val sourceRevision: String = "",
    val validatedBy: List<String> = emptyList()
)

data class RealWorldMutationDefinition(
    val kind: String = "",
    val version: String = "",
    val id: String = "",
    val intent: String = "",
    val expectedGenerated: Boolean = true,
    val expectedOutcome: RealWorldResult = RealWorldResult.SEMANTIC_ONLY,
    val expectedDiagnostics: List<String> = emptyList(),
    val tasks: List<RealWorldExpectedTask> = emptyList(),
    val requiredRelations: List<RealWorldExpectedRelation> = emptyList(),
    val forbiddenOrdering: List<RealWorldExpectedOrdering> = emptyList(),
    val requiredFeatures: List<String> = emptyList()
) {
    fun expectedPlan(): RealWorldExpectedPlan = RealWorldExpectedPlan(
        generated = expectedGenerated,
        exactTaskSources = tasks.isNotEmpty(),
        expectedOutcome = expectedOutcome,
        tasks = tasks,
        requiredRelations = requiredRelations,
        forbiddenOrdering = forbiddenOrdering,
        requiredFeatures = requiredFeatures,
        expectedDiagnostics = expectedDiagnostics
    )
}

data class LoadedRealWorldCase(
    val definition: RealWorldCaseDefinition,
    val directory: File,
    val source: RealWorldSourceRecord,
    val provenance: RealWorldCaseProvenance,
    val expectedPlan: RealWorldExpectedPlan,
    val targetExpectations: RealWorldTargetAssessmentDocument,
    val evidence: RealWorldEvidence,
    val mutations: List<Pair<RealWorldMutationDefinition, File>>
)

data class LoadedRealWorldCorpus(
    val manifest: RealWorldCorpusManifest,
    val sources: RealWorldSourceCatalog,
    val scenarios: RealWorldScenarioCatalog,
    val cases: List<LoadedRealWorldCase>
)

data class RealWorldTargetAssessmentActual(
    val target: String,
    val result: RealWorldResult,
    val executable: Boolean,
    val detail: String? = null
)

data class RealWorldEvaluationResult(
    val caseId: String,
    val outcome: RealWorldResult,
    val planGenerated: Boolean,
    val diagnostics: List<String>,
    val targetAssessments: List<RealWorldTargetAssessmentActual>,
    val mismatches: List<String>
) {
    val accepted: Boolean get() = mismatches.isEmpty()
}
