package org.flowlang.cli.honest

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.artifacts.ArtifactPublicationVerifier

class AtomicCliPublicationTests {
    @Test fun diagnosticsPublishPackagedSchemaValidatedBytesAndRejectRepeatWithoutChangingOutput() = workspace { root ->
        val destination = File(root, "output")
        val args = arrayOf("diagnostics", "--out", destination.path)
        val first = assertIs<CliExecutionResult.Completed>(executeCli(args))
        assertTrue(first.artifacts.all { it.persisted })
        val evidence = assertNotNull(ArtifactPublicationVerifier().verify(destination).publication)
        assertEquals("JSON_SCHEMA", evidence.coveredFiles.single().validation)
        val before = destination.listFiles()!!.associate { it.name to it.readBytes().toList() }
        val second = assertIs<CliExecutionResult.Rejected>(executeCli(args))
        assertTrue(second.artifacts.isEmpty())
        assertEquals(before, destination.listFiles()!!.associate { it.name to it.readBytes().toList() })
        assertEquals(listOf("output"), root.list()!!.toList())
    }

    @Test fun failedStandardVerificationCannotPublishAReport() = workspace { root ->
        val bundle = File(root, "invalid").apply { mkdir() }
        val destination = File(root, "output")
        val result = assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("standard-verify", "--bundle", bundle.path, "--out", destination.path)))
        assertEquals(2, result.exitCode)
        assertTrue(result.artifacts.isEmpty())
        assertFalse(destination.exists())
    }

    private fun workspace(action: (File) -> Unit) {
        val root = createTempDirectory("atomic-cli-test-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
}
