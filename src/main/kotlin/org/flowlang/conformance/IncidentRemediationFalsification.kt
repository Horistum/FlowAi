package org.flowlang.conformance

import org.flowlang.modules.ModuleRegistry

import java.io.File
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.effects.SemanticEffect
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentValue
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException

enum class IncidentRemediationRequirement {
    INCIDENT_CONTEXT,
    TARGETED_RUNBOOK_REMEDIATION,
    POST_REMEDIATION_VERIFICATION,
    OPERATOR_CONFIRMATION,
    INCIDENT_NOTIFICATION,
    FAILURE_CONDITIONED_HUMAN_ESCALATION
}

data class IncidentRemediationFactDeclaration(
    val id: String,
    val observationRef: String,
    val requirement: IncidentRemediationRequirement,
    val values: Map<String, String>
)

data class IncidentRemediationCaseAssessment(
    val kind: String,
    val version: String,
    val caseId: String,
    val facts: List<IncidentRemediationFactDeclaration>
)

data class IncidentRemediationFinding(
    val caseId: String,
    val factId: String,
    val observationRef: String,
    val requirement: IncidentRemediationRequirement,
    val outcome: ExternalFalsificationOutcome,
    val reason: String
)

data class IncidentRemediationFalsificationReport(
    val caseCount: Int,
    val distinctRepositoryCount: Int,
    val findings: List<IncidentRemediationFinding>
) {
    val representableCount: Int get() = findings.count { it.outcome == ExternalFalsificationOutcome.REPRESENTABLE }
    val modelGapCount: Int get() = findings.count { it.outcome == ExternalFalsificationOutcome.MODEL_GAP }
}

/**
 * EF-04 evaluator for externally grounded incident-remediation facts.
 *
 * Reviewed case assessments are the only boundary that translates product syntax into typed,
 * target-neutral requirements. Classification then crosses the production intent validator and
 * canonical meaning boundary. Product names may survive as authored values, but they can never
 * create new Core vocabulary here. MODEL_GAP is evidence, not permission to weaken validation.
 */
class IncidentRemediationFalsification(private val rootDir: File = File(".")) {
    fun evaluate(): IncidentRemediationFalsificationReport {
        val corpus = ExternalCorpusLoader(rootDir).load()
        require(corpus.manifest.status == ExternalCorpusStatus.EVIDENCE_ACTIVE) {
            "EF-04 requires an EVIDENCE_ACTIVE external corpus."
        }
        val cases = corpus.cases.filter { it.definition.domain == DOMAIN }
        require(cases.size >= MIN_CASES) {
            "EF-04 requires at least $MIN_CASES incident-remediation evidence cases."
        }
        val repositoryCount = cases.map { it.definition.provenance.repository }.distinct().size
        require(repositoryCount >= MIN_REPOSITORIES) {
            "EF-04 requires evidence from at least $MIN_REPOSITORIES independent repositories."
        }

        val findings = cases.flatMap { loadedCase ->
            val assessment = loadAssessment(loadedCase)
            assessment.facts.map { fact -> classify(loadedCase.definition.id, fact) }
        }
        require(findings.isNotEmpty()) { "EF-04 must classify at least one semantic fact." }

        val coveredRequirements = findings.map { it.requirement }.toSet()
        val missingRequirements = REQUIRED_REQUIREMENTS - coveredRequirements
        require(missingRequirements.isEmpty()) {
            "EF-04 corpus does not exercise required semantic shapes: ${missingRequirements.sortedBy { it.name }.joinToString()}."
        }

        return IncidentRemediationFalsificationReport(cases.size, repositoryCount, findings)
    }

