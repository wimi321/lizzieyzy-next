package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BrowserFrameYikeSyncControlTest {
  @Test
  void yikeAutoSyncRequiresYikeBrowserSyncEnabled() {
    assertFalse(BrowserFrame.shouldAutoStartYikeSync(false, true));
    assertFalse(BrowserFrame.shouldAutoStartYikeSync(true, false));
    assertTrue(BrowserFrame.shouldAutoStartYikeSync(true, true));
  }

  @Test
  void yikePlatformSignalRequiresRecognizedRoomUrl() {
    assertFalse(
        BrowserFrame.shouldSyncYikeCurrentAddressFromPlatformSignal(
            true, false, "https://home.yikeweiqi.com/#/live", ""));
    assertFalse(
        BrowserFrame.shouldSyncYikeCurrentAddressFromPlatformSignal(
            true, false, "https://home.yikeweiqi.com/#/game", ""));
    assertTrue(
        BrowserFrame.shouldSyncYikeCurrentAddressFromPlatformSignal(
            true, false, "https://home.yikeweiqi.com/#/unite/66304678", ""));
  }

  @Test
  void yikePlatformSignalOnWaitingPageKeepsListenerButDoesNotStartRoomSync() {
    assertTrue(
        BrowserFrame.shouldKeepYikeListenerEnabledOnPage(
            true, "https://home.yikeweiqi.com/#/live"));
    assertTrue(
        BrowserFrame.shouldKeepYikeListenerEnabledOnPage(
            true, "https://home.yikeweiqi.com/#/game"));
    assertFalse(
        BrowserFrame.shouldCreateOrRefreshYikeRoomSession(
            true, true, "https://home.yikeweiqi.com/#/live", ""));
  }

  @Test
  void firstLiveRoomEntryCanBootstrapWithoutPriorUniteSession() {
    assertTrue(
        BrowserFrame.shouldCreateOrRefreshYikeRoomSession(
            true, true, "https://home.yikeweiqi.com/#/live/new-room/186538/0/0", ""));
  }

  @Test
  void sameRoomSignalDoesNotRebuildSession() {
    String url = "https://home.yikeweiqi.com/#/unite/66304678";

    assertFalse(BrowserFrame.shouldCreateOrRefreshYikeRoomSession(true, true, url, url));
  }

  @Test
  void yikePlatformSignalIsIdempotentForSameRecognizedRoom() {
    assertFalse(
        BrowserFrame.shouldSyncYikeCurrentAddressFromPlatformSignal(
            true,
            true,
            "https://home.yikeweiqi.com/#/unite/66304678",
            "https://home.yikeweiqi.com/#/unite/66304678"));
    assertTrue(
        BrowserFrame.shouldSyncYikeCurrentAddressFromPlatformSignal(
            true,
            true,
            "https://home.yikeweiqi.com/#/live/new-room/186530/0/0",
            "https://home.yikeweiqi.com/#/unite/66304678"));
  }

  @Test
  void yikeAutoSyncRequiresRecognizedRoomUrl() {
    assertFalse(BrowserFrame.shouldAutoStartYikeSyncForAddress(true, true, ""));
    assertFalse(
        BrowserFrame.shouldAutoStartYikeSyncForAddress(
            true, true, "https://home.yikeweiqi.com/#/live"));
    assertTrue(
        BrowserFrame.shouldAutoStartYikeSyncForAddress(
            true, true, "https://home.yikeweiqi.com/#/live/new-room/186530/0/0"));
    assertTrue(
        BrowserFrame.shouldAutoStartYikeSyncForAddress(
            true, true, "https://home.yikeweiqi.com/#/game/play/1/15630642"));
  }

  @Test
  void selectsObservedLiveRoomBeforeStaleBrowserUrl() {
    assertEquals(
        "https://home.yikeweiqi.com/#/live/new-room/186602/0/0",
        BrowserFrame.selectYikeCurrentAddress(
            "https://home.yikeweiqi.com/#/live/new-room/186602/0/0",
            "https://home.yikeweiqi.com/#/unite/66372511",
            "https://home.yikeweiqi.com/#/unite/66372511"));
  }

  @Test
  void fallsBackToBrowserOrAddressWhenObservedIsNotRoom() {
    assertEquals(
        "https://home.yikeweiqi.com/#/unite/66372511",
        BrowserFrame.selectYikeCurrentAddress(
            "https://home.yikeweiqi.com/#/live",
            "https://home.yikeweiqi.com/#/unite/66372511",
            "https://home.yikeweiqi.com/#/game"));
    assertEquals(
        "https://home.yikeweiqi.com/#/unite/66372511",
        BrowserFrame.selectYikeCurrentAddress(
            "",
            "https://home.yikeweiqi.com/#/game",
            "https://home.yikeweiqi.com/#/unite/66372511"));
  }
}
