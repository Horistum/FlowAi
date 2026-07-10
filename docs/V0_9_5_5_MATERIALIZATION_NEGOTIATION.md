# v0.9.5.5 Materialization Negotiation

## Purpose

v0.9.5.5 introduces a target-neutral materialization negotiation model over the Universal Semantic Action Graph.

The previous correction step defines what Flow means. This step records whether each semantic action can advance toward projection, needs an adapter boundary, is unsupported, is blocked by policy, or must be deferred.

The important correction is honesty. A semantic action is not treated as fulfilled simply because it exists in a graph or can be rendered as a placeholder.

## Model

The model adds:

- `MaterializationStatus`
- `MaterializationEvidenceKind`
- `MaterializationEvidence`
- `MaterializationDecision`
- `MaterializationNegotiation`
- `MaterializationNegotiationValidator`
- `StandardMaterializationNegotiations.baseline()`

## Statuses

Negotiation status is explicit per semantic node:

- `MATERIALIZABLE`: the semantic action can advance toward projection using declared notes evidence
- `ADAPTER_REQUIRED`: an adapter or runtime boundary is required before support can be claimed
- `UNSUPPORTED`: declared meaning exists, but support is not available
- `BLOCKED`: policy or review evidence blocks advancement
- `DEFERRED`: the action remains intentionally unresolved for a later correction step

## Validation rules

The validator enforces:

- valid negotiation id
- valid semantic graph
- exactly one materialization decision per semantic node
- no unknown decision nodes
- no duplicate decision nodes
- non-empty reason for every decision
- evidence for every decision
- declaration evidence for every `MATERIALIZABLE` decision
- adapter evidence for every `ADAPTER_REQUIRED` decision
- safety or review evidence for every `BLOCKED` decision
- runtime, target and projection requirement nodes cannot bypass their boundary by being marked materializable too early

## Correction result

This step creates the negotiation boundary needed before target projection can be made honest.

It does not decide how a target renders work. It decides whether semantic meaning has enough declared evidence to move forward, or whether the system must stop and report a required boundary.

## Next step

The next correction item is v0.9.5.6 No-Shell Target Projection.

That step can use the negotiation model to avoid reporting target output as supported unless materialization has been explicitly negotiated.

## Versioning boundary

- Current package version remains `0.9.4`.
- Active public standard version remains `0.7.6`.
- This is roadmap correction work inside the v0.9.5.x correction track.
- No artifact schema version is changed.
