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
        for (outcome in listOf("success", "default", "empty", "failure")) {
            val dir = File(out, outcome).apply { mkdirs() }
            val source = MatchCompositionFixture.source(outcome)
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
