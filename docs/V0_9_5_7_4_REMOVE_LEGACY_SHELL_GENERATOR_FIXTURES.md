# v0.9.5.7.4 Remove Legacy Shell Generator Fixtures

## Purpose

v0.9.5.7.4 removes the active test path that still treated shell-oriented Jenkins generation as supported Flow behavior.

The former test generator mapped Flow actions directly to Jenkins steps and runtime command strings. Its tests asserted those outputs as correct, even though the production architecture now requires semantic actions, materialization negotiation, projection evidence and renderer readiness.

## Changes

- The legacy `JenkinsGenerator` implementation is replaced by a compatibility tombstone.
- The core JUnit specification bridge no longer executes shell-oriented Jenkins generator scenarios.
- Dedicated regression tests verify that the tombstone cannot generate target syntax.
- All example files that still contain `shell.run` are discovered automatically and evaluated as blocked runtime intent.
- Explicit command content remains preserved in the manifest for review.
- Blocked shell actions produce `FAIL_FAST` readiness and cannot produce a Jenkins target artifact.

## Example classification

The following syntax examples remain temporarily in the repository for parser, validation and migration coverage:

- `hello.flow`
- `build-test.flow`
- `deploy-with-approval.flow`
- `complex-devops-flow.flow`

They are not executable reference scenarios. Until v0.9.5.7.9 replaces the flagship scenarios with universal semantic actions, their `shell.run` nodes are classified as preserved but blocked runtime intent.

The test suite discovers these files from source content and requires the discovered set to match the reviewed classification. A new shell-based example therefore cannot enter the active example corpus unnoticed.

## Test boundary

The retired monolithic generator scenarios are no longer invoked by JUnit. They are replaced by tests that verify:

- no active shell, container CLI, cluster CLI, chart CLI or deployment CLI projection remains in the legacy generator source
- the compatibility tombstone throws when invoked
- command content is not silently removed
- every shell example has blocked materialization
- renderer readiness is `FAIL_FAST`
- Jenkins syntax is not returned for blocked work

## Next step

v0.9.5.7.5 will connect capability compatibility to materialization and projection readiness so a target cannot be reported as supported while its required work is unresolved.
