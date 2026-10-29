package plg.gesraco.tools;

import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plg.gesraco.tools.TestSupport.Backend;
import plg.gesraco.tools.TestSupport.Proxy;
import plg.gesraco.tools.proxy.config.ProxyConfig;
import plg.gesraco.tools.proxy.config.RouteRule;
import plg.gesraco.tools.proxy.model.ProxyAuditRecord;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local store routes ({@code local-store.routes}): an API described in configuration only.
 */
class LocalRoutesTest {

    private static final String NODES = "/alfresco/s/custom/v1/nodes";
    private static final String GUID = "11111111-2222-3333-4444-555555555555";
    private static final String METADATA = NODES + "/workspace/SpacesStore/" + GUID + "/metadata";

    private static final String ROUTES = """
            proxy:
              target-base-url: "%s"
              local-store:
                enabled: true
                directory: "%s"
                routes:
                  - path: "**/custom/v1/nodes/{space}/{store}/{guid}/metadata"
                    methods: [GET, HEAD]
                    mode: cache
                    key: "{guid}/metadata"
                  - path: "**/custom/v1/nodes/{space}/{store}/{guid}/metadata"
                    methods: [PUT, POST]
                    mode: simulate
                    key: "{guid}/metadata"
                  - path: "**/custom/v1/nodes/{space}/{store}/{guid}"
                    methods: [DELETE]
                    mode: simulate
                    key: "{guid}/metadata"
                  - path: "**/custom/v1/nodes/{space}/{store}/{guid}/permissions"
                    methods: [GET]
                    mode: proxy
                  - path: "**/custom/v1/nodes/{space}/{store}/{guid}/permissions"
                    methods: [PUT]
                    mode: simulate
                    key: "{path}"
                  - path: "**/custom/v1/nodes/{space}/{store}/{guid}/acl"
                    methods: [POST]
                    mode: simulate
                    status: 202
                    content-type: "application/json"
                    body: '{"accepted":true}'
                  - path: "**/custom/v1/search"
                    methods: [POST]
                    mode: proxy
                    read-only: true
                  - path: "**/custom/**"
                    mode: block
                    status: 451
                    body: '{"blocked":true}'
            """;

    @TempDir
    Path storeDir;

    private Backend backend;
    private Proxy proxy;
    private final List<String> backendCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<ProxyAuditRecord> audit = Collections.synchronizedList(new ArrayList<>());

    private void start() throws Exception {
        start(ROUTES);
    }

