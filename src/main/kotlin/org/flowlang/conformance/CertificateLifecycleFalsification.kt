package org.flowlang.conformance

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

enum class CertificateLifecycleRequirement {
    NAMED_CERTIFICATE_RENEWAL,
    RENEWAL_WINDOW,
    CERTIFICATE_SECRET_TARGET,
    CERTIFICATE_SUBJECT_IDENTITIES,
    ISSUER_OWNERSHIP,
    PRIVATE_KEY_ROTATION_POLICY,
    CERTIFICATE_REVOCATION_WITH_REASON
}

data class CertificateLifecycleFactDeclaration(
    val id: String,
    val observationRef: String,
    val requirement: CertificateLifecycleRequirement,
    val values: Map<String, String>
)

data class CertificateLifecycleCaseAssessment(
    val kind: String,
    val version: String,
    val caseId: String,
    val facts: List<CertificateLifecycleFactDeclaration>
)

data class CertificateLifecycleFinding(
    val caseId: String,
    val factId: String,
    val observationRef: String,
    val requirement: CertificateLifecycleRequirement,
    val outcome: ExternalFalsificationOutcome,
    val reason: String
)

data class CertificateLifecycleFalsificationReport(
    val caseCount: Int,
    val distinctRepositoryCount: Int,
    val findings: List<CertificateLifecycleFinding>
) {
    val representableCount: Int get() = findings.count { it.outcome == ExternalFalsificationOutcome.REPRESENTABLE }
    val modelGapCount: Int get() = findings.count { it.outcome == ExternalFalsificationOutcome.MODEL_GAP }
}

/**
 * EF-05 evaluator for externally grounded certificate-lifecycle facts.
 *
 * Product syntax is translated only by reviewed case assessments into bounded target-neutral
 * requirements. Classification then crosses the production intent validator and canonical meaning
 * boundary. Existing semantics are tested before any repair is allowed; a MODEL_GAP is evidence,
 * never permission to smuggle cert-manager, Certbot, ACME or Kubernetes vocabulary into Core.
 */
class CertificateLifecycleFalsification(private val rootDir: File = File(".")) {
    fun evaluate(): CertificateLifecycleFalsificationReport {
        val corpus = ExternalCorpusLoader(rootDir).load()
        require(corpus.manifest.status == ExternalCorpusStatus.EVIDENCE_ACTIVE) {
            "EF-05 requires an EVIDENCE_ACTIVE external corpus."
        }
        val cases = corpus.cases.filter { it.definition.domain == DOMAIN }
        require(cases.size >= MIN_CASES) {
            "EF-05 requires at least $MIN_CASES certificate-lifecycle evidence cases."
        }
        val repositoryCount = cases.map { it.definition.provenance.repository }.distinct().size
        require(repositoryCount >= MIN_REPOSITORIES) {
            "EF-05 requires evidence from at least $MIN_REPOSITORIES independent repositories."
        }

        val findings = cases.flatMap { loadedCase ->
            val assessment = loadAssessment(loadedCase)
            assessment.facts.map { fact -> classify(loadedCase.definition.id, fact) }
        }
        require(findings.isNotEmpty()) { "EF-05 must classify at least one semantic fact." }

        val coveredRequirements = findings.map { it.requirement }.toSet()
        val missingRequirements = REQUIRED_REQUIREMENTS - coveredRequirements
        require(missingRequirements.isEmpty()) {
            "EF-05 corpus does not exercise required semantic shapes: ${missingRequirements.sortedBy { it.name }.joinToString()}."
        }

        return CertificateLifecycleFalsificationReport(cases.size, repositoryCount, findings)
    }

