package org.flowlang.conformance

import org.flowlang.serialization.FlowJson

import java.io.File
import org.flowlang.cli.Json

/**
 * Loads C1.0 operational evidence without mutating the closed C0.1 corpus.
 *
 * Case execution deliberately reuses the existing production-path evaluator,
 * while corpus identity, domain vocabulary and evidence namespace remain C1.0-owned.
 */
class OperationalDomainCorpusLoader(private val rootDir: File = File(".")) {
    private val repositoryRoot = rootDir.canonicalFile
    private val corpusRoot = File(repositoryRoot, CORPUS_ROOT).canonicalFile
    private val manifestFile = File(corpusRoot, "manifest.yaml")

    fun load(): LoadedOperationalDomainCorpus {
        require(manifestFile.isFile) { "Missing C1.0 operational corpus manifest: ${manifestFile.path}" }
        val manifest = RealWorldCorpusSerialization.readYaml(manifestFile, OperationalDomainCorpusManifest::class.java)
        validateSchema(manifest, MANIFEST_SCHEMA)
        validateManifest(manifest)

        val sourcesById = manifest.sources.associateBy { it.id }
        val cases = manifest.casePackages.map { relativePath ->
            loadCase(resolveCorpusDirectory(relativePath, "case package"), sourcesById, manifest)
        }
        require(cases.map { it.definition.id }.distinct().size == cases.size) {
            "C1.0 operational case ids must be unique."
        }
        require(cases.map { it.directory.canonicalPath }.distinct().size == cases.size) {
            "C1.0 operational case package paths must resolve uniquely."
        }
        require(manifest.counts.executableCases == cases.size) {
            "C1.0 manifest executableCases=${manifest.counts.executableCases}, actual=${cases.size}."
        }
        val mutationCount = cases.sumOf { it.mutations.size }
        require(manifest.counts.mutationCases == mutationCount) {
            "C1.0 manifest mutationCases=${manifest.counts.mutationCases}, actual=$mutationCount."
        }
        return LoadedOperationalDomainCorpus(manifest, cases)
    }

    private fun validateManifest(manifest: OperationalDomainCorpusManifest) {
        require(manifest.kind == "FlowOperationalDomainCorpus") {
            "Unexpected C1.0 operational corpus kind '${manifest.kind}'."
        }
        require(manifest.version == VERSION && manifest.status == "executable-baseline") {
            "C1.0 operational corpus must be version $VERSION with executable-baseline status."
        }
        val expectedDomains = OperationalEvidenceDomain.entries.map { it.documentValue }
        require(manifest.scope.domains == expectedDomains) {
            "C1.0 operational domains must be exactly $expectedDomains, got ${manifest.scope.domains}."
        }
        require(manifest.scope.requiredCapabilities == REQUIRED_CAPABILITIES) {
            "C1.0 required capabilities must be exactly $REQUIRED_CAPABILITIES, got ${manifest.scope.requiredCapabilities}."
        }
        require(manifest.scope.predecessor == "AR0.1") {
            "C1.0 operational evidence must declare AR0.1 as its predecessor boundary."
        }
        require(manifest.scope.primaryUse.isNotBlank()) { "C1.0 operational corpus must declare primaryUse." }
        require(manifest.casePackages.isNotEmpty() && manifest.casePackages.distinct().size == manifest.casePackages.size) {
            "C1.0 operational case package paths must be non-empty and unique."
        }
        require(manifest.schemas.keys == REQUIRED_SCHEMA_KEYS) {
            "C1.0 schema keys must be exactly ${REQUIRED_SCHEMA_KEYS.sorted()}, got ${manifest.schemas.keys.sorted()}."
        }
        manifest.schemas.forEach { (id, path) ->
            require(resolveRootFile(path, "schema '$id'").extension == "json") {
                "C1.0 schema '$id' must be JSON: $path"
            }
        }
        require(manifest.sources.map { it.id }.distinct().size == manifest.sources.size) {
            "C1.0 operational source ids must be unique."
        }
        manifest.sources.forEach(::validateSource)
        require(manifest.counts.sources == manifest.sources.size) {
            "C1.0 manifest sources=${manifest.counts.sources}, actual=${manifest.sources.size}."
        }
    }

