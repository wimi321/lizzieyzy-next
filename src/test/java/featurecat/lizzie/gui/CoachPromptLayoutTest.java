package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.training.MoveVerdict;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CoachPromptLayoutTest {
  private static final Color DETAIL = new Color(0xd9, 0xbd, 0xb8);

  @ParameterizedTest
  @ValueSource(strings = {"", "en-US", "ja-JP", "ko", "th-TH", "zh-CN", "zh-HK", "zh-TW"})
  void realLocalizedPromptTextAndChoicesFitTheCard(String language) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          ResourceBundle strings =
              ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag(language));
          // A 360/260 px panel loses its 37 px border/padding and optionally a 17 px scrollbar.
          for (int width : new int[] {306, 323, 206, 223}) {
            List<CoachPanelState.Action> actions = new ArrayList<>();
            CoachPanelState.Prompt prompt = prompt(strings);
            CoachMoveCard card = new CoachMoveCard(() -> state(prompt), actions::add);
            card.setSize(width, 1);
            int height = card.heightFor();
            card.setSize(width, height);
            // Paint without the component clip so any text escaping the card remains observable.
            BufferedImage image =
                new BufferedImage(width + 700, height + 100, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
              card.paintComponent(graphics);
            } finally {
              graphics.dispose();
            }
            for (Color color : List.of(DETAIL, CoachPaint.verdictColor(MoveVerdict.BLUNDER))) {
              Rectangle ink = inkBounds(image, color);
              assertTrue(ink.width > 0, language + " missing text");
              assertTrue(
                  ink.x >= 16 && ink.x + ink.width <= width - 16,
                  language + " text escapes " + width + " px card: " + ink);
              assertTrue(
                  ink.y + ink.height <= card.buttons().get(0).getY(), "text overlaps choices");
            }
            if (language.equals("en-US")) {
              assertEquals(
                  fullTextInk(prompt.detail(), DETAIL, Font.PLAIN, 14),
                  countInk(image, DETAIL),
                  "The complete points-lost detail must remain visible, not ellipsized");
            }
            for (CoachButton button : card.buttons()) {
              Font font =
                  CoachPaint.font(
                      button.getText(),
                      button.kind() == CoachButton.Kind.PRIMARY
                              || button.kind() == CoachButton.Kind.ACCENT
                          ? Font.BOLD
                          : Font.PLAIN,
                      14);
              assertTrue(
                  button.getWidth()
                      >= button.getFontMetrics(font).stringWidth(button.getText()) + 24,
                  language + " clipped button: " + button.getText() + " at " + width);
              assertTrue(button.getY() + button.getHeight() <= height - 16);
              button.doClick(0);
            }
            assertFalse(
                card.buttons().get(0).getBounds().intersects(card.buttons().get(1).getBounds()));
            assertEquals(
                List.of(
                    CoachPanelState.Action.RETRY,
                    CoachPanelState.Action.SHOW_BEST,
                    CoachPanelState.Action.CONTINUE),
                actions);
            assertEquals(height, card.heightFor(), "measurement and painting must agree");
          }
        });
  }

  @Test
  void emptyTextAndTransientNarrowWidthsRemainSafe() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (CoachPanelState.Prompt prompt :
              List.of(
                  new CoachPanelState.Prompt(
                      false, "Hint", null, "Hint", null, null, "", List.of()),
                  new CoachPanelState.Prompt(
                      false, "Hint", null, "Hint", "Headline", null, "", List.of()),
                  new CoachPanelState.Prompt(
                      false, "Hint", null, "Hint", null, "Detail", "", List.of()))) {
            CoachMoveCard card = new CoachMoveCard(() -> state(prompt), action -> {});
            for (int width : new int[] {-1, 0, 1, 20, 32, 306}) {
              card.setSize(width, 1);
              int height = card.heightFor();
              assertTrue(height >= 0);
              if (width <= 32) assertEquals(0, height, "transient width has no room for content");
              card.setSize(width, height);
              Graphics2D graphics =
                  new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB).createGraphics();
              try {
                card.paintComponent(graphics);
              } finally {
                graphics.dispose();
              }
            }
          }
        });
  }

  private static CoachPanelState.Prompt prompt(ResourceBundle strings) {
    return new CoachPanelState.Prompt(
        true,
        "Blunder",
        MoveVerdict.BLUNDER,
        "Move 31 · A1",
        MessageFormat.format(strings.getString("CoachPanel.change"), "−25%"),
        MessageFormat.format(strings.getString("CoachPanel.pointsLost"), 14),
        "",
        List.of(
            new CoachPanelState.Choice(
                CoachPanelState.Action.RETRY,
                strings.getString("CoachPanel.retry"),
                CoachButton.Kind.PRIMARY),
            new CoachPanelState.Choice(
                CoachPanelState.Action.SHOW_BEST,
                strings.getString("CoachPanel.showBest"),
                CoachButton.Kind.WARM),
            new CoachPanelState.Choice(
                CoachPanelState.Action.CONTINUE,
                strings.getString("CoachPanel.continue"),
                CoachButton.Kind.WARM_QUIET)));
  }

  private static CoachPanelState state(CoachPanelState.Prompt prompt) {
    return new CoachPanelState(
        "",
        "",
        "",
        CoachPanelState.Tone.ALERT,
        false,
        "",
        "",
        "",
        "",
        "",
        null,
        null,
        prompt,
        null,
        null);
  }

  private static int fullTextInk(String text, Color color, int style, int size) {
    BufferedImage image = new BufferedImage(1000, 100, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    try {
      CoachPaint.smooth(g);
      g.setColor(color);
      g.setFont(CoachPaint.font(text, style, size));
      g.drawString(text, 16, 16 + g.getFontMetrics().getAscent());
    } finally {
      g.dispose();
    }
    return countInk(image, color);
  }

  private static int countInk(BufferedImage image, Color color) {
    int count = 0;
    for (int y = 0; y < image.getHeight(); y++)
      for (int x = 0; x < image.getWidth(); x++) {
        if (image.getRGB(x, y) == color.getRGB()) count++;
      }
    return count;
  }

  private static Rectangle inkBounds(BufferedImage image, Color color) {
    Rectangle bounds = null;
    for (int y = 0; y < image.getHeight(); y++)
      for (int x = 0; x < image.getWidth(); x++) {
        if (image.getRGB(x, y) == color.getRGB()) {
          Rectangle pixel = new Rectangle(x, y, 1, 1);
          bounds = bounds == null ? pixel : bounds.union(pixel);
        }
      }
    return bounds == null ? new Rectangle() : bounds;
  }
}
