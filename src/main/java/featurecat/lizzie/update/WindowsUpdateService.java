package featurecat.lizzie.update;

import featurecat.lizzie.Lizzie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONObject;

public final class WindowsUpdateService {
  @Deprecated
  public static final String DEFAULT_MANIFEST_URL =
      "https://github.com/wimi321/lizzieyzy-next/releases/latest/download/"
          + "lizzieyzy-next-update-manifest.json";

  public static final String MANIFEST_URL_PROPERTY =
      UpdateManifestClient.LEGACY_MANIFEST_URL_PROPERTY;

  private final UpdateManifestClient manifestClient;
  private final ResumableDownloader downloader;
  private final UpdateChannel channel;

  public WindowsUpdateService() {
    this(UpdateChannel.current(), UpdateSource.current());
  }

  public WindowsUpdateService(UpdateChannel channel, UpdateSource source) {
    this(new UpdateManifestClient(channel, source), new ResumableDownloader(), channel);
  }

  WindowsUpdateService(UpdateManifestClient manifestClient, ResumableDownloader downloader) {
    this(manifestClient, downloader, UpdateChannel.STABLE);
  }

  WindowsUpdateService(
      UpdateManifestClient manifestClient,
      ResumableDownloader downloader,
      UpdateChannel channel) {
    this.manifestClient = manifestClient;
    this.downloader = downloader;
    this.channel = channel == null ? UpdateChannel.STABLE : channel;
  }

  public interface ProgressListener {
    void onProgress(String status, long completedBytes, long totalBytes);
  }

  public Optional<WindowsUpdatePlan> checkForUpdate() throws IOException {
    if (!WindowsUpdatePaths.isWindowsRuntime()) {
      return Optional.empty();
    }
    if (!UpdateAdmission.shouldFetch(Lizzie.nextVersion)) {
      return Optional.empty();
    }
    UpdateAdmission.Result admission =
        UpdateAdmission.evaluateClient(channel, Lizzie.nextVersion, manifestClient);
    if (admission.kind == UpdateAdmission.Kind.ERROR) {
      throw new IOException(admission.message);
    }
    if (admission.kind == UpdateAdmission.Kind.NO_UPDATE || admission.manifest == null) {
      return Optional.empty();
    }
    WindowsUpdatePaths paths = WindowsUpdatePaths.detect();
    InstalledUpdateState installed =
        InstalledUpdateState.read(paths.appDir, Lizzie.nextVersion, paths.flavor);
    WindowsUpdatePlan plan =
        WindowsUpdatePlan.create(admission.manifest, installed, Lizzie.nextVersion, paths.flavor);
    return plan.hasUpdate() ? Optional.of(plan) : Optional.empty();
  }

  static boolean isStableRelease(UpdateManifest manifest) {
    return manifest != null && !manifest.prerelease;
  }

  public UpdateManifest fetchLatestManifest() throws IOException {
    return manifestClient.fetchLatest().manifest;
  }

  public Path downloadAndPrepare(WindowsUpdatePlan plan, ProgressListener listener)
      throws IOException {
    return downloadAndPrepare(
        plan,
        new ResumableDownloader.Control(),
        progress -> {
          if (listener != null) {
            listener.onProgress(
                progress.state.name(), progress.completedBytes, progress.totalBytes);
          }
        });
  }

  public Path downloadAndPrepare(
      WindowsUpdatePlan plan,
      ResumableDownloader.Control control,
      ResumableDownloader.ProgressListener listener)
      throws IOException {
    WindowsUpdatePaths paths = WindowsUpdatePaths.detect();
    List<WindowsUpdatePlan.Item> selected = plan.selectedItems();
    if (selected.isEmpty()) {
      throw new IOException("No update components selected.");
    }
    Path stagingDir = paths.stagingDir(plan.manifest.releaseTag);
    Files.createDirectories(stagingDir);
    Files.copy(
        paths.currentJar,
        paths.helperJarPath(plan.manifest.releaseTag),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);

    long total = plan.selectedSizeBytes();
    long completedBefore = 0L;
    JSONArray requestComponents = new JSONArray();
    for (WindowsUpdatePlan.Item item : selected) {
      UpdateManifest.Component component = item.component;
      Path archive = stagingDir.resolve(component.assetName).normalize();
      long offset = completedBefore;
      downloader.download(
          ResumableDownloader.DownloadSpec.from(component),
          archive,
          control,
          progress -> {
            if (listener != null) {
              long aggregateCompleted = Math.min(total, offset + progress.completedBytes);
              long aggregateEta =
                  progress.bytesPerSecond <= 0L
                      ? progress.estimatedSeconds
                      : Math.max(0L, (total - aggregateCompleted) / progress.bytesPerSecond);
              listener.onProgress(
                  new ResumableDownloader.Progress(
                      progress.state,
                      progress.assetName,
                      progress.sourceName,
                      aggregateCompleted,
                      total,
                      progress.bytesPerSecond,
                      aggregateEta));
            }
          });
      completedBefore += component.sizeBytes;
      requestComponents.put(requestComponent(component, archive, paths));
    }

    JSONObject request = new JSONObject();
    request.put("schemaVersion", UpdateManifest.SUPPORTED_SCHEMA_VERSION);
    request.put("releaseTag", plan.manifest.releaseTag);
    request.put("appDir", paths.appDir.toString());
    request.put("appRoot", paths.appRoot.toString());
    request.put("workDir", paths.workDir.toString());
    request.put("currentJar", paths.currentJar.toString());
    request.put("stagingDir", stagingDir.toString());
    request.put("mainPid", ProcessHandle.current().pid());
    request.put("components", requestComponents);
    request.put("installedManifest", updatedInstalledManifest(plan));
    JSONArray restartCommand = new JSONArray();
    for (String arg : restartCommand(paths)) {
      restartCommand.put(arg);
    }
    request.put("restartCommand", restartCommand);
    Path requestPath = paths.requestPath(plan.manifest.releaseTag);
    Files.writeString(
        requestPath, request.toString(2) + System.lineSeparator(), StandardCharsets.UTF_8);
    if (listener != null) {
      listener.onProgress(
          new ResumableDownloader.Progress(
              ResumableDownloader.State.COMPLETE, "core-update", "", total, total, 0L, 0L));
    }
    return requestPath;
  }

