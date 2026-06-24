package org.flowlang.adapters.yaml

import org.flowlang.intent.IntentDocument
import java.io.File

/**
 * Adapter-facing entry point for loading Flow Intent YAML.
 *
 * The legacy loader remains in org.flowlang.intent for compatibility. New code
 * should depend on this adapter package so the intent model can stay a pure core
 * contract in future multi-module layouts.
 */
object IntentYamlLoader {
    fun load(file: File): IntentDocument = org.flowlang.intent.IntentYamlLoader.load(file)
    fun loadText(text: String, sourceName: String = "<intent>"): IntentDocument =
        org.flowlang.intent.IntentYamlLoader.loadText(text, sourceName)
    fun normalize(root: Map<String, Any?>, sourceName: String = "<intent>"): IntentDocument =
        org.flowlang.intent.IntentYamlLoader.normalize(root, sourceName)
}
