import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.flowlang.ast.*
import org.flowlang.core.*
import org.flowlang.parser.FlowParser

class AvailabilityBudgetIntegrationTests {
    @Test fun neutralAuditInputsRetainOnePathAsSourceLengthIncreases() {
        for (count in listOf(4, 8, 12, 20, 100, 500)) {
            val analysis = FlowAvailabilityAnalyzer().analyze(FlowParser().parse(auditSource(count)))
            assertEquals(1, analysis.normalExitState.paths.size)
            assertTrue(analysis.issues.isEmpty())
        }
    }

    @Test fun everyPathExpandingConstructUsesTheSameBudget() {
        val condition = BooleanLiteralNode(value = true)
        val variants: List<(Int) -> StatementNode> = listOf(
            { i -> IfNode(condition = condition, then = listOf(definition(i))) },
            { i -> ForNode(item = "item", source = ListLiteralNode(items = emptyList()), body = listOf(definition(i))) },
            { i -> TryNode(steps = listOf(definition(i))) },
            { i -> MatchNode(source = condition, cases = listOf(WhenNode(condition = condition, steps = listOf(definition(i))))) },
            { _ -> ParallelNode(branches = listOf(ParallelBranchNode(name = "a"), ParallelBranchNode(name = "b"))) }
        )
        for (variant in variants) {
            val ast = FlowDocument(flow = FlowNode(name = "growth", steps = List(20, variant)))
            assertFailsWith<FlowAvailabilityLimitException> { FlowAvailabilityAnalyzer().analyze(ast) }
        }
    }

    private fun definition(index: Int) = SetNode(name = "value$index", value = BooleanLiteralNode(value = true))

    @Test fun original557ByteReproducerCompletesInA256MiBChildJvm() {
        val source = auditSource(20)
        assertEquals(557, source.toByteArray().size)
        assertEquals("41fc2b8eaa263269e7a23c0b9b5b571bb3d42d2b702a624a461fdc1225385de6",
            MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) })
        val output = File.createTempFile("availability-budget-child-", ".log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx256m", "-cp", System.getProperty("flow.conformance.test.classpath"),
            AvailabilityBudgetChildProbe::class.java.name
        ).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Bounded analysis did not terminate: ${output.readText()}")
            assertEquals(0, process.exitValue(), output.readText())
            assertTrue(output.readText().contains("AUDIT_ANALYSIS_PATHS=1"), output.readText())
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor() }
            output.delete()
        }
    }
}

object AvailabilityBudgetChildProbe {
    @JvmStatic fun main(args: Array<String>) {
        val ast = FlowParser().parse(auditSource(20), "audit-paths.flow")
        val analysis = FlowAvailabilityAnalyzer().analyze(ast)
        check(analysis.issues.isEmpty())
        check(analysis.normalExitState.paths.size == 1)
        println("AUDIT_ANALYSIS_PATHS=${analysis.normalExitState.paths.size}")
    }
}

private fun auditSource(count: Int): String =
    "version \"1.0\"\nflow \"audit\" {\ninput { enabled: boolean required }\nsteps {\n" +
        (1..count).joinToString("\n") { "if enabled { } else { }" } + "\n}\n}\n"
