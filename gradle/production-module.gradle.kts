import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

buildscript {
    repositories { gradlePluginPortal(); mavenCentral() }
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10") }
}

/** Local files and composite-build substitutes cannot masquerade as allowed projects. */
abstract class VerifyProductionModuleClasspath : DefaultTask() {
    @get:Classpath abstract val productionFiles: ConfigurableFileCollection
    @get:Input abstract val allowedProjects: ListProperty<String>
    @get:Input abstract val allowedLibraries: ListProperty<String>
    @get:Input abstract val compileCoordinates: ListProperty<String>
    @get:Input abstract val runtimeCoordinates: ListProperty<String>
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction fun verify() {
        val allowed = allowedProjects.get().map { "project:$it" }.toSet() + allowedLibraries.get()
        val lines = listOf("compileClasspath" to compileCoordinates.get(), "runtimeClasspath" to runtimeCoordinates.get())
            .flatMap { (configuration, coordinates) ->
                check(coordinates.isNotEmpty()) { "A production module must resolve its standard library." }
                coordinates.map { coordinate ->
                    val identity = if (coordinate.startsWith("project:")) coordinate else coordinate.substringBeforeLast(':')
                    check(identity in allowed) { "Forbidden $configuration dependency: $coordinate" }
                    "$configuration $coordinate"
                }
            }.sorted()
        report.get().asFile.apply { parentFile.mkdirs(); writeText(lines.joinToString("\n", postfix = "\n")) }
    }
}

abstract class ProductionCompilerProbeArguments : CommandLineArgumentProvider {
    @get:Classpath abstract val productionClasspath: ConfigurableFileCollection
    @get:Classpath abstract val compilerClasspath: ConfigurableFileCollection
    override fun asArguments(): Iterable<String> = listOf(
        "-Dflow.module.classpath=${productionClasspath.asPath}",
        "-Dflow.module.compilerClasspath=${compilerClasspath.asPath}"
    )
}

val productionModuleSources: Map<String, List<String>> by rootProject.extra
val owned = productionModuleSources.getValue(project.path)
val kotlinExtension = extensions.getByType<KotlinJvmProjectExtension>()
kotlinExtension.jvmToolchain(25)
kotlinExtension.sourceSets.named("main") {
    kotlin.setSrcDirs(listOf(rootProject.file("src/main/kotlin")))
    kotlin.include(owned)
}
kotlinExtension.sourceSets.named("test") {
    kotlin.srcDir(rootProject.file("test-support/kotlin"))
}
val compilerProbeRuntime = configurations.create("compilerProbeRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies.add("compilerProbeRuntime", "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")

val allowedProductionProjects: List<String> by extra
val allowedProductionLibraries: List<String> by extra
require(project.path == ":flow-conformance-kit" ||
    ":flow-conformance-kit" !in allowedProductionProjects &&
    "org.flowlang:flow-conformance-kit" !in allowedProductionLibraries) {
    "A product module cannot authorize a dependency on the conformance implementation."
}
val verifyProductionClasspath = tasks.register<VerifyProductionModuleClasspath>("verifyProductionClasspath") {
    group = "verification"
    allowedProjects.set(allowedProductionProjects)
    allowedLibraries.set(listOf("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains.kotlin:kotlin-stdlib-jdk7",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8", "org.jetbrains:annotations") + allowedProductionLibraries)
    listOf("compileClasspath" to compileCoordinates, "runtimeClasspath" to runtimeCoordinates).forEach { (name, target) ->
        val artifacts = configurations.getByName(name).incoming.artifacts
        productionFiles.from(artifacts.artifactFiles)
        target.set(artifacts.resolvedArtifacts.map { resolved ->
            resolved.map { artifact ->
                check(artifact.variant.capabilities.none { it.name.endsWith("-test-fixtures") }) {
                    "Test fixtures must never be on a production classpath."
                }
                when (val id = artifact.id.componentIdentifier) {
                    is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                    is ProjectComponentIdentifier -> if (id.buildTreePath == id.projectPath) "project:${id.projectPath}" else "!included:${id.displayName}"
                    else -> "!${id.displayName}"
                }
            }.sorted()
        })
    }
    report.set(layout.buildDirectory.file("reports/production-module-boundary/classpath.txt"))
    outputs.upToDateWhen { false }
}
tasks.named("compileKotlin") { dependsOn(verifyProductionClasspath) }
val mainSourceSet = extensions.getByType<org.gradle.api.tasks.SourceSetContainer>().getByName("main")
tasks.named<Test>("test") {
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    val probeArguments = objects.newInstance(ProductionCompilerProbeArguments::class.java)
    probeArguments.productionClasspath.from(mainSourceSet.runtimeClasspath)
    probeArguments.compilerClasspath.from(compilerProbeRuntime)
    jvmArgumentProviders.add(probeArguments)
    testLogging { events("passed", "skipped", "failed") }
}
