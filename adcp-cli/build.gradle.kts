// adcp-cli — runnable jar. Commands: `adcp <agent> [tool] [payload]`,
// `adcp storyboard run`, `adcp grade`, KMS pre-deploy probe (per RFC).
// Homebrew tap is a Java-leads add (post-v0.4).

plugins {
    id("adcp.java-base-conventions")
    application
}

description = "AdCP Java SDK — CLI"

dependencies {
    implementation(project(":adcp"))
    implementation(project(":adcp-testing"))
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "org.adcontextprotocol.adcp.cli.Main"
    applicationName = "adcp"
}
