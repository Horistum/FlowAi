# AR-01C Reviewed AI Proposal Frontend Convergence

## Result

Reviewed AI proposals now enter the same `FlowCompilationService` and digest-bound `CompilationUnit` authority as Flow Source and strict Intent YAML. The former product-side `normalize --lower` composition path no longer performs proposal review, Intent validation, lowering, Flow validation or planning independently.

## Trust boundary

`ReviewedAiProposalFrontend` captures a deterministic source view containing provider identity, the authored request and the complete provider response. Caller-owned mutable collections are detached before source hashing. The standard then independently reviews and validates the captured proposal, and authorization binds successful proposal-review evidence, Intent validation, Flow validation and the exact canonical graph digest to the exact captured source digest.

Frontend evidence is fail-closed:

- Flow Source cannot claim Intent or proposal-review evidence;
- strict Intent YAML requires successful Intent validation and no AI proposal-review evidence;
- reviewed AI proposals require the exact successful review and Intent validation used by compilation;
- compatibility-plan authorization cannot claim source or frontend evidence.

## Verification

The final implementation content passed JDK 25 production and test compilation, 1,260 complete tests, 24 targeted proposal/provenance/mutation/bypass/ABI regressions, standalone conformance, Flow Agent tooling and structure validation, and `git diff --check`.

The authoritative implementation, validation and completion heads, synthetic merge candidates and Flow CI run identifiers are recorded in `.flow-agent/work-packages/AR-01C-reviewed-ai-proposal-frontend-convergence.yaml`.

Intent 2.0, AST 2.2, Execution Plan 2.4, lowering evidence 2.1, Target Manifest 3.0 and Target Registry 3.2 remain unchanged. AR-02 control-flow semantics and AR-01D retirement of superseded compatibility authorities remain separate milestones.
