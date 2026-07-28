import java.io.File
import java.nio.file.Files
import org.flowlang.conformance.ConformanceSuiteInventory
import org.flowlang.release.SemanticClosureAuthority

/**
 * Builds lifecycle fixtures from explicit metadata rather than mutating the
 * repository's current lifecycle phase. Tests therefore remain stable when the
 * real branch advances from CORRECTION_REQUIRED to READY and finally CLOSED.
 */
object ReleaseLifecycleFixture {
    enum class Phase {
        CORRECTION_REQUIRED,
        READY,
        CLOSED
    }

    const val REPORT = "REPORT.md"
    const val RELEASE_STATE = ".flow-agent/release-state.yaml"
    const val ROADMAP = ".flow-agent/roadmap.yaml"
    const val CORE_ROADMAP = ".flow-agent/roadmap-core-v0.9.7.9.yaml"
    const val CORRECTION_WORK_PACKAGE =
        ".flow-agent/work-packages/v0.9.7.10.1-standard-closure-integrity.yaml"

    fun withRoot(
        phase: Phase,
        includeClosedEvidence: Boolean = true,
        assertions: (File) -> Unit
    ) {
        val root = Files.createTempDirectory("flow-release-lifecycle").toFile()
        try {
            copyStaticEvidence(root)
            writePhase(root, phase, includeClosedEvidence)
            assertions(root)
        } finally {
            root.deleteRecursively()
        }
    }

    fun writePhase(root: File, phase: Phase, includeClosedEvidence: Boolean = true) {
        val correctionStatus = if (phase == Phase.CORRECTION_REQUIRED) "active" else "complete"
        val closureWorkStatus = when (phase) {
            Phase.CORRECTION_REQUIRED -> "correction-required"
            Phase.READY -> "active"
            Phase.CLOSED -> "complete"
        }
        val closureItemStatus = when (phase) {
            Phase.CORRECTION_REQUIRED -> "correction-required"
            Phase.READY -> "next"
            Phase.CLOSED -> "completed"
        }
        val trackStatus = if (phase == Phase.CLOSED) "completed" else "active"
        val completedItem = if (phase == Phase.CLOSED) "0.9.7.10" else "0.9.7.9"
        val completedName = if (phase == Phase.CLOSED) {
            "Bounded Semantic Closure Gate"
        } else {
            "Intent Lowering and Diagnostic Honesty"
        }

        writeCorrectionWorkPackage(root, correctionStatus)
        writeClosureWorkPackage(root, phase, closureWorkStatus, includeClosedEvidence)
        writeCoreRoadmap(root, trackStatus, closureItemStatus)
        writeRoadmap(root, phase, completedItem, completedName, closureItemStatus)
        writeReleaseState(root, phase, completedItem, completedName, closureItemStatus)
        writeReport(root, phase, correctionStatus, closureItemStatus)
        writeChangelog(root)
    }

