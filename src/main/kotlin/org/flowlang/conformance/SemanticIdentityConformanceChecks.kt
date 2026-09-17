package org.flowlang.conformance

import org.flowlang.ast.*
import org.flowlang.core.FlowAvailabilityAnalyzer
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.generators.manifest.TargetProjectionIdentityChecks
import org.flowlang.identity.*
import org.flowlang.intent.*
import org.flowlang.modules.ModuleRegistry

/** Executable positive and negative probes, not counts of declarations or naming conventions. */
internal class SemanticIdentityConformanceChecks {
    fun checks(): List<ConformanceCheck> = listOf(
        check(WIRE) {
            val names = listOf("audit-step", "audit_step", "a/b", "a~1b", "~", "東京", "é", "e\u0301")
            val ids = names.map(SemanticId::of)
            ids.map { it.wire }.toSet().size == names.size &&
                ids.all { SemanticId.fromWire(it.wire) == it } &&
                listOf("a/b", "~", "~2").all { runCatching { SemanticId.fromWire(it) }.isFailure }
        },
        check(EARLY_REJECTION) {
            val registry = ModuleRegistry()
            fun step(id: String) = IntentStep(id, StandardCapability.CUSTOM,
                params = mapOf("operation" to IntentString("inspect")))
            fun intent(ids: List<String>) = IntentDocument(name = "identity-probe", workflows = listOf(
                IntentWorkflow("main", IntentWorkflowKind.CUSTOM, ids.map(::step))))
            val invalid = intent(listOf("audit-step", "audit_step"))
            val valid = intent(listOf("audit-step", "other_step"))
            val report = IntentCapabilityValidator(registry).validate(invalid)
            !report.valid && report.issues.any { it.code == "INTENT_SYMBOL_COLLISION" } && report.bindings.isEmpty() &&
                report.meaning.workflows.single().steps.map { it.id } == listOf("audit-step", "audit_step") &&
                runCatching { FrontendCompilerComposition.intentPlanner(registry).plan(invalid) }.isFailure &&
                FrontendCompilerComposition.intentPlanner(registry).plan(valid).flow.steps.size == 2
        },
        check(EXACT_REFERENCES) {
            val source = FlowDocument(flow = FlowNode(name = "identity-probe", steps = listOf(
                SetNode(name = "audit-step", value = StringLiteralNode(value = "left")),
                SetNode(name = "audit_step", value = StringLiteralNode(value = "right")),
                SetNode(name = "left", value = ReferenceNode(path = listOf("audit-step"))),
                SetNode(name = "right", value = ReferenceNode(path = listOf("audit_step"))))))
            val analysis = FlowAvailabilityAnalyzer().analyze(source)
            val reads = analysis.uses.associateBy { it.binding }
            val missing = source.copy(flow = source.flow.copy(steps = source.flow.steps.drop(1)))
            analysis.issues.isEmpty() &&
                reads.getValue("audit-step").state.uniqueProducer != reads.getValue("audit_step").state.uniqueProducer &&
                FlowAvailabilityAnalyzer().analyze(missing).issues.any { it.binding == "audit-step" && it.code == "UNRESOLVED_REFERENCE" }
        },
        check(PROJECTION_COLLISIONS) {
            val collision = listOf("Audit", "audit")
            val rejected = listOf(collision, collision.reversed()).all { names ->
                (runCatching { TargetProjectionIdentityChecks.requireNames("probe", names, String::lowercase) }
                    .exceptionOrNull() as? IdentityCollisionException)?.code == "DERIVED_ID_COLLISION"
            }
            TargetProjectionIdentityChecks.requireNames("probe", listOf("Audit", "Other"), String::lowercase)
            rejected
        }
    )

    private fun check(name: String, probe: () -> Boolean): ConformanceCheck = runCatching(probe).fold(
        { passed -> ConformanceCheck(name, passed, if (passed) null else "Semantic identity probe violated its invariant.") },
        { failure -> ConformanceCheck(name, false, failure.message ?: failure.javaClass.simpleName) }
    )
    companion object {
        const val WIRE = "architecture-recovery.language.lossless-identity-wire"
        const val EARLY_REJECTION = "architecture-recovery.language.early-identity-collision-rejection"
        const val EXACT_REFERENCES = "architecture-recovery.language.exact-producer-identity"
        const val PROJECTION_COLLISIONS = "architecture-recovery.language.derived-name-collision-rejection"
    }
}
