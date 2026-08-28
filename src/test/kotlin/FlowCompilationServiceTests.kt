package org.flowlang.tests

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.compiler.CanonicalExecutionGraph
import org.flowlang.compiler.CanonicalExecutionGraphDigestComputer
import org.flowlang.compiler.CanonicalExecutionGraphProjection
import org.flowlang.compiler.CompilationFrontend
import org.flowlang.compiler.CompilationSource
import org.flowlang.compiler.CompilationSourceCapture
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.requireAccepted
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.planner.FlowPlanner
import org.flowlang.validator.FlowValidator

class FlowCompilationServiceTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"))
    private val compiler = FlowCompilationService(registry)

    @Test
    fun flowSourceFrontendPreservesExistingAcceptedMeaningAndSourceLocations() {
        val sourceFile = File("examples/build-test.flow")
        val unit = FlowSourceFrontend(compiler).compile(sourceFile).requireAccepted()

        val legacyAst = FlowParser().parse(sourceFile)
        val legacyValidation = FlowValidator(registry).validate(legacyAst)
        val legacyPlan = FlowPlanner(registry).plan(legacyAst)

        assertEquals(CompilationFrontend.FLOW_SOURCE, unit.source.frontend)
        assertEquals(sourceFile.absoluteFile.toPath().normalize().toString(), unit.source.identity)
        assertEquals(sourceFile.path, unit.source.sourceName)
        assertEquals("text/x-flow", unit.source.mediaType)
        assertEquals(sourceFile.readBytes().size.toLong(), unit.source.byteCount)
        assertEquals(sourceFile.path, unit.ast.metadata.sourceFile)
        assertEquals(legacyAst, unit.ast)
        assertEquals(legacyValidation, unit.validation)
        assertEquals(legacyPlan, unit.executionPlan)
        assertEquals(ExecutionPlanCanonicalizer.canonicalize(legacyPlan), unit.canonicalPlan)
        assertEquals(
            unit.executionPlan,
            CanonicalExecutionGraphProjection.toExecutionPlan(unit.graph, unit.authorization.bindings)
        )
        assertEquals(unit.graphDigest, CanonicalExecutionGraphDigestComputer.digest(unit.graph))
        unit.authorization.requireIntegrity()
    }

    @Test
    fun sourceCaptureRejectsMalformedUtf8BeforeParserInvocation() {
        val directory = createTempDirectory("flow-source-utf8").toFile()
        val source = File(directory, "intent.yaml").apply {
            writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        }
        var parserCalled = false

        assertFailsWith<IllegalArgumentException> {
            CompilationSourceCapture.capture(source, CompilationFrontend.INTENT_YAML) { text, _ ->
                parserCalled = true
                text
            }
        }
        assertEquals(false, parserCalled)
    }

    @Test
    fun sourceCaptureParsesTheExactSnapshotBoundToItsDigest() {
        val directory = createTempDirectory("flow-source-capture").toFile()
        val source = File(directory, "intent.yaml").apply { writeText("name: before\n") }
        val original = source.readBytes()

        val captured = CompilationSourceCapture.capture(source, CompilationFrontend.INTENT_YAML) { text, _ ->
            source.writeText("name: after\n")
            text
        }

        assertEquals("name: before\n", captured.value)
        assertEquals(
            CompilationSource.fromBytes(
                frontend = CompilationFrontend.INTENT_YAML,
                identity = captured.source.identity,
                bytes = original,
                sourceName = source.path
            ),
            captured.source
        )
        assertEquals("name: after\n", source.readText())
    }

    @Test
    fun compilerBoundaryOwnsTypedGraphWithoutConcreteTargetDependencies() {
        val compilerDirectory = File("src/main/kotlin/org/flowlang/compiler")
        val sources = compilerDirectory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to it.readText() }

        assertTrue(sources.isNotEmpty())
        assertTrue(sources.values.any { source ->
            Regex("""\bdata\s+class\s+CanonicalExecutionGraph\b""").containsMatchIn(source)
        })
        assertEquals("CanonicalExecutionGraph", CanonicalExecutionGraph::class.simpleName)
        val forbiddenImports = listOf(
            "import org.flowlang.adapters.",
            "import org.flowlang.targets.",
            "import org.flowlang.generators.",
            "import org.flowlang.cli.",
            "import org.flowlang.conformance."
        )
        sources.forEach { (name, source) ->
            forbiddenImports.forEach { forbidden ->
                assertTrue(forbidden !in source, "$name must not contain '$forbidden'.")
            }
        }
    }
}
