package org.flowlang.conformance

import java.io.File
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.effects.SemanticEffect
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

enum class SecretRotationRequirement {
    NAMED_SECRET_ROTATION,
    ROTATION_CADENCE,
    IMMEDIATE_VS_SCHEDULED_ROTATION,
    ROTATION_WINDOW,
    AUTOMATED_ROTATION_ENABLEMENT,
    PRIOR_CREDENTIAL_RETIREMENT_POLICY
}

data class SecretRotationFactDeclaration(
    val id: String,
    val observationRef: String,
    val requirement: SecretRotationRequirement,
    val values: Map<String, String>
)

data class SecretRotationCaseAssessment(
    val kind: String,
    val version: String,
    val caseId: String,
    val facts: List<SecretRotationFactDeclaration>
)

data class SecretRotationFinding(
    val caseId: String,
    val factId: String,
    val observationRef: String,
    val requirement: SecretRotationRequirement,
    val outcome: ExternalFalsificationOutcome,
    val reason: String
)

data class SecretRotationFalsificationReport(
    val caseCount: Int,
    val distinctRepositoryCount: Int,
    val findings: List<SecretRotationFinding>
) {
    val representableCount: Int get() = findings.count { it.outcome == ExternalFalsificationOutcome.REPRESENTABLE }
    val modelGapCount: Int get() = findings.count { it.outcome == ExternalFalsificationOutcome.MODEL_GAP }
}

/**
 * EF-06 evaluator for externally grounded secret-rotation lifecycle facts.
 *
 * Reviewed case assessments translate product evidence into bounded target-neutral requirements.
 * Classification then crosses the public intent validator and canonical meaning boundary. Product
 * API shapes remain evidence only; a MODEL_GAP is never permission to weaken Core validation.
 */
class SecretRotationFalsification(private val rootDir: File = File(".")) {
    fun evaluate(): SecretRotationFalsificationReport {
        val corpus = ExternalCorpusLoader(rootDir).load()
        require(corpus.manifest.status == ExternalCorpusStatus.EVIDENCE_ACTIVE) {
            "EF-06 requires an EVIDENCE_ACTIVE external corpus."
        }
        val cases = corpus.cases.filter { it.definition.domain == DOMAIN }
        require(cases.size >= MIN_CASES) { "EF-06 requires at least $MIN_CASES secret-rotation evidence cases." }
        val repositoryCount = cases.map { it.definition.provenance.repository }.distinct().size
        require(repositoryCount >= MIN_REPOSITORIES) {
            "EF-06 requires evidence from at least $MIN_REPOSITORIES independent repositories."
        }

        val findings = cases.flatMap { loadedCase ->
            val assessment = loadAssessment(loadedCase)
            assessment.facts.map { fact -> classify(loadedCase.definition.id, fact) }
        }
        require(findings.isNotEmpty()) { "EF-06 must classify at least one semantic fact." }
        val missing = REQUIRED_REQUIREMENTS - findings.map { it.requirement }.toSet()
        require(missing.isEmpty()) {
            "EF-06 corpus does not exercise required semantic shapes: ${missing.sortedBy { it.name }.joinToString()}."
        }
        return SecretRotationFalsificationReport(cases.size, repositoryCount, findings)
    }

