package featurecat.lizzie.gui;

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
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WinrateGraphVariationHitTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;
  private static final int GRAPH_WIDTH = 100;
  private static final int GRAPH_HEIGHT = 20;
  private static final int GRAPH_NUM_MOVES = 4;
  private static final int RENDER_WIDTH = 240;
  private static final int RENDER_HEIGHT = 120;
  private static final double FALLBACK_CURRENT_WINRATE = 50.0;

  @Test
  void hoverKeepsVariationCurrentNodeReachable() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      boolean handled =
          frame.processMouseMoveOnWinrateGraph(
              renderedGraphPoint(graph, fixture.variationCurrent)[0],
              renderedGraphPoint(graph, fixture.variationCurrent)[1]);

      assertTrue(handled, "variation current node should still be hittable in the winrate graph.");
      assertSame(
          fixture.variationCurrent,
          graph.mouseOverNode,
          "hover should keep resolving the current variation node.");
    } finally {
      env.close();
    }
  }

  @Test
  void hoverHitsVisibleMainTrunkContinuationBeyondVariation() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] point = renderedGraphPoint(graph, fixture.mainFuture);
      boolean handled = frame.processMouseMoveOnWinrateGraph(point[0], point[1]);

      assertTrue(
          handled, "visible main-trunk continuation should be hittable from a variation view.");
      assertSame(
          fixture.mainFuture,
          graph.mouseOverNode,
          "hover should resolve the main-trunk node that remains visible after the fork.");
    } finally {
      env.close();
    }
  }

  @Test
  void hoverDistinguishesMainAndVariationNodesAtSharedMoveNumberColumns() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      assertHoverTarget(
          frame,
          graph,
          renderedGraphPoint(graph, fixture.mainThird),
          fixture.mainThird,
          "move 3 main-trunk point should stay selectable inside the overlap column.");
      assertHoverTarget(
          frame,
          graph,
          renderedGraphPoint(graph, fixture.variationStart),
          fixture.variationStart,
          "move 3 variation point should stay selectable inside the overlap column.");
      assertHoverTarget(
          frame,
          graph,
          renderedGraphPoint(graph, fixture.mainFourth),
          fixture.mainFourth,
          "move 4 main-trunk point should stay selectable inside the overlap column.");
      assertHoverTarget(
          frame,
          graph,
          renderedGraphPoint(graph, fixture.variationCurrent),
          fixture.variationCurrent,
          "move 4 variation point should stay selectable inside the overlap column.");
    } finally {
      env.close();
    }
  }

  @Test
  void clickJumpsToVisibleMainTrunkContinuationBeyondVariation() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] point = renderedGraphPoint(graph, fixture.mainFuture);
      frame.onClickedWinrateOnly(point[0], point[1]);

      assertSame(
          fixture.mainFuture,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "click should jump from the variation to the visible main-trunk continuation.");
    } finally {
      env.close();
    }
  }

  @Test
  void dragStaysWithinVariationWhenTargetColumnBelongsToVisibleMainTrunk() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] point = renderedGraphPoint(graph, fixture.mainFuture);
      frame.onMouseDragged(point[0], point[1]);

      assertSame(
          fixture.variationCurrent,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "drag should keep within-branch semantics and stay on the current variation.");
    } finally {
      env.close();
    }
  }

  @Test
  void dragStopsAtVariationStartWhenForkPixelIsHoveredAndClicked() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      BoardHistoryNode forkNode = fixture.variationStart.previous().get();
      int[] pixel = renderedVisiblePixelNearNode(graph, forkNode);
      fixture.board.getHistory().setHead(fixture.variationCurrent);
      graph.clearMouseOverNode();
      assertTrue(
          frame.processMouseMoveOnWinrateGraph(pixel[0], pixel[1]),
          "visible fork pixel should be hover-hit.");
      assertSame(forkNode, graph.mouseOverNode, "visible fork pixel hover target mismatch.");

      fixture.board.getHistory().setHead(fixture.variationCurrent);
      frame.onClickedWinrateOnly(pixel[0], pixel[1]);
      assertSame(
          forkNode,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "visible fork pixel click target mismatch.");

      fixture.board.getHistory().setHead(fixture.variationCurrent);
      frame.onMouseDragged(pixel[0], pixel[1]);
      assertSame(
          fixture.variationStart,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "visible fork pixel drag should stop at variation start.");
    } finally {
      env.close();
    }
  }

  @Test
  void dragStopsAtVariationStartWhenAncestorPixelBeforeForkIsTargeted() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      BoardHistoryNode ancestorNode = fixture.variationStart.previous().get().previous().get();
      int[] pixel = renderedVisiblePixelNearNode(graph, ancestorNode);
      fixture.board.getHistory().setHead(fixture.variationCurrent);
      graph.clearMouseOverNode();
      assertTrue(
          frame.processMouseMoveOnWinrateGraph(pixel[0], pixel[1]),
          "visible ancestor pixel should be hover-hit.");
      assertSame(
          ancestorNode, graph.mouseOverNode, "visible ancestor pixel hover target mismatch.");

      fixture.board.getHistory().setHead(fixture.variationCurrent);
      frame.onClickedWinrateOnly(pixel[0], pixel[1]);
      assertSame(
          ancestorNode,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "visible ancestor pixel click target mismatch.");

      fixture.board.getHistory().setHead(fixture.variationCurrent);
      frame.onMouseDragged(pixel[0], pixel[1]);
      assertSame(
          fixture.variationStart,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "visible ancestor pixel drag should stop at variation start.");
    } finally {
      env.close();
    }
  }

  @Test
  void tryingModeVariationStartPixelResolvesSameNodeForHoverClickAndDrag() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.isTrying = true;
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] pixel = renderedVisiblePixelNearNode(graph, fixture.variationStart);
      assertHoverClickDragResolveSameTarget(
          fixture,
          graph,
          frame,
          pixel,
          fixture.variationStart,
          "trying mode variation-start visible pixel");
    } finally {
      env.close();
    }
  }

  @Test
  void tryingModeVariationCurrentAnchorPixelInsideOverviewMissesHoverClickAndDrag()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.isTrying = true;
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      renderGraph(graph);
      int[] pixel = renderedGraphPoint(graph, fixture.variationCurrent);
      assertHoverClickDragMiss(
          fixture, graph, frame, pixel, "trying mode variation-current anchor");
    } finally {
      env.close();
    }
  }

  @Test
  void
      tryingModeVariationCurrentInsideOverviewClearsOpaqueMainGraphPixelAndMissesHoverClickAndDrag()
          throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.isTrying = true;
      Lizzie.config.showWinrateLine = true;
      Lizzie.config.winrateLineColor = new Color(100, 180, 255);
      Lizzie.config.winrateMissLineColor = new Color(120, 120, 120);
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] pixel = renderedOpaqueGraphPixelNearNode(graph, fixture.variationCurrent);
      assertNull(
          pixel,
          "overview occlusion should clear opaque main-graph pixels near variation current.");
      int[] anchorPoint = renderedGraphPoint(graph, fixture.variationCurrent);
      assertHoverClickDragMiss(
          fixture, graph, frame, anchorPoint, "trying mode variation-current visible");
    } finally {
      env.close();
    }
  }

  @Test
  void clickJumpsToVisibleMainTrunkContinuationBeyondSnapshotNumberedVariation() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithSnapshotNumberedVariation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] point = renderedGraphPoint(graph, fixture.mainFuture);
      frame.onClickedWinrateOnly(point[0], point[1]);

      assertSame(
          fixture.mainFuture,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "click should follow the real target node even when snapshot numbering inflates branch length.");
    } finally {
      env.close();
    }
  }

  @Test
  void dragStaysWithinSnapshotNumberedVariationWhenMainTrunkTargetIsVisible() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithSnapshotNumberedVariation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      int[] point = renderedGraphPoint(graph, fixture.mainFuture);
      frame.onMouseDragged(point[0], point[1]);

      assertSame(
          fixture.variationCurrent,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "drag should stay inside the numbered variation instead of jumping to visible main trunk.");
    } finally {
      env.close();
    }
  }

  @Test
  void quickOverviewColumnScrubResolvesTargetAwayFromDotAnchor() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = new WinrateGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      renderGraph(graph);
      int[] point = renderedQuickOverviewPoint(graph, fixture.mainFuture);
      assertNotNull(point, "quick overview should expose a rendered point for target node.");
      int[] scrubPixel = quickOverviewColumnScrubPixel(graph, point);

      BoardHistoryNode resolved = graph.resolveMoveTargetNode(scrubPixel[0], scrubPixel[1]);

      assertSame(
          fixture.mainFuture,
          resolved,
          "quick overview should resolve by column even when cursor is away from dot anchor.");
    } finally {
      env.close();
    }
  }

  @Test
  void hoverIgnoresRenderedVariationFallbackPixelWhenNoAnchorExists() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithFallbackVariationCurrent();
      WinrateGraph graph = new WinrateGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;
      Lizzie.config.showWinrateLine = false;

      int[] point =
          renderedCurrentDotLeftPixel(graph, fixture.variationCurrent, FALLBACK_CURRENT_WINRATE);

      graph.clearMouseOverNode();
      boolean handled = frame.processMouseMoveOnWinrateGraph(point[0], point[1]);

      assertFalse(
          handled,
          "hover should ignore fallback curve pixels when no rendered anchor exists for the node.");
      assertSame(null, graph.mouseOverNode, "hover target should stay empty for fallback pixel.");
    } finally {
      env.close();
    }
  }

  @Test
  void showWinrateLineDisabledRemovesMainGraphAnchorsAndHitTargets() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      VariationFixture fixture = boardWithVisibleMainContinuation();
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = fixture.board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;
      Lizzie.config.showWinrateLine = false;

      renderGraph(graph);
      assertTrue(
          currentGraphPoints(graph).isEmpty(),
          "showWinrateLine=false should suppress all main-graph anchor points.");
      assertFalse(
          hasAnyMainGraphHit(graph),
          "showWinrateLine=false should suppress all main-graph hit targets.");
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
    BoardHistoryNode mainThird = history.getCurrentHistoryNode();
    history.add(moveNode(0, 1, Stone.WHITE, true, 4, 48));
    BoardHistoryNode mainFourth = history.getCurrentHistoryNode();
    history.add(moveNode(1, 1, Stone.BLACK, false, 5, 62));
    BoardHistoryNode mainFuture = history.getCurrentHistoryNode();

    history.setHead(fork);
    BoardHistoryNode variationStart = fork.addAtLast(moveNode(2, 1, Stone.BLACK, false, 3, 41));
    BoardHistoryNode variationCurrent =
        variationStart.addAtLast(moveNode(0, 2, Stone.WHITE, true, 4, 36));
    history.setHead(variationCurrent);
    board.setHistory(history);
    return new VariationFixture(
        board, variationStart, variationCurrent, mainThird, mainFourth, mainFuture);
  }

  private static VariationFixture boardWithSnapshotNumberedVariation() throws Exception {
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
    BoardHistoryNode mainFuture = history.getCurrentHistoryNode();

    history.setHead(fork);
    BoardHistoryNode variationStart =
        fork.addAtLast(snapshotNode(Optional.of(new int[] {2, 1}), Stone.BLACK, false, 10, 41));
    BoardHistoryNode variationCurrent =
        variationStart.addAtLast(moveNode(0, 2, Stone.WHITE, true, 11, 36));
    history.setHead(variationCurrent);
    board.setHistory(history);
    return new VariationFixture(board, variationStart, variationCurrent, null, null, mainFuture);
  }

  private static VariationFixture boardWithFallbackVariationCurrent() throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveNode(0, 0, Stone.BLACK, false, 1, 60));
    history.add(moveNode(1, 0, Stone.WHITE, true, 2, 55));
    BoardHistoryNode fork = history.getCurrentHistoryNode();
    history.add(moveNode(2, 0, Stone.BLACK, false, 3, 10));
    BoardHistoryNode mainThird = history.getCurrentHistoryNode();
    history.add(moveNode(0, 1, Stone.WHITE, true, 4, 20));
    BoardHistoryNode mainFourth = history.getCurrentHistoryNode();
    history.add(moveNode(1, 1, Stone.BLACK, false, 5, 15));
    BoardHistoryNode mainFuture = history.getCurrentHistoryNode();

    history.setHead(fork);
    BoardHistoryNode variationStart = fork.addAtLast(moveNode(2, 1, Stone.BLACK, false, 3, 41));
    BoardHistoryNode variationCurrent =
        variationStart.addAtLast(moveNode(0, 2, Stone.WHITE, true, 4, -1));
    history.setHead(variationCurrent);
    board.setHistory(history);
    return new VariationFixture(
        board, variationStart, variationCurrent, mainThird, mainFourth, mainFuture);
  }

  private static WinrateGraph configuredGraph() throws Exception {
    WinrateGraph graph = new WinrateGraph();
    setField(graph, "origParams", new int[] {0, 0, GRAPH_WIDTH, GRAPH_HEIGHT});
    setField(graph, "params", new int[] {0, 0, GRAPH_WIDTH, GRAPH_HEIGHT, GRAPH_NUM_MOVES});
    return graph;
  }

  private static void assertHoverTarget(
      TrackingFrame frame,
      WinrateGraph graph,
      int[] point,
      BoardHistoryNode expectedNode,
      String message) {
    boolean handled = frame.processMouseMoveOnWinrateGraph(point[0], point[1]);
    assertTrue(handled, message);
    assertSame(expectedNode, graph.mouseOverNode, message);
  }

  private static int[] renderedGraphPoint(WinrateGraph graph, BoardHistoryNode node)
      throws Exception {
    Method method =
        WinrateGraph.class.getDeclaredMethod("renderedGraphPoint", BoardHistoryNode.class);
    method.setAccessible(true);
    int[] point = (int[]) method.invoke(graph, node);
    if (point == null) {
      primeRenderedPointSources(graph);
      point = (int[]) method.invoke(graph, node);
    }
    assertNotNull(point, "expected rendered graph anchor for target node.");
    return point;
  }

  private static int[] renderedQuickOverviewPoint(WinrateGraph graph, BoardHistoryNode node)
      throws Exception {
    Method method =
        WinrateGraph.class.getDeclaredMethod("renderedQuickOverviewPoint", BoardHistoryNode.class);
    method.setAccessible(true);
    int[] point = (int[]) method.invoke(graph, node);
    if (point == null) {
      renderGraph(graph);
      point = (int[]) method.invoke(graph, node);
    }
    return point;
  }

  private static int[] quickOverviewColumnScrubPixel(WinrateGraph graph, int[] point)
      throws Exception {
    Object layout = getField(graph, "renderedQuickOverviewLayout");
    if (layout == null) {
      throw new AssertionError("expected rendered quick overview layout.");
    }
    int overviewY = (int) readField(layout, "overviewY");
    int overviewHeight = (int) readField(layout, "overviewHeight");
    int dotSize = (int) readField(layout, "dotSize");
    int minY = overviewY;
    int maxY = overviewY + overviewHeight - 1;
    int up = point[1] - dotSize - 2;
    if (up >= minY) {
      return new int[] {point[0], up};
    }
    int down = point[1] + dotSize + 2;
    if (down <= maxY) {
      return new int[] {point[0], down};
    }
    int top = minY;
    if (Math.abs(top - point[1]) > dotSize / 2) {
      return new int[] {point[0], top};
    }
    int bottom = maxY;
    if (Math.abs(bottom - point[1]) > dotSize / 2) {
      return new int[] {point[0], bottom};
    }
    throw new AssertionError("expected quick overview scrub pixel away from dot anchor.");
  }

  private static void primeRenderedPointSources(WinrateGraph graph) throws Exception {
    Class<?> layoutClass = Class.forName("featurecat.lizzie.gui.WinrateGraph$QuickOverviewLayout");
    Method method =
        WinrateGraph.class.getDeclaredMethod("rememberRenderedPointSources", layoutClass);
    method.setAccessible(true);
    method.invoke(graph, new Object[] {null});
  }

  private static int[] renderedCurrentDotLeftPixel(
      WinrateGraph graph, BoardHistoryNode node, double displayedWinrate) throws Exception {
    BufferedImage winrateLayer = renderGraph(graph);
    int[] graphParams = (int[]) getField(graph, "params");
    int centerX =
        graphParams[0] + (node.getData().moveNumber - 1) * graphParams[2] / graphParams[4];
    int centerY = graphParams[1] + graphParams[3] - (int) (displayedWinrate * graphParams[3] / 100);
    return opaquePixelInsideLeftHalf(winrateLayer, centerX, centerY);
  }

  private static int[] renderedVisiblePixelNearNode(WinrateGraph graph, BoardHistoryNode node)
      throws Exception {
    graph.setMouseOverNode(node);
    BufferedImage winrateLayer = renderGraph(graph);
    int[] center = renderedGraphPoint(graph, node);
    return foregroundPixelResolvingToNode(graph, node, winrateLayer, center[0], center[1]);
  }

  private static int[] renderedOpaqueGraphPixelNearNode(WinrateGraph graph, BoardHistoryNode node)
      throws Exception {
    graph.clearMouseOverNode();
    BufferedImage winrateLayer = renderGraph(graph);
    int[] center = renderedGraphPoint(graph, node);
    int minX = Math.max(0, center[0] - 2);
    int maxX = Math.min(winrateLayer.getWidth() - 1, center[0] + 2);
    int minY = Math.max(0, center[1] - 2);
    int maxY = Math.min(winrateLayer.getHeight() - 1, center[1] + 2);
    for (int y = minY; y <= maxY; y++) {
      for (int x = minX; x <= maxX; x++) {
        Color pixel = new Color(winrateLayer.getRGB(x, y), true);
        if (pixel.getAlpha() >= 250) {
          return new int[] {x, y};
        }
      }
    }
    return null;
  }

  private static BufferedImage renderGraph(WinrateGraph graph) {
    BufferedImage winrateLayer =
        new BufferedImage(RENDER_WIDTH, RENDER_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    BufferedImage blunderLayer =
        new BufferedImage(RENDER_WIDTH, RENDER_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    BufferedImage backgroundLayer =
        new BufferedImage(RENDER_WIDTH, RENDER_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    Graphics2D winrateGraphics = winrateLayer.createGraphics();
    Graphics2D blunderGraphics = blunderLayer.createGraphics();
    Graphics2D backgroundGraphics = backgroundLayer.createGraphics();
    try {
      graph.draw(
          winrateGraphics, blunderGraphics, backgroundGraphics, 0, 0, RENDER_WIDTH, RENDER_HEIGHT);
      return winrateLayer;
    } finally {
      winrateGraphics.dispose();
      blunderGraphics.dispose();
      backgroundGraphics.dispose();
    }
  }

  private static List<?> currentGraphPoints(WinrateGraph graph) throws Exception {
    Method method = WinrateGraph.class.getDeclaredMethod("currentGraphPoints");
    method.setAccessible(true);
    return (List<?>) method.invoke(graph);
  }

  private static boolean hasAnyMainGraphHit(WinrateGraph graph) {
    for (int y = 0; y < GRAPH_HEIGHT; y++) {
      for (int x = 0; x < GRAPH_WIDTH; x++) {
        if (graph.resolveMoveTargetNode(x, y) != null) {
          return true;
        }
      }
    }
    return false;
  }

  private static void assertHoverClickDragResolveSameTarget(
      VariationFixture fixture,
      WinrateGraph graph,
      TrackingFrame frame,
      int[] pixel,
      BoardHistoryNode expectedNode,
      String label) {
    fixture.board.getHistory().setHead(fixture.variationCurrent);
    graph.clearMouseOverNode();
    boolean handled = frame.processMouseMoveOnWinrateGraph(pixel[0], pixel[1]);
    assertTrue(handled, label + " should be hover-hit.");
    assertSame(expectedNode, graph.mouseOverNode, label + " hover target mismatch.");

    fixture.board.getHistory().setHead(fixture.variationCurrent);
    frame.onClickedWinrateOnly(pixel[0], pixel[1]);
    assertSame(
        expectedNode,
        fixture.board.getHistory().getCurrentHistoryNode(),
        label + " click target mismatch.");

    fixture.board.getHistory().setHead(fixture.variationCurrent);
    frame.onMouseDragged(pixel[0], pixel[1]);
    assertSame(
        expectedNode,
        fixture.board.getHistory().getCurrentHistoryNode(),
        label + " drag target mismatch.");
  }

  private static void assertHoverClickDragMiss(
      VariationFixture fixture,
      WinrateGraph graph,
      TrackingFrame frame,
      int[] pixel,
      String label) {
    fixture.board.getHistory().setHead(fixture.variationCurrent);
    BoardHistoryNode start = fixture.board.getHistory().getCurrentHistoryNode();
    graph.clearMouseOverNode();
    boolean handled = frame.processMouseMoveOnWinrateGraph(pixel[0], pixel[1]);
    assertFalse(handled, label + " pixel should miss hover.");
    assertSame(null, graph.mouseOverNode, label + " hover target should stay empty.");

    fixture.board.getHistory().setHead(start);
    frame.onClickedWinrateOnly(pixel[0], pixel[1]);
    assertSame(
        start,
        fixture.board.getHistory().getCurrentHistoryNode(),
        label + " pixel should miss click.");

    fixture.board.getHistory().setHead(start);
    frame.onMouseDragged(pixel[0], pixel[1]);
    assertSame(
        start,
        fixture.board.getHistory().getCurrentHistoryNode(),
        label + " pixel should miss drag.");
  }

  private static int[] opaquePixelInsideLeftHalf(BufferedImage image, int centerX, int centerY) {
    for (int radius = 0; radius <= 4; radius++) {
      int up = centerY - radius;
      if (up >= 0) {
        for (int x = Math.max(0, centerX - 3); x <= centerX; x++) {
          Color pixel = new Color(image.getRGB(x, up), true);
          if (pixel.getAlpha() > 0) {
            return new int[] {x, up};
          }
        }
      }
      int down = centerY + radius;
      if (down < image.getHeight()) {
        for (int x = Math.max(0, centerX - 3); x <= centerX; x++) {
          Color pixel = new Color(image.getRGB(x, down), true);
          if (pixel.getAlpha() > 0) {
            return new int[] {x, down};
          }
        }
      }
    }
    throw new AssertionError("expected the rendered current point to paint an opaque pixel.");
  }

  private static int[] foregroundPixelResolvingToNode(
      WinrateGraph graph,
      BoardHistoryNode expectedNode,
      BufferedImage layer,
      int centerX,
      int centerY) {
    for (int radius = 0; radius <= 10; radius++) {
      int minX = Math.max(0, centerX - 3 - radius);
      int maxX = Math.min(layer.getWidth() - 1, centerX + radius);
      int minY = Math.max(0, centerY - radius);
      int maxY = Math.min(layer.getHeight() - 1, centerY + radius);
      for (int y = minY; y <= maxY; y++) {
        for (int x = minX; x <= maxX; x++) {
          Color pixel = new Color(layer.getRGB(x, y), true);
          if (pixel.getAlpha() == 0) {
            continue;
          }
          BoardHistoryNode resolved = graph.resolveMoveTargetNode(x, y);
          if (resolved == expectedNode) {
            return new int[] {x, y};
          }
        }
      }
    }
    throw new AssertionError("expected a foreground pixel that resolves to the target node.");
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

  private static BoardData snapshotNode(
      Optional<int[]> lastMove,
      Stone lastMoveColor,
      boolean blackToPlay,
      int moveNumber,
      double winrate) {
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
        winrate,
        1);
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

  private static Object getField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
  }

  private static Object readField(Object target, String fieldName) throws Exception {
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
    private final BoardHistoryNode variationCurrent;
    private final BoardHistoryNode mainThird;
    private final BoardHistoryNode mainFourth;
    private final BoardHistoryNode mainFuture;

    private VariationFixture(
        TrackingBoard board,
        BoardHistoryNode variationStart,
        BoardHistoryNode variationCurrent,
        BoardHistoryNode mainThird,
        BoardHistoryNode mainFourth,
        BoardHistoryNode mainFuture) {
      this.board = board;
      this.variationStart = variationStart;
      this.variationCurrent = variationCurrent;
      this.mainThird = mainThird;
      this.mainFourth = mainFourth;
      this.mainFuture = mainFuture;
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
