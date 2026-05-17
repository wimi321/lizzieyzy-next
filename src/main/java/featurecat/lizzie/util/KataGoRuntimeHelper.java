package featurecat.lizzie.util;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadCancelledException;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadSession;
import featurecat.lizzie.util.KataGoAutoSetupHelper.ProgressListener;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupResult;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
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
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.json.JSONObject;

public final class KataGoRuntimeHelper {
  private static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";
  private static final String NVIDIA_ENGINE_DIR = "windows-x64-nvidia";
  private static final String NVIDIA50_CUDA_ENGINE_DIR = "windows-x64-nvidia50-cuda";
  private static final String NVIDIA50_TRT_ENGINE_DIR = "windows-x64-nvidia50-trt";
  private static final String NVIDIA_BACKEND = "nvidia";
  private static final String NVIDIA50_CUDA_BACKEND = "nvidia50-cuda";
  private static final String NVIDIA50_TRT_BACKEND = "nvidia50-trt";
  private static final String ENGINE_BACKEND_MARKER_NAME = "lizzieyzy-next-engine-backend.txt";
  private static final String NVIDIA_RUNTIME_ROOT = "nvidia-runtime";
  private static final String BUNDLED_HOME_DATA_DIR = "katago-home";
  private static final String CUDA_MANIFEST_URL =
      "https://developer.download.nvidia.com/compute/cuda/redist/redistrib_12.1.1.json";
  private static final String CUDNN_MANIFEST_URL =
      "https://developer.download.nvidia.com/compute/cudnn/redist/redistrib_8.9.7.29.json";
  private static final String CUDA_12_8_MANIFEST_URL =
      "https://developer.download.nvidia.com/compute/cuda/redist/redistrib_12.8.0.json";
  private static final String CUDNN_9_MANIFEST_URL =
      "https://developer.download.nvidia.com/compute/cudnn/redist/redistrib_9.8.0.json";
  private static final String TENSORRT_ENGINE_NAME = "KataGo TensorRT RTX 50 Experimental";
  private static final String TENSORRT_KATAGO_URL_PROPERTY = "lizzie.tensorrt.katago.url";
  private static final String TENSORRT_KATAGO_SHA256_PROPERTY = "lizzie.tensorrt.katago.sha256";
  private static final String TENSORRT_KATAGO_SIZE_PROPERTY = "lizzie.tensorrt.katago.size";
  private static final String TENSORRT_RUNTIME_SHA256_PROPERTY = "lizzie.tensorrt.runtime.sha256";
  private static final String TENSORRT_SKIP_RUNTIME_FOR_TESTS_PROPERTY =
      "lizzie.tensorrt.skipRuntimePackagesForTests";
  private static final String TENSORRT_KATAGO_VERSION = "v1.16.4";
  private static final String TENSORRT_KATAGO_ASSET =
      "katago-v1.16.4-trt10.9.0-cuda12.8-windows-x64.zip";
  private static final String TENSORRT_KATAGO_URL =
      "https://github.com/lightvector/KataGo/releases/download/"
          + TENSORRT_KATAGO_VERSION
          + "/"
          + TENSORRT_KATAGO_ASSET;
  private static final String TENSORRT_KATAGO_SHA256 =
      "1dea0b507c6331c9a7cf4f0ed2eeee5384b880d60f1db7fe876506daee55830f";
  private static final long TENSORRT_KATAGO_SIZE_BYTES = 4693569L;
  private static final String TENSORRT_RUNTIME_URL =
      "https://developer.download.nvidia.com/compute/machine-learning/tensorrt/10.9.0/zip/"
          + "TensorRT-10.9.0.34.Windows.win10.cuda-12.8.zip";
  private static final long CUDA_12_8_CUDART_SIZE_BYTES = 3034859L;
  private static final long CUDA_12_8_CUBLAS_SIZE_BYTES = 574528660L;
  private static final long CUDA_12_8_NVJITLINK_SIZE_BYTES = 257312022L;
  private static final long CUDNN_9_SIZE_BYTES = 675349654L;
  private static final long TENSORRT_RUNTIME_SIZE_BYTES = 1845842538L;
  private static final Pattern BENCHMARK_RECOMMENDED_PATTERN =
      Pattern.compile("numSearchThreads\\s*=\\s*(\\d+):.*\\(recommended\\)");
  private static final Pattern BENCHMARK_CURRENT_PATTERN =
      Pattern.compile("Your GTP config is currently set to use numSearchThreads\\s*=\\s*(\\d+)");
  private static final Pattern BENCHMARK_BACKEND_PATTERN =
      Pattern.compile("You are currently using the (.+?) version of KataGo\\.");
  private static final Pattern BENCHMARK_SUMMARY_LINE_PATTERN =
      Pattern.compile("^numSearchThreads\\s*=\\s*\\d+:\\s*(?:\\(baseline\\)|[+-]?\\d+\\s+Elo.*)$");
  private static final Pattern BENCHMARK_POSITION_PROGRESS_PATTERN =
      Pattern.compile("numSearchThreads\\s*=\\s*(\\d+):\\s*(\\d+)\\s*/\\s*(\\d+)\\s*positions");
  private static final Pattern BENCHMARK_POSSIBLE_THREADS_PATTERN =
      Pattern.compile("Possible numbers of threads to test:\\s*(.*)");
  private static final List<List<String>> REQUIRED_NVIDIA_CUDA12_1_RUNTIME_DLL_GROUPS =
      Arrays.asList(
          Arrays.asList("cudart64_12.dll"),
          Arrays.asList("cublas64_12.dll"),
          Arrays.asList("cublasLt64_12.dll"),
          Arrays.asList("cudnn64_8.dll"),
          Arrays.asList("nvJitLink*.dll"),
          Arrays.asList("zlibwapi.dll", "libz.dll"));
  private static final List<List<String>> REQUIRED_NVIDIA_CUDA12_8_RUNTIME_DLL_GROUPS =
      Arrays.asList(
          Arrays.asList("cudart64_12.dll"),
          Arrays.asList("cublas64_12.dll"),
          Arrays.asList("cublasLt64_12.dll"),
          Arrays.asList("cudnn64_9.dll"),
          Arrays.asList("nvJitLink*.dll"),
          Arrays.asList("zlibwapi.dll", "libz.dll"));
  private static final List<List<String>> REQUIRED_NVIDIA_TRT10_9_RUNTIME_DLL_GROUPS =
      Arrays.asList(
          Arrays.asList("cudart64_12.dll"),
          Arrays.asList("cublas64_12.dll"),
          Arrays.asList("cublasLt64_12.dll"),
          Arrays.asList("cudnn64_9.dll"),
          Arrays.asList("nvJitLink*.dll"),
          Arrays.asList("nvinfer_10.dll", "nvinfer*.dll"),
          Arrays.asList("nvinfer_plugin_10.dll", "nvinfer_plugin*.dll"),
          Arrays.asList("zlibwapi.dll", "libz.dll"));
  private static final Object NVIDIA_RUNTIME_LOCK = new Object();
  private static final int BENCHMARK_VISITS = 800;
  private static final int BENCHMARK_POSITIONS = 6;
  private static final int BENCHMARK_MIN_TIME_SECONDS = 5;
  private static final int BENCHMARK_MAX_TIME_SECONDS = 15;
  private static final int APPLE_AUTO_OPTIMIZE_VERSION = 3;
  private static final int APPLE_AUTO_OPTIMIZE_DELAY_MILLIS = 8000;
  private static final int APPLE_AUTO_OPTIMIZE_READY_TIMEOUT_MILLIS = 45000;
  private static final int MAX_APPLE_ANALYSIS_THREADS = 8;
  private static final String BENCHMARK_SIGNATURE_KEY = "katago-benchmark-signature";
  private static final String BENCHMARK_DISMISSED_SIGNATURE_KEY =
      "katago-startup-benchmark-dismissed-signature";
  private static final String BENCHMARK_DISMISSED_VERSION_KEY =
      "katago-startup-benchmark-dismissed-version";
  private static final String APPLE_AUTO_OPTIMIZE_VERSION_KEY =
      "katago-apple-auto-optimize-version";
  private static final Object APPLE_AUTO_OPTIMIZE_LOCK = new Object();
  private static final Object BENCHMARK_ANALYSIS_PAUSE_LOCK = new Object();
  private static final String BENCHMARK_NOTICE_PROGRESS_KEY = "lizzie.benchmark.notice.progress";
  private static Boolean benchmarkPreviousShowPonderTips = null;
  private static int benchmarkPausedEngineIndex = -1;
  private static boolean benchmarkPausedEngineByShutdown = false;
  private static volatile boolean benchmarkEngineSyncSuppressed = false;
  private static volatile boolean appleAutoOptimizeRunning = false;

  private KataGoRuntimeHelper() {}

  private static boolean isWindowsPlatform() {
    String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    return !osName.contains("darwin") && osName.contains("win");
  }

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

  public static final class TensorRtInstallStatus {
    public final boolean applicable;
    public final boolean installed;
    public final Path enginePath;
    public final Path runtimeDir;
    public final long downloadBytes;
    public final String detailText;

    private TensorRtInstallStatus(
        boolean applicable,
        boolean installed,
        Path enginePath,
        Path runtimeDir,
        long downloadBytes,
        String detailText) {
      this.applicable = applicable;
      this.installed = installed;
      this.enginePath = enginePath;
      this.runtimeDir = runtimeDir;
      this.downloadBytes = downloadBytes;
      this.detailText = detailText;
    }
  }

  static final class TensorRtInstallSpec {
    final Path targetEngineDir;
    final Path targetEnginePath;
    final String katagoUrl;
    final String katagoSha256;
    final long katagoSizeBytes;
    final long totalDownloadBytes;
    final int runtimePackageCount;

