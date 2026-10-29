package plg.gesraco.tools.proxy.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local store routing rule ({@code local-store.routes}): how requests matching {@code path} and {@code methods}
 * are handled, for APIs that have no {@link plg.gesraco.tools.proxy.store.DocumentApi} and are described in
 * configuration only.
 *
 * <p>{@code path} is a glob ({@code *} = one segment, {@code **} = any depth) whose {@code {name}} segments capture
 * one segment each. {@code key} names the local resource the rule reads or writes; it may use the captured variables
 * plus {@code {path}} (request path) and {@code {uri}} (path and query, the default). Rules sharing a key share the
 * resource, so a simulated write is visible to later reads.
 *
 * @param path        path glob with optional {@code {name}} captures
 * @param methods     HTTP methods (empty = all)
 * @param mode        what to do with a matching request
 * @param key         resource key template (null = {@code {uri}})
 * @param store       for {@link Mode#SIMULATE}: what happens to the resource
 * @param status      response status (null = mode default)
 * @param contentType response content type when {@code body} is set
 * @param body        fixed response body (null = mode default)
 * @param readOnly    for {@link Mode#PROXY}: the matched writes don't modify data (searches, bulk reads), so they
 *                    may reach Alfresco without {@code allow-write-proxy}
 */
public record RouteRule(String path, List<String> methods, Mode mode, String key, Store store, Integer status,
                        String contentType, String body, boolean readOnly) {

    /** How a matching request is handled. */
    public enum Mode {
        /** Relayed to Alfresco (primary, then fallback); a GET on a locally written resource is served locally. */
        PROXY,
        /** GET/HEAD: copied from Alfresco on first read, then served from the local copy. */
        CACHE,
        /** Answered locally, never sent to Alfresco; {@code store} says what happens to the resource. */
        SIMULATE,
        /** Refused ({@code status}, 403 by default). */
        BLOCK;

        public static Mode parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
    }

    /** Effect of a simulated request on its resource. */
    public enum Store {
        /** The request body becomes the resource (later reads return it). */
        BODY,
        /** The resource is marked deleted (later reads give 404). */
        DELETE,
        /** Nothing is stored; only the configured response is sent. */
        NONE;

        public static Store parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");

    public RouteRule {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("A local-store route needs a path");
        }
        if (mode == null) {
            throw new IllegalArgumentException("Local-store route " + path + " needs a mode (proxy, cache, simulate, block)");
        }
        methods = methods == null ? List.of() : methods.stream().map(m -> m.toUpperCase(Locale.ROOT)).toList();
        store = store == null ? (mode == Mode.SIMULATE ? defaultStore(methods) : Store.NONE) : store;
        if (mode == Mode.CACHE && (methods.isEmpty() || methods.stream().anyMatch(m -> !m.equals("GET") && !m.equals("HEAD")))) {
            throw new IllegalArgumentException("Local-store route " + path + ": mode cache applies to GET/HEAD only");
        }
    }

    private static Store defaultStore(List<String> methods) {
        if (methods.size() == 1 && methods.get(0).equals("DELETE")) {
            return Store.DELETE;
        }
        return methods.stream().anyMatch(m -> m.equals("PUT") || m.equals("POST") || m.equals("PATCH"))
                ? Store.BODY : Store.NONE;
    }

    /** Whether the rule lets a write request reach Alfresco. */
    public boolean proxiesWrites() {
        return mode == Mode.PROXY && !readOnly && (methods.isEmpty() || methods.stream().anyMatch(RouteRule::isWriteMethod));
    }

    public static boolean isWriteMethod(String method) {
        String m = method.toUpperCase(Locale.ROOT);
        return !m.equals("GET") && !m.equals("HEAD") && !m.equals("OPTIONS");
    }

    /**
     * Variables captured from {@code requestPath} (query ignored), or null when the request doesn't match.
     */
    public Map<String, String> match(String method, String requestPath) {
        if (!methods.isEmpty() && !methods.contains(method.toUpperCase(Locale.ROOT))) {
            return null;
        }
        int q = requestPath.indexOf('?');
        String clean = q < 0 ? requestPath : requestPath.substring(0, q);
        List<String> names = new ArrayList<>();
        Matcher m = toRegex(path, names).matcher(clean);
        if (!m.matches()) {
            return null;
        }
        Map<String, String> variables = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            variables.put(names.get(i), m.group(i + 1));
        }
        return variables;
    }

    /** The resource key of a matched request: {@code key} with its variables replaced. */
    public String resourceKey(Map<String, String> variables, String requestPath, String uri) {
        Matcher m = VARIABLE.matcher(key == null ? "{uri}" : key);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = switch (name) {
                case "path" -> requestPath;
                case "uri" -> uri;
                default -> variables.getOrDefault(name, "");
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Pattern toRegex(String glob, List<String> names) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (glob.startsWith("**", i)) {
                sb.append(".*");
                i += 2;
                continue;
            }
            if (c == '{') {
                int end = glob.indexOf('}', i);
                if (end > i + 1 && VARIABLE.matcher(glob.substring(i, end + 1)).matches()) {
                    names.add(glob.substring(i + 1, end));
                    sb.append("([^/]+)");
                    i = end + 1;
                    continue;
                }
            }
            switch (c) {
                case '*' -> sb.append("[^/]*");
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\', '?' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
            i++;
        }
        return Pattern.compile(sb.append('$').toString(), Pattern.CASE_INSENSITIVE);
    }

    /** Parses a YAML route entry. */
    public static RouteRule fromMap(Map<?, ?> def) {
        Object path = def.get("path");
        Object mode = def.get("mode");
        Object rawMethods = def.get("methods");
        List<String> methods = new ArrayList<>();
        if (rawMethods instanceof List<?> list) {
            list.forEach(m -> methods.add(String.valueOf(m).trim()));
        } else if (rawMethods != null) {
            methods.add(rawMethods.toString().trim());
        }
        Object contentType = def.get("content-type") != null ? def.get("content-type") : def.get("contentType");
        try {
            return new RouteRule(path == null ? null : path.toString(), methods,
                    mode == null ? null : Mode.parse(mode.toString()),
                    def.get("key") == null ? null : def.get("key").toString(),
                    def.get("store") == null ? null : Store.parse(def.get("store").toString()),
                    def.get("status") instanceof Number n ? n.intValue() : null,
                    contentType == null ? null : contentType.toString(),
                    def.get("body") == null ? null : def.get("body").toString(),
                    Boolean.TRUE.equals(def.get("read-only")) || Boolean.TRUE.equals(def.get("readOnly")));
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Local-store route")) {
                throw e;
            }
            throw new IllegalArgumentException("Invalid local-store route " + def + ": " + e.getMessage(), e);
        }
    }
}