    private fun validateSource(source: RealWorldSourceRecord) {
        require(source.id.isNotBlank()) { "C1.0 source id must not be blank." }
        require(source.admission == "admitted") { "C1.0 source '${source.id}' must be admitted." }
        require(source.revision.matches(REVISION_PATTERN)) {
            "C1.0 source '${source.id}' must use an immutable 40-character revision."
        }
        require(source.repository.count { it == '/' } == 1) {
            "C1.0 source '${source.id}' has invalid repository '${source.repository}'."
        }
        require(source.path.isNotBlank()) { "C1.0 source '${source.id}' has no source path." }
        require(source.classifiedKind() != null) {
            "C1.0 source '${source.id}' has unsupported source kind '${source.sourceKind}'."
        }
        require(source.license.status == "verified" && !source.license.spdx.isNullOrBlank()) {
            "C1.0 source '${source.id}' requires verified SPDX license evidence."
        }
        require(source.license.evidence.isNotBlank()) {
            "C1.0 source '${source.id}' requires license evidence."
        }
    }

    private fun loadCase(
        caseDir: File,
        sourcesById: Map<String, RealWorldSourceRecord>,
        manifest: OperationalDomainCorpusManifest
    ): LoadedRealWorldCase {
        val definitionFile = File(caseDir, "case.yaml")
        require(definitionFile.isFile) { "Missing C1.0 case definition: ${definitionFile.path}" }
        val operational = RealWorldCorpusSerialization.readYaml(definitionFile, OperationalDomainCaseDefinition::class.java)
        validateSchema(operational, manifest.schema("case"))
        require(operational.kind == "FlowOperationalDomainCase" && operational.version == VERSION) {
            "C1.0 case '${operational.id}' must use FlowOperationalDomainCase version $VERSION."
        }
        require(operational.lifecycle in setOf(RealWorldLifecycle.MUTATION_VALIDATED, RealWorldLifecycle.ACCEPTED)) {
            "C1.0 case '${operational.id}' must be mutation-validated before admission."
        }
        require(OperationalEvidenceDomain.fromDocument(operational.domain) != null) {
            "C1.0 case '${operational.id}' declares unknown domain '${operational.domain}'."
        }
        require(operational.invariants.isNotEmpty() && operational.invariants.none(String::isBlank)) {
            "C1.0 case '${operational.id}' must declare non-blank invariants."
        }

        val source = sourcesById[operational.sourceRef]
            ?: error("C1.0 case '${operational.id}' references unknown source '${operational.sourceRef}'.")
        val sourceExcerpt = requiredFile(caseDir, operational.source.workflow, "source excerpt")
        val provenanceFile = requiredFile(caseDir, operational.source.provenance, "source provenance")
        val licenseFile = requiredFile(caseDir, operational.source.license, "source license")
        val observationsFile = requiredFile(caseDir, operational.sourceObservations, "source observations")
        require(sourceExcerpt.readText().isNotBlank()) { "C1.0 case '${operational.id}' source excerpt is empty." }
        val spdx = requireNotNull(source.license.spdx)
        require(licenseFile.readText().contains("SPDX-License-Identifier: $spdx")) {
            "C1.0 case '${operational.id}' source license must cite SPDX identifier '$spdx'."
        }
        val observations = observationsFile.readText()
        REQUIRED_OBSERVATION_HEADINGS.forEach { heading ->
            require(heading in observations) { "C1.0 case '${operational.id}' observations are missing '$heading'." }
        }

        val provenance = RealWorldCorpusSerialization.readYaml(provenanceFile, RealWorldCaseProvenance::class.java)
        validateSchema(provenance, manifest.schema("provenance"))
        require(provenance.repository == source.repository && provenance.revision == source.revision &&
            provenance.path == source.path && provenance.license == spdx
        ) { "C1.0 case '${operational.id}' provenance must exactly match its admitted source record." }

        requiredFile(caseDir, operational.intent, "canonical intent")
        val expectedPlanFile = requiredFile(caseDir, operational.expected.executionPlan, "expected execution plan")
        val expectedPlan = RealWorldCorpusSerialization.readJson(expectedPlanFile, RealWorldExpectedPlan::class.java)
        validateSchema(expectedPlan, manifest.schema("expectedPlan"))
        validateExpectedPlan(operational.id, expectedPlan)

        val targetFile = requiredFile(caseDir, operational.expected.targetAssessments, "target assessments")
        val targetExpectations = RealWorldCorpusSerialization.readYaml(
            targetFile,
            RealWorldTargetAssessmentDocument::class.java
        )
        validateSchema(targetExpectations, manifest.schema("targetAssessments"))
        require(targetExpectations.assessments.map { it.target }.distinct().size == targetExpectations.assessments.size) {
            "C1.0 case '${operational.id}' contains duplicate target assessments."
        }

        val evidenceFile = requiredFile(caseDir, operational.evidence, "case evidence")
        val evidence = RealWorldCorpusSerialization.readYaml(evidenceFile, RealWorldEvidence::class.java)
        validateSchema(evidence, manifest.schema("evidence"))
        require(evidence.caseId == operational.id && evidence.status == RealWorldLifecycle.ACCEPTED) {
            "C1.0 case '${operational.id}' requires matching ACCEPTED evidence."
        }
        require(evidence.outcome == expectedPlan.expectedOutcome &&
            evidence.expectedDiagnostics.sorted() == expectedPlan.expectedDiagnostics.sorted()
        ) { "C1.0 case '${operational.id}' evidence must match its expected plan outcome and diagnostics." }
        require(evidence.sourceRevision == source.revision) {
            "C1.0 case '${operational.id}' evidence revision differs from immutable source revision."
        }
        val caseCheck = "$CASE_CHECK_PREFIX.${operational.id.lowercase()}"
        require(caseCheck in evidence.validatedBy) {
            "C1.0 case '${operational.id}' evidence must cite '$caseCheck'."
        }
        require(MUTATION_CHECK in evidence.validatedBy) {
            "C1.0 case '${operational.id}' evidence must cite '$MUTATION_CHECK'."
        }

        val mutations = operational.mutations.map { mutationRef ->
            val mutationFile = requiredFile(caseDir, mutationRef.definition, "mutation definition")
            val mutation = RealWorldCorpusSerialization.readYaml(mutationFile, RealWorldMutationDefinition::class.java)
            validateSchema(mutation, manifest.schema("mutation"))
            require(mutation.kind == "FlowRealWorldMutation" && mutation.version == "0.2") {
                "C1.0 mutation '${mutation.id}' must use the shared strict mutation contract version 0.2."
            }
            require(mutation.id == mutationRef.id) {
                "C1.0 mutation '${mutation.id}' does not match case reference '${mutationRef.id}'."
            }
            require(mutation.expectedDiagnostics.isNotEmpty()) {
                "C1.0 mutation '${operational.id}:${mutation.id}' must expect at least one exact diagnostic."
            }
            validateExpectedPlan("${operational.id}:${mutation.id}", mutation.expectedPlan())
            requiredFile(mutationFile.parentFile, mutation.intent, "mutation intent")
            mutation to mutationFile.parentFile
        }
        require(mutations.isNotEmpty()) { "C1.0 case '${operational.id}' must contain at least one negative mutation." }

        return LoadedRealWorldCase(
            definition = operational.asEvaluationDefinition(),
            directory = caseDir,
            source = source,
            provenance = provenance,
            expectedPlan = expectedPlan,
            targetExpectations = targetExpectations,
            evidence = evidence,
            mutations = mutations
        )
    }

