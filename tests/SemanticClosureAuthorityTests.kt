import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.ConformanceCheck
import org.flowlang.conformance.ConformanceSuiteInventory
import org.flowlang.release.SemanticClosureAuthority
import org.flowlang.standard.StandardModel

class SemanticClosureAuthorityTests {
    @Test
    fun completeDeclaredEvidencePassesFiniteClosure() = withReadyEvidenceTree { root ->
        val report = SemanticClosureAuthority(root).evaluate(passingEvidence(root))

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals(SemanticClosureAuthority.CHECKLIST, report.checklist.map { it.id })
        assertTrue(report.checklist.all { it.status == "PASS" })
    }

    @Test
    fun missingRequiredReleaseCheckFailsClosure() = withReadyEvidenceTree { root ->
        val missing = StandardModel.releaseProfileCheckIds().first()
        val report = SemanticClosureAuthority(root).evaluate(
            passingEvidence(root).filterNot { it.name == missing }
        )

        assertEquals("FAIL", report.status)
        assertTrue("closure.required-checks-present" in report.failedChecks)
    }

    @Test
    fun missingNonReleaseCheckFailsCompleteInventoryPresence() = withReadyEvidenceTree { root ->
        val releaseProfile = StandardModel.releaseProfileCheckIds().toSet()
        val missing = ConformanceSuiteInventory.load(root).preClosureChecks.first { it !in releaseProfile }
        val report = SemanticClosureAuthority(root).evaluate(
            passingEvidence(root).filterNot { it.name == missing }
        )

        assertEquals("FAIL", report.status)
        assertTrue("closure.required-checks-present" in report.failedChecks)
    }

    @Test
    fun reorderedConformanceSequenceFailsCompleteInventoryPresence() = withReadyEvidenceTree { root ->
        val reordered = passingEvidence(root).toMutableList().also {
            val first = it.removeAt(0)
            it.add(1, first)
        }
        val report = SemanticClosureAuthority(root).evaluate(reordered)

        assertEquals("FAIL", report.status)
        assertTrue("closure.required-checks-present" in report.failedChecks)
    }

    @Test
    fun failedNonReleaseCheckCannotBeHiddenByPassingReleaseProfile() = withReadyEvidenceTree { root ->
        val releaseProfile = StandardModel.releaseProfileCheckIds().toSet()
        val failing = ConformanceSuiteInventory.load(root).preClosureChecks.first { it !in releaseProfile }
        val evidence = passingEvidence(root).map { check ->
            if (check.name == failing) check.copy(passed = false) else check
        }
        val report = SemanticClosureAuthority(root).evaluate(evidence)

        assertEquals("FAIL", report.status)
        assertTrue("closure.no-failed-conformance" in report.failedChecks)
    }

    @Test
    fun activeBoundedCorrectionBlocksClosure() = withEvidenceTree { root ->
        val report = SemanticClosureAuthority(root).evaluate(passingEvidence(root))

        assertEquals("FAIL", report.status)
        assertTrue("closure.no-active-corrections" in report.failedChecks)
    }

    @Test
    fun unknownBoundedCorrectionStatusFailsClosed() = withEvidenceTree { root ->
        val correction = correctionFile(root)
        correction.writeText(correction.readText().replaceFirst("status: active", "status: pending"))

        val report = SemanticClosureAuthority(root).evaluate(passingEvidence(root))

        assertEquals("FAIL", report.status)
        assertTrue("closure.no-active-corrections" in report.failedChecks)
        assertTrue(report.checklist.single { it.id == "closure.no-active-corrections" }
            .evidence.any { it.contains("invalid=") && it.contains("pending") })
    }

    @Test
    fun missingBoundedCorrectionStatusFailsClosed() = withEvidenceTree { root ->
        val correction = correctionFile(root)
        correction.writeText(correction.readText().replaceFirst(Regex("(?m)^status: active\\s*\\n"), ""))

        val report = SemanticClosureAuthority(root).evaluate(passingEvidence(root))

        assertEquals("FAIL", report.status)
        assertTrue("closure.no-active-corrections" in report.failedChecks)
        assertTrue(report.checklist.single { it.id == "closure.no-active-corrections" }
            .evidence.any { it.contains("<missing>") })
    }

    private fun passingEvidence(root: File): List<ConformanceCheck> =
        ConformanceSuiteInventory.load(root).preClosureChecks.map { ConformanceCheck(it, true) }

    private fun correctionFile(root: File): File = File(root, CORRECTION_WORK_PACKAGE)

    private fun withReadyEvidenceTree(assertions: (File) -> Unit) = withEvidenceTree { root ->
        readyMetadata(root)
        assertions(root)
    }

