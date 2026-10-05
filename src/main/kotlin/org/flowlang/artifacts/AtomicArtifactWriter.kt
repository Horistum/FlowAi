package org.flowlang.artifacts

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import org.flowlang.cli.Json
import org.flowlang.io.BoundedIo
import org.flowlang.io.InputLimits
import org.flowlang.io.IoBudget
import org.flowlang.serialization.FlowJson
import org.flowlang.standard.DiagnosticCoverageAnalyzer
import org.flowlang.standard.DiagnosticCoverageReport
import org.flowlang.standard.FlowStandardVersions

data class ArtifactByteReceipt(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val validation: String,
    val schema: String = "",
    val schemaSha256: String = "",
    val standardVersion: String = ""
)

data class ArtifactPublicationEvidence(
    val protocol: String = "staged-atomic-directory-v1",
    val coveredFiles: List<ArtifactByteReceipt>,
    val excludedPaths: List<String> = listOf(AtomicArtifactWriter.MANIFEST),
    val fileDataForced: Boolean = true,
    val stagingDirectoriesForced: Boolean
)

/** Returned only after the atomic move. The manifest cannot hash itself. */
data class PublishedArtifactReceipt(
    val directory: String,
    val manifest: ArtifactByteReceipt,
    val parentDirectoryForced: Boolean
)

/** Copied reference inputs may deliberately be invalid JSON; they receive byte-only validation. */
data class ArtifactContent(val bytes: ByteArray, val schema: String = "", val opaque: Boolean = false) {
    companion object {
        fun encode(name: String, value: Any, schema: String = ""): ArtifactContent {
            val bytes = BoundedIo.encode { output ->
                val content = if (value is String && !name.endsWith(".json"))
                    BoundedIo.encodeText(value, InputLimits.MAX_ARTIFACT_BYTES, "OUTPUT_BYTE_LIMIT") else Json.bytes(value)
                output.write(content)
                if (content.lastOrNull() != 10.toByte()) output.write(10)
            }
            return ArtifactContent(bytes, schema)
        }
    }
}

/** Only installed, uniquely resolved schemas; never a working-directory fallback. */
object ArtifactSchemas {
    fun packaged(path: String): ByteArray {
        require(path.matches(Regex("schemas/[a-z0-9-]+\\.schema\\.json"))) { "Invalid artifact schema path." }
        val urls = ArtifactSchemas::class.java.classLoader.getResources("flow/reference-contracts/$path")
            .asSequence().take(2).toList()
        check(urls.size == 1) { "Expected exactly one packaged artifact schema: $path" }
        return urls.single().openStream().use { BoundedIo.readBytes(it) }
    }
}

internal enum class PublicationStep { BEFORE_WRITE, AFTER_WRITE, BEFORE_FORCE, BEFORE_READ, BEFORE_MANIFEST, BEFORE_PUBLISH }

/**
 * A new/empty destination becomes visible in one atomic directory move. Nonempty destinations
 * and symbolic links are rejected. There is no backup dance or non-atomic fallback. The parent
 * directory must be controlled by the caller; this is not protection from a hostile local user.
 */
