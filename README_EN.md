<p align="center">
  <img src="assets/hero-english.svg" alt="LizzieYzy Next" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next?display_name=tag&label=Latest%20Release&color=111111" alt="Latest Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/wimi321/lizzieyzy-next/ci.yml?branch=main&label=CI&color=1f6feb" alt="CI"></a>
  <a href="LICENSE.txt"><img src="https://img.shields.io/github/license/wimi321/lizzieyzy-next?color=5b5b5b" alt="License"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-6b7280" alt="Platforms">
</p>

<p align="center">
  <a href="README.md">简体中文</a> · <a href="README_ZH_TW.md">繁體中文</a> · English · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a> · <a href="README_TH.md">ภาษาไทย</a>
</p>

<p align="center">
  <strong>LizzieYzy Next is a maintained KataGo desktop review GUI and the active continuation of <code>lizzieyzy 2.5.3</code>.</strong><br/>
  It focuses on the parts that matter most to real users: clearer package choice, smoother first launch, practical Fox fetching, bundled Windows board sync, and a faster path into whole-game review.
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><strong>Download Stable Release</strong></a>
  ·
  <a href="docs/INSTALL_EN.md"><strong>Installation Guide</strong></a>
  ·
  <a href="docs/PACKAGES_EN.md"><strong>Package Guide</strong></a>
  ·
  <a href="docs/TROUBLESHOOTING_EN.md"><strong>Troubleshooting</strong></a>
  ·
  <a href="https://github.com/wimi321/lizzieyzy-next/discussions"><strong>Discussions</strong></a>
</p>

| Project Status | Current Value |
| --- | --- |
| User-facing version line | `LizzieYzy Next 1.0.0` |
| Upstream base | `lizzieyzy 2.5.3` |
| Default engine | `KataGo v1.16.4` |
| Default weight | `kata1-zhizi-b28c512nbt-muonfd2.bin.gz` |
| Official download source | GitHub Releases |

> [!IMPORTANT]
> The official public download source is now GitHub Releases only.
> Standard Windows releases bundle native `readboard.exe`; the app falls back to `readboard_java` only when the native helper is missing or cannot start.

## Why This Project Matters

- It is not a one-off patch branch. It is the maintained public continuation of the practical `lizzieyzy` workflow.
- It does not stop at source changes. Packages, first-launch experience, release pages, install docs, and regression checks are maintained together.
- It prioritizes real usage: fetching games, reviewing SGF files, reading winrate trends, whole-game analysis, and reliable Windows startup and sync.

## Current Core Capabilities

| What you want | What the project does now |
| --- | --- |
| Start quickly after download | Windows, macOS, and Linux all ship public integrated packages so most users do not need to assemble an environment first |
| Fetch recent public Fox games | Enter a Fox nickname and let the app resolve the account automatically |
| Run Smart Optimize | Uses a KataGo benchmark-based flow with visible progress, cancel support, and pause/resume around active analysis |
| Use board sync on Windows | Standard releases prefer native `readboard.exe` and fall back to the Java helper only when needed |
| Stay interactive while loading kifu | Local SGF and Fox loading return control early so navigation can continue while winrate details catch up |
| Install on macOS | Official DMG releases go through signing and notarization in the release pipeline |

## Which Package To Download

All public downloads are on [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases). If you only want the right package fast, this is enough.

<p align="center">
  <img src="assets/package-guide.svg" alt="LizzieYzy Next package guide" width="100%" />
</p>

| Your situation | Find the file that contains this keyword on Releases |
| --- | --- |
| Most Windows users, default recommendation | `*windows64.opencl.portable.zip` |
| Windows, OpenCL unstable, CPU fallback | `*windows64.with-katago.portable.zip` |
| Windows, NVIDIA GPU, want more speed | `*windows64.nvidia.portable.zip` |
| Windows, bring your own engine | `*windows64.without.engine.portable.zip` |
| macOS Apple Silicon | `*mac-arm64.with-katago.dmg` |
| macOS Intel | `*mac-amd64.with-katago.dmg` |
| Linux | `*linux64.with-katago.zip` |

Notes:

- Windows users who prefer installation can choose the matching `*.installer.exe` package.
- For the full 11 public assets and package contents, see [docs/PACKAGES_EN.md](docs/PACKAGES_EN.md).
- Standard Windows releases already bundle the native board-sync helper.

## Current Public Highlights

- `Fox nickname fetch`
  Enter the Fox nickname directly instead of treating the numeric account ID as a normal prerequisite.
- `KataGo Auto Setup`
  Main bundles ship `KataGo v1.16.4` and the default weight, and Smart Optimize follows the benchmark-based tuning flow with cancel support.
- `Stronger Windows board sync path`
  Release packages now ship `readboard.exe` and its dependencies, with Java fallback only when needed.
- `More direct kifu loading interaction`
  After download completes, the main window becomes usable first, while winrate details continue to fill in.
- `Release discipline closer to a real desktop project`
  Cross-platform packaging, CI, release notes, and install docs are maintained as part of the product.

## Quick Start

1. Download the package that matches your system from [Releases](https://github.com/wimi321/lizzieyzy-next/releases).
2. If you are using a Windows bundle with built-in KataGo, open `KataGo Auto Setup` once and run `Smart Optimize`.
3. Open a local SGF, or fetch recent public games from the Fox nickname workflow.
4. Use the graph, `Down`, and keyboard navigation to move quickly through key moments while the rest of the review data fills in.

<p align="center">
  <a href="assets/fox-id-demo.gif">
    <img src="assets/fox-id-demo-cover.png" alt="LizzieYzy Next Fox nickname demo" width="100%" />
  </a>
</p>

## Interface Preview

<p align="center">
  <img src="assets/interface-overview-2026-04.png" alt="LizzieYzy Next interface preview" width="100%" />
</p>

<p align="center">
  <img src="assets/winrate-quick-overview-2026-04.png" alt="LizzieYzy Next winrate graph and quick overview" width="52%" />
</p>

You can read the current interface as three layers of information:

- Board area: current position, suggestions, and local reading.
- Winrate graph: whole-game trend and major turning points.
- Bottom quick overview: where to jump first before checking every move in detail.

## Docs And Community

- [Installation Guide](docs/INSTALL_EN.md)
- [Package Guide](docs/PACKAGES_EN.md)
- [Troubleshooting](docs/TROUBLESHOOTING_EN.md)
- [Tested Platforms](docs/TESTED_PLATFORMS.md)
- [Changelog](CHANGELOG.md)
- [Roadmap](ROADMAP.md)
- [Contributing](CONTRIBUTING.md)
- [Support](SUPPORT.md)
- [GitHub Discussions](https://github.com/wimi321/lizzieyzy-next/discussions)
- Chinese QQ group: `299419120`

## Build From Source

Requirements:

- JDK 17
- Maven 3.9+

Build commands:

```bash
mvn test
mvn -DskipTests package
java -jar target/lizzie-yzy2.5.3-shaded.jar
```

If you plan to maintain packaging, releases, or automation, also see:

- [docs/DEVELOPMENT_EN.md](docs/DEVELOPMENT_EN.md)
- [docs/MAINTENANCE_EN.md](docs/MAINTENANCE_EN.md)
- [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)

## Credits

- Original project: [yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- KataGo: [lightvector/KataGo](https://github.com/lightvector/KataGo)
- Historical Fox sync references: [yzyray/FoxRequest](https://github.com/yzyray/FoxRequest), [FuckUbuntu/Lizzieyzy-Helper](https://github.com/FuckUbuntu/Lizzieyzy-Helper)

## License

This project is released under [GPL-3.0](LICENSE.txt).
