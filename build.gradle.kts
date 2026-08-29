plugins {
    kotlin("jvm") version "2.2.20"
    application
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
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")
    implementation("org.slf4j:slf4j-simple:2.0.17")
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

tasks.jar {
    manifest {
        attributes["Main-Class"] = "carolina.MainKt"
    }
}

tasks.named<JavaExec>("run") {
    environment("PORT", System.getenv("PORT") ?: "4013")
}
