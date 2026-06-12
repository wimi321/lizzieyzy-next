package featurecat.lizzie.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SyncDiagnosticsExportSnapshot {
  private final long capturedAtMillis;
  private final SyncDiagnosticsReport report;
  private final List<SyncProtocolDiagnosticEvent> recentProtocolEvents;
  private final List<SyncDecisionTrace> recentDecisionTraces;
  private final List<YikeSessionDiagnosticsSnapshot> recentYikeEvents;
  private final SyncDiagnosticsEnvironment environment;

  public SyncDiagnosticsExportSnapshot(
      long capturedAtMillis,
      SyncDiagnosticsReport report,
      List<SyncProtocolDiagnosticEvent> recentProtocolEvents,
      List<SyncDecisionTrace> recentDecisionTraces,
      List<YikeSessionDiagnosticsSnapshot> recentYikeEvents,
      SyncDiagnosticsEnvironment environment) {
    this.capturedAtMillis = capturedAtMillis;
    this.report = report == null ? SyncDiagnosticsReport.empty() : report;
    this.recentProtocolEvents = immutableCopy(recentProtocolEvents);
    this.recentDecisionTraces = immutableCopy(recentDecisionTraces);
    this.recentYikeEvents = immutableCopy(recentYikeEvents);
    this.environment = environment == null ? SyncDiagnosticsEnvironment.capture() : environment;
  }

  public long getCapturedAtMillis() {
    return capturedAtMillis;
  }

  public SyncDiagnosticsReport getReport() {
    return report;
  }

  public List<SyncProtocolDiagnosticEvent> getRecentProtocolEvents() {
    return recentProtocolEvents;
  }

  public List<SyncDecisionTrace> getRecentDecisionTraces() {
    return recentDecisionTraces;
  }

  public List<YikeSessionDiagnosticsSnapshot> getRecentYikeEvents() {
    return recentYikeEvents;
  }

  public SyncDiagnosticsEnvironment getEnvironment() {
    return environment;
  }

  private static <T> List<T> immutableCopy(List<T> values) {
    if (values == null || values.isEmpty()) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(new ArrayList<>(values));
  }
}
