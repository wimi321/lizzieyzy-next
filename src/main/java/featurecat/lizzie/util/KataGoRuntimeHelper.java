package featurecat.lizzie.util;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadCancelledException;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadSession;
import featurecat.lizzie.util.KataGoAutoSetupHelper.ProgressListener;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.jdesktop.swingx.util.OS;
import org.json.JSONObject;

public final class KataGoRuntimeHelper {
  private static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";
  private static final String NVIDIA_ENGINE_DIR = "windows-x64-nvidia";
  private static final String ENGINE_BACKEND_MARKER_NAME = "lizzieyzy-next-engine-backend.txt";
  private static final String NVIDIA_RUNTIME_ROOT = "nvidia-runtime";
  private static final String CUDA_MANIFEST_URL =
      "https://developer.download.nvidia.com/compute/cuda/redist/redistrib_12.1.1.json";
  private static final String CUDNN_MANIFEST_URL =
      "https://developer.download.nvidia.com/compute/cudnn/redist/redistrib_8.9.7.29.json";
  private static final Pattern BENCHMARK_RECOMMENDED_PATTERN =
      Pattern.compile("numSearchThreads\\s*=\\s*(\\d+):.*\\(recommended\\)");
  private static final Pattern BENCHMARK_CURRENT_PATTERN =
      Pattern.compile("Your GTP config is currently set to use numSearchThreads\\s*=\\s*(\\d+)");
  private static final Pattern BENCHMARK_BACKEND_PATTERN =
      Pattern.compile("You are currently using the (.+?) version of KataGo\\.");
  private static final Pattern BENCHMARK_SUMMARY_LINE_PATTERN =
      Pattern.compile("^numSearchThreads\\s*=\\s*\\d+:.*$");
  private static final List<List<String>> REQUIRED_RUNTIME_DLL_GROUPS =
      Arrays.asList(
          Arrays.asList("cudart64_12.dll"),
          Arrays.asList("cublas64_12.dll"),
          Arrays.asList("cublasLt64_12.dll"),
          Arrays.asList("cudnn64_8.dll"),
          Arrays.asList("nvJitLink*.dll"),
          Arrays.asList("zlibwapi.dll", "libz.dll"));
  private static final Object NVIDIA_RUNTIME_LOCK = new Object();
  private static final int BENCHMARK_VISITS = 120;
  private static final int BENCHMARK_POSITIONS = 4;
  private static final int BENCHMARK_MIN_TIME_SECONDS = 2;
  private static final int BENCHMARK_MAX_TIME_SECONDS = 8;

  private KataGoRuntimeHelper() {}

  public static final class NvidiaRuntimeStatus {
    public final boolean applicable;
    public final boolean ready;
    public final Path enginePath;
    public final Path runtimeDir;
    public final List<String> missingDlls;
    public final long downloadBytes;
    public final String detailText;

    private NvidiaRuntimeStatus(
        boolean applicable,
        boolean ready,
        Path enginePath,
        Path runtimeDir,
        List<String> missingDlls,
        long downloadBytes,
        String detailText) {
      this.applicable = applicable;
      this.ready = ready;
      this.enginePath = enginePath;
      this.runtimeDir = runtimeDir;
      this.missingDlls = missingDlls;
      this.downloadBytes = downloadBytes;
      this.detailText = detailText;
    }
  }

  public static final class BenchmarkResult {
    public final int recommendedThreads;
    public final int currentThreads;
    public final String backendLabel;
    public final String summary;
    public final long completedAtMillis;

    private BenchmarkResult(
        int recommendedThreads,
        int currentThreads,
        String backendLabel,
        String summary,
        long completedAtMillis) {
      this.recommendedThreads = recommendedThreads;
      this.currentThreads = currentThreads;
      this.backendLabel = backendLabel;
      this.summary = summary;
      this.completedAtMillis = completedAtMillis;
    }
  }

  private static final class RuntimePackageSpec {
    private final String displayName;
    private final String version;
    private final String url;
    private final String sha256;
    private final long sizeBytes;
    private final String key;

    private RuntimePackageSpec(
        String displayName, String version, String url, String sha256, long sizeBytes, String key) {
      this.displayName = displayName;
      this.version = version;
      this.url = url;
      this.sha256 = sha256;
      this.sizeBytes = sizeBytes;
      this.key = key;
    }

    private String fileName() {
      int slash = url.lastIndexOf('/');
      return slash >= 0 ? url.substring(slash + 1) : key + ".zip";
    }
  }

  private static final class BootstrapDialog extends JDialog {
    private final JLabel statusLabel = new JLabel();
    private final JProgressBar progressBar = new JProgressBar();
    private final javax.swing.JButton cancelButton = new javax.swing.JButton();
    private long firstMeasuredAtMillis = 0L;

