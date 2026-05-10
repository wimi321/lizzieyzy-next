#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import tempfile
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO, Iterator
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

TOKEN_URL = 'https://openapi.baidu.com/oauth/2.0/token'
GITHUB_API_URL = 'https://api.github.com'
XPAN_FILE_URL = 'https://pan.baidu.com/rest/2.0/xpan/file'
SLICE_SIZE = 4 * 1024 * 1024
HTTP_TIMEOUT = 180
MAX_NETWORK_RETRIES = 5
CURRENT_VERSION_FILENAME = '当前版本.txt'
EXPECTED_ASSET_SUFFIXES = (
    'windows64.opencl.portable.zip',
    'windows64.nvidia.portable.zip',
    'windows64.nvidia50.cuda.portable.zip',
    'windows64.nvidia50.trt.portable.zip',
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description='Mirror GitHub release assets into a fixed Baidu Netdisk directory.'
    )
    parser.add_argument(
        '--local-release-dir',
        help='Optional local directory with the mirrored release assets. If omitted, stream from GitHub release directly.',
    )
    parser.add_argument(
        '--from-github-release',
        action='store_true',
        help='Force streaming assets directly from GitHub release instead of reading local files.',
    )
    parser.add_argument(
        '--release-tag',
        required=True,
        help='Release tag, for example 2.5.3-next-2026-04-17.1.',
    )
    parser.add_argument('--date-tag', required=True, help='Release date tag, for example 2026-04-17.')
    parser.add_argument(
        '--remote-root',
        default=os.getenv('BAIDU_REMOTE_ROOT', '/LizzieYzy Next 国内下载'),
        help='Remote Baidu Netdisk root directory.',
    )
    parser.add_argument(
        '--github-release-url',
        help='Optional GitHub release URL. Defaults to owner/tag format inferred from the repo env.',
    )
    parser.add_argument('--repo', default=os.getenv('GITHUB_REPOSITORY', 'wimi321/lizzieyzy-next'))
    parser.add_argument('--dry-run', action='store_true', help='Print planned actions without calling Baidu APIs.')
    return parser.parse_args()


def require_env(name: str) -> str:
    value = os.getenv(name)
    if value:
        return value
    raise SystemExit(f'Missing required environment variable: {name}')


def get_env(name: str) -> str | None:
    value = os.getenv(name)
    return value or None


def github_token() -> str | None:
    return os.getenv('GH_TOKEN') or os.getenv('GITHUB_TOKEN')


def post_form(url: str, payload: dict[str, str]) -> dict[str, Any]:
    encoded = urlencode(payload).encode('utf-8')
    request = Request(
        url,
        data=encoded,
        headers={'Content-Type': 'application/x-www-form-urlencoded'},
        method='POST',
    )
    return urlopen_json_with_retry(request, timeout=HTTP_TIMEOUT, label=url)


def request_json(url: str, *, headers: dict[str, str] | None = None) -> dict[str, Any]:
    request = Request(url, headers=headers or {}, method='GET')
    return urlopen_json_with_retry(request, timeout=HTTP_TIMEOUT, label=url)


def urlopen_json_with_retry(request: Request, *, timeout: int, label: str) -> dict[str, Any]:
    last_error: Exception | None = None
    for attempt in range(1, MAX_NETWORK_RETRIES + 1):
        try:
            with urlopen(request, timeout=timeout) as response:
                return json.load(response)
        except HTTPError as exc:
            raw = exc.read().decode('utf-8', errors='replace')
            if exc.code in (408, 409, 425, 429, 500, 502, 503, 504):
                last_error = RuntimeError(f'HTTP {exc.code} for {label}: {raw}')
                if attempt == MAX_NETWORK_RETRIES:
                    break
                wait_seconds = min(30, attempt * 3)
                print(
                    f'HTTP retry {attempt}/{MAX_NETWORK_RETRIES} for {label}: '
                    f'{exc.code}. Waiting {wait_seconds}s...'
                )
                time.sleep(wait_seconds)
                continue
            raise RuntimeError(f'HTTP {exc.code} for {label}: {raw}') from exc
        except (TimeoutError, URLError, OSError) as exc:
            last_error = exc
            if attempt == MAX_NETWORK_RETRIES:
                break
            wait_seconds = min(30, attempt * 3)
            print(
                f'Network retry {attempt}/{MAX_NETWORK_RETRIES} for {label}: {exc}. '
                f'Waiting {wait_seconds}s...'
            )
            time.sleep(wait_seconds)
    raise RuntimeError(f'Network request failed for {label}: {last_error}')