    private fun withEvidenceTree(assertions: (File) -> Unit) {
        val root = Files.createTempDirectory("flow-semantic-closure").toFile()
        try {
            copyEvidenceTree(root)
            assertions(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun readyMetadata(root: File) {
        val correction = correctionFile(root)
        correction.writeText(correction.readText().replaceFirst("status: active", "status: complete"))

        val closure = File(root, SemanticClosureAuthority.WORK_PACKAGE)
        closure.writeText(
            removeTopLevelSection(
                removeTopLevelSection(
                    closure.readText().replaceFirst("status: correction-required", "status: active"),
                    "supersededByCorrection"
                ),
                "validationEvidence"
            ).trimEnd() + "\n"
        )

        val core = File(root, CORE_ROADMAP)
        core.writeText(updateCoreClosureStatus(core.readText(), "next"))

        val roadmap = File(root, ROADMAP)
        roadmap.writeText(
            addReadyNextProjection(
                roadmap.readText()
                    .replaceFirst("correctionState: \"active\"", "correctionState: \"complete\"")
                    .replaceFirst(
                        "activeCorrectionWorkPackage: \"$CORRECTION_WORK_PACKAGE\"",
                        "activeCorrectionWorkPackage: \"\""
                    )
                    .replaceFirst(
                        "activeCorrectionWorkPackageName: \"Standard and Closure Integrity Correction\"",
                        "activeCorrectionWorkPackageName: \"\""
                    )
                    .replaceFirst("closureItemStatus: \"correction-required\"", "closureItemStatus: \"next\"")
            )
        )

        val releaseState = File(root, RELEASE_STATE)
        releaseState.writeText(
            addReadyNextProjection(
                releaseState.readText()
                    .replaceFirst("closureItemStatus: \"correction-required\"", "closureItemStatus: \"next\"")
            )
        )

        val report = File(root, REPORT)
        report.writeText(
            report.readText()
                .replaceFirst("Active correction item:", "Completed correction item:")
                .replaceFirst("Core roadmap item status: `correction-required`", "Core roadmap item status: `next`")
                .replaceFirst(
                    "Core closure correction: `0.9.7.10 Bounded Semantic Closure Gate` (`correction-required`)",
                    "Next Core roadmap item: `0.9.7.10 Bounded Semantic Closure Gate` (`next`)"
                )
        )
    }

    private fun addReadyNextProjection(text: String): String {
        require("nextCoreItem:" !in text) { "READY fixture source already contains a nextCoreItem projection." }
        val anchor = "  closureItemStatus: \"next\""
        require(anchor in text) { "READY fixture has no closureItemStatus anchor." }
        return text.replaceFirst(
            anchor,
            "$anchor\n  nextCoreItem: \"0.9.7.10\"\n  nextCoreItemName: \"Bounded Semantic Closure Gate\"\n  nextCoreItemStatus: \"next\""
        )
    }

    private fun removeTopLevelSection(text: String, section: String): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { it == "$section:" || it.startsWith("$section: ") }
        if (start < 0) return text
        val end = (start + 1 until lines.size).firstOrNull { index ->
            lines[index].isNotBlank() && !lines[index].first().isWhitespace()
        } ?: lines.size
        return (lines.take(start) + lines.drop(end)).joinToString("\n")
    }

    private fun updateCoreClosureStatus(text: String, status: String): String {
        val pattern = Regex(
            "(?ms)(^\\s*- version:\\s*[\"']?0\\.9\\.7\\.10[\"']?\\s*$.*?^\\s*status:\\s*)([a-z-]+)(\\s*$)"
        )
        val match = pattern.find(text) ?: error("Closure roadmap item 0.9.7.10 is missing.")
        return text.replaceRange(match.range, match.groupValues[1] + status + match.groupValues[3])
    }

    private fun copyEvidenceTree(root: File) {
        listOf(
            "build.gradle.kts",
            REPORT,
            "CHANGELOG-v0.9.7.9.md",
            "CHANGELOG-v0.9.7.10.md",
            RELEASE_STATE,
            ROADMAP,
            CORE_ROADMAP,
            SemanticClosureAuthority.WORK_PACKAGE,
            ConformanceSuiteInventory.PATH,
            CORRECTION_WORK_PACKAGE,
            ".flow-agent/work-packages/v0.9.7.9.11-public-artifact-evidence-verification-integrity.yaml"
        ).forEach { path ->
            val source = File(path)
            val destination = File(root, path)
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
    }

    companion object {
        private const val REPORT = "REPORT.md"
        private const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private const val ROADMAP = ".flow-agent/roadmap.yaml"
        private const val CORE_ROADMAP = ".flow-agent/roadmap-core-v0.9.7.9.yaml"
        private const val CORRECTION_WORK_PACKAGE =
            ".flow-agent/work-packages/v0.9.7.10.1-standard-closure-integrity.yaml"
    }
}
