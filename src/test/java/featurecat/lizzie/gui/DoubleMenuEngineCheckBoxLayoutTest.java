package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.awt.Insets;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class DoubleMenuEngineCheckBoxLayoutTest {
  @Test
  void wrnAndPdaUseKomiStyleLabelColonAndAdjacentField() {
    for (ResourceBundle bundle :
        List.of(
            Lizzie.resourceBundle,
            ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE))) {
      assertKomiStyleGroup(bundle.getString("Menu.separateLblWrn"));
      assertKomiStyleGroup(bundle.getString("Menu.separateLblPda"));
    }
  }

  private static void assertKomiStyleGroup(String rawLabel) {
    JFontCheckBox enable = new JFontCheckBox();
    JFontLabel label = new JFontLabel();
    JFontTextField field = new JFontTextField();
    JPanel panel = Menu.attachDoubleMenuLabeledField(enable, label, field, rawLabel);
    panel.setSize(panel.getPreferredSize());
    panel.doLayout();

    assertEquals("", enable.getText());
    assertTrue(
        label.getText().endsWith(":") || label.getText().endsWith("："),
        () -> "label should end with colon: " + label.getText());
    assertTrue(label.getText().startsWith(rawLabel.replaceAll("[:：]+$", "")));
    assertEquals(3, panel.getComponentCount());
    assertFalse(containsSpinner(panel));

    Icon icon = enable.getIcon();
    int iconWidth = icon == null ? 16 : icon.getIconWidth();
    Insets insets = enable.getInsets();
    assertTrue(
        enable.getPreferredSize().width <= iconWidth + insets.left + insets.right,
        () -> "checkbox wider than icon: " + enable.getPreferredSize().width);

    assertEquals(0, field.getX() - (label.getX() + label.getWidth()));
    assertTrue(
        field.getX() - (enable.getX() + enable.getWidth()) >= label.getWidth() - 2,
        () -> "label not sitting between checkbox and field: " + panel.getBounds());
  }

  private static boolean containsSpinner(JPanel panel) {
    for (var component : panel.getComponents()) {
      if (component instanceof JButton) {
        return true;
      }
    }
    return false;
  }
}
