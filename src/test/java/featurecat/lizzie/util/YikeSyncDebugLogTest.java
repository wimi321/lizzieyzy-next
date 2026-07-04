package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class YikeSyncDebugLogTest {
  @Test
  void geometryProbeDebugEnablesSharedYikeSyncLog() {
    assertFalse(YikeSyncDebugLog.isEnabledByProperties("false", "false"));
    assertTrue(YikeSyncDebugLog.isEnabledByProperties("true", "false"));
    assertTrue(YikeSyncDebugLog.isEnabledByProperties("false", "true"));
  }
}
