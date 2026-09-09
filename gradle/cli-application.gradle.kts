import org.gradle.api.distribution.DistributionContainer
import org.gradle.jvm.application.tasks.CreateStartScripts
import org.gradle.jvm.toolchain.JavaToolchainService

// Explicit task names avoid running several applications when a developer uses
// the historical root `run --args=conformance` task selector.
val cliApplicationName: String by extra
val cliMainClass: String by extra
val cliRunTaskName: String by extra
val applicationSources = extensions.getByType<org.gradle.api.tasks.SourceSetContainer>().getByName("main")
val toolchains = extensions.getByType<JavaToolchainService>()
val applicationRun = tasks.register<JavaExec>(cliRunTaskName) {
    group = "application"
    mainClass.set(cliMainClass)
    classpath = applicationSources.runtimeClasspath
    workingDir(rootProject.projectDir)
    javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
val applicationScripts = tasks.register<CreateStartScripts>("startScripts") {
    applicationName = cliApplicationName
    mainClass.set(cliMainClass)
    classpath = files(tasks.named("jar"), configurations.named("runtimeClasspath"))
    outputDir = layout.buildDirectory.dir("scripts").get().asFile
}
extensions.configure<DistributionContainer> {
    named("main") {
        distributionBaseName.set(cliApplicationName)
        contents {
            from(applicationScripts) { into("bin"); filePermissions { unix("rwxr-xr-x") } }
            into("lib") { from(tasks.named("jar"), configurations.named("runtimeClasspath")) }
        }
    }
}
