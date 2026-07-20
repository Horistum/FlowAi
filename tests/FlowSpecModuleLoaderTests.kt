import java.io.File
import org.flowlang.ast.BinaryExpressionNode
import org.flowlang.modules.ModuleYamlLoader

fun modulesDir(): File {
    val candidates = listOf("modules", "/home/claude/flow-prod/modules")
    return candidates.map(::File).firstOrNull { it.isDirectory } ?: File("modules")
}

fun moduleLoaderTests() {
    val dir = modulesDir()
    H.ok("mod/dir-exists", dir.isDirectory)
    val loaded = ModuleYamlLoader.loadDirectory(dir).associateBy { it.name }
    val expected = listOf("argocd", "database", "docker", "git", "helm", "kubernetes", "notify", "rest", "shell")
    for (name in expected) H.ok("mod/loaded/$name", loaded.containsKey(name))

    H.eq("mod/rest-systemtype", loaded["rest"]!!.systemTypes.containsKey("rest"), true)
    H.eq("mod/rest-token-sensitive", loaded["rest"]!!.systemTypes["rest"]!!.input["token"]?.sensitive, true)
    H.eq("mod/notify-two-systemtypes", loaded["notify"]!!.systemTypes.size, 2)
    H.eq("mod/shell-run-required", loaded["shell"]!!.actions["run"]!!.input["command"]?.required, true)
    H.eq("mod/rest-call-required", loaded["rest"]!!.actions["call"]!!.input["method"]?.required, true)
    H.eq("mod/db-upsert-required", loaded["database"]!!.actions["upsert"]!!.input["values"]?.required, true)
    H.eq("mod/k8s-delete-destructive", loaded["kubernetes"]!!.actions["delete"]!!.safety.destructive, true)
    H.eq("mod/db-delete-destructive", loaded["database"]!!.actions["delete"]!!.safety.destructive, true)
    H.eq("mod/k8s-get-not-destructive", loaded["kubernetes"]!!.actions["get"]!!.safety.destructive, false)
    H.ok("mod/shell-executes", loaded["shell"]!!.actions["run"]!!.effects.executes.any { it.contains("shell") })
    H.ok("mod/rest-network", loaded["rest"]!!.actions["call"]!!.effects.network.isNotEmpty())

    val argo = loaded["argocd"]!!.actions["sync"]!!
    H.eq("mod/argocd-errors-count", argo.errors.size, 2)
    H.eq("mod/argocd-error-condition-count", argo.errors.values.map { it.condition }.size, 2)
    H.ok("mod/argocd-error-binary", argo.errors["unhealthy"]?.condition is BinaryExpressionNode)
    H.eq("mod/argocd-idempotent", argo.idempotent, "true")
    H.eq("mod/argocd-output-health", argo.output.containsKey("health"), true)
    H.eq("mod/argocd-token-sensitive", loaded["argocd"]!!.systemTypes["argocd"]!!.input["token"]?.sensitive, true)

    run {
        val module = ModuleYamlLoader.loadText(
            """
            kind: FlowModule
            name: custom
            version: "2.0"
            description: "Synthetic inline module"
            systemTypes:
              custom:
                input: {}
            actions:
              ping:
                kind: action
                targetTypes:
                  - custom
                input:
                  host:
                    type: text
                    required: true
                output:
                  ok:
                    type: boolean
                effects:
                  network:
                    - custom.endpoint
                safety:
                  destructive: false
            """.trimIndent()
        )
        H.eq("mod/inline-name", module.name, "custom")
        H.eq("mod/inline-version", module.version, "2.0")
        H.eq("mod/inline-required", module.actions["ping"]!!.input["host"]?.required, true)
    }

    try {
        ModuleYamlLoader.loadText("kind: NotAModule\nname: x")
        H.ok("mod/bad-kind-rejected", false)
    } catch (_: ModuleYamlLoader.LoadException) {
        H.ok("mod/bad-kind-rejected", true)
    }
}
