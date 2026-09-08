# Compiler-enforced module boundaries

## Activation boundary

AR-02 was merged as PR #172, commit `c5c37283d99a47a6e387213dccfda2927ec6acd2`, tree `a3d4da21b128397e5401326d185ef459633b5ce4`. Its final exact-head and synthetic-merge CI passed 1,367 Kotlin tests each with no failures, errors or skips. The source tree used for this work was reconstructed and verified against that exact Git tree.

AR-03 starts with a separately validated activation transition. AR-03A will establish a real separately compiled semantic kernel, not a source-directory convention. A source-ownership manifest will assign the existing graph, digest and neutral dependencies to the kernel while the root project stops compiling those files. Retaining their source paths in this first bounded cut keeps historical evidence references and source-governance coverage intact. Physical relocation is not required for Kotlin classpath isolation and will be reviewed together with module-aware source discovery later.

The residual root project still contains compiler orchestration, frontends, adapters, conformance and distribution. This activation does not claim their extraction, close F-10/F-20, activate AR-04, resume EF-09 or promote an adapter. Existing AR-02 receipts and completion metadata remain historical evidence rather than an authority that can permanently prohibit an independently authorized successor.

## Validation honesty

The baseline was obtained through GitHub and the local Git tree was verified. Local JDK 25 and an offline Gradle dependency cache are not available in this session. No local Kotlin compilation is claimed. Candidate activation and implementation receipts will be admitted only after their actual CI results exist.
