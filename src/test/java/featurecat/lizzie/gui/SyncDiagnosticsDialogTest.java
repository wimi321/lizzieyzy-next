package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.FontMetrics;
import java.nio.file.Path;
import javax.swing.JButton;
import javax.swing.plaf.basic.BasicButtonUI;
import org.junit.jupiter.api.Test;

class SyncDiagnosticsDialogTest {
  @Test
  void exportSuccessStatusSanitizesUserPathBeforeItCanBeCopied() {
    Path zip =
        Path.of(
            "/home/Alice Smith/.lizzie-yzy/sync-diagnostics/sync-diagnostics-20260601-125136.zip");

    String status = SyncDiagnosticsDialog.exportSuccessStatus("Exported to:", zip);

    assertTrue(status.contains("Exported to:"));
    assertTrue(status.contains("/home/<user>/sync-diagnostics-20260601-125136.zip"));
    assertFalse(status.contains("Alice Smith"));
    assertFalse(status.contains(".lizzie-yzy"));
    assertFalse(status.contains("sync-diagnostics/"));
  }

  @Test
  void exportSuccessStatusShowsProjectRelativePathForDefaultExportFolder() {
    Path zip =
        Path.of(
            "/home/Alice Smith/dev/lizzieyzy-next/sync-diagnostics/sync-diagnostics-20260601-125136.zip");

    String status = SyncDiagnosticsDialog.exportSuccessStatus("Exported to:", zip);

    assertTrue(status.contains("lizzieyzy-next/sync-diagnostics/"));
    assertTrue(status.contains("sync-diagnostics-20260601-125136.zip"));
    assertFalse(status.contains("Alice Smith"));
    assertFalse(status.contains("/home/Alice Smith"));
  }

  @Test
  void exportSuccessStatusShowsBuildRootRelativePathForCopiedWindowsBuilds() {
    Path zip =
        Path.of(
            "/tmp/lizzieyzy-next-sync-diagnostics-export-build-20260601/sync-diagnostics/sync-diagnostics-20260601-125136.zip");

    String status = SyncDiagnosticsDialog.exportSuccessStatus("Exported to:", zip);

    assertTrue(
        status.contains("lizzieyzy-next-sync-diagnostics-export-build-20260601/sync-diagnostics/"));
    assertTrue(status.contains("sync-diagnostics-20260601-125136.zip"));
    assertFalse(status.contains("/tmp/"));
  }

  @Test
  void footerButtonsDisableHoverAndFocusPaintArtifacts() {
    JButton button = new JButton("Export package");

    SyncDiagnosticsDialog.configureFooterButton(button);

    assertNotEquals(BasicButtonUI.class, button.getUI().getClass());
    assertTrue(button.isFocusable());
    assertTrue(button.isFocusPainted());
    assertFalse(button.isRolloverEnabled());
    assertFalse(button.isContentAreaFilled());
    assertFalse(button.isBorderPainted());
    assertTrue(button.getMargin().left >= 12);
    assertTrue(button.getMargin().right >= 12);
    assertTrue(button.getMinimumSize().width >= button.getPreferredSize().width);
    assertTrue(button.getMinimumSize().height >= button.getPreferredSize().height);
  }

  @Test
  void footerButtonSizeLeavesRoomForChineseLabels() {
    JButton button = new JButton("复制诊断摘要");

    SyncDiagnosticsDialog.configureFooterButton(button);

    FontMetrics metrics = button.getFontMetrics(button.getFont());
    int requiredWidth =
        metrics.stringWidth(button.getText())
            + button.getMargin().left
            + button.getMargin().right
            + 16;
    int requiredHeight =
        metrics.getHeight() + button.getMargin().top + button.getMargin().bottom + 6;
    assertTrue(button.getPreferredSize().width >= requiredWidth);
    assertTrue(button.getPreferredSize().height >= requiredHeight);
  }
}
