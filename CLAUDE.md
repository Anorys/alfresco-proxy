# alfresco-proxy

Streaming reverse proxy in front of Alfresco ECM (Java 21 · Jetty 12 ee10 servlet on virtual threads ·
`java.net.http.HttpClient` · Jackson · SnakeYAML · Maven). Relays traffic to a primary Alfresco target, fails over to a
fallback target, can answer some routes directly or block them, can serve documents from a local store, and writes an
NDJSON audit log.

## Commands

Maven (`mvn`) and JDK 21 are on PATH. No wrapper.

```bash
mvn -q -B test                                                       # full suite (~56 tests, about a minute)
mvn -q -B test -Dtest='StreamingReverseProxyLoggerTest#*fallback*'   # filter test methods by glob
mvn -q -B compile                                                    # compile only
mvn exec:java                                                        # run proxy (loads ./application.yaml, port 9081)
mvn exec:java -Dexec.args="--port=9090 --target=http://localhost:8080"
```

Test output is noisy (banner and proxy printf lines). For results, read `target/surefire-reports/*.txt`.

## Architecture & request flow

Everything lives under `src/main/java/plg/gesraco/tools/proxy/`. Entry point: `StreamingReverseProxyLogger.main` →
`ProxyConfig.fromArgs` → `StreamingReverseProxy.start`.

Order in which a request is resolved:

1. `customRoutes` (`DirectRequestHandler`s passed to `StreamingReverseProxy`; not audited)
2. `handler/ReverseProxyHandler.handle`:
    - `DirectRequestHandler`s (from YAML `direct-responses` or code), first match wins
    - when `local-store.enabled`: `store/LocalDocuments` applies the configured `local-store.routes`
      (`store/LocalRoutes`, rules in `config/RouteRule`: proxy / cache / simulate / block), then asks each
      `DocumentApi` in turn. An API returns an `Outcome`
      when it answered, or null when the request isn't its own. Any other write (`LocalDocuments.isWrite`) gets `403`
      and never reaches a backend. APIs reach the backends through `Upstream` (implemented by `ReverseProxyHandler`:
      primary → fallback)
    - `BlockedRequestRule`s for the primary and the fallback (glob paths: `*` = one segment, `**` = recursive)
    - primary target → fallback when the primary fails, its status is in `fallback-on-status`, or the request is blocked
      on the primary
    - audit record written in `finally`: every path must still produce exactly one `ProxyAuditRecord`

Document APIs (`store/`):

- `DocumentApi` / `DocumentApiProvider` form an SPI loaded with `ServiceLoader`, from the classpath and from jars in
  `plugins-directory`. `local-store.apis` selects providers by name (default `alfresco-rest`). Each API keeps its copies
  in `<local-store.directory>/<name>` unless it sets `directory`.
- `LocalNodes` is the API-neutral engine: copy on first read (`loadOrCopy`), per-node locks, content files, cached
  sub-resources, the legacy `/api/node/content` download, record flags (deleted, locally modified, local origin).
- `store/rest/AlfrescoRestApi` is the built-in standard REST API v1 (`nodes/{id}`, `/content`, `/children`, `/copy`,
  `/move`, `/lock`, `/unlock`, other GET sub-resources). `RestEntries` builds and filters REST entries.
- `LocalRoutes` describes an API in configuration only: resources keyed by a template, stored in
  `<directory>/routes/<sha-256 of key>/`. A `proxy` route matching writes needs `read-only: true` or
  `allow-write-proxy: true` (checked when `LocalStoreConfig` is built).
- No API-specific name belongs in the core code: other APIs ship as plugins or as routes, named only in configuration.

Config resolution (`config/ProxyConfig.java`): defaults → YAML (`--config` / `-Dproxy.config` / `PROXY_CONFIG` →
`./application.yaml` → `./application.yml` → classpath) → env vars / system props → CLI `--flags`. `parseYamlMap`
accepts several key aliases (kebab-case, camelCase, with or without the `proxy.` prefix) through `extractValue`.

