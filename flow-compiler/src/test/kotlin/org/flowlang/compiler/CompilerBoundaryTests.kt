package org.flowlang.compiler

import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.flowlang.testing.ExternalCompilerProbe

class CompilerBoundaryTests {
    @Test fun independentConsumerCanCompileExplicitNeutralInputs() {
        ExternalCompilerProbe.accepts("""
            package independent.consumer
            import org.flowlang.compiler.*
            import org.flowlang.modules.ModuleCatalog
            import org.flowlang.safety.EnvironmentSafetyPolicy
            import org.flowlang.lowering.IntentExpressionParser
            fun compile(modules: ModuleCatalog, policy: EnvironmentSafetyPolicy,
                        syntax: IntentExpressionParser, input: CompilationInput): CompilationResult =
                FlowCompilationService(modules, policy, syntax).compile(input)
        """.trimIndent())
    }

    @Test fun parserAndSerializationAreNotTransitiveCompilerDependencies() {
        ExternalCompilerProbe.rejects("package org.flowlang.parser\nval forbidden = ExpressionParser", "ExpressionParser")
        ExternalCompilerProbe.rejects("val forbidden = com.fasterxml.jackson.databind.ObjectMapper()", "fasterxml")
    }

    @Test fun loadingRegistryAndFrontendCompositionAreUnavailable() {
        ExternalCompilerProbe.rejects("package org.flowlang.modules\nval forbidden = ModuleRegistry()", "ModuleRegistry")
        ExternalCompilerProbe.rejects(
            "val forbidden = org.flowlang.frontend.FrontendCompilerComposition.compiler()", "frontend")
    }

    @Test fun concreteAdaptersAndConformanceAreUnavailableEvenThroughAliases() {
        ExternalCompilerProbe.rejects(
            "val forbidden = org.flowlang.targets.builtin.BuiltInTargetProjections.registry", "targets")
        ExternalCompilerProbe.rejects(
            "import org.flowlang.conformance.ConformanceRunner as Runner\nval forbidden: Runner? = null", "conformance")
    }

    @Test fun samePackageCannotConstructAnAuthorization() {
        ExternalCompilerProbe.rejects("""
            package org.flowlang.compiler
            fun forge(value: CompilationAuthorization) = CompilationAuthorization(
                value.graph, value.graphDigest, value.inspectionView().bindings,
                value.validationBinding, value.workflowPlanSet
            )
        """.trimIndent(), "internal")
    }

    @Test fun guardedTaskViewsCannotBeCopiedIntoForgedViews() {
        ExternalCompilerProbe.rejects("""
            package org.flowlang.compiler
            fun forge(value: AuthorizedCanonicalTask) = value.copy(node = value.node)
        """.trimIndent(), "copy")
        ExternalCompilerProbe.rejects("""
            package org.flowlang.compiler
            fun forge(value: AuthorizedWorkflowFailureProjection) = value.copy(policy = value.policy)
        """.trimIndent(), "copy")
    }

    @Test fun testFixturesAreNotAvailableOnTheProductionClasspath() {
        ExternalCompilerProbe.rejects(
            "val forbidden = org.flowlang.compiler.testing.CompilerTestFixtures", "testing")
    }

    @Test fun compilerRuntimeCannotLoadResidualImplementationClasses() {
        listOf("org.flowlang.parser.FlowParser", "org.flowlang.modules.ModuleRegistry",
            "org.flowlang.frontend.FrontendCompilerComposition", "org.flowlang.targets.builtin.BuiltInTargetProjections",
            "org.flowlang.conformance.ConformanceRunner", "com.fasterxml.jackson.databind.ObjectMapper").forEach { name ->
            assertFailsWith<ClassNotFoundException>(name) { Class.forName(name) }
        }
    }
}
