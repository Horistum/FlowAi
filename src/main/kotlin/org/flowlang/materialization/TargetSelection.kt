package org.flowlang.materialization

import org.flowlang.capabilities.TargetCapability
import org.flowlang.compiler.CanonicalExecutionGraphGate
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.compiler.CompilationUnit
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlannedWorkflowFailurePolicy
import org.flowlang.standard.FlowStandardVersions

enum class TargetSelectionOrigin {
    CLI_OPTION,
    INTENT_DECLARATION,
    EXPLICIT_CONFIGURATION
}

sealed interface ExplicitConfigurationSource {
    val evidenceId: String

    data class ReferenceSnapshot internal constructor(val scenarioId: String) : ExplicitConfigurationSource {
        init { requireSourceComponent(scenarioId, "Reference snapshot scenario id") }
        override val evidenceId: String = "reference-snapshot:$scenarioId"
    }

    data class ConformanceCheck internal constructor(val checkId: String) : ExplicitConfigurationSource {
        init { requireSourceComponent(checkId, "Conformance check id") }
        override val evidenceId: String = "conformance:$checkId"
    }

    data class TestFixture internal constructor(val fixtureId: String) : ExplicitConfigurationSource {
        init { requireSourceComponent(fixtureId, "Test fixture id") }
        override val evidenceId: String = "test:$fixtureId"
    }

    companion object {
        fun referenceSnapshot(scenarioId: String): ExplicitConfigurationSource =
            ReferenceSnapshot(requireSourceComponent(scenarioId, "Reference snapshot scenario id"))

        internal fun conformanceCheck(checkId: String): ExplicitConfigurationSource =
            ConformanceCheck(requireSourceComponent(checkId, "Conformance check id"))

        internal fun testFixture(fixtureId: String): ExplicitConfigurationSource =
            TestFixture(requireSourceComponent(fixtureId, "Test fixture id"))

        internal fun parseLegacy(source: String): ExplicitConfigurationSource {
            val normalized = source.trim()
            return when {
                normalized.startsWith("reference-snapshot:") ->
                    referenceSnapshot(normalized.removePrefix("reference-snapshot:"))
                normalized.startsWith("conformance:") ->
                    conformanceCheck(normalized.removePrefix("conformance:"))
                normalized.startsWith("test:") ->
                    testFixture(normalized.removePrefix("test:"))
                else -> throw UnsupportedExplicitConfigurationSourceException(normalized)
            }
        }
    }
}

data class TargetSelectionEvidence(
    val target: String,
    val origin: TargetSelectionOrigin,
    val source: String
) {
    init {
        require(target.isNotBlank()) { "Target selection evidence must name a target." }
        require(source.isNotBlank()) { "Target selection evidence must name its source." }
    }
}

sealed interface ExplicitTargetSelection {
    val target: String
    val evidence: TargetSelectionEvidence
}

class MultiWorkflowTargetMaterializationUnsupportedException(
    target: String,
    workflows: List<String>
) : IllegalStateException(
    "Target '$target' cannot materialize a multi-workflow compilation without an explicit adapter contract. " +
        "Workflows: ${workflows.sorted().joinToString()}."
)

private fun CompilationUnit.requireSingleTargetWorkflow(target: String) {
    if (workflowPlanSet.multiWorkflow) {
        throw MultiWorkflowTargetMaterializationUnsupportedException(
            target,
            workflowPlanSet.workflows.map { it.workflowName }
        )
    }
}

sealed interface TargetSelectionDecision {
    data object NotSelected : TargetSelectionDecision
    data class Selected(val selection: ExplicitTargetSelection) : TargetSelectionDecision
}

object TargetSelectionAuthority {
    private data class IssuedSelection(
        override val target: String,
        override val evidence: TargetSelectionEvidence
    ) : ExplicitTargetSelection

    fun fromCliOption(value: String?, targets: Map<String, TargetCapability>): TargetSelectionDecision = value?.let {
        TargetSelectionDecision.Selected(issue(it, TargetSelectionOrigin.CLI_OPTION, "cli:--target", targets))
    } ?: TargetSelectionDecision.NotSelected

    fun fromIntentDeclaration(
        value: String,
        sourcePath: String,
        targets: Map<String, TargetCapability>
    ): ExplicitTargetSelection = issue(
        value = value,
        origin = TargetSelectionOrigin.INTENT_DECLARATION,
        source = requireSourceComponent(sourcePath, "Intent target source path"),
        targets = targets
    )

    fun fromReferenceSnapshot(
        value: String,
        scenarioId: String,
        targets: Map<String, TargetCapability>
    ): ExplicitTargetSelection = issueExplicitConfiguration(
        value,
        ExplicitConfigurationSource.referenceSnapshot(scenarioId),
        targets
    )

    internal fun fromConformanceCheck(
        value: String,
        checkId: String,
        targets: Map<String, TargetCapability>
    ): ExplicitTargetSelection = issueExplicitConfiguration(
        value,
        ExplicitConfigurationSource.conformanceCheck(checkId.removePrefix("conformance:")),
        targets
    )

