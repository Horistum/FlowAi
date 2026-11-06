package org.flowlang.cli.honest

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.artifacts.ArtifactPublicationVerifier

class ProposalCommandTests {
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/flow/reference-contracts/examples/proposals/$name.json")).bufferedReader().use { it.readText() }
    private val valid get() = fixture("accepted")
    private fun workspace(action: (File) -> Unit) {
        val root = createTempDirectory("proposal-cli-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
    private fun run(root: File, text: String = valid, vararg options: String): ProposalCliResult {
        val file = File(root, "input.json").apply { writeText(text) }
        return assertIs<ProposalCliResult>(executeCli(arrayOf("proposal", file.path, *options)))
    }

    @Test fun acceptedProposalPublishesSchemaCheckedReviewAndPlansAtomically() = workspace { root ->
        val output = File(root, "review")
        val result = run(root, valid, "--out", output.path)
        assertEquals(0, result.exitCode)
        assertEquals("accepted", result.review.semanticAcceptance)
        assertEquals("unassessed", result.review.requestFidelity)
        assertEquals("caller-declared", result.review.providerAttribution)
        assertNotNull(result.review.graphDigest)
        assertTrue(result.artifacts.all { it.persisted })
        assertTrue(result.artifacts.none { it.role == CliArtifactRole.TARGET_MANIFEST || it.role == CliArtifactRole.RENDERED_TARGET })
        val publication = assertNotNull(ArtifactPublicationVerifier().verify(output).publication)
        assertEquals("JSON_SCHEMA", publication.coveredFiles.single { it.path == "proposal-review.json" }.validation)
        val before = output.listFiles()!!.associate { it.name to it.readBytes().toList() }
        assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("proposal", File(root, "input.json").path, "--out", output.path)))
        assertEquals(before, output.listFiles()!!.associate { it.name to it.readBytes().toList() })
    }

    @Test fun semanticRejectionRetainsCodesAndPublishesNoPlanEvenWithEmptyProviderQuestions() = workspace { root ->
        val result = run(root, fixture("rejected"), "--out", File(root, "review").path)
        assertEquals(4, result.exitCode)
        assertEquals("rejected", result.review.semanticAcceptance)
        assertEquals("PROPOSAL_REVIEW", result.review.stage)
        assertTrue(result.review.diagnostics.any { it.code == "UNKNOWN_STEP_DEPENDENCY" })
        assertEquals(listOf("proposal-review.json"), result.review.artifacts)
        assertNotNull(result.review.compilationSource)
        assertNull(result.review.graphDigest)
    }

    @Test fun malformedInputPublishesOnlySafeDiagnostics() = workspace { root ->
        val result = run(root, valid.replace("\"DRAFT\"", "\"credential-do-not-echo\""), "--out", File(root, "review").path)
        assertEquals(2, result.exitCode)
        assertEquals("INPUT", result.review.stage)
        assertFalse(File(root, "review/proposal-review.json").readText().contains("credential-do-not-echo"))
        assertEquals(listOf("proposal-review.json"), result.review.artifacts)
    }

    @Test fun requiredQuestionsPreservePlanningButWithholdAllTargetArtifacts() = workspace { root ->
        val text = valid.replace("\"guardrails\":", "\"openQuestions\":[{\"id\":\"approval\",\"field\":\"environment\",\"severity\":\"REQUIRED\",\"question\":\"Which environment?\"}],\"guardrails\":")
        val result = run(root, text, "--target", "github-actions", "--render")
        assertEquals(3, result.exitCode)
        assertEquals("accepted", result.review.semanticAcceptance)
        assertEquals("withheld-for-review", result.review.targetDisposition)
        assertTrue(result.artifacts.none { it.role in setOf(CliArtifactRole.TARGET_MANIFEST, CliArtifactRole.RENDERED_TARGET) })
        assertEquals("$.response.report.openQuestions[0]", result.review.reviewItems.single().field)
    }

    @Test fun unmitigatedHighRiskAlsoWithholdsRendering() = workspace { root ->
        val text = valid.replace("\"guardrails\":", "\"risks\":[{\"id\":\"risk\",\"severity\":\"HIGH\",\"message\":\"Review required\"}],\"guardrails\":")
        assertEquals(3, run(root, text, "--target", "github-actions", "--render").exitCode)
    }

