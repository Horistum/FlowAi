# Scoped discovery and compiler-enforced evidence bindings

Baseline: merged PR #268 at `0977c8a778fbdce8226af6c8b76df9f7c7f8f8ae` on `main`.
Work package: [AI-A3-S3.1](../work-packages/evidence-driven-system-discovery.yaml).
Contract: [System discovery](../../docs/SYSTEM_DISCOVERY.md).

## Result and authority

A provider can list/read an operator-granted workspace and extract exact JSON values.
Caller-owned binding requirements select eligible records and declare which operation
parameters must equal those observations. The provider supplies references, not the
binding policy. Missing references, wrong sources, incompatible types and ambiguous
records block before compilation. Findings cannot substitute for source values.

The host preserves original requirement bytes and creates a separate additive compiler
input with existing literal-parameter checks. The existing canonical graph evaluator
checks all selected operations. A correct operation cannot conceal a second incorrect
one; no selected operation cannot satisfy a binding. A receipt connects the frozen
policy, ledger, observation, extraction path, response and derived requirements to the
compiler's assessed graph digest. No host-side operation evaluator or execution graph
is added. Caller checks and policy remain immutable across clarification.

Revalidation covers dispatch, response, pre-compilation and post-compilation. Changed
observations invalidate dependent facts/findings, reopen technical unknowns and terminate
the session. Restoring old bytes cannot revive it. Intermediate compiler artifacts from
a source-changed session are not an accepted result or execution permission.

## Evidence

Unit and session regressions cover omitted bindings, unrelated facts and paths, typed
predicates, ambiguous candidates, limits, scope, immutable references, clarification,
source changes during compilation and compiler receipts for another requirements input.
Pinned-source v1 and non-investigating sessions retain their contracts.

The installed proof uses a deterministic process interpreting the public binding contract,
then injects adversarial replies. Three structurally different input sets use resource
arrays/connection maps, keyed datasets/store arrays, and nested credential/policy documents.
Their plans exercise VERIFY, POLICY_CHECK and BACKUP. Twenty-four cases require correct
acceptance or rejection for zero observations, missing bindings, wrong facts, contradictory
parameters, additional contradictory operations, ambiguous records and stale sources.

No source filenames or result addresses are supplied in the request or caller contracts.
The expected parameter is resolved from delivered evidence, and failures must identify
the generated compiler obligation. These checks do not depend on a pre-known exact-address
oracle. Final PR metadata records exact-head and merge-candidate CI receipts.

## Remaining boundaries

The operator still declares JSON selection semantics and the parameter obligations to
check. The deterministic fixture is not proof of live-agent understanding of unfamiliar
formats. Only declared bindings are enforced, and only exact string parameters use the
existing literal-string check; no coercion or inference promotion is permitted. Compiler
control-flow review requirements remain authoritative.

Evidence establishes selected workspace bytes, not authenticated provider truth,
upstream freshness, discovery completeness, live connectivity or multi-file atomicity.
The configured provider executable is trusted operator infrastructure, not sandboxed by
the root grant. S3, AI-A3, AR-06/AR-07 and F-16/F-20 remain open; EF-09 stays paused.
Managed-inventory provenance and independently reviewed live discovery remain next.
