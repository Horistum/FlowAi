package org.flowlang.intent

/**
 * Centralizes defaults that are still useful for early generation but must not be
 * hidden inside lowering code. Every assumption is reported in the design report
 * so the user remains the architect and can replace conventions with explicit
 * intent decisions.
 */
object ConventionResolver {
    fun assumptions(intent: IntentDocument): List<String> {
        val steps = intent.workflows.flatMap { it.steps }
        val out = mutableListOf<String>()
        steps.forEach { step ->
            when (step.capability) {
                StandardCapability.CHECKOUT -> if (step.params["branch"] == null) out += "Step '${step.id}' does not define params.branch. Convention: branch = main."
                StandardCapability.TEST -> if (step.params["command"] == null) out += "Step '${step.id}' does not define params.command. Convention: command = mvn test."
                StandardCapability.BUILD, StandardCapability.PACKAGE -> if (step.params["command"] == null) out += "Step '${step.id}' does not define params.command. Convention: command = mvn package."
                StandardCapability.BUILD_IMAGE -> {
                    if (step.params["path"] == null) out += "Step '${step.id}' does not define params.path. Convention: path = ."
                    if (step.params["image"] == null) out += "Step '${step.id}' does not define params.image. Convention: image = <intent-name>:<version|latest>."
                }
                StandardCapability.APPROVE -> if (step.params["message"] == null) out += "Step '${step.id}' does not define params.message. Convention: use approval policy message or generated message."
                else -> Unit
            }
        }
        return out
    }
}
