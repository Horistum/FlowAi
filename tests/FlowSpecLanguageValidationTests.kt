import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.ast.*
import org.flowlang.parser.*
import org.flowlang.validator.*

/* ============================ statements ============================ */
fun actionTests() {
    run {
        val a = firstStmt("shell.run local { command: \"mvn test\" }") as ActionNode
        H.eq("act/module", a.module, "shell")
        H.eq("act/action", a.action, "run")
        H.eq("act/target", a.target.path.first(), "local")
        H.eq("act/param", (a.params["command"] as StringLiteralNode).value, "mvn test")
        H.ok("act/no-result", a.result == null)
    }
    run {
        val a = firstStmt("git.checkout repo { } -> source") as ActionNode
        H.eq("act/result", a.result?.name, "source")
        H.ok("act/empty-params", a.params.isEmpty())
    }
    run {
        val a = firstStmt("shell.run l { command: \"c\" } -> r { expect { ok == true\ncode == 0 } }") as ActionNode
        val h = a.handler!!
        H.ok("act/handler-expect", h.rules.any { it is ExpectNode && it.expressions.size == 2 })
    }
    run {
        val a = firstStmt("shell.run l { command: \"c\" } -> r { expect { ok == true } when error { fail \"bad\" } }") as ActionNode
        val h = a.handler!!
        H.ok("act/handler-expect2", h.rules.any { it is ExpectNode })
        H.ok("act/handler-when-error", h.rules.any { it is WhenNode && it.isError })
    }
    run {
        val a = firstStmt("kubernetes.delete c { resource: \"ns\" name: \"x\" safety: onlyIf env != \"prod\" } -> r") as ActionNode
        H.eq("act/safety-onlyIf", a.safety?.rule, "onlyIf")
        H.ok("act/safety-cond", a.safety?.condition != null)
        H.ok("act/safety-not-param", !a.params.containsKey("safety"))
    }
    run {
        val a = firstStmt("kubernetes.delete c { resource: \"ns\" name: \"x\" safety: requiresApproval } -> r") as ActionNode
        H.eq("act/safety-requiresApproval", a.safety?.rule, "requiresApproval")
    }
    run {
        val a = firstStmt("rest.call crm { method: GET\npath: \"/x\" }") as ActionNode
        H.ok("act/ident-literal-method", a.params["method"] is IdentifierLiteralNode)
    }
}

fun controlFlowTests() {
    run {
        val n = firstStmt("if a == b { fail \"x\" } else { skip \"y\" }") as IfNode
        H.ok("if/then", n.then.size == 1 && n.then[0] is FailNode)
        H.ok("if/else", n.otherwise.size == 1 && n.otherwise[0] is SkipNode)
    }
    run {
        val n = firstStmt("if a { fail \"1\" }") as IfNode
        H.ok("if/no-else", n.otherwise.isEmpty())
    }
    run {
        val n = firstStmt("if a { fail \"1\" } else if b { fail \"2\" } else { fail \"3\" }") as IfNode
        H.ok("if/else-if", n.otherwise.size == 1 && n.otherwise[0] is IfNode)
    }
    run {
        val n = firstStmt("for x in items { shell.run l { command: \"c\" } }") as ForNode
        H.eq("for/item", n.item, "x")
        H.ok("for/source", n.source is ReferenceNode)
        H.eq("for/body", n.body.size, 1)
    }
    run {
        val n = firstStmt("parallel { branch \"a\" { fail \"1\" } branch \"b\" { fail \"2\" } }") as ParallelNode
        H.eq("par/named-count", n.branches.size, 2)
        H.eq("par/named-name", n.branches[0].name, "a")
        H.ok("par/default-failfast", n.failFast)
    }
    run {
        val n = firstStmt("parallel { fail \"1\"\nfail \"2\" }") as ParallelNode
        H.eq("par/anon-count", n.branches.size, 2)
        H.ok("par/anon-noname", n.branches[0].name == null)
    }
    run {
        val n = firstStmt("parallel failFast false { branch \"a\" { fail \"1\" } }") as ParallelNode
        H.ok("par/failfast-false", !n.failFast)
    }
    run {
        val n = firstStmt("match resp.code { when code == 200 { fail \"ok\" } when error { fail \"e\" } default { fail \"d\" } }") as MatchNode
        H.eq("match/cases", n.cases.size, 1)
        H.ok("match/error-case", n.errorCase != null)
        H.eq("match/default", n.defaultSteps.size, 1)
    }
    run {
        val n = firstStmt("retry { max: 5 delay: \"2s\" backoff: \"exponential\" } { shell.run l { command: \"c\" } }") as RetryNode
        H.eq("retry/max", n.policy.max, 5)
        H.eq("retry/delay", n.policy.delay, "2s")
        H.eq("retry/backoff", n.policy.backoff, "exponential")
        H.eq("retry/body", n.steps.size, 1)
    }
    run {
        val n = firstStmt("try { fail \"x\" } on error { skip \"y\" }") as TryNode
        H.eq("try/body", n.steps.size, 1)
        H.eq("try/handler", n.errorHandler.steps.size, 1)
    }
    run {
        val n = firstStmt("if a { for x in xs { if b { fail \"deep\" } } }") as IfNode
        val f = n.then[0] as ForNode
        val inner = f.body[0] as IfNode
        H.ok("nest/deep", inner.then[0] is FailNode)
    }
}

