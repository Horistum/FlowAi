package org.flowlang.modules

/**
 * Module registry for Flow module contracts.
 *
 * The production default prefers module descriptors from the checked-out
 * `modules/*.yaml` directory or bundled resources. The hardcoded module list is
 * retained only as a deterministic fallback for embedded or stripped-down builds.
 */
class ModuleRegistry(
    private val modules: Map<String, FlowModule> = defaultModules().associateBy { it.name }
) {
    fun findModule(name: String): FlowModule? = modules[name]
    fun allModules(): Collection<FlowModule> = modules.values

    fun requireModule(name: String): FlowModule =
        modules[name] ?: error("Module '$name' is not registered")

    fun findAction(moduleName: String, actionName: String): ModuleActionContract? =
        modules[moduleName]?.actions?.get(actionName)

    /** Find the module + contract that provides a given system type. */
    fun findSystemType(typeName: String): Pair<FlowModule, SystemTypeContract>? {
        for (m in modules.values) m.systemTypes[typeName]?.let { return m to it }
        return null
    }

    companion object {
        /**
         * Builds a registry from `module.yaml` descriptors in [dir] (docs/06).
         * When [includeDefaults] is true, built-in modules fill any gaps so that
         * flows referencing modules without a descriptor still resolve.
         */
        fun fromDirectory(dir: java.io.File, includeDefaults: Boolean = true): ModuleRegistry {
            val loaded = ModuleYamlLoader.loadDirectory(dir).associateBy { it.name }
            val base = if (includeDefaults) defaultModules().associateBy { it.name } else emptyMap()
            return ModuleRegistry(base + loaded)
        }

        fun fromDescriptors(yamlTexts: List<String>, includeDefaults: Boolean = false): ModuleRegistry {
            val loaded = yamlTexts.map { ModuleYamlLoader.loadText(it) }.associateBy { it.name }
            val base = if (includeDefaults) defaultModules().associateBy { it.name } else emptyMap()
            return ModuleRegistry(base + loaded)
        }

        private fun stdOut(): Map<String, SchemaField> = mapOf(
            "ok" to SchemaField("boolean"),
            "status" to SchemaField("text"),
            "code" to SchemaField("number"),
            "data" to SchemaField("any"),
            "text" to SchemaField("text"),
            "lines" to SchemaField("list"),
            "json" to SchemaField("json"),
            "yaml" to SchemaField("yaml"),
            "error" to SchemaField("object"),
            "meta" to SchemaField("map"),
            "artifacts" to SchemaField("map")
        )

        fun defaultModules(): List<FlowModule> = descriptorModules().ifEmpty { hardcodedDefaultModules() }

        private fun descriptorModules(): List<FlowModule> {
            val workingTreeModules = ModuleYamlLoader.loadDirectory(java.io.File("modules"))
            if (workingTreeModules.isNotEmpty()) return workingTreeModules

            val resourceNames = listOf(
                "shell.yaml",
                "git.yaml",
                "rest.yaml",
                "notify.yaml",
                "docker.yaml",
                "helm.yaml",
                "argocd.yaml",
                "kubernetes.yaml",
                "database.yaml",
                "standard.yaml"
            )
            val loader = ModuleRegistry::class.java.classLoader
            val resourceModules = resourceNames.mapNotNull { name ->
                loader.getResourceAsStream("modules/$name")?.bufferedReader()?.use { reader ->
                    ModuleYamlLoader.loadText(reader.readText())
                }
            }
            return if (resourceModules.size == resourceNames.size) resourceModules else emptyList()
        }

        private fun hardcodedDefaultModules(): List<FlowModule> = listOf(
            FlowModule(
                name = "shell", version = "1.0", description = "Shell command execution module.",
                systemTypes = mapOf("shell" to SystemTypeContract("shell")),
                actions = mapOf(
                    "run" to ModuleActionContract(
                        name = "run", targetTypes = setOf("shell"),
                        input = mapOf("command" to SchemaField("text", required = true)),
                        output = stdOut(),
                        effects = Effects(executes = listOf("shell.command")),
                        retrySupported = true, timeoutSupported = true
                    )
                )
            ),
            FlowModule(
                name = "git", version = "1.0", description = "Git repository access module.",
                systemTypes = mapOf("git" to SystemTypeContract("git", input = mapOf(
                    "url" to SchemaField("text"), "branch" to SchemaField("text")
                ))),
                actions = mapOf(
                    "checkout" to ModuleActionContract(
                        name = "checkout", targetTypes = setOf("git"),
                        input = mapOf("branch" to SchemaField("text"), "url" to SchemaField("text"), "depth" to SchemaField("number")),
                        output = stdOut() + mapOf("path" to SchemaField("text")),
                        effects = Effects(reads = listOf("git.repository"), filesystem = listOf("workspace.write")),
                        retrySupported = true
                    )
                )
            ),
            FlowModule(
                name = "rest", version = "1.0", description = "HTTP REST request module.",
                systemTypes = mapOf("rest" to SystemTypeContract("rest", input = mapOf(
                    "baseUrl" to SchemaField("text", sensitive = false),
                    "token" to SchemaField("secret", sensitive = true)
                ))),
                actions = mapOf(
                    "call" to ModuleActionContract(
                        name = "call", targetTypes = setOf("rest"),
                        input = mapOf(
                            "method" to SchemaField("text", required = true),
                            "path" to SchemaField("text", required = true),
                            "body" to SchemaField("any"),
                            "headers" to SchemaField("map"),
                            "query" to SchemaField("map")
                        ),
                        output = stdOut(),
                        effects = Effects(network = listOf("http.request")),
                        retrySupported = true, timeoutSupported = true
                    )
                )
            ),
            FlowModule(
                name = "notify", version = "1.0", description = "Notification and email delivery module.",
                systemTypes = mapOf(
                    "notify" to SystemTypeContract("notify", input = mapOf("channel" to SchemaField("text"))),
                    "email" to SystemTypeContract("email", input = mapOf("channel" to SchemaField("text")))
                ),
                actions = mapOf(
                    "send" to ModuleActionContract(
                        name = "send", targetTypes = setOf("notify", "email"),
                        input = mapOf(
                            "subject" to SchemaField("text", required = true),
                            "body" to SchemaField("text"),
                            "to" to SchemaField("text"),
                            "channel" to SchemaField("text")
                        ),
                        output = stdOut(), effects = Effects(network = listOf("notify.send"))
                    ),
                    "email" to ModuleActionContract(
                        name = "email", targetTypes = setOf("notify", "email"),
                        input = mapOf(
                            "to" to SchemaField("text", required = true),
                            "subject" to SchemaField("text", required = true),
                            "body" to SchemaField("text")
                        ),
                        output = stdOut(), effects = Effects(network = listOf("email.send"))
                    )
                )
            ),
            FlowModule(
                name = "docker", version = "1.0", description = "Container image build and push module.",
                systemTypes = mapOf("docker" to SystemTypeContract("docker", input = mapOf(
                    "url" to SchemaField("text", sensitive = true)
                ))),
                actions = mapOf(
                    "build" to ModuleActionContract(
                        name = "build", targetTypes = setOf("docker"),
                        input = mapOf(
                            "image" to SchemaField("text", required = true),
                            "path" to SchemaField("text"),
                            "dockerfile" to SchemaField("text"),
                            "push" to SchemaField("boolean")
                        ),
                        output = stdOut() + mapOf("tag" to SchemaField("text"), "digest" to SchemaField("text")),
                        effects = Effects(creates = listOf("docker.image"), executes = listOf("docker.build")),
                        retrySupported = true
                    ),
                    "push" to ModuleActionContract(
                        name = "push", targetTypes = setOf("docker"),
                        input = mapOf("image" to SchemaField("text", required = true)),
                        output = stdOut(), effects = Effects(network = listOf("docker.registry"))
                    )
                )
            ),
            FlowModule(
                name = "helm", version = "1.0", description = "Helm chart rendering and release module.",
                systemTypes = mapOf("helm" to SystemTypeContract("helm")),
                actions = mapOf(
                    "template" to ModuleActionContract(
                        name = "template", targetTypes = setOf("helm"),
                        input = mapOf(
                            "chart" to SchemaField("text", required = true),
                            "values" to SchemaField("text"),
                            "namespace" to SchemaField("text")
                        ),
                        output = stdOut(), effects = Effects(executes = listOf("helm.template"))
                    ),
                    "upgrade" to ModuleActionContract(
                        name = "upgrade", targetTypes = setOf("helm"),
                        input = mapOf(
                            "chart" to SchemaField("text", required = true),
                            "release" to SchemaField("text", required = true),
                            "values" to SchemaField("text"),
                            "namespace" to SchemaField("text")
                        ),
                        output = stdOut(),
                        effects = Effects(updates = listOf("helm.release")),
                        retrySupported = true
                    )
                )
            ),
            FlowModule(
                name = "argocd", version = "1.0", description = "Argo CD application synchronization module.",
                systemTypes = mapOf("argocd" to SystemTypeContract("argocd", input = mapOf(
                    "url" to SchemaField("text", required = true, sensitive = false),
                    "token" to SchemaField("secret", required = true, sensitive = true)
                ))),
                actions = mapOf(
                    "sync" to ModuleActionContract(
                        name = "sync", targetTypes = setOf("argocd"),
                        input = mapOf(
                            "app" to SchemaField("text", required = true),
                            "wait" to SchemaField("boolean", defaultValue = true),
                            "timeout" to SchemaField("duration", defaultValue = "5m")
                        ),
                        output = stdOut() + mapOf(
                            "health" to SchemaField("text"), "sync" to SchemaField("text"), "revision" to SchemaField("text")
                        ),
                        effects = Effects(reads = listOf("argocd.application"), writes = listOf("argocd.application.sync"), network = listOf("argocd.api")),
                        idempotent = "true", retrySupported = true, timeoutSupported = true
                    )
                )
            ),
            FlowModule(
                name = "kubernetes", version = "1.0", description = "Kubernetes resource operation module.",
                systemTypes = mapOf("kubernetes" to SystemTypeContract("kubernetes", input = mapOf(
                    "context" to SchemaField("text")
                ))),
                actions = mapOf(
                    "get" to ModuleActionContract(
                        name = "get", targetTypes = setOf("kubernetes"),
                        input = mapOf(
                            "resource" to SchemaField("text", required = true),
                            "namespace" to SchemaField("text"),
                            "name" to SchemaField("text"),
                            "selector" to SchemaField("text")
                        ),
                        output = stdOut(), effects = Effects(reads = listOf("kubernetes.resource"), network = listOf("kubernetes.api"))
                    ),
                    "deploy" to ModuleActionContract(
                        name = "deploy", targetTypes = setOf("kubernetes"),
                        input = mapOf(
                            "app" to SchemaField("text"),
                            "name" to SchemaField("text"),
                            "namespace" to SchemaField("text"),
                            "image" to SchemaField("text"),
                            "manifest" to SchemaField("text")
                        ),
                        output = stdOut(),
                        effects = Effects(creates = listOf("kubernetes.workload"), updates = listOf("kubernetes.workload")),
                        retrySupported = true
                    ),
                    "delete" to ModuleActionContract(
                        name = "delete", targetTypes = setOf("kubernetes"),
                        input = mapOf(
                            "resource" to SchemaField("text", required = true),
                            "name" to SchemaField("text", required = true),
                            "namespace" to SchemaField("text")
                        ),
                        output = stdOut(),
                        effects = Effects(deletes = listOf("kubernetes.resource")),
                        safety = SafetyContract(destructive = true, requiresSafety = true)
                    )
                )
            ),
            FlowModule(
                name = "database", version = "1.0", description = "Database query and row mutation module.",
                systemTypes = mapOf("database" to SystemTypeContract("database", input = mapOf(
                    "engine" to SchemaField("text"),
                    "url" to SchemaField("text", sensitive = true)
                ))),
                actions = mapOf(
                    "upsert" to ModuleActionContract(
                        name = "upsert", targetTypes = setOf("database"),
                        input = mapOf(
                            "table" to SchemaField("text", required = true),
                            "key" to SchemaField("any", required = true),
                            "values" to SchemaField("any", required = true)
                        ),
                        output = stdOut(), effects = Effects(writes = listOf("database.row")),
                        idempotent = "true", retrySupported = true
                    ),
                    "query" to ModuleActionContract(
                        name = "query", targetTypes = setOf("database"),
                        input = mapOf("sql" to SchemaField("text", required = true)),
                        output = stdOut(), effects = Effects(reads = listOf("database.row"))
                    ),
                    "delete" to ModuleActionContract(
                        name = "delete", targetTypes = setOf("database"),
                        input = mapOf("table" to SchemaField("text", required = true), "key" to SchemaField("any", required = true)),
                        output = stdOut(),
                        effects = Effects(deletes = listOf("database.row")),
                        safety = SafetyContract(destructive = true, requiresSafety = true)
                    )
                )
            ),
            FlowModule(
                name = "standard", version = "1.0", description = "Portable semantic Flow standard operation module.",
                systemTypes = mapOf("standard" to SystemTypeContract("standard")),
                actions = mapOf(
                    "execute" to ModuleActionContract(
                        name = "execute", targetTypes = setOf("standard"),
                        input = mapOf(
                            "operation" to SchemaField("text", required = true),
                            "capability" to SchemaField("text"),
                            "description" to SchemaField("text"),
                            "flow" to SchemaField("text")
                        ),
                        output = stdOut(),
                        effects = Effects(executes = listOf("flow.standard.operation")),
                        additionalParams = true, retrySupported = true, timeoutSupported = true
                    ),
                    "rollback" to ModuleActionContract(
                        name = "rollback", targetTypes = setOf("standard"),
                        input = mapOf(
                            "operation" to SchemaField("text"),
                            "reason" to SchemaField("text"),
                            "flow" to SchemaField("text")
                        ),
                        output = stdOut(),
                        effects = Effects(updates = listOf("flow.rollback")),
                        additionalParams = true, retrySupported = true
                    )
                )
            )

        )
    }
}
