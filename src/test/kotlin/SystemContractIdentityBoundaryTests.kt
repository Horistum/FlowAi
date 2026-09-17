import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.ast.*
import org.flowlang.compiler.*
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.intent.*
import org.flowlang.modules.*
import org.flowlang.planner.FlowPlanner
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.MandatoryMaterializationAuthority
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.controls.PlanningControlAuthority
import org.flowlang.ai.normalization.IntentProposalReview

class SystemContractIdentityBoundaryTests {
    private fun descriptor(name: String, type: String = "shared") = """
        kind: FlowModule
        name: $name
        version: "1.0"
        description: "Independent contract"
        systemTypes:
          $type:
            input:
              url:
                type: text
                default: "$name"
        actions:
          inspect:
            kind: action
            targetTypes: [$type]
            input: {}
            output: {}
            effects: {}
            safety:
              destructive: false
              requires: []
    """.trimIndent()

    @Test fun descriptorSetsRejectAmbiguousOwnershipInBothOrders() {
        val a = descriptor("alpha")
        val b = descriptor("beta")
        listOf(listOf(a, b), listOf(b, a)).forEach { values ->
            val canonical = assertFailsWith<CanonicalModuleLoader.ContractException> {
                CanonicalModuleLoader.loadTexts(values)
            }
            assertEquals("DUPLICATE_SYSTEM_TYPE", assertIs<ModuleCatalogException>(canonical.cause).code)
            assertTrue(canonical.message.orEmpty().contains("alpha@1.0, beta@1.0"))
            assertFailsWith<CanonicalModuleLoader.ContractException> { ModuleRegistry.fromDescriptors(values) }
        }
    }