    @Test fun missingDecisionsRemainVisibleAndRequireReview() = workspace { root ->
        val text = valid.replace("\"guardrails\":", "\"missingDecisions\":[\"Choose environment\"],\"guardrails\":")
        val result = run(root, text, "--target", "jenkins", "--render")
        assertEquals(3, result.exitCode)
        assertEquals("$.response.report.missingDecisions[0]", result.review.reviewItems.single().field)
        assertTrue(result.presentation.items.filterIsInstance<CliPresentationItem.Section>().any { it.title == "WORKFLOW EXECUTION PLAN SET" })
    }

    @Test fun correctedProposalMustCrossCompilationAgain() = workspace { root ->
        val blocked = run(root, fixture("rejected"))
        val corrected = run(root, valid)
        assertEquals(4, blocked.exitCode)
        assertEquals(0, corrected.exitCode)
        assertNotEquals(blocked.review.inputSource!!.sha256, corrected.review.inputSource!!.sha256)
        assertNotEquals(blocked.review.compilationSource!!.sha256, corrected.review.compilationSource!!.sha256)
        assertNull(blocked.review.graphDigest)
        assertNotNull(corrected.review.graphDigest)
    }

    @Test fun compilerDiagnosticsDoNotEchoUnknownAuthoredDependencyValues() = workspace { root ->
        val text = fixture("rejected").replace("missing-checkout", "credential-do-not-echo")
        val result = run(root, text, "--out", File(root, "review").path)
        assertEquals(4, result.exitCode)
        assertTrue(result.review.diagnostics.any { it.code == "UNKNOWN_STEP_DEPENDENCY" })
        assertFalse(File(root, "review/proposal-review.json").readText().contains("credential-do-not-echo"))
    }

    @Test fun unsupportedTargetKeepsReviewEvidenceWithoutClaimingExecutableRendering() = workspace { root ->
        val result = run(root, valid, "--target", "github-actions", "--render")
        assertEquals(3, result.exitCode)
        assertEquals("review_only", result.review.targetDisposition)
        assertFalse(result.review.renderAuthorized)
        assertTrue(result.artifacts.any { it.role == CliArtifactRole.REVIEW_DOCUMENT })
        assertTrue(result.artifacts.none { it.role == CliArtifactRole.RENDERED_TARGET })
    }

    @Test fun targetMustBeExplicitKnownAndConsistentWithTheRequest() = workspace { root ->
        assertEquals("CLI_TARGET_REQUIRED_FOR_RENDER", run(root, valid, "--render").review.diagnostics.single().code)
        assertEquals("PROPOSAL_TARGET_UNKNOWN", run(root, valid, "--target", "missing").review.diagnostics.single().code)
        val text = valid.replace("\"context\": {}", "\"context\": {\"target\":\"jenkins\"}")
        assertEquals("PROPOSAL_TARGET_CONFLICT", run(root, text, "--target", "github-actions").review.diagnostics.single().code)
    }

    @Test fun declaredProfilesWithoutProvidersRetainVersionOnePlansAndDiagnosticReceipts() = workspace { root ->
        for (target in listOf("local", "argo-workflows", "azure-devops")) {
            val out = File(root, target)
            val result = run(root, valid, "--target", target, "--render", "--out", out.path)
            assertEquals(4, result.exitCode)
            assertEquals(1, result.review.formatVersion)
            assertEquals("MATERIALIZATION", result.review.stage)
            assertEquals("accepted", result.review.semanticAcceptance)
            assertEquals("unassessed", result.review.requestFidelity)
            assertEquals("blocked", result.review.targetDisposition)
            assertEquals(target, result.review.target)
            assertFalse(result.review.renderAuthorized)
            assertEquals("PROPOSAL_TARGET_PROJECTION_UNAVAILABLE", result.review.diagnostics.last().code)
            assertNotNull(result.review.graphDigest)
            assertTrue(File(out, "canonical-execution-plan.json").isFile)
            assertTrue(result.artifacts.none { it.role in setOf(CliArtifactRole.TARGET_MANIFEST, CliArtifactRole.RENDERED_TARGET, CliArtifactRole.REVIEW_DOCUMENT) })
            assertNotNull(ArtifactPublicationVerifier().verify(out).publication)
        }
    }

