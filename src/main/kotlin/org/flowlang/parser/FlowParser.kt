package org.flowlang.parser

import org.flowlang.io.BoundedIo
import org.flowlang.io.InputLimits

import org.flowlang.ast.*
import java.io.File

/**
 * Recursive-descent parser for the Flow language (docs/02, docs/08).
 *
 * Brace-based, newline-tolerant. Every construct described in the draft is parsed
 * into the canonical AST. Anything that cannot be parsed raises a [ParseException]
 * with a source location — the parser never silently drops a construct.
 */
class FlowParser {
    private var statementNestingDepth = 0

    companion object {
        internal const val MAX_STATEMENT_NESTING_DEPTH = InputLimits.MAX_FLOW_STATEMENT_DEPTH
    }

    fun parse(file: File): FlowDocument = parse(BoundedIo.readText(file), file.path)

    fun parse(source: String, sourceFile: String? = null): FlowDocument {
        val ts = TokenStream.fromSource(source)
        val declarations = DeclarationOccurrences()
        var version = "1.0"
        val imports = mutableListOf<ModuleImportNode>()
        var flow: FlowNode? = null

        ts.skipSeparators()
        while (!ts.atEnd()) {
            val t = ts.peek()
            when {
                t.type == TokenType.IDENT && t.text == "version" -> {
                    declarations.declare("version", t, "version")
                    ts.next(); version = ts.expect(TokenType.STRING, "version string").rawValue!!
                }
                t.type == TokenType.IDENT && t.text == "use" -> imports += parseImport(ts)
                t.type == TokenType.IDENT && t.text == "flow" -> {
                    if (flow != null) throw ParseException("only one flow per document is supported", t.line, t.column)
                    flow = parseFlow(ts)
                }
                else -> throw ParseException("unexpected ${t.type} '${t.text}' at top level", t.line, t.column)
            }
            ts.skipSeparators()
        }
        val resolved = flow ?: throw ParseException("missing 'flow' declaration", 1, 1)
        return FlowDocument(
            sourceVersion = version,
            imports = imports,
            flow = resolved,
            metadata = MetadataNode(sourceFile = sourceFile)
        )
    }

    private fun parseImport(ts: TokenStream): ModuleImportNode {
        val kw = ts.expectWord("use")
        ts.expectWord("module")
        val name = ts.expect(TokenType.STRING, "module name").rawValue!!
        ts.expectWord("version")
        val version = ts.expect(TokenType.STRING, "module version").rawValue!!
        var alias: String? = null
        if (ts.matchWord("as")) alias = ts.expect(TokenType.IDENT, "alias").text
        return ModuleImportNode(name = name, version = version, alias = alias, sourceLocation = kw.location())
    }

    private fun parseFlow(ts: TokenStream): FlowNode {
        ts.expectWord("flow")
        val name = ts.expect(TokenType.STRING, "flow name").rawValue!!
        ts.expect(TokenType.LBRACE, "'{'")
        val inputs = mutableListOf<InputNode>()
        val vars = mutableListOf<VariableNode>()
        val systems = mutableListOf<SystemNode>()
        val steps = mutableListOf<StatementNode>()
        var errorHandler: ErrorHandlerNode? = null
        val declarations = DeclarationOccurrences()

        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            val t = ts.peek()
            when {
                t.type == TokenType.IDENT && t.text == "input" -> { ts.next(); inputs += parseInputBlock(ts, inputs.size) }
                t.type == TokenType.IDENT && t.text == "vars" -> { ts.next(); vars += parseVarsBlock(ts, vars.size) }
                t.type == TokenType.IDENT && t.text == "systems" -> { ts.next(); systems += parseSystemsBlock(ts, systems.size) }
                t.type == TokenType.IDENT && t.text == "steps" -> { ts.next(); steps += parseStatementBlock(ts, "flow.steps", steps.size) }
                t.type == TokenType.IDENT && t.text == "on" -> {
                    declarations.declare("on error", t, "flow.errorHandler")
                    ts.next(); ts.expectWord("error")
                    errorHandler = ErrorHandlerNode(steps = parseStatementBlock(ts, "flow.errorHandler.steps"))
                }
                else -> throw ParseException("unexpected ${t.type} '${t.text}' in flow body", t.line, t.column)
            }
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return FlowNode(name = name, input = inputs, vars = vars, systems = systems, steps = steps, errorHandler = errorHandler)
    }

