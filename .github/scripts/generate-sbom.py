#!/usr/bin/env python3
"""Create a bounded CycloneDX 1.5 SBOM from Gradle dependency report output."""

import json
import re
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

COORDINATE = re.compile(
    r"^\s*(?:\+---|\\---|---)\s+"
    r"([^\s:]+):([^\s:]+):([^\s]+)"
    r"(?:\s+->\s+([^\s]+))?"
)
MAX_INPUT_BYTES = 8 * 1024 * 1024
MAX_COMPONENTS = 20_000


def parse_components(report: str):
    components = {}
    for raw in report.splitlines():
        if len(raw) > 4_000:
            continue
        match = COORDINATE.match(raw)
        if not match:
            continue
        group, name, requested, resolved = match.groups()
        version = resolved or requested
        if version in {"FAILED", "unspecified", "unspecified-version"}:
            continue
        purl = f"pkg:maven/{group}/{name}@{version}"
        components[purl] = {
            "type": "library",
            "bom-ref": purl,
            "group": group,
            "name": name,
            "version": version,
            "purl": purl,
            "scope": "required",
            "properties": [
                {"name": "nestro:gradle:requested-version", "value": requested}
            ] if resolved and resolved != requested else [],
        }
        if len(components) > MAX_COMPONENTS:
            raise ValueError("依赖组件超过 20,000 项，拒绝生成不完整 SBOM")
    return [components[key] for key in sorted(components)]


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: generate-sbom.py <gradle-report.txt> <sbom.cdx.json>")
    source = Path(sys.argv[1])
    destination = Path(sys.argv[2])
    if not source.is_file() or source.stat().st_size > MAX_INPUT_BYTES:
        raise SystemExit("Gradle 依赖报告不存在或超过 8 MB")
    components = parse_components(source.read_text(encoding="utf-8", errors="replace"))
    if not components:
        raise SystemExit("未解析到已解析的 Maven 依赖，拒绝输出空 SBOM")
    document = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": "urn:uuid:" + str(uuid.uuid4()),
        "version": 1,
        "metadata": {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "tools": {"components": [{
                "type": "application",
                "name": "Nestro Gradle SBOM exporter",
                "version": "1.0.0",
            }]},
            "component": {
                "type": "application",
                "name": "Nestro",
                "version": "0.3.1",
                "bom-ref": "app:nestro",
            },
            "properties": [{
                "name": "nestro:sbom:source",
                "value": "Gradle debugRuntimeClasspath and releaseRuntimeClasspath dependency reports",
            }],
        },
        "components": components,
    }
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated CycloneDX 1.5 SBOM with {len(components)} components: {destination}")


if __name__ == "__main__":
    main()
