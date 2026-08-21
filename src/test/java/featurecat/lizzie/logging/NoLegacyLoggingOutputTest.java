package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.util.YikeSyncDebugLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class NoLegacyLoggingOutputTest {
  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    LoggingRuntime.resetForTests();
  }

  @Test
  void unifiedExportDoesNotCreateLegacyLogFiles() throws Exception {
    LoggingRuntime.resetForTests();
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, java.util.List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    LoggerFactory.getLogger(LogCategories.APP).info("app-event");
    runtime.awaitIdle();
    YikeSyncDebugLog.log("should-not-write-a-file");
    Path zip =
        new DiagnosticBundleExporter(DiagnosticBundleExporter.defaultOutputDirectory(tempDir))
            .export(
                new DiagnosticBundleRequest(
                    runtime,
                    EnumSet.noneOf(TraceScope.class),
                    new JSONObject(),
                    null,
                    "next-dev"));
    assertTrue(Files.isRegularFile(zip));
    assertFalse(Files.exists(tempDir.resolve("LastGtpLogs.txt")));
    assertFalse(Files.exists(tempDir.resolve("LastConsoleLogs.txt")));
    assertFalse(Files.exists(tempDir.resolve("LastErrorLogs.txt")));
    assertFalse(Files.exists(tempDir.resolve("sync-diagnostics")));
    assertFalse(Files.exists(Path.of("target/yike-sync-debug.log")));
    assertFalse(Files.exists(Path.of("runtime/readboard-local-move-debug.log")));
  }
}
