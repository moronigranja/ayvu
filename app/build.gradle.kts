plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.android)
}

// Release signing (decisions #123): the release keystore lives OUTSIDE the
// repo (~/.android/ayvu-release.jks) and is wired through the gitignored
// keystore.properties. CI's tag gate assembles the release build UNSIGNED on
// purpose — signing is a local, manual publish step (tools/release.sh).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties: Map<String, String> =
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile
            .readLines()
            .filter { it.contains('=') && !it.trimStart().startsWith("#") }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    } else {
        emptyMap()
    }

// Release 0.1.1 (audit B): a distributed binary must carry the licence text it
// is distributed under (GPL-3.0 §4), so the APK ships `assets/LICENSE` and
// `assets/NOTICE.md` — the About → Licences screen renders them offline
// (`LicenceReader`, feature-settings). Both come from the repo root, the
// single source of truth: this task copies them into a generated assets dir
// rather than committing a second copy under app/src/main/assets that could
// drift from the files the repository publishes.
val licenceAssetsDir = layout.buildDirectory.dir("generated/licenceAssets")
val copyLicenceAssets =
    tasks.register<Copy>("copyLicenceAssets") {
        from(rootProject.file("LICENSE"))
        from(rootProject.file("NOTICE.md"))
        into(licenceAssetsDir)
    }

android {
    namespace = "io.github.moronigranja.ayvu"
    compileSdk = 36

    // assets/LICENSE + assets/NOTICE.md (above), merged into every variant.
    // The Android SourceSet API rejects Provider instances, so the dir is
    // resolved to a File here; the task edge is wired below.
    sourceSets.getByName("main").assets.srcDir(licenceAssetsDir.get().asFile)

    defaultConfig {
        applicationId = "io.github.moronigranja.ayvu"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Pinned debug keystore (repo root): AGP's default ~/.android/debug.keystore
    // is recreated per toolchain container, so every docker-built APK used to
    // get a fresh signature — device reinstalls failed (UPDATE_INCOMPATIBLE).
    // Committing the debug key makes debug builds stable across hosts (a debug
    // key is not a secret; release signing stays out of the repo).
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Present only when keystore.properties exists (local machines);
        // CI/other clones just get the unsigned release build.
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getValue("storeFile"))
                storePassword = keystoreProperties.getValue("storePassword")
                keyAlias = keystoreProperties.getValue("keyAlias")
                keyPassword = keystoreProperties.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            // Unminified for 0.1.x (decisions #123): R8 needs shrink rules +
            // a device pass (Hilt/JNA/ONNX reflection); the first release
            // trades size for a crash-proof runtime.
            isMinifyEnabled = false
            // arm64-v8a only (release 0.1.1): the espeak-ng phonemizer bundle is
            // an arm64-only libespeak-ng.so (decisions #32, verified: ELF aarch64),
            // so the other ABIs' native libs are ~114 MB of dead weight AND would
            // install an app that cannot synthesize speech. Debug stays unfiltered
            // so an x86_64 emulator can still run the UI.
            ndk {
                abiFilters += "arm64-v8a"
            }
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Llama.cpp's CPU backends are dl-loaded MODULE libraries (decisions #162:
    // GGML_CPU_ALL_VARIANTS + GGML_BACKEND_DL picks the best arm64 kernel
    // variant at runtime), and ggml finds them by scannng a directory on the
    // FILESYSTEM. With the modern default (extractNativeLibs=false) the .so
    // files live inside the APK and `nativeLibraryDir` is empty, so the scan
    // finds nothing. Legacy packaging extracts them — the price is the
    // extracted copy on disk, ~13 MB of llama.cpp libs plus the rest.
    packaging {
        jniLibs {
            useLegacyPackaging = true
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

    buildFeatures {
        compose = true
        // Release 0.1.1 About group: BuildConfig.VERSION_NAME is the app's
        // version fact, bound into feature-settings through the AppInfo seam
        // (di/AboutModule); no other module reads BuildConfig.
        buildConfig = true
    }
}

// The assets srcDir above is a plain path, so the merge tasks need an explicit
// edge to the copy: preBuild covers the build as a whole, and the merge task
// itself (whose only input is this directory) is pinned by name so no variant
// can package before the licences land.
tasks.named("preBuild") { dependsOn(copyLicenceAssets) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copyLicenceAssets) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // material-icons-core: the voice dropdown chevron on setup.
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // C1.4: SetupScreen's viewModel() hoisting (hiltViewModel).
    implementation(libs.androidx.hilt.navigation.compose)
    // di/ (composition root, A6) references these core contracts directly.
    implementation(project(":core-model"))
    implementation(project(":core-ebook"))
    implementation(project(":core-locate"))
    // C1.4: app code imports core-tts symbols directly (SetupState, VoiceCatalog,
    // EngineSpec, TTSEngine…), no longer only transitively via feature-settings.
    implementation(project(":core-tts")) { exclude(group = "net.java.dev.jna") }
    implementation(project(":core-translate")) // translate pack descriptors into the registry (PackModule)
    implementation(project(":core-persistence"))
    implementation(libs.room.runtime) // di/PersistenceModule builds LibraryDatabase
    implementation(project(":core-player")) // PlayerPhase etc. for the player surface
    implementation(project(":core-ui")) // the app's composition root renders nothing, but di/ references player contracts
    implementation(project(":feature-library"))
    implementation(project(":feature-player"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-ocr"))
    implementation(project(":feature-share"))
    implementation(project(":core-ocr"))
    // A2: the long-operation contract (ops/) + its Android runner.
    implementation(project(":core-ops"))
    // The engine's ORT + JNA runtimes ship app-side (decisions #25/#32): the
    // Android ORT AAR (core-tts is compileOnly) and JNA AAR (the plain jar
    // has no Android natives); core-tts's jar JNA is excluded below.
    implementation(libs.onnxruntime.android)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    // AyvuApp wires WorkManager's HiltWorkerFactory (Configuration.Provider, #42).
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.room.runtime)
    androidTestImplementation(project(":core-tts")) { exclude(group = "net.java.dev.jna") } // SegmentAnchor on the test classpath
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // PregenE2eTest drives the real worker: WorkManager API on the test classpath.
    androidTestImplementation(libs.work.runtime.ktx)
    // core-tts's jar JNA is excluded at the feature-player seam; the AAR above is the only JNA.

    // C1 host tests (SetupGateTest, SystemTtsEngineTest) run as JVM unit tests.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    // JUnit4 + Robolectric (vintage engine) for the service-edge/audition tests
    // that need a real android Context (matches core-ui/A57 convention).
    testImplementation(libs.junit4)
    testImplementation(libs.vintage.engine)
    testImplementation(libs.robolectric)
}

android {
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
                it.testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
            it.testLogging {
                events("passed", "failed", "skipped")
            }
        }
    }
}
