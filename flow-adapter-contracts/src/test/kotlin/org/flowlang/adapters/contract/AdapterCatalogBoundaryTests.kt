package org.flowlang.adapters.contract

import kotlin.test.*
import org.flowlang.testing.ExternalCompilerProbe

class AdapterCatalogBoundaryTests {
    @Test fun explicitEntriesAndMissingAdaptersHaveNoFallback() {
        val catalog = StaticAdapterCatalog.of(listOf("custom" to "implementation"))
        assertEquals(setOf("custom"), catalog.targetIds)
        assertEquals("implementation", catalog.requireAdapter("custom"))
        assertNull(catalog.adapterFor("jenkins"))
        assertFailsWith<IllegalStateException> { catalog.requireAdapter("jenkins") }
        assertTrue(StaticAdapterCatalog.empty<String>().targetIds.isEmpty())
    }

    @Test fun blankAndDuplicateKeysAreRejected() {
        assertFailsWith<IllegalArgumentException> { StaticAdapterCatalog.of(listOf(" " to 1)) }
        assertFailsWith<IllegalArgumentException> { StaticAdapterCatalog.of(listOf("a" to 1, "a" to 2)) }
    }

    @Test fun inputAndPublishedKeyMutationsCannotAlterTheCatalog() {
        val input = mutableListOf("a" to 1, "b" to 2)
        val catalog = StaticAdapterCatalog.of(input)
        input.clear()
        @Suppress("UNCHECKED_CAST")
        val keys = catalog.targetIds as MutableSet<String>
        assertFailsWith<UnsupportedOperationException> { keys.remove("a") }
        assertEquals(setOf("a", "b"), catalog.targetIds)
        assertEquals(1, catalog.requireAdapter("a"))
    }

    @Test fun anIndependentConsumerCompilesAgainstOnlyTheCatalogContract() {
        ExternalCompilerProbe.accepts("""
            import org.flowlang.adapters.contract.*
            fun catalog(): AdapterCatalog<Any> = StaticAdapterCatalog.of(listOf("custom" to "adapter"))
        """.trimIndent())
    }

    @Test fun contractsCannotImportCompilerAdapterEvidenceOrDistributionImplementations() {
        listOf(
            "org.flowlang.compiler.FlowCompilationService",
            "org.flowlang.generators.manifest.TargetProjectionProvider",
            "org.flowlang.adapters.continuity.AdapterContinuitySatisfactionAuthority",
            "org.flowlang.targets.builtin.JenkinsManifestRenderer",
            "org.flowlang.distribution.reference.ReferenceTargetProjections"
        ).forEach { type -> ExternalCompilerProbe.rejects("fun forbidden(value: $type) = value", "Unresolved reference") }
    }
}
