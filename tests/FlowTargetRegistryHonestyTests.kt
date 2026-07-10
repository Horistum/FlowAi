import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.targets.StandardTargetRegistryHonestySnapshots
import org.flowlang.targets.TargetRegistryEntry
import org.flowlang.targets.TargetRegistryEvidence
import org.flowlang.targets.TargetRegistryEvidenceKind
import org.flowlang.targets.TargetRegistryHonestyStatus
import org.flowlang.targets.TargetRegistryHonestyValidator
import org.flowlang.targets.TargetRegistrySnapshot
import org.flowlang.targets.TargetRegistryStatus

class FlowTargetRegistryHonestyTests {
    @Test
    fun baselineTargetRegistryIsHonestWithoutProductionSupportInflation() {
        val snapshot = StandardTargetRegistryHonestySnapshots.baseline()
        val report = TargetRegistryHonestyValidator().validate(snapshot)

        assertEquals(TargetRegistryHonestyStatus.PASS, report.status)
        assertTrue(report.valid)
        assertTrue(snapshot.entries.any { it.targetId == "shell" && it.status == TargetRegistryStatus.BLOCKED })
        assertFalse(snapshot.entries.any { it.status == TargetRegistryStatus.PRODUCTION_SUPPORTED })
        assertTrue(snapshot.entries.filter { it.status == TargetRegistryStatus.TESTED }.all { entry ->
            entry.evidenceKinds().containsAll(
                setOf(
                    TargetRegistryEvidenceKind.IMPLEMENTATION,
                    TargetRegistryEvidenceKind.PROJECTION_PLAN,
                    TargetRegistryEvidenceKind.TEST
                )
            )
        })
    }

    @Test
    fun productionSupportedTargetRequiresImplementationProjectionTestConformanceAndCapabilities() {
        val snapshot = TargetRegistrySnapshot(
            registryId = "flow.target.registry.test",
            entries = listOf(
                TargetRegistryEntry(
                    targetId = "jenkins",
                    displayName = "Jenkins",
                    status = TargetRegistryStatus.PRODUCTION_SUPPORTED,
                    evidence = listOf(
                        TargetRegistryEvidence("JenkinsManifestRenderer", TargetRegistryEvidenceKind.IMPLEMENTATION),
                        TargetRegistryEvidence("flow.projection.baseline", TargetRegistryEvidenceKind.PROJECTION_PLAN),
                        TargetRegistryEvidence("FlowNoShellTargetProjectionTests", TargetRegistryEvidenceKind.TEST)
                    ),
                    reason = "Incomplete production claim."
                )
            )
        )

        val report = TargetRegistryHonestyValidator().validate(snapshot)

        assertEquals(TargetRegistryHonestyStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "target.production.evidence.missing" })
        assertTrue(report.issues.any { it.code == "target.production.capabilities.missing" })
    }

    @Test
    fun declaredOnlyTargetMustNotCarryExecutionEvidence() {
        val snapshot = TargetRegistrySnapshot(
            registryId = "flow.target.registry.test",
            entries = listOf(
                TargetRegistryEntry(
                    targetId = "future-target",
                    displayName = "Future Target",
                    status = TargetRegistryStatus.DECLARED_ONLY,
                    evidence = listOf(
                        TargetRegistryEvidence("target.future.notes", TargetRegistryEvidenceKind.NOTES_DECLARATION),
                        TargetRegistryEvidence("FutureRenderer", TargetRegistryEvidenceKind.IMPLEMENTATION)
                    ),
                    reason = "Declared target with premature implementation claim."
                )
            )
        )

        val report = TargetRegistryHonestyValidator().validate(snapshot)

        assertEquals(TargetRegistryHonestyStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "target.declared-only.premature-evidence" })
    }

    @Test
    fun shellLikeTargetMustBeRegisteredOnlyAsBlocked() {
        val snapshot = TargetRegistrySnapshot(
            registryId = "flow.target.registry.test",
            entries = listOf(
                TargetRegistryEntry(
                    targetId = "shell",
                    displayName = "Shell",
                    status = TargetRegistryStatus.TESTED,
                    declaredCapabilities = setOf("resource.run"),
                    evidence = listOf(
                        TargetRegistryEvidence("ShellRenderer", TargetRegistryEvidenceKind.IMPLEMENTATION),
                        TargetRegistryEvidence("flow.projection.baseline", TargetRegistryEvidenceKind.PROJECTION_PLAN),
                        TargetRegistryEvidence("ShellTests", TargetRegistryEvidenceKind.TEST)
                    ),
                    reason = "Incorrect shell support claim."
                )
            )
        )

        val report = TargetRegistryHonestyValidator().validate(snapshot)

        assertEquals(TargetRegistryHonestyStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "target.shell.not-blocked" })
    }

    @Test
    fun statusSpecificEvidenceRulesAreEnforced() {
        val snapshot = TargetRegistrySnapshot(
            registryId = "flow.target.registry.test",
            entries = listOf(
                TargetRegistryEntry(
                    targetId = "experimental-target",
                    displayName = "Experimental Target",
                    status = TargetRegistryStatus.EXPERIMENTAL,
                    evidence = listOf(TargetRegistryEvidence("ExperimentImpl", TargetRegistryEvidenceKind.IMPLEMENTATION)),
                    reason = "Missing notes or review evidence."
                ),
                TargetRegistryEntry(
                    targetId = "deprecated-target",
                    displayName = "Deprecated Target",
                    status = TargetRegistryStatus.DEPRECATED,
                    evidence = listOf(TargetRegistryEvidence("OldImpl", TargetRegistryEvidenceKind.IMPLEMENTATION)),
                    reason = "Missing review decision."
                ),
                TargetRegistryEntry(
                    targetId = "blocked-target",
                    displayName = "Blocked Target",
                    status = TargetRegistryStatus.BLOCKED,
                    evidence = listOf(TargetRegistryEvidence("BlockedImpl", TargetRegistryEvidenceKind.IMPLEMENTATION)),
                    reason = "Missing policy or review evidence."
                )
            )
        )

        val report = TargetRegistryHonestyValidator().validate(snapshot)

        assertEquals(TargetRegistryHonestyStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "target.experimental.evidence.missing" })
        assertTrue(report.issues.any { it.code == "target.deprecated.review.missing" })
        assertTrue(report.issues.any { it.code == "target.blocked.evidence.missing" })
    }

    @Test
    fun registryRejectsImplicitSupportLanguageAsEvidence() {
        val snapshot = TargetRegistrySnapshot(
            registryId = "flow.target.registry.test",
            entries = listOf(
                TargetRegistryEntry(
                    targetId = "yaml-runner",
                    displayName = "YAML Runner",
                    status = TargetRegistryStatus.IMPLEMENTED,
                    evidence = listOf(TargetRegistryEvidence("YamlRunner", TargetRegistryEvidenceKind.IMPLEMENTATION, "Supported by default through generic shell.")),
                    reason = "Assume supported by fallback shell."
                )
            )
        )

        val report = TargetRegistryHonestyValidator().validate(snapshot)

        assertEquals(TargetRegistryHonestyStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "target.registry.forbidden-ready-claim" })
    }
}
