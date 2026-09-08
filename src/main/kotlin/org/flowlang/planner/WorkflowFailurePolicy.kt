package org.flowlang.planner

/** Workflow-level outcome after the optional failure-handler region completes. */
enum class WorkflowFailureDisposition {
    PROPAGATE,
    RECOVER
}

/** Values that are valid at workflow failure-handler entry. */
data class WorkflowFailureHandlerEntry(
    val errorBinding: String = "error",
    val priorSuccessfulValuesAvailable: Boolean = false
) {
    init {
        require(errorBinding.isNotBlank()) { "Workflow failure error binding must not be blank." }
    }
}

/** Public graph-derived identity of the workflow failure-handler region. */
data class WorkflowFailureHandlerRegion(
    val id: String,
    val nodeIds: List<String>,
    val entry: WorkflowFailureHandlerEntry = WorkflowFailureHandlerEntry()
) {
    init {
        require(id.isNotBlank()) { "Workflow failure-handler region id must not be blank." }
        require(nodeIds.isNotEmpty()) { "Workflow failure-handler region must own at least one node." }
        require(nodeIds.none(String::isBlank)) { "Workflow failure-handler node ids must not be blank." }
        require(nodeIds.toSet().size == nodeIds.size) {
            "Workflow failure-handler region cannot repeat node ids."
        }
    }
}

/** First-class workflow failure meaning exposed by WorkflowExecutionPlanSet. */
data class WorkflowFailurePolicy(
    val disposition: WorkflowFailureDisposition = WorkflowFailureDisposition.PROPAGATE,
    val handler: WorkflowFailureHandlerRegion? = null
) {
    init {
        require(disposition != WorkflowFailureDisposition.RECOVER || handler != null) {
            "Workflow failure recovery requires an explicit handler region."
        }
    }
}

/** Planner-owned policy plus the exact legacy compatibility mirror. */
/** Planning input, not authorization. The canonical graph gate checks its exact plan projection. */
data class PlannedWorkflowFailurePolicy(
    val policy: WorkflowFailurePolicy = WorkflowFailurePolicy(),
    val handlerNodes: List<PlanNode> = emptyList(),
    val compatibilityBoundaryNodeId: String? = null
) {
    init {
        val handler = policy.handler
        require((handler == null) == handlerNodes.isEmpty()) {
            "Planned workflow failure policy and handler nodes must be present together."
        }
        require((handler == null) == (compatibilityBoundaryNodeId == null)) {
            "Planned workflow failure policy and compatibility boundary must be present together."
        }
        if (handler != null) {
            require(handler.nodeIds == handlerNodes.map(PlanNode::id)) {
                "Planned workflow failure policy does not name its exact handler nodes."
            }
            require(compatibilityBoundaryNodeId!!.isNotBlank()) {
                "Workflow failure compatibility boundary id must not be blank."
            }
        }
    }

    fun compatibilityMirror(): List<PlanNode> = policy.handler?.let {
        listOf(
            TryPlanNode(
                id = requireNotNull(compatibilityBoundaryNodeId),
                body = emptyList(),
                errorHandler = handlerNodes
            )
        )
    } ?: emptyList()

    companion object {
        fun none(): PlannedWorkflowFailurePolicy = PlannedWorkflowFailurePolicy()
    }
}
