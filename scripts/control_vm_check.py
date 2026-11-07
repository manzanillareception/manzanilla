"""Local VM resource report; does not establish residency or production approval."""
from __future__ import annotations

import argparse
import json
import os
import platform
from pathlib import Path
import shutil
import subprocess


def report(path: Path) -> dict:
    memory = None
    try:
        line = next(x for x in Path("/proc/meminfo").read_text().splitlines() if x.startswith("MemTotal:"))
        memory = int(line.split()[1]) * 1024
    except (OSError, StopIteration, ValueError):
        pass
    docker = shutil.which("docker")
    compose_ready = False
    daemon_ready = False
    if docker:
        env = {key: value for key, value in os.environ.items() if key not in {
            "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH"}}
        try:
            compose_ready = subprocess.run([docker, "compose", "version"], env=env, capture_output=True, timeout=10).returncode == 0
            daemon_ready = subprocess.run([docker, "--host=unix:///var/run/docker.sock", "info"], env=env, capture_output=True, timeout=10).returncode == 0
        except (OSError, subprocess.TimeoutExpired):
            pass
    return {"platform": platform.system(), "architecture": platform.machine(), "logical_cpus": os.cpu_count(),
            "memory_bytes": memory, "free_disk_bytes": shutil.disk_usage(path).free,
            "docker_available": bool(docker), "compose_available": compose_ready, "local_daemon_ready": daemon_ready,
            "region_verified": False, "security_review_complete": False, "production_approved": False}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--disk-path", type=Path, default=Path.cwd())
    args = parser.parse_args()
    print(json.dumps(report(args.disk_path), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
