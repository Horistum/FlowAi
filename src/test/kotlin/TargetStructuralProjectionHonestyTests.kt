import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestContractValidator
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TargetRendererPayload
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.generators.manifest.TargetInput
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.targets.TargetDescriptor
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInNativeProjectionCatalogs
import org.flowlang.targets.builtin.JenkinsManifestRenderer
import org.flowlang.topology.ExecutionTopologyProfile

class TargetStructuralProjectionHonestyTests {
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }

    @Test
    fun targetCapabilityDefaultsFailClosed() {
        val direct = TargetCapability(target = "future", description = "No evidence")
        val omitted = TargetDescriptor(name = "future", description = "No evidence")
            .toCapability(topologyProfile = ExecutionTopologyProfile.fullySupported("future", "test:topology"))

        listOf(direct, omitted).forEach { capability ->
            assertEquals(SupportLevel.UNSUPPORTED, capability.sequentialTasks)
            assertEquals(SupportLevel.UNSUPPORTED, capability.parallel)
            assertEquals(SupportLevel.UNSUPPORTED, capability.conditions)
            assertEquals(SupportLevel.UNSUPPORTED, capability.dynamicLoops)
            assertEquals(SupportLevel.UNSUPPORTED, capability.match)
            assertEquals(SupportLevel.UNSUPPORTED, capability.retry)
            assertEquals(SupportLevel.UNSUPPORTED, capability.approvals)
            assertEquals(SupportLevel.UNSUPPORTED, capability.errorHandlers)
            assertEquals(SupportLevel.UNSUPPORTED, capability.artifacts)
            assertEquals(SupportLevel.UNSUPPORTED, capability.secrets)
            assertEquals(SupportLevel.UNSUPPORTED, capability.nativeRuntime)
        }
    }

    @Test
    fun unknownCapabilityDoesNotInheritNativeRuntime() {
        val target = TargetCapability(
            target = "programmable",
            description = "Programmable is not universal semantic evidence.",
            sequentialTasks = SupportLevel.SUPPORTED,
            nativeRuntime = SupportLevel.SUPPORTED,
            topologyProfile = ExecutionTopologyProfile.fullySupported("programmable", "test:topology")
        )
        val plan = ExecutionPlan(
            flowName = "unknown-capability",
            nodes = listOf(TaskNode(
                id = "future-task",
                module = "future",
                action = "operation",
                target = "system",
                requiredCapabilities = listOf("future.unmodeled-capability")
            ))
        )

        val report = CompatibilityAnalyzer(mapOf(target.target to target)).analyze(plan, target.target)

        assertEquals(SupportLevel.UNSUPPORTED, report.status)
        assertTrue(report.issues.any {
            it.feature == "future.unmodeled-capability" && it.message.contains("not supported")
        })
    }

    @Test
    fun registryPublishesOnlyCurrentStructuralEvidence() {
        val jenkins = targets.getValue("jenkins")
        val github = targets.getValue("github-actions")
        val tekton = targets.getValue("tekton")
        val argo = targets.getValue("argo-workflows")
        val azure = targets.getValue("azure-devops")

        assertEquals(SupportLevel.SUPPORTED, jenkins.conditions)
        assertEquals(SupportLevel.SUPPORTED, jenkins.errorHandlers)
        assertEquals(SupportLevel.UNSUPPORTED, jenkins.parallel)
        assertEquals(SupportLevel.UNSUPPORTED, jenkins.dynamicLoops)
        assertEquals(SupportLevel.UNSUPPORTED, jenkins.match)
        assertEquals(SupportLevel.UNSUPPORTED, jenkins.retry)

        assertEquals(SupportLevel.PARTIAL, github.conditions)
        assertEquals(SupportLevel.PARTIAL, github.parallel)
        assertEquals(SupportLevel.PARTIAL, tekton.conditions)
        assertEquals(SupportLevel.PARTIAL, tekton.parallel)

        listOf(argo, azure).forEach { profileOnly ->
            assertEquals(SupportLevel.UNSUPPORTED, profileOnly.conditions)
            assertEquals(SupportLevel.UNSUPPORTED, profileOnly.parallel)
            assertEquals(SupportLevel.UNSUPPORTED, profileOnly.dynamicLoops)
            assertEquals(SupportLevel.UNSUPPORTED, profileOnly.match)
            assertEquals(SupportLevel.UNSUPPORTED, profileOnly.retry)
            assertEquals(SupportLevel.UNSUPPORTED, profileOnly.errorHandlers)
        }
    }

    @Test
    fun jenkinsCatalogOwnsOnlyImplementedStructuralBehavior() {
        val structures = BuiltInNativeProjectionCatalogs.jenkins.structuralDefinitions
            .map { it.structure }
            .toSet()

        assertEquals(
            setOf(
                TargetStructuralProjectionKind.CONDITION,
                TargetStructuralProjectionKind.ERROR_BOUNDARY,
                TargetStructuralProjectionKind.RETRY
            ),
            structures
        )
        assertFalse(BuiltInNativeProjectionCatalogs.jenkins.hasStructuralProjection(TargetStructuralProjectionKind.PARALLEL))
        assertFalse(BuiltInNativeProjectionCatalogs.jenkins.hasStructuralProjection(TargetStructuralProjectionKind.LOOP))
        assertFalse(BuiltInNativeProjectionCatalogs.jenkins.hasStructuralProjection(TargetStructuralProjectionKind.MATCH))
        assertTrue(BuiltInNativeProjectionCatalogs.jenkins.hasStructuralProjection(TargetStructuralProjectionKind.RETRY))
    }

    @Test
    fun nativeChildrenCannotMakeUnprovenJenkinsStructuresExecutable() {
        val child = jenkinsCheckoutStep("checkout")
        val unsupported = listOf(
            TargetStructuralProjectionKind.PARALLEL,
            TargetStructuralProjectionKind.LOOP,
            TargetStructuralProjectionKind.MATCH
        )

        unsupported.forEach { structure ->
            val resolution = BuiltInNativeProjectionCatalogs.jenkins.resolveStructure(structure, structure.stepType)
            val step = TargetStep(
                id = "${structure.stepType}-structure",
                type = structure.stepType,
                materialization = resolution.materialization,
                rendererPayload = resolution.rendererPayload,
                children = listOf(child)
            )
            val readiness = TargetRenderPolicy.evaluate(
                manifest(step, compatibility = supportedJenkinsCompatibility(), executableMetadata = false)
            )

            assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, resolution.materialization.status)
            assertEquals(TargetRenderMode.REVIEW_ONLY, readiness.mode)
            assertTrue(readiness.findings.any {
                it.nodeId == step.id && it.status == TargetMaterializationStatus.ADAPTER_REQUIRED.name
            })
        }
    }

    @Test
    fun forgedNativeStructureWithoutBehaviorEvidenceIsRejected() {
        val implementation = "test:forged-implementation"
        val forged = TargetStep(
            id = "forged-condition",
            type = TargetStructuralProjectionKind.CONDITION.stepType,
            params = mapOf("condition" to "enabled == true"),
            children = listOf(jenkinsCheckoutStep("forged-checkout")),
            materialization = TargetMaterialization.native(
                capability = TargetStructuralProjectionKind.CONDITION.capability,
                reason = "Forged structural claim.",
                metadata = mapOf(
                    TargetNativeProjectionCatalog.STRUCTURAL_KIND_METADATA to
                        TargetStructuralProjectionKind.CONDITION.name,
                    TargetNativeProjectionCatalog.STRUCTURAL_IMPLEMENTATION_EVIDENCE_METADATA to implementation
                )
            ),
            rendererPayload = TargetRendererPayload(
                kind = "JENKINS_STRUCTURE",
                target = "jenkins",
                reference = "if",
                evidenceReference = implementation
            )
        )
        val candidate = manifest(forged, supportedJenkinsCompatibility(), executableMetadata = true)

        val contract = TargetManifestContractValidator.validate(candidate)
        val readiness = TargetRenderPolicy.evaluate(candidate)

        assertContains(contract.issues.map { it.code }, "STRUCTURAL_BEHAVIOR_EVIDENCE_MISSING")
        assertEquals(TargetRenderMode.REVIEW_ONLY, readiness.mode)
        assertTrue(readiness.findings.any { it.status == "TARGET_PAYLOAD_EVIDENCE_MISSING" })
    }

    @Test
    fun jenkinsConditionPreservesGuardedExecution() {
        val structural = BuiltInNativeProjectionCatalogs.jenkins.resolveStructure(
            TargetStructuralProjectionKind.CONDITION,
            "guarded-checkout"
        )
        val condition = TargetStep(
            id = "guarded-checkout",
            name = "guarded checkout",
            type = TargetStructuralProjectionKind.CONDITION.stepType,
            params = mapOf("condition" to "enabled == true"),
            children = listOf(jenkinsCheckoutStep("condition-checkout")),
            materialization = structural.materialization,
            rendererPayload = structural.rendererPayload
        )
        val candidate = manifest(
            condition,
            supportedJenkinsCompatibility(),
            executableMetadata = true,
            inputs = listOf(TargetInput("enabled", type = "boolean", required = true))
        )

        assertEquals(TargetRenderMode.EXECUTABLE, TargetRenderPolicy.evaluate(candidate).mode)
        val rendered = JenkinsManifestRenderer().render(candidate)

        assertContains(rendered, "if (")
        assertContains(rendered, "params.enabled")
        assertContains(rendered, "git branch:")
    }

    @Test
    fun jenkinsErrorBoundaryPreservesHandlerExecution() {
        val structural = BuiltInNativeProjectionCatalogs.jenkins.resolveStructure(
            TargetStructuralProjectionKind.ERROR_BOUNDARY,
            "flow-error-boundary"
        )
        val boundary = TargetStep(
            id = "flow-error-boundary",
            type = TargetStructuralProjectionKind.ERROR_BOUNDARY.stepType,
            materialization = structural.materialization,
            rendererPayload = structural.rendererPayload,
            children = listOf(
                TargetStep(
                    id = "flow-error-body",
                    type = "try-body",
                    children = listOf(jenkinsCheckoutStep("body-checkout"))
                ),
                TargetStep(
                    id = "flow-error-handler",
                    type = "error-handler",
                    children = listOf(jenkinsCheckoutStep("handler-checkout"))
                )
            )
        )
        val candidate = manifest(boundary, supportedJenkinsCompatibility(), executableMetadata = true)

        assertEquals(TargetRenderMode.EXECUTABLE, TargetRenderPolicy.evaluate(candidate).mode)
        val rendered = JenkinsManifestRenderer().render(candidate)

        assertContains(rendered, "try {")
        assertContains(rendered, "catch (flowError)")
        assertEquals(2, Regex("git branch:").findAll(rendered).count())
    }

    private fun supportedJenkinsCompatibility(): CompatibilityReport {
        val target = targets.getValue("jenkins")
        return CompatibilityReport(
            target = target.target,
            status = SupportLevel.SUPPORTED,
            capabilityStatus = SupportLevel.SUPPORTED,
            expressionSupport = target.expressionSupport,
            projectionRules = target.projectionRules
        )
    }

    private fun jenkinsCheckoutStep(id: String): TargetStep {
        val target = targets.getValue("jenkins")
        val rule = target.projectionRules.single { it.module == "git" && it.action == "checkout" }
        val task = TaskNode(
            id = id,
            module = "git",
            action = "checkout",
            target = "source",
            params = mapOf(
                "url" to "https://example.invalid/source.git",
                "branch" to "main"
            )
        )
        val payload = BuiltInNativeProjectionCatalogs.jenkins.compile(rule, task)
        return TargetStep(
            id = id,
            name = id,
            type = "action",
            module = "git",
            action = "checkout",
            target = "source",
            materialization = TargetMaterialization.native(
                capability = "git.checkout",
                reason = "Native checkout fixture."
            ),
            rendererPayload = payload,
            params = task.params
        )
    }

    private fun manifest(
        step: TargetStep,
        compatibility: CompatibilityReport,
        executableMetadata: Boolean,
        inputs: List<TargetInput> = emptyList()
    ): TargetManifest = TargetManifest(
        target = compatibility.target,
        flowName = "structural-honesty",
        compatibility = compatibility,
        inputs = inputs,
        jobs = listOf(TargetJob(id = "structural-honesty", steps = listOf(step))),
        metadata = mapOf(
            "sourcePlanVersion" to FlowStandardVersions.EXECUTION_PLAN_VERSION,
            "generator" to "TargetStructuralProjectionHonestyTests",
            "standardVersion" to FlowStandardVersions.FLOW_STANDARD_VERSION,
            "capabilityCompatibility" to compatibility.capabilityStatus.name,
            "effectiveCompatibility" to compatibility.status.name,
            "materializationReadiness" to if (executableMetadata) "COMPLETE" else "REVIEW_REQUIRED",
            "projectionReadiness" to if (executableMetadata) "EXECUTABLE" else "REVIEW_ONLY",
            "executable" to executableMetadata.toString()
        )
    )
}
