package org.adcontextprotocol.adcp.server.a2a;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class A2aCardServletTest {

    private static AgentCard testCard() {
        return AgentCard.builder()
                .name("test-agent")
                .description("Test agent")
                .version("1.0.0")
                .url("https://agent.example.com")
                .preferredTransport("JSONRPC")
                .capabilities(AgentCapabilities.builder().streaming(true).pushNotifications(false).build())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "https://agent.example.com")))
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(AgentSkill.builder()
                        .id("echo")
                        .name("echo")
                        .description("Echo tool")
                        .tags(List.of())
                        .examples(List.of())
                        .inputModes(List.of("text"))
                        .outputModes(List.of("text"))
                        .build()))
                .build();
    }

    @Test
    void doGet_returns_agent_card_as_json() throws Exception {
        A2aCardServlet servlet = new A2aCardServlet(testCard());
        StubResponse response = new StubResponse();

        servlet.doGet(stubRequest("application/json"), response.proxy());

        assertEquals(HttpServletResponse.SC_OK, response.status);
        assertEquals("application/json", response.contentType);
        assertTrue(response.body().contains("test-agent"));
        assertTrue(response.body().contains("echo"));
    }

    @Test
    void doGet_returns_agent_card_for_wildcard_accept() throws Exception {
        A2aCardServlet servlet = new A2aCardServlet(testCard());
        StubResponse response = new StubResponse();

        servlet.doGet(stubRequest("*/*"), response.proxy());

        assertEquals(HttpServletResponse.SC_OK, response.status);
        assertTrue(response.body().contains("test-agent"));
    }

    @Test
    void doGet_returns_agent_card_for_null_accept() throws Exception {
        A2aCardServlet servlet = new A2aCardServlet(testCard());
        StubResponse response = new StubResponse();

        servlet.doGet(stubRequest(null), response.proxy());

        assertEquals(HttpServletResponse.SC_OK, response.status);
    }

    @Test
    void doGet_rejects_html_accept() throws Exception {
        A2aCardServlet servlet = new A2aCardServlet(testCard());
        StubResponse response = new StubResponse();

        servlet.doGet(stubRequest("text/html"), response.proxy());

        assertEquals(HttpServletResponse.SC_NOT_ACCEPTABLE, response.status);
    }

    @Test
    void doGet_sets_cache_control_headers() throws Exception {
        A2aCardServlet servlet = new A2aCardServlet(testCard());
        StubResponse response = new StubResponse();

        servlet.doGet(stubRequest("application/json"), response.proxy());

        assertEquals("public, max-age=300", response.headers.get("Cache-Control"));
        assertEquals("*", response.headers.get("Access-Control-Allow-Origin"));
    }

    @Test
    void agentCard_returns_the_card() {
        AgentCard card = testCard();
        A2aCardServlet servlet = new A2aCardServlet(card);

        assertSame(card, servlet.agentCard());
    }

    private static HttpServletRequest stubRequest(String accept) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    if ("getHeader".equals(method.getName())) {
                        return "Accept".equals(args[0]) ? accept : null;
                    }
                    return null;
                });
    }

    @SuppressWarnings("default")
    private static Object defaultValue(Class<?> type) {
        if (type == void.class || !type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }

    private static class StubResponse {
        int status;
        String contentType;
        String characterEncoding;
        final Map<String, String> headers = new LinkedHashMap<>();
        final StringWriter buffer = new StringWriter();
        final PrintWriter writer = new PrintWriter(buffer) {
            @Override
            public void flush() {
                super.flush();
                committed = true;
            }
        };
        boolean committed;

        HttpServletResponse proxy() {
            return (HttpServletResponse) Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("setStatus".equals(name)) { status = (int) args[0]; return null; }
                        if ("setContentType".equals(name)) { contentType = (String) args[0]; return null; }
                        if ("setCharacterEncoding".equals(name)) { characterEncoding = (String) args[0]; return null; }
                        if ("setHeader".equals(name)) { headers.put((String) args[0], (String) args[1]); return null; }
                        if ("setContentLength".equals(name)) { return null; }
                        if ("getWriter".equals(name)) { return writer; }
                        if ("isCommitted".equals(name)) { return committed; }
                        if ("getCharacterEncoding".equals(name)) { return characterEncoding; }
                        if ("getContentType".equals(name)) { return contentType; }
                        return defaultValue(method.getReturnType());
                    });
        }

        String body() {
            writer.flush();
            return buffer.toString();
        }
    }
}