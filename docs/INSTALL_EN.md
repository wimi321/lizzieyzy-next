# Installation Guide

This guide answers four practical questions:

1. which package to download
2. how to launch it after installation
3. whether first launch auto-configures the engine
4. how to fetch Fox games by nickname

## Quick Answer First

This installation guide is for the actively maintained `LizzieYzy Next` fork, which is the practical `LizzieYzy replacement / maintained fork` many users are actually looking for.

- If you want a portable Windows `KataGo review software` package, start with `windows64.opencl.portable.zip`
- If you are looking for a maintained `LizzieYzy` build that still works, this is the project to check first
- If you want to enter a `Fox nickname`, fetch games, and review them right away, the maintained fork already supports that flow
- If you are worried about first-launch setup, the recommended bundles already include KataGo and a default weight
- If you care about board sync, the Windows release packages now include native `readboard.exe`; the app keeps a single board-sync entry for that native tool, so you do not need a separate repo first

## Pick The Right Package

| Platform | Recommended package | Bundled Java | Bundled KataGo | Best for |
| --- | --- | --- | --- | --- |
| Windows x64 | `<date>-windows64.opencl.portable.zip` | Yes | Yes | Main recommendation for regular users, unzip and run |
| Windows x64 | `<date>-windows64.opencl.installer.exe` | Yes | Yes | OpenCL users who prefer the installer flow |
| Windows x64 | `<date>-windows64.with-katago.portable.zip` | Yes | Yes | CPU fallback when OpenCL behaves badly |
| Windows x64 | `<date>-windows64.with-katago.installer.exe` | Yes | Yes | CPU fallback with an installer |
| Windows x64 | `<date>-windows64.nvidia.portable.zip` | Yes | Yes | RTX 20/30/40/50 NVIDIA users, no installer |
| Windows x64 | `<date>-windows64.nvidia.installer.exe` | Yes | Yes | RTX 20/30/40/50 NVIDIA users who prefer an installer |
| Windows x64 | `<date>-windows64.without.engine.portable.zip` | Yes | No | Custom engine setup without installation |
| Windows x64 | `<date>-windows64.without.engine.installer.exe` | Yes | No | Installer flow with your own engine |
| macOS Apple Silicon | `<date>-mac-apple-silicon.with-katago.dmg` | App runtime | Yes | M-series Macs |
| macOS Intel | `<date>-mac-intel.with-katago.dmg` | App runtime | Yes | Intel Macs |
| Linux x64 | `<date>-linux64.with-katago.zip` | Yes | Yes | Linux desktop users |
| Linux x64 | `<date>-linux64.opencl.zip` | Yes | Yes | Linux users with AMD/Intel GPUs |
| Linux x64 | `<date>-linux64.nvidia.zip` | Yes | Yes | Linux users with NVIDIA GPUs |

Quick rule:

- choose `windows64.opencl.portable.zip` if you want the shortest path
- choose `windows64.with-katago.portable.zip` if OpenCL behaves badly on your PC
- choose the single `windows64.nvidia.portable.zip` for RTX 20/30/40/50 NVIDIA GPUs
- use CUDA by default on RTX 40/50; TensorRT is an optional on-demand alternative for RTX 30 series and earlier
- `KataGo Auto Setup` detects the NVIDIA GPU / Compute Capability before recommending TensorRT
- driver `570.65` or newer loads directly; `528.33` through `570.64` runs one lightweight real-inference probe on first use; older drivers show an explicit repair state
- NVIDIA cards older than GTX 10 series should prefer the OpenCL package
- choose `without.engine.portable.zip` or `without.engine.installer.exe` on Windows if you plan to manage the engine yourself
- on Windows, regular users should start with the portable build and only switch to the installer if they want that flow

### Legacy tag note

Some older tags still show transitional names, but the current maintained release centers on 13 stable first-download assets: 8 Windows, 2 macOS, and 3 Linux packages. Six Windows experimental portable packages cover DirectML, OpenVINO, and four AMD ROCm architecture families. In-app installation is the regular TensorRT path; the Release also retains an optional offline split package with its README, manifest, and checksum file.

### Windows experimental backends

These portable-only packages do not replace the stable packages above:

| Experimental package | Intended hardware |
| --- | --- |
| `<date>-windows64.experimental.directml.portable.zip` | Windows 10/11 GPU with DirectX 12 support |
| `<date>-windows64.experimental.openvino.portable.zip` | Intel CPU, integrated GPU, or supported Intel NPU |
| `<date>-windows64.experimental.rocm.gfx103x.portable.zip` | AMD RDNA2 |
| `<date>-windows64.experimental.rocm.gfx110x.portable.zip` | AMD RDNA3 desktop GPU |
| `<date>-windows64.experimental.rocm.gfx1151.portable.zip` | AMD RDNA3.5 |
| `<date>-windows64.experimental.rocm.gfx120x.portable.zip` | AMD RDNA4 |

The app labels these backends experimental. Use the stable OpenCL package if the matching experimental backend is not compatible with your hardware.

## Windows

### Windows x64 OpenCL portable build

1. Download `windows64.opencl.portable.zip`.
2. Extract it to a normal folder.
3. Open the extracted folder.
4. Run `LizzieYzy Next OpenCL.exe`.

This is now the primary Windows path for regular users.
The OpenCL bundle can also open `KataGo Auto Setup` and run `Smart Optimize` to write a better thread setting automatically.

### Windows x64 OpenCL installer

If you prefer the installer flow:

1. Download `windows64.opencl.installer.exe`.
2. Double-click the installer.
3. Follow the setup wizard.
4. Launch the app from the Start Menu or desktop shortcut.

