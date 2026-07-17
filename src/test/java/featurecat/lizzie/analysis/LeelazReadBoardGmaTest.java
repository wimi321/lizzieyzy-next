package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.rules.Board;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LeelazReadBoardGmaTest {
  @Test
  void supportsReadBoardGmaRequiresKatagoAndRequiredCommands() throws Exception {
    Leelaz engine = new Leelaz("");
    setBooleanField(engine, "endGetCommandList", true);
    engine.commandLists.add("kata-genmove_analyze");
    engine.commandLists.add("kata-get-param");
    engine.commandLists.add("kata-set-param");

    assertFalse(engine.supportsReadBoardGma(), "non-KataGo engine must not pass GMA gate.");

    engine.isKatago = true;
    assertTrue(engine.supportsReadBoardGma());

    engine.commandLists.remove("kata-set-param");
    assertFalse(
        engine.supportsReadBoardGma(), "GMA gate must require runtime ponder param support.");
  }

  @Test
  void genmoveAnalyzeForReadBoardMapsPositiveLimitsAndPonderSetting() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true);

      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled true",
              "kata-get-param maxTime",
              "kata-set-param maxTime 5",
              "kata-get-param maxVisits",
              "kata-set-param maxVisits 1000",
              "kata-genmove_analyze B 10"),
          output.commands());
      assertTrue(engine.isThinking);
    }
  }

  @Test
  void genmoveAnalyzeForReadBoardOmitsZeroLimitsAndDisablesPonder() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      engine.genmoveAnalyzeForReadBoard("W", 0, 0, false);

      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-genmove_analyze W 10"),
          output.commands());
    }
  }

  @Test
  void numberedKataGetParamResponseRestoresOnlyTheParameterValue() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 0, 0, false));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      engine.isThinking = false;
      engine.restoreReadBoardGmaRuntimeSettingsIfNeeded();

      assertTrue(output.commands().contains("kata-set-param ponderingEnabled true"));
      assertFalse(
          output.commands().stream()
              .anyMatch(command -> command.matches("kata-set-param ponderingEnabled \\d+ true")));
    }
  }

  @Test
  void genmoveAnalyzeForReadBoardRestoresPreviousLimitOverridesWhenLimitsAreZero()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      setReadBoardGmaParamState(engine, "readBoardGmaMaxTime", "2", true);
      setReadBoardGmaParamState(engine, "readBoardGmaMaxVisits", "800", true);
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      engine.genmoveAnalyzeForReadBoard("W", 0, 0, false);

      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-set-param maxTime 2",
              "kata-set-param maxVisits 800",
              "kata-genmove_analyze W 10"),
          output.commands());
    }
  }

  @Test
  void inSessionLimitRestoreErrorQuarantinesEngine() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      setReadBoardGmaParamState(engine, "readBoardGmaMaxTime", "2", true);
      setReadBoardGmaParamState(engine, "readBoardGmaMaxVisits", "800", true);
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      assertTrue(engine.genmoveAnalyzeForReadBoard("W", 0, 0, false));
      invokeProcessCommandResponseLine(
          engine, errorResponseFor(output.rawCommands(), "maxTime", "restore failed"));

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void restoreReadBoardGmaSearchLimitsWaitsForOriginalValuesWhenStopHappensEarly()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true);
      engine.restoreReadBoardGmaSearchLimitsIfNeeded();
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxTime"));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxVisits", "800"));

      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled true",
              "kata-get-param maxTime",
              "kata-set-param maxTime 5",
              "kata-get-param maxVisits",
              "kata-set-param maxVisits 1000",
              "kata-genmove_analyze B 10",
              "kata-set-param maxTime 2",
              "kata-set-param maxVisits 800"),
          output.commands());
    }
  }

  @Test
  void newPositiveReadBoardGmaCancelsEarlyStopRestoreBeforeOriginalValueArrives()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      engine.genmoveAnalyzeForReadBoard("B", 5, 0, true);
      engine.restoreReadBoardGmaSearchLimitsIfNeeded();
      engine.isThinking = false;
      engine.genmoveAnalyzeForReadBoard("W", 6, 0, true);
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));

      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled true",
              "kata-get-param maxTime",
              "kata-set-param maxTime 5",
              "kata-genmove_analyze B 10",
              "kata-set-param ponderingEnabled true",
              "kata-set-param maxTime 6",
              "kata-genmove_analyze W 10"),
          output.commands());
    }
  }

  @Test
  void restoreReadBoardGmaRuntimeSettingsWaitsForOriginalPonderingValueWhenStopHappensEarly()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      engine.genmoveAnalyzeForReadBoard("B", 0, 0, false);
      invokeRestoreReadBoardGmaRuntimeSettingsIfNeeded(engine);
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));

      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-genmove_analyze B 10",
              "kata-set-param ponderingEnabled true"),
          output.commands());
    }
  }

  @Test
  void restoreReadBoardGmaRuntimeSettingsRecapturesPonderingForNextSession()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      engine.genmoveAnalyzeForReadBoard("B", 0, 0, false);
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      invokeProcessCommandResponseLine(engine, "=");
      invokeRestoreReadBoardGmaRuntimeSettingsIfNeeded(engine);
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));

      engine.isThinking = false;
      engine.genmoveAnalyzeForReadBoard("W", 0, 0, false);

      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-genmove_analyze B 10",
              "kata-set-param ponderingEnabled true",
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-genmove_analyze W 10"),
          output.commands());
    }
  }

  @Test
  void readBoardGmaKeepsEngineReservedUntilEveryRuntimeRestoreIsAcknowledged()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true));
      acknowledgeInitialGmaCommands(engine, output);
      engine.isThinking = false;

      engine.restoreReadBoardGmaRuntimeSettingsIfNeeded();

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
          engine.previewForegroundAnalysisLeaseAvailability());
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxVisits"));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
          engine.previewForegroundAnalysisLeaseAvailability());
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
          engine.previewForegroundAnalysisLeaseAvailability());
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxTime"));

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void restoreCannotMissAnOverrideWhileItsSnapshotCommandIsBeingSent() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      BlockingFirstFlushOutputStream output = new BlockingFirstFlushOutputStream();
      setOutputStream(engine, output);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread genmoveThread =
          new Thread(
              () -> {
                try {
                  engine.genmoveAnalyzeForReadBoard("B", 0, 0, false);
                } catch (Throwable ex) {
                  failure.set(ex);
                }
              },
              "readboard-gma-prepare-race-test");
      genmoveThread.setDaemon(true);

      genmoveThread.start();
      assertTrue(output.firstFlushStarted.await(1, TimeUnit.SECONDS));
      engine.completeReadBoardGmaEngineRestore(
          () -> {}, detail -> failure.set(new AssertionError(detail)));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
          engine.previewForegroundAnalysisLeaseAvailability());

      output.releaseFirstFlush.countDown();
      genmoveThread.join(1000L);
      assertFalse(genmoveThread.isAlive());
      assertEquals(null, failure.get());
      engine.isThinking = false;
      invokeProcessCommandResponseLine(
          engine,
          parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void readBoardGmaRejectsOverlappingGmaAndNormalEngineModeOnCallingThread()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      setOutputStream(engine, new RecordingOutputStream());

      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true));

      assertFalse(engine.genmoveAnalyzeForReadBoard("W", 5, 1000, true));
      assertEquals(null, engine.beginEngineModeReservation());
      assertFalse(engine.beginExclusiveGtpLifecycleTransition());
      assertFalse(engine.genmove("W", false));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ponderingEnabled", "maxTime", "maxVisits"})
  void readBoardGmaRuntimeRestoreErrorQuarantinesEngineAndIgnoresLateSuccess(String failedParam)
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true));
      acknowledgeInitialGmaCommands(engine, output);
      engine.isThinking = false;
      engine.restoreReadBoardGmaRuntimeSettingsIfNeeded();

      invokeProcessCommandResponseLine(
          engine, errorResponseFor(output.rawCommands(), failedParam, "restore failed"));

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
      assertFalse(engine.genmove("B", false));
      assertFalse(engine.genmoveAnalyzeForReadBoard("B", 1, 1, false));
      assertEquals(null, engine.beginEngineModeReservation());
      assertFalse(engine.beginExclusiveGtpLifecycleTransition());

      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), failedParam));

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
      Leelaz.ExclusiveGtpLifecycleReservation lifecycle =
          engine.beginExclusiveGtpLifecycleReservation();
      assertTrue(lifecycle != null, "manual switch/restart must remain available for recovery.");
      lifecycle.close();
    }
  }

  @Test
  void partialRuntimeRestoreSuccessKeepsEverySnapshotUntilTheBarrierCompletes() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true));
      acknowledgeInitialGmaCommands(engine, output);
      engine.isThinking = false;
      engine.restoreReadBoardGmaRuntimeSettingsIfNeeded();

      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      assertEquals(
          "true", getReadBoardGmaParamOriginalValue(engine, "readBoardGmaPondering"));
      invokeProcessCommandResponseLine(
          engine, errorResponseFor(output.rawCommands(), "maxTime", "restore failed"));

      assertEquals("2", getReadBoardGmaParamOriginalValue(engine, "readBoardGmaMaxTime"));
      assertEquals("800", getReadBoardGmaParamOriginalValue(engine, "readBoardGmaMaxVisits"));
      assertEquals(
          "true", getReadBoardGmaParamOriginalValue(engine, "readBoardGmaPondering"));
    }
  }

  @Test
  void runtimeRestoreErrorCanBeRecoveredByAnImmediateSameInstanceRestart() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true));
      acknowledgeInitialGmaCommands(engine, output);
      engine.isThinking = false;
      engine.restoreReadBoardGmaRuntimeSettingsIfNeeded();
      invokeProcessCommandResponseLine(
          engine, errorResponseFor(output.rawCommands(), "maxTime", "restore failed"));

      engine.restoreClosedEngineBoardState(false);
      invokeProcessCommandResponseLine(engine, "=");
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
      invokeProcessCommandResponseLine(engine, numberedResponseFor(output.rawCommands(), "name"));

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void repeatedCleanupAfterSuccessDoesNotSendOrCompleteAgain() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 0, 0, false));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      invokeProcessCommandResponseLine(engine, "=");
      engine.isThinking = false;
      AtomicInteger successes = new AtomicInteger();

      engine.completeReadBoardGmaEngineRestore(successes::incrementAndGet, detail -> {});
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      int commandCount = output.rawCommands().size();
      engine.completeReadBoardGmaEngineRestore(successes::incrementAndGet, detail -> {});

      assertEquals(1, successes.get());
      assertEquals(commandCount, output.rawCommands().size());
    }
  }

  @Test
  void repeatedCleanupAfterFailureDoesNotRetryOrReportSuccess() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true));
      acknowledgeInitialGmaCommands(engine, output);
      engine.isThinking = false;
      AtomicInteger successes = new AtomicInteger();
      AtomicInteger failures = new AtomicInteger();
      engine.completeReadBoardGmaEngineRestore(
          successes::incrementAndGet, detail -> failures.incrementAndGet());
      invokeProcessCommandResponseLine(
          engine, errorResponseFor(output.rawCommands(), "maxTime", "restore failed"));
      int commandCount = output.rawCommands().size();

      engine.completeReadBoardGmaEngineRestore(
          successes::incrementAndGet, detail -> failures.incrementAndGet());

      assertEquals(0, successes.get());
      assertEquals(1, failures.get());
      assertEquals(commandCount, output.rawCommands().size());
    }
  }

  @Test
  void readBoardGmaRuntimeRestoreSendFailureQuarantinesEngine() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true));
      acknowledgeInitialGmaCommands(engine, output);
      engine.isThinking = false;
      setOutputStream(
          engine,
          new OutputStream() {
            @Override
            public void write(int value) throws IOException {
              throw new IOException("controlled restore send failure");
            }
          });
      CountDownLatch failed = new CountDownLatch(1);

      engine.completeReadBoardGmaEngineRestore(() -> {}, detail -> failed.countDown());

      assertTrue(failed.await(1, TimeUnit.SECONDS));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void readBoardGmaRestoreFailureCancelsLoadSgfBeforePendingRegistration() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      invokeBeginReadBoardGmaSession(engine);
      Path sgfFile = Files.createTempFile("gma-reset-loadsgf-", ".sgf");
      AtomicInteger consumed = new AtomicInteger();
      AtomicReference<Throwable> loadFailure = new AtomicReference<>();
      Thread loadThread =
          new Thread(
              () -> {
                try {
                  engine.loadSgf(sgfFile, consumed::incrementAndGet);
                } catch (Throwable failure) {
                  loadFailure.set(failure);
                }
              },
              "gma-reset-loadsgf-test");
      loadThread.setDaemon(true);
      try {
        Object pendingHandlers = pendingResponseHandlers(engine);
        synchronized (pendingHandlers) {
          loadThread.start();
          assertTrue(waitForThreadState(loadThread, Thread.State.BLOCKED, 1, TimeUnit.SECONDS));

          engine.failReadBoardGmaEngineRestore("controlled restore failure");
        }

        loadThread.join(1000L);
        assertFalse(
            loadThread.isAlive(),
            "GMA failure must settle a loadsgf cancelled before pending registration.");
        assertFalse(
            output.commands().stream().anyMatch(command -> command.startsWith("loadsgf ")),
            "a loadsgf cancelled before registration must never reach the engine.");
        assertEquals(1, consumed.get());
        assertTrue(loadFailure.get() instanceof IllegalStateException);
        assertTrue(loadFailure.get().getMessage().contains("loadsgf"));
      } finally {
        loadThread.interrupt();
        loadThread.join(1000L);
        Files.deleteIfExists(sgfFile);
      }
    }
  }

  @Test
  void readBoardGmaRestoreFailureKeepsWritingLoadSgfAliveUntilItsResponse() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      BlockingFirstFlushOutputStream output = new BlockingFirstFlushOutputStream();
      setOutputStream(engine, output);
      invokeBeginReadBoardGmaSession(engine);
      Path sgfFile = Files.createTempFile("gma-reset-writing-loadsgf-", ".sgf");
      AtomicInteger consumed = new AtomicInteger();
      AtomicReference<Throwable> loadFailure = new AtomicReference<>();
      Thread loadThread =
          new Thread(
              () -> {
                try {
                  engine.loadSgf(sgfFile, consumed::incrementAndGet);
                } catch (Throwable failure) {
                  loadFailure.set(failure);
                }
              },
              "gma-reset-writing-loadsgf-test");
      loadThread.setDaemon(true);
      try {
        loadThread.start();
        assertTrue(output.firstFlushStarted.await(1, TimeUnit.SECONDS));

        engine.failReadBoardGmaEngineRestore("controlled restore failure");

        assertTrue(loadThread.isAlive());
        assertEquals(
            0,
            consumed.get(),
            "temporary SGF cleanup must wait while the command is still being written.");
        output.releaseFirstFlush.countDown();
        assertTrue(waitForRawCommandPrefix(output, "loadsgf ", 1, TimeUnit.SECONDS));
        loadThread.join(100L);
        assertEquals(
            0,
            consumed.get(),
            "a written loadsgf must remain tracked after restore returns failure.");
        invokeProcessCommandResponseLine(
            engine, successResponseForPrefix(output.rawCommands(), "loadsgf "));

        loadThread.join(1000L);
        assertFalse(loadThread.isAlive());
        assertEquals(1, consumed.get());
        assertTrue(loadFailure.get() instanceof IllegalStateException);
        assertTrue(loadFailure.get().getMessage().contains("loadsgf"));
      } finally {
        output.releaseFirstFlush.countDown();
        loadThread.interrupt();
        loadThread.join(1000L);
        Files.deleteIfExists(sgfFile);
      }
    }
  }

  @Test
  void readBoardGmaRestoreFailureKeepsSentLoadSgfAliveUntilItsResponse() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      Path sgfFile = Files.createTempFile("gma-reset-sent-loadsgf-", ".sgf");
      AtomicInteger consumed = new AtomicInteger();
      AtomicReference<Throwable> loadFailure = new AtomicReference<>();
      Thread loadThread =
          new Thread(
              () -> {
                try {
                  engine.loadSgf(sgfFile, consumed::incrementAndGet);
                } catch (Throwable failure) {
                  loadFailure.set(failure);
                }
              },
              "gma-reset-sent-loadsgf-test");
      loadThread.setDaemon(true);
      try {
        loadThread.start();
        assertTrue(waitForRawCommandPrefix(output, "loadsgf ", 1, TimeUnit.SECONDS));
        invokeBeginReadBoardGmaSession(engine);

        engine.failReadBoardGmaEngineRestore("controlled restore failure");

        assertTrue(loadThread.isAlive());
        assertEquals(
            0,
            consumed.get(),
            "temporary SGF cleanup must wait for a sent loadsgf consumer response.");
        invokeProcessCommandResponseLine(
            engine, successResponseForPrefix(output.rawCommands(), "loadsgf "));

        loadThread.join(1000L);
        assertFalse(loadThread.isAlive());
        assertEquals(1, consumed.get());
        assertTrue(loadFailure.get() instanceof IllegalStateException);
        assertTrue(loadFailure.get().getMessage().contains("loadsgf"));
      } finally {
        loadThread.interrupt();
        loadThread.join(1000L);
        Files.deleteIfExists(sgfFile);
      }
    }
  }

  @Test
  void readBoardGmaRestoreFailureKeepsBothMirroredLoadSgfConsumersAlive() throws Exception {
    try (Harness harness = Harness.open()) {
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      Leelaz primary = readyReadBoardGmaEngine();
      Leelaz secondary = new Leelaz("");
      Lizzie.leelaz = primary;
      Lizzie.leelaz2 = secondary;
      RecordingOutputStream primaryOutput = new RecordingOutputStream();
      RecordingOutputStream secondaryOutput = new RecordingOutputStream();
      setOutputStream(primary, primaryOutput);
      setOutputStream(secondary, secondaryOutput);
      Path sgfFile = Files.createTempFile("gma-reset-mirrored-loadsgf-", ".sgf");
      AtomicInteger consumed = new AtomicInteger();
      AtomicReference<Throwable> loadFailure = new AtomicReference<>();
      Thread loadThread =
          new Thread(
              () -> {
                try {
                  primary.loadSgf(sgfFile, consumed::incrementAndGet);
                } catch (Throwable failure) {
                  loadFailure.set(failure);
                }
              },
              "gma-reset-mirrored-loadsgf-test");
      loadThread.setDaemon(true);
      try {
        loadThread.start();
        assertTrue(waitForRawCommandPrefix(primaryOutput, "loadsgf ", 1, TimeUnit.SECONDS));
        assertTrue(waitForRawCommandPrefix(secondaryOutput, "loadsgf ", 1, TimeUnit.SECONDS));
        invokeBeginReadBoardGmaSession(primary);

        primary.failReadBoardGmaEngineRestore("controlled restore failure");

        assertEquals(1, pendingResponseHandlerCount(primary));
        assertEquals(1, pendingResponseHandlerCount(secondary));
        assertEquals(0, consumed.get());
        invokeProcessCommandResponseLine(
            primary, successResponseForPrefix(primaryOutput.rawCommands(), "loadsgf "));
        assertEquals(0, consumed.get());
        invokeProcessCommandResponseLine(
            secondary, successResponseForPrefix(secondaryOutput.rawCommands(), "loadsgf "));

        loadThread.join(1000L);
        assertFalse(loadThread.isAlive());
        assertEquals(1, consumed.get());
        assertTrue(loadFailure.get() instanceof IllegalStateException);
        assertTrue(loadFailure.get().getMessage().contains("loadsgf"));
      } finally {
        loadThread.interrupt();
        loadThread.join(1000L);
        Files.deleteIfExists(sgfFile);
      }
    }
  }

  @Test
  void readBoardGmaResetPublishesFailureBeforeAConcurrentLoadSgfResponse() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      engine.requireResponseBeforeSend = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      Path sentSgf = Files.createTempFile("gma-reset-sent-race-", ".sgf");
      Path queuedSgf = Files.createTempFile("gma-reset-queued-race-", ".sgf");
      AtomicReference<Throwable> sentFailure = new AtomicReference<>();
      AtomicReference<Throwable> queuedFailure = new AtomicReference<>();
      CountDownLatch queuedCleanupStarted = new CountDownLatch(1);
      CountDownLatch releaseQueuedCleanup = new CountDownLatch(1);
      Thread sentThread =
          newLoadSgfThread(engine, sentSgf, () -> {}, sentFailure, "gma-reset-sent-race-test");
      Thread queuedThread =
          newLoadSgfThread(
              engine,
              queuedSgf,
              () -> {
                queuedCleanupStarted.countDown();
                awaitLatch(releaseQueuedCleanup);
              },
              queuedFailure,
              "gma-reset-queued-race-test");
      Thread resetThread =
          new Thread(
              () -> engine.failReadBoardGmaEngineRestore("controlled restore failure"),
              "gma-reset-publish-race-test");
      resetThread.setDaemon(true);
      try {
        sentThread.start();
        assertTrue(waitForRawCommandPrefix(output, "loadsgf ", 1, TimeUnit.SECONDS));
        queuedThread.start();
        assertTrue(waitForCommandQueueSize(engine, 1, 1, TimeUnit.SECONDS));
        invokeBeginReadBoardGmaSession(engine);

        resetThread.start();
        assertTrue(queuedCleanupStarted.await(1, TimeUnit.SECONDS));
        invokeProcessCommandResponseLine(
            engine, successResponseForPrefix(output.rawCommands(), "loadsgf "));

        sentThread.join(1000L);
        assertFalse(sentThread.isAlive());
        assertTrue(
            sentFailure.get() instanceof IllegalStateException,
            "the response must observe reset failure even while reset is publishing callbacks.");
      } finally {
        releaseQueuedCleanup.countDown();
        resetThread.join(1000L);
        sentThread.interrupt();
        queuedThread.interrupt();
        sentThread.join(1000L);
        queuedThread.join(1000L);
        Files.deleteIfExists(sentSgf);
        Files.deleteIfExists(queuedSgf);
      }
      assertTrue(queuedFailure.get() instanceof IllegalStateException);
    }
  }

  @Test
  void readBoardGmaResetRetiredLoadSgfResponseDoesNotOpenSiblingSendWindow()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      engine.requireResponseBeforeSend = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      Path sgfFile = Files.createTempFile("gma-reset-retired-outstanding-", ".sgf");
      AtomicReference<Throwable> loadFailure = new AtomicReference<>();
      Thread loadThread =
          newLoadSgfThread(engine, sgfFile, () -> {}, loadFailure, "gma-reset-outstanding-test");
      try {
        loadThread.start();
        assertTrue(waitForRawCommandPrefix(output, "loadsgf ", 1, TimeUnit.SECONDS));
        invokeBeginReadBoardGmaSession(engine);
        engine.failReadBoardGmaEngineRestore("controlled restore failure");
        engine.sendCommand("name");
        engine.sendCommand("version");
        assertTrue(output.commands().contains("name"));
        assertFalse(output.commands().contains("version"));

        invokeProcessCommandResponseLine(
            engine, successResponseForPrefix(output.rawCommands(), "loadsgf "));

        assertFalse(
            output.commands().contains("version"),
            "a retired loadsgf response must not advance the new outstanding baseline.");
        invokeProcessCommandResponseLine(engine, "=");
        assertTrue(output.commands().contains("version"));
        loadThread.join(1000L);
        assertFalse(loadThread.isAlive());
        assertTrue(loadFailure.get() instanceof IllegalStateException);
      } finally {
        loadThread.interrupt();
        loadThread.join(1000L);
        Files.deleteIfExists(sgfFile);
      }
    }
  }

  @Test
  void readBoardGmaResetIgnoresLateUnnumberedResponseAheadOfLoadSgfResponse()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      Path sgfFile = Files.createTempFile("gma-reset-late-response-", ".sgf");
      AtomicInteger consumed = new AtomicInteger();
      AtomicReference<Throwable> loadFailure = new AtomicReference<>();
      Thread loadThread =
          newLoadSgfThread(
              engine,
              sgfFile,
              consumed::incrementAndGet,
              loadFailure,
              "gma-reset-late-response-test");
      try {
        loadThread.start();
        assertTrue(waitForRawCommandPrefix(output, "loadsgf ", 1, TimeUnit.SECONDS));
        invokeBeginReadBoardGmaSession(engine);
        engine.failReadBoardGmaEngineRestore("controlled restore failure");

        invokeProcessCommandResponseLine(engine, "=");

        assertTrue(loadThread.isAlive());
        assertEquals(0, consumed.get());
        assertEquals(1, pendingResponseHandlerCount(engine));
        invokeProcessCommandResponseLine(
            engine, successResponseForPrefix(output.rawCommands(), "loadsgf "));
        loadThread.join(1000L);
        assertFalse(loadThread.isAlive());
        assertEquals(1, consumed.get());
        assertTrue(loadFailure.get() instanceof IllegalStateException);
      } finally {
        loadThread.interrupt();
        loadThread.join(1000L);
        Files.deleteIfExists(sgfFile);
      }
    }
  }

  @Test
  void readBoardGmaResetLinearizesLoadSgfTimeoutBeforeNewOutstandingCommands()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      engine.requireResponseBeforeSend = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      Path sgfFile = Files.createTempFile("gma-reset-timeout-linearization-", ".sgf");
      AtomicReference<Throwable> loadFailure = new AtomicReference<>();
      Thread loadThread =
          newLoadSgfThread(
              engine, sgfFile, () -> {}, loadFailure, "gma-reset-timeout-linearization-test");
      try {
        loadThread.start();
        assertTrue(waitForRawCommandPrefix(output, "loadsgf ", 1, TimeUnit.SECONDS));
        invokeBeginReadBoardGmaSession(engine);
        Object commandQueue = commandQueue(engine);
        synchronized (commandQueue) {
          loadThread.interrupt();
          assertTrue(waitForThreadState(loadThread, Thread.State.BLOCKED, 1, TimeUnit.SECONDS));

          engine.failReadBoardGmaEngineRestore("controlled restore failure");
          engine.sendCommand("name");
          engine.sendCommand("version");
          assertFalse(output.commands().contains("version"));
        }

        loadThread.join(1000L);
        assertFalse(loadThread.isAlive());
        assertFalse(
            output.commands().contains("version"),
            "timeout retirement after reset must not advance the new outstanding baseline.");
        invokeProcessCommandResponseLine(engine, "=");
        assertTrue(output.commands().contains("version"));
        assertTrue(loadFailure.get() instanceof IllegalStateException);
      } finally {
        loadThread.interrupt();
        loadThread.join(1000L);
        Files.deleteIfExists(sgfFile);
      }
    }
  }

  @Test
  void readBoardGmaResetDoesNotRetireWritingLoadSgfTwiceAfterSendFailure() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      engine.requireResponseBeforeSend = true;
      Lizzie.leelaz = engine;
      BlockingFailingFirstFlushOutputStream blockedOutput =
          new BlockingFailingFirstFlushOutputStream();
      setOutputStream(engine, blockedOutput);
      invokeBeginReadBoardGmaSession(engine);
      Path sgfFile = Files.createTempFile("gma-reset-send-failure-retirement-", ".sgf");
      AtomicReference<Throwable> loadFailure = new AtomicReference<>();
      Thread loadThread =
          newLoadSgfThread(
              engine, sgfFile, () -> {}, loadFailure, "gma-reset-send-failure-retirement-test");
      try {
        loadThread.start();
        assertTrue(blockedOutput.firstFlushStarted.await(1, TimeUnit.SECONDS));
        engine.failReadBoardGmaEngineRestore("controlled restore failure");
        RecordingOutputStream recoveryOutput = new RecordingOutputStream();
        setOutputStream(engine, recoveryOutput);
        engine.sendCommand("name");
        engine.sendCommand("version");
        engine.sendCommand("protocol_version");
        blockedOutput.releaseFirstFlush.countDown();
        loadThread.join(1000L);
        assertFalse(loadThread.isAlive());

        invokeProcessCommandResponseLine(engine, "=");

        assertFalse(
            recoveryOutput.commands().contains("version"),
            "post-reset send failure must not retire the old loadsgf outstanding twice.");
        assertFalse(recoveryOutput.commands().contains("protocol_version"));
        assertTrue(loadFailure.get() instanceof IllegalStateException);
      } finally {
        blockedOutput.releaseFirstFlush.countDown();
        loadThread.interrupt();
        loadThread.join(1000L);
        Files.deleteIfExists(sgfFile);
      }
    }
  }

  @Test
  void readBoardGmaRuntimeRestoreTimeoutQuarantinesEngine() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new ShortGmaRestoreTimeoutLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true));
      acknowledgeInitialGmaCommands(engine, output);
      engine.isThinking = false;
      CountDownLatch failed = new CountDownLatch(1);

      engine.completeReadBoardGmaEngineRestore(() -> {}, detail -> failed.countDown());

      assertTrue(failed.await(1, TimeUnit.SECONDS));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void readBoardGmaRestoreTimeoutAlsoCoversOriginalValueStillInFlight() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new ShortGmaRestoreTimeoutLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true));
      CountDownLatch failed = new CountDownLatch(1);

      engine.completeReadBoardGmaEngineRestore(() -> {}, detail -> failed.countDown());

      assertTrue(failed.await(1, TimeUnit.SECONDS));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());

      engine.restoreClosedEngineBoardState(false);
      invokeProcessCommandResponseLine(engine, "=");
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
      invokeProcessCommandResponseLine(engine, numberedResponseFor(output.rawCommands(), "name"));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void readBoardGmaTransportEofDuringRestoreQuarantinesEngine() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true));
      acknowledgeInitialGmaCommands(engine, output);
      engine.isThinking = false;
      CountDownLatch failed = new CountDownLatch(1);
      engine.completeReadBoardGmaEngineRestore(() -> {}, detail -> failed.countDown());

      engine.failReadBoardGmaEngineRestore("transport EOF");

      assertTrue(failed.await(1, TimeUnit.SECONDS));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void readBoardGmaBoardRestoreFailureQuarantinesEngineBeforeRuntimeRestore() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new FailingBoardRestoreLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true));
      acknowledgeInitialGmaCommands(engine, output);
      engine.isThinking = false;
      ReadBoard readBoard = allocate(ReadBoard.class);
      Lizzie.frame.readBoard = readBoard;
      setBooleanField(readBoard, "readBoardGmaEngineRestorePending", true);
      setObjectField(
          readBoard,
          "readBoardGmaDeferredRestoreNode",
          Lizzie.board.getHistory().getCurrentHistoryNode());

      InvocationTargetException failure =
          assertThrows(
              InvocationTargetException.class,
              () -> invokeFlushReadBoardGmaEngineRestoreIfReady(readBoard));

      assertTrue(failure.getCause().getMessage().contains("controlled board restore failure"));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void readBoardGmaKeepsReservationUntilBoardAndRuntimeRestoreAreAcknowledged() throws Exception {
    try (Harness harness = Harness.open()) {
      ControlledBoardRestoreLeelaz engine = new ControlledBoardRestoreLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true));
      acknowledgeInitialGmaCommands(engine, output);
      engine.isThinking = false;
      ReadBoard readBoard = allocate(ReadBoard.class);
      Lizzie.frame.readBoard = readBoard;
      setBooleanField(readBoard, "readBoardGmaEngineRestorePending", true);
      setObjectField(
          readBoard,
          "readBoardGmaDeferredRestoreNode",
          Lizzie.board.getHistory().getCurrentHistoryNode());
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread restoreThread =
          new Thread(
              () -> {
                try {
                  invokeFlushReadBoardGmaEngineRestoreIfReady(readBoard);
                } catch (Throwable ex) {
                  failure.set(ex);
                }
              },
              "readboard-gma-board-restore-test");
      restoreThread.setDaemon(true);

      restoreThread.start();
      assertTrue(engine.loadStarted.await(1, TimeUnit.SECONDS));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
          engine.previewForegroundAnalysisLeaseAvailability());

      engine.completeLoad.countDown();
      restoreThread.join(1000L);
      assertFalse(restoreThread.isAlive());
      assertEquals(null, failure.get());
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
          engine.previewForegroundAnalysisLeaseAvailability());

      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxVisits"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
          engine.previewForegroundAnalysisLeaseAvailability());
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxTime"));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void readLoopEofQuarantinesActiveReadBoardGmaSession() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      setOutputStream(engine, new RecordingOutputStream());
      setInputStream(engine, "");
      engine.isNormalEnd = true;
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true));

      invokeRead(engine);

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void manualRestartKeepsQuarantineUntilBoardSyncConfirmationIsAcknowledged() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true));
      acknowledgeInitialGmaCommands(engine, output);
      engine.isThinking = false;
      engine.failReadBoardGmaEngineRestore("controlled board restore failure");

      engine.restoreClosedEngineBoardState(false);

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
      invokeProcessCommandResponseLine(engine, "=");
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
      String boardFenceResponse = numberedResponseFor(output.rawCommands(), "name");
      int responseCountBeforeStaleLines = getIntField(engine, "currentCmdNum");
      invokeProcessCommandResponseLine(engine, "=");
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
      assertEquals(responseCountBeforeStaleLines, getIntField(engine, "currentCmdNum"));
      invokeProcessCommandResponseLine(engine, "=123456789 stale response");
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
      assertEquals(responseCountBeforeStaleLines, getIntField(engine, "currentCmdNum"));
      invokeProcessCommandResponseLine(engine, boardFenceResponse);
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void automaticRestartKeepsReservationUntilBoardFenceIsAcknowledged() throws Exception {
    try (Harness harness = Harness.open()) {
      ReadyAutomaticRestartLeelaz engine = new ReadyAutomaticRestartLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      Leelaz.ExclusiveGtpLifecycleReservation reservation =
          engine.beginAutomaticEngineRestartReservation();
      assertTrue(reservation != null);
      CountDownLatch completed = new CountDownLatch(1);

      engine.restartClosedEngine(
          0,
          () -> {
            reservation.close();
            completed.countDown();
          });

      assertTrue(waitForRawCommand(output, "name", 1, TimeUnit.SECONDS));
      assertEquals(null, engine.beginEngineModeReservation());
      assertFalse(completed.await(50, TimeUnit.MILLISECONDS));
      invokeProcessCommandResponseLine(engine, numberedResponseFor(output.rawCommands(), "name"));
      assertTrue(completed.await(1, TimeUnit.SECONDS));
      Leelaz.EngineModeReservation afterFence = engine.beginEngineModeReservation();
      assertTrue(afterFence != null);
      afterFence.close();
    }
  }

  @Test
  void automaticRestartReadinessTimeoutReleasesReservationForManualRecovery() throws Exception {
    try (Harness harness = Harness.open()) {
      TimeoutAutomaticRestartLeelaz engine = new TimeoutAutomaticRestartLeelaz();
      Lizzie.leelaz = engine;
      Leelaz.ExclusiveGtpLifecycleReservation reservation =
          engine.beginAutomaticEngineRestartReservation();
      assertTrue(reservation != null);
      CountDownLatch completed = new CountDownLatch(1);

      engine.restartClosedEngine(
          0,
          () -> {
            reservation.close();
            completed.countDown();
          });

      boolean completedBeforeControlledRelease =
          completed.await(250, TimeUnit.MILLISECONDS);
      if (!completedBeforeControlledRelease) {
        engine.isCheckingName = false;
        engine.isLoaded = true;
        completed.await(1, TimeUnit.SECONDS);
      }
      assertTrue(completedBeforeControlledRelease);
      assertFalse(engine.isLoaded());
      assertTrue(engine.hasUnrestoredReadBoardGmaState());
      Leelaz.ExclusiveGtpLifecycleReservation manualRecovery =
          engine.beginExclusiveGtpLifecycleReservation();
      assertTrue(manualRecovery != null);
      manualRecovery.close();
    }
  }

  @Test
  void standaloneRestoreTimeoutQuarantinesBeforeAQueuedSiblingCanBeSent() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new ShortGmaRestoreTimeoutLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      Lizzie.leelaz = engine;
      engine.requireResponseBeforeSend = true;
      setReadBoardGmaParamState(engine, "readBoardGmaMaxTime", "2", true);
      setReadBoardGmaParamState(engine, "readBoardGmaMaxVisits", "800", true);
      BlockingSecondFlushOutputStream output = new BlockingSecondFlushOutputStream();
      setOutputStream(engine, output);
      invokeBeginReadBoardGmaSession(engine);

      engine.restoreReadBoardGmaSearchLimitsIfNeeded();

      boolean siblingSendStarted = output.secondFlushStarted.await(250, TimeUnit.MILLISECONDS);
      Leelaz.ExclusiveGtpLeaseAvailability availabilityBeforeRelease =
          engine.previewForegroundAnalysisLeaseAvailability();
      output.releaseSecondFlush.countDown();
      assertFalse(siblingSendStarted);
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          availabilityBeforeRelease);
    }
  }

  @Test
  void readBoardGmaPlayLineRetiresPendingResponseHandler() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      setBooleanField(readBoard, "readBoardGmaPending", true);
      setBooleanField(readBoard, "readBoardGmaAutoPlayActive", false);
      Lizzie.frame.readBoard = readBoard;

      engine.genmoveAnalyzeForReadBoard("B", 0, 0, true);
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));

      assertEquals(1, pendingResponseHandlerCount(engine));

      invokeParseLine(engine, "play D4");
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));

      assertEquals(0, pendingResponseHandlerCount(engine));
      assertFalse(getBooleanField(engine, "isCommandLine"));
    }
  }

  @Test
  void readBoardGmaPlayLineRetiresPendingResponseHandlerAfterLogicalCancel() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      setBooleanField(readBoard, "readBoardGmaPending", true);
      setBooleanField(readBoard, "readBoardGmaAutoPlayActive", true);
      Lizzie.frame.readBoard = readBoard;

      engine.genmoveAnalyzeForReadBoard("B", 0, 0, true);
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      assertEquals(1, pendingResponseHandlerCount(engine));

      readBoard.parseLine("nobothSync");
      invokeParseLine(engine, "play D4");
      invokeProcessCommandResponseLine(engine, "=");

      assertEquals(0, pendingResponseHandlerCount(engine));
      assertFalse(getBooleanField(engine, "isCommandLine"));
    }
  }

  @Test
  void readBoardGmaErrorLineRetiresPendingResponseHandler() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      setBooleanField(readBoard, "readBoardGmaPending", true);
      setBooleanField(readBoard, "readBoardGmaAutoPlayActive", true);
      Lizzie.frame.readBoard = readBoard;

      engine.genmoveAnalyzeForReadBoard("B", 0, 0, true);
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      assertEquals(1, pendingResponseHandlerCount(engine));

      invokeParseLine(engine, "? engine failed");

      assertEquals(0, pendingResponseHandlerCount(engine));
      assertFalse(getBooleanField(readBoard, "readBoardGmaPending"));
      assertFalse(getBooleanField(engine, "isCommandLine"));
    }
  }

  private static Leelaz readyReadBoardGmaEngine() throws Exception {
    Leelaz engine = new Leelaz("");
    configureReadyReadBoardGmaEngine(engine);
    return engine;
  }

  private static void configureReadyReadBoardGmaEngine(Leelaz engine) throws Exception {
    engine.started = true;
    engine.isLoaded = true;
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
            "kata-analyze",
            "kata-genmove_analyze",
            "kata-get-param",
            "kata-set-param"));
    Field commandListReady = Leelaz.class.getDeclaredField("endGetCommandList");
    commandListReady.setAccessible(true);
    commandListReady.setBoolean(engine, true);
  }

  private static void acknowledgeInitialGmaCommands(
      Leelaz engine, RecordingOutputStream output) throws Exception {
    invokeProcessCommandResponseLine(
        engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
    invokeProcessCommandResponseLine(
        engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
    invokeProcessCommandResponseLine(
        engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
    invokeProcessCommandResponseLine(engine, successResponseFor(output.rawCommands(), "maxTime"));
    invokeProcessCommandResponseLine(
        engine, parameterValueResponseFor(output.rawCommands(), "maxVisits", "800"));
    invokeProcessCommandResponseLine(
        engine, successResponseFor(output.rawCommands(), "maxVisits"));
    invokeProcessCommandResponseLine(engine, "=");
  }

  private static String successResponseFor(List<String> commands, String paramName) {
    for (int index = commands.size() - 1; index >= 0; index--) {
      String command = commands.get(index);
      if (!command.contains("kata-set-param " + paramName + " ")) {
        continue;
      }
      int firstSpace = command.indexOf(' ');
      if (firstSpace > 0 && command.substring(0, firstSpace).chars().allMatch(Character::isDigit)) {
        return "=" + command.substring(0, firstSpace);
      }
      return "=";
    }
    throw new IllegalArgumentException("Missing restore command for " + paramName);
  }

  private static String parameterValueResponseFor(
      List<String> commands, String paramName, String value) {
    for (String command : commands) {
      if (!command.contains("kata-get-param " + paramName)) {
        continue;
      }
      int firstSpace = command.indexOf(' ');
      if (firstSpace > 0 && command.substring(0, firstSpace).chars().allMatch(Character::isDigit)) {
        return "=" + command.substring(0, firstSpace) + " " + value;
      }
      return "= " + value;
    }
    throw new IllegalArgumentException("Missing snapshot command for " + paramName);
  }

  private static String errorResponseFor(
      List<String> commands, String paramName, String detail) {
    return successResponseFor(commands, paramName).replaceFirst("^=", "?") + " " + detail;
  }

  private static String numberedResponseFor(List<String> commands, String commandName) {
    for (int index = commands.size() - 1; index >= 0; index--) {
      String command = commands.get(index);
      int firstSpace = command.indexOf(' ');
      if (firstSpace > 0
          && command.substring(0, firstSpace).chars().allMatch(Character::isDigit)
          && command.substring(firstSpace + 1).equals(commandName)) {
        return "=" + command.substring(0, firstSpace);
      }
    }
    throw new IllegalArgumentException("Missing numbered command " + commandName);
  }

  private static String successResponseForPrefix(List<String> commands, String commandPrefix) {
    for (int index = commands.size() - 1; index >= 0; index--) {
      String command = commands.get(index);
      int firstSpace = command.indexOf(' ');
      if (firstSpace > 0
          && command.substring(0, firstSpace).chars().allMatch(Character::isDigit)
          && command.substring(firstSpace + 1).startsWith(commandPrefix)) {
        return "=" + command.substring(0, firstSpace);
      }
    }
    throw new IllegalArgumentException("Missing numbered command prefix " + commandPrefix);
  }

  private static boolean waitForRawCommand(
      RecordingOutputStream output, String commandName, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      try {
        numberedResponseFor(output.rawCommands(), commandName);
        return true;
      } catch (IllegalArgumentException ignored) {
        Thread.sleep(10L);
      }
    }
    return false;
  }

  private static boolean waitForRawCommandPrefix(
      RecordingOutputStream output, String commandPrefix, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      for (String command : output.commands()) {
        if (command.startsWith(commandPrefix)) {
          return true;
        }
      }
      Thread.sleep(10L);
    }
    return false;
  }

  private static boolean waitForRawCommandPrefix(
      BlockingFirstFlushOutputStream output,
      String commandPrefix,
      long timeout,
      TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      for (String command : output.rawCommands()) {
        int firstSpace = command.indexOf(' ');
        String normalized = firstSpace < 0 ? command : command.substring(firstSpace + 1);
        if (normalized.startsWith(commandPrefix)) {
          return true;
        }
      }
      Thread.sleep(10L);
    }
    return false;
  }

  private static boolean waitForThreadState(
      Thread thread, Thread.State state, long timeout, TimeUnit unit) throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      if (thread.getState() == state) {
        return true;
      }
      Thread.sleep(10L);
    }
    return false;
  }

  private static boolean waitForCommandQueueSize(
      Leelaz engine, int expectedSize, long timeout, TimeUnit unit) throws Exception {
    Field field = Leelaz.class.getDeclaredField("cmdQueue");
    field.setAccessible(true);
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      Collection<?> queue = (Collection<?>) field.get(engine);
      if (queue != null && queue.size() == expectedSize) {
        return true;
      }
      Thread.sleep(10L);
    }
    return false;
  }

  private static Object commandQueue(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("cmdQueue");
    field.setAccessible(true);
    return field.get(engine);
  }

  private static Thread newLoadSgfThread(
      Leelaz engine,
      Path sgfFile,
      Runnable afterConsumed,
      AtomicReference<Throwable> failure,
      String threadName) {
    Thread thread =
        new Thread(
            () -> {
              try {
                engine.loadSgf(sgfFile, afterConsumed);
              } catch (Throwable ex) {
                failure.set(ex);
              }
            },
            threadName);
    thread.setDaemon(true);
    return thread;
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  private static void invokeRestoreReadBoardGmaRuntimeSettingsIfNeeded(Leelaz engine)
      throws Exception {
    java.lang.reflect.Method method =
        Leelaz.class.getDeclaredMethod("restoreReadBoardGmaRuntimeSettingsIfNeeded");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static void invokeBeginReadBoardGmaSession(Leelaz engine) throws Exception {
    java.lang.reflect.Method method =
        Leelaz.class.getDeclaredMethod("beginReadBoardGmaSession");
    method.setAccessible(true);
    assertTrue((Boolean) method.invoke(engine));
  }

  private static void setOutputStream(Leelaz engine, OutputStream stream) throws Exception {
    Field outputField = Leelaz.class.getDeclaredField("outputStream");
    outputField.setAccessible(true);
    outputField.set(engine, Leelaz.createCommandOutputStream(stream));
  }

  private static void setInputStream(Leelaz engine, String input) throws Exception {
    Field field = Leelaz.class.getDeclaredField("inputStream");
    field.setAccessible(true);
    field.set(engine, new BufferedReader(new StringReader(input)));
  }

  private static void setObjectField(Object target, String fieldName, Object value)
      throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void setBooleanField(Object target, String fieldName, boolean value)
      throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.setBoolean(target, value);
  }

  private static void setStringField(Object target, String fieldName, String value)
      throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void setReadBoardGmaParamState(
      Leelaz engine, String paramFieldName, String originalValue, boolean overridden)
      throws Exception {
    Field field = Leelaz.class.getDeclaredField(paramFieldName);
    field.setAccessible(true);
    Object param = field.get(engine);
    setStringField(param, "originalValue", originalValue);
    setBooleanField(param, "overridden", overridden);
  }

  private static boolean getBooleanField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.getBoolean(target);
  }

  private static int getIntField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.getInt(target);
  }

  private static String getReadBoardGmaParamOriginalValue(Leelaz engine, String paramFieldName)
      throws Exception {
    Field field = Leelaz.class.getDeclaredField(paramFieldName);
    field.setAccessible(true);
    Object param = field.get(engine);
    Field originalValue = param.getClass().getDeclaredField("originalValue");
    originalValue.setAccessible(true);
    return (String) originalValue.get(param);
  }

  private static void invokeProcessCommandResponseLine(Leelaz engine, String line)
      throws Exception {
    java.lang.reflect.Method method =
        Leelaz.class.getDeclaredMethod("processCommandResponseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void invokeParseLine(Leelaz engine, String line) throws Exception {
    java.lang.reflect.Method method = Leelaz.class.getDeclaredMethod("parseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void invokeRead(Leelaz engine) throws Exception {
    java.lang.reflect.Method method = Leelaz.class.getDeclaredMethod("read");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static void invokeFlushReadBoardGmaEngineRestoreIfReady(ReadBoard readBoard)
      throws Exception {
    java.lang.reflect.Method method =
        ReadBoard.class.getDeclaredMethod(
            "flushReadBoardGmaEngineRestoreIfReady", String.class);
    method.setAccessible(true);
    method.invoke(readBoard, "test");
  }

  private static int pendingResponseHandlerCount(Leelaz engine) throws Exception {
    Collection<?> handlers = (Collection<?>) pendingResponseHandlers(engine);
    return handlers == null ? 0 : handlers.size();
  }

  private static Object pendingResponseHandlers(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("pendingResponseHandlers");
    field.setAccessible(true);
    return field.get(engine);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static Config minimalConfig() throws Exception {
    Config config = allocate(Config.class);
    config.extraMode = ExtraMode.Normal;
    config.analyzeUpdateIntervalCentisec = 10;
    config.analyzeUpdateIntervalCentisecSSH = 10;
    return config;
  }

  private static final class Harness implements AutoCloseable {
    private final Config previousConfig;
    private final LizzieFrame previousFrame;
    private final GtpConsolePane previousGtpConsole;
    private final Leelaz previousLeelaz;
    private final Leelaz previousLeelaz2;
    private final Board previousBoard;
    private final Menu previousMenu;
    private final boolean previousEngineGameFlag;

    private Harness(
        Config previousConfig,
        LizzieFrame previousFrame,
        GtpConsolePane previousGtpConsole,
        Leelaz previousLeelaz,
        Leelaz previousLeelaz2,
        Board previousBoard,
        Menu previousMenu,
        boolean previousEngineGameFlag) {
      this.previousConfig = previousConfig;
      this.previousFrame = previousFrame;
      this.previousGtpConsole = previousGtpConsole;
      this.previousLeelaz = previousLeelaz;
      this.previousLeelaz2 = previousLeelaz2;
      this.previousBoard = previousBoard;
      this.previousMenu = previousMenu;
      this.previousEngineGameFlag = previousEngineGameFlag;
    }

    private static Harness open() throws Exception {
      Harness harness =
          new Harness(
              Lizzie.config,
              Lizzie.frame,
              Lizzie.gtpConsole,
              Lizzie.leelaz,
              Lizzie.leelaz2,
              Lizzie.board,
              LizzieFrame.menu,
              EngineManager.isEngineGame);
      Lizzie.config = minimalConfig();
      Lizzie.frame = allocate(SilentFrame.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      Lizzie.leelaz = new Leelaz("");
      Lizzie.leelaz2 = null;
      Lizzie.board = new Board();
      Lizzie.leelaz = null;
      LizzieFrame.menu = allocate(SilentMenu.class);
      EngineManager.isEngineGame = false;
      return harness;
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousGtpConsole;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.leelaz2 = previousLeelaz2;
      Lizzie.board = previousBoard;
      LizzieFrame.menu = previousMenu;
      EngineManager.isEngineGame = previousEngineGameFlag;
    }
  }

  private static final class RecordingOutputStream extends OutputStream {
    private final StringBuilder currentCommand = new StringBuilder();
    private final List<String> commands = new ArrayList<>();

    @Override
    public synchronized void write(int b) {
      currentCommand.append((char) b);
    }

    @Override
    public synchronized void flush() {
      String command = currentCommand.toString().trim();
      currentCommand.setLength(0);
      if (!command.isEmpty()) {
        commands.add(command);
      }
    }

    private synchronized List<String> commands() {
      List<String> normalized = new ArrayList<>();
      for (String command : commands) {
        int firstSpace = command.indexOf(' ');
        if (firstSpace > 0
            && command.substring(0, firstSpace).chars().allMatch(Character::isDigit)) {
          normalized.add(command.substring(firstSpace + 1));
        } else {
          normalized.add(command);
        }
      }
      return normalized;
    }

    private synchronized List<String> rawCommands() {
      return new ArrayList<>(commands);
    }
  }

  private static final class BlockingFirstFlushOutputStream extends OutputStream {
    private final StringBuilder currentCommand = new StringBuilder();
    private final List<String> commands = new ArrayList<>();
    private final CountDownLatch firstFlushStarted = new CountDownLatch(1);
    private final CountDownLatch releaseFirstFlush = new CountDownLatch(1);
    private boolean firstFlush = true;

    @Override
    public synchronized void write(int value) {
      currentCommand.append((char) value);
    }

    @Override
    public synchronized void flush() throws IOException {
      if (firstFlush) {
        firstFlush = false;
        firstFlushStarted.countDown();
        try {
          releaseFirstFlush.await();
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new IOException("controlled first flush interrupted", ex);
        }
      }
      String command = currentCommand.toString().trim();
      currentCommand.setLength(0);
      if (!command.isEmpty()) {
        commands.add(command);
      }
    }

    private synchronized List<String> rawCommands() {
      return new ArrayList<>(commands);
    }
  }

  private static final class BlockingSecondFlushOutputStream extends OutputStream {
    private final CountDownLatch secondFlushStarted = new CountDownLatch(1);
    private final CountDownLatch releaseSecondFlush = new CountDownLatch(1);
    private int flushCount;

    @Override
    public void write(int value) {}

    @Override
    public void flush() throws IOException {
      flushCount++;
      if (flushCount != 2) {
        return;
      }
      secondFlushStarted.countDown();
      try {
        releaseSecondFlush.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IOException("controlled second flush interrupted", ex);
      }
    }
  }

  private static final class BlockingFailingFirstFlushOutputStream extends OutputStream {
    private final CountDownLatch firstFlushStarted = new CountDownLatch(1);
    private final CountDownLatch releaseFirstFlush = new CountDownLatch(1);

    @Override
    public void write(int value) {}

    @Override
    public void flush() throws IOException {
      firstFlushStarted.countDown();
      try {
        releaseFirstFlush.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IOException("controlled blocked flush interrupted", ex);
      }
      throw new IOException("controlled blocked flush failure");
    }
  }

  private static final class FailingBoardRestoreLeelaz extends Leelaz {
    private FailingBoardRestoreLeelaz() throws IOException {
      super("");
    }

    @Override
    public void loadSgf(Path sgfFile) {
      throw new IllegalStateException("controlled board restore failure");
    }
  }

  private static final class ControlledBoardRestoreLeelaz extends Leelaz {
    private final CountDownLatch loadStarted = new CountDownLatch(1);
    private final CountDownLatch completeLoad = new CountDownLatch(1);

    private ControlledBoardRestoreLeelaz() throws IOException {
      super("");
    }

    @Override
    public void loadSgf(Path sgfFile) {
      loadStarted.countDown();
      try {
        completeLoad.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("controlled board restore interrupted", ex);
      }
    }
  }

  private static final class SilentFrame extends LizzieFrame {
    private SilentFrame() {
      super();
    }

    @Override
    public void refresh() {}
  }

  private static final class SilentMenu extends Menu {
    private SilentMenu() {}

    @Override
    public void toggleEngineMenuStatus(boolean isPondering, boolean isThinking) {}
  }

  private static final class ShortGmaRestoreTimeoutLeelaz extends Leelaz {
    private ShortGmaRestoreTimeoutLeelaz() throws IOException {
      super("");
    }

    @Override
    protected long readBoardGmaRestoreResponseTimeoutMillis() {
      return 25L;
    }
  }

  private static final class ReadyAutomaticRestartLeelaz extends Leelaz {
    private ReadyAutomaticRestartLeelaz() throws IOException {
      super("controlled-engine");
    }

    @Override
    public void startEngine(int index) {
      started = true;
      isLoaded = true;
      isCheckingName = false;
    }
  }

  private static final class TimeoutAutomaticRestartLeelaz extends Leelaz {
    private TimeoutAutomaticRestartLeelaz() throws IOException {
      super("controlled-engine");
    }

    @Override
    public void startEngine(int index) {
      started = true;
      isLoaded = false;
      isCheckingName = true;
    }

    @Override
    long engineStartupSynchronizationTimeoutMillis() {
      return 25L;
    }
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super((Window) null);
    }

    @Override
    public boolean isVisible() {
      return false;
    }

    @Override
    public void addCommand(String command, int commandNumber, String engineName) {}

    @Override
    public void addCommandForEngineGame(
        String command, int commandNumber, String engineName, boolean isBlack) {}

    @Override
    public void addLine(String line) {}
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static sun.misc.Unsafe loadUnsafe() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Failed to access Unsafe", ex);
      }
    }
  }
}
