package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReadBoardYikeSyncControlTest {
  @Test
  void recognizesYikeSyncStartCommand() {
    assertTrue(ReadBoard.isYikeSyncStartCommand("yikeSyncStart"));
    assertFalse(ReadBoard.isYikeSyncStartCommand("sync"));
  }

  @Test
  void recognizesYikeSyncStopCommand() {
    assertTrue(ReadBoard.isYikeSyncStopCommand("yikeSyncStop"));
    assertFalse(ReadBoard.isYikeSyncStopCommand("stopsync"));
  }

  @Test
  void recognizesYikeSyncPlatformLine() {
    assertTrue(ReadBoard.isYikeSyncPlatformLine("syncPlatform yike"));
    assertTrue(ReadBoard.isYikeSyncPlatformLine(" syncPlatform YIKE "));
    assertFalse(ReadBoard.isYikeSyncPlatformLine("syncPlatform fox"));
  }
}
