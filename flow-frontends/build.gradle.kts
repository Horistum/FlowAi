plugins { kotlin("jvm"); `java-library`; `java-test-fixtures` }

group = rootProject.group
version = rootProject.version

extra["allowedProductionProjects"] = listOf(":flow-semantic-kernel", ":flow-module-contracts", ":flow-compiler")
extra["allowedProductionLibraries"] = listOf<String>("com.fasterxml.jackson.core:jackson-annotations", "com.fasterxml.jackson.core:jackson-core", "com.fasterxml.jackson.core:jackson-databind", "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml", "com.fasterxml.jackson.module:jackson-module-kotlin", "org.yaml:snakeyaml", "org.jetbrains.kotlin:kotlin-reflect")
apply(from = rootProject.file("gradle/production-module.gradle.kts"))

dependencies {
    api(project(":flow-semantic-kernel"))
    api(project(":flow-module-contracts"))
    api(project(":flow-compiler"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    testImplementation(kotlin("test"))
}

// Original file-capture regressions inspect the distribution's modules and examples.
tasks.test { workingDir(rootProject.projectDir) }
