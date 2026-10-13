import java.io.File
import kotlin.test.*
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.SupportLevel
import org.flowlang.compiler.requireAccepted
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.generators.manifest.*
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.targets.builtin.JenkinsParallelProjectionScope
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologySupportStatus

class JenkinsParallelProjectionTests {
    companion object {
        private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
        private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
        private val catalog get() = ReferenceTargetProjections.nativeCatalogs.getValue("jenkins")
        private val source = File("flow-adapter-jenkins/src/runtimeTest/parallel.flow").readText()
        private fun compile(text: String) = kotlin.io.path.createTempFile(suffix = ".flow").toFile().let { file ->
            try {
                file.writeText(text)
                FlowSourceFrontend(FrontendCompilerComposition.compiler(modules,
                    StandardEnvironmentSafetyPolicyNotes.policy(File(".")))).compile(file).requireAccepted()
            } finally { file.delete() }
        }
        private val unit by lazy { compile(source) }
        private val pipeline by lazy { ReferenceTargetProjections.pipeline(targets, modules = modules) }
        private val provider get() = ReferenceTargetProjections.registry.requireProvider("jenkins")
        private fun manifest() = pipeline.generate(TargetMaterializationRequest.fromCompilation(unit,
            testTargetSelection("jenkins", targets), strict = true))
    }

    @Test fun scopeRequiresOwnedProviderAndLeavesGenericSupportUnchanged() {
        val declared = targets.getValue("jenkins")
        val scoped = JenkinsParallelProjectionScope(catalog).resolve(unit.authorization, "jenkins", declared)
        assertEquals(SupportLevel.SUPPORTED, scoped.features["parallel.dag"])
        assertEquals(SupportLevel.UNSUPPORTED, declared.parallel)
        assertEquals(declared.copy(features = scoped.features, notes = scoped.notes, topologyProfile = scoped.topologyProfile), scoped)
        val topology = assertNotNull(scoped.topologyProfile)
        assertEquals(ExecutionTopologySupportStatus.SUPPORTED, topology.declarations.single { it.kind == ExecutionTopologyKind.BRANCH_ISOLATION }.status)
        assertEquals(declared.topologyProfile!!.declarations.filter { it.kind != ExecutionTopologyKind.BRANCH_ISOLATION },
            topology.declarations.filter { it.kind != ExecutionTopologyKind.BRANCH_ISOLATION })
        val missing = declared.copy(topologyProfile = null)
        assertEquals(missing, JenkinsParallelProjectionScope(catalog).resolve(unit.authorization, "jenkins", missing))
        val definitions = catalog.structuralDefinitions.filter { it.structure == TargetStructuralProjectionKind.PARALLEL }
        val wrong = TargetNativeProjectionCatalog.of("jenkins", emptyList(), structuralDefinitions = definitions.map { it.copy(reference = "parallel") })
        val broad = TargetNativeProjectionCatalog.of("jenkins", emptyList(), structuralDefinitions = definitions.map {
            it.copy(supportScope = TargetStructuralProjectionSupportScope.TARGET_WIDE) })
        for (candidate in listOf(null, wrong, broad, TargetNativeProjectionCatalog.empty("jenkins"), TargetNativeProjectionCatalog.empty("github-actions")))
            assertEquals(declared, JenkinsParallelProjectionScope(candidate).resolve(unit.authorization, "jenkins", declared))
    }

    @Test fun unsupportedFailurePolicyBodiesBindingsAndMessagesFailClosed() {
        val gate = "approve manual { message: \"Approve left branch?\" }"
        val checkout = "git.checkout source { url: \"git://127.0.0.1:9418/repository.git\" branch: \"selected\" }"
        for (text in listOf(source.replace("failFast false", "failFast true"), source.replace(" failFast false", ""),
            source.replace(gate, ""), source.replace(gate, checkout), source.replace(gate, "$gate -> decision"),
            source.replace("Approve right branch?", "Approve left branch?"),
            source.replace(gate, "parallel failFast false { branch \"nested\" { $gate } }"),
            source.replace(gate, "$gate approve manual { message: \"Extra gate?\" }"))) {
            val candidate = compile(text)
            assertEquals(targets.getValue("jenkins"), JenkinsParallelProjectionScope(catalog).resolve(candidate.authorization, "jenkins", targets.getValue("jenkins")))
            assertFails { pipeline.generate(TargetMaterializationRequest.fromCompilation(candidate, testTargetSelection("jenkins", targets), strict = true)) }
        }
    }

    @Test fun duplicateAuthoredLabelsRetainDistinctClosuresAndJoinBeforeCheckout() {
        val manifest = manifest()
        val group = manifest.jobs.flatMap { it.steps }.single { it.type == "parallel" }
        assertEquals(listOf("review", "review"), group.children.map { it.name })
        val rendered = provider.render(manifest)
        assertContains(rendered, "parallel([")
        assertContains(rendered, "failFast: false")
        group.children.forEach { assertContains(rendered, "'${it.id}': {") }
        assertEquals(2, rendered.lineSequence().count { it.trimStart().startsWith("input message:") })
        assertTrue(rendered.indexOf("])") < rendered.indexOf("git branch:"))
    }

    @Test fun rendererRejectsForgedBranchScopeAndPayload() {
        val original = manifest()
        val group = original.jobs.flatMap { it.steps }.single { it.type == "parallel" }
        val branch = group.children.first()
        val gate = branch.children.single()
        val injected = original.jobs.flatMap { it.steps }.single { it.type == "action" }.copy(id = "injected_checkout")
        val mutations = listOf(group.copy(metadata = group.metadata + ("failFast" to "true")),
            group.copy(rendererPayload = group.rendererPayload!!.copy(reference = "if")),
            group.copy(children = listOf(branch.copy(children = emptyList())) + group.children.drop(1)),
            group.copy(children = listOf(branch.copy(children = listOf(gate.copy(children = listOf(injected))))) + group.children.drop(1)),
            group.copy(children = listOf(branch.copy(children = listOf(gate.copy(metadata = gate.metadata + ("resultName" to "decision"))))) + group.children.drop(1)))
        for (replacement in mutations) assertFails {
            provider.render(original.copy(jobs = original.jobs.map { job -> job.copy(steps = job.steps.map { if (it.id == group.id) replacement else it }) }))
        }
    }
}
