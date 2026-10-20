package org.flowlang.targets.builtin

internal object GitHubActionsImageBuildProjectionValues {
    // Default Git context bypasses downloaded workspace bytes, so always select a local path.
    fun githubContext(contextPath: String): String = contextPath

    fun githubDockerfile(contextPath: String, dockerfile: String?): String? {
        if (dockerfile == null || ImageBuildProjectionValues.isDefaultDockerfile(contextPath, dockerfile)) return null
        if (contextPath == ".") return dockerfile
        val prefix = "$contextPath/"
        require(dockerfile.startsWith(prefix)) {
            "GitHub image-build Dockerfile '$dockerfile' must be inside build context '$contextPath'."
        }
        return dockerfile
    }
}
