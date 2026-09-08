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
import org.flowlang.intent.IntentValidationReport
import org.flowlang.intent.IntentValue
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException

enum class InfrastructureLifecycleRequirement {
    RESOURCE_EXISTENCE_UPSERT,
    RESOURCE_DEPROVISIONING,
    DESIRED_STATE_RECONCILIATION,
    NON_MUTATING_CHANGE_PREVIEW,
    CREATE_BEFORE_DESTROY_REPLACEMENT,
    DESTRUCTION_PROHIBITION,
    RETAIN_ON_MANAGEMENT_REMOVAL,
    PERSISTENT_MANAGEMENT_ACTION_POLICY
}

data class InfrastructureLifecycleFactDeclaration(
    val id: String,
    val observationRef: String,
    val requirement: InfrastructureLifecycleRequirement,
    val values: Map<String, String>
)

data class InfrastructureLifecycleCaseAssessment(
    val kind: String,
    val version: String,
    val caseId: String,
    val facts: List<InfrastructureLifecycleFactDeclaration>
)

data class InfrastructureLifecycleFinding(
    val caseId: String,
    val factId: String,
    val observationRef: String,
    val requirement: InfrastructureLifecycleRequirement,
    val outcome: ExternalFalsificationOutcome,
    val reason: String
)

data class InfrastructureLifecycleFalsificationReport(
    val caseCount: Int,
    val distinctRepositoryCount: Int,
    val findings: List<InfrastructureLifecycleFinding>
) {
    val representableCount: Int get() = findings.count { it.outcome == ExternalFalsificationOutcome.REPRESENTABLE }
    val modelGapCount: Int get() = findings.count { it.outcome == ExternalFalsificationOutcome.MODEL_GAP }
}

/**
 * EF-08 evaluator for externally grounded infrastructure-lifecycle facts.
 *
 * Reviewed case assessments are the only product-to-semantic translation boundary. Every
 * representability decision crosses the production intent validator and canonical effect model.
 * A preserved string is insufficient when the canonical state transition contradicts the authored
 * lifecycle obligation; ordered steps are also insufficient when replacement identity is absent.
 */
class InfrastructureLifecycleFalsification(private val rootDir: File = File(".")) {
    fun evaluate(): InfrastructureLifecycleFalsificationReport {
        val corpus = ExternalCorpusLoader(rootDir).load()
        require(corpus.manifest.status == ExternalCorpusStatus.EVIDENCE_ACTIVE) {
            "EF-08 requires an EVIDENCE_ACTIVE external corpus."
        }
        val cases = corpus.cases.filter { it.definition.domain == DOMAIN }
        require(cases.size >= MIN_CASES) {
            "EF-08 requires at least $MIN_CASES infrastructure-lifecycle evidence cases."
        }
        val repositoryCount = cases.map { it.definition.provenance.repository }.distinct().size
        require(repositoryCount >= MIN_REPOSITORIES) {
            "EF-08 requires evidence from at least $MIN_REPOSITORIES independent repositories."
        }

        val findings = cases.flatMap { loadedCase ->
            val assessment = loadAssessment(loadedCase)
            assessment.facts.map { fact -> classify(loadedCase.definition.id, fact) }
        }
        require(findings.isNotEmpty()) { "EF-08 must classify at least one semantic fact." }

        val coveredRequirements = findings.map { it.requirement }.toSet()
        val missingRequirements = REQUIRED_REQUIREMENTS - coveredRequirements
        require(missingRequirements.isEmpty()) {
            "EF-08 corpus does not exercise required semantic shapes: " +
                missingRequirements.sortedBy { it.name }.joinToString() + "."
        }

        return InfrastructureLifecycleFalsificationReport(cases.size, repositoryCount, findings)
    }

