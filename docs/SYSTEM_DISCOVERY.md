# Scoped discovery and evidence-bound proposal parameters (AI-A3-S3.1)

The external authoring host explores an operator-granted directory without receiving
an answer-file inventory. A provider chooses bounded listings and reads, extracts
exact JSON values and submits a proposal. Caller-owned binding requirements define
which observed records may supply particular operation parameters. The host resolves
those requirements and the existing compiler enforces the resulting values on its
canonical graph. A retained citation alone cannot authorize a different parameter.

This slice supplies observation access and enforcement of declared bindings. It does
not establish arbitrary system understanding, live-model generalization or a new
execution framework. Optional [S3.2 managed inventory provenance](MANAGED_INVENTORY_PROVENANCE.md)
authenticates collector/scope claims and collection age. Upstream freshness remains open.

## Configuration and migration

Keep the outer host configuration at version 1. Investigation version 2 requires
nonoverlapping roots and a nonempty `bindingRequirements` list. Original independent
request requirements must explicitly use version 2, 3 or 4; the host never upgrades
their version implicitly. Configurations without investigation and pinned-source
investigation version 1 retain their previous contracts.

The protocol is `authoring-investigation/2` and the host receipt is
`bounded-authoring-host/3`. The earlier unmerged draft of v2 without binding requirements
is intentionally rejected: restart those draft sessions with a complete contract.
Observer protocols and outcome versions are unchanged.

For a request containing `Verify the discovered service named payments`, the following
investigation member selects the service by name without knowing its endpoint:

```json
{
  "investigation": {
    "formatVersion": 2,
    "roots": [{
      "id": "environment",
      "path": "/absolute/nonsecret/environment-export",
      "scope": "Selected development environment inventory"
    }],
    "maxSteps": 12,
    "maxSourceBytes": 16384,
    "maxTotalSourceBytes": 131072,
    "maxRecords": 96,
    "maxEntries": 128,
    "bindingRequirements": [{
      "id": "service-endpoint",
      "sourceQuote": "Verify the discovered service",
      "source": {
        "root": "environment",
        "recordPath": ["services", "*"],
        "where": [{"path": ["metadata", "name"], "equals": "payments"}],
        "valuePath": ["status", "endpoint"]
      },
      "target": {
        "selector": {"capability": "VERIFY"},
        "parameter": "resource"
      }
    }]
  }
}
```

Merge this member into the [existing host configuration](BOUNDED_AUTHORING_HOST.md)
and use the same `tools/author_proposal.py run` and `resume` commands. Every discovery
turn consumes the existing call, token and cost reservations. No live provider is
enabled implicitly; the frozen evaluation corpus still rejects extra source exposure.

The operator owns this contract. The provider can propose fact references but cannot
add, remove or rewrite the obligations. The predicates and target selectors are public
to the provider; original independent requirements, reviewer identity and compiler
fidelity results remain private. Source filenames and resulting parameter values are
not part of the binding contract. JSON selectors are declarative input contracts, not
provider-specific adapters or proof that the model understands an unfamiliar format.

Granting a root authorizes disclosure of selected names and UTF-8 file content below
it. Prepare a nonsecret workspace and place retained session output outside all roots.
Private root paths stay in captured configuration; the provider sees root IDs, declared
scope and pinned directory identity. Root identity is rechecked throughout the session.

## Observation and extraction

An `investigate` response requests up to eight observations. Paths are root-relative;
`.` lists the root. The host returns complete bounded directory entries or exact file
text with selector, scope attribution, byte count, SHA-256, observation ID, timestamp
and attempt identity. Repeating an unchanged observation creates no new progress.

```json
{
  "kind": "investigate",
  "observe": [{"kind": "list", "root": "environment", "path": "."}],
  "extractFacts": [],
  "findings": [],
  "unknowns": []
}
```

`extractFacts` selects an exact JSON path from an already delivered read observation.
It cannot supply the value or cite a read requested in the same response. String path
components select object members and integer components select array indices.

```json
{
  "kind": "investigate",
  "observe": [],
  "extractFacts": [{
    "id": "endpoint",
    "observationId": "COPY_THE_DELIVERED_OBSERVATION_ID",
    "path": ["services", 0, "status", "endpoint"]
  }],
  "findings": [],
  "unknowns": []
}
```

The host retains `fact:endpoint` with its exact value, observation ID, path, attempt
and validity. JSON duplicate keys are rejected; facts are limited to 4 KiB. Boolean,
numeric, null and structured values retain their types. Fact IDs cannot be repointed.
Findings remain model interpretations, including findings citing extracted facts.

## Binding facts to the compiled plan

A `propose` envelope replaces `observe` with the complete `response` and includes
`extractFacts`, `findings`, `unknowns`, and this additional member:

```json
{
  "bindings": [{"requirementId": "service-endpoint", "factId": "fact:endpoint"}]
}
```

For each independent obligation the host:

1. Examines delivered strict-JSON reads from the declared root. `recordPath` traverses
   the document; `*` expands array elements or object values. `where` tests typed JSON
   equality relative to each record. Missing fields do not mean null or absence.
2. Requires exactly one matching record among those delivered observations. Two matches
   are ambiguous even if their values agree. This makes no uniqueness or completeness
   claim about unread files, unvisited roots or the upstream system.
