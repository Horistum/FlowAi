# GitHub Actions Executable Reference Scenario

This directory is the A1.0 target-scoped executable reference for the canonical `checkout-build-image` intent.

It proves the production GitHub Actions projection only for the bounded plan shape:

- `git.checkout`
- workspace channel `source`
- `docker.build`

The producer job uploads the complete workspace with `actions/upload-artifact@v7`; the consumer job restores it with `actions/download-artifact@v8` before the image build. Job ordering through `needs` remains ordering evidence and is not treated as workspace continuity.

This evidence does **not** claim generic GitHub Actions support for arbitrary workspace, value or state continuity. Any additional task, different channel, unresolved producer or unsupported continuity family remains blocked.

The historical Jenkins executable reference remains separately frozen under `conformance/snapshots/checkout-build-image` so A1.0 adapter evolution cannot rewrite the evidence boundary that previously certified Jenkins.
