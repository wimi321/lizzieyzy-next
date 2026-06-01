package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class SyncDiagnosticsResourceTest {
  @Test
  void englishBundleContainsSyncDiagnosticsKeys() {
    ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);

    assertEquals("Sync diagnostics", bundle.getString("Menu.syncDiagnostics"));
    assertEquals("Sync diagnostics", bundle.getString("SyncDiagnostics.title"));
    assertEquals("Refresh", bundle.getString("SyncDiagnostics.refresh"));
    assertEquals("Copy summary", bundle.getString("SyncDiagnostics.copy"));
    assertEquals("Export package", bundle.getString("SyncDiagnostics.export"));
    assertEquals("Exported to:", bundle.getString("SyncDiagnostics.exportSuccess"));
    assertEquals("Export failed:", bundle.getString("SyncDiagnostics.exportFailure"));
    assertEquals("Close", bundle.getString("SyncDiagnostics.close"));
  }

  @Test
  void chineseBundleContainsSyncDiagnosticsKeys() {
    ResourceBundle bundle =
        ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);

    assertEquals("同步诊断", bundle.getString("Menu.syncDiagnostics"));
    assertEquals("同步诊断", bundle.getString("SyncDiagnostics.title"));
    assertEquals("刷新", bundle.getString("SyncDiagnostics.refresh"));
    assertEquals("复制诊断摘要", bundle.getString("SyncDiagnostics.copy"));
    assertEquals("导出诊断包", bundle.getString("SyncDiagnostics.export"));
    assertEquals("已导出到：", bundle.getString("SyncDiagnostics.exportSuccess"));
    assertEquals("导出失败：", bundle.getString("SyncDiagnostics.exportFailure"));
    assertEquals("关闭", bundle.getString("SyncDiagnostics.close"));
  }
}
