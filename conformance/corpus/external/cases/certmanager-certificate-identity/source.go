	Renewal *CertificateRenewal `json:"renewal,omitempty"`

	// Requested DNS subject alternative names.
	// +optional
	// +listType=atomic
	DNSNames []string `json:"dnsNames,omitempty"`

	// Requested IP address subject alternative names.
	// +optional
	// +listType=atomic
	IPAddresses []string `json:"ipAddresses,omitempty"`

	// Requested URI subject alternative names.
	// +optional
	// +listType=atomic
	URIs []string `json:"uris,omitempty"`

	// `otherNames` is an escape hatch for SAN that allows any type. We currently restrict the support to string like otherNames, cf RFC 5280 p 37
	// Any UTF8 String valued otherName can be passed with by setting the keys oid: x.x.x.x and UTF8Value: somevalue for `otherName`.
	// Most commonly this would be UPN set with oid: 1.3.6.1.4.1.311.20.2.3
	// You should ensure that any OID passed is valid for the UTF8String type as we do not explicitly validate this.
	// +optional
	// +listType=atomic
	OtherNames []OtherName `json:"otherNames,omitempty"`

	// Requested email subject alternative names.
	// +optional
	// +listType=atomic
	EmailAddresses []string `json:"emailAddresses,omitempty"`

	// Name of the Secret resource that will be automatically created and
	// managed by this Certificate resource. It will be populated with a
	// private key and certificate, signed by the denoted issuer. The Secret
	// resource lives in the same namespace as the Certificate resource.
	SecretName string `json:"secretName"`

	// Defines annotations and labels to be copied to the Certificate's Secret.
	// Labels and annotations on the Secret will be changed as they appear on the
	// SecretTemplate when added or removed. SecretTemplate annotations are added
	// in conjunction with, and cannot overwrite, the base set of annotations
	// cert-manager sets on the Certificate's Secret.