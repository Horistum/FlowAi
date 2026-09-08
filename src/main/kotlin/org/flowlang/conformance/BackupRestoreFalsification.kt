package org.flowlang.conformance

import org.flowlang.modules.ModuleRegistry

import java.io.File
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.effects.RecoveryEffectKind
import org.flowlang.effects.RecoveryEndpointKind
import org.flowlang.effects.SemanticEffect
import org.flowlang.effects.canonicalObservationValue
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException

enum class BackupRestoreRequirement {
    BACKUP_RECOVERY_POINT_CAPTURE,
    RESTORE_FROM_RECOVERY_POINT,
    RESTORE_WITH_SELECTION,
    RESTORE_WITH_IDENTITY_MAPPING
}

data class BackupRestoreFactDeclaration(
    val id: String,
    val observationRef: String,
    val requirement: BackupRestoreRequirement,
    val values: Map<String, String>
)

data class BackupRestoreCaseAssessment(
    val kind: String,
    val version: String,
    val caseId: String,
    val facts: List<BackupRestoreFactDeclaration>
)

data class BackupRestoreFinding(
    val caseId: String,
    val factId: String,
    val observationRef: String,
    val requirement: BackupRestoreRequirement,
    val outcome: ExternalFalsificationOutcome,
    val reason: String
)

data class BackupRestoreFalsificationReport(
    val caseCount: Int,
    val distinctRepositoryCount: Int,
    val findings: List<BackupRestoreFinding>
) {
    val representableCount: Int get() = findings.count { it.outcome == ExternalFalsificationOutcome.REPRESENTABLE }
    val modelGapCount: Int get() = findings.count { it.outcome == ExternalFalsificationOutcome.MODEL_GAP }
}

/**
 * EF-03 evaluator for externally grounded backup and restore facts.
 *
 * The reviewed case assessment is the only boundary that translates product syntax into
 * typed semantic requirements. Classification goes through the production intent validator
 * and its canonical meaning rather than calling an internal effect authority directly.
 * A MODEL_GAP is evidence, not a test failure and never authorization to smuggle product
 * vocabulary into Core.
 */
class BackupRestoreFalsification(private val rootDir: File = File(".")) {
    fun evaluate(): BackupRestoreFalsificationReport {
        val corpus = ExternalCorpusLoader(rootDir).load()
        require(corpus.manifest.status == ExternalCorpusStatus.EVIDENCE_ACTIVE) {
            "EF-03 requires an EVIDENCE_ACTIVE external corpus."
        }
        val cases = corpus.cases.filter { it.definition.domain == DOMAIN }
        require(cases.size >= MIN_CASES) {
            "EF-03 requires at least $MIN_CASES backup/restore evidence cases."
        }
        val repositoryCount = cases.map { it.definition.provenance.repository }.distinct().size
        require(repositoryCount >= MIN_REPOSITORIES) {
            "EF-03 requires evidence from at least $MIN_REPOSITORIES independent repositories."
        }

        val findings = cases.flatMap { loadedCase ->
            val assessment = loadAssessment(loadedCase)
            assessment.facts.map { fact -> classify(loadedCase.definition.id, fact) }
        }
        require(findings.isNotEmpty()) { "EF-03 must classify at least one semantic fact." }
        return BackupRestoreFalsificationReport(cases.size, repositoryCount, findings)
    }

