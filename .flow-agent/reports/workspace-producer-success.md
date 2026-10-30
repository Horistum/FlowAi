# AR-06AE-C16: Native workspace producer failure containment

## Purpose and baseline

PR #251 merged at `e7a9824ce7add45f5d58b25737a6581a3c3747d5`. C16 verifies the missing failure boundary of its conditional workspace composition: successful checkout and successful publication are both required before a consumer may execute. The success-only scheduling refinement is a supporting adapter detail, not sufficient completion evidence by itself. The revised work package was committed before the native experiment implementation.

## Compiler-bound native experiments

The existing parsed-AST fixture adds two selected producer/consumer pairs. The real compiler derives eleven workspace edges across seventeen baseline jobs and five independent artifact identities. The generated production workflow has no injected failures or error suppression.

The executed verification envelope introduces exactly two controlled faults. One checkout requests a revision-specific nonexistent Git reference; its upload and observer must be skipped. Another checkout succeeds, but its upload requests a revision-specific nonexistent path with `if-no-files-found: error`; its observer must be skipped. Both producer jobs use job-level failure tolerance solely to permit the verification workflow to finish. No producer step may use `continue-on-error`, and the collector requires the original failed job and exact failed/skipped steps from the same-attempt GitHub API. Unexpected success, cancellation, skipped fault injection or failure at the wrong phase is rejected.

The corresponding generated consumer jobs retain their exact production dependencies and success conditions. Both must be skipped without steps or outputs. Two additional mutants remove only the consumer scheduling guard. They must actually attempt download and build in clean native runners; both actions must fail. Step-level tolerance is restricted to these mutant actions so a final assertion can check their original outcomes. The verifier then rejects this observed execution against the blocked baseline consumer contract. All thirteen earlier baseline jobs and four earlier mutants remain intact.

The envelope validator pins fault parameters, producer tolerance, mutant guards, native actions, dependencies and assertions to current compiler output. Thus a hand-authored passing test cannot replace the generated workflow.

## Independent admission

The source/graph/manifest oracle derives fault endpoints, blocked consumers and expected results independently from the runtime observations. It authenticates twenty-four native jobs including preparation: seventeen baselines and six mutants. Failed producer steps are retained in the archived API evidence. Mutants must start after their failed producer, while skipped consumers cannot fabricate execution intervals or outputs.

The existing coverage consumer regenerates compiler inputs and replays all failure assertions against the same-attempt API. Rehashing a proof with a successful/skipped producer, an executed baseline consumer or a mutant that did not encounter unavailable data does not admit it. This remains one bounded CI observation on the GitHub workspace row, not a signed portfolio expansion or a general continuity guarantee.

## Validation

Local validation includes the complete Python suite, Flow Agent structure/context, YAML parsing and diff checks. Five new Python regressions cover failure phases, forbidden consumer execution, actual mutant failures, source-skipping substitutions and rehashed archive forgery. JVM coverage retains six scheduling regressions and adds a compiler/envelope regression proving that fault injection and tolerance never enter production output. The existing envelope mutation test now also protects failure parameters and assertions.

Local Java is 17; the required JDK 25 build, exact-head and merge-candidate suites, native failure experiments and independent replay must pass on the revised PR head. Previous PR #252 CI proves only the earlier scheduling refinement and is not evidence for this extension. Final immutable-head results are recorded in the PR without a later source commit.

## Limits

The two fault modes are missing checkout reference and missing upload path. Cancellation, infrastructure/service outages, retries, repeated-control transport, filesystem metadata fidelity and general VALUE/STATE continuity remain outside this evidence. Job-level tolerance is test harness configuration, not a supported production recovery policy. Native API identity, runner isolation and assertion execution remain trusted. AR-06 stays active, AR-07 planned and EF-09 paused.