    private fun loadAssessment(loadedCase: LoadedExternalCorpusCase): InfrastructureLifecycleCaseAssessment {
        val file = File(loadedCase.directory, ASSESSMENT_FILE)
        require(file.isFile) {
            "Missing EF-08 assessment for case '${loadedCase.definition.id}': ${file.path}"
        }
        val assessment = readStrict(file, InfrastructureLifecycleCaseAssessment::class.java)
        require(assessment.kind == KIND && assessment.version == VERSION) {
            "EF-08 assessment for '${loadedCase.definition.id}' must use $KIND version $VERSION."
        }
        require(assessment.caseId == loadedCase.definition.id) {
            "EF-08 assessment caseId '${assessment.caseId}' does not match '${loadedCase.definition.id}'."
        }
        require(assessment.facts.isNotEmpty()) {
            "EF-08 assessment for '${assessment.caseId}' must declare semantic facts."
        }
        requireUnique(assessment.caseId, "fact", assessment.facts.map { it.id })
        requireUnique(assessment.caseId, "observation reference", assessment.facts.map { it.observationRef })

        val observations = loadedCase.definition.expectedSemanticObservations.associateBy { it.id }
        assessment.facts.forEach { fact ->
            require(ID_PATTERN.matches(fact.id)) {
                "EF-08 case '${assessment.caseId}' contains invalid fact id '${fact.id}'."
            }
            val observation = observations[fact.observationRef]
                ?: throw IllegalArgumentException(
                    "EF-08 case '${assessment.caseId}' fact '${fact.id}' references unknown semantic observation '${fact.observationRef}'."
                )
            require(observation.expectation == ExternalSemanticExpectation.PRESERVE) {
                "EF-08 case '${assessment.caseId}' fact '${fact.id}' must reference a PRESERVE semantic observation."
            }
            validateValues(assessment.caseId, fact)
        }

        val preservationObservations = loadedCase.definition.expectedSemanticObservations
            .filter { it.expectation == ExternalSemanticExpectation.PRESERVE }
            .map { it.id }
            .toSet()
        val covered = assessment.facts.map { it.observationRef }.toSet()
        require(covered == preservationObservations) {
            "EF-08 case '${assessment.caseId}' must classify every PRESERVE semantic observation exactly once; " +
                "expected=${preservationObservations.sorted()} actual=${covered.sorted()}."
        }
        return assessment
    }

    private fun validateValues(caseId: String, fact: InfrastructureLifecycleFactDeclaration) {
        require(fact.values.values.none(String::isBlank)) {
            "EF-08 case '$caseId' fact '${fact.id}' contains a blank semantic value."
        }
        val required = when (fact.requirement) {
            InfrastructureLifecycleRequirement.RESOURCE_EXISTENCE_UPSERT -> setOf(RESOURCE, TARGET)
            InfrastructureLifecycleRequirement.RESOURCE_DEPROVISIONING -> setOf(TARGET)
            InfrastructureLifecycleRequirement.DESIRED_STATE_RECONCILIATION -> setOf(RESOURCE, TARGET, MODE)
            InfrastructureLifecycleRequirement.NON_MUTATING_CHANGE_PREVIEW -> setOf(RESOURCE, TARGET, MODE)
            InfrastructureLifecycleRequirement.CREATE_BEFORE_DESTROY_REPLACEMENT ->
                setOf(LOGICAL_RESOURCE, OLD_TARGET, NEW_TARGET)
            InfrastructureLifecycleRequirement.DESTRUCTION_PROHIBITION -> setOf(TARGET, GUARD)
            InfrastructureLifecycleRequirement.RETAIN_ON_MANAGEMENT_REMOVAL -> setOf(TARGET, POLICY)
            InfrastructureLifecycleRequirement.PERSISTENT_MANAGEMENT_ACTION_POLICY -> setOf(TARGET, ACTIONS)
        }
        require(fact.values.keys.containsAll(required)) {
            "EF-08 case '$caseId' fact '${fact.id}' is missing required semantic values " +
                (required - fact.values.keys).sorted() + "."
        }
        require(fact.values.keys.all { it in required }) {
            "EF-08 case '$caseId' fact '${fact.id}' contains unsupported semantic value keys " +
                (fact.values.keys - required).sorted() + "."
        }
    }

