import org.flowlang.ast.*
import org.flowlang.parser.*
import org.flowlang.planner.*
import org.flowlang.validator.*
import java.io.File

/* ============================ test harness ============================ */
object H {
    var total = 0
    var passed = 0
    val fails = ArrayList<String>()
    private var scenarioSeq = 0
    fun reset() { total = 0; passed = 0; fails.clear(); scenarioSeq = 0 }
    fun ok(name: String, cond: Boolean) { total++; if (cond) passed++ else fails.add(name) }
    fun <T> eq(name: String, actual: T, expected: T) = ok("$name [exp=$expected act=$actual]", actual == expected)
    /**
     * Runs one isolated scenario. An unexpected exception is recorded as a
     * failure instead of aborting the whole group, so every scenario still runs
     * and all failures surface in a single pass (no fail-fast masking).
     */
    fun scenario(block: () -> Unit) {
        scenarioSeq++
        try {
            block()
        } catch (e: Throwable) {
            total++
            fails.add("scenario#$scenarioSeq :: unexpected exception: ${e.message}")
        }
    }
    fun report(): Boolean {
        println("\n================ TEST SUMMARY ================")
        println("total=$total passed=$passed failed=${fails.size}")
        if (fails.isNotEmpty()) {
            println("---- failures ----")
            fails.take(80).forEach { println("  FAIL: $it") }
            if (fails.size > 80) println("  ... and ${fails.size - 80} more")
        }
        return fails.isEmpty()
    }
}

/* ============================ helpers ============================ */
fun dollarize(s: String) = s.replace("§", "$")
fun expr(src: String): ExpressionNode = ExpressionParser.parseSource(dollarize(src))
fun doc(src: String): FlowDocument = FlowParser().parse(dollarize(src))
fun stmts(stepsSrc: String): List<StatementNode> = doc("flow \"t\" { steps {\n$stepsSrc\n} }").flow.steps
fun firstStmt(stepsSrc: String): StatementNode = stmts(stepsSrc).first()
fun validateSrc(src: String): ValidationReport = FlowValidator().validate(doc(src))
fun planSrc(src: String): ExecutionPlan = FlowPlanner().plan(doc(src))

fun parseOk(name: String, src: String) {
    try { doc(src); H.ok(name, true) } catch (e: Exception) { H.ok("$name :: ${e.message}", false) }
}
fun parseFail(name: String, src: String) {
    try { doc(src); H.ok("$name (expected parse failure but succeeded)", false) } catch (e: Exception) { H.ok(name, true) }
}
fun exprOk(name: String, src: String) {
    try { expr(src); H.ok(name, true) } catch (e: Exception) { H.ok("$name :: ${e.message}", false) }
}
fun exprFail(name: String, src: String) {
    try { expr(src); H.ok("$name (expected expr failure)", false) } catch (e: Exception) { H.ok(name, true) }
}
fun hasError(rep: ValidationReport, code: String) = rep.issues.any { it.level == "error" && it.code == code }
fun hasWarn(rep: ValidationReport, code: String) = rep.issues.any { it.level == "warning" && it.code == code }

/* ============================ test groups ============================ */

fun lexerTests() {
    // comments
    parseOk("lex/hash-comment", "# a comment\nversion \"1.0\"\nflow \"x\" { steps { } }")
    parseOk("lex/slash-comment", "// a comment\nflow \"x\" { steps { } }")
    parseOk("lex/trailing-hash", "flow \"x\" { steps { } } # trailing")
    parseOk("lex/trailing-slash", "flow \"x\" { steps { } } // trailing")
    // trailing comment after a value must not corrupt it (B-3)
    run {
        val s = stmts("set y = 5 # five")
        H.eq("lex/trailing-on-value", (s[0] as SetNode).name, "y")
    }
    // braces inside strings must not affect block depth (B-2)
    run {
        val st = firstStmt("shell.run local { command: \"echo {hi}\" }") as ActionNode
        H.eq("lex/brace-in-string", (st.params["command"] as StringLiteralNode).value, "echo {hi}")
    }
    // single-line block (B-1)
    run {
        val st = firstStmt("git.checkout repo { } -> source") as ActionNode
        H.ok("lex/single-line-empty-block", st.params.isEmpty() && st.result?.name == "source")
    }
    // escapes
    H.eq("lex/escape-quote", (expr("\"a\\\"b\"") as StringLiteralNode).value, "a\"b")
    H.eq("lex/escape-newline", (expr("\"a\\nb\"") as StringLiteralNode).value, "a\nb")
    // numbers
    H.eq("lex/int", (expr("42") as NumberLiteralNode).isInteger, true)
    H.eq("lex/decimal", (expr("3.14") as NumberLiteralNode).isInteger, false)
    H.eq("lex/negative", (expr("-7") as NumberLiteralNode).value, -7.0)
    // dot vs decimal point: member access
    H.ok("lex/member-not-decimal", expr("a.b") is ReferenceNode)
    // operators tokens
    parseOk("lex/arrow", "flow \"x\" { steps { shell.run l { command: \"x\" } -> r } }")
}