def refresh_access_token(app_key: str, app_secret: str, refresh_token: str) -> dict[str, Any]:
    payload = post_form(
        TOKEN_URL,
        {
            'grant_type': 'refresh_token',
            'refresh_token': refresh_token,
            'client_id': app_key,
            'client_secret': app_secret,
        },
    )
    error = payload.get('error')
    if error:
        raise RuntimeError(f'Failed to refresh Baidu access token: {json.dumps(payload, ensure_ascii=False)}')
    return payload


@dataclass(frozen=True)
class ReleaseAsset:
    name: str
    size: int
    local_path: Path | None = None
    download_url: str | None = None
    github_token: str | None = None

    def open_stream(self) -> BinaryIO:
        if self.local_path is not None:
            return self.local_path.open('rb')
        if self.download_url:
            return CurlBinaryStream(self.download_url, github_token=self.github_token)
        raise RuntimeError(f'Asset source is not configured: {self.name}')


class CurlBinaryStream:
    def __init__(self, url: str, *, github_token: str | None = None):
        command = [
            'curl',
            '--location',
            '--fail',
            '--silent',
            '--show-error',
            '--retry',
            '4',
            '--retry-all-errors',
            '--retry-delay',
            '2',
            '--connect-timeout',
            '20',
            '--max-time',
            '0',
            '--user-agent',
            'LizzieYzy-Next-BaiduMirror/1.0',
        ]
        if github_token and url.startswith(f'{GITHUB_API_URL}/'):
            command.extend(
                [
                    '--header',
                    'Accept: application/octet-stream',
                    '--header',
                    f'Authorization: Bearer {github_token}',
                    '--header',
                    'X-GitHub-Api-Version: 2022-11-28',
                ]
            )
        command.append(url)
        self._process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if self._process.stdout is None or self._process.stderr is None:
            raise RuntimeError('Failed to start curl download stream.')
        self._stdout = self._process.stdout
        self._stderr = self._process.stderr

    def read(self, size: int = -1) -> bytes:
        return self._stdout.read(size)

    def close(self) -> None:
        if not self._stdout.closed:
            self._stdout.close()
        stderr = self._stderr.read().decode('utf-8', errors='replace').strip()
        code = self._process.wait()
        self._stderr.close()
        if code != 0:
            raise RuntimeError(f'curl download failed with exit code {code}: {stderr}')

    def __enter__(self) -> 'CurlBinaryStream':
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()


