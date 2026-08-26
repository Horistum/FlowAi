package org.flowlang.conformance

import java.io.File
import org.flowlang.controls.ControlDecisionStatus
import org.flowlang.controls.ControlEvidenceStatus
import org.flowlang.controls.ControlRequirement
import org.flowlang.controls.ControlRequirementKind
import org.flowlang.controls.ControlRequirementScopeKind
import org.flowlang.controls.ControlRequirementSource
import org.flowlang.intent.IntentBoolean
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentPolicy
import org.flowlang.intent.IntentPolicyType
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentValidationReport
import org.flowlang.intent.IntentValue
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException

enum class HumanApprovalChangeControlRequirement {
    APPROVAL_REQUIREMENT_DECLARATION,
    ORDERED_APPROVAL_GATE,
    APPROVAL_DECISION_STATE,
    AUTHORIZED_APPROVER_SET,
    APPROVAL_QUORUM,
    SEPARATION_OF_DUTIES,
    REVISION_BOUND_APPROVAL,
    WHOLE_CHANGESET_APPROVAL_COVERAGE,
    REVISION_BOUND_CHANGE_EXECUTION
}

data class HumanApprovalChangeControlFactDeclaration(
    val id: String,
    val observationRef: String,
    val requirement: HumanApprovalChangeControlRequirement,
    val values: Map<String, String>
)

data class HumanApprovalChangeControlCaseAssessment(
    val kind: String,
    val version: String,
    val caseId: String,
    val facts: List<HumanApprovalChangeControlFactDeclaration>
)

data class HumanApprovalChangeControlFinding(
    val caseId: String,
    val factId: String,
    val observationRef: String,
    val requirement: HumanApprovalChangeControlRequirement,
    val outcome: ExternalFalsificationOutcome,
    val reason: String
)

data class HumanApprovalChangeControlFalsificationReport(
    val caseCount: Int,
    val distinctRepositoryCount: Int,
    val findings: List<HumanApprovalChangeControlFinding>
) {
    val representableCount: Int
        get() = findings.count { it.outcome == ExternalFalsificationOutcome.REPRESENTABLE }

    val modelGapCount: Int
        get() = findings.count { it.outcome == ExternalFalsificationOutcome.MODEL_GAP }
}

/**
 * EF-09 evaluator for externally grounded approval and change-control facts.
 *
 * Reviewed case assessments are the only product-to-semantic translation boundary. A result is
 * REPRESENTABLE only when the public intent validator, canonical meaning and control assessment
 * preserve the authored obligation. An APPROVE node is a mechanism, not a fabricated human
 * decision; strings such as reviewer, quorum or revision do not gain meaning by surviving in prose.
 */
class HumanApprovalChangeControlFalsification(private val rootDir: File = File(".")) {
    fun evaluate(): HumanApprovalChangeControlFalsificationReport {
        val corpus = ExternalCorpusLoader(rootDir).load()
        require(corpus.manifest.status == ExternalCorpusStatus.EVIDENCE_ACTIVE) {
            "EF-09 requires an EVIDENCE_ACTIVE external corpus."
        }
        val cases = corpus.cases.filter { it.definition.domain == DOMAIN }
        require(cases.size >= MIN_CASES) {
            "EF-09 requires at least $MIN_CASES human-approval/change-control evidence cases."
        }
        val repositoryCount = cases.map { it.definition.provenance.repository }.distinct().size
        require(repositoryCount >= MIN_REPOSITORIES) {
            "EF-09 requires evidence from at least $MIN_REPOSITORIES independent repositories."
        }

        val findings = cases.flatMap { loadedCase ->
            val assessment = loadAssessment(loadedCase)
            assessment.facts.map { fact -> classify(loadedCase.definition.id, fact) }
        }
        require(findings.isNotEmpty()) { "EF-09 must classify at least one semantic fact." }

        val coveredRequirements = findings.map { it.requirement }.toSet()
        val missingRequirements = REQUIRED_REQUIREMENTS - coveredRequirements
        require(missingRequirements.isEmpty()) {
            "EF-09 corpus does not exercise required semantic shapes: " +
                missingRequirements.sortedBy { it.name }.joinToString() + "."
        }

        return HumanApprovalChangeControlFalsificationReport(
            caseCount = cases.size,
            distinctRepositoryCount = repositoryCount,
            findings = findings
        )
    }

