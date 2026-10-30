# AR-06AE-C16: Native workspace producer failure containment

## Purpose and baseline

PR #251 merged at `e7a9824ce7add45f5d58b25737a6581a3c3747d5`. C16 verifies the missing failure boundary of its conditional workspace composition: successful checkout and successful publication are both required before a consumer may execute. The success-only scheduling refinement is a supporting adapter detail, not sufficient completion evidence by itself. The revised work package was committed before the native experiment implementation.

## Compiler-bound native experiments

The existing parsed-AST fixture adds two selected producer/consumer pairs. The real compiler derives eleven workspace edges across seventeen baseline jobs and five independent artifact identities. The generated production workflow has no injected failures or error suppression.

The executed verification envelope introduces exactly two controlled faults. One checkout requests a revision-specific nonexistent Git reference; its upload and observer must be skipped. Another checkout succeeds, but its upload requests a revision-specific nonexistent path with `if-no-files-found: error`; its observer must be skipped. Neither producer jobs nor their steps suppress errors. The standalone probe workflow must finish with failure, while its collector must succeed and authenticate the original failed jobs and exact failed/skipped steps from that run attempt. Unexpected success, cancellation, skipped fault injection or failure at the wrong phase is rejected.

The corresponding generated consumer jobs retain their exact production dependencies and success conditions. Both must be skipped without steps or outputs. Two additional mutants remove only the consumer scheduling guard. They must actually attempt download and build in clean native runners; both actions must fail. Step-level tolerance is restricted to these mutant actions so a final assertion can check their original outcomes. The verifier then rejects this observed execution against the blocked baseline consumer contract. All thirteen earlier baseline jobs and four earlier mutants remain intact.

The envelope validator pins fault parameters, absence of producer error suppression, mutant guards, native actions, dependencies and assertions to current compiler output. Thus a hand-authored passing test cannot replace the generated workflow.

## Independent admission

The source/graph/manifest oracle derives fault endpoints, blocked consumers and expected results independently from the runtime observations. It authenticates twenty-four native jobs including preparation: seventeen baselines and six mutants. Failed producer steps are retained in the archived API evidence. Mutants must start after their failed producer, while skipped consumers cannot fabricate execution intervals or outputs.

A positive observer in Adapter Runtime Certification downloads the separate probe archive and authenticates its repository, workflow path, source revision, pinned attempt and successful collector. Its receipt pins both the parent run and native run identities without rewriting the proof. The existing coverage consumer independently re-authenticates that native run, regenerates compiler inputs and replays all failure assertions against the same-attempt API. Rehashing a proof with a successful/skipped producer, an executed baseline consumer or a mutant that did not encounter unavailable data does not admit it. This remains one bounded CI observation on the GitHub workspace row, not a signed portfolio expansion or a general continuity guarantee.

## Validation

The first revised experiment (runtime run `40997697205`) exposed an invalid harness assumption: job-level `continue-on-error` preserved failed API conclusions but allowed downstream success guards to execute. That result was rejected. The corrected design separates the intentionally failing native run from its positive admission and removes producer error suppression entirely. Production scheduling is not changed to accommodate the harness.

Local validation includes the complete Python suite, Flow Agent structure/context, YAML parsing and diff checks. Twenty-four new Python regressions cover failure phases, forbidden consumer execution, actual mutant failures, source-skipping substitutions, rehashed archive forgery, cross-run provenance, collector admission and bounded polling. The complete local suite contains 301 tests. JVM coverage retains six scheduling regressions and adds a compiler/envelope regression proving that fault injection and tolerance never enter production output. The existing envelope mutation test now also protects failure parameters and assertions.

Local Java is 17; the required JDK 25 build, exact-head and merge-candidate suites, native failure collector and independent replay must pass (the standalone fault workflow must fail with exactly the two expected producer failures) on the revised PR head. Previous PR #252 CI proves only the earlier scheduling refinement and is not evidence for this extension. Final immutable-head results are recorded in the PR without a later source commit.

## Limits

The two fault modes are missing checkout reference and missing upload path. Cancellation, infrastructure/service outages, retries, repeated-control transport, filesystem metadata fidelity and general VALUE/STATE continuity remain outside this evidence. The standalone fault workflow is intentionally red; a passing observer cannot hide unexpected failures or substitute a skipped fault. Native API identity, runner isolation and assertion execution remain trusted. AR-06 stays active, AR-07 planned and EF-09 paused.


## Follow-up review and corrections

The first review identified three lifecycle defects despite successful native CI: newest-SHA selection could pick an unrelated event, latest-attempt metadata could invalidate a previously accepted proof, and the observer wait budget was shorter than the permitted probe critical path. The correction binds automatic runs to canonical triggering-event digests, downloads a pinned artifact ID, queries immutable attempt endpoints in both admission and replay, and aligns the elapsed polling/job deadlines with the native critical path. Manual dispatch now requires an explicit native run and attempt.

The second code review identified a related artifact-lifecycle race: stable artifact names and overwrite could replace evidence from earlier attempts. All native archive, prepared-input and test-report names now distinguish attempts without overwrite. Regression tests cover concurrent dispatch/other PR events, ambiguous matches, reruns during polling, explicit manual selection, wrong revisions, expired and duplicate archives, pagination and deadline boundaries. Complete native reruns are required; partial reruns fail closed. A focused test run also exposed an import-order dependency in the new test module; its explicit tools path now allows the eighteen lifecycle tests to run independently.

The intentionally red native workflow remains a documented architectural tradeoff. It is isolated from positive acceptance gates and retains read-only permissions. Removing its red conclusion by suppressing producer failures would invalidate the scheduler experiment; introducing a privileged dispatcher would add a different trust boundary. This revision does neither. Final GitHub validation must be recorded for the new immutable head before acceptance.


## Additional failure-path review

A further review reproduced two observer defects: explicit manual selection validated the run only after finding an archive, so an invalid selection without evidence could consume the entire polling budget; a slow successful API response could be accepted after the elapsed deadline. Manual selections now validate a completed exact attempt before archive lookup and reject unavailable evidence without fallback or waiting. Automatic observation retains its bounded wait for pending event-bound evidence. Deadline checks bracket every observation callback and guard the final return, preventing late results or an extra API observation after expiration.

Four added regressions cover invalid/unfinished manual metadata before artifact access, missing/expired/wrong-attempt manual archives, slow responses at each API stage and no new API call after the deadline. The focused suite passes independently (18 tests), and the full Python suite passes (301 tests). Final CI evidence must be recorded for this source revision; previous-head results are not substituted.
