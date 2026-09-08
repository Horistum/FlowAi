package org.flowlang.roadmap

import java.io.File
import org.flowlang.serialization.FlowYaml

enum class ToolchainModernizationPhase {
    ACTIVE,
    COMPLETE,
    INVALID
}

data class ToolchainModernizationLifecycleReport(
    val status: String,
    val phase: ToolchainModernizationPhase,
    val errors: List<String>
)

/**
 * Owns the orthogonal toolchain-modernization lifecycle without inventing a
 * product roadmap stream. C1.0 and SI-08 remain closed historical boundaries;
 * External Falsification remains a strategic successor until separately
 * activated after this enabling milestone.
 */
class ToolchainModernizationLifecycle(private val rootDir: File = File(".")) {
    fun analyze(): ToolchainModernizationLifecycleReport {
        val roadmap = requiredYaml(ROADMAP)
        val releaseState = requiredYaml(RELEASE_STATE)
        val semanticRoadmap = requiredYaml(SEMANTIC_INTEGRITY_ROADMAP)
        val workPackage = requiredYaml(WORK_PACKAGE)
        val milestone = roadmap.map("strategicDirection")
            .mapList("enablingMilestones")
            .singleOrNull { it.string("id") == MILESTONE_ID }
            .orEmpty()

        val phase = when {
            milestone.string("status") == "active" && workPackage.string("status") == "active" ->
                ToolchainModernizationPhase.ACTIVE
            milestone.string("status") == "completed" && workPackage.string("status") == "complete" ->
                ToolchainModernizationPhase.COMPLETE
            else -> ToolchainModernizationPhase.INVALID
        }

        val errors = buildList {
            requireRetainedCompletedStreams(roadmap, releaseState, semanticRoadmap, this)
            requireVersionAlignment(roadmap, releaseState, this)
            requireMilestoneShape(roadmap, releaseState, milestone, workPackage, phase, this)
            requireRepositoryMigrationState(workPackage, this)
            requireSelectedTarget(workPackage, this)
            requireLifecycleEvidence(workPackage, phase, this)
        }

        return ToolchainModernizationLifecycleReport(
            status = if (phase != ToolchainModernizationPhase.INVALID && errors.isEmpty()) "PASS" else "FAIL",
            phase = phase,
            errors = errors
        )
    }

    private fun requireRetainedCompletedStreams(
        roadmap: Map<String, Any?>,
        releaseState: Map<String, Any?>,
        semanticRoadmap: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        if (roadmap.string("primaryRoadmapStream") != "conformance" ||
            roadmap.string("currentDecision", "completedConformanceItem") != "C1.0" ||
            roadmap.string("currentDecision", "completedArchitectureItem") != "AR0.1" ||
            roadmap.string("currentDecision", "closureItem") != "0.9.7.10" ||
            roadmap.string("currentDecision", "closureItemStatus") != "completed"
        ) {
            errors += "Toolchain modernization must preserve the completed C1.0, AR0.1 and Core closure identities."
        }
        if (releaseState.string("roadmapState", "primaryStream") != "conformance" ||
            releaseState.string("roadmapState", "completedConformanceItem") != "C1.0" ||
            releaseState.string("roadmapState", "completedArchitectureItem") != "AR0.1" ||
            releaseState.string("roadmapState", "closureItem") != "0.9.7.10" ||
            releaseState.string("roadmapState", "closureItemStatus") != "completed"
        ) {
            errors += "Release state must preserve the same completed C1.0, AR0.1 and Core closure identities."
        }
        if (roadmap.string("currentDecision", "nextItem").isNotBlank() ||
            roadmap.string("currentDecision", "nextItemName").isNotBlank() ||
            roadmap.string("currentDecision", "nextItemStream").isNotBlank() ||
            releaseState.string("roadmapState", "nextItem").isNotBlank() ||
            releaseState.string("roadmapState", "nextItemName").isNotBlank() ||
            releaseState.string("roadmapState", "nextItemStream").isNotBlank()
        ) {
            errors += "An enabling toolchain milestone must not impersonate a product-roadmap successor."
        }
        if (semanticRoadmap.string("status") != "completed" ||
            semanticRoadmap.string("currentDecision", "completedItem") != "SI-08" ||
            semanticRoadmap.string("currentDecision", "nextItem").isNotBlank() ||
            semanticRoadmap.string("currentDecision", "nextItemName").isNotBlank()
        ) {
            errors += "Toolchain modernization may activate only after the semantic-integrity stream is terminally completed at SI-08."
        }
    }

