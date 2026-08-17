plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "org.flowlang"

// Published implementation package line. Historical and unreleased v0.9.5.x
// through v0.9.7.x correction/work-item identifiers evolve governance and
// standard evidence without creating additional published package versions.
version = "0.9.5"

application { mainClass.set("org.flowlang.cli.honest.HonestFlowCliKt") }

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

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
