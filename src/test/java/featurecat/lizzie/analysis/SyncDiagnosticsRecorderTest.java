package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SyncDiagnosticsRecorderTest {
  @Test
  void emptyRecorderFormatsUsableSummary() {
    SyncDiagnosticsRecorder recorder = new SyncDiagnosticsRecorder();

    String summary = recorder.snapshot().toSummaryText();

    assertTrue(summary.contains("Sync Diagnostics"));
    assertTrue(summary.contains("readboard:"));
    assertTrue(summary.contains("Latest decision"));
  }

  @Test
  void updatingDecisionDoesNotClearYikeSnapshot() {
    SyncDiagnosticsRecorder recorder = new SyncDiagnosticsRecorder();
    recorder.updateYikeSession(
        YikeSessionDiagnosticsSnapshot.builder()
            .currentRouteKind("live-room")
            .activeSessionKey("live-room:1")
            .activeSyncReady(true)
            .activeGeometryReady(true)
            .build());

    recorder.updateLatestDecision(SyncDecisionTrace.builder("HOLD", "conflict_hold").build());

    String summary = recorder.snapshot().toSummaryText();
    assertTrue(summary.contains("active: live-room:1"));
    assertTrue(summary.contains("result: HOLD"));
  }

  @Test
  void combinedSummaryRendersReadBoardYikeAndLatestDecisionSections() {
    SyncDiagnosticsRecorder recorder = new SyncDiagnosticsRecorder();
    recorder.updateSync(
        SyncDiagnosticsSnapshot.builder()
            .readBoardAttached(true)
            .readBoardConnected(true)
            .summary("readboard ready")
            .build());
    recorder.updateYikeSession(
        YikeSessionDiagnosticsSnapshot.builder()
            .currentRouteKind("live-room")
            .activeSessionKey("live-room:1")
            .build());
    recorder.updateLatestDecision(SyncDecisionTrace.builder("HOLD", "conflict_hold").build());

    String summary = recorder.snapshot().toSummaryText();

    assertTrue(summary.contains("Sync Diagnostics"));
    assertTrue(summary.contains("\nReadBoard\n"));
    assertTrue(summary.contains("\nYike\n"));
    assertTrue(summary.contains("Latest decision"));
  }

  @Test
  void updatingSyncDoesNotClearLatestDecisionOrYikeSnapshot() {
    SyncDiagnosticsRecorder recorder = new SyncDiagnosticsRecorder();
    recorder.updateYikeSession(
        YikeSessionDiagnosticsSnapshot.builder().activeSessionKey("live-room:2").build());
    recorder.updateLatestDecision(
        SyncDecisionTrace.builder("FORCE_REBUILD", "force_rebuild_multi_step").build());

    recorder.updateSync(
        SyncDiagnosticsSnapshot.builder()
            .readBoardAttached(true)
            .readBoardConnected(true)
            .summary("readboard connected")
            .build());

    SyncDiagnosticsReport report = recorder.snapshot();
    assertTrue(report.toSummaryText().contains("connected: true"));
    assertTrue(report.toSummaryText().contains("active: live-room:2"));
    assertEquals("FORCE_REBUILD", report.getLatestDecisionTrace().getResult());
  }

  @Test
  void nullUpdatesRestoreEmptyPlaceholdersInsteadOfNulls() {
    SyncDiagnosticsRecorder recorder = new SyncDiagnosticsRecorder();
    recorder.updateSync(SyncDiagnosticsSnapshot.builder().readBoardAttached(true).build());
    recorder.updateYikeSession(
        YikeSessionDiagnosticsSnapshot.builder().activeSessionKey("live-room:3").build());
    recorder.updateLatestDecision(SyncDecisionTrace.builder("NO_CHANGE", "matched").build());

    recorder.updateSync(null);
    recorder.updateYikeSession(null);
    recorder.updateLatestDecision(null);

    SyncDiagnosticsReport report = recorder.snapshot();
    assertNotNull(report.getSyncSnapshot());
    assertNotNull(report.getYikeSnapshot());
    assertNotNull(report.getLatestDecisionTrace());
    assertTrue(report.getLatestDecisionTrace().isEmpty());
    assertTrue(report.toSummaryText().contains("summary: not captured"));
    assertTrue(report.toSummaryText().contains("active: none"));
  }

  @Test
  void snapshotCombinesValuesAndDoesNotChangeAfterLaterUpdates() {
    SyncDiagnosticsRecorder recorder = new SyncDiagnosticsRecorder();
    SyncDiagnosticsSnapshot sync =
        SyncDiagnosticsSnapshot.builder().summary("sync before").source("readboard").build();
    YikeSessionDiagnosticsSnapshot yike =
        YikeSessionDiagnosticsSnapshot.builder().activeSessionKey("session-before").build();
    SyncDecisionTrace decision =
        SyncDecisionTrace.builder("HOLD", "conflict_hold").summary("decision before").build();
    recorder.updateSync(sync);
    recorder.updateYikeSession(yike);
    recorder.updateLatestDecision(decision);

    SyncDiagnosticsReport before = recorder.snapshot();

    recorder.updateSync(SyncDiagnosticsSnapshot.builder().summary("sync after").build());
    recorder.updateYikeSession(
        YikeSessionDiagnosticsSnapshot.builder().activeSessionKey("session-after").build());
    recorder.updateLatestDecision(
        SyncDecisionTrace.builder("NO_CHANGE", "snapshot_matches_current").build());

    assertEquals(sync, before.getSyncSnapshot());
    assertEquals(yike, before.getYikeSnapshot());
    assertEquals(decision, before.getLatestDecisionTrace());
    assertTrue(before.toSummaryText().contains("sync before"));
    assertTrue(before.toSummaryText().contains("session-before"));
    assertTrue(before.toSummaryText().contains("result: HOLD"));
    assertNotSame(before, recorder.snapshot());
  }
}