    private fun validateExpectedPlan(caseId: String, expected: RealWorldExpectedPlan) {
        require(expected.tasks.map { it.sourceId }.distinct().size == expected.tasks.size) {
            "C1.0 expected plan '$caseId' contains duplicate task source ids."
        }
        require(expected.tasks.none { it.sourceId.isBlank() }) {
            "C1.0 expected plan '$caseId' contains a blank task source id."
        }
        require(expected.requiredRelations.distinct().size == expected.requiredRelations.size) {
            "C1.0 expected plan '$caseId' contains duplicate required relations."
        }
        expected.requiredRelations.forEach { relation ->
            require(relation.source.isNotBlank() && relation.target.isNotBlank()) {
                "C1.0 expected plan '$caseId' contains a relation with a blank endpoint."
            }
            if (relation.kind != org.flowlang.planner.PlanDependencyKind.ORDERING) {
                require(!relation.channel.isNullOrBlank()) {
                    "C1.0 expected plan '$caseId' continuity relation must name a channel."
                }
            }
        }
        require(expected.expectedDiagnostics.distinct().size == expected.expectedDiagnostics.size) {
            "C1.0 expected plan '$caseId' contains duplicate diagnostics."
        }
    }

    private fun validateSchema(value: Any, schemaPath: String) {
        val schemaFile = resolveRootFile(schemaPath, "schema")
        JsonSchemaSmokeValidator.validate(Json.mapper.valueToTree(value), FlowJson.readTree(schemaFile))
    }

