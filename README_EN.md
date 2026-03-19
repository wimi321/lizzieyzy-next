<p align="center">
  <img src="assets/hero.svg" alt="LizzieYzy Next-FoxUID" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next-foxuid?display_name=tag&label=Release&color=1B4D3E" alt="Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/stargazers"><img src="https://img.shields.io/github/stars/wimi321/lizzieyzy-next-foxuid?style=flat&color=7F4F24" alt="Stars"></a>
  <a href="LICENSE.txt"><img src="https://img.shields.io/badge/License-GPL%20v3-E7A23B" alt="License"></a>
</p>

<p align="center">
  <a href="README.md">中文</a> · English · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a>
</p>

# LizzieYzy Next-FoxUID

**An actively maintained LizzieYzy fork that restores broken Fox Weiqi sync and ships practical KataGo-ready releases for Windows, macOS, and Linux.**

> [!IMPORTANT]
> The original Fox sync flow in LizzieYzy no longer worked reliably. This fork restores it and standardizes the UI around Fox ID input.

## Why This Fork Exists

The original LizzieYzy is still useful, but one of its most important flows no longer worked reliably: syncing Fox kifu. This fork focuses on making the project usable again, not just preserving history.

What changed:

- **Fox sync fixed** with a newer Fox H5 API based flow
- **Fox ID only** so users no longer need to guess whether to use a name or an internal UID
- **Cleaner releases** so people can quickly choose the right package
- **Ongoing maintenance** instead of a one-off patch

## Screenshot

![LizzieYzy Next-FoxUID Screenshot](screenshot_en.png)

## Download Guide

| Platform | Recommended package | Bundled Java | Bundled KataGo |
| --- | --- | --- | --- |
| Windows x64 | `windows64.with-katago.zip` | Yes | Yes |
| Windows x64 | `windows64.without.engine.zip` | Yes | No |
| Windows x86 | `windows32.without.engine.zip` | No | No |
| macOS Apple Silicon | `mac-arm64.with-katago.dmg` | App runtime | Yes |
| macOS Intel | `mac-amd64.with-katago.dmg` | App runtime | Yes |
| Linux x64 | `linux64.with-katago.zip` | Yes | Yes |
| Advanced users | `Macosx.amd64.Linux.amd64.without.engine.zip` | No | No |

Releases: <https://github.com/wimi321/lizzieyzy-next-foxuid/releases>

## Quick Start

1. Download the package for your system from the [Releases](https://github.com/wimi321/lizzieyzy-next-foxuid/releases) page.
2. If you want the easiest setup, choose a `with-katago` package.
3. Launch the app and use the Fox sync entry.
4. Enter a **Fox ID** to fetch the latest public Fox games.

## Detailed Docs

- [Installation Guide](docs/INSTALL_EN.md)
- [Troubleshooting](docs/TROUBLESHOOTING_EN.md)
- [Package Overview](docs/PACKAGES_EN.md)

## What Makes This Fork Different

| Topic | Original project | Next-FoxUID |
| --- | --- | --- |
| Fox sync | Broken for many users | Restored |
| Input mode | Old flow, confusing for users | Fox ID only |
| Release layout | Harder to pick the right file | Clearer per-system packages |
| Maintenance | Mostly inactive | Actively maintained |

## Bundled Engine Details

- Bundled KataGo version: `v1.16.4`
- Default bundled weight: `g170e-b20c256x2-s5303129600-d1228401921.bin.gz`
- Windows / Linux weight path: `Lizzieyzy/weights/default.bin.gz`
- macOS weight path: `LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`

## Feature Highlights

- Fox kifu sync by Fox ID
- Hawk Eye analysis
- Flash analysis with KataGo analysis mode
- Batch analysis
- Dual-engine comparison
- Tsumego analysis helpers
- Engine-vs-engine matches
- Board sync options retained from the original project

## FAQ

<details>
<summary><strong>Why remove username lookup?</strong></summary>

Because it caused confusion and was less reliable for maintenance. Fox ID is clearer for both users and debugging.
</details>

<details>
<summary><strong>Why keep dmg files but remove macOS app.zip from releases?</strong></summary>

Because most users want a direct installer workflow, not two macOS package formats that look similar.
</details>

## Contributing

If you want to help this fork grow into a stronger long-term project, contributions are welcome.

- [Contributing Guide](CONTRIBUTING.md)
- [Code Of Conduct](CODE_OF_CONDUCT.md)
- [Security Policy](SECURITY.md)
- [Issues](https://github.com/wimi321/lizzieyzy-next-foxuid/issues)
- [Discussions](https://github.com/wimi321/lizzieyzy-next-foxuid/discussions)

## Credits

- Original project: [yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- Upstream GUI base: [featurecat/lizzie](https://github.com/featurecat/lizzie)
- Engine: [lightvector/KataGo](https://github.com/lightvector/KataGo)
