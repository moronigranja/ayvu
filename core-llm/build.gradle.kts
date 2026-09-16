plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.moronigranja.ayvu.llm"
    compileSdk = 36
    // Pinned to the toolchain image's NDK (Dockerfile) — the same NDK the
    // translate model was measured with on the S22 (decisions #162).
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        // llama.cpp-android is arm64-only (decisions #162): the 32-bit ABI
        // never gets the native translator. Devices with a non-arm64 ABI fall
        // through the runtime's missing-prerequisite path (original audio).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // GGML_CPU_ALL_VARIANTS + GGML_BACKEND_DL: the CPU backend is
                // built once per arm64 kernel variant and chosen at runtime by
                // a feature-score probe (the measured device config, #161).
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    implementation(libs.kotlinx.coroutines.core) // single-flight Mutex on the native session
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}