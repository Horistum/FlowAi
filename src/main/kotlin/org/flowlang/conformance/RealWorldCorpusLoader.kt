package org.flowlang.conformance

import java.io.File
import org.flowlang.cli.Json
import org.flowlang.serialization.FlowYaml

class RealWorldCorpusLoader(private val rootDir: File = File(".")) {
    private val corpusRoot = File(rootDir, RealWorldCorpusRunner.CORPUS_ROOT)
    private val manifestFile = File(corpusRoot, "manifest.yaml")

    fun load(): LoadedRealWorldCorpus {
        require(manifestFile.isFile) { "Missing real-world corpus manifest: ${manifestFile.path}" }
        val manifest = FlowYaml.read(manifestFile, RealWorldCorpusManifest::class.java)
        validateSchema(manifest, "real-world-corpus.schema.json")
        require(manifest.kind == "FlowRealWorldCorpus") { "Unexpected real-world corpus kind '${manifest.kind}'." }
        require(manifest.status == "executable-baseline") {
            "Real-world corpus must be executable-baseline, got '${manifest.status}'."
        }
        require(manifest.casePackages.isNotEmpty()) { "Real-world corpus must contain executable case packages." }

        val sourceFile = resolveRootPath(manifest.catalogs.getValue("sources"))
        val scenarioFile = resolveRootPath(manifest.catalogs.getValue("scenarios"))
        val sources = FlowYaml.read(sourceFile, RealWorldSourceCatalog::class.java)
        val scenarios = FlowYaml.read(scenarioFile, RealWorldScenarioCatalog::class.java)
        validateSchema(sources, "real-world-source.schema.json")

        require(sources.sources.map { it.id }.distinct().size == sources.sources.size) {
            "Real-world source ids must be unique."
        }
        require(scenarios.scenarios.map { it.id }.distinct().size == scenarios.scenarios.size) {
            "Real-world scenario ids must be unique."
        }
        sources.sources.forEach { source ->
            require(source.revision.matches(Regex("^[0-9a-f]{40}$"))) {
                "Source '${source.id}' is not pinned to an immutable 40-character revision."
            }
            require(source.repository.contains('/')) { "Source '${source.id}' has invalid repository '${source.repository}'." }
            require(source.path.isNotBlank()) { "Source '${source.id}' has no source path." }
        }

        val sourcesById = sources.sources.associateBy { it.id }
        val scenariosById = scenarios.scenarios.associateBy { it.id }
        val cases = manifest.casePackages.map { relativePath ->
            val caseDir = File(corpusRoot, relativePath)
            require(caseDir.isDirectory) { "Missing real-world case package: ${caseDir.path}" }
            val definitionFile = File(caseDir, "case.yaml")
            require(definitionFile.isFile) { "Missing case.yaml in ${caseDir.path}" }
            val definition = FlowYaml.read(definitionFile, RealWorldCaseDefinition::class.java)
            validateSchema(definition, "real-world-case.schema.json")
            require(definition.lifecycle >= RealWorldLifecycle.MUTATION_VALIDATED) {
                "Case '${definition.id}' must be at least MUTATION_VALIDATED, got ${definition.lifecycle}."
            }
            require(definition.invariants.isNotEmpty()) { "Case '${definition.id}' must declare invariants." }
            val scenario = scenariosById[definition.id]
                ?: error("Case '${definition.id}' is absent from scenarios.yaml.")
            val source = sourcesById[definition.sourceRef]
                ?: error("Case '${definition.id}' references unknown source '${definition.sourceRef}'.")
            require(definition.sourceRef in scenario.sourceRefs) {
                "Case '${definition.id}' source '${definition.sourceRef}' is absent from scenario sourceRefs."
            }

            val workflowFile = requiredFile(caseDir, definition.source.workflow, "source workflow")
            val provenanceFile = requiredFile(caseDir, definition.source.provenance, "source provenance")
            val licenseFile = requiredFile(caseDir, definition.source.license, "source license")
            val observationsFile = requiredFile(caseDir, definition.sourceObservations, "source observations")
            require(workflowFile.readText().isNotBlank()) { "Case '${definition.id}' source workflow is empty." }
            require(licenseFile.readText().contains("SPDX-License-Identifier")) {
                "Case '${definition.id}' source license must contain an SPDX identifier."
            }
            val observations = observationsFile.readText()
            listOf("## Observed behavior", "## Reconstructed intent", "## Invariants", "## Ambiguities").forEach { heading ->
                require(heading in observations) { "Case '${definition.id}' observations are missing '$heading'." }
            }

            val provenance = FlowYaml.read(provenanceFile, RealWorldCaseProvenance::class.java)
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
            val targetFile = requiredFile(caseDir, definition.expected.targetAssessments, "target assessments")
            val evidenceFile = requiredFile(caseDir, definition.evidence, "case evidence")
            val expectedPlan = Json.mapper.readValue(expectedPlanFile, RealWorldExpectedPlan::class.java)
            val targetExpectations = FlowYaml.read(targetFile, RealWorldTargetAssessmentDocument::class.java)
            val evidence = FlowYaml.read(evidenceFile, RealWorldEvidence::class.java)
            require(evidence.caseId == definition.id) { "Evidence caseId '${evidence.caseId}' must be '${definition.id}'." }
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

            val mutations = definition.mutations.map { mutationRef ->
                val mutationFile = requiredFile(caseDir, mutationRef.definition, "mutation definition")
                val mutation = FlowYaml.read(mutationFile, RealWorldMutationDefinition::class.java)
                require(mutation.id == mutationRef.id) {
                    "Mutation '${mutation.id}' does not match case reference '${mutationRef.id}'."
                }
                requiredFile(mutationFile.parentFile, mutation.intent, "mutation intent")
                mutation to mutationFile.parentFile
            }
            require(mutations.isNotEmpty()) { "Case '${definition.id}' must contain at least one negative mutation." }

            LoadedRealWorldCase(
                definition = definition,
                directory = caseDir,
                source = source,
                provenance = provenance,
                expectedPlan = expectedPlan,
                targetExpectations = targetExpectations,
                evidence = evidence,
                mutations = mutations
            )
        }

        require(cases.map { it.definition.id }.distinct().size == cases.size) {
            "Real-world case ids must be unique."
        }
        require(manifest.counts.executableCases == cases.size) {
            "Manifest executableCases=${manifest.counts.executableCases}, actual=${cases.size}."
        }
        val mutationCount = cases.sumOf { it.mutations.size }
        require(manifest.counts.mutationCases == mutationCount) {
            "Manifest mutationCases=${manifest.counts.mutationCases}, actual=$mutationCount."
        }
        require(manifest.counts.sources == sources.sources.size) {
            "Manifest sources=${manifest.counts.sources}, actual=${sources.sources.size}."
        }
        require(manifest.counts.scenarios == scenarios.scenarios.size) {
            "Manifest scenarios=${manifest.counts.scenarios}, actual=${scenarios.scenarios.size}."
        }
        return LoadedRealWorldCorpus(manifest, sources, scenarios, cases)
    }

    private fun validateSchema(value: Any, schemaName: String) {
        val schemaFile = File(rootDir, "schemas/$schemaName")
        require(schemaFile.isFile) { "Missing real-world corpus schema: ${schemaFile.path}" }
        JsonSchemaSmokeValidator.validate(
            Json.mapper.valueToTree(value),
            Json.mapper.readTree(schemaFile)
        )
    }

    private fun resolveRootPath(path: String): File =
        File(rootDir, path).canonicalFile.also { resolved ->
            require(resolved.path.startsWith(rootDir.canonicalFile.path)) {
                "Corpus path escapes repository root: $path"
            }
            require(resolved.isFile) { "Missing corpus catalog: ${resolved.path}" }
        }

    private fun requiredFile(base: File, path: String, label: String): File {
        require(path.isNotBlank()) { "Missing $label path under ${base.path}." }
        val file = File(base, path).canonicalFile
        require(file.path.startsWith(base.canonicalFile.path)) { "$label path escapes case package: $path" }
        require(file.isFile) { "Missing $label: ${file.path}" }
        return file
    }
}
