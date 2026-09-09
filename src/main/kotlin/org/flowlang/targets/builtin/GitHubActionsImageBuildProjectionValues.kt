package org.flowlang.targets.builtin

internal object GitHubActionsImageBuildProjectionValues {
    fun githubContext(contextPath: String): String? =
        if (contextPath == ".") null else "{{defaultContext}}:$contextPath"

    fun githubDockerfile(contextPath: String, dockerfile: String?): String? {
        if (dockerfile == null || ImageBuildProjectionValues.isDefaultDockerfile(contextPath, dockerfile)) return null
        if (contextPath == ".") return dockerfile
        val prefix = "$contextPath/"
        require(dockerfile.startsWith(prefix)) {
            "GitHub image-build Dockerfile '$dockerfile' must be inside build context '$contextPath'."
        }
        return dockerfile.removePrefix(prefix)
    }
}
