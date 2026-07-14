package org.flowlang.conformance

import org.flowlang.capabilities.CompatibilityIssue
import org.flowlang.capabilities.CompatibilityLevel
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.ExecutionReadinessReport
import org.flowlang.capabilities.MaterializationReadinessStatus
import org.flowlang.capabilities.ProjectionReadinessStatus
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.TargetCompatibilityReadinessAnalyzer
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.FlowVersionBoundary

/** Stable evidence describing what committed reference snapshots actually prove. */
data class ReferenceSnapshotSet(
    val snapshotVersion: String = "2.0",
    val versionBoundary: FlowVersionBoundary = FlowStandardVersions.boundary(targetManifestPresent = false),
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val scenarioId: String = "",
    val overallState: ReferenceSnapshotSetState = ReferenceSnapshotSetState.MIXED,
    val executable: Boolean = false,
    val artifacts: List<ReferenceSnapshotArtifact> = emptyList(),
    val targets: List<ReferenceSnapshotTargetState> = emptyList()
)

enum class ReferenceSnapshotSetState { EXECUTABLE, REVIEW_ONLY, FAIL_FAST, MIXED }

data class ReferenceSnapshotArtifact(
    val file: String = "",
    val layer: ReferenceSnapshotLayer = ReferenceSnapshotLayer.SEMANTIC_INPUT,
    val state: ReferenceSnapshotArtifactState = ReferenceSnapshotArtifactState.SEMANTIC_ONLY,
    val executable: Boolean = false,
    val target: String? = null
)

enum class ReferenceSnapshotLayer { SEMANTIC_INPUT, SEMANTIC_AST, SEMANTIC_PLAN, TARGET_PROJECTION }
enum class ReferenceSnapshotArtifactState { SEMANTIC_ONLY, REVIEW_ONLY, FAIL_FAST, EXECUTABLE }

data class ReferenceSnapshotBlocker(
    val code: String = "",
    val nodeId: String = "",
    val feature: String = "",
    val message: String = "",
    val source: String = ""
)

data class ReferenceSnapshotTargetState(
    val target: String = "",
    val capabilityCompatibility: SupportLevel = SupportLevel.UNSUPPORTED,
    val effectiveCompatibility: SupportLevel = SupportLevel.UNSUPPORTED,
    val materializationReadiness: MaterializationReadinessStatus = MaterializationReadinessStatus.NOT_EVALUATED,
    val projectionReadiness: ProjectionReadinessStatus = ProjectionReadinessStatus.NOT_EVALUATED,
    val renderMode: TargetRenderMode = TargetRenderMode.FAIL_FAST,
    val executable: Boolean = false,
    val manifestPresent: Boolean = false,
    val renderedArtifactPresent: Boolean = false,
    val blockers: List<ReferenceSnapshotBlocker> = emptyList()
)

sealed interface ReferenceTargetProjectionEvidence {
    val target: String
}

data class ReferenceManifestProjectionEvidence(
    val manifest: TargetManifest,
    val renderedArtifactPresent: Boolean = true
) : ReferenceTargetProjectionEvidence {
    override val target: String = manifest.target
}

data class ReferenceBlockedProjectionEvidence(
    val compatibility: CompatibilityReport,
    val readiness: ExecutionReadinessReport
) : ReferenceTargetProjectionEvidence {
    override val target: String = compatibility.target
}

object ReferenceSnapshotHonesty {
    val legacyExecutableLookingFiles: Set<String> = setOf(
        "Jenkinsfile",
        "github-actions.yml",
        "tekton-pipeline.yaml",
        "compatibility-report.jenkins.json",
        "target-manifest.jenkins.json",
        "tekton.review.yaml"
    )

