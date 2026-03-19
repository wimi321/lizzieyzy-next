<p align="center">
  <img src="assets/hero.svg" alt="LizzieYzy Next-FoxUID" width="100%" />
</p>

<p align="center">
  <a href="README.md">中文</a> · <a href="README_EN.md">English</a> · 日本語 · <a href="README_KO.md">한국어</a>
</p>

# LizzieYzy Next-FoxUID

**野狐棋譜同期を修復し、より使いやすい配布構成に整えた、継続メンテ中の LizzieYzy フォークです。**

> [!IMPORTANT]
> 元の LizzieYzy では野狐棋譜同期が実質的に使いにくい状態でした。このフォークではそれを修復し、入力方式も野狐IDに統一しています。

## このフォークの目的

元の LizzieYzy は今でも魅力がありますが、野狐棋譜同期のような重要機能がそのままでは使いにくくなっていました。このフォークは、壊れたまま残すのではなく、日常的に使える状態へ戻すことを目的にしています。

主な変更点:

- **野狐棋譜同期を修復**
- **入力方式を野狐IDに統一**
- **Windows / macOS / Linux の配布を整理**
- **継続メンテ前提の分岐として運用**

## スクリーンショット

![LizzieYzy Next-FoxUID Screenshot](screenshot_en.png)

## ダウンロードの選び方

| システム | 推奨パッケージ | Java | KataGo |
| --- | --- | --- | --- |
| Windows x64 | `windows64.with-katago.zip` | 同梱 | 同梱 |
| Windows x64 | `windows64.without.engine.zip` | 同梱 | なし |
| macOS Apple Silicon | `mac-arm64.with-katago.dmg` | App 内蔵 | 同梱 |
| macOS Intel | `mac-amd64.with-katago.dmg` | App 内蔵 | 同梱 |
| Linux x64 | `linux64.with-katago.zip` | 同梱 | 同梱 |

Releases: <https://github.com/wimi321/lizzieyzy-next-foxuid/releases>

## クイックスタート

1. Releases から自分の環境に合うパッケージをダウンロードします。
2. 簡単に始めたい場合は `with-katago` を選びます。
3. アプリを開き、野狐IDを入力して最新の公開棋譜を取得します。

Detailed install guide: [Installation Guide](docs/INSTALL_EN.md)

## 同梱情報

- KataGo: `v1.16.4`
- 既定の重み: `g170e-b20c256x2-s5303129600-d1228401921.bin.gz`
- macOS 重みファイル: `LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`

## 参加方法

- [Contributing Guide](CONTRIBUTING.md)
- [Code Of Conduct](CODE_OF_CONDUCT.md)
- [Security Policy](SECURITY.md)
- [Issues](https://github.com/wimi321/lizzieyzy-next-foxuid/issues)
- [Discussions](https://github.com/wimi321/lizzieyzy-next-foxuid/discussions)
