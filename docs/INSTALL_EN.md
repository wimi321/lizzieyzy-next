# Installation Guide

This guide answers four practical questions:

1. which package to download
2. how to launch it after installation
3. whether first launch auto-configures the engine
4. how to fetch Fox games by nickname

## Pick The Right Package

| Platform | Recommended package | Bundled Java | Bundled KataGo | Best for |
| --- | --- | --- | --- | --- |
| Windows x64 | `<date>-windows64.with-katago.installer.exe` | Yes | Yes | Main recommendation for regular users |
| Windows x64 | `<date>-windows64.nvidia.installer.exe` | Yes | Yes | NVIDIA GPU users who want higher analysis speed |
| Windows x64 | `<date>-windows64.nvidia.portable.zip` | Yes | Yes | NVIDIA GPU users who do not want an installer |
| Windows x64 | `<date>-windows64.with-katago.portable.zip` | Yes | Yes | No installer, unzip and run |
| Windows x64 | `<date>-windows64.without.engine.installer.exe` | Yes | No | Installer flow with your own engine |
| Windows x64 | `<date>-windows64.without.engine.portable.zip` | Yes | No | Custom engine setup |
| macOS Apple Silicon | `<date>-mac-arm64.with-katago.dmg` | App runtime | Yes | M-series Macs |
| macOS Intel | `<date>-mac-amd64.with-katago.dmg` | App runtime | Yes | Intel Macs |
| Linux x64 | `<date>-linux64.with-katago.zip` | Yes | Yes | Linux desktop users |

Quick rule:

- choose `with-katago` if you want the shortest path
- choose `windows64.nvidia.installer.exe` if your PC has an NVIDIA GPU and you want faster KataGo analysis
- choose `without.engine.installer.exe` or `without.engine.portable.zip` on Windows if you plan to manage the engine yourself
- on Windows, regular users should start with the installer build

### Legacy tag note

Some older tags still show transitional zip names or compatibility packages, but the current maintained release now centers on 9 primary assets: 6 Windows, 2 macOS, and 1 Linux package.

## Windows

### Windows x64 installer

1. Download `windows64.with-katago.installer.exe`.
2. Double-click the installer.
3. Follow the setup wizard.
4. Launch the app from the Start Menu or desktop shortcut.

This is now the primary Windows path for regular users.

### Windows x64 NVIDIA bundle

If your PC has an NVIDIA GPU and you want higher analysis speed:

1. Download `windows64.nvidia.installer.exe`.
2. Double-click the installer.
3. Finish setup.
4. Launch `LizzieYzy Next NVIDIA` from the Start Menu or desktop shortcut.

If you prefer a no-install package:

1. Download `windows64.nvidia.portable.zip`.
2. Extract it.
3. Run `LizzieYzy Next NVIDIA.exe`.

This bundle ships with the official KataGo CUDA Windows build. If you are not sure whether your PC has an NVIDIA GPU, use the regular `windows64.with-katago.installer.exe` instead.

### Windows x64 portable build

1. Download `windows64.with-katago.portable.zip`.
2. Extract it to a normal folder.
3. Open the extracted folder.
4. Run `LizzieYzy Next.exe`.

### Windows x64 no-engine build

If you want the installer flow but prefer your own engine:

1. Download `windows64.without.engine.installer.exe`.
2. Double-click the installer.
3. Finish setup and launch `LizzieYzy Next`.
4. Configure your own engine after launch.

If you prefer a no-install package:

1. Download `windows64.without.engine.portable.zip`.
2. Extract it and run `LizzieYzy Next.exe`.
3. This package includes the application runtime but not KataGo.
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
- Weight: `g170e-b20c256x2-s5303129600-d1228401921.bin.gz`

## Need More Help

- [Package Overview](PACKAGES_EN.md)
- [Troubleshooting](TROUBLESHOOTING_EN.md)
- [Tested Platforms](TESTED_PLATFORMS.md)