    private fun classify(
        caseId: String,
        fact: InfrastructureLifecycleFactDeclaration
    ): InfrastructureLifecycleFinding {
        val classified = when (fact.requirement) {
            InfrastructureLifecycleRequirement.RESOURCE_EXISTENCE_UPSERT -> resourceExistenceUpsert(fact.values)
            InfrastructureLifecycleRequirement.RESOURCE_DEPROVISIONING -> resourceDeprovisioning(fact.values)
            InfrastructureLifecycleRequirement.DESIRED_STATE_RECONCILIATION -> desiredStateReconciliation(fact.values)
            InfrastructureLifecycleRequirement.NON_MUTATING_CHANGE_PREVIEW -> nonMutatingPreview(fact.values)
            InfrastructureLifecycleRequirement.CREATE_BEFORE_DESTROY_REPLACEMENT ->
                createBeforeDestroyReplacement(fact.values)
            InfrastructureLifecycleRequirement.DESTRUCTION_PROHIBITION -> destructionProhibition(fact.values)
            InfrastructureLifecycleRequirement.RETAIN_ON_MANAGEMENT_REMOVAL -> retainOnManagementRemoval(fact.values)
            InfrastructureLifecycleRequirement.PERSISTENT_MANAGEMENT_ACTION_POLICY ->
                persistentManagementActionPolicy(fact.values)
        }
        return InfrastructureLifecycleFinding(
            caseId = caseId,
            factId = fact.id,
            observationRef = fact.observationRef,
            requirement = fact.requirement,
            outcome = classified.first,
            reason = classified.second
        )
    }

    private fun resourceExistenceUpsert(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val authored = mapOf(
            RESOURCE to values.getValue(RESOURCE),
            TARGET to values.getValue(TARGET)
        )
        val alternate = authored + (RESOURCE to "${values.getValue(RESOURCE)}-alternate")
        val observed = provisionProjection(authored)
        val alternateProjection = provisionProjection(alternate)
        val preserved = observed.valid && alternateProjection.valid &&
            parametersPreserved(observed, authored) &&
            parametersPreserved(alternateProjection, alternate) &&
            observed.params != alternateProjection.params &&
            hasEffect(observed, EffectOperation.UPSERT, INFRASTRUCTURE_RESOURCE) &&
            hasEffect(alternateProjection, EffectOperation.UPSERT, INFRASTRUCTURE_RESOURCE)

        return outcome(
            preserved,
            "PROVISION preserves the named resource and target with a canonical infrastructure-resource UPSERT transition.",
            "PROVISION does not preserve the named resource existence/update intent through canonical parameters and effects."
        )
    }

    private fun resourceDeprovisioning(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val authored = mapOf(TARGET to values.getValue(TARGET))
        val alternate = mapOf(TARGET to "${values.getValue(TARGET)}-alternate")
        val observed = deprovisionProjection(authored)
        val alternateProjection = deprovisionProjection(alternate)
        val preserved = observed.valid && alternateProjection.valid &&
            parametersPreserved(observed, authored) &&
            parametersPreserved(alternateProjection, alternate) &&
            observed.params != alternateProjection.params &&
            hasEffect(observed, EffectOperation.DELETE, INFRASTRUCTURE_RESOURCE) &&
            hasEffect(alternateProjection, EffectOperation.DELETE, INFRASTRUCTURE_RESOURCE)

        return outcome(
            preserved,
            "Approval-protected DEPROVISION preserves the named target and canonical infrastructure-resource DELETE transition.",
            "The current validated DEPROVISION path does not preserve explicit infrastructure removal."
        )
    }

    private fun desiredStateReconciliation(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val authored = mapOf(
            RESOURCE to values.getValue(RESOURCE),
            TARGET to values.getValue(TARGET),
            MODE to values.getValue(MODE)
        )
        val alternate = authored + (MODE to CREATE_ONLY_MODE)
        val observed = provisionProjection(authored)
        val alternateProjection = provisionProjection(alternate)
        val preserved = observed.valid && alternateProjection.valid &&
            parametersPreserved(observed, authored) &&
            parametersPreserved(alternateProjection, alternate) &&
            observed.params != alternateProjection.params &&
            hasEffect(observed, EffectOperation.UPSERT, INFRASTRUCTURE_RESOURCE)

        return outcome(
            preserved,
            "PROVISION preserves named desired-state reconciliation as a declared mode with canonical UPSERT meaning distinguishable from create-only intent.",
            "Desired-state reconciliation is not preserved as distinguishable canonical create-or-update meaning."
        )
    }

