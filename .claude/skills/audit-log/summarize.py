"""Summarize the alfresco-proxy NDJSON audit log without printing sensitive fields."""
import argparse
import json
import re
import sys
from collections import Counter, defaultdict

ID_SEGMENT = re.compile(
    r"/(\d+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?=/|$)"
)


def normalize(uri: str) -> str:
    return ID_SEGMENT.sub("/{id}", uri.split("?", 1)[0])


def load(path, uri_filter, since):
    records, bad = [], 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                bad += 1
                continue
            if uri_filter and uri_filter not in r.get("uri", ""):
                continue
            if since and r.get("timestamp", "") < since:
                continue
            records.append(r)
    return records, bad


def main():
    p = argparse.ArgumentParser()
    p.add_argument("file", nargs="?", default="requests.json")
    p.add_argument("--uri", help="only records whose URI contains this substring")
    p.add_argument("--since", help="ISO timestamp prefix, e.g. 2026-10-06T14:00")
    p.add_argument("--top", type=int, default=10)
    a = p.parse_args()

    records, bad = load(a.file, a.uri, a.since)
    if not records:
        print("No matching records." + (f" ({bad} malformed lines)" if bad else ""))
        return

    ts = sorted(r.get("timestamp", "") for r in records)
    print(f"Records: {len(records)}  ({ts[0]} -> {ts[-1]})" + (f"  malformed lines: {bad}" if bad else ""))

    status = Counter(r.get("responseStatus") for r in records)
    print("\nStatus:", ", ".join(f"{k}={v}" for k, v in sorted(status.items(), key=lambda kv: str(kv[0]))))

    fallback = sum(1 for r in records if r.get("forwardedToFallback"))
    direct = sum(1 for r in records if r.get("interceptedDirectly"))
    blocked = sum(
        1 for r in records
        if r.get("responseStatus") == 403 and not r.get("interceptedDirectly") and not r.get("forwardedToFallback")
        and str((r.get("responseHeaders", {}).get("Content-Type") or [""])[0]).lower().startswith("text/plain")
    )
    local = sum(1 for r in records if r.get("servedLocally"))
    print(f"Fallback: {fallback}   Direct: {direct}   Local: {local}   Blocked (likely): {blocked}")

    targets = Counter()
    for r in records:
        m = re.match(r"https?://[^/]+", r.get("targetUrl") or "")
        targets[m.group(0) if m else "(none)"] += 1
    print("\nTargets:")
    for t, n in targets.most_common():
        print(f"  {n:6d}  {t}")

    per_ep = defaultdict(lambda: [0, 0, 0])  # total, errors(>=400), fallbacks
    for r in records:
        k = f"{r.get('method')} {normalize(r.get('uri', ''))}"
        per_ep[k][0] += 1
        if (r.get("responseStatus") or 0) >= 400:
            per_ep[k][1] += 1
        if r.get("forwardedToFallback"):
            per_ep[k][2] += 1

    print(f"\nTop endpoints (total / errors / fallbacks):")
    for k, (t, e, fb) in sorted(per_ep.items(), key=lambda kv: -kv[1][0])[: a.top]:
        print(f"  {t:5d} {e:5d} {fb:5d}  {k}")

    failing = [(k, v) for k, v in per_ep.items() if v[1]]
    if failing:
        print(f"\nMost failing endpoints:")
        for k, (t, e, fb) in sorted(failing, key=lambda kv: -kv[1][1])[: a.top]:
            print(f"  {e:5d}/{t:<5d} {k}")

    print(f"\nSlowest calls:")
    for r in sorted(records, key=lambda r: -(r.get("durationMs") or 0))[: a.top]:
        fb = " [FALLBACK]" if r.get("forwardedToFallback") else ""
        print(
            f"  {r.get('durationMs'):7d} ms  {r.get('responseStatus')}  {r.get('method')} {normalize(r.get('uri', ''))}{fb}")


if __name__ == "__main__":
    sys.exit(main())
