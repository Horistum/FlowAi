# Schema ID Policy

Flow JSON Schemas use `https://flowlang.dev` as the canonical schema id domain.

Schema ids should include a version segment when a schema is part of a versioned public contract. Existing unversioned ids are normalized to the `1.0` schema line unless a more specific historical version is already present.
