package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.ReadBoardLoggingControl;
import featurecat.lizzie.analysis.ReadBoardLoggingProtocol;
import featurecat.lizzie.analysis.ReadBoardLoggingSnapshot;
import featurecat.lizzie.analysis.SyncDiagnosticsExportSnapshot;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class DiagnosticBundleExporterTest {
  private static final DateTimeFormatter LOG_TIMESTAMP =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS");
  private static final String PROCESS_SESSION = "dGVzdFByb2Nlc3NJRA";
  private static final String OLD_PROCESS_SESSION = "b2xkUHJvY2Vzc1Nlc3Npb24";
  private static final String HOST_SESSION = "dGVzdEhvc3RTZXNzaW9u";
  private static final String CANARY_PASSWORD = "CANARY_PASSWORD_7f3a";
  private static final String CANARY_TOKEN = "CANARY_TOKEN_9c2b";
  private static final String CANARY_COOKIE = "CANARY_COOKIE_4d11";
  private static final String CANARY_MACHINE_KEY = "CANARY_MACHINEKEY_88aa";
  private static final String CANARY_CREDENTIAL = "CANARY_CREDENTIAL_12ef";
  private static final String CANARY_JSON_NESTED = "OPAQUE_JSON_NESTED_31aa";
  private static final String CANARY_JSON_UNKNOWN = "OPAQUE_JSON_UNKNOWN_96bd";
  private static final String CANARY_CAPTURE_NESTED = "OPAQUE_CAPTURE_NESTED_a780";
  private static final String CANARY_CAPTURE_UNKNOWN = "OPAQUE_CAPTURE_UNKNOWN_f293";
  private static final String CANARY_NONSTRING_SESSION = "OPAQUE_NONSTRING_SESSION_55ce";
  private static final byte[] PIXEL_PNG =
      new byte[] {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01, 0x02, 0x03, 0x04
      };

  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    LoggingRuntime.resetForTests();
    ThreadSnapshot.resetHeldForTests();
    EdtHangWatchdog.uninstall();
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

    assertTrue(
        Files.isSameFile(diagnostics, zip.getParent()),
        "published package must remain in the requested output directory: " + zip);
    assertTrue(
        Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS),
        "published package must remain a real regular file: " + zip);
    assertTrue(zip.getFileName().toString().startsWith("lizzie-diagnostics-"));
    assertTrue(zip.getFileName().toString().endsWith(".zip"));
    assertFalse(Files.exists(Path.of(zip + ".partial")));
    try (var stream = Files.list(diagnostics)) {
      assertEquals(1, stream.filter(path -> path.toString().endsWith(".zip")).count());
    }

    Map<String, byte[]> entries = unzipEntries(zip);
    Set<String> names = entries.keySet();
    assertTrue(names.contains("manifest.json"));
    assertTrue(names.contains("logs/lizzie/app.log"));
    assertTrue(names.contains("logs/lizzie/crash.log"));
    assertTrue(names.contains("snapshots/config.json"));
    assertTrue(names.contains("snapshots/environment.txt"));
    assertTrue(names.contains("snapshots/summary.txt"));
    assertTrue(names.contains("snapshots/sync-context.json"));
    assertTrue(names.contains("snapshots/versions.json"));
    assertTrue(names.contains("snapshots/runtime.json"));
    assertTrue(names.contains("snapshots/threads.txt"));
    assertFalse(names.contains("app.log"));
    assertFalse(names.contains("crash.log"));
    assertFalse(names.contains("logs/lizzie/engine-trace.log"));
    assertFalse(names.contains("logs/readboard/trace.log"));
    assertFalse(names.stream().anyMatch(name -> name.endsWith(".zip")));

    JSONObject manifest = manifest(entries);
    assertEquals(runtime.applicationLogSessionId(), manifest.getString("applicationSession"));
    assertTrue(manifest.has("traceSession"));
    assertTrue(manifest.isNull("traceSession"));
    assertEquals("next-dev", manifest.getString("appVersion"));
    assertEquals(ExportSanitizer.VERSION, manifest.getString("sanitizerVersion"));
    assertFalse(manifest.getBoolean("fullTraceActive"));
    assertTrue(manifest.getBoolean("diagnosticsEnabled"));

    JSONObject app = source(manifest, "lizzie-app");
    assertSource(app, true, true, "included", "logs/lizzie/");
    assertEquals(24, app.getInt("windowHours"));
    assertEquals(50L * 1024 * 1024, app.getLong("capBytes"));
    JSONObject crash = source(manifest, "lizzie-crash");
    assertSource(crash, true, true, "included", "logs/lizzie/");
    assertEquals(10L * 1024 * 1024, crash.getLong("capBytes"));
    assertSource(source(manifest, "lizzie-engine-trace"), false, false, "omitted", "logs/lizzie/");
    assertEquals("not-requested", source(manifest, "lizzie-engine-trace").getString("reason"));
    assertSource(source(manifest, "readboard-trace"), false, false, "omitted", "logs/readboard/");
    assertSource(
        source(manifest, "readboard-capture"), true, false, "omitted", "diagnostics/readboard-capture/");
    assertEquals("helper-not-started", source(manifest, "readboard-capture").getString("reason"));

    assertTrue(text(entries, "logs/lizzie/app.log").contains("app-evidence"));
    assertTrue(text(entries, "logs/lizzie/crash.log").contains("crash-evidence"));
    JSONObject exportedConfig = new JSONObject(text(entries, "snapshots/config.json"));
    assertEquals(19, exportedConfig.getJSONObject("ui").getInt("board-size"));
    assertFalse(exportedConfig.getJSONObject("ui").has("unknown-support-key"));
    assertFalse(exportedConfig.getJSONObject("ui").has("password"));
    JSONObject engine = exportedConfig.getJSONObject("leelaz");
    assertEquals("katago", engine.getString("kind"));
    assertEquals("katago.exe", engine.getString("executable"));
    assertFalse(engine.has("command"));
    JSONObject versions = new JSONObject(text(entries, "snapshots/versions.json"));
    assertEquals("next-dev", versions.getString("host"));
    JSONObject runtimeSnapshot = new JSONObject(text(entries, "snapshots/runtime.json"));
    assertRuntimeSnapshotShape(runtimeSnapshot);
    assertRuntimeSnapshotMemoryNonNegative(runtimeSnapshot);
    assertWorkDirUsablePresentOrMissing(runtimeSnapshot);
    assertFalse(
        text(entries, "snapshots/runtime.json").contains(tempDir.toAbsolutePath().toString()),
        text(entries, "snapshots/runtime.json"));
    String threads = text(entries, "snapshots/threads.txt");
    assertTrue(threads.contains("name="), threads);
    assertTrue(threads.contains("state="), threads);
    assertTrue(threads.contains("daemon="), threads);
    assertTrue(threads.contains("--- stack ---"), threads);
    assertEquals("included", source(manifest, "threads").getString("status"));
    assertNoCanaries(entries, "CANARY_PASSWORD_EXPORT", "C:\\Users\\alice");
  }

  @Test
  void exportWritesFindableEventQueueThreadSnapshotThroughSanitizer() throws Exception {
    LoggingRuntime runtime = start();
    CountDownLatch parked = new CountDownLatch(1);
    CountDownLatch secretParked = new CountDownLatch(1);
    Thread edt =
        new Thread(
            () -> {
              parked.countDown();
              LockSupport.park();
            },
            "AWT-EventQueue-0");
    edt.setDaemon(true);
    Thread secret =
        new Thread(
            () -> {
              secretParked.countDown();
              LockSupport.park();
            },
            "diag-worker password=CANARY_THREAD_SECRET");
    secret.setDaemon(true);
    edt.start();
    secret.start();
    assertTrue(parked.await(5, TimeUnit.SECONDS));
    assertTrue(secretParked.await(5, TimeUnit.SECONDS));
    try {
      Map<String, byte[]> entries = unzipEntries(exportDefault(runtime));
      assertTrue(entries.containsKey("snapshots/threads.txt"));
      String threads = text(entries, "snapshots/threads.txt");
      assertTrue(threads.contains("AWT-EventQueue-0"), threads);
      assertTrue(threads.contains("name=AWT-EventQueue-0"), threads);
      assertTrue(threads.contains(">>> HIGHLIGHT"), threads);
      assertTrue(threads.contains("state="), threads);
      assertTrue(threads.contains("--- stack ---"), threads);
      assertFalse(threads.contains("CANARY_THREAD_SECRET"), threads);
      assertTrue(threads.contains("password=<redacted>"), threads);
      assertSource(source(manifest(entries), "threads"), true, true, "included", "snapshots/");
    } finally {
      LockSupport.unpark(edt);
      LockSupport.unpark(secret);
      edt.join(1_000);
      secret.join(1_000);
    }
  }

  @Test
  void threadSnapshotFailureDoesNotFailThePackage() throws Exception {
    LoggingRuntime runtime = start();
    DiagnosticBundleExporter exporter =
        new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir));
    exporter.setThreadSnapshotSourceForTests(
        sanitizer -> {
          throw new IllegalStateException("snapshot boom");
        });
    Path zip = exporter.export(request(runtime, EnumSet.noneOf(TraceScope.class)));
    assertTrue(Files.isRegularFile(zip));
    Map<String, byte[]> entries = unzipEntries(zip);
    assertTrue(entries.containsKey("snapshots/config.json"));
    assertTrue(entries.containsKey("snapshots/versions.json"));
    assertTrue(entries.containsKey("snapshots/runtime.json"));
    assertTrue(entries.containsKey("snapshots/threads.txt"));
    assertTrue(text(entries, "snapshots/threads.txt").contains("thread snapshot failed"));
    JSONObject threads = source(manifest(entries), "threads");
    assertSource(threads, true, false, "failed", "snapshots/");
    assertEquals("unreadable", threads.getString("reason"));
  }

  @Test
  void exportWritesParseableRuntimeSnapshot() throws Exception {
    LoggingRuntime runtime = start();
    Map<String, byte[]> entries = unzipEntries(exportDefault(runtime));
    assertTrue(entries.containsKey("snapshots/config.json"));
    assertTrue(entries.containsKey("snapshots/environment.txt"));
    assertTrue(entries.containsKey("snapshots/summary.txt"));
    assertTrue(entries.containsKey("snapshots/sync-context.json"));
    assertTrue(entries.containsKey("snapshots/versions.json"));
    assertTrue(entries.containsKey("snapshots/threads.txt"));
    assertTrue(entries.containsKey("snapshots/runtime.json"));
    JSONObject runtimeSnapshot = new JSONObject(text(entries, "snapshots/runtime.json"));
    assertRuntimeSnapshotShape(runtimeSnapshot);
    assertRuntimeSnapshotMemoryNonNegative(runtimeSnapshot);
    assertWorkDirUsablePresentOrMissing(runtimeSnapshot);
    assertFalse(
        text(entries, "snapshots/runtime.json").contains(tempDir.toAbsolutePath().toString()));
  }

  @Test
  void runtimeSnapshotDiskFailureDoesNotFailThePackage() throws Exception {
    LoggingRuntime runtime = start();
    DiagnosticBundleExporter exporter =
        new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir));
    exporter.setRuntimeSnapshotSourceForTests(
        (workDirectory, sanitizer) ->
            RuntimeSnapshot.capture(
                workDirectory,
                sanitizer,
                ignored -> {
                  throw new IOException("store-unreadable");
                }));
    Path zip = exporter.export(request(runtime, EnumSet.noneOf(TraceScope.class)));
    assertTrue(Files.isRegularFile(zip));
    Map<String, byte[]> entries = unzipEntries(zip);
    assertTrue(entries.containsKey("snapshots/config.json"));
    assertTrue(entries.containsKey("snapshots/environment.txt"));
    assertTrue(entries.containsKey("snapshots/summary.txt"));
    assertTrue(entries.containsKey("snapshots/sync-context.json"));
    assertTrue(entries.containsKey("snapshots/versions.json"));
    assertTrue(entries.containsKey("snapshots/threads.txt"));
    assertTrue(entries.containsKey("snapshots/runtime.json"));
    JSONObject runtimeSnapshot = new JSONObject(text(entries, "snapshots/runtime.json"));
    assertRuntimeSnapshotShape(runtimeSnapshot);
    assertTrue(runtimeSnapshot.isNull("workDirUsableGiB"), runtimeSnapshot.toString());
    assertEquals(
        RuntimeSnapshot.MISSING_UNREADABLE,
        runtimeSnapshot.getJSONObject("missing").getString("workDirUsableGiB"));
    assertRuntimeSnapshotMemoryNonNegative(runtimeSnapshot);
    assertTrue(runtimeSnapshot.getLong("heapUsedMiB") >= 0L, runtimeSnapshot.toString());
  }

  @Test
  void runtimeSnapshotCaptureFailureDoesNotFailThePackage() throws Exception {
    LoggingRuntime runtime = start();
    DiagnosticBundleExporter exporter =
        new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir));
    exporter.setRuntimeSnapshotSourceForTests(
        (workDirectory, sanitizer) -> {
          throw new IllegalStateException("runtime boom");
        });
    Path zip = exporter.export(request(runtime, EnumSet.noneOf(TraceScope.class)));
    assertTrue(Files.isRegularFile(zip));
    Map<String, byte[]> entries = unzipEntries(zip);
    assertTrue(entries.containsKey("snapshots/config.json"));
    assertTrue(entries.containsKey("snapshots/versions.json"));
    assertTrue(entries.containsKey("snapshots/runtime.json"));
    JSONObject runtimeSnapshot = new JSONObject(text(entries, "snapshots/runtime.json"));
    assertRuntimeSnapshotShape(runtimeSnapshot);
    for (String field : RuntimeSnapshot.REQUIRED_FIELDS) {
      assertTrue(runtimeSnapshot.isNull(field), field);
    }
  }

  @Test
  void configSnapshotProjectsProductionEngineListWithStrictAllowlist() throws Exception {
    LoggingRuntime runtime = start();
    JSONObject config = new JSONObject();
    config.put(
        "ui",
        new JSONObject()
            .put("board-size", 19)
            .put("theme", new JSONObject().put("opaque", "UI_THEME_OBJECT_CANARY"))
            .put(
                "show-coordinates",
                new JSONArray().put(new JSONObject().put("opaque", "UI_COORD_ARRAY_CANARY")))
            .put(
                "network-proxy-mode",
                new JSONObject().put("opaque", "UI_PROXY_OBJECT_CANARY")));
    JSONObject leelaz = new JSONObject();
    leelaz.put(
        "command",
        "C:\\Users\\legacy-owner\\katago.exe gtp -model C:\\private\\legacy.bin.gz");
    leelaz.put(
        "engine-settings-list",
        new JSONArray()
            .put(
                new JSONObject()
                    .put("name", "Local KataGo")
                    .put(
                        "command",
                        "C:\\Users\\profile-owner\\katago.exe gtp --token CANARY_ENGINE_TOKEN")
                    .put("path", "C:\\Users\\profile-owner\\models\\private.bin.gz")
                    .put("token", "CANARY_ENGINE_TOKEN_FIELD")
                    .put("password", "CANARY_ENGINE_PASSWORD")
                    .put("host", "private-engine.internal.example")
                    .put("args", "--authorization CANARY_ENGINE_AUTH"))
            .put(
                new JSONObject()
                    .put("name", "Local Leela")
                    .put(
                        "command",
                        "D:\\private-engine\\leelaz.exe --gtp --password CANARY_SECOND_PASSWORD")
                    .put("path", "D:\\private-engine\\weights.gz")
                    .put("token", "CANARY_SECOND_TOKEN")
                    .put("password", "CANARY_SECOND_PASSWORD")
                    .put("host", "second-private.internal.example")
                    .put("args", "--cookie CANARY_SECOND_COOKIE"))
            .put(
                new JSONObject()
                    .put("name", new JSONObject().put("opaque", "ENGINE_NAME_OBJECT_CANARY"))
                    .put(
                        "command",
                        new JSONArray().put("ENGINE_COMMAND_ARRAY_CANARY")))
            .put(
                new JSONObject()
                    .put("name", "Malformed executable")
                    .put("command", "{\"opaque\":\"ENGINE_COMMAND_STRING_CANARY\"}")));
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

    Map<String, byte[]> entries = unzipEntries(zip);
    JSONObject configSnapshot = new JSONObject(text(entries, "snapshots/config.json"));
    JSONObject projectedUi = configSnapshot.getJSONObject("ui");
    assertEquals(Set.of("board-size"), projectedUi.keySet());
    assertEquals(19, projectedUi.getInt("board-size"));
    JSONObject projected = configSnapshot.getJSONObject("leelaz");
    assertEquals(Set.of("kind", "executable", "engine-settings-list"), projected.keySet());
    assertEquals("katago", projected.getString("kind"));
    assertEquals("katago.exe", projected.getString("executable"));

    JSONArray engines = projected.getJSONArray("engine-settings-list");
    assertEquals(4, engines.length());
    JSONObject first = engines.getJSONObject(0);
    JSONObject second = engines.getJSONObject(1);
    JSONObject wrongTypes = engines.getJSONObject(2);
    JSONObject malformedCommand = engines.getJSONObject(3);
    assertEquals(Set.of("name", "kind", "executable"), first.keySet());
    assertEquals(Set.of("name", "kind", "executable"), second.keySet());
    assertEquals("Local KataGo", first.getString("name"));
    assertEquals("katago", first.getString("kind"));
    assertEquals("katago.exe", first.getString("executable"));
    assertEquals("Local Leela", second.getString("name"));
    assertEquals("leelaz", second.getString("kind"));
    assertEquals("leelaz.exe", second.getString("executable"));
    assertEquals(Set.of("kind", "executable"), wrongTypes.keySet());
    assertEquals("other", wrongTypes.getString("kind"));
    assertEquals("unknown", wrongTypes.getString("executable"));
    assertEquals(Set.of("name", "kind", "executable"), malformedCommand.keySet());
    assertEquals("Malformed executable", malformedCommand.getString("name"));
    assertEquals("other", malformedCommand.getString("kind"));
    assertEquals("unknown", malformedCommand.getString("executable"));

    for (JSONObject engine : new JSONObject[] {first, second}) {
      for (String forbidden :
          new String[] {"command", "path", "token", "password", "host", "args"}) {
        assertFalse(engine.has(forbidden), engine.toString());
      }
    }
    assertNoCanaries(
        entries,
        "legacy-owner",
        "profile-owner",
        "private-engine.internal.example",
        "second-private.internal.example",
        "CANARY_ENGINE_TOKEN",
        "CANARY_ENGINE_TOKEN_FIELD",
        "CANARY_ENGINE_PASSWORD",
        "CANARY_ENGINE_AUTH",
        "CANARY_SECOND_TOKEN",
        "CANARY_SECOND_PASSWORD",
        "CANARY_SECOND_COOKIE",
        "UI_THEME_OBJECT_CANARY",
        "UI_COORD_ARRAY_CANARY",
        "UI_PROXY_OBJECT_CANARY",
        "ENGINE_NAME_OBJECT_CANARY",
        "ENGINE_COMMAND_ARRAY_CANARY",
        "ENGINE_COMMAND_STRING_CANARY");
  }

  @Test
  void uiConfigProjectionEnforcesProductionValueSchemas() {
    JSONObject validUi =
        new JSONObject()
            .put("board-size", 19)
            .put("theme", "default")
            .put("show-coordinates", true)
            .put("show-winrate-overview", false)
            .put("extra-mode", 2)
            .put("is-apple-style", false)
            .put("analysis-max-visits", 500)
            .put("max-game-thinking-time-seconds", 3)
            .put("autoload-default", true)
            .put("network-proxy-mode", "manual");
    JSONObject projectedValid =
        ConfigExportProjection.project(new JSONObject().put("ui", validUi)).getJSONObject("ui");
    assertEquals(validUi.keySet(), projectedValid.keySet());
    for (String key : validUi.keySet()) {
      assertEquals(validUi.get(key), projectedValid.get(key), key);
    }

    JSONObject invalidUi =
        new JSONObject()
            .put("board-size", new JSONObject().put("opaque", "UI_BOARD_OBJECT_CANARY"))
            .put("theme", new JSONArray().put("UI_THEME_ARRAY_CANARY"))
            .put(
                "show-coordinates",
                new JSONObject().put("opaque", "UI_COORD_OBJECT_CANARY"))
            .put("show-winrate-overview", new JSONArray().put("UI_OVERVIEW_ARRAY_CANARY"))
            .put("extra-mode", 4)
            .put("is-apple-style", new JSONObject().put("opaque", "UI_APPLE_OBJECT_CANARY"))
            .put("analysis-max-visits", 1.5d)
            .put(
                "max-game-thinking-time-seconds",
                new JSONArray().put("UI_THINKING_ARRAY_CANARY"))
            .put("autoload-default", "UI_AUTOLOAD_STRING_CANARY")
            .put("network-proxy-mode", "UI_PROXY_MODE_CANARY");
    JSONObject projectedInvalid =
        ConfigExportProjection.project(new JSONObject().put("ui", invalidUi))
            .getJSONObject("ui");
    assertEquals(Set.of(), projectedInvalid.keySet());
    assertFalse(projectedInvalid.toString().contains("CANARY"));
  }

  @Test
  void engineConfigProjectionRejectsCoercedFieldsAndUnsafeExecutableNames() {
    JSONObject leelaz =
        new JSONObject()
            .put("command", new JSONObject().put("opaque", "LEGACY_COMMAND_OBJECT_CANARY"))
            .put(
                "engine-settings-list",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put(
                                "name",
                                new JSONObject().put("opaque", "ENGINE_NAME_NESTED_CANARY"))
                            .put(
                                "command",
                                new JSONArray().put("ENGINE_COMMAND_NESTED_CANARY")))
                    .put(
                        new JSONObject()
                            .put("name", "Cross-platform engine")
                            .put(
                                "command",
                                "\"/opt/围棋 engines/卡塔狗-AVX2+OpenCL\" gtp"))
                    .put(
                        new JSONObject()
                            .put("name", "Malformed executable")
                            .put("command", "{\"opaque\":\"EXECUTABLE_JSON_CANARY\"}")));

    JSONObject projected =
        ConfigExportProjection.project(new JSONObject().put("leelaz", leelaz))
            .getJSONObject("leelaz");
    assertEquals(Set.of("engine-settings-list"), projected.keySet());
    JSONArray engines = projected.getJSONArray("engine-settings-list");
    assertEquals(Set.of("kind", "executable"), engines.getJSONObject(0).keySet());
    assertEquals("other", engines.getJSONObject(0).getString("kind"));
    assertEquals("unknown", engines.getJSONObject(0).getString("executable"));
    assertEquals(
        Set.of("name", "kind", "executable"), engines.getJSONObject(1).keySet());
    assertEquals("Cross-platform engine", engines.getJSONObject(1).getString("name"));
    assertEquals("other", engines.getJSONObject(1).getString("kind"));
    assertEquals("卡塔狗-AVX2+OpenCL", engines.getJSONObject(1).getString("executable"));
    assertEquals("unknown", engines.getJSONObject(2).getString("executable"));
    assertFalse(projected.toString().contains("CANARY"));

    assertEquals(
        "katago-v1.15_cuda12.8+trt.exe",
        ConfigExportProjection.executableBasename(
            "\"C:\\Program Files\\KataGo\\katago-v1.15_cuda12.8+trt.exe\" gtp"));
    assertEquals(
        "卡塔狗-AVX2+OpenCL",
        ConfigExportProjection.executableBasename(
            "\"/opt/围棋 engines/卡塔狗-AVX2+OpenCL\" gtp"));
    assertEquals(
        "unknown",
        ConfigExportProjection.executableBasename(
            "{\"opaque\":\"EXECUTABLE_JSON_CANARY\"}"));
    assertEquals(
        "unknown", ConfigExportProjection.executableBasename("a".repeat(256)));
    assertEquals(
        "unknown",
        ConfigExportProjection.executableBasename("\"UNTERMINATED_EXECUTABLE_CANARY"));
  }

  @Test
  void loggingConfigProjectionKeepsOnlyKnownSettingsWithOriginalJsonTypes() {
    JSONArray modules = new JSONArray().put("engine").put("gtp-summary");
    JSONArray scopes = new JSONArray().put("readboard-yike").put("network-websocket");
    JSONObject logging =
        new JSONObject()
            .put(LoggingSettings.DIAGNOSTICS_ENABLED_KEY, true)
            .put(LoggingSettings.DIAGNOSTIC_MODULES_KEY, modules)
            .put(LoggingSettings.PREFERRED_FULL_TRACE_SCOPES_KEY, scopes)
            .put("future-endpoint", "PRIVATE_HOST_CANARY.corp")
            .put("future-metadata", new JSONObject().put("opaque", "OPAQUE_OBJECT_CANARY"))
            .put("future-values", new JSONArray().put("OPAQUE_ARRAY_CANARY"));

    JSONObject projected =
        ConfigExportProjection.project(
                new JSONObject().put(LoggingSettings.CONFIG_KEY, logging))
            .getJSONObject(LoggingSettings.CONFIG_KEY);

    assertEquals(
        Set.of(
            LoggingSettings.DIAGNOSTICS_ENABLED_KEY,
            LoggingSettings.DIAGNOSTIC_MODULES_KEY,
            LoggingSettings.PREFERRED_FULL_TRACE_SCOPES_KEY),
        projected.keySet());
    assertTrue(projected.getBoolean(LoggingSettings.DIAGNOSTICS_ENABLED_KEY));
    assertEquals(
        modules.toString(),
        projected.getJSONArray(LoggingSettings.DIAGNOSTIC_MODULES_KEY).toString());
    assertEquals(
        scopes.toString(),
        projected
            .getJSONArray(LoggingSettings.PREFERRED_FULL_TRACE_SCOPES_KEY)
            .toString());
  }

  @Test
  void loggingConfigProjectionDropsWrongTypesAndInvalidNestedValues() {
    JSONObject wrongTypes =
        new JSONObject()
            .put(
                LoggingSettings.DIAGNOSTICS_ENABLED_KEY,
                new JSONObject().put("opaque", "WRONG_BOOLEAN_OBJECT_CANARY"))
            .put(
                LoggingSettings.DIAGNOSTIC_MODULES_KEY,
                new JSONObject().put("opaque", "WRONG_MODULES_OBJECT_CANARY"))
            .put(
                LoggingSettings.PREFERRED_FULL_TRACE_SCOPES_KEY,
                "WRONG_SCOPES_SCALAR_CANARY");
    JSONObject projectedWrongTypes =
        ConfigExportProjection.project(
                new JSONObject().put(LoggingSettings.CONFIG_KEY, wrongTypes))
            .getJSONObject(LoggingSettings.CONFIG_KEY);
    assertEquals(Set.of(), projectedWrongTypes.keySet());
    assertFalse(projectedWrongTypes.toString().contains("CANARY"));

    JSONObject mixedArrays =
        new JSONObject()
            .put(
                LoggingSettings.DIAGNOSTIC_MODULES_KEY,
                new JSONArray()
                    .put("engine")
                    .put("INVALID_MODULE_STRING_CANARY")
                    .put(new JSONObject().put("opaque", "MODULE_OBJECT_CANARY"))
                    .put(new JSONArray().put("MODULE_NESTED_ARRAY_CANARY")))
            .put(
                LoggingSettings.PREFERRED_FULL_TRACE_SCOPES_KEY,
                new JSONArray()
                    .put("readboard-yike")
                    .put("INVALID_SCOPE_STRING_CANARY")
                    .put(new JSONObject().put("opaque", "SCOPE_OBJECT_CANARY"))
                    .put(new JSONArray().put("SCOPE_NESTED_ARRAY_CANARY")));
    JSONObject projectedMixedArrays =
        ConfigExportProjection.project(
                new JSONObject().put(LoggingSettings.CONFIG_KEY, mixedArrays))
            .getJSONObject(LoggingSettings.CONFIG_KEY);

    assertEquals(
        Set.of(
            LoggingSettings.DIAGNOSTIC_MODULES_KEY,
            LoggingSettings.PREFERRED_FULL_TRACE_SCOPES_KEY),
        projectedMixedArrays.keySet());
    assertEquals(
        new JSONArray().put("engine").toString(),
        projectedMixedArrays
            .getJSONArray(LoggingSettings.DIAGNOSTIC_MODULES_KEY)
            .toString());
    assertEquals(
        new JSONArray().put("readboard-yike").toString(),
        projectedMixedArrays
            .getJSONArray(LoggingSettings.PREFERRED_FULL_TRACE_SCOPES_KEY)
            .toString());
    assertFalse(projectedMixedArrays.toString().contains("CANARY"));
  }

  @Test
  void configSnapshotDropsUnknownNestedLoggingValuesFromCompleteBundle() throws Exception {
    LoggingRuntime runtime = start();
    JSONObject logging =
        new JSONObject()
            .put(LoggingSettings.DIAGNOSTICS_ENABLED_KEY, false)
            .put(
                LoggingSettings.DIAGNOSTIC_MODULES_KEY,
                new JSONArray()
                    .put("engine")
                    .put("INVALID_MODULE_STRING_CANARY")
                    .put(new JSONObject().put("opaque", "KNOWN_MODULE_OBJECT_CANARY"))
                    .put(new JSONArray().put("KNOWN_MODULE_ARRAY_CANARY")))
            .put(
                LoggingSettings.PREFERRED_FULL_TRACE_SCOPES_KEY,
                new JSONArray()
                    .put("readboard-yike")
                    .put("INVALID_SCOPE_STRING_CANARY")
                    .put(new JSONObject().put("opaque", "KNOWN_SCOPE_OBJECT_CANARY"))
                    .put(new JSONArray().put("KNOWN_SCOPE_ARRAY_CANARY")))
            .put("future-endpoint", "PRIVATE_HOST_CANARY.corp")
            .put("future-metadata", new JSONObject().put("opaque", "OPAQUE_OBJECT_CANARY"))
            .put(
                "future-values",
                new JSONArray().put(new JSONObject().put("opaque", "OPAQUE_ARRAY_CANARY")));
    JSONObject config = new JSONObject().put(LoggingSettings.CONFIG_KEY, logging);

    Path zip =
        new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
            .export(
                new DiagnosticBundleRequest(
                    runtime,
                    EnumSet.noneOf(TraceScope.class),
                    config,
                    emptySnapshot(),
                    "next-dev"));

    Map<String, byte[]> entries = unzipEntries(zip);
    JSONObject projected =
        new JSONObject(text(entries, "snapshots/config.json"))
            .getJSONObject(LoggingSettings.CONFIG_KEY);
    assertEquals(
        Set.of(
            LoggingSettings.DIAGNOSTICS_ENABLED_KEY,
            LoggingSettings.DIAGNOSTIC_MODULES_KEY,
            LoggingSettings.PREFERRED_FULL_TRACE_SCOPES_KEY),
        projected.keySet());
    assertFalse(projected.getBoolean(LoggingSettings.DIAGNOSTICS_ENABLED_KEY));
    assertEquals(1, projected.getJSONArray(LoggingSettings.DIAGNOSTIC_MODULES_KEY).length());
    assertEquals(
        "engine", projected.getJSONArray(LoggingSettings.DIAGNOSTIC_MODULES_KEY).getString(0));
    assertEquals(
        1, projected.getJSONArray(LoggingSettings.PREFERRED_FULL_TRACE_SCOPES_KEY).length());
    assertEquals(
        "readboard-yike",
        projected
            .getJSONArray(LoggingSettings.PREFERRED_FULL_TRACE_SCOPES_KEY)
            .getString(0));
    assertNoCanaries(
        entries,
        "PRIVATE_HOST_CANARY.corp",
        "OPAQUE_OBJECT_CANARY",
        "OPAQUE_ARRAY_CANARY",
        "INVALID_MODULE_STRING_CANARY",
        "KNOWN_MODULE_OBJECT_CANARY",
        "KNOWN_MODULE_ARRAY_CANARY",
        "INVALID_SCOPE_STRING_CANARY",
        "KNOWN_SCOPE_OBJECT_CANARY",
        "KNOWN_SCOPE_ARRAY_CANARY");
  }

  @Test
  void missingCrashSourceStillPublishesPackage() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    runtime.shutdown();
    Files.deleteIfExists(tempDir.resolve("logs/crash.log"));

    Path zip = exportDefault(runtime);
    Map<String, byte[]> entries = unzipEntries(zip);
    JSONObject crash = source(manifest(entries), "lizzie-crash");
    assertEquals("failed", crash.getString("status"));
    assertEquals("missing", crash.getString("reason"));
    assertTrue(crash.getBoolean("failed"));
    assertFalse(crash.getBoolean("included"));
    assertTrue(entries.containsKey("manifest.json"));
    assertTrue(entries.containsKey("logs/lizzie/app.log"));
    assertFalse(entries.containsKey("logs/lizzie/crash.log"));
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
  void observerErrorClosesHandleAndRemovesOwnedPartial() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    Path diagnostics = DiagnosticBundleExporter.defaultOutputDirectory(tempDir);
    DiagnosticBundleExporter exporter =
        new DiagnosticBundleExporter(
            diagnostics,
            DiagnosticBundleLimits.production(),
            partial -> {
              throw new AssertionError("observer failure");
            });

    assertThrows(
        AssertionError.class,
        () -> exporter.export(request(runtime, EnumSet.noneOf(TraceScope.class))));

    try (var stream = Files.list(diagnostics)) {
      assertEquals(0, stream.count());
    }
  }

  @Test
  void errorAfterPartialCreationDoesNotLeakTemporaryPackage() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    Path diagnostics = DiagnosticBundleExporter.defaultOutputDirectory(tempDir);
    AtomicInteger checks = new AtomicInteger();

    assertThrows(
        AssertionError.class,
        () ->
            new DiagnosticBundleExporter(diagnostics)
                .export(
                    request(runtime, EnumSet.noneOf(TraceScope.class)),
                    () -> {
                      if (checks.incrementAndGet() >= 2) {
                        throw new AssertionError("cancellation hook failure");
                      }
                      return false;
                    }));

    assertTrue(checks.get() >= 2);
    try (var stream = Files.list(diagnostics)) {
      assertEquals(0, stream.count());
    }
  }

  @Test
  void secondExportUsesCollisionSafeNameAndDoesNotNestPriorZip() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    Path first = exportDefault(runtime);
    Path second = exportDefault(runtime);
    assertNotEquals(first, second);
    Map<String, byte[]> entries = unzipEntries(second);
    assertFalse(entries.keySet().stream().anyMatch(name -> name.endsWith(".zip")));
  }

  @Test
  void exportSanitizerRemovesCredentialPathAndUrlCanariesFromLogs() throws Exception {
    LoggingRuntime runtime = start();
    LoggerFactory.getLogger(LogCategories.APP)
        .error(
            "login password={} path={} url={}",
            CANARY_PASSWORD,
            "/home/dev/lizzieyzy-next/config.txt",
            "https://example.test/status");
    runtime.awaitIdle();

    Map<String, byte[]> entries = unzipEntries(exportDefault(runtime));
    String all = joinedText(entries);
    assertFalse(all.contains(CANARY_PASSWORD), all);
    assertFalse(all.contains("/home/dev/lizzieyzy-next/config.txt"), all);
    assertFalse(all.contains("https://example.test/status"), all);
    assertTrue(all.contains("/home/<user>") || all.contains("<redacted-path>"), all);
    assertTrue(all.contains("<redacted-url>") || all.contains("<redacted"), all);
  }

  @Test
  void exportSanitizerFailClosesWindowsAbsolutePathsWithoutErasingEscapedText()
      throws Exception {
    LoggingRuntime runtime = start();
    var logger = LoggerFactory.getLogger(LogCategories.APP);
    logger.error(
        "unc={}",
        "\\\\UNC_SERVER_CANARY\\UNC_SHARE_CANARY\\UNC_USER_CANARY\\UNC_PATH_CANARY\\file.txt");
    logger.error(
        "escapedUnc={}",
        "\\\\\\\\ESC_UNC_SERVER_CANARY\\\\ESC_UNC_SHARE_CANARY\\\\ESC_UNC_USER_CANARY\\\\ESC_UNC_PATH_CANARY\\\\file.txt");
    logger.error(
        "wsl={}",
        "\\\\wsl.localhost\\WSL_DISTRO_CANARY\\home\\WSL_USER_CANARY\\WSL_PATH_CANARY\\file.txt");
    logger.error(
        "extended={}",
        "\\\\?\\UNC\\EXT_SERVER_CANARY\\EXT_SHARE_CANARY\\EXT_USER_CANARY\\EXT_PATH_CANARY\\file.txt");
    logger.error(
        "drive={}",
        "Z:\\DRIVE_ROOT_CANARY\\DRIVE USER CANARY\\DRIVE_PATH_CANARY\\file.txt");
    logger.error(
        "escapedDrive={}",
        "Z:\\\\ESC_DRIVE_ROOT_CANARY\\\\ESC_DRIVE_USER_CANARY\\\\ESC_DRIVE_PATH_CANARY\\\\file.txt");
    logger.error("nonPath={}", "alpha\\\\beta\\\\gamma");
    runtime.awaitIdle();

    Map<String, byte[]> entries = unzipEntries(exportDefault(runtime));
    String all = joinedText(entries);
    assertNoCanaries(
        entries,
        "UNC_SERVER_CANARY",
        "UNC_SHARE_CANARY",
        "UNC_USER_CANARY",
        "UNC_PATH_CANARY",
        "ESC_UNC_SERVER_CANARY",
        "ESC_UNC_SHARE_CANARY",
        "ESC_UNC_USER_CANARY",
        "ESC_UNC_PATH_CANARY",
        "WSL_DISTRO_CANARY",
        "WSL_USER_CANARY",
        "WSL_PATH_CANARY",
        "EXT_SERVER_CANARY",
        "EXT_SHARE_CANARY",
        "EXT_USER_CANARY",
        "EXT_PATH_CANARY",
        "DRIVE_ROOT_CANARY",
        "DRIVE USER CANARY",
        "DRIVE_PATH_CANARY",
        "ESC_DRIVE_ROOT_CANARY",
        "ESC_DRIVE_USER_CANARY",
        "ESC_DRIVE_PATH_CANARY");
    assertTrue(all.contains("<redacted-path>"), all);
    assertTrue(all.contains("nonPath=alpha\\\\beta\\\\gamma"), all);
  }

  @Test
  void exportSanitizerRemovesEncodedCredentialCanariesFromRawSourcesAndManifest()
      throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    runtime.shutdown();
    String timestamp = LocalDateTime.now().format(LOG_TIMESTAMP);
    String[] canaries = {
      "CANARY_BUNDLE_BARE_01",
      "CANARY_BUNDLE_ESCAPED_02",
      "CANARY_BUNDLE_UNICODE_03",
      "CANARY_BUNDLE_MANIFEST_04",
      "CANARY_BUNDLE_RAW_QUOTED_05",
      "CANARY_BUNDLE_EMBEDDED_QUOTED_06",
      "CANARY_BUNDLE_UNICODE_NAME_07",
      "CANARY_BUNDLE_PERCENT_PASSWORD_08",
      "CANARY_BUNDLE_PERCENT_KEY_09",
      "CANARY_BUNDLE_PERCENT_DOUBLE_10",
      "CANARY_BUNDLE_PERCENT_MIXED_HEX_11",
      "CANARY_BUNDLE_PERCENT_OVERDEPTH_12"
    };
    String[] payloads = {
      "Bearer CANARY_BUNDLE_BARE_01",
      "payload={\\\"password\\\":\\\"CANARY_BUNDLE_ESCAPED_02\\\"}",
      "Authorization\\u003a Bearer CANARY_BUNDLE_UNICODE_03",
      "payload={\"password\":\"prefix\\\"CANARY_BUNDLE_RAW_QUOTED_05\"}",
      escapedEmbeddedJson("CANARY_BUNDLE_EMBEDDED_QUOTED_06"),
      "passw\\u006frd=CANARY_BUNDLE_UNICODE_NAME_07",
      "body=password%3DCANARY_BUNDLE_PERCENT_PASSWORD_08",
      "body=%70assword=CANARY_BUNDLE_PERCENT_KEY_09",
      "body=token%253DCANARY_BUNDLE_PERCENT_DOUBLE_10",
      "body=passw%4Frd%3dCANARY_BUNDLE_PERCENT_MIXED_HEX_11",
      "body=token%252525253DCANARY_BUNDLE_PERCENT_OVERDEPTH_12"
    };
    String ordinaryPercent = "progress=90%25 note=hello%20world code=%7Bplain%7D";
    String deepOrdinaryPercent = "opaque=%2525252541";
    StringBuilder injected = new StringBuilder();
    for (String payload : payloads) {
      injected
          .append(timestamp)
          .append(" ERROR [lizzie.app] ")
          .append(payload)
          .append('\n');
    }
    injected
        .append(timestamp)
        .append(" INFO [lizzie.app] ")
        .append(ordinaryPercent)
        .append('\n');
    injected
        .append(timestamp)
        .append(" INFO [lizzie.app] ")
        .append(deepOrdinaryPercent)
        .append('\n');
    Files.writeString(
        tempDir.resolve("logs/app.log"),
        injected,
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND);

    Path zip =
        new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
            .export(
                new DiagnosticBundleRequest(
                    runtime,
                    EnumSet.noneOf(TraceScope.class),
                    new JSONObject(),
                    emptySnapshot(),
                    "Bearer CANARY_BUNDLE_MANIFEST_04"));

    Map<String, String> entries = unzipTextEntries(zip);
    for (Map.Entry<String, String> entry : entries.entrySet()) {
      for (String canary : canaries) {
        assertFalse(entry.getValue().contains(canary), entry.getKey() + " leaked " + canary);
      }
    }
    assertTrue(entries.get("logs/lizzie/app.log").contains(ordinaryPercent));
    assertTrue(entries.get("logs/lizzie/app.log").contains(deepOrdinaryPercent));
    assertEquals(
        ExportSanitizer.VERSION,
        new JSONObject(entries.get("manifest.json")).getString("sanitizerVersion"));
  }

  @Test
  void exportOmitsOriginalSessionKeysFromEveryZipEntryIncludingManifest() throws Exception {
    LoggingRuntime runtime = start();
    String privateSession = "live-room:private-room_42";
    String applicationSession = runtime.applicationLogSessionId();
    runtime.startFullTrace(EnumSet.of(TraceScope.READBOARD_YIKE));
    LoggerFactory.getLogger(LogCategories.APP).info("yike session=({}), ready", privateSession);
    LoggerFactory.getLogger(LogCategories.READBOARD_TRACE)
        .info("yike trace session={}, ready", privateSession);
    runtime.awaitIdle();

    Path zip =
        new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
            .export(request(runtime, EnumSet.of(TraceScope.READBOARD_YIKE)));
    Map<String, byte[]> entries = unzipEntries(zip);
    assertNoCanaries(entries, privateSession, "live-room\\u003aprivate-room_42");
    String all = joinedText(entries);
    assertTrue(all.contains("live-room#1"), all);
    assertTrue(
        text(entries, "logs/lizzie/app.log").contains("session=(live-room#1), ready"),
        text(entries, "logs/lizzie/app.log"));
    assertTrue(
        text(entries, "logs/lizzie/readboard-trace.log")
            .contains("session=live-room#1, ready"),
        text(entries, "logs/lizzie/readboard-trace.log"));
    assertFalse(all.contains("live-room#2"), all);
    assertTrue(text(entries, "logs/lizzie/app.log").contains("session=" + applicationSession), all);
    assertTrue(text(entries, "logs/lizzie/app.log").contains("id=" + applicationSession), all);
    assertFalse(
        text(entries, "logs/lizzie/app.log")
            .contains("live-room#1" + applicationSession.substring(8)),
        all);
    JSONObject manifest = manifest(entries);
    assertEquals(applicationSession, manifest.getString("applicationSession"));
    assertEquals(1, manifest.getJSONArray("aliases").length());
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
    Map<String, byte[]> entries = unzipEntries(zip);
    assertTrue(entries.containsKey("logs/lizzie/engine-trace.log"));
    assertFalse(entries.containsKey("engine-trace.log"));
    String trace = text(entries, "logs/lizzie/engine-trace.log");
    assertTrue(trace.contains("raw-second"), trace);
    assertFalse(trace.contains("raw-first"), trace);
    JSONObject sources = manifest(entries).getJSONObject("sources");
    assertEquals("included", sources.getJSONObject("lizzie-engine-trace").getString("status"));
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
    DiagnosticBundleLimits limits = new DiagnosticBundleLimits(24, 1024, 24, 1024, 200, 200);
    Path zip =
        new DiagnosticBundleExporter(
                DiagnosticBundleExporter.defaultOutputDirectory(tempDir), limits)
            .export(request(runtime, EnumSet.of(TraceScope.ENGINE_GTP)));
    Map<String, byte[]> entries = unzipEntries(zip);
    JSONObject engine = source(manifest(entries), "lizzie-engine-trace");
    assertEquals("truncated", engine.getString("status"));
    assertTrue(engine.getBoolean("truncated"));
    assertTrue(entries.get("logs/lizzie/engine-trace.log").length <= 200 + 80);
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
    Map<String, byte[]> entries = unzipEntries(exportDefault(runtime));
    assertFalse(text(entries, "logs/lizzie/app.log").contains("old-evidence"));
  }

  @Test
  void gzipBombStopsAtInflationBudgetAndDoesNotPublishBombPayload() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    runtime.shutdown();
    Files.deleteIfExists(tempDir.resolve("logs/app.log"));
    Path bomb = tempDir.resolve("logs/archive/app.2099-01-01.0.log.gz");
    writeGzipRecords(bomb, 20_000, "GZIP_BOMB_CANARY_", 96);
    Files.setLastModifiedTime(bomb, FileTime.from(Instant.now().plusSeconds(60)));

    DiagnosticBundleLimits limits = new DiagnosticBundleLimits(24, 256, 24, 1024, 256);
    Path zip =
        new DiagnosticBundleExporter(
                DiagnosticBundleExporter.defaultOutputDirectory(tempDir), limits)
            .export(request(runtime, EnumSet.noneOf(TraceScope.class)));

    Map<String, String> entries = unzipTextEntries(zip);
    assertFalse(entries.get("logs/lizzie/app.log").contains("GZIP_BOMB_CANARY_"));
    JSONObject app =
        new JSONObject(entries.get("manifest.json"))
            .getJSONObject("sources")
            .getJSONObject("lizzie-app");
    assertEquals("error", app.getString("status"));
    assertEquals("inflation-limit", app.getString("reason"));
    assertEquals(1024L * 1024, app.getLong("scanBudgetBytes"));
    assertEquals(app.getLong("scanBudgetBytes"), app.getLong("scannedBytes"));
    try (var stream = Files.list(zip.getParent())) {
      assertFalse(stream.anyMatch(path -> path.getFileName().toString().endsWith(".partial")));
    }
  }

  @Test
  void archiveEnumerationHasHardEntryCapAndHonorsExportCancellation() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    Path archive = runtime.logsDirectory().resolve("archive");
    Files.createDirectories(archive);
    // These deliberately do not match a log stem. The bound must cover physical directory
    // enumeration, not only files accepted by a glob/filter.
    for (int index = 0; index < 1025; index++) {
      Files.write(archive.resolve(String.format("noise-%04d.tmp", index)), new byte[0]);
    }

    DiagnosticBundleLimits limits =
        new DiagnosticBundleLimits(24, 1234, 24, 0, 0, 0);
    DiagnosticBundleExporter exporter =
        new DiagnosticBundleExporter(
            DiagnosticBundleExporter.defaultOutputDirectory(tempDir), limits);
    assertEquals(64L * 1024 + 1234, exporter.estimateUncompressedBytes(request(runtime, Set.of())));

    Map<String, byte[]> entries =
        unzipEntries(exporter.export(request(runtime, EnumSet.noneOf(TraceScope.class))));
    JSONObject app = source(manifest(entries), "lizzie-app");
    assertEquals("truncated", app.getString("status"));
    assertTrue(app.getBoolean("fileListTruncated"));

    AtomicInteger cancellationPolls = new AtomicInteger();
    Path cancelledOutput = tempDir.resolve("cancelled-diagnostics");
    DiagnosticBundleExporter cancelledExporter =
        new DiagnosticBundleExporter(cancelledOutput, limits);
    assertThrows(
        IOException.class,
        () ->
            cancelledExporter.export(
                request(runtime, EnumSet.noneOf(TraceScope.class)),
                () -> cancellationPolls.incrementAndGet() >= 20));
    assertTrue(cancellationPolls.get() >= 20);
    if (Files.isDirectory(cancelledOutput)) {
      try (var stream = Files.list(cancelledOutput)) {
        assertFalse(
            stream.anyMatch(
                path ->
                    path.getFileName().toString().endsWith(".zip")
                        || path.getFileName().toString().endsWith(".partial")));
      }
    }
  }

  @Test
  void newestFileFillingTailPreventsReadingCorruptOlderArchives() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    runtime.shutdown();
    Files.deleteIfExists(tempDir.resolve("logs/app.log"));
    Path archive = tempDir.resolve("logs/archive");
    Path older = archive.resolve("app.2026-01-01.0.log.gz");
    Path newest = archive.resolve("app.2026-01-02.0.log.gz");
    Files.createDirectories(archive);
    Files.writeString(older, "CORRUPT_OLDER_ARCHIVE_CANARY", StandardCharsets.UTF_8);
    writeGzipRecords(newest, 80, "NEWEST_ARCHIVE_CANARY_", 16);
    Files.setLastModifiedTime(older, FileTime.from(Instant.now().plusSeconds(60)));
    Files.setLastModifiedTime(newest, FileTime.from(Instant.now().plusSeconds(120)));

    DiagnosticBundleLimits limits = new DiagnosticBundleLimits(24, 512, 24, 1024, 512);
    Path zip =
        new DiagnosticBundleExporter(
                DiagnosticBundleExporter.defaultOutputDirectory(tempDir), limits)
            .export(request(runtime, EnumSet.noneOf(TraceScope.class)));

    Map<String, String> entries = unzipTextEntries(zip);
    String appLog = entries.get("logs/lizzie/app.log");
    assertTrue(appLog.contains("NEWEST_ARCHIVE_CANARY_79"), appLog);
    assertFalse(appLog.contains("CORRUPT_OLDER_ARCHIVE_CANARY"), appLog);
    assertTrue(appLog.getBytes(StandardCharsets.UTF_8).length <= 512, appLog);
    JSONObject app =
        new JSONObject(entries.get("manifest.json"))
            .getJSONObject("sources")
            .getJSONObject("lizzie-app");
    assertEquals("truncated", app.getString("status"));
    assertFalse(app.has("readErrors"), app.toString());
  }

  @Test
  void malformedTimestampRecordAndContinuationsAreExcludedFailClosed() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    runtime.shutdown();
    Path app = tempDir.resolve("logs/app.log");
    String validTimestamp = LocalDateTime.now().format(LOG_TIMESTAMP);
    Files.writeString(
        app,
        validTimestamp
            + " INFO  [lizzie.app] VALID_BEFORE_MALFORMED\n"
            + "2026-02-30 25:61:61.999 INFO  [lizzie.app] MALFORMED_TIMESTAMP_CANARY\n"
            + "MALFORMED_CONTINUATION_CANARY\n"
            + validTimestamp
            + " INFO  [lizzie.app] VALID_AFTER_MALFORMED\n",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);

    Map<String, String> entries = unzipTextEntries(exportDefault(runtime));
    String appLog = entries.get("logs/lizzie/app.log");
    assertTrue(appLog.contains("VALID_BEFORE_MALFORMED"), appLog);
    assertTrue(appLog.contains("VALID_AFTER_MALFORMED"), appLog);
    assertFalse(appLog.contains("MALFORMED_TIMESTAMP_CANARY"), appLog);
    assertFalse(appLog.contains("MALFORMED_CONTINUATION_CANARY"), appLog);
    JSONObject appSource =
        new JSONObject(entries.get("manifest.json"))
            .getJSONObject("sources")
            .getJSONObject("lizzie-app");
    assertTrue(appSource.getInt("malformedRecordsExcluded") >= 1, appSource.toString());
  }

  @Test
  void concurrentExportsUseDistinctAtomicTargetsAndLeaveNoPartials() throws Exception {
    LoggingRuntime runtime = start();
    LoggerFactory.getLogger(LogCategories.APP).info("concurrent-export-evidence");
    runtime.awaitIdle();
    Path diagnostics = DiagnosticBundleExporter.defaultOutputDirectory(tempDir);
    DiagnosticBundleExporter exporter = new DiagnosticBundleExporter(diagnostics);
    DiagnosticBundleRequest request = request(runtime, EnumSet.noneOf(TraceScope.class));
    ExecutorService workers = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Callable<Path> task =
          () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
              throw new IOException("concurrent export start timed out");
            }
            return exporter.export(request);
          };
      Future<Path> firstFuture = workers.submit(task);
      Future<Path> secondFuture = workers.submit(task);
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      Path first = firstFuture.get(20, TimeUnit.SECONDS);
      Path second = secondFuture.get(20, TimeUnit.SECONDS);
      assertNotEquals(first, second);
      assertTrue(
          unzipTextEntries(first)
              .get("logs/lizzie/app.log")
              .contains("concurrent-export-evidence"));
      assertTrue(
          unzipTextEntries(second)
              .get("logs/lizzie/app.log")
              .contains("concurrent-export-evidence"));
      try (var stream = Files.list(diagnostics)) {
        assertFalse(stream.anyMatch(path -> path.getFileName().toString().endsWith(".partial")));
      }
    } finally {
      start.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void precreatedPublicationTargetIsNeverOverwritten() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    Path diagnostics = DiagnosticBundleExporter.defaultOutputDirectory(tempDir);
    Files.createDirectories(diagnostics);
    Path occupied = diagnostics.resolve("attacker-owned.zip");
    Files.writeString(occupied, "COLLISION_SENTINEL", StandardCharsets.UTF_8);
    DiagnosticBundleExporter exporter =
        new DiagnosticBundleExporter(
            diagnostics,
            DiagnosticBundleLimits.production(),
            path -> {},
            captureTime -> "attacker-owned.zip");

    assertThrows(
        IOException.class,
        () -> exporter.export(request(runtime, EnumSet.noneOf(TraceScope.class))));

    assertEquals("COLLISION_SENTINEL", Files.readString(occupied, StandardCharsets.UTF_8));
    try (var stream = Files.list(diagnostics)) {
      assertEquals(1, stream.count());
    }
  }

  @Test
  void exportUsesOneTraceSnapshotWhenSessionChangesAfterCapture() throws Exception {
    LoggingRuntime runtime = start();
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    String capturedSession = runtime.currentTraceSessionId();
    LoggerFactory.getLogger(LogCategories.ENGINE_TRACE)
        .info("CAPTURED_ENGINE_CANARY session={}", capturedSession);
    runtime.awaitIdle();
    AtomicBoolean switched = new AtomicBoolean();
    AtomicReference<String> replacementSession = new AtomicReference<>();

    Path zip =
        new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
            .export(
                request(runtime, EnumSet.allOf(TraceScope.class)),
                () -> {
                  if (switched.compareAndSet(false, true)) {
                    runtime.stopFullTrace();
                    runtime.startFullTrace(EnumSet.of(TraceScope.NETWORK_WEBSOCKET));
                    replacementSession.set(runtime.currentTraceSessionId());
                    LoggerFactory.getLogger(LogCategories.NETWORK_TRACE)
                        .info("REPLACEMENT_NETWORK_CANARY session={}", replacementSession.get());
                    runtime.awaitIdle();
                  }
                  return false;
                });

    assertTrue(switched.get());
    assertNotEquals(capturedSession, replacementSession.get());
    Map<String, String> entries = unzipTextEntries(zip);
    assertTrue(
        entries.get("logs/lizzie/engine-trace.log").contains("CAPTURED_ENGINE_CANARY"));
    assertFalse(entries.containsKey("logs/lizzie/network-trace.log"));
    JSONObject manifest = new JSONObject(entries.get("manifest.json"));
    assertEquals(capturedSession, manifest.getString("traceSession"));
    assertEquals(1, manifest.getJSONArray("activeTraceScopes").length());
    assertEquals("engine-gtp", manifest.getJSONArray("activeTraceScopes").getString(0));
    JSONObject network =
        manifest.getJSONObject("sources").getJSONObject("lizzie-network-trace");
    assertEquals("omitted", network.getString("status"));
    assertEquals("scope-not-active-at-capture", network.getString("reason"));
  }

  @Test
  void cancellationDuringStreamingRemovesTemporaryPackage() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    runtime.shutdown();
    Files.deleteIfExists(tempDir.resolve("logs/app.log"));
    Path source = tempDir.resolve("logs/archive/app.2099-01-01.0.log.gz");
    writeGzipRecords(source, 5_000, "CANCEL_STREAM_CANARY_", 64);
    Files.setLastModifiedTime(source, FileTime.from(Instant.now().plusSeconds(60)));
    Path diagnostics = DiagnosticBundleExporter.defaultOutputDirectory(tempDir);
    AtomicInteger checks = new AtomicInteger();

    assertThrows(
        IOException.class,
        () ->
            new DiagnosticBundleExporter(diagnostics)
                .export(
                    request(runtime, EnumSet.noneOf(TraceScope.class)),
                    () -> checks.incrementAndGet() >= 4));

    assertTrue(checks.get() >= 4);
    if (Files.isDirectory(diagnostics)) {
      try (var stream = Files.list(diagnostics)) {
        assertEquals(0, stream.count());
      }
    }
  }

  @Test
  void replacingNewlyOpenedPartialWithRegularFileCannotBindOrPublishAttackerBytes()
      throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    Path diagnostics = DiagnosticBundleExporter.defaultOutputDirectory(tempDir);
    AtomicReference<Path> displaced = new AtomicReference<>();
    AtomicReference<Path> replacement = new AtomicReference<>();
    DiagnosticBundleExporter exporter =
        new DiagnosticBundleExporter(
            diagnostics,
            DiagnosticBundleLimits.production(),
            partial -> {
              Path moved =
                  partial.resolveSibling(partial.getFileName().toString() + ".displaced");
              Files.move(partial, moved);
              Files.writeString(
                  partial,
                  "ATTACKER_REGULAR_FILE_SENTINEL",
                  StandardCharsets.UTF_8,
                  StandardOpenOption.CREATE_NEW);
              displaced.set(moved);
              replacement.set(partial);
            });

    assertThrows(
        IOException.class,
        () -> exporter.export(request(runtime, EnumSet.noneOf(TraceScope.class))));

    assertTrue(displaced.get() != null);
    if (System.getProperty("os.name", "").startsWith("Windows")) {
      assertFalse(
          Files.exists(displaced.get()),
          "DELETE_ON_CLOSE must remove the opened file after it is renamed");
    } else {
      assertTrue(Files.isRegularFile(displaced.get()));
      assertEquals(0, Files.size(displaced.get()), displaced.get().toString());
    }
    assertEquals(
        "ATTACKER_REGULAR_FILE_SENTINEL",
        Files.readString(replacement.get(), StandardCharsets.UTF_8));
    try (var stream = Files.list(diagnostics)) {
      assertFalse(stream.anyMatch(path -> path.getFileName().toString().endsWith(".zip")));
    }
  }

  @Test
  void renamedWrittenPartialIsScrubbedThroughItsOpenHandleWithoutDeleteOnClose()
      throws Exception {
    LoggingRuntime runtime = start();
    String canary = "PERSISTENT_SENSITIVE_PARTIAL_CANARY_52fd8d19";
    LoggerFactory.getLogger(LogCategories.APP).error("diagnostic payload {}", canary);
    runtime.awaitIdle();
    assertTrue(
        Files.readString(tempDir.resolve("logs/app.log"), StandardCharsets.UTF_8).contains(canary),
        "the sensitive source payload must reach the flushed application log");
    Path diagnostics = DiagnosticBundleExporter.defaultOutputDirectory(tempDir);
    AtomicReference<Path> displaced = new AtomicReference<>();
    AtomicBoolean observedWrittenPayload = new AtomicBoolean();
    DiagnosticBundleExporter exporter =
        new DiagnosticBundleExporter(
            diagnostics,
            DiagnosticBundleLimits.production(),
            new DiagnosticBundleExporter.PartialFileObserver() {
              @Override
              public void created(Path partial) {}

              @Override
              public void payloadWritten(Path partial) throws IOException {
                assertTrue(Files.size(partial) > 0, "the observer must run after ZIP bytes exist");
                Path moved =
                    partial.resolveSibling(partial.getFileName().toString() + ".written-displaced");
                Files.move(partial, moved);
                // An exclusive FileLock is mandatory on Windows, so a second read handle cannot
                // inspect the finished ZIP until cleanup closes the owner. The flushed source,
                // non-zero archive size, and this post-ZipOutputStream hook establish that the
                // test renamed an inode after payload streaming rather than at zero bytes.
                observedWrittenPayload.set(true);
                displaced.set(moved);
              }
            },
            DiagnosticBundleExporter.PartialFileCleanupStrategy.PATH_DELETE);

    IOException exportFailure =
        assertThrows(
            IOException.class,
            () -> exporter.export(request(runtime, EnumSet.noneOf(TraceScope.class))));

    assertTrue(
        observedWrittenPayload.get(),
        "the after-write attack hook must have executed; export failed early: " + exportFailure);
    assertTrue(Files.isRegularFile(displaced.get()), "the attacker-renamed inode remains named");
    assertEquals(
        0L,
        Files.size(displaced.get()),
        "failure cleanup must scrub the renamed inode through the original channel");
    assertFalse(
        new String(Files.readAllBytes(displaced.get()), StandardCharsets.ISO_8859_1)
            .contains(canary),
        "no payload canary may remain at the attacker-controlled pathname");
    try (var stream = Files.list(diagnostics)) {
      assertFalse(stream.anyMatch(path -> path.getFileName().toString().endsWith(".zip")));
    }
  }

  @Test
  void symbolicLogSourceIsNeverFollowedIntoBundle() throws Exception {
    Assumptions.assumeTrue(supportsSymbolicLinks(), "symbolic links are unavailable");
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    runtime.shutdown();
    Path appLog = tempDir.resolve("logs/app.log");
    Files.delete(appLog);
    Path outside = tempDir.resolve("outside-source.log");
    Files.writeString(
        outside,
        LocalDateTime.now().format(LOG_TIMESTAMP)
            + " ERROR [lizzie.app] SOURCE_SYMLINK_CANARY\n",
        StandardCharsets.UTF_8);
    Files.createSymbolicLink(appLog, outside.toAbsolutePath());

    Map<String, String> entries = unzipTextEntries(exportDefault(runtime));
    assertFalse(String.join("\n", entries.values()).contains("SOURCE_SYMLINK_CANARY"));
    JSONObject app =
        new JSONObject(entries.get("manifest.json"))
            .getJSONObject("sources")
            .getJSONObject("lizzie-app");
    assertEquals("failed", app.getString("status"));
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

  @Test
  void dualProcessAppAndCrashUseSeparateNamespacesWindowsAndCaps() throws Exception {
    LoggingRuntime runtime = start();
    LoggerFactory.getLogger(LogCategories.APP).info("host-app-now");
    runtime.awaitIdle();
    Path rb = readBoardRoot(runtime);
    Files.createDirectories(rb);
    Files.writeString(
        rb.resolve("app.log"),
        jsonl("2020-01-01T00:00:00.000Z", "app", PROCESS_SESSION, "rb-old", null)
            + jsonl(Instant.now().toString(), "app", PROCESS_SESSION, "rb-app-now", null));
    Files.writeString(
        rb.resolve("crash.log"),
        jsonl(Instant.now().toString(), "crash", PROCESS_SESSION, "rb-crash-now", null));

    Map<String, byte[]> windowed =
        unzipEntries(
            new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
                .export(request(runtime, helperSnapshot(PROCESS_SESSION, false, false))));
    assertTrue(windowed.containsKey("logs/lizzie/app.log"));
    assertTrue(windowed.containsKey("logs/readboard/app.log"));
    assertTrue(windowed.containsKey("logs/readboard/crash.log"));
    assertFalse(text(windowed, "logs/readboard/app.log").contains("rb-old"));
    assertTrue(text(windowed, "logs/readboard/app.log").contains("rb-app-now"));
    assertTrue(text(windowed, "logs/readboard/crash.log").contains("rb-crash-now"));
    assertEquals("logs/lizzie/", source(manifest(windowed), "lizzie-app").getString("namespace"));
    assertEquals("logs/readboard/", source(manifest(windowed), "readboard-app").getString("namespace"));

    StringBuilder padding = new StringBuilder();
    for (int i = 0; i < 80; i++) {
      padding.append("pad-").append(i).append('\n');
    }
    Files.writeString(tempDir.resolve("logs/app.log"), padding.toString(), java.nio.file.StandardOpenOption.APPEND);
    Files.writeString(rb.resolve("app.log"), padding.toString(), java.nio.file.StandardOpenOption.APPEND);
    DiagnosticBundleLimits limits = new DiagnosticBundleLimits(24, 40, 24, 1024, 1024, 1024);
    Map<String, byte[]> capped =
        unzipEntries(
            new DiagnosticBundleExporter(
                    DiagnosticBundleExporter.defaultOutputDirectory(tempDir), limits)
                .export(request(runtime, helperSnapshot(PROCESS_SESSION, false, false))));
    assertEquals("truncated", source(manifest(capped), "lizzie-app").getString("status"));
    assertEquals("truncated", source(manifest(capped), "readboard-app").getString("status"));
    assertTrue(capped.get("logs/lizzie/app.log").length <= 40 + 80);
    assertTrue(capped.get("logs/readboard/app.log").length <= 40 + 80);
  }

  @Test
  void defaultExportExcludesEveryFullTraceSource() throws Exception {
    LoggingRuntime runtime = start();
    runtime.startFullTrace(EnumSet.allOf(TraceScope.class));
    LoggerFactory.getLogger(LogCategories.ENGINE_TRACE).info("host-engine-live");
    runtime.awaitIdle();
    Files.createDirectories(readBoardRoot(runtime));
    Files.writeString(
        readBoardRoot(runtime).resolve("trace.log"),
        jsonl(Instant.now().toString(), "trace", PROCESS_SESSION, "rb-trace-live", null));

    Map<String, byte[]> entries =
        unzipEntries(
            new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
                .export(request(runtime, helperSnapshot(PROCESS_SESSION, false, true))));
    Set<String> names = entries.keySet();
    assertFalse(names.contains("logs/lizzie/engine-trace.log"));
    assertFalse(names.contains("logs/lizzie/readboard-trace.log"));
    assertFalse(names.contains("logs/lizzie/network-trace.log"));
    assertFalse(names.contains("logs/readboard/trace.log"));
    JSONObject manifest = manifest(entries);
    assertEquals("omitted", source(manifest, "lizzie-engine-trace").getString("status"));
    assertEquals("omitted", source(manifest, "lizzie-readboard-trace").getString("status"));
    assertEquals("omitted", source(manifest, "lizzie-network-trace").getString("status"));
    assertEquals("omitted", source(manifest, "readboard-trace").getString("status"));
    assertEquals("not-requested", source(manifest, "readboard-trace").getString("reason"));
  }

  @Test
  void fullTraceOptInKeepsCurrentHostAndProcessSessionsAndDoesNotSubstituteOld() throws Exception {
    LoggingRuntime runtime = start();
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    String first = runtime.currentTraceSessionId();
    LoggerFactory.getLogger(LogCategories.ENGINE_TRACE).info("host-old session={}", first);
    runtime.awaitIdle();
    runtime.stopFullTrace();
    Files.createDirectories(readBoardRoot(runtime));
    Files.writeString(
        readBoardRoot(runtime).resolve("trace.log"),
        jsonl(Instant.now().toString(), "trace", OLD_PROCESS_SESSION, "rb-old-trace", null)
            + jsonl(Instant.now().toString(), "trace", PROCESS_SESSION, "rb-current-trace", null));

    Map<String, byte[]> missingCurrent =
        unzipEntries(
            new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
                .export(
                    request(
                        runtime,
                        EnumSet.of(TraceScope.ENGINE_GTP),
                        true,
                        true,
                        helperSnapshot(PROCESS_SESSION, false, true))));
    assertFalse(missingCurrent.containsKey("logs/lizzie/engine-trace.log"));
    assertEquals(
        "no-active-session",
        source(manifest(missingCurrent), "lizzie-engine-trace").getString("reason"));
    assertEquals("omitted", source(manifest(missingCurrent), "lizzie-engine-trace").getString("status"));
    assertTrue(text(missingCurrent, "logs/readboard/trace.log").contains("rb-current-trace"));
    assertFalse(text(missingCurrent, "logs/readboard/trace.log").contains("rb-old-trace"));

    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    String current = runtime.currentTraceSessionId();
    LoggerFactory.getLogger(LogCategories.ENGINE_TRACE).info("host-current session={}", current);
    runtime.awaitIdle();
    Map<String, byte[]> included =
        unzipEntries(
            new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
                .export(
                    request(
                        runtime,
                        EnumSet.of(TraceScope.ENGINE_GTP),
                        true,
                        true,
                        helperSnapshot(PROCESS_SESSION, false, true))));
    String hostTrace = text(included, "logs/lizzie/engine-trace.log");
    assertTrue(hostTrace.contains("host-current"), hostTrace);
    assertFalse(hostTrace.contains("host-old"), hostTrace);
    assertTrue(text(included, "logs/readboard/trace.log").contains("rb-current-trace"));
    assertFalse(text(included, "logs/readboard/trace.log").contains("rb-old-trace"));
  }

  @Test
  void captureCollectsCompleteCurrentEventDirectoryAndLeavesActiveWriterAlone() throws Exception {
    LoggingRuntime runtime = start();
    Path capture = readBoardRoot(runtime).resolve("capture");
    writeCaptureEvent(
        capture,
        "20260821-170300-123-0001-recognition-success",
        PROCESS_SESSION,
        "current-event",
        "/home/dev/capture-current.png",
        "nickname=AliceFox token=" + CANARY_TOKEN);
    writeCaptureEvent(
        capture,
        "20260820-170300-123-0001-recognition-success",
        OLD_PROCESS_SESSION,
        "old-event",
        "/home/dev/capture-old.png",
        "old-session-text");
    Path incomplete = capture.resolve("20260821-180000-000-0002-recognition-success");
    Files.createDirectories(incomplete);
    Files.write(incomplete.resolve("frame.png"), PIXEL_PNG);
    Files.writeString(capture.resolve("debug.log"), "root debug path=/home/dev/capture-debug.png\n");

    Path zip =
        new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
            .export(
                request(
                    runtime,
                    EnumSet.noneOf(TraceScope.class),
                    false,
                    true,
                    helperSnapshot(PROCESS_SESSION, true, false)));
    Map<String, byte[]> entries = unzipEntries(zip);
    String prefix =
        "diagnostics/readboard-capture/20260821-170300-123-0001-recognition-success/";
    assertTrue(entries.containsKey(prefix + "frame.png"));
    assertTrue(entries.containsKey(prefix + "metadata.json"));
    assertTrue(entries.containsKey(prefix + "recognition.txt"));
    assertTrue(entries.containsKey("diagnostics/readboard-capture/debug.log"));
    assertArrayEquals(PIXEL_PNG, entries.get(prefix + "frame.png"));
    assertFalse(
        entries.keySet().stream()
            .anyMatch(name -> name.contains("20260820-170300-123-0001-recognition-success")));
    assertFalse(
        entries.keySet().stream()
            .anyMatch(name -> name.contains("20260821-180000-000-0002-recognition-success")));
    String metadata = text(entries, prefix + "metadata.json");
    assertTrue(metadata.contains("current-event"), metadata);
    assertFalse(metadata.contains("/home/dev/capture-current.png"), metadata);
    assertFalse(text(entries, prefix + "recognition.txt").contains(CANARY_TOKEN));
    assertEquals("included", source(manifest(entries), "readboard-capture").getString("status"));
    assertTrue(Files.exists(incomplete.resolve("frame.png")));
  }

  @Test
  void captureTruncatesOnCompleteEventDirectoryBoundary() throws Exception {
    LoggingRuntime runtime = start();
    Path capture = readBoardRoot(runtime).resolve("capture");
    writeCaptureEvent(
        capture, "20260821-170300-123-0001-first", PROCESS_SESSION, "first", "/tmp/a.png", "first-text");
    writeCaptureEvent(
        capture,
        "20260821-170301-123-0002-second",
        PROCESS_SESSION,
        "second",
        "/tmp/b.png",
        "second-text");
    long newestBytes = directorySize(capture.resolve("20260821-170301-123-0002-second"));
    DiagnosticBundleLimits limits =
        new DiagnosticBundleLimits(24, 1024, 24, 1024, 1024, newestBytes);
    Map<String, byte[]> entries =
        unzipEntries(
            new DiagnosticBundleExporter(
                    DiagnosticBundleExporter.defaultOutputDirectory(tempDir), limits)
                .export(
                    request(
                        runtime,
                        EnumSet.noneOf(TraceScope.class),
                        false,
                        true,
                        helperSnapshot(PROCESS_SESSION, true, false))));
    JSONObject captureSource = source(manifest(entries), "readboard-capture");
    assertEquals("truncated", captureSource.getString("status"));
    assertTrue(captureSource.getBoolean("truncated"));
    boolean first =
        entries.keySet().stream().anyMatch(name -> name.contains("20260821-170300-123-0001-first"));
    boolean second =
        entries.keySet().stream().anyMatch(name -> name.contains("20260821-170301-123-0002-second"));
    assertFalse(first);
    assertTrue(second);
    assertTrue(captureSource.getLong("bytes") <= newestBytes);
  }

  @Test
  void untaggedCaptureEventsAreBoundToCurrentProcessObservationTime() throws Exception {
    LoggingRuntime runtime = start();
    ReadBoardLoggingSnapshot helper = helperSnapshot(PROCESS_SESSION, true, false);
    Path capture = readBoardRoot(runtime).resolve("capture");
    writeCaptureEvent(
        capture,
        "20200101-000000-000-0001-old-untagged",
        null,
        Instant.parse("2020-01-01T00:00:00Z"),
        "old-untagged",
        "/tmp/old.png",
        "old-untagged-text");
    writeCaptureEvent(
        capture,
        "20260821-170300-123-0001-current-untagged",
        null,
        Instant.now(),
        "current-untagged",
        "/tmp/now.png",
        "current-untagged-text");
    writeCaptureEvent(
        capture,
        "20990101-000000-000-0002-future-untagged",
        null,
        Instant.now().plusSeconds(3600),
        "future-untagged",
        "/tmp/future.png",
        "future-untagged-text");
    Map<String, byte[]> entries =
        unzipEntries(
            new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
                .export(
                    request(
                        runtime, EnumSet.noneOf(TraceScope.class), false, true, helper)));
    assertTrue(
        entries.keySet().stream()
            .anyMatch(name -> name.contains("20260821-170300-123-0001-current-untagged")));
    assertFalse(
        entries.keySet().stream()
            .anyMatch(name -> name.contains("20200101-000000-000-0001-old-untagged")));
    assertFalse(
        entries.keySet().stream()
            .anyMatch(name -> name.contains("20990101-000000-000-0002-future-untagged")));
  }

  @Test
  void manifestRecordsHelperMissingAndUnreadableSourcesWithoutFailingZip() throws Exception {
    LoggingRuntime runtime = start();
    Path rb = readBoardRoot(runtime);
    Files.createDirectories(rb);
    Path unreadable = rb.resolve("app.log");
    boolean posix = Files.getFileStore(rb).supportsFileAttributeView("posix");
    boolean unreadableSource = false;
    if (posix) {
      Files.writeString(unreadable, jsonl(Instant.now().toString(), "app", PROCESS_SESSION, "hidden", null));
      Files.setPosixFilePermissions(unreadable, Set.of());
      unreadableSource = !Files.isReadable(unreadable);
      if (!unreadableSource) {
        // Privileged test processes can retain read access despite an empty POSIX mode. Fall back
        // to the portable missing-source branch instead of asserting a condition we did not make.
        Files.deleteIfExists(unreadable);
      }
    }

    Map<String, byte[]> detached = unzipEntries(exportDefault(runtime));
    JSONObject detachedManifest = manifest(detached);
    assertEquals("helper-not-started", source(detachedManifest, "readboard-capture").getString("reason"));
    assertEquals("omitted", source(detachedManifest, "readboard-capture").getString("status"));
    assertTrue(detached.containsKey("manifest.json"));

    ReadBoardLoggingSnapshot started = helperSnapshot(PROCESS_SESSION, true, false);
    Map<String, byte[]> helperOn =
        unzipEntries(
            new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
                .export(
                    request(
                        runtime, EnumSet.noneOf(TraceScope.class), false, true, started)));
    JSONObject helperManifest = manifest(helperOn);
    JSONObject rbApp = source(helperManifest, "readboard-app");
    // A discovered source that cannot be read is a per-source read error; a platform on which
    // POSIX unreadability cannot be created exercises the distinct missing-source path.
    assertEquals(unreadableSource ? "error" : "failed", rbApp.getString("status"));
    assertTrue(rbApp.getBoolean("failed"));
    assertFalse(rbApp.getBoolean("included"));
    assertEquals(unreadableSource ? "unreadable" : "missing", rbApp.getString("reason"));
    if (unreadableSource) {
      assertTrue(rbApp.getInt("readErrors") > 0);
    }
    assertEquals("missing", source(helperManifest, "readboard-crash").getString("reason"));
    assertEquals("failed", source(helperManifest, "readboard-crash").getString("status"));
    assertEquals("no-current-session", source(helperManifest, "readboard-capture").getString("reason"));
    assertEquals("omitted", source(helperManifest, "readboard-capture").getString("status"));
    assertTrue(helperOn.containsKey("manifest.json"));
    if (posix) {
      try {
        Files.setPosixFilePermissions(unreadable, Set.of(PosixFilePermission.OWNER_READ));
      } catch (IOException ignored) {
      }
    }
  }

  @Test
  void jsonFieldNamesAreSanitizedWithoutDroppingTheirSafeValues() {
    JSONObject raw =
        new JSONObject()
            .put("redacted-field-1", "existing-field")
            .put("safe.key-1", "password=CANARY_SAFE_VALUE")
            .put("C:\\Users\\CANARY_JSON_KEY_OWNER\\notes", "path-key-value")
            .put("https://key-canary.invalid/CANARY_JSON_URL_KEY", "url-key-value")
            .put("password=CANARY_JSON_PASSWORD_KEY", "CANARY_JSON_PASSWORD_VALUE");

    JSONObject sanitized = new ExportSanitizer().sanitizeJsonObject(raw);

    assertEquals(
        Set.of(
            "redacted-field-1",
            "safe.key-1",
            "redacted-field-2",
            "redacted-field-3",
            "redacted-field-4"),
        sanitized.keySet());
    assertEquals("existing-field", sanitized.getString("redacted-field-1"));
    assertEquals("path-key-value", sanitized.getString("redacted-field-2"));
    assertEquals("url-key-value", sanitized.getString("redacted-field-3"));
    assertEquals("<redacted>", sanitized.getString("redacted-field-4"));
    assertEquals("password=<redacted>", sanitized.getString("safe.key-1"));
    assertFalse(sanitized.toString().contains("CANARY"), sanitized.toString());
  }

  @Test
  void arbitraryJsonRootsAreStrictlyParsedAndSanitized() {
    String userCanary = "CANARY_ROOT_USER_TEXT";
    String sessionCanary = "CANARY_ROOT_SESSION_ID";
    String pathCanary = "C:\\Users\\CANARY_ROOT_PATH_OWNER\\capture.png";
    JSONArray root =
        new JSONArray()
            .put(tagged(userCanary, "userText"))
            .put(
                new JSONObject()
                    .put("session", tagged(sessionCanary, "sessionId"))
                    .put("path", tagged(pathCanary, "localPath")))
            .put(tagged("CANARY_ROOT_SECRET", "secret"));
    ExportSanitizer sanitizer = new ExportSanitizer();

    String rendered = sanitizer.sanitizeJson(root.toString());

    assertTrue(ExportSanitizer.parseJsonValueStrict(rendered) instanceof JSONArray, rendered);
    assertTrue(rendered.contains("nickname#1"), rendered);
    assertTrue(rendered.contains("session#1"), rendered);
    assertTrue(rendered.contains("path#1"), rendered);
    assertFalse(rendered.contains("CANARY"), rendered);
    String scalar = sanitizer.sanitizeJson("\"password=CANARY_SCALAR_PASSWORD\"");
    assertEquals("password=<redacted>", ExportSanitizer.parseJsonValueStrict(scalar));
    assertEquals("42", sanitizer.sanitizeJson("42"));
    assertEquals("true", sanitizer.sanitizeJson("true"));
    assertEquals("null", sanitizer.sanitizeJson("null"));
    assertThrows(JSONException.class, () -> sanitizer.sanitizeJson("{} trailing-content"));
  }

  @Test
  void captureJsonAndJsonlAreStructurallySanitizedAndMalformedRecordsFailClosed()
      throws Exception {
    LoggingRuntime runtime = start();
    String eventName = "20260821-170300-123-0001-json-structure";
    Path captureRoot = readBoardRoot(runtime).resolve("capture");
    writeCaptureEvent(
        captureRoot,
        eventName,
        PROCESS_SESSION,
        "json-structure",
        "/tmp/json-structure.png",
        "capture-json-structure");
    Path event = captureRoot.resolve(eventName);

    String rootUserCanary = "CANARY_CAPTURE_ROOT_USER";
    String rootSessionCanary = "CANARY_CAPTURE_ROOT_SESSION";
    String rootPathOwnerCanary = "CANARY_CAPTURE_ROOT_OWNER";
    String rootPathCanary = "C:\\Users\\" + rootPathOwnerCanary + "\\frame.png";
    JSONArray rootArray =
        new JSONArray()
            .put(tagged(rootUserCanary, "userText"))
            .put(tagged(rootSessionCanary, "sessionId"))
            .put(tagged(rootPathCanary, "localPath"));
    Files.writeString(
        event.resolve("details.json"), rootArray.toString(), StandardCharsets.UTF_8);

    String lineUserCanary = "CANARY_JSONL_OBJECT_USER";
    String lineSessionCanary = "CANARY_JSONL_ARRAY_SESSION";
    String linePathOwnerCanary = "CANARY_JSONL_ARRAY_OWNER";
    String linePathCanary = "/home/" + linePathOwnerCanary + "/private.png";
    String malformedCanary = "CANARY_JSONL_MALFORMED";
    String laterCanary = "CANARY_JSONL_LATER_USER";
    String scalarCanary = "CANARY_JSONL_SCALAR";
    String objectLine =
        new JSONObject().put("profile", tagged(lineUserCanary, "userText")).toString();
    String arrayLine =
        new JSONArray()
            .put(tagged(lineSessionCanary, "sessionId"))
            .put(tagged(linePathCanary, "localPath"))
            .toString();
    String malformedLine = "{\"message\":\"" + malformedCanary + "\"";
    String laterLine =
        new JSONObject().put("profile", tagged(laterCanary, "userText")).toString();
    String scalarLine = "\"" + scalarCanary + "\"";
    Files.writeString(
        event.resolve("events.jsonl"),
        objectLine
            + "\r\n"
            + arrayLine
            + "\n"
            + malformedLine
            + "\r"
            + laterLine
            + "\n"
            + scalarLine,
        StandardCharsets.UTF_8);

    String malformedJsonCanary = "CANARY_MALFORMED_JSON_ROOT";
    Files.writeString(
        event.resolve("broken.json"),
        "{\"message\":\"" + malformedJsonCanary + "\"} trailing-content",
        StandardCharsets.UTF_8);

    Map<String, byte[]> entries =
        unzipEntries(
            new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
                .export(
                    request(
                        runtime,
                        EnumSet.noneOf(TraceScope.class),
                        false,
                        true,
                        helperSnapshot(PROCESS_SESSION, true, false))));
    String prefix = "diagnostics/readboard-capture/" + eventName + "/";
    String details = text(entries, prefix + "details.json");
    assertTrue(ExportSanitizer.parseJsonValueStrict(details) instanceof JSONArray, details);
    assertTrue(details.contains("nickname#"), details);
    assertTrue(details.contains("session#"), details);
    assertTrue(details.contains("path#"), details);
    assertEquals("\"[redaction-failed]\"", text(entries, prefix + "broken.json"));

    String jsonl = text(entries, prefix + "events.jsonl");
    String[] lines = jsonl.split("\\r\\n|\\n|\\r", -1);
    assertEquals(5, lines.length, jsonl);
    assertTrue(ExportSanitizer.parseJsonValueStrict(lines[0]) instanceof JSONObject, jsonl);
    assertTrue(ExportSanitizer.parseJsonValueStrict(lines[1]) instanceof JSONArray, jsonl);
    assertEquals("\"[redaction-failed]\"", lines[2], jsonl);
    assertTrue(ExportSanitizer.parseJsonValueStrict(lines[3]) instanceof JSONObject, jsonl);
    assertEquals("\"[redaction-failed]\"", lines[4], jsonl);
    assertTrue(lines[0].contains("nickname#"), jsonl);
    assertTrue(lines[1].contains("session#"), jsonl);
    assertTrue(lines[1].contains("path#"), jsonl);
    assertTrue(lines[3].contains("nickname#"), jsonl);
    assertEquals(
        lines[0]
            + "\r\n"
            + lines[1]
            + "\n"
            + lines[2]
            + "\r"
            + lines[3]
            + "\n"
            + lines[4],
        jsonl);
    assertNoCanaries(
        entries,
        rootUserCanary,
        rootSessionCanary,
        rootPathCanary,
        rootPathOwnerCanary,
        lineUserCanary,
        lineSessionCanary,
        linePathCanary,
        linePathOwnerCanary,
        malformedCanary,
        laterCanary,
        scalarCanary,
        malformedJsonCanary);
  }

  @Test
  void typedJsonlAndCaptureShareAliasPolicyAndDropSecretCanaries() throws Exception {
    LoggingRuntime runtime = start();
    String jsonlKeyCanary = "CANARY_JSONL_FIELD_NAME";
    String metadataKeyCanary = "CANARY_METADATA_FIELD_NAME";
    JSONObject fields = new JSONObject();
    fields.put("path", tagged("/home/dev/secret-config.txt", "localPath"));
    fields.put("url", tagged("https://example.test/board", "localUrl"));
    fields.put("nickname", tagged("AliceFox", "userText"));
    fields.put("token", tagged(CANARY_TOKEN, "secret"));
    fields.put("cookie", tagged(CANARY_COOKIE, "secret"));
    fields.put("authorization", tagged(CANARY_CREDENTIAL, "secret"));
    fields.put("machineKey", tagged(CANARY_MACHINE_KEY, "secret"));
    fields.put(
        "nestedSafe",
        tagged(new JSONObject().put("machineKey", CANARY_JSON_NESTED), "safe"));
    fields.put("unknownPrivacy", tagged(CANARY_JSON_UNKNOWN, "futurePrivacy"));
    fields.put(
        "C:\\Users\\" + jsonlKeyCanary + "\\notes",
        tagged("jsonl-key-value", "safe"));
    for (String privacy :
        new String[] {"localPath", "localUrl", "userText", "sessionId"}) {
      fields.put(
          "nonString-" + privacy,
          tagged(
              new JSONObject().put("opaque", CANARY_NONSTRING_SESSION + "-" + privacy),
              privacy));
    }
    Files.createDirectories(readBoardRoot(runtime));
    Files.writeString(
        readBoardRoot(runtime).resolve("app.log"),
        jsonl(Instant.now().toString(), "app", PROCESS_SESSION, "rb-tagged", fields));
    writeCaptureEvent(
        readBoardRoot(runtime).resolve("capture"),
        "20260821-170300-123-0001-recognition-success",
        PROCESS_SESSION,
        "capture-tagged",
        "/home/dev/secret-config.txt",
        "nickname=AliceFox url=https://example.test/board token=" + CANARY_TOKEN);
    Path captureMetadata =
        readBoardRoot(runtime)
            .resolve("capture")
            .resolve("20260821-170300-123-0001-recognition-success")
            .resolve("metadata.json");
    JSONObject metadataJson = new JSONObject();
    metadataJson.put("EventName", "capture-tagged");
    metadataJson.put("TimestampUtc", Instant.now().toString());
    metadataJson.put("processSessionId", PROCESS_SESSION);
    metadataJson.put("CapturePath", "/home/dev/secret-config.txt");
    metadataJson.put(
        "https://metadata-key.invalid/" + metadataKeyCanary,
        "metadata-key-value");
    metadataJson.put(
        "nestedSafe",
        tagged(new JSONObject().put("machineKey", CANARY_CAPTURE_NESTED), "safe"));
    metadataJson.put(
        "unknownPrivacy", tagged(CANARY_CAPTURE_UNKNOWN, "futurePrivacy"));
    Files.writeString(captureMetadata, metadataJson.toString(), StandardCharsets.UTF_8);

    Map<String, byte[]> entries =
        unzipEntries(
            new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
                .export(
                    request(
                        runtime,
                        EnumSet.noneOf(TraceScope.class),
                        false,
                        true,
                        helperSnapshot(PROCESS_SESSION, true, false))));
    String app = text(entries, "logs/readboard/app.log");
    assertFalse(app.contains("/home/dev/secret-config.txt"), app);
    assertFalse(app.contains("https://example.test/board"), app);
    assertFalse(app.contains("AliceFox"), app);
    assertFalse(app.contains(PROCESS_SESSION), app);
    assertFalse(app.contains(CANARY_TOKEN), app);
    assertFalse(app.contains(jsonlKeyCanary), app);
    assertTrue(app.contains("redacted-field-1"), app);
    assertTrue(app.contains("jsonl-key-value"), app);
    assertTrue(app.contains("path#1") || app.contains("<redacted-path>") || app.contains("/home/<user>"), app);
    assertTrue(app.contains("url#1") || app.contains("<redacted-url>"), app);
    assertTrue(app.contains("nickname#1") || app.contains("<redacted"), app);
    String metadata =
        text(
            entries,
            "diagnostics/readboard-capture/20260821-170300-123-0001-recognition-success/metadata.json");
    String recognition =
        text(
            entries,
            "diagnostics/readboard-capture/20260821-170300-123-0001-recognition-success/recognition.txt");
    assertFalse(metadata.contains("/home/dev/secret-config.txt"), metadata);
    assertFalse(metadata.contains(metadataKeyCanary), metadata);
    assertTrue(metadata.contains("redacted-field-1"), metadata);
    assertTrue(metadata.contains("metadata-key-value"), metadata);
    assertFalse(recognition.contains(CANARY_TOKEN), recognition);
    assertFalse(recognition.contains("AliceFox"), recognition);
    assertNoCanaries(
        entries,
        CANARY_PASSWORD,
        CANARY_TOKEN,
        CANARY_COOKIE,
        CANARY_MACHINE_KEY,
        CANARY_CREDENTIAL,
        CANARY_JSON_NESTED,
        CANARY_JSON_UNKNOWN,
        CANARY_CAPTURE_NESTED,
        CANARY_CAPTURE_UNKNOWN,
        CANARY_NONSTRING_SESSION,
        jsonlKeyCanary,
        metadataKeyCanary,
        PROCESS_SESSION,
        "/home/dev/secret-config.txt",
        "AliceFox");
  }

  @Test
  void previousBundleInsideLogsIsNotRecursivelyIncluded() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    Files.write(
        runtime.logsDirectory().resolve("lizzie-diagnostics-old.zip"),
        "old-bundle".getBytes(StandardCharsets.UTF_8));
    Files.createDirectories(readBoardRoot(runtime).resolve("capture"));
    Files.write(
        readBoardRoot(runtime).resolve("capture").resolve("old-export.zip"),
        "nested".getBytes(StandardCharsets.UTF_8));
    Map<String, byte[]> entries = unzipEntries(exportDefault(runtime));
    assertFalse(entries.keySet().stream().anyMatch(name -> name.endsWith(".zip")));
    assertFalse(joinedText(entries).contains("old-bundle"));
  }

  @Test
  void attachedHelperWithoutProcessSessionOmitsCaptureAsNoCurrentSession() throws Exception {
    LoggingRuntime runtime = start();
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(
            new ReadBoardLoggingControl.Desired(false, true, false), true);
    ReadBoardLoggingSnapshot helper = control.snapshot();
    Map<String, byte[]> entries =
        unzipEntries(
            new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
                .export(request(runtime, helper)));
    JSONObject capture = source(manifest(entries), "readboard-capture");
    assertSource(capture, true, false, "omitted", "diagnostics/readboard-capture/");
    assertEquals("no-current-session", capture.getString("reason"));
    assertFalse(
        entries.keySet().stream()
            .anyMatch(name -> name.startsWith("diagnostics/readboard-capture/")));
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

  private static DiagnosticBundleRequest request(
      LoggingRuntime runtime, ReadBoardLoggingSnapshot helper) {
    return request(runtime, EnumSet.noneOf(TraceScope.class), false, true, helper);
  }

  private static DiagnosticBundleRequest request(
      LoggingRuntime runtime,
      Set<TraceScope> rawScopes,
      boolean includeReadBoardTrace,
      boolean includeCapture,
      ReadBoardLoggingSnapshot helper) {
    return new DiagnosticBundleRequest(
        runtime,
        rawScopes,
        includeReadBoardTrace,
        includeCapture,
        new JSONObject(),
        emptySnapshot(),
        helper,
        "next-dev",
        "rb-test");
  }

  private static ReadBoardLoggingSnapshot helperSnapshot(
      String processSession, boolean capture, boolean trace) {
    ReadBoardLoggingControl control =
        new ReadBoardLoggingControl(
            new ReadBoardLoggingControl.Desired(false, capture, trace), true);
    control.onCapability(
        ReadBoardLoggingProtocol.tryParseCapability(
            "readboardLoggingV1 "
                + processSession
                + " off "
                + (capture ? "on" : "off")
                + " "
                + (trace ? "on" : "off")
                + " healthy 0"));
    return control.snapshot();
  }

  private Path readBoardRoot(LoggingRuntime runtime) {
    return runtime.logsDirectory().resolve("readboard");
  }

  private static void writeCaptureEvent(
      Path captureRoot,
      String eventDirectory,
      String processSession,
      String eventName,
      String capturePath,
      String recognition)
      throws IOException {
    writeCaptureEvent(
        captureRoot,
        eventDirectory,
        processSession,
        Instant.now(),
        eventName,
        capturePath,
        recognition);
  }

  private static void writeCaptureEvent(
      Path captureRoot,
      String eventDirectory,
      String processSession,
      Instant timestamp,
      String eventName,
      String capturePath,
      String recognition)
      throws IOException {
    Path event = captureRoot.resolve(eventDirectory);
    Files.createDirectories(event);
    Files.write(event.resolve("frame.png"), PIXEL_PNG);
    JSONObject metadata = new JSONObject();
    metadata.put("EventName", eventName);
    metadata.put("TimestampUtc", timestamp.toString());
    if (processSession != null && !processSession.isEmpty()) {
      metadata.put("processSessionId", processSession);
    }
    metadata.put("CapturePath", capturePath);
    Files.writeString(event.resolve("metadata.json"), metadata.toString());
    Files.writeString(event.resolve("recognition.txt"), recognition);
    Files.writeString(event.resolve("debug.log"), "event debug " + recognition + "\n");
  }

  private static String jsonl(
      String ts, String stream, String processSession, String message, JSONObject fields) {
    JSONObject line = new JSONObject();
    line.put("ts", ts);
    line.put("level", "INFO");
    line.put("stream", stream);
    line.put("eventId", "test.event");
    line.put("module", "test");
    line.put("hostSessionId", tagged(HOST_SESSION, "sessionId"));
    line.put("processSessionId", tagged(processSession, "sessionId"));
    JSONObject resolved = fields == null ? new JSONObject() : fields;
    resolved.put("message", tagged(message, "safe"));
    line.put("fields", resolved);
    return line.toString() + "\n";
  }

  private static JSONObject tagged(Object value, String privacy) {
    JSONObject field = new JSONObject();
    field.put("value", value);
    field.put("privacy", privacy);
    return field;
  }


  private static long directorySize(Path directory) throws IOException {
    long total = 0;
    try (var stream = Files.walk(directory)) {
      for (Path path : (Iterable<Path>) stream::iterator) {
        if (Files.isRegularFile(path)) {
          total += Files.size(path);
        }
      }
    }
    return total;
  }

  private static SyncDiagnosticsExportSnapshot emptySnapshot() {
    return new SyncDiagnosticsExportSnapshot(
        1L, null, java.util.List.of(), java.util.List.of(), java.util.List.of(), null);
  }

  private static Map<String, byte[]> unzipEntries(Path zip) throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        entries.put(entry.getName(), input.readAllBytes());
      }
    }
    return entries;
  }

  private static Map<String, String> unzipTextEntries(Path zip) throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    for (Map.Entry<String, byte[]> entry : unzipEntries(zip).entrySet()) {
      entries.put(entry.getKey(), new String(entry.getValue(), StandardCharsets.UTF_8));
    }
    return entries;
  }

  private static String text(Map<String, byte[]> entries, String name) {
    byte[] bytes = entries.get(name);
    return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
  }

  private static String escapedEmbeddedJson(String canary) {
    String slash = "\\";
    String quote = "\"";
    return "payload={"
        + slash
        + quote
        + "password"
        + slash
        + quote
        + ":"
        + slash
        + quote
        + "prefix"
        + slash
        + slash
        + slash
        + quote
        + canary
        + slash
        + quote
        + "}";
  }

  private boolean supportsSymbolicLinks() throws IOException {
    Path target = tempDir.resolve("symlink-capability-target");
    Path link = tempDir.resolve("symlink-capability-link");
    Files.writeString(target, "probe", StandardCharsets.UTF_8);
    try {
      Files.createSymbolicLink(link, target.toAbsolutePath());
      return Files.isSymbolicLink(link);
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      return false;
    } finally {
      Files.deleteIfExists(link);
      Files.deleteIfExists(target);
    }
  }

  private static void writeGzipRecords(Path path, int count, String prefix, int payloadLength)
      throws IOException {
    Files.createDirectories(path.getParent());
    String timestamp = LocalDateTime.now().format(LOG_TIMESTAMP);
    String payload = "x".repeat(payloadLength);
    try (OutputStream file = Files.newOutputStream(path);
        GZIPOutputStream gzip = new GZIPOutputStream(file)) {
      for (int i = 0; i < count; i++) {
        String record =
            timestamp
                + " INFO  [lizzie.app] "
                + prefix
                + i
                + " "
                + payload
                + '\n';
        gzip.write(record.getBytes(StandardCharsets.UTF_8));
      }
    }
  }

  @SuppressWarnings("unused")
  private static Set<String> zipEntries(Path zip) throws IOException {
    return new TreeSet<>(unzipTextEntries(zip).keySet());
  }

  private static String joinedText(Map<String, byte[]> entries) {
    StringBuilder all = new StringBuilder();
    for (byte[] bytes : entries.values()) {
      all.append(new String(bytes, StandardCharsets.ISO_8859_1)).append('\n');
    }
    return all.toString();
  }

  private static JSONObject manifest(Map<String, byte[]> entries) {
    return new JSONObject(text(entries, "manifest.json"));
  }

  private static JSONObject source(JSONObject manifest, String name) {
    return manifest.getJSONObject("sources").getJSONObject(name);
  }

  private static void assertRuntimeSnapshotShape(JSONObject runtime) {
    for (String field : RuntimeSnapshot.REQUIRED_FIELDS) {
      assertTrue(runtime.has(field), field + " missing from " + runtime);
    }
  }

  private static void assertRuntimeSnapshotMemoryNonNegative(JSONObject runtime) {
    assertNonNegativeIfPresent(runtime, "heapUsedMiB");
    assertNonNegativeIfPresent(runtime, "heapCommittedMiB");
    assertNonNegativeIfPresent(runtime, "heapMaxMiB");
    assertNonNegativeIfPresent(runtime, "nonHeapUsedMiB");
  }

  private static void assertWorkDirUsablePresentOrMissing(JSONObject runtime) {
    assertTrue(runtime.has("workDirUsableGiB"), runtime.toString());
    if (runtime.isNull("workDirUsableGiB")) {
      assertEquals(
          RuntimeSnapshot.MISSING_UNREADABLE,
          runtime.getJSONObject("missing").getString("workDirUsableGiB"));
    } else {
      assertTrue(runtime.getLong("workDirUsableGiB") >= 0L, runtime.toString());
    }
  }

  private static void assertNonNegativeIfPresent(JSONObject json, String field) {
    assertTrue(json.has(field), field);
    if (!json.isNull(field)) {
      assertTrue(json.getLong(field) >= 0L, field + "=" + json.get(field));
    }
  }

  private static void assertSource(
      JSONObject source, boolean requested, boolean included, String status, String namespace) {
    assertEquals(requested, source.getBoolean("requested"), source.toString());
    assertEquals(included, source.getBoolean("included"), source.toString());
    assertEquals(status, source.getString("status"), source.toString());
    assertEquals(namespace, source.getString("namespace"), source.toString());
  }

  private static void assertNoCanaries(Map<String, byte[]> entries, String... canaries) {
    for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
      String utf8 = new String(entry.getValue(), StandardCharsets.UTF_8);
      String latin1 = new String(entry.getValue(), StandardCharsets.ISO_8859_1);
      for (String canary : canaries) {
        assertFalse(utf8.contains(canary), entry.getKey() + " leaked " + canary);
        assertFalse(latin1.contains(canary), entry.getKey() + " leaked " + canary);
      }
    }
  }

}
