package org.adcontextprotocol.adcp.server.a2a;

import jakarta.servlet.http.HttpServletRequest;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.spec.A2AError;

/**
 * Authenticates incoming A2A JSON-RPC requests and produces a
 * {@link ServerCallContext} for the request handler.
 *
 * <p>Implement this interface and pass it to
 * {@link A2aServlet#A2aServlet(org.a2aproject.sdk.server.requesthandlers.RequestHandler, A2aAuthProvider)}
 * to enforce authentication on all incoming A2A requests.
 *
 * <p>Example — static bearer token:
 * <pre>{@code
 * A2aAuthProvider auth = request -> {
 *     String token = request.getHeader("Authorization");
 *     if (!"Bearer my-secret".equals(token)) {
 *         throw new InvalidRequestError("Unauthorized");
 *     }
 *     return new ServerCallContext(
 *         new AuthenticatedUser(extractPrincipal(token)),
 *         Map.of(), Set.of(), AgentInterface.CURRENT_PROTOCOL_VERSION);
 * };
 * new A2aServlet(handler, auth);
 * }</pre>
 */
@FunctionalInterface
public interface A2aAuthProvider {

    /**
     * Validates the incoming HTTP request and returns an authenticated call context.
     *
     * @param request the incoming HTTP request
     * @return a fully populated {@link ServerCallContext} for this request
     * @throws A2AError to reject the request with a JSON-RPC error response
     */
    ServerCallContext authenticate(HttpServletRequest request) throws A2AError;
}
