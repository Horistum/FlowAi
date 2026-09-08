package org.flowlang.conformance

import java.io.File
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentSchedule
import org.flowlang.intent.IntentScheduleKind
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentTrigger
import org.flowlang.intent.IntentTriggerType
import org.flowlang.intent.IntentValue
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException

enum class DataOrchestrationRequirement {
    TASK_DEPENDENCY_ORDER,
    SCHEDULED_CADENCE,
    PRODUCED_DATA_IDENTITY,
    DATA_ASSET_DEPENDENCY,
    ASSET_TRIGGER_CONDITION,
    RUN_DATA_INTERVAL,
    PARTITION_BACKFILL_SELECTION,
    CONDITIONAL_BRANCHING
}

data class DataOrchestrationFactDeclaration(
    val id: String,
    val observationRef: String,
    val requirement: DataOrchestrationRequirement,
    val values: Map<String, String>
)

data class DataOrchestrationCaseAssessment(
    val kind: String,
    val version: String,
    val caseId: String,
    val facts: List<DataOrchestrationFactDeclaration>
)

data class DataOrchestrationFinding(
    val caseId: String,
    val factId: String,
    val observationRef: String,
    val requirement: DataOrchestrationRequirement,
    val outcome: ExternalFalsificationOutcome,
    val reason: String
)

data class DataOrchestrationFalsificationReport(
    val caseCount: Int,
    val distinctRepositoryCount: Int,
    val findings: List<DataOrchestrationFinding>
) {
    val representableCount: Int get() = findings.count { it.outcome == ExternalFalsificationOutcome.REPRESENTABLE }
    val modelGapCount: Int get() = findings.count { it.outcome == ExternalFalsificationOutcome.MODEL_GAP }
}

/**
 * EF-07 evaluator for externally grounded data-orchestration facts.
 *
 * Reviewed evidence is translated into bounded target-neutral requirements and every
 * representability decision crosses IntentCapabilityValidator and CanonicalIntentMeaning.
 * A field surviving serialization is not sufficient: the evaluator also requires semantic
 * distinguishability and rejects opaque/provider-specific lookalikes where applicable.
 */
class DataOrchestrationFalsification(private val rootDir: File = File(".")) {
    fun evaluate(): DataOrchestrationFalsificationReport {
        val corpus = ExternalCorpusLoader(rootDir).load()
        require(corpus.manifest.status == ExternalCorpusStatus.EVIDENCE_ACTIVE) {
            "EF-07 requires an EVIDENCE_ACTIVE external corpus."
        }
        val cases = corpus.cases.filter { it.definition.domain == DOMAIN }
        require(cases.size >= MIN_CASES) { "EF-07 requires at least $MIN_CASES data-orchestration evidence cases." }
        val repositoryCount = cases.map { it.definition.provenance.repository }.distinct().size
        require(repositoryCount >= MIN_REPOSITORIES) {
            "EF-07 requires evidence from at least $MIN_REPOSITORIES independent repositories."
        }

        val findings = cases.flatMap { loadedCase ->
            loadAssessment(loadedCase).facts.map { fact -> classify(loadedCase.definition.id, fact) }
        }
        require(findings.isNotEmpty()) { "EF-07 must classify at least one semantic fact." }
        val missing = REQUIRED_REQUIREMENTS - findings.map { it.requirement }.toSet()
        require(missing.isEmpty()) {
            "EF-07 corpus does not exercise required semantic shapes: ${missing.sortedBy { it.name }.joinToString()}."
        }
        return DataOrchestrationFalsificationReport(cases.size, repositoryCount, findings)
    }

