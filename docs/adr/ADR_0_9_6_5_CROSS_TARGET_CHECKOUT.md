# Architecture Decision: Add reviewed cross-target checkout projections

## Status

Accepted

## Change

The built-in distribution implements `git.checkout` as a native projection for Jenkins, GitHub Actions and Tekton through the provider-owned contracts introduced in v0.9.6.4.

The Flow semantic inputs remain target-neutral:

- repository `url`;
- checkout `branch`;
- optional checkout `depth`, where `0` means full history.

Concrete target mappings remain edge-owned:

- Jenkins uses the native `git` step for full history and structured Git SCM checkout for shallow depth;
- GitHub Actions uses `actions/checkout@v4` with `repository`, `ref` and `fetch-depth`;
- Tekton uses the reviewed `git-clone` Task contract with params and an explicit Pipeline workspace.

## Main Flow Axis

- Target registry evidence
- Native projection provider contracts
- Typed binding preservation
- Target manifest generation
- Concrete renderer behavior
- Behavioral conformance

## Semantic fidelity

A checkout projection is complete only when repository identity, branch and depth are preserved. The target renderer may transform representation but must not change meaning.

GitHub Actions does not accept an arbitrary Git URL as its `repository` input. The renderer therefore accepts only values that prove a `github.com` owner/repository identity and rejects other hosts.

Tekton git-clone requires an `output` workspace. The provider contract carries task target metadata as a typed workspace binding, the Pipeline declares that workspace, and the task binds its `output` workspace to it.

Depth `0` preserves full-history checkout. Positive values request shallow checkout. Negative or non-numeric depth fails closed.

## Selected targets

- Jenkins: native checkout remains supported and gains explicit typed depth preservation.
- GitHub Actions: native checkout is added with GitHub repository identity validation.
- Tekton: native checkout is added with explicit workspace structure.

Other built-in targets receive no checkout rule and remain adapter-required or review-only.

## Explicit non-goals

This decision does not add:

- runtime execution;
- SDK or plugin lifecycle;
- classpath discovery;
- target-specific public Flow syntax;
- shell or command projection;
- credentials or authentication inference;
- native image-build projection;
- an executable multi-step reference scenario.

## Negative conformance

Tests reject:

- a GitHub Actions checkout URL outside `github.com`;
- negative or non-numeric checkout depth;
- registry/catalog mismatch;
- missing checkout evidence on an unimplemented target;
- incomplete or unresolved bindings through existing readiness rules.

## Report budget

No new public report contract is added. The bounded work report records implementation scope while Target Manifest and existing readiness artifacts remain the public evidence surfaces.

## Drift score

The change adds target-native coverage only through the existing provider boundary. It introduces no runtime, SDK, plugin discovery, shell projection or target-specific semantic model. No Drift Score exception is required.

## Consequences

Later native actions must follow the same four-part evidence rule: target registry declaration, provider-owned implementation contract, concrete renderer behavior and behavioral tests. A superficially similar target primitive is insufficient when its required runtime structure differs.
