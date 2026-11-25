package org.flowlang.standard

import org.flowlang.intent.CanonicalIntentMeaningAuthority
import org.flowlang.intent.StandardCapability

/**
 * Canonical catalog of Flow standard capabilities.
 *
 * This catalog is deliberately above modules and targets. It is the bridge between
 * human/AI intent and the lower technical layers. A user should be able to ask for
 * "backup", "deploy", "rotate secret" or "run incident runbook" without learning
 * whether the final target is Jenkins, Tekton, Argo Workflows or something else
 * that humanity invented to make YAML feel employed.
 */
data class StandardCapabilityDefinition(
    val capability: StandardCapability,
    val category: String,
    val maturity: String,
    val description: String,
    val typicalModules: List<String> = emptyList(),
    val requiredDesignQuestions: List<String> = emptyList(),
    val requiredParams: List<String> = emptyList(),
    val optionalParams: List<String> = emptyList(),
    val requiredSystems: List<String> = emptyList(),
    val loweringStrategy: String = "semantic-action",
    val targetImplications: List<String> = emptyList(),
    val notes: List<String> = emptyList()
) {
    /** These parameters require an explicit module action on the step. */
    val bindingOnlyParams: List<String>
        get() = (requiredParams + optionalParams).filter { it in CanonicalIntentMeaningAuthority.BINDING_METADATA_PARAMS }
}

