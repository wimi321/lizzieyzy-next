# Development Guide

This guide is for people who want to change code, fix packaging, improve docs, or continue maintaining this fork.

If you only want to use the app, start with the [Installation Guide](INSTALL_EN.md), [Troubleshooting](TROUBLESHOOTING_EN.md), and [Package Overview](PACKAGES_EN.md).

## Important Context First

- This is a maintained LizzieYzy fork, not a one-off patch repository.
- The most important user flow is: install the app, launch it, fetch public Fox games through **Fox nickname**, and analyze normally.
- The project does not currently have a full automated test suite. The practical maintenance baseline is local builds, doc checks, and targeted manual verification.

## Building Locally

### Option 1: Use your system Java and Maven

If you already have a working Java + Maven environment, you can build directly:

```bash
mvn -B -DskipTests package
```

### Option 2: Use the bundled tool cache in the repo

Maintainers often use the toolchain already stored in this repository:

- JDK: `.tools/jdk-21/jdk-21.0.10.jdk/Contents/Home`
- Maven: `.tools/apache-maven-3.9.10/bin/mvn`

Example:

```bash
export JAVA_HOME="$PWD/.tools/jdk-21/jdk-21.0.10.jdk/Contents/Home"
export PATH="$PWD/.tools/apache-maven-3.9.10/bin:$JAVA_HOME/bin:$PATH"
mvn -B -DskipTests package
```

Common build outputs include:

- `target/lizzie-yzy2.5.3.jar`
- `target/lizzie-yzy2.5.3-shaded.jar`

## Recommended Local Checks

Before you open a PR, at minimum run:

```bash
python3 scripts/check_markdown_links.py
git diff --check
```

If you changed Java code, run another build:

```bash
mvn -B -DskipTests package
```

If you changed packaging, engine paths, first-launch behavior, or the Fox fetch flow, do the relevant manual verification too.

## Repository Map

### Code directories

- `src/main/java/featurecat/lizzie/gui`
  - main UI, dialogs, windows, interaction logic
- `src/main/java/featurecat/lizzie/analysis`
  - engine integration, analysis flow, remote connections, Fox fetch related logic
  - includes files such as `GetFoxRequest.java` for Fox request handling
- `src/main/java/featurecat/lizzie/rules`
  - board state, move logic, SGF / GIB parsing, game data structures
- `src/main/java/featurecat/lizzie/util`
  - utility helpers
- `src/main/java/featurecat/lizzie/theme`
  - theme-related logic

### Resource directories

- `src/main/resources/l10n`
  - localization files such as `DisplayStrings_zh_CN.properties`
- `src/main/resources/assets`
  - bundled assets, helper resources, embedded tools
- `theme/`
  - theme asset files

### Packaging and release directories

- `scripts/`
  - packaging and helper scripts
- `runtime/`
  - bundled runtime content used for releases
- `engines/`
  - bundled engine files
- `weights/`
  - default weight files
- `dist/`
  - release output related directory

### Documentation and repo config

- `docs/`
  - installation, troubleshooting, maintenance, release, and development docs
- `.github/`
  - CI, issue templates, PR template, release-note config
- `assets/`
  - visual assets for the GitHub project page

## Where To Start For Common Changes

### 1. Fox sync or Fox nickname workflow changes

Start with:

- `src/main/java/featurecat/lizzie/analysis/`
- `src/main/java/featurecat/lizzie/gui/`
- `src/main/resources/l10n/`

Also verify:

- the UI still says `Fox nickname`
- README and install docs do not fall back to old Fox nickname wording
- you perform at least one real Fox fetch check

### 2. UI wording, localization, and menu entries

Start with:

- `src/main/resources/l10n/DisplayStrings*.properties`
- `src/main/java/featurecat/lizzie/gui/`

Also check:

- terminology stays consistent across Chinese and English
- `with-katago`, `nvidia`, and `without.engine` spelling remains unchanged
- user-visible changes are reflected in README or install docs if needed

### 3. Packaging, bundled engine, or release asset changes

Start with:

- `scripts/prepare_bundled_runtime.sh`
- `scripts/prepare_bundled_katago.sh`
- `scripts/package_release.sh`
- `scripts/package_macos_dmg.sh`
- [Release Checklist](RELEASE_CHECKLIST.md)

These changes usually also require updates to:

- `README.md`
- `docs/PACKAGES.md`
- `docs/TESTED_PLATFORMS.md`
- GitHub release notes

## Packaging Script Roles

- `scripts/prepare_bundled_runtime.sh`
  - prepares bundled runtime assets
- `scripts/prepare_bundled_katago.sh`
  - prepares bundled KataGo plus default weight
- `scripts/package_release.sh`
  - builds Windows / Linux / advanced zip packages
- `scripts/package_macos_dmg.sh`
  - builds macOS `.dmg` packages
- `scripts/validate_release_assets.sh`
  - checks that `dist/release/` only contains the public-facing main assets
- `scripts/check_markdown_links.py`
  - validates local markdown links

## Final Pre-PR Checklist

- Did you affect the main user flow: install, launch, Fox nickname fetch, and analysis?
- Did any wording drift back to old Fox nickname terminology?
- Do package names, README guidance, and docs still agree?
- If packaging changed, did you update the related docs and verification records?
- If the UI changed, should you attach a screenshot?

## Recommended Reading

- [Contributing Guide](../CONTRIBUTING.md)
- [Maintenance Notes](MAINTENANCE_EN.md)
- [Release Checklist](RELEASE_CHECKLIST.md)
- [Package Overview](PACKAGES_EN.md)
- [Tested Platforms](TESTED_PLATFORMS.md)
