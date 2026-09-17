package org.flowlang.contracts

import kotlin.test.*
import org.flowlang.modules.*

class ModuleCatalogIdentityTests {
    private fun module(name: String, type: String = name, version: String = "1.0") = FlowModule(
        name = name, version = version,
        systemTypes = mapOf(type to SystemTypeContract(type)),
        actions = mapOf("inspect" to ModuleActionContract("inspect", targetTypes = setOf(type)))
    )

    private fun inventory(values: Collection<FlowModule>): ModuleCatalog = object : ModuleCatalog {
        override fun allModules(): Collection<FlowModule> = values
        override fun findModule(name: String): FlowModule? = values.firstOrNull { it.name == name }
    }

    @Test fun duplicateTypeOwnersAreRejectedInEveryRegistryOrder() {
        val a = module("alpha", "shared")
        val b = module("beta", "shared", "2.0")
        val errors = listOf(listOf(a, b), listOf(b, a)).map { values ->
            assertFailsWith<ModuleCatalogException> { ModuleCatalogIndex.fromModules(values) }
        }
        assertTrue(errors.all { it.code == "DUPLICATE_SYSTEM_TYPE" })
        assertEquals(errors[0].message, errors[1].message)
        assertTrue(errors[0].message.orEmpty().contains("alpha@1.0, beta@2.0"))
    }

    @Test fun evenEquivalentDuplicateContractsCannotBorrowOneOwnersIdentity() {
        val a = module("alpha", "shared")
        val b = a.copy(name = "beta")
        assertEquals(a.systemTypes, b.systemTypes)
        assertEquals("DUPLICATE_SYSTEM_TYPE", assertFailsWith<ModuleCatalogException> {
            ModuleCatalogIndex.fromModules(listOf(a, b))
        }.code)
    }

    @Test fun differentVersionsCannotSilentlySelectOneModule() {
        assertEquals("DUPLICATE_MODULE_ID", assertFailsWith<ModuleCatalogException> {
            ModuleCatalogIndex.fromModules(listOf(module("alpha"), module("alpha", version = "2.0")))
        }.code)
    }

    @Test fun moduleMapAliasesAndSystemKeyAliasesAreRejected() {
        assertEquals("MODULE_CATALOG_KEY_MISMATCH", assertFailsWith<ModuleCatalogException> {
            ModuleCatalogIndex.fromMap(mapOf("alias" to module("alpha")))
        }.code)
        val inconsistent = module("alpha").copy(systemTypes = mapOf("alias" to SystemTypeContract("actual")))
        assertEquals("SYSTEM_TYPE_KEY_MISMATCH", assertFailsWith<ModuleCatalogException> {
            ModuleCatalogIndex.fromModules(listOf(inconsistent))
        }.code)
    }

    @Test fun blankIdentityCannotBecomeAnIndexEntry() {
        listOf(module(""), module("alpha", version = "")).forEach { value ->
            assertEquals("MODULE_CATALOG_INVALID_IDENTITY", assertFailsWith<ModuleCatalogException> {
                ModuleCatalogIndex.fromModules(listOf(value))
            }.code)
        }
        assertEquals("SYSTEM_TYPE_KEY_MISMATCH", assertFailsWith<ModuleCatalogException> {
            ModuleCatalogIndex.fromModules(listOf(module("alpha", "")))
        }.code)
    }

    @Test fun defaultCatalogLookupCannotReturnTheFirstAmbiguousDeclaration() {
        val values = listOf(module("alpha", "shared"), module("beta", "shared"))
        listOf(values, values.reversed()).forEach { order ->
            assertEquals("DUPLICATE_SYSTEM_TYPE", assertFailsWith<ModuleCatalogException> {
                inventory(order).findSystemType("shared")
            }.code)
        }
    }

    @Test fun dormantConflictsFailBeforeAnyLookup() {
        assertEquals("DUPLICATE_SYSTEM_TYPE", assertFailsWith<ModuleCatalogException> {
            ModuleCatalogIndex.capture(inventory(listOf(module("alpha", "unused"), module("beta", "unused"))))
        }.code)
    }

    @Test fun uniqueLookupIsOrderIndependentAndPreservesExactIdentity() {
        val values = listOf(module("alpha", "service"), module("beta", "serviceV2"), module("gamma", "service-v2"))
        val orders = values.indices.flatMap { first -> values.indices.filter { it != first }.map { second ->
            listOf(values[first], values[second], values[3 - first - second])
        } }
        orders.forEach { order ->
            val catalog = ModuleCatalogIndex.fromModules(order)
            values.forEach { expected ->
                val type = expected.systemTypes.keys.single()
                assertEquals(expected to expected.systemTypes.getValue(type), catalog.findSystemType(type))
                assertEquals(expected.actions["inspect"], catalog.findAction(expected.name, "inspect"))
            }
            assertNull(catalog.findSystemType("SERVICE"))
            assertNull(catalog.findSystemType("missing"))
            assertNull(catalog.findModule("missing"))
        }
    }

    @Test fun capturesDeclarationsOnceWithoutTrustingProviderLookupOverrides() {
        val declared = module("alpha", "service")
        var reads = 0
        val catalog = object : ModuleCatalog {
            override fun allModules(): Collection<FlowModule> { reads++; return listOf(declared) }
            override fun findModule(name: String): FlowModule? = error("Lookup must use the declaration snapshot")
            override fun findSystemType(typeName: String): Pair<FlowModule, SystemTypeContract>? =
                error("Provider selection is not authoritative")
        }
        val snapshot = ModuleCatalogIndex.capture(catalog)
        repeat(3) { assertEquals(declared, snapshot.findModule("alpha")) }
        assertEquals(declared, snapshot.findSystemType("service")?.first)
        assertSame(snapshot, ModuleCatalogIndex.capture(snapshot))
        assertEquals(1, reads)
    }

    @Test fun callerMutationsCannotChangeIdentityOrSystemSchemaOwnership() {
        val input = linkedMapOf("url" to SchemaField(SchemaType.TEXT))
        val types = linkedMapOf("service" to SystemTypeContract("service", input))
        val actions = linkedMapOf("inspect" to ModuleActionContract("inspect"))
        val modules = linkedMapOf("alpha" to FlowModule("alpha", "1.0", systemTypes = types, actions = actions))
        val catalog = ModuleCatalogIndex.fromMap(modules)
        val expected = catalog.requireModule("alpha")
        input.clear(); types.clear(); actions.clear(); modules.clear()
        assertEquals(expected, catalog.requireModule("alpha"))
        assertEquals(SchemaType.TEXT, requireNotNull(catalog.findSystemType("service")).second.input.getValue("url").type)
        assertNotNull(catalog.findAction("alpha", "inspect"))
        assertFailsWith<UnsupportedOperationException> { (catalog.allModules() as MutableCollection<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (expected.systemTypes as MutableMap<*, *>).clear() }
        assertFailsWith<UnsupportedOperationException> { (expected.actions as MutableMap<*, *>).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (expected.systemTypes.getValue("service").input as MutableMap<*, *>).clear()
        }
    }

    @Test fun emptyInventoryDoesNotInventBuiltInContracts() {
        val catalog = ModuleCatalogIndex.fromModules(emptyList())
        assertTrue(catalog.allModules().isEmpty())
        assertNull(catalog.findSystemType("git"))
        assertNull(catalog.findAction("git", "checkout"))
    }
}
