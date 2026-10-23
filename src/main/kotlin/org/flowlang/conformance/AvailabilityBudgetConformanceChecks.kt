package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import org.flowlang.compiler.*
import org.flowlang.core.*
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

/** A-04: bounded analysis must reject rather than authorize a truncated state. */
internal class AvailabilityBudgetConformanceChecks(private val rootDir: File) {
    fun checks(): List<ConformanceCheck> = listOf(
        check("architecture-recovery.analysis.neutral-alternative-admission") {
            withFrontend { frontend, file ->
                file.writeText(source(List(100) { "if enabled {} else {}" }.joinToString("\n")))
                val unit = frontend.compile(file).requireAccepted()
                unit.authorization.requireIntegrity()
                require(unit.executionPlan.nodes.size == 100)
                val analysis = FlowAvailabilityAnalyzer().analyze(FlowParser().parse(file.readText()))
                require(analysis.normalExitState.paths.size == 1 && analysis.issues.isEmpty())
            }
        },
        check("architecture-recovery.analysis.source-budget-rejection") {
            withFrontend { frontend, file ->
                file.writeText(source(List(20) { "if enabled { set value$it = true } else {}" }.joinToString("\n")))
                val result = frontend.compile(file)
                require(result is CompilationResult.Rejected)
                require(result.rejection.stage == CompilationStage.FLOW_VALIDATION)
                require(result.rejection.diagnostics.single().code == "FLOW_AVAILABILITY_LIMIT_EXCEEDED")
                require(result.rejection.ast != null && result.rejection.flowValidation?.valid == false)
                require(result.rejection.flowValidation!!.issues.single().location != null)
            }
        },
        check("architecture-recovery.analysis.direct-planner-budget-rejection") {
            val ast = FlowParser().parse(source(List(20) { "if enabled { set value$it = true } else {}" }.joinToString("\n")))
            val failure = runCatching { FlowPlanner(ModuleRegistry.fromDirectory(File(rootDir, "modules"))).plan(ast) }.exceptionOrNull()
            require(failure is FlowAvailabilityLimitException)
            require(failure.code == "FLOW_AVAILABILITY_LIMIT_EXCEEDED")
        },
        check("architecture-recovery.analysis.bounded-merge-preservation") {
            withFrontend { frontend, file ->
                val neutral = List(20) { "if enabled {} else {}" }.joinToString("\n")
                file.writeText(source("if enabled { set left = \"selected\" } else { set right = \"other\" }\n" + neutral + "\n" + """
                    set joined = merge(left, right)
                    approve manual { message: "${'$'}{joined}" }
                """.trimIndent()))
                val unit = frontend.compile(file).requireAccepted()
                require(unit.graph.valueMerges.single().resultBinding == "joined")
                require(unit.graph.valueMerges.single().paths.size == 2)
                unit.authorization.requireIntegrity()
                file.writeText(source("if enabled { set partial = \"selected\" }\n$neutral\napprove manual { message: \"\${partial}\" }"))
                val unsafe = frontend.compile(file)
                require(unsafe is CompilationResult.Rejected)
                require(unsafe.rejection.diagnostics.any { it.code == "VALUE_MAY_BE_UNDEFINED" })
            }
        }
    )

    private fun source(body: String) = "version \"1.0\"\nflow \"budget\" {\ninput { enabled: boolean required }\nsteps {\n$body\n}\n}\n"

    private fun withFrontend(action: (FlowSourceFrontend, File) -> Unit) {
        val dir = createTempDirectory("availability-budget-").toFile()
        try {
            action(FlowSourceFrontend(FrontendCompilerComposition.compiler(
                ModuleRegistry.fromDirectory(File(rootDir, "modules")))), File(dir, "budget.flow"))
        } finally { check(dir.deleteRecursively()) }
    }

    private fun check(name: String, action: () -> Unit): ConformanceCheck {
        val result = runCatching(action)
        return ConformanceCheck(name, result.isSuccess, result.exceptionOrNull()?.toString())
    }
}
