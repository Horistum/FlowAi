type CreateOptions struct {
	BackupName                  string
	ScheduleName                string
	RestoreName                 string
	RestoreVolumes              flag.OptionalBool
	PreserveNodePorts           flag.OptionalBool
	Labels                      flag.Map
	Annotations                 flag.Map
	IncludeNamespaces           flag.StringArray
	ExcludeNamespaces           flag.StringArray
	ExistingResourcePolicy      string
	IncludeResources            flag.StringArray
	ExcludeResources            flag.StringArray
	StatusIncludeResources      flag.StringArray
	StatusExcludeResources      flag.StringArray
	NamespaceMappings           flag.Map
	Selector                    flag.LabelSelector
	OrSelector                  flag.OrLabelSelector
	IncludeClusterResources     flag.OptionalBool
	Wait                        bool
	AllowPartiallyFailed        flag.OptionalBool
	ItemOperationTimeout        time.Duration
	ResourceModifierConfigMap   string
	ResourcePoliciesConfigMap   string
	SkipDefaultResourceModifier bool
	WriteSparseFiles            flag.OptionalBool
	ParallelFilesDownload       int
	client                      kbclient.WithWatch
}

func NewCreateOptions() *CreateOptions {
	return &CreateOptions{
		Labels:                  flag.NewMap(),
		Annotations:             flag.NewMap(),
		IncludeNamespaces:       flag.NewStringArray("*"),
		NamespaceMappings:       flag.NewMap().WithEntryDelimiter(',').WithKeyValueDelimiter(':'),
		RestoreVolumes:          flag.NewOptionalBool(nil),
		PreserveNodePorts:       flag.NewOptionalBool(nil),
		IncludeClusterResources: flag.NewOptionalBool(nil),
		WriteSparseFiles:        flag.NewOptionalBool(nil),
	}
}

func (o *CreateOptions) BindFlags(flags *pflag.FlagSet) {
	flags.StringVar(&o.BackupName, "from-backup", "", "Backup to restore from")
	flags.StringVar(&o.ScheduleName, "from-schedule", "", "Schedule to restore from")
	flags.Var(&o.IncludeNamespaces, "include-namespaces", "Namespaces to include in the restore (use '*' for all namespaces)")
	flags.Var(&o.ExcludeNamespaces, "exclude-namespaces", "Namespaces to exclude from the restore.")
	flags.Var(&o.NamespaceMappings, "namespace-mappings", "Namespace mappings from name in the backup to desired restored name in the form src1:dst1,src2:dst2,...")
}
