package plg.gesraco.tools.proxy.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import plg.gesraco.tools.proxy.config.BlockedRequestRule;
import plg.gesraco.tools.proxy.config.ProxyConfig;
import plg.gesraco.tools.proxy.http.Http;
import plg.gesraco.tools.proxy.interceptor.DirectRequestHandler;
import plg.gesraco.tools.proxy.interceptor.ProxyInterceptor;
import plg.gesraco.tools.proxy.interceptor.StandardForwardingInterceptor;
import plg.gesraco.tools.proxy.logging.JsonUtils;
import plg.gesraco.tools.proxy.logging.ProxyLogger;
import plg.gesraco.tools.proxy.model.ProxyAuditRecord;
import plg.gesraco.tools.proxy.store.LocalDocuments;
import plg.gesraco.tools.proxy.store.NodeRef;
import plg.gesraco.tools.proxy.store.Outcome;
import plg.gesraco.tools.proxy.store.Upstream;
import plg.gesraco.tools.proxy.store.UpstreamResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Core reverse proxy handler that manages request processing:
 * <ol>
 *   <li>Checks if any {@link DirectRequestHandler} intercepts and responds directly.</li>
 *   <li>When the local document store is enabled, serves/updates local copies of nodes ({@link LocalDocuments})
 *       and rejects every other write so none reaches Alfresco.</li>
 *   <li>Evaluates blocking rules configured for primary and fallback targets.</li>
 *   <li>Transparently proxies and streams requests and responses to Alfresco.</li>
 *   <li>Automatically forwards requests to a fallback target server if the primary target returns an error,
 *       fails to connect, or is blocked by target rules while fallback allows it.</li>
 *   <li>Injects or overwrites Basic Authorization headers for primary and fallback targets if configured.</li>
 *   <li>Records full NDJSON audit logs for direct, proxied, blocked, and fallback-relayed exchanges.</li>
 * </ol>
 */
public class ReverseProxyHandler implements Upstream {

    // Transport-level hop-by-hop headers to filter when relaying to target. `content-length` and `expect` are
    // also restricted by java.net.http (the length comes from the body publisher)
    private static final Set<String> HOP_BY_HOP_REQUEST_HEADERS = Set.of(
            "connection", "host", "te", "trailer", "transfer-encoding", "upgrade", "content-length", "expect",
            "keep-alive", "http2-settings");

    // Additionally filtered on local-store backend calls: they carry their own body (or none) and must
    // get an uncompressed response that can be parsed and stored
    private static final Set<String> LOCAL_STORE_EXCLUDED_HEADERS = Set.of("content-type", "accept-encoding");

    // Hop-by-hop headers to filter when returning backend response to client
    private static final Set<String> HOP_BY_HOP_RESPONSE_HEADERS = Set.of(
            "connection", "transfer-encoding", "content-length", "keep-alive", ":status");

    private final ProxyConfig config;
    private final HttpClient httpClient;
    private final ProxyLogger logger;
    private final List<ProxyInterceptor> interceptors;
    private final List<DirectRequestHandler> directHandlers;
    private final LocalDocuments localDocuments;

    public ReverseProxyHandler(ProxyConfig config, HttpClient httpClient, ProxyLogger logger,
                               List<ProxyInterceptor> interceptors, List<DirectRequestHandler> directHandlers) {
        this.config = config;
        this.httpClient = httpClient;
        this.logger = logger;
        this.interceptors = List.copyOf(interceptors);
        this.directHandlers = List.copyOf(directHandlers);
        this.localDocuments = config.localStore().enabled()
                ? LocalDocuments.create(config.localStore(), this, config.maxLoggableTextBytes(),
                LocalDocuments.pluginClassLoader(config.pluginsDirectory()))
                : null;
    }

    public ReverseProxyHandler(ProxyConfig config, HttpClient httpClient, ProxyLogger logger,
                               List<DirectRequestHandler> directHandlers) {
        this(config, httpClient, logger, List.of(new StandardForwardingInterceptor()), directHandlers);
    }

    public ReverseProxyHandler(ProxyConfig config, HttpClient httpClient, ProxyLogger logger) {
        this(config, httpClient, logger, List.of());
    }

    /**
     * Outbound client for {@code config}: HTTP/1.1, connect timeout applied, redirects followed.
     */
    public static HttpClient newHttpClient(ProxyConfig config) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Local document store, or null when disabled. */
    public LocalDocuments localDocuments() {
        return localDocuments;
    }

    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Instant startInstant = Instant.now();
        long startNano = System.nanoTime();
        String method = request.getMethod();
        String relativeUri = Http.relativeUri(request);
        String requestPath = Http.path(request);
        String clientIp = request.getRemoteAddr();

