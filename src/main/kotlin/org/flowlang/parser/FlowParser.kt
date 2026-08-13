package org.flowlang.parser

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
        internal const val MAX_STATEMENT_NESTING_DEPTH = 128
    }

    fun parse(file: File): FlowDocument = parse(file.readText(), file.path)

    fun parse(source: String, sourceFile: String? = null): FlowDocument {
        val tokens = Lexer(source).tokenize()
        val ts = TokenStream(tokens)
        var version = "1.0"
        val imports = mutableListOf<ModuleImportNode>()
        var flow: FlowNode? = null

        ts.skipSeparators()
        while (!ts.atEnd()) {
            val t = ts.peek()
            when {
                t.type == TokenType.IDENT && t.text == "version" -> {
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

        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            val t = ts.peek()
            when {
                t.type == TokenType.IDENT && t.text == "input" -> { ts.next(); inputs += parseInputBlock(ts) }
                t.type == TokenType.IDENT && t.text == "vars" -> { ts.next(); vars += parseVarsBlock(ts) }
                t.type == TokenType.IDENT && t.text == "systems" -> { ts.next(); systems += parseSystemsBlock(ts) }
                t.type == TokenType.IDENT && t.text == "steps" -> { ts.next(); steps += parseStepsBlock(ts) }
                t.type == TokenType.IDENT && t.text == "on" -> {
                    ts.next(); ts.expectWord("error"); errorHandler = ErrorHandlerNode(steps = parseStatementBlock(ts))
                }
                else -> throw ParseException("unexpected ${t.type} '${t.text}' in flow body", t.line, t.column)
            }
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return FlowNode(name = name, input = inputs, vars = vars, systems = systems, steps = steps, errorHandler = errorHandler)
    }

    private fun parseInputBlock(ts: TokenStream): List<InputNode> {
        ts.expect(TokenType.LBRACE, "'{'")
        val out = mutableListOf<InputNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            val nameTok = ts.expect(TokenType.IDENT, "input name")
            ts.expect(TokenType.COLON, "':'")
            val kind = ts.expect(TokenType.IDENT, "type").text
            val values = mutableListOf<ExpressionNode>()
            if (kind == "option") {
                ts.expect(TokenType.LBRACKET, "'['")
                ts.skipSeparators()
                while (!ts.check(TokenType.RBRACKET)) {
                    values += ExpressionParser(ts).parse(); ts.skipSeparators()
                }
                ts.expect(TokenType.RBRACKET, "']'")
            }
            var required = false
            var default: ExpressionNode? = null
            while (ts.peek().type == TokenType.IDENT && ts.peek().text in setOf("required", "default")) {
                if (ts.matchWord("required")) required = true
                else if (ts.matchWord("default")) default = ExpressionParser(ts).parse()
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

    private fun parseVarsBlock(ts: TokenStream): List<VariableNode> {
        ts.expect(TokenType.LBRACE, "'{'")
        val out = mutableListOf<VariableNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            val nameTok = ts.expect(TokenType.IDENT, "variable name")
            ts.expect(TokenType.COLON, "':'")
            out += VariableNode(name = nameTok.text, value = ExpressionParser(ts).parse(), sourceLocation = nameTok.location())
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return out
    }

    private fun parseSystemsBlock(ts: TokenStream): List<SystemNode> {
        ts.expect(TokenType.LBRACE, "'{'")
        val out = mutableListOf<SystemNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            val kw = ts.expectWord("system")
            val name = ts.expect(TokenType.STRING, "system name").rawValue!!
            ts.expect(TokenType.LBRACE, "'{'")
            var systemType: String? = null
            val config = LinkedHashMap<String, ExpressionNode>()
            ts.skipSeparators()
            while (!ts.check(TokenType.RBRACE)) {
                val key = ts.expect(TokenType.IDENT, "config key").text
                ts.expect(TokenType.COLON, "':'")
                val value = ExpressionParser(ts).parse()
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

    private fun parseStepsBlock(ts: TokenStream): List<StatementNode> = parseStatementBlock(ts)

    private fun parseStatementBlock(ts: TokenStream): List<StatementNode> {
        ts.expect(TokenType.LBRACE, "'{'")
        val out = mutableListOf<StatementNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            out += parseStatement(ts)
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
                "fail" -> { ts.next(); FailNode(message = ExpressionParser(ts).parse()) }
                "skip" -> { ts.next(); SkipNode(message = ExpressionParser(ts).parse()) }
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
        val condition = ExpressionParser(ts).parse()
        val then = parseStatementBlock(ts)
        var otherwise = emptyList<StatementNode>()
        val save = ts.index
        ts.skipNewlines()
        if (ts.matchWord("else")) {
            otherwise = if (ts.checkWord("if")) listOf(parseStatement(ts)) else parseStatementBlock(ts)
        } else {
            ts.seek(save)
        }
        return IfNode(condition = condition, then = then, otherwise = otherwise)
    }

    private fun parseFor(ts: TokenStream): ForNode {
        ts.expectWord("for")
        val item = ts.expect(TokenType.IDENT, "loop variable").text
        ts.expectWord("in")
        val source = ExpressionParser(ts).parse()
        val body = parseStatementBlock(ts)
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
                branches += ParallelBranchNode(name = name, steps = parseStatementBlock(ts))
            } else {
                branches += ParallelBranchNode(name = null, steps = listOf(parseStatement(ts)))
            }
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return ParallelNode(branches = branches, failFast = failFast)
    }

    private fun parseMatch(ts: TokenStream): MatchNode {
        ts.expectWord("match")
        val source = ExpressionParser(ts).parse()
        ts.expect(TokenType.LBRACE, "'{'")
        val cases = mutableListOf<WhenNode>()
        var errorCase: List<StatementNode>? = null
        var defaultSteps = emptyList<StatementNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            when {
                ts.checkWord("when") -> {
                    ts.next()
                    if (ts.checkWord("error")) {
                        ts.next(); errorCase = parseStatementBlock(ts)
                    } else {
                        val cond = ExpressionParser(ts, scope = "implicitResult").parse()
                        cases += WhenNode(condition = cond, steps = parseStatementBlock(ts))
                    }
                }
                ts.checkWord("default") -> { ts.next(); defaultSteps = parseStatementBlock(ts) }
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
            val value = ExpressionParser(ts).parse()
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
        val handler = ErrorHandlerNode(steps = parseStatementBlock(ts))
        return TryNode(steps = steps, errorHandler = handler)
    }

    private fun parseSet(ts: TokenStream): SetNode {
        ts.expectWord("set")
        val name = ts.expect(TokenType.IDENT, "variable name").text
        ts.expect(TokenType.ASSIGN, "'='")
        return SetNode(name = name, value = ExpressionParser(ts).parse())
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
        val source = ExpressionParser(ts).parse()
        ts.expect(TokenType.ARROW, "'->'")
        val target = ts.expect(TokenType.IDENT, "transform target").text
        ts.expect(TokenType.LBRACE, "'{'")
        var where: ExpressionNode? = null
        val select = LinkedHashMap<String, ExpressionNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            when {
                ts.checkWord("where") -> { ts.next(); where = ExpressionParser(ts, scope = "implicitResult").parse() }
                ts.checkWord("select") -> {
                    ts.next(); ts.expect(TokenType.LBRACE, "'{'"); ts.skipSeparators()
                    while (!ts.check(TokenType.RBRACE)) {
                        val k = ts.expect(TokenType.IDENT, "select key").text
                        ts.expect(TokenType.COLON, "':'")
                        select[k] = ExpressionParser(ts, scope = "implicitResult").parse()
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
        val target = ExpressionParser(ts).parse()
        ts.expect(TokenType.LBRACE, "'{'")
        val rules = mutableListOf<ValidateRuleNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            if (ts.checkWord("required")) {
                ts.next()
                rules += ValidateRuleNode(type = "required", reference = ExpressionParser(ts, scope = "implicitResult").parse())
            } else {
                rules += ValidateRuleNode(type = "expression", expression = ExpressionParser(ts, scope = "implicitResult").parse())
            }
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return ValidateNode(target = target, rules = rules)
    }

    private fun parseAggregate(ts: TokenStream): AggregateNode {
        ts.expectWord("aggregate")
        val source = ExpressionParser(ts).parse()
        ts.expect(TokenType.ARROW, "'->'")
        val target = ts.expect(TokenType.IDENT, "aggregate target").text
        ts.expect(TokenType.LBRACE, "'{'")
        val fields = LinkedHashMap<String, ExpressionNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            val k = ts.expect(TokenType.IDENT, "aggregate field").text
            ts.expect(TokenType.COLON, "':'")
            fields[k] = ExpressionParser(ts, scope = "implicitResult").parse()
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
            if (ts.check(TokenType.LBRACE)) handler = parseResultHandler(ts) else rewindTo(ts, save2)
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
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            val key = ts.expect(TokenType.IDENT, "parameter").text
            ts.expect(TokenType.COLON, "':'")
            if (key == "safety" && allowSafety == true) {
                safety = parseSafety(ts)
            } else {
                params[key] = ExpressionParser(ts).parse()
            }
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return params to safety
    }

    private fun parseSafety(ts: TokenStream): SafetyNode {
        return when {
            ts.checkWord("requiresApproval") -> { ts.next(); SafetyNode(rule = "requiresApproval") }
            ts.checkWord("onlyIf") -> { ts.next(); SafetyNode(rule = "onlyIf", condition = ExpressionParser(ts).parse()) }
            else -> SafetyNode(rule = "onlyIf", condition = ExpressionParser(ts).parse())
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
                        exprs += ExpressionParser(ts, scope = "implicitResult").parse(); ts.skipSeparators()
                    }
                    ts.expect(TokenType.RBRACE, "'}'")
                    rules += ExpectNode(expressions = exprs)
                }
                ts.checkWord("when") -> {
                    ts.next()
                    if (ts.checkWord("error")) {
                        ts.next(); rules += WhenNode(isError = true, steps = parseStatementBlock(ts))
                    } else {
                        val cond = ExpressionParser(ts, scope = "implicitResult").parse()
                        rules += WhenNode(condition = cond, steps = parseStatementBlock(ts))
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
