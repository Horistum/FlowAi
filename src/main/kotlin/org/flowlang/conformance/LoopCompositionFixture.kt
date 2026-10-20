package org.flowlang.conformance

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry

/** Verification input compiled by the source frontend; no product execution path. */
internal object LoopCompositionFixture {
    fun source(outcome: String = "success") = """
        version "1.0"
        use module "git" version "1.0"
        flow "loop-composition" {
          input { enabled: boolean default true }
          systems { system "source" { type: git } }
          steps {
            for outer in ${if (outcome == "empty") "[]" else "[\"selected\", \"selected\", \"alternate\"]"} {
              ${RetryCompositionFixture.checkout("prefix")}
              if enabled and outer == "selected" {
                for inner in ["one", "two"] {
                  if inner == "one" {
                    ${RetryCompositionFixture.checkout("selected", if (outcome == "failure") RetryCompositionFixture.ABSENT_REVISION else RetryCompositionFixture.REVISION)}
                  }
                  ${RetryCompositionFixture.checkout("innerTail")}
                }
              }
              if outer == "alternate" { ${RetryCompositionFixture.checkout("alternate")} }
              ${RetryCompositionFixture.checkout("bodyTail")}
            }
            ${RetryCompositionFixture.checkout("successor")}
          }
        }
    """.trimIndent()

    val outcomes = listOf("success", "failure", "empty") +
        listOf("retry-loop", "loop-retry").flatMap { direction ->
            listOf("success", "failure", "empty").map { "$direction-$it" }
        }

    fun interactionSource(direction: String, outcome: String): String {
        require(direction in listOf("retry-loop", "loop-retry"))
        require(outcome in listOf("success", "failure", "empty"))
        val collection = if (outcome == "empty") "[]" else "[\"alternate\", \"selected\", \"selected\"]"
        val selected = RetryCompositionFixture.checkout("selected",
            if (outcome == "failure") RetryCompositionFixture.ABSENT_REVISION else RetryCompositionFixture.REVISION)
        val body = """
            if enabled and outer == "selected" { $selected }
            if outer == "alternate" { ${RetryCompositionFixture.checkout("alternate")} }
        """.trimIndent()
        fun retry(body: String) = """
            retry { max: 2 delay: "0s" backoff: fixed } {
              ${RetryCompositionFixture.checkout("attemptPrefix")}
              $body
              ${RetryCompositionFixture.checkout("attemptTail")}
            }
        """.trimIndent()
        fun loop(body: String) = """
            for outer in $collection {
              ${RetryCompositionFixture.checkout("iterationPrefix")}
              $body
              ${RetryCompositionFixture.checkout("iterationTail")}
            }
        """.trimIndent()
        val composed = if (direction == "retry-loop") retry(loop(body)) else loop(retry(body))
        return """
            version "1.0"
            use module "git" version "1.0"
            flow "retry-loop-composition" {
              input { enabled: boolean default true }
              systems { system "source" { type: git } }
              steps {
                $composed
                ${RetryCompositionFixture.checkout("successor")}
              }
            }
        """.trimIndent()
    }

}

object LoopCompositionCli {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Expected repository root and fresh output directory." }
        val root = File(args[0]); val out = File(args[1])
        require(!out.exists()); require(out.mkdirs())
        val mapper = jacksonObjectMapper()
        val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
        val selection = TargetSelectionAuthority.requireSelected(TargetSelectionAuthority.fromCliOption("jenkins", targets), "loop composition")
        val pipeline = ReferenceTargetProjections.pipeline(targets, rootDir = root,
            modules = ModuleRegistry.fromDirectory(File(root, "modules")))
        for (outcome in LoopCompositionFixture.outcomes) {
            val dir = File(out, outcome).apply { mkdirs() }
            val source = if (outcome in listOf("success", "failure", "empty")) LoopCompositionFixture.source(outcome)
                else LoopCompositionFixture.interactionSource(outcome.substringBeforeLast("-"), outcome.substringAfterLast("-"))
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
