# SI-06 Silent Authored-Value Coercion Audit

## Purpose

SI-06 enforces one source-boundary rule: defaults are valid only when the author omitted a value. Once a value is present, Flow must either preserve the intended typed value or reject it explicitly. Failed conversion must never manufacture another valid meaning.

The audit is intentionally narrower than a parser rewrite. It examines production `.flow` parsing and canonical source-loading boundaries for evidence-confirmed present-value coercion and leaves schema/loader agreement to SI-07.

## Confirmed defects

### `parallel.failFast`

`FlowParser.parseParallel` previously accepted any identifier after `failFast` and assigned:

```kotlin
failFast = token.text == "true"
```

Therefore `parallel failFast banana { ... }` became the legitimate semantic value `false`. SI-06 replaces the equality fallback with a closed `true`/`false` decision and reports every other present value at its authored token location. Omission still defaults to `true`.

### Strict typed YAML still coerced scalars

`FlowYaml.readStrict` used a mapper that rejected duplicate keys, unknown properties, null primitives and numeric enum ordinals, but Jackson scalar coercion was still enabled. Against the repository's cached Jackson 2.17.2 runtime the pre-correction mapper demonstrably accepted examples such as:

- integer `1` as boolean `true`;
- integer `42` as text `"42"`;
- boolean `true` as text `"true"`;
- quoted text `"false"` as boolean `false`;
- floating-point `1.9` as integer `1`.

That contradicts the strict loader's own contract. SI-06 disables scalar coercion and explicitly rejects numeric/boolean input for textual targets, while preserving Kotlin/default-constructor behavior for genuinely omitted fields.

### Target registry used the lenient typed mapper

`TargetRegistryYamlLoader.load` was the only production caller of the general typed `FlowYaml.read` path. As a result, target-registry source could silently discard unknown properties and inherit scalar coercion before later semantic validation ran.

SI-06 moves this public contract loader to `FlowYaml.readStrict`. Valid target-registry documents retain the same model and version; malformed scalar representations and unknown authored properties now fail at deserialization rather than disappearing or becoming another value.

## Reviewed boundaries without a correction

| Boundary | Decision | Evidence |
|---|---|---|
| `FlowParser.parseRetry` | Keep | Already applies defaults only on omission; rejects wrong types, fractional/out-of-range `max`, duplicates and unknown keys. |
| `IntentYamlLoader` | Keep | Performs explicit map/list/scalar validation and path-aware boolean/enum rejection before canonical construction. |
| `CanonicalModuleLoader` + `ModuleYamlLoader.decodeText` | Keep | Internal decoder has permissive helpers, but every public load path runs strict canonical structural/type validation before decoding; no unvalidated public bypass was found. |
| `CanonicalNotesPackageLoader` | Keep | Rejects unknown fields and wrong map/list/text types explicitly. |
| `AdapterTriggerEvidenceLoader` | Keep | Uses exact-key, strict map/list/text and closed-enum checks. |
| `FlowParser.parseSafety` | Keep | A bare expression can be an authored conditional expression; no evidence establishes this branch as failed conversion or default substitution. |

The audit does not treat every default, nullable cast or normalization as a defect. A correction requires evidence that a present malformed value can cross the boundary as a different accepted meaning.

## Test boundary

`SilentAuthoredValueCoercionTests` exercises polarity rather than only happy paths:

- omitted, explicit `true` and explicit `false` `parallel.failFast` values remain distinct and valid;
- multiple malformed identifiers plus number/string token classes fail at the authored token;
- strict typed YAML preserves omission defaults but rejects scalar cross-type coercion;
- target-registry boolean/text fields reject wrong scalar types;
- unknown target-registry fields are not discarded;
- the shipped `targets/builtin-targets.yaml` remains accepted by the strict loader.

## Version decision

No public version axis changes in SI-06:

- public standard: `0.8.0`;
- Intent: `2.0`;
- AST: `2.2`;
- ExecutionPlan: `2.3`;
- ExecutionPlan lowering evidence: `2.1`;
- TargetManifest: `3.0`;
- TargetRegistry: `3.1`.

The serialized shapes and intended valid vocabularies are unchanged. SI-06 removes implementation-level acceptance of malformed representations that only survived because of parser equality fallback, Jackson coercion or lenient unknown-property handling.

`schemas/target-registry.schema.json` still has known acceptance gaps, notably the under-specified `expressionProfiles` item shape. Closing schema-versus-loader validity differences is intentionally not smuggled into this PR; that is the explicit SI-07 responsibility.

## Validation honesty

Before editing, the offline sandbox copies of the three modified production files were Git-blob-hash identical to `main`. The modified `FlowYaml.kt` compiled independently against the cached Jackson 2.17.2 dependencies, and an executable Kotlin harness proved strict scalar rejection while preserving omission defaults.

The supplied offline cache does not contain the Kotlin/JUnit test dependencies required by Gradle test compilation. A full Gradle production compile also exceeded the sandbox execution window after reaching `:compileKotlin`. Those outcomes are recorded as environment limitations, not converted into fictional PASS evidence. The implementation remains active until independent exact-head and synthetic merge-candidate Flow CI validation succeeds and the implementation is merged.
