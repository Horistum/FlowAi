# Flow Adapter Portfolio Changelog

## Unreleased

### A0.1 Adapter Portfolio Reassessment

Started the adapter roadmap after Core `0.9.7.10` and bounded correction `0.9.7.10.2` were merged and validated.

Implemented scope:

- transition the primary roadmap stream from completed Core work to adapters;
- generalize Flow Agent roadmap selection and tests for a non-Core primary stream;
- introduce a strict distribution-owned adapter portfolio manifest;
- require exactly one support, limitation and evidence record for every target registry identity;
- distinguish semantic reference, profile-only, native-leaf-only and executable-reference claims;
- reconcile portfolio claims with actual provider composition and native projection rules;
- preserve Jenkins as the only current committed end-to-end executable reference target;
- classify GitHub Actions and Tekton as native-leaf-only;
- classify Argo Workflows and Azure DevOps as profile-only;
- retain `local` as a semantic reference rather than promoting it into a production adapter;
- add committed executable snapshot validation for executable-reference claims;
- add `AdapterRoadmapLifecycleAuthority` to reject mixed, premature or evidence-free A0.1 completion metadata;
- add a separate adapter conformance inventory that runs after frozen Core closure;
- prove semantic Core packages do not depend on adapter portfolio authority;
- preserve package `0.9.5`, public standard `0.8.0` and artifact contract `2.0`.

Flow CI #2263 passed the initial portfolio implementation before lifecycle certification was added, so it is not used as the completion authority. A0.1 remains `next` and its work package remains `active` until a later exact-head and synthetic merge-candidate run includes the lifecycle check itself. Completion metadata and A0.2 selection are intentionally deferred until that evidence exists.
