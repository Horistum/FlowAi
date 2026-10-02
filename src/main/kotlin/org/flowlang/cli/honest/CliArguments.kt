package org.flowlang.cli.honest

import java.util.Collections

/** Value and flag tokens have different types; command hosts explicitly declare their syntax. */
enum class CliValueOption(val token: String) {
    OUT("--out"), TARGET("--target"), FILE("--file"), APP("--app"),
    ENVIRONMENT("--environment"), REPO("--repo"), CHANNEL("--channel"),
    BUNDLE("--bundle"), INTENT("--intent"), SCENARIO_ID("--scenario-id"), TARGETS("--targets")
}

enum class CliFlagOption(val token: String) {
    STRICT("--strict"), FAIL_ON_UNSUPPORTED("--fail-on-unsupported"), RENDER("--render"),
    LOWER("--lower"), PIPELINE("--pipeline"), EXPLAIN("--explain"), REPAIR("--repair"),
    MARKDOWN("--markdown"), EXAMPLES("--examples")
}

/** A detached parse result. Raw argv is never searched again by command implementations. */
class CliArguments private constructor(
    values: Map<CliValueOption, String>, flags: Set<CliFlagOption>, positionals: List<String>
) {
    private val values = Collections.unmodifiableMap(values.toMap())
    private val flags = Collections.unmodifiableSet(flags.toSet())
    val positionals: List<String> = Collections.unmodifiableList(positionals.toList())
    fun value(option: CliValueOption): String? = values[option]
    fun has(option: CliFlagOption): Boolean = option in flags
    fun source(option: CliValueOption): String? = value(option) ?: positionals.singleOrNull()

    companion object {
        internal fun parsed(values: Map<CliValueOption, String>, flags: Set<CliFlagOption>, positionals: List<String>) =
            CliArguments(values, flags, positionals)
    }
}

/** Tokenize and validate the complete invocation before any command reads or writes files. */
class CliArgumentSchema(
    values: Set<CliValueOption> = emptySet(),
    flags: Set<CliFlagOption> = emptySet(),
    private val minPositionals: Int = 0,
    private val maxPositionals: Int = 0,
    private val positionalAlternative: CliValueOption? = null,
    private val requireSource: Boolean = false,
    exclusiveFlags: Set<CliFlagOption> = emptySet()
) {
    private val values = values.associateBy { it.token }
    private val flags = flags.associateBy { it.token }
    private val exclusiveFlags = exclusiveFlags.toSet()

    init {
        require(minPositionals >= 0 && maxPositionals >= minPositionals)
        require(positionalAlternative == null || positionalAlternative.token in this.values)
        require(!requireSource || positionalAlternative != null || minPositionals > 0)
        require(exclusiveFlags.all { it.token in this.flags })
    }

    fun parse(command: String, arguments: List<String>): CliArguments {
        val values = linkedMapOf<CliValueOption, String>()
        val flags = linkedSetOf<CliFlagOption>()
        val positionals = mutableListOf<String>()
        var index = 0
        var positionalOnly = false
        while (index < arguments.size) {
            val token = arguments[index++]
            if (!positionalOnly && token == "--") {
                positionalOnly = true
                continue
            }
            if (!positionalOnly && token.startsWith("-")) {
                val name = token.substringBefore('=')
                val valueOption = this.values[name]
                val flagOption = this.flags[name]
                require(valueOption != null || flagOption != null) { "$command: unknown option '$name'." }
                if (flagOption != null) {
                    require('=' !in token) { "$command: $name is a flag and does not accept a value." }
                    require(flags.add(flagOption)) { "$command: duplicate option $name." }
                } else {
                    val option = requireNotNull(valueOption)
                    require(option !in values) { "$command: duplicate option $name." }
                    val value = if ('=' in token) token.substringAfter('=') else {
                        require(index < arguments.size && !arguments[index].startsWith("--")) {
                            "$command: $name requires a value. Use $name=<value> for values starting with --."
                        }
                        arguments[index++]
                    }
                    require(value.isNotBlank()) { "$command: $name requires a non-blank value." }
                    values[option] = value
                }
            } else {
                require(token.isNotBlank()) { "$command: positional arguments must not be blank." }
                positionals += token
            }
        }
        require(positionals.size in minPositionals..maxPositionals) {
            "$command: expected $minPositionals..$maxPositionals positional arguments, received ${positionals.size}."
        }
        require(flags.count { it in exclusiveFlags } <= 1) {
            "$command: ${exclusiveFlags.joinToString { it.token }} are mutually exclusive."
        }
        require(positionalAlternative == null || positionalAlternative !in values || positionals.isEmpty()) {
            "$command: use either ${positionalAlternative?.token} or positional input, not both."
        }
        require(!requireSource || positionals.isNotEmpty() || (positionalAlternative != null && positionalAlternative in values)) {
            "$command requires ${positionalAlternative?.token} or a positional source."
        }
        return CliArguments.parsed(values, flags, positionals)
    }
}

internal object ProductCliArguments {
    private val targetFlags = setOf(CliFlagOption.STRICT, CliFlagOption.FAIL_ON_UNSUPPORTED, CliFlagOption.RENDER)
    private val schemas = mapOf(
        "intent" to CliArgumentSchema(setOf(CliValueOption.OUT, CliValueOption.TARGET), targetFlags, maxPositionals = 1),
        "normalize" to CliArgumentSchema(
            setOf(CliValueOption.OUT, CliValueOption.TARGET, CliValueOption.FILE, CliValueOption.APP,
                CliValueOption.ENVIRONMENT, CliValueOption.REPO, CliValueOption.CHANNEL),
            targetFlags + setOf(CliFlagOption.LOWER, CliFlagOption.PIPELINE, CliFlagOption.EXPLAIN, CliFlagOption.REPAIR),
            maxPositionals = Int.MAX_VALUE, positionalAlternative = CliValueOption.FILE, requireSource = true,
            exclusiveFlags = setOf(CliFlagOption.STRICT, CliFlagOption.EXPLAIN, CliFlagOption.REPAIR)),
        "diagnostics" to CliArgumentSchema(setOf(CliValueOption.OUT)),
        "standard-verify" to CliArgumentSchema(setOf(CliValueOption.OUT, CliValueOption.BUNDLE),
            maxPositionals = 1, positionalAlternative = CliValueOption.BUNDLE, requireSource = true),
        "flow" to CliArgumentSchema(minPositionals = 1, maxPositionals = 1),
        "catalog" to CliArgumentSchema(flags = setOf(CliFlagOption.MARKDOWN)),
        "targets" to CliArgumentSchema(),
        "modules" to CliArgumentSchema(),
        "scenarios" to CliArgumentSchema(flags = setOf(CliFlagOption.MARKDOWN)),
        "scenario" to CliArgumentSchema(flags = setOf(CliFlagOption.EXAMPLES), minPositionals = 1, maxPositionals = 1)
    )
    fun parse(command: String, arguments: List<String>): CliArguments = schemas.getValue(command).parse(command, arguments)
}
