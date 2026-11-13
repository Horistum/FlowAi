# Agent-proposed discovery interpretation

Discovery version 3 removes the caller-owned JSON-to-operation map. The caller
supplies the original goal, authorized roots, public compiler contracts and confirmed
independent constraints. The external agent chooses observations, extracts values,
proposes their meaning and use, and authors a complete Intent 3.0/3.1 plan.

This corrects the product focus of PR #270. Signed inventory remains optional
supporting infrastructure. Authentication alone does not make the agent understand
an unfamiliar environment or solve a complex request.

## Responsibility and acceptance

| Participant | Responsibility |
| --- | --- |
| Caller | Goal, allowed scope, provider/tool configuration and independent constraints |
| External agent | Task-relevant exploration, dependencies, proposed interpretation, complete plan and unresolved unknowns |
| Host | Bounded observations, exact fact extraction, unique delivered-record matching and immutable review evidence |
| Separate reviewer | Assess whether the interpretation and complete plan address the goal; accept or reject that exact candidate |
| Existing compiler | Check the canonical plan against unchanged independent constraints and additive observed-value obligations |

The agent cannot approve its interpretation or edit the original requirements. Review
is a separate caller action, bound to the run and packet digest. Review attribution
is caller-declared, not cryptographic identity. As with the existing host, the provider
executable is trusted operator infrastructure, not an OS-sandboxed adversary. Do not
give a remote model filesystem access to this caller channel.

Review is not a request to write extraction paths or assemble a workflow. The packet
contains the proposed meaning, rationale, exact observations and full plan for inspection.
The reviewer must check omissions, over-narrow selection predicates, conflicting evidence,
ownership and required failure behavior. Matching a string to a delivered JSON value
cannot establish its meaning, environmental completeness or permission to act.

## Configuration and protocol

Use the existing provider/compiler/budget configuration with:

```json
{
  "investigation": {
    "formatVersion": 3,
    "roots": [{"id": "environment", "path": "/absolute/allowed/inventory", "scope": "Selected development environment"}],
    "maxSteps": 12,
    "maxSourceBytes": 8192,
    "maxTotalSourceBytes": 32768,
    "maxRecords": 96,
    "maxEntries": 32
  }
}
```

There is no `bindingRequirements` field; supplying one is rejected. Independent
requirements must use version 2 or later and be confirmed before dispatch. Neither
their expected values nor reviewer identity is sent to the provider. Human goal and
constraints still need an independently reviewed compiler representation; this slice
does not solve complete natural-language requirement extraction.

The provider receives `authoring-investigation/3` and the response JSON schema. Existing
`investigate`, `observe`, `extractFacts`, findings and unknowns retain their meaning.
On `propose`, replace v2 `bindings` with:

```json
{
  "interpretation": {
    "summary": "Explain the proposed solution, including dependencies and recovery.",
    "bindings": [{
      "meaning": "The observed resource identity is the requested verification subject.",
      "rationale": "Explain why the cited record identifies the requested resource.",
      "requirement": {
        "id": "selected-resource",
        "sourceQuote": "An exact quote from the original request",
        "source": {
          "root": "environment",
          "recordPath": ["resources", "*"],
          "where": [{"path": ["name"], "equals": "requested-resource"}],
          "valuePath": ["id"]
        },
        "target": {"selector": {"capability": "VERIFY"}, "parameter": "resource"}
      },
      "factId": "fact:selected-resource"
    }]
  }
}
```

These selectors are proposed by the agent after observation. They are not accepted
as independent constraints until separate review. The current value-binding boundary
supports nonempty observed strings and JSON records; unfamiliar non-JSON content may
inform findings but cannot become a verified parameter through this mechanism.
At least one evidence-backed binding is required. Other parameters and relationships
remain subject to the independent requirements and review; the host does not infer
that every parameter is grounded merely because some bindings match.

## Review and continuation

1. Run `tools/author_proposal.py run` normally. A valid candidate stops with
   `awaiting-discovery-review` (CLI exit 3), before compilation or rendering.
2. Inspect the packet identified by the last attempt's `discoveryReviewPacket` in
   `state.json`. It contains the original request, complete response, interpretation,
   source evidence, binding assessment and explicit limits of the evidence.
3. Submit a separate review document with `formatVersion: 1`, `runId`,
   `packetSha256` (the last attempt's `discoveryReviewPacketSha256`), `reviewerId`,
   `decision` (`accept` or `reject`) and a nonempty `rationale`:

```sh
python3 tools/author_proposal.py review-discovery --run /absolute/run --review /absolute/review.json
```

Accept compiles only the captured candidate, adding observed-value checks to the
unchanged original requirements. It spends no provider call. Reject terminates without
compilation. A replacement plan requires a new session and review; approval never
survives automatic repair. User decision unknowns still use `resume` before candidate
review. Missing technical facts, conflicts and capability gaps stay blocked.

Sources and optional inventory provenance are revalidated before and after compilation.
A changed source ends the session and invalidates dependent facts. The approval is
durably consumed before compiler invocation, so an interrupted compilation cannot replay
it. Final binding receipts connect the reviewed packet, decision, requirements, observed
values and compiler graph. `accepted` means a reviewed, constraint-checked plan, never
executed infrastructure or demonstrated recovery.

## Compatibility and evidence

V1 pinned investigation and v2 caller-owned discovery remain unchanged. V3 sessions use
`bounded-authoring-host/5`; in-place migration of a captured run is unsupported. Optional
[managed inventory provenance](MANAGED_INVENTORY_PROVENANCE.md) works with either v2 or v3.

```sh
python3 -m unittest discover -s tools/tests -p 'test_discovery_review.py'
python3 tools/verify_discovery_review.py --cli flow-cli/build/install/flow-core/bin/flow-core
```

The installed proof replays explicitly manually authored transcripts for infrastructure
readiness and isolated database migration/recovery, each with two different source
schemas. It verifies the same boundary with no caller map, then rejects substituted
values, flattened failure handlers and an old plan replayed against a changed inspection-only
goal. Database recovery must use the backup-produced baseline. Transcript code knows
the source schema and candidate plan: this is integration evidence, not model reasoning.

Live-agent trials across unfamiliar schemas, task-dependent observation selection,
conditional reuse/create with ownership-aware cleanup, and independently assessed runtime
outcomes remain required product acceptance work. This change enables a model to propose
the missing interpretation; it does not establish that a live model performs it reliably.
No new executor, semantic graph, platform adapter or arbitrary shell fallback is added.
