package featurecat.lizzie.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

public final class WindowsUpdateApplier {
  private WindowsUpdateApplier() {}

  public static void main(String[] args) {
    if (args.length < 1) {
      System.err.println("Usage: WindowsUpdateApplier <update-request.json>");
      System.exit(2);
    }
    try {
      apply(Path.of(args[0]));
    } catch (Exception e) {
      e.printStackTrace(System.err);
      System.exit(1);
    }
  }

  public static void apply(Path requestPath) throws IOException, InterruptedException {
    JSONObject request = new JSONObject(Files.readString(requestPath, StandardCharsets.UTF_8));
    Path appDir = canonicalPath(request.getString("appDir"));
    Path appRoot = canonicalPath(request.optString("appRoot", appDir.toString()));
    Path currentJar = canonicalPath(request.getString("currentJar"));
    Path stagingDir = canonicalPath(request.getString("stagingDir"));
    Path backupRoot =
        canonicalPath(
            stagingDir.resolve("backup-" + Instant.now().toString().replace(':', '-')).toString());
    long mainPid = request.optLong("mainPid", -1L);
    waitForProcessExit(mainPid, 30000L);

    Files.createDirectories(backupRoot);
    List<BackupEntry> backups = new ArrayList<>();
    try {
      JSONArray components = request.getJSONArray("components");
      for (int i = 0; i < components.length(); i++) {
        JSONObject component = components.getJSONObject(i);
        String action = component.getString("installAction");
        Path archive = canonicalPath(component.getString("archivePath"));
        Path extracted = stagingDir.resolve("extract-" + i).normalize();
        deleteIfExists(extracted);
        extractZip(archive, extracted);
        if ("replace-core".equals(action)) {
          replaceCore(component, extracted, appDir, currentJar, backupRoot, backups);
        } else if ("replace-app-path".equals(action)) {
          replaceAppPath(component, extracted, appDir, appRoot, backupRoot, backups);
        } else {
          throw new IOException("Unsupported update install action: " + action);
        }
      }
      writeInstalledManifestIfPresent(request, appDir, backupRoot, backups);
      writeAppliedMarker(stagingDir, request);
      restartIfRequested(request);
    } catch (Exception e) {
      rollback(backups);
      if (e instanceof IOException) {
        throw (IOException) e;
      }
      if (e instanceof InterruptedException) {
        throw (InterruptedException) e;
      }
      throw new IOException("Update apply failed.", e);
    }
  }

  private static void replaceCore(
      JSONObject component,
      Path extracted,
      Path appDir,
      Path currentJar,
      Path backupRoot,
      List<BackupEntry> backups)
      throws IOException {
    Path newJar = extracted.resolve(component.optString("sourcePath", currentJar.getFileName().toString()));
    if (!Files.isRegularFile(newJar)) {
      throw new IOException("Core update jar not found: " + newJar);
    }
    replaceFile(newJar, currentJar, backupRoot, appDir, backups);
    replaceBundledLauncherConfigsIfPresent(extracted, appDir, backupRoot, backups);
  }

  private static void replaceBundledLauncherConfigsIfPresent(
      Path extracted, Path appDir, Path backupRoot, List<BackupEntry> backups) throws IOException {
    Path extractedAppDir = extracted.resolve("app").normalize();
    if (!Files.isDirectory(extractedAppDir)) {
      return;
    }
    try (Stream<Path> stream = Files.list(extractedAppDir)) {
      List<Path> configs =
          stream
              .filter(Files::isRegularFile)
              .filter(
                  path ->
                      path.getFileName()
                          .toString()
                          .toLowerCase(Locale.ROOT)
                          .endsWith(".cfg"))
              .toList();
      for (Path config : configs) {
        replaceFile(config, appDir.resolve(config.getFileName()), backupRoot, appDir, backups);
      }
    }
  }

  private static void writeInstalledManifestIfPresent(
      JSONObject request, Path appDir, Path backupRoot, List<BackupEntry> backups)
      throws IOException {
    JSONObject installedManifest = request.optJSONObject("installedManifest");
    if (installedManifest == null) {
      return;
    }
    Path target = appDir.resolve(InstalledUpdateState.INSTALLED_MANIFEST_NAME);
    backupTarget(target, backupRoot, appDir, backups);
    Files.createDirectories(target.getParent());
    Files.writeString(
        target, installedManifest.toString(2) + System.lineSeparator(), StandardCharsets.UTF_8);
  }

  private static void replaceAppPath(
      JSONObject component,
      Path extracted,
      Path appDir,
      Path appRoot,
      Path backupRoot,
      List<BackupEntry> backups)
      throws IOException {
    String sourcePath = component.getString("sourcePath");
    String targetPath = component.getString("targetPath");
    Path source = extracted.resolve(sourcePath).normalize();
    Path targetBase =
        "appRoot".equals(component.optString("targetBase", "appDir")) ? appRoot : appDir;
    Path target = safeTarget(targetBase, targetPath);
    if (!Files.exists(source)) {
      throw new IOException("Update source path not found: " + source);
    }
    if (Files.isDirectory(source)) {
      replaceDirectory(source, target, backupRoot, targetBase, backups);
    } else {
      replaceFile(source, target, backupRoot, targetBase, backups);
    }
  }

