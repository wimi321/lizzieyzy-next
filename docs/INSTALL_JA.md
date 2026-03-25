# インストールガイド

このガイドは、`LizzieYzy Next` をできるだけ早く使い始めたい人向けです。

## まず覚えること

1. Windows 利用者の多くは `windows64.with-katago.installer.exe` を選べば大丈夫です。
2. NVIDIA GPU を使っていて、より速い解析を求めるなら `windows64.nvidia.installer.exe` を選べます。
3. `with-katago` パッケージは KataGo と既定の重みを含みます。
4. 野狐棋譜を取得するときは、いまは **野狐のニックネーム** を入力します。

## ダウンロードするもの

| 環境 | 推奨パッケージ |
| --- | --- |
| Windows x64 | `<date>-windows64.with-katago.installer.exe` |
| Windows x64、NVIDIA GPU、高速解析向け | `<date>-windows64.nvidia.installer.exe` |
| Windows x64、NVIDIA GPU、インストーラ不要 | `<date>-windows64.nvidia.portable.zip` |
| Windows x64、インストーラ不要 | `<date>-windows64.with-katago.portable.zip` |
| Windows x64、自分でエンジン設定、インストーラあり | `<date>-windows64.without.engine.installer.exe` |
| Windows x64、自分でエンジン設定 | `<date>-windows64.without.engine.portable.zip` |
| macOS Apple Silicon | `<date>-mac-arm64.with-katago.dmg` |
| macOS Intel | `<date>-mac-amd64.with-katago.dmg` |
| Linux x64 | `<date>-linux64.with-katago.zip` |

## Windows

1. `windows64.with-katago.installer.exe` をダウンロードします。
2. インストーラを実行します。
3. インストール完了後、`LizzieYzy Next.exe` を起動します。

NVIDIA GPU があり、速度を優先したい場合:

1. `windows64.nvidia.installer.exe` をダウンロードします。
2. インストーラを実行します。
3. インストール完了後、`LizzieYzy Next NVIDIA.exe` を起動します。

インストーラ不要なら:

1. `windows64.nvidia.portable.zip` を選びます。
2. 展開して `LizzieYzy Next NVIDIA.exe` を起動します。

NVIDIA GPU かどうかわからない場合は、通常の `windows64.with-katago.installer.exe` を選んでください。

自分のエンジンを使いたい場合:

1. `windows64.without.engine.installer.exe` または `windows64.without.engine.portable.zip` を選びます。
2. インストーラ版ならそのままセットアップし、portable 版なら展開して `LizzieYzy Next.exe` を起動します。
3. 起動後に自分のエンジンを設定します。

## macOS

1. Apple Silicon か Intel かを確認します。
2. 対応する `.dmg` を開きます。
3. `LizzieYzy Next.app` を `Applications` にドラッグします。
4. 初回にブロックされた場合は、`システム設定 -> プライバシーとセキュリティ` から `このまま開く` を選びます。

## Linux

```bash
chmod +x start-linux64.sh
./start-linux64.sh
```

## 初回起動で自動で行うこと

- 内蔵 KataGo の検出
- 既定の重みの確認
- 利用可能な既定設定の作成

## 野狐棋譜の取得

1. アプリを起動します。
2. **野狐棋譜（ニックネームで取得）** を開きます。
3. 野狐のニックネームを入力します。
4. アプリが対応するアカウントを見つけて、最近の公開棋譜を取得します。

ニックネームが違う場合や公開棋譜がない場合は、結果が空になることがあります。
