package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import org.flowlang.ai.normalization.*
import org.flowlang.ast.ModuleImportNode
import org.flowlang.compiler.*
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.ai.ReviewedAiProposal
import org.flowlang.frontend.ai.ReviewedAiProposalFrontend
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.modules.*
import org.flowlang.parser.DuplicateDeclarationException
import org.flowlang.serialization.FlowYaml
import org.flowlang.cli.Json

/** Composed public boundaries: every rejection has a successfully compiled counterpart. */
internal class LanguageIntegrityIntegrationChecks {
    fun checks(): List<ConformanceCheck> = listOf(
        check(PARITY, ::preservesComposedMeaning),
        check(DECLARATIONS, ::rejectsDuplicateSourceDeclarations),
        check(SCHEMAS, ::rejectsInvalidSchemaContracts),
        check(VALUES, ::rejectsInvalidAuthoredValues),
        check(OWNERS, ::rejectsAmbiguousOwners),
        check(IDENTITIES, ::rejectsCollidingSemanticIdentities),
        check(LOADERS, ::rejectsMalformedPublicDocuments)
    )

    private fun registry(modules: List<FlowModule>) =
        ModuleRegistry((ModuleRegistry().allModules() + modules).associateBy { it.name })

    private fun compiler() = FrontendCompilerComposition.compiler(
        registry(listOf(CanonicalModuleLoader.loadText(descriptor, "typed.yaml"))))

    private fun accepted(result: CompilationResult): CompilationUnit = result.requireAccepted()

    private fun proposal(compiler: FlowCompilationService, intent: IntentDocument): CompilationResult =
        ReviewedAiProposalFrontend(compiler).compile(ReviewedAiProposal(
            providerId = "language-integrity-probe",
            request = AiIntentRequest(userText = "Inspect and record the typed system", mode = NormalizationMode.DRAFT),
            response = AiIntentResponse(intent, NormalizationReport(mode = NormalizationMode.DRAFT,
                classification = IntentClassification("custom", 1.0),
                confidence = ConfidenceScore(1.0, 1.0, 1.0, 1.0, 1.0))),
            sourceIdentity = "integrity:proposal", sourceName = "integrity-proposal.json"))

    private fun json(yaml: String): String = Json.mapper.writeValueAsString(FlowYaml.readMap(yaml))

    private fun preservesComposedMeaning() {
        val compiler = compiler()
        val frontend = IntentYamlFrontend(compiler)
        val units = listOf(
            accepted(frontend.compileText(intentYaml, "integrity.yaml")),
            accepted(frontend.compileText(json(intentYaml), "integrity.json")),
            accepted(proposal(compiler, IntentYamlLoader.loadText(intentYaml))))
        require(units.map { it.graphDigest }.distinct().size == 1) { "Equivalent frontends changed the canonical graph." }
        units.forEach { unit ->
            require(unit.ast.imports.contains(ModuleImportNode(name = "typed", version = "2.3"))) {
                "System identity lost its actual module/version owner."
            }
            val tasks = unit.executionPlan.tasks.associateBy { it.sourceId }
            require(tasks.keys == setOf("audit-step", "record")) { "Authored source identities were changed." }
            require(tasks.getValue("record").dependsOn == listOf(tasks.getValue("audit-step").id)) {
                "Exact dependency identity was lost."
            }
        }
        require(units.map { it.source.sha256 }.distinct().size == 3) { "Frontend provenance was replaced by semantic identity." }
    }

    private fun rejectsDuplicateSourceDeclarations() = withDirectory { directory ->
        val frontend = FlowSourceFrontend(compiler())
        val valid = File(directory, "positive.flow").apply { writeText(flowSource) }
        accepted(frontend.compile(valid))
        val mutants = listOf(
            flowSource.replace("count: 12", "count: 12 count: 13") to "flow.systems[0].config.count",
            flowSource.replace("count: 4", "count: 4 count: 5") to "flow.steps[0].params.count")
        mutants.forEachIndexed { index, (source, path) ->
            val file = File(directory, "duplicate-$index.flow").apply { writeText(source) }
            val error = runCatching { frontend.compile(file) }.exceptionOrNull()
            require(error is DuplicateDeclarationException && error.code == "FLOW_DUPLICATE_DECLARATION" &&
                error.path == path && error.firstOccurrence != error.secondOccurrence) {
                "Duplicate must fail during parsing with both occurrences at $path; observed $error."
            }
        }
    }

