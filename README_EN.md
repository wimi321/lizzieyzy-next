<p align="center">
  <img src="assets/hero.svg" alt="LizzieYzy Next-FoxUID" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next-foxuid?display_name=tag&label=Release&color=1B4D3E" alt="Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/actions/workflows/ci.yml"><img src="https://github.com/wimi321/lizzieyzy-next-foxuid/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/stargazers"><img src="https://img.shields.io/github/stars/wimi321/lizzieyzy-next-foxuid?style=flat&color=7F4F24" alt="Stars"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><img src="https://img.shields.io/github/downloads/wimi321/lizzieyzy-next-foxuid/total?label=Downloads&color=2F4858" alt="Downloads"></a>
  <a href="LICENSE.txt"><img src="https://img.shields.io/badge/License-GPL%20v3-E7A23B" alt="License"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-4A5D23" alt="Platforms">
</p>

<p align="center">
  <a href="README.md">中文</a> · English · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a>
</p>

<p align="center">
  <strong>A maintained LizzieYzy fork focused on making the broken Fox sync usable again.</strong><br/>
  The workflow now centers on <strong>Fox ID</strong>, with first-launch auto setup, bundled KataGo packages, and clearer multi-platform releases.
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases">Download Releases</a>
  ·
  <a href="#what-to-download-first">What To Download</a>
  ·
  <a href="#three-minute-setup">Three-Minute Setup</a>
  ·
  <a href="#release-assets">Release Assets</a>
  ·
  <a href="#docs-and-support">Docs & Support</a>
</p>

> [!IMPORTANT]
> This fork is not trying to reinvent LizzieYzy. It focuses on the practical path users still care about:
> - restore Fox kifu sync and standardize the UI around **Fox ID**
> - auto-configure bundled KataGo on first launch whenever possible
> - ship releases that are easier to choose, with Windows centered on `.installer.exe`

## What To Download First

| If you are on | Download this first | Best for |
| --- | --- | --- |
| Windows x64 and want the easiest path | `windows64.with-katago.installer.exe` | Double-click install, launch, start reviewing |
| Windows x64 and prefer no installer | `windows64.with-katago.portable.zip` | Unzip and run the packaged app directly |
| Windows x64 and want your own engine | `windows64.without.engine.portable.zip` | Keep the app runtime and configure KataGo yourself |
| macOS Apple Silicon | `mac-arm64.with-katago.dmg` | M-series Macs |
| macOS Intel | `mac-amd64.with-katago.dmg` | Intel Macs |
| Linux x64 | `linux64.with-katago.zip` | Fastest Linux desktop path |
| Legacy or advanced custom setup | `windows32.without.engine.zip` / `Macosx.amd64.Linux.amd64.without.engine.zip` | Only if you already know you need them |

> [!TIP]
> Older release tags may still show the previous Windows zip layout. Going forward, the primary Windows downloads are the installer build and the portable `.exe` package.

## Why This Fork Matters

If you used the original `lizzieyzy`, the real question is not whether the project has a new coat of paint. It is whether the core user path works again:

- can Fox kifu sync fetch public games again
- does first launch still force manual engine setup
- can you tell which release asset to download without guessing
- can you reinstall on another machine without rebuilding your setup from scratch

`LizzieYzy Next-FoxUID` exists to keep that path usable.

## Three-Minute Setup

