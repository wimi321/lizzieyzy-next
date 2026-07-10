#!/usr/bin/env python3
"""Create a compact, machine-readable summary from a LizzieYzy JFR recording."""

from __future__ import annotations

import argparse
import collections
import json
import re
import shutil
import subprocess
from pathlib import Path


def run_jfr(*args: str) -> str:
    tool = shutil.which("jfr")
    if not tool:
        raise SystemExit("jfr tool is not available")
    result = subprocess.run(
        [tool, *args], check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    return result.stdout


def event_count(summary: str, event_name: str) -> int:
    match = re.search(r"^\s*" + re.escape(event_name) + r"\s+(\d+)", summary, re.MULTILINE)
    return int(match.group(1)) if match else 0


def method_name(frame: dict) -> str:
    method = frame.get("method") or {}
    class_name = ((method.get("type") or {}).get("name") or "").replace("/", ".")
    name = method.get("name") or ""
    return f"{class_name}.{name}" if class_name else name


def allocation_summary(jfr_path: Path) -> dict:
    raw = run_jfr("print", "--json", "--events", "jdk.ObjectAllocationSample", str(jfr_path))
    events = json.loads(raw)["recording"]["events"]
    app_methods: collections.Counter[str] = collections.Counter()
    object_classes: collections.Counter[str] = collections.Counter()
    sampled_weight = 0
    for event in events:
        values = event.get("values") or {}
        weight = int(values.get("weight") or 0)
        sampled_weight += weight
        object_class = ((values.get("objectClass") or {}).get("name") or "unknown").replace(
            "/", "."
        )
        object_classes[object_class] += weight
        frames = ((values.get("stackTrace") or {}).get("frames") or [])
        for frame in frames:
            name = method_name(frame)
            if name.startswith("featurecat.lizzie."):
                app_methods[name] += weight
                break
    return {
        "sampleCount": len(events),
        "sampledWeightBytes": sampled_weight,
        "topApplicationAllocationSites": [
            {"method": name, "sampledWeightBytes": weight}
            for name, weight in app_methods.most_common(20)
        ],
        "topAllocatedClasses": [
            {"class": name, "sampledWeightBytes": weight}
            for name, weight in object_classes.most_common(20)
        ],
    }


def human_bytes(value: int) -> str:
    amount = float(value)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if amount < 1024.0 or unit == "GiB":
            return f"{amount:.1f} {unit}"
        amount /= 1024.0
    return f"{value} B"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jfr", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--json-output", default="")
    args = parser.parse_args()

    jfr_path = Path(args.jfr).resolve()
    output = Path(args.output).resolve()
    json_output = (
        Path(args.json_output).resolve() if args.json_output else output.with_suffix(".json")
    )
    summary_text = run_jfr("summary", str(jfr_path))
    allocations = allocation_summary(jfr_path)
    payload = {
        "schemaVersion": 1,
        "recording": str(jfr_path),
        "recordingSizeBytes": jfr_path.stat().st_size,
        "youngGcCount": event_count(summary_text, "jdk.YoungGarbageCollection"),
        "oldGcCount": event_count(summary_text, "jdk.OldGarbageCollection"),
        "errorThrowCount": event_count(summary_text, "jdk.JavaErrorThrow"),
        "allocations": allocations,
    }
    json_output.parent.mkdir(parents=True, exist_ok=True)
    json_output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    lines = [
        "# LizzieYzy Next JFR summary",
        "",
        f"- Recording: `{jfr_path}`",
        f"- Sampled allocation weight: {human_bytes(allocations['sampledWeightBytes'])}",
        f"- Young GC: {payload['youngGcCount']}",
        f"- Old GC: {payload['oldGcCount']}",
        "",
        "## Application allocation sites",
        "",
        "| Method | Sampled weight |",
        "| --- | ---: |",
    ]
    for entry in allocations["topApplicationAllocationSites"]:
        lines.append(f"| `{entry['method']}` | {human_bytes(entry['sampledWeightBytes'])} |")
    lines.append("")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines), encoding="utf-8")
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
