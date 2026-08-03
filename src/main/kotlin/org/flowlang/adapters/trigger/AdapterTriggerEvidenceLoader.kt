package org.flowlang.adapters.trigger

import java.io.File
import org.flowlang.serialization.FlowYaml

/**
 * Loads adapter-owned trigger evidence from one strict index and one target file
 * per active evidence record.
 *
 * The index is authoritative only when it describes the complete target
 * directory. Missing, unindexed, duplicated, nested or path-escaping files fail
 * before any claim is accepted as implementation evidence.
 */
object AdapterTriggerEvidenceLoader {
    const val PATH = "adapters/triggers/builtin-trigger-materialization.yaml"
    const val TARGET_DIRECTORY = "adapters/triggers/targets"
    const val SUPPORTED_VERSION = "1.0"

    fun load(rootDir: File = File(".")): AdapterTriggerEvidenceDocument {
        val repositoryRoot = rootDir.canonicalFile
        require(repositoryRoot.isDirectory) {
            "Adapter trigger evidence root is not a directory: ${repositoryRoot.path}"
        }

        val indexFile = requireRepositoryFile(repositoryRoot, PATH, "Adapter trigger evidence index")
        val index = FlowYaml.readMap(indexFile)
        requireExactKeys(index, INDEX_KEYS, PATH)

        val version = text(index, "version", PATH)
        require(version == SUPPORTED_VERSION) {
            "$PATH.version '$version' is unsupported; expected '$SUPPORTED_VERSION'."
        }

        val indexedPaths = stringList(index["targetFiles"], "$PATH.targetFiles", required = true)
        val targetDirectory = requireTargetDirectory(repositoryRoot)
        val indexedFiles = indexedPaths.mapIndexed { indexPosition, repositoryPath ->
            resolveIndexedTargetFile(
                repositoryRoot = repositoryRoot,
                targetDirectory = targetDirectory,
                repositoryPath = repositoryPath,
                path = "$PATH.targetFiles[$indexPosition]"
            )
        }

        require(indexedFiles.map(File::getCanonicalPath).toSet().size == indexedFiles.size) {
            "$PATH.targetFiles resolves more than one entry to the same target file."
        }
        requireCompleteDirectoryIndex(repositoryRoot, targetDirectory, indexedFiles)

        val targets = indexedFiles.map { file -> parseTargetFile(repositoryRoot, file) }
        val duplicateTargets = targets.groupingBy(AdapterTriggerTargetRecord::target)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
            .sorted()
        require(duplicateTargets.isEmpty()) {
            "$PATH target files declare duplicate target identities: ${duplicateTargets.joinToString()}."
        }

        return AdapterTriggerEvidenceDocument(version = version, targets = targets)
    }

    private fun requireTargetDirectory(repositoryRoot: File): File {
        val directory = File(repositoryRoot, TARGET_DIRECTORY).canonicalFile
        require(directory.isDirectory) {
            "Adapter trigger evidence target directory is missing: ${directory.path}"
        }
        require(directory.parentFile?.canonicalFile == File(repositoryRoot, "adapters/triggers").canonicalFile) {
            "Adapter trigger evidence target directory escaped its declared repository boundary: ${directory.path}"
        }
        return directory
    }

    private fun resolveIndexedTargetFile(
        repositoryRoot: File,
        targetDirectory: File,
        repositoryPath: String,
        path: String
    ): File {
        require('\\' !in repositoryPath) {
            "$path must use repository-style '/' separators."
        }
        require(!File(repositoryPath).isAbsolute) {
            "$path must be a repository-relative path."
        }
        val normalized = File(repositoryPath).toPath().normalize().toString().replace(File.separatorChar, '/')
        require(normalized == repositoryPath && repositoryPath.startsWith("$TARGET_DIRECTORY/")) {
            "$path '$repositoryPath' is outside the strict adapter trigger target directory."
        }
        require(TARGET_FILE_NAME.matches(repositoryPath.substringAfterLast('/'))) {
            "$path '$repositoryPath' must name a lower-case kebab-case .yaml target file."
        }

        val file = requireRepositoryFile(repositoryRoot, repositoryPath, "Indexed adapter trigger target")
        require(file.parentFile?.canonicalFile == targetDirectory) {
            "$path '$repositoryPath' must point directly inside '$TARGET_DIRECTORY'."
        }
        return file
    }