    fun build(
        scenarioId: String,
        evidence: Collection<ReferenceTargetProjectionEvidence>
    ): ReferenceSnapshotSet {
        require(evidence.isNotEmpty()) { "Reference snapshot evidence requires at least one target outcome." }
        val duplicateTargets = evidence.groupingBy { it.target }.eachCount().filterValues { it > 1 }.keys
        require(duplicateTargets.isEmpty()) { "Reference target evidence must be unique: ${duplicateTargets.sorted().joinToString()}." }

        val targets = evidence.sortedBy { it.target }.map(::targetState)
        val targetArtifacts = targets.map { target ->
            ReferenceSnapshotArtifact(
                file = projectionFile(target.target, target.renderMode),
                layer = ReferenceSnapshotLayer.TARGET_PROJECTION,
                state = target.renderMode.toArtifactState(),
                executable = target.executable,
                target = target.target
            )
        }
        return ReferenceSnapshotSet(
            versionBoundary = FlowStandardVersions.boundary(targetManifestPresent = targets.any { it.manifestPresent }),
            standardVersion = FlowStandardVersions.FLOW_STANDARD_VERSION,
            scenarioId = scenarioId,
            overallState = overallState(targets),
            executable = targets.all { it.executable },
            artifacts = semanticArtifacts + targetArtifacts,
            targets = targets
        )
    }

    fun targetState(evidence: ReferenceTargetProjectionEvidence): ReferenceSnapshotTargetState = when (evidence) {
        is ReferenceManifestProjectionEvidence -> manifestState(evidence)
        is ReferenceBlockedProjectionEvidence -> blockedState(evidence)
    }

