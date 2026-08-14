# Schema ID Policy

Flow JSON Schemas use `https://flowlang.dev` as the canonical schema id domain.

Schema ids should include a version segment when a schema is part of a versioned public contract. Existing unversioned ids are normalized to the `1.0` schema line unless a more specific historical version is already present.

## Authority classification

A canonical schema id identifies a published interchange contract; it does not by itself make JSON Schema the production validity authority. `PublishedSchemaContracts` classifies every file under `schemas/` and names the production owner that remains authoritative for semantic validity.

`PRODUCTION_VALIDITY` is reserved for schemas with explicit bidirectional acceptance proof. Otherwise the schema is `SYNTACTIC_INTERCHANGE`, and conformance must not treat a schema-only pass as proof that the production loader or validator accepts the document.
