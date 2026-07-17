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
        val root = notesRepository(
            packageYaml = validDomainPackage().replace("kind: domain", "kind: target")
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
    fun adapterEvidenceFieldsCannotEnterTheCoreAuthority() {
        val root = notesRepository(
            packageYaml = validDomainPackage() + "\ntargetCapabilities:\n  - jenkins.action\n"
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
    fun unknownDependencyFailsClosed() {
        val root = notesRepository(
            packageYaml = validDomainPackage() +
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
    fun malformedDeclarationListDoesNotBecomeEmpty() {
        val root = notesRepository(
            packageYaml = validDomainPackage().replace(
                "declaredSemantics:\n  - automation.intent",
                "declaredSemantics: automation.intent"
            )
        )
        try {
            assertFailsWith<CanonicalNotesPackageLoader.ContractException> {
                CanonicalNotesPackageLoader.load(root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun notesRepository(packageYaml: String): File {
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
        File(packages, "domain.yaml").writeText(packageYaml)
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
}
