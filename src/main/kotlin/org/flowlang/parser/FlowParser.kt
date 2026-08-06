package org.flowlang.parser

import org.flowlang.ast.*

class FlowParser {
    private lateinit var ts: TokenStream
    private lateinit var sourceName: String
    private var statementNestingDepth: Int = 0

    companion object {
        internal const val MAX_STATEMENT_NESTING_DEPTH = 128
    }

    fun parse(source: String, sourceName: String = "<memory>"): FlowDocument {
        this.sourceName = sourceName
        val tokens = Lexer(source).tokenize()
        ts = TokenStream(tokens)
        ts.skipNewlines()
        return parseDocument()
    }

    // -- document --------------------------------------------------------------

    private fun parseDocument(): FlowDocument {
        ts.expectWord("flow")
        val nameTok = ts.expectAny(setOf(TokenType.STRING, TokenType.IDENT), "flow name")
        val name = nameTok.rawValue ?: nameTok.text
        ts.expect(TokenType.LBRACE, "'{'")
        val flow = parseFlowBody(name, nameTok.location())
        ts.skipSeparators()
        ts.expect(TokenType.EOF, "end of file")
        return FlowDocument(flow = flow, sourceName = sourceName)
    }

    private fun parseFlowBody(name: String, loc: SourceLocation): FlowNode {
        val inputs = mutableListOf<InputNode>()
        val triggers = mutableListOf<TriggerNode>()
        val steps = mutableListOf<StatementNode>()
        val outputs = mutableListOf<OutputNode>()
        var description: String? = null
        var version = "1"
        var onFailure: ErrorHandlerNode? = null
        var onSuccess: List<StatementNode> = emptyList()
        var finallySteps: List<StatementNode> = emptyList()

        while (true) {
            ts.skipSeparators()
            if (ts.match(TokenType.RBRACE)) break
            val key = ts.expect(TokenType.IDENT, "flow field").text
            when (key) {
                "description" -> { ts.match(TokenType.COLON); description = parseStringValue() }
                "version" -> { ts.match(TokenType.COLON); version = parseScalarText() }
                "inputs" -> inputs += parseInputs()
                "triggers" -> triggers += parseTriggers()
                "steps" -> steps += parseStatementBlock()
                "outputs" -> outputs += parseOutputs()
                "onFailure" -> onFailure = parseErrorHandler()
                "onSuccess" -> onSuccess = parseStatementBlock()
                "finally" -> finallySteps = parseStatementBlock()
                else -> {
                    val t = ts.peek()
                    throw ParseException("unknown flow field '$key'", t.line, t.column)
                }
            }
        }
        return FlowNode(
            name = name,
            version = version,
            description = description,
            inputs = inputs,
            triggers = triggers,
            steps = steps,
            outputs = outputs,
            onFailure = onFailure,
            onSuccess = onSuccess,
            finallySteps = finallySteps,
            location = loc
        )
    }

    // -- inputs ----------------------------------------------------------------

    private fun parseInputs(): List<InputNode> {
        ts.expect(TokenType.LBRACE, "'{'")
        val out = mutableListOf<InputNode>()
        while (true) {
            ts.skipSeparators()
            if (ts.match(TokenType.RBRACE)) break
            val name = ts.expect(TokenType.IDENT, "input name")
            ts.expect(TokenType.COLON, "':'")
            val type = ts.expect(TokenType.IDENT, "input type").text
            var required = false
            var sensitive = false
            if (ts.matchWord("required")) required = true
            if (ts.matchWord("sensitive")) sensitive = true
            var default: ExpressionNode? = null
            var choices: List<ExpressionNode> = emptyList()
            if (ts.match(TokenType.ASSIGN)) default = parseExpression()
            if (ts.matchWord("choices")) choices = parseExpressionList()
            out += InputNode(name = name.text, type = type, required = required, default = default, choices = choices, sensitive = sensitive, location = name.location())
            ts.skipSeparators()
        }
        return out
    }

    // -- triggers --------------------------------------------------------------

