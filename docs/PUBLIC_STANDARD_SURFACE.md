# Public Standard Surface

Introduced in v0.4.5.

The public standard surface is the set of artifacts, schemas and conformance
rules that external implementations may rely on. It is intentionally smaller
than the Kotlin implementation.

Stable public contracts include:

- intent schema
- execution plan schema
- target manifest schema
- conformance manifest
- standard contract index
- standard release profile
- public standard surface report

Changing the public surface requires:

- a schema compatibility review,
- a migration note when consumers need to change,
- a negative conformance case,
- release-profile coverage.

Implementation helpers are not part of the public surface unless they are
listed in `public-standard-surface.json`.

## Schema authority

Every published JSON Schema has an explicit entry in `PublishedSchemaContracts` naming its authority class and production owner. The current schemas are **syntactic interchange contracts**: they constrain the serialized public shape, but they are not advertised as complete substitutes for the production loader, typed contract, or semantic validator named by the ownership index.

A schema may be promoted to `PRODUCTION_VALIDITY` only when bidirectional evidence proves that schema acceptance and production acceptance agree for the owned boundary. Conformance therefore validates published outputs against schemas without turning the schema smoke validator into an accidental second semantic authority.
