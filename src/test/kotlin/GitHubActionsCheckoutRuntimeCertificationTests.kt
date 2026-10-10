package org.flowlang.conformance

import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.flowlang.adapters.certification.*
import org.flowlang.distribution.reference.ReferenceTargetProjections

/** Synthetic protocol tests do not claim GitHub execution; the dedicated provider job supplies that evidence. */
class GitHubActionsCheckoutRuntimeCertificationTests {
    private val runner = GitHubActionsCheckoutRuntimeCertification
    private fun prepare(envelope: ByteArray = File(runner.WORKFLOW).readBytes(), source: String = "a".repeat(40)) =
        runner.prepare(File("."), source, "b".repeat(40), "20261001.1.0", envelope)
    private fun expected(p: GitHubActionsCheckoutRuntimeCertification.Prepared, id: String) = p.evidence.getValue("expected:$id")

    private fun assess(p: GitHubActionsCheckoutRuntimeCertification.Prepared, values: Map<String, ByteArray> = runner.runIds.associateWith { expected(p, it) },
        corruptSignature: Boolean = false, substitutedKey: Boolean = false): CertificationAdmissionReport {
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val trust = CertificationObservationTrust("a".repeat(32), mapOf("test" to if (substitutedKey) KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public else key.public),
            runner.runIds.map { CertificationAuthorizedRun(it, runner.SCENARIO, it.takeUnless { id -> id == "baseline" }, "test") })
        val signed = values.map { (id, raw) ->
            val observation = runner.observation(p, id, raw)
            val signer = Signature.getInstance("Ed25519"); signer.initSign(key.private)
            signer.update(CertificationObservationAuthentication.signingBytes("test", trust.challenge, observation))
            val signature = signer.sign(); if (corruptSignature) signature[0] = (signature[0].toInt() xor 1).toByte()
            SignedCertificationObservation("test", trust.challenge, observation, signature)
        }
        return AuthenticatedAdapterCertificationAdmission.evaluate(p.bundle, p.bundle.adapter, listOf(p.bound),
            ReferenceTargetProjections.nativeCatalogs.getValue("github-actions"), signed, trust, CertificationEvidenceResolver { p.evidence[it.id] })
    }

    @Test fun realCompilerBindsExactlyOneNativeLeafAndBothMutants() {
        val p = prepare()
        assertContentEquals(p.bound.resolve(p.bound.artifact), p.artifacts.getValue("baseline"))
        assertEquals(3, p.artifacts.values.map(runner::sha256).toSet().size)
        assertEquals("", p.leaves.getValue("omitted-checkout"))
        assertTrue(p.leaves.getValue("substituted-revision").contains(runner.ALTERNATE))
        assertTrue(p.bound.subjects.any { it is CertificationSubject.Leaf && it.reference == "actions/checkout@v4" })
        assertTrue(p.bound.subjects.none { it is CertificationSubject.Structural })
        assertTrue(p.bundle.coverage.filter { it.subject !in p.bound.subjects }.all { it.scenarioIds.isEmpty() })
        val result = assess(p); assertTrue(result.valid, result.findings.toString())
    }

    @Test fun wrongHeadWrongBytesAndSurvivingMutantsAreNotAdmitted() {
        val p = prepare(); val values = runner.runIds.associateWith { expected(p, it) }
        for ((id, bytes) in listOf("baseline" to runner.observationBytes(runner.ALTERNATE, runner.SELECTED_MARKER),
            "baseline" to runner.observationBytes(runner.SELECTED, runner.ALTERNATE_MARKER),
            "omitted-checkout" to expected(p, "baseline"), "substituted-revision" to expected(p, "baseline"))) {
            val result = assess(p, values + (id to bytes))
            assertFalse(result.valid); assertTrue(result.findings.any { it.code == "OBSERVATION_MISMATCH" })
        }
    }

    @Test fun signaturesKeysAndCompleteInventoryAreRequired() {
        val p = prepare()
        assertFalse(assess(p, corruptSignature = true).valid)
        assertFalse(assess(p, substitutedKey = true).valid)
        assertFalse(assess(p, runner.runIds.dropLast(1).associateWith { expected(p, it) }).valid)
        assertFails { runner.observation(p, "unexpected", expected(p, "baseline")) }
    }

