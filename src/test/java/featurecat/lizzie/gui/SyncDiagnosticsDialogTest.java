package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
}
