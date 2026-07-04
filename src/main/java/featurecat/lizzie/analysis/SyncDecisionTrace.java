package featurecat.lizzie.analysis;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SyncDecisionTrace {
  private static final SyncDecisionTrace EMPTY =
      SyncDecisionTrace.builder("UNKNOWN", "unknown_complete_snapshot_reason")
          .source("empty")
          .summary("not captured")
          .build();

  private final String result;
  private final String reasonCode;
  private final String summary;
  private final String platform;
  private final String windowKind;
  private final String source;
  private final String remoteContextFingerprint;
  private final String snapshotHash;
  private final int changedStoneCount;
  private final int removedStoneCount;
  private final int recoveryMoveNumber;
  private final int resolvedSnapshotMoveNumber;
  private final String resolvedSnapshotKind;
  private final boolean forceRebuildRequested;
  private final boolean firstSyncFrame;
  private final boolean shouldResumeAnalysis;
  private final long timestampMillis;
  private final long epoch;
  private final Map<String, String> flags;
  private final Map<String, Integer> candidateStats;

  private SyncDecisionTrace(Builder builder) {
    this.result = normalize(builder.result, "UNKNOWN");
    this.reasonCode = normalize(builder.reasonCode, "unknown_complete_snapshot_reason");
    this.summary = normalize(builder.summary, "");
    this.platform = normalize(builder.platform, "unknown");
    this.windowKind = normalize(builder.windowKind, "unknown");
    this.source = normalize(builder.source, "unknown");
    this.remoteContextFingerprint = normalize(builder.remoteContextFingerprint, "unknown");
    this.snapshotHash = normalize(builder.snapshotHash, "none");
    this.changedStoneCount = builder.changedStoneCount;
    this.removedStoneCount = builder.removedStoneCount;
    this.recoveryMoveNumber = builder.recoveryMoveNumber;
    this.resolvedSnapshotMoveNumber = builder.resolvedSnapshotMoveNumber;
    this.resolvedSnapshotKind = normalize(builder.resolvedSnapshotKind, "unknown");
    this.forceRebuildRequested = builder.forceRebuildRequested;
    this.firstSyncFrame = builder.firstSyncFrame;
    this.shouldResumeAnalysis = builder.shouldResumeAnalysis;
    this.timestampMillis = builder.timestampMillis;
    this.epoch = builder.epoch;
    this.flags = copyStringMap(builder.flags);
    this.candidateStats = copyIntegerMap(builder.candidateStats);
  }

  public static SyncDecisionTrace empty() {
    return EMPTY;
  }

  public static Builder builder(String result, String reasonCode) {
    return new Builder(result, reasonCode);
  }

  public boolean isEmpty() {
    return this == EMPTY || ("UNKNOWN".equals(result) && "empty".equals(source));
  }

  public String getResult() {
    return result;
  }

  public String getReasonCode() {
    return reasonCode;
  }

  public String getSummary() {
    return summary;
  }

  public String getPlatform() {
    return platform;
  }

  public String getWindowKind() {
    return windowKind;
  }

  public String getSource() {
    return source;
  }

  public String getRemoteContextFingerprint() {
    return remoteContextFingerprint;
  }

  public String getSnapshotHash() {
    return snapshotHash;
  }

  public int getChangedStoneCount() {
    return changedStoneCount;
  }

  public int getRemovedStoneCount() {
    return removedStoneCount;
  }

  public int getRecoveryMoveNumber() {
    return recoveryMoveNumber;
  }

  public int getResolvedSnapshotMoveNumber() {
    return resolvedSnapshotMoveNumber;
  }

  public String getResolvedSnapshotKind() {
    return resolvedSnapshotKind;
  }

  public boolean isForceRebuildRequested() {
    return forceRebuildRequested;
  }

  public boolean isFirstSyncFrame() {
    return firstSyncFrame;
  }

  public boolean shouldResumeAnalysis() {
    return shouldResumeAnalysis;
  }

  public long getTimestampMillis() {
    return timestampMillis;
  }

  public long getEpoch() {
    return epoch;
  }

  public Map<String, String> getFlags() {
    return flags;
  }

  public Map<String, Integer> getCandidateStats() {
    return candidateStats;
  }

  public String toSummaryText() {
    StringBuilder text = new StringBuilder();
    text.append("result: ").append(result).append('\n');
    text.append("reason: ").append(reasonCode).append('\n');
    text.append("platform: ").append(platform).append('\n');
    text.append("windowKind: ").append(windowKind).append('\n');
    text.append("source: ").append(source).append('\n');
    text.append("summary: ").append(summary).append('\n');
    text.append("remoteContextFingerprint: ").append(remoteContextFingerprint).append('\n');
    text.append("snapshotHash: ").append(snapshotHash).append('\n');
    text.append("changedStones: ").append(changedStoneCount).append('\n');
    text.append("removedStones: ").append(removedStoneCount).append('\n');
    text.append("recoveryMoveNumber: ").append(recoveryMoveNumber).append('\n');
    text.append("resolvedSnapshotMoveNumber: ").append(resolvedSnapshotMoveNumber).append('\n');
    text.append("resolvedSnapshotKind: ").append(resolvedSnapshotKind).append('\n');
    text.append("forceRebuildRequested: ").append(forceRebuildRequested).append('\n');
    text.append("firstSyncFrame: ").append(firstSyncFrame).append('\n');
    text.append("shouldResumeAnalysis: ").append(shouldResumeAnalysis).append('\n');
    text.append("epoch: ").append(epoch).append('\n');
    text.append("timestampMillis: ").append(timestampMillis);
    return text.toString();
  }

  public static String hashSnapshotCodes(int[] snapshotCodes) {
    if (snapshotCodes == null || snapshotCodes.length == 0) {
      return "none";
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
      for (int code : snapshotCodes) {
        buffer.clear();
        buffer.putInt(code);
        digest.update(buffer.array());
      }
      byte[] hash = digest.digest();
      StringBuilder hex = new StringBuilder();
      for (int i = 0; i < 4; i++) {
        hex.append(String.format("%02x", hash[i] & 0xff));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  public static String normalize(String value, String fallback) {
    if (value == null || value.trim().isEmpty()) {
      return fallback;
    }
    return value;
  }

  static Map<String, String> copyStringMap(Map<String, String> values) {
    if (values == null || values.isEmpty()) {
      return Collections.emptyMap();
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  private static Map<String, Integer> copyIntegerMap(Map<String, Integer> values) {
    if (values == null || values.isEmpty()) {
      return Collections.emptyMap();
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  public static final class Builder {
    private final String result;
    private final String reasonCode;
    private String summary;
    private String platform;
    private String windowKind;
    private String source;
    private String remoteContextFingerprint;
    private String snapshotHash;
    private int changedStoneCount;
    private int removedStoneCount;
    private int recoveryMoveNumber = -1;
    private int resolvedSnapshotMoveNumber = -1;
    private String resolvedSnapshotKind;
    private boolean forceRebuildRequested;
    private boolean firstSyncFrame;
    private boolean shouldResumeAnalysis;
    private long timestampMillis;
    private long epoch;
    private Map<String, String> flags;
    private Map<String, Integer> candidateStats;

    private Builder(String result, String reasonCode) {
      this.result = result;
      this.reasonCode = reasonCode;
    }

    public Builder summary(String summary) {
      this.summary = summary;
      return this;
    }

    public Builder platform(String platform) {
      this.platform = platform;
      return this;
    }

    public Builder windowKind(String windowKind) {
      this.windowKind = windowKind;
      return this;
    }

    public Builder source(String source) {
      this.source = source;
      return this;
    }

    public Builder remoteContextFingerprint(String remoteContextFingerprint) {
      this.remoteContextFingerprint = remoteContextFingerprint;
      return this;
    }

    public Builder snapshotHash(String snapshotHash) {
      this.snapshotHash = snapshotHash;
      return this;
    }

    public Builder changedStoneCount(int changedStoneCount) {
      this.changedStoneCount = changedStoneCount;
      return this;
    }

    public Builder removedStoneCount(int removedStoneCount) {
      this.removedStoneCount = removedStoneCount;
      return this;
    }

    public Builder recoveryMoveNumber(int recoveryMoveNumber) {
      this.recoveryMoveNumber = recoveryMoveNumber;
      return this;
    }

    public Builder resolvedSnapshotMoveNumber(int resolvedSnapshotMoveNumber) {
      this.resolvedSnapshotMoveNumber = resolvedSnapshotMoveNumber;
      return this;
    }

    public Builder resolvedSnapshotKind(String resolvedSnapshotKind) {
      this.resolvedSnapshotKind = resolvedSnapshotKind;
      return this;
    }

    public Builder forceRebuildRequested(boolean forceRebuildRequested) {
      this.forceRebuildRequested = forceRebuildRequested;
      return this;
    }

    public Builder firstSyncFrame(boolean firstSyncFrame) {
      this.firstSyncFrame = firstSyncFrame;
      return this;
    }

    public Builder shouldResumeAnalysis(boolean shouldResumeAnalysis) {
      this.shouldResumeAnalysis = shouldResumeAnalysis;
      return this;
    }

    public Builder timestampMillis(long timestampMillis) {
      this.timestampMillis = timestampMillis;
      return this;
    }

    public Builder epoch(long epoch) {
      this.epoch = epoch;
      return this;
    }

    public Builder flags(Map<String, String> flags) {
      this.flags = flags;
      return this;
    }

    public Builder candidateStats(Map<String, Integer> candidateStats) {
      this.candidateStats = candidateStats;
      return this;
    }

    public SyncDecisionTrace build() {
      return new SyncDecisionTrace(this);
    }
  }
}