    private fun loadAssessment(
        loadedCase: LoadedExternalCorpusCase
    ): HumanApprovalChangeControlCaseAssessment {
        val file = File(loadedCase.directory, ASSESSMENT_FILE)
        require(file.isFile) {
            "Missing EF-09 assessment for case '${loadedCase.definition.id}': ${file.path}"
        }
        val assessment = readStrict(file, HumanApprovalChangeControlCaseAssessment::class.java)
        require(assessment.kind == KIND && assessment.version == VERSION) {
            "EF-09 assessment for '${loadedCase.definition.id}' must use $KIND version $VERSION."
        }
        require(assessment.caseId == loadedCase.definition.id) {
            "EF-09 assessment caseId '${assessment.caseId}' does not match '${loadedCase.definition.id}'."
        }
        require(assessment.facts.isNotEmpty()) {
            "EF-09 assessment for '${assessment.caseId}' must declare semantic facts."
        }
        requireUnique(assessment.caseId, "fact", assessment.facts.map { it.id })
        requireUnique(
            assessment.caseId,
            "observation reference",
            assessment.facts.map { it.observationRef }
        )

        val observations = loadedCase.definition.expectedSemanticObservations.associateBy { it.id }
        assessment.facts.forEach { fact ->
            require(ID_PATTERN.matches(fact.id)) {
                "EF-09 case '${assessment.caseId}' contains invalid fact id '${fact.id}'."
            }
            val observation = observations[fact.observationRef]
                ?: throw IllegalArgumentException(
                    "EF-09 case '${assessment.caseId}' fact '${fact.id}' references unknown " +
                        "semantic observation '${fact.observationRef}'."
                )
            require(observation.expectation == ExternalSemanticExpectation.PRESERVE) {
                "EF-09 case '${assessment.caseId}' fact '${fact.id}' must reference a " +
                    "PRESERVE semantic observation."
            }
            validateValues(assessment.caseId, fact)
        }

        val expected = loadedCase.definition.expectedSemanticObservations
            .filter { it.expectation == ExternalSemanticExpectation.PRESERVE }
            .map { it.id }
            .toSet()
        val actual = assessment.facts.map { it.observationRef }.toSet()
        require(actual == expected) {
            "EF-09 case '${assessment.caseId}' must classify every PRESERVE semantic observation " +
                "exactly once; expected=${expected.sorted()} actual=${actual.sorted()}."
        }
        return assessment
    }

    private fun validateValues(
        caseId: String,
        fact: HumanApprovalChangeControlFactDeclaration
    ) {
        require(fact.values.values.none(String::isBlank)) {
            "EF-09 case '$caseId' fact '${fact.id}' contains a blank semantic value."
        }
        val required = when (fact.requirement) {
            HumanApprovalChangeControlRequirement.APPROVAL_REQUIREMENT_DECLARATION ->
                setOf(SUBJECT, MESSAGE)
            HumanApprovalChangeControlRequirement.ORDERED_APPROVAL_GATE ->
                setOf(SUBJECT, MESSAGE, RESOURCE, TARGET)
            HumanApprovalChangeControlRequirement.APPROVAL_DECISION_STATE ->
                setOf(SUBJECT, APPROVED_DECISION, REJECTED_DECISION)
            HumanApprovalChangeControlRequirement.AUTHORIZED_APPROVER_SET ->
                setOf(SUBJECT, APPROVERS)
            HumanApprovalChangeControlRequirement.APPROVAL_QUORUM ->
                setOf(SUBJECT, QUORUM)
            HumanApprovalChangeControlRequirement.SEPARATION_OF_DUTIES ->
                setOf(SUBJECT, REQUESTER, PROHIBIT_SELF_APPROVAL)
            HumanApprovalChangeControlRequirement.REVISION_BOUND_APPROVAL ->
                setOf(SUBJECT, APPROVED_REVISION, CHANGED_REVISION)
            HumanApprovalChangeControlRequirement.WHOLE_CHANGESET_APPROVAL_COVERAGE ->
                setOf(SUBJECT, PROTECTED_OPERATION, UNPROTECTED_OPERATION)
            HumanApprovalChangeControlRequirement.REVISION_BOUND_CHANGE_EXECUTION ->
                setOf(SUBJECT, PLANNED_REVISION, PLANNED_BASE_REVISION, EXECUTION_BASE_REVISION)
        }
        require(fact.values.keys.containsAll(required)) {
            "EF-09 case '$caseId' fact '${fact.id}' is missing required semantic values " +
                (required - fact.values.keys).sorted() + "."
        }
        require(fact.values.keys.all { it in required }) {
            "EF-09 case '$caseId' fact '${fact.id}' contains unsupported semantic value keys " +
                (fact.values.keys - required).sorted() + "."
        }
        if (fact.requirement == HumanApprovalChangeControlRequirement.SEPARATION_OF_DUTIES) {
            require(fact.values.getValue(PROHIBIT_SELF_APPROVAL).toBooleanStrictOrNull() != null) {
                "EF-09 case '$caseId' fact '${fact.id}' must use a boolean prohibitSelfApproval value."
            }
        }
    }

