# v0.7.5 Purpose Coverage Ratio

Flow v0.7.5 adds a purpose coverage ratio gate. The goal is to keep the public standard tied to Flow's original purpose: portable automation intent, safety boundaries, normalization, execution-plan contracts and target portability.

This is not a registry consistency check. It does not compare one generated list with another generated list. It reads the public `StandardModel` and the public reference intent corpus, then asks whether the standard still covers real automation intent rather than growing governance paperwork.

## What the gate measures

The `v0.7.5.purpose-coverage-ratio` gate requires:

- the reference corpus to contain at least 20 scenarios,
- all required purpose capabilities to appear in reference scenarios,
- risk-sensitive capabilities to have at least one blocked scenario,
- automation-purpose checks to remain at least 50% of the release profile,
- governance checks to stay below or equal to 15% of the release profile,
- automation-purpose checks to remain evidence-backed by fixtures or external anchors,
- zero registry-consistency gate growth,
- no stable public artifact growth for this release.

## Purpose capabilities

The required capability set is intentionally close to the automation scenarios Flow claims to standardize:

- `APPROVE`
- `BACKUP`
- `BUILD`
- `BUILD_IMAGE`
- `CERTIFICATE_RENEW`
- `CHECKOUT`
- `CLEANUP`
- `DATABASE_MIGRATE`
- `DEPLOY`
- `KUBERNETES_MAINTENANCE`
- `NOTIFY`
- `ROLLBACK`
- `SECRET_ROTATE`
- `TEST`
- `VERIFY`

## Non-goals

v0.7.5 does not add a runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or Flow syntax. It adds a quality signal that protects the standard from drifting into self-referential governance growth.
