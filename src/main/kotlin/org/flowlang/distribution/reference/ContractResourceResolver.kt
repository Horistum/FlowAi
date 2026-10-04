package org.flowlang.distribution.reference

import org.flowlang.io.BoundedIo
import org.flowlang.io.InputLimits
import org.flowlang.io.IoBudget

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections

enum class ContractResourceOrigin { CLASSPATH, EXTERNAL }

data class ContractResourceProvenance(
    val path: String,
    val origin: ContractResourceOrigin,
    val location: String,
    val sha256: String,
    val sizeBytes: Long
)

/** A command-scoped snapshot consumed by existing strict File-based authorities. */
class ContractResourceSnapshot internal constructor(
    val root: File,
    provenance: List<ContractResourceProvenance>
) : AutoCloseable {
    val provenance: List<ContractResourceProvenance> = Collections.unmodifiableList(provenance.toList())
    override fun close() {
        check(root.deleteRecursively()) { "Could not remove command contract snapshot." }
    }
}

/** No working-directory lookup, overlay or missing-resource fallback is permitted. */
class ContractResourceResolver(
    private val loader: ClassLoader = ContractResourceResolver::class.java.classLoader
) {
    fun open(externalRoot: File? = null): ContractResourceSnapshot {
        val entries = BoundedIo.decodeUtf8(resource(INDEX)).lineSequence().filter { it.isNotEmpty() }.take(InputLimits.MAX_FILES + 1).map { line ->
            val fields = line.split('\t', limit = 3)
            check(fields.size == 2 && fields[0].matches(Regex("[0-9a-f]{64}")) && validPath(fields[1])) {
                "Invalid packaged contract resource index entry."
            }
            fields[1] to fields[0]
        }.toList()
        check(entries.isNotEmpty() && entries.map { it.first } == entries.map { it.first }.distinct().sorted()) {
            "Contract resource index must be non-empty, sorted and unique."
        }
        BoundedIo.requireWithin(entries.size.toLong(), InputLimits.MAX_FILES, "INPUT_FILE_COUNT_LIMIT")
        val budget = IoBudget(InputLimits.MAX_TOTAL_INPUT_BYTES, code = "INPUT")
        val external = externalRoot?.absoluteFile?.toPath()?.normalize()?.also {
            require(Files.isDirectory(it) && !Files.isSymbolicLink(it)) { "External contract root must be a directory: $it" }
        }
        val root = Files.createTempDirectory("flow-contracts-").toFile()
        try {
            val provenance = entries.map { (path, expectedHash) ->
                val bytes = if (external == null) resource("$PREFIX/$path", minOf(InputLimits.MAX_SOURCE_BYTES, budget.remainingBytes())) else {
                    val file = external.resolve(path)
                    var component: java.nio.file.Path = external
                    for (part in external.relativize(file)) {
                        component = component.resolve(part)
                        require(!Files.isSymbolicLink(component)) { "Symbolic contract resource is forbidden: $path" }
                    }
                    require(Files.isRegularFile(file)) { "Required external contract resource is missing: $path" }
                    BoundedIo.readBytes(file.toFile(), minOf(InputLimits.MAX_SOURCE_BYTES, budget.remainingBytes()))
                }
                budget.add(bytes.size)
                val digest = sha256(bytes)
                check(external != null || digest == expectedHash) { "Packaged contract resource hash mismatch: $path" }
                File(root, path).apply { parentFile.mkdirs(); writeBytes(bytes) }
                ContractResourceProvenance(path,
                    if (external == null) ContractResourceOrigin.CLASSPATH else ContractResourceOrigin.EXTERNAL,
                    if (external == null) "classpath:/$PREFIX/$path" else external.resolve(path).toUri().toString(),
                    digest, bytes.size.toLong())
            }
            return ContractResourceSnapshot(root, provenance)
        } catch (failure: Throwable) {
            if (!root.deleteRecursively()) failure.addSuppressed(IllegalStateException("Could not remove failed contract snapshot."))
            throw failure
        }
    }

    private fun resource(path: String, maximum: Int = InputLimits.MAX_SOURCE_BYTES): ByteArray {
        val matches = loader.getResources(path).asSequence().take(2).toList()
        check(matches.size == 1) { "Expected exactly one classpath contract resource '$path', found ${matches.size}." }
        return matches.single().openStream().use { BoundedIo.readBytes(it, maximum) }
    }

    private fun validPath(path: String): Boolean =
        path.length <= InputLimits.MAX_NAME_LENGTH && path.matches(Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+")) &&
            path.split('/').none { it == "." || it == ".." }

    companion object {
        const val PREFIX = "flow/reference-contracts"
        const val INDEX = "$PREFIX/index.tsv"
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
