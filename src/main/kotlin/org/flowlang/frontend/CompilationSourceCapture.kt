package org.flowlang.frontend

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.flowlang.compiler.CompilationFrontend
import org.flowlang.serialization.ContractReadPolicy
import org.flowlang.compiler.CompilationSource

class CapturedCompilationSource<T> internal constructor(
    val source: CompilationSource,
    val value: T
)

/**
 * Captures source bytes once, decodes them strictly, and gives the parser the
 * exact text whose bytes are bound by the recorded digest. A later filesystem
 * change cannot alter the already captured compilation input.
 */
internal object CompilationSourceCapture {
    fun <T> capture(
        file: File,
        frontend: CompilationFrontend,
        parse: (String, String) -> T
    ): CapturedCompilationSource<T> = capture(file, frontend, null, parse)

    fun <T> capture(
        file: File,
        frontend: CompilationFrontend,
        sourceIdentity: String?,
        parse: (String, String) -> T
    ): CapturedCompilationSource<T> {
        require(sourceIdentity == null || sourceIdentity.isNotBlank()) { "Compilation source identity must not be blank." }
        require(file.isFile) { "Compilation source does not exist: ${file.path}" }
        val bytes = if (frontend == CompilationFrontend.INTENT_YAML) ContractReadPolicy.readBytes(file)
            else file.readBytes()
        val sourceName = sourceIdentity ?: file.path
        val identity = sourceIdentity ?: file.absoluteFile.toPath().normalize().toString()
        val text = decodeUtf8(bytes, sourceName)
        return CapturedCompilationSource(
            source = CompilationSource.fromBytes(
                frontend = frontend,
                identity = identity,
                bytes = bytes,
                sourceName = sourceName
            ),
            value = parse(text, sourceName)
        )
    }

    fun <T> captureText(
        text: String,
        identity: String,
        frontend: CompilationFrontend,
        parse: (String, String) -> T
    ): CapturedCompilationSource<T> {
        if (frontend == CompilationFrontend.INTENT_YAML) ContractReadPolicy.requireSize(text, identity)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        return CapturedCompilationSource(
            source = CompilationSource.fromBytes(
                frontend = frontend,
                identity = identity,
                bytes = bytes,
                sourceName = identity
            ),
            value = parse(text, identity)
        )
    }

    /** Captures a frontend-owned immutable value together with its exact serialized source view. */
    fun <T> captureBytes(
        bytes: ByteArray,
        identity: String,
        sourceName: String,
        frontend: CompilationFrontend,
        value: T
    ): CapturedCompilationSource<T> = CapturedCompilationSource(
        source = CompilationSource.fromBytes(
            frontend = frontend,
            identity = identity,
            bytes = bytes,
            sourceName = sourceName
        ),
        value = value
    )

    private fun decodeUtf8(bytes: ByteArray, identity: String): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { throw IllegalArgumentException("Compilation source must be valid UTF-8: $identity", it) }
    }
}
