import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flowlang.cli.Json
import org.flowlang.release.ReleaseMetadataHonestyAuthority

class ReleaseMetadataClosedLifecycleTests {
    @Test
    fun structuredCorrectionRequiredLifecyclePassesWithoutNextProjection() =
        withPhase(ReleaseLifecycleFixture.Phase.CORRECTION_REQUIRED) { root ->
            val report = ReleaseMetadataHonestyAuthority(root).analyze()

            assertEquals("PASS", report.status, report.failedChecks.joinToString())
            assertEquals("CORRECTION_REQUIRED", report.closurePhase)
            assertEquals("active", report.correctionStatus)
            assertEquals("correction-required", report.parentCoreItemStatus)
            assertEquals("correction-required", report.closureWorkPackageStatus)
            assertEquals("correction-required", report.closureStatus)
            assertEquals("active", report.coreTrackStatus)
            assertEquals("0.9.7.9", report.completedCoreItem)
            assertNull(report.nextCoreItem)
        }

    @Test
    fun structuredReadyLifecyclePassesWithRealNextProjection() =
        withPhase(ReleaseLifecycleFixture.Phase.READY) { root ->
            val report = ReleaseMetadataHonestyAuthority(root).analyze()

            assertEquals("PASS", report.status, report.failedChecks.joinToString())
            assertEquals("READY", report.closurePhase)
            assertEquals("complete", report.correctionStatus)
            assertEquals("next", report.parentCoreItemStatus)
            assertEquals("active", report.closureWorkPackageStatus)
            assertEquals("next", report.closureStatus)
            assertEquals("active", report.coreTrackStatus)
            assertEquals("0.9.7.9", report.completedCoreItem)
            assertEquals("0.9.7.10", report.nextCoreItem)
        }

    @Test
    fun structuredClosedLifecyclePassesWithoutNextProjection() =
        withPhase(ReleaseLifecycleFixture.Phase.CLOSED) { root ->
            val report = ReleaseMetadataHonestyAuthority(root).analyze()

            assertEquals("PASS", report.status, report.failedChecks.joinToString())
            assertEquals("CLOSED", report.closurePhase)
            assertEquals("complete", report.correctionStatus)
            assertEquals("completed", report.parentCoreItemStatus)
            assertEquals("complete", report.closureWorkPackageStatus)
            assertEquals("completed", report.closureStatus)
            assertEquals("completed", report.coreTrackStatus)
            assertEquals("0.9.7.10", report.completedCoreItem)
            assertNull(report.nextCoreItem)
        }

    @Test
    fun completedClosureWithoutStructuredImplementationEvidenceFails() =
        ReleaseLifecycleFixture.withRoot(
            ReleaseLifecycleFixture.Phase.CLOSED,
            includeClosedEvidence = false
        ) { root ->
            val report = ReleaseMetadataHonestyAuthority(root).analyze()

            assertEquals("FAIL", report.status)
            assertTrue("release.closure.implementation-evidence" in report.failedChecks)
        }

    @Test
    fun correctionRequiredCannotPublishNextProjection() =
        withPhase(ReleaseLifecycleFixture.Phase.CORRECTION_REQUIRED) { root ->
            ReleaseLifecycleFixture.addNextProjection(File(root, ReleaseLifecycleFixture.ROADMAP))
            ReleaseLifecycleFixture.addNextProjection(File(root, ReleaseLifecycleFixture.RELEASE_STATE))

            val report = ReleaseMetadataHonestyAuthority(root).analyze()

            assertEquals("FAIL", report.status)
            assertEquals("CORRECTION_REQUIRED", report.closurePhase)
            assertTrue("release.roadmap.next-item" in report.failedChecks)
            assertTrue("release.state.next-item" in report.failedChecks)
        }

    @Test
    fun readyLifecycleWithoutNextProjectionFails() =
        withPhase(ReleaseLifecycleFixture.Phase.READY) { root ->
            ReleaseLifecycleFixture.removeNextProjection(File(root, ReleaseLifecycleFixture.ROADMAP))
            ReleaseLifecycleFixture.removeNextProjection(File(root, ReleaseLifecycleFixture.RELEASE_STATE))

            val report = ReleaseMetadataHonestyAuthority(root).analyze()

            assertEquals("FAIL", report.status)
            assertEquals("READY", report.closurePhase)
            assertTrue("release.roadmap.next-item" in report.failedChecks)
            assertTrue("release.state.next-item" in report.failedChecks)
        }

