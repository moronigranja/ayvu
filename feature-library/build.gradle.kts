plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.moronigranja.localttsreader.featurelibrary"
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
    // material-icons-core: the settings gear on the library top bar.
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    // F3: DocumentFile walk over an ACTION_OPEN_DOCUMENT_TREE grant.
    implementation(libs.androidx.documentfile)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)
    // PersistenceModule constructs the Room database; core-persistence only
    // exposes it via project(), so Room itself must be on this classpath.
    implementation(libs.room.runtime)

    // A6: player card + commands come from core contracts (core-ui/core-player);
    // the app binds the implementations at the composition root.
    implementation(project(":core-ui"))

    implementation(project(":core-tts")) { exclude(group = "net.java.dev.jna") }
    implementation(project(":core-translate")) // read-in-language dialog data (PackRegistry, TranslatePackStager)
    implementation(project(":core-model"))
    implementation(project(":core-ebook"))
    implementation(project(":core-locate"))
    implementation(project(":core-persistence"))
    implementation(project(":core-player")) // PlayerStore binding (T4-1)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}