class BaiduPanClient:
    def __init__(self, access_token: str, *, dry_run: bool = False):
        self.access_token = access_token
        self.dry_run = dry_run

    def _request_json(
        self,
        url: str,
        *,
        method: str = 'GET',
        params: dict[str, Any] | None = None,
        form: dict[str, Any] | None = None,
        data: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> dict[str, Any]:
        query = dict(params or {})
        if 'access_token' not in query:
            query['access_token'] = self.access_token
        request_url = url
        if query:
            request_url = f'{url}?{urlencode(query)}'
        body = data
        request_headers = dict(headers or {})
        if form is not None:
            normalized = {key: self._stringify_form_value(value) for key, value in form.items()}
            body = urlencode(normalized).encode('utf-8')
            request_headers.setdefault('Content-Type', 'application/x-www-form-urlencoded')
        request = Request(request_url, data=body, headers=request_headers, method=method)
        payload = urlopen_json_with_retry(request, timeout=HTTP_TIMEOUT, label=request_url)
        errno = payload.get('errno')
        if errno not in (None, 0):
            raise RuntimeError(f'Baidu API error: {json.dumps(payload, ensure_ascii=False)}')
        return payload

    @staticmethod
    def _stringify_form_value(value: Any) -> str:
        if isinstance(value, str):
            return value
        if isinstance(value, (int, float)):
            return str(value)
        return json.dumps(value, ensure_ascii=False)

    def list_dir(self, path: str) -> list[dict[str, Any]]:
        if self.dry_run:
            return []
        payload = self._request_json(
            XPAN_FILE_URL,
            params={
                'method': 'list',
                'dir': path,
                'web': '1',
                'start': 0,
                'limit': 1000,
                'folder': 0,
                'desc': 0,
            },
        )
        return payload.get('list', [])

    def path_exists(self, path: str) -> bool:
        parent = parent_dir(path)
        name = Path(path).name
        if not name:
            return True
        for item in self.list_dir(parent):
            if item.get('path') == path or item.get('server_filename') == name:
                return True
        return False

    def create_dir(self, path: str) -> None:
        if self.dry_run:
            print(f'[dry-run] create dir {path}')
            return
        self._request_json(
            XPAN_FILE_URL,
            method='POST',
            params={'method': 'create'},
            form={
                'path': path,
                'isdir': 1,
                'size': 0,
                'block_list': [],
                'rtype': 3,
            },
        )

    def ensure_dir(self, path: str) -> None:
        current = ''
        for part in [segment for segment in path.split('/') if segment]:
            current = f'{current}/{part}'
            if not self.path_exists(current):
                self.create_dir(current)

    def delete_paths(self, paths: list[str]) -> None:
        if not paths:
            return
        if self.dry_run:
            for item in paths:
                print(f'[dry-run] delete {item}')
            return
        self._request_json(
            XPAN_FILE_URL,
            method='POST',
            params={'method': 'filemanager', 'opera': 'delete'},
            form={'async': 0, 'filelist': paths},
        )

    def copy_path(self, source_path: str, destination_dir: str, new_name: str) -> None:
        if self.dry_run:
            print(f'[dry-run] copy {source_path} -> {destination_dir}/{new_name}')
            return
        self._request_json(
            XPAN_FILE_URL,
            method='POST',
            params={'method': 'filemanager', 'opera': 'copy'},
            form={
                'async': 0,
                'filelist': [{'path': source_path, 'dest': destination_dir, 'newname': new_name}],
            },
        )

    def locate_upload_server(self, path: str, upload_id: str) -> str:
        payload = self._request_json(
            XPAN_FILE_URL,
            params={'method': 'locateupload', 'path': path, 'uploadid': upload_id},
        )
        servers = payload.get('servers') or []
        if not servers:
            return 'https://d.pcs.baidu.com'
        server = servers[0]
        if isinstance(server, dict):
            for key in ('https', 'host'):
                if server.get(key):
                    value = server[key]
                    return value if value.startswith('http') else f'https://{value}'
        if isinstance(server, str):
            return server if server.startswith('http') else f'https://{server}'
        return 'https://d.pcs.baidu.com'

    def precreate_file(self, remote_path: str, size: int, block_list: list[str]) -> str:
        payload = self._request_json(
            XPAN_FILE_URL,
            method='POST',
            params={'method': 'precreate'},
            form={
                'path': remote_path,
                'size': size,
                'isdir': 0,
                'autoinit': 1,
                'rtype': 3,
                'block_list': block_list,
            },
        )
        upload_id = payload.get('uploadid')
        if not upload_id:
            raise RuntimeError(f'Missing uploadid in Baidu response: {json.dumps(payload, ensure_ascii=False)}')
        return upload_id

    def upload_tmpfile_part(
        self, server: str, remote_path: str, upload_id: str, part_seq: int, content: bytes
    ) -> None:
        if self.dry_run:
            print(f'[dry-run] upload part {part_seq} -> {remote_path}')
            return
        boundary = f'----LizzieYzyNext{uuid.uuid4().hex}'
        body = build_multipart_body(
            boundary,
            field_name='file',
            filename=f'part-{part_seq}.bin',
            content=content,
            content_type='application/octet-stream',
        )
        upload_url = (
            f"{server.rstrip('/')}/rest/2.0/pcs/superfile2?"
            + urlencode(
                {
                    'method': 'upload',
                    'type': 'tmpfile',
                    'access_token': self.access_token,
                    'path': remote_path,
                    'uploadid': upload_id,
                    'partseq': part_seq,
                }
            )
        )
        request = Request(
            upload_url,
            data=body,
            headers={'Content-Type': f'multipart/form-data; boundary={boundary}'},
            method='POST',
        )
        payload = urlopen_json_with_retry(request, timeout=HTTP_TIMEOUT, label=upload_url)
        errno = payload.get('errno')
        if errno not in (None, 0):
            raise RuntimeError(f'Baidu upload error: {json.dumps(payload, ensure_ascii=False)}')

    def create_file(
        self, remote_path: str, size: int, upload_id: str, block_list: list[str]
    ) -> None:
        if self.dry_run:
            print(f'[dry-run] create file {remote_path}')
            return
        self._request_json(
            XPAN_FILE_URL,
            method='POST',
            params={'method': 'create'},
            form={
                'path': remote_path,
                'size': size,
                'isdir': 0,
                'uploadid': upload_id,
                'rtype': 3,
                'block_list': block_list,
                'local_ctime': int(time.time()),
                'local_mtime': int(time.time()),
            },
        )

    def upload_asset(self, asset: ReleaseAsset, remote_path: str) -> None:
        block_md5s = compute_block_md5s(asset)
        upload_id = self.precreate_file(remote_path, asset.size, block_md5s)
        server = self.locate_upload_server(remote_path, upload_id)
        for index, block in enumerate(iter_asset_blocks(asset)):
            self.upload_tmpfile_part(server, remote_path, upload_id, index, block)
        self.create_file(remote_path, asset.size, upload_id, block_md5s)


def build_multipart_body(
    boundary: str,
    *,
    field_name: str,
    filename: str,
    content: bytes,
    content_type: str,
) -> bytes:
    parts = [
        f'--{boundary}\r\n'.encode('utf-8'),
        (
            f'Content-Disposition: form-data; name="{field_name}"; filename="{filename}"\r\n'
        ).encode('utf-8'),
        f'Content-Type: {content_type}\r\n\r\n'.encode('utf-8'),
        content,
        b'\r\n',
        f'--{boundary}--\r\n'.encode('utf-8'),
    ]
    return b''.join(parts)


def iter_blocks(handle: BinaryIO) -> Iterator[bytes]:
    while True:
        block = handle.read(SLICE_SIZE)
        if not block:
            break
        yield block


def iter_asset_blocks(asset: ReleaseAsset) -> Iterator[bytes]:
    with asset.open_stream() as handle:
        total_bytes = 0
        yielded = False
        for block in iter_blocks(handle):
            yielded = True
            total_bytes += len(block)
            yield block
        if asset.size == 0 and not yielded:
            yield b''
        elif total_bytes != asset.size:
            raise RuntimeError(
                f'Incomplete download for {asset.name}: expected {asset.size} bytes, got {total_bytes}'
            )


def compute_block_md5s(asset: ReleaseAsset) -> list[str]:
    hashes: list[str] = []
    with asset.open_stream() as handle:
        total_bytes = 0
        for block in iter_blocks(handle):
            hashes.append(hashlib.md5(block).hexdigest())
            total_bytes += len(block)
    if not hashes:
        hashes.append(hashlib.md5(b'').hexdigest())
    elif total_bytes != asset.size:
        raise RuntimeError(
            f'Incomplete download for {asset.name}: expected {asset.size} bytes, got {total_bytes}'
        )
    return hashes


def parent_dir(path: str) -> str:
    parts = path.rstrip('/').split('/')
    if len(parts) <= 1:
        return '/'
    return '/'.join(parts[:-1]) or '/'


def pick_expected_assets(assets: list[ReleaseAsset], date_tag: str) -> list[ReleaseAsset]:
    matched: list[ReleaseAsset] = []
    for suffix in EXPECTED_ASSET_SUFFIXES:
        candidates = [item for item in assets if item.name.endswith(suffix)]
        if date_tag:
            dated = [item for item in candidates if item.name.startswith(f'{date_tag}-')]
            if dated:
                candidates = dated
        if not candidates:
            raise SystemExit(f'Missing expected release asset ending with: {suffix}')
        matched.append(sorted(candidates, key=lambda item: item.name)[-1])
    return matched


def collect_local_release_assets(local_release_dir: Path, date_tag: str) -> list[ReleaseAsset]:
    files = [
        ReleaseAsset(name=item.name, size=item.stat().st_size, local_path=item)
        for item in local_release_dir.iterdir()
        if item.is_file()
    ]
    return pick_expected_assets(files, date_tag)


def collect_github_release_assets(repo: str, release_tag: str, date_tag: str) -> list[ReleaseAsset]:
    headers = {
        'Accept': 'application/vnd.github+json',
        'User-Agent': 'LizzieYzy-Next-BaiduMirror/1.0',
    }
    token = github_token()
    if token:
        headers['Authorization'] = f'Bearer {token}'
    payload = request_json(f'{GITHUB_API_URL}/repos/{repo}/releases/tags/{quote(release_tag)}', headers=headers)
    assets = [
        ReleaseAsset(
            name=item['name'],
            size=int(item['size']),
            download_url=(item.get('url') if token else item.get('browser_download_url')),
            github_token=token,
        )
        for item in payload.get('assets', [])
        if (token and item.get('url')) or item.get('browser_download_url')
    ]
    return pick_expected_assets(assets, date_tag)


def write_current_version_file(
    release_tag: str,
    date_tag: str,
    github_release_url: str,
    share_url: str,
    share_code: str,
) -> ReleaseAsset:
    temp_dir = Path(tempfile.mkdtemp(prefix='lizzie-baidu-version-'))
    output = temp_dir / CURRENT_VERSION_FILENAME
    output.write_text(
        '\n'.join(
            [
                f'release_tag={release_tag}',
                f'date_tag={date_tag}',
                f'github_release_url={github_release_url}',
                f'baidu_share_url={share_url}',
                f'baidu_share_code={share_code}',
            ]
        )
        + '\n',
        encoding='utf-8',
    )
    return ReleaseAsset(name=output.name, size=output.stat().st_size, local_path=output)


def collect_existing_paths_by_name(
    items: list[dict[str, Any]], allowed_names: set[str]
) -> list[str]:
    return [
        item['path']
        for item in items
        if item.get('server_filename') in allowed_names and item.get('path')
    ]


def map_items_by_name(items: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    return {
        item['server_filename']: item
        for item in items
        if item.get('server_filename') and item.get('path')
    }


def item_size(item: dict[str, Any] | None) -> int | None:
    if not item:
        return None
    size = item.get('size')
    try:
        return int(size)
    except (TypeError, ValueError):
        return None


def normalize_remote_file_path(base_dir: str, filename: str) -> str:
    path = f'{base_dir}/{quote(filename, safe=".-_() ")}'
    return path.replace('%20', ' ')


def main() -> int:
    args = parse_args()

    use_github_release = args.from_github_release or not args.local_release_dir
    if use_github_release:
        assets = collect_github_release_assets(args.repo, args.release_tag, args.date_tag)
    else:
        local_release_dir = Path(args.local_release_dir)
        if not local_release_dir.is_dir():
            raise SystemExit(f'Local release directory not found: {local_release_dir}')
        assets = collect_local_release_assets(local_release_dir, args.date_tag)

    app_key = require_env('BAIDU_APP_KEY')
    app_secret = require_env('BAIDU_APP_SECRET')
    refresh_token = require_env('BAIDU_REFRESH_TOKEN')
    share_url = get_env('BAIDU_SHARE_URL') or ''
    share_code = get_env('BAIDU_SHARE_CODE') or ''

    github_release_url = args.github_release_url or (
        f'https://github.com/{args.repo}/releases/tag/{quote(args.release_tag)}'
    )

    current_version_file = write_current_version_file(
        args.release_tag, args.date_tag, github_release_url, share_url, share_code
    )

    if args.dry_run:
        print(f'[dry-run] source: {"github-release" if use_github_release else "local-files"}')
        print(
            '[dry-run] release assets: '
            + json.dumps(
                [{'name': item.name, 'size': item.size} for item in assets],
                ensure_ascii=False,
                indent=2,
            )
        )
        print(f'[dry-run] current version note: {current_version_file.local_path}')
        return 0

    access_token = get_env('BAIDU_ACCESS_TOKEN')
    if access_token:
        print('Using existing BAIDU_ACCESS_TOKEN for this sync run.')
    else:
        token_payload = refresh_access_token(app_key, app_secret, refresh_token)
        access_token = token_payload['access_token']
    client = BaiduPanClient(access_token)

    remote_root = args.remote_root.rstrip('/') or '/'
    latest_dir = f'{remote_root}/最新版本'
    history_root = f'{remote_root}/历史版本'
    history_dir = f'{history_root}/{args.release_tag}'

    client.ensure_dir(remote_root)
    client.ensure_dir(latest_dir)
    client.ensure_dir(history_root)
    client.ensure_dir(history_dir)

    latest_items = client.list_dir(latest_dir)
    allowed_latest_names = {asset.name for asset in assets} | {CURRENT_VERSION_FILENAME}
    latest_delete_paths = [
        item['path']
        for item in latest_items
        if item.get('server_filename') not in allowed_latest_names
    ]
    client.delete_paths(latest_delete_paths)

    history_items = client.list_dir(history_dir)
    allowed_history_names = {asset.name for asset in assets}
    history_delete_paths = [
        item['path']
        for item in history_items
        if item.get('server_filename') not in allowed_history_names
    ]
    client.delete_paths(history_delete_paths)
    history_items_by_name = map_items_by_name(history_items)
    latest_items_by_name = map_items_by_name(latest_items)

    for asset in assets:
        history_path = normalize_remote_file_path(history_dir, asset.name)
        latest_path = normalize_remote_file_path(latest_dir, asset.name)
        history_item = history_items_by_name.get(asset.name)
        latest_item = latest_items_by_name.get(asset.name)
        history_matches = item_size(history_item) == asset.size
        latest_matches = item_size(latest_item) == asset.size

        if history_matches and latest_matches:
            print(f'Skipping existing mirrored asset: {asset.name}')
            continue

        if history_matches:
            if latest_item and not latest_matches:
                client.delete_paths([latest_item['path']])
            print(f'Copying existing history asset to latest: {asset.name}')
            client.copy_path(history_path, latest_dir, Path(latest_path).name)
            continue

        if latest_matches:
            if history_item and not history_matches:
                client.delete_paths([history_item['path']])
            print(f'Backfilling history from existing latest asset: {asset.name}')
            client.copy_path(latest_path, history_dir, Path(history_path).name)
            continue

        delete_paths: list[str] = []
        if history_item:
            delete_paths.append(history_item['path'])
        if latest_item:
            delete_paths.append(latest_item['path'])
        client.delete_paths(delete_paths)

        print(f'Uploading history copy: {asset.name}')
        client.upload_asset(asset, history_path)
        print(f'Copying to latest: {asset.name}')
        client.copy_path(history_path, latest_dir, Path(latest_path).name)

    version_note_path = normalize_remote_file_path(latest_dir, CURRENT_VERSION_FILENAME)
    print(f'Uploading version note: {CURRENT_VERSION_FILENAME}')
    client.upload_asset(current_version_file, version_note_path)

    print('Baidu Netdisk mirror sync completed.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
