# Evidence-driven authoring investigation

Scope reassessment: the maintainer found this ledger-only outcome insufficient for
system automation. It remains supporting infrastructure; the selected continuation
is [system automation direction](../../docs/SYSTEM_AUTOMATION_DIRECTION.md), with
resource-specific fidelity and a broader lifecycle portfolio. The measurements below
describe the earlier ledger revision and do not establish the new direction's success.

Baseline: merged PR #258, `d4f98d525a8ad4ae9a6fc051155e75dcb4752f1a`.
The maintainer explicitly authorized the investigation extension discussed after
reviewing REA's evidence/unknown pattern. This is an AI-A3 host followup, not
AI-A3 completion or a target-adapter support promotion.

## Change and ownership

The external host accepts an opt-in `authoring-investigation/1` result envelope.
The model selects pinned sources by ID and records evidence-linked findings and
unknowns; host-owned code reads selected regular UTF-8 files without following
symlinks. All calls use the existing durable token/cost reservations. Every source
has a pinned digest and operator-declared scope. Source changes stop the run.

Observations preserve exact bytes, findings remain model inferences, and technical
unknown resolutions require existing evidence references without claiming proof.
Findings are immutable; unknown revisions preserve their question, category,
blocking flag and earlier evidence. Blocking user decisions use explicit answers
and independently confirmed requirements. Resume cannot remove or change existing
nonclarification requirements. Other blocking unknowns stop before compilation.
Duplicate reads add no new observation and a no-progress turn stops.

Complete proposals cross the unchanged compiler/fidelity/materialization boundary.
The semantic kernel, standard syntax, target adapters and runtime responsibilities
are unchanged. The host has no shell/network/tool discovery surface. Existing
default host configurations retain their protocol and behavior. The opt-in scope
and migration are documented in `docs/AUTHORING_INVESTIGATION.md`.

Architecture review: this supports concrete automation authoring, keeps universal
semantics and target implementation separate, introduces no runtime executor, SDK
or plugin lifecycle, and creates no scenario dispatch in production investigation.
No standard conformance vector changes are needed: the new contract belongs to the
external Python host, not the frozen public Flow standard.

## Validation

- Baseline: all 312 Python tests passed before implementation.
- Extended Python suite: 329 tests passed, including 17 new source, ledger, budget,
  decision, drift, confidentiality and protocol boundary tests.
- Flow agent structure validation and diff whitespace checks passed.
- The JDK 25 clean build and all 2,430 JVM tests passed, with no skipped tests.
- The installed distribution passed all 298 conformance checks.
- `verify_authoring_investigation.py` passed six installed-compiler cases: matching,
  conflicting and prohibited-effect variants for deployment and backup inspection.
  One generic deterministic peer derives proposal parameters from delivered source
  observations. It receives no reviewer expectations. The existing installed-host
  verifier runs this proof on exact-head and merge-candidate CI revisions.
- Final-revision exact-head and merge-candidate CI results are recorded by the PR
  validation evidence. Local checks do not substitute for those revision-bound gates.

## Remaining limits

This is evidence collection over explicitly selected local snapshots, not automatic
repository discovery or live cluster/API access. Digest identity does not attest
Git provenance, truth or remote freshness. Cited model inferences may be wrong;
compiler and independent requirements remain mandatory and do not certify complete
natural-language understanding. Invalidating a changed source stops the entire run,
rather than selectively recomputing dependent claims. Configured-credential detection
is not general secret detection, and the provider executable remains trusted operator
infrastructure rather than an OS sandbox.

The deterministic peers prove integration only. Live-model investigation quality
requires configured access and a separate reviewed evaluation. The frozen AI-A3
corpus rejects investigation source exposure until an independent exposure policy
exists. Its original expectations and held-out gate are unchanged. AI-A3, AR-06,
AR-07, F-16/F-20 and the EF-09 pause keep their previous acceptance boundaries.
