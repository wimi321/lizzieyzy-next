# Package Overview

This document answers three practical questions:

1. which package types are currently maintained
2. what each package includes
3. which one a regular user should download first

## Current Recommended Release Assets

| Package type | Typical filename | Best for |
| --- | --- | --- |
| Windows x64 installer | `<date>-windows64.with-katago.installer.exe` | Regular users who want the easiest path |
| Windows x64 bundled portable | `<date>-windows64.with-katago.portable.zip` | Users who do not want an installer |
| Windows x64 no-engine portable | `<date>-windows64.without.engine.portable.zip` | Custom KataGo setup |
| Windows x86 compatibility package | `<date>-windows32.without.engine.zip` | Older systems or compatibility cases |
| macOS Apple Silicon bundle | `<date>-mac-arm64.with-katago.dmg` | M-series Macs |
| macOS Intel bundle | `<date>-mac-amd64.with-katago.dmg` | Intel Macs |
| Linux x64 bundle | `<date>-linux64.with-katago.zip` | Linux desktop users |
| Advanced macOS/Linux no-engine package | `<date>-Macosx.amd64.Linux.amd64.without.engine.zip` | Fully custom setups |

Notes:

- `<date>` is the release date, for example `2026-03-21`.
- Windows x64 is now installer-first, with a bundled portable build as the second choice.
- If an older tag still shows `windows64.with-katago.zip`, that is the previous release layout.
- macOS stays centered on `.dmg` packages instead of `.app.zip` archives.

## What Each Package Includes

| Package | Java | KataGo | Weight | How you start it |
| --- | --- | --- | --- | --- |
| `windows64.with-katago.installer.exe` | Bundled | Bundled | Bundled | Install, then launch from Start Menu or desktop |
| `windows64.with-katago.portable.zip` | Bundled | Bundled | Bundled | Unzip and run `LizzieYzy Next-FoxUID.exe` |
| `windows64.without.engine.portable.zip` | Bundled | Not bundled | Not bundled | Unzip and run `LizzieYzy Next-FoxUID.exe` |
| `windows32.without.engine.zip` | Not bundled | Not bundled | Not bundled | Follow the package instructions |
| `mac-arm64.with-katago.dmg` | App runtime | Bundled | Bundled | Drag to Applications |
| `mac-amd64.with-katago.dmg` | App runtime | Bundled | Bundled | Drag to Applications |
| `linux64.with-katago.zip` | Bundled | Bundled | Bundled | Run `start-linux64.sh` |
| `Macosx.amd64.Linux.amd64.without.engine.zip` | Not bundled | Not bundled | Not bundled | Manual setup |

## Simple Download Advice

If you just want the shortest path:

- Windows: choose `windows64.with-katago.installer.exe`
- macOS: choose the correct `with-katago.dmg` for your chip
- Linux: choose `linux64.with-katago.zip`

If you already manage engines manually:

- Windows: choose `windows64.without.engine.portable.zip`
- Full custom setup on other platforms: choose `Macosx.amd64.Linux.amd64.without.engine.zip`

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
- macOS bundles: `LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`

## How To Read Old Versus New Release Layouts

From the new maintained releases onward:

- the main Windows x64 package is `installer.exe`
- the Windows x64 no-engine package also moves to a portable `.exe` flow
- the old `windows64.with-katago.zip` naming stays only as historical compatibility guidance

If you are organizing the release page, do not keep the old Windows x64 zip packages in the main recommendation list.

## Related Docs

- [Installation Guide](INSTALL_EN.md)
- [Troubleshooting](TROUBLESHOOTING_EN.md)
- [Tested Platforms](TESTED_PLATFORMS.md)
- [Release Checklist](RELEASE_CHECKLIST.md)
- [Chinese README](../README.md)
