package org.flowlang.tests

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.roadmap.RoadmapStreamTransitionAuthority
import org.flowlang.roadmap.RoadmapTransitionPhase
import org.flowlang.serialization.FlowYaml
import org.flowlang.standard.FlowStandardVersions

class SemanticIntegrityRoadmapTests {
    @Test
    fun repositoryCompletesSi08AndReturnsToNoSuccessorGlobalFocus() {
        val report = RoadmapStreamTransitionAuthority(File(".")).analyze()

        assertEquals(RoadmapTransitionPhase.C1_0_COMPLETE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertTerminalSemanticIntegrity(File("."))
    }

    @Test
    fun repositoryReportMatchesCompletedSemanticStreamAndArtifactContracts() {
        val releaseState = FlowYaml.readMap(File(RoadmapStreamTransitionAuthority.RELEASE_STATE))
        val roadmapState = requireMap(releaseState, "roadmapState")
        val portfolio = File("REPORT.md").readText()

        assertEquals("conformance", roadmapState["primaryStream"]?.toString())
        assertTrue(roadmapState["nextItem"]?.toString().orEmpty().isBlank())
        assertTrue(roadmapState["nextItemName"]?.toString().orEmpty().isBlank())
        assertTrue(roadmapState["nextItemStream"]?.toString().orEmpty().isBlank())
        assertTrue(
            "Completed semantic-integrity item: `SI-08 Typed Policy and State-Lifetime Semantics` (`completed`, Flow CI #2943)" in portfolio,
            "REPORT.md must record the terminal SI-08 implementation boundary."
        )
        assertTrue(
            "Active semantic-integrity item: none" in portfolio,
            "REPORT.md must not publish a successor before a distinct cross-stream activation."
        )
        val expectedContracts =
            "Live artifact contracts are tracked independently: " +
                "Intent `${FlowStandardVersions.INTENT_VERSION}`, " +
                "AST `${FlowStandardVersions.AST_VERSION}`, " +
                "ExecutionPlan `${FlowStandardVersions.EXECUTION_PLAN_VERSION}`, " +
                "WorkflowExecutionPlanSet `${FlowStandardVersions.WORKFLOW_EXECUTION_PLAN_SET_VERSION}`, " +
                "execution-plan lowering evidence `${FlowStandardVersions.EXECUTION_PLAN_LOWERING_EVIDENCE_VERSION}`, " +
                "TargetManifest `${FlowStandardVersions.TARGET_MANIFEST_VERSION}` and " +
                "TargetRegistry `${FlowStandardVersions.TARGET_REGISTRY_VERSION}`."
        assertTrue(expectedContracts in portfolio, "REPORT.md must mirror the typed live artifact-contract versions.")
    }

    @Test
    fun terminalSemanticStreamRequiresMatchingRoadmapAndWorkPackageEvidence() = withRepositoryFixture { root ->
        val workPackage = File(root, ".flow-agent/work-packages/typed-policy-state-lifetime-semantics.yaml")
        replaceRequired(workPackage, "runId: 31802519219", "runId: 31802519220")

        assertFailsWith<AssertionError> { assertTerminalSemanticIntegrity(root) }
    }

    @Test
    fun terminalSemanticStreamRequiresActualSi08MergeCommit() = withRepositoryFixture { root ->
        val roadmap = File(root, RoadmapStreamTransitionAuthority.SEMANTIC_INTEGRITY_ROADMAP)
        replaceRequired(
            roadmap,
            "completionMergeCommit: \"03c3bcb9c8d4f428b4302c2c35d098848793505e\"",
            "completionMergeCommit: \"1111111111111111111111111111111111111111\""
        )

        assertFailsWith<AssertionError> { assertTerminalSemanticIntegrity(root) }
    }

    @Test
    fun terminalSemanticStreamCannotPublishUnactivatedGlobalSuccessor() = withRepositoryFixture { root ->
        val roadmap = File(root, RoadmapStreamTransitionAuthority.ROADMAP_INDEX)
        replaceRequired(roadmap, "  nextItem: \"\"", "  nextItem: \"EF-01\"")

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals(RoadmapTransitionPhase.C1_0_COMPLETE, report.phase)
        assertEquals("FAIL", report.status)
    }

    @Test
    fun terminalSemanticStreamCannotRemainActive() = withRepositoryFixture { root ->
        val roadmap = File(root, RoadmapStreamTransitionAuthority.SEMANTIC_INTEGRITY_ROADMAP)
        replaceRequired(roadmap, "status: completed", "status: active")

        assertFailsWith<AssertionError> { assertTerminalSemanticIntegrity(root) }
    }

    @Test
    fun historicalSi01AuthorizationStateRemainsImmutableAfterStreamCompletion() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.SEMANTIC_INTEGRITY_ROADMAP),
            "authorizationStatus: missing",
            "authorizationStatus: changed"
        )

        assertFailsWith<AssertionError> { assertTerminalSemanticIntegrity(root) }
    }

    private fun assertTerminalSemanticIntegrity(root: File) {
        val roadmap = FlowYaml.readMap(File(root, RoadmapStreamTransitionAuthority.SEMANTIC_INTEGRITY_ROADMAP))
        val decision = requireMap(roadmap, "currentDecision")
        val si08Item = requireItem(roadmap, "SI-08")
        val workPackage = FlowYaml.readMap(
            File(root, ".flow-agent/work-packages/typed-policy-state-lifetime-semantics.yaml")
        )
        val authorization = requireMap(workPackage, "authorization")
        val roadmapBoundary = requireMap(si08Item, "completionBoundary")
        val workPackageBoundary = requireMap(workPackage, "completionBoundary")

        assertEquals("completed", roadmap["status"]?.toString())
        assertEquals("SI-08", decision["completedItem"]?.toString())
        assertEquals("Typed Policy and State-Lifetime Semantics", decision["completedItemName"]?.toString())
        assertTrue(decision["nextItem"]?.toString().orEmpty().isBlank())
        assertTrue(decision["nextItemName"]?.toString().orEmpty().isBlank())
        assertEquals("completed", si08Item["status"]?.toString())
        assertEquals("complete", workPackage["status"]?.toString())
        assertEquals("completed", authorization["status"]?.toString())
        assertEquals(roadmapBoundary, workPackageBoundary)
        assertEquals("passed", roadmapBoundary["status"]?.toString())
        assertEquals("Flow CI", roadmapBoundary["workflow"]?.toString())
        assertEquals("2943", roadmapBoundary["runNumber"]?.toString())
        assertEquals("31802519219", roadmapBoundary["runId"]?.toString())
        assertEquals("e5bc5cd82d591a6b744650478070b6c4919d60cf", roadmapBoundary["exactHead"]?.toString())
        assertEquals("7a9a349c929a9b2fccadd16e5f5411f77156b7c6", roadmapBoundary["mergeCandidate"]?.toString())
        assertEquals(
            "03c3bcb9c8d4f428b4302c2c35d098848793505e",
            si08Item["completionMergeCommit"]?.toString()
        )
        assertEquals(
            si08Item["completionMergeCommit"]?.toString(),
            workPackage["completionMergeCommit"]?.toString()
        )
        assertEquals("missing", requireItemHistory(roadmap, "SI-01")["authorizationStatus"]?.toString())
    }

    private fun requireItem(root: Map<String, Any?>, version: String): Map<*, *> =
        (root["items"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.singleOrNull { it["version"]?.toString() == version }
            ?: error("Semantic-integrity roadmap item '$version' is missing or duplicated.")

    private fun requireItemHistory(root: Map<String, Any?>, id: String): Map<*, *> =
        (root["historicalEvents"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.singleOrNull { it["id"]?.toString() == id }
            ?: error("Semantic-integrity historical event '$id' is missing or duplicated.")

    private fun requireMap(root: Map<*, *>, key: String): Map<*, *> =
        root[key] as? Map<*, *> ?: error("Required map '$key' is missing.")

    private fun withRepositoryFixture(block: (File) -> Unit) {
        val root = createTempDirectory("flow-semantic-integrity-roadmap").toFile()
        listOf(".flow-agent", "docs", "standard/architecture", "src/main/kotlin").forEach { path ->
            val source = File(path)
            if (source.exists()) source.copyRecursively(File(root, path), overwrite = true)
        }
        block(root)
    }

    private fun replaceRequired(file: File, old: String, new: String) {
        val source = file.readText()
        require(old in source) { "Fixture mutation source is missing in ${file.path}: $old" }
        file.writeText(source.replaceFirst(old, new))
    }
}
