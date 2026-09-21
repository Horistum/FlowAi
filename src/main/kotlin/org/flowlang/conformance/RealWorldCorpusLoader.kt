package org.flowlang.conformance

import org.flowlang.serialization.FlowJson

import java.io.File
import org.flowlang.cli.Json

class RealWorldCorpusLoader(private val rootDir: File = File(".")) {
    private val repositoryRoot = rootDir.canonicalFile
    private val corpusRoot = File(repositoryRoot, RealWorldCorpusRunner.CORPUS_ROOT)
    private val manifestFile = File(corpusRoot, "manifest.yaml")

    fun load(): LoadedRealWorldCorpus {
        require(manifestFile.isFile) { "Missing real-world corpus manifest: ${manifestFile.path}" }
        val manifest = RealWorldCorpusSerialization.readYaml(manifestFile, RealWorldCorpusManifest::class.java)
        validateSchema(manifest, BOOTSTRAP_MANIFEST_SCHEMA)
        require(manifest.kind == "FlowRealWorldCorpus") { "Unexpected real-world corpus kind '${manifest.kind}'." }
        require(manifest.version == "0.2") { "Real-world corpus manifest must use version 0.2, got '${manifest.version}'." }
        require(manifest.status == "executable-baseline") {
            "Real-world corpus must be executable-baseline, got '${manifest.status}'."
        }
        require(manifest.scope.domains.isNotEmpty()) { "Real-world corpus scope must declare domains." }
        require(manifest.scope.primaryUse.isNotBlank()) { "Real-world corpus scope must declare primaryUse." }
        require(manifest.casePackages.isNotEmpty()) { "Real-world corpus must contain executable case packages." }
        require(manifest.casePackages.distinct().size == manifest.casePackages.size) {
            "Real-world corpus case package paths must be unique."
        }
        require(manifest.schemas.keys == REQUIRED_SCHEMA_KEYS) {
            "Real-world corpus schemas must be exactly ${REQUIRED_SCHEMA_KEYS.sorted()}, got ${manifest.schemas.keys.sorted()}."
        }
        manifest.schemas.forEach { (id, path) ->
            require(resolveRootFile(path, "schema '$id'").extension == "json") {
                "Real-world corpus schema '$id' must be a JSON schema: $path"
            }
        }
        require(manifest.catalogs.keys == REQUIRED_CATALOG_KEYS) {
            "Real-world corpus catalogs must be exactly ${REQUIRED_CATALOG_KEYS.sorted()}, got ${manifest.catalogs.keys.sorted()}."
        }

        val sourcesFile = resolveRootFile(manifest.catalogs.getValue("sources"), "source catalog")
        val scenariosFile = resolveRootFile(manifest.catalogs.getValue("scenarios"), "scenario catalog")
        val acceptedScenariosFile = resolveRootFile(
            manifest.catalogs.getValue("acceptedScenarios"),
            "accepted scenario catalog"
        )

        val sources = RealWorldCorpusSerialization.readYaml(sourcesFile, RealWorldSourceCatalog::class.java)
        val scenarios = RealWorldCorpusSerialization.readYaml(scenariosFile, RealWorldScenarioCatalog::class.java)
        val acceptedScenarios = RealWorldCorpusSerialization.readYaml(
            acceptedScenariosFile,
            RealWorldScenarioCatalog::class.java
        )
        validateSchema(sources, manifest.schema("sources"))
        validateSchema(scenarios, manifest.schema("scenarios"))
        validateSchema(acceptedScenarios, manifest.schema("scenarios"))
        validateCatalogs(sources, scenarios, manifest)
        validateAcceptedScenarioIndex(sources, scenarios, acceptedScenarios, manifest)

        val sourcesById = sources.sources.associateBy { it.id }
        val acceptedScenariosById = acceptedScenarios.scenarios.associateBy { it.id }
        val cases = manifest.casePackages.map { relativePath ->
            loadCase(
                caseDir = resolveCorpusDirectory(relativePath, "case package"),
                sourcesById = sourcesById,
                acceptedScenariosById = acceptedScenariosById,
                manifest = manifest
            )
        }

        require(cases.map { it.definition.id }.distinct().size == cases.size) {
            "Real-world case ids must be unique."
        }
        require(cases.map { it.directory.canonicalPath }.distinct().size == cases.size) {
            "Real-world case packages must resolve to unique directories."
        }
        require(cases.map { it.definition.id }.toSet() == acceptedScenariosById.keys) {
            "Accepted scenario ids and loaded case ids must match exactly."
        }
        require(manifest.counts.executableCases == cases.size) {
            "Manifest executableCases=${manifest.counts.executableCases}, actual=${cases.size}."
        }
        val mutationCount = cases.sumOf { it.mutations.size }
        require(manifest.counts.mutationCases == mutationCount) {
            "Manifest mutationCases=${manifest.counts.mutationCases}, actual=$mutationCount."
        }
        return LoadedRealWorldCorpus(manifest, sources, scenarios, cases)
    }

