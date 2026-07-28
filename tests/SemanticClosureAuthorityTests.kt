import java.io.File
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
    fun activeBoundedCorrectionBlocksClosure() = withCorrectionRequiredEvidenceTree { root ->
        val report = SemanticClosureAuthority(root).evaluate(passingEvidence(root))

        assertEquals("FAIL", report.status)
        assertTrue("closure.no-active-corrections" in report.failedChecks)
    }

    @Test
    fun unknownBoundedCorrectionStatusFailsClosed() = withCorrectionRequiredEvidenceTree { root ->
        ReleaseLifecycleFixture.setCorrectionStatus(root, "pending")

        val report = SemanticClosureAuthority(root).evaluate(passingEvidence(root))

        assertEquals("FAIL", report.status)
        assertTrue("closure.no-active-corrections" in report.failedChecks)
        assertTrue(
            report.checklist.single { it.id == "closure.no-active-corrections" }
                .evidence.any { it.contains("invalid=") && it.contains("pending") }
        )
    }

    @Test
    fun missingBoundedCorrectionStatusFailsClosed() = withCorrectionRequiredEvidenceTree { root ->
        ReleaseLifecycleFixture.setCorrectionStatus(root, null)

        val report = SemanticClosureAuthority(root).evaluate(passingEvidence(root))

        assertEquals("FAIL", report.status)
        assertTrue("closure.no-active-corrections" in report.failedChecks)
        assertTrue(
            report.checklist.single { it.id == "closure.no-active-corrections" }
                .evidence.any { it.contains("<missing>") }
        )
    }

    private fun passingEvidence(root: File): List<ConformanceCheck> =
        ConformanceSuiteInventory.load(root).preClosureChecks.map { ConformanceCheck(it, true) }

    private fun withReadyEvidenceTree(assertions: (File) -> Unit) =
        ReleaseLifecycleFixture.withRoot(ReleaseLifecycleFixture.Phase.READY, assertions = assertions)

    private fun withCorrectionRequiredEvidenceTree(assertions: (File) -> Unit) =
        ReleaseLifecycleFixture.withRoot(
            ReleaseLifecycleFixture.Phase.CORRECTION_REQUIRED,
            assertions = assertions
        )
}
