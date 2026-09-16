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
    // Archive/JSON codec — pure JVM, sandbox-testable (post-v1-plan Slice B).
    // Serialization follows the core-tts no-codegen pattern (JsonElement/JsonObject):
    // no serialization compiler plugin (post-v1-plan "Follow that pattern").
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