    private fun validateCatalogs(
        sources: RealWorldSourceCatalog,
        scenarios: RealWorldScenarioCatalog,
        manifest: RealWorldCorpusManifest
    ) {
        require(sources.kind == "FlowRealWorldSourceCatalog") { "Unexpected source catalog kind '${sources.kind}'." }
        require(scenarios.kind == "FlowRealWorldScenarioCatalog") { "Unexpected scenario catalog kind '${scenarios.kind}'." }
        require(sources.admissionPolicy.keys.containsAll(setOf("admitted", "screened"))) {
            "Source admission policy must define admitted and screened boundaries."
        }
        require(sources.sources.map { it.id }.distinct().size == sources.sources.size) {
            "Real-world source ids must be unique."
        }
        sources.sources.forEach { source ->
            require(source.id.isNotBlank()) { "Real-world source id must not be blank." }
            require(source.revision.matches(REVISION_PATTERN)) {
                "Source '${source.id}' is not pinned to an immutable 40-character revision."
            }
            require(source.repository.count { it == '/' } == 1) {
                "Source '${source.id}' has invalid repository '${source.repository}'."
            }
            require(source.path.isNotBlank()) { "Source '${source.id}' has no source path." }
            require(source.admission in SOURCE_ADMISSIONS) {
                "Source '${source.id}' has unsupported admission '${source.admission}'."
            }
            require(source.license.status.isNotBlank()) { "Source '${source.id}' has no license status." }
            require(source.license.evidence.isNotBlank()) { "Source '${source.id}' has no license evidence." }
        }

        require(scenarios.resultVocabulary.toSet() == RealWorldResult.entries.toSet()) {
            "Scenario result vocabulary must exactly match RealWorldResult."
        }
        require(scenarios.continuityVocabulary.toSet() == CONTINUITY_VOCABULARY) {
            "Scenario continuity vocabulary must be exactly ${CONTINUITY_VOCABULARY.sorted()}."
        }
        require(scenarios.scenarios.map { it.id }.distinct().size == scenarios.scenarios.size) {
            "Real-world scenario ids must be unique."
        }
        val sourceIds = sources.sources.map { it.id }.toSet()
        scenarios.scenarios.forEach { scenario -> validateScenarioRecord(scenario, sourceIds) }

        val counts = manifest.counts
        require(counts.sources == sources.sources.size) { "Manifest sources=${counts.sources}, actual=${sources.sources.size}." }
        require(counts.admittedSources == sources.sources.count { it.admission == "admitted" }) {
            "Manifest admittedSources=${counts.admittedSources}, actual=${sources.sources.count { it.admission == "admitted" }}."
        }
        require(counts.screenedSources == sources.sources.count { it.admission == "screened" }) {
            "Manifest screenedSources=${counts.screenedSources}, actual=${sources.sources.count { it.admission == "screened" }}."
        }
        require(counts.scenarios == scenarios.scenarios.size) {
            "Manifest scenarios=${counts.scenarios}, actual=${scenarios.scenarios.size}."
        }
        require(counts.admittedScenarios == scenarios.scenarios.count { it.admission == "admitted" }) {
            "Manifest admittedScenarios=${counts.admittedScenarios}, actual=${scenarios.scenarios.count { it.admission == "admitted" }}."
        }
        require(counts.screenedScenarios == scenarios.scenarios.count { it.admission == "screened" }) {
            "Manifest screenedScenarios=${counts.screenedScenarios}, actual=${scenarios.scenarios.count { it.admission == "screened" }}."
        }
        require(counts.plannedScenarios == scenarios.scenarios.count { it.admission == "planned" }) {
            "Manifest plannedScenarios=${counts.plannedScenarios}, actual=${scenarios.scenarios.count { it.admission == "planned" }}."
        }
    }

