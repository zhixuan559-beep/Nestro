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
    configuration = None
    configuration_pattern = re.compile(
        r"^\\s*(debugRuntimeClasspath|releaseRuntimeClasspath)\\s+-"
    )
    for raw in report.splitlines():
        if len(raw) > 4_000:
            continue
        header = configuration_pattern.match(raw)
        if header:
            configuration = header.group(1)
            continue
        if configuration is None:
            continue
        match = COORDINATE.match(raw)
        if not match:
            continue
        group, name, requested, resolved = match.groups()
        version = resolved or requested
        if version in {"FAILED", "unspecified", "unspecified-version"}:
            continue
        purl = f"pkg:maven/{group}/{name}@{version}"
        component = components.setdefault(purl, {
            "type": "library",
            "bom-ref": purl,
            "group": group,
            "name": name,
            "version": version,
            "purl": purl,
            "scope": "required",
            "_configurations": set(),
            "_requested_versions": set(),
        })
        component["_configurations"].add(configuration)
        if resolved and resolved != requested:
            component["_requested_versions"].add(requested)

        if len(components) > MAX_COMPONENTS:
            raise ValueError("依赖组件超过 20,000 项，拒绝生成不完整 SBOM")

    result = []
    for purl in sorted(components):
        component = components[purl]
        configurations = sorted(component.pop("_configurations"))
        requested_versions = sorted(component.pop("_requested_versions"))
        properties = [{
            "name": "nestro:gradle:configurations",
            "value": ",".join(configurations),
        }]
        properties.extend({
            "name": "nestro:gradle:requested-version",
            "value": version,
        } for version in requested_versions)
        component["properties"] = properties
        result.append(component)
    return result

def require_configuration_coverage(components):
    covered_configurations = {
        configuration
        for component in components
        for prop in component.get("properties", [])
        if prop.get("name") == "nestro:gradle:configurations"
        for configuration in prop.get("value", "").split(",")
    }
    required_configurations = {"debugRuntimeClasspath", "releaseRuntimeClasspath"}
    if not required_configurations.issubset(covered_configurations):
        missing = sorted(required_configurations - covered_configurations)
        raise ValueError("SBOM 缺少实际依赖来源：" + ", ".join(missing))


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: generate-sbom.py <gradle-report.txt> <sbom.cdx.json>")
    source = Path(sys.argv[1])
    destination = Path(sys.argv[2])
    if not source.is_file() or source.stat().st_size > MAX_INPUT_BYTES:
        raise SystemExit("Gradle 依赖报告不存在或超过 8 MB")
    report = source.read_text(encoding="utf-8", errors="replace")
    required_configurations = ("debugRuntimeClasspath -", "releaseRuntimeClasspath -")
    missing = [name for name in required_configurations if name not in report]
    if missing:
        raise SystemExit("Gradle 依赖报告缺少配置结果：" + ", ".join(missing))
    components = parse_components(report)
    if not components:
        raise SystemExit("未解析到已解析的 Maven 依赖，拒绝输出空 SBOM")
    covered_configurations = {
        configuration
        for component in components
        for prop in component.get("properties", [])
        if prop.get("name") == "nestro:gradle:configurations"
        for configuration in prop.get("value", "").split(",")
    }
    required_configurations = {"debugRuntimeClasspath", "releaseRuntimeClasspath"}
    if not required_configurations.issubset(covered_configurations):
        missing = sorted(required_configurations - covered_configurations)
        raise SystemExit("SBOM 缺少实际依赖来源：" + ", ".join(missing))
    build_file = Path("app/build.gradle.kts")
    if not build_file.is_file() or build_file.stat().st_size > 1_000_000:
        raise SystemExit("无法读取受限大小内的 app/build.gradle.kts 版本信息")
    build_text = build_file.read_text(encoding="utf-8", errors="replace")
    version_match = re.search(r"""\bversionName\s*=\s*["']([^"']+)["']""", build_text)
    if not version_match:
        raise SystemExit("无法从 app/build.gradle.kts 解析 versionName")
    app_version = version_match.group(1)
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
                "version": app_version,
                "bom-ref": "app:nestro",
            },
            "properties": [
                {
                    "name": "nestro:sbom:source",
                    "value": "Gradle debugRuntimeClasspath and releaseRuntimeClasspath dependency reports",
                },
                {
                    "name": "nestro:sbom:configuration:debugRuntimeClasspath",
                    "value": "resolved",
                },
                {
                    "name": "nestro:sbom:configuration:releaseRuntimeClasspath",
                    "value": "resolved",
                },
            ],
        },
        "components": components,
    }
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if destination.stat().st_size > 240_000:
        destination.unlink(missing_ok=True)
        raise SystemExit("CycloneDX SBOM 超过 240 KB 安全读取上限，拒绝生成不完整验收证据")
    print(f"Generated CycloneDX 1.5 SBOM with {len(components)} components: {destination}")


if __name__ == "__main__":
    main()