    private fun loadAssessment(loadedCase: LoadedExternalCorpusCase): IncidentRemediationCaseAssessment {
        val file = File(loadedCase.directory, ASSESSMENT_FILE)
        require(file.isFile) { "Missing EF-04 assessment for case '${loadedCase.definition.id}': ${file.path}" }
        val assessment = readStrict(file, IncidentRemediationCaseAssessment::class.java)
        require(assessment.kind == KIND && assessment.version == VERSION) {
            "EF-04 assessment for '${loadedCase.definition.id}' must use $KIND version $VERSION."
        }
        require(assessment.caseId == loadedCase.definition.id) {
            "EF-04 assessment caseId '${assessment.caseId}' does not match '${loadedCase.definition.id}'."
        }
        require(assessment.facts.isNotEmpty()) {
            "EF-04 assessment for '${assessment.caseId}' must declare semantic facts."
        }
        requireUnique(assessment.caseId, "fact", assessment.facts.map { it.id })
        requireUnique(assessment.caseId, "observation reference", assessment.facts.map { it.observationRef })

        val observations = loadedCase.definition.expectedSemanticObservations.associateBy { it.id }
        assessment.facts.forEach { fact ->
            require(ID_PATTERN.matches(fact.id)) {
                "EF-04 case '${assessment.caseId}' contains invalid fact id '${fact.id}'."
            }
            val observation = observations[fact.observationRef]
                ?: throw IllegalArgumentException(
                    "EF-04 case '${assessment.caseId}' fact '${fact.id}' references unknown semantic observation '${fact.observationRef}'."
                )
            require(observation.expectation == ExternalSemanticExpectation.PRESERVE) {
                "EF-04 case '${assessment.caseId}' fact '${fact.id}' must reference a PRESERVE semantic observation."
            }
            validateValues(assessment.caseId, fact)
        }

        val preservationObservations = loadedCase.definition.expectedSemanticObservations
            .filter { it.expectation == ExternalSemanticExpectation.PRESERVE }
            .map { it.id }
            .toSet()
        val covered = assessment.facts.map { it.observationRef }.toSet()
        require(covered == preservationObservations) {
            "EF-04 case '${assessment.caseId}' must classify every PRESERVE semantic observation exactly once; expected=${preservationObservations.sorted()} actual=${covered.sorted()}."
        }
        return assessment
    }

    private fun validateValues(caseId: String, fact: IncidentRemediationFactDeclaration) {
        require(fact.values.values.none(String::isBlank)) {
            "EF-04 case '$caseId' fact '${fact.id}' contains a blank semantic value."
        }

        val required: Set<String>
        val allowed: Set<String>
        when (fact.requirement) {
            IncidentRemediationRequirement.INCIDENT_CONTEXT -> {
                required = setOf(DESCRIPTION, SERVICE)
                allowed = required + setOf(SEVERITY, TARGET)
            }
            IncidentRemediationRequirement.TARGETED_RUNBOOK_REMEDIATION -> {
                required = setOf(DESCRIPTION, SERVICE)
                allowed = required + TARGET
            }
            IncidentRemediationRequirement.POST_REMEDIATION_VERIFICATION -> {
                required = setOf(SUBJECT, CRITERIA)
                allowed = required + TARGET
            }
            IncidentRemediationRequirement.OPERATOR_CONFIRMATION -> {
                required = setOf(MESSAGE)
                allowed = required
            }
            IncidentRemediationRequirement.INCIDENT_NOTIFICATION -> {
                required = setOf(BODY, CHANNEL)
                allowed = required + setOf(TO, SUBJECT, TARGET)
            }
            IncidentRemediationRequirement.FAILURE_CONDITIONED_HUMAN_ESCALATION -> {
                required = setOf(CONDITION, MESSAGE, CHANNEL, HANDOFF)
                allowed = required
            }
        }

        require(fact.values.keys.containsAll(required)) {
            "EF-04 case '$caseId' fact '${fact.id}' is missing required semantic values ${(required - fact.values.keys).sorted()}."
        }
        require(fact.values.keys.all { it in allowed }) {
            "EF-04 case '$caseId' fact '${fact.id}' contains unsupported semantic value keys ${(fact.values.keys - allowed).sorted()}."
        }
    }

