package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GlassEffectRendererCacheTest {
  @AfterEach
  void clearCache() {
    GlassEffectRenderer.clearBlurCacheForTests();
  }

  @Test
  void identicalBackgroundRegionAndRadiusReuseBlurredImage() {
    BufferedImage background = background();

    BufferedImage first = GlassEffectRenderer.blurredRegion(background, 8, 9, 80, 64, 10, 800);
    BufferedImage second = GlassEffectRenderer.blurredRegion(background, 8, 9, 80, 64, 10, 800);

    assertSame(first, second);
  }

  @Test
  void geometryRadiusAndBackgroundIdentityInvalidateCacheEntry() {
    BufferedImage background = background();
    BufferedImage first = GlassEffectRenderer.blurredRegion(background, 8, 9, 80, 64, 10, 800);

    assertNotSame(
        first, GlassEffectRenderer.blurredRegion(background, 9, 9, 80, 64, 10, 800));
    assertNotSame(
        first, GlassEffectRenderer.blurredRegion(background, 8, 9, 80, 64, 12, 800));
    assertNotSame(
        first, GlassEffectRenderer.blurredRegion(background(), 8, 9, 80, 64, 10, 800));
  }

  private static BufferedImage background() {
    BufferedImage image = new BufferedImage(128, 96, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setPaint(new java.awt.GradientPaint(0, 0, Color.ORANGE, 128, 96, Color.BLUE));
    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
    graphics.dispose();
    return image;
  }
}
