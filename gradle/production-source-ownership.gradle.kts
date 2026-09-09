import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

buildscript {
    repositories { gradlePluginPortal(); mavenCentral() }
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10") }
}

/** Validate actual compiler inputs, not merely a hand-maintained file count. */
abstract class VerifyProductionSourceOwnership : DefaultTask() {
    @get:Input abstract val expectedByOwner: MapProperty<String, List<String>>
    @get:Input abstract val actualByOwner: MapProperty<String, List<String>>
    @get:InputFiles abstract val allKotlinSources: ConfigurableFileCollection
    @get:InputFiles abstract val unownedModuleSources: ConfigurableFileCollection
    @get:InputFiles abstract val javaSources: ConfigurableFileCollection

    @TaskAction fun verify() {
        fun identities(paths: List<String>) = paths.map { java.io.File(it).canonicalPath }.toSet()
        val expected = expectedByOwner.get().mapValues { identities(it.value) }
        val actual = actualByOwner.get().mapValues { identities(it.value) }
        check(actual.keys == expected.keys) { "Production compilation owners differ from the declared partition." }
        expected.forEach { (owner, files) ->
            check(actual.getValue(owner) == files) { "Production source ownership differs for $owner." }
        }
        val claimed = actual.values.flatten()
        check(claimed.size == claimed.toSet().size) { "A production source belongs to more than one compiler invocation." }
        val all = allKotlinSources.files.map { it.canonicalPath }.toSet()
        check(claimed.toSet() == all) { "Production ownership lost or invented Kotlin source files." }
        check(unownedModuleSources.isEmpty) { "Module source relocation must update the ownership contract; unowned sources are forbidden." }
        check(javaSources.isEmpty) { "Java production sources require an explicitly reviewed ownership contract." }
    }
}

val registeredManifests = linkedMapOf(
    ":flow-semantic-kernel" to "gradle/semantic-kernel-sources.txt",
    ":flow-module-contracts" to "gradle/module-contracts-sources.txt",
    ":flow-compiler" to "gradle/compiler-sources.txt",
    ":flow-frontends" to "gradle/frontends-sources.txt",
    ":flow-adapter-contracts" to "gradle/adapter-contracts-sources.txt"
)
require(subprojects.map { it.path }.all { it in registeredManifests }) {
    "Every production project must have an explicit source-ownership manifest."
}
val sourceRoot = file("src/main/kotlin").canonicalFile
val ownership = registeredManifests.filterKeys { findProject(it) != null }.mapValues { (_, manifest) ->
    val manifestFile = file(manifest)
    require(!java.nio.file.Files.isSymbolicLink(manifestFile.toPath())) { "Symbolic source manifests are forbidden." }
    val entries = manifestFile.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
    require(entries.isNotEmpty() && entries == entries.distinct().sorted()) {
        "Source ownership must be non-empty, unique and sorted: $manifest"
    }
    entries.forEach { path ->
        require(path.matches(Regex("org/flowlang/[A-Za-z0-9_/]+\\.kt"))) {
            "Ownership requires exact, relative Kotlin paths: $path"
        }
        val source = sourceRoot.resolve(path)
        require(source.isFile && source.canonicalFile.toPath().startsWith(sourceRoot.toPath()) &&
            !java.nio.file.Files.isSymbolicLink(source.toPath())) { "Missing, symbolic or escaping owned source: $path" }
    }
    entries
}
val claimed = ownership.values.flatten()
require(claimed == claimed.distinct()) { "Production ownership manifests overlap." }
extra["productionModuleSources"] = ownership
extra["semanticKernelSources"] = ownership.getValue(":flow-semantic-kernel")

val rootKotlin = extensions.getByType<KotlinJvmProjectExtension>()
rootKotlin.sourceSets.named("main") { kotlin.exclude(claimed) }
val nonKernelProductionSources = objects.fileCollection()
nonKernelProductionSources.from(files(rootKotlin.sourceSets.getByName("main").kotlin).asFileTree)
extra["nonKernelProductionSources"] = nonKernelProductionSources

val isolated = providers.gradleProperty("flow.isolatedBoundary").orNull
if (isolated != null) {
    val actual = fileTree(sourceRoot) { include("**/*.kt") }.files.map { it.relativeTo(sourceRoot).invariantSeparatorsPath }.toSet()
    require(actual == claimed.toSet()) { "An isolated build must physically omit every residual production source." }
    require(!file("src/test").exists() && !file("tests").exists()) { "An isolated build cannot borrow distribution integration tests." }
    registeredManifests.keys.filter { findProject(it) == null }.forEach { omitted ->
        require(!file(omitted.removePrefix(":") + "/src").exists()) { "An isolated build contains omitted module sources: $omitted" }
    }
}

val verifyProductionSourceOwnership = tasks.register<VerifyProductionSourceOwnership>("verifyProductionSourceOwnership") {
    group = "verification"
    description = "Require one exhaustive, disjoint owner for every actual production compiler input."
    expectedByOwner.set(ownership.mapValues { (_, paths) -> paths.map { sourceRoot.resolve(it).absolutePath } })
    val rootExpected = fileTree(sourceRoot) { include("**/*.kt"); exclude(claimed) }.files.map { it.absolutePath }.sorted()
    expectedByOwner.put(":", rootExpected)
    actualByOwner.put(":", files(rootKotlin.sourceSets.getByName("main").kotlin).asFileTree.elements.map { locations ->
        locations.map { it.asFile.absolutePath }.sorted()
    })
    allKotlinSources.from(fileTree(sourceRoot) { include("**/*.kt") })
    javaSources.from(files(project.extensions.getByType<org.gradle.api.tasks.SourceSetContainer>().getByName("main").java).asFileTree)
    outputs.upToDateWhen { false }
}
subprojects {
    val child = this
    plugins.withId("org.jetbrains.kotlin.jvm") {
        val childKotlin = child.extensions.getByType<KotlinJvmProjectExtension>()
        val actual = child.files(childKotlin.sourceSets.getByName("main").kotlin).asFileTree
        if (child.path != ":flow-semantic-kernel") nonKernelProductionSources.from(actual)
        verifyProductionSourceOwnership.configure {
            actualByOwner.put(child.path, actual.elements.map { locations -> locations.map { it.asFile.absolutePath }.sorted() })
            unownedModuleSources.from(child.fileTree("src/main") { include("**/*.kt") })
            javaSources.from(child.files(child.extensions.getByType<org.gradle.api.tasks.SourceSetContainer>().getByName("main").java).asFileTree)
        }
        child.tasks.named("compileKotlin") { dependsOn(verifyProductionSourceOwnership) }
    }
}
tasks.named("compileKotlin") { dependsOn(verifyProductionSourceOwnership) }
