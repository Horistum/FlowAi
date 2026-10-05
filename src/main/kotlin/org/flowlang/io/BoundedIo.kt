package org.flowlang.io

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files

/** Owns byte accounting before allocation, strict text decoding and bounded streaming serialization. */
object BoundedIo {
    fun requireWithin(size: Long, maximum: Int, code: String) {
        require(maximum >= 0 && size >= 0)
        if (size > maximum.toLong()) throw IoLimitException(code)
    }

    fun readBytes(file: File, maximum: Int = InputLimits.MAX_SOURCE_BYTES): ByteArray {
        // A byte budget cannot bound an open/read on a FIFO or device. File inputs are
        // regular files; streaming callers must explicitly use the stream API.
        require(Files.isRegularFile(file.toPath())) { "INPUT_FILE_TYPE: input must be a regular file." }
        return file.inputStream().use { readBytes(it, maximum) }
    }

    /** Read at most limit + one sentinel byte; file metadata is never trusted. Does not close the stream. */
    fun readBytes(input: InputStream, maximum: Int = InputLimits.MAX_SOURCE_BYTES): ByteArray {
        require(maximum in 0 until Int.MAX_VALUE)
        val bytes = input.readNBytes(maximum + 1)
        requireWithin(bytes.size.toLong(), maximum, "INPUT_BYTE_LIMIT")
        return bytes
    }

    fun readText(file: File): String = decodeUtf8(readBytes(file))

    fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (failure: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("INPUT_UTF8: input must contain valid UTF-8.", failure)
    }

    /** Count UTF-8 bytes without constructing an oversized encoded copy. Reject unpaired surrogates. */
    fun textSize(text: String, maximum: Int = InputLimits.MAX_SOURCE_BYTES, code: String = "INPUT_BYTE_LIMIT"): Int {
        requireWithin(text.length.toLong(), maximum, code)
        var size = 0L
        var index = 0
        while (index < text.length) {
            val c = text[index++]
            size += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() -> {
                    require(index < text.length && text[index].isLowSurrogate()) { "INPUT_UTF8: unpaired surrogate." }
                    index++
                    4
                }
                c.isLowSurrogate() -> throw IllegalArgumentException("INPUT_UTF8: unpaired surrogate.")
                else -> 3
            }
            requireWithin(size, maximum, code)
        }
        return size.toInt()
    }

    fun encodeText(text: String, maximum: Int = InputLimits.MAX_SOURCE_BYTES, code: String = "INPUT_BYTE_LIMIT"): ByteArray {
        textSize(text, maximum, code)
        return text.toByteArray(Charsets.UTF_8)
    }

    /** Serialization stops before the first over-budget write, including a single large write. */
    fun encode(maximum: Int = InputLimits.MAX_ARTIFACT_BYTES, code: String = "OUTPUT_BYTE_LIMIT", write: (OutputStream) -> Unit): ByteArray {
        require(maximum >= 0)
        val bytes = ByteArrayOutputStream(minOf(maximum, 8192))
        val output = object : OutputStream() {
            override fun write(value: Int) {
                requireWithin(bytes.size().toLong() + 1, maximum, code)
                bytes.write(value)
            }
            override fun write(value: ByteArray, offset: Int, length: Int) {
                java.util.Objects.checkFromIndexSize(offset, length, value.size)
                requireWithin(bytes.size().toLong() + length, maximum, code)
                bytes.write(value, offset, length)
            }
        }
        try { write(output) } catch (failure: Exception) {
            // Jackson may wrap stream failures with a property path; preserve the stable limit cause.
            limitFailure(failure)?.let { throw it }
            throw failure
        }
        return bytes.toByteArray()
    }

    fun limitFailure(failure: Throwable): IoLimitException? {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = failure
        while (cause != null && seen.add(cause)) {
            if (cause is IoLimitException) return cause
            cause = cause.cause
        }
        return null
    }

    /** Bound directory enumeration before materializing or sorting the selected file collection. */
    fun files(directory: File, accept: (File) -> Boolean): List<File> = Files.newDirectoryStream(directory.toPath()).use { stream ->
        val files = mutableListOf<File>()
        var visited = 0L
        for (entry in stream) {
            requireWithin(++visited, InputLimits.MAX_FILES, "INPUT_FILE_COUNT_LIMIT")
            val file = entry.toFile()
            if (accept(file)) files += file
        }
        files.sortedBy { it.name }
    }

    fun diagnostic(message: String): String = message.take(InputLimits.MAX_DIAGNOSTIC_CHARS)
}

/** Command/catalog-scoped aggregate; reserve before retaining the next input or output. */
class IoBudget(private val maximumBytes: Int, private val maximumItems: Int = InputLimits.MAX_FILES, private val code: String) {
    init { require(maximumBytes >= 0 && maximumItems >= 0) }
    private var bytes = 0L
    private var items = 0L
    fun remainingBytes(): Int = (maximumBytes.toLong() - bytes).toInt()
    fun add(size: Int) {
        require(size >= 0)
        BoundedIo.requireWithin(items + 1, maximumItems, "${code}_COUNT_LIMIT")
        BoundedIo.requireWithin(bytes + size, maximumBytes, "${code}_TOTAL_BYTE_LIMIT")
        items++
        bytes += size
    }
}
