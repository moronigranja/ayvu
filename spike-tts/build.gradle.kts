plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.moronigranja.ayvu.spiketts"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.moronigranja.ayvu.spiketts"
        // 27: the QNN plugin AAR (com.qualcomm.qti:onnxruntime-android-qnn)
        // declares minSdk 27; this is a measurement-only harness.
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // S22 Ultra is arm64-v8a; one ABI makes the APK-size number the
            // shipping-relevant one (decisions #30).
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Pinned debug keystore (repo root), same as :app — AGP's default
    // ~/.android/debug.keystore is recreated per toolchain container, so the
    // androidTest APK would otherwise get a different signature than the target
    // (instrumentation "signature matching" denial on install).
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
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

    packaging {
        // onnxruntime-android bundles libonnxruntime.so; strip debug symbols like the app module does.
        // Legacy packaging ON: QNN's DSP loader (and dlopen-by-path) needs the
        // libQnnHtp*/skel .so files extracted to the app lib dir — FUSE-in-APK
        // storage leaves them absent, and HTP device create fails.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.onnxruntime.android)
    // QNN plugin EP (Qualcomm-maintained) plugs into stock ORT 1.29; qnn-runtime
    // ships the QAIRT stack (libQnnHtp.so + per-arch stubs/skels, incl. v69 and v79).
    implementation("com.qualcomm.qti:onnxruntime-android-qnn:2.5.0")
    implementation("com.qualcomm.qti:qnn-runtime:2.49.0")
    implementation(libs.kotlinx.coroutines.core) // exposed to consumers by core-tts only at runtime
    // core-tts's jar JNA has no Android natives (5.17 central jar ships none);
    // the AAR carries jni/<abi>/libjnidispatch.so, resolved via System.loadLibrary.
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    implementation(project(":core-tts")) { exclude(group = "net.java.dev.jna") } // the raw Kokoro port under test
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
