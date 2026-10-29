package plg.gesraco.tools.proxy.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Servlet request/response helpers shared by the proxy, the direct handlers and the local document store.
 *
 * <p>Query parameters are always parsed from the raw query string: {@code getParameter} would consume an
 * {@code application/x-www-form-urlencoded} request body that still has to be relayed.
 */
public final class Http {

    public static final String JSON = "application/json";
    public static final String TEXT = "text/plain";
    public static final String OCTET_STREAM = "application/octet-stream";

    private Http() {
    }

    /** Raw (still encoded) request path, without query string. */
    public static String path(HttpServletRequest request) {
        return request.getRequestURI();
    }

    /** Raw request path with its query string, as sent by the client. */
    public static String relativeUri(HttpServletRequest request) {
        String query = request.getQueryString();
        return request.getRequestURI() + (query == null || query.isEmpty() ? "" : "?" + query);
    }

    /** Query string prefixed with {@code ?}, or an empty string. */
    public static String querySuffix(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null || query.isBlank() ? "" : "?" + query;
    }

    /** First value of query parameter {@code name}, or null. */
    public static String queryParameter(HttpServletRequest request, String name) {
        List<String> values = queryParameters(request.getQueryString()).get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public static Map<String, List<String>> queryParameters(String query) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int i = pair.indexOf('=');
            String key = decode(i < 0 ? pair : pair.substring(0, i));
            String value = i < 0 ? "" : decode(pair.substring(i + 1));
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return result;
    }

    public static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    /** Request headers as a name to values map, in the order received. */
    public static Map<String, List<String>> headers(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, Collections.list(request.getHeaders(name)));
        }
        return headers;
    }

    /** Media type without parameters, lower-cased ({@code text/plain; charset=UTF-8} becomes {@code text/plain}). */
    public static String mediaType(String contentType) {
        if (contentType == null) {
            return null;
        }
        int i = contentType.indexOf(';');
        String type = (i < 0 ? contentType : contentType.substring(0, i)).trim().toLowerCase(Locale.ROOT);
        return type.isEmpty() ? null : type;
    }

    public static boolean isMultipartFormData(HttpServletRequest request) {
        return "multipart/form-data".equals(mediaType(request.getContentType()));
    }

    /**
     * Sends a complete text response. A content type without charset gets {@code charset=UTF-8}
     * for textual types.
     */
    public static void respond(HttpServletResponse response, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType(withCharset(contentType));
        response.setContentLength(bytes.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
        }
    }

    public static void respondText(HttpServletResponse response, int status, String text) throws IOException {
        respond(response, status, TEXT, text);
    }

    public static void respondJson(HttpServletResponse response, int status, String json) throws IOException {
        respond(response, status, JSON, json);
    }

    /** Like {@link #respondText}, but ignores failures (the response may already be committed or closed). */
    public static void sendError(HttpServletResponse response, int status, String message) {
        try {
            if (!response.isCommitted()) {
                response.reset();
                respondText(response, status, message);
            }
        } catch (Exception ignored) {
            // Connection may already be committed or closed
        }
    }

    private static String withCharset(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return TEXT + "; charset=UTF-8";
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        boolean textual = lower.startsWith("text/") || lower.contains("json") || lower.contains("xml");
        return textual && !lower.contains("charset=") ? contentType + "; charset=UTF-8" : contentType;
    }
}
