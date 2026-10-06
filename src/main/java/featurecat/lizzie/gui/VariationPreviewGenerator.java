package featurecat.lizzie.gui;

import static java.awt.RenderingHints.KEY_ANTIALIASING;
import static java.awt.RenderingHints.KEY_INTERPOLATION;
import static java.awt.RenderingHints.KEY_RENDERING;
import static java.awt.RenderingHints.VALUE_ANTIALIAS_ON;
import static java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR;
import static java.awt.RenderingHints.VALUE_RENDER_QUALITY;
import static java.awt.image.BufferedImage.TYPE_INT_ARGB;

import featurecat.lizzie.analysis.Branch;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.Utils;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Objects;
import java.util.Map;

/** Generates candidate layers exclusively from values captured at the host boundary. */
final class VariationPreviewGenerator {
  record Geometry(
      int gridWidth,
      int gridHeight,
      int pixelWidth,
      int pixelHeight,
      int marginWidth,
      int marginHeight,
      int squareWidth,
      int squareHeight,
      int stoneRadius,
      int hoverX,
      int hoverY) {
    boolean sameSurface(Geometry other) {
      return gridWidth == other.gridWidth && gridHeight == other.gridHeight
          && pixelWidth == other.pixelWidth && pixelHeight == other.pixelHeight
          && marginWidth == other.marginWidth && marginHeight == other.marginHeight
          && squareWidth == other.squareWidth && squareHeight == other.squareHeight
          && stoneRadius == other.stoneRadius;
    }
  }

  static final class SourceStones {
    private final Stone[] stones;

    private SourceStones(Stone[] stones) {
      this.stones = stones;
    }

    Stone at(int index) {
      return stones[index];
    }

    int length() {
      return stones.length;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (!(obj instanceof SourceStones other)) return false;
      return Arrays.equals(this.stones, other.stones);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(stones);
    }
  }


  /**
   * Sprites are already scaled to the captured radius; sprites, shadow and board paint are shared
   * read-only assets. The source stones are the captured current board, which can differ from the
   * branch's source when showing previous-best-move candidates.
   */
  record Style(
      boolean floating,
      boolean editMode,
      boolean removeDeadChains,
      boolean previousBestMoves,
      boolean pureStone,
      boolean fancyBoard,
      boolean stoneHighlight,
      boolean hoverGlow,
      boolean trying,
      boolean pvVisitsAll,
      boolean pvVisitsLast,
      int pvVisitsLimit,
      int stoneLimit,
      int numberLimit,
      int boardType,
      Font font,
      BufferedImage blackStone,
      BufferedImage whiteStone,
      BufferedImage shadow,
      int shadowCenter,
      Paint boardPaint,
      Color boardColor,
      SourceStones sourceStones,
      String passText) {

    boolean sameRendering(Style other) {
      return sameSurface(other)
          && stoneLimit == other.stoneLimit && numberLimit == other.numberLimit;
    }

    boolean sameSurface(Style other) {
      return other != null
          && floating == other.floating && editMode == other.editMode
          && removeDeadChains == other.removeDeadChains
          && previousBestMoves == other.previousBestMoves
          && pureStone == other.pureStone && fancyBoard == other.fancyBoard
          && stoneHighlight == other.stoneHighlight && hoverGlow == other.hoverGlow
          && trying == other.trying && pvVisitsAll == other.pvVisitsAll
          && pvVisitsLast == other.pvVisitsLast && pvVisitsLimit == other.pvVisitsLimit
          && boardType == other.boardType && Objects.equals(font, other.font)
          && blackStone == other.blackStone && whiteStone == other.whiteStone
          && shadow == other.shadow && shadowCenter == other.shadowCenter
          && Objects.equals(boardPaint, other.boardPaint)
          && Objects.equals(boardColor, other.boardColor)
          && Objects.equals(sourceStones, other.sourceStones)
          && Objects.equals(passText, other.passText);
    }
  }

  /** All layers use board-local pixels and are private to this result; consumers only composite. */
  record Result(
      Branch branch,
      BufferedImage stones,
      BufferedImage shadows,
      BufferedImage annotations,
      boolean hideMouseOverInfo,
      boolean mouseOverStoneBlack) {}

  private VariationPreviewGenerator() {}

  static SourceStones captureSourceStones(Stone[] live, Style retained) {
    if (retained != null && retained.sourceStones() != null) {
      if (Arrays.equals(retained.sourceStones().stones, live)) {
        return retained.sourceStones();
      }
    }
    return new SourceStones(live != null ? live.clone() : new Stone[0]);
  }