    private fun parseInputBlock(ts: TokenStream, offset: Int): List<InputNode> {
        ts.expect(TokenType.LBRACE, "'{'")
        val out = mutableListOf<InputNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            val nameTok = ts.expect(TokenType.IDENT, "input name")
            ts.expect(TokenType.COLON, "':'")
            val kind = ts.expect(TokenType.IDENT, "type").text
            val path = "flow.input[${offset + out.size}]"
            val modifiers = DeclarationOccurrences()
            val values = mutableListOf<ExpressionNode>()
            if (kind == "option") {
                ts.expect(TokenType.LBRACKET, "'['")
                ts.skipSeparators()
                while (!ts.check(TokenType.RBRACKET)) {
                    values += ts.atPath("$path.valueType.values[${values.size}]") { ExpressionParser(ts).parse() }
                    ts.skipSeparators()
                }
                ts.expect(TokenType.RBRACKET, "']'")
            }
            var required = false
            var default: ExpressionNode? = null
            while (ts.peek().type == TokenType.IDENT && ts.peek().text in setOf("required", "default")) {
                val modifier = ts.peek()
                modifiers.declare(modifier.text, modifier, "$path.${modifier.text}")
                if (ts.matchWord("required")) required = true
                else if (ts.matchWord("default")) default = ts.atPath("$path.default") { ExpressionParser(ts).parse() }
            }
            out += InputNode(
                name = nameTok.text,
                valueType = ValueTypeNode(kind = kind, values = values),
                required = required, default = default, sourceLocation = nameTok.location()
            )
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return out
    }

    private fun parseVarsBlock(ts: TokenStream, offset: Int): List<VariableNode> {
        ts.expect(TokenType.LBRACE, "'{'")
        val out = mutableListOf<VariableNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            val nameTok = ts.expect(TokenType.IDENT, "variable name")
            ts.expect(TokenType.COLON, "':'")
            val value = ts.atPath("flow.vars[${offset + out.size}].value") { ExpressionParser(ts).parse() }
            out += VariableNode(name = nameTok.text, value = value, sourceLocation = nameTok.location())
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return out
    }

    private fun parseSystemsBlock(ts: TokenStream, offset: Int): List<SystemNode> {
        ts.expect(TokenType.LBRACE, "'{'")
        val out = mutableListOf<SystemNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            val kw = ts.expectWord("system")
            val name = ts.expect(TokenType.STRING, "system name").rawValue!!
            ts.expect(TokenType.LBRACE, "'{'")
            var systemType: String? = null
            val config = LinkedHashMap<String, ExpressionNode>()
            val declarations = DeclarationOccurrences()
            val systemPath = "flow.systems[${offset + out.size}]"
            ts.skipSeparators()
            while (!ts.check(TokenType.RBRACE)) {
                val keyToken = ts.expect(TokenType.IDENT, "config key")
                val key = keyToken.text
                val path = if (key == "type") "$systemPath.type" else "$systemPath.config.$key"
                declarations.declare(key, keyToken, path)
                ts.expect(TokenType.COLON, "':'")
                val value = ts.atPath(path) { ExpressionParser(ts).parse() }
                if (key == "type") {
                    systemType = when (value) {
                        is IdentifierLiteralNode -> value.value
                        is ReferenceNode -> value.path.joinToString(".")
                        is StringLiteralNode -> value.value
                        else -> throw ParseException("system type must be a simple identifier", kw.line, kw.column)
                    }
                } else config[key] = value
                ts.skipSeparators()
            }
            ts.expect(TokenType.RBRACE, "'}'")
            out += SystemNode(
                name = name,
                systemType = systemType ?: throw ParseException("system '$name' has no type", kw.line, kw.column),
                config = config, sourceLocation = kw.location()
            )
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return out
    }

    private fun parseStatementBlock(
        ts: TokenStream,
        path: String = "${ts.diagnosticPath}.steps",
        offset: Int = 0
    ): List<StatementNode> {
        ts.expect(TokenType.LBRACE, "'{'")
        val out = mutableListOf<StatementNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            out += ts.atPath("$path[${offset + out.size}]") { parseStatement(ts) }
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return out
    }

