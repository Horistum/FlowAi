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
import org.flowlang.targets.builtin.JenkinsMatchProjectionScope

class JenkinsMatchProjectionTests {
    companion object {
        private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
        private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
        private val catalog get() = ReferenceTargetProjections.nativeCatalogs.getValue("jenkins")
        private val source = File("flow-adapter-jenkins/src/runtimeTest/match-selected.flow").readText()
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

    @Test fun scopeRequiresTheOwnedPlanScopedProviderAndKeepsGenericSupportUnchanged() {
        val declared = targets.getValue("jenkins")
        val scoped = JenkinsMatchProjectionScope(catalog).resolve(unit.authorization, "jenkins", declared)
        assertEquals(SupportLevel.SUPPORTED, scoped.features["match.basic"])
        assertEquals(SupportLevel.UNSUPPORTED, declared.match)
        assertEquals(declared.copy(features = scoped.features, notes = scoped.notes), scoped)
        val definitions = catalog.structuralDefinitions.filter { it.structure == TargetStructuralProjectionKind.MATCH }
        val wrong = TargetNativeProjectionCatalog.of("jenkins", emptyList(), structuralDefinitions = definitions.map { it.copy(reference = "if") })
        val broad = TargetNativeProjectionCatalog.of("jenkins", emptyList(), structuralDefinitions = definitions.map {
            it.copy(supportScope = TargetStructuralProjectionSupportScope.TARGET_WIDE) })
        for (candidate in listOf(null, wrong, broad, TargetNativeProjectionCatalog.empty("jenkins"), TargetNativeProjectionCatalog.empty("github-actions")))
            assertEquals(declared, JenkinsMatchProjectionScope(candidate).resolve(unit.authorization, "jenkins", declared))
    }

    @Test fun literalScopeRejectsUnknownImplicitDynamicAndOverlappingPredicates() {
        assertTrue(JenkinsMatchProjectionScope.supports("\"inert\"", listOf("false", "true", "false")))
        assertTrue(JenkinsMatchProjectionScope.supports("\"inert\"", listOf("false")))
        for (conditions in listOf(emptyList(), listOf("true", "true"), listOf("enabled"), listOf("status == 200"),
            listOf("1"), listOf("null"), listOf("true; error"), listOf("not false")))
            assertFalse(JenkinsMatchProjectionScope.supports("\"inert\"", conditions), conditions.toString())
        for (value in listOf("source", "null", "1", "true", "[\"value\"]", "\"inert\"; error"))
            assertFalse(JenkinsMatchProjectionScope.supports(value, listOf("true")), value)
    }

    @Test fun outOfScopeSourceCasesHandlersAndNestedBodiesStayNonExecutable() {
        val checkout = "git.checkout source { url: \"git://127.0.0.1:9418/repository.git\" branch: \"selected\" }"
        val dynamic = source.replace("  systems {", "  input { enabled: boolean default true }\n  systems {")
        for (text in listOf(source.replace("when false", "when true"),
            dynamic.replace("match \"literal-source\"", "match enabled"), dynamic.replace("when true", "when enabled"),
            source.replace("      default {", "      when error { $checkout }\n      default {"),
            source.replace("      when true {", "      when true { match \"nested\" { when true { } }"),
            source.replace("      when true {", "      when true { if true { $checkout }"))) {
            val candidate = compile(text)
            assertEquals(targets.getValue("jenkins"), JenkinsMatchProjectionScope(catalog).resolve(candidate.authorization, "jenkins", targets.getValue("jenkins")))
            assertFails { pipeline.generate(TargetMaterializationRequest.fromCompilation(candidate, testTargetSelection("jenkins", targets), strict = true)) }
        }
    }

    @Test fun nativeRenderingRetainsExclusiveGuardsAndAnEmptySelectedCase() {
        val rendered = provider.render(manifest())
        assertContains(rendered, "if (false) {")
        assertContains(rendered, "} else if (true) {")
        assertContains(rendered, "} else {")
        val empty = compile(File("flow-adapter-jenkins/src/runtimeTest/match-empty.flow").readText())
        val artifact = provider.render(pipeline.generate(TargetMaterializationRequest.fromCompilation(empty,
            testTargetSelection("jenkins", targets), strict = true)))
        assertTrue(Regex("else if \\(true\\) \\{\\s*} else \\{").containsMatchIn(artifact))
        assertEquals(3, artifact.lineSequence().count { it.trimStart().startsWith("git branch:") })
    }

    @Test fun rendererRejectsForgedScopeDespiteNativePayloadAndExecutableMetadata() {
        val original = manifest()
        val match = original.jobs.flatMap { it.steps }.single { it.type == "match" }
        val body = match.children[1].children
        val invalid = listOf(match.copy(params = mapOf("source" to "dynamic")),
            match.copy(children = listOf(match.children[0].copy(params = mapOf("condition" to "true"))) + match.children.drop(1)),
            match.copy(children = match.children.map { if (it.type == "match-error") it.copy(children = body) else it }),
            match.copy(rendererPayload = match.rendererPayload!!.copy(reference = "if")),
            match.copy(children = match.children.map { if (it.type == "match-case") it.copy(children = listOf(match)) else it }))
        for (replacement in invalid) {
            val forged = original.copy(jobs = original.jobs.map { job -> job.copy(steps = job.steps.map { if (it.id == match.id) replacement else it }) })
            assertFails { provider.render(forged) }
        }
    }
}
