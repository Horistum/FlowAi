package org.flowlang.tests

import org.flowlang.frontend.FrontendCompilerComposition

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.flowlang.compiler.CompilationFrontend
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.CompilationSource
import org.flowlang.compiler.CompilationStage
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.IntentCompilationInput
import org.flowlang.compiler.requireAccepted
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.planner.FlowPlanner
import org.flowlang.validator.FlowValidator

class IntentFrontendConvergenceTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"))
    private val compiler = FrontendCompilerComposition.compiler(registry)
    private val frontend = IntentYamlFrontend(compiler)
    private val referenceIntent = File("examples/intent/build-test-deploy.intent.yaml")

    @Test
    fun intentFrontendPreservesTheExistingAcceptedPipelineExactly() {
        val compiled = frontend.compile(referenceIntent).requireAccepted()

        val intent = IntentYamlLoader.load(referenceIntent)
        val intentValidation = IntentCapabilityValidator(registry).validate(intent).also { it.assertValid() }
        val ast = FrontendCompilerComposition.intentPlanner(registry).plan(intent)
        val validation = FrontendCompilerComposition.flowValidator(registry).validate(ast)
        assertTrue(validation.valid, validation.issues.joinToString { it.code })
        val plan = FlowPlanner(registry).plan(ast)
        val canonical = ExecutionPlanCanonicalizer.canonicalize(plan)

        assertEquals(CompilationFrontend.INTENT_YAML, compiled.source.frontend)
        assertEquals(referenceIntent.absoluteFile.toPath().normalize().toString(), compiled.source.identity)
        assertEquals(referenceIntent.path, compiled.source.sourceName)
        assertEquals("application/vnd.flow.intent+yaml", compiled.source.mediaType)
        assertEquals(referenceIntent.readBytes().size.toLong(), compiled.source.byteCount)
        assertEquals(intent, compiled.requireIntentEvidence().intent)
        assertEquals(intentValidation, compiled.requireIntentEvidence().validation)
        assertEquals(ast, compiled.ast)
        assertEquals(validation, compiled.validation)
        assertEquals(plan, compiled.executionPlan)
        assertEquals(canonical, compiled.canonicalPlan)
    }

    @Test
    fun authoredFormattingChangesProvenanceButNotCompiledMeaning() {
        val directory = createTempDirectory("flow-intent-formatting").toFile()
        val formatted = File(directory, referenceIntent.name).apply {
            writeText("# formatting-only mutation\n\n" + referenceIntent.readText() + "\n")
        }

        val baseline = frontend.compile(referenceIntent).requireAccepted()
        val alternate = frontend.compile(formatted).requireAccepted()

        assertNotEquals(baseline.source.sha256, alternate.source.sha256)
        assertEquals(baseline.requireIntentEvidence().intent, alternate.requireIntentEvidence().intent)
        assertEquals(baseline.executionPlan, alternate.executionPlan)
        assertEquals(baseline.canonicalPlan, alternate.canonicalPlan)
    }

    @Test
    fun semanticMutationChangesTheCompiledPlan() {
        val directory = createTempDirectory("flow-intent-semantic-mutation").toFile()
        val mutated = File(directory, referenceIntent.name).apply {
            writeText(referenceIntent.readText().replace("branch: main", "branch: release"))
        }

        val baseline = frontend.compile(referenceIntent).requireAccepted()
        val alternate = frontend.compile(mutated).requireAccepted()

        assertNotEquals(baseline.source.sha256, alternate.source.sha256)
        assertNotEquals(baseline.executionPlan, alternate.executionPlan)
        assertNotEquals(baseline.canonicalPlan, alternate.canonicalPlan)
    }

    @Test
    fun invalidIntentStopsBeforeFlowPlanning() {
        val directory = createTempDirectory("flow-invalid-intent").toFile()
        val source = referenceIntent.readText()
        val approvalDeclaration = "      - id: approve-prod\n        capability: APPROVE\n"
        assertEquals(1, source.split(approvalDeclaration).size - 1,
            "The negative fixture must remove exactly one declared approval.")
        val mutated = source.replace(approvalDeclaration, "")
        assertNotEquals(source, mutated, "The negative fixture must change the authored intent.")
        val invalid = File(directory, referenceIntent.name).apply { writeText(mutated) }

        val result = frontend.compile(invalid)
        val rejection = assertIs<CompilationResult.Rejected>(result).rejection

        assertEquals(CompilationStage.INTENT_VALIDATION, rejection.stage)
        assertTrue(rejection.diagnostics.any { it.code == "UNKNOWN_STEP_DEPENDENCY" })
        assertEquals(null, rejection.ast)
        assertEquals(null, rejection.flowValidation)
    }

    @Test
    fun intentInputCannotBorrowFlowSourceProvenance() {
        val intent = IntentYamlLoader.load(referenceIntent)
        val flowSource = CompilationSource.fromBytes(
            CompilationFrontend.FLOW_SOURCE,
            "fixture.flow",
            "flow sample {}".toByteArray()
        )

        assertFailsWith<IllegalArgumentException> {
            IntentCompilationInput(flowSource, intent)
        }
    }


}
