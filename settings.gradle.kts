pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "adcp-sdk-java"

// Module list per D3 — eight artifacts. Order is dependency order: leaves first.
include(
    "adcp",
    "adcp-server",
    "adcp-testing",
    "adcp-spring-boot-starter",
    "adcp-cli",
    "adcp-reactor",
    "adcp-mutiny",
    "adcp-kotlin"
)