    private fun loadAssessment(loadedCase: LoadedExternalCorpusCase): SecretRotationCaseAssessment {
        val file = File(loadedCase.directory, ASSESSMENT_FILE)
        require(file.isFile) { "Missing EF-06 assessment for case '${loadedCase.definition.id}': ${file.path}" }
        val assessment = readStrict(file, SecretRotationCaseAssessment::class.java)
        require(assessment.kind == KIND && assessment.version == VERSION) {
            "EF-06 assessment for '${loadedCase.definition.id}' must use $KIND version $VERSION."
        }
        require(assessment.caseId == loadedCase.definition.id) {
            "EF-06 assessment caseId '${assessment.caseId}' does not match '${loadedCase.definition.id}'."
        }
        require(assessment.facts.isNotEmpty()) { "EF-06 assessment for '${assessment.caseId}' must declare semantic facts." }
        requireUnique(assessment.caseId, "fact", assessment.facts.map { it.id })
        requireUnique(assessment.caseId, "observation reference", assessment.facts.map { it.observationRef })

        val observations = loadedCase.definition.expectedSemanticObservations.associateBy { it.id }
        assessment.facts.forEach { fact ->
            require(ID_PATTERN.matches(fact.id)) { "EF-06 case '${assessment.caseId}' contains invalid fact id '${fact.id}'." }
            val observation = observations[fact.observationRef]
                ?: throw IllegalArgumentException(
                    "EF-06 case '${assessment.caseId}' fact '${fact.id}' references unknown semantic observation '${fact.observationRef}'."
                )
            require(observation.expectation == ExternalSemanticExpectation.PRESERVE) {
                "EF-06 case '${assessment.caseId}' fact '${fact.id}' must reference a PRESERVE semantic observation."
            }
            validateValues(assessment.caseId, fact)
        }

        val preservation = loadedCase.definition.expectedSemanticObservations
            .filter { it.expectation == ExternalSemanticExpectation.PRESERVE }
            .map { it.id }
            .toSet()
        val covered = assessment.facts.map { it.observationRef }.toSet()
        require(covered == preservation) {
            "EF-06 case '${assessment.caseId}' must classify every PRESERVE semantic observation exactly once; expected=${preservation.sorted()} actual=${covered.sorted()}."
        }
        return assessment
    }

    private fun validateValues(caseId: String, fact: SecretRotationFactDeclaration) {
        require(fact.values.values.none(String::isBlank)) {
            "EF-06 case '$caseId' fact '${fact.id}' contains a blank semantic value."
        }
        val required = when (fact.requirement) {
            SecretRotationRequirement.NAMED_SECRET_ROTATION -> setOf(SUBJECT)
            SecretRotationRequirement.ROTATION_CADENCE -> setOf(SUBJECT, CADENCE)
            SecretRotationRequirement.IMMEDIATE_VS_SCHEDULED_ROTATION -> setOf(SUBJECT)
            SecretRotationRequirement.ROTATION_WINDOW -> setOf(SUBJECT, WINDOW)
            SecretRotationRequirement.AUTOMATED_ROTATION_ENABLEMENT -> setOf(SUBJECT, ENABLED)
            SecretRotationRequirement.PRIOR_CREDENTIAL_RETIREMENT_POLICY -> setOf(SUBJECT, POLICY)
        }
        require(fact.values.keys.containsAll(required)) {
            "EF-06 case '$caseId' fact '${fact.id}' is missing required semantic values ${(required - fact.values.keys).sorted()}."
        }
        require(fact.values.keys.all { it in required }) {
            "EF-06 case '$caseId' fact '${fact.id}' contains unsupported semantic value keys ${(fact.values.keys - required).sorted()}."
        }
    }

    private fun classify(caseId: String, fact: SecretRotationFactDeclaration): SecretRotationFinding {
        val classified = when (fact.requirement) {
            SecretRotationRequirement.NAMED_SECRET_ROTATION -> namedRotation(fact.values)
            SecretRotationRequirement.ROTATION_CADENCE -> rotationCadence(fact.values)
            SecretRotationRequirement.IMMEDIATE_VS_SCHEDULED_ROTATION -> immediateVsScheduled(fact.values)
            SecretRotationRequirement.ROTATION_WINDOW -> rotationWindow(fact.values)
            SecretRotationRequirement.AUTOMATED_ROTATION_ENABLEMENT -> automationEnablement(fact.values)
            SecretRotationRequirement.PRIOR_CREDENTIAL_RETIREMENT_POLICY -> priorCredentialPolicy(fact.values)
        }
        return SecretRotationFinding(caseId, fact.id, fact.observationRef, fact.requirement, classified.first, classified.second)
    }

