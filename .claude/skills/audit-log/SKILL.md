---
name: audit-log
description: Analyze the proxy's NDJSON audit log (requests.json) – status breakdown, fallback usage, blocked/direct requests, slowest calls, errors per endpoint. Use when the user asks what traffic went through the proxy, why requests fell back, which calls failed, or to investigate a specific URI.
---

# Analyzing the audit log

`requests.json` (or the file set by `proxy.logging.file` / `--log-file` / `LOG_FILE`) contains one JSON object per line.
Each object is a `ProxyAuditRecord`:
`timestamp, durationMs, clientIp, method, uri, requestHeaders, requestBody, responseStatus, responseHeaders, interceptedDirectly, forwardedToFallback, targetUrl, servedLocally`.

**The log may contain unredacted `Authorization` headers and request bodies with personal data.** Don't print raw
records. Use the summary script, and mask header values if you need to show a single record.

## Summary

```bash
python -I .claude/skills/audit-log/summarize.py requests.json
python -I .claude/skills/audit-log/summarize.py requests.json --uri /alfresco/api/-default-   # filter by URI substring
python -I .claude/skills/audit-log/summarize.py requests.json --since 2026-10-06T14:00
```

It prints the totals, the status distribution, fallback/direct/blocked counts, the endpoints that fail most, and the
slowest calls. Query strings are stripped from URIs and numeric or UUID path segments are collapsed to `{id}`.

## Ad-hoc questions

For anything the script doesn't cover, write a short throwaway Python snippet in the scratchpad that reads the file line
by line with `json.loads`. Skip malformed lines: a crash mid-write can truncate the last line.

How to read the records:

- `forwardedToFallback: true` with a 2xx status → the primary failed or was blocked, and the fallback served the
  request.
- Status 403 with `interceptedDirectly: false` and a `text/plain` response header → a blocking rule matched.
- `servedLocally: true` → answered by the local document store (copy or local write).
- Status 502/504 → a proxy-level error. The console log (`[ERR] ...`) has the exception message; the audit log doesn't.