    private fun OperationalDomainCorpusManifest.schema(id: String): String =
        schemas[id] ?: error("C1.0 operational corpus manifest is missing schema '$id'.")

    private fun resolveRootFile(path: String, label: String): File {
        require(path.isNotBlank()) { "Missing C1.0 $label path." }
        val resolved = File(repositoryRoot, path).canonicalFile
        require(resolved.toPath().startsWith(repositoryRoot.toPath())) { "$label path escapes repository root: $path" }
        require(resolved.isFile) { "Missing C1.0 $label: ${resolved.path}" }
        return resolved
    }

    private fun resolveCorpusDirectory(path: String, label: String): File {
        require(path.isNotBlank()) { "Missing C1.0 $label path." }
        val resolved = File(corpusRoot, path).canonicalFile
        require(resolved.toPath().startsWith(corpusRoot.toPath())) { "$label path escapes C1.0 corpus root: $path" }
        require(resolved.isDirectory) { "Missing C1.0 $label: ${resolved.path}" }
        return resolved
    }

    private fun requiredFile(base: File, path: String, label: String): File {
        require(path.isNotBlank()) { "Missing C1.0 $label path under ${base.path}." }
        val canonicalBase = base.canonicalFile
        val file = File(canonicalBase, path).canonicalFile
        require(file.toPath().startsWith(canonicalBase.toPath())) { "$label path escapes C1.0 case package: $path" }
        require(file.isFile) { "Missing C1.0 $label: ${file.path}" }
        return file
    }

    companion object {
        const val CORPUS_ROOT = "conformance/corpus/operational"
        const val VERSION = "1.0"
        const val CASE_CHECK_PREFIX = "operational-domain-corpus.case"
        const val MUTATION_CHECK = "operational-domain-corpus.mutations"
        const val DOMAIN_POLARITY_CHECK = "operational-domain-corpus.mutation-polarity"
        const val MANIFEST_SCHEMA = "schemas/operational-domain-corpus.schema.json"
        val REQUIRED_CAPABILITIES = listOf("BACKUP", "RESTORE")
        private val REVISION_PATTERN = Regex("^[0-9a-f]{40}$")
        private val REQUIRED_SCHEMA_KEYS = setOf(
            "manifest", "case", "provenance", "expectedPlan", "targetAssessments", "evidence", "mutation"
        )
        private val REQUIRED_OBSERVATION_HEADINGS = listOf(
            "# Source observations",
            "## Observed behavior",
            "## Reconstructed intent",
            "## Invariants",
            "## Ambiguities"
        )
    }
}
