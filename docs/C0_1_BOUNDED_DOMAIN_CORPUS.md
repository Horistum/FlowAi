# C0.1 Bounded Domain Corpus

## Purpose

C0.1 activates the independent conformance stream after terminal adapter item A0.7. It does not create A0.8 and does not extend Core or adapter meaning.

The bounded completion scope is exactly:

- software delivery
- data transformation
- infrastructure state change

## Evidence model

Every accepted case owns:

1. one closed domain identity,
2. immutable repository revision and source path,
3. explicit license evidence,
4. reconstructed canonical intent,
5. expected production-pipeline plan semantics,
6. exact diagnostics for unsupported behavior,
7. at least one accepted negative mutation.

A source workflow is behavioral evidence. It is not an authority that may add a Core capability or redefine dependency, control, topology or materialization meaning.

## Domain coverage

### Software delivery

The existing accepted cases retain build/release-style DAG, artifact, approval, matrix and negative semantic-loss evidence. Their domain is now explicit rather than inferred from catalog prose.

### Data transformation

`A04-data-transformation-etl` is derived from the pinned Apache Airflow TaskFlow ETL example. The case requires three canonical `DATA_TRANSFORM` tasks and two named VALUE relations:

- `orders`: extract to transform
- `order_total`: transform to load

Removing the extract output must fail before planning with `REAL_WORLD_MISSING_VALUE_PRODUCER`.

### Infrastructure state change

`N05-runtime-generated-infrastructure` consumes the pinned Buildkite dynamic pipeline example. The visible conditional deployment is preserved as `DEPLOY`, while arbitrary runtime-generated fan-out remains explicitly unsupported.

The accepted result is `UNSUPPORTED_DYNAMIC_CONSTRUCTION` with `REAL_WORLD_RUNTIME_PLAN_NOT_REPRESENTED`. No static task list is allowed to impersonate runtime plan generation.

## Independent gates

C0.1 adds a dedicated conformance inventory after frozen Core closure and the completed adapter inventory. The gates verify:

- lifecycle alignment across work package, roadmap index, conformance roadmap and release state,
- exact source and case composition,
- closed domain coverage,
- every accepted case through production intent loading, validation, AST lowering and planning,
- all mutations with exact diagnostics,
- at least one accepted diagnostic mutation per domain,
- exact conformance check inventory.

The Core pre-closure inventory and adapter inventory remain unchanged.

## Completion boundary

The work package remains `active` and C0.1 remains `next` until Flow CI passes both the exact implementation head and its synthetic merge candidate. Only then may completion metadata select C0.2.