    private BootstrapDialog(Window owner, DownloadSession session) {
      super(owner);
      setModal(true);
      setTitle(resource("AutoSetup.nvidiaBootstrapTitle", "Preparing NVIDIA acceleration"));
      setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
      setResizable(false);

      JPanel content = new JPanel(new BorderLayout(0, 10));
      content.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
      setContentPane(content);

      JLabel description =
          new JLabel(
              "<html>"
                  + resource(
                          "AutoSetup.nvidiaBootstrapDescription",
                          "LizzieYzy Next is checking the bundled NVIDIA files in your package."
                              + " If files are missing, reinstall the NVIDIA package.")
                      .replace("\n", "<br>")
                  + "</html>");
      content.add(description, BorderLayout.NORTH);

      statusLabel.setText(
          resource("AutoSetup.installingNvidiaRuntime", "Preparing NVIDIA runtime..."));
      content.add(statusLabel, BorderLayout.CENTER);

      JPanel southPanel = new JPanel(new BorderLayout(0, 10));
      progressBar.setStringPainted(true);
      progressBar.setIndeterminate(true);
      southPanel.add(progressBar, BorderLayout.CENTER);

      JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
      cancelButton.setText(resource("AutoSetup.stopDownload", "Stop download"));
      cancelButton.addActionListener(e -> session.cancel());
      buttonPanel.add(cancelButton);
      southPanel.add(buttonPanel, BorderLayout.SOUTH);
      content.add(southPanel, BorderLayout.SOUTH);

      setMinimumSize(new Dimension(560, 170));
      pack();
      setLocationRelativeTo(owner);
    }

    private void updateProgress(String statusText, long downloadedBytes, long totalBytes) {
      statusLabel.setText(statusText);
      if (totalBytes > 0) {
        long now = System.currentTimeMillis();
        if (downloadedBytes > 0 && firstMeasuredAtMillis <= 0L) {
          firstMeasuredAtMillis = now;
        }
        progressBar.setIndeterminate(false);
        progressBar.setMaximum(1000);
        progressBar.setValue((int) Math.min(1000, (downloadedBytes * 1000L) / totalBytes));
        String etaText = "";
        if (firstMeasuredAtMillis > 0L && downloadedBytes > 0 && downloadedBytes < totalBytes) {
          long elapsedMillis = Math.max(1000L, now - firstMeasuredAtMillis);
          long bytesPerSecond = Math.max(1L, (downloadedBytes * 1000L) / elapsedMillis);
          long remainingMillis =
              Math.max(0L, ((totalBytes - downloadedBytes) * 1000L) / bytesPerSecond);
          etaText = "  ETA " + formatDuration(remainingMillis);
        }
        progressBar.setString(
            statusText
                + "  "
                + Math.min(100, (downloadedBytes * 100L) / totalBytes)
                + "%  "
                + formatBytes(downloadedBytes)
                + " / "
                + formatBytes(totalBytes)
                + etaText);
      } else if (downloadedBytes > 0) {
        progressBar.setIndeterminate(true);
        progressBar.setString(statusText + "  " + formatBytes(downloadedBytes));
      } else {
        progressBar.setIndeterminate(true);
        progressBar.setString(statusText);
      }
    }
  }

  public static Path resolveCommandExecutable(List<String> commands) {
    if (commands == null || commands.isEmpty()) {
      return null;
    }
    String executable = commands.get(0);
    if (executable == null || executable.trim().isEmpty()) {
      return null;
    }
    Path resolved = Utils.resolveExistingExecutable(executable);
    if (resolved != null) {
      return resolved.toAbsolutePath().normalize();
    }
    try {
      Path direct = Paths.get(executable);
      if (!direct.isAbsolute()) {
        direct = direct.toAbsolutePath();
      }
      return direct.normalize();
    } catch (Exception e) {
      return null;
    }
  }

  public static boolean isNvidiaBundledPath(Path enginePath) {
    if (enginePath == null) {
      return false;
    }
    String normalized = enginePath.toAbsolutePath().normalize().toString().replace('\\', '/');
    if (normalized.toLowerCase(Locale.ROOT).contains("/" + NVIDIA_ENGINE_DIR + "/")) {
      return true;
    }
    Path engineDir = enginePath.toAbsolutePath().normalize().getParent();
    if (engineDir == null) {
      return false;
    }
    Path markerPath = engineDir.resolve(ENGINE_BACKEND_MARKER_NAME);
    if (!Files.isRegularFile(markerPath)) {
      return false;
    }
    try {
      String backend = Files.readString(markerPath, StandardCharsets.UTF_8).trim();
      return "nvidia".equalsIgnoreCase(backend);
    } catch (IOException e) {
      return false;
    }
  }

