package featurecat.lizzie.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class BoardStylePainter {
  private static final String[] CHINESE_NUMERALS = {
    "\u4e00",
    "\u4e8c",
    "\u4e09",
    "\u56db",
    "\u4e94",
    "\u516d",
    "\u4e03",
    "\u516b",
    "\u4e5d",
    "\u5341",
    "\u5341\u4e00",
    "\u5341\u4e8c",
    "\u5341\u4e09",
    "\u5341\u56db",
    "\u5341\u4e94",
    "\u5341\u516d",
    "\u5341\u4e03",
    "\u5341\u516b",
    "\u5341\u4e5d"
  };

  private BoardStylePainter() {}

  static List<Point> chineseClassicMarkerPoints(int boardWidth, int boardHeight) {
    if (boardWidth != boardHeight || boardWidth < 5) {
      return Collections.emptyList();
    }

    int edgeOffset = chineseClassicEdgeOffset(boardWidth);
    int oppositeEdge = boardWidth - 1 - edgeOffset;
    int center = boardWidth / 2;
    if (edgeOffset <= 0 || edgeOffset >= center || oppositeEdge <= center) {
      return Collections.emptyList();
    }

    List<Point> points = new ArrayList<>(5);
    points.add(new Point(edgeOffset, edgeOffset));
    points.add(new Point(oppositeEdge, edgeOffset));
    points.add(new Point(center, center));
    points.add(new Point(edgeOffset, oppositeEdge));
    points.add(new Point(oppositeEdge, oppositeEdge));
    return points;
  }

  static String chineseClassicCoordinateLabel(int index, int boardSize) {
    if (index < 0 || index >= boardSize || boardSize <= 0) {
      return "";
    }
    int lineNumber = Math.min(index + 1, boardSize - index);
    if (lineNumber >= 1 && lineNumber <= CHINESE_NUMERALS.length) {
      return CHINESE_NUMERALS[lineNumber - 1];
    }
    return String.valueOf(lineNumber);
  }

  static void drawChineseClassicMarkers(
      Graphics2D g,
      int scaledMarginWidth,
      int scaledMarginHeight,
      int squareWidth,
      int squareHeight,
      int boardColumns,
      int boardRows) {
    List<Point> markerPoints = chineseClassicMarkerPoints(boardColumns, boardRows);
    if (markerPoints.isEmpty()) {
      return;
    }

    Color oldColor = g.getColor();
    Stroke oldStroke = g.getStroke();
    Object oldAntialias = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);

    double gridSize = Math.max(1.0, Math.min(squareWidth, squareHeight));
    double gap = Math.max(2.0, gridSize * 0.11);
    double arm = Math.max(gap + 2.0, gridSize * 0.30);
    float strokeWidth = (float) Math.max(1.1, Math.min(3.0, gridSize * 0.045));

    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(new Color(20, 16, 10));
    g.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));

    for (Point point : markerPoints) {
      double centerX = scaledMarginWidth + squareWidth * point.x;
      double centerY = scaledMarginHeight + squareHeight * point.y;
      drawCornerMarker(g, centerX, centerY, gap, arm);
    }

    g.setColor(oldColor);
    g.setStroke(oldStroke);
    if (oldAntialias != null) {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAntialias);
    }
  }

  private static int chineseClassicEdgeOffset(int boardSize) {
    if (boardSize >= 13) {
      return 3;
    }
    if (boardSize >= 7) {
      return 2;
    }
    return 1;
  }

  private static void drawCornerMarker(
      Graphics2D g, double centerX, double centerY, double gap, double arm) {
    double left = centerX - arm;
    double right = centerX + arm;
    double top = centerY - arm;
    double bottom = centerY + arm;
    double innerLeft = centerX - gap;
    double innerRight = centerX + gap;
    double innerTop = centerY - gap;
    double innerBottom = centerY + gap;

    drawLine(g, left, innerTop, innerLeft, innerTop);
    drawLine(g, innerLeft, top, innerLeft, innerTop);
    drawLine(g, innerRight, innerTop, right, innerTop);
    drawLine(g, innerRight, top, innerRight, innerTop);
    drawLine(g, left, innerBottom, innerLeft, innerBottom);
    drawLine(g, innerLeft, innerBottom, innerLeft, bottom);
    drawLine(g, innerRight, innerBottom, right, innerBottom);
    drawLine(g, innerRight, innerBottom, innerRight, bottom);
  }

  private static void drawLine(
      Graphics2D g, double x1, double y1, double x2, double y2) {
    g.draw(new Line2D.Double(x1, y1, x2, y2));
  }
}
