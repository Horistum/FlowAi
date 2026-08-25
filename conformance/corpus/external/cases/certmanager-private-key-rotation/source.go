
// CertificatePrivateKey contains configuration options for private keys
// used by the Certificate controller.
// These include the key algorithm and size, the used encoding and the
// rotation policy.
type CertificatePrivateKey struct {
	// RotationPolicy controls how private keys should be regenerated when a
	// re-issuance is being processed.
	//
	// If set to `Never`, a private key will only be generated if one does not
	// already exist in the target `spec.secretName`. If one does exist but it
	// does not have the correct algorithm or size, a warning will be raised
	// to await user intervention.
	// If set to `Always`, a private key matching the specified requirements
	// will be generated whenever a re-issuance occurs.
	// Default is `Always`.
	// The default was changed from `Never` to `Always` in cert-manager >=v1.18.0.
	// +optional
	RotationPolicy PrivateKeyRotationPolicy `json:"rotationPolicy,omitempty"`