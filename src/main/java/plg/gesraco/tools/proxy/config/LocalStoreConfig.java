package plg.gesraco.tools.proxy.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local document store settings.
 *
 * <p>When {@code enabled}, nodes of the configured document APIs ({@code apis}) are copied to {@code directory} the
 * first time they are read (from the primary or fallback target) and served from that copy afterwards. Writes to
 * nodes are applied to the local copy only; no write request (POST/PUT/PATCH/DELETE) is ever forwarded to a backend,
 * except POSTs matching {@code readOnlyPostPaths} or the read-only POSTs declared by the document APIs (searches,
 * bulk reads). With {@code copyContentEagerly}, the document content is downloaded together with the node's metadata
 * instead of on its first content request.
 *
 * <p>{@code routes} describe, in configuration only, how other URLs are handled (proxy, cache, simulate, block):
 * see {@link RouteRule}. A route may only let writes reach Alfresco with {@code allowWriteProxy}.
 */
public record LocalStoreConfig(
        boolean enabled,
        Path directory,
        boolean copyContentEagerly,
        List<String> readOnlyPostPaths,
        List<DocumentApiConfig> apis,
        List<RouteRule> routes,
        boolean allowWriteProxy
) {

    /** Document API used when none is configured: the standard Alfresco REST API. */
    public static final String DEFAULT_API = "alfresco-rest";

    public LocalStoreConfig {
        directory = directory == null ? Path.of("local-store") : directory;
        readOnlyPostPaths = readOnlyPostPaths == null ? List.of() : List.copyOf(readOnlyPostPaths);
        apis = apis == null || apis.isEmpty() ? List.of(new DocumentApiConfig(DEFAULT_API, Map.of())) : List.copyOf(apis);
        routes = routes == null ? List.of() : List.copyOf(routes);
        if (!allowWriteProxy) {
            for (RouteRule route : routes) {
                if (route.proxiesWrites()) {
                    throw new IllegalArgumentException("Local-store route " + route.path() + " would send writes to "
                            + "Alfresco (mode proxy on " + (route.methods().isEmpty() ? "all methods" : route.methods())
                            + "). Restrict its methods, or set local-store.allow-write-proxy: true.");
                }
            }
        }
    }

    public static LocalStoreConfig disabled() {
        return new LocalStoreConfig(false, Path.of("local-store"), true, List.of(), List.of(), List.of(), false);
    }

    public LocalStoreConfig withEnabled(boolean value) {
        return new LocalStoreConfig(value, directory, copyContentEagerly, readOnlyPostPaths, apis, routes, allowWriteProxy);
    }

    public LocalStoreConfig withDirectory(Path value) {
        return new LocalStoreConfig(enabled, value, copyContentEagerly, readOnlyPostPaths, apis, routes, allowWriteProxy);
    }

    public LocalStoreConfig withCopyContentEagerly(boolean value) {
        return new LocalStoreConfig(enabled, directory, value, readOnlyPostPaths, apis, routes, allowWriteProxy);
    }

    public LocalStoreConfig withReadOnlyPostPaths(List<String> value) {
        return new LocalStoreConfig(enabled, directory, copyContentEagerly, value, apis, routes, allowWriteProxy);
    }

    public LocalStoreConfig withApis(List<DocumentApiConfig> value) {
        return new LocalStoreConfig(enabled, directory, copyContentEagerly, readOnlyPostPaths, value, routes, allowWriteProxy);
    }

    public LocalStoreConfig withRoutes(List<RouteRule> value) {
        return new LocalStoreConfig(enabled, directory, copyContentEagerly, readOnlyPostPaths, apis, value, allowWriteProxy);
    }

    public LocalStoreConfig withAllowWriteProxy(boolean value) {
        return new LocalStoreConfig(enabled, directory, copyContentEagerly, readOnlyPostPaths, apis, routes, value);
    }

    /**
     * Whether a request with {@code method} on {@code requestPath} would modify data on a backend, considering the
     * configured {@code readOnlyPostPaths} only (the document APIs add their own, see
     * {@code LocalDocuments#isWrite}).
     */
    public boolean isWrite(String method, String requestPath) {
        return isWrite(method, requestPath, readOnlyPostPaths);
    }

    public static boolean isWrite(String method, String requestPath, List<String> readOnlyPostPaths) {
        String m = method.toUpperCase(Locale.ROOT);
        if (m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS")) {
            return false;
        }
        return !(m.equals("POST")
                && readOnlyPostPaths.stream().anyMatch(p -> BlockedRequestRule.matchesPathPattern(p, requestPath)));
    }

    /**
     * A document API served by the local store.
     *
     * @param name     provider name ({@code alfresco-rest} is built in; others come from plugins)
     * @param settings provider-specific settings (the YAML map of the entry, {@code name} excluded)
     */
    public record DocumentApiConfig(String name, Map<String, Object> settings) {
        public DocumentApiConfig {
            settings = settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        }

        public String setting(String key, String defaultValue) {
            Object value = settings.get(key);
            return value == null ? defaultValue : value.toString();
        }

        public boolean setting(String key, boolean defaultValue) {
            Object value = settings.get(key);
            return value instanceof Boolean b ? b : value == null ? defaultValue : Boolean.parseBoolean(value.toString());
        }
    }
}
