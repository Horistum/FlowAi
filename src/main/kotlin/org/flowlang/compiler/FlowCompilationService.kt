package org.flowlang.compiler

import org.flowlang.ast.FlowDocument
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.ValidatedIntent
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.validator.FlowValidator

/**
 * Shared target-neutral orchestration boundary for accepted frontend meaning.
 *
 * The planner is a construction stage. Accepted meaning crosses this service only
 * after it has been converted into a typed CanonicalExecutionGraph, validated,
 * digested and projected back without drift. Downstream consumers receive graph
 * authorization from CompilationUnit rather than an independently trusted plan.
 */
class FlowCompilationService(
    private val registry: ModuleRegistry
) {
    private val intentPlanner = IntentToAstPlanner(registry)
    private val flowValidator = FlowValidator(registry)
    private val flowPlanner = FlowPlanner(registry)

    internal fun compile(input: CompilationInput): CompilationResult = when (input) {
        is FlowSourceCompilationInput -> compileFlowSource(input)
        is IntentCompilationInput -> compileIntent(input)
    }

    private fun compileFlowSource(input: FlowSourceCompilationInput): CompilationResult =
        compileAst(
            source = input.source,
            evidence = FlowSourceCompilationEvidence(input.source),
            ast = input.ast
        )

    private fun compileIntent(input: IntentCompilationInput): CompilationResult {
        val intentEvaluation = ValidatedIntent.evaluate(registry, input.intent)
        val intentValidation = intentEvaluation.report
        if (!intentValidation.valid) {
            return CompilationResult.Rejected(
                CompilationRejection(
                    source = input.source,
                    stage = CompilationStage.INTENT_VALIDATION,
                    diagnostics = ensureErrorDiagnostic(
                        diagnostics = intentValidation.issues.map { issue ->
                            CompilationDiagnostic(
                                issue.code,
                                issue.message,
                                CompilationDiagnosticSeverity.fromWire(issue.level)
                            )
                        },
                        fallbackCode = "compiler.intent-validation.failed",
                        fallbackMessage = "Intent validation failed without an error diagnostic."
                    ),
                    intent = input.intent,
                    intentValidation = intentValidation
                )
            )
        }

        val ast = try {
            intentPlanner.plan(requireNotNull(intentEvaluation.accepted))
        } catch (failure: Exception) {
            return CompilationResult.Rejected(
                CompilationRejection(
                    source = input.source,
                    stage = CompilationStage.INTENT_LOWERING,
                    diagnostics = listOf(
                        CompilationDiagnostic(
                            "compiler.intent-lowering.failed",
                            failure.message ?: failure.javaClass.simpleName
                        )
                    ),
                    intent = input.intent,
                    intentValidation = intentValidation
                )
            )
        }

        return compileAst(
            source = input.source,
            evidence = IntentFrontendCompilationEvidence(
                source = input.source,
                intent = input.intent,
                validation = intentValidation
            ),
            ast = ast
        )
    }

    private fun ensureErrorDiagnostic(
        diagnostics: List<CompilationDiagnostic>,
        fallbackCode: String,
        fallbackMessage: String
    ): List<CompilationDiagnostic> =
        if (diagnostics.any { it.severity == CompilationDiagnosticSeverity.ERROR }) {
            diagnostics
        } else {
            diagnostics + CompilationDiagnostic(fallbackCode, fallbackMessage)
        }

    private fun compileAst(
        source: CompilationSource,
        evidence: FrontendCompilationEvidence,
        ast: FlowDocument
    ): CompilationResult {
        val validation = flowValidator.validate(ast)
        if (!validation.valid) {
            return CompilationResult.Rejected(
                CompilationRejection(
                    source = source,
                    stage = CompilationStage.FLOW_VALIDATION,
                    diagnostics = ensureErrorDiagnostic(
                        diagnostics = validation.issues.map { issue ->
                            CompilationDiagnostic(
                                issue.code,
                                issue.message,
                                CompilationDiagnosticSeverity.fromWire(issue.level)
                            )
                        },
                        fallbackCode = "compiler.flow-validation.failed",
                        fallbackMessage = "Flow validation failed without an error diagnostic."
                    ),
                    intent = (evidence as? IntentFrontendCompilationEvidence)?.intent,
                    intentValidation = (evidence as? IntentFrontendCompilationEvidence)?.validation,
                    ast = ast,
                    flowValidation = validation
                )
            )
        }

        val plannerPlan = try {
            flowPlanner.plan(ast)
        } catch (failure: Exception) {
            return CompilationResult.Rejected(
                CompilationRejection(
                    source = source,
                    stage = CompilationStage.PLANNING,
                    diagnostics = listOf(
                        CompilationDiagnostic(
                            "compiler.planning.failed",
                            failure.message ?: failure.javaClass.simpleName
                        )
                    ),
                    intent = (evidence as? IntentFrontendCompilationEvidence)?.intent,
                    intentValidation = (evidence as? IntentFrontendCompilationEvidence)?.validation,
                    ast = ast,
                    flowValidation = validation
                )
            )
        }

        return try {
            CompilationResult.Accepted(
                CompilationUnit.from(
                    source = source,
                    frontendEvidence = evidence,
                    ast = ast,
                    validation = validation,
                    plannerPlan = plannerPlan
                )
            )
        } catch (failure: Exception) {
            CompilationResult.Rejected(
                CompilationRejection(
                    source = source,
                    stage = CompilationStage.CANONICALIZATION,
                    diagnostics = listOf(
                        CompilationDiagnostic(
                            "compiler.canonicalization.failed",
                            failure.message ?: failure.javaClass.simpleName
                        )
                    ),
                    intent = (evidence as? IntentFrontendCompilationEvidence)?.intent,
                    intentValidation = (evidence as? IntentFrontendCompilationEvidence)?.validation,
                    ast = ast,
                    flowValidation = validation
                )
            )
        }
    }
}
