import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.modules.CanonicalModuleLoader
import org.flowlang.modules.ModuleRegistry

class ModuleSourceParityTests {
    @Test
    fun productionRegistryMatchesCanonicalDescriptorDirectory() {
        val canonical = CanonicalModuleLoader.loadDirectory(File("modules")).associateBy { it.name }
        val registry = ModuleRegistry().allModules().associateBy { it.name }

        assertEquals(canonical, registry)
        assertEquals(canonical, ModuleRegistry.defaultModules().associateBy { it.name })
        assertTrue(registry.values.all { it.version.isNotBlank() && it.description.isNotBlank() })
    }

    @Test
    fun legacyIncludeDefaultsFlagCannotFillDescriptorGaps() {
        val registry = ModuleRegistry.fromDescriptors(
            listOf(validDescriptor(name = "custom")),
            includeDefaults = true
        )

        assertEquals(setOf("custom"), registry.allModules().map { it.name }.toSet())
        assertFalse(registry.allModules().any { it.name == "git" })
    }

    @Test
    fun wrongSchemaShapeFailsInsteadOfBecomingEmpty() {
        val malformed = validDescriptor().replace("input: {}", "input: []", limit = 1)

        assertFailsWith<CanonicalModuleLoader.ContractException> {
            CanonicalModuleLoader.loadText(malformed)
        }
    }

    @Test
    fun stringBooleanFailsInsteadOfBecomingFalse() {
        val malformed = validDescriptor().replace("required: true", "required: \"true\"")

        assertFailsWith<CanonicalModuleLoader.ContractException> {
            CanonicalModuleLoader.loadText(malformed)
        }
    }

    @Test
    fun adapterImplicationsAreRejectedFromCoreModuleDescriptors() {
        val malformed = validDescriptor().replace(
            "    effects: {}",
            "    effects: {}\n    targetImplications:\n      jenkins:\n        support: supported"
        )

        assertFailsWith<CanonicalModuleLoader.ContractException> {
            CanonicalModuleLoader.loadText(malformed)
        }
    }

    @Test
    fun approvalRequirementIsPreservedByCanonicalLoading() {
        val module = CanonicalModuleLoader.loadText(validDescriptor())
        val action = module.actions.getValue("perform")

        assertTrue(action.safety.requiresApproval)
        assertTrue(action.safety.requiresSafety)
        assertTrue(action.safety.destructive)
    }

    @Test
    fun duplicateModuleIdsFailForDirectoryAuthority() {
        val root = Files.createTempDirectory("flow-module-authority").toFile()
        try {
            File(root, "one.yaml").writeText(validDescriptor(name = "duplicate"))
            File(root, "two.yaml").writeText(validDescriptor(name = "duplicate"))

            assertFailsWith<CanonicalModuleLoader.ContractException> {
                ModuleRegistry.fromDirectory(root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validDescriptor(name: String = "custom"): String =
        """
        kind: FlowModule
        name: $name
        version: "1.0"
        description: "Synthetic canonical module"
        systemTypes:
          $name:
            input: {}
        actions:
          perform:
            kind: action
            targetTypes:
              - $name
            input:
              value:
                type: text
                required: true
            output:
              ok:
                type: boolean
            effects: {}
            safety:
              destructive: true
              requires:
                - safety
                - approval
        """.trimIndent()
}
