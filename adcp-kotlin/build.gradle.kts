// adcp-kotlin — Kotlin extensions on top of the Java surface (D14 — at v1.0).
// Coroutine suspend-fun wrappers, DSL builders. Nullability already correct via
// JSpecify on the Java surface.

plugins {
    id("adcp.kotlin-library-conventions")
}

description = "AdCP Java SDK — Kotlin coroutine + DSL extensions"

dependencies {
    api(project(":adcp"))
    api(libs.kotlinx.coroutines.core)
}
