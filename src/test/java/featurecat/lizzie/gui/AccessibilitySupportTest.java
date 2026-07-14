package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import org.junit.jupiter.api.Test;

class AccessibilitySupportTest {
  @Test
  void localizedControlsIncludeRoomForFractionalDpiRounding() {
    JButton button = new JButton("Open the Zhizi website");
    JCheckBox checkBox = new JCheckBox("\u0E02\u0E32\u0E27");

    assertTrue(
        AccessibilitySupport.localizedControlWidth(button, 40)
            >= button.getPreferredSize().width + 12);
    assertTrue(
        AccessibilitySupport.localizedControlWidth(checkBox, 40)
            >= checkBox.getPreferredSize().width + 12);
  }

  @Test
  void iconButtonsReceiveReadableSemanticsAndTooltip() {
    JButton button = new JButton();

    AccessibilitySupport.button(button, "Refresh weights", "Reload the official weight list");

    assertEquals("Refresh weights", button.getAccessibleContext().getAccessibleName());
    assertEquals(
        "Reload the official weight list",
        button.getAccessibleContext().getAccessibleDescription());
    assertEquals("Reload the official weight list", button.getToolTipText());
  }

  @Test
  void labelsAreAssociatedWithTheirInput() {
    JLabel label = new JLabel("Account");
    JTextField field = new JTextField();

    AccessibilitySupport.labelFor(label, field, "Zhizi account");

    assertSame(field, label.getLabelFor());
    assertEquals("Account", field.getAccessibleContext().getAccessibleName());
    assertEquals("Zhizi account", field.getAccessibleContext().getAccessibleDescription());
  }

  @Test
  void treePassUsesExistingTooltipsForIconOnlyControls() {
    JPanel panel = new JPanel();
    JButton iconButton = new JButton();
    iconButton.setToolTipText("Open remote compute");
    JProgressBar progress = new JProgressBar();
    progress.setStringPainted(true);
    progress.setString("Downloading 40%");
    panel.add(iconButton);
    panel.add(progress);

    AccessibilitySupport.applyToTree(panel);

    assertEquals("Open remote compute", iconButton.getAccessibleContext().getAccessibleName());
    assertEquals("Downloading 40%", progress.getAccessibleContext().getAccessibleName());
  }

  @Test
  void treePassUsesReadableTooltipInsteadOfSymbolOnlyButtonText() {
    JPanel panel = new JPanel();
    JButton firstMove = new JButton("|<");
    firstMove.setToolTipText("Go to the first move");
    JButton pdaOptions = new JButton("...");
    pdaOptions.setToolTipText("Open PDA options");
    JButton explicitlyNamed = new JButton("|<");
    AccessibilitySupport.button(
        explicitlyNamed, "First move", "Go to the first move in the game");
    panel.add(firstMove);
    panel.add(pdaOptions);
    panel.add(explicitlyNamed);

    AccessibilitySupport.applyToTree(panel);

    assertEquals("Go to the first move", firstMove.getAccessibleContext().getAccessibleName());
    assertEquals("Open PDA options", pdaOptions.getAccessibleContext().getAccessibleName());
    assertEquals("First move", explicitlyNamed.getAccessibleContext().getAccessibleName());
    assertEquals(
        "Go to the first move in the game",
        explicitlyNamed.getAccessibleContext().getAccessibleDescription());
  }
}