    private fun loadAssessment(loadedCase: LoadedExternalCorpusCase): DataOrchestrationCaseAssessment {
        val file = File(loadedCase.directory, ASSESSMENT_FILE)
        require(file.isFile) { "Missing EF-07 assessment for case '${loadedCase.definition.id}': ${file.path}" }
        val assessment = readStrict(file, DataOrchestrationCaseAssessment::class.java)
        require(assessment.kind == KIND && assessment.version == VERSION) {
            "EF-07 assessment for '${loadedCase.definition.id}' must use $KIND version $VERSION."
        }
        require(assessment.caseId == loadedCase.definition.id) {
            "EF-07 assessment caseId '${assessment.caseId}' does not match '${loadedCase.definition.id}'."
        }
        require(assessment.facts.isNotEmpty()) { "EF-07 assessment for '${assessment.caseId}' must declare semantic facts." }
        requireUnique(assessment.caseId, "fact", assessment.facts.map { it.id })
        requireUnique(assessment.caseId, "observation reference", assessment.facts.map { it.observationRef })

        val observations = loadedCase.definition.expectedSemanticObservations.associateBy { it.id }
        assessment.facts.forEach { fact ->
            require(ID_PATTERN.matches(fact.id)) {
                "EF-07 case '${assessment.caseId}' contains invalid fact id '${fact.id}'."
            }
            val observation = observations[fact.observationRef]
                ?: throw IllegalArgumentException(
                    "EF-07 case '${assessment.caseId}' fact '${fact.id}' references unknown semantic observation '${fact.observationRef}'."
                )
            require(observation.expectation == ExternalSemanticExpectation.PRESERVE) {
                "EF-07 case '${assessment.caseId}' fact '${fact.id}' must reference a PRESERVE semantic observation."
            }
            validateValues(assessment.caseId, fact)
        }

        val preservation = loadedCase.definition.expectedSemanticObservations
            .filter { it.expectation == ExternalSemanticExpectation.PRESERVE }
            .map { it.id }
            .toSet()
        val covered = assessment.facts.map { it.observationRef }.toSet()
        require(covered == preservation) {
            "EF-07 case '${assessment.caseId}' must classify every PRESERVE semantic observation exactly once; expected=${preservation.sorted()} actual=${covered.sorted()}."
        }
        return assessment
    }

    private fun validateValues(caseId: String, fact: DataOrchestrationFactDeclaration) {
        require(fact.values.values.none(String::isBlank)) {
            "EF-07 case '$caseId' fact '${fact.id}' contains a blank semantic value."
        }
        val required = when (fact.requirement) {
            DataOrchestrationRequirement.TASK_DEPENDENCY_ORDER -> setOf(UPSTREAM, DOWNSTREAM)
            DataOrchestrationRequirement.SCHEDULED_CADENCE -> setOf(CADENCE)
            DataOrchestrationRequirement.PRODUCED_DATA_IDENTITY -> setOf(PRODUCER, DATA)
            DataOrchestrationRequirement.DATA_ASSET_DEPENDENCY ->
                setOf(UPSTREAM_STEP, UPSTREAM_DATA, DOWNSTREAM_STEP, DOWNSTREAM_DATA)
            DataOrchestrationRequirement.ASSET_TRIGGER_CONDITION -> setOf(ASSET_A, ASSET_B, CONDITION)
            DataOrchestrationRequirement.RUN_DATA_INTERVAL -> setOf(CADENCE, DATA_INTERVAL)
            DataOrchestrationRequirement.PARTITION_BACKFILL_SELECTION ->
                setOf(DATA, PARTITION_START, PARTITION_END)
            DataOrchestrationRequirement.CONDITIONAL_BRANCHING ->
                setOf(DECISION_STEP, CONDITION, TRUE_STEP, FALSE_STEP)
        }
        require(fact.values.keys.containsAll(required)) {
            "EF-07 case '$caseId' fact '${fact.id}' is missing required semantic values ${(required - fact.values.keys).sorted()}."
        }
        require(fact.values.keys.all { it in required }) {
            "EF-07 case '$caseId' fact '${fact.id}' contains unsupported semantic value keys ${(fact.values.keys - required).sorted()}."
        }
    }

