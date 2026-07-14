package org.flowlang.conformance

import org.flowlang.capabilities.MaterializationReadinessStatus
import org.flowlang.capabilities.ProjectionReadinessStatus
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.TargetCompatibilityReadinessAnalyzer
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy

/** Stable evidence describing what committed reference snapshots actually prove. */
data class ReferenceSnapshotSet(
    val snapshotVersion: String = "1.0",
    val standardVersion: String = "",
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

data class ReferenceSnapshotTargetState(
    val target: String = "",
    val capabilityCompatibility: SupportLevel = SupportLevel.UNSUPPORTED,
    val effectiveCompatibility: SupportLevel = SupportLevel.UNSUPPORTED,
    val materializationReadiness: MaterializationReadinessStatus = MaterializationReadinessStatus.NOT_EVALUATED,
    val projectionReadiness: ProjectionReadinessStatus = ProjectionReadinessStatus.NOT_EVALUATED,
    val renderMode: TargetRenderMode = TargetRenderMode.FAIL_FAST,
    val executable: Boolean = false
)

object ReferenceSnapshotHonesty {
    val legacyExecutableLookingFiles: Set<String> = setOf(
        "Jenkinsfile",
        "github-actions.yml",
        "tekton-pipeline.yaml",
        "compatibility-report.jenkins.json",
        "target-manifest.jenkins.json"
    )

    fun build(
        scenarioId: String,
        standardVersion: String,
        manifests: Collection<TargetManifest>
    ): ReferenceSnapshotSet {
        require(manifests.isNotEmpty()) { "Reference snapshot evidence requires at least one target manifest." }
        val targets = manifests.sortedBy { it.target }.map { manifest ->
            val compatibility = TargetCompatibilityReadinessAnalyzer.analyze(manifest)
            val render = TargetRenderPolicy.evaluate(manifest)
            ReferenceSnapshotTargetState(
                target = manifest.target,
                capabilityCompatibility = compatibility.capabilityStatus,
                effectiveCompatibility = compatibility.effectiveStatus,
                materializationReadiness = compatibility.materializationReadiness,
                projectionReadiness = compatibility.projectionReadiness,
                renderMode = render.mode,
                executable = render.executable
            )
        }
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
            standardVersion = standardVersion,
            scenarioId = scenarioId,
            overallState = overallState(targets),
            executable = targets.all { it.executable },
            artifacts = semanticArtifacts + targetArtifacts,
            targets = targets
        )
    }

    fun validate(snapshot: ReferenceSnapshotSet): List<String> = buildList {
        if (snapshot.snapshotVersion.isBlank()) add("snapshotVersion must not be blank.")
        if (snapshot.standardVersion.isBlank()) add("standardVersion must not be blank.")
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
        TargetRenderMode.FAIL_FAST -> "$target.blocked.yaml"
    }

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
