package featurecat.lizzie.analysis;

public final class SyncProtocolDiagnosticEvent {
  private final long timestampMillis;
  private final String summary;
  private final String source;

  private SyncProtocolDiagnosticEvent(long timestampMillis, String summary, String source) {
    this.timestampMillis = timestampMillis;
    this.summary = SyncDecisionTrace.normalize(summary, "none");
    this.source = SyncDecisionTrace.normalize(source, "unknown");
  }

  public static SyncProtocolDiagnosticEvent of(
      long timestampMillis, String summary, String source) {
    return new SyncProtocolDiagnosticEvent(timestampMillis, summary, source);
  }

  public long getTimestampMillis() {
    return timestampMillis;
  }

  public String getSummary() {
    return summary;
  }

  public String getSource() {
    return source;
  }
}
