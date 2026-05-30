package featurecat.lizzie.util;

import featurecat.lizzie.Lizzie;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects local NVIDIA GPUs and recommends the safest KataGo TensorRT setup path. */
public final class NvidiaGpuDetector {
  private static final int DETECTION_TIMEOUT_SECONDS = 8;
  private static final Pattern RTX_GENERATION_PATTERN =
      Pattern.compile("\\bRTX\\s*(?:PRO\\s*)?(\\d{2})\\d{2}", Pattern.CASE_INSENSITIVE);
  private static final Pattern GTX_GENERATION_PATTERN =
      Pattern.compile("\\bGTX\\s*(\\d{2})\\d{2}", Pattern.CASE_INSENSITIVE);

  private NvidiaGpuDetector() {}

  public enum TensorRtRecommendation {
    RECOMMENDED,
    ALLOWED,
    NOT_RECOMMENDED,
    UNKNOWN
  }

  public static final class GpuInfo {
    public final String name;
    public final int computeMajor;
    public final int computeMinor;
    public final String driverVersion;
    public final long memoryMiB;
    public final String source;

    private GpuInfo(
        String name,
        int computeMajor,
        int computeMinor,
        String driverVersion,
        long memoryMiB,
        String source) {
      this.name = name == null ? "" : name.trim();
      this.computeMajor = computeMajor;
      this.computeMinor = computeMinor;
      this.driverVersion = driverVersion == null ? "" : driverVersion.trim();
      this.memoryMiB = memoryMiB;
      this.source = source == null ? "" : source.trim();
    }

    public boolean hasComputeCapability() {
      return computeMajor > 0;
    }

    public String computeCapabilityText() {
      return hasComputeCapability() ? computeMajor + "." + computeMinor : "?";
    }

    private int computeRank() {
      return computeMajor * 10 + computeMinor;
    }
  }

  public static final class DetectionResult {
    public final boolean detected;
    public final List<GpuInfo> gpus;
    public final GpuInfo bestGpu;
    public final TensorRtRecommendation recommendation;
    public final String summaryText;
    public final String detailText;

    private DetectionResult(
        boolean detected,
        List<GpuInfo> gpus,
        GpuInfo bestGpu,
        TensorRtRecommendation recommendation,
        String summaryText,
        String detailText) {
      this.detected = detected;
      this.gpus =
          gpus == null
              ? Collections.<GpuInfo>emptyList()
              : Collections.unmodifiableList(new ArrayList<GpuInfo>(gpus));
      this.bestGpu = bestGpu;
      this.recommendation =
          recommendation == null ? TensorRtRecommendation.UNKNOWN : recommendation;
      this.summaryText = summaryText == null ? "" : summaryText.trim();
      this.detailText = detailText == null ? "" : detailText.trim();
    }
  }

  public static DetectionResult detectBestGpu() {
    List<GpuInfo> gpus = detectViaNvidiaSmi();
    if (gpus.isEmpty()) {
      return new DetectionResult(
          false,
          gpus,
          null,
          TensorRtRecommendation.UNKNOWN,
          resource(
              "AutoSetup.gpuDetectNotFound",
              "No NVIDIA GPU detected, or NVIDIA driver information is unavailable."),
          resource(
              "AutoSetup.gpuUnknownTensorRt",
              "Could not confirm Compute Capability. You can try manually, but CUDA/OpenCL is safer if startup fails."));
    }

    GpuInfo best = chooseBestGpu(gpus);
    TensorRtRecommendation recommendation = recommend(best);
    String recommendationText = recommendationText(recommendation, best);
    String summary = buildSummary(best, recommendationText);
    return new DetectionResult(true, gpus, best, recommendation, summary, recommendationText);
  }

  private static List<GpuInfo> detectViaNvidiaSmi() {
    List<GpuInfo> withCompute =
        queryNvidiaSmi(
            Arrays.asList("name", "compute_cap", "driver_version", "memory.total"), true);
    if (!withCompute.isEmpty()) {
      return withCompute;
    }
    return queryNvidiaSmi(Arrays.asList("name", "driver_version", "memory.total"), false);
  }

  private static List<GpuInfo> queryNvidiaSmi(List<String> fields, boolean includesCompute) {
    List<String> args =
        Arrays.asList("--query-gpu=" + join(fields, ","), "--format=csv,noheader,nounits");
    for (List<String> command : nvidiaSmiCommandCandidates(args)) {
      CommandResult result = runCommand(command);
      if (!result.success || result.output.trim().isEmpty()) {
        continue;
      }
      List<GpuInfo> parsed = parseGpuRows(result.output, includesCompute);
      if (!parsed.isEmpty()) {
        return parsed;
      }
    }
    return Collections.emptyList();
  }

