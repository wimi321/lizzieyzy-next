package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.AppLocale;
import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.enginegame.EngineGameSnapshotFixtures;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
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
  private static final Color CUSTOM_WINRATE_COLOR = new Color(20, 210, 95);
  private static final Color CUSTOM_MISS_COLOR = new Color(220, 90, 30);
  private static final Color CUSTOM_BLUNDER_COLOR = new Color(180, 30, 220, 255);
  private static final Color CURRENT_MOVE_MARKER_COLOR = new Color(244, 67, 72);
  private static final Color CURRENT_SCORE_MARKER_COLOR = new Color(46, 204, 113);

  @Test
  void currentScoreLeadUsesGreenPointOnRenderedScoreCurve() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishIdle();
      Lizzie.leelaz.isKatago = true;
      Lizzie.config.showScoreLeadLine = true;
      Lizzie.config.scoreMeanLineColor = new Color(220, 70, 190);
      Lizzie.config.scoreLeadStrokeWidth = 2.0f;
      fixture.target.getData().setScoreMean(2.0);
      fixture.current.getData().setScoreMean(4.0);

      RenderLayers layers = renderLayers(fixture.graph);
      int[] params = (int[]) getField(fixture.graph, "params");
      double maxScoreLead = (double) getField(fixture.graph, "maxScoreLead");
      int x = graphPointX(fixture.graph, fixture.current.getData().moveNumber);
      int y =
          params[1]
              + params[3] / 2
              - (int) (4.0 * params[3] / 2 / Math.max(1.0, maxScoreLead));

      assertColorNear(
          layers.winrate,
          new int[] {x, y},
          CURRENT_SCORE_MARKER_COLOR,
          2);
    } finally {
      env.close();
    }
  }

  @Test
  void currentMoveUsesRedPointWithoutVerticalGuideInDefaultMode() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishIdle();

      assertCurrentMoveUsesPointOnly(fixture, false);
    } finally {
      env.close();
    }
  }

  @Test
  void currentMoveUsesRedPointWithoutVerticalGuideInEngineGame() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishPlaying();

      assertCurrentMoveUsesPointOnly(fixture, false);
    } finally {
      env.close();
    }
  }

  @Test
  void pendingCurrentMoveUsesItsOwnColumnWithoutVerticalGuide()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeOneFixture();
      fixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishIdle();

      assertCurrentMoveUsesPointOnly(fixture, true);
    } finally {
      env.close();
    }
  }

  @Test
  void engineGameClickAndDragUseRenderedDotPixel() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishPlaying();

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
      EngineGameSnapshotFixtures.publishIdle();

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
      EngineGameSnapshotFixtures.publishPlaying();

      int[] clickPixel =
          renderedModeZeroDotPixel(fixture.graph, fixture.target, fixture.targetWinrate);
      EngineGameSnapshotFixtures.publishIdle();

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
      EngineGameSnapshotFixtures.publishPlaying();
      int[] dragPixel =
          renderedModeZeroDotPixel(fixture.graph, fixture.target, fixture.targetWinrate);
      EngineGameSnapshotFixtures.publishIdle();
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
  void engineGameSnapshotGapBoundaryUsesRealColumnsAndConsistentHit() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      SnapshotGapFixture fixture = snapshotGapFixture();
      fixture.renderFixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishPlaying();
      assertSnapshotGapBoundaryHitConsistency(fixture, "engine game");
    } finally {
      env.close();
    }
  }

  @Test
  void pkSnapshotGapBoundaryUsesRealColumnsAndConsistentHit() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      SnapshotGapFixture fixture = snapshotGapFixture();
      fixture.renderFixture.board.isPkBoard = true;
      EngineGameSnapshotFixtures.publishIdle();
      assertSnapshotGapBoundaryHitConsistency(fixture, "pk");
    } finally {
      env.close();
    }
  }

  @Test
  void ordinaryAnalysisSnapshotGapBoundaryUsesRealColumnsAndConsistentHit() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      SnapshotGapFixture fixture = snapshotGapFixture();
      fixture.renderFixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishIdle();
      assertSnapshotGapBoundaryHitConsistency(fixture, "ordinary analysis");
    } finally {
      env.close();
    }
  }

  @Test
  void ordinaryAnalysisZeroPlayoutSnapshotBlankPixelsStillScrubToBoundaryColumn() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      SnapshotGapFixture fixture = snapshotGapFixture();
      fixture.renderFixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishIdle();
      WinrateGraph graph = fixture.renderFixture.graph;
      BufferedImage layer = renderGraphLayer(graph);
      int[] anchor =
          renderedModeZeroDotPixel(
              graph, fixture.snapshotBoundary, fixture.renderFixture.targetWinrate);
      assertNotNull(anchor, "ordinary analysis should keep a snapshot boundary anchor point.");
      int[] blankPixel =
          blankPixelResolvingToNode(graph, fixture.snapshotBoundary, layer, anchor, 8);
      assertSame(
          fixture.snapshotBoundary,
          graph.resolveMoveTargetNode(blankPixel[0], blankPixel[1]),
          "ordinary analysis zero-playout snapshot should scrub from blank graph pixels to its boundary column.");
    } finally {
      env.close();
    }
  }

  @Test
  void engineGameBlankGraphBackgroundScrubsToNearestVisibleColumn() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishPlaying();
      assertBlankGraphBackgroundScrubsToNearestVisibleColumn(fixture, "engine game");
    } finally {
      env.close();
    }
  }

  @Test
  void pkBlankGraphBackgroundScrubsToNearestVisibleColumn() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = true;
      EngineGameSnapshotFixtures.publishIdle();
      assertBlankGraphBackgroundScrubsToNearestVisibleColumn(fixture, "pk");
    } finally {
      env.close();
    }
  }

  @Test
  void engineGameMousePressedOnGraphScrubPixelUsesWinrateNavigation() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishPlaying();
      BufferedImage layer = renderGraphLayer(fixture.graph);
      int[] anchorPoint = renderedPrimaryGraphPixel(fixture);
      int[] scrubPixel =
          blankPixelResolvingToNode(fixture.graph, fixture.target, layer, anchorPoint, 8);

      Input input = new Input();
      MouseEvent press =
          new MouseEvent(
              new Canvas(),
              MouseEvent.MOUSE_PRESSED,
              System.currentTimeMillis(),
              0,
              scrubPixel[0],
              scrubPixel[1],
              1,
              false,
              MouseEvent.BUTTON1);

      input.mousePressed(press);

      assertSame(
          fixture.target,
          fixture.board.getHistory().getCurrentHistoryNode(),
          "engine-game left click on graph scrub pixel should navigate by winrate graph.");
    } finally {
      env.close();
    }
  }

  @Test
  void customWinrateColorPaintsMainGraphLineAndDot() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config.winrateLineColor = CUSTOM_WINRATE_COLOR;
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishIdle();

      RenderLayers layers = renderLayers(fixture.graph);
      int[] dot = renderedModeZeroDotPixel(fixture.graph, fixture.target, fixture.targetWinrate);

      assertColorNear(layers.winrate, dot, CUSTOM_WINRATE_COLOR, 3);
    } finally {
      env.close();
    }
  }

  @Test
  void customWinrateColorPaintsEngineGameGraph() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config.winrateLineColor = CUSTOM_WINRATE_COLOR;
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishPlaying();

      RenderLayers layers = renderLayers(fixture.graph);
      int[] dot = renderedModeZeroDotPixel(fixture.graph, fixture.target, fixture.targetWinrate);

      assertColorNear(layers.winrate, dot, CUSTOM_WINRATE_COLOR, 3);
    } finally {
      env.close();
    }
  }


  @Test
  void engineGameInterleavedWinrateLinesDoNotUseSingleCurveAreaFill() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishPlaying();

      Lizzie.config.showWinrateGraphFill = true;
      BufferedImage fillOn = renderLayers(fixture.graph).background;
      Lizzie.config.showWinrateGraphFill = false;
      BufferedImage fillOff = renderLayers(fixture.graph).background;

      assertArrayEquals(
          fillOff.getRGB(0, 0, RENDER_WIDTH, RENDER_HEIGHT, null, 0, RENDER_WIDTH),
          fillOn.getRGB(0, 0, RENDER_WIDTH, RENDER_HEIGHT, null, 0, RENDER_WIDTH),
          "two interleaved engine polylines should stay unfilled.");
    } finally {
      env.close();
    }
  }

  @Test
  void ordinaryScoreLeadGapConnectorDoesNotFillWhileAdjacentAnalyzedConnectorDoes()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = ordinaryScoreLeadGapFixture();
      fixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishIdle();
      Lizzie.leelaz.isKatago = true;
      Lizzie.config.showWinrateLine = false;
      Lizzie.config.showScoreLeadLine = true;
      Lizzie.config.showWinrateGraphFill = true;
      Lizzie.config.scoreMeanLineColor = new Color(255, 220, 80);
      Lizzie.config.scoreLeadStrokeWidth = 2.0f;

      BufferedImage fillOn = renderLayers(fixture.graph).background;
      int xMove1 = graphPointX(fixture.graph, 1);
      int xMove2 = graphPointX(fixture.graph, 2);
      int xMove8 = graphPointX(fixture.graph, 8);

      Lizzie.config.showWinrateGraphFill = false;
      BufferedImage fillOff = renderLayers(fixture.graph).background;

      assertTrue(
          backgroundDiffersBetweenX(fillOn, fillOff, xMove1, xMove2),
          "adjacent analyzed score segment should still fill.");
      assertFalse(
          backgroundDiffersBetweenX(fillOn, fillOff, xMove2, xMove8),
          "score connector that spans a missing-analysis gap should stay unfilled.");
    } finally {
      env.close();
    }
  }

  @Test
  void whiteKataGoPkWhiteToPlayRawScoreLeadRendersFromBlackPerspective() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = whiteKataGoPkWhiteToPlayScoreFixture(4.0, false);
      EngineGameSnapshotFixtures.publishIdle();
      Lizzie.config.showScoreLeadLine = true;
      Lizzie.config.showWinrateLine = false;
      Lizzie.config.showKataGoScoreLeadWithKomi = false;
      Lizzie.config.scoreMeanLineColor = new Color(220, 70, 190);
      Lizzie.config.scoreLeadStrokeWidth = 2.0f;

      assertRenderedScoreLeadIsBlackPerspective(
          fixture.graph, fixture.current.getData(), -4.0, "W+4.0");
    } finally {
      env.close();
    }
  }

  @Test
  void saiWhiteToPlayScoreLeadIsNotNegatedTwice() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardData sai = scoreMoveNode(0, 0, Stone.BLACK, false, 1, 1, -4.0);
      sai.isSaiData = true;
      sai.scoreMeanIsBlackPerspective = true;
      assertEquals(-4.0, WinrateGraph.blackPerspectiveScoreMean(sai), 1e-9);

      RenderFixture fixture = ordinaryWhiteToPlaySaiScoreFixture(-4.0);
      EngineGameSnapshotFixtures.publishIdle();
      Lizzie.leelaz.isKatago = true;
      Lizzie.config.showScoreLeadLine = true;
      Lizzie.config.showWinrateLine = false;
      Lizzie.config.showKataGoScoreLeadWithKomi = false;

      assertRenderedScoreLeadIsBlackPerspective(
          fixture.graph, fixture.current.getData(), -4.0, "W+4.0");
    } finally {
      env.close();
    }
  }

  @Test
  void sayuriWhiteToPlayRawScoreLeadIsNegatedToBlackPerspective() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardData sayuri = scoreMoveNode(0, 0, Stone.BLACK, false, 1, 1, 4.0);
      sayuri.isSaiData = true;
      sayuri.scoreMeanIsBlackPerspective = false;
      assertEquals(-4.0, WinrateGraph.blackPerspectiveScoreMean(sayuri), 1e-9);

      RenderFixture fixture = ordinaryWhiteToPlaySayuriScoreFixture(4.0);
      EngineGameSnapshotFixtures.publishIdle();
      Lizzie.leelaz.isKatago = true;
      Lizzie.config.showScoreLeadLine = true;
      Lizzie.config.showWinrateLine = false;
      Lizzie.config.showKataGoScoreLeadWithKomi = false;

      assertRenderedScoreLeadIsBlackPerspective(
          fixture.graph, fixture.current.getData(), -4.0, "W+4.0");
    } finally {
      env.close();
    }
  }

  @Test
  void analysisCopiesPreserveScoreMeanBlackPerspective() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardData source = scoreMoveNode(0, 0, Stone.BLACK, false, 1, 1, -4.0);
      source.scoreMeanIsBlackPerspective = true;
      source.scoreMeanIsBlackPerspective2 = true;

      BoardData cloned = source.clone();
      assertTrue(cloned.scoreMeanIsBlackPerspective);
      assertTrue(cloned.scoreMeanIsBlackPerspective2);

      BoardData synced = scoreMoveNode(1, 0, Stone.WHITE, true, 2, 1, 0.0);
      synced.sync(source);
      assertTrue(synced.scoreMeanIsBlackPerspective);
      assertTrue(synced.scoreMeanIsBlackPerspective2);

      BoardData payload = scoreMoveNode(2, 0, Stone.BLACK, false, 3, 1, 0.0);
      payload.copyAnalysisPayloadFrom(source);
      assertTrue(payload.scoreMeanIsBlackPerspective);
      assertTrue(payload.scoreMeanIsBlackPerspective2);
    } finally {
      env.close();
    }
  }

  @Test
  void showBlunderBarFalseLeavesBlunderLayerEmpty() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config.showBlunderBar = false;
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishIdle();

      RenderLayers layers = renderLayers(fixture.graph);

      assertFalse(hasOpaquePixel(layers.blunder), "blunder layer should stay empty when disabled.");
    } finally {
      env.close();
    }
  }

  @Test
  void customBlunderColorAndMinimumWidthPaintMainGraphBars() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config.showBlunderBar = true;
      Lizzie.config.blunderBarColor = CUSTOM_BLUNDER_COLOR;
      Lizzie.config.minimumBlunderBarWidth = 9;
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishIdle();

      RenderLayers layers = renderLayers(fixture.graph);
      int paintedWidth = widestColorRun(layers.blunder, CUSTOM_BLUNDER_COLOR);

      assertTrue(paintedWidth >= 10, "main graph blunder bar should honor minimum width.");
    } finally {
      env.close();
    }
  }

  @Test
  void customBlunderColorPaintsEngineGameBars() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config.showBlunderBar = true;
      Lizzie.config.blunderBarColor = CUSTOM_BLUNDER_COLOR;
      RenderFixture fixture = modeZeroFixture();
      fixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishPlaying();

      RenderLayers layers = renderLayers(fixture.graph);

      assertColorPresent(layers.blunder, CUSTOM_BLUNDER_COLOR);
    } finally {
      env.close();
    }
  }

  @Test
  void engineGameBlankPixelOnDuplicatedColumnStaysOnSameMoveNumber() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeZeroDuplicatedColumnFixture();
      fixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishPlaying();
      assertExactColumnBlankPixelStaysOnTarget(fixture, "engine game duplicated column");
    } finally {
      env.close();
    }
  }

  @Test
  void ordinaryAnalysisBlankGraphBackgroundScrubsToNearestVisibleColumn() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      RenderFixture fixture = modeOneFixture();
      fixture.board.isPkBoard = false;
      EngineGameSnapshotFixtures.publishIdle();
      assertBlankGraphBackgroundScrubsToNearestVisibleColumn(fixture, "ordinary analysis");
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

  private static void assertBlankGraphBackgroundScrubsToNearestVisibleColumn(
      RenderFixture fixture, String modeLabel) throws Exception {
    BufferedImage layer = renderGraphLayer(fixture.graph);
    int[] anchorPoint = renderedPrimaryGraphPixel(fixture);
    int[] blankPixel =
        blankPixelResolvingToNode(fixture.graph, fixture.target, layer, anchorPoint, 8);
    BoardHistoryNode start = fixture.board.getHistory().getCurrentHistoryNode();

    fixture.graph.clearMouseOverNode();
    boolean handled = fixture.frame.processMouseMoveOnWinrateGraph(blankPixel[0], blankPixel[1]);
    assertTrue(
        handled, modeLabel + " blank graph hover should scrub to the nearest visible column.");
    assertSame(
        fixture.target,
        fixture.graph.mouseOverNode,
        modeLabel + " blank graph hover should scrub to the nearest visible column target.");

    fixture.frame.onClickedWinrateOnly(blankPixel[0], blankPixel[1]);
    assertSame(
        fixture.target,
        fixture.board.getHistory().getCurrentHistoryNode(),
        modeLabel + " blank graph click should jump to the nearest visible column target.");

    fixture.board.getHistory().setHead(start);
    fixture.frame.onMouseDragged(blankPixel[0], blankPixel[1]);
    assertSame(
        fixture.target,
        fixture.board.getHistory().getCurrentHistoryNode(),
        modeLabel + " blank graph drag should jump to the nearest visible column target.");
  }

  private static void assertExactColumnBlankPixelStaysOnTarget(
      RenderFixture fixture, String modeLabel) throws Exception {
    BufferedImage layer = renderGraphLayer(fixture.graph);
    int[] anchorPoint = renderedPrimaryGraphPixel(fixture);
    int[] blankPixel =
        blankPixelOnExactColumn(
            fixture.graph, fixture.target, layer, anchorPoint[0], anchorPoint[1], 20);

    fixture.graph.clearMouseOverNode();
    boolean handled = fixture.frame.processMouseMoveOnWinrateGraph(blankPixel[0], blankPixel[1]);
    assertTrue(
        handled, modeLabel + " exact-column blank hover should stay on the same move-number.");
    assertSame(
        fixture.target,
        fixture.graph.mouseOverNode,
        modeLabel + " exact-column blank hover should not drift to another move.");

    fixture.frame.onClickedWinrateOnly(blankPixel[0], blankPixel[1]);
    assertSame(
        fixture.target,
        fixture.board.getHistory().getCurrentHistoryNode(),
        modeLabel + " exact-column blank click should stay on the same move-number.");

    fixture.board.getHistory().setHead(fixture.current);
    fixture.frame.onMouseDragged(blankPixel[0], blankPixel[1]);
    assertSame(
        fixture.target,
        fixture.board.getHistory().getCurrentHistoryNode(),
        modeLabel + " exact-column blank drag should stay on the same move-number.");
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

  private static void assertCurrentMoveUsesPointOnly(
      RenderFixture fixture, boolean assertCurrentColumn) throws Exception {
    RenderLayers layers = renderLayers(fixture.graph);
    int[] marker = renderedCurrentMoveMarkerPoint(fixture.graph, fixture.current);

    assertNotNull(marker, "the current move should have a visible marker point.");
    assertColorNear(layers.winrate, marker, CURRENT_MOVE_MARKER_COLOR, 2);
    assertTrue(
        longestOpaqueRunOutsideMarker(layers.winrate, marker[0], marker[1], 8) < 15,
        "the current move column should not contain a full-height guide line.");
    assertSame(
        fixture.current,
        fixture.graph.resolveMoveTargetNode(marker[0], marker[1]),
        "the visible current marker should resolve to the current node.");
    if (assertCurrentColumn) {
      assertEquals(
          graphPointX(fixture.graph, fixture.current.getData().moveNumber),
          marker[0],
          "a pending current move should stay on its own move column.");
    }
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

    return setupGraph(board, current, target, 82);
  }

  private static RenderFixture ordinaryScoreLeadGapFixture() throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    board.isKataBoard = true;

    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(scoreMoveNode(0, 0, Stone.BLACK, false, 1, 1, -8.0));
    history.add(scoreMoveNode(1, 0, Stone.WHITE, true, 2, 1, 8.0));
    history.add(scoreMoveNode(2, 0, Stone.BLACK, false, 3, 0, 0.0));
    history.add(scoreMoveNode(0, 1, Stone.WHITE, true, 4, 0, 0.0));
    history.add(scoreMoveNode(1, 1, Stone.BLACK, false, 5, 0, 0.0));
    history.add(scoreMoveNode(2, 1, Stone.WHITE, true, 6, 0, 0.0));
    history.add(scoreMoveNode(0, 2, Stone.BLACK, false, 7, 0, 0.0));
    history.add(scoreMoveNode(1, 2, Stone.WHITE, true, 8, 1, 8.0));
    BoardHistoryNode current = history.getCurrentHistoryNode();
    history.setHead(current);
    board.setHistory(history);

    return setupGraph(board, current, current, 50);
  }

  private static RenderFixture whiteKataGoPkWhiteToPlayScoreFixture(
      double rawScoreMean, boolean saiData) throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;

    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(scoreMoveNode(0, 0, Stone.BLACK, false, 1, 1, 1.0));
    history.add(scoreMoveNode(1, 0, Stone.WHITE, true, 2, 1, 1.0));
    history.add(scoreMoveNode(2, 0, Stone.BLACK, false, 3, 1, rawScoreMean));
    BoardHistoryNode current = history.getCurrentHistoryNode();
    current.getData().isSaiData = saiData;
    history.setHead(current);
    board.setHistory(history);
    board.isPkBoard = true;
    board.isPkBoardKataW = true;

    return setupGraph(board, current, current, 50);
  }

  private static RenderFixture ordinaryWhiteToPlaySaiScoreFixture(double rawScoreMean)
      throws Exception {
    return ordinaryWhiteToPlaySaiLikeScoreFixture(rawScoreMean, true);
  }

  private static RenderFixture ordinaryWhiteToPlaySayuriScoreFixture(double rawScoreMean)
      throws Exception {
    return ordinaryWhiteToPlaySaiLikeScoreFixture(rawScoreMean, false);
  }

  private static RenderFixture ordinaryWhiteToPlaySaiLikeScoreFixture(
      double rawScoreMean, boolean scoreMeanIsBlackPerspective) throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    board.isKataBoard = true;

    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(scoreMoveNode(0, 0, Stone.BLACK, false, 1, 1, 1.0));
    history.add(scoreMoveNode(1, 0, Stone.WHITE, true, 2, 1, 1.0));
    history.add(scoreMoveNode(2, 0, Stone.BLACK, false, 3, 1, rawScoreMean));
    BoardHistoryNode current = history.getCurrentHistoryNode();
    current.getData().isSaiData = true;
    current.getData().scoreMeanIsBlackPerspective = scoreMeanIsBlackPerspective;
    history.setHead(current);
    board.setHistory(history);

    return setupGraph(board, current, current, 50);
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

    return setupGraph(board, current, target, 80);
  }

  private static RenderFixture modeZeroDuplicatedColumnFixture() throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;

    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveNode(0, 0, Stone.BLACK, false, 1, 35, 1));
    history.add(moveNode(1, 0, Stone.WHITE, true, 2, 42, 1));
    history.add(moveNode(2, 0, Stone.BLACK, false, 3, 50, 1));
    history.add(moveNode(0, 1, Stone.WHITE, true, 4, 82, 1));
    BoardHistoryNode target = history.getCurrentHistoryNode();
    history.add(moveNode(1, 1, Stone.BLACK, false, 5, 82, 1));
    history.add(moveNode(2, 1, Stone.WHITE, true, 6, 65, 1));
    BoardHistoryNode current = history.getCurrentHistoryNode();
    history.setHead(current);
    board.setHistory(history);

    return setupGraph(board, current, target, 82);
  }

  private static SnapshotGapFixture snapshotGapFixture() throws Exception {
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

    RenderFixture renderFixture = setupGraph(board, current, snapshotBoundary, 50);
    return new SnapshotGapFixture(renderFixture, snapshotBoundary, preGapMove);
  }

  private static RenderFixture setupGraph(
      TrackingBoard board,
      BoardHistoryNode current,
      BoardHistoryNode target,
      double targetWinrate)
      throws Exception {
    WinrateGraph graph = new WinrateGraph();
    TrackingFrame frame = allocate(TrackingFrame.class);
    javax.swing.JScrollPane commentEditPane = new javax.swing.JScrollPane();
    commentEditPane.setVisible(false);
    setField(frame, LizzieFrame.class, "commentEditPane", commentEditPane);
    setField(frame, LizzieFrame.class, "mainPanel", new javax.swing.JPanel());

    Lizzie.board = board;
    Lizzie.frame = frame;
    LizzieFrame.winrateGraph = graph;

    return new RenderFixture(board, frame, graph, current, target, targetWinrate);
  }

  private static int[] renderedPrimaryGraphPixel(RenderFixture fixture) throws Exception {
    return renderedModeZeroDotPixel(fixture.graph, fixture.target, fixture.targetWinrate);
  }

  private static int[] renderedModeZeroDotPixel(
      WinrateGraph graph, BoardHistoryNode target, double targetWinrate) throws Exception {
    renderGraphLayer(graph);
    int[] params = (int[]) getField(graph, "params");
    int expectedY = graphCenterY(params, targetWinrate);
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

  @SuppressWarnings("unchecked")
  private static int[] renderedCurrentMoveMarkerPoint(
      WinrateGraph graph, BoardHistoryNode currentNode) throws Exception {
    Method method =
        WinrateGraph.class.getDeclaredMethod(
            "currentMoveMarkerPoint", java.util.List.class, BoardHistoryNode.class);
    method.setAccessible(true);
    Object point =
        method.invoke(
            graph,
            (java.util.List<Object>) getField(graph, "renderedGraphPoints"),
            currentNode);
    if (point == null) {
      return null;
    }
    Field xField = point.getClass().getDeclaredField("x");
    Field yField = point.getClass().getDeclaredField("y");
    xField.setAccessible(true);
    yField.setAccessible(true);
    return new int[] {xField.getInt(point), yField.getInt(point)};
  }

  private static int graphPointX(WinrateGraph graph, int moveNumber) throws Exception {
    Method method = WinrateGraph.class.getDeclaredMethod("graphPointX", int.class);
    method.setAccessible(true);
    return (int) method.invoke(graph, moveNumber);
  }

  private static BufferedImage renderGraphLayer(WinrateGraph graph) {
    return renderLayers(graph).winrate;
  }

  private static RenderLayers renderLayers(WinrateGraph graph) {
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
      return new RenderLayers(winrateLayer, blunderLayer, backgroundLayer);
    } finally {
      winrateGraphics.dispose();
      blunderGraphics.dispose();
      backgroundGraphics.dispose();
    }
  }

  private static void assertColorNear(
      BufferedImage image, int[] center, Color expected, int radius) {
    for (int y = Math.max(0, center[1] - radius);
        y <= Math.min(image.getHeight() - 1, center[1] + radius);
        y++) {
      for (int x = Math.max(0, center[0] - radius);
          x <= Math.min(image.getWidth() - 1, center[0] + radius);
          x++) {
        if (sameRgb(new Color(image.getRGB(x, y), true), expected)) {
          return;
        }
      }
    }
    throw new AssertionError("expected color near point: " + expected);
  }

  private static int longestOpaqueRunOutsideMarker(
      BufferedImage image, int x, int markerY, int markerClearance) {
    int longest = 0;
    int current = 0;
    for (int y = 0; y < image.getHeight(); y++) {
      if (Math.abs(y - markerY) <= markerClearance) {
        current = 0;
        continue;
      }
      if (new Color(image.getRGB(x, y), true).getAlpha() > 0) {
        current++;
        longest = Math.max(longest, current);
      } else {
        current = 0;
      }
    }
    return longest;
  }

  private static void assertColorPresent(BufferedImage image, Color expected) {
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if (sameRgb(new Color(image.getRGB(x, y), true), expected)) {
          return;
        }
      }
    }
    throw new AssertionError("expected color in image: " + expected);
  }

  private static boolean backgroundDiffersBetweenX(
      BufferedImage fillOn, BufferedImage fillOff, int x1, int x2) {
    int left = Math.min(x1, x2) + 1;
    int right = Math.max(x1, x2) - 1;
    if (right < left) {
      return false;
    }
    int minX = Math.max(0, left);
    int maxX = Math.min(fillOn.getWidth() - 1, right);
    int height = Math.min(fillOn.getHeight(), fillOff.getHeight());
    for (int y = 0; y < height; y++) {
      for (int x = minX; x <= maxX; x++) {
        if (fillOn.getRGB(x, y) != fillOff.getRGB(x, y)) {
          return true;
        }
      }
    }
    return false;
  }

  private static BoardData scoreMoveNode(
      int x,
      int y,
      Stone color,
      boolean blackToPlay,
      int moveNumber,
      int playouts,
      double scoreMean) {
    BoardData data = moveNode(x, y, color, blackToPlay, moveNumber, 50, playouts);
    data.setScoreMean(scoreMean);
    return data;
  }

  private static boolean hasOpaquePixel(BufferedImage image) {
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if (new Color(image.getRGB(x, y), true).getAlpha() > 0) {
          return true;
        }
      }
    }
    return false;
  }

  private static int widestColorRun(BufferedImage image, Color expected) {
    int widest = 0;
    for (int y = 0; y < image.getHeight(); y++) {
      int current = 0;
      for (int x = 0; x < image.getWidth(); x++) {
        if (sameRgb(new Color(image.getRGB(x, y), true), expected)) {
          current++;
          widest = Math.max(widest, current);
        } else {
          current = 0;
        }
      }
    }
    return widest;
  }

  private static boolean sameRgb(Color actual, Color expected) {
    return actual.getAlpha() > 0
        && actual.getRed() == expected.getRed()
        && actual.getGreen() == expected.getGreen()
        && actual.getBlue() == expected.getBlue();
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

  private static int[] blankPixelResolvingToNode(
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
        if (pixel.getAlpha() > 0) {
          continue;
        }
        if (graph.resolveMoveTargetNode(x, y) == expectedNode) {
          return new int[] {x, y};
        }
      }
    }
    throw new AssertionError("expected a blank graph pixel that scrubs to the target node.");
  }

  private static int[] blankPixelOnExactColumn(
      WinrateGraph graph,
      BoardHistoryNode expectedNode,
      BufferedImage layer,
      int columnX,
      int preferredY,
      int radius) {
    for (int offset = 0; offset <= radius; offset++) {
      int up = preferredY - offset;
      if (up >= 0) {
        Color pixel = new Color(layer.getRGB(columnX, up), true);
        if (pixel.getAlpha() == 0 && graph.resolveMoveTargetNode(columnX, up) == expectedNode) {
          return new int[] {columnX, up};
        }
      }
      int down = preferredY + offset;
      if (down < layer.getHeight()) {
        Color pixel = new Color(layer.getRGB(columnX, down), true);
        if (pixel.getAlpha() == 0 && graph.resolveMoveTargetNode(columnX, down) == expectedNode) {
          return new int[] {columnX, down};
        }
      }
    }
    throw new AssertionError("expected a blank exact-column pixel that stays on the target node.");
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

  private static void assertRenderedScoreLeadIsBlackPerspective(
      WinrateGraph graph, BoardData data, double expectedMean, String expectedLabel)
      throws Exception {
    RenderLayers layers = renderLayers(graph);
    double mean = WinrateGraph.blackPerspectiveScoreMean(data);
    assertEquals(expectedMean, mean, 1e-9);
    int[] params = (int[]) getField(graph, "params");
    int midline = params[1] + params[3] / 2;
    assertTrue(
        hasColorInYRange(
            layers.winrate,
            CURRENT_SCORE_MARKER_COLOR,
            midline + 1,
            layers.winrate.getHeight() - 1),
        "black-perspective white lead marker should sit below the 0 line.");
    assertFalse(
        hasColorInYRange(layers.winrate, CURRENT_SCORE_MARKER_COLOR, 0, midline),
        "black-perspective white lead marker should not sit above the 0 line.");
    assertEquals(
        expectedLabel, WinrateGraph.formatScoreLead(mean, AppLocale.ENGLISH.loadBundle()));
  }

  private static boolean hasColorInYRange(
      BufferedImage image, Color expected, int startY, int endY) {
    for (int y = Math.max(0, startY); y <= Math.min(image.getHeight() - 1, endY); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if (sameRgb(new Color(image.getRGB(x, y), true), expected)) {
          return true;
        }
      }
    }
    return false;
  }

  private static Object getField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void setField(Object target, Class<?> owner, String fieldName, Object value)
      throws Exception {
    Field field = owner.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
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

    private RenderFixture(
        TrackingBoard board,
        TrackingFrame frame,
        WinrateGraph graph,
        BoardHistoryNode current,
        BoardHistoryNode target,
        double targetWinrate) {
      this.board = board;
      this.frame = frame;
      this.graph = graph;
      this.current = current;
      this.target = target;
      this.targetWinrate = targetWinrate;
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

  private static final class RenderLayers {
    private final BufferedImage winrate;
    private final BufferedImage blunder;
    private final BufferedImage background;

    private RenderLayers(BufferedImage winrate, BufferedImage blunder, BufferedImage background) {
      this.winrate = winrate;
      this.blunder = blunder;
      this.background = background;
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
      EngineGameSnapshotFixtures.publishIdle();
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
      EngineGameSnapshotFixtures.publishIdle();
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
