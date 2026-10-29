---
name: add-config-option
description: Checklist for adding or changing a proxy configuration option (YAML key, env var, CLI flag) in alfresco-proxy. Use whenever a change introduces, renames, or removes a setting in ProxyConfig or application.yaml.
---

# Adding a configuration option

A setting lives in up to six places. Missing one causes silent drift (see the "Known gaps" section in CLAUDE.md for past
examples). Work through every step and skip a step only when it truly doesn't apply. Say so explicitly when you skip
one.

## 1. `ProxyConfig` (`src/main/java/plg/gesraco/tools/proxy/config/ProxyConfig.java`)

- Add a `private final` field, its accessor, and a `Builder` field with a sensible default (plus the copy in
  `Builder(ProxyConfig)`).
- If it needs normalisation (trim, lowercase, base64), add a `normalizedXxx()` method next to the existing ones instead
  of normalising at the use site.
- If code/tests will set it programmatically, add a `withXxx(...)` method that matches the existing ones.
- A setting of a document API (`local-store.apis[].<key>`) needs none of this: read it in the API with
  `DocumentApiConfig.setting(key, default)`.

## 2. YAML parsing (`parseYamlMap`)

- Read it via `extractValue(...)`. List the canonical kebab-case key under `proxy.` first, then the aliases (camelCase,
  without the `proxy.` prefix), following the neighbouring entries.
- Set it on the builder only when the key is present, so the builder default applies otherwise.

## 3. Overrides (`fromArgs`), only if operators need to set it at runtime

- System property → env var (`UPPER_SNAKE`) → CLI `--kebab-flag=`; CLI wins.
- Apply it on the builder in `fromArgs`.

## 4. Wire it into behaviour

- Usually `handler/ReverseProxyHandler.java` or `server/StreamingReverseProxy.java` (e.g. HttpClient setup).
- If it's visible to operators, print it in the startup banner in `StreamingReverseProxy.printBanner`.

## 5. Config files

- `src/main/resources/application.yaml`: add with a comment and a **non-secret** example value.
- `application.example.yaml`: add with a comment and a placeholder value (it's the committed template for the
  git-ignored root config).
- Root `application.yaml` (git-ignored, real credentials): add the key there only with the user's agreement.

## 6. Docs and tests

- README: YAML example and the **Configuration Reference** table (YAML key, CLI flag, env var, default).
- Tests in `StreamingReverseProxyLoggerTest.java`:
    - a parsing test with `ProxyConfig.fromYaml("""...""")` (text block), including at least one alias
    - an override test with `fromArgs(new String[]{...})` if step 3 applied (use `@TempDir` for the YAML file)
    - a behaviour test if step 4 changed the request flow
- Run `mvn -q -B test` and check `target/surefire-reports/*.txt`.