    private fun validateAcceptedScenarioIndex(
        sources: RealWorldSourceCatalog,
        broadScenarios: RealWorldScenarioCatalog,
        acceptedScenarios: RealWorldScenarioCatalog,
        manifest: RealWorldCorpusManifest
    ) {
        require(acceptedScenarios.kind == "FlowRealWorldScenarioCatalog") {
            "Unexpected accepted scenario catalog kind '${acceptedScenarios.kind}'."
        }
        require(acceptedScenarios.version == "0.2" && acceptedScenarios.status == "executable-index") {
            "Accepted scenario catalog must be version 0.2 with status executable-index."
        }
        require(acceptedScenarios.resultVocabulary.toSet() == RealWorldResult.entries.toSet()) {
            "Accepted scenario result vocabulary must exactly match RealWorldResult."
        }
        require(acceptedScenarios.continuityVocabulary.toSet() == CONTINUITY_VOCABULARY) {
            "Accepted scenario continuity vocabulary must be exactly ${CONTINUITY_VOCABULARY.sorted()}."
        }
        require(acceptedScenarios.scenarios.size == manifest.counts.executableCases) {
            "Accepted scenario count ${acceptedScenarios.scenarios.size} differs from executableCases ${manifest.counts.executableCases}."
        }
        require(acceptedScenarios.scenarios.map { it.id }.distinct().size == acceptedScenarios.scenarios.size) {
            "Accepted scenario ids must be unique."
        }
        val broadById = broadScenarios.scenarios.associateBy { it.id }
        val sourceIds = sources.sources.map { it.id }.toSet()
        acceptedScenarios.scenarios.forEach { accepted ->
            validateScenarioRecord(accepted, sourceIds)
            val broad = broadById[accepted.id] ?: error(
                "Accepted scenario '${accepted.id}' is absent from the broad scenario catalog."
            )
            require(accepted.category == broad.category && accepted.admission == broad.admission) {
                "Accepted scenario '${accepted.id}' category/admission differs from the broad catalog."
            }
            require(accepted.lifecycle == RealWorldLifecycle.ACCEPTED) {
                "Accepted scenario '${accepted.id}' must have lifecycle ACCEPTED."
            }
            require(accepted.claim == "accepted-evidence") {
                "Accepted scenario '${accepted.id}' must declare claim accepted-evidence."
            }
            require(!accepted.casePath.isNullOrBlank()) {
                "Accepted scenario '${accepted.id}' must name its casePath."
            }
            require(accepted.sourceRefs.isNotEmpty()) {
                "Accepted scenario '${accepted.id}' must cite at least one immutable source."
            }
        }
        require(acceptedScenarios.scenarios.mapNotNull { it.casePath }.toSet() == manifest.casePackages.toSet()) {
            "Accepted scenario casePath values must match manifest casePackages exactly."
        }
    }

    private fun validateScenarioRecord(scenario: RealWorldScenarioRecord, sourceIds: Set<String>) {
        require(scenario.id.isNotBlank()) { "Real-world scenario id must not be blank." }
        require(scenario.category in SCENARIO_CATEGORIES) {
            "Scenario '${scenario.id}' has unsupported category '${scenario.category}'."
        }
        require(scenario.admission in SCENARIO_ADMISSIONS) {
            "Scenario '${scenario.id}' has unsupported admission '${scenario.admission}'."
        }
        require(scenario.sourceRefs.distinct().size == scenario.sourceRefs.size) {
            "Scenario '${scenario.id}' contains duplicate source references."
        }
        val missingSources = scenario.sourceRefs.filter { it !in sourceIds }
        require(missingSources.isEmpty()) {
            "Scenario '${scenario.id}' references unknown sources ${missingSources.sorted()}."
        }
        require(scenario.continuityKinds.all { it in CONTINUITY_VOCABULARY }) {
            "Scenario '${scenario.id}' uses unknown continuity kinds ${scenario.continuityKinds}."
        }
        if (scenario.sourceCoverage == "missing") {
            require(scenario.sourceRefs.isEmpty() && !scenario.coverageGap.isNullOrBlank()) {
                "Scenario '${scenario.id}' with missing coverage must have no source refs and must explain the gap."
            }
        }
    }

