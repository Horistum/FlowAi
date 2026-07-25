import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import org.flowlang.modules.CanonicalModuleLoader

class CanonicalModuleTargetOwnershipTests {
    @Test
    fun forbiddenTargetOwnershipFieldsFailThroughCanonicalStructure() {
        val forbiddenBodies = listOf(
            "runtime:\n  executable: true",
            "generators: { jenkins: owned }",
            "template: Jenkinsfile",
            "entrypoint: run",
            "targetImplications: [jenkins]"
        )

        forbiddenBodies.forEachIndexed { index, forbidden ->
            val error = assertFailsWith<CanonicalModuleLoader.ContractException> {
                CanonicalModuleLoader.loadText(moduleWith(forbidden), "target-ownership-$index.yaml")
            }
            assertContains(error.message.orEmpty(), "actions.execute")
        }
    }

    @Test
    fun inputFieldNamedTemplateIsNotConfusedWithActionOwnedRendering() {
        CanonicalModuleLoader.loadText(
            """
            kind: FlowModule
            name: neutral-template-input
            version: "1.0"
            description: "Semantic input contract"
            systemTypes:
              neutral:
                input: {}
            actions:
              execute:
                kind: action
                targetTypes: [neutral]
                input:
                  template:
                    type: text
                output: {}
                effects:
                  reads: []
                  writes: []
                  creates: []
                  updates: []
                  deletes: []
                  executes: []
                  network: []
                  filesystem: []
                safety:
                  destructive: false
            """.trimIndent(),
            "template-input.yaml"
        )
    }

    private fun moduleWith(forbidden: String): String {
        val base = """
            kind: FlowModule
            name: forbidden-target-owner
            version: "1.0"
            description: "Negative target ownership fixture"
            systemTypes:
              neutral:
                input: {}
            actions:
              execute:
                kind: action
                targetTypes: [neutral]
                input: {}
                output: {}
                effects:
                  reads: []
                  writes: []
                  creates: []
                  updates: []
                  deletes: []
                  executes: []
                  network: []
                  filesystem: []
                safety:
                  destructive: false
        """.trimIndent()
        return base + "\n" + forbidden.trimIndent().prependIndent("    ") + "\n"
    }
}
