// Kotlin library module (adcp-kotlin per D14 — thin extension at v1.0).
plugins {
    id("adcp.java-base-conventions")
    id("org.jetbrains.kotlin.jvm")
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

kotlin {
    jvmToolchain(21)
}

dependencies {
    "testImplementation"(libs.junit.jupiter.api)
    "testRuntimeOnly"(libs.junit.jupiter.engine)
    "testRuntimeOnly"(libs.junit.platform.launcher)
}
