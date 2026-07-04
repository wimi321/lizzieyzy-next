package featurecat.lizzie.analysis;

import java.util.Map;

public final class SyncDiagnosticsSnapshot {
  private static final SyncDiagnosticsSnapshot EMPTY = SyncDiagnosticsSnapshot.builder().build();

  private final boolean readBoardAttached;
  private final boolean readBoardConnected;
  private final boolean usePipe;
  private final boolean syncing;
  private final boolean awaitingFirstSyncFrame;
  private final boolean hasResumeState;
  private final boolean hasLastResolvedSnapshotNode;
  private final long syncAnalysisEpoch;
  private final long timestampMillis;
  private final String source;
  private final String summary;
  private final String pendingRemoteContextSummary;
  private final String lastResolvedSnapshotSummary;
  private final String lastProtocolLineSummary;
  private final long lastProtocolTimestampMillis;
  private final SyncDecisionTrace latestDecisionTrace;
  private final Map<String, String> stateFlags;

  private SyncDiagnosticsSnapshot(Builder builder) {
    this.readBoardAttached = builder.readBoardAttached;
    this.readBoardConnected = builder.readBoardConnected;
    this.usePipe = builder.usePipe;
    this.syncing = builder.syncing;
    this.awaitingFirstSyncFrame = builder.awaitingFirstSyncFrame;
    this.hasResumeState = builder.hasResumeState;
    this.hasLastResolvedSnapshotNode = builder.hasLastResolvedSnapshotNode;
    this.syncAnalysisEpoch = builder.syncAnalysisEpoch;
    this.timestampMillis = builder.timestampMillis;
    this.source = SyncDecisionTrace.normalize(builder.source, "unknown");
    this.summary = SyncDecisionTrace.normalize(builder.summary, "not captured");
    this.pendingRemoteContextSummary =
        SyncDecisionTrace.normalize(builder.pendingRemoteContextSummary, "none");
    this.lastResolvedSnapshotSummary =
        SyncDecisionTrace.normalize(builder.lastResolvedSnapshotSummary, "none");
    this.lastProtocolLineSummary =
        SyncDecisionTrace.normalize(builder.lastProtocolLineSummary, "none");
    this.lastProtocolTimestampMillis = builder.lastProtocolTimestampMillis;
    this.latestDecisionTrace =
        builder.latestDecisionTrace == null
            ? SyncDecisionTrace.empty()
            : builder.latestDecisionTrace;
    this.stateFlags = SyncDecisionTrace.copyStringMap(builder.stateFlags);
  }

  public static SyncDiagnosticsSnapshot empty() {
    return EMPTY;
  }

  public static Builder builder() {
    return new Builder();
  }

  public boolean isReadBoardAttached() {
    return readBoardAttached;
  }

  public boolean isReadBoardConnected() {
    return readBoardConnected;
  }

  public boolean isUsePipe() {
    return usePipe;
  }

  public boolean isSyncing() {
    return syncing;
  }

  public boolean isAwaitingFirstSyncFrame() {
    return awaitingFirstSyncFrame;
  }

  public boolean hasResumeState() {
    return hasResumeState;
  }

  public boolean hasLastResolvedSnapshotNode() {
    return hasLastResolvedSnapshotNode;
  }

  public long getSyncAnalysisEpoch() {
    return syncAnalysisEpoch;
  }

  public long getTimestampMillis() {
    return timestampMillis;
  }

  public String getSource() {
    return source;
  }

  public String getSummary() {
    return summary;
  }

  public String getPendingRemoteContextSummary() {
    return pendingRemoteContextSummary;
  }

  public String getLastResolvedSnapshotSummary() {
    return lastResolvedSnapshotSummary;
  }

  public String getLastProtocolLineSummary() {
    return lastProtocolLineSummary;
  }

  public long getLastProtocolTimestampMillis() {
    return lastProtocolTimestampMillis;
  }

  public SyncDecisionTrace getLatestDecisionTrace() {
    return latestDecisionTrace;
  }

  public Map<String, String> getStateFlags() {
    return stateFlags;
  }

