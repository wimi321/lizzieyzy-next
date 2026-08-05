package featurecat.lizzie.update;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import org.json.JSONObject;

/** Manual full-package updates for macOS and Linux. Windows keeps its in-place core updater. */
public final class PlatformUpdateService {
  public static final String DOWNLOAD_DIR_PROPERTY = "lizzie.update.downloadDir";

  private final UpdateManifestClient manifestClient;
  private final ResumableDownloader downloader;

  public PlatformUpdateService() {
    this(new UpdateManifestClient(), new ResumableDownloader());
  }

  PlatformUpdateService(UpdateManifestClient manifestClient, ResumableDownloader downloader) {
    this.manifestClient = manifestClient;
    this.downloader = downloader;
  }

  public Optional<PackageUpdatePlan> checkForUpdate() throws IOException {
    if (UpdateVersion.shouldSkipAutomaticCheck(Lizzie.nextVersion)) {
      return Optional.empty();
    }
    String platform = currentPlatform();
    if (!"macos".equals(platform) && !"linux".equals(platform)) {
      return Optional.empty();
    }
    UpdateManifest manifest = manifestClient.fetchLatest().manifest;
    if (manifest.prerelease
        || !UpdateVersion.isNewerThan(manifest.releaseTag, Lizzie.nextVersion)) {
      return Optional.empty();
    }
    String arch = currentArch();
    String flavor = currentFlavor(platform);
    UpdateManifest.PackageAsset selected = selectPackage(manifest, platform, arch, flavor);
    if (selected == null) {
      throw new IOException(
          "The stable manifest has no package for " + platform + "/" + arch + "/" + flavor + ".");
    }
    return Optional.of(
        new PackageUpdatePlan(manifest, selected, Lizzie.nextVersion, platform, arch, flavor));
  }

  public Path download(
      PackageUpdatePlan plan,
      ResumableDownloader.Control control,
      ResumableDownloader.ProgressListener listener)
      throws IOException {
    Path destination = downloadDirectory().resolve(plan.packageAsset.assetName);
    return downloader.download(
        ResumableDownloader.DownloadSpec.from(plan.packageAsset), destination, control, listener);
  }

  public void openDownloadedPackage(PackageUpdatePlan plan, Path packagePath) throws IOException {
    if (!Desktop.isDesktopSupported()) {
      throw new IOException("Desktop integration is unavailable.");
    }
    if ("open-dmg".equals(plan.packageAsset.installMode)) {
      Desktop.getDesktop().open(packagePath.toFile());
      return;
    }
    Path parent = packagePath.getParent();
    if (parent == null) {
      throw new IOException("Downloaded package has no containing directory.");
    }
    Desktop.getDesktop().open(parent.toFile());
  }

  static UpdateManifest.PackageAsset selectPackage(
      UpdateManifest manifest, String platform, String arch, String flavor) {
    return manifest.packages.stream()
        .filter(asset -> asset.matches(platform, arch, flavor))
        .sorted(
            Comparator.comparingInt(
                    (UpdateManifest.PackageAsset asset) ->
                        asset.flavor.equals(flavor) ? 0 : ("all".equals(asset.flavor) ? 1 : 2))
                .thenComparingInt(asset -> asset.arch.equals(arch) ? 0 : 1)
                .thenComparing(asset -> asset.assetName))
        .findFirst()
        .orElse(null);
  }

