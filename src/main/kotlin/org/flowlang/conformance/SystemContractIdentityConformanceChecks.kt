package org.flowlang.conformance

import org.flowlang.ast.ModuleImportNode
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentSystem
import org.flowlang.modules.*

/** Behavioral probes for decoded system ownership, independent of filenames and registration order. */
internal class SystemContractIdentityConformanceChecks {
    fun checks(): List<ConformanceCheck> = listOf(
        check(AMBIGUITY) {
            val a = module("alpha", "shared")
            val b = module("beta", "shared")
            listOf(listOf(a, b), listOf(b, a)).all { declarations ->
                val provider = object : ModuleCatalog {
                    override fun allModules(): Collection<FlowModule> = declarations
                    override fun findModule(name: String): FlowModule? = a
                    override fun findSystemType(typeName: String): Pair<FlowModule, SystemTypeContract>? =
                        a to a.systemTypes.getValue("shared")
                }
                rejects { ModuleCatalogIndex.fromModules(declarations) } &&
                    rejects { ModuleRegistry(declarations.associateBy { it.name }) } &&
                    rejects { FrontendCompilerComposition.compiler(provider) }
            }
        },
        check(PRESERVATION) {
            val a = module("alpha", "remote", "2.3")
            val b = module("beta", "other", "4.2")
            val intent = IntentDocument(name = "external-contract", systems = listOf(
                IntentSystem("target", "remote", "Explicit external contract")
            ))
            val docs = listOf(listOf(a, b), listOf(b, a)).map { declarations ->
                val catalog = ModuleCatalogIndex.fromModules(declarations)
                require(catalog.findSystemType("remote")?.first?.name == "alpha")
                require(catalog.findSystemType("other")?.first?.name == "beta")
                require(catalog.findSystemType("missing") == null)
                FrontendCompilerComposition.intentPlanner(catalog).plan(intent)
            }
            docs[0] == docs[1] && docs[0].imports == listOf(ModuleImportNode(name = "alpha", version = "2.3"))
        },
        check(SNAPSHOT) {
            val types = linkedMapOf("remote" to SystemTypeContract("remote"))
            val modules = mutableListOf(FlowModule("alpha", "2.3", systemTypes = types))
            val catalog = ModuleCatalogIndex.fromModules(modules)
            types.clear()
            modules.clear()
            val first = catalog.findSystemType("remote")
            first?.first?.name == "alpha" && first.second.name == "remote" &&
                catalog.allModules().single().systemTypes.keys == setOf("remote") &&
                runCatching { (first.first.systemTypes as MutableMap<*, *>).clear() }.isFailure &&
                runCatching { (catalog.allModules() as MutableCollection<*>).clear() }.isFailure &&
                catalog.findSystemType("remote") == first
        }
    )

    private fun module(name: String, type: String, version: String = "1.0") =
        FlowModule(name, version, systemTypes = mapOf(type to SystemTypeContract(type)))

    private fun rejects(block: () -> Any): Boolean =
        (runCatching(block).exceptionOrNull() as? ModuleCatalogException)?.code == "DUPLICATE_SYSTEM_TYPE"

    private fun check(name: String, probe: () -> Boolean): ConformanceCheck =
        runCatching(probe).fold(
            { passed -> ConformanceCheck(name, passed, if (passed) null else "System-contract identity probe did not preserve its invariant.") },
            { failure -> ConformanceCheck(name, false, failure.message ?: failure.javaClass.simpleName) }
        )

    companion object {
        const val AMBIGUITY = "architecture-recovery.language.system-contract-ambiguity-rejection"
        const val PRESERVATION = "architecture-recovery.language.system-contract-owner-preservation"
        const val SNAPSHOT = "architecture-recovery.language.system-contract-snapshot-stability"
    }
}
