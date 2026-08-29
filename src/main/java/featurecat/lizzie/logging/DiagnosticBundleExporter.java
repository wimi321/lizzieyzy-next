package featurecat.lizzie.logging;

import featurecat.lizzie.analysis.ReadBoardLoggingProtocol;
import featurecat.lizzie.analysis.ReadBoardLoggingSnapshot;
import featurecat.lizzie.analysis.SyncDiagnosticsExportSnapshot;
import featurecat.lizzie.analysis.SyncDiagnosticsExporter;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DiagnosticBundleExporter {
  public static final long APP_WINDOW_HOURS = 24;
  public static final long APP_CAP_BYTES = 50L * 1024 * 1024;
  public static final long CRASH_WINDOW_HOURS = 24;
  public static final long CRASH_CAP_BYTES = 10L * 1024 * 1024;
  public static final long RAW_CAP_BYTES = 50L * 1024 * 1024;
  public static final String NS_LIZZIE = "logs/lizzie/";
  public static final String NS_READBOARD = "logs/readboard/";
  public static final String NS_CAPTURE = "diagnostics/readboard-capture/";
  public static final String NS_SNAPSHOTS = "snapshots/";

  private static final long MIN_SOURCE_SCAN_BYTES = 1024L * 1024;
  private static final long MAX_SOURCE_SCAN_BYTES = 64L * 1024 * 1024;
  private static final int SOURCE_SCAN_MULTIPLIER = 4;
  private static final int MAX_ARCHIVE_FILES_PER_SOURCE = 255;
  private static final int MAX_ARCHIVE_DIRECTORY_ENTRIES = 1024;
  private static final int MAX_LINE_BYTES = 1024 * 1024;
  private static final int MAX_RECORD_CHARS = 1024 * 1024;
  private static final int MAX_CAPTURE_EVENTS = 255;
  private static final int MAX_CAPTURE_FILES_PER_EVENT = 64;
  private static final int MAX_CAPTURE_DIRECTORY_ENTRIES = 4096;
  private static final long MAX_CAPTURE_METADATA_BYTES = 1024L * 1024;
  private static final String LINE_TRUNCATION_MARKER = " [line-truncated]";
  private static final String RECORD_TRUNCATION_MARKER = "[record-truncated]\n";
  private static final String JSON_REDACTION_FAILURE = "\"[redaction-failed]\"";
  private static final Logger LOG = LoggerFactory.getLogger(LogCategories.DIAGNOSTICS);
  private static final DateTimeFormatter FILE_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
  private static final DateTimeFormatter LOG_TIMESTAMP =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS")
          .withResolverStyle(ResolverStyle.STRICT);
  private static final Pattern RECORD_HEADER =
      Pattern.compile("^(\\S{10}\\s+\\S{12})\\s+(?:TRACE|DEBUG|INFO|WARN|ERROR)\\b");
  private static final Pattern TRACE_SESSION_TOKEN = Pattern.compile("(?:^|\\s)trace=([^\\s]+)");
  private static final Pattern SAFE_CAPTURE_SEGMENT =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  @FunctionalInterface
  interface ThreadSnapshotSource {
    String capture(ExportSanitizer sanitizer);
  }

  @FunctionalInterface
  interface RuntimeSnapshotSource {
    String capture(Path workDirectory, ExportSanitizer sanitizer);
  }

  private final Path outputDirectory;
  private final DiagnosticBundleLimits limits;
  private final PartialFileObserver partialFileObserver;
  private final BundleNameSupplier bundleNameSupplier;
  private final PartialFileCleanupStrategy partialFileCleanupStrategy;
  private ThreadSnapshotSource threadSnapshotSource = ThreadSnapshot::capture;
  private RuntimeSnapshotSource runtimeSnapshotSource = RuntimeSnapshot::capture;

  public DiagnosticBundleExporter(Path outputDirectory) {
    this(outputDirectory, DiagnosticBundleLimits.production());
  }

  public DiagnosticBundleExporter(Path outputDirectory, DiagnosticBundleLimits limits) {
    this(
        outputDirectory,
        limits,
        path -> {},
        DiagnosticBundleExporter::randomZipName,
        PartialFileCleanupStrategy.systemDefault());
  }

  DiagnosticBundleExporter(
      Path outputDirectory,
      DiagnosticBundleLimits limits,
      PartialFileObserver partialFileObserver) {
    this(
        outputDirectory,
        limits,
        partialFileObserver,
        DiagnosticBundleExporter::randomZipName,
        PartialFileCleanupStrategy.systemDefault());
  }

  DiagnosticBundleExporter(
      Path outputDirectory,
      DiagnosticBundleLimits limits,
      PartialFileObserver partialFileObserver,
      PartialFileCleanupStrategy partialFileCleanupStrategy) {
    this(
        outputDirectory,
        limits,
        partialFileObserver,
        DiagnosticBundleExporter::randomZipName,
        partialFileCleanupStrategy);
  }

  DiagnosticBundleExporter(
      Path outputDirectory,
      DiagnosticBundleLimits limits,
      PartialFileObserver partialFileObserver,
      BundleNameSupplier bundleNameSupplier) {
    this(
        outputDirectory,
        limits,
        partialFileObserver,
        bundleNameSupplier,
        PartialFileCleanupStrategy.systemDefault());
  }

  private DiagnosticBundleExporter(
      Path outputDirectory,
      DiagnosticBundleLimits limits,
      PartialFileObserver partialFileObserver,
      BundleNameSupplier bundleNameSupplier,
      PartialFileCleanupStrategy partialFileCleanupStrategy) {
    this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.partialFileObserver =
        Objects.requireNonNull(partialFileObserver, "partialFileObserver");
    this.bundleNameSupplier = Objects.requireNonNull(bundleNameSupplier, "bundleNameSupplier");
    this.partialFileCleanupStrategy =
        Objects.requireNonNull(partialFileCleanupStrategy, "partialFileCleanupStrategy");
  }

  void setThreadSnapshotSourceForTests(ThreadSnapshotSource source) {
    this.threadSnapshotSource = source == null ? ThreadSnapshot::capture : source;
  }

  void setRuntimeSnapshotSourceForTests(RuntimeSnapshotSource source) {
    this.runtimeSnapshotSource = source == null ? RuntimeSnapshot::capture : source;
  }

  public static Path defaultOutputDirectory(Path workDirectory) {
    return Objects.requireNonNull(workDirectory, "workDirectory").resolve("diagnostics");
  }

  public long estimateUncompressedBytes(DiagnosticBundleRequest request) throws IOException {
    Objects.requireNonNull(request, "request");
    LoggingRuntime.TraceSessionSnapshot trace = request.runtime().traceSessionSnapshot();
    long total = 0;
    Path logs = request.runtime().logsDirectory();
    total = saturatingAdd(total, estimateLogBytes(logs, "app", limits.appCapBytes()));
    total = saturatingAdd(total, estimateLogBytes(logs, "crash", limits.crashCapBytes()));
    Path readBoardLogs = logs.resolve("readboard");
    total =
        saturatingAdd(
            total, estimateLogBytes(readBoardLogs, "app", limits.appCapBytes()));
    total =
        saturatingAdd(
            total, estimateLogBytes(readBoardLogs, "crash", limits.crashCapBytes()));
    if (trace.active()) {
      for (TraceScope scope : request.rawScopes()) {
        if (trace.scopes().contains(scope)) {
          total =
              saturatingAdd(
                  total, estimateLogBytes(logs, stem(scope.fileName()), limits.rawCapBytes()));
        }
      }
    }
    if (request.includeReadBoardTrace() && processSession(request) != null) {
      total =
          saturatingAdd(
              total, estimateLogBytes(readBoardLogs, "trace", limits.rawCapBytes()));
    }
    if (request.includeCapture() && processSession(request) != null) {
      // Capture enumeration is deliberately deferred to export, where every file is identity
      // checked. A conservative cap avoids an unsafe or unbounded preflight tree walk.
      total = saturatingAdd(total, limits.captureCapBytes());
    }
    return saturatingAdd(total, 64 * 1024);
  }

  private static long estimateLogBytes(Path logsDirectory, String stem, long cap)
      throws IOException {
    long total = 0;
    LogFileSet files = listLogFiles(logsDirectory, stem, () -> false);
    if (files.truncated()) {
      return cap;
    }
    for (SourceFile file : files.paths()) {
      if (isGzip(file.path())) {
        return cap;
      }
      total = Math.min(cap, saturatingAdd(total, file.size()));
      if (total >= cap) {
        return cap;
      }
    }
    return total;
  }

  public Path export(DiagnosticBundleRequest request) throws IOException {
    return export(request, () -> false);
  }

  public Path export(DiagnosticBundleRequest request, BooleanSupplier cancelled)
      throws IOException {
    Objects.requireNonNull(request, "request");
    BooleanSupplier cancel = cancelled == null ? () -> false : cancelled;
    LoggingRuntime.TraceSessionSnapshot trace = request.runtime().traceSessionSnapshot();
    LoggingSettings settings = request.runtime().settings();
    Instant captureTime = trace.capturedAt();
    throwIfCancelled(cancel);
    OutputRoot outputRoot = prepareOutputDirectory();
    CreatedPartial partial = createPartial(outputRoot);
    ExportSanitizer sanitizer = new ExportSanitizer();
    JSONObject sources = new JSONObject();
    Path hostLogs = request.runtime().logsDirectory();
    Path readBoardLogs = hostLogs.resolve("readboard");
    String hostSession = request.runtime().applicationLogSessionId();
    String processSession = processSession(request);
    String processAlias =
        processSession == null ? null : sanitizer.alias("session", processSession);
    try (FileChannel channel = partial.channel();
        FileLock ownershipLock = partial.ownershipLock()) {
      try {
        try (ZipOutputStream out =
            new ZipOutputStream(
                new CloseShieldOutputStream(Channels.newOutputStream(channel)))) {
          copyLogSource(
              out,
              NS_LIZZIE + "app.log",
              "lizzie-app",
              NS_LIZZIE,
              hostLogs,
              "app",
              captureTime.minusSeconds(limits.appWindowHours() * 3600),
              captureTime,
              limits.appWindowHours(),
              limits.appCapBytes(),
              sanitizer,
              sources,
              null,
              false,
              true,
              hostSession,
              "no-active-session",
              cancel);
          throwIfCancelled(cancel);
          copyLogSource(
              out,
              NS_LIZZIE + "crash.log",
              "lizzie-crash",
              NS_LIZZIE,
              hostLogs,
              "crash",
              captureTime.minusSeconds(limits.crashWindowHours() * 3600),
              captureTime,
              limits.crashWindowHours(),
              limits.crashCapBytes(),
              sanitizer,
              sources,
              null,
              false,
              true,
              hostSession,
              "no-active-session",
              cancel);
          throwIfCancelled(cancel);
          copyRawTraces(out, request, trace, sanitizer, sources, captureTime, cancel);
          throwIfCancelled(cancel);
          copyLogSource(
              out,
              NS_READBOARD + "app.log",
              "readboard-app",
              NS_READBOARD,
              readBoardLogs,
              "app",
              captureTime.minusSeconds(limits.appWindowHours() * 3600),
              captureTime,
              limits.appWindowHours(),
              limits.appCapBytes(),
              sanitizer,
              sources,
              null,
              true,
              true,
              processAlias,
              "no-current-session",
              cancel);
          throwIfCancelled(cancel);
          copyLogSource(
              out,
              NS_READBOARD + "crash.log",
              "readboard-crash",
              NS_READBOARD,
              readBoardLogs,
              "crash",
              captureTime.minusSeconds(limits.crashWindowHours() * 3600),
              captureTime,
              limits.crashWindowHours(),
              limits.crashCapBytes(),
              sanitizer,
              sources,
              null,
              true,
              true,
              processAlias,
              "no-current-session",
              cancel);
          throwIfCancelled(cancel);
          copyReadBoardTrace(
              out,
              request,
              sanitizer,
              sources,
              processSession,
              processAlias,
              captureTime,
              cancel);
          throwIfCancelled(cancel);
          copyCapture(
              out,
              request,
              sanitizer,
              sources,
              processSession,
              processAlias,
              captureTime,
              cancel);
          throwIfCancelled(cancel);
          writeSnapshots(
              out, request, sanitizer, sources, hostSession, captureTime);
          throwIfCancelled(cancel);
          JSONObject manifest =
              renderManifest(
                  request,
                  trace,
                  settings,
                  captureTime,
                  sanitizer,
                  sources,
                  processAlias);
          writeTextEntry(out, "manifest.json", manifest.toString(2));
        }
        // Keep this hook after ZipOutputStream.close(): tests can exercise pathname replacement
        // after real payload bytes and the central directory have reached the still-open channel.
        partialFileObserver.payloadWritten(partial.path());
        channel.force(true);
        throwIfCancelled(cancel);
        Path target = publishAtomically(partial, outputRoot, captureTime);
        LOG.info("diagnostic package published file={}", target.getFileName());
        return target;
      } catch (IOException | RuntimeException | Error e) {
        cleanupFailedPartial(partial, e);
        throw e;
      }
    }
  }

  private void copyRawTraces(
      ZipOutputStream out,
      DiagnosticBundleRequest request,
      LoggingRuntime.TraceSessionSnapshot trace,
      ExportSanitizer sanitizer,
      JSONObject sources,
      Instant captureTime,
      BooleanSupplier cancel)
      throws IOException {
    Set<TraceScope> requestedScopes = request.rawScopes();
    for (TraceScope scope : TraceScope.values()) {
      String sourceName = hostTraceSourceName(scope);
      if (!requestedScopes.contains(scope)) {
        sources.put(
            sourceName,
            sourceRecord(
                false,
                "omitted",
                0,
                0,
                limits.rawCapBytes(),
                NS_LIZZIE,
                request.runtime().applicationLogSessionId(),
                "not-requested",
                false));
        continue;
      }
      if (!trace.active()) {
        sources.put(
            sourceName,
            sourceRecord(
                true,
                "omitted",
                0,
                0,
                limits.rawCapBytes(),
                NS_LIZZIE,
                request.runtime().applicationLogSessionId(),
                "no-active-session",
                false));
        continue;
      }
      if (!trace.scopes().contains(scope)) {
        sources.put(
            sourceName,
            sourceRecord(
                true,
                "omitted",
                0,
                0,
                limits.rawCapBytes(),
                NS_LIZZIE,
                request.runtime().applicationLogSessionId(),
                "scope-not-active-at-capture",
                false));
        continue;
      }
      throwIfCancelled(cancel);
      copyLogSource(
          out,
          NS_LIZZIE + scope.fileName(),
          sourceName,
          NS_LIZZIE,
          request.runtime().logsDirectory(),
          stem(scope.fileName()),
          Instant.EPOCH,
          captureTime,
          0,
          limits.rawCapBytes(),
          sanitizer,
          sources,
          trace.sessionId(),
          false,
          true,
          request.runtime().applicationLogSessionId(),
          "no-active-session",
          cancel);
    }
  }

  private void copyReadBoardTrace(
      ZipOutputStream out,
      DiagnosticBundleRequest request,
      ExportSanitizer sanitizer,
      JSONObject sources,
      String processSession,
      String processAlias,
      Instant captureTime,
      BooleanSupplier cancel)
      throws IOException {
    if (!request.includeReadBoardTrace()) {
      sources.put(
          "readboard-trace",
          sourceRecord(
              false,
              "omitted",
              0,
              0,
              limits.rawCapBytes(),
              NS_READBOARD,
              processAlias,
              "not-requested",
              false));
      return;
    }
    if (!request.readBoardLogging().attached() || processSession == null) {
      sources.put(
          "readboard-trace",
          sourceRecord(
              true,
              "omitted",
              0,
              0,
              limits.rawCapBytes(),
              NS_READBOARD,
              processAlias,
              request.readBoardLogging().attached()
                  ? "no-current-session"
                  : "helper-not-started",
              false));
      return;
    }
    throwIfCancelled(cancel);
    copyLogSource(
        out,
        NS_READBOARD + "trace.log",
        "readboard-trace",
        NS_READBOARD,
        request.runtime().logsDirectory().resolve("readboard"),
        "trace",
        Instant.EPOCH,
        captureTime,
        0,
        limits.rawCapBytes(),
        sanitizer,
        sources,
        processSession,
        true,
        true,
        processAlias,
        "no-current-session",
        cancel);
  }

  private void copyCapture(
      ZipOutputStream out,
      DiagnosticBundleRequest request,
      ExportSanitizer sanitizer,
      JSONObject sources,
      String processSession,
      String processAlias,
      Instant captureTime,
      BooleanSupplier cancel)
      throws IOException {
    if (!request.includeCapture()) {
      sources.put(
          "readboard-capture",
          sourceRecord(
              false,
              "omitted",
              0,
              0,
              limits.captureCapBytes(),
              NS_CAPTURE,
              processAlias,
              "not-requested",
              false));
      return;
    }
    if (!request.readBoardLogging().attached() || processSession == null) {
      sources.put(
          "readboard-capture",
          sourceRecord(
              true,
              "omitted",
              0,
              0,
              limits.captureCapBytes(),
              NS_CAPTURE,
              processAlias,
              request.readBoardLogging().attached()
                  ? "no-current-session"
                  : "helper-not-started",
              false));
      return;
    }

    Path captureRoot =
        request.runtime().logsDirectory().resolve("readboard").resolve("capture");
    if (!Files.isDirectory(captureRoot, LinkOption.NOFOLLOW_LINKS)) {
      sources.put(
          "readboard-capture",
          sourceRecord(
              true,
              "omitted",
              0,
              0,
              limits.captureCapBytes(),
              NS_CAPTURE,
              processAlias,
              "no-current-session",
              false));
      return;
    }

    try {
      CaptureEventSet eventSet =
          listCurrentCaptureEvents(
              captureRoot,
              processSession,
              request.readBoardLogging().processSessionObservedAt(),
              captureTime,
              cancel);
      long remaining = limits.captureCapBytes();
      long written = 0;
      int includedEvents = 0;
      boolean truncated = eventSet.truncated();
      String boundary = "";
      List<CapturePayload> selected = new ArrayList<>();
      for (CaptureEventSource event : eventSet.events()) {
        throwIfCancelled(cancel);
        if (event.truncated()) {
          truncated = true;
          break;
        }
        List<CapturePayload> payloads =
            readCaptureEvent(event, sanitizer, remaining, cancel);
        if (payloads == null) {
          truncated = true;
          break;
        }
        long eventBytes = capturePayloadBytes(payloads);
        selected.addAll(payloads);
        written = saturatingAdd(written, eventBytes);
        remaining -= eventBytes;
        includedEvents++;
        if (boundary.isEmpty()) {
          boundary = event.name();
        }
      }

      Path rootReal = captureRoot.toRealPath();
      Path debugLog = captureRoot.resolve("debug.log");
      if (Files.isRegularFile(debugLog, LinkOption.NOFOLLOW_LINKS)) {
        SourceFile debugSource = inspectSourceFile(rootReal, debugLog);
        List<CapturePayload> debug =
            readCaptureFiles(
                List.of(debugSource), NS_CAPTURE, sanitizer, remaining, cancel);
        if (debug == null) {
          truncated = true;
        } else {
          long debugBytes = capturePayloadBytes(debug);
          selected.addAll(debug);
          written = saturatingAdd(written, debugBytes);
          remaining -= debugBytes;
        }
      }

      if (!sameIdentity(eventSet.rootIdentity(), captureDirectoryIdentity(captureRoot))) {
        throw new IOException("capture root changed while being read");
      }

      if (selected.isEmpty() && !truncated) {
        sources.put(
            "readboard-capture",
            sourceRecord(
                true,
                "omitted",
                0,
                0,
                limits.captureCapBytes(),
                NS_CAPTURE,
                processAlias,
                "no-current-session",
                false));
        return;
      }
      for (CapturePayload payload : selected) {
        throwIfCancelled(cancel);
        writeBytesEntry(out, payload.entryName(), payload.bytes());
      }
      JSONObject source =
          sourceRecord(
              true,
              truncated ? "truncated" : "included",
              written,
              0,
              limits.captureCapBytes(),
              NS_CAPTURE,
              processAlias,
              truncated ? "cap" : "",
              truncated);
      source.put("includedEvents", includedEvents);
      if (!boundary.isEmpty()) {
        source.put("boundary", boundary);
      }
      sources.put("readboard-capture", source);
    } catch (ExportCancelledException cancelled) {
      throw cancelled;
    } catch (IOException failure) {
      sources.put(
          "readboard-capture",
          sourceRecord(
              true,
              "failed",
              0,
              0,
              limits.captureCapBytes(),
              NS_CAPTURE,
              processAlias,
              failureReason(failure),
              false));
    }
  }

  private void writeSnapshots(
      ZipOutputStream out,
      DiagnosticBundleRequest request,
      ExportSanitizer sanitizer,
      JSONObject sources,
      String hostSession,
      Instant captureTime)
      throws IOException {
    JSONObject projected = ConfigExportProjection.project(request.config());
    writeTextEntry(
        out,
        NS_SNAPSHOTS + "config.json",
        sanitizer.sanitizeJsonObject(projected).toString(2));
    JSONObject versions = new JSONObject();
    versions.put("host", sanitizer.sanitizeText(request.appVersion()));
    versions.put("readboard", sanitizer.sanitizeText(request.readBoardVersion()));
    writeTextEntry(out, NS_SNAPSHOTS + "versions.json", versions.toString(2));
    writeRuntimeSnapshot(out, request, sanitizer);
    writeTextEntry(
        out,
        NS_SNAPSHOTS + "readboard-observed.json",
        renderObserved(request.readBoardLogging(), sanitizer).toString(2));
    writeThreadSnapshot(out, sanitizer, sources, hostSession);
    SyncDiagnosticsExportSnapshot snapshot =
        request.snapshot() == null
            ? new SyncDiagnosticsExportSnapshot(
                captureTime.toEpochMilli(), null, null, null, null, null)
            : request.snapshot();
    SyncDiagnosticsExporter.writeSnapshotEntries(
        out, snapshot, sanitizer.shareTime(), NS_SNAPSHOTS);
    sources.put(
        "snapshots",
        sourceRecord(
            true,
            "included",
            0,
            0,
            0,
            NS_SNAPSHOTS,
            hostSession,
            "",
            false));
    sources.put(
        "environment",
        sourceRecord(
            true,
            "included",
            0,
            0,
            0,
            NS_SNAPSHOTS,
            hostSession,
            "",
            false));
  }

  private void writeRuntimeSnapshot(
      ZipOutputStream out, DiagnosticBundleRequest request, ExportSanitizer sanitizer)
      throws IOException {
    String text;
    try {
      text = runtimeSnapshotSource.capture(resolveWorkDirectory(request), sanitizer);
      if (text == null || text.isBlank()) {
        text = RuntimeSnapshot.unavailableJson();
      }
    } catch (RuntimeException | Error ignored) {
      text = RuntimeSnapshot.unavailableJson();
    }
    writeTextEntry(out, NS_SNAPSHOTS + RuntimeSnapshot.ENTRY_NAME, text);
  }

  private static Path resolveWorkDirectory(DiagnosticBundleRequest request) {
    try {
      Path work = request.runtime().workDirectory();
      if (work != null) {
        return work;
      }
    } catch (RuntimeException ignored) {
    }
    try {
      Path logs = request.runtime().logsDirectory();
      return logs == null ? null : logs.getParent();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private void writeThreadSnapshot(
      ZipOutputStream out,
      ExportSanitizer sanitizer,
      JSONObject sources,
      String hostSession) {
    String text;
    String status = "included";
    String reason = "";
    try {
      text = threadSnapshotSource.capture(sanitizer);
      if (text == null) {
        text = "";
      }
    } catch (RuntimeException | Error ignored) {
      status = "failed";
      reason = "unreadable";
      try {
        text = sanitizer.sanitizeText("thread snapshot failed\n");
      } catch (RuntimeException | Error ignoredSanitizer) {
        text = "thread snapshot failed\n";
      }
    }
    try {
      writeTextEntry(out, NS_SNAPSHOTS + ThreadSnapshot.ENTRY_NAME, text);
      sources.put(
          "threads",
          sourceRecord(
              true,
              status,
              text.getBytes(StandardCharsets.UTF_8).length,
              0,
              0,
              NS_SNAPSHOTS,
              hostSession,
              reason,
              false));
    } catch (IOException ignoredWrite) {
      sources.put(
          "threads",
          sourceRecord(
              true,
              "failed",
              0,
              0,
              0,
              NS_SNAPSHOTS,
              hostSession,
              "unreadable",
              false));
    }
  }

  private static CaptureEventSet listCurrentCaptureEvents(
      Path captureRoot,
      String processSession,
      Instant sessionObservedAt,
      Instant captureTime,
      BooleanSupplier cancel)
      throws IOException {
    FileIdentity rootIdentity = captureDirectoryIdentity(captureRoot);
    Path rootReal = rootIdentity.realPath();
    Comparator<CaptureEventSource> oldestFirst =
        Comparator.comparing(CaptureEventSource::name);
    PriorityQueue<CaptureEventSource> newestEvents = new PriorityQueue<>(oldestFirst);
    boolean truncated = false;
    int visited = 0;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(captureRoot)) {
      for (Path candidate : stream) {
        throwIfCancelled(cancel);
        if (++visited > MAX_CAPTURE_DIRECTORY_ENTRIES) {
          truncated = true;
          break;
        }
        String name = safePathSegment(candidate.getFileName());
        if (name == null
            || name.toLowerCase(Locale.ROOT).endsWith(".zip")
            || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        try {
          CaptureEventSource event =
              inspectCaptureEvent(
                  rootReal,
                  candidate,
                  name,
                  processSession,
                  sessionObservedAt,
                  captureTime,
                  cancel);
          if (event == null) {
            continue;
          }
          newestEvents.add(event);
          if (newestEvents.size() > MAX_CAPTURE_EVENTS) {
            newestEvents.remove();
            truncated = true;
          }
        } catch (java.nio.file.NoSuchFileException disappeared) {
          // An event without a stable completed directory belongs to the active helper writer.
        }
      }
    }
    if (!sameIdentity(rootIdentity, captureDirectoryIdentity(captureRoot))) {
      throw new IOException("capture root changed during enumeration");
    }
    List<CaptureEventSource> events = new ArrayList<>(newestEvents);
    events.sort(Comparator.comparing(CaptureEventSource::name).reversed());
    return new CaptureEventSet(List.copyOf(events), truncated, rootIdentity);
  }

  private static CaptureEventSource inspectCaptureEvent(
      Path captureRootReal,
      Path eventPath,
      String eventName,
      String processSession,
      Instant sessionObservedAt,
      Instant captureTime,
      BooleanSupplier cancel)
      throws IOException {
    FileIdentity eventIdentity = captureDirectoryIdentity(eventPath);
    if (!eventIdentity.realPath().startsWith(captureRootReal)) {
      throw new IOException("capture event escapes the capture root");
    }
    Path metadataPath = eventPath.resolve("metadata.json");
    if (!Files.isRegularFile(metadataPath, LinkOption.NOFOLLOW_LINKS)) {
      return null;
    }
    SourceFile metadata = inspectSourceFile(eventIdentity.realPath(), metadataPath);
    JSONObject metadataJson = readCaptureMetadata(metadata, cancel);
    if (metadataJson == null
        || !captureEventMatchesSession(
            metadataJson, processSession, sessionObservedAt, captureTime)) {
      return null;
    }

    List<SourceFile> files = new ArrayList<>();
    boolean truncated = false;
    int visited = 0;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(eventPath)) {
      for (Path file : stream) {
        throwIfCancelled(cancel);
        if (++visited > MAX_CAPTURE_DIRECTORY_ENTRIES) {
          truncated = true;
          break;
        }
        String fileName = safePathSegment(file.getFileName());
        if (fileName == null
            || fileName.toLowerCase(Locale.ROOT).endsWith(".zip")
            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        files.add(inspectSourceFile(eventIdentity.realPath(), file));
        if (files.size() > MAX_CAPTURE_FILES_PER_EVENT) {
          truncated = true;
          break;
        }
      }
    }
    if (!sameIdentity(eventIdentity, captureDirectoryIdentity(eventPath))) {
      throw new IOException("capture event changed during enumeration");
    }
    files.sort(Comparator.comparing(source -> source.path().getFileName().toString()));
    return new CaptureEventSource(
        eventName, eventPath, eventIdentity, List.copyOf(files), truncated);
  }

  private static JSONObject readCaptureMetadata(SourceFile metadata, BooleanSupplier cancel)
      throws IOException {
    if (metadata.size() > MAX_CAPTURE_METADATA_BYTES) {
      return null;
    }
    byte[] bytes = readVerifiedBytes(metadata, MAX_CAPTURE_METADATA_BYTES, cancel);
    try {
      return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    } catch (RuntimeException malformed) {
      return null;
    }
  }

  private static boolean captureEventMatchesSession(
      JSONObject metadata,
      String processSession,
      Instant sessionObservedAt,
      Instant captureTime) {
    String stamped = extractMetadataSession(metadata);
    if (stamped != null && !stamped.isEmpty()) {
      return processSession.equals(stamped);
    }
    Instant eventTime = parseCaptureTimestamp(metadata);
    return eventTime != null
        && sessionObservedAt != null
        && captureTime != null
        && !eventTime.isBefore(sessionObservedAt)
        && !eventTime.isAfter(captureTime);
  }

  private static List<CapturePayload> readCaptureEvent(
      CaptureEventSource event,
      ExportSanitizer sanitizer,
      long remaining,
      BooleanSupplier cancel)
      throws IOException {
    if (event.truncated()) {
      return null;
    }
    List<CapturePayload> payloads = new ArrayList<>();
    long available = Math.max(0L, remaining);
    for (SourceFile source : event.files()) {
      throwIfCancelled(cancel);
      String fileName = safePathSegment(source.path().getFileName());
      if (fileName == null) {
        throw new IOException("capture payload has an invalid file name");
      }
      long rawLimit = available;
      if ("metadata.json".equalsIgnoreCase(fileName)) {
        rawLimit = Math.min(rawLimit, MAX_CAPTURE_METADATA_BYTES);
      }
      if (source.size() > rawLimit) {
        return null;
      }
      byte[] raw = readVerifiedBytes(source, rawLimit, cancel);
      byte[] rendered = sanitizeCapturePayload(fileName, raw, sanitizer);
      if (rendered.length > available) {
        return null;
      }
      payloads.add(
          new CapturePayload(NS_CAPTURE + event.name() + "/" + fileName, rendered));
      available -= rendered.length;
    }
    if (!sameIdentity(event.identity(), captureDirectoryIdentity(event.path()))) {
      throw new IOException("capture event changed while being read");
    }
    return List.copyOf(payloads);
  }

  private static List<CapturePayload> readCaptureFiles(
      List<SourceFile> files,
      String namespace,
      ExportSanitizer sanitizer,
      long remaining,
      BooleanSupplier cancel)
      throws IOException {
    List<CapturePayload> payloads = new ArrayList<>();
    long available = Math.max(0L, remaining);
    for (SourceFile source : files) {
      throwIfCancelled(cancel);
      String fileName = safePathSegment(source.path().getFileName());
      if (fileName == null || source.size() > available) {
        return null;
      }
      byte[] raw = readVerifiedBytes(source, available, cancel);
      byte[] rendered = sanitizeCapturePayload(fileName, raw, sanitizer);
      if (rendered.length > available) {
        return null;
      }
      payloads.add(new CapturePayload(namespace + fileName, rendered));
      available -= rendered.length;
    }
    return List.copyOf(payloads);
  }

  private static byte[] sanitizeCapturePayload(
      String fileName, byte[] raw, ExportSanitizer sanitizer) {
    if (isPng(fileName)) {
      return raw;
    }
    String text = new String(raw, StandardCharsets.UTF_8);
    String sanitized;
    String lowerName = fileName.toLowerCase(Locale.ROOT);
    if (lowerName.endsWith(".json")) {
      try {
        sanitized = sanitizer.sanitizeJson(text);
      } catch (RuntimeException malformed) {
        sanitized = JSON_REDACTION_FAILURE;
      }
    } else if (lowerName.endsWith(".jsonl")) {
      sanitized = sanitizeJsonLines(text, sanitizer);
    } else {
      sanitized = sanitizer.sanitizeText(text);
    }
    return sanitized.getBytes(StandardCharsets.UTF_8);
  }

  private static String sanitizeJsonLines(String text, ExportSanitizer sanitizer) {
    StringBuilder sanitized = new StringBuilder(text.length());
    int lineStart = 0;
    while (lineStart < text.length()) {
      int lineEnd = lineStart;
      while (lineEnd < text.length()
          && text.charAt(lineEnd) != '\r'
          && text.charAt(lineEnd) != '\n') {
        lineEnd++;
      }
      String line = text.substring(lineStart, lineEnd);
      if (line.trim().isEmpty()) {
        sanitized.append(line);
      } else {
        try {
          Object parsed = ExportSanitizer.parseJsonValueStrict(line);
          if (!(parsed instanceof JSONObject) && !(parsed instanceof JSONArray)) {
            throw new IllegalArgumentException("JSONL records must be objects or arrays");
          }
          sanitized.append(
              ExportSanitizer.renderJsonValue(sanitizer.sanitizeJsonValue(parsed)));
        } catch (RuntimeException malformed) {
          // A malformed line is replaced independently; later records remain available without
          // ever copying bytes from the rejected line.
          sanitized.append(JSON_REDACTION_FAILURE);
        }
      }
      if (lineEnd < text.length()) {
        char separator = text.charAt(lineEnd++);
        sanitized.append(separator);
        if (separator == '\r' && lineEnd < text.length() && text.charAt(lineEnd) == '\n') {
          sanitized.append('\n');
          lineEnd++;
        }
      }
      lineStart = lineEnd;
    }
    return sanitized.toString();
  }

  private static byte[] readVerifiedBytes(
      SourceFile source, long maximumBytes, BooleanSupplier cancel) throws IOException {
    if (source.size() < 0
        || source.size() > maximumBytes
        || source.size() > Integer.MAX_VALUE) {
      throw new SourceLimitExceededException("capture-cap");
    }
    ByteArrayOutputStream bytes =
        new ByteArrayOutputStream((int) Math.min(source.size(), 8192L));
    try (FileChannel channel = openVerifiedSource(source);
        InputStream input = Channels.newInputStream(channel)) {
      byte[] buffer = new byte[8192];
      long read = 0;
      int count;
      while ((count = input.read(buffer)) >= 0) {
        throwIfCancelled(cancel);
        if (count == 0) {
          continue;
        }
        read = saturatingAdd(read, count);
        if (read > maximumBytes || read > Integer.MAX_VALUE) {
          throw new SourceLimitExceededException("capture-cap");
        }
        bytes.write(buffer, 0, count);
      }
    }
    BasicFileAttributes attributes =
        Files.readAttributes(source.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    FileIdentity current = captureRegularFileIdentity(source.path());
    if (!sameIdentity(source.identity(), current)
        || attributes.size() != source.size()
        || !attributes.lastModifiedTime().toInstant().equals(source.lastModified())) {
      throw new IOException("capture payload changed while being read");
    }
    return bytes.toByteArray();
  }

  private static long capturePayloadBytes(List<CapturePayload> payloads) {
    long total = 0;
    for (CapturePayload payload : payloads) {
      total = saturatingAdd(total, payload.bytes().length);
    }
    return total;
  }

  private static void writeBytesEntry(ZipOutputStream out, String name, byte[] bytes)
      throws IOException {
    out.putNextEntry(new ZipEntry(name));
    out.write(bytes);
    out.closeEntry();
  }

  private static String safePathSegment(Path path) {
    if (path == null) {
      return null;
    }
    String value = path.toString();
    if (!SAFE_CAPTURE_SEGMENT.matcher(value).matches()
        || ".".equals(value)
        || "..".equals(value)) {
      return null;
    }
    try {
      Path parsed = Path.of(value);
      return parsed.isAbsolute()
              || parsed.getNameCount() != 1
              || !parsed.getFileName().toString().equals(value)
          ? null
          : value;
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  private static Instant parseCaptureTimestamp(JSONObject json) {
    String raw = json.optString("TimestampUtc", json.optString("timestampUtc", ""));
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    try {
      return Instant.parse(raw);
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  private static String extractMetadataSession(JSONObject json) {
    Object value = json.has("processSessionId") ? json.opt("processSessionId") : null;
    if (value == null || value == JSONObject.NULL) {
      String legacy = json.optString("ProcessSessionId", "");
      return legacy.isEmpty() ? null : legacy;
    }
    return extractTaggedString(value);
  }

  private void copyLogSource(
      ZipOutputStream out,
      String entryName,
      String sourceName,
      String namespace,
      Path logsDirectory,
      String stem,
      Instant cutoff,
      Instant captureTime,
      long windowHours,
      long capBytes,
      ExportSanitizer sanitizer,
      JSONObject sources,
      String requiredSession,
      boolean jsonLines,
      boolean requested,
      String sessionForManifest,
      String emptyReason,
      BooleanSupplier cancel)
      throws IOException {
    JSONObject source =
        sourceRecord(
            requested,
            "included",
            0,
            windowHours,
            capBytes,
            namespace,
            sessionForManifest,
            "",
            false);
    LogFileSet logFiles;
    try {
      logFiles = listLogFiles(logsDirectory, stem, cancel);
    } catch (ExportCancelledException e) {
      throw e;
    } catch (IOException e) {
      sources.put(
          sourceName,
          sourceRecord(
              requested,
              "failed",
              0,
              windowHours,
              capBytes,
              namespace,
              sessionForManifest,
              failureReason(e),
              false));
      return;
    }
    List<SourceFile> files = logFiles.paths();
    if (files.isEmpty()) {
      sources.put(
          sourceName,
          sourceRecord(
              requested,
              "failed",
              0,
              windowHours,
              capBytes,
              namespace,
              sessionForManifest,
              "missing",
              false));
      return;
    }

    RecordTail selected = new RecordTail(capBytes);
    ReadBudget budget = new ReadBudget(scanBudget(capBytes));
    ReadBudget compressedBudget = new ReadBudget(scanBudget(capBytes));
    boolean truncated = logFiles.truncated();
    if (logFiles.truncated()) {
      source.put("fileListTruncated", true);
    }
    int malformedRecords = 0;
    int readErrors = 0;
    String lastReadError = null;
    for (int index = 0; index < files.size(); index++) {
      throwIfCancelled(cancel);
      if (selected.remaining() <= 0) {
        truncated = true;
        break;
      }
      SourceFile file = files.get(index);
      try {
        ReadFileResult result =
            jsonLines
                ? readJsonFileTail(
                    file,
                    selected.remaining(),
                    budget,
                    compressedBudget,
                    cutoff,
                    captureTime,
                    requiredSession,
                    sanitizer,
                    cancel)
                : readFileTail(
                    file,
                    selected.remaining(),
                    budget,
                    compressedBudget,
                    cutoff,
                    captureTime,
                    requiredSession,
                    sanitizer,
                    cancel);
        malformedRecords += result.malformedRecords();
        truncated |= result.truncated();
        selected.prepend(result.records());
        if (result.newerHistoryOmitted()) {
          truncated = true;
          break;
        }
      } catch (ExportCancelledException e) {
        throw e;
      } catch (SourceLimitExceededException e) {
        truncated = true;
        readErrors++;
        lastReadError = e.reason();
        break;
      } catch (IOException e) {
        readErrors++;
        // Keep the manifest vocabulary stable and privacy-safe. Runtime exception class names
        // are an implementation detail; callers only need to distinguish a missing source from
        // one that was discovered but could not be read.
        lastReadError = failureReason(e);
      }
      if (selected.remaining() <= 0 && index + 1 < files.size()) {
        truncated = true;
        break;
      }
    }

    source.put("bytes", selected.bytes());
    source.put("truncated", truncated);
    source.put("malformedRecordsExcluded", malformedRecords);
    source.put("scanBudgetBytes", budget.limit());
    source.put("scannedBytes", budget.consumed());
    source.put("compressedScanBudgetBytes", compressedBudget.limit());
    source.put("compressedBytesRead", compressedBudget.consumed());
    if (readErrors > 0) {
      source.put("readErrors", readErrors);
      source.put("reason", lastReadError);
    }
    if (selected.bytes() == 0 && readErrors > 0) {
      writeTailEntry(out, entryName, selected, cancel);
      source.put("status", "error");
      source.put("failed", true);
      source.put("included", false);
      source.put("reason", lastReadError);
      sources.put(sourceName, source);
      return;
    }
    if (selected.bytes() == 0 && requiredSession != null) {
      source.put("status", "omitted");
      source.put("included", false);
      source.put("omitted", true);
      source.put("reason", emptyReason);
      sources.put(sourceName, source);
      return;
    }

    writeTailEntry(out, entryName, selected, cancel);
    if (truncated) {
      source.put("status", "truncated");
      source.put("truncated", true);
    } else if (readErrors > 0) {
      source.put("status", "partial");
    }
    sources.put(sourceName, source);
  }

  private static ReadFileResult readFileTail(
      SourceFile file,
      long capBytes,
      ReadBudget budget,
      ReadBudget compressedBudget,
      Instant cutoff,
      Instant captureTime,
      String requiredSession,
      ExportSanitizer sanitizer,
      BooleanSupplier cancel)
      throws IOException {
    RecordTail records = new RecordTail(capBytes);
    int malformedRecords = 0;
    boolean truncated;
    boolean tailLimited;
    RecordAccumulator current = null;
    try (OpenedLogInput opened = openLogInput(file, budget, compressedBudget);
        BoundedUtf8LineReader reader = new BoundedUtf8LineReader(opened.input(), cancel)) {
      truncated = opened.tailLimited();
      tailLimited = opened.tailLimited();
      LogLine line;
      while ((line = reader.readLine()) != null) {
        Matcher header = RECORD_HEADER.matcher(line.text());
        if (header.find()) {
          if (current != null) {
            truncated |= finishRecord(current, records, requiredSession, sanitizer);
          }
          Instant timestamp = parseTimestamp(header.group(1));
          if (timestamp == null) {
            malformedRecords++;
          }
          boolean eligible =
              timestamp != null
                  && (cutoff == null || !timestamp.isBefore(cutoff))
                  && (captureTime == null || !timestamp.isAfter(captureTime));
          current = new RecordAccumulator(eligible);
          current.append(line);
        } else if (current != null) {
          current.append(line);
        }
      }
      if (current != null) {
        truncated |= finishRecord(current, records, requiredSession, sanitizer);
      }
    }
    return new ReadFileResult(
        records,
        malformedRecords,
        truncated || records.truncated(),
        tailLimited || records.evicted());
  }

  private static ReadFileResult readJsonFileTail(
      SourceFile file,
      long capBytes,
      ReadBudget budget,
      ReadBudget compressedBudget,
      Instant cutoff,
      Instant captureTime,
      String requiredSession,
      ExportSanitizer sanitizer,
      BooleanSupplier cancel)
      throws IOException {
    RecordTail records = new RecordTail(capBytes);
    int malformedRecords = 0;
    boolean truncated;
    boolean tailLimited;
    try (OpenedLogInput opened = openLogInput(file, budget, compressedBudget);
        BoundedUtf8LineReader reader = new BoundedUtf8LineReader(opened.input(), cancel)) {
      truncated = opened.tailLimited();
      tailLimited = opened.tailLimited();
      LogLine line;
      while ((line = reader.readLine()) != null) {
        truncated |= line.truncated();
        if (line.truncated()) {
          malformedRecords++;
          continue;
        }
        String raw = line.text().trim();
        if (raw.isEmpty()) {
          continue;
        }
        JSONObject value;
        try {
          value = new JSONObject(raw);
        } catch (RuntimeException malformed) {
          malformedRecords++;
          continue;
        }
        Instant timestamp = parseJsonTimestamp(value);
        if (timestamp == null) {
          malformedRecords++;
          continue;
        }
        if ((cutoff != null && timestamp.isBefore(cutoff))
            || (captureTime != null && timestamp.isAfter(captureTime))) {
          continue;
        }
        if (requiredSession != null
            && !requiredSession.isEmpty()
            && !requiredSession.equals(extractProcessSession(value))) {
          continue;
        }
        byte[] sanitized =
            (sanitizer.sanitizeJsonObject(value).toString() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        records.add(sanitized);
      }
    }
    return new ReadFileResult(
        records,
        malformedRecords,
        truncated || records.truncated(),
        tailLimited || records.evicted());
  }

  private static boolean finishRecord(
      RecordAccumulator record,
      RecordTail records,
      String requiredSession,
      ExportSanitizer sanitizer) {
    if (!record.eligible()) {
      return record.truncated();
    }
    String raw = record.text();
    if (requiredSession != null
        && !requiredSession.isEmpty()
        && !hasTraceSession(raw, requiredSession)) {
      return record.truncated();
    }
    byte[] sanitized = sanitizer.sanitize(raw).getBytes(StandardCharsets.UTF_8);
    records.add(sanitized);
    return record.truncated();
  }

  private static boolean hasTraceSession(String record, String requiredSession) {
    int lineEnd = record.indexOf('\n');
    String header = lineEnd < 0 ? record : record.substring(0, lineEnd);
    Matcher token = TRACE_SESSION_TOKEN.matcher(header);
    return token.find() && requiredSession.equals(token.group(1));
  }

  private static OpenedLogInput openLogInput(
      SourceFile file, ReadBudget budget, ReadBudget compressedBudget) throws IOException {
    if (budget.remaining() <= 0) {
      throw new SourceLimitExceededException("scan-limit");
    }
    FileChannel channel = openVerifiedSource(file);
    if (isGzip(file.path())) {
      InputStream raw = Channels.newInputStream(channel);
      try {
        InputStream gzip =
            new GZIPInputStream(
                new BudgetInputStream(raw, compressedBudget, "compressed-input-limit"), 8192);
        return new OpenedLogInput(new BudgetInputStream(gzip, budget, "inflation-limit"), false);
      } catch (IOException | RuntimeException e) {
        raw.close();
        throw e;
      }
    }

    try {
      long size = channel.size();
      long start = Math.max(0L, size - budget.remaining());
      channel.position(start);
      return new OpenedLogInput(
          new BudgetInputStream(Channels.newInputStream(channel), budget, "scan-limit"), start > 0);
    } catch (IOException | RuntimeException e) {
      channel.close();
      throw e;
    }
  }

  private static FileChannel openVerifiedSource(SourceFile source) throws IOException {
    Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    FileChannel channel = FileChannel.open(source.path(), options);
    try {
      FileIdentity current = captureRegularFileIdentity(source.path());
      if (!sameIdentity(source.identity(), current)) {
        throw new IOException("diagnostic source changed after enumeration");
      }
      return channel;
    } catch (IOException | RuntimeException e) {
      channel.close();
      throw e;
    }
  }

  private static void writeTailEntry(
      ZipOutputStream out, String name, RecordTail records, BooleanSupplier cancel)
      throws IOException {
    out.putNextEntry(new ZipEntry(name));
    for (byte[] record : records.records()) {
      throwIfCancelled(cancel);
      out.write(record);
    }
    out.closeEntry();
  }

  private static LogFileSet listLogFiles(
      Path logsDirectory, String stem, BooleanSupplier cancel) throws IOException {
    BooleanSupplier cancelled = cancel == null ? () -> false : cancel;
    throwIfCancelled(cancelled);
    List<SourceFile> files = new ArrayList<>();
    if (!Files.isDirectory(logsDirectory, LinkOption.NOFOLLOW_LINKS)) {
      return new LogFileSet(List.of(), false);
    }
    Path logsRoot = logsDirectory.toRealPath();
    Path active = logsDirectory.resolve(stem + ".log");
    if (Files.isRegularFile(active, LinkOption.NOFOLLOW_LINKS)) {
      files.add(inspectSourceFile(logsRoot, active));
    }
    Path archive = logsDirectory.resolve("archive");
    boolean truncated = false;
    if (Files.isDirectory(archive, LinkOption.NOFOLLOW_LINKS)) {
      Comparator<SourceFile> oldestFirst =
          Comparator.comparing(SourceFile::lastModified)
              .thenComparing(source -> source.path().getFileName().toString());
      PriorityQueue<SourceFile> newestArchives = new PriorityQueue<>(oldestFirst);
      int visited = 0;
      String archivePrefix = stem + ".";
      String archiveSuffix = ".log.gz";
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(archive)) {
        for (Path path : stream) {
          throwIfCancelled(cancelled);
          if (++visited > MAX_ARCHIVE_DIRECTORY_ENTRIES) {
            truncated = true;
            break;
          }
          String fileName = path.getFileName().toString();
          if (fileName.startsWith(archivePrefix)
              && fileName.endsWith(archiveSuffix)
              && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            newestArchives.add(inspectSourceFile(logsRoot, path));
            if (newestArchives.size() > MAX_ARCHIVE_FILES_PER_SOURCE) {
              newestArchives.remove();
              truncated = true;
            }
          }
        }
      }
      files.addAll(newestArchives);
    }
    files.sort(
        Comparator.comparing(SourceFile::lastModified)
            .thenComparing(source -> source.path().getFileName().toString())
            .reversed());
    return new LogFileSet(List.copyOf(files), truncated);
  }

  private static SourceFile inspectSourceFile(Path logsRoot, Path path) throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile()) {
      throw new IOException("diagnostic source is not a regular file");
    }
    Path realPath = path.toRealPath();
    if (!realPath.startsWith(logsRoot)) {
      throw new IOException("diagnostic source escapes the logs directory");
    }
    FileIdentity identity =
        new FileIdentity(attributes.fileKey(), realPath, attributes.creationTime());
    return new SourceFile(
        path, identity, attributes.size(), attributes.lastModifiedTime().toInstant());
  }

  private JSONObject renderManifest(
      DiagnosticBundleRequest request,
      LoggingRuntime.TraceSessionSnapshot trace,
      LoggingSettings settings,
      Instant captureTime,
      ExportSanitizer sanitizer,
      JSONObject sources,
      String processAlias) {
    JSONObject manifest = new JSONObject();
    manifest.put("applicationSession", request.runtime().applicationLogSessionId());
    manifest.put("traceSession", trace.active() ? trace.sessionId() : JSONObject.NULL);
    manifest.put("processSession", processAlias == null ? JSONObject.NULL : processAlias);
    manifest.put("captureTime", captureTime.toString());
    manifest.put("appVersion", sanitizer.sanitizeText(request.appVersion()));
    manifest.put("sanitizerVersion", ExportSanitizer.VERSION);
    manifest.put("diagnosticsEnabled", settings.diagnosticsEnabled());
    manifest.put("fullTraceActive", trace.active());
    manifest.put("diagnosticModules", wireNames(settings.diagnosticModules()));
    manifest.put("preferredTraceScopes", wireScopeNames(settings.preferredTraceScopes()));
    manifest.put("activeTraceScopes", wireScopeNames(trace.scopes()));
    JSONArray aliases = new JSONArray();
    for (String alias : sanitizer.aliases().values()) {
      aliases.put(alias);
    }
    manifest.put("aliases", aliases);
    manifest.put("sources", sources);
    return manifest;
  }

  private static JSONObject renderObserved(
      ReadBoardLoggingSnapshot snapshot, ExportSanitizer sanitizer) {
    JSONObject json = new JSONObject();
    json.put("attached", snapshot.attached());
    json.put("contractLaunch", snapshot.contractLaunch());
    json.put("status", snapshot.status().name());
    String processSession = snapshot.processSessionId();
    json.put(
        "processSessionId",
        processSession == null || processSession.isEmpty()
            ? JSONObject.NULL
            : sanitizer.alias("session", processSession));
    json.put("capabilityKnown", snapshot.capabilityKnown());
    json.put("diagnosticsDesired", snapshot.desired().diagnostics);
    json.put("captureDesired", snapshot.desired().capture);
    json.put("traceDesired", snapshot.desired().trace);
    json.put("observedDiagnostics", token(snapshot.observedDiagnostics()));
    json.put("observedCapture", token(snapshot.observedCapture()));
    json.put("observedTrace", token(snapshot.observedTrace()));
    json.put(
        "persistence",
        snapshot.persistence() == null
            ? JSONObject.NULL
            : snapshot.persistence().name().toLowerCase(Locale.ROOT).replace('_', '-'));
    json.put("dropCount", snapshot.dropCount());
    json.put(
        "reason",
        snapshot.reason() == null
            ? JSONObject.NULL
            : snapshot.reason().name().toLowerCase(Locale.ROOT).replace('_', '-'));
    json.put("captureSummary", sanitizer.sanitizeText(snapshot.captureSummary()));
    return json;
  }

  private static String token(ReadBoardLoggingProtocol.Toggle toggle) {
    return toggle == null ? "unknown" : toggle.name().toLowerCase(Locale.ROOT);
  }

  private static JSONArray wireNames(Set<DiagnosticModule> modules) {
    JSONArray array = new JSONArray();
    for (DiagnosticModule module : modules) {
      array.put(module.wireName());
    }
    return array;
  }

  private static JSONArray wireScopeNames(Set<TraceScope> scopes) {
    JSONArray array = new JSONArray();
    for (TraceScope scope : scopes) {
      array.put(scope.wireName());
    }
    return array;
  }

  private Path publishAtomically(
      CreatedPartial partial, OutputRoot outputRoot, Instant captureTime) throws IOException {
    requirePathNamesLockedFile(partial.path(), partial);
    if (!hasDirectoryIdentity(outputRoot)) {
      throw new IOException("diagnostic output directory changed before publication");
    }
    for (int attempt = 0; attempt < 32; attempt++) {
      Path target =
          collisionSafeTarget(outputRoot.path(), bundleNameSupplier.fileName(captureTime));
      try {
        // A same-directory hard link is an atomic publication of the already-complete bytes and,
        // unlike ATOMIC_MOVE, has a specified no-replace contract when the target exists.
        Files.createLink(target, partial.path());
        FileIdentity targetIdentity = null;
        try {
          targetIdentity = captureRegularFileIdentity(target);
          requirePathNamesLockedFile(target, partial);
          requirePathNamesLockedFile(partial.path(), partial);
          if (!Files.isSameFile(partial.path(), target)) {
            throw new IOException("published diagnostic package identity mismatch");
          }
          deletePartialIfOwned(partial);
          requirePathNamesLockedFile(target, partial);
          return target;
        } catch (IOException | RuntimeException | Error e) {
          if (targetIdentity != null) {
            try {
              deleteIfIdentity(target, targetIdentity);
            } catch (IOException | RuntimeException | Error cleanupFailure) {
              e.addSuppressed(cleanupFailure);
            }
          }
          throw e;
        }
      } catch (FileAlreadyExistsException e) {
        // A UUID collision or hostile pre-creation must never cause an overwrite.
      } catch (UnsupportedOperationException e) {
        throw new IOException("collision-safe hard-link publication is required", e);
      }
    }
    throw new IOException("could not allocate a collision-safe diagnostic package name");
  }

  private OutputRoot prepareOutputDirectory() throws IOException {
    if (!Files.exists(outputDirectory, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectories(outputDirectory);
    }
    if (!Files.isDirectory(outputDirectory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("diagnostic output path must be a real directory");
    }
    FileIdentity requestedIdentity = captureDirectoryIdentity(outputDirectory);
    Path realPath = requestedIdentity.realPath();
    FileIdentity canonicalIdentity = captureDirectoryIdentity(realPath);
    if (!sameFileObject(requestedIdentity, canonicalIdentity)) {
      throw new IOException("diagnostic output directory changed during preparation");
    }
    return new OutputRoot(realPath, canonicalIdentity);
  }

  private CreatedPartial createPartial(OutputRoot outputRoot) throws IOException {
    Set<OpenOption> options =
        new HashSet<>(
            Set.of(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS));
    boolean deleteOnClose = partialFileCleanupStrategy.deleteOnClose();
    if (deleteOnClose) {
      // This binds Windows cleanup to the opened file object. If the partial is renamed and a
      // different regular file is installed at its old pathname, closing this channel deletes
      // only the renamed partial and cannot delete the replacement.
      options.add(StandardOpenOption.DELETE_ON_CLOSE);
    }
    for (int attempt = 0; attempt < 32; attempt++) {
      if (!hasDirectoryIdentity(outputRoot)) {
        throw new IOException("diagnostic output directory changed before temporary creation");
      }
      Path path =
          outputRoot
              .path()
              .resolve(".lizzie-diagnostics-" + UUID.randomUUID() + ".partial");
      FileChannel channel;
      try {
        channel = FileChannel.open(path, options);
      } catch (FileAlreadyExistsException e) {
        continue;
      }
      FileLock ownershipLock = null;
      CreatedPartial partial = null;
      try {
        ownershipLock = channel.tryLock();
        if (ownershipLock == null) {
          throw new IOException("could not lock diagnostic temporary file");
        }
        partial = new CreatedPartial(path, channel, ownershipLock, deleteOnClose);
        partialFileObserver.created(path);
        requirePathNamesLockedFile(path, partial);
        if (!hasDirectoryIdentity(outputRoot)) {
          throw new IOException("diagnostic output directory changed during temporary creation");
        }
        return partial;
      } catch (IOException | RuntimeException | Error e) {
        if (partial != null) {
          cleanupFailedPartial(partial, e);
        }
        if (ownershipLock != null) {
          try {
            ownershipLock.release();
          } catch (IOException cleanupFailure) {
            e.addSuppressed(cleanupFailure);
          }
        }
        try {
          channel.close();
        } catch (IOException cleanupFailure) {
          e.addSuppressed(cleanupFailure);
        }
        throw e;
      }
    }
    throw new IOException("could not allocate a collision-safe diagnostic temporary file");
  }

  private static FileIdentity captureRegularFileIdentity(Path path) throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile()) {
      throw new IOException("diagnostic file must be a regular file");
    }
    return new FileIdentity(attributes.fileKey(), path.toRealPath(), attributes.creationTime());
  }

  private static FileIdentity captureDirectoryIdentity(Path path) throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isDirectory()) {
      throw new IOException("diagnostic output path must remain a real directory");
    }
    return new FileIdentity(attributes.fileKey(), path.toRealPath(), attributes.creationTime());
  }

  private static boolean sameIdentity(FileIdentity expected, FileIdentity actual) {
    if (!expected.realPath().equals(actual.realPath())) {
      return false;
    }
    return sameFileObject(expected, actual);
  }

  private static boolean sameFileObject(FileIdentity expected, FileIdentity actual) {
    if (expected.fileKey() != null || actual.fileKey() != null) {
      return expected.fileKey() != null
          && actual.fileKey() != null
          && expected.fileKey().equals(actual.fileKey());
    }
    return expected.creationTime().equals(actual.creationTime());
  }

  private static boolean hasIdentity(Path path, FileIdentity expected) {
    try {
      return sameIdentity(expected, captureRegularFileIdentity(path));
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  private static boolean hasDirectoryIdentity(OutputRoot outputRoot) {
    try {
      return sameIdentity(outputRoot.identity(), captureDirectoryIdentity(outputRoot.path()));
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  private static void requirePathNamesLockedFile(Path path, CreatedPartial partial)
      throws IOException {
    FileLock ownershipLock = partial.ownershipLock();
    if (ownershipLock == null || !ownershipLock.isValid()) {
      throw new IOException("diagnostic temporary file ownership lock is not valid");
    }
    Set<OpenOption> options =
        Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    try (FileChannel probe = FileChannel.open(path, options)) {
      FileLock competing = null;
      try {
        competing =
            probe.tryLock(ownershipLock.position(), ownershipLock.size(), ownershipLock.isShared());
      } catch (OverlappingFileLockException expected) {
        return;
      } finally {
        if (competing != null) {
          competing.release();
        }
      }
    }
    throw new IOException("diagnostic path does not name the locked temporary file");
  }

  private static void deletePartialIfOwned(CreatedPartial partial) throws IOException {
    if (partial.deleteOnClose()) {
      return;
    }
    requirePathNamesLockedFile(partial.path(), partial);
    Files.deleteIfExists(partial.path());
  }

  private static void cleanupFailedPartial(CreatedPartial partial, Throwable failure) {
    // On Unix, another process with directory-write permission can rename an open file even while
    // this process owns its advisory lock. Path-based cleanup can no longer discover that inode.
    // Truncating through the original channel first scrubs every renamed path and hard link that
    // still refers to it. This also provides defense in depth if a future sanitizer misses a
    // credential: a failed export cannot leave the bundle bytes persistently recoverable merely
    // by changing the temporary pathname while it is streamed.
    try {
      partial.channel().truncate(0);
      partial.channel().force(true);
    } catch (IOException | RuntimeException | Error cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
    try {
      deletePartialIfOwned(partial);
    } catch (IOException | RuntimeException | Error cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
  }

  private static void deleteIfIdentity(Path path, FileIdentity identity) throws IOException {
    if (hasIdentity(path, identity)) {
      Files.deleteIfExists(path);
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").startsWith("Windows");
  }

  private static Path collisionSafeTarget(Path outputRoot, String fileName) throws IOException {
    if (fileName == null || fileName.isBlank()) {
      throw new IOException("diagnostic package name must not be empty");
    }
    Path relative;
    try {
      relative = Path.of(fileName);
    } catch (RuntimeException e) {
      throw new IOException("diagnostic package name is invalid", e);
    }
    if (relative.isAbsolute()
        || relative.getNameCount() != 1
        || !relative.getFileName().toString().equals(fileName)) {
      throw new IOException("diagnostic package name must be a single file name");
    }
    return outputRoot.resolve(relative);
  }

  private static String randomZipName(Instant captureTime) {
    String stamp = FILE_TIMESTAMP.format(captureTime);
    return "lizzie-diagnostics-" + stamp + "-" + UUID.randomUUID() + ".zip";
  }

  private static void writeTextEntry(ZipOutputStream out, String name, String text)
      throws IOException {
    out.putNextEntry(new ZipEntry(name));
    out.write(text.getBytes(StandardCharsets.UTF_8));
    out.closeEntry();
  }

  private static JSONObject status(
      String status, long windowHours, long capBytes, boolean truncated) {
    JSONObject json = new JSONObject();
    json.put("status", status);
    if (windowHours > 0) {
      json.put("windowHours", windowHours);
    }
    json.put("capBytes", capBytes);
    json.put("truncated", truncated);
    return json;
  }

  private static JSONObject sourceRecord(
      boolean requested,
      String status,
      long bytes,
      long windowHours,
      long capBytes,
      String namespace,
      String session,
      String reason,
      boolean truncated) {
    JSONObject json = new JSONObject();
    boolean included = "included".equals(status) || "truncated".equals(status);
    boolean omitted = "omitted".equals(status);
    boolean failed = "failed".equals(status) || "error".equals(status);
    json.put("requested", requested);
    json.put("included", included);
    json.put("status", status);
    json.put("bytes", bytes);
    json.put("omitted", omitted);
    json.put("failed", failed);
    json.put("truncated", truncated || "truncated".equals(status));
    json.put("reason", reason == null ? "" : reason);
    if (windowHours > 0) {
      json.put("windowHours", windowHours);
    }
    json.put("capBytes", capBytes);
    json.put("namespace", namespace);
    if (session != null && !session.isEmpty()) {
      json.put("session", session);
    }
    return json;
  }

  private static String hostTraceSourceName(TraceScope scope) {
    return switch (scope) {
      case ENGINE_GTP -> "lizzie-engine-trace";
      case READBOARD_YIKE -> "lizzie-readboard-trace";
      case NETWORK_WEBSOCKET -> "lizzie-network-trace";
    };
  }

  private static String stem(String fileName) {
    return fileName.endsWith(".log") ? fileName.substring(0, fileName.length() - 4) : fileName;
  }

  private static JSONObject sanitizeJson(JSONObject value, ExportSanitizer sanitizer) {
    return (JSONObject) sanitizeJsonValue(value, sanitizer);
  }

  private static Object sanitizeJsonValue(Object value, ExportSanitizer sanitizer) {
    if (value instanceof JSONObject object) {
      JSONObject sanitized = new JSONObject();
      for (String key : object.keySet()) {
        sanitized.put(key, sanitizeJsonValue(object.get(key), sanitizer));
      }
      return sanitized;
    }
    if (value instanceof JSONArray array) {
      JSONArray sanitized = new JSONArray();
      for (int i = 0; i < array.length(); i++) {
        sanitized.put(sanitizeJsonValue(array.get(i), sanitizer));
      }
      return sanitized;
    }
    if (value instanceof String text) {
      return sanitizer.sanitize(text);
    }
    return value;
  }

  private static Instant parseTimestamp(String value) {
    try {
      return LocalDateTime.parse(value, LOG_TIMESTAMP)
          .atZone(ZoneId.systemDefault())
          .toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static Instant parseJsonTimestamp(JSONObject value) {
    String raw = extractTaggedString(value.opt("ts"));
    if (raw == null || raw.isEmpty()) {
      raw = extractTaggedString(value.opt("timestamp"));
    }
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    try {
      return Instant.parse(raw);
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  private static String extractProcessSession(JSONObject value) {
    return extractTaggedString(value.opt("processSessionId"));
  }

  private static String extractTaggedString(Object value) {
    if (value == null || value == JSONObject.NULL) {
      return null;
    }
    if (value instanceof JSONObject tagged) {
      Object nested = tagged.opt("value");
      return nested == null || nested == JSONObject.NULL ? null : String.valueOf(nested);
    }
    return String.valueOf(value);
  }

  private static String processSession(DiagnosticBundleRequest request) {
    String value = request.readBoardLogging().processSessionId();
    return value == null || value.isEmpty() ? null : value;
  }

  private static String failureReason(IOException failure) {
    if (failure instanceof java.nio.file.NoSuchFileException) {
      return "missing";
    }
    if (failure instanceof java.nio.file.AccessDeniedException) {
      return "unreadable";
    }
    return "unreadable";
  }

  private static boolean isPng(String fileName) {
    return fileName.toLowerCase(Locale.ROOT).endsWith(".png");
  }

  private static long scanBudget(long capBytes) {
    if (capBytes <= 0) {
      return 0;
    }
    long multiplied =
        capBytes > MAX_SOURCE_SCAN_BYTES / SOURCE_SCAN_MULTIPLIER
            ? MAX_SOURCE_SCAN_BYTES
            : capBytes * SOURCE_SCAN_MULTIPLIER;
    return Math.min(MAX_SOURCE_SCAN_BYTES, Math.max(MIN_SOURCE_SCAN_BYTES, multiplied));
  }

  private static long saturatingAdd(long left, long right) {
    if (right > 0 && left > Long.MAX_VALUE - right) {
      return Long.MAX_VALUE;
    }
    return left + right;
  }

  private static boolean isGzip(Path file) {
    return file.getFileName().toString().endsWith(".gz");
  }

  private static void throwIfCancelled(BooleanSupplier cancelled) throws IOException {
    if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
      throw new ExportCancelledException();
    }
  }

  @FunctionalInterface
  interface PartialFileObserver {
    void created(Path path) throws IOException;

    default void payloadWritten(Path path) throws IOException {}
  }

  @FunctionalInterface
  interface BundleNameSupplier {
    String fileName(Instant captureTime);
  }

  enum PartialFileCleanupStrategy {
    DELETE_ON_CLOSE(true),
    PATH_DELETE(false);

    private final boolean deleteOnClose;

    PartialFileCleanupStrategy(boolean deleteOnClose) {
      this.deleteOnClose = deleteOnClose;
    }

    private boolean deleteOnClose() {
      return deleteOnClose;
    }

    private static PartialFileCleanupStrategy systemDefault() {
      return isWindows() ? DELETE_ON_CLOSE : PATH_DELETE;
    }
  }

  private record LogLine(String text, boolean truncated) {}

  private record FileIdentity(Object fileKey, Path realPath, FileTime creationTime) {}

  private record CreatedPartial(
      Path path, FileChannel channel, FileLock ownershipLock, boolean deleteOnClose) {}

  private record OutputRoot(Path path, FileIdentity identity) {}

  private record SourceFile(
      Path path, FileIdentity identity, long size, Instant lastModified) {}

  private record LogFileSet(List<SourceFile> paths, boolean truncated) {}

  private record CaptureEventSet(
      List<CaptureEventSource> events, boolean truncated, FileIdentity rootIdentity) {}

  private record CaptureEventSource(
      String name,
      Path path,
      FileIdentity identity,
      List<SourceFile> files,
      boolean truncated) {}

  private record CapturePayload(String entryName, byte[] bytes) {}

  private record ReadFileResult(
      RecordTail records,
      int malformedRecords,
      boolean truncated,
      boolean newerHistoryOmitted) {}

  private static final class RecordAccumulator {
    private final boolean eligible;
    private final StringBuilder value = new StringBuilder();
    private boolean truncated;
    private boolean markerWritten;

    private RecordAccumulator(boolean eligible) {
      this.eligible = eligible;
    }

    private void append(LogLine line) {
      truncated |= line.truncated();
      if (!eligible || markerWritten) {
        return;
      }
      int remaining = MAX_RECORD_CHARS - value.length();
      if (remaining <= 0) {
        truncate();
        return;
      }
      int lineLength = line.text().length();
      int copied = Math.min(lineLength, Math.max(0, remaining - 1));
      value.append(line.text(), 0, copied).append('\n');
      if (copied < lineLength) {
        truncate();
      }
    }

    private void truncate() {
      truncated = true;
      if (!markerWritten) {
        int maximumBody = Math.max(0, MAX_RECORD_CHARS - RECORD_TRUNCATION_MARKER.length());
        if (value.length() > maximumBody) {
          value.setLength(maximumBody);
        }
        value.append(RECORD_TRUNCATION_MARKER);
        markerWritten = true;
      }
    }

    private boolean eligible() {
      return eligible;
    }

    private boolean truncated() {
      return truncated;
    }

    private String text() {
      return value.toString();
    }
  }

  private static final class RecordTail {
    private final long limit;
    private final Deque<byte[]> records = new ArrayDeque<>();
    private long bytes;
    private boolean truncated;
    private boolean evicted;

    private RecordTail(long limit) {
      this.limit = Math.max(0L, limit);
    }

    private void add(byte[] record) {
      if (record.length > limit) {
        truncated = true;
        return;
      }
      records.addLast(record);
      bytes += record.length;
      while (bytes > limit && !records.isEmpty()) {
        bytes -= records.removeFirst().length;
        truncated = true;
        evicted = true;
      }
    }

    private void prepend(RecordTail older) {
      Iterator<byte[]> iterator = older.records.descendingIterator();
      while (iterator.hasNext()) {
        byte[] record = iterator.next();
        records.addFirst(record);
        bytes += record.length;
      }
      truncated |= older.truncated;
      while (bytes > limit && !records.isEmpty()) {
        bytes -= records.removeFirst().length;
        truncated = true;
        evicted = true;
      }
    }

    private long remaining() {
      return Math.max(0L, limit - bytes);
    }

    private long bytes() {
      return bytes;
    }

    private boolean truncated() {
      return truncated;
    }

    private boolean evicted() {
      return evicted;
    }

    private Iterable<byte[]> records() {
      return records;
    }
  }

  private static final class ReadBudget {
    private final long limit;
    private long remaining;

    private ReadBudget(long limit) {
      this.limit = Math.max(0L, limit);
      this.remaining = this.limit;
    }

    private long remaining() {
      return remaining;
    }

    private long limit() {
      return limit;
    }

    private long consumed() {
      return limit - remaining;
    }

    private void consume(long count) {
      remaining = Math.max(0L, remaining - count);
    }
  }

  private static final class BudgetInputStream extends InputStream {
    private final InputStream delegate;
    private final ReadBudget budget;
    private final String limitReason;

    private BudgetInputStream(InputStream delegate, ReadBudget budget, String limitReason) {
      this.delegate = delegate;
      this.budget = budget;
      this.limitReason = limitReason;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int count = read(one, 0, 1);
      return count < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      if (length == 0) {
        return 0;
      }
      long remaining = budget.remaining();
      if (remaining <= 0) {
        if (delegate.read() < 0) {
          return -1;
        }
        throw new SourceLimitExceededException(limitReason);
      }
      int allowed = (int) Math.min((long) length, remaining);
      int count = delegate.read(bytes, offset, allowed);
      if (count > 0) {
        budget.consume(count);
      }
      return count;
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }

  private static final class CloseShieldOutputStream extends FilterOutputStream {
    private CloseShieldOutputStream(OutputStream delegate) {
      super(delegate);
    }

    @Override
    public void close() throws IOException {
      flush();
    }
  }

  private static final class BoundedUtf8LineReader implements AutoCloseable {
    private final InputStream input;
    private final BooleanSupplier cancelled;
    private final byte[] buffer = new byte[8192];
    private int offset;
    private int length;

    private BoundedUtf8LineReader(InputStream input, BooleanSupplier cancelled) {
      this.input = input;
      this.cancelled = cancelled;
    }

    private LogLine readLine() throws IOException {
      ByteArrayOutputStream line = new ByteArrayOutputStream(256);
      boolean sawInput = false;
      boolean truncated = false;
      while (true) {
        int next = readByte();
        if (next < 0) {
          if (!sawInput) {
            return null;
          }
          break;
        }
        sawInput = true;
        if (next == '\n') {
          break;
        }
        if (next == '\r') {
          continue;
        }
        if (line.size() < MAX_LINE_BYTES) {
          line.write(next);
        } else {
          truncated = true;
        }
      }
      String text = line.toString(StandardCharsets.UTF_8);
      if (truncated) {
        text += LINE_TRUNCATION_MARKER;
      }
      return new LogLine(text, truncated);
    }

    private int readByte() throws IOException {
      if (offset >= length) {
        throwIfCancelled(cancelled);
        length = input.read(buffer);
        offset = 0;
        if (length < 0) {
          return -1;
        }
      }
      return buffer[offset++] & 0xff;
    }

    @Override
    public void close() throws IOException {
      input.close();
    }
  }

  private static final class OpenedLogInput implements AutoCloseable {
    private final InputStream input;
    private final boolean tailLimited;

    private OpenedLogInput(InputStream input, boolean tailLimited) {
      this.input = input;
      this.tailLimited = tailLimited;
    }

    private InputStream input() {
      return input;
    }

    private boolean tailLimited() {
      return tailLimited;
    }

    @Override
    public void close() throws IOException {
      input.close();
    }
  }

  private static final class SourceLimitExceededException extends IOException {
    private static final long serialVersionUID = 1L;
    private final String reason;

    private SourceLimitExceededException(String reason) {
      super("diagnostic source exceeded its " + reason);
      this.reason = reason;
    }

    private String reason() {
      return reason;
    }
  }

  private static final class ExportCancelledException extends IOException {
    private static final long serialVersionUID = 1L;

    private ExportCancelledException() {
      super("diagnostic export cancelled");
    }
  }
}
