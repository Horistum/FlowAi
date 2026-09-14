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
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    testImplementation(kotlin("test"))
}

val gitOptsFile = providers.gradleProperty("gitOptsFile")
tasks.register<JavaExec>("gitOptsPlan") {
    group = "application"
    description = "Plan a git-opts reference intent without executing Git or network side effects."
    dependsOn(tasks.named("classes"))
    mainClass.set("org.flowlang.distribution.reference.gitopts.GitOptsPlannerCliKt")
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir(rootProject.projectDir)
    doFirst {
        require(gitOptsFile.isPresent) {
            "Provide -PgitOptsFile=<intent.yaml>; for example reference/git-opts-planner/examples/change-readme.yaml"
        }
        args(gitOptsFile.get())
    }
}

tasks.test { workingDir(rootProject.projectDir) }
