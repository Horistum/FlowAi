package org.flowlang.validator

import org.flowlang.ast.*
import org.flowlang.core.FlowAvailabilityAnalysis
import org.flowlang.core.FlowAvailabilityAnalyzer
import org.flowlang.core.FlowAvailabilityIssueKind
import org.flowlang.core.FlowValueAvailability
import org.flowlang.modules.ModuleCatalog
import org.flowlang.modules.ModuleCatalogIndex
import org.flowlang.modules.SchemaType
import org.flowlang.modules.SchemaTypeCompatibility
import org.flowlang.modules.SchemaValueKind
import org.flowlang.safety.EnvironmentSafetyPolicy

/**
 * Flow AST validator (docs/03, docs/04, docs/06).
 *
 * Checks: imports & versions, system uniqueness & types, secret typing
 * ("secrets are not plain strings"), per-action module/action/target/params,
 * destructive-action safety, result-binding uniqueness, handler/result coherence,
 * reference resolution against scope, and expression operator validity. Recurses
 * through every control-flow and data statement.
 *
 * AR-02A/B adds one shared path-sensitive availability and merge product. The legacy lexical
 * walk still owns syntax/module/type diagnostics; the shared product owns whether
 * a produced value is available on every reachable path and which producer it
 * resolves to.
 */
