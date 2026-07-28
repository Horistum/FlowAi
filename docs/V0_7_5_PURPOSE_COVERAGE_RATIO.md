# v0.7.5 Purpose Coverage

Flow v0.7.5 introduced purpose coverage to keep the public standard tied to Flow's original purpose: portable automation intent, safety boundaries, normalization, execution-plan contracts and target portability.

The original implementation also used raw check-count ratios as release gates. Post-closure correction `0.9.7.10.1` proved that those ratios had zero headroom and could prevent legitimate governance checks from being modeled. Ratios are now retained as observations, while pass/fail is owned by explicit structural evidence.

This is not a registry-consistency gate. It does not compare one generated list with another generated list. It reads the public `StandardModel` and the reference intent corpus, then asks whether the standard still covers real automation intent rather than growing governance paperwork without purpose evidence.

## What the gate requires

The `v0.7.5.purpose-coverage-ratio` check requires:

- the reference corpus to contain at least 20 scenarios;
- every mandatory target-neutral purpose capability to appear in reference scenarios;
- each required risk capability to have at least one blocked scenario;
- the public model to retain behavior, safety, normalization, execution-plan and portability categories;
- every required purpose category to cite a real negative fixture or production/external evidence anchor;
- the public release profile to contain no registry-consistency bookkeeping gates;
- stable public artifact growth to remain explicitly governed.

The report still publishes automation and governance ratios for review and trend analysis. They are not used as denominator-sensitive pass/fail thresholds.

## Target-neutral purpose capabilities

The mandatory capability set represents portable automation meaning rather than one implementation domain:

- `APPROVE`
- `BACKUP`
- `BUILD`
- `BUILD_IMAGE`
- `CERTIFICATE_RENEW`
- `CHECKOUT`
- `CLEANUP`
- `DATABASE_MIGRATE`
- `DEPLOY`
- `NOTIFY`
- `ROLLBACK`
- `SECRET_ROTATE`
- `TEST`
- `VERIFY`

`KUBERNETES_MAINTENANCE` remains a supported scenario and safety-sensitive capability, but it is not mandatory evidence for every universal Flow standard implementation. A target-specific operational capability cannot define the minimum purpose of the target-neutral Core.

## Public and package governance

The public `0.8.0` release profile remains a published standard contract. Durable package-level governance checks are modeled separately and cannot enter the public release, candidate or export projections implicitly.

A single explicit package-level registry-consistency owner may validate derived model integrity. The public release profile continues to require zero registry-consistency bookkeeping gates.

## Non-goals

Purpose coverage does not add a runtime executor, SDK API, plugin lifecycle, target-specific public DSL, renderer expansion or new Flow syntax. It is a quality signal that protects the standard from both semantic narrowing and self-referential governance growth.
