/**
 * Spring Boot 3.x autoconfiguration for AdCP. Wires the request handler,
 * Jackson {@code ObjectMapper}, signing provider, account store, plus
 * Micrometer {@code MeterRegistry} and Actuator {@code HealthIndicator}
 * when those are on the classpath. Spring Security integration is a
 * documented recipe, not autoconfig.
 */
@org.jspecify.annotations.NullMarked
package org.adcontextprotocol.adcp.springboot;