  static Result generate(Branch.Input input, Geometry geometry, Style style) {
    Branch branch = new Branch(input);
    BufferedImage stones =
        new BufferedImage(geometry.pixelWidth, geometry.pixelHeight, TYPE_INT_ARGB);
    BufferedImage shadows =
        new BufferedImage(geometry.pixelWidth, geometry.pixelHeight, TYPE_INT_ARGB);
    BufferedImage annotations =
        new BufferedImage(geometry.pixelWidth, geometry.pixelHeight, TYPE_INT_ARGB);
    Graphics2D g = stones.createGraphics();
    Graphics2D gShadow = shadows.createGraphics();
    Graphics2D gAnnotations = annotations.createGraphics();
    boolean mouseOverStoneBlack;
    boolean hideMouseOverInfo;
    try {
      g.setRenderingHint(KEY_RENDERING, VALUE_RENDER_QUALITY);
      g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
      gShadow.setRenderingHint(KEY_RENDERING, VALUE_RENDER_QUALITY);
      gAnnotations.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
      mouseOverStoneBlack = drawStones(g, gShadow, branch, geometry, style);
      Font font =
          style.font
              .deriveFont(Font.PLAIN, 100)
              .deriveFont(Map.of(TextAttribute.KERNING, TextAttribute.KERNING_ON));
      hideMouseOverInfo = drawAnnotations(gAnnotations, branch, geometry, style, font);
    } finally {
      g.dispose();
      gShadow.dispose();
      gAnnotations.dispose();
    }
    return new Result(branch, stones, shadows, annotations, hideMouseOverInfo, mouseOverStoneBlack);
  }

  private static boolean drawStones(
      Graphics2D g, Graphics2D gShadow, Branch branch, Geometry geometry, Style style) {
    boolean mouseOverStoneBlack = false;
    for (int x = 0; x < geometry.gridWidth; x++) {
      for (int y = 0; y < geometry.gridHeight; y++) {
        int index = x * geometry.gridHeight + y;
        Stone stone = branch.data.stones[index];
        boolean captured = stone == Stone.BLACK_CAPTURED || stone == Stone.WHITE_CAPTURED;
        if (style.floating) {
          if (!style.editMode
              && style.sourceStones.stones[index] != Stone.EMPTY
              && !branch.isNewStone[index]
              && !captured) continue;
        } else if (!style.removeDeadChains
            && !style.previousBestMoves
            && style.sourceStones.stones[index] != Stone.EMPTY) {
          continue;
        }
        int moveNumber = branch.data.moveNumberList[index];
        if (moveNumber > style.stoneLimit) continue;
        int centerX = geometry.marginWidth + geometry.squareWidth * x;
        int centerY = geometry.marginHeight + geometry.squareHeight * y;
        boolean mouseOver = x == geometry.hoverX && y == geometry.hoverY;
        if (captured && !style.floating) {
          drawCapturedStone(g, centerX, centerY, stone, moveNumber > 0, geometry, style);
        } else {
          if (captured && style.removeDeadChains) {
            drawFloatCapturedPoint(g, centerX, centerY, x, y, mouseOver, geometry, style);
          }
          drawStone(g, gShadow, centerX, centerY, stone, geometry, style);
        }
        if (mouseOver) {
          mouseOverStoneBlack = style.floating ? stone.isBlack() : stone.isBlackColor();
          if (!style.floating && style.hoverGlow) {
            GlassEffectRenderer.drawHoverGlow(g, centerX, centerY, geometry.stoneRadius);
          }
        }
      }
    }
    return mouseOverStoneBlack;
  }

