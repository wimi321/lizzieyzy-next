package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WinrateGraphQuickOverviewHitTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;
  private static final int ORIG_GRAPH_WIDTH = 240;
  private static final int ORIG_GRAPH_HEIGHT = 200;
  private static final int GRAPH_WIDTH = ORIG_GRAPH_WIDTH - 6;
  private static final int GRAPH_HEIGHT = ORIG_GRAPH_HEIGHT - 4;
  private static final int GRAPH_Y = 2;
  private static final int GRAPH_NUM_MOVES = 50;

  @Test
  void hoverHitsVisibleVariationQuickOverviewPointWhileTrying() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.isTrying = true;
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] point = renderedQuickOverviewPoint(graph, fixture.variationStart);
      boolean handled = frame.processMouseMoveOnWinrateGraph(point[0], point[1]);

      assertTrue(handled, "hover should resolve the visible quick overview point.");
      assertSame(
          fixture.variationStart,
          graph.mouseOverNode,
          "hover should follow the variation point that quick overview actually draws.");
    } finally {
      env.close();
    }
  }

  @Test
  void clickJumpsToVisibleVariationQuickOverviewPointWhileTrying() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.isTrying = true;
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] point = renderedQuickOverviewPoint(graph, fixture.variationStart);
      frame.onClickedWinrateOnly(point[0], point[1]);

      assertSame(
          fixture.variationStart,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "click should jump to the variation node drawn in quick overview.");
    } finally {
      env.close();
    }
  }

  @Test
  void dragJumpsToVisibleVariationQuickOverviewPointWhileTrying() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.isTrying = true;
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] point = renderedQuickOverviewPoint(graph, fixture.variationStart);
      frame.onMouseDragged(point[0], point[1]);

      assertSame(
          fixture.variationStart,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "drag should jump to the variation node drawn in quick overview.");
    } finally {
      env.close();
    }
  }

  @Test
  void quickOverviewBlankAreaIgnoresHoverClickAndDragWhileTrying() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.isTrying = true;
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] blankPoint = quickOverviewBlankPoint(graph);
      BoardHistoryNode start = fixture.board.getHistory().getCurrentHistoryNode();
      graph.clearMouseOverNode();

      boolean handled = frame.processMouseMoveOnWinrateGraph(blankPoint[0], blankPoint[1]);
      assertFalse(handled, "hover should ignore blank quick overview background.");
      assertSame(
          null, graph.mouseOverNode, "blank quick overview hover should keep mouseOver null.");

      frame.onClickedWinrateOnly(blankPoint[0], blankPoint[1]);
      assertSame(
          start,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "blank quick overview click should keep current node unchanged.");

      frame.onMouseDragged(blankPoint[0], blankPoint[1]);
      assertSame(
          start,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "blank quick overview drag should keep current node unchanged.");
    } finally {
      env.close();
    }
  }

  @Test
  void quickOverviewSnapshotBoundarySupportsHoverClickAndDragWhileTrying() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      SnapshotFixture fixture = boardWithSnapshotBoundary();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.isTrying = true;
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] point = renderedQuickOverviewPoint(graph, fixture.snapshotBoundary);
      assertPixelRenderedAt(graph, point[0], point[1]);

      boolean handled = frame.processMouseMoveOnWinrateGraph(point[0], point[1]);
      assertTrue(handled, "hover should resolve the quick overview SNAPSHOT boundary point.");
      assertSame(
          fixture.snapshotBoundary,
          graph.mouseOverNode,
          "hover should target the SNAPSHOT boundary point.");

      frame.onClickedWinrateOnly(point[0], point[1]);
      assertSame(
          fixture.snapshotBoundary,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "click should jump to the SNAPSHOT boundary point.");

      fixture.board.getHistory().setHead(fixture.board.getHistory().getMainEnd());
      frame.onMouseDragged(point[0], point[1]);
      assertSame(
          fixture.snapshotBoundary,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "drag should jump to the SNAPSHOT boundary point.");
    } finally {
      env.close();
    }
  }

  @Test
  void quickOverviewTreatsHeaderOnlyPayloadAsAnalyzedPoint() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      HeaderOnlyFixture fixture = boardWithHeaderOnlyAnalysisMove();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.isTrying = true;
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      Object headerOnlyMove =
          quickOverviewMoveForNode(
              graph, fixture.board.getHistory().getCurrentHistoryNode(), fixture.node);
      assertNotNull(headerOnlyMove, "expected quick overview entry for header-only payload node.");
      assertTrue(
          quickOverviewMoveHasAnalysis(headerOnlyMove),
          "header-only payload should still be treated as analyzed in quick overview.");
      assertEquals(
          fixture.expectedDisplayedWinrate,
          quickOverviewMoveWinrate(headerOnlyMove),
          0.0001,
          "quick overview should consume header winrate instead of falling back to previous point.");
    } finally {
      env.close();
    }
  }

  @Test
  void backgroundOnlyPlayModeFrameClearsStaleRenderedAnchors() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.isTrying = true;
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] graphAnchor = renderedGraphPoint(graph, fixture.variationStart);
      int[] overviewAnchor = renderedQuickOverviewPoint(graph, fixture.variationStart);

      Lizzie.config.UsePlayMode = true;
      frame.isPlayingAgainstLeelaz = true;
      renderGraph(graph);

      assertNull(
          resolveTargetNode(graph, graphAnchor[0], graphAnchor[1]),
          "background-only play-mode frame should clear stale main-graph anchors.");
      assertNull(
          resolveTargetNode(graph, overviewAnchor[0], overviewAnchor[1]),
          "background-only play-mode frame should clear stale quick-overview anchors.");
    } finally {
      env.close();
    }
  }

  @Test
  void quickOverviewOcclusionBackgroundDoesNotPassThroughToMainGraphAnchor() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingBoard board = boardWithLowWinrateOverviewOcclusion();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.isTrying = true;
      Lizzie.board = board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] blankOccludedPoint = quickOverviewOccludedGraphBlankPoint(graph);
      BoardHistoryNode start = board.getHistory().getCurrentHistoryNode();
      graph.clearMouseOverNode();

      boolean handled =
          frame.processMouseMoveOnWinrateGraph(blankOccludedPoint[0], blankOccludedPoint[1]);
      assertFalse(
          handled, "overview blank background should miss even when graph anchors overlap.");
      assertSame(
          null,
          graph.mouseOverNode,
          "overview blank background should keep hover target empty in occluded area.");

      frame.onClickedWinrateOnly(blankOccludedPoint[0], blankOccludedPoint[1]);
      assertSame(
          start,
          board.getHistory().getCurrentHistoryNode(),
          "overview blank background should block click pass-through to main graph anchors.");

      frame.onMouseDragged(blankOccludedPoint[0], blankOccludedPoint[1]);
      assertSame(
          start,
          board.getHistory().getCurrentHistoryNode(),
          "overview blank background should block drag pass-through to main graph anchors.");
    } finally {
      env.close();
    }
  }

  @Test
  void quickOverviewOcclusionCompositeMasksMainGraphAnchorPixel() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingBoard board = boardWithLowWinrateOverviewOcclusion();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.isTrying = true;
      Lizzie.board = board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] blankOccludedPoint = quickOverviewOccludedGraphBlankPoint(graph);
      BoardHistoryNode occludedNode =
          graphAnchorNodeAt(graph, blankOccludedPoint[0], blankOccludedPoint[1]);
      Color anchorColor = graphAnchorColor(graph, occludedNode);
      BufferedImage composedLayer = renderGraphComposedLayer(graph);

      int compositeRgb = composedLayer.getRGB(blankOccludedPoint[0], blankOccludedPoint[1]);
      assertTrue(
          compositeRgb != anchorColor.getRGB(),
          "final composite should not keep main-graph anchor pixels in overview blank area.");
    } finally {
      env.close();
    }
  }

  @Test
  void quickOverviewSnapshotHitPixelsMatchRenderedDotPixels() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      SnapshotFixture fixture = boardWithSnapshotBoundary();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.isTrying = true;
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      BufferedImage winrateLayer = renderGraphWinrateLayer(graph);
      assertQuickOverviewHitPixelsHaveForeground(graph, fixture.snapshotBoundary, winrateLayer, 8);
    } finally {
      env.close();
    }
  }

  @Test
  void antialiasQuickOverviewVisibleDotPixelsResolveToSnapshotNode() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      SnapshotFixture fixture = boardWithSparseSnapshotBoundary();
      WinrateGraph graph = configuredGraph();
      setField(graph, "DOT_RADIUS", 8);
      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.isTrying = true;
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      BufferedImage winrateLayer = renderGraphWinrateLayerWithProductionHints(graph);
      int dotSize = renderedQuickOverviewDotSize(graph);
      boolean[][] strictMask = renderDotMask(dotSize, false);
      int[] edgePixel =
          antialiasEdgePixelOutsideMask(
              graph, fixture.snapshotBoundary, winrateLayer, strictMask, dotSize);
      overrideQuickOverviewDotMaskCache(graph, dotSize, strictMask);
      assertSame(
          fixture.snapshotBoundary,
          resolveTargetNode(graph, edgePixel[0], edgePixel[1]),
          "antialias edge pixel should stay hittable even when a stricter cached mask exists.");
    } finally {
      env.close();
    }
  }

  @Test
  void stateChangeBeforeNextDrawInvalidatesRenderedGraphAndOverviewCaches() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.isTrying = true;
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] graphAnchor = renderedGraphPoint(graph, fixture.variationStart);
      int[] overviewAnchor = renderedQuickOverviewPoint(graph, fixture.variationStart);
      assertSame(
          fixture.variationStart,
          resolveTargetNode(graph, graphAnchor[0], graphAnchor[1]),
          "precondition: initial rendered main-graph anchor should resolve its node.");
      assertSame(
          fixture.variationStart,
          resolveTargetNode(graph, overviewAnchor[0], overviewAnchor[1]),
          "precondition: initial rendered quick-overview anchor should resolve its node.");

      fixture.board.getHistory().setHead(fixture.board.getHistory().getMainEnd());

      assertNull(
          resolveTargetNode(graph, graphAnchor[0], graphAnchor[1]),
          "state change should invalidate stale main-graph anchors before the next draw.");
      assertNull(
          resolveTargetNode(graph, overviewAnchor[0], overviewAnchor[1]),
          "state change should invalidate stale quick-overview anchors before the next draw.");
    } finally {
      env.close();
    }
  }

  @Test
  void inPlaceNodeDataMutationBeforeNextDrawInvalidatesRenderedCaches() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.isTrying = true;
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] graphAnchor = renderedGraphPoint(graph, fixture.variationStart);
      int[] overviewAnchor = renderedQuickOverviewPoint(graph, fixture.variationStart);
      assertSame(
          fixture.variationStart,
          resolveTargetNode(graph, graphAnchor[0], graphAnchor[1]),
          "precondition: initial main-graph anchor should resolve.");
      assertSame(
          fixture.variationStart,
          resolveTargetNode(graph, overviewAnchor[0], overviewAnchor[1]),
          "precondition: initial quick-overview anchor should resolve.");

      BoardData mutatedData = fixture.variationStart.getData();
      mutatedData.winrate = 92;
      mutatedData.setPlayouts(mutatedData.getPlayouts() + 10);
      mutatedData.setScoreMean(mutatedData.scoreMean + 3.5);

      assertNull(
          resolveTargetNode(graph, graphAnchor[0], graphAnchor[1]),
          "in-place node-data update should invalidate stale main-graph points before redraw.");
      assertNull(
          resolveTargetNode(graph, overviewAnchor[0], overviewAnchor[1]),
          "in-place node-data update should invalidate stale quick-overview points before redraw.");
    } finally {
      env.close();
    }
  }

  private static VariationFixture boardWithVisibleMainContinuation() throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveNode(0, 0, Stone.BLACK, false, 1, 60));
    history.add(moveNode(1, 0, Stone.WHITE, true, 2, 55));
    BoardHistoryNode fork = history.getCurrentHistoryNode();
    history.add(moveNode(2, 0, Stone.BLACK, false, 3, 52));
    history.add(moveNode(0, 1, Stone.WHITE, true, 4, 48));
    history.add(moveNode(1, 1, Stone.BLACK, false, 5, 62));
    history.setHead(fork);
    BoardHistoryNode variationStart = fork.addAtLast(moveNode(2, 1, Stone.BLACK, false, 3, 41));
    BoardHistoryNode variationCurrent =
        variationStart.addAtLast(moveNode(0, 2, Stone.WHITE, true, 4, 36));
    history.setHead(variationCurrent);
    board.setHistory(history);
    return new VariationFixture(board, variationStart);
  }

  private static SnapshotFixture boardWithSnapshotBoundary() throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveNode(0, 0, Stone.BLACK, false, 1, 63));
    history.add(snapshotNode(Optional.of(new int[] {1, 1}), Stone.WHITE, true, 4, 0));
    BoardHistoryNode snapshotBoundary = history.getCurrentHistoryNode();
    history.add(moveNode(2, 0, Stone.WHITE, true, 5, 34));
    history.setHead(history.getMainEnd());
    board.setHistory(history);
    return new SnapshotFixture(board, snapshotBoundary);
  }

  private static SnapshotFixture boardWithSparseSnapshotBoundary() throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveNode(0, 0, Stone.BLACK, false, 1, 63));
    history.add(snapshotNode(Optional.of(new int[] {1, 1}), Stone.WHITE, true, 25, 0));
    BoardHistoryNode snapshotBoundary = history.getCurrentHistoryNode();
    history.add(moveNode(2, 0, Stone.WHITE, true, 50, 34));
    history.setHead(history.getMainEnd());
    board.setHistory(history);
    return new SnapshotFixture(board, snapshotBoundary);
  }

  private static HeaderOnlyFixture boardWithHeaderOnlyAnalysisMove() throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveNode(0, 0, Stone.BLACK, false, 1, 63));
    BoardData headerOnly = moveNodeWithHeaderOnlyAnalysis(1, 0, Stone.WHITE, true, 2, 37);
    history.add(headerOnly);
    history.setHead(history.getMainEnd());
    board.setHistory(history);
    return new HeaderOnlyFixture(board, history.getCurrentHistoryNode(), 37);
  }

  private static TrackingBoard boardWithLowWinrateOverviewOcclusion() throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveNode(0, 0, Stone.BLACK, false, 1, 3));
    history.add(moveNode(1, 0, Stone.WHITE, true, 2, 4));
    history.add(moveNode(2, 0, Stone.BLACK, false, 3, 5));
    history.add(moveNode(0, 1, Stone.WHITE, true, 4, 6));
    history.add(moveNode(1, 1, Stone.BLACK, false, 5, 7));
    history.setHead(history.getMainEnd());
    board.setHistory(history);
    return board;
  }

  private static WinrateGraph configuredGraph() throws Exception {
    WinrateGraph graph = new WinrateGraph();
    setField(graph, "origParams", new int[] {0, 0, ORIG_GRAPH_WIDTH, ORIG_GRAPH_HEIGHT});
    setField(graph, "params", new int[] {0, GRAPH_Y, GRAPH_WIDTH, GRAPH_HEIGHT, GRAPH_NUM_MOVES});
    return graph;
  }

  private static BoardData moveNode(
      int x, int y, Stone color, boolean blackToPlay, int moveNumber, double winrate) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(x, y)] = color;
    return BoardData.move(
        stones,
        new int[] {x, y},
        color,
        blackToPlay,
        zobrist(stones),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        winrate,
        1);
  }

  private static BoardData moveNodeWithHeaderOnlyAnalysis(
      int x, int y, Stone color, boolean blackToPlay, int moveNumber, double winrate) {
    BoardData data = moveNode(x, y, color, blackToPlay, moveNumber, winrate);
    data.setPlayouts(0);
    data.engineName = "MainEngine";
    data.analysisHeaderSlots = 3;
    return data;
  }

  private static BoardData snapshotNode(
      Optional<int[]> lastMove,
      Stone lastMoveColor,
      boolean blackToPlay,
      int moveNumber,
      int playouts) {
    Stone[] stones = emptyStones();
    lastMove.ifPresent(coords -> stones[Board.getIndex(coords[0], coords[1])] = lastMoveColor);
    return BoardData.snapshot(
        stones,
        lastMove,
        lastMoveColor,
        blackToPlay,
        zobrist(stones),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        playouts);
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private static Zobrist zobrist(Stone[] stones) {
    Zobrist zobrist = new Zobrist();
    for (int x = 0; x < BOARD_SIZE; x++) {
      for (int y = 0; y < BOARD_SIZE; y++) {
        Stone stone = stones[Board.getIndex(x, y)];
        if (!stone.isEmpty()) {
          zobrist.toggleStone(x, y, stone);
        }
      }
    }
    return zobrist;
  }

  private static void setField(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static int[] renderedQuickOverviewPoint(WinrateGraph graph, BoardHistoryNode node)
      throws Exception {
    renderGraph(graph);
    Method method =
        WinrateGraph.class.getDeclaredMethod("renderedQuickOverviewPoint", BoardHistoryNode.class);
    method.setAccessible(true);
    int[] point = (int[]) method.invoke(graph, node);
    assertNotNull(point, "expected rendered quick overview anchor point.");
    return point;
  }

  private static int[] renderedGraphPoint(WinrateGraph graph, BoardHistoryNode node)
      throws Exception {
    renderGraph(graph);
    Method method =
        WinrateGraph.class.getDeclaredMethod("renderedGraphPoint", BoardHistoryNode.class);
    method.setAccessible(true);
    int[] point = (int[]) method.invoke(graph, node);
    assertNotNull(point, "expected rendered main graph anchor point.");
    return point;
  }

  private static int[] quickOverviewBlankPoint(WinrateGraph graph) throws Exception {
    renderGraph(graph);
    Object layout = getField(graph, "renderedQuickOverviewLayout");
    assertNotNull(layout, "expected quick overview layout to be rendered.");
    int minX = ((int) getField(layout, "overviewX")) - 2;
    int minY = (int) getField(layout, "overviewY");
    int maxX = minX + ((int) getField(layout, "overviewWidth")) + 4;
    int maxY = minY + (int) getField(layout, "overviewHeight");
    for (int y = minY; y < maxY; y++) {
      for (int x = minX; x < maxX; x++) {
        BoardHistoryNode node = resolveTargetNode(graph, x, y);
        if (node == null) {
          return new int[] {x, y};
        }
      }
    }
    throw new AssertionError("expected blank quick overview background point.");
  }

  private static int[] quickOverviewOccludedGraphBlankPoint(WinrateGraph graph) throws Exception {
    renderGraph(graph);
    Object layout = getField(graph, "renderedQuickOverviewLayout");
    assertNotNull(layout, "expected quick overview layout to be rendered.");

    Method currentGraphPoints = WinrateGraph.class.getDeclaredMethod("currentGraphPoints");
    currentGraphPoints.setAccessible(true);
    List<?> points = (List<?>) currentGraphPoints.invoke(graph);

    Method quickOverviewHit =
        WinrateGraph.class.getDeclaredMethod(
            "directQuickOverviewPointHit", layout.getClass(), int.class, int.class);
    quickOverviewHit.setAccessible(true);
    Method graphHit =
        WinrateGraph.class.getDeclaredMethod(
            "directGraphPointHit", List.class, int.class, int.class);
    graphHit.setAccessible(true);

    int minX = ((int) getField(layout, "overviewX")) - 2;
    int minY = (int) getField(layout, "overviewY");
    int maxX = minX + ((int) getField(layout, "overviewWidth")) + 4;
    int maxY = minY + (int) getField(layout, "overviewHeight");
    for (int y = minY; y < maxY; y++) {
      for (int x = minX; x < maxX; x++) {
        Object overviewPoint = quickOverviewHit.invoke(graph, layout, x, y);
        if (overviewPoint != null) {
          continue;
        }
        Object graphPoint = graphHit.invoke(graph, points, x, y);
        if (graphPoint != null) {
          return new int[] {x, y};
        }
      }
    }
    throw new AssertionError("expected an overview-occluded blank pixel over a main graph anchor.");
  }

  private static BoardHistoryNode graphAnchorNodeAt(WinrateGraph graph, int x, int y)
      throws Exception {
    Method currentGraphPoints = WinrateGraph.class.getDeclaredMethod("currentGraphPoints");
    currentGraphPoints.setAccessible(true);
    List<?> points = (List<?>) currentGraphPoints.invoke(graph);
    Method graphHit =
        WinrateGraph.class.getDeclaredMethod(
            "directGraphPointHit", List.class, int.class, int.class);
    graphHit.setAccessible(true);
    Object graphPoint = graphHit.invoke(graph, points, x, y);
    assertNotNull(graphPoint, "expected occluded pixel to overlap a main-graph anchor.");
    return (BoardHistoryNode) getField(graphPoint, "node");
  }

  private static Color graphAnchorColor(WinrateGraph graph, BoardHistoryNode node)
      throws Exception {
    Method anchorColorMethod =
        WinrateGraph.class.getDeclaredMethod("graphAnchorColor", BoardData.class);
    anchorColorMethod.setAccessible(true);
    return (Color) anchorColorMethod.invoke(graph, node.getData());
  }

  private static BufferedImage renderGraphWinrateLayer(WinrateGraph graph) {
    BufferedImage winrateLayer =
        new BufferedImage(ORIG_GRAPH_WIDTH, ORIG_GRAPH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    BufferedImage blunderLayer =
        new BufferedImage(ORIG_GRAPH_WIDTH, ORIG_GRAPH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    BufferedImage backgroundLayer =
        new BufferedImage(ORIG_GRAPH_WIDTH, ORIG_GRAPH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    Graphics2D winrateGraphics = winrateLayer.createGraphics();
    Graphics2D blunderGraphics = blunderLayer.createGraphics();
    Graphics2D backgroundGraphics = backgroundLayer.createGraphics();
    try {
      graph.draw(
          winrateGraphics,
          blunderGraphics,
          backgroundGraphics,
          0,
          0,
          ORIG_GRAPH_WIDTH,
          ORIG_GRAPH_HEIGHT);
      return winrateLayer;
    } finally {
      winrateGraphics.dispose();
      blunderGraphics.dispose();
      backgroundGraphics.dispose();
    }
  }

  private static BufferedImage renderGraphWinrateLayerWithProductionHints(WinrateGraph graph) {
    BufferedImage winrateLayer =
        new BufferedImage(ORIG_GRAPH_WIDTH, ORIG_GRAPH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    BufferedImage blunderLayer =
        new BufferedImage(ORIG_GRAPH_WIDTH, ORIG_GRAPH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    BufferedImage backgroundLayer =
        new BufferedImage(ORIG_GRAPH_WIDTH, ORIG_GRAPH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    Graphics2D winrateGraphics = winrateLayer.createGraphics();
    Graphics2D blunderGraphics = blunderLayer.createGraphics();
    Graphics2D backgroundGraphics = backgroundLayer.createGraphics();
    try {
      applyProductionHints(winrateGraphics);
      applyProductionHints(blunderGraphics);
      applyProductionHints(backgroundGraphics);
      graph.draw(
          winrateGraphics,
          blunderGraphics,
          backgroundGraphics,
          0,
          0,
          ORIG_GRAPH_WIDTH,
          ORIG_GRAPH_HEIGHT);
      return winrateLayer;
    } finally {
      winrateGraphics.dispose();
      blunderGraphics.dispose();
      backgroundGraphics.dispose();
    }
  }

  private static BufferedImage renderGraphComposedLayer(WinrateGraph graph) {
    BufferedImage winrateLayer =
        new BufferedImage(ORIG_GRAPH_WIDTH, ORIG_GRAPH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    BufferedImage blunderLayer =
        new BufferedImage(ORIG_GRAPH_WIDTH, ORIG_GRAPH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    BufferedImage backgroundLayer =
        new BufferedImage(ORIG_GRAPH_WIDTH, ORIG_GRAPH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    Graphics2D winrateGraphics = winrateLayer.createGraphics();
    Graphics2D blunderGraphics = blunderLayer.createGraphics();
    Graphics2D backgroundGraphics = backgroundLayer.createGraphics();
    try {
      applyProductionHints(winrateGraphics);
      applyProductionHints(blunderGraphics);
      applyProductionHints(backgroundGraphics);
      graph.draw(
          winrateGraphics,
          blunderGraphics,
          backgroundGraphics,
          0,
          0,
          ORIG_GRAPH_WIDTH,
          ORIG_GRAPH_HEIGHT);
      backgroundGraphics.drawImage(blunderLayer, 0, 0, null);
      backgroundGraphics.drawImage(winrateLayer, 0, 0, null);
      return backgroundLayer;
    } finally {
      winrateGraphics.dispose();
      blunderGraphics.dispose();
      backgroundGraphics.dispose();
    }
  }

  private static void assertQuickOverviewHitPixelsHaveForeground(
      WinrateGraph graph, BoardHistoryNode targetNode, BufferedImage winrateLayer, int radius)
      throws Exception {
    Object layout = getField(graph, "renderedQuickOverviewLayout");
    assertNotNull(layout, "expected quick overview layout to be rendered.");
    Object point = quickOverviewPointForNode(layout, targetNode);
    assertNotNull(point, "expected quick overview anchor point for target node.");

    Method quickOverviewHit =
        WinrateGraph.class.getDeclaredMethod(
            "directQuickOverviewPointHit", layout.getClass(), int.class, int.class);
    quickOverviewHit.setAccessible(true);
    int centerX = (int) getField(point, "x");
    int centerY = (int) getField(point, "y");
    int hitCount = 0;
    int minY = Math.max(0, centerY - radius);
    int maxY = Math.min(winrateLayer.getHeight() - 1, centerY + radius);
    int minX = Math.max(0, centerX - radius);
    int maxX = Math.min(winrateLayer.getWidth() - 1, centerX + radius);
    for (int y = minY; y <= maxY; y++) {
      for (int x = minX; x <= maxX; x++) {
        Object hitPoint = quickOverviewHit.invoke(graph, layout, x, y);
        if (hitPoint != point) {
          continue;
        }
        hitCount++;
        int alpha = (winrateLayer.getRGB(x, y) >>> 24) & 0xff;
        assertTrue(
            alpha > 0,
            "quick overview hit pixel should match a rendered dot pixel: (" + x + "," + y + ")");
      }
    }
    assertTrue(hitCount > 0, "expected at least one quick overview hit pixel for target node.");
  }

  private static int renderedQuickOverviewDotSize(WinrateGraph graph) throws Exception {
    Object layout = getField(graph, "renderedQuickOverviewLayout");
    assertNotNull(layout, "expected quick overview layout to be rendered.");
    return (int) getField(layout, "dotSize");
  }

  @SuppressWarnings("unchecked")
  private static void overrideQuickOverviewDotMaskCache(
      WinrateGraph graph, int dotSize, boolean[][] mask) throws Exception {
    Field field = WinrateGraph.class.getDeclaredField("quickOverviewDotMaskCache");
    field.setAccessible(true);
    Map<Integer, boolean[][]> cache = (Map<Integer, boolean[][]>) field.get(graph);
    cache.put(dotSize, mask);
  }

  private static int[] antialiasEdgePixelOutsideMask(
      WinrateGraph graph,
      BoardHistoryNode targetNode,
      BufferedImage winrateLayer,
      boolean[][] strictMask,
      int dotSize)
      throws Exception {
    Object layout = getField(graph, "renderedQuickOverviewLayout");
    assertNotNull(layout, "expected quick overview layout to be rendered.");
    Object point = quickOverviewPointForNode(layout, targetNode);
    assertNotNull(point, "expected quick overview anchor point for target node.");
    int centerX = (int) getField(point, "x");
    int centerY = (int) getField(point, "y");
    int dotLeft = centerX - dotSize / 2;
    int dotTop = centerY - dotSize / 2;
    for (int localY = 0; localY < dotSize; localY++) {
      int y = dotTop + localY;
      if (y < 0 || y >= winrateLayer.getHeight()) {
        continue;
      }
      for (int localX = 0; localX < dotSize; localX++) {
        int x = dotLeft + localX;
        if (x < 0 || x >= winrateLayer.getWidth()) {
          continue;
        }
        int alpha = (winrateLayer.getRGB(x, y) >>> 24) & 0xff;
        if (alpha <= 0 || alpha >= 255) {
          continue;
        }
        if (!strictMask[localY][localX]) {
          return new int[] {x, y};
        }
      }
    }
    throw new AssertionError("expected antialias edge pixel outside the strict dot mask.");
  }

  private static boolean[][] renderDotMask(int dotSize, boolean antialias) {
    BufferedImage maskImage = new BufferedImage(dotSize, dotSize, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = maskImage.createGraphics();
    try {
      if (antialias) {
        applyProductionHints(graphics);
      }
      graphics.fillOval(0, 0, dotSize, dotSize);
    } finally {
      graphics.dispose();
    }
    boolean[][] mask = new boolean[dotSize][dotSize];
    for (int y = 0; y < dotSize; y++) {
      for (int x = 0; x < dotSize; x++) {
        mask[y][x] = ((maskImage.getRGB(x, y) >>> 24) & 0xff) > 0;
      }
    }
    return mask;
  }

  private static Object quickOverviewPointForNode(Object layout, BoardHistoryNode targetNode)
      throws Exception {
    List<?> points = (List<?>) getField(layout, "points");
    for (Object point : points) {
      Object move = getField(point, "move");
      BoardHistoryNode node = (BoardHistoryNode) getField(move, "node");
      if (node == targetNode) {
        return point;
      }
    }
    return null;
  }

  private static List<?> buildQuickOverviewMoves(WinrateGraph graph, BoardHistoryNode currentNode)
      throws Exception {
    Method method =
        WinrateGraph.class.getDeclaredMethod("buildQuickOverviewMoves", BoardHistoryNode.class);
    method.setAccessible(true);
    return (List<?>) method.invoke(graph, currentNode);
  }

  private static Object quickOverviewMoveForNode(
      WinrateGraph graph, BoardHistoryNode currentNode, BoardHistoryNode targetNode)
      throws Exception {
    List<?> moves = buildQuickOverviewMoves(graph, currentNode);
    for (Object move : moves) {
      BoardHistoryNode node = (BoardHistoryNode) getField(move, "node");
      if (node == targetNode) {
        return move;
      }
    }
    return null;
  }

  private static boolean quickOverviewMoveHasAnalysis(Object move) throws Exception {
    return (boolean) getField(move, "hasAnalysis");
  }

  private static double quickOverviewMoveWinrate(Object move) throws Exception {
    return (double) getField(move, "winrate");
  }

  private static BoardHistoryNode resolveTargetNode(WinrateGraph graph, int x, int y)
      throws Exception {
    Method resolve =
        WinrateGraph.class.getDeclaredMethod("resolveMoveTargetNode", int.class, int.class);
    resolve.setAccessible(true);
    return (BoardHistoryNode) resolve.invoke(graph, x, y);
  }

  private static void renderGraph(WinrateGraph graph) {
    BufferedImage winrateLayer =
        new BufferedImage(ORIG_GRAPH_WIDTH, ORIG_GRAPH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    BufferedImage blunderLayer =
        new BufferedImage(ORIG_GRAPH_WIDTH, ORIG_GRAPH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    BufferedImage backgroundLayer =
        new BufferedImage(ORIG_GRAPH_WIDTH, ORIG_GRAPH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    Graphics2D winrateGraphics = winrateLayer.createGraphics();
    Graphics2D blunderGraphics = blunderLayer.createGraphics();
    Graphics2D backgroundGraphics = backgroundLayer.createGraphics();
    try {
      graph.draw(
          winrateGraphics,
          blunderGraphics,
          backgroundGraphics,
          0,
          0,
          ORIG_GRAPH_WIDTH,
          ORIG_GRAPH_HEIGHT);
    } finally {
      winrateGraphics.dispose();
      blunderGraphics.dispose();
      backgroundGraphics.dispose();
    }
  }

  private static void applyProductionHints(Graphics2D graphics) {
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
  }

  private static void assertPixelRenderedAt(WinrateGraph graph, int x, int y) {
    BufferedImage winrateLayer =
        new BufferedImage(ORIG_GRAPH_WIDTH, ORIG_GRAPH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    BufferedImage blunderLayer =
        new BufferedImage(ORIG_GRAPH_WIDTH, ORIG_GRAPH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    BufferedImage backgroundLayer =
        new BufferedImage(ORIG_GRAPH_WIDTH, ORIG_GRAPH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    Graphics2D winrateGraphics = winrateLayer.createGraphics();
    Graphics2D blunderGraphics = blunderLayer.createGraphics();
    Graphics2D backgroundGraphics = backgroundLayer.createGraphics();
    try {
      graph.draw(
          winrateGraphics,
          blunderGraphics,
          backgroundGraphics,
          0,
          0,
          ORIG_GRAPH_WIDTH,
          ORIG_GRAPH_HEIGHT);
    } finally {
      winrateGraphics.dispose();
      blunderGraphics.dispose();
      backgroundGraphics.dispose();
    }
    int alpha = (winrateLayer.getRGB(x, y) >>> 24) & 0xff;
    assertTrue(alpha > 0, "quick overview anchor pixel should be rendered foreground.");
  }

  private static Object getField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class VariationFixture {
    private final TrackingBoard board;
    private final BoardHistoryNode variationStart;

    private VariationFixture(TrackingBoard board, BoardHistoryNode variationStart) {
      this.board = board;
      this.variationStart = variationStart;
    }
  }

  private static final class SnapshotFixture {
    private final TrackingBoard board;
    private final BoardHistoryNode snapshotBoundary;

    private SnapshotFixture(TrackingBoard board, BoardHistoryNode snapshotBoundary) {
      this.board = board;
      this.snapshotBoundary = snapshotBoundary;
    }
  }

  private static final class HeaderOnlyFixture {
    private final TrackingBoard board;
    private final BoardHistoryNode node;
    private final double expectedDisplayedWinrate;

    private HeaderOnlyFixture(
        TrackingBoard board, BoardHistoryNode node, double expectedDisplayedWinrate) {
      this.board = board;
      this.node = node;
      this.expectedDisplayedWinrate = expectedDisplayedWinrate;
    }
  }

  private static final class TrackingBoard extends Board {
    @Override
    public boolean nextMove(boolean needRefresh) {
      if (getHistory().getNext().isPresent()) {
        getHistory().next();
        return true;
      }
      return false;
    }

    @Override
    public boolean previousMove(boolean needRefresh) {
      if (getHistory().getPrevious().isPresent()) {
        getHistory().previous();
        return true;
      }
      return false;
    }

    @Override
    public boolean goToMoveNumberBeyondBranch(int moveNumber) {
      BoardHistoryList history = getHistory();
      if (moveNumber > history.currentBranchLength() && moveNumber <= history.mainTrunkLength()) {
        history.goToMoveNumber(0, false);
      }
      return history.goToMoveNumber(moveNumber, false);
    }

    @Override
    public boolean goToMoveNumberWithinBranch(int moveNumber) {
      return getHistory().goToMoveNumber(moveNumber, true);
    }

    @Override
    public void clearAfterMove() {}
  }

  private static final class TrackingFrame extends LizzieFrame {
    @Override
    public void repaint() {}

    @Override
    public void refresh() {}
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final WinrateGraph previousWinrateGraph;
    private final Leelaz previousLeelaz;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame,
        WinrateGraph previousWinrateGraph,
        Leelaz previousLeelaz) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousWinrateGraph = previousWinrateGraph;
      this.previousLeelaz = previousLeelaz;
    }

    private static TestEnvironment open() throws Exception {
      TestEnvironment env =
          new TestEnvironment(
              Board.boardWidth,
              Board.boardHeight,
              Lizzie.config,
              Lizzie.board,
              Lizzie.frame,
              LizzieFrame.winrateGraph,
              Lizzie.leelaz);
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      Config config = allocate(Config.class);
      config.showWinrateGraph = true;
      config.showWinrateLine = true;
      Lizzie.config = config;
      Lizzie.leelaz = allocate(Leelaz.class);
      Lizzie.board = null;
      Lizzie.frame = null;
      LizzieFrame.winrateGraph = null;
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
      LizzieFrame.winrateGraph = previousWinrateGraph;
      Lizzie.leelaz = previousLeelaz;
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
