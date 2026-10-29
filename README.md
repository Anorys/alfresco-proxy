# alfresco-proxy

Streaming reverse proxy in front of Alfresco ECM, in Java 21 (Jetty 12 on virtual threads, `java.net.http.HttpClient`,
Maven).

- Relays every request to a **primary** Alfresco and fails over to a **fallback** Alfresco when the primary errors, is
  unreachable, answers a configured status (`fallback-on-status`) or blocks the request.
- **Blocks** requests per target with glob rules (`*` = one path segment, `**` = any depth).
- Answers some routes **directly** from configuration (health checks, mocks, echo).
- Replaces the client's `Authorization` header with the configured **Basic auth** of each target.
- Optionally keeps a **local document store**: documents are copied from Alfresco on first read, then served and
  modified locally. In that mode no write ever reaches Alfresco.
- Writes one **NDJSON audit record** per request (`requests.json`), with sensitive headers redacted.

Responses are always streamed. Request bodies are streamed too, except textual bodies and bodies that may be replayed
to the fallback.

## Build and run

Requires JDK 21 and Maven.

```bash
mvn -q -B test                       # test suite
mvn -q -B package                    # target/alfresco-proxy-1.0-SNAPSHOT.jar
mvn exec:java                        # run with ./application.yaml (port 9081)
mvn exec:java -Dexec.args="--port=9090 --target=http://localhost:8080"
```

Start from the template: `cp application.example.yaml application.yaml` (git-ignored), then fill in the targets and
credentials.

## Configuration

Resolution order: built-in defaults → YAML → system properties / environment variables → command-line flags (the last
one wins).

The YAML file is the first one found among `--config=<file>`, `-Dproxy.config`, `PROXY_CONFIG`, `./application.yaml`,
`./application.yml`, then `application.yaml` on the classpath (`src/main/resources/application.yaml`). Keys are
kebab-case under `proxy:`; camelCase aliases and keys without the `proxy.` prefix are accepted too.

| YAML key | CLI flag | System property / env var | Default |
|---|---|---|---|
| `port` | `--port` | `proxy.port` / `PROXY_PORT` | `9081` |
| `target-base-url` | `--target` | `target.url` / `TARGET_URL` | `http://gesraco-alfresco7.afeliodev.local:8080` |
| `target-auth` (map `username`/`password`, `user:password`, base64 or `Basic …`) | `--target-auth`, `--target-username`, `--target-password` | `TARGET_AUTH`, `TARGET_USERNAME`, `TARGET_PASSWORD` | none |
| `target-blocked-requests` (list of `path`, `methods`) | | | none |
| `fallback-target-base-url` | `--fallback-target` | `fallback.target.url` / `FALLBACK_TARGET_URL` | none |
| `fallback-target-auth` | `--fallback-target-auth`, `--fallback-target-username`, `--fallback-target-password` | `FALLBACK_TARGET_AUTH`, `FALLBACK_TARGET_USERNAME`, `FALLBACK_TARGET_PASSWORD` | none |
| `fallback-target-blocked-requests` | | | none |
| `fallback-on-status` | | | `404, 500, 502, 503, 504` |
| `logging.file` | `--log-file` | `log.file` / `LOG_FILE` | `requests.json` |
| `logging.max-text-bytes` | | | `524288` |
| `logging.redact-sensitive-headers` | | | `true` |
| `logging.sensitive-headers` | | | `authorization, cookie, set-cookie, x-auth-token, alf_ticket` |
| `timeouts.connect-seconds` | | | `15` |
| `timeouts.request-minutes` | | | `10` |
| `forwarding.add-forwarded-headers` | | | `true` |
| `direct-responses` (list of `path`, `methods`, `status`, `content-type`, `body`, `echo`) | | | none |
| `plugins-directory` | | | `plugins` |
| `local-store.enabled` | | | `false` |
| `local-store.directory` | | | `local-store` |
| `local-store.copy-content-eagerly` | | | `true` |
| `local-store.read-only-post-paths` | | | none |
| `local-store.apis` (names, or maps with `name` and settings) | | | `alfresco-rest` |
| `local-store.routes` (list of `path`, `methods`, `mode`, `key`, `store`, `status`, `content-type`, `body`, `read-only`) | | | none |
| `local-store.allow-write-proxy` | | | `false` |

A blocked request on the primary goes to the fallback when the fallback allows it, otherwise the proxy answers the
rule's status (403 by default). A direct response with `echo: true` answers
`{"status":"ok","method":…,"path":…,"received":<request body>}`.

## Local document store

With `local-store.enabled: true`, the configured routes (see below) and each API listed in `local-store.apis` serve
their own URLs from local copies kept in
`<directory>/<api>/<space>/<store>/<guid>/` (`node.json` plus `content-*.bin`):

- **Reads**: a node is copied from the primary, or the fallback, the first time it is requested, then answered from
  the copy.
- **Writes** are applied to the copy only. Every other write (`POST`, `PUT`, `PATCH`, `DELETE`) gets `403` and is
  never forwarded, except the read-only POSTs declared by the APIs and those listed in `read-only-post-paths`.

