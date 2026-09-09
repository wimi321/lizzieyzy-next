# Package Overview

This document answers three practical questions:

1. which package types are currently recommended in public releases
2. what each package includes
3. which one a regular user should download first

## Quick Answer First

This page describes the public release layout of the maintained `LizzieYzy Next` fork, not the older historical `lizzieyzy` release layout.

- The maintained release page centers on 13 stable first-download assets, plus 6 Windows experimental portable packages
- On Windows, the default recommendation is now `portable.zip`
- RTX 20/30/40/50 NVIDIA users should start with `windows64.nvidia.portable.zip`
- AMD, Intel, and older NVIDIA users should choose `windows64.opencl.portable.zip`
- PCs without a suitable GPU, or where GPU builds cannot start, should use the `windows64.with-katago.portable.zip` CPU compatibility build
- If OpenCL behaves poorly, switch to `windows64.with-katago.portable.zip`
- RTX 20/30/40/50 NVIDIA users share the CUDA 12.8 `windows64.nvidia.portable.zip`
- RTX 40/50 should use CUDA by default; TensorRT is an optional alternative for RTX 30 series and earlier
- Driver `570.65` or newer loads directly; `528.33–570.64` runs one lightweight real-inference probe; older drivers show an explicit repair state
- `KataGo Auto Setup` detects the local NVIDIA GPU / Compute Capability and shows recommended, try, not recommended, or unknown status before TensorRT install

## The 13 Stable Public Release Assets

| Package type | Typical filename | Best for |
| --- | --- | --- |
| Windows x64 NVIDIA CUDA portable | `<date>-windows64.nvidia.portable.zip` | Recommended for RTX 20/30/40/50 NVIDIA users |
| Windows x64 NVIDIA CUDA installer | `<date>-windows64.nvidia.installer.exe` | RTX 20/30/40/50 NVIDIA users who prefer an installer |
| Windows x64 OpenCL portable | `<date>-windows64.opencl.portable.zip` | AMD, Intel, and older NVIDIA GPUs |
| Windows x64 OpenCL installer | `<date>-windows64.opencl.installer.exe` | OpenCL users who prefer an installer |
| Windows x64 CPU fallback portable | `<date>-windows64.with-katago.portable.zip` | PCs without a suitable GPU or when GPU builds cannot start |
| Windows x64 CPU fallback installer | `<date>-windows64.with-katago.installer.exe` | CPU fallback with installer flow |
| Windows x64 no-engine portable | `<date>-windows64.without.engine.portable.zip` | Custom KataGo setup |
| Windows x64 no-engine installer | `<date>-windows64.without.engine.installer.exe` | Users who want installer flow with their own engine |
| macOS Apple Silicon bundle | `<date>-mac-apple-silicon.with-katago.dmg` | M-series Macs |
| macOS Intel bundle | `<date>-mac-intel.with-katago.dmg` | Intel Macs |
| Linux x64 bundle | `<date>-linux64.with-katago.zip` | Linux desktop users |
| Linux x64 OpenCL bundle | `<date>-linux64.opencl.zip` | Linux users with AMD/Intel GPUs |
| Linux x64 NVIDIA CUDA bundle | `<date>-linux64.nvidia.zip` | Linux users with NVIDIA GPUs |

Notes:

- `<date>` is the release date, for example `2026-03-21`.
- The maintained public release page keeps these 13 stable user-facing assets as the main list.
- Windows x64 is portable-first, with matching installers kept as optional alternatives.
- Older tags may still show compatibility zips, but those are now historical layouts.

### Windows experimental portable packages

These packages are outside the 13 stable recommendations and are published for matching-hardware validation:

| Package | Intended hardware |
| --- | --- |
| `<date>-windows64.experimental.directml.portable.zip` | Windows 10/11 GPU with DirectX 12 support |
| `<date>-windows64.experimental.openvino.portable.zip` | Intel CPU, integrated GPU, or supported Intel NPU |
| `<date>-windows64.experimental.rocm.gfx103x.portable.zip` | AMD RDNA2 |
| `<date>-windows64.experimental.rocm.gfx110x.portable.zip` | AMD RDNA3 desktop GPU |
| `<date>-windows64.experimental.rocm.gfx1151.portable.zip` | AMD RDNA3.5 |
| `<date>-windows64.experimental.rocm.gfx120x.portable.zip` | AMD RDNA4 |

Each experimental package still contains KataGo v1.18.1, the B11 default weight, Java, and the application components. CI verifies asset integrity and launch structure; real performance and compatibility remain subject to reports from matching hardware.

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
| `mac-apple-silicon.with-katago.dmg` | App runtime | Bundled | Bundled | Follow the installer artwork, drag to Applications, then eject the DMG |
| `mac-intel.with-katago.dmg` | App runtime | Bundled | Bundled | Follow the installer artwork, drag to Applications, then eject the DMG |
| `linux64.with-katago.zip` | Bundled | Bundled | Bundled | Run `start-linux64.sh` |
| `linux64.opencl.zip` | Bundled | Bundled | Bundled | Run `start-linux64.sh` |
| `linux64.nvidia.zip` | Bundled | Bundled | Bundled | Run `start-linux64.sh` |

