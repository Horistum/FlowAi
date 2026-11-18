package org.flowlang.effects

import org.flowlang.intent.IntentValue
import org.flowlang.intent.StandardCapability
import org.flowlang.intent.asTextOrNull

/** One inventory-independent authority for standard intent effects. */
object CanonicalIntentEffectAuthority {
    fun effectsFor(
        capability: StandardCapability,
        params: Map<String, IntentValue> = emptyMap()
    ): List<SemanticEffect> = contractsForText(capability, params.mapValues { it.value.asTextOrNull() }).map { it.effect }

    fun effectsForRendered(
        capability: StandardCapability,
        params: Map<String, String> = emptyMap()
    ): List<SemanticEffect> = contractsForRendered(capability, params).map { it.effect }

    /** The same definitions own effects and operand roles; an empty binding is explicitly unsupported. */
    fun contractsForRendered(
        capability: StandardCapability,
        params: Map<String, String>
    ): List<BoundSemanticEffect> = contractsForText(
        capability, params.mapValues { (_, value) -> value.trim().removeSurrounding("\"") }
    )

    private fun contractsForText(
        capability: StandardCapability,
        params: Map<String, String?>
    ): List<BoundSemanticEffect> = when (capability) {
        StandardCapability.CHECKOUT -> listOf(read(EffectDomain.SOFTWARE_DELIVERY, "source.repository", capability))
        StandardCapability.BUILD -> listOf(execute(EffectDomain.SOFTWARE_DELIVERY, "software.build", capability))
        StandardCapability.TEST -> listOf(
            read(EffectDomain.SOFTWARE_DELIVERY, "software.source", capability),
            execute(EffectDomain.SOFTWARE_DELIVERY, "software.test", capability)
        )
        StandardCapability.PACKAGE -> listOf(create(EffectDomain.SOFTWARE_DELIVERY, "software.package", capability))
        StandardCapability.BUILD_IMAGE -> listOf(create(EffectDomain.SOFTWARE_DELIVERY, "software.image", capability))
        StandardCapability.PUSH_IMAGE -> listOf(upsert(EffectDomain.SOFTWARE_DELIVERY, "software.image.registry", capability))
        StandardCapability.DEPLOY -> listOf(upsert(EffectDomain.INFRASTRUCTURE_STATE, "deployment.state", capability))
        StandardCapability.VERIFY -> listOf(read(EffectDomain.INFRASTRUCTURE_STATE, "deployment.state", capability, "resource", "subject", "target"))
        StandardCapability.APPROVE -> emptyList()
        StandardCapability.ROLLBACK -> listOf(update(EffectDomain.INFRASTRUCTURE_STATE, "managed.state", capability))
        StandardCapability.NOTIFY -> listOf(emit(EffectDomain.COMMUNICATION, "notification", capability))
        StandardCapability.SYNC -> listOf(upsert(EffectDomain.INFRASTRUCTURE_STATE, "desired.state", capability, "destination"))
        StandardCapability.DATA_SYNC -> listOf(
            read(EffectDomain.DATA_TRANSFORMATION, "data.source", capability, "source"),
            upsert(EffectDomain.DATA_TRANSFORMATION, "data.destination", capability, "destination")
        )
        StandardCapability.DATA_TRANSFORM,
        StandardCapability.TRANSFORM -> listOf(
            read(EffectDomain.DATA_TRANSFORMATION, "data.input", capability),
            create(EffectDomain.DATA_TRANSFORMATION, "data.output", capability)
        )
        StandardCapability.VALIDATE -> listOf(read(EffectDomain.DATA_TRANSFORMATION, "data.input", capability))
        StandardCapability.BACKUP -> listOf(
            read(EffectDomain.STATE_RECOVERY, "protected.state", capability, "subject"),
            effect(
                domain = EffectDomain.STATE_RECOVERY,
                operation = EffectOperation.CREATE,
                resource = "recovery.point",
                capability = capability,
                recovery = backupRecovery(params)
            )
        )
        StandardCapability.RESTORE -> listOf(
            read(EffectDomain.STATE_RECOVERY, "recovery.point", capability),
            effect(
                domain = EffectDomain.STATE_RECOVERY,
                operation = EffectOperation.UPSERT,
                resource = "protected.state",
                capability = capability,
                recovery = restoreRecovery(params)
            )
        )
        StandardCapability.CLEANUP -> listOf(delete(EffectDomain.INFRASTRUCTURE_STATE, "managed.resource", capability, "resource"))
        StandardCapability.PROVISION -> listOf(upsert(EffectDomain.INFRASTRUCTURE_STATE, "infrastructure.resource", capability, "resource", "stack"))
        StandardCapability.DEPROVISION -> listOf(delete(EffectDomain.INFRASTRUCTURE_STATE, "infrastructure.resource", capability, "target"))
        StandardCapability.DATABASE_MIGRATE -> listOf(update(EffectDomain.DATA_TRANSFORMATION, "data.schema", capability, "database"))
        StandardCapability.CERTIFICATE_RENEW -> listOf(update(EffectDomain.INFRASTRUCTURE_STATE, "infrastructure.certificate", capability, "certificate"))
        StandardCapability.CLUSTER_MAINTENANCE -> listOf(update(EffectDomain.INFRASTRUCTURE_STATE, "infrastructure.cluster", capability, "scope"))
        StandardCapability.RUNBOOK -> listOf(execute(EffectDomain.INFRASTRUCTURE_STATE, "operations.runbook", capability))
        StandardCapability.INCIDENT -> listOf(create(EffectDomain.COMMUNICATION, "operations.incident", capability))
        StandardCapability.SECRET_ROTATE -> listOf(update(EffectDomain.INFRASTRUCTURE_STATE, "infrastructure.secret", capability, "subject"))
        StandardCapability.POLICY_CHECK -> listOf(read(EffectDomain.INFRASTRUCTURE_STATE, "governance.policy", capability))
        StandardCapability.RUN_COMMAND -> listOf(execute(EffectDomain.UNKNOWN, "runtime.command", capability))
        StandardCapability.CALL_API -> listOf(apiEffect(params["method"], capability))
        StandardCapability.CUSTOM -> listOf(
            BoundSemanticEffect(SemanticEffect(
                domain = EffectDomain.UNKNOWN,
                operation = EffectOperation.UNKNOWN,
                resource = "custom.resource",
                sourceCapability = capability.name
            ))
        )
    }

