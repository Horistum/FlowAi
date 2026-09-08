# Compiler-enforced module boundaries

## Baseline and activation

AR-02 was merged as PR #172, commit `c5c37283d99a47a6e387213dccfda2927ec6acd2`, tree `a3d4da21b128397e5401326d185ef459633b5ce4`. Its exact-head and synthetic-merge CI passed 1,367 Kotlin tests each. The local source tree was verified against that Git tree before modification.

The separate AR-03 activation commit `9412d4c94b0cad6a36c887d6d53b3d21f9d2d55b` passed Flow CI #3234, run `34190794340`, on the exact head and synthetic merge `0d16202ea51e6feaf4cc408f85e7a301a84eabb1`. Both jobs passed 1,374 Kotlin tests, Flow Agent validation and standalone conformance. Downloaded JUnit XML confirmed zero failures, errors or skips. Historical AR-02 receipts and completion metadata were not rewritten to authorize its successor.

## Implemented kernel slice

`flow-semantic-kernel` is a real Gradle project with independent compilation, JAR, compile/runtime classpaths and tests. Its six source files are unchanged in content and retain their original paths. One manifest partitions ownership: the application no longer compiles those files. Production classpath gates admit only the Kotlin standard library and JetBrains annotations, with no product, adapter, conformance or serialization dependencies. A composition regression verifies that the application consumes the separate kernel output.

Eight boundary tests include a successful external public-API compilation and compiler rejection of residual compiler, adapter, conformance and Jackson references, internal digest construction and external sealed-contract extension. Four behavioral tests exercise semantic digest identity and fail-closed graph, control and topology contracts. The physical isolation script rebuilds the same production kernel with all residual production sources and outputs absent, no substituted build or friend paths, and no build cache. It emits exact input fingerprints and machine-readable reports.

Separate compilation exposed seven cross-module smart-cast sites in three conformance sources. Stable local values preserve their existing predicates without changing kernel APIs. One historical regression suite now calls the public digest computer rather than its internal deprecated alias; its test identities and semantic assertions are retained.

Both normal CI jobs now include kernel reports and the physical isolation proof. Offline workflow triggers and its input manifest include the new module. Offline verification disables the build cache: `clean` alone can otherwise restore outputs copied from the prepared home. Root tests also re-execute instead of reusing classpath-only results because they inspect live repository metadata and source inventories.

## Validation and completion discipline

A real Temurin JDK 25.0.2 and resolved Gradle 9.5.0 / Kotlin 2.4.10 dependency cache were obtained for local testing. Local kernel compilation and all 12 kernel tests passed offline. Full product, conformance, baseline identity comparison and physical isolation results are recorded only after execution; CI implementation and offline receipts are admitted only after their actual results exist. No green activation run is reused as implementation evidence.

The kernel's own completion transition requires distinct implementation CI and a separate offline proof for the same head and synthetic merge. Full AR-03 completion remains pending. Compiler/frontend separation, concrete adapters and conformance/distribution composition remain future work, F-10/F-20 remain open, AR-04 is not activated and EF-09 stays paused. Public package and artifact versions, canonical graph semantics and target maturity do not change.

Source-layout migration debt and its owners are documented in `docs/COMPILER_MODULE_BOUNDARIES.md`: module-aware source discovery belongs to AR-03B, with an explicit relocation or reviewed retention decision at AR-03D and final AR-07 audit. Retained paths are not a governance exemption or a claim of full compiler extraction.
