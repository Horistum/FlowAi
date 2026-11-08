# Operation outcomes and resource boundaries (AI-A3-S2.2)

Horistum can assess independently declared postconditions of an accepted plan using
captured, authenticated observations. This is a post-run assessment over the existing
canonical graph. It neither executes work nor authorizes execution.

The same contract applies to infrastructure provisioning, data operations, credential
rotation and other canonical capabilities. It does not select an Azure API, implement
a provider adapter, poll a service or turn a successful process exit into a successful
system change. Existing Intent, AST, execution plan and authoring prompt versions
remain unchanged.

## Inputs and decisions

`outcomes <source> --source-kind intent|proposal --requirements requirements.json`
accepts the existing Intent and reviewed AI proposal compiler frontends. Add `--observations observations.json
--trust trust.json` to assess captured observations. `--out <new-directory>` publishes
`operation-outcome-review.json` and the accepted target-neutral plan(s). Existing
atomic output publication rules apply. Target selection and rendering are unavailable.

| Decision | Exit | Meaning |
| --- | --- | --- |
| `SATISFIED` | 0 | Every declared outcome has matching authenticated evidence and the required facts. |
| `UNRESOLVED` | 3 | Evidence is missing, stale, ambiguous, unauthenticated or uncheckable. |
| `VIOLATED` | 4 | An addressed operation failed, a postcondition differs, or a declared resource boundary was crossed. |
| Invalid input | 2 | A contract, version, context or CLI argument is invalid. |

Source compilation rejection exits 4. A violated requirement takes precedence over
an unresolved requirement; both remain visible individually. An invalid supplied
observation makes the entire supplied bundle unresolved, rather than selecting only
convenient records. No supplied observations means unresolved, not success.

## Reviewer-owned requirements

`operation-outcome-requirements.schema.json` version 1 describes:

- `graphDigest`: the accepted canonical graph, including resolved parameters and edges.
- `runId`, `challenge`, `startedAt`: a caller-established run context. Generate a fresh
  unpredictable challenge of 32–128 URL-safe characters for each run.
- `reviewerId`: caller-declared attribution, not authenticated reviewer identity.
- `resources`: exact `{scope, type, key}` identities and a resource disposition.
- `operations`: semantic capability/workflow/literal-parameter selectors, a resource
  ID, the actual canonical resource `parameter` and optional nested map `path`, scalar
  `postconditions`, and an observation freshness bound `maxAgeSeconds` (default 300).

Scope, type and key are case-sensitive opaque strings. No provider path normalization,
short-name equivalence or name-based ownership inference occurs. In Intent 3.0 an
address can be a normal structured parameter value, for example:

```json
{"kind":"operation","id":"create","capability":"PROVISION",
 "params":{"resource":{"scope":"tenant-a/subscription-test",
 "type":"network","key":"temporary"}}}
```

Literal addresses must contain exactly those three literal string fields. A direct
reference instead requires a signed `BINDING` claim for the actual canonical VALUE
edge producer, binding name and projection path, or for an actual public input.
A control merge is bound at the actual merge output; the assessment does not infer
which branch ran. A one-part interpolation of a direct public text input or a canonically typed text
merge output is identity-preserving and
is supported. Other interpolation, transformed addresses and partially dynamic maps
remain unresolved. Selectors must identify one task; candidate-chosen step IDs are
not the requirement oracle.

| Disposition | Obligation |
| --- | --- |
| `SHARED` | The selected operation's effects must all be reads. |
| `MANAGED` | Requirements name `owner`; a modifying operation needs a fresh matching ownership claim no later than its result. |
| `CREATED_IN_RUN` | Requirements select a creator covered for the same exact resource; a fresh successful `CREATION` claim must explicitly say `created: true`. |

A successful PROVISION/UPSERT may update or adopt an existing object. It does not prove
creation. A removal of a resource created in this run needs creation evidence no later
than the removal result. Any destructive graph operation absent from the reviewed
operations keeps the overall assessment unresolved. These checks do not establish
complete resource coverage for multi-resource operations or authorize deletion.

## Observer protocol and trust

Trust is supplied separately by the caller using `operation-observation-trust.schema.json`.
Each grant names a key ID, a base64 X.509 Ed25519 public key, exact permitted scopes,
resource types and claim kinds. A candidate or observation cannot add its own trusted
key. Key distribution, revocation and the correctness of observers remain external
responsibilities.

Each bundle record contains `keyId`, `payload`, `signature` and `source`, the last three
base64-encoded. The payload is the observer's original strict UTF-8 JSON; the source
is the captured response/log bytes. Sign the following byte sequence with Ed25519:

1. UTF-8 `Horistum operation observation`, NUL, `v1`, NUL.
2. Four-byte unsigned big-endian length of the ASCII key ID.
3. The key ID bytes followed by the exact original payload bytes.

The public JVM helper is `OperationObservationAuthentication.signingBytes`.
Payloads and source captures are each limited to 65,536 bytes. The payload includes
`contractSha256` of the exact requirements file bytes, graph digest, run ID, challenge,
resource identity, actual canonical `nodeId`, observation time, source SHA-256 and size.
Reformatting requirements changes their identity and requires new observations.

| Kind | Meaning |
| --- | --- |
| `RESULT` | Success/failure/unknown for the operation on the addressed resource. |
| `STATE` | Observed scalar facts after the operation result. |
| `CREATION` | Whether the selected creator actually created this resource in this run. |
| `OWNERSHIP` | Observed owner at or before the modifying operation result. |
| `BINDING` | Identity carried by a specific real graph output or public input. |

An observer signs its assertions; the evaluator does not derive facts by parsing the
captured source. The source digest prevents replacement after signing, while semantic
truth still depends on the authorized observer. Do not place secret values in captures
or scalar facts. The report contains identifiers and digests, not captured raw bytes.

Every record must be within the run interval and at or before the evaluation clock.
Records used for an outcome must also satisfy its freshness bound. `STATE` must be no
earlier than `RESULT`; resource bindings must be no later than `RESULT`. Exact duplicate
IDs or duplicate claim slots are rejected, including contradictory success/failure.
There is no implicit newest-record policy or retry-attempt selection in version 1.

The default evaluation clock is now. `--evaluated-at <ISO-instant>` supports reproducible
historical assessment. A historical satisfied report only certifies freshness relative
to its recorded clock; it must not be reused as present-time permission. The evaluator
is stateless: hosts must manage one-use challenges and persist their run history.

## Bounded scope and next work

Requirements support 64 resources and 64 operations; bundles support 256 observations.
Selectors and graph traversal have explicit budgets and reject unsupported cases.
Expected facts are booleans, bounded strings or finite numbers; comparisons preserve
number precision and do not coerce strings to booleans. No null, object, expression or
user-provided predicate is executed. Schemas describe interchange shape; production
readers and the canonical graph remain the semantic authority.

A satisfied report covers declared operation postconditions. It does not prove the
whole natural-language request, actual control-path execution, retries, compensation,
atomicity, authorization or cloud support. `executionAuthorized` is always false;
`workflowExecutionCorrectness` remains `unassessed`. No observer transports, credentials,
model calls, runtime scheduler or second graph have been introduced.

The next slice should connect explicit operation failures to bounded recovery regions,
including separate attempt identity and evidence invalidation after compensation.
Live-model evaluation and held-out rendering remain independent open AI-A3 gates.

Ordinary Flow Source module calls currently lack the canonical semantic capability
identity carried by normalized Intent operations. The CLI rejects that source kind
explicitly. The library returns unresolved for such graphs; it never guesses a
capability from command text. Extending the existing source semantic authority is
a prerequisite for a useful Flow Source outcome entry point.

Resource operand mapping is reviewer-owned. This slice does not independently promote
an address in arbitrary task metadata into complete effect-target coverage.

An unobserved destructive operation in an untaken branch also keeps the assessment
unresolved: version 1 has no authenticated execution-path or skipped-operation claim.
Creation evidence is distinct from proof of current ownership after a later transfer.

Postconditions are checked at each operation's recorded observation time. A satisfied
report is not an atomic final-state snapshot across all operations; a later removal
can legitimately follow a successful creation.

## Try the installed proof

With JDK 25 and OpenSSL available:

```sh
./gradlew :flow-cli:installDist
python3 tools/verify_operation_outcomes.py --cli flow-cli/build/install/flow-core/bin/flow-core
```

The [fixture portfolio](../conformance/operation-outcomes/corpus.json) has fixed,
manually authored obligations. Its zero graph pins are placeholders: the verifier
fills only the digest of the accepted compilation, then creates synthetic observations
using an ephemeral test key. This is protocol and assessment evidence, not evidence
that infrastructure, data or credentials were changed in an external system.

Resource dispositions apply to the declared operation bindings. Unselected
non-destructive operations remain unassessed; the report does not certify global
immutability of every shared resource.

[Failure-region authoring](FAILURE_REGIONS.md) separately preserves planned retry and
recovery structure. Outcome observations version 1 do not certify reached regions,
individual attempts or the runtime correctness of that structure.
