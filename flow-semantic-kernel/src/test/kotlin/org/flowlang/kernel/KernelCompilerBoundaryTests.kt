package org.flowlang.kernel

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Uses a new compiler process with only the real kernel production classpath. */
class KernelCompilerBoundaryTests {
    @Test
    fun publicGraphAndDigestCompileFromAnUnrelatedModule() {
        compileProbe(
            """
            package independent.consumer
            import org.flowlang.compiler.CanonicalExecutionGraph
            import org.flowlang.compiler.CanonicalExecutionGraphDigestComputer
            fun identity(graph: CanonicalExecutionGraph): String =
                CanonicalExecutionGraphDigestComputer.digest(graph).value
            """.trimIndent(), expectedExit = 0
        )
    }

    @Test
    fun samePackageDoesNotExposeTheResidualCompiler() {
        rejected("package org.flowlang.compiler\nval forbidden: FlowCompilationService? = null", "FlowCompilationService")
    }

    @Test
    fun fullyQualifiedConcreteAdapterIsUnavailable() {
        rejected("val forbidden = org.flowlang.targets.builtin.BuiltInTargetProjections.registry", "targets")
    }

    @Test
    fun aliasCannotImportProductConformance() {
        rejected("import org.flowlang.conformance.ConformanceRunner as Runner\nval forbidden: Runner? = null", "conformance")
    }

    @Test
    fun serializationImplementationIsNotOnTheKernelClasspath() {
        rejected("val forbidden = com.fasterxml.jackson.databind.ObjectMapper()", "fasterxml")
    }

    @Test
    fun digestByteFactoryRemainsInternalAcrossTheModuleBoundary() {
        rejected(
            """
            package org.flowlang.compiler
            val forged = CanonicalExecutionGraphDigest.fromCanonicalBytes(byteArrayOf(1))
            """.trimIndent(), "fromCanonicalBytes"
        )
    }

    @Test
    fun externalModuleCannotExtendTheSealedGraphContract() {
        rejected(
            "package org.flowlang.compiler\ninterface UnownedNode : CanonicalExecutionNode",
            "sealed"
        )
    }

    @Test
    fun kernelRuntimeCannotLoadImplementationClasses() {
        listOf(
            "org.flowlang.compiler.FlowCompilationService",
            "org.flowlang.targets.builtin.BuiltInTargetProjections",
            "org.flowlang.conformance.ConformanceRunner",
            "com.fasterxml.jackson.databind.ObjectMapper"
        ).forEach { name ->
            assertTrue(runCatching { Class.forName(name) }.exceptionOrNull() is ClassNotFoundException, name)
        }
    }

    private fun rejected(source: String, diagnostic: String) {
        val output = compileProbe(source, expectedExit = 1)
        assertTrue(output.contains(diagnostic, ignoreCase = true), output)
        assertFalse(output.contains("Exception in thread"), output)
    }

    private fun compileProbe(source: String, expectedExit: Int): String {
        val root = createTempDirectory("kernel-compiler-boundary-").toFile()
        try {
            val input = File(root, "Probe.kt").apply { writeText(source) }
            val output = File(root, "classes")
            val log = File(root, "compiler.log")
            val java = File(System.getProperty("java.home"), "bin/java")
            val process = ProcessBuilder(
                java.absolutePath, "-Xmx384m", "-cp", property("flow.kernel.compilerClasspath"),
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib", "-no-reflect", "-jvm-target", "25",
                "-classpath", property("flow.kernel.classpath"),
                "-d", output.absolutePath, input.absolutePath
            ).redirectErrorStream(true).redirectOutput(log).start()
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Compiler probe timed out: $source")
                val diagnostics = log.readText()
                assertEquals(expectedExit, process.exitValue(), diagnostics)
                if (expectedExit == 0) {
                    assertTrue(output.walkTopDown().any { it.isFile && it.extension == "class" }, diagnostics)
                }
                return diagnostics
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    check(process.waitFor(10, TimeUnit.SECONDS)) { "Compiler probe did not stop." }
                }
            }
        } finally {
            check(root.deleteRecursively()) { "Cannot remove compiler probe workspace." }
        }
    }

    private fun property(name: String): String =
        requireNotNull(System.getProperty(name)?.takeIf(String::isNotBlank)) { "Missing compiler input: $name" }
}
