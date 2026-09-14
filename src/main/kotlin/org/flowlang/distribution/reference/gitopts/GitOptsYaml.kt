package org.flowlang.distribution.reference.gitopts

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

object GitOptsYaml {
    private val yamlMapper: ObjectMapper = ObjectMapper(
        YAMLFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    )
        .registerKotlinModule()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    private val jsonMapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(SerializationFeature.INDENT_OUTPUT)

    fun load(file: File): GitOptsIntent {
        require(file.isFile) { "Git opts intent does not exist: ${file.path}" }
        return yamlMapper.readValue(file, GitOptsIntent::class.java)
    }

    fun renderPlan(plan: GitOptsPlan): String = jsonMapper.writeValueAsString(plan) + "\n"
}
