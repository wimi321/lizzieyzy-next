#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any
from urllib.parse import urlencode
from urllib.request import Request, urlopen

AUTHORIZE_URL = 'https://openapi.baidu.com/oauth/2.0/authorize'
TOKEN_URL = 'https://openapi.baidu.com/oauth/2.0/token'
DEFAULT_SCOPE = 'basic,netdisk'
DEFAULT_REDIRECT_URI = 'oob'


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description='Generate Baidu Netdisk OAuth URLs and exchange authorization codes.'
    )
    subparsers = parser.add_subparsers(dest='command', required=True)

    authorize_parser = subparsers.add_parser(
        'authorize-url',
        help='Print the URL that the maintainer should open in a browser.',
    )
    add_common_client_args(authorize_parser, need_secret=False)
    authorize_parser.add_argument('--scope', default=DEFAULT_SCOPE, help='OAuth scope list.')
    authorize_parser.add_argument(
        '--redirect-uri',
        default=DEFAULT_REDIRECT_URI,
        help='OAuth redirect_uri. Use oob for copy-paste authorization.',
    )
    authorize_parser.add_argument('--state', help='Optional OAuth state value.')
    authorize_parser.add_argument(
        '--json', action='store_true', help='Print JSON instead of plain text.'
    )

    exchange_parser = subparsers.add_parser(
        'exchange-code',
        help='Exchange an authorization code for access_token and refresh_token.',
    )
    add_common_client_args(exchange_parser, need_secret=True)
    exchange_parser.add_argument('--code', required=True, help='Authorization code from Baidu.')
    exchange_parser.add_argument(
        '--redirect-uri',
        default=DEFAULT_REDIRECT_URI,
        help='OAuth redirect_uri used in the authorize step.',
    )
    exchange_parser.add_argument(
        '--json', action='store_true', help='Print raw JSON from Baidu.'
    )

    refresh_parser = subparsers.add_parser(
        'refresh-token',
        help='Refresh an access token using an existing refresh token.',
    )
    add_common_client_args(refresh_parser, need_secret=True)
    refresh_parser.add_argument(
        '--refresh-token',
        default=os.getenv('BAIDU_REFRESH_TOKEN'),
        help='Existing refresh token. Defaults to BAIDU_REFRESH_TOKEN.',
    )
    refresh_parser.add_argument(
        '--json', action='store_true', help='Print raw JSON from Baidu.'
    )

    return parser.parse_args()


def add_common_client_args(parser: argparse.ArgumentParser, *, need_secret: bool) -> None:
    parser.add_argument(
        '--app-key',
        default=os.getenv('BAIDU_APP_KEY'),
        help='Baidu app key. Defaults to BAIDU_APP_KEY.',
    )
    if need_secret:
        parser.add_argument(
            '--app-secret',
            default=os.getenv('BAIDU_APP_SECRET'),
            help='Baidu app secret. Defaults to BAIDU_APP_SECRET.',
        )


def require_value(name: str, value: str | None) -> str:
    if value:
        return value
    raise SystemExit(f'Missing required value: {name}')


def post_form(url: str, payload: dict[str, str]) -> dict[str, Any]:
    encoded = urlencode(payload).encode('utf-8')
    request = Request(
        url,
        data=encoded,
        headers={'Content-Type': 'application/x-www-form-urlencoded'},
        method='POST',
    )
    with urlopen(request) as response:
        return json.load(response)


def build_authorize_url(
    app_key: str, redirect_uri: str, scope: str, state: str | None
) -> str:
    query = {
        'response_type': 'code',
        'client_id': app_key,
        'redirect_uri': redirect_uri,
        'scope': scope,
    }
    if state:
        query['state'] = state
    return f'{AUTHORIZE_URL}?{urlencode(query)}'


def exchange_code(
    app_key: str, app_secret: str, code: str, redirect_uri: str
) -> dict[str, Any]:
    return post_form(
        TOKEN_URL,
        {
            'grant_type': 'authorization_code',
            'code': code,
            'client_id': app_key,
            'client_secret': app_secret,
            'redirect_uri': redirect_uri,
        },
    )


def refresh_token(app_key: str, app_secret: str, refresh: str) -> dict[str, Any]:
    return post_form(
        TOKEN_URL,
        {
            'grant_type': 'refresh_token',
            'refresh_token': refresh,
            'client_id': app_key,
            'client_secret': app_secret,
        },
    )


def print_token_result(payload: dict[str, Any], *, as_json: bool) -> None:
    if as_json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return

    error = payload.get('error')
    if error:
        print('Baidu OAuth returned an error:', file=sys.stderr)
        print(json.dumps(payload, ensure_ascii=False, indent=2), file=sys.stderr)
        raise SystemExit(1)

    print('授权成功，后续主要保存这 3 个值：')
    print()
    print(f"access_token:  {payload.get('access_token', '')}")
    print(f"refresh_token: {payload.get('refresh_token', '')}")
    print(f"expires_in:    {payload.get('expires_in', '')}")
    print()
    print('建议至少保存 refresh_token，并配置到 GitHub Actions secret：')
    print(f"BAIDU_REFRESH_TOKEN={payload.get('refresh_token', '')}")


def main() -> int:
    args = parse_args()

    if args.command == 'authorize-url':
        app_key = require_value('--app-key / BAIDU_APP_KEY', args.app_key)
        url = build_authorize_url(app_key, args.redirect_uri, args.scope, args.state)
        if args.json:
            print(
                json.dumps(
                    {
                        'authorize_url': url,
                        'redirect_uri': args.redirect_uri,
                        'scope': args.scope,
                        'state': args.state,
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
        else:
            print('请在浏览器里打开这个地址完成授权：')
            print(url)
            print()
            print('授权完成后，把浏览器里返回的 code 拿回来，再执行：')
            print(
                'python3 scripts/baidu_pan_oauth.py exchange-code '
                '--app-key <APP_KEY> --app-secret <APP_SECRET> --code <CODE>'
            )
        return 0

    app_key = require_value('--app-key / BAIDU_APP_KEY', args.app_key)
    app_secret = require_value('--app-secret / BAIDU_APP_SECRET', args.app_secret)

    if args.command == 'exchange-code':
        payload = exchange_code(app_key, app_secret, args.code, args.redirect_uri)
        print_token_result(payload, as_json=args.json)
        return 0

    refresh = require_value('--refresh-token / BAIDU_REFRESH_TOKEN', args.refresh_token)
    payload = refresh_token(app_key, app_secret, refresh)
    print_token_result(payload, as_json=args.json)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
