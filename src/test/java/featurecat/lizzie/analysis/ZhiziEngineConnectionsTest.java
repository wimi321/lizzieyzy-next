package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.EngineTransport;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZhiziEngineConnectionsTest {
  @Test
  void filtersOnlyZhiziAndDeduplicatesSharedCatalogIdentity() throws Exception {
    Leelaz cloud = engine(RemoteComputeConfig.COMMAND_ZHIZI);
    Leelaz local = engine("katago gtp");
    Leelaz other = engine(RemoteComputeConfig.COMMAND_CUSTOM_WS);
    assertEquals(
        List.of(cloud), ZhiziEngineConnections.targets(List.of(local, cloud, other, cloud)));
  }

  @Test
  void statusUsesActualTransportNotSelectionOrStartedFlag() throws Exception {
    Leelaz cloud = engine(RemoteComputeConfig.COMMAND_ZHIZI);
    cloud.started = true;
    assertEquals(ZhiziEngineConnections.State.DISCONNECTED, state(cloud));
    Transport transport = install(cloud);
    cloud.started = false;
    assertEquals(ZhiziEngineConnections.State.CONNECTED, state(cloud));
    transport.open = false;
    assertEquals(ZhiziEngineConnections.State.DISCONNECTED, state(cloud));
  }

  @Test
  void busyTransitionAndExclusiveOwnerDoNotCloseOrDeselect() throws Exception {
    Leelaz cloud = engine(RemoteComputeConfig.COMMAND_ZHIZI);
    Transport transport = install(cloud);
    assertNull(ZhiziEngineConnections.prepare(List.of(cloud), true, e -> fail("deselected")));
    try (var lease = cloud.beginExclusiveGtpLifecycleReservation()) {
      assertNotNull(lease);
      assertEquals(ZhiziEngineConnections.State.BUSY, state(cloud));
      assertNull(ZhiziEngineConnections.prepare(List.of(cloud), false, e -> fail("deselected")));
    }
    assertEquals(0, transport.closes);
    assertEquals(ZhiziEngineConnections.State.CONNECTED, state(cloud));
  }

  @Test
  void partialAdmissionReleasesEarlierReservationWithoutClosing() throws Exception {
    Leelaz first = engine(RemoteComputeConfig.COMMAND_ZHIZI);
    Leelaz last =
        new Leelaz(RemoteComputeConfig.COMMAND_ZHIZI) {
          @Override
          ExclusiveGtpLifecycleReservation reserveZhiziDisconnect() {
            return null;
          }
        };
    Transport transport = install(first);
    var shutdown =
        ZhiziEngineConnections.prepare(List.of(first, last), false, e -> fail("deselected"));
    assertNotNull(shutdown);
    assertEquals(ZhiziEngineConnections.State.BUSY, shutdown.result);
    assertTrue(first.zhiziConnectionBusy(), "release waits until outside selection preparation");
    shutdown.finish();
    assertFalse(first.zhiziConnectionBusy());
    assertEquals(0, transport.closes);
    assertFalse((boolean) field(first, "zhiziDisconnectReserved"));
    assertFalse((boolean) field(first, "zhiziDisconnected"));
  }

  @Test
  void closesAllExactClientsOnceAndBlocksDelayedAutomaticStart() throws Exception {
    Leelaz first = engine(RemoteComputeConfig.COMMAND_ZHIZI);
    Leelaz second = engine(RemoteComputeConfig.COMMAND_ZHIZI);
    Transport one = install(first), two = install(second);
    List<Leelaz> deselected = new ArrayList<>();
    var shutdown = ZhiziEngineConnections.prepare(List.of(first, second), false, deselected::add);
    assertNotNull(shutdown);
    assertEquals(List.of(first, second), deselected);
    assertEquals(0, one.closes, "physical close is outside selection preparation");
    assertThrows(IOException.class, () -> first.startEngine(0));
    shutdown.finish();
    assertEquals(1, one.closes);
    assertEquals(1, two.closes);
    assertEquals(ZhiziEngineConnections.State.DISCONNECTED, state(first));
    assertThrows(IOException.class, () -> first.startEngine(0));
    var repeated = ZhiziEngineConnections.prepare(List.of(first, second), false, e -> {});
    assertNotNull(repeated);
    repeated.finish();
    assertEquals(1, one.closes);
    first.allowExplicitZhiziConnection();
    assertFalse((boolean) field(first, "zhiziDisconnected"));
  }

  @Test
  void exactCloseCannotCloseReplacementTransport() throws Exception {
    Leelaz cloud = engine(RemoteComputeConfig.COMMAND_ZHIZI);
    Transport old = install(cloud);
    var shutdown = ZhiziEngineConnections.prepare(List.of(cloud), false, e -> {});
    set(cloud, "readerStreamBinding", null);
    Transport replacement = install(cloud);
    shutdown.finish();
    assertEquals(1, old.closes);
    assertEquals(0, replacement.closes);
    assertTrue(replacement.open);
  }

  @Test
  void reservationBlocksConnectBeforeAllTargetAdmissionCompletes() throws Exception {
    Leelaz cloud = engine(RemoteComputeConfig.COMMAND_ZHIZI);
    var reservation = cloud.reserveZhiziDisconnect();
    assertNotNull(reservation);
    try {
      assertThrows(IOException.class, () -> cloud.startEngine(0));
      assertNull(field(cloud, "remoteTransport"));
      assertFalse((boolean) field(cloud, "zhiziDisconnected"));
    } finally {
      cloud.releaseZhiziDisconnect(reservation);
    }
    assertFalse((boolean) field(cloud, "zhiziDisconnectReserved"));
  }

  @Test
  void activeUnboundStartIsBusy() throws Exception {
    Leelaz cloud = engine(RemoteComputeConfig.COMMAND_ZHIZI);
    var attempt = cloud.beginUpdateEngineStartAttempt();
    try {
      assertEquals(ZhiziEngineConnections.State.BUSY, state(cloud));
      assertNull(ZhiziEngineConnections.prepare(List.of(cloud), false, e -> fail("deselected")));
    } finally {
      attempt.failClose(new IOException("cancel test attempt"));
    }
  }

  @Test
  void cleanupFailureStillClosesOtherClientsAndReleasesReservations() throws Exception {
    Leelaz first = engine(RemoteComputeConfig.COMMAND_ZHIZI);
    Leelaz second = engine(RemoteComputeConfig.COMMAND_ZHIZI);
    Transport one = install(first), two = install(second);
    one.failure = new IllegalStateException("controlled close failure");
    var shutdown = ZhiziEngineConnections.prepare(List.of(first, second), false, e -> {});
    assertThrows(IllegalStateException.class, shutdown::finish);
    assertEquals(1, two.closes);
    assertFalse(first.zhiziConnectionBusy());
    assertFalse(second.zhiziConnectionBusy());
  }

  @Test
  void managerDisconnectPreservesLocalPrimaryAndClearsOnlyCloudComparison() throws Exception {
    verifyManagerSelection(false);
  }

  @Test
  void managerDisconnectRetiresCloudPrimaryAndPreservesLocalComparison() throws Exception {
    verifyManagerSelection(true);
  }

  @Test
  void managerFindsDetachedCloudComparisonOutsideCatalogAndPreservesLocalPrimary()
      throws Exception {
    verifyManagerSelection(false, true);
  }

  private static void verifyManagerSelection(boolean cloudPrimary) throws Exception {
    verifyManagerSelection(cloudPrimary, false);
  }

  private static void verifyManagerSelection(boolean cloudPrimary, boolean detached)
      throws Exception {
    Leelaz oldPrimary = Lizzie.leelaz, oldSecondary = Lizzie.leelaz2;
    EngineManager oldManager = Lizzie.engineManager;
    int oldIndex = EngineManager.currentEngineNo, oldSecondIndex = EngineManager.currentEngineNo2;
    boolean oldEmpty = EngineManager.isEmpty;
    Leelaz cloud = engine(RemoteComputeConfig.COMMAND_ZHIZI), local = engine("katago gtp");
    Transport transport = install(cloud);
    local.started = true;
    local.isLoaded = true;
    int localIndex = detached ? 0 : 1;
    int cloudIndex = detached ? -1 : 0;
    EngineManager manager =
        new EngineManager(detached ? List.of(local) : List.of(cloud, local)) {
          @Override
          protected void publishEngineSwitchUiState(EngineSwitchUiSnapshot snapshot) {}
        };
    Field trackerField = EngineManager.class.getDeclaredField("engineSwitchUiTracker");
    trackerField.setAccessible(true);
    EngineManager.EngineSwitchUiTracker tracker =
        (EngineManager.EngineSwitchUiTracker) trackerField.get(manager);
    try {
      Lizzie.engineManager = manager;
      Lizzie.leelaz = cloudPrimary ? cloud : local;
      Lizzie.leelaz2 = cloudPrimary ? local : cloud;
      EngineManager.currentEngineNo = cloudPrimary ? cloudIndex : localIndex;
      EngineManager.currentEngineNo2 = cloudPrimary ? localIndex : cloudIndex;
      EngineManager.isEmpty = false;
      var selected = tracker.begin(cloudPrimary, 0, "Zhizi", cloud, 0, "Zhizi", cloud);
      tracker.succeed(selected.token(), cloudPrimary, 0, "Zhizi", cloud);
      assertEquals(ZhiziEngineConnections.State.CONNECTED, manager.zhiziConnectionState());
      assertEquals(ZhiziEngineConnections.State.DISCONNECTED, manager.disconnectZhiziEngines());
      assertEquals(1, transport.closes);
      assertEquals(
          EngineManager.EngineSwitchUiPhase.IDLE,
          manager.engineSwitchUiSnapshot(cloudPrimary).phase());
      assertTrue(tracker.succeed(selected.token(), cloudPrimary, 0, "stale", cloud).isEmpty());
      assertSame(local, cloudPrimary ? Lizzie.leelaz2 : Lizzie.leelaz);
      assertSame(cloud, cloudPrimary ? Lizzie.leelaz : Lizzie.leelaz2);
      assertFalse(cloud.isLoaded);
      assertFalse(cloud.started);
      assertEquals(cloudPrimary, EngineManager.isEmpty);
      assertEquals(cloudPrimary ? -1 : localIndex, EngineManager.currentEngineNo);
      assertEquals(cloudPrimary ? localIndex : -1, EngineManager.currentEngineNo2);
      assertTrue(local.started);
      manager.restartUnresponsiveRemoteEngine(cloud, 0);
      assertThrows(
          IOException.class, () -> cloud.startEngine(0), "a delayed automatic start cannot reopen");
    } finally {
      Lizzie.leelaz = oldPrimary;
      Lizzie.leelaz2 = oldSecondary;
      Lizzie.engineManager = oldManager;
      EngineManager.currentEngineNo = oldIndex;
      EngineManager.currentEngineNo2 = oldSecondIndex;
      EngineManager.isEmpty = oldEmpty;
    }
  }

  @Test
  void explicitNormalExitDoesNotRequestRecoveryFromRetiredTransport() throws Exception {
    Leelaz cloud = engine(RemoteComputeConfig.COMMAND_ZHIZI);
    Transport transport = install(cloud);
    transport.recoveryRequested = true;
    EngineManager oldManager = Lizzie.engineManager;
    var oldFrame = Lizzie.frame;
    int[] restartRequests = {0};
    EngineManager manager =
        new EngineManager(List.of(cloud)) {
          @Override
          void restartUnresponsiveRemoteEngine(Leelaz target, int index) {
            restartRequests[0]++;
          }
        };
    Object binding = field(cloud, "readerStreamBinding");
    Method finish =
        Leelaz.class.getDeclaredMethod("finishTerminatedReaderIncarnation", binding.getClass());
    finish.setAccessible(true);
    Field normalExit = binding.getClass().getDeclaredField("normalExitRequested");
    normalExit.setAccessible(true);
    try {
      Lizzie.engineManager = manager;
      Lizzie.frame = null;
      finish.invoke(cloud, binding);
      assertEquals(1, restartRequests[0], "unexpected termination still requests normal recovery");
      normalExit.setBoolean(binding, true);
      finish.invoke(cloud, binding);
      assertEquals(1, restartRequests[0], "explicit stop supersedes historical recovery intent");
    } finally {
      Lizzie.engineManager = oldManager;
      Lizzie.frame = oldFrame;
    }
  }

  @Test
  void partialBusyReleasesOutsideSelectionAndEndpointLocks() throws Exception {
    Field selection = EngineManager.class.getDeclaredField("ENGINE_SELECTION_STATE_LOCK");
    selection.setAccessible(true);
    Object selectionLock = selection.get(null);
    boolean[] released = {false};
    Leelaz first =
        new Leelaz(RemoteComputeConfig.COMMAND_ZHIZI) {
          @Override
          void releaseZhiziDisconnect(ExclusiveGtpLifecycleReservation reservation) {
            assertFalse(Thread.holdsLock(selectionLock));
            try {
              assertFalse(Thread.holdsLock(field(this, "engineArbitrationLock")));
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
            super.releaseZhiziDisconnect(reservation);
            released[0] = true;
          }
        };
    Leelaz last =
        new Leelaz(RemoteComputeConfig.COMMAND_ZHIZI) {
          @Override
          ExclusiveGtpLifecycleReservation reserveZhiziDisconnect() {
            return null;
          }
        };
    assertEquals(
        ZhiziEngineConnections.State.BUSY,
        new EngineManager(List.of(first, last)).disconnectZhiziEngines());
    assertTrue(released[0]);
    assertFalse(first.zhiziConnectionBusy());
  }

  @Test
  void releaseFailurePreservesTransportFailureAndReleasesEveryOwner() throws Exception {
    Leelaz first = engine(RemoteComputeConfig.COMMAND_ZHIZI);
    Leelaz last =
        new Leelaz(RemoteComputeConfig.COMMAND_ZHIZI) {
          @Override
          public void leela0110StopPonder() {}

          @Override
          void releaseZhiziDisconnect(ExclusiveGtpLifecycleReservation reservation) {
            super.releaseZhiziDisconnect(reservation);
            throw new IllegalArgumentException("controlled release failure");
          }
        };
    Transport transport = install(first);
    install(last);
    transport.failure = new IllegalStateException("controlled transport failure");
    var shutdown = ZhiziEngineConnections.prepare(List.of(first, last), false, e -> {});
    var failure = assertThrows(IllegalStateException.class, shutdown::finish);
    assertSame(transport.failure, failure);
    assertEquals(1, failure.getSuppressed().length);
    assertEquals("controlled release failure", failure.getSuppressed()[0].getMessage());
    assertFalse(first.zhiziConnectionBusy());
    assertFalse(last.zhiziConnectionBusy());
    assertFalse((boolean) field(last, "zhiziDisconnectReserved"));
  }

  @Test
  void releaseGapCannotAdmitASecondDisconnectOrDelayedStart() throws Exception {
    Leelaz cloud = engine(RemoteComputeConfig.COMMAND_ZHIZI);
    var first = cloud.reserveZhiziDisconnect();
    assertNotNull(first);
    try {
      // Model the exact release gap: lifecycle close has returned, but the disconnect owner's
      // finally block has not cleared its temporary start gate yet. No sleeps or concurrent I/O.
      first.close();
      assertFalse(cloud.hasExclusiveGtpWorkInProgress());
      assertTrue(cloud.zhiziConnectionBusy());
      assertNull(cloud.reserveZhiziDisconnect());
      assertThrows(IOException.class, () -> cloud.startEngine(0));
    } finally {
      cloud.releaseZhiziDisconnect(first);
    }
    assertFalse(cloud.zhiziConnectionBusy());
    var second = cloud.reserveZhiziDisconnect();
    assertNotNull(second);
    cloud.releaseZhiziDisconnect(second);
  }

  private static Leelaz engine(String command) throws Exception {
    return new Leelaz(command) {
      @Override
      public void leela0110StopPonder() {}
    };
  }

  private static ZhiziEngineConnections.State state(Leelaz engine) {
    return ZhiziEngineConnections.state(List.of(engine), false);
  }

  private static Transport install(Leelaz engine) throws Exception {
    Transport transport = new Transport();
    set(engine, "remoteTransport", transport);
    Method method = Leelaz.class.getDeclaredMethod("currentReaderStreamBinding");
    method.setAccessible(true);
    method.invoke(engine);
    engine.started = true;
    engine.isLoaded = true;
    return transport;
  }

  private static Object field(Object engine, String name) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(engine);
  }

  private static void set(Object engine, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(engine, value);
  }

  private static final class Transport implements EngineTransport {
    boolean open = true;
    int closes;
    RuntimeException failure;
    boolean recoveryRequested;

    public boolean isRecoveryRequested() {
      return recoveryRequested;
    }

    public void start() {}

    public void abort() {
      close();
    }

    public InputStream stdout() {
      return InputStream.nullInputStream();
    }

    public OutputStream stdin() {
      return OutputStream.nullOutputStream();
    }

    public InputStream stderr() {
      return InputStream.nullInputStream();
    }

    public boolean isOpen() {
      return open;
    }

    public String description() {
      return "offline fixture";
    }

    public void close() {
      closes++;
      if (failure != null) throw failure;
      open = false;
    }
  }
}
