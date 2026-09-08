package org.flowlang.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.flowlang.modules.FlowModule
import org.flowlang.modules.ModuleCatalog
import org.flowlang.testing.ExternalCompilerProbe

class ModuleContractBoundaryTests {
    @Test fun anIndependentConsumerCanSupplyDecodedContractsWithoutLoaders() {
        ExternalCompilerProbe.accepts("""
            package independent.contracts
            import org.flowlang.ast.FlowDocument
            import org.flowlang.ast.FlowNode
            import org.flowlang.modules.FlowModule
            import org.flowlang.modules.ModuleCatalog
            class Inventory : ModuleCatalog {
                override fun findModule(name: String): FlowModule? = null
                override fun allModules(): Collection<FlowModule> = emptyList()
            }
            fun document() = FlowDocument(flow = FlowNode(name = "neutral"))
        """.trimIndent())
    }

    @Test fun samePackageCannotReachCompilerOrRegistryImplementations() {
        ExternalCompilerProbe.rejects(
            "package org.flowlang.modules\nval forbidden = ModuleRegistry()", "ModuleRegistry")
        ExternalCompilerProbe.rejects(
            "package org.flowlang.compiler\nval forbidden: FlowCompilationService? = null", "FlowCompilationService")
    }

    @Test fun parserAndSerializationImportsAreAbsent() {
        ExternalCompilerProbe.rejects(
            "import org.flowlang.parser.FlowParser as Parser\nval forbidden = Parser()", "parser")
        ExternalCompilerProbe.rejects(
            "val forbidden = com.fasterxml.jackson.databind.ObjectMapper()", "fasterxml")
    }

    @Test fun externalCodeCannotExtendSealedAstContracts() {
        ExternalCompilerProbe.rejects(
            "package org.flowlang.ast\ninterface ForeignExpression : ExpressionNode", "sealed")
    }

    @Test fun emptyCatalogDefaultsNeverLoadWorkingDirectoryFiles() {
        val catalog = object : ModuleCatalog {
            override fun findModule(name: String): FlowModule? = null
            override fun allModules(): Collection<FlowModule> = emptyList()
        }
        assertNull(catalog.findAction("missing", "action"))
        assertNull(catalog.findSystemType("missing"))
        assertEquals("Module not registered: missing", assertFailsWith<IllegalStateException> {
            catalog.requireModule("missing")
        }.message)
    }
}