    private fun nonMutatingPreview(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val previewValues = mapOf(
            RESOURCE to values.getValue(RESOURCE),
            TARGET to values.getValue(TARGET),
            MODE to values.getValue(MODE)
        )
        val applyValues = previewValues + (MODE to APPLY_MODE)
        val preview = provisionProjection(previewValues)
        val apply = provisionProjection(applyValues)
        val preserved = preview.valid && apply.valid &&
            parametersPreserved(preview, previewValues) &&
            parametersPreserved(apply, applyValues) &&
            preview.params != apply.params &&
            !hasExternalResourceMutation(preview) &&
            hasEffect(apply, EffectOperation.UPSERT, INFRASTRUCTURE_RESOURCE) &&
            preview.effects != apply.effects

        return outcome(
            preserved,
            "Canonical infrastructure meaning distinguishes non-mutating preview from apply while preserving resource and target identity.",
            "PROVISION accepts mode=preview but still carries the same infrastructure UPSERT effect as apply, so the preview string does not preserve non-mutating lifecycle meaning."
        )
    }

    private fun createBeforeDestroyReplacement(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val logicalResource = values.getValue(LOGICAL_RESOURCE)
        val oldTarget = values.getValue(OLD_TARGET)
        val newTarget = values.getValue(NEW_TARGET)
        val report = validate(
            listOf(
                approvalStep(),
                IntentStep(
                    id = PROVISION_STEP,
                    capability = StandardCapability.PROVISION,
                    requires = listOf(APPROVAL_STEP),
                    params = stringParams(
                        mapOf(
                            RESOURCE to logicalResource,
                            TARGET to newTarget,
                            REPLACEMENT_OF_PARAM to oldTarget
                        )
                    )
                ),
                IntentStep(
                    id = DEPROVISION_STEP,
                    capability = StandardCapability.DEPROVISION,
                    requires = listOf(PROVISION_STEP),
                    params = stringParams(
                        mapOf(
                            TARGET to oldTarget,
                            LOGICAL_RESOURCE_PARAM to logicalResource
                        )
                    )
                )
            )
        )
        val replacement = projection(report, PROVISION_STEP)
        val removal = projection(report, DEPROVISION_STEP)
        val preserved = replacement.valid && removal.valid &&
            replacement.semanticParametersAccepted && removal.semanticParametersAccepted &&
            replacement.params[RESOURCE] == IntentString(logicalResource) &&
            replacement.params[TARGET] == IntentString(newTarget) &&
            replacement.params[REPLACEMENT_OF_PARAM] == IntentString(oldTarget) &&
            removal.params[TARGET] == IntentString(oldTarget) &&
            removal.params[LOGICAL_RESOURCE_PARAM] == IntentString(logicalResource) &&
            removal.requires == listOf(PROVISION_STEP) &&
            hasEffect(replacement, EffectOperation.UPSERT, INFRASTRUCTURE_RESOURCE) &&
            hasEffect(removal, EffectOperation.DELETE, INFRASTRUCTURE_RESOURCE)

        return outcome(
            preserved,
            "Canonical meaning ties old and new instances to one logical resource and preserves create-before-destroy replacement ordering.",
            "Current contracts can order a provision step before a deprovision step, but they reject the typed old/new replacement relation; two ordered unrelated targets are not create-before-destroy continuity."
        )
    }

    private fun destructionProhibition(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val guardedValues = mapOf(
            TARGET to values.getValue(TARGET),
            SAFETY to values.getValue(GUARD)
        )
        val allowedValues = guardedValues + (SAFETY to ALLOW_DESTROY)
        val guarded = deprovisionProjection(guardedValues)
        val allowed = deprovisionProjection(allowedValues)
        val guardedBlocked = !guarded.valid || !hasEffect(
            guarded,
            EffectOperation.DELETE,
            INFRASTRUCTURE_RESOURCE
        )
        val preserved = guarded.semanticParametersAccepted &&
            parametersPreserved(guarded, guardedValues) &&
            allowed.valid &&
            parametersPreserved(allowed, allowedValues) &&
            hasEffect(allowed, EffectOperation.DELETE, INFRASTRUCTURE_RESOURCE) &&
            guardedBlocked

        return outcome(
            preserved,
            "The authored destruction guard blocks destructive lifecycle planning while an explicitly allowed variant retains DELETE meaning.",
            "The safety value is preserved as text, but approval-protected DEPROVISION remains valid and still carries DELETE; approval or descriptive safety is not destruction prohibition."
        )
    }

