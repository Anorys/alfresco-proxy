package plg.gesraco.tools.proxy.store.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import plg.gesraco.tools.proxy.store.StoredContent;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON model of the Alfresco REST API v1 ({@code /api/-default-/public/alfresco/versions/1}) node entries:
 * field selection ({@code include} / {@code fields}), local updates ({@code NodeBodyUpdate}), creation
 * ({@code NodeBodyCreate}), lists and errors.
 *
 * <p>Properties are keyed by prefixed QNames ({@code cm:title}) with plain (single or array) values.
 */
final class RestEntries {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    /** Fields of a node entry returned by {@code GET nodes/{id}} without {@code include}. */
    static final List<String> NODE_FIELDS = List.of("id", "name", "nodeType", "isFolder", "isFile", "modifiedAt",
            "modifiedByUser", "createdAt", "createdByUser", "parentId", "content", "aspectNames", "properties");

    /** Fields of an entry in a node list ({@code GET nodes/{id}/children}) without {@code include}. */
    static final List<String> LIST_FIELDS = List.of("id", "name", "nodeType", "isFolder", "isFile", "modifiedAt",
            "modifiedByUser", "createdAt", "createdByUser", "parentId", "content");

    /** Optional fields requested when a node is copied, so later {@code include}s are answered locally. */
    static final String COPY_INCLUDES = "allowableOperations,association,isLink,isLocked,path,permissions";

    private RestEntries() {
    }