    private fun namedRotation(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val projection = semanticProjection(mapOf(SUBJECT to values.getValue(SUBJECT)))
        val preserved = projection.valid &&
            projection.params[SUBJECT] == IntentString(values.getValue(SUBJECT)) &&
            hasSecretUpdate(projection)
        return outcome(
            preserved,
            "SECRET_ROTATE preserves the authored secret identity and secret-update effect without provider-specific naming syntax.",
            "SECRET_ROTATE does not preserve the authored named-secret rotation through canonical intent meaning."
        )
    }

    private fun rotationCadence(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val cadence = values.getValue(CADENCE)
        val subject = values.getValue(SUBJECT)
        val observed = semanticProjection(mapOf(SUBJECT to subject), listOf(scheduleTrigger("rotation-cadence", cadence)))
        val alternateCadence = if (cadence == ALTERNATE_CADENCE) SECOND_ALTERNATE_CADENCE else ALTERNATE_CADENCE
        val alternate = semanticProjection(mapOf(SUBJECT to subject), listOf(scheduleTrigger("rotation-cadence", alternateCadence)))
        val observedSchedule = observed.triggers.singleOrNull()?.schedule
        val alternateSchedule = alternate.triggers.singleOrNull()?.schedule
        val preserved = observed.valid && alternate.valid && hasSecretUpdate(observed) && hasSecretUpdate(alternate) &&
            observedSchedule?.kind == IntentScheduleKind.INTERVAL && observedSchedule.expression == cadence &&
            alternateSchedule?.kind == IntentScheduleKind.INTERVAL && alternateSchedule.expression == alternateCadence &&
            observed.triggers != alternate.triggers
        return outcome(
            preserved,
            "Canonical schedule-trigger meaning preserves the authored rotation cadence independently from provider scheduling syntax.",
            "The authored recurring rotation cadence is not preserved as semantically distinguishable canonical trigger meaning."
        )
    }

    private fun immediateVsScheduled(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val subject = values.getValue(SUBJECT)
        val observedValues = mapOf(SUBJECT to subject, ROTATION_MODE_PARAM to WAIT_FOR_WINDOW)
        val observed = semanticProjection(observedValues)
        if (!observed.semanticParametersAccepted) {
            return ExternalFalsificationOutcome.MODEL_GAP to
                "SECRET_ROTATE has no typed rotation-execution mode; a workflow schedule is not equivalent to invoking rotation now while deferring completion to the next configured window."
        }
        val alternateValues = mapOf(SUBJECT to subject, ROTATION_MODE_PARAM to ROTATE_NOW)
        val alternate = semanticProjection(alternateValues)
        val preserved = observed.valid && alternate.valid &&
            parametersPreserved(observed, observedValues) && parametersPreserved(alternate, alternateValues) &&
            observed.params != alternate.params && hasSecretUpdate(observed) && hasSecretUpdate(alternate)
        return outcome(
            preserved,
            "SECRET_ROTATE preserves immediate-versus-next-window execution as typed, distinguishable canonical meaning.",
            "Rotation execution mode is accepted without preserving the authored immediate-versus-next-window distinction."
        )
    }

    private fun rotationWindow(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val subject = values.getValue(SUBJECT)
        val observedValues = mapOf(SUBJECT to subject, ROTATION_WINDOW_PARAM to values.getValue(WINDOW))
        val observed = semanticProjection(observedValues)
        if (!observed.semanticParametersAccepted) {
            return ExternalFalsificationOutcome.MODEL_GAP to
                "SECRET_ROTATE exposes no typed bounded rotation window; cadence, retry timeout and a future trigger do not preserve the same execution constraint."
        }
        val alternateValues = mapOf(SUBJECT to subject, ROTATION_WINDOW_PARAM to ALTERNATE_WINDOW)
        val alternate = semanticProjection(alternateValues)
        val preserved = observed.valid && alternate.valid &&
            parametersPreserved(observed, observedValues) && parametersPreserved(alternate, alternateValues) &&
            observed.params != alternate.params && hasSecretUpdate(observed)
        return outcome(
            preserved,
            "SECRET_ROTATE preserves the bounded rotation window as distinguishable canonical lifecycle meaning.",
            "Rotation-window data is accepted without preserving the authored execution window as canonical meaning."
        )
    }

