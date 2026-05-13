// adcp-server — agent-side primitives, RFC 9421, idempotency, async tasks,
// webhooks, comply_test_controller, upstream-recorder (7.x addition, D9-adjacent).
// Per RFC §Reference: covers TS exports `/server`, `/server/legacy/v5`,
// `/signing`, `/signing/server`, `/signing/client`, `/express-mcp` analogue.

plugins {
    id("adcp.java-library-conventions")
}

description = "AdCP Java SDK — server-side primitives, signing, async tasks, webhooks"

dependencies {
    api(project(":adcp"))
    api(libs.mcp)
    // jakarta.servlet-api is pulled in by mcp-core's HTTP server transport.
    // D9 R1 prototype question: can this run without Jetty/Tomcat?
    compileOnly(libs.jakarta.servlet.api)
}
