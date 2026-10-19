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
        for (outcome in listOf("success", "failure", "empty")) {
            val dir = File(out, outcome).apply { mkdirs() }
            val source = LoopCompositionFixture.source(outcome)
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