  private static List<List<String>> nvidiaSmiCommandCandidates(List<String> args) {
    List<List<String>> commands = new ArrayList<List<String>>();
    List<String> defaultCommand = new ArrayList<String>();
    defaultCommand.add("nvidia-smi");
    defaultCommand.addAll(args);
    commands.add(defaultCommand);

    String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (osName.contains("win")) {
      String systemRoot = System.getenv("SystemRoot");
      if (systemRoot != null && !systemRoot.trim().isEmpty()) {
        Path system32 = Paths.get(systemRoot).resolve("System32").resolve("nvidia-smi.exe");
        if (Files.isRegularFile(system32)) {
          List<String> system32Command = new ArrayList<String>();
          system32Command.add(system32.toString());
          system32Command.addAll(args);
          commands.add(system32Command);
        }
      }
    }
    return commands;
  }

  private static CommandResult runCommand(List<String> command) {
    Process process = null;
    try {
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.redirectErrorStream(true);
      process = builder.start();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      Thread reader = startStreamReader(process.getInputStream(), output);
      boolean finished = process.waitFor(DETECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        joinQuietly(reader, 500L);
        return new CommandResult(false, output.toString(StandardCharsets.UTF_8.name()));
      }
      joinQuietly(reader, 1000L);
      return new CommandResult(
          process.exitValue() == 0, output.toString(StandardCharsets.UTF_8.name()));
    } catch (Exception e) {
      if (process != null) {
        process.destroyForcibly();
      }
      return new CommandResult(false, "");
    }
  }

  private static Thread startStreamReader(InputStream inputStream, ByteArrayOutputStream output) {
    Thread reader =
        new Thread(
            () -> {
              byte[] buffer = new byte[4096];
              int read;
              try {
                while ((read = inputStream.read(buffer)) >= 0) {
                  output.write(buffer, 0, read);
                }
              } catch (IOException e) {
              }
            },
            "nvidia-gpu-detector-output");
    reader.setDaemon(true);
    reader.start();
    return reader;
  }

