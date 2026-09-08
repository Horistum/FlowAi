package org.flowlang.testing

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/** A separate compiler with the module's production classpath, never the test runtime. */
object ExternalCompilerProbe {
    fun accepts(source: String) { compile(source, expectedExit = 0) }

    fun rejects(source: String, diagnostic: String) {
        val log = compile(source, expectedExit = 1)
        check(log.contains(diagnostic, ignoreCase = true)) { "Expected '$diagnostic' diagnostic:\n$log" }
        check("Exception in thread" !in log) { "A crashed compiler is not a negative visibility proof:\n$log" }
    }

    private fun compile(source: String, expectedExit: Int): String {
        val directory = createTempDirectory("module-compiler-probe-").toFile()
        try {
            val input = File(directory, "Probe.kt").apply { writeText(source) }
            val output = File(directory, "classes")
            val log = File(directory, "compiler.log")
            val process = ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-Xmx384m", "-cp", property("flow.module.compilerClasspath"),
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "25",
                "-classpath", property("flow.module.classpath"), "-d", output.absolutePath, input.absolutePath
            ).redirectErrorStream(true).redirectOutput(log).start()
            try {
                check(process.waitFor(60, TimeUnit.SECONDS)) { "External compiler timed out." }
                val text = log.readText()
                check(process.exitValue() == expectedExit) { "Expected compiler exit $expectedExit, got ${process.exitValue()}:\n$text" }
                if (expectedExit == 0) check(output.walkTopDown().any { it.isFile && it.extension == "class" }) {
                    "A successful probe must produce a class file."
                }
                return text
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    check(process.waitFor(10, TimeUnit.SECONDS)) { "External compiler did not terminate." }
                }
            }
        } finally {
            check(directory.deleteRecursively()) { "Cannot remove external compiler workspace." }
        }
    }

    private fun property(name: String): String =
        requireNotNull(System.getProperty(name)?.takeIf(String::isNotBlank)) { "Missing compiler input: $name" }
}
