# Horistum artifact, distribution, CLI and I/O integrity

## AR-05A: typed CLI arguments

The maintainer selected AR-05 after AR-04 closure PR #190. The accepted baseline
and its independently executed main validation are recorded in
`.flow-agent/evidence/artifact-integrity-activation-baseline.json`. Historical
AR-04 implementation and completion receipts remain immutable.

The concrete F-19 defect is raw argument scanning: `intent --out result source.yaml`
could select `result` as its source, while `reference-snapshot` could also mistake
an option value for its input. Normalization discarded text after the first option.
Repeated and unknown options could be silently ignored, and some commands generated
reports or wrote files before discovering malformed options.

One parser in `flow-cli` now distinguishes typed flag options, value options and
positional inputs. Each built-in command declares its allowed syntax. Both product
and verification hosts finish parsing before executing command logic. Values support
separate and equals forms; options may be interspersed; `--` introduces literal
positionals. Invalid or ambiguous invocations produce `CLI_INVALID_INPUT` / exit 2.
The explicit custom-command port remains caller-owned. Three raw `parseOption`
implementations and positional scans are removed.

Behavioral tests cover all option/source orders, literal boundaries, source conflicts,
normalization modes, immutable parser results, both hosts, byte-identical reference
snapshots and preservation of existing output files on rejection. Two appended
Architecture Recovery conformance checks exercise the original source-selection
defect and no-write rejection through the public CLI.

The lifecycle records active AR-05 and implemented candidate AR-05A, without claiming
its future CI result. Predecessor proof checking replays only the validated succession
fields, retaining the original evidence, finding inventory and terminal Core checks.
F-03/F-05/F-19/F-22 remain open until independent whole-milestone acceptance. AR-05B
owns packaged contract resources and relocatable distributions; AR-05C owns central
limits; AR-05D owns staged publication and actual-byte receipts; AR-05E owns integration
and independent closure. EF-09 remains paused and AR-06/AR-07 remain planned.

Validation results for this candidate are recorded in its pull request after execution.
No local Kotlin result or future CI success is claimed. Public syntax changes and
migration examples are documented in `docs/migrations/typed-cli-arguments.md`.
