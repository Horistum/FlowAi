# YAML parsing boundary

Flow uses one YAML implementation: Jackson `jackson-dataformat-yaml`, configured through `org.flowlang.serialization.FlowYaml`.

## Ownership

`FlowYaml` is the only production source that constructs a Jackson `YAMLFactory` or YAML `ObjectMapper`. YAML consumers depend on that boundary rather than configuring private parsers:

- Standard Intent YAML loading
- module descriptor loading
- target registry loading
- architecture baseline and governance catalogs
- conformance vector indexing

The former `MiniYaml` parser was removed. It duplicated scalar and collection semantics, supported only a private YAML subset and contradicted the repository's existing Jackson YAML dependency.

## Contract

The shared boundary supports ordinary YAML maps, lists, flow-style collections, comments, quoted strings, booleans, numbers and nulls. Parsing failures include the source name and preserve the Jackson cause for diagnostics.

Domain loaders remain responsible for domain validation. For example, module loading still checks `kind: FlowModule`, while target registry loading still validates registry kinds, target names and expression profiles.

## Version boundary

This consolidation changes implementation ownership, not the public Flow language or serialized artifact contracts. It does not require a package, public standard or artifact version bump.