  public Process launchHelper(Path requestPath) throws IOException {
    WindowsUpdatePaths paths = WindowsUpdatePaths.detect();
    Path helperJar =
        paths.helperJarPath(new JSONObject(Files.readString(requestPath)).getString("releaseTag"));
    List<String> command = new ArrayList<>();
    command.add(paths.javaExecutable().toString());
    command.add("-cp");
    command.add(helperJar.toString());
    command.add(WindowsUpdateApplier.class.getName());
    command.add(requestPath.toString());
    if (!paths.portable && !paths.canWriteAppDir()) {
      return launchElevated(command);
    }
    return new ProcessBuilder(command).start();
  }

  private Process launchElevated(List<String> command) throws IOException {
    String executable = command.get(0);
    StringBuilder args = new StringBuilder();
    for (int i = 1; i < command.size(); i++) {
      if (args.length() > 0) {
        args.append(' ');
      }
      args.append('\'').append(command.get(i).replace("'", "''")).append('\'');
    }
    String script =
        "Start-Process -FilePath '"
            + executable.replace("'", "''")
            + "' -ArgumentList \""
            + args.toString().replace("\"", "`\"")
            + "\" -Verb RunAs";
    return new ProcessBuilder(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script)
        .start();
  }

  private List<String> restartCommand(WindowsUpdatePaths paths) throws IOException {
    List<String> command = new ArrayList<>();
    Path exe = findAppExecutable(paths.appRoot);
    if (exe != null) {
      command.add(exe.toString());
    }
    return command;
  }

  private Path findAppExecutable(Path appRoot) throws IOException {
    if (appRoot == null || !Files.isDirectory(appRoot)) {
      return null;
    }
    try (java.util.stream.Stream<Path> stream = Files.list(appRoot)) {
      return stream
          .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe"))
          .findFirst()
          .orElse(null);
    }
  }

  private JSONObject requestComponent(
      UpdateManifest.Component component, Path archive, WindowsUpdatePaths paths) {
    JSONObject json = new JSONObject();
    json.put("id", component.id);
    json.put("assetName", component.assetName);
    json.put("archivePath", archive.toString());
    json.put("installAction", requestInstallAction(component));
    if ("core".equals(component.id)) {
      json.put("sourcePath", "app/" + paths.currentJar.getFileName());
    } else {
      PathMapping mapping = PathMapping.forComponent(component.id);
      json.put("sourcePath", mapping.sourcePath);
      json.put("targetPath", mapping.targetPath);
      json.put("targetBase", mapping.targetBase);
    }
    return json;
  }

  private JSONObject updatedInstalledManifest(WindowsUpdatePlan plan) {
    java.util.LinkedHashMap<String, InstalledUpdateState.ComponentState> components =
        new java.util.LinkedHashMap<>(plan.installedState.components());
    for (WindowsUpdatePlan.Item item : plan.selectedItems()) {
      UpdateManifest.Component component = item.component;
      components.put(
          component.id,
          new InstalledUpdateState.ComponentState(
              component.id, component.version, component.sha256));
    }
    JSONObject json = new JSONObject();
    json.put("schemaVersion", UpdateManifest.SUPPORTED_SCHEMA_VERSION);
    json.put("releaseTag", plan.manifest.releaseTag);
    json.put("platform", "windows");
    json.put("flavor", plan.currentFlavor);
    JSONArray array = new JSONArray();
    for (InstalledUpdateState.ComponentState state : components.values()) {
      JSONObject component = new JSONObject();
      component.put("id", state.id);
      component.put("version", state.version);
      component.put("sha256", state.sha256);
      array.put(component);
    }
    json.put("components", array);
    return json;
  }

  private String requestInstallAction(UpdateManifest.Component component) {
    if ("core".equals(component.id)) {
      return "replace-core";
    }
    if ("replace-app-path".equals(component.installAction)) {
      return component.installAction;
    }
    return "replace-app-path";
  }

  private static final class PathMapping {
    final String sourcePath;
    final String targetPath;
    final String targetBase;

    private PathMapping(String sourcePath, String targetPath) {
      this(sourcePath, targetPath, "appDir");
    }

    private PathMapping(String sourcePath, String targetPath, String targetBase) {
      this.sourcePath = sourcePath;
      this.targetPath = targetPath;
      this.targetBase = targetBase;
    }

    static PathMapping forComponent(String id) {
      switch (id) {
        case "katago-cpu":
        case "katago-opencl":
        case "katago-nvidia":
        case "katago-nvidia50-cuda":
          return new PathMapping("engines/katago/windows-x64", "engines/katago/windows-x64");
        case "weight-default":
          return new PathMapping("weights/default.bin.gz", "weights/default.bin.gz");
        case "readboard":
          return new PathMapping("readboard", "readboard");
        case "jcef":
          return new PathMapping("jcef-bundle", "jcef-bundle");
        case "java-runtime":
          return new PathMapping("runtime", "runtime", "appRoot");
        default:
          throw new IllegalArgumentException("Unsupported update component id: " + id);
      }
    }
  }
}
