# Independent request-fidelity review

`flow-core proposal` can evaluate a proposed Intent against a separate, reviewed requirements document. This is a bounded check of explicit requirements over compiler-authorized meaning. It does not measure comprehension of all natural-language intent or authenticate a reviewer.

```sh
flow-core proposal examples/proposals/faithful.json \
  --requirements examples/proposals/reviewed-requirements.json --out review-001
```

The two [complete examples](../examples/proposals) are packaged with the installed CLI contracts. Use a new output directory for each revision. Add an explicit `--target` and `--render` only when target materialization is wanted; a fidelity pass does not grant target support.

## Requirements input

The [version 1 schema](../schemas/request-requirements.schema.json) describes a separate JSON document. It repeats the complete typed original `request` (text, context and mode), names the expectation `origin`, records `confirmation` and a caller-declared `reviewerId`, and contains 1–256 requirements. The reader rejects unknown fields, duplicate keys, malformed/null values, scalar coercion, unsupported versions and input-budget violations using the existing bounded I/O policy.

Each requirement has a unique lowercase identifier, an exact nonblank `sourceQuote` from `request.userText`, and one typed `check`. Matching quotes demonstrate traceability, not that the check completely or correctly interprets that quote. The reviewer must inspect the mapping. The original request must equal the proposal's complete typed request; a changed answer, context or mode requires a freshly reviewed requirements document. The captured requirement bytes are immutable for the evaluation and identified by SHA-256.

`REVIEWER_AUTHORED` denotes independently written expectations. `MODEL_EXTRACTED` denotes an AI host's extracted list. Either can be `PROVISIONAL`; a pass requires `CONFIRMED` and a nonblank reviewer identity. These are caller declarations. The host must obtain and retain the user's actual confirmation and keep this file outside the candidate model's write authority. A local file is not a signed human approval, and the CLI cannot distinguish a person from an agent supplying it. The candidate envelope itself has no requirements/confirmation field and rejects attempts to embed one.

## Check semantics

Checks use the accepted canonical graph and existing capability/effect contracts, without scenario matching or target-specific interpretation.

| Kind | Bounded meaning |
| --- | --- |
| `capability-count` | Minimum and optional maximum occurrences; optional exact workflow name narrows selection |
| `forbid-capability` | No node anywhere in the graph has the named capability |
| `forbid-effect` | No declared effect matches domain/operation and optional exact resource; unknown effects require review |
| `require-effect` | Every selected node has the declared effect; an empty selection violates the requirement |
| `literal-parameter` | Every selected node has exactly the expected string literal in a canonical parameter; references and expressions require review even with a matching default |
| `before` | Every selected after-node has all matching before-nodes in its own workflow as transitive resolved ordering predecessors; empty or unrelated approval is insufficient |
| `clarification` | A visible unresolved question; always requires review |
| `unsupported` | An explicit unrepresented requirement; always blocks a fidelity pass |

Presence, required effects, literals and ordering are evaluated as satisfied only in flat workflows without structural controls or failure handlers. This avoids treating conditional or failure-path presence as guaranteed behavior. Capability/effect prohibitions inspect all graph nodes, including nested ones. Declared effects are not observations of a real external runtime. Checks do not prove successful execution, transactional behavior or live trust status.

Evaluation is bounded to 2,000 graph nodes, 10,000 edges and 256 combined ordering endpoints. Exceeding a bound yields review, never a pass. The compiler's independent validation and safety policy still apply. The evaluator rechecks graph authorization and the captured proposal snapshot before and after evaluation.

## Evidence and compatibility

Without `--requirements`, `proposal-review.json` retains [version 1](../schemas/proposal-review.schema.json) and `requestFidelity: unassessed`. Supplying the option selects [version 2](../schemas/proposal-review-v2.schema.json) and, after compilation is attempted, adds [request-fidelity-review.json](../schemas/request-fidelity-review.schema.json) atomically with the other artifacts. Interactive output includes the same fidelity evidence. Input/target-selection failures happen before fidelity evaluation and publish only the proposal review where publication is possible.

The fidelity report binds the requirements digest, compilation source and accepted graph digest. Each result points to its requirement by identifier and JSON location with a stable code. It does not copy user text, source quotes, question text or expected secret values into diagnostics. Plans still contain operational values.

| Fidelity state | Process behavior |
| --- | --- |
| `requirements-satisfied` | Only the confirmed listed requirements passed; continue to provider review and existing target gates |
| `violated` | Exit 4; retain planning evidence and withhold target artifacts |
| `review-required` | Exit 3; provisional expectations, ambiguity, dynamic values or uncheckable graph require review |
| `unsupported` | Exit 4; retain the unrepresented requirement and withhold target artifacts |
| `not-evaluated` | Compiler rejection; exit 4 with per-requirement non-evaluation and no graph/plan |
| `unassessed` | Requirements absent, or input/selection rejected before evaluation |

The standalone report uses uppercase enum names; `proposal-review.json` uses the wire strings above. Violations take precedence over unsupported requirements, which take precedence over required review. Thus adding a forbidden build to an already unsupported request still exposes the violation. Every report keeps `scope: confirmed-requirements-only` and `naturalLanguageCompleteness: unassessed`. A pass means nothing about unlisted constraints.

## Corpus and next step

The [12-case corpus](../conformance/authoring/README.md) has independent expectations, six development cases and six cases reserved from future model prompting/tuning. It covers deployment, backup/restore and certificate maintenance, including ambiguous and unsupported requests. Deterministic conformance validates inventory, exact expectation hashes, outcomes, structural diversity against scenario examples and controlled mutations.

The original production-deployment request requires failure-only notification. Its candidate intentionally contains an unconditional notification; the independently recorded `OPERATION_FAILURE_SCOPE` blocker prevents a pass. Existing Intent cannot express this operation-specific failure predicate. External atomicity and live trust verification are also explicitly unsupported. Resolving these requires separate semantic or host evidence work, not a convenient adapter workaround.

AI-A3 will add a configured external model host, actual confirmation handling and captured bounded repair attempts. No model calls or model-quality measurements are part of this implementation.
