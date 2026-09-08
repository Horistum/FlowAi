package org.flowlang.conformance

import java.io.File
import org.flowlang.effects.CanonicalIntentEffectAuthority
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.effects.RecoveryEffectKind
import org.flowlang.effects.RecoveryEndpointKind
import org.flowlang.effects.SemanticEffect
import org.flowlang.effects.canonicalObservationValue
import org.flowlang.intent.StandardCapability
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException
import org.flowlang.standard.StandardCapabilityContracts

enum class DatabaseMigrationRecoveryRequirement {
    SCHEMA_CHANGE_APPLY,
    SCHEMA_CHANGE_REVERSE,
    RESTORE_FROM_RECOVERY_POINT,
    RESTORE_TO_POINT_IN_TIME
}

enum class ExternalFalsificationOutcome {
    REPRESENTABLE,
    MODEL_GAP
}

data class DatabaseMigrationRecoveryFactDeclaration(
    val id: String,
    val observationRef: String,
    val requirement: DatabaseMigrationRecoveryRequirement,
    val semanticValue: String?
)

data class DatabaseMigrationRecoveryCaseAssessment(
    val kind: String,
    val version: String,
    val caseId: String,
    val facts: List<DatabaseMigrationRecoveryFactDeclaration>
)

data class DatabaseMigrationRecoveryFinding(
    val caseId: String,
    val factId: String,
    val observationRef: String,
    val requirement: DatabaseMigrationRecoveryRequirement,
    val outcome: ExternalFalsificationOutcome,
    val reason: String
)

data class DatabaseMigrationRecoveryFalsificationReport(
    val caseCount: Int,
    val distinctRepositoryCount: Int,
    val findings: List<DatabaseMigrationRecoveryFinding>
) {
    val representableCount: Int get() = findings.count { it.outcome == ExternalFalsificationOutcome.REPRESENTABLE }
    val modelGapCount: Int get() = findings.count { it.outcome == ExternalFalsificationOutcome.MODEL_GAP }
}

/**
 * EF-02 evaluator for externally grounded database migration and recovery facts.
 *
 * External syntax is interpreted only by the reviewed case evidence. This evaluator
 * consumes typed semantic requirements and asks the current production Core contracts
 * whether those requirements can be preserved. A MODEL_GAP is therefore evidence, not
 * a conformance failure and never a reason to weaken the source contract.
 */
class DatabaseMigrationRecoveryFalsification(private val rootDir: File = File(".")) {
    fun evaluate(): DatabaseMigrationRecoveryFalsificationReport {
        val corpus = ExternalCorpusLoader(rootDir).load()
        require(corpus.manifest.status == ExternalCorpusStatus.EVIDENCE_ACTIVE) {
            "EF-02 requires an EVIDENCE_ACTIVE external corpus."
        }
        val cases = corpus.cases.filter { it.definition.domain == DOMAIN }
        require(cases.size >= MIN_CASES) {
            "EF-02 requires at least $MIN_CASES database migration/recovery evidence cases."
        }
        val repositoryCount = cases.map { it.definition.provenance.repository }.distinct().size
        require(repositoryCount >= MIN_REPOSITORIES) {
            "EF-02 requires evidence from at least $MIN_REPOSITORIES independent repositories."
        }

        val findings = cases.flatMap { loadedCase ->
            val assessment = loadAssessment(loadedCase)
            assessment.facts.map { fact -> classify(loadedCase.definition.id, fact) }
        }
        require(findings.isNotEmpty()) { "EF-02 must classify at least one semantic fact." }
        return DatabaseMigrationRecoveryFalsificationReport(cases.size, repositoryCount, findings)
    }

