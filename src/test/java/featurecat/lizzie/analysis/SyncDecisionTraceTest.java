package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SyncDecisionTraceTest {
  @Test
  void formatsTheFourSnapshotRecoveryResultsWithStableFieldNames() {
    assertSummaryContains(SyncDecisionTrace.builder("NO_CHANGE", "snapshot_matches_current"));
    assertSummaryContains(
        SyncDecisionTrace.builder("SINGLE_MOVE_RECOVERY", "single_move_recovery"));
    assertSummaryContains(SyncDecisionTrace.builder("HOLD", "conflict_hold"));
    assertSummaryContains(
        SyncDecisionTrace.builder("FORCE_REBUILD", "force_rebuild_removed_stone"));
  }

  @Test
  void normalizesNullTextAndDefensivelyCopiesDiagnosticMaps() {
    Map<String, String> flags = new LinkedHashMap<>();
    flags.put("forceRebuildRequested", "true");
    Map<String, Integer> candidateStats = new LinkedHashMap<>();
    candidateStats.put("changedStoneCount", 3);

    SyncDecisionTrace trace =
        SyncDecisionTrace.builder(null, null)
            .platform(null)
            .windowKind(null)
            .source(null)
            .summary(null)
            .flags(flags)
            .candidateStats(candidateStats)
            .build();

    flags.put("forceRebuildRequested", "false");
    candidateStats.put("changedStoneCount", 9);

    assertEquals("UNKNOWN", trace.getResult());
    assertEquals("unknown_complete_snapshot_reason", trace.getReasonCode());
    assertEquals("unknown", trace.getPlatform());
    assertEquals("unknown", trace.getWindowKind());
    assertEquals("unknown", trace.getSource());
    assertEquals("", trace.getSummary());
    assertEquals("true", trace.getFlags().get("forceRebuildRequested"));
    assertEquals(3, trace.getCandidateStats().get("changedStoneCount"));
    assertThrows(UnsupportedOperationException.class, () -> trace.getFlags().put("x", "y"));
    assertThrows(UnsupportedOperationException.class, () -> trace.getCandidateStats().put("x", 1));
  }

  @Test
  void preservesTimestampSourceRemoteFingerprintAndDistinctMoveNumbers() {
    SyncDecisionTrace trace =
        SyncDecisionTrace.builder("HOLD", "conflict_hold")
            .platform("YIKE")
            .windowKind("LIVE_ROOM")
            .source("readboard")
            .summary("waiting for repeated conflict snapshot")
            .remoteContextFingerprint("ctx:8f3a2c11")
            .snapshotHash("8f3a2c11")
            .changedStoneCount(3)
            .removedStoneCount(0)
            .recoveryMoveNumber(40)
            .resolvedSnapshotMoveNumber(42)
            .resolvedSnapshotKind("SNAPSHOT")
            .firstSyncFrame(false)
            .shouldResumeAnalysis(false)
            .timestampMillis(1_780_147_922_000L)
            .epoch(7L)
            .build();

    assertEquals(1_780_147_922_000L, trace.getTimestampMillis());
    assertEquals(7L, trace.getEpoch());
    assertEquals("readboard", trace.getSource());
    assertEquals("ctx:8f3a2c11", trace.getRemoteContextFingerprint());
    assertEquals("8f3a2c11", trace.getSnapshotHash());
    assertEquals(40, trace.getRecoveryMoveNumber());
    assertEquals(42, trace.getResolvedSnapshotMoveNumber());
    assertEquals("SNAPSHOT", trace.getResolvedSnapshotKind());

    String text = trace.toSummaryText();
    assertTrue(text.contains("result: HOLD"));
    assertTrue(text.contains("reason: conflict_hold"));
    assertTrue(text.contains("source: readboard"));
    assertTrue(text.contains("remoteContextFingerprint: ctx:8f3a2c11"));
    assertFalse(text.contains("remoteSignature:"));
    assertTrue(text.contains("recoveryMoveNumber: 40"));
    assertTrue(text.contains("resolvedSnapshotMoveNumber: 42"));
    assertTrue(text.contains("changedStones: 3"));
    assertTrue(text.contains("removedStones: 0"));
  }

  @Test
  void defaultsRecoveryMoveNumberSeparatelyFromResolvedSnapshotMoveNumber() {
    SyncDecisionTrace trace =
        SyncDecisionTrace.builder("NO_CHANGE", "snapshot_matches_current")
            .resolvedSnapshotMoveNumber(123)
            .build();

    assertEquals(-1, trace.getRecoveryMoveNumber());
    assertEquals(123, trace.getResolvedSnapshotMoveNumber());
    assertTrue(trace.toSummaryText().contains("recoveryMoveNumber: -1"));
    assertTrue(trace.toSummaryText().contains("resolvedSnapshotMoveNumber: 123"));
  }

  @Test
  void snapshotAndReportUseNonNullEmptyPlaceholdersAndImmutableMaps() {
    Map<String, String> syncFlags = new LinkedHashMap<>();
    syncFlags.put("awaitingFirstSyncFrame", "true");

    SyncDiagnosticsSnapshot sync =
        SyncDiagnosticsSnapshot.builder()
            .readBoardAttached(true)
            .source("readboard")
            .latestDecisionTrace(null)
            .stateFlags(syncFlags)
            .build();
    syncFlags.put("awaitingFirstSyncFrame", "false");

    assertNotNull(sync.getLatestDecisionTrace());
    assertTrue(sync.getLatestDecisionTrace().isEmpty());
    assertEquals("true", sync.getStateFlags().get("awaitingFirstSyncFrame"));
    assertThrows(UnsupportedOperationException.class, () -> sync.getStateFlags().put("x", "y"));

    YikeSessionDiagnosticsSnapshot yike =
        YikeSessionDiagnosticsSnapshot.builder()
            .listenerEnabled(true)
            .currentRouteKind("live-room")
            .activeSessionKey("room-a")
            .activeSyncReady(false)
            .activeGeometryReady(true)
            .effectiveGeometrySessionKey("room-a")
            .placementGeometryAllowed(false)
            .timestampMillis(11L)
            .source("online-dialog")
            .build();

    SyncDiagnosticsReport report =
        SyncDiagnosticsReport.builder()
            .syncSnapshot(sync)
            .yikeSnapshot(yike)
            .latestDecisionTrace(null)
            .capturedAtMillis(12L)
            .source("recorder")
            .build();

    assertNotNull(report.getSyncSnapshot());
    assertNotNull(report.getYikeSnapshot());
    assertNotNull(report.getLatestDecisionTrace());
    assertTrue(report.getLatestDecisionTrace().isEmpty());
    assertEquals(12L, report.getCapturedAtMillis());
    assertEquals("recorder", report.getSource());
    assertTrue(report.toSummaryText().contains("Sync Diagnostics"));
    assertTrue(report.toSummaryText().contains("readboard:"));
    assertTrue(report.toSummaryText().contains("yike:"));
    assertTrue(report.toSummaryText().contains("Latest decision"));
  }

  @Test
  void hashSnapshotCodesIsStableAndDoesNotExposeFullBoard() {
    int[] snapshotCodes = {1, 2, 3, 4, 5, 6};

    String hash = SyncDecisionTrace.hashSnapshotCodes(snapshotCodes);
    snapshotCodes[0] = 99;

    assertEquals(hash, SyncDecisionTrace.hashSnapshotCodes(new int[] {1, 2, 3, 4, 5, 6}));
    assertEquals(8, hash.length());
    assertFalse(hash.equals(SyncDecisionTrace.hashSnapshotCodes(new int[] {6, 5, 4, 3, 2, 1})));
    assertFalse(hash.contains("1, 2, 3, 4, 5, 6"));
    assertEquals("none", SyncDecisionTrace.hashSnapshotCodes(null));
    assertEquals("none", SyncDecisionTrace.hashSnapshotCodes(new int[0]));
  }

  private static void assertSummaryContains(SyncDecisionTrace.Builder builder) {
    SyncDecisionTrace trace =
        builder
            .platform("YIKE")
            .windowKind("LIVE_ROOM")
            .changedStoneCount(3)
            .removedStoneCount(0)
            .snapshotHash("8f3a2c11")
            .timestampMillis(1_780_147_922_000L)
            .source("readboard")
            .build();

    String text = trace.toSummaryText();

    assertTrue(text.contains("result: " + trace.getResult()));
    assertTrue(text.contains("reason: " + trace.getReasonCode()));
    assertTrue(text.contains("platform: YIKE"));
    assertTrue(text.contains("windowKind: LIVE_ROOM"));
    assertTrue(text.contains("changedStones: 3"));
    assertTrue(text.contains("removedStones: 0"));
    assertTrue(text.contains("timestampMillis: 1780147922000"));
  }
}
