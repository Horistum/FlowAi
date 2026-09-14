package org.flowlang.distribution.reference.gitopts

import java.io.File

fun main(args: Array<String>) {
    require(args.size == 1) {
        "Usage: gitOptsPlan <intent.yaml>. Through Gradle: ./gradlew :flow-reference-distribution:gitOptsPlan -PgitOptsFile=<intent.yaml>"
    }
    val intent = GitOptsYaml.load(File(args.single()))
    val plan = GitOptsPlanner().plan(intent)
    print(GitOptsYaml.renderPlan(plan))
}
