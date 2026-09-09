package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.ExactSnapshotEngineRestore;
import featurecat.lizzie.analysis.ExactSnapshotRestoreProtocolFixture;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.gtpconfig.GtpConfigurationProbe;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
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
    Board previousBoard = Lizzie.board;
    EngineManager previousManager = Lizzie.engineManager;
    LizzieFrame previousFrame = Lizzie.frame;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("katago-benchmark-identity"));
    RecordingBenchmarkLeelaz pausedEngine = new RecordingBenchmarkLeelaz();
    RecordingBenchmarkLeelaz selectedEngine = new RecordingBenchmarkLeelaz();
    try {
      Lizzie.config = config;
      Lizzie.board = null;
      Lizzie.leelaz = pausedEngine;
      Lizzie.engineManager = engineManager(List.of(pausedEngine, selectedEngine));
      Lizzie.frame = null;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;

      KataGoRuntimeHelper.BenchmarkPauseResult pause =
          KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();
      assertTrue(pause.accepted());

      Lizzie.leelaz = selectedEngine;
      EngineManager.currentEngineNo = 1;
      KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);
      awaitRestartSettlement(pausedEngine);

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
      Lizzie.board = previousBoard;
      Lizzie.engineManager = previousManager;
      Lizzie.frame = previousFrame;
      EngineManager.isEmpty = previousEmpty;
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
      assertTrue(pause.computeIsolated());
      assertEquals(pausePonderCalls + 1, pausedEngine.ponderingCallCount);
      assertEquals(2, pausedEngine.reservationAttempts);
      assertEquals(1, pausedEngine.restartCount);
      assertEquals(0, pausedEngine.lastRestartIndex);
      awaitRestartSettlement(pausedEngine);
      assertFalse(pausedEngine.hasExclusiveGtpWorkInProgress());
    }
  }

  @Test
  void benchmarkRestartCapturesPreparedRestoreBeforePonderAndStart() throws Exception {
    Config previousConfig = Lizzie.config;
    Board previousBoard = Lizzie.board;
    Leelaz previousEngine = Lizzie.leelaz;
    EngineManager previousManager = Lizzie.engineManager;
    LizzieFrame previousFrame = Lizzie.frame;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Board.boardWidth = 3;
    Board.boardHeight = 3;
    BoardHistoryList history = new BoardHistoryList(benchmarkSnapshotRoot());
    history.getGameInfo().setKomiNoMenu(6.5);
    history.add(benchmarkMoveNode(2, 2, Stone.BLACK, true, 4));
    PreparedBenchmarkBoard board = preparedBenchmarkBoard(history);
    PreparedBenchmarkLeelaz engine = new PreparedBenchmarkLeelaz();
    try {
      Lizzie.config = ConfigTestHelper.createForTests(Files.createTempDirectory("katago-prepared"));
      Lizzie.board = board;
      Lizzie.leelaz = engine;
      Lizzie.engineManager = engineManager(List.of(engine));
      Lizzie.frame = allocate(LizzieFrame.class);
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      engine.Pondering();
      engine.mutateOnReservation = () -> mutateBenchmarkHistory(history);

      KataGoRuntimeHelper.BenchmarkPauseResult pause =
          KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();
      assertTrue(pause.accepted());
      engine.mutateOnPonder = () -> mutateBenchmarkHistory(history);
      engine.mutateOnStart = () -> mutateBenchmarkHistory(history);
      KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(pause.analysisWasPondering());

      assertTrue(board.restoreCompleted.await(2, TimeUnit.SECONDS));
      awaitRestartSettlement(engine);
      assertTrue(board.preparedRestoreReceived);
      assertFalse(board.genericRestoreReceived);
      assertNull(board.restoreFailure.get());
      assertEquals(2, engine.loadedSgfs.size(), engine.loadedSgfs.toString());
      assertTrue(engine.loadedSgfs.get(0).contains("AB[aa]"), engine.loadedSgfs.toString());
      assertTrue(engine.loadedSgfs.get(0).contains("KM[6.5]"), engine.loadedSgfs.toString());
      String catchUpSgf = engine.loadedSgfs.get(1);
      assertFalse(catchUpSgf.contains("AB[aa]"), engine.loadedSgfs.toString());
      assertTrue(catchUpSgf.contains("AW[ba]"), engine.loadedSgfs.toString());
      assertTrue(catchUpSgf.contains("KM[7.5]"), engine.loadedSgfs.toString());
      List<String> restoreCommands = engine.transport.commands();
      assertEquals(
          2,
          restoreCommands.stream().filter(command -> command.equals("play B C1")).count(),
          restoreCommands.toString());
      assertTrue(
          restoreCommands.lastIndexOf("play B C1")
              > lastCommandIndexStartingWith(restoreCommands, "loadsgf "),
          restoreCommands.toString());
      Leelaz.EngineModeReservation nextReservation = engine.beginEngineModeReservation();
      assertNotNull(nextReservation);
      nextReservation.close();
    } finally {
      if (KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed()) {
        KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);
      }
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousEngine;
      Lizzie.engineManager = previousManager;
      Lizzie.frame = previousFrame;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
    }
  }

  @Test
  void suppressionStaysUntilPausedReservationThenAllowsSelectedForegroundLease()
      throws Exception {
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
      awaitRestartSettlement(pausedEngine);
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
        awaitRestartSettlement(pausedEngine);
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
      awaitRestartSettlement(pausedEngine);
      assertFalse(pausedEngine.hasExclusiveGtpWorkInProgress());
      Leelaz.EngineModeReservation nextReservation = pausedEngine.beginEngineModeReservation();
      assertNotNull(nextReservation);
      nextReservation.close();
    }
  }

  @Test
  void asynchronousRestartKeepsCompletionClaimUntilFinalFence() throws Exception {
    try (BenchmarkEnvironment environment = new BenchmarkEnvironment(1)) {
      RecordingBenchmarkLeelaz pausedEngine = environment.engine(0);
      pausedEngine.deferRestartCompletion = true;
      assertTrue(environment.pause(0).accepted());

      KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);

      assertFalse(KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed());
      assertEquals(1, pausedEngine.restartCount);
      pausedEngine.awaitDeferredRestartFence();
      assertTrue(
          pausedEngine.hasExclusiveGtpWorkInProgress(),
          pausedEngine.restartTransport.rawCommands().toString());
      assertNull(pausedEngine.beginEngineModeReservation());

      pausedEngine.completeDeferredRestart();

      awaitRestartSettlement(pausedEngine);
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
    Leelaz.ForegroundAnalysisLease foregroundLease = null;
    try {
      Lizzie.config = config;
      Lizzie.leelaz = engine;
      Lizzie.frame = null;
      config.showPonderLimitedTips = true;
      Leelaz.ForegroundAnalysisLeaseAcquisition foreground =
          engine.acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {});
      assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, foreground.availability());
      assertNotNull(foreground.lease());
      foregroundLease = foreground.lease();

      KataGoRuntimeHelper.BenchmarkPauseResult result =
          KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();

      assertFalse(result.accepted());
      assertFalse(result.analysisWasPondering());
      assertTrue(config.showPonderLimitedTips);
      assertFalse(KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed());
      assertTrue(engine.isLoaded());
      assertTrue(engine.isStarted());
    } finally {
      if (foregroundLease != null) {
        engine.endExclusiveGtpSession();
      }
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
      assertTrue(result.computeIsolated());
      assertFalse(KataGoRuntimeHelper.isLayeredBenchmarkComputeIsolated());
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
  void acceptedBenchmarkPauseBlocksLocalGtpConfigurationProbe() throws Exception {
    Config previousConfig = Lizzie.config;
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("katago-benchmark-probe-gate"));
    boolean pauseAccepted = false;
    try {
      Lizzie.config = config;
      Lizzie.leelaz = null;
      Lizzie.frame = null;

      KataGoRuntimeHelper.BenchmarkPauseResult result =
          KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();
      pauseAccepted = result.accepted();
      IOException error =
          assertThrows(
              IOException.class,
              () -> new GtpConfigurationProbe().inspect("definitely-missing-local-engine"));

      assertTrue(result.accepted());
      assertTrue(error.getMessage().contains("tuning"));
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
  void shutdownWaitReportsOnlyConfirmedIsolation() throws Exception {
    try (BenchmarkEnvironment environment = new BenchmarkEnvironment(1)) {
      RecordingBenchmarkLeelaz engine = environment.engine(0);

      assertFalse(KataGoRuntimeHelper.waitForEngineShutdown(engine, 1L));

      engine.shutdown();

      assertTrue(KataGoRuntimeHelper.waitForEngineShutdown(engine, 1L));
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
    private final Board previousBoard = Lizzie.board;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final boolean previousEmpty = EngineManager.isEmpty;
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
      Lizzie.board = null;
      EngineManager.isEmpty = false;
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
      for (RecordingBenchmarkLeelaz engine : engines) {
        awaitRestartSettlement(engine);
      }
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousEngine;
      Lizzie.engineManager = previousManager;
      Lizzie.frame = previousFrame;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
    }
  }

  private static final class RecordingBenchmarkLeelaz extends Leelaz {
    private CountDownLatch reservationEntered = new CountDownLatch(1);
    private final CountDownLatch restartEntered = new CountDownLatch(1);
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
    private boolean processDead;
    private volatile ExactSnapshotRestoreProtocolFixture.Transport restartTransport;

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
    public AutomaticRestartAttempt beginAutomaticEngineRestartAttempt() {
      reservationAttempts++;
      reservationEntered.countDown();
      await(reservationGate);
      return rejectReservation ? null : super.beginAutomaticEngineRestartAttempt();
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
      processDead = true;
      started = false;
      isLoaded = false;
    }

    @Override
    public boolean isProcessDead() {
      return processDead;
    }

    @Override
    public void startEngine(int index) {
      restartCount++;
      lastRestartIndex = index;
      processDead = false;
      restartEntered.countDown();
      await(restartGate);
      if (throwBeforeRestartScheduling) {
        throw new IllegalStateException("controlled restart failure before scheduling");
      }
      if (Lizzie.frame == null) {
        try {
          Lizzie.frame = allocate(LizzieFrame.class);
        } catch (Exception failure) {
          throw new IllegalStateException(failure);
        }
      }
      restartTransport =
          ExactSnapshotRestoreProtocolFixture.install(
              this,
              command -> {
                if ("name".equals(command)) {
                  return deferRestartCompletion
                      ? null
                      : ExactSnapshotRestoreProtocolFixture.Response.error(
                          "controlled benchmark fence settlement");
                }
                return ExactSnapshotRestoreProtocolFixture.Response.success();
              });
      started = true;
      isLoaded = true;
      isCheckingName = false;
      isNormalEnd = false;
      setCapabilityDiscoveryComplete(this, true);
    }

    private void awaitDeferredRestartFence() throws Exception {
      ExactSnapshotRestoreProtocolFixture.Transport transport = restartTransport;
      long transportDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (transport == null && System.nanoTime() < transportDeadline) {
        Thread.sleep(10L);
        transport = restartTransport;
      }
      assertNotNull(transport);
      long commandDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (System.nanoTime() < commandDeadline) {
        if (transport.rawCommands().stream()
            .anyMatch(command -> command.endsWith(" name") || "name".equals(command))) {
          return;
        }
        Thread.sleep(10L);
      }
      throw new AssertionError(transport.rawCommands().toString());
    }

    private void completeDeferredRestart() throws Exception {
      awaitDeferredRestartFence();
      ExactSnapshotRestoreProtocolFixture.Transport transport = restartTransport;
      String rawName =
          transport.rawCommands().stream()
              .filter(command -> command.endsWith(" name") || "name".equals(command))
              .findFirst()
              .orElseThrow();
      int split = rawName.indexOf(' ');
      String commandId = split > 0 ? rawName.substring(0, split) : "";
      processCommandResponse(this, "?" + commandId + " controlled benchmark fence settlement");
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

  private static final class PreparedBenchmarkLeelaz extends Leelaz {
    private Runnable mutateOnReservation;
    private Runnable mutateOnPonder;
    private Runnable mutateOnStart;
    private final List<String> loadedSgfs = new ArrayList<>();
    private ExactSnapshotRestoreProtocolFixture.Transport transport;

    private PreparedBenchmarkLeelaz() throws Exception {
      super("controlled-engine");
      prepareReusableKatagoEngine(this);
      installProtocol();
    }

    private void installProtocol() {
      transport =
          ExactSnapshotRestoreProtocolFixture.install(
              this,
              command -> {
                if (command.startsWith("loadsgf ")) {
                  loadedSgfs.add(
                      Files.readString(Path.of(command.substring("loadsgf ".length()).trim())));
                }
                return ExactSnapshotRestoreProtocolFixture.Response.success();
              });
    }

    @Override
    public AutomaticRestartAttempt beginAutomaticEngineRestartAttempt() {
      AutomaticRestartAttempt attempt = super.beginAutomaticEngineRestartAttempt();
      runMutation(() -> mutateOnReservation);
      return attempt;
    }

    @Override
    public void Pondering() {
      runMutation(() -> mutateOnPonder);
      super.Pondering();
    }

    @Override
    public void startEngine(int index) {
      runMutation(() -> mutateOnStart);
      started = true;
      isLoaded = true;
      isCheckingName = false;
      installProtocol();
      try {
        Field field = Leelaz.class.getDeclaredField("endGetCommandList");
        field.setAccessible(true);
        field.set(this, true);
      } catch (ReflectiveOperationException failure) {
        throw new IllegalStateException(failure);
      }
    }

    @Override
    public void normalQuit() {}

    @Override
    public void shutdown() {
      started = false;
      isLoaded = false;
    }

    private void runMutation(java.util.function.Supplier<Runnable> mutationSupplier) {
      Runnable mutation = mutationSupplier.get();
      if (mutation != null) {
        mutation.run();
        if (mutation == mutateOnReservation) {
          mutateOnReservation = null;
        }
        if (mutation == mutateOnPonder) {
          mutateOnPonder = null;
        }
        if (mutation == mutateOnStart) {
          mutateOnStart = null;
        }
      }
    }
  }

  private static final class PreparedBenchmarkBoard extends Board {
    private CountDownLatch restoreCompleted;
    private boolean preparedRestoreReceived;
    private boolean genericRestoreReceived;
    private AtomicReference<Throwable> restoreFailure = new AtomicReference<>();

    @Override
    public void resendMoveToEngine(Leelaz leelaz, boolean loadEngine) {
      genericRestoreReceived = true;
      restoreCompleted.countDown();
    }

    @Override
    public void resendMoveToEngine(
        Leelaz leelaz,
        boolean loadEngine,
        ExactSnapshotEngineRestore.PreparedRestore preparedRestore) {
      preparedRestoreReceived = true;
      try {
        preparedRestore.execute();
      } catch (Throwable failure) {
        restoreFailure.set(failure);
        throw failure;
      } finally {
        restoreCompleted.countDown();
      }
    }
  }

  private static Leelaz reusableKatagoEngine() throws Exception {
    return prepareReusableKatagoEngine(new Leelaz(""));
  }

  private static PreparedBenchmarkBoard preparedBenchmarkBoard(BoardHistoryList history)
      throws Exception {
    PreparedBenchmarkBoard board = allocate(PreparedBenchmarkBoard.class);
    board.restoreCompleted = new CountDownLatch(1);
    board.restoreFailure = new AtomicReference<>();
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    board.setHistory(history);
    return board;
  }

  private static BoardData benchmarkSnapshotRoot() {
    Stone[] stones = new Stone[9];
    java.util.Arrays.fill(stones, Stone.EMPTY);
    stones[Board.getIndex(0, 0)] = Stone.BLACK;
    stones[Board.getIndex(1, 0)] = Stone.WHITE;
    int[] moveNumbers = new int[9];
    moveNumbers[Board.getIndex(0, 0)] = 1;
    moveNumbers[Board.getIndex(1, 0)] = 2;
    return BoardData.snapshot(
        stones,
        java.util.Optional.of(new int[] {1, 0}),
        Stone.WHITE,
        false,
        benchmarkZobrist(stones),
        3,
        moveNumbers,
        0,
        0,
        50,
        0);
  }

  private static BoardData benchmarkMoveNode(
      int x, int y, Stone color, boolean blackToPlay, int moveNumber) {
    Stone[] stones = benchmarkSnapshotRoot().stones.clone();
    stones[Board.getIndex(x, y)] = color;
    return BoardData.move(
        stones,
        new int[] {x, y},
        color,
        blackToPlay,
        benchmarkZobrist(stones),
        moveNumber,
        new int[9],
        0,
        0,
        50,
        0);
  }

  private static Zobrist benchmarkZobrist(Stone[] stones) {
    Zobrist zobrist = new Zobrist();
    for (int x = 0; x < 3; x++) {
      for (int y = 0; y < 3; y++) {
        Stone stone = stones[Board.getIndex(x, y)];
        if (!stone.isEmpty()) {
          zobrist.toggleStone(x, y, stone);
        }
      }
    }
    return zobrist;
  }

  private static void mutateBenchmarkHistory(BoardHistoryList history) {
    history.getStart().getData().stones[Board.getIndex(0, 0)] = Stone.EMPTY;
    history.getGameInfo().setKomiNoMenu(7.5);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }


  private static void awaitRestartSettlement(Leelaz engine) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (engine.hasExclusiveGtpWorkInProgress() && System.nanoTime() < deadline) {
      try {
        Thread.sleep(10L);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
    assertFalse(engine.hasExclusiveGtpWorkInProgress());
  }

  private static int lastCommandIndexStartingWith(List<String> commands, String prefix) {
    int last = -1;
    for (int index = 0; index < commands.size(); index++) {
      if (commands.get(index).startsWith(prefix)) {
        last = index;
      }
    }
    return last;
  }

  private static void setCapabilityDiscoveryComplete(Leelaz engine, boolean complete) {
    try {
      Field field = Leelaz.class.getDeclaredField("endGetCommandList");
      field.setAccessible(true);
      field.set(engine, complete);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(failure);
    }
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

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static sun.misc.Unsafe loadUnsafe() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException failure) {
        throw new IllegalStateException(failure);
      }
    }
  }
}
