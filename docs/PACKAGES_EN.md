# Package Overview

This document answers three practical questions:

1. which package types are currently recommended in public releases
2. what each package includes
3. which one a regular user should download first

## Quick Answer First

This page describes the public release layout of the maintained `LizzieYzy Next` fork, not the older historical `lizzieyzy` release layout.

- The maintained release page now centers on 11 primary user-facing assets
- On Windows, the default recommendation is now `portable.zip`
- Most regular users should start with `windows64.opencl.portable.zip`
- If OpenCL behaves poorly, switch to `windows64.with-katago.portable.zip`
- If you have an NVIDIA GPU and want more speed, switch to `windows64.nvidia.portable.zip`

## The 11 Primary Public Release Assets

| Package type | Typical filename | Best for |
| --- | --- | --- |
| Windows x64 OpenCL portable | `<date>-windows64.opencl.portable.zip` | Main recommendation for regular users |
| Windows x64 OpenCL installer | `<date>-windows64.opencl.installer.exe` | OpenCL users who prefer an installer |
| Windows x64 CPU fallback portable | `<date>-windows64.with-katago.portable.zip` | CPU fallback when OpenCL behaves badly |
| Windows x64 CPU fallback installer | `<date>-windows64.with-katago.installer.exe` | CPU fallback with installer flow |
| Windows x64 NVIDIA portable | `<date>-windows64.nvidia.portable.zip` | NVIDIA GPU users who want higher analysis speed without an installer |
| Windows x64 NVIDIA installer | `<date>-windows64.nvidia.installer.exe` | NVIDIA GPU users who prefer an installer |
| Windows x64 no-engine portable | `<date>-windows64.without.engine.portable.zip` | Custom KataGo setup |
| Windows x64 no-engine installer | `<date>-windows64.without.engine.installer.exe` | Users who want installer flow with their own engine |
| macOS Apple Silicon bundle | `<date>-mac-arm64.with-katago.dmg` | M-series Macs |
| macOS Intel bundle | `<date>-mac-amd64.with-katago.dmg` | Intel Macs |
| Linux x64 bundle | `<date>-linux64.with-katago.zip` | Linux desktop users |

Notes:

- `<date>` is the release date, for example `2026-03-21`.
- The maintained public release page now keeps these 11 user-facing assets as the main list.
- Windows x64 is portable-first, with matching installers kept as optional alternatives.
- Older tags may still show compatibility zips, but those are now historical layouts.

## What Each Package Includes

| Package | Java | KataGo | Weight | How you start it |
| --- | --- | --- | --- | --- |
| `windows64.opencl.portable.zip` | Bundled | Bundled | Bundled | Unzip and run `LizzieYzy Next OpenCL.exe` |
| `windows64.opencl.installer.exe` | Bundled | Bundled | Bundled | Install, then launch from Start Menu or desktop |
| `windows64.with-katago.portable.zip` | Bundled | Bundled | Bundled | Unzip and run `LizzieYzy Next.exe` |
| `windows64.with-katago.installer.exe` | Bundled | Bundled | Bundled | Install, then launch from Start Menu or desktop |
| `windows64.nvidia.portable.zip` | Bundled | Bundled | Bundled | Unzip and run `LizzieYzy Next NVIDIA.exe` |
| `windows64.nvidia.installer.exe` | Bundled | Bundled | Bundled | Install, then launch `LizzieYzy Next NVIDIA` |
| `windows64.without.engine.portable.zip` | Bundled | Not bundled | Not bundled | Unzip and run `LizzieYzy Next.exe` |
| `windows64.without.engine.installer.exe` | Bundled | Not bundled | Not bundled | Install, then launch from Start Menu or desktop |
| `mac-arm64.with-katago.dmg` | App runtime | Bundled | Bundled | Drag to Applications |
| `mac-amd64.with-katago.dmg` | App runtime | Bundled | Bundled | Drag to Applications |
| `linux64.with-katago.zip` | Bundled | Bundled | Bundled | Run `start-linux64.sh` |

## Simple Download Advice

If you just want the shortest path:

- Windows: choose `windows64.opencl.portable.zip`
- Windows with an NVIDIA GPU: choose `windows64.nvidia.portable.zip`
- macOS: choose the correct `with-katago.dmg` for your chip
- Linux: choose `linux64.with-katago.zip`

If you already manage engines manually:

- Windows: choose `windows64.without.engine.portable.zip` if you do not want installation, or `windows64.without.engine.installer.exe` if you do
- macOS / Linux: you can still start from the standard bundle and point the app to your own engine later

## Why Windows Is Portable-First Now

Because regular users typically need this path:

1. download the app
2. unzip and run immediately
3. keep the option to install only if they want it
4. avoid manual Java setup
5. let first launch auto-configure bundled KataGo when possible

Installers still exist, but they are now secondary to the portable flow.

## Bundled Engine Details

Current bundled defaults:

- KataGo version: `v1.16.4`
- Default weight: `kata1-zhizi-b28c512nbt-muonfd2.bin.gz`

Paths:

- Windows / Linux bundles: `Lizzieyzy/weights/default.bin.gz`
- macOS bundles: `LizzieYzy Next.app/Contents/app/weights/default.bin.gz`

## Bundled Board Sync Helper

- Main release packages now ship the simplified `readboard_java` helper as part of the main product
- Windows / Linux path: `Lizzieyzy/readboard_java/`
- macOS path: `LizzieYzy Next.app/Contents/app/readboard_java/`
- The older Windows-native `readboard` folder is now treated as a legacy compatibility path, not the main release dependency

## How To Read Old Versus New Release Layouts

From the new maintained releases onward:

- the main Windows x64 package is `portable.zip`
- Windows x64 now exposes OpenCL, CPU fallback, and NVIDIA variants in both portable and installer forms
- the Windows x64 no-engine option now has both an installer and a portable `.zip`
- the public release page keeps the 11 primary user-facing assets above as the main list
- older compatibility zips now stay in historical tags instead of the main recommendation area

## Related Docs

- [Installation Guide](INSTALL_EN.md)
- [Troubleshooting](TROUBLESHOOTING_EN.md)
- [Tested Platforms](TESTED_PLATFORMS.md)
- [Release Checklist](RELEASE_CHECKLIST.md)
- [Chinese README](../README.md)
