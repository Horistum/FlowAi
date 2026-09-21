package org.flowlang.conformance

import org.flowlang.serialization.*
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.modules.CanonicalModuleLoader

/** Independent calls through public entry points with accepted baselines and rejected mutants. */
internal class StrictContractConformanceChecks {
    data class Probe(val name: String = "accepted", val enabled: Boolean = true)
    fun checks(): List<ConformanceCheck> = listOf(
        check(STRICT) {
            val readers: List<(String) -> Probe> = listOf(
                { FlowYaml.read(it, Probe::class.java) }, { FlowYaml.readStrict(it, Probe::class.java) },
                { FlowJson.read(it, Probe::class.java) })
            readers.all { read ->
                read("""{"name":"accepted","enabled":false}""") == Probe("accepted", false) &&
                    listOf("""{"unknown":true}""", """{"enabled":"false"}""", """{"enabled":null}""",
                        """{"name":"a","name":"b"}""", "{} {}", "null").all { runCatching { read(it) }.isFailure }
            }
        },
        check(MAPS) {
            val valid = """{"a":{"id":1},"b":{"id":2}}"""
            FlowYaml.readMap(valid).size == 2 && FlowJson.readTree(valid).size() == 2 &&
                listOf("""{"a":1,"a":2}""", """{"a":{"id":1,"id":2}}""", "{} {}").all { text ->
                    runCatching { FlowYaml.readMap(text) }.isFailure && runCatching { FlowJson.readTree(text) }.isFailure
                } && runCatching { FlowYaml.readMap("{}\n---\n{}") }.isFailure
        },
        check(BOUNDS) {
            val valid = "{\"value\":[[1]]}"
            val deep = "{\"value\":" + "[".repeat(ContractReadLimits.MAX_DEPTH + 1) + "1" +
                "]".repeat(ContractReadLimits.MAX_DEPTH + 1) + "}"
            FlowYaml.readMap(valid).isNotEmpty() && FlowJson.readTree(valid).size() == 1 &&
                runCatching { FlowYaml.readMap(deep) }.isFailure &&
                runCatching { FlowJson.readTree(deep) }.isFailure &&
                runCatching { FlowYaml.readMap("first: &x safe\nsecond: *x") }.isFailure &&
                FlowYaml.readMap("value: '*literal'")["value"] == "*literal"
        },
        check(VOCABULARY) {
            val intent = "name: strict-probe\nworkflows: []"
            val module = "kind: FlowModule\nname: strict-probe\nversion: \"1.0\"\ndescription: strict probe\nsystemTypes: {probe: {input: {}}}\nactions: {inspect: {kind: action, targetTypes: [probe], input: {}, output: {}}}"
            IntentYamlLoader.loadText(intent).name == "strict-probe" &&
                CanonicalModuleLoader.loadText(module).name == "strict-probe" &&
                runCatching { IntentYamlLoader.loadText(intent + "\nunknown: true") }.isFailure &&
                runCatching { CanonicalModuleLoader.loadText(module + "\nunknown: true") }.isFailure
        }
    )

    private fun check(name: String, probe: () -> Boolean): ConformanceCheck = runCatching(probe).fold(
        { passed -> ConformanceCheck(name, passed, if (passed) null else "Strict contract invariant was violated.") },
        { error -> ConformanceCheck(name, false, error.message ?: error.javaClass.simpleName) }
    )

    companion object {
        const val STRICT = "architecture-recovery.language.strict-typed-contracts"
        const val MAPS = "architecture-recovery.language.single-document-duplicate-rejection"
        const val BOUNDS = "architecture-recovery.language.bounded-contract-parsing"
        const val VOCABULARY = "architecture-recovery.language.contract-vocabulary-preservation"
    }
}
