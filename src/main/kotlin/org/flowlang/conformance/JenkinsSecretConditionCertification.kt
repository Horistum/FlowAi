package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode

/** Bounded string-credential condition oracle; values never enter artifacts or observations. */
internal object JenkinsSecretConditionCertification {
    val plugins = mapOf("credentials" to "1480.v2246fd131e83", "credentials-binding" to "702.vfe613e537e88",
        "plain-credentials" to "199.v9f8e1f741799")

    fun artifacts(text: String, original: ByteArray): Map<String, ByteArray> {
        require(original.contentEquals(text.toByteArray(Charsets.UTF_8)))
        val left = "    FLOW_SECRET_LEFTTOKEN = credentials('leftToken')"
        val right = "    FLOW_SECRET_RIGHTTOKEN = credentials('rightToken')"
        require(text.lineSequence().count { it == left } == 1 && text.lineSequence().count { it == right } == 1)
        require(text.lineSequence().count { it.contains(" = credentials(") } == 2)
        val condition = text.lineSequence().single { it.trimStart().startsWith("if (") }
        require(condition.contains("env.FLOW_SECRET_LEFTTOKEN != null") &&
            condition.contains("env.FLOW_SECRET_LEFTTOKEN == env.FLOW_SECRET_RIGHTTOKEN"))
        require(text.lineSequence().count { it.trim() == "git branch: 'selected', url: 'git://127.0.0.1:9418/repository.git'" } == 1)
        return linkedMapOf("baseline" to original,
            "omitted-binding" to text.replace(right + "\n", "").toByteArray(Charsets.UTF_8),
            "substituted-binding" to text.replace(right, right.replace("'rightToken'", "'differentToken'")).toByteArray(Charsets.UTF_8))
    }

    fun observation(row: JsonNode): Map<String, Any> {
        val secrets = requireNotNull(row["secrets"])
        require(secrets.isObject && secrets.fieldNames().asSequence().toSet() == setOf("bindings", "canaryInBuildLog"))
        require(secrets["canaryInBuildLog"]?.isBoolean == true && !secrets["canaryInBuildLog"].booleanValue()) {
            "Fixture canaries must not appear in the complete bounded build log."
        }
        val bindings = secrets["bindings"]
        require(bindings?.isArray == true && bindings.size() in 1..2)
        val values = bindings.map { binding ->
            require(binding.isObject && binding.fieldNames().asSequence().toSet() == setOf("credentialsId", "variable"))
            require(binding["credentialsId"]?.isTextual == true && binding["credentialsId"].asText() in setOf("leftToken", "rightToken", "differentToken"))
            require(binding["variable"]?.isTextual == true && binding["variable"].asText() in setOf("FLOW_SECRET_LEFTTOKEN", "FLOW_SECRET_RIGHTTOKEN"))
            linkedMapOf("credentialsId" to binding["credentialsId"].asText(), "variable" to binding["variable"].asText())
        }
        require(values.map { it.getValue("variable") }.distinct().size == values.size)
        require(values == values.sortedBy { it.getValue("variable") })
        return linkedMapOf("bindings" to values, "canaryInBuildLog" to false)
    }
}
