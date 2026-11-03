package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class InfoPanelTextTest {
  private static final ResourceBundle CHINESE =
      ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
  private static final ResourceBundle ENGLISH =
      ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);

  @Test
  void figuresCarryTheirNamesWithoutColons() {
    assertEquals("提子 4", InfoPanelText.captures(CHINESE, 4));
    assertEquals("吻合度 54.4", InfoPanelText.accuracy(CHINESE, 54.36));
    assertEquals("贴目 7.5", InfoPanelText.komi(CHINESE, 7.5));
    assertEquals("Captures 4", InfoPanelText.captures(ENGLISH, 4));
    assertEquals("Accuracy 54.4", InfoPanelText.accuracy(ENGLISH, 54.36));
    assertEquals("Komi 7.5", InfoPanelText.komi(ENGLISH, 7.5));
  }

  @Test
  void komiDropsTrailingZerosButKeepsQuarters() {
    assertEquals("贴目 7", InfoPanelText.komi(CHINESE, 7.0));
    assertEquals("贴目 6.25", InfoPanelText.komi(CHINESE, 6.25));
    assertEquals("贴目 0", InfoPanelText.komi(CHINESE, 0));
    assertEquals("贴目 -6.5", InfoPanelText.komi(CHINESE, -6.5));
  }

  @Test
  void sideToMoveIsSpelledOut() {
    assertEquals("黑走", InfoPanelText.turn(CHINESE, true));
    assertEquals("白走", InfoPanelText.turn(CHINESE, false));
    assertEquals("White to play", InfoPanelText.turn(ENGLISH, false));
  }

  @Test
  void statusDotKeepsItsColourAndTheSideToMoveFollowsIt() {
    Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
    BufferedImage withTurn = render(font, true, "白走");
    BufferedImage dotOnly = render(font, false, null);

    int dotX = leftmostPainted(withTurn);
    assertEquals(Color.GREEN.getRGB(), withTurn.getRGB(dotX + 5, 20), "analysing is green");
    assertTrue(paintedRight(withTurn, dotX + 12), "the side to move follows the dot");
    assertEquals(Color.RED.getRGB(), dotOnly.getRGB(leftmostPainted(dotOnly) + 5, 20));
    assertFalse(paintedRight(dotOnly, leftmostPainted(dotOnly) + 12), "only the dot when narrow");
  }

  @Test
  void theRulesCaptionShrinksToStayBetweenTheStonesAndKeepsKerning() {
    Graphics2D g = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB).createGraphics();
    Font panel = new Font(Font.SANS_SERIF, Font.PLAIN, 17);

    Font roomy = InfoPanelText.captionFont(g, panel, "中国规则2027", 400, 60);
    assertEquals(17, roomy.getSize());
    assertEquals(TextAttribute.KERNING_ON, roomy.getAttributes().get(TextAttribute.KERNING));

    String caption = "2027, tie to 1st pass";
    int room = 300 / 2 - 60 / 2 - 4;
    assertTrue(g.getFontMetrics(panel).stringWidth(caption) > room);
    Font fitted = InfoPanelText.captionFont(g, panel, caption, 300, 60);
    assertTrue(fitted.getSize2D() < 17f);
    assertTrue(g.getFontMetrics(fitted).stringWidth(caption) <= room + 2);

    assertEquals(9f, InfoPanelText.captionFont(g, panel, caption, 60, 30).getSize2D());
    g.dispose();
  }

  private static BufferedImage render(Font font, boolean analysing, String turn) {
    BufferedImage image = new BufferedImage(120, 40, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    InfoPanelText.drawStatusAndTurn(g, font, 60, 20, 10, analysing, turn);
    g.dispose();
    return image;
  }

  private static int leftmostPainted(BufferedImage image) {
    for (int x = 0; x < image.getWidth(); x++) {
      if ((image.getRGB(x, 20) >>> 24) > 0) return x;
    }
    return -1;
  }

  private static boolean paintedRight(BufferedImage image, int fromX) {
    for (int x = fromX; x < image.getWidth(); x++) {
      for (int y = 0; y < image.getHeight(); y++) {
        if ((image.getRGB(x, y) >>> 24) > 0) return true;
      }
    }
    return false;
  }
}
