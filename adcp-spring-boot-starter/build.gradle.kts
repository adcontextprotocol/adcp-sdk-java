// adcp-spring-boot-starter — Spring Boot 3.x autoconfig per D7 (jakarta only).
// Auto-configures: handler, Jackson, signing provider, account store,
// Micrometer (if classpath), Actuator (if classpath). Spring Security is a
// documented recipe per D12, not autoconfig.

plugins {
    id("adcp.java-library-conventions")
}

description = "AdCP Java SDK — Spring Boot starter (Spring Boot 3.x / jakarta only)"

dependencies {
    api(project(":adcp-server"))
    api(libs.spring.boot.autoconfigure)
    annotationProcessor(libs.spring.boot.configuration.processor)
}
