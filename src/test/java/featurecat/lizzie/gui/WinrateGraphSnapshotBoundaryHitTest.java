package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WinrateGraphSnapshotBoundaryHitTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;
  private static final int GRAPH_WIDTH = 100;
  private static final int GRAPH_HEIGHT = 20;
  private static final int GRAPH_NUM_MOVES = 4;

  @Test
  void moveNumberSnapsGapHitsToSnapshotAnchor() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingBoard board = boardWithSnapshotGapHistory();
      Lizzie.board = board;
      WinrateGraph graph = configuredGraph();
      BoardHistoryNode snapshotBoundary = snapshotBoundaryNode(board);
      int[] snapshotPoint = renderedGraphPoint(graph, snapshotBoundary);
      assertNotNull(snapshotPoint, "snapshot boundary should keep a rendered graph anchor.");
      int[] params = (int[]) getField(graph, "params");
      assertEquals(
          graphCenterX(params, snapshotBoundary.getData().moveNumber),
          snapshotPoint[0],
          "SNAPSHOT boundary should remain on the real moveNumber column.");

      BoardHistoryNode targetNode = resolveTargetNode(graph, snapshotPoint[0], snapshotPoint[1]);

      assertEquals(
          4,
          targetNode.getData().moveNumber,
          "main graph hits should snap to the SNAPSHOT anchor instead of a missing gap move.");
      assertTrue(targetNode.getData().isSnapshotNode(), "gap hits should resolve to SNAPSHOT.");
    } finally {
      env.close();
    }
  }

  @Test
  void moveNumberColumnScrubSnapsGapHitsToSnapshotAnchor() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingBoard board = boardWithSnapshotGapHistory();
      Lizzie.board = board;
      WinrateGraph graph = configuredGraph();
      BoardHistoryNode snapshotBoundary = snapshotBoundaryNode(board);
      int[] snapshotPoint = renderedGraphPoint(graph, snapshotBoundary);
      assertNotNull(snapshotPoint, "snapshot boundary should keep a rendered graph anchor.");

      int scrubY = 0;
      BoardHistoryNode targetNode = resolveTargetNode(graph, snapshotPoint[0], scrubY);

      assertNotNull(targetNode, "column scrub should still resolve a target node.");
      assertEquals(
          snapshotBoundary.getData().moveNumber,
          targetNode.getData().moveNumber,
          "column scrub should resolve to SNAPSHOT boundary on the same move-number column.");
      assertTrue(
          targetNode.getData().isSnapshotNode(), "column scrub target should stay SNAPSHOT.");
    } finally {
      env.close();
    }
  }

  @Test
  void hoverTargetsSnapshotAnchorInsteadOfOvershootingGap() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingBoard board = boardWithSnapshotGapHistory();
      Lizzie.board = board;
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      LizzieFrame.winrateGraph = graph;
      Lizzie.frame = frame;
      int[] snapshotPoint = renderedGraphPoint(graph, snapshotBoundaryNode(board));
      assertNotNull(snapshotPoint, "snapshot boundary should keep a rendered graph anchor.");

      boolean handled = frame.processMouseMoveOnWinrateGraph(snapshotPoint[0], snapshotPoint[1]);

      assertTrue(handled, "graph hover should resolve a hit inside the winrate graph.");
      assertEquals(
          4,
          graph.mouseOverNode.getData().moveNumber,
          "hover should target the SNAPSHOT boundary node instead of stepping into a fake gap move.");
      assertTrue(
          graph.mouseOverNode.getData().isSnapshotNode(),
          "hover should stop on the SNAPSHOT anchor.");
    } finally {
      env.close();
    }
  }

  @Test
  void clickJumpsToSnapshotAnchorInsteadOfFakeIntermediateMove() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingBoard board = boardWithSnapshotGapHistory();
      Lizzie.board = board;
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      LizzieFrame.winrateGraph = graph;
      Lizzie.frame = frame;
      int[] snapshotPoint = renderedGraphPoint(graph, snapshotBoundaryNode(board));
      assertNotNull(snapshotPoint, "snapshot boundary should keep a rendered graph anchor.");

      frame.onClickedWinrateOnly(snapshotPoint[0], snapshotPoint[1]);

      assertEquals(
          4,
          board.getHistory().getCurrentHistoryNode().getData().moveNumber,
          "click navigation should stop at the SNAPSHOT anchor instead of inventing missing moves.");
      assertTrue(
          board.getHistory().getCurrentHistoryNode().getData().isSnapshotNode(),
          "click navigation should land on the SNAPSHOT anchor.");
    } finally {
      env.close();
    }
  }

  @Test
  void drawPixelHitUsesRenderedMovePointAcrossSnapshotGap() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingBoard board = boardWithSnapshotGapHistory();
      WinrateGraph graph = new WinrateGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      BoardHistoryNode moveFive = board.getHistory().getMainEnd();
      int[] renderedMoveFivePixel = renderedHoverDotLeftPixel(graph, moveFive);

      graph.clearMouseOverNode();
      assertTrue(
          frame.processMouseMoveOnWinrateGraph(renderedMoveFivePixel[0], renderedMoveFivePixel[1]),
          "hover should accept a pixel that belongs to the rendered MOVE#5 point.");
      assertEquals(
          5,
          graph.mouseOverNode.getData().moveNumber,
          "hover should resolve the rendered MOVE#5 point instead of snapping into SNAPSHOT#4.");

      board.getHistory().goToMoveNumber(1, false);
      renderGraphLayer(graph);
      frame.onClickedWinrateOnly(renderedMoveFivePixel[0], renderedMoveFivePixel[1]);
      assertEquals(
          5,
          board.getHistory().getCurrentHistoryNode().getData().moveNumber,
          "click should follow the rendered MOVE#5 point across the SNAPSHOT jump.");

      board.getHistory().goToMoveNumber(1, false);
      renderGraphLayer(graph);
      frame.onMouseDragged(renderedMoveFivePixel[0], renderedMoveFivePixel[1]);
      assertEquals(
          5,
          board.getHistory().getCurrentHistoryNode().getData().moveNumber,
          "drag should follow the rendered MOVE#5 point across the SNAPSHOT jump.");
    } finally {
      env.close();
    }
  }

  @Test
  void preGapMoveHasVisibleForegroundPixelThatHitsPreGapNode() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingBoard board = boardWithSnapshotGapHistoryAtEnd(62, 78);
      WinrateGraph graph = new WinrateGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      BufferedImage winrateLayer = renderGraphLayer(graph);
      BoardHistoryNode preGapMove = snapshotBoundaryNode(board).previous().get();
      int[] anchor = renderedGraphPoint(graph, preGapMove);
      assertNotNull(anchor, "pre-gap move should keep a rendered hit anchor.");

      int[] visiblePixel =
          foregroundPixelResolvingToNode(graph, preGapMove, winrateLayer, anchor, 6);
      assertTrue(
          frame.processMouseMoveOnWinrateGraph(visiblePixel[0], visiblePixel[1]),
          "hover should consume a rendered pre-gap foreground pixel.");
      assertSame(preGapMove, graph.mouseOverNode, "hover should resolve the pre-gap MOVE node.");

      board.getHistory().setHead(board.getHistory().getMainEnd());
      frame.onClickedWinrateOnly(visiblePixel[0], visiblePixel[1]);
      assertSame(
          preGapMove,
          board.getHistory().getCurrentHistoryNode(),
          "click should jump to the pre-gap MOVE node from its rendered foreground pixel.");
    } finally {
      env.close();
    }
  }

  @Test
  void zeroPlayoutSnapshotBoundaryRemainsHittableByRenderedPixel() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingBoard board = boardWithSnapshotGapHistory();
      WinrateGraph graph = new WinrateGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      BoardHistoryNode snapshotBoundary = snapshotBoundaryNode(board);
      int[] renderedSnapshotPixel = renderedSnapshotBoundaryPixel(graph, snapshotBoundary);

      graph.clearMouseOverNode();
      assertTrue(
          frame.processMouseMoveOnWinrateGraph(renderedSnapshotPixel[0], renderedSnapshotPixel[1]),
          "hover should accept a rendered foreground pixel from zero-playout SNAPSHOT boundary.");
      assertEquals(
          snapshotBoundary.getData().moveNumber,
          graph.mouseOverNode.getData().moveNumber,
          "hover should resolve to the zero-playout SNAPSHOT boundary.");
      assertTrue(
          graph.mouseOverNode.getData().isSnapshotNode(),
          "hover should keep resolving to SNAPSHOT for zero-playout boundary.");

      board.getHistory().goToMoveNumber(1, false);
      renderGraphLayer(graph);
      frame.onClickedWinrateOnly(renderedSnapshotPixel[0], renderedSnapshotPixel[1]);
      assertEquals(
          snapshotBoundary.getData().moveNumber,
          board.getHistory().getCurrentHistoryNode().getData().moveNumber,
          "click should jump to zero-playout SNAPSHOT boundary.");

      board.getHistory().goToMoveNumber(1, false);
      renderGraphLayer(graph);
      frame.onMouseDragged(renderedSnapshotPixel[0], renderedSnapshotPixel[1]);
      assertEquals(
          snapshotBoundary.getData().moveNumber,
          board.getHistory().getCurrentHistoryNode().getData().moveNumber,
          "drag should jump to zero-playout SNAPSHOT boundary.");
    } finally {
      env.close();
    }
  }

  @Test
  void zeroPlayoutSnapshotEveryHittablePixelStaysWithinSnapshotColumn() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingBoard board = boardWithSnapshotGapHistoryAtEnd(40, 80);
      WinrateGraph graph = new WinrateGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      BoardHistoryNode snapshotBoundary = snapshotBoundaryNode(board);
      BufferedImage winrateLayer = renderGraphLayer(graph);
      int[] anchor = renderedGraphPoint(graph, snapshotBoundary);
      assertNotNull(anchor, "snapshot boundary should keep a rendered hit anchor.");

      assertAllHittablePixelsStayWithinSnapshotColumn(
          graph, snapshotBoundary, anchor[0], winrateLayer, anchor, 8);
    } finally {
      env.close();
    }
  }

  @Test
  void blankGraphBackgroundIgnoresHoverClickAndDrag() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingBoard board = boardWithSnapshotGapHistory();
      Lizzie.board = board;
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      LizzieFrame.winrateGraph = graph;
      Lizzie.frame = frame;

      int[] blankPixel = blankGraphPixel(graph);
      BoardHistoryNode start = board.getHistory().getCurrentHistoryNode();
      graph.clearMouseOverNode();

      boolean handled = frame.processMouseMoveOnWinrateGraph(blankPixel[0], blankPixel[1]);
      assertFalse(handled, "graph blank hover should not hit any anchor node.");
      assertSame(null, graph.mouseOverNode, "graph blank hover should keep mouseOver null.");

      frame.onClickedWinrateOnly(blankPixel[0], blankPixel[1]);
      assertSame(
          start,
          board.getHistory().getCurrentHistoryNode(),
          "graph blank click should keep current node unchanged.");

      frame.onMouseDragged(blankPixel[0], blankPixel[1]);
      assertSame(
          start,
          board.getHistory().getCurrentHistoryNode(),
          "graph blank drag should keep current node unchanged.");
    } finally {
      env.close();
    }
  }

  @Test
  void secondDragAndHoverStillHitWithoutRedrawAfterFirstDragChangesCurrentNode() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingBoard board = boardWithSnapshotGapHistory();
      Lizzie.board = board;
      WinrateGraph graph = configuredGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      LizzieFrame.winrateGraph = graph;
      Lizzie.frame = frame;

      BoardHistoryNode snapshotBoundary = snapshotBoundaryNode(board);
      BoardHistoryNode mainEnd = board.getHistory().getMainEnd();
      int[] snapshotPoint = renderedGraphPoint(graph, snapshotBoundary);
      int[] mainEndPoint = renderedGraphPoint(graph, mainEnd);
      assertNotNull(snapshotPoint, "snapshot boundary should keep a rendered graph anchor.");
      assertNotNull(mainEndPoint, "main end should keep a rendered graph anchor.");

      frame.onMouseDragged(snapshotPoint[0], snapshotPoint[1]);
      assertSame(
          snapshotBoundary,
          board.getHistory().getCurrentHistoryNode(),
          "first drag should move to snapshot boundary.");

      frame.onMouseDragged(mainEndPoint[0], mainEndPoint[1]);
      assertSame(
          mainEnd,
          board.getHistory().getCurrentHistoryNode(),
          "second drag should still resolve without requiring an intermediate redraw.");

      board.getHistory().setHead(snapshotBoundary);
      graph.clearMouseOverNode();
      boolean handled = frame.processMouseMoveOnWinrateGraph(mainEndPoint[0], mainEndPoint[1]);
      assertTrue(handled, "hover should still resolve after current node changed and no redraw.");
      assertSame(mainEnd, graph.mouseOverNode, "hover should still hit the rendered main-end point.");
    } finally {
      env.close();
    }
  }

  @Test
  void narrowGraphMidColumnBackgroundMissesAndNeighborColumnsDoNotCrossTalk() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingBoard board = boardWithLinearHistory(7);
      Lizzie.board = board;
      WinrateGraph graph = configuredGraph(28, GRAPH_HEIGHT, 7);

      BoardHistoryNode moveOne = board.getHistory().getStart().next().get();
      BoardHistoryNode moveTwo = moveOne.next().get();
      int xMoveOne = graphCenterX((int[]) getField(graph, "params"), moveOne.getData().moveNumber);
      int xMoveTwo = graphCenterX((int[]) getField(graph, "params"), moveTwo.getData().moveNumber);
      int midX = (xMoveOne + xMoveTwo) / 2;
      int y = GRAPH_HEIGHT / 2;

      BoardHistoryNode middleHit = resolveTargetNode(graph, midX, y);
      assertSame(null, middleHit, "blank background between dense columns should remain miss.");

      BoardHistoryNode nearMoveOne = resolveTargetNode(graph, xMoveOne + 1, y);
      assertSame(moveOne, nearMoveOne, "neighbor pixel near move-one column should resolve move one.");

      BoardHistoryNode nearMoveTwo = resolveTargetNode(graph, xMoveTwo - 1, y);
      assertSame(moveTwo, nearMoveTwo, "neighbor pixel near move-two column should resolve move two.");
    } finally {
      env.close();
    }
  }

  @Test
  void denseGraphRenderedAnchorEdgePixelRemainsHittableForResolveHoverClickAndDrag()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingBoard board = boardWithLinearHistory(50);
      WinrateGraph graph = new WinrateGraph();
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.board = board;
      Lizzie.frame = frame;
      LizzieFrame.winrateGraph = graph;

      BoardHistoryNode targetNode = nodeByMoveNumber(board, 25);
      BufferedImage winrateLayer = renderGraphLayer(graph);
      int[] anchor = renderedGraphPoint(graph, targetNode);
      assertNotNull(anchor, "target move should keep a rendered graph anchor.");
      int[] edgePixel = renderedAnchorEdgePixel(winrateLayer, anchor);

      BoardHistoryNode resolved = resolveTargetNode(graph, edgePixel[0], edgePixel[1]);
      assertSame(
          targetNode,
          resolved,
          "resolveMoveTargetNode should accept rendered anchor edge foreground pixels.");

      graph.clearMouseOverNode();
      boolean handled = frame.processMouseMoveOnWinrateGraph(edgePixel[0], edgePixel[1]);
      assertTrue(handled, "hover should accept rendered anchor edge foreground pixels.");
      assertSame(targetNode, graph.mouseOverNode, "hover should resolve to the target anchor node.");

      board.getHistory().goToMoveNumber(1, false);
      frame.onClickedWinrateOnly(edgePixel[0], edgePixel[1]);
      assertSame(
          targetNode,
          board.getHistory().getCurrentHistoryNode(),
          "click should navigate to the target node from rendered anchor edge foreground.");

      board.getHistory().goToMoveNumber(1, false);
      frame.onMouseDragged(edgePixel[0], edgePixel[1]);
      assertSame(
          targetNode,
          board.getHistory().getCurrentHistoryNode(),
          "drag should navigate to the target node from rendered anchor edge foreground.");

      int[] blankPixel = blankPixelNearAnchor(winrateLayer, graph, anchor, 6);
      assertSame(
          null,
          resolveTargetNode(graph, blankPixel[0], blankPixel[1]),
          "blank background near dense anchors should remain miss.");
    } finally {
      env.close();
    }
  }

  private static WinrateGraph configuredGraph() throws Exception {
    return configuredGraph(GRAPH_WIDTH, GRAPH_HEIGHT, GRAPH_NUM_MOVES);
  }

  private static WinrateGraph configuredGraph(int width, int height, int numMoves) throws Exception {
    WinrateGraph graph = new WinrateGraph();
    setField(graph, "origParams", new int[] {0, 0, width, height});
    setField(graph, "params", new int[] {0, 0, width, height, numMoves});
    primeRenderedPointSources(graph);
    return graph;
  }

  private static int[] renderedHoverDotLeftPixel(WinrateGraph graph, BoardHistoryNode hoverNode) {
    graph.setMouseOverNode(hoverNode);
    BufferedImage winrateLayer = new BufferedImage(240, 120, BufferedImage.TYPE_INT_ARGB);
    BufferedImage blunderLayer = new BufferedImage(240, 120, BufferedImage.TYPE_INT_ARGB);
    BufferedImage backgroundLayer = new BufferedImage(240, 120, BufferedImage.TYPE_INT_ARGB);
    Graphics2D winrateGraphics = winrateLayer.createGraphics();
    Graphics2D blunderGraphics = blunderLayer.createGraphics();
    Graphics2D backgroundGraphics = backgroundLayer.createGraphics();
    try {
      graph.draw(winrateGraphics, blunderGraphics, backgroundGraphics, 0, 0, 240, 120);
      int[] center = renderedGraphPoint(graph, hoverNode);
      return opaquePixelInsideLeftHalf(winrateLayer, center[0], center[1]);
    } finally {
      winrateGraphics.dispose();
      blunderGraphics.dispose();
      backgroundGraphics.dispose();
    }
  }

  private static int[] renderedSnapshotBoundaryPixel(
      WinrateGraph graph, BoardHistoryNode snapshotBoundary) {
    graph.setMouseOverNode(snapshotBoundary);
    BufferedImage winrateLayer = new BufferedImage(240, 120, BufferedImage.TYPE_INT_ARGB);
    BufferedImage blunderLayer = new BufferedImage(240, 120, BufferedImage.TYPE_INT_ARGB);
    BufferedImage backgroundLayer = new BufferedImage(240, 120, BufferedImage.TYPE_INT_ARGB);
    Graphics2D winrateGraphics = winrateLayer.createGraphics();
    Graphics2D blunderGraphics = blunderLayer.createGraphics();
    Graphics2D backgroundGraphics = backgroundLayer.createGraphics();
    try {
      graph.draw(winrateGraphics, blunderGraphics, backgroundGraphics, 0, 0, 240, 120);
      int[] center = renderedGraphPoint(graph, snapshotBoundary);
      assertNotNull(center, "zero-playout SNAPSHOT boundary should stay in rendered hit anchors.");
      return opaquePixelOnColumn(winrateLayer, center[0], center[1]);
    } finally {
      winrateGraphics.dispose();
      blunderGraphics.dispose();
      backgroundGraphics.dispose();
    }
  }

  private static int[] renderedGraphPoint(WinrateGraph graph, BoardHistoryNode node) {
    try {
      Method method =
          WinrateGraph.class.getDeclaredMethod("renderedGraphPoint", BoardHistoryNode.class);
      method.setAccessible(true);
      return (int[]) method.invoke(graph, node);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException("failed to read rendered graph point", ex);
    }
  }

  private static int[] opaquePixelInsideLeftHalf(BufferedImage image, int centerX, int centerY) {
    for (int x = Math.max(0, centerX - 2); x <= centerX; x++) {
      Color pixel = new Color(image.getRGB(x, centerY), true);
      if (pixel.getAlpha() > 0) {
        return new int[] {x, centerY};
      }
    }
    throw new AssertionError("expected the rendered hover point to paint an opaque pixel.");
  }

  private static int[] opaquePixelOnColumn(BufferedImage image, int x, int preferredY) {
    for (int radius = 0; radius < image.getHeight(); radius++) {
      int up = preferredY - radius;
      if (up >= 0) {
        Color pixel = new Color(image.getRGB(x, up), true);
        if (pixel.getAlpha() > 0) {
          return new int[] {x, up};
        }
      }
      int down = preferredY + radius;
      if (down < image.getHeight()) {
        Color pixel = new Color(image.getRGB(x, down), true);
        if (pixel.getAlpha() > 0) {
          return new int[] {x, down};
        }
      }
    }
    throw new AssertionError("expected the rendered foreground to include an opaque column pixel.");
  }

  private static int[] renderedAnchorEdgePixel(BufferedImage layer, int[] anchor) {
    int centerX = anchor[0];
    int centerY = anchor[1];
    int[] yCandidates = new int[] {centerY + 2, centerY - 2};
    for (int y : yCandidates) {
      if (y < 0 || y >= layer.getHeight()) continue;
      int minX = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      for (int x = Math.max(0, centerX - 2); x <= Math.min(layer.getWidth() - 1, centerX + 2); x++) {
        Color pixel = new Color(layer.getRGB(x, y), true);
        if (pixel.getAlpha() == 0) continue;
        minX = Math.min(minX, x);
        maxX = Math.max(maxX, x);
      }
      if (maxX != Integer.MIN_VALUE && maxX > centerX) {
        return new int[] {maxX, y};
      }
      if (minX != Integer.MAX_VALUE && minX < centerX) {
        return new int[] {minX, y};
      }
    }
    throw new AssertionError("expected rendered anchor edge foreground pixel.");
  }

  private static BufferedImage renderGraphLayer(WinrateGraph graph) {
    BufferedImage winrateLayer = new BufferedImage(240, 120, BufferedImage.TYPE_INT_ARGB);
    BufferedImage blunderLayer = new BufferedImage(240, 120, BufferedImage.TYPE_INT_ARGB);
    BufferedImage backgroundLayer = new BufferedImage(240, 120, BufferedImage.TYPE_INT_ARGB);
    Graphics2D winrateGraphics = winrateLayer.createGraphics();
    Graphics2D blunderGraphics = blunderLayer.createGraphics();
    Graphics2D backgroundGraphics = backgroundLayer.createGraphics();
    try {
      graph.draw(winrateGraphics, blunderGraphics, backgroundGraphics, 0, 0, 240, 120);
      return winrateLayer;
    } finally {
      winrateGraphics.dispose();
      blunderGraphics.dispose();
      backgroundGraphics.dispose();
    }
  }

  private static int[] foregroundPixelResolvingToNode(
      WinrateGraph graph,
      BoardHistoryNode expectedNode,
      BufferedImage layer,
      int[] anchor,
      int radius) {
    int centerX = anchor[0];
    int centerY = anchor[1];
    for (int y = Math.max(0, centerY - radius);
        y <= Math.min(layer.getHeight() - 1, centerY + radius);
        y++) {
      for (int x = Math.max(0, centerX - radius);
          x <= Math.min(layer.getWidth() - 1, centerX + radius);
          x++) {
        Color pixel = new Color(layer.getRGB(x, y), true);
        if (pixel.getAlpha() == 0) continue;
        if (safeResolveTargetNode(graph, x, y) == expectedNode) return new int[] {x, y};
      }
    }
    throw new AssertionError(
        "expected a rendered foreground pixel that resolves to the target node.");
  }

  private static int[] blankPixelNearAnchor(
      BufferedImage layer, WinrateGraph graph, int[] anchor, int radius) {
    int centerX = anchor[0];
    int centerY = anchor[1];
    for (int y = Math.max(0, centerY - radius);
        y <= Math.min(layer.getHeight() - 1, centerY + radius);
        y++) {
      for (int x = Math.max(0, centerX - radius);
          x <= Math.min(layer.getWidth() - 1, centerX + radius);
          x++) {
        Color pixel = new Color(layer.getRGB(x, y), true);
        if (pixel.getAlpha() > 0) continue;
        if (safeResolveTargetNode(graph, x, y) == null) {
          return new int[] {x, y};
        }
      }
    }
    throw new AssertionError("expected blank background pixel near rendered anchor.");
  }

  private static void assertAllHittablePixelsStayWithinSnapshotColumn(
      WinrateGraph graph,
      BoardHistoryNode expectedNode,
      int expectedColumnX,
      BufferedImage layer,
      int[] anchor,
      int radius) {
    int centerX = anchor[0];
    int centerY = anchor[1];
    int hitCount = 0;
    for (int y = Math.max(0, centerY - radius);
        y <= Math.min(layer.getHeight() - 1, centerY + radius);
        y++) {
      for (int x = Math.max(0, centerX - radius);
          x <= Math.min(layer.getWidth() - 1, centerX + radius);
          x++) {
        BoardHistoryNode hitNode = safeResolveTargetNode(graph, x, y);
        if (hitNode != expectedNode) continue;
        hitCount++;
        assertTrue(
            Math.abs(x - expectedColumnX) <= 2,
            "snapshot hit pixel should stay on snapshot column: (" + x + "," + y + ")");
      }
    }
    assertTrue(hitCount > 0, "expected at least one pixel to hit the snapshot boundary.");
  }

  private static BoardHistoryNode safeResolveTargetNode(WinrateGraph graph, int x, int y) {
    try {
      return resolveTargetNode(graph, x, y);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to resolve move target node", ex);
    }
  }

  private static BoardHistoryNode snapshotBoundaryNode(TrackingBoard board) {
    BoardHistoryNode node = board.getHistory().getStart();
    while (node != null) {
      if (node.getData() != null
          && node.getData().isSnapshotNode()
          && node.previous().isPresent()
          && node.getData().moveNumber > 0) {
        return node;
      }
      if (!node.next().isPresent()) {
        break;
      }
      node = node.next().get();
    }
    throw new AssertionError("expected snapshot boundary node in history.");
  }

  private static TrackingBoard boardWithSnapshotGapHistory() throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveNode(0, 0, Stone.BLACK, false, 1));
    history.add(snapshotNode(Optional.of(new int[] {1, 1}), Stone.WHITE, true, 4, 0));
    history.add(moveNode(2, 2, Stone.WHITE, true, 5));
    history.previous();
    history.previous();
    board.setHistory(history);
    return board;
  }

  private static TrackingBoard boardWithSnapshotGapHistoryAtEnd(
      double preGapWinrate, double postGapWinrate) throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveNode(0, 0, Stone.BLACK, false, 1, preGapWinrate, 1));
    history.add(snapshotNode(Optional.of(new int[] {1, 1}), Stone.WHITE, true, 4, 0));
    history.add(moveNode(2, 2, Stone.WHITE, true, 5, postGapWinrate, 1));
    history.setHead(history.getMainEnd());
    board.setHistory(history);
    return board;
  }

  private static TrackingBoard boardWithLinearHistory(int moveCount) throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    for (int move = 1; move <= moveCount; move++) {
      int x = (move - 1) % BOARD_SIZE;
      int y = ((move - 1) / BOARD_SIZE) % BOARD_SIZE;
      Stone color = (move % 2 == 1) ? Stone.BLACK : Stone.WHITE;
      boolean blackToPlay = (move % 2 == 0);
      history.add(moveNode(x, y, color, blackToPlay, move));
    }
    history.goToMoveNumber(1, false);
    board.setHistory(history);
    return board;
  }

  private static BoardHistoryNode nodeByMoveNumber(TrackingBoard board, int moveNumber) {
    BoardHistoryNode node = board.getHistory().getStart();
    while (node != null) {
      if (node.getData() != null && node.getData().moveNumber == moveNumber) {
        return node;
      }
      if (!node.next().isPresent()) {
        break;
      }
      node = node.next().get();
    }
    throw new AssertionError("expected history node with move number " + moveNumber);
  }

  private static BoardHistoryNode resolveTargetNode(WinrateGraph graph, int x, int y)
      throws Exception {
    Method method =
        WinrateGraph.class.getDeclaredMethod("resolveMoveTargetNode", int.class, int.class);
    method.setAccessible(true);
    return (BoardHistoryNode) method.invoke(graph, x, y);
  }

  private static int[] blankGraphPixel(WinrateGraph graph) throws Exception {
    for (int y = 0; y < GRAPH_HEIGHT; y++) {
      for (int x = 0; x < GRAPH_WIDTH; x++) {
        if (resolveTargetNode(graph, x, y) == null) {
          return new int[] {x, y};
        }
      }
    }
    throw new AssertionError("expected blank graph background pixel.");
  }

  private static void primeRenderedPointSources(WinrateGraph graph) throws Exception {
    Class<?> layoutClass = Class.forName("featurecat.lizzie.gui.WinrateGraph$QuickOverviewLayout");
    Method method =
        WinrateGraph.class.getDeclaredMethod("rememberRenderedPointSources", layoutClass);
    method.setAccessible(true);
    method.invoke(graph, new Object[] {null});
  }

  private static BoardData moveNode(
      int x, int y, Stone color, boolean blackToPlay, int moveNumber) {
    return moveNode(x, y, color, blackToPlay, moveNumber, 50, 1);
  }

  private static BoardData moveNode(
      int x,
      int y,
      Stone color,
      boolean blackToPlay,
      int moveNumber,
      double winrate,
      int playouts) {
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
        playouts);
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

  private static Object getField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
  }

  private static int graphCenterX(int[] params, int moveNumber) {
    return params[0] + (moveNumber - 1) * params[2] / params[4];
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
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
      return getHistory().goToMoveNumber(moveNumber, false);
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
      config.showBlunderBar = false;
      config.showScoreLeadLine = false;
      config.minimumBlunderBarWidth = 1;
      config.initialMaxScoreLead = 10;
      config.winrateStrokeWidth = 1f;
      config.scoreLeadStrokeWidth = 1f;
      config.winrateLineColor = new Color(100, 180, 255);
      config.winrateMissLineColor = new Color(180, 180, 180);
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
