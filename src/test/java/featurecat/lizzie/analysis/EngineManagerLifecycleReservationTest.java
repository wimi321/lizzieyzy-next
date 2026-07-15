package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineManagerLifecycleReservationTest {

  @Test
  void switchKeepsCurrentAndTargetReservedUntilBoardSynchronizationCompletes() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    Leelaz target = new Leelaz("");
    DeferredSwitchEngineManager manager = new DeferredSwitchEngineManager(List.of(current, target));
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);

      assertTrue(current.hasExclusiveGtpWorkInProgress());
      assertTrue(target.hasExclusiveGtpWorkInProgress());
      assertNotNull(manager.afterSync);

      Thread synchronizationThread = new Thread(manager.afterSync);
      synchronizationThread.start();
      synchronizationThread.join();

      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void secondaryRestartConflictDoesNotShutDownSecondaryEngine() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz previousSecondEngine = Lizzie.leelaz2;
    int previousSecondEngineNo = EngineManager.currentEngineNo2;
    LifecycleConflictLeelaz current = new LifecycleConflictLeelaz();
    TrackingShutdownLeelaz secondary = new TrackingShutdownLeelaz();
    DeferredSwitchEngineManager manager =
        new DeferredSwitchEngineManager(List.of(current, secondary));
    try {
      Lizzie.leelaz = current;
      Lizzie.leelaz2 = secondary;
      EngineManager.currentEngineNo2 = 1;

      manager.reStartEngine2();

      assertEquals(1, manager.conflictCount);
      assertEquals(0, secondary.shutdownCount);
      assertEquals(0, manager.switchCount);
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.leelaz2 = previousSecondEngine;
      EngineManager.currentEngineNo2 = previousSecondEngineNo;
    }
  }

  private static final class DeferredSwitchEngineManager extends EngineManager {
    private Runnable afterSync;
    private int conflictCount;
    private int switchCount;

    private DeferredSwitchEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void switchEngineInternal(int index, boolean isMain, Runnable afterSync) {
      switchCount++;
      this.afterSync = afterSync;
    }

    @Override
    protected void showForegroundEngineLeaseInUse() {
      conflictCount++;
    }
  }

  private static final class LifecycleConflictLeelaz extends Leelaz {
    private LifecycleConflictLeelaz() throws Exception {
      super("");
    }

    @Override
    public synchronized ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation() {
      return null;
    }
  }

  private static final class TrackingShutdownLeelaz extends Leelaz {
    private int shutdownCount;

    private TrackingShutdownLeelaz() throws Exception {
      super("");
    }

    @Override
    public void shutdown() {
      shutdownCount++;
    }
  }
}
