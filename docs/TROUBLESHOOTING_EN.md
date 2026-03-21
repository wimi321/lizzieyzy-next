# Troubleshooting

This guide covers the most common problems in the maintained fork:

- Windows or macOS installs blocked by the OS
- first launch does not auto-connect to KataGo
- Fox sync returns no games
- uncertainty about whether to use the installer, bundled portable build, or no-engine build

## 1. The Windows installer will not open or is blocked

First confirm that you downloaded:

- `windows64.with-katago.installer.exe`

Common fixes:

1. right-click the installer and try "Run as administrator"
2. if SmartScreen blocks it, click "More info" and then "Run anyway"
3. make sure the install directory is not read-only

If you do not want the installer flow, try `windows64.with-katago.portable.zip` instead.

## 2. The Windows portable build opens but nothing happens

First confirm that you downloaded:

- `windows64.with-katago.portable.zip`
- or `windows64.without.engine.portable.zip`

Check these things in order:

1. make sure you extracted the full package, not just a single `.jar`
2. make sure you are launching `LizzieYzy Next-FoxUID.exe`
3. if you are using the no-engine package, manual engine configuration after launch is expected

## 3. macOS says the app cannot be opened or is damaged

First confirm that you picked the correct chip build:

- Apple Silicon: `mac-arm64.with-katago.dmg`
- Intel: `mac-amd64.with-katago.dmg`

Current macOS builds are unsigned and not notarized, so the first launch being blocked is expected.

Fix steps:

1. try opening the app once
2. go to `System Settings -> Privacy & Security`
3. find the blocked app message
4. click `Open Anyway`

## 4. The app opens but KataGo is not auto-configured

`with-katago` packages are meant to include:

- the engine
- the bundled weight
- the config files
- first-launch auto setup

If it still does not connect automatically, check these first:

1. whether you downloaded a `without.engine` package instead
2. whether `weights/default.bin.gz` still exists
3. whether `engines/katago/` was accidentally deleted
4. whether you moved only the app jar and broke relative paths

The safest approach is:

- do not run a standalone jar
- keep the full package structure intact
- launch from the installed or fully extracted directory

## 5. Fox sync returns no games

Check these first:

1. you entered a **numeric Fox ID**, not a username
2. the account really has recent public games
3. your network is working
4. the Fox API is not temporarily unstable

This maintained fork standardizes the flow around **Fox ID**. The UI, README, and issue templates use the same wording.

## 6. The app says only numeric Fox IDs are supported

That means the input format is wrong, not that the feature is broken.

Correct example:

- `12345678`

Incorrect examples:

- `something tasty`
- `fox_123`
- `12345abc`

## 7. I entered a Fox ID and still got nothing

Common reasons:

- the ID has no recent public games
- the API failed temporarily
- your local network is unstable

Recommended order:

1. try another Fox ID that you know has public games
2. wait a few minutes and try again
3. if it still fails, open a GitHub issue and include:
   - the release asset filename
   - your OS version
   - the Fox ID
   - a screenshot of the error

## 8. I want to replace the bundled weight

You can replace the default weight file directly, but keep the filename and location consistent.

Default locations:

- Windows / Linux: `Lizzieyzy/weights/default.bin.gz`
- macOS: `LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`

If the app stops starting after the change, restore the original weight first to confirm whether the new weight file is the problem.

## 9. I want to use my own engine instead of bundled KataGo

Choose one of these packages:

- `windows64.without.engine.portable.zip`
- `Macosx.amd64.Linux.amd64.without.engine.zip`

If you only want to replace the weight, you can usually keep the bundled KataGo.

## 10. What should I include in a bug report

The most useful items are:

- the release asset filename
- your OS and version
- whether you used `with-katago` or `without.engine`
- a full screenshot or exact reproduction steps

Related docs:

- [Installation Guide](INSTALL_EN.md)
- [Package Overview](PACKAGES_EN.md)
- [GitHub Issues](https://github.com/wimi321/lizzieyzy-next-foxuid/issues)
