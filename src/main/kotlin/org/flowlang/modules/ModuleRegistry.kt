package org.flowlang.modules

import java.io.File

class ModuleRegistry(
    private val modules: Map<String, FlowModule> = loadCanonical().associateBy { it.name }
) {
    fun findModule(name: String): FlowModule? = modules[name]
    fun allModules(): Collection<FlowModule> = modules.values
    fun requireModule(name: String): FlowModule = modules[name] ?: error("Module not registered: $name")
    fun findAction(moduleName: String, actionName: String): ModuleActionContract? = modules[moduleName]?.actions?.get(actionName)
    fun findSystemType(typeName: String): Pair<FlowModule, SystemTypeContract>? =
        modules.values.firstNotNullOfOrNull { module -> module.systemTypes[typeName]?.let { module to it } }

    companion object {
        fun fromDirectory(dir: File, includeDefaults: Boolean = false): ModuleRegistry {
            require(!includeDefaults) { "A complete descriptor directory is required." }
            return ModuleRegistry(unique(ModuleYamlLoader.loadDirectory(dir)))
        }

        fun fromDescriptors(yamlTexts: List<String>, includeDefaults: Boolean = false): ModuleRegistry {
            require(!includeDefaults) { "A complete descriptor set is required." }
            val loaded = yamlTexts.mapIndexed { index, text ->
                ModuleYamlLoader.loadText(text, "<module-${index + 1}>")
            }
            return ModuleRegistry(unique(loaded))
        }

        fun loadCanonical(rootDir: File = File(".")): List<FlowModule> =
            ModuleYamlLoader.loadDirectory(File(rootDir, "modules"))

        @Deprecated("Use loadCanonical().")
        fun defaultModules(): List<FlowModule> = loadCanonical()

        private fun unique(loaded: List<FlowModule>): Map<String, FlowModule> {
            val duplicates = loaded.groupBy { it.name }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "Duplicate module descriptors: ${duplicates.sorted().joinToString()}" }
            return loaded.associateBy { it.name }
        }
    }
}
