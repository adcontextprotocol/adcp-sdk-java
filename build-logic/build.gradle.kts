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

    // Needed by adcp.publishing-conventions to apply the vanniktech plugin
    // (Central Portal support — replaces legacy OSSRH s01 endpoint).
    implementation(libs.plugins.vanniktech.maven.publish.map {
        "com.vanniktech:gradle-maven-publish-plugin:${it.version}"
    })

    // Used by the codegen task to emit Java source from JSON Schemas.
    // Build-time only; never on the SDK's runtime classpath.
    implementation(libs.javapoet)
    implementation(libs.jackson.databind)
    implementation(libs.jspecify)
}