    private fun loadAssessment(loadedCase: LoadedExternalCorpusCase): BackupRestoreCaseAssessment {
        val file = File(loadedCase.directory, ASSESSMENT_FILE)
        require(file.isFile) { "Missing EF-03 assessment for case '${loadedCase.definition.id}': ${file.path}" }
        val assessment = readStrict(file, BackupRestoreCaseAssessment::class.java)
        require(assessment.kind == KIND && assessment.version == VERSION) {
            "EF-03 assessment for '${loadedCase.definition.id}' must use $KIND version $VERSION."
        }
        require(assessment.caseId == loadedCase.definition.id) {
            "EF-03 assessment caseId '${assessment.caseId}' does not match '${loadedCase.definition.id}'."
        }
        require(assessment.facts.isNotEmpty()) {
            "EF-03 assessment for '${assessment.caseId}' must declare semantic facts."
        }
        requireUnique(assessment.caseId, "fact", assessment.facts.map { it.id })
        requireUnique(assessment.caseId, "observation reference", assessment.facts.map { it.observationRef })

        val observations = loadedCase.definition.expectedSemanticObservations.associateBy { it.id }
        assessment.facts.forEach { fact ->
            require(ID_PATTERN.matches(fact.id)) {
                "EF-03 case '${assessment.caseId}' contains invalid fact id '${fact.id}'."
            }
            val observation = observations[fact.observationRef]
                ?: throw IllegalArgumentException(
                    "EF-03 case '${assessment.caseId}' fact '${fact.id}' references unknown semantic observation '${fact.observationRef}'."
                )
            require(observation.expectation == ExternalSemanticExpectation.PRESERVE) {
                "EF-03 case '${assessment.caseId}' fact '${fact.id}' must reference a PRESERVE semantic observation."
            }
            validateValues(assessment.caseId, fact)
        }

        val preservationObservations = loadedCase.definition.expectedSemanticObservations
            .filter { it.expectation == ExternalSemanticExpectation.PRESERVE }
            .map { it.id }
            .toSet()
        val covered = assessment.facts.map { it.observationRef }.toSet()
        require(covered == preservationObservations) {
            "EF-03 case '${assessment.caseId}' must classify every PRESERVE semantic observation exactly once; expected=${preservationObservations.sorted()} actual=${covered.sorted()}."
        }
        return assessment
    }

    private fun validateValues(caseId: String, fact: BackupRestoreFactDeclaration) {
        require(fact.values.values.none(String::isBlank)) {
            "EF-03 case '$caseId' fact '${fact.id}' contains a blank semantic value."
        }
        val required: Set<String>
        val allowed: Set<String>
        when (fact.requirement) {
            BackupRestoreRequirement.BACKUP_RECOVERY_POINT_CAPTURE -> {
                required = setOf(SUBJECT)
                allowed = required
            }
            BackupRestoreRequirement.RESTORE_FROM_RECOVERY_POINT -> {
                required = setOf(RECOVERY_POINT)
                allowed = required + SUBJECT
            }
            BackupRestoreRequirement.RESTORE_WITH_SELECTION -> {
                required = setOf(RECOVERY_POINT, SELECTION)
                allowed = required + SUBJECT
            }
            BackupRestoreRequirement.RESTORE_WITH_IDENTITY_MAPPING -> {
                required = setOf(RECOVERY_POINT, SOURCE_IDENTITY, TARGET_IDENTITY)
                allowed = required + SUBJECT
            }
        }
        require(fact.values.keys.containsAll(required)) {
            "EF-03 case '$caseId' fact '${fact.id}' is missing required semantic values ${(required - fact.values.keys).sorted()}."
        }
        require(fact.values.keys.all { it in allowed }) {
            "EF-03 case '$caseId' fact '${fact.id}' contains unsupported semantic value keys ${(fact.values.keys - allowed).sorted()}."
        }
    }

    private fun classify(caseId: String, fact: BackupRestoreFactDeclaration): BackupRestoreFinding {
        val classification = when (fact.requirement) {
            BackupRestoreRequirement.BACKUP_RECOVERY_POINT_CAPTURE ->
                backupCaptureClassification(fact.values.getValue(SUBJECT))
            BackupRestoreRequirement.RESTORE_FROM_RECOVERY_POINT ->
                recoveryPointClassification(fact.values)
            BackupRestoreRequirement.RESTORE_WITH_SELECTION ->
                selectiveRestoreClassification(fact.values)
            BackupRestoreRequirement.RESTORE_WITH_IDENTITY_MAPPING ->
                identityMappingClassification(fact.values)
        }
        return BackupRestoreFinding(
            caseId = caseId,
            factId = fact.id,
            observationRef = fact.observationRef,
            requirement = fact.requirement,
            outcome = classification.first,
            reason = classification.second
        )
    }

