# Flow v0.3.11 - Conformance Manifest

v0.3.11 keeps Flow on the standardization path:

```text
Standard artifacts
  -> public artifact bundle
  -> conformance checks
  -> conformance manifest
```

This release does not add syntax, runtime execution, SDK hooks or target templates. It adds a public manifest for conformance status.

## Public artifact

`conformance-manifest.json` describes:

- Flow standard version,
- implementation name,
- pass/fail status,
- required checks,
- failed checks,
- area summaries,
- conformance vector files,
- public schemas,
- required public artifacts.

## CLI

```bash
./gradlew run --args="conformance --out generated/conformance"
```

The command still prints the human-readable conformance report and now also emits the manifest JSON. With `--out`, it writes:

```text
conformance-manifest.json
```

## Why this belongs in Flow

Flow should be implementable outside this repository. A conformance manifest lets another implementation state what it supports against a specific Flow standard version without relying on ad hoc README claims.
