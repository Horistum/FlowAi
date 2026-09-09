package org.flowlang.frontend

import kotlin.test.Test
import org.flowlang.testing.ExternalCompilerProbe

class FrontendBoundaryTests {
    @kotlin.test.Test
    fun compatibilityAliasResourceBelongsToTheFrontendArtifact() {
        val resources = java.util.Collections.list(javaClass.classLoader.getResources(
            "standard/compatibility/capability-aliases.yaml"
        ))
        kotlin.test.assertEquals(1, resources.size, "Exactly one production artifact must own the frontend aliases")
        kotlin.test.assertTrue(resources.single().toString().contains("flow-frontends"), resources.toString())
        kotlin.test.assertTrue(resources.single().readText().isNotBlank())
    }

    @Test fun independentConsumerCanUseFrontendComposition() {
        ExternalCompilerProbe.accepts("""
            package independent.consumer
            import org.flowlang.frontend.FrontendCompilerComposition
            import org.flowlang.frontend.source.FlowSourceFrontend
            import java.io.File
            fun compile(file: File) = FlowSourceFrontend(FrontendCompilerComposition.compiler()).compile(file)
        """.trimIndent())
    }

    @Test fun samePackageConsumerCannotInventCapturedSource() {
        ExternalCompilerProbe.rejects("""
            package org.flowlang.frontend
            import org.flowlang.compiler.CompilationSource
            fun forge(source: CompilationSource) = CapturedCompilationSource(source, "not parsed")
        """.trimIndent(), "internal")
    }

    @Test fun frontendDoesNotGrantCompilerAuthorizationFactories() {
        ExternalCompilerProbe.rejects("""
            package org.flowlang.compiler
            fun forge(value: CompilationAuthorization) = CompilationAuthorization(
                value.graph, value.graphDigest, value.inspectionView().bindings,
                value.validationBinding, value.workflowPlanSet)
        """.trimIndent(), "internal")
    }

    @Test fun frontendCannotLoadConcreteTargetsOrConformance() {
        ExternalCompilerProbe.rejects("val forbidden = org.flowlang.targets.builtin.BuiltInTargetProjections", "targets")
        ExternalCompilerProbe.rejects("val forbidden = org.flowlang.conformance.ConformanceRunner", "conformance")
        ExternalCompilerProbe.rejects("val forbidden = org.flowlang.compiler.testing.CompilerTestFixtures", "testing")
        ExternalCompilerProbe.rejects("val forbidden = org.flowlang.frontend.testing.FrontendTestFixtures", "testing")
    }
}
