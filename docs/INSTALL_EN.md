# Installation Guide

This guide answers four practical questions:

1. which package to download
2. how to launch it after installation
3. whether first launch auto-configures the engine
4. how to fetch Fox games by Fox ID

## Pick The Right Package

| Platform | Recommended package | Bundled Java | Bundled KataGo | Best for |
| --- | --- | --- | --- | --- |
| Windows x64 | `<date>-windows64.with-katago.installer.exe` | Yes | Yes | Main recommendation for regular users |
| Windows x64 | `<date>-windows64.with-katago.portable.zip` | Yes | Yes | No installer, unzip and run |
| Windows x64 | `<date>-windows64.without.engine.portable.zip` | Yes | No | Custom engine setup |
| Windows x86 | `<date>-windows32.without.engine.zip` | No | No | Legacy compatibility |
| macOS Apple Silicon | `<date>-mac-arm64.with-katago.dmg` | App runtime | Yes | M-series Macs |
| macOS Intel | `<date>-mac-amd64.with-katago.dmg` | App runtime | Yes | Intel Macs |
| Linux x64 | `<date>-linux64.with-katago.zip` | Yes | Yes | Linux desktop users |
| Advanced custom setup | `<date>-Macosx.amd64.Linux.amd64.without.engine.zip` | No | No | Manual Java and engine setup |

Quick rule:

- choose `with-katago` if you want the shortest path
- choose `without.engine` only if you plan to manage the engine yourself
- on Windows, regular users should start with the installer build

## Windows

### Windows x64 installer

1. Download `windows64.with-katago.installer.exe`.
2. Double-click the installer.
3. Follow the setup wizard.
4. Launch the app from the Start Menu or desktop shortcut.

This is now the primary Windows path for regular users.

### Windows x64 portable build

1. Download `windows64.with-katago.portable.zip`.
2. Extract it to a normal folder.
3. Open the extracted folder.
4. Run `LizzieYzy Next-FoxUID.exe`.

### Windows x64 no-engine build

1. Download `windows64.without.engine.portable.zip`.
2. Extract it and run `LizzieYzy Next-FoxUID.exe`.
3. This package includes the application runtime but not KataGo.
4. Configure your own engine after launch.

### If you are looking at an older release tag

Some older tags still use the previous `windows64.with-katago.zip` layout.

If you do not see the new installer or portable package yet:

1. download the matching `windows64.with-katago.zip`
2. extract it and follow the instructions inside the package
3. newer releases will keep moving toward the installer-first Windows layout

## macOS

### Pick the correct chip build

- Apple Silicon: `mac-arm64.with-katago.dmg`
- Intel: `mac-amd64.with-katago.dmg`

### Installation steps

1. Open the correct `.dmg`.
2. Drag `LizzieYzy Next-FoxUID.app` into `Applications`.
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
2. Open **Fox Kifu (Fetch by Fox ID)**.
3. Enter a numeric Fox ID.
4. Fetch the latest public games.

Notes:

- this maintained fork only supports **Fox ID**
- username lookup is no longer supported
- if the account has no recent public games, the result may be empty

## Bundled Engine Paths

- Windows / Linux bundles: `Lizzieyzy/weights/default.bin.gz`
- macOS bundles: `LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`
- macOS engine directory: `LizzieYzy Next-FoxUID.app/Contents/app/engines/katago/`

Current bundled defaults:

- KataGo version: `v1.16.4`
- Weight: `g170e-b20c256x2-s5303129600-d1228401921.bin.gz`

## Need More Help

- [Package Overview](PACKAGES_EN.md)
- [Troubleshooting](TROUBLESHOOTING_EN.md)
- [Tested Platforms](TESTED_PLATFORMS.md)
