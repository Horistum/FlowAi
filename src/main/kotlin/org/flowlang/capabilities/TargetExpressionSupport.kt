package org.flowlang.capabilities

import org.flowlang.ast.*
import org.flowlang.parser.ExpressionParser

/** Source of an explicit target expression-support declaration. */
enum class TargetExpressionEvidenceKind {
    TARGET_REGISTRY,
    TARGET_NOTES
}

/**
 * Resolved expression-language evidence for one target.
 *
 * Feature identifiers describe Flow AST requirements rather than target names.
 * A target may declare complete Flow support with [supportsAll] or list the exact
 * expression features its adapter can preserve. Missing evidence always fails closed.
 */
data class TargetExpressionSupportDeclaration(
    val profileId: String,
    val evidenceKind: TargetExpressionEvidenceKind,
    val evidenceReference: String,
    val supportsAll: Boolean = false,
    val features: Set<String> = emptySet()
)

data class TargetExpressionSupportDecision(
    val target: String,
    val supported: Boolean,
    val profileId: String? = null,
    val evidenceKind: TargetExpressionEvidenceKind? = null,
    val evidenceReference: String? = null,
    val requiredFeatures: Set<String> = emptySet(),
    val missingFeatures: Set<String> = emptySet(),
    val reason: String
)

/** Generic Flow expression feature identifiers used by target notes and registries. */
object TargetExpressionFeatures {
    const val LITERAL = "node.literal"
    const val REFERENCE = "node.reference"
    const val SECRET_REFERENCE = "node.secret-reference"
    const val MEMBER_ACCESS = "node.member-access"
    const val INDEX_ACCESS = "node.index-access"
    const val SAFE_ACCESS = "access.safe"
    const val TEMPLATE = "node.template"
    const val LIST = "node.list"
    const val MAP = "node.map"
    const val CALL_ANY = "call.any"

    fun call(function: String): String = "call.$function"
    fun unary(operator: String): String = "operator.unary.$operator"
    fun postfix(operator: String): String = "operator.postfix.$operator"
    fun logical(operator: String): String = "operator.logical.$operator"
    fun binary(operator: String): String = "operator.binary.$operator"

    fun isKnown(feature: String): Boolean = feature in STATIC_FEATURES ||
        feature.startsWith("call.") ||
        feature.startsWith("operator.unary.") ||
        feature.startsWith("operator.postfix.") ||
        feature.startsWith("operator.logical.") ||
        feature.startsWith("operator.binary.")

    private val STATIC_FEATURES = setOf(
        LITERAL,
        REFERENCE,
        SECRET_REFERENCE,
        MEMBER_ACCESS,
        INDEX_ACCESS,
        SAFE_ACCESS,
        TEMPLATE,
        LIST,
        MAP,
        CALL_ANY
    )
}

/**
 * Evaluates Flow expression support from explicit target evidence.
 *
 * This object contains no target-id switch. Target-specific knowledge belongs in
 * target registry or notes declarations, while translators remain adapter concerns.
 */
object TargetExpressionSupport {
    fun evaluate(target: TargetCapability, condition: String): TargetExpressionSupportDecision {
        val parsed = try {
            ExpressionParser.parseSource(condition)
        } catch (e: Exception) {
            return TargetExpressionSupportDecision(
                target = target.target,
                supported = false,
                profileId = target.expressionSupport?.profileId,
                evidenceKind = target.expressionSupport?.evidenceKind,
                evidenceReference = target.expressionSupport?.evidenceReference,
                reason = "Condition could not be parsed for target '${target.target}': ${e.message}"
            )
        }
        return evaluate(target.target, target.expressionSupport, parsed)
    }

    fun evaluate(target: TargetCapability, expression: ExpressionNode): TargetExpressionSupportDecision =
        evaluate(target.target, target.expressionSupport, expression)