    private fun backupCaptureClassification(subject: String): Pair<ExternalFalsificationOutcome, String> {
        val projection = semanticProjection(
            StandardCapability.BACKUP,
            mapOf(SUBJECT to subject)
        )
        val preserved = projection.semanticParametersAccepted && projection.effects.any { effect ->
            val recovery = effect.recovery
            val source = recovery?.source
            effect.domain == EffectDomain.STATE_RECOVERY &&
                effect.operation == EffectOperation.CREATE &&
                effect.resource == "recovery.point" &&
                recovery?.kind == RecoveryEffectKind.RECOVERY_POINT_CAPTURE &&
                source?.kind == RecoveryEndpointKind.PROTECTED_STATE &&
                source.identity == subject
        }
        return if (preserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "BACKUP preserves the protected subject as the typed source of RECOVERY_POINT_CAPTURE through canonical intent validation."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "BACKUP does not preserve protected-state capture through the canonical intent contract and typed recovery effect."
        }
    }

    private fun recoveryPointClassification(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val recoveryPoint = values.getValue(RECOVERY_POINT)
        val authoredSubject = values[SUBJECT]
        val subject = authoredSubject ?: EXTERNAL_PROTECTED_STATE
        val projection = semanticProjection(
            StandardCapability.RESTORE,
            mapOf(SUBJECT to subject, RECOVERY_POINT to recoveryPoint)
        )
        val preserved = projection.semanticParametersAccepted && projection.effects.any { effect ->
            val recovery = effect.recovery
            val source = recovery?.source
            val target = recovery?.target
            effect.domain == EffectDomain.STATE_RECOVERY &&
                effect.operation == EffectOperation.UPSERT &&
                effect.resource == "protected.state" &&
                recovery?.kind == RecoveryEffectKind.STATE_RESTORE &&
                source?.kind == RecoveryEndpointKind.RECOVERY_POINT &&
                source.identity == recoveryPoint &&
                target?.kind == RecoveryEndpointKind.PROTECTED_STATE &&
                (authoredSubject == null || target.identity == authoredSubject)
        }
        return if (preserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "RESTORE preserves the exact recovery-point identity${if (authoredSubject != null) " and authored protected-state target" else ""} through canonical intent validation and typed STATE_RESTORE semantics."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "RESTORE does not preserve the authored recovery-point relationship through the canonical intent contract."
        }
    }

