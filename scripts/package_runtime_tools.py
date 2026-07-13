#!/usr/bin/env python3
"""Release packaging helpers for runtime slimming, Base CDS, and size audits."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path


MIB = 1024 * 1024

SAFE_DESKTOP_MODULES = {
    "java.base",
    "java.desktop",
    "java.naming",
    "java.net.http",
    "java.prefs",
    "java.sql",
    "jdk.accessibility",
    "jdk.charsets",
    "jdk.crypto.ec",
    "jdk.jfr",
    "jdk.localedata",
    "jdk.management",
    "jdk.unsupported",
    "jdk.zipfs",
}

WARNING_BUDGETS = {
    "shaded jar": 75 * MIB,
}
RELEASE_ASSET_WARNING_BUDGET = 1900 * MIB
COMPARE_GROWTH_WARNING_BUDGET = 25 * MIB


def log(message: str) -> None:
    print(f"[package-runtime] {message}", file=sys.stderr)


def run(
    command: list[str],
    *,
    cwd: Path | None = None,
    check: bool = True,
    timeout: float | None = None,
) -> subprocess.CompletedProcess[str]:
    log("+ " + " ".join(command))
    return subprocess.run(
        command,
        cwd=str(cwd) if cwd else None,
        check=check,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
    )


def resolve_tool(name: str) -> str | None:
    path = shutil.which(name)
    if path:
        return path
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / name
        if candidate.exists():
            return str(candidate)
        if os.name == "nt":
            candidate_exe = Path(java_home) / "bin" / f"{name}.exe"
            if candidate_exe.exists():
                return str(candidate_exe)
    return None


def infer_java_home_from_tool(tool_path: str) -> Path | None:
    path = Path(tool_path).resolve()
    if path.parent.name == "bin":
        return path.parent.parent
    return None


def host_can_jlink_platform(platform_name: str) -> bool:
    normalized = platform_name.lower()
    host = platform.system().lower()
    if normalized.startswith("linux"):
        return host == "linux"
    if normalized.startswith("windows") or normalized.startswith("win"):
        return host == "windows"
    if normalized.startswith("mac") or normalized.startswith("darwin"):
        return host == "darwin"
    return True


def read_jdeps_modules(jar_path: Path) -> set[str]:
    jdeps = resolve_tool("jdeps")
    if not jdeps or not jar_path.exists():
        return set()
    try:
        result = run(
            [
                jdeps,
                "--multi-release",
                "17",
                "--ignore-missing-deps",
                "--print-module-deps",
                str(jar_path),
            ],
            check=True,
        )
    except subprocess.CalledProcessError as exc:
        log("jdeps module inference failed; continuing with safe desktop module set.")
        if exc.stderr:
            log(exc.stderr.strip())
        return set()
    modules = set()
    for raw in result.stdout.replace("\n", ",").split(","):
        value = raw.strip()
        if value:
            modules.add(value)
    return modules


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def copy_runtime(source: Path, output: Path) -> None:
    if output.exists():
        shutil.rmtree(output)
    shutil.copytree(source, output)


def create_base_cds(runtime: Path) -> dict:
    java_name = "java.exe" if os.name == "nt" else "java"
    java = runtime / "bin" / java_name
    if not java.exists():
        return {"created": False, "reason": f"java not found in runtime: {runtime}"}
    try:
        result = run([str(java), "-Xshare:dump"], check=True)
    except OSError as exc:
        return {"created": False, "reason": f"base CDS dump could not execute runtime java: {exc}"}
    except subprocess.CalledProcessError as exc:
        reason = "base CDS dump failed"
        if exc.stderr:
            reason += ": " + exc.stderr.strip().splitlines()[-1]
        return {"created": False, "reason": reason}
    archives = sorted(runtime.rglob("classes.jsa"))
    payload = {
        "created": bool(archives),
        "archives": [str(path.relative_to(runtime)) for path in archives],
    }
    if result.stdout.strip():
        payload["stdout"] = result.stdout.strip()
    return payload


def optimize_runtime(args: argparse.Namespace) -> int:
    output = Path(args.output).resolve()
    manifest = Path(args.manifest).resolve() if args.manifest else output.with_suffix(".manifest.json")
    if not host_can_jlink_platform(args.platform):
        return runtime_fallback(
            args,
            output,
            manifest,
            f"host {platform.system()} cannot jlink target platform {args.platform}",
        )

    jlink = resolve_tool("jlink")
    if not jlink:
        return runtime_fallback(args, output, manifest, "jlink not found")

    java_home = Path(args.java_home).resolve() if args.java_home else infer_java_home_from_tool(jlink)
    if not java_home:
        return runtime_fallback(args, output, manifest, "unable to infer JAVA_HOME for jlink")
    jmods = java_home / "jmods"
    if not jmods.is_dir():
        return runtime_fallback(args, output, manifest, f"jmods not found: {jmods}")

    jar_path = Path(args.jar).resolve()
    modules = set(SAFE_DESKTOP_MODULES)
    modules.update(read_jdeps_modules(jar_path))
    for value in args.add_modules:
        modules.update(item.strip() for item in value.split(",") if item.strip())
    module_arg = ",".join(sorted(modules))

    tmp_parent = output.parent
    tmp_parent.mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp(prefix=output.name + "-", dir=tmp_parent))
    shutil.rmtree(tmp)
    command = [
        jlink,
        "--module-path",
        str(jmods),
        "--add-modules",
        module_arg,
        "--strip-debug",
        "--compress=2",
        "--no-header-files",
        "--no-man-pages",
        "--output",
        str(tmp),
    ]
    if args.bind_services:
        command.insert(-2, "--bind-services")

    started = time.time()
    try:
        result = run(command, check=True)
    except subprocess.CalledProcessError as exc:
        if tmp.exists():
            shutil.rmtree(tmp, ignore_errors=True)
        reason = "jlink failed"
        if exc.stderr:
            reason += ": " + exc.stderr.strip().splitlines()[-1]
        return runtime_fallback(args, output, manifest, reason)

    if output.exists():
        shutil.rmtree(output)
    shutil.move(str(tmp), str(output))
    base_cds = create_base_cds(output)
    payload = {
        "schemaVersion": 1,
        "optimized": True,
        "fallback": False,
        "platform": args.platform,
        "javaHome": str(java_home),
        "jar": str(jar_path),
        "modules": sorted(modules),
        "sizeBytes": directory_size(output),
        "durationSeconds": round(time.time() - started, 3),
        "baseCds": base_cds,
    }
    if result.stdout.strip():
        payload["stdout"] = result.stdout.strip()
    write_json(manifest, payload)
    print(output)
    return 0


def runtime_fallback(args: argparse.Namespace, output: Path, manifest: Path, reason: str) -> int:
    source = Path(args.fallback_source).resolve() if args.fallback_source else None
    payload = {
        "schemaVersion": 1,
        "optimized": False,
        "fallback": bool(source and source.is_dir()),
        "platform": args.platform,
        "reason": reason,
    }
    if source and source.is_dir():
        log(f"Falling back to existing runtime: {source} ({reason})")
        copy_runtime(source, output)
        payload["sourceRuntime"] = str(source)
        payload["sizeBytes"] = directory_size(output)
        write_json(manifest, payload)
        print(output)
        return 0
    log(f"Runtime optimization unavailable: {reason}")
    if output.exists():
        shutil.rmtree(output, ignore_errors=True)
    write_json(manifest, payload)
    return 0 if args.optional else 1


def directory_size(path: Path) -> int:
    if path.is_file():
        return path.stat().st_size
    total = 0
    if not path.exists():
        return total
    for item in path.rglob("*"):
        if item.is_file() and not item.is_symlink():
            total += item.stat().st_size
    return total


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def human_size(size: int) -> str:
    sign = "-" if size < 0 else ""
    value = float(abs(size))
    for unit in ("B", "KiB", "MiB", "GiB"):
        if value < 1024 or unit == "GiB":
            return f"{sign}{value:.1f} {unit}" if unit != "B" else f"{sign}{int(value)} B"
        value /= 1024
    return f"{size} B"


def collect_audit_entry(label: str, path: Path) -> dict | None:
    if not path.exists():
        return None
    payload = {
        "label": label,
        "path": str(path),
        "sizeBytes": directory_size(path),
        "humanSize": human_size(directory_size(path)),
        "kind": "directory" if path.is_dir() else "file",
    }
    if path.is_file():
        payload["sha256"] = sha256(path)
    return payload


def warning_budget_for(entry: dict) -> int | None:
    label = str(entry.get("label", ""))
    if label.startswith("release asset: "):
        return RELEASE_ASSET_WARNING_BUDGET
    if label.startswith("custom runtime"):
        return 140 * MIB
    return WARNING_BUDGETS.get(label)


def collect_size_warnings(entries: list[dict]) -> list[dict]:
    warnings = []
    for entry in entries:
        budget = warning_budget_for(entry)
        if not budget:
            continue
        size = int(entry.get("sizeBytes", 0))
        if size > budget:
            warnings.append(
                {
                    "label": entry["label"],
                    "sizeBytes": size,
                    "humanSize": human_size(size),
                    "budgetBytes": budget,
                    "humanBudget": human_size(budget),
                    "message": (
                        f"{entry['label']} is {human_size(size)}, "
                        f"above warning budget {human_size(budget)}"
                    ),
                }
            )
    return warnings


def emit_github_warnings(warnings: list[dict]) -> None:
    for warning in warnings:
        print(f"::warning title=LizzieYzy package size::{warning['message']}")


def audit_sizes(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    output = Path(args.output).resolve()
    json_output = Path(args.json_output).resolve() if args.json_output else output.with_suffix(".json")
    release_prefix = args.release_prefix.strip()
    paths: list[tuple[str, Path]] = [
        ("shaded jar", root / "target" / "lizzie-yzy2.5.3-shaded.jar"),
        ("plain jar", root / "target" / "lizzie-yzy2.5.3.jar"),
        ("source resources", root / "src" / "main" / "resources"),
        ("strength models resource", root / "src" / "main" / "resources" / "models" / "strength"),
        ("ui image resources", root / "src" / "main" / "resources" / "assets" / "ui"),
        ("weights", root / "weights"),
        ("engines", root / "engines"),
        ("runtime", root / "runtime"),
        ("jcef bundle", root / "jcef-bundle"),
        ("dist release", root / "dist" / "release"),
        ("dist windows", root / "dist" / "windows"),
        ("dist macos", root / "dist" / "macos"),
        ("dist stage", root / "dist" / "stage"),
    ]
    for custom_runtime in args.custom_runtime:
        custom_runtime_path = Path(custom_runtime).resolve()
        paths.append((f"custom runtime: {custom_runtime_path.name}", custom_runtime_path))
    entries = [entry for label, path in paths if (entry := collect_audit_entry(label, path))]
    release_dir = root / "dist" / "release"
    if release_dir.is_dir():
        for path in sorted(release_dir.iterdir()):
            if path.is_file():
                if release_prefix and not path.name.startswith(release_prefix):
                    continue
                entry = collect_audit_entry(f"release asset: {path.name}", path)
                if entry:
                    entries.append(entry)

    warnings = collect_size_warnings(entries)
    payload = {
        "schemaVersion": 1,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "root": str(root),
        "platform": platform.platform(),
        "releasePrefix": release_prefix,
        "warnings": warnings,
        "entries": entries,
    }
    write_json(json_output, payload)
    output.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# LizzieYzy Next package size audit",
        "",
        f"- Generated: {payload['generatedAt']}",
        f"- Root: `{root}`",
    ]
    if release_prefix:
        lines.append(f"- Release asset prefix: `{release_prefix}`")
    lines.extend(
        [
            "",
            "| Component | Kind | Size | Path |",
            "| --- | --- | ---: | --- |",
        ]
    )
    for entry in entries:
        lines.append(
            f"| {entry['label']} | {entry['kind']} | {entry['humanSize']} | `{entry['path']}` |"
        )
    lines.extend(["", "## Warning Gate", ""])
    if warnings:
        lines.extend(
            [
                "| Component | Size | Warning budget |",
                "| --- | ---: | ---: |",
            ]
        )
        for warning in warnings:
            lines.append(
                f"| {warning['label']} | {warning['humanSize']} | {warning['humanBudget']} |"
            )
    else:
        lines.append("No package size warnings.")
    lines.append("")
    output.write_text("\n".join(lines), encoding="utf-8")
    emit_github_warnings(warnings)
    print(output)
    return 0


def load_audit_entries(path: Path) -> dict[str, dict]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return {entry["label"]: entry for entry in payload.get("entries", [])}


def compare_audits(args: argparse.Namespace) -> int:
    before_path = Path(args.before).resolve()
    after_path = Path(args.after).resolve()
    output = Path(args.output).resolve()
    before_entries = load_audit_entries(before_path)
    after_entries = load_audit_entries(after_path)
    labels = sorted(set(before_entries) | set(after_entries))
    rows = []
    warnings = []
    for label in labels:
        before_size = int(before_entries.get(label, {}).get("sizeBytes", 0))
        after_size = int(after_entries.get(label, {}).get("sizeBytes", 0))
        delta = after_size - before_size
        row = {
            "label": label,
            "beforeBytes": before_size,
            "afterBytes": after_size,
            "deltaBytes": delta,
            "before": human_size(before_size),
            "after": human_size(after_size),
            "delta": ("+" if delta > 0 else "") + human_size(delta) if delta else "0 B",
        }
        rows.append(row)
        if delta > COMPARE_GROWTH_WARNING_BUDGET:
            warnings.append(
                {
                    "label": label,
                    "deltaBytes": delta,
                    "humanDelta": human_size(delta),
                    "message": f"{label} grew by {human_size(delta)}",
                }
            )

    lines = [
        "# LizzieYzy Next package size comparison",
        "",
        f"- Before: `{before_path}`",
        f"- After: `{after_path}`",
        "",
        "| Component | Before | After | Delta |",
        "| --- | ---: | ---: | ---: |",
    ]
    for row in rows:
        lines.append(f"| {row['label']} | {row['before']} | {row['after']} | {row['delta']} |")
    lines.extend(["", "## Growth Warnings", ""])
    if warnings:
        for warning in warnings:
            lines.append(f"- {warning['message']}")
        emit_github_warnings(warnings)
    else:
        lines.append("No component grew by more than 25 MiB.")
    lines.append("")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines), encoding="utf-8")
    print(output)
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    runtime = subparsers.add_parser("optimize-runtime")
    runtime.add_argument("--jar", required=True)
    runtime.add_argument("--output", required=True)
    runtime.add_argument("--platform", default="")
    runtime.add_argument("--java-home", default="")
    runtime.add_argument("--manifest", default="")
    runtime.add_argument("--fallback-source", default="")
    runtime.add_argument("--add-modules", action="append", default=[])
    runtime.add_argument("--bind-services", action="store_true")
    runtime.add_argument("--optional", action="store_true")
    runtime.set_defaults(func=optimize_runtime)

    audit = subparsers.add_parser("audit-sizes")
    audit.add_argument("--root", default=".")
    audit.add_argument("--output", default="dist/release-meta/package-size-audit.md")
    audit.add_argument("--json-output", default="")
    audit.add_argument("--release-prefix", default="")
    audit.add_argument("--custom-runtime", action="append", default=[])
    audit.set_defaults(func=audit_sizes)

    compare = subparsers.add_parser("compare-audits")
    compare.add_argument("--before", required=True)
    compare.add_argument("--after", required=True)
    compare.add_argument("--output", default="dist/release-meta/package-size-comparison.md")
    compare.set_defaults(func=compare_audits)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