    fun evaluate(
        target: String,
        declaration: TargetExpressionSupportDeclaration?,
        expression: ExpressionNode
    ): TargetExpressionSupportDecision {
        val required = requiredFeatures(expression)
        if (declaration == null) {
            return TargetExpressionSupportDecision(
                target = target,
                supported = false,
                requiredFeatures = required,
                missingFeatures = required,
                reason = "Target '$target' has no declared expression-support evidence."
            )
        }
        declarationValidationReason(declaration)?.let { invalid ->
            return TargetExpressionSupportDecision(
                target = target,
                supported = false,
                profileId = declaration.profileId,
                evidenceKind = declaration.evidenceKind,
                evidenceReference = declaration.evidenceReference,
                requiredFeatures = required,
                missingFeatures = required,
                reason = "Target '$target' has invalid expression-support evidence: $invalid"
            )
        }
        if (declaration.supportsAll) {
            return TargetExpressionSupportDecision(
                target = target,
                supported = true,
                profileId = declaration.profileId,
                evidenceKind = declaration.evidenceKind,
                evidenceReference = declaration.evidenceReference,
                requiredFeatures = required,
                reason = "Target '$target' expression profile '${declaration.profileId}' declares complete Flow expression support via ${declaration.evidenceReference}."
            )
        }

        val missing = required.filterNotTo(sortedSetOf()) { requiredFeature ->
            requiredFeature in declaration.features ||
                (requiredFeature.startsWith("call.") && TargetExpressionFeatures.CALL_ANY in declaration.features)
        }
        return if (missing.isEmpty()) {
            TargetExpressionSupportDecision(
                target = target,
                supported = true,
                profileId = declaration.profileId,
                evidenceKind = declaration.evidenceKind,
                evidenceReference = declaration.evidenceReference,
                requiredFeatures = required,
                reason = "Target '$target' expression profile '${declaration.profileId}' covers all required features via ${declaration.evidenceReference}."
            )
        } else {
            TargetExpressionSupportDecision(
                target = target,
                supported = false,
                profileId = declaration.profileId,
                evidenceKind = declaration.evidenceKind,
                evidenceReference = declaration.evidenceReference,
                requiredFeatures = required,
                missingFeatures = missing,
                reason = "Target '$target' expression profile '${declaration.profileId}' from ${declaration.evidenceReference} does not declare: ${missing.joinToString(", ")}."
            )
        }
    }

    fun unsupportedReason(target: TargetCapability, condition: String): String? =
        evaluate(target, condition).takeUnless { it.supported }?.reason

    fun unsupportedReason(target: TargetCapability, expression: ExpressionNode): String? =
        evaluate(target, expression).takeUnless { it.supported }?.reason

    fun unsupportedReason(
        target: String,
        declaration: TargetExpressionSupportDeclaration?,
        expression: ExpressionNode
    ): String? = evaluate(target, declaration, expression).takeUnless { it.supported }?.reason

    fun requiredFeatures(expression: ExpressionNode): Set<String> = when (expression) {
        is StringLiteralNode,
        is NumberLiteralNode,
        is BooleanLiteralNode,
        is NullLiteralNode,
        is IdentifierLiteralNode -> setOf(TargetExpressionFeatures.LITERAL)

        is SecretRefNode -> setOf(TargetExpressionFeatures.SECRET_REFERENCE)
        is ReferenceNode -> buildSet {
            add(TargetExpressionFeatures.REFERENCE)
            if (expression.safe) add(TargetExpressionFeatures.SAFE_ACCESS)
        }
        is MemberExpressionNode -> buildSet {
            add(TargetExpressionFeatures.MEMBER_ACCESS)
            if (expression.safe) add(TargetExpressionFeatures.SAFE_ACCESS)
            addAll(requiredFeatures(expression.target))
        }
        is IndexExpressionNode -> buildSet {
            add(TargetExpressionFeatures.INDEX_ACCESS)
            if (expression.safe) add(TargetExpressionFeatures.SAFE_ACCESS)
            addAll(requiredFeatures(expression.target))
            addAll(requiredFeatures(expression.index))
        }
        is TemplateStringNode -> buildSet {
            add(TargetExpressionFeatures.TEMPLATE)
            expression.parts.forEach { addAll(requiredFeatures(it)) }
        }
        is ListLiteralNode -> buildSet {
            add(TargetExpressionFeatures.LIST)
            expression.items.forEach { addAll(requiredFeatures(it)) }
        }
        is MapLiteralNode -> buildSet {
            add(TargetExpressionFeatures.MAP)
            expression.entries.values.forEach { addAll(requiredFeatures(it)) }
        }
        is CallExpressionNode -> buildSet {
            add(TargetExpressionFeatures.call(expression.function))
            expression.args.forEach { addAll(requiredFeatures(it)) }
        }
        is UnaryExpressionNode -> buildSet {
            add(TargetExpressionFeatures.unary(expression.operator))
            addAll(requiredFeatures(expression.operand))
        }
        is UnaryPostfixExpressionNode -> buildSet {
            add(TargetExpressionFeatures.postfix(expression.operator))
            addAll(requiredFeatures(expression.operand))
        }
        is LogicalExpressionNode -> buildSet {
            add(TargetExpressionFeatures.logical(expression.operator))
            expression.operands.forEach { addAll(requiredFeatures(it)) }
        }
        is BinaryExpressionNode -> buildSet {
            add(TargetExpressionFeatures.binary(expression.operator))
            addAll(requiredFeatures(expression.left))
            addAll(requiredFeatures(expression.right))
        }
    }

    fun declarationValidationReason(declaration: TargetExpressionSupportDeclaration): String? = when {
        declaration.profileId.isBlank() -> "profileId must not be blank."
        declaration.evidenceReference.isBlank() -> "evidenceReference must not be blank."
        declaration.supportsAll && declaration.features.isNotEmpty() -> "supportsAll profiles must not also list partial features."
        declaration.features.any { !TargetExpressionFeatures.isKnown(it) } ->
            "unknown expression feature(s): ${declaration.features.filterNot(TargetExpressionFeatures::isKnown).sorted().joinToString(", ")}."
        else -> null
    }
}
