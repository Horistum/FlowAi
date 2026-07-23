package org.flowlang.standard

import org.flowlang.intent.StandardCapability

/**
 * Executable contract for standard capabilities.
 *
 * The catalog explains the vocabulary; contracts constrain AI and scenario-pack
 * output. Normalizers and lowerers must respect these contracts before any AST,
 * plan or target syntax is generated.
 */
data class StandardCapabilityContract(
    val capability: StandardCapability,
    val requiredParams: List<String> = emptyList(),
    val optionalParams: List<String> = emptyList(),
    val requiredSystems: List<String> = emptyList(),
    val loweringStrategy: String = "semantic-action",
    val targetFeatures: List<String> = emptyList(),
    val notes: List<String> = emptyList()
)

object StandardCapabilityContracts {
    private val overrides: Map<StandardCapability, StandardCapabilityContract> = mapOf(
        StandardCapability.CHECKOUT to contract(StandardCapability.CHECKOUT, optional = listOf("url", "branch", "system", "target"), systems = listOf("git"), strategy = "module-action"),
        StandardCapability.BUILD to contract(StandardCapability.BUILD, optional = listOf("tool", "system", "target"), systems = listOf("standard"), strategy = "semantic-action"),
        StandardCapability.TEST to contract(StandardCapability.TEST, optional = listOf("suite", "command", "tool", "system", "target"), systems = listOf("standard"), strategy = "semantic-action"),
        StandardCapability.PACKAGE to contract(StandardCapability.PACKAGE, optional = listOf("artifact", "tool", "system", "target"), systems = listOf("standard"), strategy = "semantic-action"),
        StandardCapability.RUN_COMMAND to contract(StandardCapability.RUN_COMMAND, optional = listOf("description", "system", "target"), systems = listOf("standard"), strategy = "semantic-action"),
        StandardCapability.BUILD_IMAGE to contract(StandardCapability.BUILD_IMAGE, optional = listOf("image", "path", "dockerfile", "push", "system", "target"), systems = listOf("docker"), strategy = "module-action"),
        StandardCapability.PUSH_IMAGE to contract(StandardCapability.PUSH_IMAGE, required = listOf("image"), optional = listOf("system", "target"), systems = listOf("docker"), strategy = "module-action"),
        StandardCapability.DEPLOY to contract(StandardCapability.DEPLOY, optional = listOf("system", "target", "subject", "environment", "artifact", "version", "strategy", "namespace", "image"), systems = listOf("standard"), strategy = "semantic-action"),
        StandardCapability.VERIFY to contract(StandardCapability.VERIFY, optional = listOf("system", "target", "subject", "resource", "selector", "environment", "criteria"), systems = listOf("standard"), strategy = "semantic-action"),
        StandardCapability.APPROVE to contract(StandardCapability.APPROVE, optional = listOf("message"), strategy = "approval-gate"),
        StandardCapability.ROLLBACK to contract(StandardCapability.ROLLBACK, optional = listOf("reason", "flow", "application", "system", "target"), strategy = "semantic-action"),
        StandardCapability.NOTIFY to contract(StandardCapability.NOTIFY, optional = listOf("subject", "body", "to", "channel", "system", "target"), systems = listOf("notify"), strategy = "module-action"),
        StandardCapability.CALL_API to contract(StandardCapability.CALL_API, required = listOf("path"), optional = listOf("method", "body", "system", "target"), systems = listOf("rest"), strategy = "module-action"),
        StandardCapability.BACKUP to contract(StandardCapability.BACKUP, required = listOf("subject"), optional = listOf("retention", "destination", "system", "target")),
        StandardCapability.RESTORE to contract(StandardCapability.RESTORE, required = listOf("subject"), optional = listOf("recoveryPoint", "system", "target")),
        StandardCapability.DATA_SYNC to contract(
        StandardCapability.DATA_SYNC,
        required = listOf("source", "destination"),
        optional = listOf("mode", "filters", "batches", "system", "target")
    ),
    StandardCapability.SYNC to contract(
        StandardCapability.SYNC,
        required = listOf("source", "destination"),
        optional = listOf("mode", "filters", "batches", "system", "target")
    ),
        StandardCapability.DATA_TRANSFORM to contract(StandardCapability.DATA_TRANSFORM, optional = listOf("mapping", "operation", "system", "target")),
        StandardCapability.TRANSFORM to contract(StandardCapability.TRANSFORM, optional = listOf("mapping", "operation", "system", "target")),
        StandardCapability.VALIDATE to contract(StandardCapability.VALIDATE, optional = listOf("operation", "schema", "target", "system")),
        StandardCapability.CLEANUP to contract(StandardCapability.CLEANUP, required = listOf("resource"), optional = listOf("safety", "retention", "system", "target")),
        StandardCapability.PROVISION to contract(StandardCapability.PROVISION, optional = listOf("tool", "mode", "stack", "resource", "system", "target")),
        StandardCapability.DEPROVISION to contract(StandardCapability.DEPROVISION, required = listOf("target"), optional = listOf("safety", "system")),
        StandardCapability.DATABASE_MIGRATE to contract(StandardCapability.DATABASE_MIGRATE, required = listOf("database"), optional = listOf("migration", "version", "backup", "rollbackPlan", "dryRun", "system", "target")),
        StandardCapability.CERTIFICATE_RENEW to contract(StandardCapability.CERTIFICATE_RENEW, required = listOf("certificate"), optional = listOf("provider", "secret", "service", "namespace", "window", "system", "target")),
        StandardCapability.KUBERNETES_MAINTENANCE to contract(StandardCapability.KUBERNETES_MAINTENANCE, required = listOf("scope"), optional = listOf("operation", "namespace", "dryRun", "approval", "system", "target")),
        StandardCapability.RUNBOOK to contract(StandardCapability.RUNBOOK, required = listOf("description"), optional = listOf("service", "severity", "system", "target")),
        StandardCapability.INCIDENT to contract(StandardCapability.INCIDENT, optional = listOf("description", "service", "severity", "system", "target")),
        StandardCapability.SECRET_ROTATE to contract(StandardCapability.SECRET_ROTATE, required = listOf("subject"), optional = listOf("provider", "service", "system", "target")),
        StandardCapability.POLICY_CHECK to contract(StandardCapability.POLICY_CHECK, optional = listOf("policy", "subject", "system", "target")),
        StandardCapability.CUSTOM to contract(StandardCapability.CUSTOM, optional = listOf("description", "system", "target"))
    )