## Invariants: don't break these

- **Streaming vs. buffering.** If no fallback is configured, binary request bodies are streamed. If a fallback is
  configured, they are fully buffered so they can be replayed. Textual bodies (json/xml/text/urlencoded) are always
  buffered. Responses are always streamed (`BodyHandlers.ofInputStream`). Never buffer response bodies.
- **Query strings are parsed from the raw query (`Http.queryParameter`), never with `getParameter`**, which would
  consume urlencoded request bodies.
- **Local store mode (`local-store.enabled`) is write-proof.** No POST/PUT/PATCH/DELETE may reach Alfresco, except the
  read-only POSTs (`read-only-post-paths`, each API's `readOnlyPostPaths()`, `read-only` proxy routes) and proxy
  routes allowed by `allow-write-proxy`. Store backend calls are GETs. The
  store holds JSON responses in memory (they are small) and streams content to disk, then serves it from disk. Node
  directories are named from `NodeRef` segments, which are validated against path traversal, and content file names in
  `node.json` are checked before use.
- **Hop-by-hop headers** are stripped in both directions (`HOP_BY_HOP_REQUEST_HEADERS` / `HOP_BY_HOP_RESPONSE_HEADERS`,
  which also cover the headers `java.net.http` refuses to set). When target basic auth is configured, the client
  `Authorization` header is replaced, never forwarded alongside it.
- **Redaction** applies only to the headers listed in `logging.sensitive-headers`. Redaction is done in
  `JsonFileProxyLogger`.
- Console output goes through `System.out/err.printf` with the tags `--->`, `<---`, `<=DIRECT=>`, `<=LOCAL=>`,
  `[BLOCK]`, `[WARN]`, `[ERR]`, `[FALLBACK]`. Logback only serves Jetty (set to WARN). Keep this style.

## Adding a config option

Use the `add-config-option` skill. A config option touches `ProxyConfig` (field, builder, `parseYamlMap`, maybe
`fromArgs`), both `application.yaml` files, the README config table, and the tests. A document API setting only needs
`DocumentApiConfig.setting(...)` in the API, the YAML files and the README.

## Tests

Tests are in `src/test/java/plg/gesraco/tools/` (JUnit 5). `TestSupport` provides fake backends (`Backend`, on the
JDK `HttpServer`, any free port), a proxy client (`Proxy`, starts `StreamingReverseProxy` on port 0) and a `Multipart`
builder. `StreamingReverseProxyLoggerTest` covers proxying and config. `LocalRoutesTest` covers the configured routes. `LocalDocumentStoreTest` covers the REST local
store against fake backends that record writes (the tests assert `backendWrites` is empty). Capture audit records with
a `ProxyLogger` lambda. Use `@TempDir` for store, config or log files.

## Known gaps (verified 2026-10-29; re-check before relying on them)

- The HttpClient follows redirects (`Redirect.NORMAL`), so Alfresco 3xx responses get followed by the proxy instead of
  being relayed to the client.
- The REST store doesn't support `relativePath` on create, and binary sub-resources (`versions/{id}/content`,
  renditions) are proxied, not copied.

## Sensitive files: never commit

- Root `application.yaml` (git-ignored) contains real target/fallback **credentials** and the private document API
  settings. Use `src/main/resources/application.yaml` or a temp YAML for examples and tests. `application.example.yaml`
  is its committed template, with placeholder credentials. Keep it in sync when adding config keys.
- `local-store/` (git-ignored) holds copies of real documents and their metadata. `plugins/` (git-ignored) holds
  plugin jars.
- `requests.json` is the live audit log. With `authorization` left out of `sensitive-headers`, it holds unredacted
  Basic auth headers. Don't paste its raw contents into commits, PRs, or chat; summarize it (see the `audit-log` skill).

## Conventions

- 4-space indent, 120 columns, Javadoc on public classes. Records for value types.
- Immutable `ProxyConfig` with a `Builder` and `withXxx(...)` methods. Add new options the same way.
- Git: work on a branch; `main` is the PR base.
