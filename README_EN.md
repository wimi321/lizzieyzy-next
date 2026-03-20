<p align="center">
  <img src="assets/hero.svg" alt="LizzieYzy Next-FoxUID" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next-foxuid?display_name=tag&label=Release&color=1B4D3E" alt="Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/actions/workflows/ci.yml"><img src="https://github.com/wimi321/lizzieyzy-next-foxuid/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/stargazers"><img src="https://img.shields.io/github/stars/wimi321/lizzieyzy-next-foxuid?style=flat&color=7F4F24" alt="Stars"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><img src="https://img.shields.io/github/downloads/wimi321/lizzieyzy-next-foxuid/total?label=Downloads&color=2F4858" alt="Downloads"></a>
  <a href="LICENSE.txt"><img src="https://img.shields.io/badge/License-GPL%20v3-E7A23B" alt="License"></a>
</p>

<p align="center">
  <a href="README.md">中文</a> · English · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a>
</p>

# LizzieYzy Next-FoxUID

**An actively maintained LizzieYzy fork focused on restoring broken Fox sync, standardizing the workflow around Fox ID, and shipping clearer multi-platform KataGo releases.**

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases">Download Releases</a>
  ·
  <a href="#if-you-just-want-to-start">Start Here</a>
  ·
  <a href="#which-package-to-download">Which Package</a>
  ·
  <a href="#quick-start">Quick Start</a>
  ·
  <a href="#docs-and-support">Docs & Support</a>
  ·
  <a href="#contributing">Contributing</a>
</p>

> [!IMPORTANT]
> The original Fox sync flow in LizzieYzy no longer worked reliably. This fork restores it and standardizes the UI around **Fox ID** input.

## Why People Look At This Fork First

`LizzieYzy Next-FoxUID` is a maintained fork of the original `lizzieyzy` project.

The goal is simple: keep the workflow people actually depend on in working shape, so you can install it, fetch games, and keep reviewing without rebuilding everything yourself.

<table>
<tr>
<td width="50%">
<strong>Fox sync works again</strong><br/>
The broken public-game fetch flow has been replaced with a working implementation that continues to fetch the latest visible games by Fox ID.
</td>
<td width="50%">
<strong>The wording is finally clear</strong><br/>
UI and docs now consistently say <strong>Fox ID</strong>, instead of mixing UID, usernames, and older terms.
</td>
</tr>
<tr>
<td width="50%">
<strong>Packages are easier to choose</strong><br/>
Windows, macOS, and Linux releases are reorganized around out-of-box use versus custom engine setup.
</td>
<td width="50%">
<strong>This is an ongoing fork</strong><br/>
The project keeps shipping packages, docs, and installation feedback fixes instead of stopping after a one-time patch.
</td>
</tr>
</table>

## If You Just Want To Start

