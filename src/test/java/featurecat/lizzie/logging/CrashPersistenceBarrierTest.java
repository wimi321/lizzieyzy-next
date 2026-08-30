package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

class CrashPersistenceBarrierTest {
  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    CrashHandlers.resetForTests();
    LoggingRuntime.resetForTests();
  }

  @ParameterizedTest
  @EnumSource(value = LogStream.class, names = {"APP", "CRASH"})
  void fatalBarrierWaitsThroughDequeueHandoff(LogStream pausedStream) throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    CrashHandlers.install();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch hold = new CountDownLatch(1);
    runtime.pauseHandoffForTests(pausedStream, entered, hold);

    AtomicBoolean returned = new AtomicBoolean();
    Thread waiter =
        new Thread(
            () -> {
              CrashHandlers.recordFatal(new IllegalStateException("handoff-canary"));
              returned.set(true);
            },
            "handoff-waiter");
    waiter.start();

    try {
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      Thread.sleep(150);
      assertFalse(returned.get(), "barrier returned during dequeue handoff");
      hold.countDown();
      waiter.join(4_000);
    } finally {
      hold.countDown();
      waiter.join(4_000);
    }
    assertTrue(returned.get(), "barrier did not complete after handoff release");
    runtime.awaitIdle();
    String app = Files.readString(tempDir.resolve("logs/app.log"));
    String crash = Files.readString(tempDir.resolve("logs/crash.log"));
    assertTrue(app.contains("handoff-canary"), app);
    assertTrue(crash.contains("handoff-canary"), crash);
  }

  @Test
  void fatalBarrierIncludesAnAcceptedEventBeforeItsProducerReturns() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    CrashHandlers.install();
    runtime.awaitIdle();

    CountDownLatch gate = new CountDownLatch(1);
    runtime.blockPersistenceForTests(LogStream.APP, gate);
    CountDownLatch accepted = new CountDownLatch(1);
    CountDownLatch producerHold = new CountDownLatch(1);
    runtime.pauseAfterAcceptForTests(LogStream.APP, accepted, producerHold);

    Thread ordinary =
        new Thread(
            () -> LoggerFactory.getLogger(LogCategories.APP).warn("accepted-before-return-canary"),
            "accepted-producer");
    AtomicBoolean returned = new AtomicBoolean();
    Thread waiter =
        new Thread(
            () -> {
              CrashHandlers.recordFatal(new IllegalStateException("accepted-barrier-canary"));
              returned.set(true);
            },
            "accepted-barrier-waiter");
    ordinary.start();
    try {
      assertTrue(accepted.await(3, TimeUnit.SECONDS));
      waiter.start();
      Thread.sleep(250);
      assertFalse(returned.get(), "barrier ignored an accepted event whose sink was blocked");

      gate.countDown();
      waiter.join(4_000);
      assertTrue(returned.get(), "barrier did not complete after fatal persistence");
      assertTrue(ordinary.isAlive(), "producer hold was not active after atomic acceptance");
    } finally {
      gate.countDown();
      producerHold.countDown();
      if (waiter.isAlive()) {
        waiter.join(4_000);
      }
      ordinary.join(4_000);
    }
    assertFalse(ordinary.isAlive());
    runtime.awaitIdle();
    String app = Files.readString(tempDir.resolve("logs/app.log"));
    String crash = Files.readString(tempDir.resolve("logs/crash.log"));
    assertTrue(app.contains("accepted-before-return-canary"), app);
    assertTrue(app.contains("accepted-barrier-canary"), app);
    assertTrue(crash.contains("accepted-barrier-canary"), crash);
  }
}
