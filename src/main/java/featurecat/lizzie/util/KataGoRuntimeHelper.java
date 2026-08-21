package featurecat.lizzie.util;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.AnalysisResourceCoordinator;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.KataEstimate;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadCancelledException;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadSession;
import featurecat.lizzie.util.KataGoAutoSetupHelper.ProgressListener;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupResult;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.katago.tuning.AppleSiliconHardwareProbe;
import featurecat.lizzie.util.katago.tuning.AppleSiliconTuningPlanner;
import featurecat.lizzie.util.katago.tuning.KataGoBenchmarkObservation;
import featurecat.lizzie.util.katago.tuning.KataGoBenchmarkParser;
import featurecat.lizzie.util.katago.tuning.KataGoCommandSpec;
import featurecat.lizzie.util.katago.tuning.KataGoExperimentalTuningSelector;
import featurecat.lizzie.util.katago.tuning.KataGoTuningCandidate;
import featurecat.lizzie.util.katago.tuning.KataGoTuningFingerprint;
import featurecat.lizzie.util.katago.tuning.KataGoTuningProfile;
import featurecat.lizzie.util.katago.tuning.KataGoTuningStore;
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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.json.JSONArray;
import org.json.JSONObject;

public final class KataGoRuntimeHelper {
  public enum LaunchPurpose {
    MAIN_GTP,
    ANALYSIS,
    ESTIMATE,
    HUMAN_SL,
    BENCHMARK
  }

  private static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";
  private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;
  private static final String NVIDIA_ENGINE_DIR = "windows-x64-nvidia";
  private static final String NVIDIA50_CUDA_ENGINE_DIR = "windows-x64-nvidia50-cuda";
  private static final String NVIDIA_TRT_ENGINE_DIR = "windows-x64-nvidia-tensorrt";
  private static final String NVIDIA50_TRT_ENGINE_DIR = "windows-x64-nvidia50-trt";
  private static final String NVIDIA_BACKEND = "nvidia";
  private static final String NVIDIA50_CUDA_BACKEND = "nvidia50-cuda";
  private static final String NVIDIA_TRT_BACKEND = "nvidia-tensorrt";
  private static final String NVIDIA50_TRT_BACKEND = "nvidia50-trt";
  static final String HUMAN_SL_CUDA_COMPANION_NAME = "katago-human-sl-cuda.exe";
  private static final String OPENCL_BACKEND = "opencl";
  private static final String ENGINE_BACKEND_MARKER_NAME = "lizzieyzy-next-engine-backend.txt";
  private static final String NVIDIA_RUNTIME_ROOT = "nvidia-runtime";
  private static final String NVIDIA_RUNTIME_DOWNLOAD_CACHE_DIR = "downloads";
  private static final String NVIDIA_RUNTIME_CACHE_DIR = "cache";
  private static final String NVIDIA_CUDA_CACHE_DIR = "cuda";
  private static final String NVIDIA_TENSORRT_TEMP_DIR = "temp";
  private static final String NVIDIA_DOWNLOAD_HOST_COM = "developer.download.nvidia.com";
  private static final String NVIDIA_DOWNLOAD_HOST_CN = "developer.download.nvidia.cn";
  private static final String BUNDLED_HOME_DATA_DIR = "katago-home";
  private static final String OPENCL_FP32_HOME_DATA_DIR = "katago-home-opencl-fp32";
  private static final String OPENCL_FP32_COMPATIBILITY_MARKER = "compatibility-signature.txt";
  private static final String OPENCL_TUNING_CACHE_GENERATION_MARKER =
      "lizzie-opencl-tuning-generation.txt";
  private static final String OPENCL_TUNING_CACHE_GENERATION = "serialized-launch-v1";
  private static final String OPENCL_NVIDIA_DRIVER_VERSION_PROPERTY =
      "lizzie.opencl.nvidiaDriverVersion";
  private static final int WINDOWS_FAST_FAIL_EXIT_CODE = (int) 0xC0000409L;
  private static final String CUDA_MANIFEST_URL =
      "https://developer.download.nvidia.com/compute/cuda/redist/redistrib_12.1.1.json";
  private static final String CUDNN_MANIFEST_URL =
      "https://developer.download.nvidia.com/compute/cudnn/redist/redistrib_9.8.0.json";
  private static final String CUDA_12_8_MANIFEST_URL =
      "https://developer.download.nvidia.com/compute/cuda/redist/redistrib_12.8.0.json";
  private static final String CUDNN_9_MANIFEST_URL =
      "https://developer.download.nvidia.com/compute/cudnn/redist/redistrib_9.8.0.json";
  private static final String TENSORRT_ENGINE_NAME = "KataGo TensorRT";
  private static final String TENSORRT_KATAGO_URL_PROPERTY = "lizzie.tensorrt.katago.url";
  private static final String TENSORRT_KATAGO_SHA256_PROPERTY = "lizzie.tensorrt.katago.sha256";
  private static final String TENSORRT_KATAGO_SIZE_PROPERTY = "lizzie.tensorrt.katago.size";
  private static final String TENSORRT_RUNTIME_SHA256_PROPERTY = "lizzie.tensorrt.runtime.sha256";
  private static final String TENSORRT_SKIP_RUNTIME_FOR_TESTS_PROPERTY =
      "lizzie.tensorrt.skipRuntimePackagesForTests";
  private static final String TENSORRT_KATAGO_VERSION = "v1.17.2";
  private static final String TENSORRT_INSTALL_LOCK_NAME = "tensorrt-install.lock";
  private static final String TENSORRT_KATAGO_ASSET =
      "katago-v1.17.2-trt10.9.0-cuda12.8-windows-x64.zip";
  private static final String TENSORRT_KATAGO_URL =
      "https://github.com/lightvector/KataGo/releases/download/"
          + TENSORRT_KATAGO_VERSION
          + "/"
          + TENSORRT_KATAGO_ASSET;
  private static final String TENSORRT_KATAGO_SHA256 =
      "be09c4ecc02028e2bdf98ff489683840bc9be480ba94f1cfe6f7e15018e36be6";
  private static final long TENSORRT_KATAGO_SIZE_BYTES = 7_678_930L;
  private static final String TENSORRT_ENGINE_MANIFEST_NAME =
      "lizzieyzy-next-katago-engine-manifest.txt";
  private static final String TENSORRT_RUNTIME_URL =
      "https://developer.download.nvidia.com/compute/machine-learning/tensorrt/10.9.0/zip/"
          + "TensorRT-10.9.0.34.Windows.win10.cuda-12.8.zip";
  private static final long CUDA_12_8_CUDART_SIZE_BYTES = 3034859L;
  private static final long CUDA_12_8_CUBLAS_SIZE_BYTES = 574528660L;
  private static final long CUDA_12_8_NVJITLINK_SIZE_BYTES = 257312022L;
  private static final long CUDA_12_8_NVRTC_SIZE_BYTES = 305553480L;
  private static final long CUDNN_9_SIZE_BYTES = 675349654L;
  private static final long TENSORRT_RUNTIME_SIZE_BYTES = 1845842538L;
  private static final int TENSORRT_MIRROR_PROBE_BYTES = 512 * 1024;
  private static final int TENSORRT_MIRROR_PROBE_CONNECT_TIMEOUT_MILLIS = 6000;
  private static final int TENSORRT_MIRROR_PROBE_READ_TIMEOUT_MILLIS = 6000;
  private static final int TENSORRT_MIRROR_PROBE_MAX_MILLIS = 8000;
  private static final Pattern BENCHMARK_RECOMMENDED_PATTERN =
      Pattern.compile("numSearchThreads\\s*=\\s*(\\d+):.*\\(recommended\\)");
  private static final Pattern BENCHMARK_CURRENT_PATTERN =
      Pattern.compile("Your GTP config is currently set to use numSearchThreads\\s*=\\s*(\\d+)");
  private static final Pattern BENCHMARK_BACKEND_PATTERN =
      Pattern.compile("You are currently using the (.+?) version of KataGo\\.");
  private static final Pattern BENCHMARK_SUMMARY_LINE_PATTERN =
      Pattern.compile(
          "^numSearchThreads\\s*=\\s*\\d+:\\s*(?:\\(baseline\\)(?:\\s+\\(recommended\\))?|[+-]?\\d+\\s+Elo.*)$");
  private static final Pattern BENCHMARK_POSITION_PROGRESS_PATTERN =
      Pattern.compile("numSearchThreads\\s*=\\s*(\\d+):\\s*(\\d+)\\s*/\\s*(\\d+)\\s*positions");
  private static final Pattern BENCHMARK_POSSIBLE_THREADS_PATTERN =
      Pattern.compile("Possible numbers of threads to test:\\s*(.*)");
  private static final List<List<String>> REQUIRED_NVIDIA_CUDA12_1_CUDNN8_RUNTIME_DLL_GROUPS =
      Arrays.asList(
          Arrays.asList("cudart64_12.dll"),
          Arrays.asList("cublas64_12.dll"),
          Arrays.asList("cublasLt64_12.dll"),
          Arrays.asList("cudnn64_8.dll"),
          Arrays.asList("nvJitLink*.dll"),
          Arrays.asList("zlibwapi.dll", "libz.dll", "z.dll"));
  private static final List<List<String>> REQUIRED_NVIDIA_CUDA12_1_CUDNN9_RUNTIME_DLL_GROUPS =
      Arrays.asList(
          Arrays.asList("cudart64_12.dll"),
          Arrays.asList("cublas64_12.dll"),
          Arrays.asList("cublasLt64_12.dll"),
          Arrays.asList("cudnn64_9.dll"),
          Arrays.asList("nvJitLink*.dll"),
          Arrays.asList("zlibwapi.dll", "libz.dll", "z.dll"));
  private static final List<List<String>> REQUIRED_NVIDIA_CUDA12_8_RUNTIME_DLL_GROUPS =
      Arrays.asList(
          Arrays.asList("cudart64_12.dll"),
          Arrays.asList("cublas64_12.dll"),
          Arrays.asList("cublasLt64_12.dll"),
          Arrays.asList("cudnn64_9.dll"),
          Arrays.asList("nvJitLink*.dll"),
          Arrays.asList("nvrtc64_*.dll"),
          Arrays.asList("nvrtc-builtins64_*.dll"),
          Arrays.asList("zlibwapi.dll", "libz.dll", "z.dll"));
  private static final List<List<String>> REQUIRED_NVIDIA_TRT10_9_RUNTIME_DLL_GROUPS =
      Arrays.asList(
          Arrays.asList("cudart64_12.dll"),
          Arrays.asList("cublas64_12.dll"),
          Arrays.asList("cublasLt64_12.dll"),
          Arrays.asList("cudnn64_9.dll"),
          Arrays.asList("nvJitLink*.dll"),
          Arrays.asList("nvinfer_10.dll", "nvinfer*.dll"),
          Arrays.asList("nvinfer_plugin_10.dll", "nvinfer_plugin*.dll"),
          Arrays.asList("zlibwapi.dll", "libz.dll", "z.dll"));
  private static final Object NVIDIA_RUNTIME_LOCK = new Object();
  private static final int BENCHMARK_VISITS = 800;
  private static final int BENCHMARK_POSITIONS = 6;
  private static final int LAYERED_BENCHMARK_SMOKE_THREADS = 6;
  private static final int LAYERED_BENCHMARK_SMOKE_POSITIONS = 1;
  private static final int LAYERED_BENCHMARK_SMOKE_VISITS = 200;
  private static final int LAYERED_BENCHMARK_FINAL_POSITIONS = 3;
  private static final int LAYERED_BENCHMARK_FINAL_VISITS = 600;
  private static final int BENCHMARK_MIN_TIME_SECONDS = 5;
  private static final int BENCHMARK_MAX_TIME_SECONDS = 15;
  private static final int BENCHMARK_PRE_POSITION_PROGRESS_CAP = 90;
  private static final int BENCHMARK_FINALIZING_PROGRESS = 880;
  private static final int BENCHMARK_PROGRESS_VISIBLE_CAP = 995;
  private static final int APPLE_AUTO_OPTIMIZE_VERSION = 5;
  private static final int APPLE_AUTO_OPTIMIZE_DELAY_MILLIS = 8000;
  private static final int APPLE_AUTO_OPTIMIZE_READY_TIMEOUT_MILLIS = 45000;
  private static final long BENCHMARK_AUXILIARY_SHUTDOWN_WAIT_MILLIS = 2000L;
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
  private static Leelaz benchmarkPausedEngine = null;
  private static EngineManager benchmarkPausedEngineManager = null;
  private static List<Leelaz> benchmarkPausedEngineList = null;
  private static int benchmarkPausedEngineIndex = -1;
  private static boolean benchmarkPausedEngineByShutdown = false;
  private static boolean benchmarkComputeIsolated = false;
  private static boolean benchmarkRestartQuickAnalysisPreload = false;
  private static boolean benchmarkRestartEstimatePreload = false;
  private static volatile boolean benchmarkEngineSyncSuppressed = false;
  private static volatile boolean appleAutoOptimizeRunning = false;
  private static volatile AppleSiliconHardwareProbe.HardwareProfile cachedAppleHardwareProfile;
  private static final Object NVIDIA_DRIVER_DETECTION_LOCK = new Object();
  private static final Object OPENCL_TUNING_CACHE_LOCK = new Object();
  private static volatile boolean nvidiaDriverDetectionComplete = false;
  private static volatile String detectedNvidiaDriverVersion = "";

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
    public final boolean downloaded;
    public final boolean installed;
    public final boolean active;
    public final Path enginePath;
    public final Path runtimeDir;
    public final long downloadBytes;
    public final String detailText;
    public final NvidiaGpuDetector.DetectionResult gpuDetection;
    public final NvidiaGpuDetector.TensorRtRecommendation gpuRecommendation;
    public final String gpuRecommendationText;