    private fun parseStatement(ts: TokenStream): StatementNode {
        val t = ts.peek()
        if (t.type != TokenType.IDENT) throw ParseException("expected a statement but found ${t.type} '${t.text}'", t.line, t.column)
        if (statementNestingDepth >= MAX_STATEMENT_NESTING_DEPTH) {
            throw ParseException(
                "statement nesting exceeds maximum depth $MAX_STATEMENT_NESTING_DEPTH",
                t.line,
                t.column
            )
        }
        statementNestingDepth++
        try {
            return when (t.text) {
                "if" -> parseIf(ts)
                "for" -> parseFor(ts)
                "parallel" -> parseParallel(ts)
                "match" -> parseMatch(ts)
                "retry" -> parseRetry(ts)
                "try" -> parseTry(ts)
                "fail" -> { ts.next(); FailNode(message = ts.atPath("${ts.diagnosticPath}.message") { ExpressionParser(ts).parse() }) }
                "skip" -> { ts.next(); SkipNode(message = ts.atPath("${ts.diagnosticPath}.message") { ExpressionParser(ts).parse() }) }
                "set" -> parseSet(ts)
                "approve" -> parseApprove(ts)
                "transform" -> parseTransform(ts)
                "validate" -> parseValidate(ts)
                "aggregate" -> parseAggregate(ts)
                else -> parseAction(ts)
            }
        } finally {
            statementNestingDepth--
        }
    }

    private fun parseIf(ts: TokenStream): IfNode {
        ts.expectWord("if")
        val condition = ts.atPath("${ts.diagnosticPath}.condition") { ExpressionParser(ts).parse() }
        val then = parseStatementBlock(ts, "${ts.diagnosticPath}.then")
        var otherwise = emptyList<StatementNode>()
        val save = ts.index
        ts.skipNewlines()
        if (ts.matchWord("else")) {
            otherwise = if (ts.checkWord("if")) {
                listOf(ts.atPath("${ts.diagnosticPath}.otherwise[0]") { parseStatement(ts) })
            } else parseStatementBlock(ts, "${ts.diagnosticPath}.otherwise")
        } else {
            ts.seek(save)
        }
        return IfNode(condition = condition, then = then, otherwise = otherwise)
    }

    private fun parseFor(ts: TokenStream): ForNode {
        ts.expectWord("for")
        val item = ts.expect(TokenType.IDENT, "loop variable").text
        ts.expectWord("in")
        val source = ts.atPath("${ts.diagnosticPath}.source") { ExpressionParser(ts).parse() }
        val body = parseStatementBlock(ts, "${ts.diagnosticPath}.body")
        return ForNode(item = item, source = source, body = body)
    }

    private fun parseParallel(ts: TokenStream): ParallelNode {
        ts.expectWord("parallel")
        val failFast = if (ts.matchWord("failFast")) {
            val value = ts.expect(TokenType.IDENT, "true/false")
            when (value.text) {
                "true" -> true
                "false" -> false
                else -> throw ParseException(
                    "parallel.failFast must be 'true' or 'false' but found '${value.text}'",
                    value.line,
                    value.column
                )
            }
        } else {
            true
        }
        ts.expect(TokenType.LBRACE, "'{'")
        val branches = mutableListOf<ParallelBranchNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            if (ts.checkWord("branch")) {
                ts.next()
                val name = ts.expect(TokenType.STRING, "branch name").rawValue!!
                branches += ParallelBranchNode(name = name, steps = parseStatementBlock(ts, "${ts.diagnosticPath}.branches[${branches.size}].steps"))
            } else {
                branches += ParallelBranchNode(name = null, steps = listOf(
                    ts.atPath("${ts.diagnosticPath}.branches[${branches.size}].steps[0]") { parseStatement(ts) }
                ))
            }
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return ParallelNode(branches = branches, failFast = failFast)
    }

