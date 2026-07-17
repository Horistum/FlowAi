package org.flowlang.modules

class ModuleRegistry(
    private val modules: Map<String, FlowModule> = emptyMap()
) {
    fun findModule(name: String): FlowModule? = modules[name]
    fun allModules(): Collection<FlowModule> = modules.values
    fun requireModule(name: String): FlowModule = modules[name] ?: error("Module not registered: $name")
    fun findAction(moduleName: String, actionName: String): ModuleActionContract? = modules[moduleName]?.actions?.get(actionName)
    fun findSystemType(typeName: String): Pair<FlowModule, SystemTypeContract>? =
        modules.values.firstNotNullOfOrNull { module -> module.systemTypes[typeName]?.let { module to it } }
}
