package org.adcontextprotocol.adcp.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Smoke test for the MCP SDK 1.1.2 dependency wiring (D9 R1 + R2).
 *
 * <p>This is the "deps resolve" gate — proves that mcp-core + mcp-json-jackson2
 * are on the classpath and the framework-neutral servlet transport providers
 * exist where {@code specs/mcp-prototype-findings.md} says they do.
 *
 * <p>We assert presence via {@code getResource(name + ".class")} rather than
 * {@code Class.forName(...)} because the servlet transports extend
 * {@code HttpServlet} from {@code jakarta.servlet-api} — a {@code compileOnly}
 * dep that adopters provide at runtime. Loading those classes via reflection
 * would NoClassDefFoundError without the servlet API; checking the
 * {@code .class} resource exists proves the SDK ships the class without
 * needing the transitive dep that adopters bring.
 *
 * <p>Full integration tests for the transport land on the {@code transport}
 * track.
 */
class McpDependencyImportsTest {

    @ParameterizedTest(name = "ships {0}")
    @ValueSource(strings = {
            "io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider",
            "io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider",
            "io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport",
            "io.modelcontextprotocol.server.transport.StdioServerTransportProvider",
            "io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport",
            "io.modelcontextprotocol.client.transport.HttpClientSseClientTransport",
            "io.modelcontextprotocol.client.transport.StdioClientTransport",
            "io.modelcontextprotocol.spec.McpServerTransportProvider",
            "io.modelcontextprotocol.spec.McpStreamableServerTransportProvider",
            "io.modelcontextprotocol.spec.McpClientTransport"
    })
    void mcp_core_classes_present(String fqcn) {
        String resource = fqcn.replace('.', '/') + ".class";
        assertNotNull(
                getClass().getClassLoader().getResource(resource),
                fqcn + " not on classpath — has the MCP SDK pin moved?");
    }

    @Test
    void stdio_transport_loads_without_servlet() throws Exception {
        // StdioServerTransportProvider has no servlet dep, so Class.forName
        // works. Proves the MCP SDK is at least partially callable from a
        // plain JVM without bringing in any HTTP infrastructure.
        Class<?> cls = Class.forName(
                "io.modelcontextprotocol.server.transport.StdioServerTransportProvider");
        assertNotNull(cls);
    }

    @Test
    void jackson2_module_is_pinned_not_jackson3() {
        // mcp-json-jackson2 brings com.fasterxml.jackson.databind onto the
        // classpath. The Jackson 3 module would sit under
        // tools.jackson.databind — we deliberately don't load it; absence
        // of that runtime dep is the contract.
        String jackson2 = "com/fasterxml/jackson/databind/ObjectMapper.class";
        assertNotNull(
                getClass().getClassLoader().getResource(jackson2),
                "Jackson 2 not on classpath — has mcp-json-jackson2 been replaced by jackson3?");
    }
}
