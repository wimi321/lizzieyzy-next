package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.EngineStartupStatus;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.BoardRenderer;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.JFontMenu;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.WinrateGraph;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import featurecat.lizzie.rules.extraMoveForTsumego;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Focused tests for the initial engine startup restore barrier (Issue #223): frozen immutable
 * startup route, catch-up convergence on navigation, linearized barrier end, narrow live-board
 * admission and fail-closed failure semantics.
 */
class EngineManagerInitialStartupSynchronizationTest {

  @Test
  void noNavigationExecutesFrozenRouteOnceThenMarksReady() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      engine.startEngine(0);

      AtomicInteger barrierRounds = new AtomicInteger();
      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      startup.beforeRestore =
          () -> {
            barrierRounds.incrementAndGet();
            // Ordinary live-board play must be dropped while the barrier is active...
            engine.sendCommand("play B Q4");
            // ...but startup handshake commands keep flowing through the same queue.
            engine.sendCommand("name");
          };
      int readyBaseline = env.readyTransitions.get();

      runStartupInThread(startup, engine);

      assertEquals(1, barrierRounds.get(), "no navigation must not produce catch-up rounds");
      assertEquals(1, env.clearBoardCount(engine), "frozen root replay executes once");
      assertFalse(engine.commands.contains("play B Q4"), "live-board play must be dropped");
      assertTrue(engine.commands.contains("name"), "startup handshake command must flow");
      assertEquals(1, engine.analyzeCount(), "one analysis starts after the stable restore point");
      assertEquals(0, engine.analyzePosition(), "analysis must start from the restored position");
      assertEquals(1, engine.ponderCount, "ponder must run exactly once");
      assertEquals(1, engine.responseFreshenedCount, "response freshening must run exactly once");
      assertEquals(1, env.readyTransitions.get() - readyBaseline, "markEngineReady exactly once");
      assertTrue(engine.isLoaded, "engine must stay available on success");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void liveUpdateAdmissionIsLinearizedWithBarrierStart() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      engine.startEngine(0);

      BlockingStartupConfig config = allocate(BlockingStartupConfig.class);
      config.doubleEngineQueryEntered = new CountDownLatch(1);
      config.allowDoubleEngineQuery = new CountDownLatch(1);
      Lizzie.config = config;
      AtomicReference<Throwable> sendFailure = new AtomicReference<>();
      Thread liveUpdateThread =
          new Thread(
              () -> {
                try {
                  engine.sendCommand("play B Q4");
                } catch (Throwable failure) {
                  sendFailure.set(failure);
                }
              },
              "issue-223-live-update-admission-race");
      liveUpdateThread.start();

      assertTrue(
          config.doubleEngineQueryEntered.await(2, TimeUnit.SECONDS),
          "the live update must pass its precheck before the barrier starts");
      engine.beginInitialBoardSynchronization();
      config.allowDoubleEngineQuery.countDown();
      liveUpdateThread.join(2_000L);

      assertFalse(liveUpdateThread.isAlive(), "the live update admission race must settle");
      assertNull(sendFailure.get(), "the live update must be rejected without throwing");
      assertFalse(
          engine.commands.contains("play B Q4"),
          "a live update crossing barrier start must not enter the command queue");
      engine.endInitialBoardSynchronization();
    }
  }

  @ParameterizedTest
  @MethodSource("ordinaryCommandsRejectedDuringStartupAdmission")
  void startupAdmissionRejectsOrdinaryLiveBoardCommand(String command) throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicBoolean probed = new AtomicBoolean();
      startup.beforeRestore =
          () -> {
            int commandsBefore = engine.commands.size();
            engine.sendCommand(command);
            assertFalse(
                engine.commands.subList(commandsBefore, engine.commands.size()).contains(command),
                command + " must not enter the queue while startup admission is active");
            probed.set(true);
          };

      runStartupInThread(startup, engine);

      assertTrue(probed.get(), "admission probe must run during the frozen route");
      assertEquals(1, engine.ponderCount, "ponder waits for the stable restore point");
      assertLifecycleReservationReleased(engine);
    }
  }

  @ParameterizedTest
  @MethodSource("handshakeAndSizeCommandsAllowedDuringStartupAdmission")
  void startupAdmissionAllowsHandshakeAndSizeConvergenceCommand(String command) throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicBoolean probed = new AtomicBoolean();
      startup.beforeRestore =
          () -> {
            engine.sendCommand(command);
            assertTrue(
                engine.commands.contains(command),
                command + " must keep flowing while startup admission is active");
            probed.set(true);
          };

      runStartupInThread(startup, engine);

      assertTrue(probed.get(), "admission probe must run during the frozen route");
      assertEquals(1, engine.ponderCount, "ponder waits for the stable restore point");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void startupAdmissionAllowsExactRestoreRoutePlayWhileRejectingOrdinaryPlay() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      startup.beforeRestore = () -> engine.sendCommand("play B Q4");

      runStartupInThread(startup, engine);

      assertFalse(engine.containsCommand("play B Q4"), "ordinary play must stay out of the queue");
      assertTrue(
          engine.containsCommand(play("B", 4, 3)),
          "exact restore route play must still reach the engine");
      assertEquals(1, engine.enginePosition.get(), "engine must converge on the restored move");
      assertEquals(1, engine.ponderCount, "ponder waits for the stable restore point");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void capturedTargetAndMirrorShareStartupAdmission() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz target = new StartupSyncLeelaz();
      StartupSyncLeelaz mirror = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(target, board);
      target.startEngine(0);
      mirror.startEngine(1);
      int readyBaseline = env.readyTransitions.get();

      EngineManager.InitialEngineStartupSynchronization startup =
          EngineManager.InitialEngineStartupSynchronization.capture(
              null, target, mirror, board, false, false);
      AtomicInteger readyDuringAdmission = new AtomicInteger(-1);
      startup.beforeRestore =
          () -> {
            target.sendCommand("play B Q4");
            mirror.sendCommand("play B Q4");
            target.sendCommand("name");
            mirror.sendCommand("name");
            assertFalse(target.containsCommand("play B Q4"));
            assertFalse(mirror.containsCommand("play B Q4"));
            assertTrue(target.containsCommand("name"), "handshake must flow on the target");
            assertTrue(mirror.containsCommand("name"), "handshake must flow on the mirror");
            assertEquals(0, target.ponderCount, "target must not ponder before stable handoff");
            assertEquals(0, mirror.ponderCount, "mirror must not ponder before stable handoff");
            readyDuringAdmission.set(env.readyTransitions.get());
          };

      runStartupInThread(startup, target);

      assertEquals(
          readyBaseline,
          readyDuringAdmission.get(),
          "READY must wait for the shared admission handoff");
      assertEquals(1, env.readyTransitions.get() - readyBaseline, "READY publishes once");
      assertEquals(1, target.ponderCount, "target ponders only after the shared handoff");
      target.sendCommand("play B Q16");
      mirror.sendCommand("play B Q16");
      assertTrue(target.containsCommand("play B Q16"), "target admission must reopen");
      assertTrue(mirror.containsCommand("play B Q16"), "mirror admission must reopen");
      assertLifecycleReservationReleased(target);
      assertLifecycleReservationReleased(mirror);
    }
  }

  @Test
  void representativeLiveBoardResyncLosesRaceToAdmissionStart() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      engine.startEngine(0);

      BlockingStartupConfig config = allocate(BlockingStartupConfig.class);
      config.doubleEngineQueryEntered = new CountDownLatch(1);
      config.allowDoubleEngineQuery = new CountDownLatch(1);
      Lizzie.config = config;
      AtomicReference<Throwable> resyncFailure = new AtomicReference<>();
      Thread resyncThread =
          new Thread(
              () -> {
                try {
                  board.resendCurrentPositionToPrimaryEngine();
                } catch (Throwable failure) {
                  resyncFailure.set(failure);
                }
              },
              "startup-admission-representative-resync-race");
      resyncThread.start();

      assertTrue(
          config.doubleEngineQueryEntered.await(2, TimeUnit.SECONDS),
          "the representative resync must pass precheck before admission starts");
      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      try {
        config.allowDoubleEngineQuery.countDown();
        resyncThread.join(2_000L);

        assertFalse(resyncThread.isAlive(), "the representative resync race must settle");
        assertNull(resyncFailure.get(), "the representative resync must not throw");
        assertFalse(engine.containsCommand("play B Q4"));
        assertFalse(engine.containsCommand("undo"));
        assertFalse(
            engine.commands.contains("clear_board"),
            "ordinary clear from the raced resync must not enter the queue");
        engine.sendCommand("name");
        assertTrue(engine.commands.contains("name"), "handshake must still flow");
      } finally {
        startup.close();
      }

      engine.sendCommand("play B Q16");
      assertTrue(
          engine.containsCommand("play B Q16"),
          "ordinary play must enter after admission reopens");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void rejectedOverlappingStartupOwnerLeavesNoAdmissionOccupancy() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization first = captureStartup(engine, board);
      try {
        assertThrows(
            IllegalStateException.class,
            () -> captureStartup(engine, board),
            "the overlapping owner must lose the existing lifecycle reservation");
      } finally {
        first.close();
      }

      assertLifecycleReservationReleased(engine);
      engine.sendCommand("play B Q16");
      assertTrue(
          engine.containsCommand("play B Q16"),
          "the rejected owner must not leave command admission occupied");
    }
  }

  @Test
  void rejectedMirrorAdmissionRollsBackAcceptedTargetAdmission() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz target = new StartupSyncLeelaz();
      StartupSyncLeelaz occupiedMirror = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(target, board);
      target.startEngine(0);
      occupiedMirror.startEngine(1);

      EngineManager.InitialEngineStartupSynchronization existingMirrorOwner =
          captureStartup(occupiedMirror, board);
      try {
        assertThrows(
            IllegalStateException.class,
            () ->
                EngineManager.InitialEngineStartupSynchronization.capture(
                    null, target, occupiedMirror, board, false, false),
            "the occupied mirror must reject the second startup owner");

        assertTrue(
            target.submitOrdinaryLiveBoardForwarding(
                EngineManager.OrdinaryLiveBoardForwardingIntent.of(() -> true)),
            "a mirror rejection must roll back the target admission accepted earlier");

        assertThrows(
            IllegalStateException.class,
            () ->
                EngineManager.InitialEngineStartupSynchronization.capturePrepared(
                    null, target, occupiedMirror, board, false, false),
            "the occupied mirror must also reject a prepared startup owner");
        assertTrue(
            target.submitOrdinaryLiveBoardForwarding(
                EngineManager.OrdinaryLiveBoardForwardingIntent.of(() -> true)),
            "a prepared mirror rejection must also roll back its accepted target admission");
      } finally {
        existingMirrorOwner.close();
      }

      assertLifecycleReservationReleased(target);
      assertLifecycleReservationReleased(occupiedMirror);
    }
  }

  @Test
  void admissionStartingAfterSubmitPrecheckRejectsAllOrdinaryForwardingCommands()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      engine.startEngine(0);

      CountDownLatch actionEntered = new CountDownLatch(1);
      CountDownLatch continueAction = new CountDownLatch(1);
      AtomicReference<Boolean> submitResult = new AtomicReference<>();
      AtomicReference<Throwable> submitFailure = new AtomicReference<>();
      Thread forwardingThread =
          new Thread(
              () -> {
                try {
                  submitResult.set(
                      engine.submitOrdinaryLiveBoardForwarding(
                          EngineManager.OrdinaryLiveBoardForwardingIntent.of(
                              () -> {
                                actionEntered.countDown();
                                awaitLatch(continueAction);
                                engine.sendCommand("komi 9.5");
                                engine.sendCommand("boardsize 13");
                                engine.sendCommand("clear_board");
                                return true;
                              })));
                } catch (Throwable failure) {
                  submitFailure.set(failure);
                }
              },
              "startup-admission-submit-execute-race");
      forwardingThread.start();

      assertTrue(
          actionEntered.await(2, TimeUnit.SECONDS),
          "ordinary forwarding must pass submit precheck before startup begins");
      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      try {
        continueAction.countDown();
        forwardingThread.join(2_000L);

        assertFalse(forwardingThread.isAlive(), "ordinary forwarding race must settle");
        assertNull(submitFailure.get(), "ordinary forwarding rejection must not throw");
        assertEquals(Boolean.FALSE, submitResult.get(), "raced ordinary forwarding is rejected");
        assertFalse(engine.containsCommand("komi 9.5"));
        assertFalse(engine.containsCommand("boardsize 13"));
        assertFalse(engine.containsCommand("clear_board"));
      } finally {
        continueAction.countDown();
        startup.close();
      }

      assertLifecycleReservationReleased(engine);
    }
  }

  private static Stream<Arguments> ordinaryCommandsRejectedDuringStartupAdmission() {
    return Stream.of(
        Arguments.of("play B Q4"),
        Arguments.of("undo"),
        Arguments.of("clear_board"),
        Arguments.of("lz-analyze 99"),
        Arguments.of("kata-analyze 99"),
        Arguments.of("analyze 99"),
        Arguments.of("kata-raw-nn 0"),
        Arguments.of("heat"));
  }

  private static Stream<Arguments> handshakeAndSizeCommandsAllowedDuringStartupAdmission() {
    return Stream.of(
        Arguments.of("name"),
        Arguments.of("version"),
        Arguments.of("list_commands"),
        Arguments.of("komi 6.5"),
        Arguments.of("boardsize 19"));
  }

  @Test
  void lifecycleReservationIsReleasedBeforeReadyPublication() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicBoolean observeReady = new AtomicBoolean(false);
      AtomicReference<Boolean> reservationReleasedAtReady = new AtomicReference<>();
      java.util.function.Consumer<EngineStartupStatus.Snapshot> listener =
          snapshot -> {
            if (!observeReady.get() || snapshot.state != EngineStartupStatus.State.READY) {
              return;
            }
            Leelaz.ExclusiveGtpLifecycleReservation reservation =
                engine.beginExclusiveGtpLifecycleReservation(new Object());
            reservationReleasedAtReady.set(reservation != null);
            if (reservation != null) {
              reservation.close();
            }
          };
      Lizzie.engineStartupStatus.addListener(listener);
      try {
        observeReady.set(true);
        runStartupInThread(startup, engine);
      } finally {
        Lizzie.engineStartupStatus.removeListener(listener);
      }

      assertEquals(
          Boolean.TRUE,
          reservationReleasedAtReady.get(),
          "READY observers must see the lifecycle reservation already released");
    }
  }

  @Test
  void navigationDuringStartupConvergesWithCatchUpRoute() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(2);
      history.toStart(); // capture matches "initial capture at move 0" from the spec
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() == 0) {
              // 0 -> 1 -> 2 while the engine is starting
              assertTrue(board.nextMove(false));
              assertTrue(board.nextMove(false));
            }
          };

      runStartupInThread(startup, engine);

      assertEquals(2, env.clearBoardCount(engine), "frozen route plus one catch-up route");
      assertEquals(2, engine.analyzePosition(), "engine must converge on the final node");
      assertEquals(2, engine.playsAfterLastClear().size());
      assertEquals(
          List.of(play("B", 4, 3), play("W", 5, 3)),
          engine.playsAfterLastClear(),
          "catch-up replay must rebuild the full position from the root");
      assertEquals(1, engine.ponderCount, "analysis must start only at the stable point");
      assertSame(board.getHistory().getCurrentHistoryNode(), board.getHistory().getEnd());
    }
  }

  @Test
  void delayedFakeGtpConvergesToMoveFiveBeforeAnalysis() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(5);
      history.toStart();
      Board board = boardWithHistory(history);
      env.publish(engine, board);

      CountDownLatch firstClearBoardReceived = new CountDownLatch(1);
      CountDownLatch navigationCompleted = new CountDownLatch(1);
      AtomicBoolean delayFirstClearBoard = new AtomicBoolean(true);
      AtomicReference<Throwable> navigationFailure = new AtomicReference<>();
      engine.beforeCommand =
          command -> {
            if (command.equals("clear_board") && delayFirstClearBoard.compareAndSet(true, false)) {
              firstClearBoardReceived.countDown();
              if (!navigationCompleted.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("navigation did not complete while GTP was delayed");
              }
            }
          };

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      engine.startEngine(0);
      Thread navigationThread =
          new Thread(
              () -> {
                try {
                  if (!firstClearBoardReceived.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("fake GTP did not receive the frozen clear_board");
                  }
                  for (int move = 1; move <= 5; move++) {
                    assertTrue(board.nextMove(false), "navigation must reach move " + move);
                  }
                } catch (Throwable failure) {
                  navigationFailure.set(failure);
                } finally {
                  navigationCompleted.countDown();
                }
              },
              "issue-223-delayed-gtp-navigation");
      navigationThread.start();

      runStartupInThread(startup, engine);
      navigationThread.join(2_000L);

      assertFalse(navigationThread.isAlive(), "navigation thread must settle");
      assertNull(navigationFailure.get(), "navigation during delayed GTP must succeed");
      assertEquals(5, board.getHistory().getMoveNumber(), "currentMove");
      assertEquals(5, engine.enginePosition.get(), "engineMove");
      assertEquals(5, engine.analyzePosition(), "analyzeAtMove");
      assertEquals(2, engine.clearBoardCount.get(), "frozen route plus one catch-up route");
      assertEngineMatchesBoard(engine, board, 19, 19);
    }
  }

  @Test
  void foregroundActivationConvergesAfterDelayedReadyAndOrdinaryMoveNavigation() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.delayReadyAfterStart = true;
      engine.boardSynchronizationGate = new CountDownLatch(1);
      BoardHistoryList history = loadReporterGameFixture();
      history.toStart();
      Board board = boardWithHistory(history);
      Lizzie.board = board;
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(engine)));
      Lizzie.engineManager = manager;
      env.expectedReadyEngineIndex = 0;

      Stone[] expectedMoveEightStones = mainLineNodeAtMove(history, 8).getData().stones.clone();
      assertTrue(manager.switchEngineIfAvailable(0, true));
      assertTrue(engine.startCompleted.await(2, TimeUnit.SECONDS), "engine startup must begin");

      navigateZeroToEightToNineAndBack(board);
      engine.publishReady();

      assertTrue(
          engine.boardSynchronizationEntered.await(2, TimeUnit.SECONDS),
          "the final board fence must remain separately observable");
      try {
        assertSame(engine, Lizzie.leelaz);
        assertEquals(
            -1,
            EngineManager.currentEngineNo,
            "the provisional runtime must not publish an active catalog index");
        assertTrue(
            EngineManager.isEmpty,
            "the first activation stays logically empty until its final fence succeeds");
        assertEquals(
            EngineManager.EngineSwitchUiPhase.SWITCHING,
            manager.engineSwitchUiSnapshot(true).phase(),
            "the selected model remains visibly switching until the final fence");
      } finally {
        engine.boardSynchronizationGate.countDown();
      }
      assertTrue(
          engine.analysisStarted.await(2, TimeUnit.SECONDS),
          "analysis must start after the final board fence succeeds");
      assertTrue(
          manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS),
          "production synchronization worker must complete before assertions");
      assertEquals(0, EngineManager.currentEngineNo);
      assertFalse(EngineManager.isEmpty);
      assertEquals(
          EngineManager.EngineSwitchUiPhase.ACTIVE,
          manager.engineSwitchUiSnapshot(true).phase(),
          "the exact target becomes active with the terminal selection commit");
      assertTrue(
          env.readyObservedCommittedOwner.get(),
          "READY observers must see the committed foreground owner");
      assertEquals(8, board.getHistory().getMoveNumber(), "history cursor");
      assertTrue(
          Arrays.equals(expectedMoveEightStones, board.getHistory().getData().stones),
          "the reporter SGF's first eight ordinary moves must remain at move eight");
      assertEquals(
          8L,
          Arrays.stream(board.getHistory().getData().stones)
              .filter(stone -> stone != Stone.EMPTY)
              .count(),
          "the reporter SGF has eight uncaptured stones at move eight");
      assertEquals(8, engine.enginePosition.get(), "engine position");
      assertEquals(8, engine.analyzePosition(), "analysis position");
      assertEquals(1, engine.ponderCount, "analysis starts once at the stable position");
      assertEngineMatchesBoardWithoutKomi(engine, board, 19, 19);
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void explicitPrimaryRestartConvergesAfterDelayedReadyAndNavigation() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.started = true;
      engine.isLoaded = true;
      engine.Pondering();
      engine.delayReadyAfterStart = true;
      BoardHistoryList history = loadReporterGameFixture();
      history.toStart();
      Board board = boardWithHistory(history);
      Lizzie.board = board;
      Lizzie.leelaz = engine;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(engine)));
      Lizzie.engineManager = manager;

      manager.reStartEngine(0);
      assertTrue(engine.startCompleted.await(2, TimeUnit.SECONDS), "engine restart must begin");

      navigateZeroToEightToNineAndBack(board);
      engine.publishReady();

      assertTrue(
          engine.analysisStarted.await(2, TimeUnit.SECONDS),
          "analysis must start after the restarted engine converges");
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      assertEquals(8, board.getHistory().getMoveNumber(), "history cursor");
      assertEquals(8, engine.enginePosition.get(), "restarted engine position");
      assertEquals(2, engine.clearBoardCount.get(), "frozen route plus one catch-up route");
      assertEquals(8, engine.playsAfterLastClear().size(), "the catch-up route must replay move eight");
      assertEquals(8, engine.analyzePosition(), "analysis position");
      assertEquals(1, engine.boardSynchronizationConfirmations, "restart target fence");
      assertFenceBeforeAnalyze(engine);
      assertEquals(1, engine.ponderCount, "analysis starts once at the stable position");
      assertEngineMatchesBoardWithoutKomi(engine, board, 19, 19);
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void primarySwitchConvergesAfterDelayedReadyAndNavigation() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz current = new StartupSyncLeelaz();
      StartupSyncLeelaz target = new StartupSyncLeelaz();
      current.started = true;
      current.isLoaded = true;
      current.Pondering();
      target.delayReadyAfterStart = true;
      Lizzie.config.fastChange = true;
      BoardHistoryList history = emptyRootHistory(5);
      history.toStart();
      Board board = boardWithHistory(history);
      Lizzie.board = board;
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(current, target)));
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(1, true));
      assertTrue(target.startCompleted.await(2, TimeUnit.SECONDS), "target startup must begin");
      EngineManager.EngineSwitchUiSnapshot pendingSwitch = manager.engineSwitchUiSnapshot(true);
      assertEquals(EngineManager.EngineSwitchUiPhase.SWITCHING, pendingSwitch.phase());
      assertEquals(0, pendingSwitch.activeIndex(), "the committed engine remains active while slow");
      assertEquals(1, pendingSwitch.targetIndex(), "the requested target is published immediately");
      navigateZeroToFiveToThree(board);
      target.publishReady();

      assertTrue(target.analysisStarted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      assertSame(target, Lizzie.leelaz);
      assertEquals(1, EngineManager.currentEngineNo);
      EngineManager.EngineSwitchUiSnapshot completedSwitch = manager.engineSwitchUiSnapshot(true);
      assertEquals(EngineManager.EngineSwitchUiPhase.ACTIVE, completedSwitch.phase());
      assertEquals(1, completedSwitch.activeIndex(), "READY commits the target as active");
      assertEquals(3, target.enginePosition.get(), "target engine position");
      assertEquals(2, target.clearBoardCount.get(), "frozen route plus one catch-up route");
      assertEquals(
          List.of(play("B", 4, 3), play("W", 5, 3), play("B", 6, 3)),
          target.playsAfterLastClear(),
          "no stale frozen route may overwrite the final switch route");
      assertEquals(3, target.analyzePosition(), "analysis position");
      assertEquals(1, target.boardSynchronizationConfirmations, "switch target fence");
      assertFenceBeforeAnalyze(target);
      assertEngineMatchesBoard(target, board, 19, 19);
      assertLifecycleReservationReleased(current);
      assertLifecycleReservationReleased(target);
    }
  }

  @Test
  void ordinarySwitchClosesAReaderPublishedBeforeStartThrowsAndRollsBackSelection()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz current = new StartupSyncLeelaz();
      PartialOrdinarySwitchStartLeelaz target = new PartialOrdinarySwitchStartLeelaz();
      current.started = true;
      current.isLoaded = true;
      Lizzie.config.fastChange = true;
      Lizzie.board = boardWithHistory(emptyRootHistory(1));
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(current, target)));
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(1, true));
      assertTrue(manager.synchronizationFailed.await(2, TimeUnit.SECONDS));
      long settlementDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while ((managerAtomicReferenceValue(manager, "engineSwitchTransaction") != null
              || target.started)
          && System.nanoTime() < settlementDeadline) {
        Thread.sleep(10L);
      }

      assertSame(current, Lizzie.leelaz, "the failed provisional target must be rolled back");
      assertEquals(0, EngineManager.currentEngineNo);
      assertFalse(target.started, "the partially published target reader must be stopped");
      assertFalse(target.isLoaded, "the failed target must remain unavailable");
      assertEquals(
          EngineManager.EngineSwitchUiPhase.FAILED,
          manager.engineSwitchUiSnapshot(true).phase());
      assertNull(managerAtomicReferenceValue(manager, "engineSwitchTransaction"));
      assertLifecycleReservationReleased(current);
      assertLifecycleReservationReleased(target);
      Leelaz.UpdateEngineStartAttempt retry = target.beginUpdateEngineStartAttempt();
      retry.failClose(new AssertionError("controlled ordinary switch retry settlement"));
    }
  }

  @Test
  void ordinarySwitchSynchronizationDispatchErrorExactClosesTheStartedTarget()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz current = new StartupSyncLeelaz();
      StartupSyncLeelaz target = new StartupSyncLeelaz();
      current.started = true;
      current.isLoaded = true;
      Lizzie.config.fastChange = true;
      Lizzie.board = boardWithHistory(emptyRootHistory(1));
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      FailingOrdinarySynchronizationDispatchEngineManager manager =
          new FailingOrdinarySynchronizationDispatchEngineManager(
              new ArrayList<>(List.of(current, target)));
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(1, true));
      assertTrue(target.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.failurePresented.await(2, TimeUnit.SECONDS));
      long settlementDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while ((managerAtomicReferenceValue(manager, "engineSwitchTransaction") != null
              || target.started)
          && System.nanoTime() < settlementDeadline) {
        Thread.sleep(10L);
      }

      assertEquals(1, manager.dispatchAttempts.get());
      assertSame(current, Lizzie.leelaz);
      assertEquals(0, EngineManager.currentEngineNo);
      assertFalse(target.started, "dispatch failure must exact-close the new reader");
      assertFalse(target.isLoaded);
      assertEquals(
          EngineManager.EngineSwitchUiPhase.FAILED,
          manager.engineSwitchUiSnapshot(true).phase());
      assertNull(managerAtomicReferenceValue(manager, "engineSwitchTransaction"));
      assertLifecycleReservationReleased(current);
      assertLifecycleReservationReleased(target);
      Leelaz.UpdateEngineStartAttempt retry = target.beginUpdateEngineStartAttempt();
      retry.failClose(new AssertionError("controlled dispatch-failure retry settlement"));
    }
  }

  @Test
  void edtSwitchReturnsBeforeBlockingStartupAndKeepsSwitchingThroughTheFirstPump()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz current = new StartupSyncLeelaz();
      StartupSyncLeelaz target = new StartupSyncLeelaz();
      current.started = true;
      current.isLoaded = true;
      target.delayReadyAfterStart = true;
      target.startReturnGate = new CountDownLatch(1);
      Lizzie.board = boardWithHistory(emptyRootHistory(1));
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(current, target)));
      Lizzie.engineManager = manager;
      AtomicBoolean submitted = new AtomicBoolean();

      SwingUtilities.invokeAndWait(
          () -> {
            submitted.set(manager.switchEngineIfAvailable(1, true));
            assertEquals(
                EngineManager.EngineSwitchUiPhase.SWITCHING,
                manager.engineSwitchUiSnapshot(true).phase());
          });

      assertTrue(submitted.get());
      assertTrue(target.startEntered.await(2, TimeUnit.SECONDS));
      assertFalse(
          manager.switchEngineIfAvailable(0, false),
          "primary and secondary switches must share one board-mutation transaction gate");
      SwingUtilities.invokeAndWait(
          () ->
              assertEquals(
                  EngineManager.EngineSwitchUiPhase.SWITCHING,
                  manager.engineSwitchUiSnapshot(true).phase()));
      target.startReturnGate.countDown();
      assertTrue(target.startCompleted.await(2, TimeUnit.SECONDS));
      assertEquals(
          EngineManager.EngineSwitchUiPhase.SWITCHING,
          manager.engineSwitchUiSnapshot(true).phase());

      target.publishReady();
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      assertEquals(
          EngineManager.EngineSwitchUiPhase.ACTIVE,
          manager.engineSwitchUiSnapshot(true).phase());
    }
  }

  @Test
  void edtSwitchPublishesBeforeHistorySizedPreparationStarts() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz current = new StartupSyncLeelaz();
      StartupSyncLeelaz target = new StartupSyncLeelaz();
      current.started = true;
      current.isLoaded = true;
      BlockingPrepareBoard board = blockingBoardWithHistory(emptyRootHistory(2));
      Lizzie.board = board;
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(current, target)));
      Lizzie.engineManager = manager;
      AtomicBoolean submitted = new AtomicBoolean();

      SwingUtilities.invokeAndWait(
          () -> submitted.set(manager.switchEngineIfAvailable(1, true)));
      assertTrue(submitted.get(), "EDT admission must return before snapshot preparation");
      assertEquals(
          EngineManager.EngineSwitchUiPhase.SWITCHING,
          manager.engineSwitchUiSnapshot(true).phase());

      SwingUtilities.invokeAndWait(
          () ->
              assertEquals(
                  EngineManager.EngineSwitchUiPhase.SWITCHING,
                  manager.engineSwitchUiSnapshot(true).phase(),
                  "the first EDT pump must remain paintable before preparation"));
      assertTrue(
          board.historyReadEntered.await(2, TimeUnit.SECONDS),
          "history capture must start on the worker after the first pump");
      board.allowHistoryRead.countDown();
      assertTrue(target.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      assertEquals(
          EngineManager.EngineSwitchUiPhase.ACTIVE,
          manager.engineSwitchUiSnapshot(true).phase());
    }
  }

  @Test
  void realConstructorTracksDefaultStartupByCatalogPositionUntilFinalActive()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz positionalEngine = new StartupSyncLeelaz();
      StartupSyncLeelaz defaultEngine = new StartupSyncLeelaz();
      defaultEngine.delayReadyAfterStart = true;
      Lizzie.board = boardWithHistory(emptyRootHistory(1));
      EngineData positional = engineData(3, "positional", false);
      EngineData selectedDefault = engineData(17, "selected-default", true);
      ArrayList<EngineData> catalog =
          new ArrayList<>(List.of(positional, selectedDefault));

      EngineManager manager =
          new EngineManager(
              Lizzie.config,
              0,
              true,
              catalog,
              command ->
                  "selected-default".equals(command) ? defaultEngine : positionalEngine);
      Lizzie.engineManager = manager;

      assertTrue(defaultEngine.startCompleted.await(2, TimeUnit.SECONDS));
      assertEquals(1L, positionalEngine.startEntered.getCount());
      assertSame(defaultEngine, Lizzie.leelaz);
      assertEquals(-1, EngineManager.currentEngineNo);
      assertTrue(EngineManager.isEmpty);
      assertEquals(
          1, manager.engineSwitchUiSnapshot(true).targetIndex(),
          "default selection must publish the exact engine-list position");
      assertEquals(
          EngineManager.EngineSwitchUiPhase.SWITCHING,
          manager.engineSwitchUiSnapshot(true).phase());

      defaultEngine.publishReady();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (manager.engineSwitchUiSnapshot(true).phase()
              != EngineManager.EngineSwitchUiPhase.ACTIVE
          && System.nanoTime() < deadline) {
        Thread.sleep(10L);
      }

      assertEquals(
          EngineManager.EngineSwitchUiPhase.ACTIVE,
          manager.engineSwitchUiSnapshot(true).phase());
      assertEquals(1, EngineManager.currentEngineNo);
      assertFalse(EngineManager.isEmpty);
    }
  }

  @Test
  void nonSelectedInitialPreloadClosesAReaderPublishedBeforeStartThrows() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      PartialInitialPreloadLeelaz engine = new PartialInitialPreloadLeelaz();
      EngineData data = engineData(23, "partial-initial-preload", false);
      data.preload = true;
      CountDownLatch workerCompleted = new CountDownLatch(1);
      AtomicReference<Throwable> escapedWorkerFailure = new AtomicReference<>();
      EngineManager.setInitialEnginePreloadSchedulerForTest(
          new EngineManager.InitialEnginePreloadScheduler() {
            @Override
            public Thread create(Runnable work, String name) {
              return new Thread(
                  () -> {
                    try {
                      work.run();
                    } catch (Throwable failure) {
                      escapedWorkerFailure.set(failure);
                    } finally {
                      workerCompleted.countDown();
                    }
                  },
                  name);
            }

            @Override
            public void configure(Thread worker) {
              worker.setDaemon(true);
            }

            @Override
            public void start(Thread worker) {
              worker.start();
            }
          });
      try {
        Lizzie.board = boardWithHistory(emptyRootHistory(0));
        Lizzie.leelaz = null;
        Lizzie.config.uiConfig = new org.json.JSONObject();
        new EngineManager(
            Lizzie.config,
            -1,
            false,
            new ArrayList<>(List.of(data)),
            command -> engine);

        assertTrue(workerCompleted.await(2, TimeUnit.SECONDS));
        assertNull(escapedWorkerFailure.get(), "the preload worker must settle its own failure");
        assertFalse(engine.started, "the partially published preload runtime must be stopped");
        assertFalse(engine.isLoaded, "the failed preload must never become routable");
        assertTrue(
            ((StartupSyncLeelaz) engine).notPonderingCount > 0,
            "exact cleanup must retire the published binding");
        Leelaz.UpdateEngineStartAttempt retry = engine.beginUpdateEngineStartAttempt();
        retry.failClose(new AssertionError("controlled preload retry settlement"));
      } finally {
        EngineManager.setInitialEnginePreloadSchedulerForTest(null);
        engine.forceQuit();
      }
    }
  }

  @Test
  void nonSelectedInitialPreloadStartThenThrowClosesOnlyTheScheduledIncarnation()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      EngineData data = engineData(29, "start-then-throw-initial-preload", false);
      data.preload = true;
      AssertionError schedulingFailure =
          new AssertionError("controlled initial preload start-after-throw failure");
      CountDownLatch workerCompleted = new CountDownLatch(1);
      AtomicReference<Object> replacementIncarnation = new AtomicReference<>();
      EngineManager.setInitialEnginePreloadSchedulerForTest(
          new EngineManager.InitialEnginePreloadScheduler() {
            @Override
            public Thread create(Runnable work, String name) {
              return new Thread(
                  () -> {
                    try {
                      work.run();
                    } finally {
                      workerCompleted.countDown();
                    }
                  },
                  name);
            }

            @Override
            public void configure(Thread worker) {
              worker.setDaemon(true);
            }

            @Override
            public void start(Thread worker) {
              worker.start();
              awaitLatch(engine.startCompleted);
              assertEquals(
                  0L,
                  engine.startCompleted.getCount(),
                  "the worker must publish its reader before the scheduler fails");
              engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
              replacementIncarnation.set(engine.currentEngineIncarnation());
              engine.started = true;
              engine.isLoaded = true;
              throw schedulingFailure;
            }
          });
      try {
        Lizzie.board = boardWithHistory(emptyRootHistory(0));
        Lizzie.leelaz = null;
        Lizzie.config.uiConfig = new org.json.JSONObject();
        AssertionError thrown =
            assertThrows(
                AssertionError.class,
                () ->
                    new EngineManager(
                        Lizzie.config,
                        -1,
                        false,
                        new ArrayList<>(List.of(data)),
                        command -> engine));

        assertSame(schedulingFailure, thrown);
        assertTrue(workerCompleted.await(2, TimeUnit.SECONDS));
        assertNotNull(replacementIncarnation.get());
        assertSame(replacementIncarnation.get(), engine.currentEngineIncarnation());
        assertTrue(
            engine.started,
            "cleanup for the scheduled incarnation must preserve its replacement");
        assertTrue(engine.isLoaded, "the replacement incarnation must remain routable");
        Leelaz.UpdateEngineStartAttempt retry = engine.beginUpdateEngineStartAttempt();
        retry.failClose(new AssertionError("controlled replacement-preserving retry settlement"));
      } finally {
        EngineManager.setInitialEnginePreloadSchedulerForTest(null);
        engine.forceQuit();
      }
    }
  }

  @Test
  void selectedInitialStartupClosesAReaderPublishedBeforeStartThrowsAndRollsBackSelection()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      PartialSelectedInitialStartupLeelaz engine = new PartialSelectedInitialStartupLeelaz();
      Lizzie.board = boardWithHistory(emptyRootHistory(0));
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      EngineManager manager =
          new EngineManager(
              Lizzie.config,
              0,
              false,
              new ArrayList<>(List.of(engineData(31, "partial-selected-startup", false))),
              command -> engine);

      assertTrue(((StartupSyncLeelaz) engine).startCompleted.await(2, TimeUnit.SECONDS));
      long settlementDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while ((managerAtomicReferenceValue(manager, "engineSwitchTransaction") != null
              || manager.engineSwitchUiSnapshot(true).phase()
                  == EngineManager.EngineSwitchUiPhase.SWITCHING)
          && System.nanoTime() < settlementDeadline) {
        Thread.sleep(10L);
      }
      assertNull(managerAtomicReferenceValue(manager, "engineSwitchTransaction"));
      assertEquals(
          EngineManager.EngineSwitchUiPhase.FAILED,
          manager.engineSwitchUiSnapshot(true).phase());
      assertEquals(0, manager.engineSwitchUiSnapshot(true).targetIndex());
      assertNull(Lizzie.leelaz, "the failed provisional PRIMARY must be rolled back");
      assertEquals(-1, EngineManager.currentEngineNo);
      assertTrue(EngineManager.isEmpty);
      assertFalse(engine.started, "the exact partial-start binding must be retired");
      assertFalse(engine.isLoaded);
      assertTrue(
          ((StartupSyncLeelaz) engine).notPonderingCount > 0,
          "the exact start-attempt cleanup must own the published binding");
      assertLifecycleReservationReleased(engine);
      Leelaz.UpdateEngineStartAttempt retry = engine.beginUpdateEngineStartAttempt();
      retry.failClose(new AssertionError("controlled selected-startup retry settlement"));
    }
  }

  @Test
  void failedInitialPrimaryCanBeRetriedAfterItsBinaryIsRepaired() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      FailOnceSelectedInitialStartupLeelaz engine =
          new FailOnceSelectedInitialStartupLeelaz();
      Lizzie.board = boardWithHistory(emptyRootHistory(0));
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      EngineManager manager =
          new EngineManager(
              Lizzie.config,
              0,
              false,
              new ArrayList<>(List.of(engineData(31, "repairable-selected-startup", false))),
              command -> engine);
      Lizzie.engineManager = manager;

      assertTrue(engine.firstStartCompleted.await(2, TimeUnit.SECONDS));
      long failureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while ((manager.engineSwitchUiSnapshot(true).phase()
                  != EngineManager.EngineSwitchUiPhase.FAILED
              || Lizzie.leelaz != null)
          && System.nanoTime() < failureDeadline) {
        Thread.sleep(10L);
      }
      assertNull(Lizzie.leelaz);
      assertTrue(EngineManager.isEmpty);
      assertEquals(-1, EngineManager.currentEngineNo);

      assertTrue(manager.retryUnavailablePrimaryEngine());
      assertTrue(engine.secondStartCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(((StartupSyncLeelaz) engine).analysisStarted.await(2, TimeUnit.SECONDS));
      long activeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (manager.engineSwitchUiSnapshot(true).phase()
                  != EngineManager.EngineSwitchUiPhase.ACTIVE
              && System.nanoTime() < activeDeadline) {
        Thread.sleep(10L);
      }
      assertSame(engine, Lizzie.leelaz);
      assertEquals(0, EngineManager.currentEngineNo);
      assertFalse(EngineManager.isEmpty);
      assertEquals(
          EngineManager.EngineSwitchUiPhase.ACTIVE,
          manager.engineSwitchUiSnapshot(true).phase());
    }
  }

  @Test
  void selectedInitialStartupFailurePreservesAReplacementReboundAfterCleanupClaim()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      PartialSelectedInitialStartupLeelaz engine = new PartialSelectedInitialStartupLeelaz();
      AtomicReference<Object> replacementIncarnation = new AtomicReference<>();
      EngineManager.setInitialEngineStartFailureSettlementHookForTest(
          failedEngine -> {
            assertSame(engine, failedEngine);
            engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
            replacementIncarnation.set(engine.currentEngineIncarnation());
            engine.started = true;
            engine.isLoaded = true;
            engine.isCheckingName = false;
          });
      try {
        Lizzie.board = boardWithHistory(emptyRootHistory(0));
        Lizzie.leelaz = null;
        EngineManager.isEmpty = true;
        EngineManager.currentEngineNo = -1;
        EngineManager manager =
            new EngineManager(
                Lizzie.config,
                0,
                false,
                new ArrayList<>(List.of(engineData(32, "rebound-selected-startup", false))),
                command -> engine);

        assertTrue(((StartupSyncLeelaz) engine).startCompleted.await(2, TimeUnit.SECONDS));
        long settlementDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (managerAtomicReferenceValue(manager, "engineSwitchTransaction") != null
            && System.nanoTime() < settlementDeadline) {
          Thread.sleep(10L);
        }

        assertNotNull(replacementIncarnation.get());
        assertSame(replacementIncarnation.get(), engine.currentEngineIncarnation());
        assertNull(managerAtomicReferenceValue(manager, "engineSwitchTransaction"));
        assertEquals(
            EngineManager.EngineSwitchUiPhase.IDLE,
            manager.engineSwitchUiSnapshot(true).phase(),
            "the failed A incarnation must not publish stale FAILED state over replacement B");
        assertNull(Lizzie.leelaz, "the provisional PRIMARY selection must still be rolled back");
        assertEquals(-1, EngineManager.currentEngineNo);
        assertTrue(EngineManager.isEmpty);
        assertTrue(engine.started, "cleanup for A must preserve replacement B");
        assertTrue(engine.isLoaded, "replacement B must remain live and reusable");
        assertLifecycleReservationReleased(engine);
        Leelaz.UpdateEngineStartAttempt retry = engine.beginUpdateEngineStartAttempt();
        retry.failClose(new AssertionError("controlled replacement retry settlement"));
      } finally {
        EngineManager.setInitialEngineStartFailureSettlementHookForTest(null);
        engine.forceQuit();
      }
    }
  }

  @Test
  void selectedInitialStartupStartThenThrowSettlesBeforeTheWorkerCanStartTheEngine()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      AssertionError schedulingFailure =
          new AssertionError("controlled selected startup start-after-throw failure");
      CountDownLatch workerCompleted = new CountDownLatch(1);
      EngineManager.setInitialEngineStartupSchedulerForTest(
          new EngineManager.InitialEngineStartupScheduler() {
            @Override
            public Thread create(Runnable work, String name) {
              return new Thread(
                  () -> {
                    try {
                      work.run();
                    } finally {
                      workerCompleted.countDown();
                    }
                  },
                  name);
            }

            @Override
            public void configure(Thread worker) {
              worker.setDaemon(true);
            }

            @Override
            public void start(Thread worker) {
              worker.start();
              throw schedulingFailure;
            }

            @Override
            public void dispatch(Runnable work) {
              SwingUtilities.invokeLater(work);
            }
          });
      try {
        Lizzie.board = boardWithHistory(emptyRootHistory(0));
        Lizzie.leelaz = null;
        EngineManager.isEmpty = true;
        EngineManager.currentEngineNo = -1;
        EngineManager manager =
            new EngineManager(
                Lizzie.config,
                0,
                false,
                new ArrayList<>(List.of(engineData(37, "selected-start-then-throw", false))),
                command -> engine);

        assertTrue(workerCompleted.await(2, TimeUnit.SECONDS));
        assertEquals(
            1L,
            engine.startEntered.getCount(),
            "the started worker must await the scheduler's final outcome");
        assertFalse(engine.started);
        assertNull(Lizzie.leelaz);
        assertNull(managerAtomicReferenceValue(manager, "engineSwitchTransaction"));
        assertEquals(
            EngineManager.EngineSwitchUiPhase.FAILED,
            manager.engineSwitchUiSnapshot(true).phase());
        assertTrue(
            manager.engineSwitchUiSnapshot(true).failureDetail().contains("start-after-throw"));
      } finally {
        EngineManager.setInitialEngineStartupSchedulerForTest(null);
        engine.forceQuit();
      }
    }
  }

  @Test
  void selectedInitialStartupInnerDispatchErrorReleasesTheUnstartedTransaction()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      AssertionError dispatchFailure =
          new AssertionError("controlled selected startup inner dispatch failure");
      AtomicInteger dispatchCount = new AtomicInteger();
      AtomicInteger startCount = new AtomicInteger();
      EngineManager.setInitialEngineStartupSchedulerForTest(
          new EngineManager.InitialEngineStartupScheduler() {
            @Override
            public Thread create(Runnable work, String name) {
              return new Thread(work, name);
            }

            @Override
            public void configure(Thread worker) {
              worker.setDaemon(true);
            }

            @Override
            public void start(Thread worker) {
              startCount.incrementAndGet();
              worker.start();
            }

            @Override
            public void dispatch(Runnable work) {
              if (dispatchCount.incrementAndGet() == 1) {
                work.run();
                return;
              }
              throw dispatchFailure;
            }
          });
      try {
        Lizzie.board = boardWithHistory(emptyRootHistory(0));
        Lizzie.leelaz = null;
        EngineManager.isEmpty = true;
        EngineManager.currentEngineNo = -1;
        AtomicReference<EngineManager> manager = new AtomicReference<>();
        SwingUtilities.invokeAndWait(
            () -> {
              try {
                manager.set(
                    new EngineManager(
                        Lizzie.config,
                        0,
                        false,
                        new ArrayList<>(
                            List.of(engineData(41, "selected-inner-dispatch-error", false))),
                        command -> engine));
              } catch (Exception constructionFailure) {
                throw new AssertionError(constructionFailure);
              }
            });

        assertNotNull(manager.get());
        assertEquals(2, dispatchCount.get());
        assertEquals(0, startCount.get());
        assertEquals(1L, engine.startEntered.getCount());
        assertNull(managerAtomicReferenceValue(manager.get(), "engineSwitchTransaction"));
        assertEquals(
            EngineManager.EngineSwitchUiPhase.FAILED,
            manager.get().engineSwitchUiSnapshot(true).phase());
        assertNull(Lizzie.leelaz);
      } finally {
        EngineManager.setInitialEngineStartupSchedulerForTest(null);
        engine.forceQuit();
      }
    }
  }

  private static EngineData engineData(int index, String command, boolean isDefault) {
    EngineData data = new EngineData();
    data.index = index;
    data.commands = command;
    data.name = command;
    data.width = 19;
    data.height = 19;
    data.komi = 7.5f;
    data.isDefault = isDefault;
    return data;
  }

  @Test
  void supersededInitialManagerCannotOverwriteNewManagerBoardOwnerOrReadyState()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engineA = new StartupSyncLeelaz();
      StartupSyncLeelaz engineB = new StartupSyncLeelaz();
      engineA.startReturnGate = new CountDownLatch(1);
      Board boardA = boardWithHistory(emptyRootHistory(1));
      Board boardB = boardWithHistory(emptyRootHistory(2));
      EngineData dataA = engineData(0, "manager-a", false);
      dataA.width = 9;
      dataA.height = 9;
      EngineData dataB = engineData(0, "manager-b", false);
      dataB.width = 13;
      dataB.height = 13;

      Lizzie.board = boardA;
      EngineManager managerA =
          new EngineManager(
              Lizzie.config,
              0,
              false,
              new ArrayList<>(List.of(dataA)),
              command -> engineA);
      assertTrue(engineA.startEntered.await(2, TimeUnit.SECONDS));

      Lizzie.board = boardB;
      EngineManager managerB =
          new EngineManager(
              Lizzie.config,
              0,
              false,
              new ArrayList<>(List.of(dataB)),
              command -> engineB);
      long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (managerB.engineSwitchUiSnapshot(true).phase()
              != EngineManager.EngineSwitchUiPhase.ACTIVE
          && System.nanoTime() < readyDeadline) {
        Thread.sleep(10L);
      }
      assertEquals(
          EngineManager.EngineSwitchUiPhase.ACTIVE,
          managerB.engineSwitchUiSnapshot(true).phase());

      engineA.startReturnGate.countDown();
      assertTrue(engineA.startCompleted.await(2, TimeUnit.SECONDS));
      long staleCleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (engineA.isStarted() && System.nanoTime() < staleCleanupDeadline) {
        Thread.sleep(10L);
      }

      assertSame(managerB, Lizzie.engineManager);
      assertSame(boardB, Lizzie.board);
      assertSame(engineB, Lizzie.leelaz);
      assertEquals(13, Board.boardWidth);
      assertEquals(13, Board.boardHeight);
      assertEquals(0, EngineManager.currentEngineNo);
      assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);
      assertFalse(engineA.isStarted(), "superseded A runtime must be cleaned up only");
      assertFalse(engineA.isLoaded(), "superseded A must never become routable");
      assertEquals(
          EngineManager.EngineSwitchUiPhase.ACTIVE,
          managerB.engineSwitchUiSnapshot(true).phase(),
          "A's stale token must not overwrite B's terminal state");
      assertTrue(managerA != managerB);
    }
  }

  @Test
  void supersededInitialManagerFinalAckStopsOldRuntimeOffReaderCallbackThread()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz staleEngine = new StartupSyncLeelaz();
      StartupSyncLeelaz activeEngine = new StartupSyncLeelaz();
      staleEngine.confirmBoardSynchronizationOnDedicatedThread = true;
      staleEngine.boardSynchronizationGate = new CountDownLatch(1);
      staleEngine.normalQuitEntered = new CountDownLatch(1);
      staleEngine.normalQuitGate = staleEngine.boardSynchronizationCallbackCompleted;
      staleEngine.normalQuitCompleted = new CountDownLatch(1);
      Board staleBoard = boardWithHistory(emptyRootHistory(1));
      Board activeBoard = boardWithHistory(emptyRootHistory(2));
      Lizzie.board = staleBoard;

      EngineManager staleManager =
          new EngineManager(
              Lizzie.config,
              0,
              false,
              new ArrayList<>(List.of(engineData(0, "stale-reader-owner", false))),
              command -> staleEngine);
      assertTrue(
          staleEngine.boardSynchronizationCallbackEntered.await(2, TimeUnit.SECONDS),
          "the stale manager's final ACK callback must be gated on its reader thread");

      Lizzie.board = activeBoard;
      EngineManager activeManager =
          new EngineManager(
              Lizzie.config,
              0,
              false,
              new ArrayList<>(List.of(engineData(0, "active-reader-owner", false))),
              command -> activeEngine);
      long activeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while ((activeManager.engineSwitchUiSnapshot(true).phase()
                  != EngineManager.EngineSwitchUiPhase.ACTIVE
              || managerAtomicReferenceValue(activeManager, "engineSwitchTransaction") != null)
          && System.nanoTime() < activeDeadline) {
        Thread.sleep(10L);
      }
      assertSame(activeManager, Lizzie.engineManager);
      assertSame(activeEngine, Lizzie.leelaz);

      staleEngine.boardSynchronizationGate.countDown();
      assertTrue(
          staleEngine.boardSynchronizationCallbackCompleted.await(2, TimeUnit.SECONDS),
          "a stale ACK callback must return before its process shutdown completes");
      assertTrue(staleEngine.normalQuitEntered.await(2, TimeUnit.SECONDS));
      assertTrue(staleEngine.normalQuitCompleted.await(2, TimeUnit.SECONDS));
      long staleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (managerAtomicReferenceValue(staleManager, "engineSwitchTransaction") != null
          && System.nanoTime() < staleDeadline) {
        Thread.sleep(10L);
      }

      assertNotNull(staleEngine.boardSynchronizationCallbackThread);
      assertNotNull(staleEngine.normalQuitThread);
      assertFalse(
          staleEngine.boardSynchronizationCallbackThread == staleEngine.normalQuitThread,
          "normalQuit must not run on the final-fence reader callback thread");
      assertFalse(staleEngine.isStarted());
      assertFalse(staleEngine.isLoaded());
      assertEquals(1, staleEngine.normalQuitCount);
      assertNull(managerAtomicReferenceValue(staleManager, "engineSwitchTransaction"));
      assertSame(activeEngine, Lizzie.leelaz, "stale cleanup must preserve the new manager owner");
      assertEquals(
          EngineManager.EngineSwitchUiPhase.ACTIVE,
          activeManager.engineSwitchUiSnapshot(true).phase());
      assertLifecycleReservationReleased(staleEngine);
      assertLifecycleReservationReleased(activeEngine);
    }
  }

  @Test
  void supersededInitialManagerFenceFailureCannotStopReusedEngineIncarnation()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz reusedEngine = new StartupSyncLeelaz();
      StartupSyncLeelaz activeEngine = new StartupSyncLeelaz();
      reusedEngine.confirmBoardSynchronizationOnDedicatedThread = true;
      reusedEngine.boardSynchronizationGate = new CountDownLatch(1);
      reusedEngine.boardSynchronizationFailure = "controlled stale final-fence failure";
      reusedEngine.normalQuitEntered = new CountDownLatch(1);
      reusedEngine.normalQuitCompleted = new CountDownLatch(1);
      Board staleBoard = boardWithHistory(emptyRootHistory(1));
      Board activeBoard = boardWithHistory(emptyRootHistory(2));
      Lizzie.board = staleBoard;

      EngineManager staleManager =
          new EngineManager(
              Lizzie.config,
              0,
              false,
              new ArrayList<>(List.of(engineData(0, "stale-reused-owner", false))),
              command -> reusedEngine);
      assertTrue(
          reusedEngine.boardSynchronizationCallbackEntered.await(2, TimeUnit.SECONDS));
      Object staleIncarnation = reusedEngine.currentEngineIncarnation();

      Lizzie.board = activeBoard;
      EngineManager activeManager =
          new EngineManager(
              Lizzie.config,
              0,
              false,
              new ArrayList<>(List.of(engineData(0, "active-reused-owner", false))),
              command -> activeEngine);
      long activeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while ((activeManager.engineSwitchUiSnapshot(true).phase()
                  != EngineManager.EngineSwitchUiPhase.ACTIVE
              || managerAtomicReferenceValue(activeManager, "engineSwitchTransaction") != null)
          && System.nanoTime() < activeDeadline) {
        Thread.sleep(10L);
      }

      Object replacementIncarnation = advanceEngineIncarnationForTest(reusedEngine);
      reusedEngine.started = true;
      reusedEngine.isLoaded = true;
      assertFalse(
          staleIncarnation == replacementIncarnation,
          "the fixture must replace the same Leelaz object's process incarnation");
      reusedEngine.boardSynchronizationGate.countDown();

      assertTrue(
          reusedEngine.boardSynchronizationCallbackCompleted.await(2, TimeUnit.SECONDS),
          "the stale failure callback must settle without touching the replacement runtime");
      assertFalse(
          reusedEngine.normalQuitEntered.await(250, TimeUnit.MILLISECONDS),
          "an old final-fence capability must not stop a replacement incarnation");
      long staleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (managerAtomicReferenceValue(staleManager, "engineSwitchTransaction") != null
          && System.nanoTime() < staleDeadline) {
        Thread.sleep(10L);
      }

      assertSame(replacementIncarnation, reusedEngine.currentEngineIncarnation());
      assertTrue(reusedEngine.isStarted());
      assertTrue(reusedEngine.isLoaded());
      assertEquals(0, reusedEngine.normalQuitCount);
      assertNull(managerAtomicReferenceValue(staleManager, "engineSwitchTransaction"));
      assertSame(activeManager, Lizzie.engineManager);
      assertSame(activeEngine, Lizzie.leelaz);
      assertLifecycleReservationReleased(reusedEngine);
      assertLifecycleReservationReleased(activeEngine);
    }
  }

  @Test
  void acceptedStaleQuarantineCannotRetireReboundRuntimeOrClearItsIcon() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz reusedEngine = new StartupSyncLeelaz();
      StartupSyncLeelaz interimEngine = new StartupSyncLeelaz();
      reusedEngine.confirmBoardSynchronizationOnDedicatedThread = true;
      reusedEngine.boardSynchronizationGate = new CountDownLatch(1);
      reusedEngine.boardSynchronizationFailure = "controlled stale final-fence failure";
      reusedEngine.incarnationRetirementEntered = new CountDownLatch(1);
      reusedEngine.incarnationRetirementGate = new CountDownLatch(1);
      reusedEngine.normalQuitCompleted = new CountDownLatch(1);
      Board staleBoard = boardWithHistory(emptyRootHistory(1));
      Board activeBoard = boardWithHistory(emptyRootHistory(2));
      Lizzie.board = staleBoard;

      EngineManager staleManager =
          new EngineManager(
              Lizzie.config,
              0,
              false,
              new ArrayList<>(List.of(engineData(0, "stale-cleanup-owner", false))),
              command -> reusedEngine);
      assertTrue(reusedEngine.boardSynchronizationCallbackEntered.await(2, TimeUnit.SECONDS));
      Object retiredIncarnation = reusedEngine.currentEngineIncarnation();

      Lizzie.board = activeBoard;
      EngineManager activeManager =
          new EngineManager(
              Lizzie.config,
              0,
              false,
              new ArrayList<>(List.of(engineData(0, "interim-owner", false))),
              command -> interimEngine);
      long activeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while ((activeManager.engineSwitchUiSnapshot(true).phase()
                  != EngineManager.EngineSwitchUiPhase.ACTIVE
              || managerAtomicReferenceValue(activeManager, "engineSwitchTransaction") != null)
          && System.nanoTime() < activeDeadline) {
        Thread.sleep(10L);
      }

      reusedEngine.boardSynchronizationGate.countDown();
      assertTrue(
          reusedEngine.incarnationRetirementEntered.await(2, TimeUnit.SECONDS),
          "the stale quarantine must be accepted before the replacement rebind");
      Object replacementIncarnation = advanceEngineIncarnationForTest(reusedEngine);
      assertFalse(retiredIncarnation == replacementIncarnation);
      reusedEngine.started = true;
      reusedEngine.isLoaded = true;
      activeManager.engineList.set(0, reusedEngine);
      Lizzie.setPrimaryEngine(reusedEngine);
      EngineManager.currentEngineNo = 0;
      EngineManager.isEmpty = false;
      SilentStartupMenu menu = (SilentStartupMenu) LizzieFrame.menu;
      SwingUtilities.invokeAndWait(() -> {});
      menu.stoppedPrimaryIconCount = 0;

      reusedEngine.incarnationRetirementGate.countDown();
      assertTrue(reusedEngine.normalQuitCompleted.await(2, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(() -> {});

      assertSame(replacementIncarnation, reusedEngine.currentEngineIncarnation());
      assertTrue(reusedEngine.isStarted(), "stale cleanup must preserve the replacement runtime");
      assertTrue(reusedEngine.isLoaded(), "stale cleanup must preserve replacement readiness");
      assertEquals(0, menu.stoppedPrimaryIconCount, "stale cleanup must not clear the new icon");
      assertNull(managerAtomicReferenceValue(staleManager, "engineSwitchTransaction"));
      assertSame(activeManager, Lizzie.engineManager);
      assertSame(reusedEngine, Lizzie.leelaz);
      assertLifecycleReservationReleased(reusedEngine);
    }
  }

  @Test
  void secondarySwitchConvergesTargetAndPrimaryMirrorAfterDelayedReadyAndNavigation()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz primary = new StartupSyncLeelaz();
      StartupSyncLeelaz currentSecondary = new StartupSyncLeelaz();
      StartupSyncLeelaz targetSecondary = new StartupSyncLeelaz();
      primary.started = true;
      primary.isLoaded = true;
      currentSecondary.started = true;
      currentSecondary.isLoaded = true;
      targetSecondary.delayReadyAfterStart = true;
      Lizzie.config.fastChange = true;
      Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Double_Engine;
      BoardHistoryList history = emptyRootHistory(5);
      history.toStart();
      Board board = boardWithHistory(history);
      Lizzie.board = board;
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = currentSecondary;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 1;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(
              new ArrayList<>(List.of(primary, currentSecondary, targetSecondary)));
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(2, false));
      assertTrue(
          targetSecondary.startCompleted.await(2, TimeUnit.SECONDS),
          "secondary target startup must begin");
      navigateZeroToFiveToThree(board);
      targetSecondary.publishReady();

      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      assertSame(primary, Lizzie.leelaz);
      assertSame(targetSecondary, Lizzie.leelaz2);
      assertEquals(0, EngineManager.currentEngineNo);
      assertEquals(2, EngineManager.currentEngineNo2);
      assertEquals(3, primary.enginePosition.get(), "primary mirror position");
      assertEquals(2, primary.clearBoardCount.get(), "mirror frozen route plus catch-up");
      assertEquals(2, targetSecondary.clearBoardCount.get(), "target frozen route plus catch-up");
      assertEquals(3, targetSecondary.enginePosition.get(), "secondary target position");
      assertEquals(1, primary.boardSynchronizationConfirmations, "primary mirror fence");
      assertEquals(1, targetSecondary.boardSynchronizationConfirmations, "secondary target fence");
      assertEngineMatchesBoard(primary, board, 19, 19);
      assertEngineMatchesBoard(targetSecondary, board, 19, 19);
      assertLifecycleReservationReleased(currentSecondary);
      assertLifecycleReservationReleased(targetSecondary);
    }
  }

  @Test
  void secondaryMirrorFenceFailureInvalidatesPrimaryUiAndRestoresCapturedOwners()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz oldPrimary = new StartupSyncLeelaz();
      StartupSyncLeelaz activePrimary = new StartupSyncLeelaz();
      StartupSyncLeelaz oldSecondary = new StartupSyncLeelaz();
      StartupSyncLeelaz targetSecondary = new StartupSyncLeelaz();
      oldPrimary.started = true;
      oldPrimary.isLoaded = true;
      oldSecondary.started = true;
      oldSecondary.isLoaded = true;
      activePrimary.normalQuitCompleted = new CountDownLatch(1);
      targetSecondary.normalQuitCompleted = new CountDownLatch(1);
      Lizzie.config.fastChange = true;
      Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Double_Engine;
      Lizzie.board = boardWithHistory(emptyRootHistory(2));
      Lizzie.leelaz = oldPrimary;
      Lizzie.leelaz2 = oldSecondary;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 2;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(
              new ArrayList<>(
                  List.of(oldPrimary, activePrimary, oldSecondary, targetSecondary)));
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(1, true));
      assertTrue(activePrimary.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      assertEquals(
          EngineManager.EngineSwitchUiPhase.ACTIVE,
          manager.engineSwitchUiSnapshot(true).phase());

      activePrimary.boardSynchronizationFailure = "controlled primary mirror fence failure";
      assertTrue(manager.switchEngineIfAvailable(3, false));
      assertTrue(targetSecondary.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.secondSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(
          activePrimary.normalQuitCompleted.await(2, TimeUnit.SECONDS),
          "the invalidated primary mirror shutdown must complete before its effects are asserted");
      assertTrue(
          targetSecondary.normalQuitCompleted.await(2, TimeUnit.SECONDS),
          "the failed secondary target shutdown must complete before its effects are asserted");

      assertEquals(
          EngineManager.EngineSwitchUiPhase.FAILED,
          manager.engineSwitchUiSnapshot(false).phase());
      assertEquals(
          EngineManager.EngineSwitchUiPhase.FAILED,
          manager.engineSwitchUiSnapshot(true).phase(),
          "the unavailable primary mirror must not retain an ACTIVE/playing snapshot");
      assertSame(oldPrimary, Lizzie.leelaz);
      assertEquals(0, EngineManager.currentEngineNo);
      assertSame(oldSecondary, Lizzie.leelaz2);
      assertEquals(2, EngineManager.currentEngineNo2);
      assertEquals(1, activePrimary.normalQuitCount);
      assertEquals(1, targetSecondary.normalQuitCount);
      assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);
      assertLifecycleReservationReleased(activePrimary);
      assertLifecycleReservationReleased(targetSecondary);
    }
  }

  @Test
  void secondaryTargetAsyncAckMirrorFenceSetupErrorReleasesLifecycle() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz oldPrimary = new StartupSyncLeelaz();
      StartupSyncLeelaz activePrimary = new StartupSyncLeelaz();
      StartupSyncLeelaz oldSecondary = new StartupSyncLeelaz();
      StartupSyncLeelaz targetSecondary = new StartupSyncLeelaz();
      oldPrimary.started = true;
      oldPrimary.isLoaded = true;
      oldSecondary.started = true;
      oldSecondary.isLoaded = true;
      activePrimary.normalQuitCompleted = new CountDownLatch(1);
      targetSecondary.delayReadyAfterStart = true;
      targetSecondary.confirmBoardSynchronizationOnDedicatedThread = true;
      targetSecondary.boardSynchronizationGate = new CountDownLatch(1);
      targetSecondary.normalQuitCompleted = new CountDownLatch(1);
      Lizzie.config.fastChange = true;
      Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Double_Engine;
      Lizzie.board = boardWithHistory(emptyRootHistory(2));
      Lizzie.leelaz = oldPrimary;
      Lizzie.leelaz2 = oldSecondary;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 2;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(
              new ArrayList<>(
                  List.of(oldPrimary, activePrimary, oldSecondary, targetSecondary)));
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(1, true));
      assertTrue(activePrimary.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      long primaryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while ((manager.engineSwitchUiSnapshot(true).phase()
                  != EngineManager.EngineSwitchUiPhase.ACTIVE
              || managerAtomicReferenceValue(manager, "engineSwitchTransaction") != null)
          && System.nanoTime() < primaryDeadline) {
        Thread.sleep(10L);
      }
      activePrimary.boardSynchronizationPostRegistrationError =
          new AssertionError("controlled post-registration mirror fence setup error");

      assertTrue(manager.switchEngineIfAvailable(3, false));
      assertTrue(targetSecondary.startCompleted.await(2, TimeUnit.SECONDS));
      targetSecondary.publishReady();
      assertTrue(
          targetSecondary.boardSynchronizationCallbackEntered.await(2, TimeUnit.SECONDS),
          "the target ACK must enter an asynchronous callback before mirror setup");
      assertTrue(
          manager.secondSynchronizationCompleted.await(2, TimeUnit.SECONDS),
          "the outer synchronization call must return before the asynchronous target ACK");
      targetSecondary.boardSynchronizationGate.countDown();

      assertTrue(
          targetSecondary.boardSynchronizationCallbackCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(
          manager.synchronizationFailed.await(2, TimeUnit.SECONDS),
          "mirror setup Error must enter the ordinary lifecycle failure path");
      assertTrue(activePrimary.normalQuitCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(targetSecondary.normalQuitCompleted.await(2, TimeUnit.SECONDS));
      long settlementDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (managerAtomicReferenceValue(manager, "engineSwitchTransaction") != null
          && System.nanoTime() < settlementDeadline) {
        Thread.sleep(10L);
      }

      assertEquals(
          EngineManager.EngineSwitchUiPhase.FAILED,
          manager.engineSwitchUiSnapshot(false).phase());
      assertEquals(
          EngineManager.EngineSwitchUiPhase.FAILED,
          manager.engineSwitchUiSnapshot(true).phase());
      assertSame(oldPrimary, Lizzie.leelaz);
      assertEquals(0, EngineManager.currentEngineNo);
      assertSame(oldSecondary, Lizzie.leelaz2);
      assertEquals(2, EngineManager.currentEngineNo2);
      assertEquals(1, activePrimary.normalQuitCount);
      assertEquals(1, targetSecondary.normalQuitCount);
      assertEquals(1, manager.synchronizationFailureCount);
      assertNull(managerAtomicReferenceValue(manager, "engineSwitchTransaction"));
      activePrimary.publishLateBoardSynchronizationSuccess();
      assertEquals(
          1,
          manager.synchronizationFailureCount,
          "a late mirror ACK must not settle the already-failed lifecycle a second time");
      assertEquals(1, activePrimary.normalQuitCount);
      assertEquals(1, targetSecondary.normalQuitCount);
      assertEquals(
          EngineManager.EngineSwitchUiPhase.FAILED,
          manager.engineSwitchUiSnapshot(false).phase());
      assertLifecycleReservationReleased(activePrimary);
      assertLifecycleReservationReleased(targetSecondary);
    }
  }

  @Test
  void stalePrimaryFailureCannotAttachToNewSecondaryTransactionByMirrorIdentityAlone()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz oldPrimary = new StartupSyncLeelaz();
      StartupSyncLeelaz activePrimary = new StartupSyncLeelaz();
      StartupSyncLeelaz oldSecondary = new StartupSyncLeelaz();
      StartupSyncLeelaz targetSecondary = new StartupSyncLeelaz();
      oldPrimary.started = true;
      oldPrimary.isLoaded = true;
      oldSecondary.started = true;
      oldSecondary.isLoaded = true;
      targetSecondary.delayReadyAfterStart = true;
      Lizzie.config.fastChange = true;
      Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Double_Engine;
      Lizzie.board = boardWithHistory(emptyRootHistory(1));
      Lizzie.leelaz = oldPrimary;
      Lizzie.leelaz2 = oldSecondary;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 2;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(
              new ArrayList<>(
                  List.of(oldPrimary, activePrimary, oldSecondary, targetSecondary)));
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(1, true));
      assertTrue(activePrimary.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      long capturedMirrorToken = manager.engineSwitchUiSnapshot(true).token();

      assertTrue(manager.switchEngineIfAvailable(3, false));
      assertTrue(targetSecondary.startCompleted.await(2, TimeUnit.SECONDS));

      Field trackerField = EngineManager.class.getDeclaredField("engineSwitchUiTracker");
      trackerField.setAccessible(true);
      EngineManager.EngineSwitchUiTracker tracker =
          (EngineManager.EngineSwitchUiTracker) trackerField.get(manager);
      EngineManager.EngineSwitchUiSnapshot newerSwitch =
          tracker.begin(
              true,
              1,
              "active-primary",
              activePrimary,
              1,
              "active-primary",
              activePrimary);
      EngineManager.EngineSwitchUiSnapshot newerActive =
          tracker
              .succeed(
                  newerSwitch.token(), true, 1, "active-primary", activePrimary)
              .orElseThrow();
      assertTrue(newerActive.token() > capturedMirrorToken);

      Method failUi =
          EngineManager.class.getDeclaredMethod(
              "failEngineSwitchUi", long.class, boolean.class, String.class);
      failUi.setAccessible(true);
      failUi.invoke(manager, newerActive.token(), true, "controlled stale primary failure");

      assertSame(activePrimary, Lizzie.leelaz);
      assertEquals(1, EngineManager.currentEngineNo);
      assertEquals(0, activePrimary.normalQuitCount);

      targetSecondary.publishReady();
      assertTrue(manager.secondSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      assertSame(activePrimary, Lizzie.leelaz);
      assertSame(targetSecondary, Lizzie.leelaz2);
    }
  }

  @Test
  void secondaryRestartConvergesTargetAndPrimaryMirrorAfterDelayedReadyAndNavigation()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz primary = new StartupSyncLeelaz();
      StartupSyncLeelaz secondary = new StartupSyncLeelaz();
      primary.started = true;
      primary.isLoaded = true;
      primary.Pondering();
      secondary.started = true;
      secondary.isLoaded = true;
      secondary.delayReadyAfterStart = true;
      Lizzie.config.fastChange = true;
      Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Double_Engine;
      BoardHistoryList history = emptyRootHistory(5);
      history.toStart();
      Board board = boardWithHistory(history);
      Lizzie.board = board;
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 1;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(primary, secondary)));
      Lizzie.engineManager = manager;

      manager.reStartEngine2();
      assertTrue(secondary.startCompleted.await(2, TimeUnit.SECONDS));
      navigateZeroToFiveToThree(board);
      secondary.publishReady();

      assertTrue(primary.analysisStarted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      assertEquals(3, primary.enginePosition.get(), "primary mirror position");
      assertEquals(3, secondary.enginePosition.get(), "secondary restart target position");
      assertEquals(2, primary.clearBoardCount.get(), "mirror frozen route plus catch-up");
      assertEquals(2, secondary.clearBoardCount.get(), "target frozen route plus catch-up");
      assertEquals(3, primary.analyzePosition(), "primary resumed analysis position");
      assertEquals(1, primary.boardSynchronizationConfirmations, "primary mirror fence");
      assertEquals(1, secondary.boardSynchronizationConfirmations, "secondary target fence");
      assertFenceBeforeAnalyze(primary);
      assertEngineMatchesBoard(primary, board, 19, 19);
      assertEngineMatchesBoard(secondary, board, 19, 19);
      assertLifecycleReservationReleased(secondary);
    }
  }

  @Test
  void primarySwitchReadyTimeoutFailsClosed() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz current = new StartupSyncLeelaz();
      StartupSyncLeelaz target = new StartupSyncLeelaz();
      current.started = true;
      current.isLoaded = true;
      target.delayReadyAfterStart = true;
      Board board = boardWithHistory(emptyRootHistory(3));
      Lizzie.board = board;
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(current, target)));
      manager.timeoutMillis = 10L;
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(1, true));

      assertTrue(manager.synchronizationFailed.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      EngineManager.EngineSwitchUiSnapshot failedSwitch = manager.engineSwitchUiSnapshot(true);
      assertEquals(EngineManager.EngineSwitchUiPhase.FAILED, failedSwitch.phase());
      assertEquals(0, failedSwitch.activeIndex(), "timeout must preserve the committed engine");
      assertEquals(1, failedSwitch.targetIndex(), "failure still identifies the rejected target");
      assertFalse(target.isLoaded(), "timed-out switch target remains unavailable");
      assertEquals(0, target.ponderCount, "no analysis after readiness timeout");
      assertLifecycleReservationReleased(current);
      assertLifecycleReservationReleased(target);
    }
  }

  @Test
  void primarySwitchFinalBoardFenceFailureReplacesReadyWithFailedState() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz current = new StartupSyncLeelaz();
      StartupSyncLeelaz target = new StartupSyncLeelaz();
      current.started = true;
      current.isLoaded = true;
      target.delayReadyAfterStart = true;
      target.boardSynchronizationFailure = "controlled final board fence failure";
      Lizzie.board = boardWithHistory(emptyRootHistory(3));
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(current, target)));
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(1, true));
      assertTrue(target.startCompleted.await(2, TimeUnit.SECONDS));
      assertEquals(
          EngineManager.EngineSwitchUiPhase.SWITCHING,
          manager.engineSwitchUiSnapshot(true).phase());
      target.publishReady();

      assertTrue(manager.synchronizationFailed.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      EngineManager.EngineSwitchUiSnapshot failedSwitch = manager.engineSwitchUiSnapshot(true);
      assertEquals(EngineManager.EngineSwitchUiPhase.FAILED, failedSwitch.phase());
      assertEquals(0, failedSwitch.activeIndex(), "the last committed engine remains recorded");
      assertEquals(1, failedSwitch.targetIndex());
      assertFalse(target.isLoaded(), "the failed final fence invalidates the requested target");
      assertLifecycleReservationReleased(current);
      assertLifecycleReservationReleased(target);
    }
  }

  @Test
  void primaryRestartReservationConflictFailsClosed() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.started = true;
      engine.isLoaded = true;
      engine.rejectReservation = true;
      Lizzie.board = boardWithHistory(emptyRootHistory(3));
      Lizzie.leelaz = engine;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(engine)));
      Lizzie.engineManager = manager;

      manager.reStartEngine(0);

      assertEquals(1, manager.leaseConflictCount);
      assertEquals(0, engine.ponderCount);
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void primarySwitchCatchUpRestoreFailureFailsClosed() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz current = new StartupSyncLeelaz();
      StartupSyncLeelaz target = new StartupSyncLeelaz();
      current.started = true;
      current.isLoaded = true;
      current.boardSynchronizationFailure = "controlled rollback-owner ACK failure";
      target.delayReadyAfterStart = true;
      AtomicInteger clearBoardCommands = new AtomicInteger();
      target.beforeCommand =
          command -> {
            if (command.equals("clear_board") && clearBoardCommands.incrementAndGet() == 2) {
              throw new IllegalStateException("controlled catch-up restore failure");
            }
          };
      BoardHistoryList history = emptyRootHistory(5);
      history.toStart();
      Board board = boardWithHistory(history);
      Lizzie.board = board;
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(current, target)));
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(1, true));
      assertTrue(target.startCompleted.await(2, TimeUnit.SECONDS));
      navigateZeroToFiveToThree(board);
      target.publishReady();

      assertTrue(manager.synchronizationFailed.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      assertFalse(target.isLoaded(), "failed catch-up target remains unavailable");
      assertEquals(0, target.ponderCount, "no analysis after catch-up failure");
      assertEquals(0, target.analyzeCount(), "no analyze command after catch-up failure");
      assertEquals(null, Lizzie.leelaz, "failed rollback resync must stay fail-closed");
      assertTrue(EngineManager.isEmpty);
      assertEquals(-1, EngineManager.currentEngineNo);
      assertFalse(current.isLoaded());
      assertEquals(EngineStartupStatus.State.START_FAILED, Lizzie.engineStartupStatus.snapshot().state);
      assertLifecycleReservationReleased(current);
      assertLifecycleReservationReleased(target);
    }
  }

  @Test
  void rollbackOwnerFinalInitializationFailureClearsCommittedPrimaryAndReleasesAllOwners()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz previous = new StartupSyncLeelaz();
      StartupSyncLeelaz target = new StartupSyncLeelaz();
      previous.started = true;
      previous.isLoaded = true;
      previous.Pondering();
      previous.ponderFailure =
          new IllegalStateException("controlled rollback-owner final initialization failure");
      previous.normalQuitEntered = new CountDownLatch(1);
      previous.normalQuitGate = new CountDownLatch(1);
      previous.normalQuitCompleted = new CountDownLatch(1);
      target.delayReadyAfterStart = true;
      target.boardSynchronizationFailure = "controlled target final-fence failure";
      Lizzie.config.fastChange = true;
      Lizzie.board = boardWithHistory(emptyRootHistory(3));
      Lizzie.setPrimaryEngine(previous);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(previous, target)));
      Lizzie.engineManager = manager;
      long settlementDeadline;
      try {
        assertTrue(manager.switchEngineIfAvailable(1, true));
        assertTrue(target.startCompleted.await(2, TimeUnit.SECONDS));
        target.publishReady();

        assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
        assertTrue(previous.normalQuitEntered.await(2, TimeUnit.SECONDS));
        settlementDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (managerAtomicReferenceValue(manager, "failedRollbackRecovery") != null
            && System.nanoTime() < settlementDeadline) {
          Thread.sleep(10L);
        }
        assertNull(
            managerAtomicReferenceValue(manager, "failedRollbackRecovery"),
            "the failed recovery barrier must clear before testing its retired capability");

        Object failedBinding = previous.analysisReaderBindingForTest();
        Object failedRecoveryToken = previous.analysisOutputRecoveryTokenForTest();
        EngineManager.TransactionlessAnalysisWriteLease unexpectedLease = null;
        try {
          assertNotNull(failedRecoveryToken);
          assertTrue(previous.suppressesGlobalEnginePresentation(failedBinding));
          assertEquals("EXACT_RETIRED", previous.analysisOutputRouteForTest());
          unexpectedLease =
              EngineManager.claimTransactionlessAnalysisWrite(
                  previous, failedBinding, failedRecoveryToken);
          assertNull(
              unexpectedLease,
              "a failed recovery capability must reject ordinary ownership after barrier clear");
        } finally {
          if (unexpectedLease != null) {
            unexpectedLease.close();
          }
        }
      } finally {
        previous.normalQuitGate.countDown();
      }
      assertTrue(previous.normalQuitCompleted.await(2, TimeUnit.SECONDS));
      settlementDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while ((Lizzie.leelaz != null
              || managerAtomicReferenceValue(manager, "engineSwitchTransaction") != null)
          && System.nanoTime() < settlementDeadline) {
        Thread.sleep(10L);
      }

      assertNull(Lizzie.leelaz, "the failed rollback owner must not remain routable");
      assertTrue(EngineManager.isEmpty);
      assertEquals(-1, EngineManager.currentEngineNo);
      assertFalse(previous.isLoaded());
      assertEquals(1, previous.ponderCount, "rollback final initialization runs only once");
      assertEquals(1, previous.boardSynchronizationConfirmations, "no duplicate claim callback");
      assertEquals(1, previous.normalQuitCount, "the failed rollback runtime is stopped once");
      assertEquals(2, manager.synchronizationFailureCount);
      assertEquals(
          EngineStartupStatus.State.START_FAILED,
          Lizzie.engineStartupStatus.snapshot().state);
      assertEquals(
          EngineManager.EngineSwitchUiPhase.FAILED,
          manager.engineSwitchUiSnapshot(true).phase());
      assertNull(managerAtomicReferenceValue(manager, "failedRollbackRecovery"));
      assertNull(managerAtomicReferenceValue(manager, "engineSwitchTransaction"));
      assertLifecycleReservationReleased(previous);
      assertLifecycleReservationReleased(target);
    }
  }

  @Test
  void rollbackFenceFailureStopsQuarantinedEngineOffFenceCallbackThread() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz previous = new StartupSyncLeelaz();
      StartupSyncLeelaz target = new StartupSyncLeelaz();
      previous.started = true;
      previous.isLoaded = true;
      previous.Pondering();
      previous.boardSynchronizationFailure = "controlled rollback fence failure";
      previous.confirmBoardSynchronizationOnDedicatedThread = true;
      previous.normalQuitEntered = new CountDownLatch(1);
      previous.normalQuitGate = previous.boardSynchronizationCallbackCompleted;
      previous.normalQuitCompleted = new CountDownLatch(1);
      target.delayReadyAfterStart = true;
      target.boardSynchronizationFailure = "controlled target final-fence failure";
      Lizzie.config.fastChange = true;
      Lizzie.board = boardWithHistory(emptyRootHistory(3));
      Lizzie.setPrimaryEngine(previous);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(previous, target)));
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(1, true));
      assertTrue(target.startCompleted.await(2, TimeUnit.SECONDS));
      target.publishReady();

      assertTrue(
          previous.boardSynchronizationEntered.await(2, TimeUnit.SECONDS),
          "rollback recovery must reach the previous engine's final fence");
      assertTrue(
          previous.boardSynchronizationCallbackCompleted.await(2, TimeUnit.SECONDS),
          "the fence callback must return before blocking process shutdown completes");
      assertTrue(previous.normalQuitEntered.await(2, TimeUnit.SECONDS));
      assertTrue(previous.normalQuitCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      long settlementDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while ((managerAtomicReferenceValue(manager, "failedRollbackRecovery") != null
              || managerAtomicReferenceValue(manager, "engineSwitchTransaction") != null)
          && System.nanoTime() < settlementDeadline) {
        Thread.sleep(10L);
      }

      assertNotNull(previous.boardSynchronizationCallbackThread);
      assertNotNull(previous.normalQuitThread);
      assertFalse(
          previous.boardSynchronizationCallbackThread == previous.normalQuitThread,
          "normalQuit must never run on the GTP fence callback thread");
      assertNull(Lizzie.leelaz, "a failed rollback fence must leave no routable primary");
      assertTrue(EngineManager.isEmpty);
      assertEquals(-1, EngineManager.currentEngineNo);
      assertFalse(previous.isLoaded);
      assertEquals(1, previous.normalQuitCount);
      assertEquals(
          EngineStartupStatus.State.START_FAILED,
          Lizzie.engineStartupStatus.snapshot().state);
      assertEquals(
          EngineManager.EngineSwitchUiPhase.FAILED,
          manager.engineSwitchUiSnapshot(true).phase());
      assertNull(managerAtomicReferenceValue(manager, "failedRollbackRecovery"));
      assertNull(managerAtomicReferenceValue(manager, "engineSwitchTransaction"));
      assertLifecycleReservationReleased(previous);
      assertLifecycleReservationReleased(target);
    }
  }

  @Test
  void rejectedContributingSwitchOnlyCleansUpLifecycleOwner() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz current = new StartupSyncLeelaz();
      StartupSyncLeelaz target = new StartupSyncLeelaz();
      current.started = true;
      current.isLoaded = true;
      current.Pondering();
      target.started = true;
      target.isLoaded = true;
      Lizzie.board = boardWithHistory(emptyRootHistory(3));
      Lizzie.leelaz = current;
      Lizzie.frame.isContributing = true;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(current, target)));
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(1, true));

      assertSame(current, Lizzie.leelaz, "rejected switch keeps the current engine");
      assertEquals(0, target.boardSynchronizationConfirmations, "no success fence on abort");
      assertEquals(0, target.ponderCount, "aborted target never starts analysis");
      assertLifecycleReservationReleased(current);
      assertLifecycleReservationReleased(target);
    }
  }

  private static void navigateZeroToFiveToThree(Board board) {
    for (int move = 1; move <= 5; move++) {
      assertTrue(board.nextMove(false), "navigation must reach move " + move);
    }
    assertTrue(board.previousMove(false), "navigation must return to move 4");
    assertTrue(board.previousMove(false), "navigation must return to move 3");
    assertTrue(board.goToMoveNumber(1), "jump must return to move 1");
    assertTrue(board.goToMoveNumber(3), "jump must return to final move 3");
  }

  private static void navigateZeroToEightToNineAndBack(Board board) {
    for (int move = 1; move <= 8; move++) {
      assertTrue(board.nextMove(false), "navigation must reach move " + move);
    }
    assertTrue(board.nextMove(false), "navigation must reach move 9");
    assertTrue(board.previousMove(false), "navigation must return to move 8");
  }

  private static void assertFenceBeforeAnalyze(StartupSyncLeelaz engine) {
    assertTrue(engine.lifecycleEvents.indexOf("fence") >= 0, "fence event");
    assertTrue(
        engine.lifecycleEvents.indexOf("fence") < engine.lifecycleEvents.indexOf("analyze"),
        "analysis starts only after the final response fence");
  }

  @Test
  void foregroundActivationSnapshotNavigationConvergesWithRemovedStoneTailBranchAndJump()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.delayReadyAfterStart = true;
      engine.snapshotBaseMove = 2;
      BoardHistoryList history = loadRemovedStoneSnapshotFixture();
      history.toStart();
      Board board = boardWithHistory(history);
      Lizzie.board = board;
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(engine)));
      Lizzie.engineManager = manager;
      env.expectedReadyEngineIndex = 0;

      assertTrue(manager.switchEngineIfAvailable(0, true));
      assertTrue(engine.startCompleted.await(2, TimeUnit.SECONDS), "engine startup must begin");

      // 前进 through the mid-history SNAPSHOT and the real tail: root -> B dd -> W ee ->
      // SNAPSHOT(AB fd / AW ff / AE dd / PL W) -> W hh -> B pass
      for (int step = 1; step <= 5; step++) {
        assertTrue(board.nextMove(false), "forward navigation must reach step " + step);
      }
      // 后退 back onto the tail MOVE
      assertTrue(board.previousMove(false), "backward navigation must return to the tail MOVE");
      // 分支: enter a variation child off the tail MOVE
      BoardHistoryNode tailMoveNode = board.getHistory().getCurrentHistoryNode();
      tailMoveNode.addOrGoto(
          moveNode(tailMoveNode.getData(), 8, 8, Stone.WHITE, true, 4), true, false, false);
      assertTrue(board.nextVariation(1), "navigation must enter the branch child");
      // 跳转: jump back to the tail MOVE, then forward to the main-line tail PASS
      assertTrue(board.goToMoveNumber(3), "jump must return to the tail MOVE");
      assertTrue(board.goToMoveNumber(4), "jump must reach the main-line tail PASS");
      engine.publishReady();

      assertTrue(
          engine.analysisStarted.await(2, TimeUnit.SECONDS),
          "analysis must start after the production activation converges");
      assertTrue(
          manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS),
          "production synchronization worker must complete before assertions");
      assertTrue(
          env.readyObservedCommittedOwner.get(),
          "READY observers must see the committed foreground owner");

      assertEquals(4, board.getHistory().getMoveNumber(), "history cursor at the tail PASS");
      assertSame(history.getEnd(), board.getHistory().getCurrentHistoryNode());
      assertRemovedStoneSnapshotShape(history);
      assertEquals(
          Stone.EMPTY,
          board.getHistory().getData().stones[Board.getIndex(3, 3)],
          "the AE-removed stone must stay removed on the board");
      assertEquals(1, engine.loadSgfCount.get(), "only the exact catch-up route uses loadsgf");
      assertEquals(1, engine.clearBoardCount.get(), "the frozen route is a root replay");
      assertEquals(4, engine.enginePosition.get(), "engine position");
      assertEquals(4, engine.analyzePosition(), "analysis position");
      assertEquals(1, engine.ponderCount, "analysis starts once at the stable position");
      assertEquals(
          List.of("play W " + Board.convertCoordinatesToName(7, 7), "play B pass"),
          engine.playsAfterLastLoadSgf(),
          "the final catch-up route must replay only the real SNAPSHOT tail");
      assertFalse(
          engine.containsCommand("play B " + Board.convertCoordinatesToName(3, 3)),
          "the AE-removed stone must not be faked as a MOVE command");
      assertFalse(
          engine.containsCommand("play B " + Board.convertCoordinatesToName(5, 3)),
          "snapshot static stones must not be faked as MOVE commands");
      assertFalse(
          engine.containsCommand("play W " + Board.convertCoordinatesToName(5, 5)),
          "snapshot AW static stones must not be faked as MOVE commands");
      assertFalse(
          engine.containsCommand("play W " + Board.convertCoordinatesToName(4, 4)),
          "snapshot static stones must not be faked as MOVE commands");
      assertEngineMatchesBoard(engine, board, 19, 19);
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void foregroundActivationSnapshotExactRestoreKeepsLoadsgfTailThenFenceOrder() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.delayReadyAfterStart = true;
      engine.snapshotBaseMove = 2;
      // Capture at the tail PASS so the frozen route itself carries the full real tail.
      BoardHistoryList history = loadRemovedStoneSnapshotFixture();
      history.setHead(history.getEnd());
      Board board = boardWithHistory(history);
      Lizzie.board = board;
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(engine)));
      Lizzie.engineManager = manager;
      env.expectedReadyEngineIndex = 0;

      assertTrue(manager.switchEngineIfAvailable(0, true));
      assertTrue(engine.startCompleted.await(2, TimeUnit.SECONDS), "engine startup must begin");
      engine.publishReady();

      assertTrue(
          engine.analysisStarted.await(2, TimeUnit.SECONDS),
          "analysis must start after the production activation converges");
      assertTrue(
          manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS),
          "production synchronization worker must complete before assertions");

      assertEquals(
          1, engine.loadSgfCount.get(), "no navigation must execute only the frozen route");
      assertEquals(
          List.of("play W " + Board.convertCoordinatesToName(7, 7), "play B pass"),
          engine.playsAfterLastLoadSgf(),
          "the frozen route must send loadsgf first, then the real tail");
      assertEquals(
          List.of("play W " + Board.convertCoordinatesToName(7, 7), "play B pass"),
          engine.tailPlays(),
          "the tail must contain only the real MOVE/PASS after the snapshot");
      String sgf = engine.loadedSgfContent(0);
      assertTrue(sgf.contains("PL[W]"), "materialized snapshot SGF must carry the explicit PL");
      assertTrue(sgf.contains("KM[6.5]"), "materialized snapshot SGF must carry the game komi");
      assertTrue(sgf.contains("SZ[19]"), "materialized snapshot SGF must carry the board size");
      assertTrue(
          sgf.contains("AB[" + sgfCoord(5, 3) + "]"),
          "materialized snapshot SGF must carry the static black stones");
      assertTrue(
          sgf.contains("AW[" + sgfCoord(4, 4) + "]"),
          "materialized snapshot SGF must carry the inherited white stone");
      assertTrue(
          sgf.contains("AW[" + sgfCoord(5, 5) + "]"),
          "materialized snapshot SGF must carry the AW setup white stone");
      assertFalse(
          sgf.contains("AB[" + sgfCoord(3, 3) + "]"),
          "the AE-removed stone must not be re-materialized");
      assertFalse(
          engine.containsCommand("play B " + Board.convertCoordinatesToName(3, 3)),
          "the snapshot anchor must not be replayed as a MOVE");
      assertFalse(
          engine.containsCommand("play W " + Board.convertCoordinatesToName(4, 4)),
          "the inherited white stone must not be replayed as a MOVE");
      assertFalse(
          engine.containsCommand("play W " + Board.convertCoordinatesToName(5, 5)),
          "the AW setup stone must not be replayed as a MOVE");
      assertEquals(4, engine.enginePosition.get(), "engine position after the tail");
      assertEquals(4, engine.analyzePosition(), "analysis position");
      assertEquals(1, engine.ponderCount, "analysis starts once after the stable restore point");
      assertEquals(Stone.EMPTY, engine.stoneAt(3, 3), "removed stone stays removed on the engine");
      assertEngineMatchesBoard(engine, board, 19, 19);
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void snapshotExactRouteKeepsTailBeforeFenceThenAnalyze() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.snapshotBaseMove = 2;
      BoardHistoryList history = loadRemovedStoneSnapshotFixture();
      history.setHead(history.getEnd());
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicReference<String> lastCommandBeforeFence = new AtomicReference<>();
      AtomicBoolean ordinaryPlayDroppedAtTail = new AtomicBoolean();
      startup.beforeReservationRelease =
          () -> {
            // The exact route (loadsgf + real tail) has just been committed; ordinary live-board
            // play must still be dropped and the last committed command must be the final tail.
            List<String> commands = engine.commands;
            if (!commands.isEmpty()) {
              lastCommandBeforeFence.set(commands.get(commands.size() - 1));
            }
            int before = commands.size();
            engine.sendCommand("play B Q16");
            ordinaryPlayDroppedAtTail.set(
                engine.commands.size() == before && !engine.commands.contains("play B Q16"));
          };

      runStartupInThread(startup, engine);

      assertTrue(
          ordinaryPlayDroppedAtTail.get(),
          "ordinary play must stay dropped after the tail and before reservation release");
      assertEquals(
          "play B pass",
          lastCommandBeforeFence.get(),
          "the final tail action must be the last command before the fence");
      assertEquals(4, engine.analyzePosition(), "analyze must start after the fence");
      assertEquals(1, engine.ponderCount, "ponder must run exactly once after the fence");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void snapshotNavigationAndOrdinarySyncDuringBarrierNeverThrowAdmissionException()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.snapshotBaseMove = 2;
      BoardHistoryList admissionHistory = loadRemovedStoneSnapshotFixture();
      admissionHistory.toStart();
      Board board = boardWithHistory(admissionHistory);
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      AtomicReference<Throwable> navigationFailure = new AtomicReference<>();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() == 0) {
              try {
                int commandsBefore = engine.commands.size();
                // H3 stepIn admission path on the removed-stone snapshot anchor itself.
                BoardHistoryNode snapshotNode =
                    board.getHistory().getStart().next().orElseThrow().next().orElseThrow()
                        .next().orElseThrow();
                snapshotNode.clearAndSyncBoard(true);
                // Forward over the SNAPSHOT and the real tail with refresh.
                for (int step = 1; step <= 5; step++) {
                  assertTrue(board.nextMove(true), "forward navigation must reach step " + step);
                }
                // Backward onto the tail MOVE, then variation navigation.
                assertTrue(board.previousMove(true));
                BoardHistoryNode tailMove = board.getHistory().getCurrentHistoryNode();
                tailMove.addOrGoto(
                    moveNode(tailMove.getData(), 8, 8, Stone.WHITE, true, 4), true, false, false);
                assertTrue(board.nextVariation(1));
                assertTrue(board.goToMoveNumber(3), "jump must return to the tail MOVE");
                assertTrue(board.goToMoveNumber(4), "jump must reach the main-line tail PASS");
                // Ordinary live-board sync must be refused by the barrier, not admitted.
                assertFalse(board.resendCurrentPositionToPrimaryEngine());
                assertFalse(
                    board.trySyncCurrentPositionToPrimaryEngineIncrementally(
                        board.getHistory().getData(), 19, 19));
                board.resendMoveToEngine(engine, false);
                assertEquals(
                    commandsBefore,
                    engine.commands.size(),
                    "ordinary live-board sync must not reach the engine while the barrier is active");
              } catch (Throwable failure) {
                navigationFailure.set(failure);
              }
            }
          };

      runStartupInThread(startup, engine);

      assertNull(
          navigationFailure.get(),
          "snapshot navigation and ordinary sync during the barrier must never throw");
      assertEquals(4, board.getHistory().getMoveNumber(), "history cursor at the tail PASS");
      assertSame(admissionHistory.getEnd(), board.getHistory().getCurrentHistoryNode());
      assertEquals(4, engine.analyzePosition(), "engine must converge on the main-line tail PASS");
      assertEquals(1, engine.ponderCount, "analysis waits for the stable restore point");
      assertEngineMatchesBoard(engine, board, 19, 19);
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void foregroundActivationSnapshotRestoreFailureFailsClosedAndRetryRecovers() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz failingEngine = new StartupSyncLeelaz();
      failingEngine.delayReadyAfterStart = true;
      failingEngine.snapshotBaseMove = 2;
      failingEngine.failLoadSgfAt = 1;
      StartupSyncLeelaz recoveryEngine = new StartupSyncLeelaz();
      // Capture at the tail PASS so the frozen route itself is an exact snapshot restore.
      BoardHistoryList history = loadRemovedStoneSnapshotFixture();
      history.setHead(history.getEnd());
      Lizzie.board = boardWithHistory(history);
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(failingEngine, recoveryEngine)));
      Lizzie.engineManager = manager;
      int readyBaseline = env.readyTransitions.get();

      assertTrue(manager.switchEngineIfAvailable(0, true));
      assertTrue(failingEngine.startCompleted.await(2, TimeUnit.SECONDS));
      failingEngine.publishReady();
      assertTrue(manager.synchronizationFailed.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));

      assertTrue(EngineManager.isEmpty, "failed snapshot restore must remain an empty owner");
      assertEquals(-1, EngineManager.currentEngineNo);
      assertFalse(failingEngine.isLoaded);
      assertEquals(1, failingEngine.loadSgfCount.get(), "only the failed loadsgf attempt");
      assertEquals(0, failingEngine.ponderCount, "no analysis after a failed snapshot restore");
      assertEquals(0, failingEngine.analyzeCount(), "no analyze command after a failed restore");
      assertEquals(
          0,
          env.readyTransitions.get() - readyBaseline,
          "never marked ready on restore failure");
      assertLifecycleReservationReleased(failingEngine);

      assertTrue(manager.switchEngineIfAvailable(1, true), "a later activation must retry startup");
      assertTrue(recoveryEngine.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(recoveryEngine.analysisStarted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.secondSynchronizationCompleted.await(2, TimeUnit.SECONDS));

      assertFalse(EngineManager.isEmpty);
      assertEquals(1, EngineManager.currentEngineNo);
      assertLifecycleReservationReleased(recoveryEngine);
    }
  }

  @Test
  void foregroundActivationSnapshotCatchUpRestoreFailureFailsClosed() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.delayReadyAfterStart = true;
      engine.snapshotBaseMove = 2;
      engine.failLoadSgfAt = 2;
      // Capture at the tail PASS (frozen exact route, loadsgf #1), then navigate back onto the
      // removed-stone SNAPSHOT anchor (catch-up exact route, loadsgf #2 which fails).
      BoardHistoryList history = loadRemovedStoneSnapshotFixture();
      history.setHead(history.getEnd());
      Board board = boardWithHistory(history);
      Lizzie.board = board;
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(engine)));
      Lizzie.engineManager = manager;
      int readyBaseline = env.readyTransitions.get();

      assertTrue(manager.switchEngineIfAvailable(0, true));
      assertTrue(engine.startCompleted.await(2, TimeUnit.SECONDS));
      // 后退 onto the mid-history SNAPSHOT while the engine is not ready.
      assertTrue(board.previousMove(false));
      assertTrue(board.previousMove(false));
      engine.publishReady();

      assertTrue(manager.synchronizationFailed.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));

      assertEquals(2, engine.loadSgfCount.get(), "frozen route then failed catch-up route");
      assertTrue(EngineManager.isEmpty, "failed snapshot catch-up must remain an empty owner");
      assertEquals(-1, EngineManager.currentEngineNo);
      assertFalse(engine.isLoaded);
      assertEquals(0, engine.ponderCount, "no analysis after a failed catch-up restore");
      assertEquals(0, engine.analyzeCount(), "no analyze command after a failed catch-up");
      assertEquals(
          0,
          env.readyTransitions.get() - readyBaseline,
          "never marked ready on catch-up failure");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void snapshotCatchUpReservationReacquisitionFailureFailsClosed() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.snapshotBaseMove = 2;
      BoardHistoryList history = loadRemovedStoneSnapshotFixture();
      history.setHead(history.getEnd());
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicReference<Leelaz.ExclusiveGtpLifecycleReservation> conflictingReservation =
          new AtomicReference<>();
      AtomicInteger releases = new AtomicInteger();
      startup.afterReservationRelease =
          () -> {
            if (releases.getAndIncrement() == 0) {
              // Navigate back onto the removed-stone SNAPSHOT anchor so a catch-up would be
              // needed, then occupy the lifecycle reservation.
              assertTrue(board.previousMove(false));
              assertTrue(board.previousMove(false));
              conflictingReservation.set(
                  engine.beginExclusiveGtpLifecycleReservation(new Object()));
              assertNotNull(
                  conflictingReservation.get(),
                  "fixture must occupy the lifecycle before catch-up reacquisition");
            }
          };
      int readyBaseline = env.readyTransitions.get();

      RuntimeException failure = runStartupExpectingFailure(startup, engine);
      Leelaz.ExclusiveGtpLifecycleReservation blocker = conflictingReservation.getAndSet(null);
      if (blocker != null) {
        blocker.close();
      }

      assertNotNull(failure, "rejected snapshot catch-up reservation must fail closed");
      assertFalse(engine.isLoaded);
      assertEquals(0, engine.ponderCount);
      assertEquals(0, engine.analyzeCount());
      assertEquals(0, env.readyTransitions.get() - readyBaseline);
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void foregroundActivationCaptureFailureIsNotReportedAsLeaseConflict() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.reservationFailure = new IllegalStateException("controlled capture failure");
      BoardHistoryList history = emptyRootHistory(1);
      history.toStart();
      Lizzie.board = boardWithHistory(history);
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(engine)));
      Lizzie.engineManager = manager;

      assertFalse(manager.switchEngineIfAvailable(0, true));

      assertEquals(1, manager.synchronizationFailureCount);
      assertEquals(0, manager.leaseConflictCount);
      assertFalse(engine.isLoaded);
      assertTrue(EngineManager.isEmpty);
      assertEquals(-1, EngineManager.currentEngineNo);
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void foregroundActivationReadyTimeoutPreservesEmptyStateAndCanRetry() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz timedOutEngine = new StartupSyncLeelaz();
      timedOutEngine.delayReadyAfterStart = true;
      StartupSyncLeelaz recoveryEngine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(3);
      history.toStart();
      Lizzie.board = boardWithHistory(history);
      Lizzie.leelaz = null;
      Lizzie.config.fastChange = true;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(
              new ArrayList<>(List.of(timedOutEngine, recoveryEngine)));
      manager.timeoutMillis = 25L;
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(0, true));
      assertTrue(timedOutEngine.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.synchronizationFailed.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));

      assertTrue(EngineManager.isEmpty, "failed first activation must remain an empty owner");
      assertEquals(-1, EngineManager.currentEngineNo);
      assertFalse(timedOutEngine.isLoaded);
      assertLifecycleReservationReleased(timedOutEngine);

      assertTrue(manager.switchEngineIfAvailable(1, true), "a later activation must retry startup");
      assertTrue(recoveryEngine.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(recoveryEngine.analysisStarted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.secondSynchronizationCompleted.await(2, TimeUnit.SECONDS));

      assertFalse(EngineManager.isEmpty);
      assertEquals(1, EngineManager.currentEngineNo);
      assertEquals(0, recoveryEngine.enginePosition.get());
      assertLifecycleReservationReleased(recoveryEngine);
    }
  }

  @Test
  void foregroundActivationInitializationFailureRollsBackCommittedOwner() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.ponderFailure = new IllegalStateException("controlled initialization failure");
      engine.normalQuitCompleted = new CountDownLatch(1);
      BoardHistoryList history = emptyRootHistory(2);
      history.toStart();
      Lizzie.board = boardWithHistory(history);
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(engine)));
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(0, true));
      assertTrue(engine.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.synchronizationFailed.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(
          engine.normalQuitCompleted.await(2, TimeUnit.SECONDS),
          "the isolated failed-runtime stop must complete before its effects are asserted");

      assertEquals(1, manager.synchronizationFailureCount);
      assertTrue(EngineManager.isEmpty);
      assertEquals(-1, EngineManager.currentEngineNo);
      assertFalse(engine.isLoaded);
      assertEquals(1, engine.ponderCount);
      assertEquals(0, engine.analyzeCount());
      assertEquals(1, engine.normalQuitCount, "failed initialized runtime must be stopped");
      assertTrue(engine.notPonderingCount >= 2, "rollback must cancel the started ponder intent");
      assertEquals(
          EngineStartupStatus.State.START_FAILED,
          Lizzie.engineStartupStatus.snapshot().state,
          "no-active rollback must undo the earlier READY publication");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void noPrimaryFailureHidesOrdinaryPdaEvenWhenSecondaryRemainsLoaded() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz target = new StartupSyncLeelaz();
      StartupSyncLeelaz secondary = new StartupSyncLeelaz();
      target.ponderFailure = new IllegalStateException("controlled primary failure");
      secondary.started = true;
      secondary.isLoaded = true;
      secondary.isKataGoPda = true;
      Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Double_Engine;
      Lizzie.board = boardWithHistory(emptyRootHistory(1));
      Lizzie.setPrimaryEngine(null);
      Lizzie.leelaz2 = secondary;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      EngineManager.currentEngineNo2 = 1;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(target, secondary)));
      Lizzie.engineManager = manager;
      SilentStartupMenu menu = (SilentStartupMenu) LizzieFrame.menu;

      assertTrue(manager.switchEngineIfAvailable(0, true));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));

      assertEquals(null, Lizzie.leelaz);
      assertSame(null, menu.lastPdaEngine);
      assertFalse(
          menu.lastPdaVisible,
          "ordinary analysis must hide PDA when no primary owner is routable");
      assertTrue(secondary.isLoaded(), "secondary availability must not masquerade as primary");
    }
  }

  @Test
  void finalFenceSetupErrorFailsAndReleasesTransactionForRetry() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz failedEngine = new StartupSyncLeelaz();
      failedEngine.boardSynchronizationPostRegistrationError =
          new AssertionError("controlled post-registration target fence setup error");
      failedEngine.normalQuitCompleted = new CountDownLatch(1);
      StartupSyncLeelaz recoveryEngine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(2);
      history.toStart();
      Lizzie.board = boardWithHistory(history);
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(
              new ArrayList<>(List.of(failedEngine, recoveryEngine)));
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(0, true));
      assertTrue(failedEngine.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(
          failedEngine.normalQuitCompleted.await(2, TimeUnit.SECONDS),
          "the isolated failed-runtime stop must complete before its effects are asserted");

      assertEquals(
          EngineManager.EngineSwitchUiPhase.FAILED,
          manager.engineSwitchUiSnapshot(true).phase());
      assertTrue(EngineManager.isEmpty);
      assertEquals(-1, EngineManager.currentEngineNo);
      assertEquals(1, failedEngine.normalQuitCount);
      assertEquals(1, manager.synchronizationFailureCount);
      assertEquals(EngineStartupStatus.State.START_FAILED, Lizzie.engineStartupStatus.snapshot().state);
      assertLifecycleReservationReleased(failedEngine);

      failedEngine.publishLateBoardSynchronizationSuccess();
      assertEquals(
          1,
          manager.synchronizationFailureCount,
          "a late target ACK must not settle the already-failed lifecycle a second time");
      assertEquals(1, failedEngine.normalQuitCount);
      assertEquals(
          EngineManager.EngineSwitchUiPhase.FAILED,
          manager.engineSwitchUiSnapshot(true).phase());
      assertTrue(EngineManager.isEmpty);
      assertEquals(-1, EngineManager.currentEngineNo);

      assertTrue(
          manager.switchEngineIfAvailable(1, true),
          "an Error while establishing the final fence must release the transaction gate");
      assertTrue(recoveryEngine.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(recoveryEngine.analysisStarted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.secondSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      assertEquals(
          EngineManager.EngineSwitchUiPhase.ACTIVE,
          manager.engineSwitchUiSnapshot(true).phase());
      assertEquals(1, EngineManager.currentEngineNo);
      assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);
      assertLifecycleReservationReleased(recoveryEngine);
    }
  }

  @Test
  void asynchronousFinalAckCatchUpErrorFailsClosedAndReleasesLifecycle() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz failedEngine = new StartupSyncLeelaz();
      failedEngine.confirmBoardSynchronizationOnEdt = true;
      failedEngine.boardSynchronizationGate = new CountDownLatch(1);
      failedEngine.normalQuitCompleted = new CountDownLatch(1);
      AtomicInteger clearBoardCommands = new AtomicInteger();
      failedEngine.beforeCommand =
          command -> {
            if (command.equals("clear_board") && clearBoardCommands.incrementAndGet() == 2) {
              throw new AssertionError("controlled post-ACK catch-up error");
            }
          };
      StartupSyncLeelaz recoveryEngine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(5);
      history.toStart();
      Board board = boardWithHistory(history);
      Lizzie.board = board;
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.currentEngineNo = -1;
      EngineManager manager =
          new EngineManager(
              Lizzie.config,
              0,
              false,
              new ArrayList<>(
                  List.of(
                      engineData(0, "catch-up-error", false),
                      engineData(1, "catch-up-recovery", false))),
              command ->
                  "catch-up-error".equals(command) ? failedEngine : recoveryEngine);
      Lizzie.engineManager = manager;

      assertTrue(failedEngine.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(
          failedEngine.boardSynchronizationCallbackEntered.await(2, TimeUnit.SECONDS),
          "the asynchronous final ACK callback must be pending before navigation");
      navigateZeroToFiveToThree(board);
      failedEngine.boardSynchronizationGate.countDown();

      assertTrue(
          failedEngine.boardSynchronizationCallbackCompleted.await(2, TimeUnit.SECONDS),
          "an Error in post-ACK catch-up must settle the callback instead of escaping it");
      assertTrue(
          failedEngine.normalQuitCompleted.await(2, TimeUnit.SECONDS),
          "the failed provisional runtime must be stopped after claim settlement");
      long settlementDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (managerAtomicReferenceValue(manager, "engineSwitchTransaction") != null
          && System.nanoTime() < settlementDeadline) {
        Thread.sleep(10L);
      }

      assertEquals(
          EngineManager.EngineSwitchUiPhase.FAILED,
          manager.engineSwitchUiSnapshot(true).phase());
      assertTrue(EngineManager.isEmpty);
      assertEquals(-1, EngineManager.currentEngineNo);
      assertFalse(failedEngine.isLoaded);
      assertNull(managerAtomicReferenceValue(manager, "engineSwitchTransaction"));
      assertLifecycleReservationReleased(failedEngine);

      assertTrue(
          manager.switchEngineIfAvailable(1, true),
          "claim and transaction release must permit a later engine identity to start");
      assertTrue(recoveryEngine.startCompleted.await(2, TimeUnit.SECONDS));
      long retryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while ((manager.engineSwitchUiSnapshot(true).phase()
                  != EngineManager.EngineSwitchUiPhase.ACTIVE
              || managerAtomicReferenceValue(manager, "engineSwitchTransaction") != null)
          && System.nanoTime() < retryDeadline) {
        Thread.sleep(10L);
      }
      assertEquals(
          EngineManager.EngineSwitchUiPhase.ACTIVE,
          manager.engineSwitchUiSnapshot(true).phase());
      assertSame(recoveryEngine, Lizzie.leelaz);
      assertNull(managerAtomicReferenceValue(manager, "engineSwitchTransaction"));
      assertLifecycleReservationReleased(recoveryEngine);
    }
  }

  @Test
  void nonEdtFailedTargetStopDoesNotBlockRollbackOrOtherIdentityAndQuarantinesIncarnation()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz previous = new StartupSyncLeelaz();
      StartupSyncLeelaz target = new StartupSyncLeelaz();
      StartupSyncLeelaz alternative = new StartupSyncLeelaz();
      previous.started = true;
      previous.isLoaded = true;
      previous.Pondering();
      previous.analysisOutputRecoveryCompleted = new CountDownLatch(1);
      target.ponderFailure = new IllegalStateException("controlled initialization failure");
      target.normalQuitEntered = new CountDownLatch(1);
      target.normalQuitGate = new CountDownLatch(1);
      target.normalQuitCompleted = new CountDownLatch(1);
      target.normalQuitGateTimeoutMillis = TimeUnit.SECONDS.toMillis(30);
      Lizzie.board = boardWithHistory(emptyRootHistory(1));
      Lizzie.setPrimaryEngine(previous);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(
              new ArrayList<>(List.of(previous, target, alternative)));
      Lizzie.engineManager = manager;

      Thread failedStopThread = null;
      try {
        assertTrue(manager.switchEngineIfAvailable(1, true));
        assertTrue(target.normalQuitEntered.await(2, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {});
        assertTrue(
            previous.analysisOutputRecoveryCompleted.await(10, TimeUnit.SECONDS),
            "rollback must atomically promote the recovered analysis-output owner");
        assertTrue(
            previous.boardSynchronizationCallbackCompleted.await(10, TimeUnit.SECONDS),
            "rollback completion must release its lifecycle endpoint before another switch");
        assertSame(previous, Lizzie.leelaz, "rollback recovery must restore the previous primary");
        assertNull(managerAtomicReferenceValue(manager, "failedRollbackRecovery"));
        assertNull(managerAtomicReferenceValue(manager, "engineSwitchTransaction"));
        assertFalse(previous.hasExclusiveGtpWorkInProgress());

        failedStopThread = target.normalQuitThread;
        assertNotNull(failedStopThread);
        assertTrue(
            failedStopThread.isAlive(),
            "the failed process shutdown must still be blocked after rollback completes");
        assertEquals(
            1L,
            target.normalQuitGate.getCount(),
            "the controlled failed-process shutdown gate must remain closed");
        assertEquals(
            1L,
            target.normalQuitCompleted.getCount(),
            "the blocked failed-process shutdown must not report completion");

        target.ponderFailure = null;
        assertFalse(
            manager.switchEngineIfAvailable(1, true),
            "the exact failed incarnation must remain quarantined while normalQuit is blocked");

        assertTrue(
            manager.switchEngineIfAvailable(2, true),
            "a blocked failed-process shutdown must not hold the manager gate for other identities");
        assertTrue(alternative.analysisStarted.await(2, TimeUnit.SECONDS));
        assertTrue(manager.secondSynchronizationCompleted.await(2, TimeUnit.SECONDS));
        long alternativeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((manager.engineSwitchUiSnapshot(true).phase()
                    != EngineManager.EngineSwitchUiPhase.ACTIVE
                || EngineManager.currentEngineNo != 2
                || alternative.hasExclusiveGtpWorkInProgress())
            && System.nanoTime() < alternativeDeadline) {
          Thread.sleep(10L);
        }
        assertSame(alternative, Lizzie.leelaz);
        assertEquals(2, EngineManager.currentEngineNo);
        assertFalse(alternative.hasExclusiveGtpWorkInProgress());
        assertFalse(
            manager.switchEngineIfAvailable(1, true),
            "other successful switches must not clear the failed incarnation's quarantine");
      } finally {
        target.normalQuitGate.countDown();
        Thread cleanupThread = target.normalQuitThread;
        if (cleanupThread != null) {
          cleanupThread.join(TimeUnit.SECONDS.toMillis(10));
        }
      }

      assertTrue(target.normalQuitCompleted.await(2, TimeUnit.SECONDS));
      assertNotNull(failedStopThread);
      assertFalse(
          failedStopThread.isAlive(),
          "the quarantine capability must finish after the controlled normalQuit returns");
      assertTrue(
          manager.switchEngineIfAvailable(1, true),
          "the same catalog identity may be selected only after quarantine stop completes");
      assertTrue(target.analysisStarted.await(2, TimeUnit.SECONDS));
      long completionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while ((manager.engineSwitchUiSnapshot(true).phase()
                  != EngineManager.EngineSwitchUiPhase.ACTIVE
              || target.hasExclusiveGtpWorkInProgress())
          && System.nanoTime() < completionDeadline) {
        Thread.sleep(10L);
      }
      assertEquals(
          EngineManager.EngineSwitchUiPhase.ACTIVE,
          manager.engineSwitchUiSnapshot(true).phase(),
          "the successful retry must reach its final UI fence before fixture teardown");
      assertLifecycleReservationReleased(target);
    }
  }

  @Test
  void failedTargetRestoresPreviousPrimaryOnlyAfterCurrentBoardAck() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz previous = new StartupSyncLeelaz();
      StartupSyncLeelaz target = new StartupSyncLeelaz();
      previous.started = true;
      previous.isLoaded = true;
      previous.Pondering();
      previous.boardSynchronizationGate = new CountDownLatch(1);
      previous.ponderCommandSent = new CountDownLatch(1);
      previous.ponderAfterCommandGate = new CountDownLatch(1);
      target.delayReadyAfterStart = true;
      target.boardSynchronizationFailure = "controlled target final-fence failure";
      BoardHistoryList history = emptyRootHistory(5);
      history.toStart();
      Board board = boardWithHistory(history);
      Lizzie.board = board;
      Lizzie.setPrimaryEngine(previous);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(previous, target)));
      Lizzie.engineManager = manager;

      assertTrue(manager.switchEngineIfAvailable(1, true));
      assertTrue(target.startCompleted.await(2, TimeUnit.SECONDS));
      navigateZeroToFiveToThree(board);
      target.publishReady();
      assertTrue(previous.boardSynchronizationEntered.await(2, TimeUnit.SECONDS));

      assertEquals(null, Lizzie.leelaz, "rollback owner is unroutable before its ACK");
      assertTrue(EngineManager.isEmpty);
      assertEquals(EngineStartupStatus.State.START_FAILED, Lizzie.engineStartupStatus.snapshot().state);

      previous.boardSynchronizationGate.countDown();
      try {
        assertTrue(previous.ponderCommandSent.await(2, TimeUnit.SECONDS));
        Object recovery = managerAtomicReferenceValue(manager, "failedRollbackRecovery");
        assertNotNull(recovery);
        assertSame(recovery, previous.analysisOutputRecoveryTokenForTest());
        assertTrue(
            previous.suppressesGlobalEnginePresentation(
                previous.analysisReaderBindingForTest()),
            "recovery analysis stays quarantined until the final atomic promotion");
        assertEquals("EXACT_RETIRED", previous.analysisOutputRouteForTest());
      } finally {
        previous.ponderAfterCommandGate.countDown();
      }
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      long recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while ((managerAtomicReferenceValue(manager, "failedRollbackRecovery") != null
              || Lizzie.leelaz != previous
              || EngineManager.currentEngineNo != 0
              || EngineManager.isEmpty
              || previous.suppressesGlobalEnginePresentation(
                  previous.analysisReaderBindingForTest())
              || !"ORDINARY_CURRENT".equals(previous.analysisOutputRouteForTest()))
          && System.nanoTime() < recoveryDeadline) {
        Thread.sleep(10L);
      }
      assertNull(
          managerAtomicReferenceValue(manager, "failedRollbackRecovery"),
          "the recovery completion, not the earlier failed-target callback, is the final fence");
      assertSame(previous, Lizzie.leelaz);
      assertEquals(0, EngineManager.currentEngineNo);
      assertFalse(EngineManager.isEmpty);
      assertEquals(3, previous.enginePosition.get());
      assertEquals(
          1,
          previous.ponderCount,
          "captured ponder intent resumes exactly once after rollback ACK");
      Object recoveredBinding = previous.analysisReaderBindingForTest();
      assertFalse(
          previous.suppressesGlobalEnginePresentation(recoveredBinding),
          "successful recovery must atomically clear the parser quarantine");
      assertEquals(
          "ORDINARY_CURRENT",
          previous.analysisOutputRouteForTest(),
          "the fresh recovery analysis write must be promoted out of its tombstone");
      assertNull(managerAtomicReferenceValue(manager, "failedRollbackRecovery"));
      assertEquals(EngineStartupStatus.State.READY, Lizzie.engineStartupStatus.snapshot().state);
    }
  }

  @Test
  void navigationForwardThenBackConvergesToFinalNode() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(3);
      history.toStart();
      BoardHistoryNode expectedFinalNode =
          history.getCurrentHistoryNode().next().get().next().get(); // node 2
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      startup.beforeRestore =
          () -> {
            int round = rounds.getAndIncrement();
            if (round == 0) {
              assertTrue(board.nextMove(false));
              assertTrue(board.nextMove(false));
              assertTrue(board.nextMove(false)); // 0 -> 3
            } else if (round == 1) {
              assertTrue(board.previousMove(false)); // 3 -> 2
            }
          };

      runStartupInThread(startup, engine);

      assertEquals(3, engine.clearBoardCount.get(), "frozen route plus two catch-up routes");
      assertEquals(2, engine.analyzePosition(), "engine must converge on the final node (2)");
      assertEquals(1, engine.ponderCount);
      assertSame(expectedFinalNode, board.getHistory().getCurrentHistoryNode());
    }
  }

  @Test
  void snapshotRouteBackwardNavigationCatchUpRestoresSnapshotWithoutTail() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.snapshotBaseMove = 2;
      BoardHistoryList history = snapshotHistoryWithTail(true);
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() == 0) {
              assertTrue(board.previousMove(false)); // tail -> move node
              assertTrue(board.previousMove(false)); // move node -> snapshot root
            }
          };

      runStartupInThread(startup, engine);

      assertEquals(2, engine.loadSgfCount.get(), "frozen exact route plus one catch-up route");
      assertEquals(
          List.of("play B " + Board.convertCoordinatesToName(5, 5), "play W pass"),
          engine.tailPlays(),
          "frozen route replays the SNAPSHOT MOVE/PASS tail");
      assertEquals(
          0,
          engine.playsAfterLastLoadSgf().size(),
          "catch-up route lands on the snapshot anchor without a tail");
      assertEquals(2, engine.analyzePosition(), "analysis must start from the snapshot position");
      assertEquals(1, engine.ponderCount);
      assertSame(history.getCurrentHistoryNode(), history.getStart());
    }
  }

  @Test
  void branchNavigationDuringStartupConverges() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(2); // 0 -> 1 -> 2
      history.toStart();
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      BoardHistoryNode node1 = history.getCurrentHistoryNode().next().get();
      node1.addOrGoto(moveNode(node1.getData(), 6, 3, Stone.WHITE, true, 2), true, false, false);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() == 0) {
              assertTrue(board.nextMove(false)); // 0 -> 1
              assertTrue(board.nextVariation(1)); // 1 -> branch child
            }
          };

      runStartupInThread(startup, engine);

      assertEquals(2, engine.analyzePosition(), "engine must converge on the branch child");
      assertEquals(
          List.of(play("B", 4, 3), play("W", 6, 3)),
          engine.playsAfterLastClear(),
          "catch-up replay must select the branch child, not the same-number main-line node");
      assertEngineMatchesBoard(engine, board, 19, 19);
      assertEquals(1, engine.ponderCount);
    }
  }

  @Test
  void boardSizeReopenDefersEngineResizeWhileBarrierActive() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.snapshotBaseMove = 2;
      Board board = boardWithHistory(snapshotHistoryWithTail(false));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      AtomicReference<Double> postReopenKomi = new AtomicReference<>();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() == 0) {
              board.reopen(9, 9); // board-size UI / SGF-load path during startup
              postReopenKomi.set(board.getHistory().getGameInfo().getKomi());
              assertFalse(
                  engine.commands.stream().anyMatch(command -> command.startsWith("boardsize")),
                  "ordinary board-size resync must not reach the engine while the barrier is active");
            }
          };

      runStartupInThread(startup, engine);

      List<String> boardSizeCommands =
          engine.commands.stream().filter(command -> command.startsWith("boardsize")).toList();
      assertEquals(
          List.of("boardsize 9"),
          boardSizeCommands,
          "only the restore-owned reconcile may resize the engine during the barrier");
      assertEquals(9, engine.width, "engine width cache must reconcile to the captured frame");
      assertEquals(9, engine.height, "engine height cache must reconcile to the captured frame");
      assertEquals(
          postReopenKomi.get(),
          board.getHistory().getGameInfo().getKomi(),
          "the size reconcile must not overwrite the resized board's komi");
      assertEquals(
          postReopenKomi.get().floatValue(),
          engine.komi,
          0.001f,
          "engine komi must converge to the resized board's game komi");
      assertEquals(0, engine.analyzePosition(), "converged on the resized empty board");
      assertEquals(1, engine.ponderCount);
      assertEquals(9, Board.boardWidth, "board resize itself must remain effective");
    }
  }

  @Test
  void ordinaryClearSkipsEngineCommandsWhileBarrierActive() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(0);
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() == 0) {
              int before = engine.commands.size();
              board.clear(false); // File>New style board overwrite during startup
              assertEquals(
                  before,
                  engine.commands.size(),
                  "ordinary clear must not forward komi/clear commands during the barrier");
            }
          };

      runStartupInThread(startup, engine);

      assertEquals(1, engine.ponderCount);
      assertEquals(
          (float) board.getHistory().getGameInfo().getKomi(),
          engine.komi,
          0.001f,
          "engine komi must converge to the cleared game komi");
      assertEquals(0, engine.analyzePosition(), "converged on the cleared empty board");
    }
  }

  @Test
  void clearDuringFinalHandoffIsCaughtUpBeforeReady() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger handoffs = new AtomicInteger();
      AtomicBoolean ordinaryPlayDroppedAfterRelease = new AtomicBoolean();
      startup.afterReservationRelease =
          () -> {
            if (handoffs.getAndIncrement() == 0) {
              int before = engine.commands.size();
              engine.sendCommand("play B Q16");
              ordinaryPlayDroppedAfterRelease.set(
                  engine.commands.size() == before && !engine.commands.contains("play B Q16"));
              board.clear(false);
            }
          };

      runStartupInThread(startup, engine);

      assertTrue(
          ordinaryPlayDroppedAfterRelease.get(),
          "ordinary play must stay dropped after reservation release until frame judgment");
      assertEquals(
          2,
          engine.clearBoardCount.get(),
          "the board mutation in the final handoff gap must force a catch-up route");
      assertEquals(0, engine.enginePosition.get());
      assertTrue(engine.engineStones.isEmpty());
      assertEquals(0, engine.analyzePosition(), "analysis must start on the cleared board");
      assertEquals(0, board.getHistory().getData().moveNumber);
      assertEquals(1, engine.ponderCount);
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void historyOverwriteOccupiedAtMutationDoesNotReplayAfterHandoff() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(engine, board);
      engine.startEngine(0);

      CountDownLatch reachedForward = new CountDownLatch(1);
      CountDownLatch handoffFinished = new CountDownLatch(1);
      AtomicInteger commandsWhenForwardReached = new AtomicInteger();
      AtomicReference<Throwable> mutationFailure = new AtomicReference<>();
      Board.beforeHistoryOverwriteEngineForward =
          () -> {
            commandsWhenForwardReached.set(engine.commands.size());
            reachedForward.countDown();
            awaitLatch(handoffFinished);
          };

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      Thread mutationThread =
          new Thread(
              () -> {
                try {
                  board.clear(false);
                } catch (Throwable failure) {
                  mutationFailure.set(failure);
                }
              },
              "ticket03-occupied-overwrite-forward");
      try {
        mutationThread.start();
        assertTrue(reachedForward.await(5, TimeUnit.SECONDS), "mutation must reach forwarding");

        runStartupInThread(startup, engine);
        int commandsAfterHandoff = engine.commands.size();
        handoffFinished.countDown();
        mutationThread.join(5_000L);

        assertFalse(mutationThread.isAlive(), "delayed overwrite forwarding must settle");
        assertNull(mutationFailure.get(), "occupied overwrite must not throw");
        assertEquals(
            commandsAfterHandoff,
            engine.commands.size(),
            "stale clear/komi captured while the owner occupied must not replay after handoff");
        assertTrue(
            commandsWhenForwardReached.get() <= commandsAfterHandoff,
            "catch-up may enqueue while the occupied plan is held");
        assertEquals(
            (float) board.getHistory().getGameInfo().getKomi(),
            engine.komi,
            0.001f,
            "engine komi must converge from catch-up, not the stale plan");
        assertEquals(0, engine.analyzePosition(), "analysis starts on the overwritten board");
        assertEquals(1, engine.ponderCount);
        assertLifecycleReservationReleased(engine);
      } finally {
        handoffFinished.countDown();
        Board.beforeHistoryOverwriteEngineForward = null;
      }
    }
  }

  @Test
  void historyOverwriteAfterMutationLosesRaceToAdmissionStart() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(engine, board);
      engine.startEngine(0);

      CountDownLatch reachedForward = new CountDownLatch(1);
      CountDownLatch admissionStarted = new CountDownLatch(1);
      AtomicReference<Throwable> mutationFailure = new AtomicReference<>();
      Board.beforeHistoryOverwriteEngineForward =
          () -> {
            reachedForward.countDown();
            awaitLatch(admissionStarted);
          };

      Thread mutationThread =
          new Thread(
              () -> {
                try {
                  board.clear(false);
                } catch (Throwable failure) {
                  mutationFailure.set(failure);
                }
              },
              "ticket03-stale-overwrite-enqueue");
      EngineManager.InitialEngineStartupSynchronization startup = null;
      try {
        mutationThread.start();
        assertTrue(
            reachedForward.await(5, TimeUnit.SECONDS),
            "mutation must finish before ordinary forwarding enqueues");

        startup = captureStartup(engine, board);
        admissionStarted.countDown();
        mutationThread.join(5_000L);

        assertFalse(mutationThread.isAlive(), "stale overwrite forwarding must settle");
        assertNull(mutationFailure.get(), "raced overwrite must not throw");
        assertFalse(
            engine.commands.contains("clear_board"),
            "stale ordinary clear must not enter the queue after admission starts");
        assertFalse(
            engine.commands.stream().anyMatch(command -> command.startsWith("komi ")),
            "stale ordinary komi must not enter the queue after admission starts");

        runStartupInThread(startup, engine);

        assertEquals(
            (float) board.getHistory().getGameInfo().getKomi(),
            engine.komi,
            0.001f,
            "final komi must converge from the startup route");
        assertEquals(0, engine.analyzePosition(), "analysis starts on the overwritten board");
        assertEquals(1, engine.ponderCount);
        assertLifecycleReservationReleased(engine);
      } finally {
        admissionStarted.countDown();
        Board.beforeHistoryOverwriteEngineForward = null;
        if (startup != null) {
          startup.close();
        }
      }
    }
  }

  @Test
  void historyOverwriteHonorsDepthOnlyBoardBarrierWithoutAdmission() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(engine, board);
      engine.startEngine(0);

      engine.beginInitialBoardSynchronization();
      try {
        int before = engine.commands.size();
        board.clear(false);
        board.reopen(9, 9);
        flattenWithExtraStones(board, true);
        assertEquals(
            before,
            engine.commands.size(),
            "a depth-only restart/startup barrier must suppress ordinary overwrite");
      } finally {
        engine.endInitialBoardSynchronization();
      }

      board.clear(false);
      assertTrue(
          engine.commands.contains("clear_board"),
          "ordinary clear must forward after the depth-only barrier ends");
    }
  }

  @Test
  void depthOnlyBarrierOccupancyDoesNotReplayAfterBarrierEnds() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(engine, board);
      engine.startEngine(0);

      CountDownLatch reachedForward = new CountDownLatch(1);
      CountDownLatch barrierEnded = new CountDownLatch(1);
      AtomicReference<Throwable> mutationFailure = new AtomicReference<>();
      Board.beforeHistoryOverwriteEngineForward =
          () -> {
            reachedForward.countDown();
            awaitLatch(barrierEnded);
          };

      engine.beginInitialBoardSynchronization();
      Thread mutationThread =
          new Thread(
              () -> {
                try {
                  board.clear(false);
                } catch (Throwable failure) {
                  mutationFailure.set(failure);
                }
              },
              "ticket03-depth-only-occupied-forward");
      try {
        mutationThread.start();
        assertTrue(reachedForward.await(5, TimeUnit.SECONDS), "mutation must reach forwarding");
        int commandsBeforeRelease = engine.commands.size();
        engine.endInitialBoardSynchronization();
        barrierEnded.countDown();
        mutationThread.join(5_000L);

        assertFalse(mutationThread.isAlive(), "delayed depth-only overwrite must settle");
        assertNull(mutationFailure.get(), "depth-only occupied overwrite must not throw");
        assertEquals(
            commandsBeforeRelease,
            engine.commands.size(),
            "stale overwrite captured under a depth-only barrier must not replay after it ends");
      } finally {
        barrierEnded.countDown();
        Board.beforeHistoryOverwriteEngineForward = null;
        engine.endInitialBoardSynchronization();
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("historyOverwriteMutations")
  void historyOverwriteDuringAdmissionStaysOutOfQueueAndConverges(
      String name, HistoryOverwriteMutation mutation) throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      AtomicReference<Throwable> mutationFailure = new AtomicReference<>();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() != 0) {
              return;
            }
            int before = engine.commands.size();
            try {
              mutation.apply(board);
            } catch (Throwable failure) {
              mutationFailure.set(failure);
              return;
            }
            assertEquals(
                before,
                engine.commands.size(),
                name + " must not enqueue ordinary overwrite commands while admission is active");
          };

      runStartupInThread(startup, engine);

      assertNull(mutationFailure.get(), name + " must not throw");
      assertEquals(1, engine.ponderCount, name + " must wait for READY/ponder");
      assertEquals(
          (float) board.getHistory().getGameInfo().getKomi(),
          engine.komi,
          0.001f,
          name + " must converge komi");
      assertEquals(
          Board.boardWidth,
          engine.engineBoardWidth,
          name + " must converge board width");
      assertEquals(
          Board.boardHeight,
          engine.engineBoardHeight,
          name + " must converge board height");
      assertEquals(
          0,
          engine.analyzePosition(),
          name + " must analyze the overwritten position");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void historyOverwriteForwardsWhenAdmissionIsInactive() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(engine, board);
      engine.startEngine(0);

      board.clear(false);
      assertTrue(engine.commands.contains("clear_board"), "clear must forward when idle");
      assertTrue(
          engine.commands.stream().anyMatch(command -> command.startsWith("komi ")),
          "clear must forward komi when idle");

      board.reopen(13, 13);
      assertTrue(
          engine.commands.contains("boardsize 13"),
          "reopen must forward boardsize when idle");

      flattenWithExtraStones(board, true);
      assertTrue(
          engine.containsCommand(play("B", 3, 3)),
          "flatten extra-stone feeds must reach the engine when idle");
      assertTrue(
          engine.commands.stream().anyMatch(command -> command.startsWith("komi ")),
          "flatten must forward komi when idle");

      board.reopen(13, 13);
      long boardSizeCount =
          engine.commands.stream().filter(command -> command.equals("boardsize 13")).count();
      assertEquals(1, boardSizeCount, "same-size reopen must be a no-op");
    }
  }


  @Test
  void catchUpReservationReacquisitionFailureFailsClosed() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicReference<Leelaz.ExclusiveGtpLifecycleReservation> conflictingReservation =
          new AtomicReference<>();
      AtomicInteger releases = new AtomicInteger();
      startup.afterReservationRelease =
          () -> {
            if (releases.getAndIncrement() == 0) {
              board.clear(false);
              conflictingReservation.set(
                  engine.beginExclusiveGtpLifecycleReservation(new Object()));
              assertNotNull(
                  conflictingReservation.get(),
                  "fixture must occupy the lifecycle before catch-up reacquisition");
            }
          };
      int readyBaseline = env.readyTransitions.get();

      RuntimeException failure = runStartupExpectingFailure(startup, engine);
      Leelaz.ExclusiveGtpLifecycleReservation blocker = conflictingReservation.getAndSet(null);
      if (blocker != null) {
        blocker.close();
      }

      assertNotNull(failure, "rejected catch-up reservation must fail closed");
      assertFalse(engine.isLoaded);
      assertEquals(0, engine.ponderCount);
      assertEquals(0, engine.analyzeCount());
      assertEquals(0, env.readyTransitions.get() - readyBaseline);
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void initialResizeForwardingReleasesBoardMonitorBeforeReverseLockCanEnter()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.started = true;
      engine.isLoaded = true;
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(engine, board);
      CountDownLatch forwardingReached = new CountDownLatch(1);
      CountDownLatch reverseBoardLockAcquired = new CountDownLatch(1);
      AtomicReference<Throwable> mutationFailure = new AtomicReference<>();
      Board.beforeHistoryOverwriteEngineForward =
          () -> {
            forwardingReached.countDown();
            assertFalse(
                Thread.holdsLock(board),
                "initial resize forwarding must execute outside the Board monitor");
            awaitLatch(reverseBoardLockAcquired);
          };
      Thread mutation =
          new Thread(
              () -> {
                try {
                  board.resizeAndClearForInitialEngineStartup(13, 9);
                } catch (Throwable failure) {
                  mutationFailure.set(failure);
                }
              },
              "initial-resize-forwarding");
      Thread reverse =
          new Thread(
              () -> {
                synchronized (board) {
                  reverseBoardLockAcquired.countDown();
                }
              },
              "reverse-board-lock");
      try {
        mutation.start();
        assertTrue(
            forwardingReached.await(10, TimeUnit.SECONDS),
            "initial resize must reach the out-of-monitor forwarding seam");
        reverse.start();
        assertTrue(
            reverseBoardLockAcquired.await(10, TimeUnit.SECONDS),
            "reverse board-lock acquisition must not wait for forwarding to finish");
        reverse.join(10_000L);
        mutation.join(10_000L);
        assertFalse(reverse.isAlive(), "reverse board-lock probe must terminate");
        assertFalse(mutation.isAlive(), "initial resize forwarding must converge");
        assertNull(mutationFailure.get(), "initial resize forwarding must not throw");
      } finally {
        reverseBoardLockAcquired.countDown();
        Board.beforeHistoryOverwriteEngineForward = null;
        mutation.join(10_000L);
        reverse.join(10_000L);
      }
    }
  }

  @Test
  void ordinaryBoardResyncIsSkippedWhileBarrierActive() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.snapshotBaseMove = 2;
      Board board = boardWithHistory(snapshotHistoryWithTail(false));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() == 0) {
              int before = engine.commands.size();
              board.resendMoveToEngine(engine, false); // ordinary board-following resync
              board.getHistory().getCurrentHistoryNode().clearAndSyncBoard(false);
              assertFalse(
                  board.resendCurrentPositionToPrimaryEngine(),
                  "live primary resync must be refused while the barrier is active");
              assertFalse(
                  board.trySyncCurrentPositionToPrimaryEngineIncrementally(
                      board.getHistory().getData(), 19, 19),
                  "incremental primary resync must be refused while the barrier is active");
              assertEquals(
                  before,
                  engine.commands.size(),
                  "ordinary board-following resync must not interleave with the barrier");
            }
          };

      runStartupInThread(startup, engine);

      assertEquals(1, engine.loadSgfCount.get(), "only the frozen route executes");
      assertEquals(1, engine.ponderCount);
    }
  }

  @Test
  void ordinaryMoveNavigationDuringAdmissionConvergesWithoutOrdinaryCommandsOrException()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(3);
      history.toStart();
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      BoardHistoryNode node1 = history.getCurrentHistoryNode().next().orElseThrow();
      node1.addOrGoto(moveNode(node1.getData(), 6, 3, Stone.WHITE, true, 2), true, false, false);
      engine.startEngine(0);
      assertTrue(engine.isLoaded, "fixture must keep the primary engine ready during admission");

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      AtomicReference<Throwable> navigationFailure = new AtomicReference<>();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() == 0) {
              try {
                int commandsBefore = engine.commands.size();
                assertTrue(board.nextMove(false), "next must reach move 1");
                assertTrue(board.nextMove(false), "next must reach move 2");
                assertTrue(board.previousMove(false), "previous must return to move 1");
                assertTrue(board.nextVariation(1), "variation must enter the branch child");
                assertTrue(board.goToMoveNumber(1), "jump must return to move 1");
                assertTrue(board.goToMoveNumber(3), "jump must reach the main-line tail");
                assertFalse(
                    board.resendCurrentPositionToPrimaryEngine(),
                    "full current-position resync must stay refused during admission");
                assertFalse(
                    board.trySyncCurrentPositionToPrimaryEngineIncrementally(
                        board.getHistory().getData(), 19, 19),
                    "incremental current-position sync must stay refused during admission");
                board.resendMoveToEngine(engine, false);
                board.getHistory().getCurrentHistoryNode().clearAndSyncBoard(false);
                assertEquals(
                    commandsBefore,
                    engine.commands.size(),
                    "ordinary MOVE navigation must not inject live-board commands during admission");
              } catch (Throwable failure) {
                navigationFailure.set(failure);
              }
            }
          };

      runStartupInThread(startup, engine);

      assertNull(
          navigationFailure.get(),
          "ordinary MOVE navigation and sync during admission must not throw");
      assertEquals(3, board.getHistory().getMoveNumber(), "history cursor at the main-line tail");
      assertSame(history.getEnd(), board.getHistory().getCurrentHistoryNode());
      assertEquals(3, engine.analyzePosition(), "catch-up must converge on the final MOVE");
      assertEquals(
          List.of(play("B", 4, 3), play("W", 5, 3), play("B", 6, 3)),
          engine.playsAfterLastClear(),
          "catch-up replay must rebuild the main-line tail from the root");
      assertEquals(1, engine.ponderCount, "analysis waits for the stable restore point");
      assertEngineMatchesBoard(engine, board, 19, 19);
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void initialRestoreFailureFailsClosed() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.snapshotBaseMove = 2;
      engine.failLoadSgfAt = 1;
      Board board = boardWithHistory(snapshotHistoryWithTail(false));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      int readyBaseline = env.readyTransitions.get();

      RuntimeException failure = runStartupExpectingFailure(startup, engine);

      assertNotNull(failure, "initial restore failure must surface");
      assertFalse(engine.isLoaded, "target engine must be marked unavailable");
      assertEquals(0, engine.ponderCount, "no analysis after failure");
      assertEquals(0, engine.analyzeCount(), "no analysis command after failure");
      assertEquals(0, env.readyTransitions.get() - readyBaseline, "never marked ready");
      assertEquals(1, engine.loadSgfCount.get(), "only the failed loadsgf attempt");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void failureCleanupKeepsBarrierActiveUntilReservationIsReleased() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      startup.beforeRestore =
          () -> {
            throw new IllegalStateException("controlled startup failure");
          };
      Object arbitrationLock = engineArbitrationLock(engine);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread cleanupThread =
          new Thread(
              () -> {
                try {
                  startup.run();
                } catch (Throwable thrown) {
                  failure.set(thrown);
                } finally {
                  startup.close();
                }
              },
              "initial-startup-failure-cleanup-test");

      synchronized (arbitrationLock) {
        cleanupThread.start();
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (cleanupThread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
          Thread.sleep(10L);
        }
        assertEquals(
            Thread.State.BLOCKED,
            cleanupThread.getState(),
            "failure cleanup must reach the blocked reservation-release phase");
        assertFalse(
            engine.submitOrdinaryLiveBoardForwarding(
                EngineManager.OrdinaryLiveBoardForwardingIntent.of(() -> true)),
            "ordinary forwarding must stay occupied while failure cleanup still holds the reservation");
      }

      cleanupThread.join(2_000L);
      assertFalse(cleanupThread.isAlive(), "failure cleanup must settle after reservation release");
      assertNotNull(failure.get(), "controlled startup failure must surface");
      assertTrue(
          engine.submitOrdinaryLiveBoardForwarding(
              EngineManager.OrdinaryLiveBoardForwardingIntent.of(() -> true)),
          "ordinary forwarding must reopen after failure cleanup releases the reservation");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void cleanupEndsBarrierWhenReservationReleaseFails() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      queueFailingCommandForLifecycleRelease(engine, "name");
      engine.installCommandOutputForTest(new FailingOutputStream());

      RuntimeException failure = assertThrows(RuntimeException.class, startup::close);

      assertTrue(
          failure.getMessage().contains("Failed to send GTP command"),
          "reservation release failure must propagate");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void closeReleasesMirrorAdmissionAndCompletionClaimAfterTargetDetachError()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      AssertionError detachFailure = new AssertionError("controlled target detach failure");
      DetachFailingStartupSyncLeelaz target =
          new DetachFailingStartupSyncLeelaz(detachFailure);
      StartupSyncLeelaz mirror = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(1));
      env.publish(target, board);
      target.startEngine(0);
      mirror.startEngine(1);

      EngineManager.InitialEngineStartupSynchronization startup =
          EngineManager.InitialEngineStartupSynchronization.capture(
              null, target, mirror, board, false, false);
      Method beginCompletionClaim =
          EngineManager.InitialEngineStartupSynchronization.class.getDeclaredMethod(
              "beginLifecycleCompletionClaim");
      beginCompletionClaim.setAccessible(true);
      beginCompletionClaim.invoke(startup);
      Field completionClaim =
          EngineManager.InitialEngineStartupSynchronization.class.getDeclaredField(
              "completionClaim");
      completionClaim.setAccessible(true);
      Leelaz.LifecycleCompletionClaim installedClaim =
          (Leelaz.LifecycleCompletionClaim) completionClaim.get(startup);
      assertNotNull(installedClaim);

      AssertionError thrown = assertThrows(AssertionError.class, startup::close);

      assertSame(detachFailure, thrown);
      assertEquals(1, target.detachAttempts);
      assertLifecycleReservationReleased(target);
      assertLifecycleReservationReleased(mirror);
      Leelaz.LifecycleCompletionClaim freshClaim =
          target.tryBeginLifecycleCompletion(new Object(), mirror);
      assertNotNull(
          freshClaim,
          "target detach failure must not strand completion ownership on either endpoint");
      freshClaim.abandonBeforeFence();
    }
  }

  @Test
  void lifecycleReservationsCloseRetainsFirstFailureAndSuppressesEveryLaterFailure()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz current = new StartupSyncLeelaz();
      StartupSyncLeelaz target = new StartupSyncLeelaz();
      env.publish(current, boardWithHistory(emptyRootHistory(0)));
      Leelaz.ExclusiveGtpLifecycleReservation currentReservation =
          current.beginExclusiveGtpLifecycleReservation();
      Leelaz.ExclusiveGtpLifecycleReservation targetReservation =
          target.beginExclusiveGtpLifecycleReservation();
      assertNotNull(currentReservation);
      assertNotNull(targetReservation);
      queueFailingCommandForLifecycleRelease(current, "name");
      queueFailingCommandForLifecycleRelease(target, "name");
      current.installCommandOutputForTest(new FailingOutputStream("current release failure"));
      target.installCommandOutputForTest(new FailingOutputStream("target release failure"));
      AssertionError gateFailure = new AssertionError("controlled interaction gate close failure");
      AtomicInteger gateCloseCount = new AtomicInteger();

      Class<?> reservationsType =
          Class.forName("featurecat.lizzie.analysis.EngineManager$EngineLifecycleReservations");
      Constructor<?> constructor =
          reservationsType.getDeclaredConstructor(
              Leelaz.ExclusiveGtpLifecycleReservation.class,
              Leelaz.ExclusiveGtpLifecycleReservation.class);
      constructor.setAccessible(true);
      Object reservations = constructor.newInstance(currentReservation, targetReservation);
      Field interactionGate = reservationsType.getDeclaredField("interactionGate");
      interactionGate.setAccessible(true);
      interactionGate.set(
          reservations,
          (LizzieFrame.RestartInteractionGate)
              () -> {
                gateCloseCount.incrementAndGet();
                throw gateFailure;
              });
      Method close = reservationsType.getDeclaredMethod("close");
      close.setAccessible(true);

      InvocationTargetException invocationFailure =
          assertThrows(InvocationTargetException.class, () -> close.invoke(reservations));
      Throwable failure = invocationFailure.getCause();

      assertTrue(failure instanceof RuntimeException);
      assertTrue(failure.getMessage().contains("target release failure"));
      assertEquals(2, failure.getSuppressed().length);
      assertTrue(failure.getSuppressed()[0].getMessage().contains("current release failure"));
      assertSame(gateFailure, failure.getSuppressed()[1]);
      assertEquals(1, gateCloseCount.get());
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    }
  }

  @Test
  void captureFailureRetainsPrimaryFailureWhenReservationCleanupAlsoFails() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      RuntimeException captureFailure = new IllegalStateException("controlled route capture failure");
      CaptureFailureBoard board = allocate(CaptureFailureBoard.class);
      board.startStonelist = new ArrayList<>();
      board.movelistwr = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(emptyRootHistory(0));
      board.captureFailure = captureFailure;
      board.failOnHistoryRead = true;
      env.publish(engine, board);
      engine.startEngine(0);

      queueFailingCommandForLifecycleRelease(engine, "name");
      engine.installCommandOutputForTest(new FailingOutputStream());

      RuntimeException failure =
          assertThrows(
              RuntimeException.class,
              () -> EngineManager.InitialEngineStartupSynchronization.capture(engine, board, false));

      assertSame(captureFailure, failure, "capture failure must remain the primary exception");
      assertEquals(1, failure.getSuppressed().length, "cleanup failure must be attached once");
      assertTrue(
          failure.getSuppressed()[0].getMessage().contains("Failed to send GTP command"),
          "reservation cleanup failure must remain diagnosable");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void catchUpRestoreFailureFailsClosed() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      engine.snapshotBaseMove = 2;
      engine.failLoadSgfAt = 2;
      Board board = boardWithHistory(snapshotHistoryWithTail(false));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      startup.beforeRestore =
          () -> {
            if (rounds.getAndIncrement() == 0) {
              assertTrue(board.previousMove(false));
            }
          };
      int readyBaseline = env.readyTransitions.get();

      RuntimeException failure = runStartupExpectingFailure(startup, engine);

      assertNotNull(failure, "catch-up restore failure must surface");
      assertFalse(engine.isLoaded, "target engine must be marked unavailable");
      assertEquals(0, engine.ponderCount);
      assertEquals(0, env.readyTransitions.get() - readyBaseline);
      assertEquals(2, engine.loadSgfCount.get(), "frozen route then failed catch-up route");
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void continuousNavigationRequiresMultipleCatchUpsBeforeReady() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      BoardHistoryList history = emptyRootHistory(2);
      history.toStart();
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger rounds = new AtomicInteger();
      startup.beforeRestore =
          () -> {
            int round = rounds.getAndIncrement();
            if (round == 0) {
              assertTrue(board.nextMove(false)); // 0 -> 1
            } else if (round == 1) {
              assertTrue(board.nextMove(false)); // 1 -> 2
            }
          };

      runStartupInThread(startup, engine);

      assertEquals(3, engine.clearBoardCount.get(), "frozen route plus two catch-up rounds");
      assertEquals(2, engine.analyzePosition(), "final convergence at node 2");
      assertEquals(1, engine.ponderCount, "analysis must wait for the final stable point");
    }
  }

  @Test
  void markEngineReadyIsDeferredWhileBarrierActive() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      AtomicInteger readyDuringBarrier = new AtomicInteger(-1);
      startup.beforeRestore =
          () -> {
            try {
              invokeCloseBundledStartupDialog(engine);
            } catch (Exception ex) {
              throw new AssertionError("closeBundledStartupDialog invocation failed", ex);
            }
            readyDuringBarrier.set(env.readyTransitions.get());
          };
      int readyBaseline = env.readyTransitions.get();

      runStartupInThread(startup, engine);

      assertEquals(
          readyBaseline,
          readyDuringBarrier.get(),
          "markEngineReady must be deferred while the initial synchronization barrier is active");
      assertEquals(
          1,
          env.readyTransitions.get() - readyBaseline,
          "marked ready exactly once at the stable point");
    }
  }

  @Test
  void nameVersionParserReleasesEngineMonitorBeforeBundledPrimaryFence() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz(true);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.setPrimaryEngine(engine);
      long primaryGeneration = Lizzie.capturePrimaryEngineGeneration(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.isCheckingName = false;
      engine.isCheckingVersion = true;
      engine.started = true;
      engine.isLoaded = false;
      Lizzie.engineStartupStatus.checking("engine.starting", "controlled version response");

      CountDownLatch primaryHeld = new CountDownLatch(1);
      CountDownLatch attemptEngineMonitor = new CountDownLatch(1);
      CountDownLatch engineMonitorAcquired = new CountDownLatch(1);
      AtomicReference<Throwable> primaryFailure = new AtomicReference<>();
      AtomicReference<Throwable> parserFailure = new AtomicReference<>();
      Thread primaryOwner =
          new Thread(
              () -> {
                try {
                  boolean ran =
                      Lizzie.runIfPrimaryEngine(
                          engine,
                          primaryGeneration,
                          () -> {
                            primaryHeld.countDown();
                            awaitLatch(attemptEngineMonitor);
                            synchronized (engine) {
                              engineMonitorAcquired.countDown();
                            }
                          });
                  if (!ran) {
                    primaryFailure.set(
                        new AssertionError("controlled primary generation was not current"));
                  }
                } catch (Throwable failure) {
                  primaryFailure.set(failure);
                }
              },
              "bundled-ready-primary-owner");
      Thread parser =
          new Thread(
              () -> {
                try {
                  invokeParseLine(engine, "= 0.17");
                } catch (Throwable failure) {
                  parserFailure.set(failure);
                }
              },
              "bundled-ready-version-parser");
      primaryOwner.setDaemon(true);
      parser.setDaemon(true);
      primaryOwner.start();
      assertTrue(primaryHeld.await(2, TimeUnit.SECONDS));
      parser.start();
      assertTrue(
          awaitThreadState(parser, Thread.State.BLOCKED, 2_000L),
          "the parser should reach the bundled PRIMARY fence while it is held");

      attemptEngineMonitor.countDown();
      assertTrue(
          engineMonitorAcquired.await(2, TimeUnit.SECONDS),
          "PRIMARY -> engine monitor must not deadlock with the name/version parser");
      primaryOwner.join(2_000L);
      parser.join(2_000L);

      assertFalse(primaryOwner.isAlive());
      assertFalse(parser.isAlive());
      assertNull(primaryFailure.get());
      assertNull(parserFailure.get());
      assertFalse(engine.isCheckingVersion);
    }
  }

  @Test
  void deferredNameVersionReadyRejectsAwayBackAndReaderRebind() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz(true);
      StartupSyncLeelaz intervening = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.setPrimaryEngine(engine);
      long originalGeneration = Lizzie.capturePrimaryEngineGeneration(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.isCheckingName = false;
      engine.isCheckingVersion = true;
      engine.started = true;
      engine.isLoaded = false;
      Lizzie.engineStartupStatus.checking("engine.starting", "original startup generation");

      CountDownLatch primaryHeld = new CountDownLatch(1);
      CountDownLatch replaceStartup = new CountDownLatch(1);
      AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
      AtomicReference<Throwable> parserFailure = new AtomicReference<>();
      AtomicReference<EngineStartupStatus.Snapshot> replacementStatus = new AtomicReference<>();
      Thread primaryOwner =
          new Thread(
              () -> {
                try {
                  assertTrue(
                      Lizzie.runIfPrimaryEngine(
                          engine,
                          originalGeneration,
                          () -> {
                            primaryHeld.countDown();
                            awaitLatch(replaceStartup);
                            Lizzie.setPrimaryEngine(intervening);
                            Lizzie.setPrimaryEngine(engine);
                            try {
                              engine.installFreshCommandOutputForTest(engine.commandTransport);
                              engine.bindCurrentPrimaryEngineGeneration();
                            } catch (Exception failure) {
                              throw new AssertionError(failure);
                            }
                            engine.started = true;
                            engine.isLoaded = true;
                            Lizzie.engineStartupStatus.checking(
                                "engine.starting", "replacement startup generation");
                            replacementStatus.set(Lizzie.engineStartupStatus.snapshot());
                          }));
                } catch (Throwable failure) {
                  ownerFailure.set(failure);
                }
              },
              "replace-bundled-startup-generation");
      Thread parser =
          new Thread(
              () -> {
                try {
                  invokeParseLine(engine, "= 0.17");
                } catch (Throwable failure) {
                  parserFailure.set(failure);
                }
              },
              "stale-bundled-version-parser");
      primaryOwner.setDaemon(true);
      parser.setDaemon(true);
      primaryOwner.start();
      assertTrue(primaryHeld.await(2, TimeUnit.SECONDS));
      parser.start();
      assertTrue(awaitThreadState(parser, Thread.State.BLOCKED, 2_000L));

      replaceStartup.countDown();
      primaryOwner.join(2_000L);
      parser.join(2_000L);

      assertFalse(primaryOwner.isAlive());
      assertFalse(parser.isAlive());
      assertNull(ownerFailure.get());
      assertNull(parserFailure.get());
      assertSame(replacementStatus.get(), Lizzie.engineStartupStatus.snapshot());
      assertEquals(EngineStartupStatus.State.CHECKING, replacementStatus.get().state);
    }
  }

  @Test
  void staleKataPostActionRejectsReaderRebindWhileBundledFenceBlocked() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz(true);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.setPrimaryEngine(engine);
      long primaryGeneration = Lizzie.capturePrimaryEngineGeneration(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.isCheckingName = true;
      engine.isCheckingVersion = false;
      engine.started = true;
      engine.isLoaded = false;
      Lizzie.engineStartupStatus.checking("engine.starting", "controlled stale KataGo name");

      CountDownLatch primaryHeld = new CountDownLatch(1);
      CountDownLatch rebindReader = new CountDownLatch(1);
      AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
      AtomicReference<Throwable> parserFailure = new AtomicReference<>();
      Thread primaryOwner =
          new Thread(
              () -> {
                try {
                  assertTrue(
                      Lizzie.runIfPrimaryEngine(
                          engine,
                          primaryGeneration,
                          () -> {
                            primaryHeld.countDown();
                            awaitLatch(rebindReader);
                            engine.installFreshCommandOutputForTest(engine.commandTransport);
                            engine.bindCurrentPrimaryEngineGeneration();
                            engine.started = true;
                            engine.isLoaded = true;
                            engine.commands.clear();
                            engine.getRcentLine = false;
                          }));
                } catch (Throwable failure) {
                  ownerFailure.set(failure);
                }
              },
              "stale-kata-primary-owner");
      Thread parser =
          new Thread(
              () -> {
                try {
                  invokeParseLine(engine, "= KataGo");
                } catch (Throwable failure) {
                  parserFailure.set(failure);
                }
              },
              "stale-kata-name-parser");
      primaryOwner.setDaemon(true);
      parser.setDaemon(true);
      primaryOwner.start();
      assertTrue(primaryHeld.await(2, TimeUnit.SECONDS));
      parser.start();
      assertTrue(awaitThreadState(parser, Thread.State.BLOCKED, 2_000L));

      rebindReader.countDown();
      primaryOwner.join(2_000L);
      parser.join(2_000L);

      assertFalse(primaryOwner.isAlive());
      assertFalse(parser.isAlive());
      assertNull(ownerFailure.get());
      assertNull(parserFailure.get());
      assertTrue(engine.commands.isEmpty(), "the old reader must not configure the rebound engine");
      assertFalse(engine.getRcentLine, "the old reader must not arm a parameter-read timeout");
    }
  }

  @Test
  void retiredNameLineCannotMarkReplacementReaderLoaded() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz(true);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;

      AtomicReference<Throwable> parserFailure = new AtomicReference<>();
      Thread parser =
          new Thread(
              () -> {
                try {
                  invokeParseLine(engine, "= KataGo");
                } catch (Throwable failure) {
                  parserFailure.set(failure);
                }
              },
              "retired-name-line-parser");
      parser.setDaemon(true);
      synchronized (engine) {
        parser.start();
        assertTrue(awaitThreadState(parser, Thread.State.BLOCKED, 2_000L));
        engine.installFreshCommandOutputForTest(engine.commandTransport);
        engine.bindCurrentPrimaryEngineGeneration();
        engine.started = true;
        engine.isLoaded = false;
        engine.isCheckingName = true;
      }
      parser.join(2_000L);

      assertFalse(parser.isAlive());
      assertNull(parserFailure.get());
      assertTrue(engine.isCheckingName, "the replacement reader must still await its own name");
      assertFalse(engine.isLoaded, "the retired reader must not mark the replacement runtime ready");
      assertFalse(engine.isKatago, "the retired name must not classify the replacement runtime");
    }
  }

  @Test
  void pdaStartupTimeoutIsFencedByReaderAndQueryGeneration() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz(true);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;

      invokeParseLine(engine, "= KataGoPda");
      assertEquals(1, engine.pdaStartupTimeouts.size());
      Runnable retiredTimeout = engine.pdaStartupTimeouts.get(0);
      assertTrue(engine.isCheckingPdaForTest());

      engine.installFreshCommandOutputForTest(engine.commandTransport);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      invokeParseLine(engine, "= KataGoPda");
      assertEquals(2, engine.pdaStartupTimeouts.size());
      Runnable replacementTimeout = engine.pdaStartupTimeouts.get(1);
      assertTrue(engine.isCheckingPdaForTest());

      retiredTimeout.run();
      assertTrue(
          engine.isCheckingPdaForTest(),
          "the retired reader timeout must not clear the new query");
      replacementTimeout.run();
      assertFalse(
          engine.isCheckingPdaForTest(),
          "the current reader/query timeout must settle its query");
    }
  }

  @Test
  void pdaStartupTimeoutSchedulingFailureSettlesExactQuery() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz(true);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      RuntimeException schedulingFailure =
          new IllegalStateException("controlled PDA timeout scheduling failure");
      engine.pdaStartupTimeoutSchedulingFailure = schedulingFailure;

      RuntimeException startupFailure =
          assertThrows(RuntimeException.class, () -> invokeParseLine(engine, "= KataGoPda"));
      assertSame(schedulingFailure, startupFailure.getCause());
      assertTrue(engine.startupCommandAttempts.contains("getpda"));
      assertTrue(engine.startupCommandAttempts.contains("getdympdacap"));
      assertFalse(engine.isCheckingPdaForTest());
      assertTrue(engine.pdaStartupTimeouts.isEmpty());
      assertFalse(engine.getRcentLine, "failed startup must retire parameter-read state");
      assertFalse(engine.isLoaded, "failed post-work must not publish startup readiness");
      assertTrue(engine.isDownWithError, "failed post-work must fail the exact runtime closed");
    }
  }

  @Test
  void firstPdaStartupCommandFailureSettlesExactQuery() throws Exception {
    assertPdaStartupCommandFailureSettlesExactQuery("getpda");
  }

  @Test
  void secondPdaStartupCommandFailureSettlesExactQuery() throws Exception {
    assertPdaStartupCommandFailureSettlesExactQuery("getdympdacap");
  }

  private void assertPdaStartupCommandFailureSettlesExactQuery(String failedCommand)
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz(true);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      RuntimeException commandFailure =
          new IllegalStateException("controlled " + failedCommand + " startup failure");
      engine.pdaStartupCommandFailureCommand = failedCommand;
      engine.pdaStartupCommandFailure = commandFailure;

      RuntimeException startupFailure =
          assertThrows(RuntimeException.class, () -> invokeParseLine(engine, "= KataGoPda"));
      assertSame(commandFailure, startupFailure.getCause());
      assertTrue(
          engine.startupCommandAttempts.contains(failedCommand),
          "the controlled failure must be reached through the requested startup command");
      assertFalse(engine.isCheckingPdaForTest());
      assertTrue(engine.pdaStartupTimeouts.isEmpty());
      assertFalse(engine.getRcentLine, "failed startup must retire parameter-read state");
      assertFalse(engine.isLoaded, "failed post-work must not publish startup readiness");
      assertTrue(engine.isDownWithError, "failed post-work must fail the exact runtime closed");
    }
  }

  @Test
  void leelaSaiStartupCommandFailureCannotPublishLoaded() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz(true);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      Lizzie.config.chkLzsaiEngineMem = true;
      Lizzie.config.autoLoadLzsaiEngineMem = true;
      Lizzie.config.txtLzsaiEngineMem = "256";
      String failedCommand = "lz-setoption name Maximum Memory Use (MiB) value 256";
      RuntimeException commandFailure =
          new IllegalStateException("controlled Leela-SAI startup failure");
      engine.pdaStartupCommandFailureCommand = failedCommand;
      engine.pdaStartupCommandFailure = commandFailure;
      int readyBaseline = env.readyTransitions.get();

      RuntimeException startupFailure =
          assertThrows(RuntimeException.class, () -> invokeParseLine(engine, "= Leela Zero"));

      assertSame(commandFailure, startupFailure.getCause());
      assertTrue(engine.startupCommandAttempts.contains(failedCommand));
      assertFalse(engine.isLoaded, "SAI post-work failure must not publish startup readiness");
      assertTrue(engine.isDownWithError, "SAI post-work failure must fail the exact runtime closed");
      assertEquals(0, env.readyTransitions.get() - readyBaseline);
    }
  }

  @Test
  void pdaPostActionRuntimeFailureReleasesStartupLifecycleWithoutReady() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz(true);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      Lizzie.engineStartupStatus.checking("engine.starting", "controlled PDA startup");
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(engine)));
      Lizzie.engineManager = manager;
      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      manager.synchronizeEngineWhenReady(engine, startup::run, startup::close);
      RuntimeException commandFailure =
          new IllegalStateException("controlled PDA startup command failure");
      engine.pdaStartupCommandFailureCommand = "getpda";
      engine.pdaStartupCommandFailure = commandFailure;
      int readyBaseline = env.readyTransitions.get();

      RuntimeException startupFailure =
          assertThrows(RuntimeException.class, () -> invokeParseLine(engine, "= KataGoPda"));

      assertSame(commandFailure, startupFailure.getCause());
      assertTrue(engine.startupCommandAttempts.contains("getpda"));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.synchronizationFailed.await(2, TimeUnit.SECONDS));
      assertEquals(
          0,
          env.readyTransitions.get() - readyBaseline,
          "failed startup must never publish READY");
      assertEquals(
          0,
          ((SilentStartupMenu) LizzieFrame.menu).readyPrimaryIconCount,
          "failed startup must never publish the ready icon");
      assertFalse(engine.isLoaded);
      assertTrue(engine.isDownWithError);
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void transactionlessSynchronizationFailurePresentationRejectsReplacedBoard()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz(true);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      Lizzie.engineStartupStatus.checking("engine.starting", "controlled PDA startup");
      ProductionEntryEngineManager manager =
          new ProductionEntryEngineManager(new ArrayList<>(List.of(engine)));
      manager.deferFailurePresentation = true;
      Lizzie.engineManager = manager;
      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      manager.synchronizeEngineWhenReady(engine, startup::run, startup::close);
      engine.pdaStartupCommandFailureCommand = "getpda";
      engine.pdaStartupCommandFailure =
          new IllegalStateException("controlled stale PDA startup failure");

      assertThrows(RuntimeException.class, () -> invokeParseLine(engine, "= KataGoPda"));
      assertTrue(manager.firstSynchronizationCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(manager.failurePresentationEnqueued.await(2, TimeUnit.SECONDS));
      assertNotNull(manager.deferredFailurePresentation);

      Lizzie.setBoard(boardWithHistory(emptyRootHistory(1)));
      manager.deferredFailurePresentation.run();

      assertEquals(0, manager.synchronizationFailureCount);
      assertEquals(1L, manager.synchronizationFailed.getCount());
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void blockedStartupCommandReleasesEndpointButFencesReaderRebind() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz(true);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      CountDownLatch commandEntered = new CountDownLatch(1);
      CountDownLatch releaseCommand = new CountDownLatch(1);
      AtomicBoolean blockOnce = new AtomicBoolean();
      engine.beforeCommand =
          command -> {
            if (command.equals("getpda") && blockOnce.compareAndSet(false, true)) {
              commandEntered.countDown();
              awaitLatch(releaseCommand);
            }
          };
      AtomicReference<Throwable> parserFailure = new AtomicReference<>();
      Thread parser =
          new Thread(
              () -> {
                try {
                  invokeParseLine(engine, "= KataGoPda");
                } catch (Throwable failure) {
                  parserFailure.set(failure);
                }
              },
              "blocked-startup-command-parser");
      parser.start();
      assertTrue(commandEntered.await(2, TimeUnit.SECONDS));

      AtomicBoolean endpointAcquired = new AtomicBoolean();
      Thread endpointProbe =
          new Thread(
              () -> {
                try {
                  synchronized (engineArbitrationLock(engine)) {
                    endpointAcquired.set(true);
                  }
                } catch (Exception failure) {
                  throw new AssertionError(failure);
                }
              },
              "startup-command-endpoint-probe");
      endpointProbe.start();
      endpointProbe.join(2_000L);
      assertFalse(endpointProbe.isAlive(), "blocked output must not retain the endpoint monitor");
      assertTrue(endpointAcquired.get());

      AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
      Thread rebind =
          new Thread(
              () -> {
                try {
                  engine.installFreshCommandOutputForTest(engine.commandTransport);
                } catch (Throwable failure) {
                  rebindFailure.set(failure);
                }
              },
              "startup-command-reader-rebind");
      rebind.start();
      assertTrue(
          awaitThreadState(rebind, Thread.State.WAITING, 2_000L),
          "the startup-post lease must fence rebind while startup output is blocked");

      releaseCommand.countDown();
      parser.join(2_000L);
      rebind.join(2_000L);
      assertFalse(parser.isAlive());
      assertFalse(rebind.isAlive());
      assertNull(parserFailure.get());
      assertNull(rebindFailure.get());
    }
  }

  @Test
  void startupPostCommandsCommitLoadedOnlyAfterExactPhysicalWritesBeforeRebind()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      Leelaz engine = new Leelaz("");
      GatedRecordingOutputStream oldOutput = new GatedRecordingOutputStream();
      ByteArrayOutputStream replacementOutput = new ByteArrayOutputStream();
      engine.installFreshCommandOutputForTest(oldOutput);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.engineManager = new EngineManager(new ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      Lizzie.engineStartupStatus.checking(
          "engine.starting", "controlled physical startup delivery");
      int readyBaseline = env.readyTransitions.get();

      AtomicReference<Throwable> writerFailure = new AtomicReference<>();
      Thread writer =
          new Thread(
              () -> {
                try {
                  engine.sendCommand("komi 6.5");
                } catch (Throwable failure) {
                  writerFailure.set(failure);
                }
              },
              "blocked-pre-name-command-writer");
      writer.start();
      assertTrue(oldOutput.firstFlushEntered.await(2, TimeUnit.SECONDS));

      AtomicReference<Throwable> parserFailure = new AtomicReference<>();
      Thread parser =
          new Thread(
              () -> {
                try {
                  invokeParseLine(engine, "= KataGo");
                } catch (Throwable failure) {
                  parserFailure.set(failure);
                }
              },
              "physical-startup-delivery-parser");
      parser.start();
      parser.join(2_000L);
      assertFalse(parser.isAlive(), "the stdout parser must not wait for its own command writes");
      assertNull(parserFailure.get());

      AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
      Thread rebind =
          new Thread(
              () -> {
                try {
                  engine.installFreshCommandOutputForTest(replacementOutput);
                  engine.bindCurrentPrimaryEngineGeneration();
                  engine.started = true;
                  engine.isLoaded = false;
                  engine.isCheckingName = true;
                } catch (Throwable failure) {
                  rebindFailure.set(failure);
                }
              },
              "startup-delivery-reader-rebind");
      rebind.start();
      assertTrue(
          awaitThreadState(rebind, Thread.State.WAITING, 2_000L),
          "reader rebind must wait for the exact startup-post lease");
      assertFalse(engine.isLoaded, "enqueue alone must not publish loaded");
      assertEquals(0, env.readyTransitions.get() - readyBaseline);

      oldOutput.releaseFirstFlush.countDown();
      writer.join(2_000L);
      rebind.join(2_000L);

      assertFalse(writer.isAlive());
      assertFalse(rebind.isAlive());
      assertNull(writerFailure.get());
      assertNull(rebindFailure.get());
      assertTrue(
          oldOutput.commands().stream()
              .anyMatch(
                  command ->
                      command.endsWith("kata-get-param playoutDoublingAdvantage")),
          "startup commands must be physically written to their source reader transport");
      assertEquals(
          "",
          replacementOutput.toString(StandardCharsets.UTF_8),
          "no queued startup command may cross into the replacement reader transport");
      assertFalse(engine.isLoaded, "the replacement reader must await its own name response");
      assertTrue(engine.isCheckingName);
    }
  }

  @Test
  void golaxyStartupDeliveryReturnsReaderBeforeResponseWatermarkOpens() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      Leelaz engine = new Leelaz("");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      engine.installFreshCommandOutputForTest(output);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.engineManager = new EngineManager(new ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      Lizzie.config.chkLzsaiEngineMem = true;
      Lizzie.config.autoLoadLzsaiEngineMem = true;
      Lizzie.config.txtLzsaiEngineMem = "256";
      String startupCommand = "lz-setoption name Maximum Memory Use (MiB) value 256";

      engine.sendCommand("version");
      engine.sendCommand("list_commands");
      invokeParseLine(engine, "= Golaxy");

      assertFalse(
          engine.isLoaded,
          "the parser must return without treating a response-gated enqueue as delivered");
      assertFalse(output.toString(StandardCharsets.UTF_8).contains(startupCommand));

      // These responses must be consumable by the same stdout reader that parsed the name line.
      // The second response opens requireResponseBeforeSend's watermark and dispatches the batch.
      engine.processCommandResponseLineForTest("=");
      engine.processCommandResponseLineForTest("=");

      assertTrue(awaitCondition(() -> engine.isLoaded, 2_000L));
      assertTrue(output.toString(StandardCharsets.UTF_8).contains(startupCommand));
    }
  }

  @Test
  void startupPostActionStartAfterThrowHasOneFailureOwnerAndNoLateCommands()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz(true);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.engineManager = new EngineManager(new ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      engine.startupPostActionWorkerStarted = new CountDownLatch(1);
      engine.startupPostActionWorkerGate = new CountDownLatch(1);
      Error dispatchFailure = new AssertionError("controlled start-after-throw");
      engine.startupPostActionDispatchFailureAfterStart = dispatchFailure;

      RuntimeException startupFailure =
          assertThrows(RuntimeException.class, () -> invokeParseLine(engine, "= KataGo"));
      assertSame(dispatchFailure, startupFailure.getCause());
      assertFalse(engine.isLoaded);
      assertTrue(engine.isDownWithError);

      engine.startupPostActionWorkerGate.countDown();
      engine.startupPostActionWorker.join(2_000L);

      assertFalse(engine.startupPostActionWorker.isAlive());
      assertNull(engine.startupPostActionWorkerFailure.get());
      assertTrue(
          engine.commands.isEmpty(),
          "the losing worker must not send commands after scheduling failure owns settlement");
    }
  }

  @Test
  void startupPhysicalWriteErrorSettlesLeaseAndFailsExactReaderClosed() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      InlineStartupPostLeelaz engine = new InlineStartupPostLeelaz();
      Error writeFailure = new AssertionError("controlled startup physical-write Error");
      engine.installFreshCommandOutputForTest(new ErrorOutputStream(writeFailure));
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.engineManager = new EngineManager(new ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;

      RuntimeException startupFailure =
          assertThrows(RuntimeException.class, () -> invokeParseLine(engine, "= KataGo"));

      assertSame(writeFailure, startupFailure.getCause());
      assertFalse(engine.isLoaded);
      assertTrue(engine.isDownWithError);

      engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      assertTrue(
          engine.currentEngineIncarnation() != null,
          "Error settlement must release the startup-post lease so rebind can complete");
    }
  }

  @Test
  void ordinaryPhysicalWriteErrorIsNotDowngradedByStartupSettlementBoundary()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      Leelaz engine = new Leelaz("");
      Error writeFailure = new AssertionError("controlled ordinary physical-write Error");
      engine.installFreshCommandOutputForTest(new ErrorOutputStream(writeFailure));
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      engine.started = true;

      Error observed = assertThrows(Error.class, () -> engine.sendCommand("komi 6.5"));

      assertSame(writeFailure, observed);
    }
  }

  @Test
  void asyncStartupCommandsCarryExactRestartBootstrapReceiptAcrossQueueGate() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      BootstrapReceiptLeelaz engine = new BootstrapReceiptLeelaz();
      installRestartBootstrapBinding(engine, engine.transport);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.engineManager = new EngineManager(new ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;

      invokeParseLine(engine, "= KataGo");

      assertTrue(awaitCondition(() -> engine.isLoaded, 2_000L));
      assertTrue(
          engine.startupPostActionCompleted.await(2, TimeUnit.SECONDS),
          "the async startup worker must finish before the fixture restores global state");
      assertTrue(
          engine.transport.commands().contains("kata-get-param playoutDoublingAdvantage"),
          "the async worker must physically write through the gated bootstrap receipt");
      assertFalse(engine.isDownWithError);
    }
  }

  @Test
  void staleRestartBootstrapReceiptAfterDequeueSettlesAndRetiresCount() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      BootstrapReceiptLeelaz engine = new BootstrapReceiptLeelaz();
      engine.blockBootstrapClaim = true;
      installRestartBootstrapBinding(engine, engine.transport);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.engineManager = new EngineManager(new ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      int readyBaseline = env.readyTransitions.get();

      invokeParseLine(engine, "= KataGo");
      assertTrue(engine.bootstrapClaimEntered.await(2, TimeUnit.SECONDS));
      synchronized (engineArbitrationLock(engine)) {
        setLeelazField(engine, "restartBootstrapReceipt", null);
      }
      engine.releaseBootstrapClaim.countDown();

      assertTrue(awaitCondition(() -> engine.isDownWithError, 2_000L));
      assertFalse(engine.isLoaded);
      assertEquals(0, env.readyTransitions.get() - readyBaseline);
      assertTrue(engine.transport.commands().isEmpty(), "a stale receipt must authorize zero bytes");
      assertEquals(1, ((Number) getLeelazField(engine, "cmdNumber")).intValue());

      engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      assertNotNull(engine.currentEngineIncarnation());
    }
  }

  @Test
  void startupWriteWatchdogClosesBlockedTransportAndReleasesRebind() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      ControlledStartupDeliveryLeelaz engine = new ControlledStartupDeliveryLeelaz();
      CloseReleasingOutputStream oldOutput = new CloseReleasingOutputStream();
      ByteArrayOutputStream replacementOutput = new ByteArrayOutputStream();
      engine.installFreshCommandOutputForTest(oldOutput);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.engineManager = new EngineManager(new ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      int readyBaseline = env.readyTransitions.get();

      invokeParseLine(engine, "= KataGo");
      assertTrue(oldOutput.flushEntered.await(2, TimeUnit.SECONDS));
      assertTrue(engine.timeoutScheduled.await(2, TimeUnit.SECONDS));

      AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
      Thread rebind =
          new Thread(
              () -> {
                try {
                  engine.installFreshCommandOutputForTest(replacementOutput);
                } catch (Throwable failure) {
                  rebindFailure.set(failure);
                }
              },
              "watchdog-startup-reader-rebind");
      rebind.start();
      assertTrue(awaitThreadState(rebind, Thread.State.WAITING, 2_000L));

      engine.fireStartupDeliveryTimeout();
      rebind.join(2_000L);

      assertFalse(rebind.isAlive(), "the watchdog must release the exact startup-post lease");
      assertNull(rebindFailure.get());
      assertTrue(awaitCondition(() -> oldOutput.closeCount.get() > 0, 2_000L));
      assertEquals(0, env.readyTransitions.get() - readyBaseline);
      assertFalse(engine.isLoaded);
      assertEquals("", replacementOutput.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void completedPhysicalWriteWinsAtomicallyAgainstLateStartupWatchdog() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      ControlledStartupDeliveryLeelaz engine = new ControlledStartupDeliveryLeelaz();
      GatedRecordingOutputStream output = new GatedRecordingOutputStream();
      engine.blockAbortClaim = true;
      engine.installFreshCommandOutputForTest(output);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.engineManager = new EngineManager(new ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;

      invokeParseLine(engine, "= KataGo");
      assertTrue(output.firstFlushEntered.await(2, TimeUnit.SECONDS));
      assertTrue(engine.timeoutScheduled.await(2, TimeUnit.SECONDS));

      Thread lateWatchdog =
          new Thread(engine::fireStartupDeliveryTimeout, "late-startup-delivery-watchdog");
      lateWatchdog.start();
      assertTrue(engine.abortClaimEntered.await(2, TimeUnit.SECONDS));

      try {
        output.releaseFirstFlush.countDown();
        assertTrue(awaitCondition(() -> engine.isLoaded, 2_000L));
        assertTrue(engine.startupPostActionCompleted.await(2, TimeUnit.SECONDS));
      } finally {
        engine.releaseAbortClaim.countDown();
      }
      lateWatchdog.join(2_000L);

      assertFalse(lateWatchdog.isAlive());
      assertTrue(engine.isLoaded, "a completed physical write must own the terminal outcome");
      assertFalse(engine.isDownWithError);
      assertFalse(output.commands().isEmpty());
    }
  }

  @Test
  void readerTerminationPromptlyCancelsUnsentStartupDelivery() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      ControlledStartupDeliveryLeelaz engine = new ControlledStartupDeliveryLeelaz();
      ByteArrayOutputStream oldOutput = new ByteArrayOutputStream();
      engine.installFreshCommandOutputForTest(oldOutput);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.engineManager = new EngineManager(new ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      engine.startupPostActionWorkerGate = new CountDownLatch(1);
      Object binding = engine.currentEngineIncarnation();
      int readyBaseline = env.readyTransitions.get();

      invokeParseLine(engine, "= KataGo");
      assertTrue(engine.startupPostActionWorkerEntered.await(2, TimeUnit.SECONDS));
      // KataGo classification intentionally disables Golaxy's response-watermark mode. Install the
      // deterministic unsent-command gate only after classification, while the async post worker is
      // still frozen, so this test exercises EOF cancellation rather than parser configuration.
      engine.requireResponseBeforeSend = true;
      setLeelazField(engine, "cmdNumber", 4);
      setLeelazField(engine, "currentCmdNum", 0);
      engine.startupPostActionWorkerGate.countDown();
      assertTrue(engine.outputWorkerEntered.await(2, TimeUnit.SECONDS));
      assertTrue(engine.timeoutScheduled.await(2, TimeUnit.SECONDS));
      invokeTerminateReaderIncarnation(engine, binding, null);

      ByteArrayOutputStream replacementOutput = new ByteArrayOutputStream();
      AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
      Thread rebind =
          new Thread(
              () -> {
                try {
                  engine.installFreshCommandOutputForTest(replacementOutput);
                } catch (Throwable failure) {
                  rebindFailure.set(failure);
                }
              },
              "eof-startup-reader-rebind");
      rebind.start();
      rebind.join(2_000L);

      assertFalse(rebind.isAlive(), "EOF cancellation must not wait for the delivery watchdog");
      assertNull(rebindFailure.get());
      assertEquals("", oldOutput.toString(StandardCharsets.UTF_8));
      assertEquals("", replacementOutput.toString(StandardCharsets.UTF_8));
      assertEquals(0, env.readyTransitions.get() - readyBaseline);
      assertFalse(engine.isLoaded);
    }
  }

  @Test
  void stateResetOwnsCountRetirementWhileStartupCancellationStillOccupiesQueue()
      throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      ControlledStartupDeliveryLeelaz engine = new ControlledStartupDeliveryLeelaz();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      engine.installFreshCommandOutputForTest(output);
      env.publish(engine, boardWithHistory(emptyRootHistory(0)));
      Lizzie.engineManager = new EngineManager(new ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      engine.startupPostActionWorkerGate = new CountDownLatch(1);
      engine.blockCancellationBeforeQueueRemoval = true;

      Thread watchdog = null;
      try {
        invokeParseLine(engine, "= KataGo");
        assertTrue(engine.startupPostActionWorkerEntered.await(2, TimeUnit.SECONDS));
        // Freeze the startup command in the ordinary queue without letting it claim output.
        engine.requireResponseBeforeSend = true;
        setLeelazField(engine, "cmdNumber", 4);
        setLeelazField(engine, "currentCmdNum", 0);
        engine.startupPostActionWorkerGate.countDown();
        assertTrue(engine.outputWorkerEntered.await(2, TimeUnit.SECONDS));
        assertTrue(engine.timeoutScheduled.await(2, TimeUnit.SECONDS));

        watchdog = new Thread(engine::fireStartupDeliveryTimeout, "startup-cancel-reset-race");
        watchdog.setDaemon(true);
        watchdog.start();
        assertTrue(engine.cancellationClaimed.await(2, TimeUnit.SECONDS));

        // Reset while the old command is cancelled but still visible in the queue, then establish
        // a new response-watermark baseline.  The old owner must not retire this baseline later.
        invokeResetGtpCommandStateAfterRestoreFailure(engine, "controlled cancellation race");
        engine.sendCommand("name");
        engine.sendCommand("version");
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("name\n"));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("version\n"));

        engine.releaseCancellationBeforeQueueRemoval.countDown();
        watchdog.join(2_000L);
        assertFalse(watchdog.isAlive());
        invokeTrySendCommandFromQueue(engine);

        assertEquals(3, ((Number) getLeelazField(engine, "cmdNumber")).intValue());
        assertFalse(
            output.toString(StandardCharsets.UTF_8).contains("version\n"),
            "late startup cancellation must not open the new response window");
      } finally {
        engine.startupPostActionWorkerGate.countDown();
        engine.releaseCancellationBeforeQueueRemoval.countDown();
        if (watchdog != null) {
          watchdog.join(2_000L);
        }
      }
    }
  }

  @Test
  void startupOutputStartAfterThrowHasSingleFailureOwnerAndNoLateWrite() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      ControlledStartupDeliveryLeelaz engine = new ControlledStartupDeliveryLeelaz();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      engine.installFreshCommandOutputForTest(output);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.engineManager = new EngineManager(new ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      engine.outputWorkerGate = new CountDownLatch(1);
      engine.outputDispatchFailureAfterStart =
          new AssertionError("controlled startup-output start-after-throw");

      invokeParseLine(engine, "= KataGo");
      assertTrue(engine.outputWorkerEntered.await(2, TimeUnit.SECONDS));
      assertTrue(awaitCondition(() -> engine.isDownWithError, 2_000L));

      engine.outputWorkerGate.countDown();
      engine.outputWorker.join(2_000L);

      assertFalse(engine.outputWorker.isAlive());
      assertEquals(
          "", output.toString(StandardCharsets.UTF_8), "the losing worker must write zero bytes");
      assertFalse(engine.isLoaded);
      engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      assertNotNull(engine.currentEngineIncarnation());
    }
  }

  @Test
  void physicalWriteFailureRemainsPrimaryWhenSettlementCallbackAlsoFails() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      Leelaz engine = new Leelaz("");
      Error writeFailure = new AssertionError("controlled physical flush failure");
      RuntimeException callbackFailure =
          new IllegalStateException("controlled settlement callback failure");
      engine.installFreshCommandOutputForTest(new ErrorOutputStream(writeFailure));
      env.publish(engine, boardWithHistory(emptyRootHistory(0)));
      engine.started = true;

      Error observed =
          assertThrows(
              Error.class,
              () ->
                  engine.sendCommandWithFailingStateResetCallbackForTest(
                      "komi 6.5", callbackFailure));

      assertSame(writeFailure, observed);
      assertTrue(
          List.of(writeFailure.getSuppressed()).contains(callbackFailure),
          "settlement failure must be suppressed on the physical write failure");
    }
  }

  @Test
  void startupPostCommandsNeverAcquireMirrorEndpoint() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz primary = new StartupSyncLeelaz(true);
      StartupSyncLeelaz secondary = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(primary, board);
      Lizzie.leelaz2 = secondary;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      EngineManager manager =
          new EngineManager(new ArrayList<>(List.of(primary, secondary)));
      Lizzie.engineManager = manager;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = 1;
      Lizzie.setPrimaryEngine(primary);
      primary.bindCurrentPrimaryEngineGeneration();
      primary.started = true;
      primary.isLoaded = false;
      primary.isCheckingName = true;
      secondary.started = true;
      secondary.isLoaded = true;
      CountDownLatch secondaryHeld = new CountDownLatch(1);
      CountDownLatch releaseSecondary = new CountDownLatch(1);
      Thread secondaryOwner =
          new Thread(
              () -> {
                try {
                  synchronized (engineArbitrationLock(secondary)) {
                    secondaryHeld.countDown();
                    awaitLatch(releaseSecondary);
                  }
                } catch (Exception failure) {
                  throw new AssertionError(failure);
                }
              },
              "startup-secondary-endpoint-owner");
      secondaryOwner.start();
      assertTrue(secondaryHeld.await(2, TimeUnit.SECONDS));
      AtomicReference<Throwable> parserFailure = new AtomicReference<>();
      Thread parser =
          new Thread(
              () -> {
                try {
                  invokeParseLine(primary, "= KataGo");
                } catch (Throwable failure) {
                  parserFailure.set(failure);
                }
              },
              "non-mirroring-startup-parser");
      parser.start();
      parser.join(2_000L);
      releaseSecondary.countDown();
      secondaryOwner.join(2_000L);

      assertFalse(parser.isAlive(), "startup post-work must not wait for the mirror endpoint");
      assertNull(parserFailure.get());
      assertTrue(secondary.commands.isEmpty(), "startup-only commands must never mirror");
    }
  }

  @Test
  void readyIconCallbackRejectsReaderRebindAfterPostSuccess() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz(true);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.engineManager = new EngineManager(new ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      CountDownLatch edtBlocked = new CountDownLatch(1);
      CountDownLatch releaseEdt = new CountDownLatch(1);
      SwingUtilities.invokeLater(
          () -> {
            edtBlocked.countDown();
            awaitLatch(releaseEdt);
          });
      assertTrue(edtBlocked.await(2, TimeUnit.SECONDS));

      invokeParseLine(engine, "= KataGo");
      engine.installFreshCommandOutputForTest(engine.commandTransport);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = true;
      releaseEdt.countDown();
      SwingUtilities.invokeAndWait(() -> {});

      assertEquals(
          0,
          ((SilentStartupMenu) LizzieFrame.menu).readyPrimaryIconCount,
          "a ready icon queued by the retired reader must not reach the replacement runtime");
    }
  }

  @Test
  void readyIconCallbackRejectsStoppedCurrentIncarnation() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz(true);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.engineManager = new EngineManager(new ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      CountDownLatch edtBlocked = new CountDownLatch(1);
      CountDownLatch releaseEdt = new CountDownLatch(1);
      SwingUtilities.invokeLater(
          () -> {
            edtBlocked.countDown();
            awaitLatch(releaseEdt);
          });
      assertTrue(edtBlocked.await(2, TimeUnit.SECONDS));

      invokeParseLine(engine, "= KataGo");
      engine.shutdown();
      releaseEdt.countDown();
      SwingUtilities.invokeAndWait(() -> {});

      assertEquals(
          0,
          ((SilentStartupMenu) LizzieFrame.menu).readyPrimaryIconCount,
          "a stopped binding must not receive its previously queued ready icon");
    }
  }

  @Test
  void parserReadyIconWaitsForStartupLifecycleSettlement() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz(true);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.engineManager = new EngineManager(new ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      try {
        invokeParseLine(engine, "= KataGo");
        SwingUtilities.invokeAndWait(() -> {});
        assertTrue(engine.isKatago, "the exact reader must reach the KataGo classifier branch");
        assertTrue(engine.isLoaded, "engine-local post-work should be complete");
        assertEquals(
            0,
            ((SilentStartupMenu) LizzieFrame.menu).readyPrimaryIconCount,
            "parser readiness must not outrun the startup lifecycle/final board fence");
      } finally {
        startup.close();
      }
      assertLifecycleReservationReleased(engine);
    }
  }

  @Test
  void nameMutationRuntimeFailureCannotPublishLoaded() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      ThrowingStartupConfig config = allocate(ThrowingStartupConfig.class);
      config.uiConfig = new org.json.JSONObject();
      config.firstLoadKataGo = true;
      config.txtKataEngineThreads = "1";
      config.saveFailure = new IllegalStateException("controlled startup config save failure");
      Lizzie.config = config;
      StartupSyncLeelaz engine = new StartupSyncLeelaz(true);
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      int readyBaseline = env.readyTransitions.get();

      RuntimeException startupFailure =
          assertThrows(RuntimeException.class, () -> invokeParseLine(engine, "= KataGo"));

      assertSame(config.saveFailure, startupFailure.getCause());
      assertFalse(engine.isLoaded, "classifier failure must not publish loaded");
      assertTrue(engine.isDownWithError, "classifier failure must fail the exact reader closed");
      assertEquals(0, env.readyTransitions.get() - readyBaseline);
      assertEquals(0, ((SilentStartupMenu) LizzieFrame.menu).readyPrimaryIconCount);
    }
  }

  @Test
  void terminalDiagnosticCallbackRejectsReplacementReader() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.engineManager = new EngineManager(new ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.installFreshCommandStreamsForTest(
          new ByteArrayInputStream("= KataGoPda\n".getBytes(StandardCharsets.UTF_8)),
          new ByteArrayOutputStream(),
          new ByteArrayInputStream(new byte[0]));
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      engine.pdaStartupCommandFailureCommand = "getpda";
      engine.pdaStartupCommandFailure =
          new IllegalStateException("controlled reader startup failure");
      CountDownLatch edtBlocked = new CountDownLatch(1);
      CountDownLatch releaseEdt = new CountDownLatch(1);
      SwingUtilities.invokeLater(
          () -> {
            edtBlocked.countDown();
            awaitLatch(releaseEdt);
          });
      assertTrue(edtBlocked.await(2, TimeUnit.SECONDS));

      AtomicReference<Throwable> readerFailure = new AtomicReference<>();
      Thread reader =
          new Thread(
              () -> {
                try {
                  invokeReaderLoop(engine);
                } catch (Throwable failure) {
                  readerFailure.set(failure);
                }
              },
              "failing-startup-reader");
      reader.start();
      reader.join(2_000L);
      assertFalse(reader.isAlive());
      assertNull(readerFailure.get());
      assertEquals(0, engine.diagnosticCount.get(), "diagnostic must still be queued on the EDT");

      engine.installFreshCommandOutputForTest(engine.commandTransport);
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = true;
      engine.isDownWithError = false;
      releaseEdt.countDown();
      SwingUtilities.invokeAndWait(() -> {});

      assertEquals(
          0,
          engine.diagnosticCount.get(),
          "the retired reader diagnostic must not be shown for its replacement");
    }
  }

  @Test
  void terminalDiagnosticPresentationLeaseSerializesReaderRebind() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      Lizzie.engineManager = new EngineManager(new ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.installFreshCommandStreamsForTest(
          new ByteArrayInputStream("= KataGoPda\n".getBytes(StandardCharsets.UTF_8)),
          new ByteArrayOutputStream(),
          new ByteArrayInputStream(new byte[0]));
      engine.bindCurrentPrimaryEngineGeneration();
      engine.started = true;
      engine.isLoaded = false;
      engine.isCheckingName = true;
      engine.pdaStartupCommandFailureCommand = "getpda";
      engine.pdaStartupCommandFailure =
          new IllegalStateException("controlled reader startup failure");
      engine.terminalDiagnosticEntered = new CountDownLatch(1);
      engine.terminalDiagnosticGate = new CountDownLatch(1);

      Thread reader = new Thread(() -> invokeReaderLoop(engine), "terminal-diagnostic-reader");
      reader.start();
      reader.join(2_000L);
      assertFalse(reader.isAlive());
      assertTrue(engine.terminalDiagnosticEntered.await(2, TimeUnit.SECONDS));

      AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
      Thread rebind =
          new Thread(
              () -> {
                try {
                  engine.installFreshCommandOutputForTest(engine.commandTransport);
                } catch (Throwable failure) {
                  rebindFailure.set(failure);
                }
              },
              "terminal-diagnostic-reader-rebind");
      try {
        rebind.start();
        assertTrue(
            awaitThreadState(rebind, Thread.State.WAITING, 2_000L),
            "same-object rebind must wait for the claimed diagnostic presentation");
      } finally {
        engine.terminalDiagnosticGate.countDown();
      }
      rebind.join(2_000L);
      SwingUtilities.invokeAndWait(() -> {});

      assertFalse(rebind.isAlive());
      assertNull(rebindFailure.get());
      assertEquals(1, engine.diagnosticCount.get());
    }
  }

  @Test
  void conflictingLifecycleWorkIsRejectedWhileBarrierActive() throws Exception {
    try (StartupTestEnvironment env = StartupTestEnvironment.open()) {
      StartupSyncLeelaz engine = new StartupSyncLeelaz();
      Board board = boardWithHistory(emptyRootHistory(0));
      env.publish(engine, board);
      engine.startEngine(0);

      EngineManager.InitialEngineStartupSynchronization startup = captureStartup(engine, board);
      try {
        assertNull(
            engine.beginExclusiveGtpLifecycleReservation(new Object()),
            "a different lifecycle owner must not grab the engine during initial synchronization");
      } finally {
        startup.close();
      }
      assertLifecycleReservationReleased(engine);
    }
  }

  private static EngineManager.InitialEngineStartupSynchronization captureStartup(
      StartupSyncLeelaz engine, Board board) {
    return EngineManager.InitialEngineStartupSynchronization.capture(engine, board, false);
  }

  private static void runStartupInThread(
      EngineManager.InitialEngineStartupSynchronization startup, StartupSyncLeelaz engine)
      throws Exception {
    RuntimeException failure = runStartupExpectingFailure(startup, engine);
    if (failure != null) {
      throw failure;
    }
  }

  private static RuntimeException runStartupExpectingFailure(
      EngineManager.InitialEngineStartupSynchronization startup, StartupSyncLeelaz engine)
      throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread barrierThread =
        new Thread(
            () -> {
              try {
                startup.run();
              } catch (Throwable thrown) {
                // Mirrors the production startup thread: fail closed, then release the barrier.
                engine.isLoaded = false;
                failure.set(thrown);
              } finally {
                startup.close();
              }
            },
            "initial-startup-barrier-test");
    barrierThread.start();
    barrierThread.join(15_000L);
    assertFalse(barrierThread.isAlive(), "startup barrier did not settle within timeout");
    Throwable thrown = failure.get();
    if (thrown instanceof RuntimeException) {
      return (RuntimeException) thrown;
    }
    if (thrown != null) {
      throw new AssertionError("startup barrier failed unexpectedly", thrown);
    }
    return null;
  }

  private static void assertLifecycleReservationReleased(StartupSyncLeelaz engine) {
    assertTrue(
        engine.submitOrdinaryLiveBoardForwarding(
            EngineManager.OrdinaryLiveBoardForwardingIntent.of(() -> true)),
        "ordinary live-board forwarding must reopen after the barrier ends");
    Leelaz.ExclusiveGtpLifecycleReservation reservation =
        engine.beginExclusiveGtpLifecycleReservation();
    assertNotNull(reservation, "lifecycle reservation must be released after the barrier");
    reservation.close();
  }

  private static Object managerAtomicReferenceValue(EngineManager manager, String fieldName)
      throws Exception {
    Field field = EngineManager.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return ((java.util.concurrent.atomic.AtomicReference<?>) field.get(manager)).get();
  }

  private static Object advanceEngineIncarnationForTest(Leelaz engine) throws Exception {
    Field binding = Leelaz.class.getDeclaredField("readerStreamBinding");
    binding.setAccessible(true);
    binding.set(engine, null);
    return engine.currentEngineIncarnation();
  }

  private static void invokeCloseBundledStartupDialog(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("closeBundledStartupDialog");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static void invokeParseLine(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("parseLine", String.class);
    method.setAccessible(true);
    try {
      method.invoke(engine, line);
    } catch (InvocationTargetException wrapped) {
      Throwable cause = wrapped.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new AssertionError("parseLine failed without a throwable cause", wrapped);
    }
  }

  private static void invokeResetGtpCommandStateAfterRestoreFailure(
      Leelaz engine, String detail) throws Exception {
    Method method =
        Leelaz.class.getDeclaredMethod("resetGtpCommandStateAfterRestoreFailure", String.class);
    method.setAccessible(true);
    method.invoke(engine, detail);
  }

  private static void invokeTrySendCommandFromQueue(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("trySendCommandFromQueue");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static void invokeReaderLoop(Leelaz engine) {
    try {
      Method method = Leelaz.class.getDeclaredMethod("read");
      method.setAccessible(true);
      method.invoke(engine);
    } catch (InvocationTargetException wrapped) {
      Throwable cause = wrapped.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new AssertionError("reader loop failed", cause);
    } catch (ReflectiveOperationException reflectionFailure) {
      throw new AssertionError(reflectionFailure);
    }
  }

  private static boolean awaitThreadState(Thread thread, Thread.State state, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline) {
      if (thread.getState() == state) {
        return true;
      }
      Thread.sleep(5L);
    }
    return thread.getState() == state;
  }

  private static boolean awaitCondition(
      java.util.function.BooleanSupplier condition, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(5L);
    }
    return condition.getAsBoolean();
  }

  private static Object engineArbitrationLock(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("engineArbitrationLock");
    field.setAccessible(true);
    return field.get(engine);
  }

  private static void setLeelazField(Leelaz engine, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(engine, value);
  }

  private static Object getLeelazField(Leelaz engine, String name) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(engine);
  }

  private static void installRestartBootstrapBinding(Leelaz engine, OutputStream output)
      throws Exception {
    Object lifecycleOwner = new Object();
    setLeelazField(engine, "exclusiveGtpLifecycleTransition", true);
    setLeelazField(engine, "exclusiveGtpLifecycleQueueGate", true);
    setLeelazField(engine, "exclusiveGtpLifecycleOwner", lifecycleOwner);
    setLeelazField(engine, "exclusiveGtpLifecycleDepth", 1);
    engine.installFreshCommandOutputForTest(output);
    assertNotNull(
        getLeelazField(engine, "restartBootstrapReceipt"),
        "the controlled binding must carry a restart-bootstrap receipt");
  }

  private static void invokeTerminateReaderIncarnation(
      Leelaz engine, Object binding, Throwable failure) throws Exception {
    Method terminate =
        Leelaz.class.getDeclaredMethod(
            "terminateReaderIncarnation", binding.getClass(), Throwable.class);
    terminate.setAccessible(true);
    try {
      terminate.invoke(engine, binding, failure);
    } catch (InvocationTargetException invocationFailure) {
      Throwable cause = invocationFailure.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw invocationFailure;
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for overwrite forwarding latch");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("overwrite forwarding latch interrupted", interrupted);
    }
  }

  @FunctionalInterface
  private interface HistoryOverwriteMutation {
    void apply(Board board);
  }

  private static Stream<Arguments> historyOverwriteMutations() {
    return Stream.of(
        Arguments.of("clear", (HistoryOverwriteMutation) board -> board.clear(false)),
        Arguments.of("clearForOnline", (HistoryOverwriteMutation) Board::clearForOnline),
        Arguments.of("clearforedit", (HistoryOverwriteMutation) Board::clearforedit),
        Arguments.of("reopen", (HistoryOverwriteMutation) board -> board.reopen(9, 9)),
        Arguments.of(
            "flattenWithKomi",
            (HistoryOverwriteMutation) board -> flattenWithExtraStones(board, true)),
        Arguments.of(
            "flattenExtraStones",
            (HistoryOverwriteMutation) board -> flattenWithExtraStones(board, false)));
  }

  private static void flattenWithExtraStones(Board board, boolean withKomi) {
    Stone[] stones = board.getHistory().getStones().clone();
    Zobrist zobrist = board.getHistory().getZobrist().clone();
    extraMoveForTsumego extra = new extraMoveForTsumego();
    extra.x = 3;
    extra.y = 3;
    extra.color = Stone.BLACK;
    if (withKomi) {
      board.flattenWithCondition(stones, zobrist, true, List.of(extra), 7.5);
    } else {
      board.flattenWithCondition(stones, zobrist, true, List.of(extra));
    }
  }

  private static void sendFailOnErrorCommand(Leelaz engine, String command) throws Exception {
    Method method =
        Leelaz.class.getDeclaredMethod(
            "sendCommand", String.class, Runnable.class, boolean.class, boolean.class);
    method.setAccessible(true);
    method.invoke(engine, command, null, true, false);
  }

  private static void queueFailingCommandForLifecycleRelease(Leelaz engine, String command)
      throws Exception {
    int previousCommandCount = ((Number) getLeelazField(engine, "cmdNumber")).intValue();
    // Model ordinary work that was already queued when the lifecycle gate closed. Commands first
    // admitted after that gate closes now require an exact restart receipt and are intentionally
    // rejected, so they cannot exercise reservation-close failure propagation.
    setLeelazField(engine, "normalCommandSendInProgress", true);
    try {
      sendFailOnErrorCommand(engine, command);
      setLeelazField(engine, "exclusiveGtpLifecycleQueueGate", true);
    } finally {
      setLeelazField(engine, "normalCommandSendInProgress", false);
    }
    assertEquals(
        previousCommandCount + 1,
        ((Number) getLeelazField(engine, "cmdNumber")).intValue(),
        "the controlled command must be queued before the lifecycle gate closes");
  }

  private static final class FailingOutputStream extends OutputStream {
    private final String message;

    private FailingOutputStream() {
      this("controlled startup send failure");
    }

    private FailingOutputStream(String message) {
      this.message = message;
    }

    @Override
    public void write(int value) {}

    @Override
    public void flush() throws IOException {
      throw new IOException(message);
    }
  }

  private static final class GatedRecordingOutputStream extends OutputStream {
    private final CountDownLatch firstFlushEntered = new CountDownLatch(1);
    private final CountDownLatch releaseFirstFlush = new CountDownLatch(1);
    private final AtomicBoolean firstFlush = new AtomicBoolean(true);
    private final StringBuilder pending = new StringBuilder();
    private final List<String> commands = new ArrayList<>();

    @Override
    public synchronized void write(int value) {
      pending.append((char) value);
    }

    @Override
    public void flush() {
      if (firstFlush.compareAndSet(true, false)) {
        firstFlushEntered.countDown();
        awaitLatch(releaseFirstFlush);
      }
      synchronized (this) {
        String command = pending.toString().trim();
        pending.setLength(0);
        if (!command.isEmpty()) {
          commands.add(command);
        }
      }
    }

    private synchronized List<String> commands() {
      return List.copyOf(commands);
    }
  }

  private static final class BootstrapReceiptLeelaz extends Leelaz {
    private final ExactSnapshotRestoreProtocolFixture.Transport transport;
    private final CountDownLatch bootstrapClaimEntered = new CountDownLatch(1);
    private final CountDownLatch releaseBootstrapClaim = new CountDownLatch(1);
    private final CountDownLatch startupPostActionCompleted = new CountDownLatch(1);
    private final AtomicBoolean claimBlocked = new AtomicBoolean();
    private volatile boolean blockBootstrapClaim;

    private BootstrapReceiptLeelaz() throws IOException {
      super("");
      transport =
          ExactSnapshotRestoreProtocolFixture.install(
              this, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
    }

    @Override
    void beforeRestartBootstrapOutputWriteClaim() {
      if (blockBootstrapClaim && claimBlocked.compareAndSet(false, true)) {
        bootstrapClaimEntered.countDown();
        awaitLatch(releaseBootstrapClaim);
      }
    }

    @Override
    void dispatchStartupPostActionWorker(Runnable worker) {
      super.dispatchStartupPostActionWorker(
          () -> {
            try {
              worker.run();
            } finally {
              startupPostActionCompleted.countDown();
            }
          });
    }
  }

  private static final class ControlledStartupDeliveryLeelaz extends Leelaz {
    private final CountDownLatch timeoutScheduled = new CountDownLatch(1);
    private final CountDownLatch outputWorkerEntered = new CountDownLatch(1);
    private final CountDownLatch abortClaimEntered = new CountDownLatch(1);
    private final CountDownLatch releaseAbortClaim = new CountDownLatch(1);
    private final CountDownLatch cancellationClaimed = new CountDownLatch(1);
    private final CountDownLatch releaseCancellationBeforeQueueRemoval = new CountDownLatch(1);
    private final CountDownLatch startupPostActionCompleted = new CountDownLatch(1);
    private final CountDownLatch startupPostActionWorkerEntered = new CountDownLatch(1);
    private final AtomicReference<Runnable> controlledTimeout = new AtomicReference<>();
    private final AtomicBoolean abortClaimBlocked = new AtomicBoolean();
    private volatile Error outputDispatchFailureAfterStart;
    private volatile CountDownLatch outputWorkerGate;
    private volatile CountDownLatch startupPostActionWorkerGate;
    private volatile Thread outputWorker;
    private volatile boolean blockAbortClaim;
    private volatile boolean blockCancellationBeforeQueueRemoval;

    private ControlledStartupDeliveryLeelaz() throws IOException {
      super("");
    }

    @Override
    void dispatchStartupCommandTimeout(Runnable timeout) {
      controlledTimeout.set(timeout);
      timeoutScheduled.countDown();
    }

    @Override
    void beforeStartupCommandAbortClaim() {
      if (blockAbortClaim && abortClaimBlocked.compareAndSet(false, true)) {
        abortClaimEntered.countDown();
        awaitLatch(releaseAbortClaim);
      }
    }

    @Override
    void afterStartupCommandCancellationClaimBeforeQueueRemoval() {
      if (blockCancellationBeforeQueueRemoval) {
        cancellationClaimed.countDown();
        awaitLatch(releaseCancellationBeforeQueueRemoval);
      }
    }

    @Override
    void dispatchStartupPostActionWorker(Runnable worker) {
      super.dispatchStartupPostActionWorker(
          () -> {
            startupPostActionWorkerEntered.countDown();
            CountDownLatch gate = startupPostActionWorkerGate;
            if (gate != null) {
              awaitLatch(gate);
            }
            try {
              worker.run();
            } finally {
              startupPostActionCompleted.countDown();
            }
          });
    }

    @Override
    void dispatchStartupCommandOutputWorker(Runnable output) {
      if (outputDispatchFailureAfterStart != null) {
        outputWorker =
            new Thread(
                () -> {
                  outputWorkerEntered.countDown();
                  CountDownLatch gate = outputWorkerGate;
                  if (gate != null) {
                    awaitLatch(gate);
                  }
                  output.run();
                },
                "controlled-startup-output-worker");
        outputWorker.start();
        try {
          if (!outputWorkerEntered.await(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("controlled startup output worker did not start");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("controlled startup output dispatch interrupted", interrupted);
        }
        throw outputDispatchFailureAfterStart;
      }
      super.dispatchStartupCommandOutputWorker(
          () -> {
            outputWorkerEntered.countDown();
            output.run();
          });
    }

    @Override
    long startupCommandDeliveryTimeoutMillis() {
      return 1L;
    }

    private void fireStartupDeliveryTimeout() {
      Runnable timeout = controlledTimeout.get();
      assertNotNull(timeout, "startup delivery timeout was not armed before output dispatch");
      timeout.run();
    }
  }

  private static final class CloseReleasingOutputStream extends OutputStream {
    private final CountDownLatch flushEntered = new CountDownLatch(1);
    private final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicInteger closeCount = new AtomicInteger();

    @Override
    public void write(int value) {}

    @Override
    public void flush() throws IOException {
      flushEntered.countDown();
      try {
        if (!closed.await(10, TimeUnit.SECONDS)) {
          throw new IOException("controlled startup output was not closed");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("controlled startup flush interrupted", interrupted);
      }
      throw new IOException("controlled startup output closed by watchdog");
    }

    @Override
    public void close() {
      closeCount.incrementAndGet();
      closed.countDown();
    }
  }

  private static final class ErrorOutputStream extends OutputStream {
    private final Error failure;

    private ErrorOutputStream(Error failure) {
      this.failure = failure;
    }

    @Override
    public void write(int value) {
      throw failure;
    }
  }

  private static final class InlineStartupPostLeelaz extends Leelaz {
    private InlineStartupPostLeelaz() throws IOException {
      super("");
    }

    @Override
    void dispatchStartupPostActionWorker(Runnable worker) {
      worker.run();
    }
  }

  private static Board boardWithHistory(BoardHistoryList history) throws Exception {
    Board board = allocate(StartupTestBoard.class);
    board.startStonelist = new ArrayList<>();
    board.movelistwr = new ArrayList<>();
    board.hasStartStone = false;
    board.setHistory(history);
    return board;
  }

  private static BlockingPrepareBoard blockingBoardWithHistory(BoardHistoryList history)
      throws Exception {
    BlockingPrepareBoard board = allocate(BlockingPrepareBoard.class);
    board.startStonelist = new ArrayList<>();
    board.movelistwr = new ArrayList<>();
    board.hasStartStone = false;
    board.historyReadEntered = new CountDownLatch(1);
    board.allowHistoryRead = new CountDownLatch(1);
    board.setHistory(history);
    board.blockHistoryRead = true;
    return board;
  }

  private static BoardHistoryList emptyRootHistory(int moveCount) {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(19, 19));
    history.getGameInfo().setKomiNoMenu(6.5);
    for (int move = 1; move <= moveCount; move++) {
      int x = 3 + move;
      int y = 3;
      Stone color = move % 2 == 1 ? Stone.BLACK : Stone.WHITE;
      history.add(moveNode(history.getData(), x, y, color, color != Stone.BLACK, move));
    }
    return history;
  }

  /** Parses the anonymized 205-move Issue #223 reporter game through the production SGF parser. */
  private static BoardHistoryList loadReporterGameFixture() throws Exception {
    java.net.URL fixtureResource =
        EngineManagerInitialStartupSynchronizationTest.class.getResource(
            "/featurecat/lizzie/rules/issue223-reporter-205-moves.sgf");
    if (fixtureResource == null) {
      throw new IllegalStateException("Issue #223 reporter SGF fixture must be available.");
    }
    Lizzie.leelaz = new Leelaz("");
    Lizzie.board = boardWithHistory(emptyRootHistory(0));
    Lizzie.config.readKomi = true;
    Board.boardWidth = 19;
    Board.boardHeight = 19;
    Zobrist.init();
    return SGFParser.parseSgf(Files.readString(Path.of(fixtureResource.toURI())), true);
  }

  private static BoardHistoryNode mainLineNodeAtMove(BoardHistoryList history, int moveNumber) {
    BoardHistoryNode node = history.getStart();
    for (int move = 0; move < moveNumber; move++) {
      node = node.next().orElseThrow(() -> new IllegalStateException("missing reporter move"));
    }
    return node;
  }

  /**
   * Loads the Issue #223 removed-stone SNAPSHOT fixture through the real SGF parser, matching the
   * spec's {@code exact-snapshot-manual-test.sgf} shape: two real moves, then a mid-history {@code
   * SNAPSHOT} node carrying {@code AB/AE/PL} (the {@code AE} removes a stone that was really played
   * earlier), followed by a real MOVE and PASS tail.
   *
   * <p>Parsed history: root SNAPSHOT -> MOVE B(3,3) -> MOVE W(4,4) -> SNAPSHOT (AB B(5,3), AW
   * W(5,5), removed (3,3), PL[W]) -> MOVE W(7,7) -> PASS B.
   */
  private static BoardHistoryList loadRemovedStoneSnapshotFixture() throws Exception {
    java.net.URL fixtureResource =
        EngineManagerInitialStartupSynchronizationTest.class.getResource(
            "/featurecat/lizzie/rules/issue223-snapshot-removed-stone.sgf");
    if (fixtureResource == null) {
      throw new IllegalStateException(
          "Issue #223 removed-stone snapshot fixture must be available.");
    }
    String sgf = Files.readString(Path.of(fixtureResource.toURI()));
    // SGFParser requires a live board and engine context for its parse-side effects, and the KM
    // tag only reaches game info when readKomi is enabled (the production default).
    Lizzie.leelaz = new Leelaz("");
    Lizzie.board = boardWithHistory(emptyRootHistory(0));
    Lizzie.config.readKomi = true;
    Board.boardWidth = 19;
    Board.boardHeight = 19;
    Zobrist.init();
    // first=true mirrors the production load path, which is the only path that propagates the KM
    // tag into game info (parseValue gates KM handling on firstTime).
    return SGFParser.parseSgf(sgf, true);
  }

  /** Asserts the parsed fixture's mid-history removed-stone SNAPSHOT keeps its AE/PL shape. */
  private static void assertRemovedStoneSnapshotShape(BoardHistoryList history) {
    BoardHistoryNode snapshotNode =
        history.getStart().next().orElseThrow().next().orElseThrow().next().orElseThrow();
    BoardData data = snapshotNode.getData();
    assertTrue(data.isSnapshotNode(), "fixture node 3 must be a SNAPSHOT");
    assertTrue(
        snapshotNode.hasRemovedStone(),
        "fixture SNAPSHOT must carry the AE removed-stone marker");
    assertFalse(data.blackToPlay, "fixture SNAPSHOT must carry PL[W]");
    assertEquals(
        Stone.EMPTY,
        data.stones[Board.getIndex(3, 3)],
        "the AE-removed stone must be gone from the SNAPSHOT position");
    assertEquals(
        Stone.BLACK,
        data.stones[Board.getIndex(5, 3)],
        "AB[fd] must place the black stone in the SNAPSHOT position");
    assertEquals(
        Stone.WHITE,
        data.stones[Board.getIndex(5, 5)],
        "AW[ff] must place the white stone in the SNAPSHOT position");
    assertEquals(
        Stone.WHITE,
        data.stones[Board.getIndex(4, 4)],
        "the earlier white stone must remain in the SNAPSHOT position");
  }

  private static BoardHistoryList snapshotHistoryWithTail(boolean withPass) {
    BoardData root = snapshotRoot();
    BoardHistoryList history = new BoardHistoryList(root);
    history.getGameInfo().setKomiNoMenu(6.5);
    Stone[] tailStones = root.stones.clone();
    tailStones[Board.getIndex(5, 5)] = Stone.BLACK;
    history.add(
        BoardData.move(
            tailStones,
            new int[] {5, 5},
            Stone.BLACK,
            false,
            new Zobrist(77L),
            4,
            new int[19 * 19],
            0,
            0,
            50,
            0));
    if (withPass) {
      Stone[] passStones = tailStones.clone();
      history.add(
          BoardData.pass(
              passStones, Stone.WHITE, true, new Zobrist(88L), 5, new int[19 * 19], 0, 0, 50, 0));
    }
    return history;
  }

  private static BoardData snapshotRoot() {
    Stone[] stones = new Stone[19 * 19];
    Arrays.fill(stones, Stone.EMPTY);
    stones[Board.getIndex(3, 3)] = Stone.BLACK;
    stones[Board.getIndex(4, 4)] = Stone.WHITE;
    int[] moveNumberList = new int[19 * 19];
    moveNumberList[Board.getIndex(3, 3)] = 1;
    moveNumberList[Board.getIndex(4, 4)] = 2;
    return BoardData.snapshot(
        stones,
        Optional.of(new int[] {4, 4}),
        Stone.WHITE,
        false,
        new Zobrist(42L),
        3,
        moveNumberList,
        0,
        0,
        50,
        0);
  }

  private static BoardData moveNode(
      BoardData parent, int x, int y, Stone color, boolean blackToPlay, int moveNumber) {
    Stone[] stones = parent.stones.clone();
    stones[Board.getIndex(x, y)] = color;
    int[] moveNumberList = parent.moveNumberList.clone();
    moveNumberList[Board.getIndex(x, y)] = moveNumber;
    Zobrist zobrist = parent.zobrist.clone();
    zobrist.toggleStone(x, y, color);
    return BoardData.move(
        stones,
        new int[] {x, y},
        color,
        blackToPlay,
        zobrist,
        moveNumber,
        moveNumberList,
        0,
        0,
        50,
        0);
  }

  private static void assertEngineMatchesBoard(
      StartupSyncLeelaz engine, Board board, int expectedWidth, int expectedHeight) {
    BoardData application = board.getHistory().getData();
    assertEquals(application.blackToPlay, engine.engineBlackToPlay, "side-to-play must match");
    assertEquals(expectedWidth, engine.engineBoardWidth, "board width must match");
    assertEquals(expectedHeight, engine.engineBoardHeight, "board height must match");
    assertEquals(
        board.getHistory().getGameInfo().getKomi(), engine.engineKomi, 0.0001, "komi must match");
    for (int x = 0; x < expectedWidth; x++) {
      for (int y = 0; y < expectedHeight; y++) {
        assertEquals(
            application.stones[Board.getIndex(x, y)],
            engine.stoneAt(x, y),
            "stone mismatch at " + x + "," + y);
      }
    }
  }

  private static void assertEngineMatchesBoardWithoutKomi(
      StartupSyncLeelaz engine, Board board, int expectedWidth, int expectedHeight) {
    BoardData application = board.getHistory().getData();
    assertEquals(application.blackToPlay, engine.engineBlackToPlay, "side-to-play must match");
    assertEquals(expectedWidth, engine.engineBoardWidth, "board width must match");
    assertEquals(expectedHeight, engine.engineBoardHeight, "board height must match");
    for (int x = 0; x < expectedWidth; x++) {
      for (int y = 0; y < expectedHeight; y++) {
        assertEquals(
            application.stones[Board.getIndex(x, y)],
            engine.stoneAt(x, y),
            "stone mismatch at " + x + "," + y);
      }
    }
  }

  private static String play(String color, int x, int y) {
    return "play " + color + " " + Board.convertCoordinatesToName(x, y);
  }

  private static String sgfCoord(int x, int y) {
    return "" + (char) ('a' + x) + (char) ('a' + y);
  }

  @FunctionalInterface
  private interface CommandDelay {
    void beforeCommand(String command) throws Exception;
  }

  private static class StartupSyncLeelaz extends Leelaz {
    private final List<String> commands = new ArrayList<>();
    private final List<String> loadedSgfContents = new ArrayList<>();
    private final AtomicInteger enginePosition = new AtomicInteger();
    private final AtomicInteger analyzePosition = new AtomicInteger(-1);
    private final AtomicInteger analyzeCount = new AtomicInteger();
    private final AtomicInteger diagnosticCount = new AtomicInteger();
    private final AtomicInteger loadSgfCount = new AtomicInteger();
    private final AtomicInteger clearBoardCount = new AtomicInteger();
    private final Map<String, Stone> engineStones = new HashMap<>();
    private final List<String> lifecycleEvents = new ArrayList<>();
    private final CountDownLatch startCompleted = new CountDownLatch(1);
    private final CountDownLatch startEntered = new CountDownLatch(1);
    private final CountDownLatch analysisStarted = new CountDownLatch(1);
    private final CountDownLatch boardSynchronizationEntered = new CountDownLatch(1);
    private final CountDownLatch boardSynchronizationCallbackEntered = new CountDownLatch(1);
    private final CountDownLatch boardSynchronizationCallbackCompleted = new CountDownLatch(1);
    private CommandDelay beforeCommand;
    private int snapshotBaseMove;
    private int failLoadSgfAt = Integer.MAX_VALUE;
    private int ponderCount;
    private int responseFreshenedCount;
    private int boardSynchronizationConfirmations;
    private int engineBoardWidth = 19;
    private int engineBoardHeight = 19;
    private double engineKomi = -1.0;
    private boolean engineBlackToPlay = true;
    private boolean delayReadyAfterStart;
    private CountDownLatch startReturnGate;
    private RuntimeException reservationFailure;
    private boolean rejectReservation;
    private RuntimeException ponderFailure;
    private CountDownLatch ponderCommandSent;
    private CountDownLatch ponderAfterCommandGate;
    private String boardSynchronizationFailure;
    private Error boardSynchronizationError;
    private Error boardSynchronizationPostRegistrationError;
    private CountDownLatch boardSynchronizationGate;
    private boolean confirmBoardSynchronizationOnEdt;
    private boolean confirmBoardSynchronizationOnDedicatedThread;
    private volatile Thread boardSynchronizationCallbackThread;
    private volatile Runnable lateBoardSynchronizationSuccess;
    private int notPonderingCount;
    private int normalQuitCount;
    private volatile Thread normalQuitThread;
    private CountDownLatch normalQuitEntered;
    private CountDownLatch normalQuitGate;
    private CountDownLatch normalQuitCompleted;
    private long normalQuitGateTimeoutMillis = TimeUnit.SECONDS.toMillis(5);
    private CountDownLatch analysisOutputRecoveryCompleted;
    private CountDownLatch incarnationRetirementEntered;
    private CountDownLatch incarnationRetirementGate;
    private final ExactSnapshotRestoreProtocolFixture.Transport commandTransport;
    private final List<Runnable> pdaStartupTimeouts = new ArrayList<>();
    private final List<String> startupCommandAttempts = new ArrayList<>();
    private RuntimeException pdaStartupTimeoutSchedulingFailure;
    private String pdaStartupCommandFailureCommand;
    private RuntimeException pdaStartupCommandFailure;
    private boolean asynchronousStartupPostAction;
    private Error startupPostActionDispatchFailureAfterStart;
    private CountDownLatch startupPostActionWorkerGate;
    private CountDownLatch startupPostActionWorkerStarted;
    private Thread startupPostActionWorker;
    private final AtomicReference<Throwable> startupPostActionWorkerFailure =
        new AtomicReference<>();
    private CountDownLatch terminalDiagnosticEntered;
    private CountDownLatch terminalDiagnosticGate;

    private StartupSyncLeelaz() throws Exception {
      this(false);
    }

    private StartupSyncLeelaz(boolean installParserReader) throws Exception {
      super("");
      commandTransport =
          ExactSnapshotRestoreProtocolFixture.install(
              this,
              command -> {
                if (beforeCommand != null) {
                  beforeCommand.beforeCommand(command);
                }
                commands.add(command);
                if (command.startsWith("play ")) {
                  enginePosition.incrementAndGet();
                  String[] parts = command.split("\\s+");
                  if (!"pass".equalsIgnoreCase(parts[2])) {
                    engineStones.put(
                        parts[2].toUpperCase(Locale.ROOT),
                        "B".equalsIgnoreCase(parts[1]) ? Stone.BLACK : Stone.WHITE);
                  }
                  engineBlackToPlay = "W".equalsIgnoreCase(parts[1]);
                } else if (command.equals("clear_board")) {
                  clearBoardCount.incrementAndGet();
                  enginePosition.set(0);
                  engineStones.clear();
                  engineBlackToPlay = true;
                } else if (command.startsWith("boardsize ")) {
                  engineBoardWidth =
                      Integer.parseInt(command.substring("boardsize ".length()).trim());
                  engineBoardHeight = engineBoardWidth;
                  engineStones.clear();
                  engineBlackToPlay = true;
                } else if (command.startsWith("rectangular_boardsize ")) {
                  String[] parts = command.split("\\s+");
                  engineBoardWidth = Integer.parseInt(parts[1]);
                  engineBoardHeight = Integer.parseInt(parts[2]);
                  engineStones.clear();
                  engineBlackToPlay = true;
                } else if (command.startsWith("komi ")) {
                  engineKomi = Double.parseDouble(command.substring("komi ".length()).trim());
                } else if (command.startsWith("loadsgf ")) {
                  int count = loadSgfCount.incrementAndGet();
                  enginePosition.set(snapshotBaseMove);
                  if (count >= failLoadSgfAt) {
                    return ExactSnapshotRestoreProtocolFixture.Response.error(
                        "controlled startup restore failure");
                  }
                  String sgfPath = command.substring("loadsgf ".length()).trim();
                  try {
                    String sgf = Files.readString(Path.of(sgfPath));
                    loadedSgfContents.add(sgf);
                    applySnapshotSgf(sgf);
                  } catch (IOException ioFailure) {
                    return ExactSnapshotRestoreProtocolFixture.Response.error(
                        "snapshot sgf unreadable: " + sgfPath);
                  }
                } else if (command.startsWith("lz-analyze")
                    || command.startsWith("kata-analyze")) {
                  analyzeCount.incrementAndGet();
                  analyzePosition.set(enginePosition.get());
                  lifecycleEvents.add("analyze");
                  analysisStarted.countDown();
                }
                return ExactSnapshotRestoreProtocolFixture.Response.success();
              });
      if (installParserReader) {
        // Parser regressions exercise reader-incarnation fencing, not merely command output.
        // Install the transport through initializeStreams so direct parseLine probes carry the
        // same ReaderStreamBinding that production reader callbacks always provide.
        installFreshCommandOutputForTest(commandTransport);
      }
    }

    @Override
    ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation(Object owner) {
      if (reservationFailure != null) {
        throw reservationFailure;
      }
      return rejectReservation ? null : super.beginExclusiveGtpLifecycleReservation(owner);
    }

    @Override
    public void startEngine(int index) {
      installFreshCommandOutputForTest(commandTransport);
      startEntered.countDown();
      CountDownLatch gate = startReturnGate;
      if (gate != null) {
        try {
          if (!gate.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("controlled startup gate timed out");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("controlled startup gate interrupted", interrupted);
        }
      }
      started = true;
      isLoaded = !delayReadyAfterStart;
      isCheckingName = delayReadyAfterStart;
      startCompleted.countDown();
      isNormalEnd = false;
      isDownWithError = false;
    }

    @Override
    void startPdaStartupTimeoutThread(Runnable timeout) {
      if (pdaStartupTimeoutSchedulingFailure != null) {
        throw pdaStartupTimeoutSchedulingFailure;
      }
      pdaStartupTimeouts.add(timeout);
    }

    @Override
    void dispatchStartupPostActionWorker(Runnable worker) {
      if (startupPostActionDispatchFailureAfterStart != null) {
        startupPostActionWorker =
            new Thread(
                () -> {
                  try {
                    if (startupPostActionWorkerStarted != null) {
                      startupPostActionWorkerStarted.countDown();
                    }
                    if (startupPostActionWorkerGate != null) {
                      awaitLatch(startupPostActionWorkerGate);
                    }
                    worker.run();
                  } catch (Throwable failure) {
                    startupPostActionWorkerFailure.set(failure);
                  }
                },
                "controlled-startup-post-action-worker");
        startupPostActionWorker.start();
        if (startupPostActionWorkerStarted != null) {
          awaitLatch(startupPostActionWorkerStarted);
        }
        throw startupPostActionDispatchFailureAfterStart;
      }
      if (asynchronousStartupPostAction) {
        super.dispatchStartupPostActionWorker(worker);
      } else {
        worker.run();
      }
    }

    @Override
    public void sendCommand(String command) {
      startupCommandAttempts.add(command);
      if (pdaStartupCommandFailure != null
          && command.equals(pdaStartupCommandFailureCommand)) {
        throw pdaStartupCommandFailure;
      }
      super.sendCommand(command);
    }

    @Override
    public void tryToDignostic(String message, boolean isModal) {
      diagnosticCount.incrementAndGet();
    }

    @Override
    void tryToDignosticForTerminalReader(
        String message,
        boolean primaryEngine,
        long primaryGeneration,
        Object expectedEngineIncarnation) {
      CountDownLatch entered = terminalDiagnosticEntered;
      if (entered != null) {
        entered.countDown();
      }
      CountDownLatch gate = terminalDiagnosticGate;
      if (gate != null) {
        awaitLatch(gate);
      }
      diagnosticCount.incrementAndGet();
    }

    private void publishReady() {
      isLoaded = true;
      isCheckingName = false;
    }

    @Override
    boolean completeAnalysisOutputRecovery(
        Object expectedIncarnation,
        Object recoveryToken,
        boolean requireFreshOwner,
        Supplier<Boolean> finalSettlement) {
      boolean completed =
          super.completeAnalysisOutputRecovery(
              expectedIncarnation, recoveryToken, requireFreshOwner, finalSettlement);
      if (completed && analysisOutputRecoveryCompleted != null) {
        analysisOutputRecoveryCompleted.countDown();
      }
      return completed;
    }

    @Override
    public void shutdown() {
      started = false;
      isLoaded = false;
      isCheckingName = false;
    }

    @Override
    public void ponder(boolean addPlayer, boolean blackToPlay) {
      ponderCount++;
      if (!noAnalyze) {
        // Preserve the real Leelaz state contract as well as recording the synthetic command.
        // Later switch snapshots must see the ponder intent established by a successful handoff.
        Pondering();
      }
      if (ponderFailure != null) {
        throw ponderFailure;
      }
      if (noAnalyze) {
        return;
      }
      // Exercise the real command gate: while the initial synchronization barrier is active this
      // analyze must be dropped; after the stable restore point it reaches the transport.
      sendCommand("lz-analyze 10");
      if (ponderCommandSent != null) {
        ponderCommandSent.countDown();
      }
      if (ponderAfterCommandGate != null) {
        awaitLatch(ponderAfterCommandGate);
      }
    }

    @Override
    public void setResponseUpToDate() {
      responseFreshenedCount++;
    }

    @Override
    public void notPondering() {
      notPonderingCount++;
      super.notPondering();
    }

    @Override
    public void normalQuit() {
      normalQuitThread = Thread.currentThread();
      normalQuitCount++;
      if (normalQuitEntered != null) {
        normalQuitEntered.countDown();
      }
      CountDownLatch gate = normalQuitGate;
      if (gate != null) {
        try {
          if (!gate.await(normalQuitGateTimeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("controlled normal-quit gate timed out");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("controlled normal-quit gate interrupted", interrupted);
        }
      }
      try {
        shutdown();
      } finally {
        if (normalQuitCompleted != null) {
          normalQuitCompleted.countDown();
        }
      }
    }

    @Override
    void finishExactNormalQuitClaim(ExactNormalQuitClaim claim) {
      normalQuitThread = Thread.currentThread();
      normalQuitCount++;
      if (normalQuitEntered != null) {
        normalQuitEntered.countDown();
      }
      if (incarnationRetirementEntered != null) {
        incarnationRetirementEntered.countDown();
      }
      CountDownLatch gate =
          incarnationRetirementGate == null ? normalQuitGate : incarnationRetirementGate;
      if (gate != null) {
        try {
          if (!gate.await(normalQuitGateTimeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("controlled incarnation-retirement gate timed out");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "controlled incarnation-retirement gate interrupted", interrupted);
        }
      }
      try {
        super.finishExactNormalQuitClaim(claim);
      } finally {
        if (normalQuitCompleted != null) {
          normalQuitCompleted.countDown();
        }
      }
    }

    @Override
    void confirmBoardSynchronization(
        Runnable onSuccess, java.util.function.Consumer<String> onFailure) {
      boardSynchronizationConfirmations++;
      lifecycleEvents.add("fence");
      boardSynchronizationEntered.countDown();
      if (boardSynchronizationPostRegistrationError != null) {
        lateBoardSynchronizationSuccess = onSuccess;
        throw boardSynchronizationPostRegistrationError;
      }
      if (confirmBoardSynchronizationOnDedicatedThread) {
        Thread callback =
            new Thread(
                () -> completeBoardSynchronization(onSuccess, onFailure),
                "controlled-gtp-fence-callback");
        callback.setDaemon(true);
        callback.start();
        return;
      }
      if (confirmBoardSynchronizationOnEdt && !SwingUtilities.isEventDispatchThread()) {
        SwingUtilities.invokeLater(() -> completeBoardSynchronization(onSuccess, onFailure));
        return;
      }
      completeBoardSynchronization(onSuccess, onFailure);
    }

    private void publishLateBoardSynchronizationSuccess() {
      Runnable callback = lateBoardSynchronizationSuccess;
      lateBoardSynchronizationSuccess = null;
      if (callback == null) {
        throw new IllegalStateException("no registered board synchronization callback");
      }
      callback.run();
    }

    private void completeBoardSynchronization(
        Runnable onSuccess, java.util.function.Consumer<String> onFailure) {
      boardSynchronizationCallbackThread = Thread.currentThread();
      boardSynchronizationCallbackEntered.countDown();
      try {
        if (boardSynchronizationError != null) {
          throw boardSynchronizationError;
        }
        CountDownLatch gate = boardSynchronizationGate;
        if (gate != null) {
          try {
            if (!gate.await(5, TimeUnit.SECONDS)) {
              onFailure.accept("controlled final board fence timed out");
              return;
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            onFailure.accept("controlled final board fence interrupted");
            return;
          }
        }
        if (boardSynchronizationFailure == null) {
          onSuccess.run();
        } else {
          onFailure.accept(boardSynchronizationFailure);
        }
      } finally {
        boardSynchronizationCallbackCompleted.countDown();
      }
    }

    @Override
    public void clearBestMoves() {}

    private int analyzeCount() {
      return analyzeCount.get();
    }

    private int analyzePosition() {
      return analyzePosition.get();
    }

    private Stone stoneAt(int x, int y) {
      return engineStones.getOrDefault(
          Board.convertCoordinatesToName(x, y).toUpperCase(Locale.ROOT), Stone.EMPTY);
    }

    private List<String> tailPlays() {
      List<String> plays = new ArrayList<>();
      boolean tailStarted = false;
      for (String command : commands) {
        if (command.startsWith("loadsgf ")) {
          tailStarted = true;
        } else if (command.equals("clear_board")) {
          tailStarted = false;
        } else if (tailStarted && command.startsWith("play ")) {
          plays.add(command);
        }
      }
      return plays;
    }

    private List<String> playsAfterLastLoadSgf() {
      List<String> plays = new ArrayList<>();
      for (String command : commands) {
        if (command.startsWith("loadsgf ")) {
          plays.clear();
        } else if (command.startsWith("play ")) {
          plays.add(command);
        }
      }
      return plays;
    }

    private List<String> playsAfterLastClear() {
      List<String> plays = new ArrayList<>();
      for (String command : commands) {
        if (command.equals("clear_board")) {
          plays.clear();
        } else if (command.startsWith("play ")) {
          plays.add(command);
        }
      }
      return plays;
    }

    private String loadedSgfContent(int index) {
      return loadedSgfContents.get(index);
    }

    private boolean containsCommand(String command) {
      return commands.contains(command);
    }

    private void applySnapshotSgf(String sgf) {
      engineStones.clear();
      engineBlackToPlay = true;
      String sizeValue = firstSgfValue(sgf, "SZ");
      if (sizeValue != null && !sizeValue.isEmpty()) {
        String[] parts = sizeValue.split(":");
        engineBoardWidth = Integer.parseInt(parts[0]);
        engineBoardHeight = parts.length > 1 ? Integer.parseInt(parts[1]) : engineBoardWidth;
      }
      String pl = firstSgfValue(sgf, "PL");
      if ("W".equalsIgnoreCase(pl)) {
        engineBlackToPlay = false;
      }
      String km = firstSgfValue(sgf, "KM");
      if (km != null && !km.isEmpty()) {
        engineKomi = Double.parseDouble(km);
      }
      applySgfStones(sgf, "AB", Stone.BLACK);
      applySgfStones(sgf, "AW", Stone.WHITE);
    }

    private void applySgfStones(String sgf, String property, Stone color) {
      for (String coord : sgfPropertyValues(sgf, property)) {
        if (coord == null || coord.length() < 2) {
          continue;
        }
        int x = coord.charAt(0) - 'a';
        int y = coord.charAt(1) - 'a';
        if (x >= 0 && x < 52 && y >= 0 && y < 52) {
          engineStones.put(Board.convertCoordinatesToName(x, y).toUpperCase(Locale.ROOT), color);
        }
      }
    }

    private static String firstSgfValue(String sgf, String property) {
      List<String> values = sgfPropertyValues(sgf, property);
      return values.isEmpty() ? null : values.get(0);
    }

    private static List<String> sgfPropertyValues(String sgf, String property) {
      // The materialized snapshot SGF appends one property token per stone, so the same property
      // may repeat adjacently (e.g. AW[ee]AW[ff]); the group repeats the full token, not just the
      // bracket values.
      java.util.regex.Matcher propertyMatcher =
          java.util.regex.Pattern.compile("(" + property + "\\[[^\\]]*\\])+").matcher(sgf);
      if (!propertyMatcher.find()) {
        return List.of();
      }
      java.util.regex.Matcher valueMatcher =
          java.util.regex.Pattern.compile("\\[([^\\]]*)\\]").matcher(propertyMatcher.group());
      List<String> values = new ArrayList<>();
      while (valueMatcher.find()) {
        values.add(valueMatcher.group(1));
      }
      return values;
    }
  }

  private static final class PartialInitialPreloadLeelaz extends StartupSyncLeelaz {
    private final AssertionError startFailure =
        new AssertionError("controlled initial preload failure after reader publication");

    private PartialInitialPreloadLeelaz() throws Exception {}

    @Override
    public void startEngine(int index) {
      super.startEngine(index);
      throw startFailure;
    }
  }

  private static final class PartialOrdinarySwitchStartLeelaz extends StartupSyncLeelaz {
    private final AssertionError startFailure =
        new AssertionError("controlled ordinary start failure after reader publication");

    private PartialOrdinarySwitchStartLeelaz() throws Exception {}

    @Override
    public void startEngine(int index) {
      super.startEngine(index);
      throw startFailure;
    }
  }

  private static final class PartialSelectedInitialStartupLeelaz extends StartupSyncLeelaz {
    private final AssertionError startFailure =
        new AssertionError("controlled selected startup failure after reader publication");

    private PartialSelectedInitialStartupLeelaz() throws Exception {}

    @Override
    public void startEngine(int index) {
      super.startEngine(index);
      throw startFailure;
    }
  }

  private static final class FailOnceSelectedInitialStartupLeelaz extends StartupSyncLeelaz {
    private final AssertionError firstStartFailure =
        new AssertionError("controlled repairable selected-startup failure");
    private final AtomicInteger startAttempts = new AtomicInteger();
    private final CountDownLatch firstStartCompleted = new CountDownLatch(1);
    private final CountDownLatch secondStartCompleted = new CountDownLatch(1);

    private FailOnceSelectedInitialStartupLeelaz() throws Exception {}

    @Override
    public void startEngine(int index) {
      int attempt = startAttempts.incrementAndGet();
      super.startEngine(index);
      if (attempt == 1) {
        firstStartCompleted.countDown();
        throw firstStartFailure;
      }
      secondStartCompleted.countDown();
    }
  }

  private static final class DetachFailingStartupSyncLeelaz extends StartupSyncLeelaz {
    private final AssertionError detachFailure;
    private int detachAttempts;

    private DetachFailingStartupSyncLeelaz(AssertionError detachFailure) throws Exception {
      this.detachFailure = detachFailure;
    }

    @Override
    void detachInitialEngineSyncAdmission(EngineManager.InitialEngineSyncAdmission admission) {
      detachAttempts++;
      super.detachInitialEngineSyncAdmission(admission);
      throw detachFailure;
    }
  }

  private static final class StartupTestBoard extends Board {
    @Override
    public void clearAfterMove() {
      // Avoid headless UI dependencies during navigation-driven startup tests.
    }
  }

  private static final class BlockingPrepareBoard extends Board {
    private CountDownLatch historyReadEntered;
    private CountDownLatch allowHistoryRead;
    private boolean blockHistoryRead;

    @Override
    public BoardHistoryList getHistory() {
      if (blockHistoryRead) {
        historyReadEntered.countDown();
        try {
          if (!allowHistoryRead.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("controlled history capture was not released");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "controlled history capture was interrupted", interrupted);
        }
      }
      return super.getHistory();
    }

    @Override
    public void clearAfterMove() {}
  }

  private static final class CaptureFailureBoard extends Board {
    private RuntimeException captureFailure;
    private boolean failOnHistoryRead;

    @Override
    public void clearAfterMove() {
      // Avoid headless UI dependencies during capture failure setup.
    }

    @Override
    public BoardHistoryList getHistory() {
      if (failOnHistoryRead) {
        throw captureFailure;
      }
      return super.getHistory();
    }
  }

  private static final class BlockingStartupConfig extends Config {
    private CountDownLatch doubleEngineQueryEntered;
    private CountDownLatch allowDoubleEngineQuery;

    private BlockingStartupConfig() throws IOException {
      super();
    }

    @Override
    public boolean isDoubleEngineMode() {
      doubleEngineQueryEntered.countDown();
      try {
        if (!allowDoubleEngineQuery.await(2, TimeUnit.SECONDS)) {
          throw new IllegalStateException("double-engine query was not released");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("double-engine query was interrupted", interrupted);
      }
      return false;
    }
  }

  private static final class ThrowingStartupConfig extends Config {
    private RuntimeException saveFailure;

    private ThrowingStartupConfig() throws IOException {
      super();
    }

    @Override
    public void save() {
      throw saveFailure;
    }
  }

  private static final class ProductionEntryEngineManager extends EngineManager {
    private final AtomicInteger synchronizationCompletionCount = new AtomicInteger();
    private final CountDownLatch firstSynchronizationCompleted = new CountDownLatch(1);
    private final CountDownLatch secondSynchronizationCompleted = new CountDownLatch(1);
    private final CountDownLatch synchronizationFailed = new CountDownLatch(1);
    private int synchronizationFailureCount;
    private int leaseConflictCount;
    private long timeoutMillis = TimeUnit.SECONDS.toMillis(5);
    private boolean deferFailurePresentation;
    private volatile Runnable deferredFailurePresentation;
    private final CountDownLatch failurePresentationEnqueued = new CountDownLatch(1);

    private ProductionEntryEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void synchronizeEngineWhenReady(
        Leelaz engine, Runnable synchronization, Runnable afterSync) {
      super.synchronizeEngineWhenReady(
          engine,
          synchronization,
          () -> {
            try {
              if (afterSync != null) {
                afterSync.run();
              }
            } finally {
              if (synchronizationCompletionCount.incrementAndGet() == 1) {
                firstSynchronizationCompleted.countDown();
              } else {
                secondSynchronizationCompleted.countDown();
              }
            }
          });
    }

    @Override
    protected long engineSynchronizationTimeoutMillis(Leelaz engine) {
      return timeoutMillis;
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      synchronizationFailureCount++;
      synchronizationFailed.countDown();
    }

    @Override
    protected void enqueueEngineSynchronizationFailurePresentation(Runnable presentation) {
      if (!deferFailurePresentation) {
        super.enqueueEngineSynchronizationFailurePresentation(presentation);
        return;
      }
      deferredFailurePresentation = presentation;
      failurePresentationEnqueued.countDown();
    }

    @Override
    protected void showForegroundEngineLeaseInUse() {
      leaseConflictCount++;
    }

    @Override
    protected void showContributingEngineSwitchUnavailable() {}
  }

  private static final class FailingOrdinarySynchronizationDispatchEngineManager
      extends EngineManager {
    private final AssertionError dispatchFailure =
        new AssertionError("controlled ordinary synchronization dispatch failure");
    private final AtomicInteger dispatchAttempts = new AtomicInteger();
    private final CountDownLatch failurePresented = new CountDownLatch(1);

    private FailingOrdinarySynchronizationDispatchEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected Thread createEngineSynchronizationThread(Runnable synchronization) {
      dispatchAttempts.incrementAndGet();
      throw dispatchFailure;
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failurePresented.countDown();
    }

    @Override
    protected void showContributingEngineSwitchUnavailable() {}
  }


  private static final class StartupTestEnvironment implements AutoCloseable {
    private final Leelaz previousPrimary = Lizzie.leelaz;
    private final Leelaz previousSecondary = Lizzie.leelaz2;
    private final Board previousBoard = Lizzie.board;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final BottomToolbar previousToolbar = LizzieFrame.toolbar;
    private final Menu previousMenu = LizzieFrame.menu;
    private final EngineManager previousEngineManager = Lizzie.engineManager;
    private final JFontMenu previousEngineMenu = Menu.engineMenu;
    private final JFontMenu previousEngineMenu2 = Menu.engineMenu2;
    private final BoardRenderer previousBoardRenderer2 = LizzieFrame.boardRenderer2;
    private final BoardRenderer previousBoardRenderer = LizzieFrame.boardRenderer;
    private final Config previousConfig = Lizzie.config;
    private final GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    private final EngineStartupStatus.Snapshot previousStartupStatus =
        Lizzie.engineStartupStatus.snapshot();
    private final boolean previousEmpty = EngineManager.isEmpty;
    private final int previousEngineNo = EngineManager.currentEngineNo;
    private final int previousEngineNo2 = EngineManager.currentEngineNo2;
    private final int previousBoardWidth = Board.boardWidth;
    private final int previousBoardHeight = Board.boardHeight;
    private final WinrateGraph previousWinrateGraph = LizzieFrame.winrateGraph;
    private final AtomicInteger readyTransitions = new AtomicInteger();
    private final java.util.function.Consumer<EngineStartupStatus.Snapshot> readyListener;
    private final AtomicBoolean readyObservedCommittedOwner = new AtomicBoolean();
    private volatile Integer expectedReadyEngineIndex;

    private StartupTestEnvironment() throws Exception {
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentStartupGtpConsole.class);
      Lizzie.frame = allocate(SilentStartupFrame.class);
      LizzieFrame.toolbar = allocate(SilentStartupToolbar.class);
      LizzieFrame.menu = allocate(SilentStartupMenu.class);
      LizzieFrame.menu.txtKomi = new javax.swing.JTextField();
      Menu.engineMenu = allocate(SilentStartupEngineMenu.class);
      Menu.engineMenu2 = allocate(SilentStartupEngineMenu.class);
      LizzieFrame.boardRenderer2 = new BoardRenderer(true);
      LizzieFrame.boardRenderer = new BoardRenderer(false);
      LizzieFrame.winrateGraph = allocate(WinrateGraph.class);
      Lizzie.leelaz2 = null;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = -1;
      Board.boardWidth = 19;
      Board.boardHeight = 19;
      Zobrist.init();
      readyListener =
          snapshot -> {
            if (snapshot.state == EngineStartupStatus.State.READY) {
              readyTransitions.incrementAndGet();
              Integer expectedEngineIndex = expectedReadyEngineIndex;
              if (expectedEngineIndex != null) {
                readyObservedCommittedOwner.set(
                    !EngineManager.isEmpty && EngineManager.currentEngineNo == expectedEngineIndex);
              }
            }
          };
      Lizzie.engineStartupStatus.addListener(readyListener);
    }

    private static StartupTestEnvironment open() throws Exception {
      return new StartupTestEnvironment();
    }

    private void publish(Leelaz engine, Board board) {
      Lizzie.leelaz = engine;
      Lizzie.board = board;
    }

    private int clearBoardCount(StartupSyncLeelaz engine) {
      return engine.clearBoardCount.get();
    }

    @Override
    public void close() throws Exception {
      try {
        SwingUtilities.invokeAndWait(() -> {});
      } catch (Exception ignored) {
        // EDT may be unavailable in headless test runs.
      }
      Lizzie.engineStartupStatus.removeListener(readyListener);
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      LizzieFrame.menu = previousMenu;
      Lizzie.engineManager = previousEngineManager;
      Menu.engineMenu = previousEngineMenu;
      Menu.engineMenu2 = previousEngineMenu2;
      LizzieFrame.boardRenderer2 = previousBoardRenderer2;
      LizzieFrame.boardRenderer = previousBoardRenderer;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      EngineManager.currentEngineNo2 = previousEngineNo2;
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      LizzieFrame.winrateGraph = previousWinrateGraph;
      restoreStartupStatus(previousStartupStatus);
    }

    private static void restoreStartupStatus(EngineStartupStatus.Snapshot snapshot) {
      switch (snapshot.state) {
        case READY:
          Lizzie.engineStartupStatus.ready();
          break;
        case CHECKING:
          Lizzie.engineStartupStatus.checking(snapshot.messageKey, snapshot.fallback);
          break;
        case NEEDS_REPAIR:
          Lizzie.engineStartupStatus.needsRepair(
              snapshot.messageKey, snapshot.fallback, snapshot.detail);
          break;
        case START_FAILED:
          Lizzie.engineStartupStatus.failed(
              snapshot.messageKey, snapshot.fallback, snapshot.detail);
          break;
      }
    }
  }

  private static final class SilentStartupFrame extends LizzieFrame {
    @Override
    public void refresh() {}

    @Override
    public void reSetLoc() {}

    @Override
    public void resetTitle() {}

    @Override
    public void redrawBoardrendererBackground() {}

    @Override
    public void clearKataEstimate() {}

    @Override
    public boolean resetMovelistFrameandAnalysisFrame() {
      return false;
    }
  }

  private static final class SilentStartupToolbar extends BottomToolbar {
    @Override
    public void reSetButtonLocation() {}
  }

  private static final class SilentStartupMenu extends Menu {
    private Leelaz lastPdaEngine;
    private boolean lastPdaVisible;
    private volatile int stoppedPrimaryIconCount;
    private volatile int readyPrimaryIconCount;
    @Override
    public void applyEngineSwitchUiState(EngineManager.EngineSwitchUiSnapshot snapshot) {}

    @Override
    public void showPda(boolean show) {}

    @Override
    public void showPdaForEngine(Leelaz engine, boolean show) {
      lastPdaEngine = engine;
      lastPdaVisible = show;
    }

    @Override
    public void showPdaForEngine(Leelaz engine, long primaryGeneration, boolean show) {}

    @Override
    public void updateMenuStatusForEngine() {}

    @Override
    public void changeEngineIcon2(int index, int mode) {}

    @Override
    public void changeicon(int index) {}

    @Override
    public void changeEngineIcon(int index, int mode) {
      if (mode == 0) {
        stoppedPrimaryIconCount++;
      } else if (mode == 2) {
        readyPrimaryIconCount++;
      }
    }
  }

  private static final class SilentStartupGtpConsole extends GtpConsolePane {
    private SilentStartupGtpConsole() {
      super((java.awt.Window) null);
    }

    @Override
    public boolean isVisible() {
      return false;
    }

    @Override
    public void addLine(String line) {}
  }

  private static final class SilentStartupEngineMenu extends JFontMenu {
    @Override
    public void setText(String text) {}
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }
}
