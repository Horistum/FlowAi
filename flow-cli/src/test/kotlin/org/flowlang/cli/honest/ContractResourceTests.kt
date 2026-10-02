package org.flowlang.cli.honest

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.cli.Json
import org.flowlang.distribution.reference.*
import org.flowlang.modules.ModuleRegistry

class ContractResourceTests {
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test fun packagedContractsCarryExactBytesAndStableProvenance() {
        ContractResourceResolver().open().use { resources ->
            assertTrue(resources.provenance.isNotEmpty())
            assertEquals(resources.provenance.map { it.path }.sorted().distinct(), resources.provenance.map { it.path })
            for (record in resources.provenance) {
                val bytes = File(resources.root, record.path).readBytes()
                assertEquals(digest(bytes), record.sha256)
                assertEquals(bytes.size.toLong(), record.sizeBytes)
                assertEquals(ContractResourceOrigin.CLASSPATH, record.origin)
                assertEquals("classpath:/${ContractResourceResolver.PREFIX}/${record.path}", record.location)
            }
            assertTrue(ModuleRegistry.fromDirectory(File(resources.root, "modules")).allModules().isNotEmpty())
            assertFailsWith<UnsupportedOperationException> {
                (resources.provenance as MutableList<ContractResourceProvenance>).clear()
            }
        }
    }

    @Test fun explicitOverrideSnapshotsAuthoredBytesWithoutFallingBack() {
        ContractResourceResolver().open().use { external ->
            val path = "examples/intent/build-test-deploy.intent.yaml"
            val file = File(external.root, path)
            file.writeText(file.readText().replace("name: build-test-deploy", "name: explicit-resource-input"))
            val result = executeCli(arrayOf("intent", "--contracts", external.root.path))
            assertIs<CliExecutionResult.TargetNeutral>(result)
            assertTrue(Json.mapper.writeValueAsString(result.presentation).contains("explicit-resource-input"))
            ContractResourceResolver().open(external.root).use { snapshot ->
                val record = snapshot.provenance.single { it.path == path }
                assertEquals(ContractResourceOrigin.EXTERNAL, record.origin)
                assertEquals(digest(file.readBytes()), record.sha256)
                val captured = File(snapshot.root, path).readText()
                file.writeText("later mutation")
                assertEquals(captured, File(snapshot.root, path).readText())
            }
            file.delete()
            assertFailsWith<IllegalArgumentException> { ContractResourceResolver().open(external.root) }
        }
    }

    @Test fun externalSymbolicFilesAndDirectoriesCannotEscapeTheInventory() {
        ContractResourceResolver().open().use { external ->
            val modules = File(external.root, "modules")
            val moved = File(external.root, "moved-modules")
            assertTrue(modules.renameTo(moved))
            Files.createSymbolicLink(modules.toPath(), moved.toPath())
            assertFailsWith<IllegalArgumentException> { ContractResourceResolver().open(external.root) }
            Files.delete(modules.toPath())
            assertTrue(moved.renameTo(modules))
            val file = File(modules, "standard.yaml")
            val bytes = file.readBytes()
            file.delete()
            Files.createSymbolicLink(file.toPath(), File(modules, "git.yaml").toPath())
            assertFailsWith<IllegalArgumentException> { ContractResourceResolver().open(external.root) }
            Files.delete(file.toPath())
            file.writeBytes(bytes)
        }
    }

    @Test fun snapshotLifetimeIncludesExceptionalCommandCompletion() {
        var materialized: File? = null
        assertFailsWith<IllegalArgumentException> {
            ContractResourceResolver().open().use { snapshot ->
                materialized = snapshot.root
                throw IllegalArgumentException("command failed")
            }
        }
        assertFalse(assertNotNull(materialized).exists())
    }

