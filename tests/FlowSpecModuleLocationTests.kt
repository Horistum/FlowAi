import org.flowlang.ast.*
import org.flowlang.modules.*
import org.flowlang.parser.*
import org.flowlang.planner.FlowPlanner
import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException
import org.flowlang.validator.*
import java.io.File

/* ============================ module descriptors ============================ */
fun modulesDir(): File {
    val candidates = listOf("modules", "/home/claude/flow-prod/modules")
    return candidates.map { File(it) }.firstOrNull { it.isDirectory } ?: File("modules")
}

fun yamlParsingTests() {
    H.eq("yaml/scalar-string", FlowYaml.readMap("a: hello")["a"], "hello")
    H.eq("yaml/quoted", FlowYaml.readMap("a: \"1.0\"")["a"], "1.0")
    H.eq("yaml/bool-true", FlowYaml.readMap("a: true")["a"], true)
    H.eq("yaml/bool-false", FlowYaml.readMap("a: false")["a"], false)
    H.eq("yaml/int", FlowYaml.readMap("a: 42")["a"], 42)
    H.eq("yaml/null", FlowYaml.readMap("a: null")["a"], null)
    run {
        @Suppress("UNCHECKED_CAST")
        val nested = FlowYaml.readMap("a:\n  b:\n    c: 1")["a"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val b = nested["b"] as Map<String, Any?>
        H.eq("yaml/nested", b["c"], 1)
    }
    run {
        @Suppress("UNCHECKED_CAST")
        val list = FlowYaml.readMap("items:\n  - x\n  - y\n  - z")["items"] as List<Any?>
        H.eq("yaml/list", list.size, 3)
        H.eq("yaml/list-first", list[0], "x")
    }
    run {
        @Suppress("UNCHECKED_CAST")
        val flowMap = FlowYaml.readMap("params: { app: demo, replicas: 2 }")["params"] as Map<String, Any?>
        H.eq("yaml/flow-map", flowMap["app"], "demo")
        H.eq("yaml/flow-map-number", flowMap["replicas"], 2)
    }
    run {
        @Suppress("UNCHECKED_CAST")
        val flowList = FlowYaml.readMap("items: [x, y, z]")["items"] as List<Any?>
        H.eq("yaml/flow-list", flowList, listOf("x", "y", "z"))
    }
    run {
        val m = FlowYaml.readMap("a: 1 # comment\n# whole line\nb: 2")
        H.eq("yaml/comment-strip", m["a"], 1)
        H.eq("yaml/comment-kept-key", m["b"], 2)
    }
    run {
        val m = FlowYaml.readMap("a: \"has # hash\"")
        H.eq("yaml/hash-in-quotes", m["a"], "has # hash")
    }
    try { FlowYaml.readMap("a:\n\tb: 1", "tabbed-yaml"); H.ok("yaml/tab-rejected", false) }
    catch (e: FlowYamlException) { H.ok("yaml/tab-rejected", e.message?.contains("tabbed-yaml") == true) }
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
        val m = ModuleYamlLoader.loadText("kind: FlowModule\nname: custom\nversion: \"2.0\"\nactions:\n  ping:\n    targetTypes:\n      - custom\n    input:\n      host:\n        type: text\n        required: true")
        H.eq("mod/inline-name", m.name, "custom")
        H.eq("mod/inline-version", m.version, "2.0")
        H.eq("mod/inline-required", m.actions["ping"]!!.input["host"]?.required, true)
    }
    try { ModuleYamlLoader.loadText("kind: NotAModule\nname: x"); H.ok("mod/bad-kind-rejected", false) }
    catch (e: ModuleYamlLoader.LoadException) { H.ok("mod/bad-kind-rejected", true) }
}

fun descriptorRegistryParityTests() {
    val reg = ModuleRegistry.fromDirectory(modulesDir(), includeDefaults = false)
    val validator = FlowValidator(reg)
    val planner = FlowPlanner(reg)
    val files = listOf("api-sync.flow", "build-test.flow", "complex-devops-flow.flow", "deploy-with-approval.flow", "kubernetes-cleanup.flow")
    for (file in files) {
        val f = exampleFile(file)
        try {
            val d = FlowParser().parse(f)
            val rep = validator.validate(d)
            H.ok("mod-parity/$file/valid", rep.valid)
            H.ok("mod-parity/$file/no-errors", rep.issues.none { it.level == "error" })
            H.ok("mod-parity/$file/plan", planner.plan(d).tasks.isNotEmpty())
        } catch (e: Exception) {
            H.ok("mod-parity/$file :: ${e.message}", false)
        }
    }
    val rep = FlowValidator(reg).validate(doc("""
        use module "kubernetes" version "1.0"
        flow "t" { systems { system "c" { type: kubernetes } } steps { kubernetes.delete c { resource: "ns"
        name: "x" } } }
    """))
    H.ok("mod-parity/safety-enforced", rep.issues.any { it.level == "error" && it.code == "SAFETY_REQUIRED" })
}

