pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ayvu"

// Pure-JVM modules (testable without the Android SDK) plus the Android
// app modules (toolchain in Docker, see tools/docker-build.sh) and the
// T3 measurement harness.
include(":core-model")
include(":core-ebook")
include(":core-locate")
include(":core-persistence")
include(":core-tts")
include(":core-translate")
include(":core-llm")
include(":core-player")
include(":core-ui")
include(":core-ocr")
include(":core-backup")
include(":app")
include(":feature-library")
include(":feature-player")
include(":feature-ocr")
include(":feature-settings")
include(":feature-share")
include(":spike-tts")