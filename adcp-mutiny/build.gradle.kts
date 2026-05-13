// adcp-mutiny — SmallRye Mutiny bridge over the sync surface (D13 — at GA).
// Quarkus equivalent of adcp-reactor.

plugins {
    id("adcp.java-library-conventions")
}

description = "AdCP Java SDK — SmallRye Mutiny adapter"

dependencies {
    api(project(":adcp"))
    api(libs.mutiny)
}
