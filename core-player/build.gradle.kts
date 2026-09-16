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
    implementation(libs.kotlinx.coroutines.core) // StateFlow surface, suspend store ops (Room impl in core-persistence)
    implementation(project(":core-model")) // Book/Chapter layout + LibraryEntry
    // Engine contract types only — core-tts's jar JNA has no Android natives;
    // the app supplies the AAR (decisions #25/#32, same seam as feature-player).
    implementation(project(":core-tts")) { exclude(group = "net.java.dev.jna") }
    // @IoDispatcher qualifier (A6) — annotation-only, no other DI machinery.
    implementation("javax.inject:javax.inject:1")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