    private fun classify(
        caseId: String,
        fact: HumanApprovalChangeControlFactDeclaration
    ): HumanApprovalChangeControlFinding {
        val classification = when (fact.requirement) {
            HumanApprovalChangeControlRequirement.APPROVAL_REQUIREMENT_DECLARATION ->
                approvalRequirementDeclaration(fact.values)
            HumanApprovalChangeControlRequirement.ORDERED_APPROVAL_GATE ->
                orderedApprovalGate(fact.values)
            HumanApprovalChangeControlRequirement.APPROVAL_DECISION_STATE ->
                approvalDecisionState(fact.values)
            HumanApprovalChangeControlRequirement.AUTHORIZED_APPROVER_SET ->
                authorizedApproverSet(fact.values)
            HumanApprovalChangeControlRequirement.APPROVAL_QUORUM ->
                approvalQuorum(fact.values)
            HumanApprovalChangeControlRequirement.SEPARATION_OF_DUTIES ->
                separationOfDuties(fact.values)
            HumanApprovalChangeControlRequirement.REVISION_BOUND_APPROVAL ->
                revisionBoundApproval(fact.values)
            HumanApprovalChangeControlRequirement.WHOLE_CHANGESET_APPROVAL_COVERAGE ->
                wholeChangesetCoverage(fact.values)
            HumanApprovalChangeControlRequirement.REVISION_BOUND_CHANGE_EXECUTION ->
                revisionBoundExecution(fact.values)
        }
        return HumanApprovalChangeControlFinding(
            caseId = caseId,
            factId = fact.id,
            observationRef = fact.observationRef,
            requirement = fact.requirement,
            outcome = classification.first,
            reason = classification.second
        )
    }

    private fun approvalRequirementDeclaration(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val subject = values.getValue(SUBJECT)
        val message = values.getValue(MESSAGE)
        val observed = validate(
            steps = listOf(changeStep(CHANGE_STEP, "change", "protected-target")),
            policies = listOf(approvalPolicy(subject, message))
        )
        val alternate = validate(
            steps = listOf(changeStep(CHANGE_STEP, "change", "protected-target")),
            policies = listOf(approvalPolicy("$subject-alternate", "$message alternate"))
        )
        val requirement = approvalRequirement(observed, subject)
        val alternateRequirement = approvalRequirement(alternate, "$subject-alternate")
        val evidence = requirement?.let { evidenceFor(observed, it) }
        val preserved =
            !observed.valid &&
                requirement != null &&
                requirement.scope.kind == ControlRequirementScopeKind.INTENT &&
                requirement.source == ControlRequirementSource.INTENT_POLICY &&
                requirement.message == message &&
                evidence?.status == ControlEvidenceStatus.UNKNOWN &&
                observed.controlAssessment.decision.status == ControlDecisionStatus.BLOCKED &&
                observed.issues.any { it.code == "SAFETY_REQUIRES_APPROVAL" } &&
                alternateRequirement != null &&
                alternateRequirement.id != requirement.id &&
                alternateRequirement.subject != requirement.subject

        return outcome(
            preserved,
            "IntentPolicyType.APPROVAL creates a typed, intent-scoped blocking approval requirement and fails closed when no approval mechanism is authored.",
            "The current public control contract does not preserve a typed blocking approval requirement independently from a product review system."
        )
    }