    private fun automationEnablement(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val subject = values.getValue(SUBJECT)
        val observedValues = mapOf(SUBJECT to subject, AUTOMATION_ENABLED_PARAM to values.getValue(ENABLED))
        val observed = semanticProjection(observedValues)
        if (!observed.semanticParametersAccepted) {
            return ExternalFalsificationOutcome.MODEL_GAP to
                "SECRET_ROTATE cannot preserve persistent automatic-rotation enablement state; executing one rotation action is not equivalent to enabling or disabling future automation."
        }
        val alternateValues = mapOf(SUBJECT to subject, AUTOMATION_ENABLED_PARAM to alternateBoolean(values.getValue(ENABLED)))
        val alternate = semanticProjection(alternateValues)
        val preserved = observed.valid && alternate.valid &&
            parametersPreserved(observed, observedValues) && parametersPreserved(alternate, alternateValues) &&
            observed.params != alternate.params && hasRotationPolicyUpdate(observed) && hasRotationPolicyUpdate(alternate)
        return outcome(
            preserved,
            "The canonical model preserves automatic-rotation enablement as persistent policy state distinct from performing a rotation.",
            "Automatic-rotation state is accepted without a distinct canonical rotation-policy effect."
        )
    }

    private fun priorCredentialPolicy(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val subject = values.getValue(SUBJECT)
        val observedValues = mapOf(SUBJECT to subject, PRIOR_CREDENTIAL_POLICY_PARAM to values.getValue(POLICY))
        val observed = semanticProjection(observedValues)
        if (!observed.semanticParametersAccepted) {
            return ExternalFalsificationOutcome.MODEL_GAP to
                "SECRET_ROTATE has no typed prior-credential retirement policy, so immediate invalidation and retained previous credentials collapse into the same action."
        }
        val alternatePolicy = if (values.getValue(POLICY) == RETAIN_PREVIOUS) INVALIDATE_IMMEDIATELY else RETAIN_PREVIOUS
        val alternateValues = mapOf(SUBJECT to subject, PRIOR_CREDENTIAL_POLICY_PARAM to alternatePolicy)
        val alternate = semanticProjection(alternateValues)
        val preserved = observed.valid && alternate.valid &&
            parametersPreserved(observed, observedValues) && parametersPreserved(alternate, alternateValues) &&
            observed.params != alternate.params && hasSecretUpdate(observed) && hasSecretUpdate(alternate)
        return outcome(
            preserved,
            "SECRET_ROTATE preserves the authored prior-credential retirement/overlap policy as semantically distinguishable lifecycle meaning.",
            "Prior-credential policy is accepted without preserving the difference between immediate invalidation and retained overlap."
        )
    }

    private fun semanticProjection(values: Map<String, String>, triggers: List<IntentTrigger> = emptyList()): SemanticProjection {
        val report = IntentCapabilityValidator().validate(
            IntentDocument(
                name = "ef06-secret-rotation",
                triggers = triggers,
                workflows = listOf(
                    IntentWorkflow(
                        name = "main",
                        kind = IntentWorkflowKind.SECRET_ROTATION,
                        steps = listOf(
                            IntentStep(
                                id = "rotate-secret",
                                capability = StandardCapability.SECRET_ROTATE,
                                params = values.mapValues { (_, value) -> IntentString(value) }
                            )
                        )
                    )
                )
            )
        )
        val parameterErrors = report.issues.filter {
            it.level == "error" && it.code in SEMANTIC_PARAMETER_ERROR_CODES
        }
        val step = report.meaning.workflows.single().steps.single()
        return SemanticProjection(
            valid = report.valid,
            semanticParametersAccepted = parameterErrors.isEmpty(),
            params = step.params,
            effects = step.effects,
            triggers = report.meaning.triggers
        )
    }

