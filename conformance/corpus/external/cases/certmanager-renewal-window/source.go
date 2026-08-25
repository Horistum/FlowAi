
	// How long before the currently issued certificate's expiry cert-manager should
	// renew the certificate. For example, if a certificate is valid for 60 minutes,
	// and `renewBefore=10m`, cert-manager will begin to attempt to renew the certificate
	// 50 minutes after it was issued (i.e. when there are 10 minutes remaining until
	// the certificate is no longer valid).
	//
	// NOTE: The actual lifetime of the issued certificate is used to determine the
	// renewal time. If an issuer returns a certificate with a different lifetime than
	// the one requested, cert-manager will use the lifetime of the issued certificate.
	//
	// If unset, this defaults to 1/3 of the issued certificate's lifetime.
	// Minimum accepted value is 5 minutes.
	// Value must be in units accepted by Go time.ParseDuration https://golang.org/pkg/time/#ParseDuration.
	// Cannot be set if the `renewBeforePercentage` field is set.
	// +optional
	RenewBefore *metav1.Duration `json:"renewBefore,omitempty"`

	// `renewBeforePercentage` is like `renewBefore`, except it is a relative percentage
	// rather than an absolute duration. For example, if a certificate is valid for 60
	// minutes, and  `renewBeforePercentage=25`, cert-manager will begin to attempt to
	// renew the certificate 45 minutes after it was issued (i.e. when there are 15
	// minutes (25%) remaining until the certificate is no longer valid).
	//