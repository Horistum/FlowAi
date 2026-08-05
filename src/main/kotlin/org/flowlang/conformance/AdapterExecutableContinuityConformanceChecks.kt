package org.flowlang.conformance

import java.io.File
import java.nio.file.Files
import org.flowlang.adapters.continuity.AdapterContinuityClaimStatus
import org.flowlang.adapters.continuity.AdapterContinuityEvidenceLoader
import org.flowlang.adapters.continuity.AdapterContinuityFamily
import org.flowlang.adapters.continuity.AdapterContinuitySemanticContract
import org.flowlang.adapters.continuity.AdapterExecutableContinuityRoadmapLifecycleAuthority
import org.flowlang.adapters.continuity.BuiltInAdapterContinuityScopedSupport
import org.flowlang.adapters.portfolio.AdapterExecutableReferencePromotionAuthority
import org.flowlang.adapters.portfolio.AdapterExecutableReferencePromotionLoader
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.cli.Json
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.TaskNode
import org.flowlang.serialization.FlowYaml
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.targets.builtin.GitHubActionsWorkspaceContinuityPlanner

class AdapterExecutableContinuityConformanceChecks(
    private val rootDir: File,
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry
) {
    fun checks(): List<ConformanceCheck> {
        val lifecycleResult = runCatching { AdapterExecutableContinuityRoadmapLifecycleAuthority(rootDir).analyze() }
        val lifecycle = lifecycleResult.getOrNull()
        val promotionResult = runCatching {
            AdapterExecutableReferencePromotionAuthority(rootDir, targets, projections).analyze()
        }
        val promotion = promotionResult.getOrNull()
        val executableResult = runCatching(::executableReferenceErrors)
        val boundaryResult = runCatching(::failClosedBoundaryErrors)
        val jenkinsResult = runCatching(::jenkinsPreservationErrors)

        val lifecycleErrors = buildList {
            lifecycleResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            lifecycle?.failedChecks?.forEach { id ->
                val failed = lifecycle.checks.first { it.id == id }
                add("$id:${failed.evidence.joinToString()}:${failed.message}")
            }
        }
        val promotionErrors = buildList {
            promotionResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            promotion?.findings?.forEach { add("${it.code}:${it.target}:${it.message}") }
        }

        return listOf(
            ConformanceCheck(
                name = LIFECYCLE_CHECK,
                passed = lifecycle?.status == "PASS",
                message = lifecycleErrors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = PROMOTION_CHECK,
                passed = promotion?.status == "PASS",
                message = promotionErrors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
            ),
            resultCheck(EXECUTABLE_REFERENCE_CHECK, executableResult),
            resultCheck(FAIL_CLOSED_BOUNDARY_CHECK, boundaryResult),
            resultCheck(JENKINS_PRESERVATION_CHECK, jenkinsResult)
        )
    }

    private fun executableReferenceErrors(): List<String> = buildList {
        val promotions = AdapterExecutableReferencePromotionLoader.load(rootDir).promotions
        if (promotions.size != 1) {
            add("A1.0 must declare exactly one bounded executable-reference promotion, found ${promotions.size}.")
            return@buildList
        }
        val promotion = promotions.single()
        if (promotion.target != GITHUB_ACTIONS) {
            add("A1.0 promotion target must be '$GITHUB_ACTIONS', found '${promotion.target}'.")
            return@buildList
        }
        val snapshotFile = File(rootDir, promotion.snapshotReference)
        val committedResult = runCatching {
            Json.mapper.readValue(snapshotFile, ReferenceSnapshotSet::class.java)
        }
        val committed = committedResult.getOrNull()
        if (committed == null) {
            add("GitHub Actions executable snapshot cannot be parsed: ${committedResult.exceptionOrNull()?.message}")
            return@buildList
        }
        ReferenceSnapshotHonesty.validate(committed).forEach { add("Invalid executable snapshot: $it") }
        val targetState = committed.targets.singleOrNull()
        if (targetState == null || targetState.target != GITHUB_ACTIONS || !targetState.executable) {
            add("A1.0 snapshot must contain exactly one executable GitHub Actions target state.")
        }

        val intentFile = File(rootDir, "examples/intent/${promotion.scenarioId}.intent.yaml")
        val generatedDir = Files.createTempDirectory("flow-a1-conformance").toFile()
        try {
            val generated = ReferenceSnapshotBundleGenerator(
                rootDir = rootDir,
                targets = targets,
                projections = projections
            ).generate(
                intentFile = intentFile,
                outputDir = generatedDir,
                scenarioId = promotion.scenarioId,
                targetIds = setOf(GITHUB_ACTIONS)
            )
            if (generated != committed) {
                add("Committed GitHub Actions snapshot index differs from production regeneration.")
            }
            compareManagedFiles(snapshotFile.parentFile, generatedDir).forEach(::add)
        } finally {
            generatedDir.deleteRecursively()
        }

        val workflowFile = File(snapshotFile.parentFile, "github-actions.executable.yaml")
        if (!workflowFile.isFile) {
            add("GitHub Actions executable workflow is missing: ${workflowFile.path}")
        } else {
            val workflow = workflowFile.readText()
            val checkout = workflow.indexOf("actions/checkout@v4")
            val upload = workflow.indexOf(GitHubActionsWorkspaceContinuityPlanner.UPLOAD_REFERENCE)
            val download = workflow.indexOf(GitHubActionsWorkspaceContinuityPlanner.DOWNLOAD_REFERENCE)
            val build = workflow.indexOf("docker/build-push-action@v7")
            if (!(checkout >= 0 && upload > checkout && download > upload && build > download)) {
                add("GitHub Actions workflow must order checkout, upload, download and image build through production payloads.")
            }
            REQUIRED_WORKFLOW_FRAGMENTS.filterNot(workflow::contains).forEach { fragment ->
                add("GitHub Actions executable workflow is missing required fragment: $fragment")
            }
        }
    }

    private fun failClosedBoundaryErrors(): List<String> = buildList {
        genericScopedEvidenceReconciliationErrors().forEach(::add)

        val intentFile = File(rootDir, "examples/intent/checkout-build-image.intent.yaml")
        val generator = ReferenceSnapshotBundleGenerator(rootDir, targets = targets, projections = projections)
        val plan = generator.planFor(intentFile)
        val pipeline = BuiltInTargetProjections.pipeline(targets, rootDir)
        val declared = targets.getValue(GITHUB_ACTIONS)
        if (declared.features[WORKSPACE_FEATURE] != SupportLevel.UNSUPPORTED) {
            add("The general GitHub Actions registry claim must remain workspace-unsupported.")
        }
        val promoted = pipeline.effectiveTarget(plan, GITHUB_ACTIONS)
        if (promoted.features[WORKSPACE_FEATURE] != SupportLevel.SUPPORTED) {
            add("The exact checkout-build-image plan must receive bounded workspace support.")
        }
        listOf(VALUE_FEATURE, STATE_FEATURE).forEach { feature ->
            if (promoted.features[feature] != declared.features[feature]) {
                add("A1.0 must not change unrelated continuity feature '$feature'.")
            }
        }

        val wrongChannel = plan.copy(
            dependencyRelations = plan.dependencyRelations.map { relation ->
                if (relation.kind == PlanDependencyKind.WORKSPACE) relation.copy(channel = "other") else relation
            }
        )
        if (pipeline.effectiveTarget(wrongChannel, GITHUB_ACTIONS).features[WORKSPACE_FEATURE] != SupportLevel.UNSUPPORTED) {
            add("A workspace relation on an undeclared channel must remain unsupported.")
        }

        val largerPlan = plan.copy(
            nodes = plan.nodes + TaskNode(
                id = "standard_execute_a1_probe",
                module = "standard",
                action = "execute",
                target = "follow-up"
            )
        )
        if (pipeline.effectiveTarget(largerPlan, GITHUB_ACTIONS).features[WORKSPACE_FEATURE] != SupportLevel.UNSUPPORTED) {
            add("A larger workflow must not borrow executability from the bounded reference scenario.")
        }
    }

    private fun genericScopedEvidenceReconciliationErrors(): List<String> = buildList {
        val scope = BuiltInAdapterContinuityScopedSupport.githubActionsCheckoutBuildWorkspace
        val claim = AdapterContinuityEvidenceLoader.load(rootDir)
            .targets.singleOrNull { it.target == scope.target }
            ?.claims
            ?.singleOrNull { it.family == scope.family }
        if (claim == null) {
            add("The generic GitHub Actions ARTIFACT continuity claim is missing while scoped support exists.")
            return@buildList
        }
        if (claim.status != AdapterContinuityClaimStatus.UNSUPPORTED) {
            add("The generic GitHub Actions ARTIFACT claim must remain UNSUPPORTED while only bounded scoped support exists.")
        }

        val mechanismFragments = listOf(
            "Generic GitHub Actions workspace continuity remains unsupported",
            scope.sourceAction,
            scope.targetAction,
            GitHubActionsWorkspaceContinuityPlanner.UPLOAD_REFERENCE,
            GitHubActionsWorkspaceContinuityPlanner.DOWNLOAD_REFERENCE
        )
        mechanismFragments.filterNot(claim.mechanism::contains).forEach { fragment ->
            add("The generic GitHub Actions ARTIFACT mechanism does not acknowledge scoped evidence fragment '$fragment'.")
        }

        val unsupportedReason = claim.semantics.unsupported[
            AdapterContinuitySemanticContract.ARTIFACT_SHARED_WORKSPACE
        ].orEmpty()
        if (!unsupportedReason.contains("outside the exact scoped declaration")) {
            add("The generic workspace reason must distinguish target-wide unsupported behavior from the exact scoped exception.")
        }

        val requiredEvidence = listOf(
            SCOPED_SUPPORT_SOURCE,
            SCOPED_PLANNER_SOURCE,
            SCOPED_WORKFLOW_SOURCE
        )
        requiredEvidence.filterNot(claim.evidenceReferences::contains).forEach { reference ->
            add("The generic GitHub Actions ARTIFACT claim is missing scoped evidence reference '$reference'.")
        }

        val genericLimitations = claim.limitations.joinToString(" ")
        val scopedLimitations = scope.limitations.joinToString(" ")
        listOf("target-wide support", "Unix mode bits", "symbolic-link identity").forEach { fragment ->
            if (!genericLimitations.contains(fragment, ignoreCase = true)) {
                add("The generic GitHub Actions ARTIFACT claim is missing limitation '$fragment'.")
            }
        }
        listOf("regular-file bytes", "Unix mode bits", "Symbolic-link identity").forEach { fragment ->
            if (!scopedLimitations.contains(fragment, ignoreCase = true)) {
                add("The scoped GitHub Actions declaration is missing fidelity limitation '$fragment'.")
            }
        }
    }

    private fun jenkinsPreservationErrors(): List<String> = buildList {
        val snapshotDir = File(rootDir, "conformance/snapshots/checkout-build-image")
        val snapshotFile = File(snapshotDir, "snapshot-index.json")
        val committedResult = runCatching { Json.mapper.readValue(snapshotFile, ReferenceSnapshotSet::class.java) }
        val committed = committedResult.getOrNull()
        if (committed == null) {
            add("Historical Jenkins snapshot cannot be parsed: ${committedResult.exceptionOrNull()?.message}")
            return@buildList
        }
        val targetState = committed.targets.singleOrNull()
        if (targetState == null || targetState.target != JENKINS || !targetState.executable) {
            add("Historical checkout-build-image snapshot must remain one executable Jenkins target.")
        }
        if (File(snapshotDir, "github-actions.executable.yaml").exists()) {
            add("A1.0 must not inject GitHub Actions evidence into the frozen Jenkins snapshot directory.")
        }

        val generatedDir = Files.createTempDirectory("flow-jenkins-preservation").toFile()
        try {
            val generated = ReferenceSnapshotBundleGenerator(
                rootDir = rootDir,
                targets = targets,
                projections = projections
            ).generate(
                intentFile = File(rootDir, "examples/intent/checkout-build-image.intent.yaml"),
                outputDir = generatedDir,
                scenarioId = "checkout-build-image",
                targetIds = setOf(JENKINS)
            )
            if (generated != committed) {
                add("Historical Jenkins snapshot index changed while implementing A1.0.")
            }
            compareManagedFiles(snapshotDir, generatedDir).forEach(::add)
        } finally {
            generatedDir.deleteRecursively()
        }
    }

    private fun compareManagedFiles(committedDir: File, generatedDir: File): List<String> = buildList {
        val committed = committedDir.listFiles().orEmpty()
            .filter { it.isFile && it.name != "README.md" }
            .associateBy(File::getName)
        val generated = generatedDir.listFiles().orEmpty().filter(File::isFile).associateBy(File::getName)
        if (committed.keys != generated.keys) {
            add("Snapshot file set differs: committed=${committed.keys.sorted()} generated=${generated.keys.sorted()}.")
            return@buildList
        }
        committed.keys.sorted().forEach { name ->
            val left = committed.getValue(name)
            val right = generated.getValue(name)
            val equal = if (name.endsWith(".json")) {
                Json.mapper.readTree(left) == Json.mapper.readTree(right)
            } else {
                left.readText().trimEnd() == right.readText().trimEnd()
            }
            if (!equal) add("Snapshot artifact differs from production regeneration: $name")
        }
    }

    private fun resultCheck(name: String, result: Result<List<String>>): ConformanceCheck {
        val errors = result.getOrElse { listOf(it.message ?: it.javaClass.simpleName) }
        return ConformanceCheck(
            name = name,
            passed = errors.isEmpty(),
            message = errors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
        )
    }

    companion object {
        const val LIFECYCLE_CHECK = "adapters.a1.0.lifecycle-integrity"
        const val PROMOTION_CHECK = "adapters.a1.0.promotion-integrity"
        const val EXECUTABLE_REFERENCE_CHECK = "adapters.a1.0.executable-reference"
        const val FAIL_CLOSED_BOUNDARY_CHECK = "adapters.a1.0.fail-closed-boundary"
        const val JENKINS_PRESERVATION_CHECK = "adapters.a1.0.jenkins-reference-preservation"
        private const val GITHUB_ACTIONS = "github-actions"
        private const val JENKINS = "jenkins"
        private const val WORKSPACE_FEATURE = "continuity.workspace"
        private const val VALUE_FEATURE = "continuity.value"
        private const val STATE_FEATURE = "continuity.state"
        private const val SCOPED_SUPPORT_SOURCE =
            "src/main/kotlin/org/flowlang/adapters/continuity/AdapterContinuityScopedSupport.kt"
        private const val SCOPED_PLANNER_SOURCE =
            "src/main/kotlin/org/flowlang/targets/builtin/GitHubActionsWorkspaceContinuityPlanner.kt"
        private const val SCOPED_WORKFLOW_SOURCE =
            "conformance/snapshots/github-actions-checkout-build-image/github-actions.executable.yaml"
        private val REQUIRED_WORKFLOW_FRAGMENTS = listOf(
            "name: \"flow-workspace-git_checkout_1-source\"",
            "needs: [git_checkout_1]",
            "path: \".\"",
            "if-no-files-found: \"error\"",
            "include-hidden-files: \"true\""
        )
    }
}