  public static void ensureBundledRuntimeReady(Path enginePath, Window owner) throws IOException {
    NvidiaRuntimeStatus status = inspectNvidiaRuntime(enginePath);
    if (!status.applicable || status.ready) {
      return;
    }
    throw new IOException(buildMissingRuntimeMessage(status));
  }

  public static void configureBundledProcessBuilder(
      ProcessBuilder processBuilder, Path enginePath) {
    if (processBuilder == null || enginePath == null) {
      return;
    }
    if (Lizzie.config != null) {
      processBuilder.directory(Lizzie.config.getRuntimeWorkDirectory());
    }
    Path engineDir = enginePath.getParent();
    if (engineDir == null) {
      return;
    }
    prependPath(processBuilder, engineDir);
    if (OS.isWindows() && isNvidiaBundledPath(enginePath)) {
      Path runtimeDir = getNvidiaRuntimeDir();
      if (Files.isDirectory(runtimeDir)) {
        prependPath(processBuilder, runtimeDir);
      }
    }
  }

  public static NvidiaRuntimeStatus inspectNvidiaRuntime(SetupSnapshot snapshot) {
    return inspectNvidiaRuntime(snapshot == null ? null : snapshot.enginePath);
  }

  public static NvidiaRuntimeStatus inspectNvidiaRuntime(Path enginePath) {
    Path runtimeDir = getNvidiaRuntimeDir();
    if (!OS.isWindows() || !isNvidiaBundledPath(enginePath)) {
      return new NvidiaRuntimeStatus(
          false,
          false,
          enginePath,
          runtimeDir,
          new ArrayList<String>(),
          0L,
          resource(
              "AutoSetup.nvidiaRuntimeNotApplicable",
              "Current engine does not need the NVIDIA runtime."));
    }

    List<Path> searchDirs = collectRuntimeSearchDirs(enginePath, runtimeDir);
    List<String> missing = collectMissingRuntimeGroups(searchDirs);
    Path readyDir = findDirectoryContainingRequiredDlls(searchDirs);
    boolean ready = readyDir != null;
    String detailText;
    if (ready) {
      detailText =
          resource("AutoSetup.nvidiaRuntimeReady", "Ready")
              + "  |  "
              + readyDir.toAbsolutePath().normalize();
    } else {
      detailText =
          resource(
                  "AutoSetup.nvidiaRuntimeMissing",
                  "Bundled NVIDIA runtime files are missing. Please reinstall the NVIDIA package.")
              + "  |  "
              + String.join(", ", missing);
    }
    return new NvidiaRuntimeStatus(true, ready, enginePath, runtimeDir, missing, 0L, detailText);
  }

  public static void downloadAndInstallNvidiaRuntime(
      Path enginePath, ProgressListener listener, DownloadSession session) throws IOException {
    NvidiaRuntimeStatus status = inspectNvidiaRuntime(enginePath);
    if (!status.applicable) {
      return;
    }
    if (status.ready) {
      if (listener != null) {
        listener.onProgress(resource("AutoSetup.nvidiaRuntimeReady", "Ready"), 0L, 0L);
      }
      return;
    }
    if (listener != null) {
      listener.onProgress(
          resource("AutoSetup.installingNvidiaRuntime", "Checking bundled NVIDIA files..."),
          0L,
          0L);
    }
    throw new IOException(buildMissingRuntimeMessage(status));
  }

  public static BenchmarkResult getStoredBenchmarkResult() {
    if (Lizzie.config == null || Lizzie.config.uiConfig == null) {
      return null;
    }
    int recommended = Lizzie.config.uiConfig.optInt("katago-benchmark-threads", 0);
    if (recommended <= 0) {
      return null;
    }
    return new BenchmarkResult(
        recommended,
        Lizzie.config.uiConfig.optInt("katago-benchmark-current-threads", 0),
        Lizzie.config.uiConfig.optString("katago-benchmark-backend", "").trim(),
        Lizzie.config.uiConfig.optString("katago-benchmark-summary", "").trim(),
        Lizzie.config.uiConfig.optLong("katago-benchmark-updated-at", 0L));
  }

