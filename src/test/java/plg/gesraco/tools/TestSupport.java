package plg.gesraco.tools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import plg.gesraco.tools.proxy.config.ProxyConfig;
import plg.gesraco.tools.proxy.interceptor.DirectRequestHandler;
import plg.gesraco.tools.proxy.interceptor.StandardForwardingInterceptor;
import plg.gesraco.tools.proxy.logging.ProxyLogger;
import plg.gesraco.tools.proxy.server.StreamingReverseProxy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fake backends (JDK {@link HttpServer}) and a proxy client for the tests.
 */
final class TestSupport {

    private TestSupport() {
    }

    static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ------------------------------------------------------------------------------------------ backend

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** Fake Alfresco: exact-path routes (query ignored), then an optional catch-all; 404 otherwise. */
    static final class Backend implements AutoCloseable {
        private record Route(String method, String path, Handler handler) {
        }

        private final HttpServer server;
        private final List<Route> routes = new ArrayList<>();
        private Handler fallback;

        private Backend(int port) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    String path = exchange.getRequestURI().getRawPath();
                    for (Route route : routes) {
                        if ((route.method == null || route.method.equals(exchange.getRequestMethod()))
                                && route.path.equals(path)) {
                            route.handler.handle(exchange);
                            return;
                        }
                    }
                    if (fallback != null) {
                        fallback.handle(exchange);
                    } else {
                        respond(exchange, 404, "text/plain", "not found");
                    }
                }
            });
            server.start();
        }

        static Backend start() throws IOException {
            return new Backend(0);
        }

        Backend route(String method, String path, Handler handler) {
            routes.add(new Route(method, path, handler));
            return this;
        }

        Backend get(String path, Handler handler) {
            return route("GET", path, handler);
        }

        Backend fallback(Handler handler) {
            fallback = handler;
            return this;
        }

        int port() {
            return server.getAddress().getPort();
        }

        String url() {
            return "http://127.0.0.1:" + port();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        respond(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    static void text(HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, "text/plain", body);
    }

    static void json(HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, "application/json", body);
    }

    static String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------ proxy

    /** A running proxy (any free port) and a client sending requests to it. */
    static final class Proxy implements AutoCloseable {
        private final StreamingReverseProxy proxy;
        private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        Proxy(ProxyConfig config, ProxyLogger logger, List<DirectRequestHandler> customRoutes) throws Exception {
            proxy = new StreamingReverseProxy(config, config.directHandlers(),
                    List.of(new StandardForwardingInterceptor()), logger, customRoutes);
            proxy.start(0, false);
        }

        Proxy(ProxyConfig config, ProxyLogger logger) throws Exception {
            this(config, logger, List.of());
        }

        List<String> documentApis() {
            return proxy.documentApis();
        }

        HttpResponse<String> send(String method, String uri, String contentType, byte[] body, String... headers)
                throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + proxy.port() + uri))
                    .method(method, body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            for (int i = 0; i + 1 < headers.length; i += 2) {
                builder.header(headers[i], headers[i + 1]);
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String uri, String... headers) throws IOException, InterruptedException {
            return send("GET", uri, null, null, headers);
        }

        HttpResponse<String> delete(String uri) throws IOException, InterruptedException {
            return send("DELETE", uri, null, null);
        }

        HttpResponse<String> post(String uri, String contentType, String body) throws IOException, InterruptedException {
            return send("POST", uri, contentType, body.getBytes(StandardCharsets.UTF_8));
        }

        HttpResponse<String> put(String uri, String contentType, String body) throws IOException, InterruptedException {
            return send("PUT", uri, contentType, body.getBytes(StandardCharsets.UTF_8));
        }

        HttpResponse<String> postJson(String uri, String json) throws IOException, InterruptedException {
            return post(uri, "application/json", json);
        }

        HttpResponse<String> putJson(String uri, String json) throws IOException, InterruptedException {
            return put(uri, "application/json", json);
        }

        @Override
        public void close() {
            proxy.stop();
            client.close();
        }
    }

    // ------------------------------------------------------------------------------------------ multipart

    /** Minimal multipart/form-data body builder. */
    static final class Multipart {
        private final String boundary = "----test" + UUID.randomUUID().toString().replace("-", "");
        private final StringBuilder text = new StringBuilder();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

        Multipart field(String name, String value) {
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
            return this;
        }

        Multipart file(String name, String fileName, String contentType, String content) {
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\""
                    + fileName + "\"\r\nContent-Type: " + contentType + "\r\n\r\n" + content + "\r\n");
            return this;
        }

        String contentType() {
            return "multipart/form-data; boundary=" + boundary;
        }

        byte[] body() {
            write("--" + boundary + "--\r\n");
            return bytes.toByteArray();
        }

        private void write(String s) {
            text.append(s);
            bytes.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
