plugins { kotlin("jvm"); `java-library` }

group = rootProject.group
version = rootProject.version

extra["allowedProductionProjects"] = listOf(":flow-standard-artifacts", ":flow-semantic-kernel", ":flow-module-contracts", ":flow-compiler", ":flow-frontends", ":flow-adapter-contracts", ":flow-adapter-runtime", ":flow-adapter-evidence", ":flow-adapter-jenkins", ":flow-adapter-github-actions", ":flow-adapter-tekton")
extra["allowedProductionLibraries"] = listOf("com.fasterxml.jackson.core:jackson-annotations", "com.fasterxml.jackson.core:jackson-core", "com.fasterxml.jackson.core:jackson-databind", "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml", "com.fasterxml.jackson.module:jackson-module-kotlin", "org.yaml:snakeyaml", "org.jetbrains.kotlin:kotlin-reflect")
apply(from = rootProject.file("gradle/production-module.gradle.kts"))

dependencies {
    api(project(":flow-standard-artifacts"))
    api(project(":flow-adapter-runtime"))
    api(project(":flow-adapter-evidence"))
    api(project(":flow-adapter-jenkins"))
    api(project(":flow-adapter-github-actions"))
    api(project(":flow-adapter-tekton"))
    testImplementation(kotlin("test"))
}

tasks.test { workingDir(rootProject.projectDir) }
