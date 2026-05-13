// Java library modules (everything except adcp-cli and adcp-kotlin).
plugins {
    id("adcp.java-base-conventions")
    `java-library`
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    "testImplementation"(libs.junit.jupiter.api)
    "testRuntimeOnly"(libs.junit.jupiter.engine)
    "testRuntimeOnly"(libs.junit.platform.launcher)
}
