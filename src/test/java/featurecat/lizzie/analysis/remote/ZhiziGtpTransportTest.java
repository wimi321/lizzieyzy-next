package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ZhiziGtpTransportTest {
  @Test
  void modelIdentifierBelongsToTheConnectionNotLaterEditedSettings() throws Exception {
    try (ZhiziGtpTransport b11 = new ZhiziGtpTransport(null, "", "--kata-weight 11b768t");
        ZhiziGtpTransport b10 = new ZhiziGtpTransport(null, "", "--kata-weight 10b512t")) {
      assertEquals("11b768t", b11.modelIdentifier());
      assertEquals("10b512t", b10.modelIdentifier());
      assertEquals("11b768t", b11.modelIdentifier());
    }
  }

  @Test
  void abortEscalatesWhileGracefulZhiziDisposalIsBlocked() throws Exception {
    BlockingTerminationTransport transport = new BlockingTerminationTransport();
    AtomicReference<Throwable> gracefulFailure = new AtomicReference<>();
    AtomicReference<Throwable> abortFailure = new AtomicReference<>();
    Thread graceful =
        new Thread(
            () -> {
              try {
                transport.close();
              } catch (Throwable failure) {
                gracefulFailure.set(failure);
              }
            },
            "blocked-zhizi-graceful-close");
    Thread abort =
        new Thread(
            () -> {
              try {
                transport.abort();
                transport.abort();
              } catch (Throwable failure) {
                abortFailure.set(failure);
              }
            },
            "zhizi-force-abort");
    try {
      graceful.start();
      assertTrue(transport.gracefulDisposeEntered.await(2, TimeUnit.SECONDS));
      abort.start();
      abort.join(1_000L);

      assertFalse(abort.isAlive(), "abort must not wait for graceful application shutdown");
      assertNull(abortFailure.get());
      assertEquals(1, transport.abortDisposeCount.get());
      assertFalse(transport.abortRequestedApplicationQuit);
    } finally {
      transport.allowGracefulDispose.countDown();
      abort.join(2_000L);
      graceful.join(2_000L);
      assertFalse(graceful.isAlive());
      assertNull(gracefulFailure.get());
    }
    assertEquals(1, transport.gracefulDisposeCount.get());
    assertEquals(1, transport.abortDisposeCount.get());
  }

  @Test
  void startupFailurePolicyRetriesCapacityAndNetworkErrors() {
    assertEquals(false, ZhiziGtpTransport.isFatalStartupFailure("no worker available"));
    assertEquals(false, ZhiziGtpTransport.isFatalStartupFailure("vip-share no worker available"));
    assertEquals(false, ZhiziGtpTransport.isFatalStartupFailure("connection timed out"));
    assertEquals(false, ZhiziGtpTransport.isFatalStartupFailure("temporary network failure"));
  }

  @Test
  void startupFailurePolicyStopsForAccountAndBillingErrors() {
    assertEquals(true, ZhiziGtpTransport.isFatalStartupFailure("401 unauthorized"));
    assertEquals(true, ZhiziGtpTransport.isFatalStartupFailure("VIP 套餐未开通"));
    assertEquals(true, ZhiziGtpTransport.isFatalStartupFailure("账号余额不足"));
    assertEquals(true, ZhiziGtpTransport.isFatalStartupFailure("token expired"));
  }

  @Test
  void startupRetryUsesBoundedBackoff() {
    assertEquals(1500L, ZhiziGtpTransport.startupRetryDelayMillis(1));
    assertEquals(4000L, ZhiziGtpTransport.startupRetryDelayMillis(2));
    assertEquals(10_000L, ZhiziGtpTransport.startupRetryDelayMillis(3));
  }

  @Test
  void smokeDisconnectDelayIsExplicitBoundedAndSafeByDefault() {
    assertEquals(0L, ZhiziGtpTransport.smokeDisconnectDelayMillis(null));
    assertEquals(0L, ZhiziGtpTransport.smokeDisconnectDelayMillis(""));
    assertEquals(0L, ZhiziGtpTransport.smokeDisconnectDelayMillis("not-a-number"));
    assertEquals(0L, ZhiziGtpTransport.smokeDisconnectDelayMillis("-1"));
    assertEquals(12_500L, ZhiziGtpTransport.smokeDisconnectDelayMillis(" 12500 "));
    assertEquals(60_000L, ZhiziGtpTransport.smokeDisconnectDelayMillis("999999"));
  }

  @Test
  void sessionLifecycleRequiresTokenConnectAndRealReadyInOrder() {
    ZhiziGtpTransport.SessionLifecycle lifecycle = new ZhiziGtpTransport.SessionLifecycle();
    long generation = lifecycle.beginAttempt();

    assertFalse(lifecycle.ready(generation));
    assertTrue(lifecycle.tokenFetched(generation));
    assertTrue(lifecycle.connected(generation));
    assertTrue(lifecycle.ready(generation));
    assertFalse(lifecycle.isActive());
    assertTrue(lifecycle.activate(generation));
    assertTrue(lifecycle.isActive());
  }

  @Test
  void sessionLifecycleRejectsEventsFromRetiredSocketGeneration() {
    ZhiziGtpTransport.SessionLifecycle lifecycle = new ZhiziGtpTransport.SessionLifecycle();
    long retired = lifecycle.beginAttempt();
    assertTrue(lifecycle.tokenFetched(retired));
    lifecycle.retireAttempt();
    long current = lifecycle.beginAttempt();

    assertFalse(lifecycle.connected(retired));
    assertFalse(lifecycle.ready(retired));
    assertFalse(lifecycle.acceptsEngineOutput(retired));
    assertTrue(lifecycle.tokenFetched(current));
  }

  @Test
  void sessionFailureBeforeActivationRetriesButActiveFailureRequiresFullRecovery() {
    ZhiziGtpTransport.SessionLifecycle lifecycle = new ZhiziGtpTransport.SessionLifecycle();
    long startup = lifecycle.beginAttempt();
    assertTrue(lifecycle.tokenFetched(startup));
    assertEquals(
        ZhiziGtpTransport.SessionFailureAction.STARTUP_FAILED, lifecycle.sessionFailed(startup));

    long active = lifecycle.beginAttempt();
    assertTrue(lifecycle.tokenFetched(active));
    assertTrue(lifecycle.connected(active));
    assertTrue(lifecycle.ready(active));
    assertTrue(lifecycle.activate(active));
    assertEquals(
        ZhiziGtpTransport.SessionFailureAction.RECOVERY_REQUIRED, lifecycle.sessionFailed(active));
    assertEquals(ZhiziGtpTransport.SessionState.RECOVERY_REQUIRED, lifecycle.state());
  }

  @Test
  void decodePayloadSupportsStringBytesAndByteBuffer() {
    assertEquals("= name\n", ZhiziGtpTransport.decodePayload("= name\n"));
    assertEquals(
        "info move Q16\n",
        ZhiziGtpTransport.decodePayload("info move Q16\n".getBytes(StandardCharsets.UTF_8)));
    assertEquals(
        "? error\n",
        ZhiziGtpTransport.decodePayload(
            ByteBuffer.wrap("? error\n".getBytes(StandardCharsets.UTF_8))));
  }

  @Test
  void blockingOutputStreamSurvivesShortLivedWriterThread() throws Exception {
    ZhiziGtpTransport.BlockingByteInputStream stream =
        new ZhiziGtpTransport.BlockingByteInputStream();
    Thread writer =
        new Thread(() -> stream.append("= list_commands\n".getBytes(StandardCharsets.UTF_8)));
    writer.start();
    writer.join();

    assertEquals(
        "= list_commands\n",
        new String(stream.readNBytes("= list_commands\n".length()), StandardCharsets.UTF_8));
    assertEquals(0, stream.available());
  }

  @Test
  void blockingOutputStreamReturnsEofAfterClose() throws Exception {
    InputStream stream = new ZhiziGtpTransport.BlockingByteInputStream();

    stream.close();

    assertEquals(-1, stream.read());
  }

  @Test
  void commandStreamRejectsDisconnectedWritesInsteadOfReplayingThemBlindly() throws Exception {
    ZhiziGtpTransport.SocketCommandOutputStream stream =
        new ZhiziGtpTransport.SocketCommandOutputStream(new FakeEmitter(false));

    assertThrows(java.io.IOException.class, () -> send(stream, "boardsize 19"));
  }

  @Test
  void commandStreamSendsImmediatelyWhenConnected() throws Exception {
    FakeEmitter connected = new FakeEmitter(true);
    ZhiziGtpTransport.SocketCommandOutputStream stream =
        new ZhiziGtpTransport.SocketCommandOutputStream(connected);

    send(stream, "name");

    assertEquals(List.of("name\n"), connected.commands);
  }

  @Test
  void commandStreamInvalidatesBufferedAndFutureCommandsDuringRecovery() throws Exception {
    FakeEmitter connected = new FakeEmitter(true);
    ZhiziGtpTransport.SocketCommandOutputStream stream =
        new ZhiziGtpTransport.SocketCommandOutputStream(connected);
    stream.write("boardsize 19\n".getBytes(StandardCharsets.UTF_8));
    stream.invalidateForRecovery();
    stream.write("name\n".getBytes(StandardCharsets.UTF_8));

    assertThrows(java.io.IOException.class, stream::flush);
    assertEquals(List.of(), connected.commands);
  }

  @Test
  void commandStreamRejectsWritesAfterShutdown() throws Exception {
    ZhiziGtpTransport.SocketCommandOutputStream stream =
        new ZhiziGtpTransport.SocketCommandOutputStream(new FakeEmitter(false));

    stream.closeForShutdown();
    stream.write("name\n".getBytes(StandardCharsets.UTF_8));

    assertThrows(java.io.IOException.class, stream::flush);
  }

  @Test
  void analysisWatchdogDetectsConnectedSocketThatReturnsNoInfo() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    CountDownLatch timedOut = new CountDownLatch(1);
    try {
      ZhiziGtpTransport.AnalysisResponseWatchdog watchdog =
          new ZhiziGtpTransport.AnalysisResponseWatchdog(scheduler, 30L, timedOut::countDown);
      ZhiziGtpTransport.SocketCommandOutputStream stream =
          new ZhiziGtpTransport.SocketCommandOutputStream(
              new FakeEmitter(true), watchdog::onCommandSubmittedOrEmitted);

      send(stream, "kata-analyze B 10");

      assertEquals(true, timedOut.await(2, TimeUnit.SECONDS));
      assertEquals(true, watchdog.isUnresponsive());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void analysisWatchdogRecognizesNumberedGtpAnalysisCommand() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    CountDownLatch timedOut = new CountDownLatch(1);
    try {
      ZhiziGtpTransport.AnalysisResponseWatchdog watchdog =
          new ZhiziGtpTransport.AnalysisResponseWatchdog(scheduler, 30L, timedOut::countDown);
      ZhiziGtpTransport.SocketCommandOutputStream stream =
          new ZhiziGtpTransport.SocketCommandOutputStream(
              new FakeEmitter(true), watchdog::onCommandSubmittedOrEmitted);

      send(stream, "42 kata-analyze B 10");

      assertEquals(true, timedOut.await(2, TimeUnit.SECONDS));
      assertEquals(true, watchdog.isUnresponsive());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void analysisWatchdogRenewsDeadlineOnlyWhenAnalysisMakesProgress() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    CountDownLatch timedOut = new CountDownLatch(1);
    try {
      ZhiziGtpTransport.AnalysisResponseWatchdog watchdog =
          new ZhiziGtpTransport.AnalysisResponseWatchdog(scheduler, 120L, timedOut::countDown);
      ZhiziGtpTransport.SocketCommandOutputStream stream =
          new ZhiziGtpTransport.SocketCommandOutputStream(
              new FakeEmitter(true), watchdog::onCommandSubmittedOrEmitted);

      send(stream, "kata-analyze B 10");
      TimeUnit.MILLISECONDS.sleep(70L);
      watchdog.onAnalysisProgressAccepted(100L);
      TimeUnit.MILLISECONDS.sleep(70L);
      assertFalse(watchdog.isUnresponsive());
      assertEquals(1L, timedOut.getCount());

      watchdog.onAnalysisProgressAccepted(100L);
      assertEquals(true, timedOut.await(2, TimeUnit.SECONDS));
      assertEquals(true, watchdog.isUnresponsive());

      watchdog.onAnalysisProgressAccepted(101L);
      TimeUnit.MILLISECONDS.sleep(50L);
      assertEquals(true, watchdog.isUnresponsive());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void analysisWatchdogIsCancelledByExplicitStop() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    CountDownLatch timedOut = new CountDownLatch(1);
    try {
      ZhiziGtpTransport.AnalysisResponseWatchdog watchdog =
          new ZhiziGtpTransport.AnalysisResponseWatchdog(scheduler, 80L, timedOut::countDown);
      ZhiziGtpTransport.SocketCommandOutputStream stream =
          new ZhiziGtpTransport.SocketCommandOutputStream(
              new FakeEmitter(true), watchdog::onCommandSubmittedOrEmitted);

      send(stream, "kata-analyze B 10");
      send(stream, "stop");
      TimeUnit.MILLISECONDS.sleep(120L);
      assertFalse(watchdog.isUnresponsive());
      assertEquals(1L, timedOut.getCount());
    } finally {
      scheduler.shutdownNow();
    }
  }

  private static void send(OutputStream stream, String command) throws Exception {
    stream.write((command + "\n").getBytes(StandardCharsets.UTF_8));
    stream.flush();
  }

  private static final class FakeEmitter implements ZhiziGtpTransport.CommandEmitter {
    private final List<String> commands = new ArrayList<>();
    private final boolean connected;

    private FakeEmitter(boolean connected) {
      this.connected = connected;
    }

    @Override
    public boolean isConnected() {
      return connected;
    }

    @Override
    public void emit(String command) {
      commands.add(command);
    }
  }

  private static final class BlockingTerminationTransport extends ZhiziGtpTransport {
    private final AtomicInteger gracefulDisposeCount = new AtomicInteger();
    private final AtomicInteger abortDisposeCount = new AtomicInteger();
    private final CountDownLatch gracefulDisposeEntered = new CountDownLatch(1);
    private final CountDownLatch allowGracefulDispose = new CountDownLatch(1);
    private volatile boolean abortRequestedApplicationQuit = true;

    private BlockingTerminationTransport() throws java.io.IOException {
      super(new ZhiziApiClient(), "controlled-token", "");
    }

    @Override
    void disposeSocketSession(boolean sendQuit) {
      if (!sendQuit) {
        abortRequestedApplicationQuit = false;
        abortDisposeCount.incrementAndGet();
        return;
      }
      gracefulDisposeCount.incrementAndGet();
      gracefulDisposeEntered.countDown();
      try {
        if (!allowGracefulDispose.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out waiting to release Zhizi graceful disposal");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
  }
}
