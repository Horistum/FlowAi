import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetProjectionMode
import org.flowlang.capabilities.TargetProjectionRule
import org.flowlang.capabilities.TargetRendererPayloadTemplate
import org.flowlang.generators.manifest.TargetNativeProjectionBindingContract
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetNativeProjectionDefinition
import org.flowlang.adapters.testing.MaterializationResolverFixture as TargetMaterializationResolver
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode
import org.flowlang.projection.ProjectionBinding
import org.flowlang.projection.ProjectionBindingKind
import org.flowlang.projection.ProjectionBindingResolutionStatus
import org.flowlang.targets.builtin.BuiltInNativeProjectionCatalogs
import org.flowlang.targets.builtin.GitHubActionsManifestGenerator
import org.flowlang.targets.builtin.GitHubActionsWorkspaceContinuityPlanner
import org.flowlang.targets.builtin.JenkinsManifestGenerator

class TargetNativeProjectionArchitectureTests {
    @Test
    fun catalogRejectsDuplicateOpaqueImplementationIdentity() {
        val definition = definition()

        val failure = assertFailsWith<IllegalArgumentException> {
            TargetNativeProjectionCatalog.of("future-target", definition, definition)
        }

        assertTrue(failure.message.orEmpty().contains("Duplicate native projection definition"))
    }

    @Test
    fun nativeRuleRequiresAnImplementedCatalogContract() {
        val failure = assertFailsWith<IllegalStateException> {
            TargetNativeProjectionCatalog.empty("future-target")
                .requireCompatibleRules(listOf(nativeRule()))
        }

        assertTrue(failure.message.orEmpty().contains("no native projection implementation contract"))
    }

    @Test
    fun nativeRuleBindingSchemaMustMatchImplementedContract() {
        val catalog = TargetNativeProjectionCatalog.of("future-target", definition())
        val wrongKind = nativeRule(bindings = mapOf(
            "source" to ProjectionBinding.secret("repository-token")
        ))

        val failure = assertFailsWith<IllegalArgumentException> {
            catalog.requireCompatibleRules(listOf(wrongKind))
        }

        assertTrue(failure.message.orEmpty().contains("accepted kinds"))
        assertTrue(failure.message.orEmpty().contains("TASK_PARAMETER"))
    }

    @Test
    fun nativeRuleCannotOmitRequiredOrInventUnknownBindings() {
        val catalog = TargetNativeProjectionCatalog.of("future-target", definition())

        val missing = assertFailsWith<IllegalArgumentException> {
            catalog.requireCompatibleRules(listOf(nativeRule(bindings = emptyMap())))
        }
        val unexpected = assertFailsWith<IllegalArgumentException> {
            catalog.requireCompatibleRules(listOf(nativeRule(bindings = mapOf(
                "source" to ProjectionBinding.taskParameter("source"),
                "invented" to ProjectionBinding.literal("value")
            ))))
        }

        assertTrue(missing.message.orEmpty().contains("missing required bindings: source"))
        assertTrue(unexpected.message.orEmpty().contains("unsupported bindings: invented"))
    }

    @Test
    fun catalogCompilesTypedBindingsWithoutLosingSourceProvenance() {
        val catalog = TargetNativeProjectionCatalog.of("future-target", definition())
        val rule = nativeRule()
        val task = TaskNode(
            id = "checkout_source",
            module = "git",
            action = "checkout",
            target = "source",
            params = mapOf("source" to "https://example.invalid/source.git")
        )

        val resolution = TargetMaterializationResolver.resolve(
            task = task,
            targetName = "future-target",
            projectionRules = listOf(rule),
            nativeProjections = catalog
        )
        val binding = assertNotNull(resolution.rendererPayload).bindings.getValue("source")

        assertEquals(ProjectionBindingKind.TASK_PARAMETER, binding.kind)
        assertEquals("source", binding.name)
        assertEquals("https://example.invalid/source.git", binding.value)
        assertEquals(ProjectionBindingResolutionStatus.RESOLVED, binding.resolutionStatus)
        assertEquals(rule.evidenceReference, resolution.rendererPayload?.evidenceReference)
    }

    @Test
    fun missingCompileTimeValueRemainsUnresolvedInsteadOfBecomingEmptyText() {
        val catalog = TargetNativeProjectionCatalog.of("future-target", definition())
        val resolution = TargetMaterializationResolver.resolve(
            task = TaskNode(
                id = "checkout_source",
                module = "git",
                action = "checkout",
                target = "source"
            ),
            targetName = "future-target",
            projectionRules = listOf(nativeRule()),
            nativeProjections = catalog
        )
        val binding = assertNotNull(resolution.rendererPayload).bindings.getValue("source")

        assertEquals(ProjectionBindingResolutionStatus.UNRESOLVED, binding.resolutionStatus)
        assertEquals(null, binding.value)
        assertTrue(binding.reason.orEmpty().contains("does not provide required parameter 'source'"))
    }

