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
    fun repositoryCompletesReconciliationBeforeActivatingSi02() {
        val report = RoadmapStreamTransitionAuthority(File(".")).analyze()

        assertEquals(RoadmapTransitionPhase.SI_02_ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun si02CannotFabricateSi011CompletionBoundary() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.SI_01_1_WORK_PACKAGE),
            "runNumber: 2892",
            "runNumber: 9999"
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals(RoadmapTransitionPhase.SI_02_ACTIVE, report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "exact already-passed SI-01.1" in it })
    }

    @Test
    fun si02RequiresMatchingRoadmapAndWorkPackageCompletionEvidence() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.SEMANTIC_INTEGRITY_ROADMAP),
            "runId: 31389256988",
            "runId: 31389256989"
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "must agree on the completed SI-01.1 boundary" in it })
    }

    @Test
    fun si02CannotRetroactivelyAuthorizeSi01() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.SEMANTIC_INTEGRITY_ROADMAP),
            "authorizationStatus: missing",
            "authorizationStatus: authorized"
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals(RoadmapTransitionPhase.SI_02_ACTIVE, report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "must remain explicitly unauthorized" in it })
    }

    @Test
    fun si02RequiresGlobalSemanticIntegrityFocus() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.ROADMAP_INDEX),
            "nextItem: \"SI-02\"",
            "nextItem: \"SI-03\""
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "Roadmap index must select SI-02" in it })
    }

    @Test
    fun si02RequiresExplicitAuthorizedWorkPackage() = withRepositoryFixture { root ->
        File(root, RoadmapStreamTransitionAuthority.SI_02_WORK_PACKAGE).delete()

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(
            report.errors.any { "Required stream-transition file is missing" in it } ||
                report.phase == RoadmapTransitionPhase.INVALID ||
                report.phase == RoadmapTransitionPhase.C1_0_COMPLETE
        )
    }

    @Test
    fun si02CannotImpersonateANewerGithubValidationBoundary() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.RELEASE_STATE),
            "Flow CI #2892",
            "Flow CI #9999"
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "lastKnownValidation" in it })
    }

    @Test
    fun si02MustOwnProjectDirectionSection12() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.SI_02_WORK_PACKAGE),
            "#12-preserve-the-authored-dependency-graph-exactly",
            "#13-remove-implementation-technology-from-canonical-meaning"
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "project-direction section 1.2" in it })
    }

    private fun withRepositoryFixture(block: (File) -> Unit) {
        val root = createTempDirectory("flow-si02-roadmap").toFile()
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