    static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(DATE);
    }

    static List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Copy of {@code entry} restricted to the default fields ({@link #NODE_FIELDS} or {@link #LIST_FIELDS}) plus
     * {@code include}, or to {@code fields} (plus {@code include}) when given.
     */
    static ObjectNode view(ObjectNode entry, List<String> include, List<String> fields, boolean listing) {
        Set<String> keep = new LinkedHashSet<>(fields.isEmpty() ? (listing ? LIST_FIELDS : NODE_FIELDS) : fields);
        keep.addAll(include);
        ObjectNode view = JSON.objectNode();
        for (String field : keep) {
            JsonNode value = entry.get(field);
            if (value != null) {
                view.set(field, value.deepCopy());
            }
        }
        return view;
    }

    static ObjectNode wrap(JsonNode entry) {
        ObjectNode body = JSON.objectNode();
        body.set("entry", entry);
        return body;
    }

    static ObjectNode user(String id) {
        ObjectNode user = JSON.objectNode();
        user.put("id", id);
        user.put("displayName", id);
        return user;
    }

    /** Sets {@code modifiedAt} / {@code modifiedByUser}. */
    static void touch(ObjectNode entry, String user) {
        entry.put("modifiedAt", now());
        entry.set("modifiedByUser", user(user));
    }

    /**
     * Applies a {@code NodeBodyUpdate} ({@code name}, {@code nodeType}, {@code aspectNames}, {@code properties},
     * {@code permissions}) to {@code entry} in place. Properties are merged; a null value removes a property.
     */
    static void applyUpdate(ObjectNode entry, JsonNode update, String user) {
        if (update.path("name").isTextual()) {
            entry.put("name", update.get("name").asText());
        }
        if (update.path("nodeType").isTextual()) {
            entry.put("nodeType", update.get("nodeType").asText());
        }
        if (update.path("aspectNames").isArray()) {
            entry.set("aspectNames", update.get("aspectNames").deepCopy());
        }
        if (update.path("properties").isObject()) {
            ObjectNode properties = entry.get("properties") instanceof ObjectNode p ? p : entry.putObject("properties");
            update.get("properties").fields().forEachRemaining(property -> {
                if (property.getValue().isNull()) {
                    properties.remove(property.getKey());
                } else {
                    properties.set(property.getKey(), property.getValue().deepCopy());
                }
            });
        }
        if (update.path("permissions").isObject()) {
            ObjectNode permissions = entry.get("permissions") instanceof ObjectNode p ? p : entry.putObject("permissions");
            JsonNode requested = update.get("permissions");
            if (requested.has("isInheritanceEnabled")) {
                permissions.set("isInheritanceEnabled", requested.get("isInheritanceEnabled").deepCopy());
            }
            if (requested.has("locallySet")) {
                permissions.set("locallySet", requested.get("locallySet").deepCopy());
            }
        }
        touch(entry, user);
    }

    /** Describes new content in {@code entry.content}, keeping the encoding and mime type name when applicable. */
    static void setContent(ObjectNode entry, StoredContent content) {
        JsonNode previous = entry.path("content");
        ObjectNode node = JSON.objectNode();
        node.put("mimeType", content.mimetype());
        if (previous.path("mimeType").asText("").equals(content.mimetype()) && previous.has("mimeTypeName")) {
            node.set("mimeTypeName", previous.get("mimeTypeName").deepCopy());
        }
        node.put("sizeInBytes", content.size());
        node.put("encoding", previous.path("encoding").asText("UTF-8"));
        entry.set("content", node);
    }

    /**
     * Builds the entry of a node created locally under {@code parent}: permissions and allowable operations are
     * inherited from the parent, the path is derived from the parent's path when known.
     */
    static ObjectNode newEntry(String id, String name, String nodeType, boolean folder, ObjectNode parent,
                               String user, JsonNode aspectNames, JsonNode properties, StoredContent content) {
        String now = now();
        ObjectNode entry = JSON.objectNode();
        entry.put("id", id);
        entry.put("name", name);
        entry.put("nodeType", nodeType);
        entry.put("isFolder", folder);
        entry.put("isFile", !folder);
        entry.put("createdAt", now);
        entry.set("createdByUser", user(user));
        entry.put("modifiedAt", now);
        entry.set("modifiedByUser", user(user));
        entry.put("parentId", parent.path("id").asText());

        Set<String> aspects = new LinkedHashSet<>();
        if (aspectNames != null && aspectNames.isArray()) {
            aspectNames.forEach(a -> aspects.add(a.asText()));
        }
        aspects.add("cm:auditable");
        ArrayNode aspectArray = entry.putArray("aspectNames");
        aspects.forEach(aspectArray::add);

        ObjectNode props = entry.putObject("properties");
        if (properties != null && properties.isObject()) {
            properties.fields().forEachRemaining(p -> {
                if (!p.getValue().isNull()) {
                    props.set(p.getKey(), p.getValue().deepCopy());
                }
            });
        }
        if (content != null) {
            setContent(entry, content);
        }
        entry.put("isLocked", false);
        entry.put("isLink", false);
        inherit(entry, parent);
        return entry;
    }

    /** Re-parents {@code entry}: parent id, path, inherited permissions and allowable operations. */
    static void inherit(ObjectNode entry, ObjectNode parent) {
        entry.put("parentId", parent.path("id").asText());
        if (parent.get("permissions") instanceof ObjectNode parentPermissions) {
            ObjectNode permissions = JSON.objectNode();
            permissions.put("isInheritanceEnabled", true);
            ArrayNode inherited = permissions.putArray("inherited");
            parentPermissions.path("inherited").forEach(p -> inherited.add(p.deepCopy()));
            parentPermissions.path("locallySet").forEach(p -> inherited.add(p.deepCopy()));
            if (parentPermissions.has("settable")) {
                permissions.set("settable", parentPermissions.get("settable").deepCopy());
            }
            entry.set("permissions", permissions);
        }
        if (parent.has("allowableOperations")) {
            entry.set("allowableOperations", parent.get("allowableOperations").deepCopy());
        }
        if (parent.get("path") instanceof ObjectNode parentPath) {
            ObjectNode path = JSON.objectNode();
            String parentPathName = parentPath.path("name").asText("");
            path.put("name", parentPathName + "/" + parent.path("name").asText());
            path.put("isComplete", parentPath.path("isComplete").asBoolean(true));
            ArrayNode elements = path.putArray("elements");
            parentPath.path("elements").forEach(e -> elements.add(e.deepCopy()));
            ObjectNode element = elements.addObject();
            element.put("id", parent.path("id").asText());
            element.put("name", parent.path("name").asText());
            element.put("nodeType", parent.path("nodeType").asText());
            if (parent.has("aspectNames")) {
                element.set("aspectNames", parent.get("aspectNames").deepCopy());
            }
            entry.set("path", path);
        } else {
            entry.remove("path");
        }
    }

    /** Applies {@code POST nodes/{id}/lock} ({@code type}, {@code lifetime}) or unlock to {@code entry}. */
    static void setLocked(ObjectNode entry, boolean locked, JsonNode body, String user) {
        ArrayNode aspects = entry.get("aspectNames") instanceof ArrayNode a ? a : entry.putArray("aspectNames");
        ObjectNode properties = entry.get("properties") instanceof ObjectNode p ? p : entry.putObject("properties");
        removeValue(aspects, "cm:lockable");
        for (String property : List.of("cm:lockType", "cm:lockOwner", "cm:lockLifetime", "cm:expiryDate")) {
            properties.remove(property);
        }
        if (locked) {
            aspects.add("cm:lockable");
            String type = body != null && body.path("type").isTextual() ? body.get("type").asText() : "ALLOW_OWNER_CHANGES";
            properties.put("cm:lockType", "FULL".equals(type) ? "READ_ONLY_LOCK" : "WRITE_LOCK");
            properties.set("cm:lockOwner", user(user));
            properties.put("cm:lockLifetime",
                    body != null && body.path("lifetime").isTextual() ? body.get("lifetime").asText() : "PERSISTENT");
        }
        entry.put("isLocked", locked);
    }

    private static void removeValue(ArrayNode array, String value) {
        for (Iterator<JsonNode> it = array.elements(); it.hasNext(); ) {
            if (value.equals(it.next().asText())) {
                it.remove();
            }
        }
    }

    /** A REST list: {@code {"list":{"pagination":{...},"entries":[{"entry":...}]}}}. */
    static ObjectNode list(List<? extends JsonNode> entries, int skipCount, int maxItems) {
        List<JsonNode> page = new ArrayList<>();
        for (int i = skipCount; i < entries.size() && page.size() < maxItems; i++) {
            page.add(entries.get(i));
        }
        ObjectNode body = JSON.objectNode();
        ObjectNode list = body.putObject("list");
        ObjectNode pagination = list.putObject("pagination");
        pagination.put("count", page.size());
        pagination.put("hasMoreItems", skipCount + page.size() < entries.size());
        pagination.put("totalItems", entries.size());
        pagination.put("skipCount", skipCount);
        pagination.put("maxItems", maxItems);
        ArrayNode array = list.putArray("entries");
        page.forEach(e -> array.add(wrap(e)));
        return body;
    }

    static ObjectNode emptyList() {
        return list(List.of(), 0, 100);
    }

    /** Error body in the REST API format. */
    static String error(int status, String message) {
        String key = switch (status) {
            case 400 -> "framework.exception.InvalidArgument";
            case 403 -> "framework.exception.PermissionDenied";
            case 404 -> "framework.exception.EntityNotFound";
            case 409 -> "framework.exception.ConstraintViolated";
            default -> "framework.exception.ApiDefault";
        };
        ObjectNode body = JSON.objectNode();
        ObjectNode error = body.putObject("error");
        error.put("errorKey", key);
        error.put("statusCode", status);
        error.put("briefSummary", message);
        error.put("stackTrace", "For security reasons the stack trace is no longer displayed, but the property is kept for previous versions");
        error.put("descriptionURL", "https://api-explorer.alfresco.com");
        return body.toString();
    }

    /** Names of the multipart fields of {@code POST nodes/{id}/children} that aren't node properties. */
    static final Set<String> RESERVED_UPLOAD_FIELDS = Set.of("filedata", "name", "nodeType", "relativePath",
            "overwrite", "autoRename", "majorVersion", "versioningEnabled", "comment", "renditions", "include",
            "fields", "aspectNames");

    static Map<String, String> nonReservedProperties(Map<String, String> fields) {
        Map<String, String> properties = new java.util.LinkedHashMap<>();
        fields.forEach((k, v) -> {
            if (!RESERVED_UPLOAD_FIELDS.contains(k) && k.contains(":")) {
                properties.put(k, v);
            }
        });
        return properties;
    }
}
