Prevent applies if there are any changes on the base branch since the most recent plan.

`undiverged` enforces that Atlantis local version of main is up to date
with remote so that the state of the source during the `apply` is identical to that if you were to merge the PR at that
time. In the case of a transient error, Atlantis assumes divergence for safety and errors.
