# External AI proposals

The product command `flow-core proposal` imports an externally authored proposal and runs it through `ReviewedAiProposalFrontend` and the shared compiler. The installed launcher retains its technical name `flow-core`; `flow` in the development proposal was illustrative.

```sh
flow-core proposal request.json --out review-001
flow-core proposal request.json --target github-actions --render --out review-002
```

Use a new or empty output directory for each revision. Existing nonempty output and symbolic destinations are rejected by the atomic publication policy. Without `--out`, the command presents the review and complete workflow plan, plus assessed target evidence and any rendered artifact, without persisting them. `--contracts <root>` replaces the entire contract resource set. The command uses packaged contracts when that option is absent and works outside a repository checkout.

## Version 1 input

[The envelope schema](../schemas/ai-proposal.schema.json) defines four required fields:

- `formatVersion`: integer `1`.
- `providerId`: nonblank caller-declared identity; this is not proof that the provider produced the document.
- `request`: `AiIntentRequest`, including the original nonblank `userText`, optional context and normalization mode.
- `response`: `AiIntentResponse`, including the complete proposed `normalizedIntent` and provider `report`.

The Intent uses the existing authored [Intent JSON vocabulary](../schemas/intent.schema.json), with ordinary JSON scalar/collection values and the established `ref:`, `secret:` and `expr:` prefixes. It is decoded by the existing strict Intent loader. Existing historical omissions and inline system configuration remain supported by that loader; the linked Intent schema describes the explicit current form. Internal serialized Kotlin value objects are not an alternate wire format.

The report retains the existing required `mode`, `classification` and five-field `confidence` shape. Other fields retain their existing defaults. Confidence is provider-declared, advisory and uncalibrated. It never grants compilation or rendering authority. No model call is performed by this command.

Start with the complete [accepted example](../examples/proposals/accepted.json). The [rejected example](../examples/proposals/rejected.json) changes the build dependency to a nonexistent step while retaining the provider's otherwise unchanged report. The compiler must reject it regardless of provider confidence or empty questions. These are manually authored integration fixtures.

The [existing-image example](../examples/proposals/existing-image-blocked.json) retains the acceptance request and records an unresolved representability decision. Its proposed workflow is deliberately incomplete. It must remain blocked or require review; it is not evidence of successful deployment authoring or model quality.

Input is one UTF-8 JSON document. Duplicate keys, unknown envelope/request/report fields, unsupported versions/enums, scalar coercion, malformed Intent values, trailing documents and input-budget violations are rejected. Budgets are the shared product limits: 8 MiB source, nesting depth 64, 200,000 tokens and 10,000 entries per collection.

## Review and outcomes

[The result schema](../schemas/proposal-review.schema.json) defines `proposal-review.json`. Its `semanticAcceptance` is independent of `requestFidelity`, which remains `unassessed` when no independent requirements are supplied. A successful compilation does not prove that the natural-language request was preserved.

`inputSource` identifies and hashes the exact supplied bytes. `compilationSource` identifies the deterministic typed request/response snapshot captured by the existing frontend and bound to compilation authorization. Both derive their source identity from the actual input path. Whitespace can change the input digest without changing the interpreted source digest. Provider/request changes affect interpreted provenance even when the canonical graph stays equal. `graphDigest` exists only after accepted compilation.

Diagnostics retain compiler codes and severity and use catalog descriptions rather than echoing authored values or parser exception messages. Input errors include a safe contract field path when available; compiler errors identify the Intent subtree because the current compiler diagnostics do not carry typed JSON locations. Unknown map-key names are redacted. `reviewItems` refer by index to questions, risks and missing decisions in the original provider report; the host resolves those references against its captured input. The command does not copy request text or provider question/risk text into error diagnostics. Generated plans and explicitly requested target artifacts contain authored operational values and must be stored accordingly.

| Exit code | Meaning |
| --- | --- |
| 0 | Planning completed, or requested target assessment/rendering completed under the existing target policy |
| 2 | Invalid input, conflicting/unknown target, missing target for rendering or invalid invocation/publication destination |
| 3 | Required provider question, missing decision, unmitigated high risk, or target evidence requiring review under strict/render mode |
| 4 | Compiler rejection or blocking target materialization |
| 70 | Unexpected internal failure |

Malformed input with a valid invocation/output destination and compiler rejection publish the review even though the process exits nonzero. Invocation, contract-resource, limit/presentation or publication failures use the existing bounded CLI failure result and do not publish a new batch. `artifacts` lists the command's planned output names; the atomic publisher also writes `artifact-integrity-report.json` with actual-byte and schema receipts. Treat publication as successful only when the process outcome and publication receipt agree.

Accepted proposals expose the complete `workflow-execution-plan-set.json`, including all workflows and trigger routes. Single-workflow proposals additionally expose the existing execution and canonical plan views. Required questions, missing decisions and unmitigated high risks retain planning evidence but withhold target materialization. Compiler rejection takes precedence over provider review metadata.

A target may be explicitly supplied by the CLI or by `request.context.target`; if both are supplied, they must agree. The request target is recorded with intent-declaration provenance. Rendering requires an explicit target and `--render`. Materialization uses existing `CliTargetEvidenceAuthority`, typed target outcomes and rendering receipts. Current adapters cannot materialize multiple workflows; their complete target-neutral plan remains available with a blocking target disposition. AI-A1 makes no new adapter-support claim.

A declared target profile does not imply an installed projection provider. Selecting
`local`, `argo-workflows` or `azure-devops` currently returns exit 4 at `MATERIALIZATION`,
with `targetDisposition: blocked` and `PROPOSAL_TARGET_PROJECTION_UNAVAILABLE`.
The accepted canonical plans and any independent fidelity report remain available
and are published atomically with the proposal review. No target manifest or executable
artifact is fabricated. This applies with and without `--render`; earlier compiler,
fidelity and required-review decisions retain precedence. An unknown or conflicting
target remains an input error with exit 2.

## Next boundary

[Independent request-fidelity review](REQUEST_FIDELITY_REVIEW.md) is available with `--requirements reviewed-requirements.json`. This selects proposal feedback version 2 and adds a separate bounded fidelity report; omitting the option retains version 1. [The bounded external host](BOUNDED_AUTHORING_HOST.md) supplies AI-A3 orchestration and correction; live evaluation remains a separate acceptance gate. This command supplies their deterministic input/result boundary; it does not perform model orchestration, execute workflows, attest model identity or establish calibrated confidence.
