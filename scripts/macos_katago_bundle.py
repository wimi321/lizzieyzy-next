#!/usr/bin/env python3
"""Build and audit a self-contained macOS KataGo executable bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable


SYSTEM_DEPENDENCY_PREFIXES = (
    "/System/Library/",
    "/usr/lib/",
)


class BundleError(RuntimeError):
    pass


@dataclass
class DependencyEdge:
    original: str
    source: Path
    bundled_name: str


@dataclass
class BinaryNode:
    source: Path
    target: Path
    executable: bool
    install_id: str | None = None
    edges: list[DependencyEdge] = field(default_factory=list)


def run(
    command: list[str],
    *,
    timeout: int = 60,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            command,
            check=check,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as error:
        raise BundleError(f"Command timed out: {' '.join(command)}") from error
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout or "").strip()
        raise BundleError(
            f"Command failed ({error.returncode}): {' '.join(command)}"
            + (f"\n{detail}" if detail else "")
        ) from error


def require_macos_tools() -> None:
    if platform.system() != "Darwin":
        raise BundleError("macOS KataGo bundling requires a macOS host")
    missing = [
        command
        for command in ("otool", "install_name_tool", "codesign")
        if shutil.which(command) is None
    ]
    if missing:
        raise BundleError(f"Missing macOS build tools: {', '.join(missing)}")


def is_system_dependency(reference: str) -> bool:
    return reference.startswith(SYSTEM_DEPENDENCY_PREFIXES)


def parse_otool_dependencies(output: str) -> list[str]:
    dependencies: list[str] = []
    for line in output.splitlines()[1:]:
        match = re.match(r"\s*(.+?)\s+\(compatibility version ", line)
        if match:
            dependencies.append(match.group(1))
    return dependencies


def dependencies(path: Path) -> list[str]:
    return parse_otool_dependencies(run(["otool", "-L", str(path)]).stdout)


def install_id(path: Path) -> str | None:
    result = run(["otool", "-D", str(path)], check=False)
    lines = [line.strip() for line in result.stdout.splitlines()[1:] if line.strip()]
    return lines[0] if lines else None


def rpaths(path: Path) -> list[str]:
    lines = run(["otool", "-l", str(path)]).stdout.splitlines()
    values: list[str] = []
    in_rpath = False
    for line in lines:
        stripped = line.strip()
        if stripped == "cmd LC_RPATH":
            in_rpath = True
            continue
        if in_rpath and stripped.startswith("path "):
            values.append(stripped.split(" ", 2)[1])
            in_rpath = False
        elif stripped.startswith("cmd ") and stripped != "cmd LC_RPATH":
            in_rpath = False
    return values


def expand_special_path(
    value: str,
    *,
    loader_dir: Path,
    executable_dir: Path,
) -> Path | None:
    if value.startswith("@loader_path/"):
        return loader_dir / value.removeprefix("@loader_path/")
    if value == "@loader_path":
        return loader_dir
    if value.startswith("@executable_path/"):
        return executable_dir / value.removeprefix("@executable_path/")
    if value == "@executable_path":
        return executable_dir
    if value.startswith("/"):
        return Path(value)
    return None


def resolve_dependency(
    reference: str,
    *,
    source: Path,
    executable_source: Path,
) -> Path:
    loader_dir = source.parent
    executable_dir = executable_source.parent
    expanded = expand_special_path(
        reference,
        loader_dir=loader_dir,
        executable_dir=executable_dir,
    )
    if expanded is not None:
        return expanded.resolve()

    if reference.startswith("@rpath/"):
        suffix = reference.removeprefix("@rpath/")
        search_paths = rpaths(source)
        if source != executable_source:
            search_paths.extend(rpaths(executable_source))
        for search_path in search_paths:
            base = expand_special_path(
                search_path,
                loader_dir=loader_dir,
                executable_dir=executable_dir,
            )
            if base is None:
                continue
            candidate = (base / suffix).resolve()
            if candidate.exists():
                return candidate
        raise BundleError(
            f"Unable to resolve {reference} referenced by {source}; "
            f"LC_RPATH values: {search_paths}"
        )

    raise BundleError(f"Unsupported dependency reference {reference} in {source}")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def portable_reference(node: BinaryNode, bundled_name: str) -> str:
    if node.executable:
        return f"@executable_path/lib/{bundled_name}"
    return f"@loader_path/{bundled_name}"


def sign_ad_hoc(path: Path) -> None:
    run(["codesign", "--force", "--sign", "-", str(path)])


def rewrite_binary(node: BinaryNode) -> None:
    # Do not remove the existing signature first. Xcode 16's install_name_tool
    # rejects some current Homebrew abseil binaries after --remove-signature
    # because their __LINKEDIT layout no longer fills the segment. Rewriting
    # invalidates the old signature safely; the bundle is force-signed below.
    for edge in node.edges:
        run(
            [
                "install_name_tool",
                "-change",
                edge.original,
                portable_reference(node, edge.bundled_name),
                str(node.target),
            ]
        )
    if not node.executable:
        run(
            [
                "install_name_tool",
                "-id",
                f"@loader_path/{node.target.name}",
                str(node.target),
            ]
        )


def build_bundle(
    katago_source: Path,
    output: Path,
    expected_version: str | None,
) -> None:
    require_macos_tools()
    katago_source = katago_source.expanduser().resolve()
    if not katago_source.is_file() or not os.access(katago_source, os.X_OK):
        raise BundleError(f"KataGo executable not found: {katago_source}")

    if output.exists():
        shutil.rmtree(output)
    library_dir = output / "lib"
    library_dir.mkdir(parents=True)

    executable_target = output / "katago"
    shutil.copy2(katago_source, executable_target)
    executable_target.chmod(executable_target.stat().st_mode | 0o111)

    nodes: dict[Path, BinaryNode] = {
        katago_source: BinaryNode(katago_source, executable_target, True)
    }
    bundled_names: dict[str, Path] = {}
    source_names: dict[Path, str] = {}
    pending = [katago_source]

    while pending:
        source = pending.pop(0)
        node = nodes[source]
        node.install_id = install_id(source)
        for reference in dependencies(source):
            if node.install_id and reference == node.install_id:
                continue
            if is_system_dependency(reference):
                continue
            resolved = resolve_dependency(
                reference,
                source=source,
                executable_source=katago_source,
            )
            if not resolved.is_file():
                raise BundleError(
                    f"Missing dependency {reference} referenced by {source}: {resolved}"
                )
            if resolved == source:
                continue

            bundled_name = Path(reference).name or resolved.name
            existing_source = bundled_names.get(bundled_name)
            if existing_source and existing_source != resolved:
                if sha256(existing_source) != sha256(resolved):
                    raise BundleError(
                        f"Dependency basename collision for {bundled_name}: "
                        f"{existing_source} and {resolved}"
                    )
                resolved = existing_source
            else:
                bundled_names[bundled_name] = resolved

            bundled_name = source_names.setdefault(resolved, bundled_name)
            node.edges.append(DependencyEdge(reference, resolved, bundled_name))
            if resolved in nodes:
                continue

            target = library_dir / bundled_name
            shutil.copy2(resolved, target)
            target.chmod(target.stat().st_mode | 0o111)
            nodes[resolved] = BinaryNode(resolved, target, False)
            pending.append(resolved)

    for node in nodes.values():
        rewrite_binary(node)

    for node in nodes.values():
        if not node.executable:
            sign_ad_hoc(node.target)
    sign_ad_hoc(executable_target)

    manifest = {
        "schemaVersion": 1,
        "katago": katago_source.name,
        "expectedVersion": expected_version or "",
        "libraries": sorted(node.target.name for node in nodes.values() if not node.executable),
    }
    (output / "bundle-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=True, indent=2) + "\n",
        encoding="utf-8",
    )
    audit_bundle(output, expected_version)


def resolve_bundled_reference(
    reference: str,
    *,
    binary: Path,
    executable: Path,
) -> Path:
    expanded = expand_special_path(
        reference,
        loader_dir=binary.parent,
        executable_dir=executable.parent,
    )
    if expanded is None:
        raise BundleError(
            f"Non-portable dependency reference {reference} remains in {binary}"
        )
    return expanded.resolve()


def files_to_audit(bundle: Path) -> Iterable[Path]:
    yield bundle / "katago"
    library_dir = bundle / "lib"
    if library_dir.is_dir():
        yield from sorted(library_dir.glob("*.dylib"))


def normalize_expected_version(expected_version: str | None) -> str | None:
    if not expected_version:
        return None
    return expected_version.removeprefix("v")


def load_bundle_manifest(bundle: Path) -> dict[str, object] | None:
    manifest_path = bundle / "bundle-manifest.json"
    if not manifest_path.is_file():
        return None
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BundleError(f"Invalid KataGo bundle manifest: {manifest_path}") from error
    if manifest.get("schemaVersion") != 1:
        raise BundleError(f"Unsupported KataGo bundle manifest: {manifest_path}")
    return manifest


def audit_bundle(bundle: Path, expected_version: str | None) -> None:
    require_macos_tools()
    bundle = bundle.expanduser().resolve()
    executable = bundle / "katago"
    if not executable.is_file() or not os.access(executable, os.X_OK):
        raise BundleError(f"Bundled KataGo executable is missing: {executable}")

    audited_files = list(files_to_audit(bundle))
    manifest = load_bundle_manifest(bundle)
    if manifest is not None:
        expected_libraries = manifest.get("libraries")
        if not isinstance(expected_libraries, list) or not all(
            isinstance(value, str) for value in expected_libraries
        ):
            raise BundleError("KataGo bundle manifest has an invalid library list")
        actual_libraries = sorted(path.name for path in audited_files if path != executable)
        if sorted(expected_libraries) != actual_libraries:
            raise BundleError(
                "KataGo bundle libraries do not match bundle-manifest.json"
            )
        if not expected_version:
            manifest_version = manifest.get("expectedVersion")
            if isinstance(manifest_version, str) and manifest_version.strip():
                expected_version = manifest_version.strip()

    bundle_root = bundle.resolve()
    for binary in audited_files:
        binary_id = install_id(binary)
        for reference in dependencies(binary):
            if binary_id and reference == binary_id:
                continue
            if is_system_dependency(reference):
                continue
            if reference.startswith("/") or reference.startswith("@rpath"):
                raise BundleError(
                    f"Non-portable dependency reference {reference} remains in {binary}"
                )
            target = resolve_bundled_reference(
                reference,
                binary=binary,
                executable=executable,
            )
            if not target.is_file():
                raise BundleError(
                    f"Bundled dependency {reference} referenced by {binary} is missing"
                )
            try:
                target.relative_to(bundle_root)
            except ValueError as error:
                raise BundleError(
                    f"Dependency escapes the KataGo bundle: {reference} -> {target}"
                ) from error
        run(["codesign", "--verify", "--strict", str(binary)])

    # A freshly copied or downloaded binary can spend extra time in macOS
    # provenance and code-signing checks on its first launch.
    version_result = run([str(executable), "version"], timeout=90)
    version_output = "\n".join(
        value.strip()
        for value in (version_result.stdout, version_result.stderr)
        if value.strip()
    )
    normalized_version = normalize_expected_version(expected_version)
    if normalized_version and f"KataGo v{normalized_version}" not in version_output:
        raise BundleError(
            f"Expected KataGo v{normalized_version}, got:\n{version_output}"
        )

    print(
        f"Validated self-contained macOS KataGo bundle: "
        f"{len(audited_files) - 1} dylibs"
    )
    if version_output:
        print(version_output)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    bundle_parser = subparsers.add_parser(
        "bundle",
        help="Copy KataGo and recursively bundle all non-system dylibs",
    )
    bundle_parser.add_argument("--katago", type=Path, required=True)
    bundle_parser.add_argument("--output", type=Path, required=True)
    bundle_parser.add_argument("--expected-version")

    audit_parser = subparsers.add_parser(
        "audit",
        help="Reject external dylib references and run bundled KataGo",
    )
    audit_parser.add_argument("--bundle", type=Path, required=True)
    audit_parser.add_argument("--expected-version")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.command == "bundle":
            build_bundle(args.katago, args.output, args.expected_version)
        else:
            audit_bundle(args.bundle, args.expected_version)
    except BundleError as error:
        print(f"macOS KataGo bundle error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
