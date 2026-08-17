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
            requireRepositoryBaseline(workPackage, this)
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

    private fun requireRepositoryBaseline(
        workPackage: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        val baseline = workPackage.map("baseline")
        if (baseline.string("kotlin") != BASE_KOTLIN ||
            baseline.string("gradle") != BASE_GRADLE ||
            baseline.string("jdk") != JDK
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
        val selectedKotlin = workPackage.string("selectedTarget", "kotlin")
        val selectedGradle = workPackage.string("selectedTarget", "gradle")
        val allowedKotlin = setOf(BASE_KOTLIN, selectedKotlin)
        val allowedGradle = setOf(BASE_GRADLE, selectedGradle)
        val actualKotlin = Regex("kotlin\\(\"jvm\"\\)\\s+version\\s+\"([^\"]+)\"")
            .find(build)?.groupValues?.get(1).orEmpty()
        val actualGradle = Regex("gradle-([0-9.]+)-bin\\.zip")
            .find(wrapper)?.groupValues?.get(1).orEmpty()
        val actualJdk = Regex("jvmToolchain\\((\\d+)\\)")
            .find(build)?.groupValues?.get(1).orEmpty()
        if (actualKotlin !in allowedKotlin || actualGradle !in allowedGradle || actualJdk != JDK) {
            errors += "Repository toolchain must remain on the audited baseline or one explicitly selected migration endpoint; got Kotlin=$actualKotlin Gradle=$actualGradle JDK=$actualJdk."
        }
    }

    private fun requireSelectedTarget(
        workPackage: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        val selected = workPackage.map("selectedTarget")
        if (selected.string("kotlin") != TARGET_KOTLIN ||
            selected.string("gradle") != TARGET_GRADLE ||
            selected.string("jdk") != JDK
        ) {
            errors += "Activated toolchain target must be Kotlin 2.4.10, Gradle 9.5.0 and JDK 21."
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
        const val STRATEGIC_SOURCE = "docs/PROJECT_DIRECTION_AFTER_C1_0.md#enabling-milestone-kotlin-and-gradle-toolchain-modernization"
        private const val BASE_KOTLIN = "1.9.24"
        private const val BASE_GRADLE = "8.10.2"
        private const val TARGET_KOTLIN = "2.4.10"
        private const val TARGET_GRADLE = "9.5.0"
        private const val JDK = "21"
        private val SHA_40 = Regex("^[0-9a-f]{40}$")
    }
}
