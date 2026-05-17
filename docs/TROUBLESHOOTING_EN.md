# Troubleshooting

## 1. The app does not open

Check these first:

- the package matches your platform
- the package was fully extracted or installed
- your OS security policy is not blocking first launch

### Windows

- Installer build: rerun `windows64.with-katago.installer.exe`
- NVIDIA bundle: on RTX 20/30/40 series, confirm whether you downloaded `windows64.nvidia.installer.exe` or `windows64.nvidia.portable.zip`
- RTX 50 series: first confirm whether you downloaded `windows64.nvidia50.cuda.installer.exe` or `windows64.nvidia50.cuda.portable.zip`; TensorRT experimental acceleration is installed manually from `KataGo Auto Setup` inside the app
- Portable build: make sure you are launching `LizzieYzy Next.exe`
- The current public release should not require `.bat` launchers for the main Windows path

### macOS

If Gatekeeper blocks the app:

1. try opening it once
2. go to `System Settings -> Privacy & Security`
3. click `Open Anyway`
4. launch it again

### Linux

Start it from a terminal first:

```bash
chmod +x start-linux64.sh
./start-linux64.sh
```

That is the fastest way to see Java, permission, or library errors.

## 2. First launch did not auto-configure the engine

The maintained fork tries to auto-detect:

- bundled KataGo
- the default weight
- bundled config files

If auto setup fails:

1. confirm you downloaded a `with-katago` package
2. confirm `weights/default.bin.gz` is still present
3. confirm `engines/katago/` was not removed
4. relaunch the app once

Only switch to manual configuration after that.

## 3. Fox sync returned no games

Check these first:

- you entered the correct **Fox nickname**
- the account really has recent public games
- there is no temporary network issue

Notes:

- the maintained fork now defaults to **nickname search** and resolves the account automatically
- if the nickname is wrong, the account lookup can fail
- an empty result is normal if the account has no recent public games

## 4. I want to replace the bundled weight

You can replace the default weight file directly, but keep the filename and location consistent.

Default locations:

- Windows / Linux: `Lizzieyzy/weights/default.bin.gz`
- macOS: `LizzieYzy Next.app/Contents/app/weights/default.bin.gz`

If the app stops starting after the change, restore the original weight first to confirm whether the new weight file is the problem.

## 5. I want to use my own engine instead of bundled KataGo

Recommended path:

- Windows: choose `windows64.without.engine.portable.zip`
- macOS / Linux: keep using the current main bundle and point the app to your own KataGo in settings

If you only want to replace the weight, you can usually keep the bundled KataGo.

## 6. What should I include in a bug report

The most useful items are:

- the release asset filename
- your OS and version
- whether you used the regular `with-katago`, `nvidia`, or `without.engine` package
- a full screenshot or exact reproduction steps

Related docs:

- [Installation Guide](INSTALL_EN.md)
- [Package Overview](PACKAGES_EN.md)
- [GitHub Issues](https://github.com/wimi321/lizzieyzy-next/issues)
