package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.gui.BoardRenderer;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.awt.Window;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LeelazReadBoardGmaTest {

  @Test
  void retiringActiveReadBoardGmaReleasesReservationAndQuarantinesDirtyRuntimeState()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      setOutputStream(engine, new RecordingOutputStream());
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 0, 0, false));

      engine.retireReadBoardGmaSession();
      engine.retireReadBoardGmaSession();

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
      assertTrue(engine.hasUnrestoredReadBoardGmaState());
      assertFalse(engine.isThinking);
    }
  }

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
  void websocketGmaSupportsFixedLimitsWithoutClaimingPondering() throws Exception {
    Leelaz engine = readyReadBoardGmaEngine();
    engine.setEngineCommand(RemoteComputeConfig.COMMAND_CUSTOM_WS);

    assertTrue(engine.supportsReadBoardGmaFixedLimits());
    assertFalse(engine.supportsReadBoardGmaPondering());
  }

  @Test
  void websocketGmaAppliesFixedLimitsWithoutSendingPonderingParam() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      engine.setEngineCommand(RemoteComputeConfig.COMMAND_CUSTOM_WS);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, false));

      assertEquals(List.of("kata-get-param maxTime"), output.commands());

      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
      assertEquals(
          List.of("kata-get-param maxTime", "kata-set-param maxTime 5"), output.commands());

      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxTime"));
      assertEquals(
          List.of(
              "kata-get-param maxTime",
              "kata-set-param maxTime 5",
              "kata-get-param maxVisits"),
          output.commands());

      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxVisits", "800"));
      assertEquals(
          List.of(
              "kata-get-param maxTime",
              "kata-set-param maxTime 5",
              "kata-get-param maxVisits",
              "kata-set-param maxVisits 1000"),
          output.commands());

      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxVisits"));
      assertEquals(
          List.of(
              "kata-get-param maxTime",
              "kata-set-param maxTime 5",
              "kata-get-param maxVisits",
              "kata-set-param maxVisits 1000",
              "kata-genmove_analyze B 10"),
          output.commands());
    }
  }

  @Test
  void websocketGmaRejectsPonderingBeforeStartingTheFixedLimitSession() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      engine.setEngineCommand(RemoteComputeConfig.COMMAND_CUSTOM_WS);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      assertFalse(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true));

      assertTrue(output.commands().isEmpty());
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void websocketGmaSetupErrorQuarantinesEngineAndIgnoresLateSuccess() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      engine.setEngineCommand(RemoteComputeConfig.COMMAND_CUSTOM_WS);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, false));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
      invokeProcessCommandResponseLine(
          engine, errorResponseFor(output.rawCommands(), "maxTime", "setup failed"));

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
      assertFalse(
          output.commands().stream()
              .anyMatch(command -> command.startsWith("kata-genmove_analyze")));

      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxTime"));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void websocketGmaSetupSendFailureQuarantinesEngine() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      engine.setEngineCommand(RemoteComputeConfig.COMMAND_CUSTOM_WS);
      Lizzie.leelaz = engine;
      setOutputStream(
          engine,
          new OutputStream() {
            @Override
            public void write(int value) throws IOException {
              throw new IOException("controlled setup send failure");
            }
          });

      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, false));

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void websocketGmaSetupTimeoutQuarantinesEngine() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new ShortGmaRestoreTimeoutLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      engine.setEngineCommand(RemoteComputeConfig.COMMAND_CUSTOM_WS);
      Lizzie.leelaz = engine;
      setOutputStream(engine, new RecordingOutputStream());

      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, false));

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (engine.previewForegroundAnalysisLeaseAvailability()
              != Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED
          && System.nanoTime() < deadline) {
        Thread.sleep(5L);
      }
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void websocketGmaRestoresExactFixedLimitsBeforeReleasingReservation() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      engine.setEngineCommand(RemoteComputeConfig.COMMAND_CUSTOM_WS);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, false));
      acknowledgeWebSocketGmaSetup(engine, output, "2", "800");
      engine.isThinking = false;
      AtomicInteger successes = new AtomicInteger();

      engine.completeReadBoardGmaEngineRestore(successes::incrementAndGet, detail -> {});

      assertTrue(output.commands().contains("kata-set-param maxTime 2"));
      assertTrue(output.commands().contains("kata-set-param maxVisits 800"));
      assertFalse(
          output.commands().stream().anyMatch(command -> command.contains("ponderingEnabled")));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
          engine.previewForegroundAnalysisLeaseAvailability());

      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxVisits"));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
          engine.previewForegroundAnalysisLeaseAvailability());
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxTime"));

      assertEquals(1, successes.get());
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void websocketGmaReusesOriginalSnapshotAndAppliesChangesOnlyToTheNextMove() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      engine.setEngineCommand(RemoteComputeConfig.COMMAND_CUSTOM_WS);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, false));
      acknowledgeWebSocketGmaSetup(engine, output, "2", "800");
      int firstMoveCommandCount = output.commands().size();

      assertFalse(engine.genmoveAnalyzeForReadBoard("W", 6, 900, false));
      assertEquals(firstMoveCommandCount, output.commands().size());

      engine.isThinking = false;
      assertTrue(engine.genmoveAnalyzeForReadBoard("W", 6, 900, false));
      assertEquals(
          "kata-set-param maxTime 6", output.commands().get(firstMoveCommandCount));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxTime"));
      assertEquals(
          "kata-set-param maxVisits 900", output.commands().get(firstMoveCommandCount + 1));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxVisits"));

      assertEquals(
          "kata-genmove_analyze W 10", output.commands().get(firstMoveCommandCount + 2));
      assertEquals(
          1,
          output.commands().stream()
              .filter(command -> command.equals("kata-get-param maxTime"))
              .count());
      assertEquals(
          1,
          output.commands().stream()
              .filter(command -> command.equals("kata-get-param maxVisits"))
              .count());
    }
  }

  @Test
  void websocketGmaStopDuringSnapshotWaitDoesNotReleaseOrStartTheMove() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      engine.setEngineCommand(RemoteComputeConfig.COMMAND_CUSTOM_WS);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, false));
      AtomicInteger successes = new AtomicInteger();

      assertTrue(
          engine.cancelReadBoardGmaPreparationIfPending(
              successes::incrementAndGet, detail -> {}));

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
          engine.previewForegroundAnalysisLeaseAvailability());
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));

      assertEquals(List.of("kata-get-param maxTime"), output.commands());
      assertEquals(1, successes.get());
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void websocketGmaStopDuringSetWaitRestoresTheAcknowledgedOverride() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      engine.setEngineCommand(RemoteComputeConfig.COMMAND_CUSTOM_WS);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, false));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
      AtomicInteger successes = new AtomicInteger();

      engine.completeReadBoardGmaEngineRestore(successes::incrementAndGet, detail -> {});
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxTime"));

      assertEquals(
          List.of(
              "kata-get-param maxTime",
              "kata-set-param maxTime 5",
              "kata-set-param maxTime 2"),
          output.commands());
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
          engine.previewForegroundAnalysisLeaseAvailability());
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxTime"));

      assertEquals(1, successes.get());
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void websocketGmaSnapshotsBothOriginalLimitsEvenWhenReadBoardUsesDefaults() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      engine.setEngineCommand(RemoteComputeConfig.COMMAND_CUSTOM_WS);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 0, 0, false));

      assertEquals(List.of("kata-get-param maxTime"), output.commands());
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
      assertEquals(
          List.of("kata-get-param maxTime", "kata-get-param maxVisits"), output.commands());
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxVisits", "800"));

      assertEquals(
          List.of(
              "kata-get-param maxTime",
              "kata-get-param maxVisits",
              "kata-genmove_analyze B 10"),
          output.commands());
    }
  }

  @Test
  void websocketGmaCancelBeforeFinalSetAckCannotStartTheMove() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      engine.setEngineCommand(RemoteComputeConfig.COMMAND_CUSTOM_WS);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, false));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxTime"));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxVisits", "800"));

      assertTrue(engine.cancelReadBoardGmaPreparationIfPending(null, detail -> {}));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxVisits"));

      assertFalse(
          output.commands().stream()
              .anyMatch(command -> command.startsWith("kata-genmove_analyze")));
      assertTrue(output.commands().contains("kata-set-param maxTime 2"));
      assertTrue(output.commands().contains("kata-set-param maxVisits 800"));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
          engine.previewForegroundAnalysisLeaseAvailability());
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxVisits"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxTime"));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void readBoardStopSyncCancelsWebsocketGmaPreparation() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      engine.setEngineCommand(RemoteComputeConfig.COMMAND_CUSTOM_WS);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      setObjectField(readBoard, "conflictTracker", new SyncConflictTracker());
      setObjectField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
      setObjectField(readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
      setBooleanField(readBoard, "readBoardGmaPending", true);
      setBooleanField(readBoard, "readBoardGmaAutoPlayActive", true);
      Lizzie.frame.readBoard = readBoard;
      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, false));

      readBoard.parseLine("stopsync");

      assertFalse(getBooleanField(readBoard, "readBoardGmaPending"));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
          engine.previewForegroundAnalysisLeaseAvailability());
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
      assertEquals(List.of("kata-get-param maxTime", "stop"), output.commands());
      assertFalse(
          output.commands().stream()
              .anyMatch(
                  command ->
                      command.startsWith("kata-set-param")
                          || command.startsWith("kata-genmove_analyze")));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void genmoveAnalyzeForReadBoardMapsPositiveLimitsAndPonderSetting() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      engine.isKatago = true;
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 1000, true));
      assertEquals(
          List.of("kata-get-param ponderingEnabled"),
          output.commands(),
          "the acknowledged preparation must send one command at a time");
      assertFalse(
          output.commands().stream()
              .anyMatch(command -> command.startsWith("kata-genmove_analyze")),
          "the genmove must not be sent before every required get/set ACK");

      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      assertEquals(
          List.of("kata-get-param ponderingEnabled", "kata-set-param ponderingEnabled true"),
          output.commands());
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled true",
              "kata-get-param maxTime"),
          output.commands());
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled true",
              "kata-get-param maxTime",
              "kata-set-param maxTime 5"),
          output.commands());
      invokeProcessCommandResponseLine(engine, successResponseFor(output.rawCommands(), "maxTime"));
      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled true",
              "kata-get-param maxTime",
              "kata-set-param maxTime 5",
              "kata-get-param maxVisits"),
          output.commands());
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxVisits", "800"));
      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled true",
              "kata-get-param maxTime",
              "kata-set-param maxTime 5",
              "kata-get-param maxVisits",
              "kata-set-param maxVisits 1000"),
          output.commands());
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxVisits"));

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

      assertTrue(engine.genmoveAnalyzeForReadBoard("W", 0, 0, false));
      assertEquals(
          List.of("kata-get-param ponderingEnabled"),
          output.commands(),
          "the acknowledged preparation must send one command at a time");

      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      assertEquals(
          List.of("kata-get-param ponderingEnabled", "kata-set-param ponderingEnabled false"),
          output.commands());
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-get-param maxTime"),
          output.commands());
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-get-param maxTime",
              "kata-get-param maxVisits"),
          output.commands());
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxVisits", "800"));

      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-get-param maxTime",
              "kata-get-param maxVisits",
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
      assertEquals(
          List.of("kata-get-param ponderingEnabled"),
          output.commands(),
          "the acknowledged preparation must send one command at a time");
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      assertEquals(
          List.of("kata-get-param ponderingEnabled", "kata-set-param ponderingEnabled false"),
          output.commands());
      engine.isThinking = false;
      engine.restoreReadBoardGmaRuntimeSettingsIfNeeded();
      // The stop-restore waits for the acknowledged override: the restore of the captured
      // original value is dispatched only after the preparation's set ACK arrives.
      assertFalse(
          output.commands().stream()
              .anyMatch(command -> command.startsWith("kata-set-param ponderingEnabled true")),
          "the restore must not be dispatched before the acknowledged override completes");
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));

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

      assertTrue(engine.genmoveAnalyzeForReadBoard("W", 0, 0, false));
      assertEquals(
          List.of("kata-get-param ponderingEnabled"),
          output.commands(),
          "the acknowledged preparation must send one command at a time");

      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-set-param maxTime 2"),
          output.commands(),
          "a zero limit must restore the previously acknowledged override instead of"
              + " re-snapshotting");
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxTime"));
      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-set-param maxTime 2",
              "kata-set-param maxVisits 800"),
          output.commands());
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxVisits"));

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
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      invokeProcessCommandResponseLine(
          engine, errorResponseFor(output.rawCommands(), "maxTime", "restore failed"));

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
      assertFalse(
          output.commands().stream()
              .anyMatch(command -> command.startsWith("kata-genmove_analyze")),
          "a preparation restore failure must never admit the session or send the genmove");
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
      assertFalse(
          output.commands().stream()
              .anyMatch(command -> command.startsWith("kata-set-param maxTime")),
          "an early stop restore must not dispatch overrides that are not yet acknowledged");
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled true",
              "kata-get-param maxTime"),
          output.commands());
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxTime"));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxVisits", "800"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxVisits"));
      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled true",
              "kata-get-param maxTime",
              "kata-set-param maxTime 5",
              "kata-get-param maxVisits",
              "kata-set-param maxVisits 1000",
              "kata-genmove_analyze B 10"),
          output.commands(),
          "the early stop restore must stay a no-op through the acknowledged hand");
      engine.isThinking = false;
      engine.restoreReadBoardGmaSearchLimitsIfNeeded();

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
          output.commands(),
          "a stop restore after the acknowledged hand must restore the captured originals");
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

      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 5, 0, true));
      engine.restoreReadBoardGmaSearchLimitsIfNeeded();
      assertFalse(
          output.commands().stream()
              .anyMatch(command -> command.startsWith("kata-set-param maxTime")),
          "an early stop restore must not dispatch overrides that are not yet acknowledged");
      // The first hand's acknowledged preparation: the original values arrive through the
      // snapshot ACKs, never through a standalone restore dispatch.
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
              "kata-genmove_analyze B 10"),
          output.commands());
      engine.isThinking = false;
      assertTrue(engine.genmoveAnalyzeForReadBoard("W", 6, 0, true));
      // The new positive hand cancels the early stop restore: it reuses the acknowledged
      // snapshots (no re-snapshotting) and applies its own overrides instead.
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "maxTime"));

      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled true",
              "kata-get-param maxTime",
              "kata-set-param maxTime 5",
              "kata-get-param maxVisits",
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

      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 0, 0, false));
      assertEquals(
          List.of("kata-get-param ponderingEnabled"),
          output.commands(),
          "the acknowledged preparation must send one command at a time");
      invokeRestoreReadBoardGmaRuntimeSettingsIfNeeded(engine);
      assertFalse(
          output.commands().stream()
              .anyMatch(command -> command.startsWith("kata-set-param ponderingEnabled true")),
          "the stop restore must wait for the acknowledged override before restoring");
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      assertEquals(
          List.of("kata-get-param ponderingEnabled"),
          output.commands(),
          "cancellation after the snapshot must skip the override and release without genmove");
      assertNull(
          engine.currentReadBoardGmaReservation(),
          "cancellation after a snapshot-only preparation must release the GMA reservation");
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

      assertTrue(engine.genmoveAnalyzeForReadBoard("B", 0, 0, false));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxVisits", "800"));
      invokeProcessCommandResponseLine(engine, "=");
      invokeRestoreReadBoardGmaRuntimeSettingsIfNeeded(engine);
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));

      engine.isThinking = false;
      assertTrue(engine.genmoveAnalyzeForReadBoard("W", 0, 0, false));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxVisits", "800"));

      assertEquals(
          List.of(
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-get-param maxTime",
              "kata-get-param maxVisits",
              "kata-genmove_analyze B 10",
              "kata-set-param ponderingEnabled true",
              "kata-get-param ponderingEnabled",
              "kata-set-param ponderingEnabled false",
              "kata-get-param maxTime",
              "kata-get-param maxVisits",
              "kata-genmove_analyze W 10"),
          output.commands(),
          "the completed restore must clear the snapshots so the next session recaptures them");
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
      // The restore raced the in-flight snapshot command: the override was never applied, so the
      // snapshot ACK alone completes the empty cancellation barrier and releases the reservation.
      // The preparation's set command is never sent, so there is no set ACK to deliver.
      invokeProcessCommandResponseLine(
          engine,
          parameterValueResponseFor(output.rawCommands(), "ponderingEnabled", "true"));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.previewForegroundAnalysisLeaseAvailability());
      assertFalse(
          output.rawCommands().stream().anyMatch(command -> command.startsWith("kata-set-param")),
          "the cancelled hand must not apply an override after the restore raced its snapshot");
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

      output.autoAcknowledgePositionRestore(engine, true);
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
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxVisits", "800"));
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
        assertTrue(loadFailure.get().getMessage().contains("pending protocol work"));
        assertTrue(loadFailure.get().getMessage().contains("controlled restore failure"));
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
        assertTrue(loadFailure.get().getMessage().contains("pending protocol work"));
        assertTrue(loadFailure.get().getMessage().contains("controlled restore failure"));
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
        assertTrue(loadFailure.get().getMessage().contains("pending protocol work"));
        assertTrue(loadFailure.get().getMessage().contains("controlled restore failure"));
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
        assertTrue(loadFailure.get().getMessage().contains("pending protocol work"));
        assertTrue(loadFailure.get().getMessage().contains("controlled restore failure"));
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
        assertTrue(waitForRawCommandPrefix(output, "loadsgf ", 3, TimeUnit.SECONDS));
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
        invokeProcessCommandResponseLine(
            engine, successResponseForPrefix(output.rawCommands(), "name"));
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
        invokeProcessCommandResponseLine(
            engine, successResponseForPrefix(output.rawCommands(), "name"));
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
        engine.sendCommand("name");
        engine.sendCommand("version");
        engine.sendCommand("protocol_version");
        blockedOutput.releaseFirstFlush.countDown();
        loadThread.join(1000L);
        assertFalse(loadThread.isAlive());

        assertFalse(blockedOutput.commands().contains("version"));
        invokeProcessCommandResponseLine(engine, "=");

        assertTrue(blockedOutput.commands().contains("version"));
        assertFalse(blockedOutput.commands().contains("protocol_version"));
        invokeProcessCommandResponseLine(engine, "=");
        assertTrue(blockedOutput.commands().contains("protocol_version"));
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

      output.autoAcknowledgePositionRestore(engine, true);
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
      ExactSnapshotRestoreProtocolFixture.install(
          engine,
          command ->
              command.startsWith("loadsgf ")
                  ? ExactSnapshotRestoreProtocolFixture.Response.error(
                      "controlled board restore failure")
                  : ExactSnapshotRestoreProtocolFixture.Response.success());

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
      ExactSnapshotRestoreProtocolFixture.Transport restoreTransport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command -> {
                if (command.startsWith("loadsgf ")) {
                  engine.loadSgf(Path.of(command.substring("loadsgf ".length())));
                  return ExactSnapshotRestoreProtocolFixture.Response.success();
                }
                return null;
              });
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
          engine, successResponseFor(restoreTransport.rawCommands(), "maxVisits"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(restoreTransport.rawCommands(), "ponderingEnabled"));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
          engine.previewForegroundAnalysisLeaseAvailability());
      invokeProcessCommandResponseLine(
          engine, successResponseFor(restoreTransport.rawCommands(), "maxTime"));
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

      output.autoAcknowledgePositionRestore(engine, true);
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
  void ordinaryLifecycleFailureDoesNotClearReadBoardGmaQuarantine() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      setEngineStateUnrestored(engine, true);

      engine.markLifecycleBoardSynchronizationFailed("ordinary lifecycle failure", false);

      assertTrue(engine.hasUnrestoredReadBoardGmaState());
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED,
          engine.previewForegroundAnalysisLeaseAvailability());
    }
  }

  @Test
  void automaticRestartRejectsDuplicateUntilBoardFenceIsAcknowledged() throws Exception {
    try (Harness harness = Harness.open()) {
      ReadyAutomaticRestartLeelaz engine = new ReadyAutomaticRestartLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      Leelaz.AutomaticRestartAttempt attempt =
          engine.beginAutomaticEngineRestartAttempt();
      assertTrue(attempt != null);
      CountDownLatch completed = new CountDownLatch(1);

      attempt.restartClosedEngine(0, completed::countDown);

      assertTrue(waitForRawCommand(output, "name", 1, TimeUnit.SECONDS));

      assertThrows(
          IllegalStateException.class,
          () -> engine.restartClosedEngine(0),
          "a second restart caller must not fall back to a generic root replay while the first"
              + " attempt is live");
      assertEquals(
          1,
          output.commands().stream().filter("name"::equals).count(),
          "a rejected duplicate restart must not start a second engine lifecycle");
      assertFalse(
          engine.hasExclusiveGtpLifecycleTransitionForTest(),
          "the round reservation must be released before the stable frame recheck");
      assertTrue(
          engine.hasExclusiveGtpWorkInProgress(),
          "the completion claim must keep work in progress while the final fence is pending");
      assertNull(
          engine.beginEngineModeReservation(),
          "unrelated engine-mode owners must be rejected while the final fence is pending");
      assertFalse(completed.await(50, TimeUnit.MILLISECONDS));
      acknowledgePositionCommands(engine, output);
      invokeProcessCommandResponseLine(engine, numberedResponseFor(output.rawCommands(), "name"));
      assertTrue(completed.await(1, TimeUnit.SECONDS));
      Leelaz.EngineModeReservation afterFence = engine.beginEngineModeReservation();
      assertTrue(afterFence != null);
      afterFence.close();
    }
  }

  @Test
  void automaticRestartUsesPonderIntentCapturedWithReservation() throws Exception {
    try (Harness harness = Harness.open()) {
      ReadyAutomaticRestartPonderLeelaz engine = new ReadyAutomaticRestartPonderLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      engine.Pondering();
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      Leelaz.AutomaticRestartAttempt attempt =
          engine.beginAutomaticEngineRestartAttempt();
      assertTrue(attempt != null);
      engine.notPondering();
      CountDownLatch completed = new CountDownLatch(1);

      attempt.restartClosedEngine(0, completed::countDown);

      assertTrue(waitForRawCommand(output, "name", 1, TimeUnit.SECONDS));
      assertFalse(
          output.commands().stream().anyMatch(command -> command.startsWith("kata-analyze")));
      acknowledgePositionCommands(engine, output);
      invokeProcessCommandResponseLine(engine, numberedResponseFor(output.rawCommands(), "name"));
      assertTrue(completed.await(1, TimeUnit.SECONDS));
      invokeProcessCommandResponseLine(engine, "=");
      invokeProcessCommandResponseLine(engine, "=");
      assertTrue(
          waitForRawCommandPrefix(output, "kata-analyze", 1, TimeUnit.SECONDS),
          output.commands().toString());
    }
  }
  @Test
  void directRestartReleasesRoundReservationBeforeBoardFenceAndDefersPonder() throws Exception {
    try (Harness harness = Harness.open()) {
      ReadyAutomaticRestartPonderLeelaz engine = new ReadyAutomaticRestartPonderLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      engine.Pondering();
      Lizzie.board.getHistory().getStart().getData().stones[Board.getIndex(3, 3)] = Stone.BLACK;
      Lizzie.leelaz = engine;
      ExactSnapshotRestoreProtocolFixture.Transport transport =

          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.startsWith("loadsgf ")
                      ? ExactSnapshotRestoreProtocolFixture.Response.success()
                      : null);
      CountDownLatch completed = new CountDownLatch(1);

      engine.restartClosedEngine(0, completed::countDown);

      assertTrue(waitForFixtureCommandPrefix(transport, "loadsgf ", 1, TimeUnit.SECONDS));
      assertTrue(waitForFixtureCommandPrefix(transport, "name", 1, TimeUnit.SECONDS));
      assertFalse(
          transport.commands().stream().anyMatch(command -> command.startsWith("kata-analyze")));
      assertFalse(completed.await(50, TimeUnit.MILLISECONDS));
      assertFalse(
          engine.hasExclusiveGtpLifecycleTransitionForTest(),
          "the round reservation must be released before the stable frame recheck");
      assertNull(
          engine.beginEngineModeReservation(),
          "unrelated engine-mode owners must be rejected while the final fence is pending");

      invokeProcessCommandResponseLine(
          engine, numberedResponseFor(transport.rawCommands(), "name"));

      assertTrue(completed.await(1, TimeUnit.SECONDS));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.previewForegroundAnalysisLeaseAvailability());
      invokeProcessCommandResponseLine(engine, "=");
      invokeProcessCommandResponseLine(engine, "=");
      assertTrue(
          waitForFixtureCommandPrefix(transport, "kata-analyze", 1, TimeUnit.SECONDS),
          transport.commands().toString());
    }
  }

  @Test
  void directRestartRestoresFrozenEngineWhenGlobalPrimaryChangesDuringStart() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz replacement = new Leelaz("");
      SwappingAutomaticRestartLeelaz engine = new SwappingAutomaticRestartLeelaz(replacement);
      configureReadyReadBoardGmaEngine(engine);
      Lizzie.board.getHistory().getStart().getData().stones[Board.getIndex(3, 3)] = Stone.BLACK;
      Lizzie.leelaz = engine;
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.startsWith("loadsgf ")
                      ? ExactSnapshotRestoreProtocolFixture.Response.success()
                      : null);
      CountDownLatch completed = new CountDownLatch(1);

      engine.restartClosedEngine(0, completed::countDown);

      assertTrue(waitForFixtureCommandPrefix(transport, "loadsgf ", 1, TimeUnit.SECONDS));
      assertTrue(waitForFixtureCommandPrefix(transport, "name", 1, TimeUnit.SECONDS));
      assertFalse(completed.await(50, TimeUnit.MILLISECONDS));
      invokeProcessCommandResponseLine(
          engine, numberedResponseFor(transport.rawCommands(), "name"));

      assertTrue(completed.await(1, TimeUnit.SECONDS));
      assertTrue(engine.isLoaded());
      assertSame(replacement, Lizzie.leelaz);
    }
  }

  @Test
  void directRestartResumesAnalysisOnlyAfterBoardFenceAndRuntimeSettings() throws Exception {
    try (Harness harness = Harness.open()) {
      ReadyAutomaticRestartPonderLeelaz engine = new ReadyAutomaticRestartPonderLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      engine.Pondering();
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      CountDownLatch completed = new CountDownLatch(1);

      engine.restartClosedEngine(0, completed::countDown);

      assertTrue(waitForRawCommand(output, "name", 1, TimeUnit.SECONDS));
      assertFalse(
          output.commands().stream().anyMatch(command -> command.startsWith("kata-analyze")),
          "Analysis must not start before the board synchronization fence is acknowledged.");
      assertFalse(
          engine.hasExclusiveGtpLifecycleTransitionForTest(),
          "direct restart must release the restore reservation before stable publication");
      assertNull(
          engine.beginEngineModeReservation(),
          "unrelated engine-mode owners must be rejected while the final fence is pending");

      acknowledgePositionCommands(engine, output);
      invokeProcessCommandResponseLine(engine, numberedResponseFor(output.rawCommands(), "name"));

      assertTrue(completed.await(1, TimeUnit.SECONDS));
      Leelaz.EngineModeReservation afterRestart = engine.beginEngineModeReservation();
      assertTrue(afterRestart != null);
      afterRestart.close();
      invokeProcessCommandResponseLine(engine, "=");
      invokeProcessCommandResponseLine(engine, "=");
      assertTrue(
          waitForRawCommandPrefix(output, "kata-analyze", 1, TimeUnit.SECONDS),
          output.commands().toString());
      List<String> commands = output.commands();
      int boardFence = commands.indexOf("name");
      int timeSettings = commands.indexOf("kata-time_settings none");
      int maxTime = indexOfCommandStartingWith(commands, "kata-set-param maxTime ");
      int analyze = indexOfCommandStartingWith(commands, "kata-analyze ");
      assertTrue(boardFence >= 0);
      assertTrue(timeSettings > boardFence);
      assertTrue(maxTime > timeSettings);
      assertTrue(analyze > maxTime);
      assertEquals(
          commands.size() - 1,
          analyze,
          "No startup command may follow and silently stop the recovered analysis stream.");
    }
  }

  @Test
  void directRestartEmptyPreparationCannotReenterExactRestoreAfterStart() throws Exception {
    try (Harness harness = Harness.open()) {
      LateAnchorRestartLeelaz engine = new LateAnchorRestartLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      CountDownLatch completed = new CountDownLatch(1);

      engine.restartClosedEngine(0, completed::countDown);

      assertTrue(waitForRawCommand(output, "name", 1, TimeUnit.SECONDS));
      assertTrue(output.commands().contains("clear_board"), output.commands().toString());
      assertFalse(
          output.commands().stream().anyMatch(command -> command.startsWith("loadsgf ")),
          output.commands().toString());
      assertTrue(engine.isLoaded());
      assertFalse(engine.hasUnrestoredReadBoardGmaState());
      assertFalse(
          engine.hasExclusiveGtpLifecycleTransitionForTest(),
          "the round reservation must be released before the stable frame recheck");
      assertNull(
          engine.beginEngineModeReservation(),
          "unrelated engine-mode owners must be rejected while the final fence is pending");
      acknowledgePositionCommands(engine, output);
      invokeProcessCommandResponseLine(engine, numberedResponseFor(output.rawCommands(), "name"));
      assertTrue(completed.await(1, TimeUnit.SECONDS));
      Leelaz.EngineModeReservation afterRestart = engine.beginEngineModeReservation();
      assertTrue(afterRestart != null);
      afterRestart.close();
    }
  }

  @Test
  void automaticRestartReadinessTimeoutReleasesReservationWithoutCreatingGmaQuarantine()
      throws Exception {
    try (Harness harness = Harness.open()) {
      TimeoutAutomaticRestartLeelaz engine = new TimeoutAutomaticRestartLeelaz();
      Lizzie.leelaz = engine;
      Leelaz.AutomaticRestartAttempt attempt =
          engine.beginAutomaticEngineRestartAttempt();
      assertTrue(attempt != null);
      CountDownLatch completed = new CountDownLatch(1);

      attempt.restartClosedEngine(0, completed::countDown);

      boolean completedBeforeControlledRelease =
          completed.await(250, TimeUnit.MILLISECONDS);
      if (!completedBeforeControlledRelease) {
        engine.isCheckingName = false;
        engine.isLoaded = true;
        completed.await(1, TimeUnit.SECONDS);
      }
      assertTrue(completedBeforeControlledRelease);
      assertFalse(engine.isLoaded());
      assertFalse(
          engine.hasUnrestoredReadBoardGmaState(),
          "an ordinary lifecycle readiness timeout must not create a ReadBoard GMA quarantine");
      Leelaz.ExclusiveGtpLifecycleReservation manualRecovery =
          engine.beginExclusiveGtpLifecycleReservation();
      assertTrue(manualRecovery != null);
      manualRecovery.close();
    }
  }

  @Test
  void automaticRestartReadinessTimeoutPreservesPreexistingGmaQuarantine() throws Exception {
    try (Harness harness = Harness.open()) {
      TimeoutAutomaticRestartLeelaz engine = new TimeoutAutomaticRestartLeelaz();
      Lizzie.leelaz = engine;
      setEngineStateUnrestored(engine, true);
      CountDownLatch completed = new CountDownLatch(1);

      engine.restartClosedEngine(0, completed::countDown);


      boolean completedBeforeControlledRelease =
          completed.await(250, TimeUnit.MILLISECONDS);
      if (!completedBeforeControlledRelease) {
        engine.isCheckingName = false;
        engine.isLoaded = true;
        completed.await(1, TimeUnit.SECONDS);
      }
      assertTrue(completedBeforeControlledRelease);
      assertFalse(engine.isLoaded());
      assertTrue(
          engine.hasUnrestoredReadBoardGmaState(),
          "a preexisting ReadBoard GMA quarantine must survive an automatic restart readiness"
              + " timeout");
      Leelaz.ExclusiveGtpLifecycleReservation manualRecovery =
          engine.beginExclusiveGtpLifecycleReservation();
      assertTrue(manualRecovery != null);
      manualRecovery.close();
    }
  }

  private static void setEngineStateUnrestored(Leelaz engine, boolean value) throws Exception {
    Field field = Leelaz.class.getDeclaredField("engineStateUnrestored");
    field.setAccessible(true);
    field.setBoolean(engine, value);
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
      assertEquals(
          1,
          pendingResponseHandlerCount(engine),
          "a terminal play line must not retire the outstanding acknowledged preparation command");
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
      // The terminal requested cancellation, so the maxTime snapshot ACK starts restoration of
      // the already-acknowledged pondering override without sending maxVisits or genmove.
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
      // The logical cancel waits for the outstanding snapshot ACK: the captured original is then
      // restored through the cancellation barrier.
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
      assertEquals(1, pendingResponseHandlerCount(engine));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));

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
      assertEquals(
          1,
          pendingResponseHandlerCount(engine),
          "an unowned error line must not retire the outstanding acknowledged preparation command");
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxTime", "2"));
      invokeProcessCommandResponseLine(
          engine, parameterValueResponseFor(output.rawCommands(), "maxVisits", "800"));
      // The genmove response settles the final outstanding handler.
      invokeProcessCommandResponseLine(engine, "=");

      assertEquals(0, pendingResponseHandlerCount(engine));
    }
  }

  @Test
  void lateGmaPlayIsDeliveredToTheRetiredHelperOwnerAfterReplacement() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new Leelaz("");
      Lizzie.leelaz = engine;
      ReadBoard retired = allocate(ReadBoard.class);
      ReadBoard replacement = allocate(ReadBoard.class);
      setBooleanField(retired, "retiredReadBoardGmaTerminalPending", true);
      setBooleanField(replacement, "readBoardGmaPending", true);
      Lizzie.frame.readBoard = replacement;
      engine.bindReadBoardGmaResponseOwner(retired);

      invokeParseLine(engine, "play D4");

      assertFalse(getBooleanField(retired, "retiredReadBoardGmaTerminalPending"));
      assertTrue(
          getBooleanField(replacement, "readBoardGmaPending"),
          "late old-helper play must not consume the replacement helper's pending GMA.");
    }
  }

  @Test
  void lateGmaGenerationCannotConsumeTheCurrentHelpersPendingRequest() throws Exception {
    ReadBoard readBoard = allocate(ReadBoard.class);
    Object currentIdentity = new Object();
    setBooleanField(readBoard, "readBoardGmaPending", true);
    setObjectField(readBoard, "readBoardGmaPendingIdentity", currentIdentity);
    setLongField(readBoard, "readBoardGmaPendingGeneration", 12L);

    assertFalse(readBoard.handleReadBoardGmaEnginePlay(currentIdentity, 11L, "D4"));
    assertTrue(getBooleanField(readBoard, "readBoardGmaPending"));

    assertTrue(readBoard.handleReadBoardGmaEnginePlay(currentIdentity, 12L, "D4"));
    assertFalse(getBooleanField(readBoard, "readBoardGmaPending"));
  }

  @Test
  void engineTerminationDuringGmaInFlightFailsSessionAndReleasesCapturedReservation()
      throws Exception {
    try (Harness harness = Harness.open()) {
      EngineTerminationGmaFixture fixture = openEngineTerminationGmaFixture();
      CountingReleaseLeelaz engine = fixture.engine();
      RecordingOutputStream output = fixture.output();
      ReadBoard readBoard = fixture.readBoard();
      ReadBoardGmaSession session = fixture.session();

      assertTrue(
          readBoard.failReadBoardGmaSessionForEngineTermination(engine, "engine transport closed"));
      assertFalse(
          readBoard.failReadBoardGmaSessionForEngineTermination(
              engine, "duplicate transport close"));

      ReadBoardGmaSession.Terminal terminal =
          assertInstanceOf(ReadBoardGmaSession.Terminal.class, session.state());
      assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
      assertEquals(
          ReadBoardGmaSession.FailureCategory.PROCESS_TERMINATED,
          terminal.firstFailure().category());
      assertTrue(engine.hasUnrestoredReadBoardGmaState());
      assertTrue(
          waitForReleaseRequests(engine.releaseRequests, 1, 1, TimeUnit.SECONDS),
          "transport termination must release through the session capability");
      assertNull(engine.currentReadBoardGmaReservation());
      assertNull(boundReadBoardGmaSession(readBoard));
    }
  }

  @Test
  void engineTerminationDuringExactRestoreFailsSessionAndCancelsLateCompletion() throws Exception {
    try (Harness harness = Harness.open()) {
      EngineTerminationGmaFixture fixture = openEngineTerminationGmaFixture();
      CountingReleaseLeelaz engine = fixture.engine();
      RecordingOutputStream output = fixture.output();
      ReadBoard readBoard = fixture.readBoard();
      ReadBoardGmaSession session = fixture.session();
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(engine, command -> null);

      invokeParseLine(engine, "play pass");
      assertTrue(
          waitForSessionState(
              session, ReadBoardGmaSession.RestoringExact.class, 1, TimeUnit.SECONDS));
      assertTrue(
          waitForCommandCount(transport, "loadsgf ", 1, 1, TimeUnit.SECONDS),
          "the exact participant must have a physical loadsgf in flight");

      assertTrue(
          readBoard.failReadBoardGmaSessionForEngineTermination(
              engine, "engine exited during exact"));

      ReadBoardGmaSession.Terminal terminal =
          assertInstanceOf(ReadBoardGmaSession.Terminal.class, session.state());
      assertEquals(
          ReadBoardGmaSession.FailureCategory.PROCESS_TERMINATED,
          terminal.firstFailure().category());
      assertTrue(engine.hasUnrestoredReadBoardGmaState());
      assertTrue(
          waitForReleaseRequests(engine.releaseRequests, 1, 1, TimeUnit.SECONDS),
          "exact termination must release through the session capability");
      assertNull(engine.currentReadBoardGmaReservation());
      assertNull(boundReadBoardGmaSession(readBoard));
    }
  }

  @Test
  void engineTerminationDuringRuntimeRestoreFailsSessionAndAbsorbsLateAck() throws Exception {
    try (Harness harness = Harness.open()) {
      EngineTerminationGmaFixture fixture = openEngineTerminationGmaFixture();
      CountingReleaseLeelaz engine = fixture.engine();
      RecordingOutputStream output = fixture.output();
      ReadBoard readBoard = fixture.readBoard();
      ReadBoardGmaSession session = fixture.session();
      engine.retireReadBoardGmaSession();
      ExactSnapshotRestoreProtocolFixture.install(
          engine,
          command ->
              command.startsWith("loadsgf ")
                  ? ExactSnapshotRestoreProtocolFixture.Response.success()
                  : null);

      invokeParseLine(engine, "play pass");
      assertTrue(
          waitForSessionState(
              session, ReadBoardGmaSession.RestoringRuntime.class, 1, TimeUnit.SECONDS));
      assertTrue(
          waitForRawCommandPrefix(
              output, "kata-set-param ponderingEnabled", 1, TimeUnit.SECONDS),
          "the runtime participant must have a physical ACK pending");

      assertTrue(
          readBoard.failReadBoardGmaSessionForEngineTermination(
              engine, "engine exited during runtime"));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(output.rawCommands(), "ponderingEnabled"));

      ReadBoardGmaSession.Terminal terminal =
          assertInstanceOf(ReadBoardGmaSession.Terminal.class, session.state());
      assertEquals(
          ReadBoardGmaSession.FailureCategory.PROCESS_TERMINATED,
          terminal.firstFailure().category());
      assertTrue(engine.hasUnrestoredReadBoardGmaState());
      assertTrue(
          waitForReleaseRequests(engine.releaseRequests, 1, 1, TimeUnit.SECONDS),
          "runtime termination must release through the session capability");
      assertEquals(1, engine.releaseRequests.get());
      assertNull(engine.currentReadBoardGmaReservation());
      assertNull(boundReadBoardGmaSession(readBoard));
    }
  }

  @Test
  void readBoardGmaSessionExactParticipantRestoresBoardAndReleasesReservation() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session = beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, null);
      // An isolation terminal (pass) runs the exact participant; an authorized PLAYED would
      // complete the session directly without any exact restore. A retired session then runs the
      // runtime participant after exact success; an active session keeps its runtime settings
      // between hands and reaches the terminal directly.
      engine.retireReadBoardGmaSession();
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.startsWith("loadsgf ")
                      ? ExactSnapshotRestoreProtocolFixture.Response.success()
                      : null);

      invokeParseLine(engine, "play pass");

      assertTrue(
          waitForFixtureCommandPrefix(transport, "kata-set-param ", 1, TimeUnit.SECONDS),
          "the runtime participant must restore the captured parameters after exact success");
      int loadSgfIndex =
          indexOfCommandStartingWith(transport.commands(), "loadsgf ");
      int runtimeRestoreIndex =
          indexOfCommandStartingWith(transport.commands(), "kata-set-param ");
      assertTrue(
          loadSgfIndex >= 0 && runtimeRestoreIndex > loadSgfIndex,
          "no runtime restore may start before the exact participant consumed loadsgf; commands="
              + transport.commands());
      assertInstanceOf(ReadBoardGmaSession.RestoringRuntime.class, sessionRef.get().state());
      acknowledgeReadBoardGmaRuntimeRestore(engine, transport);

      ReadBoardGmaSession.Terminal terminal = awaitGmaSessionTerminal(sessionRef);
      assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminal.outcome());
      assertNull(
          engine.currentReadBoardGmaReservation(),
          "the session terminal must release the captured reservation exactly once");
      assertNull(boundReadBoardGmaSession(readBoard));
      assertEquals(
          1,
          transport.commands().stream().filter(command -> command.startsWith("loadsgf ")).count(),
          "the exact participant must consume exactly one loadsgf per session");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"play pass", "play resign", "? engine failed"})
  void readBoardGmaSessionConsumesEveryRecoveryTerminalVariantThroughTheSession(String terminalLine)
      throws Exception {
    try (Harness harness = Harness.open()) {
      // An authorized PLAYED completes directly without exact restore and is covered by the
      // success test; every isolation/recovery terminal runs the exact-then-runtime contract.
      Leelaz engine = readyReadBoardGmaEngine();
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session = beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, null);
      // A retired session runs the runtime participant after exact success.
      engine.retireReadBoardGmaSession();
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.startsWith("loadsgf ")
                      ? ExactSnapshotRestoreProtocolFixture.Response.success()
                      : null);

      invokeParseLine(engine, terminalLine);

      assertTrue(
          waitForFixtureCommandPrefix(transport, "kata-set-param ", 1, TimeUnit.SECONDS),
          "every recovery terminal variant must run the exact-then-runtime recovery contract");
      acknowledgeReadBoardGmaRuntimeRestore(engine, transport);

      assertEquals(
          ReadBoardGmaSession.SessionOutcome.SUCCEEDED,
          awaitGmaSessionTerminal(sessionRef).outcome());
      assertNull(engine.currentReadBoardGmaReservation());
    }
  }

  @Test
  void readBoardGmaSessionHoldsSuccessUntilEveryTargetConsumedLoadSgf() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session = beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, null);
      // A retired session runs the runtime participant after exact success.
      engine.retireReadBoardGmaSession();
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      CountDownLatch loadSgfArrived = new CountDownLatch(1);
      CountDownLatch releaseLoadSgf = new CountDownLatch(1);
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command -> {
                if (command.startsWith("loadsgf ")) {
                  loadSgfArrived.countDown();
                  awaitLatch(releaseLoadSgf);
                  return ExactSnapshotRestoreProtocolFixture.Response.success();
                }
                return null;
              });

      invokeParseLine(engine, "play pass");

      assertTrue(
          loadSgfArrived.await(1, TimeUnit.SECONDS),
          "the exact participant must send loadsgf to every captured target");
      assertInstanceOf(ReadBoardGmaSession.RestoringExact.class, sessionRef.get().state());
      assertNotNull(
          engine.currentReadBoardGmaReservation(),
          "the reservation must be held while the exact participant has not consumed loadsgf");
      // The session state alone gates the runtime participant: while loadsgf has not been
      // consumed the module cannot emit the runtime start effect (the writer thread is blocked
      // inside the fixture, so the transport cannot be read here).

      releaseLoadSgf.countDown();

      assertTrue(
          waitForFixtureCommandPrefix(transport, "kata-set-param ", 1, TimeUnit.SECONDS),
          "the runtime participant must start exactly once after exact success");
      assertInstanceOf(ReadBoardGmaSession.RestoringRuntime.class, sessionRef.get().state());
      acknowledgeReadBoardGmaRuntimeRestore(engine, transport);

      assertEquals(
          ReadBoardGmaSession.SessionOutcome.SUCCEEDED,
          awaitGmaSessionTerminal(sessionRef).outcome());
      assertNull(engine.currentReadBoardGmaReservation());
    }
  }

  @Test
  void readBoardGmaSessionLoadSgfErrorFailsClosedWithoutRuntimeRestore() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session = beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, null);
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.startsWith("loadsgf ")
                      ? ExactSnapshotRestoreProtocolFixture.Response.error(
                          "controlled board restore failure")
                      : null);

      invokeParseLine(engine, "play pass");

      ReadBoardGmaSession.Terminal terminal = awaitGmaSessionTerminal(sessionRef);
      assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
      assertEquals(
          ReadBoardGmaSession.FailureCategory.GTP_ERROR, terminal.firstFailure().category());
      assertNull(engine.currentReadBoardGmaReservation());
      assertTrue(
          engine.hasUnrestoredReadBoardGmaState(),
          "an exact participant failure must quarantine the engine fail-closed");
      assertNull(boundReadBoardGmaSession(readBoard));
      assertTrue(
          transport.commands().stream().noneMatch(command -> command.startsWith("play ")),
          "no captured tail replay may start after a loadsgf failure");
      assertTrue(
          transport.commands().stream().noneMatch(command -> command.startsWith("kata-set-param")),
          "no runtime restore may start after an exact participant failure");
    }
  }

  @Test
  void readBoardGmaSessionFailureSkipsPreAdmissionDeferredRestore() throws Exception {
    try (Harness harness = Harness.open()) {
      BlockedFailureQuarantineLeelaz engine = new BlockedFailureQuarantineLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      // Arm the hand exactly like beginReadBoardGmaSessionHand, but register the deferred engine
      // restore after the schedule and before the acknowledged preparation: the production
      // deferral path records the restore while the hand is still pending, so the restore is
      // already deferred when the session is admitted.
      Lizzie.leelaz = engine;
      Lizzie.frame.bothSync = true;
      LizzieFrame.boardRenderer = new BoardRenderer(false);
      Lizzie.board = new SilentPlacementBoard();
      setBooleanField(readBoard, "readBoardGmaAutoPlayActive", true);
      setObjectField(readBoard, "readBoardGmaAutoPlayColor", Stone.BLACK);
      setBooleanField(readBoard, "readBoardTurnTrusted", true);
      setIntField(readBoard, "readBoardGmaTimeSeconds", 5);
      setIntField(readBoard, "readBoardGmaMaxVisits", 1000);
      setBooleanField(readBoard, "readBoardWebSocketPonderingNoticeAcknowledged", true);
      Object identity = new Object();
      setObjectField(readBoard, "trackingEligibilityIdentity", identity);
      setLongField(readBoard, "readBoardGmaSessionGeneration", 1L);
      Lizzie.frame.readBoard = readBoard;

      boolean scheduled = invokeScheduleReadBoardGmaIfNeeded(readBoard, "test");
      assertTrue(
          scheduled && !output.commands().isEmpty(),
          "the GMA hand must be scheduled and its preparation commands sent; scheduled="
              + scheduled
              + " commands="
              + output.commands());
      assertEquals(
          "kata-get-param ponderingEnabled",
          output.commands().get(0),
          "the acknowledged preparation must start with the pondering snapshot");
      assertNull(
          boundReadBoardGmaSession(readBoard),
          "the session must not be admitted before every matching get/set ACK");
      assertFalse(
          output.commands().stream()
              .anyMatch(command -> command.startsWith("kata-genmove_analyze")),
          "the genmove must not be sent before every required get/set ACK");

      // Pre-admission deferred restore: the helper owes one engine restore for the current node
      // while the hand is still preparing; the restore stays deferred across session admission.
      setBooleanField(readBoard, "readBoardGmaEngineRestorePending", true);
      setObjectField(
          readBoard,
          "readBoardGmaDeferredRestoreNode",
          Lizzie.board.getHistory().getCurrentHistoryNode());

      acknowledgeInitialGmaCommands(engine, output);

      ReadBoardGmaSession session = boundReadBoardGmaSession(readBoard);
      assertNotNull(
          session,
          "the GMA hand must admit a session after the acknowledged preparation");
      assertTrue(
          output.commands().stream()
              .anyMatch(command -> command.startsWith("kata-genmove_analyze ")),
          "the admitted session must send the genmove only after the acknowledged preparation;"
              + " commands="
              + output.commands());
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.startsWith("loadsgf ")
                      ? ExactSnapshotRestoreProtocolFixture.Response.error(
                          "controlled board restore failure")
                      : null);

      invokeParseLine(engine, "play pass");
      try {
        // The exact participant's loadsgf fails and the session locks its failure; the failure
        // handling is blocked so the quarantine cannot clear the reservation yet. The legacy
        // terminal worker must not replay the pre-admission deferred restore (a second loadsgf)
        // inside that window.
        assertTrue(
            engine.failureArrived.await(1, TimeUnit.SECONDS),
            "the failed session must reach its failure handling");
        assertTrue(
            waitForCommandCount(transport, "loadsgf ", 1, 1, TimeUnit.SECONDS),
            "the exact participant must send its own loadsgf");
        assertFalse(
            waitForCommandCount(transport, "loadsgf ", 2, 300, TimeUnit.MILLISECONDS),
            "the legacy deferred-restore worker must not send a second loadsgf while the "
                + "session failure handling is blocked; commands="
                + transport.commands());

        engine.releaseFailure.countDown();

        assertTrue(
            waitForReleaseRequests(engine.releaseRequests, 1, 1, TimeUnit.SECONDS),
            "the failed session must request its reservation release");
        assertNull(
            engine.currentReadBoardGmaReservation(),
            "the failed session must release its captured reservation");
        assertTrue(
            engine.hasUnrestoredReadBoardGmaState(),
            "an exact participant failure must quarantine the engine fail-closed");

        ReadBoardGmaSession.Terminal terminal = awaitGmaSessionTerminal(sessionRef);
        assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
        assertEquals(
            ReadBoardGmaSession.FailureCategory.GTP_ERROR, terminal.firstFailure().category());
        assertEquals(
            1,
            transport.commands().stream()
                .filter(command -> command.startsWith("loadsgf "))
                .count(),
            "the failed session must consume exactly one loadsgf and never retry it; commands="
                + transport.commands());
        assertTrue(
            transport.commands().stream()
                .noneMatch(command -> command.startsWith("kata-set-param")),
            "no runtime restore may start after an exact participant failure");
        assertTrue(
            transport.commands().stream()
                .noneMatch(command -> command.startsWith("kata-genmove_analyze")),
            "no continuation hand may start after a failed session");
        assertEquals(
            1,
            output.commands().stream()
                .filter(command -> command.startsWith("kata-genmove_analyze "))
                .count(),
            "the failed session must not schedule another genmove");
        assertEquals(
            1,
            engine.releaseRequests.get(),
            "the failed session must release its reservation exactly once");
        assertNull(
            boundReadBoardGmaSession(readBoard),
            "the failed session terminal must clear the published binding");
      } finally {
        engine.releaseFailure.countDown();
      }
    }
  }

  @Test
  void readBoardGmaSessionFailureDefersRestoreRequestedDuringBlockedQuarantine() throws Exception {
    try (Harness harness = Harness.open()) {
      BlockedFailureQuarantineLeelaz engine = new BlockedFailureQuarantineLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session = beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, null);
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.startsWith("loadsgf ")
                      ? ExactSnapshotRestoreProtocolFixture.Response.error(
                          "controlled board restore failure")
                      : null);

      invokeParseLine(engine, "play pass");
      try {
        // The exact participant's loadsgf fails and the session locks its failure; the failure
        // handling is blocked so the quarantine cannot clear the reservation yet.
        assertTrue(
            engine.failureArrived.await(1, TimeUnit.SECONDS),
            "the failed session must reach its blocked failure handling");
        assertTrue(
            waitForCommandCount(transport, "loadsgf ", 1, 1, TimeUnit.SECONDS),
            "the exact participant must send its own loadsgf");

        // A real authoritative restore request lands while the failure handling is still
        // blocked: the legacy flush must not send a second loadsgf into the engine before the
        // quarantine clears the reservation.
        BoardHistoryNode restoreNode = Lizzie.board.getHistory().getCurrentHistoryNode();
        AtomicReference<Throwable> restoreFailure = new AtomicReference<>();
        Thread restoreThread =
            new Thread(
                () -> {
                  try {
                    invokeRequestReadBoardGmaEngineRestore(
                        readBoard, "test-failure-window", restoreNode);
                  } catch (Throwable failure) {
                    restoreFailure.set(failure);
                  }
                },
                "readboard-gma-failure-window-restore-test");
        restoreThread.setDaemon(true);
        restoreThread.start();
        assertFalse(
            waitForCommandCount(transport, "loadsgf ", 2, 500, TimeUnit.MILLISECONDS),
            "a restore request during the blocked failure handling must not send a second "
                + "loadsgf; commands="
                + transport.commands());

        engine.releaseFailure.countDown();
        restoreThread.join(1000L);
        assertFalse(restoreThread.isAlive());
        assertNull(
            restoreFailure.get(),
            "the deferred restore request must converge without failing the engine again");

        assertTrue(
            waitForReleaseRequests(engine.releaseRequests, 1, 1, TimeUnit.SECONDS),
            "the failed session must request its reservation release");
        assertNull(
            engine.currentReadBoardGmaReservation(),
            "the failed session must release its captured reservation");
        assertTrue(
            engine.hasUnrestoredReadBoardGmaState(),
            "an exact participant failure must quarantine the engine fail-closed");

        ReadBoardGmaSession.Terminal terminal = awaitGmaSessionTerminal(sessionRef);
        assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
        assertEquals(
            ReadBoardGmaSession.FailureCategory.GTP_ERROR, terminal.firstFailure().category());
        assertEquals(
            1,
            transport.commands().stream()
                .filter(command -> command.startsWith("loadsgf "))
                .count(),
            "the failed session must consume exactly one loadsgf and never retry it; commands="
                + transport.commands());
        assertTrue(
            transport.commands().stream()
                .noneMatch(command -> command.startsWith("kata-set-param")),
            "no runtime restore may start after an exact participant failure");
        assertTrue(
            transport.commands().stream()
                .noneMatch(command -> command.startsWith("kata-genmove_analyze")),
            "no continuation hand may start after a failed session");
        assertEquals(
            1,
            output.commands().stream()
                .filter(command -> command.startsWith("kata-genmove_analyze "))
                .count(),
            "the failed session must not schedule another genmove");
        assertEquals(
            1,
            engine.releaseRequests.get(),
            "the failed session must release its reservation exactly once");
        assertNull(
            boundReadBoardGmaSession(readBoard),
            "the failed session terminal must clear the published binding");
      } finally {
        engine.releaseFailure.countDown();
      }
    }
  }

  @Test
  void readBoardGmaSessionFailureToctouRestoreRoutingSkipsLegacyRetry() throws Exception {
    try (Harness harness = Harness.open()) {
      BlockedCaptureRestoreLeelaz engine = new BlockedCaptureRestoreLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session = beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, null);
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      // The transport holds every loadsgf response: the exact participant stays RestoringExact
      // until the test settles the response manually.
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(engine, command -> null);
      // Only the post-admission captures block; the admission-time capture already passed.
      engine.blockRestoreCapture = true;

      AtomicReference<Throwable> playFailure = new AtomicReference<>();
      Thread playThread =
          new Thread(
              () -> {
                try {
                  invokeParseLine(engine, "play pass");
                } catch (Throwable failure) {
                  playFailure.set(failure);
                }
              },
              "readboard-gma-toctou-play-test");
      playThread.setDaemon(true);
      playThread.start();
      try {
        assertTrue(
            waitForCommandCount(transport, "loadsgf ", 1, 1, TimeUnit.SECONDS),
            "the exact participant must send its held loadsgf");
        playThread.join(1000L);
        assertFalse(playThread.isAlive());
        assertNull(playFailure.get());

        // A real authoritative restore request routes through the session while it is still
        // RestoringExact; its admission capture is latch-blocked inside the routing.
        BoardHistoryNode restoreNode = Lizzie.board.getHistory().getCurrentHistoryNode();
        AtomicReference<Throwable> restoreFailure = new AtomicReference<>();
        Thread restoreThread =
            new Thread(
                () -> {
                  try {
                    invokeRequestReadBoardGmaEngineRestore(
                        readBoard, "test-toctou-window", restoreNode);
                  } catch (Throwable failure) {
                    restoreFailure.set(failure);
                  }
                },
                "readboard-gma-toctou-restore-test");
        restoreThread.setDaemon(true);
        restoreThread.start();
        assertTrue(
            engine.captureStarted.await(1, TimeUnit.SECONDS),
            "the routed restore request must block inside its admission capture");

        // The session fails while the routed capture is still blocked: the terminal transition
        // and failure handling run first, so the routing's deferExactRestore lands after the
        // terminal and the legacy fallback must not retry the restore.
        ReadBoardGmaSession.RestoringExact restoring =
            assertInstanceOf(ReadBoardGmaSession.RestoringExact.class, sessionRef.get().state());
        ReadBoardGmaSession.ExactParticipantCapability exactCapability =
            restoring.capturedExactOperation().capability();
        AtomicReference<Throwable> failThreadFailure = new AtomicReference<>();
        Thread failThread =
            new Thread(
                () -> {
                  try {
                    session.completeExact(
                        exactCapability,
                        new ReadBoardGmaSession.ParticipantResult.Failed(
                            new ReadBoardGmaSession.ParticipantFailure(
                                ReadBoardGmaSession.FailureCategory.GTP_ERROR,
                                engine.currentEngineIncarnation(),
                                "controlled board restore failure")));
                  } catch (Throwable failure) {
                    failThreadFailure.set(failure);
                  }
                },
                "readboard-gma-toctou-fail-test");
        failThread.setDaemon(true);
        failThread.start();
        assertTrue(
            engine.failureArrived.await(1, TimeUnit.SECONDS),
            "the failed session must reach its blocked failure handling");

        engine.releaseCapture.countDown();
        assertFalse(
            waitForCommandCount(transport, "loadsgf ", 2, 500, TimeUnit.MILLISECONDS),
            "a routed restore request whose deferral landed after the terminal failure must not "
                + "fall back to a second loadsgf; commands="
                + transport.commands());

        engine.releaseFailure.countDown();
        failThread.join(1000L);
        assertFalse(failThread.isAlive());
        assertNull(failThreadFailure.get());

        assertTrue(
            waitForReleaseRequests(engine.releaseRequests, 1, 1, TimeUnit.SECONDS),
            "the failed session must request its reservation release");
        assertNull(
            engine.currentReadBoardGmaReservation(),
            "the failed session must release its captured reservation");
        assertTrue(
            engine.hasUnrestoredReadBoardGmaState(),
            "an exact participant failure must quarantine the engine fail-closed");

        ReadBoardGmaSession.Terminal terminal = awaitGmaSessionTerminal(sessionRef);
        assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
        assertEquals(
            ReadBoardGmaSession.FailureCategory.GTP_ERROR, terminal.firstFailure().category());
        assertEquals(
            1,
            transport.commands().stream()
                .filter(command -> command.startsWith("loadsgf "))
                .count(),
            "the failed session must consume exactly one loadsgf and never retry it; commands="
                + transport.commands());
        assertTrue(
            transport.commands().stream()
                .noneMatch(command -> command.startsWith("kata-set-param")),
            "no runtime restore may start after an exact participant failure");
        assertTrue(
            transport.commands().stream()
                .noneMatch(command -> command.startsWith("kata-genmove_analyze")),
            "no continuation hand may start after a failed session");
        assertEquals(
            1,
            output.commands().stream()
                .filter(command -> command.startsWith("kata-genmove_analyze "))
                .count(),
            "the failed session must not schedule another genmove");
        assertEquals(
            1,
            engine.releaseRequests.get(),
            "the failed session must release its reservation exactly once");
        assertNull(
            boundReadBoardGmaSession(readBoard),
            "the failed session terminal must clear the published binding");

        // Cleanup: settle every held loadsgf with the controlled error so the exact participant
        // and any legacy restore worker converge before the harness closes.
        for (String rawCommand : transport.rawCommands()) {
          int firstSpace = rawCommand.indexOf(' ');
          if (firstSpace > 0
              && rawCommand.substring(firstSpace + 1).startsWith("loadsgf ")
              && rawCommand.substring(0, firstSpace).chars().allMatch(Character::isDigit)) {
            invokeProcessCommandResponseLine(
                engine,
                "?" + rawCommand.substring(0, firstSpace) + " controlled board restore failure");
          }
        }
        restoreThread.join(1000L);
        assertFalse(restoreThread.isAlive());
        assertNull(
            restoreFailure.get(),
            "the routed restore request must converge without failing the engine again");
      } finally {
        engine.releaseCapture.countDown();
        engine.releaseFailure.countDown();
      }
    }
  }


  @Test
  void readBoardGmaSessionTailAckGatesExactSuccessAndRuntimeRestore() throws Exception {
    try (Harness harness = Harness.open()) {
      // An anchored board (usable snapshot root with a stone, black to play) makes the session
      // capture a real MOVE tail for the black final play.
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      Board board = new SilentPlacementBoard();
      Stone[] stones = new Stone[Board.boardWidth * Board.boardHeight];
      Arrays.fill(stones, Stone.EMPTY);
      stones[Board.getIndex(0, 0)] = Stone.BLACK;
      Zobrist zobrist = new Zobrist();
      zobrist.toggleStone(0, 0, Stone.BLACK);
      board.setHistory(
          new BoardHistoryList(
              BoardData.snapshot(
                  stones,
                  Optional.empty(),
                  Stone.EMPTY,
                  true,
                  zobrist,
                  0,
                  new int[Board.boardWidth * Board.boardHeight],
                  0,
                  0,
                  0,
                  0)));
      Lizzie.board = board;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session =
          beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, board);
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      CountDownLatch tailEnqueued = new CountDownLatch(1);

      // The final play advances the authoritative board while the request is still in flight; the
      // latest-wins restore intent is re-captured from the advanced node, so the isolation
      // terminal's exact restore replays the captured MOVE tail for the black final play.
      int[] coords = Board.convertNameToCoordinates("D4");
      Lizzie.board.placeFromReadBoardGma(coords[0], coords[1], Stone.BLACK);
      invokeUpdateReadBoardGmaRestoreIntent(
          readBoard, Lizzie.board.getHistory().getCurrentHistoryNode());
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command -> {
                if (command.startsWith("loadsgf ")) {
                  return ExactSnapshotRestoreProtocolFixture.Response.success();
                }
                if (command.startsWith("play ")) {
                  // Hold the numbered tail ACK so the test can prove that exact success and the
                  // runtime participant remain gated behind it.
                  tailEnqueued.countDown();
                  return null;
                }
                return null;
              });
      // A retired session runs the runtime participant after exact success.
      engine.retireReadBoardGmaSession();

      invokeParseLine(engine, "play pass");

      assertTrue(
          tailEnqueued.await(1, TimeUnit.SECONDS),
          "the captured MOVE/PASS tail must be dispatched before exact success is reported;"
              + " commands="
              + transport.commands());
      assertFalse(
          waitForFixtureCommandPrefix(transport, "kata-set-param ", 100, TimeUnit.MILLISECONDS),
          "the runtime participant must not start before the numbered tail ACK");
      String tailAck = successResponseForPrefix(transport.rawCommands(), "play B D4");
      invokeProcessCommandResponseLine(engine, tailAck);
      assertTrue(
          waitForFixtureCommandPrefix(transport, "kata-set-param ", 1, TimeUnit.SECONDS),
          "the runtime participant must start after the numbered tail ACK completes exact restore");
      acknowledgeReadBoardGmaRuntimeRestore(engine, transport);
      ReadBoardGmaSession.Terminal terminal = awaitGmaSessionTerminal(sessionRef);
      assertEquals(
          ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminal.outcome(),
          "exact success must wait for both the tail ACK and runtime restore ACKs");
      assertTrue(
          transport.commands().stream().anyMatch(command -> command.equals("play B D4")),
          "the captured tail command must have been accepted into the ordinary queue");
      assertNull(engine.currentReadBoardGmaReservation());

      // A late duplicate numbered tail ACK arrives after the session terminal: it must be absorbed
      // with no state change, no re-publication and no re-release.
      invokeProcessCommandResponseLine(engine, tailAck);
      // A late duplicate runtime restore ACK is absorbed the same way.
      invokeProcessCommandResponseLine(
          engine, successResponseFor(transport.rawCommands(), "maxTime"));
      assertEquals(
          ReadBoardGmaSession.SessionOutcome.SUCCEEDED, awaitGmaSessionTerminal(sessionRef).outcome());
      assertNull(engine.currentReadBoardGmaReservation());
    }
  }

  @Test
  void readBoardGmaSessionStaleTailAdmissionFailsClosedBeforeDispatch() throws Exception {
    try (Harness harness = Harness.open()) {
      // An anchored board (usable snapshot root with a stone, black to play) makes the session
      // capture a real MOVE tail for the black final play.
      Leelaz engine = readyReadBoardGmaEngine();
      Lizzie.leelaz = engine;
      Board board = new SilentPlacementBoard();
      Stone[] stones = new Stone[Board.boardWidth * Board.boardHeight];
      Arrays.fill(stones, Stone.EMPTY);
      stones[Board.getIndex(0, 0)] = Stone.BLACK;
      Zobrist zobrist = new Zobrist();
      zobrist.toggleStone(0, 0, Stone.BLACK);
      board.setHistory(
          new BoardHistoryList(
              BoardData.snapshot(
                  stones,
                  Optional.empty(),
                  Stone.EMPTY,
                  true,
                  zobrist,
                  0,
                  new int[Board.boardWidth * Board.boardHeight],
                  0,
                  0,
                  0,
                  0)));
      Lizzie.board = board;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session = beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, board);
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);

      // The final play advances the authoritative board while the request is still in flight; the
      // latest-wins restore intent is re-captured from the advanced node, so the isolation
      // terminal's exact restore captures the real MOVE tail for the black final play.
      int[] coords = Board.convertNameToCoordinates("D4");
      Lizzie.board.placeFromReadBoardGma(coords[0], coords[1], Stone.BLACK);
      invokeUpdateReadBoardGmaRestoreIntent(
          readBoard, Lizzie.board.getHistory().getCurrentHistoryNode());
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command -> {
                if (command.startsWith("loadsgf ")) {
                  // The engine becomes unrecoverable after the loadsgf is consumed: the captured
                  // tail admission must then fail closed before another command is dispatched.
                  setBooleanField(engine, "engineStateUnrestored", true);
                  return ExactSnapshotRestoreProtocolFixture.Response.success();
                }
                return null;
              });

      invokeParseLine(engine, "play pass");

      ReadBoardGmaSession.Terminal terminal = awaitGmaSessionTerminal(sessionRef);
      assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
      assertEquals(
          ReadBoardGmaSession.FailureCategory.ADMISSION_STALE,
          terminal.firstFailure().category());
      assertNull(engine.currentReadBoardGmaReservation());
      assertTrue(engine.hasUnrestoredReadBoardGmaState());
      assertTrue(
          transport.commands().stream().noneMatch(command -> command.equals("play B D4")),
          "a stale tail admission must fail before dispatching another command to the engine");
    }
  }

  @Test
  void readBoardGmaSessionRuntimeParticipantHoldsSuccessUntilEveryMatchingAck() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session = beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, null);
      // A retired session runs the runtime participant after exact success.
      engine.retireReadBoardGmaSession();
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.startsWith("loadsgf ")
                      ? ExactSnapshotRestoreProtocolFixture.Response.success()
                      : null);

      invokeParseLine(engine, "play pass");

      assertTrue(
          waitForFixtureCommandPrefix(
              transport, "kata-set-param maxVisits 800", 1, TimeUnit.SECONDS),
          "the runtime participant must dispatch every captured parameter restore");
      assertInstanceOf(ReadBoardGmaSession.RestoringRuntime.class, sessionRef.get().state());
      assertNotNull(
          engine.currentReadBoardGmaReservation(),
          "the reservation must be held while the runtime participant awaits its ACKs");

      // Partial ACKs keep the participant pending: only every matching ACK reaches success.
      invokeProcessCommandResponseLine(
          engine, successResponseFor(transport.rawCommands(), "maxTime"));
      assertInstanceOf(ReadBoardGmaSession.RestoringRuntime.class, sessionRef.get().state());
      invokeProcessCommandResponseLine(
          engine, successResponseFor(transport.rawCommands(), "maxVisits"));
      assertInstanceOf(ReadBoardGmaSession.RestoringRuntime.class, sessionRef.get().state());
      invokeProcessCommandResponseLine(
          engine, successResponseFor(transport.rawCommands(), "ponderingEnabled"));

      ReadBoardGmaSession.Terminal terminal = awaitGmaSessionTerminal(sessionRef);
      assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminal.outcome());
      assertNull(
          engine.currentReadBoardGmaReservation(),
          "the session terminal must release the captured reservation exactly once");
      assertNull(boundReadBoardGmaSession(readBoard));

      // A late duplicate restore ACK after the terminal is absorbed with no re-publication and no
      // re-release.
      invokeProcessCommandResponseLine(
          engine, successResponseFor(transport.rawCommands(), "maxTime"));
      assertEquals(
          ReadBoardGmaSession.SessionOutcome.SUCCEEDED, awaitGmaSessionTerminal(sessionRef).outcome());
      assertNull(engine.currentReadBoardGmaReservation());
    }
  }

  @Test
  void readBoardGmaSessionRuntimeAckFromStaleReaderBindingCannotAdvanceBarrier()
      throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session =
          beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, null);
      engine.retireReadBoardGmaSession();
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.startsWith("loadsgf ")
                      ? ExactSnapshotRestoreProtocolFixture.Response.success()
                      : null);

      invokeParseLine(engine, "play pass");

      assertTrue(
          waitForFixtureCommandPrefix(
              transport, "kata-set-param maxVisits 800", 1, TimeUnit.SECONDS));
      assertInstanceOf(ReadBoardGmaSession.RestoringRuntime.class, sessionRef.get().state());
      String staleAck = successResponseFor(transport.rawCommands(), "maxTime");
      Object staleBinding = replaceReaderStreamBinding(engine);
      assertSame(
          staleBinding,
          pendingResponseBindingFor(engine, staleAck),
          "runtime ACK handlers must capture the stream that emitted the command");

      invokeProcessCommandResponseLine(engine, staleAck, staleBinding);

      assertInstanceOf(ReadBoardGmaSession.RestoringRuntime.class, sessionRef.get().state());
      assertSame(
          staleBinding,
          pendingResponseBindingFor(engine, staleAck),
          "a stale response must leave the current GMA ACK handler pending");
      assertTrue(
          readBoard.failReadBoardGmaSessionForEngineTermination(
              engine, "engine replaced before runtime restore ACK"));
      ReadBoardGmaSession.Terminal terminal = awaitGmaSessionTerminal(sessionRef);
      assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
      assertEquals(
          ReadBoardGmaSession.FailureCategory.PROCESS_TERMINATED,
          terminal.firstFailure().category());
      assertNull(engine.currentReadBoardGmaReservation());
    }
  }

  @Test
  void readBoardGmaSessionStaleExactResponseCannotAdvanceSession() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session =
          beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, null);
      engine.retireReadBoardGmaSession();
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(engine, command -> null);

      invokeParseLine(engine, "play pass");

      assertTrue(
          waitForFixtureCommandPrefix(transport, "loadsgf ", 1, TimeUnit.SECONDS),
          "the exact restore command must be written before the stream is replaced");
      String staleResponse = successResponseForPrefix(transport.rawCommands(), "loadsgf ");
      Object staleBinding = pendingResponseBindingFor(engine, staleResponse);
      assertNotNull(staleBinding, "the exact restore response must be bound to its stream");

      replaceReaderStreamBinding(engine);
      invokeProcessCommandResponseLine(engine, staleResponse, staleBinding);

      assertInstanceOf(ReadBoardGmaSession.RestoringExact.class, sessionRef.get().state());
      assertSame(
          staleBinding,
          pendingResponseBindingFor(engine, staleResponse),
          "a stale exact response must leave the current restore handler pending");
      assertTrue(
          readBoard.failReadBoardGmaSessionForEngineTermination(
              engine, "engine replaced before exact restore response"));
      ReadBoardGmaSession.Terminal terminal = awaitGmaSessionTerminal(sessionRef);
      assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
      assertEquals(
          ReadBoardGmaSession.FailureCategory.PROCESS_TERMINATED,
          terminal.firstFailure().category());
      assertTrue(
          transport.commands().stream().noneMatch(command -> command.startsWith("kata-set-param ")),
          "a stale exact response must not advance to runtime restore");
      assertNull(engine.currentReadBoardGmaReservation());
    }
  }

  @Test
  void readBoardGmaSessionStaleRuntimeDispatchDoesNotSendOnReplacement() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session =
          beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, null);
      engine.retireReadBoardGmaSession();
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(engine, command -> null);

      invokeParseLine(engine, "play pass");
      assertTrue(
          waitForFixtureCommandPrefix(transport, "loadsgf ", 1, TimeUnit.SECONDS),
          "the exact restore command must be written before dispatch is interposed");
      String exactResponse = successResponseForPrefix(transport.rawCommands(), "loadsgf ");

      Field barrierField = Leelaz.class.getDeclaredField("readBoardGmaRestoreBarrier");
      barrierField.setAccessible(true);
      java.lang.reflect.Method lockMethod =
          Leelaz.class.getDeclaredMethod("engineArbitrationLock");
      lockMethod.setAccessible(true);
      Object arbitrationLock = lockMethod.invoke(engine);
      AtomicReference<Throwable> responseFailure = new AtomicReference<>();
      AtomicReference<Boolean> responseConsumed = new AtomicReference<>(false);
      Thread responseThread =
          new Thread(
              () -> {
                try {
                  responseConsumed.set(engine.runPendingResponseHandlerForTest(exactResponse));
                } catch (Throwable failure) {
                  responseFailure.set(failure);
                }
              },
              "readboard-gma-stale-runtime-dispatch-test");

      boolean barrierObserved = false;
      synchronized (arbitrationLock) {
        responseThread.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
          if (barrierField.get(engine) != null) {
            barrierObserved = true;
            replaceReaderStreamBinding(engine);
            break;
          }
          Thread.onSpinWait();
        }
      }
      responseThread.join(1000L);
      assertTrue(barrierObserved, "the runtime barrier must be created while dispatch is blocked");
      assertFalse(responseThread.isAlive(), "the exact response worker must converge");
      assertTrue(Boolean.TRUE.equals(responseConsumed.get()));
      assertNull(responseFailure.get());

      ReadBoardGmaSession.Terminal terminal = awaitGmaSessionTerminal(sessionRef);
      assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
      assertEquals(
          ReadBoardGmaSession.FailureCategory.ADMISSION_STALE,
          terminal.firstFailure().category());
      assertTrue(
          transport.commands().stream().noneMatch(command -> command.startsWith("kata-set-param ")),
          "runtime dispatch must not send a restore command on the replacement incarnation");
      assertFalse(
          engine.hasUnrestoredReadBoardGmaState(),
          "stale runtime dispatch must not quarantine the replacement incarnation");
      assertNull(engine.currentReadBoardGmaReservation());
    }
  }

  @Test
  void readBoardGmaSessionTerminatedRuntimeBindingFailsClosed() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session =
          beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, null);
      engine.retireReadBoardGmaSession();
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(engine, command -> null);

      invokeParseLine(engine, "play pass");
      assertTrue(
          waitForFixtureCommandPrefix(transport, "loadsgf ", 1, TimeUnit.SECONDS),
          "the exact restore command must be written before dispatch is interposed");
      String exactResponse = successResponseForPrefix(transport.rawCommands(), "loadsgf ");

      Field barrierField = Leelaz.class.getDeclaredField("readBoardGmaRestoreBarrier");
      barrierField.setAccessible(true);
      java.lang.reflect.Method lockMethod =
          Leelaz.class.getDeclaredMethod("engineArbitrationLock");
      lockMethod.setAccessible(true);
      Object arbitrationLock = lockMethod.invoke(engine);
      AtomicReference<Throwable> responseFailure = new AtomicReference<>();
      Thread responseThread =
          new Thread(
              () -> {
                try {
                  engine.runPendingResponseHandlerForTest(exactResponse);
                } catch (Throwable failure) {
                  responseFailure.set(failure);
                }
              },
              "readboard-gma-terminated-runtime-test");

      boolean barrierObserved = false;
      synchronized (arbitrationLock) {
        responseThread.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
          if (barrierField.get(engine) != null) {
            barrierObserved = true;
            Object binding =
                getObjectField(engine, "readerStreamBinding");
            setBooleanField(binding, "terminated", true);
            break;
          }
          Thread.onSpinWait();
        }
      }
      responseThread.join(1000L);
      assertTrue(barrierObserved, "the runtime barrier must be created while dispatch is blocked");
      assertFalse(responseThread.isAlive(), "the exact response worker must converge");
      assertNull(responseFailure.get());

      ReadBoardGmaSession.Terminal terminal = awaitGmaSessionTerminal(sessionRef);
      assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
      assertEquals(
          ReadBoardGmaSession.FailureCategory.PROCESS_TERMINATED,
          terminal.firstFailure().category());
      assertTrue(
          transport.commands().stream().noneMatch(command -> command.startsWith("kata-set-param ")),
          "a terminated runtime binding must not send another restore command");
      assertTrue(engine.hasUnrestoredReadBoardGmaState());
      assertNull(engine.currentReadBoardGmaReservation());
    }
  }

  @Test
  void readBoardGmaSessionRuntimeRestoreErrorFailsClosed() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session = beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, null);
      // A retired session runs the runtime participant after exact success.
      engine.retireReadBoardGmaSession();
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.startsWith("loadsgf ")
                      ? ExactSnapshotRestoreProtocolFixture.Response.success()
                      : null);

      invokeParseLine(engine, "play pass");

      assertTrue(
          waitForFixtureCommandPrefix(transport, "kata-set-param maxTime 2", 1, TimeUnit.SECONDS),
          "the runtime participant must dispatch its restore commands");
      invokeProcessCommandResponseLine(
          engine,
          errorResponseFor(transport.rawCommands(), "maxTime", "controlled restore failure"));

      ReadBoardGmaSession.Terminal terminal = awaitGmaSessionTerminal(sessionRef);
      assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
      assertEquals(
          ReadBoardGmaSession.FailureCategory.GTP_ERROR, terminal.firstFailure().category());
      assertNull(engine.currentReadBoardGmaReservation());
      assertTrue(
          engine.hasUnrestoredReadBoardGmaState(),
          "a runtime participant failure must quarantine the engine fail-closed");
      assertNull(boundReadBoardGmaSession(readBoard));

      // Late matching success cannot rewrite the locked failure or release anything again.
      invokeProcessCommandResponseLine(
          engine, successResponseFor(transport.rawCommands(), "maxVisits"));
      assertEquals(
          ReadBoardGmaSession.SessionOutcome.FAILED, awaitGmaSessionTerminal(sessionRef).outcome());
      assertNull(engine.currentReadBoardGmaReservation());
    }
  }

  @Test
  void readBoardGmaSessionStaleEngineIncarnationCannotStartExactRestore() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = readyReadBoardGmaEngine();
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session = beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, null);
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.startsWith("loadsgf ")
                      ? ExactSnapshotRestoreProtocolFixture.Response.success()
                      : null);

      // The engine process is replaced while the request is in flight: the stale-incarnation
      // guard converts the authorized play into an isolation REQUEST_ERROR terminal, and the new
      // incarnation must reject the old session before any exact or runtime restore command.
      replaceReaderStreamBinding(engine);
      BoardHistoryNode authoritativeNode = Lizzie.board.getHistory().getCurrentHistoryNode();

      invokeParseLine(engine, "play D4");
      assertSame(
          authoritativeNode,
          Lizzie.board.getHistory().getCurrentHistoryNode(),
          "a stale terminal must not place a local move or request an external click");

      ReadBoardGmaSession.Terminal terminal = awaitGmaSessionTerminal(sessionRef);
      assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
      assertEquals(
          ReadBoardGmaSession.FailureCategory.ADMISSION_STALE, terminal.firstFailure().category());
      assertTrue(
          transport.commands().stream().noneMatch(command -> command.startsWith("loadsgf ")),
          "no exact restore command may run on a replacement engine incarnation");
      assertTrue(
          transport.commands().stream().noneMatch(command -> command.startsWith("kata-set-param")),
          "no runtime restore command may run on a replacement engine incarnation");
      assertFalse(
          engine.hasUnrestoredReadBoardGmaState(),
          "zero-side-effect stale admission must not quarantine the replacement incarnation");
      assertNull(engine.currentReadBoardGmaReservation());
      assertNull(boundReadBoardGmaSession(readBoard));
    }
  }

  @Test
  void readBoardGmaSessionRuntimeRestoreTimeoutFailsClosed() throws Exception {
    try (Harness harness = Harness.open()) {
      Leelaz engine = new ShortGmaRestoreTimeoutLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session = beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, null);
      // A retired session runs the runtime participant after exact success.
      engine.retireReadBoardGmaSession();
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.startsWith("loadsgf ")
                      ? ExactSnapshotRestoreProtocolFixture.Response.success()
                      : null);

      invokeParseLine(engine, "play pass");

      assertTrue(
          waitForFixtureCommandPrefix(
              transport, "kata-set-param maxVisits 800", 1, TimeUnit.SECONDS),
          "the runtime participant must dispatch its restore commands");

      ReadBoardGmaSession.Terminal terminal = awaitGmaSessionTerminal(sessionRef);
      assertEquals(ReadBoardGmaSession.SessionOutcome.FAILED, terminal.outcome());
      assertEquals(
          ReadBoardGmaSession.FailureCategory.TIMEOUT, terminal.firstFailure().category());
      assertNull(engine.currentReadBoardGmaReservation());
      assertTrue(
          engine.hasUnrestoredReadBoardGmaState(),
          "a missing ACK before the timeout must quarantine the engine fail-closed");
      assertEquals(0, pendingResponseHandlerCount(engine));
    }
  }

  @Test
  void readBoardGmaSessionSuccessPublishesContinuesAndReleasesExactlyOnce() throws Exception {
    try (Harness harness = Harness.open()) {
      CountingReleaseLeelaz engine = new CountingReleaseLeelaz();
      configureReadyReadBoardGmaEngine(engine);
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      Lizzie.config.readBoardPonder = true;
      Lizzie.frame.isPlayingAgainstLeelaz = true;
      ReadBoard readBoard = allocate(ReadBoard.class);
      ReadBoardGmaSession session = beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, null);
      AtomicReference<ReadBoardGmaSession> sessionRef = new AtomicReference<>(session);
      ExactSnapshotRestoreProtocolFixture.Transport transport =
          ExactSnapshotRestoreProtocolFixture.install(
              engine,
              command ->
                  command.startsWith("loadsgf ")
                      ? ExactSnapshotRestoreProtocolFixture.Response.error("unexpected loadsgf")
                      : null);

      // An authorized accepted play completes the active session directly: no exact restore is
      // staged, the runtime settings persist between hands, and the captured reservation is
      // released exactly once.
      engine.Pondering();
      invokeParseLine(engine, "play D4");

      ReadBoardGmaSession.Terminal terminal = awaitGmaSessionTerminal(sessionRef);
      assertEquals(ReadBoardGmaSession.SessionOutcome.SUCCEEDED, terminal.outcome());
      assertTrue(
          engine.isPondering(),
          "an authorized PLAYED must preserve the native GMA ponder stream");
      assertEquals(1, engine.releaseRequests.get());
      assertNull(
          engine.currentReadBoardGmaReservation(),
          "the session terminal must release the captured reservation exactly once");
      assertNull(
          boundReadBoardGmaSession(readBoard),
          "the success terminal must clear the published binding");
      assertTrue(
          transport.commands().stream().noneMatch(command -> command.startsWith("loadsgf ")),
          "an authorized PLAYED must not run any exact board restore");
      assertTrue(
          transport.commands().stream().noneMatch(command -> command.startsWith("kata-set-param")),
          "an active session must keep its runtime settings between hands without restoring them");

      // The server's next play-params flip the autoplay color and clear the synced-board wait;
      // the next hand then reuses the acknowledged overrides without re-snapshotting.
      setBooleanField(readBoard, "readBoardGmaAwaitingSyncedBoard", false);
      setObjectField(readBoard, "readBoardGmaAutoPlayColor", Stone.WHITE);
      assertTrue(invokeScheduleReadBoardGmaIfNeeded(readBoard, "test-next-hand"));
      assertTrue(
          waitForFixtureCommandPrefix(
              transport, "kata-set-param ponderingEnabled true", 1, TimeUnit.SECONDS),
          "the continued hand must reuse the acknowledged overrides without re-snapshotting");
      assertTrue(
          transport.commands().stream().noneMatch(command -> command.startsWith("kata-get-param")),
          "the continued hand must reuse the acknowledged snapshots instead of re-snapshotting");
      invokeProcessCommandResponseLine(
          engine, successResponseFor(transport.rawCommands(), "ponderingEnabled"));
      assertTrue(
          waitForFixtureCommandPrefix(transport, "kata-set-param maxTime ", 1, TimeUnit.SECONDS));
      String maxTimeAck = successResponseFor(transport.rawCommands(), "maxTime");
      invokeProcessCommandResponseLine(
          engine, successResponseFor(transport.rawCommands(), "maxTime"));
      assertTrue(
          waitForFixtureCommandPrefix(transport, "kata-set-param maxVisits ", 1, TimeUnit.SECONDS));
      invokeProcessCommandResponseLine(
          engine, successResponseFor(transport.rawCommands(), "maxVisits"));

      assertTrue(
          waitForFixtureCommandPrefix(transport, "kata-genmove_analyze ", 1, TimeUnit.SECONDS),
          "the continued hand must start the next autoplay hand");
      assertNotNull(
          engine.currentReadBoardGmaReservation(),
          "the continued hand must hold a fresh reservation after the session released its own");
      assertNotSame(session, boundReadBoardGmaSession(readBoard));
      long continuedHands =
          transport.commands().stream()
              .filter(command -> command.startsWith("kata-genmove_analyze "))
              .count();
      assertEquals(1, continuedHands, "the next hand must be scheduled exactly once");

      // A duplicate late preparation ACK does not re-publish, re-continue, or re-release: the
      // terminal stays the same absorbing instance and no new hand or release request appears.
      invokeProcessCommandResponseLine(engine, maxTimeAck);
      assertSame(terminal, awaitGmaSessionTerminal(sessionRef));
      assertEquals(1, engine.releaseRequests.get());
      assertEquals(
          1,
          transport.commands().stream()
              .filter(command -> command.startsWith("kata-genmove_analyze "))
              .count());
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

  private static void acknowledgePositionCommands(Leelaz engine, RecordingOutputStream output) {
    for (String raw : output.rawCommands()) {
      String payload = raw.replaceFirst("^\\d+\\s+", "");
      if (isRestorePositionCommand(payload)) {
        engine.processCommandResponseLineForTest("=" + raw.substring(0, raw.indexOf(' ')));
      }
    }
  }

  private static boolean isRestorePositionCommand(String command) {
    return command.equals("clear_board")
        || command.startsWith("boardsize ")
        || command.startsWith("rectangular_boardsize ")
        || command.startsWith("komi ")
        || command.startsWith("play ")
        || command.startsWith("loadsgf ")
        || command.startsWith("set_position");
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

  private static void acknowledgeWebSocketGmaSetup(
      Leelaz engine, RecordingOutputStream output, String maxTime, String maxVisits)
      throws Exception {
    invokeProcessCommandResponseLine(
        engine, parameterValueResponseFor(output.rawCommands(), "maxTime", maxTime));
    invokeProcessCommandResponseLine(engine, successResponseFor(output.rawCommands(), "maxTime"));
    invokeProcessCommandResponseLine(
        engine, parameterValueResponseFor(output.rawCommands(), "maxVisits", maxVisits));
    invokeProcessCommandResponseLine(
        engine, successResponseFor(output.rawCommands(), "maxVisits"));
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
    // The latest snapshot command matches: a later hand re-snapshots the same parameter, and the
    // stale command id is already settled by the response lifecycle.
    for (int index = commands.size() - 1; index >= 0; index--) {
      String command = commands.get(index);
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
      if (firstSpace < 0 && command.startsWith(commandPrefix)) {
        return "=";
      }
    }
    throw new IllegalArgumentException("Missing command prefix " + commandPrefix);
  }

  /**
   * Replaces the engine's reader stream binding with a fresh one, simulating an engine process
   * replacement that produces a new engine incarnation for the same Leelaz instance.
   */
  private static Object replaceReaderStreamBinding(Leelaz engine) throws Exception {
    Field bindingField = Leelaz.class.getDeclaredField("readerStreamBinding");
    bindingField.setAccessible(true);
    Object current = bindingField.get(engine);
    Class<?> bindingClass = current.getClass();
    Field stdout = bindingClass.getDeclaredField("stdout");
    Field stderr = bindingClass.getDeclaredField("stderr");
    Field output = bindingClass.getDeclaredField("output");
    Field process = bindingClass.getDeclaredField("process");
    Field remoteTransport = bindingClass.getDeclaredField("remoteTransport");
    Field javaSSH = bindingClass.getDeclaredField("javaSSH");
    Field incarnation = bindingClass.getDeclaredField("incarnation");
    Field startupPrimaryEngineGeneration =
        bindingClass.getDeclaredField("startupPrimaryEngineGeneration");
    for (Field field :
        List.of(
            stdout,
            stderr,
            output,
            process,
            remoteTransport,
            javaSSH,
            incarnation,
            startupPrimaryEngineGeneration)) {
      field.setAccessible(true);
    }
    java.lang.reflect.Constructor<?> constructor =
        bindingClass.getDeclaredConstructor(
            stdout.getType(),
            stderr.getType(),
            output.getType(),
            process.getType(),
            remoteTransport.getType(),
            javaSSH.getType(),
            long.class,
            long.class);
    constructor.setAccessible(true);
    Object replacement =
        constructor.newInstance(
            stdout.get(current),
            stderr.get(current),
            output.get(current),
            process.get(current),
            remoteTransport.get(current),
            javaSSH.get(current),
            incarnation.getLong(current) + 1L,
            startupPrimaryEngineGeneration.getLong(current));
    bindingField.set(engine, replacement);
    return current;
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

  private static int indexOfCommandStartingWith(List<String> commands, String prefix) {
    for (int index = 0; index < commands.size(); index++) {
      if (commands.get(index).startsWith(prefix)) {
        return index;
      }
    }
    return -1;
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

  private static boolean waitForFixtureCommandPrefix(
      ExactSnapshotRestoreProtocolFixture.Transport transport,
      String commandPrefix,
      long timeout,
      TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      for (String command : transport.commands()) {
        if (command.startsWith(commandPrefix)) {
          return true;
        }
      }
      Thread.sleep(10L);
    }
    return false;
  }

  private static boolean waitForCommandCount(
      ExactSnapshotRestoreProtocolFixture.Transport transport,
      String commandPrefix,
      int minimumCount,
      long timeout,
      TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      long count =
          transport.commands().stream()
              .filter(command -> command.startsWith(commandPrefix))
              .count();
      if (count >= minimumCount) {
        return true;
      }
      Thread.sleep(10L);
    }
    return false;
  }

  private static boolean waitForReleaseRequests(
      AtomicInteger releaseRequests, int minimumCount, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      if (releaseRequests.get() >= minimumCount) {
        return true;
      }
      Thread.sleep(10L);
    }
    return false;
  }
  private static boolean waitForSessionState(
      ReadBoardGmaSession session,
      Class<? extends ReadBoardGmaSession.State> stateType,
      long timeout,
      TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      if (stateType.isInstance(session.state())) {
        return true;
      }
      Thread.sleep(10L);
    }
    return false;
  }



  private static boolean waitForRawCommandPrefix(
      BlockingFirstFlushOutputStream output, String commandPrefix, long timeout, TimeUnit unit)
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
    engine.installCommandOutputForTest(stream);
  }

  private static void setInputStream(Leelaz engine, String input) throws Exception {
    Field field = Leelaz.class.getDeclaredField("inputStream");
    field.setAccessible(true);
    field.set(engine, new BufferedReader(new StringReader(input)));
  }

  private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      try {
        return current.getDeclaredField(fieldName);
      } catch (NoSuchFieldException ignored) {
        // Continue through the fixture superclass hierarchy.
      }
    }
    throw new NoSuchFieldException(fieldName);
  }

  private static void setObjectField(Object target, String fieldName, Object value)
      throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void setBooleanField(Object target, String fieldName, boolean value)
      throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    field.setBoolean(target, value);
  }

  private static void setLongField(Object target, String fieldName, long value) throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    field.setLong(target, value);
  }

  private static void setStringField(Object target, String fieldName, String value)
      throws Exception {
    Field field = findField(target.getClass(), fieldName);
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
    // The acknowledged preparation only reuses a captured original when the snapshot was already
    // requested by an earlier hand; a fresh hand always snapshots first.
    setBooleanField(param, "snapshotRequested", true);
  }

  private static boolean getBooleanField(Object target, String fieldName) throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    return field.getBoolean(target);
  }

  private static Object getObjectField(Object target, String fieldName) throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    return field.get(target);
  }

  /**
   * The session currently published through the atomic {@code readBoardGmaSessionBinding}. The
   * removed {@code readBoardGmaSession} field is replaced by the binding's private session slot;
   * the binding is cleared by the session terminal publication, so this returns {@code null} after
   * a terminal (or before admission).
   */
  private static ReadBoardGmaSession boundReadBoardGmaSession(ReadBoard readBoard)
      throws Exception {
    Object binding = getObjectField(readBoard, "readBoardGmaSessionBinding");
    if (binding == null) {
      return null;
    }
    return (ReadBoardGmaSession) getObjectField(binding, "session");
  }

  /**
   * Routes the current authoritative board state into the active GMA session while its request is
   * in flight: the latest-wins restore intent is re-captured from the given history node (the
   * production entry used by sync/rebuild recovery), so a later isolation terminal restores
   * through the advanced position and replays the captured MOVE tail.
   */
  private static void invokeUpdateReadBoardGmaRestoreIntent(
      ReadBoard readBoard, BoardHistoryNode restoreNode) throws Exception {
    java.lang.reflect.Method method =
        ReadBoard.class.getDeclaredMethod(
            "updateReadBoardGmaRestoreIntent", BoardHistoryNode.class);
    method.setAccessible(true);
    method.invoke(readBoard, restoreNode);
  }

  private static void setIntField(Object target, String fieldName, int value) throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    field.setInt(target, value);
  }

  private static int getIntField(Object target, String fieldName) throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    return field.getInt(target);
  }

  private static long getLongField(Object target, String fieldName) throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    return field.getLong(target);
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
  private static void invokeProcessCommandResponseLine(
      Leelaz engine, String line, Object responseBinding) throws Exception {
    java.lang.reflect.Method method =
        Leelaz.class.getDeclaredMethod(
            "processCommandResponseLine", String.class, responseBinding.getClass());
    method.setAccessible(true);
    method.invoke(engine, line, responseBinding);
  }

  private static Object pendingResponseBindingFor(Leelaz engine, String responseLine)
      throws Exception {
    String trimmed = responseLine.trim();
    int responseCommandId = 1;
    while (responseCommandId < trimmed.length()
        && Character.isDigit(trimmed.charAt(responseCommandId))) {
      responseCommandId++;
    }
    responseCommandId = Integer.parseInt(trimmed.substring(1, responseCommandId));
    Field handlersField = Leelaz.class.getDeclaredField("pendingResponseHandlers");
    handlersField.setAccessible(true);
    Iterable<?> handlers = (Iterable<?>) handlersField.get(engine);
    Field idField = Class.forName("featurecat.lizzie.analysis.Leelaz$PendingResponseHandler")
        .getDeclaredField("responseCommandId");
    Field bindingField = Class.forName("featurecat.lizzie.analysis.Leelaz$PendingResponseHandler")
        .getDeclaredField("responseBinding");
    idField.setAccessible(true);
    bindingField.setAccessible(true);
    for (Object handler : handlers) {
      if (idField.getInt(handler) == responseCommandId) {
        return bindingField.get(handler);
      }
    }
    throw new IllegalArgumentException("Missing pending response handler " + responseCommandId);
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

  private static void invokeRequestReadBoardGmaEngineRestore(
      ReadBoard readBoard, String reason, BoardHistoryNode restoreNode) throws Exception {
    java.lang.reflect.Method method =
        ReadBoard.class.getDeclaredMethod(
            "requestReadBoardGmaEngineRestore", String.class, BoardHistoryNode.class);
    method.setAccessible(true);
    method.invoke(readBoard, reason, restoreNode);
  }

  private static boolean invokeScheduleReadBoardGmaIfNeeded(ReadBoard readBoard, String reason)
      throws Exception {
    java.lang.reflect.Method method =
        ReadBoard.class.getDeclaredMethod("scheduleReadBoardGmaIfNeeded", String.class);
    method.setAccessible(true);
    return (Boolean) method.invoke(readBoard, reason);
  }

  /**
   * Arms the ReadBoard helper for one GMA hand and schedules it through the production entry,
   * then completes the acknowledged preparation (snapshots, overrides, genmove response) so the
   * session is admitted with the frozen authoritative restore intent. The caller then drives the
   * terminal line and the exact/runtime restore protocol.
   */
  private static ReadBoardGmaSession beginReadBoardGmaSessionHand(
      ReadBoard readBoard,
      Leelaz engine,
      RecordingOutputStream output,
      Stone autoPlayColor,
      Board board)
      throws Exception {
    return armReadBoardGmaSessionHand(readBoard, engine, output, autoPlayColor, board);
  }

  /**
   * Arms the ReadBoard helper for one GMA hand and schedules it through the production entry,
   * then feeds the matching snapshot and success responses in order so the fixture observes each
   * next preparation command and finally the admitted session and genmove. Admission happens only
   * after every required get/set ACK, so the helper must not expect the session (or the genmove)
   * before the acknowledged preparation completes.
   */
  private static ReadBoardGmaSession armReadBoardGmaSessionHand(
      ReadBoard readBoard,
      Leelaz engine,
      RecordingOutputStream output,
      Stone autoPlayColor,
      Board board)
      throws Exception {
    Lizzie.leelaz = engine;
    Lizzie.frame.bothSync = true;
    LizzieFrame.boardRenderer = new BoardRenderer(false);
    Lizzie.board = board == null ? new SilentPlacementBoard() : board;
    setBooleanField(readBoard, "readBoardGmaAutoPlayActive", true);
    setObjectField(readBoard, "readBoardGmaAutoPlayColor", autoPlayColor);
    setBooleanField(readBoard, "readBoardTurnTrusted", true);
    setIntField(readBoard, "readBoardGmaTimeSeconds", 5);
    setIntField(readBoard, "readBoardGmaMaxVisits", 1000);
    setBooleanField(readBoard, "readBoardWebSocketPonderingNoticeAcknowledged", true);
    Object identity = new Object();
    setObjectField(readBoard, "trackingEligibilityIdentity", identity);
    setLongField(readBoard, "readBoardGmaSessionGeneration", 1L);
    Lizzie.frame.readBoard = readBoard;

    boolean scheduled = invokeScheduleReadBoardGmaIfNeeded(readBoard, "test");
    assertTrue(
        scheduled && !output.commands().isEmpty(),
        "the GMA hand must be scheduled and its preparation commands sent; scheduled="
            + scheduled
            + " commands="
            + output.commands());
    assertEquals(
        "kata-get-param ponderingEnabled",
        output.commands().get(0),
        "the acknowledged preparation must start with the pondering snapshot");
    assertNull(
        boundReadBoardGmaSession(readBoard),
        "the session must not be admitted before every matching get/set ACK");
    assertFalse(
        output.commands().stream().anyMatch(command -> command.startsWith("kata-genmove_analyze")),
        "the genmove must not be sent before every required get/set ACK");

    acknowledgeInitialGmaCommands(engine, output);

    ReadBoardGmaSession session = boundReadBoardGmaSession(readBoard);
    assertNotNull(
        session,
        "the GMA hand must admit a session after the acknowledged preparation; pending="
            + getBooleanField(readBoard, "readBoardGmaPending")
            + " failedGen="
            + getLongField(readBoard, "readBoardGmaFailedGeneration")
            + " commands="
            + output.commands());
    assertTrue(
        output.commands().stream().anyMatch(command -> command.startsWith("kata-genmove_analyze ")),
        "the admitted session must send the genmove only after the acknowledged preparation;"
            + " commands="
            + output.commands());
    return session;
  }

  /**
   * Acknowledges every restore command of the runtime participant of a standard hand: pondering,
   * maxTime and maxVisits originals are all overridden and captured, and their restores are
   * dispatched in that order. Waits until all three commands reached the transport so the ACKs
   * cannot race the asynchronous dispatch.
   */
  private static void acknowledgeReadBoardGmaRuntimeRestore(
      Leelaz engine, ExactSnapshotRestoreProtocolFixture.Transport transport) throws Exception {
    assertTrue(
        waitForFixtureCommandPrefix(
            transport, "kata-set-param ponderingEnabled true", 1, TimeUnit.SECONDS));
    assertTrue(
        waitForFixtureCommandPrefix(transport, "kata-set-param maxTime 2", 1, TimeUnit.SECONDS));
    assertTrue(
        waitForFixtureCommandPrefix(
            transport, "kata-set-param maxVisits 800", 1, TimeUnit.SECONDS));
    invokeProcessCommandResponseLine(
        engine, successResponseFor(transport.rawCommands(), "ponderingEnabled"));
    invokeProcessCommandResponseLine(
        engine, successResponseFor(transport.rawCommands(), "maxTime"));
    invokeProcessCommandResponseLine(
        engine, successResponseFor(transport.rawCommands(), "maxVisits"));
  }

  private static ReadBoardGmaSession.Terminal awaitGmaSessionTerminal(
      AtomicReference<ReadBoardGmaSession> sessionRef) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      ReadBoardGmaSession session = sessionRef.get();
      if (session != null && session.state() instanceof ReadBoardGmaSession.Terminal terminal) {
        return terminal;
      }
      Thread.sleep(10L);
    }
    throw new AssertionError("GMA session did not reach its terminal");
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
  private record EngineTerminationGmaFixture(
      CountingReleaseLeelaz engine,
      RecordingOutputStream output,
      ReadBoard readBoard,
      ReadBoardGmaSession session) {}

  private static EngineTerminationGmaFixture openEngineTerminationGmaFixture()
      throws Exception {
    CountingReleaseLeelaz engine = new CountingReleaseLeelaz();
    configureReadyReadBoardGmaEngine(engine);
    RecordingOutputStream output = new RecordingOutputStream();
    setOutputStream(engine, output);
    ReadBoard readBoard = allocate(ReadBoard.class);
    ReadBoardGmaSession session =
        beginReadBoardGmaSessionHand(readBoard, engine, output, Stone.BLACK, null);
    return new EngineTerminationGmaFixture(engine, output, readBoard, session);
  }


  private static final class Harness implements AutoCloseable {
    private final Config previousConfig;
    private final LizzieFrame previousFrame;
    private final GtpConsolePane previousGtpConsole;
    private final Leelaz previousLeelaz;
    private final Leelaz previousLeelaz2;
    private final Board previousBoard;
    private final Menu previousMenu;

    private Harness(
        Config previousConfig,
        LizzieFrame previousFrame,
        GtpConsolePane previousGtpConsole,
        Leelaz previousLeelaz,
        Leelaz previousLeelaz2,
        Board previousBoard,
        Menu previousMenu) {
      this.previousConfig = previousConfig;
      this.previousFrame = previousFrame;
      this.previousGtpConsole = previousGtpConsole;
      this.previousLeelaz = previousLeelaz;
      this.previousLeelaz2 = previousLeelaz2;
      this.previousBoard = previousBoard;
      this.previousMenu = previousMenu;
    }

    private static Harness open() throws Exception {
      drainEventQueue();
      Harness harness =
          new Harness(
              Lizzie.config,
              Lizzie.frame,
              Lizzie.gtpConsole,
              Lizzie.leelaz,
              Lizzie.leelaz2,
              Lizzie.board,
              LizzieFrame.menu);
      Lizzie.config = minimalConfig();
      Lizzie.frame = allocate(SilentFrame.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      Lizzie.leelaz = new Leelaz("");
      Lizzie.leelaz2 = null;
      Lizzie.board = new Board();
      Lizzie.leelaz = null;
      LizzieFrame.menu = allocate(SilentMenu.class);
      return harness;
    }

    @Override
    public void close() throws Exception {
      drainEventQueue();
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousGtpConsole;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.leelaz2 = previousLeelaz2;
      Lizzie.board = previousBoard;
      LizzieFrame.menu = previousMenu;
    }

    private static void drainEventQueue() throws InvocationTargetException, InterruptedException {
      if (!SwingUtilities.isEventDispatchThread()) {
        SwingUtilities.invokeAndWait(() -> {});
      }
    }
  }

  /**
   * Board whose {@code clearAfterMove} is a no-op, so local placements (GMA final plays) do not
   * touch UI statics that are absent on the allocated test frame.
   */
  private static final class SilentPlacementBoard extends Board {
    @Override
    public void clearAfterMove() {
      // No UI side effects in these protocol tests.
    }
  }

  private static final class RecordingOutputStream extends OutputStream {
    private final StringBuilder currentCommand = new StringBuilder();
    private final List<String> commands = new ArrayList<>();
    private Leelaz acknowledgePositionEngine;
    private boolean acknowledgeRestoreFence;

    private void autoAcknowledgePositionRestore(Leelaz engine, boolean includeFinalFence) {
      acknowledgePositionEngine = engine;
      acknowledgeRestoreFence = includeFinalFence;
    }

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
        String payload = command.replaceFirst("^\\d+\\s+", "");
        Leelaz engine = acknowledgePositionEngine;
        if (engine != null
            && (isRestorePositionCommand(payload)
                || (acknowledgeRestoreFence && payload.equals("name")))) {
          if (payload.equals("name")) acknowledgePositionEngine = null;
          engine.processCommandResponseLineForTest(
              "=" + command.substring(0, command.indexOf(' ')));
        }
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

  /**
   * Keeps one coherent physical output binding while the first command is in WRITE_CLAIMED. The
   * first flush discards its still-local bytes before failing, so successors can safely use the
   * same stream after the blocked writer settles; replacing a live writer would violate the test
   * seam's quiescence contract.
   */
  private static final class BlockingFailingFirstFlushOutputStream
      extends BufferedOutputStream {
    private final RecordingOutputStream recordingOutput;
    private final CountDownLatch firstFlushStarted = new CountDownLatch(1);
    private final CountDownLatch releaseFirstFlush = new CountDownLatch(1);
    private boolean failFirstFlush = true;

    private BlockingFailingFirstFlushOutputStream() {
      this(new RecordingOutputStream());
    }

    private BlockingFailingFirstFlushOutputStream(RecordingOutputStream recordingOutput) {
      super(recordingOutput);
      this.recordingOutput = recordingOutput;
    }

    @Override
    public synchronized void flush() throws IOException {
      if (!failFirstFlush) {
        super.flush();
        return;
      }
      failFirstFlush = false;
      firstFlushStarted.countDown();
      try {
        releaseFirstFlush.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        count = 0;
        throw new IOException("controlled blocked flush interrupted", ex);
      }
      count = 0;
      throw new IOException("controlled blocked flush failure");
    }

    private List<String> commands() {
      return recordingOutput.commands();
    }
  }

  private static final class FailingBoardRestoreLeelaz extends Leelaz {
    private FailingBoardRestoreLeelaz() throws IOException {
      super("");
      ExactSnapshotRestoreProtocolFixture.install(
          this,
          command ->
              command.startsWith("loadsgf ")
                  ? ExactSnapshotRestoreProtocolFixture.Response.error(
                      "controlled board restore failure")
                  : ExactSnapshotRestoreProtocolFixture.Response.success());
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
      ExactSnapshotRestoreProtocolFixture.install(
          this,
          command -> {
            if (command.startsWith("loadsgf ")) {
              loadSgf(Path.of(command.substring("loadsgf ".length())));
            }
            return ExactSnapshotRestoreProtocolFixture.Response.success();
          });
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

    @Override
    public void reSetLoc() {}

    @Override
    public boolean resetMovelistFrameandAnalysisFrame() {
      return false;
    }
  }

  private static final class SilentMenu extends Menu {
    private SilentMenu() {}

    @Override
    public void toggleEngineMenuStatus(boolean isPondering, boolean isThinking) {}

    @Override
    public void showPda(boolean show) {}

    @Override
    public void updateMenuStatusForEngine() {}
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

  private static final class CountingReleaseLeelaz extends Leelaz {
    private final AtomicInteger releaseRequests = new AtomicInteger();

    private CountingReleaseLeelaz() throws IOException {
      super("");
    }

    @Override
    void requestReadBoardGmaReservationRelease(
        ReadBoardGmaSession.ReservationReleaseCapability capability) {
      releaseRequests.incrementAndGet();
      super.requestReadBoardGmaReservationRelease(capability);
    }
  }

  /**
   * Engine whose failure/quarantine handling is latch-blocked so the test can observe whether
   * the legacy deferred-restore worker issues a second loadsgf while the session failure is
   * still pending, before the quarantine clears the reservation. Owns its own release counter
   * because {@link CountingReleaseLeelaz} is final.
   */
  private static class BlockedFailureQuarantineLeelaz extends Leelaz {
    final AtomicInteger releaseRequests = new AtomicInteger();
    final CountDownLatch failureArrived = new CountDownLatch(1);
    final CountDownLatch releaseFailure = new CountDownLatch(1);

    private BlockedFailureQuarantineLeelaz() throws IOException {
      super("");
    }

    @Override
    void quarantineSessionOwnedReadBoardGmaFailure(String detail) {
      failureArrived.countDown();
      awaitLatch(releaseFailure);
      super.quarantineSessionOwnedReadBoardGmaFailure(detail);
    }

    @Override
    void requestReadBoardGmaReservationRelease(
        ReadBoardGmaSession.ReservationReleaseCapability capability) {
      releaseRequests.incrementAndGet();
      super.requestReadBoardGmaReservationRelease(capability);
    }
  }

  /**
   * Engine that additionally latch-blocks the first post-admission READ_BOARD_GMA admission
   * capture, exposing the TOCTOU window between a routed restore request's capture and the
   * session terminal failure. The block is armed only after session admission so the
   * admission-time capture passes through; once the release latch is open every later capture
   * passes through too.
   */
  private static final class BlockedCaptureRestoreLeelaz extends BlockedFailureQuarantineLeelaz {
    private final CountDownLatch captureStarted = new CountDownLatch(1);
    private final CountDownLatch releaseCapture = new CountDownLatch(1);
    private volatile boolean blockRestoreCapture = false;

    private BlockedCaptureRestoreLeelaz() throws IOException {
      super();
    }

    @Override
    Leelaz.ExactSnapshotRestoreAdmission captureExactSnapshotRestoreAdmission(
        Leelaz.ExactSnapshotRestoreOwner owner, Object ownerIdentity, Leelaz mirror) {
      if (owner == Leelaz.ExactSnapshotRestoreOwner.READ_BOARD_GMA && blockRestoreCapture) {
        captureStarted.countDown();
        awaitLatch(releaseCapture);
      }
      return super.captureExactSnapshotRestoreAdmission(owner, ownerIdentity, mirror);
    }
  }

  private static final class LateAnchorRestartLeelaz extends Leelaz {
    private LateAnchorRestartLeelaz() throws IOException {
      super("controlled-engine");
    }

    @Override
    public void startEngine(int index) {
      Lizzie.board.getHistory().getStart().getData().stones[Board.getIndex(3, 3)] = Stone.BLACK;
      started = true;
      isLoaded = true;
      isCheckingName = false;
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

  private static final class SwappingAutomaticRestartLeelaz extends Leelaz {
    private final Leelaz replacement;

    private SwappingAutomaticRestartLeelaz(Leelaz replacement) throws IOException {
      super("controlled-engine");
      this.replacement = replacement;
    }

    @Override
    public void startEngine(int index) {
      started = true;
      isLoaded = true;
      isCheckingName = false;
      Lizzie.leelaz = replacement;
    }
  }

  private static final class ReadyAutomaticRestartPonderLeelaz extends Leelaz {
    private ReadyAutomaticRestartPonderLeelaz() throws IOException {
      super("controlled-engine");
    }

    @Override
    public void startEngine(int index) {
      started = true;
      isLoaded = true;
      isCheckingName = false;
    }

    @Override
    void resumeClosedEngineAfterBoardSynchronization(boolean resumePonder) {
      if (!resumePonder) {
        return;
      }
      sendCommand("kata-time_settings none");
      sendCommand("kata-set-param maxTime 2");
      sendCommand("kata-analyze 10");
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
