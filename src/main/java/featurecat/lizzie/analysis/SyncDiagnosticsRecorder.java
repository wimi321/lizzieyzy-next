package featurecat.lizzie.analysis;

public final class SyncDiagnosticsRecorder {
  private static final SyncDiagnosticsRecorder DEFAULT = new SyncDiagnosticsRecorder();

  private final Object lock = new Object();
  private SyncDiagnosticsSnapshot sync = SyncDiagnosticsSnapshot.empty();
  private YikeSessionDiagnosticsSnapshot yike = YikeSessionDiagnosticsSnapshot.empty();
  private SyncDecisionTrace latestDecision = SyncDecisionTrace.empty();

  public static SyncDiagnosticsRecorder getDefault() {
    return DEFAULT;
  }

  public void updateSync(SyncDiagnosticsSnapshot value) {
    synchronized (lock) {
      sync = value == null ? SyncDiagnosticsSnapshot.empty() : value;
    }
  }

  public void updateYikeSession(YikeSessionDiagnosticsSnapshot value) {
    synchronized (lock) {
      yike = value == null ? YikeSessionDiagnosticsSnapshot.empty() : value;
    }
  }

  public void updateLatestDecision(SyncDecisionTrace value) {
    synchronized (lock) {
      latestDecision = value == null ? SyncDecisionTrace.empty() : value;
    }
  }

  public SyncDiagnosticsReport snapshot() {
    synchronized (lock) {
      return SyncDiagnosticsReport.builder()
          .syncSnapshot(sync)
          .yikeSnapshot(yike)
          .latestDecisionTrace(latestDecision)
          .capturedAtMillis(System.currentTimeMillis())
          .source("recorder")
          .build();
    }
  }
}
