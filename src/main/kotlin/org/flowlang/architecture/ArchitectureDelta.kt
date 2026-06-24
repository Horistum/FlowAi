package org.flowlang.architecture

import org.flowlang.modules.MiniYaml
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.GateKind
import org.flowlang.standard.StandardModel
import java.io.File

/**
 * Immutable snapshot of the public standard model at one release boundary.
 *
 * Delta analysis intentionally compares snapshots, not the live repository
 * against itself. A self-consistent model can still drift away from Flow's
 * purpose; the previous release boundary is the minimum useful reference point.
 */
data class StandardModelSnapshot(
    val standardVersion: String,
    val checks: List<StandardCheckSnapshot>,
    val artifacts: List<StandardArtifactSnapshot>
) {
    companion object {
        fun current(): StandardModelSnapshot = StandardModelSnapshot(
            standardVersion = FlowStandardVersions.FLOW_STANDARD_VERSION,
            checks = StandardModel.checks.map { check ->
                StandardCheckSnapshot(
                    id = check.id,
                    introducedIn = check.introducedIn,
                    kind = check.kind,
                    inReleaseProfile = check.inReleaseProfile,
                    inCandidateLevel = check.inCandidateLevel,
                    negativeFixture = check.negativeFixture,
                    externalAnchor = check.externalAnchor
                )
            },
            artifacts = StandardModel.artifacts.map { artifact ->
                StandardArtifactSnapshot(
                    artifact = artifact.artifact,
                    stability = artifact.stability,
                    role = artifact.role,
                    inExportBundle = artifact.inExportBundle,
                    inCandidateLevel = artifact.inCandidateLevel,
                    isEvidence = artifact.isEvidence
                )
            }
        )

        @Suppress("UNCHECKED_CAST")
        fun fromYaml(file: File): StandardModelSnapshot {
            require(file.isFile) { "Standard model baseline does not exist: ${file.path}" }
            val root = MiniYaml.parseMap(file.readText())
            val checks = (root["checks"] as? List<Any?>).orEmpty().mapNotNull { item ->
                val map = item as? Map<String, Any?> ?: return@mapNotNull null
                val id = map["id"] as? String ?: return@mapNotNull null
                StandardCheckSnapshot(
                    id = id,
                    introducedIn = map["introducedIn"]?.toString().orEmpty(),
                    kind = parseGateKind(map["kind"]?.toString().orEmpty()),
                    inReleaseProfile = map["inReleaseProfile"] as? Boolean ?: true,
                    inCandidateLevel = map["inCandidateLevel"] as? Boolean ?: false,
                    negativeFixture = map["negativeFixture"] as? String ?: "",
                    externalAnchor = map["externalAnchor"] as? String ?: ""
                )
            }
            val artifacts = (root["artifacts"] as? List<Any?>).orEmpty().mapNotNull { item ->
                val map = item as? Map<String, Any?> ?: return@mapNotNull null
                val artifact = map["artifact"] as? String ?: return@mapNotNull null
                StandardArtifactSnapshot(
                    artifact = artifact,
                    stability = map["stability"] as? String ?: "",
                    role = map["role"] as? String ?: "",
                    inExportBundle = map["inExportBundle"] as? Boolean ?: true,
                    inCandidateLevel = map["inCandidateLevel"] as? Boolean ?: false,
                    isEvidence = map["isEvidence"] as? Boolean ?: false
                )
            }
            return StandardModelSnapshot(
                standardVersion = root["standardVersion"]?.toString().orEmpty(),
                checks = checks,
                artifacts = artifacts
            )
        }

        private fun parseGateKind(value: String): GateKind = GateKind.entries.firstOrNull { it.name == value }
            ?: error("Unknown gate kind in baseline: '$value'")
    }
}

data class StandardCheckSnapshot(
    val id: String,
    val introducedIn: String,
    val kind: GateKind,
    val inReleaseProfile: Boolean = true,
    val inCandidateLevel: Boolean = false,
    val negativeFixture: String = "",
    val externalAnchor: String = ""
)

data class StandardArtifactSnapshot(
    val artifact: String,
    val stability: String,
    val role: String,
    val inExportBundle: Boolean = true,
    val inCandidateLevel: Boolean = false,
    val isEvidence: Boolean = false
)

data class ArchitectureDeltaIssue(
    val code: String,
    val severity: String,
    val message: String,
    val subject: String = ""
)

data class StandardModelDeltaReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val previousVersion: String,
    val currentVersion: String,
    val status: String,
    val addedChecks: List<String>,
    val removedChecks: List<String>,
    val changedCheckKinds: List<String>,
    val addedArtifacts: List<String>,
    val removedArtifacts: List<String>,
    val stablePublicArtifactGrowth: Int,
    val behaviorCoverageGrowth: Int,
    val evidenceBackedCheckGrowth: Int,
    val governanceCheckGrowth: Int,
    val registryConsistencyCheckGrowth: Int,
    val issues: List<ArchitectureDeltaIssue>
)

/**
 * Compares two StandardModel snapshots and reports whether the change moves the
 * public standard in a useful direction.
 *
 * The analyzer deliberately uses explicit model snapshots. It does not infer
 * delta from the current repository state, because that was exactly how the old
 * drift score managed to pat itself on the back for files that already existed.
 */