    private void start(String routes) throws Exception {
        backend = Backend.start().fallback(exchange -> {
            backendCalls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange);
        });
        String yaml = routes.formatted(backend.url(), storeDir.toString().replace("\\", "/"));
        ProxyConfig config = ProxyConfig.fromYaml(yaml);
        proxy = new Proxy(config, audit::add);
    }

    private static void respond(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals(METADATA)) {
            TestSupport.json(exchange, "{\"title\":\"from alfresco\"}");
        } else if (path.endsWith("/permissions")) {
            TestSupport.json(exchange, "{\"permissions\":\"from alfresco\"}");
        } else if (path.endsWith("/search")) {
            TestSupport.json(exchange, "{\"results\":[]}");
        } else {
            TestSupport.respond(exchange, 404, "text/plain", "not found");
        }
    }

    @AfterEach
    void stop() {
        if (proxy != null) {
            proxy.close();
        }
        if (backend != null) {
            backend.close();
        }
    }

    private long calls(String prefix) {
        return backendCalls.stream().filter(c -> c.startsWith(prefix)).count();
    }

    @Test
    void cacheCopiesOnFirstReadThenServesTheCopy() throws Exception {
        start();
        HttpResponse<String> first = proxy.get(METADATA);
        assertEquals(200, first.statusCode());
        assertEquals("{\"title\":\"from alfresco\"}", first.body());
        HttpResponse<String> second = proxy.get(METADATA);
        assertEquals("{\"title\":\"from alfresco\"}", second.body());
        assertTrue(second.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals(1, calls("GET " + METADATA));
        assertTrue(audit.get(1).servedLocally());
    }

    @Test
    void failedReadsAreRelayedAndNotCopied() throws Exception {
        start();
        String missing = NODES + "/workspace/SpacesStore/00000000-0000-0000-0000-000000000000/metadata";
        assertEquals(404, proxy.get(missing).statusCode());
        assertEquals(404, proxy.get(missing).statusCode());
        assertEquals(2, calls("GET " + missing));
    }

    @Test
    void simulatedWriteIsVisibleToLaterReadsAndNeverSent() throws Exception {
        start();
        HttpResponse<String> put = proxy.putJson(METADATA, "{\"title\":\"local\"}");
        assertEquals(200, put.statusCode());
        assertEquals("{\"title\":\"local\"}", put.body());
        assertEquals("{\"title\":\"local\"}", proxy.get(METADATA).body());
        assertEquals(0, calls("PUT"));
        assertEquals(0, calls("GET"));
        assertEquals("{\"title\":\"local\"}", audit.get(0).requestBody());
    }

    @Test
    void simulatedDeleteHidesTheResource() throws Exception {
        start();
        assertEquals(200, proxy.get(METADATA).statusCode());
        assertEquals(204, proxy.delete(NODES + "/workspace/SpacesStore/" + GUID).statusCode());
        assertEquals(404, proxy.get(METADATA).statusCode());
        assertEquals(0, calls("DELETE"));
    }

    @Test
    void proxyReadsGoToAlfrescoUntilWrittenLocally() throws Exception {
        start();
        String permissions = NODES + "/workspace/SpacesStore/" + GUID + "/permissions";
        assertEquals("{\"permissions\":\"from alfresco\"}", proxy.get(permissions).body());
        assertEquals("{\"permissions\":\"from alfresco\"}", proxy.get(permissions).body());
        assertEquals(2, calls("GET " + permissions));

        assertEquals(200, proxy.putJson(permissions, "{\"permissions\":\"local\"}").statusCode());
        assertEquals("{\"permissions\":\"local\"}", proxy.get(permissions).body());
        assertEquals(2, calls("GET " + permissions));
        assertEquals(0, calls("PUT"));
    }

    @Test
    void simulateWithAFixedResponse() throws Exception {
        start();
        HttpResponse<String> response = proxy.postJson(NODES + "/workspace/SpacesStore/" + GUID + "/acl", "{\"a\":1}");
        assertEquals(202, response.statusCode());
        assertEquals("{\"accepted\":true}", response.body());
        assertEquals(0, calls("POST"));
    }

    @Test
    void blockRuleAndUnmatchedWrites() throws Exception {
        start();
        HttpResponse<String> blocked = proxy.get("/alfresco/s/custom/v1/other");
        assertEquals(451, blocked.statusCode());
        assertEquals("{\"blocked\":true}", blocked.body());
        assertEquals(403, proxy.postJson("/alfresco/s/elsewhere", "{}").statusCode());
        assertTrue(backendCalls.isEmpty(), backendCalls::toString);
    }

    @Test
    void readOnlyProxyPostReachesAlfresco() throws Exception {
        start();
        HttpResponse<String> search = proxy.postJson("/alfresco/s/custom/v1/search", "{\"q\":\"x\"}");
        assertEquals(200, search.statusCode());
        assertEquals(1, calls("POST /alfresco/s/custom/v1/search"));
    }

    @Test
    void writeProxyNeedsAnExplicitFlag() throws Exception {
        String routes = """
                proxy:
                  target-base-url: "%s"
                  local-store:
                    enabled: true
                    directory: "%s"
                    routes:
                      - path: "**/custom/v1/nodes/**"
                        methods: [PUT]
                        mode: proxy
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProxyConfig.fromYaml(routes.formatted("http://127.0.0.1:1", "x")));
        assertTrue(error.getMessage().contains("allow-write-proxy"), error.getMessage());

        start(routes + "    allow-write-proxy: true\n");
        assertEquals(200, proxy.putJson(METADATA, "{}").statusCode());
        assertEquals(1, calls("PUT " + METADATA));
    }

    @Test
    void ruleMatchingAndKeys() {
        RouteRule rule = RouteRule.fromMap(Map.of("path", "**/nodes/{space}/{store}/{guid}/metadata",
                "methods", List.of("put"), "mode", "simulate", "key", "{store}:{guid}"));
        Map<String, String> variables = rule.match("PUT", "/alfresco/s/x/nodes/workspace/SpacesStore/abc/metadata?x=1");
        assertEquals(Map.of("space", "workspace", "store", "SpacesStore", "guid", "abc"), variables);
        assertEquals("SpacesStore:abc", rule.resourceKey(variables, "/p", "/p?x=1"));
        assertEquals(RouteRule.Store.BODY, rule.store());
        assertNull(rule.match("GET", "/alfresco/s/x/nodes/workspace/SpacesStore/abc/metadata"));
        assertNull(rule.match("PUT", "/alfresco/s/x/nodes/workspace/SpacesStore/abc/def/metadata"));
        assertThrows(IllegalArgumentException.class, () -> RouteRule.fromMap(Map.of("path", "/x", "mode", "cache",
                "methods", List.of("PUT"))));
        assertThrows(IllegalArgumentException.class, () -> RouteRule.fromMap(Map.of("path", "/x", "mode", "teleport")));
    }
}
