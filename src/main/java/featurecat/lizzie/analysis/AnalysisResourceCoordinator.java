package featurecat.lizzie.analysis;

import featurecat.lizzie.logging.EngineObservation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Coordinates foreground and auxiliary KataGo processes and emits opt-in diagnostics. */
public final class AnalysisResourceCoordinator {
  public enum Purpose {
    MAIN_BOARD,
    AUTO_QUICK_ANALYSIS,
    PRELOADED_QUICK_ANALYSIS,
    USER_QUICK_ANALYSIS,
    WHOLE_GAME_ANALYSIS,
    OTHER
  }

  public enum ForegroundDecision {
    NONE,
    RELEASE_IDLE_SECONDARY,
    PREEMPT_AUTOMATIC_SECONDARY,
    KEEP_USER_TASK,
    SHARED_ENGINE
  }

  private static final Pattern SENSITIVE_ARGUMENT =
      Pattern.compile(
          "(?i)(password|passwd|token|secret|authorization|session|cookie)(\\s*[=:]\\s*|\\s+)([^\\s]+)");
  private static final Pattern URL_SECRET =
      Pattern.compile(
          "(?i)([?&](?:token|key|secret|password|session)=)[^&\\s]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern SET_PARAM =
      Pattern.compile("^\\s*kata-set-param\\s+([^\\s]+)\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern FILE_OPTION =
      Pattern.compile("(?i)(?<!\\S)(-{1,2}(?:model|config)\\s+)(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s]+)");
  private static final Pattern HOME_DATA_DIR = Pattern.compile("(?i)(homeDataDir=)([^,\\s]+)");
  private static final Pattern LEADING_EXECUTABLE =
      Pattern.compile("^(\\s*)(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s]+)");
  private static final Map<Object, Sample> FOREGROUND_SAMPLES = new java.util.WeakHashMap<>();
  private static final Map<Object, Long> REGISTERED_PROCESSES = new java.util.WeakHashMap<>();
  private static final Set<Process> ACTIVE_LOCAL_COMPUTE_PROCESSES =
      Collections.newSetFromMap(new IdentityHashMap<Process, Boolean>());

  private AnalysisResourceCoordinator() {}

  public static ForegroundDecision decideForegroundStart(
      boolean sharedEngine,
      boolean localDedicatedProcess,
      boolean analysisInProgress,
      boolean automaticBackgroundTask) {
    if (sharedEngine) {
      return ForegroundDecision.SHARED_ENGINE;
    }
    if (!localDedicatedProcess) {
      return ForegroundDecision.NONE;
    }
    if (!analysisInProgress) {
      return ForegroundDecision.RELEASE_IDLE_SECONDARY;
    }
    if (automaticBackgroundTask) {
      return ForegroundDecision.PREEMPT_AUTOMATIC_SECONDARY;
    }
    return ForegroundDecision.KEEP_USER_TASK;
  }

  public static void processStarted(
      Object owner, Purpose purpose, String command, Process process) {
    if (process != null) {
      synchronized (ACTIVE_LOCAL_COMPUTE_PROCESSES) {
        ACTIVE_LOCAL_COMPUTE_PROCESSES.add(process);
      }
    }
    String purposeName = normalizedPurpose(purpose).name();
    if (process == null) {
      EngineObservation.ensureStarted(owner, purposeName);
      if (EngineObservation.engineDiagnosticsEnabled()) {
        EngineObservation.recordProcessDetails(
            EngineObservation.identityFor(owner),
            "process-started",
            purposeName,
            -1L,
            diagnosticCommand(command));
      }
      return;
    }
    long pid = processId(process);
    synchronized (REGISTERED_PROCESSES) {
      Long registered = REGISTERED_PROCESSES.get(owner);
      if (registered != null && registered.longValue() == pid) {
        return;
      }
      REGISTERED_PROCESSES.put(owner, pid);
    }
    String engineId = EngineObservation.restartInstance(owner, purposeName);
    if (!EngineObservation.engineDiagnosticsEnabled()) {
      return;
    }
    EngineObservation.recordProcessDetails(
        engineId, "process-started", purposeName, pid, diagnosticCommand(command));
  }

  public static void processStopped(Object owner, Purpose purpose, Process process) {
    // Shutdown requests are asynchronous. Keep a still-alive child registered so benchmark
    // isolation cannot race ahead while Metal/CoreML work is winding down. The next registry
    // query can prune it, and the exit callback prevents dead Process objects accumulating when no
    // benchmark is requested later.
    if (process != null) {
      if (!isProcessAlive(process)) {
        removeTrackedLocalProcess(process);
      } else {
        try {
          process.onExit().whenComplete((ignored, failure) -> removeTrackedLocalProcess(process));
        } catch (RuntimeException unsupportedExitNotification) {
          // activeLocalComputeProcessCount() remains a safe pruning fallback.
        }
      }
    }
    if (process == null) {
      String purposeName = normalizedPurpose(purpose).name();
      String engineId = EngineObservation.identityFor(owner);
      EngineObservation.recordProcessDetails(
          engineId, "process-stopped", purposeName, -1L, null);
      EngineObservation.ensureStopped(owner, "stopped");
      synchronized (FOREGROUND_SAMPLES) {
        FOREGROUND_SAMPLES.remove(owner);
      }
      return;
    }
    long stoppedPid = processId(process);
    synchronized (REGISTERED_PROCESSES) {
      Long registeredPid = REGISTERED_PROCESSES.get(owner);
      if (registeredPid == null || registeredPid.longValue() != stoppedPid) {
        return;
      }
      REGISTERED_PROCESSES.remove(owner);
    }
    String purposeName = normalizedPurpose(purpose).name();
    String engineId = EngineObservation.identityFor(owner);
    EngineObservation.recordProcessDetails(
        engineId, "process-stopped", purposeName, stoppedPid, null);
    EngineObservation.ensureStopped(owner, "stopped");
    synchronized (FOREGROUND_SAMPLES) {
      FOREGROUND_SAMPLES.remove(owner);
    }
  }

  /** Returns whether any registered local analysis process is still alive. */
  public static boolean hasActiveLocalComputeProcess() {
    return activeLocalComputeProcessCount() > 0;
  }

  static int activeLocalComputeProcessCount() {
    synchronized (ACTIVE_LOCAL_COMPUTE_PROCESSES) {
      int alive = 0;
      Iterator<Process> processes = ACTIVE_LOCAL_COMPUTE_PROCESSES.iterator();
      while (processes.hasNext()) {
        Process process = processes.next();
        if (!isProcessAlive(process)) {
          processes.remove();
        } else {
          alive++;
        }
      }
      return alive;
    }
  }

  static int rawLocalComputeProcessCountForTesting() {
    synchronized (ACTIVE_LOCAL_COMPUTE_PROCESSES) {
      return ACTIVE_LOCAL_COMPUTE_PROCESSES.size();
    }
  }

  private static boolean isProcessAlive(Process process) {
    try {
      return process != null && process.isAlive();
    } catch (RuntimeException invalidProcess) {
      return false;
    }
  }

  private static void removeTrackedLocalProcess(Process process) {
    synchronized (ACTIVE_LOCAL_COMPUTE_PROCESSES) {
      ACTIVE_LOCAL_COMPUTE_PROCESSES.remove(process);
    }
  }

  public static void commandSent(Object owner, Purpose purpose, String command) {
    if (!EngineObservation.engineDiagnosticsEnabled() || command == null) {
      return;
    }
    Matcher matcher = SET_PARAM.matcher(command);
    if (!matcher.matches() && !command.trim().toLowerCase(Locale.ROOT).startsWith("kata-analyze")) {
      return;
    }
    String purposeName = normalizedPurpose(purpose).name();
    String engineId = EngineObservation.identityFor(owner);
    if (matcher.matches()) {
      EngineObservation.recordProcessDetails(
          engineId,
          "dynamic-parameter",
          purposeName,
          -1L,
          matcher.group(1) + "=" + redactCommand(matcher.group(2)));
    } else {
      EngineObservation.recordProcessDetails(
          engineId, "analysis-started", purposeName, -1L, EngineObservation.commandName(command));
    }
  }

  public static void foregroundPausedForAuxiliary(
      Object foregroundOwner, Purpose auxiliaryPurpose) {
    if (!EngineObservation.engineDiagnosticsEnabled()) {
      return;
    }
    EngineObservation.recordProcessDetails(
        EngineObservation.identityFor(foregroundOwner),
        "foreground-paused",
        normalizedPurpose(auxiliaryPurpose).name(),
        -1L,
        null);
  }

  public static void foregroundPlayoutSample(Object owner, int playouts) {
    if (!EngineObservation.engineDiagnosticsEnabled() || owner == null || playouts < 0) {
      return;
    }
    long now = System.nanoTime();
    Sample previous;
    synchronized (FOREGROUND_SAMPLES) {
      previous = FOREGROUND_SAMPLES.get(owner);
      if (previous == null || playouts <= previous.playouts) {
        FOREGROUND_SAMPLES.put(owner, new Sample(playouts, now));
        return;
      }
      if (now - previous.nanoTime < 250_000_000L) {
        return;
      }
      FOREGROUND_SAMPLES.put(owner, new Sample(playouts, now));
    }
    EngineObservation.recordThroughput(
        EngineObservation.identityFor(owner),
        playouts,
        (playouts - previous.playouts) / elapsedSeconds(previous.nanoTime, now));
  }

  static double elapsedSeconds(long previousNanoTime, long currentNanoTime) {
    return Math.max(0L, currentNanoTime - previousNanoTime) / 1_000_000_000.0;
  }

  static String redactCommand(String command) {
    if (command == null) {
      return "";
    }
    String redacted = SENSITIVE_ARGUMENT.matcher(command).replaceAll("$1$2<redacted>");
    return URL_SECRET.matcher(redacted).replaceAll("$1<redacted>");
  }

  static String diagnosticCommand(String command) {
    String redacted = redactCommand(command);
    Matcher fileOptions = FILE_OPTION.matcher(redacted);
    StringBuffer sanitized = new StringBuffer();
    while (fileOptions.find()) {
      fileOptions.appendReplacement(
          sanitized,
          Matcher.quoteReplacement(
              fileOptions.group(1) + diagnosticFileName(unquote(fileOptions.group(2)))));
    }
    fileOptions.appendTail(sanitized);
    String withoutHome = HOME_DATA_DIR.matcher(sanitized.toString()).replaceAll("$1<local-path>");
    Matcher executable = LEADING_EXECUTABLE.matcher(withoutHome);
    if (!executable.find()) {
      return withoutHome;
    }
    String firstToken = unquote(executable.group(2));
    if (!looksLikeLocalPath(firstToken)) {
      return withoutHome;
    }
    return executable.replaceFirst(
        Matcher.quoteReplacement(executable.group(1) + diagnosticFileName(firstToken)));
  }

  static Map<String, String> parseDynamicParameters(Iterable<String> commands) {
    Map<String, String> parameters = new LinkedHashMap<>();
    if (commands == null) {
      return parameters;
    }
    for (String command : commands) {
      Matcher matcher = SET_PARAM.matcher(command == null ? "" : command);
      if (matcher.matches()) {
        parameters.put(matcher.group(1), redactCommand(matcher.group(2)));
      }
    }
    return parameters;
  }

  static String fileSha256(Path path) {
    if (path == null || !Files.isRegularFile(path)) {
      return "";
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[64 * 1024];
      try (java.io.InputStream input = Files.newInputStream(path)) {
        int read;
        while ((read = input.read(buffer)) >= 0) {
          digest.update(buffer, 0, read);
        }
      }
      StringBuilder hash = new StringBuilder();
      for (byte value : digest.digest()) {
        hash.append(String.format(Locale.ROOT, "%02x", value));
      }
      return hash.toString();
    } catch (IOException | NoSuchAlgorithmException e) {
      return "";
    }
  }

  private static Purpose normalizedPurpose(Purpose purpose) {
    return purpose == null ? Purpose.OTHER : purpose;
  }


  private static long processId(Process process) {
    try {
      return process == null ? -1L : process.pid();
    } catch (UnsupportedOperationException e) {
      return -1L;
    }
  }

  private static boolean looksLikeLocalPath(String value) {
    return value != null
        && (value.contains("/") || value.contains("\\") || value.matches("^[A-Za-z]:.*"));
  }

  private static String diagnosticFileName(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    String normalized = value.replace('\\', '/');
    int separator = normalized.lastIndexOf('/');
    return separator >= 0 ? normalized.substring(separator + 1) : normalized;
  }

  private static String unquote(String value) {
    if (value == null || value.length() < 2) {
      return value == null ? "" : value;
    }
    char first = value.charAt(0);
    char last = value.charAt(value.length() - 1);
    return (first == last && (first == '\"' || first == '\''))
        ? value.substring(1, value.length() - 1)
        : value;
  }


  private static final class Sample {
    private final int playouts;
    private final long nanoTime;

    private Sample(int playouts, long nanoTime) {
      this.playouts = playouts;
      this.nanoTime = nanoTime;
    }
  }
}
