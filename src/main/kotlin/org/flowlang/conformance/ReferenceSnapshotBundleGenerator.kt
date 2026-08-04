package org.flowlang.conformance

import org.flowlang.adapters.trigger.AdapterTriggerAuthorizedRenderingAuthority
import org.flowlang.adapters.trigger.AdapterTriggerMaterializationAuthority
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.cli.Json
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.planner.FlowPlanner
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.validator.FlowValidator
import java.io.File

/**
 * Generates committed reference snapshots through the production semantic,
 * plan-specific capability, materialization, trigger and rendering boundaries.
 */
class ReferenceSnapshotBundleGenerator(
    private val rootDir: File = File("."),
    private val registry: ModuleRegistry = ModuleRegistry.fromDirectory(File(rootDir, "modules")),
    private val targets: Map<String, org.flowlang.capabilities.TargetCapability> =
        TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets")),
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry
) {
    private val manifestPipeline = BuiltInTargetProjections.pipeline(targets, rootDir)
    private val triggerAuthority = AdapterTriggerMaterializationAuthority(rootDir, targets, projections)
    private val renderingAuthority = AdapterTriggerAuthorizedRenderingAuthority(rootDir, projections)

    internal fun planFor(intentFile: File): ExecutionPlan {
        require(intentFile.isFile) { "Reference intent does not exist: ${intentFile.path}" }
        val intent = IntentYamlLoader.load(intentFile)
        IntentCapabilityValidator(registry).validate(intent).assertValid()
        val ast = IntentToAstPlanner(registry).plan(intent)
        val validation = FlowValidator(registry).validate(ast)
        require(validation.valid) { validation.issues.joinToString { it.code + ": " + it.message } }
        return FlowPlanner(registry).plan(ast)
    }

    fun generate(
        intentFile: File,
        outputDir: File,
        scenarioId: String,
        targetIds: Set<String> = projections.targetIds
    ): ReferenceSnapshotSet {
        require(intentFile.isFile) { "Reference intent does not exist: ${intentFile.path}" }
        require(targetIds.isNotEmpty()) { "Reference snapshot generation requires at least one target." }
        require(targets.keys.containsAll(targetIds)) {
            "Unknown reference targets: ${(targetIds - targets.keys).sorted().joinToString()}."
        }
        require(projections.targetIds.containsAll(targetIds)) {
            "Missing reference projection providers: ${(targetIds - projections.targetIds).sorted().joinToString()}."
        }

        val intent = IntentYamlLoader.load(intentFile)
        IntentCapabilityValidator(registry).validate(intent).assertValid()
        val ast = IntentToAstPlanner(registry).plan(intent)
        val validation = FlowValidator(registry).validate(ast)
        require(validation.valid) { validation.issues.joinToString { it.code + ": " + it.message } }
        val plan = FlowPlanner(registry).plan(ast)
        val evidence = mutableListOf<ReferenceTargetProjectionEvidence>()
        val renderedByTarget = linkedMapOf<String, String>()

        targetIds.sorted().forEach { target ->
            val effectiveTargets = manifestPipeline.effectiveTargets(plan, target)
            val compatibility = CompatibilityAnalyzer(effectiveTargets).analyze(plan, target, strict = false)
            val readiness = ExecutionReadinessAnalyzer(effectiveTargets).analyze(plan, target, strict = false)
            if (compatibility.hasErrors || !readiness.generationAllowed) {
                evidence += ReferenceBlockedProjectionEvidence(compatibility, readiness)
            } else {
                val selection = TargetSelectionAuthority.fromReferenceSnapshot(
                    value = target,
                    scenarioId = scenarioId,
                    targets = targets
                )
                val generated = manifestPipeline.generate(TargetMaterializationRequest(plan, selection))
                val triggerAssessment = triggerAuthority.assess(plan, target)
                val manifest = triggerAuthority.reconcileDiagnostic(generated, triggerAssessment)
                val renderReadiness = TargetRenderPolicy.evaluate(manifest)
                require(renderReadiness.mode != TargetRenderMode.FAIL_FAST) {
                    "Target '$target' passed compatibility but manifest evidence is fail-fast: ${renderReadiness.findings}."
                }
                val rendering = renderingAuthority.render(manifest)
                require(rendering.receipt.renderMode == renderReadiness.mode) {
                    "Target '$target' rendering receipt mode ${rendering.receipt.renderMode} contradicts manifest readiness ${renderReadiness.mode}."
                }
                evidence += ReferenceManifestProjectionEvidence(manifest, renderedArtifactPresent = true)
                renderedByTarget[target] = rendering.artifact.content
            }
        }

        val snapshot = ReferenceSnapshotHonesty.build(scenarioId, evidence)
        val validationIssues = ReferenceSnapshotHonesty.validate(snapshot)
        require(validationIssues.isEmpty()) {
            "Generated reference snapshot is inconsistent: ${validationIssues.joinToString()}"
        }

        prepareOutputDirectory(outputDir)
        writeJson(outputDir, "normalized-intent.json", intent)
        writeJson(outputDir, "flow-ast.json", ast)
        writeJson(outputDir, "execution-plan.json", ExecutionPlanCanonicalizer.canonicalize(plan))
        writeJson(outputDir, "snapshot-index.json", snapshot)

        snapshot.targets.forEach { targetState ->
            val fileName = ReferenceSnapshotHonesty.projectionFile(targetState.target, targetState.renderMode)
            when (targetState.renderMode) {
                TargetRenderMode.FAIL_FAST -> writeJson(outputDir, fileName, targetState)
                TargetRenderMode.REVIEW_ONLY, TargetRenderMode.EXECUTABLE -> {
                    val rendered = renderedByTarget[targetState.target]
                        ?: error("Missing rendered reference artifact for '${targetState.target}'.")
                    File(outputDir, fileName).writeText(rendered)
                }
            }
        }
        return snapshot
    }

    private fun prepareOutputDirectory(outputDir: File) {
        outputDir.mkdirs()
        outputDir.listFiles().orEmpty()
            .filter { file -> file.isFile && isManagedSnapshotArtifact(file.name) }
            .forEach { file ->
                require(file.delete()) { "Unable to replace generated snapshot artifact: ${file.path}" }
            }
    }

    private fun isManagedSnapshotArtifact(name: String): Boolean =
        name in MANAGED_SNAPSHOT_FILES ||
            name in ReferenceSnapshotHonesty.legacyExecutableLookingFiles ||
            TARGET_SNAPSHOT_FILE.matches(name)

    private fun writeJson(outputDir: File, name: String, value: Any) {
        File(outputDir, name).writeText(Json.mapper.writeValueAsString(value) + "\n")
    }

    private companion object {
        val MANAGED_SNAPSHOT_FILES: Set<String> = setOf(
            "normalized-intent.json",
            "flow-ast.json",
            "execution-plan.json",
            "snapshot-index.json"
        )
        val TARGET_SNAPSHOT_FILE: Regex = Regex("^[a-z0-9][a-z0-9-]*\\.(?:executable|review)\\.yaml$|^[a-z0-9][a-z0-9-]*\\.blocked\\.json$")
    }
}
