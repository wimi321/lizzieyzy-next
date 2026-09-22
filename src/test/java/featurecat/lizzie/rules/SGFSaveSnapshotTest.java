package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class SGFSaveSnapshotTest {
  @Test
  void selectedBranchKeepsAncestorPathAndContinuationWithoutMutatingLiveTree() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardHistoryList history =
          SGFParser.parseSgf(
              "(;SZ[5]PB[黑方]C[root];B[aa](;W[bb];B[cc])(;W[dd]C[branch];B[ee]))", true);
      Lizzie.board.setHistory(history);
      var root = history.getStart();
      var fork = root.next().orElseThrow();
      var selected = fork.getVariation(1).orElseThrow();
      history.setHead(selected);
      var originalProperties = new LinkedHashMap<>(root.getData().getProperties());
      String saved = capture(true, false, true);
      assertTrue(saved.contains(";B[aa]"));
      assertTrue(saved.contains(";W[dd]"));
      assertTrue(saved.contains(";B[ee]"));
      assertFalse(saved.contains(";W[bb]"));
      assertFalse(saved.contains(";B[cc]"));
      assertFalse(saved.contains("C[branch]"));
      assertSame(history, Lizzie.board.getHistory());
      assertSame(selected, history.getCurrentHistoryNode());
      assertSame(root, history.getStart());
      assertEquals(2, fork.numberOfChildren());
      assertEquals("root", root.getData().comment);
      assertEquals("branch", selected.getData().comment);
      assertEquals(originalProperties, root.getData().getProperties());
      assertEquals("黑方", SGFParser.parseSgf(saved, true).getGameInfo().getPlayerBlack());
    }
  }

  @Test
  void branchRetainsRootSetupPassAndMidgameSetupSemantics() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      var history = SGFParser.parseSgf("(;SZ[5]AB[aa]AW[bb]PL[W];W[];AE[aa]AB[cc];B[dd])", true);
      Lizzie.board.setHistory(history);
      var last = history.getEnd();
      history.setHead(last);
      var replayed = SGFParser.parseSgf(capture(true, false, true), true);
      assertArrayEquals(last.getData().stones, replayed.getEnd().getData().stones);
      assertEquals(last.getData().blackToPlay, replayed.getEnd().getData().blackToPlay);
      assertTrue(replayed.getStart().next().orElseThrow().getData().isPassNode());
    }
  }

  @Test
  void exportModesKeepCommentsAndRestoreFlagsAfterSnapshotAndFailure() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      Lizzie.board.setHistory(SGFParser.parseSgf("(;SZ[5];B[aa]C[中文注释](;W[bb])(;W[dd]))", true));
      boolean originalComments = LizzieFrame.isSavingRawComment;
      try {
        LizzieFrame.isSavingRaw = true;
        LizzieFrame.isSavingRawComment = true;
        String normal = capture(false, false, false);
        String raw = capture(true, false, false);
        String comments = capture(true, true, false);
        assertTrue(normal.contains("C[中文注释]"));
        assertTrue(normal.contains(";W[bb]"));
        assertTrue(normal.contains(";W[dd]"));
        assertFalse(raw.contains("C[中文注释]"));
        assertTrue(comments.contains("C[中文注释]"));
        assertTrue(LizzieFrame.isSavingRaw);
        assertTrue(LizzieFrame.isSavingRawComment);
        SwingUtilities.invokeAndWait(
            () ->
                assertThrows(
                    NullPointerException.class,
                    () -> SGFParser.saveSnapshot(null, false, false, false)));
        assertTrue(LizzieFrame.isSavingRaw);
        assertTrue(LizzieFrame.isSavingRawComment);
      } finally {
        LizzieFrame.isSavingRawComment = originalComments;
      }
    }
  }

  @Test
  void snapshotIsImmutableWhenUserContinuesEditing() throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      Lizzie.board.setHistory(SGFParser.parseSgf("(;SZ[5];B[aa]C[before])", true));
      var history = Lizzie.board.getHistory();
      String saved = capture(false, false, false);
      history.getEnd().getData().comment = "after";
      assertTrue(saved.contains("C[before]"));
      assertFalse(saved.contains("C[after]"));
      assertThrows(
          IllegalStateException.class,
          () -> SGFParser.saveSnapshot(Lizzie.board, false, false, false));
    }
  }

  private static String capture(boolean raw, boolean comments, boolean branch) throws Exception {
    AtomicReference<String> result = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            result.set(SGFParser.saveSnapshot(Lizzie.board, raw, comments, branch));
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        });
    return result.get();
  }

  @Test
  void analysisSnapshotWaitsForCompletePayloadInsteadOfMixingVisitsAndCandidates()
      throws Exception {
    try (var ignored = RulesLayerTestHarness.open()) {
      BoardData data = Lizzie.board.getHistory().getData();
      java.util.concurrent.CountDownLatch halfway = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
      var moves =
          new java.util.ArrayList<featurecat.lizzie.analysis.MoveData>() {
            @Override
            public featurecat.lizzie.analysis.MoveData get(int index) {
              halfway.countDown();
              try {
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
              } catch (InterruptedException failure) {
                throw new AssertionError(failure);
              }
              return super.get(index);
            }
          };
      var move = new featurecat.lizzie.analysis.MoveData();
      move.coordinate = "C3";
      move.playouts = 200;
      move.winrate = 61;
      moves.add(move);
      var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
      try {
        var update =
            executor.submit(
                () ->
                    data.tryToSetBestMovesWithStatus(
                        moves, "snapshot-test", false, 200, null, true));
        assertTrue(halfway.await(5, java.util.concurrent.TimeUnit.SECONDS));
        java.util.concurrent.CountDownLatch cloning = new java.util.concurrent.CountDownLatch(1);
        var snapshot =
            executor.submit(
                () -> {
                  cloning.countDown();
                  return data.clone();
                });
        assertTrue(cloning.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(snapshot.isDone(), "Partial payload commit must fence a save snapshot");
        release.countDown();
        assertTrue(update.get(5, java.util.concurrent.TimeUnit.SECONDS));
        BoardData copy = snapshot.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(200, copy.getPlayouts());
        assertEquals(200, copy.bestMoves.get(0).playouts);
        assertEquals(61, copy.winrate);
      } finally {
        release.countDown();
        executor.shutdownNow();
      }
    }
  }
}