    @Test fun staleOrChangedExecutionEnvelopesFailClosed() {
        val bytes = File(runner.WORKFLOW).readBytes()
        assertFails { prepare(bytes + "\n".toByteArray()) }
        assertFails { prepare(source = "main") }
        val p = prepare(); val text = bytes.toString(Charsets.UTF_8)
        for (changed in listOf(text.replace(runner.SELECTED, runner.ALTERNATE),
            text.replace("      # FLOW NATIVE BEGIN baseline\n", ""),
            text.replace("      # FLOW NATIVE END baseline\n", "      # FLOW NATIVE END baseline\n      # FLOW NATIVE END baseline\n"),
            text.replace("      # FLOW NATIVE END omitted-checkout\n", "      - run: echo forged\n      # FLOW NATIVE END omitted-checkout\n")))
            assertFails { runner.validateEnvelope(changed, p.leaves) }
    }

    @Test fun scopeCannotSilentlyGrowToAdditionalStepsGuardsOrBindings() {
        val text = prepare().artifacts.getValue("baseline").toString(Charsets.UTF_8)
        for (changed in listOf(text + "      - run: echo extra\n", text.replace("    steps:", "    if: false\n    steps:"),
            text.replace("        with:", "        if: false\n        with:"), text + "          path: other\n"))
            assertFails { runner.nativeLeaf(changed) }
    }

    @Test fun malformedOversizedDuplicateAndNoncanonicalObservationsFailClosed() {
        val p = prepare()
        for (raw in listOf("", "{}", "{\"revision\":null,\"revision\":null,\"markerSha256\":null}\n",
            "{\"revision\":123,\"markerSha256\":null}\n", "{\"revision\":\"main\",\"markerSha256\":null}\n",
            expected(p, "baseline").toString(Charsets.UTF_8) + "{}", "x".repeat(4097)))
            assertFails { runner.observation(p, "baseline", raw.toByteArray()) }
    }

    @Test fun cleanupRejectsWrongRootsAndDoesNotFollowSymlinks() {
        val root = Files.createTempDirectory("flow-github-cleanup-")
        try {
            val workspace = Files.createDirectory(root.resolve("workspace"))
            val outside = Files.createDirectory(root.resolve("outside"))
            val sentinel = Files.writeString(outside.resolve("keep"), "keep")
            Files.createSymbolicLink(workspace.resolve("link"), outside)
            Files.createDirectories(workspace.resolve("nested")); Files.writeString(workspace.resolve("nested/file"), "remove")
            assertFails { runner.clearWorkspace(outside, workspace) }
            assertFails { runner.clearWorkspace(root.root, root.root) }
            val alias = Files.createSymbolicLink(root.resolve("alias"), workspace)
            assertFails { runner.clearWorkspace(alias, alias) }
            runner.clearWorkspace(workspace, workspace)
            assertTrue(Files.exists(sentinel)); assertTrue(workspace.toFile().listFiles()!!.isEmpty())
            assertContentEquals(runner.observationBytes(null, null), runner.observeWorkspace(workspace.toFile()))
            Files.writeString(workspace.resolve("contamination"), "stale")
            assertFails { runner.observeWorkspace(workspace.toFile()) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun stagedCompositionDoesNotReadContractsFromTheObservedWorkspace() {
        val workspace = Files.createTempDirectory("flow-github-empty-workspace-").toFile()
        val log = File.createTempFile("flow-github-staged-composition-", ".log")
        try {
            val classpath = requireNotNull(System.getProperty("flow.conformance.test.classpath"))
            require(classpath.isNotBlank())
            val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath, "-cp", classpath,
                GitHubActionsStagedCompositionProbe::class.java.name, File(".").canonicalPath)
                .directory(workspace).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Staged composition timed out") }
            assertEquals(0, process.exitValue(), log.readText())
            assertTrue(workspace.listFiles()!!.isEmpty())
        } finally { workspace.deleteRecursively(); log.delete() }
    }
}

/** Runs in a fresh JVM with an empty CWD to expose accidental ambient registry or policy defaults. */
object GitHubActionsStagedCompositionProbe {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single())
        GitHubActionsCheckoutRuntimeCertification.prepare(root, "a".repeat(40), "b".repeat(40), "20261001.1.0",
            File(root, GitHubActionsCheckoutRuntimeCertification.WORKFLOW).readBytes())
    }
}
