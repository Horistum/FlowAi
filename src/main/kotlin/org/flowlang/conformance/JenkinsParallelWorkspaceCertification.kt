package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode

/** Bounded external-runtime protocol and exact-artifact mutants; never an executor. */
internal object JenkinsParallelWorkspaceCertification {
    fun artifacts(text: String, original: ByteArray, reject: Boolean): Map<String, ByteArray> {
        require(text.toByteArray().contentEquals(original))
        val start = text.lineSequence().single { it.trim() == "parallel([" }
        val indent = start.takeWhile(Char::isWhitespace)
        val end = "$indent])"
        require(text.lineSequence().count { it == end } == 1)
        val block = text.substring(text.indexOf(start), text.indexOf(end) + end.length)
        require(block.lineSequence().single { it.trimStart().startsWith("failFast:") }.trim() == "failFast: false")
        val left = ".flow-parallel/parallel_1_branch_1"
        val right = ".flow-parallel/parallel_1_branch_2"
        require(block.lineSequence().filter { it.trimStart().startsWith("dir(") }.map { it.trim() }.toList() ==
            listOf("dir('$left') {", "dir('$right') {"))
        require(block.lineSequence().filter { it.trimStart().startsWith("input message:") }.map { it.trim() }.toList() ==
            listOf("input message: 'Approve left branch?'", "input message: 'Approve right branch?'"))
        val git = "git branch: 'selected', url: 'git://127.0.0.1:9418/repository.git'"
        val leftGit = "$indent      $git"
        val rootGit = "$indent$git"
        require(text.lineSequence().filter { it.trimStart().startsWith("git branch:") }.toList() ==
            listOf(leftGit, "${indent}      git branch: 'alternate', url: 'git://127.0.0.1:9418/repository.git'", rootGit))
        require(text.indexOf("\n$rootGit\n") > text.indexOf(end))
        fun replace(source: String, from: String, to: String): String {
            require(from.isNotEmpty() && source.indexOf(from) >= 0 && source.indexOf(from) == source.lastIndexOf(from))
            return source.replace(from, to)
        }
        val dirStart = "$indent    dir('$left') {"
        val dirEnd = "\n$indent    }"
        val offset = text.indexOf(dirStart)
        val directory = text.substring(offset, text.indexOf(dirEnd, offset) + dirEnd.length)
        val gate = "$indent    input message: 'Approve left branch?'"
        require(directory.contains(leftGit) && offset > text.indexOf(gate))
        val earlyWork = replace(replace(text, directory + "\n", ""), gate, "$directory\n$gate")
        val earlySuccessor = replace(replace(text, "\n$rootGit\n", "\n"), block, "$rootGit\n$block")
        return linkedMapOf("baseline" to original).apply {
            if (reject) {
                put("suppressed-rejection", replace(text, block,
                    "${indent}try {\n$block\n$indent} catch (flowError) {\n${indent}  echo 'Rejection intentionally suppressed'\n$indent}").toByteArray())
            } else {
                put("aliased-workspaces", replace(text, right, left).toByteArray())
                put("swapped-workspaces", replace(replace(replace(text, left, "__workspace_swap__"), right, left), "__workspace_swap__", right).toByteArray())
                put("omitted-left-checkout", replace(text, leftGit, "${indent}      echo 'Branch checkout intentionally omitted'").toByteArray())
            }
            put("early-branch-work", earlyWork.toByteArray())
            put("early-successor", earlySuccessor.toByteArray())
        }
    }

    fun observation(node: JsonNode?, checkoutCount: Int): Map<String, Any?> {
        require(node?.isObject == true && node.fieldNames().asSequence().toSet() == setOf("leftMarker", "rightMarker", "checkouts"))
        val value = requireNotNull(node)
        fun optionalText(field: JsonNode, limit: Int): String? {
            require(field.isNull || field.isTextual && field.asText().length in 1..limit)
            return if (field.isNull) null else field.asText()
        }
        val rows = value["checkouts"]
        require(rows.isArray && rows.size() == checkoutCount && checkoutCount in 0..3)
        val checkouts = rows.map { row ->
            require(row.isObject && row.fieldNames().asSequence().toSet() == setOf("branch", "parallelBranch", "directory"))
            require(row["branch"].isTextual && row["branch"].asText().length in 1..256)
            linkedMapOf("branch" to row["branch"].asText(), "parallelBranch" to optionalText(row["parallelBranch"], 128),
                "directory" to optionalText(row["directory"], 256))
        }
        return linkedMapOf("leftMarker" to optionalText(value["leftMarker"], 1024),
            "rightMarker" to optionalText(value["rightMarker"], 1024), "checkouts" to checkouts)
    }
}