fun issueOf(rep: ValidationReport, code: String): ValidationIssue? =
    rep.issues.firstOrNull { it.code == code }

fun sourceLocationTests() {
    run {
        val r = expr("a.b") as ReferenceNode
        H.ok("loc/ref-present", r.location != null)
        H.eq("loc/ref-line", r.location?.line, 1)
        H.ok("loc/ref-col", (r.location?.column ?: 0) >= 1)
    }
    H.ok("loc/binary-present", (expr("a == b") as BinaryExpressionNode).location != null)
    H.ok("loc/logical-present", (expr("a and b") as LogicalExpressionNode).location != null)

    run {
        val src = listOf(
            "use module \"shell\" version \"1.0\"",
            "flow \"t\" {",
            "  systems { system \"l\" { type: shell } }",
            "  steps {",
            "    shell.run l { command: undefinedVar }",
            "  }",
            "}"
        ).joinToString("\n")
        val i = issueOf(validateSrc(src), "UNRESOLVED_REFERENCE")
        H.ok("loc/unresolved-present", i?.location != null)
        H.eq("loc/unresolved-line", i?.location?.line, 5)
    }
    run {
        val src = listOf(
            "use module \"shell\" version \"1.0\"",
            "flow \"t\" {",
            "  systems { system \"l\" { type: shell } }",
            "  steps {",
            "    shell.run l { }",
            "  }",
            "}"
        ).joinToString("\n")
        val i = issueOf(validateSrc(src), "MISSING_PARAM")
        H.ok("loc/missing-param-present", i?.location != null)
        H.eq("loc/missing-param-line", i?.location?.line, 5)
    }
    run {
        val src = listOf(
            "use module \"rest\" version \"1.0\"",
            "flow \"t\" {",
            "  systems {",
            "    system \"api\" {",
            "      type: rest",
            "      token: \"plain\"",
            "    }",
            "  }",
            "  steps { }",
            "}"
        ).joinToString("\n")
        val i = issueOf(validateSrc(src), "SECRET_PLAINTEXT")
        H.ok("loc/secret-present", i?.location != null)
        H.eq("loc/secret-line", i?.location?.line, 4)
    }
    run {
        val src = listOf(
            "use module \"shell\" version \"1.0\"",
            "flow \"t\" {",
            "  systems { system \"l\" { type: shell } }",
            "  steps {",
            "    retry { max: 0 } {",
            "      shell.run l { command: \"x\" }",
            "    }",
            "  }",
            "}"
        ).joinToString("\n")
        val i = issueOf(validateSrc(src), "RETRY_MAX_INVALID")
        H.ok("loc/retry-present", i?.location != null)
        H.eq("loc/retry-line", i?.location?.line, 5)
    }
    run {
        val src = listOf(
            "use module \"shell\" version \"1.0\"",
            "flow \"t\" {",
            "  systems {",
            "    system \"l\" { type: shell }",
            "    system \"l\" { type: shell }",
            "  }",
            "  steps { }",
            "}"
        ).joinToString("\n")
        val i = issueOf(validateSrc(src), "DUPLICATE_SYSTEM")
        H.ok("loc/dup-system-present", i?.location != null)
        H.eq("loc/dup-system-line", i?.location?.line, 5)
    }
    run {
        try {
            doc("flow \"t\" {\n  steps {\n    fail\n  }\n}")
            doc("flow \"t\" { steps { @bad } }")
            H.ok("loc/parse-exception", false)
        } catch (e: ParseException) {
            H.ok("loc/parse-exception", e.line >= 1 && e.column >= 1)
        }
    }
    run {
        val rep = FlowValidator().validate(FlowParser().parse(exampleFile("api-sync.flow")))
        H.ok("loc/examples-no-errors", rep.issues.none { it.level == "error" })
    }
}
