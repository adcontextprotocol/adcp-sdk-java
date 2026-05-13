plugins {
    `kotlin-dsl`
}

dependencies {
    // Expose the type-safe `libs` accessor inside precompiled script plugins.
    // Documented workaround until Gradle ships first-class support; the
    // reflective path resolves to the generated LibrariesForLibs jar.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

    // Needed by adcp.kotlin-library-conventions to apply the Kotlin plugin.
    implementation(libs.plugins.kotlin.jvm.map {
        "org.jetbrains.kotlin:kotlin-gradle-plugin:${it.version}"
    })
}