    fun validate(snapshot: ReferenceSnapshotSet): List<String> = buildList {
        if (snapshot.snapshotVersion != "2.0") add("snapshotVersion must be 2.0.")
        if (snapshot.standardVersion != snapshot.versionBoundary.publicStandardVersion) {
            add("standardVersion must match versionBoundary.publicStandardVersion.")
        }
        if (snapshot.versionBoundary.implementationPackageVersion.isBlank()) add("Implementation package version must not be blank.")
        if (snapshot.versionBoundary.artifactContractVersion.isBlank()) add("Artifact contract version must not be blank.")
        if (snapshot.scenarioId.isBlank()) add("scenarioId must not be blank.")
        if (snapshot.artifacts.isEmpty()) add("Snapshot set must list artifacts.")
        if (snapshot.targets.isEmpty()) add("Snapshot set must list target projection states.")

        val duplicateFiles = snapshot.artifacts.groupingBy { it.file }.eachCount().filterValues { it > 1 }.keys
        if (duplicateFiles.isNotEmpty()) add("Snapshot artifact files must be unique: ${duplicateFiles.sorted().joinToString()}.")
        val duplicateTargets = snapshot.targets.groupingBy { it.target }.eachCount().filterValues { it > 1 }.keys
        if (duplicateTargets.isNotEmpty()) add("Snapshot targets must be unique: ${duplicateTargets.sorted().joinToString()}.")

        snapshot.artifacts.forEach { artifact ->
            if (artifact.file.isBlank()) add("Snapshot artifact file must not be blank.")
            if (artifact.file in legacyExecutableLookingFiles) add("Legacy executable-looking snapshot '${artifact.file}' is forbidden.")
            if (artifact.layer != ReferenceSnapshotLayer.TARGET_PROJECTION) {
                if (artifact.target != null) add("Semantic artifact '${artifact.file}' must not declare a target.")
                if (artifact.state != ReferenceSnapshotArtifactState.SEMANTIC_ONLY || artifact.executable) {
                    add("Semantic artifact '${artifact.file}' must be SEMANTIC_ONLY and non-executable.")
                }
            } else {
                val target = artifact.target
                if (target.isNullOrBlank()) {
                    add("Target projection artifact '${artifact.file}' must declare a target.")
                } else {
                    val expectedFile = projectionFile(target, artifact.state.toRenderMode())
                    if (artifact.file != expectedFile) {
                        add("Target projection artifact '${artifact.file}' must use state-specific name '$expectedFile'.")
                    }
                }
                if (artifact.executable != (artifact.state == ReferenceSnapshotArtifactState.EXECUTABLE)) {
                    add("Target projection artifact '${artifact.file}' has inconsistent executable state.")
                }
            }
        }

        snapshot.targets.forEach { target ->
            if (target.target.isBlank()) add("Snapshot target id must not be blank.")
            if (target.executable != (target.renderMode == TargetRenderMode.EXECUTABLE)) {
                add("Target '${target.target}' has inconsistent renderMode/executable state.")
            }
            val expectedProjection = when (target.renderMode) {
                TargetRenderMode.EXECUTABLE -> ProjectionReadinessStatus.EXECUTABLE
                TargetRenderMode.REVIEW_ONLY -> ProjectionReadinessStatus.REVIEW_ONLY
                TargetRenderMode.FAIL_FAST -> ProjectionReadinessStatus.FAIL_FAST
            }
            if (target.projectionReadiness != expectedProjection) {
                add("Target '${target.target}' renderMode does not match projectionReadiness.")
            }
            if (target.renderMode == TargetRenderMode.FAIL_FAST) {
                if (target.manifestPresent) add("Fail-fast target '${target.target}' must not claim a manifest.")
                if (target.renderedArtifactPresent) add("Fail-fast target '${target.target}' must not claim rendered target syntax.")
                if (target.blockers.isEmpty()) add("Fail-fast target '${target.target}' must expose blockers.")
                if (target.materializationReadiness != MaterializationReadinessStatus.NOT_EVALUATED) {
                    add("Pre-projection fail-fast target '${target.target}' must leave materialization readiness NOT_EVALUATED.")
                }
            } else {
                if (!target.manifestPresent) add("Manifest-backed target '${target.target}' must declare manifestPresent.")
                if (target.blockers.isNotEmpty()) add("Non-blocked target '${target.target}' must not expose fail-fast blockers.")
            }
            val matchingArtifacts = snapshot.artifacts.filter {
                it.layer == ReferenceSnapshotLayer.TARGET_PROJECTION && it.target == target.target
            }
            if (matchingArtifacts.size != 1) {
                add("Target '${target.target}' must have exactly one projection artifact descriptor.")
            } else if (matchingArtifacts.single().state != target.renderMode.toArtifactState()) {
                add("Target '${target.target}' projection artifact state does not match renderMode.")
            }
        }

        val expectedOverall = if (snapshot.targets.isEmpty()) ReferenceSnapshotSetState.MIXED else overallState(snapshot.targets)
        if (snapshot.overallState != expectedOverall) add("Snapshot overallState must match target render evidence.")
        val expectedExecutable = snapshot.targets.isNotEmpty() && snapshot.targets.all { it.executable }
        if (snapshot.executable != expectedExecutable) add("Snapshot executable flag must require every target projection to be executable.")
    }

    fun projectionFile(target: String, mode: TargetRenderMode): String = when (mode) {
        TargetRenderMode.EXECUTABLE -> "$target.executable.yaml"
        TargetRenderMode.REVIEW_ONLY -> "$target.review.yaml"
        TargetRenderMode.FAIL_FAST -> "$target.blocked.json"
    }

    private fun manifestState(evidence: ReferenceManifestProjectionEvidence): ReferenceSnapshotTargetState {
        val manifest = evidence.manifest
        val compatibility = TargetCompatibilityReadinessAnalyzer.analyze(manifest)
        val render = TargetRenderPolicy.evaluate(manifest)
        return ReferenceSnapshotTargetState(
            target = manifest.target,
            capabilityCompatibility = compatibility.capabilityStatus,
            effectiveCompatibility = compatibility.effectiveStatus,
            materializationReadiness = compatibility.materializationReadiness,
            projectionReadiness = compatibility.projectionReadiness,
            renderMode = render.mode,
            executable = render.executable,
            manifestPresent = true,
            renderedArtifactPresent = evidence.renderedArtifactPresent
        )
    }