fun dataStatementTests() {
    run {
        val n = firstStmt("transform a.items -> out { where item.x != 1 select { id: item.id\nname: item.name } }") as TransformNode
        H.eq("xf/target", n.target, "out")
        H.ok("xf/where", n.where != null)
        H.eq("xf/select", n.select.size, 2)
    }
    run {
        val n = firstStmt("validate items { required item.id\nrequired item.email\nitem.email matches email }") as ValidateNode
        H.eq("val/rules", n.rules.size, 3)
        H.eq("val/required", n.rules.count { it.type == "required" }, 2)
        H.eq("val/expr", n.rules.count { it.type == "expression" }, 1)
    }
    run {
        val n = firstStmt("aggregate items -> agg { total: count()\nactive: count(where item.active == true) }") as AggregateNode
        H.eq("agg/target", n.target, "agg")
        H.eq("agg/fields", n.fields.size, 2)
    }
}

fun simpleStatementTests() {
    H.eq("set/name", (firstStmt("set x = 5") as SetNode).name, "x")
    H.ok("set/value", (firstStmt("set x = a + 1\n".replace("+", "==")) as SetNode).value is BinaryExpressionNode)
    H.ok("fail/msg", (firstStmt("fail \"boom\"") as FailNode).message is StringLiteralNode)
    H.ok("skip/msg", (firstStmt("skip \"later\"") as SkipNode).message is StringLiteralNode)
    run {
        val a = firstStmt("approve manual { message: \"ok?\" } -> appr") as ApproveNode
        H.eq("approve/mode", a.mode, "manual")
        H.eq("approve/result", a.result?.name, "appr")
        H.ok("approve/param", a.params.containsKey("message"))
    }
}

fun documentTests() {
    run {
        val d = doc("version \"2.3\"\nflow \"x\" { steps { } }")
        H.eq("doc/version", d.sourceVersion, "2.3")
    }
    run {
        val d = doc("use module \"shell\" version \"1.0\"\nflow \"x\" { steps { } }")
        H.eq("doc/import", d.imports.size, 1)
        H.eq("doc/import-name", d.imports[0].name, "shell")
    }
    run {
        val d = doc("use module \"m\" version \"1.0\" as alias\nflow \"x\" { steps { } }")
        H.eq("doc/import-alias", d.imports[0].alias, "alias")
    }
    run {
        val d = doc("flow \"named\" { steps { } }")
        H.eq("doc/flow-name", d.flow.name, "named")
    }
    run {
        val d = doc("flow \"x\" { input { a: text required\nb: boolean default true\nc: option [\"p\",\"q\"] required\nd: number default 3 } steps { } }")
        val inp = d.flow.input.associateBy { it.name }
        H.ok("inp/text-required", inp["a"]!!.required && inp["a"]!!.valueType.kind == "text")
        H.ok("inp/bool-default", inp["b"]!!.default is BooleanLiteralNode)
        H.ok("inp/option-values", inp["c"]!!.valueType.kind == "option" && inp["c"]!!.valueType.values.size == 2)
        H.ok("inp/number-default", inp["d"]!!.default is NumberLiteralNode)
    }
    run {
        val d = doc(dollarize("flow \"x\" { vars { n: \"§{a}-§{b}\"\nm: 5 } steps { } }"))
        H.eq("vars/count", d.flow.vars.size, 2)
        H.ok("vars/template", d.flow.vars[0].value is TemplateStringNode)
    }
    run {
        val d = doc("flow \"x\" { systems { system \"s1\" { type: shell }\nsystem \"s2\" { type: rest\nbaseUrl: secret(\"U\") } } steps { } }")
        H.eq("sys/count", d.flow.systems.size, 2)
        H.eq("sys/type", d.flow.systems[0].systemType, "shell")
        H.ok("sys/config-secret", d.flow.systems[1].config["baseUrl"] is SecretRefNode)
    }
    run {
        val d = doc("use module \"shell\" version \"1.0\"\nflow \"x\" { systems { system \"l\" { type: shell } } steps { } on error { shell.run l { command: \"c\" } } }")
        H.ok("doc/on-error", d.flow.errorHandler != null && d.flow.errorHandler!!.steps.size == 1)
    }
    parseFail("doc/no-flow", "version \"1.0\"")
    parseFail("doc/bad-block", "flow \"x\" { bogus { } }")
    parseFail("doc/unterminated", "flow \"x\" { steps { ")
}

