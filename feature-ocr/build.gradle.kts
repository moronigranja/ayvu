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
    // tess-two 9.1.0: Tesseract 4.0 (LSTM) + Leptonica for Android, ships
    // arm64/x86 natives in the AAR — the only module that sees tess-two.
    implementation("com.rmtheis:tess-two:9.1.0")

    implementation(project(":core-ocr"))
    // TtsPack/PackCache types (the pack machinery lives in core-tts); the
    // jar JNA is excluded at the seam like every Android consumer.
    implementation(project(":core-tts")) { exclude(group = "net.java.dev.jna") }

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
