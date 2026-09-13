plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

group = "com.moronigranja.localttsreader"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core) // status flow, cancellable downloads
    implementation(libs.jna) // espeak-ng phonemization (JVM + Android; explicit library path)
    implementation(libs.kotlinx.serialization.json) // model metadata + vocab config (no codegen)
    // ONNX Runtime is platform-specific: the JVM jar for host tests/benchmark, the
    // onnxruntime-android AAR inside the app (minSdk 26, conventions). core-tts
    // compiles against the Java API only and never ships a native runtime.
    compileOnly(libs.onnxruntime.jvm)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.onnxruntime.jvm) // real inference in tests/benchmark
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.register<JavaExec>("kokoroBenchmark") {
    description = "Synthesize samples with the real Kokoro model via the pack cache (T2 RTF baseline)"
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.moronigranja.localttsreader.tts.kokoro.KokoroBenchmarkKt")
    // The benchmark re-downloads packs into this cache on first run; point it elsewhere to reuse.
    // Optional second arg: directory with oracle_<lang>_<voice>.npy references for audio comparison.
    val launcherArgs = mutableListOf<String>()
    if (project.hasProperty("kokoroCache")) {
        launcherArgs += project.property("kokoroCache") as String
    }
    if (project.hasProperty("kokoroOracle")) {
        launcherArgs += project.property("kokoroOracle") as String
    }
    if (launcherArgs.isNotEmpty()) {
        args = launcherArgs
    }
}

tasks.register<JavaExec>("kokoroGrainSpike") {
    description = "Spike A: sentence-grain vs paragraph-blob synthesis measurements (decisions #31); also writes the on-device corpus"
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.moronigranja.localttsreader.tts.kokoro.KokoroGrainSpikeKt")
    // First optional arg: pack cache root (defaults to ~/.cache/local-tts-reader/packs).
    val launcherArgs = mutableListOf<String>()
    if (project.hasProperty("kokoroCache")) {
        launcherArgs += project.property("kokoroCache") as String
    }
    if (launcherArgs.isNotEmpty()) {
        args = launcherArgs
    }
}

tasks.register<JavaExec>("piperSmoke") {
    description = "D4: synthesize a passage with the real PiperEngine over the pinned packs (host espeak + ORT)"
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.moronigranja.localttsreader.tts.piper.PiperSmokeKt")
    // First optional arg: pack cache root (defaults to ~/.cache/local-tts-reader/packs);
    // the lessac model downloads once through the pinned descriptor.
    val launcherArgs = mutableListOf<String>()
    if (project.hasProperty("piperCache")) {
        launcherArgs += project.property("piperCache") as String
    }
    if (launcherArgs.isNotEmpty()) {
        args = launcherArgs
    }
}
tasks.register<JavaExec>("g0Corpus") {
    description = "G0: phonemize the narration corpus into g0_corpus.tsv (five columns) with the production EspeakPhonemizer"
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.moronigranja.localttsreader.tts.kokoro.G0CorpusGenKt")
    // First optional arg: pack cache root (defaults to ~/.cache/local-tts-reader/packs),
    // forwarded for parity with kokoroGrainSpike (unused by the phonemize-only pass).
    val launcherArgs = mutableListOf<String>()
    if (project.hasProperty("kokoroCache")) {
        launcherArgs += project.property("kokoroCache") as String
    }
    if (launcherArgs.isNotEmpty()) {
        args = launcherArgs
    }
}
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
