package plg.gesraco.tools.proxy.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable data model representing the audit record of an HTTP exchange relayed by the proxy
 * or intercepted directly.
 */
public record ProxyAuditRecord(
        Instant timestamp,
        long durationMs,
        String clientIp,
        String method,
        String uri,
        Map<String, List<String>> requestHeaders,
        String requestBody,
        int responseStatus,
        Map<String, List<String>> responseHeaders,
        boolean interceptedDirectly,
        boolean forwardedToFallback,
        String targetUrl,
        boolean servedLocally
) {

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link ProxyAuditRecord}; optional flags default to `false`, headers to empty maps.
     */
    public static final class Builder {
        private Instant timestamp = Instant.now();
        private long durationMs;
        private String clientIp = "";
        private String method = "";
        private String uri = "";
        private Map<String, List<String>> requestHeaders = Map.of();
        private String requestBody;
        private int responseStatus;
        private Map<String, List<String>> responseHeaders = Map.of();
        private boolean interceptedDirectly;
        private boolean forwardedToFallback;
        private String targetUrl;
        private boolean servedLocally;

        private Builder() {
        }

        public Builder timestamp(Instant v) { timestamp = v; return this; }
        public Builder durationMs(long v) { durationMs = v; return this; }
        public Builder clientIp(String v) { clientIp = v; return this; }
        public Builder method(String v) { method = v; return this; }
        public Builder uri(String v) { uri = v; return this; }
        public Builder requestHeaders(Map<String, List<String>> v) { requestHeaders = v; return this; }
        public Builder requestBody(String v) { requestBody = v; return this; }
        public Builder responseStatus(int v) { responseStatus = v; return this; }
        public Builder responseHeaders(Map<String, List<String>> v) { responseHeaders = v; return this; }
        public Builder interceptedDirectly(boolean v) { interceptedDirectly = v; return this; }
        public Builder forwardedToFallback(boolean v) { forwardedToFallback = v; return this; }
        public Builder targetUrl(String v) { targetUrl = v; return this; }
        public Builder servedLocally(boolean v) { servedLocally = v; return this; }

        public ProxyAuditRecord build() {
            return new ProxyAuditRecord(timestamp, durationMs, clientIp, method, uri, requestHeaders, requestBody,
                    responseStatus, responseHeaders, interceptedDirectly, forwardedToFallback, targetUrl,
                    servedLocally);
        }
    }
}
