package featurecat.lizzie.analysis;

public final class SyncDiagnosticsRecorder {
  static final int PROTOCOL_EVENT_CAPACITY = 100;
  static final int DECISION_TRACE_CAPACITY = 50;
  static final int YIKE_EVENT_CAPACITY = 50;

  private static final SyncDiagnosticsRecorder DEFAULT = new SyncDiagnosticsRecorder();

  private final Object lock = new Object();
  private SyncDiagnosticsSnapshot sync = SyncDiagnosticsSnapshot.empty();
  private YikeSessionDiagnosticsSnapshot yike = YikeSessionDiagnosticsSnapshot.empty();
  private SyncDecisionTrace latestDecision = SyncDecisionTrace.empty();
  private final SyncDiagnosticsEventBuffer<SyncProtocolDiagnosticEvent> protocolEvents =
      new SyncDiagnosticsEventBuffer<>(PROTOCOL_EVENT_CAPACITY);
  private final SyncDiagnosticsEventBuffer<SyncDecisionTrace> decisionTraces =
      new SyncDiagnosticsEventBuffer<>(DECISION_TRACE_CAPACITY);
  private final SyncDiagnosticsEventBuffer<YikeSessionDiagnosticsSnapshot> yikeEvents =
      new SyncDiagnosticsEventBuffer<>(YIKE_EVENT_CAPACITY);

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
      if (!isEmptyYikeSnapshot(yike)) {
        yikeEvents.add(yike);
      }
    }
  }

  public void updateLatestDecision(SyncDecisionTrace value) {
    synchronized (lock) {
      latestDecision = value == null ? SyncDecisionTrace.empty() : value;
      if (!latestDecision.isEmpty()) {
        decisionTraces.add(latestDecision);
      }
    }
  }

  public void recordProtocolEvent(SyncProtocolDiagnosticEvent event) {
    synchronized (lock) {
      protocolEvents.add(event);
    }
  }

  public SyncDiagnosticsReport snapshot() {
    synchronized (lock) {
      return snapshotAt(System.currentTimeMillis());
    }
  }

  public SyncDiagnosticsExportSnapshot exportSnapshot() {
    synchronized (lock) {
      long capturedAtMillis = System.currentTimeMillis();
      return new SyncDiagnosticsExportSnapshot(
          capturedAtMillis,
          snapshotAt(capturedAtMillis),
          protocolEvents.snapshot(),
          decisionTraces.snapshot(),
          yikeEvents.snapshot(),
          SyncDiagnosticsEnvironment.capture());
    }
  }

  public void clearForTests() {
    synchronized (lock) {
      sync = SyncDiagnosticsSnapshot.empty();
      yike = YikeSessionDiagnosticsSnapshot.empty();
      latestDecision = SyncDecisionTrace.empty();
      protocolEvents.clear();
      decisionTraces.clear();
      yikeEvents.clear();
    }
  }

  public static void clearDefaultForTests() {
    DEFAULT.clearForTests();
  }

  private SyncDiagnosticsReport snapshotAt(long capturedAtMillis) {
    return SyncDiagnosticsReport.builder()
        .syncSnapshot(sync)
        .yikeSnapshot(yike)
        .latestDecisionTrace(latestDecision)
        .capturedAtMillis(capturedAtMillis)
        .source("recorder")
        .build();
  }

  private static boolean isEmptyYikeSnapshot(YikeSessionDiagnosticsSnapshot value) {
    return value == null || value == YikeSessionDiagnosticsSnapshot.empty();
  }
}
