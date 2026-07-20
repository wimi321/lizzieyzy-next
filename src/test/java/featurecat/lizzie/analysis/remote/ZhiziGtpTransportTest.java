package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.junit.jupiter.api.Test;

class ZhiziGtpTransportTest {
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
  void connectionHealthAllowsInitialConnectAndShortReconnectGrace() {
    assertEquals(
        true,
        ZhiziGtpTransport.connectionIsUsable(
            false, false, true, false, 0L, 10_000L, 45_000L));
    assertEquals(
        true,
        ZhiziGtpTransport.connectionIsUsable(
            false, false, true, true, 10_000L, 40_000L, 45_000L));
  }

  @Test
  void connectionHealthExpiresEvenWhenSocketIoStillReportsActive() {
    assertEquals(
        false,
        ZhiziGtpTransport.connectionIsUsable(
            false, false, true, true, 10_000L, 60_001L, 45_000L));
    assertEquals(
        true,
        ZhiziGtpTransport.connectionIsUsable(
            false, true, false, true, 10_000L, 60_001L, 45_000L));
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
  void commandStreamQueuesCommandsUntilReconnectIsReady() throws Exception {
    FakeEmitter disconnected = new FakeEmitter(false);
    ZhiziGtpTransport.SocketCommandOutputStream stream =
        new ZhiziGtpTransport.SocketCommandOutputStream(disconnected);

    send(stream, "boardsize 19");
    send(stream, "kata-analyze B 10");

    assertEquals(2, stream.queuedCommandCount());
    assertEquals(List.of(), disconnected.commands);

    FakeEmitter reconnected = new FakeEmitter(true);
    stream.bind(reconnected);

    assertEquals(2, stream.flushQueuedCommands());
    assertEquals(List.of("boardsize 19\n", "kata-analyze B 10\n"), reconnected.commands);
    assertEquals(0, stream.queuedCommandCount());
  }

  @Test
  void commandStreamSendsImmediatelyWhenConnected() throws Exception {
    FakeEmitter connected = new FakeEmitter(true);
    ZhiziGtpTransport.SocketCommandOutputStream stream =
        new ZhiziGtpTransport.SocketCommandOutputStream(connected);

    send(stream, "name");

    assertEquals(List.of("name\n"), connected.commands);
    assertEquals(0, stream.queuedCommandCount());
  }

  @Test
  void commandStreamRestartsLastAnalysisAfterReconnectWhenNoNewAnalysisWasQueued()
      throws Exception {
    FakeEmitter connected = new FakeEmitter(true);
    ZhiziGtpTransport.SocketCommandOutputStream stream =
        new ZhiziGtpTransport.SocketCommandOutputStream(connected);
    send(stream, "kata-analyze B 10");

    FakeEmitter reconnected = new FakeEmitter(true);
    stream.bind(reconnected);

    assertEquals(true, stream.resumeLastAnalysisIfIdle());
    assertEquals(List.of("kata-analyze B 10\n"), reconnected.commands);
  }

  @Test
  void commandStreamRestartsRawNnAfterReconnectWhenNoNewAnalysisWasQueued() throws Exception {
    FakeEmitter connected = new FakeEmitter(true);
    ZhiziGtpTransport.SocketCommandOutputStream stream =
        new ZhiziGtpTransport.SocketCommandOutputStream(connected);
    send(stream, "kata-raw-nn 0");

    FakeEmitter reconnected = new FakeEmitter(true);
    stream.bind(reconnected);

    assertEquals(true, stream.resumeLastAnalysisIfIdle());
    assertEquals(List.of("kata-raw-nn 0\n"), reconnected.commands);
  }

  @Test
  void commandStreamQueuedRawNnSuppressesStaleAnalysisResume() throws Exception {
    FakeEmitter connected = new FakeEmitter(true);
    ZhiziGtpTransport.SocketCommandOutputStream stream =
        new ZhiziGtpTransport.SocketCommandOutputStream(connected);
    send(stream, "kata-analyze B 10");

    FakeEmitter disconnected = new FakeEmitter(false);
    stream.bind(disconnected);
    send(stream, "kata-raw-nn 0");

    FakeEmitter reconnected = new FakeEmitter(true);
    stream.bind(reconnected);

    assertEquals(1, stream.flushQueuedCommands());
    assertEquals(false, stream.resumeLastAnalysisIfIdle());
    assertEquals(List.of("kata-raw-nn 0\n"), reconnected.commands);
  }

  @Test
  void commandStreamDoesNotRestartAnalysisAfterStop() throws Exception {
    FakeEmitter connected = new FakeEmitter(true);
    ZhiziGtpTransport.SocketCommandOutputStream stream =
        new ZhiziGtpTransport.SocketCommandOutputStream(connected);
    send(stream, "kata-analyze B 10");
    send(stream, "stop");

    FakeEmitter reconnected = new FakeEmitter(true);
    stream.bind(reconnected);

    assertEquals(false, stream.resumeLastAnalysisIfIdle());
    assertEquals(List.of(), reconnected.commands);
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
  void analysisWatchdogDetectsAnalysisRequestThatRemainsQueued() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    CountDownLatch timedOut = new CountDownLatch(1);
    try {
      ZhiziGtpTransport.AnalysisResponseWatchdog watchdog =
          new ZhiziGtpTransport.AnalysisResponseWatchdog(scheduler, 30L, timedOut::countDown);
      ZhiziGtpTransport.SocketCommandOutputStream stream =
          new ZhiziGtpTransport.SocketCommandOutputStream(
              new FakeEmitter(false), watchdog::onCommandSubmittedOrEmitted);

      send(stream, "kata-analyze B 10");

      assertEquals(1, stream.queuedCommandCount());
      assertEquals(true, timedOut.await(2, TimeUnit.SECONDS));
      assertEquals(true, watchdog.isUnresponsive());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void analysisWatchdogIsCancelledByInfoOrExplicitStop() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    CountDownLatch timedOut = new CountDownLatch(1);
    try {
      ZhiziGtpTransport.AnalysisResponseWatchdog watchdog =
          new ZhiziGtpTransport.AnalysisResponseWatchdog(scheduler, 80L, timedOut::countDown);
      ZhiziGtpTransport.SocketCommandOutputStream stream =
          new ZhiziGtpTransport.SocketCommandOutputStream(
              new FakeEmitter(true), watchdog::onCommandSubmittedOrEmitted);

      send(stream, "kata-analyze B 10");
      watchdog.onAnalysisResponseAccepted();
      TimeUnit.MILLISECONDS.sleep(120L);
      assertFalse(watchdog.isUnresponsive());
      assertEquals(1L, timedOut.getCount());

      send(stream, "kata-analyze W 10");
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
}
