package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.analysis.MoveRankEvaluationMode;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.awt.Font;
import java.awt.Graphics2D;
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
    BoardRenderer renderer = new BoardRenderer(false);
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
    @Override
    public boolean isInPlayMode() {
      return false;
    }

    @Override
    public boolean processSubOnMouseMoved(int x, int y) {
      return false;
    }

    @Override
    public void refresh() {}

    @Override
    public void clearMoved() {}

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

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame,
        ResourceBundle previousResourceBundle,
        Font previousUiFont,
        BoardRenderer previousBoardRenderer) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousResourceBundle = previousResourceBundle;
      this.previousUiFont = previousUiFont;
      this.previousBoardRenderer = previousBoardRenderer;
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
              LizzieFrame.boardRenderer);

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
