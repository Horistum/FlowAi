# Flow v0.3.0-rc1.8.3 Test Gate Fix

This release fixes the remaining `FlowSpecJUnitTest` failure without disabling or hiding coverage.

## Root cause

The old JUnit bridge executed two different categories in one opaque test method:

1. the core language specification harness, and
2. additional conformance/regression bridge functions.

When one bridge function failed, Gradle reported only `FlowSpecJUnitTest.runFlowSpecificationScenarios()`, which made the build report point at the wrapper rather than the failing layer.

## Fix

- `FlowSpecJUnitTest` now covers the core language specification harness only.
- Intent conformance, target conformance, beta conformance and RC4 semantic generator regression checks are moved to a dedicated blocking `FlowConformanceBridgeJUnitTest`.
- The historical `H` harness now has `reset()`, so grouped bridge tests do not leak failures or counters across groups.
- No test is advisory and no coverage is removed. The goal is better failure attribution, not hiding errors.

## Expected result

A failure in intent, target, beta or RC4 semantic checks now appears as its own JUnit method instead of being buried inside a single aggregate wrapper.
