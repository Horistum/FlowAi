plugins { kotlin("jvm"); `java-library`; distribution }

group = rootProject.group
version = rootProject.version
extra["allowedProductionProjects"] = listOf(":flow-semantic-kernel", ":flow-module-contracts", ":flow-compiler", ":flow-frontends", ":flow-adapter-contracts", ":flow-adapter-runtime", ":flow-adapter-evidence", ":flow-adapter-jenkins", ":flow-adapter-github-actions", ":flow-adapter-tekton", ":flow-standard-artifacts", ":flow-reference-distribution")
extra["allowedProductionLibraries"] = listOf("com.fasterxml.jackson.core:jackson-annotations", "com.fasterxml.jackson.core:jackson-core", "com.fasterxml.jackson.core:jackson-databind", "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml", "com.fasterxml.jackson.module:jackson-module-kotlin", "org.yaml:snakeyaml", "org.jetbrains.kotlin:kotlin-reflect")
apply(from = rootProject.file("gradle/production-module.gradle.kts"))

extra["cliApplicationName"] = "flow-core"
extra["cliMainClass"] = "org.flowlang.cli.honest.HonestFlowCliKt"
extra["cliRunTaskName"] = "runProduct"
apply(from = rootProject.file("gradle/cli-application.gradle.kts"))
dependencies {
    api(project(":flow-standard-artifacts"))
    api(project(":flow-reference-distribution"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    testImplementation(kotlin("test"))
}

tasks.test { workingDir(rootProject.projectDir) }
