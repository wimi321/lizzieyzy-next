package featurecat.lizzie.gui;

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
  void yikePlatformSignalStartsOnlyWhenNotAlreadyEnabled() {
    assertFalse(BrowserFrame.shouldStartYikeSyncFromPlatformSignal(false, false));
    assertTrue(BrowserFrame.shouldStartYikeSyncFromPlatformSignal(true, false));
    assertFalse(BrowserFrame.shouldStartYikeSyncFromPlatformSignal(true, true));
  }
}
