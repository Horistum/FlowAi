package org.flowlang.modules

/** Read-only, already decoded module contracts. Implementations own loading, never semantics. */
interface ModuleCatalog {
    fun findModule(name: String): FlowModule?
    fun allModules(): Collection<FlowModule>
    fun requireModule(name: String): FlowModule = findModule(name) ?: error("Module not registered: $name")
    fun findAction(moduleName: String, actionName: String): ModuleActionContract? =
        findModule(moduleName)?.actions?.get(actionName)
    fun findSystemType(typeName: String): Pair<FlowModule, SystemTypeContract>? =
        allModules().firstNotNullOfOrNull { module -> module.systemTypes[typeName]?.let { module to it } }
}
