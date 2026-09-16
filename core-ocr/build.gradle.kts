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
    implementation(libs.kotlinx.coroutines.core)
    // TtsPack/EngineSpec/PackRegistry and the pack machinery live in core-tts
    // (the repo's asset home); like core-player, the jar JNA is excluded at
    // the seam — Android consumers bring the AAR (decisions #25/#32).
    implementation(project(":core-tts")) { exclude(group = "net.java.dev.jna") }
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
