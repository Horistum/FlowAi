package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import org.flowlang.compiler.CanonicalExecutionGraphProjection
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.CompilationStage
import org.flowlang.compiler.requireAccepted
import org.flowlang.core.UnsupportedGlobalVariableInitializerException
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

/** A-03: global initialization must not disappear into an accepted graph. */
internal class GlobalVariableInitializerConformanceChecks(private val rootDir: File) {
    fun checks(): List<ConformanceCheck> = listOf(
        check("architecture-recovery.language.global-initializers-rejected-before-authorization") {
            withFrontend { frontend, file ->
                for (declarations in listOf("gate: true", "gate: false", "gate: null", "gate: gate",
                    "gate: other\nother: gate")) {
                    file.writeText(source("vars { $declarations }"))
                    val result = frontend.compile(file)
                    require(result is CompilationResult.Rejected)
                    require(result.rejection.stage == CompilationStage.FLOW_VALIDATION)
                    require(result.rejection.diagnostics.any { it.code == "GLOBAL_VARIABLE_INITIALIZER_UNSUPPORTED" })
                    require(result.rejection.ast == FlowParser().parse(file))
                    require(result.rejection.flowValidation?.valid == false)
                }
            }
        },
        check("architecture-recovery.language.global-initializers-direct-planner-rejection") {
            val planner = FlowPlanner(ModuleRegistry.fromDirectory(File(rootDir, "modules")))
            for (value in listOf("true", "false", "null")) {
                for (body in listOf("if gate { approve manual { message: \"Review\" } }", "", "set gate = true")) {
                    val ast = FlowParser().parse(source("vars { gate: $value }", body))
                    val failure = runCatching { planner.plan(ast) }.exceptionOrNull()
                    require(failure is UnsupportedGlobalVariableInitializerException)
                    require(failure.code == "GLOBAL_VARIABLE_INITIALIZER_UNSUPPORTED" && failure.variableName == "gate")
                    require(failure.sourceLocation == ast.flow.vars.single().sourceLocation)
                }
            }
        },
        check("architecture-recovery.language.input-default-graph-preservation") {
            withFrontend { frontend, file ->
                val units = listOf("true", "false").map { value ->
                    file.writeText(source("input { gate: boolean default $value } vars {}"))
                    frontend.compile(file).requireAccepted().also { unit ->
                        require(unit.executionPlan == CanonicalExecutionGraphProjection.toExecutionPlan(
                            unit.graph, unit.authorization.inspectionView().bindings))
                        unit.authorization.requireIntegrity()
                    }
                }
                require(units[0].executionPlan != units[1].executionPlan)
                require(units[0].graph != units[1].graph && units[0].graphDigest != units[1].graphDigest)
            }
        }
    )

    private fun source(declarations: String, body: String = "if gate { approve manual { message: \"Review\" } }") =
        "version \"1.0\"\nflow \"initializer-admission\" { $declarations steps { $body } }"

    private fun withFrontend(action: (FlowSourceFrontend, File) -> Unit) {
        val root = createTempDirectory("global-initializer-conformance-").toFile()
        try {
            val modules = ModuleRegistry.fromDirectory(File(rootDir, "modules"))
            action(FlowSourceFrontend(FrontendCompilerComposition.compiler(modules)), File(root, "source.flow"))
        } finally { check(root.deleteRecursively()) }
    }

    private fun check(name: String, action: () -> Unit): ConformanceCheck {
        val result = runCatching(action)
        return ConformanceCheck(name, result.isSuccess, result.exceptionOrNull()?.message)
    }
}
