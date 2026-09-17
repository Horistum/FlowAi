package org.flowlang.modules

import java.util.Collections

/** A malformed catalog is configuration failure, never a reason to pick a fallback owner. */
class ModuleCatalogException(val code: String, message: String) : IllegalArgumentException("$code: $message")

/**
 * Validated identity snapshot of one composed module inventory.
 *
 * System type names are globally unique and case-sensitive in this composition.
 * Neither registry order nor descriptor filenames select an owner. The snapshot
 * copies and protects identity-bearing maps; it is not a deep freeze of arbitrary
 * schema default values or expression payloads.
 */
class ModuleCatalogIndex private constructor(
    private val modules: Map<String, FlowModule>,
    private val systems: Map<String, Pair<FlowModule, SystemTypeContract>>
) : ModuleCatalog {
    override fun findModule(name: String): FlowModule? = modules[name]
    override fun allModules(): Collection<FlowModule> = modules.values
    override fun findSystemType(typeName: String): Pair<FlowModule, SystemTypeContract>? = systems[typeName]

    companion object {
        /** Capture the declarations once; provider lookup overrides cannot redefine their identities. */
        fun capture(catalog: ModuleCatalog): ModuleCatalogIndex =
            if (catalog is ModuleCatalogIndex) catalog else fromModules(catalog.allModules())

        fun fromMap(modules: Map<String, FlowModule>): ModuleCatalogIndex {
            val entries = modules.entries.map { it.key to it.value }
            val mismatches = entries.filter { (key, module) -> key != module.name }
                .sortedBy { it.first }
            if (mismatches.isNotEmpty()) {
                throw ModuleCatalogException("MODULE_CATALOG_KEY_MISMATCH", mismatches.joinToString("; ") { (key, module) ->
                    "Module key '$key' does not match declared name '${module.name}'."
                })
            }
            return fromModules(entries.map { it.second })
        }

        fun fromModules(modules: Iterable<FlowModule>): ModuleCatalogIndex {
            val declarations = modules.map { module ->
                module.copy(
                    systemTypes = immutableMap(module.systemTypes.mapValues { (_, type) ->
                        type.copy(input = immutableMap(type.input))
                    }),
                    actions = immutableMap(module.actions)
                )
            }
            val duplicateModules = declarations.groupBy { it.name }.filterValues { it.size > 1 }.keys.sorted()
            if (duplicateModules.isNotEmpty()) {
                throw ModuleCatalogException("DUPLICATE_MODULE_ID", "Duplicate modules: ${duplicateModules.joinToString()}")
            }
            declarations.sortedBy { it.name }.forEach { module ->
                if (module.name.isBlank() || module.version.isBlank()) {
                    throw ModuleCatalogException("MODULE_CATALOG_INVALID_IDENTITY", "Module name and version must not be blank.")
                }
                module.systemTypes.toSortedMap().forEach { (key, type) ->
                    if (key.isBlank() || key != type.name) {
                        throw ModuleCatalogException("SYSTEM_TYPE_KEY_MISMATCH",
                            "Module '${module.name}@${module.version}' system key '$key' does not match a non-blank declared name '${type.name}'.")
                    }
                }
            }
            val owners = declarations.flatMap { module -> module.systemTypes.keys.map { it to module } }
                .groupBy({ it.first }, { it.second })
            val conflicts = owners.filterValues { it.size > 1 }.toSortedMap()
            if (conflicts.isNotEmpty()) {
                throw ModuleCatalogException("DUPLICATE_SYSTEM_TYPE", conflicts.entries.joinToString("; ") { (type, modules) ->
                    "System type '$type' has multiple owners: " +
                        modules.map { "${it.name}@${it.version}" }.sorted().joinToString() + "."
                })
            }
            return ModuleCatalogIndex(
                immutableMap(declarations.associateBy { it.name }),
                immutableMap(declarations.flatMap { module ->
                    module.systemTypes.map { (name, type) -> name to (module to type) }
                }.toMap())
            )
        }

        private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
            Collections.unmodifiableMap(LinkedHashMap(values))
    }
}