    private fun classify(caseId: String, fact: IncidentRemediationFactDeclaration): IncidentRemediationFinding {
        val classification = when (fact.requirement) {
            IncidentRemediationRequirement.INCIDENT_CONTEXT -> incidentContextClassification(fact.values)
            IncidentRemediationRequirement.TARGETED_RUNBOOK_REMEDIATION -> runbookRemediationClassification(fact.values)
            IncidentRemediationRequirement.POST_REMEDIATION_VERIFICATION -> verificationClassification(fact.values)
            IncidentRemediationRequirement.OPERATOR_CONFIRMATION -> operatorConfirmationClassification(fact.values)
            IncidentRemediationRequirement.INCIDENT_NOTIFICATION -> notificationClassification(fact.values)
            IncidentRemediationRequirement.FAILURE_CONDITIONED_HUMAN_ESCALATION ->
                failureConditionedEscalationClassification(fact.values)
        }
        return IncidentRemediationFinding(
            caseId = caseId,
            factId = fact.id,
            observationRef = fact.observationRef,
            requirement = fact.requirement,
            outcome = classification.first,
            reason = classification.second
        )
    }

    private fun incidentContextClassification(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val projection = semanticProjection(StandardCapability.INCIDENT, IntentWorkflowKind.INCIDENT, values)
        val preserved = projection.valid &&
            parametersPreserved(projection, values) &&
            hasEffect(projection, EffectDomain.COMMUNICATION, EffectOperation.CREATE, "operations.incident")

        return if (preserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "INCIDENT preserves authored incident context and target identity through canonical intent meaning without importing monitoring-product vocabulary into Core."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "INCIDENT does not preserve the authored incident context through the public canonical intent contract."
        }
    }

    private fun runbookRemediationClassification(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val projection = semanticProjection(StandardCapability.RUNBOOK, IntentWorkflowKind.RUNBOOK, values)
        val preserved = projection.valid &&
            parametersPreserved(projection, values) &&
            hasEffect(projection, EffectDomain.INFRASTRUCTURE_STATE, EffectOperation.EXECUTE, "operations.runbook")

        return if (preserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "RUNBOOK preserves the authored remediation description, service and target independently from command or action spelling."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "RUNBOOK does not preserve the targeted remediation meaning through the public canonical intent contract."
        }
    }

    private fun verificationClassification(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val projection = semanticProjection(StandardCapability.VERIFY, IntentWorkflowKind.RUNBOOK, values)
        val preserved = projection.valid &&
            parametersPreserved(projection, values) &&
            hasEffect(projection, EffectDomain.INFRASTRUCTURE_STATE, EffectOperation.READ, "deployment.state")

        return if (preserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "VERIFY preserves the authored post-remediation subject and criterion as semantic parameters while retaining an explicit state-read effect."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "VERIFY does not preserve the authored post-remediation subject and criterion through canonical intent meaning."
        }
    }

    private fun operatorConfirmationClassification(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val projection = semanticProjection(StandardCapability.APPROVE, IntentWorkflowKind.RUNBOOK, values)
        val preserved = projection.valid && parametersPreserved(projection, values)

        return if (preserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "APPROVE preserves the explicit operator confirmation message as a control step distinct from NOTIFY."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "The current intent contract cannot preserve explicit operator confirmation independently from notification."
        }
    }

    private fun notificationClassification(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val projection = semanticProjection(StandardCapability.NOTIFY, IntentWorkflowKind.INCIDENT, values)
        val preserved = projection.valid &&
            parametersPreserved(projection, values) &&
            hasEffect(projection, EffectDomain.COMMUNICATION, EffectOperation.EMIT, "notification")

        return if (preserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "NOTIFY preserves the authored incident message, channel and recipient/target metadata as communication meaning."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "NOTIFY does not preserve the authored incident-notification meaning through the public canonical intent contract."
        }
    }

    private fun failureConditionedEscalationClassification(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val condition = values.getValue(CONDITION)
        val semanticValues = mapOf(
            BODY to values.getValue(MESSAGE),
            CHANNEL to values.getValue(CHANNEL),
            TO to values.getValue(HANDOFF),
            CONDITION_PARAM to condition
        )
        val observed = semanticProjection(StandardCapability.NOTIFY, IntentWorkflowKind.INCIDENT, semanticValues)
        if (!observed.semanticParametersAccepted) {
            return ExternalFalsificationOutcome.MODEL_GAP to
                "NOTIFY exposes no typed target-neutral failure condition; canonical intent validation rejects conditional human escalation rather than silently degrading it to an unconditional notification."
        }

        val alternate = semanticProjection(
            StandardCapability.NOTIFY,
            IntentWorkflowKind.INCIDENT,
            semanticValues + (CONDITION_PARAM to ALTERNATE_CONDITION)
        )
        val conditionPreserved = observed.valid &&
            alternate.valid &&
            parametersPreserved(observed, semanticValues) &&
            alternate.params[CONDITION_PARAM] == IntentString(ALTERNATE_CONDITION) &&
            observed.params != alternate.params &&
            hasEffect(observed, EffectDomain.COMMUNICATION, EffectOperation.EMIT, "notification")

        return if (conditionPreserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "NOTIFY preserves a typed failure condition and human handoff target as semantically distinguishable escalation meaning."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "Conditional escalation data is accepted without preserving the authored failure condition and human handoff as semantically distinguishable canonical meaning."
        }
    }