    private fun classify(caseId: String, fact: DataOrchestrationFactDeclaration): DataOrchestrationFinding {
        val classified = when (fact.requirement) {
            DataOrchestrationRequirement.TASK_DEPENDENCY_ORDER -> taskDependencyOrder(fact.values)
            DataOrchestrationRequirement.SCHEDULED_CADENCE -> scheduledCadence(fact.values)
            DataOrchestrationRequirement.PRODUCED_DATA_IDENTITY -> producedDataIdentity(fact.values)
            DataOrchestrationRequirement.DATA_ASSET_DEPENDENCY -> dataAssetDependency(fact.values)
            DataOrchestrationRequirement.ASSET_TRIGGER_CONDITION -> assetTriggerCondition(fact.values)
            DataOrchestrationRequirement.RUN_DATA_INTERVAL -> runDataInterval(fact.values)
            DataOrchestrationRequirement.PARTITION_BACKFILL_SELECTION -> partitionBackfillSelection(fact.values)
            DataOrchestrationRequirement.CONDITIONAL_BRANCHING -> conditionalBranching(fact.values)
        }
        return DataOrchestrationFinding(
            caseId = caseId,
            factId = fact.id,
            observationRef = fact.observationRef,
            requirement = fact.requirement,
            outcome = classified.first,
            reason = classified.second
        )
    }

    private fun taskDependencyOrder(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val upstream = values.getValue(UPSTREAM)
        val downstream = values.getValue(DOWNSTREAM)
        val observed = validate(listOf(transformStep(upstream), transformStep(downstream, requires = listOf(upstream))))
        val alternate = validate(listOf(transformStep(upstream), transformStep(downstream)))
        val observedDownstream = observed.meaning.workflows.single().steps.single { it.id == downstream }
        val alternateDownstream = alternate.meaning.workflows.single().steps.single { it.id == downstream }
        return outcome(
            observed.valid && alternate.valid &&
                observedDownstream.requires == listOf(upstream) &&
                alternateDownstream.requires.isEmpty() &&
                observedDownstream.requires != alternateDownstream.requires,
            "Canonical step requirements preserve the authored upstream-to-downstream dependency as a distinguishable graph edge.",
            "The authored task dependency is not preserved as distinguishable canonical graph meaning."
        )
    }

    private fun scheduledCadence(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val cadence = values.getValue(CADENCE)
        val alternateCadence = if (cadence == ALTERNATE_CADENCE) SECOND_ALTERNATE_CADENCE else ALTERNATE_CADENCE
        val observed = validate(listOf(transformStep("scheduled-transform")), listOf(scheduleTrigger(cadence)))
        val alternate = validate(listOf(transformStep("scheduled-transform")), listOf(scheduleTrigger(alternateCadence)))
        val observedSchedule = observed.meaning.triggers.singleOrNull()?.schedule
        val alternateSchedule = alternate.meaning.triggers.singleOrNull()?.schedule
        return outcome(
            observed.valid && alternate.valid &&
                observedSchedule?.kind == IntentScheduleKind.INTERVAL && observedSchedule.expression == cadence &&
                alternateSchedule?.kind == IntentScheduleKind.INTERVAL && alternateSchedule.expression == alternateCadence &&
                observed.meaning.triggers != alternate.meaning.triggers,
            "Canonical interval-trigger meaning preserves the authored cadence independently from Airflow schedule aliases.",
            "The authored orchestration cadence is not preserved as distinguishable canonical schedule meaning."
        )
    }

    private fun producedDataIdentity(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val producer = values.getValue(PRODUCER)
        val data = values.getValue(DATA)
        val alternateData = "$data-alternate"
        val observed = validate(listOf(transformStep(producer, produces = listOf(data))))
        val alternate = validate(listOf(transformStep(producer, produces = listOf(alternateData))))
        val observedStep = observed.meaning.workflows.single().steps.single()
        val alternateStep = alternate.meaning.workflows.single().steps.single()
        return outcome(
            observed.valid && alternate.valid &&
                observedStep.produces == listOf(data) &&
                alternateStep.produces == listOf(alternateData) &&
                observedStep.produces != alternateStep.produces,
            "Canonical step outputs preserve the authored produced-data identity without storage URI syntax.",
            "The authored produced-data identity is not preserved as distinguishable canonical output meaning."
        )
    }

