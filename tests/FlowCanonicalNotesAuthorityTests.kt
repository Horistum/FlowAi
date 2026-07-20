import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.notes.CanonicalNotesPackageLoader
import org.flowlang.notes.NotesPackageKind
import org.flowlang.notes.StandardNotesPackageContracts

class FlowCanonicalNotesAuthorityTests {
    @Test
    fun standardFacadeLoadsExactlyTheCanonicalNotesDirectory() {
        val canonical = CanonicalNotesPackageLoader.load()
        val facade = StandardNotesPackageContracts.baseline()

        assertEquals(canonical, facade)
        assertEquals(
            setOf(
                "flow.domain.core",
                "flow.capability.core",
                "flow.safety.core",
                "flow.runtime.core",
                "flow.conformance.core"
            ),
            canonical.map { it.packageId }.toSet()
        )
        assertTrue(canonical.none { it.kind in setOf(NotesPackageKind.TARGET, NotesPackageKind.PROJECTION) })
        assertTrue(canonical.all { it.targetCapabilities.isEmpty() && it.projectionRules.isEmpty() })
    }

    @Test
    fun targetOwnedNotesCannotEnterTheCoreAuthority() {
        val root = notesRepository(validDomainPackage().replace("kind: domain", "kind: target"))
        try {
            assertFailsWith<CanonicalNotesPackageLoader.ContractException> {
                CanonicalNotesPackageLoader.load(root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun adapterEvidenceFieldsCannotEnterTheCoreAuthority() {
        val root = notesRepository(validDomainPackage() + "\ntargetCapabilities:\n  - adapter.action\n")
        try {
            assertFailsWith<CanonicalNotesPackageLoader.ContractException> {
                CanonicalNotesPackageLoader.load(root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unknownManifestFieldFailsClosed() {
        val root = notesRepository(validDomainPackage())
        File(root, "standard/notes/packages.yaml").appendText("\nimplicitPackages: true\n")
        try {
            assertFailsWith<CanonicalNotesPackageLoader.ContractException> {
                CanonicalNotesPackageLoader.load(root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unknownPackageAndBoundaryFieldsFailClosed() {
        val unknownPackage = notesRepository(validDomainPackage() + "\nimplicitDefaults: true\n")
        try {
            assertFailsWith<CanonicalNotesPackageLoader.ContractException> {
                CanonicalNotesPackageLoader.load(unknownPackage)
            }
        } finally {
            unknownPackage.deleteRecursively()
        }

        val unknownBoundary = notesRepository(
            validDomainPackage().replace(
                "    description: \"Core notes do not own target evidence.\"",
                "    description: \"Core notes do not own target evidence.\"\n    inherited: true"
            )
        )
        try {
            assertFailsWith<CanonicalNotesPackageLoader.ContractException> {
                CanonicalNotesPackageLoader.load(unknownBoundary)
            }
        } finally {
            unknownBoundary.deleteRecursively()
        }
    }

    @Test
    fun unknownDependencyFailsClosed() {
        val root = notesRepository(
            validDomainPackage() +
                "\ndependencies:\n  - packageId: flow.missing.core\n    versionConstraint: \"0.9.x\"\n"
        )
        try {
            assertFailsWith<CanonicalNotesPackageLoader.ContractException> {
                CanonicalNotesPackageLoader.load(root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun dependencyCycleFailsClosed() {
        val domain = validDomainPackage() +
            "\ndependencies:\n  - packageId: flow.capability.core\n    versionConstraint: \"0.9.x\"\n"
        val capability = validCapabilityPackage() +
            "\ndependencies:\n  - packageId: flow.domain.core\n    versionConstraint: \"0.9.x\"\n"
        val root = notesRepository(domain, capability)
        try {
            assertFailsWith<CanonicalNotesPackageLoader.ContractException> {
                CanonicalNotesPackageLoader.load(root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedOrDuplicateDeclarationsDoNotBecomeSetsSilently() {
        val malformed = notesRepository(
            validDomainPackage().replace(
                "declaredSemantics:\n  - automation.intent",
                "declaredSemantics: automation.intent"
            )
        )
        try {
            assertFailsWith<CanonicalNotesPackageLoader.ContractException> {
                CanonicalNotesPackageLoader.load(malformed)
            }
        } finally {
            malformed.deleteRecursively()
        }

        val duplicate = notesRepository(
            validDomainPackage().replace(
                "  - automation.intent",
                "  - automation.intent\n  - automation.intent"
            )
        )
        try {
            assertFailsWith<CanonicalNotesPackageLoader.ContractException> {
                CanonicalNotesPackageLoader.load(duplicate)
            }
        } finally {
            duplicate.deleteRecursively()
        }
    }

    private fun notesRepository(vararg packageYamls: String): File {
        val root = Files.createTempDirectory("flow-notes-authority").toFile()
        val packages = File(root, "standard/notes/packages")
        packages.mkdirs()
        File(root, "standard/notes/packages.yaml").writeText(
            """
            version: 1
            sourceDirectory: standard/notes/packages
            ownership: core-semantic-and-safety-contracts
            adapterEvidence: targets
            """.trimIndent()
        )
        packageYamls.forEachIndexed { index, yaml ->
            File(packages, "package-${index + 1}.yaml").writeText(yaml)
        }
        return root
    }

    private fun validDomainPackage(): String =
        """
        packageId: flow.domain.core
        packageVersion: "0.9.7.2"
        kind: domain
        description: "Synthetic domain package"
        declaredSemantics:
          - automation.intent
        boundaries:
          - name: no-target-truth
            description: "Core notes do not own target evidence."
        """.trimIndent()

    private fun validCapabilityPackage(): String =
        """
        packageId: flow.capability.core
        packageVersion: "0.9.7.2"
        kind: capability
        description: "Synthetic capability package"
        declaredCapabilities:
          - resource.read
        boundaries:
          - name: semantic-only
            description: "Capability meaning does not define adapter implementation."
        """.trimIndent()
}
