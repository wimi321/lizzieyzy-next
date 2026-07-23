package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LeelazOpenClRecoveryTest {
  @Test
  void automaticRestartWaitsForTheFullStartupCommandSequence() {
    assertFalse(Leelaz.automaticRestartReady(false, false, true));
    assertFalse(Leelaz.automaticRestartReady(true, true, true));
    assertFalse(Leelaz.automaticRestartReady(true, false, false));
    assertTrue(Leelaz.automaticRestartReady(true, false, true));
  }
}
