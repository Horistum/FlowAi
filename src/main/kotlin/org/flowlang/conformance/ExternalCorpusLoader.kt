package org.flowlang.conformance

import java.io.File
import java.security.MessageDigest

/**
 * Strict EF-01 ingestion boundary for external automation evidence.
 *
 * The loader preserves upstream provenance and captured source bytes, but it never
 * derives universal Flow meaning from implementation vocabulary. Semantic expectations
 * are explicit review evidence and must reference observed authored behavior.
 */
class ExternalCorpusLoader(private val rootDir: File = File(".")) {
    private val repositoryRoot = rootDir.canonicalFile
    private val corpusRoot = File(repositoryRoot, CORPUS_ROOT).canonicalFile
    private val manifestFile = File(corpusRoot, "manifest.yaml")

    fun load(): LoadedExternalCorpus {
        require(manifestFile.isFile) { "Missing EF-01 external corpus manifest: ${manifestFile.path}" }
        val manifest = RealWorldCorpusSerialization.readYaml(manifestFile, ExternalCorpusManifest::class.java)
        validateManifest(manifest)

        val cases = manifest.casePackages.map { relativePath ->
            loadCase(resolveWithin(corpusRoot, relativePath, "case package"))
        }
        require(cases.map { it.definition.id }.distinct().size == cases.size) {
            "EF-01 external corpus case ids must be unique."
        }
        require(cases.map { it.directory.canonicalPath }.distinct().size == cases.size) {
            "EF-01 external corpus case package paths must resolve uniquely."
        }
        if (manifest.status == ExternalCorpusStatus.EVIDENCE_ACTIVE) {
            require(cases.isNotEmpty()) { "An EVIDENCE_ACTIVE external corpus must contain at least one case." }
        }
        return LoadedExternalCorpus(manifest, cases)
    }

    private fun validateManifest(manifest: ExternalCorpusManifest) {
        require(manifest.kind == KIND) { "Unexpected external corpus kind '${manifest.kind}'." }
        require(manifest.version == VERSION) {
            "External corpus manifest must use version $VERSION, got '${manifest.version}'."
        }
        require(manifest.casePackages.distinct().size == manifest.casePackages.size) {
            "External corpus case package paths must be unique."
        }
        require(manifest.casePackages.none(String::isBlank)) {
            "External corpus case package paths must not be blank."
        }
        require(manifest.invariants.isNotEmpty() && manifest.invariants.none(String::isBlank)) {
            "External corpus manifest must declare non-blank invariants."
        }
    }

    private fun loadCase(caseDir: File): LoadedExternalCorpusCase {
        require(caseDir.isDirectory) { "External corpus case package is missing: ${caseDir.path}" }
        val caseFile = File(caseDir, "case.yaml")
        require(caseFile.isFile) { "External corpus case definition is missing: ${caseFile.path}" }
        val definition = RealWorldCorpusSerialization.readYaml(caseFile, ExternalCorpusCase::class.java)

        require(definition.kind == CASE_KIND && definition.version == VERSION) {
            "External corpus case '${definition.id}' must use $CASE_KIND version $VERSION."
        }
        require(ID_PATTERN.matches(definition.id)) {
            "External corpus case id '${definition.id}' must match ${ID_PATTERN.pattern}."
        }
        require(definition.domain.isNotBlank()) { "External corpus case '${definition.id}' must declare a domain." }
        validateProvenance(definition.id, definition.provenance)

        val sourceFile = resolveWithin(caseDir, definition.sourceCapture.path, "captured source")
        require(sourceFile.isFile) {
            "External corpus case '${definition.id}' captured source is missing: ${sourceFile.path}"
        }
        require(SHA256_PATTERN.matches(definition.sourceCapture.sha256)) {
            "External corpus case '${definition.id}' sourceCapture.sha256 must be a lowercase SHA-256 digest."
        }
        val actualDigest = sha256(sourceFile)
        require(actualDigest == definition.sourceCapture.sha256) {
            "External corpus case '${definition.id}' captured source digest mismatch: expected=${definition.sourceCapture.sha256} actual=$actualDigest."
        }

        validateBehaviors(definition)
        validateSemanticObservations(definition)
        validateUnsupportedFacts(definition)
        return LoadedExternalCorpusCase(definition, caseDir, sourceFile)
    }

