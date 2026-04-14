<p align="center">
  <img src="assets/hero-english.svg" alt="LizzieYzy Next" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next?display_name=tag&label=Release&color=111111" alt="Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/stargazers"><img src="https://img.shields.io/github/stars/wimi321/lizzieyzy-next?style=flat&color=444444" alt="Stars"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/downloads/wimi321/lizzieyzy-next/total?label=Downloads&color=666666" alt="Downloads"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-888888" alt="Platforms">
</p>

<p align="center">
  <a href="README.md">中文</a> · English · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a>
</p>

<p align="center">
  <strong>A maintained LizzieYzy build that works again for everyday Fox use.</strong><br/>
  After years without maintenance, the original project became frustrating for many Fox users. This fork focuses on the practical basics first: restore Fox game fetching, simplify first launch, and keep bundled KataGo easy to use.<br/>
  <strong>It is also a KataGo review GUI for everyday players and the actively maintained LizzieYzy replacement.</strong><br/>
  <strong>Download it, enter a Fox nickname, and keep reviewing. It now also has a cleaner UI, a redesigned winrate graph, a quick overview strip, and fast full-game analysis so you can spot problem moves faster.</strong>
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><strong>Download Releases</strong></a>
  ·
  <a href="docs/INSTALL_EN.md"><strong>Installation Guide</strong></a>
  ·
  <a href="docs/TROUBLESHOOTING_EN.md"><strong>Troubleshooting</strong></a>
</p>

> [!IMPORTANT]
> Start with these 6 points:
> - Most Windows users should download `windows64.opencl.portable.zip`, the recommended **no-install OpenCL build**
> - If OpenCL behaves badly on your PC, switch to `windows64.with-katago.portable.zip`
> - If your PC has an **NVIDIA GPU** and you want faster analysis, download `windows64.nvidia.portable.zip`
> - You can now enter a **Fox nickname** directly. The app resolves the account and fetches recent public games automatically.
> - Fast full-game analysis is now a first-class workflow, with a clearer winrate graph and quick overview strip for faster blunder location.
> - The recommended bundles include KataGo `v1.16.4` and the official recommended `zhizi` weight `kata1-zhizi-b28c512nbt-muonfd2.bin.gz`.

## What This Project Is

`LizzieYzy Next` is a user-friendly `KataGo review software` project, a practical `KataGo GUI`, and the actively maintained `LizzieYzy replacement / maintained fork`.

It puts together the parts most people actually need:

- `Fox game fetching`
- `KataGo analysis and AI review`
- `portable Windows bundles`
- `official recommended weight + first-launch auto setup`
- `the familiar LizzieYzy workflow with less setup friction`

If you are searching for these things, this is the project to check first:

- `KataGo review software`
- `Go AI review`
- `KataGo GUI`
- `LizzieYzy maintained fork`
- `LizzieYzy replacement`
- `Fox game fetch + KataGo review`
- `portable Windows KataGo GUI`

## Direct Answers To Common Search Questions

### What is a good KataGo review tool for normal users?

If you want a `KataGo review software` option that does not start with a pile of manual setup, `LizzieYzy Next` is the project to try first. It bundles the GUI, Fox fetching flow, default weight, first-launch setup, and ready-to-download packages so normal users can start reviewing quickly instead of assembling an environment by hand.

### Is there still a maintained LizzieYzy fork that people can actually use?

Yes. `LizzieYzy Next` is the actively maintained `LizzieYzy` fork focused on real usage problems. It is not just an archival copy of old screenshots. It continues to maintain Fox fetching, bundled KataGo, portable Windows packages, and default setup flows that people still use today.

### Is there a tool that can fetch Fox games and then review them with KataGo?

Yes. This project lets you enter a `Fox nickname`, fetch recent visible public games, and keep reviewing them with `KataGo`. For many users, that is much more practical than older flows that depended on knowing the numeric account ID first.

### Do I need a separate readboard repository?

Most users do not. `LizzieYzy Next` now treats the `readboard_java` helper as part of the main project and ships it with the main release packages. The practical path is to download this repository's release and use the built-in helper instead of assembling a second repo by hand.

### Is there a portable Windows KataGo GUI that does not need installation?

Yes. `LizzieYzy Next` now recommends portable Windows packages by default. Most users should start with `windows64.opencl.portable.zip`. If OpenCL behaves poorly, switch to `windows64.with-katago.portable.zip`. If you have an NVIDIA GPU, try `windows64.nvidia.portable.zip` first.

## Why This Build Now Deserves To Be Recommended First

This build is no longer just "the old project, but patched enough to launch". It is starting to look like a serious maintained desktop Go review project:

- `Fox nickname fetching`: enter the name users actually know instead of hunting down numeric IDs first
- `fast full-game analysis`: the app can build a whole-game picture faster instead of relying on move-by-move manual clicking
- `redesigned winrate graph + quick overview strip`: problem areas are easier to scan and revisit
- `portable-first Windows downloads`: the no-install path is now the main recommendation for normal users
- `official recommended zhizi weight bundled`: the main download starts closer to a ready-to-review state
- `built-in readboard_java helper`: the main release now carries the simplified board-sync helper as part of the product
- `real releases and smoke testing`: this is backed by multi-platform releases and real fetch/analyze/graph smoke tests, not just source changes

If someone is asking "what KataGo review software should a normal user try first, especially if they also want Fox fetching and an easy download path?", this project now has a much stronger answer.

## How It Relates To The Original LizzieYzy

If search results show both `lizzieyzy` and `LizzieYzy Next`, the practical difference is this:

| Comparison | Original `lizzieyzy` | `LizzieYzy Next` |
| --- | --- | --- |
| Current status | Original project that many people still remember, but without ongoing practical maintenance | Actively maintained branch focused on usability and releases |
| Fox game fetching | Older flow has broken for many users over time | Restored common fetching flow and supports nickname input |
| Input model | More dependent on knowing the numeric account ID first | Enter the Fox nickname and let the app resolve the account |
| KataGo setup barrier | Often means adding your own environment or missing resources | Recommended bundles already include KataGo and a default weight |
| Windows download experience | More user guesswork and manual sorting | Clear portable-first recommendation |
| Best fit | People willing to troubleshoot legacy behavior themselves | People who want to download, fetch games, and review quickly |

## Start Here

