package plg.gesraco.tools.proxy.store.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import plg.gesraco.tools.proxy.http.Http;
import plg.gesraco.tools.proxy.store.DocumentApi;
import plg.gesraco.tools.proxy.store.DocumentApiProvider;
import plg.gesraco.tools.proxy.store.LocalNodes;
import plg.gesraco.tools.proxy.store.LocalNodes.Copied;
import plg.gesraco.tools.proxy.store.LocalNodes.Copy;
import plg.gesraco.tools.proxy.store.LocalNodes.Failed;
import plg.gesraco.tools.proxy.store.LocalNodes.Fetched;
import plg.gesraco.tools.proxy.store.LocalNodes.NodeLocation;
import plg.gesraco.tools.proxy.store.LocalNodes.Trace;
import plg.gesraco.tools.proxy.store.NodeRef;
import plg.gesraco.tools.proxy.store.Outcome;
import plg.gesraco.tools.proxy.store.StoredContent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local document store for the standard Alfresco REST API v1
 * ({@code <prefix>/api/-default-/public/alfresco/versions/1/nodes/...}):
 * <ul>
 *   <li>Reads ({@code GET nodes/{id}}, {@code /content}, {@code /children}, {@code /versions}, other
 *       sub-resources): a node is copied from the backends the first time it is requested (entry, content), then
 *       answered from the local copy. Children lists come from the backends and are overlaid with the local state
 *       (local edits, deletions, moves and creations).</li>
 *   <li>Writes ({@code PUT nodes/{id}}, {@code PUT /content}, {@code DELETE}, {@code POST /children} JSON or
 *       multipart, {@code /copy}, {@code /move}, {@code /lock}, {@code /unlock}): applied to the local copy only,
 *       never sent to Alfresco. A node that wasn't copied yet is fetched first. Other writes get 403.</li>
 *   <li>The legacy download webscript ({@code /api/node/content/...}) is served from the same copies.</li>
 * </ul>
 * Settings: {@code base-path} (used for legacy downloads, default {@value #DEFAULT_BASE_PATH}),
 * {@code legacy-content} (default true), {@code folder-types} (comma-separated, default {@code cm:folder}).
 */
public final class AlfrescoRestApi implements DocumentApi {

    public static final String NAME = "alfresco-rest";
    public static final String DEFAULT_BASE_PATH = "/alfresco/api/-default-/public/alfresco/versions/1";

    private static final Pattern NODES_PATH =
            Pattern.compile("^(.*/api/[^/]+/public/alfresco/versions/1)/nodes/([^/]+)(?:/(.*))?$");
    private static final Pattern NODE_ID_IN_URI =
            Pattern.compile("/public/alfresco/versions/1/nodes/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    private static final Set<String> ALIASES = Set.of("-root-", "-my-", "-shared-");
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final LocalNodes nodes;
    private final String basePath;
    private final boolean legacyContent;
    private final Set<String> folderTypes;

    public AlfrescoRestApi(DocumentApiProvider.Context context) {
        this.nodes = new LocalNodes(context, (status, message) -> new String[]{Http.JSON, RestEntries.error(status, message)});
        this.basePath = context.config().setting("base-path", DEFAULT_BASE_PATH);
        this.legacyContent = context.config().setting("legacy-content", true);
        this.folderTypes = new HashSet<>(RestEntries.csv(context.config().setting("folder-types", "cm:folder")));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public LocalNodes nodes() {
        return nodes;
    }

    @Override
    public List<String> readOnlyPostPaths() {
        return List.of(
                "**/api/*/public/search/versions/1/search",
                "**/api/*/public/authentication/versions/1/tickets");
    }

    @Override
    public NodeRef nodeIn(String decodedUri) {
        Matcher m = NODE_ID_IN_URI.matcher(decodedUri);
        return m.find() ? NodeRef.ofId(m.group(1)) : null;
    }

    @Override
    public Outcome handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (legacyContent) {
            Outcome legacy = nodes.legacyContent(request, response,
                    (servicePrefix, ref) -> location(basePath, ref), record -> entry(record).path("name").asText(null));
            if (legacy != null) {
                return legacy;
            }
        }
        Matcher m = NODES_PATH.matcher(Http.path(request));
        if (!m.matches()) {
            return null;
        }
        String base = m.group(1);
        String id = Http.decode(m.group(2));
        String sub = m.group(3) == null ? "" : m.group(3).replaceAll("/+$", "");
        String method = request.getMethod().toUpperCase();
        Trace trace = new Trace();

        if (!ALIASES.contains(id) && NodeRef.ofId(id) == null) {
            return null;
        }
        NodeRef ref;
        if (ALIASES.contains(id)) {
            Fetched fetched = nodes.fetchText(request, "GET", base + "/nodes/" + id, null, trace);
            String resolved = fetched.isSuccess() ? nodes.parseJson(fetched.text()).path("entry").path("id").asText(null) : null;
            ref = resolved == null ? null : NodeRef.ofId(resolved);
            if (ref == null) {
                return nodes.relay(response, fetched, trace);
            }
        } else {
            ref = NodeRef.ofId(id);
        }
        NodeLocation location = location(base, ref);

        switch (sub) {
            case "" -> {
                return switch (method) {
                    case "GET", "HEAD" -> nodes.withLock(ref, () -> readNode(request, response, location, trace));
                    case "PUT" -> updateNode(request, response, location, trace);
                    case "DELETE" -> nodes.withLock(ref, () -> deleteNode(request, response, location, trace));
                    default -> nodes.unsupportedWrite(response, trace);
                };
            }
            case "content" -> {
                return switch (method) {
                    case "GET", "HEAD" -> nodes.withLock(ref, () -> readContent(request, response, location, trace));
                    case "PUT" -> nodes.withLock(ref, () -> setContent(request, response, location, trace));
                    default -> nodes.unsupportedWrite(response, trace);
                };
            }
            case "children" -> {
                return switch (method) {
                    case "GET", "HEAD" -> listChildren(request, response, base, location, trace);
                    case "POST" -> createChild(request, response, base, location, trace);
                    default -> nodes.unsupportedWrite(response, trace);
                };
            }
            case "copy", "move" -> {
                return method.equals("POST")
                        ? copyOrMove(request, response, base, location, sub.equals("move"), trace)
                        : nodes.unsupportedWrite(response, trace);
            }
            case "lock", "unlock" -> {
                return method.equals("POST")
                        ? nodes.withLock(ref, () -> lock(request, response, location, sub.equals("lock"), trace))
                        : nodes.unsupportedWrite(response, trace);
            }
            default -> {
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    return nodes.unsupportedWrite(response, trace);
                }
                // Binary sub-resources (version or rendition content) are left to the backends
                if (sub.endsWith("/content")) {
                    return null;
                }
                return nodes.withLock(ref, () -> readSubResource(request, response, base, location, sub, trace));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ reads

    private Outcome readNode(HttpServletRequest request, HttpServletResponse response, NodeLocation location,
                             Trace trace) throws IOException {
        ObjectNode record = liveRecord(request, response, location, trace);
        if (record == null) {
            return trace.outcome(response.getStatus());
        }
        List<String> include = RestEntries.csv(Http.queryParameter(request, "include"));
        fetchMissingIncludes(request, location, record, include, trace);
        ObjectNode entry = entry(record);
        return nodes.respondJson(response, 200, RestEntries.wrap(RestEntries.view(entry, include,
                RestEntries.csv(Http.queryParameter(request, "fields")), false)), trace);
    }

    /**
     * Fields requested with {@code include} that the copy lacks (e.g. {@code isFavorite}, {@code definition}) are
     * fetched once from the backends and kept.
     */
    private void fetchMissingIncludes(HttpServletRequest request, NodeLocation location, ObjectNode record,
                                      List<String> include, Trace trace) throws IOException {
        if (nodes.isLocalOrigin(record)) {
            return;
        }
        ObjectNode entry = entry(record);
        ArrayNode tried = record.get("includesFetched") instanceof ArrayNode a ? a : record.putArray("includesFetched");
        Set<String> alreadyTried = new HashSet<>();
        tried.forEach(t -> alreadyTried.add(t.asText()));
        List<String> missing = include.stream().filter(f -> !entry.has(f) && !alreadyTried.contains(f)).toList();
        if (missing.isEmpty()) {
            return;
        }
        String uri = location.nodeUri().substring(0, location.nodeUri().indexOf('?')) + "?include=" + String.join(",", missing);
        Fetched fetched = nodes.fetchText(request, "GET", uri, null, trace);
        JsonNode fetchedEntry = fetched.isSuccess() ? nodes.parseJson(fetched.text()) : null;
        if (fetchedEntry == null) {
            return;
        }
        for (String field : missing) {
            JsonNode value = fetchedEntry.path("entry").get(field);
            if (value != null) {
                entry.set(field, value.deepCopy());
            }
            tried.add(field);
        }
        nodes.save(location.ref(), record);
    }

    private Outcome readContent(HttpServletRequest request, HttpServletResponse response, NodeLocation location,
                                Trace trace) throws IOException {
        ObjectNode record = liveRecord(request, response, location, trace);
        if (record == null) {
            return trace.outcome(response.getStatus());
        }
        // `attachment` defaults to true in the REST API
        boolean attachment = !"false".equalsIgnoreCase(Http.queryParameter(request, "attachment"));
        String name = attachment ? entry(record).path("name").asText(location.ref().guid()) : null;
        return nodes.serveContent(request, response, location, record, trace, name);
    }

    private Outcome readSubResource(HttpServletRequest request, HttpServletResponse response, String base,
                                    NodeLocation location, String sub, Trace trace) throws IOException {
        ObjectNode record = liveRecord(request, response, location, trace);
        if (record == null) {
            return trace.outcome(response.getStatus());
        }
        String query = Http.querySuffix(request);
        String backendUri = base + "/nodes/" + location.ref().guid() + "/" + sub + query;
        // Collections (versions, parents, targets, renditions...) of a locally created node are empty
        JsonNode localDefault = sub.contains("/") ? null : RestEntries.emptyList();
        return nodes.readResource(request, response, location.ref(), record, sub + query, backendUri, trace, localDefault);
    }

    /**
     * {@code GET nodes/{id}/children}: the backend list overlaid with the local state. Entries with a local copy
     * are replaced by it, or dropped when deleted or moved away; nodes created or moved here locally are appended to
     * the last page. A locally created folder lists its local children only.
     */
    private Outcome listChildren(HttpServletRequest request, HttpServletResponse response, String base,
                                 NodeLocation location, Trace trace) throws IOException {
        ObjectNode parentRecord = nodes.withLock(location.ref(), () -> liveRecord(request, response, location, trace));
        if (parentRecord == null) {
            return trace.outcome(response.getStatus());
        }
        String parentId = location.ref().guid();
        List<String> include = RestEntries.csv(Http.queryParameter(request, "include"));
        List<String> fields = RestEntries.csv(Http.queryParameter(request, "fields"));
        int skipCount = intParameter(request, "skipCount", 0);
        int maxItems = intParameter(request, "maxItems", 100);

        if (nodes.isLocalOrigin(parentRecord)) {
            List<ObjectNode> children = new ArrayList<>();
            for (ObjectNode child : localChildren(parentRecord, parentId)) {
                children.add(RestEntries.view(child, include, fields, true));
            }
            return nodes.respondJson(response, 200, RestEntries.list(children, skipCount, maxItems), trace);
        }

        Fetched fetched = nodes.fetchText(request, "GET", base + "/nodes/" + parentId + "/children"
                + Http.querySuffix(request), null, trace);
        JsonNode body = fetched.isSuccess() ? nodes.parseJson(fetched.text()) : null;
        if (!(body instanceof ObjectNode) || !(body.path("list").path("entries") instanceof ArrayNode entries)) {
            return nodes.relay(response, fetched, trace);
        }

        Set<String> listed = new HashSet<>();
        int dropped = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            String childId = entries.get(i).path("entry").path("id").asText();
            listed.add(childId);
            NodeRef childRef = NodeRef.ofId(childId);
            ObjectNode childRecord = childRef == null ? null : nodes.load(childRef);
            if (childRecord == null || entry(childRecord) == null) {
                continue;
            }
            ObjectNode child = entry(childRecord);
            if (nodes.isDeleted(childRecord) || !parentId.equals(child.path("parentId").asText())) {
                entries.remove(i);
                dropped++;
            } else {
                ((ObjectNode) entries.get(i)).set("entry", RestEntries.view(child, include, fields, true));
            }
        }
        ObjectNode pagination = body.path("list").path("pagination") instanceof ObjectNode p ? p : null;
        int added = 0;
        if (pagination == null || !pagination.path("hasMoreItems").asBoolean(false)) {
            for (ObjectNode child : localChildren(parentRecord, parentId)) {
                if (!listed.contains(child.path("id").asText())) {
                    entries.add(RestEntries.wrap(RestEntries.view(child, include, fields, true)));
                    added++;
                }
            }
        }
        if (pagination != null) {
            pagination.put("count", entries.size());
            if (pagination.has("totalItems")) {
                pagination.put("totalItems", pagination.get("totalItems").asInt() - dropped + added);
            }
        }
        return nodes.respondJson(response, 200, body, trace);
    }

    /** Live entries of the nodes created or moved under the parent locally. */
    private List<ObjectNode> localChildren(ObjectNode parentRecord, String parentId) throws IOException {
        List<ObjectNode> children = new ArrayList<>();
        for (JsonNode childId : parentRecord.path("localChildren")) {
            NodeRef childRef = NodeRef.ofId(childId.asText());
            ObjectNode childRecord = childRef == null ? null : nodes.load(childRef);
            ObjectNode child = childRecord == null ? null : entry(childRecord);
            if (child != null && !nodes.isDeleted(childRecord) && parentId.equals(child.path("parentId").asText())) {
                children.add(child);
            }
        }
        return children;
    }

    // ----------------------------------------------------------------------------------------------- writes

    private Outcome updateNode(HttpServletRequest request, HttpServletResponse response, NodeLocation location,
                               Trace trace) throws IOException {
        ObjectNode update = nodes.readJsonObject(request, trace);
        if (update == null) {
            return nodes.respondError(response, 400, "Invalid node update JSON", trace);
        }
        return nodes.withLock(location.ref(), () -> {
            ObjectNode record = liveRecord(request, response, location, trace);
            if (record == null) {
                return trace.outcome(response.getStatus());
            }
            ObjectNode entry = entry(record);
            RestEntries.applyUpdate(entry, update, nodes.userOrLocal(request));
            if (update.path("nodeType").isTextual()) {
                setFolder(entry, folderTypes.contains(entry.path("nodeType").asText()));
            }
            nodes.markLocallyModified(record);
            nodes.save(location.ref(), record);
            return respondEntry(request, response, 200, entry, trace);
        });
    }

    /** {@code PUT nodes/{id}/content}: the raw request body is the new content (query {@code name} renames). */
    private Outcome setContent(HttpServletRequest request, HttpServletResponse response, NodeLocation location,
                               Trace trace) throws IOException {
        ObjectNode record = liveRecord(request, response, location, trace);
        if (record == null) {
            return trace.outcome(response.getStatus());
        }
        ObjectNode entry = entry(record);
        if (entry.path("isFolder").asBoolean(false)) {
            return nodes.respondError(response, 400, "Cannot set content of a folder", trace);
        }
        String mimetype = Http.mediaType(request.getContentType());
        StoredContent content;
        try (InputStream in = request.getInputStream()) {
            content = nodes.writeContent(location.ref(), mimetype, in);
        }
        trace.requestBody("\"content (" + content.size() + " bytes, " + content.mimetype() + ")\"");
        nodes.recordContent(record, location.ref(), content);
        RestEntries.setContent(entry, content);
        String newName = Http.queryParameter(request, "name");
        if (newName != null && !newName.isBlank()) {
            entry.put("name", newName);
        }
        RestEntries.touch(entry, nodes.userOrLocal(request));
        nodes.markLocallyModified(record);
        nodes.save(location.ref(), record);
        return respondEntry(request, response, 200, entry, trace);
    }

    private Outcome deleteNode(HttpServletRequest request, HttpServletResponse response, NodeLocation location,
                               Trace trace) throws IOException {
        ObjectNode existing = nodes.load(location.ref());
        if (existing != null && nodes.isDeleted(existing)) {
            return nodes.respondError(response, 404, "The entity with id: " + location.ref().guid() + " was not found", trace);
        }
        ObjectNode record;
        switch (nodes.loadOrCopy(request, response, location, trace, existing, false)) {
            case Copied c -> record = c.record();
            case Failed f -> {
                return f.outcome();
            }
        }
        nodes.markDeleted(record);
        nodes.save(location.ref(), record);
        return nodes.respondEmpty(response, 204, trace);
    }

    /**
     * {@code POST nodes/{id}/children}: JSON {@code NodeBodyCreate} ({@code name}, {@code nodeType},
     * {@code aspectNames}, {@code properties}) or multipart upload ({@code filedata}, {@code name},
     * {@code nodeType}, property fields such as {@code cm:title}). {@code relativePath} is not supported.
     */
    private Outcome createChild(HttpServletRequest request, HttpServletResponse response, String base,
                                NodeLocation parentLocation, Trace trace) throws IOException {
        NodeRef ref = NodeRef.ofId(UUID.randomUUID().toString());
        String name;
        String nodeType;
        JsonNode aspectNames = null;
        ObjectNode properties = JSON.objectNode();
        StoredContent content = null;

        if (Http.isMultipartFormData(request)) {
            Map<String, String> fields = new LinkedHashMap<>();
            Part file = null;
            try {
                for (Part part : request.getParts()) {
                    if (part.getSubmittedFileName() != null) {
                        if (part.getName().equals("filedata") && file == null) {
                            file = part;
                        }
                    } else {
                        fields.put(part.getName(), new String(part.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            } catch (ServletException | IllegalStateException e) {
                return nodes.respondError(response, 400, "Invalid multipart request: " + e.getMessage(), trace);
            }
            if (file == null) {
                return nodes.respondError(response, 400, "Content must be supplied as a multipart 'filedata' field", trace);
            }
            if (fields.containsKey("relativePath")) {
                return nodes.respondError(response, 400, "relativePath is not supported by the local document store", trace);
            }
            name = fields.getOrDefault("name", file.getSubmittedFileName());
            nodeType = fields.getOrDefault("nodeType", "cm:content");
            if (fields.containsKey("aspectNames")) {
                ArrayNode aspects = JSON.arrayNode();
                RestEntries.csv(fields.get("aspectNames")).forEach(aspects::add);
                aspectNames = aspects;
            }
            RestEntries.nonReservedProperties(fields).forEach(properties::put);
            try (InputStream in = file.getInputStream()) {
                content = nodes.writeContent(ref, Http.mediaType(file.getContentType()), in);
            }
            trace.requestBody("\"multipart upload (" + content.size() + " bytes, " + content.mimetype() + ") to "
                    + parentLocation.ref().guid() + "\"");
        } else {
            ObjectNode body = nodes.readJsonObject(request, trace);
            if (body == null) {
                return nodes.respondError(response, 400, "Invalid create node JSON", trace);
            }
            name = body.path("name").asText(null);
            nodeType = body.path("nodeType").asText(null);
            if (name == null || name.isBlank()) {
                return nodes.respondError(response, 400, "Property 'name' is required", trace);
            }
            if (nodeType == null || nodeType.isBlank()) {
                return nodes.respondError(response, 400, "Property 'nodeType' is required", trace);
            }
            if (body.has("relativePath")) {
                return nodes.respondError(response, 400, "relativePath is not supported by the local document store", trace);
            }
            aspectNames = body.get("aspectNames");
            if (body.path("properties").isObject()) {
                properties = (ObjectNode) body.get("properties");
            }
        }

        boolean folder = folderTypes.contains(nodeType);
        if (!folder && content == null) {
            content = nodes.writeContent(ref, Http.OCTET_STREAM, InputStream.nullInputStream());
        }
        final StoredContent stored = content;
        final String finalName = name;
        final String finalType = nodeType;
        final JsonNode finalAspects = aspectNames;
        final ObjectNode finalProperties = properties;

        // Parent must exist (locally or on a backend)
        ObjectNode parent = nodes.withLock(parentLocation.ref(), () -> {
            ObjectNode parentRecord = liveRecord(request, response, parentLocation, trace);
            return parentRecord == null ? null : entry(parentRecord);
        });
        if (parent == null) {
            if (stored != null) {
                nodes.store().deleteContent(ref, stored.fileName());
            }
            return trace.outcome(response.getStatus());
        }
        if (!parent.path("isFolder").asBoolean(true)) {
            return nodes.respondError(response, 400, "Parent " + parent.path("id").asText() + " is not a folder", trace);
        }

        String user = nodes.userOrLocal(request);
        ObjectNode entry = RestEntries.newEntry(ref.guid(), finalName, finalType, folder, parent, user, finalAspects,
                finalProperties, stored);
        ObjectNode record = nodes.newLocalRecord(ref, RestEntries.wrap(entry));
        if (stored != null) {
            nodes.recordContent(record, ref, stored);
        }
        nodes.withLock(ref, () -> {
            nodes.save(ref, record);
            return null;
        });
        addLocalChild(parentLocation.ref(), ref.guid());
        System.out.printf("[%s] <=LOCAL=> Created node %s under %s%n", Instant.now(), ref.guid(), parent.path("id").asText());
        return respondEntry(request, response, 201, entry, trace);
    }

    /** {@code POST nodes/{id}/copy} / {@code move} with {@code {"targetParentId": ..., "name": ...}}. */
    private Outcome copyOrMove(HttpServletRequest request, HttpServletResponse response, String base,
                               NodeLocation sourceLocation, boolean move, Trace trace) throws IOException {
        ObjectNode body = nodes.readJsonObject(request, trace);
        NodeRef targetRef = body == null ? null : NodeRef.ofId(body.path("targetParentId").asText(""));
        if (targetRef == null) {
            return nodes.respondError(response, 400, "A valid 'targetParentId' is required", trace);
        }
        String newName = body.path("name").isTextual() ? body.get("name").asText() : null;
        NodeLocation targetLocation = location(base, targetRef);
        ObjectNode target = nodes.withLock(targetRef, () -> {
            ObjectNode targetRecord = liveRecord(request, response, targetLocation, trace);
            return targetRecord == null ? null : entry(targetRecord);
        });
        if (target == null) {
            return trace.outcome(response.getStatus());
        }
        if (!target.path("isFolder").asBoolean(true)) {
            return nodes.respondError(response, 400, "Target " + targetRef.guid() + " is not a folder", trace);
        }
        String user = nodes.userOrLocal(request);

        if (move) {
            String[] oldParent = new String[1];
            Outcome outcome = nodes.withLock(sourceLocation.ref(), () -> {
                ObjectNode record = liveRecord(request, response, sourceLocation, trace);
                if (record == null) {
                    return trace.outcome(response.getStatus());
                }
                ObjectNode entry = entry(record);
                oldParent[0] = entry.path("parentId").asText(null);
                RestEntries.inherit(entry, target);
                if (newName != null) {
                    entry.put("name", newName);
                }
                RestEntries.touch(entry, user);
                nodes.markLocallyModified(record);
                nodes.save(sourceLocation.ref(), record);
                return respondEntry(request, response, 200, entry, trace);
            });
            if (outcome.status() == 200) {
                addLocalChild(targetRef, sourceLocation.ref().guid());
                NodeRef oldParentRef = oldParent[0] == null ? null : NodeRef.ofId(oldParent[0]);
                if (oldParentRef != null && !oldParentRef.equals(targetRef)) {
                    removeLocalChild(oldParentRef, sourceLocation.ref().guid());
                }
            }
            return outcome;
        }

        NodeRef copyRef = NodeRef.ofId(UUID.randomUUID().toString());
        ObjectNode copy = nodes.withLock(sourceLocation.ref(), () -> {
            ObjectNode record = liveRecord(request, response, sourceLocation, trace);
            if (record == null) {
                return null;
            }
            if (!(record.get("content") instanceof ObjectNode) && !nodes.isLocalOrigin(record)) {
                nodes.fetchContent(request, sourceLocation, record, new Trace());
            }
            ObjectNode entry = entry(record).deepCopy();
            entry.put("id", copyRef.guid());
            if (newName != null) {
                entry.put("name", newName);
            }
            entry.put("createdAt", RestEntries.now());
            entry.set("createdByUser", RestEntries.user(user));
            RestEntries.touch(entry, user);
            entry.put("isLocked", false);
            RestEntries.inherit(entry, target);
            ObjectNode copyRecord = nodes.newLocalRecord(copyRef, RestEntries.wrap(entry));
            StoredContent content = nodes.copyContent(sourceLocation.ref(), nodes.storedContent(record), copyRef);
            if (content != null) {
                nodes.recordContent(copyRecord, copyRef, content);
            } else {
                nodes.markNoContent(copyRecord, copyRef);
            }
            nodes.save(copyRef, copyRecord);
            return entry;
        });
        if (copy == null) {
            return trace.outcome(response.getStatus());
        }
        addLocalChild(targetRef, copyRef.guid());
        return respondEntry(request, response, 201, copy, trace);
    }

    private Outcome lock(HttpServletRequest request, HttpServletResponse response, NodeLocation location,
                         boolean locked, Trace trace) throws IOException {
        String text = nodes.readBody(request, trace);
        ObjectNode record = liveRecord(request, response, location, trace);
        if (record == null) {
            return trace.outcome(response.getStatus());
        }
        ObjectNode entry = entry(record);
        RestEntries.setLocked(entry, locked, nodes.parseJson(text), nodes.userOrLocal(request));
        nodes.markLocallyModified(record);
        nodes.save(location.ref(), record);
        return respondEntry(request, response, 200, entry, trace);
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static NodeLocation location(String base, NodeRef ref) {
        String node = base + "/nodes/" + ref.guid();
        return new NodeLocation(ref, node + "?include=" + RestEntries.COPY_INCLUDES, node + "/content?attachment=false");
    }

    /**
     * Record of a live node with its entry, copying it when needed; null when the response (404 or the backend's
     * answer) has already been sent.
     */
    private ObjectNode liveRecord(HttpServletRequest request, HttpServletResponse response, NodeLocation location,
                                  Trace trace) throws IOException {
        ObjectNode existing = nodes.load(location.ref());
        if (existing != null && nodes.isDeleted(existing)) {
            nodes.respondError(response, 404, "The entity with id: " + location.ref().guid() + " was not found", trace);
            return null;
        }
        Copy copy = nodes.loadOrCopy(request, response, location, trace, existing, true);
        if (copy instanceof Copied c && entry(c.record()) != null) {
            return c.record();
        }
        if (copy instanceof Copied) {
            nodes.respondError(response, 502, "Unexpected node document for " + location.ref().guid(), trace);
        }
        return null;
    }

    private ObjectNode entry(ObjectNode record) {
        ObjectNode document = nodes.nodeDocument(record);
        return document != null && document.get("entry") instanceof ObjectNode entry ? entry : null;
    }

    private Outcome respondEntry(HttpServletRequest request, HttpServletResponse response, int status,
                                 ObjectNode entry, Trace trace) throws IOException {
        return nodes.respondJson(response, status, RestEntries.wrap(RestEntries.view(entry,
                RestEntries.csv(Http.queryParameter(request, "include")),
                RestEntries.csv(Http.queryParameter(request, "fields")), false)), trace);
    }

    private static void setFolder(ObjectNode entry, boolean folder) {
        entry.put("isFolder", folder);
        entry.put("isFile", !folder);
    }

    private void addLocalChild(NodeRef parentRef, String childId) throws IOException {
        nodes.withLock(parentRef, () -> {
            ObjectNode parent = nodes.load(parentRef);
            if (parent == null) {
                return null;
            }
            ArrayNode children = parent.get("localChildren") instanceof ArrayNode a ? a : parent.putArray("localChildren");
            for (JsonNode existing : children) {
                if (existing.asText().equals(childId)) {
                    return null;
                }
            }
            children.add(childId);
            nodes.markLocallyModified(parent);
            nodes.save(parentRef, parent);
            return null;
        });
    }

    private void removeLocalChild(NodeRef parentRef, String childId) throws IOException {
        nodes.withLock(parentRef, () -> {
            ObjectNode parent = nodes.load(parentRef);
            if (parent != null && parent.get("localChildren") instanceof ArrayNode children) {
                for (int i = children.size() - 1; i >= 0; i--) {
                    if (children.get(i).asText().equals(childId)) {
                        children.remove(i);
                    }
                }
                nodes.save(parentRef, parent);
            }
            return null;
        });
    }

    private static int intParameter(HttpServletRequest request, String name, int defaultValue) {
        try {
            String value = Http.queryParameter(request, name);
            return value == null ? defaultValue : Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
