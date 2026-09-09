plugins {
    kotlin("jvm") version "2.4.10"
    `java-library`
}

group = "org.flowlang"
version = rootProject.version
repositories { mavenCentral() }

val allowedProductionProjects by extra(listOf(
    ":flow-semantic-kernel",
    ":flow-module-contracts",
    ":flow-compiler",
    ":flow-frontends",
    ":flow-adapter-contracts"
))
val allowedProductionLibraries by extra(listOf<String>())
apply(from = rootProject.file("gradle/production-module.gradle.kts"))

dependencies {
    api(project(":flow-semantic-kernel"))
    api(project(":flow-module-contracts"))
    api(project(":flow-compiler"))
    api(project(":flow-frontends"))
    api(project(":flow-adapter-contracts"))
    testImplementation(kotlin("test"))
}
