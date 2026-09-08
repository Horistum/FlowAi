import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm")
    `java-library`
}

// Task inputs contain files and serializable coordinates, never live Project,
// Configuration, SourceSet or resolved-artifact model objects. The same guards
// execute after both configuration-cache storage and reuse.
abstract class VerifyKernelSourceOwnership : DefaultTask() {
    @get:InputFiles abstract val expectedSources: ConfigurableFileCollection
    @get:InputFiles abstract val kernelSources: ConfigurableFileCollection
    @get:InputFiles abstract val productSources: ConfigurableFileCollection
    @get:InputFiles abstract val allProductionSources: ConfigurableFileCollection
    @get:InputFiles abstract val unownedKernelSources: ConfigurableFileCollection
    @get:InputFiles abstract val kernelJavaSources: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val expected = expectedSources.files.map { it.canonicalFile }.toSet()
        val observedKernel = kernelSources.files.map { it.canonicalFile }.toSet()
        val observedRoot = productSources.files.map { it.canonicalFile }.toSet()
        val allSources = allProductionSources.files.map { it.canonicalFile }.toSet()
        check(observedKernel == expected) { "Kernel compilation differs from its source-ownership manifest." }
        check(observedKernel.intersect(observedRoot).isEmpty()) { "Kernel sources are compiled twice." }
        check(observedKernel + observedRoot == allSources) { "The source partition lost or invented production files." }
        check(unownedKernelSources.isEmpty) {
            "Kernel source relocation must update the explicit ownership partition, not introduce ignored sources."
        }
        check(kernelJavaSources.isEmpty) { "This kernel boundary owns Kotlin sources only; Java additions require review." }
    }
}

abstract class VerifyKernelClasspath : DefaultTask() {
    @get:Classpath abstract val productionFiles: ConfigurableFileCollection
    @get:Input abstract val compileCoordinates: ListProperty<String>
    @get:Input abstract val runtimeCoordinates: ListProperty<String>
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val allowed = setOf(
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
            "org.jetbrains:annotations"
        )
        val coordinates = listOf(
            "compileClasspath" to compileCoordinates.get(),
            "runtimeClasspath" to runtimeCoordinates.get()
        ).flatMap { (name, components) ->
            check(components.isNotEmpty()) { "Kernel must resolve its actual standard library." }
            components.map { component ->
                check(!component.startsWith("!") && component.substringBeforeLast(':') in allowed) {
                    "Forbidden semantic-kernel $name dependency: $component"
                }
                "$name $component"
            }
        }.sorted()
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(coordinates.joinToString("\n", postfix = "\n"))
        }
    }
}

abstract class KernelCompilerArguments : CommandLineArgumentProvider {
    @get:Classpath abstract val kernelClasspath: ConfigurableFileCollection
    @get:Classpath abstract val compilerClasspath: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> = listOf(
        "-Dflow.kernel.classpath=${kernelClasspath.asPath}",
        "-Dflow.kernel.compilerClasspath=${compilerClasspath.asPath}"
    )
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

val verifySemanticKernelSourceOwnership by tasks.registering(VerifyKernelSourceOwnership::class) {
    group = "verification"
    description = "Verify the complete, disjoint source partition using declared file inputs."
    expectedSources.from(semanticKernelSources.map { rootProject.file("src/main/kotlin/$it") })
    kernelSources.from(files(kotlin.sourceSets.getByName("main").kotlin).asFileTree)
    productSources.from(rootProject.extra["nonKernelProductionSources"])
    allProductionSources.from(rootProject.fileTree("src/main/kotlin") { include("**/*.kt") })
    unownedKernelSources.from(fileTree("src/main") { include("**/*.kt") })
    kernelJavaSources.from(files(sourceSets.main.get().java).asFileTree)
}

val verifySemanticKernelClasspath by tasks.registering(VerifyKernelClasspath::class) {
    group = "verification"
    description = "Reject product projects, local files and non-stdlib kernel dependencies."
    listOf("compileClasspath" to compileCoordinates, "runtimeClasspath" to runtimeCoordinates).forEach { (name, target) ->
        val artifacts = configurations.getByName(name).incoming.artifacts
        productionFiles.from(artifacts.artifactFiles)
        target.set(artifacts.resolvedArtifacts.map { resolved ->
            resolved.map { artifact ->
                when (val id = artifact.id.componentIdentifier) {
                    is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                    else -> "!${id.displayName}"
                }
            }.sorted()
        })
    }
    report.set(layout.buildDirectory.file("reports/semantic-kernel-boundary/classpath.txt"))
    outputs.upToDateWhen { false }
}

tasks.named("compileKotlin") {
    dependsOn(verifySemanticKernelClasspath, verifySemanticKernelSourceOwnership)
}

tasks.test {
    useJUnitPlatform()
    // Cache compilation, not evidence: execute compiler probes on every revision.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    val compilerArguments = objects.newInstance(KernelCompilerArguments::class.java)
    compilerArguments.kernelClasspath.from(sourceSets.main.get().runtimeClasspath)
    compilerArguments.compilerClasspath.from(compilerProbeRuntime)
    jvmArgumentProviders.add(compilerArguments)
    testLogging { events("passed", "skipped", "failed") }
}
