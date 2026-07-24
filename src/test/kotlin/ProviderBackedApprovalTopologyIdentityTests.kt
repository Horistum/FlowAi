import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.controls.CanonicalControlRequirementAuthority
import org.flowlang.generators.manifest.TargetApprovalProjectionField
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetManifestGenerator
import org.flowlang.generators.manifest.TargetManifestRenderer
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetNativeApprovalProjectionBindingContract
import org.flowlang.generators.manifest.TargetNativeApprovalProjectionDefinition
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentPolicy
import org.flowlang.intent.IntentPolicyType
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.targets.builtin.BuiltInNativeProjectionCatalogs
import org.flowlang.targets.builtin.GitHubActionsManifestGenerator
import org.flowlang.targets.builtin.GitHubActionsManifestRenderer
import org.flowlang.targets.builtin.JenkinsManifestGenerator
import org.flowlang.targets.builtin.JenkinsManifestRenderer
import org.flowlang.topology.CanonicalTopologyRequirementAuthority
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologyProfile
import org.flowlang.topology.PlanningTopologyAuthority

class ProviderBackedApprovalTopologyIdentityTests {
    @Test
    fun jenkinsApprovalIsNativeOnlyWithOwnedProviderPayload() {
        val manifest = pipeline(
            target = "jenkins",
            generator = JenkinsManifestGenerator(),
            renderer = JenkinsManifestRenderer()
        ).generate(approvalPlan(), "jenkins")
        val step = manifest.jobs.single().steps.single()
        val payload = assertNotNull(step.rendererPayload)

        assertEquals(TargetMaterializationStatus.NATIVE, step.materialization.status)
        assertEquals("provider-native-approval-contract", step.materialization.metadata["materializationSource"])
        assertEquals("JENKINS_STEP", payload.kind)
        assertEquals("input", payload.reference)
        assertEquals("manual", payload.bindings.getValue("mode").value)
        assertEquals("Approve production release", payload.bindings.getValue("message").value)
        assertFalse(payload.evidenceReference.isBlank())

        val rendered = JenkinsManifestRenderer().render(manifest)
        assertTrue(rendered.contains("input message: 'Approve production release'"))
    }

