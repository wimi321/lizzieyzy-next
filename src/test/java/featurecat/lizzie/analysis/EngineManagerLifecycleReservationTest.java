package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class EngineManagerLifecycleReservationTest {

  @Test
  void automaticJavaSshRestartDoesNotClearQuarantinedGmaState() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    boolean previousEngineGame = EngineManager.isEngineGame;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingRestartLeelaz engine = new TrackingRestartLeelaz();
    engine.useJavaSSH = true;
    engine.isLoaded = true;
    engine.canCheckAlive = true;
    engine.javaSSHClosed = true;
    setEngineStateUnrestored(engine, true);
    EngineManager manager = new EngineManager(List.of(engine));
    try {
      Lizzie.leelaz = engine;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.currentEngineNo = 0;

      invokeCheckEngineAlive(manager);

      assertEquals(0, engine.restartCount);
      assertTrue(engine.hasUnrestoredReadBoardGmaState());
    } finally {
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void automaticProcessRestartDoesNotRaceAnActiveGmaReservation() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    boolean previousEngineGame = EngineManager.isEngineGame;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingRestartLeelaz engine = new TrackingRestartLeelaz();
    engine.started = true;
    engine.canCheckAlive = true;
    engine.processDead = true;
    Leelaz.EngineModeReservation reservation = engine.beginEngineModeReservation();
    setReadBoardGmaReservation(engine, reservation);
    EngineManager manager = new EngineManager(List.of(engine));
    try {
      Lizzie.leelaz = engine;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.currentEngineNo = 0;

      invokeCheckEngineAlive(manager);

      assertEquals(0, engine.restartCount);
    } finally {
      setReadBoardGmaReservation(engine, null);
      reservation.close();
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void automaticProcessRestartLosesTheRaceWhenGmaReservesBeforeRestartDispatch()
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    boolean previousEngineGame = EngineManager.isEngineGame;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingRestartLeelaz engine = new TrackingRestartLeelaz();
    engine.started = true;
    engine.canCheckAlive = true;
    engine.processDead = true;
    engine.blockProcessDeadCheck = true;
    EngineManager manager = new EngineManager(List.of(engine));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread checkThread =
        new Thread(
            () -> {
              try {
                invokeCheckEngineAlive(manager);
              } catch (Throwable ex) {
                failure.set(ex);
              }
            });
    Leelaz.EngineModeReservation reservation = null;
    try {
      Lizzie.leelaz = engine;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.currentEngineNo = 0;
      checkThread.start();
      assertTrue(engine.processDeadCheckEntered.await(1, TimeUnit.SECONDS));
      reservation = engine.beginEngineModeReservation();
      assertNotNull(reservation);
      setReadBoardGmaReservation(engine, reservation);

      engine.releaseProcessDeadCheck.countDown();
      checkThread.join(1000L);

      assertFalse(checkThread.isAlive());
      assertEquals(null, failure.get());
      assertEquals(0, engine.restartCount);
    } finally {
      engine.releaseProcessDeadCheck.countDown();
      checkThread.join(1000L);
      setReadBoardGmaReservation(engine, null);
      if (reservation != null) {
        reservation.close();
      }
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void remoteAutomaticRestartHandsItsReservationToTheBoardRestore() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    boolean previousEngineGame = EngineManager.isEngineGame;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingRestartLeelaz engine = new TrackingRestartLeelaz();
    engine.started = true;
    engine.canCheckAlive = true;
    engine.processDead = true;
    engine.useRemoteCompute = true;
    engine.blockSecondProcessDeadCheck = true;
    EngineManager manager = new EngineManager(List.of(engine));
    Leelaz.EngineModeReservation competingReservation = null;
    try {
      Lizzie.leelaz = engine;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.currentEngineNo = 0;

      invokeCheckEngineAlive(manager);
      assertTrue(engine.secondProcessDeadCheckEntered.await(1, TimeUnit.SECONDS));
      competingReservation = engine.beginEngineModeReservation();
      boolean competingReservationAcquired = competingReservation != null;
      if (competingReservation != null) {
        competingReservation.close();
        competingReservation = null;
      }
      engine.releaseSecondProcessDeadCheck.countDown();
      assertTrue(engine.restartCompleted.await(1, TimeUnit.SECONDS));

      assertFalse(competingReservationAcquired);
      assertEquals(1, engine.restartCount);
      Leelaz.EngineModeReservation afterRestore = engine.beginEngineModeReservation();
      assertNotNull(afterRestore);
      afterRestore.close();
    } finally {
      engine.releaseSecondProcessDeadCheck.countDown();
      if (competingReservation != null) {
        competingReservation.close();
      }
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

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
  void configurationSwitchReportsReservationConflictWithoutGenericPopup() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LifecycleConflictLeelaz current = new LifecycleConflictLeelaz();
    Leelaz target = new Leelaz("");
    DeferredSwitchEngineManager manager =
        new DeferredSwitchEngineManager(List.of(current, target));
    try {
      Lizzie.leelaz = current;

      assertFalse(manager.switchEngineIfAvailable(1, true));
      assertEquals(0, manager.conflictCount);
      assertEquals(0, manager.switchCount);
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void recoverySwitchWaitsForTargetBoardSynchronizationFenceBeforeReleasingReservations()
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    setEngineStateUnrestored(current, true);
    FenceTrackingLeelaz target = new FenceTrackingLeelaz();
    target.started = true;
    target.isLoaded = true;
    RecoverySwitchEngineManager manager =
        new RecoverySwitchEngineManager(List.of(current, target), target);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);
      assertTrue(current.hasExclusiveGtpWorkInProgress());
      assertTrue(target.hasExclusiveGtpWorkInProgress());

      manager.afterSync.run();

      assertNotNull(target.confirmation);
      assertTrue(current.hasExclusiveGtpWorkInProgress());
      assertTrue(target.hasExclusiveGtpWorkInProgress());
      target.confirmation.run();
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void failedRecoverySwitchFenceLeavesTargetUnavailableAndReleasesReservations()
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    setEngineStateUnrestored(current, true);
    FenceTrackingLeelaz target = new FenceTrackingLeelaz();
    target.started = true;
    target.isLoaded = true;
    RecoverySwitchEngineManager manager =
        new RecoverySwitchEngineManager(List.of(current, target), target);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);
      manager.afterSync.run();
      target.rejection.accept("controlled fence failure");

      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
      assertFalse(target.isLoaded());
      assertTrue(target.hasUnrestoredReadBoardGmaState());
      assertEquals(null, target.beginEngineModeReservation());
      assertFalse(target.beginExclusiveGtpLifecycleTransition());
      assertFalse(target.genmove("B", false));
      assertFalse(target.genmoveAnalyzeForReadBoard("B", 1, 1, false));
      assertEquals(1, manager.failureCount);
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void selectingTheSameQuarantinedEngineDoesNotPretendToRecoverIt() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    FenceTrackingLeelaz current = new FenceTrackingLeelaz();
    current.started = true;
    current.isLoaded = true;
    setEngineStateUnrestored(current, true);
    RecoverySwitchEngineManager manager =
        new RecoverySwitchEngineManager(List.of(current), current);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(0, true);
      manager.afterSync.run();

      assertEquals(null, current.confirmation);
      assertTrue(current.hasUnrestoredReadBoardGmaState());
      assertFalse(current.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void switchingToAQuarantinedTargetDoesNotPretendToRestoreItsRuntimeState() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    FenceTrackingLeelaz target = new FenceTrackingLeelaz();
    target.started = true;
    target.isLoaded = true;
    setEngineStateUnrestored(target, true);
    setCapabilityDiscoveryComplete(target, true);
    RecoverySwitchEngineManager manager =
        new RecoverySwitchEngineManager(List.of(current, target), target);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);
      manager.afterSync.run();

      assertEquals(null, target.confirmation);
      assertTrue(target.hasUnrestoredReadBoardGmaState());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void explicitlyRestartingAQuarantinedTargetClearsItOnlyAfterTheBoardFence() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    Leelaz current = new Leelaz("");
    FenceTrackingLeelaz target = new FenceTrackingLeelaz();
    target.started = true;
    target.isLoaded = true;
    setEngineStateUnrestored(target, true);
    setCapabilityDiscoveryComplete(target, true);
    RecoverySwitchEngineManager manager =
        new RecoverySwitchEngineManager(List.of(current, target), target);
    try {
      Lizzie.leelaz = current;
      EngineManager.isEmpty = false;

      manager.reStartEngine(1);
      manager.afterSync.run();
      assertNotNull(target.confirmation);
      assertTrue(target.hasUnrestoredReadBoardGmaState());

      target.confirmation.run();

      assertFalse(target.hasUnrestoredReadBoardGmaState());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
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

  @Test
  void failedTargetReadinessReleasesBothSwitchReservationsWithoutSynchronization()
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    Leelaz target = unavailableStartedEngine();
    target.isDownWithError = true;
    ReadinessFailureEngineManager manager =
        new ReadinessFailureEngineManager(List.of(current, target), target, 1000L);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);

      assertTrue(manager.completed.await(1, TimeUnit.SECONDS));
      assertEquals(target, Lizzie.leelaz);
      assertEquals(1, manager.failureCount);
      assertEquals(0, manager.synchronizationCount);
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
      assertFalse(target.isLoaded());
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void targetReadinessTimeoutReleasesBothSwitchReservations() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    Leelaz target = unavailableStartedEngine();
    ReadinessFailureEngineManager manager =
        new ReadinessFailureEngineManager(List.of(current, target), target, 10L);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);

      assertTrue(manager.completed.await(1, TimeUnit.SECONDS));
      assertEquals(1, manager.failureCount);
      assertEquals(0, manager.synchronizationCount);
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void switchWaitsForPublishedNameCheckAndBoardSynchronizationBeforeCompleting()
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    ControlledReadinessLeelaz target = unavailableControlledEngine(500L);
    ControlledReadinessEngineManager manager =
        new ControlledReadinessEngineManager(List.of(current, target), target, 1000L);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);
      assertTrue(target.firstLoadedReadEntered.await(1, TimeUnit.SECONDS));
      target.isLoaded = true;
      target.allowFirstLoadedRead.countDown();

      assertTrue(target.secondLoadedReadEntered.await(1, TimeUnit.SECONDS));
      assertTrue(current.hasExclusiveGtpWorkInProgress());
      assertTrue(target.hasExclusiveGtpWorkInProgress());

      target.isCheckingName = false;
      target.allowSecondLoadedRead.countDown();
      assertTrue(manager.synchronizationStarted.await(1, TimeUnit.SECONDS));
      assertEquals(1L, manager.completed.getCount());
      assertTrue(current.hasExclusiveGtpWorkInProgress());
      assertTrue(target.hasExclusiveGtpWorkInProgress());

      manager.allowSynchronizationToComplete.countDown();
      assertTrue(manager.completed.await(1, TimeUnit.SECONDS));
      assertEquals(1, manager.synchronizationCount);
      assertEquals(0, manager.failureCount);
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      target.started = false;
      target.releaseLoadedReads();
      manager.allowSynchronizationToComplete.countDown();
      manager.completed.await(1, TimeUnit.SECONDS);
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void publishedAbnormalExitFailsWithoutWaitingForTheStartupTimeout() throws Exception {
    assertPublishedTerminalStateFailsImmediately(
        target -> target.isDownWithError = true, "abnormal exit");
  }

  @Test
  void publishedNormalExitFailsWithoutWaitingForTheStartupTimeout() throws Exception {
    assertPublishedTerminalStateFailsImmediately(
        target -> target.isNormalEnd = true, "normal exit");
  }

  @Test
  void publishedStoppedStateFailsWithoutWaitingForTheStartupTimeout() throws Exception {
    assertPublishedTerminalStateFailsImmediately(target -> target.started = false, "stopped");
  }

  @Test
  void tuningStateExtendsTheOrdinaryStartupDeadline() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    ControlledReadinessLeelaz target = unavailableControlledEngine(1000L);
    target.isTuning = true;
    ControlledReadinessEngineManager manager =
        new ControlledReadinessEngineManager(List.of(current, target), target, 10L);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);
      assertTrue(target.firstLoadedReadEntered.await(1, TimeUnit.SECONDS));
      target.allowFirstLoadedRead.countDown();
      assertTrue(target.secondLoadedReadEntered.await(1, TimeUnit.SECONDS));
      assertFalse(manager.completed.await(50, TimeUnit.MILLISECONDS));
      target.allowSecondLoadedRead.countDown();
      assertTrue(target.thirdLoadedReadEntered.await(1, TimeUnit.SECONDS));

      target.isLoaded = true;
      target.isCheckingName = false;
      target.allowThirdLoadedRead.countDown();
      assertTrue(manager.synchronizationStarted.await(1, TimeUnit.SECONDS));
      manager.allowSynchronizationToComplete.countDown();
      assertTrue(manager.completed.await(1, TimeUnit.SECONDS));
      assertEquals(1, manager.synchronizationCount);
      assertEquals(0, manager.failureCount);
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      target.started = false;
      target.releaseLoadedReads();
      manager.allowSynchronizationToComplete.countDown();
      manager.completed.await(1, TimeUnit.SECONDS);
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void tuningTimeoutReleasesBothSwitchReservations() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    ControlledReadinessLeelaz target = unavailableControlledEngine(10L);
    target.isTuning = true;
    ControlledReadinessEngineManager manager =
        new ControlledReadinessEngineManager(List.of(current, target), target, 1000L);
    try {
      Lizzie.leelaz = current;
      target.releaseLoadedReads();

      manager.switchEngine(1, true);

      assertTrue(manager.completed.await(1, TimeUnit.SECONDS));
      assertEquals(target, Lizzie.leelaz);
      assertEquals(1, manager.failureCount);
      assertEquals(0, manager.synchronizationCount);
      assertFalse(target.isLoaded());
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      target.started = false;
      target.releaseLoadedReads();
      manager.allowSynchronizationToComplete.countDown();
      manager.completed.await(1, TimeUnit.SECONDS);
      Lizzie.leelaz = previousEngine;
    }
  }

  private static void assertPublishedTerminalStateFailsImmediately(
      Consumer<ControlledReadinessLeelaz> publishTerminalState, String stateDescription)
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz current = new Leelaz("");
    ControlledReadinessLeelaz target = unavailableControlledEngine(500L);
    ControlledReadinessEngineManager manager =
        new ControlledReadinessEngineManager(List.of(current, target), target, 5000L);
    try {
      Lizzie.leelaz = current;

      manager.switchEngine(1, true);
      assertTrue(target.firstLoadedReadEntered.await(1, TimeUnit.SECONDS));
      publishTerminalState.accept(target);
      target.allowFirstLoadedRead.countDown();

      assertTrue(
          manager.completed.await(500, TimeUnit.MILLISECONDS),
          stateDescription + " should fail before the five-second startup timeout");
      target.releaseLoadedReads();
      assertEquals(target, Lizzie.leelaz);
      assertEquals(1, manager.failureCount);
      assertEquals(0, manager.synchronizationCount);
      assertFalse(target.isLoaded());
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertFalse(target.hasExclusiveGtpWorkInProgress());
    } finally {
      target.started = false;
      target.releaseLoadedReads();
      manager.allowSynchronizationToComplete.countDown();
      manager.completed.await(1, TimeUnit.SECONDS);
      Lizzie.leelaz = previousEngine;
    }
  }

  private static ControlledReadinessLeelaz unavailableControlledEngine(
      long tuningTimeoutMillis) throws Exception {
    ControlledReadinessLeelaz engine = new ControlledReadinessLeelaz(tuningTimeoutMillis);
    engine.started = true;
    engine.isLoaded = false;
    engine.isCheckingName = true;
    return engine;
  }

  private static Leelaz unavailableStartedEngine() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.started = true;
    engine.isLoaded = false;
    engine.isCheckingName = true;
    return engine;
  }

  private static void setEngineStateUnrestored(Leelaz engine, boolean value) throws Exception {
    Field field = Leelaz.class.getDeclaredField("engineStateUnrestored");
    field.setAccessible(true);
    field.setBoolean(engine, value);
  }

  private static void setCapabilityDiscoveryComplete(Leelaz engine, boolean value)
      throws Exception {
    Field field = Leelaz.class.getDeclaredField("endGetCommandList");
    field.setAccessible(true);
    field.setBoolean(engine, value);
  }

  private static void setReadBoardGmaReservation(
      Leelaz engine, Leelaz.EngineModeReservation reservation) throws Exception {
    Field field = Leelaz.class.getDeclaredField("readBoardGmaReservation");
    field.setAccessible(true);
    field.set(engine, reservation);
  }

  private static void invokeCheckEngineAlive(EngineManager manager) throws Exception {
    Method method = EngineManager.class.getDeclaredMethod("checkEngineAlive");
    method.setAccessible(true);
    method.invoke(manager);
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

  private static final class FenceTrackingLeelaz extends Leelaz {
    private Runnable confirmation;
    private Consumer<String> rejection;

    private FenceTrackingLeelaz() throws Exception {
      super("");
    }

    @Override
    void confirmBoardSynchronization(Runnable onSuccess, Consumer<String> onFailure) {
      confirmation = onSuccess;
      rejection = onFailure;
    }
  }

  private static final class RecoverySwitchEngineManager extends EngineManager {
    private final Leelaz target;
    private Runnable afterSync;
    private int failureCount;

    private RecoverySwitchEngineManager(List<Leelaz> engines, Leelaz target) {
      super(engines);
      this.target = target;
    }

    @Override
    protected void switchEngineInternal(int index, boolean isMain, Runnable afterSync) {
      Lizzie.leelaz = target;
      target.started = true;
      target.isLoaded = true;
      this.afterSync = afterSync;
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failureCount++;
    }
  }

  private static final class ReadinessFailureEngineManager extends EngineManager {
    private final Leelaz target;
    private final long timeoutMillis;
    private final CountDownLatch completed = new CountDownLatch(1);
    private int failureCount;
    private int synchronizationCount;

    private ReadinessFailureEngineManager(List<Leelaz> engines, Leelaz target, long timeoutMillis) {
      super(engines);
      this.target = target;
      this.timeoutMillis = timeoutMillis;
    }

    @Override
    protected void switchEngineInternal(int index, boolean isMain, Runnable afterSync) {
      Lizzie.leelaz = target;
      synchronizeEngineWhenReady(
          target,
          () -> synchronizationCount++,
          () -> {
            afterSync.run();
            completed.countDown();
          });
    }

    @Override
    protected long engineSynchronizationTimeoutMillis(Leelaz engine) {
      return timeoutMillis;
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failureCount++;
    }
  }

  private static final class ControlledReadinessEngineManager extends EngineManager {
    private final Leelaz target;
    private final long timeoutMillis;
    private final CountDownLatch synchronizationStarted = new CountDownLatch(1);
    private final CountDownLatch allowSynchronizationToComplete = new CountDownLatch(1);
    private final CountDownLatch completed = new CountDownLatch(1);
    private int failureCount;
    private int synchronizationCount;

    private ControlledReadinessEngineManager(
        List<Leelaz> engines, Leelaz target, long timeoutMillis) {
      super(engines);
      this.target = target;
      this.timeoutMillis = timeoutMillis;
    }

    @Override
    protected void switchEngineInternal(int index, boolean isMain, Runnable afterSync) {
      Lizzie.leelaz = target;
      synchronizeEngineWhenReady(
          target,
          () -> {
            synchronizationStarted.countDown();
            await(allowSynchronizationToComplete);
            synchronizationCount++;
          },
          () -> {
            afterSync.run();
            completed.countDown();
          });
    }

    @Override
    protected long engineSynchronizationTimeoutMillis(Leelaz engine) {
      return timeoutMillis;
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failureCount++;
    }

    private static void await(CountDownLatch latch) {
      try {
        latch.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("controlled board synchronization interrupted", ex);
      }
    }
  }

  private static final class ControlledReadinessLeelaz extends Leelaz {
    private final AtomicInteger loadedReadCount = new AtomicInteger();
    private final CountDownLatch firstLoadedReadEntered = new CountDownLatch(1);
    private final CountDownLatch allowFirstLoadedRead = new CountDownLatch(1);
    private final CountDownLatch secondLoadedReadEntered = new CountDownLatch(1);
    private final CountDownLatch allowSecondLoadedRead = new CountDownLatch(1);
    private final CountDownLatch thirdLoadedReadEntered = new CountDownLatch(1);
    private final CountDownLatch allowThirdLoadedRead = new CountDownLatch(1);
    private final long tuningTimeoutMillis;

    private ControlledReadinessLeelaz(long tuningTimeoutMillis) throws Exception {
      super("");
      this.tuningTimeoutMillis = tuningTimeoutMillis;
    }

    @Override
    public boolean isLoaded() {
      int read = loadedReadCount.incrementAndGet();
      if (read == 1) {
        firstLoadedReadEntered.countDown();
        await(allowFirstLoadedRead);
      } else if (read == 2) {
        secondLoadedReadEntered.countDown();
        await(allowSecondLoadedRead);
      } else if (read == 3) {
        thirdLoadedReadEntered.countDown();
        await(allowThirdLoadedRead);
      }
      return super.isLoaded();
    }

    @Override
    long engineTuningSynchronizationTimeoutMillis() {
      return tuningTimeoutMillis;
    }

    private void releaseLoadedReads() {
      allowFirstLoadedRead.countDown();
      allowSecondLoadedRead.countDown();
      allowThirdLoadedRead.countDown();
    }

    private static void await(CountDownLatch latch) {
      try {
        latch.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("controlled readiness read interrupted", ex);
      }
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

  private static final class TrackingRestartLeelaz extends Leelaz {
    private final CountDownLatch processDeadCheckEntered = new CountDownLatch(1);
    private final CountDownLatch releaseProcessDeadCheck = new CountDownLatch(1);
    private final CountDownLatch secondProcessDeadCheckEntered = new CountDownLatch(1);
    private final CountDownLatch releaseSecondProcessDeadCheck = new CountDownLatch(1);
    private final CountDownLatch restartCompleted = new CountDownLatch(1);
    private boolean processDead;
    private boolean blockProcessDeadCheck;
    private boolean blockSecondProcessDeadCheck;
    private int processDeadCheckCount;
    private int restartCount;

    private TrackingRestartLeelaz() throws Exception {
      super("");
    }

    @Override
    public boolean isProcessDead() {
      processDeadCheckCount++;
      if (blockProcessDeadCheck) {
        processDeadCheckEntered.countDown();
        await(releaseProcessDeadCheck);
      }
      if (blockSecondProcessDeadCheck && processDeadCheckCount == 2) {
        secondProcessDeadCheckEntered.countDown();
        await(releaseSecondProcessDeadCheck);
      }
      return processDead;
    }

    @Override
    public void restartClosedEngine(int index) {
      restartCount++;
      restartCompleted.countDown();
    }

    @Override
    void restartClosedEngine(int index, Runnable afterBoardRestore) {
      restartClosedEngine(index);
      if (afterBoardRestore != null) {
        afterBoardRestore.run();
      }
    }

    private static void await(CountDownLatch latch) {
      try {
        latch.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("controlled restart check interrupted", ex);
      }
    }
  }
}
