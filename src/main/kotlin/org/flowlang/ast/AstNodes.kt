package org.flowlang.ast

import org.flowlang.effects.SemanticEffect
import org.flowlang.controls.ControlDecision
import org.flowlang.controls.ControlEvidence
import org.flowlang.controls.ControlRequirement
import org.flowlang.standard.FlowStandardVersions

/**
 * Canonical Flow AST (docs/04). Platform-neutral: it must not depend on any
 * target. Source locations are optional and filled by the parser for diagnostics.
 */

data class SourceLocation(val line: Int, val column: Int)

data class FlowDocument(
    val astVersion: String = FlowStandardVersions.AST_VERSION,
    val sourceVersion: String = "1.0",
    val kind: String = "FlowDocument",
    val imports: List<ModuleImportNode> = emptyList(),
    val flow: FlowNode,
    val metadata: MetadataNode = MetadataNode()
)

data class MetadataNode(
    val sourceFile: String? = null,
    val createdBy: String? = "flow-parser",
    val generatedByAI: Boolean = false
)

data class ModuleImportNode(
    val type: String = "ModuleImport",
    val name: String,
    val version: String,
    val alias: String? = null,
    val sourceLocation: SourceLocation? = null
)

data class FlowNode(
    val type: String = "Flow",
    val name: String,
    val input: List<InputNode> = emptyList(),
    val vars: List<VariableNode> = emptyList(),
    val systems: List<SystemNode> = emptyList(),
    val triggers: List<TriggerNode> = emptyList(),
    val controlRequirements: List<ControlRequirement> = emptyList(),
    val controlEvidence: List<ControlEvidence> = emptyList(),
    val controlDecision: ControlDecision = ControlDecision(org.flowlang.controls.ControlDecisionStatus.ALLOWED),
    val steps: List<StatementNode> = emptyList(),
    val errorHandler: ErrorHandlerNode? = null
)


data class TriggerNode(
    val type: String = "Trigger",
    val id: String,
    val triggerType: String,
    val workflows: List<String> = listOf("main"),
    val schedule: ScheduleNode? = null,
    val event: String? = null,
    val params: Map<String, ExpressionNode> = emptyMap()
)

data class ScheduleNode(
    val type: String = "Schedule",
    val kind: String,
    val expression: String,
    val timezone: String? = null
)

data class InputNode(
    val type: String = "Input",
    val name: String,
    val valueType: ValueTypeNode,
    val required: Boolean = false,
    val default: ExpressionNode? = null,
    val sourceLocation: SourceLocation? = null
)

data class ValueTypeNode(
    val kind: String,
    val values: List<ExpressionNode> = emptyList()
)

data class VariableNode(
    val type: String = "Variable",
    val name: String,
    val value: ExpressionNode,
    val sourceLocation: SourceLocation? = null
)

data class SystemNode(
    val type: String = "System",
    val name: String,
    val systemType: String,
    val config: Map<String, ExpressionNode> = emptyMap(),
    val sourceLocation: SourceLocation? = null
)

sealed interface StatementNode { val type: String }

data class ActionNode(
    override val type: String = "Action",
    val module: String,
    val action: String,
    val target: ReferenceNode,
    val params: Map<String, ExpressionNode> = emptyMap(),
    val result: ResultBindingNode? = null,
    val handler: ResultHandlerNode? = null,
    val safety: SafetyNode? = null,
    /** Canonical, target-neutral effect evidence carried from Standard Intent lowering. */
    val semanticCapability: String? = null,
    val semanticEffects: List<SemanticEffect> = emptyList(),
    /** Explicit dependency names, primarily produced by the Standard Intent lowering layer.
     * They refer to result binding names, not target-specific job ids.
     */
    val dependsOn: List<String> = emptyList(),
    val sourceLocation: SourceLocation? = null
) : StatementNode