    private fun loadCase(
        caseDir: File,
        sourcesById: Map<String, RealWorldSourceRecord>,
        acceptedScenariosById: Map<String, RealWorldScenarioRecord>,
        manifest: RealWorldCorpusManifest
    ): LoadedRealWorldCase {
        val definitionFile = requiredFile(caseDir, "case.yaml", "case definition")
        val definition = RealWorldCorpusSerialization.readYaml(definitionFile, RealWorldCaseDefinition::class.java)
        validateSchema(definition, manifest.schema("case"))
        require(definition.kind == "FlowRealWorldCase" && definition.version == "0.2") {
            "Case '${definition.id}' must be FlowRealWorldCase version 0.2."
        }
        require(definition.lifecycle >= RealWorldLifecycle.MUTATION_VALIDATED) {
            "Case '${definition.id}' must be at least MUTATION_VALIDATED, got ${definition.lifecycle}."
        }
        require(definition.invariants.isNotEmpty() && definition.invariants.none { it.isBlank() }) {
            "Case '${definition.id}' must declare non-blank invariants."
        }
        require(definition.mutations.map { it.id }.distinct().size == definition.mutations.size) {
            "Case '${definition.id}' contains duplicate mutation ids."
        }
        require(definition.mutations.map { it.definition }.distinct().size == definition.mutations.size) {
            "Case '${definition.id}' contains duplicate mutation definition paths."
        }

        val scenario = acceptedScenariosById[definition.id] ?: error(
            "Case '${definition.id}' is absent from accepted-scenarios.yaml."
        )
        val source = sourcesById[definition.sourceRef] ?: error(
            "Case '${definition.id}' references unknown source '${definition.sourceRef}'."
        )
        require(definition.category == scenario.category) {
            "Case '${definition.id}' category '${definition.category}' differs from accepted scenario '${scenario.category}'."
        }
        require(definition.sourceRef in scenario.sourceRefs) {
            "Case '${definition.id}' primary source '${definition.sourceRef}' is absent from accepted scenario sourceRefs."
        }
        require(scenario.casePath == caseDir.relativeTo(corpusRoot).invariantSeparatorsPath) {
            "Case '${definition.id}' directory differs from accepted scenario casePath '${scenario.casePath}'."
        }
        require(source.license.status == "verified" && !source.license.spdx.isNullOrBlank()) {
            "Executable case '${definition.id}' requires verified SPDX source evidence, got ${source.license.status}/${source.license.spdx}."
        }

        val workflowFile = requiredFile(caseDir, definition.source.workflow, "source workflow")
        val provenanceFile = requiredFile(caseDir, definition.source.provenance, "source provenance")
        val licenseFile = requiredFile(caseDir, definition.source.license, "source license")
        val observationsFile = requiredFile(caseDir, definition.sourceObservations, "source observations")
        require(workflowFile.readText().isNotBlank()) { "Case '${definition.id}' source workflow is empty." }
        require(licenseFile.readText().contains("SPDX-License-Identifier: ${source.license.spdx}")) {
            "Case '${definition.id}' source license must cite SPDX identifier '${source.license.spdx}'."
        }
        val observations = observationsFile.readText()
        REQUIRED_OBSERVATION_HEADINGS.forEach { heading ->
            require(heading in observations) { "Case '${definition.id}' observations are missing '$heading'." }
        }

        val provenance = RealWorldCorpusSerialization.readYaml(provenanceFile, RealWorldCaseProvenance::class.java)
        validateSchema(provenance, manifest.schema("provenance"))
        require(provenance.repository == source.repository) {
            "Case '${definition.id}' provenance repository differs from source catalog."
        }
        require(provenance.revision == source.revision) {
            "Case '${definition.id}' provenance revision differs from source catalog."
        }
        require(provenance.path == source.path) {
            "Case '${definition.id}' provenance path differs from source catalog."
        }
        require(provenance.license == source.license.spdx) {
            "Case '${definition.id}' provenance license differs from source catalog."
        }

        requiredFile(caseDir, definition.intent, "canonical intent")
        val expectedPlanFile = requiredFile(caseDir, definition.expected.executionPlan, "expected execution plan")
        val expectedPlan = RealWorldCorpusSerialization.readJson(expectedPlanFile, RealWorldExpectedPlan::class.java)
        validateSchema(expectedPlan, manifest.schema("expectedPlan"))
        validateExpectedPlan(definition.id, expectedPlan)
        require(expectedPlan.expectedOutcome == scenario.baselineExpectation) {
            "Case '${definition.id}' expected outcome differs from accepted scenario result."
        }

        val targetFile = requiredFile(caseDir, definition.expected.targetAssessments, "target assessments")
        val targetExpectations = RealWorldCorpusSerialization.readYaml(
            targetFile,
            RealWorldTargetAssessmentDocument::class.java
        )
        validateSchema(targetExpectations, manifest.schema("targetAssessments"))
        require(targetExpectations.assessments.map { it.target }.distinct().size == targetExpectations.assessments.size) {
            "Case '${definition.id}' contains duplicate target assessments."
        }
        targetExpectations.assessments.forEach { assessment ->
            require(assessment.target.isNotBlank()) { "Case '${definition.id}' has a blank target assessment." }
            require(assessment.allowedResults.isNotEmpty()) {
                "Case '${definition.id}' target '${assessment.target}' must declare allowedResults."
            }
        }

        val evidenceFile = requiredFile(caseDir, definition.evidence, "case evidence")
        val evidence = RealWorldCorpusSerialization.readYaml(evidenceFile, RealWorldEvidence::class.java)
        validateSchema(evidence, manifest.schema("evidence"))
        require(evidence.caseId == definition.id) {
            "Evidence caseId '${evidence.caseId}' must be '${definition.id}'."
        }
        require(evidence.status == RealWorldLifecycle.ACCEPTED) {
            "Case '${definition.id}' evidence must be ACCEPTED."
        }
        require(evidence.outcome == expectedPlan.expectedOutcome) {
            "Case '${definition.id}' evidence outcome differs from expected plan."
        }
        require(evidence.expectedDiagnostics.sorted() == expectedPlan.expectedDiagnostics.sorted()) {
            "Case '${definition.id}' evidence diagnostics differ from expected plan."
        }
        require(evidence.sourceRevision == source.revision) {
            "Case '${definition.id}' evidence revision differs from immutable source revision."
        }
        val caseCheck = "real-world-corpus.case.${definition.id.lowercase()}"
        require(caseCheck in evidence.validatedBy) {
            "Case '${definition.id}' evidence must cite '$caseCheck'."
        }

        val mutations = definition.mutations.map { mutationRef ->
            val mutationFile = requiredFile(caseDir, mutationRef.definition, "mutation definition")
            val mutation = RealWorldCorpusSerialization.readYaml(mutationFile, RealWorldMutationDefinition::class.java)
            validateSchema(mutation, manifest.schema("mutation"))
            require(mutation.kind == "FlowRealWorldMutation" && mutation.version == "0.2") {
                "Mutation '${mutation.id}' in case '${definition.id}' must use version 0.2."
            }
            require(mutation.id == mutationRef.id) {
                "Mutation '${mutation.id}' does not match case reference '${mutationRef.id}'."
            }
            require(mutation.expectedDiagnostics.isNotEmpty()) {
                "Mutation '${definition.id}:${mutation.id}' must expect at least one exact diagnostic."
            }
            validateExpectedPlan("${definition.id}:${mutation.id}", mutation.expectedPlan())
            requiredFile(mutationFile.parentFile, mutation.intent, "mutation intent")
            mutation to mutationFile.parentFile
        }
        require(mutations.isNotEmpty()) { "Case '${definition.id}' must contain at least one negative mutation." }
        require(RealWorldCorpusRunner.MUTATION_CHECK in evidence.validatedBy) {
            "Case '${definition.id}' evidence must cite '${RealWorldCorpusRunner.MUTATION_CHECK}'."
        }
        return LoadedRealWorldCase(
            definition,
            caseDir,
            source,
            provenance,
            expectedPlan,
            targetExpectations,
            evidence,
            mutations
        )
    }

