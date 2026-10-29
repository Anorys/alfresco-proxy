package plg.gesraco.tools.proxy.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import plg.gesraco.tools.proxy.http.Http;

import java.io.IOException;

/**
 * Handler for requests that should be intercepted and answered directly
 * by the proxy without forwarding to the target Alfresco server.
 */
@FunctionalInterface
public interface DirectRequestHandler {

    /**
     * Evaluates whether this handler should handle the call directly.
     *
     * @return {@code true} if the request was handled and answered directly;
     *         {@code false} to allow the request to continue to subsequent handlers or the reverse proxy.
     */
    boolean handle(HttpServletRequest request, HttpServletResponse response) throws IOException;

    /**
     * Action run by a matching handler; it must write the complete response.
     */
    @FunctionalInterface
    interface Action {
        void run(HttpServletRequest request, HttpServletResponse response) throws IOException;
    }

    /**
     * Intercepts requests matching an exact path and optional HTTP method ({@code null} = any), executing
     * {@code action}.
     */
    static DirectRequestHandler matching(String path, String method, Action action) {
        return (request, response) -> {
            boolean matchesMethod = method == null || method.equalsIgnoreCase(request.getMethod());
            if (matchesMethod && Http.path(request).equals(path)) {
                action.run(request, response);
                return true;
            }
            return false;
        };
    }

    /**
     * Intercepts requests matching a path prefix and optional HTTP method, executing {@code action}.
     */
    static DirectRequestHandler prefix(String pathPrefix, String method, Action action) {
        return (request, response) -> {
            boolean matchesMethod = method == null || method.equalsIgnoreCase(request.getMethod());
            if (matchesMethod && Http.path(request).startsWith(pathPrefix)) {
                action.run(request, response);
                return true;
            }
            return false;
        };
    }

    /**
     * Intercepts requests matching an exact path and responds with JSON content and HTTP status.
     */
    static DirectRequestHandler respondJson(String path, String method, int status, String jsonContent) {
        return matching(path, method, (request, response) -> Http.respondJson(response, status, jsonContent));
    }

    /** {@link #respondJson(String, String, int, String)} for any method with status 200. */
    static DirectRequestHandler respondJson(String path, String jsonContent) {
        return respondJson(path, null, 200, jsonContent);
    }

    /**
     * Intercepts requests matching an exact path and responds with plain text and HTTP status.
     */
    static DirectRequestHandler respondText(String path, String method, int status, String text) {
        return matching(path, method, (request, response) -> Http.respondText(response, status, text));
    }

    /** {@link #respondText(String, String, int, String)} for any method with status 200. */
    static DirectRequestHandler respondText(String path, String text) {
        return respondText(path, null, 200, text);
    }
}
