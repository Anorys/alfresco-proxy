package plg.gesraco.tools.proxy.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import plg.gesraco.tools.proxy.config.RouteRule;
import plg.gesraco.tools.proxy.config.RouteRule.Mode;
import plg.gesraco.tools.proxy.config.RouteRule.Store;
import plg.gesraco.tools.proxy.http.Http;
import plg.gesraco.tools.proxy.logging.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Configured routing rules of the local store ({@code local-store.routes}, see {@link RouteRule}): lets an API be
 * described in configuration only. The first matching rule applies.
 *
 * <p>Resources are kept in {@code <root>/<sha-256 of key>/} as {@code resource.json} (key, status, content type,
 * origin, deleted flag) and {@code body.bin}. A resource is either a copy of a backend response ({@code cache}) or
 * written locally ({@code simulate}); a locally written resource is also served to {@code proxy} GETs.
 */
public final class LocalRoutes {

    private static final String META_FILE = "resource.json";
    private static final String BODY_FILE = "body.bin";

    private final List<RouteRule> routes;
    private final Path root;
    private final Upstream upstream;
    private final int maxLoggableTextBytes;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public LocalRoutes(List<RouteRule> routes, Path root, Upstream upstream, int maxLoggableTextBytes) {
        this.routes = List.copyOf(routes);
        this.root = root;
        this.upstream = upstream;
        this.maxLoggableTextBytes = maxLoggableTextBytes;
    }

    public List<RouteRule> routes() {
        return routes;
    }

    public Path root() {
        return root;
    }

    private record Match(RouteRule rule, String key) {
    }

    private Match match(String method, String requestPath, String relativeUri) {
        for (RouteRule rule : routes) {
            Map<String, String> variables = rule.match(method, requestPath);
            if (variables != null) {
                return new Match(rule, rule.resourceKey(variables, requestPath, relativeUri));
            }
        }
        return null;
    }

    /** Whether the first rule matching the request relays it to Alfresco (so it isn't refused as a write). */
    public boolean proxies(String method, String requestPath) {
        Match m = match(method, requestPath, requestPath);
        return m != null && m.rule().mode() == Mode.PROXY;
    }

