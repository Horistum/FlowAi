package org.flowlang.cli.honest

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.io.*
import org.flowlang.cli.Json
import org.flowlang.distribution.reference.ContractResourceResolver

class BoundedProductIoTests {
    @Test fun oversizedSourcesAndNormalizationRejectWithoutCreatingOutput() {
        val root = createTempDirectory("bounded-cli-").toFile()
        try {
            val source = File(root, "large.source")
            java.io.RandomAccessFile(source, "rw").use { it.setLength(InputLimits.MAX_SOURCE_BYTES.toLong() + 1) }
            for (args in listOf(arrayOf("intent", source.path), arrayOf("flow", source.path), arrayOf("normalize", "--file", source.path))) {
                val out = File(root, "output")
                val invocation = if (args.first() == "flow") args else args + arrayOf("--out", out.path)
                val result = assertIs<CliExecutionResult.Rejected>(executeCli(invocation))
                assertEquals(CliDiagnosticCode.LIMIT_EXCEEDED, result.diagnostic.code, result.toString())
                assertEquals(2, result.exitCode)
                assertTrue(result.diagnostic.message.length <= InputLimits.MAX_DIAGNOSTIC_CHARS)
                assertTrue(result.artifacts.isEmpty())
                assertFalse(out.exists())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun normalizationFileRejectsMalformedUtf8() {
        val root = createTempDirectory("bounded-normalize-").toFile()
        try {
            val file = File(root, "input.txt").apply { writeBytes(byteArrayOf(0xc3.toByte(), 0x28)) }
            val out = File(root, "output")
            val result = assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("normalize", "--file", file.path, "--out", out.path)))
            assertEquals(CliDiagnosticCode.INVALID_INPUT, result.diagnostic.code)
            assertContains(result.diagnostic.message, "UTF8")
            assertFalse(out.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun artifactPreflightChecksEncodedBytesAndNewlineBeforeTouchingExistingFiles() {
        val root = createTempDirectory("bounded-artifact-").toFile()
        try {
            val existing = File(root, "keep.txt").apply { writeText("original") }
            val exact = "x".repeat(InputLimits.MAX_ARTIFACT_BYTES - 1)
            CliArtifactOutput.write(root, mapOf("exact.txt" to exact))
            assertEquals(InputLimits.MAX_ARTIFACT_BYTES.toLong(), File(root, "exact.txt").length())
            assertFailsWith<IoLimitException> { CliArtifactOutput.write(root, linkedMapOf("keep.txt" to "changed", "large.txt" to exact + "x")) }
            assertEquals("original", existing.readText())
            assertFalse(File(root, "large.txt").exists())
            assertFailsWith<IoLimitException> { CliArtifactOutput.write(root, mapOf("large.json" to "é".repeat(InputLimits.MAX_ARTIFACT_BYTES / 2))) }
            assertFalse(File(root, "large.json").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun outputBatchCountAndTotalLimitsApplyBeforeTheFirstWrite() {
        val root = createTempDirectory("bounded-batch-").toFile()
        try {
            val destination = File(root, "absent")
            assertFailsWith<IoLimitException> { CliArtifactOutput.write(destination, (0..InputLimits.MAX_FILES).associate { "$it.txt" to "" }) }
            assertFalse(destination.exists())
            val content = "x".repeat(InputLimits.MAX_ARTIFACT_BYTES - 1)
            assertFailsWith<IoLimitException> { CliArtifactOutput.write(destination, (0..4).associate { "$it.txt" to content }) }
            assertFalse(destination.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun presentationLimitsProduceASmallTypedFailureEvenAfterCollectingEarlierSections() {
        for (write in listOf<(CliOutput) -> Unit>(
            { it.section("LARGE", "é".repeat(InputLimits.MAX_ARTIFACT_BYTES / 2)) },
            { output -> repeat(InputLimits.MAX_FILES + 1) { output.text("small") } },
            { output -> repeat(5) { output.text("x".repeat(InputLimits.MAX_ARTIFACT_BYTES - 1)) } }
        )) {
            val catalog = CliCommandCatalog.of("bounded" to CliCommandHandler { _, output ->
                output.text("earlier")
                write(output)
                CliExecutionResult.Completed(output.snapshot())
            })
            val result = assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("bounded"), catalog))
            assertEquals(CliDiagnosticCode.LIMIT_EXCEEDED, result.diagnostic.code)
            assertEquals(1, result.presentation.items.size)
            assertTrue(Json.bytes(result.presentation).size < 4096)
        }
    }

    @Test fun externalResourcesEnforcePerFileAndAggregateBudgets() {
        ContractResourceResolver().open().use { resources ->
            val file = File(resources.root, resources.provenance.first().path)
            java.io.RandomAccessFile(file, "rw").use { it.setLength(InputLimits.MAX_SOURCE_BYTES.toLong() + 1) }
            assertFailsWith<IoLimitException> { ContractResourceResolver().open(resources.root).close() }
        }
        ContractResourceResolver().open().use { resources ->
            val bytes = ByteArray(512 * 1024) { 32 }
            resources.provenance.forEach { File(resources.root, it.path).writeBytes(bytes) }
            assertFailsWith<IoLimitException> { ContractResourceResolver().open(resources.root).close() }
        }
    }

    @Test fun diagnosticsDoNotEchoUnboundedCommandsOrExceptionMessages() {
        val catalog = CliCommandCatalog.of("bounded" to CliCommandHandler { _, _ -> throw IllegalArgumentException("x".repeat(100_000)) })
        for (result in listOf(executeCli(arrayOf("bounded"), catalog), executeCli(arrayOf("x".repeat(100_000))))) {
            val rejected = assertIs<CliExecutionResult.Rejected>(result)
            assertTrue(rejected.diagnostic.message.length <= InputLimits.MAX_DIAGNOSTIC_CHARS)
            assertTrue(rejected.command.length <= InputLimits.MAX_DIAGNOSTIC_CHARS)
            assertTrue(Json.bytes(rejected.presentation).size < 8192)
        }
    }
}
