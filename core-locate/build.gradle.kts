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
    api(project(":core-model")) // public API exposes Book/MatchResult locations
    implementation(libs.kotlinx.coroutines.core) // IndexRebuilder readiness gate (S2)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
