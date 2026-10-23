# Data-operation payload containment (audit A-02)

## Admission contract

Flow Source parses transform filters and selections, aggregate fields and validate
rules, but the current execution-plan data-operation node has no representation
for them. Previously the planner discarded these fields, so the audit's four
different source pairs became identical accepted plans, graphs and digests.

The shared compiler now rejects these forms at `FLOW_VALIDATION` with
`DATA_OPERATION_PAYLOAD_UNSUPPORTED`:

| Source operation | Rejected payload |
| --- | --- |
| `transform` | Non-null `where`, including literal true/false/null; non-empty `select` |
| `aggregate` | Non-empty `fields` |
| `validate` | Any rule, including `required` and expression rules |

The diagnostic names the operation and unsupported fields. The current data
statement AST does not carry statement source locations; this correction does
not fabricate one. Parsing, source provenance, the rejected AST and other
validation errors remain inspectable. No accepted `CompilationUnit`, canonical
graph authorization or executable artifact is issued for the rejected source.

The direct `FlowPlanner.plan` boundary uses the same rule and throws
`UnsupportedDataOperationPayloadException`, exposing `code`, `operationName`
and `unsupportedFields`. Typed AST callers cannot bypass it using unknown rule
kinds or an overridden AST `type` label. Nested control flow, unreachable authored
branches and local/global failure handlers are subject to the same check.
Availability analysis still runs first and retains its separate deterministic
resource limits.

## Compatibility and migration

Payload-free `transform [1] -> out {}`, `aggregate [1] -> out {}` and
`validate [1] {}` keep their existing canonical nodes. An empty `select {}`
has the same AST payload as no selection. This is bounded compatibility, not a
claim that these forms implement useful data processing or run on a target.
Their source expressions, operation kinds, targets and existing effects still
participate in canonical graph projection and digest.

Do not delete a filter, mapping, aggregate field or validation rule merely to
pass this guard. There is no automatic semantics-preserving rewrite in this PR.
Keep affected workflows blocked until their requirements have a supported
representation. Original examples and immutable audit witnesses remain intact;
the separately documented projection fixtures test review artifact structure
with empty payloads and are not equivalent workflows.

Intent YAML and reviewed AI proposals have a different existing contract:
`TRANSFORM`/`DATA_TRANSFORM` use `mapping`, and `VALIDATE` uses `schema`.
These capability parameters lower through ordinary semantic action parameters,
not the Flow Source data-operation nodes. Tests prove that accepted parameter
mutations survive plans, serialized graphs and digests, with YAML/AI parity.
Source-only `where`, `select`, `fields` and `rules` parameters are already
rejected as `UNKNOWN_STEP_PARAM` at Intent validation or AI proposal review.
This is not equivalence between source data statements and semantic actions,
nor permission to wrap source rules in opaque action parameters.

No public schema, package version, adapter support label or signed scenario
portfolio changes. This is stricter admission of previously miscompiled source.

## Evidence and remaining work

Compiler-only tests exercise typed AST admission without frontend or adapter
dependencies. Integration tests cover source parsing, all control/failure regions,
retained rejected fields, empty-payload projection/serialization and both other
frontends. Three standalone conformance checks replay the audit mutations,
direct-planner rejection and accepted graph distinctions.

A-02 is contained; typed payload lowering remains unimplemented. Full support
needs a reviewed target-neutral value/effect contract, binding and dependency
semantics, canonical serialization/digest preservation, and separate adapter
preservation evidence. A-03 global initializer preservation is the next bounded
audit correction; A-05, A-06 and general availability-state compaction remain open.
