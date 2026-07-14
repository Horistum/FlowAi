package org.flowlang.conformance

import org.flowlang.capabilities.MaterializationReadinessStatus
import org.flowlang.capabilities.ProjectionReadinessStatus
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.TargetCompatibilityReadinessAnalyzer
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy

/**
 * Stable description of what committed reference snapshots actually prove.
 *
 * The snapshot set is evidence, not an executor. Semantic artifacts are explicitly non-executable,
 * and target projection files are named by their current render state so review-only output cannot
 * masquerade as a Jenkinsfile, GitHub Actions workflow or Tekton Pipeline.
 */
data class ReferenceSnapshotSet(
    val snapshotVersion: String = "1.0",
    val standardVersion: String = "",
    val scenarioId: String = "",
    val claim: ReferenceSnapshotClaim = ReferenceSnapshotClaim.REVIEW_ONLY_PROJECTION_SET,
    val executable: Boolean = false,
    val artifacts: List<ReferenceSnapshotArtifact> = emptyList(),
    val targets: List<ReferenceSnapshotTargetState> = emptyList()
)

enum class ReferenceSnapshotClaim {
    REVIEW_ONLY_PROJECTION_SET,
    EXECUTABLE_PROJECTION_SET
}

data class ReferenceSnapshotArtifact(
    val file: String = "",
    val layer: ReferenceSnapshotLayer = ReferenceSnapshotLayer.SEMANTIC_INPUT,
    val state: ReferenceSnapshotArtifactState = ReferenceSnapshotArtifactState.SEMANTIC_ONLY,
    val executable: Boolean = false,
    val target: String? = null
)

enum class ReferenceSnapshotLayer {
    SEMANTIC_INPUT,
    SEMANTIC_AST,
    SEMANTIC_PLAN,
    TARGET_PROJECTION
}

enum class ReferenceSnapshotArtifactState {
    SEMANTIC_ONLY,
    REVIEW_ONLY,
    FAIL_FAST,
    EXECUTABLE
}

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
        val executable = targets.any { it.executable }
        return ReferenceSnapshotSet(
            standardVersion = standardVersion,
            scenarioId = scenarioId,
            claim = if (executable) {
                ReferenceSnapshotClaim.EXECUTABLE_PROJECTION_SET
            } else {
                ReferenceSnapshotClaim.REVIEW_ONLY_PROJECTION_SET
            },
            executable = executable,
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
            if (artifact.layer != ReferenceSnapshotLayer.TARGET_PROJECTION) {
                if (artifact.target != null) add("Semantic artifact '${artifact.file}' must not declare a target.")
                if (artifact.state != ReferenceSnapshotArtifactState.SEMANTIC_ONLY || artifact.executable) {
                    add("Semantic artifact '${artifact.file}' must be SEMANTIC_ONLY and non-executable.")
                }
            } else {
                if (artifact.target.isNullOrBlank()) add("Target projection artifact '${artifact.file}' must declare a target.")
                val expectedFile = artifact.target?.let { projectionFile(it, artifact.state.toRenderMode()) }
                if (expectedFile != null && artifact.file != expectedFile) {
                    add("Target projection artifact '${artifact.file}' must use state-specific name '$expectedFile'.")
                }
                if (artifact.executable != (artifact.state == ReferenceSnapshotArtifactState.EXECUTABLE)) {
                    add("Target projection artifact '${artifact.file}' has inconsistent executable state.")
                }
            }
            if (artifact.file in legacyExecutableLookingFiles) {
                add("Legacy executable-looking snapshot '${artifact.file}' is forbidden.")
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
            val artifact = snapshot.artifacts.singleOrNull {
                it.layer == ReferenceSnapshotLayer.TARGET_PROJECTION && it.target == target.target
            }
            if (artifact == null) add("Target '${target.target}' is missing its projection artifact descriptor.")
        }

        val actualExecutable = snapshot.targets.any { it.executable }
        if (snapshot.executable != actualExecutable) add("Snapshot executable flag must match target projection evidence.")
        when (snapshot.claim) {
            ReferenceSnapshotClaim.REVIEW_ONLY_PROJECTION_SET -> {
                if (snapshot.executable) add("Review-only snapshot set must be non-executable.")
                if (snapshot.targets.any { it.renderMode != TargetRenderMode.REVIEW_ONLY }) {
                    add("Review-only snapshot set must contain only REVIEW_ONLY target states.")
                }
            }
            ReferenceSnapshotClaim.EXECUTABLE_PROJECTION_SET -> {
                if (!snapshot.executable) add("Executable snapshot set must contain executable target evidence.")
                if (snapshot.targets.any { it.renderMode != TargetRenderMode.EXECUTABLE }) {
                    add("Executable snapshot set must contain only EXECUTABLE target states.")
                }
            }
        }
    }

    fun projectionFile(target: String, mode: TargetRenderMode): String = when (mode) {
        TargetRenderMode.EXECUTABLE -> "$target.executable.yaml"
        TargetRenderMode.REVIEW_ONLY -> "$target.review.yaml"
        TargetRenderMode.FAIL_FAST -> "$target.blocked.yaml"
    }

    private val semanticArtifacts = listOf(
        ReferenceSnapshotArtifact(
            file = "normalized-intent.json",
            layer = ReferenceSnapshotLayer.SEMANTIC_INPUT,
            state = ReferenceSnapshotArtifactState.SEMANTIC_ONLY
        ),
        ReferenceSnapshotArtifact(
            file = "flow-ast.json",
            layer = ReferenceSnapshotLayer.SEMANTIC_AST,
            state = ReferenceSnapshotArtifactState.SEMANTIC_ONLY
        ),
        ReferenceSnapshotArtifact(
            file = "execution-plan.json",
            layer = ReferenceSnapshotLayer.SEMANTIC_PLAN,
            state = ReferenceSnapshotArtifactState.SEMANTIC_ONLY
        )
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
