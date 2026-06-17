plugins {
    id("adcp.java-library-conventions")
}

description = "AdCP Java SDK — AWS KMS signing provider"

dependencies {
    api(project(":adcp"))
    implementation(project(":adcp-server"))
    implementation(libs.aws.kms)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
}