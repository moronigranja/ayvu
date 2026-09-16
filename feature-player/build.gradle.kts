plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "io.github.moronigranja.ayvu.featureplayer"
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

    buildFeatures {
        compose = true
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
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.media) // MediaSessionCompat + NotificationCompat.MediaStyle
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.room.runtime) // RoomLibraryStore's concrete type is on the API surface
    implementation(libs.kotlinx.coroutines.core)
    // Offline pre-generation (decisions #42): WorkManager job core + Hilt worker injection.
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(project(":core-model"))
    // JNA seam (decisions #25/#32): core-tts's jar JNA has no Android natives;
    // the AAR supplies jnidispatch per ABI and flows to the app from here.
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    implementation(project(":core-tts")) { exclude(group = "net.java.dev.jna") }
    implementation(project(":core-translate")) // TranslatingEngine decorator + TranslateRuntime (read-in-language)
    // `api`: TranslateRuntime.translator() names LlamaTranslator in its public
    // signature (and the on-device E2E drives it directly).
    api(project(":core-llm")) // LlamaTranslator: the translate runtime's llama.cpp session
    // KokoroRuntime's sessionFactory lambda names OrtSession.SessionOptions
    // (decisions #137). Same contract as core-tts: the platform AAR ships
    // app-side; feature-player compiles against the API jar only.
    compileOnly(libs.onnxruntime.jvm)
    implementation(project(":core-persistence"))
    implementation(project(":core-player"))
    implementation(project(":core-ui")) // shared PlayerCard composable (A6)

    // PregenWorker host tests (CR-1): Robolectric runs the real worker with
    // in-memory Room + a fake engine; work-testing provides the test
    // WorkManager singleton (progress/foreground updates) + the worker builder.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.robolectric)
    testImplementation(libs.vintage.engine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.work.testing)
    // Robolectric discovery loads the FakeRuntime subclasses, whose
    // KokoroRuntime superclass signature names OrtSession.SessionOptions
    // (decisions #137) — the JVM jar satisfies the class reference; the
    // AAR's natives still ship app-side only and are never loaded in tests.
    testImplementation(libs.onnxruntime.jvm)

    // The D1 androidTest drives the production engine path; core-tts is
    // compileOnly on ORT and the standalone test app has no app-module
    // runtime to supply it (Failed resolution of OrtSession$SessionOptions).
    androidTestImplementation(libs.onnxruntime.android)

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}