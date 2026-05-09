package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OnlineDialogYikeSessionStateTest {
  @Test
  void pendingRoomDoesNotBecomeActiveUntilSyncAndGeometryAreBothReady() {
    OnlineDialog.YikeSessionState active =
        OnlineDialog.YikeSessionState.active("unite-board:66304678");
    OnlineDialog.YikeSessionState pending =
        OnlineDialog.YikeSessionState.pending("live-room:186538");

    pending = pending.withGeometryReady(true);
    assertFalse(OnlineDialog.shouldPromotePendingSession(active, pending));

    pending = pending.withSyncReady(true);
    assertTrue(OnlineDialog.shouldPromotePendingSession(active, pending));
  }

  @Test
  void waitingPageInvalidatesPlacementGeometryWithoutClearingDisplaySession() {
    assertTrue(
        OnlineDialog.shouldInvalidateYikePlacementGeometry("https://home.yikeweiqi.com/#/live"));
    assertTrue(
        OnlineDialog.shouldInvalidateYikePlacementGeometry("https://home.yikeweiqi.com/#/game"));
    assertFalse(
        OnlineDialog.shouldInvalidateYikePlacementGeometry(
            "https://home.yikeweiqi.com/#/unite/66304678"));
  }

  @Test
  void geometryFromDifferentSessionKeyIsRejected() {
    assertFalse(
        OnlineDialog.isGeometryForCurrentSession("live-room:186538", "unite-board:66304678"));
    assertTrue(OnlineDialog.isGeometryForCurrentSession("live-room:186538", "live-room:186538"));
  }

  @Test
  void waitingClearIsIgnoredWhenBrowserAlreadyOnBoardPage() {
    assertFalse(
        OnlineDialog.shouldApplyYikeClearEnvelope(
            "https://home.yikeweiqi.com/#/live",
            "https://home.yikeweiqi.com/#/live/new-room/186638/0/0",
            "https://home.yikeweiqi.com/#/live/new-room/186638/0/0",
            1000));
  }

  @Test
  void waitingClearIsIgnoredDuringRecentSyncBootstrapWindow() {
    assertFalse(
        OnlineDialog.shouldApplyYikeClearEnvelope(
            "https://home.yikeweiqi.com/#/live",
            "https://home.yikeweiqi.com/#/live",
            "https://home.yikeweiqi.com/#/live/new-room/186638/0/0",
            1200));
    assertTrue(
        OnlineDialog.shouldApplyYikeClearEnvelope(
            "https://home.yikeweiqi.com/#/live",
            "https://home.yikeweiqi.com/#/live",
            "https://home.yikeweiqi.com/#/live/new-room/186638/0/0",
            6000));
  }

  @Test
  void yikeRefreshUsesConfiguredSecondsWithoutFiveSecondFloor() {
    assertEquals(3, OnlineDialog.normalizeYikeRefreshSeconds(3));
    assertEquals(1, OnlineDialog.normalizeYikeRefreshSeconds(1));
    assertEquals(1, OnlineDialog.normalizeYikeRefreshSeconds(0));
  }

  @Test
  void staleLiveFetchResultIsRejectedAfterStopOrSessionChange() {
    assertFalse(OnlineDialog.shouldAcceptYikeLiveFetchResult(true, true, 6, 6, 2, 2));
    assertFalse(OnlineDialog.shouldAcceptYikeLiveFetchResult(false, false, 6, 6, 2, 2));
    assertFalse(OnlineDialog.shouldAcceptYikeLiveFetchResult(false, true, 7, 6, 2, 2));
    assertFalse(OnlineDialog.shouldAcceptYikeLiveFetchResult(false, true, 6, 6, 3, 2));
    assertTrue(OnlineDialog.shouldAcceptYikeLiveFetchResult(false, true, 6, 6, 2, 2));
  }
}
