#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
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
    ('mac_arm64', 'mac-arm64.with-katago.dmg', 'macOS Apple Silicon', 'macOS Apple Silicon'),
    ('mac_amd64', 'mac-amd64.with-katago.dmg', 'macOS Intel', 'macOS Intel'),
    ('linux64', 'linux64.with-katago.zip', 'Linux 64 位', 'Linux x64'),
]


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


def build_release_notes(asset_map: dict[str, str | None], bundle: dict[str, str], repo: str, release_tag: str | None) -> str:
    windows_installer = format_asset(asset_map['windows_installer'], repo, release_tag)
    windows_portable = format_asset(asset_map['windows_portable'], repo, release_tag)
    windows_opencl_installer = format_asset(asset_map['windows_opencl_installer'], repo, release_tag)
    windows_opencl_portable = format_asset(asset_map['windows_opencl_portable'], repo, release_tag)
    windows_nvidia_installer = format_asset(asset_map['windows_nvidia_installer'], repo, release_tag)
    windows_nvidia_portable = format_asset(asset_map['windows_nvidia_portable'], repo, release_tag)
    windows_no_engine_installer = format_asset(asset_map['windows_no_engine_installer'], repo, release_tag)
    windows_no_engine_portable = format_asset(asset_map['windows_no_engine_portable'], repo, release_tag)
    mac_arm64 = format_asset(asset_map['mac_arm64'], repo, release_tag)
    mac_amd64 = format_asset(asset_map['mac_amd64'], repo, release_tag)
    linux64 = format_asset(asset_map['linux64'], repo, release_tag)

    windows_installer_en = format_asset_en(asset_map['windows_installer'], repo, release_tag)
    windows_portable_en = format_asset_en(asset_map['windows_portable'], repo, release_tag)
    windows_opencl_installer_en = format_asset_en(asset_map['windows_opencl_installer'], repo, release_tag)
    windows_opencl_portable_en = format_asset_en(asset_map['windows_opencl_portable'], repo, release_tag)
    windows_nvidia_installer_en = format_asset_en(asset_map['windows_nvidia_installer'], repo, release_tag)
    windows_nvidia_portable_en = format_asset_en(asset_map['windows_nvidia_portable'], repo, release_tag)
    windows_no_engine_installer_en = format_asset_en(asset_map['windows_no_engine_installer'], repo, release_tag)
    windows_no_engine_portable_en = format_asset_en(asset_map['windows_no_engine_portable'], repo, release_tag)
    mac_arm64_en = format_asset_en(asset_map['mac_arm64'], repo, release_tag)
    mac_amd64_en = format_asset_en(asset_map['mac_amd64'], repo, release_tag)
    linux64_en = format_asset_en(asset_map['linux64'], repo, release_tag)

    katago_version = bundle['katago_version']
    model_source = bundle['model_source']
    windows_opencl_bundle = bundle['windows_opencl_bundle']
    windows_nvidia_bundle = bundle['windows_nvidia_bundle']
    china_mirror_url = os.getenv('CHINA_MIRROR_URL', '').strip()
    china_mirror_code = os.getenv('CHINA_MIRROR_CODE', '').strip()

    mirror_cn = ''
    mirror_other = ''
    if china_mirror_url:
        mirror_cn = '\n'.join(
            [
                '### 国内高速下载（百度网盘）',
                '',
                f'- 分享链接：[{china_mirror_url}]({china_mirror_url})',
                f'- 提取码：`{china_mirror_code or "请联系维护者补充"}`',
                '',
            ]
        )
        mirror_other = (
            f'- China mirror: [Baidu Netdisk]({china_mirror_url})'
            + (f' (code: `{china_mirror_code}`)' if china_mirror_code else '')
        )

    return f"""# LizzieYzy Next

## 中文

这是当前仍在维护的 `lizzieyzy` 维护版，也是一个面向普通用户的 `KataGo 围棋复盘 GUI`。这一版继续把最常用的链路做实：野狐棋谱重新能抓、Windows 免安装包更好选、KataGo 更容易开箱即用。下载安装后，直接输入 **野狐昵称**，就能继续抓最近公开棋谱、分析和复盘。

{mirror_cn}

### 下载前先看这几句

- Windows 普通用户直接下载 {windows_opencl_portable}，这是 **OpenCL 版（推荐，免安装）**
- 如果 OpenCL 在你的电脑上跑得不好，再改用 {windows_portable}
- 如果你的电脑是 **英伟达显卡**，优先下载 {windows_nvidia_portable}
- 如果你更喜欢安装流程，再选同系列的 `installer.exe`
- 抓谱时直接输入 **野狐昵称**，程序会自动匹配账号并获取最近公开棋谱
- 主推荐整合包已内置 KataGo `{katago_version}` 和默认权重 `{model_source}`
- Windows OpenCL 版也支持 **智能优化**，可以自动写入更合适的线程设置
- Windows 现在把 OpenCL 版放到推荐位，优先照顾更快的分析速度
- CPU 版继续保留，作为 OpenCL 不稳定时的兼容兜底
- Windows NVIDIA 整合包已内置官方运行库，首启可离线使用

### 下载建议

| 你的电脑 | 直接下载这个 |
| --- | --- |
| Windows 64 位，OpenCL 版，推荐更快，免安装 | {windows_opencl_portable} |
| Windows 64 位，OpenCL 版，想安装 | {windows_opencl_installer} |
| Windows 64 位，CPU 版，兼容兜底，免安装 | {windows_portable} |
| Windows 64 位，CPU 版，兼容兜底，想安装 | {windows_installer} |
| Windows 64 位，英伟达显卡，想更快，免安装 | {windows_nvidia_portable} |
| Windows 64 位，英伟达显卡，想更快，也想安装 | {windows_nvidia_installer} |
| Windows 64 位，想自己配引擎 | {windows_no_engine_portable} |
| Windows 64 位，想自己配引擎，也想安装器 | {windows_no_engine_installer} |
| macOS Apple Silicon | {mac_arm64} |
| macOS Intel | {mac_amd64} |
| Linux 64 位 | {linux64} |

### 这一版为什么值得先看

- 原版已经失效的野狐抓谱链路，现在重新可用
- 现在直接输入“野狐昵称”，程序会自动找到账号再抓最近公开棋谱
- Windows 现在把 `.portable.zip` 放到推荐位，解压后更符合多数用户习惯
- Windows 现在同时提供 OpenCL 版和 CPU 版，下载时更容易按“速度优先”还是“兼容优先”来选
- Windows OpenCL 版现在放到推荐位，优先照顾更快的分析速度
- Windows OpenCL 版也支持智能优化，测速后会自动保存推荐线程数
- Windows CPU 版继续保留，适合 OpenCL 表现不理想的机器
- 对有 NVIDIA 独显的 Windows 用户，额外提供官方 CUDA 版 KataGo 的极速整合包，并且把官方运行库一起打进包里
- macOS 继续提供 Apple Silicon / Intel 两种 `.dmg`
- 整合包继续内置 KataGo 与默认权重，打开后更快进入分析

### 交流

- QQ 群：`299419120`

## English

This is the actively maintained `LizzieYzy` fork and a practical `KataGo review GUI` for normal users. Fox game fetching works again, portable Windows downloads are easier to choose, and first launch needs less manual setup.

{mirror_other}
- Windows first choice: {windows_opencl_portable_en} for the recommended no-install OpenCL build
- Windows CPU fallback: {windows_portable_en}
- Windows NVIDIA choice: {windows_nvidia_portable_en}
- Windows OpenCL installer alternative: {windows_opencl_installer_en}
- Windows CPU installer alternative: {windows_installer_en}
- Windows NVIDIA installer alternative: {windows_nvidia_installer_en}
- Windows custom-engine portable: {windows_no_engine_portable_en}
- Windows custom-engine installer: {windows_no_engine_installer_en}
- Fox fetch now starts from a **Fox nickname** and resolves the matching account automatically.
- The recommended bundles include KataGo `{katago_version}` and the default weight `{model_source}`.
- The recommended Windows bundle also supports **Smart Optimize** to benchmark and save a better thread setting automatically.
- The OpenCL Windows bundle is now the main recommended choice for users who want better analysis speed.
- The CPU Windows bundle is kept as a fallback for PCs where OpenCL behaves poorly.
- The NVIDIA package uses the official KataGo CUDA build `{windows_nvidia_bundle}`.
- The OpenCL package uses the official KataGo OpenCL build `{windows_opencl_bundle}`.
- The NVIDIA bundle now includes the official NVIDIA runtime files, so supported PCs can start offline on first launch.
- First launch tries to prepare the bundled analysis setup automatically.
- macOS downloads: Apple Silicon {mac_arm64_en}, Intel {mac_amd64_en}
- Linux download: {linux64_en}
- Chinese QQ group: `299419120`

## 日本語

このリリースは、現在も保守されている `lizzieyzy` の保守版であり、普通の利用者向けの `KataGo 復盤 GUI` でもあります。壊れていた野狐棋譜取得を復旧し、ダウンロード後すぐ使いやすい形に整えています。

{mirror_other}
- Windows 利用者の多くは {windows_opencl_portable_en} を選べば始めやすいです
- OpenCL の相性が悪い場合は {windows_portable_en} を代わりに選べます
- NVIDIA GPU を使っていて、より速い解析を求める場合は {windows_nvidia_portable_en} を選べます
- インストーラの流れがよければ、対応する `installer.exe` も選べます
- 自分のエンジンを使いたい場合は {windows_no_engine_portable_en} または {windows_no_engine_installer_en} を選べます
- 棋譜取得では **野狐のニックネーム** を入力します。アプリが一致するアカウントを自動で探します
- 推奨の Windows OpenCL 版でも **Smart Optimize** により、推奨スレッド数を保存しやすくなりました
- Windows では OpenCL 版を推奨にし、より速い解析を優先しました
- CPU 版は OpenCL の相性が悪い環境向けの互換フォールバックとして残しています
- 初回起動では、内蔵の解析環境を自動で準備する流れを優先します
- NVIDIA 同梱版は、必要な公式ランタイムも同梱するため、対応 PC なら初回起動をオフラインで始めやすくなりました
- 主な整合パッケージには KataGo `{katago_version}` と既定の重み `{model_source}` が含まれています

## 한국어

이 릴리스는 지금도 유지보수 중인 `lizzieyzy` 유지보수판이자, 일반 사용자를 위한 `KataGo 복기 GUI` 입니다. 고장난 Fox 공개 기보 가져오기를 복구하고, 다운로드 후 바로 쓰기 쉬운 형태로 정리했습니다.

{mirror_other}
- 대부분의 Windows 사용자는 {windows_opencl_portable_en} 를 먼저 받으면 가장 쉽습니다
- OpenCL 이 잘 맞지 않는 PC 라면 {windows_portable_en} 를 대신 고를 수 있습니다
- NVIDIA 그래픽카드가 있고 더 빠른 분석을 원하면 {windows_nvidia_portable_en} 를 고를 수 있습니다
- 설치형 흐름을 원한다면 대응하는 `installer.exe` 도 고를 수 있습니다
- 직접 엔진을 쓰고 싶다면 {windows_no_engine_portable_en} 또는 {windows_no_engine_installer_en} 를 고를 수 있습니다
- 기보를 가져올 때는 **Fox 닉네임** 을 입력하면 앱이 맞는 계정을 자동으로 찾아 줍니다
- 추천 Windows OpenCL 통합판도 **Smart Optimize** 로 더 알맞은 스레드 값을 저장할 수 있습니다
- Windows 에서는 더 빠른 분석을 위해 OpenCL 판을 추천으로 올렸습니다
- CPU 판은 OpenCL 이 잘 맞지 않는 PC 를 위한 호환용 대안으로 유지합니다
- 첫 실행에서는 내장 분석 환경을 자동으로 준비하는 흐름을 먼저 시도합니다
- NVIDIA 통합판은 필요한 공식 런타임도 함께 포함하므로, 지원되는 PC에서는 첫 실행을 오프라인으로 시작하기 쉽습니다
- 주요 통합 패키지에는 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 가 포함되어 있습니다
"""


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
