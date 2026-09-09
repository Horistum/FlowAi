package org.flowlang.adapters.contract

/**
 * Target-neutral catalog boundary used by generic orchestration.
 *
 * The catalog deliberately knows nothing about Jenkins, GitHub Actions, Tekton
 * or any other concrete provider. The entry type is supplied by the consumer,
 * keeping the dependency direction from generic code toward this contract.
 */
interface AdapterCatalog<out T : Any> {
    val targetIds: Set<String>
    fun adapterFor(target: String): T?

    fun requireAdapter(target: String): T = adapterFor(target)
        ?: error(
            "No adapter is registered for '$target'. Available adapters: " +
                targetIds.sorted().joinToString().ifBlank { "none" }
        )
}

/** Immutable catalog implementation for explicit distribution composition. */
class StaticAdapterCatalog<T : Any> private constructor(
    private val entries: Map<String, T>
) : AdapterCatalog<T> {
    override val targetIds: Set<String> = entries.keys
    override fun adapterFor(target: String): T? = entries[target]

    companion object {
        fun <T : Any> of(entries: Iterable<Pair<String, T>>): StaticAdapterCatalog<T> {
            val indexed = linkedMapOf<String, T>()
            entries.forEach { (target, adapter) ->
                require(target.isNotBlank()) { "Adapter catalog target id must not be blank." }
                require(indexed.putIfAbsent(target, adapter) == null) {
                    "Duplicate adapter catalog target '$target'."
                }
            }
            return StaticAdapterCatalog(indexed.toMap())
        }

        fun <T : Any> empty(): StaticAdapterCatalog<T> = StaticAdapterCatalog(emptyMap())
    }
}