class ArchitectureDeltaAnalyzer(
    private val previous: StandardModelSnapshot,
    private val current: StandardModelSnapshot = StandardModelSnapshot.current()
) {
    fun analyze(): StandardModelDeltaReport {
        val previousChecks = previous.checks.associateBy { it.id }
        val currentChecks = current.checks.associateBy { it.id }
        val addedChecks = current.checks.filterNot { it.id in previousChecks }
        val removedChecks = previous.checks.filterNot { it.id in currentChecks }
        val changedKinds = current.checks.mapNotNull { check ->
            val previousCheck = previousChecks[check.id] ?: return@mapNotNull null
            if (previousCheck.kind != check.kind) "${check.id}:${previousCheck.kind.name}->${check.kind.name}" else null
        }

        val previousArtifacts = previous.artifacts.associateBy { it.artifact }
        val currentArtifacts = current.artifacts.associateBy { it.artifact }
        val addedArtifacts = current.artifacts.filterNot { it.artifact in previousArtifacts }
        val removedArtifacts = previous.artifacts.filterNot { it.artifact in currentArtifacts }
        val stablePublicArtifactGrowth = addedArtifacts.count { it.stability == "stable" }
        val behaviorCoverageGrowth = addedChecks.count { it.kind == GateKind.BEHAVIOR }
        val evidenceBackedCheckGrowth = addedChecks.count { it.kind.isSubstance && hasEvidence(it) }
        val governanceCheckGrowth = addedChecks.count { it.kind == GateKind.GOVERNANCE }
        val registryConsistencyCheckGrowth = addedChecks.count { it.kind == GateKind.REGISTRY_CONSISTENCY }

        val issues = mutableListOf<ArchitectureDeltaIssue>()
        if (previous.standardVersion.isBlank()) {
            issues += ArchitectureDeltaIssue(
                code = "ARCHITECTURE_DELTA_BASELINE_VERSION_MISSING",
                severity = "error",
                message = "The previous StandardModel snapshot must declare a standardVersion."
            )
        }
        if (addedChecks.any { it.kind == GateKind.REGISTRY_CONSISTENCY }) {
            issues += ArchitectureDeltaIssue(
                code = "ARCHITECTURE_DELTA_REGISTRY_GATE_ADDED",
                severity = "error",
                message = "New registry-consistency gates are not allowed after the StandardModel collapse.",
                subject = addedChecks.filter { it.kind == GateKind.REGISTRY_CONSISTENCY }.joinToString { it.id }
            )
        }
        if (changedKinds.isNotEmpty()) {
            issues += ArchitectureDeltaIssue(
                code = "ARCHITECTURE_DELTA_GATE_KIND_CHANGED",
                severity = "error",
                message = "Gate kind changes must not happen silently; add a new check or document an explicit migration.",
                subject = changedKinds.joinToString()
            )
        }
        addedChecks.filter { it.kind.isSubstance && !hasEvidence(it) }.forEach { check ->
            issues += ArchitectureDeltaIssue(
                code = "ARCHITECTURE_DELTA_ADDED_CHECK_WITHOUT_EVIDENCE",
                severity = "error",
                message = "Added substance check must have a negativeFixture or externalAnchor.",
                subject = check.id
            )
        }
        if (stablePublicArtifactGrowth > evidenceBackedCheckGrowth) {
            issues += ArchitectureDeltaIssue(
                code = "ARCHITECTURE_DELTA_SURFACE_OUTRUNS_EVIDENCE",
                severity = "error",
                message = "Stable public artifacts must not grow faster than evidence-backed checks.",
                subject = "stablePublicArtifactGrowth=$stablePublicArtifactGrowth evidenceBackedCheckGrowth=$evidenceBackedCheckGrowth"
            )
        }
        removedChecks.filter { it.kind.isSubstance }.forEach { check ->
            issues += ArchitectureDeltaIssue(
                code = "ARCHITECTURE_DELTA_SUBSTANCE_CHECK_REMOVED",
                severity = "error",
                message = "Removing substance checks requires an explicit compatibility migration, not silent deletion.",
                subject = check.id
            )
        }

        return StandardModelDeltaReport(
            previousVersion = previous.standardVersion,
            currentVersion = current.standardVersion,
            status = if (issues.none { it.severity == "error" }) "PASS" else "FAIL",
            addedChecks = addedChecks.map { it.id },
            removedChecks = removedChecks.map { it.id },
            changedCheckKinds = changedKinds,
            addedArtifacts = addedArtifacts.map { it.artifact },
            removedArtifacts = removedArtifacts.map { it.artifact },
            stablePublicArtifactGrowth = stablePublicArtifactGrowth,
            behaviorCoverageGrowth = behaviorCoverageGrowth,
            evidenceBackedCheckGrowth = evidenceBackedCheckGrowth,
            governanceCheckGrowth = governanceCheckGrowth,
            registryConsistencyCheckGrowth = registryConsistencyCheckGrowth,
            issues = issues
        )
    }

    private fun hasEvidence(check: StandardCheckSnapshot): Boolean =
        check.negativeFixture.isNotBlank() || check.externalAnchor.isNotBlank()
}
