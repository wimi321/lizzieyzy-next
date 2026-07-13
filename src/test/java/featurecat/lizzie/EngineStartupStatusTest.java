package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineStartupStatusTest {
  @Test
  void listenersReceiveCurrentAndFutureStates() {
    EngineStartupStatus status = new EngineStartupStatus();
    List<EngineStartupStatus.Snapshot> updates = new ArrayList<>();

    status.addListener(updates::add);
    status.checking("checking", "Checking");
    status.needsRepair("repair", "Repair", "Missing engine");
    status.failed("failed", "Failed", "Process error");
    status.ready();

    assertEquals(5, updates.size());
    assertEquals(EngineStartupStatus.State.READY, updates.get(0).state);
    assertEquals(EngineStartupStatus.State.CHECKING, updates.get(1).state);
    assertFalse(updates.get(1).isActionable());
    assertTrue(updates.get(2).isActionable());
    assertTrue(updates.get(3).isActionable());
    assertEquals(EngineStartupStatus.State.READY, status.snapshot().state);
  }

  @Test
  void missingEngineRemainsActionableUnlessUserExplicitlySelectedNoEngine() {
    assertTrue(Lizzie.shouldOfferEngineRepair(false, false));
    assertFalse(Lizzie.shouldOfferEngineRepair(false, true));
    assertFalse(Lizzie.shouldOfferEngineRepair(true, false));
  }
}
