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

/** A result alias is not a native binding. Track only values the renderer actually introduces. */
internal object JenkinsConditionValueScope {
    private const val FEATURE = "condition.value-binding"
    private data class Problem(val feature: String, val message: String, val requirement: String)

    fun reconcile(steps: List<TargetStep>, inputs: Set<String>): List<TargetStep> {
        val checkout = JenkinsCheckoutResultBinding.resolve(steps, inputs)
        return visit(steps, inputs) { step, roots ->
            if (step.id == checkout?.conditionId) return@visit step
            val problem = problem(step, roots) ?: return@visit step
            step.copy(
                materialization = TargetMaterialization.adapterRequired(step.materialization.capability, problem.message,
                    requirements = mapOf(problem.requirement to "required")),
                rendererPayload = null,
                mappingNotes = step.mappingNotes + TargetMappingNote("warning", "jenkins", step.id, problem.feature, problem.message))
        }
    }

    fun requireBound(manifest: TargetManifest) {
        val inputs = manifest.inputs.map { it.name }.toSet()
        manifest.jobs.forEach { job ->
            val checkout = if (manifest.jobs.size == 1) JenkinsCheckoutResultBinding.resolve(job.steps, inputs) else null
            visit(job.steps, inputs) { step, roots ->
                if (step.id == checkout?.conditionId) return@visit step
                val problem = problem(step, roots)
                require(problem == null) { problem?.message.orEmpty() }
                step
            }
        }
    }

    private fun visit(steps: List<TargetStep>, roots: Set<String>,
        transform: (TargetStep, Set<String>) -> TargetStep): List<TargetStep> = steps.map { step ->
        val children = step.children.map { child ->
            val local = when {
                step.type == "loop" -> step.params["item"]?.let(::setOf).orEmpty()
                step.type == "try" && step.metadata["workflowFailurePolicy"] == "true" && child.type == "error-handler" ->
                    step.metadata["workflowFailureErrorBinding"]?.let(::setOf).orEmpty()
                else -> emptySet()
            }
            visit(listOf(child), roots + local, transform).single()
        }
        transform(if (children == step.children) step else step.copy(children = children), roots)
    }

    private fun problem(step: TargetStep, roots: Set<String>): Problem? {
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
        val missing = (references(expression) - roots).sorted()
        return if (missing.isEmpty()) null else
            valueProblem("Jenkins condition '${step.id}' has no native value binding for ${missing.joinToString()}; task outputs and ambient runtime values require an explicit adapter contract.")
    }

    private fun references(e: ExpressionNode): Set<String> = when (e) {
        is ReferenceNode -> setOf(e.path.firstOrNull().orEmpty())
        is MemberExpressionNode -> references(e.target)
        is IndexExpressionNode -> references(e.target) + references(e.index)
        is TemplateStringNode -> e.parts.flatMap { references(it) }.toSet()
        is ListLiteralNode -> e.items.flatMap { references(it) }.toSet()
        is MapLiteralNode -> e.entries.values.flatMap { references(it) }.toSet()
        is CallExpressionNode -> e.args.flatMap { references(it) }.toSet()
        is UnaryExpressionNode -> references(e.operand)
        is UnaryPostfixExpressionNode -> references(e.operand)
        is LogicalExpressionNode -> e.operands.flatMap { references(it) }.toSet()
        // Named matches patterns are language symbols, checked by the expression translator.
        is BinaryExpressionNode -> references(e.left) +
            (if (e.operator == "matches" && e.right is ReferenceNode) emptySet() else references(e.right))
        is StringLiteralNode, is NumberLiteralNode, is BooleanLiteralNode, is NullLiteralNode,
        is IdentifierLiteralNode, is SecretRefNode -> emptySet()
    }
}

/**
 * Partial output contract for modules/git.yaml checkout.ok: normal native completion
 * establishes true; a failed checkout throws before the binding is introduced.
 * Only one direct consumer is admitted. No result record, path or generic transfer is implied.
 */
internal data class JenkinsCheckoutResultBinding(val producerId: String, val conditionId: String, val expected: Boolean) {
    companion object {
        const val VARIABLE = "flowCheckoutSucceeded"

        fun resolve(steps: List<TargetStep>, inputs: Set<String>): JenkinsCheckoutResultBinding? {
            if (inputs.isNotEmpty() || steps.size != 2) return null
            val producer = steps[0]
            val condition = steps[1]
            val guardPayload = condition.rendererPayload ?: return null
            val alias = producer.metadata["resultName"]?.takeIf { it.isNotBlank() } ?: return null
            if (!checkout(producer) || producer.dependsOn.isNotEmpty() || condition.type != "condition" ||
                condition.materialization.status != TargetMaterializationStatus.NATIVE ||
                guardPayload.kind != JenkinsProjectionPayloadKinds.JENKINS_STRUCTURE ||
                guardPayload.reference != "if" || condition.params.keys != setOf("condition") ||
                condition.children.size != 1 || condition.dependsOn.isNotEmpty()) return null
            val consumer = condition.children.single()
            if (!checkout(consumer) || consumer.metadata.containsKey("resultName") ||
                consumer.dependsOn.any { it != producer.id } ||
                setOf(producer.id, condition.id, consumer.id).size != 3) return null
            val expression = runCatching { ExpressionParser.parseSource(condition.params.getValue("condition")) }.getOrNull()
                as? BinaryExpressionNode ?: return null
            val reference = expression.left as? ReferenceNode ?: return null
            val expected = expression.right as? BooleanLiteralNode ?: return null
            if (expression.operator != "==" || reference.safe || reference.path != listOf(alias, "ok")) return null
            return JenkinsCheckoutResultBinding(producer.id, condition.id, expected.value)
        }

        private fun checkout(step: TargetStep): Boolean {
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
