# AI-A1 external proposal import

Baseline: merged proposal PR #254 at `803bd7d20bf4302799c76d2ddffa9f5fa11d4650`. Both proposal workflows passed before implementation began.

The installed product CLI now imports a versioned external proposal through the existing reviewed frontend and shared compiler. It publishes bounded structured diagnostics on input/semantic rejection, separates semantic acceptance from unassessed request fidelity, preserves exact input and interpreted-source digests, and withholds target artifacts for required provider decisions. Explicit target materialization consumes the existing compiler-authorized graph and target authority.

The new schemas, complete examples and [command contract](../../docs/EXTERNAL_AI_PROPOSALS.md) define the public boundary. Tests cover strict parsing, compiler convergence/provenance, provider questions/risks, target conflicts, multi-workflow preservation, atomic publication and the intentionally blocked existing-image request. The relocated installDist/distZip proof exercises accepted and rejected proposals without the source checkout.

AI-A1 is the selected bounded authoring slice using completed AR-01/AR-05 prerequisites. The lifecycle check validates this route and replays the unchanged prior recovery evidence. AR-06 remains open, full AR-07 closure retains its AR-06 dependency, F-16/F-20 keep their owners, and EF-09 stays paused. Confidence migration, live models and fidelity evaluation remain later work.

Validation evidence is recorded in [PR #255](https://github.com/Horistum/FlowAi/pull/255), including final-revision exact-head and merge-candidate GitHub jobs. Local verification covers frontend/CLI regressions, prior recovery gates, all 294 conformance checks, 285 Python tooling tests and 144 installed-process invocations across installDist/distZip and empty/misleading working directories. The final review also added rendered output and its evidence receipt to interactive presentation. This candidate report does not claim release or milestone closure; acceptance requires the PR's final-revision checks.