        // 1. Evaluate selective direct request handlers
        for (DirectRequestHandler handler : directHandlers) {
            boolean handled;
            try {
                handled = handler.handle(request, response);
            } catch (Exception e) {
                System.err.printf("[ERR] Direct handler exception: %s %s -> %s%n", method, relativeUri, e.getMessage());
                Http.sendError(response, 500, "Direct handler error: " + e.getMessage());
                handled = true;
            }
            if (handled) {
                long durationMs = elapsedMs(startNano);
                int status = response.getStatus();
                System.out.printf("[%s] <=DIRECT=> %-7s %s -> HTTP %d (%d ms)%n",
                        Instant.now(), method, relativeUri, status, durationMs);
                logger.log(ProxyAuditRecord.builder()
                        .timestamp(startInstant).durationMs(durationMs).clientIp(clientIp).method(method)
                        .uri(relativeUri).requestHeaders(Http.headers(request)).responseStatus(status)
                        .interceptedDirectly(true)
                        .build());
                return;
            }
        }

        // 2. Local document store: serve local copies of nodes, apply writes locally,
        //    and never let any other write reach a backend
        if (localDocuments != null) {
            Outcome outcome;
            try {
                outcome = localDocuments.handle(request, response);
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                System.err.printf("[ERR] Local store error: %s %s -> %s%n", method, relativeUri, message);
                Http.sendError(response, 500, "Local store error: " + message);
                outcome = new Outcome(500, null, false, null);
            }

            boolean isWrite = localDocuments.isWrite(method, requestPath);
            if (outcome != null || isWrite) {
                int status = outcome != null ? outcome.status() : 403;
                if (outcome == null) {
                    Http.respondText(response, 403,
                            "Forbidden: write requests are never forwarded to Alfresco (local-store mode)");
                }
                long durationMs = elapsedMs(startNano);
                System.out.printf("[%s] %s %-7s %s -> HTTP %d (%d ms)%n", Instant.now(),
                        outcome == null ? "[BLOCK]" : "<=LOCAL=>", method, relativeUri, status, durationMs);
                logger.log(ProxyAuditRecord.builder()
                        .timestamp(startInstant).durationMs(durationMs).clientIp(clientIp).method(method)
                        .uri(relativeUri).requestHeaders(Http.headers(request))
                        .requestBody(outcome != null ? outcome.requestBody() : null)
                        .responseStatus(status)
                        .forwardedToFallback(outcome != null && outcome.fromFallback())
                        .targetUrl(outcome != null ? outcome.targetUrl() : null)
                        .servedLocally(outcome != null)
                        .build());
                return;
            }
        }

        // 3. Evaluate target and fallback blocking rules
        BlockedRequestRule primaryBlockedRule = firstMatch(config.targetBlockedRequests(), method, requestPath);
        boolean fallbackBlocked = firstMatch(config.fallbackTargetBlockedRequests(), method, requestPath) != null;
        String fallbackBase = config.normalizedFallbackTargetBaseUrl();
        String fallbackUrl = fallbackBase != null ? fallbackBase + relativeUri : null;
        String primaryUrl = config.normalizedTargetBaseUrl() + relativeUri;

        // Requests about a node copied from the fallback go straight to the fallback (the primary doesn't have it)
        NodeRef fallbackNode = fallbackUrl != null && !fallbackBlocked && localDocuments != null
                ? localDocuments.fallbackNodeIn(relativeUri)
                : null;
        boolean primaryBlocked = primaryBlockedRule != null || fallbackNode != null;

        // If primary target blocks this request:
        if (primaryBlocked) {
            if (fallbackUrl == null || fallbackBlocked) {
                long durationMs = elapsedMs(startNano);
                int status = primaryBlockedRule != null ? primaryBlockedRule.status() : 403;
                String message = fallbackBlocked
                        ? "Forbidden: Request blocked for both primary and fallback targets"
                        : primaryBlockedRule != null ? primaryBlockedRule.message() : BlockedRequestRule.DEFAULT_MESSAGE;
                System.out.printf("[%s] [BLOCK] %-7s %s -> HTTP %d (%d ms) [BLOCKED]%n",
                        Instant.now(), method, relativeUri, status, durationMs);
                Http.respondText(response, status, message);
                logger.log(ProxyAuditRecord.builder()
                        .timestamp(startInstant).durationMs(durationMs).clientIp(clientIp).method(method)
                        .uri(relativeUri).requestHeaders(Http.headers(request)).responseStatus(status)
                        .responseHeaders(Map.of("Content-Type", List.of(Http.TEXT)))
                        .targetUrl(primaryUrl)
                        .build());
                return;
            } else if (primaryBlockedRule == null) {
                System.out.printf("[%s] [FALLBACK] Node %s was copied from fallback; sending %s %s directly to fallback%n",
                        Instant.now(), fallbackNode, method, relativeUri);
            } else {
                System.out.printf("[%s] [BLOCK] Request %s %s blocked on primary target; failing over directly to fallback: %s%n",
                        Instant.now(), method, relativeUri, fallbackUrl);
            }
        }