    private fun validateProvenance(caseId: String, provenance: ExternalSourceProvenance) {
        require(REPOSITORY_PATTERN.matches(provenance.repository)) {
            "External corpus case '$caseId' repository '${provenance.repository}' must be owner/name."
        }
        require(REVISION_PATTERN.matches(provenance.revision)) {
            "External corpus case '$caseId' must pin an immutable lowercase 40-character Git revision."
        }
        require(provenance.path.isNotBlank() && !File(provenance.path).isAbsolute) {
            "External corpus case '$caseId' provenance path must be non-blank and repository-relative."
        }
        require(provenance.license.spdx.isNotBlank()) {
            "External corpus case '$caseId' must declare SPDX license identity."
        }
        require(provenance.license.evidence.isNotBlank()) {
            "External corpus case '$caseId' must declare license evidence."
        }
    }

    private fun validateBehaviors(definition: ExternalCorpusCase) {
        require(definition.authoredBehaviors.isNotEmpty()) {
            "External corpus case '${definition.id}' must record at least one authored behavior."
        }
        requireUniqueIds(definition.id, "authored behavior", definition.authoredBehaviors.map { it.id })
        definition.authoredBehaviors.forEach { behavior ->
            require(behavior.statement.isNotBlank()) {
                "External corpus case '${definition.id}' authored behavior '${behavior.id}' has a blank statement."
            }
            require(behavior.evidence.isNotEmpty() && behavior.evidence.none(String::isBlank)) {
                "External corpus case '${definition.id}' authored behavior '${behavior.id}' requires explicit source evidence."
            }
        }
    }

    private fun validateSemanticObservations(definition: ExternalCorpusCase) {
        require(definition.expectedSemanticObservations.isNotEmpty()) {
            "External corpus case '${definition.id}' must record at least one expected semantic observation."
        }
        requireUniqueIds(
            definition.id,
            "semantic observation",
            definition.expectedSemanticObservations.map { it.id }
        )
        val behaviorIds = definition.authoredBehaviors.map { it.id }.toSet()
        definition.expectedSemanticObservations.forEach { observation ->
            require(observation.statement.isNotBlank() && observation.rationale.isNotBlank()) {
                "External corpus case '${definition.id}' semantic observation '${observation.id}' requires statement and rationale."
            }
            require(observation.behaviorRefs.isNotEmpty() && observation.behaviorRefs.distinct().size == observation.behaviorRefs.size) {
                "External corpus case '${definition.id}' semantic observation '${observation.id}' requires unique authored-behavior references."
            }
            val unknown = observation.behaviorRefs.filter { it !in behaviorIds }
            require(unknown.isEmpty()) {
                "External corpus case '${definition.id}' semantic observation '${observation.id}' references unknown authored behavior ids ${unknown.sorted()}."
            }
        }
    }

    private fun validateUnsupportedFacts(definition: ExternalCorpusCase) {
        requireUniqueIds(definition.id, "unsupported fact", definition.unsupportedFacts.map { it.id })
        definition.unsupportedFacts.forEach { fact ->
            require(fact.statement.isNotBlank() && fact.reason.isNotBlank()) {
                "External corpus case '${definition.id}' unsupported fact '${fact.id}' requires statement and reason."
            }
            require(fact.evidence.isNotEmpty() && fact.evidence.none(String::isBlank)) {
                "External corpus case '${definition.id}' unsupported fact '${fact.id}' requires explicit source evidence."
            }
        }
    }

    private fun requireUniqueIds(caseId: String, label: String, ids: List<String>) {
        require(ids.none { !ID_PATTERN.matches(it) }) {
            "External corpus case '$caseId' contains an invalid $label id."
        }
        require(ids.distinct().size == ids.size) {
            "External corpus case '$caseId' contains duplicate $label ids."
        }
    }

    private fun resolveWithin(base: File, relativePath: String, label: String): File {
        require(relativePath.isNotBlank()) { "External corpus $label path must not be blank." }
        require(!File(relativePath).isAbsolute) { "External corpus $label path must be relative: $relativePath" }
        val resolved = File(base, relativePath).canonicalFile
        require(resolved.toPath().startsWith(base.canonicalFile.toPath())) {
            "External corpus $label escapes its evidence root: $relativePath"
        }
        return resolved
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        const val CORPUS_ROOT = "conformance/corpus/external"
        const val KIND = "FlowExternalCorpus"
        const val CASE_KIND = "FlowExternalCorpusCase"
        const val VERSION = "1.0"
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        private val REPOSITORY_PATTERN = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
        private val REVISION_PATTERN = Regex("[0-9a-f]{40}")
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

class ExternalCorpusFoundationConformanceRunner(private val rootDir: File = File(".")) {
    fun checks(): List<ConformanceCheck> {
        val result = runCatching { ExternalCorpusLoader(rootDir).load() }
        val corpus = result.getOrNull()
        val passed = corpus != null
        val message = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        return listOf(ConformanceCheck(CHECK, passed, message))
    }

    companion object {
        const val CHECK = "conformance.ef-01.external-corpus-foundation"
    }
}
