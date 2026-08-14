package org.flowlang.tests

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.roadmap.RoadmapStreamTransitionAuthority
import org.flowlang.roadmap.RoadmapTransitionPhase
import org.flowlang.serialization.FlowYaml
import org.flowlang.standard.FlowStandardVersions

class PostC1SemanticIntegrityRoadmapTests {
    @Test
    fun repositoryCompletesSi07BeforeActivatingSi08() {
        val report = RoadmapStreamTransitionAuthority(File(".")).analyze()

        assertEquals(RoadmapTransitionPhase.SEMANTIC_INTEGRITY_ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun repositoryReportMatchesLiveSemanticFocusAndArtifactContracts() {
        val releaseState = FlowYaml.readMap(File(RoadmapStreamTransitionAuthority.RELEASE_STATE))
        val roadmapState = requireNotNull(releaseState["roadmapState"] as? Map<*, *>)
        val nextItem = requireNotNull(roadmapState["nextItem"]?.toString())
        val nextItemName = requireNotNull(roadmapState["nextItemName"]?.toString())
        val portfolio = File("REPORT.md").readText()

        assertTrue(
            "Active semantic-integrity item: `$nextItem $nextItemName`" in portfolio,
            "REPORT.md must mirror the active semantic-integrity focus from release-state."
        )
        val expectedContracts =
            "Live artifact contracts are tracked independently: " +
                "Intent `${FlowStandardVersions.INTENT_VERSION}`, " +
                "AST `${FlowStandardVersions.AST_VERSION}`, " +
                "ExecutionPlan `${FlowStandardVersions.EXECUTION_PLAN_VERSION}`, " +
                "execution-plan lowering evidence `${FlowStandardVersions.EXECUTION_PLAN_LOWERING_EVIDENCE_VERSION}`, " +
                "TargetManifest `${FlowStandardVersions.TARGET_MANIFEST_VERSION}` and " +
                "TargetRegistry `${FlowStandardVersions.TARGET_REGISTRY_VERSION}`."
        assertTrue(
            expectedContracts in portfolio,
            "REPORT.md must mirror the typed live artifact-contract versions."
        )
    }

    @Test
    fun activeSemanticItemRequiresExactPredecessorCompletionBoundary() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, ".flow-agent/work-packages/SI-07-align-public-schemas-with-production-acceptance.yaml"),
            "runNumber: 2928",
            "runNumber: 9999"
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals(RoadmapTransitionPhase.SEMANTIC_INTEGRITY_ACTIVE, report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "completed predecessor CI boundary" in it })
    }

    @Test
    fun activeSemanticItemRequiresMatchingRoadmapAndWorkPackageCompletionEvidence() = withRepositoryFixture { root ->
        val roadmap = File(root, RoadmapStreamTransitionAuthority.SEMANTIC_INTEGRITY_ROADMAP)
        replaceRequired(roadmap, "runId: 31773901231", "runId: 31773901232")

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "completed predecessor CI boundary" in it })
    }

    @Test
    fun activeSemanticItemCannotRewriteHistoricalSi01AuthorizationState() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.SEMANTIC_INTEGRITY_ROADMAP),
            "authorizationStatus: missing",
            "authorizationStatus: changed"
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals(RoadmapTransitionPhase.SEMANTIC_INTEGRITY_ACTIVE, report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "must remain explicitly unauthorized" in it })
    }

    @Test
    fun activeSemanticItemRequiresGlobalFocus() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.ROADMAP_INDEX),
            "nextItem: \"SI-08\"",
            "nextItem: \"SI-99\""
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "Roadmap index must select SI-08" in it })
    }

    @Test
    fun activeSemanticItemRequiresExplicitSafeWorkPackagePath() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.SEMANTIC_INTEGRITY_ROADMAP),
            "workPackage: \".flow-agent/work-packages/SI-08-typed-policy-state-lifetime-semantics.yaml\"",
            "workPackage: \"invalid-work-package.yaml\""
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertTrue(report.phase == RoadmapTransitionPhase.C1_0_COMPLETE || report.phase == RoadmapTransitionPhase.INVALID)
        assertEquals("FAIL", report.status)
    }

    @Test
    fun activeSemanticItemCannotAdvanceValidationToUnvalidatedCurrentItem() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.RELEASE_STATE),
            "Flow CI #2928",
            "Flow CI #9999"
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "lastKnownValidation" in it })
    }

    @Test
    fun activeSemanticItemRequiresDeclaredStrategicSource() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, ".flow-agent/work-packages/SI-08-typed-policy-state-lifetime-semantics.yaml"),
            "#18-replace-string-heuristics-with-typed-policy-and-lifetime-semantics",
            "#17-align-public-schemas-with-production-acceptance"
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "explicit, matching and fail-closed work package" in it })
    }

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
