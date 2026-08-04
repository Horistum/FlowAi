# C0.1 Bounded Domain Corpus

## Scope

C0.1 closes an evidence corpus for exactly three bounded domains: software delivery, data transformation and infrastructure state change. This is a completion boundary for C0.1 only. It is not an exhaustive taxonomy of automation and does not exclude later domains from later roadmap work.

Source workflows are behavioral evidence. They cannot add Core capabilities or redefine dependency, control, topology or materialization meaning.

## Positive representability

A domain is covered only when at least one accepted baseline produces a plan, emits no diagnostic and has `SUPPORTED` or `SUPPORTED_WITH_BINDING` outcome. Merely loading a negative example successfully is not positive domain evidence.

The positive baselines include:

- software delivery through existing supported delivery cases,
- data transformation through the pinned Airflow ETL case with canonical `DATA_TRANSFORM` tasks and named VALUE relations,
- infrastructure state change through the pinned Terraform apply case with canonical `PROVISION` and state verification relations.

## Mutation polarity

Mutation polarity is an observed transition, not a count of diagnostic fixtures. The baseline must classify as `REPRESENTABLE`; the mutation must classify as `REJECTED` with its exact expected diagnostics. A negative baseline followed by another negative outcome does not flip polarity and cannot satisfy the gate.

## Dynamic boundary honesty

Runtime-generated Buildkite structure and the output-driven matrix remain `UNSUPPORTED_DYNAMIC_CONSTRUCTION`. Their exact diagnostics and non-executable assessments are checked by an independent dynamic-boundary gate. They do not impersonate positive coverage or mutation polarity.

## Roadmap ownership

A0.7 remains terminal for the A0 adapter series and forbids fabricated A0.8 work. Completed adapter lifecycle code no longer names the conformance stream or recognizes C0 item syntax. Cross-stream focus belongs to `RoadmapStreamTransitionAuthority`.

The correction has two legal states:

1. `CORRECTION_REQUIRED`, where normal roadmap selection is blocked;
2. `A1_0_ACTIVE`, reached only after exact-head and synthetic merge-candidate correction evidence passes.

A1.0 then implements real GitHub Actions artifact and workspace continuity. C0.2 follows A1.0, when a second executable target exists to exercise topology assumptions against reality.

## Validation boundaries

The original implementation boundary is Flow CI #2641. The distinct PR completion boundary is Flow CI #2647 on exact head `f084a2705ba699b4b2f7b19921bd3923ba2f73fd` and synthetic merge candidate `fb67bef6844e9fcfb2c050bebf2edbae10efc863`. PR #104 merged as `c65b30d493e6348c2b73261c634a7bb35e6cce6a`.

C0.1.1 records the post-merge integrity correction separately; its evidence must not reuse either historical boundary.
