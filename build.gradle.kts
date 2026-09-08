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

// Gradle projects consume one explicit, validated source partition. No glob may
// widen the kernel to include residual implementation files.
val kernelManifest = layout.projectDirectory.file("gradle/semantic-kernel-sources.txt")
val semanticKernelSources = providers.fileContents(kernelManifest).asText.get()
    .lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
require(semanticKernelSources.isNotEmpty() && semanticKernelSources == semanticKernelSources.distinct().sorted()) {
    "Semantic-kernel source ownership must be non-empty, unique and sorted."
}
semanticKernelSources.forEach { path ->
    require(path.matches(Regex("[A-Za-z0-9_/]+\\.kt")) && path.startsWith("org/flowlang/")) {
        "Semantic-kernel sources must be explicit relative Kotlin paths: $path"
    }
    val sourceRoot = file("src/main/kotlin").canonicalFile.toPath()
    val source = file("src/main/kotlin/$path")
    require(source.isFile && source.canonicalFile.toPath().startsWith(sourceRoot)) {
        "Semantic-kernel source is missing or escapes the source root: $path"
    }
}
extra["semanticKernelSources"] = semanticKernelSources

dependencies {
    implementation(project(":flow-semantic-kernel"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-jsonSchema:2.17.2")
    testImplementation(kotlin("test"))
}


sourceSets {
    main { kotlin.exclude(semanticKernelSources) }
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
    testLogging {
        events("passed", "skipped", "failed")
    }
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
tasks.test { dependsOn(":flow-semantic-kernel:test") }