    private fun requireVersionAlignment(
        roadmap: Map<String, Any?>,
        releaseState: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        val roadmapVersion = roadmap.string("roadmapVersion")
        val releaseRoadmapVersion = releaseState.string("roadmapState", "roadmapVersion")
        if (roadmapVersion.isBlank() || roadmapVersion != releaseRoadmapVersion) {
            errors += "Roadmap and release-state roadmapVersion must be present and identical."
        }
        val roadmapArtifacts = roadmap.map("versionBoundary").map("artifactContracts")
        val releaseArtifacts = releaseState.map("versionBoundary").map("artifactContracts")
        if (roadmapArtifacts.isEmpty() || roadmapArtifacts != releaseArtifacts) {
            errors += "Toolchain activation must not drift live artifact-contract versions between roadmap and release state."
        }
        if (roadmap.string("versionBoundary", "publishedPackageVersion") != "0.9.5" ||
            releaseState.string("versionBoundary", "publishedPackageVersion") != "0.9.5" ||
            roadmap.string("versionBoundary", "activePublicStandardVersion") != "0.8.0" ||
            releaseState.string("versionBoundary", "publicStandardVersion") != "0.8.0"
        ) {
            errors += "Toolchain modernization must retain implementation package 0.9.5 and public standard 0.8.0."
        }
    }

    private fun requireMilestoneShape(
        roadmap: Map<String, Any?>,
        releaseState: Map<String, Any?>,
        milestone: Map<String, Any?>,
        workPackage: Map<String, Any?>,
        phase: ToolchainModernizationPhase,
        errors: MutableList<String>
    ) {
        if (milestone.isEmpty()) {
            errors += "Roadmap must declare exactly one TOOLCHAIN-MODERNIZATION enabling milestone."
            return
        }
        if (milestone.string("workPackage") != WORK_PACKAGE ||
            workPackage.string("version") != MILESTONE_ID ||
            workPackage.string("name") != MILESTONE_NAME ||
            workPackage.string("type") != "enabling-maintenance" ||
            workPackage.string("stream") != "enabling-maintenance"
        ) {
            errors += "Toolchain milestone and work package identity must match exactly."
        }
        if (workPackage.string("authorization", "predecessor") != "SI-08" ||
            workPackage.string("authorization", "strategicSource") != STRATEGIC_SOURCE
        ) {
            errors += "Toolchain work package must be authorized by completed SI-08 and the dedicated strategic milestone."
        }

        val roadmapMilestone = roadmap.string("currentDecision", "activeEnablingMilestone")
        val releaseMilestone = releaseState.string("roadmapState", "activeEnablingMilestone")
        val roadmapMilestoneName = roadmap.string("currentDecision", "activeEnablingMilestoneName")
        val releaseMilestoneName = releaseState.string("roadmapState", "activeEnablingMilestoneName")
        when (phase) {
            ToolchainModernizationPhase.ACTIVE -> {
                if (roadmap.string("currentTrack") != "toolchain-modernization" ||
                    releaseState.string("roadmapState", "activeTrack") != "toolchain-modernization" ||
                    roadmapMilestone != MILESTONE_ID || releaseMilestone != MILESTONE_ID ||
                    roadmapMilestoneName != MILESTONE_NAME || releaseMilestoneName != MILESTONE_NAME ||
                    workPackage.string("authorization", "status") != "active"
                ) {
                    errors += "Active toolchain modernization must be selected consistently as the one enabling milestone in roadmap and release state."
                }
            }
            ToolchainModernizationPhase.COMPLETE -> {
                if (roadmapMilestone.isNotBlank() || releaseMilestone.isNotBlank() ||
                    roadmapMilestoneName.isNotBlank() || releaseMilestoneName.isNotBlank() ||
                    workPackage.string("authorization", "status") != "completed"
                ) {
                    errors += "Completed toolchain modernization must clear active enabling-milestone projections and close its authorization."
                }
            }
            ToolchainModernizationPhase.INVALID ->
                errors += "Toolchain modernization lifecycle must be either active or complete."
        }
    }