    private fun retainOnManagementRemoval(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val retainedValues = mapOf(
            TARGET to values.getValue(TARGET),
            SAFETY to values.getValue(POLICY)
        )
        val deletedValues = retainedValues + (SAFETY to DELETE_POLICY)
        val retained = deprovisionProjection(retainedValues)
        val deleted = deprovisionProjection(deletedValues)
        val preserved = retained.valid && deleted.valid &&
            parametersPreserved(retained, retainedValues) &&
            parametersPreserved(deleted, deletedValues) &&
            !hasEffect(retained, EffectOperation.DELETE, INFRASTRUCTURE_RESOURCE) &&
            hasEffect(deleted, EffectOperation.DELETE, INFRASTRUCTURE_RESOURCE) &&
            retained.effects != deleted.effects

        return outcome(
            preserved,
            "Canonical meaning distinguishes removal of management ownership from deletion of the external infrastructure object.",
            "DEPROVISION accepts an orphan/retain safety string but still emits the same infrastructure DELETE transition, so management removal and external deletion collapse."
        )
    }

    private fun persistentManagementActionPolicy(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val observeValues = mapOf(
            RESOURCE to values.getValue(TARGET),
            TARGET to values.getValue(TARGET),
            MODE to values.getValue(ACTIONS)
        )
        val manageValues = observeValues + (MODE to FULL_MANAGEMENT_ACTIONS)
        val observe = provisionProjection(observeValues)
        val manage = provisionProjection(manageValues)
        val preserved = observe.valid && manage.valid &&
            parametersPreserved(observe, observeValues) &&
            parametersPreserved(manage, manageValues) &&
            observe.params != manage.params &&
            hasManagementPolicyEffect(observe) &&
            hasManagementPolicyEffect(manage) &&
            !hasExternalResourceMutation(observe) &&
            observe.effects != manage.effects

        return outcome(
            preserved,
            "Canonical meaning preserves a persistent allowed-action policy and distinguishes observe-only from full management.",
            "PROVISION preserves the mode string but emits a one-shot infrastructure UPSERT for both observe-only and full-management values; no persistent management-action policy is represented."
        )
    }

    private fun provisionProjection(values: Map<String, String>): InfrastructureProjection {
        val report = validate(
            listOf(
                IntentStep(
                    id = PROVISION_STEP,
                    capability = StandardCapability.PROVISION,
                    params = stringParams(values)
                )
            )
        )
        return projection(report, PROVISION_STEP)
    }

    private fun deprovisionProjection(values: Map<String, String>): InfrastructureProjection {
        val report = validate(
            listOf(
                approvalStep(),
                IntentStep(
                    id = DEPROVISION_STEP,
                    capability = StandardCapability.DEPROVISION,
                    requires = listOf(APPROVAL_STEP),
                    params = stringParams(values)
                )
            )
        )
        return projection(report, DEPROVISION_STEP)
    }

    private fun approvalStep() = IntentStep(
        id = APPROVAL_STEP,
        capability = StandardCapability.APPROVE,
        params = mapOf(MESSAGE to IntentString("Approve destructive infrastructure lifecycle action"))
    )

    private fun validate(steps: List<IntentStep>): IntentValidationReport =
        IntentCapabilityValidator(ModuleRegistry()).validate(
            IntentDocument(
                name = "ef08-infrastructure-lifecycle",
                workflows = listOf(
                    IntentWorkflow(
                        name = "main",
                        kind = IntentWorkflowKind.PROVISION,
                        steps = steps
                    )
                )
            )
        )

    private fun projection(
        report: IntentValidationReport,
        stepId: String
    ): InfrastructureProjection {
        val parameterErrors = report.issues.filter {
            it.level == "error" && it.code in SEMANTIC_PARAMETER_ERROR_CODES
        }
        val step = report.meaning.workflows.single().steps.single { it.id == stepId }
        return InfrastructureProjection(
            valid = report.valid,
            semanticParametersAccepted = parameterErrors.isEmpty(),
            params = step.params,
            effects = step.effects,
            requires = step.requires
        )
    }

    private fun stringParams(values: Map<String, String>): Map<String, IntentValue> =
        values.mapValues { (_, value) -> IntentString(value) }

    private fun parametersPreserved(
        projection: InfrastructureProjection,
        expected: Map<String, String>
    ): Boolean = projection.semanticParametersAccepted &&
        expected.all { (name, value) -> projection.params[name] == IntentString(value) }

