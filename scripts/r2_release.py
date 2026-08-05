#!/usr/bin/env python3
"""Mirror one stable LizzieYzy Next release to R2 and publish signed update metadata."""

from __future__ import annotations

import argparse
import base64
import dataclasses
import hashlib
import html
import json
import os
import re
import sys
import time
import urllib.parse
from pathlib import Path
from typing import Any, Iterable


R2_SIZE_LIMIT = 9_000_000_000
DEFAULT_REPOSITORY = "wimi321/lizzieyzy-next"
DEFAULT_BUCKET = "lizzieyzy-next-downloads"
DEFAULT_PUBLIC_BASE = "https://download.goagent.top"
DEFAULT_KEY_ID = "stable-2026-08"
UPDATE_ENVELOPE_ASSET = "lizzieyzy-next-update-envelope.json"
LEGACY_MANIFEST_ASSET = "lizzieyzy-next-update-manifest.json"
CATALOG_ASSET = "lizzieyzy-next-download-catalog.json"
RELEASE_NOTE_START = "<!-- lizzie-r2-stable-downloads:start -->"
RELEASE_NOTE_END = "<!-- lizzie-r2-stable-downloads:end -->"
MULTIPART_PART_SIZE = 64 * 1024 * 1024
SMALL_OBJECT_LIMIT = 16 * 1024 * 1024
PUBLIC_VERIFY_ATTEMPTS = 5
PUBLIC_VERIFY_BACKOFF_SECONDS = (2, 4, 8, 16)
MAX_PUBLIC_METADATA_BYTES = 2 * 1024 * 1024

WINDOWS_PORTABLE = re.compile(
    r"^(?P<date>\d{4}-\d{2}-\d{2})-windows64\."
    r"(?P<flavor>opencl|with-katago|nvidia|nvidia50\.cuda|without\.engine)"
    r"\.portable\.zip$"
)
WINDOWS_CORE = re.compile(
    r"^(?P<date>\d{4}-\d{2}-\d{2})-windows64\.core-update\.zip$"
)
MAC_DMG = re.compile(
    r"^(?P<date>\d{4}-\d{2}-\d{2})-mac-"
    r"(?P<chip>apple-silicon|intel)\.with-katago\.dmg$"
)
LINUX_PACKAGE = re.compile(
    r"^(?P<date>\d{4}-\d{2}-\d{2})-linux64\."
    r"(?P<flavor>opencl|nvidia|with-katago|without\.engine)\.zip$"
)
TENSORRT_ASSET = re.compile(
    r"^(?P<date>\d{4}-\d{2}-\d{2})-windows64\.nvidia\.tensorrt\.portable\."
    r"(?P<part>7z\.\d{3}|README\.txt|manifest\.json|sha256\.txt)$"
)


class ReleaseError(RuntimeError):
    pass


@dataclasses.dataclass(frozen=True)
class Asset:
    name: str
    size: int
    sha256: str
    api_url: str
    browser_url: str
    category: str
    flavor: str = ""
    arch: str = ""
    r2_key: str = ""

    @classmethod
    def from_github(cls, raw: dict[str, Any], category: str, **kwargs: str) -> "Asset":
        digest = str(raw.get("digest") or "")
        if not digest.startswith("sha256:") or len(digest) != 71:
            raise ReleaseError(f"GitHub asset has no usable SHA-256 digest: {raw.get('name')}")
        size = int(raw.get("size") or 0)
        if size <= 0:
            raise ReleaseError(f"GitHub asset has invalid size: {raw.get('name')}")
        return cls(
            name=str(raw["name"]),
            size=size,
            sha256=digest.removeprefix("sha256:").lower(),
            api_url=str(raw["url"]),
            browser_url=str(raw["browser_download_url"]),
            category=category,
            **kwargs,
        )


def release_assets(release: dict[str, Any]) -> list[dict[str, Any]]:
    assets = release.get("assets")
    if not isinstance(assets, list):
        raise ReleaseError("GitHub release response has no assets list")
    return assets


def select_r2_assets(release: dict[str, Any], public_base: str) -> list[Asset]:
    tag = str(release.get("tag_name") or "").strip()
    if not tag:
        raise ReleaseError("Release tag is missing")
    selected: list[Asset] = []
    counts = {"windows": 0, "core": 0, "mac": 0, "tensorrt": 0}
    for raw in release_assets(release):
        name = str(raw.get("name") or "")
        match = WINDOWS_PORTABLE.fullmatch(name)
        category = ""
        kwargs: dict[str, str] = {}
        if match:
            category = "windows-portable"
            kwargs = {"flavor": match.group("flavor"), "arch": "x64"}
            counts["windows"] += 1
        else:
            match = WINDOWS_CORE.fullmatch(name)
            if match:
                category = "windows-core-update"
                kwargs = {"flavor": "all", "arch": "x64"}
                counts["core"] += 1
            else:
                match = MAC_DMG.fullmatch(name)
                if match:
                    category = "macos-dmg"
                    kwargs = {
                        "flavor": "with-katago",
                        "arch": "arm64" if match.group("chip") == "apple-silicon" else "x64",
                    }
                    counts["mac"] += 1
                else:
                    match = TENSORRT_ASSET.fullmatch(name)
                    if match:
                        category = "tensorrt-advanced"
                        kwargs = {"flavor": "nvidia-tensorrt", "arch": "x64"}
                        counts["tensorrt"] += 1
        if not category:
            continue
        asset = Asset.from_github(raw, category, **kwargs)
        selected.append(dataclasses.replace(asset, r2_key=f"releases/{tag}/{name}"))

    expected = {"windows": 5, "core": 1, "mac": 2, "tensorrt": 5}
    if counts != expected:
        raise ReleaseError(f"R2 asset whitelist mismatch: expected {expected}, found {counts}")
    names = [asset.name for asset in selected]
    if len(names) != len(set(names)):
        raise ReleaseError("R2 asset whitelist contains duplicate names")
    total = sum(asset.size for asset in selected)
    if total > R2_SIZE_LIMIT:
        raise ReleaseError(
            f"R2 stable assets total {total:,} bytes, above the {R2_SIZE_LIMIT:,}-byte hard limit"
        )
    return sorted(selected, key=lambda asset: asset.name)


def select_linux_assets(release: dict[str, Any]) -> list[Asset]:
    selected: list[Asset] = []
    for raw in release_assets(release):
        match = LINUX_PACKAGE.fullmatch(str(raw.get("name") or ""))
        if match:
            selected.append(
                Asset.from_github(
                    raw,
                    "linux-package",
                    flavor=match.group("flavor"),
                    arch="x64",
                )
            )
    if not selected:
        raise ReleaseError("Release has no Linux package for GitHub fallback updates")
    return sorted(selected, key=lambda asset: asset.name)