### Standard REST API (`alfresco-rest`)

Built in. It handles `…/api/-default-/public/alfresco/versions/1/nodes/{id}` (aliases `-root-`, `-my-` and `-shared-`
are resolved through the backends):

| Request | Local behaviour |
|---|---|
| `GET nodes/{id}` (`include`, `fields`) | entry from the copy; missing `include`s fetched once |
| `PUT nodes/{id}` | name, node type, properties, aspects updated locally |
| `DELETE nodes/{id}` | marked deleted (later reads give 404) |
| `GET nodes/{id}/content` (`attachment`) | content from the copy |
| `PUT nodes/{id}/content` (`name`) | the request body becomes the content |
| `GET nodes/{id}/children` | backend list overlaid with local changes (edits, deletions, moves, creations) |
| `POST nodes/{id}/children` | JSON `NodeBodyCreate` or multipart upload (`filedata`); `relativePath` not supported |
| `POST nodes/{id}/copy`, `move`, `lock`, `unlock` | applied locally |
| other `GET nodes/{id}/…` (`versions`, `parents`, `renditions`…) | fetched once and cached; empty list for local nodes |

Settings: `base-path` (default `/alfresco/api/-default-/public/alfresco/versions/1`), `legacy-content` (default
`true`: also serves `/alfresco/s/api/node/content/{space}/{store}/{guid}` from the copies) and `folder-types` (default
`cm:folder`). The search and ticket POSTs are read-only.

### Routes: an API described in configuration

`local-store.routes` handles other URLs without code. The rules are checked before the document APIs, and the first
matching rule applies. `path` is a glob (`*` = one segment, `**` = any depth) whose `{name}` segments capture one
segment each.

| `mode` | Behaviour |
|---|---|
| `proxy` | relayed to Alfresco (primary, then fallback); a GET on a resource written locally is served locally |
| `cache` | GET/HEAD only: copied on the first successful read, then served from the copy (errors are relayed, not copied) |
| `simulate` | answered locally, never sent to Alfresco; `store` says what happens to the resource |
| `block` | refused with `status` (default 403) and an optional `body` |

`key` names the local resource of a rule (default `{uri}`, the path and query; `{path}` and the captured variables
can be used). Rules sharing a key share the resource, so a simulated write is visible to later `cache` or `proxy`
reads. `store` is `body` (the request body becomes the resource; the default for PUT/POST/PATCH), `delete` (later
reads give 404; the default for DELETE) or `none`. Without a configured `body`, a simulated write answers the stored
body and a delete answers `204`. Resources are kept in `<local-store.directory>/routes/`.

A `proxy` rule matching writes is refused at startup, unless it is `read-only: true` (a search or a bulk read sent as
POST) or `allow-write-proxy: true` is set.

```yaml
proxy:
  local-store:
    enabled: true
    routes:
      - path: "/alfresco/s/my-api/nodes/{space}/{store}/{guid}/metadata"
        methods: [GET, HEAD]
        mode: cache
        key: "{guid}/metadata"
      - path: "/alfresco/s/my-api/nodes/{space}/{store}/{guid}/metadata"
        methods: [PUT, POST]
        mode: simulate
        key: "{guid}/metadata"
      - path: "/alfresco/s/my-api/search"
        methods: [POST]
        mode: proxy
        read-only: true
      - path: "/alfresco/s/my-api/**"
        mode: block
```

### Document APIs as plugins

Other APIs are provided by jars dropped in `plugins-directory`. A plugin implements
`plg.gesraco.tools.proxy.store.DocumentApiProvider`, lists it in
`META-INF/services/plg.gesraco.tools.proxy.store.DocumentApiProvider`, and builds its `DocumentApi` on the API-neutral
engine `LocalNodes` (copy on first read, locks, content files, record helpers). Enable it by name:

```yaml
proxy:
  local-store:
    enabled: true
    apis:
      - alfresco-rest
      - name: "<provider-name>"
        <setting>: <value>          # read with DocumentApiConfig.setting(key, default)
```

An unknown name stops the proxy at startup with the list of available providers.

## Audit log

`requests.json` holds one JSON object per request: `timestamp`, `durationMs`, `clientIp`, `method`, `uri`,
`requestHeaders`, `requestBody` (textual bodies up to `max-text-bytes`), `responseStatus`, `responseHeaders`,
`interceptedDirectly`, `forwardedToFallback`, `targetUrl` and `servedLocally`. Headers in `sensitive-headers` are
replaced by `[REDACTED]`.

## Embedding

```java
ProxyConfig config = ProxyConfig.load()
        .withDirectJson("/status", "{\"up\":true}")
        .withTargetBlockedRule(BlockedRequestRule.of("/alfresco/s/admin/**", "POST"));
new StreamingReverseProxy(config).start(true);
```

`StreamingReverseProxy` also takes custom `DirectRequestHandler` routes (answered first, not audited) and
`ProxyInterceptor`s that adjust outgoing requests.
