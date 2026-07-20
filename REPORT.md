# Flow Core Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Completed roadmap item: `0.9.7.3 Mandatory Materialization Authority`
Next Core roadmap item: `0.9.7.4 Universal Dependency and Continuity Contract`
Bounded closure item: `0.9.7.10 Bounded Semantic Closure Gate`

## Active direction

Core, implementation and conformance planning remain separate authorities. Existing executable reference evidence stays active as falsification feedback and does not define Core meaning.

## v0.9.7.3 outcome

- One `MandatoryMaterializationAuthority` validates target-neutral execution-plan integrity and derives compatibility from the canonical target registry.
- Target generators consume an unforgeable authorization instead of caller-supplied compatibility evidence.
- Public production generation accepts only the execution plan, target identity and strictness request.
- Execution candidates fail before provider invocation when structural or capability evidence is invalid.
- Diagnostic evidence may preserve blocked target facts only when it remains structurally validated, registry-derived and explicitly non-executable.
- Semantic graph, materialization negotiation, projection plan and artifact authority alignment are validated before resolved evidence is returned.
- Production conformance, reference snapshot and CLI composition paths use the same authority.
- The optional projection helper and duplicate planner capability gate were removed.
- Negative tests prove invalid plans do not invoke target generators and malformed materialization evidence fails closed.
- Package, public standard and artifact contract versions remain unchanged.

## Roadmap order

1. `0.9.7.1 Governance Signal Integrity` — completed
2. `0.9.7.2 Canonical Module and Notes Authority` — completed
3. `0.9.7.3 Mandatory Materialization Authority` — completed
4. `0.9.7.4 Universal Dependency and Continuity Contract` — next
5. `0.9.7.5 Canonical Intent Meaning`
6. `0.9.7.6 Universal Effect and State Transition Model`
7. `0.9.7.7 Universal Control and Policy Requirements`
8. `0.9.7.8 Abstract Execution Topology Model`
9. `0.9.7.9 Intent Lowering and Diagnostic Honesty`
10. `0.9.7.10 Bounded Semantic Closure Gate`

## Validation

Flow CI #1643 passed the implementation head, including Flow Agent checks, clean compilation, full tests, full conformance and executable reference evidence. The final metadata head must pass the same unmodified workflow before review completion.

## Architecture boundary

Flow remains a notes-driven universal automation language and standardization model. Core does not provide task execution, an SDK lifecycle, a plugin framework or concrete implementation authority.
