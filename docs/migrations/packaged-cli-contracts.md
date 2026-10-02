# Packaged CLI contracts

The product distribution now owns its built-in module descriptors, target registry,
reference inputs, semantic/safety notes and adapter evidence. `flow-cli:installDist` and `flow-cli:distZip`
produce the same application bytes. Move the entire distribution directory, including
`bin` and `lib`, and run it with JDK 25 from any working directory:

```sh
./gradlew :flow-cli:installDist :flow-cli:distZip
/absolute/path/to/flow-core/bin/flow-core resources
/absolute/path/to/flow-core/bin/flow-core modules
/absolute/path/to/flow-core/bin/flow-core targets
/absolute/path/to/flow-core/bin/flow-core intent --out ./output
/absolute/path/to/flow-core/bin/flow-core intent ./request.yaml --target jenkins --render
```

Relative authored input and output paths still resolve against the caller's working
directory. An omitted `intent` input selects the packaged build-test-deploy example.
Local `modules`, `targets`, `adapters` or `examples` directories are never implicit
contract authorities. Previously these directories could change CLI behavior or make
an installed command fail outside a checkout.

`intent`, `normalize`, `flow`, `modules`, `targets` and the new `resources` command
accept `--contracts <root>` (also `--contracts=<root>`). This explicitly replaces the
entire inventoried resource set. The directory must contain every relative path in
`gradle/reference-contract-resources.txt`; additional files are not selected. Missing
files and symbolic links within that root are errors, with no packaged fallback.
This bounded override changes existing inventoried contracts; adding resource paths
requires an explicit distribution inventory change. Existing strict loaders still
validate all consumed descriptors and evidence. The `resources` command inspects
byte provenance; it does not certify semantic validity or adapter support.

Each resource-consuming command publishes a `FLOW CONTRACT RESOURCE PROVENANCE`
JSON section containing each logical path, `CLASSPATH` or `EXTERNAL` origin, stable
classpath identity or explicit file URI, actual byte length and SHA-256. Capture
stdout to retain this record. Target expression evidence uses stable `contract:<path>#<anchor>` identities, resolved against that selected resource inventory. Temporary snapshot paths are never published as those evidence identities. The root used internally by File-based authorities is
a private command-scoped snapshot and is removed on successful and exceptional
completion. It does not become the default input/output directory. Available temporary
disk space is required; this slice does not promise filesystem immutability or atomic
output publication. External hashes identify selected bytes, not trusted certification.

The explicit inventory is packaged once by `flow-reference-distribution`. Its generated
index binds every built-in file's actual bytes. Missing, ambiguous or hash-mismatched
classpath resources fail closed. Evidence includes referenced source text and committed
snapshots so existing adapter checks retain their full inputs. Such text is packaged
under `flow/reference-contracts/`, never compiled as verification code. No conformance
or release implementation is added to the product classpath.

The existing physical product isolation gate builds both distribution forms without
verification classes, relocates them to paths containing spaces, verifies resource
bytes, deletes the staged resource inputs, and runs them from empty and misleading
directories. It covers provenance, registries, default and authored intent, Flow source,
normalization including production-sensitive maintenance, explicit overrides, rejected incomplete overrides, an executable Jenkinsfile and the existing review-only GitHub Actions evidence. Target maturity must remain `PASS`, and output must
be identical across both distribution forms and working-directory variants.

Repository verification commands in the separate verification launcher still consume
their explicitly repository-owned verification inputs. Central input/output limits,
atomic artifact publication and independent whole-milestone acceptance remain later
AR-05 slices.

The selected notes authority is passed explicitly into compiler, decision analysis, maintenance normalization and reference renderer composition. Repository-default factories remain available for repository tools; product commands never select those ambient defaults.
