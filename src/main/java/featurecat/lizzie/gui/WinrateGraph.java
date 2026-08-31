package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGamePresentation;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.util.Utils;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;

public class WinrateGraph {

  private static class QuickOverviewMove {
    final BoardHistoryNode node;
    final int moveNumber;
    final String moveName;
    final double winrate;
    final double swing;
    final boolean hasAnalysis;
    final boolean connectsToPrevious;

    QuickOverviewMove(
        BoardHistoryNode node,
        int moveNumber,
        String moveName,
        double winrate,
        double swing,
        boolean hasAnalysis,
        boolean connectsToPrevious) {
      this.node = node;
      this.moveNumber = moveNumber;
      this.moveName = moveName;
      this.winrate = winrate;
      this.swing = swing;
      this.hasAnalysis = hasAnalysis;
      this.connectsToPrevious = connectsToPrevious;
    }
  }

  private static class QuickOverviewPoint {
    final QuickOverviewMove move;
    final int x;
    final int y;

    QuickOverviewPoint(QuickOverviewMove move, int x, int y) {
      this.move = move;
      this.x = x;
      this.y = y;
    }
  }

  private static class QuickOverviewLayout {
    final List<QuickOverviewPoint> points;
    final int overviewX;
    final int overviewY;
    final int overviewWidth;
    final int overviewHeight;
    final int innerX;
    final int innerY;
    final int innerWidth;
    final int innerHeight;
    final int dotSize;
    final boolean[][] dotMask;
    final int barWidth;
    final double issueThreshold;
    final double swingScale;

    QuickOverviewLayout(
        List<QuickOverviewPoint> points,
        int overviewX,
        int overviewY,
        int overviewWidth,
        int overviewHeight,
        int innerX,
        int innerY,
        int innerWidth,
        int innerHeight,
        int dotSize,
        boolean[][] dotMask,
        int barWidth,
        double issueThreshold,
        double swingScale) {
      this.points = points;
      this.overviewX = overviewX;
      this.overviewY = overviewY;
      this.overviewWidth = overviewWidth;
      this.overviewHeight = overviewHeight;
      this.innerX = innerX;
      this.innerY = innerY;
      this.innerWidth = innerWidth;
      this.innerHeight = innerHeight;
      this.dotSize = dotSize;
      this.dotMask = dotMask;
      this.barWidth = barWidth;
      this.issueThreshold = issueThreshold;
      this.swingScale = swingScale;
    }
  }

  private static class GraphPoint {
    final BoardHistoryNode node;
    final int x;
    final int y;

    GraphPoint(BoardHistoryNode node, int x, int y) {
      this.node = node;
      this.x = x;
      this.y = y;
    }
  }

  private int DOT_RADIUS = 3;
  private static final int GRAPH_ANCHOR_HIT_HALF_SIZE = 2;
  private static final int CURRENT_MOVE_MARKER_RADIUS = 4;
  private static final Color CURRENT_MOVE_MARKER_COLOR = new Color(244, 67, 72);
  private static final Color CURRENT_MOVE_MARKER_BORDER = new Color(112, 24, 28, 230);
  private static final Color CURRENT_MOVE_MARKER_HALO = new Color(255, 250, 240, 225);
  private static final Color CURRENT_SCORE_MARKER_COLOR = new Color(46, 204, 113);
  private static final Color CURRENT_SCORE_MARKER_BORDER = new Color(20, 96, 55, 230);
  private static final Color CURRENT_SCORE_MARKER_HALO = new Color(248, 255, 245, 225);
  private int[] origParams = {0, 0, 0, 0};
  private int[] params = {0, 0, 0, 0, 0};
  public BoardHistoryNode mouseOverNode;
  // private int numMovesOfPlayed = 0;
  private double maxScoreLead = Lizzie.config.initialMaxScoreLead;
  private double weightedMaxScoreBlunder = 50;
  private boolean largeEnough = false;
  private BoardHistoryNode forkNode = null;
  private int scoreAjustMove = -10;
  private boolean scoreAjustBelow;
  private Color whiteColor = new Color(240, 240, 240);
  private boolean noC = false;
  private List<GraphPoint> renderedGraphPoints = Collections.emptyList();
  private QuickOverviewLayout renderedQuickOverviewLayout;
  private int[] renderedOrigParams = {0, 0, 0, 0};
  private int[] renderedParams = {0, 0, 0, 0, 0};
  private BoardHistoryNode renderedCurrentGraphNode;
  private BoardHistoryNode renderedGraphEndNode;
  private BoardHistoryNode renderedMainEndNode;
  private boolean renderedEngineOrPkGraphMode;
  private boolean renderedShowWinrateLine;
  private boolean renderedFrameInPlayMode;
  private final Map<Integer, boolean[][]> quickOverviewDotMaskCache = new HashMap<>();

  private int clampDotY(int dotY, int dotRadius) {
    return Math.max(origParams[1], Math.min(origParams[1] + origParams[3] - dotRadius * 2, dotY));
  }

  private Color winrateLineColor() {
    return Lizzie.config != null && Lizzie.config.winrateLineColor != null
        ? Lizzie.config.winrateLineColor
        : new Color(100, 180, 255);
  }

  private Color winrateMissLineColor() {
    return Lizzie.config != null && Lizzie.config.winrateMissLineColor != null
        ? Lizzie.config.winrateMissLineColor
        : whiteColor;
  }

  private Color winrateGuideColor(int alpha) {
    return new Color(232, 225, 210, clamp(alpha, 0, 255));
  }

  private void drawCurrentMoveMarker(Graphics2D g, int centerX, int centerY, int radius) {
    drawMetricMarker(
        g,
        centerX,
        centerY,
        radius,
        CURRENT_MOVE_MARKER_COLOR,
        CURRENT_MOVE_MARKER_BORDER,
        CURRENT_MOVE_MARKER_HALO);
  }

  private void drawCurrentScoreMarker(Graphics2D g, int centerX, int centerY, int radius) {
    drawMetricMarker(
        g,
        centerX,
        centerY,
        radius,
        CURRENT_SCORE_MARKER_COLOR,
        CURRENT_SCORE_MARKER_BORDER,
        CURRENT_SCORE_MARKER_HALO);
  }

  private void drawMetricMarker(
      Graphics2D g,
      int centerX,
      int centerY,
      int radius,
      Color fillColor,
      Color borderColor,
      Color haloColor) {
    int markerRadius = Math.max(3, radius);
    int haloRadius = markerRadius + 2;
    Paint previousPaint = g.getPaint();
    Stroke previousStroke = g.getStroke();
    g.setColor(haloColor);
    g.fillOval(
        centerX - haloRadius,
        centerY - haloRadius,
        haloRadius * 2,
        haloRadius * 2);
    drawMetricMarkerCore(g, centerX, centerY, markerRadius, fillColor, borderColor);
    g.setPaint(previousPaint);
    g.setStroke(previousStroke);
  }

  private void drawMetricMarkerCore(
      Graphics2D g,
      int centerX,
      int centerY,
      int markerRadius,
      Color fillColor,
      Color borderColor) {
    g.setColor(fillColor);
    g.fillOval(
        centerX - markerRadius,
        centerY - markerRadius,
        markerRadius * 2,
        markerRadius * 2);
    g.setColor(borderColor);
    g.setStroke(new BasicStroke(1f));
    g.drawOval(
        centerX - markerRadius,
        centerY - markerRadius,
        markerRadius * 2,
        markerRadius * 2);
  }

  private static Color withAlpha(Color color, int alpha) {
    return new Color(color.getRed(), color.getGreen(), color.getBlue(), clamp(alpha, 0, 255));
  }

  private Color getBlunderColor(double winrateDrop, double scoreDrop) {
    if (Lizzie.config != null && Lizzie.config.blunderBarColor != null) {
      return Lizzie.config.blunderBarColor;
    }
    if (scoreDrop > 5.0 || winrateDrop > 20.0) return new Color(235, 60, 60, 220);
    if (scoreDrop > 2.0 || winrateDrop > 10.0) return new Color(230, 140, 40, 220);
    if (scoreDrop > 1.0 || winrateDrop > 5.0) return new Color(230, 200, 50, 200);
    if (scoreDrop > 0.3 || winrateDrop > 2.0) return new Color(150, 180, 80, 150);
    return new Color(120, 120, 120, 50);
  }

  private void drawBlunderBar(
      Graphics2D gBlunder,
      int graphX,
      int graphWidth,
      int numMoves,
      int fromMoveIndex,
      int toMoveIndex,
      int graphHeight,
      int blunderBottom,
      double winrateDrop,
      double scoreDrop) {
    if (!Lizzie.config.showBlunderBar || numMoves <= 0) {
      return;
    }
    gBlunder.setColor(getBlunderColor(winrateDrop, scoreDrop));
    int barHeight = resolveBlunderBarHeight(graphHeight, winrateDrop, scoreDrop);
    int leftIndex = Math.min(fromMoveIndex, toMoveIndex);
    int rightIndex = Math.max(fromMoveIndex, toMoveIndex);
    int rectStart = graphX + leftIndex * graphWidth / numMoves;
    int rectEnd = graphX + rightIndex * graphWidth / numMoves;
    int rectWidth =
        Math.max(Math.max(1, Lizzie.config.minimumBlunderBarWidth), rectEnd - rectStart);
    gBlunder.fillRect(rectStart, blunderBottom - barHeight, rectWidth + 1, barHeight);
  }

  static int resolveBlunderBarHeight(int graphHeight, double winrateDrop, double scoreDrop) {
    int usableHeight = Math.max(1, graphHeight);
    int maxHeight = Math.max(4, usableHeight / 3);
    int minHeight = Math.max(2, Math.min(6, maxHeight));
    double winrateSeverity = Math.abs(winrateDrop) / 30.0;
    double scoreSeverity = Math.abs(scoreDrop) / 12.0;
    double severity = Math.max(winrateSeverity, scoreSeverity);
    if (severity <= 0.01) {
      return Math.max(1, Math.min(3, usableHeight / 24));
    }
    double easedSeverity = Math.sqrt(Math.min(1.0, severity));
    int height = (int) Math.round(minHeight + (maxHeight - minHeight) * easedSeverity);
    return clamp(height, 1, maxHeight);
  }

  static Color resolveGraphBackgroundColor(Color panelColor, boolean appleStyle) {
    Color fallback = appleStyle ? new Color(34, 39, 46, 150) : new Color(38, 44, 52, 158);
    if (panelColor == null) {
      return fallback;
    }
    Color lift = appleStyle ? new Color(62, 68, 78) : new Color(58, 66, 76);
    int red = blend(panelColor.getRed(), lift.getRed(), 0.38);
    int green = blend(panelColor.getGreen(), lift.getGreen(), 0.38);
    int blue = blend(panelColor.getBlue(), lift.getBlue(), 0.38);
    int alpha = clamp(panelColor.getAlpha() - 35, 118, 178);
    return new Color(red, green, blue, alpha);
  }

  static Color resolveGridLineColor() {
    return new Color(255, 255, 255, 72);
  }

  static final class RenderableMetrics {
    final boolean winrateRenderable;
    final boolean scoreRenderable;
    final int renderableCount;

    RenderableMetrics(boolean winrateRenderable, boolean scoreRenderable) {
      this.winrateRenderable = winrateRenderable;
      this.scoreRenderable = scoreRenderable;
      this.renderableCount = (winrateRenderable ? 1 : 0) + (scoreRenderable ? 1 : 0);
    }

    boolean areaFillEligible(boolean showWinrateGraphFill) {
      return showWinrateGraphFill && renderableCount == 1;
    }
  }

  static final class BaselineFillShape {
    final boolean aboveBaseline;
    final List<Point2D.Double> vertices;

    BaselineFillShape(boolean aboveBaseline, List<Point2D.Double> vertices) {
      this.aboveBaseline = aboveBaseline;
      this.vertices = vertices;
    }
  }

  @FunctionalInterface
  interface BaselineFillConsumer {
    void polygon(
        boolean aboveBaseline,
        double x1,
        double y1,
        double x2,
        double y2,
        double x3,
        double y3,
        double x4,
        double y4,
        int vertexCount);
  }

  static RenderableMetrics resolveRenderableMetrics(
      boolean winrateLineEnabled, boolean scoreLeadLineEnabled, boolean scoreLeadAvailable) {
    return new RenderableMetrics(winrateLineEnabled, scoreLeadLineEnabled && scoreLeadAvailable);
  }

  static boolean resolveScoreLeadAvailable(
      boolean engineOrPkBoard,
      boolean whiteKataScoreMode,
      boolean blackKataScoreMode,
      boolean saiOrKatago,
      boolean kataBoard) {
    if (engineOrPkBoard) {
      return whiteKataScoreMode || blackKataScoreMode;
    }
    return saiOrKatago || kataBoard;
  }

  static boolean shouldFillSegment(boolean areaFillEligible, boolean missingAnalysis) {
    return areaFillEligible && !missingAnalysis;
  }

  static Color resolveAboveBaselineFillColor(Color curveColor) {
    Color source = curveColor != null ? curveColor : new Color(100, 180, 255);
    return new Color(source.getRed(), source.getGreen(), source.getBlue(), 52);
  }

  static Color resolveBelowBaselineFillColor(Color background) {
    boolean darkBackground = background == null || relativeLuminance(background) < 0.5;
    return darkBackground ? new Color(232, 225, 210, 42) : new Color(50, 54, 60, 42);
  }

  static Color resolveBaselineLineColor() {
    return new Color(236, 232, 224, 168);
  }

  static String formatScoreLead(double blackPerspectiveScore, ResourceBundle bundle) {
    double magnitudeValue = Math.round(Math.abs(blackPerspectiveScore) * 10.0) / 10.0;
    String magnitude = String.format(Locale.ENGLISH, "%.1f", magnitudeValue);
    if ("0.0".equals(magnitude)) {
      return "0.0";
    }
    String prefix =
        blackPerspectiveScore > 0
            ? bundle.getString("WinrateGraph.scoreLeadBlackPrefix")
            : bundle.getString("WinrateGraph.scoreLeadWhitePrefix");
    return prefix + magnitude;
  }

  static double blackPerspectiveScoreMean(BoardData data) {
    if (data.scoreMeanIsBlackPerspective) {
      return data.scoreMean;
    }
    return data.blackToPlay ? data.scoreMean : -data.scoreMean;
  }

  static String baselineMark(RenderableMetrics metrics) {
    if (metrics == null || metrics.renderableCount != 1) {
      return null;
    }
    if (metrics.winrateRenderable) {
      return "50%";
    }
    return "0";
  }

  static boolean hasHighlightedBaseline(RenderableMetrics metrics) {
    return metrics != null && metrics.renderableCount > 0;
  }

  static boolean shouldSkipOrdinaryMidlineGrid(
      int gridIndex, int gridLineCount, boolean baselineActive) {
    return baselineActive && gridLineCount > 0 && gridIndex * 2 == gridLineCount + 1;
  }