class AtomicArtifactWriter(
    private val schemaLoader: (String) -> ByteArray = ArtifactSchemas::packaged,
    private val maximumFiles: Int = InputLimits.MAX_FILES
) {
    internal var checkpoint: (PublicationStep, Path) -> Unit = { _, _ -> }

    fun publish(
        directory: File,
        contents: Map<String, ArtifactContent>,
        bundle: FlowArtifactBundleReport? = null,
        prepare: (StagedArtifacts) -> Unit = {}
    ): PublishedArtifactReceipt {
        require(maximumFiles in 1..InputLimits.MAX_RELEASE_FILES)
        // Snapshot mutable byte arrays and preflight the entire initial batch before touching disk.
        val budget = IoBudget(InputLimits.MAX_TOTAL_OUTPUT_BYTES, maximumFiles, "OUTPUT")
        val frozen = contents.mapValues { (name, content) ->
            require(name != MANIFEST) { "The publication manifest is writer-owned." }
            requireArtifactPath(name)
            BoundedIo.requireWithin(content.bytes.size.toLong(), InputLimits.MAX_ARTIFACT_BYTES, "OUTPUT_BYTE_LIMIT")
            budget.add(content.bytes.size)
            content.copy(bytes = content.bytes.copyOf())
        }
        val destination = directory.toPath().toAbsolutePath().normalize()
        require(destination.parent != null) { "Cannot publish over a filesystem root." }
        requireNoSymlinks(destination)
        requireEmptyDestination(destination)
        Files.createDirectories(destination.parent)
        val staging = Files.createTempDirectory(destination.parent, ".${destination.fileName}.staging-")
        try {
            val staged = StagedArtifacts(staging.toFile(), schemaLoader, maximumFiles, checkpoint)
            frozen.forEach { (name, content) -> staged.put(name, content) }
            prepare(staged)
            val receipts = staged.verifiedFiles()
            val scope = bundle ?: inferredBundle(receipts)
            val integrity = staged.integrity(scope)
            check(integrity.status == "PASS") { "Actual artifact integrity failed: ${integrity.issues}" }
            val directoriesForced = forceDirectories(staging)
            checkpoint(PublicationStep.BEFORE_MANIFEST, staging.resolve(MANIFEST))
            val manifest = integrity.copy(artifactIntegrityVersion = "1.1", publication = ArtifactPublicationEvidence(
                coveredFiles = receipts, stagingDirectoriesForced = directoriesForced))
            staged.putManifest(ArtifactContent.encode(MANIFEST, manifest, MANIFEST_SCHEMA))
            // Re-read every file again, including the final manifest, immediately before publication.
            staged.verifiedFiles()
            val manifestReceipt = staged.manifestReceipt()
            forceDirectory(staging)
            checkpoint(PublicationStep.BEFORE_PUBLISH, staging)
            // A failure hook or preparer cannot alter bytes after the final verification unnoticed.
            staged.verifiedFiles()
            requireNoSymlinks(destination)
            requireEmptyDestination(destination)
            Files.move(staging, destination, ATOMIC_MOVE)
            // The commit point has passed. A best-effort parent fsync must not report a rolled-back
            // operation; its actual outcome is returned separately from atomic visibility.
            return PublishedArtifactReceipt(destination.toString(), manifestReceipt, forceDirectory(destination.parent))
        } finally {
            if (Files.exists(staging, NOFOLLOW_LINKS)) deleteStaging(staging)
        }
    }

    companion object {
        const val MANIFEST = "artifact-integrity-report.json"
        const val MANIFEST_SCHEMA = "schemas/artifact-integrity-report.schema.json"

        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        internal fun requireArtifactPath(name: String) {
            require(name.length <= InputLimits.MAX_NAME_LENGTH &&
                name.matches(Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")) &&
                name.split('/').none { it == "." || it == ".." }) { "Artifact path must be a safe relative file path." }
        }

        internal fun requireNoSymlinks(path: Path) {
            var current = path.root
            for (part in path) {
                current = current.resolve(part)
                require(!Files.isSymbolicLink(current)) { "Symbolic artifact paths are forbidden." }
            }
        }

        private fun requireEmptyDestination(path: Path) {
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                require(Files.isDirectory(path, NOFOLLOW_LINKS) && Files.newDirectoryStream(path).use { !it.iterator().hasNext() }) {
                    "Artifact destination must be a new or empty directory."
                }
            }
        }

        private fun forceDirectory(path: Path): Boolean = try {
            FileChannel.open(path, READ).use { it.force(true) }
            true
        } catch (_: java.io.IOException) { false } catch (_: UnsupportedOperationException) { false }

        private fun forceDirectories(root: Path): Boolean = Files.walk(root).use { paths ->
            // Evaluate all attempts, even when a provider does not support directory fsync.
            paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.toList().map(::forceDirectory).all { it }
        }

        private fun deleteStaging(root: Path) {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }

        private fun inferredBundle(receipts: List<ArtifactByteReceipt>): FlowArtifactBundleReport {
            val entries = receipts.mapIndexed { index, receipt -> FlowArtifactEntry(receipt.path,
                if (receipt.path.endsWith(".json")) FlowArtifactRole.REPORT else FlowArtifactRole.RENDERED,
                receipt.schema, required = true, derived = true, pipelineIndex = index + 1) }
            return FlowArtifactBundleReport(flowName = "", target = "", strict = false, artifacts = entries,
                requiredArtifacts = entries.map { it.name }, optionalArtifacts = emptyList(), pipeline = entries.map { it.name })
        }
    }
}

