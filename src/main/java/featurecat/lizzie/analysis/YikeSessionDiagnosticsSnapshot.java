package featurecat.lizzie.analysis;

public final class YikeSessionDiagnosticsSnapshot {
  private static final YikeSessionDiagnosticsSnapshot EMPTY =
      YikeSessionDiagnosticsSnapshot.builder().source("empty").summary("not captured").build();

  private final Boolean listenerEnabled;
  private final String currentRouteKind;
  private final String currentSessionKey;
  private final String activeSessionKey;
  private final Boolean activeSyncReady;
  private final Boolean activeGeometryReady;
  private final int activeBoardSize;
  private final String pendingSessionKey;
  private final Boolean pendingSyncReady;
  private final Boolean pendingGeometryReady;
  private final int pendingBoardSize;
  private final String effectiveGeometrySessionKey;
  private final Boolean effectiveGeometryReady;
  private final Boolean placementGeometryAllowed;
  private final String lastGeometryClearReason;
  private final String lastSessionSwitchReason;
  private final String lastYikeDebugEventSummary;
  private final long timestampMillis;
  private final String source;
  private final String summary;

  private YikeSessionDiagnosticsSnapshot(Builder builder) {
    this.listenerEnabled = builder.listenerEnabled;
    this.currentRouteKind = SyncDecisionTrace.normalize(builder.currentRouteKind, "unknown");
    this.currentSessionKey = SyncDecisionTrace.normalize(builder.currentSessionKey, "none");
    this.activeSessionKey = SyncDecisionTrace.normalize(builder.activeSessionKey, "none");
    this.activeSyncReady = builder.activeSyncReady;
    this.activeGeometryReady = builder.activeGeometryReady;
    this.activeBoardSize = builder.activeBoardSize;
    this.pendingSessionKey = SyncDecisionTrace.normalize(builder.pendingSessionKey, "none");
    this.pendingSyncReady = builder.pendingSyncReady;
    this.pendingGeometryReady = builder.pendingGeometryReady;
    this.pendingBoardSize = builder.pendingBoardSize;
    this.effectiveGeometrySessionKey =
        SyncDecisionTrace.normalize(builder.effectiveGeometrySessionKey, "none");
    this.effectiveGeometryReady = builder.effectiveGeometryReady;
    this.placementGeometryAllowed = builder.placementGeometryAllowed;
    this.lastGeometryClearReason =
        SyncDecisionTrace.normalize(builder.lastGeometryClearReason, "none");
    this.lastSessionSwitchReason =
        SyncDecisionTrace.normalize(builder.lastSessionSwitchReason, "none");
    this.lastYikeDebugEventSummary =
        SyncDecisionTrace.normalize(builder.lastYikeDebugEventSummary, "none");
    this.timestampMillis = builder.timestampMillis;
    this.source = SyncDecisionTrace.normalize(builder.source, "unknown");
    this.summary = SyncDecisionTrace.normalize(builder.summary, "not captured");
  }

  public static YikeSessionDiagnosticsSnapshot empty() {
    return EMPTY;
  }

  public static Builder builder() {
    return new Builder();
  }

  public Boolean getListenerEnabled() {
    return listenerEnabled;
  }

  public String getCurrentRouteKind() {
    return currentRouteKind;
  }

  public String getCurrentSessionKey() {
    return currentSessionKey;
  }

  public String getActiveSessionKey() {
    return activeSessionKey;
  }

  public Boolean getActiveSyncReady() {
    return activeSyncReady;
  }

  public Boolean getActiveGeometryReady() {
    return activeGeometryReady;
  }

  public int getActiveBoardSize() {
    return activeBoardSize;
  }

  public String getPendingSessionKey() {
    return pendingSessionKey;
  }

  public Boolean getPendingSyncReady() {
    return pendingSyncReady;
  }

  public Boolean getPendingGeometryReady() {
    return pendingGeometryReady;
  }

  public int getPendingBoardSize() {
    return pendingBoardSize;
  }

  public String getEffectiveGeometrySessionKey() {
    return effectiveGeometrySessionKey;
  }

  public Boolean getEffectiveGeometryReady() {
    return effectiveGeometryReady;
  }

  public Boolean getPlacementGeometryAllowed() {
    return placementGeometryAllowed;
  }

  public String getLastGeometryClearReason() {
    return lastGeometryClearReason;
  }

  public String getLastSessionSwitchReason() {
    return lastSessionSwitchReason;
  }