    private fun hasEffect(
        projection: InfrastructureProjection,
        operation: EffectOperation,
        resource: String
    ): Boolean = projection.effects.any {
        it.domain == EffectDomain.INFRASTRUCTURE_STATE &&
            it.operation == operation &&
            it.resource == resource
    }

    private fun hasExternalResourceMutation(projection: InfrastructureProjection): Boolean =
        projection.effects.any {
            it.domain == EffectDomain.INFRASTRUCTURE_STATE &&
                it.resource == INFRASTRUCTURE_RESOURCE &&
                it.operation in MUTATING_OPERATIONS
        }

    private fun hasManagementPolicyEffect(projection: InfrastructureProjection): Boolean =
        projection.effects.any {
            it.domain == EffectDomain.INFRASTRUCTURE_STATE &&
                it.resource == MANAGEMENT_POLICY_RESOURCE &&
                it.operation in setOf(EffectOperation.UPDATE, EffectOperation.UPSERT)
        }

    private fun outcome(
        preserved: Boolean,
        success: String,
        gap: String
    ): Pair<ExternalFalsificationOutcome, String> =
        if (preserved) ExternalFalsificationOutcome.REPRESENTABLE to success
        else ExternalFalsificationOutcome.MODEL_GAP to gap

    private fun requireUnique(caseId: String, label: String, values: List<String>) {
        require(values.distinct().size == values.size) {
            "EF-08 case '$caseId' contains duplicate $label values."
        }
    }

    private fun <T> readStrict(file: File, type: Class<T>): T = try {
        FlowYaml.readStrict(file, type)
    } catch (error: FlowYamlException) {
        throw IllegalArgumentException(
            "Invalid EF-08 assessment '${file.path}': ${error.message ?: error.javaClass.simpleName}",
            error
        )
    }

    private data class InfrastructureProjection(
        val valid: Boolean,
        val semanticParametersAccepted: Boolean,
        val params: Map<String, IntentValue>,
        val effects: List<SemanticEffect>,
        val requires: List<String>
    )

    companion object {
        const val DOMAIN = "infrastructure-lifecycle"
        const val KIND = "FlowInfrastructureLifecycleFalsification"
        const val VERSION = "1.0"
        const val ASSESSMENT_FILE = "infrastructure-lifecycle.yaml"
        const val MIN_CASES = 8
        const val MIN_REPOSITORIES = 2

        private const val RESOURCE = "resource"
        private const val TARGET = "target"
        private const val MODE = "mode"
        private const val LOGICAL_RESOURCE = "logicalResource"
        private const val OLD_TARGET = "oldTarget"
        private const val NEW_TARGET = "newTarget"
        private const val GUARD = "guard"
        private const val POLICY = "policy"
        private const val ACTIONS = "actions"
        private const val SAFETY = "safety"
        private const val MESSAGE = "message"

        private const val REPLACEMENT_OF_PARAM = "replacementOf"
        private const val LOGICAL_RESOURCE_PARAM = "logicalResource"
        private const val CREATE_ONLY_MODE = "create-only"
        private const val APPLY_MODE = "apply"
        private const val ALLOW_DESTROY = "allow-destroy"
        private const val DELETE_POLICY = "delete"
        private const val FULL_MANAGEMENT_ACTIONS = "observe,create,update,delete"

        private const val APPROVAL_STEP = "approve-infrastructure-change"
        private const val PROVISION_STEP = "provision-resource"
        private const val DEPROVISION_STEP = "deprovision-resource"

        private const val INFRASTRUCTURE_RESOURCE = "infrastructure.resource"
        private const val MANAGEMENT_POLICY_RESOURCE = "infrastructure.management-policy"

        private val MUTATING_OPERATIONS = setOf(
            EffectOperation.CREATE,
            EffectOperation.UPDATE,
            EffectOperation.DELETE,
            EffectOperation.UPSERT
        )
        private val REQUIRED_REQUIREMENTS = InfrastructureLifecycleRequirement.values().toSet()
        private val SEMANTIC_PARAMETER_ERROR_CODES =
            setOf("UNKNOWN_STEP_PARAM", "MISSING_REQUIRED_STEP_PARAM")
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}
