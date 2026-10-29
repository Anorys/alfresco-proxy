package plg.gesraco.tools.proxy.config;

import org.yaml.snakeyaml.Yaml;
import plg.gesraco.tools.proxy.config.LocalStoreConfig.DocumentApiConfig;
import plg.gesraco.tools.proxy.interceptor.DirectRequestHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Complete, immutable reverse proxy configuration. Create one with {@link #builder()}, derive variants with
 * {@link #toBuilder()} or the {@code withXxx(...)} methods.
 *
 * <p>Supports configuration via:
 * <ol>
 *   <li>YAML files ({@code application.yaml}, {@code application.yml}, or custom file via {@code --config=<path>})</li>
 *   <li>CLI arguments ({@code --port=...}, {@code --target=...}, {@code --target-auth=...},
 *       {@code --fallback-target=...}, {@code --fallback-target-auth=...}, {@code --log-file=...},
 *       {@code --config=...})</li>
 *   <li>Environment variables ({@code PROXY_PORT}, {@code TARGET_URL}, {@code TARGET_AUTH},
 *       {@code FALLBACK_TARGET_URL}, {@code FALLBACK_TARGET_AUTH}, {@code LOG_FILE}, {@code PROXY_CONFIG})</li>
 *   <li>JVM system properties ({@code proxy.port}, {@code target.url}, {@code target.auth},
 *       {@code fallback.target.url}, {@code fallback.target.auth}, {@code log.file}, {@code proxy.config})</li>
 * </ol>
 */
public final class ProxyConfig {

    /**
     * Default sensitive header names to redact in logs.
     */
    public static final Set<String> DEFAULT_SENSITIVE_HEADERS =
            Set.of("authorization", "cookie", "set-cookie", "x-auth-token", "alf_ticket");

    public static final String DEFAULT_TARGET_URL = "http://gesraco-alfresco7.afeliodev.local:8080";
    public static final Set<Integer> DEFAULT_FALLBACK_STATUSES = Set.of(404, 500, 502, 503, 504);
    public static final int DEFAULT_MAX_LOGGABLE_TEXT_BYTES = 512 * 1024;

    private final int localPort;
    private final String targetBaseUrl;
    private final String targetBasicAuth;
    private final List<BlockedRequestRule> targetBlockedRequests;
    private final String fallbackTargetBaseUrl;
    private final String fallbackTargetBasicAuth;
    private final List<BlockedRequestRule> fallbackTargetBlockedRequests;
    private final Set<Integer> fallbackHttpStatuses;
    private final Path logFile;
    private final int maxLoggableTextBytes;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final boolean addForwardedHeaders;
    private final boolean redactSensitiveHeaders;
    private final Set<String> sensitiveHeaders;
    private final List<DirectRequestHandler> directHandlers;
    private final List<DirectResponseDefinition> directResponseDefinitions;
    private final LocalStoreConfig localStore;
    private final Path pluginsDirectory;

    private ProxyConfig(Builder b) {
        if (b.localPort < 1 || b.localPort > 65535) {
            throw new IllegalArgumentException("Invalid local port: " + b.localPort);
        }
        if (b.targetBaseUrl == null || b.targetBaseUrl.isBlank()) {
            throw new IllegalArgumentException("Target URL cannot be blank");
        }
        if (b.fallbackTargetBaseUrl != null && b.fallbackTargetBaseUrl.isBlank()) {
            throw new IllegalArgumentException("Fallback target URL cannot be blank if provided");
        }
        localPort = b.localPort;
        targetBaseUrl = b.targetBaseUrl;
        targetBasicAuth = b.targetBasicAuth;
        targetBlockedRequests = List.copyOf(b.targetBlockedRequests);
        fallbackTargetBaseUrl = b.fallbackTargetBaseUrl;
        fallbackTargetBasicAuth = b.fallbackTargetBasicAuth;
        fallbackTargetBlockedRequests = List.copyOf(b.fallbackTargetBlockedRequests);
        fallbackHttpStatuses = Set.copyOf(b.fallbackHttpStatuses);
        logFile = Objects.requireNonNull(b.logFile, "logFile");
        maxLoggableTextBytes = b.maxLoggableTextBytes;
        connectTimeout = Objects.requireNonNull(b.connectTimeout, "connectTimeout");
        requestTimeout = Objects.requireNonNull(b.requestTimeout, "requestTimeout");
        addForwardedHeaders = b.addForwardedHeaders;
        redactSensitiveHeaders = b.redactSensitiveHeaders;
        sensitiveHeaders = Set.copyOf(b.sensitiveHeaders);
        directHandlers = List.copyOf(b.directHandlers);
        directResponseDefinitions = List.copyOf(b.directResponseDefinitions);
        localStore = Objects.requireNonNull(b.localStore, "localStore");
        pluginsDirectory = b.pluginsDirectory;
    }

    /** Default configuration. */
    public ProxyConfig() {
        this(new Builder());
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    // ------------------------------------------------------------------------------------------- accessors

    public int localPort() { return localPort; }
    public String targetBaseUrl() { return targetBaseUrl; }
    public String targetBasicAuth() { return targetBasicAuth; }
    public List<BlockedRequestRule> targetBlockedRequests() { return targetBlockedRequests; }
    public String fallbackTargetBaseUrl() { return fallbackTargetBaseUrl; }
    public String fallbackTargetBasicAuth() { return fallbackTargetBasicAuth; }
    public List<BlockedRequestRule> fallbackTargetBlockedRequests() { return fallbackTargetBlockedRequests; }
    public Set<Integer> fallbackHttpStatuses() { return fallbackHttpStatuses; }
    public Path logFile() { return logFile; }
    public int maxLoggableTextBytes() { return maxLoggableTextBytes; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration requestTimeout() { return requestTimeout; }
    public boolean addForwardedHeaders() { return addForwardedHeaders; }
    public boolean redactSensitiveHeaders() { return redactSensitiveHeaders; }
    public Set<String> sensitiveHeaders() { return sensitiveHeaders; }
    public List<DirectRequestHandler> directHandlers() { return directHandlers; }
    public List<DirectResponseDefinition> directResponseDefinitions() { return directResponseDefinitions; }
    public LocalStoreConfig localStore() { return localStore; }

    /** Directory scanned for plugin jars (extra document APIs), or null. Missing directories are ignored. */
    public Path pluginsDirectory() { return pluginsDirectory; }

    public String normalizedTargetBaseUrl() {
        return stripTrailingSlashes(targetBaseUrl);
    }

    public String normalizedFallbackTargetBaseUrl() {
        return fallbackTargetBaseUrl == null ? null : stripTrailingSlashes(fallbackTargetBaseUrl);
    }

    public String normalizedTargetBasicAuth() {
        return formatBasicAuth(targetBasicAuth);
    }

    public String normalizedFallbackTargetBasicAuth() {
        return formatBasicAuth(fallbackTargetBasicAuth);
    }

    public Set<String> normalizedSensitiveHeaders() {
        Set<String> result = new LinkedHashSet<>();
        sensitiveHeaders.forEach(h -> result.add(h.toLowerCase(Locale.ROOT)));
        return result;
    }

    // ------------------------------------------------------------------------------------------ withXxx

    /**
     * Creates a new {@link ProxyConfig} including the specified {@link DirectRequestHandler}.
     */
    public ProxyConfig withDirectHandler(DirectRequestHandler handler) {
        return toBuilder().addDirectHandler(handler).build();
    }

    /**
     * Convenience method to register a direct JSON response route programmatically ({@code method} null = any).
     */
    public ProxyConfig withDirectJson(String path, String method, int status, String jsonContent) {
        return withDirectHandler(DirectRequestHandler.respondJson(path, method, status, jsonContent));
    }

    public ProxyConfig withDirectJson(String path, String jsonContent) {
        return withDirectJson(path, null, 200, jsonContent);
    }

    /**
     * Convenience method to register a direct plain text response route programmatically.
     */
    public ProxyConfig withDirectText(String path, String method, int status, String text) {
        return withDirectHandler(DirectRequestHandler.respondText(path, method, status, text));
    }

    public ProxyConfig withDirectText(String path, String text) {
        return withDirectText(path, null, 200, text);
    }

    /**
     * Creates a copy with custom Basic Authorization credentials for the primary target.
     */
    public ProxyConfig withTargetAuth(String basicAuth) {
        return toBuilder().targetBasicAuth(basicAuth).build();
    }

    /**
     * Creates a copy with username and password Basic Auth for the primary target.
     */
    public ProxyConfig withTargetAuth(String username, String password) {
        return withTargetAuth(formatBasicAuth(username, password));
    }

    /**
     * Creates a copy with custom Basic Authorization credentials for the fallback target.
     */
    public ProxyConfig withFallbackTargetAuth(String basicAuth) {
        return toBuilder().fallbackTargetBasicAuth(basicAuth).build();
    }

    /**
     * Creates a copy with username and password Basic Auth for the fallback target.
     */
    public ProxyConfig withFallbackTargetAuth(String username, String password) {
        return withFallbackTargetAuth(formatBasicAuth(username, password));
    }

    /**
     * Appends a blocking rule for the primary target.
     */
    public ProxyConfig withTargetBlockedRule(BlockedRequestRule rule) {
        Builder b = toBuilder();
        b.targetBlockedRequests.add(rule);
        return b.build();
    }

    /**
     * Appends a blocking rule for the fallback target.
     */
    public ProxyConfig withFallbackTargetBlockedRule(BlockedRequestRule rule) {
        Builder b = toBuilder();
        b.fallbackTargetBlockedRequests.add(rule);
        return b.build();
    }

    /**
     * Enables the local document store in {@code directory}.
     */
    public ProxyConfig withLocalStore(Path directory) {
        return toBuilder().localStore(localStore.withEnabled(true).withDirectory(directory)).build();
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static String stripTrailingSlashes(String url) {
        String result = url;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * Formats or normalizes credentials into a standard HTTP "Basic &lt;base64&gt;" header value.
     * Accepts:
     * <ul>
     *   <li>A Map containing "username" / "password" (or "user" / "pass")</li>
     *   <li>A "user:password" string (will be base64-encoded)</li>
     *   <li>A raw base64 string (will be prefixed with "Basic ")</li>
     *   <li>An existing "Basic &lt;base64&gt;" header value</li>
     * </ul>
     */
    public static String formatBasicAuth(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> map) {
            Object user = map.get("username") != null ? map.get("username") : map.get("user");
            Object pass = map.get("password") != null ? map.get("password") : map.get("pass");
            return formatBasicAuth(user == null ? null : user.toString(), pass == null ? null : pass.toString());
        }
        String str = raw.toString().trim();
        if (str.isBlank()) {
            return null;
        }
        if (str.regionMatches(true, 0, "Basic ", 0, 6)) {
            String token = str.substring(6).trim();
            return token.isEmpty() ? null : "Basic " + token;
        }
        if (str.contains(":")) {
            return "Basic " + Base64.getEncoder().encodeToString(str.getBytes(StandardCharsets.UTF_8));
        }
        return "Basic " + str;
    }

    /**
     * Formats separate username and password into a standard HTTP "Basic &lt;base64&gt;" header value.
     */
    public static String formatBasicAuth(String username, String password) {
        String u = username == null ? "" : username.trim();
        String p = password == null ? "" : password.trim();
        if (u.isEmpty() && p.isEmpty()) {
            return null;
        }
        return "Basic " + Base64.getEncoder().encodeToString((u + ":" + p).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parses a list of blocked request rule definitions from YAML configuration.
     */
    public static List<BlockedRequestRule> parseBlockedRules(Object raw) {
        List<BlockedRequestRule> rules = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return rules;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                String path = trimToNull(map.get("path"));
                List<String> methods = parseMethods(map.get("methods") != null ? map.get("methods") : map.get("method"));
                int status = map.get("status") instanceof Number n ? n.intValue() : 403;
                Object message = map.get("message");
                if (path != null || !methods.isEmpty()) {
                    rules.add(new BlockedRequestRule(path, methods, status,
                            message == null ? BlockedRequestRule.DEFAULT_MESSAGE : message.toString()));
                }
            } else if (item instanceof String s) {
                String str = s.trim();
                rules.add(str.startsWith("/")
                        ? BlockedRequestRule.ofPath(str)
                        : BlockedRequestRule.ofMethods(str.toUpperCase(Locale.ROOT)));
            }
        }
        return rules;
    }

    private static List<String> parseMethods(Object raw) {
        List<String> methods = new ArrayList<>();
        if (raw instanceof List<?> list) {
            list.stream().filter(Objects::nonNull).forEach(m -> methods.add(m.toString().trim().toUpperCase(Locale.ROOT)));
        } else if (raw instanceof String s) {
            Arrays.stream(s.split(",")).map(String::trim).filter(m -> !m.isEmpty())
                    .forEach(m -> methods.add(m.toUpperCase(Locale.ROOT)));
        }
        return methods;
    }

    // ------------------------------------------------------------------------------------------ loading

    /**
     * Loads {@link ProxyConfig} from a YAML formatted string.
     */
    public static ProxyConfig fromYaml(String yamlContent) {
        Object raw = new Yaml().load(yamlContent);
        return parseYamlMap(raw instanceof Map<?, ?> map ? map : Map.of());
    }

    /**
     * Loads {@link ProxyConfig} from a YAML input stream.
     */
    public static ProxyConfig fromInputStream(InputStream inputStream) {
        Object raw = new Yaml().load(inputStream);
        return parseYamlMap(raw instanceof Map<?, ?> map ? map : Map.of());
    }

    /**
     * Loads {@link ProxyConfig} from a file path.
     */
    public static ProxyConfig fromYamlFile(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Configuration file not found: " + path);
        }
        try (InputStream in = Files.newInputStream(path)) {
            return fromInputStream(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Loads {@link ProxyConfig} from a classpath resource (e.g. {@code application.yaml}), or returns null.
     */
    public static ProxyConfig fromClasspath(String resourceName) {
        InputStream stream = ProxyConfig.class.getClassLoader().getResourceAsStream(resourceName);
        if (stream == null) {
            stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName);
        }
        if (stream == null) {
            return null;
        }
        try (InputStream in = stream) {
            return fromInputStream(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Automatically locates and loads configuration in priority order:
     * <ol>
     *   <li>Explicit file path (if specified)</li>
     *   <li>{@code ./application.yaml} (if present in working directory)</li>
     *   <li>{@code ./application.yml} (if present in working directory)</li>
     *   <li>Classpath resource {@code application.yaml} / {@code application.yml}</li>
     *   <li>Default {@link ProxyConfig}</li>
     * </ol>
     */
    public static ProxyConfig load(Path explicitFile) {
        if (explicitFile != null) {
            return fromYamlFile(explicitFile);
        }
        for (String local : List.of("application.yaml", "application.yml")) {
            Path path = Path.of(local);
            if (Files.exists(path)) {
                return fromYamlFile(path);
            }
        }
        for (String resource : List.of("application.yaml", "application.yml")) {
            ProxyConfig config = fromClasspath(resource);
            if (config != null) {
                return config;
            }
        }
        return new ProxyConfig();
    }

    public static ProxyConfig load() {
        return load(null);
    }

    /**
     * Parses command line arguments and environment variables, layering them over YAML configuration.
     */
    public static ProxyConfig fromArgs(String[] args) {
        String explicitConfigFile = firstNonNull(System.getProperty("proxy.config"), System.getenv("PROXY_CONFIG"));
        String port = firstNonNull(System.getProperty("proxy.port"), System.getenv("PROXY_PORT"));
        String target = firstNonNull(System.getProperty("target.url"), System.getenv("TARGET_URL"));
        String targetAuth = firstNonNull(System.getProperty("target.auth"), System.getProperty("target.basic.auth"),
                System.getenv("TARGET_AUTH"), System.getenv("TARGET_BASIC_AUTH"));
        String targetUsername = firstNonNull(System.getProperty("target.username"), System.getenv("TARGET_USERNAME"));
        String targetPassword = firstNonNull(System.getProperty("target.password"), System.getenv("TARGET_PASSWORD"));
        String fallbackTarget = firstNonNull(System.getProperty("fallback.target.url"),
                System.getenv("FALLBACK_TARGET_URL"));
        String fallbackTargetAuth = firstNonNull(System.getProperty("fallback.target.auth"),
                System.getProperty("fallback.target.basic.auth"), System.getenv("FALLBACK_TARGET_AUTH"),
                System.getenv("FALLBACK_TARGET_BASIC_AUTH"));
        String fallbackTargetUsername = firstNonNull(System.getProperty("fallback.target.username"),
                System.getenv("FALLBACK_TARGET_USERNAME"));
        String fallbackTargetPassword = firstNonNull(System.getProperty("fallback.target.password"),
                System.getenv("FALLBACK_TARGET_PASSWORD"));
        String logFile = firstNonNull(System.getProperty("log.file"), System.getenv("LOG_FILE"));

        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (!arg.startsWith("--") || eq < 0) {
                continue;
            }
            String value = arg.substring(eq + 1);
            switch (arg.substring(0, eq)) {
                case "--config" -> explicitConfigFile = value;
                case "--port" -> port = value;
                case "--target" -> target = value;
                case "--target-auth" -> targetAuth = value;
                case "--target-username" -> targetUsername = value;
                case "--target-password" -> targetPassword = value;
                case "--fallback-target" -> fallbackTarget = value;
                case "--fallback-target-auth" -> fallbackTargetAuth = value;
                case "--fallback-target-username" -> fallbackTargetUsername = value;
                case "--fallback-target-password" -> fallbackTargetPassword = value;
                case "--log-file" -> logFile = value;
                default -> {
                    // unknown flags are ignored
                }
            }
        }

        // 1. Load base configuration from YAML (explicit file, local file, or classpath)
        ProxyConfig base = load(explicitConfigFile == null ? null : Path.of(explicitConfigFile));

        String effectiveTargetAuth = firstNonNull(formatBasicAuth(targetAuth),
                formatBasicAuth(targetUsername, targetPassword), base.targetBasicAuth);
        String effectiveFallbackTargetAuth = firstNonNull(formatBasicAuth(fallbackTargetAuth),
                formatBasicAuth(fallbackTargetUsername, fallbackTargetPassword), base.fallbackTargetBasicAuth);

        // 2. Layer explicit CLI and environment overrides over YAML config
        Builder b = base.toBuilder()
                .targetBasicAuth(effectiveTargetAuth)
                .fallbackTargetBasicAuth(effectiveFallbackTargetAuth);
        Integer parsedPort = parseIntOrNull(port);
        if (parsedPort != null) {
            b.localPort(parsedPort);
        }
        if (target != null) {
            b.targetBaseUrl(target);
        }
        if (fallbackTarget != null) {
            b.fallbackTargetBaseUrl(fallbackTarget);
        }
        if (logFile != null) {
            b.logFile(Path.of(logFile));
        }
        return b.build();
    }

    private static ProxyConfig parseYamlMap(Map<?, ?> map) {
        Builder b = builder();

        if (extractValue(map, "proxy.port", "proxy.server.port", "server.port", "port") instanceof Number n) {
            b.localPort(n.intValue());
        }
        Object target = extractValue(map, "proxy.target-base-url", "proxy.target.url", "proxy.targetUrl",
                "target.url", "target-base-url", "target");
        if (target != null) {
            b.targetBaseUrl(target.toString());
        }

        Object targetAuthRaw = extractValue(map, "proxy.target-auth", "proxy.targetAuth", "proxy.target-basic-auth",
                "proxy.targetBasicAuth", "target-auth", "targetAuth", "target-basic-auth", "targetBasicAuth");
        String targetUsername = trimToNull(extractValue(map, "proxy.target-username", "proxy.targetUsername",
                "target-username", "targetUsername"));
        String targetPassword = trimToNull(extractValue(map, "proxy.target-password", "proxy.targetPassword",
                "target-password", "targetPassword"));
        b.targetBasicAuth(firstNonNull(formatBasicAuth(targetAuthRaw), formatBasicAuth(targetUsername, targetPassword)));

        b.targetBlockedRequests(parseBlockedRules(extractValue(map,
                "proxy.target-blocked-requests", "proxy.targetBlockedRequests",
                "proxy.target-blocked", "proxy.targetBlocked",
                "target-blocked-requests", "targetBlockedRequests", "target-blocked", "targetBlocked")));

        b.fallbackTargetBaseUrl(trimToNull(extractValue(map, "proxy.fallback-target-base-url",
                "proxy.fallbackTargetBaseUrl", "fallback-target-base-url", "fallbackTargetBaseUrl")));

        Object fallbackAuthRaw = extractValue(map, "proxy.fallback-target-auth", "proxy.fallbackTargetAuth",
                "proxy.fallback-target-basic-auth", "proxy.fallbackTargetBasicAuth",
                "fallback-target-auth", "fallbackTargetAuth", "fallback-target-basic-auth", "fallbackTargetBasicAuth");
        String fallbackUsername = trimToNull(extractValue(map, "proxy.fallback-target-username",
                "proxy.fallbackTargetUsername", "fallback-target-username", "fallbackTargetUsername"));
        String fallbackPassword = trimToNull(extractValue(map, "proxy.fallback-target-password",
                "proxy.fallbackTargetPassword", "fallback-target-password", "fallbackTargetPassword"));
        b.fallbackTargetBasicAuth(firstNonNull(formatBasicAuth(fallbackAuthRaw),
                formatBasicAuth(fallbackUsername, fallbackPassword)));

        b.fallbackTargetBlockedRequests(parseBlockedRules(extractValue(map,
                "proxy.fallback-target-blocked-requests", "proxy.fallbackTargetBlockedRequests",
                "proxy.fallback-target-blocked", "proxy.fallbackTargetBlocked",
                "fallback-target-blocked-requests", "fallbackTargetBlockedRequests",
                "fallback-target-blocked", "fallbackTargetBlocked")));

        if (extractValue(map, "proxy.fallback-on-status", "proxy.fallbackOnStatus",
                "fallback-on-status", "fallbackOnStatus") instanceof List<?> statuses) {
            Set<Integer> parsed = new LinkedHashSet<>();
            statuses.forEach(s -> {
                if (s instanceof Number n) {
                    parsed.add(n.intValue());
                }
            });
            b.fallbackHttpStatuses(parsed);
        }

        Object logFile = extractValue(map, "proxy.logging.file", "proxy.log-file", "logging.file", "log-file", "logFile");
        if (logFile != null) {
            b.logFile(Path.of(logFile.toString()));
        }
        if (extractValue(map, "proxy.logging.max-text-bytes", "proxy.max-loggable-text-bytes",
                "logging.max-text-bytes", "max-loggable-text-bytes") instanceof Number n) {
            b.maxLoggableTextBytes(n.intValue());
        }
        if (extractValue(map, "proxy.timeouts.connect-seconds", "proxy.connect-timeout-seconds",
                "target.connect-timeout-seconds", "connect-timeout-seconds") instanceof Number n) {
            b.connectTimeout(Duration.ofSeconds(n.longValue()));
        }
        if (extractValue(map, "proxy.timeouts.request-minutes", "proxy.request-timeout-minutes",
                "target.request-timeout-minutes", "request-timeout-minutes") instanceof Number n) {
            b.requestTimeout(Duration.ofMinutes(n.longValue()));
        }
        if (extractValue(map, "proxy.forwarding.add-forwarded-headers", "proxy.add-forwarded-headers",
                "forwarding.add-forwarded-headers", "add-forwarded-headers") instanceof Boolean v) {
            b.addForwardedHeaders(v);
        }
        if (extractValue(map, "proxy.logging.redact-sensitive-headers", "proxy.redact-sensitive-headers",
                "logging.redact-sensitive-headers", "redact-sensitive-headers") instanceof Boolean v) {
            b.redactSensitiveHeaders(v);
        }
        if (extractValue(map, "proxy.logging.sensitive-headers", "proxy.sensitive-headers",
                "logging.sensitive-headers", "sensitive-headers") instanceof List<?> headers) {
            Set<String> parsed = new LinkedHashSet<>();
            headers.stream().filter(Objects::nonNull).forEach(h -> parsed.add(h.toString().toLowerCase(Locale.ROOT)));
            b.sensitiveHeaders(parsed);
        }

        if (extractValue(map, "proxy.direct-responses", "direct-responses", "directResponses") instanceof List<?> raw) {
            List<DirectResponseDefinition> definitions = new ArrayList<>();
            for (Object item : raw) {
                if (item instanceof Map<?, ?> def && def.get("path") != null) {
                    int status = def.get("status") instanceof Number n ? n.intValue() : 200;
                    Object contentType = def.get("content-type") != null ? def.get("content-type") : def.get("contentType");
                    Object body = def.get("body");
                    definitions.add(new DirectResponseDefinition(def.get("path").toString(),
                            parseMethods(def.get("methods")), status,
                            contentType == null ? null : contentType.toString(),
                            body == null ? null : body.toString(),
                            Boolean.TRUE.equals(def.get("echo"))));
                }
            }
            b.directResponseDefinitions(definitions);
            b.directHandlers(definitions.stream().map(DirectResponseDefinition::toDirectRequestHandler).toList());
        }

        b.localStore(parseLocalStore(map));

        Object plugins = extractValue(map, "proxy.plugins-directory", "proxy.pluginsDirectory", "plugins-directory");
        if (plugins != null) {
            b.pluginsDirectory(trimToNull(plugins) == null ? null : Path.of(plugins.toString().trim()));
        }
        return b.build();
    }

    private static LocalStoreConfig parseLocalStore(Map<?, ?> map) {
        LocalStoreConfig config = LocalStoreConfig.disabled();
        if (extractValue(map, "proxy.local-store.enabled", "proxy.localStore.enabled",
                "local-store.enabled") instanceof Boolean enabled) {
            config = config.withEnabled(enabled);
        }
        String directory = trimToNull(extractValue(map, "proxy.local-store.directory", "proxy.localStore.directory",
                "local-store.directory"));
        if (directory != null) {
            config = config.withDirectory(Path.of(directory));
        }
        if (extractValue(map, "proxy.local-store.copy-content-eagerly", "proxy.localStore.copyContentEagerly",
                "local-store.copy-content-eagerly") instanceof Boolean eager) {
            config = config.withCopyContentEagerly(eager);
        }
        if (extractValue(map, "proxy.local-store.read-only-post-paths", "proxy.localStore.readOnlyPostPaths",
                "local-store.read-only-post-paths") instanceof List<?> paths) {
            config = config.withReadOnlyPostPaths(paths.stream().map(ProxyConfig::trimToNull)
                    .filter(Objects::nonNull).toList());
        }
        if (extractValue(map, "proxy.local-store.apis", "proxy.localStore.apis", "local-store.apis")
                instanceof List<?> apis) {
            List<DocumentApiConfig> parsed = new ArrayList<>();
            for (Object api : apis) {
                if (api instanceof Map<?, ?> def && trimToNull(def.get("name")) != null) {
                    Map<String, Object> settings = new LinkedHashMap<>();
                    def.forEach((k, v) -> {
                        if (!"name".equals(k)) {
                            settings.put(String.valueOf(k), v);
                        }
                    });
                    parsed.add(new DocumentApiConfig(trimToNull(def.get("name")), settings));
                } else if (trimToNull(api) != null) {
                    parsed.add(new DocumentApiConfig(trimToNull(api), Map.of()));
                }
            }
            config = config.withApis(parsed);
        }
        // allow-write-proxy first: the routes are validated against it
        if (extractValue(map, "proxy.local-store.allow-write-proxy", "proxy.localStore.allowWriteProxy",
                "local-store.allow-write-proxy") instanceof Boolean allow) {
            config = config.withAllowWriteProxy(allow);
        }
        if (extractValue(map, "proxy.local-store.routes", "proxy.localStore.routes", "local-store.routes")
                instanceof List<?> routes) {
            List<RouteRule> parsed = new ArrayList<>();
            for (Object route : routes) {
                if (route instanceof Map<?, ?> def) {
                    parsed.add(RouteRule.fromMap(def));
                }
            }
            config = config.withRoutes(parsed);
        }
        return config;
    }

    private static Object extractValue(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object current = map;
            boolean matched = true;
            for (String part : key.split("\\.")) {
                if (current instanceof Map<?, ?> m) {
                    current = m.get(part);
                } else {
                    matched = false;
                    break;
                }
            }
            if (matched && current != null) {
                return current;
            }
        }
        return null;
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer parseIntOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------ builder

    /**
     * Mutable builder for {@link ProxyConfig}; every field starts at its default value.
     */
    public static final class Builder {
        private int localPort = 9081;
        private String targetBaseUrl = DEFAULT_TARGET_URL;
        private String targetBasicAuth;
        private final List<BlockedRequestRule> targetBlockedRequests = new ArrayList<>();
        private String fallbackTargetBaseUrl;
        private String fallbackTargetBasicAuth;
        private final List<BlockedRequestRule> fallbackTargetBlockedRequests = new ArrayList<>();
        private Set<Integer> fallbackHttpStatuses = DEFAULT_FALLBACK_STATUSES;
        private Path logFile = Path.of("requests.json");
        private int maxLoggableTextBytes = DEFAULT_MAX_LOGGABLE_TEXT_BYTES;
        private Duration connectTimeout = Duration.ofSeconds(15);
        private Duration requestTimeout = Duration.ofMinutes(10);
        private boolean addForwardedHeaders = true;
        private boolean redactSensitiveHeaders = true;
        private Set<String> sensitiveHeaders = DEFAULT_SENSITIVE_HEADERS;
        private final List<DirectRequestHandler> directHandlers = new ArrayList<>();
        private List<DirectResponseDefinition> directResponseDefinitions = List.of();
        private LocalStoreConfig localStore = LocalStoreConfig.disabled();
        private Path pluginsDirectory = Path.of("plugins");

        private Builder() {
        }

        private Builder(ProxyConfig c) {
            localPort = c.localPort;
            targetBaseUrl = c.targetBaseUrl;
            targetBasicAuth = c.targetBasicAuth;
            targetBlockedRequests.addAll(c.targetBlockedRequests);
            fallbackTargetBaseUrl = c.fallbackTargetBaseUrl;
            fallbackTargetBasicAuth = c.fallbackTargetBasicAuth;
            fallbackTargetBlockedRequests.addAll(c.fallbackTargetBlockedRequests);
            fallbackHttpStatuses = c.fallbackHttpStatuses;
            logFile = c.logFile;
            maxLoggableTextBytes = c.maxLoggableTextBytes;
            connectTimeout = c.connectTimeout;
            requestTimeout = c.requestTimeout;
            addForwardedHeaders = c.addForwardedHeaders;
            redactSensitiveHeaders = c.redactSensitiveHeaders;
            sensitiveHeaders = c.sensitiveHeaders;
            directHandlers.addAll(c.directHandlers);
            directResponseDefinitions = c.directResponseDefinitions;
            localStore = c.localStore;
            pluginsDirectory = c.pluginsDirectory;
        }

        public Builder localPort(int v) { localPort = v; return this; }
        public Builder targetBaseUrl(String v) { targetBaseUrl = v; return this; }
        public Builder targetBasicAuth(String v) { targetBasicAuth = v; return this; }
        public Builder fallbackTargetBaseUrl(String v) { fallbackTargetBaseUrl = v; return this; }
        public Builder fallbackTargetBasicAuth(String v) { fallbackTargetBasicAuth = v; return this; }
        public Builder fallbackHttpStatuses(Set<Integer> v) { fallbackHttpStatuses = v; return this; }
        public Builder logFile(Path v) { logFile = v; return this; }
        public Builder maxLoggableTextBytes(int v) { maxLoggableTextBytes = v; return this; }
        public Builder connectTimeout(Duration v) { connectTimeout = v; return this; }
        public Builder requestTimeout(Duration v) { requestTimeout = v; return this; }
        public Builder addForwardedHeaders(boolean v) { addForwardedHeaders = v; return this; }
        public Builder redactSensitiveHeaders(boolean v) { redactSensitiveHeaders = v; return this; }
        public Builder sensitiveHeaders(Set<String> v) { sensitiveHeaders = v; return this; }
        public Builder directResponseDefinitions(List<DirectResponseDefinition> v) { directResponseDefinitions = v; return this; }
        public Builder localStore(LocalStoreConfig v) { localStore = v; return this; }
        public Builder pluginsDirectory(Path v) { pluginsDirectory = v; return this; }

        public Builder targetBlockedRequests(List<BlockedRequestRule> v) {
            targetBlockedRequests.clear();
            targetBlockedRequests.addAll(v);
            return this;
        }

        public Builder fallbackTargetBlockedRequests(List<BlockedRequestRule> v) {
            fallbackTargetBlockedRequests.clear();
            fallbackTargetBlockedRequests.addAll(v);
            return this;
        }

        public Builder directHandlers(List<DirectRequestHandler> v) {
            directHandlers.clear();
            directHandlers.addAll(v);
            return this;
        }

        public Builder addDirectHandler(DirectRequestHandler v) {
            directHandlers.add(v);
            return this;
        }

        public ProxyConfig build() {
            return new ProxyConfig(this);
        }
    }
}
