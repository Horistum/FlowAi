# Authoring target readiness followup

This AI-A3 followup starts from merged PR #257 at
`29c5dc8eb6d5c2b277ab5eda74fb9766454bed75`. Its tree matches the previously verified
PR head `b0e37b8ae2f46bcc9b24adbda85bdc55e432a2ce`.
[Predecessor Flow CI](https://github.com/Horistum/FlowAi/actions/runs/42201792998)
and [Adapter Runtime Certification](https://github.com/Horistum/FlowAi/actions/runs/42201793029)
passed. The followup requires its own exact-head and merge-candidate validation;
the final PR validation record owns those receipts.

## Problem and change

A declared target profile previously reached projection lookup even when the
installed product had no implementation. The resulting generic exception discarded
proposal/fidelity feedback and accepted canonical plans. The proposal command now
checks the existing projection registry after semantic, fidelity and required-review
gates. Missing implementation support publishes the complete accepted evidence with
exit 4, `MATERIALIZATION`, `blocked`, and `PROPOSAL_TARGET_PROJECTION_UNAVAILABLE`.
It does not emit a target manifest, executable artifact or render authorization.

The host also previously attempted model repair for compiler input errors in the
immutable caller request, including conflicting or unknown targets. These now stop
as `caller-input-error`; corpus evaluation stops further dispatches. The initial
attempt remains reserved and captured. Response input errors remain repairable.
This is diagnostic handling, not a provider-free preflight or a zero-cost guarantee.

## Verification

- Focused proposal/fidelity JVM regression: 26 tests passed, including both report
  versions, exact input digest, schema/byte receipts, all profile-only targets,
  render/assessment modes and earlier rejection/review precedence.
- Python regression: 312 tests passed, including request-field ownership, retained
  attempts, candidate repair and evaluation termination.
- Installed-host proof now exercises all six target profiles, unchanged canonical
  meaning, unknown/conflicting targets, repair, clarification and three frozen
  corpus repetitions. Attempt counts are derived from captured states.
- Relocated install/ZIP proof adds the profile-only target case with retained
  independent fidelity evidence and atomic schema/byte receipts.
- An executable recovery conformance vector and inventoried check cover target
  availability. The vector belongs to Architecture Recovery; it does not extend
  the frozen historical Core closure inventory.
- Full JVM, conformance, physical isolation and installed-host checks are required
  on the final PR head and synthetic merge candidate before delivery.

## Open acceptance gates

The unchanged held-out certificate request satisfies its independent requirements.
Jenkins, GitHub Actions and Tekton projections require concrete integration payloads
or task binding and produce review-only evidence. Local, Argo Workflows and Azure
DevOps have declared profiles but no installed projection providers. Switching
targets alone does not produce the required executable artifact. The request does
not authorize inventing a certificate issuer/provider or changing corpus expectations.

No live model access or operating configuration is available in this environment.
Deterministic peers establish integration behavior only. AI-A3 remains open pending
configured provider/model/access/budget, at least three real corpus repetitions,
independent quality review and the faithful executable held-out render. AR-06,
AR-07, F-16/F-20 and the EF-09 pause retain their existing owners and gates.
