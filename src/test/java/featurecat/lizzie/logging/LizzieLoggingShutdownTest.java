package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LizzieLoggingShutdownTest {
  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    LoggingRuntime.resetForTests();
  }

  @Test
  void loggingShutdownRunsOnceBeforeExit() {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    AtomicInteger exits = new AtomicInteger();
    List<String> order = new ArrayList<>();
    Lizzie.shutdownLoggingThenExit(
        code -> {
          assertEquals(0, code);
          assertTrue(runtime.isShutdown());
          order.add("exit");
          exits.incrementAndGet();
        });
    assertEquals(List.of("exit"), order);
    assertEquals(1, exits.get());
    Lizzie.shutdownLoggingThenExit(code -> exits.incrementAndGet());
    assertEquals(2, exits.get());
    assertTrue(runtime.isShutdown());
  }

  @Test
  void exitStillRunsWhenLoggingWasNeverInitialized() {
    LoggingRuntime.resetForTests();
    AtomicInteger code = new AtomicInteger(-1);
    Lizzie.shutdownLoggingThenExit(code::set);
    assertEquals(0, code.get());
  }
}
