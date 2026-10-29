package plg.gesraco.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plg.gesraco.tools.TestSupport.Backend;
import plg.gesraco.tools.TestSupport.Multipart;
import plg.gesraco.tools.TestSupport.Proxy;
import plg.gesraco.tools.proxy.config.LocalStoreConfig;
import plg.gesraco.tools.proxy.config.ProxyConfig;
import plg.gesraco.tools.proxy.model.ProxyAuditRecord;
import plg.gesraco.tools.proxy.store.NodeRef;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local document store with the standard Alfresco REST API: the primary backend knows nothing (404), the fallback
 * holds a folder and a document. Writes must never reach a backend.
 */
class LocalDocumentStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API = "/alfresco/api/-default-/public/alfresco/versions/1";
    private static final String DOC = "11111111-2222-3333-4444-555555555555";
    private static final String FOLDER = "99999999-8888-7777-6666-555555555555";
    private static final String OTHER_FOLDER = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String SIBLING = "22222222-3333-4444-5555-666666666666";

    @TempDir
    Path storeDir;

    private Backend primary;
    private Backend fallback;
    private Proxy proxy;

    /** Every non-GET request received by a backend ("backend METHOD uri"), read-only POSTs included. */
    private final List<String> backendWrites = Collections.synchronizedList(new ArrayList<>());
    private final List<String> fallbackReads = Collections.synchronizedList(new ArrayList<>());
    private final List<ProxyAuditRecord> audit = Collections.synchronizedList(new ArrayList<>());

    private static String entry(String id, String name, String parentId, boolean folder) {
        String content = folder ? "" : ",\"content\":{\"mimeType\":\"application/pdf\",\"mimeTypeName\":\"Adobe PDF\","
                + "\"sizeInBytes\":9,\"encoding\":\"UTF-8\"}";
        return "{\"entry\":{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"nodeType\":\""
                + (folder ? "cm:folder" : "cm:content") + "\",\"isFolder\":" + folder + ",\"isFile\":" + !folder
                + ",\"parentId\":\"" + parentId + "\",\"createdAt\":\"2026-01-01T00:00:00.000+0000\","
                + "\"modifiedAt\":\"2026-01-01T00:00:00.000+0000\",\"createdByUser\":{\"id\":\"admin\","
                + "\"displayName\":\"Administrator\"},\"modifiedByUser\":{\"id\":\"admin\",\"displayName\":\"Administrator\"},"
                + "\"aspectNames\":[\"cm:titled\",\"cm:auditable\"],\"properties\":{\"cm:title\":\"Original title\"},"
                + "\"allowableOperations\":[\"delete\",\"update\"],\"isLocked\":false" + content + "}}";
    }

    private void track(String backend, HttpExchange exchange) {
        if (!exchange.getRequestMethod().equals("GET")) {
            backendWrites.add(backend + " " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
        }
    }

    @BeforeEach
    void start() throws Exception {
        primary = Backend.start().fallback(exchange -> {
            track("primary", exchange);
            if (exchange.getRequestURI().getPath().endsWith("/search/versions/1/search")) {
                TestSupport.json(exchange, "{\"list\":{\"entries\":[]}}");
            } else {
                TestSupport.respond(exchange, 404, "application/json",
                        "{\"error\":{\"statusCode\":404,\"briefSummary\":\"not found\"}}");
            }
        });
        fallback = Backend.start()
                .get(API + "/nodes/" + DOC, exchange -> {
                    fallbackReads.add(exchange.getRequestURI().toString());
                    TestSupport.json(exchange, entry(DOC, "report.pdf", FOLDER, false));
                })
                .get(API + "/nodes/" + DOC + "/content", exchange -> {
                    fallbackReads.add(exchange.getRequestURI().toString());
                    TestSupport.respond(exchange, 200, "application/pdf", "PDF-BYTES");
                })
                .get(API + "/nodes/" + DOC + "/versions", exchange -> {
                    fallbackReads.add(exchange.getRequestURI().toString());
                    TestSupport.json(exchange, "{\"list\":{\"pagination\":{\"count\":1},\"entries\":[{\"entry\":{\"id\":\"1.0\"}}]}}");
                })
                .get(API + "/nodes/" + FOLDER, exchange -> TestSupport.json(exchange, entry(FOLDER, "Folder", "root", true)))
                .get(API + "/nodes/" + OTHER_FOLDER,
                        exchange -> TestSupport.json(exchange, entry(OTHER_FOLDER, "Other", "root", true)))
                .get(API + "/nodes/" + SIBLING, exchange -> TestSupport.json(exchange, entry(SIBLING, "sibling.pdf", FOLDER, false)))
                .get(API + "/nodes/" + FOLDER + "/children", exchange -> TestSupport.json(exchange,
                        "{\"list\":{\"pagination\":{\"count\":2,\"hasMoreItems\":false,\"totalItems\":2,\"skipCount\":0,"
                                + "\"maxItems\":100},\"entries\":[" + entry(DOC, "report.pdf", FOLDER, false) + ","
                                + entry(SIBLING, "sibling.pdf", FOLDER, false) + "]}}"))
                .get(API + "/nodes/" + OTHER_FOLDER + "/children", exchange -> TestSupport.json(exchange,
                        "{\"list\":{\"pagination\":{\"count\":0,\"hasMoreItems\":false,\"totalItems\":0},\"entries\":[]}}"))
                .get(API + "/nodes/-my-", exchange -> TestSupport.json(exchange, entry(FOLDER, "Folder", "root", true)))
                .fallback(exchange -> {
                    track("fallback", exchange);
                    TestSupport.respond(exchange, 404, "text/plain", "not found");
                });

        ProxyConfig config = ProxyConfig.builder()
                .targetBaseUrl(primary.url())
                .fallbackTargetBaseUrl(fallback.url())
                .build()
                .withLocalStore(storeDir);
        proxy = new Proxy(config, audit::add);
    }

    @AfterEach
    void stop() {
        proxy.close();
        primary.close();
        fallback.close();
    }

    private static JsonNode json(HttpResponse<String> response) throws IOException {
        return MAPPER.readTree(response.body());
    }

    private static List<String> childNames(HttpResponse<String> response) throws IOException {
        List<String> names = new ArrayList<>();
        json(response).path("list").path("entries").forEach(e -> names.add(e.path("entry").path("name").asText()));
        return names;
    }

    // ------------------------------------------------------------------------------------------------ reads

    @Test
    void nodeIsCopiedFromFallbackOnceThenServedLocally() throws Exception {
        HttpResponse<String> first = proxy.get(API + "/nodes/" + DOC);
        assertEquals(200, first.statusCode());
        assertEquals("report.pdf", json(first).path("entry").path("name").asText());

        HttpResponse<String> second = proxy.get(API + "/nodes/" + DOC);
        assertEquals(200, second.statusCode());
        assertEquals("report.pdf", json(second).path("entry").path("name").asText());
        assertEquals(1, fallbackReads.stream().filter(u -> u.startsWith(API + "/nodes/" + DOC + "?")).count());
        assertTrue(audit.get(1).servedLocally());
        assertTrue(backendWrites.isEmpty());
    }

    @Test
    void contentIsCopiedAndServedAsAttachmentByDefault() throws Exception {
        HttpResponse<String> content = proxy.get(API + "/nodes/" + DOC + "/content");
        assertEquals(200, content.statusCode());
        assertEquals("PDF-BYTES", content.body());
        assertTrue(content.headers().firstValue("Content-Disposition").orElse("").contains("attachment"));
        assertTrue(content.headers().firstValue("Content-Disposition").orElse("").contains("report.pdf"));

        HttpResponse<String> inline = proxy.get(API + "/nodes/" + DOC + "/content?attachment=false");
        assertEquals("PDF-BYTES", inline.body());
        assertFalse(inline.headers().firstValue("Content-Disposition").orElse("").contains("attachment"));
        assertEquals(1, fallbackReads.stream().filter(u -> u.contains("/content?")).count());
    }

    @Test
    void unknownNodeIsNotFound() throws Exception {
        HttpResponse<String> response = proxy.get(API + "/nodes/00000000-0000-0000-0000-000000000000");
        assertEquals(404, response.statusCode());
    }

    @Test
    void aliasIsResolvedThroughTheBackends() throws Exception {
        HttpResponse<String> response = proxy.get(API + "/nodes/-my-");
        assertEquals(200, response.statusCode());
        assertEquals(FOLDER, json(response).path("entry").path("id").asText());
    }

    @Test
    void subResourcesAreCachedFromTheBackends() throws Exception {
        assertEquals(200, proxy.get(API + "/nodes/" + DOC + "/versions").statusCode());
        HttpResponse<String> again = proxy.get(API + "/nodes/" + DOC + "/versions");
        assertEquals("1.0", json(again).path("list").path("entries").get(0).path("entry").path("id").asText());
        assertEquals(1, fallbackReads.stream().filter(u -> u.endsWith("/versions")).count(), fallbackReads::toString);
    }

    @Test
    void legacyContentWebscriptIsServedFromTheCopy() throws Exception {
        HttpResponse<String> response = proxy.get("/alfresco/s/api/node/content/workspace/SpacesStore/" + DOC + "?a=true");
        assertEquals(200, response.statusCode());
        assertEquals("PDF-BYTES", response.body());
        assertTrue(response.headers().firstValue("Content-Disposition").orElse("").contains("report.pdf"));

        proxy.put(API + "/nodes/" + DOC + "/content", "text/plain", "NEW");
        assertEquals("NEW", proxy.get("/alfresco/s/api/node/content/workspace/SpacesStore/" + DOC).body());
    }

    // ----------------------------------------------------------------------------------------------- writes

    @Test
    void metadataUpdateStaysLocal() throws Exception {
        HttpResponse<String> update = proxy.putJson(API + "/nodes/" + DOC,
                "{\"name\":\"renamed.pdf\",\"properties\":{\"cm:title\":\"New title\"}}");
        assertEquals(200, update.statusCode());
        JsonNode entry = json(update).path("entry");
        assertEquals("renamed.pdf", entry.path("name").asText());
        assertEquals("New title", entry.path("properties").path("cm:title").asText());

        JsonNode reread = json(proxy.get(API + "/nodes/" + DOC)).path("entry");
        assertEquals("renamed.pdf", reread.path("name").asText());
        assertEquals("New title", reread.path("properties").path("cm:title").asText());
        assertTrue(backendWrites.isEmpty(), backendWrites::toString);
    }

    @Test
    void contentUpdateStaysLocal() throws Exception {
        HttpResponse<String> put = proxy.put(API + "/nodes/" + DOC + "/content?name=notes.txt", "text/plain", "hello");
        assertEquals(200, put.statusCode());
        JsonNode entry = json(put).path("entry");
        assertEquals("notes.txt", entry.path("name").asText());
        assertEquals(5, entry.path("content").path("sizeInBytes").asInt());
        assertEquals("text/plain", entry.path("content").path("mimeType").asText());

        assertEquals("hello", proxy.get(API + "/nodes/" + DOC + "/content").body());
        assertTrue(backendWrites.isEmpty(), backendWrites::toString);
    }

    @Test
    void deleteIsSoftAndLocal() throws Exception {
        assertEquals(204, proxy.delete(API + "/nodes/" + DOC).statusCode());
        assertEquals(404, proxy.get(API + "/nodes/" + DOC).statusCode());
        assertEquals(404, proxy.get(API + "/nodes/" + DOC + "/content").statusCode());
        assertEquals(404, proxy.delete(API + "/nodes/" + DOC).statusCode());
        assertEquals(List.of("sibling.pdf"), childNames(proxy.get(API + "/nodes/" + FOLDER + "/children")));
        assertTrue(backendWrites.isEmpty(), backendWrites::toString);
    }

    @Test
    void createFolderAndDocumentFromJson() throws Exception {
        HttpResponse<String> folder = proxy.postJson(API + "/nodes/" + FOLDER + "/children",
                "{\"name\":\"Sub\",\"nodeType\":\"cm:folder\",\"properties\":{\"cm:title\":\"Sub title\"}}");
        assertEquals(201, folder.statusCode());
        JsonNode folderEntry = json(folder).path("entry");
        assertTrue(folderEntry.path("isFolder").asBoolean());
        assertEquals(FOLDER, folderEntry.path("parentId").asText());
        String subId = folderEntry.path("id").asText();

        HttpResponse<String> doc = proxy.postJson(API + "/nodes/" + subId + "/children",
                "{\"name\":\"empty.txt\",\"nodeType\":\"cm:content\"}");
        assertEquals(201, doc.statusCode());
        assertEquals(subId, json(doc).path("entry").path("parentId").asText());

        assertEquals(List.of("report.pdf", "sibling.pdf", "Sub"),
                childNames(proxy.get(API + "/nodes/" + FOLDER + "/children")));
        assertEquals(List.of("empty.txt"), childNames(proxy.get(API + "/nodes/" + subId + "/children")));
        assertEquals(200, proxy.get(API + "/nodes/" + subId).statusCode());
        assertEquals(0, json(proxy.get(API + "/nodes/" + subId + "/versions")).path("list").path("entries").size());

        assertEquals(400, proxy.postJson(API + "/nodes/" + FOLDER + "/children", "{\"nodeType\":\"cm:content\"}").statusCode());
        assertEquals(400, proxy.postJson(API + "/nodes/" + FOLDER + "/children",
                "{\"name\":\"x\",\"nodeType\":\"cm:folder\",\"relativePath\":\"a/b\"}").statusCode());
        assertTrue(backendWrites.isEmpty(), backendWrites::toString);
    }

    @Test
    void multipartUploadCreatesADocument() throws Exception {
        Multipart multipart = new Multipart()
                .field("name", "upload.txt")
                .field("cm:title", "Uploaded")
                .file("filedata", "local.txt", "text/plain", "uploaded content");
        HttpResponse<String> created = proxy.send("POST", API + "/nodes/" + FOLDER + "/children",
                multipart.contentType(), multipart.body());
        assertEquals(201, created.statusCode(), created.body());
        JsonNode entry = json(created).path("entry");
        assertEquals("upload.txt", entry.path("name").asText());
        assertEquals("Uploaded", entry.path("properties").path("cm:title").asText());
        assertEquals("text/plain", entry.path("content").path("mimeType").asText());

        assertEquals("uploaded content", proxy.get(API + "/nodes/" + entry.path("id").asText() + "/content").body());
        assertTrue(childNames(proxy.get(API + "/nodes/" + FOLDER + "/children")).contains("upload.txt"));
        assertTrue(backendWrites.isEmpty(), backendWrites::toString);
    }

    @Test
    void createUnderAMissingParentFails() throws Exception {
        HttpResponse<String> response = proxy.postJson(API + "/nodes/00000000-0000-0000-0000-000000000000/children",
                "{\"name\":\"x\",\"nodeType\":\"cm:folder\"}");
        assertEquals(404, response.statusCode());
        assertTrue(backendWrites.isEmpty(), backendWrites::toString);
    }

    @Test
    void copyAndMoveStayLocal() throws Exception {
        HttpResponse<String> copy = proxy.postJson(API + "/nodes/" + DOC + "/copy",
                "{\"targetParentId\":\"" + OTHER_FOLDER + "\",\"name\":\"copy.pdf\"}");
        assertEquals(201, copy.statusCode(), copy.body());
        String copyId = json(copy).path("entry").path("id").asText();
        assertEquals(OTHER_FOLDER, json(copy).path("entry").path("parentId").asText());
        assertEquals("PDF-BYTES", proxy.get(API + "/nodes/" + copyId + "/content").body());

        HttpResponse<String> move = proxy.postJson(API + "/nodes/" + SIBLING + "/move",
                "{\"targetParentId\":\"" + OTHER_FOLDER + "\"}");
        assertEquals(200, move.statusCode(), move.body());
        assertEquals(OTHER_FOLDER, json(move).path("entry").path("parentId").asText());

        assertEquals(List.of("report.pdf"), childNames(proxy.get(API + "/nodes/" + FOLDER + "/children")));
        assertEquals(List.of("copy.pdf", "sibling.pdf"),
                childNames(proxy.get(API + "/nodes/" + OTHER_FOLDER + "/children")).stream().sorted().toList());
        assertEquals(400, proxy.postJson(API + "/nodes/" + DOC + "/move", "{}").statusCode());
        assertTrue(backendWrites.isEmpty(), backendWrites::toString);
    }

    @Test
    void lockAndUnlockStayLocal() throws Exception {
        HttpResponse<String> lock = proxy.postJson(API + "/nodes/" + DOC + "/lock?include=isLocked", "{\"type\":\"ALLOW_OWNER_CHANGES\"}");
        assertEquals(200, lock.statusCode(), lock.body());
        assertTrue(json(lock).path("entry").path("isLocked").asBoolean());
        assertTrue(json(proxy.get(API + "/nodes/" + DOC + "?include=isLocked")).path("entry").path("isLocked").asBoolean());

        HttpResponse<String> unlock = proxy.postJson(API + "/nodes/" + DOC + "/unlock?include=isLocked", "");
        assertEquals(200, unlock.statusCode(), unlock.body());
        assertFalse(json(unlock).path("entry").path("isLocked").asBoolean());
        assertTrue(backendWrites.isEmpty(), backendWrites::toString);
    }

    @Test
    void otherWritesAreRejectedAndNeverForwarded() throws Exception {
        assertEquals(403, proxy.postJson(API + "/nodes/" + DOC + "/secondary-children", "{}").statusCode());
        assertEquals(403, proxy.postJson(API + "/people", "{\"id\":\"bob\"}").statusCode());
        assertEquals(403, proxy.put("/alfresco/s/some/webscript", "text/plain", "x").statusCode());
        assertTrue(backendWrites.isEmpty(), backendWrites::toString);
    }

    @Test
    void readOnlySearchPostIsForwarded() throws Exception {
        HttpResponse<String> search = proxy.postJson("/alfresco/api/-default-/public/search/versions/1/search",
                "{\"query\":{\"query\":\"cm:name:*\"}}");
        assertEquals(200, search.statusCode());
        assertEquals(1, backendWrites.size());
        assertTrue(backendWrites.get(0).startsWith("primary POST"));
    }

    // ---------------------------------------------------------------------------------------------- config

    @Test
    void localStoreApisAreParsedFromYaml() {
        ProxyConfig config = ProxyConfig.fromYaml("""
                proxy:
                  local-store:
                    enabled: true
                    directory: data
                    read-only-post-paths:
                      - /custom/query
                    apis:
                      - alfresco-rest
                      - name: other
                        base-path: /x
                        flag: true
                """);
        LocalStoreConfig store = config.localStore();
        assertTrue(store.enabled());
        assertEquals(Path.of("data"), store.directory());
        assertEquals(List.of("/custom/query"), store.readOnlyPostPaths());
        assertEquals(List.of("alfresco-rest", "other"), store.apis().stream().map(LocalStoreConfig.DocumentApiConfig::name).toList());
        assertEquals("/x", store.apis().get(1).setting("base-path", "default"));
        assertTrue(store.apis().get(1).setting("flag", false));
        assertFalse(store.isWrite("POST", "/custom/query"));
        assertTrue(store.isWrite("POST", "/custom/other"));
    }

    @Test
    void bundledConfigurationEnablesTheRestApi() {
        ProxyConfig config = ProxyConfig.fromClasspath("application.yaml");
        LocalStoreConfig.DocumentApiConfig rest = config.localStore().apis().get(0);
        assertEquals(LocalStoreConfig.DEFAULT_API, rest.name());
        assertEquals("cm:folder", rest.setting("folder-types", ""));
        assertTrue(config.directResponseDefinitions().stream().anyMatch(d -> d.echo()));
    }

    @Test
    void defaultApiIsTheStandardRestApi() {
        assertEquals(List.of(LocalStoreConfig.DEFAULT_API),
                ProxyConfig.fromYaml("proxy:\n  local-store:\n    enabled: true\n").localStore().apis().stream()
                        .map(LocalStoreConfig.DocumentApiConfig::name).toList());
        assertEquals(List.of(LocalStoreConfig.DEFAULT_API), proxy.documentApis());
    }

    @Test
    void unknownApiNameFailsAtStartup() {
        ProxyConfig config = ProxyConfig.builder()
                .targetBaseUrl(primary.url())
                .localStore(LocalStoreConfig.disabled().withEnabled(true).withDirectory(storeDir)
                        .withApis(List.of(new LocalStoreConfig.DocumentApiConfig("nope", java.util.Map.of()))))
                .build();
        Exception error = assertThrows(Exception.class, () -> new Proxy(config, record -> { }).close());
        assertTrue(String.valueOf(error.getMessage()).contains("alfresco-rest"), error.getMessage());
    }

    @Test
    void nodeRefRejectsPathTraversal() {
        assertNull(NodeRef.of("workspace", "..", DOC));
        assertNull(NodeRef.of("workspace", "SpacesStore", "../../etc"));
        assertNull(NodeRef.ofId("a/b"));
        assertNull(NodeRef.parse("workspace://SpacesStore/.."));
        NodeRef ref = NodeRef.parse("workspace://SpacesStore/" + DOC);
        assertNotNull(ref);
        assertEquals(DOC, ref.guid());
        assertEquals("workspace://SpacesStore/" + DOC, ref.toString());
    }

    @Test
    void storedFilesStayInsideTheStoreDirectory() throws Exception {
        proxy.get(API + "/nodes/" + DOC + "/content");
        try (var files = java.nio.file.Files.walk(storeDir)) {
            assertTrue(files.filter(java.nio.file.Files::isRegularFile)
                    .allMatch(p -> p.toAbsolutePath().normalize().startsWith(storeDir.toAbsolutePath().normalize())));
        }
    }
}
