// ManagementPolicies determine how should Crossplane controllers manage an
// external resource through an array of ManagementActions.
type ManagementPolicies []ManagementAction

// A ManagementAction represents an action that the Crossplane controllers
// can take on an external resource.
// +kubebuilder:validation:Enum=Observe;Create;Update;Delete;LateInitialize;*
type ManagementAction string

const (
	// ManagementActionObserve means that the managed resource status.atProvider
	// will be updated with the external resource state.
	ManagementActionObserve ManagementAction = "Observe"

	// ManagementActionCreate means that the external resource will be created
	// using the managed resource spec.initProvider and spec.forProvider.
	ManagementActionCreate ManagementAction = "Create"

	// ManagementActionUpdate means that the external resource will be updated
	// using the managed resource spec.forProvider.
	ManagementActionUpdate ManagementAction = "Update"

	// ManagementActionDelete means that the external resource will be deleted
	// when the managed resource is deleted.
	ManagementActionDelete ManagementAction = "Delete"
)