    private fun requireRepositoryMigrationState(
        workPackage: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        val baseline = workPackage.map("baseline")
        if (baseline.string("kotlin") != BASE_KOTLIN ||
            baseline.string("gradle") != BASE_GRADLE ||
            baseline.string("jdk") != BASE_JDK
        ) {
            errors += "Toolchain work package must retain the audited 1.9.24 / 8.10.2 / JDK 21 baseline."
        }

        val buildFile = File(rootDir, BUILD_FILE)
        val wrapperFile = File(rootDir, WRAPPER_FILE)
        if (!buildFile.isFile || !wrapperFile.isFile) {
            errors += "Toolchain lifecycle requires the production Gradle build file and wrapper properties."
            return
        }

        val build = buildFile.readText()
        val wrapper = wrapperFile.readText()
        val actualKotlin = Regex("kotlin\\(\"jvm\"\\)\\s+version\\s+\"([^\"]+)\"")
            .find(build)?.groupValues?.get(1).orEmpty()
        val actualGradle = Regex("gradle-([0-9.]+)-bin\\.zip")
            .find(wrapper)?.groupValues?.get(1).orEmpty()
        val actualJdk = Regex("jvmToolchain\\((\\d+)\\)")
            .find(build)?.groupValues?.get(1).orEmpty()

        val repositoryState = when {
            actualKotlin == BASE_KOTLIN && actualGradle == BASE_GRADLE && actualJdk == BASE_JDK ->
                RepositoryToolchainState.BASELINE
            actualKotlin == TARGET_KOTLIN && actualGradle == BASE_GRADLE && actualJdk == BASE_JDK ->
                RepositoryToolchainState.KOTLIN_MIGRATED
            actualKotlin == TARGET_KOTLIN && actualGradle == TARGET_GRADLE && actualJdk == BASE_JDK ->
                RepositoryToolchainState.GRADLE_MIGRATED
            actualKotlin == TARGET_KOTLIN && actualGradle == TARGET_GRADLE && actualJdk == TARGET_JDK ->
                RepositoryToolchainState.FINAL
            else -> RepositoryToolchainState.INVALID
        }

        if (repositoryState == RepositoryToolchainState.INVALID) {
            errors += "Repository toolchain must follow the approved sequence " +
                "$BASE_KOTLIN/$BASE_GRADLE/JDK$BASE_JDK -> " +
                "$TARGET_KOTLIN/$BASE_GRADLE/JDK$BASE_JDK -> " +
                "$TARGET_KOTLIN/$TARGET_GRADLE/JDK$BASE_JDK -> " +
                "$TARGET_KOTLIN/$TARGET_GRADLE/JDK$TARGET_JDK; got " +
                "Kotlin=$actualKotlin Gradle=$actualGradle JDK=$actualJdk."
            return
        }

        requireSequenceState(workPackage, repositoryState, errors)
    }

    private fun requireSequenceState(
        workPackage: Map<String, Any?>,
        repositoryState: RepositoryToolchainState,
        errors: MutableList<String>
    ) {
        val steps = workPackage.mapList("sequence")
        val expectedIds = listOf(KOTLIN_STEP, GRADLE_STEP, JDK_STEP, OFFLINE_STEP)
        if (steps.map { it.string("id") } != expectedIds) {
            errors += "Toolchain work package sequence must contain KOTLIN, GRADLE, JDK and OFFLINE-REFRESH exactly in that order."
            return
        }

        val kotlinStep = steps[0]
        val gradleStep = steps[1]
        val jdkStep = steps[2]
        val offlineStep = steps[3]
        when (repositoryState) {
            RepositoryToolchainState.BASELINE -> {
                requireStepStatus(kotlinStep, "planned", KOTLIN_STEP, errors)
                requireStepStatus(gradleStep, "planned", GRADLE_STEP, errors)
                requireStepStatus(jdkStep, "planned", JDK_STEP, errors)
                requireStepStatus(offlineStep, "planned", OFFLINE_STEP, errors)
            }
            RepositoryToolchainState.KOTLIN_MIGRATED -> {
                val kotlinStatus = kotlinStep.string("status")
                if (kotlinStatus !in setOf("implemented", "validated")) {
                    errors += "Kotlin 2.4.10 with Gradle 8.10.2 requires the KOTLIN step to be implemented or validated."
                }
                requireValidationEvidenceIfValidated(kotlinStep, KOTLIN_STEP, errors)
                requireStepStatus(gradleStep, "planned", GRADLE_STEP, errors)
                requireStepStatus(jdkStep, "planned", JDK_STEP, errors)
                requireStepStatus(offlineStep, "planned", OFFLINE_STEP, errors)
            }
            RepositoryToolchainState.GRADLE_MIGRATED -> {
                requireValidatedStep(kotlinStep, KOTLIN_STEP, errors)
                val gradleStatus = gradleStep.string("status")
                if (gradleStatus !in setOf("implemented", "validated")) {
                    errors += "Gradle 9.5.0 on JDK 21 requires the GRADLE step to be implemented or validated."
                }
                requireValidationEvidenceIfValidated(gradleStep, GRADLE_STEP, errors)
                requireStepStatus(jdkStep, "planned", JDK_STEP, errors)
                requireStepStatus(offlineStep, "planned", OFFLINE_STEP, errors)
            }
            RepositoryToolchainState.FINAL -> {
                requireValidatedStep(kotlinStep, KOTLIN_STEP, errors)
                requireValidatedStep(gradleStep, GRADLE_STEP, errors)
                val jdkStatus = jdkStep.string("status")
                if (jdkStatus !in setOf("implemented", "validated")) {
                    errors += "JDK 25 requires the JDK step to be implemented or validated."
                }
                requireValidationEvidenceIfValidated(jdkStep, JDK_STEP, errors)
                when (jdkStatus) {
                    "implemented" -> requireStepStatus(offlineStep, "planned", OFFLINE_STEP, errors)
                    "validated" -> requireOfflineStepState(offlineStep, errors)
                }
            }
            RepositoryToolchainState.INVALID -> Unit
        }
    }

