package org.flowlang.conformance

import java.io.File
import java.security.MessageDigest
import org.flowlang.adapters.promotion.PostA0ConcretePromotionAuthority
import org.flowlang.generators.manifest.TargetArtifactSupport
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.IntentToAstPlanner
import org.flowlang.validator.FlowValidator

data class SemanticEquivalenceCategoryStatus(
    val status: String,
    val checked: Int,
    val errors: List<String>
)

data class SemanticEquivalenceReport(
    val status: String,
    val rulesPath: String,
    val lifecycle: C03LifecycleAssessment,
    val schemaErrors: List<String>,
    val coverageErrors: List<String>,
    val polarityErrors: List<String>,
    val independenceErrors: List<String>,
    val concreteReferenceErrors: List<String>,
    val boundaryErrors: List<String>,
    val categories: Map<String, SemanticEquivalenceCategoryStatus>,
    val rules: SemanticEquivalenceDocument? = null
) {
    val errors: List<String>
        get() = schemaErrors + coverageErrors + polarityErrors + independenceErrors + concreteReferenceErrors + boundaryErrors
}

class SemanticEquivalenceAuthority(
    private val rootDir: File = File("."),
    private val modules: ModuleRegistry = ModuleRegistry.fromDirectory(File(rootDir, "modules"))
) {
    private val snapshotHonesty by lazy { ReferenceSnapshotHonesty(rootDir) }
    private val snapshotPlanBuilder by lazy { ReferenceSnapshotBundleGenerator(rootDir, modules) }
    private val implementationObservationAuthority by lazy { SemanticImplementationObservationAuthority(rootDir) }
    private val promotionAuthority by lazy { PostA0ConcretePromotionAuthority(rootDir) }

    fun analyze(): SemanticEquivalenceReport {
        val rulesResult = runCatching { SemanticEquivalenceLoader.load(rootDir) }
        val schemaErrors = rulesResult.exceptionOrNull()?.let {
            listOf("Semantic equivalence rules are invalid: ${it.message.orEmpty()}")
        }.orEmpty()
        val rules = rulesResult.getOrNull()
        val lifecycle = C03RoadmapLifecycleAuthority(rootDir).assess()
        val lifecycleErrors = lifecycle.errors.map { "C0.3 lifecycle: $it" }
        val coverageErrors = if (rules == null) emptyList() else coverageErrors(rules)
        val polarityErrors = if (rules == null) emptyList() else polarityErrors(rules)
        val independenceErrors = if (rules == null) emptyList() else independenceErrors(rules)
        val concreteReferenceErrors = if (rules == null) emptyList() else concreteReferenceErrors(rules)
        val boundaryErrors = frozenBoundaryErrors()
        val categories = linkedMapOf(
            "conformance.c0.3.lifecycle-integrity" to category(1, lifecycleErrors),
            "conformance.c0.3.rules-schema-integrity" to category(1, schemaErrors),
            "conformance.c0.3.observation-coverage" to category(rules?.cases?.size ?: 0, coverageErrors),
            "conformance.c0.3.mutation-polarity" to category(
                (rules?.cases?.size ?: 0) * (rules?.mutations?.size ?: 0),
                polarityErrors
            ),
            "conformance.c0.3.implementation-independence" to category(
                rules?.cases?.size ?: 0,
                independenceErrors
            ),
            "conformance.c0.3.concrete-reference-equivalence" to category(
                rules?.concretePairs?.size ?: 0,
                concreteReferenceErrors
            ),
            "conformance.c0.3.frozen-boundary-preservation" to category(BOUNDARY_SPECS.size, boundaryErrors)
        )
        val allErrors = categories.values.flatMap { it.errors }
        return SemanticEquivalenceReport(
            status = if (allErrors.isEmpty()) "PASS" else "FAIL",
            rulesPath = SemanticEquivalenceLoader.PATH,
            lifecycle = lifecycle,
            schemaErrors = schemaErrors,
            coverageErrors = coverageErrors,
            polarityErrors = polarityErrors,
            independenceErrors = independenceErrors,
            concreteReferenceErrors = concreteReferenceErrors,
            boundaryErrors = boundaryErrors,
            categories = categories,
            rules = rules
        )
    }

    private fun coverageErrors(rules: SemanticEquivalenceDocument): List<String> = buildList {
        val observedKinds = mutableSetOf<SemanticObservationKind>()
        rules.cases.forEach { case ->
            val requirements = SemanticObservationAuthority.requirementsFor(
                SemanticEquivalencePlanFactory.plan(case.fixture)
            )
            val kinds = requirements.map { it.kind }.toSet()
            observedKinds += kinds
            if (requirements.isEmpty()) add("Case '${case.id}' derives no semantic observations.")
            if (kinds != case.requiredKinds.toSet()) {
                add("Case '${case.id}' declares ${case.requiredKinds} but derives ${kinds.sortedBy { it.ordinal }}.")
            }
            if (requirements.map { it.id }.size != requirements.map { it.id }.toSet().size) {
                add("Case '${case.id}' derives duplicate semantic observation ids.")
            }
        }
        if (observedKinds != SemanticObservationKind.entries.toSet()) {
            add("C0.3 observation corpus covers ${observedKinds.sortedBy { it.ordinal }} instead of all observation kinds.")
        }
    }

    private fun polarityErrors(rules: SemanticEquivalenceDocument): List<String> = buildList {
        rules.cases.forEach { case ->
            val requirements = SemanticObservationAuthority.requirementsFor(
                SemanticEquivalencePlanFactory.plan(case.fixture)
            )
            val baseline = SemanticObservationAuthority.fullyPreserved(requirements, "case:${case.id}:baseline")
            val baselineAssessment = SemanticObservationAuthority.assess(requirements, baseline)
            if (baselineAssessment.decision.status != SemanticEquivalenceDecisionStatus.EQUIVALENT) {
                add("Case '${case.id}' positive baseline is not equivalent.")
            }
            requirements.forEach { requirement ->
                rules.mutations.forEach { mutation ->
                    val mutated = mutateEvidence(baseline, requirement, mutation, case.id)
                    val assessment = SemanticObservationAuthority.assess(requirements, mutated)
                    if (assessment.decision.status != SemanticEquivalenceDecisionStatus.NOT_EQUIVALENT) {
                        add("Case '${case.id}' mutation '${mutation.documentValue}' did not reject '${requirement.id}'.")
                    }
                    val observed = assessment.observations.singleOrNull { it.requirement?.id == requirement.id }
                    val expected = mutation.expectedStatus()
                    if (observed?.status != expected) {
                        add(
                            "Case '${case.id}' mutation '${mutation.documentValue}' produced " +
                                "${observed?.status} for '${requirement.id}', expected $expected."
                        )
                    }
                }
                val duplicated = baseline + baseline.single { it.requirementId == requirement.id }.copy(
                    evidenceReference = "case:${case.id}:duplicate:${requirement.id}"
                )
                val duplicateAssessment = SemanticObservationAuthority.assess(requirements, duplicated)
                val duplicateObservation = duplicateAssessment.observations.singleOrNull {
                    it.requirement?.id == requirement.id
                }
                if (duplicateAssessment.decision.status != SemanticEquivalenceDecisionStatus.NOT_EQUIVALENT ||
                    duplicateObservation?.status != SemanticObservationEvidenceStatus.CONTRADICTORY ||
                    duplicateObservation.message?.contains("multiple evidence records") != true
                ) {
                    add("Case '${case.id}' duplicate evidence did not exercise the duplicate-record rejection path.")
                }
                val fingerprintMismatch = baseline.map { evidence ->
                    if (evidence.requirementId == requirement.id) {
                        evidence.copy(
                            fingerprint = SemanticObservationIdentity.fingerprint("tampered", requirement.id),
                            evidenceReference = "case:${case.id}:fingerprint-mismatch:${requirement.id}"
                        )
                    } else {
                        evidence
                    }
                }
                val mismatchAssessment = SemanticObservationAuthority.assess(requirements, fingerprintMismatch)
                val mismatchObservation = mismatchAssessment.observations.singleOrNull {
                    it.requirement?.id == requirement.id
                }
                if (mismatchAssessment.decision.status != SemanticEquivalenceDecisionStatus.NOT_EQUIVALENT ||
                    mismatchObservation?.status != SemanticObservationEvidenceStatus.CONTRADICTORY ||
                    mismatchObservation.message?.contains("fingerprint") != true
                ) {
                    add("Case '${case.id}' fingerprint mutation did not exercise the integrity mismatch path.")
                }
            }
        }
    }

    private fun independenceErrors(rules: SemanticEquivalenceDocument): List<String> = buildList {
        rules.cases.forEach { case ->
            val reference = SemanticEquivalencePlanFactory.plan(case.fixture)
            val alternate = SemanticEquivalencePlanFactory.plan(case.fixture, alternateImplementationLabels = true)
            val referenceRequirements = SemanticObservationAuthority.requirementsFor(reference)
            val alternateRequirements = SemanticObservationAuthority.requirementsFor(alternate)
            if (referenceRequirements != alternateRequirements) {
                add("Case '${case.id}' semantic requirements depend on module/action/target labels.")
            }
            val referenceAssessment = SemanticObservationAuthority.assess(
                referenceRequirements,
                SemanticObservationAuthority.fullyPreserved(referenceRequirements, "implementation:reference")
            )
            val alternateAssessment = SemanticObservationAuthority.assess(
                alternateRequirements,
                SemanticObservationAuthority.fullyPreserved(alternateRequirements, "implementation:alternate")
            )
            if (referenceAssessment.decision != alternateAssessment.decision ||
                referenceAssessment.observations.map { it.status } != alternateAssessment.observations.map { it.status }
            ) {
                add("Case '${case.id}' decision changes when only implementation labels change.")
            }
            val semanticAlternateRequirements = SemanticObservationAuthority.requirementsFor(
                SemanticEquivalencePlanFactory.plan(
                    case.fixture,
                    alternateImplementationLabels = true,
                    alternateSemanticMeaning = true
                )
            )
            if (referenceRequirements == semanticAlternateRequirements) {
                add("Case '${case.id}' independence fixture cannot detect a semantic mutation.")
            }
        }
    }

    private fun concreteReferenceErrors(rules: SemanticEquivalenceDocument): List<String> = buildList {
        rules.concretePairs.forEach { pair ->
            val intentFile = File(rootDir, pair.intent)
            if (!intentFile.isFile) {
                add("Concrete pair '${pair.id}' intent is missing: ${pair.intent}.")
                return@forEach
            }
            val planResult = runCatching { buildProductionPlan(intentFile) }
            if (planResult.isFailure) {
                add("Concrete pair '${pair.id}' production plan failed: ${planResult.exceptionOrNull()?.message.orEmpty()}")
                return@forEach
            }
            val plan = planResult.getOrThrow()
            val requirements = SemanticObservationAuthority.requirementsFor(plan)
            if (requirements.isEmpty()) {
                add("Concrete pair '${pair.id}' derives no required semantic observations.")
                return@forEach
            }
            val leftFile = File(rootDir, pair.leftSnapshot)
            val rightFile = File(rootDir, pair.rightSnapshot)
            val leftBundle = snapshotHonesty.assess(leftFile)
            val rightBundle = snapshotHonesty.assess(rightFile)
            if (leftBundle.status != "PASS") {
                add("Concrete pair '${pair.id}' left snapshot is not honest: ${leftBundle.errors.joinToString(" | ")}.")
            }
            if (rightBundle.status != "PASS") {
                add("Concrete pair '${pair.id}' right snapshot is not honest: ${rightBundle.errors.joinToString(" | ")}.")
            }
            if (leftBundle.scenarioId != pair.scenarioId || rightBundle.scenarioId != pair.scenarioId) {
                add(
                    "Concrete pair '${pair.id}' snapshots must declare scenarioId '${pair.scenarioId}', got " +
                        "'${leftBundle.scenarioId}' and '${rightBundle.scenarioId}'."
                )
            }
            val leftTargets = leftBundle.states.filter { it.executable }.map { it.target }
            val rightTargets = rightBundle.states.filter { it.executable }.map { it.target }
            if (leftTargets != listOf(pair.leftTarget)) {
                add("Concrete pair '${pair.id}' left snapshot does not prove executable target '${pair.leftTarget}'.")
            }
            if (rightTargets != listOf(pair.rightTarget)) {
                add("Concrete pair '${pair.id}' right snapshot does not prove executable target '${pair.rightTarget}'.")
            }
            val leftArtifact = singleExecutableArtifact(leftBundle, pair.leftTarget, pair.id, "left", this)
            val rightArtifact = singleExecutableArtifact(rightBundle, pair.rightTarget, pair.id, "right", this)
            val leftProfile = runCatching {
                implementationObservationAuthority.profile(plan, pair.leftTarget, pair.scenarioId, requirements)
            }.getOrElse {
                add("Concrete pair '${pair.id}' left target evidence failed: ${it.message.orEmpty()}")
                null
            }
            val rightProfile = runCatching {
                implementationObservationAuthority.profile(plan, pair.rightTarget, pair.scenarioId, requirements)
            }.getOrElse {
                add("Concrete pair '${pair.id}' right target evidence failed: ${it.message.orEmpty()}")
                null
            }
            if (leftProfile != null) validateRenderingWitness(pair, "left", leftProfile, leftArtifact, this)
            if (rightProfile != null) validateRenderingWitness(pair, "right", rightProfile, rightArtifact, this)
            validateTargetBackings(pair, leftBundle, rightBundle, this)
            val leftPlanFile = leftBundle.artifacts.singleOrNull {
                it.kind == "execution-plan" && it.format == "json" && !it.executable
            }?.let { File(leftFile.parentFile, it.path) }
            val rightPlanFile = rightBundle.artifacts.singleOrNull {
                it.kind == "execution-plan" && it.format == "json" && !it.executable
            }?.let { File(rightFile.parentFile, it.path) }
            when {
                leftPlanFile == null -> add(
                    "Concrete pair '${pair.id}' ${pair.leftTarget} snapshot has no non-executable execution plan artifact."
                )
                !leftPlanFile.isFile -> add(
                    "Concrete pair '${pair.id}' ${pair.leftTarget} snapshot plan is missing: ${leftPlanFile.path}."
                )
                leftPlanFile.readText() != snapshotPlanBuilder.renderPlanJson(plan) -> add(
                    "Concrete pair '${pair.id}' ${pair.leftTarget} snapshot plan is stale against the production planning path."
                )
            }
            when {
                rightPlanFile == null -> add(
                    "Concrete pair '${pair.id}' ${pair.rightTarget} snapshot has no non-executable execution plan artifact."
                )
                !rightPlanFile.isFile -> add(
                    "Concrete pair '${pair.id}' ${pair.rightTarget} snapshot plan is missing: ${rightPlanFile.path}."
                )
                rightPlanFile.readText() != snapshotPlanBuilder.renderPlanJson(plan) -> add(
                    "Concrete pair '${pair.id}' ${pair.rightTarget} snapshot plan is stale against the production planning path."
                )
            }
            if (leftProfile != null) {
                val assessment = SemanticObservationAuthority.assess(requirements, leftProfile.evidence)
                if (assessment.decision.status != SemanticEquivalenceDecisionStatus.EQUIVALENT) {
                    add(
                        "Concrete pair '${pair.id}' ${pair.leftTarget} target evidence is not equivalent: " +
                            blockingSummary(assessment)
                    )
                }
            }
            if (rightProfile != null) {
                val assessment = SemanticObservationAuthority.assess(requirements, rightProfile.evidence)
                if (assessment.decision.status != SemanticEquivalenceDecisionStatus.EQUIVALENT) {
                    add(
                        "Concrete pair '${pair.id}' ${pair.rightTarget} target evidence is not equivalent: " +
                            blockingSummary(assessment)
                    )
                }
            }
            if (leftProfile != null && rightProfile != null) {
                val leftRequirementIds = leftProfile.evidence.map { it.requirementId }.toSet()
                val rightRequirementIds = rightProfile.evidence.map { it.requirementId }.toSet()
                if (leftRequirementIds != requirements.map { it.id }.toSet() ||
                    rightRequirementIds != requirements.map { it.id }.toSet()
                ) {
                    add("Concrete pair '${pair.id}' target evidence does not cover the exact required observation set.")
                }
            }
        }
    }

    private fun validateTargetBackings(
        pair: SemanticEquivalenceConcretePair,
        leftBundle: ReferenceSnapshotBundleAssessment,
        rightBundle: ReferenceSnapshotBundleAssessment,
        errors: MutableList<String>
    ) {
        val bundlesByTarget = mapOf(pair.leftTarget to leftBundle, pair.rightTarget to rightBundle)
        pair.targetBackings.forEach { backing ->
            val bundle = requireNotNull(bundlesByTarget[backing.target]) {
                "Concrete pair '${pair.id}' has backing for unknown target '${backing.target}'."
            }
            when (backing.kind) {
                SemanticEquivalenceTargetBackingKind.REFERENCE_SNAPSHOT -> {
                    val promotionClaims = promotionAuthority.promotionsFor(
                        backing.target,
                        pair.scenarioId,
                        bundle.indexFile.path.replace(File.separatorChar, '/')
                    )
                    if (promotionClaims.isNotEmpty()) {
                        errors +=
                            "Concrete pair '${pair.id}' target '${backing.target}' is declared as reference-snapshot " +
                                "backing but has bounded-promotion claims: ${promotionClaims.map { it.id }}."
                    }
                }
                SemanticEquivalenceTargetBackingKind.BOUNDED_PROMOTION -> {
                    val claims = promotionAuthority.promotionsFor(
                        backing.target,
                        pair.scenarioId,
                        bundle.indexFile.path.replace(File.separatorChar, '/')
                    )
                    if (claims.size != 1) {
                        errors +=
                            "Concrete pair '${pair.id}' target '${backing.target}' requires exactly one bounded " +
                                "promotion backing, got ${claims.map { it.id }}."
                    }
                }
            }
        }
    }

    private fun validateRenderingWitness(
        pair: SemanticEquivalenceConcretePair,
        side: String,
        profile: SemanticImplementationObservationProfile,
        snapshotArtifact: ReferenceSnapshotArtifact?,
        errors: MutableList<String>
    ) {
        val target = profile.target
        if (profile.rendering.receipt.renderMode != TargetRenderMode.EXECUTABLE) {
            errors += "Concrete pair '${pair.id}' $side target '$target' receipt is not EXECUTABLE."
        }
        if (profile.rendering.artifact.support != TargetArtifactSupport.EXECUTABLE) {
            errors += "Concrete pair '${pair.id}' $side target '$target' artifact is not executable."
        }
        if (snapshotArtifact != null) {
            val file = snapshotFile(snapshotForTarget(pair, target), snapshotArtifact)
            if (!file.isFile) {
                errors += "Concrete pair '${pair.id}' $side target '$target' snapshot artifact is missing: ${file.path}."
            } else if (file.readText() != profile.rendering.artifact.content) {
                errors += "Concrete pair '${pair.id}' $side target '$target' committed artifact is stale against production rendering."
            }
            if (snapshotArtifact.sha256 != profile.rendering.artifact.sha256) {
                errors += "Concrete pair '${pair.id}' $side target '$target' snapshot digest does not match production rendering."
            }
        }
    }

    private fun singleExecutableArtifact(
        bundle: ReferenceSnapshotBundleAssessment,
        target: String,
        pairId: String,
        side: String,
        errors: MutableList<String>
    ): ReferenceSnapshotArtifact? {
        val artifacts = bundle.artifacts.filter { it.target == target && it.executable }
        if (artifacts.size != 1) {
            errors += "Concrete pair '$pairId' $side snapshot must contain exactly one executable artifact for '$target'."
        }
        return artifacts.singleOrNull()
    }

    private fun snapshotForTarget(pair: SemanticEquivalenceConcretePair, target: String): String = when (target) {
        pair.leftTarget -> pair.leftSnapshot
        pair.rightTarget -> pair.rightSnapshot
        else -> error("Concrete pair '${pair.id}' has no snapshot for target '$target'.")
    }

    private fun snapshotFile(indexPath: String, artifact: ReferenceSnapshotArtifact): File =
        File(File(rootDir, indexPath).parentFile, artifact.path)

    private fun blockingSummary(assessment: SemanticEquivalenceAssessment): String =
        assessment.observations
            .filter { it.status != SemanticObservationEvidenceStatus.PRESERVED }
            .joinToString(" | ") { observation ->
                "${observation.requirement?.id ?: "unknown"}:${observation.status}:${observation.message.orEmpty()}"
            }

    private fun mutateEvidence(
        baseline: List<SemanticObservationEvidence>,
        requirement: SemanticObservationRequirement,
        mutation: SemanticEquivalenceMutation,
        caseId: String
    ): List<SemanticObservationEvidence> = when (mutation) {
        SemanticEquivalenceMutation.MISSING -> baseline.filterNot { it.requirementId == requirement.id }
        SemanticEquivalenceMutation.WEAKENED -> baseline.map {
            if (it.requirementId == requirement.id) {
                it.copy(
                    status = SemanticObservationEvidenceStatus.WEAKENED,
                    evidenceReference = "case:$caseId:weakened:${requirement.id}"
                )
            } else {
                it
            }
        }
        SemanticEquivalenceMutation.UNKNOWN -> baseline.map {
            if (it.requirementId == requirement.id) {
                it.copy(
                    status = SemanticObservationEvidenceStatus.UNKNOWN,
                    evidenceReference = "case:$caseId:unknown:${requirement.id}"
                )
            } else {
                it
            }
        }
        SemanticEquivalenceMutation.CONTRADICTORY -> baseline.map {
            if (it.requirementId == requirement.id) {
                it.copy(
                    status = SemanticObservationEvidenceStatus.CONTRADICTORY,
                    evidenceReference = "case:$caseId:contradictory:${requirement.id}"
                )
            } else {
                it
            }
        }
    }

    private fun SemanticEquivalenceMutation.expectedStatus(): SemanticObservationEvidenceStatus = when (this) {
        SemanticEquivalenceMutation.MISSING -> SemanticObservationEvidenceStatus.MISSING
        SemanticEquivalenceMutation.WEAKENED -> SemanticObservationEvidenceStatus.WEAKENED
        SemanticEquivalenceMutation.UNKNOWN -> SemanticObservationEvidenceStatus.UNKNOWN
        SemanticEquivalenceMutation.CONTRADICTORY -> SemanticObservationEvidenceStatus.CONTRADICTORY
    }

    private fun buildProductionPlan(intentFile: File): ExecutionPlan {
        val intent = IntentYamlLoader.load(intentFile)
        val intentReport = IntentCapabilityValidator.validate(intent, modules)
        require(intentReport.valid) {
            "Concrete intent is invalid: ${intentReport.errors.joinToString(" | ") { "${it.path}:${it.message}" }}"
        }
        val ast = IntentToAstPlanner(modules).lower(intent)
        val validation = FlowValidator(modules).validate(ast)
        require(validation.valid) {
            "Concrete lowered AST is invalid: ${validation.issues.joinToString(" | ") { "${it.path}:${it.message}" }}"
        }
        return FlowPlanner(modules).plan(ast)
    }

    private fun frozenBoundaryErrors(): List<String> = buildList {
        BOUNDARY_SPECS.forEach { spec ->
            val indexFile = File(rootDir, spec.indexPath)
            if (!indexFile.isFile) {
                add("C0.3 frozen boundary '${spec.name}' index is missing: ${spec.indexPath}.")
                return@forEach
            }
            val entries = runCatching { spec.loadEntries(indexFile) }.getOrElse {
                add("C0.3 frozen boundary '${spec.name}' is invalid: ${it.message.orEmpty()}")
                return@forEach
            }
            if (entries.isEmpty()) add("C0.3 frozen boundary '${spec.name}' is empty.")
            if (entries.map(BoundaryEntry::id).size != entries.map(BoundaryEntry::id).toSet().size) {
                add("C0.3 frozen boundary '${spec.name}' has duplicate ids.")
            }
            entries.forEach { entry ->
                if (!entry.sha256.matches(SHA_256_PATTERN)) {
                    add("C0.3 frozen boundary '${spec.name}' entry '${entry.id}' has invalid digest '${entry.sha256}'.")
                    return@forEach
                }
                val file = File(rootDir, entry.path)
                if (!file.isFile) {
                    add("C0.3 frozen boundary '${spec.name}' entry '${entry.id}' is missing: ${entry.path}.")
                } else {
                    val actual = sha256(file)
                    if (actual != entry.sha256) {
                        add("C0.3 frozen boundary '${spec.name}' entry '${entry.id}' drifted: expected ${entry.sha256}, got $actual.")
                    }
                }
            }
        }
        val c03Ids = SemanticEquivalenceConformanceChecks.ids().toSet()
        BOUNDARY_SPECS.forEach { spec ->
            val indexFile = File(rootDir, spec.indexPath)
            if (indexFile.isFile) {
                runCatching { spec.loadEntries(indexFile) }.getOrDefault(emptyList()).forEach { entry ->
                    if (entry.id in c03Ids) add("C0.3 check id '${entry.id}' was inserted into frozen ${spec.name} inventory.")
                }
            }
        }
    }

    private fun category(checked: Int, errors: List<String>): SemanticEquivalenceCategoryStatus =
        SemanticEquivalenceCategoryStatus(if (errors.isEmpty()) "PASS" else "FAIL", checked, errors)

    private data class BoundarySpec(
        val name: String,
        val indexPath: String,
        val loadEntries: (File) -> List<BoundaryEntry>
    )

    private data class BoundaryEntry(
        val id: String,
        val path: String,
        val sha256: String
    )

    companion object {
        private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
        private val BOUNDARY_SPECS = listOf(
            BoundarySpec("Core", CoreFrozenBoundaryIndex.PATH) { file ->
                CoreFrozenBoundaryIndex.load(file).artifacts.map { BoundaryEntry(it.id, it.path, it.sha256) }
            },
            BoundarySpec("A0", A0FrozenBoundaryIndex.PATH) { file ->
                A0FrozenBoundaryIndex.load(file).artifacts.map { BoundaryEntry(it.id, it.path, it.sha256) }
            },
            BoundarySpec("A1.0", A10FrozenBoundaryIndex.PATH) { file ->
                A10FrozenBoundaryIndex.load(file).artifacts.map { BoundaryEntry(it.id, it.path, it.sha256) }
            },
            BoundarySpec("C0.1", C01FrozenBoundaryIndex.PATH) { file ->
                C01FrozenBoundaryIndex.load(file).artifacts.map { BoundaryEntry(it.id, it.path, it.sha256) }
            },
            BoundarySpec("C0.2", C02FrozenBoundaryIndex.PATH) { file ->
                C02FrozenBoundaryIndex.load(file).artifacts.map { BoundaryEntry(it.id, it.path, it.sha256) }
            }
        )

        private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString(separator = "") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
    }
}
