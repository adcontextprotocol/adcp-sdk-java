// Root build. Modules apply convention plugins from build-logic/.
// Per D3, group = org.adcontextprotocol; per D17, semver tags from main.

allprojects {
    group = "org.adcontextprotocol"
    version = "0.1.0-SNAPSHOT"
}

// Convenience task: regenerate all module lockfiles in one command.
//
//   ./gradlew updateLocks
//
// Dependency locking is enabled on all configurations via `dependencyLocking
// { lockAllConfigurations() }` in adcp.java-base-conventions.gradle.kts.
// Running the built-in `dependencies` task with --write-locks causes Gradle
// to rewrite each module's gradle.lockfile; this aggregator runs all of them
// together so callers don't have to enumerate every subproject path.
tasks.register("updateLocks") {
    group = "help"
    description = "Regenerate gradle.lockfile for every module. Usage: ./gradlew updateLocks --write-locks"
    dependsOn(subprojects.map { ":${it.name}:dependencies" })
}
