import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.ast.*
import org.flowlang.capabilities.*
import org.flowlang.intent.*
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.validator.FlowValidator

fun intentConformanceTests() {
    fun load(text: String) = IntentYamlLoader.loadText(text)
    fun lower(text: String) = IntentToAstPlanner().plan(load(text))
    fun validateAst(ast: FlowDocument) = FlowValidator(ModuleRegistry.fromDirectory(java.io.File("modules"), includeDefaults = true)).validate(ast)

    H.scenario {
        val intent = load("""
            kind: FlowIntentDocument
            name: basic-build
            workflows:
              - name: ci
                kind: BUILD
                steps:
                  - id: test
                    capability: TEST
                    params:
                      command: mvn test
        """.trimIndent())
        H.eq("intent/load/name", intent.name, "basic-build")
        H.eq("intent/load/step", intent.workflows.first().steps.first().capability, StandardCapability.TEST)
    }

    H.scenario {
        val ast = lower("""
            kind: FlowIntentDocument
            name: backup-flow
            workflows:
              - name: ops
                kind: BACKUP
                steps:
                  - id: backup-db
                    capability: BACKUP
                    params:
                      subject: database
        """.trimIndent())
        val action = ast.flow.steps.first() as ActionNode
        H.eq("intent/non-cicd/standard-module", action.module, "standard")
        H.eq("intent/non-cicd/no-wip-echo", action.action, "execute")
        H.ok("intent/non-cicd/import-standard", ast.imports.any { it.name == "standard" })
    }

    H.scenario {
        val ast = lower("""
            kind: FlowIntentDocument
            name: dag-flow
            workflows:
              - name: ci
                kind: BUILD
                steps:
                  - id: checkout
                    capability: CHECKOUT
                  - id: unit-tests
                    capability: TEST
                    requires: [checkout]
                    params:
                      command: mvn test
                  - id: integration-tests
                    capability: TEST
                    requires: [checkout]
                    params:
                      command: mvn verify
                  - id: package
                    capability: PACKAGE
                    requires: [unit-tests, integration-tests]
        """.trimIndent())
        H.ok("intent/dag/no-implicit-parallel-level", ast.flow.steps.none { it is ParallelNode })
        val plan = FlowPlanner(ModuleRegistry.fromDirectory(java.io.File("modules"), includeDefaults = true)).plan(ast)
        H.ok("intent/dag/no-implicit-plan-parallel", plan.nodes.none { it is org.flowlang.planner.ParallelGroupNode })
        H.ok("intent/dag/ordered-dependencies", plan.tasks.drop(1).all { it.dependsOn.isNotEmpty() })
    }

    H.scenario {
        try {
            lower("""
                kind: FlowIntentDocument
                name: bad-dep
                workflows:
                  - name: ci
                    steps:
                      - id: test
                        capability: TEST
                        requires: [missing]
            """.trimIndent())
            H.ok("intent/dag/missing-dependency", false)
        } catch (e: Exception) {
            H.ok("intent/dag/missing-dependency", e.message?.contains("UNKNOWN_STEP_DEPENDENCY") == true)
        }
    }

    H.scenario {
        try {
            lower("""
                kind: FlowIntentDocument
                name: cycle
                workflows:
                  - name: ci
                    steps:
                      - id: a
                        capability: TEST
                        requires: [b]
                      - id: b
                        capability: TEST
                        requires: [a]
            """.trimIndent())
            H.ok("intent/dag/cycle", false)
        } catch (e: Exception) {
            H.ok("intent/dag/cycle", e.message?.contains("CYCLIC_STEP_DEPENDENCY") == true)
        }
    }

    H.scenario {
        val ast = lower("""
            kind: FlowIntentDocument
            name: no-version-deploy
            workflows:
              - name: cd
                kind: DEPLOY
                steps:
                  - id: deploy
                    capability: DEPLOY
                    params:
                      namespace: demo
                      image: demo:1.0
        """.trimIndent())
        val rep = validateAst(ast)
        H.ok("intent/lowering/no-hardcoded-version-env", rep.issues.none { it.level == "error" })
    }

    H.scenario {
        val ast = lower("""
            kind: FlowIntentDocument
            name: approval-flow
            policies:
              - name: prod-approval
                type: APPROVAL
                condition: environment == 'prod'
            workflows:
              - name: cd
                kind: DEPLOY
                steps:
                  - id: approve
                    capability: APPROVE
        """.trimIndent())
        val plan = FlowPlanner(ModuleRegistry.fromDirectory(java.io.File("modules"), includeDefaults = true)).plan(ast)
        val tekton = CompatibilityAnalyzer(TargetRegistryYamlLoader.loadDirectory(java.io.File("targets"))).analyze(plan, "tekton")
        H.ok("intent/compat/tekton-approval-error", tekton.issues.any { it.level == CompatibilityLevel.ERROR && it.feature == "approvals" })
        try {
            tekton.assertAllowed(strict = false)
            H.ok("intent/compat/gate-enforced", false)
        } catch (e: Exception) {
            H.ok("intent/compat/gate-enforced", true)
        }
    }
    H.scenario {
        val intent = load("""
            kind: FlowIntentDocument
            name: flow-style-intent
            systems: [{ name: argo, type: argocd, config: { url: secret:ARGOCD_URL, token: secret:ARGOCD_TOKEN } }]
            workflows:
              - name: cd
                kind: DEPLOY
                steps:
                  - id: deploy
                    capability: DEPLOY
                    uses: argocd.sync
                    requires: []
                    params: { system: argo, app: demo, wait: true }
        """.trimIndent())
        H.eq("intent/yaml/flow-style-system-config", (intent.systems.first().config["url"] as org.flowlang.intent.IntentSecretRef).name, "ARGOCD_URL")
        H.eq("intent/yaml/flow-style-param", (intent.workflows.first().steps.first().params["app"] as org.flowlang.intent.IntentString).value, "demo")
        val rep = IntentCapabilityValidator(ModuleRegistry.fromDirectory(java.io.File("modules"), includeDefaults = true)).validate(intent)
        H.ok("intent/capability/argocd-config-ok", rep.valid)
        val ast = IntentToAstPlanner(ModuleRegistry.fromDirectory(java.io.File("modules"), includeDefaults = true)).plan(intent)
        val argo = ast.flow.systems.first { it.name == "argo" }
        H.ok("intent/lowering/system-config-carried", argo.config.containsKey("url") && argo.config.containsKey("token"))
    }

    H.scenario {
        val intent = load("""
            kind: FlowIntentDocument
            name: bad-argocd
            systems:
              - name: argo
                type: argocd
            workflows:
              - name: cd
                kind: DEPLOY
                steps:
                  - id: deploy
                    capability: DEPLOY
                    uses: argocd.sync
                    params: { system: argo, app: demo }
        """.trimIndent())
        val rep = IntentCapabilityValidator(ModuleRegistry.fromDirectory(java.io.File("modules"), includeDefaults = true)).validate(intent)
        H.ok("intent/capability/argocd-missing-url-token", rep.issues.count { it.code == "MISSING_SYSTEM_CONFIG" } >= 2)
        try {
            IntentToAstPlanner(ModuleRegistry.fromDirectory(java.io.File("modules"), includeDefaults = true)).plan(intent)
            H.ok("intent/lowering/stops-on-capability-errors", false)
        } catch (e: Exception) {
            H.ok("intent/lowering/stops-on-capability-errors", e.message?.contains("Intent validation failed") == true)
        }
    }

    H.scenario {
        val intent = load("""
            kind: FlowIntentDocument
            name: bad-secret
            systems:
              - name: argo
                type: argocd
                config: { url: secret:ARGOCD_URL, token: plain-token }
        """.trimIndent())
        val rep = IntentCapabilityValidator(ModuleRegistry.fromDirectory(java.io.File("modules"), includeDefaults = true)).validate(intent)
        H.ok("intent/capability/sensitive-config-secret-ref", rep.issues.any { it.code == "SECRET_CONFIG_NOT_SECRET_REF" })
    }

    H.scenario {
        val intent = load("""
            kind: FlowIntentDocument
            name: structured-values
            workflows:
              - name: sync
                kind: DATA_PIPELINE
                steps:
                  - id: sync
                    capability: DATA_SYNC
                    params:
                      source: crm
                      destination: warehouse
                      filters: { status: active, region: eu }
                      batches: [small, medium]
        """.trimIndent())
        val params = intent.workflows.first().steps.first().params
        H.ok("intent/yaml/structured-object-preserved", params["filters"] is org.flowlang.intent.IntentObject)
        H.ok("intent/yaml/structured-list-preserved", params["batches"] is org.flowlang.intent.IntentList)
        val ast = IntentToAstPlanner(ModuleRegistry.fromDirectory(java.io.File("modules"), includeDefaults = true)).plan(intent)
        val action = ast.flow.steps.first() as org.flowlang.ast.ActionNode
        H.ok("intent/lowering/object-to-map-literal", action.params["filters"] is org.flowlang.ast.MapLiteralNode)
        H.ok("intent/lowering/list-to-list-literal", action.params["batches"] is org.flowlang.ast.ListLiteralNode)
    }

}