    private fun parseMatch(ts: TokenStream): MatchNode {
        ts.expectWord("match")
        val source = ts.atPath("${ts.diagnosticPath}.source") { ExpressionParser(ts).parse() }
        ts.expect(TokenType.LBRACE, "'{'")
        val cases = mutableListOf<WhenNode>()
        var errorCase: List<StatementNode>? = null
        var defaultSteps = emptyList<StatementNode>()
        val declarations = DeclarationOccurrences()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            when {
                ts.checkWord("when") -> {
                    ts.next()
                    if (ts.checkWord("error")) {
                        val key = ts.next()
                        declarations.declare("when error", key, "${ts.diagnosticPath}.errorCase")
                        errorCase = parseStatementBlock(ts, "${ts.diagnosticPath}.errorCase")
                    } else {
                        val path = "${ts.diagnosticPath}.cases[${cases.size}]"
                        val cond = ts.atPath("$path.condition") { ExpressionParser(ts, scope = "implicitResult").parse() }
                        cases += WhenNode(condition = cond, steps = parseStatementBlock(ts, "$path.steps"))
                    }
                }
                ts.checkWord("default") -> {
                    val key = ts.next()
                    declarations.declare("default", key, "${ts.diagnosticPath}.defaultSteps")
                    defaultSteps = parseStatementBlock(ts, "${ts.diagnosticPath}.defaultSteps")
                }
                else -> { val x = ts.peek(); throw ParseException("expected 'when' or 'default' in match", x.line, x.column) }
            }
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return MatchNode(source = source, cases = cases, errorCase = errorCase, defaultSteps = defaultSteps)
    }

    private fun parseRetry(ts: TokenStream): RetryNode {
        val kw = ts.expectWord("retry")
        ts.expect(TokenType.LBRACE, "'{'")
        var max = 3
        var delay = "10s"
        var backoff = "fixed"
        val seenKeys = mutableSetOf<String>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            val keyToken = ts.expect(TokenType.IDENT, "policy key")
            val key = keyToken.text
            val path = "retry.$key"
            if (!seenKeys.add(key)) {
                throw ParseException("duplicate policy key '$path'", keyToken.line, keyToken.column)
            }
            ts.expect(TokenType.COLON, "':'")
            val valueToken = ts.peek()
            val value = ts.atPath("${ts.diagnosticPath}.policy.$key") { ExpressionParser(ts).parse() }
            when (key) {
                "max" -> {
                    val number = value as? NumberLiteralNode
                        ?: throw ParseException("$path must be an integer number", valueToken.line, valueToken.column)
                    if (!number.isInteger || number.value < Int.MIN_VALUE || number.value > Int.MAX_VALUE) {
                        throw ParseException(
                            "$path must be an integer representable as a 32-bit value",
                            valueToken.line,
                            valueToken.column
                        )
                    }
                    max = number.value.toInt()
                }
                "delay" -> delay = (value as? StringLiteralNode)?.value
                    ?: throw ParseException("$path must be text", valueToken.line, valueToken.column)
                "backoff" -> backoff = when (value) {
                    is StringLiteralNode -> value.value
                    is ReferenceNode -> value.path.joinToString(".")
                    else -> throw ParseException("$path must be text or a symbolic identifier", valueToken.line, valueToken.column)
                }
                else -> throw ParseException("unknown policy key '$path'", keyToken.line, keyToken.column)
            }
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        val body = parseStatementBlock(ts)
        return RetryNode(policy = RetryPolicyNode(max = max, delay = delay, backoff = backoff), steps = body, sourceLocation = kw.location())
    }

    private fun parseTry(ts: TokenStream): TryNode {
        ts.expectWord("try")
        val steps = parseStatementBlock(ts)
        ts.skipNewlines()
        ts.expectWord("on"); ts.expectWord("error")
        val handler = ErrorHandlerNode(steps = parseStatementBlock(ts, "${ts.diagnosticPath}.errorHandler.steps"))
        return TryNode(steps = steps, errorHandler = handler)
    }

    private fun parseSet(ts: TokenStream): SetNode {
        ts.expectWord("set")
        val name = ts.expect(TokenType.IDENT, "variable name").text
        ts.expect(TokenType.ASSIGN, "'='")
        return SetNode(name = name, value = ts.atPath("${ts.diagnosticPath}.value") { ExpressionParser(ts).parse() })
    }