    private fun orderedApprovalGate(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val subject = values.getValue(SUBJECT)
        val message = values.getValue(MESSAGE)
        val protected = validate(
            steps = listOf(
                approvalStep(message),
                changeStep(
                    id = CHANGE_STEP,
                    resource = values.getValue(RESOURCE),
                    target = values.getValue(TARGET),
                    requires = listOf(APPROVAL_STEP)
                )
            ),
            policies = listOf(approvalPolicy(subject, message))
        )
        val disconnected = validate(
            steps = listOf(
                approvalStep(message),
                changeStep(
                    id = CHANGE_STEP,
                    resource = values.getValue(RESOURCE),
                    target = values.getValue(TARGET)
                )
            ),
            policies = listOf(approvalPolicy(subject, message))
        )
        val requirement = approvalRequirement(protected, subject)
        val disconnectedRequirement = approvalRequirement(disconnected, subject)
        val protectedEvidence = requirement?.let { evidenceFor(protected, it) }
        val disconnectedEvidence = disconnectedRequirement?.let {
            evidenceFor(disconnected, it)
        }
        val protectedChange = canonicalStep(protected, CHANGE_STEP)
        val approval = canonicalStep(protected, APPROVAL_STEP)
        val preserved =
            protected.valid &&
                requirement != null &&
                protectedEvidence?.status == ControlEvidenceStatus.SATISFIED &&
                protected.controlAssessment.decision.status == ControlDecisionStatus.ALLOWED &&
                approval.params[MESSAGE] == IntentString(message) &&
                protectedChange.requires == listOf(APPROVAL_STEP) &&
                !disconnected.valid &&
                disconnectedEvidence?.status == ControlEvidenceStatus.UNKNOWN &&
                disconnected.controlAssessment.decision.status == ControlDecisionStatus.BLOCKED

        return outcome(
            preserved,
            "A canonical APPROVE predecessor is preserved as an ordered approval mechanism protecting the change, while a disconnected gate fails closed.",
            "The current intent graph does not preserve an approval gate as a reachable precondition of the protected change."
        )
    }

    private fun approvalDecisionState(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val subject = values.getValue(SUBJECT)
        val approved = validateDecision(
            subject = subject,
            decision = values.getValue(APPROVED_DECISION)
        )
        val rejected = validateDecision(
            subject = subject,
            decision = values.getValue(REJECTED_DECISION)
        )
        val approvedRequirement = approvalRequirement(approved, subject)
        val rejectedRequirement = approvalRequirement(rejected, subject)
        val approvedEvidence = approvedRequirement?.let { evidenceFor(approved, it) }
        val rejectedEvidence = rejectedRequirement?.let { evidenceFor(rejected, it) }
        val approvedStep = projection(approved, APPROVAL_STEP)
        val rejectedStep = projection(rejected, APPROVAL_STEP)
        val preserved =
            approved.valid &&
                approvedStep.semanticParametersAccepted &&
                rejectedStep.semanticParametersAccepted &&
                approvedStep.params[DECISION] == IntentString(values.getValue(APPROVED_DECISION)) &&
                rejectedStep.params[DECISION] == IntentString(values.getValue(REJECTED_DECISION)) &&
                approvedStep.params != rejectedStep.params &&
                approvedEvidence?.status == ControlEvidenceStatus.SATISFIED &&
                approved.controlAssessment.decision.status == ControlDecisionStatus.ALLOWED &&
                rejectedEvidence?.status == ControlEvidenceStatus.UNSATISFIED &&
                rejected.controlAssessment.decision.status == ControlDecisionStatus.BLOCKED

        return outcome(
            preserved,
            "Canonical approval evidence distinguishes approved from rejected decisions and blocks the rejected change.",
            "APPROVE exposes no typed decision state; an authored gate is treated as satisfied regardless of whether the human decision is approved or rejected."
        )
    }

