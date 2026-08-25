	// Reference to the issuer responsible for issuing the certificate.
	// If the issuer is namespace-scoped, it must be in the same namespace
	// as the Certificate. If the issuer is cluster-scoped, it can be used
	// from any namespace.
	//
	// The `name` field of the reference must always be specified.
	IssuerRef cmmeta.IssuerReference `json:"issuerRef"`

	// Requested basic constraints isCA value.
	// The isCA value is used to set the `isCA` field on the created CertificateRequest