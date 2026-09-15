// Root build script — the repo has no shared root plugin block; module-level
// scripts own their builds. This file only hosts the cross-module boundary
// check (A6).

import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Bundling

val ktlint by configurations.creating {
    attributes {
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
    }
}
dependencies {
    ktlint(libs.ktlint.cli)
}

val ktlintCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Run ktlint over all module Kotlin sources; any violation fails (no baseline)."
    inputs.files(fileTree(rootProject.projectDir) { include("**/src/**/*.kt"); exclude("**/build/**") })
    workingDir = rootProject.projectDir
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
    )
    args(
        "--relative",
        "**/src/**/*.kt",
        "!**/build/**/*.kt",
    )
}

// The formatter twin of ktlintCheck: same pinned CLI, same source set, writes in
// place. One bulk run cleared the 2,937-entry baseline (2026-09-15 cleanup);
// keep using this for mechanical formatting so no baseline needs to come back.
val ktlintFormat by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Format every module's Kotlin sources in place with the pinned ktlint CLI."
    workingDir = rootProject.projectDir
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
    )
    args(
        "--relative",
        "--format",
        "**/src/**/*.kt",
        "!**/build/**/*.kt",
    )
}

tasks.register("checkFeatureBoundaries") {
    group = "verification"
    description = "Fails if any feature-* module gains a dependency on another feature-* module (architecture.md §2, CR-6/A6)."
    doLast {
        val featureModules = rootProject.subprojects
            .filter { it.name.startsWith("feature-") }
            .map { it.path }
            .toSet()

        // Read the DECLARED dependencies of every configuration. The earlier
        // `dependencyConstraints` query only ever contained `constraints {}`
        // entries — this build declares none — so the guard could not fire
        // even for a real implementation(project(":feature-…")) edge.
        val violations = rootProject.subprojects
            .filter { it.name.startsWith("feature-") }
            .flatMap { module ->
                module.configurations
                    .flatMap { it.allDependencies }
                    .filterIsInstance<ProjectDependency>()
                    .mapNotNull { dep ->
                        val target = dep.path
                        target.takeIf { it in featureModules && it != module.path }?.let { "${module.path} → $it" }
                    }
            }
            .distinct()
            .sorted()

        if (violations.isNotEmpty()) {
            error(
                "Feature-to-feature dependencies must not exist (CR-6/A6):\n" +
                    violations.joinToString("\n") { "  $it" } +
                    "\nMove shared types behind core contracts and bind implementations in :app.",
            )
        }
        logger.lifecycle("checkFeatureBoundaries: no feature-to-feature edges (${featureModules.size} feature modules).")
    }
}