<p align="center">
  <img src="assets/hero.svg" alt="LizzieYzy Next-FoxUID" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next-foxuid?display_name=tag&label=Release&color=111111" alt="Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/stargazers"><img src="https://img.shields.io/github/stars/wimi321/lizzieyzy-next-foxuid?style=flat&color=444444" alt="Stars"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><img src="https://img.shields.io/github/downloads/wimi321/lizzieyzy-next-foxuid/total?label=Downloads&color=666666" alt="Downloads"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-888888" alt="Platforms">
</p>

<p align="center">
  <a href="README.md">中文</a> · English · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a>
</p>

<p align="center">
  <strong>Make LizzieYzy useful again.</strong><br/>
  After years without maintenance, the original project stopped being practical for many Fox users. This maintained fork restores the game-fetching path first, then makes installation, first launch, and bundled KataGo much easier.<br/>
  <strong>Download it, enter a Fox nickname, and keep reviewing.</strong>
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><strong>Download Releases</strong></a>
  ·
  <a href="docs/INSTALL_EN.md"><strong>Installation Guide</strong></a>
  ·
  <a href="docs/TROUBLESHOOTING_EN.md"><strong>Troubleshooting</strong></a>
</p>

> [!IMPORTANT]
> Start with these 3 points:
> - Most Windows users should download `windows64.with-katago.installer.exe`
> - You can now enter a **Fox nickname** directly. The app resolves the account and fetches recent public games automatically.
> - First launch now tries to prepare the bundled analysis setup for you.

## Most Users Start Here

If you are on **Windows**, download:

**`windows64.with-katago.installer.exe`**

This is the main beginner-friendly build: download it, double-click it, install it, open it, start reviewing.

## What To Download

| Your computer | Download this |
| --- | --- |
| Windows x64 | `windows64.with-katago.installer.exe` |
| Windows x64, no installer | `windows64.with-katago.portable.zip` |
| Windows x64, your own engine | `windows64.without.engine.portable.zip` |
| macOS Apple Silicon | `mac-arm64.with-katago.dmg` |
| macOS Intel | `mac-amd64.with-katago.dmg` |
| Linux x64 | `linux64.with-katago.zip` |

Quick rule:

- Windows: if you are not sure, choose `installer.exe`
- Mac: choose Apple Silicon or Intel first
- Linux: choose `with-katago.zip`

## Why Many Users Start Here

- **It fixes the problem that matters most first**
  Many users could no longer fetch Fox games at all. This fork restores that common workflow before anything else.
- **It removes the step normal users usually get stuck on**
  You can type the Fox nickname you already know. The app resolves the account for you instead of asking for the numeric ID first.
- **It makes the first run far less annoying**
  The main bundled packages already include KataGo and a default weight, so most users can get moving much faster.

## Start In 3 Steps

1. Download the right package from [Releases](https://github.com/wimi321/lizzieyzy-next-foxuid/releases).
2. Open **Fox Kifu (search by nickname)**.
3. Enter a Fox nickname, fetch the latest visible public games, and continue reviewing.

<p align="center">
  <a href="assets/fox-id-demo.gif">
    <img src="assets/fox-id-demo-cover.png" alt="LizzieYzy Next-FoxUID Fox nickname demo" width="100%" />
  </a>
</p>

<p align="center">
  If GitHub delays GIF playback, click the image above to open the full animation.
</p>

## Actual Interface

This is the current maintained build interface, not an old historical screenshot. The bottom toolbar now exposes practical entries such as **Fox Kifu** and **Update Official Weight** directly.

<p align="center">
  <img src="assets/interface-overview.png" alt="LizzieYzy Next-FoxUID actual interface" width="100%" />
</p>

## What The Bundled Packages Already Include

| Item | Current value |
| --- | --- |
| KataGo version | `v1.16.4` |
| Default weight | `g170e-b20c256x2-s5303129600-d1228401921.bin.gz` |
| First-launch auto setup | Enabled |
| Official weight download entry | Included |

For most people, the main takeaway is simple:

**The bundled packages already include KataGo and a default weight, so you can usually open the app and start reviewing right away.**

## FAQ

<details>
<summary><strong>Do I need the Fox account number first?</strong></summary>

No. Enter the Fox nickname you already know. The app resolves the matching account automatically, and the game list still shows both nickname and account number so you can confirm the result.
</details>

<details>
<summary><strong>Why switch to nickname input?</strong></summary>

Because normal users usually know the nickname, not the account number. This maintained fork moves the lookup step into the app so the workflow feels natural again.
</details>

<details>
<summary><strong>What should I check if no games are found?</strong></summary>

Check whether the nickname is correct, whether that account has recent public games, and whether there is a temporary network issue. Empty results are normal when the account has no visible public games.
</details>

<details>
<summary><strong>Do I still need to configure KataGo by hand?</strong></summary>

Most `with-katago` users do not. The app now tries to detect bundled KataGo, bundled weights, and the right config path automatically.
</details>

<details>
<summary><strong>What if macOS blocks the app on first launch?</strong></summary>

Current macOS builds are not signed or notarized yet. If macOS blocks the app the first time, follow the steps in the [Installation Guide](docs/INSTALL_EN.md).
</details>

## More Docs

- [Installation Guide](docs/INSTALL_EN.md)
- [Package Overview](docs/PACKAGES_EN.md)
- [Troubleshooting](docs/TROUBLESHOOTING_EN.md)
- [Tested Platforms](docs/TESTED_PLATFORMS.md)
- [Changelog](CHANGELOG.md)
- [Support](SUPPORT.md)

## Credits

- Original project: [yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- KataGo: [lightvector/KataGo](https://github.com/lightvector/KataGo)
- Historical Fox sync references:
  - [yzyray/FoxRequest](https://github.com/yzyray/FoxRequest)
  - [FuckUbuntu/Lizzieyzy-Helper](https://github.com/FuckUbuntu/Lizzieyzy-Helper)