    internal fun fromTestFixture(
        value: String,
        fixtureId: String,
        targets: Map<String, TargetCapability>
    ): ExplicitTargetSelection = issueExplicitConfiguration(
        value,
        ExplicitConfigurationSource.testFixture(fixtureId.removePrefix("test:")),
        targets
    )

    @Deprecated(message = "Use the typed source-specific target selection factory.")
    internal fun fromExplicitConfiguration(
        value: String,
        source: String,
        targets: Map<String, TargetCapability>
    ): ExplicitTargetSelection = issueExplicitConfiguration(
        value,
        ExplicitConfigurationSource.parseLegacy(source),
        targets
    )

    fun requireSelected(decision: TargetSelectionDecision, operation: String): ExplicitTargetSelection =
        when (decision) {
            TargetSelectionDecision.NotSelected -> throw MissingExplicitTargetSelectionException(operation)
            is TargetSelectionDecision.Selected -> decision.selection
        }

    private fun issueExplicitConfiguration(
        value: String,
        source: ExplicitConfigurationSource,
        targets: Map<String, TargetCapability>
    ): ExplicitTargetSelection = issue(
        value = value,
        origin = TargetSelectionOrigin.EXPLICIT_CONFIGURATION,
        source = source.evidenceId,
        targets = targets
    )

    private fun issue(
        value: String,
        origin: TargetSelectionOrigin,
        source: String,
        targets: Map<String, TargetCapability>
    ): ExplicitTargetSelection {
        require(targets.isNotEmpty()) { "Explicit target selection requires a non-empty target registry." }
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "Explicit target selection must not be blank." }
        val capability = targets[normalized]
            ?: throw UnknownExplicitTargetSelectionException(normalized, targets.keys.sorted())
        require(capability.target == normalized) {
            "Target registry key '$normalized' does not match declared target '${capability.target}'."
        }
        return IssuedSelection(
            target = normalized,
            evidence = TargetSelectionEvidence(normalized, origin, source)
        )
    }
}

class MissingExplicitTargetSelectionException(operation: String) : IllegalArgumentException(
    "$operation requires an explicit target selection. Flow never selects a target implicitly."
)

class UnknownExplicitTargetSelectionException(
    target: String,
    availableTargets: List<String>
) : IllegalArgumentException(
    "Unknown target '$target'. Available targets: ${availableTargets.joinToString().ifBlank { "none" }}."
)

class UnsupportedExplicitConfigurationSourceException(source: String) : IllegalArgumentException(
    "Unsupported explicit target configuration source '$source'. Use a typed reference-snapshot, conformance or test source."
)

/**
 * A target request whose product form is bound to one exact graph authorization.
 *
 * The internal compatibility form deliberately retains the original plan until
 * the existing planning-evidence validator has examined it. This prevents a graph
 * projection from normalizing forged retained fields before the established
 * InvalidPlanningEvidenceException boundary can report them. Accessing
 * [authorization] after that validation still performs the full graph gate.
 */
class TargetMaterializationRequest private constructor(
    private val compilationAuthorization: CompilationAuthorization?,
    private val compatibilityPlan: ExecutionPlan?,
    private val compatibilityEvidenceId: String?,
    private val compatibilityFailurePolicy: PlannedWorkflowFailurePolicy,
    val selection: ExplicitTargetSelection,
    val strict: Boolean = false
) {
    private val compatibilityAuthorization: Lazy<CompilationAuthorization> = lazy {
        CanonicalExecutionGraphGate.authorizeCompatibilityPlan(
            plannerPlan = requireNotNull(compatibilityPlan),
            evidenceId = requireNotNull(compatibilityEvidenceId),
            failurePolicy = compatibilityFailurePolicy
        )
    }

    val authorization: CompilationAuthorization
        get() = compilationAuthorization ?: compatibilityAuthorization.value
    val plan: ExecutionPlan
        get() = if (compilationAuthorization != null) authorization.executionPlan else requireNotNull(compatibilityPlan)
    val graphDigest: String
        get() = authorization.graphDigest.value
    val target: String get() = selection.target
    val graphAuthorized: Boolean get() = compilationAuthorization != null

    init {
        require((compilationAuthorization == null) != (compatibilityPlan == null)) {
            "Materialization request must carry exactly one compilation authorization or compatibility plan."
        }
        if (compilationAuthorization != null) compilationAuthorization.requireIntegrity()
        if (compatibilityPlan != null) {
            require(!compatibilityEvidenceId.isNullOrBlank()) {
                "Compatibility materialization request must declare evidence identity."
            }
        }
    }

    companion object {
        fun fromCompilation(
            compilation: CompilationUnit,
            selection: ExplicitTargetSelection,
            strict: Boolean = false
        ): TargetMaterializationRequest {
            compilation.requireSingleTargetWorkflow(selection.target)
            return TargetMaterializationRequest(
                compilationAuthorization = compilation.authorization,
                compatibilityPlan = null,
                compatibilityEvidenceId = null,
                compatibilityFailurePolicy = PlannedWorkflowFailurePolicy.none(),
                selection = selection,
                strict = strict
            )
        }

        internal fun fromCompatibilityPlan(
            plan: ExecutionPlan,
            selection: ExplicitTargetSelection,
            strict: Boolean = false,
            evidenceId: String = selection.evidence.source,
            failurePolicy: PlannedWorkflowFailurePolicy = PlannedWorkflowFailurePolicy.none()
        ): TargetMaterializationRequest = TargetMaterializationRequest(
            compilationAuthorization = null,
            compatibilityPlan = plan,
            compatibilityEvidenceId = requireSourceComponent(evidenceId, "Compatibility-plan evidence id"),
            compatibilityFailurePolicy = failurePolicy,
            selection = selection,
            strict = strict
        )
    }

    @Deprecated(message = "Use the graph-bound factory or inventoried compatibility factory.")
    internal constructor(
        plan: ExecutionPlan,
        selection: ExplicitTargetSelection,
        strict: Boolean = false
    ) : this(
        compilationAuthorization = null,
        compatibilityPlan = plan,
        compatibilityEvidenceId = selection.evidence.source,
        compatibilityFailurePolicy = PlannedWorkflowFailurePolicy.none(),
        selection = selection,
        strict = strict
    )
}

