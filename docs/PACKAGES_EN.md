# Package Overview

This document explains which package types are currently maintained and what each one includes.

## Maintained Package Types

| Package type | Typical filename | Best for |
| --- | --- | --- |
| Windows x64 all-in-one | `<date>-windows64.with-katago.zip` | Users who want the easiest setup |
| Windows x64 no-engine | `<date>-windows64.without.engine.zip` | Users who want their own engine |
| Windows x86 compatibility | `<date>-windows32.without.engine.zip` | Older systems |
| macOS Apple Silicon | `<date>-mac-arm64.with-katago.dmg` | M-series Macs |
| macOS Intel | `<date>-mac-amd64.with-katago.dmg` | Intel Macs |
| Linux x64 all-in-one | `<date>-linux64.with-katago.zip` | Linux desktop users |
| Advanced macOS/Linux no-engine | `<date>-Macosx.amd64.Linux.amd64.without.engine.zip` | Fully custom setups |

## What Is Bundled

| Package | Java | KataGo | Weight |
| --- | --- | --- | --- |
| `windows64.with-katago` | Bundled | Bundled | Bundled |
| `windows64.without.engine` | Bundled | Not bundled | Not bundled |
| `windows32.without.engine` | Not bundled | Not bundled | Not bundled |
| `mac-arm64.with-katago.dmg` | App runtime | Bundled | Bundled |
| `mac-amd64.with-katago.dmg` | App runtime | Bundled | Bundled |
| `linux64.with-katago` | Bundled | Bundled | Bundled |
| `Macosx.amd64.Linux.amd64.without.engine` | Not bundled | Not bundled | Not bundled |

## Bundled Engine Details

Current default bundled setup:

- KataGo version: `v1.16.4`
- Weight: `g170e-b20c256x2-s5303129600-d1228401921.bin.gz`

## Naming Pattern

Package names are intentionally descriptive:

`date-platform-engine-flavor`

Examples:

- `2026-03-16-windows64.with-katago.zip`
- `2026-03-16-windows64.without.engine.zip`
- `2026-03-16-mac-arm64.with-katago.dmg`

## Related Docs

- [Installation Guide](INSTALL_EN.md)
- [Troubleshooting](TROUBLESHOOTING_EN.md)
- [Chinese README](../README.md)
