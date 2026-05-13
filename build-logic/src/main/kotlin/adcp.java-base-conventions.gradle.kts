// Common Java config applied to every JVM module in the SDK.
// Per D2: JDK 21 toolchain, no 17 fallback.
// Per D18: matches the family's conventional-commits / changesets shape
// at the build level (commitlint + changesets aren't a Gradle concern but
// the build-side conventions below — tests on every module, jar manifest
// stamping — are).

plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withJavadocJar()
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    // -Xlint:all minus `processing` (skeleton modules have @NullMarked
    // package annotations with no claiming processor — a real warning,
    // but not actionable until contributors fill the modules) and
    // `options` (cross-version compile noise).
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-options", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
    }
    // Tolerate package-info-only modules during the harness phase. Once
    // each module has real public classes, this can flip back to strict.
    isFailOnError = false
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        showExceptions = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Reproducible builds: deterministic ordering and no timestamps in archives.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Automatic-Module-Name" to "org.adcontextprotocol.${project.name.replace('-', '.')}",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version
        )
    }
}

// All modules get JSpecify nullability annotations on the compile classpath
// per RFC §Cross-cutting: no Optional<T> returns; @Nullable T everywhere.
val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    "compileOnly"(libs.jspecify)
}

// Dependency locking — checked-in lockfiles guard against transitive drift.
// Regenerate with: ./gradlew dependencies --write-locks
dependencyLocking {
    lockAllConfigurations()
}
