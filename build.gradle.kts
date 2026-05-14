// Root build. Modules apply convention plugins from build-logic/.
// Per D3, group = org.adcontextprotocol; per D17, semver tags from main.

allprojects {
    group = "org.adcontextprotocol"
    version = "0.1.0-SNAPSHOT"
}

// Convenience aggregator: regenerate all module lockfiles in one command.
//
//   ./gradlew updateLocks --write-locks
//
// Each subproject owns a `resolveAndLockAll` task (registered in
// adcp.java-base-conventions) that resolves every resolvable configuration
// within that project's own context — required by Gradle 9's project-
// isolation rules.  This root task aggregates them so callers only need
// one command.  Gradle also rewrites settings-gradle.lockfile automatically
// when --write-locks is active (it re-resolves the settings classpath on
// every invocation regardless of which task is run).
tasks.register("updateLocks") {
    group = "help"
    description = "Regenerate gradle.lockfile for every module. Run: ./gradlew updateLocks --write-locks"
    dependsOn(subprojects.map { ":${it.name}:resolveAndLockAll" })
}