    private fun semanticProjection(
        capability: StandardCapability,
        workflowKind: IntentWorkflowKind,
        values: Map<String, String>
    ): SemanticProjection {
        val report = IntentCapabilityValidator(ModuleRegistry()).validate(
            IntentDocument(
                name = "ef04-${capability.name.lowercase()}",
                workflows = listOf(
                    IntentWorkflow(
                        name = "main",
                        kind = workflowKind,
                        steps = listOf(
                            IntentStep(
                                id = "probe",
                                capability = capability,
                                params = values.mapValues { (_, value) -> IntentString(value) }
                            )
                        )
                    )
                )
            )
        )
        val semanticParameterErrors = report.issues.filter { issue ->
            issue.level == "error" && issue.code in SEMANTIC_PARAMETER_ERROR_CODES
        }
        val canonicalStep = report.meaning.workflows.single().steps.single()
        return SemanticProjection(
            valid = report.valid,
            semanticParametersAccepted = semanticParameterErrors.isEmpty(),
            params = canonicalStep.params,
            effects = canonicalStep.effects
        )
    }

    private fun parametersPreserved(projection: SemanticProjection, expected: Map<String, String>): Boolean =
        projection.semanticParametersAccepted &&
            expected.all { (name, value) -> projection.params[name] == IntentString(value) }

    private fun hasEffect(
        projection: SemanticProjection,
        domain: EffectDomain,
        operation: EffectOperation,
        resource: String
    ): Boolean = projection.effects.any { effect ->
        effect.domain == domain && effect.operation == operation && effect.resource == resource
    }

    private fun requireUnique(caseId: String, label: String, values: List<String>) {
        require(values.distinct().size == values.size) {
            "EF-04 case '$caseId' contains duplicate $label values."
        }
    }

    private fun <T> readStrict(file: File, type: Class<T>): T = try {
        FlowYaml.readStrict(file, type)
    } catch (error: FlowYamlException) {
        throw IllegalArgumentException(
            "Invalid EF-04 assessment '${file.path}': ${error.message ?: error.javaClass.simpleName}",
            error
        )
    }

    private data class SemanticProjection(
        val valid: Boolean,
        val semanticParametersAccepted: Boolean,
        val params: Map<String, IntentValue>,
        val effects: List<SemanticEffect>
    )

    companion object {
        const val DOMAIN = "incident-remediation"
        const val KIND = "FlowIncidentRemediationFalsification"
        const val VERSION = "1.0"
        const val ASSESSMENT_FILE = "incident-remediation.yaml"
        const val MIN_CASES = 2
        const val MIN_REPOSITORIES = 2

        private const val DESCRIPTION = "description"
        private const val SERVICE = "service"
        private const val SEVERITY = "severity"
        private const val TARGET = "target"
        private const val SUBJECT = "subject"
        private const val CRITERIA = "criteria"
        private const val MESSAGE = "message"
        private const val BODY = "body"
        private const val CHANNEL = "channel"
        private const val TO = "to"
        private const val CONDITION = "condition"
        private const val HANDOFF = "handoff"
        private const val CONDITION_PARAM = "condition"
        private const val ALTERNATE_CONDITION = "alternate-remediation-failure"
        private val REQUIRED_REQUIREMENTS = IncidentRemediationRequirement.values().toSet()
        private val SEMANTIC_PARAMETER_ERROR_CODES = setOf("UNKNOWN_STEP_PARAM", "MISSING_REQUIRED_STEP_PARAM")
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}
