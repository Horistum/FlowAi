package org.flowlang.cli.honest

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.artifacts.ArtifactPublicationVerifier
import org.flowlang.distribution.reference.ContractResourceResolver
import org.flowlang.io.BoundedIo

class IntegratedProductIntegrityTests {
    @Test fun explicitContractSnapshotOptionsAndAtomicPublicationCompose() = workspace { root ->
        ContractResourceResolver().open().use { resources ->
            val source = File(resources.root, "examples/intent/checkout-build-image.intent.yaml")
            val destination = File(root, "result with spaces")
            val result = assertIs<CliExecutionResult.Targeted>(executeCli(arrayOf("intent",
                "--out=${destination.path}", "--contracts", resources.root.path, "--target=jenkins", "--render", "--", source.path)))
            assertEquals(0, result.exitCode)
            assertTrue(result.artifacts.all { it.persisted })
            val report = ArtifactPublicationVerifier().verify(destination)
            assertTrue(report.publication!!.coveredFiles.any { it.path == "Jenkinsfile" })
            assertContentEquals(File(destination, "execution-plan.json").readBytes(), File(destination, "canonical-execution-plan.json").readBytes())
            val before = bytes(destination)
            val rejected = assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("intent",
                "--contracts", resources.root.path, "--out", destination.path, "--target=jenkins", "--target=github-actions", source.path)))
            assertEquals(CliDiagnosticCode.INVALID_INPUT, rejected.diagnostic.code)
            assertEquals(before, bytes(destination))
        }
    }

    @Test fun pathologicalInputsPreserveExistingOutputAndLeaveNoStaging() = workspace { root ->
        val destination = File(root, "existing").apply { mkdir() }
        File(destination, "keep.txt").writeText("previous bytes")
        val before = bytes(destination)
        val source = File(root, "input.yaml")
        for (raw in listOf("x: &a [1]\ny: *a\n".toByteArray(),
            ("x: " + "[".repeat(70) + "0" + "]".repeat(70)).toByteArray(),
            "x: 1\nx: 2\n".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28))) {
            source.writeBytes(raw)
            val result = assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("intent", "--out", destination.path, source.path)))
            assertEquals(CliDiagnosticCode.INVALID_INPUT, result.diagnostic.code)
            assertTrue(result.diagnostic.message.length <= 2048)
            assertTrue(result.artifacts.isEmpty())
            assertEquals(before, bytes(destination))
            assertFalse(root.list()!!.any { ".staging-" in it })
        }
    }

    @Test fun normalizationOptionsPublishVerifiableBytesAndTamperingIsDetected() = workspace { root ->
        val source = File(root, "requirements.txt").apply { writeText("build and test") }
        val destination = File(root, "normalized")
        val result = assertIs<CliExecutionResult.Completed>(executeCli(arrayOf("normalize",
            "--out", destination.path, "--app=shop", "--file", source.path)))
        assertEquals(0, result.exitCode)
        val report = ArtifactPublicationVerifier().verify(destination)
        val covered = report.publication!!.coveredFiles
        assertTrue(covered.isNotEmpty())
        val actual = File(destination, covered.first().path)
        actual.appendText("changed")
        assertFails { ArtifactPublicationVerifier().verify(destination) }
    }

    @Test fun fileInputsRejectDirectoriesAndMissingFilesBeforeOpening() = workspace { root ->
        for (file in listOf(root, File(root, "missing"))) {
            val failure = assertFailsWith<IllegalArgumentException> { BoundedIo.readBytes(file) }
            assertContains(failure.message.orEmpty(), "INPUT_FILE_TYPE")
        }
        val file = File(root, "regular").apply { writeText("bounded") }
        assertEquals("bounded", BoundedIo.readText(file))
    }

    private fun bytes(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }

    private fun workspace(action: (File) -> Unit) {
        val root = createTempDirectory("integrated-product-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
}