| What you need right now | Go here |
| --- | --- |
| A package that works out of the box | Go to [Releases](https://github.com/wimi321/lizzieyzy-next-foxuid/releases) and choose a `with-katago` package |
| A package for custom engine setup | Read [Package Overview](docs/PACKAGES_EN.md) and choose a `without.engine` package |
| Real-world platform verification status | Read [Tested Platforms](docs/TESTED_PLATFORMS.md) |
| Install or first-launch help | Read [Installation Guide](docs/INSTALL_EN.md) and [Troubleshooting](docs/TROUBLESHOOTING_EN.md) |
| Report install success or failure | Use the [Installation Report template](https://github.com/wimi321/lizzieyzy-next-foxuid/issues/new?template=installation_report.yml) |
| Report a bug or suggest an improvement | Use [Issues](https://github.com/wimi321/lizzieyzy-next-foxuid/issues) or [Discussions](https://github.com/wimi321/lizzieyzy-next-foxuid/discussions) |

## Screenshot

The current UI now presents the old confusing UID entry as a clearer Fox sync entry based on **Fox ID**, while keeping the familiar LizzieYzy review workflow around it.

![LizzieYzy Next-FoxUID Screenshot](screenshot_en.png)

## Which Package To Download

> [!TIP]
> For most users, the right choice is simple: pick a `with-katago` package. Only choose `without.engine` if you already know you want to manage the engine yourself.

<p align="center">
  <img src="assets/package-guide.svg" alt="LizzieYzy Next-FoxUID Package Guide" width="100%" />
</p>

| Platform | Recommended package | Bundled Java | Bundled KataGo | Best for |
| --- | --- | --- | --- | --- |
| Windows x64 | `windows64.with-katago.zip` | Yes | Yes | Download and use directly |
| Windows x64 | `windows64.without.engine.zip` | Yes | No | Custom engine setups |
| Windows x86 | `windows32.without.engine.zip` | No | No | Legacy or compatibility cases |
| macOS Apple Silicon | `mac-arm64.with-katago.dmg` | App runtime | Yes | M-series Mac users |
| macOS Intel | `mac-amd64.with-katago.dmg` | App runtime | Yes | Intel Mac users |
| Linux x64 | `linux64.with-katago.zip` | Yes | Yes | Linux desktop users |
| Advanced users | `Macosx.amd64.Linux.amd64.without.engine.zip` | No | No | Fully custom engine setups |

Related links:

- [Releases](https://github.com/wimi321/lizzieyzy-next-foxuid/releases)
- [Package Overview](docs/PACKAGES_EN.md)
- [Tested Platforms](docs/TESTED_PLATFORMS.md)

## Quick Start

1. Download the package for your system from the [Releases](https://github.com/wimi321/lizzieyzy-next-foxuid/releases) page.
2. If you want the shortest path, choose a `with-katago` package.
3. Launch the app and open the Fox sync entry.
4. Enter a numeric **Fox ID** to fetch the latest public Fox games.
5. If your OS blocks the first launch, check the [Installation Guide](docs/INSTALL_EN.md) and [Troubleshooting](docs/TROUBLESHOOTING_EN.md).

## What You Can Do With It

| Use case | Available capability |
| --- | --- |
| Fetch games | Pull the latest public Fox games by Fox ID |
| Review a game | Hawk Eye charts, winrate swings, score swings, mistake tracking |
| Fast analysis | Parallel whole-game analysis with KataGo analysis mode |
| Batch workflows | Analyze multiple SGFs in one run |
| Engine comparison | Dual-engine mode and engine-vs-engine matches |
| Local study | Tsumego and local-shape analysis helpers |
| Other retained workflows | Board sync, rough territory judgment, and other commonly used original features |

## Compared With The Original Project

| Topic | Original LizzieYzy | Next-FoxUID |
| --- | --- | --- |
| Fox sync | Broken for many users | Restored |
| Input naming | Mixed UID / username wording | Fox ID only |
| Release layout | Harder to choose from | Reorganized by platform and use case |
| macOS distribution | More confusing historical package mix | `.dmg` first, separated by Apple Silicon / Intel |
| Windows x64 strategy | Less clear package split | Keeps both `with-katago` and `without.engine` |
| Maintenance | Mostly inactive | Actively maintained as a fork |

## If You Are Migrating From The Original Project

- The Fox sync entry is now consistently described as a Fox ID based flow
- Username lookup is no longer part of the supported path
- Windows x64 still keeps both `with-katago` and `without.engine`
- macOS releases are centered on `.dmg` installers instead of extra `.app.zip` assets
- This repository is intended for continued maintenance, not a temporary hotfix drop

## Bundled Engine Details

| Item | Current value |
| --- | --- |
| KataGo version | `v1.16.4` |
| Default bundled weight | `g170e-b20c256x2-s5303129600-d1228401921.bin.gz` |

Paths:

- Windows / Linux weight path: `Lizzieyzy/weights/default.bin.gz`
- macOS weight path: `LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`
- On macOS, if Finder only shows a single `.app`, that is expected. Use “Show Package Contents” to inspect the bundled files.

## Docs And Support

| If you need | Go here |
| --- | --- |
| Installation help | [Installation Guide](docs/INSTALL_EN.md) |
| Startup or runtime troubleshooting | [Troubleshooting](docs/TROUBLESHOOTING_EN.md) |
| Package explanations | [Package Overview](docs/PACKAGES_EN.md) |
| Real-machine platform status | [Tested Platforms](docs/TESTED_PLATFORMS.md) |
| Support and routing | [Support](SUPPORT.md) |
| Build from source or contribute code | [Development Guide](docs/DEVELOPMENT_EN.md) |
| Change history | [Changelog](CHANGELOG.md) |
| Maintenance policy | [Maintenance Notes](docs/MAINTENANCE_EN.md) |
| Release self-check | [Release Checklist](docs/RELEASE_CHECKLIST.md) |

## FAQ

<details>
<summary><strong>Why remove username lookup?</strong></summary>

Because it caused confusion and was harder to maintain. Fox ID is clearer for users and easier to debug.
</details>

<details>
<summary><strong>Why keep dmg files but remove macOS app.zip from releases?</strong></summary>

Because most users want a direct installer workflow, not multiple macOS package formats that look similar.
</details>

<details>
<summary><strong>Why keep both with-katago and without.engine for Windows x64?</strong></summary>

Because these serve two different audiences: one wants a bundled setup, the other wants to manage engines manually.
</details>

<details>
<summary><strong>What is the relationship between this fork and the original author?</strong></summary>

This is a maintained fork built on the original project. The goal is not to replace the original author, but to continue maintaining the broken workflows and release experience after the original project became inactive.
</details>

<details>
<summary><strong>What if my platform does not have a real-machine verification record yet?</strong></summary>

Check [Tested Platforms](docs/TESTED_PLATFORMS.md) first, then follow the [Installation Guide](docs/INSTALL_EN.md). If you can report either a successful install or a failure, that directly improves the project.
</details>

## Roadmap

- [x] Restore Fox sync
- [x] Switch the user-facing flow to Fox ID
- [x] Bring back multi-platform release packages
- [x] Restore Intel Mac packaging
- [x] Reorganize Windows / macOS / Linux download strategy
- [x] Add installation and troubleshooting docs
- [x] Unify Fox ID terminology across UI and docs
- [x] Add CI and markdown link checks
- [ ] Add more real-machine installation verification records
- [ ] Improve screenshots and homepage presentation further
- [ ] Finish more complete Japanese / Korean troubleshooting docs
- [ ] Keep reducing first-run friction based on real user feedback

## Contributing

Contributions are welcome through issues, discussions, and pull requests.

The most helpful contributions right now are:

- real installation reports for Windows, Linux, and Intel Mac
- Fox sync compatibility reports
- documentation, translation, and UI wording improvements
- packaging, engine-path, and release-process fixes
- focused small code fixes

Links:

- [Contributing Guide](CONTRIBUTING.md)
- [Code Of Conduct](CODE_OF_CONDUCT.md)
- [Security Policy](SECURITY.md)
- [Support](SUPPORT.md)
- [Tested Platforms](docs/TESTED_PLATFORMS.md)
- [Issues](https://github.com/wimi321/lizzieyzy-next-foxuid/issues)
- [Discussions](https://github.com/wimi321/lizzieyzy-next-foxuid/discussions)

## Credits

- Original project: [yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- Upstream GUI base: [featurecat/lizzie](https://github.com/featurecat/lizzie)
- Engine: [lightvector/KataGo](https://github.com/lightvector/KataGo)

## License

This project keeps the original license. See [LICENSE.txt](LICENSE.txt).