    private fun validateExpectedPlan(caseId: String, expected: RealWorldExpectedPlan) {
        require(expected.tasks.map { it.sourceId }.distinct().size == expected.tasks.size) {
            "Expected plan '$caseId' contains duplicate task source ids."
        }
        require(expected.tasks.none { it.sourceId.isBlank() }) {
            "Expected plan '$caseId' contains a blank task source id."
        }
        require(expected.requiredRelations.distinct().size == expected.requiredRelations.size) {
            "Expected plan '$caseId' contains duplicate required relations."
        }
        expected.requiredRelations.forEach { relation ->
            require(relation.source.isNotBlank() && relation.target.isNotBlank()) {
                "Expected plan '$caseId' contains a relation with a blank endpoint."
            }
            if (relation.kind != org.flowlang.planner.PlanDependencyKind.ORDERING) {
                require(!relation.channel.isNullOrBlank()) {
                    "Expected plan '$caseId' continuity relation ${relation.source}->${relation.target} must name a channel."
                }
            }
        }
        require(expected.forbiddenOrdering.distinct().size == expected.forbiddenOrdering.size) {
            "Expected plan '$caseId' contains duplicate forbidden ordering relations."
        }
        require(expected.expectedDiagnostics.distinct().size == expected.expectedDiagnostics.size) {
            "Expected plan '$caseId' contains duplicate expected diagnostics."
        }
    }

