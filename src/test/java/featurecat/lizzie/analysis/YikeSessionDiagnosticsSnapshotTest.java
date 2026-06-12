package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class YikeSessionDiagnosticsSnapshotTest {
  @Test
  void emptySnapshotKeepsReadinessUnknownInsteadOfFalse() {
    YikeSessionDiagnosticsSnapshot snapshot = YikeSessionDiagnosticsSnapshot.empty();

    assertNull(snapshot.getListenerEnabled());
    assertNull(snapshot.getActiveSyncReady());
    assertNull(snapshot.getActiveGeometryReady());
    assertNull(snapshot.getPendingSyncReady());
    assertNull(snapshot.getPendingGeometryReady());
    assertNull(snapshot.getEffectiveGeometryReady());
    assertNull(snapshot.getPlacementGeometryAllowed());

    String text = snapshot.toSummaryText();
    assertTrue(text.contains("listenerEnabled: unknown"));
    assertTrue(text.contains("active: none syncReady=unknown geometryReady=unknown"));
    assertTrue(text.contains("pending: none syncReady=unknown geometryReady=unknown"));
    assertTrue(text.contains("placementGeometryAllowed: unknown"));
    assertTrue(text.contains("summary: not captured"));
  }

  @Test
  void summaryShowsPendingGeometryReadyBeforeSyncReadyAndPlacementDenied() {
    YikeSessionDiagnosticsSnapshot snapshot =
        YikeSessionDiagnosticsSnapshot.builder()
            .listenerEnabled(true)
            .currentRouteKind("live-room")
            .currentSessionKey("room-current")
            .activeSessionKey("room-active")
            .activeSyncReady(true)
            .activeGeometryReady(true)
            .activeBoardSize(19)
            .pendingSessionKey("room-pending")
            .pendingSyncReady(false)
            .pendingGeometryReady(true)
            .pendingBoardSize(19)
            .effectiveGeometrySessionKey("room-pending")
            .effectiveGeometryReady(true)
            .placementGeometryAllowed(false)
            .lastGeometryClearReason("route-change")
            .lastSessionSwitchReason("pending-not-sync-ready")
            .lastYikeDebugEventSummary("geometry-captured")
            .timestampMillis(22L)
            .source("online-dialog")
            .build();

    assertEquals(Boolean.FALSE, snapshot.getPendingSyncReady());
    assertEquals(Boolean.TRUE, snapshot.getPendingGeometryReady());
    assertEquals(Boolean.FALSE, snapshot.getPlacementGeometryAllowed());

    String text = snapshot.toSummaryText();
    assertTrue(text.contains("active: room-active syncReady=true geometryReady=true"));
    assertTrue(text.contains("pending: room-pending syncReady=false geometryReady=true"));
    assertTrue(text.contains("effectiveGeometry: room-pending ready=true"));
    assertTrue(text.contains("placementGeometryAllowed: false"));
    assertTrue(text.contains("lastSessionSwitchReason: pending-not-sync-ready"));
    assertTrue(text.contains("lastYikeDebugEventSummary: geometry-captured"));
    assertTrue(!text.contains("lastYikeDebugEvent:"));
  }
}
