package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class BigWinratePanelTest {
  private static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);

  @Test
  void eachSideShowsItsWholePercentOrDashesWithoutAnalysis() {
    WinrateReading reading = new WinrateReading(63.2, true);

    assertEquals("63", BigWinratePanel.figure(reading, true));
    assertEquals("37", BigWinratePanel.figure(reading, false));
    assertEquals("--", BigWinratePanel.figure(WinrateReading.UNKNOWN, true));
  }

  @Test
  void thePlainStyleIsABlackCardWithWhiteFiguresBesideAWhiteCardWithBlackOnes() {
    BufferedImage image = render(BigWinratePanel.Style.PLAIN, new WinrateReading(63, true));

    int[] left = census(image, new Rectangle(30, 40, 230, 170));
    int[] right = census(image, new Rectangle(320, 40, 230, 170));
    assertTrue(left[0] > left[1], "the black card is mostly dark");
    assertTrue(left[1] > 200, "with light figures on it");
    assertTrue(right[1] > right[0], "the white card is mostly light");
    assertTrue(right[0] > 200, "with dark figures on it");
  }

  @Test
  void theLedStyleLightsBlacksFiguresWarmAndWhitesCool() {
    BufferedImage image = render(BigWinratePanel.Style.LED, new WinrateReading(63, true));

    int[] left = census(image, new Rectangle(30, 40, 230, 170));
    int[] right = census(image, new Rectangle(320, 40, 230, 170));
    assertTrue(left[2] > 300, "warm segments on Black's side");
    assertTrue(right[3] > 300, "cool segments on White's side");
    assertEquals(0, left[3], "no cool light on Black's side");
  }

  @Test
  void theSettingsComeBackAndUnusableOnesAreIgnored() {
    JSONObject settings = new JSONObject();
    assertEquals(BigWinratePanel.Style.PLAIN, BigWinrateSettings.style(settings));
    assertNull(BigWinrateSettings.bounds(settings));

    BigWinrateSettings.setStyle(settings, BigWinratePanel.Style.LED);
    BigWinrateSettings.setOnTop(settings, true);
    BigWinrateSettings.setBounds(settings, new Rectangle(10, 20, 500, 250));

    assertEquals(BigWinratePanel.Style.LED, BigWinrateSettings.style(settings));
    assertTrue(BigWinrateSettings.onTop(settings));
    assertEquals(new Rectangle(10, 20, 500, 250), BigWinrateSettings.bounds(settings));

    settings.put(BigWinrateSettings.STYLE_KEY, "FUTURE_STYLE");
    BigWinrateSettings.setBounds(settings, new Rectangle(0, 0, 40, 20));
    assertEquals(BigWinratePanel.Style.PLAIN, BigWinrateSettings.style(settings));
    assertNull(BigWinrateSettings.bounds(settings), "too small to read");
  }

  private static BufferedImage render(BigWinratePanel.Style style, WinrateReading reading) {
    BigWinratePanel panel = new BigWinratePanel("Black win rate", "White win rate", FONT, FONT);
    panel.setStyle(style);
    panel.setReading(reading);
    panel.setSize(580, 250);
    BufferedImage image = new BufferedImage(580, 250, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    panel.paint(g);
    g.dispose();
    return image;
  }

  /** Dark, light, warm and cool pixels in the area, sampled every other pixel. */
  private static int[] census(BufferedImage image, Rectangle area) {
    int[] counts = new int[4];
    for (int y = area.y; y < area.y + area.height; y += 2) {
      for (int x = area.x; x < area.x + area.width; x += 2) {
        Color c = new Color(image.getRGB(x, y));
        int brightness = (c.getRed() + c.getGreen() + c.getBlue()) / 3;
        if (brightness < 60) counts[0]++;
        if (brightness > 200) counts[1]++;
        if (c.getRed() > 180 && c.getRed() > c.getBlue() + 60) counts[2]++;
        if (c.getBlue() > 180 && c.getBlue() > c.getRed() + 60) counts[3]++;
      }
    }
    return counts;
  }
}
