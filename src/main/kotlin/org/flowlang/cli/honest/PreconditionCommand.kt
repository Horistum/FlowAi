package org.flowlang.cli.honest

import java.io.File
import java.time.Instant
import org.flowlang.frontend.outcomes.*

class PreconditionCliResult internal constructor(
    val review: OperationPreconditionReview,
    override val presentation: CliPresentation,
    override val artifacts: List<CliArtifact>
) : CliExecutionResult {
    override val exitCode: Int = when (review.state) {
        OperationOutcomeState.SATISFIED -> CliProcessExit.SUCCESS.code
        OperationOutcomeState.VIOLATED -> CliProcessExit.BLOCKED.code
        OperationOutcomeState.UNRESOLVED -> CliProcessExit.REVIEW_REQUIRED.code
    }
    override val diagnostics: List<CliExecutionDiagnostic> = emptyList()
}

internal object PreconditionCommand {
    fun execute(args: CliArguments, output: CliOutputCollector, contractRoot: File): CliExecutionResult {
        val requirements = OperationPreconditionReader.requirements(File(requireNotNull(args.value(CliValueOption.REQUIREMENTS)) {
            "preconditions requires --requirements."
        }))
        val observations = args.value(CliValueOption.OBSERVATIONS)?.let { OperationPreconditionReader.observations(File(it)) }
        val trust = args.value(CliValueOption.TRUST)?.let { OperationPreconditionReader.trust(File(it)) }
        require(observations == null || trust != null) { "preconditions observations require caller-owned --trust." }
        val unit = compileAssessmentSource(args, contractRoot)
        // Compilation can be slow: an implicit current-time assessment starts after it.
        val evaluatedAt = args.value(CliValueOption.EVALUATED_AT)?.let(Instant::parse) ?: Instant.now()
        val review = OperationPreconditionReviewer.evaluate(unit, requirements, observations, trust, evaluatedAt)
        val values = linkedMapOf<String, Any>("operation-precondition-review.json" to review,
            "workflow-execution-plan-set.json" to unit.workflowPlanSet)
        if (!unit.workflowPlanSet.multiWorkflow) values["execution-plan.json"] = unit.executionPlan
        output.section("OPERATION PRECONDITION REVIEW", review)
        val directory = args.value(CliValueOption.OUT)?.let(::File)
        directory?.let { CliArtifactOutput.write(it, values) }
        return PreconditionCliResult(review, output.snapshot(), values.keys.map {
            CliArtifact(it, if (it == "operation-precondition-review.json") CliArtifactRole.DIAGNOSTIC_EVIDENCE else CliArtifactRole.TARGET_NEUTRAL_PLANNING,
                directory != null)
        })
    }
}
