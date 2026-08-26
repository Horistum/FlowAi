package org.flowlang.frontend.source

import java.io.File
import org.flowlang.ast.FlowDocument
import org.flowlang.compiler.CapturedCompilationSource
import org.flowlang.compiler.CompilationFrontend
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.CompilationSourceCapture
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.FlowSourceCompilationInput
import org.flowlang.parser.FlowParser

/** Flow Source frontend. It owns syntax parsing and provenance, never planning. */
class FlowSourceFrontend(
    private val compiler: FlowCompilationService,
    private val parser: FlowParser = FlowParser()
) {
    fun compile(file: File): CompilationResult {
        val captured: CapturedCompilationSource<FlowDocument> = CompilationSourceCapture.capture(
            file = file,
            frontend = CompilationFrontend.FLOW_SOURCE,
            parse = { text, sourceName -> parser.parse(text, sourceName) }
        )
        return compiler.compile(
            FlowSourceCompilationInput(
                source = captured.source,
                ast = captured.value
            )
        )
    }
}