    private fun selectiveRestoreClassification(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val common = restoreCommon(values)
        val observed = semanticProjection(
            StandardCapability.RESTORE,
            common + (SELECTION_PARAM to values.getValue(SELECTION))
        )
        if (!observed.semanticParametersAccepted) {
            return ExternalFalsificationOutcome.MODEL_GAP to
                "RESTORE exposes no typed target-neutral selection parameter; canonical intent validation rejects selective recovery scope rather than silently discarding it."
        }

        val alternate = semanticProjection(
            StandardCapability.RESTORE,
            common + (SELECTION_PARAM to ALTERNATE_SELECTION)
        )
        return if (
            alternate.semanticParametersAccepted &&
            semanticSignature(observed.effects) != semanticSignature(alternate.effects)
        ) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "RESTORE carries selective recovery scope into semantically observable canonical effects."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "RESTORE accepts selection data without preserving a semantically observable recovery-scope distinction."
        }
    }

    private fun identityMappingClassification(values: Map<String, String>): Pair<ExternalFalsificationOutcome, String> {
        val mapping = "${values.getValue(SOURCE_IDENTITY)}->${values.getValue(TARGET_IDENTITY)}"
        val alternateMapping = "${values.getValue(SOURCE_IDENTITY)}->$ALTERNATE_TARGET_IDENTITY"
        val common = restoreCommon(values)
        val observed = semanticProjection(
            StandardCapability.RESTORE,
            common + (IDENTITY_MAPPING_PARAM to mapping)
        )
        if (!observed.semanticParametersAccepted) {
            return ExternalFalsificationOutcome.MODEL_GAP to
                "RESTORE exposes no typed target-neutral identity-mapping parameter; canonical intent validation rejects source-to-destination remapping rather than hiding the relation in another string."
        }

        val alternate = semanticProjection(
            StandardCapability.RESTORE,
            common + (IDENTITY_MAPPING_PARAM to alternateMapping)
        )
        return if (
            alternate.semanticParametersAccepted &&
            semanticSignature(observed.effects) != semanticSignature(alternate.effects)
        ) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "RESTORE carries source-to-destination identity remapping into semantically observable canonical effects."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "RESTORE accepts identity mapping data without preserving the authored source-to-destination relation."
        }
    }

    private fun semanticProjection(
        capability: StandardCapability,
        values: Map<String, String>
    ): SemanticProjection {
        val report = IntentCapabilityValidator(ModuleRegistry()).validate(
            IntentDocument(
                name = "ef03-${capability.name.lowercase()}",
                workflows = listOf(
                    IntentWorkflow(
                        name = "main",
                        kind = when (capability) {
                            StandardCapability.BACKUP -> IntentWorkflowKind.BACKUP
                            StandardCapability.RESTORE -> IntentWorkflowKind.RESTORE
                            else -> error("EF-03 semantic projection supports only BACKUP and RESTORE, got $capability.")
                        },
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
        val effects = report.meaning.workflows.single().steps.single().effects
        return SemanticProjection(
            semanticParametersAccepted = semanticParameterErrors.isEmpty(),
            effects = effects
        )
    }

    private fun restoreCommon(values: Map<String, String>): Map<String, String> = mapOf(
        SUBJECT to (values[SUBJECT] ?: EXTERNAL_PROTECTED_STATE),
        RECOVERY_POINT to values.getValue(RECOVERY_POINT)
    )

    private fun semanticSignature(effects: List<SemanticEffect>): List<String> =
        effects.map { it.canonicalObservationValue() }

    private fun requireUnique(caseId: String, label: String, values: List<String>) {
        require(values.distinct().size == values.size) {
            "EF-03 case '$caseId' contains duplicate $label values."
        }
    }

    private fun <T> readStrict(file: File, type: Class<T>): T = try {
        FlowYaml.readStrict(file, type)
    } catch (error: FlowYamlException) {
        throw IllegalArgumentException(
            "Invalid EF-03 assessment '${file.path}': ${error.message ?: error.javaClass.simpleName}",
            error
        )
    }

    private data class SemanticProjection(
        val semanticParametersAccepted: Boolean,
        val effects: List<SemanticEffect>
    )

    companion object {
        const val DOMAIN = "backup-and-restore"
        const val KIND = "FlowBackupRestoreFalsification"
        const val VERSION = "1.0"
        const val ASSESSMENT_FILE = "backup-restore.yaml"
        const val MIN_CASES = 2
        const val MIN_REPOSITORIES = 2

        private const val SUBJECT = "subject"
        private const val RECOVERY_POINT = "recoveryPoint"
        private const val SELECTION = "selection"
        private const val SOURCE_IDENTITY = "sourceIdentity"
        private const val TARGET_IDENTITY = "targetIdentity"
        private const val SELECTION_PARAM = "selection"
        private const val IDENTITY_MAPPING_PARAM = "identityMapping"
        private const val EXTERNAL_PROTECTED_STATE = "external.protected-state"
        private const val ALTERNATE_SELECTION = "alternate-selection"
        private const val ALTERNATE_TARGET_IDENTITY = "alternate-target"
        private val SEMANTIC_PARAMETER_ERROR_CODES = setOf("UNKNOWN_STEP_PARAM", "MISSING_REQUIRED_STEP_PARAM")
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}