3. Checks that the named current fact came from that observation and the exact
   `recordPath` result plus `valuePath`. Equal bytes in another field or another root
   do not satisfy the obligation. Missing, stale or invented facts block acceptance.
4. Adds an existing `literal-parameter` check to a separate compiler requirements file.
   The selector is operator-owned and may use capability, workflow and fixed parameter
   filters. It cannot filter on the same parameter it constrains. The compiler checks
   every selected operation, so adding one correct operation cannot conceal an incorrect
   second operation. No selected operation also fails the compiler check.
5. Verifies the compiler receipt names that exact derived requirements digest and the
   same canonical graph, and that every generated obligation was assessed. Acceptance
   requires every binding obligation to be satisfied.

Original caller requirement bytes remain unchanged. Every original check is copied
unchanged into the additive compiler input; bindings cannot replace or weaken them.
The original request and confirmation metadata also remain unchanged. The inherited
reviewer attribution concerns the caller requirements; it does not authenticate newly
observed values. Separate binding receipts explicitly attribute policy to the operator
and values to workspace observations. Clarification revalidates the same frozen policy
and sources before dispatch and creates a fresh additive compiler input.

Parameter binding currently supports exact JSON **strings** of at most 1,000 characters,
matching the existing compiler's literal-string check. Other fact types, transformations
and model inferences cannot be coerced into this authority. Dynamic references and
conditional control flow remain subject to the compiler's existing proof/review rules;
the host does not reinterpret the graph or override an unresolved compiler obligation.
Only the declared parameter obligations are covered, not every value in the plan.

Missing bindings, mismatched source records and ambiguous candidates terminate as
`discovery-binding-blocked` before compilation. A structurally valid proposal with a
contradictory parameter is rejected by compiler fidelity. Open technical unknowns and
caller decisions retain the existing blocked and clarification behavior.

## Retained evidence and changes

| Record | Authority and purpose |
| --- | --- |
| Observation | Exact selected bytes or complete directory entries; operator-declared scope |
| Extracted fact | Host-extracted JSON value with immutable source/path attribution |
| Finding | Model interpretation citing retained evidence |
| Binding assessment | Frozen policy, ledger and response digests; exact source/fact/path to compiler-check relationship |
| Derived requirements | Original checks plus host-resolved literal constraints; original bytes retained separately |
| Binding result | Assessed requirements digest and canonical graph digest, with each compiler obligation's state |
| Decision answer | Explicit caller response; never model approval or authenticated provider truth |

Before dispatch, after response, before compilation and after compilation, the host
reopens retained selectors and compares content. Capture also checks for mutation during
each read/list. A new immutable ledger revision invalidates changed observations and
transitive facts/findings, reopens dependent technical unknowns and terminates the session
as `source-changed`. Unaffected records and older snapshots remain inspectable.
Restoring old bytes cannot revive an invalidated session.

If sources change during compilation, intermediate compiler artifacts may exist, but
no accepted session or final binding result is published. Consumers must check terminal
session status and the binding result rather than treating an intermediate review as
execution permission. Validation is not an atomic multi-file snapshot, continuous
monitoring or protection against a malicious storage owner reverting changes between
checks. A JSON field claiming readiness is still only source content, not cloud truth.

## Bounds and verification

Limits include eight roots, 16 investigation steps, eight observations/action, 16 fact
extractions/action, 16 binding obligations, eight predicates/obligation, 128 expanded
candidates and 128 retained investigation records. Paths, byte totals, directory entries,
ledger size and provider reservations are bounded. Over-limit listings fail rather than
truncate. Descriptor-relative traversal rejects symlinks, special files, multiply linked
regular files and alternate root spellings. The host exposes no shell/network/write tool;
these controls are not an OS sandbox for the configured provider executable.

```sh
python3 -m unittest discover -s tools/tests -p 'test_authoring_discovery.py'
python3 -m unittest discover -s tools/tests -p 'test_discovery_bindings.py'
./gradlew :flow-cli:installDist
python3 tools/verify_system_discovery.py --cli flow-cli/build/install/flow-core/bin/flow-core
```

The installed proof uses a deterministic selector-interpreting provider, then injects
hostile replies. It reads randomly named nested files with no answer index. Infrastructure
uses resource arrays and connection maps with VERIFY/POLICY_CHECK; data uses keyed dataset
records and a store array with BACKUP; credential policy uses nested documents with
POLICY_CHECK. Endpoint values occur in neither the request nor either caller contract.
Twenty-four cases cover correct proposals, zero observations, omitted bindings, wrong
facts, contradictory parameters, an extra contradictory operation, ambiguous records and
source changes. The compiler must reject the wrong values through the generated discovery
checks themselves. The original exact-address oracle and label-only domain variants are
not used. The fixture does not prove live-model reasoning or prompt-injection resistance.

Version 3: [agent-proposed interpretation and separate review](REVIEWED_DISCOVERY.md)
removes the caller binding map. Live discovery acceptance across unfamiliar formats remains
open, using optional
[managed-inventory provenance and collection-age enforcement](MANAGED_INVENTORY_PROVENANCE.md).
S3, AI-A3, AR-06/AR-07 and F-16/F-20 remain open; EF-09 stays paused.
