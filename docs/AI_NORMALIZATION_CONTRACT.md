# AI Normalization Contract: "AI proposes, the standard decides"

Flow's normalization layer turns free or AI-authored text into the Standard Intent Model
(`IntentDocument`). A provider proposes meaning; the standard independently decides whether that
proposal may cross the compiler boundary.

## Provider-neutral seam

```text
free text
  │
  ▼
AiIntentProvider.normalize(AiIntentRequest)
  │
  ├── normalizedIntent : IntentDocument
  └── report           : NormalizationReport (advisory)
  │
  ▼
ReviewedAiProposalFrontend
  │ deterministic source envelope: provider + exact request + complete response
  ▼
FlowCompilationService
  │
  ├── independent proposal review
  ├── shared Intent validation and lowering
  ├── shared Flow validation and planning
  ├── CanonicalExecutionGraph construction and validation
  └── source- and graph-digest-bound authorization
  │
  ▼
CompilationUnit
```

`AiIntentProvider` is the provider-neutral seam. The shipped
`ScenarioPackIntentNormalizer` is deterministic and offline, so the full boundary remains
reproducible without network access or vendor-specific model behavior. A model-backed provider is
another implementation of the same interface; Flow core does not call a particular LLM service.

## Provider reports are advisory

A provider's confidence, risks, assumptions, open questions and explanation are retained as
frontend evidence, but they do not authorize lowering or materialization. A probabilistic or
adversarial provider could otherwise emit destructive intent while reporting that nothing is
risky.

`ReviewedAiProposalFrontend` captures a deterministic JSON source view containing:

- the provider identity;
- the exact typed `AiIntentRequest`;
- the complete `AiIntentResponse`, including the normalized intent and report.

The SHA-256 digest of those bytes becomes the proposal source digest bound by compilation
authorization. Property and map iteration order do not affect the encoded bytes. Provider
identity, prompts, confidence and explanations may change the source digest, but they are outside
`CanonicalExecutionGraph` identity and cannot change its semantic digest unless the normalized
intent meaning changes.

## Standard-owned review

Proposal review is owned by `FlowCompilationService`, not by a CLI command or provider. The service
uses `IntentProposalReview` to re-run the standard's `IntentCapabilityValidator` over the emitted
`IntentDocument`. It does not trust the provider's self-reported report fields.

A rejected proposal stops at `CompilationStage.PROPOSAL_REVIEW`. No AST lowering, Flow validation,
planning, canonical graph construction or materialization authorization occurs. The rejection
retains both the proposal and the independently derived review evidence so callers can explain the
failure without reconstructing it.

An accepted proposal then enters the same Intent validation, lowering, Flow validation, planning,
graph construction and authorization path used by strict Intent YAML. The service checks that the
proposal-review validation and shared Intent validation are identical. Drift between those two
standard-owned decisions fails closed.

`AiIntentResponse.assertUsableForLowering()` remains a provider-report quality check used by strict
normalization modes. It is not a security or compiler authorization boundary.

## Capability-mandated safety

Mandatory control requirements are standard semantics, not an AI-only dialect.
`IntentCapabilityValidator` delegates to `CanonicalControlRequirementAuthority`, which derives and
checks capability-owned requirements for every frontend. Examples include:

| Capability | Canonical requirement |
|------------|-----------------------|
| `DATABASE_MIGRATE` | backup or restore evidence |
| `DEPROVISION` | approval evidence |

A provider that omits a required control cannot escape it by leaving the normalization report or
intent policy silent. The same rule applies to reviewed AI proposals and hand-authored Intent YAML.
For example, a database migration without backup evidence is rejected with
`SAFETY_REQUIRES_BACKUP` before lowering.

## Authorization and materialization

An accepted reviewed proposal produces a `CompilationUnit` that retains:

- proposal provider, request and response;
- independently derived proposal-review evidence;
- shared Intent and Flow validation evidence;
- the canonical graph and semantic digest;
- `ExecutionPlan` and `CanonicalExecutionPlan` compatibility views derived from that graph;
- `CompilationAuthorization` bound to the exact proposal source digest and graph digest.

Product code may materialize a reviewed proposal only from that source-bound `CompilationUnit`.
Direct proposal-review-to-planner composition and compatibility-plan substitution are architecture
violations enforced by AR-01 conformance checks.

## Honest guarantees

This boundary guarantees that:

- a provider cannot authorize its own output by under-reporting risk;
- changing provenance or provider metadata cannot silently change canonical execution meaning;
- changing normalized semantic meaning changes the canonical graph digest;
- invalid proposals fail before lowering and planning;
- all accepted product frontends converge on one compiler and graph-authorization axis.

It does not claim that a provider inferred the user's intent correctly. Human review, clarification
policy and scenario quality remain necessary wherever the normalization report or operating policy
requires them.
