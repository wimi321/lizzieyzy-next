package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class MoveStatusLineTest {
  private static final Font UI = new Font(Font.DIALOG, Font.PLAIN, 12);
  private static final String LEAD = "白领先 7.3 目";
  private static final String PREVIOUS = "上一手(#3) -0.3% -1.9目";

  @Test
  void thePreviousMoveIsLargerAndBoldWhenThereIsRoom() {
    Graphics2D g = graphics();

    MoveStatusLine.Layout layout =
        MoveStatusLine.layout(g, UI, 10, 50, 60f, 800, 16, LEAD, PREVIOUS);

    assertEquals(16f, layout.leadFont().getSize2D());
    assertEquals(24f, layout.previousFont().getSize2D());
    assertTrue(layout.previousFont().isBold());
    assertEquals(
        10 + g.getFontMetrics(layout.leadFont()).stringWidth(LEAD + "  "), layout.previousX());
    for (Font font : new Font[] {layout.leadFont(), layout.previousFont()}) {
      assertEquals(TextAttribute.KERNING_ON, font.getAttributes().get(TextAttribute.KERNING));
    }
  }

  @Test
  void aNarrowLineShrinksBothAndKeepsThePreviousMoveTheLarger() {
    Graphics2D g = graphics();

    MoveStatusLine.Layout layout =
        MoveStatusLine.layout(g, UI, 0, 50, 60f, 180, 16, LEAD, PREVIOUS);

    int right = layout.previousX() + g.getFontMetrics(layout.previousFont()).stringWidth(PREVIOUS);
    assertTrue(right <= 180, "line ends at " + right);
    assertTrue(layout.previousFont().getSize2D() >= layout.leadFont().getSize2D());
  }

  @Test
  void aLineWithoutAPreviousMoveIsTheLeadAlone() {
    Graphics2D g = graphics();

    MoveStatusLine.Layout layout = MoveStatusLine.layout(g, UI, 4, 50, 60f, 800, 16, LEAD, "");

    assertEquals(16f, layout.leadFont().getSize2D());
    assertEquals(4 + g.getFontMetrics(layout.leadFont()).stringWidth(LEAD), layout.previousX());
  }

  @Test
  void gainsAreGreenLossesRedAndNoChangeKeepsTheLineColour() {
    assertEquals(MoveStatusLine.LOSS, MoveStatusLine.color(-1.9, Color.WHITE));
    assertEquals(MoveStatusLine.GAIN, MoveStatusLine.color(0.3, Color.WHITE));
    assertEquals(Color.WHITE, MoveStatusLine.color(0.04, Color.WHITE));
    assertEquals(Color.WHITE, MoveStatusLine.color(-0.04, Color.WHITE));
  }

  @Test
  void theLossIsDrawnInRedAfterTheWhiteLead() {
    BufferedImage image = new BufferedImage(600, 100, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(Color.WHITE);

    MoveStatusLine.draw(g, UI, 0, 50, 60f, 600, 16, "Black leads 0.4", "Prev. move -3.1%", -3.1);

    MoveStatusLine.Layout layout =
        MoveStatusLine.layout(g, UI, 0, 50, 60f, 600, 16, "Black leads 0.4", "Prev. move -3.1%");
    boolean red = false;
    boolean whiteBeforeIt = false;
    for (int x = 0; x < image.getWidth(); x++) {
      for (int y = 0; y < image.getHeight(); y++) {
        Color pixel = new Color(image.getRGB(x, y));
        if (x < layout.previousX() && pixel.getBlue() > 200 && pixel.getRed() > 200) {
          whiteBeforeIt = true;
        }
        if (x >= layout.previousX() && pixel.getRed() > 200 && pixel.getGreen() < 160) red = true;
      }
    }
    assertTrue(whiteBeforeIt, "the lead keeps the line colour");
    assertTrue(red, "the previous move's loss is red");
    assertEquals(Color.WHITE, g.getColor(), "the line colour is restored");
  }

  private static Graphics2D graphics() {
    return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
  }
}
