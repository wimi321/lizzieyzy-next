package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KataGoRuntimeHelperBenchmarkLeaseTest {

  @Test
  void restoreAfterEngineSwitchRestartsOnlyThePausedEngine() throws Exception {
    Config previousConfig = Lizzie.config;
    Leelaz previousEngine = Lizzie.leelaz;
    EngineManager previousManager = Lizzie.engineManager;
    LizzieFrame previousFrame = Lizzie.frame;
    boolean previousEmpty = EngineManager.isEmpty;
    boolean previousEngineGame = EngineManager.isEngineGame;
    int previousEngineNo = EngineManager.currentEngineNo;
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("katago-benchmark-identity"));
    RecordingBenchmarkLeelaz pausedEngine = new RecordingBenchmarkLeelaz();
    RecordingBenchmarkLeelaz selectedEngine = new RecordingBenchmarkLeelaz();
    try {
      Lizzie.config = config;
      Lizzie.leelaz = pausedEngine;
      Lizzie.engineManager = engineManager(List.of(pausedEngine, selectedEngine));
      Lizzie.frame = null;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.currentEngineNo = 0;

      KataGoRuntimeHelper.BenchmarkPauseResult pause =
          KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();
      assertTrue(pause.accepted());

      Lizzie.leelaz = selectedEngine;
      EngineManager.currentEngineNo = 1;
      KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);

      assertEquals(1, pausedEngine.restartCount);
      assertEquals(0, pausedEngine.lastRestartIndex);
      assertEquals(2, pausedEngine.reservationAttempts);
      assertEquals(0, selectedEngine.restartCount);
      assertEquals(0, selectedEngine.normalQuitCount);
      assertEquals(0, selectedEngine.shutdownCount);
      assertTrue(selectedEngine.isStarted());
    } finally {
      if (KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed()) {
        KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);
      }
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousEngine;
      Lizzie.engineManager = previousManager;
      Lizzie.frame = previousFrame;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  @Test
  void currentPausedEngineRestartsWithSavedPonderIntent() throws Exception {
    try (BenchmarkEnvironment environment = new BenchmarkEnvironment(1)) {
      RecordingBenchmarkLeelaz pausedEngine = environment.engine(0);
      pausedEngine.Pondering();
      pausedEngine.ponderingCallCount = 0;

      KataGoRuntimeHelper.BenchmarkPauseResult pause = environment.pause(0);
      int pausePonderCalls = pausedEngine.ponderingCallCount;
      KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(pause.analysisWasPondering());

      assertTrue(pause.accepted());
      assertTrue(pause.analysisWasPondering());
      assertEquals(pausePonderCalls + 1, pausedEngine.ponderingCallCount);
      assertEquals(2, pausedEngine.reservationAttempts);
      assertEquals(1, pausedEngine.restartCount);
      assertEquals(0, pausedEngine.lastRestartIndex);
      assertFalse(pausedEngine.hasExclusiveGtpWorkInProgress());
    }
  }

  @Test
  void suppressionStaysUntilPausedReservationThenAllowsSelectedForegroundLease() throws Exception {
    try (BenchmarkEnvironment environment = new BenchmarkEnvironment(2)) {
      RecordingBenchmarkLeelaz pausedEngine = environment.engine(0);
      RecordingBenchmarkLeelaz selectedEngine = environment.engine(1);
      pausedEngine.restartGate = new CountDownLatch(1);
      assertTrue(environment.pause(0).accepted());
      pausedEngine.prepareReservationGate();
      environment.select(1);
      AtomicReference<Throwable> workerFailure = new AtomicReference<>();
      Thread restoreWorker =
          new Thread(
              () -> {
                try {
                  KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);
                } catch (Throwable failure) {
                  workerFailure.set(failure);
                }
              },
              "benchmark-restore-race-test");
      boolean selectedLeaseStarted = false;
      try {
        restoreWorker.start();
        assertTrue(pausedEngine.reservationEntered.await(1, TimeUnit.SECONDS));
        assertTrue(KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed());
        assertEquals(
            Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE,
            selectedEngine.previewForegroundAnalysisLeaseAvailability());

        pausedEngine.reservationGate.countDown();
        assertTrue(pausedEngine.restartEntered.await(1, TimeUnit.SECONDS));
        assertFalse(KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed());
        assertEquals(
            Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
            selectedEngine.previewForegroundAnalysisLeaseAvailability());
        Leelaz.ForegroundAnalysisLeaseAcquisition selectedLease =
            selectedEngine.acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {});
        assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, selectedLease.availability());
        assertNotNull(selectedLease.lease());
        selectedLeaseStarted = true;

        pausedEngine.restartGate.countDown();
        restoreWorker.join(1000L);
        assertFalse(restoreWorker.isAlive());
        assertNull(workerFailure.get());
        assertEquals(1, pausedEngine.restartCount);
        assertEquals(0, selectedEngine.restartCount);
        assertTrue(selectedEngine.hasForegroundAnalysisLeaseWorkInProgress());
      } finally {
        pausedEngine.reservationGate.countDown();
        pausedEngine.restartGate.countDown();
        restoreWorker.join(1000L);
        if (selectedLeaseStarted) {
          selectedEngine.endExclusiveGtpSession();
        }
      }
    }
  }

  @Test
  void rejectedPausedReservationAbandonsRestoreWithoutRetry() throws Exception {
    try (BenchmarkEnvironment environment = new BenchmarkEnvironment(1)) {
      RecordingBenchmarkLeelaz pausedEngine = environment.engine(0);
      assertTrue(environment.pause(0).accepted());
      pausedEngine.rejectReservation = true;

      KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);
      KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);

      assertFalse(KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed());
      assertEquals(2, pausedEngine.reservationAttempts);
      assertEquals(0, pausedEngine.restartCount);
      assertNull(pausedEngine.pendingRestartCompletion.get());
      assertFalse(pausedEngine.hasExclusiveGtpWorkInProgress());
    }
  }

  @Test
  void replacedSavedIndexAbandonsRestoreWithoutRestartingAnyEngine() throws Exception {
    try (BenchmarkEnvironment environment = new BenchmarkEnvironment(2)) {
      RecordingBenchmarkLeelaz pausedEngine = environment.engine(0);
      RecordingBenchmarkLeelaz selectedEngine = environment.engine(1);
      RecordingBenchmarkLeelaz replacementEngine = new RecordingBenchmarkLeelaz();
      assertTrue(environment.pause(0).accepted());
      environment.manager.engineList.set(0, replacementEngine);
      environment.select(1);

      KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);

      assertFalse(KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed());
      assertEquals(1, pausedEngine.reservationAttempts);
      assertEquals(0, pausedEngine.restartCount);
      assertEquals(0, replacementEngine.restartCount);
      assertEquals(0, selectedEngine.restartCount);
    }
  }

  @Test
  void managerReplacementDuringReservationAcquisitionAbandonsRestore() throws Exception {
    try (BenchmarkEnvironment environment = new BenchmarkEnvironment(1)) {
      RecordingBenchmarkLeelaz pausedEngine = environment.engine(0);
      assertTrue(environment.pause(0).accepted());
      pausedEngine.prepareReservationGate();
      AtomicReference<Throwable> workerFailure = new AtomicReference<>();
      Thread restoreWorker =
          new Thread(
              () -> {
                try {
                  KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);
                } catch (Throwable failure) {
                  workerFailure.set(failure);
                }
              },
              "benchmark-restore-identity-revalidation-test");
      try {
        restoreWorker.start();
        assertTrue(pausedEngine.reservationEntered.await(1, TimeUnit.SECONDS));
        assertTrue(KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed());
        Lizzie.engineManager = engineManager(new ArrayList<>(List.of(pausedEngine)));

        pausedEngine.reservationGate.countDown();
        restoreWorker.join(1000L);

        assertFalse(restoreWorker.isAlive());
        assertNull(workerFailure.get());
        assertFalse(KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed());
        assertEquals(2, pausedEngine.reservationAttempts);
        assertEquals(0, pausedEngine.restartCount);
        assertFalse(pausedEngine.hasExclusiveGtpWorkInProgress());
      } finally {
        pausedEngine.reservationGate.countDown();
        restoreWorker.join(1000L);
      }
    }
  }

  @Test
  void synchronousRestartFailureReleasesReservationWithoutDelayedWork() throws Exception {
    try (BenchmarkEnvironment environment = new BenchmarkEnvironment(1)) {
      RecordingBenchmarkLeelaz pausedEngine = environment.engine(0);
      pausedEngine.throwBeforeRestartScheduling = true;
      assertTrue(environment.pause(0).accepted());

      KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);
      KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);

      assertFalse(KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed());
      assertEquals(2, pausedEngine.reservationAttempts);
      assertEquals(1, pausedEngine.restartCount);
      assertNull(pausedEngine.pendingRestartCompletion.get());
      assertFalse(pausedEngine.hasExclusiveGtpWorkInProgress());
      Leelaz.EngineModeReservation nextReservation = pausedEngine.beginEngineModeReservation();
      assertNotNull(nextReservation);
      nextReservation.close();
    }
  }

  @Test
  void asynchronousRestartKeepsReservationUntilExistingCompletionCallback() throws Exception {
    try (BenchmarkEnvironment environment = new BenchmarkEnvironment(1)) {
      RecordingBenchmarkLeelaz pausedEngine = environment.engine(0);
      pausedEngine.deferRestartCompletion = true;
      assertTrue(environment.pause(0).accepted());

      KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);

      assertFalse(KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed());
      assertEquals(1, pausedEngine.restartCount);
      assertNotNull(pausedEngine.pendingRestartCompletion.get());
      assertTrue(pausedEngine.hasExclusiveGtpWorkInProgress());
      assertNull(pausedEngine.beginEngineModeReservation());

      pausedEngine.completeDeferredRestart();

      assertFalse(pausedEngine.hasExclusiveGtpWorkInProgress());
      Leelaz.EngineModeReservation nextReservation = pausedEngine.beginEngineModeReservation();
      assertNotNull(nextReservation);
      nextReservation.close();
    }
  }

  @Test
  void duplicateRestoreAndSecondPauseDoNotMixEngineGenerations() throws Exception {
    try (BenchmarkEnvironment environment = new BenchmarkEnvironment(2)) {
      RecordingBenchmarkLeelaz firstEngine = environment.engine(0);
      RecordingBenchmarkLeelaz secondEngine = environment.engine(1);
      assertTrue(environment.pause(0).accepted());
      KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);
      KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);

      assertEquals(1, firstEngine.restartCount);
      assertEquals(0, secondEngine.restartCount);

      assertTrue(environment.pause(1).accepted());
      KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);

      assertEquals(1, firstEngine.restartCount);
      assertEquals(1, secondEngine.restartCount);
      assertEquals(1, secondEngine.lastRestartIndex);
    }
  }

  @Test
  void activeForegroundLeaseRejectsBenchmarkWithoutChangingPauseState() throws Exception {
    Config previousConfig = Lizzie.config;
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("katago-benchmark-lease"));
    Leelaz engine = reusableKatagoEngine();
    try {
      Lizzie.config = config;
      Lizzie.leelaz = engine;
      Lizzie.frame = null;
      config.showPonderLimitedTips = true;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));

      KataGoRuntimeHelper.BenchmarkPauseResult result =
          KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();

      assertFalse(result.accepted());
      assertFalse(result.analysisWasPondering());
      assertTrue(config.showPonderLimitedTips);
      assertFalse(KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed());
      assertTrue(engine.isLoaded());
      assertTrue(engine.isStarted());
    } finally {
      engine.endExclusiveGtpSession();
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void acceptedBenchmarkPauseBlocksNewForegroundLeaseUntilRestore() throws Exception {
    Config previousConfig = Lizzie.config;
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("katago-benchmark-reservation"));
    Leelaz engine = reusableKatagoEngine();
    boolean pauseAccepted = false;
    try {
      Lizzie.config = config;
      Lizzie.leelaz = null;
      Lizzie.frame = null;

      KataGoRuntimeHelper.BenchmarkPauseResult result =
          KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();
      pauseAccepted = result.accepted();
      Lizzie.leelaz = engine;

      assertTrue(result.accepted());
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE,
          engine.previewForegroundAnalysisLeaseAvailability());
    } finally {
      if (pauseAccepted) {
        KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);
      }
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void benchmarkPauseClaimsActiveTrackingAndRestoresSavedPonderIntentInOneClick() throws Exception {
    try (BenchmarkEnvironment environment = new BenchmarkEnvironment(1)) {
      RecordingBenchmarkLeelaz engine = environment.engine(0);
      ByteArrayOutputStream output = installOutput(engine);
      engine.Pondering();
      engine.ponderingCallCount = 0;
      Leelaz.TrackingStreamLeaseAcquisition tracking =
          engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(engine, "=800000000");
      assertTrue(dispatchExclusiveLine(engine, ""));

      KataGoRuntimeHelper.BenchmarkPauseResult pause = environment.pause(0);

      assertTrue(pause.accepted());
      assertTrue(pause.analysisWasPondering());
      int pausePonderCalls = engine.ponderingCallCount;
      assertEquals(Leelaz.TrackingReleaseDisposition.CLEARED, tracking.lease().disposition());
      assertEquals("800000000 stop\n800000001 stop\n", output.toString(StandardCharsets.UTF_8));

      assertTrue(dispatchExclusiveLine(engine, "=800000001"));
      assertTrue(dispatchExclusiveLine(engine, ""));
      KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(pause.analysisWasPondering());
      assertEquals(2, engine.reservationAttempts);
      assertEquals(1, engine.restartCount);
      assertEquals(pausePonderCalls + 1, engine.ponderingCallCount);
    }
  }

  @Test
  void nonShutdownBenchmarkKeepsTrackingPausedAndRestoresSavedPonderIntent() throws Exception {
    try (BenchmarkEnvironment environment = new BenchmarkEnvironment(1)) {
      RecordingBenchmarkLeelaz engine = environment.engine(0);
      installOutput(engine);
      engine.Pondering();
      engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
      processCommandResponse(engine, "=800000000");
      assertTrue(dispatchExclusiveLine(engine, ""));
      EngineManager.isEmpty = true;

      KataGoRuntimeHelper.BenchmarkPauseResult pause = environment.pause(0);

      assertTrue(pause.accepted());
      assertTrue(pause.analysisWasPondering());
      assertFalse(
          engine.isPondering(), "benchmark must not restart ponder while tracking settles.");

      assertTrue(dispatchExclusiveLine(engine, "=800000001"));
      assertTrue(dispatchExclusiveLine(engine, ""));
      KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(pause.analysisWasPondering());

      assertTrue(engine.isPondering());
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
    }
  }

  @Test
  void secondBenchmarkPauseIsRejectedWithoutClearingFirstPauseState() throws Exception {
    Config previousConfig = Lizzie.config;
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("katago-benchmark-reentry"));
    boolean pauseAccepted = false;
    try {
      Lizzie.config = config;
      Lizzie.leelaz = null;
      Lizzie.frame = null;

      KataGoRuntimeHelper.BenchmarkPauseResult first =
          KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();
      pauseAccepted = first.accepted();
      KataGoRuntimeHelper.BenchmarkPauseResult second =
          KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();

      assertTrue(first.accepted());
      assertFalse(second.accepted());
      assertTrue(KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed());
    } finally {
      if (pauseAccepted) {
        KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);
      }
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
    }
    assertFalse(KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed());
  }

  private static EngineManager engineManager(List<Leelaz> engines) throws Exception {
    Constructor<EngineManager> constructor = EngineManager.class.getDeclaredConstructor(List.class);
    constructor.setAccessible(true);
    return constructor.newInstance(engines);
  }

  private static final class BenchmarkEnvironment implements AutoCloseable {
    private final Config previousConfig = Lizzie.config;
    private final Leelaz previousEngine = Lizzie.leelaz;
    private final EngineManager previousManager = Lizzie.engineManager;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final boolean previousEmpty = EngineManager.isEmpty;
    private final boolean previousEngineGame = EngineManager.isEngineGame;
    private final int previousEngineNo = EngineManager.currentEngineNo;
    private final List<RecordingBenchmarkLeelaz> engines = new ArrayList<>();
    private final EngineManager manager;

    private BenchmarkEnvironment(int engineCount) throws Exception {
      Config config =
          ConfigTestHelper.createForTests(Files.createTempDirectory("katago-benchmark-restore"));
      for (int i = 0; i < engineCount; i++) {
        engines.add(new RecordingBenchmarkLeelaz());
      }
      manager = engineManager(new ArrayList<Leelaz>(engines));
      Lizzie.config = config;
      Lizzie.engineManager = manager;
      Lizzie.frame = null;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      select(0);
    }

    private RecordingBenchmarkLeelaz engine(int index) {
      return engines.get(index);
    }

    private void select(int index) {
      Lizzie.leelaz = engines.get(index);
      EngineManager.currentEngineNo = index;
    }

    private KataGoRuntimeHelper.BenchmarkPauseResult pause(int index) {
      select(index);
      return KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();
    }

    @Override
    public void close() {
      if (KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed()) {
        KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);
      }
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousEngine;
      Lizzie.engineManager = previousManager;
      Lizzie.frame = previousFrame;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  private static final class RecordingBenchmarkLeelaz extends Leelaz {
    private CountDownLatch reservationEntered = new CountDownLatch(1);
    private final CountDownLatch restartEntered = new CountDownLatch(1);
    private final AtomicReference<Runnable> pendingRestartCompletion = new AtomicReference<>();
    private CountDownLatch reservationGate;
    private CountDownLatch restartGate;
    private boolean rejectReservation;
    private boolean throwBeforeRestartScheduling;
    private boolean deferRestartCompletion;
    private int reservationAttempts;
    private int restartCount;
    private int lastRestartIndex = -1;
    private int normalQuitCount;
    private int shutdownCount;
    private int ponderingCallCount;

    private RecordingBenchmarkLeelaz() throws Exception {
      super("");
      prepareReusableKatagoEngine(this);
    }

    private void prepareReservationGate() {
      reservationEntered = new CountDownLatch(1);
      reservationGate = new CountDownLatch(1);
    }

    @Override
    public ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation() {
      reservationAttempts++;
      reservationEntered.countDown();
      await(reservationGate);
      return rejectReservation ? null : super.beginExclusiveGtpLifecycleReservation();
    }

    @Override
    public void Pondering() {
      ponderingCallCount++;
      super.Pondering();
    }

    @Override
    public void normalQuit() {
      normalQuitCount++;
    }

    @Override
    public void shutdown() {
      shutdownCount++;
      started = false;
      isLoaded = false;
    }

    @Override
    public void restartClosedEngine(int index) {
      restartCount++;
      lastRestartIndex = index;
    }

    @Override
    public void restartClosedEngine(int index, Runnable afterBoardRestore) throws IOException {
      restartClosedEngine(index);
      restartEntered.countDown();
      await(restartGate);
      if (throwBeforeRestartScheduling) {
        throw new IOException("controlled restart failure before scheduling");
      }
      if (deferRestartCompletion) {
        pendingRestartCompletion.set(afterBoardRestore);
      } else if (afterBoardRestore != null) {
        afterBoardRestore.run();
      }
    }

    private void completeDeferredRestart() {
      Runnable completion = pendingRestartCompletion.getAndSet(null);
      assertNotNull(completion);
      completion.run();
    }

    private static void await(CountDownLatch gate) {
      if (gate == null) {
        return;
      }
      try {
        gate.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("controlled benchmark gate interrupted", interrupted);
      }
    }
  }

  private static Leelaz reusableKatagoEngine() throws Exception {
    return prepareReusableKatagoEngine(new Leelaz(""));
  }

  private static ByteArrayOutputStream installOutput(Leelaz engine) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    Field field = Leelaz.class.getDeclaredField("outputStream");
    field.setAccessible(true);
    field.set(engine, new BufferedOutputStream(output));
    return output;
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

  private static <T extends Leelaz> T prepareReusableKatagoEngine(T engine) throws Exception {
    engine.isLoaded = true;
    engine.started = true;
    engine.isKatago = true;
    engine.commandLists.addAll(
        List.of(
            "stop",
            "boardsize",
            "komi",
            "kata-get-rules",
            "kata-set-rules",
            "clear_board",
            "play",
            "set_position",
            "kata-analyze"));
    Field capabilityField = Leelaz.class.getDeclaredField("endGetCommandList");
    capabilityField.setAccessible(true);
    capabilityField.set(engine, true);
    Field outputField = Leelaz.class.getDeclaredField("outputStream");
    outputField.setAccessible(true);
    outputField.set(engine, new BufferedOutputStream(new ByteArrayOutputStream()));
    return engine;
  }
}
