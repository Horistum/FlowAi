# Projection fixtures

These inputs exercise existing review-only manifest, secret, parameter and renderer
contracts independently of action result handlers. They deliberately contain no
handler assertions and are not migrations or equivalent replacements for the
examples under `examples/`.

The original examples remain unchanged and are now explicitly rejected by
`ActionResultHandlerContainmentTests`: their assertions must not disappear in
planning. Remaining data-operation semantic gaps are tracked as audit A-02.