    private fun scheduleTrigger(id: String, cadence: String) = IntentTrigger(
        id = id,
        type = IntentTriggerType.SCHEDULE,
        workflows = listOf("main"),
        schedule = IntentSchedule(IntentScheduleKind.INTERVAL, cadence)
    )

    private fun parametersPreserved(projection: SemanticProjection, expected: Map<String, String>): Boolean =
        projection.semanticParametersAccepted && expected.all { (name, value) -> projection.params[name] == IntentString(value) }

    private fun hasSecretUpdate(projection: SemanticProjection): Boolean = hasEffect(
        projection, EffectDomain.INFRASTRUCTURE_STATE, EffectOperation.UPDATE, SECRET_RESOURCE
    )

    private fun hasRotationPolicyUpdate(projection: SemanticProjection): Boolean = hasEffect(
        projection, EffectDomain.INFRASTRUCTURE_STATE, EffectOperation.UPDATE, ROTATION_POLICY_RESOURCE
    )

    private fun hasEffect(projection: SemanticProjection, domain: EffectDomain, operation: EffectOperation, resource: String): Boolean =
        projection.effects.any { it.domain == domain && it.operation == operation && it.resource == resource }

    private fun outcome(preserved: Boolean, success: String, gap: String) =
        if (preserved) ExternalFalsificationOutcome.REPRESENTABLE to success else ExternalFalsificationOutcome.MODEL_GAP to gap

    private fun alternateBoolean(value: String): String = when (value.trim().lowercase()) {
        "true", "enabled" -> "false"
        else -> "true"
    }

    private fun requireUnique(caseId: String, label: String, values: List<String>) {
        require(values.distinct().size == values.size) { "EF-06 case '$caseId' contains duplicate $label values." }
    }

    private fun <T> readStrict(file: File, type: Class<T>): T = try {
        FlowYaml.readStrict(file, type)
    } catch (error: FlowYamlException) {
        throw IllegalArgumentException("Invalid EF-06 assessment '${file.path}': ${error.message ?: error.javaClass.simpleName}", error)
    }

    private data class SemanticProjection(
        val valid: Boolean,
        val semanticParametersAccepted: Boolean,
        val params: Map<String, IntentValue>,
        val effects: List<SemanticEffect>,
        val triggers: List<IntentTrigger>
    )

    companion object {
        const val DOMAIN = "secret-rotation"
        const val KIND = "FlowSecretRotationFalsification"
        const val VERSION = "1.0"
        const val ASSESSMENT_FILE = "secret-rotation.yaml"
        const val MIN_CASES = 6
        const val MIN_REPOSITORIES = 2

        private const val SUBJECT = "subject"
        private const val CADENCE = "cadence"
        private const val WINDOW = "window"
        private const val ENABLED = "enabled"
        private const val POLICY = "policy"
        private const val ROTATION_MODE_PARAM = "rotationMode"
        private const val ROTATION_WINDOW_PARAM = "rotationWindow"
        private const val AUTOMATION_ENABLED_PARAM = "automationEnabled"
        private const val PRIOR_CREDENTIAL_POLICY_PARAM = "priorCredentialPolicy"
        private const val WAIT_FOR_WINDOW = "wait-for-next-window"
        private const val ROTATE_NOW = "immediate"
        private const val RETAIN_PREVIOUS = "retain-previous"
        private const val INVALIDATE_IMMEDIATELY = "invalidate-immediately"
        private const val ALTERNATE_CADENCE = "PT2H"
        private const val SECOND_ALTERNATE_CADENCE = "PT3H"
        private const val ALTERNATE_WINDOW = "PT1H"
        private const val SECRET_RESOURCE = "infrastructure.secret"
        private const val ROTATION_POLICY_RESOURCE = "infrastructure.secret.rotation-policy"

        private val REQUIRED_REQUIREMENTS = SecretRotationRequirement.values().toSet()
        private val SEMANTIC_PARAMETER_ERROR_CODES = setOf("UNKNOWN_STEP_PARAM", "MISSING_REQUIRED_STEP_PARAM")
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}