    private fun authorizedApproverSet(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val subject = values.getValue(SUBJECT)
        val authored = values.getValue(APPROVERS)
        val alternate = "$authored-alternate"
        val observed = validateGateConfiguration(subject, mapOf(APPROVERS to IntentString(authored)))
        val alternateReport = validateGateConfiguration(
            subject,
            mapOf(APPROVERS to IntentString(alternate))
        )
        val observedStep = projection(observed, APPROVAL_STEP)
        val alternateStep = projection(alternateReport, APPROVAL_STEP)
        val preserved =
            observed.valid &&
                alternateReport.valid &&
                observedStep.semanticParametersAccepted &&
                alternateStep.semanticParametersAccepted &&
                observedStep.params[APPROVERS] == IntentString(authored) &&
                alternateStep.params[APPROVERS] == IntentString(alternate) &&
                observedStep.params != alternateStep.params

        return outcome(
            preserved,
            "APPROVE preserves the authorized approver set as typed gate configuration distinguishable from an alternate authority set.",
            "The current APPROVE contract accepts only a message and cannot preserve which user, team, role or ownership set may authorize the change."
        )
    }

    private fun approvalQuorum(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val subject = values.getValue(SUBJECT)
        val authored = values.getValue(QUORUM)
        val alternate = "$authored-alternate"
        val observed = validateGateConfiguration(subject, mapOf(QUORUM to IntentString(authored)))
        val alternateReport = validateGateConfiguration(
            subject,
            mapOf(QUORUM to IntentString(alternate))
        )
        val observedStep = projection(observed, APPROVAL_STEP)
        val alternateStep = projection(alternateReport, APPROVAL_STEP)
        val preserved =
            observed.valid &&
                alternateReport.valid &&
                observedStep.semanticParametersAccepted &&
                alternateStep.semanticParametersAccepted &&
                observedStep.params[QUORUM] == IntentString(authored) &&
                alternateStep.params[QUORUM] == IntentString(alternate) &&
                observedStep.params != alternateStep.params

        return outcome(
            preserved,
            "APPROVE preserves the required approval quorum as typed gate configuration distinguishable from a different quorum.",
            "The current control model records only the presence of an approval gate and cannot preserve the required number of independent approvals."
        )
    }

    private fun separationOfDuties(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val subject = values.getValue(SUBJECT)
        val requester = values.getValue(REQUESTER)
        val prohibitSelfApproval = values.getValue(PROHIBIT_SELF_APPROVAL).toBooleanStrict()
        val constrained = validateGateConfiguration(
            subject,
            mapOf(
                REQUESTER to IntentString(requester),
                PROHIBIT_SELF_APPROVAL to IntentBoolean(prohibitSelfApproval)
            )
        )
        val unconstrained = validateGateConfiguration(
            subject,
            mapOf(
                REQUESTER to IntentString(requester),
                PROHIBIT_SELF_APPROVAL to IntentBoolean(!prohibitSelfApproval)
            )
        )
        val constrainedStep = projection(constrained, APPROVAL_STEP)
        val unconstrainedStep = projection(unconstrained, APPROVAL_STEP)
        val preserved =
            constrained.valid &&
                unconstrained.valid &&
                constrainedStep.semanticParametersAccepted &&
                unconstrainedStep.semanticParametersAccepted &&
                constrainedStep.params[REQUESTER] == IntentString(requester) &&
                constrainedStep.params[PROHIBIT_SELF_APPROVAL] ==
                IntentBoolean(prohibitSelfApproval) &&
                unconstrainedStep.params[PROHIBIT_SELF_APPROVAL] ==
                IntentBoolean(!prohibitSelfApproval) &&
                constrainedStep.params != unconstrainedStep.params

        return outcome(
            preserved,
            "APPROVE preserves the requester identity and self-approval prohibition as distinguishable separation-of-duties policy.",
            "The current approval gate has no typed requester or self-approval constraint, so it cannot preserve that the change author is ineligible to approve."
        )
    }

