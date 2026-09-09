plugins { kotlin("jvm"); `java-library`; distribution }

group = rootProject.group
version = rootProject.version
// These are verification dependencies. No product project may include this kit
// on either production classpath, including through a transitive dependency.
extra["allowedProductionProjects"] = listOf(":flow-semantic-kernel", ":flow-module-contracts", ":flow-compiler", ":flow-frontends", ":flow-adapter-contracts", ":flow-adapter-runtime", ":flow-adapter-evidence", ":flow-adapter-jenkins", ":flow-adapter-github-actions", ":flow-adapter-tekton", ":flow-standard-artifacts", ":flow-reference-distribution", ":flow-cli")
extra["allowedProductionLibraries"] = listOf("com.fasterxml.jackson.core:jackson-annotations", "com.fasterxml.jackson.core:jackson-core", "com.fasterxml.jackson.core:jackson-databind", "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml", "com.fasterxml.jackson.module:jackson-module-kotlin", "org.yaml:snakeyaml", "org.jetbrains.kotlin:kotlin-reflect", "com.fasterxml.jackson.module:jackson-module-jsonSchema", "javax.validation:validation-api")
apply(from = rootProject.file("gradle/production-module.gradle.kts"))

extra["cliApplicationName"] = "flow-conformance"
extra["cliMainClass"] = "org.flowlang.verification.VerificationCliKt"
extra["cliRunTaskName"] = "runVerification"
apply(from = rootProject.file("gradle/cli-application.gradle.kts"))
dependencies {
    api(project(":flow-cli"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-jsonSchema:2.17.2")
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":flow-compiler")))
    testImplementation(testFixtures(project(":flow-frontends")))
    testImplementation(testFixtures(project(":flow-adapter-runtime")))
    testImplementation(testFixtures(project(":flow-adapter-evidence")))
}

// Preserve all historical integration and white-box test identities without
// widening production visibility or granting friend paths across modules.
kotlin.sourceSets.test {
    kotlin.srcDirs(rootProject.file("src/test/kotlin"), rootProject.file("tests"))
}
sourceSets.test { resources.srcDir(rootProject.file("src/test/resources")) }
sourceSets.main {
    resources.setSrcDirs(listOf(rootProject.file("src/main/resources")))
    resources.exclude("standard/compatibility/capability-aliases.yaml")
}
tasks.test { workingDir(rootProject.projectDir) }
