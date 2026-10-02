import java.security.MessageDigest

plugins { kotlin("jvm"); `java-library` }

group = rootProject.group
version = rootProject.version

extra["allowedProductionProjects"] = listOf(":flow-standard-artifacts", ":flow-semantic-kernel", ":flow-module-contracts", ":flow-compiler", ":flow-frontends", ":flow-adapter-contracts", ":flow-adapter-runtime", ":flow-adapter-evidence", ":flow-adapter-jenkins", ":flow-adapter-github-actions", ":flow-adapter-tekton")
extra["allowedProductionLibraries"] = listOf("com.fasterxml.jackson.core:jackson-annotations", "com.fasterxml.jackson.core:jackson-core", "com.fasterxml.jackson.core:jackson-databind", "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml", "com.fasterxml.jackson.module:jackson-module-kotlin", "org.yaml:snakeyaml", "org.jetbrains.kotlin:kotlin-reflect")
apply(from = rootProject.file("gradle/production-module.gradle.kts"))

dependencies {
    api(project(":flow-standard-artifacts"))
    api(project(":flow-adapter-runtime"))
    api(project(":flow-adapter-evidence"))
    api(project(":flow-adapter-jenkins"))
    api(project(":flow-adapter-github-actions"))
    api(project(":flow-adapter-tekton"))
    testImplementation(kotlin("test"))
}

tasks.test { workingDir(rootProject.projectDir) }

// Only explicitly inventoried contracts and their evidence witnesses enter the JAR.
// Witness Kotlin text is a resource, never a compilation input or verification dependency.
val contractInventory = rootProject.file("gradle/reference-contract-resources.txt")
val contractPaths = contractInventory.readLines().filter { it.isNotBlank() }
require(contractPaths.isNotEmpty() && contractPaths == contractPaths.distinct().sorted())
require(contractPaths.all { path ->
    path.matches(Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+")) && path.split('/').none { it == "." || it == ".." }
})
val contractSourceRoot = providers.gradleProperty("flow.contractSourceRoot")
    .map { rootProject.file(it) }.getOrElse(rootProject.projectDir)
val packagedContracts = tasks.register("packageContractResources") {
    inputs.file(contractInventory)
    inputs.files(contractPaths.map { contractSourceRoot.resolve(it) }).withPathSensitivity(PathSensitivity.RELATIVE)
    val destination = layout.buildDirectory.dir("generated/contract-resources")
    outputs.dir(destination)
    doLast {
        val output = destination.get().asFile
        output.deleteRecursively()
        val prefix = output.resolve("flow/reference-contracts")
        prefix.mkdirs()
        val index = contractPaths.map { path ->
            val source = contractSourceRoot.resolve(path)
            require(source.isFile && source.canonicalFile.toPath().startsWith(contractSourceRoot.canonicalFile.toPath())) {
                "Missing or escaping contract resource: $path"
            }
            val bytes = source.readBytes()
            prefix.resolve(path).apply { parentFile.mkdirs(); writeBytes(bytes) }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            "$hash\t$path"
        }
        prefix.resolve("index.tsv").writeText(index.joinToString("\n", postfix = "\n"))
    }
}
sourceSets.main { resources.srcDir(packagedContracts) }
