package featurecat.lizzie.analysis;

public final class SyncDiagnosticsReport {
  private static final SyncDiagnosticsReport EMPTY = SyncDiagnosticsReport.builder().build();

  private final SyncDiagnosticsSnapshot syncSnapshot;
  private final YikeSessionDiagnosticsSnapshot yikeSnapshot;
  private final SyncDecisionTrace latestDecisionTrace;
  private final long capturedAtMillis;
  private final String source;

  private SyncDiagnosticsReport(Builder builder) {
    this.syncSnapshot =
        builder.syncSnapshot == null ? SyncDiagnosticsSnapshot.empty() : builder.syncSnapshot;
    this.yikeSnapshot =
        builder.yikeSnapshot == null
            ? YikeSessionDiagnosticsSnapshot.empty()
            : builder.yikeSnapshot;
    this.latestDecisionTrace =
        builder.latestDecisionTrace == null
            ? this.syncSnapshot.getLatestDecisionTrace()
            : builder.latestDecisionTrace;
    this.capturedAtMillis = builder.capturedAtMillis;
    this.source = SyncDecisionTrace.normalize(builder.source, "unknown");
  }

  public static SyncDiagnosticsReport empty() {
    return EMPTY;
  }

  public static Builder builder() {
    return new Builder();
  }

  public SyncDiagnosticsSnapshot getSyncSnapshot() {
    return syncSnapshot;
  }

  public YikeSessionDiagnosticsSnapshot getYikeSnapshot() {
    return yikeSnapshot;
  }

  public SyncDecisionTrace getLatestDecisionTrace() {
    return latestDecisionTrace;
  }

  public long getCapturedAtMillis() {
    return capturedAtMillis;
  }

  public String getSource() {
    return source;
  }

  public String toSummaryText() {
    StringBuilder text = new StringBuilder();
    text.append("Sync Diagnostics\n");
    text.append("capturedAtMillis: ").append(capturedAtMillis).append('\n');
    text.append("source: ").append(source).append('\n');
    text.append('\n').append("ReadBoard").append('\n');
    text.append(syncSnapshot.toSummaryText()).append('\n');
    text.append('\n').append("Yike").append('\n');
    text.append(yikeSnapshot.toSummaryText()).append('\n');
    text.append('\n').append("Latest decision").append('\n');
    text.append(latestDecisionTrace.toSummaryText());
    return text.toString();
  }

  public static final class Builder {
    private SyncDiagnosticsSnapshot syncSnapshot;
    private YikeSessionDiagnosticsSnapshot yikeSnapshot;
    private SyncDecisionTrace latestDecisionTrace;
    private long capturedAtMillis;
    private String source;

    public Builder syncSnapshot(SyncDiagnosticsSnapshot syncSnapshot) {
      this.syncSnapshot = syncSnapshot;
      return this;
    }

    public Builder yikeSnapshot(YikeSessionDiagnosticsSnapshot yikeSnapshot) {
      this.yikeSnapshot = yikeSnapshot;
      return this;
    }

    public Builder latestDecisionTrace(SyncDecisionTrace latestDecisionTrace) {
      this.latestDecisionTrace = latestDecisionTrace;
      return this;
    }

    public Builder capturedAtMillis(long capturedAtMillis) {
      this.capturedAtMillis = capturedAtMillis;
      return this;
    }

    public Builder source(String source) {
      this.source = source;
      return this;
    }

    public SyncDiagnosticsReport build() {
      return new SyncDiagnosticsReport(this);
    }
  }
}