    private fun parseTriggers(): List<TriggerNode> {
        ts.expect(TokenType.LBRACE, "'{'")
        val out = mutableListOf<TriggerNode>()
        while (true) {
            ts.skipSeparators()
            if (ts.match(TokenType.RBRACE)) break
            val idTok = ts.expect(TokenType.IDENT, "trigger id")
            ts.expect(TokenType.COLON, "':'")
            val typeTok = ts.expect(TokenType.IDENT, "trigger type")
            val params = LinkedHashMap<String, ExpressionNode>()
            while (true) {
                val next = ts.peek()
                if (next.type in setOf(TokenType.NEWLINE, TokenType.COMMA, TokenType.RBRACE, TokenType.EOF)) break
                val key = ts.expect(TokenType.IDENT, "trigger parameter").text
                ts.match(TokenType.COLON)
                params[key] = parseExpression()
            }
            out += TriggerNode(id = idTok.text, type = typeTok.text, params = params, location = idTok.location())
            ts.skipSeparators()
        }
        return out
    }

    // -- statements ------------------------------------------------------------

    private fun parseStatementBlock(): List<StatementNode> {
        val opener = ts.expect(TokenType.LBRACE, "'{'")
        if (statementNestingDepth >= MAX_STATEMENT_NESTING_DEPTH) {
            throw ParseException(
                "statement nesting exceeds maximum depth $MAX_STATEMENT_NESTING_DEPTH",
                opener.line,
                opener.column
            )
        }
        statementNestingDepth++
        try {
            val out = mutableListOf<StatementNode>()
            while (true) {
                ts.skipSeparators()
                if (ts.match(TokenType.RBRACE)) break
                val keyword = ts.expect(TokenType.IDENT, "statement keyword")
                out += when (keyword.text) {
                    "task" -> parseTask(keyword.location())
                    "set" -> parseSet(keyword.location())
                    "emit" -> parseEmit(keyword.location())
                    "skip" -> parseSkip(keyword.location())
                    "fail" -> parseFail(keyword.location())
                    "if" -> parseIf(keyword.location())
                    "for" -> parseFor(keyword.location())
                    "parallel" -> parseParallel(keyword.location())
                    "match" -> parseMatch(keyword.location())
                    "retry" -> parseRetry(keyword.location())
                    "try" -> parseTry(keyword.location())
                    "wait" -> parseWait(keyword.location())
                    "approval" -> parseApproval(keyword.location())
                    else -> throw ParseException("unknown statement '${keyword.text}'", keyword.line, keyword.column)
                }
                ts.skipSeparators()
            }
            return out
        } finally {
            statementNestingDepth--
        }
    }

    private fun parseTask(loc: SourceLocation): StatementNode {
        val module = ts.expect(TokenType.IDENT, "module name")
        ts.expect(TokenType.DOT, "'.'")
        val action = ts.expect(TokenType.IDENT, "action name")
        var alias: String? = null
        if (ts.matchWord("as")) alias = ts.expect(TokenType.IDENT, "task alias").text
        val params = if (ts.check(TokenType.LBRACE)) parseParamBlock() else emptyMap()
        var resultName: String? = null
        if (ts.match(TokenType.ARROW)) resultName = ts.expect(TokenType.IDENT, "result name").text
        return TaskNode(module = module.text, action = action.text, alias = alias, params = params, resultName = resultName, location = loc)
    }

    private fun parseSet(loc: SourceLocation): StatementNode {
        val target = parseReferencePath()
        ts.expect(TokenType.ASSIGN, "'='")
        return SetNode(target = target, value = parseExpression(), location = loc)
    }

    private fun parseEmit(loc: SourceLocation): StatementNode {
        val name = ts.expect(TokenType.IDENT, "output name")
        ts.expect(TokenType.ASSIGN, "'='")
        return EmitNode(name = name.text, value = parseExpression(), location = loc)
    }

    private fun parseSkip(loc: SourceLocation): StatementNode = SkipNode(reason = parseStringValue(), location = loc)
    private fun parseFail(loc: SourceLocation): StatementNode = FailNode(message = parseStringValue(), location = loc)

    private fun parseIf(loc: SourceLocation): StatementNode {
        val condition = parseExpressionUntil(TokenType.LBRACE)
        val thenBlock = parseStatementBlock()
        var elseBlock: List<StatementNode> = emptyList()
        ts.skipSeparators()
        if (ts.matchWord("else")) elseBlock = parseStatementBlock()
        return IfNode(condition = condition, then = thenBlock, otherwise = elseBlock, location = loc)
    }

    private fun parseFor(loc: SourceLocation): StatementNode {
        val item = ts.expect(TokenType.IDENT, "loop variable")
        ts.expectWord("in")
        val source = parseExpressionUntil(TokenType.LBRACE)
        return ForNode(item = item.text, source = source, body = parseStatementBlock(), location = loc)
    }

