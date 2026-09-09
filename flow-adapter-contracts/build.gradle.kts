plugins {
    kotlin("jvm") version "2.4.10"
    `java-library`
}

group = "org.flowlang"
version = rootProject.version

repositories { mavenCentral() }

val allowedProductionProjects by extra(listOf<String>())
val allowedProductionLibraries by extra(listOf<String>())
apply(from = rootProject.file("gradle/production-module.gradle.kts"))

dependencies {
    testImplementation(kotlin("test"))
}
