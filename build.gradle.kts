plugins {
    kotlin("jvm") version "1.9.24"
    application
}

group = "org.flowlang"
version = "0.9.4"

application { mainClass.set("org.flowlang.cli.FlowCliKt") }

kotlin { jvmToolchain(21) }

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-jsonSchema:2.17.2")
    testImplementation(kotlin("test"))
}

sourceSets {
    test {
        kotlin.srcDirs("src/test/kotlin", "tests")
        resources.srcDirs("src/test/resources")
    }
}

val exportConformanceRunnerSource by tasks.registering {
    doLast {
        val source = file("src/main/kotlin/org/flowlang/conformance/ConformanceRunner.kt")
        val destination = layout.buildDirectory.file("reports/tests/test/conformance-runner-source.kt").get().asFile
        destination.parentFile.mkdirs()
        source.copyTo(destination, overwrite = true)
    }
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(exportConformanceRunnerSource)
    testLogging {
        events("passed", "skipped", "failed")
    }
}
