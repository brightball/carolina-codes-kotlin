plugins {
    kotlin("jvm") version "2.2.20"
    application
    id("dev.detekt") version "2.0.0-alpha.6"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "carolina"
version = "0.2.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.3.1"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("org.postgresql:postgresql:42.7.12")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.9")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}

application {
    mainClass.set("carolina.MainKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Lock the resolved application/test graphs so OSV Scanner has a committed input.
listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name) {
        resolutionStrategy.activateDependencyLocking()
    }
}

detekt {
    toolVersion = "2.0.0-alpha.6"
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
    ignoreFailures = false
    config.setFrom(files("config/detekt/detekt.yml"))
    source.setFrom("src/main/kotlin", "src/test/kotlin")
}

ktlint {
    version.set("1.7.1")
    ignoreFailures.set(false)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "carolina.MainKt"
    }
}

tasks.named<JavaExec>("run") {
    environment("PORT", System.getenv("PORT") ?: "4013")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped", "standardOut", "standardError")
        showStandardStreams = true
    }
}

tasks.register("resolveAndLockAll") {
    group = "verification"
    description = "Resolve locked configurations so --write-locks can persist gradle.lockfile"
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "Run with --write-locks, e.g. ./gradlew resolveAndLockAll --write-locks"
        }
    }
    doLast {
        listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath").forEach { name ->
            configurations.named(name).get().resolve()
        }
    }
}

tasks.register<Exec>("osvScan") {
    group = "verification"
    description = "Scan locked Gradle dependencies with OSV Scanner (fails on known vulns)"
    val lockfile = layout.projectDirectory.file("gradle.lockfile")
    inputs.file(lockfile)
    workingDir = layout.projectDirectory.asFile
    commandLine("osv-scanner", "scan", "source", "--lockfile", lockfile.asFile.absolutePath)
}