  private static void drawStone(
      Graphics2D g,
      Graphics2D gShadow,
      int centerX,
      int centerY,
      Stone stone,
      Geometry geometry,
      Style style) {
    g.setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_BILINEAR);
    g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
    if (!stone.needDrawBlack() && !stone.needDrawWhite()) return;
    boolean black = stone.isBlack();
    int radius = geometry.stoneRadius;
    if (style.shadow != null) {
      gShadow.drawImage(
          style.shadow, centerX - style.shadowCenter, centerY - style.shadowCenter, null);
    }
    if (style.pureStone) {
      g.setColor(black ? Color.BLACK : Color.WHITE);
      fillCircle(g, centerX, centerY, radius);
      if (!black) {
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(Math.max(radius / 16f, 1f)));
        g.drawOval(centerX - radius, centerY - radius, 2 * radius, 2 * radius);
      }
    } else {
      int size = radius * 2 + 1;
      g.drawImage(
          black ? style.blackStone : style.whiteStone,
          centerX - radius,
          centerY - radius,
          size,
          size,
          null);
      if (!style.floating && style.stoneHighlight) {
        GlassEffectRenderer.drawStoneHighlight(g, centerX, centerY, radius, black);
      }
    }
  }

  private static void drawCapturedStone(
      Graphics2D g,
      int centerX,
      int centerY,
      Stone stone,
      boolean darker,
      Geometry geometry,
      Style style) {
    if (darker) {
      if (style.fancyBoard) g.setPaint(style.boardPaint);
      else g.setColor(style.boardColor);
      fillCircle(g, centerX, centerY, geometry.stoneRadius);
    }
    g.setColor(
        stone == Stone.BLACK_CAPTURED
            ? new Color(0, 0, 0, darker ? 80 : 60)
            : new Color(255, 255, 255, darker ? 110 : 85));
    fillCircle(g, centerX, centerY, geometry.stoneRadius);
  }

  private static void drawFloatCapturedPoint(
      Graphics2D g,
      int centerX,
      int centerY,
      int x,
      int y,
      boolean mouseOver,
      Geometry geometry,
      Style style) {
    int radius = geometry.stoneRadius + 1;
    g.setPaint(style.boardPaint);
    fillCircle(g, centerX, centerY, radius);
    if (!mouseOver) {
      int offset = style.boardType == 0 ? 1 : 0;
      g.setColor(Color.BLACK);
      g.setStroke(new BasicStroke(1));
      g.drawLine(
          centerX - (x == 0 ? 0 : radius),
          centerY + offset,
          centerX + (x == geometry.gridWidth - 1 ? 0 : radius),
          centerY + offset);
      g.drawLine(
          centerX + offset,
          centerY - (y == 0 ? 0 : radius),
          centerX + offset,
          centerY + (y == geometry.gridHeight - 1 ? 0 : radius));
    }
  }

  private static boolean drawAnnotations(
      Graphics2D g, Branch branch, Geometry geometry, Style style, Font font) {
    if (!style.floating) drawPass(g, branch, geometry, style, font);
    if (branch.data.isSnapshotNode()) return false;
    int[] lastMove = branch.data.lastMove.orElse(null);
    boolean hideMouseOverInfo = false;
    for (int x = 0; x < geometry.gridWidth; x++) {
      for (int y = 0; y < geometry.gridHeight; y++) {
        int index = x * geometry.gridHeight + y;
        int moveNumber = branch.data.moveNumberList[index];
        if (!(moveNumber > 0 || style.trying && moveNumber < 0)) continue;
        if (x == geometry.hoverX && y == geometry.hoverY) {
          if (moveNumber > 1) hideMouseOverInfo = true;
          else continue;
        }
        int centerX = geometry.marginWidth + geometry.squareWidth * x;
        int centerY = geometry.marginHeight + geometry.squareHeight * y;
        boolean last = lastMove != null && lastMove[0] == x && lastMove[1] == y;
        int visits = branch.pvVisitsList[index];
        boolean showVisits =
            (last ? style.pvVisitsLast : style.pvVisitsAll) && visits > style.pvVisitsLimit;
        if (last) {
          if (showVisits) {
            drawPvVisits(g, centerX, centerY, visits, true, geometry, font);
            g.setColor(Color.RED);
            drawPolygonSmallPv(g, centerX, centerY, geometry.squareWidth);
            drawNumber(g, centerX, centerY, moveNumber, true, false, geometry, font);
            continue;
          }
          g.setColor(Color.RED);
          drawPolygonSmall(g, centerX, centerY, geometry.stoneRadius);
        } else {
          if (moveNumber > style.numberLimit) continue;
          if (showVisits) drawPvVisits(g, centerX, centerY, visits, false, geometry, font);
          Stone stone = branch.data.stones[index];
          boolean whiteText =
              style.floating
                  ? stone.isBlack() || stone == Stone.WHITE_CAPTURED
                  : stone.isBlackColor();
          g.setColor(whiteText ? Color.WHITE : Color.BLACK);
        }
        // The floating host suppresses positive try-mode numbers. The main host only does so
        // when the number shares its stone with a PV-visits label.
        if (style.trying && (style.floating || showVisits)) {
          if (moveNumber < 0) {
            drawNumber(g, centerX, centerY, -moveNumber, showVisits, false, geometry, font);
          }
        } else {
          drawNumber(g, centerX, centerY, moveNumber, showVisits, moveNumber >= 100, geometry, font);
        }
      }
    }
    return hideMouseOverInfo;
  }

  private static void drawPass(
      Graphics2D g, Branch branch, Geometry geometry, Style style, Font font) {
    if (!branch.data.isPassNode() || branch.data.moveNumber == 0 || branch.data.dummy) return;
    int radius = geometry.stoneRadius;
    int centerX = geometry.pixelWidth / 2;
    int centerY = geometry.pixelHeight / 2;
    boolean blackToPlay = branch.data.blackToPlay;
    g.setColor(blackToPlay ? new Color(255, 255, 255, 80) : new Color(0, 0, 0, 80));
    g.fillOval(centerX - radius * 5 / 2, centerY - radius * 5 / 2, radius * 5, radius * 5);
    g.setColor(blackToPlay ? new Color(0, 0, 0, 200) : new Color(255, 255, 255, 200));
    drawString(g, centerX, centerY, font, style.passText, radius * 3, radius * 9 / 2, 0);
  }

  private static void drawPvVisits(
      Graphics2D g,
      int centerX,
      int centerY,
      int visits,
      boolean last,
      Geometry geometry,
      Font font) {
    int square = geometry.squareWidth;
    g.setColor(Color.ORANGE);
    double left = last ? 0.43 : visits >= 1000 ? 0.3 : 0.25;
    double width = last ? visits >= 1000 ? 0.93 : 0.9 : visits >= 1000 ? 0.8 : 0.72;
    g.fillRect(
        (int) (centerX - square * left),
        (int) (centerY - square * 0.5),
        (int) (square * width),
        (int) Math.round(square * 0.33));
    g.setColor(Color.BLACK);
    drawString(
        g,
        (int) (centerX + square * 0.1),
        (int) (centerY - square * 0.2),
        font,
        Utils.getPlayoutsString(visits),
        (float) (square * 0.33),
        square * 0.8,
        1);
  }

  private static void drawNumber(
      Graphics2D g,
      int centerX,
      int centerY,
      int number,
      boolean visits,
      boolean wide,
      Geometry geometry,
      Font font) {
    drawString(
        g,
        centerX,
        visits ? (int) (centerY + geometry.squareWidth * 0.12) : centerY,
        font,
        String.valueOf(number),
        (float) (geometry.stoneRadius * (visits ? 1.3 : 1.4)),
        (int) (geometry.stoneRadius * (wide ? 1.85 : 1.4)),
        0);
  }

  private static void drawString(
      Graphics2D g,
      int x,
      int y,
      Font font,
      String string,
      float maximumHeight,
      double maximumWidth,
      int aboveOrBelow) {
    FontMetrics metrics = g.getFontMetrics(font);
    font = font.deriveFont((float) (font.getSize2D() * maximumWidth / metrics.stringWidth(string)));
    font = font.deriveFont(Math.min(maximumHeight, font.getSize()));
    g.setFont(font);
    metrics = g.getFontMetrics(font);
    int height = metrics.getAscent() - metrics.getDescent();
    int offset = aboveOrBelow == -1 ? height / 2 : aboveOrBelow == 1 ? -height / 2 : 0;
    g.drawString(string, x - metrics.stringWidth(string) / 2, y + height / 2 + offset);
  }

  private static void fillCircle(Graphics2D g, int centerX, int centerY, int radius) {
    g.fillOval(centerX - radius, centerY - radius, 2 * radius + 1, 2 * radius + 1);
  }

  private static void drawPolygonSmall(Graphics2D g, int centerX, int centerY, int radius) {
    int[] xPoints = {
      centerX - radius * 16 / 15, centerX - radius * 16 / 15, centerX - radius * 4 / 11
    };
    int[] yPoints = {
      centerY - radius * 16 / 15, centerY - radius * 4 / 11, centerY - radius * 16 / 15
    };
    g.fillPolygon(xPoints, yPoints, 3);
  }

  private static void drawPolygonSmallPv(Graphics2D g, int centerX, int centerY, int radius) {
    int[] xPoints = {
      centerX - radius * 9 / 20,
      centerX - radius * 9 / 20,
      centerX - radius * 17 / 40,
      centerX - radius * 11 / 40
    };
    int[] yPoints = {
      (int) (centerY - radius * 0.5),
      (int) (centerY - radius * 0.18),
      (int) (centerY - radius * 0.18),
      (int) (centerY - radius * 0.5)
    };
    g.fillPolygon(xPoints, yPoints, 4);
  }
}
