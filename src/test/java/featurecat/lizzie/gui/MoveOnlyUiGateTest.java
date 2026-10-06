package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.analysis.MoveRankEvaluationMode;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.EngineFollowController;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class MoveOnlyUiGateTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;
  private static final int CANVAS_SIZE = 120;
  private static final int STONE_RADIUS = 12;
  private static final int SCALED_MARGIN = 20;
  private static final int SQUARE_SIZE = 40;

  @Test
  void analysisFrameShowNextSkipsSnapshotMarkerRows() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board = boardWith(historyWithNext(currentData(), snapshotData(new int[] {2, 2}, 2)));

      AnalysisFrame frame = allocate(AnalysisFrame.class);
      frame.index = 1;
      AbstractTableModel model = frame.getTableModel();

      assertEquals(
          1,
          model.getRowCount(),
          "analysis frame should only treat real MOVE nodes as next-move rows.");
    } finally {
      env.close();
    }
  }

  @Test
  void analysisFrameShowNextKeepsRealMoveRows() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board = boardWith(historyWithNext(currentData(), moveData(new int[] {2, 2}, 2)));

      AnalysisFrame frame = allocate(AnalysisFrame.class);
      frame.index = 1;
      AbstractTableModel model = frame.getTableModel();

      assertEquals(2, model.getRowCount(), "analysis frame should still expose real next moves.");
    } finally {
      env.close();
    }
  }

  @Test
  void lizzieFrameSuggestionTableSkipsSnapshotMarkerRows() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingLizzieFrame frame = configuredFrame();
      Lizzie.frame = frame;
      Lizzie.board = boardWith(historyWithNext(currentData(), snapshotData(new int[] {2, 2}, 2)));

      assertEquals(
          1,
          frame.getTableModel().getRowCount(),
          "main suggestion table should ignore snapshot marker metadata.");
    } finally {
      env.close();
    }
  }

  @Test
  void lizzieFrameSuggestionTableKeepsRealMoveRows() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingLizzieFrame frame = configuredFrame();
      Lizzie.frame = frame;
      Lizzie.board = boardWith(historyWithNext(currentData(), moveData(new int[] {2, 2}, 2)));

      assertEquals(
          2, frame.getTableModel().getRowCount(), "main suggestion table should keep real moves.");
    } finally {
      env.close();
    }
  }

  @Test
  void lizzieFrameMouseHoverIgnoresSnapshotNextMarker() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingLizzieFrame frame = configuredFrame();
      Lizzie.frame = frame;
      Lizzie.board =
          boardWith(historyWithNext(noSuggestionData(), snapshotData(new int[] {1, 1}, 2)));
      LizzieFrame.boardRenderer = new CoordinateBoardRenderer(new int[] {1, 1});

      frame.onMouseMoved(0, 0);

      assertFalse(frame.isMouseOver, "snapshot markers must not activate next-move blunder hover.");
    } finally {
      env.close();
    }
  }

  @Test
  void lizzieFrameMouseHoverKeepsRealNextMove() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingLizzieFrame frame = configuredFrame();
      Lizzie.frame = frame;
      Lizzie.board = boardWith(historyWithNext(noSuggestionData(), moveData(new int[] {1, 1}, 2)));
      LizzieFrame.boardRenderer = new CoordinateBoardRenderer(new int[] {1, 1});

      frame.onMouseMoved(0, 0);

      assertTrue(
          frame.isMouseOver, "real next moves should still activate next-move blunder hover.");
    } finally {
      env.close();
    }
  }

  @Test
  void trialDisplayNodeDoesNotDrawItsOwnMoveAsNextMove() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingLizzieFrame frame = configuredFrame();
      Lizzie.frame = frame;
      BoardHistoryList history =
          historyWithNext(currentData(), moveData(new int[] {1, 1}, 2));
      BoardHistoryNode anchor = history.getStart();
      insertDummyAsFirstVariation(anchor);
      BoardHistoryNode displayNode = anchor.variations.get(1);
      Lizzie.board = boardWith(history);
      frame.setDisplayNodeOverride(displayNode);
      BoardRenderer renderer = configuredBranchRenderer();

      assertFalse(
          hasVisiblePaintNear(renderNextMoveOverlay(renderer), 1, 1),
          "the trial branch's first stone must not be drawn as the display node's future move.");
    } finally {
      env.close();
    }
  }

  @Test
  void trialDisplayNodeDrawsItsRealNextMoveAndIgnoresDummy() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingLizzieFrame frame = configuredFrame();
      Lizzie.frame = frame;
      BoardHistoryList history =
          historyWithNext(currentData(), moveData(new int[] {1, 1}, 2));
      BoardHistoryNode displayNode = history.getStart().variations.get(0);
      MoveData displayCandidate = bestMove(2, 1);
      displayCandidate.order = 1;
      displayNode.getData().bestMoves = new ArrayList<>(List.of(displayCandidate));
      insertDummyAsFirstVariation(displayNode);
      BoardData realNextMove = moveData(new int[] {2, 1}, 3);
      realNextMove.setPlayoutsForce(40);
      realNextMove.bestMoves.get(0).variation = new ArrayList<>();
      displayNode.addAtLast(realNextMove);
      Lizzie.board = boardWith(history);
      frame.setDisplayNodeOverride(displayNode);
      Lizzie.config.showBlackCandidates = true;
      Lizzie.config.showWhiteCandidates = true;
      Lizzie.config.minPlayoutsForNextMove = 30;
      BoardRenderer renderer = configuredBranchRenderer();
      setField(BoardRenderer.class, renderer, "bestMoves", displayNode.getData().bestMoves);
      BufferedImage overlay = renderNextMoveOverlay(renderer);

      assertFalse(
          hasVisiblePaintNear(overlay, 1, 1),
          "the trial display node must not be outlined as its own future move.");
      assertTrue(
          hasVisiblePaintNear(overlay, 2, 1),
          "the trial display node's real next move should keep its outline.");
      assertTrue(
          (boolean) getField(BoardRenderer.class, renderer, "isShowingNextMoveBlunder"),
          "the dummy placeholder must not displace the real next move from first-move handling.");
    } finally {
      env.close();
    }
  }

  @Test
  void trialNextBlunderStoneUsesDisplayNodeTurn() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingLizzieFrame frame = configuredFrame();
      Lizzie.frame = frame;
      BoardHistoryList history =
          historyWithNext(currentData(), moveData(new int[] {1, 1}, 2));
      Lizzie.board = boardWith(history);
      frame.setDisplayNodeOverride(history.getStart().variations.get(0));
      Lizzie.config.usePureStone = true;
      BoardRenderer renderer = configuredBranchRenderer();

      BufferedImage image = renderNextBlunderFirstMove(renderer, 1, 1);
      int centerRgb =
          image.getRGB(SCALED_MARGIN + SQUARE_SIZE, SCALED_MARGIN + SQUARE_SIZE);

      assertEquals(
          Color.BLACK.getRGB(),
          centerRgb,
          "the next-move preview stone should use the trial display node's side to play.");
    } finally {
      env.close();
    }
  }

  @Test
  void currentNodeStillDrawsItsRealNextMove() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingLizzieFrame frame = configuredFrame();
      Lizzie.frame = frame;
      Lizzie.board =
          boardWith(historyWithNext(currentData(), moveData(new int[] {1, 1}, 2)));
      BoardRenderer renderer = configuredBranchRenderer();

      assertTrue(
          hasVisiblePaint(renderNextMoveOverlay(renderer)),
          "the normal current-node path should keep its real next-move outline.");
    } finally {
      env.close();
    }
  }

  @Test
  void suggestionTablePreviewClearsWhenMouseReturnsToBoardCandidate() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingLizzieFrame frame = configuredFrame();
      Lizzie.frame = frame;
      Lizzie.config.showBlackCandidates = true;
      Lizzie.config.showWhiteCandidates = true;
      Lizzie.config.showSuggestionVariations = true;
      Lizzie.board = boardWith(historyForCurrentNode(currentData()));
      LizzieFrame.boardRenderer = new CoordinateBoardRenderer(new int[] {0, 1});
      frame.clickOrder = 0;
      frame.selectedorder = 0;
      frame.currentRow = 0;
      frame.suggestionclick = new int[] {1, 0};
      frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
      frame.isMouseOver = false;

      frame.onMouseMoved(0, 0);

      assertEquals(-1, frame.clickOrder, "board hover should exit table-preview lock.");
      assertEquals(-1, frame.selectedorder, "board hover should clear selected suggestion row.");
      assertEquals(-1, frame.currentRow, "board hover should clear current suggestion row.");
      assertTrue(frame.isMouseOver, "board hover should activate the hovered candidate preview.");
      assertEquals(
          0, frame.mouseOverCoordinate[0], "hovered candidate x should replace old preview.");
      assertEquals(
          1, frame.mouseOverCoordinate[1], "hovered candidate y should replace old preview.");
    } finally {
      env.close();
    }
  }

  @Test
  void clearSuggestionTablePreviewClearsLockedSuggestionState() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingLizzieFrame frame = configuredFrame();
      Lizzie.frame = frame;
      frame.clickOrder = 2;
      frame.selectedorder = 2;
      frame.currentRow = 2;
      frame.suggestionclick = new int[] {1, 1};
      frame.mouseOverCoordinate = new int[] {1, 1};
      frame.isMouseOver = true;

      frame.clearSuggestionTablePreview();

      assertEquals(-1, frame.clickOrder);
      assertEquals(-1, frame.selectedorder);
      assertEquals(-1, frame.currentRow);
      assertSame(LizzieFrame.outOfBoundCoordinate, frame.suggestionclick);
      assertSame(LizzieFrame.outOfBoundCoordinate, frame.mouseOverCoordinate);
      assertFalse(frame.isMouseOver);
    } finally {
      env.close();
    }
  }


  @Test
  void boardRendererKeepsBranchNavigationAtFirstCandidateMove() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config.showBranch = true;
      Lizzie.config.showSuggestionVariations = true;
      Lizzie.config.showBlackCandidates = true;
      Lizzie.config.showWhiteCandidates = true;
      Lizzie.config.usePureStone = true;
      TrackingLizzieFrame frame = configuredFrame();
      frame.priorityMoveCoords = new ArrayList<>();
      frame.mouseOverCoordinate = new int[] {0, 1};
      Lizzie.frame = frame;
      BoardData current = currentData();
      MoveData suggested = current.bestMoves.get(0);
      suggested.variation =
          List.of(
              suggested.coordinate,
              Board.convertCoordinatesToName(1, 1),
              Board.convertCoordinatesToName(2, 2));
      Lizzie.board = boardWith(historyForCurrentNode(current));
      BoardHistoryNode currentNode = Lizzie.board.getHistory().getCurrentHistoryNode();
      BoardRenderer renderer = configuredBranchRenderer();
      LizzieFrame.boardRenderer = renderer;

      invokeDrawBranch(renderer);
      assertTrue(renderer.hasSelectedVariation(), "hovered variation should own navigation.");

      for (int i = 0; i < 4; i++) {
        LizzieFrame.undoNoRefresh(1);
        invokeDrawBranch(renderer);
        assertSame(
            currentNode,
            Lizzie.board.getHistory().getCurrentHistoryNode(),
            "stepping back inside a candidate variation must never undo the real game.");
        assertTrue(
            renderer.hasSelectedVariation(),
            "the variation should keep owning navigation at its first move.");
      }
      assertEquals(1, renderer.getDisplayedBranchLength());

      LizzieFrame.redoNoRefresh(1);
      assertEquals(
          2,
          renderer.getDisplayedBranchLength(),
          "stepping forward from the first variation move should show the second move.");
      invokeDrawBranch(renderer);
      assertTrue(renderer.hasSelectedVariation());
      assertSame(currentNode, Lizzie.board.getHistory().getCurrentHistoryNode());

      frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
      invokeDrawBranch(renderer);
      assertFalse(
          renderer.hasSelectedVariation(),
          "leaving the candidate should hand navigation back to the game record.");

      frame.mouseOverCoordinate = new int[] {0, 1};
      invokeDrawBranch(renderer);
      renderer.clearBranch();
      assertFalse(renderer.hasSelectedVariation(), "clearing the branch drops ownership.");
    } finally {
      env.close();
    }
  }

  @Test
  void primaryWheelKeepsFirstCandidateMoveUntilPreviewCancelled() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BranchRecordFixture fixture = branchRecordFixture();
      fixture.draw();
      for (int i = 0; i < 4; i++) {
        fixture.wheel(-1);
        fixture.draw();
        fixture.assertPreviewRecordUnchanged();
      }
      assertEquals(1, fixture.renderer.getDisplayedBranchLength());

      fixture.wheel(1);
      fixture.draw();
      assertEquals(2, fixture.renderer.getDisplayedBranchLength());
      fixture.assertPreviewRecordUnchanged();

      fixture.frame.clearSuggestionTablePreview();
      fixture.frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
      fixture.draw();
      assertFalse(fixture.renderer.hasSelectedVariation());
      fixture.wheel(1);
      assertSame(fixture.next, Lizzie.board.getHistory().getCurrentHistoryNode());
      fixture.wheel(-1);
      fixture.assertPreviewRecordUnchanged();
    } finally {
      env.close();
    }
  }

  @Test
  void primaryKeysKeepFirstCandidateMoveForArrowAndPageNavigation() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BranchRecordFixture fixture = branchRecordFixture();
      fixture.draw();
      for (int i = 0; i < 6; i++) {
        fixture.key(i < 4 ? KeyEvent.VK_UP : KeyEvent.VK_PAGE_UP);
        fixture.draw();
        fixture.assertPreviewRecordUnchanged();
      }
      assertEquals(1, fixture.renderer.getDisplayedBranchLength());

      fixture.key(KeyEvent.VK_DOWN);
      fixture.draw();
      assertEquals(2, fixture.renderer.getDisplayedBranchLength());
      fixture.assertPreviewRecordUnchanged();
      fixture.key(KeyEvent.VK_UP);
      fixture.draw();
      assertEquals(1, fixture.renderer.getDisplayedBranchLength());
      fixture.key(KeyEvent.VK_PAGE_DOWN);
      fixture.draw();
      assertEquals(2, fixture.renderer.getDisplayedBranchLength());
      fixture.assertPreviewRecordUnchanged();
    } finally {
      env.close();
    }
  }

  @Test
  void independentPageDownAdvancesOwnedPreviewFromFirstMove() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BranchRecordFixture fixture = branchRecordFixture();
      fixture.frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
      IndependentMainBoard independent = allocate(IndependentMainBoard.class);
      independent.boardRenderer = configuredBranchRenderer(true);
      independent.mouseOverCoordinate = new int[] {0, 1};
      fixture.frame.independentMainBoard = independent;
      InputIndependentMainBoard input = new InputIndependentMainBoard();
      invokeDrawBranch(fixture.renderer);
      invokeDrawBranch(independent.boardRenderer);
      assertFalse(fixture.renderer.hasSelectedVariation());
      assertTrue(fixture.frame.isMouseOverIndependMainBoard(0, 1));

      for (int i = 0; i < 4; i++) {
        SwingUtilities.invokeAndWait(() -> input.keyPressed(keyEvent(KeyEvent.VK_UP)));
        invokeDrawBranch(independent.boardRenderer);
        fixture.assertPreviewRecordUnchanged();
        assertTrue(independent.boardRenderer.hasSelectedVariation());
      }
      assertEquals(1, independent.boardRenderer.getDisplayedBranchLength());
      SwingUtilities.invokeAndWait(() -> input.keyPressed(keyEvent(KeyEvent.VK_PAGE_DOWN)));
      invokeDrawBranch(independent.boardRenderer);
      fixture.assertPreviewRecordUnchanged();

      assertEquals(
          4,
          independent.boardRenderer.getDisplayedBranchLength(),
          "independent Page Down must advance to the short PV's existing end sentinel.");
    } finally {
      env.close();
    }
  }

  @Test
  void doubleEnginePageDownAdvancesSecondOwnedPreviewFromFirstMove() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BranchRecordFixture fixture = branchRecordFixture();
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      fixture.node.getData().bestMoves2 = new ArrayList<>(List.of(fixture.candidate));
      fixture.node.getData().bestMoves.clear();
      BoardRenderer second = configuredBranchRenderer();
      second.setOrder(1);
      second.setDisplayedBranchLength(3);
      LizzieFrame.boardRenderer2 = second;
      fixture.draw();
      invokeDrawBranch(second);
      assertFalse(fixture.renderer.hasSelectedVariation());
      assertTrue(second.hasSelectedVariation());

      for (int i = 0; i < 4; i++) {
        fixture.wheel(-1);
        fixture.draw();
        invokeDrawBranch(second);
        fixture.assertPreviewRecordUnchanged();
        assertTrue(second.hasSelectedVariation());
      }
      assertEquals(1, second.getDisplayedBranchLength());
      fixture.key(KeyEvent.VK_PAGE_DOWN);
      fixture.draw();
      invokeDrawBranch(second);
      fixture.assertPreviewRecordUnchanged();

      assertEquals(
          4,
          second.getDisplayedBranchLength(),
          "double-engine Page Down must advance to the short PV's existing end sentinel.");
    } finally {
      env.close();
    }
  }

  @Test
  void longVariationBackstepUsesCapturedPreviewBoundBeforeAndAfterPublication() throws Exception {
    for (boolean published : new boolean[] {false, true}) {
      try (TestEnvironment env = TestEnvironment.open()) {
        BranchRecordFixture fixture = branchRecordFixture();
        fixture.candidate.variation = new ArrayList<>(java.util.Collections.nCopies(250, "pass"));
        fixture.candidate.variation.set(0, fixture.candidate.coordinate);
        fixture.renderer.selectHoveredVariation();
        if (published) fixture.draw();
        assertEquals(199, fixture.renderer.getBranchLength());

        fixture.wheel(-1);
        assertEquals(198, fixture.renderer.getDisplayedBranchLength());
        fixture.draw();
        assertEquals(198, fixture.renderer.branchOpt.orElseThrow().length);
        fixture.assertPreviewRecordUnchanged();
      }
    }
  }

  @Test
  void queuedPreviewCannotPublishDuringScoreModeAndReselectsAfterExit() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BranchRecordFixture fixture = branchRecordFixture();
      fixture.renderer.selectHoveredVariation();
      VariationPreviewState preview =
          (VariationPreviewState) getField(BoardRenderer.class, fixture.renderer, "preview");
      VariationPreviewScheduler scheduler =
          (VariationPreviewScheduler) getField(VariationPreviewState.class, preview, "scheduler");
      ControlledPreview worker =
          (ControlledPreview) getField(VariationPreviewScheduler.class, scheduler, "worker");
      assertTrue(fixture.renderer.hasSelectedVariation());
      assertFalse(fixture.renderer.isShowingBranch());

      fixture.frame.isInScoreMode = true;
      worker.finish();
      assertFalse(fixture.renderer.hasSelectedVariation());
      assertFalse(fixture.renderer.branchOpt.isPresent());
      fixture.renderer.selectHoveredVariation();
      assertFalse(fixture.renderer.hasSelectedVariation());

      fixture.frame.isInScoreMode = false;
      fixture.renderer.selectHoveredVariation();
      fixture.draw();
      assertTrue(fixture.renderer.isShowingBranch());
      assertEquals(fixture.candidate.variation.size(), fixture.renderer.branchOpt.orElseThrow().length);
      fixture.assertPreviewRecordUnchanged();
    }
  }

  @Test
  void doubleEngineArrowKeysNavigateSecondOwnedPreviewAndPreserveHistory() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BranchRecordFixture fixture = branchRecordFixture();
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      fixture.node.getData().bestMoves2 = new ArrayList<>(List.of(fixture.candidate));
      fixture.node.getData().bestMoves.clear();
      BoardRenderer second = configuredBranchRenderer();
      second.setOrder(1);
      LizzieFrame.boardRenderer2 = second;
      fixture.draw();
      second.selectHoveredVariation();
      assertFalse(fixture.renderer.hasSelectedVariation());
      assertTrue(second.hasSelectedVariation());

      // Secondary-only pending Down advances preview to move 2 without mutating real game history.
      second.startNormalBoard();
      second.selectHoveredVariation();
      assertFalse(second.isShowingBranch());
      assertFalse(fixture.renderer.hasSelectedVariation());
      assertTrue(second.hasSelectedVariation());
      fixture.key(KeyEvent.VK_DOWN);
      invokeDrawBranch(second);
      assertEquals(2, second.getDisplayedBranchLength());
      assertTrue(second.hasSelectedVariation());
      fixture.assertPreviewRecordUnchanged();

      // Secondary-only pending Up shows full preview without mutating real game history.
      second.clearBranch();
      second.startNormalBoard();
      second.selectHoveredVariation();
      assertFalse(second.isShowingBranch());
      assertTrue(second.hasSelectedVariation());
      fixture.key(KeyEvent.VK_UP);
      invokeDrawBranch(second);
      assertEquals(fixture.candidate.variation.size(), second.getDisplayedBranchLength());
      assertTrue(second.hasSelectedVariation());
      fixture.assertPreviewRecordUnchanged();

      // Visible variation stepping with Up and Down.
      fixture.key(KeyEvent.VK_UP);
      invokeDrawBranch(second);
      assertEquals(2, second.getDisplayedBranchLength());
      assertTrue(second.hasSelectedVariation());
      fixture.assertPreviewRecordUnchanged();

      fixture.key(KeyEvent.VK_DOWN);
      invokeDrawBranch(second);
      assertEquals(3, second.getDisplayedBranchLength());
      assertTrue(second.hasSelectedVariation());
      fixture.assertPreviewRecordUnchanged();

      // Stepping back to length 1 (first move).
      fixture.key(KeyEvent.VK_UP);
      invokeDrawBranch(second);
      assertEquals(2, second.getDisplayedBranchLength());
      fixture.assertPreviewRecordUnchanged();

      fixture.key(KeyEvent.VK_UP);
      invokeDrawBranch(second);
      assertEquals(1, second.getDisplayedBranchLength());
      assertTrue(second.hasSelectedVariation());
      fixture.assertPreviewRecordUnchanged();

      // Repeated Up at length 1 retains length 1 and candidate ownership without history mutation.
      fixture.key(KeyEvent.VK_UP);
      invokeDrawBranch(second);
      assertEquals(1, second.getDisplayedBranchLength());
      assertTrue(second.hasSelectedVariation());
      fixture.assertPreviewRecordUnchanged();

      // Down from length 1 advances to length 2.
      fixture.key(KeyEvent.VK_DOWN);
      invokeDrawBranch(second);
      assertEquals(2, second.getDisplayedBranchLength());
      assertTrue(second.hasSelectedVariation());
      fixture.assertPreviewRecordUnchanged();

      // When the mouse leaves the candidate, ordinary keys navigate history.
      fixture.frame.clearSuggestionTablePreview();
      fixture.frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
      fixture.draw();
      invokeDrawBranch(second);
      assertFalse(fixture.renderer.hasSelectedVariation());
      assertFalse(second.hasSelectedVariation());

      fixture.key(KeyEvent.VK_DOWN);
      assertSame(
          fixture.next,
          Lizzie.board.getHistory().getCurrentHistoryNode(),
          "leaving the candidate should hand forward navigation back to the game record.");
      assertEquals(3, Lizzie.board.getData().moveNumber);

      fixture.key(KeyEvent.VK_UP);
      assertSame(
          fixture.node,
          Lizzie.board.getHistory().getCurrentHistoryNode(),
          "leaving the candidate should hand backward navigation back to the game record.");
      assertEquals(2, Lizzie.board.getData().moveNumber);

      fixture.key(KeyEvent.VK_UP);
      assertSame(
          fixture.previous,
          Lizzie.board.getHistory().getCurrentHistoryNode(),
          "backward navigation should continue through the game record.");
      assertEquals(1, Lizzie.board.getData().moveNumber);
    } finally {
      env.close();
    }
  }

  @Test
  void noRefreshKeepsFrozenPvWhenWheelCrossesFirstCandidateMove() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BranchRecordFixture fixture = branchRecordFixture();
      Lizzie.config.noRefreshOnMouseMove = true;
      fixture.frame.isMouseOver = true;
      List<String> originalPv = new ArrayList<>(fixture.candidate.variation);
      fixture.draw();
      fixture.reachFirstMove();
      fixture.candidate.variation.set(1, Board.convertCoordinatesToName(2, 1));
      fixture.candidate.variation.set(2, Board.convertCoordinatesToName(1, 2));

      fixture.wheel(1);
      fixture.draw();
      fixture.assertPreviewRecordUnchanged();

      assertIterableEquals(
          originalPv,
          fixture.renderer.selectedVariation().orElseThrow(),
          "no-refresh preview must retain PV A after stepping forward from its first move.");
    } finally {
      env.close();
    }
  }

  @Test
  void autoReplayWheelKeepsFirstCandidateMoveInsidePreview() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BranchRecordFixture fixture = branchRecordFixture();
      Lizzie.config.autoReplayBranch = true;
      fixture.draw();
      fixture.reachFirstMove();
      assertTrue(fixture.renderer.hasSelectedVariation());
    } finally {
      env.close();
    }
  }

  @Test
  void numericCandidateSelectionKeepsWheelInsideFirstPreviewMove() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BranchRecordFixture fixture = branchRecordFixture();
      fixture.frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
      setField(LizzieFrame.class, fixture.frame, "curSuggestionMoveOrderByNumber", -1);

      fixture.key(KeyEvent.VK_1);
      fixture.draw();

      assertArrayEquals(new int[] {0, 1}, fixture.frame.mouseOverCoordinate);
      assertTrue(fixture.renderer.hasSelectedVariation());
      fixture.reachFirstMove();
    } finally {
      env.close();
    }
  }

  @Test
  void positionChangeReleasesFirstMovePreviewOwnership() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BranchRecordFixture fixture = branchRecordFixture();
      fixture.draw();
      fixture.reachFirstMove();

      Lizzie.board.getHistory().next();
      fixture.draw();

      assertSame(fixture.next, Lizzie.board.getHistory().getCurrentHistoryNode());
      assertFalse(
          fixture.renderer.hasSelectedVariation(),
          "a new position must not inherit the previous node's first-move preview ownership.");
      fixture.assertRecordStructureUnchanged();
    } finally {
      env.close();
    }
  }

  @Test
  void noRefreshPositionChangeDropsFirstMoveFrozenPvForSameCoordinate() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BranchRecordFixture fixture = branchRecordFixture();
      Lizzie.config.noRefreshOnMouseMove = true;
      fixture.frame.isMouseOver = true;
      MoveData nextCandidate = bestMove(0, 1);
      nextCandidate.variation =
          List.of(
              nextCandidate.coordinate,
              Board.convertCoordinatesToName(1, 2),
              Board.convertCoordinatesToName(2, 1));
      fixture.next.getData().bestMoves = new ArrayList<>(List.of(nextCandidate));
      fixture.draw();
      fixture.reachFirstMove();

      Lizzie.board.getHistory().next();
      fixture.draw();

      assertSame(fixture.next, Lizzie.board.getHistory().getCurrentHistoryNode());
      assertIterableEquals(
          nextCandidate.variation,
          fixture.renderer.selectedVariation().orElseThrow(),
          "a new position must not reuse the previous node's frozen first-move PV.");
    } finally {
      env.close();
    }
  }

  @Test
  void explicitStepRejectsOldReplayAdvancementAndRestoration() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BranchRecordFixture fixture = branchRecordFixture();
      fixture.draw();
      int originalLength = fixture.renderer.getDisplayedBranchLength();
      long replay = fixture.renderer.replayTarget();
      assertTrue(fixture.renderer.setReplayLength(replay, 3));

      fixture.renderer.setDisplayedBranchLength(2);

      assertFalse(fixture.renderer.setReplayLength(replay, 4));
      assertFalse(fixture.renderer.setReplayLength(replay, originalLength));
      assertEquals(2, fixture.renderer.getDisplayedBranchLength());
      long freshReplay = fixture.renderer.replayTarget();
      assertTrue(fixture.renderer.setReplayLength(freshReplay, 3));
      assertTrue(fixture.renderer.setReplayLength(freshReplay, 2));
      assertEquals(2, fixture.renderer.getDisplayedBranchLength());
      fixture.assertRecordStructureUnchanged();
    } finally {
      env.close();
    }
  }

  @Test
  void differentCandidateTakesOwnershipAfterFirstMovePreview() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BranchRecordFixture fixture = branchRecordFixture();
      MoveData other = bestMove(1, 0);
      other.variation =
          List.of(
              other.coordinate,
              Board.convertCoordinatesToName(1, 2),
              Board.convertCoordinatesToName(2, 1));
      fixture.node.getData().bestMoves.add(other);
      fixture.draw();
      fixture.reachFirstMove();

      fixture.frame.mouseOverCoordinate = new int[] {1, 0};
      fixture.draw();

      assertIterableEquals(
          other.variation, fixture.renderer.selectedVariation().orElseThrow());
      assertTrue(fixture.renderer.hasSelectedVariation());
      fixture.wheel(-1);
      fixture.draw();
      fixture.assertPreviewRecordUnchanged();
      assertTrue(fixture.renderer.hasSelectedVariation());
    } finally {
      env.close();
    }
  }

  @Test
  void cancelledFirstMovePreviewReturnsWheelToPreviousRecordNode() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BranchRecordFixture fixture = branchRecordFixture();
      fixture.draw();
      fixture.reachFirstMove();

      fixture.frame.clearSuggestionTablePreview();

      assertFalse(fixture.renderer.hasSelectedVariation());
      fixture.wheel(-1);
      assertSame(
          fixture.previous,
          Lizzie.board.getHistory().getCurrentHistoryNode(),
          "cancelling the first-move preview must return wheel-up to real history navigation.");
      fixture.assertRecordStructureUnchanged();
    } finally {
      env.close();
    }
  }

  @Test
  void engineAnalysisRefreshSelectsIncrementalBoardAndWinratePainting() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingLizzieFrame frame = configuredFrame();
      Lizzie.frame = frame;

      frame.refresh(1);

      assertTrue((boolean) getField(LizzieFrame.class, frame, "redrawBoardSurfacesOnly"));
      assertTrue((boolean) getField(LizzieFrame.class, frame, "redrawWinratePaneOnly"));
      assertEquals(
          0,
          frame.fullRefreshes,
          "engine output must not route through the full-frame refresh used for layout changes.");
    } finally {
      env.close();
    }
  }

  @Test
  void committedMoveDefersFullUiMaintenanceUntilAfterImmediateBoardRepaint() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingLizzieFrame frame = configuredFrame();
      Lizzie.frame = frame;

      javax.swing.SwingUtilities.invokeAndWait(frame::refreshAfterMove);

      assertTrue((boolean) getField(LizzieFrame.class, frame, "redrawBoardSurfacesOnly"));
      assertEquals(
          0,
          frame.fullRefreshes,
          "comments and layout work must not run in the move's input event.");

      Thread.sleep(260L);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertEquals(1, frame.fullRefreshes, "secondary move UI should still refresh after input.");
    } finally {
      env.close();
    }
  }

  @Test
  void boardClickClearsSettledSuggestionBeforeMoveRendering() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingLizzieFrame frame = configuredFrame();
      Lizzie.frame = frame;
      frame.isMouseOver = true;
      frame.mouseOverCoordinate = new int[] {0, 1};
      frame.suggestionclick = new int[] {0, 1};

      frame.clearSuggestionPreviewBeforeBoardClick();

      assertFalse(frame.isMouseOver);
      assertSame(LizzieFrame.outOfBoundCoordinate, frame.mouseOverCoordinate);
      assertSame(LizzieFrame.outOfBoundCoordinate, frame.suggestionclick);
      assertEquals(
          1,
          frame.clearedMovePreviews,
          "the visible PV must be cleared before the clicked stone is rendered.");
    } finally {
      env.close();
    }
  }


  @Test
  void boardRendererRedrawsBranchImagesAfterClearingSameHover() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config.showBranch = true;
      Lizzie.config.showSuggestionVariations = true;
      Lizzie.config.showBlackCandidates = true;
      Lizzie.config.showWhiteCandidates = true;
      Lizzie.config.noRefreshOnMouseMove = true;
      Lizzie.config.usePureStone = true;
      TrackingLizzieFrame frame = configuredFrame();
      frame.mouseOverCoordinate = new int[] {0, 1};
      frame.isMouseOver = true;
      frame.priorityMoveCoords = new ArrayList<>();
      Lizzie.frame = frame;
      BoardData current = currentData();
      MoveData suggested = current.bestMoves.get(0);
      suggested.variation = List.of(suggested.coordinate, Board.convertCoordinatesToName(1, 1));
      Lizzie.board = boardWith(historyForCurrentNode(current));
      BoardRenderer renderer = configuredBranchRenderer();

      renderer.selectHoveredVariation();
      invokeDrawBranch(renderer);
      assertIterableEquals(suggested.variation, renderer.selectedVariation().orElseThrow());
      assertTrue(hasVisiblePaintNear(renderBranchOverlay(renderer), 1, 1));

      renderer.clearBranch();
      assertFalse(renderer.hasSelectedVariation());
      assertFalse(hasVisiblePaintNear(renderBranchOverlay(renderer), 1, 1));
      frame.mouseOverCoordinate = new int[] {0, 1};
      frame.isMouseOver = true;
      renderer.selectHoveredVariation();
      invokeDrawBranch(renderer);

      assertIterableEquals(suggested.variation, renderer.selectedVariation().orElseThrow());
      assertTrue(
          hasVisiblePaintNear(renderBranchOverlay(renderer), 1, 1),
          "returning to the cleared candidate must show its second stone again.");
    } finally {
      env.close();
    }
  }

  @Test
  void boardRendererNoRefreshFreezesHoveredVariationSnapshot() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config.showBranch = true;
      Lizzie.config.showSuggestionVariations = true;
      Lizzie.config.showBlackCandidates = true;
      Lizzie.config.showWhiteCandidates = true;
      Lizzie.config.noRefreshOnMouseMove = true;
      Lizzie.config.usePureStone = true;
      TrackingLizzieFrame frame = configuredFrame();
      frame.mouseOverCoordinate = new int[] {0, 1};
      frame.isMouseOver = true;
      frame.priorityMoveCoords = new ArrayList<>();
      Lizzie.frame = frame;
      BoardData current = currentData();
      MoveData suggested = current.bestMoves.get(0);
      List<String> firstPv =
          new ArrayList<>(List.of(suggested.coordinate, Board.convertCoordinatesToName(1, 1)));
      suggested.variation = firstPv;
      Lizzie.board = boardWith(historyForCurrentNode(current));
      BoardRenderer renderer = configuredBranchRenderer();

      renderer.selectHoveredVariation();
      invokeDrawBranch(renderer);

      List<String> firstPreview = renderer.selectedVariation().orElseThrow();
      assertIterableEquals(firstPv, firstPreview);
      assertTrue(hasVisiblePaintNear(renderBranchOverlay(renderer), 1, 1));

      firstPv.set(1, Board.convertCoordinatesToName(2, 2));
      firstPv.add(Board.convertCoordinatesToName(1, 2));
      suggested.variation = new ArrayList<>(firstPv);
      invokeDrawBranch(renderer);

      assertIterableEquals(
          List.of(suggested.coordinate, Board.convertCoordinatesToName(1, 1)),
          renderer.selectedVariation().orElseThrow(),
          "same hovered move should not refresh when no-refresh-on-mouse-move is enabled.");
      BufferedImage frozen = renderBranchOverlay(renderer);
      assertTrue(hasVisiblePaintNear(frozen, 1, 1));
      assertFalse(hasVisiblePaintNear(frozen, 2, 2));
      assertFalse(hasVisiblePaintNear(frozen, 1, 2));

      renderer.refreshVariation();
      assertIterableEquals(firstPv, renderer.selectedVariation().orElseThrow());
      invokeDrawBranch(renderer);
      BufferedImage refreshed = renderBranchOverlay(renderer);
      assertFalse(hasVisiblePaintNear(refreshed, 1, 1));
      assertTrue(hasVisiblePaintNear(refreshed, 2, 2));
      assertTrue(hasVisiblePaintNear(refreshed, 1, 2));
    } finally {
      env.close();
    }
  }

  @Test
  void boardRendererRefreshesHoveredVariationWhenNoRefreshDisabled() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config.showBranch = true;
      Lizzie.config.showSuggestionVariations = true;
      Lizzie.config.showBlackCandidates = true;
      Lizzie.config.showWhiteCandidates = true;
      Lizzie.config.noRefreshOnMouseMove = false;
      Lizzie.config.usePureStone = true;
      TrackingLizzieFrame frame = configuredFrame();
      frame.mouseOverCoordinate = new int[] {0, 1};
      frame.isMouseOver = true;
      frame.priorityMoveCoords = new ArrayList<>();
      Lizzie.frame = frame;
      BoardData current = currentData();
      MoveData suggested = current.bestMoves.get(0);
      suggested.variation =
          new ArrayList<>(List.of(suggested.coordinate, Board.convertCoordinatesToName(1, 1)));
      Lizzie.board = boardWith(historyForCurrentNode(current));
      BoardRenderer renderer = configuredBranchRenderer();

      renderer.selectHoveredVariation();
      invokeDrawBranch(renderer);
      assertTrue(hasVisiblePaintNear(renderBranchOverlay(renderer), 1, 1));

      suggested.variation =
          new ArrayList<>(
              List.of(
                  suggested.coordinate,
                  Board.convertCoordinatesToName(2, 2),
                  Board.convertCoordinatesToName(1, 2)));
      invokeDrawBranch(renderer);

      assertIterableEquals(
          suggested.variation,
          renderer.selectedVariation().orElseThrow(),
          "hover variation should keep refreshing when no-refresh-on-mouse-move is disabled.");
      BufferedImage refreshed = renderBranchOverlay(renderer);
      assertFalse(hasVisiblePaintNear(refreshed, 1, 1));
      assertTrue(hasVisiblePaintNear(refreshed, 2, 2));
      assertTrue(hasVisiblePaintNear(refreshed, 1, 2));
    } finally {
      env.close();
    }
  }

  @Test
  void pendingHoverAndNumericSelectionShareLengthWithoutNavigatingHistory() throws Exception {
    try (TestEnvironment env = TestEnvironment.open();
        PendingInputEnvironment pending = PendingInputEnvironment.open()) {
      BoardRenderer renderer = LizzieFrame.boardRenderer;
      TrackingLizzieFrame frame = (TrackingLizzieFrame) Lizzie.frame;
      Input input = new Input();
      BoardHistoryNode node = Lizzie.board.getHistory().getCurrentHistoryNode();
      frame.mouseOverCoordinate = new int[] {2, 0};
      frame.isMouseOver = true;
      renderer.setDisplayedBranchLength(2);
      renderer.selectHoveredVariation();
      List<String> hovered = renderer.selectedVariation().orElseThrow();
      assertIterableEquals(pending.selectedPv, hovered);
      assertFalse(renderer.isShowingBranch(), "selection must precede image generation.");

      input.keyPressed(key(KeyEvent.VK_UP));
      assertPendingLength(renderer, node, 1);
      input.keyPressed(key(KeyEvent.VK_DOWN));
      assertPendingLength(renderer, node, 2);
      input.mouseWheelMoved(wheel(1, 1));
      assertPendingLength(renderer, node, 3);
      input.mouseWheelMoved(wheel(-1, 2));
      assertPendingLength(renderer, node, 2);

      frame.cancelPendingSuggestionHoverPreview();
      renderer.clearBranch();
      input.keyPressed(key(KeyEvent.VK_2));
      assertIterableEquals(hovered, renderer.selectedVariation().orElseThrow());
      input.keyPressed(key(KeyEvent.VK_DOWN));
      assertPendingLength(renderer, node, 3);
    }
  }

  @Test
  void commaAppliesPendingNonFirstVariationPrefixAfterEngineReplacement() throws Exception {
    try (TestEnvironment env = TestEnvironment.open();
        PendingInputEnvironment pending = PendingInputEnvironment.open()) {
      Input input = new Input();
      BoardRenderer renderer = LizzieFrame.boardRenderer;
      renderer.setDisplayedBranchLength(2);
      input.keyPressed(key(KeyEvent.VK_2));
      assertFalse(renderer.isShowingBranch());
      pending.replaceEnginePv();

      input.keyPressed(key(KeyEvent.VK_COMMA));

      assertAppliedSelectedPrefix();
    }
  }

  @Test
  void middlePressReleaseConsumesSamePendingSnapshotAfterEngineReplacement() throws Exception {
    try (TestEnvironment env = TestEnvironment.open();
        PendingInputEnvironment pending = PendingInputEnvironment.open()) {
      Input input = new Input();
      BoardRenderer renderer = LizzieFrame.boardRenderer;
      renderer.setDisplayedBranchLength(2);
      input.keyPressed(key(KeyEvent.VK_2));
      BoardHistoryNode node = Lizzie.board.getHistory().getCurrentHistoryNode();

      input.mousePressed(middle(MouseEvent.MOUSE_PRESSED));
      assertPendingLength(renderer, node, 2);
      assertIterableEquals(pending.selectedPv, renderer.selectedVariation().orElseThrow());
      pending.replaceEnginePv();
      input.mouseReleased(middle(MouseEvent.MOUSE_RELEASED));

      assertAppliedSelectedPrefix();
    }
  }

  @Test
  void independentPendingKeysAndCommaUseOwnSelectionNotMainSelection() throws Exception {
    try (TestEnvironment env = TestEnvironment.open();
        PendingInputEnvironment pending = PendingInputEnvironment.open()) {
      TrackingLizzieFrame frame = (TrackingLizzieFrame) Lizzie.frame;
      frame.mouseOverCoordinate = new int[] {0, 1};
      frame.isMouseOver = true;
      LizzieFrame.boardRenderer.selectHoveredVariation();
      TrackingIndependentMainBoard independent = allocate(TrackingIndependentMainBoard.class);
      independent.boardRenderer = configuredBranchRenderer(true);
      independent.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
      frame.independentMainBoard = independent;
      independent.boardRenderer.setDisplayedBranchLength(2);
      independent.setMouseOverCoords(1);
      InputIndependentMainBoard input = new InputIndependentMainBoard();
      BoardHistoryNode node = Lizzie.board.getHistory().getCurrentHistoryNode();

      input.keyPressed(key(KeyEvent.VK_UP));
      assertPendingLength(independent.boardRenderer, node, 1);
      input.keyPressed(key(KeyEvent.VK_DOWN));
      assertPendingLength(independent.boardRenderer, node, 2);
      assertIterableEquals(pending.selectedPv, independent.boardRenderer.selectedVariation().orElseThrow());
      assertIterableEquals(
          Lizzie.board.getData().bestMoves.get(0).variation,
          LizzieFrame.boardRenderer.selectedVariation().orElseThrow());
      pending.replaceEnginePv();
      input.keyPressed(key(KeyEvent.VK_COMMA));

      assertAppliedSelectedPrefix();
    }
  }

  @Test
  void emptyHostKeysAndWheelNavigateHistoryInsteadOfOppositeSelection() throws Exception {
    for (boolean independentHost : new boolean[] {false, true}) {
      for (boolean useWheel : new boolean[] {false, true}) {
        for (int direction : new int[] {-1, 1}) {
          try (TestEnvironment env = TestEnvironment.open();
              PendingInputEnvironment pending = PendingInputEnvironment.open()) {
            TrackingIndependentMainBoard independent = configuredPendingIndependent();
            BoardRenderer opposite = independentHost ? LizzieFrame.boardRenderer : independent.boardRenderer;
            opposite.setDisplayedBranchLength(2);
            if (independentHost) new Input().keyPressed(key(KeyEvent.VK_2));
            else independent.setMouseOverCoords(1);
            BoardHistoryNode node = Lizzie.board.getHistory().getCurrentHistoryNode();
            BoardHistoryNode target = direction < 0 ? node.previous().orElseThrow() : node.next().orElseThrow();
            Input mainInput = new Input();
            Runnable navigate = () -> {
              if (useWheel) {
                if (independentHost) independent.processMouseWheelMoved(wheel(direction, 1));
                else mainInput.mouseWheelMoved(wheel(direction, 1));
              } else {
                pendingKey(independentHost, direction < 0 ? KeyEvent.VK_UP : KeyEvent.VK_DOWN);
              }
            };

            Lizzie.frame.isPlayingAgainstLeelaz = true;
            SwingUtilities.invokeAndWait(navigate);
            assertSame(node, Lizzie.board.getHistory().getCurrentHistoryNode());
            assertEquals(2, opposite.getDisplayedBranchLength());
            assertIterableEquals(pending.selectedPv, opposite.selectedVariation().orElseThrow());
            Lizzie.frame.isPlayingAgainstLeelaz = false;
            SwingUtilities.invokeAndWait(() -> {
              if (useWheel && !independentHost) mainInput.mouseWheelMoved(wheel(direction, 2));
              else navigate.run();
            });

            assertSame(target, Lizzie.board.getHistory().getCurrentHistoryNode());
            assertEquals(direction < 0 ? 0 : 2, Lizzie.board.getData().moveNumber);
            assertFalse(opposite.hasSelectedVariation(), "real history navigation retires the old source.");
          }
        }
      }
    }
  }

  @Test
  void independentPageKeysRetainMainRendererRoute() throws Exception {
    try (TestEnvironment env = TestEnvironment.open();
        PendingInputEnvironment pending = PendingInputEnvironment.open()) {
      TrackingIndependentMainBoard independent = configuredPendingIndependent();
      BoardRenderer main = LizzieFrame.boardRenderer;
      main.setDisplayedBranchLength(2);
      new Input().keyPressed(key(KeyEvent.VK_2));
      independent.boardRenderer.setDisplayedBranchLength(2);
      independent.setMouseOverCoords(0);
      BoardHistoryNode node = Lizzie.board.getHistory().getCurrentHistoryNode();

      pendingKey(true, KeyEvent.VK_PAGE_DOWN);
      assertPendingLength(main, node, 3);
      assertPendingLength(independent.boardRenderer, node, 2);
      pendingKey(true, KeyEvent.VK_PAGE_UP);
      assertPendingLength(main, node, 2);
      assertPendingLength(independent.boardRenderer, node, 2);
    }
  }

  @Test
  void middleGestureKeepsPressedSelectionAfterNumericReplacement() throws Exception {
    for (boolean independentHost : new boolean[] {false, true}) {
      try (TestEnvironment env = TestEnvironment.open();
          PendingInputEnvironment pending = PendingInputEnvironment.open()) {
        BoardRenderer owner = pendingHostRenderer(independentHost);
        owner.setDisplayedBranchLength(2);
        pendingKey(independentHost, KeyEvent.VK_2);
        pendingMiddle(independentHost, true);

        pendingKey(independentHost, KeyEvent.VK_1);
        assertIterableEquals(Lizzie.board.getData().bestMoves.get(0).variation,
            owner.selectedVariation().orElseThrow());
        pendingMiddle(independentHost, false);

        assertAppliedSelectedPrefix();
        pendingMiddle(independentHost, false);
        assertAppliedSelectedPrefix();
      }
    }
  }

  @Test
  void middleGestureKeepsPressedPrefixWhileCurrentSelectionSteps() throws Exception {
    for (boolean independentHost : new boolean[] {false, true}) {
      try (TestEnvironment env = TestEnvironment.open();
          PendingInputEnvironment pending = PendingInputEnvironment.open()) {
        BoardRenderer owner = pendingHostRenderer(independentHost);
        owner.setDisplayedBranchLength(2);
        pendingKey(independentHost, KeyEvent.VK_2);
        pendingMiddle(independentHost, true);
        BoardHistoryNode node = Lizzie.board.getHistory().getCurrentHistoryNode();

        pendingKey(independentHost, KeyEvent.VK_DOWN);
        assertPendingLength(owner, node, 3);
        pendingMiddle(independentHost, false);

        assertAppliedSelectedPrefix();
      }
    }
  }

  @Test
  void ordinaryClearCancelsMiddleGestureEvenAfterAnotherSelection() throws Exception {
    for (boolean independentHost : new boolean[] {false, true}) {
      try (TestEnvironment env = TestEnvironment.open();
          PendingInputEnvironment pending = PendingInputEnvironment.open()) {
        BoardRenderer owner = pendingHostRenderer(independentHost);
        owner.setDisplayedBranchLength(2);
        pendingKey(independentHost, KeyEvent.VK_2);
        pendingMiddle(independentHost, true);
        BoardHistoryNode node = Lizzie.board.getHistory().getCurrentHistoryNode();

        owner.clearBranch();
        pendingKey(independentHost, KeyEvent.VK_1);
        pendingMiddle(independentHost, false);

        assertPendingLength(owner, node, 2);
        assertEquals(Stone.EMPTY, Lizzie.board.getStones()[Board.getIndex(2, 0)]);
        assertEquals(Stone.EMPTY, Lizzie.board.getStones()[Board.getIndex(0, 1)]);
        pendingKey(independentHost, KeyEvent.VK_COMMA);
        assertEquals(3, Lizzie.board.getData().moveNumber);
        assertEquals(Stone.WHITE, Lizzie.board.getStones()[Board.getIndex(0, 1)]);
        assertEquals(Stone.BLACK, Lizzie.board.getStones()[Board.getIndex(0, 2)]);
        assertEquals(Stone.EMPTY, Lizzie.board.getStones()[Board.getIndex(2, 0)]);
      }
    }
  }

  private static TrackingIndependentMainBoard configuredPendingIndependent() throws Exception {
    TrackingIndependentMainBoard independent = allocate(TrackingIndependentMainBoard.class);
    independent.boardRenderer = configuredBranchRenderer(true);
    independent.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
    setField(IndependentMainBoard.class, independent, "curSuggestionMoveOrderByNumber", -1);
    Lizzie.frame.independentMainBoard = independent;
    return independent;
  }

  private static BoardRenderer pendingHostRenderer(boolean independentHost) throws Exception {
    return independentHost ? configuredPendingIndependent().boardRenderer : LizzieFrame.boardRenderer;
  }

  private static void pendingKey(boolean independentHost, int code) {
    if (independentHost) new InputIndependentMainBoard().keyPressed(key(code));
    else new Input().keyPressed(key(code));
  }

  private static void pendingMiddle(boolean independentHost, boolean press) {
    if (independentHost) {
      BoardRenderer owner = Lizzie.frame.independentMainBoard.boardRenderer;
      if (press) owner.beginMiddlePreview();
      else Lizzie.frame.playMiddleVariation(owner);
    } else if (press) new Input().mousePressed(middle(MouseEvent.MOUSE_PRESSED));
    else new Input().mouseReleased(middle(MouseEvent.MOUSE_RELEASED));
  }

  private static KeyEvent key(int code) {
    return new KeyEvent(Lizzie.frame.mainPanel, KeyEvent.KEY_PRESSED, 1, 0, code, KeyEvent.CHAR_UNDEFINED);
  }

  private static MouseWheelEvent wheel(int rotation, long when) {
    return new MouseWheelEvent(
        Lizzie.frame.mainPanel, MouseEvent.MOUSE_WHEEL, when, 0, 60, 60, 0, false,
        MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, rotation);
  }

  private static MouseEvent middle(int id) {
    return new MouseEvent(Lizzie.frame.mainPanel, id, 1, 0, 60, 60, 1, false, MouseEvent.BUTTON2);
  }

  private static void assertPendingLength(BoardRenderer renderer, BoardHistoryNode node, int length) {
    assertEquals(length, renderer.getDisplayedBranchLength());
    assertSame(node, Lizzie.board.getHistory().getCurrentHistoryNode());
    assertEquals(1, Lizzie.board.getData().moveNumber);
    assertFalse(renderer.isShowingBranch(), "input must work before any preview has been painted.");
  }

  private static void assertAppliedSelectedPrefix() {
    assertEquals(3, Lizzie.board.getData().moveNumber);
    assertEquals(Stone.WHITE, Lizzie.board.getStones()[Board.getIndex(2, 0)]);
    assertEquals(Stone.BLACK, Lizzie.board.getStones()[Board.getIndex(2, 1)]);
    assertEquals(Stone.EMPTY, Lizzie.board.getStones()[Board.getIndex(1, 2)]);
    assertEquals(Stone.EMPTY, Lizzie.board.getStones()[Board.getIndex(0, 1)]);
    assertEquals(Stone.EMPTY, Lizzie.board.getStones()[Board.getIndex(1, 1)]);
    assertEquals(Stone.BLACK, Lizzie.board.getStones()[Board.getIndex(0, 0)]);
  }

  @Test
  void independentMainBoardBlunderHoverIgnoresSnapshotMarker() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board =
          boardWith(historyWithNext(noSuggestionData(), snapshotData(new int[] {1, 1}, 2)));
      IndependentMainBoard board = allocate(IndependentMainBoard.class);

      assertFalse(
          invokeIndependentMainBoardHoverGate(board, new int[] {1, 1}),
          "independent main board should ignore snapshot marker metadata.");
    } finally {
      env.close();
    }
  }

  @Test
  void independentMainBoardBlunderHoverKeepsRealMove() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board = boardWith(historyWithNext(noSuggestionData(), moveData(new int[] {1, 1}, 2)));
      IndependentMainBoard board = allocate(IndependentMainBoard.class);

      assertTrue(
          invokeIndependentMainBoardHoverGate(board, new int[] {1, 1}),
          "independent main board should still accept real next moves.");
    } finally {
      env.close();
    }
  }

  @Test
  void floatBoardMoveRankMarkIgnoresSnapshotMarker() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board = boardWith(historyForCurrentNode(snapshotData(new int[] {1, 1}, 2)));
      FloatBoardRenderer renderer = configuredFloatRenderer();

      assertFalse(
          hasVisiblePaint(renderMoveRankMark(renderer)),
          "float board move-rank marks should ignore snapshot markers.");
    } finally {
      env.close();
    }
  }

  @Test
  void floatBoardMoveRankMarkKeepsRealMove() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board = boardWith(historyForCurrentNode(moveData(new int[] {1, 1}, 2)));
      FloatBoardRenderer renderer = configuredFloatRenderer();

      assertTrue(
          hasVisiblePaint(renderMoveRankMark(renderer)),
          "float board move-rank marks should still render for real moves.");
    } finally {
      env.close();
    }
  }

  @Test
  void floatBoardMoveRankMarkColorsUseContinuousSeverityShades() throws Exception {
    Config previousConfig = Lizzie.config;
    Config config = allocate(Config.class);
    config.winLossThreshold1 = -1;
    config.winLossThreshold2 = -3;
    config.winLossThreshold3 = -6;
    config.winLossThreshold4 = -12;
    config.winLossThreshold5 = -24;
    config.scoreLossThreshold1 = -0.5;
    config.scoreLossThreshold2 = -1.5;
    config.scoreLossThreshold3 = -3;
    config.scoreLossThreshold4 = -6;
    config.scoreLossThreshold5 = -12;
    config.moveRankEvaluationMode = MoveRankEvaluationMode.WINRATE;
    Lizzie.config = config;
    try {
      Set<Integer> colors = new HashSet<Integer>();
      colors.add(
          FloatBoardRenderer.moveRankMarkColor(FloatBoardRenderer.moveRankMarkSeverity(0, 0))
              .getRGB());
      colors.add(
          FloatBoardRenderer.moveRankMarkColor(FloatBoardRenderer.moveRankMarkSeverity(-1.5, 0))
              .getRGB());
      colors.add(
          FloatBoardRenderer.moveRankMarkColor(FloatBoardRenderer.moveRankMarkSeverity(-5, 0))
              .getRGB());
      colors.add(
          FloatBoardRenderer.moveRankMarkColor(FloatBoardRenderer.moveRankMarkSeverity(-16, 0))
              .getRGB());
      colors.add(
          FloatBoardRenderer.moveRankMarkColor(FloatBoardRenderer.moveRankMarkSeverity(-40, 0))
              .getRGB());

      assertTrue(colors.size() >= 5, "move rank marks should expose several severity shades.");
    } finally {
      Lizzie.config = previousConfig;
    }
  }

  private static BranchRecordFixture branchRecordFixture() throws Exception {
    Lizzie.config.showBranch = true;
    Lizzie.config.showSuggestionVariations = true;
    Lizzie.config.showBlackCandidates = true;
    Lizzie.config.showWhiteCandidates = true;
    Lizzie.config.usePureStone = true;
    TrackingLizzieFrame frame = configuredFrame();
    frame.priorityMoveCoords = new ArrayList<>();
    frame.mouseOverCoordinate = new int[] {0, 1};
    Lizzie.frame = frame;
    BoardData current = moveData(new int[] {0, 0}, 2);
    MoveData candidate = bestMove(0, 1);
    candidate.variation =
        new ArrayList<>(
            List.of(
                candidate.coordinate,
                Board.convertCoordinatesToName(1, 1),
                Board.convertCoordinatesToName(2, 2)));
    current.bestMoves = new ArrayList<>(List.of(candidate));
    BoardHistoryList history = historyForCurrentNode(current);
    BoardHistoryNode node = history.getCurrentHistoryNode();
    history.add(moveData(new int[] {2, 0}, 3));
    history.previous();
    PreviewNavigationBoard board = allocate(PreviewNavigationBoard.class);
    board.setHistory(history);
    Lizzie.board = board;
    BoardRenderer renderer = configuredBranchRenderer();
    LizzieFrame.boardRenderer = renderer;
    return new BranchRecordFixture(frame, renderer, node, candidate);
  }

  private static KeyEvent keyEvent(int keyCode) {
    return new KeyEvent(
        new JPanel(), KeyEvent.KEY_PRESSED, 0L, 0, keyCode, KeyEvent.CHAR_UNDEFINED);
  }

  private static TrackingLizzieFrame configuredFrame() throws Exception {
    TrackingLizzieFrame frame = allocate(TrackingLizzieFrame.class);
    frame.mainPanel = new JPanel();
    frame.commentEditPane = new JScrollPane();
    frame.RightClickMenu = allocate(HiddenRightClickMenu.class);
    frame.RightClickMenu2 = allocate(HiddenRightClickMenu2.class);
    frame.clickOrder = -1;
    frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
    frame.suggestionclick = LizzieFrame.outOfBoundCoordinate;
    frame.priorityMoveCoords = new ArrayList<>();
    return frame;
  }

  private static boolean invokeIndependentMainBoardHoverGate(
      IndependentMainBoard board, int[] coords) throws Exception {
    Method method =
        IndependentMainBoard.class.getDeclaredMethod("isNextMoveBlunderTarget", int[].class);
    method.setAccessible(true);
    return (boolean) method.invoke(board, (Object) coords);
  }

  private static void invokeDrawBranch(BoardRenderer renderer) throws Exception {
    Method method = BoardRenderer.class.getDeclaredMethod("drawBranch");
    method.setAccessible(true);
    method.invoke(renderer);
    VariationPreviewState state =
        (VariationPreviewState) getField(BoardRenderer.class, renderer, "preview");
    VariationPreviewScheduler scheduler =
        (VariationPreviewScheduler) getField(VariationPreviewState.class, state, "scheduler");
    ((ControlledPreview) getField(VariationPreviewScheduler.class, scheduler, "worker")).finish();
  }

  private static final class ControlledPreview implements Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable task) {
      tasks.add(task);
    }

    void finish() {
      while (!tasks.isEmpty()) tasks.remove().run();
    }
  }

  private static FloatBoardRenderer configuredFloatRenderer() throws Exception {
    FloatBoardRenderer renderer = new FloatBoardRenderer();
    setIntField(renderer, "x", 0);
    setIntField(renderer, "y", 0);
    setIntField(renderer, "boardWidth", CANVAS_SIZE);
    setIntField(renderer, "boardHeight", CANVAS_SIZE);
    setIntField(renderer, "stoneRadius", STONE_RADIUS);
    setIntField(renderer, "scaledMarginWidth", SCALED_MARGIN);
    setIntField(renderer, "scaledMarginHeight", SCALED_MARGIN);
    setIntField(renderer, "squareWidth", SQUARE_SIZE);
    setIntField(renderer, "squareHeight", SQUARE_SIZE);
    return renderer;
  }

  private static BoardRenderer configuredBranchRenderer() throws Exception {
    return configuredBranchRenderer(false);
  }

  private static BoardRenderer configuredBranchRenderer(boolean independent) throws Exception {
    BoardRenderer renderer = new BoardRenderer(independent);
    VariationPreviewState state =
        (VariationPreviewState) getField(BoardRenderer.class, renderer, "preview");
    setField(VariationPreviewState.class, state, "scheduler",
        new VariationPreviewScheduler(new ControlledPreview(), Runnable::run));
    setIntField(renderer, "x", 0);
    setIntField(renderer, "y", 0);
    setIntField(renderer, "boardWidth", CANVAS_SIZE);
    setIntField(renderer, "boardHeight", CANVAS_SIZE);
    setIntField(renderer, "stoneRadius", STONE_RADIUS);
    setIntField(renderer, "scaledMarginWidth", SCALED_MARGIN);
    setIntField(renderer, "scaledMarginHeight", SCALED_MARGIN);
    setIntField(renderer, "squareWidth", SQUARE_SIZE);
    setIntField(renderer, "squareHeight", SQUARE_SIZE);
    return renderer;
  }

  private static BufferedImage renderMoveRankMark(FloatBoardRenderer renderer) throws Exception {
    BufferedImage image = new BufferedImage(CANVAS_SIZE, CANVAS_SIZE, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      Method method =
          FloatBoardRenderer.class.getDeclaredMethod("drawMoveRankMark", Graphics2D.class);
      method.setAccessible(true);
      method.invoke(renderer, graphics);
      return image;
    } finally {
      graphics.dispose();
    }
  }

  private static BufferedImage renderNextMoveOverlay(BoardRenderer renderer) throws Exception {
    BufferedImage image = new BufferedImage(CANVAS_SIZE, CANVAS_SIZE, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      setField(BoardRenderer.class, renderer, "nextCoords", new ArrayList<int[]>());
      Method drawNextMoves =
          BoardRenderer.class.getDeclaredMethod("drawNextMoves", Graphics2D.class);
      drawNextMoves.setAccessible(true);
      drawNextMoves.invoke(renderer, graphics);
      Method drawOutlines =
          BoardRenderer.class.getDeclaredMethod("drawNextMoveOutlinesOnTop", Graphics2D.class);
      drawOutlines.setAccessible(true);
      drawOutlines.invoke(renderer, graphics);
      return image;
    } finally {
      graphics.dispose();
    }
  }

  private static BufferedImage renderNextBlunderFirstMove(
      BoardRenderer renderer, int boardX, int boardY) throws Exception {
    BufferedImage image = new BufferedImage(CANVAS_SIZE, CANVAS_SIZE, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      setIntField(renderer, "nextMoveX", boardX);
      setIntField(renderer, "nextMoveY", boardY);
      Method method =
          BoardRenderer.class.getDeclaredMethod("drawNextBlunderFirstMove", Graphics2D.class);
      method.setAccessible(true);
      method.invoke(renderer, graphics);
      return image;
    } finally {
      graphics.dispose();
    }
  }

  private static boolean hasVisiblePaint(BufferedImage image) {
    for (int x = 0; x < image.getWidth(); x++) {
      for (int y = 0; y < image.getHeight(); y++) {
        if (((image.getRGB(x, y) >>> 24) & 0xFF) > 0) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasVisiblePaintNear(BufferedImage image, int boardX, int boardY) {
    int centerX = SCALED_MARGIN + SQUARE_SIZE * boardX;
    int centerY = SCALED_MARGIN + SQUARE_SIZE * boardY;
    int radius = STONE_RADIUS + 4;
    for (int x = centerX - radius; x <= centerX + radius; x++) {
      for (int y = centerY - radius; y <= centerY + radius; y++) {
        if (((image.getRGB(x, y) >>> 24) & 0xFF) > 0) {
          return true;
        }
      }
    }
    return false;
  }

  private static void setIntField(Object target, String name, int value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.setInt(target, value);
  }

  private static void setField(Class<?> owner, Object target, String name, Object value)
      throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object getField(Class<?> owner, Object target, String name) throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static BufferedImage renderBranchOverlay(BoardRenderer renderer) throws Exception {
    BufferedImage image = new BufferedImage(CANVAS_SIZE, CANVAS_SIZE, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      Method method = BoardRenderer.class.getDeclaredMethod("renderImages", Graphics2D.class);
      method.setAccessible(true);
      method.invoke(renderer, graphics);
      return image;
    } finally {
      graphics.dispose();
    }
  }

  private static BoardHistoryList historyWithNext(BoardData current, BoardData next) {
    BoardHistoryList history = new BoardHistoryList(current);
    history.add(next);
    history.toStart();
    return history;
  }

  private static void insertDummyAsFirstVariation(BoardHistoryNode parent) {
    BoardData dummyData = parent.getData().clone();
    dummyData.dummy = true;
    dummyData.lastMove = Optional.empty();
    BoardHistoryNode dummy = new BoardHistoryNode(dummyData);
    parent.variations.add(0, dummy);
    parent.setPreviousForChild(dummy);
  }

  private static BoardHistoryList historyForCurrentNode(BoardData current) {
    BoardHistoryList history = new BoardHistoryList(analyzedRootData());
    history.add(current);
    return history;
  }

  private static Board boardWith(BoardHistoryList history) throws Exception {
    Board board = allocate(Board.class);
    board.setHistory(history);
    return board;
  }

  private static BoardData analyzedRootData() {
    BoardData data = moveData(new int[] {0, 0}, 1);
    data.bestMoves = new ArrayList<>();
    data.winrate = 55;
    data.scoreMean = 0.5;
    return data;
  }

  private static BoardData currentData() {
    BoardData data = moveData(new int[] {0, 0}, 1);
    data.bestMoves = new ArrayList<>(List.of(bestMove(0, 1)));
    data.winrate = 55;
    data.scoreMean = 1.5;
    return data;
  }

  private static BoardData noSuggestionData() {
    BoardData data = moveData(new int[] {0, 0}, 1);
    data.bestMoves = new ArrayList<>();
    return data;
  }

  private static BoardData moveData(int[] lastMove, int moveNumber) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(lastMove[0], lastMove[1])] =
        moveNumber % 2 == 1 ? Stone.BLACK : Stone.WHITE;
    BoardData data =
        BoardData.move(
            stones,
            lastMove,
            stones[Board.getIndex(lastMove[0], lastMove[1])],
            moveNumber % 2 == 0,
            new Zobrist(),
            moveNumber,
            moveList(lastMove[0], lastMove[1], moveNumber),
            0,
            0,
            50,
            20);
    data.bestMoves = new ArrayList<>(List.of(bestMove(lastMove[0], lastMove[1])));
    data.winrate = 50;
    return data;
  }

  private static BoardData snapshotData(int[] lastMove, int moveNumber) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(lastMove[0], lastMove[1])] =
        moveNumber % 2 == 1 ? Stone.BLACK : Stone.WHITE;
    BoardData data =
        BoardData.snapshot(
            stones,
            Optional.of(lastMove),
            stones[Board.getIndex(lastMove[0], lastMove[1])],
            moveNumber % 2 == 0,
            new Zobrist(),
            moveNumber,
            moveList(lastMove[0], lastMove[1], moveNumber),
            0,
            0,
            48,
            20);
    data.bestMoves = new ArrayList<>(List.of(bestMove(1, 0)));
    data.scoreMean = -0.5;
    return data;
  }

  private static MoveData bestMove(int x, int y) {
    MoveData move = new MoveData();
    move.coordinate = Board.convertCoordinatesToName(x, y);
    move.order = 0;
    move.playouts = 10;
    move.winrate = 52;
    move.scoreMean = 1.0;
    return move;
  }

  private static int[] moveList(int x, int y, int moveNumber) {
    int[] moveNumberList = new int[BOARD_AREA];
    moveNumberList[Board.getIndex(x, y)] = moveNumber;
    return moveNumberList;
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class BranchRecordFixture {
    private final TrackingLizzieFrame frame;
    private final BoardRenderer renderer;
    private final BoardHistoryNode node;
    private final BoardHistoryNode previous;
    private final BoardHistoryNode next;
    private final MoveData candidate;
    private final BoardData data;
    private final Stone[] stones;
    private final int[] moveNumbers;
    private final List<BoardHistoryNode> variations;
    private final List<BoardHistoryNode> previousVariations;
    private final Input input = new Input();
    private long wheelWhen;

    private BranchRecordFixture(
        TrackingLizzieFrame frame,
        BoardRenderer renderer,
        BoardHistoryNode node,
        MoveData candidate) {
      this.frame = frame;
      this.renderer = renderer;
      this.node = node;
      this.previous = node.previous().orElseThrow();
      this.next = node.next().orElseThrow();
      this.candidate = candidate;
      this.data = node.getData();
      this.stones = data.stones.clone();
      this.moveNumbers = data.moveNumberList.clone();
      this.variations = new ArrayList<>(node.variations);
      this.previousVariations = new ArrayList<>(previous.variations);
    }

    private void draw() throws Exception {
      invokeDrawBranch(renderer);
    }

    private void key(int keyCode) throws Exception {
      SwingUtilities.invokeAndWait(() -> input.keyPressed(keyEvent(keyCode)));
    }

    private void wheel(int rotation) throws Exception {
      MouseWheelEvent event =
          new MouseWheelEvent(
              new JPanel(),
              MouseWheelEvent.MOUSE_WHEEL,
              ++wheelWhen,
              0,
              0,
              0,
              0,
              false,
              MouseWheelEvent.WHEEL_UNIT_SCROLL,
              1,
              rotation);
      SwingUtilities.invokeAndWait(() -> input.mouseWheelMoved(event));
    }

    private void reachFirstMove() throws Exception {
      for (int i = 0; i < 4; i++) {
        wheel(-1);
        draw();
        assertPreviewRecordUnchanged();
        assertTrue(
            renderer.hasSelectedVariation(), "the candidate must retain navigation ownership.");
      }
      assertEquals(1, renderer.getDisplayedBranchLength());
    }

    private void assertPreviewRecordUnchanged() {
      assertSame(
          node,
          Lizzie.board.getHistory().getCurrentHistoryNode(),
          "candidate-preview input must not navigate the real game record.");
      assertRecordStructureUnchanged();
    }

    private void assertRecordStructureUnchanged() {
      assertSame(data, node.getData(), "preview input must not replace record contents.");
      assertArrayEquals(stones, data.stones, "preview input must not change record stones.");
      assertArrayEquals(moveNumbers, data.moveNumberList);
      assertSame(previous, node.previous().orElseThrow());
      assertSame(next, node.next().orElseThrow());
      assertEquals(variations.size(), node.variations.size());
      for (int i = 0; i < variations.size(); i++) {
        assertSame(variations.get(i), node.variations.get(i));
      }
      assertEquals(previousVariations.size(), previous.variations.size());
      for (int i = 0; i < previousVariations.size(); i++) {
        assertSame(previousVariations.get(i), previous.variations.get(i));
      }
    }
  }

  private static final class PreviewNavigationBoard extends Board {
    @Override
    public void clearAfterMove() {
      // Keep actual history navigation; omit unrelated toolbar/sub-board UI maintenance.
    }
  }

  private static final class CoordinateBoardRenderer extends BoardRenderer {
    private final int[] coords;

    private CoordinateBoardRenderer(int[] coords) {
      super(false);
      this.coords = coords;
    }

    @Override
    public Optional<int[]> convertScreenToCoordinates(int x, int y) {
      return Optional.of(coords);
    }

    @Override
    public void setDisplayedBranchLength(int n) {}

    @Override
    public void drawmoveblock(int x, int y, boolean isblack) {}

    @Override
    public void removeblock() {}
  }

  private static final class TrackingLizzieFrame extends LizzieFrame {
    private int fullRefreshes;
    private int clearedMovePreviews;

    @Override
    public boolean isInPlayMode() {
      return false;
    }

    @Override
    public boolean processSubOnMouseMoved(int x, int y) {
      return false;
    }

    @Override
    public void refresh() {
      fullRefreshes++;
    }

    @Override
    public void clearMoved() {
      clearedMovePreviews++;
    }

    @Override
    public void onMainEnginePonder() {}


    @Override
    public void repaint() {}
  }

  private static final class TrackingIndependentMainBoard extends IndependentMainBoard {
    @Override
    public void refresh() {}

    @Override
    public void repaint() {}
  }

  private static final class PendingInputBoard extends Board {
    @Override
    public void clearAfterMove() {
      // Keep real rules/history placement and preview retirement, without unrelated sidebar UI.
      LizzieFrame.boardRenderer.clearBranch();
      if (Lizzie.frame.independentMainBoard != null) {
        Lizzie.frame.independentMainBoard.boardRenderer.clearBranch();
      }
    }
  }

  private static final class PendingInputEnvironment implements AutoCloseable {
    private final Leelaz previousEngine = Lizzie.leelaz;
    private final boolean previousEngineEmpty = EngineManager.isEmpty;
    private final EngineFollowController previousFollow = Lizzie.engineFollowController;
    private final boolean previousUrlSgf = LizzieFrame.urlSgf;
    private final boolean previousTempDrag = Input.tempDrag;
    private final boolean previousDragMode = Input.Draggedmode;
    private final boolean previousSelectMode = Input.selectMode;
    private MoveData selectedMove;
    private final List<String> selectedPv = List.of(
        Board.convertCoordinatesToName(2, 0),
        Board.convertCoordinatesToName(2, 1),
        Board.convertCoordinatesToName(1, 2));

    private static PendingInputEnvironment open() throws Exception {
      PendingInputEnvironment env = new PendingInputEnvironment();
      Lizzie.setPrimaryEngine(null);
      EngineManager.isEmpty = true;
      Lizzie.engineFollowController = null;
      LizzieFrame.urlSgf = false;
      Input.tempDrag = false;
      Input.Draggedmode = false;
      Input.selectMode = false;
      Lizzie.config.showBranch = true;
      Lizzie.config.showSuggestionVariations = true;
      Lizzie.config.showBlackCandidates = true;
      Lizzie.config.showWhiteCandidates = true;
      Lizzie.config.noRefreshOnMouseMove = true;
      Lizzie.config.usePureStone = true;
      Lizzie.config.extraMode = featurecat.lizzie.ExtraMode.Normal;
      TrackingLizzieFrame frame = (TrackingLizzieFrame) Lizzie.frame;
      frame.priorityMoveCoords = new ArrayList<>();
      frame.commentEditPane.setVisible(false);
      setField(LizzieFrame.class, frame, "curSuggestionMoveOrderByNumber", -1);
      Consumer<String> place =
          v -> Board.asCoordinates(v).ifPresent(c -> Lizzie.board.place(c[0], c[1]));
      setField(LizzieFrame.class, frame, "placeVariation", place);
      LizzieFrame.boardRenderer = configuredBranchRenderer();
      MoveData first = bestMove(0, 1);
      first.variation = List.of(first.coordinate, Board.convertCoordinatesToName(0, 2));
      env.selectedMove = bestMove(2, 0);
      env.selectedMove.order = 1;
      env.selectedMove.variation = new ArrayList<>(env.selectedPv);
      BoardData data = moveData(new int[] {0, 0}, 1);
      data.bestMoves = new ArrayList<>(List.of(first, env.selectedMove));
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(data);
      BoardHistoryNode selectedNode = history.getCurrentHistoryNode();
      history.add(moveData(new int[] {1, 0}, 2));
      history.setHead(selectedNode);
      PendingInputBoard board = allocate(PendingInputBoard.class);
      board.setHistory(history);
      Lizzie.board = board;
      return env;
    }

    private void replaceEnginePv() {
      selectedMove.variation.set(1, Board.convertCoordinatesToName(1, 1));
      MoveData replacement = bestMove(2, 0);
      replacement.order = 1;
      replacement.variation = List.of(replacement.coordinate, Board.convertCoordinatesToName(1, 1));
      Lizzie.board.getData().bestMoves.set(1, replacement);
    }

    @Override
    public void close() {
      Lizzie.frame.cancelPendingSuggestionHoverPreview();
      Lizzie.setPrimaryEngine(previousEngine);
      EngineManager.isEmpty = previousEngineEmpty;
      Lizzie.engineFollowController = previousFollow;
      LizzieFrame.urlSgf = previousUrlSgf;
      Input.tempDrag = previousTempDrag;
      Input.Draggedmode = previousDragMode;
      Input.selectMode = previousSelectMode;
    }
  }

  private static final class HiddenRightClickMenu extends RightClickMenu {
    @Override
    public boolean isVisible() {
      return false;
    }
  }

  private static final class HiddenRightClickMenu2 extends RightClickMenu2 {
    @Override
    public boolean isVisible() {
      return false;
    }
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final ResourceBundle previousResourceBundle;
    private final Font previousUiFont;
    private final BoardRenderer previousBoardRenderer;
    private final BoardRenderer previousBoardRenderer2;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame,
        ResourceBundle previousResourceBundle,
        Font previousUiFont,
        BoardRenderer previousBoardRenderer,
        BoardRenderer previousBoardRenderer2) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousResourceBundle = previousResourceBundle;
      this.previousUiFont = previousUiFont;
      this.previousBoardRenderer = previousBoardRenderer;
      this.previousBoardRenderer2 = previousBoardRenderer2;
    }

    private static TestEnvironment open() throws Exception {
      TestEnvironment env =
          new TestEnvironment(
              Board.boardWidth,
              Board.boardHeight,
              Lizzie.config,
              Lizzie.board,
              Lizzie.frame,
              Lizzie.resourceBundle,
              LizzieFrame.uiFont,
              LizzieFrame.boardRenderer,
              LizzieFrame.boardRenderer2);

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      Config config = allocate(Config.class);
      config.anaFrameShowNext = true;
      config.showNextMoveBlunder = true;
      config.showPreviousBestmovesInEngineGame = false;
      config.showMouseOverWinrateGraph = false;
      config.showWinrateGraph = false;
      config.noRefreshOnSub = false;
      config.autoReplayBranch = false;
      config.showrect = 2;
      config.moveRankMarkLastMove = 1;
      config.stoneIndicatorType = 1;
      config.useWinLossInMoveRank = false;
      config.useScoreLossInMoveRank = false;
      Lizzie.config = config;
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      LizzieFrame.uiFont = new Font("Dialog", Font.PLAIN, 12);

      TrackingLizzieFrame frame = configuredFrame();
      frame.isTrying = false;
      Lizzie.frame = frame;
      LizzieFrame.boardRenderer = new CoordinateBoardRenderer(new int[] {0, 0});
      return env;
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.resourceBundle = previousResourceBundle;
      LizzieFrame.uiFont = previousUiFont;
      LizzieFrame.boardRenderer = previousBoardRenderer;
      LizzieFrame.boardRenderer2 = previousBoardRenderer2;
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
