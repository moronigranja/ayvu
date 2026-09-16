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
    implementation(libs.kotlinx.coroutines.core) // suspend translate contract
    // Engine contract types for the TranslatingEngine decorator + TtsPack
    // descriptors. The JNA jar is excluded on the Android seam exactly as in
    // core-player/feature-player (the app ships the onnxruntime-android AAR,
    // decisions #25/#32).
    api(project(":core-tts")) { exclude(group = "net.java.dev.jna") }
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}