    private fun loadAssessment(loadedCase: LoadedExternalCorpusCase): CertificateLifecycleCaseAssessment {
        val file = File(loadedCase.directory, ASSESSMENT_FILE)
        require(file.isFile) { "Missing EF-05 assessment for case '${loadedCase.definition.id}': ${file.path}" }
        val assessment = readStrict(file, CertificateLifecycleCaseAssessment::class.java)
        require(assessment.kind == KIND && assessment.version == VERSION) {
            "EF-05 assessment for '${loadedCase.definition.id}' must use $KIND version $VERSION."
        }
        require(assessment.caseId == loadedCase.definition.id) {
            "EF-05 assessment caseId '${assessment.caseId}' does not match '${loadedCase.definition.id}'."
        }
        require(assessment.facts.isNotEmpty()) {
            "EF-05 assessment for '${assessment.caseId}' must declare semantic facts."
        }
        requireUnique(assessment.caseId, "fact", assessment.facts.map { it.id })
        requireUnique(assessment.caseId, "observation reference", assessment.facts.map { it.observationRef })

        val observations = loadedCase.definition.expectedSemanticObservations.associateBy { it.id }
        assessment.facts.forEach { fact ->
            require(ID_PATTERN.matches(fact.id)) {
                "EF-05 case '${assessment.caseId}' contains invalid fact id '${fact.id}'."
            }
            val observation = observations[fact.observationRef]
                ?: throw IllegalArgumentException(
                    "EF-05 case '${assessment.caseId}' fact '${fact.id}' references unknown semantic observation '${fact.observationRef}'."
                )
            require(observation.expectation == ExternalSemanticExpectation.PRESERVE) {
                "EF-05 case '${assessment.caseId}' fact '${fact.id}' must reference a PRESERVE semantic observation."
            }
            validateValues(assessment.caseId, fact)
        }

        val preservationObservations = loadedCase.definition.expectedSemanticObservations
            .filter { it.expectation == ExternalSemanticExpectation.PRESERVE }
            .map { it.id }
            .toSet()
        val covered = assessment.facts.map { it.observationRef }.toSet()
        require(covered == preservationObservations) {
            "EF-05 case '${assessment.caseId}' must classify every PRESERVE semantic observation exactly once; expected=${preservationObservations.sorted()} actual=${covered.sorted()}."
        }
        return assessment
    }

    private fun validateValues(caseId: String, fact: CertificateLifecycleFactDeclaration) {
        require(fact.values.values.none(String::isBlank)) {
            "EF-05 case '$caseId' fact '${fact.id}' contains a blank semantic value."
        }

        val required: Set<String>
        val allowed: Set<String>
        when (fact.requirement) {
            CertificateLifecycleRequirement.NAMED_CERTIFICATE_RENEWAL -> {
                required = setOf(CERTIFICATE)
                allowed = required
            }
            CertificateLifecycleRequirement.RENEWAL_WINDOW -> {
                required = setOf(CERTIFICATE, WINDOW)
                allowed = required
            }
            CertificateLifecycleRequirement.CERTIFICATE_SECRET_TARGET -> {
                required = setOf(CERTIFICATE, SECRET)
                allowed = required
            }
            CertificateLifecycleRequirement.CERTIFICATE_SUBJECT_IDENTITIES -> {
                required = setOf(CERTIFICATE, IDENTITIES)
                allowed = required
            }
            CertificateLifecycleRequirement.ISSUER_OWNERSHIP -> {
                required = setOf(CERTIFICATE, ISSUER)
                allowed = required
            }
            CertificateLifecycleRequirement.PRIVATE_KEY_ROTATION_POLICY -> {
                required = setOf(CERTIFICATE, ROTATION_POLICY)
                allowed = required
            }
            CertificateLifecycleRequirement.CERTIFICATE_REVOCATION_WITH_REASON -> {
                required = setOf(CERTIFICATE, REASON)
                allowed = required
            }
        }

        require(fact.values.keys.containsAll(required)) {
            "EF-05 case '$caseId' fact '${fact.id}' is missing required semantic values ${(required - fact.values.keys).sorted()}."
        }
        require(fact.values.keys.all { it in allowed }) {
            "EF-05 case '$caseId' fact '${fact.id}' contains unsupported semantic value keys ${(fact.values.keys - allowed).sorted()}."
        }
    }

    private fun classify(
        caseId: String,
        fact: CertificateLifecycleFactDeclaration
    ): CertificateLifecycleFinding {
        val classification = when (fact.requirement) {
            CertificateLifecycleRequirement.NAMED_CERTIFICATE_RENEWAL ->
                namedRenewalClassification(fact.values)
            CertificateLifecycleRequirement.RENEWAL_WINDOW ->
                renewalWindowClassification(fact.values)
            CertificateLifecycleRequirement.CERTIFICATE_SECRET_TARGET ->
                certificateSecretTargetClassification(fact.values)
            CertificateLifecycleRequirement.CERTIFICATE_SUBJECT_IDENTITIES ->
                subjectIdentitiesClassification(fact.values)
            CertificateLifecycleRequirement.ISSUER_OWNERSHIP ->
                issuerOwnershipClassification(fact.values)
            CertificateLifecycleRequirement.PRIVATE_KEY_ROTATION_POLICY ->
                privateKeyRotationClassification(fact.values)
            CertificateLifecycleRequirement.CERTIFICATE_REVOCATION_WITH_REASON ->
                certificateRevocationClassification(fact.values)
        }
        return CertificateLifecycleFinding(
            caseId = caseId,
            factId = fact.id,
            observationRef = fact.observationRef,
            requirement = fact.requirement,
            outcome = classification.first,
            reason = classification.second
        )
    }

