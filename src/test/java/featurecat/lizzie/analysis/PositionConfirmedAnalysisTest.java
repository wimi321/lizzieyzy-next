package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.logging.DiagnosticModule;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.LoggingSettings;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import featurecat.lizzie.rules.Board;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PositionConfirmedAnalysisTest {
  @Test
  void navigationBurstDoesNotCreateAThreadPerPendingPosition() throws Exception {
    long baseline = positionTimeoutThreads();
    try (Fixture fixture = new Fixture()) {
      for (int i = 0; i < 128; i++) fixture.engine.sendCommand("undo");
      fixture.engine.sendCommand("kata-analyze B 10");
      assertEquals(128, fixture.transport.commands().size());
      try {
        long additionalThreads = positionTimeoutThreads() - baseline;
        assertTrue(
            additionalThreads <= 1, "Position timeout threads created: " + additionalThreads);
        for (int i = 0; i < 127; i++) fixture.respond(i, "=");
        assertEquals(128, fixture.transport.commands().size(), "analysis must wait for every ACK");
      } finally {
        for (int i = 0; i < 128; i++) fixture.respond(i, "=");
      }
      assertEquals(129, fixture.transport.commands().size());
      assertEquals("kata-analyze B 10", fixture.transport.commands().get(128));
    }
  }

  private static long positionTimeoutThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(
            thread ->
                thread.getName().equals("lizzie-position-response-timeout")
                    || thread.getName().equals("lizzie-gtp-response-timeouts"))
        .count();
  }

  @Test
  void analysisDiagnosticsReportOnlyTheRequestThatReachesTheTransport(@TempDir Path work)
      throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(work, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.ENGINE)));
    try (Fixture fixture = new Fixture()) {
      fixture.engine.sendCommand("undo");
      for (int i = 0; i < 128; i++) fixture.engine.sendCommand("kata-analyze B 10");
      assertEquals(List.of("undo"), fixture.transport.commands());
      fixture.respond(0, "=");
      assertEquals(List.of("undo", "kata-analyze B 10"), fixture.transport.commands());
    } finally {
      runtime.shutdown();
    }
    long starts =
        Files.readAllLines(work.resolve("logs/app.log")).stream()
            .filter(line -> line.contains("event=analysis-started"))
            .count();
    assertEquals(1, starts, "coalesced analysis requests were never sent");
  }

  @Test
  void kataGenmoveOnlyUsesDecimalPayloadWithoutChangingLeelaAnalysisFormat() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.engine.isKatago = false;
      fixture.engine.advertiseCommandsForTest(List.of("kata-genmove_analyze", "lz-analyze"));
      assertFalse(fixture.engine.usesKataAnalysis());
      fixture.engine.sendCommand("kata-genmove_analyze B 10");
      assertEquals(61, parsedWinrate(fixture.engine, "0.61"), 0.00001);
      fixture.respond(0, "=");
      fixture.engine.sendCommand("lz-analyze B 10");
      assertEquals(61, parsedWinrate(fixture.engine, "6100"), 0.00001);
    }
  }

  private static double parsedWinrate(Leelaz engine, String value) throws Exception {
    var parser = Leelaz.class.getDeclaredMethod("parseAnalysisInfoPayload", String.class);
    parser.setAccessible(true);
    Object parsed = parser.invoke(engine, "move D4 visits 30 winrate " + value + " pv D4");
    Field moves = parsed.getClass().getDeclaredField("moves");
    moves.setAccessible(true);
    return ((MoveData) ((List<?>) moves.get(parsed)).get(0)).winrate;
  }

  @ParameterizedTest
  @ValueSource(strings = {"boardsize", ""})
  void commandDiscoveryAcceptsAnyFirstCommandOrEmptyHeader(String firstCommand) throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.engine.isKatago = false;
      fixture.engine.isCheckingName = false;
      fixture.engine.sendCommand("list_commands");
      String raw = fixture.transport.rawCommands().get(0);
      String id = raw.startsWith("list_commands") ? "" : raw.substring(0, raw.indexOf(' '));
      fixture.engine.dispatchReaderLineForTest("=" + id + " " + firstCommand);
      fixture.engine.dispatchReaderLineForTest("kata-analyze");
      fixture.engine.dispatchReaderLineForTest("protocol_version");
      assertFalse(fixture.engine.usesKataAnalysis(), "the list must complete before publication");
      fixture.engine.dispatchReaderLineForTest("");
      assertTrue(fixture.engine.usesKataAnalysis());
      assertEquals(!firstCommand.isEmpty(), fixture.engine.supportsGtpCommand("boardsize", false));
      assertFalse(fixture.engine.supportsGtpCommand("stop", false));
    }
  }

  @Test
  void commandDiscoveryOwnsPayloadWhileNumberedPositionResponseIsPending() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.engine.dispatchReaderLineForTest("= protocol_version");
      fixture.engine.sendCommand("set_position B D4");
      fixture.engine.sendCommand("kata-analyze B 10");
      assertEquals(List.of("set_position B D4"), fixture.transport.commands());

      fixture.engine.dispatchReaderLineForTest("play");
      fixture.engine.dispatchReaderLineForTest("");

      assertEquals(List.of("play"), fixture.engine.commandLists);
      assertTrue(fixture.engine.isRulesCapabilityDiscoveryComplete());

      String raw = fixture.transport.rawCommands().get(0);
      String id = raw.substring(0, raw.indexOf(' '));
      fixture.engine.dispatchReaderLineForTest("=" + id);

      assertEquals(List.of("play"), fixture.engine.commandLists);
      assertEquals(List.of("set_position B D4", "kata-analyze B 10"), fixture.transport.commands());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"undo", "play B D4", "play W pass", "set_position B D4"})
  void positionMustSucceedBeforeSuccessorAnalysisIsWritten(String mutation) throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.engine.sendCommand(mutation);
      fixture.engine.sendCommand("kata-analyze B 10");
      assertEquals(List.of(mutation), fixture.transport.commands());
      fixture.respond(0, "=");
      assertEquals(List.of(mutation, "kata-analyze B 10"), fixture.transport.commands());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"undo", "play B D4", "play W pass", "set_position B D4"})
  void queuedPositionCannotBeBypassedByAnalysis(String mutation) throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.engine.requireResponseBeforeSend = true;
      fixture.engine.sendCommand("name");
      fixture.engine.sendCommand(mutation);
      fixture.engine.sendCommand("kata-analyze B 10");
      assertEquals(List.of("name"), fixture.transport.commands());
      fixture.respond(0, "=");
      assertEquals(List.of("name", mutation), fixture.transport.commands());
      fixture.respond(1, "=");
      assertEquals(List.of("name", mutation, "kata-analyze B 10"), fixture.transport.commands());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"undo", "play B D4", "play W pass", "set_position B D4"})
  void rejectedPositionCannotResumeButFreshPositionCan(String mutation) throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.engine.sendCommand(mutation);
      fixture.engine.sendCommand("kata-analyze B 10");
      fixture.respond(0, "?");
      fixture.respond(0, "=");
      assertEquals(List.of(mutation), fixture.transport.commands());
      fixture.engine.sendCommand("set_position");
      fixture.engine.sendCommand("kata-analyze W 11");
      fixture.respond(1, "=");
      assertEquals(
          List.of(mutation, "set_position", "kata-analyze W 11"), fixture.transport.commands());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"undo", "play B D4", "play W pass", "set_position B D4"})
  void timeoutRetiresOnlyItsOwnResponseAndLetsQueueProgress(String mutation) throws Exception {
    try (Fixture fixture = new Fixture()) {
      Runnable response = () -> {};
      fixture.engine.sendCommandWithResponseForTest(mutation, response);
      fixture.engine.sendCommand("kata-analyze B 10");
      fixture.engine.retireTimedOutNormalCommandForTest(response);
      assertEquals(List.of(mutation), fixture.transport.commands());
      fixture.engine.sendCommand("set_position");
      fixture.engine.sendCommand("kata-analyze W 11");
      fixture.respond(0, "=");
      assertEquals(List.of(mutation, "set_position"), fixture.transport.commands());
      fixture.respond(1, "=");
      assertEquals(
          List.of(mutation, "set_position", "kata-analyze W 11"), fixture.transport.commands());
    }
  }

  @Test
  void queuedSetPositionTimeoutCannotOpenAnalysis() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.engine.requireResponseBeforeSend = true;
      fixture.engine.sendCommand("name");
      Runnable response = () -> {};
      fixture.engine.sendCommandWithResponseForTest("set_position", response);
      fixture.engine.sendCommand("kata-analyze B 10");
      fixture.engine.retireTimedOutNormalCommandForTest(response);
      fixture.respond(0, "=");
      assertEquals(List.of("name"), fixture.transport.commands());
      fixture.engine.sendCommand("protocol_version");
      assertEquals(List.of("name", "protocol_version"), fixture.transport.commands());
    }
  }

  @Test
  void failedPositionWriteDoesNotOpenAnalysis() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.failWrite.set(true);
      fixture.engine.sendCommandNoLeelaz2("set_position");
      fixture.engine.sendCommand("kata-analyze B 10");
      assertEquals(List.of("set_position"), fixture.transport.commands());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"kata-analyze B 10", "kata-analyze", "lz-analyze"})
  void replacingBoardWhilePositionIsPendingDiscardsCapturedAnalysis(String command)
      throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.engine.sendCommand("undo");
      fixture.engine.sendCommand(command);
      Lizzie.board = new Board();
      fixture.respond(0, "=");
      assertEquals(List.of("undo"), fixture.transport.commands());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"kata-analyze B 10", "kata-analyze", "lz-analyze"})
  void replacingPrimaryWhilePositionIsPendingDiscardsCapturedAnalysis(String command)
      throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.engine.sendCommand("undo");
      fixture.engine.sendCommand(command);
      Lizzie.leelaz = new Leelaz("");
      fixture.respond(0, "=");
      assertEquals(List.of("undo"), fixture.transport.commands());
    }
  }

  @Test
  void successfulFenceCannotHideFailedRestorePosition() throws Exception {
    try (Fixture fixture = new Fixture()) {
      AtomicBoolean success = new AtomicBoolean();
      AtomicBoolean failure = new AtomicBoolean();
      fixture.engine.sendCommand("clear_board");
      fixture.engine.sendCommand("play B D4");
      fixture.engine.confirmBoardSynchronization(
          () -> success.set(true), detail -> failure.set(true));
      fixture.respond(0, "?");
      fixture.respond(1, "=");
      fixture.respond(2, "=");
      assertFalse(success.get(), "a final name ACK cannot repair a failed clear_board");
      assertTrue(failure.get());
    }
  }

  @Test
  void analysisRequestedBeforeRestoreDispatchWaitsWithoutBlockingRequiredCommands()
      throws Exception {
    try (Fixture fixture = new Fixture()) {
      Leelaz.PositionRestore restore = fixture.engine.capturePositionRestore(null);
      fixture.engine.sendCommand("kata-analyze B 10");
      assertTrue(fixture.transport.commands().isEmpty());
      restore.execute(() -> fixture.engine.sendCommand("clear_board"));
      AtomicBoolean success = new AtomicBoolean();
      restore.confirm(() -> success.set(true), detail -> fail(detail));
      assertEquals(List.of("clear_board", "name"), fixture.transport.commands());
      fixture.respond(0, "=");
      assertFalse(success.get());
      fixture.respond(1, "=");
      assertTrue(success.get());
      assertEquals(
          List.of("clear_board", "name", "kata-analyze B 10"), fixture.transport.commands());
    }
  }

  @Test
  void finalFenceWaitsForEveryCompoundPositionAckEvenOutOfOrder() throws Exception {
    try (Fixture fixture = new Fixture()) {
      AtomicBoolean success = new AtomicBoolean();
      AtomicBoolean failure = new AtomicBoolean();
      Leelaz.PositionRestore restore = fixture.engine.capturePositionRestore(null);
      restore.execute(
          () -> {
            fixture.engine.sendCommand("clear_board");
            fixture.engine.sendCommand("play B D4");
          });
      restore.confirm(() -> success.set(true), detail -> failure.set(true));

      fixture.respond(2, "=");
      assertFalse(success.get(), "the final fence cannot bypass pending position responses");
      fixture.respond(0, "=");
      assertFalse(success.get(), "every compound position response is required");
      fixture.respond(1, "=");

      assertTrue(success.get());
      assertFalse(failure.get());
    }
  }

  @Test
  void replacementsInsideOneCompoundRestoreShareEarlierFailure() throws Exception {
    try (Fixture fixture = new Fixture()) {
      AtomicBoolean success = new AtomicBoolean();
      AtomicBoolean failure = new AtomicBoolean();
      Leelaz.PositionRestore restore = fixture.engine.capturePositionRestore(null);
      restore.execute(
          () -> {
            fixture.engine.sendCommand("clear_board");
            fixture.engine.sendCommand("boardsize 19");
            fixture.engine.sendCommand("komi 7.5");
            fixture.engine.sendCommand("loadsgf /tmp/compound-restore.sgf");
            fixture.engine.sendCommand("play W pass");
          });
      restore.confirm(() -> success.set(true), detail -> failure.set(true));

      fixture.respond(0, "?");
      for (int index = 1; index < 6; index++) {
        fixture.respond(index, "=");
      }

      assertFalse(success.get());
      assertTrue(failure.get(), "a later replacement must not reset the compound lineage");
    }
  }

  @Test
  void sendFailureFailsCapturedRestoreBeforeFinalFence() throws Exception {
    try (Fixture fixture = new Fixture()) {
      AtomicBoolean success = new AtomicBoolean();
      AtomicBoolean failure = new AtomicBoolean();
      Leelaz.PositionRestore restore = fixture.engine.capturePositionRestore(null);
      fixture.failWrite.set(true);
      restore.execute(() -> fixture.engine.sendCommand("clear_board"));
      restore.confirm(() -> success.set(true), detail -> failure.set(true));

      assertFalse(success.get());
      assertTrue(failure.get());
    }
  }

  @Test
  void timedOutRestoreLateAckCannotSettleFreshRestore() throws Exception {
    ShortTimeoutLeelaz engine = new ShortTimeoutLeelaz();
    try (Fixture fixture = new Fixture(engine, false)) {
      CountDownLatch oldFailure = new CountDownLatch(1);
      Leelaz.PositionRestore oldRestore = engine.capturePositionRestore(null);
      oldRestore.execute(() -> engine.sendCommand("clear_board"));
      oldRestore.confirm(
          () -> fail("timed-out restore succeeded"), detail -> oldFailure.countDown());
      assertTrue(oldFailure.await(1, TimeUnit.SECONDS));

      engine.isLoaded = true;
      engine.timeoutMillis.set(2_000L);
      AtomicBoolean freshSuccess = new AtomicBoolean();
      AtomicBoolean freshFailure = new AtomicBoolean();
      Leelaz.PositionRestore freshRestore = engine.capturePositionRestore(null);
      freshRestore.execute(() -> engine.sendCommand("set_position B D4"));
      freshRestore.confirm(() -> freshSuccess.set(true), detail -> freshFailure.set(true));

      fixture.respond(0, "=");
      assertFalse(freshSuccess.get(), "late ACK belongs only to the retired restore");
      fixture.respond(2, "=");
      fixture.respond(3, "=");

      assertTrue(freshSuccess.get());
      assertFalse(freshFailure.get());
    }
  }

  @Test
  void obsoleteRestoreFailureCannotUnloadSuccessorOnSameReader() throws Exception {
    try (Fixture fixture = new Fixture()) {
      AtomicBoolean oldFailure = new AtomicBoolean();
      Leelaz.PositionRestore oldRestore = fixture.engine.capturePositionRestore(null);
      oldRestore.execute(() -> fixture.engine.sendCommand("clear_board"));
      oldRestore.confirm(() -> fail("obsolete restore succeeded"), detail -> oldFailure.set(true));

      AtomicBoolean freshSuccess = new AtomicBoolean();
      Leelaz.PositionRestore freshRestore = fixture.engine.capturePositionRestore(null);
      freshRestore.execute(() -> fixture.engine.sendCommand("set_position B D4"));
      freshRestore.confirm(
          () -> freshSuccess.set(true), detail -> fail("fresh restore failed: " + detail));

      fixture.respond(0, "?");
      fixture.respond(1, "=");
      assertTrue(oldFailure.get());
      assertTrue(fixture.engine.isLoaded, "obsolete failure must not unload the successor lineage");
      fixture.respond(2, "=");
      fixture.respond(3, "=");

      assertTrue(freshSuccess.get());
    }
  }

  @Test
  void capturedMirrorFailureFailsWholeRestore() throws Exception {
    try (Fixture fixture = new Fixture(true)) {
      AtomicBoolean success = new AtomicBoolean();
      AtomicBoolean failure = new AtomicBoolean();
      Leelaz.PositionRestore restore = fixture.engine.capturePositionRestore(fixture.mirror);
      restore.execute(
          () -> {
            fixture.engine.sendCommandNoLeelaz2("clear_board");
            fixture.mirror.sendCommandNoLeelaz2("clear_board");
          });
      restore.confirm(() -> success.set(true), detail -> failure.set(true));

      fixture.respond(0, "=");
      fixture.respond(1, "=");
      fixture.respondMirror(0, "?");

      assertFalse(success.get());
      assertTrue(failure.get());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void olderRestoreTailCannotDisplaceNewerCapturedRestore(boolean withMirror) throws Exception {
    try (Fixture fixture = new Fixture(withMirror)) {
      Leelaz.PositionRestore older = fixture.engine.capturePositionRestore(fixture.mirror);
      java.util.concurrent.atomic.AtomicReference<Leelaz.PositionRestore> latest =
          new java.util.concurrent.atomic.AtomicReference<>();
      older.execute(
          () -> {
            fixture.engine.sendCommandNoLeelaz2("clear_board");
            if (withMirror) fixture.mirror.sendCommandNoLeelaz2("clear_board");
            latest.set(fixture.engine.capturePositionRestore(fixture.mirror));
            fixture.engine.sendCommandNoLeelaz2("play B D4");
            if (withMirror) fixture.mirror.sendCommandNoLeelaz2("play B D4");
          });
      try {
        latest
            .get()
            .execute(
                () -> {
                  fixture.engine.sendCommandNoLeelaz2("clear_board");
                  if (withMirror) fixture.mirror.sendCommandNoLeelaz2("clear_board");
                });
        older.cancel();
        latest
            .get()
            .confirm(
                () -> fixture.engine.sendCommandNoLeelaz2("kata-analyze B 10"),
                detail -> fail(detail));
        assertEquals(
            List.of("clear_board", "play B D4", "clear_board", "name"),
            fixture.transport.commands());
        for (int index = 0; index < 4; index++) fixture.respond(index, "=");
        if (withMirror) {
          assertEquals(4, fixture.transport.commands().size());
          for (int index = 0; index < 3; index++) fixture.respondMirror(index, "=");
          assertEquals(4, fixture.transport.commands().size());
          fixture.respondMirror(3, "=");
        }
        assertEquals(
            List.of("clear_board", "play B D4", "clear_board", "name", "kata-analyze B 10"),
            fixture.transport.commands());
      } finally {
        older.cancel();
        latest.get().cancel();
      }
    }
  }

  private static final class ShortTimeoutLeelaz extends Leelaz {
    private final AtomicLong timeoutMillis = new AtomicLong(10L);

    private ShortTimeoutLeelaz() throws Exception {
      super("");
    }

    @Override
    protected long readBoardGmaRestoreResponseTimeoutMillis() {
      return timeoutMillis.get();
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final Config previousConfig = Lizzie.config;
    private final Board previousBoard = Lizzie.board;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    private final Leelaz previousEngine = Lizzie.leelaz;
    private final Leelaz previousMirror = Lizzie.leelaz2;
    private final Leelaz engine;
    private final Leelaz mirror;
    private final AtomicBoolean failWrite = new AtomicBoolean();
    private final ExactSnapshotRestoreProtocolFixture.Transport transport;
    private final ExactSnapshotRestoreProtocolFixture.Transport mirrorTransport;

    private Fixture() throws Exception {
      this(new Leelaz(""), false);
    }

    private Fixture(boolean withMirror) throws Exception {
      this(new Leelaz(""), withMirror);
    }

    private Fixture(Leelaz engine, boolean withMirror) throws Exception {
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(GtpConsolePane.class);
      this.engine = engine;
      this.mirror = withMirror ? new Leelaz("") : null;
      Lizzie.leelaz = engine;
      Lizzie.leelaz2 = mirror;
      Lizzie.board = new Board();
      Lizzie.frame = allocate(LizzieFrame.class);
      prepareEngine(engine);
      transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command -> {
                if (failWrite.getAndSet(false))
                  throw new IOException("controlled position write failure");
                return null;
              });
      if (mirror == null) {
        mirrorTransport = null;
      } else {
        prepareEngine(mirror);
        mirrorTransport = ExactSnapshotRestoreProtocolFixture.install(mirror, command -> null);
      }
    }

    private static void prepareEngine(Leelaz engine) {
      engine.started = true;
      engine.isLoaded = true;
      engine.isKatago = true;
    }

    private void respond(int index, String prefix) {
      respond(engine, transport, index, prefix);
    }

    private void respondMirror(int index, String prefix) {
      respond(mirror, mirrorTransport, index, prefix);
    }

    private static void respond(
        Leelaz engine,
        ExactSnapshotRestoreProtocolFixture.Transport transport,
        int index,
        String prefix) {
      String raw = transport.rawCommands().get(index);
      int separator = raw.indexOf(' ');
      String id =
          separator > 0 && Character.isDigit(raw.charAt(0)) ? raw.substring(0, separator) : "";
      engine.processCommandResponseLineForTest(prefix + id);
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousGtpConsole;
      Lizzie.leelaz = previousEngine;
      Lizzie.leelaz2 = previousMirror;
    }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }
}
