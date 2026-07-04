package featurecat.lizzie.update;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.jdesktop.swingx.util.OS;

public final class WindowsUpdatePaths {
  public static final String APP_ROOT_PROPERTY = "lizzie.update.appRoot";
  public static final String APP_DIR_PROPERTY = "lizzie.update.appDir";
  public static final String WORK_DIR_PROPERTY = "lizzie.update.workDir";
  public static final String CURRENT_JAR_PROPERTY = "lizzie.update.currentJar";

  private static final String PORTABLE_MARKER_NAME = ".lizzie-portable";
  private static final String ENGINE_BACKEND_MARKER_NAME = "lizzieyzy-next-engine-backend.txt";

  public final Path appRoot;
  public final Path appDir;
  public final Path workDir;
  public final Path currentJar;
  public final boolean portable;
  public final String flavor;

  WindowsUpdatePaths(
      Path appRoot, Path appDir, Path workDir, Path currentJar, boolean portable, String flavor) {
    this.appRoot = appRoot;
    this.appDir = appDir;
    this.workDir = workDir;
    this.currentJar = currentJar;
    this.portable = portable;
    this.flavor = flavor;
  }

  public static boolean isWindowsRuntime() {
    return OS.isWindows() || Boolean.getBoolean("lizzie.update.forceWindowsForTests");
  }

  public static WindowsUpdatePaths detect() throws IOException {
    Path currentJar = overridePath(CURRENT_JAR_PROPERTY);
    if (currentJar == null) {
      currentJar = detectCurrentJar();
    }
    if (currentJar == null || !Files.isRegularFile(currentJar)) {
      throw new IOException("Cannot locate packaged LizzieYzy jar for update.");
    }
    currentJar = currentJar.toAbsolutePath().normalize();

    Path appDir = overridePath(APP_DIR_PROPERTY);
    if (appDir == null) {
      appDir = currentJar.getParent();
    }
    appDir = appDir.toAbsolutePath().normalize();

    Path appRoot = overridePath(APP_ROOT_PROPERTY);
    if (appRoot == null) {
      appRoot =
          appDir.getFileName() != null && "app".equalsIgnoreCase(appDir.getFileName().toString())
              ? appDir.getParent()
              : appDir;
    }
    appRoot = appRoot.toAbsolutePath().normalize();

    Path workDir = overridePath(WORK_DIR_PROPERTY);
    if (workDir == null) {
      workDir = Config.resolvedWorkDirPath();
    }
    workDir = workDir.toAbsolutePath().normalize();

    boolean portable = Files.isRegularFile(appRoot.resolve(PORTABLE_MARKER_NAME));
    String flavor = detectFlavor(appDir);
    return new WindowsUpdatePaths(appRoot, appDir, workDir, currentJar, portable, flavor);
  }

  public Path updateRoot() {
    return workDir.resolve("update").toAbsolutePath().normalize();
  }

  public Path stagingDir(String releaseTag) {
    return updateRoot().resolve("staging").resolve(safeName(releaseTag)).normalize();
  }

  public Path requestPath(String releaseTag) {
    return stagingDir(releaseTag).resolve("update-request.json");
  }

  public Path helperJarPath(String releaseTag) {
    return stagingDir(releaseTag).resolve("lizzieyzy-next-updater-helper.jar");
  }

  public Path javaExecutable() {
    Path bundledJava = appRoot.resolve("runtime").resolve("bin").resolve("java.exe");
    if (Files.isRegularFile(bundledJava)) {
      return bundledJava;
    }
    return Path.of("java");
  }

  public boolean canWriteAppDir() {
    try {
      Files.createDirectories(appDir);
      Path probe = appDir.resolve(".lizzie-update-write-test");
      Files.writeString(probe, "ok", StandardCharsets.UTF_8);
      Files.deleteIfExists(probe);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  static String safeName(String value) {
    String trimmed = value == null ? "unknown" : value.trim();
    return trimmed.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  static Path overridePath(String property) {
    String value = System.getProperty(property, "").trim();
    if (value.isEmpty()) {
      return null;
    }
    return Path.of(value);
  }

  static Path detectCurrentJar() throws IOException {
    try {
      File source = new File(Lizzie.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path path = source.toPath().toAbsolutePath().normalize();
      return Files.isRegularFile(path) ? path : null;
    } catch (URISyntaxException e) {
      throw new IOException("Cannot inspect application code source.", e);
    }
  }

  static String detectFlavor(Path appDir) {
    Path installedManifest = appDir.resolve(InstalledUpdateState.INSTALLED_MANIFEST_NAME);
    if (Files.isRegularFile(installedManifest)) {
      try {
        String raw = Files.readString(installedManifest, StandardCharsets.UTF_8);
        String flavor = new org.json.JSONObject(raw).optString("flavor", "").trim();
        if (!flavor.isEmpty()) {
          return flavor.toLowerCase(Locale.ROOT);
        }
      } catch (Exception ignored) {
      }
    }

    Path backendMarker =
        appDir.resolve("engines")
            .resolve("katago")
            .resolve("windows-x64")
            .resolve(ENGINE_BACKEND_MARKER_NAME);
    if (Files.isRegularFile(backendMarker)) {
      try {
        String backend = Files.readString(backendMarker, StandardCharsets.UTF_8).trim();
        if ("opencl".equalsIgnoreCase(backend)) {
          return "opencl";
        }
        if ("nvidia".equalsIgnoreCase(backend)) {
          return "nvidia";
        }
        if ("nvidia50-cuda".equalsIgnoreCase(backend)) {
          return "nvidia50.cuda";
        }
        if ("cpu".equalsIgnoreCase(backend)) {
          return "with-katago";
        }
      } catch (IOException ignored) {
      }
    }
    if (Files.isDirectory(appDir.resolve("engines"))) {
      return "with-katago";
    }
    return "without.engine";
  }
}