    @Test
    fun missingOptionalCompileTimeBindingIsOmittedInsteadOfDowngradingReadiness() {
        val definition = TargetNativeProjectionDefinition(
            kind = "FUTURE_TASK",
            reference = "checkout",
            bindings = mapOf(
                "source" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.TASK_PARAMETER)
                ),
                "depth" to TargetNativeProjectionBindingContract(
                    acceptedKinds = setOf(ProjectionBindingKind.TASK_PARAMETER),
                    required = false
                )
            )
        )
        val catalog = TargetNativeProjectionCatalog.of("future-target", definition)
        val rule = nativeRule(bindings = mapOf(
            "source" to ProjectionBinding.taskParameter("source"),
            "depth" to ProjectionBinding.taskParameter("depth")
        ))
        val resolution = TargetMaterializationResolver.resolve(
            task = TaskNode(
                id = "checkout_source",
                module = "git",
                action = "checkout",
                target = "source",
                params = mapOf("source" to "https://example.invalid/source.git")
            ),
            targetName = "future-target",
            projectionRules = listOf(rule),
            nativeProjections = catalog
        )
        val payload = assertNotNull(resolution.rendererPayload)

        assertEquals(setOf("source"), payload.bindings.keys)
        assertEquals(ProjectionBindingResolutionStatus.RESOLVED, payload.bindings.getValue("source").resolutionStatus)
    }

    @Test
    fun builtInDistributionDeclaresOnlyActuallyImplementedNativeCoverage() {
        assertEquals(2, BuiltInNativeProjectionCatalogs.jenkins.definitions.size)
        assertEquals(4, BuiltInNativeProjectionCatalogs.githubActions.definitions.size)
        assertEquals(
            setOf(
                "actions/checkout@v4",
                "docker/build-push-action@v7",
                GitHubActionsWorkspaceContinuityPlanner.UPLOAD_REFERENCE,
                GitHubActionsWorkspaceContinuityPlanner.DOWNLOAD_REFERENCE
            ),
            BuiltInNativeProjectionCatalogs.githubActions.definitions.map { it.reference }.toSet()
        )
        assertEquals(2, BuiltInNativeProjectionCatalogs.tekton.definitions.size)

        val fakeNativeCompatibility = CompatibilityReport(
            target = "github-actions",
            status = SupportLevel.SUPPORTED,
            capabilityStatus = SupportLevel.SUPPORTED,
            projectionRules = listOf(nativeRule(kind = "GITHUB_ACTION"))
        )
        val failure = assertFailsWith<IllegalStateException> {
            GitHubActionsManifestGenerator().generate(ExecutionPlan(flowName = "false-claim"), fakeNativeCompatibility)
        }

        assertTrue(failure.message.orEmpty().contains("no native projection implementation contract"))
    }

    @Test
    fun existingJenkinsCheckoutUsesCatalogEvidenceAndPreservesHonestReadiness() {
        val rule = TargetProjectionRule(
            module = "git",
            action = "checkout",
            mode = TargetProjectionMode.NATIVE,
            reason = "Native checkout evidence.",
            evidenceReference = "test:jenkins#git.checkout",
            payload = TargetRendererPayloadTemplate(
                kind = "JENKINS_STEP",
                reference = "git",
                bindings = mapOf(
                    "url" to ProjectionBinding.taskParameter("url"),
                    "branch" to ProjectionBinding.taskParameter("branch", "main")
                )
            )
        )
        val plan = ExecutionPlan(
            flowName = "checkout",
            nodes = listOf(TaskNode(
                id = "checkout",
                module = "git",
                action = "checkout",
                target = "source",
                params = mapOf("url" to "https://example.invalid/source.git")
            ))
        )
        val compatibility = CompatibilityReport(
            target = "jenkins",
            status = SupportLevel.SUPPORTED,
            capabilityStatus = SupportLevel.SUPPORTED,
            projectionRules = listOf(rule)
        )

        val manifest = JenkinsManifestGenerator().generate(plan, compatibility)
        val payload = assertNotNull(manifest.jobs.single().steps.single().rendererPayload)

        assertEquals("JENKINS_STEP", payload.kind)
        assertEquals("git", payload.reference)
        assertEquals("https://example.invalid/source.git", payload.bindings.getValue("url").value)
        assertFalse(payload.evidenceReference.isBlank())
    }

    private fun definition(): TargetNativeProjectionDefinition = TargetNativeProjectionDefinition(
        kind = "FUTURE_TASK",
        reference = "checkout",
        bindings = mapOf(
            "source" to TargetNativeProjectionBindingContract(
                acceptedKinds = setOf(ProjectionBindingKind.TASK_PARAMETER)
            )
        )
    )

    private fun nativeRule(
        kind: String = "FUTURE_TASK",
        bindings: Map<String, ProjectionBinding> = mapOf(
            "source" to ProjectionBinding.taskParameter("source")
        )
    ): TargetProjectionRule = TargetProjectionRule(
        module = "git",
        action = "checkout",
        mode = TargetProjectionMode.NATIVE,
        reason = "Native implementation evidence exists.",
        evidenceReference = "test:future-target#git.checkout",
        payload = TargetRendererPayloadTemplate(
            kind = kind,
            reference = "checkout",
            bindings = bindings
        )
    )
}
