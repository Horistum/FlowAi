package org.flowlang.targets.builtin

import org.flowlang.ast.*
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetMappingNote
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.parser.ExpressionParser
import org.flowlang.projection.ProjectionBinding
import org.flowlang.projection.ProjectionBindingKind

internal object JenkinsProjectionPayloadKinds {
    const val JENKINS_STEP = "JENKINS_STEP"
    const val JENKINS_STRUCTURE = "JENKINS_STRUCTURE"
}

internal object JenkinsProjectionSyntax : ProjectionValueSyntax {
    override fun renderOpaque(name: String): String = "env.${safeEnvName(name)}"
    override fun renderInput(name: String): String = groovyProperty("params", name)
    override fun renderInputInterpolation(name: String): String = "\${${renderInput(name)}}"
    fun bindingValue(name: String): String = "credentials(${groovyString(name)})"
    fun boundary(): String = "withCredentials"
    fun mappingSpec(name: String): String =
        "string(credentialsId: ${groovyString(name)}, variable: ${groovyString(safeEnvName(name))})"
    override fun mappingNote(name: String): String =
        "value mapping requirement: ${boundary()}([${mappingSpec(name)}]) exposes env.${safeEnvName(name)}"
}

internal object JenkinsProjectionBindingRenderer {
    fun jenkinsArgument(binding: ProjectionBinding, context: String): String = when (binding.kind) {
        ProjectionBindingKind.LITERAL,
        ProjectionBindingKind.TASK_PARAMETER,
        ProjectionBindingKind.TASK_INPUT,
        ProjectionBindingKind.TASK_METADATA -> groovyString(requireNotNull(binding.value) { "$context ${binding.kind} binding is unresolved." })
        ProjectionBindingKind.FLOW_INPUT -> groovyProperty("params", requireName(binding, context))
        ProjectionBindingKind.SECRET -> "env.${safeEnvName(requireName(binding, context))}"
        ProjectionBindingKind.TARGET_EXPRESSION -> {
            require(binding.target == "jenkins") { "$context contains a target expression for '${binding.target}', not Jenkins." }
            requireNotNull(binding.expression) { "$context target expression is missing expression text." }
        }
        ProjectionBindingKind.TASK_OUTPUT -> error("$context uses TASK_OUTPUT, but Jenkins output binding requires an explicit renderer contract.")
        ProjectionBindingKind.ARTIFACT -> error("$context uses ARTIFACT, but Jenkins artifact binding requires an explicit renderer contract.")
    }

    private fun requireName(binding: ProjectionBinding, context: String): String =
        requireNotNull(binding.name) { "$context ${binding.kind} binding is missing name." }
}

/** Complete, non-interpolating Groovy string literal. Regex text is data too. */
internal fun groovyString(value: String): String = "'" + groovyEscape(value) + "'"

/** Content of a single-quoted literal. Escape backslash before interpreting quotes. */
internal fun groovyEscape(value: String): String = buildString {
    value.forEach { character ->
        append(when (character) {
            '\\' -> "\\\\"
            '\'' -> "\\'"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            '\b' -> "\\b"
            '\u000C' -> "\\f"
            else -> if (character.code < 0x20 || character == '\u007F' || character == '\u2028' || character == '\u2029') {
                "\\u" + character.code.toString(16).padStart(4, '0')
            } else character.toString()
        })
    }
}

private val GROOVY_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** Preserve unusual property names instead of sanitizing them into a different identity. */
internal fun groovyProperty(target: String, name: String, safe: Boolean = false): String =
    target + (if (safe) "?." else ".") +
        (if (GROOVY_IDENTIFIER.matches(name)) name else groovyString(name))

/** Free variables/callees cannot use the quoted-property syntax. Reject unsupported names. */
internal fun groovyIdentifier(name: String): String {
    require(GROOVY_IDENTIFIER.matches(name)) { "Unsupported Jenkins Groovy identifier '$name'." }
    return name
}

/** Condition secrets use typed expression nodes, never substring matching or native variable inference. */
internal object JenkinsConditionSecrets {
    fun names(manifest: TargetManifest): List<String> = manifest.jobs.flatMap { it.steps }
        .flatMap { it.flatten() }.flatMap { step ->
            if (step.type == "condition") {
                TargetProjectionDiagnostics.opaqueNames(step.copy(params = step.params - "condition")) +
                    step.params["condition"]?.let { names(ExpressionParser.parseSource(it)) }.orEmpty()
            } else TargetProjectionDiagnostics.opaqueNames(step)
        }.distinct()

