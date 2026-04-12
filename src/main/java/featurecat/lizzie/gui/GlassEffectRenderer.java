package featurecat.lizzie.gui;

import static java.awt.image.BufferedImage.TYPE_INT_ARGB;

import com.jhlabs.image.GaussianFilter;
import featurecat.lizzie.theme.MorandiPalette;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

public class GlassEffectRenderer {

  public enum GlassLevel {
    NONE,
    FROSTED,
    LIQUID
  }

  private static GaussianFilter glassBlurFilter = new GaussianFilter(10);
  private static GaussianFilter liquidBlurFilter = new GaussianFilter(15);

  private static long lastHighlightTime = 0;
  private static float highlightPhase = 0f;

  public static void drawGlassPanel(
      Graphics2D g,
      BufferedImage cachedBackground,
      int vx,
      int vy,
      int vw,
      int vh,
      GlassLevel level,
      int cornerRadius) {
    if (level == GlassLevel.NONE || vw <= 0 || vh <= 0) return;

    if (vx < cachedBackground.getMinX()
        || vx + vw > cachedBackground.getMinX() + cachedBackground.getWidth()
        || vy < cachedBackground.getMinY()
        || vy + vh > cachedBackground.getMinY() + cachedBackground.getHeight()) {
      return;
    }

    int maxDim = 800;
    float scaleX = 1f, scaleY = 1f;
    int srcW = vw, srcH = vh;
    if (vw > maxDim || vh > maxDim) {
      scaleX = (float) vw / maxDim;
      scaleY = (float) vh / maxDim;
      float scale = Math.max(scaleX, scaleY);
      srcW = Math.max((int) (vw / scale), 1);
      srcH = Math.max((int) (vh / scale), 1);
    }

    BufferedImage blurSource;
    if (srcW != vw || srcH != vh) {
      BufferedImage subImg = cachedBackground.getSubimage(vx, vy, vw, vh);
      blurSource = new BufferedImage(srcW, srcH, TYPE_INT_ARGB);
      Graphics2D sg = blurSource.createGraphics();
      sg.setRenderingHint(
          java.awt.RenderingHints.KEY_INTERPOLATION,
          java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      sg.drawImage(subImg, 0, 0, srcW, srcH, null);
      sg.dispose();
    } else {
      blurSource = cachedBackground.getSubimage(vx, vy, vw, vh);
    }

    BufferedImage blurred = new BufferedImage(srcW, srcH, TYPE_INT_ARGB);

    if (level == GlassLevel.LIQUID) {
      liquidBlurFilter.filter(blurSource, blurred);
    } else {
      glassBlurFilter.filter(blurSource, blurred);
    }

    g.drawImage(blurred, vx, vy, vw, vh, null);

    Color overlayColor = MorandiPalette.GLASS_OVERLAY;
    g.setColor(overlayColor);
    g.fillRoundRect(vx, vy, vw, vh, cornerRadius, cornerRadius);

    g.setColor(MorandiPalette.GLASS_BORDER);
    g.setStroke(new BasicStroke(1f));
    g.drawRoundRect(vx, vy, vw, vh, cornerRadius, cornerRadius);

    GradientPaint topLight =
        new GradientPaint(
            vx,
            vy,
            MorandiPalette.GLASS_HIGHLIGHT,
            vx,
            vy + vh * 0.3f,
            new Color(255, 255, 255, 0));
    Paint oldPaint = g.getPaint();
    g.setPaint(topLight);
    g.fillRoundRect(vx + 1, vy + 1, vw - 2, vh / 3, cornerRadius, cornerRadius);
    g.setPaint(oldPaint);

    if (level == GlassLevel.LIQUID) {
      drawLiquidEffects(g, vx, vy, vw, vh, cornerRadius);
    }
  }

  public static void drawGlassPanel(
      Graphics2D g,
      BufferedImage cachedBackground,
      int vx,
      int vy,
      int vw,
      int vh,
      GlassLevel level) {
    drawGlassPanel(g, cachedBackground, vx, vy, vw, vh, level, 16);
  }

  private static void drawLiquidEffects(
      Graphics2D g, int vx, int vy, int vw, int vh, int cornerRadius) {
    long now = System.currentTimeMillis();
    highlightPhase = (now % 6000) / 6000f;

    float highlightX = (float) (0.5 + 0.3 * Math.sin(highlightPhase * Math.PI * 2));
    float highlightY = (float) (0.5 + 0.3 * Math.cos(highlightPhase * Math.PI * 2 * 0.7));
    Point2D center = new Point2D.Float(vx + vw * highlightX, vy + vh * highlightY);
    float radius = Math.max(vw, vh) * 0.5f;

    RadialGradientPaint highlight =
        new RadialGradientPaint(
            center,
            radius,
            new float[] {0f, 0.5f, 1f},
            new Color[] {
              new Color(255, 255, 255, 50),
              new Color(255, 255, 255, 15),
              new Color(255, 255, 255, 0)
            });
    Paint oldPaint = g.getPaint();
    g.setPaint(highlight);
    g.fillRoundRect(vx, vy, vw, vh, cornerRadius, cornerRadius);
    g.setPaint(oldPaint);

    drawInnerShadow(g, vx, vy, vw, vh, cornerRadius);
  }

  private static void drawInnerShadow(Graphics2D g, int x, int y, int w, int h, int cornerRadius) {
    GradientPaint topShadow =
        new GradientPaint(x, y, new Color(0, 0, 0, 40), x, y + 15, new Color(0, 0, 0, 0));
    Paint oldPaint = g.getPaint();
    g.setPaint(topShadow);
    g.fillRoundRect(x + 2, y + 2, w - 4, 15, cornerRadius, cornerRadius);

    GradientPaint bottomShadow =
        new GradientPaint(x, y + h - 10, new Color(0, 0, 0, 0), x, y + h, new Color(0, 0, 0, 25));
    g.setPaint(bottomShadow);
    g.fillRoundRect(x + 2, y + h - 10, w - 4, 10, cornerRadius, cornerRadius);
    g.setPaint(oldPaint);
  }

  public static void drawStoneHighlight(
      Graphics2D g, int centerX, int centerY, int stoneRadius, boolean isBlack) {
    Point2D highlightCenter =
        new Point2D.Float(centerX - stoneRadius * 0.3f, centerY - stoneRadius * 0.3f);
    RadialGradientPaint highlight =
        new RadialGradientPaint(
            highlightCenter,
            stoneRadius * 0.6f,
            new float[] {0f, 0.6f, 1f},
            isBlack
                ? new Color[] {
                  new Color(255, 255, 255, 70),
                  new Color(255, 255, 255, 20),
                  new Color(255, 255, 255, 0)
                }
                : new Color[] {
                  new Color(255, 255, 255, 180),
                  new Color(255, 255, 255, 60),
                  new Color(255, 255, 255, 0)
                });
    Paint oldPaint = g.getPaint();
    g.setPaint(highlight);
    g.fillOval(centerX - stoneRadius, centerY - stoneRadius, stoneRadius * 2, stoneRadius * 2);

    RadialGradientPaint rim =
        new RadialGradientPaint(
            new Point2D.Float(centerX, centerY),
            stoneRadius,
            new float[] {0.85f, 0.95f, 1f},
            new Color[] {
              new Color(255, 255, 255, 0),
              new Color(255, 255, 255, isBlack ? 25 : 50),
              new Color(255, 255, 255, 0)
            });
    g.setPaint(rim);
    g.fillOval(centerX - stoneRadius, centerY - stoneRadius, stoneRadius * 2, stoneRadius * 2);
    g.setPaint(oldPaint);
  }

  public static void drawBoardLightGradient(Graphics2D g, int boardWidth, int boardHeight) {
    GradientPaint lightGradient =
        new GradientPaint(
            0, 0, new Color(255, 255, 230, 25), boardWidth, boardHeight, new Color(0, 0, 20, 25));
    Paint oldPaint = g.getPaint();
    g.setPaint(lightGradient);
    g.fillRect(0, 0, boardWidth, boardHeight);
    g.setPaint(oldPaint);
  }

  public static void drawBoardInnerShadow(Graphics2D g, int boardWidth, int boardHeight) {
    int shadowDepth = Math.max(boardWidth / 80, 4);
    GradientPaint topShadow =
        new GradientPaint(0, 0, new Color(0, 0, 0, 30), 0, shadowDepth, new Color(0, 0, 0, 0));
    Paint oldPaint = g.getPaint();
    g.setPaint(topShadow);
    g.fillRect(0, 0, boardWidth, shadowDepth);

    GradientPaint leftShadow =
        new GradientPaint(0, 0, new Color(0, 0, 0, 20), shadowDepth, 0, new Color(0, 0, 0, 0));
    g.setPaint(leftShadow);
    g.fillRect(0, 0, shadowDepth, boardHeight);

    GradientPaint bottomShadow =
        new GradientPaint(
            0,
            boardHeight - shadowDepth,
            new Color(0, 0, 0, 0),
            0,
            boardHeight,
            new Color(0, 0, 0, 20));
    g.setPaint(bottomShadow);
    g.fillRect(0, boardHeight - shadowDepth, boardWidth, shadowDepth);

    GradientPaint rightShadow =
        new GradientPaint(
            boardWidth - shadowDepth,
            0,
            new Color(0, 0, 0, 0),
            boardWidth,
            0,
            new Color(0, 0, 0, 15));
    g.setPaint(rightShadow);
    g.fillRect(boardWidth - shadowDepth, 0, shadowDepth, boardHeight);
    g.setPaint(oldPaint);
  }

  public static void drawHoverGlow(Graphics2D g, int mouseX, int mouseY, int stoneRadius) {
    if (mouseX < 0 || mouseY < 0) return;
    RadialGradientPaint glow =
        new RadialGradientPaint(
            new Point2D.Float(mouseX, mouseY),
            stoneRadius * 2,
            new float[] {0f, 0.5f, 1f},
            new Color[] {
              new Color(255, 255, 255, 30),
              new Color(255, 255, 255, 10),
              new Color(255, 255, 255, 0)
            });
    Paint oldPaint = g.getPaint();
    g.setPaint(glow);
    g.fillOval(
        mouseX - stoneRadius * 2, mouseY - stoneRadius * 2, stoneRadius * 4, stoneRadius * 4);
    g.setPaint(oldPaint);
  }

  public static void setGlassBlurRadius(int radius) {
    glassBlurFilter = new GaussianFilter(Math.max(radius, 5));
  }

  public static void setLiquidBlurRadius(int radius) {
    liquidBlurFilter = new GaussianFilter(Math.max(radius, 10));
  }
}
