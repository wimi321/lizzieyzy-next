# Installation Guide

This guide is for users who want the fastest path from download to actually using the app.

## Pick The Right Package

| Platform | Recommended package | Bundled Java | Bundled KataGo |
| --- | --- | --- | --- |
| Windows x64 | `<date>-windows64.with-katago.zip` | Yes | Yes |
| Windows x64 | `<date>-windows64.without.engine.zip` | Yes | No |
| Windows x86 | `<date>-windows32.without.engine.zip` | No | No |
| macOS Apple Silicon | `<date>-mac-arm64.with-katago.dmg` | App runtime | Yes |
| macOS Intel | `<date>-mac-amd64.with-katago.dmg` | App runtime | Yes |
| Linux x64 | `<date>-linux64.with-katago.zip` | Yes | Yes |
| Advanced users | `<date>-Macosx.amd64.Linux.amd64.without.engine.zip` | No | No |

If you want the least setup work, choose a `with-katago` package.

## Windows

1. Download `windows64.with-katago.zip`.
2. Extract it to a normal folder.
3. Run `start-windows64.bat`.
4. On first launch, the app should usually detect the bundled KataGo automatically.

If you download `windows64.without.engine.zip`, Java is usually bundled but KataGo is not. You will need to configure your own engine.

## macOS

1. Pick the correct dmg for your chip:
   - Apple Silicon: `mac-arm64.with-katago.dmg`
   - Intel: `mac-amd64.with-katago.dmg`
2. Open the dmg and drag the app to `Applications`.
3. Launch the app from `Applications`.

Current macOS packages are unsigned / not notarized maintenance builds. If Gatekeeper blocks the first launch:

1. Try opening the app once.
2. Go to `System Settings -> Privacy & Security`.
3. Click `Open Anyway`.
4. Launch the app again.

## Linux

1. Download `linux64.with-katago.zip`.
2. Extract it.
3. Start it from a terminal:

```bash
chmod +x start-linux64.sh
./start-linux64.sh
```

## Fox Sync

After launch, use the Fox sync entry and enter a **numeric Fox ID**.
Username lookup is no longer supported in this maintained fork.

## Bundled Engine Details

Current default bundled setup:

- KataGo version: `v1.16.4`
- Weight: `g170e-b20c256x2-s5303129600-d1228401921.bin.gz`

Paths:

- Windows / Linux weight: `Lizzieyzy/weights/default.bin.gz`
- macOS weight: `LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`
