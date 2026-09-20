plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "io.github.moronigranja.ayvu.featureocr"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            it.testLogging {
                events("passed", "failed", "skipped")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Tesseract4Android 4.9.0 (Tesseract 5.5.1 + Leptonica, arm64/x86 natives in
    // the AAR, JitPack distribution) — the maintained successor to the retired
    // pre-LSTM tess-two 9.1.0; the only module that sees the OCR binding
    // (decisions #186). Same package/API shape as tess-two.
    implementation(libs.tesseract4android)

    implementation(project(":core-ocr"))
    // TtsPack/PackCache types (the pack machinery lives in core-tts); the
    // jar JNA is excluded at the seam like every Android consumer.
    implementation(project(":core-tts")) { exclude(group = "net.java.dev.jna") }

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