    @Test
    fun missingProviderContractCannotClaimNativeApproval() {
        val generator = JenkinsManifestGenerator(TargetNativeProjectionCatalog.empty("jenkins"))
        val manifest = pipeline(
            target = "jenkins",
            generator = generator,
            renderer = JenkinsManifestRenderer()
        ).generate(approvalPlan(), "jenkins")
        val step = manifest.jobs.single().steps.single()
        val readiness = TargetRenderPolicy.evaluate(manifest)

        assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, step.materialization.status)
        assertEquals(null, step.rendererPayload)
        assertEquals(TargetRenderMode.REVIEW_ONLY, readiness.mode)
        assertTrue(readiness.findings.any {
            it.nodeId == step.id && it.status == TargetMaterializationStatus.ADAPTER_REQUIRED.name
        })
    }

    @Test
    fun githubEnvironmentCapabilityDoesNotForgeStepPayloadEvidence() {
        val manifest = pipeline(
            target = "github-actions",
            generator = GitHubActionsManifestGenerator(),
            renderer = GitHubActionsManifestRenderer()
        ).generate(approvalPlan(), "github-actions")
        val step = manifest.jobs.single().steps.single()

        assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, step.materialization.status)
        assertEquals(null, step.rendererPayload)
        assertEquals(TargetRenderMode.REVIEW_ONLY, TargetRenderPolicy.evaluate(manifest).mode)
        assertTrue(BuiltInNativeProjectionCatalogs.githubActions.approvalDefinitions.isEmpty())
    }

    @Test
    fun forgedNativeApprovalWithoutPayloadIsNotExecutable() {
        val manifest = TargetManifest(
            target = "jenkins",
            flowName = "forged-approval",
            compatibility = supportedCompatibility("jenkins"),
            jobs = listOf(TargetJob(
                id = "main",
                steps = listOf(TargetStep(
                    id = "approve",
                    type = "approval",
                    materialization = TargetMaterialization.native(
                        "approval.manual",
                        "Unverified native claim."
                    )
                ))
            ))
        )

        val readiness = TargetRenderPolicy.evaluate(manifest)
        assertEquals(TargetRenderMode.REVIEW_ONLY, readiness.mode)
        assertTrue(readiness.findings.any { it.status == "TARGET_PAYLOAD_MISSING" })
    }

    @Test
    fun providerCatalogRejectsDuplicateApprovalCapabilityAndFieldMappings() {
        val definition = approvalDefinition("input")
        val duplicate = assertFailsWith<IllegalArgumentException> {
            TargetNativeProjectionCatalog.of(
                target = "future",
                definitions = emptyList(),
                approvalDefinitions = listOf(definition, definition.copy(reference = "other-input"))
            )
        }
        assertTrue(duplicate.message.orEmpty().contains("Duplicate native approval projection capability"))

        val repeatedField = assertFailsWith<IllegalArgumentException> {
            TargetNativeApprovalProjectionDefinition(
                capability = "approval.manual",
                kind = "FUTURE_CONTROL",
                reference = "input",
                evidenceReference = "test:future#approval",
                bindings = mapOf(
                    "first" to TargetNativeApprovalProjectionBindingContract(TargetApprovalProjectionField.MESSAGE),
                    "second" to TargetNativeApprovalProjectionBindingContract(TargetApprovalProjectionField.MESSAGE)
                )
            )
        }
        assertTrue(repeatedField.message.orEmpty().contains("same semantic field"))
    }

    @Test
    fun controlSubjectsThatShareASlugKeepDistinctDeterministicIdentities() {
        val first = controlCollisionIntent(listOf("prod approval", "prod-approval"))
        val second = controlCollisionIntent(listOf("prod-approval", "prod approval"))

        val firstIds = CanonicalControlRequirementAuthority.requirementsFor(first)
            .associate { it.subject to it.id }
        val secondIds = CanonicalControlRequirementAuthority.requirementsFor(second)
            .associate { it.subject to it.id }

        assertEquals(firstIds, secondIds)
        assertEquals(2, firstIds.values.toSet().size)
        assertTrue(firstIds.values.all { it.startsWith("control.approval.prod-approval--") })
    }

    @Test
    fun topologySubjectsThatShareASlugAreNotDiscarded() {
        val first = topologyCollisionIntent(listOf("release api", "release-api"))
        val second = topologyCollisionIntent(listOf("release-api", "release api"))

        fun identities(intent: IntentDocument) = CanonicalTopologyRequirementAuthority.requirementsFor(intent)
            .filter { it.kind == ExecutionTopologyKind.WORKFLOW_SCOPE }
            .associate { it.subject to it.id }

        val firstIds = identities(first)
        val secondIds = identities(second)
        assertEquals(firstIds, secondIds)
        assertEquals(2, firstIds.values.toSet().size)
        assertTrue(firstIds.values.all { it.startsWith("topology.workflowScope.release-api--") })
    }

    @Test
    fun planningTopologyCoalescesSameMeaningButPreservesSlugCollisions() {
        val canonical = CanonicalTopologyRequirementAuthority.requirementsFor(
            topologyCollisionIntent(listOf("release api", "release-api"))
        )
        val planned = PlanningTopologyAuthority.requirementsFor(
            flowName = "release api",
            canonicalRequirements = canonical,
            nodes = listOf(
                ApprovalNode(id = "gate one"),
                ApprovalNode(id = "gate-one")
            ),
            dependencyRelations = emptyList()
        )

        val workflowScopes = planned.filter { it.kind == ExecutionTopologyKind.WORKFLOW_SCOPE }
        val suspendResume = planned.filter { it.kind == ExecutionTopologyKind.SUSPEND_RESUME }
        assertEquals(2, workflowScopes.size)
        assertEquals(2, suspendResume.size)
        assertEquals(2, suspendResume.map { it.id }.toSet().size)
        assertTrue(suspendResume.all { it.id.startsWith("topology.suspendResume.gate-one--") })
    }

    @Test
    fun ordinaryReadableIdsRemainStableWithoutCollision() {
        val intent = topologyCollisionIntent(listOf("release"))
        val requirement = CanonicalTopologyRequirementAuthority.requirementsFor(intent)
            .single { it.kind == ExecutionTopologyKind.WORKFLOW_SCOPE }
        assertEquals("topology.workflowScope.release", requirement.id)
    }

    private fun pipeline(
        target: String,
        generator: TargetManifestGenerator,
        renderer: TargetManifestRenderer
    ): TargetManifestGenerationPipeline {
        val capability = TargetCapability(
            target = target,
            description = "Provider-backed approval test target.",
            approvals = SupportLevel.SUPPORTED,
            features = mapOf(
                "approval.manual" to SupportLevel.SUPPORTED,
                "approval.inline" to SupportLevel.SUPPORTED
            ),
            topologyProfile = ExecutionTopologyProfile.fullySupported(
                target,
                "test:$target#topology"
            )
        )
        return TargetManifestGenerationPipeline(
            targets = mapOf(target to capability),
            projections = TargetProjectionRegistry.of(TargetProjectionProvider(generator, renderer))
        )
    }

    private fun approvalPlan(): ExecutionPlan = ExecutionPlan(
        flowName = "approval",
        nodes = listOf(ApprovalNode(
            id = "approve-production",
            message = "Approve production release"
        ))
    )

    private fun supportedCompatibility(target: String): CompatibilityReport = CompatibilityReport(
        target = target,
        status = SupportLevel.SUPPORTED,
        capabilityStatus = SupportLevel.SUPPORTED
    )

    private fun approvalDefinition(reference: String): TargetNativeApprovalProjectionDefinition =
        TargetNativeApprovalProjectionDefinition(
            capability = "approval.manual",
            kind = "FUTURE_CONTROL",
            reference = reference,
            evidenceReference = "test:future#approval",
            bindings = mapOf(
                "mode" to TargetNativeApprovalProjectionBindingContract(TargetApprovalProjectionField.MODE),
                "message" to TargetNativeApprovalProjectionBindingContract(TargetApprovalProjectionField.MESSAGE)
            )
        )

    private fun controlCollisionIntent(names: List<String>): IntentDocument = IntentDocument(
        name = "control-collision",
        workflows = listOf(IntentWorkflow(
            name = "main",
            kind = IntentWorkflowKind.DEPLOY,
            steps = listOf(IntentStep("approve", StandardCapability.APPROVE))
        )),
        policies = names.map { name ->
            IntentPolicy(
                name = name,
                type = IntentPolicyType.APPROVAL,
                message = "Approval obligation for $name"
            )
        }
    )

    private fun topologyCollisionIntent(names: List<String>): IntentDocument = IntentDocument(
        name = "topology-collision",
        workflows = names.map { name ->
            IntentWorkflow(
                name = name,
                kind = IntentWorkflowKind.BUILD,
                steps = listOf(IntentStep("build-$name", StandardCapability.BUILD))
            )
        }
    )
}