    private fun parseApprove(ts: TokenStream): ApproveNode {
        val kw = ts.expectWord("approve")
        val mode = if (ts.check(TokenType.IDENT)) ts.next().text else "manual"
        val params = parseParamBlock(ts, null).first
        var result: ResultBindingNode? = null
        val save = ts.index
        ts.skipNewlines()
        if (ts.match(TokenType.ARROW)) result = ResultBindingNode(name = ts.expect(TokenType.IDENT, "result name").text)
        else rewindTo(ts, save)
        return ApproveNode(mode = mode, params = params, result = result, sourceLocation = kw.location())
    }

    private fun parseTransform(ts: TokenStream): TransformNode {
        ts.expectWord("transform")
        val source = ts.atPath("${ts.diagnosticPath}.source") { ExpressionParser(ts).parse() }
        ts.expect(TokenType.ARROW, "'->'")
        val target = ts.expect(TokenType.IDENT, "transform target").text
        ts.expect(TokenType.LBRACE, "'{'")
        var where: ExpressionNode? = null
        val select = LinkedHashMap<String, ExpressionNode>()
        val clauses = DeclarationOccurrences()
        val selectedFields = DeclarationOccurrences()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            when {
                ts.checkWord("where") -> {
                    val key = ts.next()
                    clauses.declare("where", key, "${ts.diagnosticPath}.where")
                    where = ts.atPath("${ts.diagnosticPath}.where") { ExpressionParser(ts, scope = "implicitResult").parse() }
                }
                ts.checkWord("select") -> {
                    ts.next(); ts.expect(TokenType.LBRACE, "'{'"); ts.skipSeparators()
                    while (!ts.check(TokenType.RBRACE)) {
                        val key = ts.expect(TokenType.IDENT, "select key")
                        val path = "${ts.diagnosticPath}.select.${key.text}"
                        selectedFields.declare(key.text, key, path)
                        ts.expect(TokenType.COLON, "':'")
                        select[key.text] = ts.atPath(path) { ExpressionParser(ts, scope = "implicitResult").parse() }
                        ts.skipSeparators()
                    }
                    ts.expect(TokenType.RBRACE, "'}'")
                }
                else -> { val x = ts.peek(); throw ParseException("expected 'where' or 'select' in transform", x.line, x.column) }
            }
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return TransformNode(source = source, target = target, where = where, select = select)
    }

    private fun parseValidate(ts: TokenStream): ValidateNode {
        ts.expectWord("validate")
        val target = ts.atPath("${ts.diagnosticPath}.target") { ExpressionParser(ts).parse() }
        ts.expect(TokenType.LBRACE, "'{'")
        val rules = mutableListOf<ValidateRuleNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            if (ts.checkWord("required")) {
                ts.next()
                rules += ValidateRuleNode(type = "required", reference = ts.atPath("${ts.diagnosticPath}.rules[${rules.size}].reference") { ExpressionParser(ts, scope = "implicitResult").parse() })
            } else {
                rules += ValidateRuleNode(type = "expression", expression = ts.atPath("${ts.diagnosticPath}.rules[${rules.size}].expression") { ExpressionParser(ts, scope = "implicitResult").parse() })
            }
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return ValidateNode(target = target, rules = rules)
    }

    private fun parseAggregate(ts: TokenStream): AggregateNode {
        ts.expectWord("aggregate")
        val source = ts.atPath("${ts.diagnosticPath}.source") { ExpressionParser(ts).parse() }
        ts.expect(TokenType.ARROW, "'->'")
        val target = ts.expect(TokenType.IDENT, "aggregate target").text
        ts.expect(TokenType.LBRACE, "'{'")
        val fields = LinkedHashMap<String, ExpressionNode>()
        val declarations = DeclarationOccurrences()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            val key = ts.expect(TokenType.IDENT, "aggregate field")
            val path = "${ts.diagnosticPath}.fields.${key.text}"
            declarations.declare(key.text, key, path)
            ts.expect(TokenType.COLON, "':'")
            fields[key.text] = ts.atPath(path) { ExpressionParser(ts, scope = "implicitResult").parse() }
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return AggregateNode(source = source, target = target, fields = fields)
    }

