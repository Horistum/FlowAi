package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.compiler.CanonicalExecutionGraph
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.conformance.ConformanceRunner
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.ModuleCatalog

/** The distribution consumes actual compiled modules rather than recompiling their sources. */
class CompilerModuleCompositionTests {
    private val ownedClasses = listOf(CanonicalExecutionGraph::class.java, ModuleCatalog::class.java,
        FlowCompilationService::class.java, FlowSourceFrontend::class.java)

    @Test fun kernelContractsCompilerAndFrontendsHaveDistinctProductionOutputs() {
        val outputs = ownedClasses.map { it.protectionDomain.codeSource.location }
        assertEquals(4, outputs.toSet().size, outputs.toString())
        assertFalse(ConformanceRunner::class.java.protectionDomain.codeSource.location in outputs)
    }

    @Test fun everyOwnedClassHasExactlyOneRuntimeDefinition() {
        ownedClasses.forEach { type ->
            val resource = type.name.replace('.', '/') + ".class"
            val definitions = type.classLoader.getResources(resource).toList()
            assertEquals(1, definitions.size, "$resource: $definitions")
        }
    }

    @Test fun compilerServiceDoesNotExposeAnImplicitLoadingConstructor() {
        val constructors = FlowCompilationService::class.java.constructors
        assertTrue(constructors.isNotEmpty())
        assertTrue(constructors.all { constructor ->
            constructor.parameterTypes.toList() == listOf(ModuleCatalog::class.java,
                org.flowlang.safety.EnvironmentSafetyPolicy::class.java,
                org.flowlang.lowering.IntentExpressionParser::class.java)
        }, constructors.joinToString())
    }
}
