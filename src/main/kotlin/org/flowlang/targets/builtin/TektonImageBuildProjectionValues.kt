package org.flowlang.targets.builtin

internal object TektonImageBuildProjectionValues {
    fun tektonDockerfile(contextPath: String, dockerfile: String?): String =
        dockerfile ?: if (contextPath == ".") "./Dockerfile" else "$contextPath/Dockerfile"
}
