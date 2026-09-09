plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "org.flowlang"

// Published implementation package line. Historical and unreleased v0.9.5.x
// through v0.9.7.x correction/work-item identifiers evolve governance and
// standard evidence without creating additional published package versions.
version = "0.9.5"

application { mainClass.set("org.flowlang.cli.honest.HonestFlowCliKt") }

kotlin { jvmToolchain(25) }

// Every production file is owned by exactly one separately compiled project.
apply(from = "gradle/production-source-ownership.gradle.kts")

dependencies {
    // The residual CLI/conformance edge composes the reference distribution;
    // adding a project never grants it an implicit production dependency.
    listOf(
        ":flow-semantic-kernel", ":flow-module-contracts", ":flow-compiler",
        ":flow-frontends", ":flow-adapter-runtime", ":flow-reference-distribution"
    ).filter { findProject(it) != null }.forEach { implementation(project(it)) }
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-jsonSchema:2.17.2")
    testImplementation(kotlin("test"))
    if (findProject(":flow-frontends") != null) {
        testImplementation(testFixtures(project(":flow-compiler")))
        testImplementation(testFixtures(project(":flow-frontends")))
    }
    if (findProject(":flow-adapter-runtime") != null) {
        testImplementation(testFixtures(project(":flow-adapter-runtime")))
    }
}

sourceSets {
    test {
        kotlin.srcDirs("src/test/kotlin", "tests")
        resources.srcDirs("src/test/resources")
    }
}

tasks.test {
    useJUnitPlatform()
    // These tests inspect live repository metadata and source inventories that
    // are not represented by the test runtime classpath. Re-execute them even
    // when a lifecycle-only change leaves all compiled classes unchanged.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    testLogging { events("passed", "skipped", "failed") }
}

// The implementation is configured in the child after both Kotlin source sets
// exist. This alias preserves the root verification entry point without reading
// another Project or its extensions at task execution time.
val verifySemanticKernelSourceOwnership by tasks.registering {
    group = "verification"
    description = "Check the actual Gradle source sets form a complete, disjoint partition."
    dependsOn(":flow-semantic-kernel:verifySemanticKernelSourceOwnership")
}

tasks.named("compileKotlin") { dependsOn(verifySemanticKernelSourceOwnership) }
tasks.test { dependsOn(subprojects.map { "${it.path}:test" }) }