fun expressionTests() {
    val comparators = listOf("==", "!=", ">", ">=", "<", "<=")
    for (op in comparators) {
        val e = expr("a.b $op c")
        H.ok("expr/cmp/$op", e is BinaryExpressionNode && e.operator == op)
    }
    val textops = listOf("in", "contains", "matches", "startsWith", "endsWith")
    for (op in textops) {
        val e = expr("a.b $op c")
        H.ok("expr/text/$op", e is BinaryExpressionNode && e.operator == op)
    }
    // logical
    run {
        val e = expr("a and b")
        H.ok("expr/and", e is LogicalExpressionNode && e.operator == "and" && e.operands.size == 2)
    }
    run {
        val e = expr("a or b or c")
        H.ok("expr/or-chain", e is LogicalExpressionNode && e.operator == "or" && e.operands.size == 3)
    }
    run {
        // precedence: a and b or c  ==> or(and(a,b), c)
        val e = expr("a and b or c") as LogicalExpressionNode
        H.eq("expr/prec-top-or", e.operator, "or")
        H.ok("expr/prec-nested-and", e.operands[0] is LogicalExpressionNode && (e.operands[0] as LogicalExpressionNode).operator == "and")
    }
    run {
        val e = expr("a or b and c") as LogicalExpressionNode
        H.eq("expr/prec-top-or2", e.operator, "or")
        H.ok("expr/prec-nested-and2", e.operands[1] is LogicalExpressionNode)
    }
    // prefix not
    run {
        val e = expr("not a.ok")
        H.ok("expr/prefix-not", e is UnaryExpressionNode && e.operator == "not")
    }
    run {
        val e = expr("not a and b") as LogicalExpressionNode
        H.ok("expr/not-and", e.operator == "and" && e.operands[0] is UnaryExpressionNode)
    }
    // postfix empty / exists
    run {
        val e = expr("x empty")
        H.ok("expr/empty", e is UnaryPostfixExpressionNode && e.operator == "empty")
    }
    run {
        val e = expr("x exists")
        H.ok("expr/exists", e is UnaryPostfixExpressionNode && e.operator == "exists")
    }
    run {
        // not empty => not(empty(x))
        val e = expr("json.items not empty")
        H.ok("expr/not-empty", e is UnaryExpressionNode && e.operand is UnaryPostfixExpressionNode)
    }
    run {
        // not contains => not(contains(a,b))
        val e = expr("text not contains \"x\"")
        H.ok("expr/not-contains", e is UnaryExpressionNode && e.operand is BinaryExpressionNode)
    }
    // literals
    H.ok("expr/true", expr("true").let { it is BooleanLiteralNode && it.value })
    H.ok("expr/false", expr("false").let { it is BooleanLiteralNode && !it.value })
    H.ok("expr/null", expr("null") is NullLiteralNode)
    // identifier literal vs reference
    H.ok("expr/ident-literal-GET", expr("GET") is IdentifierLiteralNode)
    H.ok("expr/ident-literal-POST", expr("POST") is IdentifierLiteralNode)
    H.ok("expr/reference-lower", expr("customers") is ReferenceNode)
    H.ok("expr/reference-path", (expr("a.b.c") as ReferenceNode).path.size == 3)
    // safe nav
    run {
        val r = expr("a?.b?.c") as ReferenceNode
        H.ok("expr/safe-nav", r.safe && r.path == listOf("a", "b", "c"))
    }
    // index
    H.ok("expr/index", expr("a.b[0]") is IndexExpressionNode)
    // lists & maps
    H.eq("expr/list", (expr("[1, 2, 3]") as ListLiteralNode).items.size, 3)
    H.eq("expr/empty-list", (expr("[]") as ListLiteralNode).items.size, 0)
    H.eq("expr/map", (expr("{ a: 1, b: 2 }") as MapLiteralNode).entries.size, 2)
    // calls
    H.eq("expr/call-empty", (expr("count()") as CallExpressionNode).args.size, 0)
    H.eq("expr/call-args", (expr("max(a, b)") as CallExpressionNode).args.size, 2)
    // secret
    H.eq("expr/secret", (expr("secret(\"TOKEN\")") as SecretRefNode).name, "TOKEN")
    // grouping
    run {
        val e = expr("(a or b) and c") as LogicalExpressionNode
        H.eq("expr/group", e.operator, "and")
    }
    // aggregate-style call with where
    H.ok("expr/call-where", expr("count(where item.active == true)") is CallExpressionNode)
    // error cases
    exprFail("expr/dangling-op", "a ==")
    exprFail("expr/bad-not", "a not 5")
}

fun templateTests() {
    run {
        val e = expr("\"hello\"")
        H.ok("tmpl/plain", e is StringLiteralNode)
    }
    run {
        val e = expr(dollarize("\"a§{x}b\"")) as TemplateStringNode
        H.eq("tmpl/parts", e.parts.size, 3)
        H.ok("tmpl/part-mid-ref", e.parts[1] is ReferenceNode)
    }
    run {
        val e = expr(dollarize("\"§{x}\"")) as TemplateStringNode
        H.ok("tmpl/only-ref", e.parts.size == 1 && e.parts[0] is ReferenceNode)
    }
    run {
        val e = expr(dollarize("\"§{a}-§{b}\"")) as TemplateStringNode
        H.eq("tmpl/two-refs", e.parts.count { it is ReferenceNode }, 2)
    }
    run {
        val e = expr(dollarize("\"§{secret(\\\"S\\\")}\"")) as TemplateStringNode
        H.ok("tmpl/sensitive", e.sensitive)
    }
    run {
        // nested member in template
        val e = expr(dollarize("\"total: §{summary.total}\"")) as TemplateStringNode
        H.ok("tmpl/nested-member", e.parts.any { it is ReferenceNode && it.path == listOf("summary", "total") })
    }
}