    private fun dataAssetDependency(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val upstreamStep = values.getValue(UPSTREAM_STEP)
        val upstreamData = values.getValue(UPSTREAM_DATA)
        val downstreamStep = values.getValue(DOWNSTREAM_STEP)
        val downstreamData = values.getValue(DOWNSTREAM_DATA)
        val report = validate(
            listOf(
                transformStep(upstreamStep, produces = listOf(upstreamData)),
                transformStep(downstreamStep, requires = listOf(upstreamStep), produces = listOf(downstreamData))
            )
        )
        val steps = report.meaning.workflows.single().steps.associateBy { it.id }
        val structuralPiecesPreserved = report.valid &&
            steps.getValue(upstreamStep).produces == listOf(upstreamData) &&
            steps.getValue(downstreamStep).requires == listOf(upstreamStep) &&
            steps.getValue(downstreamStep).produces == listOf(downstreamData)
        return if (!structuralPiecesPreserved) {
            ExternalFalsificationOutcome.MODEL_GAP to
                "The reviewed producer output and dependency edge are not preserved by current canonical meaning."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "Canonical meaning preserves the producer output identity and a step dependency, but it has no semantic edge tying the downstream step to that specific produced data. A control-only dependency and authored data lineage therefore collapse to the same canonical shape."
        }
    }

    private fun assetTriggerCondition(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val baseParams = mapOf(
            ASSET_A to IntentString(values.getValue(ASSET_A)),
            ASSET_B to IntentString(values.getValue(ASSET_B)),
            CONDITION to IntentString(values.getValue(CONDITION))
        )
        val alternateParams = baseParams + (CONDITION to IntentString(alternateCondition(values.getValue(CONDITION))))
        val observed = validate(listOf(transformStep("asset-consumer")), listOf(eventTrigger("asset-condition", baseParams)))
        val alternate = validate(listOf(transformStep("asset-consumer")), listOf(eventTrigger("asset-condition", alternateParams)))
        val smuggled = validate(
            listOf(transformStep("asset-consumer")),
            listOf(eventTrigger("asset-condition", baseParams + (PROVIDER_TRIGGER_PARAM to IntentString("provider-expression"))))
        )
        val observedTrigger = observed.meaning.triggers.singleOrNull()
        val alternateTrigger = alternate.meaning.triggers.singleOrNull()
        return outcome(
            observed.valid && alternate.valid && observedTrigger != null && alternateTrigger != null &&
                observedTrigger.params == baseParams && alternateTrigger.params == alternateParams &&
                observedTrigger.params != alternateTrigger.params && !smuggled.valid,
            "Canonical trigger validation preserves typed asset identities and logical trigger condition while rejecting unrelated provider vocabulary.",
            "Trigger params are currently free-form: asset identities and ALL/ANY semantics can be echoed, but arbitrary provider-specific keys are equally accepted, so this is not typed canonical trigger meaning."
        )
    }

    private fun runDataInterval(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val cadence = values.getValue(CADENCE)
        val interval = values.getValue(DATA_INTERVAL)
        val observedParams = mapOf(DATA_INTERVAL to IntentString(interval))
        val alternateParams = mapOf(DATA_INTERVAL to IntentString("$interval-alternate"))
        val observed = validate(listOf(transformStep("interval-transform")), listOf(scheduleTrigger(cadence, observedParams)))
        val alternate = validate(listOf(transformStep("interval-transform")), listOf(scheduleTrigger(cadence, alternateParams)))
        val smuggled = validate(
            listOf(transformStep("interval-transform")),
            listOf(scheduleTrigger(cadence, observedParams + (PROVIDER_TRIGGER_PARAM to IntentString("logical-date"))))
        )
        val observedTrigger = observed.meaning.triggers.singleOrNull()
        val alternateTrigger = alternate.meaning.triggers.singleOrNull()
        return outcome(
            observed.valid && alternate.valid && observedTrigger != null && alternateTrigger != null &&
                observedTrigger.schedule?.expression == cadence &&
                observedTrigger.params == observedParams && alternateTrigger.params == alternateParams &&
                observedTrigger.params != alternateTrigger.params && !smuggled.valid,
            "Canonical trigger meaning distinguishes a validated per-run data interval from cadence and rejects provider-only trigger metadata.",
            "The canonical schedule preserves cadence but trigger params are free-form, so a run data interval is not preserved as validated meaning distinct from schedule time."
        )
    }

