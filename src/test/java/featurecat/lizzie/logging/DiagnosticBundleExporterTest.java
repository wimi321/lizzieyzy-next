package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.SyncDiagnosticsExportSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class DiagnosticBundleExporterTest {
  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    LoggingRuntime.resetForTests();
  }

  @Test
  void defaultExportWritesAtomicPackageWithManifestAndSafeSources() throws Exception {
    LoggingRuntime runtime = start();
    LoggerFactory.getLogger(LogCategories.APP).info("app-evidence");
    LoggerFactory.getLogger(LogCategories.CRASH).error("crash-evidence");
    runtime.awaitIdle();

    JSONObject config = new JSONObject();
    JSONObject ui = new JSONObject();
    ui.put("board-size", 19);
    ui.put("unknown-support-key", "SHOULD_OMIT");
    ui.put("password", "CANARY_PASSWORD_EXPORT");
    config.put("ui", ui);
    JSONObject leelaz = new JSONObject();
    leelaz.put("command", "C:\\\\Users\\\\alice\\\\katago.exe gtp -model secret.bin");
    config.put("leelaz", leelaz);

    Path diagnostics = DiagnosticBundleExporter.defaultOutputDirectory(tempDir);
    Path zip =
        new DiagnosticBundleExporter(diagnostics)
            .export(
                new DiagnosticBundleRequest(
                    runtime,
                    EnumSet.noneOf(TraceScope.class),
                    config,
                    emptySnapshot(),
                    "next-dev"));

    assertTrue(zip.startsWith(diagnostics), zip.toString());
    assertTrue(zip.getFileName().toString().startsWith("lizzie-diagnostics-"));
    assertTrue(zip.getFileName().toString().endsWith(".zip"));
    assertFalse(Files.exists(Path.of(zip + ".partial")));
    try (var stream = Files.list(diagnostics)) {
      assertEquals(1, stream.filter(path -> path.toString().endsWith(".zip")).count());
    }

    Map<String, String> entries = unzipTextEntries(zip);
    assertTrue(entries.keySet().containsAll(Set.of("manifest.json", "app.log", "crash.log", "config.json", "environment.txt")));
    assertTrue(entries.containsKey("summary.txt"));
    assertTrue(entries.containsKey("sync-context.json"));
    assertFalse(entries.containsKey("engine-trace.log"));
    assertFalse(entries.keySet().stream().anyMatch(name -> name.endsWith(".zip")));

    JSONObject manifest = new JSONObject(entries.get("manifest.json"));
    assertEquals(runtime.applicationLogSessionId(), manifest.getString("applicationSession"));
    assertTrue(manifest.has("traceSession"));
    assertTrue(manifest.isNull("traceSession"));
    assertEquals("next-dev", manifest.getString("appVersion"));
    assertEquals(ExportSanitizer.VERSION, manifest.getString("sanitizerVersion"));
    assertFalse(manifest.getBoolean("fullTraceActive"));
    assertTrue(manifest.getBoolean("diagnosticsEnabled"));
    JSONObject sources = manifest.getJSONObject("sources");
    assertEquals("included", sources.getJSONObject("app").getString("status"));
    assertEquals(24, sources.getJSONObject("app").getInt("windowHours"));
    assertEquals(50L * 1024 * 1024, sources.getJSONObject("app").getLong("capBytes"));
    assertEquals("included", sources.getJSONObject("crash").getString("status"));
    assertEquals(10L * 1024 * 1024, sources.getJSONObject("crash").getLong("capBytes"));
    assertEquals("omitted", sources.getJSONObject("engine-trace").getString("status"));

    assertTrue(entries.get("app.log").contains("app-evidence"));
    assertTrue(entries.get("crash.log").contains("crash-evidence"));
    JSONObject exportedConfig = new JSONObject(entries.get("config.json"));
    assertEquals(19, exportedConfig.getJSONObject("ui").getInt("board-size"));
    assertFalse(exportedConfig.getJSONObject("ui").has("unknown-support-key"));
    assertFalse(exportedConfig.getJSONObject("ui").has("password"));
    JSONObject engine = exportedConfig.getJSONObject("leelaz");
    assertEquals("katago", engine.getString("kind"));
    assertEquals("katago.exe", engine.getString("executable"));
    assertFalse(engine.has("command"));
    assertFalse(entries.values().toString().contains("CANARY_PASSWORD_EXPORT"));
    assertFalse(entries.values().toString().contains("C:\\\\Users\\\\alice"));
  }

  @Test
  void missingCrashSourceStillPublishesPackage() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    runtime.shutdown();
    Files.deleteIfExists(tempDir.resolve("logs/crash.log"));

    Path zip = exportDefault(runtime);
    Map<String, String> entries = unzipTextEntries(zip);
    JSONObject sources = new JSONObject(entries.get("manifest.json")).getJSONObject("sources");
    assertEquals("error", sources.getJSONObject("crash").getString("status"));
    assertTrue(entries.containsKey("manifest.json"));
    assertTrue(entries.containsKey("app.log"));
    assertFalse(entries.containsKey("crash.log"));
  }

  @Test
  void cancellationBeforePublicationLeavesNoZip() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    Path diagnostics = DiagnosticBundleExporter.defaultOutputDirectory(tempDir);
    assertThrows(
        IOException.class,
        () ->
            new DiagnosticBundleExporter(diagnostics)
                .export(request(runtime, EnumSet.noneOf(TraceScope.class)), () -> true));
    if (Files.isDirectory(diagnostics)) {
      try (var stream = Files.list(diagnostics)) {
        assertEquals(0, stream.count());
      }
    }
  }

  @Test
  void secondExportUsesCollisionSafeNameAndDoesNotNestPriorZip() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    Path first = exportDefault(runtime);
    Path second = exportDefault(runtime);
    assertNotEquals(first, second);
    Map<String, String> entries = unzipTextEntries(second);
    assertFalse(entries.keySet().stream().anyMatch(name -> name.endsWith(".zip")));
  }

  @Test
  void exportSanitizerRemovesCredentialPathAndUrlCanariesFromLogs() throws Exception {
    LoggingRuntime runtime = start();
    LoggerFactory.getLogger(LogCategories.APP)
        .error(
            "login password={} path={} url={}",
            "CANARY_PASSWORD_7f3a",
            "/home/dev/lizzieyzy-next/config.txt",
            "https://example.test/status");
    runtime.awaitIdle();

    Map<String, String> entries = unzipTextEntries(exportDefault(runtime));
    String all = String.join("\n", entries.values());
    assertFalse(all.contains("CANARY_PASSWORD_7f3a"), all);
    assertFalse(all.contains("/home/dev/lizzieyzy-next/config.txt"), all);
    assertFalse(all.contains("https://example.test/status"), all);
    assertTrue(all.contains("/home/<user>") || all.contains("<redacted-path>"), all);
    assertTrue(all.contains("<redacted-url>") || all.contains("<redacted"), all);
  }

  @Test
  void exportOmitsOriginalSessionKeysFromEveryZipEntryIncludingManifest() throws Exception {
    LoggingRuntime runtime = start();
    LoggerFactory.getLogger(LogCategories.APP).info("yike session=live-room:186538");
    runtime.awaitIdle();

    Map<String, String> entries = unzipTextEntries(exportDefault(runtime));
    for (Map.Entry<String, String> entry : entries.entrySet()) {
      assertFalse(
          entry.getValue().contains("live-room:186538"),
          entry.getKey() + " leaked live-room:186538");
      assertFalse(
          entry.getValue().contains("live-room\\u003a186538"),
          entry.getKey() + " leaked escaped live-room id");
    }
    String all = String.join("\n", entries.values());
    assertTrue(all.contains("live-room#1"), all);
    JSONObject manifest = new JSONObject(entries.get("manifest.json"));
    assertEquals("live-room#1", manifest.getJSONArray("aliases").getString(0));
  }

  @Test
  void rawOptInCopiesCurrentTraceSessionOnly() throws Exception {
    LoggingRuntime runtime = start();
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    String firstSession = runtime.currentTraceSessionId();
    LoggerFactory.getLogger(LogCategories.ENGINE_TRACE).info("raw-first session={}", firstSession);
    runtime.awaitIdle();
    runtime.stopFullTrace();
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    String secondSession = runtime.currentTraceSessionId();
    LoggerFactory.getLogger(LogCategories.ENGINE_TRACE).info("raw-second session={}", secondSession);
    runtime.awaitIdle();

    Path zip =
        new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
            .export(request(runtime, EnumSet.of(TraceScope.ENGINE_GTP)));
    Map<String, String> entries = unzipTextEntries(zip);
    assertTrue(entries.containsKey("engine-trace.log"));
    String trace = entries.get("engine-trace.log");
    assertTrue(trace.contains("raw-second"), trace);
    assertFalse(trace.contains("raw-first"), trace);
    JSONObject sources = new JSONObject(entries.get("manifest.json")).getJSONObject("sources");
    assertEquals("included", sources.getJSONObject("engine-trace").getString("status"));
  }

  @Test
  void rawCapTruncatesCompleteRecordsWithoutPausingActiveTrace() throws Exception {
    LoggingRuntime runtime = start();
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    org.slf4j.Logger trace = LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
    for (int i = 0; i < 40; i++) {
      trace.info("raw-line-{}-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", i);
    }
    runtime.awaitIdle();
    DiagnosticBundleLimits limits = new DiagnosticBundleLimits(24, 1024, 24, 1024, 200);
    Path zip =
        new DiagnosticBundleExporter(
                DiagnosticBundleExporter.defaultOutputDirectory(tempDir), limits)
            .export(request(runtime, EnumSet.of(TraceScope.ENGINE_GTP)));
    Map<String, String> entries = unzipTextEntries(zip);
    JSONObject sources = new JSONObject(entries.get("manifest.json")).getJSONObject("sources");
    assertEquals("truncated", sources.getJSONObject("engine-trace").getString("status"));
    assertTrue(entries.get("engine-trace.log").getBytes(StandardCharsets.UTF_8).length <= 200 + 80);
    trace.info("post-export-still-live");
    runtime.awaitIdle();
    assertTrue(
        Files.readString(tempDir.resolve("logs/engine-trace.log")).contains("post-export-still-live"));
  }

  @Test
  void appWindowOmitsLinesOlderThan24Hours() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    Path appLog = tempDir.resolve("logs/app.log");
    Files.writeString(
        appLog,
        "2020-01-01 00:00:00.000 INFO  [lizzie.app] old-evidence\n",
        java.nio.file.StandardOpenOption.APPEND);
    Map<String, String> entries = unzipTextEntries(exportDefault(runtime));
    assertFalse(entries.get("app.log").contains("old-evidence"), entries.get("app.log"));
  }

  @Test
  void estimateIsPositiveBeforeExport() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    long estimate =
        new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
            .estimateUncompressedBytes(request(runtime, EnumSet.noneOf(TraceScope.class)));
    assertTrue(estimate > 0);
  }

  private LoggingRuntime start() {
    LoggingRuntime.resetForTests();
    return LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, java.util.List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
  }

  private Path exportDefault(LoggingRuntime runtime) throws IOException {
    return new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
        .export(request(runtime, EnumSet.noneOf(TraceScope.class)));
  }

  private static DiagnosticBundleRequest request(
      LoggingRuntime runtime, Set<TraceScope> rawScopes) {
    return new DiagnosticBundleRequest(runtime, rawScopes, new JSONObject(), emptySnapshot(), "next-dev");
  }

  private static SyncDiagnosticsExportSnapshot emptySnapshot() {
    return new SyncDiagnosticsExportSnapshot(
        1L, null, java.util.List.of(), java.util.List.of(), java.util.List.of(), null);
  }

  private static Map<String, String> unzipTextEntries(Path zip) throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        entries.put(entry.getName(), new String(readEntry(input), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }

  private static byte[] readEntry(ZipInputStream input) throws IOException {
    return input.readAllBytes();
  }

  @SuppressWarnings("unused")
  private static Set<String> zipEntries(Path zip) throws IOException {
    return new TreeSet<>(unzipTextEntries(zip).keySet());
  }
}
