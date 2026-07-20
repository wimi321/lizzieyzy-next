package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JButton;
import javax.swing.plaf.basic.BasicButtonUI;
import org.junit.jupiter.api.Test;

class WholeGameAnalysisDialogTest {
  @Test
  void remainingDurationUsesLocaleNeutralClockNotation() {
    assertEquals("00:00", WholeGameAnalysisDialog.formatDuration(0));
    assertEquals("00:01", WholeGameAnalysisDialog.formatDuration(1));
    assertEquals("01:05", WholeGameAnalysisDialog.formatDuration(65_000));
    assertEquals("1:01:01", WholeGameAnalysisDialog.formatDuration(3_661_000));
  }

  @Test
  void completionButtonUsesUiThatPaintsItsAccentBackgroundOnWindows() {
    JButton button = new JButton("Close");

    WholeGameAnalysisDialog.installPortableButtonFill(button);

    assertTrue(button.getUI() instanceof BasicButtonUI);
    assertTrue(button.isContentAreaFilled());
  }
}