    private fun namedRenewalClassification(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> =
        certificateRenewalClassification(
            values,
            "CERTIFICATE_RENEW preserves the authored certificate identity and certificate-update effect without depending on Certbot command syntax.",
            "CERTIFICATE_RENEW does not preserve the authored named-certificate renewal through the public canonical intent contract."
        )

    private fun renewalWindowClassification(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> =
        certificateRenewalClassification(
            values,
            "CERTIFICATE_RENEW preserves the authored renewal window as target-neutral lifecycle meaning.",
            "CERTIFICATE_RENEW does not preserve the authored renewal timing through the public canonical intent contract."
        )

    private fun certificateSecretTargetClassification(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> =
        certificateRenewalClassification(
            values,
            "CERTIFICATE_RENEW preserves the authored certificate-material target through its neutral secret parameter without promoting Kubernetes Secret to a capability.",
            "CERTIFICATE_RENEW does not preserve the authored certificate-material storage target through canonical intent meaning."
        )

    private fun certificateRenewalClassification(
        values: Map<String, String>,
        successReason: String,
        gapReason: String
    ): Pair<ExternalFalsificationOutcome, String> {
        val projection = semanticProjection(StandardCapability.CERTIFICATE_RENEW, values)
        val preserved = projection.valid &&
            parametersPreserved(projection, values) &&
            hasEffect(
                projection,
                EffectDomain.INFRASTRUCTURE_STATE,
                EffectOperation.UPDATE,
                CERTIFICATE_RESOURCE
            )
        return if (preserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to successReason
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to gapReason
        }
    }

    private fun subjectIdentitiesClassification(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val semanticValues = mapOf(
            CERTIFICATE to values.getValue(CERTIFICATE),
            IDENTITIES_PARAM to values.getValue(IDENTITIES)
        )
        val observed = semanticProjection(StandardCapability.CERTIFICATE_RENEW, semanticValues)
        if (!observed.semanticParametersAccepted) {
            return ExternalFalsificationOutcome.MODEL_GAP to
                "CERTIFICATE_RENEW exposes no typed target-neutral certificate-identity set; canonical validation rejects the authored identities rather than hiding them inside the certificate label."
        }

        val alternate = semanticProjection(
            StandardCapability.CERTIFICATE_RENEW,
            semanticValues + (IDENTITIES_PARAM to ALTERNATE_IDENTITIES)
        )
        val preserved = observed.valid &&
            alternate.valid &&
            parametersPreserved(observed, semanticValues) &&
            alternate.params[IDENTITIES_PARAM] == IntentString(ALTERNATE_IDENTITIES) &&
            observed.params != alternate.params &&
            hasEffect(
                observed,
                EffectDomain.INFRASTRUCTURE_STATE,
                EffectOperation.UPDATE,
                CERTIFICATE_RESOURCE
            )

        return if (preserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "CERTIFICATE_RENEW preserves the authored certificate identities as semantically distinguishable canonical meaning."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "Certificate identity data is accepted without preserving the authored identity set as semantically distinguishable canonical meaning."
        }
    }

    private fun issuerOwnershipClassification(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val semanticValues = mapOf(
            CERTIFICATE to values.getValue(CERTIFICATE),
            PROVIDER to values.getValue(ISSUER)
        )
        val projection = semanticProjection(StandardCapability.CERTIFICATE_RENEW, semanticValues)
        val preserved = projection.valid &&
            parametersPreserved(projection, semanticValues) &&
            hasEffect(
                projection,
                EffectDomain.INFRASTRUCTURE_STATE,
                EffectOperation.UPDATE,
                CERTIFICATE_RESOURCE
            )

        return if (preserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "CERTIFICATE_RENEW preserves issuer ownership through the existing neutral provider parameter; cert-manager issuer kind and scope remain implementation details."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "The current certificate contract cannot preserve the authority responsible for certificate issuance without product-specific vocabulary."
        }
    }

    private fun privateKeyRotationClassification(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val semanticValues = mapOf(
            CERTIFICATE to values.getValue(CERTIFICATE),
            PRIVATE_KEY_ROTATION_POLICY_PARAM to values.getValue(ROTATION_POLICY)
        )
        val observed = semanticProjection(StandardCapability.CERTIFICATE_RENEW, semanticValues)
        if (!observed.semanticParametersAccepted) {
            return ExternalFalsificationOutcome.MODEL_GAP to
                "CERTIFICATE_RENEW exposes no typed certificate-specific private-key regeneration policy; generic SECRET_ROTATE cannot substitute for reuse-versus-regenerate meaning during re-issuance."
        }

        val alternate = semanticProjection(
            StandardCapability.CERTIFICATE_RENEW,
            semanticValues + (PRIVATE_KEY_ROTATION_POLICY_PARAM to ALTERNATE_ROTATION_POLICY)
        )
        val preserved = observed.valid &&
            alternate.valid &&
            parametersPreserved(observed, semanticValues) &&
            alternate.params[PRIVATE_KEY_ROTATION_POLICY_PARAM] == IntentString(ALTERNATE_ROTATION_POLICY) &&
            observed.params != alternate.params &&
            hasEffect(
                observed,
                EffectDomain.INFRASTRUCTURE_STATE,
                EffectOperation.UPDATE,
                CERTIFICATE_RESOURCE
            )

        return if (preserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "CERTIFICATE_RENEW preserves certificate-specific private-key regeneration policy as semantically distinguishable canonical meaning."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "Private-key rotation data is accepted without preserving reuse-versus-regenerate policy as certificate lifecycle meaning."
        }
    }

    private fun certificateRevocationClassification(
        values: Map<String, String>
    ): Pair<ExternalFalsificationOutcome, String> {
        val semanticValues = mapOf(
            TARGET to values.getValue(CERTIFICATE),
            REASON_PARAM to values.getValue(REASON)
        )
        val observed = semanticProjection(StandardCapability.DEPROVISION, semanticValues)
        if (!observed.semanticParametersAccepted) {
            return ExternalFalsificationOutcome.MODEL_GAP to
                "DEPROVISION cannot preserve an authored certificate-revocation reason, and generic resource deletion is not authority-level revocation."
        }

        val alternate = semanticProjection(
            StandardCapability.DEPROVISION,
            semanticValues + (REASON_PARAM to ALTERNATE_REVOCATION_REASON)
        )
        val preserved = observed.valid &&
            alternate.valid &&
            parametersPreserved(observed, semanticValues) &&
            alternate.params[REASON_PARAM] == IntentString(ALTERNATE_REVOCATION_REASON) &&
            observed.params != alternate.params &&
            hasEffect(
                observed,
                EffectDomain.INFRASTRUCTURE_STATE,
                EffectOperation.UPDATE,
                CERTIFICATE_REVOCATION_RESOURCE
            )

        return if (preserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "The current model preserves certificate revocation, its reason and an explicit authority-state update distinct from local deletion."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "Certificate revocation data is accepted without a typed authority-state revocation effect; generic DEPROVISION remains semantically different from revocation."
        }
    }

    private fun semanticProjection(
        capability: StandardCapability,
        values: Map<String, String>
    ): SemanticProjection {
        val report = IntentCapabilityValidator().validate(
            IntentDocument(
                name = "ef05-${capability.name.lowercase()}",
                workflows = listOf(
                    IntentWorkflow(
                        name = "main",
                        kind = IntentWorkflowKind.CUSTOM,
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

    private fun parametersPreserved(
        projection: SemanticProjection,
        expected: Map<String, String>
    ): Boolean =
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
            "EF-05 case '$caseId' contains duplicate $label values."
        }
    }

    private fun <T> readStrict(file: File, type: Class<T>): T = try {
        FlowYaml.readStrict(file, type)
    } catch (error: FlowYamlException) {
        throw IllegalArgumentException(
            "Invalid EF-05 assessment '${file.path}': ${error.message ?: error.javaClass.simpleName}",
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
        const val DOMAIN = "certificate-lifecycle"
        const val KIND = "FlowCertificateLifecycleFalsification"
        const val VERSION = "1.0"
        const val ASSESSMENT_FILE = "ef05.yaml"
        const val MIN_CASES = 5
        const val MIN_REPOSITORIES = 2

        private const val CERTIFICATE = "certificate"
        private const val WINDOW = "window"
        private const val SECRET = "secret"
        private const val IDENTITIES = "identities"
        private const val ISSUER = "issuer"
        private const val ROTATION_POLICY = "rotationPolicy"
        private const val REASON = "reason"
        private const val PROVIDER = "provider"
        private const val TARGET = "target"

        private const val IDENTITIES_PARAM = "identities"
        private const val PRIVATE_KEY_ROTATION_POLICY_PARAM = "privateKeyRotationPolicy"
        private const val REASON_PARAM = "reason"
        private const val ALTERNATE_IDENTITIES = "alternate certificate identities"
        private const val ALTERNATE_ROTATION_POLICY = "Never on re-issuance"
        private const val ALTERNATE_REVOCATION_REASON = "superseded"
        private const val CERTIFICATE_RESOURCE = "infrastructure.certificate"
        private const val CERTIFICATE_REVOCATION_RESOURCE = "infrastructure.certificate.revocation"

        private val REQUIRED_REQUIREMENTS = CertificateLifecycleRequirement.values().toSet()
        private val SEMANTIC_PARAMETER_ERROR_CODES = setOf("UNKNOWN_STEP_PARAM", "MISSING_REQUIRED_STEP_PARAM")
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}
