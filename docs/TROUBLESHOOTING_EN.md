# Troubleshooting

This guide covers the most common issues in the maintained fork: blocked app launch, missing Java, missing KataGo, and Fox sync returning no games.

## macOS says the app cannot be opened

Make sure you downloaded the correct build first:

- Apple Silicon: `mac-arm64.with-katago.dmg`
- Intel: `mac-amd64.with-katago.dmg`

Current macOS builds are unsigned / not notarized maintenance packages. If Gatekeeper blocks the first launch:

1. Try opening the app once.
2. Open `System Settings -> Privacy & Security`.
3. Click `Open Anyway`.
4. Launch the app again.

## Windows or Linux says Java is missing

Check which package you downloaded:

- `windows64.with-katago.zip`: Java is usually bundled
- `windows64.without.engine.zip`: Java is usually bundled
- `linux64.with-katago.zip`: Java is usually bundled
- `windows32.without.engine.zip`: Java is not bundled
- `Macosx.amd64.Linux.amd64.without.engine.zip`: Java is not bundled

For the last two package types, install Java 11+ yourself.

## The app opens but KataGo is not detected

`with-katago` packages are designed to auto-detect the bundled engine when engine files, weights, and configs are present together.

Check these first:

1. You did not accidentally download a `without.engine` package.
2. The archive was fully extracted.
3. `weights/default.bin.gz` and `engines/katago/` still exist.
4. You are launching from the full extracted folder, not just a copied `.jar` file.

## Fox sync returns no games

Please confirm:

1. You entered a **numeric Fox ID**, not a username.
2. That account has recent public games.
3. Your network is working.
4. The Fox API is not temporarily unstable.

## Fox ID format error

If the app says only numeric Fox IDs are supported, the input format is wrong.

Correct example:

- `12345678`

Incorrect examples:

- `some_username`
- `fox_123`
- `123abc`

## Useful links

- [Installation Guide](INSTALL_EN.md)
- [Package Overview](PACKAGES_EN.md)
- [Issues](https://github.com/wimi321/lizzieyzy-next-foxuid/issues)
