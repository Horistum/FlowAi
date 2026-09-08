package org.flowlang.conformance

import org.flowlang.frontend.FrontendCompilerComposition

import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.cli.Json
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.effects.CanonicalIntentEffectAuthority
import org.flowlang.controls.CanonicalControlRequirementAuthority
import org.flowlang.controls.ControlDecisionStatus
import org.flowlang.controls.ControlEvidenceStatus
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
import org.flowlang.lowering.IntentLoweringAuthority
import org.flowlang.modules.CanonicalModuleLoader
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.parser.FlowParser
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.StandardIntentCatalog
import org.flowlang.topology.ExecutionTopologyDecisionStatus
import org.flowlang.topology.ExecutionTopologyEvidenceStatus
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologyMatchingAuthority
import org.flowlang.topology.ExecutionTopologyProfile
import org.flowlang.topology.ExecutionTopologySupportDeclaration
import org.flowlang.topology.ExecutionTopologySupportStatus
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
        checkUniversalControlPolicyRequirements(),
        checkAbstractExecutionTopologyModel(),
        checkIntentLoweringDiagnosticHonesty(),
        checkEnvironmentSafetyProductionIntegration(),
        checkScenarioNegationTokenBoundaryHonesty(),
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
        val createdState = create.transition
        require(createdState?.from == ResourceState.ABSENT && createdState.to == ResourceState.PRESENT)

        val reconcile = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.DEPLOY).single()
        require(reconcile.operation == EffectOperation.UPSERT)
        val reconciledState = reconcile.transition
        require(reconciledState?.from == ResourceState.UNKNOWN && reconciledState.to == ResourceState.PRESENT)

        val read = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.CHECKOUT).single()
        require(read.operation == EffectOperation.READ && read.transition == null)
    }

    private fun checkUniversalControlPolicyRequirements(): ConformanceCheck = runCheck("intent.controls.universal-policy-requirements") {
        val known = IntentDocument(
            name = "known-control",
            workflows = listOf(IntentWorkflow(
                name = "migration",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(
                    IntentStep(id = "backup", capability = StandardCapability.BACKUP),
                    IntentStep(
                        id = "migrate",
                        capability = StandardCapability.DATABASE_MIGRATE,
                        requires = listOf("backup")
                    )
                )
            ))
        )
        val knownAssessment = CanonicalControlRequirementAuthority.assess(known)
        require(knownAssessment.decision.status == ControlDecisionStatus.ALLOWED)
        require(knownAssessment.evidence.all { it.status == ControlEvidenceStatus.SATISFIED })

        val unrelated = known.copy(
            name = "unrelated-control",
            workflows = listOf(IntentWorkflow(
                name = "migration",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(
                    IntentStep(id = "backup", capability = StandardCapability.BACKUP),
                    IntentStep(id = "migrate", capability = StandardCapability.DATABASE_MIGRATE)
                )
            ))
        )
        val unrelatedAssessment = CanonicalControlRequirementAuthority.assess(unrelated)
        require(unrelatedAssessment.decision.status == ControlDecisionStatus.BLOCKED) {
            "A same-workflow backup without an authored protection edge satisfied the migration control requirement."
        }
        require(unrelatedAssessment.evidence.single().status == ControlEvidenceStatus.UNKNOWN)

        val unknown = known.copy(
            name = "unknown-control",
            workflows = listOf(IntentWorkflow(
                name = "migration",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(IntentStep(id = "migrate", capability = StandardCapability.DATABASE_MIGRATE))
            ))
        )
        val unknownAssessment = CanonicalControlRequirementAuthority.assess(unknown)
        require(unknownAssessment.decision.status == ControlDecisionStatus.BLOCKED)
        require(unknownAssessment.evidence.any { it.status == ControlEvidenceStatus.UNKNOWN })

        val dynamic = IntentDocument(
            name = "dynamic-control",
            workflows = listOf(IntentWorkflow(
                name = "delivery",
                kind = IntentWorkflowKind.DEPLOY,
                steps = listOf(IntentStep(id = "approve", capability = StandardCapability.APPROVE))
            )),
            policies = listOf(org.flowlang.intent.IntentPolicy(
                name = "production-approval",
                type = org.flowlang.intent.IntentPolicyType.APPROVAL,
                condition = "environment == 'prod'"
            ))
        )
        val dynamicAssessment = CanonicalControlRequirementAuthority.assess(dynamic)
        require(dynamicAssessment.decision.status == ControlDecisionStatus.PENDING)
        require(dynamicAssessment.evidence.single().status == ControlEvidenceStatus.DYNAMIC)
        require(dynamicAssessment.evidence.single().enforcementCapabilities.containsAll(listOf("approval.manual", "condition.evaluate")))
    }

    private fun checkAbstractExecutionTopologyModel(): ConformanceCheck = runCheck("planning.topology.abstract-execution-model") {
        val intent = IntentYamlLoader.load(File(rootDir, "examples/intent/checkout-build-image.intent.yaml"))
        val canonical = CanonicalIntentMeaningAuthority(registry).resolve(intent).meaning.topologyRequirements
        val inventoryIndependent = CanonicalIntentMeaningAuthority(ModuleRegistry()).resolve(intent).meaning.topologyRequirements
        require(canonical == inventoryIndependent) {
            "Equivalent intent changed topology requirements across implementation inventories."
        }

        val plan = FlowPlanner(registry).plan(FrontendCompilerComposition.intentPlanner(registry).plan(intent))
        require(plan.topologyRequirements.any { it.kind == ExecutionTopologyKind.EPHEMERAL_WORKSPACE })
        require(plan.topologyRequirements.any { it.kind == ExecutionTopologyKind.WORKSPACE_PROPAGATION })

        val jenkins = ExecutionTopologyMatchingAuthority.assess(
            plan.topologyRequirements,
            targets.getValue("jenkins").topologyProfile
        )
        require(jenkins.decision.status == ExecutionTopologyDecisionStatus.MATCHED) {
            "Jenkins reference topology must match the checkout-and-build plan."
        }

        val github = ExecutionTopologyMatchingAuthority.assess(
            plan.topologyRequirements,
            targets.getValue("github-actions").topologyProfile
        )
        require(github.decision.status == ExecutionTopologyDecisionStatus.BLOCKED)
        require(github.evidence.any {
            it.kind == ExecutionTopologyKind.WORKSPACE_PROPAGATION &&
                it.status == ExecutionTopologyEvidenceStatus.UNSATISFIED
        }) { "GitHub Actions must remain blocked without workspace propagation evidence." }

        val missing = ExecutionTopologyMatchingAuthority.assess(plan.topologyRequirements, null)
        require(missing.decision.status == ExecutionTopologyDecisionStatus.BLOCKED)
        require(missing.evidence.all { it.status == ExecutionTopologyEvidenceStatus.UNKNOWN }) {
            "Action capability without a topology profile must remain unknown and blocked."
        }

        val contradictory = ExecutionTopologyProfile(
            target = "contradictory",
            declarations = ExecutionTopologyKind.entries.flatMap { kind ->
                val supported = ExecutionTopologySupportDeclaration(
                    kind = kind,
                    status = ExecutionTopologySupportStatus.SUPPORTED,
                    evidenceReference = "conformance:${kind.registryKey}:supported"
                )
                if (kind == ExecutionTopologyKind.WORKFLOW_SCOPE) {
                    listOf(
                        supported,
                        ExecutionTopologySupportDeclaration(
                            kind = kind,
                            status = ExecutionTopologySupportStatus.UNSUPPORTED,
                            evidenceReference = "conformance:${kind.registryKey}:unsupported"
                        )
                    )
                } else {
                    listOf(supported)
                }
            }
        )
        val contradiction = ExecutionTopologyMatchingAuthority.assess(plan.topologyRequirements, contradictory)
        require(contradiction.decision.status == ExecutionTopologyDecisionStatus.BLOCKED)
        require(contradiction.evidence.any { it.status == ExecutionTopologyEvidenceStatus.CONTRADICTORY })
    }

    private fun checkIntentLoweringDiagnosticHonesty(): ConformanceCheck = runCheck("intent.lowering.diagnostic-honesty") {
        val block = IntentYamlLoader.loadText(
            """
            name: lowering-honesty
            description: Preserve accepted data
            inputs:
              - name: config
                type: object
                default: { enabled: true, names: [one, two] }
            systems:
              - name: standard
                type: standard
                purpose: semantic operations
            workflows:
              - name: main
                kind: CUSTOM
                steps:
                  - id: transform
                    capability: CUSTOM
                    produces: [artifact]
                    params:
                      structured: { enabled: true, names: [one, two] }
                      reference: ref:config.names
            """.trimIndent(),
            "block.yaml"
        )
        val flowStyle = IntentYamlLoader.loadText(
            """
            name: lowering-honesty
            description: Preserve accepted data
            inputs: [{ name: config, type: object, default: { enabled: true, names: [one, two] } }]
            systems: [{ name: standard, type: standard, purpose: semantic operations }]
            workflows: [{ name: main, kind: CUSTOM, steps: [{ id: transform, capability: CUSTOM, produces: [artifact], params: { structured: { enabled: true, names: [one, two] }, reference: "ref:config.names" } }] }]
            """.trimIndent(),
            "flow.yaml"
        )
        require(block == flowStyle) { "Equivalent block and flow-style source forms normalized differently." }

        val ast = FrontendCompilerComposition.intentPlanner(registry).plan(block)
        val plan = FlowPlanner(registry).plan(ast)
        val sourceMetadata = requireNotNull(ast.metadata.sourceIntent) { "AST lacks stable lowering source metadata." }
        require(sourceMetadata.fields.isNotEmpty()) { "AST lacks stable lowering source field identities." }
        require(ast.metadata.loweringReport == null) { "AST must not certify execution-plan values before the plan exists." }
        val loweringReport = requireNotNull(plan.loweringReport) { "Execution plan lacks artifact-derived lowering evidence." }
        require(loweringReport.evidence.map { it.sourceIdentity }.toSet() == sourceMetadata.fields.map { it.identity }.toSet()) {
            "Execution-plan lowering evidence does not cover the stable source catalog."
        }
        require(loweringReport == IntentLoweringAuthority.report(plan.copy(loweringReport = null))) {
            "Execution-plan lowering evidence is not reproducible from concrete plan values."
        }
        require(plan.tasks.single().outputs.contains("artifact")) { "Declared intent output disappeared during lowering." }
        require(plan.inputs.single().defaultExpression?.contains("enabled") == true) { "Structured input default disappeared during planning." }

        val malformed = runCatching {
            IntentYamlLoader.loadText("name: malformed\nworkflows: [not-an-object]", "malformed.yaml")
        }.exceptionOrNull()
        require(malformed is org.flowlang.intent.IntentSourceException) { "Malformed source did not produce a stable source diagnostic." }
        require(malformed.code == "INTENT_LIST_ITEM_TYPE_MISMATCH") { "Malformed source produced code '${malformed.code}'." }

        val unsupported = IntentDocument(
            name = "unsupported-param",
            workflows = listOf(IntentWorkflow(
                name = "main",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(IntentStep(
                    id = "test",
                    capability = StandardCapability.TEST,
                    params = mapOf("mystery" to IntentString("must-not-disappear"))
                ))
            ))
        )
        val report = IntentCapabilityValidator(registry).validate(unsupported)
        require(!report.valid && report.issues.any { it.code == "UNKNOWN_STEP_PARAM" }) {
            "Unknown semantic input was accepted even though canonical lowering would discard it."
        }
    }

    private fun checkEnvironmentSafetyProductionIntegration(): ConformanceCheck =
        runCheck("flow.environment-safety.production-integration") {
            fun flow(namespace: String, approval: Boolean = false, input: Boolean = false): org.flowlang.ast.FlowDocument {
                val inputBlock = if (input) "input { environment: text required }" else ""
                val approvalLine = if (approval) "safety: requiresApproval" else ""
                return FlowParser().parse(
                    """
                    version "1.0"
                    use module "kubernetes" version "1.0"
                    flow "environment safety" {
                      $inputBlock
                      systems { system "k8s" { type: kubernetes } }
                      steps {
                        kubernetes.deploy k8s {
                          app: "demo"
                          namespace: $namespace
                          image: "demo:1"
                          $approvalLine
                        }
                      }
                    }
                    """.trimIndent()
                )
            }

            val dynamic = FrontendCompilerComposition.flowValidator(registry).validate(flow("environment", input = true))
            require(!dynamic.valid && dynamic.issues.any { it.code == "ENVIRONMENT_CLASSIFICATION_UNKNOWN" }) {
                "Runtime environment reference was not blocked by the production Flow validator."
            }
            require(dynamic.issues.single { it.code == "ENVIRONMENT_CLASSIFICATION_UNKNOWN" }
                .message.contains("runtime reference 'environment'")) {
                "Reference environment evidence was reinterpreted as a literal."
            }

            val sensitive = FrontendCompilerComposition.flowValidator(registry).validate(flow("\"prod\""))
            require(!sensitive.valid && sensitive.issues.any { it.code == "ENVIRONMENT_APPROVAL_REQUIRED" }) {
                "Sensitive environment mutation was not approval-gated."
            }

            val approved = FrontendCompilerComposition.flowValidator(registry).validate(flow("\"prod\"", approval = true))
            require(approved.issues.none { it.code.startsWith("ENVIRONMENT_") }) {
                "Unconditional approval did not satisfy sensitive environment policy: ${approved.issues}."
            }

            val engineering = FrontendCompilerComposition.flowValidator(registry).validate(flow("\"dev\""))
            require(engineering.issues.none { it.code.startsWith("ENVIRONMENT_") }) {
                "Known non-sensitive environment was blocked: ${engineering.issues}."
            }
        }

    private fun checkScenarioNegationTokenBoundaryHonesty(): ConformanceCheck =
        runCheck("ai.normalization.scenario-negation-token-boundary-honesty") {
            val normalizer = ScenarioPackIntentNormalizer()

            val deniedBackup = normalizer.normalize(
                AiIntentRequest("Migrate database orders to version 2; backup is unavailable.")
            )
            require(deniedBackup.normalizedIntent.workflows.flatMap { it.steps }
                .none { it.capability == StandardCapability.BACKUP }) {
                "Explicit backup denial synthesized positive BACKUP evidence."
            }

            val deniedNotification = normalizer.normalize(
                AiIntentRequest("Run incident runbook for api outage, but do not notify the team.")
            )
            require(deniedNotification.normalizedIntent.workflows.flatMap { it.steps }
                .none { it.capability == StandardCapability.NOTIFY }) {
                "Explicit notification denial synthesized a NOTIFY step."
            }

            val boundary = normalizer.normalize(AiIntentRequest("Investigate capacity regression."))
            require(boundary.report.scenarioSelection?.selectedPack == "custom") {
                "The substring 'ci' inside 'capacity' selected the build-test scenario."
            }

            val conflicting = normalizer.normalize(
                AiIntentRequest("Run incident runbook for api outage; do not notify on success, but notify on failure.")
            )
            require(conflicting.report.openQuestions.any {
                it.severity == ClarificationSeverity.REQUIRED && it.field == "source.notification"
            }) {
                "Conflicting notification polarity did not require clarification."
            }
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
        val semanticPackages = listOf(
            "ast",
            "capabilities",
            "controls",
            "core",
            "effects",
            "identity",
            "intent",
            "lowering",
            "materialization",
            "modules",
            "notes",
            "planner",
            "projection",
            "obligations",
            "safety",
            "standard",
            "topology",
            "validator"
        )
        val missingPackages = semanticPackages.filterNot { File(src, it).isDirectory }
        require(missingPackages.isEmpty()) {
            "Semantic serialization boundary references missing packages: ${missingPackages.joinToString()}"
        }
        val forbiddenTokens = listOf("com.fasterxml.jackson", "org.yaml.snakeyaml", "YAMLFactory", "ObjectMapper")
        val offenders = semanticPackages.flatMap { dir ->
            File(src, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { file ->
                val text = file.readText()
                forbiddenTokens.any(text::contains)
            }.map { it.relativeTo(rootDir).path }.toList()
        }.sorted()
        require(offenders.isEmpty()) {
            "Semantic Core packages must not import Jackson/YAML infrastructure: ${offenders.joinToString()}"
        }
    }

    private fun checkModulesDoNotOwnTargetRendering(): ConformanceCheck = runCheck("architecture.modules-do-not-own-target-rendering") {
        val moduleDir = File(rootDir, "modules")
        val descriptorFiles = moduleDir.walkTopDown()
            .filter { it.isFile && (it.extension == "yaml" || it.extension == "yml") }
            .toList()
            .sortedBy { it.relativeTo(rootDir).path }
        require(descriptorFiles.isNotEmpty()) { "No canonical module descriptors were found." }

        val loaded = CanonicalModuleLoader.loadDirectory(moduleDir)
        require(loaded.size == descriptorFiles.size) {
            "Canonical module loading did not account for every descriptor: files=${descriptorFiles.size}, modules=${loaded.size}."
        }
        require(loaded.all { module ->
            module.actions.values.all { action ->
                action.requiredCapabilities.none { capability ->
                    capability.contains("jenkins", ignoreCase = true) ||
                        capability.contains("github-actions", ignoreCase = true) ||
                        capability.contains("tekton", ignoreCase = true)
                }
            }
        }) {
            "Canonical module contracts contain target-owned capability vocabulary."
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
        val ast = FrontendCompilerComposition.intentPlanner(registry).plan(response.normalizedIntent)
        val validation = FrontendCompilerComposition.flowValidator(registry).validate(ast)
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
