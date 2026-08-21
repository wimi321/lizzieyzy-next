package featurecat.lizzie.logging;

import featurecat.lizzie.analysis.SyncDiagnosticsExportSnapshot;
import featurecat.lizzie.analysis.SyncDiagnosticsExporter;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
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

  private static final Logger LOG = LoggerFactory.getLogger(LogCategories.DIAGNOSTICS);
  private static final DateTimeFormatter FILE_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
  private static final DateTimeFormatter LOG_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  private final Path outputDirectory;
  private final DiagnosticBundleLimits limits;

  public DiagnosticBundleExporter(Path outputDirectory) {
    this(outputDirectory, DiagnosticBundleLimits.production());
  }

  public DiagnosticBundleExporter(Path outputDirectory, DiagnosticBundleLimits limits) {
    this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  public static Path defaultOutputDirectory(Path workDirectory) {
    return Objects.requireNonNull(workDirectory, "workDirectory").resolve("diagnostics");
  }

  public long estimateUncompressedBytes(DiagnosticBundleRequest request) throws IOException {
    Objects.requireNonNull(request, "request");
    long total = 0;
    Path logs = request.runtime().logsDirectory();
    total += estimateLogBytes(logs, "app", limits.appCapBytes());
    total += estimateLogBytes(logs, "crash", limits.crashCapBytes());
    if (!request.rawScopes().isEmpty() && request.runtime().currentTraceSessionId() != null) {
      for (TraceScope scope : request.rawScopes()) {
        total += estimateLogBytes(logs, stem(scope.fileName()), limits.rawCapBytes());
      }
    }
    total += 64 * 1024;
    return total;
  }

  private static long estimateLogBytes(Path logsDirectory, String stem, long cap)
      throws IOException {
    long total = 0;
    for (Path file : listLogFiles(logsDirectory, stem)) {
      total += Files.size(file);
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
    Files.createDirectories(outputDirectory);
    Instant captureTime = Instant.now();
    Path published = uniqueZipPath(captureTime);
    Path temporary = outputDirectory.resolve(published.getFileName().toString() + ".partial");
    ExportSanitizer sanitizer = new ExportSanitizer();
    JSONObject sources = new JSONObject();
    try {
      try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temporary))) {
        copyLogSource(
            out,
            "app.log",
            "app",
            request.runtime().logsDirectory(),
            "app",
            captureTime.minusSeconds(limits.appWindowHours() * 3600),
            limits.appWindowHours(),
            limits.appCapBytes(),
            sanitizer,
            sources,
            null);
        throwIfCancelled(cancel, temporary);
        copyLogSource(
            out,
            "crash.log",
            "crash",
            request.runtime().logsDirectory(),
            "crash",
            captureTime.minusSeconds(limits.crashWindowHours() * 3600),
            limits.crashWindowHours(),
            limits.crashCapBytes(),
            sanitizer,
            sources,
            null);
        throwIfCancelled(cancel, temporary);
        copyRawTraces(out, request, sanitizer, sources, captureTime, cancel, temporary);
        throwIfCancelled(cancel, temporary);
        JSONObject projected = ConfigExportProjection.project(request.config());
        writeTextEntry(out, "config.json", sanitizeJson(projected, sanitizer).toString(2));
        sources.put("config", status("included", 0, 0, false));
        SyncDiagnosticsExportSnapshot snapshot =
            request.snapshot() == null
                ? new SyncDiagnosticsExportSnapshot(
                    captureTime.toEpochMilli(), null, null, null, null, null)
                : request.snapshot();
        SyncDiagnosticsExporter.writeSnapshotEntries(out, snapshot, sanitizer.shareTime());
        sources.put("snapshots", status("included", 0, 0, false));
        sources.put("environment", status("included", 0, 0, false));
        throwIfCancelled(cancel, temporary);
        writeTextEntry(
            out,
            "manifest.json",
            renderManifest(request, captureTime, sanitizer, sources).toString(2));
      }
      throwIfCancelled(cancel, temporary);
      try {
        Files.move(temporary, published, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.deleteIfExists(temporary);
        throw new IOException("atomic publication is required", e);
      }
      LOG.info("diagnostic package published file={}", published.getFileName());
      return published;
    } catch (IOException e) {
      Files.deleteIfExists(temporary);
      throw e;
    }
  }

  private void copyRawTraces(
      ZipOutputStream out,
      DiagnosticBundleRequest request,
      ExportSanitizer sanitizer,
      JSONObject sources,
      Instant captureTime,
      BooleanSupplier cancel,
      Path temporary)
      throws IOException {
    Set<TraceScope> rawScopes = request.rawScopes();
    for (TraceScope scope : TraceScope.values()) {
      String sourceName = sourceName(scope);
      if (!rawScopes.contains(scope)) {
        JSONObject omitted = status("omitted", 0, limits.rawCapBytes(), false);
        omitted.put("reason", "not-requested");
        sources.put(sourceName, omitted);
        continue;
      }
      throwIfCancelled(cancel, temporary);
      String session = request.runtime().currentTraceSessionId();
      if (session == null) {
        JSONObject omitted = status("omitted", 0, limits.rawCapBytes(), false);
        omitted.put("reason", "no-active-session");
        sources.put(sourceName, omitted);
        continue;
      }
      copyLogSource(
          out,
          scope.fileName(),
          sourceName,
          request.runtime().logsDirectory(),
          stem(scope.fileName()),
          Instant.EPOCH,
          0,
          limits.rawCapBytes(),
          sanitizer,
          sources,
          session);
    }
  }

  private void copyLogSource(
      ZipOutputStream out,
      String entryName,
      String sourceName,
      Path logsDirectory,
      String stem,
      Instant cutoff,
      long windowHours,
      long capBytes,
      ExportSanitizer sanitizer,
      JSONObject sources,
      String requiredSession)
      throws IOException {
    JSONObject source = status("included", windowHours, capBytes, false);
    Path chrono = null;
    Path trimmed = null;
    try {
      List<Path> files = listLogFiles(logsDirectory, stem);
      if (files.isEmpty()) {
        source.put("status", "error");
        source.put("reason", "missing");
        sources.put(sourceName, source);
        return;
      }
      chrono = Files.createTempFile(outputDirectory, sourceName, ".src");
      try (BufferedWriter writer = Files.newBufferedWriter(chrono, StandardCharsets.UTF_8)) {
        for (int i = files.size() - 1; i >= 0; i--) {
          try (BufferedReader reader = openLogReader(files.get(i))) {
            String line;
            while ((line = reader.readLine()) != null) {
              if (!inWindow(line, cutoff) || !matchesSession(line, requiredSession)) {
                continue;
              }
              String sanitized = sanitizer.sanitize(line);
              writer.write(sanitized);
              if (!sanitized.endsWith("\n")) {
                writer.write('\n');
              }
            }
          }
        }
      }
      long size = Files.size(chrono);
      Path publishedSource = chrono;
      boolean truncated = size > capBytes;
      if (truncated) {
        trimmed = Files.createTempFile(outputDirectory, sourceName, ".tail");
        copyCompleteTail(chrono, trimmed, capBytes);
        publishedSource = trimmed;
        size = Files.size(trimmed);
      }
      writeFileEntry(out, entryName, publishedSource);
      source.put("bytes", size);
      source.put("truncated", truncated);
      if (truncated) {
        source.put("status", "truncated");
      }
    } catch (IOException e) {
      source.put("status", "error");
      source.put("reason", e.getClass().getSimpleName());
      sources.put(sourceName, source);
      return;
    } finally {
      if (chrono != null) {
        Files.deleteIfExists(chrono);
      }
      if (trimmed != null) {
        Files.deleteIfExists(trimmed);
      }
    }
    sources.put(sourceName, source);
  }

  private static BufferedReader openLogReader(Path file) throws IOException {
    InputStream raw = Files.newInputStream(file);
    InputStream input =
        file.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
    return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
  }

  private static void copyCompleteTail(Path source, Path dest, long capBytes) throws IOException {
    long size = Files.size(source);
    long start = size <= capBytes ? 0L : size - capBytes;
    if (start > 0) {
      try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
        ByteBuffer one = ByteBuffer.allocate(1);
        while (start < size) {
          channel.position(start);
          one.clear();
          if (channel.read(one) < 1) {
            break;
          }
          one.flip();
          start++;
          if (one.get() == '\n') {
            break;
          }
        }
      }
    }
    try (InputStream input = Files.newInputStream(source);
        OutputStream output = Files.newOutputStream(dest)) {
      input.skipNBytes(start);
      input.transferTo(output);
    }
  }

  private static void writeFileEntry(ZipOutputStream out, String name, Path file)
      throws IOException {
    out.putNextEntry(new ZipEntry(name));
    try (InputStream input = Files.newInputStream(file)) {
      input.transferTo(out);
    }
    out.closeEntry();
  }

  private static List<Path> listLogFiles(Path logsDirectory, String stem) throws IOException {
    List<Path> files = new ArrayList<>();
    Path active = logsDirectory.resolve(stem + ".log");
    if (Files.isRegularFile(active)) {
      files.add(active);
    }
    Path archive = logsDirectory.resolve("archive");
    if (Files.isDirectory(archive)) {
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(archive, stem + ".*.log.gz")) {
        for (Path path : stream) {
          files.add(path);
        }
      }
    }
    files.sort(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());
    files.sort(Comparator.comparing(DiagnosticBundleExporter::lastModified).reversed());
    return files;
  }

  private static Instant lastModified(Path path) {
    try {
      return Files.getLastModifiedTime(path).toInstant();
    } catch (IOException e) {
      return Instant.EPOCH;
    }
  }
  private static boolean inWindow(String line, Instant cutoff) {
    if (cutoff == null || cutoff.equals(Instant.EPOCH)) {
      return true;
    }
    if (line.length() < 23) {
      return true;
    }
    try {
      LocalDateTime parsed = LocalDateTime.parse(line.substring(0, 23), LOG_TIMESTAMP);
      return !parsed.atZone(ZoneId.systemDefault()).toInstant().isBefore(cutoff);
    } catch (DateTimeParseException e) {
      return true;
    }
  }

  private static boolean matchesSession(String line, String requiredSession) {
    if (requiredSession == null || requiredSession.isEmpty()) {
      return true;
    }
    return line.contains(requiredSession);
  }


  private JSONObject renderManifest(
      DiagnosticBundleRequest request,
      Instant captureTime,
      ExportSanitizer sanitizer,
      JSONObject sources) {
    LoggingRuntime runtime = request.runtime();
    LoggingSettings settings = runtime.settings();
    JSONObject manifest = new JSONObject();
    manifest.put("applicationSession", runtime.applicationLogSessionId());
    if (runtime.currentTraceSessionId() == null) {
      manifest.put("traceSession", JSONObject.NULL);
    } else {
      manifest.put("traceSession", runtime.currentTraceSessionId());
    }
    manifest.put("captureTime", captureTime.toString());
    manifest.put("appVersion", request.appVersion());
    manifest.put("sanitizerVersion", ExportSanitizer.VERSION);
    manifest.put("diagnosticsEnabled", settings.diagnosticsEnabled());
    manifest.put("fullTraceActive", runtime.fullTraceActive());
    manifest.put("diagnosticModules", wireNames(settings.diagnosticModules()));
    manifest.put("preferredTraceScopes", wireScopeNames(settings.preferredTraceScopes()));
    manifest.put("activeTraceScopes", wireScopeNames(runtime.activeTraceScopes()));
    JSONArray aliases = new JSONArray();
    for (String alias : sanitizer.aliases().values()) {
      aliases.put(alias);
    }
    manifest.put("aliases", aliases);
    manifest.put("sources", sources);
    return manifest;
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


  private Path uniqueZipPath(Instant captureTime) throws IOException {
    String stamp = FILE_TIMESTAMP.format(captureTime);
    Path candidate = outputDirectory.resolve("lizzie-diagnostics-" + stamp + ".zip");
    int suffix = 2;
    while (Files.exists(candidate)) {
      candidate = outputDirectory.resolve("lizzie-diagnostics-" + stamp + "-" + suffix + ".zip");
      suffix++;
    }
    return candidate;
  }

  private static void writeTextEntry(ZipOutputStream out, String name, String text)
      throws IOException {
    out.putNextEntry(new ZipEntry(name));
    out.write(text.getBytes(StandardCharsets.UTF_8));
    out.closeEntry();
  }

  private static JSONObject status(String status, long windowHours, long capBytes, boolean truncated) {
    JSONObject json = new JSONObject();
    json.put("status", status);
    if (windowHours > 0) {
      json.put("windowHours", windowHours);
    }
    json.put("capBytes", capBytes);
    json.put("truncated", truncated);
    return json;
  }

  private static String sourceName(TraceScope scope) {
    switch (scope) {
      case ENGINE_GTP:
        return "engine-trace";
      case READBOARD_YIKE:
        return "readboard-trace";
      case NETWORK_WEBSOCKET:
        return "network-trace";
      default:
        return scope.wireName();
    }
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

  private static void throwIfCancelled(BooleanSupplier cancelled, Path temporary)
      throws IOException {
    if (cancelled.getAsBoolean()) {
      Files.deleteIfExists(temporary);
      throw new IOException("diagnostic export cancelled");
    }
  }

}
