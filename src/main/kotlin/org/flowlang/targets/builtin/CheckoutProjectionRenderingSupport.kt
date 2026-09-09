package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetRendererPayload
import org.flowlang.generators.manifest.sanitizeId
import org.flowlang.generators.manifest.unquote

/** Shared checkout value validation; repository-host syntax belongs to each adapter. */
object CheckoutProjectionValues {
    fun gitUrl(payload: TargetRendererPayload, name: String, context: String): String =
        text(payload, name, context).also {
            require(it.isNotBlank()) { "$context binding '$name' must not be blank." }
        }

    fun branch(payload: TargetRendererPayload, name: String, context: String): String =
        text(payload, name, context).also {
            require(it.isNotBlank()) { "$context binding '$name' must not be blank." }
        }

    fun depth(payload: TargetRendererPayload, name: String, context: String): Int {
        val raw = text(payload, name, context)
        val parsed = raw.toIntOrNull()
        require(parsed != null && parsed >= 0) {
            "$context binding '$name' must be a non-negative integer, but was '$raw'."
        }
        return parsed
    }

    fun workspace(payload: TargetRendererPayload, name: String, context: String): String {
        val raw = text(payload, name, context)
        require(raw.isNotBlank()) { "$context binding '$name' must not be blank." }
        return sanitizeId(raw)
    }

    fun text(payload: TargetRendererPayload, name: String, context: String): String {
        val binding = requireNotNull(payload.bindings[name]) { "$context requires binding '$name'." }
        return unquote(requireNotNull(binding.value) { "$context.bindings.$name is unresolved." })
    }
}