    private fun validateSchema(value: Any, schemaPath: String) {
        val schemaFile = resolveRootFile(schemaPath, "schema")
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(value), FlowJson.readTree(schemaFile))
    }

    private fun RealWorldCorpusManifest.schema(id: String): String = schemas[id]
        ?: error("Real-world corpus manifest is missing schema '$id'.")

    private fun resolveRootFile(path: String, label: String): File {
        require(path.isNotBlank()) { "Missing $label path." }
        val resolved = File(repositoryRoot, path).canonicalFile
        require(resolved.toPath().startsWith(repositoryRoot.toPath())) {
            "$label path escapes repository root: $path"
        }
        require(resolved.isFile) { "Missing $label: ${resolved.path}" }
        return resolved
    }

    private fun resolveCorpusDirectory(path: String, label: String): File {
        require(path.isNotBlank()) { "Missing $label path." }
        val resolved = File(corpusRoot, path).canonicalFile
        require(resolved.toPath().startsWith(corpusRoot.canonicalFile.toPath())) {
            "$label path escapes corpus root: $path"
        }
        require(resolved.isDirectory) { "Missing $label: ${resolved.path}" }
        return resolved
    }

    private fun requiredFile(base: File, path: String, label: String): File {
        require(path.isNotBlank()) { "Missing $label path under ${base.path}." }
        val canonicalBase = base.canonicalFile
        val file = File(canonicalBase, path).canonicalFile
        require(file.toPath().startsWith(canonicalBase.toPath())) { "$label path escapes case package: $path" }
        require(file.isFile) { "Missing $label: ${file.path}" }
        return file
    }

    companion object {
        private const val BOOTSTRAP_MANIFEST_SCHEMA = "schemas/real-world-corpus.schema.json"
        private val REVISION_PATTERN = Regex("^[0-9a-f]{40}$")
        private val REQUIRED_CATALOG_KEYS = setOf("sources", "scenarios", "acceptedScenarios")
        private val REQUIRED_SCHEMA_KEYS = setOf(
            "manifest",
            "sources",
            "scenarios",
            "case",
            "provenance",
            "expectedPlan",
            "targetAssessments",
            "evidence",
            "mutation"
        )
        private val SOURCE_ADMISSIONS = setOf("admitted", "screened", "candidate")
        private val SCENARIO_ADMISSIONS = setOf("admitted", "screened", "planned")
        private val SCENARIO_CATEGORIES = setOf("common", "production", "advanced", "negative")
        private val CONTINUITY_VOCABULARY = setOf("value", "workspace", "state", "artifact")
        private val REQUIRED_OBSERVATION_HEADINGS = listOf(
            "## Observed behavior",
            "## Reconstructed intent",
            "## Invariants",
            "## Ambiguities"
        )
    }
}