    private fun parseAction(ts: TokenStream): ActionNode {
        val moduleTok = ts.expect(TokenType.IDENT, "module")
        ts.expect(TokenType.DOT, "'.'")
        val action = ts.expect(TokenType.IDENT, "action").text
        val target = ts.expect(TokenType.IDENT, "target system").text
        val (params, safety) = parseParamBlock(ts, allowSafety = true)
        var result: ResultBindingNode? = null
        var handler: ResultHandlerNode? = null
        val save = ts.index
        ts.skipNewlines()
        if (ts.match(TokenType.ARROW)) {
            result = ResultBindingNode(name = ts.expect(TokenType.IDENT, "result name").text)
            val save2 = ts.index
            ts.skipNewlines()
            if (ts.check(TokenType.LBRACE)) handler = ts.atPath("${ts.diagnosticPath}.handler") { parseResultHandler(ts) } else rewindTo(ts, save2)
        } else rewindTo(ts, save)
        return ActionNode(
            module = moduleTok.text, action = action,
            target = ReferenceNode(path = listOf(target), scope = "system"),
            params = params, result = result, handler = handler, safety = safety,
            sourceLocation = moduleTok.location()
        )
    }

    private fun parseParamBlock(ts: TokenStream, allowSafety: Boolean?): Pair<Map<String, ExpressionNode>, SafetyNode?> {
        ts.expect(TokenType.LBRACE, "'{'")
        val params = LinkedHashMap<String, ExpressionNode>()
        var safety: SafetyNode? = null
        val declarations = DeclarationOccurrences()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            val keyToken = ts.expect(TokenType.IDENT, "parameter")
            val key = keyToken.text
            val isSafety = key == "safety" && allowSafety == true
            val path = if (isSafety) "${ts.diagnosticPath}.safety" else "${ts.diagnosticPath}.params.$key"
            declarations.declare(key, keyToken, path)
            ts.expect(TokenType.COLON, "':'")
            if (isSafety) {
                safety = ts.atPath(path) { parseSafety(ts) }
            } else {
                params[key] = ts.atPath(path) { ExpressionParser(ts).parse() }
            }
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return params to safety
    }

    private fun parseSafety(ts: TokenStream): SafetyNode {
        return when {
            ts.checkWord("requiresApproval") -> { ts.next(); SafetyNode(rule = "requiresApproval") }
            ts.checkWord("onlyIf") -> { ts.next(); SafetyNode(rule = "onlyIf", condition = ts.atPath("${ts.diagnosticPath}.condition") { ExpressionParser(ts).parse() }) }
            else -> SafetyNode(rule = "onlyIf", condition = ts.atPath("${ts.diagnosticPath}.condition") { ExpressionParser(ts).parse() })
        }
    }

    private fun parseResultHandler(ts: TokenStream): ResultHandlerNode {
        ts.expect(TokenType.LBRACE, "'{'")
        val rules = mutableListOf<ResultHandlerRuleNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            when {
                ts.checkWord("expect") -> {
                    ts.next(); ts.expect(TokenType.LBRACE, "'{'"); ts.skipSeparators()
                    val exprs = mutableListOf<ExpressionNode>()
                    while (!ts.check(TokenType.RBRACE)) {
                        exprs += ts.atPath("${ts.diagnosticPath}.rules[${rules.size}].expressions[${exprs.size}]") { ExpressionParser(ts, scope = "implicitResult").parse() }
                        ts.skipSeparators()
                    }
                    ts.expect(TokenType.RBRACE, "'}'")
                    rules += ExpectNode(expressions = exprs)
                }
                ts.checkWord("when") -> {
                    ts.next()
                    if (ts.checkWord("error")) {
                        ts.next(); rules += WhenNode(isError = true, steps = parseStatementBlock(ts, "${ts.diagnosticPath}.rules[${rules.size}].steps"))
                    } else {
                        val path = "${ts.diagnosticPath}.rules[${rules.size}]"
                        val cond = ts.atPath("$path.condition") { ExpressionParser(ts, scope = "implicitResult").parse() }
                        rules += WhenNode(condition = cond, steps = parseStatementBlock(ts, "$path.steps"))
                    }
                }
                else -> { val x = ts.peek(); throw ParseException("expected 'expect' or 'when' in result handler", x.line, x.column) }
            }
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return ResultHandlerNode(rules = rules)
    }

    private fun rewindTo(ts: TokenStream, index: Int) { ts.seek(index) }
}
