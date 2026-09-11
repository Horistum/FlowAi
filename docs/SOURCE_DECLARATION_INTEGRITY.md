# Source declaration integrity

Horistum rejects ambiguous Flow Source declarations before canonical compilation.
A second declaration cannot silently replace the first, even when both values
are equal, the first value is `null`, or the first block is empty.

## Owning scopes

| Declaration | Occurrence scope | Example diagnostic path |
| --- | --- | --- |
| Source `version` | Document | `version` |
| Input `required` / `default` | Individual input | `flow.input[0].default` |
| System `type` / configuration key | Individual system | `flow.systems[0].config.url` |
| Action or approval parameter | Individual statement | `flow.steps[0].params.message` |
| Action `safety` | Individual action | `flow.steps[0].safety` |
| Transform `where` | Individual transform | `flow.steps[0].where` |
| Selected field | All `select` blocks of that transform | `flow.steps[0].select.name` |
| Aggregate field | Individual aggregate | `flow.steps[0].fields.total` |
| Match default / error case | Individual match | `flow.steps[0].defaultSteps` |
| Global `on error` | Flow | `flow.errorHandler` |
| Map-literal key | Individual map, using decoded key identity | `flow.steps[0].params.payload.key` |

The existing strict retry-policy duplicate rejection remains in place, including
its established `retry.max`, `retry.delay` and `retry.backoff` diagnostics.

For example, this source is rejected rather than weakening its first safety rule:

```flow
flow "example" {
    steps {
        service.change target {
            safety: requiresApproval
            safety: onlyIf true
        }
    }
}
```

`DeclarationOccurrences` records a declaration before parsing its replacement.
It does not infer presence from the resulting value, so empty/default-valued first
declarations are not a bypass. Occurrence sets belong to the parser's owning
scope, never a global registry shared across files or invocations.

## Source diagnostics

`DuplicateDeclarationException` extends `ParseException`. It exposes the stable
code `FLOW_DUPLICATE_DECLARATION`, the owning `path`, and `firstOccurrence` and
`secondOccurrence`. The inherited `line` and `column` identify the second
occurrence. Positions use the lexer's existing one-based line/column convention.
The diagnostic includes field identity and coordinates, not parameter values.

Paths preserve nested statement/list indices, including across additive blocks.
Quoted map keys use their decoded identity: `key`, `'key'` and `"key"` identify
the same map field. Punctuation and control characters are escaped in bracketed
paths, so a key `"a.b"` is distinct from nested fields `a.b`.

For interpolation, lexical provenance accompanies the token list. Duplicate
errors are mapped through each decoded string back to the original source.
String position maps use sparse column runs, not one retained location object per
character. This mapping applies **only to the diagnostic**. Existing valid AST expression
locations, serialized AST bytes and canonical input fingerprints are not
repositioned as a side effect of better diagnostics. Escaped interpolation is
still literal text and is not scanned as an executable expression. Standalone and
interpolated expression entry points consume their complete fragment: trailing
authored tokens cannot hide duplicate-looking input after an accepted prefix.

## Preserved constructs

Repeated `input`, `vars`, `systems` and `steps` blocks remain additive. Disjoint
`select` blocks remain additive. Ordered normal match cases and result-handler
rules remain lists, not singleton declarations. Separate maps or statements may
reuse the same key. Name uniqueness represented by lists remains the existing
semantic validator's responsibility.

This change does not modify schema types, descriptor lookup order, system identity,
semantic identity generation, general YAML loading, target support or public
artifact versions. Those concerns retain their separate roadmap ownership.

## CLI and source-API compatibility

Both `ParseException` and `LexException` now extend `IllegalArgumentException`.
Existing code catching their concrete types or `RuntimeException` still catches
them. Code distinguishing `IllegalArgumentException` now treats source syntax
errors as invalid input.

The CLI uses its existing `CLI_INVALID_INPUT` diagnostic and exit **2** for these
failures, including duplicates. It does not report them as unexpected internal
failures (exit 70), emit a partially accepted AST or planning artifacts, or continue
to target rendering. Unexpected implementation failures retain the separate
internal-error classification. No command, launcher, AST field or schema ID is added.

## Executable verification

Frontend tests cover all affected scopes, null/empty first declarations, comments,
separators, nested control paths, lexical provenance, escapes, nested interpolation,
repeated invocations and retained additive syntax. A recording module catalog
verifies source rejection before module lookup or planning.

Product CLI tests verify error classification without external registry fixtures.
The verification kit owns actual `flow` command integration tests requiring the
reference registry and verifies rejection without output artifacts.

Four live conformance checks independently exercise singleton rejection, decoded
map-key rejection, scope preservation and the real frontend boundary. Their IDs
are in the existing architecture-recovery conformance inventory. They do not infer
success from roadmap status or a source-name scan.