    @Test fun filenamesAndCompatibilityLoadersCannotChooseTheWinningSchema() {
        val directory = createTempDirectory("system-contract-conflict").toFile()
        try {
            repeat(2) { order ->
                File(directory, "a.yaml").writeText(descriptor(if (order == 0) "alpha" else "beta"))
                File(directory, "z.yaml").writeText(descriptor(if (order == 0) "beta" else "alpha"))
                assertFailsWith<CanonicalModuleLoader.ContractException> { CanonicalModuleLoader.loadDirectory(directory) }
                val wrapped = assertFailsWith<ModuleYamlLoader.LoadException> { ModuleYamlLoader.loadDirectory(directory) }
                assertTrue(wrapped.message.orEmpty().contains("DUPLICATE_SYSTEM_TYPE"))
                assertFailsWith<CanonicalModuleLoader.ContractException> { ModuleRegistry.fromDirectory(directory) }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun separatelyDecodedModulesStillNeedUniqueComposition() {
        val a = ModuleYamlLoader.loadText(descriptor("alpha"))
        val b = ModuleYamlLoader.loadText(descriptor("beta"))
        listOf(linkedMapOf("alpha" to a, "beta" to b), linkedMapOf("beta" to b, "alpha" to a)).forEach { modules ->
            assertEquals("DUPLICATE_SYSTEM_TYPE", assertFailsWith<ModuleCatalogException> {
                ModuleRegistry(modules)
            }.code)
        }
    }

    @Test fun everyPublicSemanticBoundaryRejectsEvenAnUnusedConflictFromACustomCatalog() {
        val a = ModuleYamlLoader.loadText(descriptor("alpha"))
        val b = ModuleYamlLoader.loadText(descriptor("beta"))
        val catalog = object : ModuleCatalog {
            override fun allModules(): Collection<FlowModule> = listOf(a, b)
            override fun findModule(name: String): FlowModule? = a.takeIf { it.name == name }
            override fun findSystemType(typeName: String): Pair<FlowModule, SystemTypeContract>? =
                a to a.systemTypes.getValue("shared")
        }
        val boundaries = listOf<() -> Any>(
            { FrontendCompilerComposition.compiler(catalog) },
            { FrontendCompilerComposition.flowValidator(catalog) },
            { FrontendCompilerComposition.safetyValidator(catalog) },
            { IntentCapabilityValidator(catalog) },
            { FrontendCompilerComposition.intentPlanner(catalog) },
            { CanonicalIntentMeaningAuthority(catalog) },
            { IntentDesignAnalyzer(catalog) },
            { IntentDecisionAnalyzer(catalog) },
            { FlowPlanner(catalog) },
            { IntentProposalReview(catalog) },
            { ModuleContractAnalyzer.analyze(catalog) },
            { PlanningControlAuthority.assess(emptyList(), emptyList(), emptyList(), catalog) },
            { MandatoryMaterializationAuthority(mapOf("custom" to TargetCapability("custom", "Explicit custom target")), catalog) },
            { TargetManifestGenerationPipeline(
                mapOf("custom" to TargetCapability("custom", "Explicit custom target")),
                TargetProjectionRegistry.empty(), modules = catalog) }
        )
        boundaries.forEachIndexed { index, create ->
            assertEquals("DUPLICATE_SYSTEM_TYPE", assertFailsWith<ModuleCatalogException>("boundary $index") { create() }.code)
        }
    }

    @Test fun compilerUsesOneInventorySnapshotForBothValidationAndPlanning() {
        val type = SystemTypeContract("service", mapOf("count" to SchemaField(SchemaType.NUMBER, required = true)))
        val values = mutableListOf(FlowModule("alpha", "1.0", systemTypes = mapOf("service" to type)))
        var reads = 0
        val catalog = object : ModuleCatalog {
            override fun allModules(): Collection<FlowModule> { reads++; return values }
            override fun findModule(name: String): FlowModule? = error("Must use the captured declaration index")
            override fun findSystemType(typeName: String): Pair<FlowModule, SystemTypeContract>? =
                error("Must not trust an overridden lookup")
        }
        val compiler = FrontendCompilerComposition.compiler(catalog)
        values.clear()
        val ast = FlowDocument(flow = FlowNode(name = "sample", systems = listOf(
            SystemNode(name = "target", systemType = "service", config = mapOf("count" to StringLiteralNode(value = "12")))
        )))
        val source = CompilationSource.fromBytes(CompilationFrontend.FLOW_SOURCE, "sample.flow", "fixture".toByteArray())
        val rejection = assertIs<CompilationResult.Rejected>(compiler.compile(FlowSourceCompilationInput(source, ast)))
        assertTrue(rejection.rejection.diagnostics.any { it.code == "TYPE_MISMATCH" })
        assertEquals(1, reads)
    }

    @Test fun builtinInventoryAndIndependentTypesRemainAcceptedWithoutRenaming() {
        val builtins = ModuleRegistry().allModules().toList()
        val reverse = ModuleRegistry(builtins.reversed().associateBy { it.name })
        builtins.forEach { module -> module.systemTypes.forEach { (name, type) ->
            assertEquals(module to type, reverse.findSystemType(name))
        } }
        val custom = ModuleRegistry.fromDescriptors(listOf(descriptor("alpha", "first"), descriptor("beta", "second")))
        assertEquals("alpha", custom.findSystemType("first")?.first?.name)
        assertEquals("beta", custom.findSystemType("second")?.first?.name)
    }

    @Test fun intentImportsTheActualSystemOwnerAndVersionRatherThanTheTypeName() {
        val module = ModuleYamlLoader.loadText(descriptor("alpha", "remote").replace("version: \"1.0\"", "version: \"2.3\""))
        val intent = IntentDocument(name = "external-contract", systems = listOf(
            IntentSystem(name = "target", type = "remote", purpose = "Explicit external contract")
        ))
        val standard = ModuleRegistry().requireModule("standard")
        val catalogs = listOf(listOf(module, standard), listOf(standard, module))
        val documents = catalogs.map { values ->
            val registry = ModuleRegistry(values.associateBy { it.name })
            val document = FrontendCompilerComposition.intentPlanner(registry).plan(intent)
            assertEquals(listOf(ModuleImportNode(name = "alpha", version = "2.3")), document.imports)
            val validation = FrontendCompilerComposition.flowValidator(registry).validate(document)
            assertTrue(validation.valid, validation.issues.joinToString { it.code + ": " + it.message })
            document
        }
        assertEquals(documents[0], documents[1])
    }

    @Test fun anUnknownSystemTypeDoesNotInventAnImportOrChooseAnotherModule() {
        val registry = ModuleRegistry()
        val intent = IntentDocument(name = "unknown-type", systems = listOf(
            IntentSystem(name = "target", type = "unregistered", purpose = "Unresolved external contract")
        ))
        val intentReport = IntentCapabilityValidator(registry).validate(intent)
        assertTrue(intentReport.issues.any { it.code == "UNKNOWN_SYSTEM_TYPE" })
        val document = FrontendCompilerComposition.intentPlanner(registry).plan(intent)
        assertTrue(document.imports.none { it.name == "unregistered" })
        assertEquals("unregistered", document.flow.systems.single().systemType)
        val report = FrontendCompilerComposition.flowValidator(registry).validate(document)
        assertTrue(report.issues.any { it.code == "SYSTEM_TYPE_UNKNOWN" })
    }

}