  public String getLastYikeDebugEventSummary() {
    return lastYikeDebugEventSummary;
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

  public String toSummaryText() {
    StringBuilder text = new StringBuilder();
    text.append("yike:\n");
    text.append("  listenerEnabled: ").append(readinessText(listenerEnabled)).append('\n');
    text.append("  currentRouteKind: ").append(currentRouteKind).append('\n');
    text.append("  currentSession: ").append(currentSessionKey).append('\n');
    text.append("  active: ")
        .append(activeSessionKey)
        .append(" syncReady=")
        .append(readinessText(activeSyncReady))
        .append(" geometryReady=")
        .append(readinessText(activeGeometryReady))
        .append(" boardSize=")
        .append(activeBoardSize)
        .append('\n');
    text.append("  pending: ")
        .append(pendingSessionKey)
        .append(" syncReady=")
        .append(readinessText(pendingSyncReady))
        .append(" geometryReady=")
        .append(readinessText(pendingGeometryReady))
        .append(" boardSize=")
        .append(pendingBoardSize)
        .append('\n');
    text.append("  effectiveGeometry: ")
        .append(effectiveGeometrySessionKey)
        .append(" ready=")
        .append(readinessText(effectiveGeometryReady))
        .append('\n');
    text.append("  placementGeometryAllowed: ")
        .append(readinessText(placementGeometryAllowed))
        .append('\n');
    text.append("  lastGeometryClearReason: ").append(lastGeometryClearReason).append('\n');
    text.append("  lastSessionSwitchReason: ").append(lastSessionSwitchReason).append('\n');
    text.append("  lastYikeDebugEventSummary: ").append(lastYikeDebugEventSummary).append('\n');
    text.append("  source: ").append(source).append('\n');
    text.append("  timestampMillis: ").append(timestampMillis).append('\n');
    text.append("  summary: ").append(summary);
    return text.toString();
  }

  private static String readinessText(Boolean value) {
    return value == null ? "unknown" : value.toString();
  }

  public static final class Builder {
    private Boolean listenerEnabled;
    private String currentRouteKind;
    private String currentSessionKey;
    private String activeSessionKey;
    private Boolean activeSyncReady;
    private Boolean activeGeometryReady;
    private int activeBoardSize;
    private String pendingSessionKey;
    private Boolean pendingSyncReady;
    private Boolean pendingGeometryReady;
    private int pendingBoardSize;
    private String effectiveGeometrySessionKey;
    private Boolean effectiveGeometryReady;
    private Boolean placementGeometryAllowed;
    private String lastGeometryClearReason;
    private String lastSessionSwitchReason;
    private String lastYikeDebugEventSummary;
    private long timestampMillis;
    private String source;
    private String summary;

    public Builder listenerEnabled(Boolean listenerEnabled) {
      this.listenerEnabled = listenerEnabled;
      return this;
    }

    public Builder currentRouteKind(String currentRouteKind) {
      this.currentRouteKind = currentRouteKind;
      return this;
    }

    public Builder currentSessionKey(String currentSessionKey) {
      this.currentSessionKey = currentSessionKey;
      return this;
    }

    public Builder activeSessionKey(String activeSessionKey) {
      this.activeSessionKey = activeSessionKey;
      return this;
    }

    public Builder activeSyncReady(Boolean activeSyncReady) {
      this.activeSyncReady = activeSyncReady;
      return this;
    }

    public Builder activeGeometryReady(Boolean activeGeometryReady) {
      this.activeGeometryReady = activeGeometryReady;
      return this;
    }

    public Builder activeBoardSize(int activeBoardSize) {
      this.activeBoardSize = activeBoardSize;
      return this;
    }

    public Builder pendingSessionKey(String pendingSessionKey) {
      this.pendingSessionKey = pendingSessionKey;
      return this;
    }

    public Builder pendingSyncReady(Boolean pendingSyncReady) {
      this.pendingSyncReady = pendingSyncReady;
      return this;
    }

    public Builder pendingGeometryReady(Boolean pendingGeometryReady) {
      this.pendingGeometryReady = pendingGeometryReady;
      return this;
    }

    public Builder pendingBoardSize(int pendingBoardSize) {
      this.pendingBoardSize = pendingBoardSize;
      return this;
    }

    public Builder effectiveGeometrySessionKey(String effectiveGeometrySessionKey) {
      this.effectiveGeometrySessionKey = effectiveGeometrySessionKey;
      return this;
    }

    public Builder effectiveGeometryReady(Boolean effectiveGeometryReady) {
      this.effectiveGeometryReady = effectiveGeometryReady;
      return this;
    }

    public Builder placementGeometryAllowed(Boolean placementGeometryAllowed) {
      this.placementGeometryAllowed = placementGeometryAllowed;
      return this;
    }

    public Builder lastGeometryClearReason(String lastGeometryClearReason) {
      this.lastGeometryClearReason = lastGeometryClearReason;
      return this;
    }

    public Builder lastSessionSwitchReason(String lastSessionSwitchReason) {
      this.lastSessionSwitchReason = lastSessionSwitchReason;
      return this;
    }

    public Builder lastYikeDebugEventSummary(String lastYikeDebugEventSummary) {
      this.lastYikeDebugEventSummary = lastYikeDebugEventSummary;
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

    public YikeSessionDiagnosticsSnapshot build() {
      return new YikeSessionDiagnosticsSnapshot(this);
    }
  }
}
