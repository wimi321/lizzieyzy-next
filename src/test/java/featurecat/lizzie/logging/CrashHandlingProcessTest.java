package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.ConfigFatalExitProbe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashHandlingProcessTest {
  @TempDir Path tempDir;

  @Test
  void backgroundUncaughtExceptionWritesCrashAndAppThenContinues() throws Exception {
    Path work = Files.createTempDirectory(tempDir, "bg-crash");
    LoggingChildProcess.Result result =
        LoggingChildProcess.run(work, BackgroundUncaughtExceptionProbe.class);

    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("CONTINUED"), result.output());
    assertTrue(result.output().contains("BG_UNCAUGHT_CANARY"), result.output());
    String app = LoggingChildProcess.readLog(work, "app.log");
    String crash = LoggingChildProcess.readLog(work, "crash.log");
    assertTrue(app.contains("BG_UNCAUGHT_CANARY"), app);
    assertTrue(crash.contains("BG_UNCAUGHT_CANARY"), crash);
    assertTrue(app.contains("IllegalStateException"), app);
    assertTrue(crash.contains("IllegalStateException"), crash);
    assertTrue(app.contains("application log session started"), app);
    assertEquals(1, count(crash, "BG_UNCAUGHT_CANARY"));
  }

  @Test
  void swingEdtExceptionWritesCrashAndAppThenContinues() throws Exception {
    Path work = Files.createTempDirectory(tempDir, "edt-crash");
    LoggingChildProcess.Result result =
        LoggingChildProcess.run(work, SwingEdtExceptionProbe.class);

    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("CONTINUED"), result.output());
    String app = LoggingChildProcess.readLog(work, "app.log");
    String crash = LoggingChildProcess.readLog(work, "crash.log");
    assertTrue(app.contains("edt-canary"), app);
    assertTrue(crash.contains("edt-canary"), crash);
    assertEquals(1, count(crash, "edt-canary"));
  }

  @Test
  void fatalStartupExceptionWritesCrashAndAppThenTerminates() throws Exception {
    Path work = Files.createTempDirectory(tempDir, "fatal-crash");
    LoggingChildProcess.Result result =
        LoggingChildProcess.run(work, FatalStartupExceptionProbe.class);

    assertNotEquals(0, result.exitCode(), result.output());
    assertFalse(result.output().contains("CONTINUED"), result.output());
    assertTrue(result.output().contains("fatal-canary"), result.output());
    String app = LoggingChildProcess.readLog(work, "app.log");
    String crash = LoggingChildProcess.readLog(work, "crash.log");
    assertTrue(app.contains("fatal-canary"), app);
    assertTrue(crash.contains("fatal-canary"), crash);
    assertEquals(1, count(crash, "fatal-canary"));
    assertTrue(count(app, "fatal-canary") <= 2, app);
  }

  @Test
  void crashSinkFailureDoesNotRecurseOrChangeContinuation() throws Exception {
    Path work = Files.createTempDirectory(tempDir, "fail-crash");
    LoggingChildProcess.Result result =
        LoggingChildProcess.run(work, CrashLoggingFailureProbe.class);

    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("CONTINUED"), result.output());
    assertTrue(result.output().contains("fail-canary"), result.output());
    assertTrue(
        result.output().contains(LoggingRuntime.STDERR_PREFIX)
            || result.output().contains("crash-reason="),
        result.output());
    String scanned =
        LoggingChildProcess.readLog(work, "app.log")
            + LoggingChildProcess.readLog(work, "crash.log");
    assertTrue(count(scanned, "fail-canary") < 8, scanned);
  }

  @Test
  void configFailClosedExitWritesCrashAndAppThenTerminates() throws Exception {
    Path work = Files.createTempDirectory(tempDir, "config-exit");
    LoggingChildProcess.Result result =
        LoggingChildProcess.run(work, ConfigFatalExitProbe.class);

    assertEquals(1, result.exitCode(), result.output());
    String app = LoggingChildProcess.readLog(work, "app.log");
    String crash = LoggingChildProcess.readLog(work, "crash.log");
    assertTrue(app.contains("config-exit-canary"), app);
    assertTrue(crash.contains("config-exit-canary"), crash);
  }

  @Test
  void delayedSinkFatalExitPersistsCanaryInAppAndCrash() throws Exception {
    Path work = Files.createTempDirectory(tempDir, "slow-sink");
    LoggingChildProcess.Result result =
        LoggingChildProcess.run(work, DelayedCrashSinkProbe.class);

    assertEquals(1, result.exitCode(), result.output());
    String app = LoggingChildProcess.readLog(work, "app.log");
    String crash = LoggingChildProcess.readLog(work, "crash.log");
    assertEquals(1, count(app, "slow-sink-canary"), app);
    assertEquals(1, count(crash, "slow-sink-canary"), crash);
  }

  @Test
  void interruptedThreadStillPersistsDelayedCrashCanary() throws Exception {
    Path work = Files.createTempDirectory(tempDir, "interrupted-sink");
    LoggingChildProcess.Result result =
        LoggingChildProcess.run(work, InterruptedCrashSinkProbe.class);

    assertEquals(1, result.exitCode(), result.output());
    String app = LoggingChildProcess.readLog(work, "app.log");
    String crash = LoggingChildProcess.readLog(work, "crash.log");
    assertEquals(1, count(app, "interrupted-sink-canary"), app);
    assertEquals(1, count(crash, "interrupted-sink-canary"), crash);
  }

  @Test
  void blockedSinksEmitUnwrittenNoticeAndExit() throws Exception {
    Path work = Files.createTempDirectory(tempDir, "blocked-sink");
    long started = System.nanoTime();
    LoggingChildProcess.Result result =
        LoggingChildProcess.runWithTimeout(work, 8_000L, BlockedCrashSinkProbe.class);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertEquals(1, result.exitCode(), result.output());
    assertTrue(elapsedMs >= 2_500L, "elapsed=" + elapsedMs + " output=" + result.output());
    assertTrue(elapsedMs <= 6_000L, "elapsed=" + elapsedMs + " output=" + result.output());
    assertEquals(
        1,
        count(result.output(), LoggingRuntime.STDERR_PREFIX + "crash unwritten events=2"),
        result.output());
  }

  private static int count(String text, String token) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(token, index)) >= 0) {
      count++;
      index += token.length();
    }
    return count;
  }
}
