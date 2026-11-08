package org.flowlang.cli.honest

import java.io.File
import java.time.Instant
import org.flowlang.compiler.CompilationResult
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.ai.ExternalAiProposalReader
import org.flowlang.frontend.ai.ReviewedAiProposalFrontend
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.frontend.outcomes.*
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes

class OutcomeCliResult internal constructor(
    val review: OperationOutcomeReview,
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

/** Independent assessment; target selection and executable rendering are intentionally unavailable. */
internal object OutcomeCommand {
    fun execute(args: CliArguments, output: CliOutputCollector, contractRoot: File): CliExecutionResult {
        val kind = requireNotNull(args.value(CliValueOption.SOURCE_KIND)) { "outcomes requires --source-kind." }
        require(kind in setOf("intent", "proposal")) { "Unsupported outcome source kind." }
        val requirements = OperationOutcomeReader.requirements(File(requireNotNull(args.value(CliValueOption.REQUIREMENTS)) {
            "outcomes requires --requirements."
        }))
        val observations = args.value(CliValueOption.OBSERVATIONS)?.let { OperationOutcomeReader.observations(File(it)) }
        val trust = args.value(CliValueOption.TRUST)?.let { OperationOutcomeReader.trust(File(it)) }
        require(observations == null || trust != null) { "outcomes observations require caller-owned --trust." }
        val evaluatedAt = args.value(CliValueOption.EVALUATED_AT)?.let(Instant::parse) ?: Instant.now()
        val compiler = FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File(contractRoot, "modules")),
            StandardEnvironmentSafetyPolicyNotes.policy(contractRoot))
        val file = File(args.positionals.single())
        val compiled = when (kind) {
            "intent" -> IntentYamlFrontend(compiler).compile(file)
            else -> ReviewedAiProposalFrontend(compiler).compile(ExternalAiProposalReader.read(file).proposal)
        }
        if (compiled !is CompilationResult.Accepted) throw CliTypedFailure(CliDiagnosticCode.INTEGRITY_BLOCKED,
            "Outcome assessment requires a source accepted by the compiler.")
        val review = OperationOutcomeReviewer.evaluate(compiled.unit, requirements, observations, trust, evaluatedAt)
        val values = linkedMapOf<String, Any>("operation-outcome-review.json" to review,
            "workflow-execution-plan-set.json" to compiled.unit.workflowPlanSet)
        if (!compiled.unit.workflowPlanSet.multiWorkflow) values["execution-plan.json"] = compiled.unit.executionPlan
        output.section("OPERATION OUTCOME REVIEW", review)
        val directory = args.value(CliValueOption.OUT)?.let(::File)
        directory?.let { CliArtifactOutput.write(it, values) }
        return OutcomeCliResult(review, output.snapshot(), values.keys.map {
            CliArtifact(it, if (it == "operation-outcome-review.json") CliArtifactRole.DIAGNOSTIC_EVIDENCE else CliArtifactRole.TARGET_NEUTRAL_PLANNING,
                directory != null)
        })
    }
}