/** Transaction-local staged files. A preparer may add dependent reports but cannot publish. */
class StagedArtifacts internal constructor(
    val directory: File,
    private val schemaLoader: (String) -> ByteArray,
    private val maximumFiles: Int,
    private val checkpoint: (PublicationStep, Path) -> Unit
) {
    private val budget = IoBudget(InputLimits.MAX_TOTAL_OUTPUT_BYTES, maximumFiles, "OUTPUT")
    private val receipts = linkedMapOf<String, ArtifactByteReceipt>()
    private var sealed = false

    fun put(name: String, content: ArtifactContent) {
        require(!sealed && name != AtomicArtifactWriter.MANIFEST) { "Publication manifest is writer-owned and last." }
        write(name, content)
    }

    internal fun putManifest(content: ArtifactContent) {
        check(!sealed)
        write(AtomicArtifactWriter.MANIFEST, content)
        sealed = true
    }

    private fun write(name: String, content: ArtifactContent) {
        AtomicArtifactWriter.requireArtifactPath(name)
        require(name !in receipts) { "Artifact already staged: $name" }
        BoundedIo.requireWithin(content.bytes.size.toLong(), InputLimits.MAX_ARTIFACT_BYTES, "OUTPUT_BYTE_LIMIT")
        budget.add(content.bytes.size)
        val path = directory.toPath().resolve(name)
        AtomicArtifactWriter.requireNoSymlinks(path)
        Files.createDirectories(path.parent)
        checkpoint(PublicationStep.BEFORE_WRITE, path)
        FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(content.bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            checkpoint(PublicationStep.AFTER_WRITE, path)
            checkpoint(PublicationStep.BEFORE_FORCE, path)
            channel.force(true)
        }
        val actual = read(path)
        check(actual.contentEquals(content.bytes)) { "Written artifact bytes differ: $name" }
        receipts[name] = inspect(name, actual, content.schema, content.opaque, schemaLoader)
    }

    private fun read(path: Path): ByteArray {
        checkpoint(PublicationStep.BEFORE_READ, path)
        AtomicArtifactWriter.requireNoSymlinks(path)
        check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Artifact must be a regular file." }
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { BoundedIo.readBytes(it, InputLimits.MAX_ARTIFACT_BYTES) }
    }

    fun verifiedFiles(): List<ArtifactByteReceipt> {
        val names = artifactFiles(directory, maximumFiles)
        check(names.toSet() == receipts.keys) { "Staging inventory changed after write." }
        receipts.forEach { (name, receipt) ->
            val bytes = read(File(directory, name).toPath())
            check(bytes.size.toLong() == receipt.sizeBytes && AtomicArtifactWriter.sha256(bytes) == receipt.sha256) {
                "Staged artifact changed after validation: $name"
            }
        }
        return receipts.values.filter { it.path != AtomicArtifactWriter.MANIFEST }.sortedBy { it.path }
    }

    /** Scope excludes the not-yet-written manifest; callers may explicitly select an earlier DAG layer. */
    fun integrity(bundle: FlowArtifactBundleReport): ArtifactIntegrityReport {
        val actual = verifiedFiles()
        val scope = bundle.copy(artifacts = bundle.artifacts.filter { it.name != AtomicArtifactWriter.MANIFEST },
            requiredArtifacts = bundle.requiredArtifacts.filter { it != AtomicArtifactWriter.MANIFEST },
            pipeline = bundle.pipeline.filter { it != AtomicArtifactWriter.MANIFEST })
        val coverage = if ("diagnostic-coverage-report.json" in receipts)
            FlowJson.read(File(directory, "diagnostic-coverage-report.json"), DiagnosticCoverageReport::class.java)
        else DiagnosticCoverageAnalyzer().analyze(emptyList())
        return ArtifactIntegrityAnalyzer().analyze(scope, actual.map { it.path }.toSet(),
            actual.filter { it.standardVersion.isNotEmpty() }.map { ArtifactIntegrityVersionObservation(it.path, it.standardVersion) }, coverage)
    }

    internal fun manifestReceipt(): ArtifactByteReceipt = receipts.getValue(AtomicArtifactWriter.MANIFEST)

    companion object {
        internal fun inspect(name: String, bytes: ByteArray, schema: String, opaque: Boolean,
            schemaLoader: (String) -> ByteArray): ArtifactByteReceipt {
            var validation = "BYTES"
            var version = ""
            var schemaHash = ""
            require(!opaque || schema.isEmpty()) { "Opaque reference files cannot declare output schemas." }
            if (!opaque) {
                val text = BoundedIo.decodeUtf8(bytes)
                validation = "UTF8"
                if (name.endsWith(".json")) {
                    val tree = FlowJson.readTree(text, name)
                    validation = "JSON"
                    tree.get("standardVersion")?.let {
                        require(it.isTextual) { "Artifact standardVersion must be text: $name" }
                        version = it.textValue()
                        require(version == FlowStandardVersions.FLOW_STANDARD_VERSION) { "Artifact standardVersion mismatch: $name" }
                    }
                    if (schema.isNotBlank()) {
                        val schemaBytes = schemaLoader(schema)
                        ArtifactSchemaValidator.validate(tree, FlowJson.readTree(BoundedIo.decodeUtf8(schemaBytes), schema))
                        schemaHash = AtomicArtifactWriter.sha256(schemaBytes)
                        validation = "JSON_SCHEMA"
                    }
                } else {
                    require(schema.isEmpty()) { "Only JSON outputs may declare a JSON schema." }
                    if (name == "standard-version.txt") {
                        version = text.trim()
                        require(version == FlowStandardVersions.FLOW_STANDARD_VERSION) { "Written standard version mismatch." }
                        validation = "STANDARD_VERSION"
                    }
                }
            }
            return ArtifactByteReceipt(name, bytes.size.toLong(), AtomicArtifactWriter.sha256(bytes), validation, schema, schemaHash, version)
        }

        internal fun artifactFiles(directory: File, maximum: Int): List<String> {
            val root = directory.toPath()
            AtomicArtifactWriter.requireNoSymlinks(root.toAbsolutePath().normalize())
            require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Artifact directory is missing." }
            val names = mutableListOf<String>()
            var visited = 0
            Files.walk(root).use { paths -> paths.forEach { path ->
                // Directories also consume a traversal budget (one path component per name character
                // is not allowed to turn a bounded file set into an unbounded walk).
                BoundedIo.requireWithin((++visited).toLong(), maximum * 4 + 1, "OUTPUT_COUNT_LIMIT")
                require(!Files.isSymbolicLink(path)) { "Symbolic artifact paths are forbidden." }
                if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Non-regular artifact entry." }
                    names += root.relativize(path).toString().replace(File.separatorChar, '/')
                    BoundedIo.requireWithin(names.size.toLong(), maximum, "OUTPUT_COUNT_LIMIT")
                }
            } }
            return names.sorted()
        }
    }
}
