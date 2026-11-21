# Independent review of a planning portfolio

An experiment owner registers the scope and held-out cases before the first model turn. A provider audit custodian attests the actual exchanges. An independent reviewer assesses the selected plans against the original requests and observed sources. The review CLI compares both accounts with the existing evaluator's recovered worker and compiler receipts. It starts zero model turns.

## Register before dispatch

Prepare a private [worker portfolio](PLANNING_WORKER_EVALUATION.md) and a commissioned worker with its owner-approved operating budget. Keep requirements, policy, output and private evidence outside every discovery grant. The reviewer checks the meaning of the private tasks, their exports and any historical exposure to examples before designating held-out cases. The software rejects an identical request on opposite sides of the split. It cannot establish historical exposure from file paths.

Obtain public Ed25519 keys from the provider audit custodian and independent reviewer through trusted channels. Each role holds its own private key. Supply canonical base64 of each 44-byte DER SubjectPublicKeyInfo. For an existing PEM public key, use `openssl pkey -pubin -in public.pem -outform DER | openssl base64 -A`.

```json
{
  "formatVersion": 1,
  "reviewerId": "operations-review",
  "reviewerKey": "<reviewer public key>",
  "providerId": "model-audit",
  "providerKey": "<different audit custodian public key>",
  "evidenceKind": "live-provider",
  "requiredDomains": ["infrastructure", "data-recovery"],
  "heldOutCases": ["isolated-restore"]
}
```

The required domains must exactly cover the portfolio. Use `scripted` for a test peer. The registered evidence class, keys, model, settings, assessment code, exact cases, requirements, source snapshots and turn limits stay fixed. From the repository root, with the worker environment activated:

```sh
python tools/review_planning_portfolio.py register --out /private/evaluation \
  --portfolio /private/portfolio.json --policy /private/policy.json --max-turns 6
```

Retain the returned `registrationSha256` with the independent reviewer **before** starting the evaluator. Each new registration includes a fresh experiment ID. Repeating registration for the same experiment returns the same digest. A missing registration after the first reservation cannot be reconstructed as a preregistration. The example permits two cases with three repetitions each. Registration creates the evaluator's frozen plan and spends zero turns.

```sh
python tools/evaluate_planning_worker.py --out /private/evaluation \
  --portfolio /private/portfolio.json --max-turns 6
python tools/review_planning_portfolio.py request --out /private/evaluation \
  --registration-sha256 "$registration_sha" > /private/review-request.json
```

The request command recovers every planning and independent compiler receipt, rechecks the frozen inputs and refreshes the evaluator summary. An incomplete matrix yields an incomplete result. Inspect each original task, observed source, selected proposal, canonical plan and requirements review under the evaluator's existing `cases/` and `trials/` directories. The review request identifies those exact receipts and records the compiler disposition for every trial.

## Independently capture provider records

The audit custodian obtains exchanges from the commissioned provider or a separately controlled audit collector with access to the delivered tool traffic. Export exactly one turn for each registered trial. Use the effective model actually served. Preserve the order of checked drafts and the observed final selection. A provider lacking sufficient records cannot establish this evidence. Copying expected fields from the evaluator's request defeats independence.

| Turn field | Source in the actual exchange |
| --- | --- |
| `threadId`, `turnId`, `model` | Provider session, completed turn and effective model. Each repetition uses a fresh thread. |
| `taskSha256` | Digest of the delivered `flow_task` object restricted to `request`, `allowedEnvironment`, `availableTools` and `constraints`. |
| `drafts` | Ordered array of `{"responseSha256":"…","observationsSha256":"…"}` for each distinct successfully delivered `flow_check_plan` call that returned a candidate. Hash its complete `response` argument, retain the observation digest in its reply and include compiler-rejected drafts. |
| `observationsSha256` | Final observation ledger digest delivered by the broker. |
| `candidateId` | Final selected candidate, or JSON null for an empty selection. |
| `latencyMs`, `costMicrousd` | Measured nonnegative integers or JSON null when unavailable. A missing charge leaves the aggregate cost unknown. |

For hashes **of JSON values**, use the repository's `authoring_host.contracts.canonical` followed by SHA-256. This sorts keys and uses compact UTF-8 JSON plus one LF. For signatures and file digests use the **exact file bytes**. The provider document contains `formatVersion: 1`, the independently retained `registrationSha256`, registered `providerId` and `evidenceKind`, and the `turns` array. Turn order in that array is immaterial. Sign it on the custodian's side.

```sh
openssl pkeyutl -sign -rawin -inkey provider-private.pem \
  -in provider-records.json -out provider-records.sig
```

## Assess the selections

The independent reviewer records `formatVersion: 1`, the registered `reviewerId`, `requestSha256` of the canonical review request, `providerRecordsSha256` of the signed provider file bytes and a `trials` array. Each decision has the exact trial `id`, one `assessment` and a short nonempty `reason`. Review the original request for omissions from the formal requirements. Check how the candidate interpreted discovered state, handled ambiguity and respected prohibitions.

Use `faithful`, `appropriate-clarification`, `appropriate-unsupported`, `hard-constraint-violation`, `wrong-goal` or `unassessed`. A faithful result requires compiler acceptance and independently satisfied requirements. Crediting clarification requires a selected proposal with a required question withheld by the compiler for review. An unsupported classification must agree with the compiler's independent assessment. Sign the review with the reviewer's key.

```sh
openssl pkeyutl -sign -rawin -inkey reviewer-private.pem -in review.json -out review.sig
python tools/review_planning_portfolio.py assess --out /private/evaluation \
  --registration-sha256 "$registration_sha" --review /private/review.json \
  --review-signature /private/review.sig --provider-records /private/provider-records.json \
  --provider-signature /private/provider-records.sig
```

Acceptance applies to this registered portfolio. It requires a faithful plan in every declared domain, at least one faithful held-out selection, complete independent review, and no compiler-accepted wrong goal or hard-constraint violation. Reported blocked trials stay in the denominator. An explicitly unsuccessful, compiler-rejected trial can coexist with a faithful result elsewhere in its domain. Exit 0 from `assess` means the live portfolio passed. Exit 3 reports incomplete measurements, failed quality gates or scripted evidence. Exit 2 means invalid inputs or unverified evidence. Use the current command result and exit status when publishing a decision.

The legacy twelve-case corpus, a rendered artifact for a fully specified held-out request, and observed workflow execution each require their own evidence. The report keeps overall milestone completion false.