data class IfNode(
    override val type: String = "If",
    val condition: ExpressionNode,
    val then: List<StatementNode> = emptyList(),
    val otherwise: List<StatementNode> = emptyList()
) : StatementNode

data class ForNode(
    override val type: String = "For",
    val item: String,
    val source: ExpressionNode,
    val body: List<StatementNode> = emptyList()
) : StatementNode

data class ParallelNode(
    override val type: String = "Parallel",
    val branches: List<ParallelBranchNode>,
    val failFast: Boolean = true
) : StatementNode

data class ParallelBranchNode(
    val type: String = "ParallelBranch",
    val name: String? = null,
    val steps: List<StatementNode> = emptyList()
)

data class MatchNode(
    override val type: String = "Match",
    val source: ExpressionNode,
    val cases: List<WhenNode> = emptyList(),
    val errorCase: List<StatementNode>? = null,
    val defaultSteps: List<StatementNode> = emptyList()
) : StatementNode

data class RetryNode(
    override val type: String = "Retry",
    val policy: RetryPolicyNode,
    val steps: List<StatementNode> = emptyList(),
    val sourceLocation: SourceLocation? = null
) : StatementNode

data class RetryPolicyNode(
    val max: Int = 3,
    val delay: String = "10s",
    val backoff: String = "fixed"
)

data class FailNode(
    override val type: String = "Fail",
    val message: ExpressionNode
) : StatementNode

data class SkipNode(
    override val type: String = "Skip",
    val message: ExpressionNode
) : StatementNode

data class ApproveNode(
    override val type: String = "Approve",
    val mode: String = "manual",
    val params: Map<String, ExpressionNode> = emptyMap(),
    val result: ResultBindingNode? = null,
    /** Explicit dependency names produced by intent lowering. They refer to result binding names. */
    val dependsOn: List<String> = emptyList(),
    val sourceLocation: SourceLocation? = null
) : StatementNode

data class SetNode(
    override val type: String = "Set",
    val name: String,
    val value: ExpressionNode
) : StatementNode

data class TryNode(
    override val type: String = "Try",
    val steps: List<StatementNode> = emptyList(),
    val errorHandler: ErrorHandlerNode = ErrorHandlerNode()
) : StatementNode

data class TransformNode(
    override val type: String = "Transform",
    val source: ExpressionNode,
    val target: String,
    val where: ExpressionNode? = null,
    val select: Map<String, ExpressionNode> = emptyMap()
) : StatementNode

data class ValidateNode(
    override val type: String = "Validate",
    val target: ExpressionNode,
    val rules: List<ValidateRuleNode> = emptyList()
) : StatementNode

data class ValidateRuleNode(
    val type: String,                 // "required" | "expression"
    val reference: ExpressionNode? = null,
    val expression: ExpressionNode? = null
)

data class AggregateNode(
    override val type: String = "Aggregate",
    val source: ExpressionNode,
    val target: String,
    val fields: Map<String, ExpressionNode> = emptyMap()
) : StatementNode

data class ErrorHandlerNode(
    override val type: String = "ErrorHandler",
    val steps: List<StatementNode> = emptyList()
) : StatementNode

data class ResultBindingNode(
    val type: String = "ResultBinding",
    val name: String
)

data class ResultHandlerNode(
    val type: String = "ResultHandler",
    val rules: List<ResultHandlerRuleNode> = emptyList()
)

sealed interface ResultHandlerRuleNode { val type: String }

data class ExpectNode(
    override val type: String = "Expect",
    val expressions: List<ExpressionNode> = emptyList()
) : ResultHandlerRuleNode, StatementNode

data class WhenNode(
    override val type: String = "When",
    val condition: ExpressionNode? = null,
    val isError: Boolean = false,
    val steps: List<StatementNode> = emptyList()
) : ResultHandlerRuleNode

data class SafetyNode(
    val type: String = "Safety",
    val rule: String,                 // "requiresApproval" | "onlyIf" | custom
    val condition: ExpressionNode? = null
)