## Simple Download Advice

If you just want the shortest path:

- Windows with an RTX 20/30/40/50 NVIDIA GPU: choose the unified `windows64.nvidia.portable.zip`
- Windows with an AMD, Intel, or older NVIDIA GPU: choose `windows64.opencl.portable.zip`
- Windows without a suitable GPU, or when CUDA/OpenCL cannot start: choose `windows64.with-katago.portable.zip`
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

- KataGo version: `v1.18.1` across CPU, OpenCL, CUDA, TensorRT, Metal, and Linux bundles; Linux NVIDIA remains on CUDA `12.1` for runtime compatibility
- macOS release builds pin the official `v1.18.1` commit `92ee95c0a4b25fec214da00951ab69e97e207729`. If Homebrew lags, packaging builds Metal from that commit and verifies the real binary version instead of trusting `VERSION.txt` alone
- Default weight: official flagship B11 Transformer `kata1-tf3-b11c768-s11500M-d6163M.bin.gz`, shown as “Transformer B11 · 2026-09-07”
- Default weight size: `211,568,937` bytes (about 202 MiB), SHA-256: `73f6454eba62d2f6d099af8ce66d8c3fde6225e223c55817da0627590e98b0ae`
- B11 makes stronger individual evaluations and performs better in complex positions, but search can be slower; B10 remains available as an on-demand speed-first model and is not duplicated in full packages
- The single Windows NVIDIA package uses CUDA `12.8` + cuDNN `9.8` for RTX 20/30/40/50; separate `nvidia50` assets are no longer published
- Windows NVIDIA runtimes include matching NVRTC compiler and builtins; release audits verify exact DLLs, official asset hashes, and manifest records
- Driver `570.65` or newer loads directly; `528.33–570.64` runs one lightweight real-inference probe; older drivers show an explicit repair state without silently changing backends
- Transformer performs best through CUDA or Metal; OpenCL remains fully offline-capable but is normally slower
- `core-update.zip` updates only the application and does not include KataGo 1.18.1 or B11; old users keep their current weight until they install a full bundle or explicitly download B11
- Full-bundle migration changes only managed engines still using the old bundled `zhizi 28B` / `default.bin.gz`; custom weights, remote compute, and startup modes are preserved
- TensorRT acceleration: RTX 30 series and earlier users may install it on demand from `KataGo Auto Setup`; RTX 40/50 default to CUDA. Offline users may download every Release split plus its README, manifest, and SHA-256 file, then extract from `.001`
- The TensorRT runtime archive is pinned to SHA-256 `c2758eb60191f01a47b24f54700e5463f577ebe129cd18fe835d0aa9f1e1a16d`. TensorRT remains the main analysis backend, while a lightweight bundled CUDA companion loads HumanSL for AI Coach; preparation temporarily releases foreground GPU analysis and an idempotent lease restores only the captured engine when it is still current
- Legacy `nvidia50-cuda` configurations remain recognized, while new downloads use the unified `windows64.nvidia` CUDA package
- The TensorRT install UI uses `nvidia-smi` to detect the local NVIDIA GPU, with a lightweight model-name fallback when Compute Capability is unavailable

Paths:

- Windows / Linux bundles: `Lizzieyzy/weights/default.bin.gz`
- macOS bundles: `LizzieYzy Next.app/Contents/app/weights/default.bin.gz`

## Bundled Board Sync Helper

- Windows release packages now include native `readboard/readboard.exe` and its dependency files, so normal users do not need to download a separate board sync tool
- Windows native path: `Lizzieyzy/readboard/`
- The app now keeps only the native readboard sync entry and no longer ships or starts the old simplified Java helper

## How To Read Old Versus New Release Layouts

From the new maintained releases onward:

- the main Windows x64 package is `portable.zip`
- Windows x64 exposes OpenCL, CPU fallback, and unified NVIDIA CUDA variants in both portable and installer forms
- the Windows x64 no-engine option now has both an installer and a portable `.zip`
- the public release page keeps the 13 stable first-download assets above as the main list, plus 6 experimental Windows portable packages; TensorRT uses in-app installation by default, while an optional split offline package and its verification metadata remain Release assets
- older compatibility zips now stay in historical tags instead of the main recommendation area

## Related Docs

- [Installation Guide](INSTALL_EN.md)
- [Troubleshooting](TROUBLESHOOTING_EN.md)
- [Tested Platforms](TESTED_PLATFORMS.md)
- [Release Checklist](RELEASE_CHECKLIST.md)
- [Chinese README](../README.md)
