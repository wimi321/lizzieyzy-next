package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.JFontMenu;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class EngineManagerLifecycleReservationTest {

  @Test
  void killAllEnginesClaimsActiveTrackingAndRunsImmediatelyOnce() throws Exception {
    assertDestructiveKillClaimsActiveTracking(true);
  }

  @Test
  void killThisEngineClaimsActiveTrackingAndRunsImmediatelyOnce() throws Exception {
    assertDestructiveKillClaimsActiveTracking(false);
  }

  @Test
  void activeRestartResumesPonderAfterFinalBoardFenceAndRetiresTrackingOnRebind() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingRestartActionLeelaz engine = new TrackingRestartActionLeelaz();
    CountingRestartGateFrame frame = allocate(CountingRestartGateFrame.class);
    DeferredSwitchEngineManager manager = new DeferredSwitchEngineManager(List.of(engine));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    setLeelazField(engine, "outputStream", new BufferedOutputStream(output));
    setCapabilityDiscoveryComplete(engine, true);
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = frame;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      Leelaz.TrackingStreamLeaseAcquisition tracking = activateTracking(engine);
      engine.emitPonderCommand = true;

      manager.reStartEngine(0);

      assertEquals(1, engine.shutdownCount);
      assertEquals(1, manager.switchCount);
      assertEquals(1, frame.beginCount);
      assertFalse(tracking.lease().isOwned());
      assertTrue(engine.hasExclusiveGtpWorkInProgress());
      assertNotNull(manager.afterSync);
      assertFalse(engine.isStarted());
      assertFalse(engine.isLoaded());
      engine.started = true;
      engine.isLoaded = true;
      engine.Pondering();
      manager.afterSync.run();
      assertNotNull(engine.confirmation);
      assertTrue(engine.hasExclusiveGtpWorkInProgress());
      assertEquals(0, engine.ponderCount);
      setLeelazField(engine, "currentCmdNum", 15);
      setLeelazField(engine, "cmdNumber", 16);
      engine.confirmation.run();
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
      assertEquals(1, engine.ponderCount);
      assertTrue(engine.ponderWhileLifecycleHeld);
      assertEquals(17, getLeelazField(engine, "cmdNumber"));
      assertTrue(
          engine.isResponseUpToDate(),
          "post-fence ponder must accept the first analysis info without another board action");
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void activeRestartReleasesLifecycleAfterBoardFenceFailure() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingRestartActionLeelaz engine = new TrackingRestartActionLeelaz();
    CountingRestartGateFrame frame = allocate(CountingRestartGateFrame.class);
    DeferredSwitchEngineManager manager = new DeferredSwitchEngineManager(List.of(engine));
    setLeelazField(engine, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
    setCapabilityDiscoveryComplete(engine, true);
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = frame;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      activateTracking(engine);

      manager.reStartEngine(0);

      assertEquals(1, engine.shutdownCount);
      assertEquals(1, frame.beginCount);
      assertNotNull(manager.afterSync);
      assertFalse(engine.isStarted());
      assertFalse(engine.isLoaded());
      engine.started = true;
      engine.isLoaded = true;
      engine.Pondering();
      manager.afterSync.run();
      assertNotNull(engine.rejection);
      assertTrue(engine.hasExclusiveGtpWorkInProgress());

      engine.rejection.accept("controlled board fence failure");

      assertFalse(engine.hasExclusiveGtpWorkInProgress());
      assertFalse(engine.isLoaded());
      assertEquals(0, engine.ponderCount);
      assertEquals(1, manager.failureCount);
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void unresponsiveRemoteAnalysisRestartsAndRestoresThroughExistingLifecycle() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingRestartLeelaz engine = new TrackingRestartLeelaz();
    engine.useRemoteCompute = true;
    engine.started = true;
    engine.processDead = true;
    engine.Pondering();
    EngineManager manager = new EngineManager(List.of(engine));
    try {
      Lizzie.leelaz = engine;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      manager.restartUnresponsiveRemoteEngine(engine, 0);

      assertTrue(engine.restartCompleted.await(2, TimeUnit.SECONDS));
      assertEquals(1, engine.restartCount);
    } finally {
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void disconnectedRemoteSessionRestartsEvenWhenOrdinaryPonderIsNotActive() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingRestartLeelaz engine = new TrackingRestartLeelaz();
    engine.useRemoteCompute = true;
    engine.started = true;
    engine.processDead = true;
    EngineManager manager = new EngineManager(List.of(engine));
    try {
      Lizzie.leelaz = engine;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      manager.restartUnresponsiveRemoteEngine(engine, 0);

      assertTrue(engine.restartCompleted.await(2, TimeUnit.SECONDS));
      assertEquals(1, engine.restartCount);
    } finally {
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

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
  void automaticProcessRestartLosesTheRaceWhenGmaReservesBeforeRestartDispatch() throws Exception {
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
  void retainedSwitchKeepsOldTrackingQueueGatedUntilFinalFence() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    TrackingKillLeelaz current = new TrackingKillLeelaz();
    TrackingRestartActionLeelaz target = new TrackingRestartActionLeelaz();
    LifecycleFrame frame = allocate(LifecycleFrame.class);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    setLeelazField(current, "outputStream", new BufferedOutputStream(output));
    setCapabilityDiscoveryComplete(current, true);
    DeferredSwitchEngineManager manager = new DeferredSwitchEngineManager(List.of(current, target));
    try {
      Lizzie.frame = frame;
      Lizzie.leelaz = current;
      Lizzie.config = allocate(Config.class);
      activateTracking(current);

      manager.switchEngine(1, true);
      target.started = true;
      target.isLoaded = true;
      target.Pondering();
      Lizzie.leelaz = target;

      assertEquals(1, manager.switchCount);
      assertNotNull(manager.afterSync);
      assertEquals(
          "800000000 stop\n800000001 kata-analyze B 10\n800000002 stop\n",
          output.toString(StandardCharsets.UTF_8));
      current.sendCommand("stop");
      manager.afterSync.run();
      assertNotNull(target.confirmation);
      target.confirmation.run();
      assertEquals(0, target.ponderCount, "regular switch must preserve its existing ponder path");
      assertEquals(
          "800000000 stop\n800000001 kata-analyze B 10\n800000002 stop\n",
          output.toString(StandardCharsets.UTF_8));

      assertTrue(dispatchExclusiveLine(current, ""));
      assertTrue(dispatchExclusiveLine(current, "=800000002"));
      assertTrue(dispatchExclusiveLine(current, ""));
      assertFalse(current.hasExclusiveGtpWorkInProgress());
      assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("stop\nstop\n"));
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void configurationSwitchReportsReservationConflictWithoutGenericPopup() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LifecycleConflictLeelaz current = new LifecycleConflictLeelaz();
    Leelaz target = new Leelaz("");
    DeferredSwitchEngineManager manager = new DeferredSwitchEngineManager(List.of(current, target));
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
  void switchReservesDistinctTargetBeforeTouchingCurrentOwner() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    List<String> reservationOrder = new java.util.ArrayList<>();
    OrderedLifecycleLeelaz current = new OrderedLifecycleLeelaz("current", reservationOrder, false);
    OrderedLifecycleLeelaz target = new OrderedLifecycleLeelaz("target", reservationOrder, true);
    DeferredSwitchEngineManager manager = new DeferredSwitchEngineManager(List.of(current, target));
    try {
      Lizzie.leelaz = current;

      assertFalse(manager.switchEngineIfAvailable(1, true));

      assertEquals(List.of("target"), reservationOrder);
      assertEquals(0, current.reservationAttempts);
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
  void failedRecoverySwitchFenceLeavesTargetUnavailableAndReleasesReservations() throws Exception {
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
  void inactiveExplicitRestartPreservesImmediateLifecycleSettlement() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    boolean previousEmpty = EngineManager.isEmpty;
    FenceTrackingLeelaz engine = new FenceTrackingLeelaz();
    CountingRestartGateFrame frame = allocate(CountingRestartGateFrame.class);
    engine.started = true;
    engine.isLoaded = true;
    RecoverySwitchEngineManager manager = new RecoverySwitchEngineManager(List.of(engine), engine);
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = frame;
      EngineManager.isEmpty = false;

      manager.reStartEngine(0);
      manager.afterSync.run();

      assertNull(engine.confirmation);
      assertEquals(0, frame.beginCount);
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      EngineManager.isEmpty = previousEmpty;
    }
  }

  @Test
  void restartSynchronizationPropagatesReceiptIntoTheFinalBoardFence() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    ReceiptAwareFenceLeelaz engine = new ReceiptAwareFenceLeelaz();
    engine.started = true;
    engine.isLoaded = true;
    setCapabilityDiscoveryComplete(engine, true);
    setLeelazField(engine, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
    Lizzie.leelaz = engine;
    Leelaz.ExclusiveGtpLifecycleReservation reservation = null;
    try {
      activateTracking(engine);
      reservation = engine.beginExclusiveGtpLifecycleReservation();
      assertNotNull(reservation);
      rebindReader(engine);
      ReceiptSynchronizationEngineManager manager =
          new ReceiptSynchronizationEngineManager(List.of(engine));
      manager.synchronize(engine, () -> engine.confirmBoardSynchronization(() -> {}, detail -> {}));

      assertTrue(manager.completed.await(1, TimeUnit.SECONDS));
      assertTrue(engine.receiptSeenByBoardFence);
    } finally {
      if (reservation != null) {
        reservation.close();
      }
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void restartReceiptIsDetachedFromTheReaderBindingWhenLifecycleEnds() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz engine = new TrackingRestartActionLeelaz();
    setCapabilityDiscoveryComplete(engine, true);
    setLeelazField(engine, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
    Lizzie.leelaz = engine;
    try {
      activateTracking(engine);
      Leelaz.ExclusiveGtpLifecycleReservation reservation =
          engine.beginExclusiveGtpLifecycleReservation();
      assertNotNull(reservation);
      rebindReader(engine);
      Object binding = getLeelazField(engine, "readerStreamBinding");
      assertNotNull(getField(binding, "restartBootstrapReceipt"));

      reservation.close();

      assertEquals(null, getField(binding, "restartBootstrapReceipt"));
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void restartGateFailureReleasesLifecycleBeforeDestructiveWork() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    boolean previousEmpty = EngineManager.isEmpty;
    TrackingRestartActionLeelaz engine = new TrackingRestartActionLeelaz();
    setLeelazField(engine, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
    setCapabilityDiscoveryComplete(engine, true);
    GateFailureEngineManager manager = new GateFailureEngineManager(List.of(engine));
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = null;
      EngineManager.isEmpty = false;
      Leelaz.TrackingStreamLeaseAcquisition tracking = activateTracking(engine);
      Lizzie.frame = allocate(FailingRestartGateFrame.class);

      manager.reStartEngine(0);

      assertEquals(0, engine.shutdownCount);
      assertEquals(1, manager.failureCount);
      assertEquals(Leelaz.TrackingReleaseDisposition.CLEARED, tracking.lease().disposition());
      assertFalse((boolean) getLeelazField(engine, "exclusiveGtpLifecycleTransition"));
      assertTrue(dispatchExclusiveLine(engine, ""));
      assertTrue(dispatchExclusiveLine(engine, "=800000002"));
      assertTrue(dispatchExclusiveLine(engine, ""));
      assertFalse(tracking.lease().isOwned());
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
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
  void switchWaitsForPublishedNameCheckAndBoardSynchronizationBeforeCompleting() throws Exception {
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

  private static ControlledReadinessLeelaz unavailableControlledEngine(long tuningTimeoutMillis)
      throws Exception {
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

  private static void assertDestructiveKillClaimsActiveTracking(boolean killAll) throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    JFontMenu previousEngineMenu = Menu.engineMenu;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    TrackingKillLeelaz engine = new TrackingKillLeelaz();
    LifecycleFrame frame = allocate(LifecycleFrame.class);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    setLeelazField(engine, "outputStream", new BufferedOutputStream(output));
    setCapabilityDiscoveryComplete(engine, true);
    try {
      Lizzie.frame = frame;
      Lizzie.leelaz = engine;
      Menu.engineMenu = new JFontMenu();
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      Leelaz.TrackingStreamLeaseAcquisition tracking = activateTracking(engine);
      EngineManager manager = new EngineManager(List.of(engine));

      if (killAll) {
        assertTrue(manager.killAllEngines());
      } else {
        manager.killThisEngines();
      }

      assertEquals(1, engine.forceQuitCount);
      assertEquals(Leelaz.TrackingReleaseDisposition.CLEARED, tracking.lease().disposition());
      assertEquals(
          "800000000 stop\n800000001 kata-analyze B 10\n800000002 stop\n",
          output.toString(StandardCharsets.UTF_8));
      assertEquals(1, frame.trackingInvalidationCount);
      assertTrue(dispatchExclusiveLine(engine, ""));
      assertTrue(dispatchExclusiveLine(engine, "=800000002"));
      assertTrue(dispatchExclusiveLine(engine, ""));
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      Menu.engineMenu = previousEngineMenu;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  private static void setLeelazField(Leelaz engine, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(engine, value);
  }

  private static Object getLeelazField(Leelaz engine, String name)
      throws ReflectiveOperationException {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(engine);
  }

  private static Object getField(Object target, String name) throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static boolean hasRestartBootstrapReceiptContext(Leelaz engine) {
    try {
      @SuppressWarnings("unchecked")
      ThreadLocal<Object> context =
          (ThreadLocal<Object>) getLeelazField(engine, "restartBootstrapReceiptContext");
      return context.get() != null;
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static boolean dispatchExclusiveLine(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("dispatchExclusiveGtpLine", String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(engine, line);
  }

  private static void processCommandResponse(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("processCommandResponseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
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

  private static Leelaz.TrackingStreamLeaseAcquisition activateTracking(Leelaz engine)
      throws Exception {
    Leelaz.TrackingStreamLeaseAcquisition tracking =
        engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
    processCommandResponse(engine, "=800000000");
    assertTrue(dispatchExclusiveLine(engine, ""));
    assertTrue(tracking.lease().send("kata-analyze B 10"));
    return tracking;
  }

  private static void rebindReader(Leelaz engine) {
    try {
      Method method =
          Leelaz.class.getDeclaredMethod(
              "initializeStreams",
              java.io.InputStream.class,
              java.io.OutputStream.class,
              java.io.InputStream.class);
      method.setAccessible(true);
      method.invoke(
          engine,
          new java.io.ByteArrayInputStream(new byte[0]),
          new ByteArrayOutputStream(),
          new java.io.ByteArrayInputStream(new byte[0]));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
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
    private int failureCount;

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

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failureCount++;
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

  private static final class TrackingKillLeelaz extends Leelaz {
    private int forceQuitCount;

    private TrackingKillLeelaz() throws Exception {
      super("");
      started = true;
      isLoaded = true;
      isKatago = true;
      commandLists.addAll(List.of("stop", "boardsize", "komi", "kata-analyze"));
    }

    @Override
    public void forceQuit() {
      forceQuitCount++;
    }
  }

  private static final class TrackingRestartActionLeelaz extends Leelaz {
    private int shutdownCount;
    private int ponderCount;
    private boolean ponderWhileLifecycleHeld;
    private Runnable confirmation;
    private Consumer<String> rejection;
    private boolean emitPonderCommand;

    private TrackingRestartActionLeelaz() throws Exception {
      super("");
      started = true;
      isLoaded = true;
      isKatago = true;
      commandLists.addAll(List.of("stop", "boardsize", "komi", "kata-analyze"));
    }

    @Override
    public void shutdown() {
      shutdownCount++;
      rebindReader(this);
    }

    @Override
    public void ponder() {
      ponderWhileLifecycleHeld = hasExclusiveGtpWorkInProgress();
      ponderCount++;
      if (emitPonderCommand) {
        cmdNumber++;
      }
    }

    @Override
    void confirmBoardSynchronization(Runnable onSuccess, Consumer<String> onFailure) {
      confirmation = onSuccess;
      rejection = onFailure;
    }
  }

  private static final class LifecycleFrame extends LizzieFrame {
    private int trackingInvalidationCount;

    @Override
    public void invalidateTrackingAnalysis() {
      trackingInvalidationCount++;
    }

    @Override
    public void refresh() {}
  }

  private static final class CountingRestartGateFrame extends LizzieFrame {
    private int beginCount;

    @Override
    public boolean isDisplayable() {
      return true;
    }

    @Override
    public RestartInteractionGate beginRestartInteractionGate() {
      beginCount++;
      return () -> {};
    }
  }

  private static final class FailingRestartGateFrame extends LizzieFrame {
    @Override
    public boolean isDisplayable() {
      return true;
    }

    @Override
    public RestartInteractionGate beginRestartInteractionGate() {
      throw new IllegalStateException("controlled restart gate failure");
    }
  }

  private static final class GateFailureEngineManager extends EngineManager {
    private int failureCount;

    private GateFailureEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      failureCount++;
    }
  }

  private static final class OrderedLifecycleLeelaz extends Leelaz {
    private final String name;
    private final List<String> reservationOrder;
    private final boolean rejectReservation;
    private int reservationAttempts;

    private OrderedLifecycleLeelaz(
        String name, List<String> reservationOrder, boolean rejectReservation) throws Exception {
      super("");
      this.name = name;
      this.reservationOrder = reservationOrder;
      this.rejectReservation = rejectReservation;
    }

    @Override
    public ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation() {
      reservationAttempts++;
      reservationOrder.add(name);
      return rejectReservation ? null : super.beginExclusiveGtpLifecycleReservation();
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

  private static final class ReceiptAwareFenceLeelaz extends Leelaz {
    private boolean receiptSeenByBoardFence;

    private ReceiptAwareFenceLeelaz() throws Exception {
      super("");
      isKatago = true;
      commandLists.addAll(List.of("stop", "boardsize", "komi", "kata-analyze"));
    }

    @Override
    void confirmBoardSynchronization(Runnable onSuccess, Consumer<String> onFailure) {
      receiptSeenByBoardFence = hasRestartBootstrapReceiptContext(this);
      onSuccess.run();
    }
  }

  private static final class ReceiptSynchronizationEngineManager extends EngineManager {
    private final CountDownLatch completed = new CountDownLatch(1);

    private ReceiptSynchronizationEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    private void synchronize(Leelaz engine, Runnable afterSync) {
      synchronizeEngineWhenReady(
          engine,
          () -> {},
          () -> {
            afterSync.run();
            completed.countDown();
          });
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
    public void restartClosedEngine(int index, Runnable afterBoardRestore) {
      restartCount++;
      if (afterBoardRestore != null) {
        afterBoardRestore.run();
      }
      restartCompleted.countDown();
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
