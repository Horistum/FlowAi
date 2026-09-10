package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import org.flowlang.ast.ActionNode
import org.flowlang.ast.MatchNode
import org.flowlang.ast.TransformNode
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.FlowModule
import org.flowlang.modules.ModuleCatalog
import org.flowlang.parser.DuplicateDeclarationException
import org.flowlang.parser.FlowParser

/** Executable source-integrity evidence; an inventory or exception class alone cannot pass it. */
internal class SourceDeclarationConformanceChecks {
    fun checks(): List<ConformanceCheck> = listOf(
        check(SINGLETONS) { singletonErrors() },
        check(MAPS) { mapErrors() },
        check(SCOPES) { scopeErrors() },
        check(FRONTEND) { frontendErrors() }
    )

    private fun singletonErrors(): List<String> {
        val documents = listOf(
            "version \"1.0\" version \"2.0\" flow \"x\" { steps {} }" to "version",
            "flow \"x\" { input { a: text default null default \"next\" } steps {} }" to "flow.input[0].default",
            "flow \"x\" { input { a: text required required } steps {} }" to "flow.input[0].required",
            "flow \"x\" { systems { system \"s\" { type: one type: two } } steps {} }" to "flow.systems[0].type",
            "flow \"x\" { systems { system \"s\" { type: one key: null key: null } } steps {} }" to "flow.systems[0].config.key",
            "flow \"x\" { on error {} steps {} on error {} }" to "flow.errorHandler"
        )
        val statements = listOf(
            "m.a s { key: 1 key: 2 }" to "params.key",
            "m.a s { safety: requiresApproval safety: onlyIf true }" to "safety",
            "approve manual { message: null message: \"next\" }" to "params.message",
            "transform xs -> ys { where false where true }" to "where",
            "transform xs -> ys { select { key: 1 key: 2 } }" to "select.key",
            "transform xs -> ys { select { key: 1 } select { key: 1 } }" to "select.key",
            "aggregate xs -> ys { key: 1 key: 2 }" to "fields.key",
            "match value { default {} default {} }" to "defaultSteps",
            "match value { when error {} when error {} }" to "errorCase"
        ).map { (body, path) -> flow(body) to "flow.steps[0].$path" }
        return (documents + statements).flatMap { (source, path) -> rejectionErrors(source, path) }
    }

    private fun mapErrors(): List<String> = listOf(
        "m.a s { payload: {key: 1 'key': 2} }" to "flow.steps[0].params.payload.key",
        "m.a s { payload: [{inner: {key: 1 key: 2}}] }" to "flow.steps[0].params.payload[0].inner.key",
        "m.a s { payload: \"prefix ${'$'}{{key: 1 key: 2}}\" }" to "flow.steps[0].params.payload.parts[1].key"
    ).flatMap { (source, path) -> rejectionErrors(flow(source), path) }

    private fun rejectionErrors(source: String, path: String): List<String> {
        val error = runCatching { FlowParser().parse(source, "source-integrity.flow") }.exceptionOrNull()
        if (error !is DuplicateDeclarationException) {
            return listOf("$path must reject a duplicate declaration, observed ${error?.javaClass?.simpleName ?: "accepted AST"}.")
        }
        val first = error.firstOccurrence
        val second = error.secondOccurrence
        return if (error.code == DuplicateDeclarationException.CODE && error.path == path &&
            first.line > 0 && first.column > 0 && second.line > 0 && second.column > 0 &&
            (second.line > first.line || (second.line == first.line && second.column > first.column))) {
            emptyList()
        } else listOf("$path did not identify both source occurrences in order: ${error.message}")
    }

    private fun scopeErrors(): List<String> {
        val document = FlowParser().parse("""
            flow "x" {
                input { a: text } input { b: text }
                steps { transform xs -> ys { select { a: 1 } select {} select { b: 2 } } }
                steps {
                    m.a s { payload: [{key: 1}, {key: 2}] safety: requiresApproval }
                    m.a s { payload: {key: 3} safety: onlyIf true }
                    match x { when true {} when true {} default {} when error {} }
                    m.a s {} -> r { when error {} when error {} }
                }
            }
        """.trimIndent())
        val steps = document.flow.steps
        return if (document.flow.input.map { it.name } == listOf("a", "b") && steps.size == 5 &&
            (steps[0] as? TransformNode)?.select?.keys == setOf("a", "b") &&
            (steps[3] as? MatchNode)?.cases?.size == 2 &&
            (steps[4] as? ActionNode)?.handler?.rules?.size == 2) emptyList()
        else listOf("Distinct scopes, additive blocks or ordered rule lists lost their authored contents.")
    }

    private fun frontendErrors(): List<String> {
        var lookups = 0
        val catalog = object : ModuleCatalog {
            override fun findModule(name: String): FlowModule? { lookups++; return null }
            override fun allModules(): Collection<FlowModule> { lookups++; return emptyList() }
        }
        val directory = createTempDirectory("source-integrity-evidence-").toFile()
        try {
            val file = File(directory, "source.flow").apply {
                writeText(flow("m.a s { safety: requiresApproval safety: onlyIf true }"))
            }
            val failure = runCatching {
                FlowSourceFrontend(FrontendCompilerComposition.compiler(catalog)).compile(file)
            }.exceptionOrNull()
            return if (failure is DuplicateDeclarationException && failure.path == "flow.steps[0].safety" && lookups == 0) {
                emptyList()
            } else listOf("Duplicate safety crossed the source boundary: failure=${failure?.javaClass?.simpleName}, module lookups=$lookups.")
        } finally {
            check(directory.deleteRecursively()) { "Cannot remove source-integrity evidence directory." }
        }
    }

    private fun check(name: String, assess: () -> List<String>): ConformanceCheck {
        val errors = runCatching(assess).getOrElse { listOf(it.message ?: it.javaClass.simpleName) }
        return ConformanceCheck(name, errors.isEmpty(), errors.takeIf { it.isNotEmpty() }?.joinToString(" | "))
    }

    private fun flow(steps: String): String = "flow \"x\" { steps { $steps } }"

    companion object {
        const val SINGLETONS = "architecture-recovery.language.non-overwriting-singletons"
        const val MAPS = "architecture-recovery.language.non-overwriting-map-keys"
        const val SCOPES = "architecture-recovery.language.declaration-scope-preservation"
        const val FRONTEND = "architecture-recovery.language.early-source-rejection"
    }
}
