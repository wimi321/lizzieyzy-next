package featurecat.lizzie.logging;

import featurecat.lizzie.analysis.SyncDiagnosticsExportSanitizer;
import java.util.Map;

public final class ExportSanitizer {
  public static final String VERSION = "export-1";

  private final PersistenceSanitizer persistence = new PersistenceSanitizer();
  private final SyncDiagnosticsExportSanitizer shareTime = new SyncDiagnosticsExportSanitizer();

  public String sanitize(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    return shareTime.text(persistence.sanitize(text));
  }

  public SyncDiagnosticsExportSanitizer shareTime() {
    return shareTime;
  }

  public Map<String, String> aliases() {
    return shareTime.aliases();
  }
}
