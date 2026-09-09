package org.flowlang.targets.builtin

import java.net.URI
import org.flowlang.generators.manifest.TargetRendererPayload

/** Repository-host interpretation is owned by the concrete checkout adapter. */
internal object GitHubActionsCheckoutProjectionValues {
    fun githubRepository(payload: TargetRendererPayload, name: String, context: String): String {
        val raw = CheckoutProjectionValues.text(payload, name, context).trim()
        val scp = GITHUB_SCP.matchEntire(raw)
        if (scp != null) return repositorySlug(scp.groupValues[1], scp.groupValues[2], context)
        val short = GITHUB_SHORT.matchEntire(raw)
        if (short != null) return repositorySlug(short.groupValues[1], short.groupValues[2], context)
        val uri = runCatching { URI(raw) }.getOrNull()
        require(uri != null && uri.isAbsolute) {
            "$context binding '$name' must be an absolute GitHub repository URL or git@github.com SCP reference."
        }
        require(uri.host.equals("github.com", ignoreCase = true)) {
            "$context binding '$name' targets '${uri.host ?: "unknown"}', not github.com."
        }
        require(uri.query == null && uri.fragment == null) {
            "$context binding '$name' must not contain query or fragment components."
        }
        require(uri.scheme.lowercase() in setOf("https", "http", "ssh", "git")) {
            "$context binding '$name' uses unsupported GitHub URL scheme '${uri.scheme}'."
        }
        val segments = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        require(segments.size == 2) {
            "$context binding '$name' must identify exactly one GitHub owner/repository pair."
        }
        return repositorySlug(segments[0], segments[1], context)
    }

    private fun repositorySlug(owner: String, rawRepository: String, context: String): String {
        val repository = rawRepository.removeSuffix(".git").trimEnd('/')
        require(GITHUB_COMPONENT.matches(owner) && GITHUB_COMPONENT.matches(repository)) {
            "$context contains an invalid GitHub owner or repository component."
        }
        return "$owner/$repository"
    }

    private val GITHUB_SCP = Regex("""^git@github\.com:([^/]+)/([^/]+?)(?:\.git)?/?$""", RegexOption.IGNORE_CASE)
    private val GITHUB_SHORT = Regex("""^github\.com/([^/]+)/([^/]+?)(?:\.git)?/?$""", RegexOption.IGNORE_CASE)
    private val GITHUB_COMPONENT = Regex("^[A-Za-z0-9_.-]+$")
}