    private fun names(e: ExpressionNode): List<String> = when (e) {
        is SecretRefNode -> listOf(e.name)
        is MemberExpressionNode -> names(e.target)
        is IndexExpressionNode -> names(e.target) + names(e.index)
        is TemplateStringNode -> e.parts.flatMap(::names)
        is ListLiteralNode -> e.items.flatMap(::names)
        is MapLiteralNode -> e.entries.values.flatMap(::names)
        is CallExpressionNode -> e.args.flatMap(::names)
        is UnaryExpressionNode -> names(e.operand)
        is UnaryPostfixExpressionNode -> names(e.operand)
        is LogicalExpressionNode -> e.operands.flatMap(::names)
        is BinaryExpressionNode -> names(e.left) + names(e.right)
        is StringLiteralNode, is NumberLiteralNode, is BooleanLiteralNode, is NullLiteralNode,
        is IdentifierLiteralNode, is ReferenceNode -> emptyList()
    }
}

/** Native value availability follows sequential execution and lexical scope, never fixture shape. */
internal object JenkinsConditionValueScope {
    private const val FEATURE = "condition.value-binding"
    private data class Problem(val feature: String, val message: String, val requirement: String)
    private data class ProducedValue(val producerId: String, val variable: String)
    internal data class Bindings(
        val producers: Map<String, String>,
        val conditions: Map<String, Map<List<String>, String>>
    )
    private data class Analysis(val steps: List<TargetStep>, val bindings: Bindings, val problems: List<Problem>)

    fun reconcile(steps: List<TargetStep>, inputs: Set<String>): List<TargetStep> = analyze(steps, inputs).steps

    fun requireBound(steps: List<TargetStep>, inputs: Set<String>): Bindings {
        val analysis = analyze(steps, inputs)
        require(analysis.problems.isEmpty()) { analysis.problems.joinToString { it.message } }
        return analysis.bindings
    }

    private fun analyze(steps: List<TargetStep>, inputs: Set<String>): Analysis {
        val problems = mutableListOf<Problem>()
        val producers = linkedMapOf<String, String>()
        val conditions = linkedMapOf<String, Map<List<String>, String>>()
        // Authored lexical names must not capture renderer-owned variables.
        val reserved = inputs + steps.flatMap { it.flatten() }.flatMap {
            listOfNotNull(it.params["item"], it.metadata["workflowFailureErrorBinding"])
        }
        var nextVariable = 0
        fun variable(): String {
            var candidate: String
            do {
                nextVariable++
                candidate = "flowCheckoutSucceeded" + if (nextVariable == 1) "" else nextVariable.toString()
            } while (candidate in reserved)
            return candidate
        }
        fun visit(block: List<TargetStep>, roots: Set<String>, inherited: Map<List<String>, ProducedValue>): List<TargetStep> {
            val available = inherited.toMutableMap()
            val declared = mutableSetOf<String>()
            return block.map { original ->
                val bound = linkedMapOf<List<String>, ProducedValue>()
                val problem = problem(original, roots, available, bound)
                if (problem == null && bound.isNotEmpty()) {
                    conditions[original.id] = bound.mapValues { it.value.variable }
                    bound.values.forEach { producers[it.producerId] = it.variable }
                }
                val local = if (original.type == "loop") original.params["item"]?.let(::setOf).orEmpty() else emptySet()
                val children = if (original.type == "try") original.children.map { child ->
                    val error = if (original.metadata["workflowFailurePolicy"] == "true" && child.type == "error-handler")
                        original.metadata["workflowFailureErrorBinding"]?.let(::setOf).orEmpty() else emptySet()
                    visit(listOf(child), roots + error, available.filterKeys { it.first() !in error }).single()
                } else visit(original.children, roots + local, available.filterKeys { it.first() !in local })
                // A child block never exports its bindings: a branch may not execute, and a failed body
                // cannot establish a value in a handler or successor. Each job starts with an empty scope.
                val alias = original.metadata["resultName"]
                if (alias != null) {
                    require(alias.isNotBlank() && alias !in roots && declared.add(alias)) {
                        "Jenkins result alias '$alias' conflicts with an existing lexical binding."
                    }
                    available.keys.removeAll { it.first() == alias }
                    if (JenkinsCheckoutResultBinding.supports(original))
                        available[listOf(alias, "ok")] = ProducedValue(original.id, variable())
                }
                val step = if (children == original.children) original else original.copy(children = children)
                if (problem == null) step else {
                    problems += problem
                    step.copy(
                        materialization = TargetMaterialization.adapterRequired(step.materialization.capability, problem.message,
                            requirements = mapOf(problem.requirement to "required")),
                        rendererPayload = null,
                        mappingNotes = step.mappingNotes + TargetMappingNote("warning", "jenkins", step.id, problem.feature, problem.message))
                }
            }
        }
        val transformed = visit(steps, inputs, emptyMap())
        return Analysis(transformed, Bindings(producers, conditions), problems)
    }