  public String toSummaryText() {
    StringBuilder text = new StringBuilder();
    text.append("readboard:\n");
    text.append("  attached: ").append(readBoardAttached).append('\n');
    text.append("  connected: ").append(readBoardConnected).append('\n');
    text.append("  usePipe: ").append(usePipe).append('\n');
    text.append("  syncing: ").append(syncing).append('\n');
    text.append("  awaitingFirstSyncFrame: ").append(awaitingFirstSyncFrame).append('\n');
    text.append("  hasResumeState: ").append(hasResumeState).append('\n');
    text.append("  hasLastResolvedSnapshotNode: ").append(hasLastResolvedSnapshotNode).append('\n');
    text.append("  syncAnalysisEpoch: ").append(syncAnalysisEpoch).append('\n');
    text.append("  pendingRemoteContext: ").append(pendingRemoteContextSummary).append('\n');
    text.append("  lastResolvedSnapshot: ").append(lastResolvedSnapshotSummary).append('\n');
    text.append("  lastProtocolLine: ").append(lastProtocolLineSummary).append('\n');
    text.append("  lastProtocolTimestampMillis: ").append(lastProtocolTimestampMillis).append('\n');
    text.append("  source: ").append(source).append('\n');
    text.append("  timestampMillis: ").append(timestampMillis).append('\n');
    text.append("  summary: ").append(summary);
    return text.toString();
  }

  public static final class Builder {
    private boolean readBoardAttached;
    private boolean readBoardConnected;
    private boolean usePipe;
    private boolean syncing;
    private boolean awaitingFirstSyncFrame;
    private boolean hasResumeState;
    private boolean hasLastResolvedSnapshotNode;
    private long syncAnalysisEpoch;
    private long timestampMillis;
    private String source;
    private String summary;
    private String pendingRemoteContextSummary;
    private String lastResolvedSnapshotSummary;
    private String lastProtocolLineSummary;
    private long lastProtocolTimestampMillis;
    private SyncDecisionTrace latestDecisionTrace;
    private Map<String, String> stateFlags;

    public Builder readBoardAttached(boolean readBoardAttached) {
      this.readBoardAttached = readBoardAttached;
      return this;
    }

    public Builder readBoardConnected(boolean readBoardConnected) {
      this.readBoardConnected = readBoardConnected;
      return this;
    }

    public Builder usePipe(boolean usePipe) {
      this.usePipe = usePipe;
      return this;
    }

    public Builder syncing(boolean syncing) {
      this.syncing = syncing;
      return this;
    }

    public Builder awaitingFirstSyncFrame(boolean awaitingFirstSyncFrame) {
      this.awaitingFirstSyncFrame = awaitingFirstSyncFrame;
      return this;
    }

    public Builder hasResumeState(boolean hasResumeState) {
      this.hasResumeState = hasResumeState;
      return this;
    }

    public Builder hasLastResolvedSnapshotNode(boolean hasLastResolvedSnapshotNode) {
      this.hasLastResolvedSnapshotNode = hasLastResolvedSnapshotNode;
      return this;
    }

    public Builder syncAnalysisEpoch(long syncAnalysisEpoch) {
      this.syncAnalysisEpoch = syncAnalysisEpoch;
      return this;
    }

    public Builder timestampMillis(long timestampMillis) {
      this.timestampMillis = timestampMillis;
      return this;
    }

    public Builder source(String source) {
      this.source = source;
      return this;
    }

    public Builder summary(String summary) {
      this.summary = summary;
      return this;
    }

    public Builder pendingRemoteContextSummary(String pendingRemoteContextSummary) {
      this.pendingRemoteContextSummary = pendingRemoteContextSummary;
      return this;
    }

    public Builder lastResolvedSnapshotSummary(String lastResolvedSnapshotSummary) {
      this.lastResolvedSnapshotSummary = lastResolvedSnapshotSummary;
      return this;
    }

    public Builder lastProtocolLineSummary(String lastProtocolLineSummary) {
      this.lastProtocolLineSummary = lastProtocolLineSummary;
      return this;
    }

    public Builder lastProtocolTimestampMillis(long lastProtocolTimestampMillis) {
      this.lastProtocolTimestampMillis = lastProtocolTimestampMillis;
      return this;
    }

    public Builder latestDecisionTrace(SyncDecisionTrace latestDecisionTrace) {
      this.latestDecisionTrace = latestDecisionTrace;
      return this;
    }

    public Builder stateFlags(Map<String, String> stateFlags) {
      this.stateFlags = stateFlags;
      return this;
    }

    public SyncDiagnosticsSnapshot build() {
      return new SyncDiagnosticsSnapshot(this);
    }
  }
}
