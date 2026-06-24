# Standard Export Bundle

Introduced in v0.4.9.

The standard export bundle is the portable package of public Flow standard
materials. It is intended for implementers who want to inspect or validate an
implementation without depending on Kotlin internals.

The CLI command is:

```bash
./gradlew run --args="standard-export --out dist/flow-standard-0.7.5"
```

The bundle must include:

- public JSON reports,
- schemas,
- conformance vectors,
- standard catalogs,

The bundle can be verified with:

```bash
./gradlew run --args="standard-verify --bundle dist/flow-standard-0.7.5"
```
- target registry descriptors,
- examples,
- documentation.

The bundle manifest is exported as `standard-export-bundle.json`.

## v0.7.1 architecture debt cleanup

The standard export remains focused on public contracts and conformance evidence. v0.7.1 hardens existing governance checks by enforcing drift-score and report-budget rules through the conformance runner.

## v0.7.3 standard model projection coherence

v0.7.3 removes redundant registry-consistency gates from the active release profile. Release profile, export manifest, conformance levels, export bundle and public surface are projected from `StandardModel` and checked once by `v0.7.3.standard-model-projection-coherence`.


## v0.7.4 architecture delta analyzer

v0.7.4 keeps the StandardModel projection model and adds delta validation from `standard/architecture/standard-model-baseline-v0.7.3.yaml` to the active model. The release intentionally adds no new stable public artifact.

## v0.7.5 purpose coverage ratio

v0.7.5 keeps the stable public artifact surface unchanged and adds Purpose Coverage Ratio as release evidence. The exported bundle includes `docs/V0_7_5_PURPOSE_COVERAGE_RATIO.md` and the `v0.7.5.purpose-coverage-ratio` release gate.