class FlowValidator(
    registry: ModuleCatalog,
    private val environmentPolicy: EnvironmentSafetyPolicy
) {
    private val registry = ModuleCatalogIndex.capture(registry)

    private val standardResultFields = setOf(
        "ok", "status", "code", "data", "text", "lines", "json", "yaml", "error", "meta", "artifacts"
    )
    private val validOperators = setOf(
        "==", "!=", ">", ">=", "<", "<=", "and", "or",
        "in", "contains", "startsWith", "endsWith", "matches"
    )
    private val builtinPatterns = setOf(
        "email", "url", "uuid", "ipv4", "ipv6", "date", "datetime",
        "number", "alpha", "alphanumeric", "slug", "semver", "hostname"
    )

    fun validate(document: FlowDocument): ValidationReport =
        validate(document, FlowAvailabilityAnalyzer().analyze(document))

    internal fun validate(
        document: FlowDocument,
        availability: FlowAvailabilityAnalysis
    ): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()
        if (document.kind != "FlowDocument") issues += err("INVALID_KIND", "AST kind must be FlowDocument")
        if (document.flow.name.isBlank()) issues += err("FLOW_NAME_EMPTY", "Flow name must not be empty")

        // imports
        document.imports.forEach { imp ->
            val module = registry.findModule(imp.name)
            if (module == null) issues += err("MODULE_NOT_FOUND", "Module '${imp.name}' is not registered", imp.sourceLocation)
            else if (module.version != imp.version)
                issues += warn("MODULE_VERSION_MISMATCH", "Module '${imp.name}' requested '${imp.version}', registry has '${module.version}'", imp.sourceLocation)
        }
        val imported = document.imports.map { it.name }.toSet()

        // base scope: inputs + vars + systems
        val scope = Scope()
        document.flow.input.forEach { scope.declare(it.name) }
        document.flow.vars.forEach { scope.declare(it.name) }
        document.flow.systems.forEach { scope.declare(it.name) }

        // input defaults
        document.flow.input.forEach { input ->
            val d = input.default
            if (d != null && input.valueType.kind == "option") {
                val allowed = input.valueType.values.filterIsInstance<StringLiteralNode>().map { it.value }
                val dv = (d as? StringLiteralNode)?.value
                if (dv != null && dv !in allowed)
                    issues += warn("INPUT_DEFAULT_NOT_IN_OPTION", "Default '$dv' for input '${input.name}' is not one of $allowed", input.sourceLocation)
            }
        }

        // vars / systems expressions reference inputs+vars+systems
        document.flow.vars.forEach { checkExpr(it.value, scope, "auto", null, issues) }

        val systemNames = mutableSetOf<String>()
        document.flow.systems.forEach { system ->
            if (!systemNames.add(system.name)) issues += err("DUPLICATE_SYSTEM", "System '${system.name}' is declared more than once", system.sourceLocation)
            val resolved = registry.findSystemType(system.systemType)
            if (resolved == null) {
                issues += warn("SYSTEM_TYPE_UNKNOWN", "System '${system.name}' uses type '${system.systemType}', not backed by any known module", system.sourceLocation)
            } else {
                val (_, contract) = resolved
                contract.input.forEach { (key, field) ->
                    if (field.required && key !in system.config)
                        issues += err("MISSING_SYSTEM_CONFIG", "System '${system.name}' of type '${system.systemType}' requires config '$key'", system.sourceLocation)
                }
                system.config.forEach { (key, value) ->
                    val field = contract.input[key]
                    if (field == null) {
                        issues += warn("UNKNOWN_SYSTEM_CONFIG", "System '${system.name}' type '${system.systemType}' does not define config '$key'", system.sourceLocation)
                        // Unknown config values are intentionally not scope-checked here:
                        // without a schema we cannot know whether a bareword is meant as a
                        // symbolic value (channel: email) or a reference. The warning above
                        // is the signal; later schema tightening can make this an error.
                    } else {
                        validateSchemaValue("System '${system.name}.$key'", value, field, scope, system.sourceLocation, issues)
                    }
                }
            }
        }

        // steps
        val results = mutableSetOf<String>()
        document.flow.steps.forEach { validateStatement(it, imported, document.flow.systems, scope, results, issues) }

        // global error handler scope has `error`
        document.flow.errorHandler?.let { eh ->
        if (eh.steps.isEmpty()) {
            issues += err(
                "FLOW_ERROR_HANDLER_EMPTY",
                "Workflow error handler must contain at least one statement."
            )
        }
        val ehScope = scope.child().also { it.declare("error") }
            val handlerResults = mutableSetOf<String>()
            eh.steps.forEach { validateStatement(it, imported, document.flow.systems, ehScope, handlerResults, issues) }
        }

        reconcileAvailabilityIssues(issues, availability)
        issues += SafetyBoundaryValidator(this.registry, environmentPolicy).validate(document)
        return ValidationReport(valid = issues.none { it.level == "error" }, issues = issues)
    }

    private fun validateStatement(
        stmt: StatementNode, imported: Set<String>, systems: List<SystemNode>,
        scope: Scope, results: MutableSet<String>, issues: MutableList<ValidationIssue>
    ) {
        when (stmt) {
            is ActionNode -> validateAction(stmt, imported, systems, scope, results, issues)
            is IfNode -> {
                checkExpr(stmt.condition, scope, "auto", null, issues)
                // then/otherwise are mutually exclusive: each validates against its own copy of the
                // result names, so the same binding name in both branches is not a false
                // DUPLICATE_RESULT. Availability after the join comes from FlowAvailabilityAnalysis.
                val thenScope = scope.child()
                val thenResults = results.toMutableSet()
                stmt.then.forEach { validateStatement(it, imported, systems, thenScope, thenResults, issues) }
                val otherwiseScope = scope.child()
                val otherwiseResults = results.toMutableSet()
                stmt.otherwise.forEach { validateStatement(it, imported, systems, otherwiseScope, otherwiseResults, issues) }
                results += thenResults
                results += otherwiseResults
            }
            is ForNode -> {
                checkExpr(stmt.source, scope, "auto", null, issues)
                val s = scope.child().also { it.declare(stmt.item) }
                stmt.body.forEach { validateStatement(it, imported, systems, s, results, issues) }
            }
            is ParallelNode -> stmt.branches.forEach { b ->
                val branchScope = scope.child()
                b.steps.forEach { validateStatement(it, imported, systems, branchScope, results, issues) }
            }
            is MatchNode -> {
                checkExpr(stmt.source, scope, "auto", null, issues)
                // Cases and the error case are mutually exclusive; each validates against the pre-match
                // result snapshot so a shared binding name is not a false duplicate.
                val baseResults = results.toSet()
                stmt.cases.forEach { c ->
                    c.condition?.let { checkExpr(it, scope, "implicitResult", null, issues) }
                    val caseScope = scope.child()
                    val caseResults = baseResults.toMutableSet()
                    c.steps.forEach { validateStatement(it, imported, systems, caseScope, caseResults, issues) }
                    results += caseResults
                }
                stmt.errorCase?.let { steps ->
                    val errorScope = scope.child().also { s -> s.declare("error") }
                    val errorResults = baseResults.toMutableSet()
                    steps.forEach { validateStatement(it, imported, systems, errorScope, errorResults, issues) }
                    results += errorResults
                }
                val defaultScope = scope.child()
                stmt.defaultSteps.forEach { validateStatement(it, imported, systems, defaultScope, results, issues) }
            }
            is RetryNode -> {
                if (stmt.policy.max < 1) issues += err("RETRY_MAX_INVALID", "retry.max must be >= 1", stmt.sourceLocation)
                val retryScope = scope.child()
                stmt.steps.forEach { validateStatement(it, imported, systems, retryScope, results, issues) }
            }
            is TryNode -> {
                val baseResults = results.toSet()
                val tryScope = scope.child()
                val tryResults = baseResults.toMutableSet()
                stmt.steps.forEach { validateStatement(it, imported, systems, tryScope, tryResults, issues) }
                val eh = scope.child().also { it.declare("error") }
                val handlerResults = baseResults.toMutableSet()
                stmt.errorHandler.steps.forEach { validateStatement(it, imported, systems, eh, handlerResults, issues) }
                results += tryResults
                results += handlerResults
            }
            is TransformNode -> {
                checkExpr(stmt.source, scope, "auto", null, issues)
                val s = scope.child().also { it.declare("item") }
                stmt.where?.let { checkExpr(it, s, "implicitResult", null, issues) }
                stmt.select.values.forEach { checkExpr(it, s, "implicitResult", null, issues) }
                scope.declare(stmt.target)
            }
            is AggregateNode -> {
                checkExpr(stmt.source, scope, "auto", null, issues)
                val s = scope.child().also { it.declare("item") }
                stmt.fields.values.forEach { checkExpr(it, s, "implicitResult", null, issues) }
                scope.declare(stmt.target)
            }
            is ValidateNode -> {
                checkExpr(stmt.target, scope, "auto", null, issues)
                val s = scope.child().also { it.declare("item") }
                stmt.rules.forEach { r ->
                    r.reference?.let { checkExpr(it, s, "implicitResult", null, issues) }
                    r.expression?.let { checkExpr(it, s, "implicitResult", null, issues) }
                }
            }
            is SetNode -> { checkExpr(stmt.value, scope, "auto", null, issues); scope.declare(stmt.name) }
            is FailNode -> checkExpr(stmt.message, scope, "auto", null, issues)
            is SkipNode -> checkExpr(stmt.message, scope, "auto", null, issues)
            is ApproveNode -> {
                stmt.params.values.forEach { checkExpr(it, scope, "auto", null, issues) }
                stmt.result?.let { declareResult(it.name, scope, results, stmt.sourceLocation, issues) }
                stmt.declaredOutputs.forEach { declareResult(it, scope, results, stmt.sourceLocation, issues) }
            }
            is ExpectNode -> stmt.expressions.forEach { checkExpr(it, scope, "implicitResult", null, issues) }
            is ErrorHandlerNode -> {
                val errorScope = scope.child().also { s -> s.declare("error") }
                stmt.steps.forEach { validateStatement(it, imported, systems, errorScope, results, issues) }
            }
        }
    }

    private fun validateAction(
        action: ActionNode, imported: Set<String>, systems: List<SystemNode>,
        scope: Scope, results: MutableSet<String>, issues: MutableList<ValidationIssue>
    ) {
        if (action.module !in imported)
            issues += err("MODULE_NOT_IMPORTED", "Action uses module '${action.module}' but it is not imported", action.sourceLocation)

        val contract = registry.findAction(action.module, action.action)
        if (contract == null) {
            issues += err("ACTION_NOT_FOUND", "Action '${action.module}.${action.action}' is not registered", action.sourceLocation)
        } else {
            val targetName = action.target.path.firstOrNull()
            val target = systems.find { it.name == targetName }
            if (target == null) issues += err("TARGET_NOT_FOUND", "Target system '$targetName' does not exist", action.sourceLocation)
            else if (target.systemType !in contract.targetTypes)
                issues += err("TARGET_TYPE_INVALID", "Action '${action.module}.${action.action}' cannot target system type '${target.systemType}'", action.sourceLocation)

            contract.input.forEach { (name, field) ->
                if (field.required && name !in action.params)
                    issues += err("MISSING_PARAM", "Action '${action.module}.${action.action}' requires parameter '$name'", action.sourceLocation)
            }
            action.params.forEach { (paramName, value) ->
                contract.input[paramName]?.let { field ->
                    validateValueType("Action '${action.module}.${action.action}' parameter '$paramName'", value, field, action.sourceLocation, issues)
                }
            }
            if (!contract.additionalParams) {
                action.params.keys.filter { it !in contract.input.keys }.forEach {
                    issues += err("UNKNOWN_PARAM", "Action '${action.module}.${action.action}' does not define parameter '$it'", action.sourceLocation)
                }
            }
        }

        // Action parameters are executable expressions. A bareword in an action
        // parameter remains a reference, so typos such as `command: undefinedVar`
        // are still caught. Symbolic lowercase barewords are only tolerated in
        // schema-bound system config values.
        action.params.values.forEach { checkExpr(it, scope, "auto", null, issues) }
        action.safety?.condition?.let { checkExpr(it, scope, "auto", null, issues) }

        // Result bindings and canonical declared outputs become referenceable only
        // after the producing statement validates, so self-references remain invalid.
        action.result?.let { declareResult(it.name, scope, results, action.sourceLocation, issues) }
        action.declaredOutputs.forEach { declareResult(it, scope, results, action.sourceLocation, issues) }
        if (action.handler != null && action.result == null)
            issues += err("HANDLER_WITHOUT_RESULT", "Result handler requires a result binding", action.sourceLocation)

        // result handler scope = standard fields + module outputs. When the module declares a concrete
        // output schema, unknown field references become errors (typo detection); an open output stays lenient.
        val moduleOutputs = registry.findAction(action.module, action.action)?.output?.keys ?: emptySet()
        val outputs = standardResultFields + moduleOutputs
        val strictOutputs = moduleOutputs.isNotEmpty()
        action.handler?.rules?.forEach { rule ->
            when (rule) {
                is ExpectNode -> rule.expressions.forEach { checkExpr(it, scope, "implicitResult", outputs, issues, strictOutputs) }
                is WhenNode -> {
                    rule.condition?.let { checkExpr(it, scope, "implicitResult", outputs, issues, strictOutputs) }
                    val s = scope.child().also { if (rule.isError) it.declare("error") }
                    rule.steps.forEach { validateStatement(it, imported, systems, s, results, issues) }
                }
            }
        }
    }

    private fun declareResult(
        name: String,
        scope: Scope,
        results: MutableSet<String>,
        location: SourceLocation?,
        issues: MutableList<ValidationIssue>
    ) {
        if (name.isBlank()) {
            issues += err("RESULT_NAME_EMPTY", "Result and declared output names must not be empty", location)
            return
        }
        val logicalName = name.replace('-', '_')
        if (!results.add(logicalName)) {
            issues += err("DUPLICATE_RESULT", "Result '$name' is already defined", location)
        }
        scope.declare(name)
    }

    // --- expression checking --------------------------------------------------

    private fun checkExpr(
        expr: ExpressionNode, scope: Scope, defaultScope: String,
        resultFields: Set<String>?, issues: MutableList<ValidationIssue>,
        strictResultFields: Boolean = false
    ) {
        when (expr) {
            is ReferenceNode -> {
                val root = expr.path.firstOrNull() ?: return
                val effScope = if (expr.scope == "auto") defaultScope else expr.scope
                if (effScope == "implicitResult") {
                    val allowed = (resultFields ?: standardResultFields)
                    if (root !in allowed && root != "item" && root != "error" && root !in builtinPatterns && !scope.has(root)) {
                        if (strictResultFields)
                            issues += err("UNKNOWN_RESULT_FIELD", "Reference '$root' is not a declared output of this module (possible typo)", expr.location)
                        else
                            issues += warn("UNKNOWN_RESULT_FIELD", "Reference '$root' is not a standard result field or known output", expr.location)
                    }
                } else {
                    if (!scope.has(root))
                        issues += err("UNRESOLVED_REFERENCE", "Reference '$root' is not defined in scope", expr.location)
                }
            }
            is BinaryExpressionNode -> {
                if (expr.operator !in validOperators) issues += err("UNKNOWN_OPERATOR", "Unknown operator '${expr.operator}'", expr.location)
                checkExpr(expr.left, scope, defaultScope, resultFields, issues, strictResultFields)
                checkExpr(expr.right, scope, defaultScope, resultFields, issues, strictResultFields)
            }
            is LogicalExpressionNode -> {
                if (expr.operator !in setOf("and", "or")) issues += err("UNKNOWN_OPERATOR", "Unknown logical operator '${expr.operator}'", expr.location)
                expr.operands.forEach { checkExpr(it, scope, defaultScope, resultFields, issues, strictResultFields) }
            }
            is UnaryExpressionNode -> {
                if (expr.operator != "not") issues += err("UNKNOWN_OPERATOR", "Unknown unary operator '${expr.operator}'")
                checkExpr(expr.operand, scope, defaultScope, resultFields, issues, strictResultFields)
            }
            is UnaryPostfixExpressionNode -> {
                if (expr.operator !in setOf("exists", "empty")) issues += err("UNKNOWN_OPERATOR", "Unknown postfix operator '${expr.operator}'")
                checkExpr(expr.operand, scope, defaultScope, resultFields, issues, strictResultFields)
            }
            is ListLiteralNode -> expr.items.forEach { checkExpr(it, scope, defaultScope, resultFields, issues, strictResultFields) }
            is MapLiteralNode -> expr.entries.values.forEach { checkExpr(it, scope, defaultScope, resultFields, issues, strictResultFields) }
            is TemplateStringNode -> expr.parts.forEach { checkExpr(it, scope, defaultScope, resultFields, issues, strictResultFields) }
            is CallExpressionNode -> expr.args.forEach { checkExpr(it, scope, defaultScope, resultFields, issues, strictResultFields) }
            is IndexExpressionNode -> {
                checkExpr(expr.target, scope, defaultScope, resultFields, issues, strictResultFields)
                checkExpr(expr.index, scope, defaultScope, resultFields, issues, strictResultFields)
            }
            is MemberExpressionNode -> checkExpr(expr.target, scope, defaultScope, resultFields, issues, strictResultFields)
            else -> Unit
        }
    }

    /**
     * Validates expression references for a value that is constrained by a module schema.
     */
    private fun checkSchemaExpression(
        expr: ExpressionNode,
        field: org.flowlang.modules.SchemaField,
        scope: Scope,
        issues: MutableList<ValidationIssue>
    ) {
        if (isSchemaSymbolicBareword(expr, field, scope)) return
        checkExpr(expr, scope, "auto", null, issues)
    }

    private fun validateSchemaValue(
        label: String,
        expr: ExpressionNode,
        field: org.flowlang.modules.SchemaField,
        scope: Scope,
        loc: org.flowlang.ast.SourceLocation?,
        issues: MutableList<ValidationIssue>
    ) {
        validateValueType(label, expr, field, loc, issues)
        checkSchemaExpression(expr, field, scope, issues)
    }

    private fun isSchemaSymbolicBareword(
        expr: ExpressionNode,
        field: org.flowlang.modules.SchemaField,
        scope: Scope
    ): Boolean {
        if (expr !is ReferenceNode) return false
        if (expr.path.size != 1) return false
        if (scope.has(expr.path.first())) return false
        return field.type in setOf(SchemaType.TEXT, SchemaType.DURATION)
    }

    private fun validateValueType(
        label: String, expr: ExpressionNode, field: org.flowlang.modules.SchemaField,
        loc: org.flowlang.ast.SourceLocation?, issues: MutableList<ValidationIssue>
    ) {
        if (!isCompatible(expr, field.type)) {
            issues += err("TYPE_MISMATCH", "$label expects type '${field.type}' but got ${expr.type}", loc)
        }
        if (field.sensitive && expr is StringLiteralNode) {
            issues += err("SECRET_PLAINTEXT", "$label is sensitive and must use secret(...), not a plain string", loc)
        }
    }

    private fun isCompatible(expr: ExpressionNode, expected: SchemaType): Boolean =
        SchemaTypeCompatibility.accepts(expected, schemaValueKind(expr))

    private fun schemaValueKind(expr: ExpressionNode): SchemaValueKind = when (expr) {
        is StringLiteralNode, is TemplateStringNode, is IdentifierLiteralNode -> SchemaValueKind.TEXT
        is NumberLiteralNode -> SchemaValueKind.NUMBER
        is BooleanLiteralNode -> SchemaValueKind.BOOLEAN
        is ListLiteralNode -> SchemaValueKind.LIST
        is MapLiteralNode -> SchemaValueKind.MAP
        is SecretRefNode -> SchemaValueKind.SECRET
        is NullLiteralNode -> SchemaValueKind.NULL
        else -> SchemaValueKind.DYNAMIC
    }

    /**
     * The lexical validator predates control-flow joins. It can therefore report
     * UNRESOLVED_REFERENCE for a value that the shared analysis proved safe, or
     * provide an imprecise unresolved error for a maybe/ambiguous value. Reconcile
     * those diagnostics by occurrence, then add the authoritative flow issues.
     */
    private fun reconcileAvailabilityIssues(
        issues: MutableList<ValidationIssue>,
        availability: FlowAvailabilityAnalysis
    ) {
        availability.uses
            .filter { use ->
                use.accepted || use.state.availability == FlowValueAvailability.MAYBE_DEFINED
            }
            .forEach { use -> removeOneLexicalUnresolved(issues, use.binding, use.location) }

        availability.issues.forEach { issue ->
            if (issue.kind != FlowAvailabilityIssueKind.UNRESOLVED) {
                removeOneLexicalUnresolved(issues, issue.binding, issue.location)
            }
            val validationIssue = err(issue.code, issue.message, issue.location)
            if (validationIssue !in issues) issues += validationIssue
        }
    }

    private fun removeOneLexicalUnresolved(
        issues: MutableList<ValidationIssue>,
        binding: String,
        location: SourceLocation?
    ) {
        val index = issues.indexOfFirst { issue ->
            issue.code == "UNRESOLVED_REFERENCE" &&
                issue.message.contains("'$binding'") &&
                (location == null || issue.location == location)
        }
        if (index >= 0) issues.removeAt(index)
    }

    private fun err(code: String, message: String, loc: org.flowlang.ast.SourceLocation? = null) =
        ValidationIssue("error", code, message, loc)

    private fun warn(code: String, message: String, loc: org.flowlang.ast.SourceLocation? = null) =
        ValidationIssue("warning", code, message, loc)

    /** Lexical scope with parent chaining. */
    private class Scope(private val parent: Scope? = null) {
        private val names = mutableSetOf<String>()
        fun declare(name: String) { names += name }
        fun has(name: String): Boolean = name in names || (parent?.has(name) ?: false)
        fun child() = Scope(this)
    }
}