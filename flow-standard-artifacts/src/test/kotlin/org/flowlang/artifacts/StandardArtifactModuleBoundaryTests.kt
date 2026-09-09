package org.flowlang.artifacts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.capabilities.TargetCapability
import org.flowlang.testing.ExternalCompilerProbe

class StandardArtifactModuleBoundaryTests {
    @Test fun reportContractsNeedNoVerifierImplementation() {
        assertTrue(StandardReleaseProfile.report().requiredConformanceChecks.isNotEmpty())
        assertTrue(StandardSurface.publicSurface().stableArtifacts.isNotEmpty())
        listOf("org.flowlang.conformance.ConformanceRunner",
            "org.flowlang.release.StandardReleaseAssemblyAuthority",
            "org.flowlang.distribution.reference.ReferenceTargetProjections",
            "org.flowlang.cli.honest.CliCommandCatalog").forEach { name ->
            assertFailsWith<ClassNotFoundException>(name) { Class.forName(name) }
        }
    }

    @Test fun theArtifactFactoryCannotInsertReferenceTargetsIntoAnEmptyCatalog() {
        val matrix = StandardSurface.targetSemanticsMatrix(emptyMap(), emptyMap())
        assertTrue(matrix.targetIds.isEmpty())
        assertTrue(matrix.entries.all { it.semanticsByTarget.isEmpty() })
    }

    @Test fun suppliedTargetNamesCannotSelectHiddenNativeImplementations() {
        val targets = listOf("jenkins", "github-actions", "tekton", "independent")
            .associateWith { TargetCapability(it, "Explicit caller-owned declaration") }
        val matrix = StandardSurface.targetSemanticsMatrix(targets, emptyMap())
        assertEquals(targets.keys.sorted(), matrix.targetIds)
        for (feature in listOf("approvals", "secrets", "artifacts")) {
            assertEquals(setOf("not-declared-review-only"),
                matrix.entries.single { it.feature == feature }.semanticsByTarget.values.toSet())
        }
    }

    @Test fun publicArtifactContractsCanBeConsumedIndependently() {
        ExternalCompilerProbe.accepts("""
            import org.flowlang.artifacts.StandardSurface
            import org.flowlang.capabilities.TargetCapability
            fun publish() = StandardSurface.targetSemanticsMatrix(
                mapOf("independent" to TargetCapability("independent", "caller owned")), emptyMap()
            )
        """.trimIndent())
    }

    @Test fun theReferenceCompositionIsNotAnArtifactLibraryDependency() {
        ExternalCompilerProbe.rejects(
            "val forbidden = org.flowlang.distribution.reference.ReferenceStandardArtifacts", "distribution")
    }

    @Test fun theConformanceImplementationIsNotAnArtifactLibraryDependency() {
        ExternalCompilerProbe.rejects(
            "val forbidden = org.flowlang.conformance.ConformanceRunner()", "conformance")
    }
}
