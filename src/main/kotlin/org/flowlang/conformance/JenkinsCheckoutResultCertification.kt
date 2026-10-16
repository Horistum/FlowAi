package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode

/** Native completion-to-boolean contract; no result record or generic output transfer claim. */
internal object JenkinsCheckoutResultCertification {
    fun artifacts(text: String, original: ByteArray, scenario: JenkinsCheckoutRuntimeCertification.Scenario): Map<String, ByteArray> {
        require(original.contentEquals(text.toByteArray(Charsets.UTF_8)))
        if (scenario == JenkinsCheckoutRuntimeCertification.Scenario.RESULT_COMPOSITION)
            return compositionArtifacts(text, original)
        val binding = "          def flowCheckoutSucceeded = true"
        val expected = scenario != JenkinsCheckoutRuntimeCertification.Scenario.RESULT_FALSE
        val guard = "          if (flowCheckoutSucceeded == $expected) {"
        val branch = if (scenario == JenkinsCheckoutRuntimeCertification.Scenario.RESULT_FAILURE) "missing-revision" else "selected"
        val producer = "          git branch: '$branch', url: 'git://127.0.0.1:9418/repository.git'"
        val consumer = "            git branch: 'alternate', url: 'git://127.0.0.1:9418/repository.git'"
        fun replace(line: String, replacement: String): ByteArray {
            require(text.lineSequence().count { it == line } == 1)
            return text.replace(line + "\n", replacement + "\n").toByteArray(Charsets.UTF_8)
        }
        for (line in listOf(producer, binding, guard, consumer)) require(text.lineSequence().count { it == line } == 1)
        require(text.lineSequence().count { it.trimStart().startsWith("git branch:") } == 2)
        require(text.indexOf(producer) < text.indexOf(binding) && text.indexOf(binding) < text.indexOf(guard) && text.indexOf(guard) < text.indexOf(consumer))
        return when (scenario) {
            JenkinsCheckoutRuntimeCertification.Scenario.RESULT_TRUE -> linkedMapOf("baseline" to original,
                "omitted-binding" to replace(binding, binding.replace("= true", "= null")),
                "substituted-binding" to replace(binding, binding.replace("= true", "= false")))
            JenkinsCheckoutRuntimeCertification.Scenario.RESULT_FALSE -> linkedMapOf("baseline" to original,
                "substituted-binding" to replace(binding, binding.replace("= true", "= false")),
                "bypassed-guard" to replace(guard, "          if (true) {"))
            JenkinsCheckoutRuntimeCertification.Scenario.RESULT_FAILURE -> linkedMapOf("baseline" to original,
                "omitted-failure" to replace(producer, producer.replace("'missing-revision'", "'selected'")),
                "suppressed-failure" to replace(producer, "          catchError(buildResult: 'SUCCESS', stageResult: 'SUCCESS') {\n  $producer\n          }"))
            else -> error("Not a checkout result scenario.")
        }
    }

    private fun compositionArtifacts(text: String, original: ByteArray): Map<String, ByteArray> {
        val bindings = listOf("          def flowCheckoutSucceeded = true",
            "          def flowCheckoutSucceeded2 = true", "            def flowCheckoutSucceeded3 = true")
        bindings.forEach { line -> require(text.lineSequence().count { it == line } == 1) }
        require(text.lineSequence().count { it.trimStart().startsWith("git branch:") } == 5)
        require(text.contains("params.enabled") && text.contains("flowCheckoutSucceeded2 != false") &&
            text.contains("flowCheckoutSucceeded3 == true"))
        fun changed(index: Int, value: String): ByteArray = text.replace(bindings[index] + "\n",
            bindings[index].replace("= true", "= $value") + "\n").toByteArray(Charsets.UTF_8)
        return linkedMapOf("baseline" to original,
            "omitted-first-binding" to changed(0, "null"),
            "substituted-second-binding" to changed(1, "false"),
            "omitted-inner-binding" to changed(2, "null"))
    }

    fun completedFailure(row: JsonNode, message: String): Boolean =
        row["result"]?.asText() == "FAILURE" && row["checkoutCount"]?.asInt() == 1 &&
            row["checkoutBranches"]?.map { it.asText() } == listOf("missing-revision") &&
            row["checkoutErrors"]?.size() == 1 && row["checkoutErrors"][0]["type"]?.asText() == "hudson.AbortException" &&
            row["checkoutErrors"][0]["message"]?.asText() == message &&
            row["checkoutErrorIndices"]?.map { it.asInt() } == listOf(1) &&
            row["terminalError"]?.get("type")?.asText() == "hudson.AbortException" &&
            row["terminalError"]?.get("message")?.asText() == message && row["terminalError"]?.get("checkoutIndex")?.asInt() == 1
}
