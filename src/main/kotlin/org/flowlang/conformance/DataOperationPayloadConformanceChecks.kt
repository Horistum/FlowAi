package org.flowlang.conformance

import java.io.File
import kotlin.io.path.createTempDirectory
import org.flowlang.compiler.CanonicalExecutionGraphProjection
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.CompilationStage
import org.flowlang.compiler.requireAccepted
import org.flowlang.core.UnsupportedDataOperationPayloadException
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

/** Audit A-02: source payload differences must not collapse into accepted identical graphs. */
internal class DataOperationPayloadConformanceChecks(private val rootDir: File) {
    fun checks(): List<ConformanceCheck> = listOf(
        check("architecture-recovery.language.data-payloads-rejected-before-authorization") {
            withFrontend { frontend, file ->
                for (body in rejectedBodies) {
                    file.writeText(source(body))
                    val result = frontend.compile(file)
                    require(result is CompilationResult.Rejected) { "Payload was accepted: $body" }
                    require(result.rejection.stage == CompilationStage.FLOW_VALIDATION)
                    require(result.rejection.diagnostics.any { it.code == "DATA_OPERATION_PAYLOAD_UNSUPPORTED" })
                    require(result.rejection.ast == FlowParser().parse(file))
                    require(result.rejection.flowValidation?.valid == false)
                }
            }
        },
        check("architecture-recovery.language.data-payloads-direct-planner-rejection") {
            val planner = FlowPlanner(ModuleRegistry.fromDirectory(File(rootDir, "modules")))
            for (body in rejectedBodies) {
                val failure = runCatching { planner.plan(FlowParser().parse(source(body))) }.exceptionOrNull()
                require(failure is UnsupportedDataOperationPayloadException) { "Planner discarded payload: $body ($failure)" }
                require(failure.code == "DATA_OPERATION_PAYLOAD_UNSUPPORTED")
                require(failure.unsupportedFields.isNotEmpty())
            }
        },
        check("architecture-recovery.language.empty-data-payload-graph-preservation") {
            withFrontend { frontend, file ->
                for (body in listOf("transform [1] -> out {}", "aggregate [1] -> out {}", "validate [1] {}")) {
                    file.writeText(source(body))
                    val baseline = frontend.compile(file).requireAccepted()
                    require(baseline.executionPlan == CanonicalExecutionGraphProjection.toExecutionPlan(
                        baseline.graph, baseline.authorization.inspectionView().bindings))
                    baseline.authorization.requireIntegrity()
                    file.writeText(source(body.replace("[1]", "[2]")))
                    val changed = frontend.compile(file).requireAccepted()
                    require(baseline.executionPlan != changed.executionPlan)
                    require(baseline.graph != changed.graph && baseline.graphDigest != changed.graphDigest)
                }
            }
        }
    )

    private fun withFrontend(action: (FlowSourceFrontend, File) -> Unit) {
        val root = createTempDirectory("data-payload-conformance-").toFile()
        try {
            val modules = ModuleRegistry.fromDirectory(File(rootDir, "modules"))
            action(FlowSourceFrontend(FrontendCompilerComposition.compiler(modules)), File(root, "source.flow"))
        } finally { check(root.deleteRecursively()) }
    }

    private fun check(name: String, action: () -> Unit): ConformanceCheck {
        val result = runCatching(action)
        return ConformanceCheck(name, result.isSuccess, result.exceptionOrNull()?.message)
    }

    private fun source(body: String) = "version \"1.0\"\nflow \"data-admission\" { steps { $body } }"

    private val rejectedBodies = listOf(
        "transform [1] -> out { where true select { value: 1 } }",
        "transform [1] -> out { where false select { value: 1 } }",
        "transform [1] -> out { select { value: 1 } }",
        "transform [1] -> out { select { value: 2 } }",
        "aggregate [1] -> out { total: 1 }", "aggregate [1] -> out { total: 2 }",
        "validate [1] { true }", "validate [1] { false }",
        "transform [1] -> out { where true }", "transform [1] -> out { where false }",
        "validate [1] { required item.id }"
    )
}