    private fun requireStepStatus(
        step: Map<String, Any?>,
        expected: String,
        stepId: String,
        errors: MutableList<String>
    ) {
        if (step.string("status") != expected) {
            errors += "$stepId step must be '$expected' for the current repository toolchain state."
        }
        if (step.map("validationEvidence").isNotEmpty()) {
            errors += "$stepId step must not carry validation evidence while its status is '$expected'."
        }
    }

    private fun requireValidatedStep(
        step: Map<String, Any?>,
        stepId: String,
        errors: MutableList<String>
    ) {
        if (step.string("status") != "validated") {
            errors += "$stepId step must be 'validated' before the next toolchain version boundary is crossed."
            return
        }
        if (!evidence(step.map("validationEvidence")).valid) {
            errors += "$stepId validated status requires complete passed exact-head and merge-candidate Flow CI evidence."
        }
    }

    private fun requireValidationEvidenceIfValidated(
        step: Map<String, Any?>,
        stepId: String,
        errors: MutableList<String>
    ) {
        val status = step.string("status")
        val validationEvidence = evidence(step.map("validationEvidence"))
        when {
            status == "validated" && !validationEvidence.valid ->
                errors += "$stepId validated status requires complete passed exact-head and merge-candidate Flow CI evidence."
            status != "validated" && validationEvidence.present ->
                errors += "$stepId validation evidence may be authored only when the step status is 'validated'."
        }
    }

    private fun requireOfflineStepState(
        step: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        val status = step.string("status")
        if (status !in setOf("planned", "implemented", "validated")) {
            errors += "$OFFLINE_STEP step must be planned, implemented or validated after the JDK step is validated."
            return
        }
        requireValidationEvidenceIfValidated(step, OFFLINE_STEP, errors)
    }

    private fun requireSelectedTarget(
        workPackage: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        val selected = workPackage.map("selectedTarget")
        if (selected.string("kotlin") != TARGET_KOTLIN ||
            selected.string("gradle") != TARGET_GRADLE ||
            selected.string("jdk") != TARGET_JDK
        ) {
            errors += "Activated toolchain target must be Kotlin 2.4.10, Gradle 9.5.0 and JDK 25."
        }
        if (workPackage.string("localValidation", "status") !in setOf("pending", "passed")) {
            errors += "Toolchain local validation must be recorded honestly as pending or passed."
        }
    }