data class AdapterA1ConformanceInventory(val version: String, val checks: List<String>) {
    init {
        require(version == VERSION) { "A1 conformance inventory version '$version' is unsupported; expected '$VERSION'." }
        require(checks.isNotEmpty()) { "A1 conformance inventory must declare checks." }
        require(checks.none(String::isBlank)) { "A1 conformance inventory contains a blank check id." }
        require(checks.size == checks.toSet().size) { "A1 conformance inventory contains duplicate check ids." }
    }

    companion object {
        const val PATH = "adapters/conformance/a1-check-inventory.yaml"
        const val VERSION = "1.0"
        private val KEYS = setOf("version", "checks")

        fun load(rootDir: File): AdapterA1ConformanceInventory {
            val file = File(rootDir, PATH)
            require(file.isFile) { "A1 conformance inventory is missing: ${file.path}" }
            val yaml = FlowYaml.readMap(file)
            val unknown = yaml.keys - KEYS
            val missing = KEYS - yaml.keys
            require(unknown.isEmpty()) { "$PATH has unknown fields: ${unknown.sorted()}." }
            require(missing.isEmpty()) { "$PATH is missing fields: ${missing.sorted()}." }
            val checks = (yaml["checks"] as? Iterable<*>)?.mapIndexed { index, value ->
                (value as? String)?.takeIf(String::isNotBlank)
                    ?: error("$PATH.checks[$index] must be non-blank text.")
            } ?: error("$PATH.checks must be a list.")
            return AdapterA1ConformanceInventory(yaml["version"]?.toString().orEmpty(), checks)
        }
    }
}

class AdapterExecutableContinuityConformanceRunner(
    private val rootDir: File,
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry
) {
    fun checks(): List<ConformanceCheck> {
        val produced = AdapterExecutableContinuityConformanceChecks(rootDir, targets, projections).checks()
        val inventoryResult = runCatching { AdapterA1ConformanceInventory.load(rootDir) }
        val inventory = inventoryResult.getOrNull()
        val observed = produced.map { it.name }
        val exact = inventory != null && observed == inventory.checks
        val message = when {
            inventoryResult.isFailure -> inventoryResult.exceptionOrNull()?.message
            !exact -> "declared=${inventory?.checks?.joinToString()} observed=${observed.joinToString()}"
            else -> null
        }
        return listOf(
            ConformanceCheck(INVENTORY_CHECK, exact, message)
        ) + produced
    }

    companion object {
        const val INVENTORY_CHECK = "adapters.a1.0.inventory-exact"
    }
}
