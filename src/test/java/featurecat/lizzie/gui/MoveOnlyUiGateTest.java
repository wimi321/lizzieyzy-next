package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.analysis.MoveRankEvaluationMode;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
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
  void boardRendererClearBranchDropsStaleBranchState() throws Exception {
    BoardRenderer renderer = new BoardRenderer(false);
    setField(BoardRenderer.class, renderer, "isShowingBranch", true);
    setField(BoardRenderer.class, renderer, "branchOpt", Optional.of(new Object()));
    setField(BoardRenderer.class, renderer, "variationOpt", Optional.of(List.of("A1")));
    setField(BoardRenderer.class, renderer, "mouseOverTemp", bestMove(0, 1));
    setField(
        BoardRenderer.class,
        renderer,
        "branchStonesImage",
        new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB));
    setField(
        BoardRenderer.class,
        renderer,
        "branchStonesShadowImage",
        new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB));

    renderer.clearBranch();

    Object emptyImage = getField(BoardRenderer.class, null, "emptyImage");
    assertFalse(renderer.isShowingBranch());
    assertFalse(((Optional<?>) getField(BoardRenderer.class, renderer, "branchOpt")).isPresent());
    assertFalse(
        ((Optional<?>) getField(BoardRenderer.class, renderer, "variationOpt")).isPresent());
    assertNull(getField(BoardRenderer.class, renderer, "mouseOverTemp"));
    assertSame(emptyImage, getField(BoardRenderer.class, renderer, "branchStonesImage"));
    assertSame(emptyImage, getField(BoardRenderer.class, renderer, "branchStonesShadowImage"));
  }

  @Test
  void floatBoardRendererClearBranchDropsStaleBranchState() throws Exception {
    FloatBoardRenderer renderer = new FloatBoardRenderer();
    setField(FloatBoardRenderer.class, renderer, "isShowingBranch", true);
    setField(FloatBoardRenderer.class, renderer, "showingBranch", true);
    setField(FloatBoardRenderer.class, renderer, "branchOpt", Optional.of(new Object()));
    setField(FloatBoardRenderer.class, renderer, "variationOpt", Optional.of(List.of("A1")));
    setField(FloatBoardRenderer.class, renderer, "mouseOverTemp", bestMove(0, 1));
    setField(
        FloatBoardRenderer.class,
        renderer,
        "branchStonesImage",
        new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB));
    setField(
        FloatBoardRenderer.class,
        renderer,
        "branchStonesShadowImage",
        new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB));

    renderer.clearBranch();

    Object emptyImage = getField(FloatBoardRenderer.class, null, "emptyImage");
    assertFalse(renderer.isShowingBranch());
    assertFalse(
        ((Optional<?>) getField(FloatBoardRenderer.class, renderer, "branchOpt")).isPresent());
    assertFalse(
        ((Optional<?>) getField(FloatBoardRenderer.class, renderer, "variationOpt")).isPresent());
    assertNull(getField(FloatBoardRenderer.class, renderer, "mouseOverTemp"));
    assertSame(emptyImage, getField(FloatBoardRenderer.class, renderer, "branchStonesImage"));
    assertSame(emptyImage, getField(FloatBoardRenderer.class, renderer, "branchStonesShadowImage"));
  }

  @Test
  void boardRendererDrawBranchClearsStaleBranchStateWhenHoverHasNoVariation() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config.showBranch = true;
      TrackingLizzieFrame frame = configuredFrame();
      frame.mouseOverCoordinate = new int[] {0, 1};
      Lizzie.frame = frame;
      Lizzie.board = boardWith(historyForCurrentNode(currentData()));
      BoardRenderer renderer = new BoardRenderer(false);
      setField(BoardRenderer.class, renderer, "isShowingBranch", true);
      setField(BoardRenderer.class, renderer, "branchOpt", Optional.of(new Object()));

      invokeDrawBranch(renderer);

      assertFalse(
          renderer.isShowingBranch(),
          "a hover candidate without a drawable variation should not keep stale branch state.");
      assertFalse(
          ((Optional<?>) getField(BoardRenderer.class, renderer, "branchOpt")).isPresent(),
          "stale branch data should stay cleared when drawBranch exits before rendering.");
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
      assertTrue(renderer.ownsBranchNavigation(), "hovered variation should own the wheel.");

      for (int i = 0; i < 4; i++) {
        LizzieFrame.undoNoRefresh(1);
        invokeDrawBranch(renderer);
        assertSame(
            currentNode,
            Lizzie.board.getHistory().getCurrentHistoryNode(),
            "stepping back inside a candidate variation must never undo the real game.");
        assertTrue(
            renderer.ownsBranchNavigation(),
            "the variation should keep owning navigation at its first move.");
      }
      assertEquals(1, renderer.getDisplayedBranchLength());

      LizzieFrame.redoNoRefresh(1);
      assertEquals(
          2,
          renderer.getDisplayedBranchLength(),
          "stepping forward from the first variation move should show the second move.");
      invokeDrawBranch(renderer);
      assertTrue(renderer.ownsBranchNavigation());
      assertSame(currentNode, Lizzie.board.getHistory().getCurrentHistoryNode());

      frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
      invokeDrawBranch(renderer);
      assertFalse(
          renderer.ownsBranchNavigation(),
          "leaving the candidate should hand navigation back to the game record.");

      frame.mouseOverCoordinate = new int[] {0, 1};
      invokeDrawBranch(renderer);
      renderer.clearBranch();
      assertFalse(renderer.ownsBranchNavigation(), "clearing the branch drops ownership.");
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
      assertFalse(fixture.renderer.ownsBranchNavigation());
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
      assertTrue(fixture.renderer.getDisplayedBranchLength() > 1);
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
      assertFalse(fixture.renderer.ownsBranchNavigation());
      assertTrue(independent.isSuggestionHoverPreviewReady(0, 1));
      assertTrue(fixture.frame.isMouseOverIndependMainBoard(0, 1));

      for (int i = 0; i < 4; i++) {
        SwingUtilities.invokeAndWait(() -> input.keyPressed(keyEvent(KeyEvent.VK_UP)));
        invokeDrawBranch(independent.boardRenderer);
        fixture.assertPreviewRecordUnchanged();
        assertTrue(independent.boardRenderer.ownsBranchNavigation());
      }
      assertEquals(1, independent.boardRenderer.getDisplayedBranchLength());
      SwingUtilities.invokeAndWait(() -> input.keyPressed(keyEvent(KeyEvent.VK_PAGE_DOWN)));
      invokeDrawBranch(independent.boardRenderer);
      fixture.assertPreviewRecordUnchanged();

      assertTrue(
          independent.boardRenderer.getDisplayedBranchLength() > 1,
          "independent Page Down must advance the owned preview from its first move.");
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
      assertFalse(fixture.renderer.ownsBranchNavigation());
      assertTrue(second.ownsBranchNavigation());

      for (int i = 0; i < 4; i++) {
        fixture.wheel(-1);
        fixture.draw();
        invokeDrawBranch(second);
        fixture.assertPreviewRecordUnchanged();
        assertTrue(second.ownsBranchNavigation());
      }
      assertEquals(1, second.getDisplayedBranchLength());
      fixture.key(KeyEvent.VK_PAGE_DOWN);
      fixture.draw();
      invokeDrawBranch(second);
      fixture.assertPreviewRecordUnchanged();

      assertTrue(
          second.getDisplayedBranchLength() > 1,
          "double-engine Page Down must advance the second owned preview from its first move.");
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
          boardRendererVariationOpt(fixture.renderer).orElseThrow(),
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
      assertTrue(fixture.renderer.ownsBranchNavigation());
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
      assertTrue(fixture.renderer.ownsBranchNavigation());
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
          fixture.renderer.ownsBranchNavigation(),
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
          boardRendererVariationOpt(fixture.renderer).orElseThrow(),
          "a new position must not reuse the previous node's frozen first-move PV.");
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

      assertEquals(
          other.coordinate, getField(BoardRenderer.class, fixture.renderer, "mouseOverCoords"));
      assertIterableEquals(
          other.variation, boardRendererVariationOpt(fixture.renderer).orElseThrow());
      assertTrue(fixture.renderer.ownsBranchNavigation());
      fixture.wheel(-1);
      fixture.draw();
      fixture.assertPreviewRecordUnchanged();
      assertTrue(fixture.renderer.ownsBranchNavigation());
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

      assertFalse(fixture.renderer.ownsBranchNavigation());
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
  void lowAllocationUnownedCandidateRetainsWheelJumpToEntireVariation() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BranchRecordFixture fixture = branchRecordFixture();
      MoveData weak = bestMove(1, 0);
      weak.edgeVisits = 0;
      weak.variation = List.of(weak.coordinate, Board.convertCoordinatesToName(1, 2));
      fixture.candidate.edgeVisits = 100;
      fixture.node.getData().bestMoves.add(weak);
      Lizzie.config.minPlayoutRatioForStats = 0.1;
      fixture.frame.mouseOverCoordinate = new int[] {1, 0};
      fixture.draw();

      assertEquals(0, weak.allocationVisits());
      assertFalse(fixture.renderer.ownsBranchNavigation());
      assertEquals(1, fixture.renderer.getDisplayedBranchLength());
      fixture.wheel(1);

      assertEquals(
          256,
          fixture.renderer.getDisplayedBranchLength(),
          "an un-owned low-allocation candidate must retain the full-variation wheel jump.");
      fixture.assertPreviewRecordUnchanged();
    } finally {
      env.close();
    }
  }

  @Test
  void boardRendererDefersHeavyBranchUntilCandidateHoverSettles() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config.showBranch = true;
      Lizzie.config.showSuggestionVariations = true;
      Lizzie.config.showBlackCandidates = true;
      Lizzie.config.showWhiteCandidates = true;
      Lizzie.config.noRefreshOnMouseMove = true;
      Lizzie.config.usePureStone = true;
      TrackingLizzieFrame frame = configuredFrame();
      frame.priorityMoveCoords = new ArrayList<>();
      Lizzie.frame = frame;
      BoardData current = currentData();
      MoveData suggested = current.bestMoves.get(0);
      suggested.variation = List.of(suggested.coordinate, Board.convertCoordinatesToName(1, 1));
      Lizzie.board = boardWith(historyForCurrentNode(current));
      LizzieFrame.boardRenderer = new CoordinateBoardRenderer(new int[] {0, 1});
      BoardRenderer renderer = configuredBranchRenderer();

      frame.onMouseMoved(0, 0);
      assertEquals(
          0,
          frame.fullRefreshes,
          "candidate hover must not rebuild comments and the problem list on the EDT.");
      invokeDrawBranch(renderer);

      Object emptyImage = getField(BoardRenderer.class, null, "emptyImage");
      assertTrue(frame.isMouseOver, "candidate marker should still react immediately.");
      assertFalse(frame.isSuggestionHoverPreviewReady(0, 1));
      assertSame(
          emptyImage,
          getField(BoardRenderer.class, renderer, "branchStonesImage"),
          "the expensive variation image must not be built during a quick candidate click.");

      SuggestionHoverIntent intent =
          (SuggestionHoverIntent)
              getField(LizzieFrame.class, frame, "suggestionHoverIntent");
      intent.reveal();
      assertEquals(
          0,
          frame.fullRefreshes,
          "revealing a settled preview must remain a board-only repaint.");
      invokeDrawBranch(renderer);

      BufferedImage branchImage =
          (BufferedImage) getField(BoardRenderer.class, renderer, "branchStonesImage");
      assertNotSame(emptyImage, branchImage, "settled hover should keep the full variation preview.");
      assertTrue(hasVisiblePaint(branchImage));
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
  void incrementalBranchOverlayRequiresExistingStonesToRemainUnchanged() {
    Stone[] source = {Stone.BLACK, Stone.EMPTY, Stone.WHITE};
    Stone[] branch = {Stone.BLACK, Stone.WHITE, Stone.WHITE};
    boolean[] newStones = {false, true, false};

    assertTrue(BoardRenderer.branchPreservesExistingStones(source, branch, newStones));

    branch[0] = Stone.BLACK_CAPTURED;
    assertFalse(
        BoardRenderer.branchPreservesExistingStones(source, branch, newStones),
        "a captured existing stone requires a complete branch image redraw.");

    newStones[0] = true;
    assertFalse(
        BoardRenderer.branchPreservesExistingStones(source, branch, newStones),
        "an existing stone may not disappear even if a malformed branch marks it as new.");
  }

  @Test
  void incrementalBranchOverlayRejectsMalformedBranchBuffers() {
    assertFalse(
        BoardRenderer.branchPreservesExistingStones(
            new Stone[] {Stone.BLACK}, new Stone[] {Stone.BLACK, Stone.WHITE}, new boolean[] {false}));
    assertFalse(BoardRenderer.branchPreservesExistingStones(null, null, null));
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

      invokeDrawBranch(renderer);

      Object emptyImage = getField(BoardRenderer.class, null, "emptyImage");
      BufferedImage firstBranchImage =
          (BufferedImage) getField(BoardRenderer.class, renderer, "branchStonesImage");
      assertNotSame(emptyImage, firstBranchImage, "first hover should render branch stones.");
      assertTrue(
          hasVisiblePaint(firstBranchImage), "first hover branch image should contain stones.");

      renderer.clearBranch();
      assertSame(emptyImage, getField(BoardRenderer.class, renderer, "branchStonesImage"));
      frame.mouseOverCoordinate = new int[] {0, 1};
      frame.isMouseOver = true;

      invokeDrawBranch(renderer);

      BufferedImage secondBranchImage =
          (BufferedImage) getField(BoardRenderer.class, renderer, "branchStonesImage");
      assertNotSame(
          emptyImage, secondBranchImage, "second hover of the same candidate must redraw stones.");
      assertTrue(
          hasVisiblePaint(secondBranchImage), "second hover should not publish a blank branch.");
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

      invokeDrawBranch(renderer);

      Optional<List<String>> firstPreview = boardRendererVariationOpt(renderer);
      Object firstBranch = getField(BoardRenderer.class, renderer, "branch");
      assertTrue(firstPreview.isPresent());
      assertIterableEquals(firstPv, firstPreview.get());
      assertNotSame(
          suggested.variation,
          firstPreview.get(),
          "no-refresh hover should keep an immutable preview snapshot, not the live engine list.");

      firstPv.set(1, Board.convertCoordinatesToName(2, 2));
      firstPv.add(Board.convertCoordinatesToName(1, 2));
      invokeDrawBranch(renderer);

      Optional<List<String>> secondPreview = boardRendererVariationOpt(renderer);
      assertTrue(secondPreview.isPresent());
      assertSame(
          firstBranch,
          getField(BoardRenderer.class, renderer, "branch"),
          "engine repaints must reuse the frozen branch instead of replaying the PV on the EDT.");
      assertIterableEquals(
          List.of(suggested.coordinate, Board.convertCoordinatesToName(1, 1)),
          secondPreview.get(),
          "same hovered move should not refresh when no-refresh-on-mouse-move is enabled.");
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

      invokeDrawBranch(renderer);

      suggested.variation =
          new ArrayList<>(
              List.of(
                  suggested.coordinate,
                  Board.convertCoordinatesToName(2, 2),
                  Board.convertCoordinatesToName(1, 2)));
      invokeDrawBranch(renderer);

      Optional<List<String>> preview = boardRendererVariationOpt(renderer);
      assertTrue(preview.isPresent());
      assertIterableEquals(
          suggested.variation,
          preview.get(),
          "hover variation should keep refreshing when no-refresh-on-mouse-move is disabled.");
    } finally {
      env.close();
    }
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

  @SuppressWarnings("unchecked")
  private static Optional<List<String>> boardRendererVariationOpt(BoardRenderer renderer)
      throws Exception {
    return (Optional<List<String>>) getField(BoardRenderer.class, renderer, "variationOpt");
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
            renderer.ownsBranchNavigation(), "the candidate must retain navigation ownership.");
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
    public void repaint() {}
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
