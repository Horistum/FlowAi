package org.flowlang.adapters.yaml

import org.flowlang.capabilities.TargetCapability
import org.flowlang.targets.TargetRegistryDocument
import java.io.File

/**
 * Adapter-facing entry point for target registry YAML loading.
 */
object TargetRegistryYamlLoader {
    fun load(file: File): TargetRegistryDocument = org.flowlang.targets.TargetRegistryYamlLoader.load(file)
    fun loadDirectory(dir: File): Map<String, TargetCapability> =
        org.flowlang.targets.TargetRegistryYamlLoader.loadDirectory(dir)
}