  static int scoreLeadAnchorY(
      int graphY, int graphHeight, double blackPerspectiveScore, double maxScoreLead) {
    double scoreScale = Math.max(1.0, maxScoreLead);
    return graphY
        + graphHeight / 2
        - (int) (blackPerspectiveScore * graphHeight / 2 / scoreScale);
  }

  static Rectangle baselineChipBox(int graphX, int baselineY, int textWidth, int textHeight) {
    int padX = 4;
    int padY = 1;
    int width = textWidth + padX * 2;
    int height = textHeight + padY * 2;
    return new Rectangle(graphX + 4, baselineY - height / 2, width, height);
  }

  static Rectangle placeGraphLabelBox(
      Rectangle preferred, Rectangle bounds, List<Rectangle> occupied) {
    Rectangle box = clampToBounds(preferred, bounds);
    if (!intersectsAny(box, occupied)) {
      return box;
    }
    int step = Math.max(1, preferred.height + 1);
    int[] dys = {-step, step, -2 * step, 2 * step, -3 * step, 3 * step};
    int[] dxs = {0, preferred.width + 4, -(preferred.width + 4)};
    for (int dy : dys) {
      for (int dx : dxs) {
        Rectangle candidate =
            clampToBounds(
                new Rectangle(
                    preferred.x + dx, preferred.y + dy, preferred.width, preferred.height),
                bounds);
        if (!intersectsAny(candidate, occupied)) {
          return candidate;
        }
      }
    }
    return box;
  }

