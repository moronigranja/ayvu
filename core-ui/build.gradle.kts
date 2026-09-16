plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.moronigranja.ayvu.ui"
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
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()
                it.testLogging { events("passed", "failed", "skipped") }
            }
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(project(":core-player")) // PlaybackUiState / PlayerCommands / formatBytes
    implementation(project(":core-tts")) { exclude(group = "net.java.dev.jna") } // KokoroVoiceMeta (selector rows)

    testImplementation(libs.junit4)
    testImplementation(libs.vintage.engine)
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit.platform.launcher)
}