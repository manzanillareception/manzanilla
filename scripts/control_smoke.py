"""Read-only V2 liveness/readiness and unauthenticated-access checks."""
from __future__ import annotations

import argparse
import json
import time
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


def fetch(base: str, path: str) -> tuple[int, object]:
    request = Request(base.rstrip("/") + path, headers={"Accept": "application/json"})
    try:
        with urlopen(request, timeout=4) as response:
            data = response.read(65537)
            if len(data) > 65536:
                raise ValueError("Oversized health response")
            return response.status, json.loads(data)
    except HTTPError as exc:
        return exc.code, None


def check_portal(base: str) -> None:
    request = Request(base.rstrip("/") + "/", headers={"Accept": "text/html"})
    with urlopen(request, timeout=4) as response:
        body = response.read(131073)
        if (response.status != 200 or not response.headers.get("Content-Type", "").startswith("text/html")
                or len(body) > 131072 or b"<html" not in body.lower()):
            raise ValueError("Portal document is unavailable")


def check(base: str, *, portal: bool = False) -> None:
    for path in ("/health/live", "/health/ready"):
        status, payload = fetch(base, path)
        if status != 200 or not isinstance(payload, dict) or payload.get("status") != "ok":
            raise ValueError("Service not healthy")
    status, _ = fetch(base, "/api/v2/auth/session")
    if status != 401:
        raise ValueError("Unauthenticated session must be denied")
    status, _ = fetch(base, "/api/v2/hotels")
    if status != 401:
        raise ValueError("Unauthenticated hotel listing must be denied")
    if portal:
        check_portal(base)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--wait-seconds", type=int, default=0)
    parser.add_argument("--portal", action="store_true", help="Also require a served HTML portal at the HTTPS origin")
    args = parser.parse_args(argv)
    try:
        parts = urlsplit(args.base_url)
        if parts.username or parts.password or parts.query or parts.fragment or parts.path not in ("", "/"):
            raise ValueError("Use a plain origin")
        if parts.scheme != "https" and not (parts.scheme == "http" and parts.hostname in {"localhost", "127.0.0.1", "::1"}):
            raise ValueError("HTTPS required outside loopback")
        if not 0 <= args.wait_seconds <= 60:
            raise ValueError("Wait limit is 60 seconds")
        end = time.monotonic() + args.wait_seconds
        while True:
            try:
                check(args.base_url, portal=args.portal)
                print(json.dumps({"healthy": True, "unauthenticated_access_denied": True}))
                return 0
            except (ValueError, OSError, URLError):
                if time.monotonic() >= end:
                    raise
                time.sleep(min(2, max(0, end - time.monotonic())))
    except (ValueError, OSError, URLError):
        print("Control smoke check failed; inspect sanitized service readiness and authentication status.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
