# インストールガイド

このガイドは、`LizzieYzy Next-FoxUID` をできるだけ早く使い始めたい人向けです。

## まずはパッケージを選ぶ

| 環境 | 推奨パッケージ | Java | KataGo |
| --- | --- | --- | --- |
| Windows x64 | `<date>-windows64.with-katago.installer.exe` | 同梱 | 同梱 |
| Windows x64 | `<date>-windows64.with-katago.portable.zip` | 同梱 | 同梱 |
| Windows x64 | `<date>-windows64.without.engine.portable.zip` | 同梱 | なし |
| Windows x86 | `<date>-windows32.without.engine.zip` | なし | なし |
| macOS Apple Silicon | `<date>-mac-arm64.with-katago.dmg` | App 内蔵 | 同梱 |
| macOS Intel | `<date>-mac-amd64.with-katago.dmg` | App 内蔵 | 同梱 |
| Linux x64 | `<date>-linux64.with-katago.zip` | 同梱 | 同梱 |

簡単に使い始めたい場合は `with-katago` を選んでください。Windows では通常、`installer.exe` が最も分かりやすい選択です。

## Windows

### Windows x64 インストーラ

1. `windows64.with-katago.installer.exe` をダウンロードします。
2. ダブルクリックしてインストーラを実行します。
3. セットアップウィザードに従ってインストールします。
4. インストール後、スタートメニューまたはデスクトップから起動します。

### Windows x64 ポータブル版

1. `windows64.with-katago.portable.zip` をダウンロードします。
2. 普通のフォルダに展開します。
3. 展開先で `LizzieYzy Next-FoxUID.exe` を実行します。

### Windows x64 無引擎版

1. `windows64.without.engine.portable.zip` をダウンロードします。
2. 展開後、`LizzieYzy Next-FoxUID.exe` を実行します。
3. このパッケージには KataGo は含まれていないため、起動後に自分のエンジンを設定します。

## macOS

1. 自分のチップに合う dmg を選びます。
   - Apple Silicon: `mac-arm64.with-katago.dmg`
   - Intel: `mac-amd64.with-katago.dmg`
2. dmg を開いてアプリを `Applications` にドラッグします。
3. `Applications` から起動します。

現在の macOS パッケージは未署名 / 未公証です。最初の起動時にブロックされた場合は：

1. 一度開こうとします。
2. `システム設定 -> プライバシーとセキュリティ` を開きます。
3. `このまま開く` を選びます。
4. もう一度起動します。

## Linux

1. `linux64.with-katago.zip` をダウンロードします。
2. 展開します。
3. ターミナルから起動します。

```bash
chmod +x start-linux64.sh
./start-linux64.sh
```

## 初回起動について

現在のメンテナンス版では、初回起動時に次の処理を優先して行います。

- 同梱 KataGo と重みファイルの検出
- 使える既定エンジン設定の自動作成
- 必要に応じて推奨公式重みの案内

通常の `with-katago` パッケージであれば、手動設定なしで始められるケースが増えています。

## 野狐棋譜の取得

起動後は、野狐棋譜の入口から **数字のみの Fox ID** を入力してください。

- ユーザー名検索は現在サポートしていません。
- 最新の公開棋譜を取得する方式に統一しています。

## 関連ドキュメント

- [Installation Guide (English)](INSTALL_EN.md)
- [Troubleshooting (English)](TROUBLESHOOTING_EN.md)
- [Package Overview (English)](PACKAGES_EN.md)
- [Development Guide (English)](DEVELOPMENT_EN.md)
- [Tested Platforms](TESTED_PLATFORMS.md)
- [Support](../SUPPORT.md)
