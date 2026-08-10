package org.flowlang.conformance

import java.io.File
import org.flowlang.roadmap.WorkflowBoundaryEvidence
import org.flowlang.serialization.FlowYaml

enum class OperationalDomainAdequacyLifecyclePhase {
    IMPLEMENTING,
    VALIDATING,
    COMPLETED,
    INVALID
}

data class OperationalDomainAdequacyLifecycleInput(
    val workPackageStatus: String,
    val c10Status: String,
    val conformanceRoadmapStatus: String,
    val conformanceCompletedItem: String,
    val conformanceNextItem: String,
    val architectureRoadmapStatus: String,
    val architectureCompletedItem: String,
    val architectureNextItem: String,
    val primaryStream: String,
    val indexCompletedConformanceItem: String,
    val indexCompletedArchitectureItem: String,
    val indexNextItem: String,
    val indexNextStream: String,
    val releasePrimaryStream: String,
    val releaseCompletedConformanceItem: String,
    val releaseCompletedArchitectureItem: String,
    val releaseNextItem: String,
    val activationEvidence: WorkflowBoundaryEvidence,
    val ar01CompletionBoundary: WorkflowBoundaryEvidence,
    val implementationEvidence: WorkflowBoundaryEvidence,
    val completionBoundary: WorkflowBoundaryEvidence,
    val requiredFilesPresent: Boolean
)

data class OperationalDomainAdequacyLifecycleReport(
    val status: String,
    val phase: OperationalDomainAdequacyLifecyclePhase,
    val errors: List<String>
)

