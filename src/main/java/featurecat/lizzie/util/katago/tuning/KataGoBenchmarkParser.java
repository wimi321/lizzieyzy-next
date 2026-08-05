package featurecat.lizzie.util.katago.tuning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser for the stable human-readable output emitted by KataGo 1.17.x benchmark. */
public final class KataGoBenchmarkParser {
  private static final String NUMBER =
      "(?:[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?|NaN|Infinity)";
  private static final Pattern BACKEND_PATTERN =
      Pattern.compile("You are currently using the (.+?) version of KataGo\\.");
  private static final Pattern CURRENT_THREADS_PATTERN =
      Pattern.compile("Your GTP config is currently set to use numSearchThreads\\s*=\\s*(\\d+)");
  private static final Pattern RECOMMENDED_THREADS_PATTERN =
      Pattern.compile("^numSearchThreads\\s*=\\s*(\\d+):.*\\(recommended\\)\\s*$");
  private static final Pattern DETAILED_METRICS_PATTERN =
      Pattern.compile(
          "^numSearchThreads\\s*=\\s*(\\d+):\\s*(\\d+)\\s*/\\s*(\\d+)\\s+positions,"
              + "\\s*visits/s\\s*=\\s*("
              + NUMBER
              + ")\\s+nnEvals/s\\s*=\\s*("
              + NUMBER
              + ")\\s+nnBatches/s\\s*=\\s*("
              + NUMBER
              + ")\\s+avgBatchSize\\s*=\\s*("
              + NUMBER
              + ")(?:\\s+.*)?$");

  private KataGoBenchmarkParser() {}

  /**
   * Parses one process output. When an explicit single thread count was requested, pass it in so a
   * successful fixed-thread benchmark need not contain KataGo's final {@code (recommended)} marker.
   */
  public static KataGoBenchmarkObservation parse(String output, int explicitThreads) {
    if (explicitThreads < 0 || explicitThreads > 4096) {
      throw new IllegalArgumentException("explicitThreads must be between 0 and 4096");
    }

    String backend = "";
    int currentThreads = 0;
    int recommendedThreads = 0;
    boolean mpsGraphInitialized = false;
    boolean coreMlInitialized = false;
    boolean failureDetected = false;
    Map<Integer, KataGoBenchmarkObservation.ThreadMetrics> metricsByThread =
        new LinkedHashMap<Integer, KataGoBenchmarkObservation.ThreadMetrics>();

    String normalizedOutput = output == null ? "" : output;
    String[] lines = normalizedOutput.split("\\r\\n|[\\r\\n]", -1);
    for (String rawLine : lines) {
      String line = rawLine == null ? "" : rawLine.trim();
      if (line.isEmpty()) {
        continue;
      }

      Matcher backendMatcher = BACKEND_PATTERN.matcher(line);
      if (backendMatcher.find()) {
        backend = backendMatcher.group(1).trim();
      }

      Matcher currentMatcher = CURRENT_THREADS_PATTERN.matcher(line);
      if (currentMatcher.find()) {
        currentThreads = parseInteger(currentMatcher.group(1));
      }

      Matcher recommendedMatcher = RECOMMENDED_THREADS_PATTERN.matcher(line);
      if (recommendedMatcher.matches()) {
        recommendedThreads = parseInteger(recommendedMatcher.group(1));
      }

      Matcher metricsMatcher = DETAILED_METRICS_PATTERN.matcher(line);
      if (metricsMatcher.matches()) {
        KataGoBenchmarkObservation.ThreadMetrics metrics = parseMetrics(metricsMatcher);
        if (metrics != null) {
          metricsByThread.put(metrics.numSearchThreads(), metrics);
        }
      }

      String lower = line.toLowerCase(Locale.ROOT);
      if (lower.contains("gpu mode - using mpsgraph")
          || lower.contains("initialized mpsgraph gpu-only mode")) {
        mpsGraphInitialized = true;
      }
      if (lower.contains("mux ane mode - using coreml")) {
        coreMlInitialized = true;
      }
      if (isFailureLine(lower)) {
        failureDetected = true;
      }
    }

    if (recommendedThreads <= 0 && explicitThreads > 0) {
      recommendedThreads = explicitThreads;
    }

    return new KataGoBenchmarkObservation(
        backend,
        currentThreads,
        recommendedThreads,
        new ArrayList<KataGoBenchmarkObservation.ThreadMetrics>(metricsByThread.values()),
        mpsGraphInitialized,
        coreMlInitialized,
        failureDetected);
  }

  private static KataGoBenchmarkObservation.ThreadMetrics parseMetrics(Matcher matcher) {
    try {
      return new KataGoBenchmarkObservation.ThreadMetrics(
          Integer.parseInt(matcher.group(1)),
          Integer.parseInt(matcher.group(2)),
          Integer.parseInt(matcher.group(3)),
          Double.parseDouble(matcher.group(4)),
          Double.parseDouble(matcher.group(5)),
          Double.parseDouble(matcher.group(6)),
          Double.parseDouble(matcher.group(7)));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static int parseInteger(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static boolean isFailureLine(String lowerLine) {
    return lowerLine.startsWith("error:")
        || lowerLine.startsWith("fatal:")
        || lowerLine.contains("fatal error")
        || lowerLine.contains("segmentation fault")
        || lowerLine.contains("sigsegv")
        || lowerLine.contains("core dumped")
        || lowerLine.contains("uncaught exception")
        || lowerLine.contains("terminate called")
        || lowerLine.contains("failed to load model")
        || lowerLine.contains("invalid metaldevicetousethread");
  }
}
