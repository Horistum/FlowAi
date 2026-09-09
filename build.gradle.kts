import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    kotlin("jvm") version "2.4.10"
    distribution
}

group = "org.flowlang"

// Published package line, not a delivery-milestone identifier.
version = "0.9.5"
kotlin { jvmToolchain(25) }

// The root owns no source or product dependency. Every JVM implementation has
// one separately compiled owner; verification is a separate application profile.
apply(from = "gradle/production-source-ownership.gradle.kts")
sourceSets.test {
    kotlin.setSrcDirs(emptyList<String>())
    resources.setSrcDirs(emptyList<String>())
}
sourceSets.main { resources.setSrcDirs(emptyList<String>()) }
tasks.jar { enabled = false }

fun org.gradle.api.artifacts.Configuration.applicationRuntime() {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
val verificationRuntime by configurations.creating { applicationRuntime() }
val productRuntime by configurations.creating { applicationRuntime() }
dependencies {
    if (findProject(":flow-conformance-kit") != null) {
        verificationRuntime(project(":flow-conformance-kit"))
    }
    if (findProject(":flow-cli") != null) {
        productRuntime(project(":flow-cli"))
    }
}

// Historical developer command compatibility. This is a verification host,
// not the production CLI's compile/runtime classpath. The independent product
// application is :flow-cli:runProduct / :flow-cli:installDist.
val run by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run the reference verification host (including product commands)."
    mainClass.set("org.flowlang.verification.VerificationCliKt")
    classpath = verificationRuntime
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
val startScripts by tasks.registering(CreateStartScripts::class) {
    applicationName = "flow-core"
    mainClass.set("org.flowlang.verification.VerificationCliKt")
    classpath = verificationRuntime
    outputDir = layout.buildDirectory.dir("scripts/reference").get().asFile
}
val productStartScripts by tasks.registering(CreateStartScripts::class) {
    applicationName = "flow-product"
    mainClass.set("org.flowlang.cli.honest.HonestFlowCliKt")
    classpath = productRuntime
    outputDir = layout.buildDirectory.dir("scripts/product").get().asFile
}
distributions.main {
    contents {
        from(startScripts) { into("bin"); filePermissions { unix("rwxr-xr-x") } }
        from(productStartScripts) { into("bin"); filePermissions { unix("rwxr-xr-x") } }
        from(verificationRuntime) { into("lib") }
    }
}

// Verification aggregation never turns test fixtures into production inputs.
val verifySemanticKernelSourceOwnership by tasks.registering {
    group = "verification"
    description = "Check the actual Gradle source sets form a complete, disjoint partition."
    dependsOn(":flow-semantic-kernel:verifySemanticKernelSourceOwnership")
}
tasks.named("compileKotlin") { dependsOn(verifySemanticKernelSourceOwnership) }
tasks.test { dependsOn(subprojects.map { "${it.path}:test" }) }