    private fun requireCompleteDirectoryIndex(
        repositoryRoot: File,
        targetDirectory: File,
        indexedFiles: List<File>
    ) {
        val indexedPaths = indexedFiles.map { file -> repositoryPath(repositoryRoot, file) }.toSet()
        val discoveredPaths = targetDirectory.walkTopDown()
            .filter { file -> file.isFile && file.extension == "yaml" }
            .map { file -> repositoryPath(repositoryRoot, file.canonicalFile) }
            .toSet()

        val missing = indexedPaths - discoveredPaths
        val unindexed = discoveredPaths - indexedPaths
        require(missing.isEmpty() && unindexed.isEmpty()) {
            buildString {
                append("$PATH.targetFiles must exactly inventory '$TARGET_DIRECTORY'.")
                if (missing.isNotEmpty()) append(" Missing files: ${missing.sorted().joinToString()}.")
                if (unindexed.isNotEmpty()) append(" Unindexed files: ${unindexed.sorted().joinToString()}.")
            }
        }
    }

    private fun parseTargetFile(repositoryRoot: File, file: File): AdapterTriggerTargetRecord {
        val repositoryPath = repositoryPath(repositoryRoot, file)
        val raw = FlowYaml.readMap(file)
        requireExactKeys(raw, TARGET_KEYS, repositoryPath)
        val target = text(raw, "target", repositoryPath)
        val expectedTarget = file.nameWithoutExtension
        require(target == expectedTarget) {
            "$repositoryPath.target '$target' must match file identity '$expectedTarget'."
        }
        val claims = objectList(raw["claims"], "$repositoryPath.claims").mapIndexed { index, claim ->
            parseClaim(claim, "$repositoryPath.claims[$index]")
        }
        require(claims.isNotEmpty()) { "$repositoryPath.claims must not be empty." }
        return AdapterTriggerTargetRecord(target = target, claims = claims)
    }

    private fun parseClaim(raw: Map<String, Any?>, path: String): AdapterTriggerClaim {
        val claim = expandClaimTemplate(raw, path)
        requireExactKeys(claim, CLAIM_KEYS, path)
        val semantics = map(claim["semantics"], "$path.semantics")
        requireExactKeys(semantics, SEMANTIC_KEYS, "$path.semantics")
        val constraints = map(claim["constraints"], "$path.constraints")
        requireExactKeys(constraints, CONSTRAINT_KEYS, "$path.constraints")
        return AdapterTriggerClaim(
            family = enumValue(text(claim, "family", path), "$path.family"),
            status = enumValue(text(claim, "status", path), "$path.status"),
            mechanism = text(claim, "mechanism", path),
            semantics = AdapterTriggerSemanticPartition(
                supported = stringList(semantics["supported"], "$path.semantics.supported").toSet(),
                unsupported = reasonMap(semantics["unsupported"], "$path.semantics.unsupported"),
                unknown = reasonMap(semantics["unknown"], "$path.semantics.unknown")
            ),
            constraints = AdapterTriggerClaimConstraints(
                workflowScopes = stringList(
                    constraints["workflowScopes"],
                    "$path.constraints.workflowScopes"
                ).toSet(),
                eventNames = stringList(constraints["eventNames"], "$path.constraints.eventNames").toSet(),
                parameterNames = stringList(
                    constraints["parameterNames"],
                    "$path.constraints.parameterNames"
                ).toSet(),
                timezoneMode = enumValue(
                    text(constraints, "timezoneMode", "$path.constraints"),
                    "$path.constraints.timezoneMode"
                ),
                expressionMode = enumValue(
                    text(constraints, "expressionMode", "$path.constraints"),
                    "$path.constraints.expressionMode"
                )
            ),
            evidenceReferences = stringList(
                claim["evidenceReferences"],
                "$path.evidenceReferences",
                required = true
            ),
            prerequisites = stringList(claim["prerequisites"], "$path.prerequisites"),
            limitations = stringList(claim["limitations"], "$path.limitations", required = true)
        )
    }

