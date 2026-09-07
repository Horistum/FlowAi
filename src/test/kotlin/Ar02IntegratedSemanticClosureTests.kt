package org.flowlang.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.Ar02FindingClosureCatalog
import org.flowlang.conformance.Ar02FindingClosureEvidenceValidator
import org.flowlang.conformance.Ar02IntegratedSemanticClosureChecks
import org.flowlang.conformance.ArchitectureRecoveryConformanceInventory

class Ar02IntegratedSemanticClosureTests {
    private val root = File(".")

    @Test
    fun integratedClosureMatrixPassesAsOneAtomicBoundary() {
        val checks = Ar02IntegratedSemanticClosureChecks(root).checks()

        assertEquals(
            setOf(
                Ar02IntegratedSemanticClosureChecks.FRONTEND_MATRIX,
                Ar02IntegratedSemanticClosureChecks.MUTATION_MATRIX,
                Ar02IntegratedSemanticClosureChecks.TARGET_MATRIX,
                Ar02IntegratedSemanticClosureChecks.PUBLIC_COMPATIBILITY_MATRIX,
                Ar02IntegratedSemanticClosureChecks.FINDING_CLOSURE
            ),
            checks.map { it.name }.toSet()
        )
        assertTrue(
            checks.all { it.passed },
            checks.filterNot { it.passed }.joinToString(" | ") { "${it.name}: ${it.message}" }
        )
    }

    @Test
    fun findingCatalogMapsEveryAr02FindingToProductionPositiveAndNegativeEvidence() {
        val inventory = ArchitectureRecoveryConformanceInventory.load(root)
        val errors = Ar02FindingClosureEvidenceValidator.errors(
            entries = Ar02FindingClosureCatalog.entries,
            declaredChecks = inventory.checks.toSet(),
            rootDir = root
        )

        assertTrue(errors.isEmpty(), errors.joinToString(" | "))
        assertEquals(
            setOf("F-02", "F-08", "F-15"),
            Ar02FindingClosureCatalog.entries.map { it.findingId }.toSet()
        )
    }

    @Test
    fun closureEvidenceCannotPassWithoutNegativePolarity() {
        val inventory = ArchitectureRecoveryConformanceInventory.load(root)
        val invalid = Ar02FindingClosureCatalog.entries.map { evidence ->
            if (evidence.findingId == "F-02") evidence.copy(negativeChecks = emptyList()) else evidence
        }
        val errors = Ar02FindingClosureEvidenceValidator.errors(
            entries = invalid,
            declaredChecks = inventory.checks.toSet(),
            rootDir = root
        )

        assertTrue(errors.any { "F-02 has no negative or mutation evidence" in it }, errors.joinToString(" | "))
    }

    @Test
    fun closureEvidenceCannotReferenceMissingProductionOrConformanceEvidence() {
        val inventory = ArchitectureRecoveryConformanceInventory.load(root)
        val invalid = Ar02FindingClosureCatalog.entries.map { evidence ->
            if (evidence.findingId == "F-08") {
                evidence.copy(
                    productionPaths = evidence.productionPaths + "missing/ar02e-production-boundary.kt",
                    negativeChecks = evidence.negativeChecks + "architecture-recovery.ar-02.missing-mutant"
                )
            } else {
                evidence
            }
        }
        val errors = Ar02FindingClosureEvidenceValidator.errors(
            entries = invalid,
            declaredChecks = inventory.checks.toSet(),
            rootDir = root
        )

        assertTrue(errors.any { "production evidence path is missing" in it }, errors.joinToString(" | "))
        assertTrue(errors.any { "references undeclared conformance check" in it }, errors.joinToString(" | "))
    }
}
