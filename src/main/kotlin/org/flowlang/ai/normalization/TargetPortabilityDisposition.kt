package org.flowlang.ai.normalization

/** Internal map implementation used by scenario normalization without changing the public JSON shape. */
internal class TargetPortabilityDisposition(requestedTarget: String = "not-specified") :
    LinkedHashMap<String, String>(TargetPortabilityEvidence.deferred(requestedTarget))
