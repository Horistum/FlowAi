import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.notes.NotesPackageBoundary
import org.flowlang.notes.NotesPackageContract
import org.flowlang.notes.NotesPackageContractStatus
import org.flowlang.notes.NotesPackageContractValidator
import org.flowlang.notes.NotesPackageDependency
import org.flowlang.notes.NotesPackageKind
import org.flowlang.notes.StandardNotesPackageContracts

class FlowNotesPackageContractTests {
    @Test
    fun baselineNotesPackageContractsAreValidAndTargetNeutral() {
        val report = NotesPackageContractValidator().validateAll(StandardNotesPackageContracts.baseline())

        assertEquals(NotesPackageContractStatus.PASS, report.status)
        assertEquals(5, report.packages)
        assertTrue(report.valid)
        assertTrue(StandardNotesPackageContracts.baseline().all { it.boundaries.isNotEmpty() })
        assertFalse(StandardNotesPackageContracts.baseline().any { contract ->
            contract.allDeclarationText().any { it.contains("Jenkins", ignoreCase = true) || it.contains("GitHub Actions", ignoreCase = true) || it.contains("Tekton", ignoreCase = true) }
        })
    }

    @Test
    fun notesPackagesCannotClaimRuntimeExecutorSdkPluginLifecycleOrShellProjection() {
        val contract = NotesPackageContract(
            packageId = "flow.domain.bad",
            packageVersion = "0.9.5.3",
            kind = NotesPackageKind.DOMAIN,
            description = "This package pretends to be a runtime executor with shell projection.",
            declaredSemantics = setOf("automation.intent"),
            boundaries = setOf(NotesPackageBoundary("bad", "Bad boundary"))
        )

        val report = NotesPackageContractValidator().validate(contract)

        assertEquals(NotesPackageContractStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "notes.boundary.forbidden-claim" })
    }

    @Test
    fun packageKindControlsWhichDeclarationsItMayOwn() {
        val contract = NotesPackageContract(
            packageId = "flow.capability.bad",
            packageVersion = "0.9.5.3",
            kind = NotesPackageKind.CAPABILITY,
            description = "Capability contract with invalid projection ownership.",
            declaredCapabilities = setOf("resource.read"),
            projectionRules = setOf("render.resource.read"),
            targetCapabilities = setOf("target.resource.read"),
            boundaries = setOf(NotesPackageBoundary("semantic-only", "Capability notes do not own targets or projections."))
        )

        val report = NotesPackageContractValidator().validate(contract)

        assertEquals(NotesPackageContractStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "notes.projection.kind-mismatch" })
        assertTrue(report.issues.any { it.code == "notes.target.kind-mismatch" })
    }

    @Test
    fun dependenciesMustBeVersionedValidAndNotSelfReferential() {
        val contract = NotesPackageContract(
            packageId = "flow.safety.bad",
            packageVersion = "0.9.5.3",
            kind = NotesPackageKind.SAFETY,
            description = "Safety notes with invalid dependency metadata.",
            safetyPolicies = setOf("approval.required"),
            dependencies = setOf(
                NotesPackageDependency("flow.safety.bad", "0.9.x"),
                NotesPackageDependency("Invalid Dependency", "")
            ),
            boundaries = setOf(NotesPackageBoundary("pre-projection", "Safety notes run before projection."))
        )

        val report = NotesPackageContractValidator().validate(contract)

        assertEquals(NotesPackageContractStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "notes.dependency.self" })
        assertTrue(report.issues.any { it.code == "notes.dependency.id.invalid" })
        assertTrue(report.issues.any { it.code == "notes.dependency.version.missing" })
    }

    @Test
    fun everyPackageKindMustDeclareItsOwnMeaningAndBoundary() {
        val contract = NotesPackageContract(
            packageId = "flow.runtime.empty",
            packageVersion = "0.9.5.3",
            kind = NotesPackageKind.RUNTIME,
            description = "Runtime package without runtime declarations."
        )

        val report = NotesPackageContractValidator().validate(contract)

        assertEquals(NotesPackageContractStatus.FAIL, report.status)
        assertTrue(report.issues.any { it.code == "notes.package.declaration.missing" })
        assertTrue(report.issues.any { it.code == "notes.package.boundary.missing" })
    }

    private fun NotesPackageContract.allDeclarationText(): List<String> =
        declaredSemantics.toList() + declaredCapabilities.toList() + safetyPolicies.toList() + runtimeRequirements.toList() + targetCapabilities.toList() + projectionRules.toList() + conformanceChecks.toList()
}
