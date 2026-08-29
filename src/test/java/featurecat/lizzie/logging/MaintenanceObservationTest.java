package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class MaintenanceObservationTest {
  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    LoggingRuntime.resetForTests();
  }

  @Test
  void withoutRuntimeDoesNotThrowOrEmit() {
    LoggingRuntime.resetForTests();
    Logger diagnostics = (Logger) LoggerFactory.getLogger(LogCategories.DIAGNOSTICS);
    ListAppender<ILoggingEvent> events = attach(diagnostics);

    MaintenanceObservation.record(
        MaintenanceObservation.OPERATION_WEIGHT_DOWNLOAD,
        MaintenanceObservation.STAGE_VERIFY,
        MaintenanceObservation.OUTCOME_FAILED,
        12L,
        "token=should-not-appear");

    assertTrue(events.list.isEmpty(), events.list.toString());
  }

  @Test
  void successPathEmitsExplicitOutcomeWithoutReason() {
    initializeRuntime();
    ListAppender<ILoggingEvent> events = attachDiagnostics();

    MaintenanceObservation.record(
        MaintenanceObservation.OPERATION_WEIGHT_DOWNLOAD,
        MaintenanceObservation.STAGE_EXISTING_FILE,
        MaintenanceObservation.OUTCOME_SUCCESS,
        7L,
        "token=should-not-be-logged");
    MaintenanceObservation.record(
        MaintenanceObservation.OPERATION_WEIGHT_DOWNLOAD,
        MaintenanceObservation.STAGE_VERIFY,
        MaintenanceObservation.OUTCOME_SUCCESS,
        3L,
        null);

    String logs = formatted(events);
    assertTrue(logs.contains("maintenance operation=weight-download"), logs);
    assertTrue(logs.contains("stage=existing-file outcome=success durationMs=7"), logs);
    assertTrue(logs.contains("stage=verify outcome=success durationMs=3"), logs);
    assertFalse(logs.contains("reason="), logs);
    assertFalse(logs.contains("token="), logs);
  }

  @Test
  void runStageStopsAtFailedStageAndRethrowsOriginalException() {
    initializeRuntime();
    ListAppender<ILoggingEvent> events = attachDiagnostics();
    IOException boom =
        new IOException("HTTP 503 from https://alice:s3cret@cdn.example.com/weights.bin");

    IOException thrown =
        assertThrows(
            IOException.class,
            () ->
                MaintenanceObservation.runStage(
                    MaintenanceObservation.OPERATION_WEIGHT_DOWNLOAD,
                    MaintenanceObservation.STAGE_HTTP_DOWNLOAD,
                    () -> {
                      throw boom;
                    }));

    assertSame(boom, thrown);
    String logs = formatted(events);
    assertTrue(logs.contains("operation=weight-download"), logs);
    assertTrue(logs.contains("stage=http-download"), logs);
    assertTrue(logs.contains("outcome=failed"), logs);
    assertTrue(logs.contains("reason=http-503"), logs);
    assertFalse(logs.contains("alice:s3cret"), logs);
    assertFalse(logs.contains("cdn.example.com"), logs);
  }

  @Test
  void failureReasonIsBoundedAndSanitized() {
    initializeRuntime();
    ListAppender<ILoggingEvent> events = attachDiagnostics();
    String unixPath = "/tmp/lizzie-diag-canary/weights/model.bin.gz";
    String windowsPath = "C:\\Users\\Jake\\AppData\\Local\\Lizzie\\model.bin.gz";
    String credentialedUrl = "https://alice:s3cret@cdn.example.com/private/weights.bin";
    String reason =
        "token=super-secret-token password=hunter2 "
            + credentialedUrl
            + " "
            + unixPath
            + " "
            + windowsPath
            + " "
            + "x".repeat(400);

    MaintenanceObservation.record(
        MaintenanceObservation.OPERATION_TENSORRT_SETUP,
        MaintenanceObservation.STAGE_INSTALL,
        MaintenanceObservation.OUTCOME_FAILED,
        44L,
        reason);

    assertEquals(1, events.list.size(), events.list.toString());
    String message = events.list.get(0).getFormattedMessage();
    assertTrue(message.contains("operation=tensorrt-setup"), message);
    assertTrue(message.contains("stage=install"), message);
    assertTrue(message.contains("outcome=failed"), message);
    assertTrue(message.contains("durationMs=44"), message);
    assertTrue(message.contains("reason="), message);
    String payload = message.substring(message.indexOf("reason=") + "reason=".length());
    assertTrue(
        payload.getBytes(StandardCharsets.UTF_8).length
            <= MaintenanceObservation.REASON_MAX_UTF8_BYTES,
        Integer.toString(payload.getBytes(StandardCharsets.UTF_8).length));
    assertTrue(payload.endsWith(" [truncated]") || payload.length() < reason.length(), payload);
    assertFalse(message.contains("super-secret-token"), message);
    assertFalse(message.contains("hunter2"), message);
    assertFalse(message.contains("alice:s3cret"), message);
    assertFalse(message.contains(unixPath), message);
    assertFalse(message.contains(windowsPath), message);
    assertFalse(message.contains("C:\\Users\\Jake"), message);
    assertTrue(message.contains("<redacted>"), message);
    assertTrue(message.contains("<redacted-url>") || message.contains("<redacted-path>"), message);
  }

  @Test
  void spacedAbsolutePathsAreFullyRedactedFromFailureReason() {
    initializeRuntime();
    ListAppender<ILoggingEvent> events = attachDiagnostics();
    String windowsSpaced =
        "C:\\Users\\Jake Smith\\AppData\\Local\\Lizzie\\engine\\nvidia-runtime\\downloads\\katago-trt.zip";
    String unixSpaced = "/home/Jake Smith/.local/share/Lizzie/weights/model.bin.gz";
    String reason =
        "TensorRT install failed: "
            + windowsSpaced
            + " (sharing violation) also "
            + unixSpaced;

    MaintenanceObservation.record(
        MaintenanceObservation.OPERATION_TENSORRT_SETUP,
        MaintenanceObservation.STAGE_INSTALL,
        MaintenanceObservation.OUTCOME_FAILED,
        18L,
        reason);

    assertEquals(1, events.list.size(), events.list.toString());
    String message = events.list.get(0).getFormattedMessage();
    assertTrue(message.contains("reason="), message);
    assertTrue(message.contains("<redacted-path>"), message);
    assertTrue(message.contains("(sharing violation)"), message);
    assertFalse(message.contains(windowsSpaced), message);
    assertFalse(message.contains(unixSpaced), message);
    assertFalse(message.contains("Jake Smith"), message);
    assertFalse(message.contains("Smith\\AppData"), message);
    assertFalse(message.contains("Smith/.local"), message);
    assertFalse(message.contains("AppData\\Local\\Lizzie"), message);
  }

  @Test
  void unknownTokensAreCoercedToUnknown() {
    initializeRuntime();
    ListAppender<ILoggingEvent> events = attachDiagnostics();

    MaintenanceObservation.record("not-an-operation", "not-a-stage", "maybe", 1L, null);

    String logs = formatted(events);
    assertTrue(logs.contains("operation=unknown"), logs);
    assertTrue(logs.contains("stage=unknown"), logs);
    assertTrue(logs.contains("outcome=unknown"), logs);
  }

  private void initializeRuntime() {
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
  }

  private static ListAppender<ILoggingEvent> attachDiagnostics() {
    return attach((Logger) LoggerFactory.getLogger(LogCategories.DIAGNOSTICS));
  }

  private static String formatted(ListAppender<ILoggingEvent> events) {
    StringBuilder text = new StringBuilder();
    for (ILoggingEvent event : events.list) {
      text.append(event.getFormattedMessage()).append('\n');
    }
    return text.toString();
  }

  private static ListAppender<ILoggingEvent> attach(Logger logger) {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }
}