    private fun rejectsInvalidSchemaContracts() = withDirectory { directory ->
        val valid = CanonicalModuleLoader.loadText(descriptor, "schema-positive.yaml")
        require(valid.systemTypes.getValue("remote").input.getValue("count").type == SchemaType.NUMBER)
        require(valid.actions.getValue("inspect").input.getValue("count").defaultValue is Number)
        accepted(IntentYamlFrontend(FrontendCompilerComposition.compiler(registry(listOf(valid))))
            .compileText(intentYaml))
        val mutants = listOf(
            descriptor.replace("type: number", "type: imaginary") to ".type",
            descriptor.replace("default: 4", "default: '4'") to ".default",
            descriptor.replace("default: 4", "default: null") to ".default")
        mutants.forEachIndexed { index, (source, path) ->
            listOf(source, json(source)).forEachIndexed { format, text ->
                val file = File(directory, "schema-$index-$format.yaml").apply { writeText(text) }
                listOf<() -> Any>({ CanonicalModuleLoader.loadText(text, file.path) },
                    { CanonicalModuleLoader.loadFile(file) }).forEach { load ->
                    val error = runCatching(load).exceptionOrNull()
                    require(error is CanonicalModuleLoader.ContractException && path in error.message.orEmpty() &&
                        file.path in error.message.orEmpty()) { "Invalid schema crossed its public loader: $error" }
                }
            }
        }
    }

    private fun rejectsInvalidAuthoredValues() = withDirectory { directory ->
        val compiler = compiler()
        val frontend = IntentYamlFrontend(compiler)
        accepted(frontend.compileText(intentYaml))
        accepted(proposal(compiler, IntentYamlLoader.loadText(intentYaml)))
        val invalid = intentYaml.replace("count: 12", "count: '12'")
        listOf(invalid, json(invalid)).forEach {
            reject(frontend.compileText(it), CompilationStage.INTENT_VALIDATION, "SYSTEM_CONFIG_TYPE_MISMATCH")
        }
        reject(proposal(compiler, IntentYamlLoader.loadText(invalid)), CompilationStage.PROPOSAL_REVIEW,
            "SYSTEM_CONFIG_TYPE_MISMATCH")
        val file = File(directory, "typed.flow").apply { writeText(flowSource) }
        val source = FlowSourceFrontend(compiler)
        accepted(source.compile(file))
        file.writeText(flowSource.replace("count: 12", "count: \"12\""))
        reject(source.compile(file), CompilationStage.FLOW_VALIDATION, "TYPE_MISMATCH", beforeAst = false)
    }

    private fun rejectsAmbiguousOwners() {
        val other = descriptor.replace("name: typed", "name: other").replace("remote", "alternate")
        val orders = listOf(listOf(descriptor, other), listOf(other, descriptor))
        val units = orders.map { documents ->
            val modules = CanonicalModuleLoader.loadTexts(documents)
            accepted(IntentYamlFrontend(FrontendCompilerComposition.compiler(registry(modules)))
                .compileText(intentYaml))
        }
        require(units[0].graphDigest == units[1].graphDigest) { "Catalog order changed meaning." }
        val conflicting = other.replace("alternate", "remote")
        listOf(listOf(descriptor, conflicting), listOf(conflicting, descriptor)).forEach { documents ->
            val loaderError = runCatching { CanonicalModuleLoader.loadTexts(documents) }.exceptionOrNull()
            require(loaderError is CanonicalModuleLoader.ContractException &&
                (loaderError.cause as? ModuleCatalogException)?.code == "DUPLICATE_SYSTEM_TYPE") {
                "Public descriptor composition selected an ambiguous owner: $loaderError"
            }
            val modules = documents.map { CanonicalModuleLoader.loadText(it) }
            val provider = object : ModuleCatalog {
                override fun allModules(): Collection<FlowModule> = modules
                override fun findModule(name: String): FlowModule? = modules.first()
            }
            val compilerError = runCatching { FrontendCompilerComposition.compiler(provider) }.exceptionOrNull()
            require((compilerError as? ModuleCatalogException)?.code == "DUPLICATE_SYSTEM_TYPE") {
                "Programmatic composition selected an ambiguous owner: $compilerError"
            }
        }
    }

