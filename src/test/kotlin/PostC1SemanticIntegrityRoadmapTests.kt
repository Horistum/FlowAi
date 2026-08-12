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
    fun repositoryCompletesSi02BeforeActivatingSi03() {
        val report = RoadmapStreamTransitionAuthority(File(".")).analyze()

        assertEquals(RoadmapTransitionPhase.SEMANTIC_INTEGRITY_ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun activeSemanticItemRequiresExactPredecessorCompletionBoundary() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.SI_02_WORK_PACKAGE),
            "runNumber: 2894",
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
        replaceRequired(roadmap, "runId: 31403411226", "runId: 31403411227")

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "completed predecessor CI boundary" in it })
    }

    @Test
    fun activeSemanticItemCannotRetroactivelyAuthorizeSi01() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.SEMANTIC_INTEGRITY_ROADMAP),
            "authorizationStatus: missing",
            "authorizationStatus: authorized"
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
            "nextItem: \"SI-03\"",
            "nextItem: \"SI-99\""
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "Roadmap index must select SI-03" in it })
    }

    @Test
    fun activeSemanticItemRequiresExplicitSafeWorkPackagePath() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.SEMANTIC_INTEGRITY_ROADMAP),
            "workPackage: \".flow-agent/work-packages/SI-03-canonical-technology-neutrality.yaml\"",
            "workPackage: \"../SI-03.yaml\""
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertTrue(report.phase == RoadmapTransitionPhase.C1_0_COMPLETE || report.phase == RoadmapTransitionPhase.INVALID)
        assertEquals("FAIL", report.status)
    }

    @Test
    fun activeSemanticItemCannotImpersonateItsOwnFutureGithubBoundary() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.RELEASE_STATE),
            "Flow CI #2894",
            "Flow CI #9999"
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "lastKnownValidation" in it })
    }

    @Test
    fun activeSemanticItemRequiresDeclaredStrategicSource() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.SI_03_WORK_PACKAGE),
            "#13-remove-implementation-technology-from-canonical-meaning",
            "#12-preserve-the-authored-dependency-graph-exactly"
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
