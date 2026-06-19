package org.adcontextprotocol.adcp.server.a2a;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Serves the A2A Agent Card at the standard well-known path
 * {@code /.well-known/agent-card.json}.
 *
 * <p>A2A callers discover agent capabilities by fetching this endpoint.
 * Without it, callers fall back to a synthetic card and lose the server's
 * advertised skills, security metadata, and interface URLs.
 *
 * <p>Deploy at {@code /.well-known/agent-card.json} in the same servlet container
 * that hosts the {@link A2aServlet} JSON-RPC endpoint.
 *
 * @see A2aServerBuilder#buildAgentCard()
 */
public final class A2aCardServlet extends HttpServlet {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private static final String CACHE_CONTROL = "public, max-age=300";
    private static final String ALLOWED_ORIGIN = "*";

    private final transient AgentCard agentCard;
    private final byte[] cachedJson;

    /**
     * Creates a servlet that serves the given agent card.
     *
     * @param agentCard the agent card to serve (typically from
     *                  {@link A2aServerBuilder#buildAgentCard()})
     */
    public A2aCardServlet(AgentCard agentCard) {
        this.agentCard = agentCard;
        byte[] json;
        try {
            json = JsonUtil.toJson(agentCard).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize AgentCard", e);
        }
        this.cachedJson = json;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String accept = request.getHeader("Accept");
        boolean acceptsJson = accept != null
                && (accept.contains("application/json") || accept.contains("*/*"));

        if (!acceptsJson && accept != null && !accept.isBlank()) {
            response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"Not Acceptable: this endpoint serves application/json\"}");
            response.getWriter().flush();
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", CACHE_CONTROL);
        response.setHeader("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
        response.setContentLength(cachedJson.length);
        response.getWriter().write(new String(cachedJson, StandardCharsets.UTF_8));
        response.getWriter().flush();
    }

    /** Returns the agent card served by this servlet. */
    public AgentCard agentCard() {
        return agentCard;
    }
}