    private fun blockedState(evidence: ReferenceBlockedProjectionEvidence): ReferenceSnapshotTargetState {
        require(evidence.compatibility.target == evidence.readiness.target) {
            "Blocked compatibility/readiness evidence must describe the same target."
        }
        val compatibilityBlockers = evidence.compatibility.issues
            .filter { it.level == CompatibilityLevel.ERROR }
            .map { it.toSnapshotBlocker() }
        val readinessBlockers = evidence.readiness.blockers.map {
            ReferenceSnapshotBlocker(
                code = it.code,
                nodeId = it.nodeId.orEmpty(),
                feature = it.capability,
                message = it.message,
                source = "execution-readiness-report.json"
            )
        }
        val blockers = (compatibilityBlockers + readinessBlockers)
            .distinctBy { listOf(it.code, it.nodeId, it.feature, it.message, it.source) }
            .sortedWith(compareBy({ it.source }, { it.nodeId }, { it.feature }, { it.code }, { it.message }))
        require(blockers.isNotEmpty()) {
            "Fail-fast reference evidence for '${evidence.target}' requires a compatibility or readiness blocker."
        }
        return ReferenceSnapshotTargetState(
            target = evidence.target,
            capabilityCompatibility = evidence.compatibility.capabilityStatus,
            effectiveCompatibility = SupportLevel.UNSUPPORTED,
            materializationReadiness = MaterializationReadinessStatus.NOT_EVALUATED,
            projectionReadiness = ProjectionReadinessStatus.FAIL_FAST,
            renderMode = TargetRenderMode.FAIL_FAST,
            executable = false,
            manifestPresent = false,
            renderedArtifactPresent = false,
            blockers = blockers
        )
    }

    private fun CompatibilityIssue.toSnapshotBlocker(): ReferenceSnapshotBlocker = ReferenceSnapshotBlocker(
        code = "TARGET_COMPATIBILITY_BLOCKED",
        nodeId = nodeId,
        feature = feature,
        message = message,
        source = "compatibility-report.json"
    )

    private fun overallState(targets: List<ReferenceSnapshotTargetState>): ReferenceSnapshotSetState {
        val modes = targets.map { it.renderMode }.toSet()
        return when (modes.singleOrNull()) {
            TargetRenderMode.EXECUTABLE -> ReferenceSnapshotSetState.EXECUTABLE
            TargetRenderMode.REVIEW_ONLY -> ReferenceSnapshotSetState.REVIEW_ONLY
            TargetRenderMode.FAIL_FAST -> ReferenceSnapshotSetState.FAIL_FAST
            null -> ReferenceSnapshotSetState.MIXED
        }
    }

    private val semanticArtifacts = listOf(
        ReferenceSnapshotArtifact("normalized-intent.json", ReferenceSnapshotLayer.SEMANTIC_INPUT),
        ReferenceSnapshotArtifact("flow-ast.json", ReferenceSnapshotLayer.SEMANTIC_AST),
        ReferenceSnapshotArtifact("execution-plan.json", ReferenceSnapshotLayer.SEMANTIC_PLAN)
    )

    private fun TargetRenderMode.toArtifactState(): ReferenceSnapshotArtifactState = when (this) {
        TargetRenderMode.EXECUTABLE -> ReferenceSnapshotArtifactState.EXECUTABLE
        TargetRenderMode.REVIEW_ONLY -> ReferenceSnapshotArtifactState.REVIEW_ONLY
        TargetRenderMode.FAIL_FAST -> ReferenceSnapshotArtifactState.FAIL_FAST
    }

    private fun ReferenceSnapshotArtifactState.toRenderMode(): TargetRenderMode = when (this) {
        ReferenceSnapshotArtifactState.EXECUTABLE -> TargetRenderMode.EXECUTABLE
        ReferenceSnapshotArtifactState.REVIEW_ONLY -> TargetRenderMode.REVIEW_ONLY
        ReferenceSnapshotArtifactState.FAIL_FAST -> TargetRenderMode.FAIL_FAST
        ReferenceSnapshotArtifactState.SEMANTIC_ONLY -> TargetRenderMode.FAIL_FAST
    }
}