class TargetDiagnosticMaterializationRequest private constructor(
    private val compilationAuthorization: CompilationAuthorization?,
    private val compatibilityPlan: ExecutionPlan?,
    private val compatibilityEvidenceId: String?,
    private val compatibilityFailurePolicy: PlannedWorkflowFailurePolicy,
    val selection: ExplicitTargetSelection
) {
    private val compatibilityAuthorization: Lazy<CompilationAuthorization> = lazy {
        CanonicalExecutionGraphGate.authorizeCompatibilityPlan(
            plannerPlan = requireNotNull(compatibilityPlan),
            evidenceId = requireNotNull(compatibilityEvidenceId),
            failurePolicy = compatibilityFailurePolicy
        )
    }

    val authorization: CompilationAuthorization
        get() = compilationAuthorization ?: compatibilityAuthorization.value
    val plan: ExecutionPlan
        get() = if (compilationAuthorization != null) authorization.executionPlan else requireNotNull(compatibilityPlan)
    val graphDigest: String
        get() = authorization.graphDigest.value
    val target: String get() = selection.target
    val graphAuthorized: Boolean get() = compilationAuthorization != null

    init {
        require((compilationAuthorization == null) != (compatibilityPlan == null)) {
            "Diagnostic request must carry exactly one compilation authorization or compatibility plan."
        }
        if (compilationAuthorization != null) compilationAuthorization.requireIntegrity()
        if (compatibilityPlan != null) {
            require(!compatibilityEvidenceId.isNullOrBlank()) {
                "Compatibility diagnostic request must declare evidence identity."
            }
        }
    }

    companion object {
        fun fromCompilation(
            compilation: CompilationUnit,
            selection: ExplicitTargetSelection
        ): TargetDiagnosticMaterializationRequest {
            compilation.requireSingleTargetWorkflow(selection.target)
            return TargetDiagnosticMaterializationRequest(
                compilationAuthorization = compilation.authorization,
                compatibilityPlan = null,
                compatibilityEvidenceId = null,
                compatibilityFailurePolicy = PlannedWorkflowFailurePolicy.none(),
                selection = selection
            )
        }

        internal fun fromCompatibilityPlan(
            plan: ExecutionPlan,
            selection: ExplicitTargetSelection,
            evidenceId: String = selection.evidence.source,
            failurePolicy: PlannedWorkflowFailurePolicy = PlannedWorkflowFailurePolicy.none()
        ): TargetDiagnosticMaterializationRequest = TargetDiagnosticMaterializationRequest(
            compilationAuthorization = null,
            compatibilityPlan = plan,
            compatibilityEvidenceId = requireSourceComponent(evidenceId, "Compatibility-plan evidence id"),
            compatibilityFailurePolicy = failurePolicy,
            selection = selection
        )
    }

    @Deprecated(message = "Use the graph-bound factory or inventoried compatibility factory.")
    internal constructor(
        plan: ExecutionPlan,
        selection: ExplicitTargetSelection
    ) : this(
        compilationAuthorization = null,
        compatibilityPlan = plan,
        compatibilityEvidenceId = selection.evidence.source,
        compatibilityFailurePolicy = PlannedWorkflowFailurePolicy.none(),
        selection = selection
    )
}

data class TargetSelectionEvidenceReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val planVersion: String,
    val flowName: String,
    val target: String,
    val origin: TargetSelectionOrigin,
    val source: String,
    val explicit: Boolean = true
) {
    companion object {
        fun from(plan: ExecutionPlan, selection: ExplicitTargetSelection): TargetSelectionEvidenceReport =
            TargetSelectionEvidenceReport(
                planVersion = plan.planVersion,
                flowName = plan.flowName,
                target = selection.target,
                origin = selection.evidence.origin,
                source = selection.evidence.source
            )
    }
}

private fun requireSourceComponent(value: String, label: String): String {
    val normalized = value.trim()
    require(normalized.isNotEmpty()) { "$label must not be blank." }
    require('\n' !in normalized && '\r' !in normalized) { "$label must be a single line." }
    return normalized
}
