plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

group = "io.github.moronigranja.ayvu"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core-model")) // parsers return Book
    implementation(project(":core-locate")) // BookImporter indexes into TextIndex
    // F1: importAll cooperates with cancellation at file boundaries (yield/ensureActive).
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    // Fixture helpers read "core-ebook/src/test/resources/..." (repo-root-relative).
    useJUnitPlatform()
    workingDir = rootProject.projectDir
    testLogging {
        events("passed", "failed", "skipped")
    }
}