    private fun requireLifecycleEvidence(
        workPackage: Map<String, Any?>,
        phase: ToolchainModernizationPhase,
        errors: MutableList<String>
    ) {
        val implementation = evidence(workPackage.map("implementationEvidence"))
        val completion = evidence(workPackage.map("completionBoundary"))
        when (phase) {
            ToolchainModernizationPhase.ACTIVE -> {
                if (implementation.present && !implementation.valid) {
                    errors += "Authored toolchain implementation evidence must be a complete passed exact-head and merge-candidate Flow CI boundary."
                }
                if (completion.present) {
                    errors += "Active toolchain modernization must not contain completion evidence."
                }
            }
            ToolchainModernizationPhase.COMPLETE -> {
                if (!implementation.valid || !completion.valid || !completion.distinctFrom(implementation)) {
                    errors += "Completed toolchain modernization requires distinct passed implementation and completion Flow CI boundaries."
                }
                if (!workPackage.string("completionMergeCommit").matches(SHA_40)) {
                    errors += "Completed toolchain modernization requires the exact 40-character implementation merge commit."
                }
            }
            ToolchainModernizationPhase.INVALID -> Unit
        }
    }

    private fun evidence(raw: Map<String, Any?>): Evidence {
        if (raw.isEmpty()) return Evidence(false, false, "", "", "", "")
        val runNumber = raw.string("runNumber")
        val runId = raw.string("runId")
        val exactHead = raw.string("exactHead")
        val mergeCandidate = raw.string("mergeCandidate")
        val valid = raw.string("status") == "passed" &&
            raw.string("workflow") == "Flow CI" &&
            runNumber.toIntOrNull()?.let { it > 0 } == true &&
            runId.toLongOrNull()?.let { it > 0 } == true &&
            exactHead.matches(SHA_40) && mergeCandidate.matches(SHA_40) &&
            exactHead != mergeCandidate
        return Evidence(true, valid, runNumber, runId, exactHead, mergeCandidate)
    }

    private data class Evidence(
        val present: Boolean,
        val valid: Boolean,
        val runNumber: String,
        val runId: String,
        val exactHead: String,
        val mergeCandidate: String
    ) {
        fun distinctFrom(other: Evidence): Boolean =
            valid && other.valid &&
                runNumber != other.runNumber && runId != other.runId &&
                exactHead != other.exactHead && mergeCandidate != other.mergeCandidate
    }

    private enum class RepositoryToolchainState {
        BASELINE,
        KOTLIN_MIGRATED,
        GRADLE_MIGRATED,
        FINAL,
        INVALID
    }

    private fun requiredYaml(path: String): Map<String, Any?> {
        val file = File(rootDir, path)
        require(file.isFile) { "Required toolchain lifecycle file is missing: ${file.path}" }
        return FlowYaml.readMap(file)
    }

    private fun Map<String, Any?>.string(vararg path: String): String {
        var current: Any? = this
        path.forEach { key -> current = (current as? Map<*, *>)?.get(key) }
        return current?.toString().orEmpty()
    }

    private fun Map<String, Any?>.map(key: String): Map<String, Any?> =
        (get(key) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> =
        (get(key) as? Iterable<*>)?.mapNotNull { value ->
            (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        }.orEmpty()

    companion object {
        const val CHECK_ID = "roadmap.toolchain-modernization-lifecycle-integrity"
        const val ROADMAP = ".flow-agent/roadmap.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        const val SEMANTIC_INTEGRITY_ROADMAP = ".flow-agent/roadmap-semantic-integrity.yaml"
        const val WORK_PACKAGE = ".flow-agent/work-packages/TOOLCHAIN-MODERNIZATION.yaml"
        const val BUILD_FILE = "build.gradle.kts"
        const val WRAPPER_FILE = "gradle/wrapper/gradle-wrapper.properties"
        const val MILESTONE_ID = "TOOLCHAIN-MODERNIZATION"
        const val MILESTONE_NAME = "Kotlin and Gradle Toolchain Modernization"
        const val STRATEGIC_SOURCE = "docs/PROJECT_SEMANTIC_INTEGRITY_DIRECTION.md#enabling-milestone-kotlin-and-gradle-toolchain-modernization"
        private const val BASE_KOTLIN = "1.9.24"
        private const val BASE_GRADLE = "8.10.2"
        private const val TARGET_KOTLIN = "2.4.10"
        private const val TARGET_GRADLE = "9.5.0"
        private const val BASE_JDK = "21"
        private const val TARGET_JDK = "25"
        private const val KOTLIN_STEP = "KOTLIN"
        private const val GRADLE_STEP = "GRADLE"
        private const val JDK_STEP = "JDK"
        private const val OFFLINE_STEP = "OFFLINE-REFRESH"
        private val SHA_40 = Regex("^[0-9a-f]{40}$")
    }
}