1. Go to [Releases](https://github.com/wimi321/lizzieyzy-next-foxuid/releases) and choose the package for your system.
2. Windows users should start with `windows64.with-katago.installer.exe`; macOS users should pick the correct `.dmg`; Linux users should choose `linux64.with-katago.zip`.
3. On first launch, the app now tries to auto-detect bundled KataGo, configs, and the default weight.
4. Open **Fox Kifu (Fetch by Fox ID)** and enter a numeric Fox ID.
5. Fetch the latest public games and continue with bundled or custom KataGo review.

## What First Launch Does Now

The maintained fork no longer assumes new users want to configure engines by hand.

At startup, it now tries to:

- detect bundled KataGo binaries, configs, and bundled weight files
- write a usable default engine configuration automatically
- offer a guided path to download a recommended official weight if needed
- fall back to manual setup only when automatic setup still cannot produce a working configuration

That keeps the common case simple: install, open, fetch, review.

## Screenshot

![LizzieYzy Next-FoxUID Screenshot](screenshot_en.png)

## Release Assets

> [!TIP]
> For most users, the rule is simple: choose `with-katago` if you want the shortest path, and `without.engine` only if you want to manage the engine yourself.

| Platform | Recommended asset | Bundled Java | Bundled KataGo | Install style |
| --- | --- | --- | --- | --- |
| Windows x64 | `windows64.with-katago.installer.exe` | Yes | Yes | Installer with Start Menu and desktop shortcut |
| Windows x64 | `windows64.with-katago.portable.zip` | Yes | Yes | Portable app image, unzip and run `LizzieYzy Next-FoxUID.exe` |
| Windows x64 | `windows64.without.engine.portable.zip` | Yes | No | Portable app with manual engine setup |
| Windows x86 | `windows32.without.engine.zip` | No | No | Compatibility package |
| macOS Apple Silicon | `mac-arm64.with-katago.dmg` | App runtime | Yes | Drag to Applications |
| macOS Intel | `mac-amd64.with-katago.dmg` | App runtime | Yes | Drag to Applications |
| Linux x64 | `linux64.with-katago.zip` | Yes | Yes | Unzip and run `start-linux64.sh` |
| Advanced users | `Macosx.amd64.Linux.amd64.without.engine.zip` | No | No | Manual Java and engine setup |

A few design choices behind this layout:

- Windows now treats the installer as the primary user-facing package instead of `.bat` launchers.
- The Windows no-engine package also moves to a portable `.exe` flow instead of a bat-first flow.
- macOS stays centered on `.dmg` installers, one for Apple Silicon and one for Intel.
- Linux keeps a practical all-in-one package.

## Compared With The Original Project

| Topic | Original LizzieYzy | Next-FoxUID |
| --- | --- | --- |
| Fox sync | Broken for many users | Restored and maintained |
| Input wording | UID, username, and mixed labels | Fox ID only |
| First launch | Often required manual engine setup | Prefers automatic bundled setup |
| Windows experience | Mostly zip + bat based | Installer-first with portable `.exe` fallback |
| macOS packages | Historically confusing mix | `.dmg` first, split by Apple Silicon / Intel |
| Maintenance | Mostly inactive | Ongoing releases, docs, and support |

## Bundled Engine Details

| Item | Current value |
| --- | --- |
| KataGo version | `v1.16.4` |
| Default bundled weight | `g170e-b20c256x2-s5303129600-d1228401921.bin.gz` |
| First-launch auto setup | Enabled |
| Recommended weight download helper | Included |

Common paths:

- Windows / Linux bundles: `Lizzieyzy/weights/default.bin.gz`
- macOS bundles: `LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`
- macOS engine directory: `LizzieYzy Next-FoxUID.app/Contents/app/engines/katago/`

## Docs And Support

| If you need | Go here |
| --- | --- |
| Installation steps | [Installation Guide](docs/INSTALL_EN.md) |
| Package explanations | [Package Overview](docs/PACKAGES_EN.md) |
| Startup, engine, or Fox sync troubleshooting | [Troubleshooting](docs/TROUBLESHOOTING_EN.md) |
| Real-machine verification status | [Tested Platforms](docs/TESTED_PLATFORMS.md) |
| Release process guidance | [Release Checklist](docs/RELEASE_CHECKLIST.md) |
| Help routing | [Support](SUPPORT.md) |
| Change history | [Changelog](CHANGELOG.md) |

## FAQ

<details>
<summary><strong>Why remove username lookup?</strong></summary>

Because it was harder to debug, easier to misunderstand, and less reliable for maintenance. This fork standardizes the user path around Fox ID.
</details>

<details>
<summary><strong>Do I still need to configure the engine by hand on first launch?</strong></summary>

Most users should not. The maintained fork now tries to auto-configure the bundled engine first and only falls back to manual setup when necessary.
</details>

<details>
<summary><strong>Why make the Windows installer the main recommendation?</strong></summary>

Because regular users want a straightforward install flow: download, double-click, finish setup, and open the app. Portable builds still exist, but they are no longer the main path.
</details>

<details>
<summary><strong>Why can macOS still block first launch?</strong></summary>

Current maintenance builds are still unsigned and not notarized. That means Gatekeeper may block the first launch until you choose “Open Anyway” in macOS security settings.
</details>

## Contributing

The most helpful contributions right now are:

- real-machine Windows, Linux, and Intel Mac install reports
- Fox sync compatibility feedback
- better release-page copy, installation docs, and translations
- packaging, first-launch setup, and engine integration fixes

Links:

- [Contributing Guide](CONTRIBUTING.md)
- [Code Of Conduct](CODE_OF_CONDUCT.md)
- [Security Policy](SECURITY.md)
- [Issues](https://github.com/wimi321/lizzieyzy-next-foxuid/issues)
- [Discussions](https://github.com/wimi321/lizzieyzy-next-foxuid/discussions)

## Credits

- Original project: [yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- Engine: [lightvector/KataGo](https://github.com/lightvector/KataGo)
- Historical Fox sync references:
  - [yzyray/FoxRequest](https://github.com/yzyray/FoxRequest)
  - [FuckUbuntu/Lizzieyzy-Helper](https://github.com/FuckUbuntu/Lizzieyzy-Helper)