  private static Rectangle clampToBounds(Rectangle box, Rectangle bounds) {
    if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
      return new Rectangle(box);
    }
    int x = box.x;
    int y = box.y;
    if (box.width >= bounds.width) {
      x = bounds.x;
    } else {
      x = Math.max(bounds.x, Math.min(box.x, bounds.x + bounds.width - box.width));
    }
    if (box.height >= bounds.height) {
      y = bounds.y;
    } else {
      y = Math.max(bounds.y, Math.min(box.y, bounds.y + bounds.height - box.height));
    }
    return new Rectangle(x, y, box.width, box.height);
  }

  private static boolean intersectsAny(Rectangle box, List<Rectangle> occupied) {
    if (occupied == null) {
      return false;
    }
    for (Rectangle other : occupied) {
      if (other != null && box.intersects(other)) {
        return true;
      }
    }
    return false;
  }

  private static double relativeLuminance(Color color) {
    return (0.2126 * color.getRed() + 0.7152 * color.getGreen() + 0.0722 * color.getBlue())
        / 255.0;
  }

  static List<BaselineFillShape> baselineFillShapes(
      double x1, double y1, double x2, double y2, double baselineY) {
    ArrayList<BaselineFillShape> shapes = new ArrayList<>(2);
    visitBaselineFillShapes(
        x1,
        y1,
        x2,
        y2,
        baselineY,
        (aboveBaseline, vx1, vy1, vx2, vy2, vx3, vy3, vx4, vy4, vertexCount) -> {
          ArrayList<Point2D.Double> vertices = new ArrayList<>(vertexCount);
          vertices.add(new Point2D.Double(vx1, vy1));
          vertices.add(new Point2D.Double(vx2, vy2));
          vertices.add(new Point2D.Double(vx3, vy3));
          if (vertexCount > 3) {
            vertices.add(new Point2D.Double(vx4, vy4));
          }
          shapes.add(new BaselineFillShape(aboveBaseline, vertices));
        });
    return shapes;
  }

  static void visitBaselineFillShapes(
      double x1,
      double y1,
      double x2,
      double y2,
      double baselineY,
      BaselineFillConsumer consumer) {
    if (y1 == baselineY && y2 == baselineY) {
      return;
    }
    double side1 = y1 - baselineY;
    double side2 = y2 - baselineY;
    if (side1 * side2 >= 0) {
      boolean above = y1 < baselineY || y2 < baselineY;
      consumer.polygon(above, x1, y1, x2, y2, x2, baselineY, x1, baselineY, 4);
      return;
    }
    double t = (baselineY - y1) / (y2 - y1);
    double xi = x1 + t * (x2 - x1);
    consumer.polygon(y1 < baselineY, x1, y1, xi, baselineY, x1, baselineY, 0, 0, 3);
    consumer.polygon(y2 < baselineY, x2, y2, xi, baselineY, x2, baselineY, 0, 0, 3);
  }

  private static int blend(int base, int lift, double liftRatio) {
    return clamp((int) Math.round(base * (1.0 - liftRatio) + lift * liftRatio), 0, 255);
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  public void draw(
      Graphics2D g,
      Graphics2D gBlunder,
      Graphics2D gBackground,
      int posx,
      int posy,
      int width,
      int height) {
    largeEnough = width > 475 && height > 335;
    clearRenderedPointSources();
    BoardHistoryNode curMove = Lizzie.board.getHistory().getCurrentHistoryNode();
    BoardHistoryNode requestedCurrentMove = curMove;
    BoardHistoryNode node = curMove;
    // draw background rectangle
    final Paint customBackground =
        resolveGraphBackgroundColor(
            Lizzie.config.commentBackgroundColor, Lizzie.config.isAppleStyle);
    gBackground.setPaint(customBackground);
    gBackground.fillRect(posx, posy, width, height);

    int strokeRadius = 1;
    // record parameters (before resizing) for calculating moveNumber
    origParams[0] = posx;
    origParams[1] = posy;
    origParams[2] = width;
    origParams[3] = height;
    int blunderBottom = posy + height;

    // resize the box now so it's inside the border
    posy += 2 * strokeRadius;
    width -= 6 * strokeRadius;
    height -= 4 * strokeRadius;

    // draw lines marking 50% 60% 70% etc.
    Stroke dashed =
        new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[] {4}, 0);
    gBackground.setStroke(dashed);

    boolean suppressGraphContent =
        Lizzie.frame.isInPlayMode()
            || shouldSuppressForActiveHumanSlGame(Lizzie.frame.humanSlGame);
    RenderableMetrics renderableMetrics =
        suppressGraphContent ? null : currentRenderableMetrics();
    String baselineText = baselineMark(renderableMetrics);
    boolean baselineActive = hasHighlightedBaseline(renderableMetrics);
    gBackground.setColor(resolveGridLineColor());
    int winRateGridLines = Lizzie.frame.winRateGridLines;
    for (int i = 1; i <= winRateGridLines; i++) {
      if (shouldSkipOrdinaryMidlineGrid(i, winRateGridLines, baselineActive)) {
        continue;
      }
      double percent = i * 100.0 / (winRateGridLines + 1);
      int y = posy + height - (int) (height * percent / 100);
      gBackground.drawLine(posx, y, posx + width, y);
    }
    if (suppressGraphContent) return;
    boolean engineOrPkBoard = engineGamePlaying() || Lizzie.board.isPkBoard;
    boolean fillEnabled =
        !engineOrPkBoard
            && Lizzie.config != null
            && renderableMetrics.areaFillEligible(Lizzie.config.showWinrateGraphFill);
    int baselineY = posy + height / 2;
    List<Rectangle> graphTextBoxes = new ArrayList<>();
    Rectangle baselineChip =
        reserveBaselineChip(gBackground, baselineY, baselineText, graphTextBoxes);
    Color activeCurveColor =
        renderableMetrics.scoreRenderable && !renderableMetrics.winrateRenderable
            ? (Lizzie.config != null && Lizzie.config.scoreMeanLineColor != null
                ? Lizzie.config.scoreMeanLineColor
                : new Color(255, 0, 255))
            : winrateLineColor();
    Color aboveFill = resolveAboveBaselineFillColor(activeCurveColor);
    Color belowFill =
        resolveBelowBaselineFillColor(
            resolveGraphBackgroundColor(
                Lizzie.config != null ? Lizzie.config.commentBackgroundColor : null,
                Lizzie.config != null && Lizzie.config.isAppleStyle));
    Path2D.Double fillPath = new Path2D.Double();
    //    if(Lizzie.frame.extraMode==8)
    //    	{if(width>65)width=width-12;
    //    	else width=width*85/100;}
    g.setColor(winrateLineColor());
    // g.setColor(Color.BLACK);
    g.setStroke(new BasicStroke(Lizzie.config.winrateStrokeWidth));

    Optional<BoardHistoryNode> topOfVariation = Optional.empty();
    int numMoves = 0;
    if (!curMove.isMainTrunk()) {
      // We're in a variation, need to draw both main trunk and variation
      // Find top of variation
      BoardHistoryNode top = curMove.findTop();
      topOfVariation = Optional.of(top);
      // Find depth of main trunk, need this for plot scaling
      numMoves = top.getDepth() + top.getData().moveNumber - 1;
      //   g.setStroke(dashed);
    }

    // Go to end of variation and work our way backwards to the root

    while (node.next().isPresent()) {
      node = node.next().get();
    }
    if (numMoves < node.getData().moveNumber - 1) {
      numMoves = node.getData().moveNumber - 1;
    }

    if (numMoves < 1) {
      paintHighlightedBaseline(
          gBackground, posx, width, baselineY, baselineText, baselineChip, baselineActive);
      return;
    }
    if (numMoves < 50) numMoves = 50;

    // Plot
    width = (int) (width * 0.98); // Leave some space after last move
    double lastWr = 50;
    double lastScore = 0;
    boolean lastNodeOk = false;
    int movenum = node.getData().moveNumber - 1;
    int lastOkMove = -1;
    //    if (Lizzie.config.dynamicWinrateGraphWidth && this.numMovesOfPlayed > 0) {
    //      numMoves = this.numMovesOfPlayed;
    //    }
    double cwr = -1;
    int cmovenum = -1;
    double mwr = -1;
    int mmovenum = -1;
    int curScoreMoveNum = -1;
    double drawCurSoreMean = 0;
    int mScoreMoveNum = -1;
    double drawmSoreMean = 0;
    int currentScoreMarkerMoveIndex = -1;
    double currentScoreMarkerMean = 0;
    if (engineOrPkBoard) {
      int saveCurMovenum = 0;
      double saveCurWr = 0;
      if (numMoves < 2) {
        paintHighlightedBaseline(
            gBackground, posx, width, baselineY, baselineText, baselineChip, baselineActive);
        return;
      }
      while (node.previous().isPresent() && node.previous().get().previous().isPresent()) {
        BoardHistoryNode twoBackNode = node.previous().get().previous().get();
        int currentMoveIndex = node.getData().moveNumber - 1;
        int twoBackMoveIndex = Math.max(0, twoBackNode.getData().moveNumber - 1);
        double wr = 50;
        double score = 0;
        if (node.getData().getPlayouts() > 0) {
          wr = node.getData().winrate;
          score = node.getData().scoreMean;
        } else if (twoBackNode.getData().getPlayouts() > 0) {
          wr = twoBackNode.getData().winrate;
          score = twoBackNode.getData().scoreMean;
        }
        if (twoBackNode.getData().getPlayouts() > 0) {
          lastWr = twoBackNode.getData().winrate;
          lastScore = twoBackNode.getData().scoreMean;
        } else {
          lastWr = wr;
          lastScore = score;
        }
        if (Lizzie.config.showBlunderBar) {
          double lastMoveRate = Math.abs(lastWr - wr);
          double lastMoveScoreRate = Math.abs(lastScore - score);
          drawBlunderBar(
              gBlunder,
              posx,
              width,
              numMoves,
              twoBackMoveIndex,
              currentMoveIndex,
              height,
              blunderBottom,
              lastMoveRate,
              lastMoveScoreRate);
        }

        lastOkMove = twoBackMoveIndex;
        if (Lizzie.config.showWinrateLine) {
          int x1 = posx + (twoBackMoveIndex * width / numMoves);
          int y1 = posy + height - (int) (lastWr * height / 100);
          int x2 = posx + (currentMoveIndex * width / numMoves);
          int y2 = posy + height - (int) (wr * height / 100);
          boolean blackSegment = node.getData().blackToPlay;
          g.setColor(blackSegment ? winrateLineColor() : winrateMissLineColor());
          drawFilledGraphLine(
              g,
              gBackground,
              fillPath,
              fillEnabled,
              false,
              x1,
              y1,
              x2,
              y2,
              baselineY,
              aboveFill,
              belowFill);
          if (curMove.previous().isPresent() && currentMoveIndex > 1) {
            if (node == curMove) {
              saveCurMovenum = currentMoveIndex;
              saveCurWr = wr;
            } else if (node == curMove.previous().get()) {
              if (node.getData().blackToPlay) {
                g.setColor(winrateLineColor());
                g.fillOval(
                    posx + (currentMoveIndex * width / numMoves) - DOT_RADIUS,
                    clampDotY(posy + height - (int) (wr * height / 100) - DOT_RADIUS, DOT_RADIUS),
                    DOT_RADIUS * 2,
                    DOT_RADIUS * 2);
                Font f =
                    new Font(
                        Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(16) : 16);
                g.setFont(f);
                String wrString = String.format(Locale.ENGLISH, "%.1f", wr);
                int stringWidth = g.getFontMetrics().stringWidth(wrString);
                int xPos = posx + (currentMoveIndex * width / numMoves) - stringWidth / 2;
                xPos = Math.max(xPos, origParams[0]);
                xPos = Math.min(xPos, origParams[0] + origParams[2] - stringWidth);
                if (wr > 50) {
                  if (wr > 90) {
                    drawPlacedString(
                        g,
                        wrString,
                        xPos,
                        posy + (height - (int) (wr * height / 100)) + 6 * DOT_RADIUS,
                        graphTextBoxes);
                  } else {
                    drawPlacedString(
                        g,
                        wrString,
                        xPos,
                        posy + (height - (int) (wr * height / 100)) - 2 * DOT_RADIUS,
                        graphTextBoxes);
                  }
                } else {
                  if (wr < 10) {
                    drawPlacedString(
                        g,
                        wrString,
                        xPos,
                        posy + (height - (int) (wr * height / 100)) - 2 * DOT_RADIUS,
                        graphTextBoxes);
                  } else {
                    drawPlacedString(
                        g,
                        wrString,
                        xPos,
                        posy + (height - (int) (wr * height / 100)) + 6 * DOT_RADIUS,
                        graphTextBoxes);
                  }
                }
              } else {
                g.setColor(winrateMissLineColor());
                g.fillOval(
                    posx + (currentMoveIndex * width / numMoves) - DOT_RADIUS,
                    clampDotY(posy + height - (int) (wr * height / 100) - DOT_RADIUS, DOT_RADIUS),
                    DOT_RADIUS * 2,
                    DOT_RADIUS * 2);
                Font f =
                    new Font(
                        Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(16) : 16);
                g.setFont(f);
                g.setColor(Color.WHITE);
                String wrString = String.format(Locale.ENGLISH, "%.1f", wr);
                int stringWidth = g.getFontMetrics().stringWidth(wrString);
                int xPos = posx + (currentMoveIndex * width / numMoves) - stringWidth / 2;
                xPos = Math.max(xPos, origParams[0]);
                xPos = Math.min(xPos, origParams[0] + origParams[2] - stringWidth);
                if (wr > 50) {
                  if (wr < 90) {
                    drawPlacedString(
                        g,
                        wrString,
                        xPos,
                        posy + (height - (int) (wr * height / 100)) - 2 * DOT_RADIUS,
                        graphTextBoxes);
                  } else {
                    drawPlacedString(
                        g,
                        wrString,
                        xPos,
                        posy + (height - (int) (wr * height / 100)) + 6 * DOT_RADIUS,
                        graphTextBoxes);
                  }
                } else {
                  if (wr < 10) {
                    drawPlacedString(
                        g,
                        wrString,
                        xPos,
                        posy + (height - (int) (wr * height / 100)) - 2 * DOT_RADIUS,
                        graphTextBoxes);
                  } else {
                    drawPlacedString(
                        g,
                        wrString,
                        xPos,
                        posy + (height - (int) (wr * height / 100)) + 6 * DOT_RADIUS,
                        graphTextBoxes);
                  }
                }
              }
            }
          }
        }
        node = node.previous().get();
      }
      if (saveCurMovenum > 1) {
        String wrString = String.format(Locale.ENGLISH, "%.1f", saveCurWr);
        int stringWidth = g.getFontMetrics().stringWidth(wrString);
        int xPos = posx + (saveCurMovenum * width / numMoves) - stringWidth / 2;
        xPos = Math.max(xPos, origParams[0]);
        xPos = Math.min(xPos, origParams[0] + origParams[2] - stringWidth);
        if (curMove.getData().blackToPlay) {
          g.setColor(winrateLineColor());
          g.fillOval(
              posx + (saveCurMovenum * width / numMoves) - DOT_RADIUS,
              clampDotY(posy + height - (int) (saveCurWr * height / 100) - DOT_RADIUS, DOT_RADIUS),
              DOT_RADIUS * 2,
              DOT_RADIUS * 2);
          Font f =
              new Font(Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(16) : 16);
          g.setFont(f);
          if (saveCurWr > 50) {
            if (saveCurWr > 90) {
              drawPlacedString(
                  g,
                  wrString,
                  xPos,
                  posy + (height - (int) (saveCurWr * height / 100)) + 6 * DOT_RADIUS,
                  graphTextBoxes);
            } else {
              drawPlacedString(
                  g,
                  wrString,
                  xPos,
                  posy + (height - (int) (saveCurWr * height / 100)) - 2 * DOT_RADIUS,
                  graphTextBoxes);
            }
          } else {
            if (saveCurWr < 10) {
              drawPlacedString(
                  g,
                  wrString,
                  xPos,
                  posy + (height - (int) (saveCurWr * height / 100)) - 2 * DOT_RADIUS,
                  graphTextBoxes);
            } else {
              drawPlacedString(
                  g,
                  wrString,
                  xPos,
                  posy + (height - (int) (saveCurWr * height / 100)) + 6 * DOT_RADIUS,
                  graphTextBoxes);
            }
          }
        } else {
          g.setColor(winrateMissLineColor());
          g.fillOval(
              posx + (saveCurMovenum * width / numMoves) - DOT_RADIUS,
              clampDotY(posy + height - (int) (saveCurWr * height / 100) - DOT_RADIUS, DOT_RADIUS),
              DOT_RADIUS * 2,
              DOT_RADIUS * 2);
          Font f =
              new Font(Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(16) : 16);
          g.setFont(f);
          g.setColor(Color.WHITE);
          if (saveCurWr > 50) {
            if (saveCurWr < 90) {
              drawPlacedString(
                  g,
                  wrString,
                  xPos,
                  posy + (height - (int) (saveCurWr * height / 100)) - 2 * DOT_RADIUS,
                  graphTextBoxes);
            } else {
              drawPlacedString(
                  g,
                  wrString,
                  xPos,
                  posy + (height - (int) (saveCurWr * height / 100)) + 6 * DOT_RADIUS,
                  graphTextBoxes);
            }
          } else {
            if (saveCurWr < 10) {
              drawPlacedString(
                  g,
                  wrString,
                  xPos,
                  posy + (height - (int) (saveCurWr * height / 100)) - 2 * DOT_RADIUS,
                  graphTextBoxes);
            } else {
              drawPlacedString(
                  g,
                  wrString,
                  xPos,
                  posy + (height - (int) (saveCurWr * height / 100)) + 6 * DOT_RADIUS,
                  graphTextBoxes);
            }
          }
        }
      }
    } else {
        boolean canDrawBlunderBar = true;
        while (node.previous().isPresent()) {
          BoardData data = node.getData();
          double wr = data.winrate;
          double score = node.getData().scoreMean;
          boolean hasAnalysis = hasPrimaryAnalysisPayload(data);
          Double displayedWinrate = displayedDefaultAnchorWinrate(node, lastWr);
          if (displayedWinrate != null) {
            wr = displayedWinrate.doubleValue();
            if (hasAnalysis) {
              if (data.winrate < 0) {
                score = lastScore;
              } else if (!data.blackToPlay) {
                score = -score;
              }
            } else {
              score = lastScore;
            }
            // if (Lizzie.frame.isPlayingAgainstLeelaz
            // && Lizzie.frame.playerIsBlack == !node.getData().blackToPlay) {
            // wr = lastWr;
            // }

            if (lastNodeOk) g.setColor(winrateLineColor());
            // g.setColor(Color.BLACK);
            else g.setColor(winrateMissLineColor());

            if (lastOkMove > 0 && lastOkMove - movenum < 25) {
              if (canDrawBlunderBar && Lizzie.config.showBlunderBar && hasAnalysis) {
                drawBlunderBar(
                    gBlunder,
                    posx,
                    width,
                    numMoves,
                    lastOkMove,
                    movenum,
                    height,
                    blunderBottom,
                    Math.abs(lastWr - wr),
                    Math.abs(lastScore - score));
              }
              if (Lizzie.config.showWinrateLine) {
                drawFilledGraphLine(
                    g,
                    gBackground,
                    fillPath,
                    fillEnabled,
                    !lastNodeOk,
                    posx + (lastOkMove * width / numMoves),
                    posy + height - (int) (lastWr * height / 100),
                    posx + (movenum * width / numMoves),
                    posy + height - (int) (wr * height / 100),
                    baselineY,
                    aboveFill,
                    belowFill);
              }
            }
            if (forkNode != null && forkNode == node) {
              canDrawBlunderBar = true;
              g.setStroke(new BasicStroke(Lizzie.config.winrateStrokeWidth));
            }
            lastWr = wr;
            lastScore = score;
            lastNodeOk = true;
            // Check if we were in a variation and has reached the main trunk
            if (topOfVariation.isPresent()
                && topOfVariation.get() == node
                && node.next().isPresent()) {
              // Reached top of variation, go to end of main trunk before continuing
              canDrawBlunderBar = false;
              forkNode = topOfVariation.get();
              g.setStroke(dashed);
              node = graphTraversalEnd(node);
              movenum = node.getData().moveNumber - 1;
              Double continuationWinrate = displayedDefaultAnchorWinrate(node, lastWr);
              if (continuationWinrate != null) {
                lastWr = continuationWinrate;
                wr = continuationWinrate;
              }
              data = node.getData();
              hasAnalysis = hasPrimaryAnalysisPayload(data);
              lastScore = data.scoreMean;
              if (!data.blackToPlay && hasAnalysis) {
                lastScore = -lastScore;
              }
              // g.setStroke(new BasicStroke(Lizzie.config.winrateStrokeWidth));
              topOfVariation = Optional.empty();
              if (continuationWinrate == null) {
                lastNodeOk = false;
              }
            }
            if (Lizzie.config.showWinrateLine) {
              if (node == curMove
                  || (curMove.previous().isPresent()
                      && node == curMove.previous().get()
                      && !hasPrimaryAnalysisPayload(curMove.getData()))) {
                g.setColor(winrateLineColor());
                g.fillOval(
                    posx + (movenum * width / numMoves) - DOT_RADIUS,
                    clampDotY(posy + height - (int) (wr * height / 100) - DOT_RADIUS, DOT_RADIUS),
                    DOT_RADIUS * 2,
                    DOT_RADIUS * 2);
                cwr = wr;
                cmovenum = movenum;
              }
            }
            lastOkMove = lastNodeOk ? movenum : -1;
          } else {
            lastNodeOk = false;
          }

          if (mouseOverNode != null && node == mouseOverNode) {
            Stroke previousStroke = g.getStroke();
            int x = posx + (movenum * width / numMoves);
            g.setStroke(dashed);

            g.setColor(winrateGuideColor(180));

            g.drawLine(x, posy, x, posy + height);
            // Show move number
            String moveNumString = "" + node.getData().moveNumber;
            //    int mw = g.getFontMetrics().stringWidth(moveNumString);
            int margin = strokeRadius;
            // int mx = x - posx < width / 2 ? x + margin : x - mw - margin;
            Font f =
                new Font(
                    Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(12) : 12);
            g.setFont(f);
            g.setColor(Color.WHITE);
            int moveNum = node.getData().moveNumber;
            if (wr < 3) {
              int fontHeight = g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent();
              if (moveNum < 10)
                g.drawString(
                    moveNumString,
                    moveNum < numMoves / 2 ? x + 3 : x - 10,
                    posy + fontHeight - margin);
              else if (Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber > 99)
                g.drawString(
                    moveNumString,
                    moveNum < numMoves / 2 ? x + 3 : x - 22,
                    posy + fontHeight - margin);
              else
                g.drawString(
                    moveNumString,
                    moveNum < numMoves / 2 ? x + 3 : x - 16,
                    posy + fontHeight - margin);
            } else {
              if (moveNum < 10)
                g.drawString(
                    moveNumString, moveNum < numMoves / 2 ? x + 3 : x - 10, posy + height - margin);
              else if (Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber > 99)
                g.drawString(
                    moveNumString, moveNum < numMoves / 2 ? x + 3 : x - 22, posy + height - margin);
              else
                g.drawString(
                    moveNumString, moveNum < numMoves / 2 ? x + 3 : x - 16, posy + height - margin);
            }
            if (Lizzie.config.showWinrateLine) {
              if (hasPrimaryAnalysisPayload(node.getData())) {
                mwr = wr;
                mmovenum = movenum;
              }
            }
            g.setStroke(previousStroke);
          }

          node = node.previous().get();
          movenum--;
        }
        g.setStroke(new BasicStroke(1));

    }
    // 添加是否显示目差
    if (Lizzie.config.showScoreLeadLine) {
      node = curMove;
      while (node.next().isPresent()) {
        node = node.next().get();
      }
      if (numMoves < node.getData().moveNumber - 1) {
        numMoves = node.getData().moveNumber - 1;
      }

      if (numMoves < 1) {
        paintHighlightedBaseline(
            gBackground, posx, width, baselineY, baselineText, baselineChip, baselineActive);
        return;
      }
      lastOkMove = -1;
      movenum = node.getData().moveNumber - 1;
      //    if (Lizzie.config.dynamicWinrateGraphWidth && this.numMovesOfPlayed > 0) {
      //      numMoves = this.numMovesOfPlayed;
      //    }
      if (engineGamePlaying() || Lizzie.board.isPkBoard) {
        setMaxScoreLead(node);
        if (whiteKataScoreMode()) {
          double lastscoreMean = -500;
          int curmovenum = -1;
          double drawcurscoreMean = 0;
          if (node.getData().blackToPlay) movenum -= 1;
          if (curMove.getData().blackToPlay && curMove.previous().isPresent())
            curMove = curMove.previous().get();
          if (node.getData().blackToPlay && node.previous().isPresent()) {
            double curscoreMean = 0;
            try {
              curscoreMean = blackPerspectiveScoreMean(node.previous().get().getData());
            } catch (Exception ex) {
            }
            if (engineGamePlaying()) {
              curmovenum = movenum;
              drawcurscoreMean = curscoreMean;
              lastscoreMean = curscoreMean;
              lastOkMove = movenum;
            }
            node = node.previous().get();
          }
          while (node.previous().isPresent() && node.previous().get().previous().isPresent()) {
            if (node.getData().getPlayouts() > 0) {
              double curscoreMean = blackPerspectiveScoreMean(node.getData());
              //              if (Math.abs(curscoreMean) > maxcoreMean)
              //            	  maxcoreMean = Math.abs(curscoreMean);

              if (node == curMove) {
                curmovenum = movenum;
                drawcurscoreMean = curscoreMean;
              }
              if (lastOkMove > 0 && Math.abs(movenum - lastOkMove) < 25) {

                if (lastscoreMean > -500) {
                  // Color lineColor = g.getColor();
                  Stroke previousStroke = g.getStroke();
                  g.setColor(Lizzie.config.scoreMeanLineColor);
                  g.setStroke(new BasicStroke(Lizzie.config.scoreLeadStrokeWidth));
                  drawFilledGraphLine(
                      g,
                      gBackground,
                      fillPath,
                      fillEnabled,
                      false,
                      posx + ((lastOkMove) * width / numMoves),
                      posy
                          + height / 2
                          - (int) (convertScoreLead(lastscoreMean) * height / 2 / maxScoreLead),
                      posx + ((movenum) * width / numMoves),
                      posy
                          + height / 2
                          - (int) (convertScoreLead(curscoreMean) * height / 2 / maxScoreLead),
                      baselineY,
                      aboveFill,
                      belowFill);
                  g.setStroke(previousStroke);
                }
              }

              lastscoreMean = curscoreMean;
              lastOkMove = movenum;
            } else {
              if (engineGamePlaying()
                  && (!node.next().isPresent() || !node.next().get().next().isPresent())) {
                curmovenum = movenum;
                drawcurscoreMean =
                    blackPerspectiveScoreMean(node.previous().get().previous().get().getData());
              }
            }
            if (node.previous().isPresent() && node.previous().get().previous().isPresent())
              node = node.previous().get().previous().get();
            movenum = movenum - 2;
          }
          if (curmovenum >= 0) {
            currentScoreMarkerMoveIndex = curmovenum;
            currentScoreMarkerMean = drawcurscoreMean;
          }
          if (lastscoreMean > -500) {
            // Color lineColor = g.getColor();
            Stroke previousStroke = g.getStroke();
            g.setColor(Lizzie.config.scoreMeanLineColor);
            g.setStroke(new BasicStroke(Lizzie.config.scoreLeadStrokeWidth));
            drawFilledGraphLine(
                g,
                gBackground,
                fillPath,
                fillEnabled,
                false,
                posx + ((lastOkMove) * width / numMoves),
                posy
                    + height / 2
                    - (int) (convertScoreLead(lastscoreMean) * height / 2 / maxScoreLead),
                posx + ((movenum) * width / numMoves),
                posy
                    + height / 2
                    - (int) (convertScoreLead(lastscoreMean) * height / 2 / maxScoreLead),
                baselineY,
                aboveFill,
                belowFill);
            g.setStroke(previousStroke);
          }
          if (curmovenum >= 0) {
            g.setColor(Color.YELLOW);
            Font f =
                new Font(
                    Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(14) : 13);
            g.setFont(f);
            int mScoreHeight =
                scoreLeadAnchorY(posy, height, convertScoreLead(drawcurscoreMean), maxScoreLead) - 3;
            int fontHeigt = g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent();
            int up = origParams[1] + fontHeigt;
            int down = origParams[1] + origParams[3];
            mScoreHeight = Math.max(up, mScoreHeight);
            mScoreHeight = Math.min(down, mScoreHeight);
            String scoreString = formatScoreLead(drawcurscoreMean, Lizzie.resourceBundle);
            int stringWidth = g.getFontMetrics().stringWidth(scoreString);
            int x = posx + (curmovenum * width / numMoves) - stringWidth / 2;
            x = Math.max(x, origParams[0]);
            x = Math.min(x, origParams[0] + origParams[2] - stringWidth);
            drawPlacedString(g, scoreString, x, mScoreHeight, graphTextBoxes);
          }
        } else if (blackKataScoreMode()) {
          double lastscoreMean = -500;
          int curmovenum = -1;
          double drawcurscoreMean = 0;
          if (!node.getData().blackToPlay) movenum -= 1;
          if (!node.getData().blackToPlay && node.previous().isPresent()) {
            double curscoreMean = 0;
            try {
              curscoreMean = blackPerspectiveScoreMean(node.previous().get().getData());
            } catch (Exception ex) {
            }
            if (engineGamePlaying()) {
              curmovenum = movenum;
              drawcurscoreMean = curscoreMean;
              lastscoreMean = curscoreMean;
              lastOkMove = movenum;
            }
            node = node.previous().get();
          }
          if (!curMove.getData().blackToPlay && curMove.previous().isPresent())
            curMove = curMove.previous().get();
          while (node.previous().isPresent() && node.previous().get().previous().isPresent()) {
            if (node.getData().getPlayouts() > 0) {

              double curscoreMean = blackPerspectiveScoreMean(node.getData());
              //              if (Math.abs(curscoreMean) > maxcoreMean)
              //            	  maxcoreMean = Math.abs(curscoreMean);

              if (node == curMove) {
                curmovenum = movenum;
                drawcurscoreMean = curscoreMean;
              }
              if (lastOkMove > 0 && Math.abs(movenum - lastOkMove) < 25) {

                if (lastscoreMean > -500) {
                  // Color lineColor = g.getColor();
                  Stroke previousStroke = g.getStroke();
                  g.setColor(Lizzie.config.scoreMeanLineColor);
                  g.setStroke(new BasicStroke(Lizzie.config.scoreLeadStrokeWidth));
                  drawFilledGraphLine(
                      g,
                      gBackground,
                      fillPath,
                      fillEnabled,
                      false,
                      posx + ((lastOkMove) * width / numMoves),
                      posy
                          + height / 2
                          - (int) (convertScoreLead(lastscoreMean) * height / 2 / maxScoreLead),
                      posx + ((movenum) * width / numMoves),
                      posy
                          + height / 2
                          - (int) (convertScoreLead(curscoreMean) * height / 2 / maxScoreLead),
                      baselineY,
                      aboveFill,
                      belowFill);
                  g.setStroke(previousStroke);
                }
              }

              lastscoreMean = curscoreMean;
              lastOkMove = movenum;
            } else {
              if (engineGamePlaying()
                  && (!node.next().isPresent() || !node.next().get().next().isPresent())) {
                curmovenum = movenum;
                drawcurscoreMean =
                    blackPerspectiveScoreMean(node.previous().get().previous().get().getData());
              }
            }
            if (node.previous().isPresent() && node.previous().get().previous().isPresent())
              node = node.previous().get().previous().get();
            movenum = movenum - 2;
          }
          if (curmovenum >= 0) {
            currentScoreMarkerMoveIndex = curmovenum;
            currentScoreMarkerMean = drawcurscoreMean;
          }
          if (lastscoreMean > -500) {
            // Color lineColor = g.getColor();
            Stroke previousStroke = g.getStroke();
            g.setColor(Lizzie.config.scoreMeanLineColor);
            g.setStroke(new BasicStroke(Lizzie.config.scoreLeadStrokeWidth));
            drawFilledGraphLine(
                g,
                gBackground,
                fillPath,
                fillEnabled,
                false,
                posx + ((lastOkMove) * width / numMoves),
                posy
                    + height / 2
                    - (int) (convertScoreLead(lastscoreMean) * height / 2 / maxScoreLead),
                posx + ((movenum) * width / numMoves),
                posy
                    + height / 2
                    - (int) (convertScoreLead(lastscoreMean) * height / 2 / maxScoreLead),
                baselineY,
                aboveFill,
                belowFill);
            g.setStroke(previousStroke);
          }
          if (curmovenum >= 0) {
            g.setColor(Color.YELLOW);
            Font f =
                new Font(
                    Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(14) : 13);
            g.setFont(f);
            int mScoreHeight =
                scoreLeadAnchorY(posy, height, convertScoreLead(drawcurscoreMean), maxScoreLead) - 3;
            int fontHeigt = g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent();
            int up = origParams[1] + fontHeigt;
            int down = origParams[1] + origParams[3];
            mScoreHeight = Math.max(up, mScoreHeight);
            mScoreHeight = Math.min(down, mScoreHeight);
            String scoreString = formatScoreLead(drawcurscoreMean, Lizzie.resourceBundle);
            int stringWidth = g.getFontMetrics().stringWidth(scoreString);
            int x = posx + (curmovenum * width / numMoves) - stringWidth / 2;
            x = Math.max(x, origParams[0]);
            x = Math.min(x, origParams[0] + origParams[2] - stringWidth);
            drawPlacedString(g, scoreString, x, mScoreHeight, graphTextBoxes);
          }
        }
      } else if (Lizzie.leelaz.isSai || Lizzie.leelaz.isKatago || Lizzie.board.isKataBoard) {
        setMaxScoreLead(node);
        double lastscoreMean = -500;
        lastNodeOk = false;
        while (node.previous().isPresent()) {
          if (node.getData().getPlayouts() > 0) {

            double curscoreMean = blackPerspectiveScoreMean(node.getData());
            if (Lizzie.config.showKataGoScoreLeadWithKomi)
              curscoreMean = curscoreMean + Lizzie.board.getHistory().getGameInfo().getKomi();
            //            if (Math.abs(curscoreMean) > maxcoreMean)
            //            	maxcoreMean = Math.abs(curscoreMean);

            if (node == curMove
                || (curMove.previous().isPresent()
                    && node == curMove.previous().get()
                    && curMove.getData().getPlayouts() <= 0)) {
              curScoreMoveNum = movenum;
              drawCurSoreMean = curscoreMean;
              currentScoreMarkerMoveIndex = movenum;
              currentScoreMarkerMean = curscoreMean;
            }
            if (mouseOverNode != null && node == mouseOverNode) {
              mScoreMoveNum = movenum;
              drawmSoreMean = curscoreMean;
            }
            if (lastOkMove > 0 && Math.abs(movenum - lastOkMove) < 25) {

              if (lastscoreMean > -500) {
                // Color lineColor = g.getColor();
                Stroke previousStroke = g.getStroke();
                g.setColor(Lizzie.config.scoreMeanLineColor);
                //                if (!node.isMainTrunk()) {
                //                  g.setStroke(dashed);
                //                } else
                g.setStroke(new BasicStroke(Lizzie.config.scoreLeadStrokeWidth));
                drawFilledGraphLine(
                    g,
                    gBackground,
                    fillPath,
                    fillEnabled,
                    !lastNodeOk,
                    posx + (lastOkMove * width / numMoves),
                    posy
                        + height / 2
                        - (int) (convertScoreLead(lastscoreMean) * height / 2 / maxScoreLead),
                    posx + (movenum * width / numMoves),
                    posy
                        + height / 2
                        - (int) (convertScoreLead(curscoreMean) * height / 2 / maxScoreLead),
                    baselineY,
                    aboveFill,
                    belowFill);
                g.setStroke(previousStroke);
              }
            }

            lastscoreMean = curscoreMean;
            lastOkMove = movenum;
            lastNodeOk = true;
          } else {
            lastNodeOk = false;
          }

          node = node.previous().get();
          movenum--;
        }
      }
      // g.setStroke(new BasicStroke(1));

      // record parameters for calculating moveNumber
    }
    paintHighlightedBaseline(
        gBackground, posx, width, baselineY, baselineText, baselineChip, baselineActive);
    int mwrHeight = -1;
    int mWinFontHeight = -1;
    int oriMWrHeight = -1;
    int mx = -1;
    if (mwr >= 0) {
      g.setColor(Color.RED);
      g.fillOval(
          posx + (mmovenum * width / numMoves) - DOT_RADIUS,
          clampDotY(posy + height - (int) (mwr * height / 100) - DOT_RADIUS, DOT_RADIUS),
          DOT_RADIUS * 2,
          DOT_RADIUS * 2);
      g.setColor(Color.WHITE);
      Font f = new Font(Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(16) : 16);
      g.setFont(f);
      oriMWrHeight = posy + (height - (int) (mwr * height / 100));
      mwrHeight = oriMWrHeight + (mwr < 10 ? -5 : (mwr > 90 ? 6 : -2) * DOT_RADIUS);
      mWinFontHeight = g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent();
      if (mwrHeight > origParams[1] + origParams[3]) {
        mwrHeight = origParams[1] + origParams[3] - 2;
      }

      String mwrString = String.format(Locale.ENGLISH, "%.1f", mwr);
      int stringWidth = g.getFontMetrics().stringWidth(mwrString);
      int x = posx + (mmovenum * width / numMoves) - stringWidth / 2;
      x = Math.max(x, origParams[0]);
      x = Math.min(x, origParams[0] + origParams[2] - stringWidth);
      mx = x;
      drawPlacedString(g, mwrString, x, mwrHeight, graphTextBoxes);
    }
    if (mScoreMoveNum >= 0) {
      //        if (Lizzie.config.dynamicWinrateGraphWidth
      //            && node.getData().moveNumber - 1 > this.numMovesOfPlayed) {
      //          this.numMovesOfPlayed = node.getData().moveNumber - 1;
      //          numMoves = this.numMovesOfPlayed;
      //        }
      g.setColor(Color.YELLOW);
      Font f = new Font(Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(14) : 14);
      g.setFont(f);
      int mScoreHeight =
          scoreLeadAnchorY(posy, height, convertScoreLead(drawmSoreMean), maxScoreLead) - 3;
      int oriScoreHeight = mScoreHeight;
      int fontHeigt = g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent();
      int up = origParams[1] + fontHeigt;
      int down = origParams[1] + origParams[3];
      mScoreHeight = Math.max(up, mScoreHeight);
      mScoreHeight = Math.min(down, mScoreHeight);
      int heightDiff = Math.abs(mwrHeight - mScoreHeight);

      if (heightDiff < fontHeigt) {
        if (oriScoreHeight < oriMWrHeight) {
          if (mwrHeight - mWinFontHeight - 1 >= up) mScoreHeight = mwrHeight - mWinFontHeight - 1;
          else mScoreHeight = mwrHeight + fontHeigt + 1;
        } else if (mwrHeight + fontHeigt + 1 <= down) mScoreHeight = mwrHeight + fontHeigt + 1;
        else mScoreHeight = mwrHeight - mWinFontHeight - 1;
      }
      if (mScoreHeight > origParams[1] + origParams[3]) {
        mScoreHeight = Math.max(origParams[1] + origParams[3], mwrHeight - mWinFontHeight);
      }
      String scoreString = formatScoreLead(drawmSoreMean, Lizzie.resourceBundle);
      int stringWidth = g.getFontMetrics().stringWidth(scoreString);
      int x = posx + (mScoreMoveNum * width / numMoves) - stringWidth / 2;
      x = Math.max(x, origParams[0]);
      x = Math.min(x, origParams[0] + origParams[2] - stringWidth);
      drawPlacedString(g, scoreString, x, mScoreHeight, graphTextBoxes);
    }

    int cwrHeight = -1;
    int winFontHeight = -1;
    int oriWrHeight = -1;
    noC = false;
    if (cwr >= 0) {
      Font f = new Font(Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(16) : 16);
      g.setFont(f);
      g.setColor(Color.WHITE);
      oriWrHeight = posy + (height - (int) (cwr * height / 100));
      cwrHeight = oriWrHeight + (cwr < 10 ? -5 : (cwr > 90 ? 6 : -2) * DOT_RADIUS);
      winFontHeight = g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent();
      if (cwrHeight > origParams[1] + origParams[3]) {
        cwrHeight = origParams[1] + origParams[3] - 2;
      }
      String wrString = String.format(Locale.ENGLISH, "%.1f", cwr);
      int stringWidth = g.getFontMetrics().stringWidth(wrString);
      int x = posx + (cmovenum * width / numMoves) - stringWidth / 2;
      x = Math.max(x, origParams[0]);
      x = Math.min(x, origParams[0] + origParams[2] - stringWidth);
      if (mx >= 0) {
        if (Math.abs(x - mx) < stringWidth) noC = true;
      }
      drawPlacedString(g, wrString, x, cwrHeight, graphTextBoxes);
    }
    if (curScoreMoveNum >= 0) {
      g.setColor(Color.YELLOW);
      Font f = new Font(Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(14) : 14);
      g.setFont(f);
      int cScoreHeight =
          scoreLeadAnchorY(posy, height, convertScoreLead(drawCurSoreMean), maxScoreLead) - 3;
      int oriScoreHeight = cScoreHeight;
      int fontHeigt = g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent();
      int up = origParams[1] + fontHeigt;
      int down = origParams[1] + origParams[3];
      cScoreHeight = Math.max(up, cScoreHeight);
      cScoreHeight = Math.min(down, cScoreHeight);
      int heightDiff = Math.abs(cwrHeight - cScoreHeight);

      if (heightDiff < fontHeigt) {
        if (heightDiff <= fontHeigt / 3 && scoreAjustMove == curScoreMoveNum) {
          if (scoreAjustBelow) cScoreHeight = cwrHeight + fontHeigt + 1;
          else cScoreHeight = cwrHeight - winFontHeight - 1;
        } else {
          if (oriScoreHeight < oriWrHeight) {
            if (cwrHeight - winFontHeight - 1 >= up) {
              cScoreHeight = cwrHeight - winFontHeight - 1;
              scoreAjustBelow = false;
            } else {
              cScoreHeight = cwrHeight + fontHeigt + 1;
              scoreAjustBelow = true;
            }
          } else if (cwrHeight + fontHeigt + 1 <= down) {
            cScoreHeight = cwrHeight + fontHeigt + 1;
            scoreAjustBelow = true;
          } else {
            cScoreHeight = cwrHeight - winFontHeight - 1;
            scoreAjustBelow = false;
          }
          if (heightDiff <= fontHeigt / 3) {
            scoreAjustMove = curScoreMoveNum;
          } else scoreAjustMove = -1;
        }
      }
      String scoreString = formatScoreLead(drawCurSoreMean, Lizzie.resourceBundle);
      int stringWidth = g.getFontMetrics().stringWidth(scoreString);
      int x = posx + (curScoreMoveNum * width / numMoves) - stringWidth / 2;
      x = Math.max(x, origParams[0]);
      x = Math.min(x, origParams[0] + origParams[2] - stringWidth);
      drawPlacedString(g, scoreString, x, cScoreHeight, graphTextBoxes);
    }
    if (width >= 150) {
      gBackground.setFont(
          new Font(Config.sysDefaultFontName, Font.PLAIN, largeEnough ? Utils.zoomOut(11) : 11));
      gBackground.setColor(new Color(200, 200, 200));
      if (numMoves <= 63) {
        for (int i = 1; i <= (numMoves / 10); i++)
          if (numMoves - i * 10 > 3)
            gBackground.drawString(
                String.valueOf(i * 10),
                posx + (i * 10 - 1) * width / numMoves - 3,
                posy + height - strokeRadius);
      } else if (numMoves <= 125) {
        for (int i = 1; i <= (numMoves / 20); i++)
          if (numMoves - i * 20 > 3)
            gBackground.drawString(
                String.valueOf(i * 20),
                posx + (i * 20 - 1) * width / numMoves - 3,
                posy + height - strokeRadius);
      } else if (numMoves < 205) {
        for (int i = 1; i <= (numMoves / 30); i++)
          if (numMoves - i * 30 > 3)
            gBackground.drawString(
                String.valueOf(i * 30),
                posx + (i * 30 - 1) * width / numMoves - 3,
                posy + height - strokeRadius);
      } else {
        for (int i = 1; i <= (numMoves / 40); i++)
          if (numMoves - i * 40 > 3)
            gBackground.drawString(
                String.valueOf(i * 40),
                posx + (i * 40 - 1) * width / numMoves - 3,
                posy + height - strokeRadius);
      }
    }
    drawLineLegend(gBackground, origParams[0], origParams[1], origParams[2]);

    params[0] = posx;
    params[1] = posy;
    params[2] = width;
    params[3] = height;
    params[4] = numMoves;
    BoardHistoryNode graphBaseNode = requestedCurrentMove;
    List<GraphPoint> renderedAnchors = buildGraphAnchorPoints(graphBaseNode);
    drawGraphAnchors(g, renderedAnchors);
    drawMainCurrentMarkers(
        g,
        renderedAnchors,
        graphBaseNode,
        currentScoreMarkerMoveIndex,
        currentScoreMarkerMean);
    QuickOverviewLayout quickOverviewLayout =
        drawQuickOverview(g, gBlunder, gBackground, graphBaseNode, posx, width, numMoves);
    rememberRenderedPointSources(quickOverviewLayout, renderedAnchors);
  }

  static boolean shouldSuppressForActiveHumanSlGame(HumanSlGameController controller) {
    return controller != null && !controller.isFinished() && !controller.isLiveAnalysisMode();
  }

  static List<String> lineLegendLabels(RenderableMetrics metrics, ResourceBundle bundle) {
    ArrayList<String> labels = new ArrayList<>();
    if (metrics != null && metrics.winrateRenderable) {
      labels.add(bundle.getString("WinrateGraph.legendWinrate"));
    }
    if (metrics != null && metrics.scoreRenderable) {
      labels.add(bundle.getString("WinrateGraph.legendScoreLead"));
    }
    return labels;
  }

  static List<Color> lineLegendColors(
      RenderableMetrics metrics, Color winrateColor, Color scoreColor) {
    ArrayList<Color> colors = new ArrayList<>();
    if (metrics != null && metrics.winrateRenderable) {
      colors.add(winrateColor);
    }
    if (metrics != null && metrics.scoreRenderable) {
      colors.add(scoreColor);
    }
    return colors;
  }

  private Rectangle reserveBaselineChip(
      Graphics2D g, int baselineY, String baselineText, List<Rectangle> occupied) {
    if (baselineText == null || g == null) {
      return null;
    }
    Font chipFont =
        new Font(Config.sysDefaultFontName, Font.PLAIN, largeEnough ? Utils.zoomOut(11) : 11);
    Font previousFont = g.getFont();
    g.setFont(chipFont);
    FontMetrics metrics = g.getFontMetrics();
    Rectangle chip =
        baselineChipBox(
            origParams[0],
            baselineY,
            metrics.stringWidth(baselineText),
            metrics.getAscent() + metrics.getDescent());
    occupied.add(chip);
    g.setFont(previousFont);
    return chip;
  }

  private void paintHighlightedBaseline(
      Graphics2D g,
      int posx,
      int width,
      int baselineY,
      String baselineText,
      Rectangle chip,
      boolean baselineActive) {
    if (g == null || !baselineActive) {
      return;
    }
    Stroke previousStroke = g.getStroke();
    g.setStroke(new BasicStroke(1.5f));
    g.setColor(resolveBaselineLineColor());
    g.drawLine(posx, baselineY, posx + width, baselineY);
    g.setStroke(previousStroke);
    if (baselineText == null || chip == null) {
      return;
    }
    Font chipFont =
        new Font(Config.sysDefaultFontName, Font.PLAIN, largeEnough ? Utils.zoomOut(11) : 11);
    Font previousFont = g.getFont();
    g.setFont(chipFont);
    FontMetrics metrics = g.getFontMetrics();
    g.setColor(new Color(0, 0, 0, 170));
    g.fillRoundRect(chip.x, chip.y, chip.width, chip.height, 6, 6);
    g.setColor(new Color(236, 232, 224, 230));
    g.drawString(baselineText, chip.x + 4, chip.y + metrics.getAscent() + 1);
    g.setFont(previousFont);
  }

  private void drawPlacedString(
      Graphics2D g, String text, int preferredX, int preferredY, List<Rectangle> occupied) {
    FontMetrics fm = g.getFontMetrics();
    int textWidth = fm.stringWidth(text);
    int textHeight = fm.getAscent() + fm.getDescent();
    Rectangle bounds = new Rectangle(origParams[0], origParams[1], origParams[2], origParams[3]);
    Rectangle preferred =
        new Rectangle(preferredX, preferredY - fm.getAscent(), textWidth, textHeight);
    Rectangle placed = placeGraphLabelBox(preferred, bounds, occupied);
    occupied.add(placed);
    g.drawString(text, placed.x, placed.y + fm.getAscent());
  }

  private void drawLineLegend(Graphics2D g, int graphX, int graphY, int graphWidth) {
    if (graphWidth < 240) return;
    Font font =
        new Font(Config.sysDefaultFontName, Font.PLAIN, largeEnough ? Utils.zoomOut(11) : 11);
    g.setFont(font);
    FontMetrics fm = g.getFontMetrics();

    RenderableMetrics metrics = currentRenderableMetrics();
    List<String> labels = lineLegendLabels(metrics, Lizzie.resourceBundle);
    if (labels.isEmpty()) {
      return;
    }
    List<Color> colors =
        lineLegendColors(
            metrics,
            winrateLineColor(),
            Lizzie.config != null && Lizzie.config.scoreMeanLineColor != null
                ? Lizzie.config.scoreMeanLineColor
                : Color.YELLOW);

    int lineLen = largeEnough ? Utils.zoomOut(16) : 16;
    int gap = largeEnough ? Utils.zoomOut(12) : 12;
    int innerPad = 5;

    int totalWidth = 0;
    for (String label : labels) {
      totalWidth += lineLen + innerPad + fm.stringWidth(label) + gap;
    }
    totalWidth -= gap;

    int paddingX = 8;
    int paddingY = 3;
    int boxWidth = totalWidth + paddingX * 2;
    int boxHeight = fm.getHeight() + paddingY * 2;
    int boxX = graphX + graphWidth - boxWidth - 6;
    int boxY = graphY + 4;

    g.setColor(new Color(0, 0, 0, 150));
    g.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 8, 8);
    g.setColor(new Color(255, 255, 255, 25));
    g.drawRoundRect(boxX, boxY, boxWidth, boxHeight, 8, 8);

    int cx = boxX + paddingX;
    int textY = boxY + paddingY + fm.getAscent();
    int lineY = textY - fm.getAscent() / 2;
    Stroke prevStroke = g.getStroke();
    g.setStroke(new BasicStroke(2.0f));
    for (int i = 0; i < labels.size(); i++) {
      g.setColor(colors.get(i));
      g.drawLine(cx, lineY, cx + lineLen, lineY);
      cx += lineLen + innerPad;
      g.setColor(new Color(210, 210, 210));
      g.drawString(labels.get(i), cx, textY);
      cx += fm.stringWidth(labels.get(i)) + gap;
    }
    g.setStroke(prevStroke);
  }

  private QuickOverviewLayout drawQuickOverview(
      Graphics2D g,
      Graphics2D gBlunder,
      Graphics2D gBackground,
      BoardHistoryNode curMove,
      int posx,
      int width,
      int numMoves) {
    QuickOverviewLayout layout = buildQuickOverviewLayout(curMove, posx, width, numMoves, g);
    if (layout == null) return null;
    occludeMainGraphUnderQuickOverview(g, gBlunder, layout);

    Color overviewLineColor = winrateLineColor();
    Stroke previousStroke = g.getStroke();

    gBackground.setColor(new Color(15, 20, 28, 205));
    gBackground.fillRoundRect(
        layout.overviewX - 2,
        layout.overviewY,
        layout.overviewWidth + 4,
        layout.overviewHeight,
        12,
        12);
    gBackground.setColor(new Color(255, 255, 255, 65));
    gBackground.drawRoundRect(
        layout.overviewX - 2,
        layout.overviewY,
        layout.overviewWidth + 4,
        layout.overviewHeight,
        12,
        12);
    gBackground.setColor(new Color(255, 255, 255, 40));
    int centerY = layout.innerY + layout.innerHeight / 2;
    gBackground.drawLine(layout.innerX, centerY, layout.innerX + layout.innerWidth, centerY);

    QuickOverviewPoint lastPoint = null;
    for (QuickOverviewPoint point : layout.points) {
      QuickOverviewMove move = point.move;

      if (move.hasAnalysis && move.swing >= layout.issueThreshold) {
        int barHeight =
            Math.max(
                3, (int) Math.round(move.swing * layout.innerHeight * 0.75 / layout.swingScale));
        gBlunder.setColor(
            quickOverviewBarColor(move.swing, layout.issueThreshold, layout.swingScale));
        gBlunder.fillRoundRect(
            point.x - layout.barWidth / 2,
            layout.innerY + layout.innerHeight - barHeight,
            layout.barWidth,
            barHeight,
            4,
            4);
      }

      if (lastPoint != null && move.connectsToPrevious) {
        g.setColor(
            lastPoint.move.hasAnalysis && move.hasAnalysis
                ? overviewLineColor
                : new Color(180, 180, 180, 140));
        g.setStroke(new BasicStroke(Math.max(1.6f, (float) Lizzie.config.winrateStrokeWidth)));
        g.drawLine(lastPoint.x, lastPoint.y, point.x, point.y);
      }
      g.setColor(quickOverviewDotColor(move, overviewLineColor));
      g.fillOval(
          point.x - layout.dotSize / 2,
          point.y - layout.dotSize / 2,
          layout.dotSize,
          layout.dotSize);
      lastPoint = point;
    }
    g.setStroke(previousStroke);

    QuickOverviewPoint currentPoint = findQuickOverviewPoint(layout.points, curMove);
    QuickOverviewPoint hoverPoint = findQuickOverviewPoint(layout.points, mouseOverNode);

    if (hoverPoint != null) {
      g.setColor(winrateGuideColor(210));
      g.drawLine(hoverPoint.x, layout.innerY, hoverPoint.x, layout.innerY + layout.innerHeight);
      g.fillOval(
          hoverPoint.x - layout.dotSize / 2,
          hoverPoint.y - layout.dotSize / 2,
          layout.dotSize,
          layout.dotSize);
      drawQuickOverviewLabel(
          g,
          hoverPoint.move,
          hoverPoint.x,
          layout.overviewY,
          layout.innerX,
          layout.innerX + layout.innerWidth);
    }
    if (currentPoint != null) {
      drawCurrentMoveMarker(
          g,
          currentPoint.x,
          currentPoint.y,
          Math.max(3, layout.dotSize / 2 + 1));
    }
    return layout;
  }

  private List<QuickOverviewMove> buildQuickOverviewMoves(BoardHistoryNode curMove) {
    ArrayList<QuickOverviewMove> moves = new ArrayList<>();
    double lastWinrate = 50;
    int lastMoveNumber = 0;
    boolean startsNewSegment = false;
    List<BoardHistoryNode> path = buildGraphPath(curMove);

    for (BoardHistoryNode pathNode : path) {
      if (!pathNode.previous().isPresent()) continue;
      BoardData data = pathNode.getData();
      boolean isSnapshot = data.isSnapshotNode();
      if (!isSnapshot && !isRealHistoryActionNode(data)) continue;
      if (!moves.isEmpty() && pathNode.getData().moveNumber <= lastMoveNumber) {
        startsNewSegment = true;
      }

      double previousWinrate = lastWinrate;
      double currentWinrate = resolveQuickOverviewWinrate(pathNode, previousWinrate);
      boolean connectsToPrevious = !moves.isEmpty() && !startsNewSegment && !isSnapshot;
      lastWinrate = currentWinrate;
      String moveName = quickOverviewMoveName(data);
      boolean hasAnalysis = hasPrimaryAnalysisPayload(data);
      double swing =
          startsNewSegment || isSnapshot
              ? 0
              : resolveQuickOverviewSwing(pathNode, previousWinrate, currentWinrate);
      moves.add(
          new QuickOverviewMove(
              pathNode,
              data.moveNumber,
              moveName,
              currentWinrate,
              swing,
              hasAnalysis,
              connectsToPrevious));
      startsNewSegment = isSnapshot;
      lastMoveNumber = data.moveNumber;
    }
    return moves;
  }

  private String quickOverviewMoveName(BoardData data) {
    if (data.isPassNode()) {
      return "PASS";
    }
    if (data.isMoveNode() && data.lastMove.isPresent()) {
      return Board.convertCoordinatesToName(data.lastMove.get()[0], data.lastMove.get()[1]);
    }
    return "SNAPSHOT";
  }

  private QuickOverviewLayout buildQuickOverviewLayout(
      BoardHistoryNode currentNode, int posx, int width, int numMoves, Graphics2D graphics) {
    if (!canShowQuickOverview(width, numMoves)) return null;

    List<QuickOverviewMove> moves = buildQuickOverviewMoves(currentNode);
    if (moves.size() < 2) return null;

    int overviewHeight = Math.max(42, Math.min(68, origParams[3] / 5));
    int overviewY = origParams[1] + origParams[3] - overviewHeight - 4;
    int innerPadding = Math.max(4, overviewHeight / 8);
    int innerX = posx + innerPadding;
    int innerY = overviewY + innerPadding;
    int innerWidth = Math.max(10, width - innerPadding * 2);
    int innerHeight = Math.max(10, overviewHeight - innerPadding * 2);
    double issueThreshold =
        Lizzie.config.blunderWinThreshold > 0 ? Lizzie.config.blunderWinThreshold : 3.0;
    double winrateScale = quickOverviewWinrateScale(moves);
    double swingScale = quickOverviewSwingScale(moves, issueThreshold);
    int dotSize = Math.max(4, DOT_RADIUS * 2);
    boolean[][] dotMask =
        graphics == null
            ? quickOverviewDotMask(dotSize)
            : quickOverviewDotMask(dotSize, graphics.getRenderingHints());
    return new QuickOverviewLayout(
        buildQuickOverviewPoints(
            moves, innerX, innerY, innerWidth, innerHeight, numMoves, winrateScale),
        posx,
        overviewY,
        width,
        overviewHeight,
        innerX,
        innerY,
        innerWidth,
        innerHeight,
        dotSize,
        dotMask,
        Math.max(2, (int) Math.ceil(innerWidth / Math.max(70.0, numMoves))),
        issueThreshold,
        swingScale);
  }

  private boolean canShowQuickOverview(int width, int numMoves) {
    return isShowQuickOverviewEnabled()
        && origParams[2] >= 180
        && origParams[3] >= 120
        && width >= 140
        && numMoves >= 2;
  }

  private boolean isShowQuickOverviewEnabled() {
    return Lizzie.config != null && Lizzie.config.showWinrateOverview;
  }

  private double quickOverviewWinrateScale(List<QuickOverviewMove> moves) {
    double maxWinrateSpread = 10;
    for (QuickOverviewMove move : moves) {
      if (move.hasAnalysis) {
        maxWinrateSpread = Math.max(maxWinrateSpread, Math.abs(move.winrate - 50.0));
      }
    }
    return Math.max(10.0, Math.ceil(maxWinrateSpread / 5.0) * 5.0);
  }

  private double quickOverviewSwingScale(List<QuickOverviewMove> moves, double issueThreshold) {
    double maxSwing = issueThreshold;
    for (QuickOverviewMove move : moves) {
      if (move.hasAnalysis) {
        maxSwing = Math.max(maxSwing, move.swing);
      }
    }
    return Math.max(issueThreshold, Math.ceil(maxSwing / 5.0) * 5.0);
  }

  private List<QuickOverviewPoint> buildQuickOverviewPoints(
      List<QuickOverviewMove> moves,
      int innerX,
      int innerY,
      int innerWidth,
      int innerHeight,
      int numMoves,
      double winrateScale) {
    ArrayList<QuickOverviewPoint> points = new ArrayList<>(moves.size());
    int centerY = innerY + innerHeight / 2;
    for (QuickOverviewMove move : moves) {
      int x = innerX + (move.moveNumber - 1) * innerWidth / numMoves;
      int y =
          centerY
              - (int) Math.round((move.winrate - 50.0) * (innerHeight / 2.0 - 2) / winrateScale);
      points.add(new QuickOverviewPoint(move, x, y));
    }
    return points;
  }

  private double resolveQuickOverviewWinrate(BoardHistoryNode node, double fallback) {
    double wr = node.getData().winrate;
    if (!hasPrimaryAnalysisPayload(node.getData()) || wr < 0) return fallback;
    if (!node.getData().blackToPlay) return 100 - wr;
    return wr;
  }

  private double resolveQuickOverviewSwing(
      BoardHistoryNode node, double previousWinrate, double currentWinrate) {
    if (node.previous().isPresent()) {
      BoardHistoryNode previousNode = node.previous().get();
      if (previousNode.nodeInfo != null
          && previousNode.nodeInfo.moveNum == node.getData().moveNumber
          && previousNode.nodeInfo.analyzed) {
        return Math.abs(previousNode.nodeInfo.diffWinrate);
      }
    }
    return Math.abs(currentWinrate - previousWinrate);
  }

  private QuickOverviewPoint findQuickOverviewPoint(
      List<QuickOverviewPoint> points, BoardHistoryNode targetNode) {
    if (targetNode == null) return null;
    for (QuickOverviewPoint point : points) {
      if (point.move.node == targetNode) return point;
    }
    return null;
  }

  private void drawQuickOverviewLabel(
      Graphics2D g, QuickOverviewMove move, int x, int overviewY, int minX, int maxX) {
    Font previousFont = g.getFont();
    Font font =
        new Font(Config.sysDefaultFontName, Font.BOLD, largeEnough ? Utils.zoomOut(11) : 11);
    g.setFont(font);

    String label =
        String.format(
            Locale.ENGLISH,
            "#%d %s %.1f%% swing %.1f",
            move.moveNumber,
            move.moveName,
            move.winrate,
            move.swing);
    FontMetrics metrics = g.getFontMetrics();
    int paddingX = 6;
    int paddingY = 4;
    int labelWidth = metrics.stringWidth(label) + paddingX * 2;
    int labelHeight = metrics.getAscent() + paddingY * 2;
    int labelX = Math.max(minX, Math.min(x - labelWidth / 2, maxX - labelWidth));
    int labelY = Math.max(origParams[1] + 2, overviewY - labelHeight - 4);

    g.setColor(new Color(0, 0, 0, 210));
    g.fillRoundRect(labelX, labelY, labelWidth, labelHeight, 10, 10);
    g.setColor(new Color(255, 255, 255, 90));
    g.drawRoundRect(labelX, labelY, labelWidth, labelHeight, 10, 10);
    g.setColor(Color.WHITE);
    g.drawString(label, labelX + paddingX, labelY + paddingY + metrics.getAscent() - 1);
    g.setFont(previousFont);
  }

  private Color quickOverviewBarColor(double swing, double threshold, double swingScale) {
    Color customColor = Lizzie.config == null ? null : Lizzie.config.blunderBarColor;
    double severity =
        Math.max(0.0, Math.min(1.0, (swing - threshold) / Math.max(1.0, swingScale - threshold)));
    int alpha = Math.min(255, (int) Math.round(150 + 80 * severity));
    if (customColor != null) {
      return withAlpha(customColor, Math.max(customColor.getAlpha(), alpha));
    }
    int red = 255;
    int green = Math.max(70, (int) Math.round(176 - 96 * severity));
    int blue = Math.max(36, (int) Math.round(84 - 48 * severity));
    return new Color(red, green, blue, alpha);
  }

  private Color quickOverviewDotColor(QuickOverviewMove move, Color analyzedColor) {
    if (move.node != null && move.node.getData().isSnapshotNode()) {
      return new Color(255, 208, 84, 210);
    }
    if (move.hasAnalysis) {
      return new Color(
          analyzedColor.getRed(), analyzedColor.getGreen(), analyzedColor.getBlue(), 210);
    }
    return new Color(200, 200, 200, 170);
  }

  private double convertScoreLead(double coreMean) {
    if (coreMean > maxScoreLead) return maxScoreLead;
    if (coreMean < 0 && Math.abs(coreMean) > maxScoreLead) return -maxScoreLead;
    return coreMean;
  }

  private void setMaxScoreLead(BoardHistoryNode lastMove) {
    resetMaxScoreLead();
    while (lastMove.previous().isPresent()) {
      Double scoreMean = Math.abs(lastMove.getData().scoreMean);
      if (scoreMean > maxScoreLead) maxScoreLead = scoreMean;
      lastMove = lastMove.previous().get();
    }
    Double scoreMean = Math.abs(lastMove.getData().scoreMean);
    if (scoreMean > maxScoreLead) maxScoreLead = scoreMean;
  }

  public void setMouseOverNode(BoardHistoryNode node) {
    mouseOverNode = node;
  }

  public void clearMouseOverNode() {
    mouseOverNode = null;
  }

  public void clearParames() {
    origParams = new int[] {0, 0, 0, 0};
    params = new int[] {0, 0, 0, 0, 0};
    clearRenderedPointSources();
  }

  BoardHistoryNode resolveMoveTargetNode(int x, int y) {
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      return null;
    }
    QuickOverviewLayout quickOverviewLayout = currentQuickOverviewLayout();
    if (quickOverviewLayout != null && isInsideQuickOverview(quickOverviewLayout, x, y)) {
      QuickOverviewPoint point = directQuickOverviewPointHit(quickOverviewLayout, x, y);
      if (point == null && !isFrameTryingMode()) {
        point = columnQuickOverviewPointHit(quickOverviewLayout, x, y);
      }
      return point == null ? null : point.move.node;
    }
    if (!isInsideGraphBounds(x, y)) {
      return null;
    }
    List<GraphPoint> points = currentGraphPoints();
    if (points.isEmpty()) {
      return null;
    }
    GraphPoint point = directGraphPointHit(points, x, y);
    if (point == null) {
      point = columnGraphPointHit(points, x, y);
    }
    return point == null ? null : point.node;
  }

  public int moveNumber(int x, int y) {
    BoardHistoryNode targetNode = resolveMoveTargetNode(x, y);
    return targetNode == null ? -1 : targetNode.getData().moveNumber;
  }

  public void resetMaxScoreLead() {
    maxScoreLead = Lizzie.config.initialMaxScoreLead;
  }

  double maxScoreLeadForModeHandoff() {
    return maxScoreLead;
  }

  void restoreMaxScoreLeadAfterFailedModeHandoff(double value) {
    maxScoreLead = value;
  }

  private BoardHistoryNode currentGraphNode() {
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      return null;
    }
    return Lizzie.board.getHistory().getCurrentHistoryNode();
  }

  private List<BoardHistoryNode> buildGraphPath(BoardHistoryNode currentNode) {
    ArrayList<BoardHistoryNode> path = new ArrayList<>();
    if (currentNode == null) {
      return path;
    }
    BoardHistoryNode node = currentNode;
    while (node.next().isPresent()) {
      node = node.next().get();
    }
    path.add(node);
    while (node.previous().isPresent()) {
      node = node.previous().get();
      path.add(node);
    }
    Collections.reverse(path);
    appendVisibleMainTrunkNodes(currentNode, path);
    return path;
  }

  private void appendVisibleMainTrunkNodes(
      BoardHistoryNode currentNode, List<BoardHistoryNode> path) {
    if (currentNode.isMainTrunk()) {
      return;
    }
    BoardHistoryNode forkNode = currentNode.findTop();
    Optional<BoardHistoryNode> mainNode = forkNode.next();
    while (mainNode.isPresent()) {
      path.add(mainNode.get());
      mainNode = mainNode.get().next();
    }
  }

  private boolean isGraphAnchorNode(BoardData data) {
    return data.isSnapshotNode() || isRealHistoryActionNode(data);
  }

  private boolean isRealHistoryActionNode(BoardData data) {
    return data.isMoveNode() || (data.isPassNode() && !data.dummy);
  }

  private List<GraphPoint> buildGraphAnchorPoints(BoardHistoryNode currentNode) {
    if (!isShowWinrateLineEnabled()) {
      return Collections.emptyList();
    }
    if (params[2] <= 0 || params[3] <= 0 || params[4] <= 0 || currentNode == null) {
      return Collections.emptyList();
    }
    List<GraphPoint> points;
    if (isEngineOrPkGraphMode()) {
      points = buildEngineGraphAnchorPoints(currentNode);
    } else {
      points = buildDefaultGraphAnchorPoints(currentNode);
    }
    return includeCurrentMoveMarkerPoint(points, currentNode);
  }

  private List<GraphPoint> includeCurrentMoveMarkerPoint(
      List<GraphPoint> points, BoardHistoryNode currentNode) {
    if (findGraphPoint(points, currentNode) != null) {
      return points;
    }
    GraphPoint markerPoint = currentMoveMarkerPoint(points, currentNode);
    if (markerPoint == null) {
      return points;
    }
    ArrayList<GraphPoint> pointsWithMarker = new ArrayList<>(points);
    pointsWithMarker.add(markerPoint);
    return pointsWithMarker;
  }

  private List<GraphPoint> buildDefaultGraphAnchorPoints(BoardHistoryNode currentNode) {
    ArrayList<GraphPoint> points = new ArrayList<>();
    BoardHistoryNode node = graphTraversalEnd(currentNode);
    Optional<BoardHistoryNode> variationTop =
        currentNode.isMainTrunk() ? Optional.empty() : Optional.of(currentNode.findTop());
    double lastWinrate = 50;
    while (node.previous().isPresent()) {
      Double displayedWinrate = displayedDefaultAnchorWinrate(node, lastWinrate);
      if (displayedWinrate != null) {
        lastWinrate = displayedWinrate;
        appendGraphPoint(points, node, displayedWinrate.doubleValue());
      }
      if (variationTop.isPresent() && variationTop.get() == node && node.next().isPresent()) {
        node = graphTraversalEnd(node);
        displayedWinrate = displayedDefaultAnchorWinrate(node, lastWinrate);
        if (displayedWinrate != null) {
          lastWinrate = displayedWinrate;
          appendGraphPoint(points, node, displayedWinrate.doubleValue());
        }
        variationTop = Optional.empty();
      }
      node = node.previous().get();
    }
    return points;
  }

  private Double displayedDefaultAnchorWinrate(BoardHistoryNode node, double fallbackWinrate) {
    Double displayedWinrate = displayedGraphWinrate(node, fallbackWinrate);
    if (displayedWinrate != null) {
      return displayedWinrate;
    }
    if (node == null || node.getData() == null) {
      return null;
    }
    BoardData data = node.getData();
    if (!data.isSnapshotNode() || hasPrimaryAnalysisPayload(data)) {
      return null;
    }
    return Math.max(0, Math.min(100, fallbackWinrate));
  }

  private List<GraphPoint> buildEngineGraphAnchorPoints(BoardHistoryNode currentNode) {
    ArrayList<GraphPoint> points = new ArrayList<>();
    BoardHistoryNode node = graphTraversalEnd(currentNode);
    while (node.previous().isPresent() && node.previous().get().previous().isPresent()) {
      BoardHistoryNode twoBackNode = node.previous().get().previous().get();
      double currentWinrate = resolveEngineGraphWinrate(node, twoBackNode);
      double twoBackWinrate = resolveEngineGraphBackWinrate(twoBackNode, currentWinrate);
      appendGraphPoint(points, node, currentWinrate);
      appendGraphPoint(points, twoBackNode, twoBackWinrate);
      node = node.previous().get();
    }
    return points;
  }

  private double resolveEngineGraphWinrate(BoardHistoryNode node, BoardHistoryNode twoBackNode) {
    if (hasPrimaryAnalysisPayload(node.getData())) {
      return node.getData().winrate;
    }
    if (hasPrimaryAnalysisPayload(twoBackNode.getData())) {
      return twoBackNode.getData().winrate;
    }
    return 50;
  }

  private double resolveEngineGraphBackWinrate(
      BoardHistoryNode twoBackNode, double fallbackWinrate) {
    if (hasPrimaryAnalysisPayload(twoBackNode.getData())) {
      return twoBackNode.getData().winrate;
    }
    return fallbackWinrate;
  }


  private boolean isEngineOrPkGraphMode() {
    return engineGamePlaying() || (Lizzie.board != null && Lizzie.board.isPkBoard);
  }

  private static boolean engineGamePlaying() {
    return EngineGamePresentation.current().playing();
  }

  private static boolean whiteKataScoreMode() {
    EngineGameSnapshot snapshot = EngineGamePresentation.current();
    return (snapshot.playing()
            && (EngineGamePresentation.whiteKatago(
                    EngineGamePresentation.currentHistoryInfo(), snapshot)
                || EngineGamePresentation.whiteSai(
                    EngineGamePresentation.currentHistoryInfo(), snapshot)))
        || (Lizzie.board != null && Lizzie.board.isPkBoardKataW);
  }

  private static boolean blackKataScoreMode() {
    EngineGameSnapshot snapshot = EngineGamePresentation.current();
    return (snapshot.playing()
            && (EngineGamePresentation.blackKatago(
                    EngineGamePresentation.currentHistoryInfo(), snapshot)
                || EngineGamePresentation.blackSai(
                    EngineGamePresentation.currentHistoryInfo(), snapshot)))
        || (Lizzie.board != null && Lizzie.board.isPkBoardKataB);
  }

  private BoardHistoryNode graphTraversalEnd(BoardHistoryNode node) {
    BoardHistoryNode current = node;
    while (current.next().isPresent()) {
      current = current.next().get();
    }
    return current;
  }

  private void appendGraphPoint(List<GraphPoint> points, BoardHistoryNode node, double winrate) {
    if (!node.previous().isPresent() || !isGraphAnchorNode(node.getData())) {
      return;
    }
    points.add(new GraphPoint(node, graphPointX(node.getData().moveNumber), graphPointY(winrate)));
  }

  private void drawGraphAnchors(Graphics2D g, List<GraphPoint> points) {
    if (points.isEmpty()) {
      return;
    }
    int markerHalfWidth = graphAnchorHitHalfWidth(points);
    int markerWidth = graphAnchorMarkerWidth(markerHalfWidth);
    int markerHeight = graphAnchorMarkerHeight();
    for (GraphPoint point : points) {
      g.setColor(graphAnchorColor(point.node.getData()));
      g.fillRect(
          point.x - markerHalfWidth,
          point.y - GRAPH_ANCHOR_HIT_HALF_SIZE,
          markerWidth,
          markerHeight);
    }
  }

  private int graphAnchorMarkerWidth(int markerHalfWidth) {
    return markerHalfWidth * 2 + 1;
  }

  private int graphAnchorMarkerHeight() {
    return GRAPH_ANCHOR_HIT_HALF_SIZE * 2 + 1;
  }

  private Color graphAnchorColor(BoardData data) {
    if (data != null && data.isSnapshotNode()) {
      return new Color(255, 208, 84, 110);
    }
    Color baseColor = winrateLineColor();
    return new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), 80);
  }

  private Double displayedGraphWinrate(BoardHistoryNode node, double fallbackWinrate) {
    if (node == null) {
      return null;
    }
    BoardData data = node.getData();
    if (data == null || !hasPrimaryAnalysisPayload(data)) {
      return null;
    }
    double winrate = data.winrate;
    if (winrate < 0) {
      winrate = 100 - fallbackWinrate;
    } else if (!data.blackToPlay) {
      winrate = 100 - winrate;
    }
    return Math.max(0, Math.min(100, winrate));
  }

  private boolean isInsideQuickOverview(QuickOverviewLayout layout, int x, int y) {
    int minX = quickOverviewHitMinX(layout);
    int maxX = quickOverviewHitMaxX(layout);
    int maxY = quickOverviewHitMaxY(layout);
    return minX <= x && x < maxX && layout.overviewY <= y && y < maxY;
  }

  private boolean hasPrimaryAnalysisPayload(BoardData data) {
    return data != null && data.hasPrimaryAnalysisPayload();
  }

  private int quickOverviewHitMinX(QuickOverviewLayout layout) {
    return layout.overviewX - 2;
  }

  private int quickOverviewHitMaxX(QuickOverviewLayout layout) {
    return layout.overviewX + layout.overviewWidth + 2;
  }

  private int quickOverviewHitMaxY(QuickOverviewLayout layout) {
    return layout.overviewY + layout.overviewHeight;
  }

  private Rectangle quickOverviewHitBounds(QuickOverviewLayout layout) {
    int minX = quickOverviewHitMinX(layout);
    int width = quickOverviewHitMaxX(layout) - minX;
    return new Rectangle(minX, layout.overviewY, width, layout.overviewHeight);
  }

  private void occludeMainGraphUnderQuickOverview(
      Graphics2D graphGraphics, Graphics2D blunderGraphics, QuickOverviewLayout layout) {
    Rectangle bounds = quickOverviewHitBounds(layout);
    clearGraphicsRegion(graphGraphics, bounds);
    clearGraphicsRegion(blunderGraphics, bounds);
  }

  private void clearGraphicsRegion(Graphics2D graphics, Rectangle bounds) {
    if (graphics == null || bounds == null || bounds.width <= 0 || bounds.height <= 0) {
      return;
    }
    Composite previousComposite = graphics.getComposite();
    try {
      graphics.setComposite(AlphaComposite.Clear);
      graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
    } finally {
      graphics.setComposite(previousComposite);
    }
  }

  private QuickOverviewPoint directQuickOverviewPointHit(
      QuickOverviewLayout layout, int targetX, int targetY) {
    QuickOverviewPoint bestPoint = null;
    long bestDistance = Long.MAX_VALUE;
    for (QuickOverviewPoint point : layout.points) {
      if (!isInsideQuickOverviewDotPixel(layout, point, targetX, targetY)) {
        continue;
      }
      long distance = quickOverviewDistanceSquared(point, targetX, targetY);
      if (distance <= bestDistance) {
        bestPoint = point;
        bestDistance = distance;
      }
    }
    return bestPoint;
  }

  private boolean isInsideQuickOverviewDotPixel(
      QuickOverviewLayout layout, QuickOverviewPoint point, int targetX, int targetY) {
    int dotSize = layout.dotSize;
    if (dotSize <= 0) {
      return false;
    }
    int dotLeft = point.x - dotSize / 2;
    int dotTop = point.y - dotSize / 2;
    int localX = targetX - dotLeft;
    int localY = targetY - dotTop;
    if (localX < 0 || localX >= dotSize || localY < 0 || localY >= dotSize) {
      return false;
    }
    boolean[][] dotMask = quickOverviewDotMask(layout);
    return dotMask[localY][localX];
  }

  private boolean[][] quickOverviewDotMask(QuickOverviewLayout layout) {
    if (layout != null
        && layout.dotMask != null
        && layout.dotMask.length == layout.dotSize
        && layout.dotSize > 0
        && layout.dotMask[0].length == layout.dotSize) {
      return layout.dotMask;
    }
    return quickOverviewDotMask(layout == null ? 0 : layout.dotSize);
  }

  private boolean[][] quickOverviewDotMask(int dotSize) {
    if (dotSize <= 0) {
      return new boolean[0][0];
    }
    boolean[][] mask = quickOverviewDotMaskCache.get(dotSize);
    if (mask != null) {
      return mask;
    }

    BufferedImage dotImage = new BufferedImage(dotSize, dotSize, BufferedImage.TYPE_INT_ARGB);
    Graphics2D dotGraphics = dotImage.createGraphics();
    try {
      dotGraphics.fillOval(0, 0, dotSize, dotSize);
    } finally {
      dotGraphics.dispose();
    }

    boolean[][] computedMask = new boolean[dotSize][dotSize];
    for (int y = 0; y < dotSize; y++) {
      for (int x = 0; x < dotSize; x++) {
        computedMask[y][x] = ((dotImage.getRGB(x, y) >>> 24) & 0xff) > 0;
      }
    }
    quickOverviewDotMaskCache.put(dotSize, computedMask);
    return computedMask;
  }

  private boolean[][] quickOverviewDotMask(int dotSize, RenderingHints renderingHints) {
    if (dotSize <= 0) {
      return new boolean[0][0];
    }
    BufferedImage dotImage = new BufferedImage(dotSize, dotSize, BufferedImage.TYPE_INT_ARGB);
    Graphics2D dotGraphics = dotImage.createGraphics();
    try {
      if (renderingHints != null) {
        dotGraphics.addRenderingHints(renderingHints);
      }
      dotGraphics.fillOval(0, 0, dotSize, dotSize);
    } finally {
      dotGraphics.dispose();
    }
    boolean[][] computedMask = new boolean[dotSize][dotSize];
    for (int y = 0; y < dotSize; y++) {
      for (int x = 0; x < dotSize; x++) {
        computedMask[y][x] = ((dotImage.getRGB(x, y) >>> 24) & 0xff) > 0;
      }
    }
    return computedMask;
  }

  private long quickOverviewDistanceSquared(QuickOverviewPoint point, int targetX, int targetY) {
    long dx = point.x - targetX;
    long dy = point.y - targetY;
    return dx * dx + dy * dy;
  }

  private QuickOverviewPoint columnQuickOverviewPointHit(
      QuickOverviewLayout layout, int targetX, int targetY) {
    QuickOverviewPoint bestPoint = null;
    long bestDistance = Long.MAX_VALUE;
    int hitHalfWidth = quickOverviewColumnHitHalfWidth(layout);
    for (QuickOverviewPoint point : layout.points) {
      if (!isInsideQuickOverviewColumnHitRegion(point, targetX, hitHalfWidth)) {
        continue;
      }
      long distance = quickOverviewDistanceSquared(point, targetX, targetY);
      if (distance <= bestDistance) {
        bestPoint = point;
        bestDistance = distance;
      }
    }
    return bestPoint;
  }

  private boolean isInsideQuickOverviewColumnHitRegion(
      QuickOverviewPoint point, int targetX, int hitHalfWidth) {
    return Math.abs(point.x - targetX) <= hitHalfWidth;
  }

  private GraphPoint directGraphPointHit(List<GraphPoint> points, int targetX, int targetY) {
    GraphPoint bestPoint = null;
    long bestDistance = Long.MAX_VALUE;
    int xHitHalfWidth = graphAnchorHitHalfWidth(points);
    for (GraphPoint point : points) {
      if (!isInsideGraphAnchorHitRegion(point, targetX, targetY, xHitHalfWidth)) {
        continue;
      }
      long distance = graphDistanceSquared(point, targetX, targetY);
      if (distance <= bestDistance) {
        bestPoint = point;
        bestDistance = distance;
      }
    }
    return bestPoint;
  }

  private GraphPoint columnGraphPointHit(List<GraphPoint> points, int targetX, int targetY) {
    Integer targetColumnX = targetGraphColumnX(targetX);
    if (targetColumnX != null) {
      GraphPoint point = graphPointHitOnColumn(points, targetColumnX.intValue(), targetX, targetY);
      if (point != null || hasGraphPointOnColumn(points, targetColumnX.intValue())) {
        return point;
      }
    }
    int fallbackColumnX = nearestVisibleGraphColumnX(points, targetX);
    if (fallbackColumnX == Integer.MIN_VALUE) {
      return null;
    }
    return graphPointHitOnColumn(points, fallbackColumnX, targetX, targetY);
  }

  private GraphPoint graphPointHitOnColumn(
      List<GraphPoint> points, int columnX, int targetX, int targetY) {
    GraphPoint bestPoint = null;
    long bestDistance = Long.MAX_VALUE;
    BoardHistoryNode firstNode = null;
    boolean hasDifferentNodes = false;
    for (GraphPoint point : points) {
      if (point.x != columnX) {
        continue;
      }
      if (firstNode == null) {
        firstNode = point.node;
      } else if (firstNode != point.node) {
        hasDifferentNodes = true;
      }
    }
    for (GraphPoint point : points) {
      if (point.x != columnX) {
        continue;
      }
      if (hasDifferentNodes && !isInsideNodeRenderedYRange(points, point.node, targetY)) {
        continue;
      }
      long distance = graphDistanceSquared(point, targetX, targetY);
      if (distance <= bestDistance) {
        bestPoint = point;
        bestDistance = distance;
      }
    }
    return bestPoint;
  }

  private boolean hasGraphPointOnColumn(List<GraphPoint> points, int columnX) {
    for (GraphPoint point : points) {
      if (point.x == columnX) {
        return true;
      }
    }
    return false;
  }

  private Integer targetGraphColumnX(int targetX) {
    if (params[2] <= 0 || params[4] <= 0) {
      return null;
    }
    double scaledMoveIndex = (double) (targetX - params[0]) * params[4] / params[2];
    int moveIndex = (int) Math.round(scaledMoveIndex);
    moveIndex = Math.max(0, Math.min(params[4] - 1, moveIndex));
    return Integer.valueOf(graphPointXByMoveIndex(moveIndex));
  }

  private int nearestVisibleGraphColumnX(List<GraphPoint> points, int targetX) {
    int bestColumnX = Integer.MIN_VALUE;
    long bestDistance = Long.MAX_VALUE;
    for (GraphPoint point : points) {
      long distance = Math.abs((long) point.x - targetX);
      if (distance < bestDistance) {
        bestColumnX = point.x;
        bestDistance = distance;
      }
    }
    return bestColumnX;
  }

  private boolean isInsideNodeRenderedYRange(
      List<GraphPoint> points, BoardHistoryNode node, int targetY) {
    for (GraphPoint other : points) {
      if (other.node != node) continue;
      if (Math.abs(other.y - targetY) <= GRAPH_ANCHOR_HIT_HALF_SIZE) {
        return true;
      }
    }
    return false;
  }

  private boolean isInsideGraphAnchorHitRegion(
      GraphPoint point, int targetX, int targetY, int xHitHalfWidth) {
    return Math.abs(point.x - targetX) <= xHitHalfWidth
        && Math.abs(point.y - targetY) <= GRAPH_ANCHOR_HIT_HALF_SIZE;
  }

  private boolean isInsideGraphColumnHitRegion(GraphPoint point, int targetX, int hitHalfWidth) {
    return Math.abs(point.x - targetX) <= hitHalfWidth;
  }

  private long graphDistanceSquared(GraphPoint point, int targetX, int targetY) {
    long dx = point.x - targetX;
    long dy = point.y - targetY;
    return dx * dx + dy * dy;
  }

  private int graphColumnHitHalfWidth(List<GraphPoint> points) {
    int minSpacing = minPositiveGraphColumnSpacing(points);
    if (minSpacing == Integer.MAX_VALUE) {
      return 0;
    }
    int spacingLimitedHalfWidth = Math.max(0, (minSpacing - 1) / 2);
    return Math.min(GRAPH_ANCHOR_HIT_HALF_SIZE, spacingLimitedHalfWidth);
  }

  private int graphAnchorHitHalfWidth(List<GraphPoint> points) {
    return graphColumnHitHalfWidth(points);
  }

  private int minPositiveGraphColumnSpacing(List<GraphPoint> points) {
    int[] xs = points.stream().mapToInt(p -> p.x).distinct().sorted().toArray();
    int minSpacing = Integer.MAX_VALUE;
    for (int i = 1; i < xs.length; i++) {
      int spacing = xs[i] - xs[i - 1];
      if (spacing > 0 && spacing < minSpacing) {
        minSpacing = spacing;
      }
    }
    return minSpacing;
  }

  private int quickOverviewColumnHitHalfWidth(QuickOverviewLayout layout) {
    int maxHalfWidth = Math.max(1, layout.barWidth);
    int minSpacing = minPositiveQuickOverviewColumnSpacing(layout.points);
    if (minSpacing == Integer.MAX_VALUE) {
      return 0;
    }
    int spacingLimitedHalfWidth = Math.max(0, (minSpacing - 1) / 2);
    return Math.min(maxHalfWidth, spacingLimitedHalfWidth);
  }

  private int minPositiveQuickOverviewColumnSpacing(List<QuickOverviewPoint> points) {
    int minSpacing = Integer.MAX_VALUE;
    for (int i = 1; i < points.size(); i++) {
      int spacing = Math.abs(points.get(i).x - points.get(i - 1).x);
      if (spacing > 0 && spacing < minSpacing) {
        minSpacing = spacing;
      }
    }
    return minSpacing;
  }

  private void rememberRenderedPointSources(QuickOverviewLayout quickOverviewLayout) {
    BoardHistoryNode graphBaseNode = currentGraphNode();
    rememberRenderedPointSources(quickOverviewLayout, buildGraphAnchorPoints(graphBaseNode));
  }

  private void rememberRenderedPointSources(
      QuickOverviewLayout quickOverviewLayout, List<GraphPoint> renderedAnchors) {
    renderedGraphPoints =
        renderedAnchors == null ? Collections.emptyList() : new ArrayList<>(renderedAnchors);
    renderedQuickOverviewLayout = quickOverviewLayout;
    renderedOrigParams = origParams.clone();
    renderedParams = params.clone();
    rememberRenderedStateSnapshot();
  }

  private void clearRenderedPointSources() {
    renderedGraphPoints = Collections.emptyList();
    renderedQuickOverviewLayout = null;
    renderedOrigParams = new int[] {0, 0, 0, 0};
    renderedParams = new int[] {0, 0, 0, 0, 0};
    renderedCurrentGraphNode = null;
    renderedGraphEndNode = null;
    renderedMainEndNode = null;
    renderedEngineOrPkGraphMode = false;
    renderedShowWinrateLine = false;
    renderedFrameInPlayMode = false;
  }

  private List<GraphPoint> currentGraphPoints() {
    if (!hasFreshRenderedSources()) {
      return Collections.emptyList();
    }
    return renderedGraphPoints;
  }

  private QuickOverviewLayout currentQuickOverviewLayout() {
    if (!hasFreshRenderedSources()) {
      return null;
    }
    return renderedQuickOverviewLayout;
  }

  private boolean hasFreshRenderedSources() {
    return hasFreshRenderedParams() && hasFreshRenderedState();
  }

  private boolean hasFreshRenderedParams() {
    return Arrays.equals(renderedOrigParams, origParams) && Arrays.equals(renderedParams, params);
  }

  private void rememberRenderedStateSnapshot() {
    BoardHistoryNode currentNode = currentGraphNode();
    renderedCurrentGraphNode = currentNode;
    renderedGraphEndNode = currentNode == null ? null : graphTraversalEnd(currentNode);
    renderedMainEndNode = currentMainEndNode();
    renderedEngineOrPkGraphMode = isEngineOrPkGraphMode();
    renderedShowWinrateLine = isShowWinrateLineEnabled();
    renderedFrameInPlayMode = isFrameInPlayMode();
  }

  private boolean hasFreshRenderedState() {
    boolean sameState =
        (!isFrameTryingMode() || currentGraphNode() == renderedCurrentGraphNode)
            && currentMainEndNode() == renderedMainEndNode
            && isEngineOrPkGraphMode() == renderedEngineOrPkGraphMode
            && isShowWinrateLineEnabled() == renderedShowWinrateLine
            && isFrameInPlayMode() == renderedFrameInPlayMode;
    if (!sameState) {
      return false;
    }
    return hasFreshRenderedGraphPoints(renderedCurrentGraphNode)
        && hasFreshRenderedQuickOverviewLayout(renderedCurrentGraphNode);
  }

  private boolean hasFreshRenderedGraphPoints(BoardHistoryNode currentNode) {
    return sameGraphPoints(renderedGraphPoints, buildGraphAnchorPoints(currentNode));
  }

  private boolean hasFreshRenderedQuickOverviewLayout(BoardHistoryNode currentNode) {
    QuickOverviewLayout currentLayout =
        buildQuickOverviewLayout(currentNode, params[0], params[2], params[4], null);
    return sameQuickOverviewLayout(renderedQuickOverviewLayout, currentLayout);
  }

  private boolean sameGraphPoints(List<GraphPoint> renderedPoints, List<GraphPoint> currentPoints) {
    if (renderedPoints.size() != currentPoints.size()) {
      return false;
    }
    for (int i = 0; i < renderedPoints.size(); i++) {
      GraphPoint renderedPoint = renderedPoints.get(i);
      GraphPoint currentPoint = currentPoints.get(i);
      if (renderedPoint.node != currentPoint.node
          || renderedPoint.x != currentPoint.x
          || renderedPoint.y != currentPoint.y) {
        return false;
      }
    }
    return true;
  }

  private boolean sameQuickOverviewLayout(
      QuickOverviewLayout rendered, QuickOverviewLayout current) {
    if (rendered == current) {
      return true;
    }
    if (rendered == null || current == null) {
      return false;
    }
    if (rendered.overviewX != current.overviewX
        || rendered.overviewY != current.overviewY
        || rendered.overviewWidth != current.overviewWidth
        || rendered.overviewHeight != current.overviewHeight
        || rendered.innerX != current.innerX
        || rendered.innerY != current.innerY
        || rendered.innerWidth != current.innerWidth
        || rendered.innerHeight != current.innerHeight
        || rendered.dotSize != current.dotSize) {
      return false;
    }
    return sameQuickOverviewPoints(rendered.points, current.points);
  }

  private boolean sameQuickOverviewPoints(
      List<QuickOverviewPoint> renderedPoints, List<QuickOverviewPoint> currentPoints) {
    if (renderedPoints.size() != currentPoints.size()) {
      return false;
    }
    for (int i = 0; i < renderedPoints.size(); i++) {
      QuickOverviewPoint renderedPoint = renderedPoints.get(i);
      QuickOverviewPoint currentPoint = currentPoints.get(i);
      if (renderedPoint.move.node != currentPoint.move.node
          || renderedPoint.x != currentPoint.x
          || renderedPoint.y != currentPoint.y) {
        return false;
      }
    }
    return true;
  }

  private BoardHistoryNode currentMainEndNode() {
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      return null;
    }
    return Lizzie.board.getHistory().getMainEnd();
  }

  private boolean isFrameInPlayMode() {
    return Lizzie.frame != null && Lizzie.frame.isInPlayMode();
  }

  private boolean isShowWinrateLineEnabled() {
    return Lizzie.config != null && Lizzie.config.showWinrateLine;
  }

  private RenderableMetrics currentRenderableMetrics() {
    boolean winrateLineEnabled = Lizzie.config != null && Lizzie.config.showWinrateLine;
    boolean scoreLeadLineEnabled = Lizzie.config != null && Lizzie.config.showScoreLeadLine;
    return resolveRenderableMetrics(winrateLineEnabled, scoreLeadLineEnabled, scoreLeadAvailable());
  }

  private boolean scoreLeadAvailable() {
    boolean engineOrPkBoard =
        engineGamePlaying() || (Lizzie.board != null && Lizzie.board.isPkBoard);
    boolean saiOrKatago =
        Lizzie.leelaz != null && (Lizzie.leelaz.isSai || Lizzie.leelaz.isKatago);
    boolean kataBoard = Lizzie.board != null && Lizzie.board.isKataBoard;
    return resolveScoreLeadAvailable(
        engineOrPkBoard, whiteKataScoreMode(), blackKataScoreMode(), saiOrKatago, kataBoard);
  }

  private void drawFilledGraphLine(
      Graphics2D gLine,
      Graphics2D gFill,
      Path2D.Double fillPath,
      boolean fillEligible,
      boolean missingAnalysis,
      int x1,
      int y1,
      int x2,
      int y2,
      int baselineY,
      Color aboveFill,
      Color belowFill) {
    fillBaselineAreaIfNeeded(
        gFill,
        fillPath,
        fillEligible,
        missingAnalysis,
        x1,
        y1,
        x2,
        y2,
        baselineY,
        aboveFill,
        belowFill);
    gLine.drawLine(x1, y1, x2, y2);
  }

  private void fillBaselineAreaIfNeeded(
      Graphics2D gFill,
      Path2D.Double fillPath,
      boolean fillEligible,
      boolean missingAnalysis,
      double x1,
      double y1,
      double x2,
      double y2,
      double baselineY,
      Color aboveFill,
      Color belowFill) {
    if (!shouldFillSegment(fillEligible, missingAnalysis) || gFill == null || fillPath == null) {
      return;
    }
    Paint previous = gFill.getPaint();
    visitBaselineFillShapes(
        x1,
        y1,
        x2,
        y2,
        baselineY,
        (aboveBaseline, vx1, vy1, vx2, vy2, vx3, vy3, vx4, vy4, vertexCount) -> {
          fillPath.reset();
          fillPath.moveTo(vx1, vy1);
          fillPath.lineTo(vx2, vy2);
          fillPath.lineTo(vx3, vy3);
          if (vertexCount > 3) {
            fillPath.lineTo(vx4, vy4);
          }
          fillPath.closePath();
          gFill.setColor(aboveBaseline ? aboveFill : belowFill);
          gFill.fill(fillPath);
        });
    gFill.setPaint(previous);
  }

  private boolean isFrameTryingMode() {
    return Lizzie.frame != null && Lizzie.frame.isTrying;
  }

  private GraphPoint findGraphPoint(List<GraphPoint> points, BoardHistoryNode targetNode) {
    if (targetNode == null) return null;
    for (GraphPoint point : points) {
      if (point.node == targetNode) return point;
    }
    return null;
  }

  private void drawMainCurrentMarkers(
      Graphics2D g,
      List<GraphPoint> points,
      BoardHistoryNode currentNode,
      int scoreMoveIndex,
      double displayedScoreMean) {
    GraphPoint movePoint = currentMoveMarkerPoint(points, currentNode);
    int[] scorePoint = currentScoreMarkerPoint(scoreMoveIndex, displayedScoreMean);
    if (scorePoint == null) {
      if (movePoint != null) {
        drawCurrentMoveMarker(g, movePoint.x, movePoint.y, CURRENT_MOVE_MARKER_RADIUS);
      }
      return;
    }
    if (movePoint == null) {
      drawCurrentScoreMarker(g, scorePoint[0], scorePoint[1], CURRENT_MOVE_MARKER_RADIUS);
      return;
    }
    int deltaX = movePoint.x - scorePoint[0];
    int deltaY = movePoint.y - scorePoint[1];
    int overlapDistance = CURRENT_MOVE_MARKER_RADIUS * 2 + 2;
    boolean overlapping = deltaX * deltaX + deltaY * deltaY <= overlapDistance * overlapDistance;
    if (!overlapping) {
      drawCurrentScoreMarker(g, scorePoint[0], scorePoint[1], CURRENT_MOVE_MARKER_RADIUS);
      drawCurrentMoveMarker(g, movePoint.x, movePoint.y, CURRENT_MOVE_MARKER_RADIUS);
      return;
    }

    drawCurrentScoreMarker(g, scorePoint[0], scorePoint[1], CURRENT_MOVE_MARKER_RADIUS + 3);
    Paint previousPaint = g.getPaint();
    Stroke previousStroke = g.getStroke();
    drawMetricMarkerCore(
        g,
        movePoint.x,
        movePoint.y,
        Math.max(3, CURRENT_MOVE_MARKER_RADIUS - 1),
        CURRENT_MOVE_MARKER_COLOR,
        CURRENT_MOVE_MARKER_BORDER);
    g.setPaint(previousPaint);
    g.setStroke(previousStroke);
  }

  private int[] currentScoreMarkerPoint(int moveIndex, double displayedScoreMean) {
    if (!Lizzie.config.showScoreLeadLine
        || moveIndex < 0
        || params[2] <= 0
        || params[3] <= 0
        || params[4] <= 0) {
      return null;
    }
    int x = params[0] + moveIndex * params[2] / params[4];
    double scoreScale = Math.max(1.0, maxScoreLead);
    int y =
        params[1]
            + params[3] / 2
            - (int) (convertScoreLead(displayedScoreMean) * params[3] / 2 / scoreScale);
    y = clamp(y, params[1], params[1] + params[3]);
    return new int[] {x, y};
  }

  private GraphPoint currentMoveMarkerPoint(
      List<GraphPoint> points, BoardHistoryNode currentNode) {
    GraphPoint exactPoint = findGraphPoint(points, currentNode);
    if (exactPoint != null) {
      return exactPoint;
    }
    if (currentNode == null || currentNode.getData() == null) {
      return null;
    }
    BoardHistoryNode fallbackNode = currentNode;
    while (fallbackNode.previous().isPresent()) {
      fallbackNode = fallbackNode.previous().get();
      GraphPoint fallbackPoint = findGraphPoint(points, fallbackNode);
      if (fallbackPoint != null) {
        return new GraphPoint(
            currentNode,
            graphPointX(currentNode.getData().moveNumber),
            fallbackPoint.y);
      }
    }
    return null;
  }

  private int[] renderedGraphPoint(BoardHistoryNode targetNode) {
    GraphPoint point = findGraphPoint(currentGraphPoints(), targetNode);
    if (point == null) return null;
    return new int[] {point.x, point.y};
  }

  private int[] renderedQuickOverviewPoint(BoardHistoryNode targetNode) {
    QuickOverviewLayout layout = currentQuickOverviewLayout();
    if (layout == null) return null;
    QuickOverviewPoint point = findQuickOverviewPoint(layout.points, targetNode);
    if (point == null) return null;
    return new int[] {point.x, point.y};
  }

  private int graphPointX(int moveNumber) {
    return graphPointXByMoveIndex(moveNumber - 1);
  }

  private int graphPointXByMoveIndex(int moveIndex) {
    int x = params[0] + moveIndex * params[2] / params[4];
    int maxX = params[0] + Math.max(0, params[2] - 1);
    return Math.max(params[0], Math.min(maxX, x));
  }

  private int graphPointY(double winrate) {
    int y = params[1] + params[3] - (int) (winrate * params[3] / 100);
    int maxY = params[1] + Math.max(0, params[3] - 1);
    return Math.max(params[1], Math.min(maxY, y));
  }

  private boolean isInsideGraphBounds(int x, int y) {
    int origPosx = origParams[0];
    int origPosy = origParams[1];
    int origWidth = origParams[2];
    int origHeight = origParams[3];
    return origPosx <= x && x < origPosx + origWidth && origPosy <= y && y < origPosy + origHeight;
  }
}