object StandardIntentCatalog {
    val definitions: List<StandardCapabilityDefinition> = listOf(
        def(
            StandardCapability.CHECKOUT, "source", "stable", "Fetch or prepare source input.", catalogModules("git"), listOf("Where is the source located?"),
            optionalParams = listOf("url", "branch", "system", "target"),
            requiredSystems = listOf("git"),
            loweringStrategy = "module-action"
        ),
        def(
            StandardCapability.BUILD, "ci", "stable", "Build application or project artifacts.", catalogModules("standard"), listOf("Which build semantics or artifact should be produced?"),
            optionalParams = listOf("tool", "system", "target"),
            requiredSystems = listOf("standard")
        ),
        def(
            StandardCapability.TEST, "ci", "stable", "Represent test or validation intent without choosing a runtime command.", catalogModules("standard"), listOf("Which test suite or validation intent should be represented?"),
            optionalParams = listOf("suite", "tool", "system", "target"),
            requiredSystems = listOf("standard")
        ),
        def(
            StandardCapability.PACKAGE, "ci", "stable", "Package build outputs into distributable artifacts.", catalogModules("standard", "file"),
            optionalParams = listOf("artifact", "tool", "system", "target"),
            requiredSystems = listOf("standard")
        ),
        def(
            StandardCapability.BUILD_IMAGE, "delivery", "stable", "Build a container image.", requiredDesignQuestions = listOf("Which image name and tag should be produced?"),
            optionalParams = listOf("image", "path", "push", "system", "target")
        ),
        def(
            StandardCapability.PUSH_IMAGE, "delivery", "stable", "Publish a container image to a registry.", requiredDesignQuestions = listOf("Which registry should receive the image?"),
            requiredParams = listOf("image"),
            optionalParams = listOf("system", "target")
        ),
        def(
            StandardCapability.DEPLOY, "delivery", "stable", "Deploy application/configuration to a target environment.", catalogModules("kubernetes", "argocd", "helm"), listOf("What is the target environment?", "What deployment engine should be used?"),
            optionalParams = listOf("system", "target", "subject", "environment", "artifact", "version", "strategy", "namespace", "image"),
            requiredSystems = listOf("standard")
        ),
        def(
            StandardCapability.VERIFY, "delivery", "stable", "Verify the deployed or changed system health/status.", catalogModules("kubernetes", "argocd", "rest"),
            optionalParams = listOf("system", "target", "subject", "resource", "selector", "environment", "criteria"),
            requiredSystems = listOf("standard")
        ),
        def(
            StandardCapability.APPROVE, "governance", "stable", "Require human or policy approval before continuing.", catalogModules("standard"), listOf("Who can approve?", "When is approval required?"),
            optionalParams = listOf("message"),
            loweringStrategy = "approval-gate"
        ),
        def(
            StandardCapability.ROLLBACK, "resilience", "draft", "Return system to a previous known-good state.", catalogModules("standard", "kubernetes", "argocd", "helm"), listOf("What is the rollback strategy?"),
            optionalParams = listOf("reason", "flow", "application", "system", "target")
        ),
        def(
            StandardCapability.NOTIFY, "communication", "stable", "Send notification to a human or system channel.", catalogModules("notify"),
            optionalParams = listOf("subject", "body", "to", "channel", "system", "target"),
            requiredSystems = listOf("notify"),
            loweringStrategy = "module-action"
        ),
        def(
            StandardCapability.SYNC, "integration", "draft", "Synchronize data or state between systems.", catalogModules("rest", "database", "file"),
            requiredParams = listOf("source", "destination"),
            optionalParams = listOf("mode", "filters", "batches", "system", "target")
        ),
        def(
            StandardCapability.DATA_SYNC, "data", "draft", "Move data between sources and destinations.", catalogModules("rest", "database", "file"),
            requiredParams = listOf("source", "destination"),
            optionalParams = listOf("mode", "filters", "batches", "system", "target")
        ),
        def(
            StandardCapability.DATA_TRANSFORM, "data", "draft", "Transform source data into a target shape.", catalogModules("standard"),
            optionalParams = listOf("mapping", "operation", "system", "target")
        ),
        def(
            StandardCapability.TRANSFORM, "data", "draft", "Generic transform operation.", catalogModules("standard"),
            optionalParams = listOf("mapping", "operation", "system", "target")
        ),
        def(
            StandardCapability.VALIDATE, "quality", "stable", "Validate input, output, schema or policy constraints.", catalogModules("standard", "file"),
            optionalParams = listOf("operation", "schema", "target", "system")
        ),
        def(
            StandardCapability.BACKUP, "operations", "draft", "Create a backup of data/configuration/system state.", catalogModules("standard", "database", "file"), listOf("What must be backed up?", "Where should backup be stored?"),
            requiredParams = listOf("subject"),
            optionalParams = listOf("retention", "destination", "system", "target")
        ),
        def(
            StandardCapability.RESTORE, "operations", "draft", "Restore data/configuration/system state.", catalogModules("standard", "database", "file"), listOf("Which recovery point should be used?"),
            requiredParams = listOf("subject"),
            optionalParams = listOf("recoveryPoint", "system", "target")
        ),
        def(
            StandardCapability.CLEANUP, "operations", "stable", "Remove old temporary resources or data.", catalogModules("standard", "file", "kubernetes"), listOf("What safety condition prevents destructive cleanup?"),
            requiredParams = listOf("resource"),
            optionalParams = listOf("safety", "retention", "system", "target")
        ),
        def(
            StandardCapability.PROVISION, "infrastructure", "draft", "Create or update infrastructure/resources.", catalogModules("standard", "kubernetes"),
            optionalParams = listOf("tool", "mode", "stack", "resource", "system", "target")
        ),
        def(
            StandardCapability.DEPROVISION, "infrastructure", "draft", "Remove infrastructure/resources.", catalogModules("standard", "kubernetes"), listOf("What safety rule is required?"),
            requiredParams = listOf("target"),
            optionalParams = listOf("safety", "system")
        ),
        def(
            StandardCapability.DATABASE_MIGRATE, "data", "draft", "Apply database schema or data migrations with backup, validation and rollback planning.", catalogModules("database", "standard"), listOf("Which database is targeted?", "Which migration source/version should be applied?", "What backup and rollback plan is required?"),
            requiredParams = listOf("database"),
            optionalParams = listOf("migration", "version", "backup", "rollbackPlan", "dryRun", "system", "target")
        ),
        def(
            StandardCapability.CERTIFICATE_RENEW, "security", "draft", "Renew, deploy and verify a certificate without fabricating provider or expiry details.", catalogModules("standard", "kubernetes", "rest"), listOf("Which certificate should be renewed?", "Which provider or secret store owns it?", "Which service must be verified after renewal?"),
            requiredParams = listOf("certificate"),
            optionalParams = listOf("provider", "secret", "service", "namespace", "window", "system", "target")
        ),
        def(
            StandardCapability.CLUSTER_MAINTENANCE,
            "operations",
            "draft",
            "Run cluster maintenance with explicit scope, dry-run/approval and verification.",
            catalogModules("standard", "kubernetes"),
            listOf("Which cluster, namespace or resource scope is affected?", "Is the operation destructive or production-impacting?", "What verification confirms recovery?"),
            requiredParams = listOf("scope"),
            optionalParams = listOf("operation", "namespace", "dryRun", "approval", "window", "system", "target")
        ),
        def(
            StandardCapability.RUNBOOK, "operations", "draft", "Execute a guided operational runbook.", catalogModules("standard", "rest"),
            requiredParams = listOf("description"),
            optionalParams = listOf("service", "severity", "system", "target")
        ),
        def(
            StandardCapability.INCIDENT, "operations", "draft", "Handle an incident workflow.", catalogModules("standard", "notify", "rest"),
            optionalParams = listOf("description", "service", "severity", "system", "target")
        ),
        def(
            StandardCapability.SECRET_ROTATE, "security", "draft", "Rotate secrets or credentials.", catalogModules("standard", "rest", "kubernetes"), listOf("Which secret provider owns the credential?"),
            requiredParams = listOf("subject"),
            optionalParams = listOf("provider", "service", "system", "target")
        ),
        def(
            StandardCapability.POLICY_CHECK, "governance", "draft", "Evaluate policy before execution.", catalogModules("standard"),
            optionalParams = listOf("policy", "subject", "system", "target")
        ),
        def(
            StandardCapability.RUN_COMMAND, "technical", "stable", "Represent a low-level runtime request as unresolved semantic intent.", catalogModules("standard"), notes = listOf("Requires notes-driven materialization. Prefer semantic capabilities when possible."),
            requiredParams = listOf("description"),
            optionalParams = listOf("system", "target"),
            requiredSystems = listOf("standard")
        ),
        def(
            StandardCapability.CALL_API, "technical", "stable", "Call an API directly. Low-level escape hatch.", catalogModules("rest"), notes = listOf("Prefer semantic capabilities when possible."),
            requiredParams = listOf("path"),
            optionalParams = listOf("method", "body", "system", "target"),
            requiredSystems = listOf("rest"),
            loweringStrategy = "module-action"
        ),
        def(
            StandardCapability.CUSTOM, "extension", "draft", "Custom extension capability.", catalogModules("standard"), notes = listOf("Should be documented by a module or organization-specific catalog."),
            optionalParams = listOf("description", "system", "target")
        )
    )

