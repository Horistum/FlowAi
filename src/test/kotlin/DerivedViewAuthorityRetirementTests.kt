import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.compiler.CanonicalExecutionGraphGate
import org.flowlang.compiler.CanonicalExecutionGraphProjection
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.requireAccepted
import org.flowlang.compiler.requireAuthorizedTask
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.generators.manifest.TargetMaterializationResolver
import org.flowlang.modules.ModuleRegistry
import org.flowlang.obligations.ArchitectureObligationKind
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.planner.TaskNode
import org.flowlang.targets.builtin.BuiltInNativeProjectionCatalogs

class DerivedViewAuthorityRetirementTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"))
    private val compiler = FlowCompilationService(registry)

    @Test
    fun canonicalCompatibilityViewIsProjectedDirectlyFromTheAuthorizedGraph() {
        val unit = referenceUnit()
        val direct = CanonicalExecutionGraphProjection.toCanonicalExecutionPlan(
            unit.graph,
            unit.authorization.bindings
        )

        assertEquals(unit.canonicalPlan, direct)
        // Historical facade remains a compatibility oracle only. It must reproduce the
        // graph-owned view byte-for-byte, not own production canonicalization.
        assertEquals(ExecutionPlanCanonicalizer.canonicalize(unit.executionPlan), direct)

        val authorizationSource = File(
            "src/main/kotlin/org/flowlang/compiler/CompilationAuthorization.kt"
        ).readText()
        assertFalse(authorizationSource.contains("ExecutionPlanCanonicalizer"))
        assertTrue(authorizationSource.contains("toWorkflowExecutionPlanSet("))
    }

    @Test
    fun materializationObligationUsesCanonicalCapabilityAndKeepsImplementationAsBindingEvidence() {
        val unit = referenceUnit()
        val task = unit.executionPlan.nodes.filterIsInstance<TaskNode>().first()
        val authorizedTask = unit.authorization.requireAuthorizedTask(task)
        val target = TargetRegistryYamlLoader.loadDirectory(File("targets")).getValue("jenkins")

        val resolution = TargetMaterializationResolver.resolve(
            authorization = unit.authorization,
            task = task,
            targetName = target.target,
            projectionRules = target.projectionRules,
            nativeProjections = BuiltInNativeProjectionCatalogs.jenkins
        )
        val obligation = resolution.obligationGraph.nodes.single()

        assertEquals(authorizedTask.node.semantics.capability?.value, obligation.declaration)
        assertEquals(
            "${authorizedTask.binding.module}.${authorizedTask.binding.action}",
            obligation.attributes["implementationBinding"]
        )
        assertEquals(unit.graphDigest.value, obligation.attributes["graphDigest"])
    }

    @Test
    fun moduleAndActionCannotSynthesizeMissingCanonicalMeaning() {
        val task = TaskNode(
            id = "checkout",
            module = "git",
            action = "checkout",
            target = "repository",
            semanticCapability = null
        )
        val plan = ExecutionPlan(flowName = "missing-canonical-capability", nodes = listOf(task))
        val authorization = CanonicalExecutionGraphGate.authorizeCompatibilityPlan(
            plannerPlan = plan,
            evidenceId = "test:ar-01d:no-module-action-semantics"
        )

        val resolution = TargetMaterializationResolver.resolve(
            authorization = authorization,
            task = task,
            targetName = "jenkins",
            projectionRules = TargetRegistryYamlLoader.loadDirectory(File("targets"))
                .getValue("jenkins")
                .projectionRules,
            nativeProjections = BuiltInNativeProjectionCatalogs.jenkins
        )
        val obligation = resolution.obligationGraph.nodes.single()
        val canonicalTask = authorization.requireAuthorizedTask(task).node

        assertEquals(null, canonicalTask.semantics.capability)
        assertEquals(ArchitectureObligationKind.CONFORMANCE_REQUIREMENT, obligation.kind)
        assertEquals("canonical.task.authorized", obligation.declaration)
        assertEquals("git.checkout", obligation.attributes["implementationBinding"])
        assertFalse(obligation.attributes.containsKey("canonicalCapability"))
    }

    @Test
    fun detachedOrMutatedTaskCannotBorrowAuthorizationFromAnotherGraphView() {
        val unit = referenceUnit()
        val task = unit.executionPlan.nodes.filterIsInstance<TaskNode>().first()
        val changed = task.copy(
            module = task.module + "-alternate",
            action = task.action + "-alternate"
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            unit.authorization.requireAuthorizedTask(changed)
        }

        assertTrue(failure.message.orEmpty().contains("differs from the graph-derived compatibility view"))
    }

    @Test
    fun executionLookingNotesGraphIsRetiredFromProduction() {
        assertFalse(File("src/main/kotlin/org/flowlang/semantic/SemanticActionGraph.kt").exists())
        val replacement = File(
            "src/main/kotlin/org/flowlang/obligations/ArchitectureObligationGraph.kt"
        )
        assertTrue(replacement.isFile)
        assertTrue(replacement.readText().contains("not an execution IR"))

        val offenders = File("src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.endsWith("CompilerAxisConformanceChecks.kt") }
            .filter { source ->
                val text = source.readText()
                text.contains("org.flowlang.semantic.SemanticActionGraph") ||
                    Regex("""\bSemanticActionGraph\s*[<(]""").containsMatchIn(text)
            }
            .map(File::getPath)
            .toList()

        assertTrue(offenders.isEmpty(), offenders.joinToString())
    }

    private fun referenceUnit() = IntentYamlFrontend(compiler)
        .compile(File("examples/intent/build-test-deploy.intent.yaml"))
        .requireAccepted()
}
