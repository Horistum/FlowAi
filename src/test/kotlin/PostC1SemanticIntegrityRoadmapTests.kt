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
    fun repositoryActivatesAuthorizedPostC1Reconciliation() {
        val report = RoadmapStreamTransitionAuthority(File(".")).analyze()

        assertEquals(RoadmapTransitionPhase.SI_01_1_ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun reconciliationCannotRetroactivelyAuthorizeSi01() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.SEMANTIC_INTEGRITY_ROADMAP),
            "authorizationStatus: missing",
            "authorizationStatus: authorized"
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals(RoadmapTransitionPhase.SI_01_1_ACTIVE, report.phase)
        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "must remain explicitly unauthorized" in it })
    }

    @Test
    fun reconciliationCannotFabricateSi01ValidationBoundary() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.SI_01_1_WORK_PACKAGE),
            "runNumber: 2888",
            "runNumber: 9999"
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "exact already-passed SI-01 validation boundary" in it })
    }

    @Test
    fun reconciliationRequiresGlobalSemanticIntegrityFocus() = withRepositoryFixture { root ->
        replaceRequired(
            File(root, RoadmapStreamTransitionAuthority.ROADMAP_INDEX),
            "primaryRoadmapStream: semantic-integrity",
            "primaryRoadmapStream: conformance"
        )

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "Roadmap index must select SI-01.1" in it })
    }

    @Test
    fun reconciliationRequiresExplicitWorkPackage() = withRepositoryFixture { root ->
        File(root, RoadmapStreamTransitionAuthority.SI_01_1_WORK_PACKAGE).delete()

        val report = RoadmapStreamTransitionAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(
            report.errors.any { "no successor focus" in it } ||
                report.errors.any { "Required stream-transition file is missing" in it } ||
                report.phase == RoadmapTransitionPhase.INVALID
        )
    }

    private fun withRepositoryFixture(block: (File) -> Unit) {
        val root = createTempDirectory("flow-si011-roadmap").toFile()
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
