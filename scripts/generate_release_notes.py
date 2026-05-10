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
    ('windows_nvidia50_cuda_installer', 'windows64.nvidia50.cuda.installer.exe', 'Windows 64 位，RTX 50 CUDA 版', 'Windows x64, RTX 50 CUDA'),
    ('windows_nvidia50_cuda_portable', 'windows64.nvidia50.cuda.portable.zip', 'Windows 64 位，RTX 50 CUDA 版，免安装', 'Windows x64, RTX 50 CUDA, no installer'),
    ('windows_nvidia50_trt_installer', 'windows64.nvidia50.trt.installer.exe', 'Windows 64 位，RTX 50 TensorRT 试验版', 'Windows x64, RTX 50 TensorRT experimental'),
    ('windows_nvidia50_trt_portable', 'windows64.nvidia50.trt.portable.zip', 'Windows 64 位，RTX 50 TensorRT 试验版，免安装', 'Windows x64, RTX 50 TensorRT experimental, no installer'),
    ('windows_no_engine_installer', 'windows64.without.engine.installer.exe', 'Windows 64 位，想自己配引擎，也想安装器', 'Windows x64, your own engine with installer'),
    ('windows_no_engine_portable', 'windows64.without.engine.portable.zip', 'Windows 64 位，想自己配引擎', 'Windows x64, your own engine'),
    ('mac_arm64', 'mac-apple-silicon.with-katago.dmg', 'macOS Apple Silicon', 'macOS Apple Silicon'),
    ('mac_amd64', 'mac-intel.with-katago.dmg', 'macOS Intel', 'macOS Intel'),
    ('linux64', 'linux64.with-katago.zip', 'Linux 64 位，CPU 兼容版', 'Linux x64, CPU fallback'),
    ('linux64_opencl', 'linux64.opencl.zip', 'Linux 64 位，OpenCL 版', 'Linux x64, OpenCL'),
    ('linux64_nvidia', 'linux64.nvidia.zip', 'Linux 64 位，NVIDIA CUDA 版', 'Linux x64, NVIDIA CUDA'),
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
        'windows_nvidia50_cuda_bundle': 'Unknown',
        'windows_nvidia50_trt_bundle': 'Unknown',
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
            elif key == 'windows nvidia 50 tensorrt bundle':
                metadata['windows_nvidia50_trt_bundle'] = value
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
            'windows_nvidia50_trt_bundle': r'WINDOWS_NVIDIA50_TRT_ASSET="\$\{WINDOWS_NVIDIA50_TRT_ASSET:-([^"]+)\}"',
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
            'windows_nvidia50_trt_bundle',
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
        metadata['windows_nvidia50_trt_bundle'] = metadata['windows_nvidia50_trt_bundle'].replace('${KATAGO_TAG}', katago_version)
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
        if metadata['windows_nvidia50_trt_bundle'] == 'Unknown':
            metadata['windows_nvidia50_trt_bundle'] = (
                f'katago-{katago_version}-trt10.9.0-cuda12.8-windows-x64.zip'
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
                if not isinstance(rows, list) or len(rows) != expected_download_rows:
                    raise SystemExit(
                        f'{language} download table must contain {expected_download_rows} rows'
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
            'Windows 64 位，RTX 50 TensorRT 试验版，免安装',
            'Windows 64 位，RTX 50 TensorRT 试验版，想安装',
        ),
        '繁體中文': (
            'Windows 64 位，RTX 50 CUDA 版，5070/5080/5090 優先，免安裝',
            'Windows 64 位，RTX 50 CUDA 版，5070/5080/5090 優先，想安裝',
            'Windows 64 位，RTX 50 TensorRT 試驗版，免安裝',
            'Windows 64 位，RTX 50 TensorRT 試驗版，想安裝',
        ),
        'English': (
            'Windows 64-bit, RTX 50 CUDA, recommended for 5070/5080/5090, no install',
            'Windows 64-bit, RTX 50 CUDA, recommended for 5070/5080/5090, installer',
            'Windows 64-bit, RTX 50 TensorRT experimental, no install',
            'Windows 64-bit, RTX 50 TensorRT experimental, installer',
        ),
        '日本語': (
            'Windows 64-bit、RTX 50 CUDA、5070/5080/5090 推奨、インストール不要',
            'Windows 64-bit、RTX 50 CUDA、5070/5080/5090 推奨、インストーラ',
            'Windows 64-bit、RTX 50 TensorRT 試験版、インストール不要',
            'Windows 64-bit、RTX 50 TensorRT 試験版、インストーラ',
        ),
        '한국어': (
            'Windows 64-bit, RTX 50 CUDA, 5070/5080/5090 권장, 무설치',
            'Windows 64-bit, RTX 50 CUDA, 5070/5080/5090 권장, 설치형',
            'Windows 64-bit, RTX 50 TensorRT 실험판, 무설치',
            'Windows 64-bit, RTX 50 TensorRT 실험판, 설치형',
        ),
        'ภาษาไทย': (
            'Windows 64-bit, RTX 50 CUDA, แนะนำสำหรับ 5070/5080/5090, ไม่ต้องติดตั้ง',
            'Windows 64-bit, RTX 50 CUDA, แนะนำสำหรับ 5070/5080/5090, แบบติดตั้ง',
            'Windows 64-bit, RTX 50 TensorRT รุ่นทดลอง, ไม่ต้องติดตั้ง',
            'Windows 64-bit, RTX 50 TensorRT รุ่นทดลอง, แบบติดตั้ง',
        ),
    }
    before_note_by_language = {
        '中文': 'RTX 5070/5080/5090 用户优先下载 RTX 50 CUDA 版；TensorRT 版是试验包，适合愿意反馈测速和日志的用户。',
        '繁體中文': 'RTX 5070/5080/5090 使用者優先下載 RTX 50 CUDA 版；TensorRT 版是試驗包，適合願意回報測速和日誌的使用者。',
        'English': 'RTX 5070/5080/5090 users should try the RTX 50 CUDA build first; the TensorRT build is experimental for users willing to report benchmark results and logs.',
        '日本語': 'RTX 5070/5080/5090 ユーザーは RTX 50 CUDA 版を優先してください。TensorRT 版は、ベンチマーク結果とログを共有できる方向けの試験版です。',
        '한국어': 'RTX 5070/5080/5090 사용자는 RTX 50 CUDA 버전을 먼저 권장합니다. TensorRT 버전은 벤치마크와 로그 피드백을 줄 수 있는 사용자를 위한 실험판입니다.',
        'ภาษาไทย': 'ผู้ใช้ RTX 5070/5080/5090 ควรลอง RTX 50 CUDA ก่อน ส่วน TensorRT เป็นรุ่นทดลองสำหรับผู้ที่ยินดีส่งผล benchmark และ log กลับมา',
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
            (labels[2], localized_assets['windows_nvidia50_trt_portable']),
            (labels[3], localized_assets['windows_nvidia50_trt_installer']),
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


def build_release_notes(asset_map: dict[str, str | None], bundle: dict[str, str], repo: str, release_tag: str | None) -> str:
    if release_tag == 'next-2026-05-03.1':
        return build_next_2026_05_03_1_notes(asset_map, repo, release_tag)
    if release_tag == 'next-2026-05-04.1':
        return build_next_2026_05_04_1_notes(asset_map, repo, release_tag)
    if release_tag == 'next-2026-05-06.1':
        return build_next_2026_05_06_1_notes(asset_map, repo, release_tag)

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
                    'KataGo 人机对弈的“AI 每手用时”现在按固定秒数执行，不再因为 KataGo 时间管理而提前秒下；本机实测设置 4 秒时约 4.03 秒落子。',
                    '引擎设置里的自动加载方式（默认引擎、最后退出的引擎、手动选择、无引擎）和贴目现在会保存，重启后不再被内置 KataGo 默认项覆盖。',
                    '合并 qiyi71w 的 PR #17：新增 Web 端试下模式与引擎跟随分析。感谢 qiyi71w 持续优化和贡献。',
                    '发布前已重新跑全量测试、打包和本机启动冒烟；当前 GitHub 开放 PR 已清空。',
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
                    ('Linux 64 位，CPU 兼容版', assets_cn['linux64']),
                    ('Linux 64 位，OpenCL 版，AMD/Intel GPU', assets_cn['linux64_opencl']),
                    ('Linux 64 位，NVIDIA CUDA 版', assets_cn['linux64_nvidia']),
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
                    'KataGo 人機對弈的「AI 每手用時」現在會按固定秒數執行，不再因 KataGo 時間管理而提前秒下；本機實測設定 4 秒時約 4.03 秒落子。',
                    '引擎設定裡的自動載入方式（預設引擎、最後退出的引擎、手動選擇、無引擎）和貼目現在會儲存，重啟後不再被內建 KataGo 預設項覆蓋。',
                    '合併 qiyi71w 的 PR #17：新增 Web 端試下模式與引擎跟隨分析。感謝 qiyi71w 持續最佳化與貢獻。',
                    '發布前已重新跑完整測試、打包和本機啟動冒煙；目前 GitHub 開放 PR 已清空。',
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
                    ('Linux 64 位，CPU 相容版', assets_cn['linux64']),
                    ('Linux 64 位，OpenCL 版，AMD/Intel GPU', assets_cn['linux64_opencl']),
                    ('Linux 64 位，NVIDIA CUDA 版', assets_cn['linux64_nvidia']),
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
                    'KataGo human-vs-AI games now honor the fixed “AI seconds per move” setting instead of moving early because of KataGo time management; a local 4-second smoke test moved in about 4.03 seconds.',
                    'Engine Settings now persist auto-load mode (default engine, last engine, manual choice, no engine) and komi across restart instead of being overwritten by bundled KataGo defaults.',
                    'Merged qiyi71w’s PR #17: Web trial mode plus engine-following analysis. Thank you qiyi71w for the continued improvements and contributions.',
                    'Before release, full tests, packaging, and a local launch smoke test were rerun; there are no open GitHub PRs at publish time.',
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
                    ('Linux 64-bit, CPU fallback', assets['linux64']),
                    ('Linux 64-bit, OpenCL for AMD/Intel GPU', assets['linux64_opencl']),
                    ('Linux 64-bit, NVIDIA CUDA', assets['linux64_nvidia']),
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
                    'KataGo との人間対 AI 対局で、「AI の 1 手あたり秒数」が固定秒数として効くようになりました。KataGo の時間管理で早打ちする問題を避け、ローカル 4 秒テストでは約 4.03 秒で着手しました。',
                    'エンジン設定の自動読み込み方式（既定エンジン、最後に終了したエンジン、手動選択、エンジンなし）とコミが再起動後も保持され、同梱 KataGo の既定値で上書きされなくなりました。',
                    'qiyi71w さんの PR #17 をマージしました。Web 端末の試し打ちモードと、表示局面に追従するエンジン分析が追加されています。継続的な改善と貢献に感謝します。',
                    'リリース前に full test、package、ローカル起動 smoke test を再実行しました。公開時点で GitHub の open PR はありません。',
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
                    ('Linux 64-bit、CPU fallback', assets['linux64']),
                    ('Linux 64-bit、OpenCL、AMD/Intel GPU', assets['linux64_opencl']),
                    ('Linux 64-bit、NVIDIA CUDA', assets['linux64_nvidia']),
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
                    'KataGo 인간 대 AI 대국에서 “AI 한 수당 시간” 설정이 고정 초 단위로 적용됩니다. KataGo 시간 관리 때문에 너무 빨리 두던 문제를 피했으며, 로컬 4초 smoke test 에서는 약 4.03초에 착수했습니다.',
                    '엔진 설정의 자동 로드 방식(기본 엔진, 마지막 종료 엔진, 수동 선택, 엔진 없음)과 덤이 재시작 후에도 유지되며, 내장 KataGo 기본값으로 덮어써지지 않습니다.',
                    'qiyi71w 의 PR #17 을 병합했습니다. Web 시험수 모드와 현재 표시 국면을 따라가는 엔진 분석이 추가되었습니다. 지속적인 개선과 기여에 감사드립니다.',
                    '릴리스 전에 full test, package, 로컬 실행 smoke test 를 다시 수행했습니다. 공개 시점의 GitHub open PR 은 없습니다.',
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
                    ('Linux 64-bit, CPU fallback', assets['linux64']),
                    ('Linux 64-bit, OpenCL, AMD/Intel GPU', assets['linux64_opencl']),
                    ('Linux 64-bit, NVIDIA CUDA', assets['linux64_nvidia']),
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
                    'โหมดคนเล่นกับ KataGo จะทำตามค่า “เวลา AI ต่อหนึ่งตา” แบบคงที่แล้ว ไม่เดินเร็วเกินไปจาก time management ของ KataGo; ทดสอบในเครื่องที่ 4 วินาทีแล้วเดินประมาณ 4.03 วินาที',
                    'Engine Settings จะจำโหมดโหลดอัตโนมัติ (default engine, last engine, manual choice, no engine) และ komi หลังรีสตาร์ต ไม่ถูกค่า default ของ KataGo ที่มากับโปรแกรมทับอีก',
                    'รวม PR #17 ของ qiyi71w แล้ว: เพิ่ม Web trial mode และการวิเคราะห์ที่ engine ติดตามตำแหน่งที่แสดงอยู่ ขอบคุณ qiyi71w สำหรับการปรับปรุงและการร่วมพัฒนาอย่างต่อเนื่อง',
                    'ก่อนปล่อยเวอร์ชันนี้ ได้รัน full test, package และ local launch smoke test ซ้ำแล้ว และตอนเผยแพร่ไม่มี GitHub PR ที่เปิดค้างอยู่',
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
                    ('Linux 64-bit, CPU fallback', assets['linux64']),
                    ('Linux 64-bit, OpenCL สำหรับ AMD/Intel GPU', assets['linux64_opencl']),
                    ('Linux 64-bit, NVIDIA CUDA', assets['linux64_nvidia']),
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

    add_nvidia50_download_rows(sections, assets_cn, assets)
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
