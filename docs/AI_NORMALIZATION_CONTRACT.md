# AI Normalization Contract — "AI proposes, the standard decides"

Flow's normalization layer turns free / AI-authored text into a Standard Intent Model
(`IntentDocument`). Track 2 makes that boundary safe to back with a probabilistic model.

## The seam

```
free text ──▶  AiIntentProvider.normalize(AiIntentRequest) ──▶ AiIntentResponse
                       │                                              │
            deterministic default:                          normalizedIntent : IntentDocument
            ScenarioPackIntentNormalizer                    report           : NormalizationReport (advisory)
            (reproducible, no network)
```

`AiIntentProvider` is the provider-neutral seam. The shipped implementation,
`ScenarioPackIntentNormalizer`, is deterministic and offline: it is the reference normalizer
used by tests and conformance, and the fallback when no model is wired. A model-backed
provider is just another implementation of the same interface — Flow core never calls an LLM.

## The contract (the part that makes a model safe)

A provider's `report` (its self-assessed risks, open questions, and confidence) is **advisory**.
A probabilistic provider can be wrong or adversarial: it could emit an `IntentDocument` for a
destructive operation while reporting "no risk, nothing to clarify".

`IntentProposalReview` is therefore the trust boundary. Before a proposal is lowered, it
**re-derives the verdict from the `IntentDocument` itself** using the standard's own
`IntentCapabilityValidator` (which also runs the safety-policy checks), ignoring the report:

```kotlin
when (val decision = IntentProposalReview().review(response)) {
    is IntentProposalDecision.Accepted -> /* proceed to lowering */
    is IntentProposalDecision.Rejected -> /* surface decision.violations; do not lower */
}
```

This supersedes `AiIntentResponse.assertUsableForLowering()`, which only inspects the provider's
reported risks/questions — fine for the deterministic normalizer, unsafe for a model. The
regression test `aProposalThatUnderReportsRiskIsStillRejectedOnSafety` pins this down: a proposal
with a pristine report but an approval-required intent and no approval step passes
`assertUsableForLowering()` yet is **rejected** by `IntentProposalReview`.

## Capability-mandated safety

Some obligations come from the capability, not from whether the proposer remembered to declare
them. Before validating, `IntentProposalReview` injects any missing **mandated** safety policy for
irreversible capabilities, then lets the standard's `SafetyPolicyValidator` decide whether it is
satisfied. The satisfaction logic is not reimplemented - only the mandate is declared:

| Capability | Mandated requirement |
|------------|----------------------|
| `DATABASE_MIGRATE` | backup (a backup step or confirmed backup parameter) |
| `DEPROVISION`      | approval |

So a model that proposes a migration while staying silent about backup is rejected with
`SAFETY_REQUIRES_BACKUP`, even though it declared no policy at all. The mandate is deliberately
conservative: `DEPLOY` is **excluded**, because deploying to a non-production environment is
routine and approval there is a scenario decision, not a blanket capability mandate.

## Honest scope

What this guarantees: a provider cannot lower its own risk by under-reporting (any violation
present in the intent is caught), and for the irreversible capabilities above it cannot escape the
obligation by omitting the policy.

What remains: enforcement currently lives in the AI-proposal gate, not in the shared
`IntentCapabilityValidator`, so hand-authored intents keep their existing bar; promoting the
mandate into the general validator, and widening the mandated set beyond the two least-debatable
capabilities, are deliberate follow-ons rather than silent scope creep.

