package org.flowlang.cli.honest

import kotlin.test.*

class CliArgumentParserTests {
    private val schema = CliArgumentSchema(setOf(CliValueOption.OUT, CliValueOption.TARGET),
        setOf(CliFlagOption.STRICT), minPositionals = 1, maxPositionals = 1)

    @Test fun valuesAreConsumedBeforePositionalsInEveryOrderAndSyntax() {
        for (out in listOf(listOf("--out", "result dir"), listOf("--out=result dir"))) {
            for (target in listOf(listOf("--target", "jenkins"), listOf("--target=jenkins"))) {
                val groups = listOf(out, target, listOf("source.intent.yaml"), listOf("--strict"))
                fun permutations(parts: List<List<String>>): List<List<String>> = if (parts.isEmpty()) listOf(emptyList())
                    else parts.indices.flatMap { i -> permutations(parts.filterIndexed { j, _ -> i != j }).map { parts[i] + it } }
                for (args in permutations(groups)) {
                    val parsed = schema.parse("intent", args)
                    assertEquals(listOf("source.intent.yaml"), parsed.positionals)
                    assertEquals("result dir", parsed.value(CliValueOption.OUT))
                    assertEquals("jenkins", parsed.value(CliValueOption.TARGET))
                    assertTrue(parsed.has(CliFlagOption.STRICT))
                }
            }
        }
    }

    @Test fun delimiterAndEqualsPreserveLiteralInput() {
        val parsed = schema.parse("intent", listOf("--out=a=b", "--", "--source.yaml"))
        assertEquals("a=b", parsed.value(CliValueOption.OUT))
        assertEquals(listOf("--source.yaml"), parsed.positionals)
        assertEquals("--folder", schema.parse("intent", listOf("--out=--folder", "source")).value(CliValueOption.OUT))
        assertEquals(listOf("--strict"), schema.parse("intent", listOf("--", "--strict")).positionals)
        assertFalse(schema.parse("intent", listOf("--", "--strict")).has(CliFlagOption.STRICT))
    }

    @Test fun malformedOptionsAreRejectedWithoutGuessing() {
        val invalid = listOf(
            listOf("--out"), listOf("--out="), listOf("--out", " "), listOf("--out", "--strict", "source"),
            listOf("--unknown", "source"), listOf("-x", "source"), listOf("--strict=false", "source"),
            listOf("--out=a", "--out", "b", "source"), listOf("--out", "a", "--out=b", "source"),
            listOf("--strict", "--strict", "source"), listOf("first", "second"), listOf(""), emptyList()
        )
        for (args in invalid) assertFailsWith<IllegalArgumentException>(args.toString()) { schema.parse("intent", args) }
    }

    @Test fun commandSchemasRejectUnsupportedFlagsConflictingSourcesAndModes() {
        val invalid = listOf(
            "flow" to listOf("--out", "dir", "file"), "targets" to listOf("source"),
            "catalog" to listOf("--examples"), "scenario" to emptyList(),
            "standard-verify" to listOf("--bundle", "dir", "other"),
            "normalize" to listOf("text", "--file", "input"),
            "normalize" to listOf("--strict", "--repair", "text"),
            "normalize" to listOf("--explain", "--repair", "text"),
            "normalize" to listOf("--strict", "--explain", "text"),
            "normalize" to listOf("--out", "dir")
        )
        for ((command, args) in invalid) assertFailsWith<IllegalArgumentException>("$command $args") {
            ProductCliArguments.parse(command, args)
        }
        val parsed = ProductCliArguments.parse("normalize", listOf("--app", "shop", "build", "--explain", "and", "test"))
        assertEquals(listOf("build", "and", "test"), parsed.positionals)
        assertEquals("shop", parsed.value(CliValueOption.APP))
    }

    @Test fun inputAndSchemaMutationsCannotChangeAParsedInvocation() {
        val allowed = mutableSetOf(CliValueOption.OUT)
        val local = CliArgumentSchema(allowed, maxPositionals = 1)
        allowed.clear()
        val source = mutableListOf("--out", "directory", "file")
        val parsed = local.parse("intent", source)
        source.clear()
        assertEquals("directory", parsed.value(CliValueOption.OUT))
        assertEquals(listOf("file"), parsed.positionals)
        assertFailsWith<UnsupportedOperationException> { (parsed.positionals as MutableList<String>).clear() }
    }

    @Test fun customCompositionStillReceivesItsOriginalArguments() {
        val arguments = listOf("--custom", "value", "--", "literal")
        var observed: List<String>? = null
        val catalog = CliCommandCatalog.of("custom" to CliCommandHandler { args, output ->
            observed = args
            CliExecutionResult.Completed(output.snapshot())
        })
        assertIs<CliExecutionResult.Completed>(executeCli((listOf("custom") + arguments).toTypedArray(), catalog))
        assertEquals(arguments, observed)
    }
}
