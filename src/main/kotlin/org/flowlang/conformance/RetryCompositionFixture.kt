package org.flowlang.conformance

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.compiler.requireAccepted
import org.flowlang.compiler.CompilationFrontend
import org.flowlang.compiler.CompilationSource
import org.flowlang.compiler.FlowSourceCompilationInput
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.parser.FlowParser
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes

/** Verification fixture through the normal parser and compiler, with a reproducible logical source identity. */
internal object RetryCompositionFixture {
    const val REVISION = "c6149ff9ba87f88e25cdc0905cfb86cb935f7756"
    const val ABSENT_REVISION = "0000000000000000000000000000000000000000"
    fun checkout(name: String, revision: String = REVISION) = """
        git.checkout source { url: "https://github.com/Horistum/FlowAi.git" branch: "$revision" } -> $name
    """.trimIndent()

    fun source(failure: Boolean = false) = """
        version "1.0"
        use module "git" version "1.0"
        flow "retry-composition" {
          input { enabled: boolean default true }
          systems { system "source" { type: git } }
          steps {
            retry { max: 2 delay: "0s" backoff: fixed } {
              ${checkout("prefix")}
              if enabled {
                retry { max: 3 delay: "0s" backoff: fixed } {
                  ${checkout("selected", if (failure) ABSENT_REVISION else REVISION)}
                  ${checkout("innerTail")}
                }
              }
              ${checkout("bodyTail")}
            }
            ${checkout("successor")}
          }
        }
    """.trimIndent()

    fun compile(root: File, source: String) = FrontendCompilerComposition.compiler(
        ModuleRegistry.fromDirectory(File(root, "modules")), StandardEnvironmentSafetyPolicyNotes.policy(root)
    ).compile(FlowSourceCompilationInput(
        source = CompilationSource.fromBytes(CompilationFrontend.FLOW_SOURCE, "composition.flow", source.toByteArray(Charsets.UTF_8)),
        ast = FlowParser().parse(source, "composition.flow")
    )).requireAccepted()
}

object RetryCompositionCli {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Expected repository root and fresh output directory." }
        val root = File(args[0]); val out = File(args[1])
        require(!out.exists()); require(out.mkdirs())
        val mapper = jacksonObjectMapper()
        val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
        val selection = TargetSelectionAuthority.requireSelected(TargetSelectionAuthority.fromCliOption("jenkins", targets), "retry composition")
        val pipeline = ReferenceTargetProjections.pipeline(targets, rootDir = root,
            modules = ModuleRegistry.fromDirectory(File(root, "modules")))
        for (failure in listOf(false, true)) {
            val dir = File(out, if (failure) "failure" else "success").apply { mkdirs() }
            val source = RetryCompositionFixture.source(failure)
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
