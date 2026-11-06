package org.flowlang.frontend.ai

import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.compiler.CompilationResult
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.io.InputLimits
import org.flowlang.modules.ModuleRegistry

class ExternalAiProposalReaderTests {
    private val valid get() = File("examples/proposals/accepted.json").readText()
    private fun withInput(text: String, action: (File) -> Unit) {
        val root = createTempDirectory("proposal-reader-").toFile()
        try { action(File(root, "input.json").apply { writeText(text) }) } finally { root.deleteRecursively() }
    }
    private fun reject(text: String) = withInput(text) { assertFailsWith<ProposalInputException> { ExternalAiProposalReader.read(it) } }

    @Test fun inputBytesAndInterpretedSourceRemainDistinctWhileCanonicalMeaningConverges() = withInput(valid) { file ->
        val read = ExternalAiProposalReader.read(file)
        val bytes = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(digest, read.inputSource.sha256)
        assertEquals(file.absolutePath, read.proposal.sourceIdentity)
        file.writeText("invalid after capture")
        val compiler = FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File("modules")))
        val unit = assertIs<CompilationResult.Accepted>(ReviewedAiProposalFrontend(compiler).compile(read.proposal)).unit
        val direct = assertIs<CompilationResult.Accepted>(IntentYamlFrontend(compiler).compile(File("examples/intent/checkout-build-image.intent.yaml"))).unit
        assertEquals(direct.graphDigest, unit.graphDigest)
        assertNotEquals(read.inputSource.sha256, unit.source.sha256)
        assertNotEquals(direct.source.sha256, unit.source.sha256)
        val changed = read.proposal.copy(providerId = "another-provider", request = read.proposal.request.copy(userText = "Another authored request."))
        val revised = assertIs<CompilationResult.Accepted>(ReviewedAiProposalFrontend(compiler).compile(changed)).unit
        assertEquals(unit.graphDigest, revised.graphDigest)
        assertNotEquals(unit.source.sha256, revised.source.sha256)
    }

    @Test fun rejectsDuplicateKeysTrailingContentAndUnknownFieldsAtEveryBoundary() {
        reject(valid.replace("\"formatVersion\": 1", "\"formatVersion\": 1, \"formatVersion\": 1"))
        reject(valid + "{}")
        reject(valid.replaceFirst("{", "{\"sourceIdentity\":\"forged\","))
        reject(valid.replace("\"userText\":", "\"unexpected\":true,\"userText\":"))
        reject(valid.replace("\"normalizedIntent\":", "\"unexpected\":true,\"normalizedIntent\":"))
        reject(valid.replace("\"classification\":", "\"unexpected\":true,\"classification\":"))
        reject(valid.replace("\"capability\":", "\"unexpected\":true,\"capability\":"))
    }

    @Test fun rejectsVersionsEnumsNullsAndScalarCoercion() {
        reject(valid.replace("\"formatVersion\": 1", "\"formatVersion\": 2"))
        reject(valid.replace("\"formatVersion\": 1", "\"formatVersion\": \"1\""))
        reject(valid.replace("\"formatVersion\": 1", "\"formatVersion\": 1.5"))
        reject(valid.replace("\"DRAFT\"", "\"invented\""))
        reject(valid.replace("\"DRAFT\"", "1"))
        reject(valid.replace("\"CHECKOUT\"", "\"invented\""))
        reject(valid.replace("\"required\": true", "\"required\": \"true\""))
        reject(valid.replace("\"providerId\": \"example-external-author\"", "\"providerId\": null"))
        reject(valid.replace("\"providerId\": \"example-external-author\"", "\"providerId\": \" \""))
        reject(valid.replace("\"context\": {}", "\"context\": {\"knownSystems\":{\"source\":null}}"))
        reject(valid.replace("\"guardrails\":", "\"missingDecisions\":[null],\"guardrails\":"))
        reject(valid.replace("\"guardrails\":", "\"entities\":{\"source\":null},\"guardrails\":"))
    }

    @Test fun parserDiagnosticsDoNotEchoAuthoredSecretsOrUnknownKeys() = withInput(valid.replace("\"DRAFT\"", "\"credential-do-not-echo\"")) { file ->
        val failure = assertFailsWith<ProposalInputException> { ExternalAiProposalReader.read(file) }
        assertFalse(failure.message!!.contains("credential-do-not-echo"))
        assertEquals("PROPOSAL_INVALID_INPUT", failure.code)
        assertNotNull(failure.inputSource)
    }

    @Test fun preservesExistingInlineSystemConfigurationAndLegacyDefaults() {
        val minimal = """{"formatVersion":1,"providerId":"fixture","request":{"userText":"Read the source"},"response":{"normalizedIntent":{"name":"legacy","systems":[{"name":"source","type":"git","url":"https://example.invalid/repository"}]},"report":{"mode":"DRAFT","classification":{"type":"custom","confidence":0},"confidence":{"overall":0,"intentType":0,"entities":0,"dependencies":0,"safety":0}}}}"""
        withInput(minimal) {
            val read = ExternalAiProposalReader.read(it)
            assertEquals("2.0", read.proposal.response.normalizedIntent.intentVersion)
            assertTrue("url" in read.proposal.response.normalizedIntent.systems.single().config)
        }
    }

    @Test fun rejectsInvalidUtf8OversizedAndDeeplyNestedInputs() {
        withInput(valid) {
            it.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertFailsWith<ProposalInputException> { ExternalAiProposalReader.read(it) }
            it.writeText(" ".repeat(InputLimits.MAX_SOURCE_BYTES + 1))
            assertFailsWith<ProposalInputException> { ExternalAiProposalReader.read(it) }
        }
        reject("[".repeat(InputLimits.MAX_DEPTH + 1) + "0" + "]".repeat(InputLimits.MAX_DEPTH + 1))
    }
}