  private static void joinQuietly(Thread thread, long millis) {
    if (thread == null) {
      return;
    }
    try {
      thread.join(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static List<GpuInfo> parseGpuRows(String output, boolean includesCompute) {
    List<GpuInfo> gpus = new ArrayList<GpuInfo>();
    String[] lines = output.split("\\R");
    for (String line : lines) {
      if (line == null || line.trim().isEmpty()) {
        continue;
      }
      List<String> parts = splitCsvLine(line);
      if (includesCompute && parts.size() < 4) {
        continue;
      }
      if (!includesCompute && parts.size() < 3) {
        continue;
      }
      String name = clean(parts.get(0));
      String computeText = includesCompute ? clean(parts.get(1)) : "";
      String driver = clean(parts.get(includesCompute ? 2 : 1));
      String memory = clean(parts.get(includesCompute ? 3 : 2));
      int[] compute = parseComputeCapability(computeText);
      String source = "nvidia-smi";
      if (compute[0] <= 0) {
        compute = inferComputeCapabilityFromName(name);
        if (compute[0] > 0) {
          source = "model-name-fallback";
        }
      }
      gpus.add(new GpuInfo(name, compute[0], compute[1], driver, parseMemoryMiB(memory), source));
    }
    return gpus;
  }

  private static List<String> splitCsvLine(String line) {
    List<String> parts = new ArrayList<String>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '"') {
        quoted = !quoted;
        continue;
      }
      if (ch == ',' && !quoted) {
        parts.add(current.toString());
        current.setLength(0);
      } else {
        current.append(ch);
      }
    }
    parts.add(current.toString());
    return parts;
  }

  private static int[] parseComputeCapability(String value) {
    String normalized = clean(value).replace("Compute Capability", "").trim();
    if (normalized.isEmpty() || "N/A".equalsIgnoreCase(normalized)) {
      return new int[] {0, 0};
    }
    Matcher matcher = Pattern.compile("(\\d+)\\s*[.]\\s*(\\d+)").matcher(normalized);
    if (matcher.find()) {
      return new int[] {parseInt(matcher.group(1)), parseInt(matcher.group(2))};
    }
    return new int[] {0, 0};
  }

  private static int[] inferComputeCapabilityFromName(String name) {
    String normalized = name == null ? "" : name.toUpperCase(Locale.ROOT);
    if (normalized.contains("BLACKWELL") || normalized.contains("RTX PRO")) {
      return new int[] {12, 0};
    }
    if (normalized.contains(" ADA") || normalized.endsWith("ADA")) {
      return new int[] {8, 9};
    }
    Matcher rtxMatcher = RTX_GENERATION_PATTERN.matcher(normalized);
    if (rtxMatcher.find()) {
      int generation = parseInt(rtxMatcher.group(1));
      if (generation >= 50) {
        return new int[] {12, 0};
      }
      if (generation >= 40) {
        return new int[] {8, 9};
      }
      if (generation >= 30) {
        return new int[] {8, 6};
      }
      if (generation >= 20) {
        return new int[] {7, 5};
      }
    }
    Matcher gtxMatcher = GTX_GENERATION_PATTERN.matcher(normalized);
    if (gtxMatcher.find()) {
      int generation = parseInt(gtxMatcher.group(1));
      if (generation == 16) {
        return new int[] {7, 5};
      }
      if (generation == 10) {
        return new int[] {6, 1};
      }
      if (generation == 9) {
        return new int[] {5, 2};
      }
    }
    if (normalized.contains("TITAN RTX") || normalized.contains("QUADRO RTX")) {
      return new int[] {7, 5};
    }
    if (normalized.contains("RTX A")) {
      return new int[] {8, 6};
    }
    if (normalized.matches(".*\\bA100\\b.*")) {
      return new int[] {8, 0};
    }
    if (normalized.matches(".*\\b(A40|A10|A16|A2)\\b.*")) {
      return new int[] {8, 6};
    }
    if (normalized.matches(".*\\b(L4|L40|L40S)\\b.*")) {
      return new int[] {8, 9};
    }
    if (normalized.matches(".*\\bT4\\b.*")) {
      return new int[] {7, 5};
    }
    return new int[] {0, 0};
  }

  private static GpuInfo chooseBestGpu(List<GpuInfo> gpus) {
    GpuInfo best = gpus.get(0);
    for (GpuInfo candidate : gpus) {
      if (candidate.computeRank() > best.computeRank()) {
        best = candidate;
      } else if (candidate.computeRank() == best.computeRank()
          && candidate.memoryMiB > best.memoryMiB) {
        best = candidate;
      }
    }
    return best;
  }

  private static TensorRtRecommendation recommend(GpuInfo gpu) {
    if (gpu == null || !gpu.hasComputeCapability()) {
      return TensorRtRecommendation.UNKNOWN;
    }
    int rank = gpu.computeRank();
    if (rank < 75) {
      return TensorRtRecommendation.NOT_RECOMMENDED;
    }
    if (rank == 75 && isGtx16Series(gpu.name)) {
      return TensorRtRecommendation.ALLOWED;
    }
    return TensorRtRecommendation.RECOMMENDED;
  }

  private static boolean isGtx16Series(String name) {
    String normalized = name == null ? "" : name.toUpperCase(Locale.ROOT);
    return normalized.contains("GTX 16");
  }

  private static String buildSummary(GpuInfo gpu, String recommendationText) {
    if (gpu == null) {
      return recommendationText;
    }
    StringBuilder builder = new StringBuilder();
    builder.append(gpu.name.isEmpty() ? "NVIDIA GPU" : gpu.name);
    builder.append("  |  SM ").append(gpu.computeCapabilityText());
    if (!gpu.driverVersion.isEmpty()) {
      builder.append("  |  Driver ").append(gpu.driverVersion);
    }
    if (gpu.memoryMiB > 0) {
      builder.append("  |  ").append(gpu.memoryMiB).append(" MiB");
    }
    if (!gpu.source.isEmpty()) {
      builder.append("  |  ").append(gpu.source);
    }
    if (!recommendationText.isEmpty()) {
      builder.append("  |  ").append(recommendationText);
    }
    return builder.toString();
  }

  private static String recommendationText(TensorRtRecommendation recommendation, GpuInfo gpu) {
    if (recommendation == TensorRtRecommendation.RECOMMENDED) {
      if (gpu != null && gpu.computeMajor >= 12) {
        return resource(
            "AutoSetup.gpuRecommendTensorRtRtx50",
            "Recommended: TensorRT RTX 50 / Blackwell acceleration. First launch may build caches.");
      }
      return resource("AutoSetup.gpuRecommendTensorRt", "Recommended: TensorRT for this GPU.");
    }
    if (recommendation == TensorRtRecommendation.ALLOWED) {
      return resource(
          "AutoSetup.gpuAllowTensorRt",
          "Allowed: this GPU meets the TensorRT 10.x SM 7.5+ floor, but RTX cards are preferred for best speed.");
    }
    if (recommendation == TensorRtRecommendation.NOT_RECOMMENDED) {
      return resource(
          "AutoSetup.gpuNotRecommendTensorRt",
          "Not recommended: TensorRT 10.x requires SM 7.5+. Use CUDA/OpenCL for this GPU.");
    }
    return resource(
        "AutoSetup.gpuUnknownTensorRt",
        "Could not confirm Compute Capability. You can try manually, but CUDA/OpenCL is safer if startup fails.");
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }

  private static int parseInt(String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (Exception e) {
      return 0;
    }
  }

  private static long parseMemoryMiB(String value) {
    String normalized = clean(value).replace("MiB", "").replace("MB", "").trim();
    try {
      return Long.parseLong(normalized);
    } catch (Exception e) {
      return 0L;
    }
  }

  private static String join(List<String> values, String delimiter) {
    StringBuilder builder = new StringBuilder();
    for (String value : values) {
      if (builder.length() > 0) {
        builder.append(delimiter);
      }
      builder.append(value);
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

  private static final class CommandResult {
    final boolean success;
    final String output;

    private CommandResult(boolean success, String output) {
      this.success = success;
      this.output = output == null ? "" : output;
    }
  }
}