  private static void replaceFile(
      Path source, Path target, Path backupRoot, Path appDir, List<BackupEntry> backups)
      throws IOException {
    target = safeExistingOrFutureAppTarget(appDir, target);
    backupTarget(target, backupRoot, appDir, backups);
    Files.createDirectories(target.getParent());
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
  }

  private static void replaceDirectory(
      Path source, Path target, Path backupRoot, Path appDir, List<BackupEntry> backups)
      throws IOException {
    target = safeExistingOrFutureAppTarget(appDir, target);
    backupTarget(target, backupRoot, appDir, backups);
    copyDirectory(source, target);
  }

  private static void backupTarget(
      Path target, Path backupRoot, Path appDir, List<BackupEntry> backups) throws IOException {
    target = safeExistingOrFutureAppTarget(appDir, target);
    if (!Files.exists(target)) {
      backups.add(new BackupEntry(target, null));
      return;
    }
    Path relative = appDir.relativize(target);
    Path backup = backupRoot.resolve(relative).normalize();
    Files.createDirectories(backup.getParent());
    Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
    backups.add(new BackupEntry(target, backup));
  }

  private static void rollback(List<BackupEntry> backups) {
    for (int i = backups.size() - 1; i >= 0; i--) {
      BackupEntry entry = backups.get(i);
      try {
        if (Files.exists(entry.target)) {
          deleteIfExists(entry.target);
        }
        if (entry.backup != null && Files.exists(entry.backup)) {
          Files.createDirectories(entry.target.getParent());
          Files.move(entry.backup, entry.target, StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (IOException rollbackError) {
        rollbackError.printStackTrace(System.err);
      }
    }
  }

  private static void copyDirectory(Path source, Path target) throws IOException {
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(target.resolve(source.relativize(dir)).normalize());
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Path destination = target.resolve(source.relativize(file)).normalize();
            Files.createDirectories(destination.getParent());
            Files.copy(
                file,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void extractZip(Path archive, Path destination) throws IOException {
    Files.createDirectories(destination);
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        Path output = destination.resolve(entry.getName()).normalize();
        if (!output.startsWith(destination)) {
          throw new IOException("Blocked unsafe zip entry: " + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(output);
        } else {
          Files.createDirectories(output.getParent());
          Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private static Path safeTarget(Path baseDir, String relativePath) throws IOException {
    if (relativePath == null || relativePath.trim().isEmpty()) {
      throw new IOException("Update target path is empty.");
    }
    Path target = baseDir.resolve(relativePath).normalize();
    return safeExistingOrFutureAppTarget(baseDir, target);
  }

  private static Path safeExistingOrFutureAppTarget(Path appDir, Path target) throws IOException {
    Path normalized = target.toAbsolutePath().normalize();
    if (!normalized.startsWith(appDir.toAbsolutePath().normalize())) {
      throw new IOException("Update target escapes app directory: " + target);
    }
    return normalized;
  }

  private static Path canonicalPath(String raw) throws IOException {
    return Path.of(raw).toAbsolutePath().normalize();
  }

  private static void waitForProcessExit(long pid, long timeoutMs)
      throws IOException, InterruptedException {
    if (pid <= 0L || pid == ProcessHandle.current().pid()) {
      return;
    }
    Optional<ProcessHandle> handle = ProcessHandle.of(pid);
    if (handle.isEmpty()) {
      return;
    }
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (handle.get().isAlive()) {
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0L) {
        throw new IOException("Main process did not exit before update timeout.");
      }
      Thread.sleep(Math.min(500L, remaining));
    }
  }

  private static void restartIfRequested(JSONObject request) throws IOException {
    JSONArray command = request.optJSONArray("restartCommand");
    if (command == null || command.isEmpty()) {
      return;
    }
    List<String> args = new ArrayList<>();
    for (int i = 0; i < command.length(); i++) {
      String value = command.optString(i, "").trim();
      if (!value.isEmpty()) {
        args.add(value);
      }
    }
    if (!args.isEmpty()) {
      new ProcessBuilder(args).start();
    }
  }

  private static void writeAppliedMarker(Path stagingDir, JSONObject request) throws IOException {
    JSONObject marker = new JSONObject();
    marker.put("appliedAt", Instant.now().toString());
    marker.put("releaseTag", request.optString("releaseTag"));
    Files.writeString(
        stagingDir.resolve("applied.json"),
        marker.toString(2) + System.lineSeparator(),
        StandardCharsets.UTF_8);
  }

  private static void deleteIfExists(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    if (Files.isDirectory(path)) {
      try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
        List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
        for (Path item : paths) {
          Files.deleteIfExists(item);
        }
      }
    } else {
      Files.deleteIfExists(path);
    }
  }

  private static final class BackupEntry {
    final Path target;
    final Path backup;

    BackupEntry(Path target, Path backup) {
      this.target = target;
      this.backup = backup;
    }
  }
}