### Windows x64 CPU fallback

If OpenCL behaves badly on your PC:

1. Download `windows64.with-katago.portable.zip`.
2. Extract it and run `LizzieYzy Next.exe`.
3. If you prefer the installer flow, switch to `windows64.with-katago.installer.exe`.

### Windows x64 NVIDIA bundle

If your PC has an NVIDIA GPU and you want higher analysis speed:

1. Download `windows64.nvidia.portable.zip`.
2. Extract it.
3. Run `LizzieYzy Next NVIDIA.exe`.
4. On first launch, the app automatically prepares the required official NVIDIA runtime files in your user folder.

If you prefer the installer flow:

1. Download `windows64.nvidia.installer.exe`.
2. Double-click the installer.
3. Finish setup and launch `LizzieYzy Next NVIDIA`.

This bundle ships with the official KataGo CUDA Windows build. If you want to tune speed further, open `KataGo Auto Setup` once and run `Smart Optimize` to apply a benchmark-based thread setting automatically. If you are not sure whether your PC has an NVIDIA GPU, use the regular `windows64.opencl.portable.zip` instead.

### Unified NVIDIA CUDA and optional TensorRT

RTX 20/30/40/50 users all download `windows64.nvidia.portable.zip` and run `LizzieYzy Next NVIDIA.exe`; use the matching installer only when you prefer that flow. The package uses CUDA 12.8 + cuDNN 9.8, and CUDA is the default recommendation for RTX 40/50.

TensorRT is no longer a huge standalone package recommended to regular users. RTX 30 series and earlier users may open `KataGo Auto Setup` and choose `Install TensorRT acceleration` as an optional alternative. The install UI detects the local NVIDIA GPU / Compute Capability and shows recommended, try, not recommended, or unknown status. The app downloads and verifies files from official KataGo / NVIDIA sources only; nothing is downloaded unless the user starts installation. Fully offline users can download every `.7z.00N` split asset, verify the matching README, manifest, and SHA-256 file, and extract from `.001`.

### Windows x64 no-engine build

If you want your own engine without installation:

1. Download `windows64.without.engine.portable.zip`.
2. Extract it and run `LizzieYzy Next.exe`.
3. This package includes the application runtime but not KataGo.
4. Configure your own engine after launch.

If you prefer the installer flow:

1. Download `windows64.without.engine.installer.exe`.
2. Double-click the installer.
3. Finish setup and launch `LizzieYzy Next`.
4. Configure your own engine after launch.

## macOS

### Pick the correct chip build

- Apple Silicon: `mac-apple-silicon.with-katago.dmg`
- Intel: `mac-intel.with-katago.dmg`

### Installation steps

1. Open the correct `.dmg`.
2. Confirm that the chip label in the top-right corner matches your Mac.
3. Follow the arrow and drag `LizzieYzy Next.app` onto the `Applications` folder.
4. Wait for the copy to finish, then eject the `LizzieYzy Next` installer disk in Finder.
5. Open Finder's `Applications` folder and launch `LizzieYzy Next` from there.

Do not launch the app directly from the installer disk. That only runs the temporary
copy mounted from the DMG and does not install the app.

### If Gatekeeper blocks first launch

Current official macOS releases are signed and notarized in the release pipeline.

If you downloaded the current DMG from the official release page, it should usually open normally.
If macOS still blocks it the first time, the cause is usually a local security cache, policy, or an older app record. Use the steps below:

If macOS blocks the first launch:

1. try opening the app once
2. go to `System Settings -> Privacy & Security`
3. click `Open Anyway`
4. launch the app again

## Linux

1. Download `linux64.with-katago.zip`.
2. Extract it to a writable folder.
3. Start it from a terminal:

```bash
chmod +x start-linux64.sh
./start-linux64.sh
```

If double-click launch does nothing in your desktop environment, launching from a terminal is the fastest way to see the error.

## What First Launch Does Now

The maintained fork now tries to handle the common setup work automatically:

- detect bundled KataGo binaries, configs, and default weight
- write a usable default engine configuration
- offer a guided path to download a recommended official weight if needed
- fall back to manual setup only when auto setup still cannot produce a working configuration

That means most `with-katago` users should not need manual engine setup on day one.

## Fox Sync

1. Launch the app.
2. Open **Fox Kifu (search by nickname)**.
3. Enter a Fox nickname.
4. The app resolves the account automatically and fetches recent public games.

Notes:

- you do not need to know the numeric account ID first
- if the nickname is wrong, the account lookup can fail
- an empty result is normal if the account has no recent public games

## Bundled Engine Paths

- Windows / Linux bundles: `Lizzieyzy/weights/default.bin.gz`
- macOS bundles: `LizzieYzy Next.app/Contents/app/weights/default.bin.gz`
- macOS engine directory: `LizzieYzy Next.app/Contents/app/engines/katago/`

Current bundled defaults:

- KataGo version: `v1.18.1`
- Weight: official flagship B11 Transformer `kata1-tf3-b11c768-s11500M-d6163M.bin.gz` (shown as “Transformer B11 · 2026-09-07”, `211,568,937` bytes, about 202 MiB)
- B11 makes stronger individual evaluations and handles complex positions better, but search can be slower; users who prioritize throughput can download and switch to B10 in `KataGo Auto Setup`
- Upgrading an older full bundle requires the latest full package; `core-update.zip` does not contain the new engine or weight

## Need More Help

- [Package Overview](PACKAGES_EN.md)
- [Troubleshooting](TROUBLESHOOTING_EN.md)
- [Tested Platforms](TESTED_PLATFORMS.md)
