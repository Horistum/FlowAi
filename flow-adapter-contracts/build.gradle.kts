plugins { kotlin("jvm"); `java-library` }

group = rootProject.group
version = rootProject.version

extra["allowedProductionProjects"] = listOf<String>()
extra["allowedProductionLibraries"] = listOf<String>()
apply(from = rootProject.file("gradle/production-module.gradle.kts"))

dependencies { testImplementation(kotlin("test")) }