    private TensorRtInstallStatus(
        boolean applicable,
        boolean downloaded,
        boolean installed,
        boolean active,
        Path enginePath,
        Path runtimeDir,
        long downloadBytes,
        String detailText,
        NvidiaGpuDetector.DetectionResult gpuDetection,
        NvidiaGpuDetector.TensorRtRecommendation gpuRecommendation,
        String gpuRecommendationText) {
      this.applicable = applicable;
      this.downloaded = downloaded;
      this.installed = installed;
      this.active = active;
      this.enginePath = enginePath;
      this.runtimeDir = runtimeDir;
      this.downloadBytes = downloadBytes;
      this.detailText = detailText;
      this.gpuDetection = gpuDetection;
      this.gpuRecommendation =
          gpuRecommendation == null
              ? NvidiaGpuDetector.TensorRtRecommendation.UNKNOWN
              : gpuRecommendation;
      this.gpuRecommendationText = gpuRecommendationText == null ? "" : gpuRecommendationText;
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
    public final String topologyLabel;
    public final int maxBatchSize;
    public final double visitsPerSecond;
    public final double nnEvalsPerSecond;
    public final double averageBatchSize;
    private final KataGoTuningProfile tuningProfile;

    private BenchmarkResult(
        int recommendedThreads,
        int currentThreads,
        String backendLabel,
        String summary,
        long completedAtMillis) {
      this(
          recommendedThreads,
          currentThreads,
          backendLabel,
          summary,
          completedAtMillis,
          "",
          0,
          0.0,
          0.0,
          0.0,
          null);
    }

    private BenchmarkResult(
        int recommendedThreads,
        int currentThreads,
        String backendLabel,
        String summary,
        long completedAtMillis,
        String topologyLabel,
        int maxBatchSize,
        double visitsPerSecond,
        double nnEvalsPerSecond,
        double averageBatchSize,
        KataGoTuningProfile tuningProfile) {
      this.recommendedThreads = recommendedThreads;
      this.currentThreads = currentThreads;
      this.backendLabel = backendLabel;
      this.summary = summary;
      this.completedAtMillis = completedAtMillis;
      this.topologyLabel = topologyLabel == null ? "" : topologyLabel;
      this.maxBatchSize = Math.max(0, maxBatchSize);
      this.visitsPerSecond = Math.max(0.0, visitsPerSecond);
      this.nnEvalsPerSecond = Math.max(0.0, nnEvalsPerSecond);
      this.averageBatchSize = Math.max(0.0, averageBatchSize);
      this.tuningProfile = tuningProfile;
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

  static final class NvidiaMirrorProbeResult {
    final String host;
    final long bytesRead;
    final long elapsedMillis;
    final String errorMessage;

    NvidiaMirrorProbeResult(String host, long bytesRead, long elapsedMillis, String errorMessage) {
      this.host = host;
      this.bytesRead = Math.max(0L, bytesRead);
      this.elapsedMillis = Math.max(1L, elapsedMillis);
      this.errorMessage = errorMessage;
    }

    boolean isUsable() {
      return bytesRead > 0L && Utils.isBlank(errorMessage);
    }

    long bytesPerSecond() {
      return isUsable() ? (bytesRead * 1000L) / elapsedMillis : 0L;
    }
  }

  private static final class IncompleteRuntimePackageDownloadException extends IOException {
    private IncompleteRuntimePackageDownloadException(String message) {
      super(message);
    }
  }

  private static final class CorruptRuntimePackageDownloadException extends IOException {
    private CorruptRuntimePackageDownloadException(String message) {
      super(message);
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

  public static boolean isBundledNvidiaCommand(String engineCommand) {
    if (engineCommand == null || engineCommand.trim().isEmpty()) {
      return false;
    }
    try {
      Path executable = resolveCommandExecutable(Utils.splitCommand(engineCommand));
      return Config.isBundledKataGoExecutable(executable) && isNvidiaBundledPath(executable);
    } catch (RuntimeException e) {
      return false;
    }
  }

  public static boolean isBundledTensorRtPath(Path enginePath) {
    return isWindowsPlatform()
        && enginePath != null
        && Config.isBundledKataGoExecutable(enginePath)
        && isTensorRtBackend(resolveNvidiaBackend(enginePath));
  }

  public static boolean isBundledTensorRtCommand(String engineCommand) {
    if (engineCommand == null || engineCommand.trim().isEmpty()) {
      return false;
    }
    try {
      return isBundledTensorRtPath(
          resolveCommandExecutable(Utils.splitCommand(engineCommand)));
    } catch (RuntimeException e) {
      return false;
    }
  }

  public static boolean isBundledOpenClPath(Path enginePath) {
    if (!isWindowsPlatform()
        || enginePath == null
        || !Config.isBundledKataGoExecutable(enginePath)
        || resolveNvidiaBackend(enginePath) != null) {
      return false;
    }
    return OPENCL_BACKEND.equals(readEngineBackendMarker(enginePath));
  }

  private static String resolveNvidiaBackend(Path enginePath) {
    if (enginePath == null) {
      return null;
    }
    Path fileName = enginePath.getFileName();
    if (fileName != null
        && HUMAN_SL_CUDA_COMPANION_NAME.equalsIgnoreCase(fileName.toString())) {
      return NVIDIA50_CUDA_BACKEND;
    }
    String normalized = enginePath.toAbsolutePath().normalize().toString().replace('\\', '/');
    String normalizedLower = normalized.toLowerCase(Locale.ROOT);
    if (normalizedLower.contains("/" + NVIDIA_TRT_ENGINE_DIR + "/")) {
      return NVIDIA_TRT_BACKEND;
    }
    if (normalizedLower.contains("/" + NVIDIA50_TRT_ENGINE_DIR + "/")) {
      return NVIDIA_TRT_BACKEND;
    }
    if (normalizedLower.contains("/" + NVIDIA50_CUDA_ENGINE_DIR + "/")) {
      return NVIDIA50_CUDA_BACKEND;
    }
    if (normalizedLower.contains("/" + NVIDIA_ENGINE_DIR + "/")) {
      return NVIDIA_BACKEND;
    }
    String backendLower = readEngineBackendMarker(enginePath);
    if (!backendLower.isEmpty()) {
      if (NVIDIA_TRT_BACKEND.equals(backendLower) || NVIDIA50_TRT_BACKEND.equals(backendLower)) {
        return NVIDIA_TRT_BACKEND;
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
    }
    return null;
  }

  private static String readEngineBackendMarker(Path enginePath) {
    if (enginePath == null) {
      return "";
    }
    Path engineDir = enginePath.toAbsolutePath().normalize().getParent();
    if (engineDir == null) {
      return "";
    }
    Path markerPath = engineDir.resolve(ENGINE_BACKEND_MARKER_NAME);
    if (!Files.isRegularFile(markerPath)) {
      return "";
    }
    try {
      return Files.readString(markerPath, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
    } catch (IOException e) {
      return "";
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
    if (!Config.isBundledKataGoExecutable(enginePath)) {
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
      configureNvidiaRuntimeCacheEnvironment(processBuilder, enginePath, runtimeDir);
    }
  }

  public static List<String> prepareBundledLaunchCommand(
      List<String> originalCommand, Path enginePath) {
    return prepareBundledLaunchCommand(originalCommand, enginePath, LaunchPurpose.MAIN_GTP);
  }

  public static List<String> prepareBundledLaunchCommand(
      List<String> originalCommand, Path enginePath, LaunchPurpose purpose) {
    if (originalCommand == null) {
      return null;
    }
    List<String> launchCommand = new ArrayList<String>(originalCommand);
    if (enginePath == null || Lizzie.config == null) {
      return launchCommand;
    }
    if (!Config.isBundledKataGoExecutable(enginePath)) {
      return launchCommand;
    }

    if (purpose == LaunchPurpose.HUMAN_SL && isBundledTensorRtPath(enginePath)) {
      Path companion = resolveHumanSlCudaCompanion(enginePath);
      if (companion != null && !launchCommand.isEmpty()) {
        launchCommand.set(0, companion.toString());
      }
    }

    boolean openClFp32Compatibility = shouldUseOpenClFp32Compatibility(launchCommand, enginePath);
    Path homeDataDir =
        openClFp32Compatibility ? getOpenClFp32HomeDataDir() : getBundledHomeDataDir();
    if (homeDataDir == null) {
      return launchCommand;
    }
    try {
      Files.createDirectories(homeDataDir);
    } catch (IOException e) {
      e.printStackTrace();
      return launchCommand;
    }

    if (openClFp32Compatibility) {
      setOverrideConfig(launchCommand, "homeDataDir=" + homeDataDir.toString());
      setOverrideConfig(launchCommand, "openclUseFP16=false");
    } else {
      appendOverrideConfig(launchCommand, "homeDataDir=" + homeDataDir.toString());
      prepareBundledOpenClTuningCache(
          enginePath, resolveEffectiveHomeDataDir(launchCommand, homeDataDir));
    }
    appendAnalysisPvLenOverride(launchCommand);
    if (purpose == LaunchPurpose.HUMAN_SL) {
      applyHumanSlLaunchProfile(launchCommand);
    }
    if (purpose == LaunchPurpose.MAIN_GTP) {
      launchCommand = applyStoredAppleTuningProfile(launchCommand, enginePath);
    }
    return launchCommand;
  }

  static Path resolveHumanSlCudaCompanion(Path tensorRtEnginePath) {
    if (tensorRtEnginePath == null || !isBundledTensorRtPath(tensorRtEnginePath)) {
      return null;
    }
    Path engineDir = tensorRtEnginePath.toAbsolutePath().normalize().getParent();
    if (engineDir != null) {
      Path packagedCompanion = engineDir.resolve(HUMAN_SL_CUDA_COMPANION_NAME);
      if (Files.isRegularFile(packagedCompanion)) {
        return packagedCompanion.toAbsolutePath().normalize();
      }
    }
    if (Lizzie.config == null || Lizzie.config.leelazConfig == null) {
      return null;
    }
    JSONArray engines = Lizzie.config.leelazConfig.optJSONArray("engine-settings-list");
    if (engines == null) {
      return null;
    }
    for (int index = 0; index < engines.length(); index++) {
      JSONObject engine = engines.optJSONObject(index);
      String command = engine == null ? "" : engine.optString("command", "").trim();
      if (command.isEmpty()) {
        continue;
      }
      Path candidate;
      try {
        candidate = resolveCommandExecutable(Utils.splitCommand(command));
      } catch (RuntimeException e) {
        continue;
      }
      if (candidate == null
          || candidate.toAbsolutePath().normalize().equals(tensorRtEnginePath.toAbsolutePath().normalize())
          || !Files.isRegularFile(candidate)
          || !Config.isBundledKataGoExecutable(candidate)) {
        continue;
      }
      String backend = resolveNvidiaBackend(candidate);
      if (NVIDIA_BACKEND.equalsIgnoreCase(backend)
          || NVIDIA50_CUDA_BACKEND.equalsIgnoreCase(backend)) {
        return candidate.toAbsolutePath().normalize();
      }
    }
    return null;
  }

  private static void applyHumanSlLaunchProfile(List<String> command) {
    setOverrideConfig(command, "numAnalysisThreads=1");
    setOverrideConfig(command, "numSearchThreadsPerAnalysisThread=8");
    setOverrideConfig(command, "nnMaxBatchSize=8");
    setOverrideConfig(command, "nnCacheSizePowerOfTwo=20");
    if (Board.boardWidth == 19 && Board.boardHeight == 19) {
      setOverrideConfig(command, "maxBoardXSizeForNNBuffer=19");
      setOverrideConfig(command, "maxBoardYSizeForNNBuffer=19");
      setOverrideConfig(command, "requireMaxBoardSize=true");
    }
  }

  static List<String> applyStoredAppleTuningProfile(List<String> command, Path enginePath) {
    if (!isAppleSiliconHost()
        || command == null
        || enginePath == null
        || Lizzie.config == null
        || Lizzie.config.uiConfig == null) {
      return command;
    }
    Path modelPath = findCommandPath(command, "-model", "--model", "-weights", "--weights");
    Path configPath = findCommandPath(command, "-config", "--config");
    if (modelPath == null || configPath == null) {
      return command;
    }
    KataGoTuningStore tuningStore = new KataGoTuningStore(Lizzie.config.uiConfig);
    if (!tuningStore.hasStoredProfile()) {
      return command;
    }
    try {
      KataGoTuningFingerprint officialFingerprint =
          KataGoTuningFingerprint.create(
              enginePath,
              modelPath,
              configPath,
              currentAppleHardwareProfile(),
              officialTuningCommandSemantics(command));
      Optional<KataGoTuningProfile> stored = tuningStore.loadMatching(officialFingerprint);
      if (stored.isEmpty()) {
        KataGoTuningFingerprint experimentalFingerprint =
            KataGoTuningFingerprint.create(
                enginePath,
                modelPath,
                configPath,
                currentAppleHardwareProfile(),
                tuningCommandSemantics(command));
        stored = tuningStore.loadMatching(experimentalFingerprint);
      }
      if (stored.isEmpty()) {
        return command;
      }

      KataGoTuningProfile profile = stored.get();
      if (!profile.managesHardwareSettings()) {
        return mergeStoredAppleThreadProfile(command, profile.threads());
      }
      KataGoTuningCandidate candidate =
          new KataGoTuningCandidate("stored", profile.devices(), profile.batch());
      return mergeStoredAppleTuningProfile(command, candidate, profile.threads());
    } catch (IOException | RuntimeException e) {
      System.err.println("Ignoring stale KataGo tuning profile: " + e.getLocalizedMessage());
      return command;
    }
  }

  private static AppleSiliconHardwareProbe.HardwareProfile currentAppleHardwareProfile() {
    AppleSiliconHardwareProbe.HardwareProfile hardware = cachedAppleHardwareProfile;
    if (hardware == null) {
      hardware = new AppleSiliconHardwareProbe().probe();
      cachedAppleHardwareProfile = hardware;
    }
    return hardware;
  }

  private static boolean isMetalTopologyOverride(String key) {
    if (key == null) {
      return false;
    }
    String normalized = key.trim().toLowerCase(Locale.ROOT);
    return normalized.equals("numnnserverthreadspermodel")
        || normalized.startsWith("metaldevicetouse")
        || normalized.startsWith("metalgputouse")
        || normalized.startsWith("devicetouse")
        || normalized.startsWith("gputouse")
        || normalized.startsWith("metalusefp16")
        || normalized.startsWith("usefp16");
  }

  static List<String> mergeStoredAppleTuningProfile(
      List<String> command, KataGoTuningCandidate candidate, int profileThreads) {
    KataGoCommandSpec commandSpec = KataGoCommandSpec.parse(command);
    Map<String, String> managedOverrides =
        new LinkedHashMap<String, String>(candidate.runtimeOverrides(profileThreads));

    // The server count, every device lane, and FP16 mode describe one indivisible topology. If a
    // user supplies any spelling from KataGo's topology alias family, do not complete or replace
    // the rest of that topology from the stored profile.
    if (commandSpec.hasOverrideMatching(KataGoRuntimeHelper::isMetalTopologyOverride)) {
      managedOverrides.keySet().removeIf(KataGoRuntimeHelper::isMetalTopologyOverride);
    }
    if (commandSpec.hasOverrideMatching(
        key -> "nnMaxBatchSize".equalsIgnoreCase(key == null ? "" : key.trim()))) {
      managedOverrides.remove("nnMaxBatchSize");
    }
    if (commandSpec.hasOverrideMatching(KataGoRuntimeHelper::isNumSearchThreadsOverride)) {
      managedOverrides.remove("numSearchThreads");
    }
    return commandSpec.withManagedOverrides(managedOverrides);
  }

  static List<String> mergeStoredAppleThreadProfile(List<String> command, int profileThreads) {
    if (profileThreads <= 0 || profileThreads > 4096) {
      throw new IllegalArgumentException("profileThreads must be between 1 and 4096");
    }
    KataGoCommandSpec commandSpec = KataGoCommandSpec.parse(command);
    if (commandSpec.hasOverrideMatching(KataGoRuntimeHelper::isNumSearchThreadsOverride)) {
      return command;
    }
    return commandSpec.withManagedOverrides(
        Map.of("numSearchThreads", String.valueOf(profileThreads)));
  }

  /** Returns whether the effective launch command explicitly sets KataGo's search threads. */
  public static boolean hasEffectiveNumSearchThreadsOverride(List<String> launchCommand) {
    if (launchCommand == null || launchCommand.isEmpty()) {
      return false;
    }
    try {
      return KataGoCommandSpec.parse(launchCommand)
          .hasOverrideMatching(KataGoRuntimeHelper::isNumSearchThreadsOverride);
    } catch (RuntimeException invalidCommand) {
      return false;
    }
  }

  private static boolean isNumSearchThreadsOverride(String key) {
    return "numSearchThreads".equalsIgnoreCase(key == null ? "" : key.trim());
  }

  private static boolean isUserManagedTuningOverride(String key) {
    return isMetalTopologyOverride(key)
        || "nnMaxBatchSize".equalsIgnoreCase(key)
        || isNumSearchThreadsOverride(key);
  }

  public static boolean isOpenClFp32CompatibilityActive(
      List<String> launchCommand, Path enginePath) {
    return isBundledOpenClPath(enginePath)
        && "false".equalsIgnoreCase(findOverrideConfigValue(launchCommand, "openclUseFP16"));
  }

  public static boolean shouldRecoverOpenClNativeExit(
      List<String> originalCommand,
      Path enginePath,
      int exitCode,
      boolean compatibilityAlreadyActive) {
    return !compatibilityAlreadyActive
        && exitCode == WINDOWS_FAST_FAIL_EXIT_CODE
        && isBundledOpenClPath(enginePath)
        && findCommandPath(originalCommand, "-model", "--model", "-weights", "--weights") != null;
  }

  public static boolean rememberOpenClFp32Compatibility(
      List<String> originalCommand, Path enginePath) {
    if (!isBundledOpenClPath(enginePath)) {
      return false;
    }
    Path marker = getOpenClFp32CompatibilityMarker();
    String signature =
        buildOpenClCompatibilitySignature(
            originalCommand, enginePath, resolveNvidiaDriverVersion());
    if (marker == null || signature.isEmpty()) {
      return false;
    }
    try {
      Files.createDirectories(marker.getParent());
      Files.writeString(
          marker,
          signature,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
      return true;
    } catch (IOException e) {
      System.err.println(
          "Unable to remember KataGo OpenCL FP32 compatibility mode: " + e.getLocalizedMessage());
      return false;
    }
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
    List<List<String>> requiredDllGroups = requiredRuntimeDllGroups(enginePath, backend);
    List<String> missing = collectMissingRuntimeGroups(searchDirs, requiredDllGroups);
    Path readyDir = findDirectoryContainingRequiredDlls(searchDirs, requiredDllGroups);
    boolean ready = missing.isEmpty();
    String detailText;
    if (ready) {
      detailText =
          resource("AutoSetup.nvidiaRuntimeReady", "Ready")
              + "  |  "
              + (readyDir != null
                  ? readyDir.toAbsolutePath().normalize()
                  : formatRuntimeSearchDirs(searchDirs));
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
    return inspectTensorRtInstall(snapshot, null);
  }

  public static TensorRtInstallStatus inspectTensorRtInstall(
      SetupSnapshot snapshot, NvidiaGpuDetector.DetectionResult gpuDetection) {
    TensorRtInstallSpec spec = buildTensorRtInstallSpec(snapshot);
    if (!isWindowsPlatform()) {
      return new TensorRtInstallStatus(
          false,
          false,
          false,
          false,
          spec.targetEnginePath,
          getNvidiaRuntimeDir(),
          spec.totalDownloadBytes,
          resource(
              "AutoSetup.tensorRtNotApplicable",
              "TensorRT acceleration is only available on Windows NVIDIA packages."),
          null,
          NvidiaGpuDetector.TensorRtRecommendation.UNKNOWN,
          "");
    }
    if (!isTensorRtSourceProfileAllowed(snapshot)) {
      return new TensorRtInstallStatus(
          false,
          false,
          false,
          false,
          spec.targetEnginePath,
          getNvidiaRuntimeDir(),
          spec.totalDownloadBytes,
          resource(
              "AutoSetup.tensorRtNeedNvidia",
              "TensorRT can be installed from Windows NVIDIA packages. Recommended for RTX 20/30/40/50. "
                  + "GTX 10 series and older NVIDIA GPUs should use CUDA/OpenCL."),
          null,
          NvidiaGpuDetector.TensorRtRecommendation.UNKNOWN,
          "");
    }
    boolean engineDownloaded = Files.isRegularFile(spec.targetEnginePath);
    boolean runtimeReady = inspectNvidiaRuntime(spec.targetEnginePath).ready;
    boolean engineCurrent = isCurrentTensorRtEngine(spec.targetEnginePath);
    boolean installed = engineDownloaded && runtimeReady && engineCurrent;
    boolean active = installed && isTensorRtEngineActive(snapshot, spec);
    long requiredDownloadBytes = runtimeReady ? spec.katagoSizeBytes : spec.totalDownloadBytes;
    String recommendation = tensorRtRecommendationText(gpuDetection);
    String detail =
        engineDownloaded && runtimeReady && !engineCurrent
            ? String.format(
                Locale.ROOT,
                resource(
                    "AutoSetup.tensorRtEngineUpgradeAvailable",
                    "The TensorRT runtime is ready, but its KataGo engine is outdated. Upgrade the engine only (%s); existing runtime files will be reused."),
                formatBytes(spec.katagoSizeBytes))
            : installed
                ? active
                    ? resource("AutoSetup.tensorRtEnabled", "TensorRT acceleration is enabled.")
                    : resource(
                        "AutoSetup.tensorRtInstalledNotSelected",
                        "TensorRT acceleration is installed. Click Enable TensorRT acceleration to use it.")
                : engineDownloaded
                    ? resource(
                        "AutoSetup.tensorRtDownloadedRuntimeMissing",
                        "TensorRT engine files are present, but runtime files are incomplete. Click Install TensorRT acceleration to finish setup.")
                    : String.format(
                        Locale.ROOT,
                        resource(
                            "AutoSetup.tensorRtAvailable",
                            "Optional TensorRT download: about %s. %s"),
                        formatBytes(spec.totalDownloadBytes),
                        recommendation);
    return new TensorRtInstallStatus(
        true,
        engineDownloaded,
        installed,
        active,
        spec.targetEnginePath,
        getNvidiaRuntimeDir(),
        requiredDownloadBytes,
        detail,
        gpuDetection,
        gpuDetection == null
            ? NvidiaGpuDetector.TensorRtRecommendation.UNKNOWN
            : gpuDetection.recommendation,
        recommendation);
  }

  public static boolean canInstallTensorRt(SetupSnapshot snapshot) {
    TensorRtInstallStatus status = inspectTensorRtInstall(snapshot);
    return status.applicable && (!status.installed || !status.active);
  }

  public static boolean canSwitchBackToCuda(SetupSnapshot snapshot) {
    if (snapshot == null
        || !snapshot.hasEngine()
        || !snapshot.hasConfigs()
        || !snapshot.hasWeight()) {
      return false;
    }
    TensorRtInstallStatus status = inspectTensorRtInstall(snapshot);
    return status.applicable && status.active;
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
              "TensorRT can be installed from Windows NVIDIA packages. Recommended for RTX 20/30/40/50. "
                  + "GTX 10 series and older NVIDIA GPUs should use CUDA/OpenCL."));
    }
    if (snapshot.gtpConfigPath == null || !Files.isRegularFile(snapshot.gtpConfigPath)) {
      throw new IOException(
          resource("AutoSetup.missingConfig", "No KataGo config file was found."));
    }
    if (!snapshot.hasWeight()) {
      throw new IOException(
          resource("AutoSetup.missingWeight", "No local KataGo weight file was found."));
    }

    TensorRtInstallSpec spec = buildTensorRtInstallSpec(snapshot);
    DownloadSession activeSession = session != null ? session : new DownloadSession();
    Path runtimeDir = getNvidiaRuntimeDir();
    Files.createDirectories(runtimeDir);
    try (FileChannel lockChannel =
            FileChannel.open(
                runtimeDir.resolve(TENSORRT_INSTALL_LOCK_NAME),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock installLock = lockChannel.tryLock()) {
      if (installLock == null) {
        throw new IOException(tensorRtInstallAlreadyRunningMessage());
      }
      return downloadAndInstallTensorRtLocked(snapshot, listener, activeSession, spec, runtimeDir);
    } catch (OverlappingFileLockException e) {
      throw new IOException(tensorRtInstallAlreadyRunningMessage(), e);
    }
  }

  public static SetupResult applyInstalledTensorRt(SetupSnapshot snapshot) throws IOException {
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
              "TensorRT can be installed from Windows NVIDIA packages. Recommended for RTX 20/30/40/50. "
                  + "GTX 10 series and older NVIDIA GPUs should use CUDA/OpenCL."));
    }
    if (snapshot.gtpConfigPath == null || !Files.isRegularFile(snapshot.gtpConfigPath)) {
      throw new IOException(
          resource("AutoSetup.missingConfig", "No KataGo config file was found."));
    }
    if (!snapshot.hasWeight()) {
      throw new IOException(
          resource("AutoSetup.missingWeight", "No local KataGo weight file was found."));
    }
    TensorRtInstallSpec spec = buildTensorRtInstallSpec(snapshot);
    if (!Files.isRegularFile(spec.targetEnginePath)
        || !inspectNvidiaRuntime(spec.targetEnginePath).ready) {
      throw new IOException(
          resource(
              "AutoSetup.tensorRtRuntimeMissing",
              "TensorRT runtime is not installed. Open KataGo Auto Setup and install TensorRT acceleration, or switch back to CUDA/OpenCL."));
    }
    if (!isCurrentTensorRtEngine(spec.targetEnginePath)) {
      throw new IOException(
          resource(
              "AutoSetup.tensorRtEngineUpgradeRequired",
              "The installed TensorRT engine is outdated. Upgrade it in KataGo Auto Setup before using Transformer weights; existing runtime files will be reused."));
    }
    return applyTensorRtEngineProfile(snapshot, spec);
  }

  public static SetupResult applyBundledCudaProfile(SetupSnapshot snapshot) throws IOException {
    if (snapshot == null) {
      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    }
    if (!snapshot.hasEngine()) {
      throw new IOException(
          resource("AutoSetup.missingEngine", "No local KataGo binary was found."));
    }
    if (snapshot.gtpConfigPath == null || !Files.isRegularFile(snapshot.gtpConfigPath)) {
      throw new IOException(
          resource("AutoSetup.missingConfig", "No KataGo config file was found."));
    }
    if (!snapshot.hasWeight()) {
      throw new IOException(
          resource("AutoSetup.missingWeight", "No local KataGo weight file was found."));
    }
    if (isWindowsPlatform() && isNvidiaBundledPath(snapshot.enginePath)) {
      ensureBundledRuntimeReady(snapshot.enginePath, null);
    }
    return KataGoAutoSetupHelper.applyAutoSetup(
        snapshot.withActiveWeight(snapshot.activeWeightPath), true);
  }

  private static SetupResult downloadAndInstallTensorRtLocked(
      SetupSnapshot snapshot,
      ProgressListener listener,
      DownloadSession activeSession,
      TensorRtInstallSpec spec,
      Path runtimeDir)
      throws IOException {
    activeSession.throwIfCancelled();
    boolean runtimeReady = inspectNvidiaRuntime(spec.targetEnginePath).ready;
    if (Files.isRegularFile(spec.targetEnginePath)
        && runtimeReady
        && isCurrentTensorRtEngine(spec.targetEnginePath)) {
      notifyProgress(
          listener,
          resource("AutoSetup.tensorRtReady", "TensorRT acceleration is installed."),
          spec.totalDownloadBytes,
          spec.totalDownloadBytes);
      return applyTensorRtEngineProfile(snapshot, spec);
    }
    long effectiveTotalBytes = runtimeReady ? spec.katagoSizeBytes : spec.totalDownloadBytes;
    notifyProgress(
        listener,
        resource("AutoSetup.tensorRtPreparing", "Preparing TensorRT download..."),
        0L,
        effectiveTotalBytes);

    Path cacheDir = runtimeDir.resolve(NVIDIA_RUNTIME_DOWNLOAD_CACHE_DIR);
    Files.createDirectories(cacheDir);
    List<Path> completedArchives = new ArrayList<Path>();
    RuntimePackageSpec katagoPackage =
        new RuntimePackageSpec(
            "KataGo TensorRT",
            TENSORRT_KATAGO_VERSION,
            spec.katagoUrl,
            spec.katagoSha256,
            spec.katagoSizeBytes,
            "katago-tensorrt");
    List<RuntimePackageSpec> runtimePackages = new ArrayList<RuntimePackageSpec>();
    if (!runtimeReady) {
      String nvidiaDownloadHost =
          chooseTensorRtNvidiaDownloadHost(activeSession, listener, effectiveTotalBytes);
      runtimePackages = resolveTensorRtRuntimePackages(nvidiaDownloadHost);
    }
    long completedBytes = 0L;

    Path katagoArchive =
        downloadPackageWithAggregateProgress(
            katagoPackage,
            cacheDir.resolve(katagoPackage.fileName()),
            activeSession,
            listener,
            completedBytes,
            effectiveTotalBytes);
    completedArchives.add(katagoArchive);
    completedBytes += Math.max(0L, katagoPackage.sizeBytes);

    Path licenseDir = runtimeDir.resolve("licenses").resolve("nvidia-runtime");
    if (!runtimeReady && !Boolean.getBoolean(TENSORRT_SKIP_RUNTIME_FOR_TESTS_PROPERTY)) {
      for (RuntimePackageSpec runtimePackage : runtimePackages) {
        Path archivePath =
            downloadPackageWithAggregateProgress(
                runtimePackage,
                cacheDir.resolve(runtimePackage.fileName()),
                activeSession,
                listener,
                completedBytes,
                effectiveTotalBytes);
        completedArchives.add(archivePath);
        completedBytes += Math.max(0L, runtimePackage.sizeBytes);
        activeSession.throwIfCancelled();
        notifyProgress(
            listener,
            resource("AutoSetup.tensorRtExtracting", "Extracting TensorRT files...")
                + " "
                + runtimePackage.displayName,
            Math.min(completedBytes, effectiveTotalBytes),
            effectiveTotalBytes);
        extractRuntimePackage(runtimePackage, archivePath, runtimeDir, licenseDir);
      }
      writeRuntimeManifest(runtimeDir, runtimePackages);
    }

    activeSession.throwIfCancelled();
    notifyProgress(
        listener,
        resource("AutoSetup.tensorRtExtracting", "Extracting TensorRT files..."),
        Math.min(completedBytes, effectiveTotalBytes),
        effectiveTotalBytes);
    installTensorRtKataGoArchive(katagoArchive, spec.targetEngineDir, activeSession);
    activeSession.throwIfCancelled();

    SetupResult result = applyTensorRtEngineProfile(snapshot, spec);
    notifyProgress(
        listener,
        resource("AutoSetup.tensorRtCleaningCache", "Cleaning TensorRT download cache..."),
        effectiveTotalBytes,
        effectiveTotalBytes);
    cleanupCompletedTensorRtDownloadArchives(cacheDir, completedArchives);
    notifyProgress(
        listener,
        resource("AutoSetup.tensorRtInstallDone", "TensorRT acceleration installed."),
        effectiveTotalBytes,
        effectiveTotalBytes);
    return result;
  }

  private static SetupResult applyTensorRtEngineProfile(
      SetupSnapshot snapshot, TensorRtInstallSpec spec) throws IOException {
    SetupSnapshot tensorRtSnapshot = snapshot.withEnginePath(spec.targetEnginePath);
    return KataGoAutoSetupHelper.applyEngineProfile(tensorRtSnapshot, TENSORRT_ENGINE_NAME, true);
  }

  private static String tensorRtInstallAlreadyRunningMessage() {
    return resource(
        "AutoSetup.tensorRtInstallAlreadyRunning",
        "TensorRT installation is already running in another LizzieYzy Next window. Please wait for it to finish.");
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
        Lizzie.config.uiConfig.optLong("katago-benchmark-updated-at", 0L),
        Lizzie.config.uiConfig.optString("katago-benchmark-topology", "").trim(),
        Lizzie.config.uiConfig.optInt("katago-benchmark-batch-size", 0),
        Lizzie.config.uiConfig.optDouble("katago-benchmark-visits-per-second", 0.0),
        Lizzie.config.uiConfig.optDouble("katago-benchmark-nn-evals-per-second", 0.0),
        Lizzie.config.uiConfig.optDouble("katago-benchmark-average-batch-size", 0.0),
        null);
  }

  /** Returns the displayed result only when it still belongs to the selected Apple setup. */
  public static BenchmarkResult getStoredBenchmarkResult(SetupSnapshot snapshot) {
    BenchmarkResult stored = getStoredBenchmarkResult();
    if (stored == null || !isAppleSiliconOptimizationEligible(snapshot)) {
      return stored;
    }
    String expectedSignature = buildBenchmarkSignature(snapshot);
    String storedSignature = Lizzie.config.uiConfig.optString(BENCHMARK_SIGNATURE_KEY, "").trim();
    boolean currentVersion =
        Lizzie.config.uiConfig.optInt(APPLE_AUTO_OPTIMIZE_VERSION_KEY, 0)
            == APPLE_AUTO_OPTIMIZE_VERSION;
    boolean matchingProfile = matchingAppleTuningProfile(snapshot).isPresent();
    return currentVersion && matchingProfile && expectedSignature.equals(storedSignature)
        ? stored
        : null;
  }

  public static BenchmarkResult runBenchmark(
      SetupSnapshot snapshot, ProgressListener listener, DownloadSession session)
      throws IOException {
    if (snapshot == null) {
      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    }
    validateBenchmarkSnapshot(snapshot);
    String output =
        runBenchmarkProcess(snapshot, buildBenchmarkCommand(snapshot), listener, session, 0, false);
    BenchmarkResult result = parseBenchmarkOutput(output);
    if (result == null) {
      throw benchmarkFailedException();
    }
    return result;
  }

  private static void validateBenchmarkSnapshot(SetupSnapshot snapshot) throws IOException {
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
  }

  private static String runBenchmarkProcess(
      SetupSnapshot snapshot,
      List<String> command,
      ProgressListener listener,
      DownloadSession session,
      int expectedThreadTests,
      boolean requireComputeIsolation)
      throws IOException {
    DownloadSession activeSession = session != null ? session : new DownloadSession();
    activeSession.throwIfCancelled();
    if (requireComputeIsolation) {
      requireLayeredBenchmarkComputeIsolation();
    }
    if (isWindowsPlatform() && isNvidiaBundledPath(snapshot.enginePath)) {
      ensureBundledRuntimeReady(snapshot.enginePath, null);
    }
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
      if (requireComputeIsolation && !isLayeredBenchmarkComputeIsolated()) {
        process.destroyForcibly();
        throw benchmarkIsolationLostException();
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
    BenchmarkProgressTracker progressTracker = new BenchmarkProgressTracker(expectedThreadTests);
    AtomicLong benchmarkStartedAt = new AtomicLong(System.currentTimeMillis());
    AtomicLong lastProgressAt = new AtomicLong(benchmarkStartedAt.get());
    AtomicInteger lastProgressPermille = new AtomicInteger(BENCHMARK_PRE_POSITION_PROGRESS_CAP);
    AtomicBoolean computeIsolationLost = new AtomicBoolean(false);
    Thread cancellationWatcher =
        startBenchmarkCancellationWatcher(
            process, activeSession, requireComputeIsolation, computeIsolationLost);
    Thread progressHeartbeat =
        startBenchmarkProgressHeartbeat(
            process,
            listener,
            activeSession,
            progressTracker,
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
      if (computeIsolationLost.get()) {
        throw benchmarkIsolationLostException();
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
      if (computeIsolationLost.get()) {
        throw benchmarkIsolationLostException();
      }
      if (requireComputeIsolation) {
        requireLayeredBenchmarkComputeIsolation();
      }
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
    return output.toString();
  }

  private static IOException benchmarkFailedException() {
    return new IOException(
        resource("AutoSetup.benchmarkFailed", "Unable to run KataGo benchmark right now."));
  }

  private static IOException benchmarkIsolationLostException() {
    return new IOException(
        "KataGo tuning stopped because another engine resumed compute during the benchmark.");
  }

  private static void requireLayeredBenchmarkComputeIsolation() throws IOException {
    if (!isLayeredBenchmarkComputeIsolated()) {
      throw benchmarkIsolationLostException();
    }
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
    command =
        KataGoCommandSpec.parse(command)
            .withForcedOverrides(inheritedOfficialBenchmarkOverrides(snapshot));
    command =
        KataGoCommandSpec.parse(command)
            .withForcedOverrides(
                Map.of(
                    "logToStderr", "false",
                    "logAllGTPCommunication", "false",
                    "logSearchInfo", "false"));
    return prepareBundledLaunchCommand(command, snapshot.enginePath, LaunchPurpose.BENCHMARK);
  }

  private static Map<String, String> inheritedOfficialBenchmarkOverrides(SetupSnapshot snapshot) {
    return officialBenchmarkOverrides(snapshotSourceCommand(snapshot));
  }

  static Map<String, String> officialBenchmarkOverrides(List<String> sourceCommand) {
    Map<String, String> inherited = new LinkedHashMap<String, String>();
    for (Map.Entry<String, String> entry :
        KataGoCommandSpec.parse(sourceCommand == null ? List.of() : sourceCommand)
            .effectiveOverrides()
            .entrySet()) {
      String key = entry.getKey();
      if (!isNumSearchThreadsOverride(key)
          && !"numAnalysisThreads".equalsIgnoreCase(key)
          && !"numSearchThreadsPerAnalysisThread".equalsIgnoreCase(key)
          && !"homeDataDir".equalsIgnoreCase(key)) {
        inherited.put(key, entry.getValue());
      }
    }
    return inherited;
  }

  static List<String> buildLayeredBenchmarkCommand(
      SetupSnapshot snapshot,
      KataGoTuningCandidate candidate,
      int explicitThreads,
      int positions,
      int visits) {
    if (snapshot == null || candidate == null) {
      throw new IllegalArgumentException("snapshot and candidate are required");
    }
    if (explicitThreads < 0 || positions <= 0 || visits <= 0) {
      throw new IllegalArgumentException("invalid layered benchmark limits");
    }
    List<String> command = new ArrayList<String>();
    command.add(snapshot.enginePath.toAbsolutePath().normalize().toString());
    command.add("benchmark");
    command.add("-config");
    command.add(snapshot.gtpConfigPath.toAbsolutePath().normalize().toString());
    command.add("-model");
    command.add(snapshot.activeWeightPath.toAbsolutePath().normalize().toString());
    if (explicitThreads > 0) {
      command.add("-t");
      command.add(String.valueOf(explicitThreads));
    } else {
      command.add("-s");
    }
    command.add("-n");
    command.add(String.valueOf(positions));
    command.add("-v");
    command.add(String.valueOf(visits));
    command.add("-time");
    command.add(String.valueOf(resolveBenchmarkTimeSeconds()));
    command.add("-boardsize");
    command.add("19");
    command.add("-fixed-batch-size");
    command.add(String.valueOf(candidate.batch()));

    command =
        KataGoCommandSpec.parse(command).withForcedOverrides(inheritedBenchmarkOverrides(snapshot));
    Map<String, String> forcedOverrides =
        new LinkedHashMap<String, String>(candidate.benchmarkOverrides());
    forcedOverrides.put("logToStderr", "false");
    forcedOverrides.put("logAllGTPCommunication", "false");
    forcedOverrides.put("logSearchInfo", "false");
    command = KataGoCommandSpec.parse(command).withForcedOverrides(forcedOverrides);
    return prepareBundledLaunchCommand(command, snapshot.enginePath, LaunchPurpose.BENCHMARK);
  }

  private static Map<String, String> inheritedBenchmarkOverrides(SetupSnapshot snapshot) {
    Map<String, String> inherited = new LinkedHashMap<String, String>();
    for (Map.Entry<String, String> entry :
        KataGoCommandSpec.parse(snapshotSourceCommand(snapshot)).effectiveOverrides().entrySet()) {
      String key = entry.getKey();
      if (!isUserManagedTuningOverride(key)
          && !"numAnalysisThreads".equalsIgnoreCase(key)
          && !"numSearchThreadsPerAnalysisThread".equalsIgnoreCase(key)) {
        inherited.put(key, entry.getValue());
      }
    }
    return inherited;
  }

  private static List<String> snapshotSourceCommand(SetupSnapshot snapshot) {
    if (snapshot == null
        || snapshot.discovery == null
        || Utils.isBlank(snapshot.discovery.sourceCommand)) {
      return List.of();
    }
    List<String> source = Utils.splitCommand(snapshot.discovery.sourceCommand);
    return source == null ? List.of() : source;
  }

  private static KataGoBenchmarkObservation runLayeredBenchmarkCell(
      SetupSnapshot snapshot,
      KataGoTuningCandidate candidate,
      int explicitThreads,
      int positions,
      int visits,
      ProgressListener listener,
      DownloadSession session)
      throws IOException {
    List<String> command =
        buildLayeredBenchmarkCommand(snapshot, candidate, explicitThreads, positions, visits);
    String output =
        runBenchmarkProcess(
            snapshot, command, listener, session, explicitThreads > 0 ? 1 : 0, true);
    KataGoBenchmarkObservation observation = KataGoBenchmarkParser.parse(output, explicitThreads);
    if (observation.failureDetected()
        || observation.recommendedThreads() <= 0
        || observation
            .recommendedMetric()
            .filter(KataGoBenchmarkObservation.ThreadMetrics::validForThroughputSelection)
            .isEmpty()) {
      throw benchmarkFailedException();
    }
    if (candidate.devices().contains(KataGoTuningCandidate.METAL_GPU)
        && !observation.mpsGraphInitialized()) {
      throw new IOException("KataGo did not initialize the requested Metal GPU lane.");
    }
    if (candidate.devices().contains(KataGoTuningCandidate.METAL_ANE)
        && !observation.coreMlInitialized()) {
      throw new IOException("KataGo did not initialize the requested Apple Neural Engine lane.");
    }
    return observation;
  }

  private static Thread startBenchmarkCancellationWatcher(
      Process process,
      DownloadSession session,
      boolean requireComputeIsolation,
      AtomicBoolean computeIsolationLost) {
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
                  if (requireComputeIsolation && !isLayeredBenchmarkComputeIsolated()) {
                    if (computeIsolationLost != null) {
                      computeIsolationLost.set(true);
                    }
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
      BenchmarkProgressTracker progressTracker,
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
                  if (sinceLastProgress >= 1200L
                      && (progressTracker == null
                          || !progressTracker.hasObservedPositionProgress())) {
                    int syntheticPermille =
                        estimatePrePositionBenchmarkPermille(
                            now - benchmarkStartedAt.get(), lastProgressPermille.get());
                    int displayPermille =
                        advanceAtomicBenchmarkProgress(lastProgressPermille, syntheticPermille);
                    notifyProgress(
                        listener,
                        formatBenchmarkHeartbeatStatus(
                            now - benchmarkStartedAt.get(), displayPermille),
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
    int displayProgress =
        lastProgressPermille == null
            ? progressValue
            : advanceAtomicBenchmarkProgress(lastProgressPermille, progressValue);
    notifyProgress(listener, trimStatusForUi(trimmed), displayProgress, 1000L);
    if (lastProgressAt != null) {
      lastProgressAt.set(System.currentTimeMillis());
    }
    return trimmed;
  }

  private static int advanceAtomicBenchmarkProgress(
      AtomicInteger progressPermille, int candidatePermille) {
    int candidate = Math.max(0, Math.min(BENCHMARK_PROGRESS_VISIBLE_CAP, candidatePermille));
    if (progressPermille == null) {
      return candidate;
    }
    while (true) {
      int current = progressPermille.get();
      if (candidate <= current) {
        return current;
      }
      if (progressPermille.compareAndSet(current, candidate)) {
        return candidate;
      }
    }
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
    DownloadSession activeSession = session != null ? session : new DownloadSession();
    boolean officialAppleSilicon = isAppleSiliconOptimizationEligible(snapshot);
    BenchmarkResult result =
        officialAppleSilicon
            ? runOfficialAppleSiliconBenchmark(snapshot, listener, activeSession)
            : runBenchmark(snapshot, listener, activeSession);
    if (officialAppleSilicon) {
      requireLayeredBenchmarkComputeIsolation();
    }
    activeSession.throwIfCancelled();
    applyBenchmarkResult(result);
    rememberBenchmarkContext(snapshot, result);
    return result;
  }

  private static BenchmarkResult runOfficialAppleSiliconBenchmark(
      SetupSnapshot snapshot, ProgressListener listener, DownloadSession session)
      throws IOException {
    validateBenchmarkSnapshot(snapshot);
    DownloadSession activeSession = session != null ? session : new DownloadSession();
    activeSession.throwIfCancelled();
    requireLayeredBenchmarkComputeIsolation();
    notifyProgress(
        listener,
        resource("AutoSetup.benchmarkPreparing", "Preparing benchmark and pausing analysis..."),
        20L,
        1000L);

    KataGoTuningFingerprint fingerprint =
        KataGoTuningFingerprint.create(
            snapshot.enginePath,
            snapshot.activeWeightPath,
            snapshot.gtpConfigPath,
            currentAppleHardwareProfile(),
            officialTuningCommandSemantics(snapshotSourceCommand(snapshot)),
            activeSession::throwIfCancelled);
    activeSession.throwIfCancelled();

    String output =
        runBenchmarkProcess(
            snapshot, buildBenchmarkCommand(snapshot), listener, activeSession, 0, true);
    BenchmarkResult parsed = parseBenchmarkOutput(output);
    if (parsed == null) {
      throw benchmarkFailedException();
    }
    KataGoBenchmarkObservation observation = KataGoBenchmarkParser.parse(output, 0);
    if (observation.failureDetected()) {
      throw benchmarkFailedException();
    }
    KataGoTuningProfile.Metrics metrics =
        observation
            .recommendedMetric()
            .map(KataGoTuningProfile.Metrics::from)
            .orElseGet(() -> new KataGoTuningProfile.Metrics(0.0, 0.0, 0.0, 0.0));
    String backend =
        parsed.backendLabel == null || parsed.backendLabel.isEmpty()
            ? observation.backend()
            : parsed.backendLabel;
    long completedAt = System.currentTimeMillis();
    KataGoTuningProfile profile =
        KataGoTuningProfile.officialThreads(
            fingerprint, parsed.recommendedThreads, metrics, backend, completedAt);
    String officialSummary =
        "KataGo official benchmark (-time "
            + resolveBenchmarkTimeSeconds()
            + "s)"
            + (parsed.summary == null || parsed.summary.isEmpty() ? "" : " | " + parsed.summary);
    return new BenchmarkResult(
        parsed.recommendedThreads,
        parsed.currentThreads,
        backend,
        officialSummary,
        completedAt,
        "",
        0,
        metrics.visitsPerSecond(),
        metrics.nnEvalsPerSecond(),
        metrics.averageBatchSize(),
        profile);
  }

  /** Runs the opt-in Apple Silicon GPU/ANE and batch search. This is never the default path. */
  public static BenchmarkResult runExperimentalAppleSiliconBenchmarkAndApply(
      SetupSnapshot snapshot, ProgressListener listener, DownloadSession session)
      throws IOException {
    if (snapshot == null) {
      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    }
    if (!isAppleSiliconOptimizationEligible(snapshot)) {
      throw new IOException(
          resource(
              "AutoSetup.experimentalBenchmarkUnavailable",
              "Experimental hardware tuning requires the bundled Apple Silicon Metal engine."));
    }
    DownloadSession activeSession = session != null ? session : new DownloadSession();
    BenchmarkResult result = runLayeredAppleSiliconBenchmark(snapshot, listener, activeSession);
    requireLayeredBenchmarkComputeIsolation();
    activeSession.throwIfCancelled();
    applyBenchmarkResult(result);
    rememberBenchmarkContext(snapshot, result);
    return result;
  }

  public static boolean isExperimentalAppleSiliconTuningAvailable(SetupSnapshot snapshot) {
    return isAppleSiliconOptimizationEligible(snapshot);
  }

  private static BenchmarkResult runLayeredAppleSiliconBenchmark(
      SetupSnapshot snapshot, ProgressListener listener, DownloadSession session)
      throws IOException {
    validateBenchmarkSnapshot(snapshot);
    DownloadSession activeSession = session != null ? session : new DownloadSession();
    activeSession.throwIfCancelled();
    if (!isLayeredBenchmarkComputeIsolated()) {
      throw new IOException(
          resource("AutoSetup.benchmarkFailed", "Unable to run KataGo benchmark right now."));
    }
    notifyProgress(
        listener,
        resource("AutoSetup.benchmarkPreparing", "Preparing benchmark and pausing analysis..."),
        20L,
        1000L);
    AppleSiliconHardwareProbe.HardwareProfile hardware = currentAppleHardwareProfile();
    activeSession.throwIfCancelled();
    KataGoTuningFingerprint fingerprint =
        KataGoTuningFingerprint.create(
            snapshot.enginePath,
            snapshot.activeWeightPath,
            snapshot.gtpConfigPath,
            hardware,
            tuningCommandSemantics(snapshotSourceCommand(snapshot)),
            activeSession::throwIfCancelled);
    activeSession.throwIfCancelled();
    List<KataGoTuningCandidate> candidates =
        AppleSiliconTuningPlanner.candidates(hardware, Files.size(snapshot.activeWeightPath));
    if (candidates.isEmpty()) {
      throw benchmarkFailedException();
    }

    long scheduleSeed = System.nanoTime() ^ ((long) fingerprint.canonicalDigest().hashCode() << 32);
    int totalSegments =
        candidates.size()
            + KataGoExperimentalTuningSelector.DEFAULT_SHORTLIST_LIMIT
                * (1 + KataGoExperimentalTuningSelector.REQUIRED_VERIFICATION_SAMPLES);
    int segment = 0;
    Map<KataGoTuningCandidate, KataGoBenchmarkObservation> smokeResults =
        new LinkedHashMap<KataGoTuningCandidate, KataGoBenchmarkObservation>();
    for (KataGoTuningCandidate candidate :
        KataGoExperimentalTuningSelector.shuffledCopy(candidates, scheduleSeed)) {
      activeSession.throwIfCancelled();
      try {
        KataGoBenchmarkObservation observation =
            runLayeredBenchmarkCell(
                snapshot,
                candidate,
                LAYERED_BENCHMARK_SMOKE_THREADS,
                LAYERED_BENCHMARK_SMOKE_POSITIONS,
                LAYERED_BENCHMARK_SMOKE_VISITS,
                layeredProgressListener(
                    listener, "Metal " + candidate.displayId() + " smoke", segment, totalSegments),
                activeSession);
        smokeResults.put(candidate, observation);
      } catch (DownloadCancelledException e) {
        throw e;
      } catch (IOException e) {
        System.err.println(
            "Skipping KataGo Metal candidate "
                + candidate.displayId()
                + ": "
                + e.getLocalizedMessage());
      }
      segment++;
    }
    if (smokeResults.isEmpty()) {
      throw benchmarkFailedException();
    }

    List<KataGoTuningCandidate> shortlist =
        KataGoExperimentalTuningSelector.shortlist(smokeResults);
    if (shortlist.isEmpty()) {
      throw new IOException(
          "The mandatory single-GPU baseline did not complete the experimental smoke test.");
    }

    Map<KataGoTuningCandidate, KataGoBenchmarkObservation> officialResults =
        new LinkedHashMap<KataGoTuningCandidate, KataGoBenchmarkObservation>();
    for (KataGoTuningCandidate candidate :
        KataGoExperimentalTuningSelector.shuffledCopy(shortlist, scheduleSeed ^ 0x5DEECE66DL)) {
      activeSession.throwIfCancelled();
      try {
        KataGoBenchmarkObservation observation =
            runLayeredBenchmarkCell(
                snapshot,
                candidate,
                0,
                LAYERED_BENCHMARK_FINAL_POSITIONS,
                LAYERED_BENCHMARK_FINAL_VISITS,
                layeredProgressListener(
                    listener,
                    "Metal " + candidate.displayId() + " official search",
                    segment,
                    totalSegments),
                activeSession);
        if (candidate.mixed() && observation.recommendedThreads() < 2) {
          throw new IOException("KataGo returned an invalid thread count for a mixed topology.");
        }
        officialResults.put(candidate, observation);
      } catch (DownloadCancelledException e) {
        throw e;
      } catch (IOException e) {
        System.err.println(
            "KataGo official thread search failed for "
                + candidate.displayId()
                + ": "
                + e.getLocalizedMessage());
      }
      segment++;
    }
    KataGoTuningCandidate baseline = shortlist.get(0);
    if (!officialResults.containsKey(baseline)) {
      throw new IOException(
          "The mandatory single-GPU baseline did not complete KataGo's official thread search.");
    }

    List<KataGoTuningCandidate> verificationCandidates =
        shortlist.stream().filter(officialResults::containsKey).toList();
    Map<KataGoTuningCandidate, List<KataGoBenchmarkObservation>> verificationSamples =
        new LinkedHashMap<KataGoTuningCandidate, List<KataGoBenchmarkObservation>>();
    for (KataGoTuningCandidate candidate : verificationCandidates) {
      verificationSamples.put(candidate, new ArrayList<KataGoBenchmarkObservation>());
    }
    List<List<KataGoTuningCandidate>> verificationRounds =
        KataGoExperimentalTuningSelector.verificationRounds(
            verificationCandidates,
            KataGoExperimentalTuningSelector.REQUIRED_VERIFICATION_SAMPLES,
            scheduleSeed ^ 0x9E3779B97F4A7C15L);
    int roundNumber = 0;
    for (List<KataGoTuningCandidate> round : verificationRounds) {
      roundNumber++;
      for (KataGoTuningCandidate candidate : round) {
        activeSession.throwIfCancelled();
        KataGoBenchmarkObservation official = officialResults.get(candidate);
        try {
          KataGoBenchmarkObservation verification =
              runLayeredBenchmarkCell(
                  snapshot,
                  candidate,
                  official.recommendedThreads(),
                  LAYERED_BENCHMARK_FINAL_POSITIONS,
                  LAYERED_BENCHMARK_FINAL_VISITS,
                  layeredProgressListener(
                      listener,
                      "Metal "
                          + candidate.displayId()
                          + " verification "
                          + roundNumber
                          + "/"
                          + KataGoExperimentalTuningSelector.REQUIRED_VERIFICATION_SAMPLES,
                      segment,
                      totalSegments),
                  activeSession);
          verificationSamples.get(candidate).add(verification);
        } catch (DownloadCancelledException e) {
          throw e;
        } catch (IOException e) {
          if (!isLayeredBenchmarkComputeIsolated()) {
            throw e;
          }
          System.err.println(
              "KataGo verification failed for "
                  + candidate.displayId()
                  + " in round "
                  + roundNumber
                  + ": "
                  + e.getLocalizedMessage());
        }
        segment++;
      }
    }

    KataGoExperimentalTuningSelector.Selection selection =
        KataGoExperimentalTuningSelector.selectValidated(officialResults, verificationSamples)
            .orElseThrow(KataGoRuntimeHelper::benchmarkFailedException);
    if (selection.usesBaselineFallback()
        && selection
            .verification()
            .filter(KataGoExperimentalTuningSelector.Aggregate::stable)
            .isEmpty()) {
      throw new IOException(
          "The single-GPU baseline stability check was inconclusive; keeping the previous profile.");
    }
    requireLayeredBenchmarkComputeIsolation();
    KataGoTuningCandidate candidate = selection.candidate();
    KataGoBenchmarkObservation official = officialResults.get(candidate);
    KataGoBenchmarkObservation.ThreadMetrics selectedMetrics = selection.metrics();
    long completedAt = System.currentTimeMillis();
    String backend = official.backend();
    if (backend.isEmpty()) {
      backend = "Metal";
    }
    KataGoTuningProfile profile =
        new KataGoTuningProfile(
            fingerprint,
            candidate.devices(),
            candidate.batch(),
            selection.searchThreads(),
            selectedMetrics,
            backend,
            completedAt);
    String topology = candidate.id() + " " + candidate.devices();
    String verificationSummary =
        selection
            .verification()
            .map(
                aggregate ->
                    String.format(
                        Locale.ROOT,
                        "median=%.1f visits/s | spread=%.1f%%",
                        aggregate.medianVisitsPerSecond(),
                        aggregate.relativeSpread() * 100.0))
            .orElse(
                String.format(
                    Locale.ROOT,
                    "official=%.1f visits/s | verification=incomplete",
                    selectedMetrics.visitsPerSecond()));
    String decision =
        selection.challengerAccepted()
            ? String.format(
                Locale.ROOT,
                "accepted challenger | baselineGain=%.1f%%",
                selection.gainOverBaseline() * 100.0)
            : "safe single-GPU baseline fallback";
    String summary =
        String.format(
            Locale.ROOT,
            "Experimental Metal %s | batch=%d | officialThreads=%d | %s | %s | nn=%.1f/s",
            topology,
            candidate.batch(),
            selection.searchThreads(),
            verificationSummary,
            decision,
            selectedMetrics.nnEvalsPerSecond());
    notifyProgress(
        listener, resource("AutoSetup.benchmarkDone", "Benchmark complete."), 1000L, 1000L);
    return new BenchmarkResult(
        selection.searchThreads(),
        official.currentThreads(),
        backend,
        summary,
        completedAt,
        topology,
        candidate.batch(),
        selectedMetrics.visitsPerSecond(),
        selectedMetrics.nnEvalsPerSecond(),
        selectedMetrics.averageBatchSize(),
        profile);
  }

  private static ProgressListener layeredProgressListener(
      ProgressListener listener, String label, int segment, int totalSegments) {
    if (listener == null) {
      return null;
    }
    int safeTotal = Math.max(1, totalSegments);
    int safeSegment = Math.max(0, Math.min(safeTotal - 1, segment));
    return (statusText, completed, total) -> {
      long inner = total > 0L ? Math.max(0L, Math.min(1000L, completed * 1000L / total)) : 0L;
      long overall = Math.min(999L, (safeSegment * 1000L + inner) / safeTotal);
      String status = label + (Utils.isBlank(statusText) ? "" : " — " + statusText);
      listener.onProgress(status, overall, 1000L);
    };
  }

  public static void applyBenchmarkResult(BenchmarkResult result) throws IOException {
    if (result == null
        || result.recommendedThreads <= 0
        || Lizzie.config == null
        || Lizzie.config.uiConfig == null) {
      return;
    }
    JSONObject candidateUi = new JSONObject(Lizzie.config.uiConfig.toString());
    if (result.tuningProfile == null) {
      candidateUi.put("chk-kata-engine-threads", true);
      candidateUi.put("autoload-kata-engine-threads", true);
      candidateUi.put("txt-kata-engine-threads", String.valueOf(result.recommendedThreads));
    }
    candidateUi.put("katago-benchmark-threads", result.recommendedThreads);
    candidateUi.put("katago-benchmark-current-threads", result.currentThreads);
    candidateUi.put("katago-benchmark-backend", result.backendLabel);
    candidateUi.put("katago-benchmark-summary", result.summary);
    candidateUi.put("katago-benchmark-updated-at", result.completedAtMillis);
    candidateUi.put("katago-benchmark-topology", result.topologyLabel);
    candidateUi.put("katago-benchmark-batch-size", result.maxBatchSize);
    candidateUi.put("katago-benchmark-visits-per-second", result.visitsPerSecond);
    candidateUi.put("katago-benchmark-nn-evals-per-second", result.nnEvalsPerSecond);
    candidateUi.put("katago-benchmark-average-batch-size", result.averageBatchSize);
    if (result.tuningProfile != null) {
      new KataGoTuningStore(candidateUi).save(result.tuningProfile);
    }
    JSONObject currentLeelaz =
        Lizzie.config.leelazConfig == null ? new JSONObject() : Lizzie.config.leelazConfig;
    Lizzie.config.saveConfigSections(candidateUi, currentLeelaz);
    if (result.tuningProfile == null) {
      Lizzie.config.chkKataEngineThreads = true;
      Lizzie.config.autoLoadKataEngineThreads = true;
      Lizzie.config.txtKataEngineThreads = String.valueOf(result.recommendedThreads);
    }
  }

  public static void applyBenchmarkResultToRunningEngines(BenchmarkResult result) {
    if (result == null || result.recommendedThreads <= 0) {
      return;
    }
    if (result.tuningProfile != null && result.tuningProfile.managesHardwareSettings()) {
      // Metal topology and batch size are startup-only. The benchmark lifecycle lease restarts the
      // main engine after this call, so avoid briefly applying only the thread portion.
      restartIdlePreloadedAnalysisEngine();
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

  static boolean isLayeredBenchmarkComputeIsolated() {
    boolean pauseActive;
    boolean pauseConfirmedIsolation;
    synchronized (BENCHMARK_ANALYSIS_PAUSE_LOCK) {
      pauseActive = benchmarkEngineSyncSuppressed;
      pauseConfirmedIsolation = benchmarkComputeIsolated;
    }
    if (pauseActive && !pauseConfirmedIsolation) {
      return false;
    }
    return !AnalysisResourceCoordinator.hasActiveLocalComputeProcess()
        && isEngineComputeIsolated(Lizzie.leelaz);
  }

  private static boolean isEngineComputeIsolated(Leelaz engine) {
    return engine == null || engine.isProcessDead() || (!engine.isLoaded() && !engine.isStarted());
  }

  public record BenchmarkPauseResult(
      boolean accepted, boolean analysisWasPondering, boolean computeIsolated) {}

  public static BenchmarkPauseResult pauseCurrentAnalysisForBenchmark() {
    Leelaz currentEngine = Lizzie.leelaz;
    boolean analysisWasPondering =
        currentEngine != null
            && currentEngine.isLoaded()
            && currentEngine.isPonderingOrWasPonderingBeforeTracking();
    Leelaz.ExclusiveGtpLifecycleReservation reservation =
        currentEngine == null ? null : currentEngine.beginExclusiveGtpLifecycleReservation();
    if (currentEngine != null && reservation == null) {
      return new BenchmarkPauseResult(false, false, false);
    }
    try {
      synchronized (BENCHMARK_ANALYSIS_PAUSE_LOCK) {
        if (benchmarkEngineSyncSuppressed) {
          return new BenchmarkPauseResult(false, false, false);
        }
        if (benchmarkPreviousShowPonderTips == null && Lizzie.config != null) {
          benchmarkPreviousShowPonderTips = Lizzie.config.showPonderLimitedTips;
          Lizzie.config.showPonderLimitedTips = false;
        }
        benchmarkPausedEngine = currentEngine;
        benchmarkPausedEngineManager = null;
        benchmarkPausedEngineList = null;
        benchmarkPausedEngineIndex = -1;
        benchmarkPausedEngineByShutdown = false;
        benchmarkComputeIsolated = isEngineComputeIsolated(currentEngine);
        benchmarkRestartQuickAnalysisPreload = false;
        benchmarkRestartEstimatePreload = false;
        benchmarkEngineSyncSuppressed = true;
      }

      releaseIdleAuxiliaryComputeForBenchmark();

      if (currentEngine != null
          && currentEngine.isLoaded()
          && currentEngine.isKatago
          && Lizzie.engineManager != null
          && !EngineManager.isEmpty
          && !EngineManager.isEngineGame) {
        try {
          boolean pauseByShutdown;
          synchronized (BENCHMARK_ANALYSIS_PAUSE_LOCK) {
            EngineManager manager = Lizzie.engineManager;
            List<Leelaz> engines = manager == null ? null : manager.engineList;
            int engineIndex = EngineManager.currentEngineNo;
            pauseByShutdown =
                engines != null
                    && engineIndex >= 0
                    && engineIndex < engines.size()
                    && engines.get(engineIndex) == currentEngine;
            benchmarkPausedEngine = currentEngine;
            benchmarkPausedEngineManager = pauseByShutdown ? manager : null;
            benchmarkPausedEngineList = pauseByShutdown ? engines : null;
            benchmarkPausedEngineIndex = pauseByShutdown ? engineIndex : -1;
            benchmarkPausedEngineByShutdown = pauseByShutdown;
          }
          if (!pauseByShutdown) {
            if (currentEngine.isPondering()) {
              currentEngine.togglePonder();
            }
            return new BenchmarkPauseResult(true, analysisWasPondering, false);
          }
          if (analysisWasPondering) {
            currentEngine.Pondering();
          } else {
            currentEngine.notPondering();
          }
          currentEngine.canCheckAlive = false;
          currentEngine.normalQuit();
          currentEngine.shutdown();
          boolean shutdownConfirmed = waitForEngineShutdown(currentEngine, 8000L);
          boolean computeIsolated =
              shutdownConfirmed
                  && waitForRegisteredLocalComputeShutdown(
                      BENCHMARK_AUXILIARY_SHUTDOWN_WAIT_MILLIS);
          synchronized (BENCHMARK_ANALYSIS_PAUSE_LOCK) {
            benchmarkComputeIsolated = computeIsolated;
          }
          return new BenchmarkPauseResult(true, analysisWasPondering, computeIsolated);
        } catch (Exception e) {
          synchronized (BENCHMARK_ANALYSIS_PAUSE_LOCK) {
            benchmarkPausedEngineManager = null;
            benchmarkPausedEngineList = null;
            benchmarkPausedEngineIndex = -1;
            benchmarkPausedEngineByShutdown = false;
            benchmarkComputeIsolated = false;
          }
        }
      }

      if (currentEngine != null && currentEngine.isPondering()) {
        try {
          currentEngine.togglePonder();
        } catch (Exception ignored) {
        }
      }
      boolean computeIsolated;
      synchronized (BENCHMARK_ANALYSIS_PAUSE_LOCK) {
        computeIsolated = benchmarkComputeIsolated;
      }
      if (computeIsolated) {
        computeIsolated =
            waitForRegisteredLocalComputeShutdown(BENCHMARK_AUXILIARY_SHUTDOWN_WAIT_MILLIS);
        synchronized (BENCHMARK_ANALYSIS_PAUSE_LOCK) {
          benchmarkComputeIsolated = computeIsolated;
        }
      }
      return new BenchmarkPauseResult(true, analysisWasPondering, computeIsolated);
    } finally {
      if (reservation != null) {
        reservation.close();
      }
    }
  }

  private static boolean benchmarkPausedEngineIdentityMatchesLocked(
      Leelaz pausedEngine, int pausedEngineIndex) {
    return pausedEngine != null
        && Lizzie.engineManager == benchmarkPausedEngineManager
        && benchmarkPausedEngineManager != null
        && benchmarkPausedEngineManager.engineList == benchmarkPausedEngineList
        && benchmarkPausedEngineList != null
        && pausedEngineIndex >= 0
        && pausedEngineIndex < benchmarkPausedEngineList.size()
        && benchmarkPausedEngineList.get(pausedEngineIndex) == pausedEngine;
  }

  private static void releaseIdleAuxiliaryComputeForBenchmark() {
    if (Lizzie.frame == null) {
      return;
    }
    AnalysisEngine auxiliaryAnalysis = Lizzie.frame.analysisEngine;
    if (auxiliaryAnalysis != null
        && auxiliaryAnalysis.isLocalDedicatedProcess()
        && !auxiliaryAnalysis.isAnalysisInProgress()) {
      Lizzie.frame.analysisEngine = null;
      synchronized (BENCHMARK_ANALYSIS_PAUSE_LOCK) {
        benchmarkRestartQuickAnalysisPreload =
            Lizzie.config != null && Lizzie.config.analysisEnginePreLoad;
      }
      try {
        auxiliaryAnalysis.clearRequestCallbacks();
        auxiliaryAnalysis.normalQuit();
      } catch (RuntimeException ignored) {
      }
    }

    KataEstimate estimate = Lizzie.frame.zen;
    if (estimate != null && !Lizzie.frame.isCounting && !Lizzie.frame.isAutocounting) {
      Lizzie.frame.zen = null;
      synchronized (BENCHMARK_ANALYSIS_PAUSE_LOCK) {
        benchmarkRestartEstimatePreload = true;
      }
      try {
        estimate.shutdown();
      } catch (RuntimeException ignored) {
      }
    }
  }

  public static void restoreAnalysisAfterBenchmark(boolean analysisWasPondering) {
    Leelaz pausedEngine;
    int pausedEngineIndex;
    boolean pausedEngineByShutdown;
    boolean restartQuickAnalysisPreload;
    boolean restartEstimatePreload;
    Leelaz.AutomaticRestartAttempt restartAttempt = null;
    synchronized (BENCHMARK_ANALYSIS_PAUSE_LOCK) {
      if (benchmarkPreviousShowPonderTips != null && Lizzie.config != null) {
        Lizzie.config.showPonderLimitedTips = benchmarkPreviousShowPonderTips.booleanValue();
      }
      benchmarkPreviousShowPonderTips = null;
      pausedEngine = benchmarkPausedEngine;
      pausedEngineIndex = benchmarkPausedEngineIndex;
      pausedEngineByShutdown = benchmarkPausedEngineByShutdown;
      restartQuickAnalysisPreload = benchmarkRestartQuickAnalysisPreload;
      restartEstimatePreload = benchmarkRestartEstimatePreload;
      if (pausedEngineByShutdown
          && benchmarkPausedEngineIdentityMatchesLocked(pausedEngine, pausedEngineIndex)) {
        restartAttempt = pausedEngine.beginAutomaticEngineRestartAttempt();
        if (restartAttempt != null
            && !benchmarkPausedEngineIdentityMatchesLocked(pausedEngine, pausedEngineIndex)) {
          restartAttempt.close();
          restartAttempt = null;
        }
      }
      benchmarkPausedEngine = null;
      benchmarkPausedEngineManager = null;
      benchmarkPausedEngineList = null;
      benchmarkPausedEngineIndex = -1;
      benchmarkPausedEngineByShutdown = false;
      benchmarkComputeIsolated = false;
      benchmarkRestartQuickAnalysisPreload = false;
      benchmarkRestartEstimatePreload = false;
      benchmarkEngineSyncSuppressed = false;
    }
    restartAuxiliaryComputeAfterBenchmark(restartQuickAnalysisPreload, restartEstimatePreload);
    if (pausedEngineByShutdown) {
      if (restartAttempt == null) {
        return;
      }
      try {
        if (analysisWasPondering) {
          pausedEngine.Pondering();
        } else {
          pausedEngine.notPondering();
        }
        restartAttempt.restartClosedEngine(pausedEngineIndex);
        return;
      } catch (Exception e) {
        restartAttempt.close();
        return;
      }
    }
    if (!analysisWasPondering) {
      return;
    }
    if (pausedEngine == null || !pausedEngine.isLoaded() || pausedEngine.isPondering()) {
      return;
    }
    try {
      pausedEngine.togglePonder();
    } catch (Exception ignored) {
    }
  }

  private static void restartAuxiliaryComputeAfterBenchmark(
      boolean restartQuickAnalysisPreload, boolean restartEstimatePreload) {
    if ((!restartQuickAnalysisPreload && !restartEstimatePreload) || Lizzie.frame == null) {
      return;
    }
    SwingUtilities.invokeLater(
        () -> {
          if (Lizzie.frame == null) {
            return;
          }
          if (restartQuickAnalysisPreload) {
            Lizzie.frame.preloadConfiguredAnalysisEngineAfterStartup();
          }
          if (restartEstimatePreload) {
            Lizzie.frame.preloadEstimateEngineAfterStartup();
          }
        });
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

  static boolean waitForEngineShutdown(Leelaz engine, long timeoutMillis) {
    if (engine == null) {
      return true;
    }
    long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMillis);
    while (System.currentTimeMillis() < deadline) {
      if (engine.isProcessDead()) {
        return true;
      }
      try {
        long remainingMillis = Math.max(1L, deadline - System.currentTimeMillis());
        Thread.sleep(Math.min(100L, remainingMillis));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return engine.isProcessDead();
  }

  private static boolean waitForRegisteredLocalComputeShutdown(long timeoutMillis) {
    long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMillis);
    while (AnalysisResourceCoordinator.hasActiveLocalComputeProcess()
        && System.currentTimeMillis() < deadline) {
      try {
        long remainingMillis = Math.max(1L, deadline - System.currentTimeMillis());
        Thread.sleep(Math.min(50L, remainingMillis));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return !AnalysisResourceCoordinator.hasActiveLocalComputeProcess();
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
              boolean benchmarkPauseAccepted = false;
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
                        "正在进行 Apple Silicon 智能提升算棋速度",
                        "正在使用当前模型和当前硬件配置运行 KataGo 官方 benchmark。<br/>"
                            + "通常需要 2-10 分钟；不会更换模型，也不会默认尝试实验性 GPU/ANE 组合。<br/>"
                            + "完成后只会为完全匹配的电脑、引擎、模型和配置保存官方推荐线程数。<br/>"
                            + "优化期间会暂停当前分析，完成后会自动恢复。<br/>"
                            + "你可以继续使用主窗口；如果暂时不想优化，关闭这个窗口即可停止本次优化。",
                        session);
                final JDialog progressNotice = notice;
                if (session.isCancelled()) return;
                BenchmarkPauseResult pauseResult = pauseCurrentAnalysisForBenchmark();
                if (!pauseResult.accepted()) {
                  return;
                }
                benchmarkPauseAccepted = true;
                pausedAnalysis = pauseResult.analysisWasPondering();

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
                if (benchmarkPauseAccepted) {
                  restoreAnalysisAfterBenchmark(pausedAnalysis);
                }
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
        statusText == null || statusText.trim().isEmpty() ? "优化中..." : statusText;
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

  static final class BenchmarkProgressTracker {
    private static final int MODEL_LOAD_PROGRESS = 30;
    private static final int THREAD_LIST_PROGRESS = 80;
    private static final int SEARCH_PROGRESS_START = 100;
    private static final int SEARCH_PROGRESS_SPAN = 870;
    private static final int SUMMARY_PROGRESS = 990;
    private static final int FALLBACK_EXPECTED_THREAD_COUNT = 12;

    private final Map<Integer, Integer> completedPositionsByThread =
        new HashMap<Integer, Integer>();
    private int expectedTestedThreadCount;
    private int observedThreadCount = 0;
    private int positionsPerThread = 0;
    private int lastPermille = 0;
    private volatile boolean observedPositionProgress = false;

    BenchmarkProgressTracker() {
      this(0);
    }

    BenchmarkProgressTracker(int expectedThreadTests) {
      if (expectedThreadTests < 0) {
        throw new IllegalArgumentException("expectedThreadTests must not be negative");
      }
      expectedTestedThreadCount = expectedThreadTests;
    }

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
        if (possibleThreadCount > 0) {
          expectedTestedThreadCount = Math.max(expectedTestedThreadCount, possibleThreadCount);
        }
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
        observedPositionProgress = true;
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
          expectedTestedThreadCount = FALLBACK_EXPECTED_THREAD_COUNT;
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

    boolean hasObservedPositionProgress() {
      return observedPositionProgress;
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
                          "KataGo 智能提升算棋速度",
                          "首次启动正在进行一次 KataGo 智能提升算棋速度，<br/>"
                              + "将使用当前模型运行 KataGo 官方线程 benchmark，<br/>"
                              + "不会自动更换模型或启用实验性硬件组合。<br/><br/>"
                              + "通常需要 2-10 分钟；电脑较慢、模型较大时可能更久，请稍候。<br/>"
                              + "期间分析会被暂停，完成后会自动恢复。<br/>"
                              + "如果暂时不想优化，可以关闭这个窗口停止优化。",
                          benchmarkSession)
                      : null;

              boolean pausedAnalysis = false;
              boolean benchmarkPauseAccepted = false;

              try {
                if (benchmarkSession.isCancelled()) {
                  return;
                }
                BenchmarkPauseResult pauseResult = pauseCurrentAnalysisForBenchmark();
                if (!pauseResult.accepted()) {
                  return;
                }
                benchmarkPauseAccepted = true;
                pausedAnalysis = pauseResult.analysisWasPondering();
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
                if (benchmarkPauseAccepted) {
                  restoreAnalysisAfterBenchmark(pausedAnalysis);
                }
              }
            },
            "katago-first-run-benchmark");
    worker.setDaemon(true);
    worker.start();
  }

  public static String optimizeAnalysisEngineCommand(
      String engineCommand, int maxVisits, boolean isBatchAnalysisMode) {
    return optimizeAnalysisEngineCommand(engineCommand, maxVisits, isBatchAnalysisMode, false);
  }

  public static String optimizeAnalysisEngineCommand(
      String engineCommand,
      int maxVisits,
      boolean isBatchAnalysisMode,
      boolean wholeGameThroughput) {
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

    if (wholeGameThroughput && looksLikeKataGoCommand(engineCommand)) {
      AnalysisThreadProfile profile = resolveWholeGameAnalysisProfile();
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
      return commandChanged ? buildCommandLine(commandParts) : engineCommand;
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

  private static void configureNvidiaRuntimeCacheEnvironment(
      ProcessBuilder processBuilder, Path enginePath, Path runtimeDir) {
    if (processBuilder == null || runtimeDir == null) {
      return;
    }
    String backend = resolveNvidiaBackend(enginePath);
    if (backend == null) {
      return;
    }
    try {
      Path cacheRoot = Files.createDirectories(runtimeDir.resolve(NVIDIA_RUNTIME_CACHE_DIR));
      Path cudaCache = Files.createDirectories(cacheRoot.resolve(NVIDIA_CUDA_CACHE_DIR));
      processBuilder.environment().put("CUDA_CACHE_PATH", cudaCache.toString());
      if (isTensorRtBackend(backend)) {
        Path tempCache = Files.createDirectories(cacheRoot.resolve(NVIDIA_TENSORRT_TEMP_DIR));
        processBuilder.environment().put("TEMP", tempCache.toString());
        processBuilder.environment().put("TMP", tempCache.toString());
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
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

  private static AnalysisThreadProfile resolveWholeGameAnalysisProfile() {
    int totalThreadBudget = Math.max(2, Math.min(24, Utils.getRecommendedKataGoThreads()));
    int numAnalysisThreads = Math.max(2, Math.min(8, totalThreadBudget / 2));
    int perAnalysisThread = Math.max(1, Math.min(2, totalThreadBudget / numAnalysisThreads));
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

  private static Path getOpenClFp32HomeDataDir() {
    if (Lizzie.config == null) {
      return null;
    }
    return Lizzie.config
        .getRuntimeWorkDirectory()
        .toPath()
        .resolve(OPENCL_FP32_HOME_DATA_DIR)
        .toAbsolutePath()
        .normalize();
  }

  private static Path getOpenClFp32CompatibilityMarker() {
    Path homeDataDir = getOpenClFp32HomeDataDir();
    return homeDataDir == null ? null : homeDataDir.resolve(OPENCL_FP32_COMPATIBILITY_MARKER);
  }

  /**
   * Returns true when the bundled engine still needs a one-time OpenCL autotuning pass, i.e. no
   * cached tuning parameters exist yet. The first OpenCL tuning can take a few minutes, so callers
   * should grant a longer startup budget in that case.
   */
  public static boolean needsFirstOpenCLTuning(Path enginePath) {
    return needsFirstOpenCLTuning(enginePath, false);
  }

  public static boolean needsFirstOpenCLTuning(Path enginePath, boolean openClFp32Compatibility) {
    if (!isWindowsPlatform()) {
      return false;
    }
    if (enginePath == null || !Config.isBundledKataGoExecutable(enginePath)) {
      return false;
    }
    // NVIDIA TensorRT/CUDA packages do not rely on the OpenCL tuning cache.
    if (resolveNvidiaBackend(enginePath) != null) {
      return false;
    }
    Path homeDataDir =
        openClFp32Compatibility ? getOpenClFp32HomeDataDir() : getBundledHomeDataDir();
    if (homeDataDir == null) {
      return false;
    }
    Path tuningDir = homeDataDir.resolve("opencltuning");
    if (!Files.isDirectory(tuningDir)) {
      return true;
    }
    try (Stream<Path> entries = Files.list(tuningDir)) {
      return entries.noneMatch(
          p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"));
    } catch (IOException e) {
      return true;
    }
  }

  private static boolean shouldUseOpenClFp32Compatibility(List<String> command, Path enginePath) {
    if (!isBundledOpenClPath(enginePath)) {
      return false;
    }
    if ("false".equalsIgnoreCase(findOverrideConfigValue(command, "openclUseFP16"))) {
      return true;
    }
    String driverVersion = resolveNvidiaDriverVersion();
    Path marker = getOpenClFp32CompatibilityMarker();
    if (marker == null || !Files.isRegularFile(marker)) {
      return false;
    }
    String expected = buildOpenClCompatibilitySignature(command, enginePath, driverVersion);
    if (expected.isEmpty()) {
      return false;
    }
    try {
      return expected.equals(Files.readString(marker, StandardCharsets.UTF_8).trim());
    } catch (IOException e) {
      return false;
    }
  }

  private static String resolveNvidiaDriverVersion() {
    String configured = System.getProperty(OPENCL_NVIDIA_DRIVER_VERSION_PROPERTY, "").trim();
    if (!configured.isEmpty()) {
      return "none".equalsIgnoreCase(configured) ? "" : normalizeDriverVersion(configured);
    }
    if (nvidiaDriverDetectionComplete) {
      return detectedNvidiaDriverVersion;
    }
    synchronized (NVIDIA_DRIVER_DETECTION_LOCK) {
      if (!nvidiaDriverDetectionComplete) {
        NvidiaGpuDetector.DetectionResult detection = NvidiaGpuDetector.detectBestGpu();
        detectedNvidiaDriverVersion =
            detection != null && detection.bestGpu != null
                ? normalizeDriverVersion(detection.bestGpu.driverVersion)
                : "";
        nvidiaDriverDetectionComplete = true;
      }
      return detectedNvidiaDriverVersion;
    }
  }

  private static String normalizeDriverVersion(String driverVersion) {
    return driverVersion == null ? "" : driverVersion.trim().replace(',', '.');
  }

  private static Path resolveEffectiveHomeDataDir(List<String> command, Path fallback) {
    String configured = findOverrideConfigValue(command, "homeDataDir");
    if (configured.isEmpty()) {
      return fallback;
    }
    try {
      Path path = Paths.get(configured);
      if (!path.isAbsolute() && Lizzie.config != null) {
        path = Lizzie.config.getRuntimeWorkDirectory().toPath().resolve(path);
      }
      return path.toAbsolutePath().normalize();
    } catch (Exception e) {
      return fallback;
    }
  }

  private static void prepareBundledOpenClTuningCache(Path enginePath, Path homeDataDir) {
    if (!isBundledOpenClPath(enginePath) || homeDataDir == null) {
      return;
    }
    synchronized (OPENCL_TUNING_CACHE_LOCK) {
      Path generationMarker = homeDataDir.resolve(OPENCL_TUNING_CACHE_GENERATION_MARKER);
      try {
        if (Files.isRegularFile(generationMarker)
            && OPENCL_TUNING_CACHE_GENERATION.equals(
                Files.readString(generationMarker, StandardCharsets.UTF_8).trim())) {
          return;
        }

        Path tuningDir = homeDataDir.resolve("opencltuning");
        if (Files.exists(tuningDir)) {
          Path quarantine = availableOpenClTuningQuarantinePath(homeDataDir);
          Files.move(tuningDir, quarantine);
          System.err.println(
              "Moved legacy KataGo OpenCL tuning cache to " + quarantine.toAbsolutePath());
        }
        Files.writeString(
            generationMarker,
            OPENCL_TUNING_CACHE_GENERATION,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
      } catch (IOException e) {
        System.err.println(
            "Unable to prepare KataGo OpenCL tuning cache: " + e.getLocalizedMessage());
      }
    }
  }

  private static Path availableOpenClTuningQuarantinePath(Path homeDataDir) {
    Path candidate = homeDataDir.resolve("opencltuning-legacy");
    int suffix = 1;
    while (Files.exists(candidate)) {
      candidate = homeDataDir.resolve("opencltuning-legacy-" + suffix++);
    }
    return candidate;
  }

  private static String buildOpenClCompatibilitySignature(
      List<String> command, Path enginePath, String driverVersion) {
    Path modelPath = findCommandPath(command, "-model", "--model", "-weights", "--weights");
    if (enginePath == null || modelPath == null) {
      return "";
    }
    StringBuilder signature = new StringBuilder("v1");
    signature.append("|driver=").append(normalizeDriverVersion(driverVersion));
    appendPathFingerprint(signature, enginePath);
    appendPathFingerprint(signature, modelPath);
    return signature.toString();
  }

  private static Path findCommandPath(List<String> command, String... options) {
    if (command == null || options == null) {
      return null;
    }
    for (int i = 0; i + 1 < command.size(); i++) {
      String candidate = command.get(i);
      for (String option : options) {
        if (!option.equals(candidate)) {
          continue;
        }
        try {
          return Paths.get(command.get(i + 1)).toAbsolutePath().normalize();
        } catch (Exception e) {
          return null;
        }
      }
    }
    return null;
  }

  static String tuningCommandSemantics(List<String> command) {
    if (command == null || command.isEmpty()) {
      return "";
    }
    try {
      Map<String, String> canonical = new TreeMap<String, String>();
      for (Map.Entry<String, String> entry :
          KataGoCommandSpec.parse(command).effectiveOverrides().entrySet()) {
        if (!isTuningFingerprintOverride(entry.getKey())) {
          canonical.put(entry.getKey(), entry.getValue());
        }
      }
      StringBuilder result = new StringBuilder();
      for (Map.Entry<String, String> entry : canonical.entrySet()) {
        result.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
      }
      return result.toString();
    } catch (RuntimeException e) {
      return "";
    }
  }

  static String officialTuningCommandSemantics(List<String> command) {
    StringBuilder result = new StringBuilder();
    if (command != null && !command.isEmpty()) {
      try {
        Map<String, String> canonical = new TreeMap<String, String>();
        for (Map.Entry<String, String> entry :
            KataGoCommandSpec.parse(command).effectiveOverrides().entrySet()) {
          if (!isOfficialTuningFingerprintOverride(entry.getKey())) {
            canonical.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
          }
        }
        for (Map.Entry<String, String> entry : canonical.entrySet()) {
          result.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
      } catch (RuntimeException ignored) {
      }
    }
    result
        .append("benchmarkMode=officialThreads\n")
        .append("benchmarkTimeSeconds=")
        .append(resolveBenchmarkTimeSeconds())
        .append('\n');
    return result.toString();
  }

  private static boolean isOfficialTuningFingerprintOverride(String key) {
    String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    return normalized.equals("numsearchthreads")
        || normalized.equals("numanalysisthreads")
        || normalized.equals("numsearchthreadsperanalysisthread")
        || normalized.equals("homedatadir")
        || normalized.equals("analysispvlen")
        || normalized.equals("logtostderr")
        || normalized.equals("logallgtpcommunication")
        || normalized.equals("logsearchinfo");
  }

  private static boolean isTuningFingerprintOverride(String key) {
    if (isMetalTopologyOverride(key)) {
      return true;
    }
    String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    return normalized.equals("nnmaxbatchsize")
        || normalized.equals("numsearchthreads")
        || normalized.equals("numanalysisthreads")
        || normalized.equals("numsearchthreadsperanalysisthread")
        || normalized.equals("homedatadir")
        || normalized.equals("analysispvlen")
        || normalized.equals("logtostderr")
        || normalized.equals("logallgtpcommunication")
        || normalized.equals("logsearchinfo");
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

  private static String findOverrideConfigValue(List<String> command, String key) {
    if (command == null || key == null || key.trim().isEmpty()) {
      return "";
    }
    String expectedKey = key.trim();
    for (int i = 0; i + 1 < command.size(); i++) {
      if (!"-override-config".equals(command.get(i))) {
        continue;
      }
      String overrides = command.get(i + 1);
      if (overrides == null) {
        continue;
      }
      for (String entry : overrides.split(",")) {
        int separator = entry.indexOf('=');
        if (separator <= 0) {
          continue;
        }
        if (expectedKey.equalsIgnoreCase(entry.substring(0, separator).trim())) {
          return entry.substring(separator + 1).trim();
        }
      }
    }
    return "";
  }

  private static void setOverrideConfig(List<String> command, String keyValue) {
    if (command == null || keyValue == null || keyValue.trim().isEmpty()) {
      return;
    }
    String normalizedKey = overrideConfigKey(keyValue);
    if (normalizedKey.isEmpty()) {
      return;
    }
    for (int i = 0; i < command.size(); i++) {
      if (!"-override-config".equals(command.get(i))) {
        continue;
      }
      if (i + 1 >= command.size()) {
        command.add(keyValue);
        return;
      }
      String existing = command.get(i + 1);
      List<String> entries = new ArrayList<String>();
      boolean replaced = false;
      if (existing != null && !existing.trim().isEmpty()) {
        for (String entry : existing.split(",")) {
          if (normalizedKey.equals(overrideConfigKey(entry))) {
            if (!replaced) {
              entries.add(keyValue);
              replaced = true;
            }
          } else if (!entry.trim().isEmpty()) {
            entries.add(entry.trim());
          }
        }
      }
      if (!replaced) {
        entries.add(keyValue);
      }
      command.set(i + 1, String.join(",", entries));
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
    return matchingAppleTuningProfile(snapshot).isEmpty();
  }

  private static Optional<KataGoTuningProfile> matchingAppleTuningProfile(SetupSnapshot snapshot) {
    if (snapshot == null || Lizzie.config == null || Lizzie.config.uiConfig == null) {
      return Optional.empty();
    }
    KataGoTuningStore tuningStore = new KataGoTuningStore(Lizzie.config.uiConfig);
    if (!tuningStore.hasStoredProfile()) {
      return Optional.empty();
    }
    try {
      KataGoTuningFingerprint officialFingerprint =
          KataGoTuningFingerprint.create(
              snapshot.enginePath,
              snapshot.activeWeightPath,
              snapshot.gtpConfigPath,
              currentAppleHardwareProfile(),
              officialTuningCommandSemantics(snapshotSourceCommand(snapshot)));
      Optional<KataGoTuningProfile> official = tuningStore.loadMatching(officialFingerprint);
      if (official.isPresent()) {
        return official;
      }
      KataGoTuningFingerprint experimentalFingerprint =
          KataGoTuningFingerprint.create(
              snapshot.enginePath,
              snapshot.activeWeightPath,
              snapshot.gtpConfigPath,
              currentAppleHardwareProfile(),
              tuningCommandSemantics(snapshotSourceCommand(snapshot)));
      return tuningStore.loadMatching(experimentalFingerprint);
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
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
    if (!Config.isBundledKataGoExecutable(snapshot.enginePath)) {
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

  static String buildBenchmarkSignature(SetupSnapshot snapshot) {
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

  private static String formatRuntimeSearchDirs(List<Path> searchDirs) {
    if (searchDirs == null || searchDirs.isEmpty()) {
      return "";
    }
    List<String> displayPaths = new ArrayList<String>();
    for (Path dir : searchDirs) {
      if (dir == null) {
        continue;
      }
      displayPaths.add(dir.toAbsolutePath().normalize().toString());
      if (displayPaths.size() >= 2) {
        break;
      }
    }
    return String.join(" ; ", displayPaths);
  }

  private static boolean pathEquals(Path left, Path right) {
    if (left == null || right == null) {
      return false;
    }
    return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
  }

  private static boolean isTensorRtEngineActive(SetupSnapshot snapshot, TensorRtInstallSpec spec) {
    if (spec == null || spec.targetEnginePath == null) {
      return false;
    }
    if (pathEquals(snapshot == null ? null : snapshot.enginePath, spec.targetEnginePath)) {
      return true;
    }
    return pathEquals(resolveConfiguredActiveEnginePath(), spec.targetEnginePath);
  }

  private static Path resolveConfiguredActiveEnginePath() {
    if (Lizzie.config == null || Lizzie.config.uiConfig == null) {
      return null;
    }
    try {
      ArrayList<EngineData> engines = Utils.getEngineData();
      int defaultEngine = Lizzie.config.uiConfig.optInt("default-engine", -1);
      Path defaultEnginePath = resolveEngineDataPath(engines, defaultEngine);
      if (defaultEnginePath != null) {
        return defaultEnginePath;
      }
      if (engines != null) {
        for (EngineData engineData : engines) {
          if (engineData != null && engineData.isDefault) {
            Path enginePath = resolveEngineDataPath(engineData);
            if (enginePath != null) {
              return enginePath;
            }
          }
        }
      }
      String rememberedPath =
          Lizzie.config.uiConfig.optString("katago-auto-setup-engine-path", "").trim();
      if (!rememberedPath.isEmpty()) {
        return Paths.get(rememberedPath).toAbsolutePath().normalize();
      }
    } catch (Exception e) {
      return null;
    }
    return null;
  }

  private static Path resolveEngineDataPath(ArrayList<EngineData> engines, int index) {
    if (engines == null || index < 0 || index >= engines.size()) {
      return null;
    }
    return resolveEngineDataPath(engines.get(index));
  }

  private static Path resolveEngineDataPath(EngineData engineData) {
    if (engineData == null || engineData.commands == null || engineData.commands.trim().isEmpty()) {
      return null;
    }
    return resolveCommandExecutable(Utils.splitCommand(engineData.commands));
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

  static List<List<String>> requiredRuntimeDllGroups(Path enginePath, String backend) {
    if (isTensorRtBackend(backend)) {
      return REQUIRED_NVIDIA_TRT10_9_RUNTIME_DLL_GROUPS;
    }
    if (NVIDIA50_CUDA_BACKEND.equalsIgnoreCase(backend)) {
      return REQUIRED_NVIDIA_CUDA12_8_RUNTIME_DLL_GROUPS;
    }
    if (usesLegacyCudnn8Runtime(enginePath)) {
      return REQUIRED_NVIDIA_CUDA12_1_CUDNN8_RUNTIME_DLL_GROUPS;
    }
    return REQUIRED_NVIDIA_CUDA12_1_CUDNN9_RUNTIME_DLL_GROUPS;
  }

  private static boolean usesLegacyCudnn8Runtime(Path enginePath) {
    if (enginePath == null || enginePath.getParent() == null) {
      return false;
    }
    Path engineDir = enginePath.getParent();
    Path manifest = engineDir.resolve("lizzieyzy-next-nvidia-runtime-manifest.txt");
    if (Files.isRegularFile(manifest)) {
      try {
        String text = Files.readString(manifest, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (text.contains("profile: cuda12.1-cudnn9")) {
          return false;
        }
        if (text.contains("profile: cuda12.1-cudnn8")) {
          return true;
        }
      } catch (IOException e) {
      }
    }
    return Files.isRegularFile(engineDir.resolve("cudnn64_8.dll"))
        && !Files.isRegularFile(engineDir.resolve("cudnn64_9.dll"));
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
            status != null && isTensorRtBackend(resolveNvidiaBackend(status.enginePath))
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

  public static long tensorRtDownloadCacheBytes() {
    try {
      return directorySize(getNvidiaRuntimeDir().resolve(NVIDIA_RUNTIME_DOWNLOAD_CACHE_DIR));
    } catch (IOException e) {
      return 0L;
    }
  }

  public static long cleanupTensorRtDownloadCache() throws IOException {
    return deleteDownloadCacheContents(
        getNvidiaRuntimeDir().resolve(NVIDIA_RUNTIME_DOWNLOAD_CACHE_DIR), true);
  }

  static TensorRtInstallSpec buildTensorRtInstallSpec(SetupSnapshot snapshot) {
    Path runtimeRoot =
        Lizzie.config != null
            ? Lizzie.config.getRuntimeWorkDirectory().toPath()
            : Paths.get(System.getProperty("user.dir", "."))
                .toAbsolutePath()
                .normalize()
                .resolve("runtime");
    Path targetEnginePath = findExistingTensorRtEnginePath(snapshot, runtimeRoot);
    Path targetEngineDir;
    if (targetEnginePath != null && targetEnginePath.getParent() != null) {
      targetEnginePath = targetEnginePath.toAbsolutePath().normalize();
      targetEngineDir = targetEnginePath.getParent();
    } else {
      targetEngineDir = tensorRtEngineDir(runtimeRoot, NVIDIA_TRT_ENGINE_DIR);
      targetEnginePath = targetEngineDir.resolve("katago.exe");
    }
    long katagoSize =
        resolveLongProperty(TENSORRT_KATAGO_SIZE_PROPERTY, TENSORRT_KATAGO_SIZE_BYTES);
    long total =
        katagoSize
            + CUDA_12_8_CUDART_SIZE_BYTES
            + CUDA_12_8_CUBLAS_SIZE_BYTES
            + CUDA_12_8_NVJITLINK_SIZE_BYTES
            + CUDA_12_8_NVRTC_SIZE_BYTES
            + CUDNN_9_SIZE_BYTES
            + TENSORRT_RUNTIME_SIZE_BYTES;
    return new TensorRtInstallSpec(
        targetEngineDir,
        targetEnginePath,
        System.getProperty(TENSORRT_KATAGO_URL_PROPERTY, TENSORRT_KATAGO_URL),
        System.getProperty(TENSORRT_KATAGO_SHA256_PROPERTY, TENSORRT_KATAGO_SHA256),
        katagoSize,
        total,
        Boolean.getBoolean(TENSORRT_SKIP_RUNTIME_FOR_TESTS_PROPERTY) ? 0 : 6);
  }

  private static Path findExistingTensorRtEnginePath(SetupSnapshot snapshot, Path runtimeRoot) {
    LinkedHashSet<Path> candidates = new LinkedHashSet<Path>();
    if (snapshot != null
        && snapshot.enginePath != null
        && isTensorRtBackend(resolveNvidiaBackend(snapshot.enginePath))) {
      candidates.add(snapshot.enginePath);
    }
    addTensorRtEngineCandidates(candidates, runtimeRoot);
    if (snapshot != null) {
      addTensorRtEngineCandidates(candidates, snapshot.appRoot);
      addTensorRtEngineCandidates(candidates, snapshot.workingDir);
    }
    for (Path candidate : candidates) {
      if (candidate != null && Files.isRegularFile(candidate)) {
        return candidate.toAbsolutePath().normalize();
      }
    }
    return null;
  }

  private static void addTensorRtEngineCandidates(LinkedHashSet<Path> candidates, Path root) {
    if (candidates == null || root == null) {
      return;
    }
    candidates.add(tensorRtEngineDir(root, NVIDIA_TRT_ENGINE_DIR).resolve("katago.exe"));
    candidates.add(tensorRtEngineDir(root, NVIDIA50_TRT_ENGINE_DIR).resolve("katago.exe"));
  }

  private static Path tensorRtEngineDir(Path root, String engineDirName) {
    return root.toAbsolutePath()
        .normalize()
        .resolve("engines")
        .resolve("katago")
        .resolve(engineDirName);
  }

  private static boolean isTensorRtSourceProfileAllowed(SetupSnapshot snapshot) {
    if (snapshot == null || snapshot.enginePath == null) {
      return false;
    }
    String backend = resolveNvidiaBackend(snapshot.enginePath);
    return NVIDIA_BACKEND.equals(backend)
        || NVIDIA50_CUDA_BACKEND.equals(backend)
        || isTensorRtBackend(backend);
  }

  private static boolean isTensorRtBackend(String backend) {
    return NVIDIA_TRT_BACKEND.equalsIgnoreCase(backend)
        || NVIDIA50_TRT_BACKEND.equalsIgnoreCase(backend);
  }

  private static String tensorRtRecommendationText(NvidiaGpuDetector.DetectionResult gpuDetection) {
    if (gpuDetection != null && !Utils.isBlank(gpuDetection.detailText)) {
      return gpuDetection.detailText;
    }
    return resource(
        "AutoSetup.tensorRtGpuHint",
        "Recommended for RTX 20/30/40/50 NVIDIA GPUs. "
            + "GTX 10 series and older NVIDIA GPUs should use CUDA/OpenCL.");
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
          (NVIDIA_TRT_BACKEND + "\n").getBytes(StandardCharsets.UTF_8));
      Files.writeString(
          stagingDir.resolve(TENSORRT_ENGINE_MANIFEST_NAME),
          tensorRtEngineManifestText(),
          StandardCharsets.UTF_8);
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

  private static boolean isCurrentTensorRtEngine(Path enginePath) {
    if (enginePath == null || enginePath.getParent() == null || !Files.isRegularFile(enginePath)) {
      return false;
    }
    Path manifest = enginePath.getParent().resolve(TENSORRT_ENGINE_MANIFEST_NAME);
    if (!Files.isRegularFile(manifest)) {
      return false;
    }
    try {
      String text = Files.readString(manifest, StandardCharsets.UTF_8);
      return text.contains("KataGo release: " + TENSORRT_KATAGO_VERSION)
          && text.contains("Asset SHA-256: " + TENSORRT_KATAGO_SHA256);
    } catch (IOException e) {
      return false;
    }
  }

  private static String tensorRtEngineManifestText() {
    return "KataGo release: "
        + TENSORRT_KATAGO_VERSION
        + "\nAsset: "
        + TENSORRT_KATAGO_ASSET
        + "\nAsset SHA-256: "
        + TENSORRT_KATAGO_SHA256
        + "\n";
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
        readNestedPackageSpec(
            cudnnManifest,
            CUDNN_MANIFEST_URL,
            "cudnn",
            "windows-x86_64",
            "cuda12",
            "NVIDIA cuDNN"));
    return packages;
  }

  private static String chooseTensorRtNvidiaDownloadHost(
      DownloadSession session, ProgressListener listener, long totalBytes) throws IOException {
    if (Boolean.getBoolean(TENSORRT_SKIP_RUNTIME_FOR_TESTS_PROPERTY)) {
      return NVIDIA_DOWNLOAD_HOST_COM;
    }
    notifyProgress(
        listener,
        resource("AutoSetup.tensorRtTestingMirrors", "Testing NVIDIA download mirrors..."),
        0L,
        totalBytes);
    session.throwIfCancelled();
    NvidiaMirrorProbeResult cnResult =
        probeNvidiaDownloadMirror(NVIDIA_DOWNLOAD_HOST_CN, TENSORRT_RUNTIME_URL, session);
    session.throwIfCancelled();
    NvidiaMirrorProbeResult comResult =
        probeNvidiaDownloadMirror(NVIDIA_DOWNLOAD_HOST_COM, TENSORRT_RUNTIME_URL, session);
    session.throwIfCancelled();
    return selectNvidiaDownloadHostFromProbes(cnResult, comResult);
  }

  private static NvidiaMirrorProbeResult probeNvidiaDownloadMirror(
      String host, String url, DownloadSession session) throws IOException {
    String probeUrl = mirrorNvidiaDownloadUrl(url, host);
    HttpURLConnection conn = null;
    long startedAt = System.nanoTime();
    long bytesRead = 0L;
    try {
      conn = (HttpURLConnection) NetworkProxy.openConnection(URI.create(probeUrl).toURL());
      session.attach(conn);
      session.throwIfCancelled();
      conn.setInstanceFollowRedirects(true);
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(TENSORRT_MIRROR_PROBE_CONNECT_TIMEOUT_MILLIS);
      conn.setReadTimeout(TENSORRT_MIRROR_PROBE_READ_TIMEOUT_MILLIS);
      conn.setRequestProperty("User-Agent", USER_AGENT);
      conn.setRequestProperty("Range", "bytes=0-" + (TENSORRT_MIRROR_PROBE_BYTES - 1));
      int responseCode = conn.getResponseCode();
      if (responseCode < 200 || responseCode >= 400) {
        return failedNvidiaMirrorProbeResult(host, startedAt, "HTTP " + responseCode);
      }
      byte[] buffer = new byte[8192];
      try (InputStream input = conn.getInputStream()) {
        int read;
        while (bytesRead < TENSORRT_MIRROR_PROBE_BYTES && (read = input.read(buffer)) >= 0) {
          session.throwIfCancelled();
          int accepted = (int) Math.min(read, (long) TENSORRT_MIRROR_PROBE_BYTES - bytesRead);
          bytesRead += accepted;
          if (elapsedMillisSince(startedAt) >= TENSORRT_MIRROR_PROBE_MAX_MILLIS) {
            break;
          }
        }
      }
      return new NvidiaMirrorProbeResult(host, bytesRead, elapsedMillisSince(startedAt), null);
    } catch (IOException e) {
      if (session.isCancelled() || e instanceof DownloadCancelledException) {
        throw e;
      }
      if (bytesRead > 0L) {
        return new NvidiaMirrorProbeResult(host, bytesRead, elapsedMillisSince(startedAt), null);
      }
      return failedNvidiaMirrorProbeResult(host, startedAt, e.getClass().getSimpleName());
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
      session.clear();
    }
  }

  private static NvidiaMirrorProbeResult failedNvidiaMirrorProbeResult(
      String host, long startedAt, String errorMessage) {
    return new NvidiaMirrorProbeResult(host, 0L, elapsedMillisSince(startedAt), errorMessage);
  }

  private static long elapsedMillisSince(long startedAtNanos) {
    return Math.max(1L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
  }

  static String selectNvidiaDownloadHostFromProbes(
      NvidiaMirrorProbeResult cnResult, NvidiaMirrorProbeResult comResult) {
    boolean cnUsable = cnResult != null && cnResult.isUsable();
    boolean comUsable = comResult != null && comResult.isUsable();
    if (cnUsable && comUsable) {
      long cnWeightedSpeed = cnResult.bytesRead * comResult.elapsedMillis;
      long comWeightedSpeed = comResult.bytesRead * cnResult.elapsedMillis;
      return cnWeightedSpeed >= comWeightedSpeed
          ? NVIDIA_DOWNLOAD_HOST_CN
          : NVIDIA_DOWNLOAD_HOST_COM;
    }
    if (cnUsable) {
      return NVIDIA_DOWNLOAD_HOST_CN;
    }
    if (comUsable) {
      return NVIDIA_DOWNLOAD_HOST_COM;
    }
    return NVIDIA_DOWNLOAD_HOST_COM;
  }

  static String mirrorNvidiaDownloadUrl(String url, String host) {
    if (Utils.isBlank(url) || Utils.isBlank(host)) {
      return url;
    }
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      return url;
    }
    String currentHost = uri.getHost();
    if (!NVIDIA_DOWNLOAD_HOST_COM.equalsIgnoreCase(currentHost)
        && !NVIDIA_DOWNLOAD_HOST_CN.equalsIgnoreCase(currentHost)) {
      return url;
    }
    try {
      return new URI(
              uri.getScheme(),
              uri.getUserInfo(),
              host,
              uri.getPort(),
              uri.getPath(),
              uri.getQuery(),
              uri.getFragment())
          .toString();
    } catch (Exception e) {
      return url;
    }
  }

  private static List<RuntimePackageSpec> resolveTensorRtRuntimePackages(String nvidiaDownloadHost)
      throws IOException {
    if (Boolean.getBoolean(TENSORRT_SKIP_RUNTIME_FOR_TESTS_PROPERTY)) {
      return new ArrayList<RuntimePackageSpec>();
    }
    String cudaManifestUrl = mirrorNvidiaDownloadUrl(CUDA_12_8_MANIFEST_URL, nvidiaDownloadHost);
    String cudnnManifestUrl = mirrorNvidiaDownloadUrl(CUDNN_9_MANIFEST_URL, nvidiaDownloadHost);
    List<RuntimePackageSpec> packages = new ArrayList<RuntimePackageSpec>();
    JSONObject cudaManifest = new JSONObject(httpGet(cudaManifestUrl));
    JSONObject cudnnManifest = new JSONObject(httpGet(cudnnManifestUrl));
    packages.add(
        readPackageSpec(
            cudaManifest, cudaManifestUrl, "cuda_cudart", "windows-x86_64", "CUDA Runtime"));
    packages.add(
        readPackageSpec(
            cudaManifest, cudaManifestUrl, "libcublas", "windows-x86_64", "CUDA cuBLAS"));
    packages.add(
        readPackageSpec(
            cudaManifest, cudaManifestUrl, "libnvjitlink", "windows-x86_64", "CUDA nvJitLink"));
    packages.add(
        readPackageSpec(
            cudaManifest, cudaManifestUrl, "cuda_nvrtc", "windows-x86_64", "CUDA NVRTC"));
    packages.add(
        readNestedPackageSpec(
            cudnnManifest, cudnnManifestUrl, "cudnn", "windows-x86_64", "cuda12", "NVIDIA cuDNN"));
    packages.add(
        new RuntimePackageSpec(
            "NVIDIA TensorRT",
            "10.9.0.34",
            mirrorNvidiaDownloadUrl(TENSORRT_RUNTIME_URL, nvidiaDownloadHost),
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
    if (isRuntimePackageFileValid(spec, archivePath)) {
      notifyRuntimePackageComplete(spec, listener);
      return;
    }

    Files.createDirectories(archivePath.getParent());
    Path tempPath = archivePath.resolveSibling(archivePath.getFileName().toString() + ".part");
    if (promoteCompletedRuntimePackagePartial(spec, tempPath, archivePath, listener)) {
      return;
    }

    long resumeBytes = runtimePackagePartialSize(spec, tempPath);
    boolean retriedWithoutRange = false;

    while (true) {
      URLConnection conn = null;
      HttpURLConnection httpConn = null;
      try {
        session.throwIfCancelled();
        long existingBytes = resumeBytes;
        boolean appendToPartial = existingBytes > 0;
        conn = NetworkProxy.openConnection(URI.create(spec.url).toURL());
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/octet-stream,*/*");
        if (conn instanceof HttpURLConnection) {
          httpConn = (HttpURLConnection) conn;
          session.attach(httpConn);
          httpConn.setInstanceFollowRedirects(true);
          httpConn.setRequestMethod("GET");
          if (appendToPartial) {
            httpConn.setRequestProperty("Range", "bytes=" + existingBytes + "-");
          }
          int responseCode = httpConn.getResponseCode();
          if (appendToPartial) {
            if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
              // Server accepted the Range request. Keep the existing .part bytes.
            } else if (responseCode == HttpURLConnection.HTTP_OK) {
              Files.deleteIfExists(tempPath);
              existingBytes = 0L;
              appendToPartial = false;
            } else if (responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
              if (promoteCompletedRuntimePackagePartial(spec, tempPath, archivePath, listener)) {
                return;
              }
              Files.deleteIfExists(tempPath);
              if (!retriedWithoutRange) {
                resumeBytes = 0L;
                retriedWithoutRange = true;
                continue;
              }
              throw new IOException("HTTP " + responseCode + " from " + spec.url);
            } else if (responseCode < 200 || responseCode >= 400) {
              throw new IOException("HTTP " + responseCode + " from " + spec.url);
            } else {
              Files.deleteIfExists(tempPath);
              existingBytes = 0L;
              appendToPartial = false;
            }
          } else if (responseCode < 200 || responseCode >= 400) {
            throw new IOException("HTTP " + responseCode + " from " + spec.url);
          }
        } else if (appendToPartial) {
          Files.deleteIfExists(tempPath);
          existingBytes = 0L;
          appendToPartial = false;
        }

        long totalBytes =
            resolveRuntimePackageTotalBytes(
                spec, existingBytes, conn.getContentLengthLong(), appendToPartial);
        String progressText = runtimePackageProgressText(spec, existingBytes);
        if (listener != null && existingBytes > 0) {
          listener.onProgress(progressText, existingBytes, totalBytes);
        }
        StandardOpenOption[] openOptions =
            appendToPartial
                ? new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new StandardOpenOption[] {
                  StandardOpenOption.CREATE,
                  StandardOpenOption.TRUNCATE_EXISTING,
                  StandardOpenOption.WRITE
                };
        try (InputStream raw = conn.getInputStream();
            BufferedInputStream input = new BufferedInputStream(raw);
            OutputStream output = Files.newOutputStream(tempPath, openOptions)) {
          byte[] buffer = new byte[256 * 1024];
          long downloaded = existingBytes;
          int read;
          long lastReport = 0L;
          while ((read = input.read(buffer)) >= 0) {
            session.throwIfCancelled();
            output.write(buffer, 0, read);
            downloaded += read;
            long now = System.currentTimeMillis();
            if (listener != null && (now - lastReport > 120 || downloaded == totalBytes)) {
              listener.onProgress(progressText, downloaded, totalBytes);
              lastReport = now;
            }
          }
        }

        session.throwIfCancelled();
        validateRuntimePackageDownload(spec, tempPath);
        moveRuntimePackageIntoCache(tempPath, archivePath);
        notifyRuntimePackageComplete(spec, listener);
        return;
      } catch (IOException e) {
        if (e instanceof CorruptRuntimePackageDownloadException) {
          Files.deleteIfExists(tempPath);
        }
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
  }

  private static boolean isRuntimePackageFileValid(RuntimePackageSpec spec, Path path)
      throws IOException {
    if (!Files.isRegularFile(path)) {
      return false;
    }
    long size = Files.size(path);
    if (spec.sizeBytes > 0 && size != spec.sizeBytes) {
      return false;
    }
    return Utils.isBlank(spec.sha256) || spec.sha256.equalsIgnoreCase(sha256(path));
  }

  private static boolean promoteCompletedRuntimePackagePartial(
      RuntimePackageSpec spec, Path tempPath, Path archivePath, ProgressListener listener)
      throws IOException {
    if (!Files.isRegularFile(tempPath)) {
      return false;
    }
    long size = Files.size(tempPath);
    if (size <= 0) {
      Files.deleteIfExists(tempPath);
      return false;
    }
    if (hasRuntimePackageIntegritySpec(spec) && isRuntimePackageFileValid(spec, tempPath)) {
      moveRuntimePackageIntoCache(tempPath, archivePath);
      notifyRuntimePackageComplete(spec, listener);
      return true;
    }
    if (spec.sizeBytes > 0 && size >= spec.sizeBytes) {
      Files.deleteIfExists(tempPath);
    }
    return false;
  }

  private static boolean hasRuntimePackageIntegritySpec(RuntimePackageSpec spec) {
    return spec.sizeBytes > 0 || !Utils.isBlank(spec.sha256);
  }

  private static long runtimePackagePartialSize(RuntimePackageSpec spec, Path tempPath)
      throws IOException {
    if (!Files.isRegularFile(tempPath)) {
      return 0L;
    }
    long size = Files.size(tempPath);
    if (size <= 0 || (spec.sizeBytes > 0 && size >= spec.sizeBytes)) {
      Files.deleteIfExists(tempPath);
      return 0L;
    }
    return size;
  }

  private static long resolveRuntimePackageTotalBytes(
      RuntimePackageSpec spec, long existingBytes, long responseBytes, boolean appendToPartial) {
    if (spec.sizeBytes > 0) {
      return spec.sizeBytes;
    }
    if (responseBytes > 0) {
      return appendToPartial ? existingBytes + responseBytes : responseBytes;
    }
    return -1L;
  }

  private static String runtimePackageProgressText(RuntimePackageSpec spec, long existingBytes) {
    if (existingBytes <= 0) {
      return spec.displayName;
    }
    return String.format(resource("AutoSetup.downloadResuming", "%s (resuming)"), spec.displayName);
  }

  private static void validateRuntimePackageDownload(RuntimePackageSpec spec, Path tempPath)
      throws IOException {
    long actualSize = Files.size(tempPath);
    if (spec.sizeBytes > 0) {
      if (actualSize < spec.sizeBytes) {
        throw new IncompleteRuntimePackageDownloadException(
            "Incomplete download for "
                + spec.displayName
                + ": "
                + actualSize
                + " of "
                + spec.sizeBytes
                + " bytes");
      }
      if (actualSize > spec.sizeBytes) {
        throw new CorruptRuntimePackageDownloadException(
            "Size mismatch for " + spec.displayName + ": " + actualSize + " bytes");
      }
    }
    if (!Utils.isBlank(spec.sha256) && !spec.sha256.equalsIgnoreCase(sha256(tempPath))) {
      throw new CorruptRuntimePackageDownloadException("SHA-256 mismatch for " + spec.displayName);
    }
  }

  private static void moveRuntimePackageIntoCache(Path tempPath, Path archivePath)
      throws IOException {
    try {
      Files.move(
          tempPath,
          archivePath,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tempPath, archivePath, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void notifyRuntimePackageComplete(
      RuntimePackageSpec spec, ProgressListener listener) {
    if (listener != null) {
      long totalBytes = Math.max(0L, spec.sizeBytes);
      listener.onProgress(spec.displayName, totalBytes, totalBytes);
    }
  }

  private static void cleanupCompletedTensorRtDownloadArchives(
      Path cacheDir, List<Path> completedArchives) {
    if (cacheDir == null || completedArchives == null || completedArchives.isEmpty()) {
      return;
    }
    Path normalizedCacheDir = cacheDir.toAbsolutePath().normalize();
    for (Path archive : completedArchives) {
      if (archive == null) {
        continue;
      }
      try {
        Path normalizedArchive = archive.toAbsolutePath().normalize();
        if (normalizedArchive.startsWith(normalizedCacheDir) && Files.isRegularFile(archive)) {
          Files.deleteIfExists(archive);
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    try {
      Files.deleteIfExists(cacheDir);
    } catch (IOException ignored) {
    }
  }

  private static long directorySize(Path directory) throws IOException {
    if (directory == null || !Files.exists(directory)) {
      return 0L;
    }
    AtomicLong total = new AtomicLong();
    try (Stream<Path> stream = Files.walk(directory)) {
      stream
          .filter(Files::isRegularFile)
          .forEach(
              path -> {
                try {
                  total.addAndGet(Files.size(path));
                } catch (IOException ignored) {
                }
              });
    }
    return total.get();
  }

  private static long deleteDownloadCacheContents(Path cacheDir, boolean includePartialFiles)
      throws IOException {
    if (cacheDir == null || !Files.exists(cacheDir)) {
      return 0L;
    }
    List<Path> paths = new ArrayList<Path>();
    try (Stream<Path> stream = Files.walk(cacheDir)) {
      stream.forEach(paths::add);
    }
    paths.sort(Comparator.reverseOrder());
    long freedBytes = 0L;
    for (Path path : paths) {
      if (path.equals(cacheDir)) {
        continue;
      }
      if (Files.isDirectory(path)) {
        Files.deleteIfExists(path);
        continue;
      }
      String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
      if (!includePartialFiles && fileName.endsWith(".part")) {
        continue;
      }
      long size = Files.isRegularFile(path) ? Files.size(path) : 0L;
      Files.deleteIfExists(path);
      freedBytes += size;
    }
    try {
      Files.deleteIfExists(cacheDir);
    } catch (IOException ignored) {
    }
    return freedBytes;
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
      conn = (HttpURLConnection) NetworkProxy.openConnection(URI.create(url).toURL());
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
    return Math.max(
        last, Math.max(BENCHMARK_FINALIZING_PROGRESS, smoothSilentBenchmarkProgress(last, silent)));
  }

  static int estimatePrePositionBenchmarkPermille(long elapsedMillis, int lastProgressPermille) {
    int synthetic = estimateSyntheticBenchmarkPermille(elapsedMillis, 0L, lastProgressPermille);
    return Math.max(
        0,
        Math.min(BENCHMARK_PRE_POSITION_PROGRESS_CAP, Math.max(lastProgressPermille, synthetic)));
  }

  static int smoothSilentBenchmarkProgress(int lastProgressPermille, long sinceLastProgressMillis) {
    int last = Math.max(0, Math.min(1000, lastProgressPermille));
    if (last < 820 || sinceLastProgressMillis < 2500L) {
      return last;
    }
    long extraSeconds = Math.max(0L, (sinceLastProgressMillis - 2500L) / 1000L);
    int ceiling;
    long stepPermille;
    if (last >= 985) {
      ceiling = BENCHMARK_PROGRESS_VISIBLE_CAP;
      stepPermille = 2L;
    } else if (last >= 970) {
      ceiling = 985;
      stepPermille = 4L;
    } else {
      ceiling = 970;
      stepPermille = 10L;
    }
    int smoothed = last + (int) Math.min(ceiling - last, extraSeconds * stepPermille);
    return Math.max(last, Math.min(ceiling, smoothed));
  }

  private static String formatBenchmarkHeartbeatStatus(long elapsedMillis, int displayPermille) {
    long elapsed = Math.max(0L, elapsedMillis);
    if (elapsed <= 8000L) {
      return resource("AutoSetup.benchmarkLoadingModel", "Loading KataGo model...")
          + "  "
          + formatDuration(elapsed);
    }
    if (displayPermille >= BENCHMARK_FINALIZING_PROGRESS) {
      return resource("AutoSetup.benchmarkFinalizing", "KataGo is summarizing benchmark results...")
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
          "AutoSetup.benchmarkMissing",
          "No speed boost result yet. Run Smart reading speed boost once.");
    }
    StringBuilder builder = new StringBuilder();
    builder
        .append(resource("AutoSetup.benchmarkRecommended", "Recommended threads"))
        .append(" ")
        .append(result.recommendedThreads);
    if (result.topologyLabel != null && !result.topologyLabel.isEmpty()) {
      builder.append("  |  Metal ").append(result.topologyLabel);
    }
    if (result.maxBatchSize > 0) {
      builder.append("  |  batch ").append(result.maxBatchSize);
    }
    if (result.visitsPerSecond > 0.0) {
      builder
          .append("  |  ")
          .append(String.format(Locale.ROOT, "%.1f visits/s", result.visitsPerSecond));
    }
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
