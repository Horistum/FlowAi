package org.flowlang.targets.builtin

import java.net.URI
import org.flowlang.generators.manifest.TargetRendererPayload
import org.flowlang.generators.manifest.sanitizeId
import org.flowlang.generators.manifest.unquote
import org.flowlang.projection.ProjectionBinding

/** Shared validated checkout values consumed by concrete adapter renderers. */
object CheckoutProjectionValues {
    fun gitUrl(payload: TargetRendererPayload, name: String, context: String): String = resolvedText(payload, name, context).also {
        require(it.isNotBlank()) { "$context binding '$name' must not be blank." }
    }
    fun branch(payload: TargetRendererPayload, name: String, context: String): String = resolvedText(payload, name, context).also {
        require(it.isNotBlank()) { "$context binding '$name' must not be blank." }
    }
    fun depth(payload: TargetRendererPayload, name: String, context: String): Int {
        val raw = resolvedText(payload, name, context)
        val parsed = raw.toIntOrNull()
        require(parsed != null && parsed >= 0) { "$context binding '$name' must be a non-negative integer, but was '$raw'." }
        return parsed
    }
    fun workspace(payload: TargetRendererPayload, name: String, context: String): String {
        val raw = resolvedText(payload, name, context)
        require(raw.isNotBlank()) { "$context binding '$name' must not be blank." }
        return sanitizeId(raw)
    }
    fun githubRepository(payload: TargetRendererPayload, name: String, context: String): String {
        val raw = resolvedText(payload, name, context).trim()
        GITHUB_SCP.matchEntire(raw)?.let { return repositorySlug(it.groupValues[1], it.groupValues[2], context) }
        GITHUB_SHORT.matchEntire(raw)?.let { return repositorySlug(it.groupValues[1], it.groupValues[2], context) }
        val uri = runCatching { URI(raw) }.getOrNull()
        require(uri != null && uri.isAbsolute) { "$context binding '$name' must be an absolute GitHub repository URL or git@github.com SCP reference." }
        require(uri.host.equals("github.com", ignoreCase = true)) { "$context binding '$name' targets '${uri.host ?: "unknown"}', not github.com." }
        require(uri.query == null && uri.fragment == null) { "$context binding '$name' must not contain query or fragment components." }
        require(uri.scheme.lowercase() in setOf("https", "http", "ssh", "git")) { "$context binding '$name' uses unsupported GitHub URL scheme '${uri.scheme}'." }
        val segments = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        require(segments.size == 2) { "$context binding '$name' must identify exactly one GitHub owner/repository pair." }
        return repositorySlug(segments[0], segments[1], context)
    }
    private fun resolvedText(payload: TargetRendererPayload, name: String, context: String): String {
        val binding = requireNotNull(payload.bindings[name]) { "$context requires binding '$name'." }
        return resolvedText(binding, "$context.bindings.$name")
    }
    private fun resolvedText(binding: ProjectionBinding, context: String): String = unquote(requireNotNull(binding.value) { "$context is unresolved." })
    private fun repositorySlug(owner: String, rawRepository: String, context: String): String {
        val repository = rawRepository.removeSuffix(".git").trimEnd('/')
        require(GITHUB_COMPONENT.matches(owner) && GITHUB_COMPONENT.matches(repository)) { "$context contains an invalid GitHub owner or repository component." }
        return "$owner/$repository"
    }
    private val GITHUB_SCP = Regex("""^git@github\.com:([^/]+)/([^/]+?)(?:\.git)?/?$""", RegexOption.IGNORE_CASE)
    private val GITHUB_SHORT = Regex("""^github\.com/([^/]+)/([^/]+?)(?:\.git)?/?$""", RegexOption.IGNORE_CASE)
    private val GITHUB_COMPONENT = Regex("^[A-Za-z0-9_.-]+$")
}