    fun setCorrectionStatus(root: File, status: String?) {
        val file = File(root, CORRECTION_WORK_PACKAGE)
        val lines = file.readText().lines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith("status:") }
        require(index >= 0) { "Correction status is missing in ${file.path}" }
        if (status == null) {
            lines.removeAt(index)
        } else {
            lines[index] = "status: $status"
        }
        file.writeText(lines.joinToString("\n").trimEnd() + "\n")
    }

    fun setClosureWorkPackageStatus(root: File, status: String) {
        replaceTopLevelStatus(File(root, SemanticClosureAuthority.WORK_PACKAGE), status)
    }

    fun setCoreTrackStatus(root: File, status: String) {
        replaceTopLevelStatus(File(root, CORE_ROADMAP), status)
    }

    fun setCoreClosureStatus(root: File, status: String) {
        val file = File(root, CORE_ROADMAP)
        val text = file.readText()
        val pattern = Regex(
            "(?ms)(^\\s*- version:\\s*\"0\\.9\\.7\\.10\"\\s*$.*?^\\s*status:\\s*)([a-z-]+)(\\s*$)"
        )
        val match = pattern.find(text) ?: error("Closure roadmap item 0.9.7.10 is missing.")
        file.writeText(text.replaceRange(match.range, match.groupValues[1] + status + match.groupValues[3]))
    }

    fun setClosureItemStatus(file: File, status: String) = replaceScalar(file, "closureItemStatus", status)

    fun setCompletedItem(file: File, version: String, name: String) {
        replaceScalar(file, "completedItem", version)
        replaceScalar(file, "completedItemName", name)
    }

    fun setRoadmapCorrectionState(root: File, status: String, activePointer: Boolean) {
        val file = File(root, ROADMAP)
        replaceScalar(file, "correctionState", status)
        replaceScalar(
            file,
            "activeCorrectionWorkPackage",
            if (activePointer) CORRECTION_WORK_PACKAGE else ""
        )
        replaceScalar(
            file,
            "activeCorrectionWorkPackageName",
            if (activePointer) "Standard and Closure Integrity Correction" else ""
        )
    }

    fun addNextProjection(file: File) {
        if (file.readText().lineSequence().any { it.trimStart().startsWith("nextCoreItem:") }) return
        val text = file.readText()
        val anchor = Regex("(?m)^(\\s*)closureItemStatus:\\s*\"?[^\"\\n]+\"?\\s*$")
            .find(text) ?: error("closureItemStatus missing in ${file.path}")
        val indent = anchor.groupValues[1]
        val addition = "\n${indent}nextCoreItem: \"0.9.7.10\"" +
            "\n${indent}nextCoreItemName: \"Bounded Semantic Closure Gate\"" +
            "\n${indent}nextCoreItemStatus: \"next\""
        file.writeText(text.replaceRange(anchor.range, anchor.value + addition))
    }

    fun removeNextProjection(file: File) {
        val retained = file.readText().lines().filterNot { line ->
            line.trimStart().startsWith("nextCoreItem:") ||
                line.trimStart().startsWith("nextCoreItemName:") ||
                line.trimStart().startsWith("nextCoreItemStatus:")
        }
        file.writeText(retained.joinToString("\n").trimEnd() + "\n")
    }

    private fun copyStaticEvidence(root: File) {
        listOf(
            "build.gradle.kts",
            ConformanceSuiteInventory.PATH
        ).forEach { path ->
            val source = File(path)
            require(source.isFile) { "Required fixture source is missing: $path" }
            val destination = File(root, path)
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
    }

    private fun writeCorrectionWorkPackage(root: File, status: String) {
        write(
            root,
            CORRECTION_WORK_PACKAGE,
            """
            version: "0.9.7.10.1"
            name: "Standard and Closure Integrity Correction"
            type: "bounded-correction"
            stream: core
            status: $status
            parentCoreItem: "0.9.7.10"
            """.trimIndent()
        )
    }

    private fun writeClosureWorkPackage(
        root: File,
        phase: Phase,
        status: String,
        includeClosedEvidence: Boolean
    ) {
        val lifecycleEvidence = when (phase) {
            Phase.CORRECTION_REQUIRED -> """
                supersededByCorrection: "0.9.7.10.1"
                validationEvidence:
                  status: passed
                  workflow: "Flow CI"
                  runNumber: "2183"
                  runId: "30255409444"
                  exactHead: "1111111111111111111111111111111111111111"
                  mergeCandidate: "2222222222222222222222222222222222222222"
            """.trimIndent()
            Phase.READY -> ""
            Phase.CLOSED -> if (includeClosedEvidence) {
                """
                validationEvidence:
                  status: passed
                  workflow: "Flow CI"
                  runNumber: "9999"
                  runId: "123456789"
                  exactHead: "3333333333333333333333333333333333333333"
                  mergeCandidate: "4444444444444444444444444444444444444444"
                """.trimIndent()
            } else {
                ""
            }
        }
        val suffix = lifecycleEvidence.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        write(
            root,
            SemanticClosureAuthority.WORK_PACKAGE,
            """
            version: "0.9.7.10"
            name: "Bounded Semantic Closure Gate"
            type: "architecture-closure"
            stream: core
            status: $status
            closureChecklist:
              - "closure.checklist-exact"
              - "closure.no-active-corrections"
              - "closure.prior-core-items-complete"
              - "closure.release-metadata-honest"
              - "closure.required-checks-present"
              - "closure.required-checks-pass"
              - "closure.no-failed-conformance"
              - "closure.version-boundary-unchanged"
              - "closure.reference-evidence-live"$suffix
            """.trimIndent()
        )
    }

    private fun writeCoreRoadmap(root: File, trackStatus: String, closureItemStatus: String) {
        val priorItems = (1..9).joinToString("\n") { item ->
            """
              - version: "0.9.7.$item"
                name: "Completed Core Item $item"
                status: completed
            """.trimIndent()
        }
        write(
            root,
            CORE_ROADMAP,
            """
            project: Flow Core
            stream: core
            roadmapVersion: 5
            track: v0.9.7-universal-semantic-foundation
            status: $trackStatus
            items:
            $priorItems
              - version: "0.9.7.10"
                name: "Bounded Semantic Closure Gate"
                status: $closureItemStatus
            """.trimIndent()
        )
    }

    private fun writeRoadmap(
        root: File,
        phase: Phase,
        completedItem: String,
        completedName: String,
        closureItemStatus: String
    ) {
        val active = phase == Phase.CORRECTION_REQUIRED
        val next = if (phase == Phase.READY) {
            """
              nextCoreItem: "0.9.7.10"
              nextCoreItemName: "Bounded Semantic Closure Gate"
              nextCoreItemStatus: "next"
            """.trimIndent()
        } else {
            ""
        }
        write(
            root,
            ROADMAP,
            """
            project: Flow Core
            roadmapVersion: 5
            currentDecision:
              completedItem: "$completedItem"
              completedItemName: "$completedName"
              correctionState: "${if (active) "active" else "complete"}"
              activeCorrectionWorkPackage: "${if (active) CORRECTION_WORK_PACKAGE else ""}"
              activeCorrectionWorkPackageName: "${if (active) "Standard and Closure Integrity Correction" else ""}"
              closureItem: "0.9.7.10"
              closureItemName: "Bounded Semantic Closure Gate"
              closureItemStatus: "$closureItemStatus"
            ${next.prependIndent("  ").trimEnd()}
            """.trimIndent().lines().filterNot { it.isBlank() }.joinToString("\n")
        )
    }

    private fun writeReleaseState(
        root: File,
        phase: Phase,
        completedItem: String,
        completedName: String,
        closureItemStatus: String
    ) {
        val next = if (phase == Phase.READY) {
            """
              nextCoreItem: "0.9.7.10"
              nextCoreItemName: "Bounded Semantic Closure Gate"
              nextCoreItemStatus: "next"
            """.trimIndent()
        } else {
            ""
        }
        write(
            root,
            RELEASE_STATE,
            """
            project: Flow Core
            stateVersion: 24
            currentVersion: "0.9.5"
            activeStandardVersion: "0.8.0"
            versionBoundary:
              publishedPackageVersion: "0.9.5"
              publicStandardVersion: "0.8.0"
              artifactContractVersion: "2.0"
            roadmapState:
              completedItem: "$completedItem"
              completedItemName: "$completedName"
              closureItem: "0.9.7.10"
              closureItemName: "Bounded Semantic Closure Gate"
              closureItemStatus: "$closureItemStatus"
            ${next.prependIndent("  ").trimEnd()}
              activeRoadmaps:
                core: "$CORE_ROADMAP"
            lastKnownValidation:
              validationSource: "Flow CI #2183 passed exact completion-metadata head aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa before merge."
              notes:
                - "Flow CI #2231 supplied external exact-head CI evidence for the candidate."
            """.trimIndent().lines().filterNot { it.isBlank() }.joinToString("\n")
        )
    }

    private fun writeReport(root: File, phase: Phase, correctionStatus: String, closureItemStatus: String) {
        val correctionLabel = if (correctionStatus == "active") "Active" else "Completed"
        val closureLine = when (phase) {
            Phase.CORRECTION_REQUIRED ->
                "Core closure correction: `0.9.7.10 Bounded Semantic Closure Gate` (`correction-required`)"
            Phase.READY ->
                "Next Core roadmap item: `0.9.7.10 Bounded Semantic Closure Gate` (`next`)"
            Phase.CLOSED ->
                "Completed Core closure item: `0.9.7.10 Bounded Semantic Closure Gate` (`completed`)"
        }
        write(
            root,
            REPORT,
            """
            # Flow Core Report

            Current published package line: `0.9.5`
            Active public standard version: `0.8.0`
            $correctionLabel correction item: `0.9.7.10.1 Standard and Closure Integrity Correction`
            Core roadmap item status: `$closureItemStatus`
            $closureLine
            """.trimIndent()
        )
    }

    private fun writeChangelog(root: File) {
        write(
            root,
            "CHANGELOG-v0.9.7.10.md",
            """
            # v0.9.7.10 correction track

            ### v0.9.7.10.1 Standard and Closure Integrity Correction
            """.trimIndent()
        )
        write(root, "CHANGELOG-v0.9.7.9.md", "# v0.9.7.9 correction track")
    }

    private fun replaceTopLevelStatus(file: File, status: String) {
        val text = file.readText()
        val pattern = Regex("(?m)^(status:\\s*)([a-z-]+)(\\s*)$")
        val match = pattern.find(text) ?: error("Top-level status is missing in ${file.path}")
        file.writeText(text.replaceRange(match.range, match.groupValues[1] + status + match.groupValues[3]))
    }

    private fun replaceScalar(file: File, key: String, value: String) {
        val text = file.readText()
        val pattern = Regex("(?m)^(\\s*${Regex.escape(key)}:\\s*)[^\\n#]+(\\s*)$")
        val match = pattern.find(text) ?: error("$key is missing in ${file.path}")
        file.writeText(text.replaceRange(match.range, match.groupValues[1] + "\"$value\"" + match.groupValues[2]))
    }

    private fun write(root: File, path: String, content: String) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(content.trimEnd() + "\n")
    }
}