    private fun problem(step: TargetStep, roots: Set<String>, available: Map<List<String>, ProducedValue>,
        bound: MutableMap<List<String>, ProducedValue>): Problem? {
        if (step.type != "condition") return null
        fun valueProblem(message: String) = Problem(FEATURE, message, "nativeValueBinding")
        val source = step.params["condition"] ?: return valueProblem("Jenkins condition '${step.id}' has no expression.")
        val expression = runCatching { ExpressionParser.parseSource(source) }.getOrElse {
            return valueProblem("Jenkins condition '${step.id}' has an invalid expression.")
        }
        val calls = JenkinsExpressionCalls.names(expression).sorted()
        if (calls.isNotEmpty()) return Problem("condition.function-binding",
            "Jenkins condition '${step.id}' has no native function binding for ${calls.joinToString()}; canonical expression calls cannot invoke ambient Jenkins methods.",
            "nativeFunctionBinding")
        val missing = references(expression).filter { ref ->
            val value = available[ref.path]?.takeUnless { ref.safe }
            if (value != null) { bound[ref.path] = value; false }
            else ref.path.firstOrNull() !in roots
        }.map { it.path.joinToString(".") }.distinct().sorted()
        return if (missing.isEmpty()) null else
            valueProblem("Jenkins condition '${step.id}' has no native value binding for ${missing.joinToString()}; task outputs and ambient runtime values require an explicit adapter contract.")
    }

    private fun references(e: ExpressionNode): List<ReferenceNode> = when (e) {
        is ReferenceNode -> listOf(e)
        is MemberExpressionNode -> references(e.target)
        is IndexExpressionNode -> references(e.target) + references(e.index)
        is TemplateStringNode -> e.parts.flatMap(::references)
        is ListLiteralNode -> e.items.flatMap(::references)
        is MapLiteralNode -> e.entries.values.flatMap(::references)
        is CallExpressionNode -> e.args.flatMap(::references)
        is UnaryExpressionNode -> references(e.operand)
        is UnaryPostfixExpressionNode -> references(e.operand)
        is LogicalExpressionNode -> e.operands.flatMap(::references)
        is BinaryExpressionNode -> references(e.left) +
            (if (e.operator == "matches" && e.right is ReferenceNode) emptyList() else references(e.right))
        is StringLiteralNode, is NumberLiteralNode, is BooleanLiteralNode, is NullLiteralNode,
        is IdentifierLiteralNode, is SecretRefNode -> emptyList()
    }
}

/**
 * Adapter-owned field contract for modules/git.yaml checkout.ok. Native normal completion
 * establishes true; failure throws before the binding. Consumer shape is irrelevant.
 * Other output fields and checkout implementations require their own native contracts.
 */
internal object JenkinsCheckoutResultBinding {
    fun supports(step: TargetStep): Boolean {
        val payload = step.rendererPayload ?: return false
        if (step.type != "action" || step.module != "git" || step.action != "checkout" || step.children.isNotEmpty() ||
            step.materialization.status != TargetMaterializationStatus.NATIVE ||
            payload.kind != JenkinsProjectionPayloadKinds.JENKINS_STEP || payload.reference != "git") return false
        return runCatching {
            val context = "Jenkins checkout result '${step.id}'"
            CheckoutProjectionValues.gitUrl(payload, "url", context)
            val branch = CheckoutProjectionValues.branch(payload, "branch", context)
            val depth = payload.bindings["depth"]?.let { CheckoutProjectionValues.depth(payload, "depth", context) } ?: 0
            depth == 0 && !Regex("[0-9a-fA-F]{40}").matches(branch)
        }.getOrDefault(false)
    }
}

/** No generic expression function currently has a Jenkins mapping. Typed secrets are not calls. */
internal object JenkinsExpressionCalls {
    fun names(e: ExpressionNode): Set<String> = when (e) {
        is CallExpressionNode -> setOf(e.function) + e.args.flatMap(::names)
        is MemberExpressionNode -> names(e.target)
        is IndexExpressionNode -> names(e.target) + names(e.index)
        is TemplateStringNode -> e.parts.flatMap(::names).toSet()
        is ListLiteralNode -> e.items.flatMap(::names).toSet()
        is MapLiteralNode -> e.entries.values.flatMap(::names).toSet()
        is UnaryExpressionNode -> names(e.operand)
        is UnaryPostfixExpressionNode -> names(e.operand)
        is LogicalExpressionNode -> e.operands.flatMap(::names).toSet()
        is BinaryExpressionNode -> names(e.left) + names(e.right)
        is StringLiteralNode, is NumberLiteralNode, is BooleanLiteralNode, is NullLiteralNode,
        is IdentifierLiteralNode, is SecretRefNode, is ReferenceNode -> emptySet()
    }
}
