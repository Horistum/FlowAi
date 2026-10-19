package org.flowlang.conformance

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry

/** Ordinary source input; conformance derives membership and ordering independently. */
internal object ParallelCompositionFixture {
    fun checkout(name: String, branch: String = "selected") =
        """git.checkout source { url: "git://127.0.0.1:9418/repository.git" branch: "$branch" } -> $name"""
    const val GATE = "approve manual { message: \"Repeated decision\" }"
    fun source(failFast: Boolean = false, failure: Boolean = false) = """
        version "1.0"
        use module "git" version "1.0"
        flow "parallel-composition" {
          systems { system "source" { type: git } }
          steps {
            parallel failFast $failFast {
              branch "same" {
                $GATE
                ${checkout("first", if (failure) "missing-revision" else "selected")}
                ${checkout("second", "alternate")}
                $GATE
                ${checkout("third")}
              }
              branch "same" { $GATE ${checkout("sibling", "alternate")} }
              branch "same" { $GATE $GATE }
              branch "empty" { }
            }
            ${checkout("joined")}
            parallel failFast $failFast {
              branch "work" { ${checkout("ungated")} ${checkout("tail", "alternate")} }
              branch "empty" { }
            }
            ${checkout("successor")}
          }
        }
    """.trimIndent()
}

object ParallelCompositionCli {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2)
        val root = File(args[0]); val out = File(args[1])
        require(!out.exists()); require(out.mkdirs())
        val mapper = jacksonObjectMapper()
        val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
        val selection = TargetSelectionAuthority.requireSelected(TargetSelectionAuthority.fromCliOption("jenkins", targets), "parallel composition")
        val pipeline = ReferenceTargetProjections.pipeline(targets, rootDir = root,
            modules = ModuleRegistry.fromDirectory(File(root, "modules")))
        for (failFast in listOf(false, true)) for (failure in listOf(false, true)) {
            val dir = File(out, "${if (failure) "failure" else "success"}-$failFast").apply { mkdirs() }
            val source = ParallelCompositionFixture.source(failFast, failure)
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