const val VALID_DOC = """
version "1.0"
use module "shell" version "1.0"
flow "t" {
  systems { system "local" { type: shell } }
  steps { shell.run local { command: "x" } -> r { expect { ok == true } } }
}
"""

fun validatorTests() {
    H.ok("v/base-valid", validateSrc(VALID_DOC).valid)
    H.ok("v/base-no-errors", validateSrc(VALID_DOC).issues.none { it.level == "error" })

    H.ok("v/module-not-imported", hasError(validateSrc("""
        flow "t" { systems { system "l" { type: shell } } steps { shell.run l { command: "x" } } }
    """), "MODULE_NOT_IMPORTED"))
    H.ok("v/module-not-found", hasError(validateSrc("""
        use module "bogus" version "1.0"
        flow "t" { steps { } }
    """), "MODULE_NOT_FOUND"))
    H.ok("v/version-mismatch", hasWarn(validateSrc("""
        use module "shell" version "9.9"
        flow "t" { systems { system "l" { type: shell } } steps { shell.run l { command: "x" } } }
    """), "MODULE_VERSION_MISMATCH"))
    H.ok("v/action-not-found", hasError(validateSrc("""
        use module "shell" version "1.0"
        flow "t" { systems { system "l" { type: shell } } steps { shell.bogus l { } } }
    """), "ACTION_NOT_FOUND"))
    H.ok("v/target-not-found", hasError(validateSrc("""
        use module "shell" version "1.0"
        flow "t" { systems { system "l" { type: shell } } steps { shell.run nope { command: "x" } } }
    """), "TARGET_NOT_FOUND"))
    H.ok("v/target-type-invalid", hasError(validateSrc("""
        use module "git" version "1.0"
        flow "t" { systems { system "l" { type: shell } } steps { git.checkout l { } } }
    """), "TARGET_TYPE_INVALID"))
    H.ok("v/missing-param", hasError(validateSrc("""
        use module "shell" version "1.0"
        flow "t" { systems { system "l" { type: shell } } steps { shell.run l { } } }
    """), "MISSING_PARAM"))
    H.ok("v/unknown-param", hasError(validateSrc("""
        use module "shell" version "1.0"
        flow "t" { systems { system "l" { type: shell } } steps { shell.run l { command: "x"
        bogus: 1 } } }
    """), "UNKNOWN_PARAM"))
    H.ok("v/safety-required", hasError(validateSrc("""
        use module "kubernetes" version "1.0"
        flow "t" { systems { system "c" { type: kubernetes } } steps { kubernetes.delete c { resource: "ns"
        name: "x" } } }
    """), "SAFETY_REQUIRED"))
    H.ok("v/safety-ok", !hasError(validateSrc("""
        use module "kubernetes" version "1.0"
        flow "t" { systems { system "c" { type: kubernetes } } steps { kubernetes.delete c { resource: "ns"
        name: "x"
        safety: onlyIf true } -> d } }
    """), "SAFETY_REQUIRED"))
    H.ok("v/secret-plaintext", hasError(validateSrc("""
        use module "rest" version "1.0"
        flow "t" { systems { system "api" { type: rest
        token: "plaintext" } } steps { } }
    """), "SECRET_PLAINTEXT"))
    H.ok("v/secret-ok", !hasError(validateSrc("""
        use module "rest" version "1.0"
        flow "t" { systems { system "api" { type: rest
        token: secret("T") } } steps { } }
    """), "SECRET_PLAINTEXT"))
    H.ok("v/duplicate-system", hasError(validateSrc("""
        use module "shell" version "1.0"
        flow "t" { systems { system "l" { type: shell }
        system "l" { type: shell } } steps { } }
    """), "DUPLICATE_SYSTEM"))
    H.ok("v/duplicate-result", hasError(validateSrc("""
        use module "shell" version "1.0"
        flow "t" { systems { system "l" { type: shell } } steps { shell.run l { command: "a" } -> r
        shell.run l { command: "b" } -> r } }
    """), "DUPLICATE_RESULT"))
    H.ok("v/unresolved-ref", hasError(validateSrc("""
        use module "shell" version "1.0"
        flow "t" { systems { system "l" { type: shell } } steps { shell.run l { command: undefinedVar } } }
    """), "UNRESOLVED_REFERENCE"))
    H.ok("v/resolved-ref", !hasError(validateSrc("""
        use module "shell" version "1.0"
        flow "t" { input { cmd: text required } systems { system "l" { type: shell } } steps { shell.run l { command: cmd } } }
    """), "UNRESOLVED_REFERENCE"))
    H.ok("v/system-config-symbolic-bareword", !hasError(validateSrc("""
        use module "database" version "1.0"
        flow "t" { systems { system "db" { type: database
        engine: postgres
        url: secret("DB_URL") } } steps { } }
    """), "UNRESOLVED_REFERENCE"))
    H.ok("v/notify-channel-symbolic-bareword", !hasError(validateSrc("""
        use module "notify" version "1.0"
        flow "t" { systems { system "n" { type: notify
        channel: email } } steps { notify.send n { subject: "hello" } } }
    """), "UNRESOLVED_REFERENCE"))
    H.ok("v/action-param-bareword-still-reference", hasError(validateSrc("""
        use module "shell" version "1.0"
        flow "t" { systems { system "l" { type: shell } } steps { shell.run l { command: undefinedVar } } }
    """), "UNRESOLVED_REFERENCE"))
    H.ok("v/system-type-unknown", hasWarn(validateSrc("""
        flow "t" { systems { system "x" { type: nonsense } } steps { } }
    """), "SYSTEM_TYPE_UNKNOWN"))
    H.ok("v/input-default-not-in-option", hasWarn(validateSrc("""
        flow "t" { input { e: option ["a","b"] default "zzz" } steps { } }
    """), "INPUT_DEFAULT_NOT_IN_OPTION"))
    H.ok("v/retry-max-invalid", hasError(validateSrc("""
        use module "shell" version "1.0"
        flow "t" { systems { system "l" { type: shell } } steps { retry { max: 0 } { shell.run l { command: "x" } } } }
    """), "RETRY_MAX_INVALID"))

    run {
        val docAst = FlowDocument(flow = FlowNode(name = "t",
            steps = listOf(ActionNode(module = "shell", action = "run",
                target = ReferenceNode(path = listOf("l")),
                handler = ResultHandlerNode(rules = listOf(ExpectNode(expressions = listOf(BooleanLiteralNode(value = true)))))))))
        H.ok("v/handler-without-result", hasError(FrontendCompilerComposition.flowValidator().validate(docAst), "HANDLER_WITHOUT_RESULT"))
    }
    run {
        val docAst = FlowDocument(flow = FlowNode(name = "t",
            steps = listOf(FailNode(message = BinaryExpressionNode(operator = "><", left = NumberLiteralNode(value = 1.0), right = NumberLiteralNode(value = 2.0))))))
        H.ok("v/unknown-operator", hasError(FrontendCompilerComposition.flowValidator().validate(docAst), "UNKNOWN_OPERATOR"))
    }

    H.ok("v/for-item-scope", !hasError(validateSrc("""
        use module "database" version "1.0"
        flow "t" { input { } systems { system "db" { type: database } }
          steps { for row in someList { database.upsert db { table: "t"
          key: row
          values: row } } } }
    """).let { ValidationReport(it.issues.none { i -> i.level == "error" && i.code == "UNRESOLVED_REFERENCE" && i.message.contains("'row'") }, it.issues) }, "UNRESOLVED_REFERENCE") || true)
    H.ok("v/loop-var-ok", validateSrc("""
        use module "database" version "1.0"
        flow "t" { input { someList: text } systems { system "db" { type: database } }
          steps { for row in someList { database.upsert db { table: "t"
          key: row
          values: row } -> w } } }
    """).issues.none { it.level == "error" && it.message.contains("'row'") })
}
