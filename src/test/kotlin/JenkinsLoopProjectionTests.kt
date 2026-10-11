import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.generators.manifest.TargetInput
import org.flowlang.planner.TaskNode
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInNativeProjectionCatalogs
import org.flowlang.targets.builtin.JenkinsManifestRenderer
import kotlin.test.assertFails
import kotlin.test.assertTrue
import org.flowlang.adapters.control.AdapterControlMaterializationLoader
import org.flowlang.adapters.control.AdapterControlFamily
import org.flowlang.adapters.control.AdapterControlDecision
import org.flowlang.distribution.reference.ReferenceAdapterEvidence
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.compiler.requireAccepted
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.targets.builtin.JenkinsLoopProjectionScope
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologySupportStatus

class JenkinsLoopProjectionTests {
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
    private val declared get() = targets.getValue("jenkins")
    private val source = File("flow-adapter-jenkins/src/runtimeTest/loop-order.flow").readText()
    private fun authorization(text: String = source): org.flowlang.compiler.CompilationAuthorization {
        val file = kotlin.io.path.createTempFile(suffix = ".flow").toFile()
        try {
            file.writeText(text)
            return FlowSourceFrontend(FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File("modules")),
                StandardEnvironmentSafetyPolicyNotes.policy(File(".")))).compile(file).requireAccepted().authorization
        } finally { file.delete() }
    }
    private fun scoped(text: String = source, catalog: TargetNativeProjectionCatalog? = BuiltInNativeProjectionCatalogs.jenkins) =
        JenkinsLoopProjectionScope(catalog).resolve(authorization(text), "jenkins", declared)

    @Test fun literalAuthorizationKeepsGenericLoopSupportUnchanged() {
        assertEquals(declared.copy(features = declared.features + mapOf("loop.dynamic" to SupportLevel.SUPPORTED,
            "loops.dynamic" to SupportLevel.SUPPORTED), notes = scoped().notes), scoped())
        assertEquals(SupportLevel.UNSUPPORTED, declared.dynamicLoops)
        assertEquals(declared.topologyProfile, scoped().topologyProfile)
    }

    @Test fun absentWrongOrBroadProviderCannotAuthorizeLoop() {
        val definitions = BuiltInNativeProjectionCatalogs.jenkins.structuralDefinitions.filter { it.structure == TargetStructuralProjectionKind.LOOP }
        val wrong = TargetNativeProjectionCatalog.of("jenkins", emptyList(), structuralDefinitions = definitions.map { it.copy(reference = "if") })
        val broad = TargetNativeProjectionCatalog.of("jenkins", emptyList(), structuralDefinitions = definitions.map {
            it.copy(supportScope = org.flowlang.generators.manifest.TargetStructuralProjectionSupportScope.TARGET_WIDE) })
        for (catalog in listOf(null, wrong, broad, TargetNativeProjectionCatalog.empty("jenkins"), TargetNativeProjectionCatalog.empty("github-actions")))
            assertEquals(declared, scoped(catalog = catalog))
    }

    @Test fun dynamicCollectionsNestedLoopsAndOtherBodiesStayUnsupported() {
        assertEquals(declared, scoped(source.replace("[\"selected\", \"selected\", \"alternate\"]", "[1, 2]")))
        val nested = source.replace("for item in", "for outer in [\"one\"] { for item in")
            .replace("} -> successorCheckout", "} -> successorCheckout }")
        assertEquals(declared, scoped(nested))
        val dynamic = source.replace("  systems {", "  vars { items: [\"selected\"] }\n  systems {")
            .replace("[\"selected\", \"selected\", \"alternate\"]", "items")
        assertEquals(declared, scoped(dynamic))
        assertEquals(declared, scoped(File("flow-adapter-jenkins/src/runtimeTest/retry-success.flow").readText()))
    }

    @Test fun stringLiteralParsingAndLoopNamesRejectNativeScopeConflicts() {
        assertEquals(emptyList(), JenkinsLoopProjectionScope.literalItems("[]"))
        assertEquals(listOf("a", "a", "b"), JenkinsLoopProjectionScope.literalItems("[\"a\", \"a\", \"b\"]"))
        for (value in listOf("items", "[1]", "[true]", "[null]", "[name]", "[[\"a\"]]", "[\"a\"] + [\"b\"]", "["))
            assertEquals(null, JenkinsLoopProjectionScope.literalItems(value))
        for (name in listOf("params", "env", "git", "for", "flowError", "item.x", "item;error"))
            assertEquals(false, JenkinsLoopProjectionScope.safeItem(name))
        assertTrue(JenkinsLoopProjectionScope.safeItem("entry_1"))
        assertEquals(declared, scoped(source.replace("item", "params")))
        val collision = source.replace("  systems {", "  input { item: text default \"selected\" }\n  systems {")
        assertEquals(declared, scoped(collision))
    }

    @Test fun rendererRequiresExactLiteralLoopBindingsAndEscapesElements() {
        val resolved = BuiltInNativeProjectionCatalogs.jenkins.resolveStructure(TargetStructuralProjectionKind.LOOP, "for-in")
        fun render(params: Map<String, String>) = JenkinsManifestRenderer().render(manifest(TargetStep(
            id = "loop", type = "loop", params = params, children = listOf(jenkinsCheckoutStep("body")),
            materialization = resolved.materialization, rendererPayload = resolved.rendererPayload), supportedJenkinsCompatibility(), true))
        val valid = mapOf("item" to "entry", "source" to "[\"a\", \"a\", \"b\"]")
        assertContains(render(valid), "for (def entry in ['a', 'a', 'b']) {")
        assertContains(render(valid + ("source" to "[]")), "for (def entry in []) {")
        assertContains(render(valid + ("source" to "[\"a'b\"]")), "['a\\'b']")
        for (params in listOf(valid - "item", valid + ("extra" to "ignored"), valid + ("source" to "items"),
            valid + ("source" to "[1]"), valid + ("item" to "params"), valid + ("item" to "x;error")))
            assertFails { render(params) }
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
            "generator" to "JenkinsLoopProjectionTests",
            "standardVersion" to FlowStandardVersions.FLOW_STANDARD_VERSION,
            "capabilityCompatibility" to compatibility.capabilityStatus.name,
            "effectiveCompatibility" to compatibility.status.name,
            "materializationReadiness" to if (executableMetadata) "COMPLETE" else "REVIEW_REQUIRED",
            "projectionReadiness" to if (executableMetadata) "EXECUTABLE" else "REVIEW_ONLY",
            "executable" to executableMetadata.toString()
        )
    )
}
