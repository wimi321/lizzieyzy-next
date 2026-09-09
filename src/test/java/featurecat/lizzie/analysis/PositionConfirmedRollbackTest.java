package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.BoardRenderer;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class PositionConfirmedRollbackTest {
  private static final int BOARD_SIZE = 19;
  private static final long OBSERVATION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);

  @Test
  void acceptedRollbackDelayedResumeStartsOnceWithoutReplayingTheBoard() throws Exception {
    try (Harness harness = Harness.open()) {
      Lizzie.config.autoQuickAnalyzeOnLoad = true;
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              harness.engine, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      harness.engine.playMoveNoPonder(Stone.BLACK, "Q16");
      harness.engine.ponder();
      awaitRawCommand(transport, "kata-analyze", 0);
      harness.acceptEmptySnapshot();
      assertNotNull(harness.frame.scheduledResume);
      harness.frame.scheduledResume.run();
      awaitRawCommand(transport, "kata-analyze", 1);
      java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
      harness.engine.sendCommandWithResponseForTest("name", drained::countDown);
      assertTrue(drained.await(2, TimeUnit.SECONDS));
      assertEquals(2, payloadCount(transport, "kata-analyze"), transport.commands().toString());
      assertEquals(0, payloadCount(transport, "clear_board"), transport.commands().toString());
      assertEquals(0, payloadCount(transport, "loadsgf"));
      assertSame(harness.p0, harness.board.getHistory().getCurrentHistoryNode());
      assertSame(harness.p1, harness.p0.next().orElseThrow());
    }
  }

  @org.junit.jupiter.api.RepeatedTest(10)
  void rejectedOrdinaryPredecessorCannotBeForgottenBySyncRollback() throws Exception {
    try (Harness harness = Harness.open();
        ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.installAsync(
              harness.engine,
              command ->
                  command.equals("play W D4")
                      ? null
                      : ExactSnapshotRestoreProtocolFixture.Response.success())) {
      harness.engine.playMoveNoPonder(Stone.BLACK, "Q16");
      harness.engine.ponder();
      awaitRawCommand(transport, "kata-analyze", 0);
      harness.engine.parseAnalysisLineForTest(info(12_000, 0.615));
      harness.board.getHistory().place(3, 15, Stone.WHITE, false);
      harness.engine.playMoveNoPonder(Stone.WHITE, "D4");
      String predecessor = awaitRawCommand(transport, "play W D4", 0);
      harness.acceptSnapshot(harness.p1);
      harness.frame.scheduledResume.run();
      transport.respond(
          "?" + predecessor.substring(0, predecessor.indexOf(' ')) + " rejected play");
      long deadline = System.nanoTime() + OBSERVATION_TIMEOUT_NANOS;
      while (harness.engine.isLoaded
          && payloadCount(transport, "kata-analyze") == 1
          && System.nanoTime() < deadline) Thread.sleep(5L);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertEquals(1, payloadCount(transport, "kata-analyze"), transport.commands().toString());
      assertFalse(harness.engine.isLoaded);
      assertSame(harness.p1, harness.board.getHistory().getCurrentHistoryNode());
      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
      assertAnalysis(harness.p1.getData(), 12_000, 61.5);
    }
  }

  @Test
  void multiStepRollbackAndForwardResumeOnlyFinalExistingNode() throws Exception {
    try (Harness harness = Harness.open()) {
      BoardHistoryList history = harness.board.getHistory();
      history.place(3, 15, Stone.WHITE, false);
      BoardHistoryNode p2 = history.getCurrentHistoryNode();
      history.place(2, 6, Stone.BLACK, false);
      BoardHistoryNode p3 = history.getCurrentHistoryNode();
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              harness.engine, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      harness.engine.playMoveNoPonder(Stone.BLACK, "Q16");
      harness.engine.playMoveNoPonder(Stone.WHITE, "D4");
      harness.engine.playMoveNoPonder(Stone.BLACK, "C13");
      harness.engine.ponder();
      awaitRawCommand(transport, "kata-analyze", 0);
      harness.acceptEmptySnapshot();
      awaitRawCommand(transport, "name", 0);
      assertEquals(1, payloadCount(transport, "kata-analyze"));
      harness.frame.scheduledResume.run();
      awaitRawCommand(transport, "kata-analyze", 1);
      assertEquals(3, payloadCount(transport, "undo"));
      assertEquals(0, payloadCount(transport, "clear_board"));
      assertSame(harness.p0, history.getCurrentHistoryNode());
      harness.acceptSnapshot(p2);
      harness.frame.scheduledResume.run();
      awaitRawCommand(transport, "kata-analyze", 2);
      assertEquals(3, payloadCount(transport, "kata-analyze"), transport.commands().toString());
      assertEquals(0, payloadCount(transport, "clear_board"));
      assertSame(p2, history.getCurrentHistoryNode());
      assertSame(p3, history.getMainEnd());
      assertSame(harness.p1, harness.p0.next().orElseThrow());
      assertSame(p2, harness.p1.next().orElseThrow());
      assertSame(p3, p2.next().orElseThrow());
      List<String> beforeRepeatedFrame = transport.commands();
      harness.acceptSnapshot(p2);
      harness.frame.scheduledResume.run();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertEquals(beforeRepeatedFrame, transport.commands());
    }
  }

  @Test
  void acceptedRootRecoveryWaitsForFinalPassAndDoesNotRestoreTwice() throws Exception {
    assertAcceptedFullRestore(false);
  }

  @Test
  void acceptedStaticRecoveryWaitsForFinalPassAndDoesNotRestoreTwice() throws Exception {
    assertAcceptedFullRestore(true);
  }

  private void assertAcceptedFullRestore(boolean exact) throws Exception {
    try (Harness harness = Harness.open()) {
      RestoreHistory restored = exact ? exactRestoreHistory() : rootRestoreHistory(BOARD_SIZE);
      restored.history.place(10, 10, Stone.BLACK, false);
      BoardHistoryNode successor = restored.history.getCurrentHistoryNode();
      restored.history.setHead(restored.target);
      harness.board.setHistory(restored.history);
      setField(harness.readBoard, "awaitingFirstSyncFrame", true);
      harness.engine.commandLists.add("loadsgf");
      SnapshotTrackingLeelaz enginePosition = SnapshotTrackingLeelaz.create();
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          installPositionTransport(harness.engine, enginePosition, "play W pass");
      harness.acceptSnapshot(restored.target);
      String lastPass = awaitRawCommand(transport, "play W pass", 0);
      assertNotNull(harness.frame.scheduledResume);
      harness.frame.scheduledResume.run();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertEquals(0, payloadCount(transport, "kata-analyze"));
      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
      assertNoAnalysis(restored.target.getData());
      acknowledge(harness.engine, lastPass);
      awaitRawCommand(transport, "kata-analyze", 0);
      harness.engine.parseAnalysisLineForTest(info(4_875, 0.96937));
      assertAnalysis(restored.target.getData(), 4_875, 96.937);
      assertEquals(1, payloadCount(transport, "kata-analyze"));
      assertEquals(1, payloadCount(transport, "clear_board"));
      assertEquals(exact ? 1 : 0, payloadCount(transport, "loadsgf"));
      assertPosition(enginePosition, restored.target.getData(), BOARD_SIZE, BOARD_SIZE);
      assertSame(restored.target, restored.history.getCurrentHistoryNode());
      assertSame(successor, restored.history.getMainEnd());
      assertTrue(restored.target.getData().blackToPlay);
    }
  }

  @Test
  void pausedSyncKeepsAcceptedHistoryWithoutStartingAnalysis() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.userAnalysisPaused = true;
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              harness.engine, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      harness.acceptEmptySnapshot();
      awaitRawCommand(transport, "name", 0);
      harness.frame.scheduledResume.run();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertSame(harness.p0, harness.board.getHistory().getCurrentHistoryNode());
      assertEquals(0, payloadCount(transport, "kata-analyze"));
      assertTrue(harness.frame.isUserAnalysisPaused());
    }
  }

  @Test
  void syncWithoutEngineStillNavigatesToTheExistingNode() throws Exception {
    try (Harness harness = Harness.open()) {
      Lizzie.setPrimaryEngine(null);
      EngineManager.isEmpty = true;
      harness.acceptEmptySnapshot();
      assertSame(harness.p0, harness.board.getHistory().getCurrentHistoryNode());
      assertSame(harness.p1, harness.board.getHistory().getMainEnd());
      assertTrue(harness.transport.commands().isEmpty());
    }
  }

  @Test
  void supersedingPendingRollbackRestoresNewTargetAndRejectsOldCallbacks() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.board.getHistory().place(3, 15, Stone.WHITE, false);
      BoardHistoryNode p2 = harness.board.getHistory().getCurrentHistoryNode();
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              harness.engine,
              command ->
                  command.equals("undo")
                      ? null
                      : ExactSnapshotRestoreProtocolFixture.Response.success());
      harness.acceptEmptySnapshot();
      String oldUndo = awaitRawCommand(transport, "undo", 0);
      Runnable oldResume = harness.frame.scheduledResume;
      harness.acceptSnapshot(harness.p1);
      Runnable currentResume = harness.frame.scheduledResume;
      oldResume.run();
      currentResume.run();
      acknowledge(harness.engine, oldUndo);
      String secondUndo = awaitRawCommand(transport, "undo", 1);
      acknowledge(harness.engine, secondUndo);
      awaitRawCommand(transport, "kata-analyze", 0);
      acknowledge(harness.engine, oldUndo);
      oldResume.run();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertEquals(1, payloadCount(transport, "kata-analyze"), transport.commands().toString());
      assertEquals(1, payloadCount(transport, "clear_board"));
      assertSame(harness.p1, harness.board.getHistory().getCurrentHistoryNode());
      assertSame(p2, harness.board.getHistory().getMainEnd());
      harness.engine.parseAnalysisLineForTest(info(4_875, 0.96937));
      assertAnalysis(harness.p1.getData(), 4_875, 96.937);
      assertNoAnalysis(harness.p0.getData());
    }
  }

  @Test
  void forcedSnapshotRebuildLoadsAndAnalyzesOnlyOnce() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.engine.commandLists.add("loadsgf");
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              harness.engine, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      harness.engine.ponder();
      awaitRawCommand(transport, "kata-analyze", 0);
      harness.readBoard.parseLine("forceRebuild");
      harness.acceptSnapshot(harness.p1);
      harness.frame.scheduledResume.run();
      awaitRawCommand(transport, "kata-analyze", 1);
      assertEquals(2, payloadCount(transport, "kata-analyze"));
      assertEquals(1, payloadCount(transport, "clear_board"), transport.commands().toString());
      assertEquals(1, payloadCount(transport, "loadsgf"));
      BoardData target = harness.board.getHistory().getData();
      assertTrue(target.isSnapshotNode());
      assertArrayEquals(harness.p1.getData().stones, target.stones);
      harness.engine.parseAnalysisLineForTest(info(4_875, 0.96937));
      assertAnalysis(target, 4_875, 96.937);
    }
  }

  @Test
  void acceptedCaptureResumesOnlyAfterItsFinalMove() throws Exception {
    BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
    try (Harness harness = Harness.open()) {
      LizzieFrame.boardRenderer = allocate(SilentBoardRenderer.class);
      Stone[] before = emptyStones(BOARD_SIZE, BOARD_SIZE);
      before[Board.getIndex(0, 1)] = Stone.BLACK;
      before[Board.getIndex(1, 0)] = Stone.BLACK;
      before[Board.getIndex(2, 1)] = Stone.BLACK;
      before[Board.getIndex(1, 1)] = Stone.WHITE;
      BoardData setup =
          BoardData.snapshot(
              before,
              java.util.Optional.empty(),
              Stone.EMPTY,
              true,
              zobrist(before, BOARD_SIZE, BOARD_SIZE),
              0,
              new int[before.length],
              0,
              0,
              50,
              0);
      BoardHistoryList history = new BoardHistoryList(setup);
      harness.board.setHistory(history);
      Stone[] after = before.clone();
      after[Board.getIndex(1, 1)] = Stone.EMPTY;
      after[Board.getIndex(1, 2)] = Stone.BLACK;
      BoardData expected =
          BoardData.move(
              after,
              new int[] {1, 2},
              Stone.BLACK,
              false,
              zobrist(after, BOARD_SIZE, BOARD_SIZE),
              1,
              new int[after.length],
              1,
              0,
              50,
              0);
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              harness.engine, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      harness.engine.ponder();
      awaitRawCommand(transport, "kata-analyze", 0);
      harness.acceptSnapshot(new BoardHistoryNode(expected));
      harness.frame.scheduledResume.run();
      awaitRawCommand(transport, "kata-analyze", 1);
      assertEquals(List.of("play B B17"), commandsWithPrefix(transport, "play "));
      assertEquals(2, payloadCount(transport, "kata-analyze"));
      assertEquals(0, payloadCount(transport, "clear_board"));
      assertEquals(0, payloadCount(transport, "loadsgf"));
      BoardData actual = history.getData();
      assertTrue(actual.isMoveNode());
      assertArrayEquals(after, actual.stones);
      assertEquals(1, actual.blackCaptures);
      assertFalse(actual.blackToPlay);
    } finally {
      LizzieFrame.boardRenderer = previousRenderer;
    }
  }

  @Test
  void rejectedUndoCannotResumeEvenAfterTheFinalFenceSucceeds() throws Exception {
    assertFailedSyncDoesNotResume("error");
  }

  @Test
  void failedUndoWriteCannotResumeAnalysis() throws Exception {
    assertFailedSyncDoesNotResume("write");
  }

  @org.junit.jupiter.api.RepeatedTest(10)
  void timedOutUndoCannotBeRevivedByLateAckOrDelayedResume() throws Exception {
    assertFailedSyncDoesNotResume("timeout");
  }

  private void assertFailedSyncDoesNotResume(String failure) throws Exception {
    try (Harness harness = Harness.open()) {
      harness.engine.requireResponseBeforeSend = false;
      ((PublicationControlledLeelaz) harness.engine).responseTimeoutMillis = 50;
      try (ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.installAsync(
              harness.engine,
              command -> {
                if (command.equals("undo")) {
                  if (failure.equals("write"))
                    throw new java.io.IOException("controlled undo write failure");
                  if (failure.equals("error"))
                    return ExactSnapshotRestoreProtocolFixture.Response.error("illegal undo");
                  return null;
                }
                return ExactSnapshotRestoreProtocolFixture.Response.success();
              })) {
        harness.acceptEmptySnapshot();
        String undo = awaitRawCommand(transport, "undo", 0);
        harness.frame.scheduledResume.run();
        long deadline = System.nanoTime() + OBSERVATION_TIMEOUT_NANOS;
        while (harness.engine.isLoaded && System.nanoTime() < deadline) Thread.sleep(5L);
        assertFalse(harness.engine.isLoaded, "failed sync must retire its unavailable engine target");
        transport.respond("=" + undo.substring(0, undo.indexOf(' ')));
        transport.awaitResponses();
        harness.frame.scheduledResume.run();
        transport.awaitResponses();
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        assertEquals(0, payloadCount(transport, "kata-analyze"));
        harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
        assertNoAnalysis(harness.p0.getData());
        assertSame(harness.p0, harness.board.getHistory().getCurrentHistoryNode());
        assertSame(harness.p1, harness.board.getHistory().getMainEnd());
      }
    }
  }

  @Test
  void replacingEngineWhileSyncIsPendingCannotResumeTheReplacement() throws Exception {
    assertReplacedSyncDoesNotResume(true);
  }

  @Test
  void overwritingHistoryWhileSyncIsPendingCannotResumeTheReplacementHistory() throws Exception {
    assertReplacedSyncDoesNotResume(false);
  }

  private void assertReplacedSyncDoesNotResume(boolean replaceEngine) throws Exception {
    try (Harness harness = Harness.open()) {
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              harness.engine,
              command ->
                  command.equals("undo")
                      ? null
                      : ExactSnapshotRestoreProtocolFixture.Response.success());
      harness.acceptEmptySnapshot();
      String undo = awaitRawCommand(transport, "undo", 0);
      Runnable oldResume = harness.frame.scheduledResume;
      Leelaz replacement = new Leelaz("");
      replacement.started = true;
      replacement.isLoaded = true;
      replacement.isKatago = true;
      ExactSnapshotRestoreProtocolFixture.Transport replacementTransport =
          ExactSnapshotRestoreProtocolFixture.install(
              replacement, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      try {
        if (replaceEngine) Lizzie.setPrimaryEngine(replacement);
        else
          harness.board.setHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
        oldResume.run();
        acknowledge(harness.engine, undo);
        awaitRawCommand(transport, "name", 0);
        oldResume.run();
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        assertEquals(0, payloadCount(transport, "kata-analyze"));
        assertTrue(replacementTransport.commands().isEmpty());
        harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
        assertNoAnalysis(harness.board.getHistory().getData());
      } finally {
        replacement.started = false;
        replacement.isLoaded = false;
      }
    }
  }

  @Test
  void disablingReadBoardAnalysisBeforeConfirmationKeepsAcceptedPositionWithoutResume()
      throws Exception {
    try (Harness harness = Harness.open()) {
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              harness.engine,
              command ->
                  command.equals("undo")
                      ? null
                      : ExactSnapshotRestoreProtocolFixture.Response.success());
      harness.acceptEmptySnapshot();
      String undo = awaitRawCommand(transport, "undo", 0);
      Lizzie.config.readBoardPonder = false;
      harness.frame.scheduledResume.run();
      acknowledge(harness.engine, undo);
      awaitRawCommand(transport, "name", 0);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertSame(harness.p0, harness.board.getHistory().getCurrentHistoryNode());
      assertEquals(0, payloadCount(transport, "kata-analyze"));
    }
  }

  @Test
  void userPauseRetiresOldSyncResumeEvenAfterExplicitContinue() throws Exception {
    try (Harness harness = Harness.open()) {
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              harness.engine,
              command ->
                  command.equals("undo")
                      ? null
                      : ExactSnapshotRestoreProtocolFixture.Response.success());
      harness.acceptEmptySnapshot();
      String undo = awaitRawCommand(transport, "undo", 0);
      Runnable oldResume = harness.frame.scheduledResume;
      Method pause =
          LizzieFrame.class.getDeclaredMethod("recordUserAnalysisPause", BoardHistoryNode.class);
      pause.setAccessible(true);
      harness.engine.pauseForAnalysisControl(
          () -> {
            try {
              pause.invoke(harness.frame, harness.p0);
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            }
          });
      acknowledge(harness.engine, undo);
      awaitRawCommand(transport, "name", 0);
      harness.engine.ponder();
      awaitRawCommand(transport, "kata-analyze", 0);
      oldResume.run();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
      harness.engine.sendCommandWithResponseForTest("name", drained::countDown);
      assertTrue(drained.await(2, TimeUnit.SECONDS));
      assertEquals(1, payloadCount(transport, "kata-analyze"));
      harness.engine.parseAnalysisLineForTest(info(4_875, 0.96937));
      assertAnalysis(harness.p0.getData(), 4_875, 96.937);
    }
  }

  @Test
  void localNavigationWhileSyncIsPendingKeepsOnlyTheManualResume() throws Exception {
    try (Harness harness = Harness.open()) {
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              harness.engine,
              command ->
                  command.equals("undo")
                      ? null
                      : ExactSnapshotRestoreProtocolFixture.Response.success());
      harness.engine.ponder();
      awaitRawCommand(transport, "kata-analyze", 0);
      harness.acceptEmptySnapshot();
      String undo = awaitRawCommand(transport, "undo", 0);
      Runnable oldResume = harness.frame.scheduledResume;
      harness.board.nextMove(false);
      oldResume.run();
      acknowledge(harness.engine, undo);
      awaitRawCommand(transport, "kata-analyze", 1);
      oldResume.run();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertEquals(2, payloadCount(transport, "kata-analyze"));
      assertSame(harness.p1, harness.board.getHistory().getCurrentHistoryNode());
      harness.engine.parseAnalysisLineForTest(info(4_875, 0.96937));
      assertAnalysis(harness.p1.getData(), 4_875, 96.937);
      assertNoAnalysis(harness.p0.getData());
    }
  }

  @Test
  void exactSnapshotResendWaitsForMovePassTailAndPublishesOnlyConfirmedTarget() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.readBoard = null;
      RestoreHistory restoreHistory = exactRestoreHistory();
      harness.board.setHistory(restoreHistory.history);
      startPonder(harness);

      SnapshotTrackingLeelaz enginePosition = SnapshotTrackingLeelaz.create();
      ExactSnapshotRestoreProtocolFixture.Transport restoreTransport =
          installPositionTransport(harness.engine, enginePosition, "play W pass");
      AsyncAction restore =
          AsyncAction.start(() -> harness.board.resendMoveToEngine(harness.engine, false));

      String finalTail = awaitRawCommand(restoreTransport, "play W pass", 0);
      boolean released = false;
      try {
        assertEquals(
            List.of("play B D16", "play W pass"),
            commandsWithPrefix(restoreTransport, "play "),
            "the exact route must replay both real actions after the static anchor");
        int loadSgfCommand = indexOfCommand(restoreTransport, "loadsgf ");
        assertTrue(loadSgfCommand >= 0);
        assertTrue(loadSgfCommand < indexOfCommand(restoreTransport, "play B D16"));
        assertEquals(
            0,
            payloadCount(restoreTransport, "kata-analyze"),
            "the unconfirmed final tail must keep physical analysis closed");

        harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
        assertNoAnalysis(restoreHistory.target.getData());
        assertPosition(enginePosition, restoreHistory.target.getData(), BOARD_SIZE, BOARD_SIZE);

        acknowledge(harness.engine, finalTail);
        released = true;
        awaitRawCommand(restoreTransport, "kata-analyze", 0);
        restore.awaitSuccess();
        harness.engine.parseAnalysisLineForTest(info(4_875, 0.96937));

        assertSame(restoreHistory.target, harness.board.getHistory().getCurrentHistoryNode());
        assertAnalysis(restoreHistory.target.getData(), 4_875, 96.937);
        assertEquals(1, payloadCount(restoreTransport, "kata-analyze"));
        assertPosition(enginePosition, restoreHistory.target.getData(), BOARD_SIZE, BOARD_SIZE);
      } finally {
        if (!released) {
          acknowledge(harness.engine, finalTail);
          restore.awaitCleanup();
        }
      }
    }
  }

  @Test
  void previousNavigationFromSnapshotChildWaitsForCompleteExactTarget() throws Exception {
    assertCompoundNavigation(false);
  }

  @Test
  void branchJumpWaitsForCompleteExactTarget() throws Exception {
    assertCompoundNavigation(true);
  }

  private void assertCompoundNavigation(boolean branchJump) throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.readBoard = null;
      RestoreHistory restoreHistory =
          branchJump ? exactBranchRestoreHistory() : exactRestoreHistoryWithSnapshotSuccessor();
      harness.board.setHistory(restoreHistory.history);
      startPonder(harness);

      SnapshotTrackingLeelaz enginePosition = SnapshotTrackingLeelaz.create();
      ExactSnapshotRestoreProtocolFixture.Transport restoreTransport =
          installPositionTransport(harness.engine, enginePosition, "play W pass");
      AsyncAction navigation =
          AsyncAction.start(
              () -> {
                if (branchJump) harness.board.moveToAnyPosition(restoreHistory.target);
                else harness.board.previousMove(false);
              });
      String finalTail = awaitRawCommand(restoreTransport, "play W pass", 0);
      boolean released = false;
      try {
        assertSame(
            restoreHistory.target,
            harness.board.getHistory().getCurrentHistoryNode(),
            "real Board navigation must adopt the intended tail target before confirmation");
        assertEquals(0, payloadCount(restoreTransport, "kata-analyze"));
        harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
        assertNoAnalysis(restoreHistory.target.getData());
        assertPosition(enginePosition, restoreHistory.target.getData(), BOARD_SIZE, BOARD_SIZE);

        acknowledge(harness.engine, finalTail);
        released = true;
        awaitRawCommand(restoreTransport, "kata-analyze", 0);
        navigation.awaitSuccess();
        harness.engine.parseAnalysisLineForTest(info(4_875, 0.96937));

        assertSame(restoreHistory.target, harness.board.getHistory().getCurrentHistoryNode());
        assertAnalysis(restoreHistory.target.getData(), 4_875, 96.937);
        assertEquals(1, payloadCount(restoreTransport, "kata-analyze"));
      } finally {
        if (!released) {
          acknowledge(harness.engine, finalTail);
          navigation.awaitCleanup();
        }
      }
    }
  }

  @Test
  void rootReplayResendWaitsForFinalPassAndPreservesCompatibleAnalysisCache() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.readBoard = null;
      RestoreHistory restoreHistory = rootRestoreHistory(9);
      harness.board.setHistory(restoreHistory.history);
      harness.engine.width = BOARD_SIZE;
      harness.engine.height = BOARD_SIZE;
      startPonder(harness);
      harness.engine.parseAnalysisLineForTest(info(12_000, 0.615));
      assertAnalysis(restoreHistory.target.getData(), 12_000, 61.5);

      SnapshotTrackingLeelaz enginePosition = SnapshotTrackingLeelaz.create();
      ExactSnapshotRestoreProtocolFixture.Transport restoreTransport =
          installPositionTransport(harness.engine, enginePosition, "play W pass");
      AsyncAction restore =
          AsyncAction.start(() -> harness.board.resendMoveToEngine(harness.engine, false));

      String finalReplay = awaitRawCommand(restoreTransport, "play W pass", 0);
      boolean released = false;
      try {
        int boardSizeCommand = indexOfCommand(restoreTransport, "boardsize 9");
        int clearCommand = indexOfCommand(restoreTransport, "clear_board");
        assertTrue(boardSizeCommand >= 0);
        assertTrue(clearCommand > boardSizeCommand);
        assertEquals(
            List.of("play B C7", "play W pass"), commandsWithPrefix(restoreTransport, "play "));
        assertEquals(0, payloadCount(restoreTransport, "loadsgf "));
        assertEquals(0, payloadCount(restoreTransport, "kata-analyze"));

        harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
        assertAnalysis(restoreHistory.target.getData(), 12_000, 61.5);
        assertPosition(enginePosition, restoreHistory.target.getData(), 9, 9);

        acknowledge(harness.engine, finalReplay);
        released = true;
        awaitRawCommand(restoreTransport, "kata-analyze", 0);
        restore.awaitSuccess();
        harness.engine.parseAnalysisLineForTest(info(4_875, 0.96937));

        assertAnalysis(restoreHistory.target.getData(), 12_000, 61.5);
        assertEquals(1, payloadCount(restoreTransport, "kata-analyze"));
        assertPosition(enginePosition, restoreHistory.target.getData(), 9, 9);
      } finally {
        if (!released) {
          acknowledge(harness.engine, finalReplay);
          restore.awaitCleanup();
        }
      }
    }
  }

  @Test
  void replacingHistoryDuringExactRestoreCannotResumeOrPublishIntoReplacement() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.readBoard = null;
      RestoreHistory restoreHistory = exactRestoreHistory();
      harness.board.setHistory(restoreHistory.history);
      startPonder(harness);

      SnapshotTrackingLeelaz enginePosition = SnapshotTrackingLeelaz.create();
      ExactSnapshotRestoreProtocolFixture.Transport restoreTransport =
          installPositionTransport(harness.engine, enginePosition, "play W pass");
      AsyncAction restore =
          AsyncAction.start(() -> harness.board.resendMoveToEngine(harness.engine, false));
      String finalTail = awaitRawCommand(restoreTransport, "play W pass", 0);
      boolean released = false;
      try {
        assertEquals(0, payloadCount(restoreTransport, "kata-analyze"));
        harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
        assertNoAnalysis(restoreHistory.target.getData());
        assertPosition(enginePosition, restoreHistory.target.getData(), BOARD_SIZE, BOARD_SIZE);

        BoardHistoryList replacement =
            new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
        BoardHistoryNode replacementTarget = replacement.getCurrentHistoryNode();
        harness.board.setHistory(replacement);
        acknowledge(harness.engine, finalTail);
        released = true;
        restore.awaitFinished();

        assertSame(replacementTarget, harness.board.getHistory().getCurrentHistoryNode());
        assertEquals(
            0,
            payloadCount(restoreTransport, "kata-analyze"),
            "an obsolete restore completion must not resume analysis");
        harness.engine.parseAnalysisLineForTest(info(4_875, 0.96937));
        assertNoAnalysis(replacementTarget.getData());
      } finally {
        if (!released) {
          acknowledge(harness.engine, finalTail);
          restore.awaitCleanup();
        }
      }
    }
  }

  @Test
  void replacingPrimaryDuringRootRestoreCannotResumeEitherEngine() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.readBoard = null;
      RestoreHistory restoreHistory = rootRestoreHistory(BOARD_SIZE);
      harness.board.setHistory(restoreHistory.history);
      startPonder(harness);

      SnapshotTrackingLeelaz enginePosition = SnapshotTrackingLeelaz.create();
      ExactSnapshotRestoreProtocolFixture.Transport restoreTransport =
          installPositionTransport(harness.engine, enginePosition, "play W pass");
      AsyncAction restore =
          AsyncAction.start(() -> harness.board.resendMoveToEngine(harness.engine, false));
      String finalReplay = awaitRawCommand(restoreTransport, "play W pass", 0);

      Leelaz replacement = new PublicationControlledLeelaz();
      replacement.isKatago = true;
      replacement.started = true;
      replacement.isLoaded = true;
      replacement.commandLists.addAll(List.of("name", "play", "stop", "kata-analyze"));
      setField(replacement, "endGetCommandList", true);
      ExactSnapshotRestoreProtocolFixture.Transport replacementTransport =
          ExactSnapshotRestoreProtocolFixture.install(
              replacement, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      Lizzie.setPrimaryEngine(replacement);

      acknowledge(harness.engine, finalReplay);
      restore.awaitFinished();

      assertEquals(0, payloadCount(restoreTransport, "kata-analyze"));
      assertTrue(
          replacementTransport.commands().isEmpty(),
          "the old completion must not send restore or analysis commands to the replacement");
      assertNoAnalysis(restoreHistory.target.getData());
      replacement.started = false;
      replacement.isLoaded = false;
    }
  }

  @Test
  void userPauseCancelsSelectedAnalysisBeforeWriteAndAllowsLaterResume() throws Exception {
    try (Harness harness = Harness.open()) {
      Field lockField =
          EngineManager.class.getDeclaredField("ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK");
      lockField.setAccessible(true);
      ReentrantLock writeAdmission = (ReentrantLock) lockField.get(null);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread writer =
          new Thread(
              () -> {
                try {
                  harness.engine.ponder();
                } catch (Throwable thrown) {
                  failure.set(thrown);
                }
              },
              "selected-analysis-pause-regression");
      writeAdmission.lock();
      try {
        writer.start();
        long deadline = System.nanoTime() + OBSERVATION_TIMEOUT_NANOS;
        while (!writeAdmission.hasQueuedThread(writer) && System.nanoTime() < deadline) {
          Thread.sleep(1L);
        }
        assertTrue(writeAdmission.hasQueuedThread(writer), "writer must reach physical admission");
        assertTrue(harness.transport.commands().isEmpty());
        harness.engine.pauseForAnalysisControl(() -> harness.frame.userAnalysisPaused = true);
      } finally {
        writeAdmission.unlock();
        writer.join(TimeUnit.SECONDS.toMillis(2));
      }
      assertFalse(writer.isAlive());
      assertEquals(null, failure.get());
      assertEquals(0, payloadCount(harness.transport, "kata-analyze"));
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "stop", 0));
      harness.frame.userAnalysisPaused = false;
      harness.engine.ponder();
      awaitRawCommand(harness.transport, "kata-analyze", 0);
      assertEquals(1, payloadCount(harness.transport, "kata-analyze"));
    }
  }

  @Test
  void userPauseCancelsAnalysisRequestedDuringBranchRestore() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.readBoard = null;
      RestoreHistory target = exactBranchRestoreHistory();
      harness.board.setHistory(target.history);
      startPonder(harness);
      SnapshotTrackingLeelaz enginePosition = SnapshotTrackingLeelaz.create();
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          installPositionTransport(harness.engine, enginePosition, "play W pass");
      AsyncAction restore = AsyncAction.start(() -> harness.board.moveToAnyPosition(target.target));
      String tail = awaitRawCommand(transport, "play W pass", 0);
      try {
        harness.engine.ponder();
        harness.engine.pauseForAnalysisControl(() -> harness.frame.userAnalysisPaused = true);
      } finally {
        acknowledge(harness.engine, tail);
        restore.awaitSuccess();
      }
      assertEquals(0, payloadCount(transport, "kata-analyze"));
      assertNoAnalysis(target.target.getData());
    }
  }

  @Test
  void userPauseWhileExactRestoreIsPendingSuppressesCapturedPonderDisposition() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.readBoard = null;
      RestoreHistory restoreHistory = exactRestoreHistory();
      harness.board.setHistory(restoreHistory.history);
      startPonder(harness);

      SnapshotTrackingLeelaz enginePosition = SnapshotTrackingLeelaz.create();
      ExactSnapshotRestoreProtocolFixture.Transport restoreTransport =
          installPositionTransport(harness.engine, enginePosition, "play W pass");
      AsyncAction restore =
          AsyncAction.start(() -> harness.board.resendMoveToEngine(harness.engine, false));
      String finalTail = awaitRawCommand(restoreTransport, "play W pass", 0);

      harness.frame.userAnalysisPaused = true;
      acknowledge(harness.engine, finalTail);
      restore.awaitSuccess();

      assertEquals(
          0,
          payloadCount(restoreTransport, "kata-analyze"),
          "a pause chosen before confirmation must suppress the captured resume");
      assertNoAnalysis(restoreHistory.target.getData());
      assertPosition(enginePosition, restoreHistory.target.getData(), BOARD_SIZE, BOARD_SIZE);
    }
  }

  @Test
  void acceptedSnapshotRollbackRejectsOldAnalysisUntilUndoIsConfirmed() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.engine.playMoveNoPonder(Stone.BLACK, "Q16");
      String play = awaitRawCommand(harness.transport, "play B Q16", 0);
      acknowledge(harness.engine, play);

      harness.engine.ponder();
      String initialAnalyze = awaitRawCommand(harness.transport, "kata-analyze", 0);
      acknowledge(harness.engine, initialAnalyze);
      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));

      assertAnalysis(harness.p1.getData(), 16_768, 33.5525);
      assertNoAnalysis(harness.p0.getData());

      harness.engine.sendCommandWithResponseForTest("name", () -> {});
      String precedingQuery = awaitRawCommand(harness.transport, "name", 0);

      harness.acceptEmptySnapshot();

      assertSame(
          harness.p0,
          harness.board.getHistory().getCurrentHistoryNode(),
          "the accepted snapshot must navigate the real history back to P0");
      assertSame(
          harness.p1,
          harness.board.getHistory().getMainEnd(),
          "rollback must retain the legitimate P1 history node");
      assertEquals(
          0,
          payloadCount(harness.transport, "undo"),
          "the unanswered query must keep undo in the real command queue");
      assertEquals(
          1,
          payloadCount(harness.transport, "kata-analyze"),
          "queued rollback must not physically start analysis for P0");
      assertNotNull(
          harness.frame.scheduledResume,
          "ReadBoard should offer its delayed resume to the scheduler seam");
      assertEquals(1, harness.frame.scheduledResumeCount);
      harness.frame.scheduledResume.run();

      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
      assertNoAnalysis(harness.p0.getData());
      assertAnalysis(harness.p1.getData(), 16_768, 33.5525);

      acknowledge(harness.engine, precedingQuery);
      String undo = awaitRawCommand(harness.transport, "undo", 0);

      assertEquals(
          1,
          payloadCount(harness.transport, "kata-analyze"),
          "a written but unconfirmed undo must not physically start successor analysis");
      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
      assertNoAnalysis(harness.p0.getData());
      assertAnalysis(harness.p1.getData(), 16_768, 33.5525);

      acknowledge(harness.engine, undo);
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "name", 1));
      String confirmedAnalyze = awaitRawCommand(harness.transport, "kata-analyze", 1);
      acknowledge(harness.engine, confirmedAnalyze);
      harness.engine.parseAnalysisLineForTest(info(4_875, 0.96937));

      assertSame(harness.p0, harness.board.getHistory().getCurrentHistoryNode());
      assertAnalysis(harness.p0.getData(), 4_875, 96.937);
      assertAnalysis(harness.p1.getData(), 16_768, 33.5525);
      assertEquals(
          2,
          payloadCount(harness.transport, "kata-analyze"),
          "confirmation should produce one physical successor analysis start");
    }
  }

  @Test
  void manualNavigationPassAndPlacementBindTheirIntendedTargets() throws Exception {
    BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
    try (Harness harness = Harness.open()) {
      LizzieFrame.boardRenderer = allocate(SilentBoardRenderer.class);
      harness.frame.readBoard = null;
      harness.engine.canAddPlayer = true;
      harness.engine.playMoveNoPonder(Stone.BLACK, "Q16");
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "play B Q16", 0));
      harness.engine.ponder();
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze", 0));
      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));

      assertTrue(harness.board.previousMove(false));
      String undo = awaitRawCommand(harness.transport, "undo", 0);
      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
      assertNoAnalysis(harness.p0.getData());
      assertEquals(1, payloadCount(harness.transport, "kata-analyze"));
      acknowledge(harness.engine, undo);
      String backwardAnalysis = awaitRawCommand(harness.transport, "kata-analyze B", 0);
      acknowledge(harness.engine, backwardAnalysis);
      harness.engine.parseAnalysisLineForTest(info(4_875, 0.96937));
      assertAnalysis(harness.p0.getData(), 4_875, 96.937);

      assertTrue(harness.board.nextMove(false));
      String replay = awaitRawCommand(harness.transport, "play B Q16", 1);
      assertSame(harness.p1, harness.board.getHistory().getCurrentHistoryNode());
      assertEquals(2, payloadCount(harness.transport, "kata-analyze"));
      acknowledge(harness.engine, replay);
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze W", 1));
      harness.engine.parseAnalysisLineForTest(info(100, 0.6));
      assertAnalysis(harness.p1.getData(), 16_768, 33.5525);

      harness.board.pass();
      BoardHistoryNode pass = harness.board.getHistory().getCurrentHistoryNode();
      assertTrue(pass.getData().isPassNode());
      String passCommand = awaitRawCommand(harness.transport, "play W pass", 0);
      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
      assertNoAnalysis(pass.getData());
      assertEquals(3, payloadCount(harness.transport, "kata-analyze"));
      acknowledge(harness.engine, passCommand);
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze B", 1));
      harness.engine.parseAnalysisLineForTest(info(50, 0.8));
      assertAnalysis(pass.getData(), 50, 80);

      harness.board.place(3, 15, Stone.BLACK);
      BoardHistoryNode placed = harness.board.getHistory().getCurrentHistoryNode();
      String placement = awaitRawCommand(harness.transport, "play B D4", 0);
      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
      assertNoAnalysis(placed.getData());
      assertEquals(4, payloadCount(harness.transport, "kata-analyze"));
      acknowledge(harness.engine, placement);
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze W", 2));
      harness.engine.parseAnalysisLineForTest(info(25, 0.7));
      assertAnalysis(placed.getData(), 25, 70);
    } finally {
      LizzieFrame.boardRenderer = previousRenderer;
    }
  }

  @Test
  void secondaryPacketsCannotEnterPrimarySlotAfterLeavingDoubleMode() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.engine.playMoveNoPonder(Stone.BLACK, "Q16");
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "play B Q16", 0));
      harness.engine.ponder();
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze", 0));
      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));
      Leelaz secondary = new Leelaz("");
      secondary.started = true;
      secondary.isLoaded = true;
      secondary.isKatago = true;
      Lizzie.leelaz2 = secondary;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      ExactSnapshotRestoreProtocolFixture.install(
          secondary, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
      secondary.sendCommandNoLeelaz2("kata-analyze W 10");
      secondary.parseAnalysisLineForTest(info(40, 0.6));
      assertEquals(40, harness.p1.getData().getPlayouts2());

      Lizzie.config.extraMode = ExtraMode.Normal;
      secondary.parseAnalysisLineForTest(info(20_000, 0.8));

      assertAnalysis(harness.p1.getData(), 16_768, 33.5525);
    }
  }

  @Test
  void savedSgfAnalysisSurvivesConfirmedHistoryRevisit() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.readBoard = null;
      harness.engine.bestMovesEnginename = "KataGo";
      harness.engine.playMoveNoPonder(Stone.BLACK, "Q16");
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "play B Q16", 0));
      harness.engine.ponder();
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze", 0));
      harness.engine.parseAnalysisLineForTest(info(12_000, 0.615));
      String saved = SGFParser.saveToString(false);
      BoardHistoryList imported = SGFParser.parseSgf(saved, false);
      BoardHistoryNode importedP1 = imported.getMainEnd();
      assertAnalysis(importedP1.getData(), 12_000, 61.5);
      harness.board.setHistory(imported);
      harness.engine.sendCommand("clear_board");
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "clear_board", 0));

      assertTrue(harness.board.nextMove(false));
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "play B Q16", 1));
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze", 1));
      harness.engine.parseAnalysisLineForTest(info(50, 0.8));

      assertSame(importedP1, harness.board.getHistory().getCurrentHistoryNode());
      assertAnalysis(importedP1.getData(), 12_000, 61.5);
    }
  }

  @Test
  void navigationBetweenInfoIngressAndPublicationCannotWriteSuccessor() throws Exception {
    try (Harness harness = Harness.open()) {
      harness.frame.readBoard = null;
      harness.engine.playMoveNoPonder(Stone.BLACK, "Q16");
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "play B Q16", 0));
      harness.engine.ponder();
      acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze", 0));
      ((PublicationControlledLeelaz) harness.engine).beforePublication =
          () -> assertTrue(harness.board.previousMove(false));

      harness.engine.parseAnalysisLineForTest(info(16_768, 0.335525));

      assertSame(harness.p0, harness.board.getHistory().getCurrentHistoryNode());
      assertNoAnalysis(harness.p0.getData());
      assertEquals(1, payloadCount(harness.transport, "kata-analyze"));
    }
  }

  private static void startPonder(Harness harness) throws Exception {
    harness.engine.ponder();
    acknowledge(harness.engine, awaitRawCommand(harness.transport, "kata-analyze", 0));
  }

  private static ExactSnapshotRestoreProtocolFixture.Transport installPositionTransport(
      Leelaz engine, SnapshotTrackingLeelaz enginePosition, String heldCommand) {
    return ExactSnapshotRestoreProtocolFixture.install(
        engine,
        command -> {
          if (command.startsWith("loadsgf ")) {
            enginePosition.loadSgf(Path.of(command.substring("loadsgf ".length())));
          } else {
            enginePosition.sendCommand(command);
          }
          return command.equals(heldCommand)
              ? null
              : ExactSnapshotRestoreProtocolFixture.Response.success();
        });
  }

  private static RestoreHistory exactRestoreHistory() {
    Board.boardWidth = BOARD_SIZE;
    Board.boardHeight = BOARD_SIZE;
    Zobrist.init();
    Stone[] setupStones = emptyStones(BOARD_SIZE, BOARD_SIZE);
    setupStones[Board.getIndex(0, 0)] = Stone.BLACK;
    setupStones[Board.getIndex(1, 0)] = Stone.WHITE;
    BoardData setup =
        BoardData.snapshot(
            setupStones,
            java.util.Optional.of(new int[] {1, 0}),
            Stone.WHITE,
            true,
            zobrist(setupStones, BOARD_SIZE, BOARD_SIZE),
            3,
            new int[setupStones.length],
            0,
            0,
            50,
            0);
    setup.addProperty("SZ", String.valueOf(BOARD_SIZE));
    setup.addProperty("PL", "B");
    BoardHistoryList history = new BoardHistoryList(setup);

    Stone[] movedStones = setupStones.clone();
    movedStones[Board.getIndex(3, 3)] = Stone.BLACK;
    history.add(
        BoardData.move(
            movedStones,
            new int[] {3, 3},
            Stone.BLACK,
            false,
            zobrist(movedStones, BOARD_SIZE, BOARD_SIZE),
            4,
            new int[movedStones.length],
            0,
            0,
            50,
            0));
    history.add(
        BoardData.pass(
            movedStones.clone(),
            Stone.WHITE,
            true,
            zobrist(movedStones, BOARD_SIZE, BOARD_SIZE),
            5,
            new int[movedStones.length],
            0,
            0,
            50,
            0));
    BoardHistoryNode target = history.getCurrentHistoryNode();
    return new RestoreHistory(history, target);
  }

  private static RestoreHistory exactBranchRestoreHistory() {
    RestoreHistory source = exactRestoreHistory();
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(source.history.getStart().getData().clone());
    history.add(source.target.previous().orElseThrow().getData().clone());
    history.add(source.target.getData().clone());
    BoardHistoryNode target = history.getCurrentHistoryNode();
    history.toStart();
    return new RestoreHistory(history, target);
  }

  private static RestoreHistory exactRestoreHistoryWithSnapshotSuccessor() {
    RestoreHistory restoreHistory = exactRestoreHistory();
    Stone[] successorStones = restoreHistory.target.getData().stones.clone();
    successorStones[Board.getIndex(9, 9)] = Stone.BLACK;
    BoardData successor =
        BoardData.snapshot(
            successorStones,
            java.util.Optional.of(new int[] {9, 9}),
            Stone.BLACK,
            false,
            zobrist(successorStones, BOARD_SIZE, BOARD_SIZE),
            6,
            new int[successorStones.length],
            0,
            0,
            50,
            0);
    successor.addProperty("SZ", String.valueOf(BOARD_SIZE));
    successor.addProperty("PL", "W");
    restoreHistory.history.add(successor);
    return restoreHistory;
  }

  private static RestoreHistory rootRestoreHistory(int size) {
    Board.boardWidth = size;
    Board.boardHeight = size;
    Zobrist.init();
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(size, size));
    Stone[] movedStones = emptyStones(size, size);
    movedStones[Board.getIndex(2, 2)] = Stone.BLACK;
    history.add(
        BoardData.move(
            movedStones,
            new int[] {2, 2},
            Stone.BLACK,
            false,
            zobrist(movedStones, size, size),
            1,
            new int[movedStones.length],
            0,
            0,
            50,
            0));
    history.add(
        BoardData.pass(
            movedStones.clone(),
            Stone.WHITE,
            true,
            zobrist(movedStones, size, size),
            2,
            new int[movedStones.length],
            0,
            0,
            50,
            0));
    return new RestoreHistory(history, history.getCurrentHistoryNode());
  }

  private static Stone[] emptyStones(int width, int height) {
    Stone[] stones = new Stone[width * height];
    java.util.Arrays.fill(stones, Stone.EMPTY);
    return stones;
  }

  private static Zobrist zobrist(Stone[] stones, int width, int height) {
    Zobrist value = new Zobrist();
    for (int x = 0; x < width; x++) {
      for (int y = 0; y < height; y++) {
        Stone stone = stones[x * height + y];
        if (!stone.isEmpty()) {
          value.toggleStone(x, y, stone);
        }
      }
    }
    return value;
  }

  private static List<String> commandsWithPrefix(
      ExactSnapshotRestoreProtocolFixture.Transport transport, String prefix) {
    ArrayList<String> matches = new ArrayList<>();
    for (String command : transport.commands()) {
      if (command.startsWith(prefix)) {
        matches.add(command);
      }
    }
    return matches;
  }

  private static int indexOfCommand(
      ExactSnapshotRestoreProtocolFixture.Transport transport, String prefix) {
    List<String> commands = transport.commands();
    for (int index = 0; index < commands.size(); index++) {
      if (commands.get(index).startsWith(prefix)) {
        return index;
      }
    }
    return -1;
  }

  private static void assertPosition(
      SnapshotTrackingLeelaz enginePosition, BoardData expected, int width, int height) {
    assertEquals(width * height, enginePosition.copyStones().length);
    assertArrayEquals(expected.stones, enginePosition.copyStones());
    assertEquals(expected.blackToPlay, enginePosition.isBlackToPlay());
    assertEquals(width, Board.boardWidth);
    assertEquals(height, Board.boardHeight);
  }

  private static final class RestoreHistory {
    private final BoardHistoryList history;
    private final BoardHistoryNode target;

    private RestoreHistory(BoardHistoryList history, BoardHistoryNode target) {
      this.history = history;
      this.target = target;
    }
  }

  private static final class AsyncAction {
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Thread thread;

    private AsyncAction(Runnable action) {
      thread =
          new Thread(
              () -> {
                try {
                  action.run();
                } catch (Throwable thrown) {
                  failure.set(thrown);
                }
              },
              "position-confirmed-restore-test");
      thread.setDaemon(true);
      thread.start();
    }

    private static AsyncAction start(Runnable action) {
      return new AsyncAction(action);
    }

    private void awaitFinished() throws InterruptedException {
      thread.join(TimeUnit.NANOSECONDS.toMillis(OBSERVATION_TIMEOUT_NANOS));
      assertFalse(thread.isAlive(), "restore action did not finish after its final response");
    }

    private void awaitCleanup() throws InterruptedException {
      thread.join(TimeUnit.NANOSECONDS.toMillis(OBSERVATION_TIMEOUT_NANOS));
    }

    private void awaitSuccess() throws InterruptedException {
      awaitFinished();
      Throwable thrown = failure.get();
      if (thrown != null) {
        throw new AssertionError("restore action failed", thrown);
      }
    }
  }

  private static String info(int visits, double winrate) {
    return "info move C13 visits "
        + visits
        + " winrate "
        + winrate
        + " prior 0.7 lcb "
        + winrate
        + " scoreMean 1 scoreStdev 2 order 0 pv C13";
  }

  private static void assertNoAnalysis(BoardData data) {
    assertEquals(0, data.getPlayouts());
    assertTrue(data.bestMoves.isEmpty());
  }

  private static void assertAnalysis(BoardData data, int visits, double winrate) {
    assertEquals(visits, data.getPlayouts());
    assertEquals(winrate, data.winrate, 0.000_001);
    assertEquals(1, data.bestMoves.size());
    assertEquals("C13", data.bestMoves.get(0).coordinate);
    assertEquals(visits, data.bestMoves.get(0).playouts);
    assertEquals(winrate, data.bestMoves.get(0).winrate, 0.000_001);
  }

  private static String awaitRawCommand(
      ExactSnapshotRestoreProtocolFixture.Transport transport, String prefix, int occurrence)
      throws InterruptedException {
    long deadline = System.nanoTime() + OBSERVATION_TIMEOUT_NANOS;
    while (System.nanoTime() < deadline) {
      String raw = rawCommand(transport, prefix, occurrence);
      if (raw != null) {
        return raw;
      }
      Thread.sleep(5L);
    }
    throw new AssertionError(
        "Timed out waiting for physical command "
            + prefix
            + " occurrence "
            + occurrence
            + "; commands="
            + transport.rawCommands());
  }

  private static String rawCommand(
      ExactSnapshotRestoreProtocolFixture.Transport transport, String prefix, int occurrence) {
    List<String> commands = transport.commands();
    List<String> rawCommands = transport.rawCommands();
    int matched = 0;
    for (int index = 0; index < commands.size(); index++) {
      if (commands.get(index).startsWith(prefix)) {
        if (matched == occurrence) {
          return rawCommands.get(index);
        }
        matched++;
      }
    }
    return null;
  }

  private static int payloadCount(
      ExactSnapshotRestoreProtocolFixture.Transport transport, String prefix) {
    int count = 0;
    for (String command : transport.commands()) {
      if (command.startsWith(prefix)) {
        count++;
      }
    }
    return count;
  }

  private static void acknowledge(Leelaz engine, String rawCommand) {
    int split = rawCommand.indexOf(' ');
    String id =
        split > 0 && isDigits(rawCommand.substring(0, split)) ? rawCommand.substring(0, split) : "";
    engine.processCommandResponseLineForTest("=" + id);
  }

  private static boolean isDigits(String value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      if (!Character.isDigit(value.charAt(index))) {
        return false;
      }
    }
    return true;
  }

  private static void setPendingSnapshot(ReadBoard readBoard, int[] snapshotCodes)
      throws Exception {
    ArrayList<Integer> counts = new ArrayList<>(snapshotCodes.length);
    for (int code : snapshotCodes) {
      counts.add(code);
    }
    setField(readBoard, "tempcount", counts);
  }

  private static void invokeSyncBoardStones(ReadBoard readBoard) throws Exception {
    Method method = ReadBoard.class.getDeclaredMethod("syncBoardStones", boolean.class);
    method.setAccessible(true);
    method.invoke(readBoard, false);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    field.set(target, value);
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

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  private static final class Harness implements AutoCloseable {
    private final Config previousConfig;
    private final Board previousBoard;
    private final Leelaz previousPrimary;
    private final Leelaz previousSecondary;
    private final LizzieFrame previousFrame;
    private final EngineManager previousManager;
    private final Menu previousMenu;
    private final BottomToolbar previousToolbar;
    private final boolean previousEngineEmpty;
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final boolean previousKeepForcing;
    private final String previousAllowCoords;
    private final String previousAvoidCoords;

    private final HeadlessBoard board;
    private final RecordingFrame frame;
    private final Leelaz engine;
    private final ReadBoard readBoard;
    private final ExactSnapshotRestoreProtocolFixture.Transport transport;
    private final BoardHistoryNode p0;
    private final BoardHistoryNode p1;

    private Harness(
        Config previousConfig,
        Board previousBoard,
        Leelaz previousPrimary,
        Leelaz previousSecondary,
        LizzieFrame previousFrame,
        EngineManager previousManager,
        Menu previousMenu,
        BottomToolbar previousToolbar,
        boolean previousEngineEmpty,
        int previousBoardWidth,
        int previousBoardHeight,
        boolean previousKeepForcing,
        String previousAllowCoords,
        String previousAvoidCoords,
        HeadlessBoard board,
        RecordingFrame frame,
        Leelaz engine,
        ReadBoard readBoard,
        ExactSnapshotRestoreProtocolFixture.Transport transport,
        BoardHistoryNode p0,
        BoardHistoryNode p1) {
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousPrimary = previousPrimary;
      this.previousSecondary = previousSecondary;
      this.previousFrame = previousFrame;
      this.previousManager = previousManager;
      this.previousMenu = previousMenu;
      this.previousToolbar = previousToolbar;
      this.previousEngineEmpty = previousEngineEmpty;
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousKeepForcing = previousKeepForcing;
      this.previousAllowCoords = previousAllowCoords;
      this.previousAvoidCoords = previousAvoidCoords;
      this.board = board;
      this.frame = frame;
      this.engine = engine;
      this.readBoard = readBoard;
      this.transport = transport;
      this.p0 = p0;
      this.p1 = p1;
    }

    private static Harness open() throws Exception {
      Config previousConfig = Lizzie.config;
      Board previousBoard = Lizzie.board;
      Leelaz previousPrimary = Lizzie.leelaz;
      Leelaz previousSecondary = Lizzie.leelaz2;
      LizzieFrame previousFrame = Lizzie.frame;
      EngineManager previousManager = Lizzie.engineManager;
      Menu previousMenu = LizzieFrame.menu;
      BottomToolbar previousToolbar = LizzieFrame.toolbar;
      boolean previousEngineEmpty = EngineManager.isEmpty;
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      boolean previousKeepForcing = LizzieFrame.isKeepForcing;
      String previousAllowCoords = LizzieFrame.allowcoords;
      String previousAvoidCoords = LizzieFrame.avoidcoords;

      EngineManager.resetEngineGameTransactionStateForTest();
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      Config config = allocate(Config.class);
      config.enableLizzieCache = true;
      config.readBoardPonder = true;
      config.analyzeBlack = true;
      config.analyzeWhite = true;
      config.analyzeUpdateIntervalCentisec = 10;
      config.newMoveNumberInBranch = true;
      Lizzie.config = config;

      RecordingFrame frame = allocate(RecordingFrame.class);
      frame.priorityMoveCoords = new ArrayList<>();
      frame.isPlayingAgainstLeelaz = false;
      frame.isAnaPlayingAgainstLeelaz = false;
      frame.bothSync = false;
      frame.syncBoard = false;
      Lizzie.frame = frame;
      LizzieFrame.menu = allocate(SilentMenu.class);
      LizzieFrame.toolbar = allocate(BottomToolbar.class);
      LizzieFrame.isKeepForcing = false;
      LizzieFrame.allowcoords = "";
      LizzieFrame.avoidcoords = "";

      Leelaz engine = new PublicationControlledLeelaz();
      engine.isKatago = true;
      engine.started = true;
      engine.isLoaded = true;
      engine.requireResponseBeforeSend = true;
      engine.commandLists.addAll(List.of("name", "play", "undo", "stop", "kata-analyze"));
      setField(engine, "endGetCommandList", true);
      Lizzie.engineManager = new EngineManager(List.of(engine));
      EngineManager.isEmpty = false;
      Lizzie.setPrimaryEngine(engine);
      Lizzie.leelaz2 = null;

      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(engine, command -> null);

      HeadlessBoard board = new HeadlessBoard();
      Lizzie.board = board;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode p0 = history.getCurrentHistoryNode();
      history.place(15, 3, Stone.BLACK, false);
      BoardHistoryNode p1 = history.getCurrentHistoryNode();
      board.setHistory(history);

      ReadBoard readBoard = allocate(ReadBoard.class);
      setField(readBoard, "conflictTracker", new SyncConflictTracker());
      setField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
      setField(readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
      setField(readBoard, "tempcount", new ArrayList<Integer>());
      readBoard.firstSync = false;
      frame.readBoard = readBoard;

      readBoard.parseLine("syncPlatform fox");
      readBoard.parseLine("roomToken rollback-regression");
      readBoard.parseLine("liveTitleMove 0");
      readBoard.parseLine("foxMoveNumber 0");

      return new Harness(
          previousConfig,
          previousBoard,
          previousPrimary,
          previousSecondary,
          previousFrame,
          previousManager,
          previousMenu,
          previousToolbar,
          previousEngineEmpty,
          previousBoardWidth,
          previousBoardHeight,
          previousKeepForcing,
          previousAllowCoords,
          previousAvoidCoords,
          board,
          frame,
          engine,
          readBoard,
          transport,
          p0,
          p1);
    }

    private void acceptEmptySnapshot() throws Exception {
      setPendingSnapshot(readBoard, new int[BOARD_SIZE * BOARD_SIZE]);
      invokeSyncBoardStones(readBoard);
    }

    private void acceptSnapshot(BoardHistoryNode node) throws Exception {
      int[] snapshot = new int[Board.boardWidth * Board.boardHeight];
      BoardData data = node.getData();
      for (int y = 0; y < Board.boardHeight; y++) {
        for (int x = 0; x < Board.boardWidth; x++) {
          Stone stone = data.stones[Board.getIndex(x, y)];
          snapshot[y * Board.boardWidth + x] =
              stone == Stone.BLACK ? 1 : stone == Stone.WHITE ? 2 : 0;
        }
      }
      if (data.lastMove.isPresent()) {
        int[] point = data.lastMove.get();
        snapshot[point[1] * Board.boardWidth + point[0]] =
            data.lastMoveColor == Stone.BLACK ? 3 : 4;
      }
      readBoard.parseLine("foxMoveNumber " + data.moveNumber);
      readBoard.parseLine("liveTitleMove " + data.moveNumber);
      setPendingSnapshot(readBoard, snapshot);
      invokeSyncBoardStones(readBoard);
    }

    @Override
    public void close() {
      try {
        Field pending = Board.class.getDeclaredField("lastSyncNavigation");
        pending.setAccessible(true);
        java.util.concurrent.CompletableFuture<?> confirmation =
            (java.util.concurrent.CompletableFuture<?>) pending.get(board);
        if (confirmation != null)
          confirmation.handle((result, failure) -> null).get(3, TimeUnit.SECONDS);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
      } catch (Exception failure) {
        throw new AssertionError("sync worker did not finish before fixture teardown", failure);
      } finally {
      engine.started = false;
      engine.isLoaded = false;
      EngineManager.resetEngineGameTransactionStateForTest();
      Lizzie.engineManager = previousManager;
      EngineManager.isEmpty = previousEngineEmpty;
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.toolbar = previousToolbar;
      LizzieFrame.isKeepForcing = previousKeepForcing;
      LizzieFrame.allowcoords = previousAllowCoords;
      LizzieFrame.avoidcoords = previousAvoidCoords;
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      }
    }
  }

  private static final class PublicationControlledLeelaz extends Leelaz {
    private Runnable beforePublication;
    private long responseTimeoutMillis = 2_000;

    @Override
    protected long readBoardGmaRestoreResponseTimeoutMillis() {
      return responseTimeoutMillis;
    }

    private PublicationControlledLeelaz() throws java.io.IOException {
      super("");
    }

    @Override
    void beforeAnalysisDisplayPublicationForTest() {
      Runnable action = beforePublication;
      beforePublication = null;
      if (action != null) action.run();
    }
  }

  private static final class HeadlessBoard extends Board {
    @Override
    public void clearAfterMove() {
      Lizzie.leelaz.clearPonderLimit();
    }
  }

  private static final class RecordingFrame extends LizzieFrame {
    private int scheduledResumeCount;
    private volatile Runnable scheduledResume;
    private boolean scheduledResumeRan;
    private boolean userAnalysisPaused;

    @Override
    public BoardHistoryNode getDisplayNode() {
      return Lizzie.board.getHistory().getCurrentHistoryNode();
    }

    @Override
    public void scheduleResumeAnalysisAfterLoad(int delayMillis, Runnable action) {
      scheduledResumeCount++;
      scheduledResume =
          () -> {
            scheduledResumeRan = true;
            action.run();
          };
    }

    @Override
    public boolean isUserAnalysisPaused() {
      return userAnalysisPaused;
    }

    @Override
    public void onMainEnginePonder() {}

    @Override
    public void clearSelectImage() {}

    @Override
    public void clearKataEstimate() {}

    @Override
    public void clearTryPlay() {}

    @Override
    public void requestAnalysisRefresh() {}

    @Override
    public void requestAnalysisTitleUpdate() {}

    @Override
    public void refresh() {}

    @Override
    public void refreshAfterMove() {}

    @Override
    public void updateTitle() {}

    @Override
    public void resetTitle() {}

    @Override
    public void renderVarTree(int vw, int vh, boolean changeSize, boolean needGetEnd) {}
  }

  private static final class SilentBoardRenderer extends BoardRenderer {
    private SilentBoardRenderer() {
      super(false);
    }

    @Override
    public void removedrawmovestone() {}
  }

  private static final class SilentMenu extends Menu {
    private SilentMenu() {}

    @Override
    public void toggleEngineMenuStatus(boolean isPondering, boolean isThinking) {}

    @Override
    public void toggleDoubleMenuGameStatus() {}
  }
}
