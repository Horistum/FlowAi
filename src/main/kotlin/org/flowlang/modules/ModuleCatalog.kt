package org.flowlang.modules

/**
 * Already decoded module declarations. Implementations own loading, never semantics.
 * Semantic consumers capture [allModules] through [ModuleCatalogIndex] before use;
 * lookup overrides cannot select a different owner than the declared inventory.
 */
interface ModuleCatalog {
    fun findModule(name: String): FlowModule?
    fun allModules(): Collection<FlowModule>
    fun requireModule(name: String): FlowModule = findModule(name) ?: error("Module not registered: $name")
    fun findAction(moduleName: String, actionName: String): ModuleActionContract? =
        findModule(moduleName)?.actions?.get(actionName)
    fun findSystemType(typeName: String): Pair<FlowModule, SystemTypeContract>? =
        ModuleCatalogIndex.capture(this).findSystemType(typeName)
}
