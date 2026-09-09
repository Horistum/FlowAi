package org.flowlang.targets.builtin

/** Shared payload identifiers used only by concrete adapter implementation modules. */
object BuiltInProjectionPayloadKinds {
    const val JENKINS_STEP = "JENKINS_STEP"
    const val JENKINS_STRUCTURE = "JENKINS_STRUCTURE"
    const val GITHUB_ACTION = "GITHUB_ACTION"
    const val TEKTON_TASK = "TEKTON_TASK"
}
