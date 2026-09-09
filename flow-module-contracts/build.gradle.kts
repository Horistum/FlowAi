plugins { kotlin("jvm"); `java-library` }

group = rootProject.group
version = rootProject.version

extra["allowedProductionProjects"] = listOf(":flow-semantic-kernel")
extra["allowedProductionLibraries"] = listOf<String>()
apply(from = rootProject.file("gradle/production-module.gradle.kts"))

dependencies {
    api(project(":flow-semantic-kernel"))
    testImplementation(kotlin("test"))
}