    private fun revisionBoundApproval(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val subject = values.getValue(SUBJECT)
        val approvedRevision = values.getValue(APPROVED_REVISION)
        val changedRevision = values.getValue(CHANGED_REVISION)
        val matching = validateRevisionBoundApproval(
            subject = subject,
            approvedRevision = approvedRevision,
            changeRevision = approvedRevision
        )
        val stale = validateRevisionBoundApproval(
            subject = subject,
            approvedRevision = approvedRevision,
            changeRevision = changedRevision
        )
        val matchingApproval = projection(matching, APPROVAL_STEP)
        val matchingChange = projection(matching, CHANGE_STEP)
        val staleApproval = projection(stale, APPROVAL_STEP)
        val staleChange = projection(stale, CHANGE_STEP)
        val preserved =
            matching.valid &&
                matchingApproval.semanticParametersAccepted &&
                matchingChange.semanticParametersAccepted &&
                staleApproval.semanticParametersAccepted &&
                staleChange.semanticParametersAccepted &&
                matchingApproval.params[APPROVED_REVISION] == IntentString(approvedRevision) &&
                matchingChange.params[REVISION] == IntentString(approvedRevision) &&
                staleChange.params[REVISION] == IntentString(changedRevision) &&
                matching.controlAssessment.decision.status == ControlDecisionStatus.ALLOWED &&
                (!stale.valid ||
                    stale.controlAssessment.decision.status != ControlDecisionStatus.ALLOWED)

        return outcome(
            preserved,
            "Approval is bound to the reviewed change revision and a stale revision fails closed until reapproval.",
            "The current model has no typed relation between an approval and the reviewed revision; changing the protected content does not invalidate the static APPROVE gate."
        )
    }

    private fun wholeChangesetCoverage(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val subject = values.getValue(SUBJECT)
        val first = values.getValue(PROTECTED_OPERATION)
        val second = values.getValue(UNPROTECTED_OPERATION)
        val fullyProtected = validate(
            steps = listOf(
                approvalStep("Approve complete change set"),
                changeStep(first, first, "target-one", listOf(APPROVAL_STEP)),
                changeStep(second, second, "target-two", listOf(APPROVAL_STEP))
            ),
            policies = listOf(approvalPolicy(subject, "Approve complete change set"))
        )
        val partiallyProtected = validate(
            steps = listOf(
                approvalStep("Approve complete change set"),
                changeStep(first, first, "target-one", listOf(APPROVAL_STEP)),
                changeStep(second, second, "target-two")
            ),
            policies = listOf(approvalPolicy(subject, "Approve complete change set"))
        )
        val firstFull = canonicalStep(fullyProtected, first)
        val secondFull = canonicalStep(fullyProtected, second)
        val firstPartial = canonicalStep(partiallyProtected, first)
        val secondPartial = canonicalStep(partiallyProtected, second)
        val preserved =
            fullyProtected.valid &&
                fullyProtected.controlAssessment.decision.status == ControlDecisionStatus.ALLOWED &&
                firstFull.requires == listOf(APPROVAL_STEP) &&
                secondFull.requires == listOf(APPROVAL_STEP) &&
                firstPartial.requires == listOf(APPROVAL_STEP) &&
                secondPartial.requires.isEmpty() &&
                (!partiallyProtected.valid ||
                    partiallyProtected.controlAssessment.decision.status !=
                    ControlDecisionStatus.ALLOWED)

        return outcome(
            preserved,
            "Intent-wide approval protects every authored change operation and fails closed when any operation is outside the approved change set.",
            "Intent-wide approval is currently accepted when the gate precedes only one of multiple change operations, so partial coverage masquerades as whole-change authorization."
        )
    }

    private fun revisionBoundExecution(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val subject = values.getValue(SUBJECT)
        val plannedRevision = values.getValue(PLANNED_REVISION)
        val plannedBase = values.getValue(PLANNED_BASE_REVISION)
        val executionBase = values.getValue(EXECUTION_BASE_REVISION)
        val matching = validateExecutionRevision(
            subject = subject,
            plannedRevision = plannedRevision,
            plannedBase = plannedBase,
            executionBase = plannedBase
        )
        val diverged = validateExecutionRevision(
            subject = subject,
            plannedRevision = plannedRevision,
            plannedBase = plannedBase,
            executionBase = executionBase
        )
        val matchingApproval = projection(matching, APPROVAL_STEP)
        val matchingChange = projection(matching, CHANGE_STEP)
        val divergedApproval = projection(diverged, APPROVAL_STEP)
        val divergedChange = projection(diverged, CHANGE_STEP)
        val preserved =
            matching.valid &&
                matchingApproval.semanticParametersAccepted &&
                matchingChange.semanticParametersAccepted &&
                divergedApproval.semanticParametersAccepted &&
                divergedChange.semanticParametersAccepted &&
                matchingChange.params[PLANNED_REVISION] == IntentString(plannedRevision) &&
                matchingChange.params[PLANNED_BASE_REVISION] == IntentString(plannedBase) &&
                matchingChange.params[EXECUTION_BASE_REVISION] == IntentString(plannedBase) &&
                divergedChange.params[EXECUTION_BASE_REVISION] == IntentString(executionBase) &&
                matching.controlAssessment.decision.status == ControlDecisionStatus.ALLOWED &&
                (!diverged.valid ||
                    diverged.controlAssessment.decision.status != ControlDecisionStatus.ALLOWED)

        return outcome(
            preserved,
            "Execution is bound to the planned source and base revisions and fails closed when the execution base diverges.",
            "The current intent/control model has no typed planned-versus-execution revision relation, so approval ordering cannot prove that the reviewed plan is the change actually executed."
        )
    }