    private fun parseParallel(loc: SourceLocation): StatementNode {
        var failFast = false
        if (ts.matchWord("failFast")) {
            ts.match(TokenType.COLON)
            failFast = parseBoolean()
        }
        ts.expect(TokenType.LBRACE, "'{'")
        val branches = mutableListOf<ParallelBranchNode>()
        while (true) {
            ts.skipSeparators()
            if (ts.match(TokenType.RBRACE)) break
            val branch = ts.expect(TokenType.IDENT, "branch")
            if (branch.text != "branch") throw ParseException("expected 'branch'", branch.line, branch.column)
            val name = if (!ts.check(TokenType.LBRACE)) parseStringValue() else null
            branches += ParallelBranchNode(name = name, steps = parseStatementBlock())
        }
        return ParallelNode(failFast = failFast, branches = branches, location = loc)
    }

    private fun parseMatch(loc: SourceLocation): StatementNode {
        val source = parseExpressionUntil(TokenType.LBRACE)
        ts.expect(TokenType.LBRACE, "'{'")
        val cases = mutableListOf<MatchCaseNode>()
        var errorCase: List<StatementNode> = emptyList()
        var default: List<StatementNode> = emptyList()
        while (true) {
            ts.skipSeparators()
            if (ts.match(TokenType.RBRACE)) break
            val kw = ts.expect(TokenType.IDENT, "case/default")
            when (kw.text) {
                "case" -> {
                    val pattern = parseExpressionUntil(TokenType.LBRACE)
                    cases += MatchCaseNode(pattern = pattern, steps = parseStatementBlock())
                }
                "error" -> errorCase = parseStatementBlock()
                "default" -> default = parseStatementBlock()
                else -> throw ParseException("expected case/default/error", kw.line, kw.column)
            }
        }
        return MatchNode(source = source, cases = cases, errorCase = errorCase, default = default, location = loc)
    }

    private fun parseRetry(loc: SourceLocation): StatementNode {
        var max = 3
        var delay = "10s"
        var backoff = "fixed"
        ts.expect(TokenType.LBRACE, "'{'")
        val body = mutableListOf<StatementNode>()
        while (true) {
            ts.skipSeparators()
            if (ts.match(TokenType.RBRACE)) break
            if (ts.checkWord("max") || ts.checkWord("delay") || ts.checkWord("backoff")) {
                val keyToken = ts.next()
                val key = keyToken.text
                ts.match(TokenType.COLON)
                when (key) {
                    "max" -> {
                        val number = ts.expect(TokenType.NUMBER, "integer retry max")
                        if (!number.isInteger) {
                            throw ParseException("retry max must be an integer", number.line, number.column)
                        }
                        val parsed = number.text.toLongOrNull()
                            ?: throw ParseException("retry max is outside the supported integer range", number.line, number.column)
                        if (parsed !in 1..Int.MAX_VALUE.toLong()) {
                            throw ParseException("retry max must be between 1 and ${Int.MAX_VALUE}", number.line, number.column)
                        }
                        max = parsed.toInt()
                    }
                    "delay" -> delay = parseScalarText()
                    "backoff" -> backoff = parseScalarText()
                }
                ts.skipSeparators()
            } else {
                val kw = ts.expect(TokenType.IDENT, "retry statement")
                body += when (kw.text) {
                    "task" -> parseTask(kw.location())
                    "set" -> parseSet(kw.location())
                    "emit" -> parseEmit(kw.location())
                    "skip" -> parseSkip(kw.location())
                    "fail" -> parseFail(kw.location())
                    "if" -> parseIf(kw.location())
                    "for" -> parseFor(kw.location())
                    "parallel" -> parseParallel(kw.location())
                    "match" -> parseMatch(kw.location())
                    "retry" -> parseRetry(kw.location())
                    "try" -> parseTry(kw.location())
                    "wait" -> parseWait(kw.location())
                    "approval" -> parseApproval(kw.location())
                    else -> throw ParseException("unknown retry statement '${kw.text}'", kw.line, kw.column)
                }
            }
        }
        return RetryNode(max = max, delay = delay, backoff = backoff, body = body, location = loc)
    }

    private fun parseTry(loc: SourceLocation): StatementNode {
        val body = parseStatementBlock()
        ts.skipSeparators()
        val handler = if (ts.matchWord("catch")) parseStatementBlock() else emptyList()
        return TryNode(body = body, catch = handler, location = loc)
    }

