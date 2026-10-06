package org.flowlang.conformance

import kotlin.test.*
import org.flowlang.adapters.certification.*
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.generators.manifest.TargetStructuralProjectionKind

class AdapterCertificationProviderInventoryTests {
    @Test fun realProviderInventoriesRemainUncoveredWithoutIndependentRuns() {
        val catalogs = ReferenceTargetProjections.nativeCatalogs
        assertEquals(setOf("jenkins", "github-actions", "tekton"), catalogs.keys)
        catalogs.forEach { (target, catalog) ->
            assertTrue(catalog.definitions.isNotEmpty())
            // This synthetic identity tests inventory admission, not adapter certification.
            val identity = CertificationAdapterIdentity(target, "inventory-test", "test", "a".repeat(64))
            val rows = TargetStructuralProjectionKind.entries.map { CertificationSubject.Structural(it) } +
                catalog.definitions.map { CertificationSubject.Leaf(it.kind, it.reference) }
            val candidate = AdapterCertificationBundle(identity,
                rows.map { CertificationCoverage(it, emptyList(), "No AR-06 runtime evidence admitted.") },
                emptyList(), listOf("Inventory inspection only."))
            val report = AdapterCertificationAdmission.evaluate(candidate, identity, emptySet(), catalog,
                emptyList(), CertificationEvidenceResolver { error("An uncovered inventory must not request evidence.") })
            assertTrue(report.valid, "$target: ${report.findings}")
            assertTrue(report.admittedScenarioIds.isEmpty())
            val incomplete = AdapterCertificationAdmission.evaluate(candidate.copy(coverage = candidate.coverage.dropLast(1)),
                identity, emptySet(), catalog, emptyList(), CertificationEvidenceResolver { null })
            assertFalse(incomplete.valid, "$target cannot silently omit its native inventory")
        }
    }
}
