package org.flowlang.frontend

import org.flowlang.compiler.FlowCompilationService
import org.flowlang.frontend.intent.FlowIntentExpressionParser
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleCatalog
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.EnvironmentSafetyPolicy
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.validator.FlowValidator
import org.flowlang.validator.SafetyBoundaryValidator

/** Explicit composition of decoded defaults and syntax services outside semantic compilation. */
object FrontendCompilerComposition {
    fun compiler(registry: ModuleCatalog = ModuleRegistry()): FlowCompilationService =
        FlowCompilationService(registry, StandardEnvironmentSafetyPolicyNotes.policy(), FlowIntentExpressionParser)

    fun intentPlanner(registry: ModuleCatalog = ModuleRegistry()): IntentToAstPlanner =
        IntentToAstPlanner(registry, FlowIntentExpressionParser)

    fun flowValidator(
        registry: ModuleCatalog = ModuleRegistry(),
        environmentPolicy: EnvironmentSafetyPolicy = StandardEnvironmentSafetyPolicyNotes.policy()
    ): FlowValidator = FlowValidator(registry, environmentPolicy)

    fun safetyValidator(
        registry: ModuleCatalog = ModuleRegistry(),
        environmentPolicy: EnvironmentSafetyPolicy = StandardEnvironmentSafetyPolicyNotes.policy()
    ): SafetyBoundaryValidator = SafetyBoundaryValidator(registry, environmentPolicy)
}
