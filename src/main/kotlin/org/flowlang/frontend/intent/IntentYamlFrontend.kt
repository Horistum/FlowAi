package org.flowlang.frontend.intent

import java.io.File
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.compiler.CapturedCompilationSource
import org.flowlang.compiler.CompilationFrontend
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.CompilationSourceCapture
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.IntentCompilationInput
import org.flowlang.intent.IntentDocument

/** Strict Intent YAML frontend. It owns parsing and provenance, never planning. */
class IntentYamlFrontend(
    private val compiler: FlowCompilationService
) {
    internal fun load(file: File): CapturedCompilationSource<IntentDocument> =
        CompilationSourceCapture.capture(
            file = file,
            frontend = CompilationFrontend.INTENT_YAML,
            parse = IntentYamlLoader::loadText
        )

    fun compile(file: File): CompilationResult = compile(load(file))

    fun compileText(text: String, sourceName: String = "<intent>"): CompilationResult = compile(
        CompilationSourceCapture.captureText(
            text = text,
            identity = sourceName,
            frontend = CompilationFrontend.INTENT_YAML,
            parse = IntentYamlLoader::loadText
        )
    )

    internal fun compile(
        captured: CapturedCompilationSource<IntentDocument>
    ): CompilationResult = compiler.compile(
        IntentCompilationInput(
            source = captured.source,
            intent = captured.value
        )
    )
}