    private TensorRtInstallSpec(
        Path targetEngineDir,
        Path targetEnginePath,
        String katagoUrl,
        String katagoSha256,
        long katagoSizeBytes,
        long totalDownloadBytes,
        int runtimePackageCount) {
      this.targetEngineDir = targetEngineDir;
      this.targetEnginePath = targetEnginePath;
      this.katagoUrl = katagoUrl;
      this.katagoSha256 = katagoSha256;
      this.katagoSizeBytes = katagoSizeBytes;
      this.totalDownloadBytes = totalDownloadBytes;
      this.runtimePackageCount = runtimePackageCount;
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

  private static final class AnalysisThreadProfile {
    public final int numAnalysisThreads;
    public final int numSearchThreadsPerAnalysisThread;

    private AnalysisThreadProfile(int numAnalysisThreads, int numSearchThreadsPerAnalysisThread) {
      this.numAnalysisThreads = numAnalysisThreads;
      this.numSearchThreadsPerAnalysisThread = numSearchThreadsPerAnalysisThread;
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
    return resolveNvidiaBackend(enginePath) != null;
  }

  private static String resolveNvidiaBackend(Path enginePath) {
    if (enginePath == null) {
      return null;
    }
    String normalized = enginePath.toAbsolutePath().normalize().toString().replace('\\', '/');
    String normalizedLower = normalized.toLowerCase(Locale.ROOT);
    if (normalizedLower.contains("/" + NVIDIA50_TRT_ENGINE_DIR + "/")) {
      return NVIDIA50_TRT_BACKEND;
    }
    if (normalizedLower.contains("/" + NVIDIA50_CUDA_ENGINE_DIR + "/")) {
      return NVIDIA50_CUDA_BACKEND;
    }
    if (normalizedLower.contains("/" + NVIDIA_ENGINE_DIR + "/")) {
      return NVIDIA_BACKEND;
    }
    Path engineDir = enginePath.toAbsolutePath().normalize().getParent();
    if (engineDir == null) {
      return null;
    }
    Path markerPath = engineDir.resolve(ENGINE_BACKEND_MARKER_NAME);
    if (!Files.isRegularFile(markerPath)) {
      return null;
    }
    try {
      String backend = Files.readString(markerPath, StandardCharsets.UTF_8).trim();
      String backendLower = backend.toLowerCase(Locale.ROOT);
      if (NVIDIA50_TRT_BACKEND.equals(backendLower)) {
        return NVIDIA50_TRT_BACKEND;
      }
      if (NVIDIA50_CUDA_BACKEND.equals(backendLower)) {
        return NVIDIA50_CUDA_BACKEND;
      }
      if (NVIDIA_BACKEND.equals(backendLower)) {
        return NVIDIA_BACKEND;
      }
      if (backendLower.startsWith("nvidia")) {
        return backendLower;
      }
      return null;
    } catch (IOException e) {
      return null;
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
    if (!Config.isBundledKataGoCommand(enginePath.toAbsolutePath().normalize().toString())) {
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
    if (isWindowsPlatform() && isNvidiaBundledPath(enginePath)) {
      Path runtimeDir = getNvidiaRuntimeDir();
      if (Files.isDirectory(runtimeDir)) {
        prependPath(processBuilder, runtimeDir);
      }
    }
  }

  public static List<String> prepareBundledLaunchCommand(
      List<String> originalCommand, Path enginePath) {
    if (originalCommand == null) {
      return null;
    }
    List<String> launchCommand = new ArrayList<String>(originalCommand);
    if (enginePath == null || Lizzie.config == null) {
      return launchCommand;
    }
    if (!Config.isBundledKataGoCommand(enginePath.toAbsolutePath().normalize().toString())) {
      return launchCommand;
    }

    Path homeDataDir = getBundledHomeDataDir();
    if (homeDataDir == null) {
      return launchCommand;
    }
    try {
      Files.createDirectories(homeDataDir);
    } catch (IOException e) {
      e.printStackTrace();
      return launchCommand;
    }

    appendOverrideConfig(launchCommand, "homeDataDir=" + homeDataDir.toString());
    appendAnalysisPvLenOverride(launchCommand);
    return launchCommand;
  }

  public static NvidiaRuntimeStatus inspectNvidiaRuntime(SetupSnapshot snapshot) {
    return inspectNvidiaRuntime(snapshot == null ? null : snapshot.enginePath);
  }

  public static NvidiaRuntimeStatus inspectNvidiaRuntime(Path enginePath) {
    Path runtimeDir = getNvidiaRuntimeDir();
    String backend = resolveNvidiaBackend(enginePath);
    if (!isWindowsPlatform() || backend == null) {
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
    List<List<String>> requiredDllGroups = requiredRuntimeDllGroups(backend);
    List<String> missing = collectMissingRuntimeGroups(searchDirs, requiredDllGroups);
    Path readyDir = findDirectoryContainingRequiredDlls(searchDirs, requiredDllGroups);
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

  public static TensorRtInstallStatus inspectTensorRtInstall(SetupSnapshot snapshot) {
    TensorRtInstallSpec spec = buildTensorRtInstallSpec(snapshot);
    if (!isWindowsPlatform()) {
      return new TensorRtInstallStatus(
          false,
          false,
          spec.targetEnginePath,
          getNvidiaRuntimeDir(),
          spec.totalDownloadBytes,
          resource(
              "AutoSetup.tensorRtNotApplicable",
              "TensorRT acceleration is only available on Windows NVIDIA packages."));
    }
    if (!isTensorRtSourceProfileAllowed(snapshot)) {
      return new TensorRtInstallStatus(
          false,
          false,
          spec.targetEnginePath,
          getNvidiaRuntimeDir(),
          spec.totalDownloadBytes,
          resource(
              "AutoSetup.tensorRtNeedNvidia",
              "TensorRT is recommended only for Windows NVIDIA / RTX 50 packages."));
    }
    boolean engineInstalled = Files.isRegularFile(spec.targetEnginePath);
    boolean runtimeReady = inspectNvidiaRuntime(spec.targetEnginePath).ready;
    String detail =
        engineInstalled && runtimeReady
            ? resource("AutoSetup.tensorRtReady", "TensorRT acceleration is installed.")
            : String.format(
                Locale.ROOT,
                resource(
                    "AutoSetup.tensorRtAvailable",
                    "Optional TensorRT download: about %s. RTX 50 users can install it here."),
                formatBytes(spec.totalDownloadBytes));
    return new TensorRtInstallStatus(
        true,
        engineInstalled && runtimeReady,
        spec.targetEnginePath,
        getNvidiaRuntimeDir(),
        spec.totalDownloadBytes,
        detail);
  }

  public static boolean canInstallTensorRt(SetupSnapshot snapshot) {
    TensorRtInstallStatus status = inspectTensorRtInstall(snapshot);
    return status.applicable && !status.installed;
  }

  public static SetupResult downloadAndInstallTensorRt(
      SetupSnapshot snapshot, ProgressListener listener, DownloadSession session)
      throws IOException {
    if (snapshot == null) {
      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    }
    if (!isWindowsPlatform()) {
      throw new IOException(
          resource(
              "AutoSetup.tensorRtNotApplicable",
              "TensorRT acceleration is only available on Windows NVIDIA packages."));
    }
    if (!isTensorRtSourceProfileAllowed(snapshot)) {
      throw new IOException(
          resource(
              "AutoSetup.tensorRtNeedNvidia",
              "TensorRT is recommended only for Windows NVIDIA / RTX 50 packages."));
    }
    if (snapshot.gtpConfigPath == null || !Files.isRegularFile(snapshot.gtpConfigPath)) {
      throw new IOException(
          resource("AutoSetup.missingConfig", "No KataGo config file was found."));
    }
    if (!snapshot.hasWeight()) {
      throw new IOException(
          resource("AutoSetup.missingWeight", "No local KataGo weight file was found."));
    }

    DownloadSession activeSession = session != null ? session : new DownloadSession();
    TensorRtInstallSpec spec = buildTensorRtInstallSpec(snapshot);
    activeSession.throwIfCancelled();
    notifyProgress(
        listener,
        resource("AutoSetup.tensorRtPreparing", "Preparing TensorRT download..."),
        0L,
        spec.totalDownloadBytes);

    Path cacheDir = getNvidiaRuntimeDir().resolve("downloads");
    Files.createDirectories(cacheDir);
    RuntimePackageSpec katagoPackage =
        new RuntimePackageSpec(
            "KataGo TensorRT",
            TENSORRT_KATAGO_VERSION,
            spec.katagoUrl,
            spec.katagoSha256,
            spec.katagoSizeBytes,
            "katago-tensorrt");
    List<RuntimePackageSpec> runtimePackages = resolveTensorRtRuntimePackages();
    long completedBytes = 0L;

    Path katagoArchive =
        downloadPackageWithAggregateProgress(
            katagoPackage,
            cacheDir.resolve(katagoPackage.fileName()),
            activeSession,
            listener,
            completedBytes,
            spec.totalDownloadBytes);
    completedBytes += Math.max(0L, katagoPackage.sizeBytes);

    Path runtimeDir = getNvidiaRuntimeDir();
    Path licenseDir = runtimeDir.resolve("licenses").resolve("nvidia-runtime");
    if (!Boolean.getBoolean(TENSORRT_SKIP_RUNTIME_FOR_TESTS_PROPERTY)) {
      for (RuntimePackageSpec runtimePackage : runtimePackages) {
        Path archivePath =
            downloadPackageWithAggregateProgress(
                runtimePackage,
                cacheDir.resolve(runtimePackage.fileName()),
                activeSession,
                listener,
                completedBytes,
                spec.totalDownloadBytes);
        completedBytes += Math.max(0L, runtimePackage.sizeBytes);
        activeSession.throwIfCancelled();
        notifyProgress(
            listener,
            resource("AutoSetup.tensorRtExtracting", "Extracting TensorRT files...")
                + " "
                + runtimePackage.displayName,
            Math.min(completedBytes, spec.totalDownloadBytes),
            spec.totalDownloadBytes);
        extractRuntimePackage(runtimePackage, archivePath, runtimeDir, licenseDir);
      }
      writeRuntimeManifest(runtimeDir, runtimePackages);
    }

    activeSession.throwIfCancelled();
    notifyProgress(
        listener,
        resource("AutoSetup.tensorRtExtracting", "Extracting TensorRT files..."),
        Math.min(completedBytes, spec.totalDownloadBytes),
        spec.totalDownloadBytes);
    installTensorRtKataGoArchive(katagoArchive, spec.targetEngineDir, activeSession);
    activeSession.throwIfCancelled();

    SetupSnapshot tensorRtSnapshot = snapshot.withEnginePath(spec.targetEnginePath);
    SetupResult result =
        KataGoAutoSetupHelper.applyEngineProfile(tensorRtSnapshot, TENSORRT_ENGINE_NAME, true);
    notifyProgress(
        listener,
        resource("AutoSetup.tensorRtInstallDone", "TensorRT acceleration installed."),
        spec.totalDownloadBytes,
        spec.totalDownloadBytes);
    return result;
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
    activeSession.throwIfCancelled();
    if (isWindowsPlatform() && isNvidiaBundledPath(snapshot.enginePath)) {
      ensureBundledRuntimeReady(snapshot.enginePath, null);
    }

    List<String> command = buildBenchmarkCommand(snapshot);
    notifyProgress(
        listener,
        resource("AutoSetup.benchmarkStarting", "Starting KataGo benchmark..."),
        60L,
        1000L);

    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);
    configureBundledProcessBuilder(processBuilder, snapshot.enginePath);

    Process process;
    try {
      process = processBuilder.start();
      if (activeSession.isCancelled()) {
        process.destroyForcibly();
        activeSession.throwIfCancelled();
      }
      notifyProgress(
          listener,
          resource("AutoSetup.benchmarkRunning", "KataGo benchmark is running..."),
          90L,
          1000L);
    } catch (IOException e) {
      throw new IOException(
          resource("AutoSetup.benchmarkFailed", "Unable to run KataGo benchmark right now.")
              + " "
              + e.getLocalizedMessage(),
          e);
    }

    StringBuilder output = new StringBuilder();
    BenchmarkProgressTracker progressTracker = new BenchmarkProgressTracker();
    AtomicLong benchmarkStartedAt = new AtomicLong(System.currentTimeMillis());
    AtomicLong lastProgressAt = new AtomicLong(benchmarkStartedAt.get());
    AtomicInteger lastProgressPermille = new AtomicInteger(0);
    Thread cancellationWatcher = startBenchmarkCancellationWatcher(process, activeSession);
    Thread progressHeartbeat =
        startBenchmarkProgressHeartbeat(
            process,
            listener,
            activeSession,
            benchmarkStartedAt,
            lastProgressAt,
            lastProgressPermille);
    try {
      readBenchmarkOutput(
          process.getInputStream(),
          output,
          listener,
          activeSession,
          process,
          progressTracker,
          lastProgressAt,
          lastProgressPermille);
    } catch (IOException e) {
      process.destroyForcibly();
      if (activeSession.isCancelled()) {
        throw new DownloadCancelledException(
            resource("AutoSetup.benchmarkCancelled", "Benchmark stopped."));
      }
      throw e;
    } finally {
      if (cancellationWatcher != null) {
        cancellationWatcher.interrupt();
      }
      if (progressHeartbeat != null) {
        progressHeartbeat.interrupt();
      }
    }

    try {
      activeSession.throwIfCancelled();
      int exitCode = process.waitFor();
      activeSession.throwIfCancelled();
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
    notifyProgress(
        listener, resource("AutoSetup.benchmarkDone", "Benchmark complete."), 1000L, 1000L);

    BenchmarkResult result = parseBenchmarkOutput(output.toString());
    if (result == null) {
      throw new IOException(
          resource("AutoSetup.benchmarkFailed", "Unable to run KataGo benchmark right now."));
    }
    return result;
  }

  static List<String> buildBenchmarkCommand(SetupSnapshot snapshot) {
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
    return prepareBundledLaunchCommand(command, snapshot.enginePath);
  }

  private static Thread startBenchmarkCancellationWatcher(
      Process process, DownloadSession session) {
    if (process == null || session == null) {
      return null;
    }
    Thread watcher =
        new Thread(
            () -> {
              try {
                while (process.isAlive()) {
                  if (session.isCancelled()) {
                    process.destroyForcibly();
                    return;
                  }
                  Thread.sleep(100L);
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            "katago-benchmark-cancel-watch");
    watcher.setDaemon(true);
    watcher.start();
    return watcher;
  }

  private static Thread startBenchmarkProgressHeartbeat(
      Process process,
      ProgressListener listener,
      DownloadSession session,
      AtomicLong benchmarkStartedAt,
      AtomicLong lastProgressAt,
      AtomicInteger lastProgressPermille) {
    if (process == null || listener == null) {
      return null;
    }
    Thread heartbeat =
        new Thread(
            () -> {
              try {
                while (process.isAlive()) {
                  if (session != null && session.isCancelled()) {
                    return;
                  }
                  long now = System.currentTimeMillis();
                  long sinceLastProgress = now - lastProgressAt.get();
                  if (sinceLastProgress >= 1200L) {
                    int syntheticPermille =
                        estimateSyntheticBenchmarkPermille(
                            now - benchmarkStartedAt.get(),
                            sinceLastProgress,
                            lastProgressPermille.get());
                    int displayPermille = Math.max(lastProgressPermille.get(), syntheticPermille);
                    notifyProgress(
                        listener,
                        formatBenchmarkHeartbeatStatus(now - benchmarkStartedAt.get()),
                        displayPermille,
                        1000L);
                  }
                  Thread.sleep(500L);
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            "katago-benchmark-progress-heartbeat");
    heartbeat.setDaemon(true);
    heartbeat.start();
    return heartbeat;
  }

  private static void readBenchmarkOutput(
      InputStream inputStream,
      StringBuilder output,
      ProgressListener listener,
      DownloadSession session,
      Process process,
      BenchmarkProgressTracker progressTracker,
      AtomicLong lastProgressAt,
      AtomicInteger lastProgressPermille)
      throws IOException {
    try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
      char[] buffer = new char[1024];
      StringBuilder statusSegment = new StringBuilder();
      String lastPublishedStatus = "";
      int read;
      while ((read = reader.read(buffer)) != -1) {
        if (session != null && session.isCancelled()) {
          process.destroyForcibly();
          throw new DownloadCancelledException(
              resource("AutoSetup.benchmarkCancelled", "Benchmark stopped."));
        }
        for (int i = 0; i < read; i++) {
          char ch = buffer[i];
          output.append(ch);
          if (ch == '\r' || ch == '\n') {
            lastPublishedStatus =
                publishBenchmarkStatus(
                    statusSegment.toString(),
                    listener,
                    progressTracker,
                    lastPublishedStatus,
                    lastProgressAt,
                    lastProgressPermille);
            statusSegment.setLength(0);
          } else {
            statusSegment.append(ch);
          }
        }
        if (shouldDisplayBenchmarkStatusLine(statusSegment.toString())) {
          lastPublishedStatus =
              publishBenchmarkStatus(
                  statusSegment.toString(),
                  listener,
                  progressTracker,
                  lastPublishedStatus,
                  lastProgressAt,
                  lastProgressPermille);
        }
      }
      publishBenchmarkStatus(
          statusSegment.toString(),
          listener,
          progressTracker,
          lastPublishedStatus,
          lastProgressAt,
          lastProgressPermille);
    }
  }

  private static String publishBenchmarkStatus(
      String rawStatus,
      ProgressListener listener,
      BenchmarkProgressTracker progressTracker,
      String lastPublishedStatus,
      AtomicLong lastProgressAt,
      AtomicInteger lastProgressPermille) {
    String trimmed = rawStatus == null ? "" : rawStatus.trim();
    if (trimmed.isEmpty()) {
      return lastPublishedStatus == null ? "" : lastPublishedStatus;
    }
    if (trimmed.equals(lastPublishedStatus)) {
      return lastPublishedStatus;
    }
    int progressValue = progressTracker == null ? 0 : progressTracker.update(trimmed);
    if (!shouldDisplayBenchmarkStatusLine(trimmed)) {
      return lastPublishedStatus == null ? "" : lastPublishedStatus;
    }
    notifyProgress(listener, trimStatusForUi(trimmed), progressValue, 1000L);
    if (lastProgressAt != null) {
      lastProgressAt.set(System.currentTimeMillis());
    }
    if (lastProgressPermille != null) {
      lastProgressPermille.set(progressValue);
    }
    return trimmed;
  }

  private static boolean shouldDisplayBenchmarkStatusLine(String rawStatus) {
    String trimmed = rawStatus == null ? "" : rawStatus.trim();
    if (trimmed.isEmpty()) {
      return false;
    }
    return BENCHMARK_POSITION_PROGRESS_PATTERN.matcher(trimmed).find()
        || BENCHMARK_POSSIBLE_THREADS_PATTERN.matcher(trimmed).find()
        || trimmed.contains("Loading model")
        || trimmed.contains("Initializing benchmark")
        || trimmed.contains("Automatically trying different numbers of threads")
        || trimmed.contains("Your GTP config is currently set to use numSearchThreads")
        || trimmed.contains("Testing using ")
        || trimmed.contains("Ordered summary of results")
        || trimmed.contains("So APPROXIMATELY based on this benchmark");
  }

  public static BenchmarkResult runBenchmarkAndApply(
      SetupSnapshot snapshot, ProgressListener listener, DownloadSession session)
      throws IOException {
    if (snapshot == null) {
      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    }
    BenchmarkResult result = runBenchmark(snapshot, listener, session);
    applyBenchmarkResult(result);
    rememberBenchmarkContext(snapshot, result);
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

  public static void applyBenchmarkResultToRunningEngines(BenchmarkResult result) {
    if (result == null || result.recommendedThreads <= 0) {
      return;
    }
    try {
      if (Lizzie.leelaz != null && Lizzie.leelaz.isLoaded() && Lizzie.leelaz.isKatago) {
        Lizzie.leelaz.sendCommand("kata-set-param numSearchThreads " + result.recommendedThreads);
      }
    } catch (Exception e) {
    }
    restartIdlePreloadedAnalysisEngine();
  }

  public static boolean isBenchmarkEngineSyncSuppressed() {
    return benchmarkEngineSyncSuppressed;
  }

  public static boolean pauseCurrentAnalysisForBenchmark() {
    synchronized (BENCHMARK_ANALYSIS_PAUSE_LOCK) {
      if (benchmarkPreviousShowPonderTips == null && Lizzie.config != null) {
        benchmarkPreviousShowPonderTips = Lizzie.config.showPonderLimitedTips;
        Lizzie.config.showPonderLimitedTips = false;
      }
      benchmarkPausedEngineIndex = -1;
      benchmarkPausedEngineByShutdown = false;
      benchmarkEngineSyncSuppressed = true;
    }

    Leelaz currentEngine = Lizzie.leelaz;
    boolean analysisWasPondering =
        currentEngine != null && currentEngine.isLoaded() && currentEngine.isPondering();
    if (currentEngine != null
        && currentEngine.isLoaded()
        && currentEngine.isKatago
        && Lizzie.engineManager != null
        && !EngineManager.isEmpty
        && !EngineManager.isEngineGame) {
      try {
        synchronized (BENCHMARK_ANALYSIS_PAUSE_LOCK) {
          benchmarkPausedEngineIndex = EngineManager.currentEngineNo;
          benchmarkPausedEngineByShutdown = benchmarkPausedEngineIndex >= 0;
        }
        if (analysisWasPondering) {
          currentEngine.Pondering();
        } else {
          currentEngine.notPondering();
        }
        currentEngine.canCheckAlive = false;
        currentEngine.normalQuit();
        currentEngine.shutdown();
        waitForEngineShutdown(currentEngine, 8000L);
        return analysisWasPondering;
      } catch (Exception e) {
        synchronized (BENCHMARK_ANALYSIS_PAUSE_LOCK) {
          benchmarkPausedEngineIndex = -1;
          benchmarkPausedEngineByShutdown = false;
        }
      }
    }

    if (analysisWasPondering) {
      try {
        currentEngine.togglePonder();
      } catch (Exception ignored) {
      }
    }
    return analysisWasPondering;
  }

  public static void restoreAnalysisAfterBenchmark(boolean analysisWasPondering) {
    int pausedEngineIndex;
    boolean pausedEngineByShutdown;
    synchronized (BENCHMARK_ANALYSIS_PAUSE_LOCK) {
      if (benchmarkPreviousShowPonderTips != null && Lizzie.config != null) {
        Lizzie.config.showPonderLimitedTips = benchmarkPreviousShowPonderTips.booleanValue();
      }
      benchmarkPreviousShowPonderTips = null;
      pausedEngineIndex = benchmarkPausedEngineIndex;
      pausedEngineByShutdown = benchmarkPausedEngineByShutdown;
      benchmarkPausedEngineIndex = -1;
      benchmarkPausedEngineByShutdown = false;
      benchmarkEngineSyncSuppressed = false;
    }
    if (pausedEngineByShutdown && pausedEngineIndex >= 0 && Lizzie.leelaz != null) {
      try {
        if (analysisWasPondering) {
          Lizzie.leelaz.Pondering();
        } else {
          Lizzie.leelaz.notPondering();
        }
        Lizzie.leelaz.restartClosedEngine(pausedEngineIndex);
        return;
      } catch (Exception e) {
      }
    }
    if (!analysisWasPondering) {
      return;
    }
    if (Lizzie.leelaz == null || !Lizzie.leelaz.isLoaded() || Lizzie.leelaz.isPondering()) {
      return;
    }
    try {
      Lizzie.leelaz.togglePonder();
    } catch (Exception ignored) {
    }
  }

  private static boolean waitForPrimaryEngineReadyBeforeBenchmark(long timeoutMillis) {
    long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMillis);
    while (System.currentTimeMillis() < deadline) {
      Leelaz engine = Lizzie.leelaz;
      if (engine != null && engine.isLoaded()) {
        return true;
      }
      try {
        Thread.sleep(200L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  private static void waitForEngineShutdown(Leelaz engine, long timeoutMillis) {
    if (engine == null) {
      return;
    }
    long deadline = System.currentTimeMillis() + Math.max(500L, timeoutMillis);
    while (System.currentTimeMillis() < deadline) {
      if (!engine.isStarted() || engine.isProcessDead()) {
        return;
      }
      try {
        Thread.sleep(100L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  public static void startAppleSiliconAutoOptimizationAsync() {
    if (!isAppleSiliconHost() || Lizzie.config == null || Lizzie.config.uiConfig == null) {
      return;
    }
    synchronized (APPLE_AUTO_OPTIMIZE_LOCK) {
      if (appleAutoOptimizeRunning) {
        return;
      }
      appleAutoOptimizeRunning = true;
    }

    Thread worker =
        new Thread(
            () -> {
              JDialog notice = null;
              boolean pausedAnalysis = false;
              SetupSnapshot snapshot = null;
              try {
                try {
                  Thread.sleep(APPLE_AUTO_OPTIMIZE_DELAY_MILLIS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }

                snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
                if (!shouldRunAppleSiliconAutoBenchmark(snapshot)) {
                  return;
                }
                if (!waitForPrimaryEngineReadyBeforeBenchmark(
                    APPLE_AUTO_OPTIMIZE_READY_TIMEOUT_MILLIS)) {
                  return;
                }

                DownloadSession session = new DownloadSession();
                notice =
                    showBenchmarkNotice(
                        "正在进行 Apple Silicon 智能测速优化",
                        "正在后台按 KataGo 官方 benchmark 自动测试最合适的线程数。<br/>"
                            + "测速期间会暂停当前分析，完成后会自动恢复。<br/>"
                            + "你可以继续使用主窗口；如果暂时不想测速，关闭这个窗口即可停止本次测速。",
                        session);
                final JDialog progressNotice = notice;
                if (session.isCancelled()) return;
                pausedAnalysis = pauseCurrentAnalysisForBenchmark();

                System.out.println(
                    "Running Apple Silicon KataGo benchmark in background for automatic tuning...");
                BenchmarkResult result =
                    runBenchmarkAndApply(
                        snapshot,
                        (statusText, downloadedBytes, totalBytes) -> {
                          if (progressNotice != null) {
                            updateBenchmarkNotice(
                                progressNotice, statusText, downloadedBytes, totalBytes);
                          }
                        },
                        session);
                applyBenchmarkResultToRunningEngines(result);
                System.out.println(
                    "Apple Silicon KataGo tuning applied: " + formatBenchmarkResult(result));
              } catch (DownloadCancelledException e) {
                rememberStartupBenchmarkDismissal(snapshot);
                System.out.println("Apple Silicon KataGo auto benchmark cancelled by user.");
              } catch (Exception e) {
                System.err.println(
                    "Apple Silicon KataGo auto benchmark failed: " + e.getLocalizedMessage());
                e.printStackTrace();
              } finally {
                if (notice != null) {
                  disposeBenchmarkNotice(notice);
                }
                restoreAnalysisAfterBenchmark(pausedAnalysis);
                synchronized (APPLE_AUTO_OPTIMIZE_LOCK) {
                  appleAutoOptimizeRunning = false;
                }
              }
            },
            "katago-apple-auto-optimize");
    worker.setDaemon(true);
    worker.start();
  }

  private static JDialog showBenchmarkNotice(String title, String message) {
    return showBenchmarkNotice(title, message, null);
  }

  private static JDialog showBenchmarkNotice(
      String title, String message, DownloadSession cancelSession) {
    if (Lizzie.frame == null) return null;
    final JDialog[] noticeHolder = new JDialog[1];
    Runnable task =
        () -> {
          JDialog notice = createBenchmarkNotice(title, message, cancelSession);
          if (notice != null) {
            noticeHolder[0] = notice;
            notice.setVisible(true);
            updateBenchmarkNotice(
                notice,
                resource("AutoSetup.benchmarkPreparing", "Preparing benchmark..."),
                30L,
                1000L);
            notice.toFront();
            notice.repaint();
          }
        };
    if (SwingUtilities.isEventDispatchThread()) {
      task.run();
    } else {
      try {
        SwingUtilities.invokeAndWait(task);
      } catch (Exception e) {
        e.printStackTrace();
        return null;
      }
    }
    return noticeHolder[0];
  }

  private static JDialog createBenchmarkNotice(
      String title, String message, DownloadSession cancelSession) {
    if (Lizzie.frame == null) return null;
    JDialog notice = new JDialog(Lizzie.frame, title, false);
    notice.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
    if (cancelSession != null) {
      notice.getRootPane().putClientProperty("lizzie.benchmark.notice.session", cancelSession);
      notice.addWindowListener(
          new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
              cancelBenchmarkNotice(notice, cancelSession);
            }
          });
    }
    notice.setAlwaysOnTop(true);
    notice.setType(Window.Type.UTILITY);
    if (cancelSession == null) {
      notice.setFocusableWindowState(false);
    }
    JPanel panel = new JPanel(new BorderLayout(12, 12));
    panel.setOpaque(true);
    panel.setBackground(new Color(255, 248, 232));
    panel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(230, 190, 122)),
            BorderFactory.createEmptyBorder(18, 22, 18, 22)));
    JLabel msg = new JLabel("<html>" + message + "</html>");
    msg.putClientProperty("lizzie.benchmark.notice.label", Boolean.TRUE);
    panel.add(msg, BorderLayout.NORTH);
    String preparingText = resource("AutoSetup.benchmarkPreparing", "Preparing benchmark...");
    JLabel status = new JLabel(preparingText);
    status.putClientProperty("lizzie.benchmark.notice.status", Boolean.TRUE);
    panel.add(status, BorderLayout.CENTER);
    JProgressBar pb = new JProgressBar();
    pb.setIndeterminate(false);
    pb.setMinimum(0);
    pb.setMaximum(1000);
    pb.setValue(30);
    pb.setStringPainted(true);
    pb.putClientProperty(BENCHMARK_NOTICE_PROGRESS_KEY, Integer.valueOf(30));
    pb.setString("3%");
    pb.setPreferredSize(new Dimension(520, 24));
    pb.putClientProperty("lizzie.benchmark.notice.bar", Boolean.TRUE);
    JPanel bottomPanel = new JPanel(new BorderLayout(0, 8));
    bottomPanel.setOpaque(false);
    bottomPanel.add(pb, BorderLayout.CENTER);
    if (cancelSession != null) {
      JButton cancelButton = new JButton(resource("AutoSetup.stopBenchmark", "Stop benchmark"));
      cancelButton.addActionListener(e -> cancelBenchmarkNotice(notice, cancelSession));
      JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
      buttonPanel.setOpaque(false);
      buttonPanel.add(cancelButton);
      bottomPanel.add(buttonPanel, BorderLayout.SOUTH);
    }
    panel.add(bottomPanel, BorderLayout.SOUTH);
    notice.setContentPane(panel);
    notice.setMinimumSize(new Dimension(560, 150));
    notice.pack();
    notice.setLocationRelativeTo(Lizzie.frame);
    return notice;
  }

  private static void cancelBenchmarkNotice(JDialog notice, DownloadSession cancelSession) {
    if (cancelSession != null) {
      cancelSession.cancel();
    }
    if (notice != null) {
      updateBenchmarkNotice(
          notice, resource("AutoSetup.benchmarkCancelled", "Benchmark stopped."), 0L, 1000L);
      notice.dispose();
    }
  }

  private static void disposeBenchmarkNotice(JDialog notice) {
    if (notice == null) return;
    SwingUtilities.invokeLater(notice::dispose);
  }

  private static void updateBenchmarkNotice(
      JDialog notice, String statusText, long downloadedBytes, long totalBytes) {
    if (notice == null) return;
    Runnable updateTask =
        () -> {
          Component root = notice.getContentPane();
          int progressValue =
              totalBytes > 0
                  ? (int) Math.max(0L, Math.min(1000L, (downloadedBytes * 1000L) / totalBytes))
                  : -1;
          updateBenchmarkNoticeComponents(root, statusText, progressValue);
          notice.getContentPane().revalidate();
          notice.getContentPane().repaint();
          notice.getRootPane().paintImmediately(notice.getRootPane().getVisibleRect());
        };
    if (SwingUtilities.isEventDispatchThread()) {
      updateTask.run();
      return;
    }
    try {
      SwingUtilities.invokeAndWait(updateTask);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static void updateBenchmarkNoticeComponents(
      Component component, String statusText, int progressValue) {
    String displayStatus =
        statusText == null || statusText.trim().isEmpty() ? "测速中..." : statusText;
    if (component instanceof JLabel
        && Boolean.TRUE.equals(
            ((JLabel) component).getClientProperty("lizzie.benchmark.notice.status"))) {
      ((JLabel) component).setText(displayStatus);
    }
    if (component instanceof JProgressBar) {
      JProgressBar progressBar = (JProgressBar) component;
      int displayProgress = progressValue >= 0 ? progressValue : progressBar.getValue();
      int previousProgress =
          progressBar.getClientProperty(BENCHMARK_NOTICE_PROGRESS_KEY) instanceof Integer
              ? ((Integer) progressBar.getClientProperty(BENCHMARK_NOTICE_PROGRESS_KEY)).intValue()
              : 0;
      if (progressValue >= 0 && !isTerminalBenchmarkStatus(displayStatus)) {
        displayProgress = Math.max(previousProgress, progressValue);
      }
      progressBar.setIndeterminate(false);
      progressBar.setMaximum(1000);
      if (progressValue >= 0) {
        progressBar.setValue(displayProgress);
        progressBar.putClientProperty(
            BENCHMARK_NOTICE_PROGRESS_KEY, Integer.valueOf(displayProgress));
      }
      progressBar.setString(Math.max(0, Math.min(1000, displayProgress)) / 10 + "%");
    }
    if (component instanceof java.awt.Container) {
      for (Component child : ((java.awt.Container) component).getComponents()) {
        updateBenchmarkNoticeComponents(child, statusText, progressValue);
      }
    }
  }

  private static final class BenchmarkProgressTracker {
    private static final int MODEL_LOAD_PROGRESS = 30;
    private static final int THREAD_LIST_PROGRESS = 80;
    private static final int SEARCH_PROGRESS_START = 80;
    private static final int SEARCH_PROGRESS_SPAN = 900;
    private static final int SUMMARY_PROGRESS = 990;

    private final Map<Integer, Integer> completedPositionsByThread =
        new HashMap<Integer, Integer>();
    private int expectedTestedThreadCount = 0;
    private int observedThreadCount = 0;
    private int positionsPerThread = 0;
    private int lastPermille = 0;

    int update(String rawStatus) {
      String status = rawStatus == null ? "" : rawStatus.trim();
      if (status.isEmpty()) {
        return lastPermille;
      }
      if (status.contains("Loading model") || status.contains("Initializing benchmark")) {
        return advanceTo(MODEL_LOAD_PROGRESS);
      }

      Matcher possibleThreadsMatcher = BENCHMARK_POSSIBLE_THREADS_PATTERN.matcher(status);
      if (possibleThreadsMatcher.find()) {
        int possibleThreadCount = countIntegers(possibleThreadsMatcher.group(1));
        expectedTestedThreadCount =
            Math.max(
                expectedTestedThreadCount, estimateOfficialAutoTuneProbeCount(possibleThreadCount));
        return advanceTo(THREAD_LIST_PROGRESS);
      }

      Matcher progressMatcher = BENCHMARK_POSITION_PROGRESS_PATTERN.matcher(status);
      if (progressMatcher.find()) {
        int threadCount = parseIntSafely(progressMatcher.group(1));
        int completed = parseIntSafely(progressMatcher.group(2));
        int total = parseIntSafely(progressMatcher.group(3));
        if (threadCount <= 0 || total <= 0) {
          return lastPermille;
        }
        if (!completedPositionsByThread.containsKey(threadCount)) {
          observedThreadCount += 1;
        }
        positionsPerThread = Math.max(positionsPerThread, total);
        int clampedCompleted = Math.max(0, Math.min(completed, total));
        Integer previousCompleted = completedPositionsByThread.get(threadCount);
        if (previousCompleted == null || clampedCompleted > previousCompleted.intValue()) {
          completedPositionsByThread.put(threadCount, clampedCompleted);
        }
        if (expectedTestedThreadCount <= 0) {
          expectedTestedThreadCount = 6;
        }
        expectedTestedThreadCount =
            Math.max(expectedTestedThreadCount, Math.max(1, observedThreadCount));

        int completedUnits = 0;
        for (Integer value : completedPositionsByThread.values()) {
          completedUnits += Math.max(0, value.intValue());
        }
        int totalUnits = Math.max(1, expectedTestedThreadCount * positionsPerThread);
        int progress =
            SEARCH_PROGRESS_START
                + (int)
                    Math.min(
                        SEARCH_PROGRESS_SPAN,
                        (completedUnits * (long) SEARCH_PROGRESS_SPAN) / totalUnits);
        return advanceTo(Math.min(progress, 985));
      }

      if (status.contains("Ordered summary of results")
          || status.contains("So APPROXIMATELY based on this benchmark")) {
        return advanceTo(SUMMARY_PROGRESS);
      }

      return lastPermille;
    }

    private int advanceTo(int permille) {
      lastPermille = Math.max(lastPermille, Math.max(0, Math.min(1000, permille)));
      return lastPermille;
    }

    private static int countIntegers(String text) {
      if (text == null || text.trim().isEmpty()) {
        return 0;
      }
      int count = 0;
      Matcher matcher = Pattern.compile("\\d+").matcher(text);
      while (matcher.find()) {
        count += 1;
      }
      return count;
    }

    private static int estimateOfficialAutoTuneProbeCount(int possibleThreadCount) {
      if (possibleThreadCount <= 0) {
        return 6;
      }
      if (possibleThreadCount > 64) {
        return Math.max(6, (int) Math.ceil(Math.log(possibleThreadCount) / Math.log(1.5)));
      }
      boolean[] seen = new boolean[possibleThreadCount];
      return Math.max(1, estimateTernaryProbeCount(0, possibleThreadCount - 1, seen, 0));
    }

    private static int estimateTernaryProbeCount(
        int start, int end, boolean[] seen, int seenCount) {
      if (start > end) {
        return seenCount;
      }
      int firstMid = start + (end - start) / 3;
      int secondMid = end - (end - start) / 3;
      boolean firstWasSeen = seen[firstMid];
      boolean secondWasSeen = seen[secondMid];
      int nextSeenCount = seenCount;
      if (!firstWasSeen) {
        seen[firstMid] = true;
        nextSeenCount += 1;
      }
      if (secondMid != firstMid && !secondWasSeen) {
        seen[secondMid] = true;
        nextSeenCount += 1;
      }
      int leftCount = estimateTernaryProbeCount(start, secondMid - 1, seen, nextSeenCount);
      int rightCount = estimateTernaryProbeCount(firstMid + 1, end, seen, nextSeenCount);
      seen[firstMid] = firstWasSeen;
      if (secondMid != firstMid) {
        seen[secondMid] = secondWasSeen;
      }
      return Math.max(leftCount, rightCount);
    }
  }

  private static void notifyProgress(
      ProgressListener listener, String statusText, long downloadedBytes, long totalBytes) {
    if (listener != null) {
      listener.onProgress(statusText == null ? "" : statusText, downloadedBytes, totalBytes);
    }
  }

  /**
   * Run a one-time KataGo benchmark on the first launch so default thread counts reflect the actual
   * hardware. Shows a non-modal notification to the user while the benchmark runs. No-op if a
   * benchmark result is already stored, if no engine is available, or on Apple Silicon (handled by
   * {@link #startAppleSiliconAutoOptimizationAsync()}).
   */
  public static void startFirstRunBenchmarkAsync() {
    if (Lizzie.config == null || Lizzie.config.uiConfig == null) return;
    if (isAppleSiliconHost()) return;
    if (!Lizzie.config.enableStartupBenchmark) return;
    if (getStoredBenchmarkResult() != null) return;

    Thread worker =
        new Thread(
            () -> {
              try {
                Thread.sleep(3000L);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              SetupSnapshot snapshot;
              try {
                snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
              } catch (Exception e) {
                return;
              }
              if (snapshot == null
                  || !snapshot.hasEngine()
                  || !snapshot.hasConfigs()
                  || !snapshot.hasWeight()) {
                return;
              }
              if (getStoredBenchmarkResult() != null) return;
              if (isStartupBenchmarkDismissed(snapshot)) return;

              final DownloadSession benchmarkSession = new DownloadSession();
              final javax.swing.JDialog notice =
                  Lizzie.frame != null && Lizzie.frame.isShowing()
                      ? showBenchmarkNotice(
                          "KataGo 智能测速",
                          "首次启动正在进行一次 KataGo 智能测速优化，<br/>"
                              + "用于自动选出最适合你这台电脑的线程数，<br/>"
                              + "完成后分析速度会更稳定、更快。<br/><br/>"
                              + "这是 KataGo 官方 benchmark 流程，可能需要数分钟，请稍候，<br/>"
                              + "期间分析会被暂停，完成后会自动恢复。<br/>"
                              + "如果暂时不想测速，可以关闭这个窗口停止测速。",
                          benchmarkSession)
                      : null;

              boolean pausedAnalysis = false;

              try {
                if (benchmarkSession.isCancelled()) {
                  return;
                }
                pausedAnalysis = pauseCurrentAnalysisForBenchmark();
                BenchmarkResult result =
                    runBenchmarkAndApply(
                        snapshot,
                        (statusText, downloadedBytes, totalBytes) ->
                            updateBenchmarkNotice(notice, statusText, downloadedBytes, totalBytes),
                        benchmarkSession);
                applyBenchmarkResultToRunningEngines(result);
                System.out.println(
                    "First-run KataGo benchmark applied: " + formatBenchmarkResult(result));
              } catch (DownloadCancelledException e) {
                rememberStartupBenchmarkDismissal(snapshot);
                System.out.println("First-run KataGo benchmark cancelled by user.");
              } catch (Exception e) {
                System.err.println("First-run KataGo benchmark failed: " + e.getLocalizedMessage());
              } finally {
                if (notice != null) {
                  disposeBenchmarkNotice(notice);
                }
                restoreAnalysisAfterBenchmark(pausedAnalysis);
              }
            },
            "katago-first-run-benchmark");
    worker.setDaemon(true);
    worker.start();
  }

  public static String optimizeAnalysisEngineCommand(
      String engineCommand, int maxVisits, boolean isBatchAnalysisMode) {
    if (engineCommand == null || engineCommand.trim().isEmpty()) {
      return engineCommand;
    }

    List<String> commandParts = Utils.splitCommand(engineCommand);
    if (commandParts.isEmpty()) {
      return engineCommand;
    }

    boolean hasSearchThreadOverride =
        hasOverrideConfigKey(commandParts, "numSearchThreadsPerAnalysisThread")
            || hasOverrideConfigKey(commandParts, "numSearchThreads");
    boolean hasAnalysisThreadOverride = hasOverrideConfigKey(commandParts, "numAnalysisThreads");
    boolean commandChanged = false;
    if (looksLikeKataGoCommand(engineCommand)) {
      commandChanged = appendAnalysisPvLenOverride(commandParts);
    }

    if (shouldUseAppleSiliconAnalysisProfile(engineCommand)) {
      AnalysisThreadProfile profile =
          resolveAppleSiliconAnalysisProfile(maxVisits, isBatchAnalysisMode);
      if (!hasAnalysisThreadOverride) {
        appendOverrideConfig(commandParts, "numAnalysisThreads=" + profile.numAnalysisThreads);
        commandChanged = true;
      }
      if (!hasSearchThreadOverride) {
        appendOverrideConfig(
            commandParts,
            "numSearchThreadsPerAnalysisThread=" + profile.numSearchThreadsPerAnalysisThread);
        commandChanged = true;
      }
      return buildCommandLine(commandParts);
    }

    if (maxVisits <= 36 && !hasSearchThreadOverride) {
      appendOverrideConfig(
          commandParts, "numSearchThreadsPerAnalysisThread=" + Math.max(1, maxVisits / 10));
      return buildCommandLine(commandParts);
    }

    return commandChanged ? buildCommandLine(commandParts) : engineCommand;
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

  private static boolean shouldUseAppleSiliconAnalysisProfile(String engineCommand) {
    if (!isAppleSiliconHost()) {
      return false;
    }
    String normalized = engineCommand == null ? "" : engineCommand.toLowerCase(Locale.ROOT);
    if (!normalized.contains(" analysis")) {
      return false;
    }
    return Config.isBundledKataGoCommand(engineCommand);
  }

  private static boolean looksLikeKataGoCommand(String engineCommand) {
    String normalized = engineCommand == null ? "" : engineCommand.toLowerCase(Locale.ROOT);
    return normalized.contains("katago");
  }

  private static AnalysisThreadProfile resolveAppleSiliconAnalysisProfile(
      int maxVisits, boolean isBatchAnalysisMode) {
    int totalThreadBudget = Math.max(4, Math.min(16, Utils.getRecommendedKataGoThreads()));
    int effectiveVisits = Math.max(1, maxVisits);
    int perAnalysisThread;
    int maxParallelAnalyses;

    if (effectiveVisits <= 8) {
      perAnalysisThread = 1;
      maxParallelAnalyses = MAX_APPLE_ANALYSIS_THREADS;
    } else if (effectiveVisits <= 36) {
      perAnalysisThread = 2;
      maxParallelAnalyses = 6;
    } else if (effectiveVisits <= 100) {
      perAnalysisThread = 2;
      maxParallelAnalyses = 5;
    } else if (effectiveVisits <= 220) {
      perAnalysisThread = 3;
      maxParallelAnalyses = 4;
    } else {
      perAnalysisThread = Math.min(4, Math.max(2, totalThreadBudget / 3));
      maxParallelAnalyses = 3;
    }

    if (isBatchAnalysisMode && effectiveVisits >= 100) {
      perAnalysisThread = Math.max(perAnalysisThread, 3);
      maxParallelAnalyses = Math.min(maxParallelAnalyses, 4);
    }

    int numAnalysisThreads =
        Math.max(
            2, Math.min(maxParallelAnalyses, Math.max(1, totalThreadBudget / perAnalysisThread)));

    if (effectiveVisits <= 12 && totalThreadBudget >= 6) {
      numAnalysisThreads =
          Math.max(numAnalysisThreads, Math.min(MAX_APPLE_ANALYSIS_THREADS, totalThreadBudget));
    }

    return new AnalysisThreadProfile(numAnalysisThreads, perAnalysisThread);
  }

  private static Path getBundledHomeDataDir() {
    if (Lizzie.config == null) {
      return null;
    }
    return Lizzie.config
        .getRuntimeWorkDirectory()
        .toPath()
        .resolve(BUNDLED_HOME_DATA_DIR)
        .toAbsolutePath()
        .normalize();
  }

  private static void appendOverrideConfig(List<String> command, String keyValue) {
    if (command == null || keyValue == null || keyValue.trim().isEmpty()) {
      return;
    }
    String normalizedKey = overrideConfigKey(keyValue);

    for (int i = 0; i < command.size(); i++) {
      if (!"-override-config".equals(command.get(i))) {
        continue;
      }

      if (i + 1 >= command.size()) {
        command.add(keyValue);
        return;
      }

      String existing = command.get(i + 1);
      if (!normalizedKey.isEmpty() && containsOverrideConfigKey(existing, normalizedKey)) {
        return;
      }
      if (existing == null || existing.trim().isEmpty()) {
        command.set(i + 1, keyValue);
      } else {
        command.set(i + 1, existing + "," + keyValue);
      }
      return;
    }

    command.add("-override-config");
    command.add(keyValue);
  }

  private static boolean appendAnalysisPvLenOverride(List<String> command) {
    int pvLen = resolveAnalysisPvLenOverride();
    if (pvLen <= 0 || hasOverrideConfigKey(command, "analysisPVLen")) {
      return false;
    }
    appendOverrideConfig(command, "analysisPVLen=" + pvLen);
    return true;
  }

  static int resolveAnalysisPvLenOverride() {
    if (Lizzie.config == null) {
      return 15;
    }
    return Math.max(0, Lizzie.config.limitBranchLength);
  }

  private static boolean hasOverrideConfigKey(List<String> command, String key) {
    if (command == null || key == null || key.trim().isEmpty()) {
      return false;
    }
    String normalizedKey = key.trim().toLowerCase(Locale.ROOT);
    for (int i = 0; i < command.size(); i++) {
      if (!"-override-config".equals(command.get(i)) || i + 1 >= command.size()) {
        continue;
      }
      String overrideValue = command.get(i + 1);
      if (overrideValue == null || overrideValue.trim().isEmpty()) {
        continue;
      }
      if (containsOverrideConfigKey(overrideValue, normalizedKey)) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsOverrideConfigKey(String overrideValue, String normalizedKey) {
    if (overrideValue == null || normalizedKey == null || normalizedKey.trim().isEmpty()) {
      return false;
    }
    for (String entry : overrideValue.split(",")) {
      String trimmed = entry == null ? "" : entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int eqIndex = trimmed.indexOf('=');
      String entryKey = eqIndex >= 0 ? trimmed.substring(0, eqIndex).trim() : trimmed.trim();
      if (entryKey.toLowerCase(Locale.ROOT).equals(normalizedKey)) {
        return true;
      }
    }
    return false;
  }

  private static String overrideConfigKey(String keyValue) {
    String trimmed = keyValue == null ? "" : keyValue.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    int eqIndex = trimmed.indexOf('=');
    String key = eqIndex >= 0 ? trimmed.substring(0, eqIndex).trim() : trimmed;
    return key.toLowerCase(Locale.ROOT);
  }

  private static String buildCommandLine(List<String> commands) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < commands.size(); i++) {
      if (i > 0) {
        builder.append(' ');
      }
      builder.append(quoteCommandToken(commands.get(i)));
    }
    return builder.toString();
  }

  private static String quoteCommandToken(String token) {
    if (token == null) {
      return "\"\"";
    }
    String trimmed = token.trim();
    if (trimmed.isEmpty()) {
      return "\"\"";
    }
    if (trimmed.indexOf(' ') >= 0 || trimmed.indexOf('\t') >= 0 || trimmed.indexOf('"') >= 0) {
      return "\"" + trimmed.replace("\"", "\\\"") + "\"";
    }
    return trimmed;
  }

  private static boolean isAppleSiliconHost() {
    String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    return (osName.contains("mac") || osName.contains("darwin"))
        && (arch.contains("arm64") || arch.contains("aarch64"));
  }

  static boolean shouldRunAppleSiliconAutoBenchmark(SetupSnapshot snapshot) {
    if (Lizzie.config == null
        || Lizzie.config.uiConfig == null
        || !Lizzie.config.enableStartupBenchmark) {
      return false;
    }
    if (!isAppleSiliconOptimizationEligible(snapshot)) {
      return false;
    }
    if (isStartupBenchmarkDismissed(snapshot)) {
      return false;
    }
    BenchmarkResult benchmarkResult = getStoredBenchmarkResult();
    if (benchmarkResult == null || benchmarkResult.recommendedThreads <= 0) {
      return true;
    }
    String backend = benchmarkResult.backendLabel == null ? "" : benchmarkResult.backendLabel;
    if (!backend.toLowerCase(Locale.ROOT).contains("metal")) {
      return true;
    }
    String expectedSignature = buildBenchmarkSignature(snapshot);
    String storedSignature =
        Lizzie.config == null || Lizzie.config.uiConfig == null
            ? ""
            : Lizzie.config.uiConfig.optString(BENCHMARK_SIGNATURE_KEY, "").trim();
    if (!expectedSignature.equals(storedSignature)) {
      return true;
    }
    int storedVersion =
        Lizzie.config == null || Lizzie.config.uiConfig == null
            ? 0
            : Lizzie.config.uiConfig.optInt(APPLE_AUTO_OPTIMIZE_VERSION_KEY, 0);
    return storedVersion < APPLE_AUTO_OPTIMIZE_VERSION;
  }

  static boolean isStartupBenchmarkDismissed(SetupSnapshot snapshot) {
    if (snapshot == null || Lizzie.config == null || Lizzie.config.uiConfig == null) {
      return false;
    }
    String expectedSignature = buildBenchmarkSignature(snapshot);
    String dismissedSignature =
        Lizzie.config.uiConfig.optString(BENCHMARK_DISMISSED_SIGNATURE_KEY, "").trim();
    if (!expectedSignature.equals(dismissedSignature)) {
      return false;
    }
    int dismissedVersion = Lizzie.config.uiConfig.optInt(BENCHMARK_DISMISSED_VERSION_KEY, 0);
    return dismissedVersion >= APPLE_AUTO_OPTIMIZE_VERSION;
  }

  static void rememberStartupBenchmarkDismissal(SetupSnapshot snapshot) {
    if (snapshot == null || Lizzie.config == null || Lizzie.config.uiConfig == null) {
      return;
    }
    Lizzie.config.uiConfig.put(
        BENCHMARK_DISMISSED_SIGNATURE_KEY, buildBenchmarkSignature(snapshot));
    Lizzie.config.uiConfig.put(BENCHMARK_DISMISSED_VERSION_KEY, APPLE_AUTO_OPTIMIZE_VERSION);
    try {
      Lizzie.config.save();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static boolean isAppleSiliconOptimizationEligible(SetupSnapshot snapshot) {
    if (!isAppleSiliconHost() || snapshot == null) {
      return false;
    }
    if (!snapshot.hasEngine() || !snapshot.hasConfigs() || !snapshot.hasWeight()) {
      return false;
    }
    String enginePath =
        snapshot.enginePath.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    return enginePath.contains("macos-arm64");
  }

  private static void rememberBenchmarkContext(SetupSnapshot snapshot, BenchmarkResult result) {
    if (!isAppleSiliconOptimizationEligible(snapshot)
        || result == null
        || result.recommendedThreads <= 0
        || Lizzie.config == null
        || Lizzie.config.uiConfig == null) {
      return;
    }
    Lizzie.config.uiConfig.put(BENCHMARK_SIGNATURE_KEY, buildBenchmarkSignature(snapshot));
    Lizzie.config.uiConfig.put(APPLE_AUTO_OPTIMIZE_VERSION_KEY, APPLE_AUTO_OPTIMIZE_VERSION);
    try {
      Lizzie.config.save();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static String buildBenchmarkSignature(SetupSnapshot snapshot) {
    if (snapshot == null) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    appendPathFingerprint(builder, snapshot.enginePath);
    appendPathFingerprint(builder, snapshot.gtpConfigPath);
    appendPathFingerprint(builder, snapshot.analysisConfigPath);
    appendPathFingerprint(builder, snapshot.activeWeightPath);
    return builder.toString();
  }

  private static void appendPathFingerprint(StringBuilder builder, Path path) {
    if (path == null) {
      builder.append("|missing");
      return;
    }
    Path normalized = path.toAbsolutePath().normalize();
    builder.append('|').append(normalized);
    try {
      builder.append(':').append(Files.size(normalized));
      builder.append(':').append(Files.getLastModifiedTime(normalized).toMillis());
    } catch (IOException e) {
      builder.append(":0:0");
    }
  }

  private static void restartIdlePreloadedAnalysisEngine() {
    if (Lizzie.config == null
        || !Lizzie.config.analysisEnginePreLoad
        || Lizzie.frame == null
        || Lizzie.frame.analysisEngine == null) {
      return;
    }
    AnalysisEngine currentEngine = Lizzie.frame.analysisEngine;
    if (currentEngine == null || currentEngine.isAnalysisInProgress()) {
      return;
    }
    SwingUtilities.invokeLater(
        () -> {
          try {
            if (Lizzie.frame == null || Lizzie.frame.analysisEngine == null) {
              return;
            }
            if (Lizzie.frame.analysisEngine.isAnalysisInProgress()) {
              return;
            }
            Lizzie.frame.destroyAnalysisEngine();
            Lizzie.frame.analysisEngine = new AnalysisEngine(true);
          } catch (IOException e) {
            e.printStackTrace();
          }
        });
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

  private static List<List<String>> requiredRuntimeDllGroups(String backend) {
    if (NVIDIA50_TRT_BACKEND.equalsIgnoreCase(backend)) {
      return REQUIRED_NVIDIA_TRT10_9_RUNTIME_DLL_GROUPS;
    }
    if (NVIDIA50_CUDA_BACKEND.equalsIgnoreCase(backend)) {
      return REQUIRED_NVIDIA_CUDA12_8_RUNTIME_DLL_GROUPS;
    }
    return REQUIRED_NVIDIA_CUDA12_1_RUNTIME_DLL_GROUPS;
  }

  private static List<String> collectMissingRuntimeGroups(
      List<Path> searchDirs, List<List<String>> requiredDllGroups) {
    List<String> missing = new ArrayList<String>();
    for (List<String> requirementGroup : requiredDllGroups) {
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

  private static Path findDirectoryContainingRequiredDlls(
      List<Path> searchDirs, List<List<String>> requiredDllGroups) {
    for (Path dir : searchDirs) {
      if (dir == null) {
        continue;
      }
      boolean allPresent = true;
      for (List<String> requirementGroup : requiredDllGroups) {
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
            status != null
                    && NVIDIA50_TRT_BACKEND.equalsIgnoreCase(
                        resolveNvidiaBackend(status.enginePath))
                ? resource(
                    "AutoSetup.tensorRtRuntimeMissing",
                    "TensorRT runtime is not installed. Open KataGo Auto Setup and install TensorRT acceleration.")
                : resource(
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

  static TensorRtInstallSpec buildTensorRtInstallSpec(SetupSnapshot snapshot) {
    Path runtimeRoot =
        Lizzie.config != null
            ? Lizzie.config.getRuntimeWorkDirectory().toPath()
            : Paths.get(System.getProperty("user.dir", "."))
                .toAbsolutePath()
                .normalize()
                .resolve("runtime");
    Path targetEngineDir =
        runtimeRoot.resolve("engines").resolve("katago").resolve(NVIDIA50_TRT_ENGINE_DIR);
    Path targetEnginePath = targetEngineDir.resolve("katago.exe");
    long katagoSize =
        resolveLongProperty(TENSORRT_KATAGO_SIZE_PROPERTY, TENSORRT_KATAGO_SIZE_BYTES);
    long total =
        katagoSize
            + CUDA_12_8_CUDART_SIZE_BYTES
            + CUDA_12_8_CUBLAS_SIZE_BYTES
            + CUDA_12_8_NVJITLINK_SIZE_BYTES
            + CUDNN_9_SIZE_BYTES
            + TENSORRT_RUNTIME_SIZE_BYTES;
    return new TensorRtInstallSpec(
        targetEngineDir,
        targetEnginePath,
        System.getProperty(TENSORRT_KATAGO_URL_PROPERTY, TENSORRT_KATAGO_URL),
        System.getProperty(TENSORRT_KATAGO_SHA256_PROPERTY, TENSORRT_KATAGO_SHA256),
        katagoSize,
        total,
        Boolean.getBoolean(TENSORRT_SKIP_RUNTIME_FOR_TESTS_PROPERTY) ? 0 : 5);
  }

  private static boolean isTensorRtSourceProfileAllowed(SetupSnapshot snapshot) {
    if (snapshot == null || snapshot.enginePath == null) {
      return false;
    }
    String backend = resolveNvidiaBackend(snapshot.enginePath);
    return NVIDIA_BACKEND.equals(backend)
        || NVIDIA50_CUDA_BACKEND.equals(backend)
        || NVIDIA50_TRT_BACKEND.equals(backend);
  }

  private static long resolveLongProperty(String key, long fallback) {
    String value = System.getProperty(key, "").trim();
    if (value.isEmpty()) {
      return fallback;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static Path downloadPackageWithAggregateProgress(
      RuntimePackageSpec spec,
      Path archivePath,
      DownloadSession session,
      ProgressListener listener,
      long completedBeforePackage,
      long totalBytes)
      throws IOException {
    downloadRuntimePackage(
        spec,
        archivePath,
        session,
        (statusText, downloadedBytes, packageTotalBytes) -> {
          long knownPackageBytes =
              packageTotalBytes > 0 ? packageTotalBytes : Math.max(0L, spec.sizeBytes);
          long boundedDownloaded =
              knownPackageBytes > 0
                  ? Math.min(Math.max(0L, downloadedBytes), knownPackageBytes)
                  : Math.max(0L, downloadedBytes);
          notifyProgress(
              listener,
              statusText,
              completedBeforePackage + boundedDownloaded,
              totalBytes > 0 ? totalBytes : completedBeforePackage + knownPackageBytes);
        });
    return archivePath;
  }

  private static void installTensorRtKataGoArchive(
      Path archivePath, Path targetEngineDir, DownloadSession session) throws IOException {
    Path parent = targetEngineDir.getParent();
    Files.createDirectories(parent);
    String suffix = Long.toHexString(System.nanoTime());
    Path stagingDir =
        parent.resolve(targetEngineDir.getFileName().toString() + ".installing-" + suffix);
    Path backupDir = parent.resolve(targetEngineDir.getFileName().toString() + ".backup-" + suffix);
    try {
      Files.createDirectories(stagingDir);
      extractKatagoEnginePackage(archivePath, stagingDir);
      session.throwIfCancelled();
      Files.write(
          stagingDir.resolve(ENGINE_BACKEND_MARKER_NAME),
          (NVIDIA50_TRT_BACKEND + "\n").getBytes(StandardCharsets.UTF_8));
      if (!Files.isRegularFile(stagingDir.resolve("katago.exe"))) {
        throw new IOException("KataGo TensorRT package did not contain katago.exe");
      }
      if (Files.exists(targetEngineDir)) {
        Files.move(targetEngineDir, backupDir, StandardCopyOption.REPLACE_EXISTING);
      }
      try {
        Files.move(stagingDir, targetEngineDir, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        if (Files.exists(backupDir) && !Files.exists(targetEngineDir)) {
          Files.move(backupDir, targetEngineDir, StandardCopyOption.REPLACE_EXISTING);
        }
        throw e;
      }
      deleteRecursively(backupDir);
    } catch (IOException e) {
      deleteRecursively(stagingDir);
      throw e;
    } finally {
      deleteRecursively(backupDir);
    }
  }

  private static void extractKatagoEnginePackage(Path archivePath, Path targetDir)
      throws IOException {
    try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(archivePath))) {
      ZipEntry entry;
      while ((entry = zipInputStream.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        String fileName = Paths.get(entry.getName().replace('\\', '/')).getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        if ("katago.exe".equals(lower) || "cacert.pem".equals(lower) || lower.endsWith(".dll")) {
          copyZipEntry(zipInputStream, targetDir.resolve(fileName));
        }
      }
    }
  }

  private static void deleteRecursively(Path path) throws IOException {
    if (path == null || !Files.exists(path)) {
      return;
    }
    try (Stream<Path> stream = Files.walk(path)) {
      List<Path> paths = new ArrayList<Path>();
      stream.forEach(paths::add);
      paths.sort(Comparator.reverseOrder());
      for (Path candidate : paths) {
        Files.deleteIfExists(candidate);
      }
    }
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

  private static List<RuntimePackageSpec> resolveTensorRtRuntimePackages() throws IOException {
    if (Boolean.getBoolean(TENSORRT_SKIP_RUNTIME_FOR_TESTS_PROPERTY)) {
      return new ArrayList<RuntimePackageSpec>();
    }
    List<RuntimePackageSpec> packages = new ArrayList<RuntimePackageSpec>();
    JSONObject cudaManifest = new JSONObject(httpGet(CUDA_12_8_MANIFEST_URL));
    JSONObject cudnnManifest = new JSONObject(httpGet(CUDNN_9_MANIFEST_URL));
    packages.add(
        readPackageSpec(
            cudaManifest, CUDA_12_8_MANIFEST_URL, "cuda_cudart", "windows-x86_64", "CUDA Runtime"));
    packages.add(
        readPackageSpec(
            cudaManifest, CUDA_12_8_MANIFEST_URL, "libcublas", "windows-x86_64", "CUDA cuBLAS"));
    packages.add(
        readPackageSpec(
            cudaManifest,
            CUDA_12_8_MANIFEST_URL,
            "libnvjitlink",
            "windows-x86_64",
            "CUDA nvJitLink"));
    packages.add(
        readNestedPackageSpec(
            cudnnManifest,
            CUDNN_9_MANIFEST_URL,
            "cudnn",
            "windows-x86_64",
            "cuda12",
            "NVIDIA cuDNN"));
    packages.add(
        new RuntimePackageSpec(
            "NVIDIA TensorRT",
            "10.9.0.34",
            TENSORRT_RUNTIME_URL,
            System.getProperty(TENSORRT_RUNTIME_SHA256_PROPERTY, ""),
            TENSORRT_RUNTIME_SIZE_BYTES,
            "tensorrt"));
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

  private static RuntimePackageSpec readNestedPackageSpec(
      JSONObject manifest,
      String manifestUrl,
      String key,
      String platformKey,
      String nestedPlatformKey,
      String displayName)
      throws IOException {
    JSONObject packageJson = manifest.optJSONObject(key);
    if (packageJson == null) {
      throw new IOException("Missing NVIDIA package metadata: " + key);
    }
    JSONObject platformJson = packageJson.optJSONObject(platformKey);
    if (platformJson == null) {
      throw new IOException("Missing NVIDIA platform metadata: " + key + " " + platformKey);
    }
    JSONObject nestedJson = platformJson.optJSONObject(nestedPlatformKey);
    if (nestedJson == null) {
      throw new IOException(
          "Missing NVIDIA platform metadata: " + key + " " + platformKey + "/" + nestedPlatformKey);
    }
    String relativePath = nestedJson.optString("relative_path", "").trim();
    String sha256 = nestedJson.optString("sha256", "").trim();
    long sizeBytes = parseLongSafely(nestedJson.optString("size", "0"));
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
    if (Files.isRegularFile(archivePath)) {
      boolean cacheValid =
          !Utils.isBlank(spec.sha256)
              ? spec.sha256.equalsIgnoreCase(sha256(archivePath))
              : spec.sizeBytes <= 0 || Files.size(archivePath) == spec.sizeBytes;
      if (cacheValid) {
        if (listener != null) {
          listener.onProgress(spec.displayName, spec.sizeBytes, spec.sizeBytes);
        }
        return;
      }
    }

    Files.createDirectories(archivePath.getParent());
    Path tempPath = archivePath.resolveSibling(archivePath.getFileName().toString() + ".part");
    Files.deleteIfExists(tempPath);
    URLConnection conn = null;
    HttpURLConnection httpConn = null;
    try {
      conn = URI.create(spec.url).toURL().openConnection();
      if (conn instanceof HttpURLConnection) {
        httpConn = (HttpURLConnection) conn;
        session.attach(httpConn);
      }
      session.throwIfCancelled();
      conn.setConnectTimeout(15000);
      conn.setReadTimeout(30000);
      conn.setRequestProperty("User-Agent", USER_AGENT);
      if (httpConn != null) {
        httpConn.setInstanceFollowRedirects(true);
        httpConn.setRequestMethod("GET");
        int responseCode = httpConn.getResponseCode();
        if (responseCode < 200 || responseCode >= 400) {
          throw new IOException("HTTP " + responseCode + " from " + spec.url);
        }
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
      if (!Utils.isBlank(spec.sha256) && !spec.sha256.equalsIgnoreCase(actualSha256)) {
        throw new IOException("SHA-256 mismatch for " + spec.displayName);
      }
      if (Utils.isBlank(spec.sha256)
          && spec.sizeBytes > 0
          && Files.size(tempPath) != spec.sizeBytes) {
        throw new IOException("Size mismatch for " + spec.displayName);
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
      if (httpConn != null) {
        httpConn.disconnect();
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
        if (lower.endsWith(".dll") && shouldExtractRuntimeDll(spec, lower)) {
          copyZipEntry(zipInputStream, runtimeDir.resolve(fileName));
        } else if (lower.equals("license.txt")
            || entryName.toLowerCase(Locale.ROOT).contains("/license")) {
          copyZipEntry(zipInputStream, licenseDir.resolve(spec.key + "-" + fileName));
        }
      }
    }
  }

  private static boolean shouldExtractRuntimeDll(RuntimePackageSpec spec, String lowerFileName) {
    if (spec != null && "tensorrt".equals(spec.key)) {
      return lowerFileName.startsWith("nvinfer")
          || lowerFileName.startsWith("nvonnxparser")
          || lowerFileName.startsWith("onnx_proto")
          || lowerFileName.startsWith("myelin");
    }
    return true;
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
      conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
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

  private static int resolveBenchmarkTimeSeconds() {
    int seconds = 5;
    if (Lizzie.config != null) {
      seconds = Math.max(seconds, Lizzie.config.maxGameThinkingTimeSeconds);
    }
    return Math.max(BENCHMARK_MIN_TIME_SECONDS, Math.min(BENCHMARK_MAX_TIME_SECONDS, seconds));
  }

  private static String trimStatusForUi(String line) {
    String trimmed = line == null ? "" : line.replace('\r', '\n').trim();
    if (trimmed.contains("\n")) {
      String[] parts = trimmed.split("\\n");
      for (int i = parts.length - 1; i >= 0; i--) {
        if (!parts[i].trim().isEmpty()) {
          trimmed = parts[i].trim();
          break;
        }
      }
    }
    if (trimmed.isEmpty()) {
      return resource("AutoSetup.benchmarking", "Optimizing KataGo...");
    }
    Matcher progressMatcher = BENCHMARK_POSITION_PROGRESS_PATTERN.matcher(trimmed);
    if (progressMatcher.find()) {
      return String.format(
          resource("AutoSetup.benchmarkThreadProgress", "Testing threads %d: %d/%d positions"),
          parseIntSafely(progressMatcher.group(1)),
          parseIntSafely(progressMatcher.group(2)),
          parseIntSafely(progressMatcher.group(3)));
    }
    if (BENCHMARK_POSSIBLE_THREADS_PATTERN.matcher(trimmed).find()) {
      return resource(
          "AutoSetup.benchmarkOfficialTune", "Running KataGo official benchmark thread search...");
    }
    if (trimmed.length() > 120) {
      return trimmed.substring(0, 120) + "...";
    }
    return trimmed;
  }

  static int estimateSyntheticBenchmarkPermille(
      long elapsedMillis, long sinceLastProgressMillis, int lastProgressPermille) {
    long elapsed = Math.max(0L, elapsedMillis);
    long silent = Math.max(0L, sinceLastProgressMillis);
    int last = Math.max(0, Math.min(1000, lastProgressPermille));
    if (elapsed <= 8000L) {
      return Math.max(last, 40 + (int) ((elapsed * 80L) / 8000L));
    }
    if (elapsed <= 30000L) {
      return Math.max(last, 120 + (int) (((elapsed - 8000L) * 180L) / 22000L));
    }
    if (elapsed <= 300000L) {
      int synthetic = 300 + (int) (((elapsed - 30000L) * 580L) / 270000L);
      return Math.max(last, Math.max(synthetic, smoothSilentBenchmarkProgress(last, silent)));
    }
    return Math.max(last, Math.max(880, smoothSilentBenchmarkProgress(last, silent)));
  }

  static int smoothSilentBenchmarkProgress(int lastProgressPermille, long sinceLastProgressMillis) {
    int last = Math.max(0, Math.min(1000, lastProgressPermille));
    if (last < 820 || sinceLastProgressMillis < 2500L) {
      return last;
    }
    long extraSeconds = Math.max(0L, (sinceLastProgressMillis - 2500L) / 1000L);
    int ceiling = last >= 970 ? 985 : 970;
    int smoothed = last + (int) Math.min(ceiling - last, extraSeconds * 8L);
    return Math.max(last, Math.min(ceiling, smoothed));
  }

  private static String formatBenchmarkHeartbeatStatus(long elapsedMillis) {
    long elapsed = Math.max(0L, elapsedMillis);
    if (elapsed <= 8000L) {
      return resource("AutoSetup.benchmarkLoadingModel", "Loading KataGo model...")
          + "  "
          + formatDuration(elapsed);
    }
    return resource(
            "AutoSetup.benchmarkOfficialTuneRunning", "KataGo official benchmark is running...")
        + "  "
        + formatDuration(elapsed);
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

  private static boolean isTerminalBenchmarkStatus(String statusText) {
    String lowered = statusText == null ? "" : statusText.toLowerCase(Locale.ROOT);
    return lowered.contains("complete")
        || lowered.contains("done")
        || lowered.contains("stopped")
        || lowered.contains("cancelled")
        || lowered.contains("canceled")
        || lowered.contains("完成")
        || lowered.contains("停止")
        || lowered.contains("取消");
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
