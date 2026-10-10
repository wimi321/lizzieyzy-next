# Troubleshooting

## 1. The app does not open

Check these first:

- the package matches your platform
- the package was fully extracted or installed
- your OS security policy is not blocking first launch

### Windows

- Installer build: rerun `windows64.with-katago.installer.exe`
- NVIDIA CUDA bundle: RTX 20/30/40/50 all use the unified `windows64.nvidia.installer.exe` or `windows64.nvidia.portable.zip`; do not use the separate `nvidia50` package from an old tag
- Driver `570.65` or newer loads directly; `528.33–570.64` runs one lightweight real-inference probe; older drivers show a repair state. RTX 40/50 default to CUDA, while RTX 30 series and earlier may optionally install TensorRT in `KataGo Auto Setup`
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

The main window and Auto Setup read the internal model name from the current weight header. Native KataGo `.bin`, `.bin.gz`, `.txt`, and `.txt.gz` files are supported, including files renamed to `default.bin.gz`. Unlisted Transformer versions keep their full internal names; unreadable headers fall back to the filename. The name supplies display metadata and compatibility hints for recognized naming conventions, not an integrity check. KataGo still determines whether the model can load. Download SHA-256 verification is unchanged.

If the app stops starting after the change, restore the original weight first to confirm whether the new weight file is the problem.

## 5. I want to use my own engine instead of bundled KataGo

Recommended path:

- Windows: choose `windows64.without.engine.portable.zip`
- macOS / Linux: keep using the current main bundle and point the app to your own KataGo in settings

If you only want to replace the weight, you can usually keep the bundled KataGo.

You can rename bundled KataGo in engine settings. Restarts, Auto Setup, and path updates after moving a portable package preserve its name and entry settings. Replacing `default.bin.gz` at the same path changes model information, not entry ownership. After you edit the engine command or enable Java SSH, bundled-profile repair leaves that command intact; a complete package still recreates its bundled default entry when needed.

Legacy entries are migrated when their bundled ownership can be established. Already-renamed legacy entries pointing to another complete package are left alone, and historical duplicates are not merged by guesswork.

After Auto Setup changes a managed profile's weight, restarts preserve that weight, its name, default-engine selection, and preload setting. A manually added default engine may use a different weight from a custom dedicated quick-analysis engine; this difference does not trigger automatic profile creation. Historical duplicates are retained. If all old profiles have lost their managed identity, a complete package may create one valid bundled entry, after which the count should remain stable.

## 6. What should I include in a bug report

The most useful items are:

- the release asset filename
- your OS and version
- whether you used the regular `with-katago`, `nvidia`, or `without.engine` package
- a full screenshot or exact reproduction steps

If the app can start, open **Help → Diagnostics and Logs → Export package** and attach the ZIP to the issue. The package is privacy-sanitized. If the app cannot start, attach `logs/app.log`, and `logs/crash.log` if present.

Related docs:

- [Installation Guide](INSTALL_EN.md)
- [Package Overview](PACKAGES_EN.md)
- [GitHub Issues](https://github.com/wimi321/lizzieyzy-next/issues)
