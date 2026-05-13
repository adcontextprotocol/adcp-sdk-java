// Java library modules (everything except adcp-cli and adcp-kotlin).
plugins {
    id("adcp.java-base-conventions")
    id("adcp.publishing-conventions")
    `java-library`
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    "testImplementation"(libs.junit.jupiter.api)
    "testImplementation"(libs.junit.jupiter.params)
    "testRuntimeOnly"(libs.junit.jupiter.engine)
    "testRuntimeOnly"(libs.junit.platform.launcher)
}
