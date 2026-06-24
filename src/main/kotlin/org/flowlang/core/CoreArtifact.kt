package org.flowlang.core

/**
 * Marker interface for Flow core artifacts that must stay independent from
 * serialization libraries and vendor SDKs.
 *
 * The practical split in this single-module prototype is enforced by conformance:
 * packages org.flowlang.ast, org.flowlang.planner, org.flowlang.intent model
 * classes and org.flowlang.core must not import Jackson/YAML classes. Loaders,
 * CLI and conformance adapters may use Jackson.
 */
interface CoreArtifact {
    val standardVersion: String
}
