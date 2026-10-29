package plg.gesraco.tools.proxy.config;

import plg.gesraco.tools.proxy.http.Http;
import plg.gesraco.tools.proxy.interceptor.DirectRequestHandler;
import plg.gesraco.tools.proxy.logging.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Definition of a direct response route loaded from YAML configuration.
 *
 * <p>With {@code echo}, the route answers
 * {@code {"status":"ok","method":"<method>","path":"<path>","received":<request body>}} instead of {@code body}
 * (handy to check that a client reaches the proxy).
 */
public record DirectResponseDefinition(
        String path,
        List<String> methods,
        int status,
        String contentType,
        String body,
        boolean echo
) {

    public DirectResponseDefinition {
        methods = methods == null ? List.of() : List.copyOf(methods);
        contentType = contentType == null ? Http.JSON : contentType;
        body = body == null ? "" : body;
    }

    public DirectResponseDefinition(String path, List<String> methods, int status, String contentType, String body) {
        this(path, methods, status, contentType, body, false);
    }

    /**
     * Converts this YAML response definition into an executable {@link DirectRequestHandler}.
     */
    public DirectRequestHandler toDirectRequestHandler() {
        return (request, response) -> {
            boolean pathMatches = Http.path(request).equals(path);
            boolean methodMatches = methods.isEmpty()
                    || methods.stream().anyMatch(m -> m.equalsIgnoreCase(request.getMethod()));
            if (!pathMatches || !methodMatches) {
                return false;
            }
            int httpStatus = status >= 100 && status <= 599 ? status : 200;
            if (echo) {
                String received = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String receivedJson = received.isBlank() ? "null" : JsonUtils.formatJsonBody(received);
                Http.respond(response, httpStatus, Http.JSON, "{\"status\":\"ok\",\"method\":\""
                        + JsonUtils.escape(request.getMethod()) + "\",\"path\":\"" + JsonUtils.escape(path)
                        + "\",\"received\":" + receivedJson + "}");
            } else {
                Http.respond(response, httpStatus, contentType, body);
            }
            return true;
        };
    }
}