    private fun backupRecovery(params: Map<String, String?>): RecoverySemantics? {
        val subject = semanticText(params["subject"]) ?: return null
        return RecoverySemantics(
            kind = RecoveryEffectKind.RECOVERY_POINT_CAPTURE,
            source = RecoveryEndpoint(RecoveryEndpointKind.PROTECTED_STATE, subject),
            target = semanticText(params["destination"])?.let {
                RecoveryEndpoint(RecoveryEndpointKind.BACKUP_DESTINATION, it)
            },
            retention = semanticText(params["retention"])
        )
    }

    private fun restoreRecovery(params: Map<String, String?>): RecoverySemantics? {
        val subject = semanticText(params["subject"]) ?: return null
        return RecoverySemantics(
            kind = RecoveryEffectKind.STATE_RESTORE,
            source = semanticText(params["recoveryPoint"])?.let {
                RecoveryEndpoint(RecoveryEndpointKind.RECOVERY_POINT, it)
            },
            target = RecoveryEndpoint(RecoveryEndpointKind.PROTECTED_STATE, subject)
        )
    }

    private fun semanticText(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)

    private fun apiEffect(method: String?, capability: StandardCapability): BoundSemanticEffect = when (method?.trim()?.uppercase()) {
        "GET", "HEAD" -> read(EffectDomain.COMMUNICATION, "external.api.resource", capability)
        "POST" -> execute(EffectDomain.COMMUNICATION, "external.api.request", capability)
        "PUT" -> upsert(EffectDomain.COMMUNICATION, "external.api.resource", capability)
        "PATCH" -> update(EffectDomain.COMMUNICATION, "external.api.resource", capability)
        "DELETE" -> delete(EffectDomain.COMMUNICATION, "external.api.resource", capability)
        else -> execute(EffectDomain.COMMUNICATION, "external.api.request", capability)
    }

    private fun read(domain: EffectDomain, resource: String, capability: StandardCapability, vararg parameters: String) =
        effect(domain, EffectOperation.READ, resource, capability, parameters = parameters.toList())

    private fun create(domain: EffectDomain, resource: String, capability: StandardCapability, vararg parameters: String) =
        effect(domain, EffectOperation.CREATE, resource, capability, parameters = parameters.toList())

    private fun update(domain: EffectDomain, resource: String, capability: StandardCapability, vararg parameters: String) =
        effect(domain, EffectOperation.UPDATE, resource, capability, parameters = parameters.toList())

    private fun delete(domain: EffectDomain, resource: String, capability: StandardCapability, vararg parameters: String) =
        effect(domain, EffectOperation.DELETE, resource, capability, parameters = parameters.toList())

    private fun upsert(domain: EffectDomain, resource: String, capability: StandardCapability, vararg parameters: String) =
        effect(domain, EffectOperation.UPSERT, resource, capability, parameters = parameters.toList())

    private fun execute(domain: EffectDomain, resource: String, capability: StandardCapability, vararg parameters: String) =
        effect(domain, EffectOperation.EXECUTE, resource, capability, parameters = parameters.toList())

    private fun emit(domain: EffectDomain, resource: String, capability: StandardCapability, vararg parameters: String) =
        effect(domain, EffectOperation.EMIT, resource, capability, parameters = parameters.toList())

    private fun effect(
        domain: EffectDomain,
        operation: EffectOperation,
        resource: String,
        capability: StandardCapability,
        recovery: RecoverySemantics? = null,
        parameters: List<String> = emptyList()
    ) = BoundSemanticEffect(SemanticEffect(
        domain = domain,
        operation = operation,
        resource = resource,
        sourceCapability = capability.name,
        recovery = recovery
    ), parameters)
}