        // 4. Fall-through: proxy request to target (or failover to fallback)
        System.out.printf("[%s] ---> %-7s %s (from %s)%n", startInstant, method, relativeUri, clientIp);

        int responseStatus = 502;
        Map<String, List<String>> responseHeaders = Map.of();
        String loggedBody = null;
        boolean forwardedToFallback = primaryBlocked;
        String effectiveTargetUrl = primaryBlocked ? fallbackUrl : primaryUrl;

        try {
            RequestPayload payload = processRequestBody(request);
            loggedBody = payload.logSummary();

            boolean primaryFailed = false;

            // Attempt primary target ONLY if primary is not blocked
            if (!primaryBlocked) {
                HttpRequest.Builder primaryRequest = buildTargetRequest(request, primaryUrl,
                        config.normalizedTargetBasicAuth(), payload);
                interceptors.forEach(i -> i.onRequest(request, primaryRequest, config));
                try {
                    HttpResponse<InputStream> primaryResponse = send(primaryRequest.build());
                    int status = primaryResponse.statusCode();
                    boolean shouldFallback = fallbackUrl != null && config.fallbackHttpStatuses().contains(status);
                    if (shouldFallback && !fallbackBlocked) {
                        primaryResponse.body().close();
                        primaryFailed = true;
                        System.err.printf("[%s] [WARN] Primary target returned HTTP %d for %s %s. Forwarding to fallback: %s%n",
                                Instant.now(), status, method, relativeUri, fallbackUrl);
                    } else {
                        if (shouldFallback) {
                            System.err.printf("[%s] [BLOCK] Primary target returned HTTP %d for %s %s, but fallback target blocks this request. Suppressing fallback.%n",
                                    Instant.now(), status, method, relativeUri);
                        }
                        responseStatus = status;
                        responseHeaders = primaryResponse.headers().map();
                        interceptors.forEach(i -> i.onResponse(request, primaryResponse, config));
                        relayResponse(request, response, primaryResponse);
                    }
                } catch (IOException e) {
                    if (fallbackUrl == null) {
                        throw e;
                    }
                    if (fallbackBlocked) {
                        System.err.printf("[%s] [BLOCK] Primary target connection failed for %s %s, but fallback target blocks this request.%n",
                                Instant.now(), method, relativeUri);
                        responseStatus = 403;
                        Http.sendError(response, 403,
                                "Forbidden: Primary target unavailable and fallback blocks this request");
                    } else {
                        primaryFailed = true;
                        System.err.printf("[%s] [WARN] Primary target connection failed for %s %s (%s). Forwarding to fallback: %s%n",
                                Instant.now(), method, relativeUri, describe(e), fallbackUrl);
                    }
                }
            }

            // Execute fallback target if primary was blocked or primary failed, provided fallback allows it
            if ((primaryBlocked || primaryFailed) && fallbackUrl != null && !fallbackBlocked) {
                forwardedToFallback = true;
                effectiveTargetUrl = fallbackUrl;

                HttpRequest.Builder fallbackRequest = buildTargetRequest(request, fallbackUrl,
                        config.normalizedFallbackTargetBasicAuth(), payload);
                interceptors.forEach(i -> i.onRequest(request, fallbackRequest, config));
                HttpResponse<InputStream> fallbackResponse = send(fallbackRequest.build());
                responseStatus = fallbackResponse.statusCode();
                responseHeaders = fallbackResponse.headers().map();
                interceptors.forEach(i -> i.onResponse(request, fallbackResponse, config));
                relayResponse(request, response, fallbackResponse);
            }

            System.out.printf("[%s] <--- %-7s %s -> HTTP %d (%d ms)%s%n", Instant.now(), method, relativeUri,
                    responseStatus, elapsedMs(startNano), forwardedToFallback ? " [FALLBACK]" : "");

        } catch (HttpTimeoutException e) {
            responseStatus = 504;
            System.err.printf("[ERR] Target timeout (504): %s %s -> %s%n", method, relativeUri, e.getMessage());
            Http.sendError(response, 504, "Gateway Timeout: " + e.getMessage());
        } catch (ConnectException e) {
            responseStatus = 502;
            System.err.printf("[ERR] Connection failed (502): %s %s -> %s%n", method, relativeUri, e.getMessage());
            Http.sendError(response, 502, "Bad Gateway: Failed to connect to " + effectiveTargetUrl);
        } catch (Exception e) {
            responseStatus = 502;
            System.err.printf("[ERR] Proxy error (502): %s %s -> %s%n", method, relativeUri, e.getMessage());
            Http.sendError(response, 502, "Proxy Error: " + describe(e));
        } finally {
            logger.log(ProxyAuditRecord.builder()
                    .timestamp(startInstant).durationMs(elapsedMs(startNano)).clientIp(clientIp).method(method)
                    .uri(relativeUri).requestHeaders(Http.headers(request)).requestBody(loggedBody)
                    .responseStatus(responseStatus).responseHeaders(responseHeaders)
                    .forwardedToFallback(forwardedToFallback).targetUrl(effectiveTargetUrl)
                    .build());
        }
    }

    /**
     * Backend call made on behalf of the local document store: primary target first, then the fallback
     * when the primary is blocked, unreachable, or answers with a status in {@code fallback-on-status}.
     * {@code consumer} receives the final response only.
     */
    @Override
    public <T> UpstreamResult<T> request(HttpServletRequest request, String method, String relativeUri,
                                         String jsonBody, ResponseConsumer<T> consumer) throws IOException {
        int q = relativeUri.indexOf('?');
        String path = q < 0 ? relativeUri : relativeUri.substring(0, q);
        String fallbackBase = config.normalizedFallbackTargetBaseUrl();
        String fallbackUrl = fallbackBase != null && firstMatch(config.fallbackTargetBlockedRequests(), method, path) == null
                ? fallbackBase + relativeUri
                : null;
        boolean primaryBlocked = firstMatch(config.targetBlockedRequests(), method, path) != null
                || (fallbackUrl != null && localDocuments != null && localDocuments.fallbackNodeIn(relativeUri) != null);

        if (!primaryBlocked) {
            String primaryUrl = config.normalizedTargetBaseUrl() + relativeUri;
            boolean consumed = false;
            try {
                HttpRequest.Builder builder = buildLocalStoreRequest(request, primaryUrl,
                        config.normalizedTargetBasicAuth(), method, jsonBody);
                HttpResponse<InputStream> response = send(builder.build());
                try (InputStream ignored = response.body()) {
                    if (fallbackUrl != null && config.fallbackHttpStatuses().contains(response.statusCode())) {
                        System.err.printf("[%s] [WARN] Primary target returned HTTP %d for %s %s. Forwarding to fallback: %s%n",
                                Instant.now(), response.statusCode(), method, relativeUri, fallbackUrl);
                    } else {
                        consumed = true;
                        interceptors.forEach(i -> i.onResponse(request, response, config));
                        return new UpstreamResult<>(consumer.consume(response), response.statusCode(), primaryUrl, false);
                    }
                }
            } catch (IOException e) {
                if (consumed || fallbackUrl == null) {
                    throw e;
                }
                System.err.printf("[%s] [WARN] Primary target connection failed for %s %s (%s). Forwarding to fallback: %s%n",
                        Instant.now(), method, relativeUri, describe(e), fallbackUrl);
            }
        }

        if (fallbackUrl == null) {
            return new UpstreamResult<>(null, 403, null, false);
        }
        HttpRequest.Builder builder = buildLocalStoreRequest(request, fallbackUrl,
                config.normalizedFallbackTargetBasicAuth(), method, jsonBody);
        HttpResponse<InputStream> response = send(builder.build());
        try (InputStream ignored = response.body()) {
            interceptors.forEach(i -> i.onResponse(request, response, config));
            return new UpstreamResult<>(consumer.consume(response), response.statusCode(), fallbackUrl, true);
        }
    }

    private HttpRequest.Builder buildTargetRequest(HttpServletRequest request, String url, String basicAuth,
                                                   RequestPayload payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(config.requestTimeout())
                .method(request.getMethod(), payload.publisher());
        copyHeaders(request, builder, basicAuth, Set.of());
        return builder;
    }

    private HttpRequest.Builder buildLocalStoreRequest(HttpServletRequest request, String url, String basicAuth,
                                                       String method, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(config.requestTimeout())
                .method(method, jsonBody == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        copyHeaders(request, builder, basicAuth, LOCAL_STORE_EXCLUDED_HEADERS);
        if (jsonBody != null) {
            builder.header("Content-Type", Http.JSON);
        }
        interceptors.forEach(i -> i.onRequest(request, builder, config));
        return builder;
    }

    /**
     * Copies the client headers, except hop-by-hop ones and {@code excluded}. When target basic auth is configured,
     * the client's Authorization header is replaced, never forwarded alongside it.
     */
    private static void copyHeaders(HttpServletRequest request, HttpRequest.Builder builder, String basicAuth,
                                    Set<String> excluded) {
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP_REQUEST_HEADERS.contains(lower) || excluded.contains(lower)
                    || (basicAuth != null && lower.equals("authorization"))) {
                continue;
            }
            for (String value : Collections.list(request.getHeaders(name))) {
                builder.header(name, value);
            }
        }
        if (basicAuth != null) {
            builder.setHeader("Authorization", basicAuth);
        }
    }

    private HttpResponse<InputStream> send(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting for " + request.uri());
        }
    }

    /** Streams the backend response to the client (never buffered). */
    private static void relayResponse(HttpServletRequest request, HttpServletResponse response,
                                      HttpResponse<InputStream> targetResponse) throws IOException {
        int status = targetResponse.statusCode();
        response.setStatus(status);
        targetResponse.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP_RESPONSE_HEADERS.contains(lower) && !lower.equals("content-type")) {
                values.forEach(v -> response.addHeader(name, v));
            }
        });
        targetResponse.headers().firstValue("Content-Type").ifPresent(response::setContentType);

        boolean hasNoBody = status == 204 || status == 304 || "HEAD".equalsIgnoreCase(request.getMethod());
        try (InputStream in = targetResponse.body()) {
            if (hasNoBody) {
                return;
            }
            targetResponse.headers().firstValueAsLong("Content-Length").ifPresent(response::setContentLengthLong);
            OutputStream out = response.getOutputStream();
            in.transferTo(out);
            out.flush();
        }
    }

    private record RequestPayload(HttpRequest.BodyPublisher publisher, String logSummary) {
    }

    private RequestPayload processRequestBody(HttpServletRequest request) throws IOException {
        String method = request.getMethod();
        if (method.equalsIgnoreCase("GET") || method.equalsIgnoreCase("HEAD")) {
            return new RequestPayload(HttpRequest.BodyPublishers.noBody(), null);
        }

        String contentType = request.getContentType() == null ? "" : request.getContentType();
        String lower = contentType.toLowerCase(Locale.ROOT);
        boolean textual = lower.contains("json") || lower.contains("text") || lower.contains("xml")
                || lower.contains("urlencoded");

        if (!textual) {
            String kind = contentType.isBlank() ? "unknown" : contentType;
            if (config.fallbackTargetBaseUrl() != null) {
                // Buffered so it can be replayed on the fallback
                byte[] bytes = request.getInputStream().readAllBytes();
                return new RequestPayload(HttpRequest.BodyPublishers.ofByteArray(bytes),
                        "\"binary/stream [" + JsonUtils.escape(kind) + "] (" + bytes.length + " bytes)\"");
            }
            long length = request.getContentLengthLong();
            HttpRequest.BodyPublisher streamed = HttpRequest.BodyPublishers.ofInputStream(() -> {
                try {
                    return request.getInputStream();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            return new RequestPayload(length >= 0 ? HttpRequest.BodyPublishers.fromPublisher(streamed, length) : streamed,
                    "\"binary/stream [" + JsonUtils.escape(kind) + "]\"");
        }

        byte[] bytes = request.getInputStream().readAllBytes();
        if (bytes.length == 0) {
            return new RequestPayload(HttpRequest.BodyPublishers.noBody(), "null");
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        String summary = bytes.length > config.maxLoggableTextBytes()
                ? "\"[TRUNCATED " + bytes.length + " bytes] "
                + JsonUtils.escape(text.substring(0, Math.min(text.length(), config.maxLoggableTextBytes()))) + "\""
                : JsonUtils.formatJsonBody(text);
        return new RequestPayload(HttpRequest.BodyPublishers.ofByteArray(bytes), summary);
    }

    private static BlockedRequestRule firstMatch(List<BlockedRequestRule> rules, String method, String path) {
        for (BlockedRequestRule rule : rules) {
            if (rule.matches(method, path)) {
                return rule;
            }
        }
        return null;
    }

    private static long elapsedMs(long startNano) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNano);
    }

    private static String describe(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
