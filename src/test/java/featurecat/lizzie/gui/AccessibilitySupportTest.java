package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
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
  void accessibleButtonsActivateWithEnterAsWellAsSpace() {
    JButton button = new JButton("Import");

    AccessibilitySupport.button(button, "Import", "Import a local weight");

    assertEquals(
        "pressed",
        button.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("pressed ENTER")));
    assertEquals(
        "released",
        button.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("released ENTER")));
  }

  @Test
  void screenReaderOnlyDescriptionsNeverRegainVisibleTooltips() {
    JPanel panel = new JPanel();
    JButton nextMove = new JButton(">");
    nextMove.setToolTipText("Old tooltip");
    panel.add(nextMove);

    AccessibilitySupport.buttonWithoutTooltip(
        nextMove, "Next move", "Go forward one move");
    AccessibilitySupport.applyToTree(panel);

    assertNull(nextMove.getToolTipText());
    assertEquals("Next move", nextMove.getAccessibleContext().getAccessibleName());
    assertEquals(
        "Go forward one move", nextMove.getAccessibleContext().getAccessibleDescription());
    assertEquals(
        "pressed",
        nextMove
            .getInputMap(JComponent.WHEN_FOCUSED)
            .get(KeyStroke.getKeyStroke("pressed ENTER")));
  }

  @Test
  void treeWideTooltipSuppressionPreservesButtonSemanticsAfterAnotherTreePass() {
    JPanel toolbar = new JPanel();
    JPanel details = new JPanel();
    JButton labeled = new JButton("Auto analyze");
    labeled.setToolTipText("Analyze every move");
    JButton iconOnly = new JButton("...");
    AccessibilitySupport.button(iconOnly, "More actions", "Show hidden toolbar actions");
    details.add(labeled);
    details.add(iconOnly);
    toolbar.add(details);

    AccessibilitySupport.applyToTree(toolbar);
    AccessibilitySupport.disableVisibleButtonTooltips(toolbar);
    AccessibilitySupport.applyToTree(toolbar);

    assertNull(labeled.getToolTipText());
    assertNull(iconOnly.getToolTipText());
    assertEquals("Auto analyze", labeled.getAccessibleContext().getAccessibleName());
    assertEquals(
        "Analyze every move", labeled.getAccessibleContext().getAccessibleDescription());
    assertEquals("More actions", iconOnly.getAccessibleContext().getAccessibleName());
    assertEquals(
        "Show hidden toolbar actions",
        iconOnly.getAccessibleContext().getAccessibleDescription());
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
  void treePassPreservesExplicitProgressSemanticsAsPercentageChanges() {
    JPanel panel = new JPanel();
    JProgressBar progress = new JProgressBar(0, 1000);
    progress.setStringPainted(true);
    progress.setString("0%");
    AccessibilitySupport.progress(
        progress, "Setup progress", "Download, installation, or optimization progress");
    panel.add(progress);

    AccessibilitySupport.applyToTree(panel);
    progress.setValue(995);
    progress.setString("99%");
    AccessibilitySupport.applyToTree(panel);

    assertEquals("Setup progress", progress.getAccessibleContext().getAccessibleName());
    assertEquals(
        "Download, installation, or optimization progress",
        progress.getAccessibleContext().getAccessibleDescription());
  }

  @Test
  void relabelButtonUpdatesVisibleAndAccessibleDynamicActionText() {
    JButton button = new JButton("Stop download");
    AccessibilitySupport.button(button, "Stop download", "Stop download");

    AccessibilitySupport.relabelButton(button, "Stop benchmark", "Stop benchmark");
    AccessibilitySupport.applyToTree(button);

    assertEquals("Stop benchmark", button.getText());
    assertEquals("Stop benchmark", button.getToolTipText());
    assertEquals("Stop benchmark", button.getAccessibleContext().getAccessibleName());
    assertEquals("Stop benchmark", button.getAccessibleContext().getAccessibleDescription());
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
