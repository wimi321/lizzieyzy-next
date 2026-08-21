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
import org.slf4j.LoggerFactory;

class CrashPersistenceBarrierTest {
  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    CrashHandlers.resetForTests();
    LoggingRuntime.resetForTests();
  }

  @Test
  void fatalBarrierWaitsThroughDequeueHandoff() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    CrashHandlers.install();
    CountDownLatch entered = new CountDownLatch(2);
    CountDownLatch hold = new CountDownLatch(1);
    runtime.pauseHandoffForTests(LogStream.APP, entered, hold);
    runtime.pauseHandoffForTests(LogStream.CRASH, entered, hold);

    AtomicBoolean returned = new AtomicBoolean();
    Thread waiter =
        new Thread(
            () -> {
              CrashHandlers.recordFatal(new IllegalStateException("handoff-canary"));
              returned.set(true);
            },
            "handoff-waiter");
    waiter.start();

    assertTrue(entered.await(3, TimeUnit.SECONDS));
    Thread.sleep(150);
    assertFalse(returned.get(), "barrier returned during dequeue handoff");
    hold.countDown();
    waiter.join(4_000);
    assertTrue(returned.get(), "barrier did not complete after handoff release");
    runtime.awaitIdle();
    String app = Files.readString(tempDir.resolve("logs/app.log"));
    String crash = Files.readString(tempDir.resolve("logs/crash.log"));
    assertTrue(app.contains("handoff-canary"), app);
    assertTrue(crash.contains("handoff-canary"), crash);
  }

  @Test
  void fatalBarrierIgnoresEarlierUnpublishedCompletion() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    CrashHandlers.install();
    runtime.awaitIdle();

    CountDownLatch publishEntered = new CountDownLatch(1);
    CountDownLatch publishHold = new CountDownLatch(1);
    runtime.pausePublishForTests(LogStream.APP, publishEntered, publishHold);

    Thread ordinary =
        new Thread(
            () -> LoggerFactory.getLogger(LogCategories.APP).warn("ordinary-U-canary"),
            "ordinary-publisher");
    ordinary.start();
    assertTrue(publishEntered.await(3, TimeUnit.SECONDS));

    Path appFile = tempDir.resolve("logs/app.log");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      if (Files.isRegularFile(appFile) && Files.readString(appFile).contains("ordinary-U-canary")) {
        break;
      }
      Thread.sleep(20);
    }
    assertTrue(Files.readString(appFile).contains("ordinary-U-canary"));

    CountDownLatch gate = new CountDownLatch(1);
    runtime.blockPersistenceForTests(LogStream.APP, gate);
    runtime.blockPersistenceForTests(LogStream.CRASH, gate);

    AtomicBoolean returned = new AtomicBoolean();
    Thread waiter =
        new Thread(
            () -> {
              CrashHandlers.recordFatal(new IllegalStateException("publish-race-canary"));
              returned.set(true);
            },
            "publish-race-waiter");
    waiter.start();
    Thread.sleep(400);
    assertFalse(returned.get(), "barrier returned before fatal event completed");
    String crashBefore =
        Files.isRegularFile(tempDir.resolve("logs/crash.log"))
            ? Files.readString(tempDir.resolve("logs/crash.log"))
            : "";
    assertFalse(Files.readString(appFile).contains("publish-race-canary"));
    assertFalse(crashBefore.contains("publish-race-canary"));

    publishHold.countDown();
    gate.countDown();
    waiter.join(4_000);
    ordinary.join(4_000);
    assertTrue(returned.get(), "barrier did not complete after fatal persistence");
    runtime.awaitIdle();
    String app = Files.readString(appFile);
    String crash = Files.readString(tempDir.resolve("logs/crash.log"));
    assertTrue(app.contains("publish-race-canary"), app);
    assertTrue(crash.contains("publish-race-canary"), crash);
  }
}
