// adcp-reactor — Project Reactor bridge over the sync surface (D13 — at GA).
// Wraps blocking calls in Mono.fromCallable on a bounded elastic scheduler.

plugins {
    id("adcp.java-library-conventions")
}

description = "AdCP Java SDK — Project Reactor adapter"

dependencies {
    api(project(":adcp"))
    api(libs.reactor.core)
}
