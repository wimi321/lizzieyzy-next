package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class BoardNamePlateTest {
  private static final Color WOOD = new Color(220, 182, 122);
  private static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, 24);

  @Test
  void namesSitStraightOnTheWoodWithoutAPlate() {
    BufferedImage image = board();
    Graphics2D g = image.createGraphics();
    BoardNamePlate.paintBlackName(g, FONT, "Shusaku", 40, 130);
    BoardNamePlate.paintWhiteName(g, FONT, "Gennan", 220, 130);
    g.dispose();

    assertTrue(woodShare(image, 40, 140) > 0.4, "the wood shows around Black's letters");
    assertTrue(woodShare(image, 220, 310) > 0.4, "the wood shows around White's letters");
  }

  @Test
  void blacksNameIsBlackAndWhitesIsWhiteWithADarkEdge() {
    BufferedImage black = board();
    Graphics2D g = black.createGraphics();
    BoardNamePlate.paintBlackName(g, FONT, "Shusaku", 40, 130);
    g.dispose();
    BufferedImage white = board();
    g = white.createGraphics();
    BoardNamePlate.paintWhiteName(g, FONT, "Shusaku", 40, 130);
    g.dispose();

    assertTrue(extreme(black, true) < 60, "black type");
    assertTrue(extreme(black, false) <= brightness(WOOD), "nothing lighter than the wood");
    assertTrue(extreme(white, false) > 700, "white type");
    assertTrue(extreme(white, true) < 150, "with a dark edge");
  }

  @Test
  void theSideToMoveHasItsStoneOnTop() {
    assertEquals(Color.BLACK.getRGB(), overlapPixel(true), "black to move: black stone on top");
    assertEquals(Color.WHITE.getRGB(), overlapPixel(false), "white to move: white stone on top");
  }

  @Test
  void aClickAnywhereFromBlacksNameToWhitesHitsTheNames() {
    Rectangle box = BoardNamePlate.hitBox(40, 300, 130, 20, 6);

    assertEquals(new Rectangle(40, 110, 260, 26), box);
    assertTrue(box.contains(45, 125), "Black's name");
    assertTrue(box.contains(170, 125), "the stones between the names");
    assertTrue(box.contains(295, 133), "White's name");
    assertTrue(!box.contains(170, 100), "above the type");
  }

  @Test
  void aLongNameIsCutShortWithAnEllipsis() {
    Graphics2D g = board().createGraphics();
    FontMetrics metrics = g.getFontMetrics(FONT);
    g.dispose();
    int max = metrics.stringWidth("Honinbo Shu");

    String fitted = BoardNamePlate.fit("Honinbo Shusaku the Great", metrics, max);

    assertTrue(fitted.endsWith("…"), fitted);
    assertTrue(metrics.stringWidth(fitted) <= max, fitted);
    assertEquals("Shusaku", BoardNamePlate.fit("Shusaku", metrics, max));
  }

  /** The share of pixels in the name's box that are still exactly the wood. */
  private static double woodShare(BufferedImage image, int fromX, int toX) {
    int wood = 0;
    int all = 0;
    for (int x = fromX; x < toX; x++) {
      for (int y = 110; y < 132; y++) {
        all++;
        if (image.getRGB(x, y) == WOOD.getRGB()) wood++;
      }
    }
    return wood / (double) all;
  }

  private static int extreme(BufferedImage image, boolean darkest) {
    int extreme = darkest ? 765 : 0;
    for (int x = 38; x < 142; x++) {
      for (int y = 106; y < 134; y++) {
        int value = brightness(new Color(image.getRGB(x, y)));
        extreme = darkest ? Math.min(extreme, value) : Math.max(extreme, value);
      }
    }
    return extreme;
  }

  private static int overlapPixel(boolean blackOnTop) {
    BufferedImage image = new BufferedImage(60, 30, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    BoardNamePlate.paintStones(g, 10, 22, 5, 20, blackOnTop);
    g.dispose();
    return image.getRGB(26, 15);
  }

  private static BufferedImage board() {
    BufferedImage image = new BufferedImage(400, 160, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    g.setColor(WOOD);
    g.fillRect(0, 0, 400, 160);
    g.dispose();
    return image;
  }

  private static int brightness(Color color) {
    return color.getRed() + color.getGreen() + color.getBlue();
  }
}