    /**
     * Jackson exposes the bounded YAML claim template as a literal `<<` map.
     * Resolve exactly one map template before strict key validation. Explicit
     * claim fields override the template; nested or sequence merges are rejected.
     */
    private fun expandClaimTemplate(raw: Map<String, Any?>, path: String): Map<String, Any?> {
        val inheritedRaw = raw[MERGE_KEY] ?: return raw
        val inherited = map(inheritedRaw, "$path.$MERGE_KEY")
        require(MERGE_KEY !in inherited) {
            "$path.$MERGE_KEY must not contain a nested claim template."
        }
        requireExactKeys(inherited, CLAIM_KEYS, "$path.$MERGE_KEY")
        val explicit = raw - MERGE_KEY
        val unknownExplicit = explicit.keys - CLAIM_KEYS
        require(unknownExplicit.isEmpty()) {
            "$path has unknown explicit fields: ${unknownExplicit.sorted().joinToString()}."
        }
        return inherited + explicit
    }

    private fun requireRepositoryFile(repositoryRoot: File, path: String, label: String): File {
        val file = File(repositoryRoot, path).canonicalFile
        val rootPath = repositoryRoot.toPath()
        require(file.toPath().startsWith(rootPath)) {
            "$label escapes the repository root: $path"
        }
        require(file.isFile) { "$label is missing: ${file.path}" }
        return file
    }

    private fun repositoryPath(repositoryRoot: File, file: File): String =
        file.relativeTo(repositoryRoot).invariantSeparatorsPath

    private inline fun <reified T : Enum<T>> enumValue(value: String, path: String): T =
        runCatching { enumValueOf<T>(value) }
            .getOrElse { error("$path has unknown value '$value'; expected ${enumValues<T>().joinToString()}.") }

    private fun reasonMap(value: Any?, path: String): Map<String, String> =
        map(value, path).mapValues { (key, raw) ->
            require(key.isNotBlank()) { "$path contains a blank semantic key." }
            (raw as? String)?.takeIf(String::isNotBlank)
                ?: error("$path.$key must be non-blank text.")
        }

    private fun requireExactKeys(value: Map<String, Any?>, expected: Set<String>, path: String) {
        val unknown = value.keys - expected
        val missing = expected - value.keys
        require(unknown.isEmpty()) { "$path has unknown fields: ${unknown.sorted().joinToString()}." }
        require(missing.isEmpty()) { "$path is missing fields: ${missing.sorted().joinToString()}." }
    }

    private fun text(value: Map<String, Any?>, key: String, path: String): String =
        (value[key] as? String)?.takeIf(String::isNotBlank)
            ?: error("$path.$key must be non-blank text.")

    @Suppress("UNCHECKED_CAST")
    private fun map(value: Any?, path: String): Map<String, Any?> =
        value as? Map<String, Any?> ?: error("$path must be a map.")

    @Suppress("UNCHECKED_CAST")
    private fun objectList(value: Any?, path: String): List<Map<String, Any?>> = when (value) {
        is List<*> -> value.mapIndexed { index, item ->
            item as? Map<String, Any?> ?: error("$path[$index] must be a map.")
        }
        else -> error("$path must be a list.")
    }

    private fun stringList(value: Any?, path: String, required: Boolean = false): List<String> = when (value) {
        is List<*> -> value.mapIndexed { index, item ->
            (item as? String)?.takeIf(String::isNotBlank)
                ?: error("$path[$index] must be non-blank text.")
        }.also { list ->
            require(!required || list.isNotEmpty()) { "$path must not be empty." }
            require(list.size == list.toSet().size) { "$path must not contain duplicates." }
        }
        else -> error("$path must be a list.")
    }

    private const val MERGE_KEY = "<<"
    private val INDEX_KEYS = setOf("version", "targetFiles")
    private val TARGET_KEYS = setOf("target", "claims")
    private val CLAIM_KEYS = setOf(
        "family",
        "status",
        "mechanism",
        "semantics",
        "constraints",
        "evidenceReferences",
        "prerequisites",
        "limitations"
    )
    private val SEMANTIC_KEYS = setOf("supported", "unsupported", "unknown")
    private val CONSTRAINT_KEYS = setOf(
        "workflowScopes",
        "eventNames",
        "parameterNames",
        "timezoneMode",
        "expressionMode"
    )
    private val TARGET_FILE_NAME = Regex("[a-z0-9]+(?:-[a-z0-9]+)*\\.yaml")
}