    private fun loadAssessment(loadedCase: LoadedExternalCorpusCase): DatabaseMigrationRecoveryCaseAssessment {
        val file = File(loadedCase.directory, ASSESSMENT_FILE)
        require(file.isFile) { "Missing EF-02 assessment for case '${loadedCase.definition.id}': ${file.path}" }
        val assessment = readStrict(file, DatabaseMigrationRecoveryCaseAssessment::class.java)
        require(assessment.kind == KIND && assessment.version == VERSION) {
            "EF-02 assessment for '${loadedCase.definition.id}' must use $KIND version $VERSION."
        }
        require(assessment.caseId == loadedCase.definition.id) {
            "EF-02 assessment caseId '${assessment.caseId}' does not match '${loadedCase.definition.id}'."
        }
        require(assessment.facts.isNotEmpty()) {
            "EF-02 assessment for '${assessment.caseId}' must declare semantic facts."
        }
        requireUnique(assessment.caseId, "fact", assessment.facts.map { it.id })
        requireUnique(assessment.caseId, "observation reference", assessment.facts.map { it.observationRef })

        val observations = loadedCase.definition.expectedSemanticObservations.associateBy { it.id }
        assessment.facts.forEach { fact ->
            require(ID_PATTERN.matches(fact.id)) {
                "EF-02 case '${assessment.caseId}' contains invalid fact id '${fact.id}'."
            }
            val observation = observations[fact.observationRef]
                ?: throw IllegalArgumentException(
                    "EF-02 case '${assessment.caseId}' fact '${fact.id}' references unknown semantic observation '${fact.observationRef}'."
                )
            require(observation.expectation == ExternalSemanticExpectation.PRESERVE) {
                "EF-02 case '${assessment.caseId}' fact '${fact.id}' must reference a PRESERVE semantic observation."
            }
            when (fact.requirement) {
                DatabaseMigrationRecoveryRequirement.SCHEMA_CHANGE_APPLY,
                DatabaseMigrationRecoveryRequirement.SCHEMA_CHANGE_REVERSE -> require(fact.semanticValue == null) {
                    "EF-02 schema-change fact '${fact.id}' must not invent an implementation value."
                }
                DatabaseMigrationRecoveryRequirement.RESTORE_FROM_RECOVERY_POINT,
                DatabaseMigrationRecoveryRequirement.RESTORE_TO_POINT_IN_TIME -> require(!fact.semanticValue.isNullOrBlank()) {
                    "EF-02 recovery fact '${fact.id}' requires the exact authored semantic value."
                }
            }
        }

        val preservationObservations = loadedCase.definition.expectedSemanticObservations
            .filter { it.expectation == ExternalSemanticExpectation.PRESERVE }
            .map { it.id }
            .toSet()
        val covered = assessment.facts.map { it.observationRef }.toSet()
        require(covered == preservationObservations) {
            "EF-02 case '${assessment.caseId}' must classify every PRESERVE semantic observation exactly once; expected=${preservationObservations.sorted()} actual=${covered.sorted()}."
        }
        return assessment
    }

    private fun classify(
        caseId: String,
        fact: DatabaseMigrationRecoveryFactDeclaration
    ): DatabaseMigrationRecoveryFinding {
        val classification = when (fact.requirement) {
            DatabaseMigrationRecoveryRequirement.SCHEMA_CHANGE_APPLY -> schemaApplyClassification()
            DatabaseMigrationRecoveryRequirement.SCHEMA_CHANGE_REVERSE -> schemaReverseClassification()
            DatabaseMigrationRecoveryRequirement.RESTORE_FROM_RECOVERY_POINT ->
                recoveryPointClassification(fact.semanticValue!!)
            DatabaseMigrationRecoveryRequirement.RESTORE_TO_POINT_IN_TIME ->
                pointInTimeClassification(fact.semanticValue!!)
        }
        return DatabaseMigrationRecoveryFinding(
            caseId = caseId,
            factId = fact.id,
            observationRef = fact.observationRef,
            requirement = fact.requirement,
            outcome = classification.first,
            reason = classification.second
        )
    }

    private fun schemaApplyClassification(): Pair<ExternalFalsificationOutcome, String> {
        val contract = StandardCapabilityContracts.requireContract(StandardCapability.DATABASE_MIGRATE)
        val effects = CanonicalIntentEffectAuthority.effectsForRendered(
            StandardCapability.DATABASE_MIGRATE,
            mapOf("database" to "external.database")
        )
        val preserved = "database" in contract.requiredParams && effects.any { it.isSchemaMutation() }
        return if (preserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "DATABASE_MIGRATE requires database identity and produces a target-neutral data.schema UPDATE effect."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "DATABASE_MIGRATE does not preserve the required target-neutral schema-change effect."
        }
    }

