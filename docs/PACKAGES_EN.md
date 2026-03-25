# Package Overview

This document answers three practical questions:

1. which package types are currently recommended in public releases
2. what each package includes
3. which one a regular user should download first

## The 9 Primary Public Release Assets

| Package type | Typical filename | Best for |
| --- | --- | --- |
| Windows x64 installer | `<date>-windows64.with-katago.installer.exe` | Regular users who want the easiest path |
| Windows x64 NVIDIA installer | `<date>-windows64.nvidia.installer.exe` | NVIDIA GPU users who want higher analysis speed |
| Windows x64 NVIDIA portable | `<date>-windows64.nvidia.portable.zip` | NVIDIA GPU users who do not want an installer |
| Windows x64 bundled portable | `<date>-windows64.with-katago.portable.zip` | Users who do not want an installer |
| Windows x64 no-engine installer | `<date>-windows64.without.engine.installer.exe` | Users who want installer flow with their own engine |
| Windows x64 no-engine portable | `<date>-windows64.without.engine.portable.zip` | Custom KataGo setup |
| macOS Apple Silicon bundle | `<date>-mac-arm64.with-katago.dmg` | M-series Macs |
| macOS Intel bundle | `<date>-mac-amd64.with-katago.dmg` | Intel Macs |
| Linux x64 bundle | `<date>-linux64.with-katago.zip` | Linux desktop users |

Notes:

- `<date>` is the release date, for example `2026-03-21`.
- The maintained public release page now keeps these 9 user-facing assets as the main list.
- Windows x64 is installer-first, with a bundled portable build as the second choice.
- Older tags may still show compatibility zips, but those are now historical layouts.

## What Each Package Includes

| Package | Java | KataGo | Weight | How you start it |
| --- | --- | --- | --- | --- |
| `windows64.with-katago.installer.exe` | Bundled | Bundled | Bundled | Install, then launch from Start Menu or desktop |
| `windows64.nvidia.installer.exe` | Bundled | Bundled | Bundled | Install, then launch `LizzieYzy Next NVIDIA` |
| `windows64.nvidia.portable.zip` | Bundled | Bundled | Bundled | Unzip and run `LizzieYzy Next NVIDIA.exe` |
| `windows64.with-katago.portable.zip` | Bundled | Bundled | Bundled | Unzip and run `LizzieYzy Next.exe` |
| `windows64.without.engine.installer.exe` | Bundled | Not bundled | Not bundled | Install, then launch from Start Menu or desktop |
| `windows64.without.engine.portable.zip` | Bundled | Not bundled | Not bundled | Unzip and run `LizzieYzy Next.exe` |
| `mac-arm64.with-katago.dmg` | App runtime | Bundled | Bundled | Drag to Applications |
| `mac-amd64.with-katago.dmg` | App runtime | Bundled | Bundled | Drag to Applications |
| `linux64.with-katago.zip` | Bundled | Bundled | Bundled | Run `start-linux64.sh` |

## Simple Download Advice

If you just want the shortest path:

- Windows: choose `windows64.with-katago.installer.exe`
- Windows with an NVIDIA GPU: choose `windows64.nvidia.installer.exe`
- macOS: choose the correct `with-katago.dmg` for your chip
- Linux: choose `linux64.with-katago.zip`

If you already manage engines manually:

- Windows: choose `windows64.without.engine.installer.exe` if you want installation, or `windows64.without.engine.portable.zip` if you do not
- macOS / Linux: you can still start from the standard bundle and point the app to your own engine later

## Why Windows Is Installer-First Now

Because regular users typically need this path:

1. download the app
2. double-click install
3. avoid dealing with `.bat` launchers
4. avoid manual Java setup
5. let first launch auto-configure bundled KataGo when possible

Portable packages still exist, but they are now secondary to the installer flow.

## Bundled Engine Details

Current bundled defaults:

- KataGo version: `v1.16.4`
- Default weight: `g170e-b20c256x2-s5303129600-d1228401921.bin.gz`

Paths:

- Windows / Linux bundles: `Lizzieyzy/weights/default.bin.gz`
- macOS bundles: `LizzieYzy Next.app/Contents/app/weights/default.bin.gz`

## How To Read Old Versus New Release Layouts

From the new maintained releases onward:

- the main Windows x64 package is `installer.exe`
- Windows x64 also has `nvidia.installer.exe` and `nvidia.portable.zip` for NVIDIA GPU users
- the Windows x64 no-engine option now has both an installer and a portable `.zip`
- the public release page keeps the 9 primary user-facing assets above as the main list
- older compatibility zips now stay in historical tags instead of the main recommendation area

## Related Docs

- [Installation Guide](INSTALL_EN.md)
- [Troubleshooting](TROUBLESHOOTING_EN.md)
- [Tested Platforms](TESTED_PLATFORMS.md)
- [Release Checklist](RELEASE_CHECKLIST.md)
- [Chinese README](../README.md)
