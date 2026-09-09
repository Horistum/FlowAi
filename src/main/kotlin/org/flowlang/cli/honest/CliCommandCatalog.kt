package org.flowlang.cli.honest

/** A statically supplied command, not a plugin lifecycle or a source of authorization. */
fun interface CliCommandHandler {
    fun execute(arguments: List<String>, output: CliOutput): CliExecutionResult
}

/**
 * Immutable command composition. Providers are supplied by the application host;
 * the product never discovers or imports conformance or release tooling.
 */
class CliCommandCatalog private constructor(private val handlers: Map<String, CliCommandHandler>) {
    val names: Set<String> get() = handlers.keys.toSet()

    internal fun requireDisjoint(productCommands: Set<String>) {
        require(handlers.keys.intersect(productCommands).isEmpty()) {
            "Additional CLI commands cannot replace product commands."
        }
    }

    internal fun execute(command: String, arguments: List<String>, output: CliOutput): CliExecutionResult =
        requireNotNull(handlers[command]) { "No command implementation for '$command'." }
            .execute(arguments.toList(), output)

    companion object {
        fun empty(): CliCommandCatalog = CliCommandCatalog(emptyMap())

        fun of(vararg entries: Pair<String, CliCommandHandler>): CliCommandCatalog {
            val names = entries.map { it.first }
            require(names.all { it.matches(Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")) }) {
                "CLI command names must be non-blank lowercase command identifiers."
            }
            require(names.size == names.toSet().size) { "Duplicate CLI command implementations are forbidden." }
            return CliCommandCatalog(entries.toMap())
        }
    }
}
