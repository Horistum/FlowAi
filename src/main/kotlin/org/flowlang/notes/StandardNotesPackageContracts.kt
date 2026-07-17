package org.flowlang.notes

import java.io.File

object StandardNotesPackageContracts {
    fun baseline(rootDir: File = File(".")): List<NotesPackageContract> =
        CanonicalNotesPackageLoader.load(rootDir)
}
