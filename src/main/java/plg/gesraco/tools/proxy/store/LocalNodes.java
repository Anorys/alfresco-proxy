package plg.gesraco.tools.proxy.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import plg.gesraco.tools.proxy.http.Http;
import plg.gesraco.tools.proxy.logging.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API-neutral building blocks of the local document store, used by {@link DocumentApi} implementations.
 *
 * <p>A node record ({@link LocalDocumentStore}) holds:
 * <ul>
 *   <li>{@code resources}: cached backend responses ({@code status}, {@code contentType}, {@code body}) keyed by
 *       sub-resource; the key {@code ""} holds the node document (the API's JSON description of the node);</li>
 *   <li>{@code content}: the stored content file ({@code present}, {@code file}, {@code mimetype}, {@code size});</li>
 *   <li>{@code origin} ({@code primary}, {@code fallback}, {@code local}), {@code deleted}, {@code locallyModified}.</li>
 * </ul>
 * Reads copy missing data from the backends through {@link Upstream} (GETs only); writes only change the record.
 */
public class LocalNodes {

    public static final String LOCAL_USER = "proxy-local";
    public static final String CONTENT_PROPERTY = "{http://www.alfresco.org/model/content/1.0}content";

    // A node reference anywhere in a URI: `/workspace/SpacesStore/<uuid>` (path) or `workspace://SpacesStore/<uuid>`
    private static final Pattern NODE_IN_URI = Pattern.compile(
            "([A-Za-z]+)(?:/|://)([A-Za-z]+)/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    // Legacy Alfresco download webscript: `<prefix>/api/node/content[;cm:content]/{space}/{store}/{guid}[/{filename}]`
    private static final Pattern LEGACY_CONTENT_PATH =
            Pattern.compile("^(.*)/api/node/content(?:;([^/]*))?/([^/]+)/([^/]+)/([^/]+)(?:/.*)?$");

    /** Formats error responses in the API's style. */
    @FunctionalInterface
    public interface ErrorFormat {
        /** Returns {@code {contentType, body}}. */
        String[] format(int status, String message);

        ErrorFormat PLAIN_TEXT = (status, message) -> new String[]{Http.TEXT, message};
    }

    /**
     * Backend locations of a node: where its node document and its content are read.
     */
    public record NodeLocation(NodeRef ref, String nodeUri, String contentUri) {
    }

    /**
     * Collects what the audit record needs while a request is handled.
     */
    public static final class Trace {
        private String targetUrl;
        private boolean fromFallback;
        private String requestBody;

        public void record(UpstreamResult<?> result) {
            if (result.targetUrl() != null) {
                targetUrl = result.targetUrl();
            }
            fromFallback = result.fromFallback();
        }

        public boolean fromFallback() {
            return fromFallback;
        }

        public String targetUrl() {
            return targetUrl;
        }

        public void requestBody(String value) {
            requestBody = value;
        }

        public Outcome outcome(int status) {
            return new Outcome(status, targetUrl, fromFallback, requestBody);
        }
    }

    /** A buffered (textual) backend response. */
    public record Fetched(int status, String contentType, String text) {
        public boolean isSuccess() {
            return status >= 200 && status <= 299;
        }
    }

    /** Result of {@link #loadOrCopy}: the record, or the response already sent to the client. */
    public sealed interface Copy permits Copied, Failed {
    }

    public record Copied(ObjectNode record) implements Copy {
    }

    public record Failed(Outcome outcome) implements Copy {
    }

    private final LocalDocumentStore store;
    private final Upstream upstream;
    private final boolean copyContentEagerly;
    private final int maxLoggableTextBytes;
    private final ErrorFormat errorFormat;
    private final ObjectMapper mapper;

    public LocalNodes(DocumentApiProvider.Context context, ErrorFormat errorFormat) {
        this.store = context.store();
        this.upstream = context.upstream();
        this.copyContentEagerly = context.copyContentEagerly();
        this.maxLoggableTextBytes = context.maxLoggableTextBytes();
        this.errorFormat = errorFormat;
        this.mapper = store.mapper();
    }

    public LocalDocumentStore store() {
        return store;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public <T> T withLock(NodeRef ref, LocalDocumentStore.Locked<T> block) throws IOException {
        return store.withLock(ref, block);
    }

    public ObjectNode load(NodeRef ref) throws IOException {
        return store.load(ref);
    }

    public void save(NodeRef ref, ObjectNode record) throws IOException {
        store.save(ref, record);
    }

    // ------------------------------------------------------------------------------------- node lookup

    /**
     * Returns the node referenced by {@code relativeUri} (generic node references, or {@code apiSyntax}) when its
     * local copy came from the fallback target. Such a node doesn't exist on the primary, which may still answer
     * {@code 200} for it, so its requests must go to the fallback.
     */
    public NodeRef fallbackNodeIn(String relativeUri, Function<String, NodeRef> apiSyntax) throws IOException {
        String decoded = Http.decode(relativeUri);
        NodeRef ref = null;
        Matcher m = NODE_IN_URI.matcher(decoded);
        if (m.find()) {
            ref = NodeRef.of(m.group(1), m.group(2), m.group(3));
        }
        if (ref == null && apiSyntax != null) {
            ref = apiSyntax.apply(decoded);
        }
        if (ref == null) {
            return null;
        }
        ObjectNode record = store.load(ref);
        return record != null && "fallback".equals(record.path("origin").asText()) ? ref : null;
    }

    // ------------------------------------------------------------------------------------- copying

    /**
     * Returns the local record of the node, copying it (node document, plus content when eager) from the backends
     * when there is none. With {@code requireLive}, a locally deleted node is reported as not found.
     * Only a JSON object is accepted as node document; anything else is relayed to the client as-is.
     */
    public Copy loadOrCopy(HttpServletRequest request, HttpServletResponse response, NodeLocation location,
                           Trace trace, ObjectNode existing, boolean requireLive) throws IOException {
        if (existing != null) {
            if (requireLive && isDeleted(existing)) {
                return new Failed(respondError(response, 404, "Node does not exist: " + location.ref(), trace));
            }
            return new Copied(existing);
        }
        Fetched fetched = fetchText(request, "GET", location.nodeUri(), null, trace);
        if (!fetched.isSuccess() || !(parseJson(fetched.text()) instanceof ObjectNode)) {
            return new Failed(relay(response, fetched, trace));
        }

        ObjectNode record = store.newRecord(location.ref(), trace.fromFallback() ? "fallback" : "primary",
                trace.targetUrl());
        putResource(record, "", fetched);
        store.save(location.ref(), record);
        System.out.printf("[%s] <=LOCAL=> Copied node %s from %s%n", Instant.now(), location.ref(), trace.targetUrl());

        if (copyContentEagerly && location.contentUri() != null) {
            try {
                fetchContent(request, location, record, new Trace());
            } catch (IOException | RuntimeException e) {
                System.err.printf("[WARN] Could not copy content of %s: %s%n", location.ref(),
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }
        return new Copied(record);
    }

    /** Buffered backend call; a request no backend may receive yields a 403. */
    public Fetched fetchText(HttpServletRequest request, String method, String uri, String jsonBody, Trace trace)
            throws IOException {
        UpstreamResult<Fetched> result = upstream.request(request, method, uri, jsonBody, response ->
                new Fetched(response.statusCode(), contentType(response, Http.TEXT), readText(response)));
        trace.record(result);
        return result.value() != null
                ? result.value()
                : new Fetched(result.status(), Http.TEXT, "Forbidden: Request blocked by proxy configuration");
    }

    /**
     * Downloads the node content into the store and records it on {@code record} (and saves it). A 404 records
     * "no content". Returns the backend's response when the content can't be stored, so it can be relayed.
     */
    public Fetched fetchContent(HttpServletRequest request, NodeLocation location, ObjectNode record, Trace trace)
            throws IOException {
        Fetched[] failure = new Fetched[1];
        UpstreamResult<StoredContent> result = upstream.request(request, "GET", location.contentUri(), null,
                response -> {
                    if (response.statusCode() >= 200 && response.statusCode() <= 299) {
                        String mimetype = Http.mediaType(contentType(response, Http.OCTET_STREAM));
                        try (InputStream in = response.body()) {
                            return store.writeContent(location.ref(), mimetype, in::transferTo);
                        }
                    }
                    failure[0] = new Fetched(response.statusCode(), contentType(response, Http.TEXT), readText(response));
                    return null;
                });
        trace.record(result);
        if (result.value() != null) {
            recordContent(record, location.ref(), result.value());
        } else if (failure[0] != null && failure[0].status() == 404) {
            markNoContent(record);
        } else {
            return failure[0] != null
                    ? failure[0]
                    : new Fetched(result.status(), Http.TEXT, "Forbidden: Request blocked by proxy configuration");
        }
        store.save(location.ref(), record);
        return null;
    }

    // ------------------------------------------------------------------------------------- serving

    /**
     * Serves the stored content of the node, copying it first when it wasn't yet. With {@code attachmentName}, a
     * {@code Content-Disposition: attachment} header is added.
     */
    public Outcome serveContent(HttpServletRequest request, HttpServletResponse response, NodeLocation location,
                                ObjectNode record, Trace trace, String attachmentName) throws IOException {
        if (!(record.get("content") instanceof ObjectNode) && !isLocalOrigin(record) && location.contentUri() != null) {
            Fetched failure = fetchContent(request, location, record, trace);
            if (failure != null) {
                return relay(response, failure, trace);
            }
        }
        JsonNode content = record.get("content");
        Path file = content == null ? null : store.contentFile(location.ref(), content.path("file").asText(null));
        if (content == null || !content.path("present").asBoolean(false) || file == null || !Files.exists(file)) {
            return respondError(response, 404, "Node " + location.ref() + " has no content", trace);
        }
        response.setStatus(200);
        response.setContentType(content.path("mimetype").asText(Http.OCTET_STREAM));
        response.setContentLengthLong(Files.size(file));
        if (attachmentName != null) {
            response.setHeader("Content-Disposition", contentDisposition(attachmentName));
        }
        if (!"HEAD".equalsIgnoreCase(request.getMethod())) {
            try (OutputStream out = response.getOutputStream()) {
                Files.copy(file, out);
            }
        }
        return trace.outcome(200);
    }

    /**
     * Serves a cached per-node resource ({@code key} in the record), copying it from {@code backendUri} on first use.
     * Locally created nodes have nothing on the backends: they answer {@code localDefault}, or 404 when null.
     */
    public Outcome readResource(HttpServletRequest request, HttpServletResponse response, NodeRef ref,
                                ObjectNode record, String key, String backendUri, Trace trace, JsonNode localDefault)
            throws IOException {
        JsonNode cached = resources(record).get(key);
        if (cached != null) {
            return respondResource(response, cached, trace);
        }
        if (isLocalOrigin(record)) {
            return localDefault != null
                    ? respondJson(response, 200, localDefault, trace)
                    : respondError(response, 404, "Not available for locally created node " + ref, trace);
        }
        Fetched fetched = fetchText(request, "GET", backendUri, null, trace);
        if (fetched.isSuccess()) {
            putResource(record, key, fetched);
            store.save(ref, record);
        }
        return relay(response, fetched, trace);
    }

    /**
     * Legacy {@code GET <prefix>/api/node/content[;cm:content]/{space}/{store}/{guid}[/{name}][?a=true]}: served
     * from the local copy like the API's own content. Returns null for other paths and other content properties
     * ({@code ;cm:thumbnail}...), which are left to the backends.
     *
     * @param locator backend locations of a node, given the service prefix ({@code /alfresco/s}) and the node
     * @param nameOf  file name of a node record, for {@code a=true}
     */
    public Outcome legacyContent(HttpServletRequest request, HttpServletResponse response,
                                 LegacyLocator locator, Function<ObjectNode, String> nameOf) throws IOException {
        if (!"GET".equalsIgnoreCase(request.getMethod()) && !"HEAD".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        Matcher m = LEGACY_CONTENT_PATH.matcher(Http.path(request));
        if (!m.matches()) {
            return null;
        }
        String property = m.group(2);
        if (property != null && !property.isEmpty() && !isContentProperty(property)) {
            return null;
        }
        NodeRef ref = NodeRef.of(m.group(3), m.group(4), m.group(5));
        if (ref == null) {
            return null;
        }
        NodeLocation location = locator.locate(m.group(1), ref);
        boolean attachment = "true".equalsIgnoreCase(Http.queryParameter(request, "a"));
        Trace trace = new Trace();
        return withLock(ref, () -> {
            ObjectNode existing = load(ref);
            if (existing != null && isDeleted(existing)) {
                return respondError(response, 404, "Node " + ref + " does not exist", trace);
            }
            ObjectNode record;
            switch (loadOrCopy(request, response, location, trace, existing, false)) {
                case Copied c -> record = c.record();
                case Failed f -> {
                    return f.outcome();
                }
            }
            String name = null;
            if (attachment) {
                name = nameOf.apply(record);
                if (name == null) {
                    name = ref.guid();
                }
            }
            return serveContent(request, response, location, record, trace, name);
        });
    }

    /** Backend locations of a node addressed through the legacy download webscript. */
    @FunctionalInterface
    public interface LegacyLocator {
        NodeLocation locate(String servicePrefix, NodeRef ref);
    }

    private static boolean isContentProperty(String property) {
        String p = property.trim();
        return p.equals(CONTENT_PROPERTY) || p.equals("cm:content");
    }

    // ------------------------------------------------------------------------------------- record helpers

    public ObjectNode resources(ObjectNode record) {
        return record.get("resources") instanceof ObjectNode r ? r : record.putObject("resources");
    }

    /** The node document (the {@code ""} resource), or null. */
    public ObjectNode nodeDocument(ObjectNode record) {
        return resources(record).path("").get("body") instanceof ObjectNode body ? body : null;
    }

    /** Body of a cached resource, or null. */
    public JsonNode resourceBody(ObjectNode record, String key) {
        JsonNode resource = resources(record).get(key);
        return resource == null ? null : resource.get("body");
    }

    public void putJsonResource(ObjectNode record, String key, JsonNode body) {
        ObjectNode resource = resources(record).putObject(key);
        resource.put("status", 200);
        resource.put("contentType", Http.JSON);
        resource.set("body", body);
    }

    public void putResource(ObjectNode record, String key, Fetched fetched) {
        JsonNode body = fetched.contentType().toLowerCase(Locale.ROOT).contains("json") ? parseJson(fetched.text()) : null;
        ObjectNode resource = resources(record).putObject(key);
        resource.put("status", fetched.status());
        resource.put("contentType", fetched.contentType());
        resource.set("body", body != null ? body : TextNode.valueOf(fetched.text()));
    }

    /** New record of a node created locally (no backend copy). */
    public ObjectNode newLocalRecord(NodeRef ref, JsonNode nodeDocument) {
        ObjectNode record = store.newRecord(ref, "local", null);
        putJsonResource(record, "", nodeDocument);
        return record;
    }

    /** Records new content on {@code record}, deleting the previous content file. */
    public void recordContent(ObjectNode record, NodeRef ref, StoredContent content) {
        String previousFile = record.path("content").path("file").asText(null);
        ObjectNode node = record.putObject("content");
        node.put("present", true);
        node.put("file", content.fileName());
        node.put("mimetype", content.mimetype());
        node.put("size", content.size());
        if (previousFile != null && !previousFile.equals(content.fileName())) {
            store.deleteContent(ref, previousFile);
        }
    }

    /** Records "no content", deleting the previous content file. */
    public void markNoContent(ObjectNode record, NodeRef ref) {
        String previousFile = record.path("content").path("file").asText(null);
        markNoContent(record);
        store.deleteContent(ref, previousFile);
    }

    private static void markNoContent(ObjectNode record) {
        record.putObject("content").put("present", false);
    }

    /** Stored content descriptor of {@code record}, or null when it has none. */
    public StoredContent storedContent(ObjectNode record) {
        JsonNode content = record.get("content");
        if (content == null || !content.path("present").asBoolean(false)) {
            return null;
        }
        return new StoredContent(content.path("file").asText(), content.path("size").asLong(),
                content.path("mimetype").asText(Http.OCTET_STREAM));
    }

    public StoredContent writeContent(NodeRef ref, String mimetype, InputStream in) throws IOException {
        return store.writeContent(ref, mimetype == null ? Http.OCTET_STREAM : mimetype, in::transferTo);
    }

    public StoredContent copyContent(NodeRef from, StoredContent content, NodeRef to) throws IOException {
        return content == null ? null : store.copyContent(from, content.fileName(), to, content.mimetype());
    }

    public boolean isDeleted(ObjectNode record) {
        return record.path("deleted").asBoolean(false);
    }

    public boolean isLocalOrigin(ObjectNode record) {
        return "local".equals(record.path("origin").asText());
    }

    public void markLocallyModified(ObjectNode record) {
        record.put("locallyModified", true);
        record.put("updatedAt", Instant.now().toString());
    }

    /** Soft delete: the copy stays on disk (set {@code "deleted": false} in node.json to restore it). */
    public void markDeleted(ObjectNode record) {
        record.put("deleted", true);
        record.put("deletedAt", Instant.now().toString());
        markLocallyModified(record);
    }

    // ------------------------------------------------------------------------------------- request helpers

    public JsonNode parseJson(String text) {
        try {
            return text == null ? null : mapper.readTree(text);
        } catch (IOException e) {
            return null;
        }
    }

    /** Reads the request body as text and records its summary for the audit log. */
    public String readBody(HttpServletRequest request, Trace trace) throws IOException {
        String text = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        trace.requestBody(summarize(text));
        return text;
    }

    /** Reads the request body as a JSON object, or returns null. */
    public ObjectNode readJsonObject(HttpServletRequest request, Trace trace) throws IOException {
        return parseJson(readBody(request, trace)) instanceof ObjectNode o ? o : null;
    }

    public String summarize(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.length() > maxLoggableTextBytes) {
            return "\"[TRUNCATED " + text.length() + " chars] "
                    + JsonUtils.escape(text.substring(0, maxLoggableTextBytes)) + "\"";
        }
        return JsonUtils.formatJsonBody(text);
    }

    /** User name from the client's Basic credentials (used for creator / modifier), or null. */
    public String user(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            String user = colon < 0 ? decoded : decoded.substring(0, colon);
            return user.isBlank() ? null : user;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@link #user} or {@link #LOCAL_USER}. */
    public String userOrLocal(HttpServletRequest request) {
        String user = user(request);
        return user != null ? user : LOCAL_USER;
    }

    // ------------------------------------------------------------------------------------- responses

    public Outcome respondJson(HttpServletResponse response, int status, JsonNode body, Trace trace) throws IOException {
        Http.respondJson(response, status, mapper.writeValueAsString(body));
        return trace.outcome(status);
    }

    public Outcome respondText(HttpServletResponse response, int status, String text, Trace trace) throws IOException {
        Http.respondText(response, status, text);
        return trace.outcome(status);
    }

    public Outcome respondEmpty(HttpServletResponse response, int status, Trace trace) {
        response.setStatus(status);
        response.setContentLength(0);
        return trace.outcome(status);
    }

    public Outcome respondError(HttpServletResponse response, int status, String message, Trace trace)
            throws IOException {
        String[] formatted = errorFormat.format(status, message);
        Http.respond(response, status, formatted[0], formatted[1]);
        return trace.outcome(status);
    }

    /** Answers a write the API doesn't support locally; it is never forwarded. */
    public Outcome unsupportedWrite(HttpServletResponse response, Trace trace) throws IOException {
        return respondError(response, 403,
                "Forbidden: this write is not supported by the local document store and is never forwarded to Alfresco",
                trace);
    }

    public Outcome relay(HttpServletResponse response, Fetched fetched, Trace trace) throws IOException {
        Http.respond(response, fetched.status(), fetched.contentType(), fetched.text());
        return trace.outcome(fetched.status());
    }

    public Outcome respondResource(HttpServletResponse response, JsonNode resource, Trace trace) throws IOException {
        JsonNode body = resource.get("body");
        String text = body == null ? "" : body.isTextual() ? body.asText() : mapper.writeValueAsString(body);
        int status = resource.path("status").asInt(200);
        Http.respond(response, status, resource.path("contentType").asText(Http.JSON), text);
        return trace.outcome(status);
    }

    private static String contentType(HttpResponse<?> response, String defaultValue) {
        return response.headers().firstValue("Content-Type").orElse(defaultValue);
    }

    private static String readText(HttpResponse<InputStream> response) throws IOException {
        try (InputStream in = response.body()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String contentDisposition(String fileName) {
        String ascii = fileName.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
        String encoded = java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
