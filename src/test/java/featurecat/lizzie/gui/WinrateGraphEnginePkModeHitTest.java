package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
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

class WinrateGraphEnginePkModeHitTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;
  private static final int RENDER_WIDTH = 260;
  private static final int RENDER_HEIGHT = 140;

  @Test
  void engineGameClickAndDragUseRenderedDotPixel() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = false;
      EngineManager.isEngineGame = true;

      int[] pixel = renderedModeZeroDotPixel(fixture.graph, fixture.target, fixture.targetWinrate);
      clickAndDragShouldReachTarget(fixture, pixel);
    } finally {
      env.close();
    }
  }

  @Test
  void pkClickAndDragUseRenderedDotPixel() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = true;
      EngineManager.isEngineGame = false;

      int[] pixel = renderedModeZeroDotPixel(fixture.graph, fixture.target, fixture.targetWinrate);
      clickAndDragShouldReachTarget(fixture, pixel);
    } finally {
      env.close();
    }
  }

  @Test
  void endedEngineGameKeepsRenderedPkGraphHitCacheBeforeRedraw() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = true;
      EngineManager.isEngineGame = true;

      int[] clickPixel =
          renderedModeZeroDotPixel(fixture.graph, fixture.target, fixture.targetWinrate);
      EngineManager.isEngineGame = false;

      fixture.graph.clearMouseOverNode();
      boolean handled = fixture.frame.processMouseMoveOnWinrateGraph(clickPixel[0], clickPixel[1]);
      assertTrue(handled, "ended engine game hover should keep using the rendered PK graph cache.");
      assertSame(
          fixture.target,
          fixture.graph.mouseOverNode,
          "ended engine game hover should still resolve the rendered target.");

      fixture.frame.onClickedWinrateOnly(clickPixel[0], clickPixel[1]);
      assertSame(
          fixture.target,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "ended engine game click should navigate before the next graph repaint.");

      fixture.board.getHistory().setHead(fixture.current);
      EngineManager.isEngineGame = true;
      int[] dragPixel =
          renderedModeZeroDotPixel(fixture.graph, fixture.target, fixture.targetWinrate);
      EngineManager.isEngineGame = false;
      fixture.frame.onMouseDragged(dragPixel[0], dragPixel[1]);
      assertSame(
          fixture.target,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "ended engine game drag should navigate before the next graph repaint.");
    } finally {
      env.close();
    }
  }

  @Test
  void modeOneClickAndDragUseRenderedWhiteDotPixel() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeOneFixture();
      EngineManager.isEngineGame = false;
      fixture.board.isPkBoard = false;

      int[] pixel =
          renderedModeOneWhiteDotPixel(fixture.graph, fixture.target, fixture.whiteDotWinrate);
      clickAndDragShouldReachTarget(fixture, pixel);
    } finally {
      env.close();
    }
  }

  @Test
  void modeOneHoverUsesRenderedWhiteDotPixel() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeOneFixture();
      EngineManager.isEngineGame = false;
      fixture.board.isPkBoard = false;

      int[] pixel =
          renderedModeOneWhiteDotPixel(fixture.graph, fixture.target, fixture.whiteDotWinrate);
      boolean handled = fixture.frame.processMouseMoveOnWinrateGraph(pixel[0], pixel[1]);

      assertTrue(handled, "hover should consume the rendered white-dot pixel in mode 1.");
      assertSame(
          fixture.target,
          fixture.graph.mouseOverNode,
          "hover should resolve the same rendered white-dot target node as click and drag.");
    } finally {
      env.close();
    }
  }

  @Test
  void engineGameSnapshotGapBoundaryUsesRealColumnsAndConsistentHit() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      SnapshotGapFixture fixture = snapshotGapFixture(0);
      fixture.renderFixture.board.isPkBoard = false;
      EngineManager.isEngineGame = true;
      assertSnapshotGapBoundaryHitConsistency(fixture, "engine game");
    } finally {
      env.close();
    }
  }

  @Test
  void pkSnapshotGapBoundaryUsesRealColumnsAndConsistentHit() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      SnapshotGapFixture fixture = snapshotGapFixture(0);
      fixture.renderFixture.board.isPkBoard = true;
      EngineManager.isEngineGame = false;
      assertSnapshotGapBoundaryHitConsistency(fixture, "pk");
    } finally {
      env.close();
    }
  }

  @Test
  void modeOneSnapshotGapBoundaryUsesRealColumnsAndConsistentHit() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      SnapshotGapFixture fixture = snapshotGapFixture(1);
      fixture.renderFixture.board.isPkBoard = false;
      EngineManager.isEngineGame = false;
      assertSnapshotGapBoundaryHitConsistency(fixture, "mode 1");
    } finally {
      env.close();
    }
  }

  @Test
  void modeOneZeroPlayoutSnapshotHitPixelsStayOnForeground() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      SnapshotGapFixture fixture = snapshotGapFixture(1);
      fixture.renderFixture.board.isPkBoard = false;
      EngineManager.isEngineGame = false;
      WinrateGraph graph = fixture.renderFixture.graph;
      BufferedImage layer = renderGraphLayer(graph);
      int[] anchor = renderedGraphPoint(graph, fixture.snapshotBoundary);
      assertNotNull(anchor, "mode 1 should keep a snapshot boundary anchor point.");
      assertAllHittablePixelsHaveForeground(graph, fixture.snapshotBoundary, layer, anchor, 8);
    } finally {
      env.close();
    }
  }

  @Test
  void engineGameBlankGraphBackgroundIgnoresHoverClickAndDrag() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = false;
      EngineManager.isEngineGame = true;
      assertBlankGraphBackgroundMisses(fixture, "engine game");
    } finally {
      env.close();
    }
  }

  @Test
  void pkBlankGraphBackgroundIgnoresHoverClickAndDrag() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = true;
      EngineManager.isEngineGame = false;
      assertBlankGraphBackgroundMisses(fixture, "pk");
    } finally {
      env.close();
    }
  }

  @Test
  void modeOneBlankGraphBackgroundIgnoresHoverClickAndDrag() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeOneFixture();
      fixture.board.isPkBoard = false;
      EngineManager.isEngineGame = false;
      assertBlankGraphBackgroundMisses(fixture, "mode 1");
    } finally {
      env.close();
    }
  }

  private static void assertSnapshotGapBoundaryHitConsistency(
      SnapshotGapFixture fixture, String modeLabel) throws Exception {
    WinrateGraph graph = fixture.renderFixture.graph;
    BoardHistoryNode snapshotBoundary = fixture.snapshotBoundary;
    BufferedImage image = renderGraphLayer(graph);
    int[] params = (int[]) getField(graph, "params");
    int[] preGapPoint = renderedGraphPoint(graph, fixture.preGapMove);
    assertNotNull(preGapPoint, modeLabel + " should retain the pre-gap move anchor point.");
    assertEquals(
        graphCenterX(params, fixture.preGapMove.getData().moveNumber),
        preGapPoint[0],
        modeLabel + " should keep the pre-gap move on its real moveNumber column.");

    int[] anchorPoint = renderedGraphPoint(graph, snapshotBoundary);

    assertNotNull(anchorPoint, modeLabel + " should retain a SNAPSHOT boundary anchor point.");
    assertEquals(
        graphCenterX(params, snapshotBoundary.getData().moveNumber),
        anchorPoint[0],
        modeLabel + " should keep SNAPSHOT boundary on its real moveNumber column.");

    int[] pixel = foregroundPixelResolvingToNode(graph, snapshotBoundary, image, anchorPoint);
    assertHoverClickDragHitSameNode(fixture.renderFixture, snapshotBoundary, pixel, modeLabel);
  }

  private static void assertBlankGraphBackgroundMisses(RenderFixture fixture, String modeLabel)
      throws Exception {
    renderGraphLayer(fixture.graph);
    int[] blankPixel = blankGraphPixel(fixture.graph);
    BoardHistoryNode start = fixture.board.getHistory().getCurrentHistoryNode();

    fixture.graph.clearMouseOverNode();
    boolean handled = fixture.frame.processMouseMoveOnWinrateGraph(blankPixel[0], blankPixel[1]);
    assertFalse(handled, modeLabel + " blank graph hover should not hit any node.");
    assertSame(
        null, fixture.graph.mouseOverNode, modeLabel + " blank graph hover should stay null.");

    fixture.frame.onClickedWinrateOnly(blankPixel[0], blankPixel[1]);
    assertSame(
        start,
        fixture.board.getHistory().getCurrentHistoryNode(),
        modeLabel + " blank graph click should keep current node unchanged.");

    fixture.frame.onMouseDragged(blankPixel[0], blankPixel[1]);
    assertSame(
        start,
        fixture.board.getHistory().getCurrentHistoryNode(),
        modeLabel + " blank graph drag should keep current node unchanged.");
  }

  private static void assertHoverClickDragHitSameNode(
      RenderFixture fixture, BoardHistoryNode expectedNode, int[] pixel, String modeLabel) {
    fixture.graph.clearMouseOverNode();
    boolean handled = fixture.frame.processMouseMoveOnWinrateGraph(pixel[0], pixel[1]);
    assertTrue(handled, modeLabel + " hover should consume the rendered boundary pixel.");
    assertSame(expectedNode, fixture.graph.mouseOverNode, modeLabel + " hover target mismatch.");

    fixture.board.getHistory().goToMoveNumber(1, false);
    renderGraphLayer(fixture.graph);
    fixture.frame.onClickedWinrateOnly(pixel[0], pixel[1]);
    assertSame(
        expectedNode,
        fixture.board.getHistory().getCurrentHistoryNode(),
        modeLabel + " click target mismatch.");

    fixture.board.getHistory().goToMoveNumber(1, false);
    renderGraphLayer(fixture.graph);
    fixture.frame.onMouseDragged(pixel[0], pixel[1]);
    assertSame(
        expectedNode,
        fixture.board.getHistory().getCurrentHistoryNode(),
        modeLabel + " drag target mismatch.");
  }

  private static void clickAndDragShouldReachTarget(RenderFixture fixture, int[] pixel) {
    fixture.frame.onClickedWinrateOnly(pixel[0], pixel[1]);
    assertSame(
        fixture.target,
        fixture.board.getHistory().getCurrentHistoryNode(),
        "click should follow the rendered pixel to the exact target node.");

    fixture.board.getHistory().setHead(fixture.current);
    renderGraphLayer(fixture.graph);
    fixture.frame.onMouseDragged(pixel[0], pixel[1]);
    assertSame(
        fixture.target,
        fixture.board.getHistory().getCurrentHistoryNode(),
        "drag should follow the rendered pixel to the exact target node.");
  }

  private static RenderFixture modeZeroFixture() throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;

    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveNode(0, 0, Stone.BLACK, false, 1, 35, 1));
    history.add(moveNode(1, 0, Stone.WHITE, true, 2, 42, 1));
    history.add(moveNode(2, 0, Stone.BLACK, false, 3, 50, 1));
    history.add(moveNode(0, 1, Stone.WHITE, true, 4, 82, 1));
    history.add(moveNode(1, 1, Stone.BLACK, false, 5, 82, 1));
    BoardHistoryNode target = history.getCurrentHistoryNode();
    history.add(moveNode(2, 1, Stone.WHITE, true, 6, 65, 1));
    BoardHistoryNode current = history.getCurrentHistoryNode();
    history.setHead(current);
    board.setHistory(history);

    return setupGraph(board, current, target, 82, 0, 0);
  }

  private static RenderFixture modeOneFixture() throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;

    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveNode(0, 0, Stone.BLACK, false, 1, 40, 1));
    history.add(moveNode(1, 0, Stone.WHITE, true, 2, 55, 1));
    history.add(moveNode(2, 0, Stone.BLACK, false, 3, 45, 1));
    history.add(moveNode(0, 1, Stone.WHITE, true, 4, 80, 1));
    history.add(moveNode(1, 1, Stone.BLACK, false, 5, 80, 1));
    BoardHistoryNode target = history.getCurrentHistoryNode();
    history.add(moveNode(2, 1, Stone.WHITE, true, 6, 50, 0));
    BoardHistoryNode current = history.getCurrentHistoryNode();
    history.setHead(current);
    board.setHistory(history);

    return setupGraph(board, current, target, 80, 1, 80);
  }

  private static SnapshotGapFixture snapshotGapFixture(int mode) throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;

    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveNode(0, 0, Stone.BLACK, false, 1, 40, 1));
    BoardHistoryNode preGapMove = history.getCurrentHistoryNode();
    history.add(snapshotNode(Optional.of(new int[] {1, 1}), Stone.WHITE, true, 4, 50, 0));
    BoardHistoryNode snapshotBoundary = history.getCurrentHistoryNode();
    history.add(moveNode(2, 0, Stone.WHITE, true, 5, 65, 1));
    BoardHistoryNode current = history.getCurrentHistoryNode();
    history.setHead(current);
    board.setHistory(history);

    RenderFixture renderFixture = setupGraph(board, current, snapshotBoundary, 50, mode, 50);
    return new SnapshotGapFixture(renderFixture, snapshotBoundary, preGapMove);
  }

  private static RenderFixture setupGraph(
      TrackingBoard board,
      BoardHistoryNode current,
      BoardHistoryNode target,
      double targetWinrate,
      int mode,
      double whiteDotWinrate)
      throws Exception {
    WinrateGraph graph = new WinrateGraph();
    graph.mode = mode;
    TrackingFrame frame = allocate(TrackingFrame.class);

    Lizzie.board = board;
    Lizzie.frame = frame;
    LizzieFrame.winrateGraph = graph;

    return new RenderFixture(board, frame, graph, current, target, targetWinrate, whiteDotWinrate);
  }

  private static int[] renderedModeZeroDotPixel(
      WinrateGraph graph, BoardHistoryNode target, double targetWinrate) throws Exception {
    renderGraphLayer(graph);
    int[] params = (int[]) getField(graph, "params");
    int expectedY = graphCenterY(params, targetWinrate);
    return renderedGraphPointMatchingY(graph, target, expectedY);
  }

  private static int[] renderedModeOneWhiteDotPixel(
      WinrateGraph graph, BoardHistoryNode target, double whiteDotWinrate) throws Exception {
    renderGraphLayer(graph);
    int[] params = (int[]) getField(graph, "params");
    int expectedY = graphCenterY(params, whiteDotWinrate);
    return renderedGraphPointMatchingY(graph, target, expectedY);
  }

  @SuppressWarnings("unchecked")
  private static int[] renderedGraphPointMatchingY(
      WinrateGraph graph, BoardHistoryNode target, int expectedY) throws Exception {
    Field field = WinrateGraph.class.getDeclaredField("renderedGraphPoints");
    field.setAccessible(true);
    java.util.List<Object> points = (java.util.List<Object>) field.get(graph);
    Object best = null;
    int bestDelta = Integer.MAX_VALUE;
    for (Object point : points) {
      Field nodeField = point.getClass().getDeclaredField("node");
      nodeField.setAccessible(true);
      if (nodeField.get(point) != target) continue;
      Field yField = point.getClass().getDeclaredField("y");
      yField.setAccessible(true);
      int y = yField.getInt(point);
      int delta = Math.abs(y - expectedY);
      if (delta < bestDelta) {
        best = point;
        bestDelta = delta;
      }
    }
    if (best == null) {
      throw new AssertionError("expected rendered graph point for target node.");
    }
    Field xField = best.getClass().getDeclaredField("x");
    xField.setAccessible(true);
    Field yField = best.getClass().getDeclaredField("y");
    yField.setAccessible(true);
    return new int[] {xField.getInt(best), yField.getInt(best)};
  }

  private static int[] renderedGraphPoint(WinrateGraph graph, BoardHistoryNode node)
      throws Exception {
    Method method =
        WinrateGraph.class.getDeclaredMethod("renderedGraphPoint", BoardHistoryNode.class);
    method.setAccessible(true);
    return (int[]) method.invoke(graph, node);
  }

  private static BufferedImage renderGraphLayer(WinrateGraph graph) {
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

  private static int graphCenterX(int[] params, int moveNumber) {
    return params[0] + (moveNumber - 1) * params[2] / params[4];
  }

  private static int graphCenterY(int[] params, double winrate) {
    return params[1] + params[3] - (int) (winrate * params[3] / 100.0);
  }

  private static int[] opaquePixelNear(BufferedImage image, int centerX, int centerY) {
    for (int y = Math.max(0, centerY - 2); y <= Math.min(image.getHeight() - 1, centerY + 2); y++) {
      for (int x = Math.max(0, centerX - 2);
          x <= Math.min(image.getWidth() - 1, centerX + 2);
          x++) {
        Color pixel = new Color(image.getRGB(x, y), true);
        if (pixel.getAlpha() > 0) {
          return new int[] {x, y};
        }
      }
    }
    throw new AssertionError("expected rendered graph point to paint an opaque pixel.");
  }

  private static int[] blankGraphPixel(WinrateGraph graph) throws Exception {
    int[] origParams = (int[]) getField(graph, "origParams");
    int minX = origParams[0];
    int maxX = minX + origParams[2];
    int minY = origParams[1];
    int maxY = minY + Math.max(1, origParams[3] / 2);
    for (int y = minY; y < maxY; y++) {
      for (int x = minX; x < maxX; x++) {
        if (graph.resolveMoveTargetNode(x, y) == null) {
          return new int[] {x, y};
        }
      }
    }
    throw new AssertionError("expected blank graph background pixel.");
  }

  private static int[] foregroundPixelResolvingToNode(
      WinrateGraph graph, BoardHistoryNode expectedNode, BufferedImage layer, int[] anchorPoint) {
    int centerX = anchorPoint[0];
    int centerY = anchorPoint[1];
    for (int radius = 0; radius <= 12; radius++) {
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
          BoardHistoryNode resolvedNode = graph.resolveMoveTargetNode(x, y);
          if (resolvedNode == expectedNode) {
            return new int[] {x, y};
          }
        }
      }
    }
    throw new AssertionError(
        "expected a rendered foreground pixel that resolves to snapshot boundary.");
  }

  private static void assertAllHittablePixelsHaveForeground(
      WinrateGraph graph,
      BoardHistoryNode expectedNode,
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
        BoardHistoryNode hitNode = graph.resolveMoveTargetNode(x, y);
        if (hitNode != expectedNode) continue;
        hitCount++;
        Color pixel = new Color(layer.getRGB(x, y), true);
        assertTrue(
            pixel.getAlpha() > 0,
            "mode 1 snapshot hit pixel should map to foreground: (" + x + "," + y + ")");
      }
    }
    assertTrue(hitCount > 0, "expected at least one hittable snapshot boundary pixel.");
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
      double winrate,
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
        winrate,
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

  private static Object getField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class RenderFixture {
    private final TrackingBoard board;
    private final TrackingFrame frame;
    private final WinrateGraph graph;
    private final BoardHistoryNode current;
    private final BoardHistoryNode target;
    private final double targetWinrate;
    private final double whiteDotWinrate;

    private RenderFixture(
        TrackingBoard board,
        TrackingFrame frame,
        WinrateGraph graph,
        BoardHistoryNode current,
        BoardHistoryNode target,
        double targetWinrate,
        double whiteDotWinrate) {
      this.board = board;
      this.frame = frame;
      this.graph = graph;
      this.current = current;
      this.target = target;
      this.targetWinrate = targetWinrate;
      this.whiteDotWinrate = whiteDotWinrate;
    }
  }

  private static final class SnapshotGapFixture {
    private final RenderFixture renderFixture;
    private final BoardHistoryNode snapshotBoundary;
    private final BoardHistoryNode preGapMove;

    private SnapshotGapFixture(
        RenderFixture renderFixture,
        BoardHistoryNode snapshotBoundary,
        BoardHistoryNode preGapMove) {
      this.renderFixture = renderFixture;
      this.snapshotBoundary = snapshotBoundary;
      this.preGapMove = preGapMove;
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
    private final boolean previousEngineGame;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame,
        WinrateGraph previousWinrateGraph,
        Leelaz previousLeelaz,
        boolean previousEngineGame) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousWinrateGraph = previousWinrateGraph;
      this.previousLeelaz = previousLeelaz;
      this.previousEngineGame = previousEngineGame;
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
              Lizzie.leelaz,
              EngineManager.isEngineGame);
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      Config config = allocate(Config.class);
      config.showWinrateGraph = true;
      config.showBlunderBar = false;
      config.showScoreLeadLine = false;
      config.showWinrateLine = true;
      config.winrateStrokeWidth = 2.0f;
      config.winrateLineColor = new Color(100, 180, 255);
      config.winrateMissLineColor = new Color(100, 100, 100);
      config.initialMaxScoreLead = 15;
      Lizzie.config = config;

      Lizzie.leelaz = allocate(Leelaz.class);
      Lizzie.board = null;
      Lizzie.frame = null;
      LizzieFrame.winrateGraph = null;
      EngineManager.isEngineGame = false;
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
      EngineManager.isEngineGame = previousEngineGame;
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