/** Owns only the C1.0 lifecycle after the completed AR0.1 architecture boundary. */
class OperationalDomainAdequacyRoadmapLifecycleAuthority(
    private val rootDir: File = File(".")
) {
    fun analyze(): OperationalDomainAdequacyLifecycleReport {
        val workPackage = requiredYaml(WORK_PACKAGE)
        val ar01 = requiredYaml(AR01_WORK_PACKAGE)
        val conformanceRoadmap = requiredYaml(CONFORMANCE_ROADMAP)
        val architectureRoadmap = requiredYaml(ARCHITECTURE_ROADMAP)
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val releaseState = requiredYaml(RELEASE_STATE)
        return evaluate(
            OperationalDomainAdequacyLifecycleInput(
                workPackageStatus = workPackage.string("status"),
                c10Status = conformanceRoadmap.itemStatus("C1.0"),
                conformanceRoadmapStatus = conformanceRoadmap.string("status"),
                conformanceCompletedItem = conformanceRoadmap.string("currentDecision", "completedItem"),
                conformanceNextItem = conformanceRoadmap.string("currentDecision", "nextItem"),
                architectureRoadmapStatus = architectureRoadmap.string("status"),
                architectureCompletedItem = architectureRoadmap.string("currentDecision", "completedItem"),
                architectureNextItem = architectureRoadmap.string("currentDecision", "nextItem"),
                primaryStream = roadmap.string("primaryRoadmapStream"),
                indexCompletedConformanceItem = roadmap.string("currentDecision", "completedConformanceItem"),
                indexCompletedArchitectureItem = roadmap.string("currentDecision", "completedArchitectureItem"),
                indexNextItem = roadmap.string("currentDecision", "nextItem"),
                indexNextStream = roadmap.string("currentDecision", "nextItemStream"),
                releasePrimaryStream = releaseState.string("roadmapState", "primaryStream"),
                releaseCompletedConformanceItem = releaseState.string("roadmapState", "completedConformanceItem"),
                releaseCompletedArchitectureItem = releaseState.string("roadmapState", "completedArchitectureItem"),
                releaseNextItem = releaseState.string("roadmapState", "nextItem"),
                activationEvidence = workPackage.workflowEvidence("activationEvidence"),
                ar01CompletionBoundary = ar01.workflowEvidence("completionBoundary"),
                implementationEvidence = workPackage.workflowEvidence("implementationEvidence"),
                completionBoundary = workPackage.workflowEvidence("completionBoundary"),
                requiredFilesPresent = REQUIRED_FILES.all { File(rootDir, it).isFile }
            )
        )
    }

    fun evaluate(input: OperationalDomainAdequacyLifecycleInput): OperationalDomainAdequacyLifecycleReport {
        val phase = when {
            input.workPackageStatus == "active" &&
                input.c10Status == "next" &&
                !input.implementationEvidence.present &&
                !input.completionBoundary.present -> OperationalDomainAdequacyLifecyclePhase.IMPLEMENTING
            input.workPackageStatus == "active" &&
                input.c10Status == "next" &&
                input.implementationEvidence.structurallyValid &&
                !input.completionBoundary.present -> OperationalDomainAdequacyLifecyclePhase.VALIDATING
            input.workPackageStatus == "complete" &&
                input.c10Status == "completed" &&
                input.implementationEvidence.structurallyValid &&
                input.completionBoundary.structurallyValid -> OperationalDomainAdequacyLifecyclePhase.COMPLETED
            else -> OperationalDomainAdequacyLifecyclePhase.INVALID
        }

        val architectureClosed = input.architectureRoadmapStatus == "completed" &&
            input.architectureCompletedItem == "AR0.1" &&
            input.architectureNextItem.isBlank() &&
            input.indexCompletedArchitectureItem == "AR0.1" &&
            input.releaseCompletedArchitectureItem == "AR0.1"
        val localFocus = when (phase) {
            OperationalDomainAdequacyLifecyclePhase.IMPLEMENTING,
            OperationalDomainAdequacyLifecyclePhase.VALIDATING ->
                input.conformanceRoadmapStatus == "active" &&
                    input.conformanceCompletedItem == "C0.4" &&
                    input.conformanceNextItem == "C1.0"
            OperationalDomainAdequacyLifecyclePhase.COMPLETED ->
                input.conformanceRoadmapStatus == "completed" &&
                    input.conformanceCompletedItem == "C1.0" &&
                    input.conformanceNextItem.isBlank()
            OperationalDomainAdequacyLifecyclePhase.INVALID -> false
        }
        val globalFocus = when (phase) {
            OperationalDomainAdequacyLifecyclePhase.IMPLEMENTING,
            OperationalDomainAdequacyLifecyclePhase.VALIDATING ->
                input.primaryStream == "conformance" &&
                    input.indexCompletedConformanceItem == "C0.4" &&
                    input.releaseCompletedConformanceItem == "C0.4" &&
                    input.indexNextItem == "C1.0" &&
                    input.indexNextStream == "conformance" &&
                    input.releasePrimaryStream == "conformance" &&
                    input.releaseNextItem == "C1.0"
            OperationalDomainAdequacyLifecyclePhase.COMPLETED ->
                input.indexCompletedConformanceItem == "C1.0" &&
                    input.releaseCompletedConformanceItem == "C1.0"
            OperationalDomainAdequacyLifecyclePhase.INVALID -> false
        }
        val evidenceAligned = when (phase) {
            OperationalDomainAdequacyLifecyclePhase.IMPLEMENTING ->
                !input.implementationEvidence.present && !input.completionBoundary.present
            OperationalDomainAdequacyLifecyclePhase.VALIDATING ->
                input.implementationEvidence.follows(input.activationEvidence) && !input.completionBoundary.present
            OperationalDomainAdequacyLifecyclePhase.COMPLETED ->
                input.implementationEvidence.follows(input.activationEvidence) &&
                    input.completionBoundary.follows(input.implementationEvidence)
            OperationalDomainAdequacyLifecyclePhase.INVALID -> false
        }

        val errors = buildList {
            if (phase == OperationalDomainAdequacyLifecyclePhase.INVALID) {
                add("C1.0 must be exactly IMPLEMENTING, VALIDATING or COMPLETED.")
            }
            if (!architectureClosed) {
                add("C1.0 requires the architecture stream to remain terminally closed at completed AR0.1.")
            }
            if (!input.activationEvidence.sameBoundary(input.ar01CompletionBoundary)) {
                add("C1.0 activation evidence must equal the recorded AR0.1 completion boundary.")
            }
            if (!localFocus) add("C1.0 local conformance focus is inconsistent with lifecycle phase $phase.")
            if (!globalFocus) {
                add(
                    if (phase == OperationalDomainAdequacyLifecyclePhase.COMPLETED)
                        "Completed C1.0 identity is not retained in global roadmap and release state."
                    else
                        "C1.0 global roadmap and release focus is inconsistent with lifecycle phase $phase."
                )
            }
            if (!input.requiredFilesPresent) {
                add("C1.0 lifecycle is missing one or more required corpus, production, test, inventory or documentation files.")
            }
            if (!evidenceAligned) {
                add(
                    "C1.0 implementation and completion evidence is inconsistent with lifecycle phase $phase: " +
                        "implementation=${input.implementationEvidence.summary()} completion=${input.completionBoundary.summary()}."
                )
            }
        }
        return OperationalDomainAdequacyLifecycleReport(
            status = if (errors.isEmpty()) "PASS" else "FAIL",
            phase = phase,
            errors = errors
        )
    }

    private fun requiredYaml(path: String): Map<String, Any?> {
        val file = File(rootDir, path)
        require(file.isFile) { "Required C1.0 lifecycle evidence is missing: ${file.path}" }
        return FlowYaml.readMap(file)
    }

    private fun Map<String, Any?>.workflowEvidence(key: String): WorkflowBoundaryEvidence =
        WorkflowBoundaryEvidence.fromMap(map(key))

    private fun Map<String, Any?>.itemStatus(version: String): String =
        mapList("items").firstOrNull { it.string("version") == version }?.string("status").orEmpty()

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
        const val WORK_PACKAGE = ".flow-agent/work-packages/C1.0-operational-domain-adequacy.yaml"
        const val AR01_WORK_PACKAGE = ".flow-agent/work-packages/AR0.1-authority-responsibility-consolidation.yaml"
        const val CONFORMANCE_ROADMAP = ".flow-agent/roadmap-conformance.yaml"
        const val ARCHITECTURE_ROADMAP = ".flow-agent/roadmap-architecture.yaml"
        const val ROADMAP_INDEX = ".flow-agent/roadmap.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private val REQUIRED_FILES = listOf(
            OperationalDomainCorpusLoader.MANIFEST_SCHEMA,
            "schemas/operational-domain-case.schema.json",
            "conformance/corpus/operational/manifest.yaml",
            OperationalDomainAdequacyConformanceInventory.PATH,
            "src/main/kotlin/org/flowlang/conformance/OperationalDomainAdequacyContracts.kt",
            "src/main/kotlin/org/flowlang/conformance/OperationalDomainCorpusLoader.kt",
            "src/main/kotlin/org/flowlang/conformance/OperationalDomainAdequacyRoadmapLifecycleAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/OperationalDomainAdequacyConformanceChecks.kt",
            "src/test/kotlin/OperationalDomainCorpusLoaderTests.kt",
            "src/test/kotlin/OperationalDomainAdequacyRoadmapLifecycleAuthorityTests.kt",
            "src/test/kotlin/OperationalDomainAdequacyConformanceChecksTests.kt",
            "docs/C1_0_OPERATIONAL_DOMAIN_ADEQUACY.md"
        )
    }
}