def r2_url(public_base: str, asset: Asset) -> str:
    return f"{public_base.rstrip('/')}/{urllib.parse.quote(asset.r2_key, safe='/')}"


def package_entry(asset: Asset, public_base: str, *, mirrored: bool) -> dict[str, Any]:
    if asset.category == "windows-portable":
        platform = "windows"
        install_mode = "download-archive"
    elif asset.category == "macos-dmg":
        platform = "macos"
        install_mode = "open-dmg"
    elif asset.category == "linux-package":
        platform = "linux"
        install_mode = "download-archive"
    else:
        raise ReleaseError(f"Asset is not an application package: {asset.name}")
    primary = r2_url(public_base, asset) if mirrored else asset.browser_url
    mirrors = [asset.browser_url] if mirrored else []
    return {
        "platform": platform,
        "arch": asset.arch,
        "flavor": asset.flavor,
        "installMode": install_mode,
        "assetName": asset.name,
        "sizeBytes": asset.size,
        "sha256": asset.sha256,
        "downloadUrl": primary,
        "mirrorUrls": mirrors,
    }


def build_manifest(
    release: dict[str, Any], mirrored_assets: list[Asset], public_base: str
) -> dict[str, Any]:
    tag = str(release["tag_name"])
    core = next(asset for asset in mirrored_assets if asset.category == "windows-core-update")
    packages = [
        package_entry(asset, public_base, mirrored=True)
        for asset in mirrored_assets
        if asset.category in {"windows-portable", "macos-dmg"}
    ]
    packages.extend(
        package_entry(asset, public_base, mirrored=False) for asset in select_linux_assets(release)
    )
    return {
        "schemaVersion": 2,
        "releaseTag": tag,
        "publishedAt": str(release.get("published_at") or release.get("created_at") or ""),
        "notesUrl": str(release.get("html_url") or f"https://github.com/{DEFAULT_REPOSITORY}/releases/tag/{tag}"),
        "minUpdaterVersion": "2",
        "prerelease": False,
        "components": [
            {
                "id": "core",
                "platform": "windows",
                "flavor": "all",
                "version": tag,
                "assetName": core.name,
                "downloadUrl": r2_url(public_base, core),
                "sizeBytes": core.size,
                "sha256": core.sha256,
                "installAction": "replace-core",
                "defaultSelectedIfChanged": True,
                "mirrorUrls": [core.browser_url],
            }
        ],
        "packages": sorted(
            packages,
            key=lambda entry: (
                entry["platform"], entry["arch"], entry["flavor"], entry["assetName"]
            ),
        ),
    }


def build_legacy_manifest(manifest: dict[str, Any]) -> dict[str, Any]:
    core = dict(manifest["components"][0])
    core["downloadUrl"] = core["mirrorUrls"][0]
    core["mirrorUrls"] = []
    return {
        "schemaVersion": 1,
        "releaseTag": manifest["releaseTag"],
        "publishedAt": manifest["publishedAt"],
        "notesUrl": manifest["notesUrl"],
        "minUpdaterVersion": "1",
        "prerelease": False,
        "components": [core],
    }


def stable_release_body(
    release: dict[str, Any], mirrored_assets: list[Asset], public_base: str
) -> str:
    """Switch mirrored asset links to R2 while keeping one obvious GitHub fallback."""
    body = str(release.get("body") or "")
    marker_pattern = re.compile(
        re.escape(RELEASE_NOTE_START) + r".*?" + re.escape(RELEASE_NOTE_END) + r"\n*",
        re.DOTALL,
    )
    body = marker_pattern.sub("", body).strip()
    for asset in mirrored_assets:
        body = body.replace(asset.browser_url, r2_url(public_base, asset))

    release_url = str(release.get("html_url") or "").strip()
    fallback = (
        f"[GitHub 全量资产与备用下载 / GitHub fallback]({release_url})"
        if release_url
        else "GitHub 全量资产与备用下载 / GitHub fallback"
    )
    notice = (
        f"{RELEASE_NOTE_START}\n"
        "> [!IMPORTANT]\n"
        f"> **正式版主下载 / Stable primary downloads:** "
        f"[{public_base.rstrip('/')}/]({public_base.rstrip('/')}/)  \n"
        f"> R2 连接异常时可使用 {fallback}；Linux、安装器与历史版本仍在 GitHub。\n"
        f"{RELEASE_NOTE_END}"
    )
    if not body:
        return notice + "\n"
    first_break = body.find("\n")
    if body.startswith("# ") and first_break >= 0:
        return body[:first_break] + "\n\n" + notice + "\n\n" + body[first_break + 1 :].lstrip()
    return notice + "\n\n" + body + "\n"


def catalog_label(asset: Asset) -> tuple[str, str, bool]:
    labels = {
        "opencl": ("Windows OpenCL", "Windows OpenCL", False),
        "with-katago": ("Windows CPU / 通用版", "Windows CPU / universal", False),
        "nvidia": ("Windows NVIDIA", "Windows NVIDIA", False),
        "nvidia50.cuda": ("Windows RTX 50 CUDA", "Windows RTX 50 CUDA", False),
        "without.engine": ("Windows 无引擎版", "Windows without engine", False),
        "nvidia-tensorrt": ("高级可选 TensorRT", "Advanced optional TensorRT", True),
    }
    if asset.category == "macos-dmg":
        return (
            ("macOS Apple Silicon", "macOS Apple Silicon", False)
            if asset.arch == "arm64"
            else ("macOS Intel", "macOS Intel", False)
        )
    if asset.category == "windows-core-update":
        return ("Windows 主程序小更新", "Windows core update", False)
    return labels[asset.flavor]


def build_catalog(
    release: dict[str, Any], mirrored_assets: list[Asset], public_base: str
) -> dict[str, Any]:
    entries = []
    for asset in mirrored_assets:
        zh_label, en_label, advanced = catalog_label(asset)
        entries.append(
            {
                "name": asset.name,
                "category": asset.category,
                "flavor": asset.flavor,
                "arch": asset.arch,
                "sizeBytes": asset.size,
                "sha256": asset.sha256,
                "downloadUrl": r2_url(public_base, asset),
                "mirrorUrls": [asset.browser_url],
                "labelZh": zh_label,
                "labelEn": en_label,
                "advanced": advanced,
            }
        )
    return {
        "schemaVersion": 1,
        "releaseTag": release["tag_name"],
        "publishedAt": release.get("published_at"),
        "releaseUrl": release.get("html_url"),
        "totalSizeBytes": sum(asset.size for asset in mirrored_assets),
        "assets": entries,
    }


def format_size(size: int) -> str:
    value = float(size)
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024 or unit == "GB":
            return f"{value:.1f} {unit}" if unit != "B" else f"{int(value)} B"
        value /= 1024
    return f"{size} B"


