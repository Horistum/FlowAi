package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import org.flowlang.compiler.*
import org.flowlang.core.InvalidInputApprovalValueException
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

/** A-06 counterexamples must fail before an accepted graph can erase their values. */
internal class InputApprovalValueConformanceChecks(private val rootDir: File) {
    fun checks(): List<ConformanceCheck> = listOf(
        check("architecture-recovery.language.input-values-rejected-before-authorization") {
            withFrontend { frontend, file ->
                for ((input, code) in invalidInputs) {
                    file.writeText(source(input))
                    val result = frontend.compile(file)
                    require(result is CompilationResult.Rejected)
                    require(result.rejection.stage == CompilationStage.FLOW_VALIDATION)
                    require(result.rejection.diagnostics.any { it.code == code })
                }
            }
        },
        check("architecture-recovery.language.approval-parameters-rejected-before-authorization") {
            withFrontend { frontend, file ->
                for (params in listOf("unexpected: 1", "unexpected: 2", "timeout: 10", "message: false")) {
                    file.writeText(source("", "approve manual { $params }"))
                    val result = frontend.compile(file)
                    require(result is CompilationResult.Rejected)
                    require(result.rejection.diagnostics.any { it.code in setOf("APPROVAL_PARAM_UNSUPPORTED", "APPROVAL_MESSAGE_TYPE_MISMATCH") })
                }
            }
        },
        check("architecture-recovery.language.input-approval-direct-planner-rejection") {
            val planner = FlowPlanner(ModuleRegistry.fromDirectory(File(rootDir, "modules")))
            for ((input, code) in invalidInputs) {
                val failure = runCatching { planner.plan(FlowParser().parse(source(input))) }.exceptionOrNull()
                require(failure is InvalidInputApprovalValueException && failure.code == code)
            }
            val ast = FlowParser().parse(source("", "try {} on error { approve manual { unexpected: 1 } }"))
            val failure = runCatching { planner.plan(ast) }.exceptionOrNull()
            require(failure is InvalidInputApprovalValueException && failure.code == "APPROVAL_PARAM_UNSUPPORTED")
        },
        check("architecture-recovery.language.accepted-input-approval-value-preservation") {
            withFrontend { frontend, file ->
                val units = listOf("a", "b").map { value ->
                    file.writeText(source("choice: option [\"$value\", \"other\"] default \"$value\"", "approve manual { message: \"Review $value\" }"))
                    frontend.compile(file).requireAccepted().also { unit ->
                        require(unit.graph.inputs.single().choices == listOf(value, "other"))
                        require(unit.graph.inputs.single().defaultValue == value)
                        require((unit.graph.nodes.single() as CanonicalApprovalNode).message == "Review $value")
                        require(unit.executionPlan == CanonicalExecutionGraphProjection.toExecutionPlan(unit.graph, unit.authorization.inspectionView().bindings))
                        unit.authorization.requireIntegrity()
                    }
                }
                require(units[0].graphDigest != units[1].graphDigest)
            }
        }
    )

    private val invalidInputs = listOf(
        "value: mystery" to "INPUT_TYPE_UNSUPPORTED",
        "value: boolean default \"false\"" to "INPUT_DEFAULT_TYPE_MISMATCH",
        "value: option [1, 2]" to "INPUT_OPTION_VALUE_UNSUPPORTED",
        "value: option [3, 4]" to "INPUT_OPTION_VALUE_UNSUPPORTED",
        "value: option [\"a\", \"b\"] default \"c\"" to "INPUT_DEFAULT_NOT_IN_OPTION",
        "value: text default value" to "INPUT_DEFAULT_UNSUPPORTED"
    )
    private fun source(inputs: String, body: String = "approve manual { message: \"Review\" }") =
        "version \"1.0\"\nflow \"value-admission\" { input { $inputs } steps { $body } }"
    private fun withFrontend(action: (FlowSourceFrontend, File) -> Unit) {
        val root = createTempDirectory("input-approval-conformance-").toFile()
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
