package org.flowlang.tests

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.roadmap.RoadmapStreamTransitionAuthority
import org.flowlang.roadmap.RoadmapTransitionPhase

class PostC1SemanticIntegrityRoadmapTests {
    @Test
    fun repositoryCompletesSi06BeforeActivatingSi07() {
        val report = RoadmapStreamTransitionAuthority(File(".")).analyze()

        assertEquals(RoadmapTransitionPhase.SEMANTIC_INTEGRITY_ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun activeSemanticItemRequiresExactPredecessorCompletionBoundary() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, ".flow-agent/work-packages/SI-06-eliminate-remaining-silent-authored-value-coercion.yaml"),
            "runNumber: 2919",
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
        replaceRequired(roadmap, "runId: 31697607328", "runId: 31697607329")

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
            "nextItem: \"SI-07\"",
            "nextItem: \"SI-99\""
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "Roadmap index must select SI-07" in it })
    }

    @Test
    fun activeSemanticItemRequiresExplicitSafeWorkPackagePath() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.SEMANTIC_INTEGRITY_ROADMAP),
            "workPackage: \".flow-agent/work-packages/SI-07-align-public-schemas-with-production-acceptance.yaml\"",
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
            "Flow CI #2919",
            "Flow CI #9999"
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "lastKnownValidation" in it })
    }

    @Test
    fun activeSemanticItemRequiresDeclaredStrategicSource() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, ".flow-agent/work-packages/SI-07-align-public-schemas-with-production-acceptance.yaml"),
            "#17-align-public-schemas-with-production-acceptance",
            "#16-eliminate-remaining-silent-authored-value-coercion"
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
