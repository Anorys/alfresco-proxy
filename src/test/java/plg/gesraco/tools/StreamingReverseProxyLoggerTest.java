package plg.gesraco.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plg.gesraco.tools.TestSupport.Backend;
import plg.gesraco.tools.TestSupport.Proxy;
import plg.gesraco.tools.proxy.config.BlockedRequestRule;
import plg.gesraco.tools.proxy.config.ProxyConfig;
import plg.gesraco.tools.proxy.interceptor.DirectRequestHandler;
import plg.gesraco.tools.proxy.logging.JsonFileProxyLogger;
import plg.gesraco.tools.proxy.logging.JsonUtils;
import plg.gesraco.tools.proxy.logging.ProxyLogger;
import plg.gesraco.tools.proxy.model.ProxyAuditRecord;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static plg.gesraco.tools.TestSupport.respond;
import static plg.gesraco.tools.TestSupport.text;

class StreamingReverseProxyLoggerTest {

    private static final ProxyLogger NO_LOG = record -> {
    };

    private static String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("").split(";")[0].trim();
    }

    @Test
    void blockedRequestRuleMatchingLogic() {
        // Rule 1: Block all PUT requests (no path specified)
        BlockedRequestRule putRule = BlockedRequestRule.ofMethods("PUT");
        assertTrue(putRule.matches("PUT", "/api/test/hello"));
        assertTrue(putRule.matches("put", "/anything/else"));
        assertFalse(putRule.matches("GET", "/api/test/hello"));
        assertFalse(putRule.matches("POST", "/api/test/hello"));

        // Rule 2: Block POST requests on /api/test/hello
        BlockedRequestRule postHelloRule = BlockedRequestRule.of("/api/test/hello", "POST");
        assertTrue(postHelloRule.matches("POST", "/api/test/hello"));
        assertTrue(postHelloRule.matches("post", "/api/test/hello?query=1"));
        assertFalse(postHelloRule.matches("GET", "/api/test/hello"));
        assertFalse(postHelloRule.matches("POST", "/api/test/other"));

        // Rule 3: Block all methods on /api/test/test (methods empty)
        BlockedRequestRule allTestRule = BlockedRequestRule.ofPath("/api/test/test");
        assertTrue(allTestRule.matches("GET", "/api/test/test"));
        assertTrue(allTestRule.matches("POST", "/api/test/test"));
        assertTrue(allTestRule.matches("PUT", "/api/test/test"));
        assertTrue(allTestRule.matches("DELETE", "/api/test/test"));
        assertFalse(allTestRule.matches("GET", "/api/test/test/sub"));

        // Rule 4: Wildcard matching
        BlockedRequestRule wildcardRule = BlockedRequestRule.ofPath("/api/secure/**");
        assertTrue(wildcardRule.matches("GET", "/api/secure/docs"));
        assertTrue(wildcardRule.matches("GET", "/api/secure/docs/123/content"));
        assertFalse(wildcardRule.matches("GET", "/api/public/docs"));
    }

    @Test
    void proxyConfigDefaultValuesAndArgsParsing() {
        ProxyConfig defaultConfig = new ProxyConfig();
        assertEquals(9081, defaultConfig.localPort());
        assertEquals("http://gesraco-alfresco7.afeliodev.local:8080", defaultConfig.normalizedTargetBaseUrl());
        assertNull(defaultConfig.targetBasicAuth());
        assertNull(defaultConfig.fallbackTargetBaseUrl());
        assertNull(defaultConfig.fallbackTargetBasicAuth());
        assertTrue(defaultConfig.targetBlockedRequests().isEmpty());
        assertTrue(defaultConfig.fallbackTargetBlockedRequests().isEmpty());
        assertTrue(defaultConfig.directHandlers().isEmpty());

        ProxyConfig customConfig = ProxyConfig.fromArgs(new String[]{
                "--port=8090",
                "--target=http://example.com:8080/",
                "--target-auth=admin:primarySecret",
                "--fallback-target=http://fallback.example.com:8080/",
                "--fallback-target-auth=fallbackAdmin:fallbackSecret",
                "--log-file=test.json"
        });
        assertEquals(8090, customConfig.localPort());
        assertEquals("http://example.com:8080", customConfig.normalizedTargetBaseUrl());
        assertEquals("Basic YWRtaW46cHJpbWFyeVNlY3JldA==", customConfig.normalizedTargetBasicAuth());
        assertEquals("http://fallback.example.com:8080", customConfig.normalizedFallbackTargetBaseUrl());
        assertEquals("Basic ZmFsbGJhY2tBZG1pbjpmYWxsYmFja1NlY3JldA==", customConfig.normalizedFallbackTargetBasicAuth());
        assertEquals(Path.of("test.json"), customConfig.logFile());
    }

    @Test
    void proxyConfigParsesTargetAuthAndFallbackTargetAuthFromYaml() {
        ProxyConfig config = ProxyConfig.fromYaml("""
                proxy:
                  port: 8088
                  target-base-url: "http://target.local:8080"
                  target-auth:
                    username: "alfrescoUser"
                    password: "alfrescoPassword"
                  fallback-target-base-url: "http://fallback.local:8080"
                  fallback-target-auth:
                    username: "backupUser"
                    password: "backupPassword"
                """);
        assertEquals("Basic YWxmcmVzY29Vc2VyOmFsZnJlc2NvUGFzc3dvcmQ=", config.normalizedTargetBasicAuth());
        assertEquals("Basic YmFja3VwVXNlcjpiYWNrdXBQYXNzd29yZA==", config.normalizedFallbackTargetBasicAuth());
    }

    @Test
    void proxyConfigParsesUserColonPasswordStringAuthFromYaml() {
        ProxyConfig config = ProxyConfig.fromYaml("""
                proxy:
                  target-base-url: "http://target.local:8080"
                  target-auth: "admin:admin123"
                  fallback-target-base-url: "http://fallback.local:8080"
                  fallback-target-auth: "fallback:fallback123"
                """);
        assertEquals("Basic YWRtaW46YWRtaW4xMjM=", config.normalizedTargetBasicAuth());
        assertEquals("Basic ZmFsbGJhY2s6ZmFsbGJhY2sxMjM=", config.normalizedFallbackTargetBasicAuth());
    }

    @Test
    void proxyConfigParsesTargetAndFallbackBlockedRequestsFromYaml() {
        ProxyConfig config = ProxyConfig.fromYaml("""
                proxy:
                  target-base-url: "http://target.local:8080"
                  target-blocked-requests:
                    - methods: ["PUT"]
                    - path: "/api/test/hello"
                      methods: ["POST"]
                    - path: "/api/test/test"
                  fallback-target-base-url: "http://fallback.local:8080"
                  fallback-target-blocked-requests:
                    - methods: ["PUT", "DELETE"]
                    - path: "/api/test/hello"
                      methods: ["POST"]
                """);

        assertEquals(3, config.targetBlockedRequests().size());
        assertEquals(List.of("PUT"), config.targetBlockedRequests().get(0).methods());
        assertNull(config.targetBlockedRequests().get(0).path());
        assertEquals("/api/test/hello", config.targetBlockedRequests().get(1).path());
        assertEquals(List.of("POST"), config.targetBlockedRequests().get(1).methods());
        assertEquals("/api/test/test", config.targetBlockedRequests().get(2).path());
        assertTrue(config.targetBlockedRequests().get(2).methods().isEmpty());

        assertEquals(2, config.fallbackTargetBlockedRequests().size());
        assertEquals(List.of("PUT", "DELETE"), config.fallbackTargetBlockedRequests().get(0).methods());
        assertEquals("/api/test/hello", config.fallbackTargetBlockedRequests().get(1).path());
        assertEquals(List.of("POST"), config.fallbackTargetBlockedRequests().get(1).methods());
    }

    @Test
    void proxyConfigParsesFullYamlWithFallbackTarget() {
        ProxyConfig config = ProxyConfig.fromYaml("""
                proxy:
                  port: 8088
                  target-base-url: "http://target.local:8080"
                  fallback-target-base-url: "http://fallback.local:8080"
                  fallback-on-status:
                    - 404
                    - 500
                    - 503
                  logging:
                    file: "custom-requests.ndjson"
                    max-text-bytes: 1048576
                    redact-sensitive-headers: false
                    sensitive-headers:
                      - "token"
                      - "secret"
                  timeouts:
                    connect-seconds: 30
                    request-minutes: 5
                  forwarding:
                    add-forwarded-headers: false
                  direct-responses:
                    - path: "/echo"
                      methods: ["POST", "PUT"]
                      echo: true
                    - path: "/api/health"
                      methods: ["GET"]
                      status: 200
                      content-type: "application/json"
                      body: '{"status":"UP"}'
                """);

        assertEquals(8088, config.localPort());
        assertEquals("http://target.local:8080", config.normalizedTargetBaseUrl());
        assertEquals("http://fallback.local:8080", config.normalizedFallbackTargetBaseUrl());
        assertEquals(Set.of(404, 500, 503), config.fallbackHttpStatuses());
        assertEquals(Path.of("custom-requests.ndjson"), config.logFile());
        assertEquals(1048576, config.maxLoggableTextBytes());
        assertFalse(config.redactSensitiveHeaders());
        assertEquals(Set.of("token", "secret"), config.sensitiveHeaders());
        assertEquals(Duration.ofSeconds(30), config.connectTimeout());
        assertEquals(Duration.ofMinutes(5), config.requestTimeout());
        assertFalse(config.addForwardedHeaders());
        assertEquals(2, config.directResponseDefinitions().size());
        assertTrue(config.directResponseDefinitions().get(0).echo());
        assertEquals(2, config.directHandlers().size());
    }

    @Test
    void proxyConfigLoadsFromYamlFileAndOverridesViaCliArgs(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("test-config.yaml");
        Files.writeString(configFile, """
                proxy:
                  port: 7000
                  target-base-url: "http://initial.host:8080"
                  fallback-target-base-url: "http://fallback.host:8080"
                  logging:
                    file: "initial.log"
                """);

        ProxyConfig config = ProxyConfig.fromArgs(new String[]{
                "--config=" + configFile.toAbsolutePath(),
                "--port=7070",
                "--target=http://overridden.host:9090",
                "--fallback-target=http://overridden-fallback.host:9090"
        });

        assertEquals(7070, config.localPort());
        assertEquals("http://overridden.host:9090", config.normalizedTargetBaseUrl());
        assertEquals("http://overridden-fallback.host:9090", config.normalizedFallbackTargetBaseUrl());
        assertEquals(Path.of("initial.log"), config.logFile());
    }

    @Test
    void directResponsesConfiguredInYamlAreServed() throws Exception {
        ProxyConfig config = ProxyConfig.fromYaml("""
                proxy:
                  target-base-url: "http://127.0.0.1:1"
                  direct-responses:
                    - path: "/yaml/test"
                      methods: ["GET"]
                      status: 200
                      content-type: "application/json"
                      body: '{"from":"yaml"}'
                """);
        try (Proxy proxy = new Proxy(config, NO_LOG)) {
            HttpResponse<String> response = proxy.get("/yaml/test");
            assertEquals(200, response.statusCode());
            assertEquals("{\"from\":\"yaml\"}", response.body());
        }
    }

    @Test
    void echoDirectResponseReturnsTheRequestForPostAndPut() throws Exception {
        List<ProxyAuditRecord> records = Collections.synchronizedList(new ArrayList<>());
        ProxyConfig config = ProxyConfig.fromYaml("""
                proxy:
                  target-base-url: "http://127.0.0.1:1"
                  direct-responses:
                    - path: "/probe/test"
                      methods: ["POST", "PUT"]
                      echo: true
                """);
        try (Proxy proxy = new Proxy(config, records::add)) {
            HttpResponse<String> post = proxy.postJson("/probe/test", "{\"query\":\"documentSearch\"}");
            assertEquals(200, post.statusCode());
            assertEquals("application/json", contentType(post));
            assertTrue(post.body().contains("\"status\":\"ok\""));
            assertTrue(post.body().contains("\"method\":\"POST\""));
            assertTrue(post.body().contains("\"path\":\"/probe/test\""));
            assertTrue(post.body().contains("\"query\":\"documentSearch\""));

            HttpResponse<String> put = proxy.put("/probe/test", "text/plain", "test-update");
            assertEquals(200, put.statusCode());
            assertTrue(put.body().contains("\"method\":\"PUT\""));
            assertTrue(put.body().contains("test-update"));

        }
        assertTrue(records.get(0).interceptedDirectly());
        assertEquals("POST", records.get(0).method());
        assertEquals("/probe/test", records.get(0).uri());
        assertTrue(records.get(1).interceptedDirectly());
        assertEquals("PUT", records.get(1).method());
    }

    @Test
    void proxyOverwritesBasicAuthorizationHeaderOnPrimaryTarget() throws Exception {
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        try (Backend primary = Backend.start().get("/alfresco/service/api/auth-check", ex -> {
            receivedAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            text(ex, "auth received: " + receivedAuth.get());
        })) {
            ProxyConfig config = ProxyConfig.builder()
                    .targetBaseUrl(primary.url())
                    .targetBasicAuth("targetUser:targetSecret")
                    .build();
            try (Proxy proxy = new Proxy(config, NO_LOG)) {
                // Client sends its own Authorization header which must be overwritten
                HttpResponse<String> response = proxy.get("/alfresco/service/api/auth-check",
                        "Authorization", "Bearer client-original-token-to-overwrite");
                assertEquals(200, response.statusCode());
                assertEquals("Basic dGFyZ2V0VXNlcjp0YXJnZXRTZWNyZXQ=", receivedAuth.get());
                assertEquals("auth received: Basic dGFyZ2V0VXNlcjp0YXJnZXRTZWNyZXQ=", response.body());
            }
        }
    }

    @Test
    void proxyOverwritesBasicAuthorizationHeaderOnFallbackTargetWithSeparateCredentials() throws Exception {
        AtomicReference<String> fallbackAuth = new AtomicReference<>();
        try (Backend primary = Backend.start().get("/service/fallback-auth",
                ex -> respond(ex, 500, "text/plain", "Primary error"));
             Backend fallback = Backend.start().get("/service/fallback-auth", ex -> {
                 fallbackAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
                 text(ex, "Fallback success");
             })) {
            ProxyConfig config = ProxyConfig.builder()
                    .targetBaseUrl(primary.url())
                    .targetBasicAuth("primaryAdmin:primaryPass")
                    .fallbackTargetBaseUrl(fallback.url())
                    .fallbackTargetBasicAuth("fallbackAdmin:fallbackPass")
                    .fallbackHttpStatuses(Set.of(500))
                    .build();
            try (Proxy proxy = new Proxy(config, NO_LOG)) {
                HttpResponse<String> response = proxy.get("/service/fallback-auth",
                        "Authorization", "Basic Y2xpZW50VXNlcjpjbGllbnRQYXNz");
                assertEquals(200, response.statusCode());
                assertEquals("Fallback success", response.body());
                // Verify fallback server received its own configured credentials
                assertEquals("Basic ZmFsbGJhY2tBZG1pbjpmYWxsYmFja1Bhc3M=", fallbackAuth.get());
            }
        }
    }

    @Test
    void requestBlockedOnPrimaryFailsOverDirectlyToFallbackTarget() throws Exception {
        AtomicBoolean primaryPutHit = new AtomicBoolean();
        AtomicBoolean fallbackPutHit = new AtomicBoolean();
        List<ProxyAuditRecord> records = Collections.synchronizedList(new ArrayList<>());
        try (Backend primary = Backend.start()
                .route("PUT", "/api/document/123", ex -> {
                    primaryPutHit.set(true);
                    text(ex, "primary put");
                })
                .get("/api/document/123", ex -> text(ex, "primary get"));
             Backend fallback = Backend.start().route("PUT", "/api/document/123", ex -> {
                 fallbackPutHit.set(true);
                 text(ex, "fallback put success");
             })) {
            // Block all PUT requests on primary target
            ProxyConfig config = ProxyConfig.builder()
                    .targetBaseUrl(primary.url())
                    .targetBlockedRequests(List.of(BlockedRequestRule.ofMethods("PUT")))
                    .fallbackTargetBaseUrl(fallback.url())
                    .build();
            try (Proxy proxy = new Proxy(config, records::add)) {
                // 1. PUT request is blocked on primary -> proxy fails over directly to fallback
                HttpResponse<String> put = proxy.put("/api/document/123", "text/plain", "file-content");
                assertEquals(200, put.statusCode());
                assertEquals("fallback put success", put.body());
                assertFalse(primaryPutHit.get(), "Primary target should not have been called for blocked PUT");
                assertTrue(fallbackPutHit.get(), "Fallback target should have processed the PUT request");

                // 2. GET request is not blocked -> primary processes it
                HttpResponse<String> get = proxy.get("/api/document/123");
                assertEquals(200, get.statusCode());
                assertEquals("primary get", get.body());
            }
            assertEquals(2, records.size());
            assertTrue(records.get(0).forwardedToFallback());
            assertEquals("PUT", records.get(0).method());
            assertEquals(fallback.url() + "/api/document/123", records.get(0).targetUrl());

            assertFalse(records.get(1).forwardedToFallback());
            assertEquals("GET", records.get(1).method());
            assertEquals(primary.url() + "/api/document/123", records.get(1).targetUrl());
        }
    }

    @Test
    void requestBlockedOnBothTargetsReturns403WithoutCallingEitherBackend() throws Exception {
        AtomicBoolean backendContacted = new AtomicBoolean();
        try (Backend primary = Backend.start().fallback(ex -> {
            backendContacted.set(true);
            text(ex, "primary ok");
        });
             Backend fallback = Backend.start().fallback(ex -> {
                 backendContacted.set(true);
                 text(ex, "fallback ok");
             })) {
            // Block POST on /api/test/hello for both targets
            ProxyConfig config = ProxyConfig.builder()
                    .targetBaseUrl(primary.url())
                    .targetBlockedRequests(List.of(BlockedRequestRule.of("/api/test/hello", "POST")))
                    .fallbackTargetBaseUrl(fallback.url())
                    .fallbackTargetBlockedRequests(List.of(BlockedRequestRule.of("/api/test/hello", "POST")))
                    .build();
            try (Proxy proxy = new Proxy(config, NO_LOG)) {
                HttpResponse<String> response = proxy.post("/api/test/hello", "text/plain", "payload");
                assertEquals(403, response.statusCode());
                assertTrue(response.body().contains("Forbidden"));
                assertFalse(backendContacted.get(), "Neither backend should be contacted when blocked on both");
            }
        }
    }

    @Test
    void requestBlockedOnPathForAllMethodsReturns403() throws Exception {
        AtomicBoolean backendContacted = new AtomicBoolean();
        try (Backend primary = Backend.start().fallback(ex -> {
            backendContacted.set(true);
            text(ex, "ok");
        })) {
            ProxyConfig config = ProxyConfig.builder()
                    .targetBaseUrl(primary.url())
                    .targetBlockedRequests(List.of(BlockedRequestRule.ofPath("/api/test/test")))
                    .build();
            try (Proxy proxy = new Proxy(config, NO_LOG)) {
                assertEquals(403, proxy.get("/api/test/test").statusCode());
                assertEquals(403, proxy.post("/api/test/test", "text/plain", "data").statusCode());
                assertFalse(backendContacted.get());
            }
        }
    }

    @Test
    void fallbackThatBlocksTheRequestIsSuppressedWhenPrimaryReturnsError() throws Exception {
        AtomicBoolean fallbackContacted = new AtomicBoolean();
        try (Backend primary = Backend.start().route("POST", "/api/service/unstable",
                ex -> respond(ex, 500, "text/plain", "primary 500 error"));
             Backend fallback = Backend.start().fallback(ex -> {
                 fallbackContacted.set(true);
                 text(ex, "fallback response");
             })) {
            ProxyConfig config = ProxyConfig.builder()
                    .targetBaseUrl(primary.url())
                    .fallbackTargetBaseUrl(fallback.url())
                    .fallbackHttpStatuses(Set.of(500))
                    .fallbackTargetBlockedRequests(List.of(BlockedRequestRule.of("/api/service/unstable", "POST")))
                    .build();
            try (Proxy proxy = new Proxy(config, NO_LOG)) {
                HttpResponse<String> response = proxy.post("/api/service/unstable", "text/plain", "payload");
                // Primary returned 500, but fallback blocks it -> fallback is not contacted, 500 returned
                assertEquals(500, response.statusCode());
                assertEquals("primary 500 error", response.body());
                assertFalse(fallbackContacted.get(), "Fallback must not be contacted when it blocks this request");
            }
        }
    }

    @Test
    void proxyForwardsToFallbackWhenPrimaryReturnsErrorStatus() throws Exception {
        List<ProxyAuditRecord> records = Collections.synchronizedList(new ArrayList<>());
        try (Backend primary = Backend.start()
                .get("/service/data", ex -> respond(ex, 500, "text/plain", "primary 500 error"))
                .get("/service/normal", ex -> text(ex, "primary normal"));
             Backend fallback = Backend.start().get("/service/data", ex -> text(ex, "fallback successful response"))) {
            ProxyConfig config = ProxyConfig.builder()
                    .targetBaseUrl(primary.url())
                    .fallbackTargetBaseUrl(fallback.url())
                    .fallbackHttpStatuses(Set.of(500))
                    .build();
            try (Proxy proxy = new Proxy(config, records::add)) {
                HttpResponse<String> fromFallback = proxy.get("/service/data");
                assertEquals(200, fromFallback.statusCode());
                assertEquals("fallback successful response", fromFallback.body());

                HttpResponse<String> normal = proxy.get("/service/normal");
                assertEquals(200, normal.statusCode());
                assertEquals("primary normal", normal.body());
            }
            assertEquals(2, records.size());
            assertTrue(records.get(0).forwardedToFallback());
            assertEquals(200, records.get(0).responseStatus());
            assertEquals(fallback.url() + "/service/data", records.get(0).targetUrl());
            assertFalse(records.get(1).forwardedToFallback());
            assertEquals(200, records.get(1).responseStatus());
            assertEquals(primary.url() + "/service/normal", records.get(1).targetUrl());
        }
    }

    @Test
    void proxyForwardsToFallbackWhenPrimaryIsUnreachable() throws Exception {
        int unusedPrimaryPort = TestSupport.findFreePort();
        List<ProxyAuditRecord> records = Collections.synchronizedList(new ArrayList<>());
        try (Backend fallback = Backend.start().get("/service/unreachable-test",
                ex -> text(ex, "fallback after unreachable primary"))) {
            ProxyConfig config = ProxyConfig.builder()
                    .targetBaseUrl("http://127.0.0.1:" + unusedPrimaryPort)
                    .fallbackTargetBaseUrl(fallback.url())
                    .build();
            try (Proxy proxy = new Proxy(config, records::add)) {
                HttpResponse<String> response = proxy.get("/service/unreachable-test");
                assertEquals(200, response.statusCode());
                assertEquals("fallback after unreachable primary", response.body());
            }
            assertEquals(1, records.size());
            assertTrue(records.get(0).forwardedToFallback());
            assertEquals(200, records.get(0).responseStatus());
            assertEquals(fallback.url() + "/service/unreachable-test", records.get(0).targetUrl());
        }
    }

    @Test
    void requestAndResponseBodiesAreRelayed() throws Exception {
        byte[] binary = new byte[256 * 1024];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) i;
        }
        try (Backend primary = Backend.start()
                .route("POST", "/upload", ex -> {
                    byte[] received = ex.getRequestBody().readAllBytes();
                    text(ex, received.length + " " + ex.getRequestHeaders().getFirst("Content-Type"));
                })
                .get("/redirect", ex -> {
                    ex.getResponseHeaders().set("X-Custom", "kept");
                    respond(ex, 204, "text/plain", new byte[0]);
                })) {
            ProxyConfig config = ProxyConfig.builder().targetBaseUrl(primary.url()).build();
            try (Proxy proxy = new Proxy(config, NO_LOG)) {
                HttpResponse<String> upload = proxy.send("POST", "/upload", "application/octet-stream", binary);
                assertEquals(binary.length + " application/octet-stream", upload.body());

                HttpResponse<String> noContent = proxy.get("/redirect");
                assertEquals(204, noContent.statusCode());
                assertEquals("kept", noContent.headers().firstValue("X-Custom").orElse(null));
            }
        }
    }

    @Test
    void proxyConfigBuilderMethodsForDirectHandlers() {
        ProxyConfig config = new ProxyConfig()
                .withDirectJson("/api/status", "{\"status\":\"ok\"}")
                .withDirectText("/ping", "pong");
        assertEquals(2, config.directHandlers().size());
    }

    @Test
    void jsonFileProxyLoggerRedactsSensitiveHeadersAndEscapesJson(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("audit.json");
        JsonFileProxyLogger logger = new JsonFileProxyLogger(logFile, true, Set.of("authorization", "cookie"));

        logger.log(ProxyAuditRecord.builder()
                .timestamp(Instant.parse("2026-09-09T15:30:00Z"))
                .durationMs(42)
                .clientIp("127.0.0.1")
                .method("POST")
                .uri("/alfresco/api/-default-/public/authentication/versions/1/tickets")
                .requestHeaders(Map.of(
                        "Authorization", List.of("Basic dXNlcjpzZWNyZXQ="),
                        "Content-Type", List.of("application/json"),
                        "User-Agent", List.of("Mozilla/5.0 \"quoted\"")))
                .requestBody("{\"username\":\"admin\"}")
                .responseStatus(201)
                .responseHeaders(Map.of("Set-Cookie", List.of("JSESSIONID=12345")))
                .forwardedToFallback(true)
                .targetUrl("http://fallback.server:8080/alfresco/api")
                .build());

        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size());
        String jsonLine = lines.get(0);

        // Sensitive headers are redacted
        assertTrue(jsonLine.contains("\"Authorization\":\"[REDACTED]\""));
        assertFalse(jsonLine.contains("dXNlcjpzZWNyZXQ="));

        assertTrue(jsonLine.contains("\"durationMs\":42"));
        assertTrue(jsonLine.contains("\"responseStatus\":201"));
        assertTrue(jsonLine.contains("\"requestBody\":{\"username\":\"admin\"}"));
        assertTrue(jsonLine.contains("\"interceptedDirectly\":false"));
        assertTrue(jsonLine.contains("\"forwardedToFallback\":true"));
        assertTrue(jsonLine.contains("\"targetUrl\":\"http://fallback.server:8080/alfresco/api\""));
        assertTrue(jsonLine.contains("Mozilla/5.0 \\\"quoted\\\""));
    }

    @Test
    void jsonFileProxyLoggerLoadedFromProxyConfigRedactsYamlConfiguredHeaders(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("audit-yaml.json");
        ProxyConfig config = ProxyConfig.fromYaml("""
                proxy:
                  logging:
                    file: "%s"
                    redact-sensitive-headers: true
                    sensitive-headers:
                      - "X-Custom-Api-Key"
                      - "Session-Secret"
                """.formatted(logFile.toAbsolutePath().toString().replace("\\", "/")));
        JsonFileProxyLogger logger = new JsonFileProxyLogger(config);

        logger.log(ProxyAuditRecord.builder()
                .timestamp(Instant.parse("2026-09-10T08:00:00Z"))
                .durationMs(15)
                .clientIp("127.0.0.1")
                .method("POST")
                .uri("/api/data")
                .requestHeaders(Map.of(
                        "X-Custom-Api-Key", List.of("super-secret-key-12345"),
                        "Session-Secret", List.of("token-xyz"),
                        "Content-Type", List.of("application/json")))
                .responseStatus(200)
                .build());

        String line = Files.readAllLines(logFile).get(0);
        assertTrue(line.contains("\"X-Custom-Api-Key\":\"[REDACTED]\""));
        assertTrue(line.contains("\"Session-Secret\":\"[REDACTED]\""));
        assertFalse(line.contains("super-secret-key-12345"));
        assertFalse(line.contains("token-xyz"));
    }

    @Test
    void jsonEscaping() {
        String unescaped = "line1\nline2\t\"quoted\"\\backslash\u0000null";
        assertEquals("line1\\nline2\\t\\\"quoted\\\"\\\\backslash\\u0000null", JsonUtils.escape(unescaped));
    }

    @Test
    void directHandlerInterceptsRequestAndReturnsDirectResponse() throws Exception {
        List<ProxyAuditRecord> records = Collections.synchronizedList(new ArrayList<>());
        ProxyConfig config = ProxyConfig.builder()
                .targetBaseUrl("http://127.0.0.1:1")
                .directHandlers(List.of(
                        DirectRequestHandler.respondJson("/alfresco/service/health", "{\"status\":\"UP\",\"version\":\"7.4\"}"),
                        DirectRequestHandler.respondText("/direct/ping", "pong")))
                .build();
        try (Proxy proxy = new Proxy(config, records::add)) {
            HttpResponse<String> health = proxy.get("/alfresco/service/health");
            assertEquals(200, health.statusCode());
            assertEquals("{\"status\":\"UP\",\"version\":\"7.4\"}", health.body());
            assertEquals("application/json", contentType(health));

            HttpResponse<String> ping = proxy.get("/direct/ping");
            assertEquals(200, ping.statusCode());
            assertEquals("pong", ping.body());
            assertEquals("text/plain", contentType(ping));
        }
        assertEquals(2, records.size());
        assertTrue(records.get(0).interceptedDirectly());
        assertEquals("/alfresco/service/health", records.get(0).uri());
        assertEquals(200, records.get(0).responseStatus());
        assertTrue(records.get(1).interceptedDirectly());
        assertEquals("/direct/ping", records.get(1).uri());
    }

    @Test
    void customRouteInterceptsBeforeTheProxy() throws Exception {
        List<ProxyAuditRecord> records = Collections.synchronizedList(new ArrayList<>());
        ProxyConfig config = ProxyConfig.builder().targetBaseUrl("http://127.0.0.1:1").build();
        try (Proxy proxy = new Proxy(config, records::add, List.of(
                DirectRequestHandler.respondText("/mock/custom", "Custom Direct Response")))) {
            HttpResponse<String> response = proxy.get("/mock/custom");
            assertEquals(200, response.statusCode());
            assertEquals("Custom Direct Response", response.body());
        }
        assertTrue(records.isEmpty(), "custom routes are not audited");
    }
}
