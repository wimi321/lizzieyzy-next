#!/usr/bin/env python3
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
    ('windows_no_engine_installer', 'windows64.without.engine.installer.exe', 'Windows 64 位，想自己配引擎，也想安装器', 'Windows x64, your own engine with installer'),
    ('windows_no_engine_portable', 'windows64.without.engine.portable.zip', 'Windows 64 位，想自己配引擎', 'Windows x64, your own engine'),
    ('mac_arm64', 'mac-apple-silicon.with-katago.dmg', 'macOS Apple Silicon', 'macOS Apple Silicon'),
    ('mac_amd64', 'mac-intel.with-katago.dmg', 'macOS Intel', 'macOS Intel'),
    ('linux64', 'linux64.with-katago.zip', 'Linux 64 位', 'Linux x64'),
]

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
            elif key == 'model source':
                metadata['model_source'] = value

    if PREPARE_BUNDLED_KATAGO_SCRIPT.exists():
        script_text = PREPARE_BUNDLED_KATAGO_SCRIPT.read_text(encoding='utf-8')
        pattern_map = {
            'katago_version': r'KATAGO_TAG="\$\{KATAGO_TAG:-([^"]+)\}"',
            'windows_bundle': r'WINDOWS_ASSET="\$\{WINDOWS_ASSET:-([^"]+)\}"',
            'windows_opencl_bundle': r'WINDOWS_OPENCL_ASSET="\$\{WINDOWS_OPENCL_ASSET:-([^"]+)\}"',
            'windows_nvidia_bundle': r'WINDOWS_NVIDIA_ASSET="\$\{WINDOWS_NVIDIA_ASSET:-([^"]+)\}"',
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

        for key in ('windows_bundle', 'windows_opencl_bundle', 'windows_nvidia_bundle'):
            if key in script_metadata:
                metadata[key] = script_metadata[key]

    katago_version = metadata['katago_version']
    if katago_version != 'Unknown':
        metadata['windows_bundle'] = metadata['windows_bundle'].replace('${KATAGO_TAG}', katago_version)
        metadata['windows_opencl_bundle'] = metadata['windows_opencl_bundle'].replace('${KATAGO_TAG}', katago_version)
        metadata['windows_nvidia_bundle'] = metadata['windows_nvidia_bundle'].replace('${KATAGO_TAG}', katago_version)
    if katago_version != 'Unknown':
        if metadata['windows_bundle'] == 'Unknown':
            metadata['windows_bundle'] = f'katago-{katago_version}-eigen-windows-x64.zip'
        if metadata['windows_opencl_bundle'] == 'Unknown':
            metadata['windows_opencl_bundle'] = f'katago-{katago_version}-opencl-windows-x64.zip'
        if metadata['windows_nvidia_bundle'] == 'Unknown':
            metadata['windows_nvidia_bundle'] = (
                f'katago-{katago_version}-cuda12.1-cudnn8.9.7-windows-x64.zip'
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


def release_asset_url(repo: str, release_tag: str | None, asset_name: str) -> str | None:
    if not release_tag:
        return None
    return f'https://github.com/{repo}/releases/download/{quote(release_tag)}/{quote(asset_name)}'


def format_asset(asset_name: str | None, repo: str, release_tag: str | None) -> str:
    if not asset_name:
        return '暂未包含在本次发布中'
    url = release_asset_url(repo, release_tag, asset_name)
    if not url:
        return f'`{asset_name}`'
    return f'[`{asset_name}`]({url})'


def format_asset_en(asset_name: str | None, repo: str, release_tag: str | None) -> str:
    if not asset_name:
        return 'Not included in this release'
    url = release_asset_url(repo, release_tag, asset_name)
    if not url:
        return f'`{asset_name}`'
    return f'[`{asset_name}`]({url})'


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
                if not isinstance(rows, list) or len(rows) != expected_download_rows:
                    raise SystemExit(
                        f'{language} download table must contain {expected_download_rows} rows'
                    )
                continue

            items = block.get('items')
            if not isinstance(items, list) or not items:
                raise SystemExit(f'{language} release notes section "{key}" needs bullet items')


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


def build_release_notes(asset_map: dict[str, str | None], bundle: dict[str, str], repo: str, release_tag: str | None) -> str:
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
                '这一版继续把最常用的链路做实：野狐棋谱重新能抓、Windows 免安装包更好选、KataGo 更容易开箱即用。'
                '下载安装后，直接输入 **野狐昵称**，就能继续抓最近公开棋谱、分析和复盘。'
            ),
            'updates': {
                'heading': '本版主要更新',
                'items': [
                    '首次启动和手动智能测速现在都可以主动取消：关闭窗口或点击“停止测速”会结束 KataGo benchmark 进程，并恢复当前分析。',
                    '棋谱加载改成“可操作优先”：本地 SGF、野狐棋谱、在线/共享棋谱加载后，胜率曲线和 movelist 补齐前也能立即用方向键走棋。',
                    'Windows 棋盘同步工具修复 native readboard 启动路径，正常 release 包优先使用内置 `readboard.exe`；启动失败会明确提示并自动回退到 Java 简易版。',
                    '发布说明已精简为本版新增变化，同时继续保持 6 种语言同结构和同一下载表格式。',
                ],
            },
            'before': {
                'heading': '下载前先看这几句',
                'items': [
                    f'Windows 普通用户直接下载 {assets_cn["windows_opencl_portable"]}，这是 **OpenCL 版（推荐，免安装）**。',
                    f'如果 OpenCL 在你的电脑上跑得不好，再改用 {assets_cn["windows_portable"]}。',
                    f'如果你的电脑是 **英伟达显卡**，优先下载 {assets_cn["windows_nvidia_portable"]}。',
                    '如果你更喜欢安装流程，再选同系列的 `installer.exe`。',
                    '抓谱时直接输入 **野狐昵称**，程序会自动匹配账号并获取最近公开棋谱。',
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
                    ('Linux 64 位', assets_cn['linux64']),
                ],
            },
            'why': {
                'heading': '这一版为什么值得先看',
                'items': [
                    '原版已经失效的野狐抓谱链路，现在重新可用。',
                    '现在直接输入“野狐昵称”，程序会自动找到账号再抓最近公开棋谱。',
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
                '這一版繼續把最常用的流程做紮實：野狐棋譜重新能抓、Windows 免安裝包更好選、KataGo 更容易開箱即用。'
                '下載安裝後，直接輸入 **野狐暱稱**，就能繼續抓最近公開棋譜、分析和復盤。'
            ),
            'updates': {
                'heading': '本版主要更新',
                'items': [
                    '首次啟動和手動智慧測速現在都可以主動取消：關閉視窗或點擊「停止測速」會結束 KataGo benchmark 行程，並恢復目前分析。',
                    '棋譜載入改成「可操作優先」：本地 SGF、野狐棋譜、線上/共享棋譜載入後，勝率曲線和 movelist 補齊前也能立即用方向鍵走棋。',
                    'Windows 棋盤同步工具修復 native readboard 啟動路徑，正常 release 包優先使用內建 `readboard.exe`；啟動失敗會明確提示並自動回退到 Java 簡易版。',
                    '發布說明已精簡為本版新增變化，同時繼續保持 6 種語言同結構和同一下載表格式。',
                ],
            },
            'before': {
                'heading': '下載前先看這幾句',
                'items': [
                    f'Windows 一般使用者直接下載 {assets_cn["windows_opencl_portable"]}，這是 **OpenCL 版（推薦，免安裝）**。',
                    f'如果 OpenCL 在你的電腦上跑得不好，再改用 {assets_cn["windows_portable"]}。',
                    f'如果你的電腦是 **NVIDIA 顯示卡**，優先下載 {assets_cn["windows_nvidia_portable"]}。',
                    '如果你更喜歡安裝流程，再選同系列的 `installer.exe`。',
                    '抓譜時直接輸入 **野狐暱稱**，程式會自動匹配帳號並取得最近公開棋譜。',
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
                    ('Linux 64 位', assets_cn['linux64']),
                ],
            },
            'why': {
                'heading': '這一版為什麼值得先看',
                'items': [
                    '原版已經失效的野狐抓譜流程，現在重新可用。',
                    '現在直接輸入「野狐暱稱」，程式會自動找到帳號再抓最近公開棋譜。',
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
                'Fox game fetching works again, portable Windows downloads are easier to choose, and first launch needs less manual setup. '
                'After installing, enter a **Fox nickname** to fetch recent public games for analysis and review.'
            ),
            'updates': {
                'heading': 'Release Highlights',
                'items': [
                    'First-run and manual Smart Optimize benchmarks can now be cancelled: closing the window or pressing Stop kills the KataGo benchmark process and restores analysis.',
                    'Game-record loading now prioritizes interaction: after local SGF, Fox game, online SGF, or shared-game loading, arrow-key navigation works immediately while the winrate graph and movelist catch up.',
                    'The Windows board sync tool now launches native readboard from the correct path, prefers the bundled `readboard.exe` in normal release packages, and clearly falls back to the Java lightweight sync tool if native startup fails.',
                    'Release notes now focus on changes introduced in this release while keeping the fixed six-language structure and matching download tables.',
                ],
            },
            'before': {
                'heading': 'Read Before Downloading',
                'items': [
                    f'Most Windows users should download {assets["windows_opencl_portable"]}, the **recommended no-install OpenCL build**.',
                    f'If OpenCL is unreliable on your PC, use {assets["windows_portable"]} instead.',
                    f'If your PC has an **NVIDIA GPU**, try {assets["windows_nvidia_portable"]} first.',
                    'If you prefer an installer, choose the matching `installer.exe` package.',
                    'Fox game fetching starts from a **Fox nickname** and resolves the matching account automatically.',
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
                    ('Linux 64-bit', assets['linux64']),
                ],
            },
            'why': {
                'heading': 'Why This Release Is Worth Trying',
                'items': [
                    'The Fox game-fetching flow that had broken in the original app is usable again.',
                    'You can enter a Fox nickname directly, and the app resolves the matching account before fetching recent public games.',
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
                '野狐棋譜取得が再び使えるようになり、Windows の portable パッケージを選びやすくし、KataGo の初期セットアップもより簡単にしました。'
                'インストール後、**野狐ニックネーム** を入力すれば、最近の公開棋譜を取得して分析・復盤できます。'
            ),
            'updates': {
                'heading': '主な更新',
                'items': [
                    '初回起動時と手動のスマート最適化 benchmark は、ウィンドウを閉じるか停止ボタンを押すことでキャンセルできます。キャンセル時は KataGo benchmark プロセスを終了し、分析を復帰します。',
                    '棋譜読み込みは操作可能性を優先します。ローカル SGF、野狐棋譜、オンライン/共有棋譜の読み込み後、勝率グラフと movelist の補完前でも方向キーで進められます。',
                    'Windows の棋盤同期ツールは native readboard の起動パスを修正しました。通常の release パッケージでは同梱 `readboard.exe` を優先し、起動失敗時は明確に通知して Java 簡易版へ自動 fallback します。',
                    'リリースノートはこのリリースで追加された変更に絞りつつ、6 言語同一構造と同じダウンロード表形式を維持します。',
                ],
            },
            'before': {
                'heading': 'ダウンロード前に',
                'items': [
                    f'多くの Windows ユーザーは {assets["windows_opencl_portable"]} を選ぶのがおすすめです。これは **推奨 OpenCL 版、インストール不要** です。',
                    f'OpenCL との相性が悪い場合は {assets["windows_portable"]} を使ってください。',
                    f'**NVIDIA GPU** 搭載 PC では {assets["windows_nvidia_portable"]} を優先してください。',
                    'インストーラ形式がよい場合は、同じ系列の `installer.exe` を選んでください。',
                    '棋譜取得では **野狐ニックネーム** を入力します。アプリが一致するアカウントを自動で探します。',
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
                    ('Linux 64-bit', assets['linux64']),
                ],
            },
            'why': {
                'heading': 'このリリースを試す理由',
                'items': [
                    '元版で使えなくなっていた野狐棋譜取得フローが再び利用できます。',
                    '野狐ニックネームを直接入力すれば、アプリがアカウントを探して最近の公開棋譜を取得します。',
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
                'Fox 공개 기보 가져오기를 다시 사용할 수 있게 했고, Windows portable 패키지를 더 쉽게 고를 수 있게 했으며, KataGo 첫 실행 설정도 더 간단하게 정리했습니다. '
                '설치 후 **Fox 닉네임** 을 입력하면 최근 공개 기보를 가져와 분석하고 복기할 수 있습니다.'
            ),
            'updates': {
                'heading': '주요 업데이트',
                'items': [
                    '첫 실행 및 수동 Smart Optimize 벤치마크는 이제 취소할 수 있습니다. 창을 닫거나 Stop 버튼을 누르면 KataGo benchmark 프로세스를 종료하고 분석을 복구합니다.',
                    '기보 로딩은 즉시 조작을 우선합니다. 로컬 SGF, Fox 기보, 온라인/공유 기보를 불러온 뒤 승률 그래프와 movelist 가 보완되기 전에도 방향키 이동이 바로 가능합니다.',
                    'Windows 보드 동기화 도구는 native readboard 실행 경로를 수정했습니다. 정상 release 패키지에서는 내장 `readboard.exe` 를 우선 사용하고, native 시작 실패 시 명확히 알린 뒤 Java 간편 버전으로 자동 fallback 합니다.',
                    'release notes 는 이번 릴리스에서 추가된 변경 사항에 집중하면서도 6개 언어 동일 구조와 같은 다운로드 표 형식을 유지합니다.',
                ],
            },
            'before': {
                'heading': '다운로드 전 확인',
                'items': [
                    f'대부분의 Windows 사용자는 {assets["windows_opencl_portable"]} 를 받으면 됩니다. 이는 **추천 OpenCL 무설치 빌드** 입니다.',
                    f'OpenCL 이 PC에서 불안정하면 {assets["windows_portable"]} 를 대신 사용하세요.',
                    f'**NVIDIA GPU** 가 있다면 {assets["windows_nvidia_portable"]} 를 우선 사용해 보세요.',
                    '설치형 흐름을 원한다면 같은 계열의 `installer.exe` 를 고르세요.',
                    '기보를 가져올 때는 **Fox 닉네임** 을 입력하면 앱이 맞는 계정을 자동으로 찾아 줍니다.',
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
                    ('Linux 64-bit', assets['linux64']),
                ],
            },
            'why': {
                'heading': '이번 릴리스를 먼저 볼 이유',
                'items': [
                    '원본에서 사용할 수 없던 Fox 기보 가져오기 흐름이 다시 동작합니다.',
                    'Fox 닉네임을 직접 입력하면 앱이 계정을 찾고 최근 공개 기보를 가져옵니다.',
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
                'เวอร์ชันนี้ทำให้เส้นทางที่ใช้บ่อยแน่นขึ้น: ดึงเกมจาก Fox ได้อีกครั้ง, แพ็กเกจ Windows แบบ portable เลือกง่ายขึ้น, และ KataGo พร้อมใช้งานมากขึ้น '
                'หลังดาวน์โหลดและติดตั้ง เพียงกรอก **ชื่อเล่น Fox** ก็สามารถดึงเกมสาธารณะล่าสุด วิเคราะห์ และทบทวนเกมได้ต่อทันที'
            ),
            'updates': {
                'heading': 'ไฮไลต์ของเวอร์ชันนี้',
                'items': [
                    'benchmark ครั้งแรกและ Smart Optimize แบบกดเองสามารถยกเลิกได้แล้ว: ปิดหน้าต่างหรือกด Stop จะหยุด process benchmark ของ KataGo และคืนค่าการวิเคราะห์',
                    'การโหลด SGF/บันทึกเกมเน้นให้ใช้งานได้ทันที: local SGF, เกม Fox, online/shared SGF สามารถใช้ปุ่มลูกศรเดินหมากได้ทันที แม้กราฟอัตราชนะและ movelist จะยังเติมข้อมูลตามมา',
                    'เครื่องมือซิงก์กระดานบน Windows แก้ path สำหรับเปิด native readboard แล้ว แพ็กเกจ release ปกติจะใช้ `readboard.exe` ที่มาพร้อมกันก่อน และถ้าเปิด native ไม่สำเร็จจะแจ้งชัดเจนพร้อม fallback ไป Java lightweight sync โดยอัตโนมัติ',
                    'release notes ถูกย่อให้เน้นเฉพาะการเปลี่ยนแปลงของเวอร์ชันนี้ พร้อมคงโครงสร้าง 6 ภาษาและตารางดาวน์โหลดรูปแบบเดียวกัน',
                ],
            },
            'before': {
                'heading': 'ก่อนดาวน์โหลด ดูตรงนี้ก่อน',
                'items': [
                    f'ผู้ใช้ Windows ทั่วไปให้ดาวน์โหลด {assets["windows_opencl_portable"]} ซึ่งเป็น **OpenCL รุ่นแนะนำ แบบไม่ต้องติดตั้ง**',
                    f'ถ้า OpenCL ทำงานไม่ดีบนเครื่องของคุณ ให้เปลี่ยนไปใช้ {assets["windows_portable"]}',
                    f'ถ้าเครื่องของคุณมี **การ์ดจอ NVIDIA** แนะนำให้ใช้ {assets["windows_nvidia_portable"]}',
                    'ถ้าชอบขั้นตอนแบบติดตั้ง ให้เลือกไฟล์ `installer.exe` ในชุดเดียวกัน',
                    'เวลาดึงเกม ให้กรอก **ชื่อเล่น Fox** โปรแกรมจะจับคู่บัญชีและดึงเกมสาธารณะล่าสุดให้อัตโนมัติ',
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
                    ('Linux 64-bit', assets['linux64']),
                ],
            },
            'why': {
                'heading': 'ทำไมเวอร์ชันนี้ควรลองก่อน',
                'items': [
                    'เส้นทางดึงเกมจาก Fox ที่เคยใช้ไม่ได้ในต้นฉบับ ตอนนี้กลับมาใช้ได้อีกครั้ง',
                    'ตอนนี้ใส่ “ชื่อเล่น Fox” ได้โดยตรง โปรแกรมจะหาบัญชีแล้วดึงเกมสาธารณะล่าสุดให้',
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

    validate_release_sections(sections)

    return '# LizzieYzy Next\n\n' + '\n\n---\n\n'.join(
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