    private fun schemaReverseClassification(): Pair<ExternalFalsificationOutcome, String> {
        val migrateContract = StandardCapabilityContracts.requireContract(StandardCapability.DATABASE_MIGRATE)
        val migrateParams = migrateContract.requiredParams.toSet() + migrateContract.optionalParams
        val forward = CanonicalIntentEffectAuthority.effectsForRendered(
            StandardCapability.DATABASE_MIGRATE,
            mapOf("database" to "external.database", "direction" to "forward")
        )
        val reverse = CanonicalIntentEffectAuthority.effectsForRendered(
            StandardCapability.DATABASE_MIGRATE,
            mapOf("database" to "external.database", "direction" to "reverse")
        )
        val typedMigrationDirection = "direction" in migrateParams &&
            reverse.any { it.isSchemaMutation() } && semanticSignature(forward) != semanticSignature(reverse)

        val rollbackContract = StandardCapabilityContracts.requireContract(StandardCapability.ROLLBACK)
        val rollbackParams = rollbackContract.requiredParams.toSet() + rollbackContract.optionalParams
        val rollback = CanonicalIntentEffectAuthority.effectsForRendered(
            StandardCapability.ROLLBACK,
            mapOf("database" to "external.database")
        )
        val typedDatabaseRollback = "database" in rollbackParams && rollback.any { it.isSchemaMutation() }

        return if (typedMigrationDirection || typedDatabaseRollback) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "Current Core exposes a typed database-schema reversal path distinct from forward migration."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "Current DATABASE_MIGRATE has no typed reverse direction and generic ROLLBACK does not identify database schema mutation."
        }
    }

    private fun recoveryPointClassification(recoveryPoint: String): Pair<ExternalFalsificationOutcome, String> {
        val contract = StandardCapabilityContracts.requireContract(StandardCapability.RESTORE)
        val params = contract.requiredParams.toSet() + contract.optionalParams
        val subject = "external.database"
        val effects = CanonicalIntentEffectAuthority.effectsForRendered(
            StandardCapability.RESTORE,
            mapOf("subject" to subject, "recoveryPoint" to recoveryPoint)
        )
        val preserved = "subject" in contract.requiredParams && "recoveryPoint" in params && effects.any { effect ->
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
                target.identity == subject
        }
        return if (preserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "RESTORE preserves exact recovery-point identity and protected target state in typed STATE_RESTORE semantics."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "RESTORE does not preserve the exact recovery-point-to-protected-state relationship."
        }
    }

    private fun pointInTimeClassification(targetTime: String): Pair<ExternalFalsificationOutcome, String> {
        val contract = StandardCapabilityContracts.requireContract(StandardCapability.RESTORE)
        val params = contract.requiredParams.toSet() + contract.optionalParams
        if (RECOVERY_TARGET_TIME_PARAM !in params) {
            return ExternalFalsificationOutcome.MODEL_GAP to
                "RESTORE has no typed recovery-target-time parameter; a PITR target cannot be preserved separately from recovery-point identity."
        }

        val common = mapOf("subject" to "external.database", "recoveryPoint" to "external.recovery-point")
        val observed = CanonicalIntentEffectAuthority.effectsForRendered(
            StandardCapability.RESTORE,
            common + (RECOVERY_TARGET_TIME_PARAM to targetTime)
        )
        val alternate = CanonicalIntentEffectAuthority.effectsForRendered(
            StandardCapability.RESTORE,
            common + (RECOVERY_TARGET_TIME_PARAM to ALTERNATE_TARGET_TIME)
        )
        val preserved = semanticSignature(observed) != semanticSignature(alternate) && observed.any {
            it.recovery?.kind == RecoveryEffectKind.STATE_RESTORE
        }
        return if (preserved) {
            ExternalFalsificationOutcome.REPRESENTABLE to
                "RESTORE carries the exact point-in-time target into typed recovery semantics."
        } else {
            ExternalFalsificationOutcome.MODEL_GAP to
                "RESTORE accepts no semantically observable point-in-time target value."
        }
    }

    private fun SemanticEffect.isSchemaMutation(): Boolean =
        domain == EffectDomain.DATA_TRANSFORMATION &&
            operation == EffectOperation.UPDATE &&
            resource == "data.schema"

    private fun semanticSignature(effects: List<SemanticEffect>): List<String> =
        effects.map { it.canonicalObservationValue() }

    private fun requireUnique(caseId: String, label: String, values: List<String>) {
        require(values.distinct().size == values.size) {
            "EF-02 case '$caseId' contains duplicate $label values."
        }
    }

    private fun <T> readStrict(file: File, type: Class<T>): T = try {
        FlowYaml.readStrict(file, type)
    } catch (error: FlowYamlException) {
        throw IllegalArgumentException(
            "Invalid EF-02 assessment '${file.path}': ${error.message ?: error.javaClass.simpleName}",
            error
        )
    }

    companion object {
        const val DOMAIN = "database-migration-and-recovery"
        const val KIND = "FlowDatabaseMigrationRecoveryFalsification"
        const val VERSION = "1.0"
        const val ASSESSMENT_FILE = "database-migration-recovery.yaml"
        const val MIN_CASES = 2
        const val MIN_REPOSITORIES = 2
        const val RECOVERY_TARGET_TIME_PARAM = "recoveryTargetTime"
        const val ALTERNATE_TARGET_TIME = "2000-01-01T00:00:00Z"
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}