  public static BenchmarkResult runBenchmark(
      SetupSnapshot snapshot, ProgressListener listener, DownloadSession session)
      throws IOException {
    if (snapshot == null) {
      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    }
    if (snapshot == null
        || snapshot.enginePath == null
        || !Files.isRegularFile(snapshot.enginePath)) {
      throw new IOException(
          resource("AutoSetup.missingEngine", "No local KataGo binary was found."));
    }
    if (snapshot.gtpConfigPath == null || !Files.isRegularFile(snapshot.gtpConfigPath)) {
      throw new IOException(
          resource("AutoSetup.missingConfig", "No local KataGo config file was found."));
    }
    if (snapshot.activeWeightPath == null || !Files.isRegularFile(snapshot.activeWeightPath)) {
      throw new IOException(
          resource("AutoSetup.missingWeight", "No local KataGo weight file was found."));
    }

    DownloadSession activeSession = session != null ? session : new DownloadSession();
    if (OS.isWindows() && isNvidiaBundledPath(snapshot.enginePath)) {
      ensureBundledRuntimeReady(snapshot.enginePath, null);
    }

    int benchmarkTime = resolveBenchmarkTimeSeconds();
    List<String> command = new ArrayList<String>();
    command.add(snapshot.enginePath.toAbsolutePath().normalize().toString());
    command.add("benchmark");
    command.add("-config");
    command.add(snapshot.gtpConfigPath.toAbsolutePath().normalize().toString());
    command.add("-model");
    command.add(snapshot.activeWeightPath.toAbsolutePath().normalize().toString());
    command.add("-s");
    command.add("-n");
    command.add(String.valueOf(BENCHMARK_POSITIONS));
    command.add("-v");
    command.add(String.valueOf(BENCHMARK_VISITS));
    command.add("-time");
    command.add(String.valueOf(benchmarkTime));
    command.add("-override-config");
    command.add("logToStderr=false,logAllGTPCommunication=false,logSearchInfo=false");

    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);
    configureBundledProcessBuilder(processBuilder, snapshot.enginePath);

    Process process;
    try {
      process = processBuilder.start();
    } catch (IOException e) {
      throw new IOException(
          resource("AutoSetup.benchmarkFailed", "Unable to run KataGo benchmark right now.")
              + " "
              + e.getLocalizedMessage(),
          e);
    }