    @Test
    fun closedLifecycleCannotRetainNextProjection() =
        withPhase(ReleaseLifecycleFixture.Phase.CLOSED) { root ->
            ReleaseLifecycleFixture.addNextProjection(File(root, ReleaseLifecycleFixture.ROADMAP))
            ReleaseLifecycleFixture.addNextProjection(File(root, ReleaseLifecycleFixture.RELEASE_STATE))

            val report = ReleaseMetadataHonestyAuthority(root).analyze()

            assertEquals("FAIL", report.status)
            assertEquals("CLOSED", report.closurePhase)
            assertTrue("release.roadmap.next-item" in report.failedChecks)
            assertTrue("release.state.next-item" in report.failedChecks)
        }

    @Test
    fun activeCorrectionCannotPublishReadyClosureStatus() =
        withPhase(ReleaseLifecycleFixture.Phase.CORRECTION_REQUIRED) { root ->
            ReleaseLifecycleFixture.setClosureWorkPackageStatus(root, "active")
            ReleaseLifecycleFixture.setCoreClosureStatus(root, "next")
            listOf(
                File(root, ReleaseLifecycleFixture.ROADMAP),
                File(root, ReleaseLifecycleFixture.RELEASE_STATE)
            ).forEach { file ->
                ReleaseLifecycleFixture.setClosureItemStatus(file, "next")
                ReleaseLifecycleFixture.addNextProjection(file)
            }

            val report = ReleaseMetadataHonestyAuthority(root).analyze()

            assertEquals("FAIL", report.status)
            assertEquals("INVALID", report.closurePhase)
            assertTrue("release.closure.phase" in report.failedChecks)
        }

    @Test
    fun completedCorrectionCannotLeaveClosureCorrectionRequired() =
        withPhase(ReleaseLifecycleFixture.Phase.CORRECTION_REQUIRED) { root ->
            ReleaseLifecycleFixture.setCorrectionStatus(root, "complete")
            ReleaseLifecycleFixture.setRoadmapCorrectionState(root, "complete", activePointer = false)

            val report = ReleaseMetadataHonestyAuthority(root).analyze()

            assertEquals("FAIL", report.status)
            assertEquals("INVALID", report.closurePhase)
            assertTrue("release.closure.phase" in report.failedChecks)
        }

    @Test
    fun completedClosureWithActiveCoreTrackFails() =
        withPhase(ReleaseLifecycleFixture.Phase.CLOSED) { root ->
            ReleaseLifecycleFixture.setCoreTrackStatus(root, "active")

            val report = ReleaseMetadataHonestyAuthority(root).analyze()

            assertEquals("FAIL", report.status)
            assertEquals("INVALID", report.closurePhase)
            assertTrue("release.closure.phase-alignment" in report.failedChecks)
        }

    @Test
    fun completedClosureWithStaleCompletedItemFails() =
        withPhase(ReleaseLifecycleFixture.Phase.CLOSED) { root ->
            ReleaseLifecycleFixture.setCompletedItem(
                File(root, ReleaseLifecycleFixture.ROADMAP),
                "0.9.7.9",
                "Intent Lowering and Diagnostic Honesty"
            )

            val report = ReleaseMetadataHonestyAuthority(root).analyze()

            assertEquals("FAIL", report.status)
            assertTrue("release.roadmap.completed-item" in report.failedChecks)
        }

    @Test
    fun schemaMatchesRuntimeLifecycleContract() {
        val report = ReleaseMetadataHonestyAuthority(File(".")).analyze()
        val schema = Json.mapper.readTree(File("schemas/release-metadata-honesty-report.schema.json"))
        val required = schema.path("required").map { it.asText() }.toSet()
        val phases = schema.path("properties").path("closurePhase").path("enum").map { it.asText() }.toSet()
        val closureStatuses = schema.path("properties").path("closureStatus").path("enum")
            .map { it.asText() }.toSet()
        val nextVariants = schema.path("properties").path("nextCoreItem").path("oneOf")

        assertEquals(report.reportVersion, schema.path("properties").path("reportVersion").path("const").asText())
        assertTrue(setOf("CORRECTION_REQUIRED", "READY", "CLOSED", "INVALID").all { it in phases })
        assertTrue(setOf("correction-required", "next", "completed").all { it in closureStatuses })
        assertTrue(nextVariants.any { it.path("type").asText() == "null" })
        assertTrue(
            setOf(
                "closureItem",
                "closureWorkPackageStatus",
                "closurePhase",
                "coreTrackStatus",
                "completedCoreItem",
                "nextCoreItem"
            ).all { it in required }
        )
    }

    private fun withPhase(
        phase: ReleaseLifecycleFixture.Phase,
        assertions: (File) -> Unit
    ) = ReleaseLifecycleFixture.withRoot(phase, assertions = assertions)
}
