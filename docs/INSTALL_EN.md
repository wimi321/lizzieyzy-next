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
- If you care about board sync, the main release packages now also include the simplified `readboard_java` helper, so you do not need a separate repo first

## Pick The Right Package

| Platform | Recommended package | Bundled Java | Bundled KataGo | Best for |
| --- | --- | --- | --- | --- |
| Windows x64 | `<date>-windows64.opencl.portable.zip` | Yes | Yes | Main recommendation for regular users, unzip and run |
| Windows x64 | `<date>-windows64.opencl.installer.exe` | Yes | Yes | OpenCL users who prefer the installer flow |
| Windows x64 | `<date>-windows64.with-katago.portable.zip` | Yes | Yes | CPU fallback when OpenCL behaves badly |
| Windows x64 | `<date>-windows64.with-katago.installer.exe` | Yes | Yes | CPU fallback with an installer |
| Windows x64 | `<date>-windows64.nvidia.portable.zip` | Yes | Yes | NVIDIA GPU users who want higher analysis speed without an installer |
| Windows x64 | `<date>-windows64.nvidia.installer.exe` | Yes | Yes | NVIDIA GPU users who prefer an installer |
| Windows x64 | `<date>-windows64.without.engine.portable.zip` | Yes | No | Custom engine setup without installation |
| Windows x64 | `<date>-windows64.without.engine.installer.exe` | Yes | No | Installer flow with your own engine |
| macOS Apple Silicon | `<date>-mac-arm64.with-katago.dmg` | App runtime | Yes | M-series Macs |
| macOS Intel | `<date>-mac-amd64.with-katago.dmg` | App runtime | Yes | Intel Macs |
| Linux x64 | `<date>-linux64.with-katago.zip` | Yes | Yes | Linux desktop users |

Quick rule:

- choose `windows64.opencl.portable.zip` if you want the shortest path
- choose `windows64.with-katago.portable.zip` if OpenCL behaves badly on your PC
- choose `windows64.nvidia.portable.zip` if your PC has an NVIDIA GPU and you want faster KataGo analysis
- choose `without.engine.portable.zip` or `without.engine.installer.exe` on Windows if you plan to manage the engine yourself
- on Windows, regular users should start with the portable build and only switch to the installer if they want that flow

### Legacy tag note

Some older tags still show transitional zip names or compatibility packages, but the current maintained release now centers on 11 primary assets: 8 Windows, 2 macOS, and 1 Linux package.

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

- Apple Silicon: `mac-arm64.with-katago.dmg`
- Intel: `mac-amd64.with-katago.dmg`

### Installation steps

1. Open the correct `.dmg`.
2. Drag `LizzieYzy Next.app` into `Applications`.
3. Launch it from `Applications`.

### If Gatekeeper blocks first launch

Current maintenance builds are still unsigned and not notarized.

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

- KataGo version: `v1.16.4`
- Weight: `kata1-zhizi-b28c512nbt-muonfd2.bin.gz`

## Need More Help

- [Package Overview](PACKAGES_EN.md)
- [Troubleshooting](TROUBLESHOOTING_EN.md)
- [Tested Platforms](TESTED_PLATFORMS.md)
