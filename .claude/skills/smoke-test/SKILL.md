---
name: smoke-test
description: Start the alfresco-proxy locally against throwaway config and hit it with curl to confirm a change works end-to-end (direct routes, blocking, fallback, header handling). Use after behavioural changes when unit tests aren't enough, or when the user asks to "run the proxy" / "try it".
---

# Smoke-testing the proxy

The root `application.yaml` points at real Alfresco servers with real credentials. **Don't smoke-test against it**
unless the user explicitly asks. Use an isolated config instead.

## 1. Write a throwaway config in the scratchpad

```yaml
proxy:
  port: 19081
  target-base-url: "http://127.0.0.1:19999"      # nothing listening -> exercises fallback path
  fallback-target-base-url: "http://127.0.0.1:19998"
  fallback-on-status: [404, 500, 502, 503, 504]
  logging:
    file: "<scratchpad>/smoke-requests.json"
  direct-responses:
    - path: "/health"
      methods: ["GET"]
      body: '{"status":"UP"}'
    - path: "/echo"
      methods: ["POST", "PUT"]
      echo: true
```

If you need a live backend, start a trivial one, for example `python -m http.server 19998` in a scratchpad directory.
You can also leave the target unreachable on purpose to check the 502 behaviour.

## 2. Start the proxy in the background

```bash
mvn -q compile && mvn -q exec:java -Dexec.args="--config=<scratchpad>/smoke.yaml"
```

Use `run_in_background: true`. Wait for the `Proxy started on` banner (Monitor with an until-loop on
`curl -s localhost:19081/health`). Don't use a fixed sleep.

## 3. Exercise it

```bash
curl -si localhost:19081/health                                         # direct response, 200
curl -si -X POST localhost:19081/echo -H 'Content-Type: application/json' -d '{"a":1}'   # echo direct response
curl -si localhost:19081/alfresco/api/whatever                          # proxied -> fallback or 502
```

Then summarize the log: `python -I .claude/skills/audit-log/summarize.py <scratchpad>/smoke-requests.json`.

## 4. Clean up

Stop the background task (TaskStop), along with any helper backend. Report what you ran and the actual status codes you
got.