    private fun parseWait(loc: SourceLocation): StatementNode {
        var duration: String? = null
        var until: ExpressionNode? = null
        var timeout: String? = null
        when {
            ts.matchWord("duration") -> { ts.match(TokenType.COLON); duration = parseScalarText() }
            ts.matchWord("until") -> until = parseExpression()
            else -> {
                val t = ts.peek()
                throw ParseException("wait requires duration or until", t.line, t.column)
            }
        }
        if (ts.matchWord("timeout")) { ts.match(TokenType.COLON); timeout = parseScalarText() }
        return WaitNode(duration = duration, until = until, timeout = timeout, location = loc)
    }

    private fun parseApproval(loc: SourceLocation): StatementNode {
        val mode = ts.expect(TokenType.IDENT, "approval mode").text
        var message: String? = null
        if (ts.matchWord("message")) { ts.match(TokenType.COLON); message = parseStringValue() }
        var resultName: String? = null
        if (ts.match(TokenType.ARROW)) resultName = ts.expect(TokenType.IDENT, "approval result name").text
        return ApprovalNode(mode = mode, message = message, resultName = resultName, location = loc)
    }

    // -- outputs / handlers ----------------------------------------------------

    private fun parseOutputs(): List<OutputNode> {
        ts.expect(TokenType.LBRACE, "'{'")
        val out = mutableListOf<OutputNode>()
        while (true) {
            ts.skipSeparators()
            if (ts.match(TokenType.RBRACE)) break
            val name = ts.expect(TokenType.IDENT, "output name")
            ts.expect(TokenType.COLON, "':'")
            val type = ts.expect(TokenType.IDENT, "output type").text
            ts.expect(TokenType.ASSIGN, "'='")
            out += OutputNode(name = name.text, type = type, value = parseExpression(), location = name.location())
            ts.skipSeparators()
        }
        return out
    }

    private fun parseErrorHandler(): ErrorHandlerNode {
        val steps = parseStatementBlock()
        return ErrorHandlerNode(steps = steps)
    }

    // -- params / expressions --------------------------------------------------

    private fun parseParamBlock(): Map<String, ExpressionNode> {
        ts.expect(TokenType.LBRACE, "'{'")
        val out = LinkedHashMap<String, ExpressionNode>()
        while (true) {
            ts.skipSeparators()
            if (ts.match(TokenType.RBRACE)) break
            val key = ts.expect(TokenType.IDENT, "parameter name").text
            ts.expect(TokenType.COLON, "':'")
            out[key] = parseExpression()
            ts.skipSeparators()
        }
        return out
    }

    private fun parseExpression(): ExpressionNode = ExpressionParser(ts).parse()

    private fun parseExpressionUntil(stop: TokenType): ExpressionNode {
        val start = ts.index
        var depth = 0
        while (true) {
            val token = ts.peek()
            if (token.type == TokenType.EOF) throw ParseException("unexpected EOF in expression", token.line, token.column)
            if (depth == 0 && token.type == stop) break
            when (token.type) {
                TokenType.LPAREN, TokenType.LBRACKET -> depth++
                TokenType.RPAREN, TokenType.RBRACKET -> depth--
                else -> Unit
            }
            ts.next()
        }
        val end = ts.index
        val slice = ts.slice(start, end) + Token(TokenType.EOF, "", ts.peek().line, ts.peek().column)
        ts.index = start
        repeat(end - start) { ts.next() }
        return ExpressionParser(TokenStream(slice)).parse()
    }

    private fun parseExpressionList(): List<ExpressionNode> {
        ts.expect(TokenType.LBRACKET, "'['")
        val out = mutableListOf<ExpressionNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACKET)) {
            out += parseExpression()
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACKET, "']'")
        return out
    }

    private fun parseReferencePath(): ReferenceNode {
        val first = ts.expect(TokenType.IDENT, "reference")
        val path = mutableListOf(first.text)
        while (ts.match(TokenType.DOT)) path += ts.expect(TokenType.IDENT, "reference segment").text
        return ReferenceNode(path = path, scope = "auto", location = first.location())
    }

    private fun parseStringValue(): String {
        val t = ts.expectAny(setOf(TokenType.STRING, TokenType.IDENT), "string")
        return t.rawValue ?: t.text
    }

    private fun parseScalarText(): String {
        val t = ts.expectAny(setOf(TokenType.STRING, TokenType.IDENT, TokenType.NUMBER), "scalar")
        return t.rawValue ?: t.text
    }

    private fun parseBoolean(): Boolean {
        val t = ts.expect(TokenType.IDENT, "boolean")
        return when (t.text) {
            "true" -> true
            "false" -> false
            else -> throw ParseException("expected true/false", t.line, t.column)
        }
    }
}
