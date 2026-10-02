# Typed CLI argument parsing

AR-05A gives the product launcher and the verification launcher one argument parser.
Option values are consumed before positional inputs are resolved. Parsing and command
syntax validation finish before file reads, report generation or output writes.

Both forms are supported, in any order relative to positional inputs:

```sh
flow-core intent --out ./result --target jenkins ./request.intent.yaml
flow-core intent ./request.intent.yaml --target=jenkins --out=./result
flow-core normalize --app shop build --explain and test
```

Quote values containing spaces. The first `=` separates a long option from its value;
subsequent `=` characters remain part of that value. Use `--out=--directory` for a value
starting with `--`. A standalone `--` ends option parsing, so a positional filename or
text starting with `-` can be passed literally:

```sh
flow-core intent --out ./result -- --request.intent.yaml
flow-core normalize -- --literal text
```

| Command | Positional input | Value options | Flags |
| --- | --- | --- | --- |
| `intent` | Zero or one source file; existing default retained | `--out`, `--target` | `--strict`, `--fail-on-unsupported`, `--render` |
| `normalize` | Text, or `--file`; never both | `--file`, `--out`, `--target`, `--app`, `--environment`, `--repo`, `--channel` | `--strict`, `--fail-on-unsupported`, `--render`, `--lower`, `--pipeline`, `--explain`, `--repair` |
| `flow` | Exactly one source file | None | None |
| `diagnostics` | None | `--out` | None |
| `standard-verify` | Exactly one bundle directory, or `--bundle` | `--bundle`, `--out` | None |
| `catalog`, `scenarios` | None | None | `--markdown` |
| `targets`, `modules` | None | None | None |
| `scenario` | Exactly one scenario ID | None | `--examples` |
| `reference-snapshot`* | Zero or one source, or `--intent`; existing default retained | `--intent`, `--out`, `--scenario-id`, `--targets` | None |
| `conformance`, `release-profile`, `standard-draft`, `standard-export`* | None | `--out` | None |

\* These commands belong to the verification launcher. They are not installed in the
standalone product distribution.

Migration rules:

- Unknown options, unsupported flags and surplus positional arguments now fail with
  `CLI_INVALID_INPUT` and exit code 2 instead of being ignored or becoming a source.
- A value option requires a non-blank value. Flags do not accept `=true` or `=false`.
- Repeating the same option is rejected, including mixed `--out x` / `--out=y` forms.
- `normalize` accepts one of `--strict`, `--explain` or `--repair`. The existing
  `--fail-on-unsupported` behavior remains independent of that mode selection.
- `normalize --file`, `standard-verify --bundle` and `reference-snapshot --intent`
  cannot be combined with a positional input. Choose exactly one input form.
- Normalization text after an interspersed option is retained. Previously only the
  leading text before the first option was consumed.
- Explicit custom commands supplied through `CliCommandCatalog` retain their own
  argument contract. The host passes their original argument list unchanged.

This slice does not change artifact formats, source defaults or resource lookup.
Classpath-owned contracts, relocatable packaging, centralized limits and atomic
artifact publication belong to the remaining AR-05 slices.
