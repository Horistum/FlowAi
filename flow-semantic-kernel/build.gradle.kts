import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    kotlin("jvm")
    `java-library`
}

group = rootProject.group
version = rootProject.version

// One manifest owns the partition. Keeping source paths stable does not share a
// compiler invocation: this project has its own output, classpath and Kotlin module.
val semanticKernelSources: List<String> by rootProject.extra
kotlin {
    jvmToolchain(25)
    sourceSets.named("main") {
        kotlin.setSrcDirs(listOf(rootProject.file("src/main/kotlin")))
        kotlin.include(semanticKernelSources)
    }
}

// The external compiler is a test tool, never a kernel production dependency.
val compilerProbeRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    testImplementation(kotlin("test"))
    compilerProbeRuntime(kotlin("compiler-embeddable"))
}

val productionClasspaths = listOf(configurations.named("compileClasspath"), configurations.named("runtimeClasspath"))
val verifySemanticKernelClasspath by tasks.registering {
    group = "verification"
    description = "Reject product projects, local files and non-stdlib kernel dependencies."
    inputs.files(productionClasspaths)
    doLast {
        val allowed = setOf(
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
            "org.jetbrains:annotations"
        )
        val coordinates = productionClasspaths.flatMap { configuration ->
            val artifacts = configuration.get().incoming.artifacts.artifacts
            check(artifacts.isNotEmpty()) { "Kernel must resolve its actual standard library." }
            artifacts.map { artifact ->
                val id = artifact.id.componentIdentifier
                check(id is ModuleComponentIdentifier && "${id.group}:${id.module}" in allowed) {
                    "Forbidden semantic-kernel ${configuration.name} dependency: ${id.displayName}"
                }
                "${configuration.name} ${id.group}:${id.module}:${id.version}"
            }
        }.sorted()
        val report = layout.buildDirectory.file("reports/semantic-kernel-boundary/classpath.txt").get().asFile
        report.parentFile.mkdirs()
        report.writeText(coordinates.joinToString("\n", postfix = "\n"))
    }
}

tasks.named("compileKotlin") {
    dependsOn(verifySemanticKernelClasspath, rootProject.tasks.named("verifySemanticKernelSourceOwnership"))
}

tasks.test {
    useJUnitPlatform()
    inputs.files(compilerProbeRuntime)
    doFirst {
        systemProperty("flow.kernel.classpath", sourceSets.main.get().runtimeClasspath.asPath)
        systemProperty("flow.kernel.compilerClasspath", compilerProbeRuntime.asPath)
    }
    testLogging { events("passed", "skipped", "failed") }
}