    @Test fun missingCorruptDuplicateOrEscapingClasspathResourcesFailClosed() {
        val root = createTempDirectory("resource-loader-test-").toFile()
        try {
            val first = File(root, "first").apply { mkdirs() }
            val second = File(root, "second").apply { mkdirs() }
            val prefix = File(first, ContractResourceResolver.PREFIX).apply { mkdirs() }
            val content = File(prefix, "modules/one.yaml").apply { parentFile.mkdirs(); writeText("authored") }
            val index = File(first, ContractResourceResolver.INDEX)
            val valid = "${digest(content.readBytes())}\tmodules/one.yaml\n"
            URLClassLoader(arrayOf(first.toURI().toURL()), null).use { loader ->
                val resolver = ContractResourceResolver(loader)
                assertFailsWith<IllegalStateException> { resolver.open() }
                for (invalid in listOf("", valid + valid, "bad\tmodules/one.yaml\n", valid.replace("modules/one.yaml", "../one.yaml"),
                    valid.replace("modules/one.yaml", "/modules/one.yaml"))) {
                    index.writeText(invalid)
                    assertFailsWith<IllegalStateException>(invalid) { resolver.open() }
                }
                index.writeText(valid)
                resolver.open().use { assertEquals("authored", File(it.root, "modules/one.yaml").readText()) }
                content.writeText("corrupt")
                assertFailsWith<IllegalStateException> { resolver.open() }
                content.delete()
                assertFailsWith<IllegalStateException> { resolver.open() }
            }
            File(second, ContractResourceResolver.INDEX).apply { parentFile.mkdirs(); writeText(valid) }
            URLClassLoader(arrayOf(first.toURI().toURL(), second.toURI().toURL()), null).use { loader ->
                assertFailsWith<IllegalStateException> { ContractResourceResolver(loader).open() }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun installedContractsPreserveTargetMaturityAndExecutableRendering() {
        val targets = assertIs<CliExecutionResult.Completed>(executeCli(arrayOf("targets")))
        val maturity = targets.presentation.items.filterIsInstance<CliPresentationItem.Section>()
            .single { it.title == "FLOW TARGET MATURITY REPORT" }.value
        assertEquals("PASS", Json.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(maturity)["status"].asText())
        ContractResourceResolver().open().use { resources ->
            for (target in listOf("jenkins", "github-actions")) {
                val input = File(resources.root, "examples/intent/checkout-build-image.intent.yaml")
                val result = assertIs<CliExecutionResult.Targeted>(executeCli(arrayOf("intent", input.path, "--target", target, "--render")))
                val executable = target == "jenkins"
                assertEquals(if (executable) 0 else 3, result.exitCode, Json.mapper.writeValueAsString(result.presentation))
                assertEquals(if (executable) CliTargetEvidenceOutcome.EXECUTABLE else CliTargetEvidenceOutcome.REVIEW_ONLY, result.evidence.outcome)
                val expected = if (executable) "Jenkinsfile" else "flow-github-actions-review.yaml"
                val role = if (executable) CliArtifactRole.RENDERED_TARGET else CliArtifactRole.REVIEW_DOCUMENT
                assertTrue(result.artifacts.any { it.name == expected && it.role == role })
                if (!executable) assertTrue(result.artifacts.none { it.role == CliArtifactRole.RENDERED_TARGET || it.name == "github-actions.yml" })
            }
        }
    }

    @Test fun missingOverrideIsRejectedBeforeAnyOutputWrite() {
        val root = createTempDirectory("missing-contracts-").toFile()
        try {
            val output = File(root, "output")
            val result = assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("intent", "--contracts", root.path, "--out", output.path)))
            assertEquals(CliDiagnosticCode.INVALID_INPUT, result.diagnostic.code)
            assertFalse(output.exists())
            assertTrue(result.artifacts.isEmpty())
            assertEquals(listOf("CLI DIAGNOSTIC FAILURE"), result.presentation.items.filterIsInstance<CliPresentationItem.Section>().map { it.title })
        } finally { root.deleteRecursively() }
    }

    @Test fun maintenanceSafetyUsesSelectedNotesAndRejectsInvalidOverrides() {
        val request = "Run Kubernetes maintenance in namespace payments with dry-run."
        for ((environment, requiredField) in listOf("prod" to "safety.maintenance.window", "prod-eu" to "safety.environment", "dev" to null)) {
            val result = assertIs<CliExecutionResult.Completed>(executeCli(arrayOf("normalize", request, "--environment", environment)))
            val report = result.presentation.items.filterIsInstance<CliPresentationItem.Section>()
                .single { it.title == "AI INTENT NORMALIZATION REPORT" }.value
            val fields = Json.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(report)["openQuestions"].map { it["field"].asText() }
            if (requiredField == null) assertTrue(fields.none { it == "safety.environment" || it == "safety.maintenance.window" })
            else assertTrue(requiredField in fields)
        }
        ContractResourceResolver().open().use { external ->
            File(external.root, "standard/notes/packages/safety-core.yaml").writeText("invalid: [notes")
            val out = File(external.root, "output")
            val rejected = executeCli(arrayOf("normalize", request, "--environment", "prod", "--contracts", external.root.path, "--out", out.path))
            assertIs<CliExecutionResult.Rejected>(rejected)
            assertFalse(out.exists())
            assertTrue(rejected.artifacts.isEmpty())
        }
    }
}
