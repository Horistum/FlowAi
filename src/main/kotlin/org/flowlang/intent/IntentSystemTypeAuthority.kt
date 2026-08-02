package org.flowlang.intent

/**
 * Resolves only explicit legacy aliases used at the binding boundary.
 *
 * Generic semantic system kinds must remain unchanged. In particular,
 * `containerRegistry` is not evidence that a Docker implementation was selected.
 */
object IntentSystemTypeAuthority {
    private val explicitAliases: Map<String, String> = mapOf(
        "dockerRegistry" to "docker",
        "notification" to "notify",
        "email" to "notify"
    )

    fun bindingType(sourceType: String): String = explicitAliases[sourceType] ?: sourceType
}
