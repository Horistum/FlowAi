package org.flowlang.compiler

import org.flowlang.ai.normalization.IntentProposalDecision
import org.flowlang.ai.normalization.IntentProposalReview
import org.flowlang.ai.normalization.IntentProposalReviewEvidence
import org.flowlang.ast.FlowDocument
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentValidationReport
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
    private val proposalReview = IntentProposalReview(registry)
    private val intentPlanner = IntentToAstPlanner(registry)
    private val flowValidator = FlowValidator(registry)
    private val flowPlanner = FlowPlanner(registry)

    internal fun compile(input: CompilationInput): CompilationResult = when (input) {
        is FlowSourceCompilationInput -> compileFlowSource(input)
        is IntentCompilationInput -> compileIntent(input)
        is ReviewedAiProposalCompilationInput -> compileReviewedAiProposal(input)
    }

    private fun compileFlowSource(input: FlowSourceCompilationInput): CompilationResult =
        compileAst(
            source = input.source,
            evidence = FlowSourceCompilationEvidence(input.source),
            ast = input.ast
        )

    private fun compileIntent(input: IntentCompilationInput): CompilationResult =
        compileIntentDocument(
            source = input.source,
            intent = input.intent,
            evidenceFactory = { validation ->
                IntentFrontendCompilationEvidence(
                    source = input.source,
                    intent = input.intent,
                    validation = validation
                )
            }
        )

    private fun compileReviewedAiProposal(input: ReviewedAiProposalCompilationInput): CompilationResult {
        val review = proposalReview.reviewWithEvidence(input.response)
        val rejected = review.decision as? IntentProposalDecision.Rejected
        if (rejected != null) {
            return CompilationResult.Rejected(
                CompilationRejection(
                    source = input.source,
                    stage = CompilationStage.PROPOSAL_REVIEW,
                    diagnostics = ensureErrorDiagnostic(
                        diagnostics = review.validation.issues.map { issue ->
                            CompilationDiagnostic(
                                issue.code,
                                issue.message,
                                CompilationDiagnosticSeverity.fromWire(issue.level)
                            )
                        },
                        fallbackCode = "compiler.ai-proposal.review-failed",
                        fallbackMessage = "AI proposal review failed without an error diagnostic."
                    ),
                    intent = input.response.normalizedIntent,
                    intentValidation = review.validation,
                    proposal = input.response,
                    proposalReview = review
                )
            )
        }

        return compileIntentDocument(
            source = input.source,
            intent = input.response.normalizedIntent,
            expectedReview = review,
            reviewedInput = input,
            evidenceFactory = { validation ->
                ReviewedAiProposalCompilationEvidence(
                    source = input.source,
                    providerId = input.providerId,
                    request = input.request,
                    response = input.response,
                    review = review,
                    validation = validation
                )
            }
        )
    }

    private fun compileIntentDocument(
        source: CompilationSource,
        intent: IntentDocument,
        expectedReview: IntentProposalReviewEvidence? = null,
        reviewedInput: ReviewedAiProposalCompilationInput? = null,
        evidenceFactory: (IntentValidationReport) -> IntentCompilationEvidence
    ): CompilationResult {
        require((expectedReview == null) == (reviewedInput == null)) {
            "Reviewed AI proposal input and review evidence must be supplied together."
        }
        val intentEvaluation = ValidatedIntent.evaluate(registry, intent)
        val intentValidation = intentEvaluation.report
        if (expectedReview != null && expectedReview.validation != intentValidation) {
            return CompilationResult.Rejected(
                CompilationRejection(
                    source = source,
                    stage = CompilationStage.PROPOSAL_REVIEW,
                    diagnostics = listOf(
                        CompilationDiagnostic(
                            "compiler.ai-proposal.review-drift",
                            "AI proposal review and shared compiler Intent validation produced different evidence."
                        )
                    ),
                    intent = intent,
                    intentValidation = intentValidation,
                    proposal = reviewedInput?.response,
                    proposalReview = expectedReview
                )
            )
        }
        if (!intentValidation.valid) {
            return CompilationResult.Rejected(
                CompilationRejection(
                    source = source,
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
                    intent = intent,
                    intentValidation = intentValidation,
                    proposal = reviewedInput?.response,
                    proposalReview = expectedReview
                )
            )
        }

        val evidence = evidenceFactory(intentValidation)
        val ast = try {
            intentPlanner.plan(requireNotNull(intentEvaluation.accepted))
        } catch (failure: Exception) {
            return CompilationResult.Rejected(
                rejection(
                    source = source,
                    stage = CompilationStage.INTENT_LOWERING,
                    diagnostics = listOf(
                        CompilationDiagnostic(
                            "compiler.intent-lowering.failed",
                            failure.message ?: failure.javaClass.simpleName
                        )
                    ),
                    evidence = evidence,
                    intentValidation = intentValidation
                )
            )
        }

        return compileAst(
            source = source,
            evidence = evidence,
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
                rejection(
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
                    evidence = evidence,
                    ast = ast,
                    flowValidation = validation
                )
            )
        }

        val plannerPlan = try {
            flowPlanner.plan(ast)
        } catch (failure: Exception) {
            return CompilationResult.Rejected(
                rejection(
                    source = source,
                    stage = CompilationStage.PLANNING,
                    diagnostics = listOf(
                        CompilationDiagnostic(
                            "compiler.planning.failed",
                            failure.message ?: failure.javaClass.simpleName
                        )
                    ),
                    evidence = evidence,
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
                rejection(
                    source = source,
                    stage = CompilationStage.CANONICALIZATION,
                    diagnostics = listOf(
                        CompilationDiagnostic(
                            "compiler.canonicalization.failed",
                            failure.message ?: failure.javaClass.simpleName
                        )
                    ),
                    evidence = evidence,
                    ast = ast,
                    flowValidation = validation
                )
            )
        }
    }

    private fun rejection(
        source: CompilationSource,
        stage: CompilationStage,
        diagnostics: List<CompilationDiagnostic>,
        evidence: FrontendCompilationEvidence,
        intentValidation: IntentValidationReport? = (evidence as? IntentCompilationEvidence)?.validation,
        ast: FlowDocument? = null,
        flowValidation: org.flowlang.validator.ValidationReport? = null
    ): CompilationRejection {
        val intentEvidence = evidence as? IntentCompilationEvidence
        val proposalEvidence = evidence as? ReviewedAiProposalCompilationEvidence
        return CompilationRejection(
            source = source,
            stage = stage,
            diagnostics = diagnostics,
            intent = intentEvidence?.intent,
            intentValidation = intentValidation,
            proposal = proposalEvidence?.response,
            proposalReview = proposalEvidence?.review,
            ast = ast,
            flowValidation = flowValidation
        )
    }
}