  static String currentPlatform() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("mac")) {
      return "macos";
    }
    if (os.contains("linux")) {
      return "linux";
    }
    if (os.contains("win")) {
      return "windows";
    }
    return "unsupported";
  }

  static String currentArch() {
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    if (arch.contains("aarch64") || arch.contains("arm64")) {
      return "arm64";
    }
    if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) {
      return "x64";
    }
    return arch.replaceAll("[^a-z0-9._-]", "");
  }

  static String currentFlavor(String platform) {
    String override = System.getProperty("lizzie.update.flavor", "").trim();
    if (!override.isEmpty()) {
      return override.toLowerCase(Locale.ROOT);
    }
    if ("macos".equals(platform)) {
      return "with-katago";
    }
    Path appRoot = detectAppRoot();
    String manifestFlavor = installedFlavor(appRoot);
    if (!manifestFlavor.isEmpty()) {
      return manifestFlavor;
    }
    String backend = engineBackend(appRoot);
    if (!backend.isEmpty()) {
      return backend;
    }
    return containsKataGo(appRoot) ? "with-katago" : "without.engine";
  }

  private static Path downloadDirectory() throws IOException {
    String override = System.getProperty(DOWNLOAD_DIR_PROPERTY, "").trim();
    Path directory;
    if (!override.isEmpty()) {
      directory = Path.of(override);
    } else {
      Path downloads = Path.of(System.getProperty("user.home", "."), "Downloads");
      directory =
          Files.isDirectory(downloads)
              ? downloads
              : Config.resolvedWorkDirPath().resolve("update/downloads");
    }
    Files.createDirectories(directory);
    return directory.toAbsolutePath().normalize();
  }

  private static Path detectAppRoot() {
    try {
      Path jar = WindowsUpdatePaths.detectCurrentJar();
      if (jar != null) {
        Path parent = jar.getParent();
        if (parent != null
            && parent.getFileName() != null
            && "app".equalsIgnoreCase(parent.getFileName().toString())) {
          return parent.getParent() == null ? parent : parent.getParent();
        }
        return parent == null ? Config.resolvedWorkDirPath() : parent;
      }
    } catch (IOException ignored) {
    }
    return Config.resolvedWorkDirPath();
  }

  private static String installedFlavor(Path appRoot) {
    for (Path candidate :
        new Path[] {
          appRoot.resolve("app").resolve(InstalledUpdateState.INSTALLED_MANIFEST_NAME),
          appRoot.resolve(InstalledUpdateState.INSTALLED_MANIFEST_NAME)
        }) {
      if (!Files.isRegularFile(candidate)) {
        continue;
      }
      try {
        return new JSONObject(Files.readString(candidate, StandardCharsets.UTF_8))
            .optString("flavor", "")
            .trim()
            .toLowerCase(Locale.ROOT);
      } catch (Exception ignored) {
      }
    }
    return "";
  }

  private static String engineBackend(Path appRoot) {
    Path engines = appRoot.resolve("app/engines/katago");
    if (!Files.isDirectory(engines)) {
      engines = appRoot.resolve("engines/katago");
    }
    if (!Files.isDirectory(engines)) {
      return "";
    }
    try (java.util.stream.Stream<Path> paths = Files.walk(engines, 3)) {
      Optional<Path> marker =
          paths
              .filter(
                  path ->
                      path.getFileName() != null
                          && "lizzieyzy-next-engine-backend.txt"
                              .equals(path.getFileName().toString()))
              .findFirst();
      if (marker.isEmpty()) {
        return "";
      }
      String value =
          Files.readString(marker.get(), StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
      if ("cpu".equals(value)) {
        return "with-katago";
      }
      if ("nvidia50-cuda".equals(value)) {
        return "nvidia50.cuda";
      }
      return value;
    } catch (IOException ignored) {
      return "";
    }
  }

  private static boolean containsKataGo(Path appRoot) {
    for (Path engines :
        new Path[] {appRoot.resolve("app/engines/katago"), appRoot.resolve("engines/katago")}) {
      if (!Files.isDirectory(engines)) {
        continue;
      }
      try (java.util.stream.Stream<Path> paths = Files.walk(engines, 4)) {
        if (paths.anyMatch(
            path -> {
              String name =
                  path.getFileName() == null
                      ? ""
                      : path.getFileName().toString().toLowerCase(Locale.ROOT);
              return Files.isRegularFile(path)
                  && ("katago".equals(name) || "katago.exe".equals(name));
            })) {
          return true;
        }
      } catch (IOException ignored) {
      }
    }
    return false;
  }
}