    StringBuilder output = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line).append('\n');
        if (listener != null && !line.trim().isEmpty()) {
          listener.onProgress(trimStatusForUi(line), 0L, -1L);
        }
      }
    } catch (IOException e) {
      process.destroyForcibly();
      throw e;
    }

    try {
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new IOException(
            resource("AutoSetup.benchmarkFailed", "Unable to run KataGo benchmark right now.")
                + " (exit "
                + exitCode
                + ")");
      }
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("KataGo benchmark interrupted");
    }

    BenchmarkResult result = parseBenchmarkOutput(output.toString());
    if (result == null) {
      throw new IOException(
          resource("AutoSetup.benchmarkFailed", "Unable to run KataGo benchmark right now."));
    }
    return result;
  }

  public static BenchmarkResult runBenchmarkAndApply(
      SetupSnapshot snapshot, ProgressListener listener, DownloadSession session)
      throws IOException {
    BenchmarkResult result = runBenchmark(snapshot, listener, session);
    applyBenchmarkResult(result);
    return result;
  }

  public static void applyBenchmarkResult(BenchmarkResult result) throws IOException {
    if (result == null || result.recommendedThreads <= 0 || Lizzie.config == null) {
      return;
    }
    Lizzie.config.chkKataEngineThreads = true;
    Lizzie.config.autoLoadKataEngineThreads = true;
    Lizzie.config.txtKataEngineThreads = String.valueOf(result.recommendedThreads);
    Lizzie.config.uiConfig.put("chk-kata-engine-threads", true);
    Lizzie.config.uiConfig.put("autoload-kata-engine-threads", true);
    Lizzie.config.uiConfig.put("txt-kata-engine-threads", Lizzie.config.txtKataEngineThreads);
    Lizzie.config.uiConfig.put("katago-benchmark-threads", result.recommendedThreads);
    Lizzie.config.uiConfig.put("katago-benchmark-current-threads", result.currentThreads);
    Lizzie.config.uiConfig.put("katago-benchmark-backend", result.backendLabel);
    Lizzie.config.uiConfig.put("katago-benchmark-summary", result.summary);
    Lizzie.config.uiConfig.put("katago-benchmark-updated-at", result.completedAtMillis);
    Lizzie.config.save();
  }

  private static void installNvidiaRuntimeWithDialog(
      Window owner, Path enginePath, NvidiaRuntimeStatus status) throws IOException {
    final DownloadSession session = new DownloadSession();
    final IOException[] errorHolder = new IOException[1];
    final DownloadCancelledException[] cancelHolder = new DownloadCancelledException[1];
    final BootstrapDialog[] dialogHolder = new BootstrapDialog[1];

    try {
      if (SwingUtilities.isEventDispatchThread()) {
        dialogHolder[0] = new BootstrapDialog(owner, session);
      } else {
        SwingUtilities.invokeAndWait(() -> dialogHolder[0] = new BootstrapDialog(owner, session));
      }
    } catch (Exception e) {
      throw new IOException("Unable to create NVIDIA bootstrap dialog", e);
    }

    Thread worker =
        new Thread(
            () -> {
              try {
                downloadAndInstallNvidiaRuntime(
                    enginePath,
                    (statusText, downloadedBytes, totalBytes) ->
                        SwingUtilities.invokeLater(
                            () ->
                                dialogHolder[0].updateProgress(
                                    statusText, downloadedBytes, totalBytes)),
                    session);
              } catch (DownloadCancelledException e) {
                cancelHolder[0] = e;
              } catch (IOException e) {
                errorHolder[0] = e;
              } finally {
                SwingUtilities.invokeLater(() -> dialogHolder[0].dispose());
              }
            },
            "katago-nvidia-runtime-bootstrap");
    worker.start();

    if (SwingUtilities.isEventDispatchThread()) {
      dialogHolder[0].setVisible(true);
    } else {
      try {
        SwingUtilities.invokeAndWait(() -> dialogHolder[0].setVisible(true));
      } catch (Exception e) {
        throw new IOException("Unable to show NVIDIA bootstrap dialog", e);
      }
    }

    if (cancelHolder[0] != null) {
      throw cancelHolder[0];
    }
    if (errorHolder[0] != null) {
      throw errorHolder[0];
    }
  }

  private static BenchmarkResult parseBenchmarkOutput(String output) {
    if (output == null || output.trim().isEmpty()) {
      return null;
    }
    Matcher recommendedMatcher = BENCHMARK_RECOMMENDED_PATTERN.matcher(output);
    int recommendedThreads = 0;
    while (recommendedMatcher.find()) {
      recommendedThreads = parseIntSafely(recommendedMatcher.group(1));
    }
    if (recommendedThreads <= 0) {
      return null;
    }

    Matcher currentMatcher = BENCHMARK_CURRENT_PATTERN.matcher(output);
    int currentThreads = currentMatcher.find() ? parseIntSafely(currentMatcher.group(1)) : 0;

    Matcher backendMatcher = BENCHMARK_BACKEND_PATTERN.matcher(output);
    String backend = backendMatcher.find() ? backendMatcher.group(1).trim() : "";

    List<String> summaryLines = new ArrayList<String>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                new java.io.ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (BENCHMARK_SUMMARY_LINE_PATTERN.matcher(trimmed).matches()) {
          summaryLines.add(trimmed);
        }
      }
    } catch (IOException e) {
      return null;
    }
    String summary = String.join(" | ", summaryLines);
    return new BenchmarkResult(
        recommendedThreads, currentThreads, backend, summary, System.currentTimeMillis());
  }

  private static int resolveBenchmarkTimeSeconds() {
    int seconds = 5;
    if (Lizzie.config != null) {
      seconds = Math.max(seconds, Lizzie.config.maxGameThinkingTimeSeconds);
    }
    return Math.max(BENCHMARK_MIN_TIME_SECONDS, Math.min(BENCHMARK_MAX_TIME_SECONDS, seconds));
  }

  private static void prependPath(ProcessBuilder processBuilder, Path path) {
    if (processBuilder == null || path == null) {
      return;
    }
    String candidate = path.toAbsolutePath().normalize().toString();
    String separator = System.getProperty("path.separator", ";");
    String original = processBuilder.environment().get("PATH");
    LinkedHashSet<String> entries = new LinkedHashSet<String>();
    entries.add(candidate);
    if (original != null && !original.trim().isEmpty()) {
      entries.addAll(Arrays.asList(original.split(Pattern.quote(separator))));
    }
    StringBuilder rebuilt = new StringBuilder();
    for (String entry : entries) {
      String trimmed = entry == null ? "" : entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (rebuilt.length() > 0) {
        rebuilt.append(separator);
      }
      rebuilt.append(trimmed);
    }
    processBuilder.environment().put("PATH", rebuilt.toString());
  }

  private static List<Path> collectRuntimeSearchDirs(Path enginePath, Path runtimeDir) {
    LinkedHashSet<Path> paths = new LinkedHashSet<Path>();
    if (enginePath != null && enginePath.getParent() != null) {
      paths.add(enginePath.getParent().toAbsolutePath().normalize());
    }
    if (runtimeDir != null) {
      paths.add(runtimeDir.toAbsolutePath().normalize());
    }
    String pathEnv = System.getenv("PATH");
    if (pathEnv != null && !pathEnv.trim().isEmpty()) {
      String separator = System.getProperty("path.separator", ";");
      for (String entry : pathEnv.split(Pattern.quote(separator))) {
        if (entry == null || entry.trim().isEmpty()) {
          continue;
        }
        try {
          Path candidate = Paths.get(entry).toAbsolutePath().normalize();
          if (Files.isDirectory(candidate)) {
            paths.add(candidate);
          }
        } catch (Exception e) {
        }
      }
    }
    return new ArrayList<Path>(paths);
  }

  private static boolean hasFile(List<Path> searchDirs, String fileName) {
    for (Path dir : searchDirs) {
      if (dir == null) {
        continue;
      }
      if (fileName.contains("*")) {
        String prefix = fileName.substring(0, fileName.indexOf('*'));
        String suffix = fileName.substring(fileName.indexOf('*') + 1);
        try (Stream<Path> files = Files.list(dir)) {
          boolean found =
              files.anyMatch(
                  path -> {
                    String name = path.getFileName().toString();
                    return Files.isRegularFile(path)
                        && name.startsWith(prefix)
                        && name.endsWith(suffix);
                  });
          if (found) {
            return true;
          }
        } catch (IOException e) {
        }
        continue;
      }
      if (Files.isRegularFile(dir.resolve(fileName))) {
        return true;
      }
    }
    return false;
  }

  private static List<String> collectMissingRuntimeGroups(List<Path> searchDirs) {
    List<String> missing = new ArrayList<String>();
    for (List<String> requirementGroup : REQUIRED_RUNTIME_DLL_GROUPS) {
      if (!hasAnyFile(searchDirs, requirementGroup)) {
        missing.add(describeRequirementGroup(requirementGroup));
      }
    }
    return missing;
  }

  private static boolean hasAnyFile(List<Path> searchDirs, List<String> fileNames) {
    for (String fileName : fileNames) {
      if (hasFile(searchDirs, fileName)) {
        return true;
      }
    }
    return false;
  }

  private static String describeRequirementGroup(List<String> requirementGroup) {
    if (requirementGroup == null || requirementGroup.isEmpty()) {
      return "";
    }
    if (requirementGroup.size() == 1) {
      return requirementGroup.get(0);
    }
    return String.join(" or ", requirementGroup);
  }

  private static Path findDirectoryContainingRequiredDlls(List<Path> searchDirs) {
    for (Path dir : searchDirs) {
      if (dir == null) {
        continue;
      }
      boolean allPresent = true;
      for (List<String> requirementGroup : REQUIRED_RUNTIME_DLL_GROUPS) {
        if (!hasAnyFile(Arrays.asList(dir), requirementGroup)) {
          allPresent = false;
          break;
        }
      }
      if (allPresent) {
        return dir.toAbsolutePath().normalize();
      }
    }
    return null;
  }

  private static String buildMissingRuntimeMessage(NvidiaRuntimeStatus status) {
    StringBuilder builder =
        new StringBuilder(
            resource(
                "AutoSetup.nvidiaRuntimeInstallFailed",
                "Bundled NVIDIA files are incomplete. Please reinstall the NVIDIA package."));
    if (status != null && status.missingDlls != null && !status.missingDlls.isEmpty()) {
      builder.append(" Missing: ").append(String.join(", ", status.missingDlls));
    }
    if (status != null && status.enginePath != null && status.enginePath.getParent() != null) {
      builder
          .append(" | ")
          .append(status.enginePath.getParent().toAbsolutePath().normalize().toString());
    }
    return builder.toString();
  }

  private static Path getNvidiaRuntimeDir() {
    if (Lizzie.config != null) {
      return Lizzie.config.getRuntimeWorkDirectory().toPath().resolve(NVIDIA_RUNTIME_ROOT);
    }
    return Paths.get(System.getProperty("user.dir", "."))
        .toAbsolutePath()
        .normalize()
        .resolve("runtime")
        .resolve(NVIDIA_RUNTIME_ROOT);
  }

  private static List<RuntimePackageSpec> resolveRequiredRuntimePackages() throws IOException {
    List<RuntimePackageSpec> packages = new ArrayList<RuntimePackageSpec>();
    JSONObject cudaManifest = new JSONObject(httpGet(CUDA_MANIFEST_URL));
    JSONObject cudnnManifest = new JSONObject(httpGet(CUDNN_MANIFEST_URL));
    packages.add(
        readPackageSpec(
            cudaManifest, CUDA_MANIFEST_URL, "cuda_cudart", "windows-x86_64", "CUDA Runtime"));
    packages.add(
        readPackageSpec(
            cudaManifest, CUDA_MANIFEST_URL, "libcublas", "windows-x86_64", "CUDA cuBLAS"));
    packages.add(
        readPackageSpec(
            cudaManifest, CUDA_MANIFEST_URL, "libnvjitlink", "windows-x86_64", "CUDA nvJitLink"));
    packages.add(
        readPackageSpec(
            cudnnManifest, CUDNN_MANIFEST_URL, "cudnn", "windows-x86_64", "NVIDIA cuDNN"));
    return packages;
  }

  private static RuntimePackageSpec readPackageSpec(
      JSONObject manifest, String manifestUrl, String key, String platformKey, String displayName)
      throws IOException {
    JSONObject packageJson = manifest.optJSONObject(key);
    if (packageJson == null) {
      throw new IOException("Missing NVIDIA package metadata: " + key);
    }
    JSONObject platformJson = packageJson.optJSONObject(platformKey);
    if (platformJson == null) {
      throw new IOException("Missing NVIDIA platform metadata: " + key + " " + platformKey);
    }
    String relativePath = platformJson.optString("relative_path", "").trim();
    String sha256 = platformJson.optString("sha256", "").trim();
    long sizeBytes = parseLongSafely(platformJson.optString("size", "0"));
    String version = packageJson.optString("version", "").trim();
    if (relativePath.isEmpty() || sha256.isEmpty()) {
      throw new IOException("Incomplete NVIDIA metadata: " + key);
    }
    String url =
        relativePath.startsWith("http")
            ? relativePath
            : resolveRelativeDownloadUrl(manifestUrl, relativePath);
    return new RuntimePackageSpec(displayName, version, url, sha256, sizeBytes, key);
  }

  private static String resolveRelativeDownloadUrl(String manifestUrl, String relativePath) {
    int lastSlash = manifestUrl.lastIndexOf('/');
    if (lastSlash < 0) {
      return relativePath;
    }
    return manifestUrl.substring(0, lastSlash + 1) + relativePath;
  }

  private static void downloadRuntimePackage(
      RuntimePackageSpec spec, Path archivePath, DownloadSession session, ProgressListener listener)
      throws IOException {
    if (Files.isRegularFile(archivePath) && spec.sha256.equalsIgnoreCase(sha256(archivePath))) {
      if (listener != null) {
        listener.onProgress(spec.displayName, spec.sizeBytes, spec.sizeBytes);
      }
      return;
    }

    Files.createDirectories(archivePath.getParent());
    Path tempPath = archivePath.resolveSibling(archivePath.getFileName().toString() + ".part");
    Files.deleteIfExists(tempPath);
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) new URL(spec.url).openConnection();
      session.attach(conn);
      session.throwIfCancelled();
      conn.setInstanceFollowRedirects(true);
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(15000);
      conn.setReadTimeout(30000);
      conn.setRequestProperty("User-Agent", USER_AGENT);
      int responseCode = conn.getResponseCode();
      if (responseCode < 200 || responseCode >= 400) {
        throw new IOException("HTTP " + responseCode + " from " + spec.url);
      }
      long totalBytes =
          conn.getContentLengthLong() > 0 ? conn.getContentLengthLong() : spec.sizeBytes;
      MessageDigest digest = createSha256Digest();
      try (InputStream raw = conn.getInputStream();
          BufferedInputStream input = new BufferedInputStream(raw);
          OutputStream output = Files.newOutputStream(tempPath)) {
        byte[] buffer = new byte[8192];
        long downloaded = 0L;
        int read;
        long lastReport = 0L;
        while ((read = input.read(buffer)) >= 0) {
          session.throwIfCancelled();
          output.write(buffer, 0, read);
          digest.update(buffer, 0, read);
          downloaded += read;
          long now = System.currentTimeMillis();
          if (listener != null && (now - lastReport > 120 || downloaded == totalBytes)) {
            listener.onProgress(spec.displayName, downloaded, totalBytes);
            lastReport = now;
          }
        }
      }
      session.throwIfCancelled();
      String actualSha256 = toHex(digest.digest());
      if (!spec.sha256.equalsIgnoreCase(actualSha256)) {
        throw new IOException("SHA-256 mismatch for " + spec.displayName);
      }
      try {
        Files.move(
            tempPath,
            archivePath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tempPath, archivePath, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      Files.deleteIfExists(tempPath);
      if (session.isCancelled() && !(e instanceof DownloadCancelledException)) {
        throw new DownloadCancelledException(
            resource("AutoSetup.downloadCancelled", "Download cancelled."));
      }
      throw e;
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
      session.clear();
    }
  }

  private static void extractRuntimePackage(
      RuntimePackageSpec spec, Path archivePath, Path runtimeDir, Path licenseDir)
      throws IOException {
    Files.createDirectories(runtimeDir);
    Files.createDirectories(licenseDir);
    try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(archivePath))) {
      ZipEntry entry;
      while ((entry = zipInputStream.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        String entryName = entry.getName().replace('\\', '/');
        String fileName = Paths.get(entryName).getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".dll")) {
          copyZipEntry(zipInputStream, runtimeDir.resolve(fileName));
        } else if (lower.equals("license.txt")
            || entryName.toLowerCase(Locale.ROOT).contains("/license")) {
          copyZipEntry(zipInputStream, licenseDir.resolve(spec.key + "-" + fileName));
        }
      }
    }
  }

  private static void writeRuntimeManifest(Path runtimeDir, List<RuntimePackageSpec> packages)
      throws IOException {
    Path manifest = runtimeDir.resolve("manifest.txt");
    StringBuilder builder = new StringBuilder();
    builder
        .append("Prepared at: ")
        .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").format(new Date()))
        .append('\n');
    for (RuntimePackageSpec spec : packages) {
      builder
          .append(spec.displayName)
          .append(": ")
          .append(spec.version)
          .append('\n')
          .append(spec.url)
          .append('\n');
    }
    Files.write(manifest, builder.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void copyZipEntry(InputStream inputStream, Path destination) throws IOException {
    Files.createDirectories(destination.getParent());
    try (OutputStream output = Files.newOutputStream(destination)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = inputStream.read(buffer)) >= 0) {
        output.write(buffer, 0, read);
      }
    }
  }

  private static String httpGet(String url) throws IOException {
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setInstanceFollowRedirects(true);
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(15000);
      conn.setReadTimeout(30000);
      conn.setRequestProperty("User-Agent", USER_AGENT);
      int code = conn.getResponseCode();
      if (code < 200 || code >= 400) {
        throw new IOException("HTTP " + code + " from " + url);
      }
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
          builder.append(line).append('\n');
        }
        return builder.toString();
      }
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  private static MessageDigest createSha256Digest() throws IOException {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("SHA-256 is unavailable", e);
    }
  }

  private static String sha256(Path file) throws IOException {
    MessageDigest digest = createSha256Digest();
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
    }
    return toHex(digest.digest());
  }

  private static String toHex(byte[] bytes) {
    StringBuilder builder = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      builder.append(String.format(Locale.ROOT, "%02x", b & 0xff));
    }
    return builder.toString();
  }

  private static int parseIntSafely(String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (Exception e) {
      return 0;
    }
  }

  private static long parseLongSafely(String value) {
    try {
      return Long.parseLong(value.trim());
    } catch (Exception e) {
      return 0L;
    }
  }

  private static String trimStatusForUi(String line) {
    String trimmed = line == null ? "" : line.trim();
    if (trimmed.isEmpty()) {
      return resource("AutoSetup.benchmarking", "Optimizing KataGo...");
    }
    if (trimmed.length() > 120) {
      return trimmed.substring(0, 120) + "...";
    }
    return trimmed;
  }

  private static String formatBytes(long bytes) {
    if (bytes <= 0) {
      return "0 MB";
    }
    double gb = bytes / 1024.0 / 1024.0 / 1024.0;
    if (gb >= 1.0) {
      return String.format(Locale.ROOT, "%.1f GB", gb);
    }
    double mb = bytes / 1024.0 / 1024.0;
    if (mb >= 100) {
      return String.format(Locale.ROOT, "%.0f MB", mb);
    }
    return String.format(Locale.ROOT, "%.1f MB", mb);
  }

  private static String formatDuration(long millis) {
    long seconds = Math.max(0L, millis / 1000L);
    long minutes = seconds / 60L;
    long remainSeconds = seconds % 60L;
    if (minutes <= 0L) {
      return remainSeconds + "s";
    }
    if (minutes < 60L) {
      return minutes + "m " + remainSeconds + "s";
    }
    long hours = minutes / 60L;
    long remainMinutes = minutes % 60L;
    return hours + "h " + remainMinutes + "m";
  }

  public static String formatBenchmarkResult(BenchmarkResult result) {
    if (result == null || result.recommendedThreads <= 0) {
      return resource(
          "AutoSetup.benchmarkMissing", "No benchmark result yet. Run Smart Optimize once.");
    }
    StringBuilder builder = new StringBuilder();
    builder
        .append(resource("AutoSetup.benchmarkRecommended", "Recommended threads"))
        .append(" ")
        .append(result.recommendedThreads);
    if (result.backendLabel != null && !result.backendLabel.isEmpty()) {
      builder.append("  |  ").append(result.backendLabel);
    }
    if (result.completedAtMillis > 0) {
      builder
          .append("  |  ")
          .append(
              new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(result.completedAtMillis)));
    }
    return builder.toString();
  }

  private static String resource(String key, String fallback) {
    try {
      if (Lizzie.resourceBundle != null && Lizzie.resourceBundle.containsKey(key)) {
        return Lizzie.resourceBundle.getString(key);
      }
    } catch (Exception e) {
    }
    return fallback;
  }
}
