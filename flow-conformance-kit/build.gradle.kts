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
tasks.test {
    workingDir(rootProject.projectDir)
    // Child-JVM regression probes need the actual test classpath, independent of Gradle's worker classloader implementation.
    doFirst { systemProperty("flow.conformance.test.classpath", classpath.asPath) }
}

// Opt-in external runtime proof. Root tests and product distributions never start Docker or Jenkins.
tasks.register<JavaExec>("verifyJenkinsCheckoutRuntime") {
    group = "verification"
    description = "Execute the adapter-owned checkout artifact and mutants on disposable real Jenkins."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.flowlang.conformance.JenkinsCheckoutRuntimeCertificationKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    args(rootProject.projectDir.absolutePath, layout.buildDirectory.dir("jenkins-runtime-certification").get().asFile.absolutePath)
    workingDir(rootProject.projectDir)
}

tasks.register<JavaExec>("verifyJenkinsSharedCheckoutRuntime") {
    group = "verification"
    description = "Execute the shared immutable checkout fixture and revision mutants on real Jenkins."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.flowlang.conformance.JenkinsCheckoutRuntimeCertificationKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    args(rootProject.projectDir.absolutePath, layout.buildDirectory.dir("jenkins-shared-checkout-certification").get().asFile.absolutePath,
        "jenkins-shared-checkout-runtime")
    workingDir(rootProject.projectDir)
}

tasks.register<JavaExec>("verifyJenkinsFailureRuntime") {
    group = "verification"
    description = "Verify native failure propagation and its omission/suppression mutants on real Jenkins."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.flowlang.conformance.JenkinsCheckoutRuntimeCertificationKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    args(rootProject.projectDir.absolutePath, layout.buildDirectory.dir("jenkins-failure-certification").get().asFile.absolutePath, "jenkins-failure-runtime")
    workingDir(rootProject.projectDir)
}

val conditionalRuntimeTasks = listOf("true", "false").map { value ->
    tasks.register<JavaExec>("verifyJenkinsCondition${value.replaceFirstChar(Char::uppercase)}Runtime") {
        group = "verification"
        description = "Verify generated boolean guards with default $value on real Jenkins."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("org.flowlang.conformance.JenkinsCheckoutRuntimeCertificationKt")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        args(rootProject.projectDir.absolutePath, layout.buildDirectory.dir("jenkins-condition-certification/$value").get().asFile.absolutePath,
            "jenkins-condition-$value-runtime")
        workingDir(rootProject.projectDir)
    }
}
tasks.register("verifyJenkinsConditionRuntime") {
    group = "verification"
    description = "Execute both boolean defaults and their flattened/inverted guard mutants on real Jenkins."
    dependsOn(conditionalRuntimeTasks)
}

val errorBoundaryRuntimeTasks = listOf("failure", "success").map { outcome ->
    tasks.register<JavaExec>("verifyJenkinsError${outcome.replaceFirstChar(Char::uppercase)}Runtime") {
        group = "verification"
        description = "Verify workflow error handling for a $outcome body on real Jenkins."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("org.flowlang.conformance.JenkinsCheckoutRuntimeCertificationKt")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        args(rootProject.projectDir.absolutePath, layout.buildDirectory.dir("jenkins-error-boundary-certification/$outcome").get().asFile.absolutePath,
            "jenkins-error-$outcome-runtime")
        workingDir(rootProject.projectDir)
    }
}
tasks.register("verifyJenkinsErrorBoundaryRuntime") {
    group = "verification"
    description = "Execute successful/failing workflow handlers and their omission/propagation mutants on real Jenkins."
    dependsOn(errorBoundaryRuntimeTasks)
}

val localRecoveryRuntimeTasks = listOf("failure", "success").map { outcome ->
    tasks.register<JavaExec>("verifyJenkinsRecovery${outcome.replaceFirstChar(Char::uppercase)}Runtime") {
        group = "verification"
        description = "Verify local recovery for a $outcome body on real Jenkins."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("org.flowlang.conformance.JenkinsCheckoutRuntimeCertificationKt")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        args(rootProject.projectDir.absolutePath, layout.buildDirectory.dir("jenkins-local-recovery-certification/$outcome").get().asFile.absolutePath,
            "jenkins-recovery-$outcome-runtime")
        workingDir(rootProject.projectDir)
    }
}
tasks.register("verifyJenkinsLocalRecoveryRuntime") {
    group = "verification"
    description = "Execute local recovery and its handler/continuation mutants on real Jenkins."
    dependsOn(localRecoveryRuntimeTasks)
}

val retryRuntimeTasks = listOf("failure", "success").map { outcome ->
    tasks.register<JavaExec>("verifyJenkinsRetry${outcome.replaceFirstChar(Char::uppercase)}Runtime") {
        group = "verification"
        description = "Verify bounded retry for a $outcome body on real Jenkins."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("org.flowlang.conformance.JenkinsCheckoutRuntimeCertificationKt")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        args(rootProject.projectDir.absolutePath, layout.buildDirectory.dir("jenkins-retry-certification/$outcome").get().asFile.absolutePath,
            "jenkins-retry-$outcome-runtime")
        workingDir(rootProject.projectDir)
    }
}
tasks.register("verifyJenkinsRetryRuntime") {
    group = "verification"
    description = "Execute bounded retry and its attempt-count mutants on real Jenkins."
    dependsOn(retryRuntimeTasks)
}

val approvalRuntimeTasks = listOf("approve", "reject").map { outcome ->
    tasks.register<JavaExec>("verifyJenkinsApproval${outcome.replaceFirstChar(Char::uppercase)}Runtime") {
        group = "verification"
        description = "Verify a manual input with a test $outcome decision on real Jenkins."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("org.flowlang.conformance.JenkinsCheckoutRuntimeCertificationKt")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        args(rootProject.projectDir.absolutePath, layout.buildDirectory.dir("jenkins-approval-certification/$outcome").get().asFile.absolutePath,
            "jenkins-approval-$outcome-runtime")
        workingDir(rootProject.projectDir)
    }
}
tasks.register("verifyJenkinsApprovalRuntime") {
    group = "verification"
    description = "Execute manual approval and its omission, ordering and rejection mutants on real Jenkins."
    dependsOn(approvalRuntimeTasks)
}

// Replays authenticated archives from the same CI run; never starts a target runtime.
tasks.register<JavaExec>("verifyAdapterCertificationPortfolio") {
    group = "verification"
    description = "Reauthenticate the complete scenario inventory and generate the reference adapter portfolio."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.flowlang.conformance.AdapterCertificationPortfolioCli")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    args(rootProject.projectDir.absolutePath,
        rootProject.layout.buildDirectory.dir("certification-inputs").get().asFile.absolutePath,
        layout.buildDirectory.dir("adapter-certification-portfolio").get().asFile.absolutePath,
        providers.environmentVariable("FLOW_CERTIFICATION_REVISION").getOrElse(""))
    workingDir(rootProject.projectDir)
}