    val all: Map<StandardCapability, StandardCapabilityContract> = StandardCapability.values().associateWith { capability ->
        val def = StandardIntentCatalog.byCapability[capability]
        overrides[capability] ?: StandardCapabilityContract(
            capability = capability,
            requiredParams = def?.requiredParams ?: emptyList(),
            optionalParams = def?.optionalParams ?: emptyList(),
            requiredSystems = def?.requiredSystems ?: emptyList(),
            loweringStrategy = def?.loweringStrategy ?: "semantic-action",
            targetFeatures = def?.targetImplications ?: emptyList(),
            notes = def?.notes ?: emptyList()
        )
    }

    fun requireContract(capability: StandardCapability): StandardCapabilityContract =
        all[capability] ?: error("Missing standard capability contract for $capability")

    private fun contract(
        capability: StandardCapability,
        required: List<String> = emptyList(),
        optional: List<String> = emptyList(),
        systems: List<String> = emptyList(),
        strategy: String = "semantic-action"
    ) = StandardCapabilityContract(
        capability = capability,
        requiredParams = required,
        optionalParams = optional,
        requiredSystems = systems,
        loweringStrategy = strategy,
        targetFeatures = StandardIntentCatalog.byCapability[capability]?.targetImplications ?: emptyList(),
        notes = StandardIntentCatalog.byCapability[capability]?.notes ?: emptyList()
    )
}
