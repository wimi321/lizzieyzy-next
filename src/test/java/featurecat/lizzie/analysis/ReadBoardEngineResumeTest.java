package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.BoardRenderer;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.JFontCheckBox;
import featurecat.lizzie.gui.JFontTextField;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.JCheckBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReadBoardEngineResumeTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  private int previousBoardWidth;
  private int previousBoardHeight;

  @BeforeEach
  void setUpFixtureBoardSize() {
    previousBoardWidth = Board.boardWidth;
    previousBoardHeight = Board.boardHeight;
    Board.boardWidth = BOARD_SIZE;
    Board.boardHeight = BOARD_SIZE;
    Zobrist.init();
  }

  @AfterEach
  void tearDownFixtureBoardSize() {
    Board.boardWidth = previousBoardWidth;
    Board.boardHeight = previousBoardHeight;
    Zobrist.init();
  }

  @Test
  void stoppedSynchronizationKeepsCompleteLocalPlacement() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      harness.readBoard.process = new AliveProcess();
      setField(
          harness.readBoard, "pendingLocalMoveTimeoutExecutor", new ScheduledThreadPoolExecutor(1));
      harness.readBoard.parseLine("bothSync");
      harness.readBoard.parseLine("sync");
      harness.readBoard.parseLine("stopsync");

      harness.board.place(0, 1, Stone.BLACK, false, false, false);
      BoardHistoryNode localMove = harness.board.getHistory().getCurrentHistoryNode();
      harness.leelaz.sentCommands.clear();
      Thread.sleep(3400);
      assertSame(localMove, harness.board.getHistory().getCurrentHistoryNode());
      assertTrue(harness.leelaz.sentCommands.isEmpty());

      assertEquals(3, localMove.getData().moveNumber);
      assertEquals(Stone.BLACK, localMove.getData().stones[Board.getIndex(0, 1)]);
      assertFalse(harness.protocolOutput().contains("place "));
      assertTrue(harness.readBoard.process.isAlive());
    }
  }

  @Test
  void stoppingRetiresCapturedTimeoutAndAllowsNextLocalMove() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      CapturedTimeoutScheduler scheduler = harness.activateLocalPlacement();
      harness.board.place(0, 1, Stone.BLACK, false, false, false);
      BoardHistoryNode pending = harness.board.getHistory().getCurrentHistoryNode();
      assertTrue(harness.readBoard.hasLocalMoveConfirmationAuthority());
      assertEquals(3, pending.getData().moveNumber);
      assertTrue(harness.protocolOutput().contains("place 0 1"), harness.protocolOutput());
      Runnable timeout = scheduler.callbacks.get(0);

      harness.readBoard.parseLine("stopsync");
      setField(
          harness.readBoard, "lastPendingLocalMoveRetryTimeMs", System.currentTimeMillis() - 4000);
      harness.leelaz.sentCommands.clear();
      timeout.run();

      assertSame(pending, harness.board.getHistory().getCurrentHistoryNode());
      assertTrue(harness.leelaz.sentCommands.isEmpty());
      harness.board.place(2, 2, Stone.WHITE, false, false, false);
      assertEquals(4, harness.board.getHistory().getData().moveNumber);
      assertEquals(Stone.WHITE, harness.board.getHistory().getStones()[Board.getIndex(2, 2)]);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"endsync", "shutdown"})
  void endingHelperConfirmationPreservesPendingHistory(String end) throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      CapturedTimeoutScheduler scheduler = harness.activateLocalPlacement();
      harness.board.place(0, 1, Stone.BLACK, false, false, false);
      BoardHistoryNode pending = harness.board.getHistory().getCurrentHistoryNode();
      harness.readBoard.parseLine("re=1,2,2");
      harness.readBoard.parseLine("re=0,0,0");
      harness.readBoard.parseLine("re=0,0,0");
      harness.leelaz.sentCommands.clear();
      if (end.equals("shutdown")) {
        harness.readBoard.shutdownAfterProcessEnd();
      } else {
        harness.readBoard.parseLine(end);
      }
      harness.expire(scheduler.callbacks.get(0));
      assertSame(pending, harness.board.getHistory().getCurrentHistoryNode());
      assertFalse(
          harness.leelaz.sentCommands.stream().anyMatch(command -> command.startsWith("loadsgf ")));
      harness.board.place(2, 2, Stone.WHITE, false, false, false);
      assertEquals(4, harness.board.getHistory().getData().moveNumber);
    }
  }

  @Test
  void concurrentLocalPlacementsSharePendingAdmission() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.activateLocalPlacement();
      CountDownLatch bothPlacementsEntered = new CountDownLatch(2);
      LizzieFrame.boardRenderer =
          new BoardRenderer(false) {
            @Override
            public void removedrawmovestone() {
              bothPlacementsEntered.countDown();
              try {
                assertTrue(bothPlacementsEntered.await(2, TimeUnit.SECONDS));
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
              }
            }
          };
      CompletableFuture<Void> first =
          CompletableFuture.runAsync(
              () -> harness.board.place(0, 1, Stone.BLACK, false, false, false));
      CompletableFuture<Void> second =
          CompletableFuture.runAsync(
              () -> harness.board.place(2, 2, Stone.BLACK, false, false, false));
      CompletableFuture.allOf(first, second).get(3, TimeUnit.SECONDS);

      BoardHistoryNode pending = harness.board.getHistory().getCurrentHistoryNode();
      assertEquals(3, pending.getData().moveNumber);
      assertEquals(
          1, harness.protocolOutput().lines().filter(line -> line.startsWith("place ")).count());
      harness.acceptSnapshot(pending);
      harness.board.place(1, 2, Stone.WHITE, false, false, false);
      assertEquals(4, harness.board.getHistory().getData().moveNumber);
      assertTrue(harness.protocolOutput().contains("place 1 2"));
    }
  }

  @Test
  void restartedSyncConfirmsNewPendingDespiteOldTimeout() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      CapturedTimeoutScheduler scheduler = harness.activateLocalPlacement();
      harness.board.place(0, 1, Stone.BLACK, false, false, false);
      Runnable oldTimeout = scheduler.callbacks.get(0);
      harness.readBoard.parseLine("stopsync");
      harness.readBoard.parseLine("sync");
      harness.board.place(2, 2, Stone.WHITE, false, false, false);
      BoardHistoryNode newPending = harness.board.getHistory().getCurrentHistoryNode();
      assertTrue(harness.protocolOutput().contains("place 2 2"));

      harness.expire(oldTimeout);
      assertSame(newPending, harness.board.getHistory().getCurrentHistoryNode());
      harness.acceptSnapshot(newPending);
      harness.expire(scheduler.callbacks.get(1));
      assertSame(newPending, harness.board.getHistory().getCurrentHistoryNode());
      harness.board.place(1, 2, Stone.BLACK, false, false, false);
      assertEquals(5, harness.board.getHistory().getData().moveNumber);
      assertTrue(harness.protocolOutput().contains("place 1 2"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"helper", "history"})
  void replacementRetiresOldCapturedWork(String replacement) throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      CapturedTimeoutScheduler scheduler = harness.activateLocalPlacement();
      harness.board.place(0, 1, Stone.BLACK, false, false, false);
      if (replacement.equals("helper")) {
        harness.frame.readBoard = allocate(ReadBoard.class);
      } else {
        harness.board.setHistory(rootHistory(stones(placement(2, 1, Stone.WHITE)), true));
      }
      BoardHistoryList history = harness.board.getHistory();
      BoardHistoryNode current = history.getCurrentHistoryNode();
      harness.leelaz.sentCommands.clear();
      harness.expire(scheduler.callbacks.get(0));
      assertSame(history, harness.board.getHistory());
      assertSame(current, history.getCurrentHistoryNode());
      assertTrue(harness.leelaz.sentCommands.isEmpty());
    }
  }

  @Test
  void stopWinsWhileTimeoutWaitsForHistoryCommit() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      CapturedTimeoutScheduler scheduler = harness.activateLocalPlacement();
      harness.board.place(0, 1, Stone.BLACK, false, false, false);
      BoardHistoryNode pending = harness.board.getHistory().getCurrentHistoryNode();
      setField(
          harness.readBoard, "lastPendingLocalMoveRetryTimeMs", System.currentTimeMillis() - 4000);
      CountDownLatch started = new CountDownLatch(1);
      CompletableFuture<Void> completed = new CompletableFuture<>();
      Thread timeout =
          new Thread(
              () -> {
                started.countDown();
                try {
                  scheduler.callbacks.get(0).run();
                  completed.complete(null);
                } catch (Throwable failure) {
                  completed.completeExceptionally(failure);
                }
              });
      synchronized (harness.board) {
        timeout.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (timeout.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
          Thread.yield();
        }
        assertEquals(Thread.State.BLOCKED, timeout.getState());
        harness.readBoard.parseLine("stopsync");
        harness.leelaz.sentCommands.clear();
      }
      completed.get(2, TimeUnit.SECONDS);
      assertSame(pending, harness.board.getHistory().getCurrentHistoryNode());
      assertTrue(harness.leelaz.sentCommands.isEmpty());
    }
  }

  @Test
  void committedTimeoutThenStopRestoresOrdinaryLocalEditing() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      CapturedTimeoutScheduler scheduler = harness.activateLocalPlacement();
      harness.board.place(0, 1, Stone.BLACK, false, false, false);
      harness.expire(scheduler.callbacks.get(0));
      assertEquals(2, harness.board.getHistory().getData().moveNumber);
      assertEquals(Stone.EMPTY, harness.board.getHistory().getStones()[Board.getIndex(0, 1)]);
      assertTrue(
          harness.leelaz.sentCommands.stream().anyMatch(command -> command.startsWith("loadsgf ")));
      harness.board.place(0, 1, Stone.BLACK, false, false, false);
      assertEquals(2, harness.board.getHistory().getData().moveNumber);

      harness.readBoard.parseLine("stopsync");
      harness.board.place(0, 1, Stone.BLACK, false, false, false);
      harness.board.place(2, 2, Stone.WHITE, false, false, false);
      assertEquals(4, harness.board.getHistory().getData().moveNumber);
      assertEquals(Stone.BLACK, harness.board.getHistory().getStones()[Board.getIndex(0, 1)]);
      assertEquals(Stone.WHITE, harness.board.getHistory().getStones()[Board.getIndex(2, 2)]);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"pipe", "socket"})
  void stopInvalidatesPlacementBeforeQueuedOutputStarts(String transport) throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.activateLocalPlacement();
      if (transport.equals("socket")) harness.useSocketPlacement();
      List<Runnable> output = new ArrayList<>();
      setField(harness.readBoard, "placeCommandDispatcher", (Executor) output::add);
      SwingUtilities.invokeAndWait(
          () -> harness.board.place(0, 1, Stone.BLACK, false, false, false));
      assertEquals(1, output.size());
      harness.readBoard.parseLine("stopsync");
      output.get(0).run();
      assertFalse(harness.protocolOutput().contains("place "));
      assertEquals(3, harness.board.getHistory().getData().moveNumber);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"gap", "clear", "start 3 3", "socket"})
  void activeFrameBoundariesKeepSnapshotAsAcknowledgement(String control) throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      CapturedTimeoutScheduler scheduler = harness.activateLocalPlacement();
      if (control.equals("socket")) harness.useSocketPlacement();
      harness.board.place(0, 1, Stone.BLACK, false, false, false);
      BoardHistoryNode pending = harness.board.getHistory().getCurrentHistoryNode();
      assertTrue(harness.protocolOutput().contains("place 0 1"));
      harness.readBoard.parseLine("placeComplete");
      if (control.equals("clear") || control.startsWith("start"))
        harness.readBoard.parseLine(control);
      harness.board.place(2, 2, Stone.WHITE, false, false, false);
      assertSame(pending, harness.board.getHistory().getCurrentHistoryNode());
      harness.acceptSnapshot(pending);
      harness.expire(scheduler.callbacks.get(0));
      assertSame(pending, harness.board.getHistory().getCurrentHistoryNode());
      harness.board.place(2, 2, Stone.WHITE, false, false, false);
      assertEquals(4, harness.board.getHistory().getData().moveNumber);
      assertTrue(harness.protocolOutput().contains("place 2 2"));
    }
  }

  @Test
  void oneTimeRecognitionImportsSnapshotWithoutContinuousSync() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.readBoard.parseLine("start 3 3");
      harness.readBoard.parseLine("re=1,2,0");
      harness.readBoard.parseLine("re=0,0,0");
      harness.readBoard.parseLine("re=0,0,0");
      harness.readBoard.parseLine("end");
      assertFalse(harness.frame.syncBoard);
      assertEquals(Stone.BLACK, harness.board.getHistory().getStones()[Board.getIndex(0, 0)]);
      assertEquals(Stone.WHITE, harness.board.getHistory().getStones()[Board.getIndex(1, 0)]);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"error place failed", "changed-snapshot"})
  void activeProtocolFailureRollsBackCompleteLocalMove(String failure) throws Exception {
    GtpConsolePane previousConsole = Lizzie.gtpConsole;
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      Lizzie.gtpConsole = allocate(SilentProtocolConsole.class);
      harness.activateLocalPlacement();
      harness.board.place(0, 1, Stone.BLACK, false, false, false);
      if (failure.equals("changed-snapshot")) {
        harness.readBoard.parseLine("re=1,2,2");
        harness.readBoard.parseLine("re=0,0,0");
        harness.readBoard.parseLine("re=0,0,0");
        harness.readBoard.parseLine("end");
      } else {
        harness.readBoard.parseLine(failure);
      }
      assertEquals(2, harness.board.getHistory().getData().moveNumber);
      assertEquals(Stone.EMPTY, harness.board.getHistory().getStones()[Board.getIndex(0, 1)]);
      assertTrue(
          harness.leelaz.sentCommands.stream().anyMatch(command -> command.startsWith("loadsgf ")));
      harness.board.place(2, 2, Stone.BLACK, false, false, false);
      assertEquals(2, harness.board.getHistory().getData().moveNumber);
    } finally {
      Lizzie.gtpConsole = previousConsole;
    }
  }

  private static final class SilentProtocolConsole extends GtpConsolePane {
    private SilentProtocolConsole() {
      super(null);
    }

    @Override
    public void addLineReadBoard(String line) {}
  }

  private static final class CapturedTimeoutScheduler extends ScheduledThreadPoolExecutor {
    private final List<Runnable> callbacks = new ArrayList<>();

    private CapturedTimeoutScheduler() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      callbacks.add(command);
      return null;
    }
  }

  @Test
  void forceRebuildSchedulesResumeAnalysis() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              mainEnd.getData().stones,
              mainEnd.getData().lastMove,
              mainEnd.getData().lastMoveColor));

      assertEquals(1, harness.frame.scheduleResumeAnalysisCount);
      assertNotNull(harness.frame.lastScheduledResumeAction);
    }
  }

  @Test
  void ordinaryReadBoardSyncStartsAnalysisWithoutInstallingGameMoveTime() throws Exception {
    Menu previousMenu = LizzieFrame.menu;
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      LizzieFrame.menu = allocate(SilentMenu.class);
      LizzieFrame.toolbar = allocate(SilentBottomToolbar.class);
      harness.frame.isPlayingAgainstLeelaz = false;
      harness.frame.isAnaPlayingAgainstLeelaz = false;
      harness.leelaz.isKatago = true;
      Lizzie.config.maxGameThinkingTimeSeconds = 2;
      Lizzie.config.notStartPondering = true;

      SnapshotTrackingLeelaz previousPrimaryEngine = SnapshotTrackingLeelaz.create();
      Lizzie.leelaz = previousPrimaryEngine;
      try {
        Lizzie.initializeAfterVersionCheck(false, harness.leelaz);
      } finally {
        Lizzie.leelaz = harness.leelaz;
      }
      harness.readBoard.parseLine("sync");
      SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, harness.leelaz.togglePonderCount);
      assertEquals(1, harness.leelaz.ponderCount);
      assertFalse(
          harness.leelaz.sentCommands.stream()
              .anyMatch(
                  command ->
                      command.startsWith("time_settings ")
                          || command.startsWith("kata-time_settings ")
                          || command.startsWith("kata-set-param maxTime ")),
          "ordinary ReadBoard analysis must not inherit per-move game time commands.");
    } finally {
      LizzieFrame.menu = previousMenu;
    }
  }


  @Test
  void forceRebuildContinuesPlayingAgainstLeelazGenmove() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isPlayingAgainstLeelaz = true;
      harness.frame.playerIsBlack = true;
      setField(harness.readBoard, "needGenmove", true);
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              mainEnd.getData().stones,
              mainEnd.getData().lastMove,
              mainEnd.getData().lastMoveColor));

      assertEquals(1, harness.leelaz.genmoveCount);
      assertEquals("W", harness.leelaz.lastGenmoveColor);
      assertEquals(0, harness.frame.scheduleResumeAnalysisCount);
    }
  }

  @Test
  void forceRebuildResumesAutoPlayAfterRemoteChangesFollowingFailedLocalMove() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      buildHistory(
          harness.board,
          placement(0, 0, Stone.BLACK),
          placement(1, 0, Stone.WHITE),
          placement(0, 1, Stone.BLACK));
      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(2, 0, Stone.WHITE)),
              Optional.of(new int[] {2, 0}),
              Stone.WHITE));

      assertEquals(1, harness.leelaz.ponderCount);
      assertEquals(0, harness.leelaz.genmoveCount);
      assertEquals(0, harness.frame.scheduleResumeAnalysisCount);
      assertFalse(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));
    }
  }


  @Test
  void forceRebuildRegeneratesFailedEngineMoveWhenPlayingAgainstLeelaz() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isPlayingAgainstLeelaz = true;
      harness.frame.playerIsBlack = false;
      buildHistory(
          harness.board,
          placement(0, 0, Stone.BLACK),
          placement(1, 0, Stone.WHITE),
          placement(0, 1, Stone.BLACK));
      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);
      int previousGenmoveRequests = harness.leelaz.genmoveCount;

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(2, 0, Stone.WHITE)),
              Optional.of(new int[] {2, 0}),
              Stone.WHITE));

      assertEquals(previousGenmoveRequests + 1, harness.leelaz.genmoveCount);
      assertEquals("B", harness.leelaz.lastGenmoveColor);
      assertEquals(0, harness.frame.scheduleResumeAnalysisCount);
      assertFalse(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));
    }
  }

  @Test
  void placementFailureRollsBackFailedMainEndAndResumesAnalysisWithPlacementGuard()
      throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode remoteNode = path.nodes.get(1);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);

      assertSame(
          remoteNode,
          harness.board.getHistory().getCurrentHistoryNode(),
          "the local failed engine move must be rolled back to the last remote-confirmed node.");
      assertSame(
          remoteNode,
          harness.board.getHistory().getMainEnd(),
          "the failed child must be removed so the next engine move cannot be consumed as history-next.");
      assertFalse(
          remoteNode.next().isPresent(),
          "the rejected local move should not remain as the next mainline child.");
      assertEquals(
          Stone.EMPTY,
          harness.leelaz.copyStones()[stoneIndex(0, 1)],
          "the engine process must be restored to the rolled-back board before analysis resumes.");
      assertTrue(
          harness.leelaz.sentCommands.stream().anyMatch(command -> command.startsWith("loadsgf ")),
          "rollback should reload an exact snapshot into the engine rather than leaving the failed play applied.");
      assertEquals(
          1,
          harness.leelaz.ponderCount,
          "analysis should resume immediately after rollback; only physical placement is guarded briefly.");
      assertEquals(0, harness.leelaz.genmoveCount);
      assertTrue(
          getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"),
          "auto-play must keep the failed move guard until the remote board is observed or the guard expires.");
      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveAwaitingRemoteObservation"));
      assertTrue(
          harness.readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 1, Stone.BLACK),
          "no local place should be sent during the short remote-observation guard.");

      harness.board.place(0, 1, Stone.BLACK, false, false, false);

      assertSame(
          remoteNode,
          harness.board.getHistory().getCurrentHistoryNode(),
          "an immediate repeat of the failed move should be swallowed before it re-enters the mainline.");
      assertFalse(
          remoteNode.next().isPresent(),
          "an immediate repeat of the failed move should not recreate the rejected child.");
    }
  }

  @Test
  void pendingLocalMoveAckTimeoutRollsBackAndResumesAnalysisWithPlacementGuard() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode remoteNode = path.nodes.get(1);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      setField(
          harness.readBoard, "lastPendingLocalMoveRetryTimeMs", System.currentTimeMillis() - 5000L);
      harness.sync(
          snapshot(
              remoteNode.getData().stones,
              remoteNode.getData().lastMove,
              remoteNode.getData().lastMoveColor));

      assertSame(
          remoteNode,
          harness.board.getHistory().getCurrentHistoryNode(),
          "a pending place without any readboard result must roll back to the last remote-confirmed node.");
      assertSame(
          remoteNode,
          harness.board.getHistory().getMainEnd(),
          "the unconfirmed local child must be removed after the place-result timeout.");
      assertFalse(remoteNode.next().isPresent());
      assertFalse(harness.readBoard.lastMovePlayByLizzie);
      assertFalse(getBooleanField(harness.readBoard, "waitingForReadBoardLocalMoveAck"));
      assertEquals(
          Stone.EMPTY,
          harness.leelaz.copyStones()[stoneIndex(0, 1)],
          "the engine process must not keep the timed-out local move applied.");
      assertTrue(
          harness.leelaz.sentCommands.stream().anyMatch(command -> command.startsWith("loadsgf ")),
          "timeout recovery should reload the rolled-back board into the engine.");
      assertEquals(1, harness.leelaz.ponderCount);
      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));
      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveAwaitingRemoteObservation"));
      assertTrue(
          harness.readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 1, Stone.BLACK),
          "physical placement must still be guarded briefly after timeout recovery.");
    }
  }

  @Test
  void pendingLocalMoveAckTimeoutWithoutSyncFrameRollsBackAndResumesAnalysis() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode remoteNode = path.nodes.get(1);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      setField(
          harness.readBoard, "lastPendingLocalMoveRetryTimeMs", System.currentTimeMillis() - 5000L);

      CapturedTimeoutScheduler scheduler =
          (CapturedTimeoutScheduler) getField(harness.readBoard, "pendingLocalMoveTimeoutExecutor");
      scheduler.callbacks.get(0).run();

      assertSame(
          remoteNode,
          harness.board.getHistory().getMainEnd(),
          "a pending place must not wait forever when readboard sends no follow-up board frame.");
      assertEquals(1, harness.leelaz.ponderCount);
    }
  }

  @Test
  void placementFailureLineBypassesStalePlaceCompleteQuarantine() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode remoteNode = path.nodes.get(1);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      setField(harness.readBoard, "ignoreReadBoardPlaceResultsForCurrentPending", true);

      invokePlacementFailedLine(harness.readBoard);

      assertSame(
          remoteNode,
          harness.board.getHistory().getMainEnd(),
          "the stale-placeComplete quarantine must not swallow the real error place failed for the current command.");
      assertFalse(harness.readBoard.lastMovePlayByLizzie);
      assertFalse(getBooleanField(harness.readBoard, "waitingForReadBoardLocalMoveAck"));
      assertEquals(1, harness.leelaz.ponderCount);
    }
  }

  @Test
  void pendingLocalMoveRemoteChangeWithoutTargetRollsBackBeforeAckTimeout() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode remoteNode = path.nodes.get(1);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      setField(harness.readBoard, "lastPendingLocalMoveRetryTimeMs", System.currentTimeMillis());

      int[] misplacedSnapshot =
          snapshot(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(2, 0, Stone.BLACK)),
              Optional.of(new int[] {2, 0}),
              Stone.BLACK);

      harness.sync(misplacedSnapshot);

      assertSame(
          remoteNode,
          harness.board.getHistory().getMainEnd(),
          "if the remote board changed but never contains the pending target, the local pending move must be released immediately.");
      assertFalse(harness.readBoard.lastMovePlayByLizzie);
      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));

      harness.sync(misplacedSnapshot);

      assertEquals(3, harness.board.getHistory().getMainEnd().getData().moveNumber);
      assertTrue(
          getBooleanField(harness.readBoard, "failedLocalMoveWaitingForOurTurnAfterRemoteChange"));
    }
  }

  @Test
  void boardPlaceWhileReadBoardPendingDoesNotMutateMainline() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode pendingNode = path.nodes.get(2);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);

      harness.board.place(2, 2, Stone.WHITE, false, false, false);

      assertSame(
          pendingNode,
          harness.board.getHistory().getMainEnd(),
          "a new local place while readboard is still confirming the previous move must not rewrite the pending target.");
      assertFalse(
          pendingNode.next().isPresent(),
          "the suppressed local place must not create a new mainline child.");
    }
  }

  @Test
  void placementFailureWithUnchangedRemoteSnapshotResumesAnalysisForOurTurn() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode remoteNode = path.nodes.get(1);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);
      harness.sync(
          snapshot(
              remoteNode.getData().stones,
              remoteNode.getData().lastMove,
              remoteNode.getData().lastMoveColor));

      assertEquals(
          1,
          harness.leelaz.ponderCount,
          "if the remote board did not change, it is still our turn and analysis can resume.");
      assertFalse(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));
      assertFalse(getBooleanField(harness.readBoard, "failedLocalMoveAwaitingRemoteObservation"));
      assertFalse(
          getBooleanField(harness.readBoard, "failedLocalMoveWaitingForOurTurnAfterRemoteChange"));
      assertFalse(harness.readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 1, Stone.BLACK));
    }
  }

  @Test
  void placementFailureObservationSurvivesActiveFrameReset() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode remoteNode = path.nodes.get(1);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);

      harness.readBoard.parseLine("clear");

      assertTrue(
          harness.readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 1, Stone.BLACK),
          "readboard control lines must preserve the short failed-place observation gate.");

      harness.readBoard.parseLine("play");

      assertTrue(
          harness.readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 1, Stone.BLACK),
          "readboard play control lines must not clear the short failed-place observation gate.");

      harness.board.place(0, 1, Stone.BLACK, false, false, false);

      assertSame(remoteNode, harness.board.getHistory().getCurrentHistoryNode());
      assertFalse(remoteNode.next().isPresent());
    }
  }

  @Test
  void autoPlaySideChangeClearsStaleFailedPlacementRecovery() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);

      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));

      invokeClearFailedLocalMoveStateIfAutoPlaySideChanged(harness.readBoard, Stone.WHITE);

      assertTrue(
          getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"),
          "same-side play control should keep the failed-place guard.");

      invokeClearFailedLocalMoveStateIfAutoPlaySideChanged(harness.readBoard, Stone.BLACK);

      assertFalse(
          getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"),
          "switching auto-play from the failed side must drop stale failed-place recovery.");
      assertFalse(getBooleanField(harness.readBoard, "failedLocalMoveSuppressionActive"));
      assertFalse(harness.readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 1, Stone.BLACK));
    }
  }

  @Test
  void placementFailureWithRemoteChangeToOpponentTurnDoesNotResumeAutoPlace() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);
      harness.sync(
          snapshot(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(2, 0, Stone.BLACK)),
              Optional.of(new int[] {2, 0}),
              Stone.BLACK));

      assertEquals(
          1,
          harness.leelaz.ponderCount,
          "the rollback may resume analysis, but the remote-change state must not trigger another local place.");
      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));
      assertFalse(getBooleanField(harness.readBoard, "failedLocalMoveAwaitingRemoteObservation"));
      assertTrue(
          getBooleanField(harness.readBoard, "failedLocalMoveWaitingForOurTurnAfterRemoteChange"));
      assertTrue(
          harness.readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 2, Stone.BLACK),
          "while the remote board says it is the opponent's turn, even a different local move must wait.");
      assertEquals(3, harness.board.getHistory().getMainEnd().getData().moveNumber);
    }
  }

  @Test
  void placementFailureResumesAnalysisAfterOpponentRepliesAndItIsOurTurnAgain() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      buildHistory(
          harness.board,
          placement(0, 0, Stone.BLACK),
          placement(1, 0, Stone.WHITE),
          placement(0, 1, Stone.BLACK));

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);
      harness.sync(
          snapshot(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(2, 0, Stone.BLACK)),
              Optional.of(new int[] {2, 0}),
              Stone.BLACK));

      assertEquals(
          1,
          harness.leelaz.ponderCount,
          "after the misplaced local stone, analysis may already be running, but no local place should resume yet.");

      harness.sync(
          snapshot(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(2, 0, Stone.BLACK),
                  placement(2, 1, Stone.WHITE)),
              Optional.of(new int[] {2, 1}),
              Stone.WHITE));

      assertEquals(
          1,
          harness.leelaz.ponderCount,
          "if the observed remote board is back to our turn, analyze first and let the next move come from analysis.");
      assertFalse(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));
      assertFalse(
          getBooleanField(harness.readBoard, "failedLocalMoveWaitingForOurTurnAfterRemoteChange"));
      assertEquals(4, harness.board.getHistory().getMainEnd().getData().moveNumber);
    }
  }

  @Test
  void firstNoChangeFrameAfterRestartSchedulesResumeAnalysis() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);

      harness.readBoard.parseLine("start 3 3");
      harness.sync(
          snapshot(
              mainEnd.getData().stones,
              mainEnd.getData().lastMove,
              mainEnd.getData().lastMoveColor));

      assertEquals(1, harness.frame.scheduleResumeAnalysisCount);
      assertNotNull(harness.frame.lastScheduledResumeAction);
    }
  }

  @Test
  void singleMoveRecoverySchedulesResumeAnalysis() throws Exception {
    Stone[] beforeCapture =
        stones(
            placement(0, 1, Stone.BLACK),
            placement(1, 0, Stone.BLACK),
            placement(1, 1, Stone.WHITE),
            placement(2, 1, Stone.BLACK));
    Stone[] afterCapture =
        stones(
            placement(0, 1, Stone.BLACK),
            placement(1, 0, Stone.BLACK),
            placement(2, 1, Stone.BLACK),
            placement(1, 2, Stone.BLACK));

    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(beforeCapture, true))) {
      harness.sync(snapshot(afterCapture, Optional.of(new int[] {1, 2}), Stone.BLACK));

      assertEquals(1, harness.frame.scheduleResumeAnalysisCount);
      assertNotNull(harness.frame.lastScheduledResumeAction);
    }
  }

  @Test
  void gmaPendingDefersSingleMoveRecoveryEngineRestoreUntilOldTerminalPlayIsConsumed()
      throws Exception {
    Stone[] beforeCapture =
        stones(
            placement(0, 1, Stone.BLACK),
            placement(1, 0, Stone.BLACK),
            placement(1, 1, Stone.WHITE),
            placement(2, 1, Stone.BLACK));
    Stone[] afterCapture =
        stones(
            placement(0, 1, Stone.BLACK),
            placement(1, 0, Stone.BLACK),
            placement(2, 1, Stone.BLACK),
            placement(1, 2, Stone.BLACK));

    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(beforeCapture, true))) {
      harness.frame.bothSync = true;
      assertTrue(beginReadBoardGmaSessionForTest(harness.leelaz));
      setField(harness.readBoard, "readBoardGmaPending", true);
      setField(harness.readBoard, "readBoardGmaAutoPlayActive", true);

      harness.sync(snapshot(afterCapture, Optional.of(new int[] {1, 2}), Stone.BLACK));
      Stone engineColor =
          harness.board.getHistory().isBlacksTurn() ? Stone.BLACK : Stone.WHITE;
      setField(harness.readBoard, "readBoardGmaAutoPlayColor", engineColor);

      assertFalse(
          harness.leelaz.sentCommands.contains("clear_board"),
          "single-move recovery restore must be frozen while GMA is still in flight.");
      assertFalse(
          harness.leelaz.sentCommands.stream().anyMatch(command -> command.startsWith("loadsgf ")),
          "single-move recovery must not queue loadsgf behind an in-flight stale GMA request.");

      boolean consumed =
          harness.readBoard.handleReadBoardGmaEnginePlay(Board.convertCoordinatesToName(0, 0));

      assertTrue(consumed);
      harness.readBoard.afterReadBoardGmaTerminalResponseConsumed("test-terminal");
      assertTrue(
          waitForSentCommand(harness.leelaz, "clear_board"),
          "consuming old terminal play should release deferred single-move restore.");
      assertTrue(
          waitForSentCommandPrefix(harness.leelaz, "loadsgf "),
          "deferred single-move restore should use the recovered authoritative node.");
    }
  }

  @Test
  void syncCommandDoesNotStartPonderWhenEngineIsNotStarted() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.leelaz.started = false;

      harness.readBoard.parseLine("sync");

      assertEquals(0, harness.leelaz.ponderCount);
      assertEquals(true, harness.frame.syncBoard);
    }
  }

  @Test
  void noponderDoesNotStartPonderWhenAlreadyStopped() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.leelaz.notPondering();

      harness.readBoard.parseLine("noponder");

      assertEquals(0, harness.leelaz.togglePonderCount);
      assertEquals(0, harness.leelaz.ponderCount);
      assertFalse(harness.leelaz.isPondering());
      assertEquals("analysisState paused\n", harness.protocolOutput());
    }
  }

  @Test
  void noponderDoesNotRestartPonderAfterAutoPlayStopHandlesEngine() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.leelaz.Pondering();
      harness.frame.isAnaPlayingAgainstLeelaz = true;

      harness.readBoard.parseLine("noponder");

      assertEquals(1, harness.frame.stopAiPlayingAndPolicyCount);
      assertEquals(0, harness.leelaz.togglePonderCount);
      assertEquals(0, harness.leelaz.ponderCount);
      assertFalse(harness.leelaz.isPondering());
      assertEquals("analysisState paused\n", harness.protocolOutput());
    }
  }

  @Test
  void noponderStopsActivePonderWhenNoGameStopHandledIt() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.leelaz.Pondering();

      harness.readBoard.parseLine("noponder");

      assertEquals(1, harness.leelaz.togglePonderCount);
      assertEquals(1, harness.leelaz.nameCmdCount);
      assertEquals(0, harness.leelaz.ponderCount);
      assertFalse(harness.leelaz.isPondering());
      assertEquals("analysisState paused\n", harness.protocolOutput());
    }
  }

  @Test
  void resumeponderUsesManualToggleOnceAndReportsRunningState() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.leelaz.notPondering();

      harness.readBoard.parseLine("resumeponder");
      harness.readBoard.parseLine("resumeponder");

      assertEquals(1, harness.frame.togglePonderMannulCount);
      assertEquals(1, harness.leelaz.togglePonderCount);
      assertEquals("analysisState running\nanalysisState running\n", harness.protocolOutput());
    }
  }

  @Test
  void clearBoardClearsOnEdtWithoutMatchingLegacyClear() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      ArrayList<Integer> pendingSnapshot = new ArrayList<>(List.of(1));
      setField(harness.readBoard, "tempcount", pendingSnapshot);

      harness.readBoard.parseLine("clearBoard");

      assertEquals(1, harness.board.clearCount);
      assertEquals(true, harness.board.clearCalledOnEdt);
      assertEquals(pendingSnapshot, getField(harness.readBoard, "tempcount"));
    }
  }

  @Test
  void boardOnlyForceRebuildDoesNotScheduleResumeAnalysis() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);

      harness.leelaz.started = false;
      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              mainEnd.getData().stones,
              mainEnd.getData().lastMove,
              mainEnd.getData().lastMoveColor));

      assertEquals(0, harness.frame.scheduleResumeAnalysisCount);
      assertNull(harness.frame.lastScheduledResumeAction);

      harness.leelaz.started = true;
      assertEquals(0, harness.leelaz.ponderCount);
    }
  }

  @Test
  void scheduledResumeAnalysisDoesNotRunAfterUserNavigatesAwayFromTarget() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);
      BoardHistoryNode root = harness.board.getHistory().getStart();

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              mainEnd.getData().stones,
              mainEnd.getData().lastMove,
              mainEnd.getData().lastMoveColor));

      Runnable scheduledAction = harness.frame.lastScheduledResumeAction;
      assertNotNull(scheduledAction, "sync should bind a target-aware resume action.");

      harness.board.moveToAnyPosition(root);
      scheduledAction.run();

      assertEquals(0, harness.leelaz.ponderCount);
    }
  }


  @Test
  void syncSpecificResumeSkipsAutoQuickAnalyzeLoadedGame() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      Lizzie.config.autoQuickAnalyzeOnLoad = true;
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      harness.board.moveToAnyPosition(path.nodes.get(path.nodes.size() - 1));

      boolean resumed = harness.frame.ensureAnalysisResumedAfterSyncLoad();

      assertEquals(true, resumed);
      assertEquals(1, harness.leelaz.ponderCount);
      assertEquals(0, harness.frame.flashAnalyzeGameCount);
    }
  }

  @Test
  void readBoardPlayLineKeepsAnalysisWideRootNoiseEnabled() throws Exception {
    assertPlayLineKeepsAnalysisWideRootNoise("play>black>5 1000 0", false);
  }

  @Test
  void readBoardGmaPlayLineKeepsAnalysisWideRootNoiseEnabled() throws Exception {
    assertPlayLineKeepsAnalysisWideRootNoise("play>white>5 1000 0 gma", true);
  }

  @Test
  void readBoardEndsyncLeavesWRNOffAfterUserUnchecks() throws Exception {
    assertEndsyncKeepsUserWRNChoice(true);
  }

  @Test
  void readBoardEndsyncLeavesWRNOnIfStillOn() throws Exception {
    assertEndsyncKeepsUserWRNChoice(false);
  }


  @Test
  void readBoardGmaPlayLineWaitsForSyncedBoardBeforeStartingEngineDecision() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();
      HistoryPath path = buildHistory(harness.board, placement(0, 0, Stone.BLACK));
      BoardHistoryNode staleWhiteTurnNode = path.nodes.get(0);
      harness.board.moveToAnyPosition(staleWhiteTurnNode);

      harness.readBoard.parseLine("play>white>0 0 0 gma");

      assertEquals(
          0,
          harness.leelaz.readBoardGmaCount,
          "play> only arms GMA autoplay; it must not start before the next synced board frame.");

      Stone[] blackToPlayRemoteStones = staleWhiteTurnNode.getData().stones.clone();
      blackToPlayRemoteStones[stoneIndex(1, 0)] = Stone.WHITE;
      harness.sync(snapshot(blackToPlayRemoteStones, Optional.of(new int[] {1, 0}), Stone.WHITE));

      assertEquals(
          0,
          harness.leelaz.readBoardGmaCount,
          "after sync, configured white autoplay must wait because the synced board is black to play.");
    }
  }

  @Test
  void readBoardGmaActivationArmsWithoutTakingLifecycleOwnership() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      RecordingLifecycleLeelaz foreground = new RecordingLifecycleLeelaz();
      Lizzie.leelaz = foreground;

      harness.readBoard.parseLine("play>white>0 0 0 gma");

      assertEquals(1, foreground.armCheckCount);
      assertEquals(0, foreground.beginLifecycleCount);
      assertEquals(0, foreground.endLifecycleCount);
      assertTrue(harness.readBoard.isReadBoardGmaAutoPlayActive());
    }
  }

  @Test
  void readBoardGmaActivationRejectedByForegroundLeaseHasNoSideEffects() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      RecordingLifecycleLeelaz foreground = new RecordingLifecycleLeelaz();
      foreground.allowArm = false;
      Lizzie.leelaz = foreground;
      setField(harness.readBoard, "failedLocalMoveSuppressionActive", true);
      setField(harness.readBoard, "failedLocalMoveSuppressionX", 2);
      setField(harness.readBoard, "failedLocalMoveSuppressionY", 3);
      setField(harness.readBoard, "failedLocalMoveSuppressionColor", Stone.BLACK);
      setField(harness.readBoard, "failedLocalMoveRecoveryActive", true);
      setField(harness.readBoard, "failedLocalMoveRecoveryX", 2);
      setField(harness.readBoard, "failedLocalMoveRecoveryY", 3);
      setField(harness.readBoard, "failedLocalMoveRecoveryColor", Stone.BLACK);
      setField(harness.readBoard, "failedLocalMoveAwaitingRemoteObservation", true);

      harness.readBoard.parseLine("play>white>5 12 34 gma");

      assertEquals(1, foreground.armCheckCount);
      assertEquals(0, foreground.beginLifecycleCount);
      assertEquals(0, foreground.endLifecycleCount);
      assertFalse(harness.readBoard.isReadBoardGmaAutoPlayActive());
      assertFalse(getBooleanField(harness.readBoard, "readBoardGmaAwaitingSyncedBoard"));
      assertEquals(0, getIntField(harness.readBoard, "readBoardGmaTimeSeconds"));
      assertEquals(0, getIntField(harness.readBoard, "readBoardGmaMaxVisits"));
      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveSuppressionActive"));
      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));
      assertTrue(
          getBooleanField(harness.readBoard, "failedLocalMoveAwaitingRemoteObservation"));
      assertEquals(Stone.BLACK, getField(harness.readBoard, "failedLocalMoveRecoveryColor"));
    }
  }

  @Test
  void readBoardGmaPlayLineColorSwitchUpdatesAuthorizationWhenAlreadyArmed() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      RecordingLifecycleLeelaz foreground = new RecordingLifecycleLeelaz();
      Lizzie.leelaz = foreground;

      harness.readBoard.parseLine("play>white>5 12 34 gma");

      assertTrue(harness.readBoard.isReadBoardGmaAutoPlayActive());
      assertEquals(Stone.WHITE, getField(harness.readBoard, "readBoardGmaAutoPlayColor"));
      assertTrue(LizzieFrame.toolbar.chkAutoPlayWhite.isSelected());
      assertFalse(LizzieFrame.toolbar.chkAutoPlayBlack.isSelected());

      foreground.allowArm = false;
      harness.readBoard.parseLine("play>black>5 12 34 gma");

      assertTrue(
          harness.readBoard.isReadBoardGmaAutoPlayActive(),
          "color switch must keep the existing GMA autoplay authorization");
      assertEquals(
          Stone.BLACK,
          getField(harness.readBoard, "readBoardGmaAutoPlayColor"),
          "already-armed GMA must accept play> color changes instead of treating itself as a foreign exclusive task");
      assertTrue(getBooleanField(harness.readBoard, "readBoardGmaAwaitingSyncedBoard"));
      assertTrue(LizzieFrame.toolbar.chkAutoPlayBlack.isSelected());
      assertFalse(LizzieFrame.toolbar.chkAutoPlayWhite.isSelected());
      assertEquals(5, getIntField(harness.readBoard, "readBoardGmaTimeSeconds"));
      assertEquals(12, getIntField(harness.readBoard, "readBoardGmaMaxVisits"));
    }
  }

  @Test
  void readBoardGmaPlayLineColorSwitchInvalidatesInFlightWhiteResult() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      RecordingLifecycleLeelaz foreground = new RecordingLifecycleLeelaz();
      Lizzie.leelaz = foreground;

      harness.readBoard.parseLine("play>white>5 12 34 gma");
      Object helperIdentity = new Object();
      long generation = ((Number) getField(harness.readBoard, "readBoardGmaSessionGeneration")).longValue();
      setField(harness.readBoard, "readBoardGmaPending", true);
      setField(harness.readBoard, "readBoardGmaPendingIdentity", helperIdentity);
      setField(harness.readBoard, "readBoardGmaPendingGeneration", generation);
      setField(harness.readBoard, "trackingEligibilityIdentity", helperIdentity);

      foreground.allowArm = false;
      harness.readBoard.parseLine("play>black>5 12 34 gma");

      assertEquals(Stone.BLACK, getField(harness.readBoard, "readBoardGmaAutoPlayColor"));
      assertTrue(getBooleanField(harness.readBoard, "readBoardGmaPendingLogicallyInvalid"));

      boolean consumed =
          harness.readBoard.handleReadBoardGmaEnginePlay(helperIdentity, generation, "A1");

      assertTrue(consumed, "stale white GMA must be consumed as an invalidated result");
      assertFalse(
          getBooleanField(harness.readBoard, "readBoardGmaPending"),
          "invalidated white GMA must not stay pending after its late result");
      int occupied = 0;
      for (Stone stone : harness.board.getHistory().getStones()) {
        if (!stone.isEmpty()) {
          occupied++;
        }
      }
      assertEquals(
          0,
          occupied,
          "a late white GMA play must not be placed after switching to black");
    }
  }


  @Test
  void activatedReadBoardGmaBlocksForegroundAnalysisLease() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      Leelaz foreground = new Leelaz("");
      foreground.isLoaded = true;
      foreground.started = true;
      foreground.isKatago = true;
      foreground.commandLists.addAll(
          List.of(
              "stop",
              "boardsize",
              "komi",
              "kata-get-rules",
              "kata-set-rules",
              "clear_board",
              "play",
              "set_position",
              "kata-analyze"));
      setField(foreground, "endGetCommandList", true);
      setField(
          foreground,
          "outputStream",
          new BufferedOutputStream(new ByteArrayOutputStream()));
      Lizzie.leelaz = foreground;

      harness.readBoard.parseLine("play>white>0 0 0 gma");

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
          foreground.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void readBoardGmaStartsAfterTrustedFoxCornerMarkerShowsConfiguredSideToMove() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.readBoard.parseLine("lastMoveSource foxCornerFlip");
      assertEquals(0, harness.leelaz.readBoardGmaCount);

      Stone[] whiteToPlayRemoteStones = emptyStones();
      whiteToPlayRemoteStones[stoneIndex(0, 0)] = Stone.BLACK;
      harness.sync(snapshot(whiteToPlayRemoteStones, Optional.of(new int[] {0, 0}), Stone.BLACK));

      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("W", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void readBoardGmaStartsAfterTrustedRedBlueMarkerShowsConfiguredSideToMove() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();

      harness.readBoard.parseLine("play>black>0 0 0 gma");
      harness.readBoard.parseLine("lastMoveSource redBlueMarker");

      Stone[] blackToPlayRemoteStones = emptyStones();
      blackToPlayRemoteStones[stoneIndex(0, 0)] = Stone.WHITE;
      harness.sync(snapshot(blackToPlayRemoteStones, Optional.of(new int[] {0, 0}), Stone.WHITE));

      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("B", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void readBoardGmaSkipsUntrustedHeuristicTurnEvenWhenConfiguredSideMatches()
      throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();
      harness.board.hasStartStone = true;

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.readBoard.parseLine("foxMoveNumber 1");
      harness.readBoard.parseLine("lastMoveSource stoneCount");

      Stone[] whiteToPlayRemoteStones = emptyStones();
      whiteToPlayRemoteStones[stoneIndex(0, 0)] = Stone.BLACK;
      harness.sync(snapshot(whiteToPlayRemoteStones, Optional.empty(), Stone.EMPTY));

      assertEquals(0, harness.leelaz.readBoardGmaCount);
      assertFalse(getBooleanField(harness.readBoard, "readBoardGmaPending"));
    }
  }

  @Test
  void readBoardGmaSkipsMissingLastMoveSourceWhenSetupRiskExists() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();
      harness.board.hasStartStone = true;

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.readBoard.parseLine("foxMoveNumber 1");

      Stone[] whiteToPlayRemoteStones = emptyStones();
      whiteToPlayRemoteStones[stoneIndex(0, 0)] = Stone.BLACK;
      harness.sync(snapshot(whiteToPlayRemoteStones, Optional.empty(), Stone.EMPTY));

      assertEquals(0, harness.leelaz.readBoardGmaCount);
      assertFalse(getBooleanField(harness.readBoard, "readBoardGmaPending"));
    }
  }

  @Test
  void readBoardGmaStartsForFoxZeroMoveAllBlackSetupAsWhiteTurn() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("foxMoveNumber 0");
      harness.readBoard.parseLine("lastMoveSource stoneCount");

      Stone[] handicapSetupStones =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(2, 0, Stone.BLACK),
              placement(1, 1, Stone.BLACK),
              placement(0, 2, Stone.BLACK),
              placement(2, 2, Stone.BLACK));
      harness.sync(snapshot(handicapSetupStones, Optional.empty(), Stone.EMPTY));

      assertFalse(harness.board.getHistory().isBlacksTurn());
      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("W", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void readBoardGmaTrustsFoxZeroMoveHandicapSetupAfterForceRebuildFromDirtyLocalHistory()
      throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();
      buildHistory(
          harness.board,
          placement(0, 0, Stone.BLACK),
          placement(1, 0, Stone.WHITE),
          placement(0, 1, Stone.BLACK));

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("foxMoveNumber 0");
      harness.readBoard.parseLine("lastMoveSource stoneCount");
      harness.readBoard.parseLine("forceRebuild");

      Stone[] handicapSetupStones =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(2, 0, Stone.BLACK),
              placement(1, 1, Stone.BLACK),
              placement(0, 2, Stone.BLACK),
              placement(2, 2, Stone.BLACK));
      harness.sync(snapshot(handicapSetupStones, Optional.empty(), Stone.EMPTY));

      assertFalse(harness.board.getHistory().isBlacksTurn());
      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("W", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void readBoardGmaCorrectsExistingFoxZeroMoveAllBlackSetupSnapshotTurn() throws Exception {
    Stone[] handicapSetupStones =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(2, 0, Stone.BLACK),
            placement(1, 1, Stone.BLACK),
            placement(0, 2, Stone.BLACK),
            placement(2, 2, Stone.BLACK));
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(handicapSetupStones, true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("foxMoveNumber 0");
      harness.readBoard.parseLine("lastMoveSource stoneCount");
      harness.sync(snapshot(handicapSetupStones, Optional.empty(), Stone.EMPTY));

      assertFalse(harness.board.getHistory().isBlacksTurn());
      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("W", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void readBoardGmaTrustsUnchangedExistingSnapshotTurn() throws Exception {
    Stone[] whiteToPlaySnapshot = stones(placement(0, 0, Stone.BLACK));
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(whiteToPlaySnapshot, false))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.sync(snapshot(whiteToPlaySnapshot, Optional.empty(), Stone.EMPTY));

      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("W", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void readBoardGmaStartsAfterReadBoardExchangeOrderOverridesTurnTrust() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.canAddPlayer = true;
      harness.leelaz.enableReadBoardGmaSupport();

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("foxMoveNumber 1");
      harness.readBoard.parseLine("lastMoveSource stoneCount");

      Stone[] setupStones =
          stones(placement(0, 0, Stone.BLACK), placement(2, 2, Stone.BLACK));
      harness.sync(snapshot(setupStones, Optional.empty(), Stone.EMPTY));
      assertEquals(0, harness.leelaz.readBoardGmaCount);

      harness.readBoard.parseLine("pass");

      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("W", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void readBoardGmaStartsAfterGenericExchangeOrderOverridesTurnTrust() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.canAddPlayer = true;
      harness.leelaz.enableReadBoardGmaSupport();

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.readBoard.parseLine("lastMoveSource stoneCount");

      Stone[] setupStones =
          stones(placement(0, 0, Stone.BLACK), placement(2, 2, Stone.BLACK));
      harness.sync(snapshot(setupStones, Optional.empty(), Stone.EMPTY));
      assertEquals(0, harness.leelaz.readBoardGmaCount);

      harness.readBoard.parseLine("pass");

      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("W", harness.leelaz.lastReadBoardGmaColor);
      assertFalse(
          harness.board.getHistory().getCurrentHistoryNode().getData().isPassNode(),
          "ReadBoard exchange-order pass must not create a real PASS node.");
    }
  }

  @Test
  void readBoardGmaStartsAfterGenericHeuristicSingleMoveSyncTrustsAcceptedMove()
      throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.readBoard.parseLine("lastMoveSource stoneCount");

      Stone[] whiteToPlayRemoteStones = stones(placement(0, 0, Stone.BLACK));
      harness.sync(snapshot(whiteToPlayRemoteStones, Optional.of(new int[] {0, 0}), Stone.BLACK));

      assertFalse(harness.board.getHistory().isBlacksTurn());
      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("W", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void readBoardGmaWaitsForFailedPlaceObservationBeforeRestartingEngineDecision()
      throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();
      harness.readBoard.parseLine("play>white>5 0 0 gma");
      harness.readBoard.parseLine("lastMoveSource redBlueMarker");

      Stone[] remoteStones = stones(placement(0, 0, Stone.BLACK));
      harness.sync(snapshot(remoteStones, Optional.of(new int[] {0, 0}), Stone.BLACK));
      assertEquals(1, harness.leelaz.readBoardGmaCount);

      setField(harness.readBoard, "readBoardGmaPending", false);
      harness.leelaz.retireReadBoardGmaSession();
      harness.board.getHistory().place(1, 0, Stone.WHITE, false);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);

      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveAwaitingRemoteObservation"));
      assertEquals(
          1,
          harness.leelaz.readBoardGmaCount,
          "GMA must not start a second uncancellable engine decision while the failed place is "
              + "still waiting for remote-board observation.");
    }
  }

  @Test
  void readBoardGmaStartsAfterGenericHandicapSingleMoveSyncTrustsAcceptedMove()
      throws Exception {
    Stone[] setupStones = stones(placement(0, 0, Stone.BLACK), placement(2, 0, Stone.BLACK));
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(setupStones, false))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();
      buildHistory(harness.board, placement(1, 1, Stone.WHITE));

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.readBoard.parseLine("lastMoveSource stoneCount");

      Stone[] whiteToPlayRemoteStones =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(2, 0, Stone.BLACK),
              placement(1, 1, Stone.WHITE),
              placement(0, 2, Stone.BLACK));
      harness.sync(snapshot(whiteToPlayRemoteStones, Optional.of(new int[] {0, 2}), Stone.BLACK));

      assertFalse(harness.board.getHistory().isBlacksTurn());
      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("W", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void readBoardGmaStartsAfterMarkerlessOrdinaryFoxSyncTrustsTurn() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("foxMoveNumber 1");
      harness.readBoard.parseLine("lastMoveSource none");

      Stone[] whiteToPlayRemoteStones = emptyStones();
      whiteToPlayRemoteStones[stoneIndex(0, 0)] = Stone.BLACK;
      harness.sync(snapshot(whiteToPlayRemoteStones, Optional.empty(), Stone.EMPTY));

      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("W", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void readBoardGmaStartsWhenExplicitPlTrustsSnapshotTurn() throws Exception {
    Stone[] whiteToPlayStones = stones(placement(0, 0, Stone.BLACK));
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(whiteToPlayStones, false))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();
      harness.board.getHistory().getCurrentHistoryNode().getData().addProperty("PL", "W");

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.sync(snapshot(whiteToPlayStones, Optional.empty(), Stone.EMPTY));

      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("W", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void readBoardGmaStartsWhenRebuiltSnapshotCopiesExplicitPl() throws Exception {
    Stone[] anchorStones = stones(placement(0, 0, Stone.BLACK));
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(anchorStones, false))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();
      harness.board.getHistory().getCurrentHistoryNode().getData().addProperty("PL", "W");
      buildHistory(harness.board, placement(1, 0, Stone.WHITE));

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              stones(placement(0, 0, Stone.BLACK), placement(2, 0, Stone.WHITE)),
              Optional.empty(),
              Stone.EMPTY));

      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("W", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void readBoardGmaStartsWhenSourceOnlyUpdateRefreshesTurnTrust() throws Exception {
    Stone[] blackToPlayRemoteStones = stones(placement(0, 0, Stone.WHITE));
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(blackToPlayRemoteStones, true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();
      harness.readBoard.parseLine("play>black>0 0 0 gma");

      int[] snapshot =
          snapshot(blackToPlayRemoteStones, Optional.of(new int[] {0, 0}), Stone.WHITE);

      harness.readBoard.parseLine("lastMoveSource stoneCount");
      harness.sync(snapshot);
      assertEquals(0, harness.leelaz.readBoardGmaCount);
      assertFalse(getBooleanField(harness.readBoard, "readBoardGmaPending"));

      harness.readBoard.parseLine("lastMoveSource foxCornerFlip");
      harness.sync(snapshot);

      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("B", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void readBoardGmaStartsForTrustedEmptyBoardBlackOpening() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();

      harness.readBoard.parseLine("play>black>0 0 0 gma");
      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));

      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("B", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void websocketGmaWaitsForPonderingNoticeThenContinuesWithoutPondering() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaFixedLimitOnlySupport();
      Lizzie.config.readBoardPonder = true;
      Lizzie.config.suppressReadBoardWebSocketPonderingNotice = false;

      harness.readBoard.parseLine("play>black>5 12 0 gma");
      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));

      assertEquals(1, harness.frame.readBoardPonderingNoticeCount);
      assertEquals(0, harness.leelaz.readBoardGmaCount);
      assertTrue(Lizzie.config.readBoardPonder);

      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));

      assertEquals(1, harness.frame.readBoardPonderingNoticeCount);
      assertEquals(0, harness.leelaz.readBoardGmaCount);

      harness.frame.answerReadBoardPonderingNotice(false);

      assertEquals(1, harness.frame.readBoardPonderingNoticeCount);
      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals(
          "kata-genmove_analyze B maxTime=5 maxVisits=12 ponder=false",
          harness.leelaz.sentCommands.get(0));
      assertTrue(Lizzie.config.readBoardPonder);
    }
  }

  @Test
  void closingWebsocketPonderingNoticeDoesNotStartGmaOrPromptAgainInSameSession()
      throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaFixedLimitOnlySupport();
      Lizzie.config.readBoardPonder = true;
      Lizzie.config.suppressReadBoardWebSocketPonderingNotice = false;

      harness.readBoard.parseLine("play>black>5 12 0 gma");
      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));
      harness.frame.closeReadBoardPonderingNotice();
      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));

      assertEquals(1, harness.frame.readBoardPonderingNoticeCount);
      assertEquals(0, harness.leelaz.readBoardGmaCount);
      assertFalse(Lizzie.config.suppressReadBoardWebSocketPonderingNotice);
      assertTrue(Lizzie.config.readBoardPonder);
    }
  }

  @Test
  void websocketPonderingNoticeIsEligibleAgainAfterStopSyncStartsANewGmaSession()
      throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaFixedLimitOnlySupport();
      Lizzie.config.readBoardPonder = true;
      Lizzie.config.suppressReadBoardWebSocketPonderingNotice = false;

      harness.readBoard.parseLine("play>black>5 12 0 gma");
      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));
      assertEquals(1, harness.frame.readBoardPonderingNoticeCount);

      harness.readBoard.parseLine("stopsync");
      harness.frame.bothSync = true;
      harness.readBoard.parseLine("play>black>5 12 0 gma");
      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));

      assertEquals(2, harness.frame.readBoardPonderingNoticeCount);
      assertEquals(0, harness.leelaz.readBoardGmaCount);
    }
  }

  @Test
  void stalePonderingNoticeAcknowledgementAfterStopSyncDoesNotRestartGma() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaFixedLimitOnlySupport();
      Lizzie.config.readBoardPonder = true;
      Lizzie.config.suppressReadBoardWebSocketPonderingNotice = false;

      harness.readBoard.parseLine("play>black>5 12 0 gma");
      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));
      assertEquals(1, harness.frame.readBoardPonderingNoticeCount);

      harness.readBoard.parseLine("stopsync");
      harness.frame.answerReadBoardPonderingNotice(false);

      assertEquals(0, harness.leelaz.readBoardGmaCount);
      assertFalse(harness.readBoard.isReadBoardGmaAutoPlayActive());
    }
  }

  @Test
  void acknowledgedPonderingDifferenceIsNotPromptedAgainForLaterMoveInSameSession()
      throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaFixedLimitOnlySupport();
      Lizzie.config.readBoardPonder = true;
      Lizzie.config.suppressReadBoardWebSocketPonderingNotice = false;

      harness.readBoard.parseLine("play>black>5 12 0 gma");
      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));
      harness.frame.answerReadBoardPonderingNotice(false);
      assertEquals(1, harness.leelaz.readBoardGmaCount);

      assertTrue(harness.readBoard.handleReadBoardGmaEnginePlay("pass"));
      harness.leelaz.isThinking = false;
      harness.leelaz.blockNextLoadSgf();
      harness.readBoard.afterReadBoardGmaTerminalResponseConsumed("first-move");
      assertTrue(harness.leelaz.awaitBlockedLoadSgf());
      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));
      assertEquals(
          1,
          harness.leelaz.readBoardGmaCount,
          "the next GMA request must wait until the exact engine restore has completed");
      harness.leelaz.releaseBlockedLoadSgf();

      assertEquals(1, harness.frame.readBoardPonderingNoticeCount);
      assertTrue(
          waitForReadBoardGmaCount(harness.leelaz, 2),
          "the completed restore must resume GMA after the synced board arrived during loadsgf");
    }
  }

  @Test
  void closingReadBoardDuringDelayedSnapshotLoadKeepsFileAliveForEngineRestart()
      throws Exception {
    Stone[] authoritativeStones = stones(placement(0, 0, Stone.BLACK));
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(authoritativeStones, false))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();
      Lizzie.config.suppressReadBoardWebSocketPonderingNotice = true;

      harness.readBoard.parseLine("play>white>5 12 0 gma");
      harness.sync(snapshot(authoritativeStones, Optional.empty(), Stone.EMPTY));
      assertEquals(1, harness.leelaz.readBoardGmaCount);

      assertTrue(harness.readBoard.handleReadBoardGmaEnginePlay("pass"));
      harness.leelaz.isThinking = false;
      harness.leelaz.blockNextLoadSgf();
      harness.readBoard.afterReadBoardGmaTerminalResponseConsumed("close-readboard");
      assertTrue(
          harness.leelaz.awaitBlockedLoadSgf(),
          "the exact snapshot load must reach the engine before closing ReadBoard");

      Path pendingSnapshot = harness.leelaz.pendingLoadSgf();
      assertNotNull(pendingSnapshot);
      assertTrue(
          Files.exists(pendingSnapshot),
          "the temporary SGF must exist while the engine has not consumed loadsgf");

      harness.readBoard.shutdown();

      assertNull(harness.frame.readBoard, "closing ReadBoard should detach the helper window");
      assertTrue(
          Files.exists(pendingSnapshot),
          "closing ReadBoard must not delete an SGF still being consumed by KataGo");

      harness.leelaz.releaseBlockedLoadSgf();
      assertTrue(
          waitForFileDeletion(pendingSnapshot),
          "the temporary SGF should be cleaned only after KataGo consumes it");

      SnapshotTrackingLeelaz restartedEngine = SnapshotTrackingLeelaz.create();
      Lizzie.leelaz = restartedEngine;
      harness.board.resendMoveToEngine(restartedEngine, false);

      assertNotNull(
          restartedEngine.lastLoadedSgfContent(),
          "a restarted engine should load the authoritative snapshot without a missing-file error");
      assertTrue(restartedEngine.lastLoadedSgfContent().contains("AB[aa]"));
      assertTrue(restartedEngine.lastLoadedSgfContent().contains("PL[W]"));
      assertTrue(
          waitForFileDeletion(restartedEngine.lastLoadedSgf()),
          "the restarted engine snapshot should also be cleaned after consumption");
    }
  }

  @Test
  void suppressedWebsocketPonderingNoticeContinuesWithoutChangingReadBoardPreference()
      throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaFixedLimitOnlySupport();
      Lizzie.config.readBoardPonder = true;
      Lizzie.config.suppressReadBoardWebSocketPonderingNotice = true;

      harness.readBoard.parseLine("play>black>6 24 0 gma");
      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));

      assertEquals(0, harness.frame.readBoardPonderingNoticeCount);
      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals(
          "kata-genmove_analyze B maxTime=6 maxVisits=24 ponder=false",
          harness.leelaz.sentCommands.get(0));
      assertTrue(Lizzie.config.readBoardPonder);
    }
  }

  @Test
  void noLongerShowActionSuppressesNoticeAndImmediatelyContinuesCurrentGma() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaFixedLimitOnlySupport();
      Lizzie.config.readBoardPonder = true;
      Lizzie.config.suppressReadBoardWebSocketPonderingNotice = false;

      harness.readBoard.parseLine("play>black>7 48 0 gma");
      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));
      harness.frame.answerReadBoardPonderingNotice(true);

      assertEquals(1, ((TrackingConfig) Lizzie.config).suppressionCount);
      assertTrue(Lizzie.config.suppressReadBoardWebSocketPonderingNotice);
      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals(
          "kata-genmove_analyze B maxTime=7 maxVisits=48 ponder=false",
          harness.leelaz.sentCommands.get(0));
      assertTrue(Lizzie.config.readBoardPonder);
    }
  }

  @Test
  void readBoardGmaLeaseRejectionIsOneShotUntilANewPlayGeneration() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();
      harness.leelaz.rejectReadBoardGma = true;

      harness.readBoard.parseLine("play>black>0 0 0 gma");
      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));
      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));

      assertEquals(0, harness.leelaz.readBoardGmaCount);
      assertEquals(1, harness.leelaz.readBoardGmaAttemptCount);
      assertFalse(getBooleanField(harness.readBoard, "readBoardGmaPending"));

      harness.readBoard.parseLine("play>black>0 0 0 gma");
      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));

      assertEquals(2, harness.leelaz.readBoardGmaAttemptCount);
    }
  }

  @Test
  void readBoardGmaSkipsEmptyBoardDefaultTurnWhenSetupRiskExists() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();
      harness.board.hasStartStone = true;

      harness.readBoard.parseLine("play>black>0 0 0 gma");
      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));

      assertEquals(0, harness.leelaz.readBoardGmaCount);
      assertFalse(getBooleanField(harness.readBoard, "readBoardGmaPending"));
    }
  }

  @Test
  void gmaFinalMoveReplayingExistingNextNodeStillSendsReadBoardPlace() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.frame.syncBoard = true;
      setField(
          harness.readBoard,
          "placeCommandDispatcher",
          (java.util.concurrent.Executor) Runnable::run);
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode beforeEngineMove = path.nodes.get(1);
      BoardHistoryNode existingEngineMove = path.nodes.get(2);
      harness.board.moveToAnyPosition(beforeEngineMove);

      ByteArrayOutputStream readBoardBytes = new ByteArrayOutputStream();
      Lizzie.config.readBoardPonder = false;
      harness.readBoard.process = new AliveProcess();
      setField(harness.readBoard, "usePipe", true);
      setField(harness.readBoard, "outputStream", new BufferedOutputStream(readBoardBytes));
      setField(harness.readBoard, "readBoardGmaPending", true);
      setField(harness.readBoard, "readBoardGmaAutoPlayActive", true);
      setField(harness.readBoard, "readBoardGmaAutoPlayColor", Stone.BLACK);

      boolean consumed =
          harness.readBoard.handleReadBoardGmaEnginePlay(Board.convertCoordinatesToName(0, 1));

      assertTrue(consumed);
      assertSame(
          existingEngineMove,
          harness.board.getHistory().getCurrentHistoryNode(),
          "GMA final move should replay the existing next node instead of creating a duplicate.");
      assertTrue(
          new String(readBoardBytes.toByteArray(), StandardCharsets.UTF_8).contains("place 0 1\n"),
          "GMA final move must still click ReadBoard when local history already contains that next move.");
      assertTrue(
          harness.leelaz.playedMoves.isEmpty(),
          "GMA final move comes from KataGo and must not be echoed back as a normal GTP play.");
      assertFalse(
          harness.leelaz.sentCommands.contains("stop"),
          "GMA final move must not use the generic no-ponder stop path.");
    }
  }

  @Test
  void gmaFinalNonBoardMoveForcesExactEngineRestore() throws Exception {
    Stone[] authoritativeStones = stones(placement(0, 0, Stone.BLACK));
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(authoritativeStones, false))) {
      harness.frame.bothSync = true;
      assertTrue(beginReadBoardGmaSessionForTest(harness.leelaz));
      setField(harness.readBoard, "readBoardGmaPending", true);
      setField(harness.readBoard, "readBoardGmaAutoPlayActive", true);
      setField(harness.readBoard, "readBoardGmaAutoPlayColor", Stone.WHITE);

      boolean consumed = harness.readBoard.handleReadBoardGmaEnginePlay("pass");

      assertTrue(consumed);
      assertFalse(getBooleanField(harness.readBoard, "readBoardGmaPending"));
      harness.readBoard.afterReadBoardGmaTerminalResponseConsumed("test-terminal");
      assertTrue(
          waitForSentCommand(harness.leelaz, "clear_board"),
          "non-board GMA terminal result must force engine restore.");
      assertTrue(
          waitForSentCommandPrefix(harness.leelaz, "loadsgf "),
          "non-board GMA terminal result must restore the authoritative snapshot exactly.");
      String restoredSgf = harness.leelaz.lastLoadedSgfContent();
      assertTrue(
          restoredSgf.contains("AB[aa]"),
          "GMA exact restore must capture the authoritative current stone placement.");
      assertTrue(
          restoredSgf.contains("PL[W]"),
          "GMA exact restore must capture the authoritative current side to play.");
    }
  }

  @Test
  void gmaPendingDefersRebuildEngineRestoreUntilOldTerminalPlayIsConsumed() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      assertTrue(beginReadBoardGmaSessionForTest(harness.leelaz));
      setField(harness.readBoard, "readBoardGmaPending", true);
      setField(harness.readBoard, "readBoardGmaAutoPlayActive", true);

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(snapshot(stones(placement(0, 0, Stone.BLACK)), Optional.empty(), Stone.EMPTY));
      Stone engineColor =
          harness.board.getHistory().isBlacksTurn() ? Stone.BLACK : Stone.WHITE;
      setField(harness.readBoard, "readBoardGmaAutoPlayColor", engineColor);

      assertFalse(
          harness.leelaz.sentCommands.contains("clear_board"),
          "rebuild restore must be frozen while the old GMA request is still in flight.");
      assertFalse(
          harness.leelaz.sentCommands.stream().anyMatch(command -> command.startsWith("loadsgf ")),
          "rebuild restore must not queue loadsgf behind an in-flight stale GMA request.");

      boolean consumed =
          harness.readBoard.handleReadBoardGmaEnginePlay(Board.convertCoordinatesToName(1, 0));

      assertTrue(consumed);
      harness.readBoard.afterReadBoardGmaTerminalResponseConsumed("test-terminal");
      assertTrue(
          waitForSentCommand(harness.leelaz, "clear_board"),
          "consuming the old terminal play should release the deferred exact restore.");
      assertTrue(
          waitForSentCommandPrefix(harness.leelaz, "loadsgf "),
          "deferred restore should use the latest authoritative snapshot.");
    }
  }

  @Test
  void gmaRestoreInProgressDefersRebuildEngineRestoreUntilCurrentRestoreFinishes()
      throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      assertTrue(beginReadBoardGmaSessionForTest(harness.leelaz));
      setField(harness.readBoard, "readBoardGmaEngineRestorePending", true);
      setField(harness.readBoard, "readBoardGmaEngineRestoreInProgress", true);

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(snapshot(stones(placement(0, 0, Stone.BLACK)), Optional.empty(), Stone.EMPTY));

      assertFalse(
          harness.leelaz.sentCommands.contains("clear_board"),
          "rebuild restore must stay frozen while a previous GMA restore is still in progress.");
      assertFalse(
          harness.leelaz.sentCommands.stream().anyMatch(command -> command.startsWith("loadsgf ")),
          "rebuild restore must only update the deferred target while restore is in progress.");

      setField(harness.readBoard, "readBoardGmaEngineRestoreInProgress", false);
      harness.readBoard.afterReadBoardGmaTerminalResponseConsumed("test-terminal");

      assertTrue(
          waitForSentCommand(harness.leelaz, "clear_board"),
          "finishing the current restore should release the deferred rebuild restore.");
      assertTrue(
          waitForSentCommandPrefix(harness.leelaz, "loadsgf "),
          "deferred rebuild restore should use the latest authoritative snapshot.");
    }
  }

  private static HistoryPath buildHistory(TrackingBoard board, Placement... moves) {
    BoardHistoryList history = board.getHistory();
    List<BoardHistoryNode> nodes = new ArrayList<>();
    for (Placement move : moves) {
      history.place(move.x, move.y, move.color, false);
      nodes.add(history.getCurrentHistoryNode());
    }
    return new HistoryPath(nodes);
  }

  private static BoardHistoryList rootHistory(Stone[] stones, boolean blackToPlay) {
    Board.boardWidth = BOARD_SIZE;
    Board.boardHeight = BOARD_SIZE;
    Zobrist.init();
    return new BoardHistoryList(
        BoardData.snapshot(
            stones.clone(),
            Optional.empty(),
            Stone.EMPTY,
            blackToPlay,
            zobrist(stones),
            0,
            new int[BOARD_AREA],
            0,
            0,
            50,
            0));
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private static Stone[] stones(Placement... placements) {
    Stone[] stones = emptyStones();
    for (Placement placement : placements) {
      stones[stoneIndex(placement.x, placement.y)] = placement.color;
    }
    return stones;
  }

  private static int[] snapshot(Stone[] stones, Optional<int[]> lastMove, Stone lastMoveColor) {
    int[] snapshot = new int[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      snapshot[index] = normalize(stones[stoneIndex(index % BOARD_SIZE, index / BOARD_SIZE)]);
    }
    if (lastMove.isPresent()) {
      int[] coords = lastMove.get();
      snapshot[coords[1] * BOARD_SIZE + coords[0]] = lastMoveColor == Stone.BLACK ? 3 : 4;
    }
    return snapshot;
  }

  private static int normalize(Stone stone) {
    if (stone == Stone.BLACK || stone == Stone.BLACK_RECURSED) {
      return 1;
    }
    if (stone == Stone.WHITE || stone == Stone.WHITE_RECURSED) {
      return 2;
    }
    return 0;
  }

  private static Zobrist zobrist(Stone[] stones) {
    Zobrist zobrist = new Zobrist();
    for (int x = 0; x < BOARD_SIZE; x++) {
      for (int y = 0; y < BOARD_SIZE; y++) {
        Stone stone = stones[stoneIndex(x, y)];
        if (!stone.isEmpty()) {
          zobrist.toggleStone(x, y, stone);
        }
      }
    }
    return zobrist;
  }

  private static int stoneIndex(int x, int y) {
    return x * BOARD_SIZE + y;
  }

  private static Placement placement(int x, int y, Stone color) {
    return new Placement(x, y, color);
  }

  private static void assertPlayLineKeepsAnalysisWideRootNoise(String playLine, boolean gma)
      throws Exception {
    Menu previousMenu = LizzieFrame.menu;
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      LizzieFrame.menu = allocate(SilentMenu.class);
      JFontCheckBox chkWRN = new JFontCheckBox();
      setField(LizzieFrame.menu, "chkWRN", chkWRN);
      LizzieFrame.menu.txtWRN = new JFontTextField("0.04");
      chkWRN.setSelected(true);
      LizzieFrame.menu.txtWRN.setEnabled(true);
      Lizzie.config.disableWRNInGame = true;
      Lizzie.config.chkKataEngineWRN = true;
      harness.leelaz.isKatago = true;
      harness.leelaz.wrn = 0.04;
      if (gma) {
        harness.leelaz.enableReadBoardGmaSupport();
      }

      harness.readBoard.parseLine(playLine);

      assertTrue(harness.frame.isAnaPlayingAgainstLeelaz);
      assertTrue(LizzieFrame.toolbar.isAutoPlay);
      assertTrue(chkWRN.isSelected(), "ReadBoard play> must not uncheck WRN");
      assertTrue(LizzieFrame.menu.txtWRN.isEnabled());
      assertTrue(Lizzie.config.chkKataEngineWRN);
      assertEquals(0.04, harness.leelaz.wrn);
      assertFalse(
          harness.leelaz.sentCommands.stream()
              .anyMatch(command -> command.startsWith("kata-set-param analysisWideRootNoise")),
          "ReadBoard play> must not reset analysisWideRootNoise");
    } finally {
      LizzieFrame.menu = previousMenu;
    }
  }

  private static void assertEndsyncKeepsUserWRNChoice(boolean uncheckAfterPlay) throws Exception {
    Menu previousMenu = LizzieFrame.menu;
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      LizzieFrame.menu = allocate(SilentMenu.class);
      JFontCheckBox chkWRN = new JFontCheckBox();
      setField(LizzieFrame.menu, "chkWRN", chkWRN);
      LizzieFrame.menu.txtWRN = new JFontTextField("0.04");
      chkWRN.setSelected(true);
      LizzieFrame.menu.txtWRN.setEnabled(true);
      Lizzie.config.disableWRNInGame = true;
      Lizzie.config.chkKataEngineWRN = true;
      harness.leelaz.isKatago = true;
      harness.leelaz.wrn = 0.04;
      setField(harness.frame, "WRNStatusBeforeGame", true);

      harness.readBoard.parseLine("play>black>5 1000 0");
      assertFalse(getBooleanField(harness.frame, "WRNStatusBeforeGame"));

      if (uncheckAfterPlay) {
        chkWRN.setSelected(false);
        LizzieFrame.menu.txtWRN.setEnabled(false);
        Lizzie.config.chkKataEngineWRN = false;
      }
      harness.leelaz.sentCommands.clear();

      harness.readBoard.parseLine("endsync");

      assertEquals(!uncheckAfterPlay, chkWRN.isSelected());
      assertEquals(!uncheckAfterPlay, Lizzie.config.chkKataEngineWRN);
      assertFalse(
          harness.leelaz.sentCommands.stream()
              .anyMatch(command -> command.startsWith("kata-set-param analysisWideRootNoise")),
          "endsync must not restore WRN that ReadBoard play> never cleared");
    } finally {
      LizzieFrame.menu = previousMenu;
    }
  }


  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static BottomToolbar minimalToolbar() throws Exception {
    BottomToolbar toolbar = allocate(BottomToolbar.class);
    toolbar.chkAutoPlay = new JCheckBox();
    toolbar.chkAutoPlayBlack = new JCheckBox();
    toolbar.chkAutoPlayWhite = new JCheckBox();
    toolbar.chkAutoPlayTime = new JCheckBox();
    toolbar.chkAutoPlayPlayouts = new JCheckBox();
    toolbar.chkAutoPlayFirstPlayouts = new JCheckBox();
    toolbar.txtAutoPlayTime = new JTextField("0");
    toolbar.txtAutoPlayPlayouts = new JTextField("0");
    toolbar.txtAutoPlayFirstPlayouts = new JTextField("0");
    return toolbar;
  }

  private static boolean getBooleanField(Object target, String name) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    return field.getBoolean(target);
  }

  private static int getIntField(Object target, String name) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    return field.getInt(target);
  }

  private static Object getField(Object target, String name) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
    Class<?> current = type;
    while (current != null) {
      try {
        return current.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        current = current.getSuperclass();
      }
    }
    throw new NoSuchFieldException(name);
  }

  private static void invokeSyncBoardStones(ReadBoard readBoard) throws Exception {
    Method method = ReadBoard.class.getDeclaredMethod("syncBoardStones", boolean.class);
    method.setAccessible(true);
    method.invoke(readBoard, false);
  }

  private static void markPendingLocalMoveAwaitingReadBoard(ReadBoard readBoard) throws Exception {
    readBoard.process = new AliveProcess();
    Lizzie.frame.readBoard = readBoard;
    Lizzie.frame.bothSync = true;
    Lizzie.frame.syncBoard = true;
    setField(readBoard, "placeCommandDispatcher", (java.util.concurrent.Executor) Runnable::run);
    setField(readBoard, "pendingLocalMoveTimeoutExecutor", new CapturedTimeoutScheduler());
    int[] coords = Lizzie.board.getHistory().getMainEnd().getData().lastMove.orElseThrow();
    readBoard.sendCommand("place " + coords[0] + " " + coords[1]);
  }

  private static void invokePlacementFailed(ReadBoard readBoard) throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod("handlePendingLocalMovePlacementFailure", String.class);
    method.setAccessible(true);
    method.invoke(readBoard, "test placement failed");
  }

  private static void invokePlacementFailedLine(ReadBoard readBoard) throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod("handleLocalMovePlacementFailed", String.class);
    method.setAccessible(true);
    method.invoke(readBoard, "error place failed");
  }

  private static boolean beginReadBoardGmaSessionForTest(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("beginReadBoardGmaSession");
    method.setAccessible(true);
    return (boolean) method.invoke(engine);
  }


  private static void invokeClearFailedLocalMoveStateIfAutoPlaySideChanged(
      ReadBoard readBoard, Stone autoPlayColor) throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod(
            "clearFailedLocalMoveStateIfAutoPlaySideChanged", Stone.class);
    method.setAccessible(true);
    method.invoke(readBoard, autoPlayColor);
  }

  private static boolean waitForSentCommand(SnapshotTrackingLeelaz leelaz, String command)
      throws InterruptedException {
    return waitForSentCommandPrefix(leelaz, command, true);
  }

  private static boolean waitForFileDeletion(Path path) throws InterruptedException {
    if (path == null) {
      return false;
    }
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    while (Files.exists(path) && System.nanoTime() < deadline) {
      Thread.sleep(10L);
    }
    return Files.notExists(path);
  }

  private static boolean waitForSentCommandPrefix(SnapshotTrackingLeelaz leelaz, String prefix)
      throws InterruptedException {
    return waitForSentCommandPrefix(leelaz, prefix, false);
  }

  private static boolean waitForSentCommandPrefix(
      SnapshotTrackingLeelaz leelaz, String value, boolean exact) throws InterruptedException {
    for (int attempt = 0; attempt < 100; attempt++) {
      if (new ArrayList<>(leelaz.sentCommands).stream()
          .anyMatch(command -> exact ? command.equals(value) : command.startsWith(value))) {
        return true;
      }
      Thread.sleep(10);
    }
    return false;
  }

  private static boolean waitForReadBoardGmaCount(
      SnapshotTrackingLeelaz leelaz, int expectedCount) throws InterruptedException {
    for (int attempt = 0; attempt < 100; attempt++) {
      if (leelaz.readBoardGmaCount >= expectedCount) {
        return true;
      }
      Thread.sleep(10);
    }
    return false;
  }

  private static final class EngineResumeHarness implements AutoCloseable {
    private final Config previousConfig;
    private final Board previousBoard;
    private final Leelaz previousLeelaz;
    private final Leelaz previousLeelaz2;
    private final EngineManager previousEngineManager;
    private final int previousCurrentEngineNo;
    private final int previousCurrentEngineNo2;
    private final boolean previousEngineEmpty;
    private final LizzieFrame previousFrame;
    private final BoardRenderer previousBoardRenderer;
    private final BottomToolbar previousToolbar;
    private final TrackingBoard board;
    private final TrackingFrame frame;
    private final SnapshotTrackingLeelaz leelaz;
    private final ReadBoard readBoard;
    private final ByteArrayOutputStream protocolCapture;

    private EngineResumeHarness(
        Config previousConfig,
        Board previousBoard,
        Leelaz previousLeelaz,
        Leelaz previousLeelaz2,
        EngineManager previousEngineManager,
        int previousCurrentEngineNo,
        int previousCurrentEngineNo2,
        boolean previousEngineEmpty,
        LizzieFrame previousFrame,
        BoardRenderer previousBoardRenderer,
        BottomToolbar previousToolbar,
        TrackingBoard board,
        TrackingFrame frame,
        SnapshotTrackingLeelaz leelaz,
        ReadBoard readBoard,
        ByteArrayOutputStream protocolCapture) {
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousLeelaz = previousLeelaz;
      this.previousLeelaz2 = previousLeelaz2;
      this.previousEngineManager = previousEngineManager;
      this.previousCurrentEngineNo = previousCurrentEngineNo;
      this.previousCurrentEngineNo2 = previousCurrentEngineNo2;
      this.previousEngineEmpty = previousEngineEmpty;
      this.previousFrame = previousFrame;
      this.previousBoardRenderer = previousBoardRenderer;
      this.previousToolbar = previousToolbar;
      this.board = board;
      this.frame = frame;
      this.leelaz = leelaz;
      this.readBoard = readBoard;
      this.protocolCapture = protocolCapture;
    }

    private static EngineResumeHarness create(BoardHistoryList history) throws Exception {
      Config previousConfig = Lizzie.config;
      Board previousBoard = Lizzie.board;
      Leelaz previousLeelaz = Lizzie.leelaz;
      Leelaz previousLeelaz2 = Lizzie.leelaz2;
      EngineManager previousEngineManager = Lizzie.engineManager;
      int previousCurrentEngineNo = EngineManager.currentEngineNo;
      int previousCurrentEngineNo2 = EngineManager.currentEngineNo2;
      boolean previousEngineEmpty = EngineManager.isEmpty;
      LizzieFrame previousFrame = Lizzie.frame;
      BoardRenderer previousBoardRenderer = LizzieFrame.boardRenderer;
      BottomToolbar previousToolbar = LizzieFrame.toolbar;

      TrackingConfig config = allocate(TrackingConfig.class);
      config.alwaysSyncBoardStat = false;
      config.alwaysGotoLastOnLive = false;
      config.newMoveNumberInBranch = true;
      config.noCapture = false;
      config.readBoardPonder = true;
      config.winrateAlwaysBlack = false;
      config.leelazConfig = new JSONObject().put("max-game-thinking-time-seconds", 2);
      config.suppressionCount = 0;
      Lizzie.config = config;

      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
      leelaz.canSuicidal = false;
      EngineManager fixtureEngineManager =
          new EngineManager(new ArrayList<>(List.of(leelaz)));
      // Analysis resumption is gated by this complete foreground-engine authority tuple.
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = -1;
      EngineManager.isEmpty = false;
      Lizzie.setPrimaryEngine(leelaz);
      Lizzie.setEngineManager(fixtureEngineManager);
      Lizzie.leelaz2 = null;

      TrackingBoard board = allocate(TrackingBoard.class);
      board.initialize(history);
      Lizzie.board = board;

      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.initialize(board);
      Lizzie.frame = frame;
      LizzieFrame.boardRenderer = new BoardRenderer(false);
      LizzieFrame.toolbar = minimalToolbar();

      ReadBoard readBoard = allocate(SilentConflictReadBoard.class);
      setField(readBoard, "conflictTracker", new SyncConflictTracker());
      setField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
      setField(readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
      setField(readBoard, "tempcount", new ArrayList<Integer>());
      readBoard.firstSync = false;
      setField(readBoard, "usePipe", true);
      ByteArrayOutputStream protocolCapture = new ByteArrayOutputStream();
      setField(readBoard, "outputStream", new BufferedOutputStream(protocolCapture));
      frame.readBoard = readBoard;

      return new EngineResumeHarness(
          previousConfig,
          previousBoard,
          previousLeelaz,
          previousLeelaz2,
          previousEngineManager,
          previousCurrentEngineNo,
          previousCurrentEngineNo2,
          previousEngineEmpty,
          previousFrame,
          previousBoardRenderer,
          previousToolbar,
          board,
          frame,
          leelaz,
          readBoard,
          protocolCapture);
    }

    private CapturedTimeoutScheduler activateLocalPlacement() throws Exception {
      buildHistory(board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      readBoard.process = new AliveProcess();
      setField(readBoard, "placeCommandDispatcher", (java.util.concurrent.Executor) Runnable::run);
      readBoard.parseLine("bothSync");
      readBoard.parseLine("sync");
      CapturedTimeoutScheduler scheduler = new CapturedTimeoutScheduler();
      setField(readBoard, "pendingLocalMoveTimeoutExecutor", scheduler);
      return scheduler;
    }

    private void useSocketPlacement() throws Exception {
      ReadBoardStream stream = allocate(ReadBoardStream.class);
      setField(stream, "out", new BufferedOutputStream(protocolCapture));
      setField(readBoard, "readBoardStream", stream);
      setField(readBoard, "usePipe", false);
      readBoard.process = null;
    }

    private void expire(Runnable timeout) throws Exception {
      setField(readBoard, "lastPendingLocalMoveRetryTimeMs", System.currentTimeMillis() - 4000);
      timeout.run();
    }

    private void acceptSnapshot(BoardHistoryNode node) {
      int[] codes =
          snapshot(node.getData().stones, node.getData().lastMove, node.getData().lastMoveColor);
      for (int y = 0; y < BOARD_SIZE; y++) {
        readBoard.parseLine(
            "re="
                + codes[y * BOARD_SIZE]
                + ","
                + codes[y * BOARD_SIZE + 1]
                + ","
                + codes[y * BOARD_SIZE + 2]);
      }
      readBoard.parseLine("end");
    }

    private void sync(int[] snapshotCodes) throws Exception {
      ArrayList<Integer> counts = new ArrayList<>(snapshotCodes.length);
      for (int code : snapshotCodes) {
        counts.add(code);
      }
      setField(readBoard, "tempcount", counts);
      invokeSyncBoardStones(readBoard);
    }

    private String protocolOutput() {
      return protocolCapture.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws Exception {
      ScheduledThreadPoolExecutor scheduler =
          (ScheduledThreadPoolExecutor) getField(readBoard, "pendingLocalMoveTimeoutExecutor");
      if (scheduler != null) scheduler.shutdownNow();
      leelaz.releaseBlockedLoadSgf();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.setPrimaryEngine(previousLeelaz);
      Lizzie.setEngineManager(previousEngineManager);
      Lizzie.leelaz2 = previousLeelaz2;
      EngineManager.currentEngineNo = previousCurrentEngineNo;
      EngineManager.currentEngineNo2 = previousCurrentEngineNo2;
      EngineManager.isEmpty = previousEngineEmpty;
      Lizzie.frame = previousFrame;
      LizzieFrame.boardRenderer = previousBoardRenderer;
      LizzieFrame.toolbar = previousToolbar;
    }
  }

  private static final class TrackingConfig extends Config {
    private int suppressionCount;

    private TrackingConfig() throws IOException {}

    @Override
    public void suppressReadBoardWebSocketPonderingNotice() {
      suppressionCount++;
      suppressReadBoardWebSocketPonderingNotice = true;
    }
  }

  private static final class RecordingLifecycleLeelaz extends Leelaz {
    private int armCheckCount;
    private int beginLifecycleCount;
    private int endLifecycleCount;
    private boolean allowArm = true;

    private RecordingLifecycleLeelaz() throws IOException {
      super("");
    }

    @Override
    boolean canArmReadBoardGma() {
      armCheckCount++;
      return allowArm;
    }

    @Override
    public synchronized boolean beginExclusiveGtpLifecycleTransition() {
      beginLifecycleCount++;
      return true;
    }

    @Override
    public synchronized void endExclusiveGtpLifecycleTransition() {
      endLifecycleCount++;
    }
  }

  private static final class SilentConflictReadBoard extends ReadBoard {
    private SilentConflictReadBoard() throws Exception {
      super(false, false);
    }

    @Override
    void showForegroundEngineLeaseConflict() {}
  }

  private static final class SilentMenu extends Menu {
    @Override
    public void showPda(boolean show) {}

    @Override
    public void updateMenuStatusForEngine() {}
  }

  private static final class SilentBottomToolbar extends BottomToolbar {
    @Override
    public void reSetButtonLocation() {}
  }

  private static final class TrackingBoard extends Board {
    private int clearCount;
    private boolean clearCalledOnEdt;

    @Override
    public java.util.concurrent.CompletableFuture<Void> applyReadBoardSync(
        Runnable localChanges, java.util.function.BooleanSupplier requiresConfirmation) {
      // Protocol confirmation is exercised by PositionConfirmedRollbackTest, not this decision
      // fixture.
      localChanges.run();
      return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    private void initialize(BoardHistoryList history) {
      setHistory(history);
      hasStartStone = false;
    }

    @Override
    public void clearAfterMove() {}

    @Override
    public void clear(boolean isEngineGame) {
      clearCount++;
      clearCalledOnEdt = SwingUtilities.isEventDispatchThread();
      setHistory(rootHistory(emptyStones(), true));
      hasStartStone = false;
      if (Lizzie.frame != null && Lizzie.frame.readBoard != null) {
        Lizzie.frame.readBoard.firstSync = true;
      }
    }

    @Override
    public void placeForSync(int x, int y, Stone color, boolean newBranch) {
      getHistory().place(x, y, color, newBranch);
      if (Lizzie.frame != null && Lizzie.frame.readBoard != null) {
        Lizzie.frame.readBoard.lastMovePlayByLizzie = false;
      }
    }

    @Override
    public void moveToAnyPosition(BoardHistoryNode targetNode) {
      getHistory().setHead(targetNode);
    }

    @Override
    public boolean previousMove(boolean needRefresh) {
      Optional<BoardData> previous = getHistory().previous();
      return previous.isPresent();
    }

    @Override
    public void addStartListAll() {}

    @Override
    public void flatten() {}
  }

  private static final class TrackingFrame extends LizzieFrame {
    private int scheduleResumeAnalysisCount;
    private int stopAiPlayingAndPolicyCount;
    private int flashAnalyzeGameCount;
    private Runnable lastScheduledResumeAction;
    private int togglePonderMannulCount;
    private TrackingBoard board;
    private int readBoardPonderingNoticeCount;
    private Consumer<LizzieFrame.ReadBoardWebSocketPonderingDecision>
        readBoardPonderingNoticeDecision;

    private void initialize(TrackingBoard board) {
      this.board = board;
      bothSync = false;
      syncBoard = false;
      isPlayingAgainstLeelaz = false;
      playerIsBlack = true;
      readBoardPonderingNoticeCount = 0;
      readBoardPonderingNoticeDecision = null;
    }

    @Override
    public void showReadBoardWebSocketPonderingNotice(
        Consumer<LizzieFrame.ReadBoardWebSocketPonderingDecision> decision) {
      readBoardPonderingNoticeCount++;
      readBoardPonderingNoticeDecision = decision;
    }

    private void answerReadBoardPonderingNotice(boolean suppressPermanently) {
      Consumer<LizzieFrame.ReadBoardWebSocketPonderingDecision> decision =
          readBoardPonderingNoticeDecision;
      readBoardPonderingNoticeDecision = null;
      assertNotNull(decision);
      decision.accept(
          suppressPermanently
              ? LizzieFrame.ReadBoardWebSocketPonderingDecision.SUPPRESS
              : LizzieFrame.ReadBoardWebSocketPonderingDecision.CONFIRM);
    }

    private void closeReadBoardPonderingNotice() {
      Consumer<LizzieFrame.ReadBoardWebSocketPonderingDecision> decision =
          readBoardPonderingNoticeDecision;
      readBoardPonderingNoticeDecision = null;
      assertNotNull(decision);
      decision.accept(LizzieFrame.ReadBoardWebSocketPonderingDecision.DISMISS);
    }

    @Override
    public void refresh() {}

    @Override
    public void reSetLoc() {}

    @Override
    public boolean resetMovelistFrameandAnalysisFrame() {
      return false;
    }

    @Override
    public void onMainEnginePonder() {}

    @Override
    public void togglePonderMannul() {
      togglePonderMannulCount++;
      super.togglePonderMannul();
    }

    @Override
    public void flashAnalyzeGame(boolean isAllGame, boolean isAllBranches, boolean silentAnalyze) {
      flashAnalyzeGameCount++;
    }

    @Override
    public void renderVarTree(int vw, int vh, boolean changeSize, boolean needGetEnd) {}

    @Override
    public void lastMove() {
      board.getHistory().setHead(board.getHistory().getMainEnd());
    }

    @Override
    public void clearKataEstimate() {}

    @Override
    public void resetTitle() {}

    @Override
    public void clearTryPlay() {}

    @Override
    public boolean stopAiPlayingAndPolicy() {
      stopAiPlayingAndPolicyCount++;
      boolean wasGaming = isPlayingAgainstLeelaz || isAnaPlayingAgainstLeelaz;
      if (isAnaPlayingAgainstLeelaz
          && LizzieFrame.menu != null
          && LizzieFrame.menu.txtWRN != null) {
        restoreWRN(false);
      }
      isPlayingAgainstLeelaz = false;
      isAnaPlayingAgainstLeelaz = false;
      if (Lizzie.leelaz != null) {
        Lizzie.leelaz.notPondering();
        Lizzie.leelaz.isThinking = false;
      }
      return wasGaming;
    }

    @Override
    public void scheduleResumeAnalysisAfterLoad(int delayMillis) {
      scheduleResumeAnalysisCount++;
      lastScheduledResumeAction = null;
    }

    public void scheduleResumeAnalysisAfterLoad(int delayMillis, Runnable action) {
      scheduleResumeAnalysisCount++;
      lastScheduledResumeAction = action;
    }
  }

  private static final class HistoryPath {
    private final List<BoardHistoryNode> nodes;

    private HistoryPath(List<BoardHistoryNode> nodes) {
      this.nodes = nodes;
    }
  }

  private static final class Placement {
    private final int x;
    private final int y;
    private final Stone color;

    private Placement(int x, int y, Stone color) {
      this.x = x;
      this.y = y;
      this.color = color;
    }
  }

  static final class AliveProcess extends Process {
    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {}

    @Override
    public boolean isAlive() {
      return true;
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static sun.misc.Unsafe loadUnsafe() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Failed to access Unsafe", ex);
      }
    }
  }
}