    private fun validateDecision(subject: String, decision: String): IntentValidationReport =
        validate(
            steps = listOf(
                approvalStep(
                    message = "Approve protected change",
                    extraParams = mapOf(DECISION to IntentString(decision))
                ),
                changeStep(
                    id = CHANGE_STEP,
                    resource = "protected-change",
                    target = "production",
                    requires = listOf(APPROVAL_STEP)
                )
            ),
            policies = listOf(approvalPolicy(subject, "Approve protected change"))
        )

    private fun validateGateConfiguration(
        subject: String,
        extraParams: Map<String, IntentValue>
    ): IntentValidationReport = validate(
        steps = listOf(
            approvalStep(
                message = "Approve protected change",
                extraParams = extraParams
            ),
            changeStep(
                id = CHANGE_STEP,
                resource = "protected-change",
                target = "production",
                requires = listOf(APPROVAL_STEP)
            )
        ),
        policies = listOf(approvalPolicy(subject, "Approve protected change"))
    )

    private fun validateRevisionBoundApproval(
        subject: String,
        approvedRevision: String,
        changeRevision: String
    ): IntentValidationReport = validate(
        steps = listOf(
            approvalStep(
                message = "Approve protected change",
                extraParams = mapOf(
                    APPROVED_REVISION to IntentString(approvedRevision)
                )
            ),
            changeStep(
                id = CHANGE_STEP,
                resource = "protected-change",
                target = "production",
                requires = listOf(APPROVAL_STEP),
                extraParams = mapOf(REVISION to IntentString(changeRevision))
            )
        ),
        policies = listOf(approvalPolicy(subject, "Approve protected change"))
    )

    private fun validateExecutionRevision(
        subject: String,
        plannedRevision: String,
        plannedBase: String,
        executionBase: String
    ): IntentValidationReport = validate(
        steps = listOf(
            approvalStep(
                message = "Approve planned infrastructure change",
                extraParams = mapOf(
                    APPROVED_REVISION to IntentString(plannedRevision)
                )
            ),
            changeStep(
                id = CHANGE_STEP,
                resource = "infrastructure-plan",
                target = "production",
                requires = listOf(APPROVAL_STEP),
                extraParams = mapOf(
                    PLANNED_REVISION to IntentString(plannedRevision),
                    PLANNED_BASE_REVISION to IntentString(plannedBase),
                    EXECUTION_BASE_REVISION to IntentString(executionBase)
                )
            )
        ),
        policies = listOf(approvalPolicy(subject, "Approve planned infrastructure change"))
    )

    private fun validate(
        steps: List<IntentStep>,
        policies: List<IntentPolicy>
    ): IntentValidationReport = IntentCapabilityValidator().validate(
        IntentDocument(
            name = "ef09-approval-change-control",
            workflows = listOf(
                IntentWorkflow(
                    name = WORKFLOW,
                    kind = IntentWorkflowKind.PROVISION,
                    steps = steps
                )
            ),
            policies = policies
        )
    )

    private fun approvalPolicy(subject: String, message: String) = IntentPolicy(
        name = subject,
        type = IntentPolicyType.APPROVAL,
        message = message
    )

    private fun approvalStep(
        message: String,
        extraParams: Map<String, IntentValue> = emptyMap()
    ) = IntentStep(
        id = APPROVAL_STEP,
        capability = StandardCapability.APPROVE,
        params = mapOf(MESSAGE to IntentString(message)) + extraParams
    )

