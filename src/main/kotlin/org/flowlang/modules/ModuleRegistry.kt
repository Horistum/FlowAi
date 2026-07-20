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
        fun fromDirectory(dir: File): ModuleRegistry =
            ModuleRegistry(CanonicalModuleLoader.loadDirectory(dir).associateBy { it.name })

        @Deprecated(
            "Descriptor directories are complete authorities. Use fromDirectory(dir).",
            ReplaceWith("fromDirectory(dir)")
        )
        @Suppress("UNUSED_PARAMETER")
        fun fromDirectory(dir: File, includeDefaults: Boolean): ModuleRegistry = fromDirectory(dir)

        fun fromDescriptors(yamlTexts: List<String>): ModuleRegistry =
            ModuleRegistry(CanonicalModuleLoader.loadTexts(yamlTexts).associateBy { it.name })

        @Deprecated(
            "Descriptor sets are complete authorities. Use fromDescriptors(yamlTexts).",
            ReplaceWith("fromDescriptors(yamlTexts)")
        )
        @Suppress("UNUSED_PARAMETER")
        fun fromDescriptors(yamlTexts: List<String>, includeDefaults: Boolean): ModuleRegistry =
            fromDescriptors(yamlTexts)

        fun loadCanonical(rootDir: File = File(".")): List<FlowModule> =
            CanonicalModuleLoader.loadDirectory(File(rootDir, "modules"))

        @Deprecated("Use loadCanonical().", ReplaceWith("loadCanonical()"))
        fun defaultModules(): List<FlowModule> = loadCanonical()
    }
}
