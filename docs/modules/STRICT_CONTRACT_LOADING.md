# Strict YAML and JSON contract loading (AR-04E)

All public YAML methods (including the historical `FlowYaml.read` signature) use
one strict mapper. There is no benevolent parsing mode. JSON evidence readers use
`FlowJson` and the same frontend-owned `ContractReadPolicy`. The semantic kernel
and compiler do not gain Jackson dependencies.

## Accepted input

Each call accepts exactly one non-null document. Typed binding rejects unknown
properties, duplicate object keys (including nested keys), malformed scalar types,
fractional integers, numeric enum values and null primitive values. Omitted fields
retain their declared model defaults. A map/tree is a structural representation:
it preserves every key, and the existing contract owner validates its vocabulary.
Dynamic names in an explicitly declared map remain legal; structural parsing must
not confuse them with unknown fixed fields.

YAML flow/block collections, comments, quoted strings, Unicode and literal
`<<: { ... }` claim templates remain supported. Alias references are rejected
explicitly, including scalar aliases. Previously Jackson could expose the alias
name as a scalar without preserving the referenced value. Quote literal strings
such as `'*name'`; write repeated values explicitly. An unused anchor has no
semantic effect and is allowed. The adapter trigger loader remains the owner of
its bounded literal-map template semantics.

## Parser budgets

| Property | Limit |
| --- | ---: |
| UTF-8 document bytes | 8,388,608 |
| Nested object/array depth | 64 |
| Streaming tokens (including keys and container boundaries) | 200,000 |
| String length (UTF-16 code units) | 1,048,576 |
| Field-name length (UTF-16 code units) | 1,024 |
| Numeric token length | 128 |
| YAML alias references | 0 |

A streaming pass checks the entire document before object binding. It includes
unknown subtrees and trailing content. Limits are shared across typed and map/tree
entry points; Jackson/SnakeYAML also receive defensive parser constraints.
File readers capture at most the byte limit plus one byte and decode UTF-8
strictly. Error wrappers retain the source name and the underlying parser detail.
No process-global Jackson default is changed.

These are contract parser budgets. General Flow-source capture, generated-output
budgets, CLI argument parsing and atomic artifact publication remain AR-05 work.

## Ownership and migration

| Entry point | Fixed-field owner |
| --- | --- |
| Intent YAML/JSON and adapter-facing Intent facade | Existing Intent normalizer |
| Module descriptors and ModuleYamlLoader facade | CanonicalModuleLoader |
| Notes manifest and packages | CanonicalNotesPackageLoader |
| Target registries | TargetRegistryDocument and existing registry validation |
| Adapter binding, topology, control, continuity, rendering, trigger evidence | Existing exact-key loader for each contract |
| Typed external/real-world/operational corpus documents | Existing Kotlin contract models |
| Standard-bundle JSON evidence | Existing report models and StandardBundleVerifier |
| Schema documents and generic evidence maps | Their schema/contract owner after structural parsing |
| Internal roadmap metadata views | Existing lifecycle authorities; not a public extensible contract |

Use `FlowJson.read(file, Contract::class.java)` for JSON contracts and
`FlowYaml.readStrict(file, Contract::class.java)` for YAML. JSON tree readers
must still validate the expected schema. `Json.mapper` remains the shared output
serializer; production contract reads go through `FlowJson` so that full-stream
budgets cannot be skipped.

There is no package, standard, schema or target-support version promotion.
Malformed input now fails instead of being partially consumed or coerced.
The integrated AR-04 milestone acceptance remains AR-04F.
