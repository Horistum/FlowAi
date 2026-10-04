package org.flowlang.frontend

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.io.*
import org.flowlang.compiler.CompilationFrontend
import org.flowlang.parser.FlowParser
import org.flowlang.parser.Lexer
import org.flowlang.serialization.FlowJson
import org.flowlang.serialization.FlowYaml
import org.flowlang.modules.CanonicalModuleLoader

class BoundedIoTests {
    @Test fun streamReadsAcceptExactLimitAndConsumeOnlyOneSentinelByte() {
        assertContentEquals(ByteArray(16), BoundedIo.readBytes(ByteArrayInputStream(ByteArray(16)), 16))
        var consumed = 0
        val infinite = object : InputStream() { override fun read(): Int { consumed++; return 32 } }
        assertEquals("INPUT_BYTE_LIMIT", assertFailsWith<IoLimitException> { BoundedIo.readBytes(infinite, 16) }.limitCode)
        assertEquals(17, consumed)
        assertContentEquals(byteArrayOf(), BoundedIo.readBytes(ByteArrayInputStream(byteArrayOf()), 0))
        assertFailsWith<IoLimitException> { BoundedIo.readBytes(ByteArrayInputStream(byteArrayOf(1)), 0) }
    }

    @Test fun utf8BudgetsCountBytesAndRejectMalformedTextWithoutReplacement() {
        for ((text, size) in listOf("plain" to 5, "é" to 2, "中" to 3, "😀" to 4)) {
            assertEquals(size, BoundedIo.textSize(text, size))
            assertEquals(text, BoundedIo.decodeUtf8(BoundedIo.encodeText(text, size)))
            assertFailsWith<IoLimitException> { BoundedIo.encodeText(text, size - 1) }
        }
        for (text in listOf("\uD800", "\uDC00", "\uD800x")) assertFailsWith<IllegalArgumentException> { BoundedIo.encodeText(text) }
        assertFailsWith<IllegalArgumentException> { BoundedIo.decodeUtf8(byteArrayOf(0xc3.toByte(), 0x28)) }
    }

    @Test fun streamingSerializationStopsBeforeOversizedBulkAndSingleWrites() {
        assertContentEquals(ByteArray(16), BoundedIo.encode(16) { it.write(ByteArray(16)) })
        var afterOverflow = false
        assertFailsWith<IoLimitException> { BoundedIo.encode(16) {
            it.write(ByteArray(16)); it.write(0); afterOverflow = true
        } }
        assertFalse(afterOverflow)
        assertFailsWith<IoLimitException> { BoundedIo.encode(16) { it.write(ByteArray(17)) } }
        assertFailsWith<IoLimitException> { BoundedIo.encode(16) { output ->
            try { output.write(ByteArray(17)) } catch (failure: Exception) { throw IllegalStateException("wrapped", failure) }
        } }
    }

    @Test fun aggregateAccountingChecksBothCountsAndBytesWithoutOverflow() {
        val budget = IoBudget(16, 2, "INPUT")
        budget.add(8); budget.add(8)
        assertEquals(0, budget.remainingBytes())
        assertEquals("INPUT_COUNT_LIMIT", assertFailsWith<IoLimitException> { budget.add(0) }.limitCode)
        val huge = IoBudget(Int.MAX_VALUE, 2, "OUTPUT")
        huge.add(Int.MAX_VALUE)
        assertEquals("OUTPUT_TOTAL_BYTE_LIMIT", assertFailsWith<IoLimitException> { huge.add(1) }.limitCode)
    }

    @Test fun jsonAndYamlCollectionsAcceptExactCountsAndRejectOneExtraIncludingNestedContainers() {
        val readers: List<(String) -> Any> = listOf({ FlowJson.readTree(it) }, { FlowYaml.readMap(it) })
        for (item in listOf("0", "[]", "{}")) {
            val accepted = "{\"items\":[" + List(InputLimits.MAX_COLLECTION_ENTRIES) { item }.joinToString(",") + "]}"
            val rejected = accepted.dropLast(2) + ",$item]}"
            readers.forEach { read ->
                read(accepted)
                assertEquals("CONTRACT_COLLECTION_LIMIT", BoundedIo.limitFailure(assertFails { read(rejected) })?.limitCode)
            }
        }
        val fields = (0 until InputLimits.MAX_COLLECTION_ENTRIES).joinToString(",") { "\"k$it\":0" }
        readers.forEach { read ->
            read("{$fields}")
            assertEquals("CONTRACT_COLLECTION_LIMIT", BoundedIo.limitFailure(assertFails { read("{$fields,\"extra\":0}") })?.limitCode)
        }
    }

    @Test fun flowLexerRejectsTokenFloodBeforeConstructingAnUnboundedTokenList() {
        assertEquals(InputLimits.MAX_TOKENS, Lexer("a ".repeat(InputLimits.MAX_TOKENS - 1)).tokenize().size)
        assertEquals("FLOW_TOKEN_LIMIT", assertFailsWith<IoLimitException> {
            Lexer("a ".repeat(InputLimits.MAX_TOKENS)).tokenize()
        }.limitCode)
    }

    @Test fun allCapturedSourceFormsRejectOversizeBeforeCallingTheirParser() {
        val root = createTempDirectory("bounded-source-").toFile()
        try {
            val file = File(root, "oversize.flow")
            java.io.RandomAccessFile(file, "rw").use { it.setLength(InputLimits.MAX_SOURCE_BYTES.toLong() + 1) }
            var called = false
            assertFailsWith<IoLimitException> { CompilationSourceCapture.capture(file, CompilationFrontend.FLOW_SOURCE) { _, _ -> called = true } }
            assertFalse(called)
            assertFailsWith<IoLimitException> { FlowParser().parse(file) }
            val large = "x".repeat(InputLimits.MAX_SOURCE_BYTES + 1)
            assertFailsWith<IoLimitException> { CompilationSourceCapture.captureText(large, "large", CompilationFrontend.FLOW_SOURCE) { _, _ -> called = true } }
            assertFalse(called)
            assertFailsWith<IoLimitException> { CompilationSourceCapture.captureBytes(large.toByteArray(), "large", "large", CompilationFrontend.FLOW_SOURCE, Unit) }
        } finally { root.deleteRecursively() }
    }

    @Test fun directoryEnumerationBoundsEvenUnselectedEntriesAndDescriptorCollections() {
        val root = createTempDirectory("bounded-directory-").toFile()
        try {
            repeat(InputLimits.MAX_FILES) { File(root, "$it.txt").writeText("") }
            assertEquals(InputLimits.MAX_FILES, BoundedIo.files(root) { true }.size)
            File(root, "extra.txt").writeText("")
            assertFailsWith<IoLimitException> { BoundedIo.files(root) { false } }
            assertFailsWith<IoLimitException> { CanonicalModuleLoader.loadDirectory(root) }
            assertFailsWith<IoLimitException> { CanonicalModuleLoader.loadTexts(List(InputLimits.MAX_FILES + 1) { "" }) }
        } finally { root.deleteRecursively() }
    }
}
