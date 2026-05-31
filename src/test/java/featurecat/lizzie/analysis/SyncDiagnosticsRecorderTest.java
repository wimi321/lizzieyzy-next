package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
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

  @Test
  void exportSnapshotIncludesRecentProtocolDecisionAndYikeEvents() {
    SyncDiagnosticsRecorder recorder = new SyncDiagnosticsRecorder();
    SyncDecisionTrace trace =
        SyncDecisionTrace.builder("HOLD", "conflict_hold").timestampMillis(100L).build();
    YikeSessionDiagnosticsSnapshot yike =
        YikeSessionDiagnosticsSnapshot.builder()
            .currentSessionKey("live-room:186538")
            .summary("session event")
            .build();

    recorder.recordProtocolEvent(SyncProtocolDiagnosticEvent.of(10L, "syncPlatform yike", "test"));
    recorder.updateLatestDecision(trace);
    recorder.updateYikeSession(yike);

    SyncDiagnosticsExportSnapshot snapshot = recorder.exportSnapshot();

    assertEquals(1, snapshot.getRecentProtocolEvents().size());
    assertSame(trace, snapshot.getRecentDecisionTraces().get(0));
    assertSame(yike, snapshot.getRecentYikeEvents().get(0));
  }

  @Test
  void decisionTraceBufferKeepsOnlyNewestValuesWhenCapacityIsExceeded() {
    SyncDiagnosticsRecorder recorder = new SyncDiagnosticsRecorder();
    int eventCount = SyncDiagnosticsRecorder.DECISION_TRACE_CAPACITY + 3;

    for (int i = 0; i < eventCount; i++) {
      recorder.updateLatestDecision(
          SyncDecisionTrace.builder("HOLD", "conflict_hold").timestampMillis(i).build());
    }

    SyncDiagnosticsExportSnapshot snapshot = recorder.exportSnapshot();

    assertEquals(
        SyncDiagnosticsRecorder.DECISION_TRACE_CAPACITY, snapshot.getRecentDecisionTraces().size());
    assertEquals(3L, snapshot.getRecentDecisionTraces().get(0).getTimestampMillis());
    assertEquals(
        eventCount - 1L,
        snapshot
            .getRecentDecisionTraces()
            .get(snapshot.getRecentDecisionTraces().size() - 1)
            .getTimestampMillis());
  }

  @Test
  void clearForTestsClearsLatestAndRecentBuffers() {
    SyncDiagnosticsRecorder recorder = new SyncDiagnosticsRecorder();
    recorder.recordProtocolEvent(SyncProtocolDiagnosticEvent.of(10L, "sync", "test"));
    recorder.updateLatestDecision(SyncDecisionTrace.builder("HOLD", "conflict_hold").build());
    recorder.updateYikeSession(
        YikeSessionDiagnosticsSnapshot.builder().currentSessionKey("live-room:1").build());

    recorder.clearForTests();

    SyncDiagnosticsExportSnapshot export = recorder.exportSnapshot();
    assertTrue(export.getRecentProtocolEvents().isEmpty());
    assertTrue(export.getRecentDecisionTraces().isEmpty());
    assertTrue(export.getRecentYikeEvents().isEmpty());
    assertTrue(recorder.snapshot().getLatestDecisionTrace().isEmpty());
  }

  @Test
  void clearDefaultForTestsClearsSingletonBuffersAcrossPackages() {
    SyncDiagnosticsRecorder recorder = SyncDiagnosticsRecorder.getDefault();
    recorder.recordProtocolEvent(SyncProtocolDiagnosticEvent.of(10L, "sync", "test"));
    recorder.updateLatestDecision(SyncDecisionTrace.builder("HOLD", "conflict_hold").build());
    recorder.updateYikeSession(
        YikeSessionDiagnosticsSnapshot.builder().currentSessionKey("live-room:1").build());

    SyncDiagnosticsRecorder.clearDefaultForTests();

    SyncDiagnosticsExportSnapshot export = recorder.exportSnapshot();
    assertTrue(export.getRecentProtocolEvents().isEmpty());
    assertTrue(export.getRecentDecisionTraces().isEmpty());
    assertTrue(export.getRecentYikeEvents().isEmpty());
    assertTrue(recorder.snapshot().getLatestDecisionTrace().isEmpty());
  }
}