    private fun partitionBackfillSelection(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val data = values.getValue(DATA)
        val authored = mapOf(
            PARTITION_START_PARAM to values.getValue(PARTITION_START),
            PARTITION_END_PARAM to values.getValue(PARTITION_END)
        )
        val alternate = authored + (PARTITION_END_PARAM to "${values.getValue(PARTITION_END)}-alternate")
        val observed = stepParameterProjection("backfill-data", data, authored)
        val alternateProjection = stepParameterProjection("backfill-data", data, alternate)
        return outcome(
            observed.valid && alternateProjection.valid &&
                parametersPreserved(observed, authored) &&
                parametersPreserved(alternateProjection, alternate) &&
                observed.params != alternateProjection.params,
            "Canonical data-orchestration meaning preserves the selected historical partition range as typed, distinguishable semantics.",
            "DATA_TRANSFORM has no typed partition-subset/backfill selection; generic batches, reruns or repeated schedules do not preserve which historical partitions were requested."
        )
    }

    private fun conditionalBranching(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val decisionStep = values.getValue(DECISION_STEP)
        val condition = values.getValue(CONDITION)
        val trueStep = values.getValue(TRUE_STEP)
        val falseStep = values.getValue(FALSE_STEP)
        val proposed = mapOf(
            BRANCH_CONDITION_PARAM to condition,
            TRUE_STEP_PARAM to trueStep,
            FALSE_STEP_PARAM to falseStep
        )
        val probe = validate(
            listOf(
                IntentStep(
                    id = decisionStep,
                    capability = StandardCapability.POLICY_CHECK,
                    params = mapOf("policy" to IntentString("data-quality")) +
                        proposed.mapValues { (_, value) -> IntentString(value) }
                ),
                transformStep(trueStep, requires = listOf(decisionStep)),
                transformStep(falseStep, requires = listOf(decisionStep))
            )
        )
        val parameterErrors = probe.issues.filter { it.level == "error" && it.code in SEMANTIC_PARAMETER_ERROR_CODES }
        val staticGraph = probe.meaning.workflows.single().steps.associateBy { it.id }
        val bothPathsStatic = staticGraph[trueStep]?.requires == listOf(decisionStep) &&
            staticGraph[falseStep]?.requires == listOf(decisionStep)
        val reason = when {
            parameterErrors.isNotEmpty() ->
                "Current contracts reject typed branch-condition/path parameters, and static requires edges only state that both downstream paths depend on the decision step."
            bothPathsStatic ->
                "Branch-like parameters are accepted but canonical graph meaning still contains both static downstream edges and does not encode mutually exclusive path selection."
            else ->
                "Canonical meaning does not preserve the authored conditional path selection as typed control-flow semantics."
        }
        return ExternalFalsificationOutcome.MODEL_GAP to reason
    }

    private fun stepParameterProjection(stepId: String, data: String, values: Map<String, String>): StepParameterProjection {
        val report = validate(
            listOf(
                IntentStep(
                    id = stepId,
                    capability = StandardCapability.DATA_TRANSFORM,
                    produces = listOf(data),
                    params = values.mapValues { (_, value) -> IntentString(value) }
                )
            )
        )
        val parameterErrors = report.issues.filter { it.level == "error" && it.code in SEMANTIC_PARAMETER_ERROR_CODES }
        return StepParameterProjection(
            valid = report.valid,
            semanticParametersAccepted = parameterErrors.isEmpty(),
            params = report.meaning.workflows.single().steps.single().params
        )
    }

    private fun validate(steps: List<IntentStep>, triggers: List<IntentTrigger> = emptyList()) =
        IntentCapabilityValidator().validate(
            IntentDocument(
                name = "ef07-data-orchestration",
                triggers = triggers,
                workflows = listOf(
                    IntentWorkflow(
                        name = "main",
                        kind = IntentWorkflowKind.DATA_PIPELINE,
                        steps = steps
                    )
                )
            )
        )

