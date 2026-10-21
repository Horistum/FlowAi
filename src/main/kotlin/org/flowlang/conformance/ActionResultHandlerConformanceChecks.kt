package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.CompilationStage
import org.flowlang.compiler.requireAccepted
import org.flowlang.core.UnsupportedActionResultHandlerException
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

/** Audit A-01: unsupported handler meaning must never reach graph authorization. */
internal class ActionResultHandlerConformanceChecks(private val rootDir: File) {
    fun checks(): List<ConformanceCheck> = listOf(
        check("architecture-recovery.language.action-handlers-rejected-before-authorization") {
            withFrontend { frontend, file ->
                for (rule in rules) {
                    file.writeText(source("{ $rule }"))
                    val result = frontend.compile(file)
                    require(result is CompilationResult.Rejected) { "Handler was accepted: $rule" }
                    require(result.rejection.stage == CompilationStage.FLOW_VALIDATION)
                    require(result.rejection.diagnostics.any { it.code == "ACTION_RESULT_HANDLER_UNSUPPORTED" })
                    require(result.rejection.ast != null) { "Rejected source lost its inspectable AST." }
                }
            }
        },
        check("architecture-recovery.language.action-handlers-direct-planner-rejection") {
            val planner = FlowPlanner(ModuleRegistry.fromDirectory(File(rootDir, "modules")))
            for (rule in rules) {
                val ast = FlowParser().parse(source("{ $rule }"))
                val failure = runCatching { planner.plan(ast) }.exceptionOrNull()
                require(failure is UnsupportedActionResultHandlerException) { "Planner discarded handler: $rule ($failure)" }
                require(failure.code == "ACTION_RESULT_HANDLER_UNSUPPORTED" && failure.moduleName == "git")
            }
        },
        check("architecture-recovery.language.empty-action-handler-is-noop") {
            withFrontend { frontend, file ->
                file.writeText(source(""))
                val absent = frontend.compile(file).requireAccepted()
                file.writeText(source("{}"))
                val empty = frontend.compile(file).requireAccepted()
                require(absent.graphDigest == empty.graphDigest && absent.executionPlan == empty.executionPlan)
                empty.authorization.requireIntegrity()
            }
        }
    )

    private fun withFrontend(action: (FlowSourceFrontend, File) -> Unit) {
        val root = createTempDirectory("action-handler-conformance-").toFile()
        try {
            val modules = ModuleRegistry.fromDirectory(File(rootDir, "modules"))
            action(FlowSourceFrontend(FrontendCompilerComposition.compiler(modules)), File(root, "source.flow"))
        } finally { check(root.deleteRecursively()) }
    }

    private fun check(name: String, action: () -> Unit): ConformanceCheck {
        val result = runCatching(action)
        return ConformanceCheck(name, result.isSuccess, result.exceptionOrNull()?.message)
    }

    private val rules = listOf(
        "expect { true }", "expect { false }", "expect {}",
        "when true { fail \"selected\" }", "when false { fail \"selected\" }",
        "when error { fail \"must stop\" }", "when error {}"
    )

    private fun source(handler: String) = """
        version "1.0"
        use module "git" version "1.0"
        flow "handler-admission" {
          systems { system "repo" { type: git } }
          steps { git.checkout repo { url: "https://example.invalid/repo.git" branch: "main" } -> result $handler }
        }
    """.trimIndent()
}
