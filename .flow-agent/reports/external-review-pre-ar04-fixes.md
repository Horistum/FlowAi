# External review corrections before AR-04

Baseline: `dc3cfab1ec2e16ff315f6af0b8f3a7c1ef62707c`.
Review source: PR #182, `18d01dae490d47b6fbedd9714ede0759ddc393d0`.
Authorization: maintainer instruction on 2026-09-16 to fix confirmed defects before continuing AR-04.

## Validation status

Implementation candidate, not a completed release or recovery milestone. Exact-head and synthetic-merge-candidate Flow CI are required. No unobserved PASS, external Jenkins execution, or adapter certification is claimed here. The editing environment cannot run the repository's JDK 25/Gradle toolchain locally; repository CI is the authoritative full validation boundary.

## Jenkins boundary correction: EXT-01 and EXT-02

Regex text now uses a non-interpolating single-quoted Groovy string as the right operand of `==~`. Its regex meaning is retained, rather than escaping all regex dollars or relying on slashy-string escape rules. Empty patterns and trailing backslashes can no longer interfere with the Groovy source delimiter.

String and credential encoding share one single-quoted-literal implementation. It escapes backslashes, quotes and source line/control characters. Input properties preserve their exact names through quoted property syntax when ordinary dot identifiers are insufficient; they are not sanitized into potentially colliding names. Unknown Jenkins expression operators now reject explicitly.

`JenkinsLiteralBoundaryTests` evaluates generated code with GroovyShell on the test classpath. It covers literal round-trips, parser-to-regex translation, regex anchors and builtins, exact input properties, credential calls, unknown operators, and a harmless mutation negative control which verifies the fixture detects the former interpolation behavior. Groovy is a test dependency only, not a runtime executor added to the product.

## Remaining review scope

EXT-03 Core validation, EXT-04 scoped approval, EXT-05/EXT-06 decision evidence, EXT-07 graph digest and EXT-08 diagnostic consistency are separately authorized in `external-review-semantic-corrections.yaml` and are not claimed closed by the Jenkins patch. The existing AR-04 lifecycle and PR #182 remain unchanged. The larger AR-04 identity/type migration and the proposed product roadmap are outside this correction.
