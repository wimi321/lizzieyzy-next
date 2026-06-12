#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
VERSION_FILE = ROOT / 'engines' / 'katago' / 'VERSION.txt'
PREPARE_BUNDLED_KATAGO_SCRIPT = ROOT / 'scripts' / 'prepare_bundled_katago.sh'

ASSET_SPECS = [
    ('windows_installer', 'windows64.with-katago.installer.exe', 'Windows 64 位，CPU 兼容版', 'Windows x64, CPU fallback'),
    ('windows_portable', 'windows64.with-katago.portable.zip', 'Windows 64 位，CPU 兼容版，免安装', 'Windows x64, CPU fallback, no installer'),
    ('windows_opencl_installer', 'windows64.opencl.installer.exe', 'Windows 64 位，OpenCL 推荐版', 'Windows x64, OpenCL recommended'),
    ('windows_opencl_portable', 'windows64.opencl.portable.zip', 'Windows 64 位，OpenCL 推荐版，免安装', 'Windows x64, OpenCL recommended, no installer'),
    ('windows_nvidia_installer', 'windows64.nvidia.installer.exe', 'Windows 64 位，英伟达显卡', 'Windows x64, NVIDIA GPU'),
    ('windows_nvidia_portable', 'windows64.nvidia.portable.zip', 'Windows 64 位，英伟达显卡，免安装', 'Windows x64, NVIDIA GPU, no installer'),
    ('windows_nvidia50_cuda_installer', 'windows64.nvidia50.cuda.installer.exe', 'Windows 64 位，RTX 50 CUDA 版', 'Windows x64, RTX 50 CUDA'),
    ('windows_nvidia50_cuda_portable', 'windows64.nvidia50.cuda.portable.zip', 'Windows 64 位，RTX 50 CUDA 版，免安装', 'Windows x64, RTX 50 CUDA, no installer'),
    ('windows_no_engine_installer', 'windows64.without.engine.installer.exe', 'Windows 64 位，想自己配引擎，也想安装器', 'Windows x64, your own engine with installer'),
    ('windows_no_engine_portable', 'windows64.without.engine.portable.zip', 'Windows 64 位，想自己配引擎', 'Windows x64, your own engine'),
    ('mac_arm64', 'mac-apple-silicon.with-katago.dmg', 'macOS Apple Silicon', 'macOS Apple Silicon'),
    ('mac_amd64', 'mac-intel.with-katago.dmg', 'macOS Intel', 'macOS Intel'),
    ('linux64', 'linux64.with-katago.zip', 'Linux 64 位，CPU 兼容版', 'Linux x64, CPU fallback'),
    ('linux64_opencl', 'linux64.opencl.zip', 'Linux 64 位，OpenCL 版', 'Linux x64, OpenCL'),
    ('linux64_nvidia', 'linux64.nvidia.zip', 'Linux 64 位，NVIDIA CUDA 版', 'Linux x64, NVIDIA CUDA'),
]
TENSORRT_SPLIT_README_SUFFIX = 'windows64.nvidia.tensorrt.portable.README.txt'
TENSORRT_SPLIT_PART_PATTERN = r'windows64\.nvidia\.tensorrt\.portable\.7z\.\d+$'
TENSORRT_SPLIT_MANIFEST_SUFFIX = 'windows64.nvidia.tensorrt.portable.manifest.json'
TENSORRT_SPLIT_SHA256_SUFFIX = 'windows64.nvidia.tensorrt.portable.sha256.txt'
TENSORRT_SPLIT_ASSET_KEYS = (
    'windows_tensorrt_split_readme',
    'windows_tensorrt_split_parts',
    'windows_tensorrt_split_sha256',
    'windows_tensorrt_split_manifest',
)

RELEASE_LANGUAGES = ('中文', '繁體中文', 'English', '日本語', '한국어', 'ภาษาไทย')
SECTION_KEYS = ('updates', 'before', 'download', 'why', 'contact')


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description='Generate polished multi-language GitHub release notes.')
    parser.add_argument('--date-tag', help='Release date tag, for example 2026-03-23')
    parser.add_argument('--release-dir', default=str(ROOT / 'dist' / 'release'), help='Directory containing release assets')
    parser.add_argument('--release-tag', help='GitHub release tag, used for direct asset links')
    parser.add_argument('--repo', default='wimi321/lizzieyzy-next', help='GitHub repo in owner/name format')
    parser.add_argument('--from-gh', action='store_true', help='Read asset names from GitHub release instead of local dist/release')
    parser.add_argument('--output', help='Output markdown file path; defaults to stdout')
    return parser.parse_args()


def load_bundle_metadata() -> dict[str, str]:
    metadata = {
        'katago_version': 'Unknown',
        'model_source': 'Unknown',
        'windows_bundle': 'Unknown',
        'windows_opencl_bundle': 'Unknown',
        'windows_nvidia_bundle': 'Unknown',
        'windows_nvidia50_cuda_bundle': 'Unknown',
        'linux_bundle': 'Unknown',
        'linux_opencl_bundle': 'Unknown',
        'linux_nvidia_bundle': 'Unknown',
    }
    if VERSION_FILE.exists():
        for raw_line in VERSION_FILE.read_text(encoding='utf-8').splitlines():
            if ':' not in raw_line:
                continue
            key, value = raw_line.split(':', 1)
            key = key.strip().lower()
            value = value.strip()
            if key == 'katago release':
                metadata['katago_version'] = value
            elif key == 'windows bundle':
                metadata['windows_bundle'] = value
            elif key == 'windows opencl bundle':
                metadata['windows_opencl_bundle'] = value
            elif key == 'windows nvidia bundle':
                metadata['windows_nvidia_bundle'] = value
            elif key == 'windows nvidia 50 cuda bundle':
                metadata['windows_nvidia50_cuda_bundle'] = value
            elif key == 'linux bundle':
                metadata['linux_bundle'] = value
            elif key == 'linux opencl bundle':
                metadata['linux_opencl_bundle'] = value
            elif key == 'linux nvidia bundle':
                metadata['linux_nvidia_bundle'] = value
            elif key == 'model source':
                metadata['model_source'] = value

    if PREPARE_BUNDLED_KATAGO_SCRIPT.exists():
        script_text = PREPARE_BUNDLED_KATAGO_SCRIPT.read_text(encoding='utf-8')
        pattern_map = {
            'katago_version': r'KATAGO_TAG="\$\{KATAGO_TAG:-([^"]+)\}"',
            'windows_bundle': r'WINDOWS_ASSET="\$\{WINDOWS_ASSET:-([^"]+)\}"',
            'windows_opencl_bundle': r'WINDOWS_OPENCL_ASSET="\$\{WINDOWS_OPENCL_ASSET:-([^"]+)\}"',
            'windows_nvidia_bundle': r'WINDOWS_NVIDIA_ASSET="\$\{WINDOWS_NVIDIA_ASSET:-([^"]+)\}"',
            'windows_nvidia50_cuda_bundle': r'WINDOWS_NVIDIA50_CUDA_ASSET="\$\{WINDOWS_NVIDIA50_CUDA_ASSET:-([^"]+)\}"',
            'linux_bundle': r'LINUX_ASSET="\$\{LINUX_ASSET:-([^"]+)\}"',
            'linux_opencl_bundle': r'LINUX_OPENCL_ASSET="\$\{LINUX_OPENCL_ASSET:-([^"]+)\}"',
            'linux_nvidia_bundle': r'LINUX_NVIDIA_ASSET="\$\{LINUX_NVIDIA_ASSET:-([^"]+)\}"',
            'model_source': r'PREFERRED_MODEL_NAME="\$\{PREFERRED_MODEL_NAME:-([^"]+)\}"',
        }
        script_metadata: dict[str, str] = {}
        for key, pattern in pattern_map.items():
            match = re.search(pattern, script_text)
            if match:
                script_metadata[key] = match.group(1).strip()

        for key in ('katago_version', 'model_source'):
            if metadata[key] == 'Unknown' and key in script_metadata:
                metadata[key] = script_metadata[key]

        for key in (
            'windows_bundle',
            'windows_opencl_bundle',
            'windows_nvidia_bundle',
            'windows_nvidia50_cuda_bundle',
            'linux_bundle',
            'linux_opencl_bundle',
            'linux_nvidia_bundle',
        ):
            if key in script_metadata:
                metadata[key] = script_metadata[key]

    katago_version = metadata['katago_version']
    if katago_version != 'Unknown':
        metadata['windows_bundle'] = metadata['windows_bundle'].replace('${KATAGO_TAG}', katago_version)
        metadata['windows_opencl_bundle'] = metadata['windows_opencl_bundle'].replace('${KATAGO_TAG}', katago_version)
        metadata['windows_nvidia_bundle'] = metadata['windows_nvidia_bundle'].replace('${KATAGO_TAG}', katago_version)
        metadata['windows_nvidia50_cuda_bundle'] = metadata['windows_nvidia50_cuda_bundle'].replace('${KATAGO_TAG}', katago_version)
        metadata['linux_bundle'] = metadata['linux_bundle'].replace('${KATAGO_TAG}', katago_version)
        metadata['linux_opencl_bundle'] = metadata['linux_opencl_bundle'].replace('${KATAGO_TAG}', katago_version)
        metadata['linux_nvidia_bundle'] = metadata['linux_nvidia_bundle'].replace('${KATAGO_TAG}', katago_version)
    if katago_version != 'Unknown':
        if metadata['windows_bundle'] == 'Unknown':
            metadata['windows_bundle'] = f'katago-{katago_version}-eigen-windows-x64.zip'
        if metadata['windows_opencl_bundle'] == 'Unknown':
            metadata['windows_opencl_bundle'] = f'katago-{katago_version}-opencl-windows-x64.zip'
        if metadata['windows_nvidia_bundle'] == 'Unknown':
            metadata['windows_nvidia_bundle'] = (
                f'katago-{katago_version}-cuda12.1-cudnn8.9.7-windows-x64.zip'
            )
        if metadata['windows_nvidia50_cuda_bundle'] == 'Unknown':
            metadata['windows_nvidia50_cuda_bundle'] = (
                f'katago-{katago_version}-cuda12.8-cudnn9.8.0-windows-x64.zip'
            )
        if metadata['linux_bundle'] == 'Unknown':
            metadata['linux_bundle'] = f'katago-{katago_version}-eigen-linux-x64.zip'
        if metadata['linux_opencl_bundle'] == 'Unknown':
            metadata['linux_opencl_bundle'] = f'katago-{katago_version}-opencl-linux-x64.zip'
        if metadata['linux_nvidia_bundle'] == 'Unknown':
            metadata['linux_nvidia_bundle'] = (
                f'katago-{katago_version}-cuda12.1-cudnn8.9.7-linux-x64.zip'
            )
    return metadata


def run_command(cmd: list[str]) -> str:
    result = subprocess.run(cmd, check=True, capture_output=True, text=True)
    return result.stdout


def asset_names_from_gh(repo: str, release_tag: str) -> list[str]:
    if not release_tag:
        raise SystemExit('--release-tag is required when --from-gh is used')
    payload = run_command(['gh', 'release', 'view', release_tag, '--repo', repo, '--json', 'assets'])
    data = json.loads(payload)
    return [asset['name'] for asset in data.get('assets', [])]


def asset_names_from_dir(release_dir: str, date_tag: str | None) -> list[str]:
    path = Path(release_dir)
    if not path.is_dir():
        raise SystemExit(f'Release directory not found: {path}')
    names = [item.name for item in path.iterdir() if item.is_file()]
    if date_tag:
        dated = [name for name in names if name.startswith(f'{date_tag}-')]
        if dated:
            return dated
    return names


def pick_asset(asset_names: list[str], suffix: str, date_tag: str | None) -> str | None:
    matches = [name for name in asset_names if name.endswith(suffix)]
    if date_tag:
        dated = [name for name in matches if name.startswith(f'{date_tag}-')]
        if dated:
            matches = dated
    return sorted(matches)[-1] if matches else None


def pick_assets_matching(asset_names: list[str], pattern: str, date_tag: str | None) -> list[str]:
    regex = re.compile(pattern)
    matches = [name for name in asset_names if regex.search(name)]
    if date_tag:
        dated = [name for name in matches if name.startswith(f'{date_tag}-')]
        if dated:
            matches = dated
    return sorted(matches)


def release_asset_url(repo: str, release_tag: str | None, asset_name: str) -> str | None:
    if not release_tag:
        return None
    return f'https://github.com/{repo}/releases/download/{quote(release_tag)}/{quote(asset_name)}'


def format_asset(asset_name: str | list[str] | None, repo: str, release_tag: str | None) -> str:
    if isinstance(asset_name, list):
        if not asset_name:
            return '暂未包含在本次发布中'
        return '<br>'.join(format_asset(name, repo, release_tag) for name in asset_name)
    if not asset_name:
        return '暂未包含在本次发布中'
    url = release_asset_url(repo, release_tag, asset_name)
    if not url:
        return f'`{asset_name}`'
    return f'[`{asset_name}`]({url})'


def format_asset_en(asset_name: str | list[str] | None, repo: str, release_tag: str | None) -> str:
    if isinstance(asset_name, list):
        if not asset_name:
            return 'Not included in this release'
        return '<br>'.join(format_asset_en(name, repo, release_tag) for name in asset_name)
    if not asset_name:
        return 'Not included in this release'
    url = release_asset_url(repo, release_tag, asset_name)
    if not url:
        return f'`{asset_name}`'
    return f'[`{asset_name}`]({url})'


def release_heading(release_tag: str | None) -> str:
    tag = (release_tag or '').strip()
    if not tag:
        return '# LizzieYzy Next'
    return f'# LizzieYzy Next {tag}'


def validate_release_sections(sections: list[dict[str, object]]) -> None:
    languages = tuple(str(section.get('language', '')) for section in sections)
    if languages != RELEASE_LANGUAGES:
        raise SystemExit(
            'Release note language order must stay fixed: '
            + ', '.join(RELEASE_LANGUAGES)
        )

    expected_download_rows = len(ASSET_SPECS)
    for section in sections:
        language = str(section['language'])
        missing = [key for key in SECTION_KEYS if key not in section]
        if missing:
            raise SystemExit(f'{language} release notes are missing sections: {", ".join(missing)}')

        for key in SECTION_KEYS:
            block = section[key]
            if not isinstance(block, dict) or not block.get('heading'):
                raise SystemExit(f'{language} release notes section "{key}" needs a heading')

            if key == 'download':
                rows = block.get('rows')
                if not isinstance(rows, list) or len(rows) not in (
                    expected_download_rows,
                    expected_download_rows + 1,
                ):
                    raise SystemExit(
                        f'{language} download table must contain {expected_download_rows} rows'
                        f' or {expected_download_rows + 1} rows with optional TensorRT split guidance'
                    )
                continue

            items = block.get('items')
            if not isinstance(items, list) or not items:
                raise SystemExit(f'{language} release notes section "{key}" needs bullet items')


def add_nvidia50_download_rows(
    sections: list[dict[str, object]],
    assets_cn: dict[str, str],
    assets: dict[str, str],
) -> None:
    labels_by_language = {
        '中文': (
            'Windows 64 位，RTX 50 CUDA 版，5070/5080/5090 优先，免安装',
            'Windows 64 位，RTX 50 CUDA 版，5070/5080/5090 优先，想安装',
        ),
        '繁體中文': (
            'Windows 64 位，RTX 50 CUDA 版，5070/5080/5090 優先，免安裝',
            'Windows 64 位，RTX 50 CUDA 版，5070/5080/5090 優先，想安裝',
        ),
        'English': (
            'Windows 64-bit, RTX 50 CUDA, recommended for 5070/5080/5090, no install',
            'Windows 64-bit, RTX 50 CUDA, recommended for 5070/5080/5090, installer',
        ),
        '日本語': (
            'Windows 64-bit、RTX 50 CUDA、5070/5080/5090 推奨、インストール不要',
            'Windows 64-bit、RTX 50 CUDA、5070/5080/5090 推奨、インストーラ',
        ),
        '한국어': (
            'Windows 64-bit, RTX 50 CUDA, 5070/5080/5090 권장, 무설치',
            'Windows 64-bit, RTX 50 CUDA, 5070/5080/5090 권장, 설치형',
        ),
        'ภาษาไทย': (
            'Windows 64-bit, RTX 50 CUDA, แนะนำสำหรับ 5070/5080/5090, ไม่ต้องติดตั้ง',
            'Windows 64-bit, RTX 50 CUDA, แนะนำสำหรับ 5070/5080/5090, แบบติดตั้ง',
        ),
    }
    before_note_by_language = {
        '中文': 'RTX 5070/5080/5090 用户优先下载 RTX 50 CUDA 版；RTX 20/30/40/50 用户需要 TensorRT 时可在软件内“一键设置”按需安装，GTX 10 系及更老显卡优先 CUDA/OpenCL。',
        '繁體中文': 'RTX 5070/5080/5090 使用者優先下載 RTX 50 CUDA 版；RTX 20/30/40/50 使用者需要 TensorRT 時可在軟體內「一鍵設定」按需安裝，GTX 10 系及更舊顯卡優先 CUDA/OpenCL。',
        'English': 'RTX 5070/5080/5090 users should try the RTX 50 CUDA build first; RTX 20/30/40/50 users can install TensorRT on demand from the in-app KataGo Auto Setup, while GTX 10 series and older cards should prefer CUDA/OpenCL.',
        '日本語': 'RTX 5070/5080/5090 ユーザーは RTX 50 CUDA 版を優先してください。RTX 20/30/40/50 ユーザーはアプリ内の KataGo 自動設定から TensorRT を必要時にインストールできます。GTX 10 系以前は CUDA/OpenCL を推奨します。',
        '한국어': 'RTX 5070/5080/5090 사용자는 RTX 50 CUDA 버전을 먼저 권장합니다. RTX 20/30/40/50 사용자는 앱 안의 KataGo 자동 설정에서 TensorRT를 필요할 때 설치할 수 있고, GTX 10 시리즈 및 이전 카드는 CUDA/OpenCL을 권장합니다.',
        'ภาษาไทย': 'ผู้ใช้ RTX 5070/5080/5090 ควรลอง RTX 50 CUDA ก่อน ผู้ใช้ RTX 20/30/40/50 สามารถติดตั้ง TensorRT จาก KataGo Auto Setup ในแอปเมื่อต้องการ ส่วน GTX 10 series และรุ่นเก่าควรใช้ CUDA/OpenCL',
    }
    for section in sections:
        language = str(section['language'])
        download = section['download']
        assert isinstance(download, dict)
        rows = download['rows']
        assert isinstance(rows, list)
        if any('nvidia50.cuda' in str(row[1]) for row in rows if isinstance(row, tuple)):
            continue
        localized_assets = assets_cn if language in ('中文', '繁體中文') else assets
        labels = labels_by_language.get(language, labels_by_language['English'])
        additions = [
            (labels[0], localized_assets['windows_nvidia50_cuda_portable']),
            (labels[1], localized_assets['windows_nvidia50_cuda_installer']),
        ]
        insert_at = min(6, len(rows))
        for index, row in enumerate(rows):
            if isinstance(row, tuple) and 'windows64.nvidia.installer' in str(row[1]):
                insert_at = index + 1
                break
        rows[insert_at:insert_at] = additions
        before = section['before']
        assert isinstance(before, dict)
        before_items = before['items']
        assert isinstance(before_items, list)
        note = before_note_by_language.get(language, before_note_by_language['English'])
        if note not in before_items:
            before_items.append(note)


def add_tensorrt_split_download_row(
    sections: list[dict[str, object]],
    assets_cn: dict[str, str],
    assets: dict[str, str],
    asset_map: dict[str, str | None],
) -> None:
    if not any(asset_map.get(key) for key in TENSORRT_SPLIT_ASSET_KEYS):
        return
    labels_by_language = {
        '中文': '高级可选：TensorRT 预装分卷包说明（需下载全部 .7z.00N）',
        '繁體中文': '進階可選：TensorRT 預裝分卷包說明（需下載全部 .7z.00N）',
        'English': 'Advanced optional TensorRT split package guide; download every .7z.00N part',
        '日本語': '上級者向け任意：TensorRT 分割パッケージ案内（.7z.00N を全て取得）',
        '한국어': '고급 선택: TensorRT 분할 패키지 안내(.7z.00N 전체 다운로드 필요)',
        'ภาษาไทย': 'ตัวเลือกขั้นสูง: คู่มือ TensorRT split package ต้องดาวน์โหลด .7z.00N ครบทุกไฟล์',
    }
    before_note_by_language = {
        '中文': '高级可选 TensorRT 分卷包只适合熟悉 7-Zip 的 RTX 20/30/40/50 用户；普通用户继续下载 NVIDIA/CUDA 包后在软件内一键安装，支持断点续传。分卷包必须下载全部 `.7z.00N`，只下 `.001` 没用。',
        '繁體中文': '進階可選 TensorRT 分卷包只適合熟悉 7-Zip 的 RTX 20/30/40/50 使用者；一般使用者請先下載 NVIDIA/CUDA 包，再在軟體內一鍵安裝，支援斷點續傳。分卷包必須下載全部 `.7z.00N`，只下 `.001` 沒有用。',
        'English': 'The advanced optional TensorRT split package is only for RTX 20/30/40/50 users who are comfortable with 7-Zip. Most users should keep using the normal NVIDIA/CUDA package plus the in-app resumable TensorRT installer. You must download every `.7z.00N` part; `.001` alone is useless.',
        '日本語': '上級者向けの TensorRT 分割パッケージは、7-Zip に慣れた RTX 20/30/40/50 ユーザー向けです。通常は NVIDIA/CUDA パッケージを使い、アプリ内の再開対応 TensorRT インストーラを利用してください。`.7z.00N` を全てダウンロードする必要があり、`.001` だけでは使えません。',
        '한국어': '고급 선택 TensorRT 분할 패키지는 7-Zip 에 익숙한 RTX 20/30/40/50 사용자용입니다. 대부분의 사용자는 일반 NVIDIA/CUDA 패키지와 앱 안의 이어받기 지원 TensorRT 설치를 쓰면 됩니다. `.7z.00N` 전체를 받아야 하며 `.001` 만 받으면 사용할 수 없습니다.',
        'ภาษาไทย': 'TensorRT split package แบบตัวเลือกขั้นสูงเหมาะกับผู้ใช้ RTX 20/30/40/50 ที่คุ้นกับ 7-Zip เท่านั้น ผู้ใช้ทั่วไปควรใช้ NVIDIA/CUDA package ปกติแล้วติดตั้ง TensorRT ในแอปซึ่งรองรับ resume ต้องดาวน์โหลด `.7z.00N` ครบทุกไฟล์ ไฟล์ `.001` อย่างเดียวใช้ไม่ได้',
    }
    for section in sections:
        language = str(section['language'])
        localized_assets = assets_cn if language in ('中文', '繁體中文') else assets
        download = section['download']
        assert isinstance(download, dict)
        rows = download['rows']
        assert isinstance(rows, list)
        split_assets = '<br>'.join(
            localized_assets[key]
            for key in TENSORRT_SPLIT_ASSET_KEYS
            if asset_map.get(key)
        )
        if not any('tensorrt.portable.README' in str(row[1]) for row in rows if isinstance(row, tuple)):
            insert_at = min(8, len(rows))
            rows.insert(
                insert_at,
                (labels_by_language.get(language, labels_by_language['English']), split_assets),
            )

        before = section['before']
        assert isinstance(before, dict)
        before_items = before['items']
        assert isinstance(before_items, list)
        note = before_note_by_language.get(language, before_note_by_language['English'])
        if note not in before_items:
            before_items.append(note)


def render_language_section(section: dict[str, object]) -> str:
    lines: list[str] = [f"## {section['language']}", '', str(section['intro']).strip(), '']
    for key in SECTION_KEYS:
        block = section[key]
        assert isinstance(block, dict)
        lines.append(f"### {block['heading']}")
        lines.append('')
        if key == 'download':
            headers = block['headers']
            rows = block['rows']
            assert isinstance(headers, tuple)
            assert isinstance(rows, list)
            lines.append(f"| {headers[0]} | {headers[1]} |")
            lines.append('| --- | --- |')
            for row in rows:
                assert isinstance(row, tuple)
                lines.append(f'| {row[0]} | {row[1]} |')
        else:
            items = block['items']
            assert isinstance(items, list)
            for item in items:
                lines.append(f'- {item}')
        lines.append('')
    return '\n'.join(lines).rstrip()


def standard_download_rows(labels: list[str], localized_assets: dict[str, str]) -> list[tuple[str, str]]:
    asset_order = [
        'windows_opencl_portable',
        'windows_opencl_installer',
        'windows_portable',
        'windows_installer',
        'windows_nvidia_portable',
        'windows_nvidia_installer',
        'windows_no_engine_portable',
        'windows_no_engine_installer',
        'mac_arm64',
        'mac_amd64',
        'linux64',
        'linux64_opencl',
        'linux64_nvidia',
    ]
    return list(zip(labels, (localized_assets[key] for key in asset_order)))


STANDARD_DOWNLOAD_LABELS = {
    'zh': [
        'Windows 64 位，OpenCL 版，推荐更快，免安装',
        'Windows 64 位，OpenCL 版，想安装',
        'Windows 64 位，CPU 兼容版，免安装',
        'Windows 64 位，CPU 兼容版，想安装',
        'Windows 64 位，NVIDIA 显卡，免安装',
        'Windows 64 位，NVIDIA 显卡，想安装',
        'Windows 64 位，想自己配引擎',
        'Windows 64 位，想自己配引擎，也想安装器',
        'macOS Apple Silicon',
        'macOS Intel',
        'Linux 64 位，CPU 兼容版',
        'Linux 64 位，OpenCL 版，AMD/Intel GPU',
        'Linux 64 位，NVIDIA CUDA 版',
    ],
    'zh_hant': [
        'Windows 64 位，OpenCL 版，推薦更快，免安裝',
        'Windows 64 位，OpenCL 版，想安裝',
        'Windows 64 位，CPU 相容版，免安裝',
        'Windows 64 位，CPU 相容版，想安裝',
        'Windows 64 位，NVIDIA 顯示卡，免安裝',
        'Windows 64 位，NVIDIA 顯示卡，想安裝',
        'Windows 64 位，想自己配引擎',
        'Windows 64 位，想自己配引擎，也想安裝器',
        'macOS Apple Silicon',
        'macOS Intel',
        'Linux 64 位，CPU 相容版',
        'Linux 64 位，OpenCL 版，AMD/Intel GPU',
        'Linux 64 位，NVIDIA CUDA 版',
    ],
    'en': [
        'Windows 64-bit, OpenCL, recommended and faster, no install',
        'Windows 64-bit, OpenCL, installer',
        'Windows 64-bit, CPU compatible build, no install',
        'Windows 64-bit, CPU compatible build, installer',
        'Windows 64-bit, NVIDIA GPU, no install',
        'Windows 64-bit, NVIDIA GPU, installer',
        'Windows 64-bit, configure your own engine',
        'Windows 64-bit, configure your own engine, installer',
        'macOS Apple Silicon',
        'macOS Intel',
        'Linux 64-bit, CPU compatible build',
        'Linux 64-bit, OpenCL for AMD/Intel GPU',
        'Linux 64-bit, NVIDIA CUDA',
    ],
    'ja': [
        'Windows 64-bit、OpenCL 推奨高速版、インストール不要',
        'Windows 64-bit、OpenCL 版、インストーラ',
        'Windows 64-bit、CPU 互換版、インストール不要',
        'Windows 64-bit、CPU 互換版、インストーラ',
        'Windows 64-bit、NVIDIA GPU、インストール不要',
        'Windows 64-bit、NVIDIA GPU、インストーラ',
        'Windows 64-bit、自分でエンジンを設定したい場合',
        'Windows 64-bit、自分でエンジンを設定したい場合、インストーラ',
        'macOS Apple Silicon',
        'macOS Intel',
        'Linux 64-bit、CPU 互換版',
        'Linux 64-bit、OpenCL、AMD/Intel GPU',
        'Linux 64-bit、NVIDIA CUDA',
    ],
    'ko': [
        'Windows 64-bit, OpenCL 추천 고속판, 무설치',
        'Windows 64-bit, OpenCL, 설치형',
        'Windows 64-bit, CPU 호환 빌드, 무설치',
        'Windows 64-bit, CPU 호환 빌드, 설치형',
        'Windows 64-bit, NVIDIA GPU, 무설치',
        'Windows 64-bit, NVIDIA GPU, 설치형',
        'Windows 64-bit, 직접 엔진 설정',
        'Windows 64-bit, 직접 엔진 설정, 설치형',
        'macOS Apple Silicon',
        'macOS Intel',
        'Linux 64-bit, CPU 호환 빌드',
        'Linux 64-bit, OpenCL, AMD/Intel GPU',
        'Linux 64-bit, NVIDIA CUDA',
    ],
    'th': [
        'Windows 64-bit, OpenCL, แนะนำและเร็วกว่า, ไม่ต้องติดตั้ง',
        'Windows 64-bit, OpenCL, แบบติดตั้ง',
        'Windows 64-bit, CPU compatible build, ไม่ต้องติดตั้ง',
        'Windows 64-bit, CPU compatible build, แบบติดตั้ง',
        'Windows 64-bit, การ์ดจอ NVIDIA, ไม่ต้องติดตั้ง',
        'Windows 64-bit, การ์ดจอ NVIDIA, แบบติดตั้ง',
        'Windows 64-bit, ต้องการตั้งค่า engine เอง',
        'Windows 64-bit, ต้องการตั้งค่า engine เองและอยากใช้ installer',
        'macOS Apple Silicon',
        'macOS Intel',
        'Linux 64-bit, CPU compatible build',
        'Linux 64-bit, OpenCL สำหรับ AMD/Intel GPU',
        'Linux 64-bit, NVIDIA CUDA',
    ],
}


def build_next_2026_05_17_2_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {
        key: format_asset(asset_map[key], repo, release_tag)
        for key in asset_map
    }
    assets = {
        key: format_asset_en(asset_map[key], repo, release_tag)
        for key in asset_map
    }
    katago_version = bundle['katago_version']
    model_source = bundle['model_source']
    sections: list[dict[str, object]] = [
        {
            'language': '中文',
            'intro': '这一版是“4段纪念版”。2026 年 5 月 17 日，女儿参加围棋升段赛，正式晋升围棋业余 4 段；这版用来记录这个很值得开心的日子。本次重点不再重复上一版的大功能介绍，而是把 KataGo 一键设置窗口重新整理成更清楚、更稳的 C 方案分栏界面。',
            'updates': {
                'heading': '本版主要更新',
                'items': [
                    'KataGo 一键设置改为左侧分区、右侧详情的专家模式：总览、权重、加速、测速各自独立，按钮不再堆在一个区域里。',
                    '智能测速进度集中到底部状态区显示，保留当前步骤和百分比，不再让进度文字在窗口里重复或挤出内容。',
                    '取消智能测速后会恢复按钮可用状态，用户可以继续重试、关闭窗口或调整其他设置。',
                    '本地真实启动应用检查了总览、权重、加速、测速分区，以及测速启动和取消流程。',
                    '发布页和软件内显示版本同步使用本次 release tag，并在发布页标注纪念版名称。',
                ],
            },
            'before': {
                'heading': '下载前先看这几句',
                'items': [
                    f'Windows 普通用户优先下载 {assets_cn["windows_opencl_portable"]}，这是 **OpenCL 版（推荐，免安装）**。',
                    f'如果 OpenCL 在你的电脑上不稳定，再改用 {assets_cn["windows_portable"]}。',
                    f'如果你的电脑是 **NVIDIA 显卡**，优先下载 {assets_cn["windows_nvidia_portable"]}。',
                    f'主推荐整合包已内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
                    '如果你更喜欢安装流程，再选同系列的 `installer.exe`。',
                ],
            },
            'download': {
                'heading': '下载建议',
                'headers': ('你的电脑', '直接下载这个'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['zh'], assets_cn),
            },
            'why': {
                'heading': '这一版为什么值得更新',
                'items': [
                    '一键设置的信息架构更清楚，新用户先看总览，老用户也能快速去权重、加速或测速区。',
                    '测速进度不再撑乱窗口，取消后也不会留下按钮灰掉的尴尬状态。',
                    '这是一个小而确定的 UI 质量提升版，适合替换上一版继续测试。',
                ],
            },
            'contact': {'heading': '交流', 'items': ['QQ 群：`299419120`']},
        },
        {
            'language': '繁體中文',
            'intro': '這一版是「4 段紀念版」。2026 年 5 月 17 日，女兒參加圍棋升段賽，正式晉升圍棋業餘 4 段；這版用來記錄這個很值得開心的日子。本次重點不再重複上一版的大功能介紹，而是把 KataGo 一鍵設定視窗整理成更清楚、更穩定的 C 方案分欄介面。',
            'updates': {
                'heading': '本版主要更新',
                'items': [
                    'KataGo 一鍵設定改為左側分區、右側詳情的專家模式：總覽、權重、加速、測速各自獨立，按鈕不再堆在同一區域。',
                    '智慧測速進度集中到底部狀態區顯示，保留目前步驟和百分比，不再讓進度文字在視窗裡重複或擠出內容。',
                    '取消智慧測速後會恢復按鈕可用狀態，使用者可以繼續重試、關閉視窗或調整其他設定。',
                    '本機真實啟動應用檢查了總覽、權重、加速、測速分區，以及測速啟動和取消流程。',
                    '發布頁和軟體內顯示版本同步使用本次 release tag，並在發布頁標註紀念版名稱。',
                ],
            },
            'before': {
                'heading': '下載前先看這幾句',
                'items': [
                    f'Windows 一般使用者優先下載 {assets_cn["windows_opencl_portable"]}，這是 **OpenCL 版（推薦，免安裝）**。',
                    f'如果 OpenCL 在你的電腦上不穩定，再改用 {assets_cn["windows_portable"]}。',
                    f'如果你的電腦是 **NVIDIA 顯示卡**，優先下載 {assets_cn["windows_nvidia_portable"]}。',
                    f'主推薦整合包已內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
                    '如果你更喜歡安裝流程，再選同系列的 `installer.exe`。',
                ],
            },
            'download': {
                'heading': '下載建議',
                'headers': ('你的電腦', '直接下載這個'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['zh_hant'], assets_cn),
            },
            'why': {
                'heading': '這一版為什麼值得更新',
                'items': [
                    '一鍵設定的資訊架構更清楚，新使用者先看總覽，進階使用者也能快速切到權重、加速或測速區。',
                    '測速進度不再撐亂視窗，取消後也不會留下按鈕灰掉的狀態。',
                    '這是一個小而確定的 UI 品質提升版，適合替換上一版繼續測試。',
                ],
            },
            'contact': {'heading': '交流', 'items': ['QQ 群：`299419120`']},
        },
        {
            'language': 'English',
            'intro': 'This is the “4-dan commemorative build”. On May 17, 2026, my daughter competed in a Go promotion tournament and was promoted to amateur 4-dan; this release records that happy day. Instead of repeating the previous large feature notes, it focuses on the C-style redesign of the KataGo Auto Setup dialog.',
            'updates': {
                'heading': 'Release Highlights',
                'items': [
                    'KataGo Auto Setup now uses an expert split layout: Overview, Weights, Acceleration, and Benchmark each have their own panel and actions.',
                    'Smart benchmark progress is consolidated into the bottom status area with the current step and percentage, avoiding duplicated progress text and layout overflow.',
                    'Cancelling Smart Benchmark restores idle controls so users can retry, close the dialog, or adjust other settings immediately.',
                    'A real local launch smoke test covered the Overview, Weights, Acceleration, Benchmark sections plus benchmark start and cancellation.',
                    'The in-app display version uses this release tag, and the release page carries the commemorative name.',
                ],
            },
            'before': {
                'heading': 'Read Before Downloading',
                'items': [
                    f'Most Windows users should download {assets["windows_opencl_portable"]}, the **recommended no-install OpenCL build**.',
                    f'If OpenCL is unreliable on your PC, use {assets["windows_portable"]} instead.',
                    f'If your PC has an **NVIDIA GPU**, try {assets["windows_nvidia_portable"]} first.',
                    f'The recommended bundles include KataGo `{katago_version}` and the default weight `{model_source}`.',
                    'If you prefer an installer, choose the matching `installer.exe` package.',
                ],
            },
            'download': {
                'heading': 'Download Guide',
                'headers': ('Your computer', 'Download this file'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['en'], assets),
            },
            'why': {
                'heading': 'Why This Release Is Worth Updating',
                'items': [
                    'The one-click setup flow has clearer information architecture for both first-time and advanced users.',
                    'Benchmark progress no longer disturbs the layout, and cancelling does not leave the dialog in a disabled-looking state.',
                    'This is a small but concrete UI-quality release suitable for replacing the previous build during testing.',
                ],
            },
            'contact': {'heading': 'Contact', 'items': ['QQ group: `299419120`']},
        },
        {
            'language': '日本語',
            'intro': 'このリリースは「4 段記念版」です。2026 年 5 月 17 日、娘が囲碁の昇段大会に参加し、アマ 4 段へ昇段しました。このうれしい日を記録するリリースです。前回の大きな機能説明を繰り返すのではなく、KataGo 自動設定ダイアログを C 案の分割レイアウトへ整理することに集中しました。',
            'updates': {
                'heading': '主な更新',
                'items': [
                    'KataGo 自動設定をエキスパート向けの分割レイアウトに変更しました。概要、重み、加速、ベンチマークがそれぞれ独立したパネルになります。',
                    'Smart Benchmark の進捗は下部ステータス領域に集約し、現在のステップとパーセントを表示します。重複表示やレイアウト崩れを避けました。',
                    'Smart Benchmark をキャンセルした後、ボタンが待機状態に戻り、再試行・終了・設定変更がすぐできます。',
                    'ローカルで実際にアプリを起動し、概要、重み、加速、ベンチマークの各セクションと、ベンチマーク開始・キャンセルを確認しました。',
                    'アプリ内表示バージョンは今回の release tag を使い、リリースページにも記念版名を記載します。',
                ],
            },
            'before': {
                'heading': 'ダウンロード前に',
                'items': [
                    f'多くの Windows ユーザーは {assets["windows_opencl_portable"]} を選ぶのがおすすめです。これは **推奨 OpenCL 版、インストール不要** です。',
                    f'OpenCL が不安定な場合は {assets["windows_portable"]} を使ってください。',
                    f'**NVIDIA GPU** 搭載 PC では {assets["windows_nvidia_portable"]} を優先してください。',
                    f'推奨バンドルには KataGo `{katago_version}` と既定の重み `{model_source}` が含まれています。',
                    'インストーラ形式がよい場合は、同じ系列の `installer.exe` を選んでください。',
                ],
            },
            'download': {
                'heading': 'ダウンロード案内',
                'headers': ('お使いの環境', 'ダウンロードするファイル'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['ja'], assets),
            },
            'why': {
                'heading': 'このリリースを更新する理由',
                'items': [
                    '一键設定の情報構造が分かりやすくなり、初回ユーザーも上級ユーザーも迷いにくくなりました。',
                    'ベンチマーク進捗がレイアウトを崩さず、キャンセル後も無効状態のように見えません。',
                    '前回ビルドの置き換えに適した、小さく確実な UI 品質改善版です。',
                ],
            },
            'contact': {'heading': '連絡先', 'items': ['QQ グループ: `299419120`']},
        },
        {
            'language': '한국어',
            'intro': '이번 릴리스는 “4단 기념판”입니다. 2026년 5월 17일, 딸이 바둑 승단전에 참가해 아마 4단으로 승단했습니다. 이 기쁜 날을 기록하기 위한 릴리스입니다. 이전 릴리스의 큰 기능 설명을 반복하기보다 KataGo 자동 설정 창을 C안 분할 레이아웃으로 정리하는 데 집중했습니다.',
            'updates': {
                'heading': '주요 업데이트',
                'items': [
                    'KataGo 자동 설정을 전문가형 분할 레이아웃으로 바꿨습니다. Overview, Weights, Acceleration, Benchmark 가 각각 독립 패널과 동작을 갖습니다.',
                    'Smart Benchmark 진행률은 하단 상태 영역에 모아 현재 단계와 퍼센트를 보여 주며, 진행 문구 중복과 레이아웃 밀림을 줄였습니다.',
                    'Smart Benchmark 를 취소하면 대기 상태 버튼이 복구되어 바로 재시도, 닫기, 다른 설정 변경을 할 수 있습니다.',
                    '로컬에서 실제 앱을 실행해 Overview, Weights, Acceleration, Benchmark 섹션과 benchmark 시작/취소 흐름을 확인했습니다.',
                    '앱 내 표시 버전은 이번 release tag 를 사용하고, 릴리스 페이지에는 기념판 이름을 표시합니다.',
                ],
            },
            'before': {
                'heading': '다운로드 전 확인',
                'items': [
                    f'대부분의 Windows 사용자는 {assets["windows_opencl_portable"]} 를 먼저 받으면 됩니다. 이는 **추천 OpenCL 무설치 빌드** 입니다.',
                    f'OpenCL 이 PC에서 불안정하면 {assets["windows_portable"]} 를 대신 사용하세요.',
                    f'**NVIDIA GPU** 가 있다면 {assets["windows_nvidia_portable"]} 를 우선 사용해 보세요.',
                    f'추천 번들에는 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 가 포함되어 있습니다.',
                    '설치형 흐름을 원한다면 같은 계열의 `installer.exe` 를 고르세요.',
                ],
            },
            'download': {
                'heading': '다운로드 안내',
                'headers': ('내 컴퓨터', '다운로드할 파일'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['ko'], assets),
            },
            'why': {
                'heading': '이번 릴리스를 업데이트할 이유',
                'items': [
                    '원클릭 설정의 정보 구조가 더 명확해져 처음 쓰는 사용자와 고급 사용자 모두 이동하기 쉬워졌습니다.',
                    '벤치마크 진행률이 창 레이아웃을 밀어내지 않고, 취소 후에도 버튼이 비활성처럼 남지 않습니다.',
                    '이전 빌드를 대체해 테스트하기 좋은 작지만 확실한 UI 품질 개선판입니다.',
                ],
            },
            'contact': {'heading': '연락처', 'items': ['QQ 그룹: `299419120`']},
        },
        {
            'language': 'ภาษาไทย',
            'intro': 'รีลีสนี้คือ “เวอร์ชันที่ระลึก 4 ดั้ง” วันที่ 17 พฤษภาคม 2026 ลูกสาวเข้าร่วมการแข่งขันเลื่อนระดับหมากล้อมและเลื่อนเป็นสมัครเล่น 4 ดั้งอย่างเป็นทางการ จึงใช้รีลีสนี้บันทึกวันที่น่าดีใจนี้ไว้ รอบนี้ไม่ซ้ำรายละเอียดใหญ่จากเวอร์ชันก่อน แต่โฟกัสที่การจัดหน้าต่าง KataGo Auto Setup ใหม่ตามแบบ C ให้ชัดและนิ่งขึ้น',
            'updates': {
                'heading': 'ไฮไลต์ของเวอร์ชันนี้',
                'items': [
                    'KataGo Auto Setup เปลี่ยนเป็น layout แบบ expert แยกซ้าย/ขวา: Overview, Weights, Acceleration และ Benchmark มี panel และปุ่มของตัวเอง',
                    'Smart Benchmark progress ถูกรวมไว้ที่แถบสถานะด้านล่าง แสดงขั้นตอนปัจจุบันและเปอร์เซ็นต์ ลดข้อความซ้ำและปัญหาดัน layout',
                    'เมื่อยกเลิก Smart Benchmark ปุ่มต่าง ๆ จะกลับสู่สถานะพร้อมใช้งาน ผู้ใช้ retry, ปิดหน้าต่าง หรือปรับค่าอื่นต่อได้ทันที',
                    'ทดสอบเปิดแอปจริงบนเครื่อง local แล้ว ตรวจทั้ง Overview, Weights, Acceleration, Benchmark รวมถึงเริ่มและยกเลิก benchmark',
                    'เวอร์ชันที่แสดงในแอปใช้ release tag รอบนี้ และหน้า release ระบุชื่อเวอร์ชันที่ระลึก',
                ],
            },
            'before': {
                'heading': 'ก่อนดาวน์โหลด ดูตรงนี้ก่อน',
                'items': [
                    f'ผู้ใช้ Windows ส่วนใหญ่แนะนำให้ดาวน์โหลด {assets["windows_opencl_portable"]} ซึ่งเป็น **OpenCL รุ่นแนะนำ แบบไม่ต้องติดตั้ง**',
                    f'ถ้า OpenCL ไม่เสถียรบนเครื่องของคุณ ให้ใช้ {assets["windows_portable"]} แทน',
                    f'ถ้ามี **NVIDIA GPU** แนะนำให้ลอง {assets["windows_nvidia_portable"]} ก่อน',
                    f'แพ็กเกจหลักมี KataGo `{katago_version}` และน้ำหนักเริ่มต้น `{model_source}` มาให้แล้ว',
                    'ถ้าต้องการแบบติดตั้ง ให้เลือกไฟล์ `installer.exe` ในชุดเดียวกัน',
                ],
            },
            'download': {
                'heading': 'แนะนำการดาวน์โหลด',
                'headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['th'], assets),
            },
            'why': {
                'heading': 'ทำไมเวอร์ชันนี้ควรอัปเดต',
                'items': [
                    'โครงสร้างข้อมูลของ one-click setup ชัดขึ้น ผู้ใช้ใหม่ดู Overview ก่อน ส่วนผู้ใช้ขั้นสูงไปที่ Weights, Acceleration หรือ Benchmark ได้เร็ว',
                    'progress ของ benchmark ไม่ดัน layout และหลังยกเลิกจะไม่เหลือปุ่มที่ดูเหมือนถูก disable ค้าง',
                    'เป็นรีลีสปรับคุณภาพ UI ขนาดเล็กแต่ชัดเจน เหมาะสำหรับแทนที่ build ก่อนหน้าเพื่อทดสอบต่อ',
                ],
            },
            'contact': {'heading': 'ติดต่อ', 'items': ['QQ group: `299419120`']},
        },
    ]

    add_nvidia50_download_rows(sections, assets_cn, assets)
    add_tensorrt_split_download_row(sections, assets_cn, assets, asset_map)
    validate_release_sections(sections)
    heading = f'# LizzieYzy Next {release_tag} 4段纪念版' if release_tag else '# LizzieYzy Next 4段纪念版'
    return heading + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_05_03_1_notes(
    asset_map: dict[str, str | None],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {
        key: format_asset(asset_map[key], repo, release_tag)
        for key in asset_map
    }
    assets = {
        key: format_asset_en(asset_map[key], repo, release_tag)
        for key in asset_map
    }
    sections: list[dict[str, object]] = [
        {
            'language': '中文',
            'intro': (
                '这一版集中修复最近几轮真机测试反馈的界面、同步和发版链路问题。'
                '重点是把“评论 / 问题手”侧栏重新排顺，把弈客同步后的引擎状态补齐，'
                '并把公开版本号改成更清楚的 `next-日期.编号`。'
            ),
            'updates': {
                'heading': '本版主要更新',
                'items': [
                    '优化“评论 / 问题手”侧栏：顶部区域和正文不再有突兀色差，黑棋/白棋筛选放到“问题手”同一行右侧，切换点击区域更稳定。',
                    '修复“问题手”列表卡片过宽和底部无意义横向滚动条，列表现在会按侧栏宽度自适应。',
                    '问题手进度改成“评估中 / 已评估 / 评估完成”，分母按实际可评估的位置计算，避免 230 手棋最终看起来停在 229/230 的误解。',
                    '修复弈客直播棋谱同步后主引擎没有同步到当前棋谱历史的问题，载入弈客棋局后分析状态更一致。',
                    '调整评论区、问题手区、胜率图相关背景绘制，让透明/半透明背景在不同主题下更自然。',
                    '公开 release tag 改成 `next-YYYY-MM-DD.N`，从本版起不再使用 `1.0.0-` 前缀。',
                    '修复发版链路：macOS DMG 签名/公证后重打包增加卸载重试；Windows workflow 优先上传正式 release 资产，非关键 artifact/升级 smoke 不再阻塞下载。',
                ],
            },
            'before': {
                'heading': '下载前先看这几句',
                'items': [
                    f'Windows 普通用户仍然优先下载 {assets_cn["windows_opencl_portable"]}，这是 **OpenCL 版（推荐，免安装）**。',
                    f'如果 OpenCL 在你的电脑上不稳定，再改用 {assets_cn["windows_portable"]}。',
                    f'如果你的电脑是 **NVIDIA 显卡**，优先下载 {assets_cn["windows_nvidia_portable"]}。',
                    '如果你更喜欢安装流程，再选同系列的 `installer.exe`。',
                    '这版主要修 UI 与同步体验；已经在本地通过 `mvn test` 和 `mvn -DskipTests package`。',
                    'Windows / Linux / macOS 打包工作流已完成关键校验并上传本版资产。',
                ],
            },
            'download': {
                'heading': '下载建议',
                'headers': ('你的电脑', '直接下载这个'),
                'rows': [
                    ('Windows 64 位，OpenCL 版，推荐更快，免安装', assets_cn['windows_opencl_portable']),
                    ('Windows 64 位，OpenCL 版，想安装', assets_cn['windows_opencl_installer']),
                    ('Windows 64 位，CPU 兼容版，免安装', assets_cn['windows_portable']),
                    ('Windows 64 位，CPU 兼容版，想安装', assets_cn['windows_installer']),
                    ('Windows 64 位，NVIDIA 显卡，免安装', assets_cn['windows_nvidia_portable']),
                    ('Windows 64 位，NVIDIA 显卡，想安装', assets_cn['windows_nvidia_installer']),
                    ('Windows 64 位，想自己配引擎', assets_cn['windows_no_engine_portable']),
                    ('Windows 64 位，想自己配引擎，也想安装器', assets_cn['windows_no_engine_installer']),
                    ('macOS Apple Silicon', assets_cn['mac_arm64']),
                    ('macOS Intel', assets_cn['mac_amd64']),
                    ('Linux 64 位，CPU 兼容版', assets_cn['linux64']),
                    ('Linux 64 位，OpenCL 版，AMD/Intel GPU', assets_cn['linux64_opencl']),
                    ('Linux 64 位，NVIDIA CUDA 版', assets_cn['linux64_nvidia']),
                ],
            },
            'why': {
                'heading': '这一版为什么值得更新',
                'items': [
                    '评论和问题手区域占位、色差、点击范围、横向滚动条这些细节都重新收拾过，日常复盘更顺眼。',
                    '问题手分析完成时会显示真正完成状态，不再让人误以为还差最后一手。',
                    '弈客直播载入后主引擎能跟上当前棋谱历史，后续分析更可靠。',
                    '公开版本号不再长期停在 `1.0.0`，以后看 tag 就能知道日期和编号。',
                    'macOS 和 Windows 发版链路针对这次暴露出的阻塞点做了加固。',
                ],
            },
            'contact': {'heading': '交流', 'items': ['QQ 群：`299419120`']},
        },
        {
            'language': '繁體中文',
            'intro': (
                '這一版集中修復最近幾輪真機測試回饋的介面、同步與發版流程問題。'
                '重點是重新整理「評論 / 問題手」側欄，補齊弈客同步後的引擎狀態，'
                '並把公開版本號改成更清楚的 `next-日期.編號`。'
            ),
            'updates': {
                'heading': '本版主要更新',
                'items': [
                    '優化「評論 / 問題手」側欄：頂部區域與正文不再有突兀色差，黑棋/白棋篩選放到「問題手」同一行右側，切換點擊區域更穩定。',
                    '修復「問題手」列表卡片過寬和底部無意義橫向捲軸，列表現在會依側欄寬度自適應。',
                    '問題手進度改成「評估中 / 已評估 / 評估完成」，分母按實際可評估的位置計算，避免 230 手棋最終看起來停在 229/230 的誤解。',
                    '修復弈客直播棋譜同步後主引擎沒有同步到目前棋譜歷史的問題，載入弈客棋局後分析狀態更一致。',
                    '調整評論區、問題手區、勝率圖相關背景繪製，讓透明/半透明背景在不同主題下更自然。',
                    '公開 release tag 改成 `next-YYYY-MM-DD.N`，從本版起不再使用 `1.0.0-` 前綴。',
                    '修復發版流程：macOS DMG 簽名/公證後重打包增加卸載重試；Windows workflow 優先上傳正式 release 資產，非關鍵 artifact/升級 smoke 不再阻塞下載。',
                ],
            },
            'before': {
                'heading': '下載前先看這幾句',
                'items': [
                    f'Windows 一般使用者仍然優先下載 {assets_cn["windows_opencl_portable"]}，這是 **OpenCL 版（推薦，免安裝）**。',
                    f'如果 OpenCL 在你的電腦上不穩定，再改用 {assets_cn["windows_portable"]}。',
                    f'如果你的電腦是 **NVIDIA 顯示卡**，優先下載 {assets_cn["windows_nvidia_portable"]}。',
                    '如果你更喜歡安裝流程，再選同系列的 `installer.exe`。',
                    '這版主要修 UI 與同步體驗；已經在本地通過 `mvn test` 和 `mvn -DskipTests package`。',
                    'Windows / Linux / macOS 打包 workflow 已完成關鍵校驗並上傳本版資產。',
                ],
            },
            'download': {
                'heading': '下載建議',
                'headers': ('你的電腦', '直接下載這個'),
                'rows': [
                    ('Windows 64 位，OpenCL 版，推薦更快，免安裝', assets_cn['windows_opencl_portable']),
                    ('Windows 64 位，OpenCL 版，想安裝', assets_cn['windows_opencl_installer']),
                    ('Windows 64 位，CPU 相容版，免安裝', assets_cn['windows_portable']),
                    ('Windows 64 位，CPU 相容版，想安裝', assets_cn['windows_installer']),
                    ('Windows 64 位，NVIDIA 顯示卡，免安裝', assets_cn['windows_nvidia_portable']),
                    ('Windows 64 位，NVIDIA 顯示卡，想安裝', assets_cn['windows_nvidia_installer']),
                    ('Windows 64 位，想自己配引擎', assets_cn['windows_no_engine_portable']),
                    ('Windows 64 位，想自己配引擎，也想安裝器', assets_cn['windows_no_engine_installer']),
                    ('macOS Apple Silicon', assets_cn['mac_arm64']),
                    ('macOS Intel', assets_cn['mac_amd64']),
                    ('Linux 64 位，CPU 相容版', assets_cn['linux64']),
                    ('Linux 64 位，OpenCL 版，AMD/Intel GPU', assets_cn['linux64_opencl']),
                    ('Linux 64 位，NVIDIA CUDA 版', assets_cn['linux64_nvidia']),
                ],
            },
            'why': {
                'heading': '這一版為什麼值得更新',
                'items': [
                    '評論和問題手區域的占位、色差、點擊範圍、橫向捲軸等細節都重新整理過，日常復盤更順眼。',
                    '問題手分析完成時會顯示真正完成狀態，不再讓人誤以為還差最後一手。',
                    '弈客直播載入後主引擎能跟上目前棋譜歷史，後續分析更可靠。',
                    '公開版本號不再長期停在 `1.0.0`，之後看 tag 就能知道日期和編號。',
                    'macOS 和 Windows 發版流程針對這次暴露出的阻塞點做了加固。',
                ],
            },
            'contact': {'heading': '交流', 'items': ['QQ 群：`299419120`']},
        },
        {
            'language': 'English',
            'intro': (
                'This build focuses on the UI, sync, and release-pipeline issues found during recent real-machine testing. '
                'It cleans up the Comments / Problem Moves sidebar, keeps the engine in sync after Yike live-game loading, '
                'and switches public release tags to the clearer `next-date.serial` format.'
            ),
            'updates': {
                'heading': 'Release Highlights',
                'items': [
                    'Improved the Comments / Problem Moves sidebar: the header and content no longer have a harsh color mismatch, Black/White filters sit on the Problem Moves row, and tab hit areas are more reliable.',
                    'Fixed overly wide problem-move cards and the meaningless horizontal scrollbar; the list now adapts to the sidebar width.',
                    'Problem-move progress now uses In Progress / Evaluated / Complete states, and the denominator counts only positions that can actually be evaluated.',
                    'Fixed Yike live-game sync so the primary engine is resent the current board history after a game is loaded.',
                    'Adjusted background painting for comments, problem moves, and the winrate graph so translucent themes look cleaner.',
                    'Public release tags now use `next-YYYY-MM-DD.N`; the public `1.0.0-` prefix is no longer used.',
                    'Hardened release automation: macOS DMG signing now retries detach before rebuilding, and Windows uploads release assets before non-critical artifact and upgrade-smoke steps.',
                ],
            },
            'before': {
                'heading': 'Read Before Downloading',
                'items': [
                    f'Most Windows users should still download {assets["windows_opencl_portable"]}, the **recommended no-install OpenCL build**.',
                    f'If OpenCL is unstable on your PC, use {assets["windows_portable"]} instead.',
                    f'If your PC has an **NVIDIA GPU**, try {assets["windows_nvidia_portable"]} first.',
                    'If you prefer an installer workflow, choose the matching `installer.exe` package.',
                    'This release mainly improves UI and sync behavior; local `mvn test` and `mvn -DskipTests package` both passed.',
                    'Windows, Linux, and macOS packaging workflows completed their key validation steps and uploaded the release assets.',
                ],
            },
            'download': {
                'heading': 'Download Guide',
                'headers': ('Your computer', 'Download this file'),
                'rows': [
                    ('Windows 64-bit, OpenCL, recommended and faster, no install', assets['windows_opencl_portable']),
                    ('Windows 64-bit, OpenCL, installer', assets['windows_opencl_installer']),
                    ('Windows 64-bit, CPU compatible build, no install', assets['windows_portable']),
                    ('Windows 64-bit, CPU compatible build, installer', assets['windows_installer']),
                    ('Windows 64-bit, NVIDIA GPU, no install', assets['windows_nvidia_portable']),
                    ('Windows 64-bit, NVIDIA GPU, installer', assets['windows_nvidia_installer']),
                    ('Windows 64-bit, configure your own engine', assets['windows_no_engine_portable']),
                    ('Windows 64-bit, configure your own engine, installer', assets['windows_no_engine_installer']),
                    ('macOS Apple Silicon', assets['mac_arm64']),
                    ('macOS Intel', assets['mac_amd64']),
                    ('Linux 64-bit, CPU compatible build', assets['linux64']),
                    ('Linux 64-bit, OpenCL for AMD/Intel GPU', assets['linux64_opencl']),
                    ('Linux 64-bit, NVIDIA CUDA', assets['linux64_nvidia']),
                ],
            },
            'why': {
                'heading': 'Why This Release Is Worth Updating',
                'items': [
                    'The comments and problem-move panel now has cleaner spacing, colors, hit areas, and scrolling behavior for daily review work.',
                    'Problem-move analysis now ends with a real complete state instead of looking stuck on the last move.',
                    'After loading a Yike live game, the primary engine follows the current game history more reliably.',
                    'Public versions are no longer stuck behind `1.0.0`; the tag now tells you the release date and serial directly.',
                    'macOS and Windows release workflows are more resilient against the blockers exposed by this release.',
                ],
            },
            'contact': {'heading': 'Contact', 'items': ['QQ group: `299419120`']},
        },
        {
            'language': '日本語',
            'intro': (
                'このビルドは、直近の実機テストで見つかった UI、同期、リリース工程の問題修正に集中しています。'
                '「コメント / 問題手」サイドバーを整理し、弈客ライブ棋譜読み込み後のエンジン同期を補強し、'
                '公開 release tag を分かりやすい `next-日付.番号` 形式へ変更しました。'
            ),
            'updates': {
                'heading': '主な更新',
                'items': [
                    '「コメント / 問題手」サイドバーを改善しました。ヘッダーと本文の不自然な色差をなくし、黒/白フィルタを問題手と同じ行に置き、タブのクリック範囲も安定させました。',
                    '問題手カードが広すぎる問題と意味のない横スクロールバーを修正し、リストがサイドバー幅に合わせて表示されるようにしました。',
                    '問題手の進捗表示を「評価中 / 評価済み / 評価完了」に変更し、分母は実際に評価可能な局面だけを数えるようにしました。',
                    '弈客ライブ棋譜を読み込んだ後、現在の棋譜履歴を主エンジンへ再送するように修正しました。',
                    'コメント、問題手、勝率グラフの背景描画を調整し、半透明テーマでもより自然に見えるようにしました。',
                    '公開 release tag は `next-YYYY-MM-DD.N` 形式になり、公開用の `1.0.0-` 接頭辞は使わなくなりました。',
                    'リリース自動化を強化しました。macOS DMG 署名後の detach を再試行し、Windows は非重要 artifact / upgrade smoke より先に release asset をアップロードします。',
                ],
            },
            'before': {
                'heading': 'ダウンロード前に',
                'items': [
                    f'多くの Windows ユーザーは {assets["windows_opencl_portable"]} を選ぶのがおすすめです。これは **推奨 OpenCL 版、インストール不要** です。',
                    f'OpenCL が不安定な場合は {assets["windows_portable"]} を使ってください。',
                    f'**NVIDIA GPU** 搭載 PC では {assets["windows_nvidia_portable"]} を優先してください。',
                    'インストーラ形式がよい場合は、同じ系列の `installer.exe` を選んでください。',
                    'このリリースは主に UI と同期体験の改善です。ローカルの `mvn test` と `mvn -DskipTests package` はどちらも通過しました。',
                    'Windows / Linux / macOS のパッケージ workflow は主要な検証を終え、このリリースの asset をアップロード済みです。',
                ],
            },
            'download': {
                'heading': 'ダウンロード案内',
                'headers': ('お使いの環境', 'ダウンロードするファイル'),
                'rows': [
                    ('Windows 64-bit、OpenCL 推奨高速版、インストール不要', assets['windows_opencl_portable']),
                    ('Windows 64-bit、OpenCL 版、インストーラ', assets['windows_opencl_installer']),
                    ('Windows 64-bit、CPU 互換版、インストール不要', assets['windows_portable']),
                    ('Windows 64-bit、CPU 互換版、インストーラ', assets['windows_installer']),
                    ('Windows 64-bit、NVIDIA GPU、インストール不要', assets['windows_nvidia_portable']),
                    ('Windows 64-bit、NVIDIA GPU、インストーラ', assets['windows_nvidia_installer']),
                    ('Windows 64-bit、自分でエンジンを設定したい場合', assets['windows_no_engine_portable']),
                    ('Windows 64-bit、自分でエンジンを設定したい場合、インストーラ', assets['windows_no_engine_installer']),
                    ('macOS Apple Silicon', assets['mac_arm64']),
                    ('macOS Intel', assets['mac_amd64']),
                    ('Linux 64-bit、CPU 互換版', assets['linux64']),
                    ('Linux 64-bit、OpenCL、AMD/Intel GPU', assets['linux64_opencl']),
                    ('Linux 64-bit、NVIDIA CUDA', assets['linux64_nvidia']),
                ],
            },
            'why': {
                'heading': 'このリリースを更新する理由',
                'items': [
                    'コメントと問題手パネルの余白、色、クリック範囲、スクロール挙動が整理され、日常の復盤で見やすくなりました。',
                    '問題手分析の完了時に本当に完了状態が表示され、最後の一手で止まっているように見えなくなりました。',
                    '弈客ライブ棋譜の読み込み後、主エンジンが現在の棋譜履歴により確実に追従します。',
                    '公開バージョンは `1.0.0` に見え続ける状態ではなくなり、tag だけで日付と番号が分かります。',
                    'macOS と Windows のリリース工程は、今回見つかった阻害要因に対してより強くなりました。',
                ],
            },
            'contact': {'heading': '連絡先', 'items': ['QQ グループ: `299419120`']},
        },
        {
            'language': '한국어',
            'intro': (
                '이번 빌드는 최근 실제 PC 테스트에서 확인된 UI, 동기화, 릴리스 파이프라인 문제를 고치는 데 집중했습니다. '
                'Comments / Problem Moves 사이드바를 정리하고, Yike 라이브 기보 로딩 후 엔진 동기화를 보강했으며, '
                '공개 release tag 를 더 명확한 `next-date.serial` 형식으로 바꿨습니다.'
            ),
            'updates': {
                'heading': '주요 업데이트',
                'items': [
                    'Comments / Problem Moves 사이드바를 개선했습니다. 헤더와 본문 사이의 어색한 색 차이를 줄이고, 흑/백 필터를 Problem Moves 행 오른쪽에 배치했으며, 탭 클릭 영역도 더 안정적으로 만들었습니다.',
                    '문제수 카드가 지나치게 넓고 의미 없는 가로 스크롤바가 생기던 문제를 고쳐, 목록이 사이드바 폭에 맞게 표시됩니다.',
                    '문제수 진행 표시는 In Progress / Evaluated / Complete 상태를 사용하며, 분모는 실제로 평가 가능한 위치만 계산합니다.',
                    'Yike 라이브 기보를 불러온 뒤 현재 기보 이력을 주 엔진에 다시 보내도록 수정했습니다.',
                    '댓글, 문제수, 승률 그래프의 배경 그리기를 조정해 반투명 테마에서도 더 자연스럽게 보이도록 했습니다.',
                    '공개 release tag 는 이제 `next-YYYY-MM-DD.N` 형식을 사용하며, 공개용 `1.0.0-` 접두사는 더 이상 쓰지 않습니다.',
                    '릴리스 자동화를 보강했습니다. macOS DMG 서명 후 detach 를 재시도하고, Windows 는 비핵심 artifact / upgrade smoke 보다 release asset 업로드를 먼저 수행합니다.',
                ],
            },
            'before': {
                'heading': '다운로드 전 확인',
                'items': [
                    f'대부분의 Windows 사용자는 {assets["windows_opencl_portable"]} 를 먼저 받으면 됩니다. 이는 **추천 OpenCL 무설치 빌드** 입니다.',
                    f'OpenCL 이 PC에서 불안정하면 {assets["windows_portable"]} 를 대신 사용하세요.',
                    f'**NVIDIA GPU** 가 있다면 {assets["windows_nvidia_portable"]} 를 우선 사용해 보세요.',
                    '설치형 흐름을 원한다면 같은 계열의 `installer.exe` 를 고르세요.',
                    '이번 릴리스는 주로 UI 와 동기화 경험을 개선합니다. 로컬 `mvn test` 와 `mvn -DskipTests package` 는 모두 통과했습니다.',
                    'Windows / Linux / macOS 패키징 workflow 는 주요 검증을 완료하고 이번 릴리스 asset 을 업로드했습니다.',
                ],
            },
            'download': {
                'heading': '다운로드 안내',
                'headers': ('내 컴퓨터', '다운로드할 파일'),
                'rows': [
                    ('Windows 64-bit, OpenCL 추천 고속판, 무설치', assets['windows_opencl_portable']),
                    ('Windows 64-bit, OpenCL, 설치형', assets['windows_opencl_installer']),
                    ('Windows 64-bit, CPU 호환 빌드, 무설치', assets['windows_portable']),
                    ('Windows 64-bit, CPU 호환 빌드, 설치형', assets['windows_installer']),
                    ('Windows 64-bit, NVIDIA GPU, 무설치', assets['windows_nvidia_portable']),
                    ('Windows 64-bit, NVIDIA GPU, 설치형', assets['windows_nvidia_installer']),
                    ('Windows 64-bit, 직접 엔진 설정', assets['windows_no_engine_portable']),
                    ('Windows 64-bit, 직접 엔진 설정, 설치형', assets['windows_no_engine_installer']),
                    ('macOS Apple Silicon', assets['mac_arm64']),
                    ('macOS Intel', assets['mac_amd64']),
                    ('Linux 64-bit, CPU 호환 빌드', assets['linux64']),
                    ('Linux 64-bit, OpenCL, AMD/Intel GPU', assets['linux64_opencl']),
                    ('Linux 64-bit, NVIDIA CUDA', assets['linux64_nvidia']),
                ],
            },
            'why': {
                'heading': '이번 릴리스를 업데이트할 이유',
                'items': [
                    '댓글과 문제수 패널의 여백, 색상, 클릭 영역, 스크롤 동작을 정리해 일상 복기가 더 편해졌습니다.',
                    '문제수 분석 완료 시 실제 완료 상태가 표시되어 마지막 수에서 멈춘 것처럼 보이지 않습니다.',
                    'Yike 라이브 기보를 불러온 뒤 주 엔진이 현재 기보 이력을 더 안정적으로 따라갑니다.',
                    '공개 버전이 더 이상 `1.0.0` 에 묶여 보이지 않으며, tag 만 봐도 날짜와 번호를 알 수 있습니다.',
                    'macOS 와 Windows 릴리스 workflow 는 이번에 드러난 차단 지점에 더 강해졌습니다.',
                ],
            },
            'contact': {'heading': '연락처', 'items': ['QQ 그룹: `299419120`']},
        },
        {
            'language': 'ภาษาไทย',
            'intro': (
                'บิลด์นี้เน้นแก้ปัญหา UI, การซิงก์ และขั้นตอน release ที่พบจากการทดสอบบนเครื่องจริงรอบล่าสุด '
                'โดยจัดระเบียบแถบ Comments / Problem Moves, ทำให้ engine ตามประวัติหมากหลังโหลดเกม Yike live ได้ถูกต้องขึ้น, '
                'และเปลี่ยน release tag สาธารณะเป็นรูปแบบ `next-date.serial` ที่อ่านง่ายกว่าเดิม'
            ),
            'updates': {
                'heading': 'ไฮไลต์ของเวอร์ชันนี้',
                'items': [
                    'ปรับปรุงแถบ Comments / Problem Moves: ลดสีที่ตัดกันเกินไประหว่างหัวแถบกับเนื้อหา, ย้ายตัวกรอง Black/White ไปอยู่แถวเดียวกับ Problem Moves, และทำให้พื้นที่กดแท็บเสถียรขึ้น',
                    'แก้การ์ด problem move ที่กว้างเกินไปและ scrollbar แนวนอนที่ไม่มีประโยชน์ ตอนนี้รายการจะปรับตามความกว้างของ sidebar',
                    'สถานะ progress ของ problem move เปลี่ยนเป็น In Progress / Evaluated / Complete และตัวหารจะนับเฉพาะตำแหน่งที่ประเมินได้จริง',
                    'แก้ Yike live-game sync ให้ส่งประวัติกระดานปัจจุบันกลับไปยัง primary engine หลังโหลดเกม',
                    'ปรับการวาดพื้นหลังของ comments, problem moves และ winrate graph ให้ธีมโปร่งแสงดูเป็นธรรมชาติมากขึ้น',
                    'release tag สาธารณะเปลี่ยนเป็น `next-YYYY-MM-DD.N` และไม่ใช้ prefix `1.0.0-` สำหรับผู้ใช้แล้ว',
                    'ปรับ release automation: macOS DMG signing จะ retry detach ก่อน rebuild และ Windows จะอัปโหลด release assets ก่อนขั้นตอน artifact / upgrade smoke ที่ไม่ใช่ critical path',
                ],
            },
            'before': {
                'heading': 'ก่อนดาวน์โหลด ดูตรงนี้ก่อน',
                'items': [
                    f'ผู้ใช้ Windows ส่วนใหญ่ยังแนะนำให้ดาวน์โหลด {assets["windows_opencl_portable"]} ซึ่งเป็น **OpenCL รุ่นแนะนำ แบบไม่ต้องติดตั้ง**',
                    f'ถ้า OpenCL ไม่เสถียรบนเครื่องของคุณ ให้ใช้ {assets["windows_portable"]} แทน',
                    f'ถ้ามี **NVIDIA GPU** แนะนำให้ลอง {assets["windows_nvidia_portable"]} ก่อน',
                    'ถ้าต้องการแบบติดตั้ง ให้เลือกไฟล์ `installer.exe` ในชุดเดียวกัน',
                    'รีลีสนี้เน้นปรับ UI และประสบการณ์ sync โดย `mvn test` และ `mvn -DskipTests package` ผ่านแล้วในเครื่อง local',
                    'Windows / Linux / macOS packaging workflows ผ่าน validation สำคัญและอัปโหลด assets ของรีลีสนี้แล้ว',
                ],
            },
            'download': {
                'heading': 'แนะนำการดาวน์โหลด',
                'headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
                'rows': [
                    ('Windows 64-bit, OpenCL, แนะนำและเร็วกว่า, ไม่ต้องติดตั้ง', assets['windows_opencl_portable']),
                    ('Windows 64-bit, OpenCL, แบบติดตั้ง', assets['windows_opencl_installer']),
                    ('Windows 64-bit, CPU compatible build, ไม่ต้องติดตั้ง', assets['windows_portable']),
                    ('Windows 64-bit, CPU compatible build, แบบติดตั้ง', assets['windows_installer']),
                    ('Windows 64-bit, การ์ดจอ NVIDIA, ไม่ต้องติดตั้ง', assets['windows_nvidia_portable']),
                    ('Windows 64-bit, การ์ดจอ NVIDIA, แบบติดตั้ง', assets['windows_nvidia_installer']),
                    ('Windows 64-bit, ต้องการตั้งค่า engine เอง', assets['windows_no_engine_portable']),
                    ('Windows 64-bit, ต้องการตั้งค่า engine เองและอยากใช้ installer', assets['windows_no_engine_installer']),
                    ('macOS Apple Silicon', assets['mac_arm64']),
                    ('macOS Intel', assets['mac_amd64']),
                    ('Linux 64-bit, CPU compatible build', assets['linux64']),
                    ('Linux 64-bit, OpenCL สำหรับ AMD/Intel GPU', assets['linux64_opencl']),
                    ('Linux 64-bit, NVIDIA CUDA', assets['linux64_nvidia']),
                ],
            },
            'why': {
                'heading': 'ทำไมเวอร์ชันนี้ควรอัปเดต',
                'items': [
                    'พื้นที่ comments และ problem moves ถูกปรับทั้ง spacing, สี, hit area และ scrolling ทำให้ใช้งานตอน review เกมได้สบายขึ้น',
                    'เมื่อวิเคราะห์ problem moves จบแล้วจะแสดงสถานะเสร็จจริง ไม่ดูเหมือนค้างที่หมากสุดท้าย',
                    'หลังโหลดเกม Yike live primary engine จะตามประวัติหมากปัจจุบันได้เชื่อถือมากขึ้น',
                    'เวอร์ชันสาธารณะไม่ดูเหมือนติดอยู่ที่ `1.0.0` อีกต่อไป ดู tag ก็รู้วันที่และหมายเลข release ได้ทันที',
                    'workflow release ของ macOS และ Windows ถูกเสริมให้ทนต่อจุดที่เคยบล็อกในรอบนี้มากขึ้น',
                ],
            },
            'contact': {'heading': 'ติดต่อ', 'items': ['QQ group: `299419120`']},
        },
    ]

    add_nvidia50_download_rows(sections, assets_cn, assets)
    add_tensorrt_split_download_row(sections, assets_cn, assets, asset_map)
    validate_release_sections(sections)

    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_05_04_1_notes(
    asset_map: dict[str, str | None],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {
        key: format_asset(asset_map[key], repo, release_tag)
        for key in asset_map
    }
    assets = {
        key: format_asset_en(asset_map[key], repo, release_tag)
        for key in asset_map
    }
    asset_order = [
        'windows_opencl_portable',
        'windows_opencl_installer',
        'windows_portable',
        'windows_installer',
        'windows_nvidia_portable',
        'windows_nvidia_installer',
        'windows_no_engine_portable',
        'windows_no_engine_installer',
        'mac_arm64',
        'mac_amd64',
        'linux64',
        'linux64_opencl',
        'linux64_nvidia',
    ]

    def download_rows(labels: list[str], localized_assets: dict[str, str]) -> list[tuple[str, str]]:
        return list(zip(labels, (localized_assets[key] for key in asset_order)))

    shared_download_labels = {
        'zh': [
            'Windows 64 位，OpenCL 版，推荐更快，免安装',
            'Windows 64 位，OpenCL 版，想安装',
            'Windows 64 位，CPU 兼容版，免安装',
            'Windows 64 位，CPU 兼容版，想安装',
            'Windows 64 位，NVIDIA 显卡，免安装',
            'Windows 64 位，NVIDIA 显卡，想安装',
            'Windows 64 位，想自己配引擎',
            'Windows 64 位，想自己配引擎，也想安装器',
            'macOS Apple Silicon',
            'macOS Intel',
            'Linux 64 位，CPU 兼容版',
            'Linux 64 位，OpenCL 版，AMD/Intel GPU',
            'Linux 64 位，NVIDIA CUDA 版',
        ],
        'zh_hant': [
            'Windows 64 位，OpenCL 版，推薦更快，免安裝',
            'Windows 64 位，OpenCL 版，想安裝',
            'Windows 64 位，CPU 相容版，免安裝',
            'Windows 64 位，CPU 相容版，想安裝',
            'Windows 64 位，NVIDIA 顯示卡，免安裝',
            'Windows 64 位，NVIDIA 顯示卡，想安裝',
            'Windows 64 位，想自己配引擎',
            'Windows 64 位，想自己配引擎，也想安裝器',
            'macOS Apple Silicon',
            'macOS Intel',
            'Linux 64 位，CPU 相容版',
            'Linux 64 位，OpenCL 版，AMD/Intel GPU',
            'Linux 64 位，NVIDIA CUDA 版',
        ],
        'en': [
            'Windows 64-bit, OpenCL, recommended and faster, no install',
            'Windows 64-bit, OpenCL, installer',
            'Windows 64-bit, CPU compatible build, no install',
            'Windows 64-bit, CPU compatible build, installer',
            'Windows 64-bit, NVIDIA GPU, no install',
            'Windows 64-bit, NVIDIA GPU, installer',
            'Windows 64-bit, configure your own engine',
            'Windows 64-bit, configure your own engine, installer',
            'macOS Apple Silicon',
            'macOS Intel',
            'Linux 64-bit, CPU compatible build',
            'Linux 64-bit, OpenCL for AMD/Intel GPU',
            'Linux 64-bit, NVIDIA CUDA',
        ],
        'ja': [
            'Windows 64-bit、OpenCL 推奨高速版、インストール不要',
            'Windows 64-bit、OpenCL 版、インストーラ',
            'Windows 64-bit、CPU 互換版、インストール不要',
            'Windows 64-bit、CPU 互換版、インストーラ',
            'Windows 64-bit、NVIDIA GPU、インストール不要',
            'Windows 64-bit、NVIDIA GPU、インストーラ',
            'Windows 64-bit、自分でエンジンを設定したい場合',
            'Windows 64-bit、自分でエンジンを設定したい場合、インストーラ',
            'macOS Apple Silicon',
            'macOS Intel',
            'Linux 64-bit、CPU 互換版',
            'Linux 64-bit、OpenCL、AMD/Intel GPU',
            'Linux 64-bit、NVIDIA CUDA',
        ],
        'ko': [
            'Windows 64-bit, OpenCL 추천 고속판, 무설치',
            'Windows 64-bit, OpenCL, 설치형',
            'Windows 64-bit, CPU 호환 빌드, 무설치',
            'Windows 64-bit, CPU 호환 빌드, 설치형',
            'Windows 64-bit, NVIDIA GPU, 무설치',
            'Windows 64-bit, NVIDIA GPU, 설치형',
            'Windows 64-bit, 직접 엔진 설정',
            'Windows 64-bit, 직접 엔진 설정, 설치형',
            'macOS Apple Silicon',
            'macOS Intel',
            'Linux 64-bit, CPU 호환 빌드',
            'Linux 64-bit, OpenCL, AMD/Intel GPU',
            'Linux 64-bit, NVIDIA CUDA',
        ],
        'th': [
            'Windows 64-bit, OpenCL, แนะนำและเร็วกว่า, ไม่ต้องติดตั้ง',
            'Windows 64-bit, OpenCL, แบบติดตั้ง',
            'Windows 64-bit, CPU compatible build, ไม่ต้องติดตั้ง',
            'Windows 64-bit, CPU compatible build, แบบติดตั้ง',
            'Windows 64-bit, การ์ดจอ NVIDIA, ไม่ต้องติดตั้ง',
            'Windows 64-bit, การ์ดจอ NVIDIA, แบบติดตั้ง',
            'Windows 64-bit, ต้องการตั้งค่า engine เอง',
            'Windows 64-bit, ต้องการตั้งค่า engine เองและอยากใช้ installer',
            'macOS Apple Silicon',
            'macOS Intel',
            'Linux 64-bit, CPU compatible build',
            'Linux 64-bit, OpenCL สำหรับ AMD/Intel GPU',
            'Linux 64-bit, NVIDIA CUDA',
        ],
    }

    content: list[dict[str, object]] = [
        {
            'language': '中文',
            'intro': (
                '这一版是一次小而关键的维护更新：合并并补强社区 PR #16，'
                '修复棋盘同步工具发出停止思考信号后，主引擎可能被重新打开分析的问题。'
            ),
            'updates_heading': '本版主要更新',
            'updates': [
                '感谢 @qiyi71w 提交 PR #16：readboard 的 `noponder` 信号现在只会在引擎确实正在 ponder 时才切换状态。',
                '修复引擎对局 / 棋盘同步结束后，`stopAiPlayingAndPolicy()` 已经停止分析，但后续 `togglePonder()` 又把分析重新打开的边界问题。',
                '新增 `stopPonderingIfActive()` 作为统一停止入口，避免以后再出现“停止命令反向启动引擎”的同类问题。',
                '补充 readboard 引擎生命周期回归测试，覆盖已停止、自动对局停止、仍在 ponder 三种场景。',
                '复查同类调用：`sync` 命令仍保留按需启动分析；`noponder` 只负责停止，不再改变用户已经停止的状态。',
            ],
            'before_heading': '下载前先看这几句',
            'before': [
                f'Windows 普通用户仍然优先下载 {assets_cn["windows_opencl_portable"]}，这是 **OpenCL 版（推荐，免安装）**。',
                f'如果 OpenCL 在你的电脑上不稳定，再改用 {assets_cn["windows_portable"]}。',
                f'如果你的电脑是 **NVIDIA 显卡**，优先下载 {assets_cn["windows_nvidia_portable"]}。',
                '这版是稳定性修复版，重点解决 PR #16 指向的引擎状态问题。',
                '本地已通过 `mvn test` 和 `mvn -DskipTests package`；发布工作流会继续生成 Windows / Linux / macOS 全量包。',
            ],
            'download_heading': '下载建议',
            'download_headers': ('你的电脑', '直接下载这个'),
            'download_labels': shared_download_labels['zh'],
            'why_heading': '这一版为什么值得更新',
            'why': [
                '棋盘同步工具停止分析时，不会再把已经停止的主引擎误启动。',
                '引擎对局结束、同步结束、手动停止分析之间的状态更一致。',
                '这类状态机问题已经补上自动化测试，后续改 readboard 同步逻辑更稳。',
                '社区 PR 被保留并补强测试后合并，贡献记录清楚，也更方便继续协作。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': '繁體中文',
            'intro': (
                '這一版是一次小而關鍵的維護更新：合併並補強社群 PR #16，'
                '修復棋盤同步工具發出停止思考訊號後，主引擎可能被重新打開分析的問題。'
            ),
            'updates_heading': '本版主要更新',
            'updates': [
                '感謝 @qiyi71w 提交 PR #16：readboard 的 `noponder` 訊號現在只會在引擎確實正在 ponder 時才切換狀態。',
                '修復引擎對局 / 棋盤同步結束後，`stopAiPlayingAndPolicy()` 已經停止分析，但後續 `togglePonder()` 又把分析重新打開的邊界問題。',
                '新增 `stopPonderingIfActive()` 作為統一停止入口，避免以後再出現「停止命令反向啟動引擎」的同類問題。',
                '補充 readboard 引擎生命週期回歸測試，覆蓋已停止、自動對局停止、仍在 ponder 三種場景。',
                '復查同類呼叫：`sync` 命令仍保留按需啟動分析；`noponder` 只負責停止，不再改變使用者已經停止的狀態。',
            ],
            'before_heading': '下載前先看這幾句',
            'before': [
                f'Windows 一般使用者仍然優先下載 {assets_cn["windows_opencl_portable"]}，這是 **OpenCL 版（推薦，免安裝）**。',
                f'如果 OpenCL 在你的電腦上不穩定，再改用 {assets_cn["windows_portable"]}。',
                f'如果你的電腦是 **NVIDIA 顯示卡**，優先下載 {assets_cn["windows_nvidia_portable"]}。',
                '這版是穩定性修復版，重點解決 PR #16 指向的引擎狀態問題。',
                '本地已通過 `mvn test` 和 `mvn -DskipTests package`；發版 workflow 會繼續產生 Windows / Linux / macOS 全量包。',
            ],
            'download_heading': '下載建議',
            'download_headers': ('你的電腦', '直接下載這個'),
            'download_labels': shared_download_labels['zh_hant'],
            'why_heading': '這一版為什麼值得更新',
            'why': [
                '棋盤同步工具停止分析時，不會再把已經停止的主引擎誤啟動。',
                '引擎對局結束、同步結束、手動停止分析之間的狀態更一致。',
                '這類狀態機問題已經補上自動化測試，後續修改 readboard 同步邏輯更穩。',
                '社群 PR 被保留並補強測試後合併，貢獻記錄清楚，也更方便繼續協作。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': 'English',
            'intro': (
                'This is a focused maintenance build: it merges and strengthens community PR #16, '
                'fixing a readboard stop signal that could accidentally restart primary-engine analysis.'
            ),
            'updates_heading': 'Release Highlights',
            'updates': [
                'Thanks to @qiyi71w for PR #16: readboard `noponder` now toggles ponder only when the engine is actually pondering.',
                'Fixed the edge case where `stopAiPlayingAndPolicy()` had already stopped analysis after engine play or board sync, but a later `togglePonder()` started analysis again.',
                'Added `stopPonderingIfActive()` as the single guarded stop path, reducing the chance of another stop command turning into a start command.',
                'Added readboard engine-lifecycle regression tests for already stopped, auto-play stopped, and still-pondering states.',
                'Reviewed related call sites: `sync` still starts analysis when needed, while `noponder` now only stops active analysis.',
            ],
            'before_heading': 'Read Before Downloading',
            'before': [
                f'Most Windows users should still download {assets["windows_opencl_portable"]}, the **recommended no-install OpenCL build**.',
                f'If OpenCL is unstable on your PC, use {assets["windows_portable"]} instead.',
                f'If your PC has an **NVIDIA GPU**, try {assets["windows_nvidia_portable"]} first.',
                'This is a stability release focused on the engine-state issue fixed by PR #16.',
                'Local `mvn test` and `mvn -DskipTests package` passed; release workflows will build the full Windows / Linux / macOS asset set.',
            ],
            'download_heading': 'Download Guide',
            'download_headers': ('Your computer', 'Download this file'),
            'download_labels': shared_download_labels['en'],
            'why_heading': 'Why This Release Is Worth Updating',
            'why': [
                'Stopping the board-sync tool no longer restarts a primary engine that was already stopped.',
                'Engine-game end, sync end, and manual analysis stop states are now more consistent.',
                'The readboard state-machine behavior now has regression coverage for future changes.',
                'The community contribution is merged with clear credit and additional project-side safeguards.',
            ],
            'contact_heading': 'Contact',
            'contact': ['QQ group: `299419120`'],
        },
        {
            'language': '日本語',
            'intro': (
                'このビルドは小さくても重要なメンテナンス更新です。コミュニティ PR #16 を取り込み、'
                'readboard の停止信号によって主エンジン分析が誤って再開される問題を修正しました。'
            ),
            'updates_heading': '主な更新',
            'updates': [
                '@qiyi71w さんの PR #16 に感謝します。readboard の `noponder` は、エンジンが実際に ponder 中のときだけ状態を切り替えるようになりました。',
                'エンジン対局 / 棋盤同期の終了後、`stopAiPlayingAndPolicy()` が分析を止めたのに、後続の `togglePonder()` が分析を再開してしまう境界ケースを修正しました。',
                '停止処理を `stopPonderingIfActive()` に集約し、停止コマンドが開始コマンドに変わる同種の問題を起こしにくくしました。',
                'readboard のエンジンライフサイクル回帰テストを追加し、停止済み、自動対局で停止済み、まだ ponder 中の 3 状態を確認しています。',
                '関連呼び出しも確認しました。`sync` は必要なときだけ分析を開始し、`noponder` は active な分析だけを止めます。',
            ],
            'before_heading': 'ダウンロード前に',
            'before': [
                f'多くの Windows ユーザーは {assets["windows_opencl_portable"]} を選ぶのがおすすめです。これは **推奨 OpenCL 版、インストール不要** です。',
                f'OpenCL が不安定な場合は {assets["windows_portable"]} を使ってください。',
                f'**NVIDIA GPU** 搭載 PC では {assets["windows_nvidia_portable"]} を優先してください。',
                'この版は PR #16 のエンジン状態修正に集中した安定性更新です。',
                'ローカルの `mvn test` と `mvn -DskipTests package` は通過済みです。release workflow が Windows / Linux / macOS の全パッケージを生成します。',
            ],
            'download_heading': 'ダウンロード案内',
            'download_headers': ('お使いの環境', 'ダウンロードするファイル'),
            'download_labels': shared_download_labels['ja'],
            'why_heading': 'このリリースを更新する理由',
            'why': [
                '棋盤同期ツールを停止したとき、すでに停止している主エンジンを誤って再起動しなくなりました。',
                'エンジン対局終了、同期終了、手動停止の状態がより一貫します。',
                'readboard の状態遷移に回帰テストが入り、今後の変更も安全に進めやすくなりました。',
                'コミュニティからの貢献を、明確なクレジットと追加の安全策付きで取り込みました。',
            ],
            'contact_heading': '連絡先',
            'contact': ['QQ グループ: `299419120`'],
        },
        {
            'language': '한국어',
            'intro': (
                '이번 빌드는 작지만 중요한 유지보수 릴리스입니다. 커뮤니티 PR #16 을 병합하고 보강하여, '
                'readboard 중지 신호가 주 엔진 분석을 다시 켜는 문제를 수정했습니다.'
            ),
            'updates_heading': '주요 업데이트',
            'updates': [
                'PR #16 을 제출해 준 @qiyi71w 님께 감사드립니다. readboard `noponder` 는 이제 엔진이 실제로 ponder 중일 때만 상태를 전환합니다.',
                '엔진 대국 / 보드 동기화 종료 후 `stopAiPlayingAndPolicy()` 가 이미 분석을 멈췄는데, 뒤이어 `togglePonder()` 가 분석을 다시 시작하던 경계 문제를 고쳤습니다.',
                '`stopPonderingIfActive()` 를 단일 보호 중지 경로로 추가해, 중지 명령이 시작 명령처럼 동작하는 같은 유형의 문제를 줄였습니다.',
                'readboard 엔진 lifecycle 회귀 테스트를 추가해 이미 멈춘 상태, 자동 대국으로 멈춘 상태, 아직 ponder 중인 상태를 모두 확인합니다.',
                '관련 호출도 점검했습니다. `sync` 는 필요할 때 분석을 시작하고, `noponder` 는 active 분석만 멈춥니다.',
            ],
            'before_heading': '다운로드 전 확인',
            'before': [
                f'대부분의 Windows 사용자는 {assets["windows_opencl_portable"]} 를 먼저 받으면 됩니다. 이는 **추천 OpenCL 무설치 빌드** 입니다.',
                f'OpenCL 이 PC에서 불안정하면 {assets["windows_portable"]} 를 대신 사용하세요.',
                f'**NVIDIA GPU** 가 있다면 {assets["windows_nvidia_portable"]} 를 우선 사용해 보세요.',
                '이번 버전은 PR #16 이 수정한 엔진 상태 문제에 집중한 안정성 릴리스입니다.',
                '로컬 `mvn test` 와 `mvn -DskipTests package` 는 통과했습니다. release workflow 가 Windows / Linux / macOS 전체 패키지를 생성합니다.',
            ],
            'download_heading': '다운로드 안내',
            'download_headers': ('내 컴퓨터', '다운로드할 파일'),
            'download_labels': shared_download_labels['ko'],
            'why_heading': '이번 릴리스를 업데이트할 이유',
            'why': [
                '보드 동기화 도구를 멈출 때 이미 멈춘 주 엔진이 다시 시작되지 않습니다.',
                '엔진 대국 종료, 동기화 종료, 수동 분석 중지 상태가 더 일관됩니다.',
                'readboard 상태 전환에 회귀 테스트가 추가되어 이후 변경도 더 안전합니다.',
                '커뮤니티 기여를 명확한 크레딧과 추가 안전장치와 함께 병합했습니다.',
            ],
            'contact_heading': '연락처',
            'contact': ['QQ 그룹: `299419120`'],
        },
        {
            'language': 'ภาษาไทย',
            'intro': (
                'บิลด์นี้เป็น maintenance release ขนาดเล็กแต่สำคัญ: รวมและเสริม PR #16 จากชุมชน '
                'เพื่อแก้ปัญหาสัญญาณหยุดของ readboard ที่อาจเปิด analysis ของ primary engine กลับขึ้นมาเอง'
            ),
            'updates_heading': 'ไฮไลต์ของเวอร์ชันนี้',
            'updates': [
                'ขอบคุณ @qiyi71w สำหรับ PR #16: ตอนนี้ readboard `noponder` จะ toggle ponder เฉพาะเมื่อ engine กำลัง ponder จริง ๆ เท่านั้น',
                'แก้ edge case ที่ `stopAiPlayingAndPolicy()` หยุด analysis แล้วหลังจบ engine game / board sync แต่ `togglePonder()` ถัดมาทำให้ analysis เริ่มใหม่',
                'เพิ่ม `stopPonderingIfActive()` เป็นทางหยุดแบบมี guard เพื่อลดโอกาสที่คำสั่ง stop จะกลายเป็นคำสั่ง start',
                'เพิ่ม regression tests สำหรับ lifecycle ของ readboard engine ครอบคลุมสถานะ already stopped, auto-play stopped และ still pondering',
                'ตรวจ call sites ที่เกี่ยวข้องแล้ว: `sync` ยังเริ่ม analysis เมื่อจำเป็น ส่วน `noponder` จะหยุดเฉพาะ analysis ที่ active อยู่',
            ],
            'before_heading': 'ก่อนดาวน์โหลด ดูตรงนี้ก่อน',
            'before': [
                f'ผู้ใช้ Windows ส่วนใหญ่ยังแนะนำให้ดาวน์โหลด {assets["windows_opencl_portable"]} ซึ่งเป็น **OpenCL รุ่นแนะนำ แบบไม่ต้องติดตั้ง**',
                f'ถ้า OpenCL ไม่เสถียรบนเครื่องของคุณ ให้ใช้ {assets["windows_portable"]} แทน',
                f'ถ้ามี **NVIDIA GPU** แนะนำให้ลอง {assets["windows_nvidia_portable"]} ก่อน',
                'รีลีสนี้เป็น stability release ที่เน้นปัญหา engine-state จาก PR #16',
                'ในเครื่อง local ผ่าน `mvn test` และ `mvn -DskipTests package` แล้ว และ release workflows จะสร้าง assets ครบสำหรับ Windows / Linux / macOS',
            ],
            'download_heading': 'แนะนำการดาวน์โหลด',
            'download_headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
            'download_labels': shared_download_labels['th'],
            'why_heading': 'ทำไมเวอร์ชันนี้ควรอัปเดต',
            'why': [
                'เมื่อหยุด board-sync tool จะไม่ทำให้ primary engine ที่หยุดอยู่แล้วเริ่มใหม่โดยไม่ตั้งใจ',
                'สถานะหลังจบ engine game, จบ sync และหยุด analysis เองจะสอดคล้องกันมากขึ้น',
                'state-machine ของ readboard มี regression coverage แล้ว ทำให้การแก้ไขในอนาคตปลอดภัยขึ้น',
                'รวม contribution จากชุมชนพร้อม credit ชัดเจนและ safeguards เพิ่มเติมฝั่งโปรเจกต์',
            ],
            'contact_heading': 'ติดต่อ',
            'contact': ['QQ group: `299419120`'],
        },
    ]

    sections: list[dict[str, object]] = []
    for block in content:
        language = str(block['language'])
        localized_assets = assets_cn if language in ('中文', '繁體中文') else assets
        sections.append(
            {
                'language': language,
                'intro': block['intro'],
                'updates': {
                    'heading': block['updates_heading'],
                    'items': block['updates'],
                },
                'before': {
                    'heading': block['before_heading'],
                    'items': block['before'],
                },
                'download': {
                    'heading': block['download_heading'],
                    'headers': block['download_headers'],
                    'rows': download_rows(block['download_labels'], localized_assets),
                },
                'why': {
                    'heading': block['why_heading'],
                    'items': block['why'],
                },
                'contact': {
                    'heading': block['contact_heading'],
                    'items': block['contact'],
                },
            }
        )

    add_nvidia50_download_rows(sections, assets_cn, assets)
    add_tensorrt_split_download_row(sections, assets_cn, assets, asset_map)
    validate_release_sections(sections)

    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_05_06_1_notes(
    asset_map: dict[str, str | None],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {
        key: format_asset(asset_map[key], repo, release_tag)
        for key in asset_map
    }
    assets = {
        key: format_asset_en(asset_map[key], repo, release_tag)
        for key in asset_map
    }
    asset_order = [
        'windows_opencl_portable',
        'windows_opencl_installer',
        'windows_portable',
        'windows_installer',
        'windows_nvidia_portable',
        'windows_nvidia_installer',
        'windows_no_engine_portable',
        'windows_no_engine_installer',
        'mac_arm64',
        'mac_amd64',
        'linux64',
        'linux64_opencl',
        'linux64_nvidia',
    ]

    def download_rows(labels: list[str], localized_assets: dict[str, str]) -> list[tuple[str, str]]:
        return list(zip(labels, (localized_assets[key] for key in asset_order)))

    shared_download_labels = {
        'zh': [
            'Windows 64 位，OpenCL 版，推荐更快，免安装',
            'Windows 64 位，OpenCL 版，想安装',
            'Windows 64 位，CPU 兼容版，免安装',
            'Windows 64 位，CPU 兼容版，想安装',
            'Windows 64 位，NVIDIA 显卡，免安装',
            'Windows 64 位，NVIDIA 显卡，想安装',
            'Windows 64 位，想自己配引擎',
            'Windows 64 位，想自己配引擎，也想安装器',
            'macOS Apple Silicon',
            'macOS Intel',
            'Linux 64 位，CPU 兼容版',
            'Linux 64 位，OpenCL 版，AMD/Intel GPU',
            'Linux 64 位，NVIDIA CUDA 版',
        ],
        'zh_hant': [
            'Windows 64 位，OpenCL 版，推薦更快，免安裝',
            'Windows 64 位，OpenCL 版，想安裝',
            'Windows 64 位，CPU 相容版，免安裝',
            'Windows 64 位，CPU 相容版，想安裝',
            'Windows 64 位，NVIDIA 顯示卡，免安裝',
            'Windows 64 位，NVIDIA 顯示卡，想安裝',
            'Windows 64 位，想自己配引擎',
            'Windows 64 位，想自己配引擎，也想安裝器',
            'macOS Apple Silicon',
            'macOS Intel',
            'Linux 64 位，CPU 相容版',
            'Linux 64 位，OpenCL 版，AMD/Intel GPU',
            'Linux 64 位，NVIDIA CUDA 版',
        ],
        'en': [
            'Windows 64-bit, OpenCL, recommended and faster, no install',
            'Windows 64-bit, OpenCL, installer',
            'Windows 64-bit, CPU compatible build, no install',
            'Windows 64-bit, CPU compatible build, installer',
            'Windows 64-bit, NVIDIA GPU, no install',
            'Windows 64-bit, NVIDIA GPU, installer',
            'Windows 64-bit, configure your own engine',
            'Windows 64-bit, configure your own engine, installer',
            'macOS Apple Silicon',
            'macOS Intel',
            'Linux 64-bit, CPU compatible build',
            'Linux 64-bit, OpenCL for AMD/Intel GPU',
            'Linux 64-bit, NVIDIA CUDA',
        ],
        'ja': [
            'Windows 64-bit、OpenCL 推奨高速版、インストール不要',
            'Windows 64-bit、OpenCL 版、インストーラ',
            'Windows 64-bit、CPU 互換版、インストール不要',
            'Windows 64-bit、CPU 互換版、インストーラ',
            'Windows 64-bit、NVIDIA GPU、インストール不要',
            'Windows 64-bit、NVIDIA GPU、インストーラ',
            'Windows 64-bit、自分でエンジンを設定したい場合',
            'Windows 64-bit、自分でエンジンを設定したい場合、インストーラ',
            'macOS Apple Silicon',
            'macOS Intel',
            'Linux 64-bit、CPU 互換版',
            'Linux 64-bit、OpenCL、AMD/Intel GPU',
            'Linux 64-bit、NVIDIA CUDA',
        ],
        'ko': [
            'Windows 64-bit, OpenCL 추천 고속판, 무설치',
            'Windows 64-bit, OpenCL, 설치형',
            'Windows 64-bit, CPU 호환 빌드, 무설치',
            'Windows 64-bit, CPU 호환 빌드, 설치형',
            'Windows 64-bit, NVIDIA GPU, 무설치',
            'Windows 64-bit, NVIDIA GPU, 설치형',
            'Windows 64-bit, 직접 엔진 설정',
            'Windows 64-bit, 직접 엔진 설정, 설치형',
            'macOS Apple Silicon',
            'macOS Intel',
            'Linux 64-bit, CPU 호환 빌드',
            'Linux 64-bit, OpenCL, AMD/Intel GPU',
            'Linux 64-bit, NVIDIA CUDA',
        ],
        'th': [
            'Windows 64-bit, OpenCL, แนะนำและเร็วกว่า, ไม่ต้องติดตั้ง',
            'Windows 64-bit, OpenCL, แบบติดตั้ง',
            'Windows 64-bit, CPU compatible build, ไม่ต้องติดตั้ง',
            'Windows 64-bit, CPU compatible build, แบบติดตั้ง',
            'Windows 64-bit, การ์ดจอ NVIDIA, ไม่ต้องติดตั้ง',
            'Windows 64-bit, การ์ดจอ NVIDIA, แบบติดตั้ง',
            'Windows 64-bit, ต้องการตั้งค่า engine เอง',
            'Windows 64-bit, ต้องการตั้งค่า engine เองและอยากใช้ installer',
            'macOS Apple Silicon',
            'macOS Intel',
            'Linux 64-bit, CPU compatible build',
            'Linux 64-bit, OpenCL สำหรับ AMD/Intel GPU',
            'Linux 64-bit, NVIDIA CUDA',
        ],
    }

    content: list[dict[str, object]] = [
        {
            'language': '中文',
            'intro': '这是面向公开测试的稳定性 pre-release，重点修复 KataGo 智能测速、一键设置、默认引擎保存和经典配色提示框。',
            'updates_heading': '本版主要更新',
            'updates': [
                '智能测速保留官方 KataGo benchmark 流程，但优化静默阶段的进度心跳，避免 88% 长时间不动造成“卡住”的错觉。',
                '一键设置新增“导入自定义权重”，下载权重和应用权重分离；本地可以保留多个权重，再选择一个应用。',
                '修复默认引擎/权重保存：重启后不再被第一项或 auto setup 覆盖，调整顺序也能正确保留默认项。',
                '综合设置里的“限制变化图长度”默认改为 15，并传入 KataGo `analysisPVLen`，右下角小棋盘变化图长度设置开始真正生效。',
                '经典配色下的通用提示框改成可读背景，避免引擎未加载完时点规则等功能弹出黑底提示。',
                '一键设置弹窗在高 DPI Windows 上不再被长路径撑出屏幕，导入、应用、下载按钮完整可见。',
                '感谢 @qiyi71w 的 PR #17，已隔离编译验证但本轮不混入大功能；感谢 @tosuhun-sys 的 issue #14，相关界面问题已关闭。',
            ],
            'before_heading': '下载前先看这几句',
            'before': [
                f'Windows 普通用户优先下载 {assets_cn["windows_opencl_portable"]}。',
                f'如果 OpenCL 不稳定，再改用 {assets_cn["windows_portable"]}。',
                f'NVIDIA 显卡用户优先试 {assets_cn["windows_nvidia_portable"]}。',
                '这是 pre-release，建议愿意帮忙测试 benchmark、一键设置和默认引擎保存的用户优先试用。',
                '本地已通过 `mvn test`、`mvn -DskipTests package`，并在 Windows OpenCL 便携包上完成真实 GUI 复测。',
            ],
            'download_heading': '下载建议',
            'download_headers': ('你的电脑', '直接下载这个'),
            'download_labels': shared_download_labels['zh'],
            'why_heading': '这一版为什么值得测试',
            'why': [
                '测速不会再给用户“88% 卡死”的体验。',
                '自定义权重可以像正常产品一样导入、保存、选择、应用。',
                '默认引擎和默认权重终于不再被启动修复流程抢回去。',
                '变化图长度设置和 KataGo 实际分析参数对齐。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': '繁體中文',
            'intro': '這是面向公開測試的穩定性 pre-release，重點修復 KataGo 智慧測速、一鍵設定、預設引擎保存和經典配色提示框。',
            'updates_heading': '本版主要更新',
            'updates': [
                '智慧測速保留官方 KataGo benchmark 流程，但最佳化靜默階段的進度心跳，避免 88% 長時間不動造成「卡住」的錯覺。',
                '一鍵設定新增「匯入自訂權重」，下載權重和套用權重分離；本地可以保留多個權重，再選擇一個套用。',
                '修復預設引擎/權重保存：重新啟動後不再被第一項或 auto setup 覆蓋，調整順序也能正確保留預設項。',
                '綜合設定裡的「限制變化圖長度」預設改為 15，並傳入 KataGo `analysisPVLen`，右下角小棋盤變化圖長度設定開始真正生效。',
                '經典配色下的通用提示框改成可讀背景，避免引擎未載入完成時點規則等功能彈出黑底提示。',
                '一鍵設定視窗在高 DPI Windows 上不再被長路徑撐出螢幕，匯入、套用、下載按鈕完整可見。',
                '感謝 @qiyi71w 的 PR #17，已隔離編譯驗證但本輪不混入大功能；感謝 @tosuhun-sys 的 issue #14，相關介面問題已關閉。',
            ],
            'before_heading': '下載前先看這幾句',
            'before': [
                f'Windows 一般使用者優先下載 {assets_cn["windows_opencl_portable"]}。',
                f'如果 OpenCL 不穩定，再改用 {assets_cn["windows_portable"]}。',
                f'NVIDIA 顯示卡使用者優先試 {assets_cn["windows_nvidia_portable"]}。',
                '這是 pre-release，建議願意幫忙測試 benchmark、一鍵設定和預設引擎保存的使用者優先試用。',
                '本地已通過 `mvn test`、`mvn -DskipTests package`，並在 Windows OpenCL 便攜包上完成真實 GUI 複測。',
            ],
            'download_heading': '下載建議',
            'download_headers': ('你的電腦', '直接下載這個'),
            'download_labels': shared_download_labels['zh_hant'],
            'why_heading': '這一版為什麼值得測試',
            'why': [
                '測速不會再給使用者「88% 卡死」的體驗。',
                '自訂權重可以像正常產品一樣匯入、保存、選擇、套用。',
                '預設引擎和預設權重不再被啟動修復流程搶回去。',
                '變化圖長度設定和 KataGo 實際分析參數對齊。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': 'English',
            'intro': 'This public testing pre-release focuses on KataGo Smart Optimize, one-click setup, default-engine persistence, and readable classic-theme dialogs.',
            'updates_heading': 'Release Highlights',
            'updates': [
                'Smart Optimize still uses the official KataGo benchmark, but the silent late phase now gets smoother progress heartbeats so 88% no longer looks frozen.',
                'One-click setup can now import custom weights. Downloading a weight and applying a weight are separate, so users can keep multiple local weights and choose one to use.',
                'Fixed default engine and weight persistence: restarts no longer reset the default to the first row or auto setup, and reordered engines keep the intended default.',
                'The “limit variation length” setting now defaults to 15 and is passed to KataGo as `analysisPVLen`, so the lower-right board variation length setting takes effect.',
                'Generic message dialogs now use readable colors in the classic theme, avoiding black-background warnings when engine-related actions are clicked before loading finishes.',
                'The one-click setup dialog no longer stretches off-screen on high-DPI Windows; import, apply, and download buttons remain visible.',
                'Thanks to @qiyi71w for PR #17; it passed isolated package compilation but is held for a dedicated feature review. Thanks to @tosuhun-sys for issue #14, now closed.',
            ],
            'before_heading': 'Read Before Downloading',
            'before': [
                f'Most Windows users should try {assets["windows_opencl_portable"]} first.',
                f'If OpenCL is unstable, use {assets["windows_portable"]}.',
                f'NVIDIA GPU users should try {assets["windows_nvidia_portable"]} first.',
                'This is a pre-release for users willing to test benchmark progress, one-click setup, and default-engine persistence.',
                'Local `mvn test` and `mvn -DskipTests package` passed, with real Windows OpenCL portable GUI verification.',
            ],
            'download_heading': 'Download Guide',
            'download_headers': ('Your computer', 'Download this file'),
            'download_labels': shared_download_labels['en'],
            'why_heading': 'Why This Release Is Worth Testing',
            'why': [
                'Benchmark progress no longer feels stuck at 88%.',
                'Custom weights can be imported, kept locally, selected, and applied explicitly.',
                'Default engine and weight choices are not overwritten by startup repair.',
                'Variation-length UI settings now match the actual KataGo analysis parameter.',
            ],
            'contact_heading': 'Contact',
            'contact': ['QQ group: `299419120`'],
        },
        {
            'language': '日本語',
            'intro': 'この pre-release は、KataGo Smart Optimize、一括設定、既定エンジン保存、classic テーマのメッセージ表示を重点的に改善します。',
            'updates_heading': '主な更新',
            'updates': [
                'Smart Optimize は公式 KataGo benchmark をそのまま使い、後半の無出力区間だけ進捗表示をなめらかにして 88% で止まったように見える問題を減らしました。',
                '一括設定でカスタム重みをインポートできます。ダウンロードと適用を分離し、複数のローカル重みから選んで使えます。',
                '既定エンジン/重みの保存を修正しました。再起動後に先頭行や auto setup に戻らず、並べ替え後も既定項目を維持します。',
                '「変化図長さ制限」の既定値を 15 にし、KataGo `analysisPVLen` に渡すことで右下の小盤の変化図長さ設定が有効になります。',
                'classic テーマの共通メッセージダイアログを読みやすい背景に変更し、エンジン読込前の警告が黒背景にならないようにしました。',
                '高 DPI Windows で一括設定ダイアログが画面外へ伸びないようにし、インポート/適用/ダウンロードボタンを表示します。',
                '@qiyi71w さんの PR #17 に感謝します。隔離ビルドは通過しましたが、大きな機能なので別途レビューします。issue #14 の @tosuhun-sys さんにも感謝します。',
            ],
            'before_heading': 'ダウンロード前に',
            'before': [
                f'多くの Windows ユーザーは {assets["windows_opencl_portable"]} を先に試してください。',
                f'OpenCL が不安定な場合は {assets["windows_portable"]} を使ってください。',
                f'NVIDIA GPU では {assets["windows_nvidia_portable"]} を優先してください。',
                'これは benchmark、一括設定、既定エンジン保存の確認に協力できるユーザー向け pre-release です。',
                'ローカル `mvn test`、`mvn -DskipTests package` に通過し、Windows OpenCL portable で実機 GUI 検証済みです。',
            ],
            'download_heading': 'ダウンロード案内',
            'download_headers': ('お使いの環境', 'ダウンロードするファイル'),
            'download_labels': shared_download_labels['ja'],
            'why_heading': 'この版をテストする理由',
            'why': [
                'benchmark が 88% で固まったように見えにくくなります。',
                'カスタム重みをインポートし、ローカル保存、選択、適用できます。',
                '既定エンジンと重みが起動時の修復処理で上書きされません。',
                '変化図長さ設定が実際の KataGo 分析パラメータと一致します。',
            ],
            'contact_heading': '連絡先',
            'contact': ['QQ グループ: `299419120`'],
        },
        {
            'language': '한국어',
            'intro': '이 pre-release 는 KataGo Smart Optimize, 원클릭 설정, 기본 엔진 저장, classic 테마 메시지 가독성 개선에 집중합니다.',
            'updates_heading': '주요 업데이트',
            'updates': [
                'Smart Optimize 는 공식 KataGo benchmark 를 그대로 사용하되, 후반 무출력 구간의 진행 표시를 부드럽게 해 88% 에서 멈춘 것처럼 보이는 문제를 줄였습니다.',
                '원클릭 설정에서 사용자 지정 가중치를 가져올 수 있습니다. 다운로드와 적용을 분리해 여러 로컬 가중치를 보관하고 선택해 적용할 수 있습니다.',
                '기본 엔진/가중치 저장을 수정했습니다. 재시작 후 첫 번째 행이나 auto setup 으로 돌아가지 않고, 순서를 바꿔도 기본 항목이 유지됩니다.',
                '“변화도 길이 제한” 기본값을 15 로 바꾸고 KataGo `analysisPVLen` 으로 전달해 오른쪽 아래 작은 보드의 변화도 길이 설정이 실제로 적용됩니다.',
                'classic 테마의 공통 메시지 다이얼로그 배경을 읽기 쉬운 색으로 바꿔, 엔진 로딩 전 경고가 검은 배경으로 뜨지 않게 했습니다.',
                '고 DPI Windows 에서 원클릭 설정 창이 화면 밖으로 늘어나지 않고, 가져오기/적용/다운로드 버튼이 보입니다.',
                '@qiyi71w 님의 PR #17 에 감사드립니다. 격리 패키지 빌드는 통과했지만 큰 기능이라 별도 리뷰로 진행합니다. issue #14 의 @tosuhun-sys 님께도 감사드립니다.',
            ],
            'before_heading': '다운로드 전 확인',
            'before': [
                f'대부분의 Windows 사용자는 {assets["windows_opencl_portable"]} 를 먼저 사용해 보세요.',
                f'OpenCL 이 불안정하면 {assets["windows_portable"]} 를 사용하세요.',
                f'NVIDIA GPU 사용자는 {assets["windows_nvidia_portable"]} 를 우선 권장합니다.',
                'benchmark 진행, 원클릭 설정, 기본 엔진 저장을 함께 테스트할 사용자를 위한 pre-release 입니다.',
                '로컬 `mvn test`, `mvn -DskipTests package` 를 통과했고 Windows OpenCL portable 에서 실제 GUI 검증을 마쳤습니다.',
            ],
            'download_heading': '다운로드 안내',
            'download_headers': ('내 컴퓨터', '다운로드할 파일'),
            'download_labels': shared_download_labels['ko'],
            'why_heading': '이번 버전을 테스트할 이유',
            'why': [
                'benchmark 진행률이 88% 에서 멈춘 것처럼 느껴지지 않습니다.',
                '사용자 지정 가중치를 가져오고, 로컬에 보관하고, 선택해서 적용할 수 있습니다.',
                '기본 엔진과 가중치가 시작 복구 과정에서 덮어써지지 않습니다.',
                '변화도 길이 설정이 실제 KataGo 분석 파라미터와 일치합니다.',
            ],
            'contact_heading': '연락처',
            'contact': ['QQ 그룹: `299419120`'],
        },
        {
            'language': 'ภาษาไทย',
            'intro': 'pre-release นี้เน้นแก้ Smart Optimize ของ KataGo, one-click setup, การจำ default engine และกล่องข้อความให้อ่านง่ายใน classic theme',
            'updates_heading': 'ไฮไลต์ของเวอร์ชันนี้',
            'updates': [
                'Smart Optimize ยังใช้ benchmark ทางการของ KataGo แต่เพิ่ม heartbeat ของ progress ในช่วงท้ายที่เงียบ ทำให้ 88% ไม่ดูเหมือนค้าง',
                'one-click setup รองรับการนำเข้า custom weight แล้ว การดาวน์โหลด weight และการ apply weight แยกจากกัน ผู้ใช้เก็บหลาย weight ในเครื่องแล้วเลือกใช้ได้',
                'แก้การจำ default engine/weight หลังรีสตาร์ต ไม่กลับไปแถวแรกหรือ auto setup และการเลื่อนลำดับยังคง default ที่ตั้งไว้',
                'ค่า “จำกัดความยาว variation” ตั้งต้นเป็น 15 และส่งเข้า KataGo เป็น `analysisPVLen` ทำให้ small board มุมขวาล่างใช้ค่านี้จริง',
                'กล่องข้อความทั่วไปใน classic theme ใช้พื้นหลังที่อ่านง่าย ไม่เป็นกล่องดำเมื่อกดเมนูเกี่ยวกับ engine ก่อนโหลดเสร็จ',
                'หน้าต่าง one-click setup บน Windows high DPI ไม่ยืดหลุดจอ และปุ่ม import/apply/download เห็นครบ',
                'ขอบคุณ @qiyi71w สำหรับ PR #17 ซึ่งผ่าน isolated package compile แล้วแต่จะรีวิวแยก และขอบคุณ @tosuhun-sys สำหรับ issue #14 ที่ปิดแล้ว',
            ],
            'before_heading': 'ก่อนดาวน์โหลด ดูตรงนี้ก่อน',
            'before': [
                f'ผู้ใช้ Windows ส่วนใหญ่ให้ลอง {assets["windows_opencl_portable"]} ก่อน',
                f'ถ้า OpenCL ไม่เสถียร ให้ใช้ {assets["windows_portable"]}',
                f'ผู้ใช้ NVIDIA GPU แนะนำ {assets["windows_nvidia_portable"]}',
                'นี่คือ pre-release สำหรับผู้ใช้ที่ช่วยทดสอบ benchmark progress, one-click setup และ default-engine persistence ได้',
                'ผ่าน `mvn test`, `mvn -DskipTests package` ในเครื่อง และทดสอบ GUI จริงด้วย Windows OpenCL portable แล้ว',
            ],
            'download_heading': 'แนะนำการดาวน์โหลด',
            'download_headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
            'download_labels': shared_download_labels['th'],
            'why_heading': 'ทำไมเวอร์ชันนี้ควรทดสอบ',
            'why': [
                'benchmark จะไม่รู้สึกเหมือนค้างที่ 88%',
                'custom weight สามารถ import, เก็บในเครื่อง, เลือก และ apply ได้ชัดเจน',
                'default engine และ weight จะไม่ถูก startup repair เขียนทับ',
                'ค่าความยาว variation ใน UI ตรงกับพารามิเตอร์ KataGo จริง',
            ],
            'contact_heading': 'ติดต่อ',
            'contact': ['QQ group: `299419120`'],
        },
    ]

    sections: list[dict[str, object]] = []
    for block in content:
        language = str(block['language'])
        localized_assets = assets_cn if language in ('中文', '繁體中文') else assets
        sections.append(
            {
                'language': language,
                'intro': block['intro'],
                'updates': {'heading': block['updates_heading'], 'items': block['updates']},
                'before': {'heading': block['before_heading'], 'items': block['before']},
                'download': {
                    'heading': block['download_heading'],
                    'headers': block['download_headers'],
                    'rows': download_rows(block['download_labels'], localized_assets),
                },
                'why': {'heading': block['why_heading'], 'items': block['why']},
                'contact': {'heading': block['contact_heading'], 'items': block['contact']},
            }
        )

    add_nvidia50_download_rows(sections, assets_cn, assets)
    validate_release_sections(sections)
    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_05_26_1_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {key: format_asset(asset_map[key], repo, release_tag) for key in asset_map}
    assets = {key: format_asset_en(asset_map[key], repo, release_tag) for key in asset_map}
    katago_version = bundle['katago_version']
    model_source = bundle['model_source']

    localized_sections = [
        {
            'language': '中文',
            'labels': 'zh',
            'intro': (
                '这一版是 TensorRT 与棋谱加载稳定性复测版。重点不是换一个“模型名字”，而是把 TensorRT 作为后端加速路径处理好：'
                '用户看到的权重/模型显示名继续保留真实权重，例如 `zhizi 28B muonfd2`，不会被盖成 `KataGo TensorRT`。'
            ),
            'updates_heading': '本版主要更新',
            'updates': [
                '修复“加载棋谱后快速刷新胜率曲线”设置项在综合设置里和原有选项重叠的问题，位置已移到更合适的空位。',
                '保留棋谱加载后的快速胜率曲线功能默认开启；不想自动刷新时，可在设置里关闭。',
                '修复测试配置对象会把临时 TensorRT/CUDA 路径写进真实用户配置的风险，测试写入现在只会落到测试工作目录。',
                '重新从本机卸载 TensorRT 已安装目录后，按应用自己的 TensorRT 安装路径重新解压、启用并验证。',
                '重新运行 KataGo 官方 benchmark，TensorRT 路径完成测速并写回推荐线程数。',
                '继续确认 TensorRT 是后端/加速方式，不覆盖用户看到的真实权重/模型显示名。',
            ],
            'before_heading': '下载前先看这几句',
            'before': [
                f'主推荐整合包继续内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
                'Windows 普通用户优先下载 OpenCL 免安装版；NVIDIA 用户可下载 NVIDIA/RTX 50 CUDA 版，再在软件内按需启用 TensorRT。',
                'TensorRT 不是新的权重名；如果你使用 zhizi 28B muonfd2，界面应该继续显示这个模型/权重身份。',
                '如果打开旧棋谱时不想立刻刷新快速胜率曲线，可以到综合设置里关闭“加载棋谱后快速刷新胜率曲线”。',
                '本次发布前做了全量单元测试、JDK 17 真机启动烟测、TensorRT 卸载后重装、TensorRT 官方 benchmark 复测。',
            ],
            'download_heading': '下载建议',
            'download_headers': ('你的电脑', '直接下载这个'),
            'why_heading': '这一版为什么值得更新',
            'why': [
                '棋谱加载后卡在刷新胜率曲线的关键路径已由回归测试覆盖，避免再让 UI 被重复刷新拖住。',
                '新增设置项现在不再挤占原有界面，综合设置页可读性恢复正常。',
                'TensorRT 安装链路经过“本机无已安装 TensorRT”的真实状态验证，不只是单元测试模拟。',
                '官方 benchmark 已在 TensorRT 引擎上跑完，推荐线程数会写入配置，后续启动直接使用。',
                '测试隔离修复后，本地测试不会再污染 `C:\\Users\\Public\\Documents\\LizzieYzyNext\\config.txt` 这类真实用户配置。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': '繁體中文',
            'labels': 'zh_hant',
            'intro': (
                '這一版是 TensorRT 與棋譜載入穩定性複測版。重點不是換一個「模型名稱」，而是把 TensorRT 當作後端加速路徑處理好：'
                '使用者看到的權重/模型顯示名會保留真實權重，例如 `zhizi 28B muonfd2`，不會被蓋成 `KataGo TensorRT`。'
            ),
            'updates_heading': '本版主要更新',
            'updates': [
                '修復「載入棋譜後快速刷新勝率曲線」設定項在綜合設定裡與原有選項重疊的問題，位置已移到更合適的空位。',
                '保留棋譜載入後的快速勝率曲線功能預設開啟；不想自動刷新時，可在設定裡關閉。',
                '修復測試設定物件可能把臨時 TensorRT/CUDA 路徑寫進真實使用者設定的風險，測試寫入現在只會落到測試工作目錄。',
                '重新從本機卸載 TensorRT 已安裝目錄後，按應用程式自己的 TensorRT 安裝路徑重新解壓、啟用並驗證。',
                '重新執行 KataGo 官方 benchmark，TensorRT 路徑完成測速並寫回推薦執行緒數。',
                '繼續確認 TensorRT 是後端/加速方式，不覆蓋使用者看到的真實權重/模型顯示名。',
            ],
            'before_heading': '下載前先看這幾句',
            'before': [
                f'主推薦整合包繼續內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
                'Windows 一般使用者優先下載 OpenCL 免安裝版；NVIDIA 使用者可下載 NVIDIA/RTX 50 CUDA 版，再在軟體內按需啟用 TensorRT。',
                'TensorRT 不是新的權重名稱；如果你使用 zhizi 28B muonfd2，介面應該繼續顯示這個模型/權重身份。',
                '如果開啟舊棋譜時不想立刻刷新快速勝率曲線，可以到綜合設定裡關閉「載入棋譜後快速刷新勝率曲線」。',
                '本次發布前做了完整單元測試、JDK 17 真機啟動煙測、TensorRT 卸載後重裝、TensorRT 官方 benchmark 複測。',
            ],
            'download_heading': '下載建議',
            'download_headers': ('你的電腦', '直接下載這個'),
            'why_heading': '這一版為什麼值得更新',
            'why': [
                '棋譜載入後卡在刷新勝率曲線的關鍵路徑已由回歸測試覆蓋，避免 UI 再被重複刷新拖住。',
                '新增設定項現在不再擠占原有介面，綜合設定頁可讀性恢復正常。',
                'TensorRT 安裝鏈路經過「本機沒有已安裝 TensorRT」的真實狀態驗證，不只是單元測試模擬。',
                '官方 benchmark 已在 TensorRT 引擎上跑完，推薦執行緒數會寫入設定，後續啟動直接使用。',
                '測試隔離修復後，本機測試不會再污染 `C:\\Users\\Public\\Documents\\LizzieYzyNext\\config.txt` 這類真實使用者設定。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': 'English',
            'labels': 'en',
            'intro': (
                'This is a TensorRT and kifu-load stability retest release. TensorRT is treated as an acceleration backend, '
                'not as the user-visible model name: real weight/model labels such as `zhizi 28B muonfd2` stay visible instead of being replaced by `KataGo TensorRT`.'
            ),
            'updates_heading': 'Release Highlights',
            'updates': [
                'Moved the new “quick winrate curve refresh after loading kifu” setting so it no longer overlaps existing controls in General Settings.',
                'The quick winrate curve after loading kifu remains enabled by default; users who do not want it can turn it off in settings.',
                'Fixed test configuration isolation so temporary TensorRT/CUDA paths cannot be written into the real user config.',
                'Uninstalled the local TensorRT runtime directories, then reinstalled and enabled TensorRT through the app path on the real machine.',
                'Reran the official KataGo benchmark on the TensorRT engine and saved the recommended thread count.',
                'Reconfirmed that TensorRT is backend acceleration and does not overwrite the real user-visible weight/model name.',
            ],
            'before_heading': 'Read Before Downloading',
            'before': [
                f'The recommended bundles continue to include KataGo `{katago_version}` and the default weight `{model_source}`.',
                'Most Windows users should start with the no-install OpenCL build; NVIDIA users can use the NVIDIA / RTX 50 CUDA builds and enable TensorRT on demand inside the app.',
                'TensorRT is not a new weight name. If you use zhizi 28B muonfd2, the UI should keep showing that model/weight identity.',
                'If you do not want old kifu files to refresh the quick winrate curve immediately after loading, disable the option in General Settings.',
                'Before release, I reran the full unit suite, a JDK 17 real launch smoke test, TensorRT uninstall-and-reinstall, and the official TensorRT benchmark.',
            ],
            'download_heading': 'Download Guide',
            'download_headers': ('Your computer', 'Download this file'),
            'why_heading': 'Why Update',
            'why': [
                'The kifu-load path that could stall at winrate curve refresh is now covered by regression tests.',
                'The new setting no longer crowds the settings UI, so the General Settings tab is readable again.',
                'The TensorRT install path was verified from a real “not installed” machine state, not only through mocked tests.',
                'The official benchmark completed on TensorRT and saved the recommended thread count for later launches.',
                'Test isolation now prevents local tests from polluting real user files such as `C:\\Users\\Public\\Documents\\LizzieYzyNext\\config.txt`.',
            ],
            'contact_heading': 'Contact',
            'contact': ['QQ group: `299419120`'],
        },
        {
            'language': '日本語',
            'labels': 'ja',
            'intro': (
                'このリリースは TensorRT と棋譜読み込み安定性の再テスト版です。TensorRT はユーザーに見えるモデル名ではなく、'
                'あくまで高速化バックエンドとして扱います。`zhizi 28B muonfd2` のような実際の重み/モデル名は `KataGo TensorRT` に置き換えられません。'
            ),
            'updates_heading': '主な更新',
            'updates': [
                '「棋譜読み込み後に勝率曲線を高速更新」設定の位置を移動し、総合設定内の既存項目と重ならないようにしました。',
                '棋譜読み込み後の高速勝率曲線は既定で有効のままです。不要な場合は設定で無効化できます。',
                'テスト設定の隔離を修正し、一時的な TensorRT/CUDA パスが実ユーザー設定へ書き込まれないようにしました。',
                'ローカル TensorRT のインストール済みディレクトリを外した状態から、アプリの手順で再インストール、適用、検証しました。',
                'TensorRT エンジンで KataGo 公式 benchmark を再実行し、推奨スレッド数を書き戻しました。',
                'TensorRT はバックエンド高速化であり、実際の重み/モデル表示名を上書きしないことを再確認しました。',
            ],
            'before_heading': 'ダウンロード前に',
            'before': [
                f'推奨バンドルには引き続き KataGo `{katago_version}` と既定の重み `{model_source}` が含まれます。',
                'Windows の多くのユーザーは OpenCL のインストール不要版から始めてください。NVIDIA ユーザーは NVIDIA / RTX 50 CUDA 版を使い、アプリ内で必要時に TensorRT を有効化できます。',
                'TensorRT は新しい重み名ではありません。zhizi 28B muonfd2 を使っている場合、UI はそのモデル/重み名を表示し続けるべきです。',
                '古い棋譜を開いた直後に高速勝率曲線を更新したくない場合は、総合設定でこのオプションを無効化してください。',
                'リリース前に full unit test、JDK 17 実起動 smoke、TensorRT uninstall/reinstall、公式 TensorRT benchmark を再実行しました。',
            ],
            'download_heading': 'ダウンロード案内',
            'download_headers': ('お使いの環境', 'ダウンロードするファイル'),
            'why_heading': '更新する理由',
            'why': [
                '棋譜読み込み後に勝率曲線更新で止まる可能性があった経路を regression test でカバーしました。',
                '新しい設定項目が既存 UI を圧迫しなくなり、総合設定タブが読みやすくなりました。',
                'TensorRT インストール経路は、実際に「未インストール」状態から検証しました。',
                '公式 benchmark は TensorRT 上で完了し、推奨スレッド数が次回起動用に保存されます。',
                'テスト隔離により、`C:\\Users\\Public\\Documents\\LizzieYzyNext\\config.txt` のような実ユーザー設定をローカルテストが汚さなくなりました。',
            ],
            'contact_heading': '連絡先',
            'contact': ['QQ グループ: `299419120`'],
        },
        {
            'language': '한국어',
            'labels': 'ko',
            'intro': (
                '이번 릴리스는 TensorRT 와 기보 로딩 안정성을 다시 검증한 버전입니다. TensorRT 는 사용자에게 보이는 모델명이 아니라 '
                '가속 백엔드로만 취급합니다. `zhizi 28B muonfd2` 같은 실제 가중치/모델 표시는 `KataGo TensorRT` 로 덮어쓰지 않습니다.'
            ),
            'updates_heading': '주요 업데이트',
            'updates': [
                '“기보 로딩 후 빠른 승률 곡선 새로고침” 설정 위치를 옮겨 General Settings 의 기존 항목과 겹치지 않게 했습니다.',
                '기보 로딩 후 빠른 승률 곡선은 기본값으로 계속 켜져 있습니다. 원하지 않으면 설정에서 끌 수 있습니다.',
                '테스트 설정 격리를 고쳐 임시 TensorRT/CUDA 경로가 실제 사용자 설정에 쓰이지 않게 했습니다.',
                '로컬 TensorRT 설치 디렉터리를 제거한 상태에서 앱 경로로 TensorRT 를 다시 설치, 적용, 검증했습니다.',
                'TensorRT 엔진에서 KataGo 공식 benchmark 를 다시 실행하고 추천 스레드 수를 저장했습니다.',
                'TensorRT 는 백엔드 가속이며 실제 사용자 표시용 가중치/모델 이름을 덮어쓰지 않는다는 점을 다시 확인했습니다.',
            ],
            'before_heading': '다운로드 전 확인',
            'before': [
                f'추천 번들은 계속 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 를 포함합니다.',
                '대부분의 Windows 사용자는 OpenCL 무설치 빌드부터 쓰면 됩니다. NVIDIA 사용자는 NVIDIA / RTX 50 CUDA 빌드를 사용하고 앱 안에서 필요할 때 TensorRT 를 켤 수 있습니다.',
                'TensorRT 는 새로운 가중치 이름이 아닙니다. zhizi 28B muonfd2 를 사용하면 UI 는 그 모델/가중치 정체성을 계속 보여야 합니다.',
                '오래된 기보를 열 때 빠른 승률 곡선을 즉시 새로고침하고 싶지 않다면 General Settings 에서 옵션을 끄세요.',
                '릴리스 전 full unit test, JDK 17 실제 실행 smoke, TensorRT uninstall/reinstall, 공식 TensorRT benchmark 를 다시 수행했습니다.',
            ],
            'download_heading': '다운로드 안내',
            'download_headers': ('내 컴퓨터', '다운로드할 파일'),
            'why_heading': '업데이트할 이유',
            'why': [
                '기보 로딩 후 승률 곡선 새로고침에서 멈출 수 있던 경로를 regression test 로 덮었습니다.',
                '새 설정 항목이 더 이상 설정 UI 를 밀어내지 않아 General Settings 탭이 다시 읽기 쉬워졌습니다.',
                'TensorRT 설치 경로는 실제 “설치되지 않음” 상태에서 검증했습니다.',
                '공식 benchmark 가 TensorRT 에서 완료되었고 추천 스레드 수가 이후 실행을 위해 저장됩니다.',
                '테스트 격리 수정으로 로컬 테스트가 `C:\\Users\\Public\\Documents\\LizzieYzyNext\\config.txt` 같은 실제 사용자 파일을 오염시키지 않습니다.',
            ],
            'contact_heading': '연락처',
            'contact': ['QQ 그룹: `299419120`'],
        },
        {
            'language': 'ภาษาไทย',
            'labels': 'th',
            'intro': (
                'รีลีสนี้เป็นรอบทดสอบซ้ำเรื่อง TensorRT และความเสถียรตอนโหลด kifu โดย TensorRT ถูกใช้เป็น backend เร่งความเร็ว '
                'ไม่ใช่ชื่อโมเดลที่ผู้ใช้เห็น ชื่อ weight/model จริงเช่น `zhizi 28B muonfd2` จะยังแสดงตามเดิม ไม่ถูกแทนด้วย `KataGo TensorRT`'
            ),
            'updates_heading': 'ไฮไลต์ของเวอร์ชันนี้',
            'updates': [
                'ย้ายตำแหน่งตัวเลือก “รีเฟรชกราฟ winrate อย่างรวดเร็วหลังโหลด kifu” เพื่อไม่ให้ทับกับตัวเลือกเดิมใน General Settings',
                'ฟังก์ชันกราฟ winrate หลังโหลด kifu ยังเปิดเป็นค่าเริ่มต้น ผู้ใช้ที่ไม่ต้องการสามารถปิดได้ใน Settings',
                'แก้การแยก config ของ test เพื่อไม่ให้ path ชั่วคราวของ TensorRT/CUDA ถูกเขียนเข้า config จริงของผู้ใช้',
                'ถอน TensorRT ที่ติดตั้งไว้ในเครื่องออกก่อน แล้วติดตั้งและเปิดใช้ใหม่ผ่านเส้นทางของแอปจริง',
                'รัน KataGo official benchmark บน TensorRT engine อีกครั้ง และบันทึกจำนวน thread ที่แนะนำ',
                'ยืนยันซ้ำว่า TensorRT เป็น backend acceleration และไม่เขียนทับชื่อ weight/model จริงที่ผู้ใช้เห็น',
            ],
            'before_heading': 'ก่อนดาวน์โหลด ดูตรงนี้ก่อน',
            'before': [
                f'แพ็กเกจแนะนำยังรวม KataGo `{katago_version}` และ weight เริ่มต้น `{model_source}` ไว้ให้แล้ว',
                'ผู้ใช้ Windows ส่วนใหญ่เริ่มจาก OpenCL แบบไม่ต้องติดตั้งได้ ส่วนผู้ใช้ NVIDIA ใช้ NVIDIA / RTX 50 CUDA build แล้วเปิด TensorRT ในแอปเมื่อต้องการ',
                'TensorRT ไม่ใช่ชื่อ weight ใหม่ ถ้าคุณใช้ zhizi 28B muonfd2 หน้าจอควรยังแสดงตัวตนของ model/weight นั้น',
                'ถ้าไม่ต้องการให้ kifu เก่ารีเฟรชกราฟ winrate ทันทีหลังโหลด ให้ปิดตัวเลือกนี้ใน General Settings',
                'ก่อนปล่อยเวอร์ชันนี้ ได้รัน full unit test, JDK 17 launch smoke บนเครื่องจริง, TensorRT uninstall/reinstall และ official TensorRT benchmark แล้ว',
            ],
            'download_heading': 'แนะนำการดาวน์โหลด',
            'download_headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
            'why_heading': 'ทำไมควรอัปเดต',
            'why': [
                'เส้นทางโหลด kifu ที่อาจค้างตอนรีเฟรชกราฟ winrate มี regression test ครอบคลุมแล้ว',
                'ตัวเลือกใหม่ไม่เบียด UI เดิม ทำให้แท็บ General Settings อ่านง่ายขึ้น',
                'เส้นทางติดตั้ง TensorRT ถูกตรวจจากสถานะจริงที่ “ยังไม่ได้ติดตั้ง” ไม่ใช่แค่ mock test',
                'official benchmark ทำงานสำเร็จบน TensorRT และบันทึก thread ที่แนะนำไว้ใช้ตอนเปิดครั้งต่อไป',
                'การแยก test config ช่วยป้องกันไม่ให้ local test ทำให้ไฟล์จริงอย่าง `C:\\Users\\Public\\Documents\\LizzieYzyNext\\config.txt` ปนเปื้อน',
            ],
            'contact_heading': 'ติดต่อ',
            'contact': ['QQ group: `299419120`'],
        },
    ]

    sections: list[dict[str, object]] = []
    for block in localized_sections:
        language = str(block['language'])
        labels_key = str(block['labels'])
        localized_assets = assets_cn if language in ('中文', '繁體中文') else assets
        sections.append(
            {
                'language': language,
                'intro': block['intro'],
                'updates': {'heading': block['updates_heading'], 'items': block['updates']},
                'before': {'heading': block['before_heading'], 'items': block['before']},
                'download': {
                    'heading': block['download_heading'],
                    'headers': block['download_headers'],
                    'rows': standard_download_rows(
                        STANDARD_DOWNLOAD_LABELS[labels_key],
                        localized_assets,
                    ),
                },
                'why': {'heading': block['why_heading'], 'items': block['why']},
                'contact': {'heading': block['contact_heading'], 'items': block['contact']},
            }
        )

    add_nvidia50_download_rows(sections, assets_cn, assets)
    validate_release_sections(sections)
    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_05_30_1_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {
        key: format_asset(asset_map[key], repo, release_tag)
        for key in asset_map
    }
    assets = {
        key: format_asset_en(asset_map[key], repo, release_tag)
        for key in asset_map
    }
    katago_version = bundle['katago_version']
    model_source = bundle['model_source']
    localized_sections: list[dict[str, object]] = [
        {
            'language': '中文',
            'labels': 'zh',
            'intro': (
                '这是 `LizzieYzy Next` 的 2026-05-30 预览版，重点合并社区 PR 并修复同步、分析与预览交互。'
                '特别感谢 @semanym 与 @qiyi71w 的持续贡献：这一版补强了 ReadBoard 落子失败后的恢复、批量闪电分析设置、'
                '候选点表预览清理，以及 TensorRT 一键设置的 NVIDIA GPU 检测。'
            ),
            'updates_heading': '本版主要更新',
            'updates': [
                '合并 PR #34：修复 ReadBoard 错点/漏点后本地引擎与远端棋盘不同步的问题。',
                'Windows 发布包内置 readboard 固定升级到 `qiyi71w/readboard v3.0.6`，匹配新的同步恢复协议。',
                '合并 PR #33：完善批量闪电分析设置与启动体验，让批量分析入口更稳。',
                '合并 PR #35：修复候选点表预览状态泄漏，鼠标回到棋盘候选点或点击变化图节点时会清理旧 PV 预览。',
                '合并 PR #31：KataGo 一键设置新增 NVIDIA GPU / Compute Capability 检测，TensorRT 仍为软件内按需安装，不进入巨大 release 包。',
                '继续保留 Windows 免安装包自包含数据目录，解压即用，配置和 TensorRT 下载数据优先留在解压目录内。',
            ],
            'before_heading': '下载前先看这几句',
            'before': [
                f'Windows 普通用户优先下载 {assets_cn["windows_opencl_portable"]}，这是 **OpenCL 版（推荐，免安装）**。',
                f'如果 OpenCL 在你的电脑上不稳定，再改用 {assets_cn["windows_portable"]}。',
                f'如果你的电脑是 **NVIDIA 显卡**，优先下载 {assets_cn["windows_nvidia_portable"]}；RTX 5070/5080/5090 可优先试 RTX 50 CUDA 版。',
                'TensorRT 不再作为巨大 GitHub Release 包发布；需要时到软件内 `KataGo 一键设置` 按需安装。',
                f'整合包继续内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
            ],
            'download_heading': '下载建议',
            'download_headers': ('你的电脑', '直接下载这个'),
            'why_heading': '这一版为什么值得先测',
            'why': [
                'ReadBoard 同步恢复、候选点预览、批量分析和 TensorRT 推荐逻辑都属于高频真实使用路径。',
                '本版感谢并合并社区贡献，同时补上发布包依赖版本，避免“代码修了但包里工具没跟上”。',
                '发布前已跑全量测试、专项测试、打包，并在本机真实启动应用做 UI 冒烟。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': '繁體中文',
            'labels': 'zh_hant',
            'intro': (
                '這是 `LizzieYzy Next` 的 2026-05-30 預覽版，重點是合併社群 PR 並修復同步、分析與預覽互動。'
                '特別感謝 @semanym 與 @qiyi71w 的持續貢獻：這一版補強 ReadBoard 落子失敗後的恢復、批量閃電分析設定、'
                '候選點表預覽清理，以及 TensorRT 一鍵設定的 NVIDIA GPU 偵測。'
            ),
            'updates_heading': '本版主要更新',
            'updates': [
                '合併 PR #34：修復 ReadBoard 錯點/漏點後本地引擎與遠端棋盤不同步的問題。',
                'Windows 發布包內建 readboard 固定升級到 `qiyi71w/readboard v3.0.6`，匹配新的同步恢復協議。',
                '合併 PR #33：完善批量閃電分析設定與啟動體驗，讓批量分析入口更穩。',
                '合併 PR #35：修復候選點表預覽狀態洩漏，滑鼠回到棋盤候選點或點擊變化圖節點時會清理舊 PV 預覽。',
                '合併 PR #31：KataGo 一鍵設定新增 NVIDIA GPU / Compute Capability 偵測，TensorRT 仍為軟體內按需安裝，不進入巨大 release 包。',
                'Windows 免安裝包繼續保持自包含資料目錄，解壓即用，設定和 TensorRT 下載資料優先留在解壓目錄內。',
            ],
            'before_heading': '下載前先看這幾句',
            'before': [
                f'Windows 一般使用者優先下載 {assets_cn["windows_opencl_portable"]}，這是 **OpenCL 版（推薦，免安裝）**。',
                f'如果 OpenCL 在你的電腦上不穩定，再改用 {assets_cn["windows_portable"]}。',
                f'如果你的電腦是 **NVIDIA 顯示卡**，優先下載 {assets_cn["windows_nvidia_portable"]}；RTX 5070/5080/5090 可優先試 RTX 50 CUDA 版。',
                'TensorRT 不再作為巨大 GitHub Release 包發布；需要時到軟體內 `KataGo 一鍵設定` 按需安裝。',
                f'整合包繼續內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
            ],
            'download_heading': '下載建議',
            'download_headers': ('你的電腦', '直接下載這個'),
            'why_heading': '這一版為什麼值得先測',
            'why': [
                'ReadBoard 同步恢復、候選點預覽、批量分析和 TensorRT 推薦邏輯都屬於高頻真實使用路徑。',
                '本版感謝並合併社群貢獻，同時補上發布包依賴版本，避免「程式修了但包裡工具沒跟上」。',
                '發布前已跑完整測試、專項測試、打包，並在本機真實啟動應用做 UI 冒煙。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': 'English',
            'labels': 'en',
            'intro': (
                'This is the 2026-05-30 preview build of `LizzieYzy Next`, focused on community PRs and sync, analysis, and preview stability. '
                'Special thanks to @semanym and @qiyi71w for the continued work: this release improves ReadBoard recovery after failed local moves, '
                'batch lightning analysis settings, candidate-table preview cleanup, and NVIDIA GPU detection for TensorRT auto setup.'
            ),
            'updates_heading': 'Release Highlights',
            'updates': [
                'Merged PR #34: fixes ReadBoard desync after wrong/missed local placements.',
                'Pinned the bundled Windows readboard to `qiyi71w/readboard v3.0.6`, matching the new sync recovery protocol.',
                'Merged PR #33: improves batch lightning analysis settings and startup experience.',
                'Merged PR #35: clears stale candidate-table PV preview when returning to board candidates or clicking variation-tree nodes.',
                'Merged PR #31: adds NVIDIA GPU / Compute Capability detection to KataGo Auto Setup while keeping TensorRT as an optional in-app install, not a huge release asset.',
                'Keeps Windows portable builds self-contained, with config and TensorRT download data staying inside the extracted folder first.',
            ],
            'before_heading': 'Read Before Downloading',
            'before': [
                f'Most Windows users should download {assets["windows_opencl_portable"]}, the **recommended no-install OpenCL build**.',
                f'If OpenCL is unreliable on your PC, use {assets["windows_portable"]} instead.',
                f'If your PC has an **NVIDIA GPU**, try {assets["windows_nvidia_portable"]}; RTX 5070/5080/5090 users can try the RTX 50 CUDA build first.',
                'TensorRT is no longer shipped as a giant GitHub Release package; install it on demand from the in-app `KataGo Auto Setup` when needed.',
                f'The bundled packages continue to include KataGo `{katago_version}` and default weight `{model_source}`.',
            ],
            'download_heading': 'Download Guide',
            'download_headers': ('Your computer', 'Download this file'),
            'why_heading': 'Why This Preview Is Worth Testing',
            'why': [
                'ReadBoard recovery, candidate previews, batch analysis, and TensorRT recommendations are all frequent real-world paths.',
                'This preview merges community fixes and updates the packaged dependency version so the release assets match the code.',
                'Before publishing, full tests, targeted tests, packaging, and a real local UI launch smoke test were rerun.',
            ],
            'contact_heading': 'Contact',
            'contact': ['QQ group: `299419120`'],
        },
        {
            'language': '日本語',
            'labels': 'ja',
            'intro': (
                'これは `LizzieYzy Next` の 2026-05-30 プレビュー版です。コミュニティ PR を取り込み、同期・分析・プレビュー操作の安定性を高めました。'
                '@semanym さんと @qiyi71w さんの継続的な貢献に感謝します。この版では ReadBoard の失敗手後の復旧、'
                'batch lightning analysis 設定、候補手表のプレビュー解除、TensorRT 自動設定向け NVIDIA GPU 検出を改善しています。'
            ),
            'updates_heading': '主な更新',
            'updates': [
                'PR #34 をマージ：ReadBoard で誤クリック/漏れた着手の後にローカルエンジンと遠隔碁盤がずれる問題を修正。',
                'Windows 同梱 readboard を `qiyi71w/readboard v3.0.6` に固定し、新しい同期復旧プロトコルに合わせました。',
                'PR #33 をマージ：batch lightning analysis の設定と開始体験を改善。',
                'PR #35 をマージ：候補手表の古い PV プレビューが残る問題を修正。',
                'PR #31 をマージ：KataGo 自動設定に NVIDIA GPU / Compute Capability 検出を追加。TensorRT は巨大な release asset ではなく、アプリ内の任意インストールのままです。',
                'Windows portable build は引き続き自己完結型で、設定と TensorRT ダウンロードデータは展開フォルダ内を優先します。',
            ],
            'before_heading': 'ダウンロード前に',
            'before': [
                f'多くの Windows ユーザーには {assets["windows_opencl_portable"]}、つまり **OpenCL 推奨・インストール不要版** をおすすめします。',
                f'OpenCL が不安定な場合は {assets["windows_portable"]} を使ってください。',
                f'**NVIDIA GPU** 搭載 PC では {assets["windows_nvidia_portable"]} を試してください。RTX 5070/5080/5090 は RTX 50 CUDA 版も候補です。',
                'TensorRT は巨大な GitHub Release パッケージとして配布しません。必要な場合はアプリ内の `KataGo 自動設定` から任意でインストールしてください。',
                f'同梱パッケージには KataGo `{katago_version}` と既定の重み `{model_source}` が含まれます。',
            ],
            'download_heading': 'ダウンロード案内',
            'download_headers': ('環境', 'ダウンロードするファイル'),
            'why_heading': 'このプレビューを試す理由',
            'why': [
                'ReadBoard 復旧、候補手プレビュー、batch analysis、TensorRT 推奨は、どれも実使用でよく通る経路です。',
                'コミュニティ修正を取り込み、同梱依存のバージョンもコードに合わせました。',
                '公開前に full test、targeted test、package、実機ローカル UI 起動 smoke を再実行しました。',
            ],
            'contact_heading': '連絡先',
            'contact': ['QQ グループ：`299419120`'],
        },
        {
            'language': '한국어',
            'labels': 'ko',
            'intro': (
                '`LizzieYzy Next` 2026-05-30 프리뷰 빌드입니다. 커뮤니티 PR 을 반영하고 동기화, 분석, 미리보기 상호작용 안정성을 개선했습니다. '
                '@semanym 님과 @qiyi71w 님의 지속적인 기여에 감사드립니다. 이번 릴리스는 ReadBoard 실패 착수 복구, '
                'batch lightning analysis 설정, 후보수 표 미리보기 정리, TensorRT 자동 설정용 NVIDIA GPU 감지를 보강합니다.'
            ),
            'updates_heading': '주요 변경',
            'updates': [
                'PR #34 병합: ReadBoard 오착/누락 후 로컬 엔진과 원격 바둑판이 어긋나는 문제를 수정했습니다.',
                'Windows 내장 readboard 를 `qiyi71w/readboard v3.0.6` 으로 고정해 새 동기화 복구 프로토콜과 맞췄습니다.',
                'PR #33 병합: batch lightning analysis 설정과 시작 경험을 개선했습니다.',
                'PR #35 병합: 후보수 표의 오래된 PV 미리보기 상태가 남는 문제를 정리했습니다.',
                'PR #31 병합: KataGo Auto Setup 에 NVIDIA GPU / Compute Capability 감지를 추가했습니다. TensorRT 는 거대한 release asset 이 아니라 앱 안에서 선택 설치합니다.',
                'Windows portable build 는 계속 self-contained 방식이며 설정과 TensorRT 다운로드 데이터는 우선 압축 해제 폴더 안에 둡니다.',
            ],
            'before_heading': '다운로드 전 안내',
            'before': [
                f'대부분의 Windows 사용자는 {assets["windows_opencl_portable"]}, 즉 **OpenCL 권장 무설치 빌드** 를 받으면 됩니다.',
                f'OpenCL 이 불안정하면 {assets["windows_portable"]} 를 사용하세요.',
                f'**NVIDIA GPU** PC 라면 {assets["windows_nvidia_portable"]} 를 먼저 시도하세요. RTX 5070/5080/5090 은 RTX 50 CUDA 빌드도 우선 후보입니다.',
                'TensorRT 는 더 이상 거대한 GitHub Release 패키지로 배포하지 않습니다. 필요할 때 앱 안의 `KataGo Auto Setup` 에서 설치하세요.',
                f'번들 패키지는 KataGo `{katago_version}` 와 기본 weight `{model_source}` 를 계속 포함합니다.',
            ],
            'download_heading': '다운로드 안내',
            'download_headers': ('사용 환경', '받을 파일'),
            'why_heading': '이 프리뷰를 테스트할 이유',
            'why': [
                'ReadBoard 복구, 후보수 미리보기, batch analysis, TensorRT 추천은 실제 사용에서 자주 거치는 경로입니다.',
                '커뮤니티 수정과 패키지 의존성 버전을 함께 맞춰 코드와 릴리스 자산의 괴리를 줄였습니다.',
                '공개 전 full test, targeted test, package, 실제 로컬 UI 실행 smoke 를 다시 수행했습니다.',
            ],
            'contact_heading': '연락',
            'contact': ['QQ 그룹: `299419120`'],
        },
        {
            'language': 'ภาษาไทย',
            'labels': 'th',
            'intro': (
                'นี่คือ build preview วันที่ 2026-05-30 ของ `LizzieYzy Next` เน้นรวม PR จากชุมชนและปรับความเสถียรของ sync, analysis, และ preview interaction '
                'ขอบคุณ @semanym และ @qiyi71w สำหรับการช่วยพัฒนาอย่างต่อเนื่อง รุ่นนี้ปรับปรุงการกู้คืน ReadBoard หลังวางหมากพลาด, '
                'batch lightning analysis settings, การล้าง preview ในตาราง candidate, และการตรวจ NVIDIA GPU สำหรับ TensorRT auto setup'
            ),
            'updates_heading': 'สิ่งที่อัปเดต',
            'updates': [
                'Merge PR #34: แก้ ReadBoard desync หลังคลิกผิดหรือหมากจากเครื่องมือ sync ตกหล่น',
                'Windows bundle pin readboard เป็น `qiyi71w/readboard v3.0.6` เพื่อให้ตรงกับ sync recovery protocol ใหม่',
                'Merge PR #33: ปรับ batch lightning analysis settings และประสบการณ์ตอนเริ่มทำงาน',
                'Merge PR #35: ล้าง PV preview เก่าจาก candidate table เมื่อกลับไป hover บนกระดานหรือคลิก variation tree',
                'Merge PR #31: เพิ่ม NVIDIA GPU / Compute Capability detection ใน KataGo Auto Setup โดย TensorRT ยังเป็นการติดตั้งในแอปแบบ on-demand ไม่ใช่ release asset ขนาดใหญ่',
                'Windows portable build ยังเป็น self-contained โดย config และข้อมูลดาวน์โหลด TensorRT จะอยู่ในโฟลเดอร์ที่แตกไฟล์ก่อน',
            ],
            'before_heading': 'อ่านก่อนดาวน์โหลด',
            'before': [
                f'ผู้ใช้ Windows ส่วนใหญ่ควรดาวน์โหลด {assets["windows_opencl_portable"]} ซึ่งเป็น **OpenCL build แบบไม่ต้องติดตั้งที่แนะนำ**',
                f'ถ้า OpenCL ไม่เสถียรบนเครื่องของคุณ ให้ใช้ {assets["windows_portable"]}',
                f'ถ้าเครื่องมี **NVIDIA GPU** ให้ลอง {assets["windows_nvidia_portable"]}; ผู้ใช้ RTX 5070/5080/5090 อาจลอง RTX 50 CUDA build ก่อน',
                'TensorRT จะไม่ถูกปล่อยเป็น GitHub Release package ขนาดใหญ่อีกต่อไป เมื่อต้องการให้ติดตั้งจาก `KataGo Auto Setup` ในแอป',
                f'Bundle ยังรวม KataGo `{katago_version}` และ default weight `{model_source}` ไว้ให้',
            ],
            'download_heading': 'คำแนะนำการดาวน์โหลด',
            'download_headers': ('เครื่องของคุณ', 'ไฟล์ที่ควรดาวน์โหลด'),
            'why_heading': 'ทำไม preview นี้น่าลอง',
            'why': [
                'ReadBoard recovery, candidate preview, batch analysis, และ TensorRT recommendation เป็น flow ที่ผู้ใช้เจอบ่อยจริง',
                'รุ่นนี้รวม community fixes และอัปเดต dependency ที่อยู่ใน package ให้ตรงกับ code',
                'ก่อน publish ได้รัน full test, targeted test, package และ real local UI launch smoke แล้ว',
            ],
            'contact_heading': 'ติดต่อ',
            'contact': ['QQ group: `299419120`'],
        },
    ]
    sections: list[dict[str, object]] = []
    for block in localized_sections:
        language = str(block['language'])
        labels_key = str(block['labels'])
        localized_assets = assets_cn if language in ('中文', '繁體中文') else assets
        sections.append(
            {
                'language': language,
                'intro': block['intro'],
                'updates': {'heading': block['updates_heading'], 'items': block['updates']},
                'before': {'heading': block['before_heading'], 'items': block['before']},
                'download': {
                    'heading': block['download_heading'],
                    'headers': block['download_headers'],
                    'rows': standard_download_rows(
                        STANDARD_DOWNLOAD_LABELS[labels_key],
                        localized_assets,
                    ),
                },
                'why': {'heading': block['why_heading'], 'items': block['why']},
                'contact': {'heading': block['contact_heading'], 'items': block['contact']},
            }
        )

    add_nvidia50_download_rows(sections, assets_cn, assets)
    validate_release_sections(sections)
    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_05_30_2_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    notes = build_next_2026_05_30_1_notes(asset_map, bundle, repo, release_tag)
    replacements = {
        '2026-05-30 预览版': '2026-05-30 正式复测版',
        '这一版为什么值得先测': '这一版为什么可以放心更新',
        '2026-05-30 預覽版': '2026-05-30 正式複測版',
        '這一版為什麼值得先測': '這一版為什麼可以放心更新',
        '2026-05-30 preview build': '2026-05-30 stable retest release',
        'Why This Preview Is Worth Testing': 'Why This Release Is Ready',
        'This preview merges community fixes': 'This release merges community fixes',
        '2026-05-30 プレビュー版': '2026-05-30 正式リテスト版',
        'このプレビューを試す理由': 'このリリースで更新する理由',
        '2026-05-30 프리뷰 빌드': '2026-05-30 정식 재검증 릴리스',
        '이 프리뷰를 테스트할 이유': '이 릴리스를 업데이트할 이유',
        'build preview วันที่ 2026-05-30': 'stable retest release วันที่ 2026-05-30',
        'ทำไม preview นี้น่าลอง': 'ทำไม release นี้พร้อมใช้งาน',
    }
    for old, new in replacements.items():
        notes = notes.replace(old, new)
    return notes


def build_next_2026_05_31_1_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {key: format_asset(asset_map[key], repo, release_tag) for key in asset_map}
    assets = {key: format_asset_en(asset_map[key], repo, release_tag) for key in asset_map}
    katago_version = bundle['katago_version']
    model_source = bundle['model_source']

    localized_sections = [
        {
            'language': '中文',
            'labels': 'zh',
            'intro': (
                '这是 TensorRT 下载体验修复版。TensorRT 仍然是应用内按需安装的 NVIDIA 后端加速方式；'
                '本版重点解决国内下载 NVIDIA TensorRT/CUDA/cuDNN 运行库时速度不稳定的问题。'
            ),
            'updates_heading': '本版主要更新',
            'updates': [
                '安装 TensorRT 前会自动对 `developer.download.nvidia.cn` 和 `developer.download.nvidia.com` 做小分段测速，再选择当前网络更快的官方 NVIDIA 下载源。',
                '测速只读取 TensorRT 官方运行库前 512 KiB 的 Range 数据，时间短、流量小，不会提前下载完整 1.8GB 大包。',
                '测速胜出的域名会统一用于 CUDA 12.8、cuDNN 9 和 TensorRT 10.9 运行库下载；KataGo TensorRT 本体仍按原来的 GitHub 官方发布包下载。',
                '任意一侧测速失败时会自动降级到可用的一侧；两侧都失败时保守回到 `.com`，避免安装流程卡死。',
                '下载前新增“正在测速 NVIDIA 下载源...”进度提示，不要求用户理解代理、TUN 或 DIRECT 规则。',
                '继续保留 SHA-256 / 文件大小校验，测速只决定下载域名，不降低包完整性检查标准。',
            ],
            'before_heading': '下载前先看这几句',
            'before': [
                f'主推荐整合包继续内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
                'Windows 普通用户优先下载 OpenCL 免安装版；NVIDIA 用户可下载 NVIDIA/RTX 50 CUDA 版，再在软件内按需安装 TensorRT。',
                'TensorRT 不会作为巨大的 GitHub release asset 直接打包进安装包，仍然在一键设置中按需下载。',
                '如果你的网络里 `.cn` 更快，安装器会自动选 `.cn`；如果 `.com` 更快，也会自动选 `.com`。',
                '这版不要求用户手动配置 Clash、TUN 或代理规则，程序按实际下载速度自己判断。',
            ],
            'download_heading': '下载建议',
            'download_headers': ('你的电脑', '直接下载这个'),
            'why_heading': '这一版为什么值得更新',
            'why': [
                '国内用户点击安装 TensorRT 时，不再固定走单一 NVIDIA 下载域名，遇到慢源会自动换到更快的官方源。',
                '测速发生在真正下载大运行库之前，能避免用户等了很久才发现当前域名很慢。',
                '这次修改只影响 NVIDIA 官方运行库下载源选择，不改变模型名、权重显示、TensorRT 后端启用逻辑。',
                '发布前已重新跑 TensorRT 相关单元测试、全量单元测试和 Maven package。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': '繁體中文',
            'labels': 'zh_hant',
            'intro': (
                '這是 TensorRT 下載體驗修正版。TensorRT 仍然是應用程式內按需安裝的 NVIDIA 後端加速方式；'
                '本版重點改善下載 NVIDIA TensorRT/CUDA/cuDNN 執行庫時速度不穩的問題。'
            ),
            'updates_heading': '本版主要更新',
            'updates': [
                '安裝 TensorRT 前會自動對 `developer.download.nvidia.cn` 和 `developer.download.nvidia.com` 做小分段測速，再選擇目前網路更快的官方 NVIDIA 下載源。',
                '測速只讀取 TensorRT 官方執行庫前 512 KiB 的 Range 資料，時間短、流量小，不會提前下載完整 1.8GB 大包。',
                '測速勝出的網域會統一用於 CUDA 12.8、cuDNN 9 和 TensorRT 10.9 執行庫下載；KataGo TensorRT 本體仍按原 GitHub 官方發布包下載。',
                '任一側測速失敗時會自動降級到可用的一側；兩側都失敗時保守回到 `.com`，避免安裝流程卡住。',
                '下載前新增「正在測速 NVIDIA 下載源...」進度提示，不要求使用者理解代理、TUN 或 DIRECT 規則。',
                '繼續保留 SHA-256 / 檔案大小校驗，測速只決定下載網域，不降低完整性檢查標準。',
            ],
            'before_heading': '下載前先看這幾句',
            'before': [
                f'主推薦整合包繼續內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
                'Windows 一般使用者優先下載 OpenCL 免安裝版；NVIDIA 使用者可下載 NVIDIA/RTX 50 CUDA 版，再在軟體內按需安裝 TensorRT。',
                'TensorRT 不會作為巨大的 GitHub release asset 直接打包進安裝包，仍然在一鍵設定中按需下載。',
                '如果你的網路裡 `.cn` 更快，安裝器會自動選 `.cn`；如果 `.com` 更快，也會自動選 `.com`。',
                '這版不要求使用者手動配置 Clash、TUN 或代理規則，程式會按實際下載速度自行判斷。',
            ],
            'download_heading': '下載建議',
            'download_headers': ('你的電腦', '直接下載這個'),
            'why_heading': '這一版為什麼值得更新',
            'why': [
                '下載 TensorRT 時不再固定走單一 NVIDIA 下載網域，遇到慢源會自動換到更快的官方源。',
                '測速發生在真正下載大型執行庫之前，能避免等很久才發現目前網域很慢。',
                '這次修改只影響 NVIDIA 官方執行庫下載源選擇，不改變模型名、權重顯示、TensorRT 後端啟用邏輯。',
                '發布前已重新跑 TensorRT 相關單元測試、完整單元測試和 Maven package。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': 'English',
            'labels': 'en',
            'intro': (
                'This is a TensorRT download-experience fix. TensorRT remains an optional in-app NVIDIA backend acceleration path; '
                'this build improves the reliability of downloading the NVIDIA TensorRT/CUDA/cuDNN runtime packages.'
            ),
            'updates_heading': 'Release Highlights',
            'updates': [
                'Before installing TensorRT, the app now probes both `developer.download.nvidia.cn` and `developer.download.nvidia.com`, then chooses the faster official NVIDIA download host for the current network.',
                'The probe reads only the first 512 KiB of the official TensorRT runtime via an HTTP Range request, so it is quick and does not pre-download the full 1.8GB package.',
                'The winning host is reused for CUDA 12.8, cuDNN 9, and TensorRT 10.9 runtime downloads; the KataGo TensorRT binary still comes from its original GitHub release asset.',
                'If one host fails, the installer falls back to the working host; if both fail, it conservatively falls back to `.com` instead of stalling.',
                'A new “Testing NVIDIA download mirrors...” progress message appears before the large runtime downloads begin.',
                'SHA-256 and size checks remain in place: the probe only chooses the host and does not weaken package integrity verification.',
            ],
            'before_heading': 'Read Before Downloading',
            'before': [
                f'The recommended bundles continue to include KataGo `{katago_version}` and the default weight `{model_source}`.',
                'Most Windows users should start with the no-install OpenCL build; NVIDIA users can use the NVIDIA / RTX 50 CUDA builds and install TensorRT on demand inside the app.',
                'TensorRT is not bundled as a giant GitHub release asset; it is still downloaded only when requested from KataGo Auto Setup.',
                'If `.cn` is faster on your network, the installer chooses `.cn`; if `.com` is faster, it chooses `.com`.',
                'Users do not need to configure Clash, TUN, proxy, or DIRECT rules for this release path.',
            ],
            'download_heading': 'Download Guide',
            'download_headers': ('Your computer', 'Download this file'),
            'why_heading': 'Why Update',
            'why': [
                'TensorRT installation no longer depends on one fixed NVIDIA download host, so slow routes can be avoided automatically.',
                'The speed choice happens before the large runtime downloads, reducing the chance of waiting a long time on a bad host.',
                'The change only affects NVIDIA runtime download host selection; model names, weight display, and TensorRT backend activation are unchanged.',
                'Before release, TensorRT-focused tests, the full unit suite, and Maven package were rerun.',
            ],
            'contact_heading': 'Contact',
            'contact': ['QQ group: `299419120`'],
        },
        {
            'language': '日本語',
            'labels': 'ja',
            'intro': (
                'これは TensorRT ダウンロード体験の修正版です。TensorRT は引き続きアプリ内で任意インストールする NVIDIA 高速化バックエンドであり、'
                '本リリースでは NVIDIA TensorRT/CUDA/cuDNN ランタイムのダウンロード安定性を改善しました。'
            ),
            'updates_heading': '主な更新',
            'updates': [
                'TensorRT インストール前に `developer.download.nvidia.cn` と `developer.download.nvidia.com` の小さな分割ダウンロードを測定し、現在のネットワークで速い公式 NVIDIA ホストを選びます。',
                '測定は公式 TensorRT ランタイム先頭 512 KiB だけを HTTP Range で読み取るため短時間で終わり、1.8GB 全体を事前ダウンロードしません。',
                '選ばれたホストは CUDA 12.8、cuDNN 9、TensorRT 10.9 ランタイムのダウンロードに使われます。KataGo TensorRT 本体は従来どおり GitHub release asset から取得します。',
                '片方のホストが失敗した場合は動作する側へ自動フォールバックし、両方失敗した場合は保守的に `.com` へ戻します。',
                '大きなランタイムをダウンロードする前に “Testing NVIDIA download mirrors...” の進捗メッセージを表示します。',
                'SHA-256 とサイズ検証は継続します。測定はホスト選択だけで、パッケージ完全性チェックは弱めません。',
            ],
            'before_heading': 'ダウンロード前に',
            'before': [
                f'推奨バンドルには引き続き KataGo `{katago_version}` と既定の重み `{model_source}` が含まれます。',
                'Windows の多くのユーザーは OpenCL のインストール不要版から始めてください。NVIDIA ユーザーは NVIDIA / RTX 50 CUDA 版を使い、アプリ内で必要時に TensorRT をインストールできます。',
                'TensorRT は巨大な GitHub release asset として同梱されず、KataGo 自動設定から必要時にだけダウンロードします。',
                'あなたのネットワークで `.cn` が速ければ `.cn`、`.com` が速ければ `.com` を自動で選びます。',
                'このリリースでは Clash、TUN、proxy、DIRECT ルールの手動設定は不要です。',
            ],
            'download_heading': 'ダウンロード案内',
            'download_headers': ('お使いの環境', 'ダウンロードするファイル'),
            'why_heading': '更新する理由',
            'why': [
                'TensorRT インストールが単一の NVIDIA ダウンロードホストに固定されず、遅い経路を自動で避けられます。',
                '大きなランタイムを落とす前に速度を選ぶため、遅いホストで長時間待つ可能性を減らします。',
                '変更対象は NVIDIA ランタイムのホスト選択のみで、モデル名、重み表示、TensorRT バックエンド有効化の挙動は変わりません。',
                'リリース前に TensorRT 関連テスト、full unit suite、Maven package を再実行しました。',
            ],
            'contact_heading': '連絡先',
            'contact': ['QQ グループ: `299419120`'],
        },
        {
            'language': '한국어',
            'labels': 'ko',
            'intro': (
                '이번 버전은 TensorRT 다운로드 경험을 고친 릴리스입니다. TensorRT 는 계속 앱 안에서 선택 설치하는 NVIDIA 백엔드 가속 경로이며, '
                '이번 빌드는 NVIDIA TensorRT/CUDA/cuDNN 런타임 다운로드 안정성을 개선합니다.'
            ),
            'updates_heading': '주요 업데이트',
            'updates': [
                'TensorRT 설치 전에 `developer.download.nvidia.cn` 과 `developer.download.nvidia.com` 을 작은 Range 다운로드로 측정하고, 현재 네트워크에서 더 빠른 공식 NVIDIA 다운로드 호스트를 선택합니다.',
                '측정은 공식 TensorRT 런타임의 처음 512 KiB 만 읽으므로 빠르게 끝나며, 1.8GB 전체 패키지를 미리 다운로드하지 않습니다.',
                '선택된 호스트는 CUDA 12.8, cuDNN 9, TensorRT 10.9 런타임 다운로드에 함께 사용됩니다. KataGo TensorRT 본체는 기존 GitHub release asset 에서 받습니다.',
                '한쪽 호스트가 실패하면 동작하는 쪽으로 자동 전환하고, 둘 다 실패하면 보수적으로 `.com` 으로 돌아가 설치 흐름이 멈추지 않게 합니다.',
                '대형 런타임 다운로드 전에 “Testing NVIDIA download mirrors...” 진행 메시지를 표시합니다.',
                'SHA-256 과 파일 크기 검증은 그대로 유지됩니다. 측정은 호스트 선택만 담당하며 패키지 무결성 기준은 낮추지 않습니다.',
            ],
            'before_heading': '다운로드 전 확인',
            'before': [
                f'추천 번들은 계속 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 를 포함합니다.',
                '대부분의 Windows 사용자는 OpenCL 무설치 빌드부터 쓰면 됩니다. NVIDIA 사용자는 NVIDIA / RTX 50 CUDA 빌드에서 앱 안의 TensorRT 설치를 사용할 수 있습니다.',
                'TensorRT 는 거대한 GitHub release asset 으로 포함되지 않고, KataGo 자동 설정에서 요청할 때만 다운로드됩니다.',
                '내 네트워크에서 `.cn` 이 빠르면 `.cn`, `.com` 이 빠르면 `.com` 을 자동으로 선택합니다.',
                '이 릴리스 경로에서는 Clash, TUN, proxy, DIRECT 규칙을 사용자가 직접 설정할 필요가 없습니다.',
            ],
            'download_heading': '다운로드 안내',
            'download_headers': ('내 컴퓨터', '다운로드할 파일'),
            'why_heading': '업데이트할 이유',
            'why': [
                'TensorRT 설치가 하나의 NVIDIA 다운로드 호스트에 고정되지 않아 느린 경로를 자동으로 피할 수 있습니다.',
                '대형 런타임을 받기 전에 속도를 선택하므로 느린 호스트에서 오래 기다릴 가능성을 줄입니다.',
                '이번 변경은 NVIDIA 런타임 다운로드 호스트 선택에만 영향을 주며, 모델명, 가중치 표시, TensorRT 백엔드 활성화는 바뀌지 않습니다.',
                '릴리스 전에 TensorRT 관련 테스트, 전체 단위 테스트, Maven package 를 다시 수행했습니다.',
            ],
            'contact_heading': '연락처',
            'contact': ['QQ 그룹: `299419120`'],
        },
        {
            'language': 'ภาษาไทย',
            'labels': 'th',
            'intro': (
                'รีลีสนี้แก้ประสบการณ์ดาวน์โหลด TensorRT โดย TensorRT ยังเป็น backend เร่งความเร็วของ NVIDIA ที่ติดตั้งจากในแอปเมื่อผู้ใช้ต้องการ '
                'รุ่นนี้ปรับให้การดาวน์โหลด NVIDIA TensorRT/CUDA/cuDNN runtime เสถียรกว่าเดิม'
            ),
            'updates_heading': 'ไฮไลต์ของเวอร์ชันนี้',
            'updates': [
                'ก่อนติดตั้ง TensorRT แอปจะ probe ทั้ง `developer.download.nvidia.cn` และ `developer.download.nvidia.com` แล้วเลือก host ทางการของ NVIDIA ที่เร็วกว่าในเครือข่ายปัจจุบัน',
                'การ probe อ่านแค่ 512 KiB แรกของ TensorRT runtime ทางการผ่าน HTTP Range จึงเร็วและไม่ดาวน์โหลดแพ็กเกจเต็ม 1.8GB ล่วงหน้า',
                'host ที่ชนะจะถูกใช้กับ CUDA 12.8, cuDNN 9 และ TensorRT 10.9 runtime ส่วน KataGo TensorRT binary ยังดาวน์โหลดจาก GitHub release asset เดิม',
                'ถ้า host ฝั่งหนึ่งล้มเหลว จะ fallback ไปอีกฝั่งที่ใช้ได้ ถ้าล้มเหลวทั้งคู่จะกลับไปใช้ `.com` แบบปลอดภัยเพื่อไม่ให้ขั้นตอนติดตั้งค้าง',
                'เพิ่มข้อความ progress “Testing NVIDIA download mirrors...” ก่อนเริ่มดาวน์โหลด runtime ขนาดใหญ่',
                'ยังคงตรวจ SHA-256 และขนาดไฟล์เหมือนเดิม การ probe ใช้เลือก host เท่านั้น ไม่ลดมาตรฐาน integrity check',
            ],
            'before_heading': 'ก่อนดาวน์โหลด ดูตรงนี้ก่อน',
            'before': [
                f'แพ็กเกจแนะนำยังรวม KataGo `{katago_version}` และ weight เริ่มต้น `{model_source}` ไว้ให้แล้ว',
                'ผู้ใช้ Windows ส่วนใหญ่เริ่มจาก OpenCL แบบไม่ต้องติดตั้งได้ ส่วนผู้ใช้ NVIDIA ใช้ NVIDIA / RTX 50 CUDA build แล้วติดตั้ง TensorRT จากในแอปเมื่อต้องการ',
                'TensorRT ไม่ได้ถูกแนบเป็น GitHub release asset ขนาดใหญ่ แต่จะดาวน์โหลดเมื่อเรียกจาก KataGo Auto Setup เท่านั้น',
                'ถ้า `.cn` เร็วกว่าในเครือข่ายของคุณ ตัวติดตั้งจะเลือก `.cn`; ถ้า `.com` เร็วกว่า ก็จะเลือก `.com`',
                'ผู้ใช้ไม่ต้องตั้งค่า Clash, TUN, proxy หรือ DIRECT rule เองสำหรับเส้นทาง release นี้',
            ],
            'download_heading': 'แนะนำการดาวน์โหลด',
            'download_headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
            'why_heading': 'ทำไมควรอัปเดต',
            'why': [
                'การติดตั้ง TensorRT ไม่ผูกกับ NVIDIA download host เดียวอีกต่อไป จึงหลีกเลี่ยง route ที่ช้าได้อัตโนมัติ',
                'แอปเลือก host ก่อนดาวน์โหลด runtime ขนาดใหญ่ ลดโอกาสรอนานบน host ที่ช้า',
                'การเปลี่ยนแปลงนี้มีผลเฉพาะการเลือก host สำหรับ NVIDIA runtime ไม่กระทบชื่อโมเดล การแสดง weight หรือการเปิดใช้ TensorRT backend',
                'ก่อน release ได้รัน TensorRT-focused tests, full unit suite และ Maven package อีกครั้ง',
            ],
            'contact_heading': 'ติดต่อ',
            'contact': ['QQ group: `299419120`'],
        },
    ]

    sections: list[dict[str, object]] = []
    for block in localized_sections:
        language = str(block['language'])
        labels_key = str(block['labels'])
        localized_assets = assets_cn if language in ('中文', '繁體中文') else assets
        sections.append(
            {
                'language': language,
                'intro': block['intro'],
                'updates': {'heading': block['updates_heading'], 'items': block['updates']},
                'before': {'heading': block['before_heading'], 'items': block['before']},
                'download': {
                    'heading': block['download_heading'],
                    'headers': block['download_headers'],
                    'rows': standard_download_rows(
                        STANDARD_DOWNLOAD_LABELS[labels_key],
                        localized_assets,
                    ),
                },
                'why': {'heading': block['why_heading'], 'items': block['why']},
                'contact': {'heading': block['contact_heading'], 'items': block['contact']},
            }
        )

    add_nvidia50_download_rows(sections, assets_cn, assets)
    validate_release_sections(sections)
    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_05_31_2_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {key: format_asset(asset_map[key], repo, release_tag) for key in asset_map}
    assets = {key: format_asset_en(asset_map[key], repo, release_tag) for key in asset_map}
    katago_version = bundle['katago_version']
    model_source = bundle['model_source']

    localized_sections = [
        {
            'language': '中文',
            'labels': 'zh',
            'intro': (
                '这是 KataGo 线程数与换权重修复版。上一版已经修好 TensorRT 官方下载源测速；'
                '这一版重点补上更换权重后线程数设置没有重新应用的问题，让设置面板里的 numSearchThreads 和实际启动的引擎保持一致。'
            ),
            'updates_heading': '本版主要更新',
            'updates': [
                '修复“设置了 KataGo 搜索线程数后，更换权重/重载引擎后无法继续按新线程数生效”的问题。启用线程数设置后，重启后的 KataGo 会重新收到 `kata-set-param numSearchThreads`。',
                '手动启用线程数但没有勾“自动加载”时，现在换权重触发的引擎重启也会正确应用该线程数，不再只依赖自动加载开关。',
                '线程数为空或异常但设置已启用时，会按当前推荐值解析并写回配置，避免重载后发送空参数。',
                '修复预加载/双引擎场景中线程数命令可能发给全局主引擎的问题；现在命令会发给正在初始化的那个 KataGo 实例。',
                'TensorRT 仍然只是 NVIDIA 后端加速方式，不会覆盖用户看到的权重/模型名；上一版的 `.cn` / `.com` 官方下载源测速逻辑继续保留。',
                '新增线程数重载回归测试，覆盖手动线程数、自动加载线程数和非当前引擎初始化三条关键路径。',
            ],
            'before_heading': '下载前先看这几句',
            'before': [
                f'主推荐整合包继续内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
                '如果你改过“搜索线程数 numSearchThreads”，并且经常在 KataGo 一键设置里切换权重，建议更新这一版。',
                'Windows 普通用户优先下载 OpenCL 免安装版；NVIDIA 用户可下载 NVIDIA/RTX 50 CUDA 版，再在软件内按需安装 TensorRT。',
                'TensorRT 不作为巨大 release asset 直接打包，仍然在一键设置中按需安装；本版继续自动选择更快的 NVIDIA 官方下载域名。',
                '这一版不改变默认权重名、模型名显示或 TensorRT 启用入口，只修复线程数应用和引擎实例命令发送。'
            ],
            'download_heading': '下载建议',
            'download_headers': ('你的电脑', '直接下载这个'),
            'why_heading': '这一版为什么值得更新',
            'why': [
                '换权重会重启 KataGo；旧版在没有勾自动加载时，线程数设置可能留在界面里但没有真正下发给新引擎。',
                '修复后，无论是手动线程数还是测速推荐线程数，重载后的引擎都会按设置重新同步。',
                '预加载和双引擎用户也更稳，线程数命令不会误发到另一个引擎实例。',
                '发布前已跑新增线程数回归测试、KataGo/TensorRT 相关测试、全量单元测试和 Maven package。'
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': '繁體中文',
            'labels': 'zh_hant',
            'intro': (
                '這是 KataGo 執行緒數與切換權重修正版。上一版已修好 TensorRT 官方下載源測速；'
                '這一版重點補上切換權重後執行緒數設定沒有重新套用的問題，讓設定面板中的 numSearchThreads 和實際啟動的引擎保持一致。'
            ),
            'updates_heading': '本版主要更新',
            'updates': [
                '修復「設定 KataGo 搜尋執行緒數後，切換權重/重載引擎後無法繼續按新執行緒數生效」的問題。啟用執行緒數設定後，重啟的 KataGo 會重新收到 `kata-set-param numSearchThreads`。',
                '手動啟用執行緒數但沒有勾「自動載入」時，現在切換權重觸發的引擎重啟也會正確套用該執行緒數，不再只依賴自動載入開關。',
                '執行緒數為空或異常但設定已啟用時，會按目前推薦值解析並寫回設定，避免重載後送出空參數。',
                '修復預載/雙引擎場景中執行緒數命令可能送到全域主引擎的問題；現在命令會送給正在初始化的那個 KataGo 實例。',
                'TensorRT 仍然只是 NVIDIA 後端加速方式，不會覆蓋使用者看到的權重/模型名；上一版的 `.cn` / `.com` 官方下載源測速邏輯繼續保留。',
                '新增執行緒數重載回歸測試，覆蓋手動執行緒數、自動載入執行緒數和非目前引擎初始化三條關鍵路徑。',
            ],
            'before_heading': '下載前先看這幾句',
            'before': [
                f'主推薦整合包繼續內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
                '如果你改過「搜尋執行緒數 numSearchThreads」，並且常在 KataGo 一鍵設定中切換權重，建議更新這一版。',
                'Windows 一般使用者優先下載 OpenCL 免安裝版；NVIDIA 使用者可下載 NVIDIA/RTX 50 CUDA 版，再在軟體內按需安裝 TensorRT。',
                'TensorRT 不作為巨大 release asset 直接打包，仍然在一鍵設定中按需安裝；本版繼續自動選擇更快的 NVIDIA 官方下載網域。',
                '這一版不改變預設權重名、模型名顯示或 TensorRT 啟用入口，只修復執行緒數套用和引擎實例命令送出。',
            ],
            'download_heading': '下載建議',
            'download_headers': ('你的電腦', '直接下載這個'),
            'why_heading': '這一版為什麼值得更新',
            'why': [
                '切換權重會重啟 KataGo；舊版在沒有勾自動載入時，執行緒數設定可能留在介面裡但沒有真正下發給新引擎。',
                '修復後，無論是手動執行緒數還是測速推薦執行緒數，重載後的引擎都會按設定重新同步。',
                '預載和雙引擎使用者也更穩，執行緒數命令不會誤送到另一個引擎實例。',
                '發布前已跑新增執行緒數回歸測試、KataGo/TensorRT 相關測試、完整單元測試和 Maven package。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': 'English',
            'labels': 'en',
            'intro': (
                'This release fixes KataGo thread settings after weight changes. The previous build improved TensorRT official mirror probing; '
                'this build makes sure the `numSearchThreads` value shown in settings is also applied to the engine that starts after a weight reload.'
            ),
            'updates_heading': 'Release Highlights',
            'updates': [
                'Fixed a bug where changing KataGo weights or reloading the engine could leave a previously configured search thread count unapplied. When thread control is enabled, the restarted KataGo now receives `kata-set-param numSearchThreads` again.',
                'Manual thread settings now apply after a weight-triggered restart even when the separate auto-load checkbox is off.',
                'If the thread field is empty or invalid while thread control is enabled, the app resolves the recommended value and writes it back before sending it to KataGo.',
                'Fixed preloaded and dual-engine cases where the thread command could be sent to the global primary engine instead of the KataGo instance currently being initialized.',
                'TensorRT remains an NVIDIA backend acceleration path and does not replace the visible weight/model name; the previous `.cn` / `.com` official mirror probe remains in place.',
                'Added regression tests for manual thread settings, auto-loaded thread settings, and non-current engine initialization.',
            ],
            'before_heading': 'Read Before Downloading',
            'before': [
                f'The recommended bundles continue to include KataGo `{katago_version}` and the default weight `{model_source}`.',
                'If you changed `numSearchThreads` and often switch weights in KataGo Auto Setup, this is the build to update to.',
                'Most Windows users should start with the no-install OpenCL build; NVIDIA users can use the NVIDIA / RTX 50 CUDA builds and install TensorRT on demand inside the app.',
                'TensorRT is still installed on demand from KataGo Auto Setup rather than bundled as a giant release asset; this build keeps automatic NVIDIA official host selection.',
                'This release does not change default weight names, model display names, or the TensorRT enablement entry point; it fixes thread synchronization and command targeting.',
            ],
            'download_heading': 'Download Guide',
            'download_headers': ('Your computer', 'Download this file'),
            'why_heading': 'Why Update',
            'why': [
                'Changing weights restarts KataGo; older builds could keep the thread value visible in settings without actually applying it to the new engine when auto-load was off.',
                'After this fix, both manual thread counts and benchmark-recommended thread counts are synchronized to the reloaded engine.',
                'Preload and dual-engine setups are safer because the thread command is sent to the correct engine instance.',
                'Before release, the new thread regression tests, KataGo/TensorRT-focused tests, the full unit suite, and Maven package were rerun.',
            ],
            'contact_heading': 'Contact',
            'contact': ['QQ group: `299419120`'],
        },
        {
            'language': '日本語',
            'labels': 'ja',
            'intro': (
                'これは KataGo のスレッド数設定と重み切り替えに関する修正版です。前回のビルドでは TensorRT 公式ミラー測定を改善しました。'
                'このビルドでは、設定画面に表示される `numSearchThreads` が重み切り替え後に起動するエンジンへ確実に反映されるようにしました。'
            ),
            'updates_heading': '主な更新',
            'updates': [
                'KataGo の重みを切り替えた後、またはエンジンを再読み込みした後、設定済みの検索スレッド数が反映されないことがある問題を修正しました。スレッド設定が有効な場合、再起動した KataGo へ `kata-set-param numSearchThreads` を再送します。',
                '手動のスレッド設定は、別の自動読み込みチェックがオフでも、重み切り替えによる再起動後に適用されます。',
                'スレッド欄が空または不正な値でも設定が有効な場合、推奨値に解決してから設定へ書き戻し、KataGo に送ります。',
                'プリロードや dual-engine 環境で、スレッド命令が現在初期化中の KataGo ではなく global primary engine に送られる可能性を修正しました。',
                'TensorRT は引き続き NVIDIA バックエンド高速化経路であり、表示される重み/モデル名を置き換えません。前回追加した `.cn` / `.com` 公式ミラー測定も維持します。',
                '手動スレッド設定、自動読み込みスレッド設定、現在の主エンジンではない初期化経路をカバーする回帰テストを追加しました。',
            ],
            'before_heading': 'ダウンロード前に',
            'before': [
                f'推奨バンドルには引き続き KataGo `{katago_version}` と既定の重み `{model_source}` が含まれます。',
                '`numSearchThreads` を変更し、KataGo 自動設定でよく重みを切り替える場合は、このビルドへの更新をおすすめします。',
                'Windows の多くのユーザーは OpenCL のインストール不要版から始めてください。NVIDIA ユーザーは NVIDIA / RTX 50 CUDA 版を使い、アプリ内で必要時に TensorRT をインストールできます。',
                'TensorRT は巨大な release asset として同梱されず、KataGo 自動設定から必要時にだけインストールします。このビルドでも速い NVIDIA 公式ホストを自動選択します。',
                'このリリースは既定の重み名、モデル表示名、TensorRT 有効化入口を変更せず、スレッド同期とコマンド送信先だけを修正します。',
            ],
            'download_heading': 'ダウンロード案内',
            'download_headers': ('お使いの環境', 'ダウンロードするファイル'),
            'why_heading': '更新する理由',
            'why': [
                '重みを切り替えると KataGo は再起動します。旧ビルドでは自動読み込みがオフの場合、設定画面にスレッド数が残っていても新しいエンジンに反映されないことがありました。',
                '修正後は、手動スレッド数も benchmark 推奨スレッド数も、再読み込み後のエンジンに同期されます。',
                'プリロードや dual-engine の構成でも、スレッド命令が正しいエンジンインスタンスへ送られるためより安定します。',
                'リリース前に新しいスレッド回帰テスト、KataGo/TensorRT 関連テスト、full unit suite、Maven package を再実行しました。',
            ],
            'contact_heading': '連絡先',
            'contact': ['QQ グループ: `299419120`'],
        },
        {
            'language': '한국어',
            'labels': 'ko',
            'intro': (
                '이번 버전은 KataGo 스레드 수 설정과 가중치 전환 문제를 고친 릴리스입니다. 이전 빌드는 TensorRT 공식 미러 측정을 개선했고, '
                '이번 빌드는 설정 화면의 `numSearchThreads` 값이 가중치 전환 후 시작되는 엔진에도 실제로 적용되도록 했습니다.'
            ),
            'updates_heading': '주요 업데이트',
            'updates': [
                'KataGo 가중치를 바꾸거나 엔진을 다시 로드한 뒤, 기존에 설정한 검색 스레드 수가 적용되지 않을 수 있던 문제를 수정했습니다. 스레드 제어가 켜져 있으면 재시작한 KataGo 에 `kata-set-param numSearchThreads` 를 다시 보냅니다.',
                '별도의 자동 로드 체크박스가 꺼져 있어도, 수동 스레드 설정은 가중치 전환으로 인한 재시작 후 적용됩니다.',
                '스레드 입력값이 비어 있거나 잘못되어도 스레드 제어가 켜져 있으면 추천값으로 해석해 설정에 다시 저장한 뒤 KataGo 에 보냅니다.',
                '프리로드/듀얼 엔진 환경에서 스레드 명령이 현재 초기화 중인 KataGo 대신 전역 주 엔진으로 갈 수 있던 문제를 수정했습니다.',
                'TensorRT 는 계속 NVIDIA 백엔드 가속 경로이며, 사용자에게 보이는 가중치/모델명을 바꾸지 않습니다. 이전 `.cn` / `.com` 공식 미러 측정도 유지됩니다.',
                '수동 스레드 설정, 자동 로드 스레드 설정, 현재 주 엔진이 아닌 초기화 경로를 검증하는 회귀 테스트를 추가했습니다.',
            ],
            'before_heading': '다운로드 전 확인',
            'before': [
                f'추천 번들은 계속 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 를 포함합니다.',
                '`numSearchThreads` 를 바꾸고 KataGo 자동 설정에서 가중치를 자주 전환한다면 이 빌드로 업데이트하는 것을 권장합니다.',
                '대부분의 Windows 사용자는 OpenCL 무설치 빌드부터 쓰면 됩니다. NVIDIA 사용자는 NVIDIA / RTX 50 CUDA 빌드에서 앱 안의 TensorRT 설치를 사용할 수 있습니다.',
                'TensorRT 는 거대한 release asset 으로 포함되지 않고 KataGo 자동 설정에서 요청할 때만 설치됩니다. 이 빌드도 더 빠른 NVIDIA 공식 호스트를 자동 선택합니다.',
                '이번 릴리스는 기본 가중치 이름, 모델 표시 이름, TensorRT 활성화 진입점을 바꾸지 않고 스레드 동기화와 명령 대상만 수정합니다.',
            ],
            'download_heading': '다운로드 안내',
            'download_headers': ('내 컴퓨터', '다운로드할 파일'),
            'why_heading': '업데이트할 이유',
            'why': [
                '가중치를 바꾸면 KataGo 가 재시작됩니다. 이전 빌드는 자동 로드가 꺼져 있을 때 설정 화면의 스레드 값이 실제 새 엔진에 적용되지 않을 수 있었습니다.',
                '수정 후에는 수동 스레드 수와 benchmark 추천 스레드 수가 모두 재로드된 엔진에 다시 동기화됩니다.',
                '프리로드와 듀얼 엔진 구성에서도 스레드 명령이 올바른 엔진 인스턴스로 보내져 더 안정적입니다.',
                '릴리스 전에 신규 스레드 회귀 테스트, KataGo/TensorRT 관련 테스트, 전체 단위 테스트, Maven package 를 다시 수행했습니다.',
            ],
            'contact_heading': '연락처',
            'contact': ['QQ 그룹: `299419120`'],
        },
        {
            'language': 'ภาษาไทย',
            'labels': 'th',
            'intro': (
                'รีลีสนี้แก้ปัญหาค่า thread ของ KataGo หลังเปลี่ยน weight โดย build ก่อนหน้าได้ปรับการ probe mirror ทางการของ TensorRT แล้ว '
                'ส่วน build นี้ทำให้ค่า `numSearchThreads` ที่เห็นใน settings ถูกนำไปใช้กับ engine ที่เริ่มใหม่หลัง reload weight จริง ๆ'
            ),
            'updates_heading': 'ไฮไลต์ของเวอร์ชันนี้',
            'updates': [
                'แก้บั๊กที่หลังเปลี่ยน weight ของ KataGo หรือ reload engine แล้ว ค่า search thread ที่เคยตั้งไว้อาจไม่ถูกใช้ เมื่อเปิดใช้ thread control แล้ว KataGo ที่ restart จะได้รับ `kata-set-param numSearchThreads` อีกครั้ง',
                'ค่า thread แบบ manual จะถูกใช้หลัง restart จากการเปลี่ยน weight แม้ checkbox auto-load แยกต่างหากจะปิดอยู่',
                'ถ้าช่อง thread ว่างหรือไม่ถูกต้อง แต่ thread control เปิดอยู่ แอปจะ resolve เป็นค่าที่แนะนำและเขียนกลับลง config ก่อนส่งให้ KataGo',
                'แก้กรณี preload / dual-engine ที่คำสั่ง thread อาจถูกส่งไปยัง global primary engine แทน KataGo instance ที่กำลัง initialize อยู่',
                'TensorRT ยังเป็นทางเลือก acceleration backend ของ NVIDIA และไม่แทนที่ชื่อ weight/model ที่ผู้ใช้เห็น logic probe `.cn` / `.com` จากเวอร์ชันก่อนยังคงอยู่',
                'เพิ่ม regression tests สำหรับ manual thread settings, auto-loaded thread settings และการ initialize engine ที่ไม่ใช่ current primary engine',
            ],
            'before_heading': 'ก่อนดาวน์โหลด ดูตรงนี้ก่อน',
            'before': [
                f'แพ็กเกจแนะนำยังรวม KataGo `{katago_version}` และ weight เริ่มต้น `{model_source}` ไว้ให้แล้ว',
                'ถ้าคุณเคยเปลี่ยน `numSearchThreads` และสลับ weight บ่อยใน KataGo Auto Setup แนะนำให้อัปเดตเป็น build นี้',
                'ผู้ใช้ Windows ส่วนใหญ่เริ่มจาก OpenCL แบบไม่ต้องติดตั้งได้ ส่วนผู้ใช้ NVIDIA ใช้ NVIDIA / RTX 50 CUDA build แล้วติดตั้ง TensorRT จากในแอปเมื่อต้องการ',
                'TensorRT ยังไม่ได้ถูกแนบเป็น release asset ขนาดใหญ่ แต่ติดตั้งเมื่อเรียกจาก KataGo Auto Setup เท่านั้น และ build นี้ยังเลือก NVIDIA official host ที่เร็วกว่าโดยอัตโนมัติ',
                'รีลีสนี้ไม่เปลี่ยนชื่อ weight เริ่มต้น ชื่อ model ที่แสดง หรือทางเข้าเปิดใช้ TensorRT แต่แก้การ sync thread และ target ของคำสั่ง',
            ],
            'download_heading': 'แนะนำการดาวน์โหลด',
            'download_headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
            'why_heading': 'ทำไมควรอัปเดต',
            'why': [
                'การเปลี่ยน weight จะ restart KataGo ใน build เก่า ถ้า auto-load ปิดอยู่ ค่า thread อาจยังแสดงใน settings แต่ไม่ได้ถูกใช้กับ engine ใหม่',
                'หลังแก้แล้ว ทั้งค่า thread manual และค่า thread ที่ benchmark แนะนำจะ sync ไปยัง engine ที่ reload แล้ว',
                'setup แบบ preload และ dual-engine ปลอดภัยขึ้น เพราะคำสั่ง thread ถูกส่งไปยัง engine instance ที่ถูกต้อง',
                'ก่อน release ได้รัน thread regression tests ใหม่, KataGo/TensorRT-focused tests, full unit suite และ Maven package อีกครั้ง',
            ],
            'contact_heading': 'ติดต่อ',
            'contact': ['QQ group: `299419120`'],
        },
    ]

    sections: list[dict[str, object]] = []
    for block in localized_sections:
        language = str(block['language'])
        labels_key = str(block['labels'])
        localized_assets = assets_cn if language in ('中文', '繁體中文') else assets
        sections.append(
            {
                'language': language,
                'intro': block['intro'],
                'updates': {'heading': block['updates_heading'], 'items': block['updates']},
                'before': {'heading': block['before_heading'], 'items': block['before']},
                'download': {
                    'heading': block['download_heading'],
                    'headers': block['download_headers'],
                    'rows': standard_download_rows(
                        STANDARD_DOWNLOAD_LABELS[labels_key],
                        localized_assets,
                    ),
                },
                'why': {'heading': block['why_heading'], 'items': block['why']},
                'contact': {'heading': block['contact_heading'], 'items': block['contact']},
            }
        )

    add_nvidia50_download_rows(sections, assets_cn, assets)
    validate_release_sections(sections)
    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_06_01_1_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {key: format_asset(asset_map[key], repo, release_tag) for key in asset_map}
    assets = {key: format_asset_en(asset_map[key], repo, release_tag) for key in asset_map}
    katago_version = bundle['katago_version']
    model_source = bundle['model_source']
    sections: list[dict[str, object]] = [
        {
            'language': '中文',
            'intro': (
                '这是一次面向 Windows NVIDIA / TensorRT 用户的空间占用修复版。'
                '重点解决软件内一键安装 TensorRT 后，下载缓存继续占用 C 盘或运行目录空间的问题。'
                'TensorRT 仍然不作为巨大主包强制分发，普通用户继续通过 KataGo 一键设置按需安装。'
            ),
            'updates': {
                'heading': '本版主要更新',
                'items': [
                    'TensorRT 一键安装成功后会自动清理完整下载包缓存，避免安装包和已解压运行库重复占用数 GB 空间。',
                    'KataGo 一键设置新增“清理 TensorRT 缓存”按钮，可清理旧版本留下的下载缓存；已安装的 TensorRT 运行库和引擎不会被删除。',
                    '启动内置 NVIDIA / TensorRT KataGo 时，会尽量把 CUDA 缓存和 TensorRT 临时文件固定到软件自己的 `runtime/nvidia-runtime/cache`。',
                    '继续保留断点续传：下载失败或用户停止时 `.part` 会留下；完整安装成功后才清理完整安装包。',
                    'README 和发布包说明补充了免安装包、安装器版本、C 盘占用和 TensorRT 缓存路径的说明。',
                    '发布前已跑 TensorRT 缓存回归测试、完整单元测试、Maven package，并通过 GitHub release workflow 重新构建发布资产。',
                ],
            },
            'before': {
                'heading': '下载前先看这几句',
                'items': [
                    f'Windows 普通用户优先下载 {assets_cn["windows_opencl_portable"]}，这是 **OpenCL 版（推荐，免安装）**。',
                    f'如果你的电脑是 **NVIDIA 显卡**，优先下载 {assets_cn["windows_nvidia_portable"]}；RTX 5070/5080/5090 用户优先下载 RTX 50 CUDA 包。',
                    '想测试 TensorRT 的 RTX 20/30/40/50 用户，先下载对应 NVIDIA/CUDA 包，再在软件内一键安装 TensorRT。',
                    '已经安装过 TensorRT 的用户，如果旧版本留下了大体积下载缓存，可以打开 KataGo 一键设置，点击“清理 TensorRT 缓存”。',
                    f'主推荐整合包已内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
                    '如果非常在意 C 盘空间，Windows 用户优先使用免安装包并解压到非 C 盘。',
                ],
            },
            'download': {
                'heading': '下载建议',
                'headers': ('你的电脑', '直接下载这个'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['zh'], assets_cn),
            },
            'why': {
                'heading': '这一版为什么值得更新',
                'items': [
                    'TensorRT 官方运行库本身很大，这一版至少避免“安装包缓存 + 已解压文件”双份长期占用。',
                    '旧版本已经占用空间的用户，不需要手动找目录，软件内就能清理下载缓存。',
                    '免安装包继续把配置、权重、TRT 和运行缓存尽量放在解压目录内，更适合放到非 C 盘使用。',
                ],
            },
            'contact': {'heading': '交流', 'items': ['QQ 群：`299419120`']},
        },
        {
            'language': '繁體中文',
            'intro': (
                '這是針對 Windows NVIDIA / TensorRT 使用者的空間占用修正版。'
                '重點處理軟體內一鍵安裝 TensorRT 後，下載快取仍持續占用 C 槽或執行目錄空間的問題。'
                'TensorRT 仍不作為巨大主包強制發佈，一般使用者繼續透過 KataGo 一鍵設定按需安裝。'
            ),
            'updates': {
                'heading': '本版主要更新',
                'items': [
                    'TensorRT 一鍵安裝成功後會自動清理完整下載包快取，避免安裝包與已解壓執行庫重複占用數 GB 空間。',
                    'KataGo 一鍵設定新增「清理 TensorRT 快取」按鈕，可清理舊版本留下的下載快取；已安裝的 TensorRT 執行庫和引擎不會被刪除。',
                    '啟動內建 NVIDIA / TensorRT KataGo 時，會盡量把 CUDA 快取和 TensorRT 暫存檔固定到軟體自己的 `runtime/nvidia-runtime/cache`。',
                    '繼續保留斷點續傳：下載失敗或使用者停止時 `.part` 會留下；完整安裝成功後才清理完整安裝包。',
                    'README 和發布包說明補充了免安裝包、安裝器版本、C 槽占用和 TensorRT 快取路徑的說明。',
                    '發布前已跑 TensorRT 快取回歸測試、完整單元測試、Maven package，並通過 GitHub release workflow 重新建置發布資產。',
                ],
            },
            'before': {
                'heading': '下載前先看這幾句',
                'items': [
                    f'Windows 一般使用者優先下載 {assets_cn["windows_opencl_portable"]}，這是 **OpenCL 版（推薦，免安裝）**。',
                    f'如果你的電腦是 **NVIDIA 顯示卡**，優先下載 {assets_cn["windows_nvidia_portable"]}；RTX 5070/5080/5090 使用者優先下載 RTX 50 CUDA 包。',
                    '想測試 TensorRT 的 RTX 20/30/40/50 使用者，先下載對應 NVIDIA/CUDA 包，再在軟體內一鍵安裝 TensorRT。',
                    '已經安裝過 TensorRT 的使用者，如果舊版本留下大體積下載快取，可以開啟 KataGo 一鍵設定，點擊「清理 TensorRT 快取」。',
                    f'主推薦整合包已內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
                    '如果非常在意 C 槽空間，Windows 使用者優先使用免安裝包並解壓到非 C 槽。',
                ],
            },
            'download': {
                'heading': '下載建議',
                'headers': ('你的電腦', '直接下載這個'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['zh_hant'], assets_cn),
            },
            'why': {
                'heading': '這一版為什麼值得更新',
                'items': [
                    'TensorRT 官方執行庫本身很大，這一版至少避免「安裝包快取 + 已解壓檔案」雙份長期占用。',
                    '舊版本已經占用空間的使用者，不需要手動找目錄，軟體內就能清理下載快取。',
                    '免安裝包繼續把設定、權重、TRT 和執行快取盡量放在解壓目錄內，更適合放到非 C 槽使用。',
                ],
            },
            'contact': {'heading': '交流', 'items': ['QQ 群：`299419120`']},
        },
        {
            'language': 'English',
            'intro': (
                'This release focuses on Windows NVIDIA / TensorRT disk usage. '
                'It fixes the case where in-app TensorRT installation could leave large download archives on the C drive or in the runtime folder after setup. '
                'TensorRT remains an optional in-app install instead of a forced giant default package.'
            ),
            'updates': {
                'heading': 'Release Highlights',
                'items': [
                    'Successful TensorRT installs now remove completed installer archives, avoiding long-term double storage of archives plus extracted runtime files.',
                    'KataGo Auto Setup now includes a Clean TensorRT cache action for old leftover download caches; installed TensorRT runtime files and engines are kept.',
                    'Bundled NVIDIA / TensorRT KataGo launches now try to keep CUDA cache and TensorRT temporary files under `runtime/nvidia-runtime/cache`.',
                    'Resume support is preserved: interrupted downloads keep `.part` files, and completed archives are cleaned only after a successful install.',
                    'README and package docs now explain portable mode, installer runtime locations, C-drive usage, and TensorRT cache paths.',
                    'Before release, TensorRT cache regression tests, the full unit suite, Maven package, and GitHub release workflows were rerun.',
                ],
            },
            'before': {
                'heading': 'Read Before Downloading',
                'items': [
                    f'Most Windows users should download {assets["windows_opencl_portable"]}, the **recommended no-install OpenCL build**.',
                    f'If your PC has an **NVIDIA GPU**, start with {assets["windows_nvidia_portable"]}; RTX 5070/5080/5090 users should start with the RTX 50 CUDA package.',
                    'RTX 20/30/40/50 users who want TensorRT should download the matching NVIDIA/CUDA package first, then install TensorRT from inside the app.',
                    'If an older build already left a large TensorRT download cache, open KataGo Auto Setup and press Clean TensorRT cache.',
                    f'The recommended bundles include KataGo `{katago_version}` and the default weight `{model_source}`.',
                    'If C-drive space matters, prefer the Windows portable package and extract it to a non-C drive.',
                ],
            },
            'download': {
                'heading': 'Download Guide',
                'headers': ('Your computer', 'Download this file'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['en'], assets),
            },
            'why': {
                'heading': 'Why This Release Is Worth Updating',
                'items': [
                    'The official TensorRT runtime is large; this release avoids keeping both completed installer archives and extracted files indefinitely.',
                    'Users with existing cache bloat can clean it from the app instead of hunting through Windows folders.',
                    'Portable packages continue to keep settings, weights, TRT, and runtime caches inside the extracted folder where possible.',
                ],
            },
            'contact': {'heading': 'Contact', 'items': ['QQ group: `299419120`']},
        },
        {
            'language': '日本語',
            'intro': (
                'このリリースは Windows NVIDIA / TensorRT ユーザー向けのディスク使用量修正版です。'
                'アプリ内 TensorRT インストール後、大きなダウンロードアーカイブが C ドライブや runtime フォルダーに残る問題を改善します。'
                'TensorRT は引き続き巨大な既定パッケージではなく、アプリ内の任意インストールです。'
            ),
            'updates': {
                'heading': '主な更新',
                'items': [
                    'TensorRT インストール成功後、完了済みインストーラーアーカイブを自動削除し、アーカイブと展開済み実行ファイルの二重占有を減らしました。',
                    'KataGo 自動設定に Clean TensorRT cache 操作を追加し、旧バージョンのダウンロードキャッシュを削除できます。インストール済み runtime と engine は残ります。',
                    '内蔵 NVIDIA / TensorRT KataGo 起動時、CUDA cache と TensorRT 一時ファイルを `runtime/nvidia-runtime/cache` に寄せるようにしました。',
                    'レジューム対応は維持します。中断時は `.part` を残し、成功後にだけ完了済みアーカイブを清理します。',
                    'README と package docs に portable mode、installer の runtime 位置、C ドライブ使用、TensorRT cache path の説明を追加しました。',
                    'リリース前に TensorRT cache regression tests、full unit suite、Maven package、GitHub release workflow を再実行しました。',
                ],
            },
            'before': {
                'heading': 'ダウンロード前に',
                'items': [
                    f'多くの Windows ユーザーは {assets["windows_opencl_portable"]} を選ぶのがおすすめです。これは **推奨 OpenCL 版、インストール不要** です。',
                    f'**NVIDIA GPU** 搭載 PC では {assets["windows_nvidia_portable"]} を優先してください。RTX 5070/5080/5090 は RTX 50 CUDA パッケージから始めてください。',
                    'TensorRT を試したい RTX 20/30/40/50 ユーザーは、先に対応する NVIDIA/CUDA パッケージをダウンロードし、アプリ内で TensorRT をインストールしてください。',
                    '旧ビルドで大きな TensorRT download cache が残っている場合は、KataGo 自動設定で Clean TensorRT cache を押してください。',
                    f'推奨バンドルには KataGo `{katago_version}` と既定の重み `{model_source}` が含まれています。',
                    'C ドライブ容量を重視する場合、Windows portable package を非 C ドライブへ展開するのがおすすめです。',
                ],
            },
            'download': {
                'heading': 'ダウンロード案内',
                'headers': ('お使いの環境', 'ダウンロードするファイル'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['ja'], assets),
            },
            'why': {
                'heading': 'このリリースを更新する理由',
                'items': [
                    '公式 TensorRT runtime は大きいため、完了済みアーカイブと展開済みファイルを長期間二重に持たないようにしました。',
                    '既に cache が膨らんでいるユーザーも、Windows フォルダーを探さずアプリ内で清理できます。',
                    'Portable package は引き続き、設定、重み、TRT、runtime cache を可能な限り展開先フォルダー内に保持します。',
                ],
            },
            'contact': {'heading': '連絡先', 'items': ['QQ グループ: `299419120`']},
        },
        {
            'language': '한국어',
            'intro': (
                '이번 릴리스는 Windows NVIDIA / TensorRT 사용자의 디스크 사용량을 줄이는 수정판입니다. '
                '앱 안에서 TensorRT 를 설치한 뒤 큰 다운로드 archive 가 C 드라이브나 runtime 폴더에 남는 문제를 개선했습니다. '
                'TensorRT 는 계속 거대한 기본 패키지가 아니라 앱 안에서 선택 설치하는 방식입니다.'
            ),
            'updates': {
                'heading': '주요 업데이트',
                'items': [
                    'TensorRT 설치가 성공하면 완료된 installer archive 를 자동 삭제해 archive 와 압축 해제된 runtime 파일이 장기간 중복 저장되는 문제를 줄였습니다.',
                    'KataGo 자동 설정에 Clean TensorRT cache 동작을 추가했습니다. 예전 버전의 다운로드 cache 를 지워도 설치된 TensorRT runtime 과 engine 은 유지됩니다.',
                    '내장 NVIDIA / TensorRT KataGo 실행 시 CUDA cache 와 TensorRT temporary file 을 `runtime/nvidia-runtime/cache` 아래로 모으도록 했습니다.',
                    '이어받기는 유지됩니다. 중단된 다운로드는 `.part` 를 남기고, 설치가 완전히 성공한 뒤에만 완료된 archive 를 정리합니다.',
                    'README 와 package docs 에 portable mode, installer runtime 위치, C 드라이브 사용량, TensorRT cache path 설명을 보강했습니다.',
                    '릴리스 전에 TensorRT cache regression tests, full unit suite, Maven package, GitHub release workflow 를 다시 실행했습니다.',
                ],
            },
            'before': {
                'heading': '다운로드 전 확인',
                'items': [
                    f'대부분의 Windows 사용자는 {assets["windows_opencl_portable"]} 를 먼저 받으면 됩니다. 이는 **추천 OpenCL 무설치 빌드** 입니다.',
                    f'**NVIDIA GPU** 가 있다면 {assets["windows_nvidia_portable"]} 를 우선 사용하세요. RTX 5070/5080/5090 사용자는 RTX 50 CUDA 패키지부터 시작하세요.',
                    'TensorRT 를 테스트하려는 RTX 20/30/40/50 사용자는 먼저 해당 NVIDIA/CUDA 패키지를 받은 뒤 앱 안에서 TensorRT 를 설치하세요.',
                    '이전 빌드가 큰 TensorRT download cache 를 남겼다면 KataGo 자동 설정에서 Clean TensorRT cache 를 누르세요.',
                    f'추천 번들에는 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 가 포함되어 있습니다.',
                    'C 드라이브 공간이 중요하다면 Windows portable package 를 C 드라이브가 아닌 곳에 압축 해제하는 것이 좋습니다.',
                ],
            },
            'download': {
                'heading': '다운로드 안내',
                'headers': ('내 컴퓨터', '다운로드할 파일'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['ko'], assets),
            },
            'why': {
                'heading': '이번 릴리스를 업데이트할 이유',
                'items': [
                    '공식 TensorRT runtime 은 크기 때문에, 완료된 archive 와 압축 해제 파일을 계속 이중 보관하지 않도록 했습니다.',
                    '이미 cache 가 커진 사용자도 Windows 폴더를 직접 찾지 않고 앱 안에서 정리할 수 있습니다.',
                    'Portable package 는 설정, 가중치, TRT, runtime cache 를 가능한 한 압축 해제한 폴더 안에 유지합니다.',
                ],
            },
            'contact': {'heading': '연락처', 'items': ['QQ 그룹: `299419120`']},
        },
        {
            'language': 'ภาษาไทย',
            'intro': (
                'รีลีสนี้เน้นลดการใช้พื้นที่ของผู้ใช้ Windows NVIDIA / TensorRT '
                'แก้กรณีที่ติดตั้ง TensorRT จากในแอปแล้ว archive ดาวน์โหลดขนาดใหญ่ยังค้างอยู่ในไดรฟ์ C หรือ runtime folder '
                'TensorRT ยังคงเป็นการติดตั้งแบบเลือกเองในแอป ไม่ใช่แพ็กเกจหลักขนาดใหญ่ที่บังคับทุกคนดาวน์โหลด'
            ),
            'updates': {
                'heading': 'ไฮไลต์ของเวอร์ชันนี้',
                'items': [
                    'เมื่อติดตั้ง TensorRT สำเร็จแล้ว แอปจะลบ installer archive ที่ดาวน์โหลดครบแล้ว ลดการเก็บซ้ำระหว่าง archive และ runtime ที่แตกไฟล์แล้ว',
                    'KataGo Auto Setup เพิ่มปุ่ม Clean TensorRT cache สำหรับล้าง download cache จากเวอร์ชันเก่า โดยไม่ลบ TensorRT runtime และ engine ที่ติดตั้งแล้ว',
                    'เมื่อเปิด bundled NVIDIA / TensorRT KataGo แอปจะพยายามเก็บ CUDA cache และไฟล์ชั่วคราวของ TensorRT ไว้ใต้ `runtime/nvidia-runtime/cache`',
                    'ยังรองรับ resume เหมือนเดิม: download ที่หยุดกลางทางจะเก็บ `.part` ไว้ และจะลบ archive เต็มเมื่อ install สำเร็จแล้วเท่านั้น',
                    'README และ package docs เพิ่มคำอธิบาย portable mode, ตำแหน่ง runtime ของ installer, การใช้พื้นที่ไดรฟ์ C และ TensorRT cache path',
                    'ก่อน release ได้รัน TensorRT cache regression tests, full unit suite, Maven package และ GitHub release workflow อีกครั้ง',
                ],
            },
            'before': {
                'heading': 'ก่อนดาวน์โหลด ดูตรงนี้ก่อน',
                'items': [
                    f'ผู้ใช้ Windows ส่วนใหญ่แนะนำให้ดาวน์โหลด {assets["windows_opencl_portable"]} ซึ่งเป็น **OpenCL รุ่นแนะนำ แบบไม่ต้องติดตั้ง**',
                    f'ถ้าเครื่องมี **NVIDIA GPU** ให้เริ่มจาก {assets["windows_nvidia_portable"]}; ผู้ใช้ RTX 5070/5080/5090 ให้เริ่มจากแพ็กเกจ RTX 50 CUDA',
                    'ผู้ใช้ RTX 20/30/40/50 ที่อยากลอง TensorRT ให้ดาวน์โหลดแพ็กเกจ NVIDIA/CUDA ที่ตรงก่อน แล้วติดตั้ง TensorRT จากในแอป',
                    'ถ้า build เก่าเหลือ TensorRT download cache ขนาดใหญ่ ให้เปิด KataGo Auto Setup แล้วกด Clean TensorRT cache',
                    f'แพ็กเกจหลักมี KataGo `{katago_version}` และ weight เริ่มต้น `{model_source}` มาให้แล้ว',
                    'ถ้าพื้นที่ไดรฟ์ C สำคัญ แนะนำใช้ Windows portable package และแตกไฟล์ไปยังไดรฟ์อื่น',
                ],
            },
            'download': {
                'heading': 'แนะนำการดาวน์โหลด',
                'headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['th'], assets),
            },
            'why': {
                'heading': 'ทำไมเวอร์ชันนี้ควรอัปเดต',
                'items': [
                    'TensorRT runtime ทางการมีขนาดใหญ่ รีลีสนี้ช่วยไม่ให้เก็บ archive ที่ดาวน์โหลดครบแล้วและไฟล์ที่แตกแล้วซ้ำกันนาน ๆ',
                    'ผู้ใช้ที่มี cache ใหญ่จากเวอร์ชันเก่าสามารถล้างจากในแอปได้ ไม่ต้องค้นหาโฟลเดอร์ Windows เอง',
                    'Portable package ยังคงพยายามเก็บ settings, weights, TRT และ runtime cache ไว้ในโฟลเดอร์ที่แตกไฟล์เท่าที่ทำได้',
                ],
            },
            'contact': {'heading': 'ติดต่อ', 'items': ['QQ group: `299419120`']},
        },
    ]
    add_nvidia50_download_rows(sections, assets_cn, assets)
    add_tensorrt_split_download_row(sections, assets_cn, assets, asset_map)
    validate_release_sections(sections)
    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_06_01_2_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {key: format_asset(asset_map[key], repo, release_tag) for key in asset_map}
    assets = {key: format_asset_en(asset_map[key], repo, release_tag) for key in asset_map}
    katago_version = bundle['katago_version']
    model_source = bundle['model_source']
    content = [
        {
            'language': '中文',
            'labels': 'zh',
            'intro': '这一版是 KataGo 官方 `v1.16.5` 引擎跟进版，重点吸收 Windows、CUDA/TensorRT、分析引擎和 SGF 解析相关稳定性修复。',
            'updates_heading': '本版主要更新',
            'updates': [
                '内置 Windows / Linux KataGo 默认升级到官方 `v1.16.5`。',
                '软件内 TensorRT 一键安装使用 `katago-v1.16.5-trt10.9.0-cuda12.8-windows-x64.zip`，并更新官方 SHA256 校验。',
                'Windows 高级可选 TensorRT 分卷包也同步使用 KataGo `v1.16.5` TensorRT 引擎。',
                '继续保留上一版的 TensorRT 断点续传、安装后自动清理下载缓存、运行缓存尽量放入软件 runtime 目录。',
                '文档中的内置 KataGo 版本说明同步更新为 `v1.16.5`。',
            ],
            'before_heading': '下载前先看这几句',
            'before': [
                f'主推荐整合包已内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
                '这次不改变默认权重；重点是跟进官方引擎 bugfix 和兼容性修复。',
                'RTX 20/30/40/50 用户仍建议先下载 NVIDIA/CUDA 包，再在软件内按需安装 TensorRT。',
                'GTX 10 系及更老 NVIDIA 显卡继续优先 CUDA/OpenCL，不作为 TensorRT 推荐对象。',
            ],
            'download_heading': '下载建议',
            'download_headers': ('你的电脑', '直接下载这个'),
            'why_heading': '这一版为什么值得更新',
            'why': [
                'KataGo `v1.16.5` 修复了 Windows 线程队列、分析引擎 `clear_cache` 崩溃、SGF 解析栈溢出等用户可感知稳定性问题。',
                '官方修复了 Windows TensorRT `nvinfer_10` 检测，对我们的一键 TensorRT 路径更友好。',
                '官方同时改进了 CUDA / TensorRT / Metal 构建兼容性，为后续 macOS Metal 深度优化打基础。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': '繁體中文',
            'labels': 'zh_hant',
            'intro': '這一版是 KataGo 官方 `v1.16.5` 引擎跟進版，重點吸收 Windows、CUDA/TensorRT、分析引擎與 SGF 解析相關穩定性修復。',
            'updates_heading': '本版主要更新',
            'updates': [
                '內建 Windows / Linux KataGo 預設升級到官方 `v1.16.5`。',
                '軟體內 TensorRT 一鍵安裝改用 `katago-v1.16.5-trt10.9.0-cuda12.8-windows-x64.zip`，並更新官方 SHA256 校驗。',
                'Windows 進階可選 TensorRT 分卷包也同步使用 KataGo `v1.16.5` TensorRT 引擎。',
                '保留上一版的 TensorRT 斷點續傳、安裝後自動清理下載快取、執行快取盡量放入軟體 runtime 目錄。',
                '文件中的內建 KataGo 版本說明同步更新為 `v1.16.5`。',
            ],
            'before_heading': '下載前先看這幾句',
            'before': [
                f'主推薦整合包已內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
                '這次不改變預設權重；重點是跟進官方引擎 bugfix 與相容性修復。',
                'RTX 20/30/40/50 使用者仍建議先下載 NVIDIA/CUDA 包，再在軟體內按需安裝 TensorRT。',
                'GTX 10 系及更舊 NVIDIA 顯卡繼續優先 CUDA/OpenCL，不作為 TensorRT 推薦對象。',
            ],
            'download_heading': '下載建議',
            'download_headers': ('你的電腦', '直接下載這個'),
            'why_heading': '這一版為什麼值得更新',
            'why': [
                'KataGo `v1.16.5` 修復了 Windows 執行緒佇列、分析引擎 `clear_cache` 崩潰、SGF 解析堆疊溢出等使用者可感知穩定性問題。',
                '官方修復了 Windows TensorRT `nvinfer_10` 偵測，對我們的一鍵 TensorRT 路徑更友善。',
                '官方也改善 CUDA / TensorRT / Metal 建構相容性，為後續 macOS Metal 深度優化打基礎。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': 'English',
            'labels': 'en',
            'intro': 'This release tracks the official KataGo `v1.16.5` engine, mainly for Windows, CUDA/TensorRT, analysis-engine, and SGF parsing stability fixes.',
            'updates_heading': 'Release Highlights',
            'updates': [
                'Bundled Windows / Linux KataGo defaults now use official `v1.16.5`.',
                'The in-app TensorRT installer now uses `katago-v1.16.5-trt10.9.0-cuda12.8-windows-x64.zip` with the updated SHA256 check.',
                'The advanced optional Windows TensorRT split package also uses the KataGo `v1.16.5` TensorRT engine.',
                'Keeps the previous resumable TensorRT downloads, post-install download-cache cleanup, and app-local runtime cache paths.',
                'Documentation now lists the bundled KataGo version as `v1.16.5`.',
            ],
            'before_heading': 'Read Before Downloading',
            'before': [
                f'The recommended bundles include KataGo `{katago_version}` and the default weight `{model_source}`.',
                'This does not change the default weight; the focus is official engine bugfixes and compatibility fixes.',
                'RTX 20/30/40/50 users should still start with the NVIDIA/CUDA package, then install TensorRT on demand inside the app.',
                'GTX 10 series and older NVIDIA cards should keep preferring CUDA/OpenCL instead of TensorRT.',
            ],
            'download_heading': 'Download Guide',
            'download_headers': ('Your computer', 'Download this file'),
            'why_heading': 'Why Update',
            'why': [
                'KataGo `v1.16.5` fixes user-facing stability issues around Windows thread queues, analysis-engine `clear_cache`, and SGF parser stack overflow.',
                'The official Windows TensorRT `nvinfer_10` detection fix helps our one-click TensorRT path.',
                'CUDA / TensorRT / Metal build compatibility improvements give us a better base for later macOS Metal work.',
            ],
            'contact_heading': 'Contact',
            'contact': ['QQ group: `299419120`'],
        },
        {
            'language': '日本語',
            'labels': 'ja',
            'intro': 'このリリースは KataGo 公式 `v1.16.5` エンジン追従版です。Windows、CUDA/TensorRT、分析エンジン、SGF 解析の安定性修正を取り込みます。',
            'updates_heading': '主な更新',
            'updates': [
                '同梱 Windows / Linux KataGo の既定を公式 `v1.16.5` に更新しました。',
                'アプリ内 TensorRT インストーラは `katago-v1.16.5-trt10.9.0-cuda12.8-windows-x64.zip` と新しい SHA256 検証を使います。',
                'Windows 上級者向け TensorRT 分割パッケージも KataGo `v1.16.5` TensorRT エンジンに同期しました。',
                '前版の TensorRT 再開対応ダウンロード、インストール後のキャッシュ削除、アプリ内 runtime キャッシュ配置は維持します。',
                'ドキュメント上の同梱 KataGo バージョンも `v1.16.5` に更新しました。',
            ],
            'before_heading': 'ダウンロード前に',
            'before': [
                f'推奨バンドルには KataGo `{katago_version}` と既定の重み `{model_source}` が含まれます。',
                '既定の重みは変わりません。今回は公式エンジンの bugfix と互換性修正が中心です。',
                'RTX 20/30/40/50 ユーザーは NVIDIA/CUDA パッケージから始め、必要時にアプリ内で TensorRT を入れてください。',
                'GTX 10 系以前の NVIDIA カードは TensorRT ではなく CUDA/OpenCL を推奨します。',
            ],
            'download_heading': 'ダウンロード案内',
            'download_headers': ('お使いの環境', 'ダウンロードするファイル'),
            'why_heading': '更新する理由',
            'why': [
                'KataGo `v1.16.5` は Windows の thread queue、analysis engine の `clear_cache`、SGF parser stack overflow などの安定性問題を修正します。',
                '公式の Windows TensorRT `nvinfer_10` 検出修正は、こちらのワンクリック TensorRT 経路にも有利です。',
                'CUDA / TensorRT / Metal の build 互換性改善により、今後の macOS Metal 最適化の土台も良くなります。',
            ],
            'contact_heading': '連絡先',
            'contact': ['QQ グループ: `299419120`'],
        },
        {
            'language': '한국어',
            'labels': 'ko',
            'intro': '이번 릴리스는 공식 KataGo `v1.16.5` 엔진 반영판입니다. Windows, CUDA/TensorRT, 분석 엔진, SGF 파싱 안정성 수정을 중심으로 가져왔습니다.',
            'updates_heading': '주요 업데이트',
            'updates': [
                '내장 Windows / Linux KataGo 기본 버전을 공식 `v1.16.5` 로 올렸습니다.',
                '앱 안의 TensorRT 설치는 `katago-v1.16.5-trt10.9.0-cuda12.8-windows-x64.zip` 과 새 SHA256 검증을 사용합니다.',
                'Windows 고급 선택 TensorRT 분할 패키지도 KataGo `v1.16.5` TensorRT 엔진을 사용합니다.',
                '이전 버전의 이어받기 지원 TensorRT 다운로드, 설치 후 다운로드 캐시 정리, 앱 runtime 캐시 경로는 유지합니다.',
                '문서의 내장 KataGo 버전 설명도 `v1.16.5` 로 갱신했습니다.',
            ],
            'before_heading': '다운로드 전 확인',
            'before': [
                f'권장 번들에는 KataGo `{katago_version}` 와 기본 weight `{model_source}` 가 포함됩니다.',
                '기본 weight 는 바뀌지 않습니다. 이번 초점은 공식 엔진 bugfix 와 호환성 수정입니다.',
                'RTX 20/30/40/50 사용자는 NVIDIA/CUDA 패키지로 시작한 뒤 앱 안에서 TensorRT 를 필요할 때 설치하세요.',
                'GTX 10 시리즈 및 이전 NVIDIA 카드는 TensorRT 보다 CUDA/OpenCL 을 권장합니다.',
            ],
            'download_heading': '다운로드 안내',
            'download_headers': ('사용 환경', '다운로드할 파일'),
            'why_heading': '업데이트할 이유',
            'why': [
                'KataGo `v1.16.5` 는 Windows thread queue, analysis engine `clear_cache`, SGF parser stack overflow 등 안정성 문제를 수정합니다.',
                '공식 Windows TensorRT `nvinfer_10` 감지 수정은 우리의 원클릭 TensorRT 경로에도 도움이 됩니다.',
                'CUDA / TensorRT / Metal build 호환성 개선은 이후 macOS Metal 최적화의 기반이 됩니다.',
            ],
            'contact_heading': '연락',
            'contact': ['QQ group: `299419120`'],
        },
        {
            'language': 'ภาษาไทย',
            'labels': 'th',
            'intro': 'รุ่นนี้อัปเดตตาม KataGo official `v1.16.5` โดยเน้นความเสถียรของ Windows, CUDA/TensorRT, analysis engine และ SGF parser',
            'updates_heading': 'อัปเดตหลัก',
            'updates': [
                'KataGo ที่มากับแพ็กเกจ Windows / Linux เปลี่ยนเป็น official `v1.16.5`',
                'ตัวติดตั้ง TensorRT ในแอปใช้ `katago-v1.16.5-trt10.9.0-cuda12.8-windows-x64.zip` พร้อม SHA256 ใหม่',
                'แพ็กเกจ TensorRT split สำหรับผู้ใช้ขั้นสูงบน Windows ใช้ KataGo `v1.16.5` TensorRT engine เช่นกัน',
                'ยังคงรองรับ TensorRT resume download, ล้าง download cache หลังติดตั้ง และเก็บ runtime cache ในโฟลเดอร์ของแอปให้มากที่สุด',
                'เอกสารอัปเดตเวอร์ชัน KataGo ที่มากับแพ็กเกจเป็น `v1.16.5`',
            ],
            'before_heading': 'ก่อนดาวน์โหลด',
            'before': [
                f'แพ็กเกจแนะนำมี KataGo `{katago_version}` และ weight เริ่มต้น `{model_source}`',
                'รุ่นนี้ไม่ได้เปลี่ยน default weight จุดสำคัญคือ bugfix และ compatibility fixes ของ engine official',
                'ผู้ใช้ RTX 20/30/40/50 ควรเริ่มจาก NVIDIA/CUDA package แล้วติดตั้ง TensorRT ในแอปเมื่อต้องการ',
                'GTX 10 series และ NVIDIA รุ่นเก่ากว่า แนะนำ CUDA/OpenCL มากกว่า TensorRT',
            ],
            'download_heading': 'แนะนำการดาวน์โหลด',
            'download_headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
            'why_heading': 'ทำไมควรอัปเดต',
            'why': [
                'KataGo `v1.16.5` แก้ปัญหา stability ที่ผู้ใช้เจอได้ เช่น Windows thread queue, analysis engine `clear_cache`, และ SGF parser stack overflow',
                'การแก้ `nvinfer_10` detection บน Windows TensorRT จาก official ช่วยเส้นทางติดตั้ง TensorRT ในแอปของเรา',
                'การปรับ CUDA / TensorRT / Metal build compatibility เป็นฐานที่ดีสำหรับงาน macOS Metal ต่อไป',
            ],
            'contact_heading': 'ติดต่อ',
            'contact': ['QQ group: `299419120`'],
        },
    ]
    sections: list[dict[str, object]] = []
    for block in content:
        localized_assets = assets_cn if block['language'] in ('中文', '繁體中文') else assets
        sections.append(
            {
                'language': block['language'],
                'intro': block['intro'],
                'updates': {'heading': block['updates_heading'], 'items': block['updates']},
                'before': {'heading': block['before_heading'], 'items': block['before']},
                'download': {
                    'heading': block['download_heading'],
                    'headers': block['download_headers'],
                    'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS[block['labels']], localized_assets),
                },
                'why': {'heading': block['why_heading'], 'items': block['why']},
                'contact': {'heading': block['contact_heading'], 'items': block['contact']},
            }
        )
    add_nvidia50_download_rows(sections, assets_cn, assets)
    add_tensorrt_split_download_row(sections, assets_cn, assets, asset_map)
    validate_release_sections(sections)
    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_06_06_1_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {key: format_asset(asset_map[key], repo, release_tag) for key in asset_map}
    assets = {key: format_asset_en(asset_map[key], repo, release_tag) for key in asset_map}
    katago_version = bundle['katago_version']
    model_source = bundle['model_source']
    content = [
        {
            'language': '中文',
            'labels': 'zh',
            'intro': '这是一个面向真实使用反馈的稳定性修复版。重点修复两个会直接影响复盘体验的问题：加载棋谱后当前引擎贴目不再被棋谱默认 `KM[7.5]` 覆盖；鼠标连续悬停同一个候选选点时，棋盘上的棋子不再短暂消失。',
            'updates_heading': '本版主要更新',
            'updates': [
                '修复加载 SGF、GIB、在线棋谱后，当前引擎贴目被棋谱 `KM[7.5]` 覆盖的问题。',
                '棋谱里的贴目仍会保留在棋谱信息里显示，但不会再擅自改掉用户当前引擎的贴目设置。',
                '修复鼠标连续停在同一个候选选点时，主棋盘棋子可能短暂消失的渲染问题。',
                '弈客直播同步现在会保留已有分析数据，并在后台补齐主线缺失的胜率和目差曲线。',
                '改进 KataGo 临时局面恢复逻辑，snapshot SGF 会使用当前引擎贴目，避免 `loadsgf` 间接重置贴目。',
                '发布前已通过全量测试、打包、本机启动冒烟和四个平台 release workflow。',
            ],
            'before_heading': '下载前先看这几句',
            'before': [
                f'Windows 普通用户优先下载 {{windows_opencl_portable}}，这是 **OpenCL 版（推荐，免安装）**。',
                f'如果 OpenCL 在你的电脑上不稳定，再改用 {{windows_portable}}。',
                f'如果你的电脑是 **英伟达显卡**，优先下载 {{windows_nvidia_portable}}。',
                f'主推荐整合包已内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
                '如果你更喜欢安装流程，再选同系列的 `installer.exe`。',
            ],
            'download_heading': '下载建议',
            'download_headers': ('你的电脑', '直接下载这个'),
            'why_heading': '这一版为什么值得更新',
            'why': [
                '加载棋谱后，用户在引擎设置里选择的贴目会继续生效，不会被棋谱默认值悄悄改掉。',
                '候选点预览更稳定，连续看同一个选点时棋盘不会出现棋子消失这种明显干扰。',
                '弈客直播不再只停留在当前胜率/目差摘要，开启自动快速分析时会逐步补齐全盘曲线。',
                '这版是针对复盘和分析基础体验的稳定性修复，建议替换上一版继续使用。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': '繁體中文',
            'labels': 'zh_hant',
            'intro': '這是一個面向真實使用回饋的穩定性修復版。重點修復兩個會直接影響復盤體驗的問題：載入棋譜後目前引擎貼目不再被棋譜預設 `KM[7.5]` 覆蓋；滑鼠連續停在同一個候選點時，棋盤上的棋子不再短暫消失。',
            'updates_heading': '本版主要更新',
            'updates': [
                '修復載入 SGF、GIB、線上棋譜後，目前引擎貼目被棋譜 `KM[7.5]` 覆蓋的問題。',
                '棋譜裡的貼目仍會保留在棋譜資訊中顯示，但不會再擅自改掉使用者目前引擎的貼目設定。',
                '修復滑鼠連續停在同一個候選點時，主棋盤棋子可能短暫消失的渲染問題。',
                '弈客直播同步現在會保留已有分析資料，並在背景補齊主線缺失的勝率和目差曲線。',
                '改進 KataGo 臨時局面恢復邏輯，snapshot SGF 會使用目前引擎貼目，避免 `loadsgf` 間接重置貼目。',
                '發布前已通過完整測試、打包、本機啟動冒煙和四個平台 release workflow。',
            ],
            'before_heading': '下載前先看這幾句',
            'before': [
                f'Windows 一般使用者優先下載 {{windows_opencl_portable}}，這是 **OpenCL 版（推薦，免安裝）**。',
                f'如果 OpenCL 在你的電腦上不穩定，再改用 {{windows_portable}}。',
                f'如果你的電腦是 **NVIDIA 顯示卡**，優先下載 {{windows_nvidia_portable}}。',
                f'主推薦整合包已內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
                '如果你更喜歡安裝流程，再選同系列的 `installer.exe`。',
            ],
            'download_heading': '下載建議',
            'download_headers': ('你的電腦', '直接下載這個'),
            'why_heading': '這一版為什麼值得更新',
            'why': [
                '載入棋譜後，使用者在引擎設定裡選擇的貼目會繼續生效，不會被棋譜預設值悄悄改掉。',
                '候選點預覽更穩定，連續看同一個選點時棋盤不會出現棋子消失這種明顯干擾。',
                '弈客直播不再只停留在目前勝率/目差摘要，開啟自動快速分析時會逐步補齊全盤曲線。',
                '這版是針對復盤和分析基礎體驗的穩定性修復，建議替換上一版繼續使用。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': 'English',
            'labels': 'en',
            'intro': 'This is a stability-focused update based on real user feedback. It fixes two issues that directly affected review quality: loading a kifu no longer overwrites the current engine komi with the game file `KM[7.5]`, and repeatedly hovering the same candidate move no longer makes board stones briefly disappear.',
            'updates_heading': 'Release Highlights',
            'updates': [
                'Fixed SGF, GIB, and online kifu loading so game-file `KM[7.5]` no longer overwrites the current engine komi.',
                'The kifu komi is still preserved in game information for display, but it no longer silently changes the user’s active engine setting.',
                'Fixed a board rendering issue where stones could briefly disappear when hovering the same candidate move repeatedly.',
                'Yike live sync now preserves existing analysis data and completes missing mainline winrate/score curves in the background.',
                'Improved KataGo temporary position restore so snapshot SGF uses the current engine komi and does not indirectly reset komi through `loadsgf`.',
                'Before release, full tests, packaging, a local launch smoke test, and all four platform release workflows passed.',
            ],
            'before_heading': 'Read Before Downloading',
            'before': [
                f'Most Windows users should download {{windows_opencl_portable}}, the **recommended no-install OpenCL build**.',
                f'If OpenCL is unreliable on your PC, use {{windows_portable}} instead.',
                f'If your PC has an **NVIDIA GPU**, try {{windows_nvidia_portable}} first.',
                f'The recommended bundles include KataGo `{katago_version}` and the default weight `{model_source}`.',
                'If you prefer an installer, choose the matching `installer.exe` package.',
            ],
            'download_heading': 'Download Guide',
            'download_headers': ('Your computer', 'Download this file'),
            'why_heading': 'Why Update',
            'why': [
                'After loading a kifu, the komi chosen in engine settings stays active instead of being silently replaced by the game-file default.',
                'Candidate-move preview is more stable, and reviewing the same suggestion repeatedly no longer causes visible board flicker or missing stones.',
                'Yike live games no longer stay limited to the current winrate/score summary; with auto quick analysis enabled, the full-game curves are filled in progressively.',
                'This is a focused stability update for core review and analysis behavior, recommended over the previous build.',
            ],
            'contact_heading': 'Contact',
            'contact': ['QQ group: `299419120`'],
        },
        {
            'language': '日本語',
            'labels': 'ja',
            'intro': 'これは実際の利用フィードバックに基づく安定性修正版です。復盤体験に直接影響する 2 点を修正しました。棋譜を読み込んでも現在のエンジンコミが棋譜の `KM[7.5]` で上書きされず、同じ候補手に連続してマウスを置いても盤上の石が一時的に消えません。',
            'updates_heading': '主な更新',
            'updates': [
                'SGF、GIB、オンライン棋譜の読み込みで、棋譜の `KM[7.5]` が現在のエンジンコミを上書きする問題を修正しました。',
                '棋譜内のコミは棋譜情報として表示されますが、ユーザーが使っているエンジン設定を勝手に変更しません。',
                '同じ候補手にマウスを連続して置いたとき、メイン盤の石が一時的に消えることがある描画問題を修正しました。',
                '弈客ライブ同期では既存の分析データを保持し、不足している主線の勝率/目差曲線をバックグラウンドで補完します。',
                'KataGo の一時局面復元を改善し、snapshot SGF は現在のエンジンコミを使うため、`loadsgf` 経由でコミがリセットされにくくなりました。',
                'リリース前に full test、package、ローカル起動 smoke test、4 プラットフォームの release workflow がすべて通過しました。',
            ],
            'before_heading': 'ダウンロード前に',
            'before': [
                f'多くの Windows ユーザーは {{windows_opencl_portable}} を選ぶのがおすすめです。これは **推奨 OpenCL 版、インストール不要** です。',
                f'OpenCL が不安定な場合は {{windows_portable}} を使ってください。',
                f'**NVIDIA GPU** 搭載 PC では {{windows_nvidia_portable}} を優先してください。',
                f'推奨バンドルには KataGo `{katago_version}` と既定の重み `{model_source}` が含まれています。',
                'インストーラ形式がよい場合は、同じ系列の `installer.exe` を選んでください。',
            ],
            'download_heading': 'ダウンロード案内',
            'download_headers': ('お使いの環境', 'ダウンロードするファイル'),
            'why_heading': '更新する理由',
            'why': [
                '棋譜を読み込んだ後も、エンジン設定で選んだコミがそのまま有効で、棋譜の既定値に置き換わりません。',
                '候補手プレビューが安定し、同じ候補手を繰り返し見ても盤上の石が消えるような表示乱れが起きにくくなりました。',
                '弈客ライブは現在局面の勝率/目差だけでなく、自動クイック分析が有効な場合に全局曲線を段階的に補完します。',
                '復盤と分析の基本動作を直す安定性更新なので、前回ビルドからの更新をおすすめします。',
            ],
            'contact_heading': '連絡先',
            'contact': ['QQ グループ: `299419120`'],
        },
        {
            'language': '한국어',
            'labels': 'ko',
            'intro': '이번 버전은 실제 사용자 피드백을 바탕으로 한 안정성 수정판입니다. 복기 경험에 직접 영향을 주는 두 문제를 고쳤습니다. 기보를 불러와도 현재 엔진 덤이 기보의 `KM[7.5]` 로 덮어써지지 않고, 같은 후보수에 마우스를 반복해서 올려도 보드의 돌이 잠깐 사라지지 않습니다.',
            'updates_heading': '주요 업데이트',
            'updates': [
                'SGF, GIB, 온라인 기보를 불러올 때 기보의 `KM[7.5]` 가 현재 엔진 덤을 덮어쓰는 문제를 수정했습니다.',
                '기보 안의 덤은 게임 정보 표시용으로 유지되지만, 사용자의 현재 엔진 설정을 조용히 바꾸지 않습니다.',
                '같은 후보수에 마우스를 반복해서 올릴 때 메인 보드의 돌이 잠깐 사라질 수 있던 렌더링 문제를 수정했습니다.',
                'Yike live 동기화는 기존 분석 데이터를 보존하고, 메인라인에서 빠진 승률/집 차이 곡선을 백그라운드에서 채웁니다.',
                'KataGo 임시 포지션 복원 로직을 개선해 snapshot SGF 가 현재 엔진 덤을 사용하고, `loadsgf` 를 통해 덤이 간접적으로 리셋되지 않도록 했습니다.',
                '릴리스 전에 full test, package, 로컬 실행 smoke test, 4개 플랫폼 release workflow 가 모두 통과했습니다.',
            ],
            'before_heading': '다운로드 전 확인',
            'before': [
                f'대부분의 Windows 사용자는 {{windows_opencl_portable}} 를 먼저 받으면 됩니다. 이는 **추천 OpenCL 무설치 빌드** 입니다.',
                f'OpenCL 이 PC에서 불안정하면 {{windows_portable}} 를 대신 사용하세요.',
                f'**NVIDIA GPU** 가 있다면 {{windows_nvidia_portable}} 를 우선 사용해 보세요.',
                f'추천 번들에는 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 가 포함되어 있습니다.',
                '설치형 흐름을 원한다면 같은 계열의 `installer.exe` 를 고르세요.',
            ],
            'download_heading': '다운로드 안내',
            'download_headers': ('내 컴퓨터', '다운로드할 파일'),
            'why_heading': '업데이트할 이유',
            'why': [
                '기보를 불러온 뒤에도 엔진 설정에서 선택한 덤이 그대로 유지되고, 기보 기본값으로 조용히 바뀌지 않습니다.',
                '후보수 미리보기가 더 안정적이며, 같은 후보수를 반복해서 볼 때 돌이 사라지는 듯한 표시 문제가 줄었습니다.',
                'Yike live 는 현재 승률/집 차이 요약에만 머물지 않고, 자동 빠른 분석이 켜져 있으면 전체 곡선을 점진적으로 채웁니다.',
                '복기와 분석의 기본 동작을 고친 안정성 업데이트라서 이전 빌드보다 권장합니다.',
            ],
            'contact_heading': '연락',
            'contact': ['QQ 그룹: `299419120`'],
        },
        {
            'language': 'ภาษาไทย',
            'labels': 'th',
            'intro': 'รีลีสนี้เป็นรุ่นแก้ความเสถียรจาก feedback การใช้งานจริง โดยแก้สองปัญหาที่กระทบการรีวิวเกมโดยตรง: โหลด kifu แล้วค่า komi ของ engine ปัจจุบันจะไม่ถูก `KM[7.5]` ในไฟล์ทับ และเมื่อ hover candidate move เดิมซ้ำ ๆ หินบนกระดานจะไม่หายไปชั่วคราว',
            'updates_heading': 'อัปเดตหลัก',
            'updates': [
                'แก้ปัญหาโหลด SGF, GIB และ kifu ออนไลน์แล้ว `KM[7.5]` ในไฟล์ไปทับค่า komi ของ engine ปัจจุบัน',
                'ค่า komi ใน kifu ยังแสดงในข้อมูลเกมตามเดิม แต่จะไม่เปลี่ยนค่าที่ผู้ใช้ตั้งไว้ใน engine อย่างเงียบ ๆ',
                'แก้ปัญหา render ที่ทำให้หินบนกระดานหลักอาจหายไปชั่วคราวเมื่อ hover candidate move เดิมซ้ำ ๆ',
                'Yike live sync จะเก็บข้อมูลวิเคราะห์ที่มีอยู่ และเติมกราฟ winrate/score ของ mainline ที่ยังขาดอยู่แบบ background',
                'ปรับปรุงการ restore ตำแหน่งชั่วคราวของ KataGo ให้ snapshot SGF ใช้ komi ของ engine ปัจจุบัน ลดการ reset ผ่าน `loadsgf`',
                'ก่อน release ได้ผ่าน full test, package, local launch smoke test และ release workflow ครบทั้ง 4 platform',
            ],
            'before_heading': 'ก่อนดาวน์โหลด',
            'before': [
                f'ผู้ใช้ Windows ส่วนใหญ่แนะนำให้ดาวน์โหลด {{windows_opencl_portable}} ซึ่งเป็น **OpenCL รุ่นแนะนำ แบบไม่ต้องติดตั้ง**',
                f'ถ้า OpenCL ไม่เสถียรบนเครื่องของคุณ ให้ใช้ {{windows_portable}} แทน',
                f'ถ้ามี **NVIDIA GPU** แนะนำให้ลอง {{windows_nvidia_portable}} ก่อน',
                f'แพ็กเกจหลักมี KataGo `{katago_version}` และน้ำหนักเริ่มต้น `{model_source}` มาให้แล้ว',
                'ถ้าต้องการแบบติดตั้ง ให้เลือกไฟล์ `installer.exe` ในชุดเดียวกัน',
            ],
            'download_heading': 'แนะนำการดาวน์โหลด',
            'download_headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
            'why_heading': 'ทำไมควรอัปเดต',
            'why': [
                'หลังโหลด kifu ค่า komi ที่เลือกใน engine settings จะยังคงอยู่ ไม่ถูกค่า default ในไฟล์เปลี่ยนเอง',
                'การ preview candidate move เสถียรขึ้น และการดูคำแนะนำเดิมซ้ำ ๆ จะไม่ทำให้เห็นหินหายหรือกระพริบอย่างรบกวน',
                'Yike live จะไม่แสดงแค่ winrate/score ปัจจุบันเท่านั้น หากเปิด auto quick analysis ระบบจะค่อย ๆ เติมกราฟทั้งเกมให้ครบ',
                'เป็นรุ่นแก้เสถียรภาพของ flow รีวิวและวิเคราะห์หลัก จึงแนะนำให้อัปเดตจาก build ก่อนหน้า',
            ],
            'contact_heading': 'ติดต่อ',
            'contact': ['QQ group: `299419120`'],
        },
    ]
    sections: list[dict[str, object]] = []
    for block in content:
        localized_assets = assets_cn if block['language'] in ('中文', '繁體中文') else assets
        before_items = [
            item.format(
                windows_opencl_portable=localized_assets['windows_opencl_portable'],
                windows_portable=localized_assets['windows_portable'],
                windows_nvidia_portable=localized_assets['windows_nvidia_portable'],
            )
            for item in block['before']
        ]
        sections.append(
            {
                'language': block['language'],
                'intro': block['intro'],
                'updates': {'heading': block['updates_heading'], 'items': block['updates']},
                'before': {'heading': block['before_heading'], 'items': before_items},
                'download': {
                    'heading': block['download_heading'],
                    'headers': block['download_headers'],
                    'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS[block['labels']], localized_assets),
                },
                'why': {'heading': block['why_heading'], 'items': block['why']},
                'contact': {'heading': block['contact_heading'], 'items': block['contact']},
            }
        )
    add_nvidia50_download_rows(sections, assets_cn, assets)
    add_tensorrt_split_download_row(sections, assets_cn, assets, asset_map)
    validate_release_sections(sections)
    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_06_08_1_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {key: format_asset(asset_map[key], repo, release_tag) for key in asset_map}
    assets = {key: format_asset_en(asset_map[key], repo, release_tag) for key in asset_map}
    katago_version = bundle['katago_version']
    model_source = bundle['model_source']
    content = [
        {
            'language': '中文',
            'labels': 'zh',
            'intro': '这是棋力评估体验预览版。重点把“棋力评估 / 吻合度”从偏工程化的详细表格，改成普通棋友更容易看懂的卡片式展示；详细数据仍然保留，方便需要完整指标的用户继续查看。',
            'updates_heading': '本版主要更新',
            'updates': [
                '重做“棋力评估”弹窗，主界面改成现代卡片式布局，不再像旧版指标表格那样拥挤。',
                '“测评”页只展示黑棋和白棋表现，不再在主视图显示合计棋力，避免误导普通用户。',
                '棋力区间改成人能看懂的中文格式，例如 `业余1级-2级`、`业余1段-2段`。',
                '“吻合度”页改成黑白双方卡片、走势曲线和建议复查区间，只提示复盘重点，不做作弊结论。',
                '每个页面右上角保留“详细数据”，点击后仍可打开旧版完整指标表和详细图表。',
                '分析工具栏新增短按钮“棋力评估”，放在腾讯棋谱旁边，日常复盘更容易找到。',
            ],
            'before_heading': '下载前先看这几句',
            'before': [
                '这是 pre-release 预览版，适合想先体验新版棋力评估 UI 的用户。',
                f'主推荐整合包继续内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
                f'Windows 普通用户优先下载 {{windows_opencl_portable}}，这是 **OpenCL 版（推荐，免安装）**。',
                f'如果 OpenCL 在你的电脑上不稳定，再改用 {{windows_portable}}。',
                f'如果你的电脑是 **英伟达显卡**，优先下载 {{windows_nvidia_portable}}。',
            ],
            'download_heading': '下载建议',
            'download_headers': ('你的电脑', '直接下载这个'),
            'why_heading': '这一版为什么值得试用',
            'why': [
                '普通用户打开棋力评估后，先看到的是黑棋和白棋表现卡片，而不是一大堆看不懂的表格。',
                '业余级、业余段的显示更符合中文围棋用户习惯，减少 `1-2k` 这类英文缩写带来的理解成本。',
                '吻合度页面更适合复盘：突出双方数据、走势和建议复查区间，不制造过度判断。',
                '这次只优化展示和入口，不改变棋力评估算法，旧版详细数据也没有被删除。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': '繁體中文',
            'labels': 'zh_hant',
            'intro': '這是棋力評估體驗預覽版。重點把「棋力評估 / 吻合度」從偏工程化的詳細表格，改成一般棋友更容易看懂的卡片式展示；詳細資料仍然保留，方便需要完整指標的使用者繼續查看。',
            'updates_heading': '本版主要更新',
            'updates': [
                '重做「棋力評估」視窗，主介面改成現代卡片式布局，不再像舊版指標表格那樣擁擠。',
                '「測評」頁只展示黑棋和白棋表現，不再在主視圖顯示合計棋力，避免誤導一般使用者。',
                '棋力區間改成人能看懂的中文格式，例如 `業餘1級-2級`、`業餘1段-2段`。',
                '「吻合度」頁改成黑白雙方卡片、走勢曲線和建議複查區間，只提示復盤重點，不做作弊結論。',
                '每個頁面右上角保留「詳細資料」，點擊後仍可開啟舊版完整指標表和詳細圖表。',
                '分析工具列新增短按鈕「棋力評估」，放在騰訊棋譜旁邊，日常復盤更容易找到。',
            ],
            'before_heading': '下載前先看這幾句',
            'before': [
                '這是 pre-release 預覽版，適合想先體驗新版棋力評估 UI 的使用者。',
                f'主推薦整合包繼續內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
                f'Windows 一般使用者優先下載 {{windows_opencl_portable}}，這是 **OpenCL 版（推薦，免安裝）**。',
                f'如果 OpenCL 在你的電腦上不穩定，再改用 {{windows_portable}}。',
                f'如果你的電腦是 **NVIDIA 顯示卡**，優先下載 {{windows_nvidia_portable}}。',
            ],
            'download_heading': '下載建議',
            'download_headers': ('你的電腦', '直接下載這個'),
            'why_heading': '這一版為什麼值得試用',
            'why': [
                '一般使用者打開棋力評估後，先看到的是黑棋和白棋表現卡片，而不是一大堆看不懂的表格。',
                '業餘級、業餘段的顯示更符合中文圍棋使用者習慣，減少 `1-2k` 這類英文縮寫帶來的理解成本。',
                '吻合度頁面更適合復盤：突出雙方資料、走勢和建議複查區間，不製造過度判斷。',
                '這次只優化展示和入口，不改變棋力評估演算法，舊版詳細資料也沒有被刪除。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': 'English',
            'labels': 'en',
            'intro': 'This is a preview build for the player strength UI. The Strength / Match views have been redesigned from dense engineering-style tables into clearer cards for everyday reviewers, while the full detailed data remains available for advanced users.',
            'updates_heading': 'Release Highlights',
            'updates': [
                'Redesigned the Player Strength dialog with a modern card-based main view instead of the crowded legacy metrics table.',
                'The Assessment page now focuses on Black and White only; the combined strength summary is no longer shown in the main view.',
                'Rank ranges are displayed in friendlier localized wording, such as amateur kyu and amateur dan ranges.',
                'The Match page now shows Black/White cards, a trend chart, and suggested review intervals without making cheating conclusions.',
                'Each page keeps a Detail Data entry in the top-right corner, opening the previous full metrics table or detailed chart.',
                'Added a short Strength button next to the Tencent Kifu entry on the analysis toolbar.',
            ],
            'before_heading': 'Read Before Downloading',
            'before': [
                'This is a pre-release preview for users who want to try the new Player Strength UI early.',
                f'The recommended bundles continue to include KataGo `{katago_version}` and the default weight `{model_source}`.',
                f'Most Windows users should download {{windows_opencl_portable}}, the **recommended no-install OpenCL build**.',
                f'If OpenCL is unreliable on your PC, use {{windows_portable}} instead.',
                f'If your PC has an **NVIDIA GPU**, try {{windows_nvidia_portable}} first.',
            ],
            'download_heading': 'Download Guide',
            'download_headers': ('Your computer', 'Download this file'),
            'why_heading': 'Why Try This Build',
            'why': [
                'The first view now explains Black and White performance directly instead of starting with a hard-to-read metrics table.',
                'Localized amateur kyu/dan wording is easier for Chinese Go users to understand than raw `1-2k` style labels.',
                'The Match view is better suited for review: it highlights both players, trend, and suggested intervals without overclaiming.',
                'This release changes presentation and entry points only; the strength estimation algorithm and legacy detail view remain available.',
            ],
            'contact_heading': 'Contact',
            'contact': ['QQ group: `299419120`'],
        },
        {
            'language': '日本語',
            'labels': 'ja',
            'intro': 'これは棋力評価 UI のプレビュー版です。「棋力評価 / 一致度」を、開発者向けの細かい表から、普段の復盤で見やすいカード形式に刷新しました。詳細データは従来どおり残しています。',
            'updates_heading': '主な更新',
            'updates': [
                '「棋力評価」ダイアログを刷新し、旧来の詰まった指標表ではなく、カード形式のメイン画面にしました。',
                '「評価」ページは黒番と白番の表示に絞り、メイン画面では合計棋力を表示しないようにしました。',
                '棋力レンジは、級位・段位として読みやすい表現にしました。',
                '「一致度」ページは黒白のカード、推移グラフ、見直し候補区間を表示し、不正判定のような結論は出しません。',
                '各ページ右上に「詳細データ」を残し、従来の詳細指標表や詳細グラフを開けます。',
                '分析ツールバーの Tencent 棋譜の横に、短い「棋力評価」ボタンを追加しました。',
            ],
            'before_heading': 'ダウンロード前に',
            'before': [
                'これは新しい棋力評価 UI を先に試したいユーザー向けの pre-release です。',
                f'推奨バンドルには KataGo `{katago_version}` と既定の重み `{model_source}` が含まれています。',
                f'多くの Windows ユーザーは {{windows_opencl_portable}} を選ぶのがおすすめです。これは **推奨 OpenCL 版、インストール不要** です。',
                f'OpenCL が不安定な場合は {{windows_portable}} を使ってください。',
                f'**NVIDIA GPU** 搭載 PC では {{windows_nvidia_portable}} を優先してください。',
            ],
            'download_heading': 'ダウンロード案内',
            'download_headers': ('お使いの環境', 'ダウンロードするファイル'),
            'why_heading': 'このビルドを試す理由',
            'why': [
                '棋力評価を開いたとき、まず黒番と白番のパフォーマンスが見え、難しい表から読み解く必要がありません。',
                '級位・段位の表示により、`1-2k` のような略記より直感的に理解できます。',
                '一致度ページは復盤向けに、双方のデータ、推移、見直し候補区間を示し、過度な判断を避けます。',
                '今回は表示と入口の改善だけで、棋力評価アルゴリズムや従来の詳細画面は変更していません。',
            ],
            'contact_heading': '連絡先',
            'contact': ['QQ グループ: `299419120`'],
        },
        {
            'language': '한국어',
            'labels': 'ko',
            'intro': '이번 버전은 기력 평가 UI 프리뷰 빌드입니다. Strength / Match 화면을 촘촘한 엔지니어링식 표에서 일반 복기 사용자가 보기 쉬운 카드형 화면으로 바꾸었고, 전체 상세 데이터는 그대로 남겼습니다.',
            'updates_heading': '주요 업데이트',
            'updates': [
                'Player Strength 창을 현대적인 카드형 메인 화면으로 다시 만들었습니다. 이전처럼 지표 표가 빽빽하게 보이지 않습니다.',
                'Assessment 페이지는 흑과 백의 성과만 보여 주며, 메인 화면에서 합산 기력은 표시하지 않습니다.',
                '기력 구간은 아마 급/단 범위처럼 더 이해하기 쉬운 표현으로 표시합니다.',
                'Match 페이지는 흑/백 카드, 추세 그래프, 복기 추천 구간을 보여 주며 부정행위 결론을 내리지 않습니다.',
                '각 페이지 오른쪽 위에 Detail Data 진입점을 남겨 기존 전체 지표 표와 상세 그래프를 열 수 있습니다.',
                '분석 툴바의 Tencent 기보 옆에 짧은 Strength 버튼을 추가했습니다.',
            ],
            'before_heading': '다운로드 전 확인',
            'before': [
                '새 Player Strength UI 를 먼저 써 보고 싶은 사용자를 위한 pre-release 프리뷰입니다.',
                f'추천 번들에는 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 가 포함되어 있습니다.',
                f'대부분의 Windows 사용자는 {{windows_opencl_portable}} 를 먼저 받으면 됩니다. 이는 **추천 OpenCL 무설치 빌드** 입니다.',
                f'OpenCL 이 PC에서 불안정하면 {{windows_portable}} 를 대신 사용하세요.',
                f'**NVIDIA GPU** 가 있다면 {{windows_nvidia_portable}} 를 우선 사용해 보세요.',
            ],
            'download_heading': '다운로드 안내',
            'download_headers': ('내 컴퓨터', '다운로드할 파일'),
            'why_heading': '이 빌드를 써 볼 이유',
            'why': [
                '기력 평가를 열면 어려운 표가 아니라 흑과 백의 성과 카드부터 볼 수 있습니다.',
                '아마 급/단 표시가 `1-2k` 같은 축약 표기보다 이해하기 쉽습니다.',
                'Match 화면은 복기에 맞게 양쪽 데이터, 추세, 추천 확인 구간을 보여 주고 과도한 판단은 피합니다.',
                '이번 변경은 표시와 진입점만 바꾸며, 기력 평가 알고리즘과 기존 상세 화면은 그대로 유지합니다.',
            ],
            'contact_heading': '연락',
            'contact': ['QQ 그룹: `299419120`'],
        },
        {
            'language': 'ภาษาไทย',
            'labels': 'th',
            'intro': 'รีลีสนี้เป็น preview ของ UI ประเมินฝีมือผู้เล่น โดยเปลี่ยน Strength / Match จากตารางละเอียดแบบวิศวกรรมให้เป็นการ์ดที่อ่านง่ายขึ้นสำหรับการรีวิวเกมทั่วไป และยังคงหน้า detailed data เดิมไว้สำหรับผู้ใช้ขั้นสูง',
            'updates_heading': 'อัปเดตหลัก',
            'updates': [
                'ปรับหน้าต่าง Player Strength ใหม่เป็นหน้าหลักแบบ card layout แทนตาราง metric เดิมที่แน่นเกินไป',
                'หน้า Assessment แสดงเฉพาะฝั่งดำและขาว ไม่แสดง combined strength ในหน้าหลัก',
                'ช่วงระดับฝีมือแสดงด้วยคำที่เข้าใจง่ายขึ้น เช่น amateur kyu / amateur dan range',
                'หน้า Match แสดงการ์ดดำ/ขาว กราฟแนวโน้ม และช่วงที่แนะนำให้กลับไปตรวจ โดยไม่สรุปว่าโกง',
                'แต่ละหน้ามีปุ่ม Detail Data มุมขวาบน เพื่อเปิดตาราง metric หรือกราฟละเอียดแบบเดิม',
                'เพิ่มปุ่ม Strength แบบสั้นใน toolbar ถัดจาก Tencent Kifu',
            ],
            'before_heading': 'ก่อนดาวน์โหลด',
            'before': [
                'นี่เป็น pre-release สำหรับผู้ใช้ที่ต้องการลอง UI ประเมินฝีมือแบบใหม่ก่อน',
                f'แพ็กเกจหลักมี KataGo `{katago_version}` และน้ำหนักเริ่มต้น `{model_source}` มาให้แล้ว',
                f'ผู้ใช้ Windows ส่วนใหญ่แนะนำให้ดาวน์โหลด {{windows_opencl_portable}} ซึ่งเป็น **OpenCL รุ่นแนะนำ แบบไม่ต้องติดตั้ง**',
                f'ถ้า OpenCL ไม่เสถียรบนเครื่องของคุณ ให้ใช้ {{windows_portable}} แทน',
                f'ถ้ามี **NVIDIA GPU** แนะนำให้ลอง {{windows_nvidia_portable}} ก่อน',
            ],
            'download_heading': 'แนะนำการดาวน์โหลด',
            'download_headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
            'why_heading': 'ทำไมควรลอง build นี้',
            'why': [
                'เมื่อเปิดการประเมินฝีมือ จะเห็นการ์ดผลงานของดำและขาวก่อน ไม่ต้องเริ่มจากตารางที่อ่านยาก',
                'การแสดงระดับแบบ amateur kyu/dan เข้าใจง่ายกว่า label แบบ `1-2k`',
                'หน้า Match เหมาะกับการรีวิวมากขึ้น เพราะเน้นข้อมูลสองฝั่ง แนวโน้ม และช่วงที่ควรตรวจ ไม่ตัดสินเกินจริง',
                'รุ่นนี้ปรับเฉพาะการแสดงผลและตำแหน่งปุ่ม ไม่เปลี่ยนอัลกอริทึมประเมินฝีมือ และยังเก็บ detail view เดิมไว้',
            ],
            'contact_heading': 'ติดต่อ',
            'contact': ['QQ group: `299419120`'],
        },
    ]

    sections: list[dict[str, object]] = []
    for block in content:
        localized_assets = assets_cn if block['language'] in ('中文', '繁體中文') else assets
        before_items = [
            item.format(
                windows_opencl_portable=localized_assets['windows_opencl_portable'],
                windows_portable=localized_assets['windows_portable'],
                windows_nvidia_portable=localized_assets['windows_nvidia_portable'],
            )
            for item in block['before']
        ]
        sections.append(
            {
                'language': block['language'],
                'intro': block['intro'],
                'updates': {'heading': block['updates_heading'], 'items': block['updates']},
                'before': {'heading': block['before_heading'], 'items': before_items},
                'download': {
                    'heading': block['download_heading'],
                    'headers': block['download_headers'],
                    'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS[block['labels']], localized_assets),
                },
                'why': {'heading': block['why_heading'], 'items': block['why']},
                'contact': {'heading': block['contact_heading'], 'items': block['contact']},
            }
        )
    add_nvidia50_download_rows(sections, assets_cn, assets)
    add_tensorrt_split_download_row(sections, assets_cn, assets, asset_map)
    validate_release_sections(sections)
    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_release_notes(asset_map: dict[str, str | None], bundle: dict[str, str], repo: str, release_tag: str | None) -> str:
    if release_tag == 'next-2026-05-03.1':
        return build_next_2026_05_03_1_notes(asset_map, repo, release_tag)
    if release_tag == 'next-2026-05-04.1':
        return build_next_2026_05_04_1_notes(asset_map, repo, release_tag)
    if release_tag == 'next-2026-05-06.1':
        return build_next_2026_05_06_1_notes(asset_map, repo, release_tag)
    if release_tag == 'next-2026-05-17.2':
        return build_next_2026_05_17_2_notes(asset_map, bundle, repo, release_tag)
    if release_tag == 'next-2026-05-18.1':
        return build_next_2026_05_18_1_notes(asset_map, bundle, repo, release_tag)
    if release_tag == 'next-2026-05-26.1':
        return build_next_2026_05_26_1_notes(asset_map, bundle, repo, release_tag)
    if release_tag == 'next-2026-05-30.1':
        return build_next_2026_05_30_1_notes(asset_map, bundle, repo, release_tag)
    if release_tag == 'next-2026-05-30.2':
        return build_next_2026_05_30_2_notes(asset_map, bundle, repo, release_tag)
    if release_tag == 'next-2026-05-31.1':
        return build_next_2026_05_31_1_notes(asset_map, bundle, repo, release_tag)
    if release_tag == 'next-2026-05-31.2':
        return build_next_2026_05_31_2_notes(asset_map, bundle, repo, release_tag)
    if release_tag == 'next-2026-06-01.1':
        return build_next_2026_06_01_1_notes(asset_map, bundle, repo, release_tag)
    if release_tag == 'next-2026-06-01.2':
        return build_next_2026_06_01_2_notes(asset_map, bundle, repo, release_tag)
    if release_tag == 'next-2026-06-06.1':
        return build_next_2026_06_06_1_notes(asset_map, bundle, repo, release_tag)
    if release_tag == 'next-2026-06-08.1':
        return build_next_2026_06_08_1_notes(asset_map, bundle, repo, release_tag)
    if release_tag == 'next-2026-06-09.1':
        return build_next_2026_06_09_1_notes(asset_map, bundle, repo, release_tag)
    if release_tag == 'next-2026-06-10.1':
        return build_next_2026_06_10_1_notes(asset_map, bundle, repo, release_tag)
    if release_tag == 'next-2026-06-11.1':
        return build_next_2026_06_11_1_notes(asset_map, bundle, repo, release_tag)
    if release_tag == 'next-2026-06-11.2':
        return build_next_2026_06_11_2_notes(asset_map, bundle, repo, release_tag)
    if release_tag == 'next-2026-06-12.1':
        return build_next_2026_06_12_1_notes(asset_map, bundle, repo, release_tag)
    if release_tag == 'next-2026-06-12.2':
        return build_next_2026_06_12_2_notes(asset_map, bundle, repo, release_tag)

    assets_cn = {
        key: format_asset(asset_map[key], repo, release_tag)
        for key in asset_map
    }
    assets = {
        key: format_asset_en(asset_map[key], repo, release_tag)
        for key in asset_map
    }

    katago_version = bundle['katago_version']
    model_source = bundle['model_source']
    sections: list[dict[str, object]] = [
        {
            'language': '中文',
            'intro': (
                '这是当前仍在维护的 `lizzieyzy` 维护版，也是一个面向普通用户的 `KataGo 围棋复盘 GUI`。'
                '这一版继续补齐常用棋谱来源：在野狐棋谱旁新增 **腾讯棋谱** 入口，Windows 免安装包和 KataGo 开箱体验继续保持。'
                '下载安装后，可以输入 **野狐昵称** 抓野狐公开棋谱，也可以在腾讯棋谱中输入 **腾讯昵称或数字 UID** 搜索公开棋谱、分析和复盘。'
            ),
            'updates': {
                'heading': '本版主要更新',
                'items': [
                    '新增“腾讯棋谱”入口，放在野狐棋谱旁边，方便直接搜索腾讯围棋公开棋谱。',
                    '支持腾讯昵称或数字 UID 搜索棋谱列表，并展示对局时间、黑白双方、段位、胜负结果和手数。',
                    '支持双击棋谱行或点击“打开”下载腾讯棋谱详情，加载到主棋盘继续分析复盘。',
                    '腾讯棋谱搜索和下载都走后台线程，搜索/下载阶段有明确提示，失败时会恢复窗口操作并显示错误。',
                    '修复腾讯职业棋手段位显示，柯洁这类 `P9段` 不会再被显示成异常的业余段位。',
                    '发布前已重新跑全量测试、打包、四平台 release workflow，并完成本机真实启动 UI 冒烟。',
                ],
            },
            'before': {
                'heading': '下载前先看这几句',
                'items': [
                    f'Windows 普通用户直接下载 {assets_cn["windows_opencl_portable"]}，这是 **OpenCL 版（推荐，免安装）**。',
                    f'如果 OpenCL 在你的电脑上跑得不好，再改用 {assets_cn["windows_portable"]}。',
                    f'如果你的电脑是 **英伟达显卡**，优先下载 {assets_cn["windows_nvidia_portable"]}。',
                    '如果你更喜欢安装流程，再选同系列的 `installer.exe`。',
                    '野狐棋谱和腾讯棋谱现在是两个入口：野狐输入野狐昵称，腾讯输入腾讯昵称或数字 UID。',
                    f'主推荐整合包已内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
                    'Windows OpenCL 版也支持 **智能优化**，可以自动写入更合适的线程设置。',
                    'Windows 现在把 OpenCL 版放到推荐位，优先照顾更快的分析速度。',
                    'CPU 版继续保留，作为 OpenCL 不稳定时的兼容兜底。',
                    'Windows NVIDIA 整合包已内置官方运行库，首启可离线使用。',
                ],
            },
            'download': {
                'heading': '下载建议',
                'headers': ('你的电脑', '直接下载这个'),
                'rows': [
                    ('Windows 64 位，OpenCL 版，推荐更快，免安装', assets_cn['windows_opencl_portable']),
                    ('Windows 64 位，OpenCL 版，想安装', assets_cn['windows_opencl_installer']),
                    ('Windows 64 位，CPU 版，兼容兜底，免安装', assets_cn['windows_portable']),
                    ('Windows 64 位，CPU 版，兼容兜底，想安装', assets_cn['windows_installer']),
                    ('Windows 64 位，英伟达显卡，想更快，免安装', assets_cn['windows_nvidia_portable']),
                    ('Windows 64 位，英伟达显卡，想更快，也想安装', assets_cn['windows_nvidia_installer']),
                    ('Windows 64 位，想自己配引擎', assets_cn['windows_no_engine_portable']),
                    ('Windows 64 位，想自己配引擎，也想安装器', assets_cn['windows_no_engine_installer']),
                    ('macOS Apple Silicon', assets_cn['mac_arm64']),
                    ('macOS Intel', assets_cn['mac_amd64']),
                    ('Linux 64 位，CPU 兼容版', assets_cn['linux64']),
                    ('Linux 64 位，OpenCL 版，AMD/Intel GPU', assets_cn['linux64_opencl']),
                    ('Linux 64 位，NVIDIA CUDA 版', assets_cn['linux64_nvidia']),
                ],
            },
            'why': {
                'heading': '这一版为什么值得先看',
                'items': [
                    '野狐抓谱链路继续可用，并新增腾讯棋谱入口，常见公开棋谱来源更完整。',
                    '腾讯棋谱支持昵称/数字 UID 搜索、列表分页、双击或“打开”直接加载到主棋盘。',
                    'Windows 现在把 `.portable.zip` 放到推荐位，解压后更符合多数用户习惯。',
                    'Windows 现在同时提供 OpenCL 版和 CPU 版，下载时更容易按“速度优先”还是“兼容优先”来选。',
                    'Windows OpenCL 版现在放到推荐位，优先照顾更快的分析速度。',
                    'Windows OpenCL 版也支持智能优化，测速后会自动保存推荐线程数。',
                    'Windows CPU 版继续保留，适合 OpenCL 表现不理想的机器。',
                    '对有 NVIDIA 独显的 Windows 用户，额外提供官方 CUDA 版 KataGo 的极速整合包，并且把官方运行库一起打进包里。',
                    'macOS 继续提供 Apple Silicon / Intel 两种 `.dmg`。',
                    '整合包继续内置 KataGo 与默认权重，打开后更快进入分析。',
                ],
            },
            'contact': {'heading': '交流', 'items': ['QQ 群：`299419120`']},
        },
        {
            'language': '繁體中文',
            'intro': (
                '這是目前仍在維護的 `lizzieyzy` 維護版，也是一個面向一般使用者的 `KataGo 圍棋復盤 GUI`。'
                '這一版繼續補齊常用棋譜來源：在野狐棋譜旁新增 **騰訊棋譜** 入口，Windows 免安裝包和 KataGo 開箱體驗繼續保持。'
                '下載安裝後，可以輸入 **野狐暱稱** 抓野狐公開棋譜，也可以在騰訊棋譜中輸入 **騰訊暱稱或數字 UID** 搜尋公開棋譜、分析和復盤。'
            ),
            'updates': {
                'heading': '本版主要更新',
                'items': [
                    '新增「騰訊棋譜」入口，放在野狐棋譜旁邊，方便直接搜尋騰訊圍棋公開棋譜。',
                    '支援騰訊暱稱或數字 UID 搜尋棋譜列表，並顯示對局時間、黑白雙方、段位、勝負結果和手數。',
                    '支援雙擊棋譜列或點擊「開啟」下載騰訊棋譜詳情，載入到主棋盤繼續分析復盤。',
                    '騰訊棋譜搜尋和下載都走背景執行緒，搜尋/下載階段有明確提示，失敗時會恢復視窗操作並顯示錯誤。',
                    '修復騰訊職業棋手段位顯示，柯潔這類 `P9段` 不會再被顯示成異常的業餘段位。',
                    '發布前已重新跑完整測試、打包、四平台 release workflow，並完成本機真實啟動 UI 冒煙。',
                ],
            },
            'before': {
                'heading': '下載前先看這幾句',
                'items': [
                    f'Windows 一般使用者直接下載 {assets_cn["windows_opencl_portable"]}，這是 **OpenCL 版（推薦，免安裝）**。',
                    f'如果 OpenCL 在你的電腦上跑得不好，再改用 {assets_cn["windows_portable"]}。',
                    f'如果你的電腦是 **NVIDIA 顯示卡**，優先下載 {assets_cn["windows_nvidia_portable"]}。',
                    '如果你更喜歡安裝流程，再選同系列的 `installer.exe`。',
                    '野狐棋譜和騰訊棋譜現在是兩個入口：野狐輸入野狐暱稱，騰訊輸入騰訊暱稱或數字 UID。',
                    f'主推薦整合包已內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
                    'Windows OpenCL 版也支援 **智慧最佳化**，可以自動寫入更合適的執行緒設定。',
                    'Windows 現在把 OpenCL 版放到推薦位，優先照顧更快的分析速度。',
                    'CPU 版繼續保留，作為 OpenCL 不穩定時的相容兜底。',
                    'Windows NVIDIA 整合包已內建官方執行庫，首次啟動可離線使用。',
                ],
            },
            'download': {
                'heading': '下載建議',
                'headers': ('你的電腦', '直接下載這個'),
                'rows': [
                    ('Windows 64 位，OpenCL 版，推薦更快，免安裝', assets_cn['windows_opencl_portable']),
                    ('Windows 64 位，OpenCL 版，想安裝', assets_cn['windows_opencl_installer']),
                    ('Windows 64 位，CPU 版，相容兜底，免安裝', assets_cn['windows_portable']),
                    ('Windows 64 位，CPU 版，相容兜底，想安裝', assets_cn['windows_installer']),
                    ('Windows 64 位，NVIDIA 顯示卡，想更快，免安裝', assets_cn['windows_nvidia_portable']),
                    ('Windows 64 位，NVIDIA 顯示卡，想更快，也想安裝', assets_cn['windows_nvidia_installer']),
                    ('Windows 64 位，想自己配引擎', assets_cn['windows_no_engine_portable']),
                    ('Windows 64 位，想自己配引擎，也想安裝器', assets_cn['windows_no_engine_installer']),
                    ('macOS Apple Silicon', assets_cn['mac_arm64']),
                    ('macOS Intel', assets_cn['mac_amd64']),
                    ('Linux 64 位，CPU 相容版', assets_cn['linux64']),
                    ('Linux 64 位，OpenCL 版，AMD/Intel GPU', assets_cn['linux64_opencl']),
                    ('Linux 64 位，NVIDIA CUDA 版', assets_cn['linux64_nvidia']),
                ],
            },
            'why': {
                'heading': '這一版為什麼值得先看',
                'items': [
                    '野狐抓譜流程繼續可用，並新增騰訊棋譜入口，常見公開棋譜來源更完整。',
                    '騰訊棋譜支援暱稱/數字 UID 搜尋、列表分頁、雙擊或「開啟」直接載入到主棋盤。',
                    'Windows 現在把 `.portable.zip` 放到推薦位，解壓後更符合多數使用者習慣。',
                    'Windows 現在同時提供 OpenCL 版和 CPU 版，下載時更容易按「速度優先」還是「相容優先」來選。',
                    'Windows OpenCL 版現在放到推薦位，優先照顧更快的分析速度。',
                    'Windows OpenCL 版也支援智慧最佳化，測速後會自動儲存推薦執行緒數。',
                    'Windows CPU 版繼續保留，適合 OpenCL 表現不理想的機器。',
                    '對有 NVIDIA 獨顯的 Windows 使用者，額外提供官方 CUDA 版 KataGo 的高速整合包，並且把官方執行庫一起打進包裡。',
                    'macOS 繼續提供 Apple Silicon / Intel 兩種 `.dmg`。',
                    '整合包繼續內建 KataGo 與預設權重，打開後更快進入分析。',
                ],
            },
            'contact': {'heading': '交流', 'items': ['QQ 群：`299419120`']},
        },
        {
            'language': 'English',
            'intro': (
                'This is the actively maintained `LizzieYzy` fork and a practical `KataGo review GUI` for normal users. '
                'This release adds a **Tencent kifu** entry next to Fox kifu, while keeping the Windows portable packages and KataGo first-run experience straightforward. '
                'After installing, enter a **Fox nickname** for Fox games, or use a **Tencent nickname / numeric UID** to search public Tencent Weiqi games for analysis and review.'
            ),
            'updates': {
                'heading': 'Release Highlights',
                'items': [
                    'Added a Tencent kifu entry next to Fox kifu for searching public Tencent Weiqi games directly from the app.',
                    'Tencent kifu search supports nickname or numeric UID input, and shows game time, black/white players, ranks, result, and move count.',
                    'Double-clicking a row or pressing Open downloads the Tencent kifu detail and loads it into the main board for analysis.',
                    'Tencent search and download now run in background workers with clear progress feedback, restored controls on failure, and explicit error messages.',
                    'Fixed Tencent professional rank display so players such as Ke Jie show as `P9段` instead of an invalid amateur rank.',
                    'Before release, full tests, packaging, all four platform release workflows, and a real local launch UI smoke test were rerun.',
                ],
            },
            'before': {
                'heading': 'Read Before Downloading',
                'items': [
                    f'Most Windows users should download {assets["windows_opencl_portable"]}, the **recommended no-install OpenCL build**.',
                    f'If OpenCL is unreliable on your PC, use {assets["windows_portable"]} instead.',
                    f'If your PC has an **NVIDIA GPU**, try {assets["windows_nvidia_portable"]} first.',
                    'If you prefer an installer, choose the matching `installer.exe` package.',
                    'Fox kifu and Tencent kifu are separate entries: use a Fox nickname for Fox, and a Tencent nickname or numeric UID for Tencent.',
                    f'The recommended bundles include KataGo `{katago_version}` and the default weight `{model_source}`.',
                    'The Windows OpenCL build also supports **Smart Optimize** to save a better thread setting automatically.',
                    'The OpenCL Windows bundle is now the main recommended Windows choice for better analysis speed.',
                    'The CPU build remains available as a compatibility fallback when OpenCL is unstable.',
                    'The Windows NVIDIA bundle includes the official runtime files and can start offline on first launch.',
                ],
            },
            'download': {
                'heading': 'Download Guide',
                'headers': ('Your computer', 'Download this file'),
                'rows': [
                    ('Windows 64-bit, OpenCL, recommended and faster, no install', assets['windows_opencl_portable']),
                    ('Windows 64-bit, OpenCL, installer', assets['windows_opencl_installer']),
                    ('Windows 64-bit, CPU fallback, no install', assets['windows_portable']),
                    ('Windows 64-bit, CPU fallback, installer', assets['windows_installer']),
                    ('Windows 64-bit, NVIDIA GPU, faster, no install', assets['windows_nvidia_portable']),
                    ('Windows 64-bit, NVIDIA GPU, faster, installer', assets['windows_nvidia_installer']),
                    ('Windows 64-bit, configure your own engine', assets['windows_no_engine_portable']),
                    ('Windows 64-bit, configure your own engine, installer', assets['windows_no_engine_installer']),
                    ('macOS Apple Silicon', assets['mac_arm64']),
                    ('macOS Intel', assets['mac_amd64']),
                    ('Linux 64-bit, CPU fallback', assets['linux64']),
                    ('Linux 64-bit, OpenCL for AMD/Intel GPU', assets['linux64_opencl']),
                    ('Linux 64-bit, NVIDIA CUDA', assets['linux64_nvidia']),
                ],
            },
            'why': {
                'heading': 'Why This Release Is Worth Trying',
                'items': [
                    'Fox fetching remains available, and Tencent kifu support adds another common public game source.',
                    'Tencent kifu supports nickname/UID search, paged lists, and direct loading by double-clicking or pressing Open.',
                    "Windows `.portable.zip` packages are now emphasized because they fit most users' no-install workflow better.",
                    'Windows now offers both OpenCL and CPU builds, making it easier to choose speed-first or compatibility-first.',
                    'The OpenCL build is the recommended Windows package for faster analysis.',
                    'The OpenCL build supports Smart Optimize and saves the recommended thread count after benchmarking.',
                    'The CPU build remains available for PCs where OpenCL behaves poorly.',
                    'NVIDIA Windows users get an official CUDA KataGo bundle with required runtime files included.',
                    'macOS continues to provide separate Apple Silicon and Intel `.dmg` packages.',
                    'Bundled packages still include KataGo and the default weight, so analysis is ready faster after launch.',
                ],
            },
            'contact': {'heading': 'Contact', 'items': ['QQ group: `299419120`']},
        },
        {
            'language': '日本語',
            'intro': (
                'このリリースは、現在も保守されている `lizzieyzy` の保守版であり、一般ユーザー向けの `KataGo 復盤 GUI` です。'
                'このリリースでは野狐棋譜の隣に **Tencent 棋譜** 入口を追加し、Windows portable パッケージと KataGo の初期セットアップも引き続き使いやすく保っています。'
                'インストール後、野狐は **野狐ニックネーム**、Tencent は **Tencent ニックネームまたは数字 UID** で公開棋譜を検索し、分析・復盤できます。'
            ),
            'updates': {
                'heading': '主な更新',
                'items': [
                    '野狐棋譜の隣に Tencent 棋譜入口を追加し、Tencent Weiqi の公開棋譜をアプリ内から直接検索できるようにしました。',
                    'Tencent ニックネームまたは数字 UID による棋譜一覧検索に対応し、対局時間、黒白双方、段位、勝敗、手数を表示します。',
                    '棋譜行のダブルクリックまたは「開く」ボタンで Tencent 棋譜詳細を取得し、メイン碁盤に読み込んで分析できます。',
                    'Tencent 棋譜の検索とダウンロードは background worker で実行し、進捗表示、失敗時の操作復帰、明確なエラー表示を行います。',
                    'Tencent のプロ棋士段位表示を修正し、柯潔のような `P9段` が不正なアマ段位として表示されないようにしました。',
                    'リリース前に full test、package、4 プラットフォームの release workflow、実機ローカル起動 UI smoke test を再実行しました。',
                ],
            },
            'before': {
                'heading': 'ダウンロード前に',
                'items': [
                    f'多くの Windows ユーザーは {assets["windows_opencl_portable"]} を選ぶのがおすすめです。これは **推奨 OpenCL 版、インストール不要** です。',
                    f'OpenCL との相性が悪い場合は {assets["windows_portable"]} を使ってください。',
                    f'**NVIDIA GPU** 搭載 PC では {assets["windows_nvidia_portable"]} を優先してください。',
                    'インストーラ形式がよい場合は、同じ系列の `installer.exe` を選んでください。',
                    '野狐棋譜と Tencent 棋譜は別々の入口です。野狐は野狐ニックネーム、Tencent は Tencent ニックネームまたは数字 UID を入力します。',
                    f'推奨バンドルには KataGo `{katago_version}` と既定の重み `{model_source}` が含まれています。',
                    'Windows OpenCL 版も **Smart Optimize** に対応し、より適切なスレッド設定を自動保存できます。',
                    'Windows では OpenCL 版を推奨にし、より速い分析速度を優先しました。',
                    'CPU 版は OpenCL が不安定な環境向けの互換 fallback として残しています。',
                    'Windows NVIDIA バンドルには公式 runtime が含まれ、対応 PC では初回起動をオフラインで始められます。',
                ],
            },
            'download': {
                'heading': 'ダウンロード案内',
                'headers': ('お使いの環境', 'ダウンロードするファイル'),
                'rows': [
                    ('Windows 64-bit、OpenCL 推奨高速版、インストール不要', assets['windows_opencl_portable']),
                    ('Windows 64-bit、OpenCL 版、インストーラ', assets['windows_opencl_installer']),
                    ('Windows 64-bit、CPU fallback、インストール不要', assets['windows_portable']),
                    ('Windows 64-bit、CPU fallback、インストーラ', assets['windows_installer']),
                    ('Windows 64-bit、NVIDIA GPU、高速版、インストール不要', assets['windows_nvidia_portable']),
                    ('Windows 64-bit、NVIDIA GPU、高速版、インストーラ', assets['windows_nvidia_installer']),
                    ('Windows 64-bit、自分でエンジンを設定したい場合', assets['windows_no_engine_portable']),
                    ('Windows 64-bit、自分でエンジンを設定したい場合、インストーラ', assets['windows_no_engine_installer']),
                    ('macOS Apple Silicon', assets['mac_arm64']),
                    ('macOS Intel', assets['mac_amd64']),
                    ('Linux 64-bit、CPU fallback', assets['linux64']),
                    ('Linux 64-bit、OpenCL、AMD/Intel GPU', assets['linux64_opencl']),
                    ('Linux 64-bit、NVIDIA CUDA', assets['linux64_nvidia']),
                ],
            },
            'why': {
                'heading': 'このリリースを試す理由',
                'items': [
                    '野狐棋譜取得は引き続き利用でき、Tencent 棋譜対応により公開棋譜ソースがさらに増えました。',
                    'Tencent 棋譜はニックネーム/UID 検索、ページ付き一覧、ダブルクリックまたは「開く」による直接読み込みに対応します。',
                    'Windows では `.portable.zip` を推奨し、展開してすぐ使う一般的な利用方法に合わせました。',
                    'Windows では OpenCL 版と CPU 版を用意し、速度優先か互換優先かを選びやすくしました。',
                    'OpenCL 版は Windows の推奨パッケージで、より速い分析速度を狙えます。',
                    'OpenCL 版は Smart Optimize に対応し、benchmark 後に推奨スレッド数を保存できます。',
                    'CPU 版は OpenCL がうまく動かない PC 向けに残しています。',
                    'NVIDIA GPU の Windows ユーザー向けには、公式 CUDA KataGo と runtime を含むバンドルを用意しました。',
                    'macOS は Apple Silicon / Intel の 2 種類の `.dmg` を引き続き提供します。',
                    'バンドル版には KataGo と既定の重みが含まれ、起動後すばやく分析に入れます。',
                ],
            },
            'contact': {'heading': '連絡先', 'items': ['QQ グループ: `299419120`']},
        },
        {
            'language': '한국어',
            'intro': (
                '이 릴리스는 지금도 유지보수 중인 `lizzieyzy` 유지보수판이자, 일반 사용자를 위한 `KataGo 복기 GUI` 입니다. '
                '이번 릴리스는 Fox 기보 옆에 **Tencent 기보** 입구를 추가하고, Windows portable 패키지와 KataGo 첫 실행 경험도 계속 단순하게 유지합니다. '
                '설치 후 Fox 는 **Fox 닉네임**, Tencent 는 **Tencent 닉네임 또는 숫자 UID** 로 공개 기보를 검색해 분석하고 복기할 수 있습니다.'
            ),
            'updates': {
                'heading': '주요 업데이트',
                'items': [
                    'Fox 기보 옆에 Tencent 기보 입구를 추가해 Tencent Weiqi 공개 기보를 앱 안에서 바로 검색할 수 있게 했습니다.',
                    'Tencent 닉네임 또는 숫자 UID 로 기보 목록을 검색하고, 대국 시간, 흑/백 대국자, 단급, 결과, 수순 수를 표시합니다.',
                    '기보 행을 더블클릭하거나 Open 을 누르면 Tencent 기보 상세를 내려받아 메인 보드에 불러와 분석할 수 있습니다.',
                    'Tencent 기보 검색과 다운로드는 background worker 로 실행되며, 진행 표시, 실패 시 조작 복구, 명확한 오류 메시지를 제공합니다.',
                    'Tencent 프로 기사 단급 표시를 수정해 커제 같은 `P9段` 이 잘못된 아마추어 단급으로 보이지 않게 했습니다.',
                    '릴리스 전에 full test, package, 4개 플랫폼 release workflow, 실제 로컬 실행 UI smoke test 를 다시 수행했습니다.',
                ],
            },
            'before': {
                'heading': '다운로드 전 확인',
                'items': [
                    f'대부분의 Windows 사용자는 {assets["windows_opencl_portable"]} 를 받으면 됩니다. 이는 **추천 OpenCL 무설치 빌드** 입니다.',
                    f'OpenCL 이 PC에서 불안정하면 {assets["windows_portable"]} 를 대신 사용하세요.',
                    f'**NVIDIA GPU** 가 있다면 {assets["windows_nvidia_portable"]} 를 우선 사용해 보세요.',
                    '설치형 흐름을 원한다면 같은 계열의 `installer.exe` 를 고르세요.',
                    'Fox 기보와 Tencent 기보는 별도 입구입니다. Fox 는 Fox 닉네임, Tencent 는 Tencent 닉네임 또는 숫자 UID 를 입력하세요.',
                    f'추천 번들에는 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 가 포함되어 있습니다.',
                    'Windows OpenCL 빌드도 **Smart Optimize** 를 지원해 더 적절한 스레드 설정을 자동 저장할 수 있습니다.',
                    'Windows에서는 더 빠른 분석 속도를 위해 OpenCL 빌드를 추천으로 올렸습니다.',
                    'CPU 빌드는 OpenCL 이 불안정한 환경을 위한 호환 fallback 으로 유지합니다.',
                    'Windows NVIDIA 번들에는 공식 runtime 파일이 포함되어 지원 PC에서 첫 실행을 오프라인으로 시작할 수 있습니다.',
                ],
            },
            'download': {
                'heading': '다운로드 안내',
                'headers': ('내 컴퓨터', '다운로드할 파일'),
                'rows': [
                    ('Windows 64-bit, OpenCL 추천 고속판, 무설치', assets['windows_opencl_portable']),
                    ('Windows 64-bit, OpenCL, 설치형', assets['windows_opencl_installer']),
                    ('Windows 64-bit, CPU fallback, 무설치', assets['windows_portable']),
                    ('Windows 64-bit, CPU fallback, 설치형', assets['windows_installer']),
                    ('Windows 64-bit, NVIDIA GPU, 고속판, 무설치', assets['windows_nvidia_portable']),
                    ('Windows 64-bit, NVIDIA GPU, 고속판, 설치형', assets['windows_nvidia_installer']),
                    ('Windows 64-bit, 직접 엔진 설정', assets['windows_no_engine_portable']),
                    ('Windows 64-bit, 직접 엔진 설정, 설치형', assets['windows_no_engine_installer']),
                    ('macOS Apple Silicon', assets['mac_arm64']),
                    ('macOS Intel', assets['mac_amd64']),
                    ('Linux 64-bit, CPU fallback', assets['linux64']),
                    ('Linux 64-bit, OpenCL, AMD/Intel GPU', assets['linux64_opencl']),
                    ('Linux 64-bit, NVIDIA CUDA', assets['linux64_nvidia']),
                ],
            },
            'why': {
                'heading': '이번 릴리스를 먼저 볼 이유',
                'items': [
                    'Fox 기보 가져오기는 계속 사용할 수 있고, Tencent 기보 지원으로 공개 기보 소스가 하나 더 늘었습니다.',
                    'Tencent 기보는 닉네임/UID 검색, 페이지 목록, 더블클릭 또는 Open 으로 직접 불러오기를 지원합니다.',
                    'Windows `.portable.zip` 패키지를 추천으로 두어 압축 해제 후 바로 쓰는 일반적인 흐름에 맞췄습니다.',
                    'Windows 에서는 OpenCL 과 CPU 빌드를 모두 제공해 속도 우선 또는 호환 우선을 쉽게 선택할 수 있습니다.',
                    'OpenCL 빌드는 더 빠른 분석 속도를 위한 Windows 추천 패키지입니다.',
                    'OpenCL 빌드는 Smart Optimize 를 지원하며 벤치마크 후 추천 스레드 수를 저장합니다.',
                    'CPU 빌드는 OpenCL 이 잘 맞지 않는 PC 를 위해 유지합니다.',
                    'NVIDIA GPU Windows 사용자를 위해 공식 CUDA KataGo 와 runtime 이 포함된 번들을 제공합니다.',
                    'macOS 는 Apple Silicon / Intel `.dmg` 를 계속 따로 제공합니다.',
                    '번들 패키지에는 KataGo 와 기본 가중치가 들어 있어 실행 후 더 빨리 분석을 시작할 수 있습니다.',
                ],
            },
            'contact': {'heading': '연락처', 'items': ['QQ 그룹: `299419120`']},
        },
        {
            'language': 'ภาษาไทย',
            'intro': (
                'รีลีสนี้คือ fork `lizzieyzy` ที่ยังดูแลต่อเนื่อง และเป็น `KataGo review GUI` สำหรับผู้ใช้ทั่วไป '
                'เวอร์ชันนี้เพิ่มปุ่ม **Tencent kifu** ข้างปุ่ม Fox kifu พร้อมคงประสบการณ์ Windows portable และ KataGo ให้ใช้งานง่ายเหมือนเดิม '
                'หลังดาวน์โหลดและติดตั้ง ใช้ **ชื่อเล่น Fox** สำหรับ Fox หรือใช้ **ชื่อเล่น Tencent / UID ตัวเลข** เพื่อค้นหาเกมสาธารณะของ Tencent Weiqi มาวิเคราะห์และทบทวนได้'
            ),
            'updates': {
                'heading': 'ไฮไลต์ของเวอร์ชันนี้',
                'items': [
                    'เพิ่มปุ่ม Tencent kifu ข้าง Fox kifu เพื่อค้นหาเกมสาธารณะของ Tencent Weiqi ได้จากในแอปโดยตรง',
                    'รองรับการค้นหารายการเกมด้วยชื่อเล่น Tencent หรือ UID ตัวเลข และแสดงเวลาแข่ง ผู้เล่นดำ/ขาว ระดับ ผลแพ้ชนะ และจำนวนหมาก',
                    'ดับเบิลคลิกแถวเกมหรือกด Open เพื่อดาวน์โหลดรายละเอียด Tencent kifu แล้วโหลดเข้า board หลักเพื่อวิเคราะห์',
                    'การค้นหาและดาวน์โหลด Tencent kifu ทำงานใน background worker พร้อม feedback ความคืบหน้า คืนค่าปุ่มเมื่อเกิดข้อผิดพลาด และแสดง error ชัดเจน',
                    'แก้การแสดงระดับมืออาชีพของ Tencent เช่น Ke Jie จะแสดงเป็น `P9段` ไม่ใช่ระดับสมัครเล่นที่ผิดปกติ',
                    'ก่อนปล่อยเวอร์ชันนี้ ได้รัน full test, package, release workflow ครบ 4 แพลตฟอร์ม และทดสอบเปิดแอปจริงบนเครื่อง local แล้ว',
                ],
            },
            'before': {
                'heading': 'ก่อนดาวน์โหลด ดูตรงนี้ก่อน',
                'items': [
                    f'ผู้ใช้ Windows ทั่วไปให้ดาวน์โหลด {assets["windows_opencl_portable"]} ซึ่งเป็น **OpenCL รุ่นแนะนำ แบบไม่ต้องติดตั้ง**',
                    f'ถ้า OpenCL ทำงานไม่ดีบนเครื่องของคุณ ให้เปลี่ยนไปใช้ {assets["windows_portable"]}',
                    f'ถ้าเครื่องของคุณมี **การ์ดจอ NVIDIA** แนะนำให้ใช้ {assets["windows_nvidia_portable"]}',
                    'ถ้าชอบขั้นตอนแบบติดตั้ง ให้เลือกไฟล์ `installer.exe` ในชุดเดียวกัน',
                    'Fox kifu และ Tencent kifu เป็นคนละปุ่ม: Fox ใช้ชื่อเล่น Fox ส่วน Tencent ใช้ชื่อเล่น Tencent หรือ UID ตัวเลข',
                    f'แพ็กเกจหลักมี KataGo `{katago_version}` และน้ำหนักเริ่มต้น `{model_source}` มาให้แล้ว',
                    'Windows OpenCL build รองรับ **Smart Optimize** เพื่อเขียนค่าจำนวนเธรดที่เหมาะสมกว่าโดยอัตโนมัติ',
                    'Windows ตอนนี้แนะนำ OpenCL build เป็นตัวหลัก เพื่อให้ได้ความเร็ววิเคราะห์ที่ดีกว่า',
                    'CPU build ยังเก็บไว้เป็นทางเลือกสำรองสำหรับเครื่องที่ OpenCL ไม่เสถียร',
                    'Windows NVIDIA bundle ใส่ runtime ทางการมาให้แล้ว เปิดครั้งแรกแบบ offline ได้บนเครื่องที่รองรับ',
                ],
            },
            'download': {
                'heading': 'แนะนำการดาวน์โหลด',
                'headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
                'rows': [
                    ('Windows 64-bit, OpenCL, แนะนำและเร็วกว่า, ไม่ต้องติดตั้ง', assets['windows_opencl_portable']),
                    ('Windows 64-bit, OpenCL, แบบติดตั้ง', assets['windows_opencl_installer']),
                    ('Windows 64-bit, CPU fallback, ไม่ต้องติดตั้ง', assets['windows_portable']),
                    ('Windows 64-bit, CPU fallback, แบบติดตั้ง', assets['windows_installer']),
                    ('Windows 64-bit, การ์ดจอ NVIDIA, เร็วกว่า, ไม่ต้องติดตั้ง', assets['windows_nvidia_portable']),
                    ('Windows 64-bit, การ์ดจอ NVIDIA, เร็วกว่า, แบบติดตั้ง', assets['windows_nvidia_installer']),
                    ('Windows 64-bit, ต้องการตั้งค่า engine เอง', assets['windows_no_engine_portable']),
                    ('Windows 64-bit, ต้องการตั้งค่า engine เองและอยากใช้ installer', assets['windows_no_engine_installer']),
                    ('macOS Apple Silicon', assets['mac_arm64']),
                    ('macOS Intel', assets['mac_amd64']),
                    ('Linux 64-bit, CPU fallback', assets['linux64']),
                    ('Linux 64-bit, OpenCL สำหรับ AMD/Intel GPU', assets['linux64_opencl']),
                    ('Linux 64-bit, NVIDIA CUDA', assets['linux64_nvidia']),
                ],
            },
            'why': {
                'heading': 'ทำไมเวอร์ชันนี้ควรลองก่อน',
                'items': [
                    'การดึงเกมจาก Fox ยังใช้ได้ต่อไป และเพิ่ม Tencent kifu เป็นแหล่งเกมสาธารณะอีกทางหนึ่ง',
                    'Tencent kifu รองรับค้นหาด้วยชื่อเล่น/UID, รายการแบบแบ่งหน้า, และโหลดเข้ากระดานด้วยการดับเบิลคลิกหรือกด Open',
                    'Windows ให้ความสำคัญกับ `.portable.zip` มากขึ้น แตกไฟล์แล้วใช้ได้ เหมาะกับผู้ใช้ส่วนใหญ่',
                    'Windows มีทั้ง OpenCL และ CPU build ให้เลือกตาม “ความเร็ว” หรือ “ความเข้ากันได้”',
                    'Windows OpenCL build เป็นตัวแนะนำหลัก เพื่อความเร็ววิเคราะห์ที่ดีกว่า',
                    'Windows OpenCL build รองรับ Smart Optimize และจะบันทึกจำนวนเธรดที่แนะนำหลัง benchmark',
                    'Windows CPU build ยังอยู่ สำหรับเครื่องที่ OpenCL ทำงานไม่ดี',
                    'สำหรับผู้ใช้ Windows ที่มี NVIDIA GPU มี CUDA KataGo bundle ทางการพร้อม runtime ในแพ็กเกจเดียว',
                    'macOS ยังมี `.dmg` แยกสำหรับ Apple Silicon และ Intel',
                    'แพ็กเกจรวมยังมี KataGo และน้ำหนักเริ่มต้น ทำให้เข้าโหมดวิเคราะห์ได้เร็วขึ้น',
                ],
            },
            'contact': {'heading': 'ติดต่อ', 'items': ['QQ group: `299419120`']},
        },
    ]

    add_nvidia50_download_rows(sections, assets_cn, assets)
    add_tensorrt_split_download_row(sections, assets_cn, assets, asset_map)
    validate_release_sections(sections)

    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_05_18_1_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {key: format_asset(asset_map[key], repo, release_tag) for key in asset_map}
    assets = {key: format_asset_en(asset_map[key], repo, release_tag) for key in asset_map}
    katago_version = bundle['katago_version']
    model_source = bundle['model_source']
    sections: list[dict[str, object]] = [
        {
            'language': '中文',
            'intro': '这是“4段纪念版”的一键设置体验修正版。重点根据真实测试反馈继续打磨 KataGo 一键设置：智能提升算棋速度的进度更稳，权重管理更容易理解，按钮和分区命名也更贴近普通用户。',
            'updates': {
                'heading': '本版主要更新',
                'items': [
                    '智能提升算棋速度继续使用 KataGo 官方 benchmark，但进度条改为按“已完成的测试局面”推进，不再在第 4 项测试时乱跳或长时间停在 88%。',
                    '一键设置左侧顺序调整为“总览、权重、智能提升算棋速度、英伟达显卡提速组件”，把更常用的速度提升入口放到前面。',
                    '把“测速/智能测速优化/加速组件”等容易让用户紧张的说法，统一改为“智能提升算棋速度”和“英伟达显卡提速组件”。',
                    '权重管理继续优化：可下载官方权重显示模型简称、发布日期、已下载/正在使用等重点信息；已下载权重和下载按钮位置更清楚。',
                    '发布前已跑全量测试、重新打包，并本机真实启动应用检查一键设置界面和 Apple Silicon 智能提升流程。',
                ],
            },
            'before': {
                'heading': '下载前先看这几句',
                'items': [
                    f'Windows 普通用户优先下载 {assets_cn["windows_opencl_portable"]}，这是 **OpenCL 版（推荐，免安装）**。',
                    f'如果 OpenCL 在你的电脑上不稳定，再改用 {assets_cn["windows_portable"]}。',
                    f'如果你的电脑是 **英伟达显卡**，优先下载 {assets_cn["windows_nvidia_portable"]}。',
                    f'主推荐整合包已内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
                    '如果你更喜欢安装流程，再选同系列的 `installer.exe`。',
                ],
            },
            'download': {
                'heading': '下载建议',
                'headers': ('你的电脑', '直接下载这个'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['zh'], assets_cn),
            },
            'why': {
                'heading': '这一版为什么值得更新',
                'items': [
                    '第一次打开或手动提升算棋速度时，进度条更像真实任务进度，不会给人“卡住了”的感觉。',
                    '新用户能更快看懂权重下载、已下载权重、智能提升和英伟达组件分别是做什么的。',
                    '这是一个针对一键设置高频入口的小版本修复，建议替换上一版继续测试。',
                ],
            },
            'contact': {'heading': '交流', 'items': ['QQ 群：`299419120`']},
        },
        {
            'language': '繁體中文',
            'intro': '這是「4 段紀念版」的一鍵設定體驗修正版。重點依照真實測試回饋繼續打磨 KataGo 一鍵設定：智慧提升算棋速度的進度更穩，權重管理更容易理解，按鈕和分區命名也更貼近一般使用者。',
            'updates': {
                'heading': '本版主要更新',
                'items': [
                    '智慧提升算棋速度繼續使用 KataGo 官方 benchmark，但進度條改為依照「已完成的測試局面」推進，不再在第 4 項測試時亂跳或長時間停在 88%。',
                    '一鍵設定左側順序調整為「總覽、權重、智慧提升算棋速度、NVIDIA 顯示卡提速元件」，把更常用的速度提升入口放到前面。',
                    '把「測速/智慧測速最佳化/加速元件」等容易讓使用者緊張的說法，統一改為「智慧提升算棋速度」和「NVIDIA 顯示卡提速元件」。',
                    '權重管理繼續最佳化：可下載官方權重顯示模型簡稱、發布日期、已下載/正在使用等重點資訊；已下載權重和下載按鈕位置更清楚。',
                    '發布前已跑完整測試、重新打包，並在本機真實啟動應用檢查一鍵設定介面和 Apple Silicon 智慧提升流程。',
                ],
            },
            'before': {
                'heading': '下載前先看這幾句',
                'items': [
                    f'Windows 一般使用者優先下載 {assets_cn["windows_opencl_portable"]}，這是 **OpenCL 版（推薦，免安裝）**。',
                    f'如果 OpenCL 在你的電腦上不穩定，再改用 {assets_cn["windows_portable"]}。',
                    f'如果你的電腦是 **NVIDIA 顯示卡**，優先下載 {assets_cn["windows_nvidia_portable"]}。',
                    f'主推薦整合包已內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
                    '如果你更喜歡安裝流程，再選同系列的 `installer.exe`。',
                ],
            },
            'download': {
                'heading': '下載建議',
                'headers': ('你的電腦', '直接下載這個'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['zh_hant'], assets_cn),
            },
            'why': {
                'heading': '這一版為什麼值得更新',
                'items': [
                    '第一次開啟或手動提升算棋速度時，進度條更接近真實任務進度，不會讓人覺得卡住。',
                    '新使用者能更快看懂權重下載、已下載權重、智慧提升和 NVIDIA 元件分別是做什麼的。',
                    '這是針對一鍵設定高頻入口的小版本修復，建議替換上一版繼續測試。',
                ],
            },
            'contact': {'heading': '交流', 'items': ['QQ 群：`299419120`']},
        },
        {
            'language': 'English',
            'intro': 'This is a one-click setup polish update for the “4-dan commemorative build”. It focuses on the real feedback from testing: steadier Smart Reading Speed Boost progress, clearer weight management, and friendlier names for the benchmark and NVIDIA component areas.',
            'updates': {
                'heading': 'Release Highlights',
                'items': [
                    'Smart Reading Speed Boost still uses the official KataGo benchmark, but the progress bar now advances by completed benchmark positions instead of time guesses, so the fourth test phase no longer jumps around or appears stuck at 88%.',
                    'KataGo Auto Setup now orders the sidebar as Overview, Weights, Smart Reading Speed Boost, and NVIDIA GPU Speed Components, putting the more common speed-improvement action first.',
                    'User-facing wording was softened from benchmark/optimization/acceleration jargon to Smart Reading Speed Boost and NVIDIA GPU Speed Components.',
                    'Weight management is clearer: downloadable official weights show the model short name, release date, downloaded/current markers, and the download action sits next to the official-weight selector.',
                    'Before release, full tests, packaging, and a real local app launch were rerun, including the Auto Setup UI and Apple Silicon speed-boost flow.',
                ],
            },
            'before': {
                'heading': 'Read Before Downloading',
                'items': [
                    f'Most Windows users should download {assets["windows_opencl_portable"]}, the **recommended no-install OpenCL build**.',
                    f'If OpenCL is unreliable on your PC, use {assets["windows_portable"]} instead.',
                    f'If your PC has an **NVIDIA GPU**, try {assets["windows_nvidia_portable"]} first.',
                    f'The recommended bundles include KataGo `{katago_version}` and the default weight `{model_source}`.',
                    'If you prefer an installer, choose the matching `installer.exe` package.',
                ],
            },
            'download': {
                'heading': 'Download Guide',
                'headers': ('Your computer', 'Download this file'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['en'], assets),
            },
            'why': {
                'heading': 'Why This Release Is Worth Updating',
                'items': [
                    'First launch and manual speed boosting now feel more like real task progress rather than a frozen dialog.',
                    'New users can understand downloaded weights, official downloadable weights, smart speed boosting, and NVIDIA components faster.',
                    'This is a focused quality update for the high-traffic KataGo Auto Setup flow, suitable for replacing the previous build.',
                ],
            },
            'contact': {'heading': 'Contact', 'items': ['QQ group: `299419120`']},
        },
        {
            'language': '日本語',
            'intro': 'これは「4 段記念版」の一括設定体験を整える更新です。実際のテストフィードバックをもとに、Smart Reading Speed Boost の進捗を安定させ、重み管理を分かりやすくし、benchmark や NVIDIA 関連の表示名を一般ユーザー向けに整理しました。',
            'updates': {
                'heading': '主な更新',
                'items': [
                    'Smart Reading Speed Boost は公式 KataGo benchmark を使い続けますが、進捗バーは時間推定ではなく完了したテスト局面に合わせて進みます。第 4 テストで跳ねたり 88% 付近で止まって見える問題を減らしました。',
                    'KataGo 自動設定のサイドバー順を Overview、Weights、Smart Reading Speed Boost、NVIDIA GPU Speed Components に変更し、よく使う速度改善を前に出しました。',
                    'benchmark/optimization/acceleration という硬い表現を、Smart Reading Speed Boost と NVIDIA GPU Speed Components に整理しました。',
                    '重み管理を改善し、ダウンロード可能な公式重みにモデル短縮名、公開日、ダウンロード済み/使用中の印を表示し、ダウンロード操作も公式重み選択の近くに置きました。',
                    'リリース前に full test、package、実機ローカル起動を再実行し、自動設定 UI と Apple Silicon 速度改善フローを確認しました。',
                ],
            },
            'before': {
                'heading': 'ダウンロード前に',
                'items': [
                    f'多くの Windows ユーザーは {assets["windows_opencl_portable"]} を選ぶのがおすすめです。これは **推奨 OpenCL 版、インストール不要** です。',
                    f'OpenCL が不安定な場合は {assets["windows_portable"]} を使ってください。',
                    f'**NVIDIA GPU** 搭載 PC では {assets["windows_nvidia_portable"]} を優先してください。',
                    f'推奨バンドルには KataGo `{katago_version}` と既定の重み `{model_source}` が含まれています。',
                    'インストーラ形式がよい場合は、同じ系列の `installer.exe` を選んでください。',
                ],
            },
            'download': {
                'heading': 'ダウンロード案内',
                'headers': ('お使いの環境', 'ダウンロードするファイル'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['ja'], assets),
            },
            'why': {
                'heading': 'このリリースを更新する理由',
                'items': [
                    '初回起動や手動の速度改善で、固まった画面ではなく実際に進んでいる処理として感じやすくなりました。',
                    '新規ユーザーが、ダウンロード済み重み、公式重み、速度改善、NVIDIA 部品の役割を理解しやすくなりました。',
                    '利用頻度の高い KataGo 自動設定に絞った品質改善版で、前回ビルドの置き換えに向いています。',
                ],
            },
            'contact': {'heading': '連絡先', 'items': ['QQ グループ: `299419120`']},
        },
        {
            'language': '한국어',
            'intro': '이번 버전은 “4단 기념판”의 원클릭 설정 경험을 다듬은 업데이트입니다. 실제 테스트 피드백을 바탕으로 Smart Reading Speed Boost 진행률을 안정화하고, 가중치 관리와 NVIDIA 관련 영역 이름을 일반 사용자에게 더 이해하기 쉽게 정리했습니다.',
            'updates': {
                'heading': '주요 업데이트',
                'items': [
                    'Smart Reading Speed Boost 는 공식 KataGo benchmark 를 계속 사용하지만, 진행률은 시간 추정이 아니라 완료된 테스트 포지션 기준으로 움직입니다. 네 번째 테스트에서 튀거나 88% 에서 멈춘 것처럼 보이는 문제를 줄였습니다.',
                    'KataGo 자동 설정 사이드바 순서를 Overview, Weights, Smart Reading Speed Boost, NVIDIA GPU Speed Components 로 바꿔 더 자주 쓰는 속도 개선 항목을 앞에 두었습니다.',
                    'benchmark/optimization/acceleration 처럼 딱딱한 표현을 Smart Reading Speed Boost 와 NVIDIA GPU Speed Components 로 정리했습니다.',
                    '가중치 관리가 더 명확해졌습니다. 다운로드 가능한 공식 가중치는 모델 짧은 이름, 공개일, 다운로드됨/사용 중 표시를 보여 주고, 다운로드 버튼도 공식 가중치 선택 옆에 배치했습니다.',
                    '릴리스 전에 full test, package, 실제 로컬 앱 실행을 다시 수행했고, 자동 설정 UI 와 Apple Silicon speed-boost 흐름을 확인했습니다.',
                ],
            },
            'before': {
                'heading': '다운로드 전 확인',
                'items': [
                    f'대부분의 Windows 사용자는 {assets["windows_opencl_portable"]} 를 먼저 받으면 됩니다. 이는 **추천 OpenCL 무설치 빌드** 입니다.',
                    f'OpenCL 이 PC에서 불안정하면 {assets["windows_portable"]} 를 대신 사용하세요.',
                    f'**NVIDIA GPU** 가 있다면 {assets["windows_nvidia_portable"]} 를 우선 사용해 보세요.',
                    f'추천 번들에는 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 가 포함되어 있습니다.',
                    '설치형 흐름을 원한다면 같은 계열의 `installer.exe` 를 고르세요.',
                ],
            },
            'download': {
                'heading': '다운로드 안내',
                'headers': ('내 컴퓨터', '다운로드할 파일'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['ko'], assets),
            },
            'why': {
                'heading': '이번 릴리스를 업데이트할 이유',
                'items': [
                    '첫 실행이나 수동 속도 개선이 멈춘 창처럼 보이지 않고 실제 작업이 진행되는 느낌에 가까워졌습니다.',
                    '새 사용자가 다운로드된 가중치, 공식 다운로드 가중치, 속도 개선, NVIDIA 구성 요소의 역할을 더 빨리 이해할 수 있습니다.',
                    '사용 빈도가 높은 KataGo 자동 설정 흐름에 집중한 품질 개선판으로, 이전 빌드를 대체해 테스트하기 좋습니다.',
                ],
            },
            'contact': {'heading': '연락처', 'items': ['QQ 그룹: `299419120`']},
        },
        {
            'language': 'ภาษาไทย',
            'intro': 'นี่คืออัปเดตปรับประสบการณ์ One-click setup สำหรับ “เวอร์ชันที่ระลึก 4 ดั้ง” โดยเน้น feedback จากการทดสอบจริง: progress ของ Smart Reading Speed Boost นิ่งขึ้น, จัดการ weight เข้าใจง่ายขึ้น, และชื่อหมวด benchmark / NVIDIA เป็นมิตรกับผู้ใช้ทั่วไปมากขึ้น',
            'updates': {
                'heading': 'ไฮไลต์ของเวอร์ชันนี้',
                'items': [
                    'Smart Reading Speed Boost ยังใช้ benchmark ทางการของ KataGo แต่ progress bar จะเดินตามจำนวน position ที่ทดสอบเสร็จแล้ว ไม่ใช้การเดาเวลา ทำให้ช่วงทดสอบที่ 4 ไม่กระโดดไปมาและไม่เหมือนค้างที่ 88%',
                    'KataGo Auto Setup จัด sidebar ใหม่เป็น Overview, Weights, Smart Reading Speed Boost, NVIDIA GPU Speed Components โดยวางฟังก์ชันเพิ่มความเร็วที่ใช้บ่อยไว้ก่อน',
                    'ปรับคำที่ผู้ใช้เห็นจาก benchmark/optimization/acceleration ให้เป็น Smart Reading Speed Boost และ NVIDIA GPU Speed Components ที่เข้าใจง่ายกว่า',
                    'Weight management ชัดขึ้น: รายการ weight ทางการที่ดาวน์โหลดได้จะแสดงชื่อรุ่นแบบสั้น วันที่เผยแพร่ สถานะ downloaded/current และปุ่ม download อยู่ใกล้ตัวเลือก official weight',
                    'ก่อน release ได้รัน full test, package และเปิดแอปจริงบนเครื่อง local เพื่อตรวจ Auto Setup UI กับ Apple Silicon speed-boost flow แล้ว',
                ],
            },
            'before': {
                'heading': 'ก่อนดาวน์โหลด ดูตรงนี้ก่อน',
                'items': [
                    f'ผู้ใช้ Windows ส่วนใหญ่แนะนำให้ดาวน์โหลด {assets["windows_opencl_portable"]} ซึ่งเป็น **OpenCL รุ่นแนะนำ แบบไม่ต้องติดตั้ง**',
                    f'ถ้า OpenCL ทำงานไม่ดีบนเครื่องของคุณ ให้เปลี่ยนไปใช้ {assets["windows_portable"]}',
                    f'ถ้าเครื่องของคุณมี **การ์ดจอ NVIDIA** แนะนำให้ใช้ {assets["windows_nvidia_portable"]}',
                    f'แพ็กเกจหลักมี KataGo `{katago_version}` และ weight เริ่มต้น `{model_source}` มาให้แล้ว',
                    'ถ้าชอบขั้นตอนแบบติดตั้ง ให้เลือกไฟล์ `installer.exe` ในชุดเดียวกัน',
                ],
            },
            'download': {
                'heading': 'แนะนำการดาวน์โหลด',
                'headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['th'], assets),
            },
            'why': {
                'heading': 'ทำไมเวอร์ชันนี้ควรอัปเดต',
                'items': [
                    'การเปิดครั้งแรกหรือการเพิ่มความเร็วแบบ manual จะดูเหมือนงานที่กำลังเดินจริง ไม่ใช่หน้าต่างที่ค้างอยู่',
                    'ผู้ใช้ใหม่จะเข้าใจ downloaded weights, official downloadable weights, smart speed boost และ NVIDIA components ได้เร็วขึ้น',
                    'เป็นอัปเดตคุณภาพที่โฟกัส flow KataGo Auto Setup ซึ่งใช้งานบ่อย เหมาะสำหรับแทนที่ build ก่อนหน้า',
                ],
            },
            'contact': {'heading': 'ติดต่อ', 'items': ['QQ group: `299419120`']},
        },
    ]
    add_nvidia50_download_rows(sections, assets_cn, assets)
    validate_release_sections(sections)
    heading = f'# LizzieYzy Next {release_tag} 4段纪念版更新' if release_tag else '# LizzieYzy Next 4段纪念版更新'
    return heading + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_06_09_1_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {key: format_asset(asset_map[key], repo, release_tag) for key in asset_map}
    assets = {key: format_asset_en(asset_map[key], repo, release_tag) for key in asset_map}
    katago_version = bundle['katago_version']
    model_source = bundle['model_source']
    sections: list[dict[str, object]] = [
        {
            'language': '中文',
            'intro': '这是棋力评估界面修正版。重点把“测评”和“吻合度”做成更适合普通用户阅读的卡片式界面，并修复真实测试中发现的遮挡、刻度和图标显示问题。',
            'updates': {
                'heading': '本版主要更新',
                'items': [
                    '重做“棋力评估”主界面，只保留黑棋和白棋表现，默认不再显示普通用户看不懂的合计棋力。',
                    '“吻合度”改为原始横条风格的选点命中图：上层显示 AI 一选，下层显示好手，鼠标移动到命中点可查看具体手数和损失信息。',
                    '修复黑棋/白棋统计文字被图表遮挡的问题，右侧概率和说明现在会绘制在上层。',
                    '修复底部手数刻度显示不全和尾部 `151 154` 这类重复拥挤的问题，尾手会自动替代过近刻度。',
                    '修复“详细数据”和说明条里的叹号图标裁切问题，并重绘顶部围棋图标，避免左侧出现异常边缘。',
                    '发布前已重新跑棋力评估回归测试、打包验证，并完成本机真实启动 UI 复测。',
                ],
            },
            'before': {
                'heading': '下载前先看这几句',
                'items': [
                    f'Windows 普通用户优先下载 {assets_cn["windows_opencl_portable"]}，这是 **OpenCL 版（推荐，免安装）**。',
                    f'如果 OpenCL 在你的电脑上不稳定，再改用 {assets_cn["windows_portable"]}。',
                    f'如果你的电脑是 **英伟达显卡**，优先下载 {assets_cn["windows_nvidia_portable"]}。',
                    f'主推荐整合包已内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
                    '如果你更喜欢安装流程，再选同系列的 `installer.exe`。',
                ],
            },
            'download': {
                'heading': '下载建议',
                'headers': ('你的电脑', '直接下载这个'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['zh'], assets_cn),
            },
            'why': {
                'heading': '这一版为什么值得更新',
                'items': [
                    '棋力评估不再像调试表格，更接近用户能直接看懂的复盘结果页。',
                    '吻合度页面能一眼看到哪些手命中 AI 一选、哪些手属于好手，少了看不懂的趋势噪音。',
                    '这是一个针对高频查看页面的视觉和可读性修复，建议替换上一版继续测试。',
                ],
            },
            'contact': {'heading': '交流', 'items': ['QQ 群：`299419120`']},
        },
        {
            'language': '繁體中文',
            'intro': '這是棋力評估介面修正版。重點把「測評」和「吻合度」做成更適合一般使用者閱讀的卡片式介面，並修復真實測試中發現的遮擋、刻度和圖示顯示問題。',
            'updates': {
                'heading': '本版主要更新',
                'items': [
                    '重做「棋力評估」主介面，只保留黑棋和白棋表現，預設不再顯示一般使用者看不懂的合計棋力。',
                    '「吻合度」改為原始橫條風格的選點命中圖：上層顯示 AI 一選，下層顯示好手，滑鼠移到命中點可查看具體手數和損失資訊。',
                    '修復黑棋/白棋統計文字被圖表遮擋的問題，右側機率和說明現在會繪製在上層。',
                    '修復底部手數刻度顯示不全和尾部 `151 154` 這類重複擁擠問題，尾手會自動替代過近刻度。',
                    '修復「詳細資料」和說明條裡的驚嘆號圖示裁切問題，並重繪頂部圍棋圖示，避免左側出現異常邊緣。',
                    '發布前已重新跑棋力評估回歸測試、打包驗證，並完成本機真實啟動 UI 複測。',
                ],
            },
            'before': {
                'heading': '下載前先看這幾句',
                'items': [
                    f'Windows 一般使用者優先下載 {assets_cn["windows_opencl_portable"]}，這是 **OpenCL 版（推薦，免安裝）**。',
                    f'如果 OpenCL 在你的電腦上不穩定，再改用 {assets_cn["windows_portable"]}。',
                    f'如果你的電腦是 **NVIDIA 顯示卡**，優先下載 {assets_cn["windows_nvidia_portable"]}。',
                    f'主推薦整合包已內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
                    '如果你更喜歡安裝流程，再選同系列的 `installer.exe`。',
                ],
            },
            'download': {
                'heading': '下載建議',
                'headers': ('你的電腦', '直接下載這個'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['zh_hant'], assets_cn),
            },
            'why': {
                'heading': '這一版為什麼值得更新',
                'items': [
                    '棋力評估不再像除錯表格，更接近使用者能直接看懂的復盤結果頁。',
                    '吻合度頁面能一眼看到哪些手命中 AI 一選、哪些手屬於好手，少了看不懂的趨勢噪音。',
                    '這是針對高頻查看頁面的視覺和可讀性修復，建議替換上一版繼續測試。',
                ],
            },
            'contact': {'heading': '交流', 'items': ['QQ 群：`299419120`']},
        },
        {
            'language': 'English',
            'intro': 'This is a player-strength estimate UI polish update. It turns Assessment and Match Rate into a clearer card-based view and fixes the overlap, axis-label, and icon clipping issues found during real UI testing.',
            'updates': {
                'heading': 'Release Highlights',
                'items': [
                    'Redesigned the Player Strength Estimate dashboard around Black and White performance only, hiding the confusing combined-strength summary from the default view.',
                    'Match Rate now uses an original-style hit map: the upper lane shows AI first-choice hits, the lower lane shows good moves, and hovering over a hit reveals the move number and loss details.',
                    'Fixed Black/White stat labels being covered by the chart; the right-side percentages and descriptions are now painted above the graph layer.',
                    'Fixed clipped bottom move-number labels and crowded tail labels such as `151 154`; the final move now replaces nearby ticks when needed.',
                    'Fixed clipped exclamation icons in Detail Data and note strips, and redrew the header Go-stone mark to avoid odd left-edge artifacts.',
                    'Before release, the player-strength regression tests, packaging build, and real local UI launch checks were rerun.',
                ],
            },
            'before': {
                'heading': 'Read Before Downloading',
                'items': [
                    f'Most Windows users should download {assets["windows_opencl_portable"]}, the **recommended no-install OpenCL build**.',
                    f'If OpenCL is unreliable on your PC, use {assets["windows_portable"]} instead.',
                    f'If your PC has an **NVIDIA GPU**, try {assets["windows_nvidia_portable"]} first.',
                    f'The recommended bundles include KataGo `{katago_version}` and the default weight `{model_source}`.',
                    'If you prefer an installer, choose the matching `installer.exe` package.',
                ],
            },
            'download': {
                'heading': 'Download Guide',
                'headers': ('Your computer', 'Download this file'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['en'], assets),
            },
            'why': {
                'heading': 'Why This Release Is Worth Updating',
                'items': [
                    'Player Strength Estimate now feels like a readable review result page instead of a debug table.',
                    'The Match Rate page makes first-choice hits and good moves visible at a glance without an unreadable trend chart.',
                    'This is a focused visual and readability fix for a high-traffic analysis view, suitable for replacing the previous build.',
                ],
            },
            'contact': {'heading': 'Contact', 'items': ['QQ group: `299419120`']},
        },
        {
            'language': '日本語',
            'intro': 'これは棋力評価 UI を整える更新です。Assessment と Match Rate を読みやすいカード型画面にし、実際の UI テストで見つかった重なり、軸ラベル、アイコンの欠けを修正しました。',
            'updates': {
                'heading': '主な更新',
                'items': [
                    '棋力評価ダッシュボードを黒番と白番の成績中心に再設計し、既定表示では分かりにくい合計棋力を出さないようにしました。',
                    'Match Rate は元の横バー風の命中図に変更しました。上段は AI 一選、下段は好手を示し、命中点にマウスを置くと手数と損失情報を確認できます。',
                    '黒番/白番の統計文字がグラフに隠れる問題を修正し、右側の割合と説明をグラフ層の上に描画するようにしました。',
                    '下部の手数目盛りが欠ける問題と、末尾の `151 154` のような詰まりを修正しました。終局手は近すぎる目盛りを置き換えます。',
                    'Detail Data と説明欄の感嘆符アイコンが欠ける問題を修正し、ヘッダーの碁石マークも描き直して左端の異常表示をなくしました。',
                    'リリース前に棋力評価回帰テスト、package、ローカル実起動 UI チェックを再実行しました。',
                ],
            },
            'before': {
                'heading': 'ダウンロード前に',
                'items': [
                    f'多くの Windows ユーザーは {assets["windows_opencl_portable"]} を選ぶのがおすすめです。これは **推奨 OpenCL 版、インストール不要** です。',
                    f'OpenCL が不安定な場合は {assets["windows_portable"]} を使ってください。',
                    f'**NVIDIA GPU** 搭載 PC では {assets["windows_nvidia_portable"]} を優先してください。',
                    f'推奨バンドルには KataGo `{katago_version}` と既定の重み `{model_source}` が含まれています。',
                    'インストーラ形式がよい場合は、同じ系列の `installer.exe` を選んでください。',
                ],
            },
            'download': {
                'heading': 'ダウンロード案内',
                'headers': ('お使いの環境', 'ダウンロードするファイル'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['ja'], assets),
            },
            'why': {
                'heading': 'このリリースを更新する理由',
                'items': [
                    '棋力評価がデバッグ表ではなく、読みやすい検討結果ページに近づきました。',
                    'Match Rate では、AI 一選に当たった手と好手がひと目で分かり、読みにくいトレンド図を見る必要が減りました。',
                    'よく使う分析画面の見た目と可読性を改善した更新で、前回ビルドの置き換えに向いています。',
                ],
            },
            'contact': {'heading': '連絡先', 'items': ['QQ グループ: `299419120`']},
        },
        {
            'language': '한국어',
            'intro': '이번 버전은 기력 평가 UI 를 다듬은 업데이트입니다. Assessment 와 Match Rate 를 더 읽기 쉬운 카드형 화면으로 바꾸고, 실제 UI 테스트에서 발견된 겹침, 축 라벨, 아이콘 잘림 문제를 수정했습니다.',
            'updates': {
                'heading': '주요 업데이트',
                'items': [
                    '기력 평가 대시보드를 흑/백 성과 중심으로 다시 구성했고, 기본 화면에서는 일반 사용자가 이해하기 어려운 합계 기력을 숨겼습니다.',
                    'Match Rate 는 원래 방식에 가까운 가로 막대형 명중 지도로 바뀌었습니다. 위쪽 줄은 AI 1순위, 아래쪽 줄은 좋은 수를 표시하며, 마우스를 올리면 수순과 손실 정보를 볼 수 있습니다.',
                    '흑/백 통계 문구가 차트에 가려지는 문제를 수정해 오른쪽 확률과 설명이 그래프 위 레이어에 표시되도록 했습니다.',
                    '아래쪽 수순 눈금이 잘리는 문제와 `151 154` 처럼 끝부분이 빽빽하게 겹치는 문제를 수정했습니다. 마지막 수는 가까운 눈금을 자동으로 대체합니다.',
                    'Detail Data 와 안내 줄의 느낌표 아이콘 잘림을 수정했고, 헤더의 바둑돌 표시도 다시 그려 왼쪽 가장자리 이상 표시를 없앴습니다.',
                    '릴리스 전에 기력 평가 회귀 테스트, package, 실제 로컬 UI 실행 확인을 다시 수행했습니다.',
                ],
            },
            'before': {
                'heading': '다운로드 전 확인',
                'items': [
                    f'대부분의 Windows 사용자는 {assets["windows_opencl_portable"]} 를 먼저 받으면 됩니다. 이는 **추천 OpenCL 무설치 빌드** 입니다.',
                    f'OpenCL 이 PC에서 불안정하면 {assets["windows_portable"]} 를 대신 사용하세요.',
                    f'**NVIDIA GPU** 가 있다면 {assets["windows_nvidia_portable"]} 를 우선 사용해 보세요.',
                    f'추천 번들에는 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 가 포함되어 있습니다.',
                    '설치형 흐름을 원한다면 같은 계열의 `installer.exe` 를 고르세요.',
                ],
            },
            'download': {
                'heading': '다운로드 안내',
                'headers': ('내 컴퓨터', '다운로드할 파일'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['ko'], assets),
            },
            'why': {
                'heading': '이번 릴리스를 업데이트할 이유',
                'items': [
                    '기력 평가가 디버그 표가 아니라 읽기 쉬운 복기 결과 페이지에 더 가까워졌습니다.',
                    'Match Rate 페이지에서 AI 1순위와 좋은 수를 한눈에 볼 수 있어 읽기 어려운 추세 그래프를 볼 필요가 줄었습니다.',
                    '자주 보는 분석 화면의 시각과 가독성을 개선한 버전으로, 이전 빌드를 대체해 테스트하기 좋습니다.',
                ],
            },
            'contact': {'heading': '연락처', 'items': ['QQ 그룹: `299419120`']},
        },
        {
            'language': 'ภาษาไทย',
            'intro': 'นี่คืออัปเดตปรับ UI Player Strength Estimate โดยเปลี่ยนหน้า Assessment และ Match Rate เป็นแบบการ์ดที่อ่านง่ายขึ้น พร้อมแก้ปัญหาข้อความซ้อน label แกนล่าง และไอคอนถูกตัดจากการทดสอบ UI จริง',
            'updates': {
                'heading': 'ไฮไลต์ของเวอร์ชันนี้',
                'items': [
                    'ออกแบบหน้า Player Strength Estimate ใหม่โดยเน้นผลงานของ Black และ White เท่านั้น และซ่อนค่ารวมที่ผู้ใช้ทั่วไปเข้าใจยากจากหน้าหลัก',
                    'Match Rate เปลี่ยนเป็น hit map แบบแถบแนวนอนคล้ายของเดิม: แถวบนแสดง AI first choice แถวล่างแสดง good move และเมื่อชี้เมาส์จะเห็น move number กับ loss details',
                    'แก้ปัญหาข้อความสถิติของ Black/White ถูกกราฟบัง โดยให้เปอร์เซ็นต์และคำอธิบายด้านขวาวาดอยู่บน layer ด้านบน',
                    'แก้ label เลข move ด้านล่างที่แสดงไม่ครบ และแก้กรณีท้ายกระดานแน่นเกินไปเช่น `151 154` โดยให้ move สุดท้ายแทน tick ที่อยู่ใกล้เกินไป',
                    'แก้ไอคอนเครื่องหมายตกใจใน Detail Data และ note strip ที่ถูกตัด พร้อมวาด Go-stone mark ด้านหัวใหม่เพื่อลด artifact ด้านซ้าย',
                    'ก่อน release ได้รัน player-strength regression tests, package build และเปิด UI จริงบนเครื่อง local เพื่อตรวจซ้ำแล้ว',
                ],
            },
            'before': {
                'heading': 'ก่อนดาวน์โหลด ดูตรงนี้ก่อน',
                'items': [
                    f'ผู้ใช้ Windows ส่วนใหญ่แนะนำให้ดาวน์โหลด {assets["windows_opencl_portable"]} ซึ่งเป็น **OpenCL รุ่นแนะนำ แบบไม่ต้องติดตั้ง**',
                    f'ถ้า OpenCL ทำงานไม่ดีบนเครื่องของคุณ ให้เปลี่ยนไปใช้ {assets["windows_portable"]}',
                    f'ถ้าเครื่องของคุณมี **การ์ดจอ NVIDIA** แนะนำให้ใช้ {assets["windows_nvidia_portable"]}',
                    f'แพ็กเกจหลักมี KataGo `{katago_version}` และ weight เริ่มต้น `{model_source}` มาให้แล้ว',
                    'ถ้าชอบขั้นตอนแบบติดตั้ง ให้เลือกไฟล์ `installer.exe` ในชุดเดียวกัน',
                ],
            },
            'download': {
                'heading': 'แนะนำการดาวน์โหลด',
                'headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
                'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS['th'], assets),
            },
            'why': {
                'heading': 'ทำไมเวอร์ชันนี้ควรอัปเดต',
                'items': [
                    'Player Strength Estimate ดูเหมือนหน้าสรุปผลรีวิวที่อ่านง่ายขึ้น ไม่ใช่ตาราง debug',
                    'หน้า Match Rate ทำให้เห็นได้ทันทีว่า move ไหนตรง AI first choice และ move ไหนเป็น good move โดยไม่ต้องดูกราฟ trend ที่อ่านยาก',
                    'เป็นอัปเดตด้านภาพและการอ่านสำหรับหน้าวิเคราะห์ที่ใช้บ่อย เหมาะสำหรับแทนที่ build ก่อนหน้า',
                ],
            },
            'contact': {'heading': 'ติดต่อ', 'items': ['QQ group: `299419120`']},
        },
    ]
    add_nvidia50_download_rows(sections, assets_cn, assets)
    validate_release_sections(sections)
    heading = f'# LizzieYzy Next {release_tag} 更新' if release_tag else '# LizzieYzy Next 更新'
    return heading + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_06_10_1_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {key: format_asset(asset_map[key], repo, release_tag) for key in asset_map}
    assets = {key: format_asset_en(asset_map[key], repo, release_tag) for key in asset_map}
    katago_version = bundle['katago_version']
    model_source = bundle['model_source']
    blocks = [
        {
            'language': '中文',
            'labels': 'zh',
            'assets': assets_cn,
            'intro': '这是棋力评估模型升级版。重点合并 @huhanyu 贡献的 PR #39：新增 GP core4 默认模型、XGBoost top16 对照模型和可复现校准数据，让“棋力评估”不只好看，也更有数据支撑。',
            'updates_heading': '本版主要更新',
            'updates': [
                '合并 PR #39，接入 GP/XGBoost 棋力模型和校准数据集，感谢 @huhanyu 的高质量贡献。',
                '默认棋力模型升级为 GP core4，用更多真实样本校准高段和职业区间的估计。',
                '加入 XGBoost top16 作为可选对照模型，同时保留 Huber linear 作为回退/调试模型。',
                '棋力评估界面增加模型选择提示，分段统计和命中图会严格跟随当前选择的模型。',
                '补充模型加载、校准器、XGBoost、棋力评估和 UI 回归测试，发布前完成全量测试与打包验证。',
            ],
            'before_heading': '下载前先看这几句',
            'before': [
                f'Windows 普通用户优先下载 {assets_cn["windows_opencl_portable"]}，这是 **OpenCL 版（推荐，免安装）**。',
                f'如果 OpenCL 在你的电脑上不稳定，再改用 {assets_cn["windows_portable"]}。',
                f'如果你的电脑是 **英伟达显卡**，优先下载 {assets_cn["windows_nvidia_portable"]}。',
                f'主推荐整合包已内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
                '如果你更喜欢安装流程，再选同系列的 `installer.exe`。',
            ],
            'download_heading': '下载建议',
            'download_headers': ('你的电脑', '直接下载这个'),
            'why_heading': '这一版为什么值得更新',
            'why': [
                '棋力评估从“展示优化”继续向“模型可信度”推进，默认结果更适合长期使用和复盘对比。',
                '高级用户可以切换 GP / XGBoost / Huber 观察差异，普通用户保持默认 GP core4 即可。',
                '这版延续上一版的棋力评估 UI，同时把底层估计模型和测试覆盖一起补强。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': '繁體中文',
            'labels': 'zh_hant',
            'assets': assets_cn,
            'intro': '這是棋力評估模型升級版。重點合併 @huhanyu 貢獻的 PR #39：新增 GP core4 預設模型、XGBoost top16 對照模型和可復現校準資料，讓「棋力評估」不只好看，也更有資料支撐。',
            'updates_heading': '本版主要更新',
            'updates': [
                '合併 PR #39，接入 GP/XGBoost 棋力模型和校準資料集，感謝 @huhanyu 的高品質貢獻。',
                '預設棋力模型升級為 GP core4，用更多真實樣本校準高段和職業區間的估計。',
                '加入 XGBoost top16 作為可選對照模型，同時保留 Huber linear 作為回退/除錯模型。',
                '棋力評估介面增加模型選擇提示，分段統計和命中圖會嚴格跟隨目前選擇的模型。',
                '補充模型載入、校準器、XGBoost、棋力評估和 UI 回歸測試，發布前完成全量測試與打包驗證。',
            ],
            'before_heading': '下載前先看這幾句',
            'before': [
                f'Windows 一般使用者優先下載 {assets_cn["windows_opencl_portable"]}，這是 **OpenCL 版（推薦，免安裝）**。',
                f'如果 OpenCL 在你的電腦上不穩定，再改用 {assets_cn["windows_portable"]}。',
                f'如果你的電腦是 **NVIDIA 顯示卡**，優先下載 {assets_cn["windows_nvidia_portable"]}。',
                f'主推薦整合包已內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
                '如果你更喜歡安裝流程，再選同系列的 `installer.exe`。',
            ],
            'download_heading': '下載建議',
            'download_headers': ('你的電腦', '直接下載這個'),
            'why_heading': '這一版為什麼值得更新',
            'why': [
                '棋力評估從「展示優化」繼續向「模型可信度」推進，預設結果更適合長期使用和復盤對比。',
                '進階使用者可以切換 GP / XGBoost / Huber 觀察差異，一般使用者保持預設 GP core4 即可。',
                '這版延續上一版的棋力評估 UI，同時把底層估計模型和測試覆蓋一起補強。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': 'English',
            'labels': 'en',
            'assets': assets,
            'intro': 'This release upgrades the Player Strength Estimate model. It merges PR #39 from @huhanyu, adding the GP core4 default model, an XGBoost top16 comparison model, and reproducible calibration data so the polished UI is backed by stronger data.',
            'updates_heading': 'Release Highlights',
            'updates': [
                'Merged PR #39 with GP/XGBoost player-strength models and calibration datasets. Thanks to @huhanyu for the high-quality contribution.',
                'The default strength model is now GP core4, calibrated with more real samples for high-dan and professional ranges.',
                'Added XGBoost top16 as an optional comparison model while keeping Huber linear as a fallback/debug model.',
                'The Player Strength Estimate UI now localizes the model selector hint, and segment summaries / hit maps follow the selected model consistently.',
                'Added model-loading, calibrator, XGBoost, player-strength, and UI regression coverage; full tests and packaging were rerun before release.',
            ],
            'before_heading': 'Read Before Downloading',
            'before': [
                f'Most Windows users should download {assets["windows_opencl_portable"]}, the **recommended no-install OpenCL build**.',
                f'If OpenCL is unreliable on your PC, use {assets["windows_portable"]} instead.',
                f'If your PC has an **NVIDIA GPU**, try {assets["windows_nvidia_portable"]} first.',
                f'The recommended bundles include KataGo `{katago_version}` and the default weight `{model_source}`.',
                'If you prefer an installer, choose the matching `installer.exe` package.',
            ],
            'download_heading': 'Download Guide',
            'download_headers': ('Your computer', 'Download this file'),
            'why_heading': 'Why This Release Is Worth Updating',
            'why': [
                'Player Strength Estimate moves beyond visual polish toward more trustworthy model-backed results.',
                'Advanced users can compare GP, XGBoost, and Huber; most users can simply keep the default GP core4 model.',
                'This keeps the previous readable UI while strengthening the underlying estimation model and test coverage.',
            ],
            'contact_heading': 'Contact',
            'contact': ['QQ group: `299419120`'],
        },
        {
            'language': '日本語',
            'labels': 'ja',
            'assets': assets,
            'intro': 'これは棋力評価モデルのアップグレード版です。@huhanyu さんの PR #39 をマージし、既定の GP core4 モデル、比較用の XGBoost top16 モデル、再現可能な校正データを追加しました。',
            'updates_heading': '主な更新',
            'updates': [
                'PR #39 をマージし、GP/XGBoost 棋力モデルと校正データセットを導入しました。@huhanyu さんの高品質な貢献に感謝します。',
                '既定の棋力モデルを GP core4 に変更し、より多くの実データで高段・プロ帯の推定を校正しました。',
                '比較用モデルとして XGBoost top16 を追加し、Huber linear はフォールバック/デバッグ用として残しています。',
                '棋力評価 UI のモデル選択ヒントをローカライズし、区間集計と命中図が選択中のモデルに一貫して従うようにしました。',
                'モデル読み込み、校正器、XGBoost、棋力評価、UI 回帰テストを追加し、リリース前に全体テストと package を再実行しました。',
            ],
            'before_heading': 'ダウンロード前に',
            'before': [
                f'多くの Windows ユーザーは {assets["windows_opencl_portable"]} を選ぶのがおすすめです。これは **推奨 OpenCL 版、インストール不要** です。',
                f'OpenCL が不安定な場合は {assets["windows_portable"]} を使ってください。',
                f'**NVIDIA GPU** 搭載 PC では {assets["windows_nvidia_portable"]} を優先してください。',
                f'推奨バンドルには KataGo `{katago_version}` と既定の重み `{model_source}` が含まれています。',
                'インストーラ形式がよい場合は、同じ系列の `installer.exe` を選んでください。',
            ],
            'download_heading': 'ダウンロード案内',
            'download_headers': ('お使いの環境', 'ダウンロードするファイル'),
            'why_heading': 'このリリースを更新する理由',
            'why': [
                '棋力評価は見た目の改善だけでなく、モデルに基づくより信頼しやすい結果へ進みました。',
                '上級ユーザーは GP / XGBoost / Huber を比較でき、通常は既定の GP core4 のままで使えます。',
                '前回の読みやすい UI を維持しながら、推定モデルとテスト範囲を強化しています。',
            ],
            'contact_heading': '連絡先',
            'contact': ['QQ グループ: `299419120`'],
        },
        {
            'language': '한국어',
            'labels': 'ko',
            'assets': assets,
            'intro': '이번 버전은 기력 평가 모델 업그레이드입니다. @huhanyu 님의 PR #39 를 병합해 기본 GP core4 모델, 비교용 XGBoost top16 모델, 재현 가능한 보정 데이터를 추가했습니다.',
            'updates_heading': '주요 업데이트',
            'updates': [
                'PR #39 를 병합해 GP/XGBoost 기력 모델과 보정 데이터셋을 도입했습니다. 좋은 기여를 해 준 @huhanyu 님께 감사드립니다.',
                '기본 기력 모델을 GP core4 로 업그레이드했고, 더 많은 실제 샘플로 고단/프로 구간 추정을 보정했습니다.',
                '선택 비교 모델로 XGBoost top16 을 추가했고, Huber linear 는 fallback/debug 모델로 유지했습니다.',
                '기력 평가 UI 의 모델 선택 안내를 현지화했고, 구간 통계와 명중도 화면이 선택된 모델을 일관되게 따르도록 했습니다.',
                '모델 로딩, 보정기, XGBoost, 기력 평가, UI 회귀 테스트를 보강했으며 릴리스 전 전체 테스트와 package 를 다시 수행했습니다.',
            ],
            'before_heading': '다운로드 전 확인',
            'before': [
                f'대부분의 Windows 사용자는 {assets["windows_opencl_portable"]} 를 먼저 받으면 됩니다. 이는 **추천 OpenCL 무설치 빌드** 입니다.',
                f'OpenCL 이 PC에서 불안정하면 {assets["windows_portable"]} 를 대신 사용하세요.',
                f'**NVIDIA GPU** 가 있다면 {assets["windows_nvidia_portable"]} 를 우선 사용해 보세요.',
                f'추천 번들에는 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 가 포함되어 있습니다.',
                '설치형 흐름을 원한다면 같은 계열의 `installer.exe` 를 고르세요.',
            ],
            'download_heading': '다운로드 안내',
            'download_headers': ('내 컴퓨터', '다운로드할 파일'),
            'why_heading': '이번 릴리스를 업데이트할 이유',
            'why': [
                '기력 평가는 시각 개선을 넘어 모델 기반의 더 신뢰할 수 있는 결과로 나아갔습니다.',
                '고급 사용자는 GP / XGBoost / Huber 를 비교할 수 있고, 일반 사용자는 기본 GP core4 를 그대로 쓰면 됩니다.',
                '이전 버전의 읽기 쉬운 UI 를 유지하면서 내부 추정 모델과 테스트 커버리지를 강화했습니다.',
            ],
            'contact_heading': '연락처',
            'contact': ['QQ 그룹: `299419120`'],
        },
        {
            'language': 'ภาษาไทย',
            'labels': 'th',
            'assets': assets,
            'intro': 'เวอร์ชันนี้อัปเกรดโมเดล Player Strength Estimate โดย merge PR #39 จาก @huhanyu เพิ่ม GP core4 เป็นค่าเริ่มต้น, XGBoost top16 สำหรับเปรียบเทียบ และข้อมูล calibration ที่ทำซ้ำได้',
            'updates_heading': 'ไฮไลต์ของเวอร์ชันนี้',
            'updates': [
                'Merge PR #39 เพิ่ม GP/XGBoost player-strength models และ calibration datasets ขอบคุณ @huhanyu สำหรับ contribution คุณภาพสูง',
                'โมเดลเริ่มต้นเปลี่ยนเป็น GP core4 ซึ่งปรับเทียบด้วย sample จริงมากขึ้นสำหรับช่วง high-dan และ professional',
                'เพิ่ม XGBoost top16 เป็นโมเดลเปรียบเทียบ และยังคง Huber linear ไว้เป็น fallback/debug model',
                'UI Player Strength Estimate มีคำอธิบาย model selector และ segment summary / hit map จะใช้โมเดลที่เลือกอย่างสอดคล้องกัน',
                'เพิ่ม coverage สำหรับ model loading, calibrator, XGBoost, player-strength และ UI regression พร้อมรัน full tests และ packaging ก่อน release',
            ],
            'before_heading': 'ก่อนดาวน์โหลด ดูตรงนี้ก่อน',
            'before': [
                f'ผู้ใช้ Windows ส่วนใหญ่แนะนำให้ดาวน์โหลด {assets["windows_opencl_portable"]} ซึ่งเป็น **OpenCL รุ่นแนะนำ แบบไม่ต้องติดตั้ง**',
                f'ถ้า OpenCL ทำงานไม่ดีบนเครื่องของคุณ ให้เปลี่ยนไปใช้ {assets["windows_portable"]}',
                f'ถ้าเครื่องของคุณมี **การ์ดจอ NVIDIA** แนะนำให้ใช้ {assets["windows_nvidia_portable"]}',
                f'แพ็กเกจหลักมี KataGo `{katago_version}` และ weight เริ่มต้น `{model_source}` มาให้แล้ว',
                'ถ้าชอบขั้นตอนแบบติดตั้ง ให้เลือกไฟล์ `installer.exe` ในชุดเดียวกัน',
            ],
            'download_heading': 'คำแนะนำการดาวน์โหลด',
            'download_headers': ('คอมพิวเตอร์ของคุณ', 'ไฟล์ที่ควรดาวน์โหลด'),
            'why_heading': 'ทำไมเวอร์ชันนี้น่าอัปเดต',
            'why': [
                'Player Strength Estimate ไม่ได้ดีขึ้นแค่หน้าตา แต่มีโมเดลและข้อมูล calibration รองรับมากขึ้น',
                'ผู้ใช้ขั้นสูงสามารถเปรียบเทียบ GP / XGBoost / Huber ได้ ส่วนผู้ใช้ทั่วไปใช้ GP core4 ค่าเริ่มต้นได้เลย',
                'ยังคง UI ที่อ่านง่ายจากเวอร์ชันก่อน พร้อมเสริมโมเดลและ test coverage ด้านใน',
            ],
            'contact_heading': 'ติดต่อ',
            'contact': ['กลุ่ม QQ: `299419120`'],
        },
    ]
    sections: list[dict[str, object]] = []
    for block in blocks:
        labels_key = str(block['labels'])
        localized_assets = block['assets']
        sections.append(
            {
                'language': block['language'],
                'intro': block['intro'],
                'updates': {'heading': block['updates_heading'], 'items': block['updates']},
                'before': {'heading': block['before_heading'], 'items': block['before']},
                'download': {
                    'heading': block['download_heading'],
                    'headers': block['download_headers'],
                    'rows': standard_download_rows(
                        STANDARD_DOWNLOAD_LABELS[labels_key],
                        localized_assets,
                    ),
                },
                'why': {'heading': block['why_heading'], 'items': block['why']},
                'contact': {'heading': block['contact_heading'], 'items': block['contact']},
            }
        )
    add_nvidia50_download_rows(sections, assets_cn, assets)
    add_tensorrt_split_download_row(sections, assets_cn, assets, asset_map)
    validate_release_sections(sections)
    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_06_11_1_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {key: format_asset(asset_map[key], repo, release_tag) for key in asset_map}
    assets = {key: format_asset_en(asset_map[key], repo, release_tag) for key in asset_map}
    katago_version = bundle['katago_version']
    model_source = bundle['model_source']
    content = [
        {
            'language': '中文',
            'labels': 'zh',
            'intro': '这是一次界面观感和分析时渲染性能的维护版。Apple 玻璃风格和 LizzieYzy 经典风格都做了统一打磨；引擎持续分析时的全窗口绘制、胜率图和分支预览也改成复用缓冲，减少大图像反复分配带来的卡顿和内存压力。',
            'updates_heading': '本版主要更新',
            'updates': [
                '优化 Apple 玻璃风格：顶部/底部工具条改成统一的半透明平面材质，按钮静止态更安静，主次层级更清楚。',
                '优化 LizzieYzy 经典风格：浅色工具条、白色按钮、柔和描边和页签下划线统一为现代桌面应用观感，不再是旧 Swing 灰色工具堆。',
                '问题手侧栏新增更明确的黑/白状态、棋子标识、导航箭头、严重问题手强调和卡片式空状态。',
                '分析输出频繁刷新时，主窗口绘制缓冲、胜率图三层缓冲、主棋盘/小棋盘分支图像都改为复用，明显减少每秒临时内存分配。',
                'Windows/Linux 下启动时开启 Swing 文字抗锯齿，经典界面和弹窗文字更顺滑。',
                '本次不改变 KataGo 分析逻辑、问题手判定规则或棋盘背景规则，只改视觉表现和渲染性能。',
            ],
            'before_heading': '下载前先看这几句',
            'before': [
                f'主推荐整合包继续内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
                f'Windows 普通用户优先下载 {{windows_opencl_portable}}，这是 **OpenCL 版（推荐，免安装）**。',
                f'如果 OpenCL 在你的电脑上不稳定，再改用 {{windows_portable}}。',
                f'如果你的电脑是 **英伟达显卡**，优先下载 {{windows_nvidia_portable}}。',
                '如果你之前关闭了 Apple 风格，这一版的经典风格也已经同步优化，可以直接继续使用当前配置。',
            ],
            'download_heading': '下载建议',
            'download_headers': ('你的电脑', '直接下载这个'),
            'why_heading': '这一版为什么值得更新',
            'why': [
                '复盘时界面更像一个统一的现代桌面产品，而不是多代控件拼在一起。',
                '经典风格这次也被认真打磨：常用浅色模式用户能直接看到工具条、按钮和侧栏页签的升级。',
                '长时间分析、候选手刷新和分支预览时，临时大图像分配更少，整体更稳。',
                '所有优化都围绕显示层完成，不会改变你的引擎设置、分析结果或既有复盘习惯。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': '繁體中文',
            'labels': 'zh_hant',
            'intro': '這是一次介面觀感和分析時渲染效能的維護版。Apple 玻璃風格和 LizzieYzy 經典風格都做了統一打磨；引擎持續分析時的全視窗繪製、勝率圖和分支預覽也改成重用緩衝，減少大型影像反覆分配造成的卡頓和記憶體壓力。',
            'updates_heading': '本版主要更新',
            'updates': [
                '優化 Apple 玻璃風格：頂部/底部工具列改成統一的半透明平面材質，按鈕靜止態更安靜，主次層級更清楚。',
                '優化 LizzieYzy 經典風格：淺色工具列、白色按鈕、柔和描邊和頁籤底線統一為現代桌面應用觀感，不再像舊 Swing 灰色工具堆。',
                '問題手側欄新增更明確的黑/白狀態、棋子標識、導覽箭頭、嚴重問題手強調和卡片式空狀態。',
                '分析輸出頻繁刷新時，主視窗繪製緩衝、勝率圖三層緩衝、主棋盤/小棋盤分支影像都改為重用，明顯減少每秒臨時記憶體分配。',
                'Windows/Linux 下啟動時開啟 Swing 文字抗鋸齒，經典介面和對話框文字更順滑。',
                '本次不改變 KataGo 分析邏輯、問題手判定規則或棋盤背景規則，只改視覺表現和渲染效能。',
            ],
            'before_heading': '下載前先看這幾句',
            'before': [
                f'主推薦整合包繼續內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
                f'Windows 一般使用者優先下載 {{windows_opencl_portable}}，這是 **OpenCL 版（推薦，免安裝）**。',
                f'如果 OpenCL 在你的電腦上不穩定，再改用 {{windows_portable}}。',
                f'如果你的電腦是 **NVIDIA 顯示卡**，優先下載 {{windows_nvidia_portable}}。',
                '如果你之前關閉了 Apple 風格，這一版的經典風格也已經同步優化，可以直接繼續使用目前設定。',
            ],
            'download_heading': '下載建議',
            'download_headers': ('你的電腦', '直接下載這個'),
            'why_heading': '這一版為什麼值得更新',
            'why': [
                '復盤時介面更像一個統一的現代桌面產品，而不是多代控制項拼在一起。',
                '經典風格這次也被認真打磨：常用淺色模式使用者能直接看到工具列、按鈕和側欄頁籤的升級。',
                '長時間分析、候選手刷新和分支預覽時，臨時大型影像分配更少，整體更穩。',
                '所有優化都圍繞顯示層完成，不會改變你的引擎設定、分析結果或既有復盤習慣。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': 'English',
            'labels': 'en',
            'intro': 'This maintenance release focuses on UI polish and rendering performance during live analysis. Both the Apple glass style and the LizzieYzy classic style have been refined, while the main window, winrate graph, and branch preview rendering now reuse image buffers to reduce large temporary allocations.',
            'updates_heading': 'Release Highlights',
            'updates': [
                'Refined the Apple glass style with one flatter translucent toolbar material, quieter resting buttons, and clearer visual hierarchy.',
                'Refined the LizzieYzy classic style with a modern light toolbar, white buttons, softer borders, and active tab underlines instead of legacy Swing grey.',
                'Improved the problem-move sidebar with clearer Black/White state, stone markers, navigation arrows, severe-move emphasis, and a card-style empty state.',
                'Reduced repaint-time allocations by reusing the full-window paint buffer, the three winrate graph layers, and branch preview images on the main and sub boards.',
                'Enabled Swing text antialiasing on startup for smoother Windows/Linux classic UI and dialog text.',
                'KataGo analysis logic, problem-move rules, and custom-board-background behavior are unchanged; this release only touches presentation and rendering performance.',
            ],
            'before_heading': 'Read Before Downloading',
            'before': [
                f'The recommended bundles continue to include KataGo `{katago_version}` and the default weight `{model_source}`.',
                f'Most Windows users should download {{windows_opencl_portable}}, the **recommended no-install OpenCL build**.',
                f'If OpenCL is unreliable on your PC, use {{windows_portable}} instead.',
                f'If your PC has an **NVIDIA GPU**, try {{windows_nvidia_portable}} first.',
                'If you keep Apple style disabled, this build also upgrades the classic style, so you can keep your current preference.',
            ],
            'download_heading': 'Download Guide',
            'download_headers': ('Your computer', 'Download this file'),
            'why_heading': 'Why Update',
            'why': [
                'The review screen now feels more like one coherent modern desktop product rather than several generations of controls stitched together.',
                'Classic-style users get visible upgrades too: the toolbar, buttons, and sidebar tabs are cleaner and more deliberate.',
                'Long live-analysis sessions create fewer large temporary images during candidate refreshes and branch previews.',
                'The changes stay in the display layer and do not alter your engine settings, analysis results, or review workflow.',
            ],
            'contact_heading': 'Contact',
            'contact': ['QQ group: `299419120`'],
        },
        {
            'language': '日本語',
            'labels': 'ja',
            'intro': 'このリリースは UI の仕上げと、ライブ解析中の描画性能を中心にしたメンテナンス版です。Apple ガラス風スタイルと LizzieYzy クラシックスタイルの両方を整え、メインウィンドウ、勝率グラフ、分岐プレビューでは画像バッファを再利用して大きな一時割り当てを減らしました。',
            'updates_heading': '主な更新',
            'updates': [
                'Apple ガラス風スタイルを、よりフラットな半透明ツールバー、静かな通常状態のボタン、分かりやすい階層感に調整しました。',
                'LizzieYzy クラシックスタイルを、現代的な明るいツールバー、白いボタン、柔らかい境界線、アクティブタブ下線で整理しました。',
                '問題手サイドバーに、黒/白の状態表示、石マーカー、ナビゲーション矢印、重大な問題手の強調、カード型の空状態を追加しました。',
                'メインウィンドウの描画バッファ、勝率グラフ 3 層、メイン盤/サブ盤の分岐プレビュー画像を再利用し、再描画時の割り当てを減らしました。',
                'Windows/Linux でも Swing の文字アンチエイリアスを起動時に有効化し、クラシック UI とダイアログの文字を滑らかにしました。',
                'KataGo 解析ロジック、問題手ルール、カスタム盤背景の仕様は変更していません。表示と描画性能のみの更新です。',
            ],
            'before_heading': 'ダウンロード前に',
            'before': [
                f'推奨バンドルには KataGo `{katago_version}` と既定の重み `{model_source}` が含まれています。',
                f'多くの Windows ユーザーは {{windows_opencl_portable}} を選ぶのがおすすめです。これは **推奨 OpenCL 版、インストール不要** です。',
                f'OpenCL が不安定な場合は {{windows_portable}} を使ってください。',
                f'**NVIDIA GPU** 搭載 PC では {{windows_nvidia_portable}} を優先してください。',
                'Apple スタイルを無効にしている場合でも、このビルドではクラシックスタイルも改善されています。',
            ],
            'download_heading': 'ダウンロード案内',
            'download_headers': ('お使いの環境', 'ダウンロードするファイル'),
            'why_heading': '更新する理由',
            'why': [
                '復盤画面が、複数世代の部品を寄せ集めた印象ではなく、ひとつの現代的なデスクトップ製品としてまとまりました。',
                'クラシックスタイル利用者にも、ツールバー、ボタン、サイドバータブの見た目がはっきり改善されます。',
                '長時間のライブ解析、候補手更新、分岐プレビューで、大きな一時画像の生成が少なくなります。',
                '変更は表示層に限られ、エンジン設定、解析結果、復盤の流れはそのままです。',
            ],
            'contact_heading': '連絡先',
            'contact': ['QQ グループ: `299419120`'],
        },
        {
            'language': '한국어',
            'labels': 'ko',
            'intro': '이번 릴리스는 UI 다듬기와 라이브 분석 중 렌더링 성능을 중심으로 한 유지보수 버전입니다. Apple glass 스타일과 LizzieYzy classic 스타일을 모두 정리했고, 메인 창, 승률 그래프, 분기 미리보기는 이미지 버퍼를 재사용해 큰 임시 할당을 줄였습니다.',
            'updates_heading': '주요 업데이트',
            'updates': [
                'Apple glass 스타일을 더 평평한 반투명 툴바, 조용한 기본 버튼 상태, 더 명확한 시각적 계층으로 다듬었습니다.',
                'LizzieYzy classic 스타일은 현대적인 밝은 툴바, 흰색 버튼, 부드러운 테두리, 활성 탭 밑줄로 정리했습니다.',
                '문제수 사이드바에 흑/백 상태, 돌 표시, 이동 화살표, 큰 문제수 강조, 카드형 빈 상태를 더했습니다.',
                '전체 창 페인트 버퍼, 승률 그래프 3개 레이어, 메인/서브 보드 분기 미리보기 이미지를 재사용해 재그리기 중 할당을 줄였습니다.',
                'Windows/Linux 에서도 시작 시 Swing 텍스트 안티앨리어싱을 켜서 classic UI 와 대화상자 글자가 더 부드럽게 보입니다.',
                'KataGo 분석 로직, 문제수 판정 규칙, 커스텀 보드 배경 동작은 바꾸지 않았습니다. 표시와 렌더링 성능만 개선했습니다.',
            ],
            'before_heading': '다운로드 전 확인',
            'before': [
                f'추천 번들에는 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 가 포함되어 있습니다.',
                f'대부분의 Windows 사용자는 {{windows_opencl_portable}} 를 먼저 받으면 됩니다. 이는 **추천 OpenCL 무설치 빌드** 입니다.',
                f'OpenCL 이 PC에서 불안정하면 {{windows_portable}} 를 대신 사용하세요.',
                f'**NVIDIA GPU** 가 있다면 {{windows_nvidia_portable}} 를 우선 사용해 보세요.',
                'Apple 스타일을 꺼 두고 사용하더라도 이번 빌드에서는 classic 스타일도 함께 개선되었습니다.',
            ],
            'download_heading': '다운로드 안내',
            'download_headers': ('내 컴퓨터', '다운로드할 파일'),
            'why_heading': '업데이트할 이유',
            'why': [
                '복기 화면이 여러 세대의 컨트롤을 이어 붙인 느낌보다 하나의 현대적인 데스크톱 제품처럼 보입니다.',
                'classic 스타일 사용자도 툴바, 버튼, 사이드바 탭의 개선을 바로 볼 수 있습니다.',
                '긴 라이브 분석, 후보수 갱신, 분기 미리보기 중 큰 임시 이미지 생성이 줄어듭니다.',
                '변경은 표시 레이어에 머물며 엔진 설정, 분석 결과, 복기 흐름은 그대로 유지됩니다.',
            ],
            'contact_heading': '연락',
            'contact': ['QQ 그룹: `299419120`'],
        },
        {
            'language': 'ภาษาไทย',
            'labels': 'th',
            'intro': 'รีลีสนี้เป็น maintenance build ที่เน้นปรับ UI และประสิทธิภาพการ render ระหว่าง live analysis ทั้ง Apple glass style และ LizzieYzy classic style ถูกขัดเกลาใหม่ ส่วน main window, winrate graph และ branch preview ใช้ image buffer ซ้ำเพื่อลดการสร้างภาพขนาดใหญ่ชั่วคราว',
            'updates_heading': 'อัปเดตหลัก',
            'updates': [
                'ปรับ Apple glass style ให้เป็น toolbar โปร่งแสงแบบ flat มากขึ้น ปุ่มตอน idle สงบขึ้น และลำดับชั้นของ UI ชัดขึ้น',
                'ปรับ LizzieYzy classic style เป็น toolbar สีอ่อนสมัยใหม่ ปุ่มสีขาว เส้นขอบนุ่มขึ้น และ active tab underline แทนกลิ่น Swing สีเทาเดิม',
                'ปรับ problem-move sidebar ให้เห็นสถานะดำ/ขาวชัดขึ้น มี stone marker, navigation arrow, การเน้นปัญหารุนแรง และ empty state แบบ card',
                'ลด allocation ตอน repaint โดยใช้ full-window paint buffer, winrate graph layers และ branch preview images ซ้ำบน main/sub board',
                'เปิด Swing text antialiasing ตอนเริ่มโปรแกรม เพื่อให้ตัวอักษรบน Windows/Linux classic UI และ dialog นุ่มขึ้น',
                'ไม่ได้เปลี่ยน logic วิเคราะห์ KataGo, กฎ problem-move หรือพฤติกรรม custom board background; รุ่นนี้แตะเฉพาะ presentation และ render performance',
            ],
            'before_heading': 'ก่อนดาวน์โหลด',
            'before': [
                f'แพ็กเกจหลักมี KataGo `{katago_version}` และน้ำหนักเริ่มต้น `{model_source}` มาให้แล้ว',
                f'ผู้ใช้ Windows ส่วนใหญ่แนะนำให้ดาวน์โหลด {{windows_opencl_portable}} ซึ่งเป็น **OpenCL รุ่นแนะนำ แบบไม่ต้องติดตั้ง**',
                f'ถ้า OpenCL ไม่เสถียรบนเครื่องของคุณ ให้ใช้ {{windows_portable}} แทน',
                f'ถ้ามี **NVIDIA GPU** แนะนำให้ลอง {{windows_nvidia_portable}} ก่อน',
                'ถ้าคุณปิด Apple style ไว้ รุ่นนี้ก็ปรับ classic style แล้วเช่นกัน จึงใช้ setting เดิมต่อได้เลย',
            ],
            'download_heading': 'แนะนำการดาวน์โหลด',
            'download_headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
            'why_heading': 'ทำไมควรอัปเดต',
            'why': [
                'หน้าจอรีวิวเกมดูเป็น desktop product สมัยใหม่ที่เป็นหนึ่งเดียวมากขึ้น ไม่เหมือนเอา control หลายยุคมาต่อกัน',
                'ผู้ใช้ classic style จะเห็นการอัปเกรดของ toolbar, buttons และ sidebar tabs ได้ชัดเจน',
                'live analysis นาน ๆ, candidate refresh และ branch preview จะสร้างภาพขนาดใหญ่ชั่วคราวน้อยลง',
                'การเปลี่ยนแปลงอยู่ที่ display layer ไม่เปลี่ยน engine settings, analysis results หรือ workflow การรีวิวของคุณ',
            ],
            'contact_heading': 'ติดต่อ',
            'contact': ['QQ group: `299419120`'],
        },
    ]

    sections: list[dict[str, object]] = []
    for block in content:
        localized_assets = assets_cn if block['language'] in ('中文', '繁體中文') else assets
        before_items = [
            item.format(
                windows_opencl_portable=localized_assets['windows_opencl_portable'],
                windows_portable=localized_assets['windows_portable'],
                windows_nvidia_portable=localized_assets['windows_nvidia_portable'],
            )
            for item in block['before']
        ]
        sections.append(
            {
                'language': block['language'],
                'intro': block['intro'],
                'updates': {'heading': block['updates_heading'], 'items': block['updates']},
                'before': {'heading': block['before_heading'], 'items': before_items},
                'download': {
                    'heading': block['download_heading'],
                    'headers': block['download_headers'],
                    'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS[block['labels']], localized_assets),
                },
                'why': {'heading': block['why_heading'], 'items': block['why']},
                'contact': {'heading': block['contact_heading'], 'items': block['contact']},
            }
        )
    add_nvidia50_download_rows(sections, assets_cn, assets)
    add_tensorrt_split_download_row(sections, assets_cn, assets, asset_map)
    validate_release_sections(sections)
    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_06_11_2_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {key: format_asset(asset_map[key], repo, release_tag) for key in asset_map}
    assets = {key: format_asset_en(asset_map[key], repo, release_tag) for key in asset_map}
    katago_version = bundle['katago_version']
    model_source = bundle['model_source']
    content = [
        {
            'language': '中文',
            'labels': 'zh',
            'intro': '这是一次发布前质量收口版，重点修复野狐棋谱加载后的自动胜率曲线、macOS 安装包拖拽体验，以及默认界面的胜率图观感。加载野狐棋谱后不再需要先按空格，程序会自动启动静默快速分析来补出胜率曲线。',
            'updates_heading': '本版主要更新',
            'updates': [
                '修复野狐棋谱加载后不自动生成胜率曲线的问题：主引擎还没开始 ponder 时，也会自动启动静默快速分析。',
                '默认关闭胜率图里的柱状失误条，让新用户第一眼看到的是更干净的胜率曲线；旧默认配置会一次性迁移，之后用户手动开启会被保留。',
                'macOS DMG 改成标准拖拽安装布局：打开安装包后可以把 LizzieYzy Next 拖到 Applications。',
                'macOS 签名公证上传增加重试，降低 Apple notary 临时 503 导致 Intel/Apple Silicon 包失败的概率。',
                '保留上一版 Apple 风格和 LizzieYzy 经典风格的视觉打磨，以及主窗口、胜率图、分支预览的缓冲复用性能优化。',
                '本次不改变 KataGo 分析逻辑、问题手判定规则或用户已有的手动引擎配置。',
            ],
            'before_heading': '下载前先看这几句',
            'before': [
                f'主推荐整合包继续内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
                f'Windows 普通用户优先下载 {{windows_opencl_portable}}，这是 **OpenCL 版（推荐，免安装）**。',
                f'如果 OpenCL 在你的电脑上不稳定，再改用 {{windows_portable}}。',
                f'如果你的电脑是 **英伟达显卡**，优先下载 {{windows_nvidia_portable}}。',
                '如果你主要下载野狐棋谱复盘，建议更新到这一版；加载后会自动补胜率曲线。',
            ],
            'download_heading': '下载建议',
            'download_headers': ('你的电脑', '直接下载这个'),
            'why_heading': '这一版为什么值得更新',
            'why': [
                '野狐棋谱加载后的体验更顺：打开棋谱后胜率曲线会自己生成，不需要记住再按一次空格。',
                '胜率图默认更清爽，柱状失误条不再抢占第一视觉焦点。',
                'macOS 用户拿到的是更符合 macOS 习惯的 DMG 安装包，拖到 Applications 的窗口会出现。',
                '发布前重新跑了聚焦回归测试、全量 Maven 测试、DMG 布局验证和四平台 release workflow。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': '繁體中文',
            'labels': 'zh_hant',
            'intro': '這是一次發布前品質收口版，重點修復野狐棋譜載入後的自動勝率曲線、macOS 安裝包拖曳體驗，以及預設介面的勝率圖觀感。載入野狐棋譜後不再需要先按空白鍵，程式會自動啟動靜默快速分析來補出勝率曲線。',
            'updates_heading': '本版主要更新',
            'updates': [
                '修復野狐棋譜載入後不自動生成勝率曲線的問題：主引擎尚未開始 ponder 時，也會自動啟動靜默快速分析。',
                '預設關閉勝率圖裡的柱狀失誤條，讓新使用者第一眼看到更乾淨的勝率曲線；舊預設設定會一次性遷移，之後手動開啟會被保留。',
                'macOS DMG 改成標準拖曳安裝版面：打開安裝包後可以把 LizzieYzy Next 拖到 Applications。',
                'macOS 簽名公證上傳增加重試，降低 Apple notary 暫時 503 造成 Intel/Apple Silicon 包失敗的機率。',
                '保留上一版 Apple 風格和 LizzieYzy 經典風格的視覺打磨，以及主視窗、勝率圖、分支預覽的緩衝重用效能優化。',
                '本次不改變 KataGo 分析邏輯、問題手判定規則或使用者既有的手動引擎設定。',
            ],
            'before_heading': '下載前先看這幾句',
            'before': [
                f'主推薦整合包繼續內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
                f'Windows 一般使用者優先下載 {{windows_opencl_portable}}，這是 **OpenCL 版（推薦，免安裝）**。',
                f'如果 OpenCL 在你的電腦上不穩定，再改用 {{windows_portable}}。',
                f'如果你的電腦是 **NVIDIA 顯示卡**，優先下載 {{windows_nvidia_portable}}。',
                '如果你主要下載野狐棋譜復盤，建議更新到這一版；載入後會自動補勝率曲線。',
            ],
            'download_heading': '下載建議',
            'download_headers': ('你的電腦', '直接下載這個'),
            'why_heading': '這一版為什麼值得更新',
            'why': [
                '野狐棋譜載入後的體驗更順：打開棋譜後勝率曲線會自己生成，不需要記住再按一次空白鍵。',
                '勝率圖預設更清爽，柱狀失誤條不再搶佔第一視覺焦點。',
                'macOS 使用者拿到的是更符合 macOS 習慣的 DMG 安裝包，拖到 Applications 的視窗會出現。',
                '發布前重新跑了聚焦回歸測試、完整 Maven 測試、DMG 版面驗證和四平台 release workflow。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': 'English',
            'labels': 'en',
            'intro': 'This release tightens the final user-facing details before publishing: Fox kifu loads now generate the winrate graph automatically, the macOS package uses the standard drag-to-Applications DMG layout, and the default winrate graph is cleaner. After loading a Fox record, you no longer need to press Space first; LizzieYzy starts a silent quick analysis pass to fill the curve.',
            'updates_heading': 'Release Highlights',
            'updates': [
                'Fixed Fox kifu loading so the winrate graph is generated automatically even before the primary engine starts pondering.',
                'Turned the blunder bar off by default for a cleaner first winrate graph; old default-derived configs migrate once, and explicit later user changes are preserved.',
                'Changed macOS DMGs to the standard drag-install layout with the app and an Applications target.',
                'Added retry handling around macOS notarization uploads to reduce failures from temporary Apple notary 503 responses.',
                'Kept the previous Apple style, LizzieYzy classic style, and rendering-buffer performance polish from the UI/performance release.',
                'KataGo analysis logic, problem-move rules, and manually configured engines are unchanged.',
            ],
            'before_heading': 'Read Before Downloading',
            'before': [
                f'The recommended bundles continue to include KataGo `{katago_version}` and the default weight `{model_source}`.',
                f'Most Windows users should download {{windows_opencl_portable}}, the **recommended no-install OpenCL build**.',
                f'If OpenCL is unreliable on your PC, use {{windows_portable}} instead.',
                f'If your PC has an **NVIDIA GPU**, try {{windows_nvidia_portable}} first.',
                'If your review flow often starts from downloaded Fox records, this is the build to use.',
            ],
            'download_heading': 'Download Guide',
            'download_headers': ('Your computer', 'Download this file'),
            'why_heading': 'Why Update',
            'why': [
                'Fox records now feel direct: load the game and the winrate curve starts filling without a Space-key workaround.',
                'The default graph is calmer, with the blunder bars no longer dominating the first view.',
                'macOS users get the expected drag-to-Applications install window.',
                'Before release, targeted regressions, the full Maven test suite, DMG layout validation, and all four platform release workflows were rerun.',
            ],
            'contact_heading': 'Contact',
            'contact': ['QQ group: `299419120`'],
        },
        {
            'language': '日本語',
            'labels': 'ja',
            'intro': 'このリリースは公開前の最終品質調整です。Fox 棋譜の読み込み後に勝率グラフが自動生成され、macOS パッケージは標準的な Applications へのドラッグ形式になり、既定の勝率グラフもよりすっきりしました。Fox 棋譜を読み込んだ後、先に Space を押す必要はありません。',
            'updates_heading': '主な更新',
            'updates': [
                'Fox 棋譜の読み込み後、メインエンジンがまだ ponder を始めていなくても、静かなクイック解析で勝率グラフを自動生成します。',
                '勝率グラフの blunder bar を既定でオフにし、最初の表示をよりきれいにしました。古い既定値は一度だけ移行し、その後の手動変更は保持されます。',
                'macOS DMG を標準のドラッグインストール形式に変更し、アプリを Applications にドラッグできます。',
                'Apple notary の一時的な 503 に備えて、macOS 公証アップロードにリトライを追加しました。',
                '前回の Apple style、LizzieYzy classic style、描画バッファ再利用の性能改善はそのまま含まれます。',
                'KataGo 解析ロジック、問題手ルール、手動設定したエンジンは変更していません。',
            ],
            'before_heading': 'ダウンロード前に',
            'before': [
                f'推奨バンドルには KataGo `{katago_version}` と既定の重み `{model_source}` が含まれています。',
                f'多くの Windows ユーザーは {{windows_opencl_portable}} を選ぶのがおすすめです。これは **推奨 OpenCL 版、インストール不要** です。',
                f'OpenCL が不安定な場合は {{windows_portable}} を使ってください。',
                f'**NVIDIA GPU** 搭載 PC では {{windows_nvidia_portable}} を優先してください。',
                'ダウンロードした Fox 棋譜から復盤を始めることが多い場合は、このビルドがおすすめです。',
            ],
            'download_heading': 'ダウンロード案内',
            'download_headers': ('お使いの環境', 'ダウンロードするファイル'),
            'why_heading': '更新する理由',
            'why': [
                'Fox 棋譜を開くと、Space キーの回避操作なしで勝率曲線が生成されます。',
                '既定のグラフ表示がより落ち着き、blunder bar が最初の視線を奪わなくなりました。',
                'macOS では期待どおり Applications へドラッグするインストール画面が出ます。',
                'リリース前に targeted regression、full Maven test、DMG layout validation、4 プラットフォームの release workflow を再実行しました。',
            ],
            'contact_heading': '連絡先',
            'contact': ['QQ グループ: `299419120`'],
        },
        {
            'language': '한국어',
            'labels': 'ko',
            'intro': '이번 릴리스는 공개 전 마지막 품질 정리 버전입니다. Fox 기보를 불러온 뒤 승률 그래프가 자동으로 생성되고, macOS 패키지는 표준 drag-to-Applications DMG 형태가 되었으며, 기본 승률 그래프도 더 깔끔해졌습니다. Fox 기보를 연 뒤 먼저 Space 를 누를 필요가 없습니다.',
            'updates_heading': '주요 업데이트',
            'updates': [
                'Fox 기보 로딩 후 메인 엔진이 아직 ponder 를 시작하지 않았더라도 silent quick analysis 로 승률 그래프를 자동 생성합니다.',
                '기본 승률 그래프에서 blunder bar 를 꺼서 첫 화면을 더 깔끔하게 만들었습니다. 예전 기본값은 한 번만 migrate 되고, 이후 사용자가 직접 켠 설정은 유지됩니다.',
                'macOS DMG 를 표준 drag-install 레이아웃으로 바꿔 앱을 Applications 로 끌어 놓을 수 있습니다.',
                'Apple notary 의 일시적인 503 응답에 대비해 macOS notarization 업로드에 retry 를 추가했습니다.',
                '이전 Apple style, LizzieYzy classic style, rendering buffer 성능 개선은 그대로 포함됩니다.',
                'KataGo 분석 로직, 문제수 규칙, 수동 엔진 설정은 변경하지 않았습니다.',
            ],
            'before_heading': '다운로드 전 확인',
            'before': [
                f'추천 번들에는 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 가 포함되어 있습니다.',
                f'대부분의 Windows 사용자는 {{windows_opencl_portable}} 를 먼저 받으면 됩니다. 이는 **추천 OpenCL 무설치 빌드** 입니다.',
                f'OpenCL 이 PC에서 불안정하면 {{windows_portable}} 를 대신 사용하세요.',
                f'**NVIDIA GPU** 가 있다면 {{windows_nvidia_portable}} 를 우선 사용해 보세요.',
                '다운로드한 Fox 기보로 복기를 자주 시작한다면 이 빌드를 권장합니다.',
            ],
            'download_heading': '다운로드 안내',
            'download_headers': ('내 컴퓨터', '다운로드할 파일'),
            'why_heading': '업데이트할 이유',
            'why': [
                'Fox 기보를 열면 Space 키 우회 없이 승률 곡선이 자동으로 채워집니다.',
                '기본 그래프가 더 차분해지고 blunder bar 가 첫 시선을 차지하지 않습니다.',
                'macOS 에서는 기대한 대로 Applications 로 드래그하는 설치 창이 표시됩니다.',
                '릴리스 전에 targeted regression, full Maven test, DMG layout validation, 4개 플랫폼 release workflow 를 다시 실행했습니다.',
            ],
            'contact_heading': '연락',
            'contact': ['QQ 그룹: `299419120`'],
        },
        {
            'language': 'ภาษาไทย',
            'labels': 'th',
            'intro': 'รีลีสนี้เป็นการเก็บคุณภาพรอบสุดท้ายก่อนเผยแพร่: เมื่อโหลด Fox kifu แล้ว winrate graph จะถูกสร้างอัตโนมัติ, แพ็กเกจ macOS ใช้ DMG แบบลากไป Applications และกราฟเริ่มต้นดูสะอาดขึ้น ไม่ต้องกด Space ก่อนหลังโหลด Fox record',
            'updates_heading': 'อัปเดตหลัก',
            'updates': [
                'แก้ Fox kifu load ให้สร้าง winrate graph อัตโนมัติ แม้ primary engine ยังไม่ได้เริ่ม ponder',
                'ปิด blunder bar เป็นค่าเริ่มต้นเพื่อให้กราฟแรกดูสะอาดขึ้น; config เก่าที่เป็นค่า default จะ migrate ครั้งเดียว และถ้าผู้ใช้เปิดเองภายหลังจะคงไว้',
                'เปลี่ยน macOS DMG เป็น layout ติดตั้งมาตรฐานที่ลากแอปไป Applications ได้',
                'เพิ่ม retry ให้ macOS notarization upload เพื่อลดความล้มเหลวจาก Apple notary 503 ชั่วคราว',
                'ยังรวม polish ของ Apple style, LizzieYzy classic style และ rendering-buffer performance จากรุ่นก่อน',
                'ไม่ได้เปลี่ยน logic วิเคราะห์ KataGo, กฎ problem-move หรือ engine ที่ผู้ใช้ตั้งเอง',
            ],
            'before_heading': 'ก่อนดาวน์โหลด',
            'before': [
                f'แพ็กเกจหลักมี KataGo `{katago_version}` และน้ำหนักเริ่มต้น `{model_source}` มาให้แล้ว',
                f'ผู้ใช้ Windows ส่วนใหญ่แนะนำให้ดาวน์โหลด {{windows_opencl_portable}} ซึ่งเป็น **OpenCL รุ่นแนะนำ แบบไม่ต้องติดตั้ง**',
                f'ถ้า OpenCL ไม่เสถียรบนเครื่องของคุณ ให้ใช้ {{windows_portable}} แทน',
                f'ถ้ามี **NVIDIA GPU** แนะนำให้ลอง {{windows_nvidia_portable}} ก่อน',
                'ถ้าคุณมักเริ่มรีวิวจาก Fox records ที่ดาวน์โหลดมา รุ่นนี้เหมาะที่สุด',
            ],
            'download_heading': 'แนะนำการดาวน์โหลด',
            'download_headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
            'why_heading': 'ทำไมควรอัปเดต',
            'why': [
                'เปิด Fox kifu แล้ว winrate curve จะเริ่มเติมเอง ไม่ต้องกด Space เพื่อแก้ทาง',
                'กราฟเริ่มต้นนิ่งและสะอาดขึ้น เพราะ blunder bar ไม่แย่งสายตา',
                'ผู้ใช้ macOS จะเห็นหน้าต่างติดตั้งแบบลากไป Applications ตามที่คุ้นเคย',
                'ก่อน release ได้รัน targeted regressions, full Maven tests, DMG layout validation และ release workflow ครบ 4 แพลตฟอร์มแล้ว',
            ],
            'contact_heading': 'ติดต่อ',
            'contact': ['QQ group: `299419120`'],
        },
    ]

    sections: list[dict[str, object]] = []
    for block in content:
        localized_assets = assets_cn if block['language'] in ('中文', '繁體中文') else assets
        before_items = [
            item.format(
                windows_opencl_portable=localized_assets['windows_opencl_portable'],
                windows_portable=localized_assets['windows_portable'],
                windows_nvidia_portable=localized_assets['windows_nvidia_portable'],
            )
            for item in block['before']
        ]
        sections.append(
            {
                'language': block['language'],
                'intro': block['intro'],
                'updates': {'heading': block['updates_heading'], 'items': block['updates']},
                'before': {'heading': block['before_heading'], 'items': before_items},
                'download': {
                    'heading': block['download_heading'],
                    'headers': block['download_headers'],
                    'rows': standard_download_rows(STANDARD_DOWNLOAD_LABELS[block['labels']], localized_assets),
                },
                'why': {'heading': block['why_heading'], 'items': block['why']},
                'contact': {'heading': block['contact_heading'], 'items': block['contact']},
            }
        )
    add_nvidia50_download_rows(sections, assets_cn, assets)
    add_tensorrt_split_download_row(sections, assets_cn, assets, asset_map)
    validate_release_sections(sections)
    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_06_12_1_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {key: format_asset(asset_map[key], repo, release_tag) for key in asset_map}
    assets = {key: format_asset_en(asset_map[key], repo, release_tag) for key in asset_map}
    katago_version = bundle['katago_version']
    model_source = bundle['model_source']
    content = [
        {
            'language': '中文',
            'labels': 'zh',
            'intro': '这是一次 PR 质量收口版。感谢 @semanym 提交 #41 和 #42：一个修复野狐让子棋开局连续黑棋被丢失的问题，一个修复 Windows OpenCL 首次自动调优耗时较长时被误判为引擎异常关闭的问题。',
            'updates_heading': '本版主要更新',
            'updates': [
                '合并 #41：野狐把让子棋编码成开局连续 `B[]`，现在会在归一化前识别并还原为正确的 `HA[n]` / `AB[...]` 根节点 setup，棋盘和引擎都能看到让子。',
                '合并 #42：Windows 内置 OpenCL 引擎第一次启动需要 autotuning 且可能超过 90 秒，现在缺少 tuning 缓存时会给首次调优更长启动预算。',
                '当引擎已经进入 tuning 状态时，看门狗会继续顺延 deadline，避免调优进行中被强杀。',
                '补充 OpenCL 首次调优判断回归测试，覆盖无缓存需要长预算、已有 `.txt` tuning 缓存恢复正常预算、NVIDIA 包不误用 OpenCL 预算。',
                '保留上一版野狐棋谱自动胜率曲线、默认隐藏柱状失误条、macOS 拖拽安装 DMG 和 TensorRT 分卷下载说明。',
            ],
            'before_heading': '下载前先看这几句',
            'before': [
                f'主推荐整合包继续内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
                f'Windows 普通用户优先下载 {{windows_opencl_portable}}，这是 **OpenCL 版（推荐，免安装）**。',
                f'如果你是第一次启动 OpenCL 版，首次调优现在会有更充足的等待时间；后续已有缓存后仍会走正常快速启动判断。',
                f'如果你的电脑是 **英伟达显卡**，优先下载 {{windows_nvidia_portable}}；RTX 50 用户可选 RTX 50 CUDA 行。',
                '如果你经常下载野狐让子棋复盘，建议更新到这一版，避免开局让子丢失。',
            ],
            'download_heading': '下载建议',
            'download_headers': ('你的电脑', '直接下载这个'),
            'why_heading': '这一版为什么值得更新',
            'why': [
                '野狐让子棋不再被归一化逻辑误伤，开局让子会正确进入棋盘和引擎。',
                'Windows OpenCL 首次启动更耐心，不会因为一次性 autotuning 比较慢就弹出“引擎异常关闭”。',
                '感谢 @semanym 对真实用户场景的定位和 PR 贡献；这次发布也保留了对应回归测试。',
                '发布前重新跑了 PR 相关测试、完整 Maven 测试和本地 package。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': '繁體中文',
            'labels': 'zh_hant',
            'intro': '這是一次 PR 品質收口版。感謝 @semanym 提交 #41 和 #42：一個修復野狐讓子棋開局連續黑棋被丟失的問題，一個修復 Windows OpenCL 首次自動調優耗時較長時被誤判為引擎異常關閉的問題。',
            'updates_heading': '本版主要更新',
            'updates': [
                '合併 #41：野狐把讓子棋編碼成開局連續 `B[]`，現在會在正規化前識別並還原為正確的 `HA[n]` / `AB[...]` 根節點 setup，棋盤和引擎都能看到讓子。',
                '合併 #42：Windows 內建 OpenCL 引擎第一次啟動需要 autotuning 且可能超過 90 秒，現在缺少 tuning 快取時會給首次調優更長啟動預算。',
                '當引擎已經進入 tuning 狀態時，看門狗會繼續延長 deadline，避免調優進行中被強制關閉。',
                '補充 OpenCL 首次調優判斷回歸測試，覆蓋無快取需要長預算、已有 `.txt` tuning 快取恢復正常預算、NVIDIA 包不誤用 OpenCL 預算。',
                '保留上一版野狐棋譜自動勝率曲線、預設隱藏柱狀失誤條、macOS 拖曳安裝 DMG 和 TensorRT 分卷下載說明。',
            ],
            'before_heading': '下載前先看這幾句',
            'before': [
                f'主推薦整合包繼續內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
                f'Windows 一般使用者優先下載 {{windows_opencl_portable}}，這是 **OpenCL 版（推薦，免安裝）**。',
                f'如果你是第一次啟動 OpenCL 版，首次調優現在會有更充足的等待時間；之後已有快取後仍會走正常快速啟動判斷。',
                f'如果你的電腦是 **NVIDIA 顯示卡**，優先下載 {{windows_nvidia_portable}}；RTX 50 使用者可選 RTX 50 CUDA 行。',
                '如果你經常下載野狐讓子棋復盤，建議更新到這一版，避免開局讓子丟失。',
            ],
            'download_heading': '下載建議',
            'download_headers': ('你的電腦', '直接下載這個'),
            'why_heading': '這一版為什麼值得更新',
            'why': [
                '野狐讓子棋不再被正規化邏輯誤傷，開局讓子會正確進入棋盤和引擎。',
                'Windows OpenCL 首次啟動更有耐心，不會因為一次性 autotuning 比較慢就彈出「引擎異常關閉」。',
                '感謝 @semanym 對真實使用者場景的定位和 PR 貢獻；這次發布也保留了對應回歸測試。',
                '發布前重新跑了 PR 相關測試、完整 Maven 測試和本地 package。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': 'English',
            'labels': 'en',
            'intro': 'This PR polish release ships two fixes from @semanym. PR #41 fixes Fox handicap games where leading consecutive black moves were dropped, and PR #42 fixes first-run Windows OpenCL autotuning being mistaken for an engine startup failure.',
            'updates_heading': 'Release Highlights',
            'updates': [
                'Merged #41: Fox handicap records encoded as leading consecutive `B[]` moves are now promoted to proper `HA[n]` / `AB[...]` root setup before strict move normalization, so both the board and engine keep the handicap stones.',
                'Merged #42: the bundled Windows OpenCL engine now gets a longer first-start budget when the OpenCL tuning cache is missing.',
                'The startup watchdog keeps extending its deadline while tuning is actually in progress, avoiding a forced kill during autotuning.',
                'Added regression tests for the first OpenCL tuning detector: missing cache uses the longer budget, existing `.txt` tuning cache restores the normal budget, and NVIDIA bundles do not take the OpenCL path.',
                'The previous Fox auto-winrate-curve fix, default-hidden blunder bar, drag-to-Applications macOS DMG, and TensorRT split download guidance remain included.',
            ],
            'before_heading': 'Read Before Downloading',
            'before': [
                f'The recommended bundles continue to include KataGo `{katago_version}` and the default weight `{model_source}`.',
                f'Most Windows users should download {{windows_opencl_portable}}, the **recommended no-install OpenCL build**.',
                f'If this is your first OpenCL launch, the initial tuning pass now has a longer wait budget; later launches with cache still use the normal fast startup watchdog.',
                f'If your PC has an **NVIDIA GPU**, try {{windows_nvidia_portable}} first; RTX 50 users can use the RTX 50 CUDA row.',
                'If you review downloaded Fox handicap records, this build avoids missing opening handicap stones.',
            ],
            'download_heading': 'Download Guide',
            'download_headers': ('Your computer', 'Download this file'),
            'why_heading': 'Why Update',
            'why': [
                'Fox handicap records are no longer damaged by strict alternation normalization; the handicap stones reach both the board and engine.',
                'Windows OpenCL first launch is more patient and should not show an engine-crash dialog just because one-time autotuning is slow.',
                'Thanks to @semanym for the real-world diagnosis and PRs; this release keeps regression coverage around those cases.',
                'Before release, the PR-focused tests, full Maven suite, and local package build were rerun.',
            ],
            'contact_heading': 'Contact',
            'contact': ['QQ group: `299419120`'],
        },
        {
            'language': '日本語',
            'labels': 'ja',
            'intro': 'このリリースは PR 品質調整版です。@semanym による #41 と #42 を取り込みました。#41 は Fox の置き碁で先頭の連続黒石が失われる問題を修正し、#42 は Windows OpenCL 初回自動調整がエンジン起動失敗と誤判定される問題を修正します。',
            'updates_heading': '主な更新',
            'updates': [
                '#41 をマージ: Fox の置き碁で先頭の連続 `B[]` を、厳密な交互手順の正規化前に `HA[n]` / `AB[...]` のルート setup として復元します。',
                '#42 をマージ: Windows 内蔵 OpenCL エンジンで tuning cache がない初回起動時に、より長い起動予算を与えます。',
                'エンジンが tuning 中である場合、startup watchdog は deadline を延長し、autotuning 中の強制終了を避けます。',
                'OpenCL 初回 tuning 判定の回帰テストを追加しました。cache なし、`.txt` cache あり、NVIDIA bundle の除外を確認します。',
                '前回の Fox 勝率曲線自動生成、既定 blunder bar 非表示、macOS drag-to-Applications DMG、TensorRT 分割ダウンロード案内も引き続き含みます。',
            ],
            'before_heading': 'ダウンロード前に',
            'before': [
                f'推奨バンドルには KataGo `{katago_version}` と既定の重み `{model_source}` が含まれています。',
                f'多くの Windows ユーザーは {{windows_opencl_portable}} を選ぶのがおすすめです。これは **推奨 OpenCL 版、インストール不要** です。',
                f'OpenCL 版の初回起動では、最初の tuning により長い待機時間を使います。cache 作成後は通常の高速な起動判定に戻ります。',
                f'**NVIDIA GPU** 搭載 PC では {{windows_nvidia_portable}} を優先してください。RTX 50 ユーザーは RTX 50 CUDA 行を選べます。',
                'Fox の置き碁棋譜をよく復盤する場合、このビルドで開局の置き石欠落を避けられます。',
            ],
            'download_heading': 'ダウンロード案内',
            'download_headers': ('お使いの環境', 'ダウンロードするファイル'),
            'why_heading': '更新する理由',
            'why': [
                'Fox の置き碁が厳密な交互手順の正規化で壊れず、置き石が棋盤とエンジンに正しく渡ります。',
                'Windows OpenCL 初回起動で、一度だけの autotuning が遅いだけでエンジン異常終了ダイアログが出にくくなります。',
                '実際の利用環境に基づく診断と PR を提供した @semanym に感謝します。このケースの回帰テストも残しています。',
                'リリース前に PR focused tests、full Maven suite、local package build を再実行しました。',
            ],
            'contact_heading': '連絡先',
            'contact': ['QQ グループ: `299419120`'],
        },
        {
            'language': '한국어',
            'labels': 'ko',
            'intro': '이번 릴리스는 PR 품질 정리 버전입니다. @semanym 이 제출한 #41 과 #42 를 포함합니다. #41 은 Fox 접바둑에서 앞부분의 연속 흑돌이 사라지는 문제를 고치고, #42 는 Windows OpenCL 첫 autotuning 이 엔진 시작 실패로 오판되는 문제를 고칩니다.',
            'updates_heading': '주요 업데이트',
            'updates': [
                '#41 병합: Fox 접바둑의 선두 연속 `B[]` 를 엄격한 교대 수순 정규화 전에 `HA[n]` / `AB[...]` 루트 setup 으로 복원합니다.',
                '#42 병합: Windows 번들 OpenCL 엔진에서 tuning cache 가 없는 첫 실행 시 더 긴 시작 예산을 줍니다.',
                '엔진이 실제로 tuning 중이면 startup watchdog 이 deadline 을 계속 연장해 autotuning 중 강제 종료를 피합니다.',
                'OpenCL 첫 tuning 판단 회귀 테스트를 추가했습니다. cache 없음, `.txt` cache 있음, NVIDIA bundle 제외를 확인합니다.',
                '이전 Fox 자동 승률 곡선, 기본 blunder bar 숨김, macOS drag-to-Applications DMG, TensorRT split 다운로드 안내도 그대로 포함됩니다.',
            ],
            'before_heading': '다운로드 전 확인',
            'before': [
                f'추천 번들에는 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 가 포함되어 있습니다.',
                f'대부분의 Windows 사용자는 {{windows_opencl_portable}} 를 먼저 받으면 됩니다. 이는 **추천 OpenCL 무설치 빌드** 입니다.',
                f'OpenCL 첫 실행이라면 초기 tuning 에 더 긴 대기 시간이 주어집니다. cache 가 생긴 뒤에는 정상적인 빠른 시작 감시로 돌아갑니다.',
                f'**NVIDIA GPU** 가 있다면 {{windows_nvidia_portable}} 를 우선 사용해 보세요. RTX 50 사용자는 RTX 50 CUDA 항목을 선택할 수 있습니다.',
                '다운로드한 Fox 접바둑 기보를 자주 복기한다면 이 빌드가 초반 접바둑 돌 누락을 피합니다.',
            ],
            'download_heading': '다운로드 안내',
            'download_headers': ('내 컴퓨터', '다운로드할 파일'),
            'why_heading': '업데이트할 이유',
            'why': [
                'Fox 접바둑이 엄격한 교대 수순 정규화로 손상되지 않고, 접바둑 돌이 보드와 엔진에 올바르게 전달됩니다.',
                'Windows OpenCL 첫 실행에서 일회성 autotuning 이 느리다는 이유만으로 엔진 크래시 대화상자가 뜰 가능성이 줄었습니다.',
                '실제 사용자 환경을 진단하고 PR 을 제출한 @semanym 에게 감사합니다. 이번 릴리스는 해당 회귀 테스트도 포함합니다.',
                '릴리스 전에 PR focused tests, full Maven suite, local package build 를 다시 실행했습니다.',
            ],
            'contact_heading': '연락',
            'contact': ['QQ 그룹: `299419120`'],
        },
        {
            'language': 'ภาษาไทย',
            'labels': 'th',
            'intro': 'รีลีสนี้เป็น PR polish build พร้อมขอบคุณ @semanym สำหรับ #41 และ #42: #41 แก้ Fox handicap games ที่หมากดำต่อเนื่องตอนต้นหายไป และ #42 แก้ Windows OpenCL autotuning ครั้งแรกที่ถูกมองว่า engine startup fail',
            'updates_heading': 'อัปเดตหลัก',
            'updates': [
                'รวม #41: Fox handicap records ที่ขึ้นต้นด้วย `B[]` ต่อเนื่องจะถูกแปลงเป็น root setup `HA[n]` / `AB[...]` ก่อน strict move normalization เพื่อให้ board และ engine เห็น handicap stones ครบ',
                'รวม #42: bundled Windows OpenCL engine จะได้ startup budget นานขึ้นเมื่อยังไม่มี OpenCL tuning cache',
                'ถ้า engine อยู่ใน tuning จริง startup watchdog จะขยาย deadline ต่อ เพื่อไม่ kill process ระหว่าง autotuning',
                'เพิ่ม regression tests สำหรับ first OpenCL tuning detector: ไม่มี cache ใช้ budget ยาว, มี `.txt` tuning cache แล้วกลับสู่ budget ปกติ, และ NVIDIA bundles ไม่เข้า OpenCL path',
                'ยังรวม Fox auto-winrate-curve fix, default-hidden blunder bar, macOS DMG แบบลากไป Applications และ TensorRT split download guidance จากรุ่นก่อน',
            ],
            'before_heading': 'ก่อนดาวน์โหลด',
            'before': [
                f'แพ็กเกจหลักมี KataGo `{katago_version}` และน้ำหนักเริ่มต้น `{model_source}` มาให้แล้ว',
                f'ผู้ใช้ Windows ส่วนใหญ่แนะนำให้ดาวน์โหลด {{windows_opencl_portable}} ซึ่งเป็น **OpenCL รุ่นแนะนำ แบบไม่ต้องติดตั้ง**',
                f'ถ้าเปิด OpenCL ครั้งแรก initial tuning จะมีเวลารอนานขึ้น หลังมี cache แล้วจะกลับไปใช้ startup watchdog ปกติ',
                f'ถ้ามี **NVIDIA GPU** แนะนำให้ลอง {{windows_nvidia_portable}} ก่อน; ผู้ใช้ RTX 50 เลือกแถว RTX 50 CUDA ได้',
                'ถ้าคุณรีวิว Fox handicap records ที่ดาวน์โหลดมา รุ่นนี้ช่วยกันไม่ให้ handicap stones ตอนต้นหาย',
            ],
            'download_heading': 'แนะนำการดาวน์โหลด',
            'download_headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
            'why_heading': 'ทำไมควรอัปเดต',
            'why': [
                'Fox handicap records จะไม่ถูก strict alternation normalization ทำให้เสียอีกต่อไป และ handicap stones จะถูกส่งถึงทั้ง board และ engine',
                'Windows OpenCL first launch รอ autotuning ได้นานขึ้น จึงไม่ควรขึ้น dialog engine crash เพียงเพราะ one-time autotuning ช้า',
                'ขอบคุณ @semanym สำหรับการวิเคราะห์เคสจริงและ PR; release นี้มี regression coverage สำหรับเคสเหล่านี้ด้วย',
                'ก่อน release ได้รัน PR-focused tests, full Maven suite และ local package build ใหม่แล้ว',
            ],
            'contact_heading': 'ติดต่อ',
            'contact': ['QQ group: `299419120`'],
        },
    ]

    sections: list[dict[str, object]] = []
    for block in content:
        localized_assets = assets_cn if block['language'] in ('中文', '繁體中文') else assets
        before_items = [
            item.format(
                windows_opencl_portable=localized_assets['windows_opencl_portable'],
                windows_nvidia_portable=localized_assets['windows_nvidia_portable'],
            )
            for item in block['before']
        ]
        sections.append(
            {
                'language': block['language'],
                'intro': block['intro'],
                'updates': {'heading': block['updates_heading'], 'items': block['updates']},
                'before': {'heading': block['before_heading'], 'items': before_items},
                'download': {
                    'heading': block['download_heading'],
                    'headers': block['download_headers'],
                    'rows': standard_download_rows(
                        STANDARD_DOWNLOAD_LABELS[block['labels']],
                        localized_assets,
                    ),
                },
                'why': {'heading': block['why_heading'], 'items': block['why']},
                'contact': {'heading': block['contact_heading'], 'items': block['contact']},
            }
        )
    add_nvidia50_download_rows(sections, assets_cn, assets)
    add_tensorrt_split_download_row(sections, assets_cn, assets, asset_map)
    validate_release_sections(sections)
    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def build_next_2026_06_12_2_notes(
    asset_map: dict[str, str | None],
    bundle: dict[str, str],
    repo: str,
    release_tag: str | None,
) -> str:
    assets_cn = {key: format_asset(asset_map[key], repo, release_tag) for key in asset_map}
    assets = {key: format_asset_en(asset_map[key], repo, release_tag) for key in asset_map}
    katago_version = bundle['katago_version']
    model_source = bundle['model_source']
    content = [
        {
            'language': '中文',
            'labels': 'zh',
            'intro': '这是一次用户体验与诊断收口版。感谢 @qiyi71w 提交 #43，让同步诊断和导出包可以在真实问题现场更快定位；这一版也合入 Windows 轻量自动更新、直播贴目与 UI 细节修复，以及候选点变化图二次悬停修复。',
            'updates_heading': '本版主要更新',
            'updates': [
                '新增 Windows 轻量自动更新基础能力：软件内“检查更新”改用 GitHub Release manifest，核心更新可单独下载，KataGo、权重、JCEF、readboard 和运行时只有变化时才默认更新。',
                '合并 #43：新增只读同步诊断面板和脱敏诊断包导出，方便排查 ReadBoard、弈客 session、geometry readiness、同步决策和分析恢复状态。',
                '直播时手动调到 7.0 的贴目不会在每次刷新后又回到 7.5。',
                '落子评价标记从粗略三色改为更连续的严重度色阶；评论/问题手控制条新增设置项可隐藏，macOS/Swing 提示框关闭后也减少透明残影。',
                '修复候选点变化图：第一次移开后再次悬停同一候选点，不会再出现只有变化图数字、棋子图层消失的状态。',
            ],
            'before_heading': '下载前先看这几句',
            'before': [
                f'主推荐整合包继续内置 KataGo `{katago_version}` 和默认权重 `{model_source}`。',
                f'Windows 普通用户优先下载 {{windows_opencl_portable}}，这是 **OpenCL 版（推荐，免安装）**。',
                '这版已经内置自动更新入口；老用户仍需手动安装一次，从后续版本开始才能走轻量更新。',
                f'如果你的电脑是 **英伟达显卡**，优先下载 {{windows_nvidia_portable}}；RTX 50 用户可选 RTX 50 CUDA 行。',
                'macOS 包继续使用拖到 Applications 的 DMG 布局，但仍是未签名、未公证包，首次打开请按安装说明处理系统拦截。',
            ],
            'download_heading': '下载建议',
            'download_headers': ('你的电脑', '直接下载这个'),
            'why_heading': '这一版为什么值得更新',
            'why': [
                '直播复盘时贴目、候选点变化图和提示框这些高频细节更稳，少掉会打断思路的小毛刺。',
                '同步诊断包能把问题现场打包给维护者，排查弈客/ReadBoard 同步问题不用靠零散截图猜。',
                'Windows 自动更新从这一版开始打底，后续常规更新会更轻，不必每次都下载完整大包。',
                '发布前已完成本机用户视角启动/悬停复测、macOS DMG 拖拽布局校验、完整 Maven 测试、打包和 GitHub Actions 检查。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': '繁體中文',
            'labels': 'zh_hant',
            'intro': '這是一次使用者體驗與診斷收口版。感謝 @qiyi71w 提交 #43，讓同步診斷和匯出包可以在真實問題現場更快定位；這一版也合入 Windows 輕量自動更新、直播貼目與 UI 細節修復，以及候選點變化圖二次懸停修復。',
            'updates_heading': '本版主要更新',
            'updates': [
                '新增 Windows 輕量自動更新基礎能力：軟體內「檢查更新」改用 GitHub Release manifest，核心更新可單獨下載，KataGo、權重、JCEF、readboard 和執行環境只有變化時才預設更新。',
                '合併 #43：新增唯讀同步診斷面板和脫敏診斷包匯出，方便排查 ReadBoard、弈客 session、geometry readiness、同步決策和分析恢復狀態。',
                '直播時手動調到 7.0 的貼目不會在每次刷新後又回到 7.5。',
                '落子評價標記從粗略三色改為更連續的嚴重度色階；評論/問題手控制條新增設定項可隱藏，macOS/Swing 提示框關閉後也減少透明殘影。',
                '修復候選點變化圖：第一次移開後再次懸停同一候選點，不會再出現只有變化圖數字、棋子圖層消失的狀態。',
            ],
            'before_heading': '下載前先看這幾句',
            'before': [
                f'主推薦整合包繼續內建 KataGo `{katago_version}` 和預設權重 `{model_source}`。',
                f'Windows 一般使用者優先下載 {{windows_opencl_portable}}，這是 **OpenCL 版（推薦，免安裝）**。',
                '這版已經內建自動更新入口；舊使用者仍需手動安裝一次，從後續版本開始才能走輕量更新。',
                f'如果你的電腦是 **NVIDIA 顯示卡**，優先下載 {{windows_nvidia_portable}}；RTX 50 使用者可選 RTX 50 CUDA 行。',
                'macOS 包繼續使用拖到 Applications 的 DMG 佈局，但仍是未簽名、未公證包，首次打開請按安裝說明處理系統攔截。',
            ],
            'download_heading': '下載建議',
            'download_headers': ('你的電腦', '直接下載這個'),
            'why_heading': '這一版為什麼值得更新',
            'why': [
                '直播復盤時貼目、候選點變化圖和提示框這些高頻細節更穩，少掉會打斷思路的小毛刺。',
                '同步診斷包能把問題現場打包給維護者，排查弈客/ReadBoard 同步問題不用靠零散截圖猜。',
                'Windows 自動更新從這一版開始打底，後續常規更新會更輕，不必每次都下載完整大包。',
                '發布前已完成本機使用者視角啟動/懸停複測、macOS DMG 拖曳佈局校驗、完整 Maven 測試、打包和 GitHub Actions 檢查。',
            ],
            'contact_heading': '交流',
            'contact': ['QQ 群：`299419120`'],
        },
        {
            'language': 'English',
            'labels': 'en',
            'intro': 'This release closes a set of user-experience and diagnostics issues. Thanks to @qiyi71w for PR #43, which adds a read-only sync diagnostics panel and export package for real-world debugging. It also includes the Windows lightweight updater foundation, live-komi/UI fixes, and the second-hover candidate variation fix.',
            'updates_heading': 'Release Highlights',
            'updates': [
                'Added the first Windows lightweight updater path: in-app Check for Updates now reads a GitHub Release manifest, core updates can be downloaded separately, and large resources such as KataGo, weights, JCEF, readboard, and runtime are selected only when they changed.',
                'Merged #43: a read-only sync diagnostics panel and sanitized export package now help inspect ReadBoard state, Yike session state, geometry readiness, recent sync decisions, and analysis recovery.',
                'Live komi manually changed to 7.0 no longer snaps back to the remote 7.5 value after every refresh.',
                'Move-rank markers now use a smoother severity color scale; the comment/problem-move control bar can be hidden from Settings; macOS/Swing prompt disposal was tightened to reduce transparent ghost windows.',
                'Fixed candidate variation previews so hovering the same candidate again after leaving no longer shows move numbers over a blank branch-stone layer.',
            ],
            'before_heading': 'Read Before Downloading',
            'before': [
                f'The recommended bundles continue to include KataGo `{katago_version}` and the default weight `{model_source}`.',
                f'Most Windows users should download {{windows_opencl_portable}}, the **recommended no-install OpenCL build**.',
                'This build includes the updater bootstrap; existing users still need to install this version manually once, then later releases can use the lightweight update path.',
                f'If your PC has an **NVIDIA GPU**, try {{windows_nvidia_portable}} first; RTX 50 users can use the RTX 50 CUDA row.',
                'macOS packages still use the drag-to-Applications DMG layout, but they remain unsigned and not notarized, so the first launch may require the documented macOS security override.',
            ],
            'download_heading': 'Download Guide',
            'download_headers': ('Your computer', 'Download this file'),
            'why_heading': 'Why Update',
            'why': [
                'Live review details such as komi, candidate variation previews, and prompt cleanup are steadier in everyday use.',
                'The diagnostics export gives maintainers a structured, sanitized view of Yike/ReadBoard sync problems instead of scattered screenshots.',
                'Windows automatic updates now have their foundation, so future routine updates can become much smaller than full packages.',
                'Before release, local user-perspective launch and hover retests, macOS DMG drag-layout validation, the full Maven suite, package build, and GitHub Actions checks were completed.',
            ],
            'contact_heading': 'Contact',
            'contact': ['QQ group: `299419120`'],
        },
        {
            'language': '日本語',
            'labels': 'ja',
            'intro': 'このリリースは、ユーザー体験と診断まわりの仕上げ版です。実際の同期問題を調べやすくする読み取り専用診断パネルとエクスポートパッケージを追加した #43 の @qiyi71w に感謝します。Windows 軽量自動更新の土台、ライブコミ/UI 修正、候補点変化図の二度目ホバー修正も含みます。',
            'updates_heading': '主な更新',
            'updates': [
                'Windows 軽量自動更新の最初の経路を追加しました。アプリ内の更新確認は GitHub Release manifest を読み、core は単独更新でき、KataGo・重み・JCEF・readboard・runtime などの大きなリソースは変更時だけ既定選択されます。',
                '#43 をマージ: 読み取り専用の同期診断パネルと sanitized export package により、ReadBoard、Yike session、geometry readiness、最近の同期判断、分析復帰状態を確認しやすくしました。',
                'ライブ中に手動で 7.0 に変えたコミが、毎手の更新でリモートの 7.5 に戻らないようにしました。',
                '着手評価マーカーは粗い 3 色ではなく、より連続的な severity color scale を使います。コメント/問題手の control bar は設定から非表示にでき、macOS/Swing のプロンプト終了後の透明残りも減らしました。',
                '候補点変化図を修正しました。一度離れてから同じ候補点に戻っても、変化図の数字だけが残って石レイヤーが空になる状態を避けます。',
            ],
            'before_heading': 'ダウンロード前に',
            'before': [
                f'推奨バンドルには KataGo `{katago_version}` と既定の重み `{model_source}` が含まれています。',
                f'多くの Windows ユーザーは {{windows_opencl_portable}} を選ぶのがおすすめです。これは **推奨 OpenCL 版、インストール不要** です。',
                'このビルドには updater bootstrap が含まれます。既存ユーザーは今回だけ手動インストールが必要で、以後のリリースから軽量更新を使えます。',
                f'**NVIDIA GPU** 搭載 PC では {{windows_nvidia_portable}} を優先してください。RTX 50 ユーザーは RTX 50 CUDA 行を選べます。',
                'macOS package は引き続き drag-to-Applications DMG 形式ですが、未署名・未公証のため、初回起動では手順に沿ったセキュリティ許可が必要になる場合があります。',
            ],
            'download_heading': 'ダウンロード案内',
            'download_headers': ('お使いの環境', 'ダウンロードするファイル'),
            'why_heading': '更新する理由',
            'why': [
                'ライブ検討でよく触れるコミ、候補点変化図、プロンプト終了処理がより安定します。',
                '診断エクスポートにより、Yike/ReadBoard 同期問題を散発的なスクリーンショットではなく構造化データで共有できます。',
                'Windows 自動更新の土台が入り、今後の通常更新はフルパッケージより小さくできます。',
                'リリース前にローカルのユーザー視点起動/ホバー再テスト、macOS DMG drag layout validation、full Maven suite、package build、GitHub Actions checks を完了しました。',
            ],
            'contact_heading': '連絡先',
            'contact': ['QQ グループ: `299419120`'],
        },
        {
            'language': '한국어',
            'labels': 'ko',
            'intro': '이번 릴리스는 사용자 경험과 진단 기능을 정리한 버전입니다. 실제 동기화 문제를 더 빨리 분석할 수 있도록 읽기 전용 sync diagnostics panel 과 export package 를 추가한 #43 의 @qiyi71w 에게 감사합니다. Windows lightweight updater 기반, live komi/UI 수정, 후보점 변화도 두 번째 hover 수정도 포함합니다.',
            'updates_heading': '주요 업데이트',
            'updates': [
                'Windows lightweight updater 의 첫 경로를 추가했습니다. 앱 안의 업데이트 확인은 GitHub Release manifest 를 읽고, core 는 따로 업데이트할 수 있으며 KataGo, weights, JCEF, readboard, runtime 같은 큰 리소스는 바뀐 경우에만 기본 선택됩니다.',
                '#43 병합: 읽기 전용 sync diagnostics panel 과 sanitized export package 로 ReadBoard, Yike session, geometry readiness, 최근 sync decisions, analysis recovery 상태를 확인할 수 있습니다.',
                '라이브 중 사용자가 komi 를 7.0 으로 바꾼 뒤, 매 수 갱신마다 원격 7.5 값으로 돌아가지 않도록 했습니다.',
                '착수 평가 마커는 거친 3 색 대신 더 부드러운 severity color scale 을 사용합니다. comment/problem-move control bar 는 설정에서 숨길 수 있고, macOS/Swing prompt 종료 뒤 투명 잔상이 남는 경우도 줄였습니다.',
                '후보점 변화도를 수정했습니다. 한 번 벗어났다가 같은 후보점에 다시 hover 해도, 숫자만 남고 돌 레이어가 사라지는 상태가 나오지 않습니다.',
            ],
            'before_heading': '다운로드 전 확인',
            'before': [
                f'추천 번들에는 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 가 포함되어 있습니다.',
                f'대부분의 Windows 사용자는 {{windows_opencl_portable}} 를 먼저 받으면 됩니다. 이는 **추천 OpenCL 무설치 빌드** 입니다.',
                '이 빌드에는 updater bootstrap 이 포함됩니다. 기존 사용자는 이번 버전을 한 번 수동 설치해야 하며, 이후 릴리스부터 lightweight update path 를 사용할 수 있습니다.',
                f'**NVIDIA GPU** 가 있다면 {{windows_nvidia_portable}} 를 우선 사용해 보세요. RTX 50 사용자는 RTX 50 CUDA 항목을 선택할 수 있습니다.',
                'macOS package 는 계속 drag-to-Applications DMG layout 을 사용하지만, 아직 unsigned/not notarized 이므로 첫 실행 때 문서의 macOS 보안 허용 절차가 필요할 수 있습니다.',
            ],
            'download_heading': '다운로드 안내',
            'download_headers': ('내 컴퓨터', '다운로드할 파일'),
            'why_heading': '업데이트할 이유',
            'why': [
                '라이브 검토에서 자주 쓰는 komi, 후보점 변화도, prompt cleanup 이 일상 사용에서 더 안정적입니다.',
                '진단 export 로 Yike/ReadBoard sync 문제를 산발적인 스크린샷이 아니라 구조화되고 sanitized 된 데이터로 전달할 수 있습니다.',
                'Windows automatic update 기반이 들어가 이후 일반 업데이트는 전체 패키지보다 훨씬 작아질 수 있습니다.',
                '릴리스 전에 로컬 사용자 관점 launch/hover retest, macOS DMG drag-layout validation, full Maven suite, package build, GitHub Actions checks 를 완료했습니다.',
            ],
            'contact_heading': '연락',
            'contact': ['QQ 그룹: `299419120`'],
        },
        {
            'language': 'ภาษาไทย',
            'labels': 'th',
            'intro': 'รีลีสนี้เป็นรอบเก็บรายละเอียด user experience และ diagnostics ขอบคุณ @qiyi71w สำหรับ PR #43 ที่เพิ่ม sync diagnostics panel แบบ read-only และ export package สำหรับ debug เคสจริงได้เร็วขึ้น นอกจากนี้ยังรวม Windows lightweight updater foundation, live komi/UI fixes และ candidate variation second-hover fix',
            'updates_heading': 'อัปเดตหลัก',
            'updates': [
                'เพิ่มทางเดินแรกของ Windows lightweight updater: Check for Updates ในแอปอ่าน GitHub Release manifest, core update ดาวน์โหลดแยกได้ และ resource ใหญ่ เช่น KataGo, weights, JCEF, readboard, runtime จะถูกเลือกอัปเดตเมื่อมีการเปลี่ยนเท่านั้น',
                'รวม #43: sync diagnostics panel แบบ read-only และ sanitized export package ช่วยตรวจ ReadBoard state, Yike session, geometry readiness, recent sync decisions และ analysis recovery',
                'เมื่อ live komi ถูกปรับเองเป็น 7.0 จะไม่เด้งกลับเป็นค่า remote 7.5 หลัง refresh ทุกตา',
                'move-rank markers ใช้ severity color scale ที่นุ่มกว่าเดิม ไม่ใช่ 3 สีหยาบ ๆ; comment/problem-move control bar ซ่อนได้จาก Settings; และ macOS/Swing prompt disposal ลดปัญหา transparent ghost windows',
                'แก้ candidate variation preview: hover candidate เดิมอีกครั้งหลังย้ายเมาส์ออก จะไม่เหลือแต่เลข variation บน branch-stone layer ว่าง',
            ],
            'before_heading': 'ก่อนดาวน์โหลด',
            'before': [
                f'แพ็กเกจหลักมี KataGo `{katago_version}` และน้ำหนักเริ่มต้น `{model_source}` มาให้แล้ว',
                f'ผู้ใช้ Windows ส่วนใหญ่แนะนำให้ดาวน์โหลด {{windows_opencl_portable}} ซึ่งเป็น **OpenCL รุ่นแนะนำ แบบไม่ต้องติดตั้ง**',
                'รุ่นนี้มี updater bootstrap แล้ว ผู้ใช้เดิมยังต้องติดตั้งด้วยตัวเองหนึ่งครั้ง จากรุ่นถัดไปจึงเริ่มใช้ lightweight update path ได้',
                f'ถ้ามี **NVIDIA GPU** แนะนำให้ลอง {{windows_nvidia_portable}} ก่อน; ผู้ใช้ RTX 50 เลือกแถว RTX 50 CUDA ได้',
                'macOS package ยังเป็น DMG แบบลากไป Applications แต่ยัง unsigned/not notarized ดังนั้นครั้งแรกอาจต้องอนุญาตตามขั้นตอน macOS security ในเอกสาร',
            ],
            'download_heading': 'แนะนำการดาวน์โหลด',
            'download_headers': ('เครื่องของคุณ', 'ดาวน์โหลดไฟล์นี้'),
            'why_heading': 'ทำไมควรอัปเดต',
            'why': [
                'รายละเอียดที่ใช้บ่อยในการ live review เช่น komi, candidate variation preview และ prompt cleanup เสถียรกว่าเดิม',
                'diagnostics export ช่วยส่งข้อมูล Yike/ReadBoard sync แบบ structured และ sanitized ให้ผู้ดูแล ไม่ต้องเดาจาก screenshot กระจัดกระจาย',
                'Windows automatic updates มี foundation แล้ว ทำให้ routine updates ในอนาคตเล็กกว่า full packages ได้มาก',
                'ก่อน release ได้ตรวจ local user-perspective launch/hover retest, macOS DMG drag-layout validation, full Maven suite, package build และ GitHub Actions checks',
            ],
            'contact_heading': 'ติดต่อ',
            'contact': ['QQ group: `299419120`'],
        },
    ]

    sections: list[dict[str, object]] = []
    for block in content:
        localized_assets = assets_cn if block['language'] in ('中文', '繁體中文') else assets
        before_items = [
            item.format(
                windows_opencl_portable=localized_assets['windows_opencl_portable'],
                windows_nvidia_portable=localized_assets['windows_nvidia_portable'],
            )
            for item in block['before']
        ]
        sections.append(
            {
                'language': block['language'],
                'intro': block['intro'],
                'updates': {'heading': block['updates_heading'], 'items': block['updates']},
                'before': {'heading': block['before_heading'], 'items': before_items},
                'download': {
                    'heading': block['download_heading'],
                    'headers': block['download_headers'],
                    'rows': standard_download_rows(
                        STANDARD_DOWNLOAD_LABELS[block['labels']],
                        localized_assets,
                    ),
                },
                'why': {'heading': block['why_heading'], 'items': block['why']},
                'contact': {'heading': block['contact_heading'], 'items': block['contact']},
            }
        )
    add_nvidia50_download_rows(sections, assets_cn, assets)
    add_tensorrt_split_download_row(sections, assets_cn, assets, asset_map)
    validate_release_sections(sections)
    return release_heading(release_tag) + '\n\n' + '\n\n---\n\n'.join(
        render_language_section(section) for section in sections
    ) + '\n'


def main() -> int:
    args = parse_args()
    if args.from_gh:
        asset_names = asset_names_from_gh(args.repo, args.release_tag)
    else:
        asset_names = asset_names_from_dir(args.release_dir, args.date_tag)

    asset_map = {
        key: pick_asset(asset_names, suffix, args.date_tag)
        for key, suffix, _cn, _en in ASSET_SPECS
    }
    asset_map['windows_tensorrt_split_readme'] = pick_asset(
        asset_names,
        TENSORRT_SPLIT_README_SUFFIX,
        args.date_tag,
    )
    asset_map['windows_tensorrt_split_parts'] = pick_assets_matching(
        asset_names,
        TENSORRT_SPLIT_PART_PATTERN,
        args.date_tag,
    )
    asset_map['windows_tensorrt_split_sha256'] = pick_asset(
        asset_names,
        TENSORRT_SPLIT_SHA256_SUFFIX,
        args.date_tag,
    )
    asset_map['windows_tensorrt_split_manifest'] = pick_asset(
        asset_names,
        TENSORRT_SPLIT_MANIFEST_SUFFIX,
        args.date_tag,
    )
    bundle = load_bundle_metadata()
    notes = build_release_notes(asset_map, bundle, args.repo, args.release_tag)

    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(notes, encoding='utf-8')
    else:
        sys.stdout.write(notes)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
