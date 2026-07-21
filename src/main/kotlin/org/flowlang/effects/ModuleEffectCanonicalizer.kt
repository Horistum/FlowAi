package org.flowlang.effects

import org.flowlang.modules.Effects

/**
 * Converts legacy descriptor buckets into the universal typed shape.
 *
 * Module resource names are implementation evidence, so this adapter preserves
 * their operation and resource identity but deliberately does not infer a
 * semantic domain from names such as git, Docker, Kubernetes or Argo CD.
 */
object ModuleEffectCanonicalizer {
    fun canonicalize(effects: Effects): List<SemanticEffect> = buildList {
        effects.reads.forEach { add(effect(EffectOperation.READ, it)) }
        effects.writes.forEach { add(effect(EffectOperation.UPSERT, it)) }
        effects.creates.forEach { add(effect(EffectOperation.CREATE, it)) }
        effects.updates.forEach { add(effect(EffectOperation.UPDATE, it)) }
        effects.deletes.forEach { add(effect(EffectOperation.DELETE, it)) }
        effects.executes.forEach { add(effect(EffectOperation.EXECUTE, it, external = false)) }
        effects.network.forEach { add(effect(EffectOperation.EMIT, it)) }
        effects.filesystem.forEach { add(effect(EffectOperation.UPSERT, it, external = false)) }
    }.distinct()

    private fun effect(operation: EffectOperation, resource: String, external: Boolean = true) = SemanticEffect(
        domain = EffectDomain.UNKNOWN,
        operation = operation,
        resource = resource,
        external = external
    )
}
