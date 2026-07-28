import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.adapters.control.AdapterControlMaterializationLoader

class AdapterControlEvidenceAnchorTests {
    @Test
    fun loaderRejectsSourceEvidenceWithMissingAnchor() {
        val root = Files.createTempDirectory("flow-a04-evidence-anchor").toFile()
        try {
            val source = File(root, "src/main/kotlin/example/ControlEvidence.kt")
            source.parentFile.mkdirs()
            source.writeText("package example\n\nfun actualAnchor() = Unit\n")

            val manifest = File(root, AdapterControlMaterializationLoader.PATH)
            manifest.parentFile.mkdirs()
            manifest.writeText(
                """
                version: "1.0"
                targets:
                  - target: fixture
                    claims:
                      - family: APPROVAL
                        status: UNKNOWN
                        mechanism: "Fixture evidence."
                        ownership: NONE
                        scopes: [STEP]
                        semantics:
                          supported: []
                          unsupported: {}
                          unknown:
                            approval.manual.inline: "Fixture unknown."
                        evidenceReferences:
                          - "src/main/kotlin/example/ControlEvidence.kt#missingAnchor"
                        platformReferences: []
                        prerequisites: []
                        limitations: ["Fixture only."]
                """.trimIndent() + "\n"
            )

            val failure = assertFailsWith<IllegalArgumentException> {
                AdapterControlMaterializationLoader.load(root)
            }
            assertTrue(failure.message.orEmpty().contains("unresolved source anchor 'missingAnchor'"))
        } finally {
            root.deleteRecursively()
        }
    }
}
