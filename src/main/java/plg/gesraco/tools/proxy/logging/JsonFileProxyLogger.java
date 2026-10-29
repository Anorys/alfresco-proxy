package plg.gesraco.tools.proxy.logging;

import plg.gesraco.tools.proxy.config.ProxyConfig;
import plg.gesraco.tools.proxy.model.ProxyAuditRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Thread-safe audit logger writing records in Newline Delimited JSON (NDJSON) format.
 * Includes automatic masking of sensitive credentials and headers for security and GDPR compliance.
 *
 * <p>Configurable directly with {@link ProxyConfig} (loaded from {@code application.yaml} or custom configuration),
 * ensuring sensitive headers, redaction flag, and log file path are dynamically loaded.
 */
public class JsonFileProxyLogger implements ProxyLogger {

    /**
     * Default sensitive headers sourced from {@link ProxyConfig#DEFAULT_SENSITIVE_HEADERS}.
     */
    public static final Set<String> DEFAULT_SENSITIVE_HEADERS = ProxyConfig.DEFAULT_SENSITIVE_HEADERS;

    private final Path logPath;
    private final boolean redactSensitive;
    private final Set<String> sensitiveHeaders;
    private final Object writeLock = new Object();

    public JsonFileProxyLogger(Path logPath, boolean redactSensitive, Set<String> sensitiveHeaders) {
        this.logPath = logPath;
        this.redactSensitive = redactSensitive;
        this.sensitiveHeaders = sensitiveHeaders.stream()
                .map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public JsonFileProxyLogger(Path logPath) {
        this(logPath, true, DEFAULT_SENSITIVE_HEADERS);
    }

    /**
     * Creates the logger from {@link ProxyConfig} (loaded from {@code application.yaml} or custom configuration).
     */
    public JsonFileProxyLogger(ProxyConfig config) {
        this(config.logFile(), config.redactSensitiveHeaders(), config.sensitiveHeaders());
    }

    /**
     * Factory method to create a logger using the given {@link ProxyConfig}.
     */
    public static JsonFileProxyLogger fromConfig(ProxyConfig config) {
        return new JsonFileProxyLogger(config);
    }

    @Override
    public void log(ProxyAuditRecord record) {
        String json = buildJsonLine(record);
        synchronized (writeLock) {
            try {
                Files.writeString(logPath, json + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("[ERR] Failed to write audit log: " + e.getMessage());
            }
        }
    }

    private String buildJsonLine(ProxyAuditRecord r) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append('{');
        property(sb, "timestamp", r.timestamp().toString()).append(',');
        property(sb, "durationMs", r.durationMs()).append(',');
        property(sb, "clientIp", r.clientIp()).append(',');
        property(sb, "method", r.method()).append(',');
        property(sb, "uri", r.uri()).append(',');
        sb.append("\"requestHeaders\":");
        headers(sb, r.requestHeaders()).append(',');
        sb.append("\"requestBody\":").append(r.requestBody() != null ? r.requestBody() : "null").append(',');
        property(sb, "responseStatus", r.responseStatus()).append(',');
        sb.append("\"responseHeaders\":");
        headers(sb, r.responseHeaders()).append(',');
        property(sb, "interceptedDirectly", r.interceptedDirectly());
        if (r.forwardedToFallback()) {
            sb.append(',');
            property(sb, "forwardedToFallback", true);
        }
        if (r.targetUrl() != null) {
            sb.append(',');
            property(sb, "targetUrl", r.targetUrl());
        }
        if (r.servedLocally()) {
            sb.append(',');
            property(sb, "servedLocally", true);
        }
        return sb.append('}').toString();
    }

    private StringBuilder headers(StringBuilder sb, Map<String, List<String>> headers) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            String name = header.getKey();
            sb.append('"').append(JsonUtils.escape(name)).append("\":\"");
            boolean sensitive = redactSensitive && sensitiveHeaders.contains(name.toLowerCase(Locale.ROOT));
            String value = sensitive ? "[REDACTED]" : String.join(", ", header.getValue());
            sb.append(JsonUtils.escape(value)).append('"');
        }
        return sb.append('}');
    }

    private static StringBuilder property(StringBuilder sb, String key, String value) {
        return sb.append('"').append(JsonUtils.escape(key)).append("\":\"")
                .append(JsonUtils.escape(value)).append('"');
    }

    private static StringBuilder property(StringBuilder sb, String key, Object value) {
        return sb.append('"').append(JsonUtils.escape(key)).append("\":").append(value);
    }
}