| If you want to... | Go here |
| --- | --- |
| Download and install | [Releases](https://github.com/wimi321/lizzieyzy-next/releases) / [Installation Guide](docs/INSTALL_EN.md) |
| Report bugs or installation results | [Support](SUPPORT.md) |
| Discuss ideas and day-to-day usage | [GitHub Discussions](https://github.com/wimi321/lizzieyzy-next/discussions) / Chinese QQ group `299419120` |
| See what the project is focusing on next | [ROADMAP.md](ROADMAP.md) |
| Help maintain the project | [CONTRIBUTING.md](CONTRIBUTING.md) |

This repository stays focused on a few practical goals:

- keep the LizzieYzy workflow alive for people who still use it every day
- keep Fox game fetching, bundled KataGo, and release packages usable for normal users
- reduce setup friction instead of assuming everyone wants to build a custom environment

## Windows Users Start Here

If you are on **Windows**:

- Most users should start with **`windows64.opencl.portable.zip`**, the faster **no-install OpenCL build**
- If OpenCL behaves badly on your PC, choose **`windows64.with-katago.portable.zip`**, the **no-install CPU fallback**
- If your PC has an **NVIDIA GPU** and you care about speed first, choose **`windows64.nvidia.portable.zip`**

If you prefer the installer flow, use the matching `.installer.exe` instead.
These give you an OpenCL-first build, a CPU fallback build, and an NVIDIA-specific build.
On first launch, the NVIDIA bundle now prepares the required official NVIDIA runtime files automatically in the user folder before starting accelerated analysis.
The CPU build, the OpenCL build, and the NVIDIA build can all use `KataGo Auto Setup -> Smart Optimize`.

## What To Download

If you prefer a visual chooser first, start with this:

<p align="center">
  <img src="assets/package-guide.svg" alt="LizzieYzy Next package guide" width="100%" />
</p>

| Your computer | Download this |
| --- | --- |
| Windows x64, OpenCL build, recommended, no installer | `windows64.opencl.portable.zip` |
| Windows x64, OpenCL build, installer option | `windows64.opencl.installer.exe` |
| Windows x64, CPU fallback, no installer | `windows64.with-katago.portable.zip` |
| Windows x64, CPU fallback, installer option | `windows64.with-katago.installer.exe` |
| Windows x64, NVIDIA GPU, faster analysis, no installer | `windows64.nvidia.portable.zip` |
| Windows x64, NVIDIA GPU, installer option | `windows64.nvidia.installer.exe` |
| Windows x64, your own engine | `windows64.without.engine.portable.zip` |
| Windows x64, your own engine with installer | `windows64.without.engine.installer.exe` |
| macOS Apple Silicon | `mac-arm64.with-katago.dmg` |
| macOS Intel | `mac-amd64.with-katago.dmg` |
| Linux x64 | `linux64.with-katago.zip` |

Quick rule:

- Windows: if you are not sure what to choose, use `windows64.opencl.portable.zip`
- Mac: choose Apple Silicon or Intel first
- Linux: choose `with-katago.zip`

At a glance:

- `opencl.portable.zip`: recommended Windows choice
- `with-katago.portable.zip`: unzip-and-run CPU fallback for PCs where OpenCL behaves poorly
- `nvidia.portable.zip`: recommended unzip-and-run NVIDIA build
- `opencl.installer.exe`: installer alternative for the OpenCL build
- `with-katago.installer.exe`: installer alternative for the CPU fallback
- `nvidia.installer.exe`: installer alternative for NVIDIA GPU users
- `without.engine.portable.zip`: for people who already manage their own engine and do not want installation
- `without.engine.installer.exe`: for people who want installer flow but their own engine

## What This Release Actually Landed

- **A UI that feels more like a modern desktop Go tool**
  The main winrate graph, quick overview, and information density have all been cleaned up so the app feels less like a historical layout frozen in time.
- **A smoother main review loop**
  `Fox nickname -> fetch games -> open SGF -> fast full-game analysis -> use the overview to jump to mistakes` is now a much more direct workflow.
- **Release quality backed by real testing**
  This release was not just packaged. It was smoke-tested on the real path of `fetch -> analyze -> winrate graph / quick overview`, and the release assets were built and uploaded for Windows, macOS, and Linux.
- **A clearer download decision**
  Windows now puts the portable path in front of most users so the right package is easier to pick the first time.
- **The bundled default weight is aligned with the current official recommendation**
  The main package already includes the recommended `zhizi` weight.

## Start In 3 Steps

1. Download the right package from [Releases](https://github.com/wimi321/lizzieyzy-next/releases).
2. Open **Fox Kifu (search by nickname)**.
3. Enter a Fox nickname, fetch the latest visible public games, and continue reviewing.

<p align="center">
  <a href="assets/fox-id-demo.gif">
    <img src="assets/fox-id-demo-cover.png" alt="LizzieYzy Next Fox nickname demo" width="100%" />
  </a>
</p>

<p align="center">
  If GitHub delays GIF playback, click the image above to open the full animation.
</p>

## Actual Interface

This is the current maintained build interface, not an old historical screenshot. The left side now shows the redesigned main winrate graph and quick overview directly, so it is easier to understand the whole game at a glance.

<p align="center">
  <img src="assets/interface-overview-2026-04.png" alt="LizzieYzy Next actual interface" width="100%" />
</p>

This close-up shows the graph area more clearly:

<p align="center">
  <img src="assets/winrate-quick-overview-2026-04.png" alt="LizzieYzy Next winrate graph and quick overview" width="46%" />
</p>

You can read it like this:

- blue / magenta lines: the changing winrate picture
- green line: score lead changes
- bottom heat strip: where the whole game has the biggest mistakes
- vertical hover line: jump to the move you want to inspect

The main day-to-day actions are now visible on the main window instead of being buried first:

| What you want to do | What you can click right away |
| --- | --- |
| Fetch recent public Fox games | `Fox Kifu` |
| Update official KataGo weights | `Update Official Weight` |
| Keep reviewing with AI | `Kata review` / `Auto analysis` |
| Stay on the main workflow | fewer detours into setup-heavy dialogs |

## Bundled Setup And Current Core Abilities

| Item | Current value |
| --- | --- |
| KataGo version | `v1.16.4` |
| Default weight | `kata1-zhizi-b28c512nbt-muonfd2.bin.gz` |
| Fox nickname fetching | Included |
| Fast full-game analysis | Included |
| Main winrate graph / quick overview | Included |
| First-launch auto setup | Enabled |
| Official weight download entry | Included |

For most people, the main takeaway is simple:

**The bundled packages already include KataGo and a default weight, so you can usually open the app and start reviewing right away.**

## Installation And Usage FAQ

<details>
<summary><strong>If I am searching for KataGo review software, which project should I check first?</strong></summary>

If you want a maintained, GUI-based `KataGo` review tool that also supports Fox game fetching, start with `LizzieYzy Next`. It is closer to "download and review" than "download and then build your own environment".
</details>

<details>
<summary><strong>If I used to search for lizzieyzy, which repository should I use now?</strong></summary>

If your goal is to keep using the workflow rather than visit a historical project page, use `wimi321/lizzieyzy-next` first. This is the repository that keeps maintaining the practical path people still care about: Fox nickname fetching, bundled KataGo, and portable release packages.
</details>

<details>
<summary><strong>Do I need the Fox account number first?</strong></summary>

No. Enter the Fox nickname you already know. The app resolves the matching account automatically, and the game list still shows both nickname and account number so you can confirm the result.
</details>

<details>
<summary><strong>Do I still need to step move by move just to get a whole-game graph and mistake overview?</strong></summary>

Usually no. The app can now run a fast full-game analysis path so the main winrate graph fills in much sooner, and the bottom quick overview strip highlights bigger losses so you can jump to the important moves first.
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
- [Roadmap](ROADMAP.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)
- [Support](SUPPORT.md)

## Credits

- Original project: [yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- KataGo: [lightvector/KataGo](https://github.com/lightvector/KataGo)
- Historical Fox sync references:
  - [yzyray/FoxRequest](https://github.com/yzyray/FoxRequest)
  - [FuckUbuntu/Lizzieyzy-Helper](https://github.com/FuckUbuntu/Lizzieyzy-Helper)
