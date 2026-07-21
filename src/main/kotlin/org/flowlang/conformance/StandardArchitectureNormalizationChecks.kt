package org.flowlang.conformance

import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.cli.Json
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.effects.CanonicalIntentEffectAuthority
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.effects.ResourceState
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ClarificationSeverity
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.intent.CanonicalIntentMeaningAuthority
import org.flowlang.intent.IntentBindingStatus
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDesignAnalyzer
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentSystem
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.StandardIntentCatalog
import org.flowlang.validator.FlowValidator
import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry

internal class StandardArchitectureNormalizationChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        checkRenderedSnapshotsContainVersion(),
        checkStandardIntentCatalogCoverage(),
        checkStandardCapabilityContracts(),
        checkCanonicalIntentMeaning(),
        checkUniversalEffectModel(),
        checkIntentDesignReport(),
        checkCorePackagesDoNotImportJackson(),
        checkModulesDoNotOwnTargetRendering(),
        checkAiNormalizationDeployment(),
        checkAiNormalizationFullPipelineValidation(),
        checkAiNormalizationMissingApplicationQuestion()
    )

    private fun checkRenderedSnapshotsContainVersion(): ConformanceCheck = runCheck("snapshots.rendered.standard-version") {
        val dir = File(rootDir, "conformance/snapshots/build-test-deploy")
        val standardVersion = FlowStandardVersions.FLOW_STANDARD_VERSION
        val review = File(dir, "jenkins.review.yaml").readText()
        require(review.contains(standardVersion)) {
            "Snapshot jenkins.review.yaml does not contain Flow standard version $standardVersion"
        }
        require(review.contains("renderMode: REVIEW_ONLY"))
        require(review.contains("executable: false"))
        listOf("github-actions.blocked.json", "tekton.blocked.json").forEach { name ->
            val blocked = Json.mapper.readValue(File(dir, name), ReferenceSnapshotTargetState::class.java)
            require(blocked.renderMode == TargetRenderMode.FAIL_FAST)
            require(!blocked.manifestPresent && !blocked.renderedArtifactPresent)
        }
        val index = Json.mapper.readValue(File(dir, "snapshot-index.json"), ReferenceSnapshotSet::class.java)
        require(index.versionBoundary.implementationPackageVersion == FlowStandardVersions.IMPLEMENTATION_PACKAGE_VERSION)
        require(index.versionBoundary.publicStandardVersion == standardVersion)
        require(index.versionBoundary.artifactContractVersion == FlowStandardVersions.TARGET_MANIFEST_VERSION)
    }

    private fun checkStandardIntentCatalogCoverage(): ConformanceCheck = runCheck("standard.catalog.coverage") {
        val capabilities = org.flowlang.intent.StandardCapability.values().toSet()
        val catalog = StandardIntentCatalog.byCapability.keys
        require(catalog.containsAll(capabilities)) { "Standard catalog does not cover all StandardCapability enum values." }
        require(StandardIntentCatalog.definitions.any { it.capability.name == "BACKUP" }) { "Catalog must include non-CI/CD capability BACKUP." }
        require(StandardIntentCatalog.definitions.any { it.capability.name == "SECRET_ROTATE" }) { "Catalog must include security capability SECRET_ROTATE." }
    }

    private fun checkStandardCapabilityContracts(): ConformanceCheck = runCheck("standard.capability-contracts") {
        val capabilities = org.flowlang.intent.StandardCapability.values().toSet()
        val contracts = org.flowlang.standard.StandardCapabilityContracts.all.keys
        require(contracts.containsAll(capabilities)) { "Standard capability contracts do not cover all StandardCapability enum values." }
        val deploy = org.flowlang.standard.StandardCapabilityContracts.requireContract(org.flowlang.intent.StandardCapability.DEPLOY)
        require(deploy.loweringStrategy.isNotBlank()) { "DEPLOY contract must declare lowering strategy." }
    }

    private fun checkCanonicalIntentMeaning(): ConformanceCheck = runCheck("intent.canonical-meaning.inventory-independent") {
        fun intent(systemType: String, uses: String, bindingParam: String) = IntentDocument(
            name = "equivalent-deploy",
            systems = listOf(IntentSystem("delivery", systemType)),
            workflows = listOf(IntentWorkflow(
                name = "delivery",
                kind = IntentWorkflowKind.DEPLOY,
                steps = listOf(IntentStep(
                    id = "deploy",
                    capability = StandardCapability.DEPLOY,
                    uses = uses,
                    params = mapOf(
                        "system" to IntentString("delivery"),
                        bindingParam to IntentString("billing")
                    )
                ))
            ))
        )

        val argo = CanonicalIntentMeaningAuthority(registry).resolve(intent("argocd", "argocd.sync", "app"))
        val kubernetes = CanonicalIntentMeaningAuthority(registry).resolve(intent("kubernetes", "kubernetes.deploy", "name"))
        require(argo.meaning == kubernetes.meaning) { "Equivalent intent changed meaning across explicit implementation inventories." }
        require(argo.bindings.single().status == IntentBindingStatus.RESOLVED)
        require(kubernetes.bindings.single().status == IntentBindingStatus.RESOLVED)
    }

    private fun checkUniversalEffectModel(): ConformanceCheck = runCheck("intent.effects.universal-state-transition-model") {
        val representative = listOf(
            StandardCapability.BUILD_IMAGE,
            StandardCapability.DATA_TRANSFORM,
            StandardCapability.PROVISION
        ).flatMap(CanonicalIntentEffectAuthority::effectsFor)

        require(representative.map { it.domain }.toSet().containsAll(setOf(
            EffectDomain.SOFTWARE_DELIVERY,
            EffectDomain.DATA_TRANSFORMATION,
            EffectDomain.INFRASTRUCTURE_STATE
        ))) { "Software delivery, data transformation and infrastructure state change must share one semantic effect model." }

        val create = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.BUILD_IMAGE).single()
        require(create.operation == EffectOperation.CREATE)
        require(create.transition?.from == ResourceState.ABSENT && create.transition.to == ResourceState.PRESENT)

        val reconcile = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.DEPLOY).single()
        require(reconcile.operation == EffectOperation.UPSERT)
        require(reconcile.transition?.from == ResourceState.UNKNOWN && reconcile.transition.to == ResourceState.PRESENT)

        val read = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.CHECKOUT).single()
        require(read.operation == EffectOperation.READ && read.transition == null)
    }

    private fun checkIntentDesignReport(): ConformanceCheck = runCheck("intent.design-report") {
        val intent = IntentYamlLoader.load(File(rootDir, "examples/intent/build-test-deploy.intent.yaml"))
        val report = IntentDesignAnalyzer(registry).analyze(intent)
        require(report.capabilities.isNotEmpty()) { "Design report must list capabilities." }
        require(report.requiredSystems.isNotEmpty()) { "Design report must list required systems." }
        require(report.standardVersion == FlowStandardVersions.FLOW_STANDARD_VERSION) { "Design report must carry standard version." }
    }

    private fun checkCorePackagesDoNotImportJackson(): ConformanceCheck = runCheck("architecture.core-no-jackson-imports") {
        val src = File(rootDir, "src/main/kotlin/org/flowlang")
        val coreDirs = listOf("ast", "planner", "capabilities", "standard", "core")
        val offenders = coreDirs.flatMap { dir ->
            File(src, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { file ->
                val text = file.readText()
                text.contains("com.fasterxml.jackson") || text.contains("YAMLFactory") || text.contains("ObjectMapper")
            }.map { it.relativeTo(rootDir).path }.toList()
        }
        require(offenders.isEmpty()) { "Core packages must not import Jackson/YAML: ${offenders.joinToString()}" }
    }

    private fun checkModulesDoNotOwnTargetRendering(): ConformanceCheck = runCheck("architecture.modules-do-not-own-target-rendering") {
        val moduleDir = File(rootDir, "modules")
        val offenders = moduleDir.walkTopDown()
            .filter { it.isFile && (it.extension == "yaml" || it.extension == "yml") }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val forbidden = Regex("^\\s{4}(runtime|generators):\\s*$").containsMatchIn(line) ||
                        Regex("^\\s{8}template:\\s*").containsMatchIn(line) ||
                        Regex("^\\s{8}entrypoint:\\s*").containsMatchIn(line)
                    if (forbidden) "${file.relativeTo(rootDir).path}:${index + 1}:${line.trim()}" else null
                }
            }.toList()
        require(offenders.isEmpty()) {
            "Module descriptors must describe capabilities/effects/safety, not runtime hooks or renderer templates: ${offenders.joinToString()}"
        }
    }

    private fun checkAiNormalizationDeployment(): ConformanceCheck = runCheck("ai.normalization.deployment") {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure."))
        require(response.normalizedIntent.workflows.flatMap { it.steps }.any { it.capability.name == "DEPLOY" }) { "Normalizer must synthesize DEPLOY capability." }
        require(response.normalizedIntent.policies.any { it.type.name == "APPROVAL" }) { "Normalizer must infer approval policy." }
        require(response.normalizedIntent.failure.rollback) { "Normalizer must infer rollback failure policy." }
        val validation = IntentCapabilityValidator(registry).validate(response.normalizedIntent)
        require(validation.valid) { validation.issues.joinToString { it.code + ": " + it.message } }
    }

    private fun checkAiNormalizationFullPipelineValidation(): ConformanceCheck = runCheck("ai.normalization.full-pipeline-validation") {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure."))
        IntentCapabilityValidator(registry).validate(response.normalizedIntent).assertValid()
        val ast = IntentToAstPlanner(registry).plan(response.normalizedIntent)
        val validation = FlowValidator(registry).validate(ast)
        require(validation.valid) { "Normalized deployment produced invalid Flow AST: " + validation.issues.joinToString { it.code + ": " + it.message } }
        val plan = FlowPlanner(registry).plan(ast)
        require(plan.nodes.isNotEmpty()) { "Normalized deployment produced an empty execution plan." }
    }

    private fun checkAiNormalizationMissingApplicationQuestion(): ConformanceCheck = runCheck("ai.normalization.required-question") {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Deploy to Kubernetes with health verification."))
        require(response.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.application.name" }) {
            "Normalizer must ask a required clarification when deployment application is missing."
        }
    }
}
