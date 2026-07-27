import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.BoundedSemanticClosureAuthority
import org.flowlang.conformance.BoundedSemanticClosureCatalog
import org.flowlang.conformance.ConformanceCheck

class BoundedSemanticClosureGateTests {
    private val authority = BoundedSemanticClosureAuthority(File("."))
    private val passingChecks = BoundedSemanticClosureCatalog.declaredPriorChecks.map {
        ConformanceCheck(it, true)
    }

    @Test
    fun completedClosurePassesOnlyWhenEveryDeclaredEvidencePasses() {
        val report = authority.analyze(passingChecks)

        assertEquals("PASS", report.status, report.checks.filter { it.status == "FAIL" }.joinToString { "${it.id}:${it.observed}" })
        assertEquals("CLOSED", report.phase)
        assertTrue(report.incompleteCoreItems.isEmpty())
        assertTrue(report.missingConformanceChecks.isEmpty())
        assertTrue(report.unexpectedConformanceChecks.isEmpty())
        assertTrue(report.duplicateConformanceChecks.isEmpty())
        assertTrue(report.failedConformanceChecks.isEmpty())
        assertTrue(report.missingReleaseProfileChecks.isEmpty())
        assertTrue(report.missingAnchoredRuntimeChecks.isEmpty())
        assertTrue(report.missingRetainedReferenceChecks.isEmpty())
        assertTrue(report.standardModelIssues.isEmpty())
    }

    @Test
    fun omittedDeclaredCheckFailsClosure() {
        val removed = passingChecks.first()
        val report = authority.analyze(passingChecks.drop(1))

        assertEquals("FAIL", report.status)
        assertTrue(removed.name in report.missingConformanceChecks)
        assertTrue("closure.declared-check-set" in report.failedChecks)
    }

    @Test
    fun failingPriorCheckFailsClosure() {
        val first = passingChecks.first()
        val report = authority.analyze(
            listOf(ConformanceCheck(first.name, false, "synthetic failure")) + passingChecks.drop(1)
        )

        assertEquals("FAIL", report.status)
        assertTrue(first.name in report.failedConformanceChecks)
        assertTrue("closure.all-prior-checks-pass" in report.failedChecks)
    }

    @Test
    fun duplicateEvidenceFailsClosure() {
        val first = passingChecks.first()
        val report = authority.analyze(passingChecks + ConformanceCheck(first.name, true))

        assertEquals("FAIL", report.status)
        assertTrue(first.name in report.duplicateConformanceChecks)
        assertTrue("closure.declared-check-set" in report.failedChecks)
    }

    @Test
    fun closureCannotUseItsOwnPassResult() {
        val report = authority.analyze(
            passingChecks + ConformanceCheck(BoundedSemanticClosureCatalog.CLOSURE_CHECK_ID, true)
        )

        assertEquals("FAIL", report.status)
        assertTrue(BoundedSemanticClosureCatalog.CLOSURE_CHECK_ID in report.unexpectedConformanceChecks)
        assertTrue("closure.declared-check-set" in report.failedChecks)
    }
}
