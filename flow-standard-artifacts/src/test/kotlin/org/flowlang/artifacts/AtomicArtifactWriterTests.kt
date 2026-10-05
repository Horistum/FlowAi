package org.flowlang.artifacts

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.io.InputLimits
import org.flowlang.io.IoLimitException
import org.flowlang.standard.FlowStandardVersions

class AtomicArtifactWriterTests {
    // The installed CLI integration tests use the actual packaged manifest schema. These unit
    // tests inject a tiny declared payload schema without requiring the reference-distribution module.
    private val schemas: (String) -> ByteArray = { path ->
        if (path == "schemas/payload.schema.json") """{"type":"object","required":["value"],"properties":{"value":{"type":"integer"}}}""".toByteArray()
        else """{"type":"object"}""".toByteArray()
    }
    private fun writer() = AtomicArtifactWriter(schemas)
    private fun values() = linkedMapOf("payload.json" to ArtifactContent.encode("payload.json", mapOf(
        "standardVersion" to FlowStandardVersions.FLOW_STANDARD_VERSION, "value" to 7), "schemas/payload.schema.json"),
        "nested/unicode.txt" to ArtifactContent.encode("unicode.txt", "žluťoučký"))

    @Test fun publishedReceiptBindsReadBackBytesAndExcludesOnlyItsOwnManifest() = workspace { root ->
        val destination = File(root, "published")
        val receipt = writer().publish(destination, values())
        val report = ArtifactPublicationVerifier(schemas).verify(destination, receipt.manifest)
        val evidence = assertNotNull(report.publication)
        assertEquals(listOf(AtomicArtifactWriter.MANIFEST), evidence.excludedPaths)
        assertEquals(listOf("nested/unicode.txt", "payload.json"), evidence.coveredFiles.map { it.path })
        for (file in evidence.coveredFiles) {
            val bytes = File(destination, file.path).readBytes()
            assertEquals(bytes.size.toLong(), file.sizeBytes)
            assertEquals(AtomicArtifactWriter.sha256(bytes), file.sha256)
        }
        val json = evidence.coveredFiles.single { it.path == "payload.json" }
        assertEquals("JSON_SCHEMA", json.validation)
        assertEquals(AtomicArtifactWriter.sha256(schemas(json.schema)), json.schemaSha256)
        assertEquals(FlowStandardVersions.FLOW_STANDARD_VERSION, json.standardVersion)
        assertFalse(AtomicArtifactWriter.MANIFEST in report.requiredArtifactsPresent)
        assertEquals(setOf("published"), root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun everyPrecommitFailureRemovesStagingAndEmitsNoPublishedPass() = workspace { root ->
        for (step in PublicationStep.entries) {
            val destination = File(root, "published")
            val writer = writer().apply { checkpoint = { current, _ -> if (current == step) throw IOException("injected $step") } }
            assertFailsWith<IOException>(step.name) { writer.publish(destination, values()) }
            assertFalse(destination.exists(), step.name)
            assertTrue(root.listFiles()!!.isEmpty(), step.name)
        }
    }

    @Test fun partialWriteAndPostValidationCorruptionCannotEscape() = workspace { root ->
        for (step in listOf(PublicationStep.AFTER_WRITE, PublicationStep.BEFORE_PUBLISH)) {
            val writer = writer().apply { checkpoint = { current, path ->
                if (current == step) {
                    if (current == PublicationStep.AFTER_WRITE) path.toFile().writeBytes(byteArrayOf(1))
                    else File(path.toFile(), "payload.json").appendText(" ")
                }
            } }
            assertFails { writer.publish(File(root, "published"), values()) }
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun unsupportedAtomicMoveFailsClosedWithoutFallback() = workspace { root ->
        val writer = writer().apply { checkpoint = { step, path -> if (step == PublicationStep.BEFORE_PUBLISH)
            throw AtomicMoveNotSupportedException(path.toString(), "published", "injected provider rejection") } }
        assertFailsWith<AtomicMoveNotSupportedException> { writer.publish(File(root, "published"), values()) }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun nonemptyDestinationIsNeverReplacedAndEmptyDestinationSurvivesFailure() = workspace { root ->
        val destination = File(root, "published").apply { mkdir() }
        val old = File(destination, "old.txt").apply { writeText("previous release") }
        assertFailsWith<IllegalArgumentException> { writer().publish(destination, values()) }
        assertEquals("previous release", old.readText())
        assertEquals(listOf("old.txt"), destination.list()!!.toList())
        old.delete()
        val writer = writer().apply { checkpoint = { _, _ -> error("failure") } }
        assertFails { writer.publish(destination, values()) }
        assertTrue(destination.isDirectory && destination.list()!!.isEmpty())
        writer().publish(destination, values())
        assertEquals("PASS", ArtifactPublicationVerifier(schemas).verify(destination).status)
    }

    @Test fun traversalLinksAndUntrackedFilesAreRejected() = workspace { root ->
        val destination = File(root, "published")
        for (name in listOf("../escape.txt", "/absolute.txt", "a/../b", "a\\b", ".", "a//b"))
            assertFails { writer().publish(destination, mapOf(name to ArtifactContent(byteArrayOf(1)))) }
        val outside = File(root, "outside").apply { mkdir() }
        Files.createSymbolicLink(destination.toPath(), outside.toPath())
        assertFails { writer().publish(destination, values()) }
        Files.delete(destination.toPath())
        assertFails { writer().publish(destination, values()) { staged -> File(staged.directory, "extra.txt").writeText("unexpected") } }
        assertFalse(destination.exists())
        assertTrue(outside.list()!!.isEmpty())
    }

    @Test fun schemaVersionUtf8AndDuplicateJsonFailuresCannotPublish() = workspace { root ->
        val invalid = listOf(
            ArtifactContent("""{"value":"wrong"}""".toByteArray(), "schemas/payload.schema.json"),
            ArtifactContent("""{"value":1,"standardVersion":"wrong"}""".toByteArray()),
            ArtifactContent("""{"value":1,"value":2}""".toByteArray()),
            ArtifactContent(byteArrayOf(0xc3.toByte(), 0x28)))
        for (content in invalid) {
            assertFails { writer().publish(File(root, "published"), mapOf("payload.json" to content)) }
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun receiptVerificationRejectsTamperingDeletionExtraFilesAndChangedManifest() = workspace { root ->
        val destination = File(root, "published")
        val receipt = writer().publish(destination, values())
        val file = File(destination, "nested/unicode.txt")
        val original = file.readBytes()
        file.appendText("changed")
        assertFails { ArtifactPublicationVerifier(schemas).verify(destination) }
        file.delete()
        assertFails { ArtifactPublicationVerifier(schemas).verify(destination) }
        file.writeBytes(original)
        val extra = File(destination, "extra.txt").apply { writeText("extra") }
        assertFails { ArtifactPublicationVerifier(schemas).verify(destination) }
        extra.delete()
        File(destination, AtomicArtifactWriter.MANIFEST).appendText(" ")
        assertFails { ArtifactPublicationVerifier(schemas).verify(destination, receipt.manifest) }
    }

    @Test fun manifestBytesAndCountConsumeTheSamePublicationBudget() = workspace { root ->
        assertFailsWith<IoLimitException> {
            AtomicArtifactWriter(schemas, maximumFiles = 1).publish(File(root, "published"), mapOf("one.txt" to ArtifactContent(byteArrayOf(1))))
        }
        val maximum = ByteArray(InputLimits.MAX_ARTIFACT_BYTES) { 32 }
        assertFailsWith<IoLimitException> { writer().publish(File(root, "published"), (0..3).associate { "$it.txt" to ArtifactContent(maximum) }) }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun manifestIsWrittenLastAfterAllDependentReportsAndMissingRequiredFilesBlockCommit() = workspace { root ->
        val written = mutableListOf<String>()
        val writer = writer().apply { checkpoint = { step, path -> if (step == PublicationStep.AFTER_WRITE) written += path.fileName.toString() } }
        writer.publish(File(root, "published"), values()) { it.put("late.txt", ArtifactContent.encode("late.txt", "late")) }
        assertEquals(AtomicArtifactWriter.MANIFEST, written.last())
        val bundle = FlowArtifactBundleReport(flowName = "test", target = "", strict = false, artifacts = emptyList(),
            requiredArtifacts = listOf("missing.txt"), optionalArtifacts = emptyList(), pipeline = listOf("missing.txt"))
        assertFails { writer().publish(File(root, "missing"), values(), bundle) }
        assertFalse(File(root, "missing").exists())
    }

    private fun workspace(action: (File) -> Unit) {
        val root = createTempDirectory("atomic-artifact-test-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
}
