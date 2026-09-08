plugins { kotlin("jvm"); `java-library`; `java-test-fixtures` }

group = rootProject.group
version = rootProject.version

extra["allowedProductionProjects"] = listOf(":flow-semantic-kernel", ":flow-module-contracts")
extra["allowedProductionLibraries"] = listOf<String>()
apply(from = rootProject.file("gradle/production-module.gradle.kts"))

dependencies {
    api(project(":flow-semantic-kernel"))
    api(project(":flow-module-contracts"))
    testImplementation(kotlin("test"))
}
