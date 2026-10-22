package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import java.awt.FontMetrics;
import java.awt.Insets;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class ToolbarSizingTest {
  @Test
  void textButtonsAreAsWideAsTheirLabelPlusPadding() {
    JFontButton shortButton = new JFontButton("Fox");
    JFontButton longButton = new JFontButton("Fox game records");

    ToolbarSizing.fitText(shortButton);
    ToolbarSizing.fitText(longButton);

    FontMetrics metrics = shortButton.getFontMetrics(shortButton.getFont());
    assertEquals(
        metrics.stringWidth("Fox game records") - metrics.stringWidth("Fox"),
        longButton.getPreferredSize().width - shortButton.getPreferredSize().width,
        "a button's width follows its label instead of a fixed multiple of the row height");
    Insets insets = shortButton.getInsets();
    assertTrue(insets.left >= ToolbarSizing.TEXT_PADDING && insets.right >= insets.left - 1);
    assertTrue(
        shortButton.getPreferredSize().width
            >= metrics.stringWidth("Fox") + 2 * ToolbarSizing.TEXT_PADDING);
    assertEquals(Config.menuHeight, shortButton.getPreferredSize().height);
    assertEquals(Config.menuHeight, longButton.getPreferredSize().height);
  }

  @Test
  void fieldGroupsShowAndHideTogether() {
    JPanel panel = new JPanel();
    JFontCheckBox check = new JFontCheckBox();
    JFontTextField field = new JFontTextField();

    ToolbarSizing.showFieldGroup(false, panel, check, field);
    assertFalse(panel.isVisible() || check.isVisible() || field.isVisible());
    ToolbarSizing.showFieldGroup(true, panel, check, field);
    assertTrue(panel.isVisible() && check.isVisible() && field.isVisible());
    ToolbarSizing.showFieldGroup(false, null, check, field);
    assertFalse(check.isVisible() || field.isVisible(), "the panel is built later");
  }

  @Test
  void kataGoFieldsKeepTheirPlaceWhileTheEngineStarts() {
    assertTrue(
        ToolbarSizing.showsKataGoFields(true, false, false, false),
        "starting engine: kind not known yet, keep the space so the toolbar does not jump");
    assertTrue(
        ToolbarSizing.showsKataGoFields(true, false, true, true),
        "KataGo, also during an engine game: the menu locks the fields instead of hiding them");
    assertFalse(
        ToolbarSizing.showsKataGoFields(true, false, true, false),
        "a loaded engine other than KataGo has no such parameters");
    assertFalse(ToolbarSizing.showsKataGoFields(true, true, false, false), "no engine");
    assertFalse(ToolbarSizing.showsKataGoFields(false, false, true, true), "turned off");
  }
}