    private fun rejectsCollidingSemanticIdentities() {
        val compiler = compiler()
        val frontend = IntentYamlFrontend(compiler)
        accepted(frontend.compileText(intentYaml))
        accepted(proposal(compiler, IntentYamlLoader.loadText(intentYaml)))
        val invalid = intentYaml.replace("id: record", "id: audit_step")
        listOf(invalid, json(invalid)).forEach {
            reject(frontend.compileText(it), CompilationStage.INTENT_VALIDATION, "INTENT_SYMBOL_COLLISION")
        }
        reject(proposal(compiler, IntentYamlLoader.loadText(invalid)), CompilationStage.PROPOSAL_REVIEW,
            "INTENT_SYMBOL_COLLISION")
    }

    private fun rejectsMalformedPublicDocuments() = withDirectory { directory ->
        val frontend = IntentYamlFrontend(compiler())
        listOf(intentYaml, json(intentYaml)).forEachIndexed { index, text ->
            accepted(frontend.compileText(text, "positive-$index"))
            accepted(frontend.compile(File(directory, "positive-$index.yaml").apply { writeText(text) }))
        }
        val mutants = listOf(
            intentYaml + "\nname: overwritten", intentYaml + "\nunknown: true",
            intentYaml + "\n---\n{}", intentYaml.replace("count: 12", "count: 12, count: 13"),
            json(intentYaml).replaceFirst("{", "{\"name\":\"overwritten\","),
            json(intentYaml).replaceFirst("{", "{\"unknown\":true,"), json(intentYaml) + " {}")
        mutants.forEachIndexed { index, text ->
            val file = File(directory, "malformed-$index.yaml").apply { writeText(text) }
            listOf<() -> Any>({ frontend.compileText(text, file.path) }, { frontend.compile(file) }).forEach { compile ->
                val error = runCatching(compile).exceptionOrNull()
                require(error != null && file.path in error.message.orEmpty()) {
                    "Malformed input must fail at capture with source provenance; observed $error."
                }
            }
        }
    }

    private fun reject(result: CompilationResult, stage: CompilationStage, code: String, beforeAst: Boolean = true) {
        require(result is CompilationResult.Rejected) { "Invalid meaning acquired compilation authority." }
        val rejected = result.rejection
        require(rejected.stage == stage && rejected.diagnostics.any { it.code == code }) {
            "Expected $stage/$code, observed ${rejected.stage}/${rejected.diagnostics}."
        }
        if (beforeAst) require(rejected.ast == null && rejected.flowValidation == null) {
            "Rejected meaning crossed the AST construction boundary."
        }
        require(runCatching { result.requireAccepted() }.isFailure) { "Rejection exposed an accepted compilation unit." }
    }

    private fun withDirectory(probe: (File) -> Unit) {
        val directory = createTempDirectory("language-integrity-").toFile()
        try { probe(directory) } finally { check(directory.deleteRecursively()) }
    }

    private fun check(name: String, probe: () -> Unit): ConformanceCheck = runCatching(probe).fold(
        { ConformanceCheck(name, true) },
        { ConformanceCheck(name, false, it.message ?: it.javaClass.simpleName) })

    companion object {
        const val PARITY = "architecture-recovery.language.integrated-frontend-parity"
        const val DECLARATIONS = "architecture-recovery.language.integrated-declaration-rejection"
        const val SCHEMAS = "architecture-recovery.language.integrated-schema-contracts"
        const val VALUES = "architecture-recovery.language.integrated-authored-types"
        const val OWNERS = "architecture-recovery.language.integrated-system-owners"
        const val IDENTITIES = "architecture-recovery.language.integrated-semantic-identities"
        const val LOADERS = "architecture-recovery.language.integrated-public-loaders"
        val descriptor = """
            kind: FlowModule
            name: typed
            version: "2.3"
            description: Integration contract
            systemTypes:
              remote:
                input: {count: {type: number, required: true}}
            actions:
              inspect:
                kind: action
                targetTypes: [remote]
                input: {count: {type: number, default: 4}}
                output: {}
                effects: {}
                safety: {destructive: false}
        """.trimIndent()
        val intentYaml = """
            name: language-integrity
            systems:
              - name: target
                type: remote
                config: {count: 12}
            workflows:
              - name: main
                kind: CUSTOM
                steps:
                  - {id: audit-step, capability: CUSTOM, params: {operation: inspect}}
                  - {id: record, capability: CUSTOM, requires: [audit-step], params: {operation: record}}
        """.trimIndent()
        val flowSource = """
            use module "typed" version "2.3"
            flow "language-integrity" {
                systems { system "target" { type: remote count: 12 } }
                steps { typed.inspect target { count: 4 } }
            }
        """.trimIndent()
    }
}