    private fun transformStep(
        id: String,
        requires: List<String> = emptyList(),
        produces: List<String> = emptyList()
    ) = IntentStep(
        id = id,
        capability = StandardCapability.DATA_TRANSFORM,
        requires = requires,
        produces = produces
    )

    private fun scheduleTrigger(cadence: String, params: Map<String, IntentValue> = emptyMap()) =
        IntentTrigger(
            id = "schedule",
            type = IntentTriggerType.SCHEDULE,
            workflows = listOf("main"),
            schedule = IntentSchedule(IntentScheduleKind.INTERVAL, cadence),
            params = params
        )

    private fun eventTrigger(id: String, params: Map<String, IntentValue>) = IntentTrigger(
        id = id,
        type = IntentTriggerType.EVENT,
        workflows = listOf("main"),
        event = "data-asset-update",
        params = params
    )

    private fun parametersPreserved(projection: StepParameterProjection, expected: Map<String, String>): Boolean =
        projection.semanticParametersAccepted &&
            expected.all { (name, value) -> projection.params[name] == IntentString(value) }

    private fun outcome(preserved: Boolean, success: String, gap: String) =
        if (preserved) ExternalFalsificationOutcome.REPRESENTABLE to success
        else ExternalFalsificationOutcome.MODEL_GAP to gap

    private fun alternateCondition(condition: String): String =
        if (condition.equals("all", ignoreCase = true)) "any" else "all"

    private fun requireUnique(caseId: String, label: String, values: List<String>) {
        require(values.distinct().size == values.size) {
            "EF-07 case '$caseId' contains duplicate $label values."
        }
    }

    private fun <T> readStrict(file: File, type: Class<T>): T = try {
        FlowYaml.readStrict(file, type)
    } catch (error: FlowYamlException) {
        throw IllegalArgumentException(
            "Invalid EF-07 assessment '${file.path}': ${error.message ?: error.javaClass.simpleName}",
            error
        )
    }

    private data class StepParameterProjection(
        val valid: Boolean,
        val semanticParametersAccepted: Boolean,
        val params: Map<String, IntentValue>
    )

    companion object {
        const val DOMAIN = "data-orchestration"
        const val KIND = "FlowDataOrchestrationFalsification"
        const val VERSION = "1.0"
        const val ASSESSMENT_FILE = "data-orchestration.yaml"
        const val MIN_CASES = 8
        const val MIN_REPOSITORIES = 2

        private const val UPSTREAM = "upstream"
        private const val DOWNSTREAM = "downstream"
        private const val CADENCE = "cadence"
        private const val PRODUCER = "producer"
        private const val DATA = "data"
        private const val UPSTREAM_STEP = "upstreamStep"
        private const val UPSTREAM_DATA = "upstreamData"
        private const val DOWNSTREAM_STEP = "downstreamStep"
        private const val DOWNSTREAM_DATA = "downstreamData"
        private const val ASSET_A = "assetA"
        private const val ASSET_B = "assetB"
        private const val CONDITION = "condition"
        private const val DATA_INTERVAL = "dataInterval"
        private const val PARTITION_START = "partitionStart"
        private const val PARTITION_END = "partitionEnd"
        private const val DECISION_STEP = "decisionStep"
        private const val TRUE_STEP = "trueStep"
        private const val FALSE_STEP = "falseStep"

        private const val PARTITION_START_PARAM = "partitionStart"
        private const val PARTITION_END_PARAM = "partitionEnd"
        private const val BRANCH_CONDITION_PARAM = "branchCondition"
        private const val TRUE_STEP_PARAM = "trueStep"
        private const val FALSE_STEP_PARAM = "falseStep"
        private const val PROVIDER_TRIGGER_PARAM = "airflowDatasetExpression"
        private const val ALTERNATE_CADENCE = "P2D"
        private const val SECOND_ALTERNATE_CADENCE = "P3D"

        private val REQUIRED_REQUIREMENTS = DataOrchestrationRequirement.values().toSet()
        private val SEMANTIC_PARAMETER_ERROR_CODES = setOf("UNKNOWN_STEP_PARAM", "MISSING_REQUIRED_STEP_PARAM")
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}
