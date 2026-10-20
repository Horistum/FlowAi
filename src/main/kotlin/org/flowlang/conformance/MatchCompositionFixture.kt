package org.flowlang.conformance

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry

/** Source-derived verification input; selection expectations do not come from adapter output. */
internal object MatchCompositionFixture {
    val outcomes = listOf("success", "default", "empty", "failure") +
        listOf("retry-match", "match-retry").flatMap { direction ->
            listOf("success", "default", "empty", "failure").map { "$direction-$it" }
        }

    fun interactionSource(direction: String, outcome: String): String {
        require(direction in listOf("retry-match", "match-retry"))
        require(outcome in listOf("success", "default", "empty", "failure"))
        val selected = RetryCompositionFixture.checkout("selected",
            if (outcome == "failure") RetryCompositionFixture.ABSENT_REVISION else RetryCompositionFixture.REVISION)
        val body = """
            if enabled {
              if entry == "selected" { $selected }
            }
            if entry == "alternate" { ${RetryCompositionFixture.checkout("alternate")} }
        """.trimIndent()
        fun retry(body: String) = """
            retry { max: 2 delay: "0s" backoff: fixed } {
              ${RetryCompositionFixture.checkout("attemptPrefix")}
              $body
              ${RetryCompositionFixture.checkout("attemptTail")}
            }
        """.trimIndent()
        fun loop(body: String) = """for entry in ["alternate", "selected", "selected"] { $body }"""
        fun match(body: String): String {
            val selectedBody = if (outcome == "empty") "" else body
            val fallback = RetryCompositionFixture.checkout("fallback")
            return """
                match "inert" {
                  when false { ${RetryCompositionFixture.checkout("unselected")} }
                  when ${outcome != "default"} { ${if (outcome == "default") fallback else selectedBody} }
                  default { ${if (outcome == "default") selectedBody else fallback} }
                }
            """.trimIndent()
        }
        val composed = if (direction == "retry-match") retry(match(loop(body))) else loop(match(retry(body)))
        return """
            version "1.0"
            use module "git" version "1.0"
            flow "sequential-match-composition" {
              input { enabled: boolean default true }
              systems { system "source" { type: git } }
              steps { $composed ${RetryCompositionFixture.checkout("successor")} }
            }
        """.trimIndent()
    }

    fun source(outcome: String = "success"): String {
        val selected = if (outcome == "empty") "" else """
            ${RetryCompositionFixture.checkout("prefix")}
            if enabled {
              match "inner" {
                when false { ${RetryCompositionFixture.checkout("innerUnselected")} }
                when true { ${RetryCompositionFixture.checkout("selected", if (outcome == "failure") RetryCompositionFixture.ABSENT_REVISION else RetryCompositionFixture.REVISION)} }
                default { ${RetryCompositionFixture.checkout("innerDefault")} }
              }
            }
            ${RetryCompositionFixture.checkout("bodyTail")}
        """.trimIndent()
        return """
            version "1.0"
            use module "git" version "1.0"
            flow "match-composition" {
              input { enabled: boolean default true }
              systems { system "source" { type: git } }
              steps {
                match "outer" {
                  when false { ${RetryCompositionFixture.checkout("unselected")} }
                  when ${outcome != "default"} { $selected }
                  default {
                    if enabled { ${RetryCompositionFixture.checkout("defaultPrefix")} }
                    match "fallback" {
                      when false { ${RetryCompositionFixture.checkout("defaultUnselected")} }
                      default { ${RetryCompositionFixture.checkout("defaultSelected")} }
                    }
                    ${RetryCompositionFixture.checkout("defaultTail")}
                  }
                }
                ${RetryCompositionFixture.checkout("successor")}
              }
            }
        """.trimIndent()
    }
}

object MatchCompositionCli {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Expected repository root and fresh output directory." }
        val root = File(args[0]); val out = File(args[1])
        require(!out.exists()); require(out.mkdirs())
        val mapper = jacksonObjectMapper()
        val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
        val selection = TargetSelectionAuthority.requireSelected(TargetSelectionAuthority.fromCliOption("jenkins", targets), "match composition")
        val pipeline = ReferenceTargetProjections.pipeline(targets, rootDir = root,
            modules = ModuleRegistry.fromDirectory(File(root, "modules")))
        for (outcome in MatchCompositionFixture.outcomes) {
            val dir = File(out, outcome).apply { mkdirs() }
            val source = if (outcome in listOf("success", "default", "empty", "failure")) MatchCompositionFixture.source(outcome)
                else MatchCompositionFixture.interactionSource(outcome.substringBeforeLast("-"), outcome.substringAfterLast("-"))
            val unit = RetryCompositionFixture.compile(root, source)
            val manifest = pipeline.generate(TargetMaterializationRequest.fromCompilation(unit, selection, strict = true))
            File(dir, "source.flow").writeText(source)
            File(dir, "source.ast.json").writeBytes(mapper.writeValueAsBytes(unit.ast))
            File(dir, "canonical-graph.json").writeBytes(mapper.writeValueAsBytes(unit.graph))
            File(dir, "manifest.json").writeBytes(mapper.writeValueAsBytes(manifest))
            File(dir, "Jenkinsfile").writeText(ReferenceTargetProjections.registry.requireProvider("jenkins").render(manifest))
        }
    }
}