    /**
     * Handles a request matched by a rule; returns null when no rule matches or the rule relays it to Alfresco.
     */
    public Outcome handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        Match m = match(method, Http.path(request), Http.relativeUri(request));
        if (m == null) {
            return null;
        }
        RouteRule rule = m.rule();
        boolean read = method.equals("GET") || method.equals("HEAD");
        return switch (rule.mode()) {
            case PROXY -> read ? withLock(m.key(), () -> serveLocalWrite(request, response, m.key())) : null;
            case CACHE -> withLock(m.key(), () -> cache(request, response, m.key()));
            case SIMULATE -> withLock(m.key(), () -> simulate(request, response, rule, m.key()));
            case BLOCK -> {
                int status = rule.status() != null ? rule.status() : 403;
                if (rule.body() != null) {
                    Http.respond(response, status, contentType(rule), rule.body());
                } else {
                    Http.respondText(response, status, "Forbidden: blocked by local-store route " + rule.path());
                }
                yield new Outcome(status, null, false, null);
            }
        };
    }

    // ------------------------------------------------------------------------------------------------ modes

    private Outcome serveLocalWrite(HttpServletRequest request, HttpServletResponse response, String key)
            throws IOException {
        ObjectNode meta = load(key);
        if (meta == null || !"local".equals(meta.path("origin").asText())) {
            return null;
        }
        return serve(request, response, key, meta, null);
    }

    private Outcome cache(HttpServletRequest request, HttpServletResponse response, String key) throws IOException {
        ObjectNode existing = load(key);
        if (existing != null) {
            return serve(request, response, key, existing, null);
        }
        Path dir = dir(key);
        UpstreamResult<ObjectNode> result = upstream.request(request, "GET", Http.relativeUri(request), null, backend -> {
            String contentType = backend.headers().firstValue("Content-Type").orElse(Http.OCTET_STREAM);
            if (backend.statusCode() < 200 || backend.statusCode() > 299) {
                // Not copied: relayed as is
                response.setStatus(backend.statusCode());
                response.setContentType(contentType);
                try (InputStream in = backend.body(); OutputStream out = response.getOutputStream()) {
                    in.transferTo(out);
                }
                return null;
            }
            Files.createDirectories(dir);
            try (InputStream in = backend.body()) {
                writeBody(dir, in);
            }
            ObjectNode meta = newMeta(key, backend.statusCode(), contentType, "copy");
            save(key, meta);
            return meta;
        });
        if (result.value() == null) {
            if (result.targetUrl() == null) {
                Http.respondText(response, 403, "Forbidden: Request blocked by proxy configuration");
                return new Outcome(403, null, false, null);
            }
            return new Outcome(result.status(), result.targetUrl(), result.fromFallback(), null);
        }
        System.out.printf("[%s] <=LOCAL=> Copied %s from %s%n", Instant.now(), key, result.targetUrl());
        return serve(request, response, key, result.value(), result);
    }

    private Outcome simulate(HttpServletRequest request, HttpServletResponse response, RouteRule rule, String key)
            throws IOException {
        String requestBody = null;
        Store store = rule.store();
        if (store == Store.BODY) {
            Path dir = dir(key);
            Files.createDirectories(dir);
            try (InputStream in = request.getInputStream()) {
                writeBody(dir, in);
            }
            String contentType = request.getContentType() != null ? request.getContentType() : Http.OCTET_STREAM;
            save(key, newMeta(key, 200, contentType, "local"));
            requestBody = summarizeBody(dir.resolve(BODY_FILE), contentType);
        } else if (store == Store.DELETE) {
            ObjectNode meta = newMeta(key, 404, Http.TEXT, "local");
            meta.put("deleted", true);
            Files.deleteIfExists(dir(key).resolve(BODY_FILE));
            save(key, meta);
        }
        System.out.printf("[%s] <=LOCAL=> Simulated %s %s (%s)%n", Instant.now(), request.getMethod(), key,
                store.name().toLowerCase(Locale.ROOT));

        if (rule.body() != null) {
            int status = rule.status() != null ? rule.status() : 200;
            Http.respond(response, status, contentType(rule), rule.body());
            return new Outcome(status, null, false, requestBody);
        }
        if (store == Store.BODY) {
            Outcome served = serve(request, response, key, load(key), null);
            return new Outcome(served.status(), null, false, requestBody);
        }
        int status = rule.status() != null ? rule.status() : 204;
        response.setStatus(status);
        return new Outcome(status, null, false, requestBody);
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private Outcome serve(HttpServletRequest request, HttpServletResponse response, String key, ObjectNode meta,
                          UpstreamResult<?> copiedFrom) throws IOException {
        String targetUrl = copiedFrom != null ? copiedFrom.targetUrl() : null;
        boolean fromFallback = copiedFrom != null && copiedFrom.fromFallback();
        if (meta.path("deleted").asBoolean(false)) {
            Http.respondText(response, 404, "Not found (deleted locally): " + key);
            return new Outcome(404, targetUrl, fromFallback, null);
        }
        int status = meta.path("status").asInt(200);
        Path body = dir(key).resolve(BODY_FILE);
        response.setStatus(status);
        response.setContentType(meta.path("contentType").asText(Http.OCTET_STREAM));
        response.setContentLengthLong(Files.exists(body) ? Files.size(body) : 0);
        if (!"HEAD".equalsIgnoreCase(request.getMethod()) && Files.exists(body)) {
            try (OutputStream out = response.getOutputStream()) {
                Files.copy(body, out);
            }
        }
        return new Outcome(status, targetUrl, fromFallback, null);
    }

    private String summarizeBody(Path body, String contentType) throws IOException {
        long size = Files.size(body);
        String type = Http.mediaType(contentType);
        boolean textual = type.startsWith("text/") || type.contains("json") || type.contains("xml")
                || type.equals("application/x-www-form-urlencoded");
        if (size == 0) {
            return null;
        }
        if (!textual || size > maxLoggableTextBytes) {
            return "\"body (" + size + " bytes, " + JsonUtils.escape(type) + ")\"";
        }
        return JsonUtils.formatJsonBody(Files.readString(body, StandardCharsets.UTF_8));
    }

    private static String contentType(RouteRule rule) {
        return rule.contentType() != null ? rule.contentType() : Http.JSON;
    }

    private ObjectNode newMeta(String key, int status, String contentType, String origin) {
        ObjectNode meta = mapper.createObjectNode();
        meta.put("key", key);
        meta.put("status", status);
        meta.put("contentType", contentType);
        meta.put("origin", origin);
        meta.put("updatedAt", Instant.now().toString());
        return meta;
    }

    private Path dir(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return root.resolve(HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectNode load(String key) throws IOException {
        Path file = dir(key).resolve(META_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        JsonNode node = mapper.readTree(file.toFile());
        return node instanceof ObjectNode o ? o : null;
    }

    private void save(String key, ObjectNode meta) throws IOException {
        Path dir = dir(key);
        Files.createDirectories(dir);
        Path tmp = dir.resolve(META_FILE + "." + UUID.randomUUID() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), meta);
        move(tmp, dir.resolve(META_FILE));
    }

    private static void writeBody(Path dir, InputStream in) throws IOException {
        Path tmp = dir.resolve(BODY_FILE + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.copy(in, tmp);
            move(tmp, dir.resolve(BODY_FILE));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private <T> T withLock(String key, LocalDocumentStore.Locked<T> block) throws IOException {
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            return block.run();
        } finally {
            lock.unlock();
        }
    }
}
