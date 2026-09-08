package org.flowlang.tests

import org.flowlang.frontend.FrontendCompilerComposition

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.validator.FlowValidator

class DeclaredIntentOutputValidationTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"))

    @Test
    fun declaredOutputBecomesReferenceableAndProducesValueContinuity() {
        val intent = IntentYamlLoader.loadText(
            """
            intentVersion: '2.0'
            kind: FlowIntentDocument
            name: declared-output-reference
            workflows:
            - name: main
              kind: CUSTOM
              steps:
              - id: producer
                capability: CUSTOM
                produces: [bundle]
                params: {operation: package}
              - id: consumer
                capability: CUSTOM
                requires: [producer]
                params: {operation: consume, artifact: 'ref:bundle'}
            failure: {notify: false, rollback: false, stopOnError: true}
            """.trimIndent(),
            "declared-output-reference.intent.yaml"
        )

        val ast = FrontendCompilerComposition.intentPlanner(registry).plan(intent)
        val validation = FrontendCompilerComposition.flowValidator(registry).validate(ast)
        assertTrue(validation.valid, validation.issues.joinToString { "${it.code}: ${it.message}" })

        val plan = FlowPlanner(registry).plan(ast)
        val producer = plan.tasks.single { it.sourceId == "producer" }
        val consumer = plan.tasks.single { it.sourceId == "consumer" }
        assertTrue(
            plan.dependencyRelations.any {
                it.sourceNodeId == producer.id &&
                    it.targetNodeId == consumer.id &&
                    it.kind == PlanDependencyKind.VALUE &&
                    it.channel == "bundle"
            }
        )
    }

    @Test
    fun duplicateDeclaredOutputIsRejectedAtTheAstBoundary() {
        val intent = IntentYamlLoader.loadText(
            """
            intentVersion: '2.0'
            kind: FlowIntentDocument
            name: duplicate-declared-output
            workflows:
            - name: main
              kind: CUSTOM
              steps:
              - {id: first, capability: CUSTOM, produces: [bundle], params: {operation: first}}
              - {id: second, capability: CUSTOM, produces: [bundle], params: {operation: second}}
            failure: {notify: false, rollback: false, stopOnError: true}
            """.trimIndent(),
            "duplicate-declared-output.intent.yaml"
        )

        val ast = FrontendCompilerComposition.intentPlanner(registry).plan(intent)
        val validation = FrontendCompilerComposition.flowValidator(registry).validate(ast)
        assertEquals(false, validation.valid)
        assertTrue(validation.issues.any { it.code == "DUPLICATE_RESULT" })
    }
}