    @Test fun unavailableTargetCannotOverrideCompilerRejectionOrProviderReview() = workspace { root ->
        val rejected = run(root, fixture("rejected"), "--target", "local", "--render")
        assertEquals("rejected", rejected.review.semanticAcceptance)
        assertEquals(listOf("proposal-review.json"), rejected.review.artifacts)
        assertFalse(rejected.review.diagnostics.any { it.code == "PROPOSAL_TARGET_PROJECTION_UNAVAILABLE" })
        val undecided = valid.replace("\"guardrails\":", "\"missingDecisions\":[\"Choose environment\"],\"guardrails\":")
        val reviewed = run(root, undecided, "--target", "local", "--render")
        assertEquals(3, reviewed.exitCode)
        assertEquals("withheld-for-review", reviewed.review.targetDisposition)
    }

    @Test fun renderingUsesTheExistingAuthorityAndItsReceipt() = workspace { root ->
        val result = run(root, valid, "--target", "jenkins", "--render", "--out", File(root, "review").path)
        assertEquals(0, result.exitCode)
        assertTrue(result.review.renderAuthorized)
        assertTrue(result.artifacts.any { it.role == CliArtifactRole.RENDERED_TARGET })
        assertTrue(result.artifacts.any { it.name == "target-artifact-evidence.json" })
        assertNotNull(ArtifactPublicationVerifier().verify(File(root, "review")).publication)
        assertTrue(result.presentation.items.filterIsInstance<CliPresentationItem.Text>().any {
            it.value == File(root, "review/Jenkinsfile").readText()
        })
    }

    @Test fun declaredTargetCanBeUsedWithoutRewritingSourceProvenance() = workspace { root ->
        val text = valid.replace("\"context\": {}", "\"context\": {\"target\":\"jenkins\"}")
        val result = run(root, text, "--render")
        assertEquals(0, result.exitCode)
        assertTrue(result.review.renderAuthorized)
        assertEquals("jenkins", result.review.target)
        assertTrue(result.artifacts.none { it.persisted })
        assertTrue(result.presentation.items.filterIsInstance<CliPresentationItem.Section>().any { it.title == "TARGET ARTIFACT EVIDENCE RECEIPT" })
        assertTrue(result.presentation.items.filterIsInstance<CliPresentationItem.Text>().any { it.value.contains("pipeline {") })
    }

    @Test fun multiWorkflowPlanningPreservesAllWorkflowsAndTargetingRemainsBlocked() = workspace { root ->
        val tree = org.flowlang.cli.Json.mapper.readTree(valid) as com.fasterxml.jackson.databind.node.ObjectNode
        val workflows = tree.path("response").path("normalizedIntent").path("workflows") as com.fasterxml.jackson.databind.node.ArrayNode
        val other = workflows[0].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("name", "another-workflow")
        val steps = other.path("steps") as com.fasterxml.jackson.databind.node.ArrayNode
        steps.forEach { step ->
            val value = step as com.fasterxml.jackson.databind.node.ObjectNode
            value.put("id", value.path("id").asText() + "-other")
            val dependencies = value.path("requires")
            if (dependencies.isArray) {
                val rewritten = dependencies.map { it.asText() + "-other" }
                value.putArray("requires").also { array -> rewritten.forEach { array.add(it) } }
            }
        }
        workflows.add(other)
        val text = tree.toString()
        val neutral = run(root, text)
        assertEquals(0, neutral.exitCode)
        assertEquals(listOf("workflow-execution-plan-set.json", "proposal-review.json"), neutral.review.artifacts)
        assertEquals(4, run(root, text, "--target", "github-actions", "--render").exitCode)
    }

    @Test fun existingImageAnchorNeverInventsBuildOrRollbackToPass() = workspace { root ->
        val result = run(root, fixture("existing-image-blocked"), "--target", "github-actions", "--render")
        assertTrue(result.exitCode in setOf(3, 4))
        assertFalse(result.review.renderAuthorized)
        assertTrue(result.artifacts.none { it.role == CliArtifactRole.RENDERED_TARGET })
    }

    @Test fun invalidArgumentsAndPublicationFailuresCannotExposePartialResults() = workspace { root ->
        val file = File(root, "input.json").apply { writeText(valid) }
        for (args in listOf(arrayOf("proposal"), arrayOf("proposal", file.path, "--unknown"), arrayOf("proposal", file.path, "--render", "--render"))) {
            assertEquals(2, executeCli(args).exitCode)
        }
        val occupied = File(root, "occupied").apply { mkdir(); resolve("sentinel").writeText("keep") }
        val result = executeCli(arrayOf("proposal", file.path, "--out", occupied.path))
        assertIs<CliExecutionResult.Rejected>(result)
        assertTrue(result.artifacts.isEmpty())
        assertEquals(listOf("sentinel"), occupied.list()!!.toList())
    }
}
