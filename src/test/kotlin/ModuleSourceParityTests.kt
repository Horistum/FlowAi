import kotlin.test.Test
import kotlin.test.assertTrue
import org.flowlang.modules.ModuleRegistry
import org.flowlang.modules.ModuleYamlLoader
import java.io.File

/**
 * Regression guard for module contract metadata (review finding #2).
 *
 * Built-in modules remain the canonical runtime source. The descriptor files under
 * modules/ are documentation/configuration descriptors and must at least carry
 * matching module ids and non-blank metadata so drift is visible in tests.
 */
class ModuleSourceParityTests {
    @Test
    fun everyDefaultModuleHasDescriptionAndVersion() {
        ModuleRegistry.defaultModules().forEach { module ->
            assertTrue(module.description.isNotBlank(), "Module '${module.name}' must declare a non-blank description.")
            assertTrue(module.version.isNotBlank(), "Module '${module.name}' must declare a non-blank version.")
        }
    }

    @Test
    fun yamlDescriptorsCoverDefaultsWithNonBlankMetadata() {
        val defaults = ModuleRegistry.defaultModules().associateBy { it.name }
        val yaml = ModuleYamlLoader.loadDirectory(File("modules")).associateBy { it.name }
        val missing = defaults.keys - yaml.keys

        assertTrue(missing.isEmpty(), "Missing module descriptors: ${missing.joinToString()}")
        defaults.keys.forEach { name ->
            val descriptor = yaml.getValue(name)
            assertTrue(descriptor.version.isNotBlank(), "Descriptor for module '$name' must declare a version.")
            assertTrue(descriptor.description.isNotBlank(), "Descriptor for module '$name' must declare a description.")
        }
    }
}