    val byCapability: Map<StandardCapability, StandardCapabilityDefinition> = definitions.associateBy { it.capability }

    fun markdown(): String = buildString {
        appendLine("# Flow Standard Intent Catalog")
        appendLine()
        appendLine("This catalog defines the platform-neutral capability vocabulary used by Flow intents.")
        appendLine()
        definitions.groupBy { it.category }.toSortedMap().forEach { (category, defs) ->
            appendLine("## ${category.replaceFirstChar { it.uppercase() }}")
            appendLine()
            defs.forEach { d ->
                appendLine("### ${d.capability}")
                appendLine()
                appendLine("- Maturity: `${d.maturity}`")
                appendLine("- Description: ${d.description}")
                if (d.typicalModules.isNotEmpty()) appendLine("- Typical modules: ${d.typicalModules.joinToString()}")
                if (d.requiredDesignQuestions.isNotEmpty()) appendLine("- Design questions: ${d.requiredDesignQuestions.joinToString("; ")}")
                if (d.requiredParams.isNotEmpty()) appendLine("- Required params: ${d.requiredParams.joinToString()}")
                if (d.optionalParams.isNotEmpty()) appendLine("- Optional params: ${d.optionalParams.joinToString()}")
                if (d.bindingOnlyParams.isNotEmpty()) appendLine("- Require an explicit module action: ${d.bindingOnlyParams.joinToString()}")
                if (d.requiredSystems.isNotEmpty()) appendLine("- Required systems: ${d.requiredSystems.joinToString()}")
                appendLine("- Lowering strategy: `${d.loweringStrategy}`")
                if (d.targetImplications.isNotEmpty()) appendLine("- Target implications: ${d.targetImplications.joinToString("; ")}")
                if (d.notes.isNotEmpty()) appendLine("- Notes: ${d.notes.joinToString("; ")}")
                appendLine()
            }
        }
    }

    private fun catalogModules(vararg ids: String): List<String> = ids.toList()

    private fun def(
        capability: StandardCapability,
        category: String,
        maturity: String,
        description: String,
        typicalModules: List<String> = emptyList(),
        requiredDesignQuestions: List<String> = emptyList(),
        notes: List<String> = emptyList(),
        requiredParams: List<String> = emptyList(),
        optionalParams: List<String> = emptyList(),
        requiredSystems: List<String> = emptyList(),
        loweringStrategy: String = "semantic-action",
        targetImplications: List<String> = emptyList()
    ) = StandardCapabilityDefinition(
        capability = capability,
        category = category,
        maturity = maturity,
        description = description,
        typicalModules = typicalModules,
        requiredDesignQuestions = requiredDesignQuestions,
        requiredParams = requiredParams,
        optionalParams = optionalParams,
        requiredSystems = requiredSystems,
        loweringStrategy = loweringStrategy,
        targetImplications = targetImplications,
        notes = notes
    )
}