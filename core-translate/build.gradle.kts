plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

group = "com.moronigranja.localttsreader"
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
    implementation(libs.kotlinx.coroutines.core) // suspend translate contract, idle-close timer
    implementation(libs.kotlinx.serialization.json) // HF vocab.json piece->id table (dynamic JSON, no codegen)
    // Engine contract types for the TranslatingEngine decorator + TtsPack
    // descriptors. The JNA jar is excluded on the Android seam exactly as in
    // core-player/feature-player (the app ships the onnxruntime-android AAR,
    // decisions #25/#32).
    api(project(":core-tts")) { exclude(group = "net.java.dev.jna") }
    // ONNX Runtime is platform-specific: the JVM jar for host tests, the
    // onnxruntime-android AAR inside the app. core-translate compiles against
    // the Java API only and never ships a native runtime.
    compileOnly(libs.onnxruntime.jvm)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.onnxruntime.jvm) // real inference in translator tests
    testImplementation(libs.kotlinx.serialization.json) // golden-id fixture (dynamic JSON, no codegen)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}