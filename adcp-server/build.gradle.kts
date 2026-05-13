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
    // mcp-core ships HttpServletSseServerTransportProvider and
    // HttpServletStreamableServerTransportProvider — framework-neutral
    // (no Spring dependency). mcp-json-jackson2 pins us to the Jackson 2
    // tree per RFC §JSON. See specs/mcp-prototype-findings.md.
    api(libs.mcp.core)
    api(libs.mcp.json.jackson2)
    // The servlet transport classes use jakarta.servlet.* at compile time;
    // the adopter brings their own Servlet container at runtime (Jetty,
    // Tomcat, Undertow, embedded Spring Boot, etc.).
    compileOnly(libs.jakarta.servlet.api)
}
