import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.architecture.ArchitectureDeltaAnalyzer
import org.flowlang.architecture.StandardArtifactSnapshot
import org.flowlang.architecture.StandardCheckSnapshot
import org.flowlang.architecture.StandardModelSnapshot
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.GateKind
import java.io.File

class FlowArchitectureDeltaAnalyzerTests {
    @Test
    fun currentModelDeltaPassesAgainstFrozenV073Baseline() {
        val baseline = StandardModelSnapshot.fromYaml(File("standard/architecture/standard-model-baseline-v0.7.3.yaml"))
        val delta = ArchitectureDeltaAnalyzer(baseline).analyze()

        assertEquals("0.7.6", FlowStandardVersions.FLOW_STANDARD_VERSION)
        assertEquals("PASS", delta.status, delta.issues.joinToString { it.code + ":" + it.subject })
        assertEquals("0.7.3", delta.previousVersion)
        assertEquals(FlowStandardVersions.FLOW_STANDARD_VERSION, delta.currentVersion)
        assertTrue("v0.7.4.architecture-delta-analyzer" in delta.addedChecks)
        assertTrue("v0.7.5.purpose-coverage-ratio" in delta.addedChecks)
        assertEquals(emptyList(), delta.removedChecks)
        assertEquals(emptyList(), delta.changedCheckKinds)
        assertEquals(0, delta.registryConsistencyCheckGrowth)
        assertEquals(0, delta.stablePublicArtifactGrowth)
        assertEquals(1, delta.governanceCheckGrowth)
        assertEquals(1, delta.behaviorCoverageGrowth)
    }

    @Test
    fun currentModelDeltaPassesAgainstFrozenV074Baseline() {
        val baseline = StandardModelSnapshot.fromYaml(File("standard/architecture/standard-model-baseline-v0.7.4.yaml"))
        val delta = ArchitectureDeltaAnalyzer(baseline).analyze()

        assertEquals("PASS", delta.status, delta.issues.joinToString { it.code + ":" + it.subject })
        assertEquals("0.7.4", delta.previousVersion)
        assertEquals(listOf("v0.7.5.purpose-coverage-ratio"), delta.addedChecks)
        assertEquals(0, delta.registryConsistencyCheckGrowth)
        assertEquals(0, delta.stablePublicArtifactGrowth)
        assertEquals(0, delta.governanceCheckGrowth)
        assertEquals(1, delta.behaviorCoverageGrowth)
    }

    @Test
    fun packageLineChecksOutsideReleaseProfileDoNotAffectPublicDelta() {
        val previous = minimalSnapshot("0.7.5")
        val current = previous.copy(
            standardVersion = "0.7.6",
            checks = previous.checks + StandardCheckSnapshot(
                id = "v9.package-observation",
                introducedIn = "9.0.0",
                kind = GateKind.BEHAVIOR,
                inReleaseProfile = false,
                externalAnchor = "docs/example.md"
            )
        )

        val delta = ArchitectureDeltaAnalyzer(previous, current).analyze()

        assertEquals("PASS", delta.status, delta.issues.joinToString { it.code + ":" + it.subject })
        assertEquals(emptyList(), delta.addedChecks)
        assertEquals(0, delta.behaviorCoverageGrowth)
    }

    @Test
    fun addingRegistryConsistencyGateFailsDelta() {
        val previous = minimalSnapshot("0.7.3")
        val current = previous.copy(
            standardVersion = "0.7.5",
            checks = previous.checks + StandardCheckSnapshot(
                id = "v9.registry-parity-is-back",
                introducedIn = "9.0.0",
                kind = GateKind.REGISTRY_CONSISTENCY
            )
        )

        val delta = ArchitectureDeltaAnalyzer(previous, current).analyze()

        assertEquals("FAIL", delta.status)
        assertTrue(delta.issues.any { it.code == "ARCHITECTURE_DELTA_REGISTRY_GATE_ADDED" })
    }

    @Test
    fun growingStablePublicSurfaceWithoutEvidenceFailsDelta() {
        val previous = minimalSnapshot("0.7.3")
        val current = previous.copy(
            standardVersion = "0.7.5",
            artifacts = previous.artifacts + StandardArtifactSnapshot(
                artifact = "new-public-report.json",
                stability = "stable",
                role = "new-report-contract"
            )
        )

        val delta = ArchitectureDeltaAnalyzer(previous, current).analyze()

        assertEquals("FAIL", delta.status)
        assertTrue(delta.issues.any { it.code == "ARCHITECTURE_DELTA_SURFACE_OUTRUNS_EVIDENCE" })
    }

    @Test
    fun silentGateKindReclassificationFailsDelta() {
        val previous = minimalSnapshot("0.7.3")
        val current = previous.copy(
            standardVersion = "0.7.5",
            checks = previous.checks.map { check ->
                if (check.id == "v1.behavior") check.copy(kind = GateKind.CONTRACT) else check
            }
        )

        val delta = ArchitectureDeltaAnalyzer(previous, current).analyze()

        assertEquals("FAIL", delta.status)
        assertTrue(delta.issues.any { it.code == "ARCHITECTURE_DELTA_GATE_KIND_CHANGED" })
    }

    private fun minimalSnapshot(version: String): StandardModelSnapshot = StandardModelSnapshot(
        standardVersion = version,
        checks = listOf(
            StandardCheckSnapshot(
                id = "v1.behavior",
                introducedIn = "1.0.0",
                kind = GateKind.BEHAVIOR,
                externalAnchor = "conformance/example.yaml"
            )
        ),
        artifacts = listOf(
            StandardArtifactSnapshot(
                artifact = "intent.schema.json",
                stability = "stable",
                role = "intent-contract"
            )
        )
    )
}