    private fun changeStep(
        id: String,
        resource: String,
        target: String,
        requires: List<String> = emptyList(),
        extraParams: Map<String, IntentValue> = emptyMap()
    ) = IntentStep(
        id = id,
        capability = StandardCapability.PROVISION,
        requires = requires,
        params = mapOf(
            RESOURCE to IntentString(resource),
            TARGET to IntentString(target)
        ) + extraParams
    )

    private fun approvalRequirement(
        report: IntentValidationReport,
        subject: String
    ): ControlRequirement? = report.meaning.controlRequirements.singleOrNull {
        it.kind == ControlRequirementKind.APPROVAL && it.subject == subject
    }

    private fun evidenceFor(
        report: IntentValidationReport,
        requirement: ControlRequirement
    ) = report.controlAssessment.evidence.singleOrNull {
        it.requirementId == requirement.id
    }

    private fun canonicalStep(
        report: IntentValidationReport,
        stepId: String
    ) = report.meaning.workflows.single().steps.single { it.id == stepId }

    private fun projection(
        report: IntentValidationReport,
        stepId: String
    ): StepProjection {
        val step = canonicalStep(report, stepId)
        val semanticErrors = report.issues.filter { issue ->
            issue.level == "error" &&
                issue.code in SEMANTIC_PARAMETER_ERROR_CODES &&
                issue.message.contains("'$stepId'")
        }
        return StepProjection(
            semanticParametersAccepted = semanticErrors.isEmpty(),
            params = step.params,
            requires = step.requires
        )
    }

    private fun outcome(
        preserved: Boolean,
        success: String,
        gap: String
    ): Pair<ExternalFalsificationOutcome, String> =
        if (preserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to success
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to gap
        }

    private fun requireUnique(caseId: String, label: String, values: List<String>) {
        require(values.distinct().size == values.size) {
            "EF-09 case '$caseId' contains duplicate $label values."
        }
    }

    private fun <T> readStrict(file: File, type: Class<T>): T = try {
        FlowYaml.readStrict(file, type)
    } catch (error: FlowYamlException) {
        throw IllegalArgumentException(
            "Invalid EF-09 assessment '${file.path}': " +
                (error.message ?: error.javaClass.simpleName),
            error
        )
    }

    private data class StepProjection(
        val semanticParametersAccepted: Boolean,
        val params: Map<String, IntentValue>,
        val requires: List<String>
    )

    companion object {
        const val DOMAIN = "human-approval-change-control"
        const val KIND = "FlowHumanApprovalChangeControlFalsification"
        const val VERSION = "1.0"
        const val ASSESSMENT_FILE = "ef09.yaml"
        const val MIN_CASES = 6
        const val MIN_REPOSITORIES = 2

        private const val WORKFLOW = "main"
        private const val APPROVAL_STEP = "approval"
        private const val CHANGE_STEP = "change"
        private const val SUBJECT = "subject"
        private const val MESSAGE = "message"
        private const val RESOURCE = "resource"
        private const val TARGET = "target"
        private const val APPROVED_DECISION = "approvedDecision"
        private const val REJECTED_DECISION = "rejectedDecision"
        private const val DECISION = "decision"
        private const val APPROVERS = "approvers"
        private const val QUORUM = "quorum"
        private const val REQUESTER = "requester"
        private const val PROHIBIT_SELF_APPROVAL = "prohibitSelfApproval"
        private const val APPROVED_REVISION = "approvedRevision"
        private const val CHANGED_REVISION = "changedRevision"
        private const val REVISION = "revision"
        private const val PROTECTED_OPERATION = "protectedOperation"
        private const val UNPROTECTED_OPERATION = "unprotectedOperation"
        private const val PLANNED_REVISION = "plannedRevision"
        private const val PLANNED_BASE_REVISION = "plannedBaseRevision"
        private const val EXECUTION_BASE_REVISION = "executionBaseRevision"

        private val REQUIRED_REQUIREMENTS =
            HumanApprovalChangeControlRequirement.values().toSet()
        private val SEMANTIC_PARAMETER_ERROR_CODES =
            setOf("UNKNOWN_STEP_PARAM", "MISSING_REQUIRED_STEP_PARAM")
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}
