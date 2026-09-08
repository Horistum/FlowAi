# SI-07 Public Schema Acceptance Alignment Migration

## Boundary

SI-07 separates two claims that had been conflated: a JSON document matching a published interchange shape, and the production implementation accepting that document as semantically valid. A schema-only PASS is no longer treated as proof of the latter unless the schema has explicit bidirectional acceptance evidence.

Every published `schemas/*.schema.json` file is now registered in `PublishedSchemaContracts` with an authority classification and a named production owner. The current inventory is `SYNTACTIC_INTERCHANGE`; production validity remains with the loader, typed contract or report producer named by the index. `PRODUCTION_VALIDITY` is reserved for a future boundary that proves acceptance equivalence in both directions.

## TargetRegistry 3.1 to 3.2

TargetRegistry is the concrete contract migration owned by SI-07. Version `3.2` removes contradictions between the published schema and `TargetRegistryYamlLoader` without weakening the strict loader introduced by SI-06.

### Wire changes

- `expressionProfiles` has a concrete object schema instead of an unconstrained array.
- Duplicate authored expression features are rejected before conversion to the runtime set representation.
- Capability names are a closed authored vocabulary.
- Capability, feature and topology support values use the documented wire vocabulary exactly. Undocumented aliases are no longer normalized into valid values.
- Projection `mode` remains optional and defaults to `adapter_required` in the typed model.
- Projection payload `bindings` remains optional and defaults to an empty map.
- Inline target `topology` is optional at the registry document shape boundary because adapter-owned topology evidence may be supplied by the distribution; `loadDirectory` still requires one authoritative topology source before capability materialization.

The built-in registry advances from `version: "3.1"` to `version: "3.2"`. Consumers producing 3.1 documents must update the version and remove undocumented support aliases or duplicate expression features.

## Existing report schemas

Several report schemas omitted fields already emitted by their production data classes. SI-07 adds those existing fields to the interchange schemas for capability negotiation, compatibility, execution readiness, target selection and intent capability validation. This is schema repair to match the already-published producer shape; it does not create new report-model semantics or new artifact-version axes.

## Validator behavior

`JsonSchemaSmokeValidator` still intentionally implements only Flow's published JSON Schema subset. The difference is that the subset is now explicit and fail-closed: adding an unsupported assertion keyword makes conformance fail instead of silently ignoring the constraint. The validator covers the assertion keywords currently used by all published Flow schemas, including composition, conditionals, local references, object closure, property-name constraints and uniqueness.

## Unchanged axes

SI-07 does not change:

- implementation package `0.9.5`;
- public standard `0.8.0`;
- Intent `2.0`;
- AST `2.2`;
- ExecutionPlan `2.3`;
- ExecutionPlan lowering evidence `2.1`;
- TargetManifest `3.0`.

Historical Core closure evidence remains historical and continues to record the TargetRegistry version certified at that earlier boundary. Only live contract metadata advances to TargetRegistry `3.2`.