def inline_asset_data_uri(relative_path: str, mime_type: str) -> str:
    asset_path = Path(__file__).resolve().parent.parent / relative_path
    try:
        encoded = base64.b64encode(asset_path.read_bytes()).decode("ascii")
    except OSError as exc:
        raise ReleaseError(f"Download page asset is unavailable: {asset_path}") from exc
    return f"data:{mime_type};base64,{encoded}"


def render_index(catalog: dict[str, Any], *, maintenance: bool = False) -> str:
    assets = list(catalog.get("assets") or [])
    app_icon = inline_asset_data_uri("src/main/resources/assets/logo.png", "image/png")
    page_background = inline_asset_data_uri("assets/download-page-bg.webp", "image/webp")
    icons = {
        name: inline_asset_data_uri(f"assets/download-page-icons/{name}.svg", "image/svg+xml")
        for name in (
            "windows",
            "apple",
            "gpu-card",
            "cpu",
            "speedometer",
            "gear",
            "arrow-repeat",
            "book",
            "clock-history",
        )
    }
    history_url = "https://github.com/wimi321/lizzieyzy-next/releases"
    help_url = str(catalog.get("releaseUrl") or history_url)

    def decorative_icon(name: str, class_name: str = "row-icon") -> str:
        return (
            f'<img class="{class_name}" src="{icons[name]}" alt="" '
            'aria-hidden="true" loading="eager">'
        )

    def entry_url(entry: dict[str, Any]) -> str:
        if maintenance:
            mirrors = list(entry.get("mirrorUrls") or [])
            if not mirrors:
                raise ReleaseError(f"Download entry has no maintenance mirror: {entry.get('name')}")
            return str(mirrors[0])
        return str(entry["downloadUrl"])

    def find_entry(category: str, flavor: str = "", arch: str = "") -> dict[str, Any]:
        matches = [
            entry
            for entry in assets
            if entry.get("category") == category
            and (not flavor or entry.get("flavor") == flavor)
            and (not arch or entry.get("arch") == arch)
        ]
        if len(matches) != 1:
            raise ReleaseError(
                f"Download page expected one {category}/{flavor}/{arch} entry, found {len(matches)}"
            )
        return matches[0]

    def download_action(
        entry: dict[str, Any], label: str = "下载", accessible_name: str = ""
    ) -> str:
        target_name = accessible_name or str(entry.get("labelZh") or "")
        return (
            f'<a class="download-action" href="{html.escape(entry_url(entry), quote=True)}" '
            f'aria-label="{html.escape(label + " " + target_name, quote=True)}">'
            f"{html.escape(label)}</a>"
        )

    def download_row(
        entry: dict[str, Any],
        title: str,
        description: str,
        icon: str,
        *,
        recommended: bool = False,
    ) -> str:
        badge = '<span class="recommendation">推荐</span>' if recommended else ""
        return (
            '<li class="download-row">'
            '<div class="download-main">'
            f'{decorative_icon(icon)}'
            '<div class="download-copy">'
            f'<div class="download-title">{html.escape(title)}{badge}</div>'
            f'<div class="download-description">{html.escape(description)}</div>'
            "</div>"
            "</div>"
            f'<span class="download-size">{format_size(int(entry["sizeBytes"]))}</span>'
            f'{download_action(entry, accessible_name=title)}'
            "</li>"
        )

    windows_entries = {
        flavor: find_entry("windows-portable", flavor=flavor)
        for flavor in (
            "nvidia",
            "nvidia50.cuda",
            "opencl",
            "with-katago",
            "without.engine",
        )
    }
    mac_arm = find_entry("macos-dmg", arch="arm64")
    mac_intel = find_entry("macos-dmg", arch="x64")
    core = find_entry("windows-core-update")
    trt_parts = sorted(
        (
            entry
            for entry in assets
            if entry.get("category") == "tensorrt-advanced"
            and re.search(r"\.7z\.\d{3}$", str(entry.get("name") or ""))
        ),
        key=lambda entry: str(entry["name"]),
    )
    if len(trt_parts) != 2:
        raise ReleaseError(f"Download page expected two TensorRT volumes, found {len(trt_parts)}")

    windows_rows = "".join(
        [
            download_row(
                windows_entries["nvidia"],
                "NVIDIA 显卡",
                "RTX 20 / 30 / 40 系",
                "gpu-card",
                recommended=True,
            ),
            download_row(
                windows_entries["nvidia50.cuda"],
                "RTX 50 CUDA",
                "RTX 5070 / 5080 / 5090",
                "gpu-card",
            ),
            (
                '<li class="download-row trt-row">'
                '<div class="download-main">'
                f'{decorative_icon("speedometer")}'
                '<div class="download-copy">'
                '<div class="download-title">TensorRT 高性能版</div>'
                '<div class="download-description">RTX 20 至 50 · 两个分卷都要下载</div>'
                "</div>"
                "</div>"
                f'<span class="download-size">{format_size(sum(int(entry["sizeBytes"]) for entry in trt_parts))}</span>'
                '<div class="volume-actions">'
                f'{download_action(trt_parts[0], "分卷 1", "TensorRT 高性能版")}'
                f'{download_action(trt_parts[1], "分卷 2", "TensorRT 高性能版")}'
                "</div>"
                "</li>"
            ),
            download_row(
                windows_entries["opencl"],
                "OpenCL 兼容版",
                "AMD / Intel 显卡",
                "gpu-card",
            ),
            download_row(
                windows_entries["with-katago"],
                "CPU 通用版",
                "没有独立显卡也能用",
                "cpu",
            ),
            download_row(
                windows_entries["without.engine"],
                "无引擎版",
                "已有自己的 KataGo",
                "gear",
            ),
        ]
    )
    mac_rows = "".join(
        [
            download_row(
                mac_arm,
                "Apple 芯片",
                "M1 / M2 / M3 / M4 / M5",
                "apple",
                recommended=True,
            ),
            download_row(mac_intel, "Intel 芯片", "旧款 Intel Mac", "cpu"),
        ]
    )
    maintenance_notice = (
        '<div class="maintenance-notice" role="status">下载页面正在更新，当前下载仍可正常使用。</div>'
        if maintenance
        else ""
    )
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="color-scheme" content="light">
  <title>LizzieYzy Next 下载</title>
  <style>
    :root {{ --ink:#153f37; --muted:#68746f; --paper:#fbf8f1; --surface:#fffdf9e8; --line:#ddd5c5; --gold:#d66c1b; --green:#165c4c; --green-hover:#10493d; --focus:#d98a26; }}
    * {{ box-sizing:border-box; }}
    html {{ background:var(--paper); }}
    body {{ min-height:100vh; margin:0; color:var(--ink); background:var(--paper) url("{page_background}") top center/cover fixed no-repeat; font:15px/1.45 "Noto Sans SC","Microsoft YaHei","PingFang SC",sans-serif; }}
    a {{ color:inherit; }}
    main {{ width:min(1280px,calc(100% - 40px)); margin:0 auto; padding:42px 0 30px; }}
    .masthead {{ display:flex; align-items:center; gap:22px; margin-bottom:28px; }}
    .app-icon {{ width:82px; height:82px; flex:0 0 auto; border-radius:22px; box-shadow:0 10px 24px #6a4b2424; }}
    .brand {{ margin:0 0 5px; font:600 31px/1.1 "Iowan Old Style","Palatino Linotype",serif; letter-spacing:.02em; }}
    h1 {{ margin:0; font:700 clamp(40px,4.4vw,64px)/1.08 "Noto Serif SC","Source Han Serif SC","Songti SC","SimSun",serif; letter-spacing:.03em; }}
    .subtitle {{ margin:10px 0 0; color:var(--muted); font-size:19px; }}
    .maintenance-notice {{ margin:-8px 0 18px; padding:10px 14px; border:1px solid #e5bd75; border-radius:12px; background:#fff7e7; color:#6f4d13; }}
    .platform-grid {{ display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr); gap:24px; align-items:stretch; }}
    .platform-panel {{ min-width:0; border:1px solid var(--line); border-radius:20px; background:var(--surface); box-shadow:0 14px 34px #153f3709; overflow:hidden; }}
    .platform-heading {{ padding:22px 24px 16px; }}
    .platform-title {{ display:flex; align-items:center; gap:11px; }}
    .platform-icon {{ width:28px; height:28px; object-fit:contain; }}
    .platform-heading h2 {{ margin:0; font:700 28px/1.2 "Iowan Old Style","Palatino Linotype","Noto Serif SC",serif; }}
    .platform-heading p {{ margin:6px 0 0; color:var(--muted); }}
    .download-list {{ margin:0 18px 18px; padding:0; list-style:none; border:1px solid #e4ddd0; border-radius:14px; background:#fffefa; overflow:hidden; }}
    .download-row {{ min-height:67px; display:grid; grid-template-columns:minmax(0,1fr) auto auto; align-items:center; gap:14px; padding:11px 14px; border-bottom:1px solid #e8e1d5; }}
    .download-row:last-child {{ border-bottom:0; }}
    .download-main {{ min-width:0; display:grid; grid-template-columns:34px minmax(0,1fr); align-items:center; gap:12px; }}
    .row-icon {{ width:30px; height:30px; object-fit:contain; }}
    .download-copy {{ min-width:0; }}
    .download-title {{ display:flex; align-items:center; flex-wrap:wrap; gap:8px; color:#1c2f2a; font-size:16px; font-weight:800; }}
    .download-description {{ margin-top:3px; color:var(--muted); font-size:13px; }}
    .recommendation {{ display:inline-flex; align-items:center; min-height:24px; padding:2px 8px; border-radius:7px; color:#a94f0d; background:#fff0df; font-size:12px; font-weight:800; }}
    .download-size {{ color:var(--muted); font-variant-numeric:tabular-nums; white-space:nowrap; }}
    .download-action {{ display:inline-flex; min-width:74px; min-height:40px; align-items:center; justify-content:center; padding:8px 14px; border-radius:9px; color:#fff; background:var(--green); font-weight:800; text-decoration:none; transition:background-color .18s ease,transform .18s ease; }}
    .download-action:hover {{ background:var(--green-hover); transform:translateY(-1px); }}
    .download-action:focus-visible,.footer-link:focus-visible {{ outline:3px solid var(--focus); outline-offset:3px; }}
    .volume-actions {{ display:flex; gap:7px; }}
    .volume-actions .download-action {{ min-width:64px; padding-inline:10px; }}
    .mac-panel {{ display:flex; flex-direction:column; }}
    .mac-panel .download-list {{ width:calc(100% - 36px); }}
    .install-tip {{ margin:auto 24px 26px; padding:18px 0 4px; border-top:1px solid #e3dccf; color:var(--muted); text-align:center; font-size:16px; }}
    .update-strip {{ display:grid; grid-template-columns:auto minmax(0,1fr) auto; align-items:center; gap:18px; margin-top:18px; padding:16px 22px; border:1px solid var(--line); border-radius:16px; background:var(--surface); }}
    .update-mark {{ width:30px; height:30px; object-fit:contain; }}
    .update-copy strong {{ display:inline; color:#243832; font-size:17px; }}
    .update-copy span {{ margin-left:14px; color:var(--muted); }}
    .update-strip .download-action {{ color:#a74a0a; background:#fffaf2; border:1px solid var(--gold); }}
    .update-strip .download-action:hover {{ color:#fff; background:var(--gold); }}
    footer {{ display:flex; justify-content:center; gap:0; margin-top:24px; color:var(--ink); }}
    .footer-link {{ display:inline-flex; align-items:center; gap:8px; padding:8px 20px; font-weight:700; text-decoration:none; }}
    .footer-icon {{ width:17px; height:17px; object-fit:contain; }}
    .footer-link+.footer-link {{ border-left:1px solid #bdb5a8; }}
    @media(max-width:980px) {{
      .platform-grid {{ grid-template-columns:1fr; }}
      .install-tip {{ margin-top:20px; }}
    }}
    @media(max-width:660px) {{
      main {{ width:min(100% - 24px,1280px); padding-top:24px; }}
      .masthead {{ align-items:flex-start; gap:14px; }}
      .app-icon {{ width:62px; height:62px; border-radius:17px; }}
      .brand {{ font-size:23px; }}
      h1 {{ font-size:36px; }}
      .subtitle {{ font-size:16px; }}
      .platform-heading {{ padding:18px 16px 13px; }}
      .download-list {{ margin:0 10px 12px; }}
      .download-row {{ grid-template-columns:minmax(0,1fr) auto; gap:8px 12px; padding:13px 12px; }}
      .download-main {{ grid-template-columns:30px minmax(0,1fr); gap:10px; }}
      .row-icon {{ width:27px; height:27px; }}
      .download-size {{ grid-column:1; }}
      .download-action,.volume-actions {{ grid-column:2; grid-row:1 / span 2; }}
      .trt-row {{ align-items:start; }}
      .volume-actions {{ flex-direction:column; }}
      .volume-actions .download-action {{ min-height:36px; }}
      .update-strip {{ grid-template-columns:auto 1fr; padding:15px; }}
      .update-copy strong,.update-copy span {{ display:block; margin:0; }}
      .update-strip>.download-action {{ grid-column:1 / -1; grid-row:auto; width:100%; }}
    }}
    @media(prefers-reduced-motion:reduce) {{ .download-action {{ transition:none; }} }}
  </style>
</head>
<body><main>
  <header class="masthead">
    <img class="app-icon" src="{app_icon}" width="82" height="82" alt="LizzieYzy Next 图标">
    <div><p class="brand">LizzieYzy Next</p><h1>选择你的版本</h1><p class="subtitle">先选电脑，再下载与你硬件匹配的版本</p></div>
  </header>
  {maintenance_notice}
  <div class="platform-grid">
    <section class="platform-panel" aria-labelledby="windows-heading">
      <div class="platform-heading"><div class="platform-title">{decorative_icon("windows", "platform-icon")}<h2 id="windows-heading">Windows</h2></div><p>根据显卡选择；不确定时优先使用 CPU 通用版</p></div>
      <ul class="download-list">{windows_rows}</ul>
    </section>
    <section class="platform-panel mac-panel" aria-labelledby="mac-heading">
      <div class="platform-heading"><div class="platform-title">{decorative_icon("apple", "platform-icon")}<h2 id="mac-heading">macOS</h2></div><p>选择与你 Mac 芯片匹配的版本</p></div>
      <ul class="download-list">{mac_rows}</ul>
      <p class="install-tip">下载后拖到“应用程序”即可安装</p>
    </section>
  </div>
  <section class="update-strip" aria-label="Windows 主程序小更新">
    {decorative_icon("arrow-repeat", "update-mark")}
    <div class="update-copy"><strong>已经安装过免安装版？</strong><span>只更新主程序，约 {format_size(int(core["sizeBytes"]))}</span></div>
    {download_action(core, "下载小更新", "Windows 主程序")}
  </section>
  <footer>
    <a class="footer-link" href="{html.escape(help_url, quote=True)}">{decorative_icon("book", "footer-icon")}安装帮助</a>
    <a class="footer-link" href="{history_url}">{decorative_icon("clock-history", "footer-icon")}历史版本</a>
  </footer>
</main></body></html>
"""


def sign_manifest(manifest: dict[str, Any], private_key_pem: bytes, key_id: str) -> dict[str, Any]:
    try:
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    except ImportError as exc:
        raise ReleaseError("cryptography is required to sign update manifests") from exc
    key = serialization.load_pem_private_key(private_key_pem, password=None)
    if not isinstance(key, Ed25519PrivateKey):
        raise ReleaseError("Update signing key is not an Ed25519 private key")
    payload = json.dumps(
        manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return {
        "envelopeVersion": 1,
        "algorithm": "Ed25519",
        "keyId": key_id,
        "payload": base64.b64encode(payload).decode("ascii"),
        "signature": base64.b64encode(key.sign(payload)).decode("ascii"),
    }


def json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def github_session(token: str):
    try:
        import requests
        from requests.adapters import HTTPAdapter
        from urllib3.util.retry import Retry
    except ImportError as exc:
        raise ReleaseError("requests is required for GitHub release promotion") from exc
    session = requests.Session()
    retries = Retry(
        total=5,
        connect=5,
        read=5,
        backoff_factor=1.0,
        status_forcelist=(429, 500, 502, 503, 504),
        allowed_methods=("GET", "HEAD", "DELETE", "POST", "PATCH"),
    )
    session.mount("https://", HTTPAdapter(max_retries=retries))
    session.headers.update(
        {
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "lizzieyzy-next-r2-publisher",
        }
    )
    return session


def fetch_release(session, repository: str, tag: str) -> dict[str, Any]:
    response = session.get(f"https://api.github.com/repos/{repository}/releases/tags/{tag}", timeout=60)
    if response.status_code != 200:
        raise ReleaseError(f"GitHub release lookup failed: HTTP {response.status_code} {response.text[:300]}")
    return response.json()


def content_type(name: str) -> str:
    if name.endswith(".json"):
        return "application/json; charset=utf-8"
    if name.endswith(".html"):
        return "text/html; charset=utf-8"
    if name.endswith(".txt"):
        return "text/plain; charset=utf-8"
    if name.endswith(".zip"):
        return "application/zip"
    if name.endswith(".dmg"):
        return "application/x-apple-diskimage"
    return "application/octet-stream"


def r2_client(account_id: str, access_key_id: str, secret_access_key: str):
    try:
        import boto3
        from botocore.config import Config
    except ImportError as exc:
        raise ReleaseError("boto3 is required for R2 publishing") from exc
    return boto3.client(
        "s3",
        endpoint_url=f"https://{account_id}.r2.cloudflarestorage.com",
        aws_access_key_id=access_key_id,
        aws_secret_access_key=secret_access_key,
        region_name="auto",
        config=Config(signature_version="s3v4", retries={"max_attempts": 8, "mode": "adaptive"}),
    )


def put_bytes(
    client,
    bucket: str,
    key: str,
    body: bytes,
    *,
    cache_control: str,
    disposition: str = "inline",
    metadata: dict[str, str] | None = None,
) -> None:
    client.put_object(
        Bucket=bucket,
        Key=key,
        Body=body,
        ContentType=content_type(key),
        CacheControl=cache_control,
        ContentDisposition=disposition,
        Metadata=metadata or {},
    )


def list_keys(client, bucket: str, prefix: str) -> list[str]:
    keys: list[str] = []
    token: str | None = None
    while True:
        kwargs: dict[str, Any] = {"Bucket": bucket, "Prefix": prefix}
        if token:
            kwargs["ContinuationToken"] = token
        response = client.list_objects_v2(**kwargs)
        keys.extend(str(entry["Key"]) for entry in response.get("Contents", []))
        if not response.get("IsTruncated"):
            break
        token = str(response["NextContinuationToken"])
    return keys


def stale_release_keys(existing: Iterable[str], keep_keys: set[str]) -> list[str]:
    return sorted(key for key in existing if key not in keep_keys)


def delete_unselected_release_objects(
    client, bucket: str, keep_keys: set[str]
) -> None:
    stale = stale_release_keys(list_keys(client, bucket, "releases/"), keep_keys)
    for start in range(0, len(stale), 1000):
        batch = stale[start : start + 1000]
        if not batch:
            continue
        response = client.delete_objects(
            Bucket=bucket,
            Delete={"Objects": [{"Key": key} for key in batch], "Quiet": True},
        )
        if response.get("Errors"):
            raise ReleaseError(
                f"R2 failed to delete unselected release objects: {response['Errors']}"
            )
    remaining = stale_release_keys(
        list_keys(client, bucket, "releases/"), keep_keys
    )
    if remaining:
        raise ReleaseError(
            f"Unselected R2 release objects remain after deletion: {remaining[:5]}"
        )


def abort_incomplete_release_uploads(client, bucket: str) -> None:
    key_marker: str | None = None
    upload_id_marker: str | None = None
    while True:
        kwargs: dict[str, Any] = {"Bucket": bucket, "Prefix": "releases/"}
        if key_marker:
            kwargs["KeyMarker"] = key_marker
        if upload_id_marker:
            kwargs["UploadIdMarker"] = upload_id_marker
        response = client.list_multipart_uploads(**kwargs)
        for upload in response.get("Uploads", []):
            client.abort_multipart_upload(
                Bucket=bucket,
                Key=str(upload["Key"]),
                UploadId=str(upload["UploadId"]),
            )
        if not response.get("IsTruncated"):
            break
        key_marker = str(response.get("NextKeyMarker") or "") or None
        upload_id_marker = (
            str(response.get("NextUploadIdMarker") or "") or None
        )


def verify_r2_inventory(client, bucket: str, assets: Iterable[Asset]) -> None:
    expected = {asset.r2_key: asset for asset in assets}
    actual_keys = set(list_keys(client, bucket, "releases/"))
    if actual_keys != set(expected):
        missing = sorted(set(expected) - actual_keys)
        extra = sorted(actual_keys - set(expected))
        raise ReleaseError(
            f"R2 release inventory mismatch; missing={missing[:5]}, extra={extra[:5]}"
        )
    total = 0
    for key, asset in expected.items():
        head = client.head_object(Bucket=bucket, Key=key)
        size = int(head.get("ContentLength", -1))
        sha256 = str(head.get("Metadata", {}).get("sha256", "")).lower()
        if size != asset.size or sha256 != asset.sha256:
            raise ReleaseError(f"R2 object metadata mismatch: {asset.name}")
        total += size
    if total > R2_SIZE_LIMIT:
        raise ReleaseError(
            f"R2 actual release inventory is {total:,} bytes, above the "
            f"{R2_SIZE_LIMIT:,}-byte hard limit"
        )


def download_small_asset(session, asset: Asset) -> bytes:
    response = session.get(
        asset.api_url,
        headers={"Accept": "application/octet-stream"},
        timeout=(30, 180),
    )
    if response.status_code != 200:
        raise ReleaseError(f"GitHub asset download failed for {asset.name}: HTTP {response.status_code}")
    body = response.content
    verify_asset_bytes(asset, body)
    return body


def verify_asset_bytes(asset: Asset, body: bytes) -> None:
    if len(body) != asset.size:
        raise ReleaseError(f"Asset size mismatch for {asset.name}: {len(body)} != {asset.size}")
    digest = hashlib.sha256(body).hexdigest()
    if digest != asset.sha256:
        raise ReleaseError(f"Asset SHA-256 mismatch for {asset.name}")


def object_matches(client, bucket: str, asset: Asset) -> bool:
    try:
        head = client.head_object(Bucket=bucket, Key=asset.r2_key)
    except Exception as exc:  # botocore exception type is intentionally optional at import time
        response = getattr(exc, "response", {})
        status = response.get("ResponseMetadata", {}).get("HTTPStatusCode")
        if status in {403, 404}:
            return False
        raise
    return int(head.get("ContentLength", -1)) == asset.size and str(
        head.get("Metadata", {}).get("sha256", "")
    ).lower() == asset.sha256


def upload_asset(session, client, bucket: str, asset: Asset) -> None:
    if object_matches(client, bucket, asset):
        print(f"R2 reuse verified object: {asset.name}")
        return
    disposition = f'attachment; filename="{asset.name}"'
    metadata = {"sha256": asset.sha256, "github-asset": asset.name}
    if asset.size <= SMALL_OBJECT_LIMIT:
        body = download_small_asset(session, asset)
        put_bytes(
            client,
            bucket,
            asset.r2_key,
            body,
            cache_control="public, max-age=31536000, immutable",
            disposition=disposition,
            metadata=metadata,
        )
        return

    create = client.create_multipart_upload(
        Bucket=bucket,
        Key=asset.r2_key,
        ContentType=content_type(asset.name),
        CacheControl="public, max-age=31536000, immutable",
        ContentDisposition=disposition,
        Metadata=metadata,
    )
    upload_id = create["UploadId"]
    completed_parts: list[dict[str, Any]] = []
    digest = hashlib.sha256()
    try:
        for number, start in enumerate(range(0, asset.size, MULTIPART_PART_SIZE), start=1):
            end = min(asset.size, start + MULTIPART_PART_SIZE) - 1
            response = session.get(
                asset.api_url,
                headers={
                    "Accept": "application/octet-stream",
                    "Range": f"bytes={start}-{end}",
                },
                timeout=(30, 300),
                stream=True,
            )
            if response.status_code != 206:
                raise ReleaseError(
                    f"GitHub did not honor Range for {asset.name}: HTTP {response.status_code}"
                )
            expected = end - start + 1
            body = bytearray()
            for chunk in response.iter_content(chunk_size=1024 * 1024):
                if chunk:
                    body.extend(chunk)
                    if len(body) > expected:
                        raise ReleaseError(f"GitHub returned too many bytes for {asset.name} part {number}")
            if len(body) != expected:
                raise ReleaseError(
                    f"GitHub returned {len(body)} of {expected} bytes for {asset.name} part {number}"
                )
            digest.update(body)
            uploaded = client.upload_part(
                Bucket=bucket,
                Key=asset.r2_key,
                UploadId=upload_id,
                PartNumber=number,
                Body=bytes(body),
            )
            completed_parts.append({"PartNumber": number, "ETag": uploaded["ETag"]})
            print(f"R2 upload {asset.name}: {end + 1:,}/{asset.size:,}")
        if digest.hexdigest() != asset.sha256:
            raise ReleaseError(f"Streamed SHA-256 mismatch for {asset.name}")
        client.complete_multipart_upload(
            Bucket=bucket,
            Key=asset.r2_key,
            UploadId=upload_id,
            MultipartUpload={"Parts": completed_parts},
        )
    except BaseException:
        client.abort_multipart_upload(
            Bucket=bucket, Key=asset.r2_key, UploadId=upload_id
        )
        raise
    if not object_matches(client, bucket, asset):
        raise ReleaseError(f"R2 HEAD verification failed after upload: {asset.name}")


def replace_github_asset(
    session, release: dict[str, Any], name: str, body: bytes, mime_type: str
) -> None:
    for existing in release_assets(release):
        if existing.get("name") == name:
            response = session.delete(existing["url"], timeout=60)
            if response.status_code != 204:
                raise ReleaseError(f"Could not replace GitHub asset {name}: HTTP {response.status_code}")
    upload_url = str(release["upload_url"]).split("{", 1)[0]
    response = session.post(
        upload_url,
        params={"name": name},
        headers={"Content-Type": mime_type},
        data=body,
        timeout=(30, 180),
    )
    if response.status_code != 201:
        raise ReleaseError(f"GitHub asset upload failed for {name}: HTTP {response.status_code} {response.text[:300]}")


def _verify_public_object(requests, public_url: str, asset: Asset) -> None:
    response = requests.head(
        public_url,
        headers={"Accept-Encoding": "identity"},
        allow_redirects=True,
        timeout=60,
    )
    if response.status_code != 200:
        raise ReleaseError(f"HEAD returned HTTP {response.status_code}")
    actual_length = int(response.headers.get("Content-Length", -1))
    if actual_length != asset.size:
        raise ReleaseError(
            f"Content-Length is {actual_length}, expected {asset.size}"
        )
    if response.headers.get("Accept-Ranges", "").lower() != "bytes":
        raise ReleaseError("object does not advertise byte ranges")
    if "immutable" not in response.headers.get("Cache-Control", "").lower():
        raise ReleaseError("object is missing immutable cache metadata")
    if "attachment" not in response.headers.get("Content-Disposition", "").lower():
        raise ReleaseError("object is missing attachment metadata")

    partial = requests.get(
        public_url,
        headers={"Range": "bytes=0-0", "Accept-Encoding": "identity"},
        allow_redirects=True,
        timeout=60,
        stream=True,
    )
    try:
        if partial.status_code != 206:
            raise ReleaseError(f"byte range returned HTTP {partial.status_code}")
        range_length = int(partial.headers.get("Content-Length", -1))
        if range_length != 1:
            raise ReleaseError(
                f"byte range Content-Length is {range_length}, expected 1"
            )
        expected_range = f"bytes 0-0/{asset.size}"
        actual_range = partial.headers.get("Content-Range", "")
        if actual_range.lower() != expected_range.lower():
            raise ReleaseError(
                f"Content-Range is {actual_range!r}, expected {expected_range!r}"
            )
        first_chunk = next(partial.iter_content(chunk_size=2), b"")
        if len(first_chunk) != 1:
            raise ReleaseError(
                f"byte range body has {len(first_chunk)} bytes, expected 1"
            )
    finally:
        partial.close()


def verify_public_objects(public_base: str, assets: Iterable[Asset]) -> None:
    try:
        import requests
    except ImportError as exc:
        raise ReleaseError("requests is required for public R2 verification") from exc
    if not public_base.lower().startswith("https://"):
        raise ReleaseError("Public R2 base URL must use HTTPS")
    for asset in assets:
        public_url = r2_url(public_base, asset)
        for attempt in range(1, PUBLIC_VERIFY_ATTEMPTS + 1):
            try:
                _verify_public_object(requests, public_url, asset)
                break
            except (ReleaseError, requests.RequestException) as exc:
                if attempt == PUBLIC_VERIFY_ATTEMPTS:
                    raise ReleaseError(
                        f"Public R2 verification failed for {asset.name} after "
                        f"{PUBLIC_VERIFY_ATTEMPTS} attempts: {exc}"
                    ) from exc
                delay = PUBLIC_VERIFY_BACKOFF_SECONDS[attempt - 1]
                print(
                    f"Public R2 verification retry {attempt}/"
                    f"{PUBLIC_VERIFY_ATTEMPTS} for {asset.name} in {delay}s: {exc}",
                    file=sys.stderr,
                )
                time.sleep(delay)


def _cache_busted_url(url: str, attempt: int) -> str:
    separator = "&" if "?" in url else "?"
    return f"{url}{separator}r2-verify={int(time.time())}-{attempt}"


def _verify_public_document(
    requests,
    url: str,
    *,
    expected_content_type: str,
    expected_body: bytes | None = None,
    required_marker: bytes | None = None,
) -> None:
    response = requests.get(
        url,
        headers={
            "Accept-Encoding": "identity",
            "Cache-Control": "no-cache",
            "Pragma": "no-cache",
        },
        allow_redirects=True,
        timeout=60,
        stream=True,
    )
    try:
        if response.status_code != 200:
            raise ReleaseError(f"GET returned HTTP {response.status_code}")
        expected_host = urllib.parse.urlparse(url).hostname
        final_host = urllib.parse.urlparse(str(response.url)).hostname
        if final_host != expected_host:
            raise ReleaseError(
                f"request redirected from {expected_host} to {final_host}"
            )
        actual_type = response.headers.get("Content-Type", "").lower()
        if not actual_type.startswith(expected_content_type.lower()):
            raise ReleaseError(
                f"Content-Type is {actual_type!r}, expected {expected_content_type!r}"
            )

        body = bytearray()
        for chunk in response.iter_content(chunk_size=64 * 1024):
            if not chunk:
                continue
            body.extend(chunk)
            if len(body) > MAX_PUBLIC_METADATA_BYTES:
                raise ReleaseError(
                    f"response exceeds {MAX_PUBLIC_METADATA_BYTES} bytes"
                )
        actual_body = bytes(body)
        if expected_body is not None and actual_body != expected_body:
            actual_sha = hashlib.sha256(actual_body).hexdigest()[:12]
            expected_sha = hashlib.sha256(expected_body).hexdigest()[:12]
            raise ReleaseError(
                "public body differs from the uploaded object "
                f"({len(actual_body)} bytes, sha256 {actual_sha}; expected "
                f"{len(expected_body)} bytes, sha256 {expected_sha})"
            )
        if required_marker is not None and required_marker not in actual_body:
            raise ReleaseError("public body is missing the expected page marker")
    finally:
        response.close()


def _verify_public_with_retry(requests, description: str, url: str, **kwargs) -> None:
    for attempt in range(1, PUBLIC_VERIFY_ATTEMPTS + 1):
        request_url = _cache_busted_url(url, attempt)
        try:
            _verify_public_document(requests, request_url, **kwargs)
            return
        except (ReleaseError, requests.RequestException) as exc:
            if attempt == PUBLIC_VERIFY_ATTEMPTS:
                raise ReleaseError(
                    f"Public R2 verification failed for {description} after "
                    f"{PUBLIC_VERIFY_ATTEMPTS} attempts: {exc}"
                ) from exc
            delay = PUBLIC_VERIFY_BACKOFF_SECONDS[attempt - 1]
            print(
                f"Public R2 verification retry {attempt}/"
                f"{PUBLIC_VERIFY_ATTEMPTS} for {description} in {delay}s: {exc}",
                file=sys.stderr,
            )
            time.sleep(delay)


def verify_public_homepage(public_base: str) -> None:
    try:
        import requests
    except ImportError as exc:
        raise ReleaseError("requests is required for public R2 verification") from exc
    if not public_base.lower().startswith("https://"):
        raise ReleaseError("Public R2 base URL must use HTTPS")
    _verify_public_with_retry(
        requests,
        "download homepage route",
        public_base.rstrip("/") + "/",
        expected_content_type="text/html",
        required_marker=b"LizzieYzy Next",
    )


def verify_public_stable_channel(
    public_base: str,
    *,
    index_body: bytes,
    catalog_body: bytes,
    envelope_body: bytes,
) -> None:
    try:
        import requests
    except ImportError as exc:
        raise ReleaseError("requests is required for public R2 verification") from exc
    if not public_base.lower().startswith("https://"):
        raise ReleaseError("Public R2 base URL must use HTTPS")
    base = public_base.rstrip("/")
    documents = (
        ("download homepage", base + "/", "text/html", index_body),
        ("download index", base + "/index.html", "text/html", index_body),
        (
            "stable catalog",
            base + "/channels/stable/catalog.json",
            "application/json",
            catalog_body,
        ),
        (
            "stable update envelope",
            base + "/channels/stable/update-envelope.json",
            "application/json",
            envelope_body,
        ),
    )
    for description, url, content_type, expected_body in documents:
        _verify_public_with_retry(
            requests,
            description,
            url,
            expected_content_type=content_type,
            expected_body=expected_body,
        )


def verify_and_activate_stable_channel(
    client,
    bucket: str,
    public_base: str,
    assets: Iterable[Asset],
    catalog: dict[str, Any],
    envelope: dict[str, Any],
    *,
    skip_public_verify: bool,
) -> tuple[bytes, bytes]:
    asset_list = list(assets)
    if not skip_public_verify:
        verify_public_objects(public_base, asset_list)
        verify_public_homepage(public_base)

    catalog_body = json_bytes(catalog)
    envelope_body = json_bytes(envelope)
    index_body = render_index(catalog).encode("utf-8")
    put_bytes(
        client,
        bucket,
        "channels/stable/catalog.json",
        catalog_body,
        cache_control="public, max-age=60, must-revalidate",
    )
    put_bytes(
        client,
        bucket,
        "index.html",
        index_body,
        cache_control="public, max-age=300, must-revalidate",
    )
    # The envelope is the activation pointer. Publish it only after public asset verification and
    # every other stable-channel object are complete.
    put_bytes(
        client,
        bucket,
        "channels/stable/update-envelope.json",
        envelope_body,
        cache_control="public, max-age=60, must-revalidate",
    )
    if not skip_public_verify:
        verify_public_stable_channel(
            public_base,
            index_body=index_body,
            catalog_body=catalog_body,
            envelope_body=envelope_body,
        )
    return catalog_body, envelope_body


def promote(args: argparse.Namespace) -> None:
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    account_id = os.environ.get("CLOUDFLARE_R2_ACCOUNT_ID", "").strip()
    access_key = os.environ.get("CLOUDFLARE_R2_ACCESS_KEY_ID", "").strip()
    secret_key = os.environ.get("CLOUDFLARE_R2_SECRET_ACCESS_KEY", "").strip()
    missing = [
        name
        for name, value in {
            "GITHUB_TOKEN": token,
            "CLOUDFLARE_R2_ACCOUNT_ID": account_id,
            "CLOUDFLARE_R2_ACCESS_KEY_ID": access_key,
            "CLOUDFLARE_R2_SECRET_ACCESS_KEY": secret_key,
        }.items()
        if not value
    ]
    if missing:
        raise ReleaseError("Missing required secret environment variables: " + ", ".join(missing))
    private_key = Path(args.private_key).read_bytes()
    session = github_session(token)
    release = fetch_release(session, args.repository, args.tag)
    if release.get("draft"):
        raise ReleaseError("Draft releases cannot be promoted to stable")
    if not release.get("prerelease") and not args.allow_stable:
        raise ReleaseError("Release is already stable; pass --allow-stable only for recovery")

    selected = select_r2_assets(release, args.public_base)
    total = sum(asset.size for asset in selected)
    print(f"R2 promotion plan: {len(selected)} assets, {total:,} bytes")
    manifest = build_manifest(release, selected, args.public_base)
    catalog = build_catalog(release, selected, args.public_base)
    envelope = sign_manifest(manifest, private_key, args.key_id)
    legacy = build_legacy_manifest(manifest)

    client = r2_client(account_id, access_key, secret_key)
    maintenance_html = render_index(catalog, maintenance=True).encode("utf-8")
    put_bytes(
        client,
        args.bucket,
        "index.html",
        maintenance_html,
        cache_control="no-store",
    )
    keep_keys = {asset.r2_key for asset in selected}
    abort_incomplete_release_uploads(client, args.bucket)
    delete_unselected_release_objects(client, args.bucket, keep_keys)
    for asset in selected:
        upload_asset(session, client, args.bucket, asset)
    verify_r2_inventory(client, args.bucket, selected)
    catalog_body, envelope_body = verify_and_activate_stable_channel(
        client,
        args.bucket,
        args.public_base,
        selected,
        catalog,
        envelope,
        skip_public_verify=args.skip_public_verify,
    )
    replace_github_asset(session, release, UPDATE_ENVELOPE_ASSET, envelope_body, "application/json")
    replace_github_asset(session, release, CATALOG_ASSET, catalog_body, "application/json")
    replace_github_asset(session, release, LEGACY_MANIFEST_ASSET, json_bytes(legacy), "application/json")

    release_body = stable_release_body(release, selected, args.public_base)
    response = session.patch(
        release["url"],
        json={
            "body": release_body,
            "draft": False,
            "prerelease": False,
            "make_latest": "true",
        },
        timeout=60,
    )
    if response.status_code != 200:
        raise ReleaseError(f"GitHub stable promotion failed: HTTP {response.status_code} {response.text[:300]}")
    print(f"Stable release promoted: {response.json().get('html_url')}")


def plan(args: argparse.Namespace) -> None:
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if not token:
        raise ReleaseError("GITHUB_TOKEN is required to inspect release assets")
    session = github_session(token)
    release = fetch_release(session, args.repository, args.tag)
    selected = select_r2_assets(release, args.public_base)
    manifest = build_manifest(release, selected, args.public_base)
    catalog = build_catalog(release, selected, args.public_base)
    result = {
        "tag": args.tag,
        "assetCount": len(selected),
        "totalSizeBytes": sum(asset.size for asset in selected),
        "limitBytes": R2_SIZE_LIMIT,
        "assets": [asset.name for asset in selected],
        "manifest": manifest,
        "catalog": catalog,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    subcommands = root.add_subparsers(dest="command", required=True)
    for name in ("plan", "promote"):
        command = subcommands.add_parser(name)
        command.add_argument("--repository", default=DEFAULT_REPOSITORY)
        command.add_argument("--tag", required=True)
        command.add_argument("--public-base", default=DEFAULT_PUBLIC_BASE)
        if name == "promote":
            command.add_argument("--bucket", default=DEFAULT_BUCKET)
            command.add_argument("--private-key", required=True)
            command.add_argument("--key-id", default=DEFAULT_KEY_ID)
            command.add_argument("--allow-stable", action="store_true")
            command.add_argument("--skip-public-verify", action="store_true")
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "plan":
            plan(args)
        else:
            promote(args)
        return 0
    except ReleaseError as exc:
        print(f"R2 release error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
