plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
}

android {
    namespace = "io.github.moronigranja.ayvu.persistence"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric runs the Room DAO/store tests against a real SQLite.
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()
                it.testLogging {
                    events("passed", "failed", "skipped")
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    compileOnly("javax.inject:javax.inject:1") // AppSettings Hilt annotations
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(project(":core-model"))
    implementation(project(":core-player")) // PlayerStore contract + player types (T4-1)
    implementation(project(":core-backup")) // BackupSnapshot/BackupCodec (E1)

    testImplementation(libs.junit4)
    testImplementation(libs.vintage.engine)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
