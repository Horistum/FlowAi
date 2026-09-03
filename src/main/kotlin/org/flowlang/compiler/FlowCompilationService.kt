package org.flowlang.compiler

import org.flowlang.ai.normalization.IntentProposalDecision
import org.flowlang.ai.normalization.IntentProposalReview
import org.flowlang.ai.normalization.IntentProposalReviewEvidence
import org.flowlang.ast.FlowDocument
import org.flowlang.core.FlowAvailabilityAnalyzer
import org.flowlang.core.FlowWorkflowIdentity
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.LoweredIntentProgram
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentValidationReport
import org.flowlang.intent.ValidatedIntent
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.ExecutionProgramPlanningResult
import org.flowlang.planner.PlanSchedule
import org.flowlang.planner.PlanTrigger
import org.flowlang.planner.RuntimeParamRenderer
import org.flowlang.planner.WorkflowPlanningResult
import org.flowlang.lowering.IntentLoweringAuthority
import org.flowlang.validator.FlowValidator

/**
 * Shared target-neutral orchestration boundary for accepted frontend meaning.
 *
 * The planner is a construction stage. Accepted meaning crosses this service only
 * after it has been converted into a typed CanonicalExecutionGraph, validated,
 * digested and projected back without drift. Downstream consumers receive graph
 * authorization from CompilationUnit rather than an independently trusted plan.
 *
 * AR-02A computes path-sensitive value availability once here and supplies that
 * exact immutable result to both validation and planning. The compiler therefore
 * has one control-flow truth rather than two implementations that can drift.
 */
class FlowCompilationService(
    private val registry: ModuleRegistry
) {
    private val proposalReview = IntentProposalReview(registry)
    private val intentPlanner = IntentToAstPlanner(registry)
    private val flowAvailabilityAnalyzer = FlowAvailabilityAnalyzer()
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
        val program = try {
            intentPlanner.planProgram(requireNotNull(intentEvaluation.accepted))
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

        return compileProgram(
            source = source,
            evidence = evidence,
            program = program
        )
    }

    private data class WorkflowCompilationDraft(
        val name: String,
        val ast: FlowDocument,
        val availability: org.flowlang.core.FlowAvailabilityAnalysis,
        val validation: org.flowlang.validator.ValidationReport,
        val planning: org.flowlang.planner.FlowPlanningResult
    )

    private fun compileProgram(
        source: CompilationSource,
        evidence: IntentCompilationEvidence,
        program: LoweredIntentProgram
    ): CompilationResult {
        val multiple = program.workflows.size > 1
        val drafts = mutableListOf<WorkflowCompilationDraft>()
        program.workflows.sortedBy { it.name }.forEach { workflow ->
            val identity = FlowWorkflowIdentity(workflow.name)
            val availability = flowAvailabilityAnalyzer.analyze(workflow.document, identity)
            val validation = flowValidator.validate(workflow.document, availability)
            if (!validation.valid) {
                return CompilationResult.Rejected(
                    rejection(
                        source = source,
                        stage = CompilationStage.FLOW_VALIDATION,
                        diagnostics = ensureErrorDiagnostic(
                            diagnostics = validation.issues.map { issue ->
                                CompilationDiagnostic(
                                    issue.code,
                                    "[workflow ${workflow.name}] ${issue.message}",
                                    CompilationDiagnosticSeverity.fromWire(issue.level)
                                )
                            },
                            fallbackCode = "compiler.flow-validation.failed",
                            fallbackMessage = "Workflow '${workflow.name}' validation failed without an error diagnostic."
                        ),
                        evidence = evidence,
                        ast = workflow.document,
                        flowValidation = validation
                    )
                )
            }
            val planning = try {
                flowPlanner.planWithProvenance(
                    document = workflow.document,
                    availability = availability,
                    workflowIdentity = identity,
                    nodeIdNamespace = workflow.name.takeIf { multiple }
                )
            } catch (failure: Exception) {
                return CompilationResult.Rejected(
                    rejection(
                        source = source,
                        stage = CompilationStage.PLANNING,
                        diagnostics = listOf(
                            CompilationDiagnostic(
                                "compiler.planning.failed",
                                "[workflow ${workflow.name}] ${failure.message ?: failure.javaClass.simpleName}"
                            )
                        ),
                        evidence = evidence,
                        ast = workflow.document,
                        flowValidation = validation
                    )
                )
            }
            drafts += WorkflowCompilationDraft(
                workflow.name,
                workflow.document,
                availability,
                validation,
                planning
            )
        }

        val inputs = drafts.first().planning.plan.inputs
        require(drafts.all { it.planning.plan.inputs == inputs }) {
            "Independent workflow plans changed the shared input contract."
        }
        val triggers = program.triggers.map { trigger ->
            PlanTrigger(
                id = trigger.id,
                type = trigger.triggerType,
                workflows = trigger.workflows,
                schedule = trigger.schedule?.let { PlanSchedule(it.kind, it.expression, it.timezone) },
                event = trigger.event,
                params = trigger.params.mapValues { (_, value) -> RuntimeParamRenderer.render(value, emptySet()) },
                requiredCapabilities = when (trigger.triggerType) {
                    "SCHEDULE" -> listOf("trigger.schedule.${trigger.schedule?.kind?.lowercase() ?: "unknown"}")
                    "EVENT" -> listOf("trigger.event")
                    "WEBHOOK" -> listOf("trigger.webhook")
                    "MANUAL" -> listOf("trigger.manual")
                    else -> listOf("trigger.unknown")
                }
            )
        }
        val loweringReport = if (drafts.size == 1) {
            drafts.single().planning.plan.loweringReport
        } else {
            IntentLoweringAuthority.report(
                flowName = program.name,
                inputs = inputs,
                triggers = triggers,
                sourceIntent = program.sourceIntent,
                workflowPlans = drafts.map { it.planning.plan }
            )
        }
        val planning = ExecutionProgramPlanningResult(
            flowName = program.name,
            inputs = inputs,
            triggers = triggers,
            sourceIntent = program.sourceIntent,
            loweringReport = loweringReport,
            workflows = drafts.map { draft ->
                WorkflowPlanningResult(draft.name, draft.planning, draft.availability)
            }
        )

        return try {
            CompilationResult.Accepted(
                CompilationUnit.fromProgram(
                    source = source,
                    frontendEvidence = evidence,
                    workflows = drafts.map { draft ->
                        CompiledWorkflow(draft.name, draft.ast, draft.validation)
                    },
                    planning = planning
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
                    ast = drafts.firstOrNull()?.ast,
                    flowValidation = drafts.firstOrNull()?.validation
                )
            )
        }
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
        val availability = flowAvailabilityAnalyzer.analyze(ast)
        val validation = flowValidator.validate(ast, availability)
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

        val planning = try {
            flowPlanner.planWithProvenance(ast, availability)
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
                    planning = planning,
                    availability = availability
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
