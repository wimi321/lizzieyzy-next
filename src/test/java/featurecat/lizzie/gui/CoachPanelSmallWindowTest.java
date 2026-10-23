package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.training.MoveVerdict;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class CoachPanelSmallWindowTest {
  @Test
  void clearingTheDecisionClampsBottomScrollWithoutGrowingTheWindow() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (int width : new int[] {260, 360}) {
            CoachPanel panel = new CoachPanel(null, () -> null);
            panel.setVisible(true);
            panel.setSize(width, 400);
            setState(panel, blunderState());
            layoutTree(panel);
            JScrollPane scroll = field(panel, "scroll", JScrollPane.class);
            assertTrue(scroll.getVerticalScrollBar().isVisible());
            scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
            assertTrue(
                scroll.getViewport().getViewPosition().y > 0, "start scrolled to the bottom");

            setState(panel, CoachPanelState.EMPTY);
            layoutTree(panel);

            assertFalse(
                scroll.getVerticalScrollBar().isVisible(), "no resize needed after collapse");
            assertTrue(scroll.getViewport().getViewPosition().y == 0);
            assertTrue(panel.getHeight() == 400, "the window must remain short throughout");
          }
        });
  }

  @Test
  void scrollingEndsWhenTheWindowOrDecisionNoLongerNeedsIt() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          CoachPanel panel = new CoachPanel(null, () -> null);
          panel.setVisible(true);
          setState(panel, blunderState());
          panel.setSize(360, 400);
          layoutTree(panel);
          JScrollPane scroll = field(panel, "scroll", JScrollPane.class);
          assertTrue(scroll.getVerticalScrollBar().isVisible());
          assertFalse(scroll.isFocusable());
          assertFalse(scroll.getVerticalScrollBar().isFocusable());
          scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
          panel.setSize(360, 900);
          layoutTree(panel);
          assertFalse(scroll.getVerticalScrollBar().isVisible());
          assertTrue(scroll.getViewport().getViewPosition().y == 0);
          panel.setSize(360, 400);
          setState(panel, CoachPanelState.EMPTY);
          layoutTree(panel);
          assertFalse(scroll.getVerticalScrollBar().isVisible());
        });
  }

  @Test
  void blunderDecisionAndActionsStaySeparateAndReachableInShortWindows() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (int width : new int[] {260, 360}) {
            for (int height : new int[] {400, 600, 900}) {
              CoachPanel panel = new CoachPanel(null, () -> null);
              panel.setVisible(true);
              setState(panel, blunderState());
              panel.setSize(width, height);
              layoutTree(panel);
              CoachMoveCard card = field(panel, "moveCard", CoachMoveCard.class);
              JPanel actions = field(panel, "actions", JPanel.class);
              Rectangle cardBounds = inPanel(panel, card);
              Rectangle actionBounds = inPanel(panel, actions);
              assertFalse(
                  cardBounds.intersects(actionBounds),
                  "decision overlaps actions at " + width + "x" + height);
              for (CoachButton button : panel.buttons()) {
                if (!button.isVisible() || button.getParent() == null) continue;
                assertTrue(button.getWidth() > 0 && button.getHeight() > 0, button.getText());
                button.scrollRectToVisible(
                    new Rectangle(0, 0, button.getWidth(), button.getHeight()));
                Rectangle visible = button.getVisibleRect();
                assertTrue(
                    visible.contains(new Rectangle(0, 0, button.getWidth(), button.getHeight())),
                    "button must be reachable without resizing: "
                        + button.getText()
                        + " at "
                        + width
                        + "x"
                        + height
                        + ", visible "
                        + visible);
              }
            }
          }
        });
  }

  private static CoachPanelState blunderState() {
    List<CoachPanelState.Choice> choices =
        List.of(
            new CoachPanelState.Choice(
                CoachPanelState.Action.RETRY, "Play it again", CoachButton.Kind.PRIMARY),
            new CoachPanelState.Choice(
                CoachPanelState.Action.SHOW_BEST, "See the AI move", CoachButton.Kind.WARM),
            new CoachPanelState.Choice(
                CoachPanelState.Action.CONTINUE,
                "Keep it and play on",
                CoachButton.Kind.WARM_QUIET));
    return new CoachPanelState(
        "AI Coach",
        "Plays like a 3 dan player",
        "Your decision",
        CoachPanelState.Tone.ALERT,
        false,
        "You",
        "0 captures",
        "AI",
        "0 captures",
        "Time: you 01:00 · AI 01:00 · komi 7.5",
        new CoachPanelState.WinChance("Estimated at 3 dan level", new double[] {50, 25}, 50),
        null,
        new CoachPanelState.Prompt(
            true,
            "Blunder",
            MoveVerdict.BLUNDER,
            "Move 31 · A1",
            "Win chance −25%",
            "about 14 points lost",
            "At 3 dan level your win chance fell from 50% to 25%. Less than 1% of players at this level play it.",
            choices),
        null,
        null);
  }

  private static void setState(CoachPanel panel, CoachPanelState state) {
    try {
      Field field = CoachPanel.class.getDeclaredField("state");
      field.setAccessible(true);
      field.set(panel, state);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static <T> T field(CoachPanel panel, String name, Class<T> type) {
    try {
      Field field = CoachPanel.class.getDeclaredField(name);
      field.setAccessible(true);
      return type.cast(field.get(panel));
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static Rectangle inPanel(CoachPanel panel, JComponent component) {
    return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), panel);
  }

  private static void layoutTree(Container container) {
    container.doLayout();
    for (Component child : container.getComponents()) {
      if (child instanceof Container nested) layoutTree(nested);
    }
  }
}
