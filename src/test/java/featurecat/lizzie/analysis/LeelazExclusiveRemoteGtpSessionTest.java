package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.KataGoAnalysisWebSocketTransportTest;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Movelist;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LeelazExclusiveRemoteGtpSessionTest {
  private static final List<Leelaz> createdEngines = new ArrayList<>();

  @Test
  void foregroundLeaseWaitsForCapturedPositionRestoreAndItsFinalFence() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    ByteArrayOutputStream output = installOutput(engine);
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.installForReader(engine)) {
      Leelaz.PositionRestore restore = engine.capturePositionRestore(null);
      try {
        assertForegroundLeaseWaitsForPosition(engine, output);
        restore.execute(() -> engine.sendCommandNoLeelaz2("komi 7.5"));
        assertTrue(commandQueue(engine).isEmpty(), "the physical write is already complete");
        assertForegroundLeaseWaitsForPosition(engine, output);
        String komi = output.toString(StandardCharsets.UTF_8).trim();
        processCommandResponse(engine, "=" + komi.substring(0, komi.indexOf(' ')));
        assertForegroundLeaseWaitsForPosition(engine, output);

        AtomicBoolean confirmed = new AtomicBoolean();
        restore.confirm(() -> confirmed.set(true), detail -> fail(detail));
        assertForegroundLeaseWaitsForPosition(engine, output);
        String fence =
            output.toString(StandardCharsets.UTF_8).lines().reduce((a, b) -> b).orElseThrow();
        assertTrue(fence.endsWith(" name"), fence);
        processCommandResponse(engine, "=" + fence.substring(0, fence.indexOf(' ')));
        assertTrue(confirmed.get());
        assertEquals(
            Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
            engine
                .acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {})
                .availability());
      } finally {
        restore.cancel();
      }
    }
  }

  @Test
  void foregroundLeaseWaitsForOutstandingPositionResponseWithoutRestoreOwner() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    ByteArrayOutputStream output = installOutput(engine);
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.installForReader(engine)) {
      engine.sendCommandNoLeelaz2("komi 7.5");
      assertTrue(commandQueue(engine).isEmpty());
      assertForegroundLeaseWaitsForPosition(engine, output);
      String komi = output.toString(StandardCharsets.UTF_8).trim();
      processCommandResponse(engine, "=" + komi.substring(0, komi.indexOf(' ')));
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine
              .acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {})
              .availability());
    }
  }

  @Test
  void foregroundLeaseCanInterruptOrdinaryAnalysisWithOutstandingResponse() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    ByteArrayOutputStream output = installOutput(engine);
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.installForReader(engine)) {
      engine.sendCommandNoLeelaz2("kata-analyze B 10");
      assertTrue(commandQueue(engine).isEmpty());
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine
              .acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {})
              .availability());
      assertTrue(output.toString(StandardCharsets.UTF_8).contains("800000000 stop"));
    }
  }

  private static void assertForegroundLeaseWaitsForPosition(
      Leelaz engine, ByteArrayOutputStream output) {
    assertTrue(engine.hasPendingPositionSynchronization());
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY,
        engine.previewForegroundAnalysisLeaseAvailability());
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY,
        engine.acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {}).availability());
    assertFalse(engine.hasExclusiveGtpLease());
    assertFalse(output.toString(StandardCharsets.UTF_8).contains("800000000 stop"));
  }

  @Test
  void cancelledUndispatchedRestoreDoesNotBlockFreshConfirmedRestore() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    ByteArrayOutputStream output = installOutput(engine);
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.installForReader(engine)) {
      Leelaz.PositionRestore cancelled = engine.capturePositionRestore(null);
      assertForegroundLeaseWaitsForPosition(engine, output);
      cancelled.cancel();
      cancelled.cancel();
      assertFalse(engine.hasPendingPositionSynchronization());

      Leelaz.PositionRestore fresh = engine.capturePositionRestore(null);
      try {
        assertForegroundLeaseWaitsForPosition(engine, output);
        fresh.execute(() -> {});
        AtomicBoolean confirmed = new AtomicBoolean();
        fresh.confirm(() -> confirmed.set(true), detail -> fail(detail));
        String fence = output.toString(StandardCharsets.UTF_8).trim();
        processCommandResponse(engine, "=" + fence.substring(0, fence.indexOf(' ')));
        assertTrue(confirmed.get());
        assertFalse(engine.hasPendingPositionSynchronization());
        assertEquals(
            Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
            engine
                .acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {})
                .availability());
      } finally {
        fresh.cancel();
      }
    }
  }

  @Test
  void oldReaderRestoreCannotKeepReplacementReaderBusy() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    ByteArrayOutputStream output = installOutput(engine);
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.installForReader(engine)) {
      Leelaz.PositionRestore old = engine.capturePositionRestore(null);
      try {
        assertForegroundLeaseWaitsForPosition(engine, output);
        List<String> commands = List.copyOf(engine.commandLists);
        engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
        engine.advertiseCommandsForTest(commands);
        assertFalse(engine.hasPendingPositionSynchronization());
        old.cancel();
        assertFalse(engine.hasPendingPositionSynchronization());
        assertEquals(
            Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
            engine
                .acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {})
                .availability());
      } finally {
        old.cancel();
      }
    }
  }

  @AfterEach
  void closeCreatedExclusiveSessions() throws Exception {
    for (Leelaz engine : createdEngines) {
      closeExclusiveSessionForTest(engine);
    }
    createdEngines.clear();
  }

  @Test
  void preparationCapabilityRequiresAnActiveLeaseOnTheActualZhiziReader() throws Exception {
    for (boolean zhizi : List.of(false, true)) {
      Leelaz engine = reusableKatagoEngine(false, false);
      ByteArrayOutputStream output = installOutput(engine);
      AtomicInteger progressNotifications = new AtomicInteger();
      try (var transport =
              new featurecat.lizzie.analysis.remote.ZhiziGtpTransport(
                  null, "", "--kata-weight 10b512t") {
                @Override
                public void markAnalysisProgressAccepted(long visits) {
                  progressNotifications.incrementAndGet();
                }
              };
          ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
        Field readerField = Leelaz.class.getDeclaredField("readerStreamBinding");
        readerField.setAccessible(true);
        Object reader = readerField.get(engine);
        if (zhizi) {
          Field transportField = reader.getClass().getDeclaredField("remoteTransport");
          transportField.setAccessible(true);
          transportField.set(reader, transport);
        }
        var acquisition =
            engine.acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {}, false);
        var lease = acquisition.lease();
        assertNotNull(lease);
        assertFalse(lease.isActiveBuiltInZhizi());
        lease.markAnalysisProgressAccepted(5);
        assertEquals(0, progressNotifications.get());
        String stop = output.toString(StandardCharsets.UTF_8).trim();
        dispatch(engine, "=" + stop.substring(0, stop.indexOf(' ')));
        assertFalse(lease.isActiveBuiltInZhizi(), "initial stop needs its terminating blank line");
        dispatch(engine, "");
        assertEquals(zhizi, lease.isActiveBuiltInZhizi());
        lease.markAnalysisProgressAccepted(0);
        lease.markAnalysisProgressAccepted(5);
        assertEquals(zhizi ? 1 : 0, progressNotifications.get());
        lease.release(() -> {}, () -> {});
        assertFalse(lease.isActiveBuiltInZhizi());
        lease.markAnalysisProgressAccepted(6);
        assertEquals(zhizi ? 1 : 0, progressNotifications.get());
        engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
        assertFalse(
            lease.isActiveBuiltInZhizi(), "a replacement reader cannot inherit eligibility");
        lease.markAnalysisProgressAccepted(7);
        assertEquals(zhizi ? 1 : 0, progressNotifications.get());
      }
    }
  }

  @Test
  void finiteHandbackRequiresSearchTerminalEvenWhenFinalStopCompletesFirst() throws Exception {
    try (RestoreHarness harness = RestoreHarness.open(false, false, true);
        var transport =
            new featurecat.lizzie.analysis.remote.ZhiziGtpTransport(
                null, "", "--kata-weight 10b512t")) {
      bindZhiziReader(harness.engine, transport);
      harness.board.restoreHistory = new BoardHistoryList(BoardData.empty(19, 19));
      assertTrue(harness.owner.installSearchParameter(101, "maxVisits", "999", "16"));
      assertTrue(
          harness.engine.sendExclusiveGtpCommand(
              "102 kata-search_analyze_cancellable B 1 rootInfo true"));
      assertTrue(harness.owner.release(harness.completions::incrementAndGet, () -> {}));
      String stop =
          harness.output.toString(StandardCharsets.UTF_8).lines().reduce((a, b) -> b).orElseThrow();
      dispatch(harness.engine, "=" + stop.split(" ")[0]);
      dispatch(harness.engine, "");
      assertFalse(
          harness.output.toString(StandardCharsets.UTF_8).contains("kata-set-param maxVisits 999"));
      assertEquals(0, harness.board.resendCount);
      dispatch(harness.engine, "=102");
      dispatch(harness.engine, "play cancelled");
      assertEquals(0, harness.board.resendCount);
      dispatch(harness.engine, "");
      String restore =
          harness.output.toString(StandardCharsets.UTF_8).lines().reduce((a, b) -> b).orElseThrow();
      assertTrue(restore.endsWith("kata-set-param maxVisits 999"));
      dispatch(harness.engine, "=" + restore.split(" ")[0]);
      assertEquals(0, harness.board.resendCount);
      dispatch(harness.engine, "");
      waitUntil(() -> harness.board.resendCount == 1);
      completeForegroundRestore(harness.engine);
      waitUntil(() -> harness.completions.get() == 1);
    }
  }

  @Test
  void parameterRestoreErrorAllowsSnapshotNavigationAndCompletesFailureOnce() throws Exception {
    try (RestoreHarness harness = RestoreHarness.open(false, false, true);
        var transport =
            new featurecat.lizzie.analysis.remote.ZhiziGtpTransport(
                null, "", "--kata-weight 10b512t")) {
      bindZhiziReader(harness.engine, transport);
      assertTrue(harness.owner.installSearchParameter(101, "maxVisits", "999", "16"));
      AtomicInteger failed = new AtomicInteger();
      assertTrue(
          harness.owner.release(harness.completions::incrementAndGet, failed::incrementAndGet));
      String stop =
          harness.output.toString(StandardCharsets.UTF_8).lines().reduce((a, b) -> b).orElseThrow();
      dispatch(harness.engine, "=" + stop.split(" ")[0]);
      dispatch(harness.engine, "");
      String restore =
          harness.output.toString(StandardCharsets.UTF_8).lines().reduce((a, b) -> b).orElseThrow();
      assertTrue(restore.endsWith("kata-set-param maxVisits 999"));
      String rejection = "?" + restore.split(" ")[0] + " parameter rejected";
      AtomicReference<Throwable> readerFailure = new AtomicReference<>();
      Thread reader =
          new Thread(
              () -> {
                try {
                  dispatch(harness.engine, rejection);
                } catch (Throwable failure) {
                  readerFailure.set(failure);
                }
              },
              "parameter-restore-error-reader");
      Method lockMethod = Leelaz.class.getDeclaredMethod("engineArbitrationLock");
      lockMethod.setAccessible(true);
      Object arbitration = lockMethod.invoke(harness.engine);
      var threads = java.lang.management.ManagementFactory.getThreadMXBean();
      boolean heldArbitration;
      boolean navigationReturned = false;
      try {
        synchronized (harness.board) {
          reader.start();
          waitUntil(
              () -> {
                var info = threads.getThreadInfo(reader.getId());
                return info != null
                    && info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockOwnerId() == Thread.currentThread().getId();
              });
          var info = threads.getThreadInfo(new long[] {reader.getId()}, true, false)[0];
          heldArbitration =
              java.util.Arrays.stream(info.getLockedMonitors())
                  .anyMatch(
                      monitor ->
                          monitor.getIdentityHashCode() == System.identityHashCode(arbitration));
          // Gate navigation on the proven lock boundary: a regression fails without stranding
          // threads.
          if (!heldArbitration) {
            try {
              harness.engine.captureHistoryNavigationExactSnapshotRestoreAdmission(null);
            } catch (Leelaz.ExactSnapshotRestoreAdmissionException expectedUnavailable) {
            }
            navigationReturned = true;
          }
        }
      } finally {
        reader.join(3000L);
      }
      assertFalse(reader.isAlive());
      assertEquals(null, readerFailure.get());
      assertFalse(
          heldArbitration, "parameter failure must release arbitration before waiting for Board");
      assertTrue(navigationReturned);
      dispatch(harness.engine, rejection);
      dispatch(harness.engine, "");
      assertEquals(1, failed.get());
      assertEquals(0, harness.completions.get());
      assertFalse(harness.engine.isLoaded());
      assertEquals(0, harness.engine.ponderCount);
    }
  }

  @Test
  void missingParameterRestoreBoundaryTimesOutDespiteUnrelatedNameSuccess() throws Exception {
    try (RestoreHarness harness = RestoreHarness.open(false, false, true);
        var transport =
            new featurecat.lizzie.analysis.remote.ZhiziGtpTransport(
                null, "", "--kata-weight 10b512t")) {
      bindZhiziReader(harness.engine, transport);
      assertTrue(harness.owner.installSearchParameter(101, "ponderingEnabled", "true", "false"));
      harness.engine.releaseStopTimeoutMillis = 100L;
      AtomicInteger failed = new AtomicInteger();
      assertTrue(
          harness.owner.release(harness.completions::incrementAndGet, failed::incrementAndGet));
      String stop =
          harness.output.toString(StandardCharsets.UTF_8).lines().reduce((a, b) -> b).orElseThrow();
      dispatch(harness.engine, "=" + stop.split(" ")[0]);
      dispatch(harness.engine, "");
      String restore =
          harness.output.toString(StandardCharsets.UTF_8).lines().reduce((a, b) -> b).orElseThrow();
      dispatch(harness.engine, "=" + restore.split(" ")[0]);
      dispatch(harness.engine, "=199999999 KataGo");
      dispatch(harness.engine, "");
      waitUntil(() -> failed.get() == 1);
      assertEquals(0, harness.completions.get());
      assertEquals(0, harness.board.resendCount);
      assertFalse(harness.engine.isLoaded());
      assertEquals(0, harness.engine.ponderCount);
    }
  }

  private static void bindZhiziReader(
      Leelaz engine, featurecat.lizzie.analysis.remote.ZhiziGtpTransport transport)
      throws Exception {
    Field readerField = Leelaz.class.getDeclaredField("readerStreamBinding");
    readerField.setAccessible(true);
    Object reader = readerField.get(engine);
    Field transportField = reader.getClass().getDeclaredField("remoteTransport");
    transportField.setAccessible(true);
    transportField.set(reader, transport);
  }

  @Test
  void outstandingPreparationRepliesCannotReleaseLeaseOrResumePausedPonder() throws Exception {
    try (RestoreHarness harness = RestoreHarness.open(true, false, true)) {
      List<Integer> outstanding = List.of(101, 102, 103, 104, 105, 106, 107, 108);
      List<String> moves =
          List.of("B D4", "W Q16", "B D16", "W Q4", "B C6", "W R3", "B F3", "W R14");
      for (int i = 0; i < outstanding.size(); i++) {
        assertTrue(
            harness.engine.sendExclusiveGtpCommand(outstanding.get(i) + " play " + moves.get(i)));
      }
      Method pause = LizzieFrame.class.getDeclaredMethod("pauseFromAnalysisControl");
      pause.setAccessible(true);
      pause.invoke(Lizzie.frame);
      harness.board.currentMarker = 7;
      String rules = "{\"koRule\":\"POSITIONAL\",\"scoringRule\":\"AREA\",\"taxRule\":\"NONE\"}";
      assertTrue(harness.owner.setRestoreRules(rules));
      assertTrue(harness.owner.release(harness.completions::incrementAndGet, () -> {}));
      String finalStop =
          harness.output.toString(StandardCharsets.UTF_8).lines().reduce((a, b) -> b).get();
      assertFalse(harness.engine.sendExclusiveGtpCommand("109 play W Q16"));
      for (int id : outstanding) {
        dispatch(harness.engine, "=" + id);
        dispatch(harness.engine, "");
      }
      assertEquals(0, harness.board.resendCount);
      assertEquals(0, harness.completions.get());
      dispatch(harness.engine, "=" + finalStop.substring(0, finalStop.indexOf(' ')));
      dispatch(harness.engine, "");
      waitUntil(() -> harness.board.resendCount == 1);
      assertEquals(7, harness.board.restoredMarker);
      assertTrue(
          harness.output.toString(StandardCharsets.UTF_8).contains("kata-set-rules " + rules));
      completeForegroundRestore(harness.engine);
      waitUntil(() -> harness.completions.get() == 1);
      assertEquals(0, harness.engine.ponderCount);
    }
  }

  @Test
  void initialStopCompletesThroughReaderWhileAnotherStrictRequestIsPending() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installInput(engine, "=800000001\n\n=800000000\n\n");
    installOutput(engine);
    engine.isNormalEnd = true;
    AtomicInteger ready = new AtomicInteger();
    AtomicReference<Boolean> rulesCompletedAtReady = new AtomicReference<>();
    AtomicReference<Boolean> exclusiveCommandAccepted = new AtomicReference<>();
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.installForReader(engine)) {
      Leelaz.EngineRulesOperation rules = engine.queryEngineRulesOperation(30_000L);
      assertTrue(rules.accepted());
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginExclusiveGtpSession(
              line -> {},
              () -> {
                rulesCompletedAtReady.set(rules.isDone());
                exclusiveCommandAccepted.set(engine.sendExclusiveGtpCommand("kata-raw-nn 0"));
                ready.incrementAndGet();
              },
              () -> {}));

      invokeRead(engine);

      assertEquals(1, ready.get(), "the matching stop frame must activate its lease exactly once");
      assertEquals(
          Boolean.FALSE, rulesCompletedAtReady.get(), "stop must not settle the rules query");
      assertEquals(
          Boolean.TRUE, exclusiveCommandAccepted.get(), "the activated lease must be usable");
    }
  }

  @Test
  void exclusiveRemoteSessionWaitsForStopThenRoutesOnlyQuickCurveTraffic() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    ByteArrayOutputStream bytes = installOutput(engine);
    List<String> received = new ArrayList<>();
    AtomicInteger ready = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.beginExclusiveGtpSession(
            received::add, ready::incrementAndGet, closed::incrementAndGet));
    assertFalse(
        engine.hasForegroundAnalysisLeaseWorkInProgress(),
        "generic exclusive work must not be mistaken for interruptible quick analysis.");
    assertEquals("800000000 stop\n", bytes.toString(StandardCharsets.UTF_8));
    assertTrue(dispatch(engine, "info move D4 visits 10"));
    assertEquals(0, ready.get());

    assertFalse(dispatch(engine, "="));
    processCommandResponse(engine, "=");
    assertEquals(0, ready.get(), "the analyze terminator is not the numbered stop response.");
    dispatch(engine, "=800000000");

    assertEquals(0, ready.get(), "the stop response is incomplete until its blank boundary.");
    assertTrue(dispatch(engine, ""), "the trailing stop boundary must be consumed once.");
    assertEquals(1, ready.get());
    assertTrue(engine.sendExclusiveGtpCommand("kata-raw-nn 0"));
    assertEquals("800000000 stop\nkata-raw-nn 0\n", bytes.toString(StandardCharsets.UTF_8));

    assertTrue(dispatch(engine, "= symmetry 0"));
    assertTrue(dispatch(engine, "whiteWin 0.61"));
    assertEquals(List.of("= symmetry 0", "whiteWin 0.61"), received);

    engine.endExclusiveGtpSession();
    assertFalse(engine.sendExclusiveGtpCommand("kata-raw-nn 0"));
    assertEquals(0, closed.get());
  }

  @Test
  void exclusiveRemoteSessionStopErrorLeavesEngineUnavailable() throws Exception {
    Leelaz engine = reusableKatagoEngine(true, false);
    installOutput(engine);
    AtomicInteger closed = new AtomicInteger();

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.beginExclusiveGtpSession(line -> {}, () -> {}, closed::incrementAndGet));
    assertTrue(
        dispatch(engine, "?800000000 cannot stop"),
        "the matching stop error must be consumed before the normal response path sees it.");

    assertEquals(1, closed.get());
    assertFalse(engine.sendExclusiveGtpCommand("kata-raw-nn 0"));
    assertFalse(engine.isLoaded(), "an unconfirmed stop must not reopen the engine.");
  }

  @Test
  void foregroundLeaseInitialStopTimeoutClosesOnceAndIgnoresLateSuccess() throws Exception {
    RecordingRestoreLeelaz engine = recordingRestoreEngine();
    engine.initialStopTimeoutMillis = 25L;
    installOutput(engine);
    AtomicInteger ready = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();
    AtomicReference<Leelaz.ForegroundAnalysisLeaseFailure> failureReason = new AtomicReference<>();
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
      Leelaz.ForegroundAnalysisLeaseAcquisition acquisition =
          engine.acquireForegroundAnalysisLease(
              line -> {},
              lease -> ready.incrementAndGet(),
              lease -> {
                failureReason.set(lease.failureReason().orElse(null));
                closed.incrementAndGet();
              });
      assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, acquisition.availability());
      assertTrue(engine.hasForegroundAnalysisLeaseWorkInProgress());
      assertFalse(dispatch(engine, "=800000001"));
      processCommandResponse(engine, "=800000001");
      assertFalse(dispatch(engine, ""));
      waitUntil(() -> closed.get() == 1);

      assertEquals(0, ready.get());
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
      assertFalse(engine.hasForegroundAnalysisLeaseWorkInProgress());
      assertFalse(dispatch(engine, "=800000000"));
      processCommandResponse(engine, "=800000000");
      assertFalse(dispatch(engine, ""));
      assertEquals(0, ready.get());
      assertEquals(1, closed.get());
      assertEquals(Leelaz.ForegroundAnalysisLeaseFailure.INITIAL_STOP_TIMEOUT, failureReason.get());
      assertFalse(engine.isLoaded(), "an unconfirmed stop must not reopen the engine.");
    }
  }

  @Test
  void automaticLeaseInitialStopTimeoutIsNotAHandbackFailure() throws Exception {
    RecordingRestoreLeelaz engine = recordingRestoreEngine();
    engine.initialStopTimeoutMillis = 25L;
    installOutput(engine);
    AtomicInteger closed = new AtomicInteger();
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
      Leelaz.ForegroundAnalysisLeaseAcquisition acquisition =
          engine.acquireForegroundAnalysisLease(
              line -> {}, lease -> {}, lease -> closed.incrementAndGet(), false);
      assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, acquisition.availability());
      waitUntil(() -> closed.get() == 1);
      drainEdt();

      assertFalse(engine.isLoaded(), "the acquisition failure stays fail-closed.");
      assertEquals(
          java.util.Optional.empty(),
          engine.unrestoredForegroundLeaseFailure(),
          "a lease that never became active did not fail to hand the engine back");
    }
  }

  @Test
  void foregroundLeaseRestoreFailureNotificationPolicyDefaultsToInteractive() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
      Leelaz.ForegroundAnalysisLeaseAcquisition interactive =
          engine.acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {});

      assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, interactive.availability());
      assertTrue(Leelaz.shouldReportForegroundRestoreFailure(interactive.lease()));
    }
  }

  @Test
  void automaticForegroundLeaseCanSuppressRestoreFailurePopup() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
      Leelaz.ForegroundAnalysisLeaseAcquisition automatic =
          engine.acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {}, false);

      assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, automatic.availability());
      assertFalse(Leelaz.shouldReportForegroundRestoreFailure(automatic.lease()));
    }
  }

  @Test
  void foregroundLeaseInitialStopHeaderWithoutBoundaryTimesOut() throws Exception {
    RecordingRestoreLeelaz engine = recordingRestoreEngine();
    engine.initialStopTimeoutMillis = 25L;
    installOutput(engine);
    AtomicInteger ready = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.beginExclusiveGtpSession(
            line -> {}, ready::incrementAndGet, closed::incrementAndGet));
    dispatch(engine, "=800000000");
    waitUntil(() -> closed.get() == 1);

    assertEquals(0, ready.get());
    assertFalse(dispatch(engine, ""));
    assertEquals(1, closed.get());
    assertFalse(engine.hasExclusiveGtpWorkInProgress());
  }

  @Test
  void foregroundLeaseInitialStopErrorHasStableFailureReason() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);
    AtomicInteger ready = new AtomicInteger();
    AtomicReference<Leelaz.ForegroundAnalysisLeaseFailure> failureReason = new AtomicReference<>();
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
      Leelaz.ForegroundAnalysisLeaseAcquisition acquisition =
          engine.acquireForegroundAnalysisLease(
              line -> {},
              lease -> ready.incrementAndGet(),
              lease -> failureReason.set(lease.failureReason().orElse(null)));
      assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, acquisition.availability());
      assertTrue(dispatch(engine, "?800000000 cannot stop"));

      assertEquals(0, ready.get());
      assertEquals(
          Leelaz.ForegroundAnalysisLeaseFailure.INITIAL_STOP_ERROR_RESPONSE, failureReason.get());
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
      assertFalse(engine.isLoaded(), "an unconfirmed stop must not reopen the engine.");
    }
  }

  @Test
  void foregroundLeaseInitialStopSendFailureNeverBecomesReady() throws Exception {
    RecordingRestoreLeelaz engine = recordingRestoreEngine();
    installFailOnceOutput(engine);
    AtomicInteger ready = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
      Leelaz.ForegroundAnalysisLeaseAcquisition acquisition =
          engine.acquireForegroundAnalysisLease(
              line -> {}, lease -> ready.incrementAndGet(), lease -> closed.incrementAndGet());

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY, acquisition.availability());
      assertEquals(null, acquisition.lease());
      assertEquals(
          java.util.Optional.of(Leelaz.ForegroundAnalysisLeaseFailure.INITIAL_STOP_SEND_FAILED),
          acquisition.failureReason());
      assertEquals(0, ready.get());
      assertEquals(0, closed.get());
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
      assertFalse(engine.isLoaded(), "an unconfirmed stop must not reopen the engine.");
    }
  }

  @Test
  void foregroundLeaseInitialStopEofClosesOnceWithoutBecomingReady() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installInput(engine, "");
    installOutput(engine);
    AtomicInteger ready = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
      engine.isNormalEnd = true;

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginForegroundAnalysisLease(
              new Object(), line -> {}, ready::incrementAndGet, closed::incrementAndGet));
      invokeRead(engine);

      assertEquals(0, ready.get());
      assertEquals(1, closed.get());
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
      assertFalse(engine.isLoaded());
    }
  }

  @Test
  void webSocketEofClosesActiveForegroundLeaseThroughReader() throws Exception {
    try (KataGoAnalysisWebSocketTransportTest.TransportHarness transport =
        KataGoAnalysisWebSocketTransportTest.TransportHarness.open()) {
      Leelaz engine = reusableKatagoEngine(false, false);
      installInput(engine, transport.transport().stdout());
      installOutput(engine, Leelaz.createCommandOutputStream(transport.transport().stdin()));
      engine.isNormalEnd = true;
      CountDownLatch ready = new CountDownLatch(1);
      AtomicInteger closed = new AtomicInteger();
      AtomicReference<Throwable> readerFailure = new AtomicReference<>();
      Thread reader =
          daemonThread(
              "websocket-eof-lease-reader",
              () -> {
                try {
                  invokeRead(engine);
                } catch (Throwable failure) {
                  readerFailure.set(failure);
                }
              });

      try (ForegroundLeaseGlobalState ignored =
          ForegroundLeaseGlobalState.installForReader(engine)) {
        reader.start();
        try {
          Leelaz.ForegroundAnalysisLeaseAcquisition acquisition =
              engine.acquireForegroundAnalysisLease(
                  line -> {}, lease -> ready.countDown(), lease -> closed.incrementAndGet());
          assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, acquisition.availability());
          await(ready);
          assertTrue(engine.sendExclusiveGtpCommand("kata-analyze B 10"));

          transport.closeFromServer(1006, "lost");

          reader.join(1000L);
          assertFalse(reader.isAlive());
          assertNull(readerFailure.get());
          assertEquals(1, closed.get());
          assertFalse(acquisition.lease().isOwned());
          assertEquals(
              java.util.Optional.of(Leelaz.ForegroundAnalysisLeaseFailure.TRANSPORT_CLOSED),
              acquisition.lease().failureReason());
          assertFalse(engine.isLoaded());
        } finally {
          transport.close();
          reader.join(1000L);
        }
      }
    }
  }

  @Test
  void foregroundLeaseReleaseBeforeReadyKeepsInitialStopFailureClassification() throws Exception {
    RecordingRestoreLeelaz engine = recordingRestoreEngine();
    engine.initialStopTimeoutMillis = 50L;
    engine.releaseStopTimeoutMillis = 1L;
    installOutput(engine);
    AtomicInteger completions = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
      Leelaz.ForegroundAnalysisLeaseAcquisition acquisition =
          engine.acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {});
      assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, acquisition.availability());
      assertTrue(
          acquisition.lease().release(completions::incrementAndGet, failures::incrementAndGet));
      waitUntil(() -> failures.get() == 1);

      assertEquals(0, completions.get());
      assertEquals(
          java.util.Optional.of(Leelaz.ForegroundAnalysisLeaseFailure.INITIAL_STOP_TIMEOUT),
          acquisition.lease().failureReason());
      assertFalse(engine.isLoaded());
    }
  }

  @Test
  void exclusiveSessionIgnoresUnrelatedStopResponsesAndRejectsSecondLease() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);
    AtomicInteger ready = new AtomicInteger();

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.beginExclusiveGtpSession(line -> {}, ready::incrementAndGet, () -> {}));
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.EXISTING_LEASE,
        engine.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));

    assertFalse(dispatch(engine, "=800000001"));
    processCommandResponse(engine, "=800000001");
    assertFalse(dispatch(engine, "?800000001 unrelated failure"));
    assertEquals(0, ready.get());
    assertTrue(engine.hasExclusiveGtpLease());

    dispatch(engine, "=800000000");
    assertEquals(0, ready.get());
    assertTrue(dispatch(engine, ""));
    assertEquals(1, ready.get());
    engine.endExclusiveGtpSession();
  }

  @Test
  void exclusiveSessionUsesSameTransportNeutralEntryPointForJavaSsh() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, true);
    installOutput(engine);

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));
  }

  @Test
  void exclusiveSessionRejectsNonKatagoAndMissingCapabilities() throws Exception {
    Leelaz nonKatago = reusableKatagoEngine(false, false);
    nonKatago.isKatago = false;
    installOutput(nonKatago);
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.NOT_KATAGO,
        nonKatago.previewExclusiveGtpLeaseAvailability());

    Leelaz missingCapability = reusableKatagoEngine(false, false);
    missingCapability.commandLists.remove("kata-analyze");
    installOutput(missingCapability);
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.MISSING_CAPABILITY,
        missingCapability.previewExclusiveGtpLeaseAvailability());

    Leelaz missingSetPosition = reusableKatagoEngine(false, false);
    missingSetPosition.commandLists.remove("set_position");
    installOutput(missingSetPosition);
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.MISSING_CAPABILITY,
        missingSetPosition.previewExclusiveGtpLeaseAvailability());

    Leelaz missingRulesQuery = reusableKatagoEngine(false, false);
    missingRulesQuery.commandLists.remove("kata-get-rules");
    installOutput(missingRulesQuery);
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.MISSING_CAPABILITY,
        missingRulesQuery.previewExclusiveGtpLeaseAvailability());
  }

  @Test
  void exclusiveSessionWaitsForCapabilityDiscovery() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    setCapabilityDiscoveryComplete(engine, false);
    installOutput(engine);

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY,
        engine.previewExclusiveGtpLeaseAvailability());
  }

  @Test
  void lifecycleTransitionAndLeaseAcquisitionShareOneAtomicGate() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);

    assertTrue(engine.beginExclusiveGtpLifecycleTransition());
    assertTrue(
        engine.beginExclusiveGtpLifecycleTransition(),
        "nested mode transitions on the same UI thread must remain atomic and usable.");
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE,
        engine.previewExclusiveGtpLeaseAvailability());
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE,
        engine.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));
    engine.endExclusiveGtpLifecycleTransition();
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE,
        engine.previewExclusiveGtpLeaseAvailability());
    engine.endExclusiveGtpLifecycleTransition();

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));
    assertFalse(engine.beginExclusiveGtpLifecycleTransition());
    engine.endExclusiveGtpSession();
  }

  @Test
  void explicitLifecycleReservationCanBeReleasedBySynchronizationThread() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);

    Leelaz.ExclusiveGtpLifecycleReservation reservation =
        engine.beginExclusiveGtpLifecycleReservation();
    assertTrue(reservation != null);
    assertTrue(engine.hasExclusiveGtpWorkInProgress());

    Thread synchronizationThread = new Thread(reservation::close);
    synchronizationThread.start();
    synchronizationThread.join();

    assertFalse(engine.hasExclusiveGtpWorkInProgress());
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.previewExclusiveGtpLeaseAvailability());
  }

  @Test
  void explicitLifecycleReservationDoesNotInvokeOverriddenNoArgEntryPoint() throws Exception {
    Leelaz engine = new NoArgLifecycleReservationLeelaz();

    Leelaz.ExclusiveGtpLifecycleReservation reservation =
        engine.beginExclusiveGtpLifecycleReservation(new Object());

    assertNotNull(reservation);
    reservation.close();
  }

  @Test
  void foregroundLeaseAndEngineModeReservationExposeDistinctPermitTypes() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = null;

      Leelaz.ForegroundAnalysisLeaseAcquisition acquisition =
          engine.acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {});

      assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, acquisition.availability());
      assertTrue(acquisition.lease() != null);
      assertTrue(acquisition.lease().isOwned());
      assertEquals(null, engine.beginEngineModeReservation());

      engine.endExclusiveGtpSession();
      Leelaz.EngineModeReservation reservation = engine.beginEngineModeReservation();
      assertTrue(reservation != null);
      assertTrue(engine.hasExclusiveGtpWorkInProgress());
      reservation.close();
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
    } finally {
      engine.endExclusiveGtpSession();
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void engineModeReservationAllowsCommandsFromItsExecutionScope() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    Leelaz engine = reusableKatagoEngine(false, false);
    ByteArrayOutputStream output = installOutput(engine);
    try {
      Lizzie.leelaz = engine;
      Lizzie.board = new Board();
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);

      Leelaz.EngineModeReservation reservation = engine.beginEngineModeReservation();
      assertTrue(reservation != null);
      try {
        engine.komi(6.5);
      } finally {
        reservation.close();
      }

      assertEquals(
          "komi 6.5\n", output.toString(StandardCharsets.UTF_8).replaceFirst("^\\d+\\s+", ""));
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
    }
  }

  @Test
  void normalCommandAndForegroundLeaseAdmissionCannotDeadlock() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    Leelaz engine = reusableKatagoEngine(false, false);
    ByteArrayOutputStream output = installOutput(engine);
    Object queue = commandQueue(engine);
    CountDownLatch queueHeld = new CountDownLatch(1);
    CountDownLatch sendCommand = new CountDownLatch(1);
    CountDownLatch commandFinished = new CountDownLatch(1);
    CountDownLatch leaseFinished = new CountDownLatch(1);
    CountDownLatch modeFinished = new CountDownLatch(1);
    AtomicReference<Leelaz.ExclusiveGtpLeaseAvailability> availability = new AtomicReference<>();
    AtomicReference<Leelaz.EngineModeReservation> modeReservation = new AtomicReference<>();
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);

      Thread commandThread =
          daemonThread(
              "overlapping-normal-command",
              () -> {
                try {
                  synchronized (queue) {
                    queueHeld.countDown();
                    await(sendCommand);
                    engine.sendCommand("name");
                  }
                } finally {
                  commandFinished.countDown();
                }
              });
      commandThread.start();
      assertTrue(queueHeld.await(1, TimeUnit.SECONDS));

      Thread leaseThread =
          daemonThread(
              "overlapping-foreground-lease",
              () -> {
                availability.set(
                    engine.beginForegroundAnalysisLease(
                        new Object(), line -> {}, () -> {}, () -> {}));
                leaseFinished.countDown();
              });
      leaseThread.start();
      waitForState(leaseThread, Thread.State.BLOCKED);

      Thread modeThread =
          daemonThread(
              "overlapping-engine-mode",
              () -> {
                modeReservation.set(engine.beginEngineModeReservation());
                modeFinished.countDown();
              });
      modeThread.start();
      waitForState(modeThread, Thread.State.BLOCKED);
      sendCommand.countDown();

      assertTrue(
          commandFinished.await(1, TimeUnit.SECONDS),
          "normal command admission must not deadlock with foreground lease acquisition");
      assertTrue(
          leaseFinished.await(1, TimeUnit.SECONDS),
          "foreground lease acquisition must not deadlock with normal command admission");
      assertTrue(
          modeFinished.await(1, TimeUnit.SECONDS),
          "engine mode admission must complete after the overlapping lease decision");
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          availability.get(),
          "the lease may start after the accepted command has been sent");
      assertEquals(null, modeReservation.get(), "only one conflicting permit may be acquired");
      assertEquals(
          "name\n800000000 stop\n",
          output.toString(StandardCharsets.UTF_8),
          "an accepted command must be sent before foreground analysis takes the stream");
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
    }
  }

  @Test
  void normalCommandsRemainSuppressedUntilForegroundLeaseRestoreCompletes() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    Leelaz engine = reusableKatagoEngine(false, false);
    ByteArrayOutputStream output = installOutput(engine);
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      Leelaz.ForegroundAnalysisLeaseAcquisition acquisition =
          engine.acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {});
      Leelaz.ForegroundAnalysisLease lease = acquisition.lease();
      dispatch(engine, "=800000000");
      assertTrue(dispatch(engine, ""));

      engine.sendCommand("name");
      assertEquals("800000000 stop\n", output.toString(StandardCharsets.UTF_8));

      Lizzie.leelaz = null;
      assertTrue(lease.release(() -> {}, () -> {}));
      assertTrue(dispatch(engine, "=800000001"));
      assertTrue(dispatch(engine, ""));
      assertFalse(engine.hasExclusiveGtpWorkInProgress());

      Lizzie.leelaz = engine;
      engine.sendCommand("name");
      assertEquals(
          "800000000 stop\n800000001 stop\nname\n", output.toString(StandardCharsets.UTF_8));
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
    }
  }

  @Test
  void sendFailureCallbackCannotDeadlockForegroundLeaseAdmission() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    Leelaz engine = reusableKatagoEngine(false, false);
    BlockingFailOnceOutput failingOutput = new BlockingFailOnceOutput();
    installOutput(engine, Leelaz.createCommandOutputStream(failingOutput));
    CountDownLatch commandFinished = new CountDownLatch(1);
    CountDownLatch leaseFinished = new CountDownLatch(1);
    AtomicReference<Leelaz.ExclusiveGtpLeaseAvailability> leaseAvailability =
        new AtomicReference<>();
    AtomicReference<Leelaz.EngineModeReservation> callbackReservation = new AtomicReference<>();
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);

      Thread commandThread =
          daemonThread(
              "failing-normal-command",
              () -> {
                try {
                  invokeSendCommandWithResponse(
                      engine,
                      "name",
                      () -> callbackReservation.set(engine.beginEngineModeReservation()));
                } finally {
                  commandFinished.countDown();
                }
              });
      commandThread.start();
      assertTrue(failingOutput.writeStarted.await(1, TimeUnit.SECONDS));

      Thread leaseThread =
          daemonThread(
              "lease-during-send-failure",
              () -> {
                leaseAvailability.set(
                    engine
                        .acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {})
                        .availability());
                leaseFinished.countDown();
              });
      leaseThread.start();
      assertTrue(
          leaseFinished.await(1, TimeUnit.SECONDS),
          "lease admission must not wait for blocked transport I/O");
      assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY, leaseAvailability.get());
      failingOutput.failWrite.countDown();

      assertTrue(
          commandFinished.await(1, TimeUnit.SECONDS),
          "send failure callback must run after command queue admission is released");
      assertTrue(callbackReservation.get() != null);
      callbackReservation.get().close();
    } finally {
      failingOutput.failWrite.countDown();
      if (commandFinished.getCount() == 0 && leaseFinished.getCount() == 0) {
        engine.endExclusiveGtpSession();
      }
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
    }
  }

  @Test
  void foregroundLeaseAdmissionDoesNotWaitForBlockedKomiTransport() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    Leelaz engine = reusableKatagoEngine(false, false);
    BlockingFailOnceOutput failingOutput = new BlockingFailOnceOutput();
    installOutput(engine, Leelaz.createCommandOutputStream(failingOutput));
    CountDownLatch commandFinished = new CountDownLatch(1);
    AtomicReference<Leelaz.ExclusiveGtpLeaseAvailability> leaseAvailability =
        new AtomicReference<>();
    try {
      Lizzie.leelaz = engine;
      Lizzie.board = new Board();
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);

      Thread commandThread =
          daemonThread(
              "blocking-komi-command",
              () -> {
                try {
                  engine.komi(6.5);
                } finally {
                  commandFinished.countDown();
                }
              });
      commandThread.start();
      assertTrue(failingOutput.writeStarted.await(1, TimeUnit.SECONDS));

      Thread leaseThread =
          daemonThread(
              "lease-during-blocking-komi",
              () ->
                  leaseAvailability.set(
                      engine
                          .acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {})
                          .availability()));
      leaseThread.start();
      leaseThread.join(1000L);

      assertFalse(leaseThread.isAlive(), "lease admission must not wait for komi transport I/O");
      assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY, leaseAvailability.get());
      failingOutput.failWrite.countDown();
      assertTrue(commandFinished.await(1, TimeUnit.SECONDS));
    } finally {
      failingOutput.failWrite.countDown();
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
    }
  }

  @Test
  void throwingSendFailureCallbackCannotStrandQueuedCommand() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    Leelaz engine = reusableKatagoEngine(false, false);
    BlockingFailOnceOutput failingOutput = new BlockingFailOnceOutput();
    installOutput(engine, Leelaz.createCommandOutputStream(failingOutput));
    CountDownLatch commandFinished = new CountDownLatch(1);
    AtomicReference<Throwable> commandFailure = new AtomicReference<>();
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);

      Thread commandThread =
          daemonThread(
              "throwing-send-failure-callback",
              () -> {
                try {
                  invokeSendCommandWithResponse(
                      engine,
                      "name",
                      () -> {
                        throw new IllegalStateException("simulated callback failure");
                      });
                } catch (Throwable failure) {
                  commandFailure.set(failure);
                } finally {
                  commandFinished.countDown();
                }
              });
      commandThread.start();
      assertTrue(failingOutput.writeStarted.await(1, TimeUnit.SECONDS));

      engine.sendCommand("version");
      failingOutput.failWrite.countDown();

      assertTrue(commandFinished.await(1, TimeUnit.SECONDS));
      assertTrue(commandFailure.get() != null);
      waitUntil(() -> failingOutput.output().contains("version\n"));
    } finally {
      failingOutput.failWrite.countDown();
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
    }
  }

  @Test
  void cancelledLeaseDropsInitialStopWaitingForTransport() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Leelaz engine = reusableKatagoEngine(false, false);
    ByteArrayOutputStream output = installOutput(engine);
    BufferedOutputStream transport = commandOutputStream(engine);
    Object owner = new Object();
    CountDownLatch outputHeld = new CountDownLatch(1);
    CountDownLatch releaseOutput = new CountDownLatch(1);
    AtomicReference<Leelaz.ExclusiveGtpLeaseAvailability> availability = new AtomicReference<>();
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = null;

      Thread outputHolder =
          daemonThread(
              "initial-stop-output-holder",
              () -> {
                synchronized (transport) {
                  outputHeld.countDown();
                  await(releaseOutput);
                }
              });
      outputHolder.start();
      assertTrue(outputHeld.await(1, TimeUnit.SECONDS));

      Thread acquisitionThread =
          daemonThread(
              "initial-stop-waiting-for-output",
              () ->
                  availability.set(
                      engine.beginForegroundAnalysisLease(owner, line -> {}, () -> {}, () -> {})));
      acquisitionThread.start();
      waitUntil(() -> engine.hasExclusiveGtpLeaseOwnedBy(owner));

      assertTrue(engine.endForegroundAnalysisLease(owner, () -> {}));
      releaseOutput.countDown();
      acquisitionThread.join(1000L);

      assertFalse(acquisitionThread.isAlive());
      assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY, availability.get());
      assertEquals("", output.toString(StandardCharsets.UTF_8));
    } finally {
      releaseOutput.countDown();
      engine.endExclusiveGtpSession();
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void closedLeaseDropsFinalStopWaitingForTransport() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Leelaz engine = reusableKatagoEngine(false, false);
    ByteArrayOutputStream output = installOutput(engine);
    BufferedOutputStream transport = commandOutputStream(engine);
    Object owner = new Object();
    CountDownLatch outputHeld = new CountDownLatch(1);
    CountDownLatch releaseOutput = new CountDownLatch(1);
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = null;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginForegroundAnalysisLease(owner, line -> {}, () -> {}, () -> {}));
      dispatch(engine, "=800000000");
      assertTrue(dispatch(engine, ""));

      Thread outputHolder =
          daemonThread(
              "final-stop-output-holder",
              () -> {
                synchronized (transport) {
                  outputHeld.countDown();
                  await(releaseOutput);
                }
              });
      outputHolder.start();
      assertTrue(outputHeld.await(1, TimeUnit.SECONDS));

      Thread releaseThread =
          daemonThread(
              "final-stop-waiting-for-output",
              () -> engine.endForegroundAnalysisLease(owner, () -> {}));
      releaseThread.start();
      waitForState(releaseThread, Thread.State.BLOCKED);

      engine.endExclusiveGtpSession();
      releaseOutput.countDown();
      releaseThread.join(1000L);

      assertFalse(releaseThread.isAlive());
      assertEquals("800000000 stop\n", output.toString(StandardCharsets.UTF_8));
    } finally {
      releaseOutput.countDown();
      engine.endExclusiveGtpSession();
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void timedOutLeaseDropsFinalStopWaitingForTransport() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    RecordingRestoreLeelaz engine = recordingRestoreEngine();
    engine.releaseStopTimeoutMillis = 25L;
    ByteArrayOutputStream output = installOutput(engine);
    BufferedOutputStream transport = commandOutputStream(engine);
    Object owner = new Object();
    CountDownLatch outputHeld = new CountDownLatch(1);
    CountDownLatch releaseOutput = new CountDownLatch(1);
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = null;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginForegroundAnalysisLease(owner, line -> {}, () -> {}, () -> {}));
      dispatch(engine, "=800000000");
      assertTrue(dispatch(engine, ""));

      Thread outputHolder =
          daemonThread(
              "timed-final-stop-output-holder",
              () -> {
                synchronized (transport) {
                  outputHeld.countDown();
                  await(releaseOutput);
                }
              });
      outputHolder.start();
      assertTrue(outputHeld.await(1, TimeUnit.SECONDS));

      Thread releaseThread =
          daemonThread(
              "timed-final-stop-waiting-for-output",
              () -> engine.endForegroundAnalysisLease(owner, () -> {}));
      releaseThread.start();
      waitForState(releaseThread, Thread.State.BLOCKED);
      waitUntil(() -> !engine.hasExclusiveGtpLease());
      releaseOutput.countDown();
      releaseThread.join(1000L);

      assertFalse(releaseThread.isAlive());
      assertEquals("800000000 stop\n", output.toString(StandardCharsets.UTF_8));
    } finally {
      releaseOutput.countDown();
      engine.endExclusiveGtpSession();
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void foregroundLeaseCanOnlyBeReleasedByItsAcquisitionOwner() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);
    Object owner = new Object();
    AtomicInteger restoreCompletions = new AtomicInteger();
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = null;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginForegroundAnalysisLease(owner, line -> {}, () -> {}, () -> {}));
      dispatch(engine, "=800000000");
      assertTrue(dispatch(engine, ""));

      engine.endForegroundAnalysisLease(new Object());
      assertTrue(engine.hasExclusiveGtpLeaseOwnedBy(owner));

      Lizzie.leelaz = null;
      assertTrue(engine.endForegroundAnalysisLease(owner, restoreCompletions::incrementAndGet));
      assertTrue(dispatch(engine, "=800000001"));
      assertTrue(dispatch(engine, ""));
      assertFalse(engine.hasExclusiveGtpLease());
      assertEquals(1, restoreCompletions.get());
      assertFalse(engine.endForegroundAnalysisLease(owner, restoreCompletions::incrementAndGet));
      assertEquals(1, restoreCompletions.get());
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void foxOwnerIsNotInterruptibleQuickAnalysisAndRejectsForeignWrites() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    ByteArrayOutputStream output = installOutput(engine);
    Object owner = new Object();
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginForegroundAnalysisLease(owner, line -> {}, () -> {}, () -> {}));
      assertFalse(engine.hasForegroundAnalysisLeaseWorkInProgress());
      assertFalse(engine.claimFoxSubmission(owner, () -> true), "initial stop not yet confirmed");
      dispatch(engine, "=800000000");
      dispatch(engine, "");
      assertTrue(engine.claimFoxSubmission(owner, () -> true));
      assertFalse(engine.claimFoxSubmission(new Object(), () -> true));
      assertFalse(engine.sendExclusiveGtpCommand(new Object(), "clear_board"));
      assertTrue(engine.sendExclusiveGtpCommand(owner, "name"));
      assertFalse(output.toString(StandardCharsets.UTF_8).contains("clear_board"));
      engine.endExclusiveGtpSession();
      assertFalse(engine.sendExclusiveGtpCommand(owner, "name"));
      assertFalse(
          engine.claimFoxSubmission(owner, () -> true), "retired owner cannot send a late choice");
    }
  }

  @Test
  void foxPreparationRequiresCompleteMatchingRulesWithoutChangingOtherParameters()
      throws Exception {
    KataGoRules custom =
        KataGoRules.parse("chinese")
            .orElseThrow()
            .overlayEditor("AREA", "POSITIONAL", true, "ALL", "N-1", false, true);
    for (String result : List.of("success", "mismatch", "rejected", "invalid", "visits-rejected")) {
      Leelaz engine = reusableKatagoEngine(false, false);
      ByteArrayOutputStream output = installOutput(engine);
      try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
        FoxEngineSession adapter =
            new FoxEngineSession(
                engine, engine.engineIncarnationToken(), candidates -> {}, reason -> fail(reason));
        assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, adapter.acquire(() -> {}));
        dispatch(engine, "=800000000");
        dispatch(engine, "");
        AtomicInteger consumed = new AtomicInteger(1);
        var preparation =
            new java.util.concurrent.FutureTask<Void>(
                () -> {
                  adapter.prepare(custom.toGtpArgument(), null);
                  return null;
                });
        Thread thread = new Thread(preparation, "fox-custom-rules-test");
        thread.setDaemon(true);
        thread.start();
        for (int i = 0;
            i < (result.equals("success") ? 9 : result.equals("visits-rejected") ? 8 : 7);
            i++) {
          String command = nextFoxCommand(output, consumed);
          boolean set = command.contains(" kata-set-rules ");
          if (set) assertTrue(command.endsWith(custom.toGtpArgument()));
          String payload =
              command.endsWith(" list_commands")
                  ? "kata-search_analyze_cancellable kata-get-rules kata-set-rules kata-get-param kata-set-param kgs-time_settings kata-time_settings kata-list_time_settings set_free_handicap time_left stop"
                  : command.endsWith(" kata-list_time_settings")
                      ? "none byoyomi"
                      : command.endsWith(" kata-get-param maxTime")
                          ? "3.0"
                          : command.endsWith(" kata-get-param maxVisits")
                              ? "500"
                              : command.endsWith(" kata-get-param playoutDoublingAdvantage")
                                  ? "0.2"
                                  : command.endsWith(" kata-get-rules")
                                      ? (i < 5
                                          ? "japanese"
                                          : result.equals("mismatch")
                                              ? "chinese"
                                              : result.equals("invalid")
                                                  ? "{}"
                                                  : custom.toGtpArgument())
                                      : "";
          boolean reject =
              (set && result.equals("rejected"))
                  || (command.contains("kata-set-param maxVisits")
                      && result.equals("visits-rejected"));
          dispatch(engine, (reject ? "?" : "=") + command.split(" ", 2)[0] + " " + payload);
          dispatch(engine, "");
          if (reject) break;
        }
        if (result.equals("success")) preparation.get(2, TimeUnit.SECONDS);
        else
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> preparation.get(2, TimeUnit.SECONDS));
        assertFalse(
            output.toString(StandardCharsets.UTF_8).contains("kata-set-param numSearchThreads"),
            "preparation preserves threads and all non-owned parameters");
      }
    }
  }

  @Test
  void foxCancelledAndLateRealChoicesCannotAcquireSubmissionPermission() throws Exception {
    for (String terminal :
        List.of("cancelled", "cancel-race", "late-choice", "resign", "stale-resign")) {
      Leelaz engine = reusableKatagoEngine(false, false);
      ByteArrayOutputStream output = installOutput(engine);
      try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
        AtomicReference<FoxEngineSession.Candidates> candidates = new AtomicReference<>();
        FoxEngineSession adapter =
            new FoxEngineSession(
                engine,
                engine.engineIncarnationToken(),
                candidates::set,
                reason -> {
                  throw new AssertionError(reason);
                });
        assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, adapter.acquire(() -> {}));
        dispatch(engine, "=800000000");
        dispatch(engine, "");
        AtomicInteger consumed = new AtomicInteger(1);
        var preparation =
            new java.util.concurrent.FutureTask<Void>(
                () -> {
                  adapter.prepare("chinese", null);
                  return null;
                });
        Thread preparationThread = new Thread(preparation, "fox-prepare-boundary-test");
        preparationThread.setDaemon(true);
        preparationThread.start();
        for (int i = 0; i < 9; i++) {
          String command = nextFoxCommand(output, consumed);
          String payload =
              command.endsWith(" list_commands")
                  ? "kata-search_analyze_cancellable kata-get-rules kata-set-rules kata-get-param kata-set-param kgs-time_settings kata-time_settings kata-list_time_settings set_free_handicap time_left stop"
                  : command.endsWith(" kata-list_time_settings")
                      ? "none byoyomi"
                      : command.endsWith(" kata-get-rules")
                          ? "chinese"
                          : command.endsWith(" kata-get-param maxTime")
                              ? "0.25"
                              : command.endsWith(" kata-get-param maxVisits")
                                  ? "500"
                                  : command.endsWith(" kata-get-param playoutDoublingAdvantage")
                                      ? "0.2"
                                      : "";
          dispatch(engine, "=" + command.split(" ", 2)[0] + " " + payload);
          dispatch(engine, "");
        }
        preparation.get(2, TimeUnit.SECONDS);
        featurecat.lizzie.fox.FoxGameState state = new featurecat.lizzie.fox.FoxGameState();
        long now = System.nanoTime();
        state.connected();
        state.receive("FARULE,19,60,30,3,650", now);
        state.receive("FASTATUS,1,B", now);
        state.receive("FATIMELEFT,60,30,3", now);
        state.receive("FAMOVE,1,B,0^0^0^N", now);
        assertTrue(state.beginClockRefresh(now));
        state.receive("FATIMELEFT,60,30,3", now);
        var search =
            new java.util.concurrent.FutureTask<FoxEngineSession.Selection>(
                () -> adapter.analyze(state.snapshot()));
        Thread searchThread = new Thread(search, "fox-search-boundary-test");
        searchThread.setDaemon(true);
        searchThread.start();
        String command;
        while (!(command = nextFoxCommand(output, consumed))
            .contains("kata-search_analyze_cancellable")) {
          if (command.contains("kata-set-param maxTime")) assertTrue(command.endsWith(" 0.125"));
          dispatch(engine, "=" + command.split(" ", 2)[0]);
          dispatch(engine, "");
        }
        dispatch(engine, "=" + command.split(" ", 2)[0] + "0 unrelated response");
        dispatch(engine, "");
        dispatch(engine, "=" + command.split(" ", 2)[0]);
        dispatch(engine, "info move D4 visits 12 winrate 0.55 scoreMean 1 pv D4");
        waitUntil(() -> candidates.get() != null);
        if (terminal.equals("late-choice")) {
          for (int i = 0; i < 1500; i++)
            dispatch(
                engine, "info move D4 visits " + (20 + i) + " winrate 0.55 scoreMean 1 pv D4 Q16");
        }
        if (terminal.equals("cancel-race") || terminal.equals("stale-resign")) adapter.invalidate();
        dispatch(
            engine,
            terminal.equals("cancelled")
                ? "play cancelled"
                : terminal.endsWith("resign") ? "play resign" : "play D4");
        dispatch(engine, "");
        FoxEngineSession.Selection selection = search.get(2, TimeUnit.SECONDS);
        if (terminal.equals("late-choice")) {
          assertNotNull(selection);
          assertEquals(candidates.get().identity(), selection.identity());
          assertEquals("D4", selection.vertex());
          String stream = adapter.drainTranscript();
          assertTrue(stream.length() <= FoxGtpTranscript.MAX_CHARS);
          assertFalse(stream.contains("list_commands"), "old display entries evicted");
          assertTrue(stream.contains("< info move D4 visits 1519"));
          assertTrue(stream.contains("< play D4"), "final response retains protocol ownership");
          adapter.invalidate();
          assertFalse(adapter.claimSubmission(selection, () -> true));
        } else if (terminal.equals("resign")) {
          assertNotNull(selection);
          assertEquals("resign", selection.vertex());
          assertEquals(candidates.get().identity(), selection.identity());
          adapter.invalidate();
          assertFalse(adapter.claimSubmission(selection, () -> true));
        } else assertNull(selection);
        assertTrue(state.snapshot().actions().isEmpty());
      }
    }
  }

  @Test
  void foxTranscriptDoesNotRelabelOldResponsesAfterReconnect() {
    FoxGtpTranscript transcript = new FoxGtpTranscript();
    transcript.begin(1);
    transcript.sent("101 kata-search_analyze_cancellable B 10");
    transcript.received("=101");
    transcript.begin(2);
    transcript.received("info move D4 visits 12");
    transcript.received("play D4");
    transcript.received("");
    assertEquals("", transcript.drain());
    transcript.sent("102 name");
    transcript.received("=101 stale");
    transcript.received("");
    transcript.received("=102 KataGo");
    transcript.received("");
    assertEquals("[2] > 102 name\n[2] < =102 KataGo\n[2] < \n", transcript.drain());
  }

  @Test
  void foxLimitValidationUsesOrdinaryUnlimitedSentinels() {
    assertEquals(
        new FoxEngineSession.Limits(1e20, 1125899906842624L),
        FoxEngineSession.Limits.parse("0", "0"));
    assertEquals(
        new FoxEngineSession.Limits(0.5, 200), FoxEngineSession.Limits.parse("0.5", "200"));
    for (String value : List.of("-1", "NaN", "Infinity", "1e21", ""))
      assertThrows(
          IllegalArgumentException.class, () -> FoxEngineSession.Limits.parse(value, "100"));
    for (String value : List.of("-1", "2.5", "1125899906842625", ""))
      assertThrows(IllegalArgumentException.class, () -> FoxEngineSession.Limits.parse("1", value));
  }

  @Test
  void foxExplicitLimitsAndResearchConsumeOriginalTurnIncludingReplayAndQueue() throws Exception {
    for (String scenario :
        List.of(
            "visits-first", "time-first", "fox-first", "queue", "replay", "set-error", "late")) {
      Leelaz engine = reusableKatagoEngine(false, false);
      ByteArrayOutputStream output = installOutput(engine);
      try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        FoxEngineSession adapter =
            new FoxEngineSession(
                engine, engine.engineIncarnationToken(), value -> {}, reason -> fail(reason));
        adapter.useClockForTest(clock::get);
        assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, adapter.acquire(() -> {}));
        dispatch(engine, "=800000000");
        dispatch(engine, "");
        AtomicInteger consumed = new AtomicInteger(1);
        var preparation =
            new java.util.concurrent.FutureTask<Void>(
                () -> {
                  adapter.prepare("chinese", new FoxEngineSession.Limits(10, 256));
                  return null;
                });
        startFoxTestTask(preparation);
        for (int i = 0; i < 9; i++) {
          String command = nextFoxCommand(output, consumed);
          String payload =
              command.endsWith(" list_commands")
                  ? "kata-search_analyze_cancellable kata-get-rules kata-set-rules kata-get-param kata-set-param kgs-time_settings kata-time_settings kata-list_time_settings set_free_handicap time_left stop"
                  : command.endsWith(" kata-list_time_settings")
                      ? "none byoyomi"
                      : command.endsWith(" kata-get-rules")
                          ? "chinese"
                          : command.endsWith(" kata-get-param maxTime")
                              ? "0.25"
                              : command.endsWith(" kata-get-param maxVisits")
                                  ? "8"
                                  : command.endsWith(" kata-get-param playoutDoublingAdvantage")
                                      ? "0.2"
                                      : "";
          if (command.contains("kata-set-param maxVisits")) assertTrue(command.endsWith(" 256"));
          dispatch(engine, "=" + command.split(" ", 2)[0] + " " + payload);
          dispatch(engine, "");
        }
        preparation.get(2, TimeUnit.SECONDS);
        clock.set(2_000_000_000L); // owner initialization and parameter ACK cost
        var state = new featurecat.lizzie.fox.FoxGameState();
        state.setDirectMaxTime(10);
        state.connected();
        state.receive("FARULE,19,60,30,3,650", 0);
        state.receive("FASTATUS,1,B", 0);
        state.receive("FATIMELEFT,60,30,3", 0);
        state.receive("FAMOVE,1,B,0^0^0^N", 0);
        state.receive("FAMOVE,0,B,1^16^3^B", 0);
        state.receive("FAMOVE,1,B,2^3^15^W", 0);
        assertTrue(state.beginClockRefresh(0));
        state.receive(scenario.equals("fox-first") ? "FATIMELEFT,8,30,3" : "FATIMELEFT,60,30,3", 0);
        long deadline = state.snapshot().submitDeadlineNanos();
        int rounds = scenario.equals("visits-first") ? 2 : 1;
        for (int round = 0; round < rounds; round++) {
          var search =
              new java.util.concurrent.FutureTask<FoxEngineSession.Selection>(
                  () -> adapter.analyze(state.snapshot()));
          startFoxTestTask(search);
          boolean searchSent = false;
          while (true) {
            String command = nextFoxCommand(output, consumed);
            boolean setTime = command.contains("kata-set-param maxTime");
            if (command.contains(" play ")) {
              clock.addAndGet(1_000_000_000L);
              if (scenario.equals("replay")) clock.set(11_000_000_000L);
            }
            if (command.contains(" time_left "))
              assertTrue(
                  command.endsWith(
                      " "
                          + (scenario.equals("fox-first") ? "3.0" : round == 1 ? "52.0" : "55.0")
                          + " 0"),
                  "time_left reports the Fox clock, while maxTime owns the direct cap: " + command);
            if (setTime) {
              double value = Double.parseDouble(command.substring(command.lastIndexOf(' ') + 1));
              assertEquals(scenario.equals("fox-first") || round == 1 ? 2.5 : 5.5, value, 1e-6);
              assertTrue(value > 0.25, "explicit cap can exceed original ordinary cap");
              if (scenario.equals("queue")) clock.set(10_000_000_000L);
            }
            boolean rejected = setTime && scenario.equals("set-error");
            if (command.contains("kata-search_analyze_cancellable")) {
              searchSent = true;
              clock.set(
                  scenario.equals("late")
                      ? deadline
                      : scenario.equals("time-first") || scenario.equals("fox-first")
                          ? deadline - 100_000_000L
                          : clock.get() + 1_000_000_000L);
              dispatch(engine, "=" + command.split(" ", 2)[0]);
              dispatch(engine, "play D4");
              dispatch(engine, "");
              break;
            }
            dispatch(engine, (rejected ? "?" : "=") + command.split(" ", 2)[0]);
            dispatch(engine, "");
            if (rejected
                || (setTime && scenario.equals("queue"))
                || (command.contains("kgs-time_settings") && scenario.equals("replay"))) break;
          }
          if (List.of("queue", "replay", "set-error").contains(scenario)) {
            assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> search.get(2, TimeUnit.SECONDS));
            assertFalse(searchSent);
          } else if (scenario.equals("late")) assertNull(search.get(2, TimeUnit.SECONDS));
          else {
            var choice = search.get(2, TimeUnit.SECONDS);
            assertNotNull(choice);
            assertTrue(choice.completedNanos() < deadline);
            if (round == rounds - 1)
              assertNotNull(
                  state.prepareSubmission(
                      state.snapshot(), choice.identity().search(), "D4", clock.get()));
            else adapter.invalidate();
          }
          assertEquals(deadline, state.snapshot().submitDeadlineNanos());
          assertEquals(0, state.snapshot().turnStartedNanos());
        }
      }
    }
  }

  @Test
  void foxRecoveredPassReplayRequiresEveryMatchingAckBeforeFreshSearch() throws Exception {
    for (String scenario : List.of("success", "rejected", "timeout", "invalidated")) {
      Leelaz engine = reusableKatagoEngine(false, false);
      ByteArrayOutputStream output = installOutput(engine);
      try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
        FoxEngineSession adapter =
            new FoxEngineSession(
                engine, engine.engineIncarnationToken(), value -> {}, reason -> fail(reason));
        adapter.useClockForTest(() -> 2_000_000_000L);
        assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, adapter.acquire(() -> {}));
        dispatch(engine, "=800000000");
        dispatch(engine, "");
        AtomicInteger consumed = new AtomicInteger(1);
        var preparation =
            new java.util.concurrent.FutureTask<Void>(
                () -> {
                  adapter.prepare("chinese", new FoxEngineSession.Limits(10, 64));
                  return null;
                });
        startFoxTestTask(preparation);
        for (int i = 0; i < 9; i++) {
          String command = nextFoxCommand(output, consumed);
          String payload =
              command.endsWith(" list_commands")
                  ? "kata-search_analyze_cancellable kata-get-rules kata-set-rules kata-get-param kata-set-param kgs-time_settings kata-time_settings kata-list_time_settings set_free_handicap time_left stop"
                  : command.endsWith(" kata-list_time_settings")
                      ? "none byoyomi"
                      : command.endsWith(" kata-get-rules")
                          ? "chinese"
                          : command.endsWith(" kata-get-param maxTime")
                              ? "3"
                              : command.endsWith(" kata-get-param maxVisits")
                                  ? "64"
                                  : command.endsWith(" kata-get-param playoutDoublingAdvantage")
                                      ? "0"
                                      : "";
          dispatch(engine, "=" + command.split(" ", 2)[0] + " " + payload);
          dispatch(engine, "");
        }
        preparation.get(2, TimeUnit.SECONDS);
        var game = new featurecat.lizzie.fox.FoxGameState();
        game.connected();
        for (String message :
            List.of(
                "FARULE,19,60,30,3,650",
                "FASTATUS,1,B",
                "FATIMELEFT,60,30,3",
                "FAMOVE,1,B,0^0^0^N",
                "FAMOVE,0,B,1^16^3^B",
                "FAMOVE,1,B,2^3^15^W",
                "FASKIP,0,B")) game.receive(message, 0);
        game.disconnected();
        game.connected();
        for (String message :
            List.of(
                "FARULE,19,60,30,3,650",
                "FASTATUS,1,B,1^16^3^B,2^3^15^W,3^15^15^W",
                "FATIMELEFT,50,30,3",
                "FAMOVE,1,B,3^15^15^W")) game.receive(message, 1_000_000_000L);
        assertTrue(game.recoverHistory(1_000_000_000L));
        assertTrue(game.beginClockRefresh(1_000_000_000L));
        game.receive("FATIMELEFT,49,30,3", 1_000_000_000L);
        var search =
            new java.util.concurrent.FutureTask<FoxEngineSession.Selection>(
                () -> adapter.analyze(game.snapshot()));
        startFoxTestTask(search);
        for (String expected :
            List.of(
                "boardsize 19",
                "clear_board",
                "komi 6.5",
                "play B R16",
                "play W D4",
                "play B pass")) {
          String command = nextFoxCommand(output, consumed);
          assertTrue(command.endsWith(" " + expected), command);
          String held = output.toString(StandardCharsets.UTF_8);
          dispatch(engine, "=999999999");
          dispatch(engine, "");
          assertThrows(
              java.util.concurrent.TimeoutException.class,
              () -> search.get(30, TimeUnit.MILLISECONDS));
          assertEquals(
              held,
              output.toString(StandardCharsets.UTF_8),
              "unmatched response cannot advance replay");
          if (expected.equals("play B pass")) {
            if (scenario.equals("timeout")) break;
            if (scenario.equals("invalidated")) adapter.invalidate();
          }
          dispatch(
              engine,
              (expected.equals("play B pass") && scenario.equals("rejected") ? "?" : "=")
                  + command.split(" ", 2)[0]);
          dispatch(engine, "");
        }
        if (scenario.equals("success")) {
          assertTrue(nextFoxCommand(output, consumed).endsWith(" play W Q4"));
          String command =
              output.toString(StandardCharsets.UTF_8).lines().toList().get(consumed.get() - 1);
          dispatch(engine, "=" + command.split(" ", 2)[0]);
          dispatch(engine, "");
          do {
            command = nextFoxCommand(output, consumed);
            dispatch(engine, "=" + command.split(" ", 2)[0]);
            if (command.contains("kata-search_analyze_cancellable")) dispatch(engine, "play D16");
            dispatch(engine, "");
          } while (!command.contains("kata-search_analyze_cancellable"));
          assertNotNull(search.get(2, TimeUnit.SECONDS));
        } else {
          if (scenario.equals("invalidated")) assertNull(search.get(2, TimeUnit.SECONDS));
          else
            assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> search.get(7, TimeUnit.SECONDS));
          assertFalse(output.toString(StandardCharsets.UTF_8).contains("play W Q4"));
          assertFalse(
              output
                  .toString(StandardCharsets.UTF_8)
                  .contains("kata-search_analyze_cancellable B 10"));
        }
      }
    }
  }

  @Test
  void foxPdaUpdateDrainsMatchingStopAndUsesLatestIntentWithinOriginalBudget() throws Exception {
    for (String scenario :
        List.of("rapid", "stop-error", "set-error", "readback-error", "attribution", "exhausted")) {
      Leelaz engine = reusableKatagoEngine(false, false);
      var capability = Leelaz.class.getDeclaredField("foxPdaCompatibility");
      capability.setAccessible(true);
      capability.set(engine, "");
      ByteArrayOutputStream output = installOutput(engine);
      try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        AtomicReference<FoxEngineSession.Candidates> candidates = new AtomicReference<>();
        FoxEngineSession adapter =
            new FoxEngineSession(
                engine, engine.engineIncarnationToken(), candidates::set, key -> fail(key));
        adapter.useClockForTest(clock::get);
        assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, adapter.acquire(() -> {}));
        dispatch(engine, "=800000000");
        dispatch(engine, "");
        AtomicInteger consumed = new AtomicInteger(1);
        var preparation =
            new java.util.concurrent.FutureTask<Void>(
                () -> {
                  adapter.prepare("chinese", new FoxEngineSession.Limits(10, 256));
                  return null;
                });
        startFoxTestTask(preparation);
        for (int i = 0; i < 9; i++) {
          String command = nextFoxCommand(output, consumed);
          String payload =
              command.endsWith(" list_commands")
                  ? "kata-search_analyze_cancellable kata-get-rules kata-set-rules kata-get-param kata-set-param kgs-time_settings kata-time_settings kata-list_time_settings set_free_handicap time_left stop"
                  : command.endsWith(" kata-list_time_settings")
                      ? "none byoyomi"
                      : command.endsWith(" kata-get-rules")
                          ? "chinese"
                          : command.endsWith(" kata-get-param maxTime")
                              ? "3"
                              : command.endsWith(" kata-get-param maxVisits")
                                  ? "64"
                                  : command.endsWith(" kata-get-param playoutDoublingAdvantage")
                                      ? "0.2"
                                      : "";
          dispatch(engine, "=" + command.split(" ", 2)[0] + " " + payload);
          dispatch(engine, "");
        }
        preparation.get(2, TimeUnit.SECONDS);
        assertEquals(0.2, adapter.confirmedPda());
        assertFalse(
            output
                .toString(StandardCharsets.UTF_8)
                .contains("kata-set-param playoutDoublingAdvantage"));
        var game = new featurecat.lizzie.fox.FoxGameState();
        game.setDirectMaxTime(10);
        game.connected();
        game.receive("FARULE,19,60,30,3,650", 0);
        game.receive("FASTATUS,1,B", 0);
        game.receive("FATIMELEFT,60,30,3", 0);
        game.receive("FAMOVE,1,B,0^0^0^N", 0);
        assertTrue(game.beginClockRefresh(0));
        game.receive("FATIMELEFT,60,30,3", 0);
        long deadline = game.snapshot().submitDeadlineNanos();
        var search =
            new java.util.concurrent.FutureTask<FoxEngineSession.Selection>(
                () -> adapter.analyze(game.snapshot()));
        startFoxTestTask(search);
        String command;
        while (!(command = nextFoxCommand(output, consumed))
            .contains("kata-search_analyze_cancellable")) {
          dispatch(engine, "=" + command.split(" ", 2)[0]);
          dispatch(engine, "");
        }
        dispatch(engine, "=" + command.split(" ", 2)[0]);
        dispatch(engine, "info move D4 visits 12 winrate 0.55 scoreMean 1 pv D4");
        waitUntil(() -> candidates.get() != null);
        var oldCandidate = candidates.get();
        adapter.desirePda(0.4);
        adapter.invalidate();
        String stop = nextFoxCommand(output, consumed);
        assertTrue(stop.endsWith(" stop"));
        adapter.desirePda(0.6);
        adapter.invalidate();
        assertFalse(adapter.isCurrent(oldCandidate));
        dispatch(engine, "info move Q16 visits 20 winrate 0.6 scoreMean 2 pv Q16");
        dispatch(engine, "play D4");
        dispatch(engine, "");
        assertNull(search.get(2, TimeUnit.SECONDS));
        assertSame(oldCandidate, candidates.get(), "late candidate rejected");
        var update =
            new java.util.concurrent.FutureTask<Void>(
                () -> {
                  adapter.applyDesiredPda();
                  return null;
                });
        startFoxTestTask(update);
        Thread.sleep(30);
        assertEquals(
            consumed.get(),
            output.toString(StandardCharsets.UTF_8).lines().count(),
            "setter waits for stop ACK");
        clock.set(scenario.equals("exhausted") ? deadline : 4_000_000_000L);
        if (scenario.equals("attribution")) {
          dispatch(engine, "=" + stop.split(" ", 2)[0]);
          dispatch(engine, "=123456 wrong owner");
          dispatch(engine, "");
        } else {
          dispatch(engine, (scenario.equals("stop-error") ? "?" : "=") + stop.split(" ", 2)[0]);
          dispatch(engine, "");
        }
        if (scenario.equals("stop-error") || scenario.equals("attribution")) {
          assertThrows(
              java.util.concurrent.ExecutionException.class, () -> update.get(2, TimeUnit.SECONDS));
          assertEquals(0.2, adapter.confirmedPda());
          assertPdaFailureQuarantined(adapter, engine, output, consumed);
          continue;
        }
        command = nextFoxCommand(output, consumed);
        assertTrue(command.endsWith("kata-set-param playoutDoublingAdvantage 0.6"), command);
        if (scenario.equals("rapid")) adapter.desirePda(0.8);
        dispatch(engine, (scenario.equals("set-error") ? "?" : "=") + command.split(" ", 2)[0]);
        dispatch(engine, "");
        if (!scenario.equals("set-error")) {
          command = nextFoxCommand(output, consumed);
          assertTrue(command.endsWith("kata-get-param playoutDoublingAdvantage"));
          dispatch(
              engine,
              "="
                  + command.split(" ", 2)[0]
                  + (scenario.equals("readback-error") ? " 0.1" : " 0.6"));
          dispatch(engine, "");
        }
        if (scenario.equals("set-error") || scenario.equals("readback-error")) {
          assertThrows(
              java.util.concurrent.ExecutionException.class, () -> update.get(2, TimeUnit.SECONDS));
          assertEquals(0.2, adapter.confirmedPda());
          assertThrows(IllegalStateException.class, () -> adapter.analyze(game.snapshot()));
          assertPdaFailureQuarantined(adapter, engine, output, consumed);
          continue;
        }
        if (scenario.equals("rapid")) {
          command = nextFoxCommand(output, consumed);
          assertTrue(command.endsWith("kata-set-param playoutDoublingAdvantage 0.8"));
          dispatch(engine, "=" + command.split(" ", 2)[0]);
          dispatch(engine, "");
          command = nextFoxCommand(output, consumed);
          dispatch(engine, "=" + command.split(" ", 2)[0] + " 0.8");
          dispatch(engine, "");
        }
        update.get(2, TimeUnit.SECONDS);
        assertEquals(scenario.equals("rapid") ? 0.8 : 0.6, adapter.confirmedPda());
        var research =
            new java.util.concurrent.FutureTask<FoxEngineSession.Selection>(
                () -> adapter.analyze(game.snapshot()));
        startFoxTestTask(research);
        while (true) {
          command = nextFoxCommand(output, consumed);
          if (command.contains("kata-set-param maxTime"))
            assertEquals(
                5.5, Double.parseDouble(command.substring(command.lastIndexOf(' ') + 1)), 1e-9);
          if (command.contains("kata-search_analyze_cancellable")) {
            dispatch(engine, "=" + command.split(" ", 2)[0]);
            dispatch(engine, "play Q16");
            dispatch(engine, "");
            break;
          }
          dispatch(engine, "=" + command.split(" ", 2)[0]);
          dispatch(engine, "");
          if (scenario.equals("exhausted") && command.contains("kgs-time_settings")) break;
        }
        if (scenario.equals("exhausted"))
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> research.get(2, TimeUnit.SECONDS));
        else assertEquals("Q16", research.get(2, TimeUnit.SECONDS).vertex());
        assertEquals(deadline, game.snapshot().submitDeadlineNanos());
        assertEquals(0, game.snapshot().turnStartedNanos());
      }
    }
  }

  private static void assertPdaFailureQuarantined(
      FoxEngineSession adapter, Leelaz engine, ByteArrayOutputStream output, AtomicInteger consumed)
      throws Exception {
    AtomicBoolean failed = new AtomicBoolean();
    adapter.release(() -> fail("unconfirmed PDA cannot hand back healthy"), () -> failed.set(true));
    String finalStop = nextFoxCommand(output, consumed);
    assertTrue(finalStop.endsWith(" stop"));
    dispatch(engine, "=" + finalStop.split(" ", 2)[0]);
    dispatch(engine, "");
    waitUntil(failed::get);
    assertFalse(engine.isLoaded(), "existing unrestored engine barrier retained");
    assertFalse(
        engine.previewForegroundAnalysisLeaseAvailability()
            == Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE);
  }

  private static void startFoxTestTask(Runnable task) {
    Thread thread = new Thread(task, "fox-budget-test");
    thread.setDaemon(true);
    thread.start();
  }

  private static String nextFoxCommand(ByteArrayOutputStream output, AtomicInteger consumed)
      throws Exception {
    waitUntil(() -> output.toString(StandardCharsets.UTF_8).lines().count() > consumed.get());
    return output.toString(StandardCharsets.UTF_8).lines().toList().get(consumed.getAndIncrement());
  }

  @Test
  void foxSubmissionCannotCrossReaderReplacement() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);
    Object owner = new Object();
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginForegroundAnalysisLease(owner, line -> {}, () -> {}, () -> {}));
      dispatch(engine, "=800000000");
      dispatch(engine, "");
      assertTrue(engine.claimFoxSubmission(owner, () -> true));
      engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      assertFalse(engine.claimFoxSubmission(owner, () -> true));
    }
  }

  @Test
  void foxRecoveryCannotAcquireReplacementReader() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);
    Object retainedReader = engine.engineIncarnationToken();
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
      ByteArrayOutputStream replacement = new ByteArrayOutputStream();
      engine.installFreshCommandOutputForTest(replacement);
      FoxEngineSession adapter =
          new FoxEngineSession(
              engine,
              retainedReader,
              candidates -> fail("old reader published candidates"),
              reason -> {});
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY,
          adapter.acquire(() -> fail("old reader acquired replacement")));
      assertThrows(IllegalStateException.class, () -> adapter.prepare("chinese", null));
      assertEquals("", replacement.toString(StandardCharsets.UTF_8));
      assertFalse(engine.hasExclusiveGtpLease());
    }
  }

  @Test
  void foxRuntimeRestoreRejectionCannotBeHiddenByFinalName() throws Exception {
    for (String rejected :
        List.of(
            "kata-set-rules",
            "kata-get-rules",
            "kata-set-param maxTime",
            "kata-set-param maxVisits",
            "kata-time_settings none")) {
      try (ProductionForegroundRestoreHarness harness =
          ProductionForegroundRestoreHarness.open(true, rejected)) {
        assertTrue(harness.engine.setFoxRestoreState(harness.lease, "chinese", 3.0, 500));
        assertTrue(
            harness.lease.release(
                harness.completions::incrementAndGet, harness.failures::incrementAndGet));
        dispatch(harness.engine, "=800000001");
        dispatch(harness.engine, "");
        waitUntil(() -> harness.failures.get() == 1);
        assertTrue(harness.output.rejected, "restore must reach " + rejected);
        assertEquals(0, harness.completions.get());
        assertFalse(harness.engine.isLoaded());
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        assertEquals(0, harness.engine.ponderCount);
        assertBreadthAvailable(false);
      }
    }
  }

  @Test
  void foxHandbackWaitsForBoardConfirmationAndDiscardsPriorPonderIntent() throws Exception {
    RecordingRestoreLeelaz engine = recordingRestoreEngine();
    installOutput(engine);
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    GtpConsolePane previousConsole = Lizzie.gtpConsole;
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      RecordingRestoreBoard board = allocate(RecordingRestoreBoard.class);
      Lizzie.board = board;
      engine.Pondering();
      FoxEngineSession fox =
          new FoxEngineSession(engine, engine.engineIncarnationToken(), value -> {}, key -> {});
      assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, fox.acquire(() -> {}));
      dispatch(engine, "=800000000");
      dispatch(engine, "");
      AtomicInteger completed = new AtomicInteger();
      AtomicInteger failed = new AtomicInteger();
      board.currentMarker = 2;
      fox.release(completed::incrementAndGet, failed::incrementAndGet);
      dispatch(engine, "=800000001");
      dispatch(engine, "");
      waitUntil(() -> board.resendCount == 1);
      assertTrue(engine.hasExclusiveGtpWorkInProgress());
      assertEquals(0, completed.get());
      assertEquals(0, engine.ponderCount);
      assertBreadthAvailable(false);
      completeForegroundRestore(engine);
      waitUntil(() -> completed.get() == 1);
      assertEquals(2, board.restoredMarker);
      assertEquals(0, failed.get());
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
      assertEquals(0, engine.ponderCount, "Fox handback restores admission, not analysis intent");
      assertFalse(engine.isPondering());
      assertBreadthAvailable(true);
    } finally {
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousConsole;
    }
  }

  @Test
  void ordinaryBreadthDoesNotRequireFlashAnalysisCommands() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);
    engine.commandLists.remove("set_position");
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.MISSING_CAPABILITY,
          engine.previewExclusiveGtpLeaseAvailability());
      assertBreadthAvailable(true);
    }
  }

  @Test
  void breadthRefreshPreservesHumanGenmoveRestriction() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.frame.isPlayingAgainstLeelaz = true;
      assertBreadthAvailable(false);
      Lizzie.frame.isPlayingAgainstLeelaz = false;
      assertBreadthAvailable(true);
    }
  }

  @Test
  void breadthNeedsKataGoAndWaitsForExclusiveWork() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);
    try (ForegroundLeaseGlobalState ignored = ForegroundLeaseGlobalState.install(engine)) {
      assertTrue(engine.canSetAnalysisWideRootNoise());
      engine.isKatago = false;
      assertFalse(engine.canSetAnalysisWideRootNoise(), "breadth is a KataGo parameter");
      engine.isKatago = true;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginForegroundAnalysisLease(new Object(), line -> {}, () -> {}, () -> {}));
      try {
        assertFalse(engine.canSetAnalysisWideRootNoise(), "exclusive work owns the engine");
        assertBreadthAvailable(false);
      } finally {
        engine.endExclusiveGtpSession();
      }
    }
  }

  private static void assertBreadthAvailable(boolean expected) throws Exception {
    featurecat.lizzie.gui.Menu menu = allocate(featurecat.lizzie.gui.Menu.class);
    Field checkbox = featurecat.lizzie.gui.Menu.class.getDeclaredField("chkWRN");
    checkbox.setAccessible(true);
    javax.swing.JCheckBox selected =
        (javax.swing.JCheckBox) checkbox.getType().getConstructor().newInstance();
    selected.setSelected(true);
    checkbox.set(menu, selected);
    Field text = featurecat.lizzie.gui.Menu.class.getDeclaredField("txtWRN");
    javax.swing.JTextField input =
        (javax.swing.JTextField) text.getType().getConstructor().newInstance();
    text.set(menu, input);
    javax.swing.SwingUtilities.invokeAndWait(
        () -> {
          menu.refreshBreadthAdmission();
          assertEquals(expected, selected.isEnabled());
          assertEquals(expected, input.isEnabled());
        });
  }

  @Test
  void foregroundLeaseRestoresLatestBoardAndPreviousPonderingOnlyAfterRestoreResponse()
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    RecordingRestoreLeelaz engine = recordingRestoreEngine();
    installOutput(engine);
    RecordingRestoreBoard board = allocate(RecordingRestoreBoard.class);
    Object owner = new Object();
    AtomicInteger completions = new AtomicInteger();
    try {
      Lizzie.leelaz = engine;
      Lizzie.board = board;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      board.currentMarker = 1;
      engine.Pondering();
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginForegroundAnalysisLease(owner, line -> {}, () -> {}, () -> {}));
      dispatch(engine, "=800000000");
      assertTrue(dispatch(engine, ""));

      board.currentMarker = 2;
      assertTrue(engine.endForegroundAnalysisLease(owner, completions::incrementAndGet));
      assertTrue(dispatch(engine, "=800000001"));
      assertTrue(dispatch(engine, ""));
      waitUntil(() -> board.resendCount == 1);

      assertEquals(2, board.restoredMarker);
      assertEquals(0, engine.ponderCount);
      assertEquals(0, completions.get());

      completeForegroundRestore(engine);
      waitUntil(() -> completions.get() == 1);

      assertEquals(1, engine.ponderCount);
      assertFalse(engine.lifecycleBusyDuringPonder);
      assertEquals(1, completions.get());
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
    }
  }

  @Test
  void foregroundExactRestoreUsesFrozenGameStateAndPondersOnlyAfterNameResponse() throws Exception {
    try (ProductionForegroundRestoreHarness harness = ProductionForegroundRestoreHarness.open()) {
      harness.releaseThroughNameCommand();

      assertTrue(harness.output.loadedSgf().contains("KM[6.5]"));
      assertTrue(harness.output.loadedSgf().contains("SZ[13:9]"));
      assertEquals(13, harness.engine.width);
      assertEquals(9, harness.engine.height);
      assertEquals(0, harness.engine.ponderCount);
      assertEquals(0, harness.completions.get());

      harness.completeNameResponse();

      assertEquals(1, harness.engine.ponderCount);
      assertEquals(1, harness.completions.get());
    }
  }

  @Test
  void staleNumberedErrorDoesNotFailCurrentForegroundRestore() throws Exception {
    try (ProductionForegroundRestoreHarness harness = ProductionForegroundRestoreHarness.open()) {
      harness.releaseThroughNameCommand();

      processCommandResponse(harness.engine, "?999 stale restore error");
      harness.completeNameResponse();

      assertEquals(
          1, harness.completions.get(), "stale response must not terminate foreground restore.");
      assertEquals(1, harness.engine.ponderCount);
    }
  }

  @Test
  void foregroundExactRestoreDoesNotPonderAfterEnteringPlayMode() throws Exception {
    try (ProductionForegroundRestoreHarness harness = ProductionForegroundRestoreHarness.open()) {
      harness.releaseThroughNameCommand();
      Lizzie.frame.isPlayingAgainstLeelaz = true;

      assertEquals(0, harness.engine.ponderCount);
      harness.completeNameResponse();

      assertEquals(0, harness.engine.ponderCount);
      assertEquals(1, harness.completions.get());
    }
  }

  @Test
  void foregroundLeaseReleaseCatchesUpNavigationAfterFinalStopWasSent() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    try {
      harness.board.currentMarker = 1;
      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet));

      // Navigation remains available while the final stop response is pending.
      harness.board.currentMarker = 2;
      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> harness.board.resendCount == 1);

      completeForegroundRestore(harness.engine);
      waitUntil(() -> harness.board.resendCount == 2);
      assertEquals(2, harness.board.restoredMarker);
      assertEquals(0, harness.completions.get());

      completeForegroundRestore(harness.engine);
      waitUntil(() -> harness.completions.get() == 1);
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundFinalFrameCheckReleasesLifecycleBeforeNavigationCanResume() throws Exception {
    RestoreHarness harness = RestoreHarness.open(true, false);
    AtomicReference<Throwable> completionFailure = new AtomicReference<>();
    AtomicReference<Throwable> navigationFailure = new AtomicReference<>();
    Thread completionThread = null;
    Thread navigationThread = null;
    try {
      harness.board.currentMarker = 1;
      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet));
      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> harness.board.resendCount == 1);
      harness.engine.pauseLifecycleRelease();

      completionThread =
          daemonThread(
              "foreground-final-frame-completion",
              () -> {
                try {
                  completeForegroundRestore(harness.engine);
                } catch (Throwable failure) {
                  completionFailure.set(failure);
                }
              });
      completionThread.start();
      await(harness.engine.lifecycleReleaseReached);

      CountDownLatch navigationCompleted = new CountDownLatch(1);
      navigationThread =
          daemonThread(
              "foreground-navigation-after-final-frame",
              () -> {
                try {
                  synchronized (harness.board) {
                    harness.board.currentMarker = 2;
                    harness.engine.sendCommand("play B D4");
                  }
                } catch (Throwable failure) {
                  navigationFailure.set(failure);
                } finally {
                  navigationCompleted.countDown();
                }
              });
      navigationThread.start();
      assertFalse(
          navigationCompleted.await(50, TimeUnit.MILLISECONDS),
          "navigation must wait for the atomic Board/gate handoff");

      harness.engine.continueLifecycleRelease.countDown();
      completionThread.join(5_000L);
      assertTrue(
          navigationCompleted.await(5, TimeUnit.SECONDS),
          "navigation must resume after the final-frame handoff");
      navigationThread.join(1_000L);

      assertNull(completionFailure.get());
      assertNull(navigationFailure.get());
      assertEquals(0L, navigationCompleted.getCount());
      waitUntil(() -> harness.output.toString(StandardCharsets.UTF_8).contains("play B D4"));
      assertEquals(1, harness.engine.ponderCount);
      assertFalse(harness.engine.lifecycleBusyDuringPonder);
    } finally {
      harness.engine.resumeLifecycleRelease();
      if (completionThread != null) {
        completionThread.join(1000L);
      }
      if (navigationThread != null) {
        navigationThread.join(1000L);
      }
      harness.close();
    }
  }

  @Test
  void foregroundRestoreRejectsConflictedMirrorBeforeLoadOrTailReplay() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    Leelaz previousMirror = Lizzie.leelaz2;
    Config config = Lizzie.config;
    RecordingRestoreLeelaz mirror = recordingRestoreEngine();
    ByteArrayOutputStream mirrorOutput = installOutput(mirror);
    Thread boundaryThread = null;
    Leelaz.EngineModeReservation mirrorReservation = null;
    AtomicReference<Throwable> boundaryFailure = new AtomicReference<>();
    try {
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.leelaz2 = mirror;
      harness.engine.pauseReleaseBoundaryAndTimeout();

      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet));
      assertTrue(dispatch(harness.engine, "=800000001"));
      boundaryThread =
          daemonThread(
              "foreground-mirror-admission-boundary",
              () -> {
                try {
                  assertTrue(dispatch(harness.engine, ""));
                } catch (Throwable failure) {
                  boundaryFailure.set(failure);
                }
              });
      boundaryThread.start();
      await(harness.engine.releaseBoundaryReached);

      mirrorReservation = mirror.beginEngineModeReservation();
      assertTrue(mirrorReservation != null, "mirror must be able to claim the conflict window");
      harness.engine.continueReleaseBoundary.countDown();
      boundaryThread.join(1000L);
      assertNull(boundaryFailure.get());

      waitUntil(() -> !harness.engine.isLoaded());
      String mirrorCommands = mirrorOutput.toString(StandardCharsets.UTF_8);
      assertFalse(
          mirrorCommands.contains("loadsgf "),
          "a conflicted mirror must receive no exact loadsgf command");
      assertFalse(
          mirrorCommands.contains("play "),
          "a conflicted mirror must receive no exact trailing replay command");
    } finally {
      if (mirrorReservation != null) {
        mirrorReservation.close();
      }
      harness.engine.resumeReleaseBoundaryAndTimeout();
      if (boundaryThread != null) {
        boundaryThread.join(1000L);
      }
      Lizzie.leelaz2 = previousMirror;
      Lizzie.config = config;
      harness.close();
    }
  }

  @Test
  void foregroundLeaseReleaseFailsClosedWhenMirrorIsAlreadyReserved() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    Leelaz previousMirror = Lizzie.leelaz2;
    Config config = Lizzie.config;
    RecordingRestoreLeelaz mirror = recordingRestoreEngine();
    ByteArrayOutputStream mirrorOutput = installOutput(mirror);
    Leelaz.EngineModeReservation mirrorReservation = null;
    AtomicInteger failures = new AtomicInteger();
    try {
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.leelaz2 = mirror;
      mirrorReservation = mirror.beginEngineModeReservation();
      assertTrue(mirrorReservation != null);
      harness.output.reset();

      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet, failures::incrementAndGet));
      waitUntil(() -> failures.get() == 1);

      assertEquals(0, harness.completions.get());
      assertEquals(
          java.util.Optional.of(Leelaz.ForegroundAnalysisLeaseFailure.RESTORE_FAILED),
          harness.owner.failureReason());
      assertFalse(harness.owner.isOwned());
      assertFalse(harness.engine.hasExclusiveGtpWorkInProgress());
      assertTrue(mirror.hasExclusiveGtpWorkInProgress());
      assertFalse(harness.output.toString(StandardCharsets.UTF_8).contains("loadsgf "));
      assertFalse(harness.output.toString(StandardCharsets.UTF_8).contains("play "));
      assertFalse(mirrorOutput.toString(StandardCharsets.UTF_8).contains("loadsgf "));
      assertFalse(mirrorOutput.toString(StandardCharsets.UTF_8).contains("play "));
    } finally {
      if (mirrorReservation != null) {
        mirrorReservation.close();
      }
      Lizzie.leelaz2 = previousMirror;
      Lizzie.config = config;
      harness.close();
    }
  }

  @Test
  void foregroundRestoreRepeatsWhenSuppressedBoardCommandInvalidatesFirstAttempt()
      throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    try {
      harness.board.currentMarker = 1;
      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet));
      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> harness.board.resendCount == 1);

      harness.board.currentMarker = 2;
      harness.engine.sendCommand("play B D4");
      completeForegroundRestore(harness.engine);
      waitUntil(() -> harness.board.resendCount == 2);

      assertEquals(2, harness.board.restoredMarker);
      assertEquals(0, harness.completions.get());
      completeForegroundRestore(harness.engine);
      waitUntil(() -> harness.completions.get() == 1);
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundRestoreRetryFailsClosedWhenMirrorBecomesReserved() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    Leelaz previousMirror = Lizzie.leelaz2;
    Config config = Lizzie.config;
    RecordingRestoreLeelaz mirror = recordingRestoreEngine();
    CountDownLatch restoreReady = new CountDownLatch(1);
    ExactSnapshotRestoreProtocolFixture.install(
        harness.engine,
        command -> {
          if ("name".equals(command)) {
            restoreReady.countDown();
            return null;
          }
          return "stop".equals(command)
              ? null
              : ExactSnapshotRestoreProtocolFixture.Response.success();
        });
    ExactSnapshotRestoreProtocolFixture.install(
        mirror, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
    Leelaz.EngineModeReservation mirrorReservation = null;
    AtomicInteger failures = new AtomicInteger();
    try {
      config.extraMode = ExtraMode.Double_Engine;
      Lizzie.leelaz2 = mirror;
      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet, failures::incrementAndGet));
      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      // resendCount marks entry, not completion. Acknowledge the first snapshot and wait
      // for its final boundary before reserving the mirror, so only retry admission fails.
      assertTrue(restoreReady.await(3, TimeUnit.SECONDS));
      assertEquals(1, harness.board.resendCount);
      assertEquals(0, failures.get());

      harness.engine.sendCommand("play B D4");
      mirrorReservation = mirror.beginEngineModeReservation();
      assertTrue(mirrorReservation != null);
      invokeResponseHandlerForLine(harness.engine, "=");
      waitUntil(() -> failures.get() == 1);

      assertEquals(0, harness.completions.get());
      assertEquals(
          java.util.Optional.of(Leelaz.ForegroundAnalysisLeaseFailure.RESTORE_FAILED),
          harness.owner.failureReason());
      assertFalse(harness.engine.hasExclusiveGtpWorkInProgress());
      assertTrue(mirror.hasExclusiveGtpWorkInProgress());
    } finally {
      if (mirrorReservation != null) {
        mirrorReservation.close();
      }
      Lizzie.leelaz2 = previousMirror;
      Lizzie.config = config;
      harness.close();
    }
  }

  @Test
  void foregroundLeaseReleaseStopsActiveAnalysisBeforeRestoringBoard() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    try {
      harness.output.reset();
      assertTrue(harness.engine.sendExclusiveGtpCommand("kata-analyze B 50"));
      harness.output.reset();

      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet));

      assertEquals("800000001 stop\n", harness.output.toString(StandardCharsets.UTF_8));
      assertEquals(0, harness.board.resendCount);
      assertEquals(0, harness.completions.get());

      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> harness.board.resendCount == 1);
      completeForegroundRestore(harness.engine);
      waitUntil(() -> harness.completions.get() == 1);
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseReleaseSuccessBoundaryWinsConcurrentTimeout() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    AtomicInteger failures = new AtomicInteger();
    AtomicReference<Throwable> boundaryFailure = new AtomicReference<>();
    Thread boundaryThread =
        daemonThread(
            "foreground-release-boundary",
            () -> {
              try {
                assertTrue(dispatch(harness.engine, ""));
              } catch (Throwable failure) {
                boundaryFailure.set(failure);
              }
            });
    try {
      harness.engine.releaseStopTimeoutMillis = 1L;
      harness.engine.pauseReleaseBoundaryAndTimeout();
      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet, failures::incrementAndGet));
      assertTrue(dispatch(harness.engine, "=800000001"));

      boundaryThread.start();
      await(harness.engine.releaseBoundaryReached);
      await(harness.engine.releaseTimeoutStarted);
      harness.engine.continueReleaseTimeout.countDown();
      await(harness.engine.releaseTimeoutCompleted);
      harness.engine.continueReleaseBoundary.countDown();
      boundaryThread.join(1000L);

      assertFalse(boundaryThread.isAlive());
      assertEquals(null, boundaryFailure.get());
      waitUntil(() -> harness.board.resendCount == 1);
      completeForegroundRestore(harness.engine);
      waitUntil(() -> harness.completions.get() == 1);
      assertEquals(0, failures.get());
      assertEquals(java.util.Optional.empty(), harness.owner.failureReason());
      assertTrue(harness.engine.isLoaded());
    } finally {
      harness.engine.resumeReleaseBoundaryAndTimeout();
      harness.close();
    }
  }

  @Test
  void foregroundLeaseRestoresCapturedRulesBeforeRestoringBoard() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    String originalRules =
        "{\"koRule\":\"POSITIONAL\",\"scoringRule\":\"AREA\",\"taxRule\":\"NONE\"}";
    try {
      assertTrue(
          harness.engine.setForegroundAnalysisLeaseRestoreRules(harness.owner, originalRules));
      harness.output.reset();

      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet));
      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> harness.board.resendCount == 1);

      assertTrue(
          harness
              .output
              .toString(StandardCharsets.UTF_8)
              .contains("\nkata-set-rules " + originalRules + "\n"));
      completeForegroundRestore(harness.engine);
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseDropsNormalCommandsInsteadOfReplayingThemAfterRestore() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    try {
      harness.output.reset();
      harness.engine.sendCommand("play B D4");

      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet));
      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> harness.board.resendCount == 1);
      completeForegroundRestore(harness.engine);

      assertFalse(harness.output.toString(StandardCharsets.UTF_8).contains("play B D4"));
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseRejectsKomiAndBoardSizeBeforeMutatingApplicationState() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    try {
      float originalKomi = harness.engine.komi;
      int originalWidth = harness.engine.width;
      int originalHeight = harness.engine.height;
      harness.output.reset();

      harness.engine.komi(5.5);
      harness.engine.boardSize(13, 13);

      assertEquals(originalKomi, harness.engine.komi);
      assertEquals(originalWidth, harness.engine.width);
      assertEquals(originalHeight, harness.engine.height);
      assertEquals("", harness.output.toString(StandardCharsets.UTF_8));
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseReleaseTimeoutLeavesEngineUnavailable() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    AtomicInteger failures = new AtomicInteger();
    try {
      harness.engine.releaseStopTimeoutMillis = 25L;

      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet, failures::incrementAndGet));
      waitUntil(() -> failures.get() == 1);

      assertFalse(harness.engine.isLoaded());
      assertEquals(0, harness.board.resendCount);
      assertEquals(0, harness.completions.get());
      assertEquals(1, failures.get());
      assertEquals(
          java.util.Optional.of(Leelaz.ForegroundAnalysisLeaseFailure.FINAL_STOP_TIMEOUT),
          harness.owner.failureReason());
    } finally {
      harness.close();
    }
  }

  @Test
  void automaticLeaseFinalStopTimeoutKeepsCurrentReaderVisiblyUnrestored() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false, true);
    AtomicInteger failures = new AtomicInteger();
    try {
      harness.engine.releaseStopTimeoutMillis = 25L;
      harness.output.reset();

      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet, failures::incrementAndGet));
      waitUntil(() -> failures.get() == 1);
      drainEdt();

      assertEquals(
          java.util.Optional.of(Leelaz.ForegroundAnalysisLeaseFailure.FINAL_STOP_TIMEOUT),
          harness.engine.unrestoredForegroundLeaseFailure());
      assertFalse(harness.engine.isLoaded());
      assertFalse(harness.output.toString(StandardCharsets.UTF_8).contains("analyze"));

      installInput(harness.engine, "");
      assertEquals(
          java.util.Optional.empty(),
          harness.engine.unrestoredForegroundLeaseFailure(),
          "a new reader of the same engine object must not inherit the old failure");
    } finally {
      harness.close();
    }
  }

  @Test
  void unrestoredReaderIsNotInheritedByANewPrimarySelection() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false, true);
    AtomicInteger failures = new AtomicInteger();
    try {
      harness.engine.releaseStopTimeoutMillis = 25L;
      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet, failures::incrementAndGet));
      waitUntil(() -> failures.get() == 1);
      drainEdt();
      assertTrue(harness.engine.unrestoredForegroundLeaseFailure().isPresent());

      Lizzie.setPrimaryEngine(harness.engine);

      assertEquals(java.util.Optional.empty(), harness.engine.unrestoredForegroundLeaseFailure());
    } finally {
      harness.close();
    }
  }

  @Test
  void lateFailureOfReplacedPrimaryIsNeverPublished() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false, true);
    AtomicInteger failures = new AtomicInteger();
    CountDownLatch edtBlocked = new CountDownLatch(1);
    CountDownLatch releaseEdt = new CountDownLatch(1);
    try {
      RecordingRestoreLeelaz successor = recordingRestoreEngine();
      harness.engine.releaseStopTimeoutMillis = 25L;
      javax.swing.SwingUtilities.invokeLater(
          () -> {
            edtBlocked.countDown();
            await(releaseEdt);
          });
      await(edtBlocked);
      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet, failures::incrementAndGet));
      waitUntil(() -> failures.get() == 1);
      Lizzie.leelaz = successor;
      releaseEdt.countDown();
      drainEdt();

      assertEquals(java.util.Optional.empty(), successor.unrestoredForegroundLeaseFailure());
      Lizzie.leelaz = harness.engine;
      assertEquals(
          java.util.Optional.empty(),
          harness.engine.unrestoredForegroundLeaseFailure(),
          "a failure settled after replacement must not be presented on a later selection");
    } finally {
      releaseEdt.countDown();
      harness.close();
    }
  }

  @Test
  void onlyFailedAutomaticLeaseReleasesRecordAnUnrestoredReader() throws Exception {
    RestoreHarness manual = RestoreHarness.open(false, false);
    AtomicInteger failures = new AtomicInteger();
    try {
      manual.engine.releaseStopTimeoutMillis = 25L;
      assertTrue(
          manual.engine.endForegroundAnalysisLease(
              manual.owner, manual.completions::incrementAndGet, failures::incrementAndGet));
      waitUntil(() -> failures.get() == 1);
      drainEdt();
      assertFalse(manual.engine.isLoaded());
      assertEquals(java.util.Optional.empty(), manual.engine.unrestoredForegroundLeaseFailure());
    } finally {
      manual.close();
    }

    RestoreHarness automatic = RestoreHarness.open(false, false, true);
    try {
      automatic.finishRestore();
      drainEdt();
      assertTrue(automatic.engine.isLoaded());
      assertEquals(java.util.Optional.empty(), automatic.engine.unrestoredForegroundLeaseFailure());
    } finally {
      automatic.close();
    }
  }

  @Test
  void automaticLeaseRestoreCommandRejectionKeepsCurrentReaderVisiblyUnrestored() throws Exception {
    try (ProductionForegroundRestoreHarness harness =
        ProductionForegroundRestoreHarness.open(true, "loadsgf ")) {
      assertTrue(
          harness.lease.release(
              harness.completions::incrementAndGet, harness.failures::incrementAndGet));
      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> harness.failures.get() == 1);
      drainEdt();

      assertTrue(harness.output.rejected, "the restore must reach the rejected command");
      assertEquals(
          java.util.Optional.of(Leelaz.ForegroundAnalysisLeaseFailure.RESTORE_FAILED),
          harness.engine.unrestoredForegroundLeaseFailure());
      assertFalse(harness.engine.isLoaded());
      assertEquals(0, harness.engine.ponderCount);
      assertEquals(0, harness.completions.get());
    }
  }

  @Test
  void foregroundLeaseReleaseStopErrorLeavesEngineUnavailable() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    AtomicInteger failures = new AtomicInteger();
    try {
      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet, failures::incrementAndGet));
      assertTrue(dispatch(harness.engine, "?800000001 cannot stop"));
      waitUntil(() -> failures.get() == 1);

      assertFalse(harness.engine.isLoaded());
      assertEquals(0, harness.board.resendCount);
      assertEquals(0, harness.completions.get());
      assertEquals(1, failures.get());
      assertEquals(
          java.util.Optional.of(Leelaz.ForegroundAnalysisLeaseFailure.FINAL_STOP_ERROR_RESPONSE),
          harness.owner.failureReason());
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseReleaseWrongIdTimesOutAndIgnoresLateSuccess() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    AtomicInteger failures = new AtomicInteger();
    try {
      harness.engine.releaseStopTimeoutMillis = 25L;
      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet, failures::incrementAndGet));

      assertTrue(dispatch(harness.engine, "=800000099"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> failures.get() == 1);

      assertFalse(dispatch(harness.engine, "=800000001"));
      assertFalse(dispatch(harness.engine, ""));
      assertFalse(harness.engine.isLoaded());
      assertEquals(0, harness.board.resendCount);
      assertEquals(0, harness.completions.get());
      assertEquals(1, failures.get());
      assertEquals(
          java.util.Optional.of(Leelaz.ForegroundAnalysisLeaseFailure.FINAL_STOP_TIMEOUT),
          harness.owner.failureReason());
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseReleaseSendFailureLeavesEngineUnavailableAndReportsOnce() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    AtomicInteger failures = new AtomicInteger();
    try {
      installFailOnceOutput(harness.engine);

      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet, failures::incrementAndGet));
      waitUntil(() -> failures.get() == 1);

      assertFalse(harness.engine.isLoaded());
      assertEquals(0, harness.board.resendCount);
      assertEquals(0, harness.completions.get());
      assertEquals(1, failures.get());
      assertEquals(
          java.util.Optional.of(Leelaz.ForegroundAnalysisLeaseFailure.FINAL_STOP_SEND_FAILED),
          harness.owner.failureReason());
      assertFalse(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet, failures::incrementAndGet));
      assertEquals(1, failures.get());
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseReleaseEofReportsFailureOnceAndIgnoresLateResponse() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    AtomicInteger failures = new AtomicInteger();
    try {
      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet, failures::incrementAndGet));
      Lizzie.frame = null;
      harness.engine.isNormalEnd = true;

      invokeRead(harness.engine);
      waitUntil(() -> failures.get() == 1);

      assertEquals(0, harness.completions.get());
      assertEquals(1, failures.get());
      assertFalse(dispatch(harness.engine, "=800000001"));
      assertFalse(dispatch(harness.engine, ""));
      assertEquals(1, failures.get());
      assertEquals(
          java.util.Optional.of(Leelaz.ForegroundAnalysisLeaseFailure.TRANSPORT_CLOSED),
          harness.owner.failureReason());
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundRestoreFailureDoesNotRunSuccessCompletion() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    try {
      harness.board.failResend = true;
      Lizzie.frame = null;

      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet));
      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> !harness.engine.isLoaded());

      assertEquals(0, harness.completions.get());
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundRestoreWriteFailureRunsFailureCleanupInsteadOfSuccess() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    AtomicInteger failures = new AtomicInteger();
    try {
      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet, failures::incrementAndGet));
      installFailOnceOutput(harness.engine);
      Lizzie.frame = null;

      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> failures.get() == 1);

      assertFalse(harness.engine.isLoaded());
      assertEquals(0, harness.completions.get());
      assertFalse(harness.engine.hasExclusiveGtpWorkInProgress());
      assertEquals(
          java.util.Optional.of(Leelaz.ForegroundAnalysisLeaseFailure.RESTORE_FAILED),
          harness.owner.failureReason());
    } finally {
      harness.close();
    }
  }

  @Test
  void exclusiveLineConsumerFailureCannotEscapeReadCleanup() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz engine = reusableKatagoEngine(false, false);
    installInput(engine, "info move D4 visits 1\n");
    installOutput(engine);
    AtomicInteger consumerCalls = new AtomicInteger();
    try {
      Lizzie.leelaz = engine;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginForegroundAnalysisLease(
              new Object(),
              line -> {
                consumerCalls.incrementAndGet();
                throw new IllegalStateException("simulated parser failure");
              },
              () -> {},
              () -> {}));
      dispatch(engine, "=800000000");
      assertTrue(dispatch(engine, ""));
      Lizzie.leelaz = null;
      engine.isNormalEnd = true;

      assertDoesNotThrow(() -> invokeRead(engine));

      assertEquals(1, consumerCalls.get());
      assertFalse(engine.hasExclusiveGtpLease());
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
      assertFalse(engine.isStarted());
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void foregroundLeaseDoesNotResumePonderWhenItWasNotPreviouslyPondering() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    try {
      harness.finishRestore();

      assertEquals(1, harness.board.resendCount);
      assertEquals(0, harness.engine.ponderCount);
      assertEquals(1, harness.completions.get());
    } finally {
      harness.close();
    }
  }

  @Test
  void analysisControlPauseKeepsSharedLeaseRestoreFromResumingPonder() throws Exception {
    RestoreHarness harness = RestoreHarness.open(true, false);
    try {
      Method pause = LizzieFrame.class.getDeclaredMethod("pauseFromAnalysisControl");
      pause.setAccessible(true);
      pause.invoke(Lizzie.frame);

      harness.finishRestore();

      assertEquals(
          0,
          harness.engine.ponderCount,
          "analysis-control pause must keep ExclusiveGtp restore from sending ponder.");
    } finally {
      harness.close();
    }
  }

  @Test
  void failedSharedLeaseRestoreAfterRapidResumeDoesNotPonder() throws Exception {
    RestoreHarness harness = RestoreHarness.open(true, false);
    AtomicInteger failures = new AtomicInteger();
    try {
      Field paused = LizzieFrame.class.getDeclaredField("userAnalysisPaused");
      paused.setAccessible(true);
      paused.setBoolean(Lizzie.frame, true);
      Field cleanup = LizzieFrame.class.getDeclaredField("analysisControlCleanupInProgress");
      cleanup.setAccessible(true);
      cleanup.setBoolean(Lizzie.frame, true);
      Field cleanupGeneration =
          LizzieFrame.class.getDeclaredField("analysisControlCleanupGeneration");
      cleanupGeneration.setAccessible(true);
      cleanupGeneration.setLong(Lizzie.frame, 7L);

      Method resume = LizzieFrame.class.getDeclaredMethod("resumeFromAnalysisControl");
      resume.setAccessible(true);
      resume.invoke(Lizzie.frame);
      assertFalse(Lizzie.frame.isUserAnalysisPaused());

      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner,
              harness.completions::incrementAndGet,
              () -> {
                failures.incrementAndGet();
                try {
                  Method finish =
                      LizzieFrame.class.getDeclaredMethod(
                          "finishUserAnalysisPauseCleanup", long.class, boolean.class);
                  finish.setAccessible(true);
                  finish.invoke(Lizzie.frame, 7L, false);
                } catch (ReflectiveOperationException failure) {
                  throw new AssertionError(failure);
                }
              }));
      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> harness.board.resendCount == 1);

      Field sessionField = Leelaz.class.getDeclaredField("foregroundRestoreSession");
      sessionField.setAccessible(true);
      Object session = sessionField.get(harness.engine);
      Field releaseStopFailed = session.getClass().getDeclaredField("releaseStopFailed");
      releaseStopFailed.setAccessible(true);
      releaseStopFailed.setBoolean(session, true);
      completeForegroundRestore(harness.engine);

      assertEquals(0, harness.engine.ponderCount);
      assertFalse(harness.engine.isPondering());
      assertEquals(0, harness.completions.get());
      assertEquals(1, failures.get());
      assertTrue(Lizzie.frame.isUserAnalysisPaused());
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseDoesNotRestoreOrPonderAfterForegroundEngineSwitch() throws Exception {
    RestoreHarness harness = RestoreHarness.open(true, false);
    try {
      Lizzie.leelaz = new Leelaz("");
      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet));
      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));

      assertEquals(0, harness.board.resendCount);
      assertEquals(0, harness.engine.ponderCount);
      assertEquals(1, harness.completions.get());
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseRestoresBoardButDoesNotResumePonderAfterEnteringPlayMode() throws Exception {
    RestoreHarness harness = RestoreHarness.open(true, true);
    try {
      harness.finishRestore();

      assertEquals(1, harness.board.resendCount);
      assertEquals(0, harness.engine.ponderCount);
      assertEquals(1, harness.completions.get());
    } finally {
      harness.close();
    }
  }

  private static Leelaz reusableKatagoEngine(boolean remoteCompute, boolean javaSsh)
      throws Exception {
    Leelaz engine = new Leelaz("");
    engine.useRemoteCompute = remoteCompute;
    engine.useJavaSSH = javaSsh;
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
    setCapabilityDiscoveryComplete(engine, true);
    createdEngines.add(engine);
    return engine;
  }

  private static RecordingRestoreLeelaz recordingRestoreEngine() throws Exception {
    RecordingRestoreLeelaz engine = new RecordingRestoreLeelaz();
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
    setCapabilityDiscoveryComplete(engine, true);
    createdEngines.add(engine);
    return engine;
  }

  private static void setCapabilityDiscoveryComplete(Leelaz engine, boolean complete)
      throws Exception {
    Field field = Leelaz.class.getDeclaredField("endGetCommandList");
    field.setAccessible(true);
    field.set(engine, complete);
  }

  private static ByteArrayOutputStream installOutput(Leelaz engine) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    engine.installCommandOutputForTest(new BufferedOutputStream(bytes));
    return bytes;
  }

  private static void installOutput(Leelaz engine, BufferedOutputStream output) throws Exception {
    engine.installCommandOutputForTest(output);
  }

  private static BufferedOutputStream commandOutputStream(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("outputStream");
    field.setAccessible(true);
    return (BufferedOutputStream) field.get(engine);
  }

  private static ArrayDeque<?> commandQueue(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("cmdQueue");
    field.setAccessible(true);
    return (ArrayDeque<?>) field.get(engine);
  }

  private static Thread daemonThread(String name, Runnable action) {
    Thread thread = new Thread(action, name);
    thread.setDaemon(true);
    return thread;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(1, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for controlled concurrency barrier");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError(ex);
    }
  }

  private static void waitForState(Thread thread, Thread.State expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (thread.getState() != expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(expected, thread.getState(), "controlled thread did not reach expected state");
  }

  private static void invokeSendCommandWithResponse(
      Leelaz engine, String command, Runnable onResponse) {
    try {
      Method method = Leelaz.class.getDeclaredMethod("sendCommand", String.class, Runnable.class);
      method.setAccessible(true);
      method.invoke(engine, command, onResponse);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError(ex);
    }
  }

  private static void installFailOnceOutput(Leelaz engine) throws Exception {
    engine.installCommandOutputForTest(
        new OutputStream() {
          private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          private boolean failed;

          @Override
          public void write(int value) throws IOException {
            if (!failed) {
              failed = true;
              throw new IOException("simulated restore write failure");
            }
            bytes.write(value);
          }

          @Override
          public void write(byte[] buffer, int offset, int length) throws IOException {
            if (!failed) {
              failed = true;
              throw new IOException("simulated restore write failure");
            }
            bytes.write(buffer, offset, length);
          }
        });
  }

  private static void installInput(Leelaz engine, String input) throws Exception {
    installInput(engine, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
  }

  private static void installInput(Leelaz engine, InputStream input) throws Exception {
    List<String> commands = List.copyOf(engine.commandLists);
    engine.installFreshCommandStreamsForTest(
        input, new ByteArrayOutputStream(), new ByteArrayInputStream(new byte[0]));
    engine.advertiseCommandsForTest(commands);
  }

  private static void invokeRead(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("read");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static boolean dispatch(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("dispatchExclusiveGtpLine", String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(engine, line);
  }

  private static void processCommandResponse(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("processCommandResponseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void invokeResponseHandlerForLine(Leelaz engine, String line) throws Exception {
    Method method =
        Leelaz.class.getDeclaredMethod("runPendingResponseHandlerForLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void closeExclusiveSessionForTest(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("exclusiveGtpSession");
    field.setAccessible(true);
    Object session = field.get(engine);
    if (session == null) {
      return;
    }
    Method cancelInitial =
        Leelaz.class.getDeclaredMethod("cancelExclusiveGtpInitialStopTimeout", session.getClass());
    cancelInitial.setAccessible(true);
    cancelInitial.invoke(engine, session);
    Method cancelRelease =
        Leelaz.class.getDeclaredMethod("cancelExclusiveGtpReleaseStopTimeout", session.getClass());
    cancelRelease.setAccessible(true);
    cancelRelease.invoke(engine, session);
    Method close = Leelaz.class.getDeclaredMethod("closeExclusiveGtpSession", session.getClass());
    close.setAccessible(true);
    close.invoke(engine, session);
  }

  private static void completeForegroundRestore(Leelaz engine) throws Exception {
    Field sessionField = Leelaz.class.getDeclaredField("foregroundRestoreSession");
    sessionField.setAccessible(true);
    Object session = sessionField.get(engine);
    Method method = Leelaz.class.getDeclaredMethod("completeForegroundRestore", session.getClass());
    method.setAccessible(true);
    method.invoke(engine, session);
  }

  private static void drainEdt() throws Exception {
    javax.swing.SwingUtilities.invokeAndWait(() -> {});
  }

  private static void waitUntil(Check condition) throws Exception {
    long deadline = System.currentTimeMillis() + 3000L;
    while (!condition.get() && System.currentTimeMillis() < deadline) {
      Thread.sleep(10L);
    }
    assertTrue(condition.get(), "timed out waiting for foreground restore lifecycle");
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  @FunctionalInterface
  private interface Check {
    boolean get() throws Exception;
  }

  private static final class RecordingRestoreBoard extends Board {
    private volatile int currentMarker;
    private volatile int restoredMarker;
    private volatile int resendCount;
    private volatile boolean failResend;
    private BoardHistoryList restoreHistory;
    private boolean restorePlanCaptured;
    private int frozenMarker;

    private RecordingRestoreBoard() {
      super();
    }

    @Override
    public void resendMoveToEngine(Leelaz leelaz, boolean loadEngine) {
      if (failResend) {
        throw new IllegalStateException("simulated restore failure");
      }
      restoredMarker = currentMarker;
      resendCount++;
    }

    @Override
    public void resendMoveToEngineFromCurrentRoot(Leelaz leelaz) {
      resendMoveToEngine(leelaz, false);
    }

    @Override
    public void resendMoveToEngine(
        Leelaz leelaz,
        boolean loadEngine,
        ExactSnapshotEngineRestore.PreparedRestore preparedRestore) {
      if (failResend) {
        throw new IllegalStateException("simulated restore failure");
      }
      restoredMarker = frozenMarker;
      resendCount++;
      preparedRestore.execute();
    }

    @Override
    public void resendMoveToEngineFromRoot(
        Leelaz engine,
        Leelaz mirrorEngine,
        boolean loadEngine,
        boolean isEngineGame,
        ArrayList<Movelist> moves,
        Double gameKomi) {
      resendMoveToEngine(engine, loadEngine);
      if (mirrorEngine != null) {
        resendMoveToEngine(mirrorEngine, loadEngine);
      }
    }

    @Override
    public BoardHistoryList getHistory() {
      if (!restorePlanCaptured || frozenMarker != currentMarker) {
        restorePlanCaptured = true;
        frozenMarker = currentMarker;
      }
      return restoreHistory;
    }

    @Override
    public ArrayList<Movelist> getMoveList() {
      return new ArrayList<>();
    }

    @Override
    public long getContextRevision() {
      return currentMarker;
    }
  }

  private static final class BlockingFailOnceOutput extends OutputStream {
    private final CountDownLatch writeStarted = new CountDownLatch(1);
    private final CountDownLatch failWrite = new CountDownLatch(1);
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private boolean failed;

    @Override
    public synchronized void write(int value) throws IOException {
      if (!failed) {
        writeStarted.countDown();
        await(failWrite);
        failed = true;
        throw new IOException("simulated command failure");
      }
      output.write(value);
    }

    private synchronized String output() {
      return output.toString(StandardCharsets.UTF_8);
    }
  }

  private static final class ProductionBoundaryRestoreLeelaz extends Leelaz {
    private volatile int ponderCount;

    private ProductionBoundaryRestoreLeelaz() throws Exception {
      super("");
      isLoaded = true;
      started = true;
      isKatago = true;
      commandLists.addAll(
          List.of(
              "stop",
              "boardsize",
              "rectangular_boardsize",
              "komi",
              "kata-get-rules",
              "kata-set-rules",
              "clear_board",
              "play",
              "set_position",
              "kata-analyze"));
      setCapabilityDiscoveryComplete(this, true);
    }

    @Override
    public void ponder() {
      ponderCount++;
      Pondering();
    }
  }

  private static final class AutoRespondingRestoreOutput extends OutputStream {
    private final Leelaz engine;
    private final StringBuilder currentCommand = new StringBuilder();
    private final CountDownLatch nameCommandWritten = new CountDownLatch(1);
    private volatile String loadedSgf = "";
    private final String rejectedCommand;
    private volatile boolean rejected;

    private AutoRespondingRestoreOutput(Leelaz engine, String rejectedCommand) {
      this.engine = engine;
      this.rejectedCommand = rejectedCommand;
    }

    @Override
    public synchronized void write(int value) {
      currentCommand.append((char) value);
    }

    @Override
    public synchronized void flush() throws IOException {
      String command = currentCommand.toString().trim();
      currentCommand.setLength(0);
      if (command.isEmpty() || command.endsWith(" stop")) {
        return;
      }
      if ("name".equals(command)) {
        nameCommandWritten.countDown();
        return;
      }
      String response = "=";
      int commandSeparator = command.indexOf(' ');
      if (commandSeparator > 0
          && command.substring(0, commandSeparator).chars().allMatch(Character::isDigit)) {
        response += command.substring(0, commandSeparator);
      }
      int loadSgf = command.indexOf("loadsgf ");
      if (loadSgf >= 0) {
        try {
          loadedSgf = Files.readString(Path.of(command.substring(loadSgf + 8).trim()));
        } catch (Exception ex) {
          throw new IOException("Failed to consume exact restore SGF", ex);
        }
      }
      if (command.contains("kata-get-rules")) {
        response += " " + KataGoRules.parse("chinese").orElseThrow().toGtpArgument();
      }
      if (rejectedCommand != null && command.contains(rejectedCommand)) {
        rejected = true;
        response = "?" + response.substring(1) + " injected restore rejection";
      }
      try {
        if (command.contains("kata-set-rules")
            || command.contains("kata-set-param")
            || command.contains("kata-get-rules")
            || command.contains("kata-time_settings")) {
          String reply = response;
          Thread reader =
              new Thread(
                  () -> {
                    try {
                      Thread.sleep(20);
                      processCommandResponse(engine, reply);
                      processCommandResponse(engine, "");
                    } catch (Exception failure) {
                      throw new AssertionError(failure);
                    }
                  },
                  "fox-restore-test-reader");
          reader.setDaemon(true);
          reader.start();
        } else {
          invokeResponseHandlerForLine(engine, response);
        }
      } catch (Exception ex) {
        throw new IOException("Failed to settle test GTP response for " + command, ex);
      }
    }

    private String loadedSgf() {
      return loadedSgf;
    }
  }

  private static final class RecordingRestoreLeelaz extends Leelaz {
    private volatile int ponderCount;
    private volatile boolean lifecycleBusyDuringPonder;
    private volatile long initialStopTimeoutMillis = 8000L;
    private volatile long releaseStopTimeoutMillis = 8000L;
    private volatile CountDownLatch releaseBoundaryReached;
    private volatile CountDownLatch continueReleaseBoundary;
    private volatile CountDownLatch releaseTimeoutStarted;
    private volatile CountDownLatch continueReleaseTimeout;
    private volatile CountDownLatch releaseTimeoutCompleted;
    private volatile CountDownLatch lifecycleReleaseReached;
    private volatile CountDownLatch continueLifecycleRelease;

    private RecordingRestoreLeelaz() throws Exception {
      super("");
      ExactSnapshotRestoreProtocolFixture.install(
          this, command -> ExactSnapshotRestoreProtocolFixture.Response.success());
    }

    @Override
    public void ponder() {
      ponderCount++;
      lifecycleBusyDuringPonder = hasExclusiveGtpWorkInProgress();
      Pondering();
    }

    @Override
    protected long foregroundInitialStopTimeoutMillis() {
      return initialStopTimeoutMillis;
    }

    @Override
    protected long foregroundReleaseStopTimeoutMillis() {
      return releaseStopTimeoutMillis;
    }

    @Override
    protected void executeForegroundReleaseStopTimeout(Runnable timeoutAction) {
      CountDownLatch started = releaseTimeoutStarted;
      CountDownLatch proceed = continueReleaseTimeout;
      CountDownLatch completed = releaseTimeoutCompleted;
      if (started == null || proceed == null || completed == null) {
        timeoutAction.run();
        return;
      }
      started.countDown();
      await(proceed);
      timeoutAction.run();
      completed.countDown();
    }

    @Override
    protected void beforeForegroundReleaseRestoreAfterBoundary() {
      CountDownLatch reached = releaseBoundaryReached;
      CountDownLatch proceed = continueReleaseBoundary;
      if (reached == null || proceed == null) {
        return;
      }
      reached.countDown();
      await(proceed);
    }

    @Override
    protected void beforeForegroundRestoreLifecycleRelease() {
      CountDownLatch reached = lifecycleReleaseReached;
      CountDownLatch proceed = continueLifecycleRelease;
      if (reached == null || proceed == null) {
        return;
      }
      reached.countDown();
      await(proceed);
    }

    private void pauseLifecycleRelease() {
      lifecycleReleaseReached = new CountDownLatch(1);
      continueLifecycleRelease = new CountDownLatch(1);
    }

    private void resumeLifecycleRelease() {
      if (continueLifecycleRelease != null) {
        continueLifecycleRelease.countDown();
      }
    }

    private void pauseReleaseBoundaryAndTimeout() {
      releaseBoundaryReached = new CountDownLatch(1);
      continueReleaseBoundary = new CountDownLatch(1);
      releaseTimeoutStarted = new CountDownLatch(1);
      continueReleaseTimeout = new CountDownLatch(1);
      releaseTimeoutCompleted = new CountDownLatch(1);
    }

    private void resumeReleaseBoundaryAndTimeout() {
      if (continueReleaseTimeout != null) {
        continueReleaseTimeout.countDown();
      }
      if (continueReleaseBoundary != null) {
        continueReleaseBoundary.countDown();
      }
    }
  }

  private static final class ForegroundLeaseGlobalState implements AutoCloseable {
    private final Leelaz previousEngine;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final Config previousConfig;
    private final GtpConsolePane previousGtpConsole;

    private ForegroundLeaseGlobalState(
        Leelaz previousEngine,
        Board previousBoard,
        LizzieFrame previousFrame,
        Config previousConfig,
        GtpConsolePane previousGtpConsole) {
      this.previousEngine = previousEngine;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousConfig = previousConfig;
      this.previousGtpConsole = previousGtpConsole;
    }

    private static ForegroundLeaseGlobalState install(Leelaz engine) {
      ForegroundLeaseGlobalState state =
          new ForegroundLeaseGlobalState(
              Lizzie.leelaz, Lizzie.board, Lizzie.frame, Lizzie.config, Lizzie.gtpConsole);
      Lizzie.leelaz = engine;
      Lizzie.board = null;
      Lizzie.frame = null;
      return state;
    }

    private static ForegroundLeaseGlobalState installForReader(Leelaz engine) throws Exception {
      Config config = allocate(Config.class);
      GtpConsolePane gtpConsole = allocate(SilentGtpConsole.class);
      ForegroundLeaseGlobalState state = install(engine);
      Lizzie.config = config;
      Lizzie.gtpConsole = gtpConsole;
      return state;
    }

    @Override
    public void close() {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
    }
  }

  private static final class ProductionForegroundRestoreHarness implements AutoCloseable {
    private final ForegroundLeaseGlobalState globalState;
    private final ProductionBoundaryRestoreLeelaz engine;
    private final AutoRespondingRestoreOutput output;
    private final Leelaz.ForegroundAnalysisLease lease;
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final AtomicInteger completions = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();

    private ProductionForegroundRestoreHarness(
        ForegroundLeaseGlobalState globalState,
        ProductionBoundaryRestoreLeelaz engine,
        AutoRespondingRestoreOutput output,
        Leelaz.ForegroundAnalysisLease lease,
        int previousBoardWidth,
        int previousBoardHeight) {
      this.globalState = globalState;
      this.engine = engine;
      this.output = output;
      this.lease = lease;
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
    }

    private static ProductionForegroundRestoreHarness open() throws Exception {
      return open(false, null);
    }

    private static ProductionForegroundRestoreHarness open(
        boolean automatic, String rejectedCommand) throws Exception {
      ProductionBoundaryRestoreLeelaz engine = new ProductionBoundaryRestoreLeelaz();
      ForegroundLeaseGlobalState globalState = ForegroundLeaseGlobalState.install(engine);
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      try {
        Config config = allocate(Config.class);
        config.extraMode = ExtraMode.Normal;
        config.alwaysGtp = false;
        Lizzie.config = config;
        Lizzie.frame = allocate(LizzieFrame.class);
        Field listeners = LizzieFrame.class.getDeclaredField("problemListListeners");
        listeners.setAccessible(true);
        listeners.set(Lizzie.frame, new ArrayList<>());
        Lizzie.gtpConsole = allocate(SilentGtpConsole.class);

        BoardData snapshot = BoardData.empty(13, 9);
        snapshot.stones[0] = Stone.BLACK;
        snapshot.addProperty("SZ", "13:9");
        BoardHistoryList history = new BoardHistoryList(snapshot);
        history.getGameInfo().setKomiNoMenu(6.5);
        Board board = allocate(Board.class);
        board.startStonelist = new ArrayList<>();
        board.hasStartStone = false;
        board.setHistory(history);
        Lizzie.board = board;

        engine.komi = 7.5f;
        engine.width = 19;
        engine.height = 19;
        AutoRespondingRestoreOutput output =
            new AutoRespondingRestoreOutput(engine, rejectedCommand);
        installOutput(engine, Leelaz.createCommandOutputStream(output));
        engine.Pondering();
        Leelaz.ForegroundAnalysisLeaseAcquisition acquisition =
            engine.acquireForegroundAnalysisLease(line -> {}, owner -> {}, owner -> {}, !automatic);
        assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, acquisition.availability());
        dispatch(engine, "=800000000");
        assertTrue(dispatch(engine, ""));
        return new ProductionForegroundRestoreHarness(
            globalState,
            engine,
            output,
            acquisition.lease(),
            previousBoardWidth,
            previousBoardHeight);
      } catch (Throwable failure) {
        globalState.close();
        throw failure;
      }
    }

    private void releaseThroughNameCommand() throws Exception {
      assertTrue(lease.release(completions::incrementAndGet, () -> {}));
      assertTrue(dispatch(engine, "=800000001"));
      assertTrue(dispatch(engine, ""));
      assertTrue(
          output.nameCommandWritten.await(2, TimeUnit.SECONDS),
          "foreground restore must reach the final name command");
    }

    private void completeNameResponse() throws Exception {
      invokeResponseHandlerForLine(engine, "=");
      waitUntil(() -> completions.get() == 1);
    }

    @Override
    public void close() {
      try {
        globalState.close();
      } finally {
        Board.boardWidth = previousBoardWidth;
        Board.boardHeight = previousBoardHeight;
        Zobrist.init();
      }
    }
  }

  private static final class RestoreHarness implements AutoCloseable {
    private final Leelaz previousEngine;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final Config previousConfig;
    private final GtpConsolePane previousGtpConsole;
    private final RecordingRestoreLeelaz engine;
    private final RecordingRestoreBoard board;
    private final ByteArrayOutputStream output;
    private final Leelaz.ForegroundAnalysisLease owner;
    private final AtomicInteger completions = new AtomicInteger();

    private RestoreHarness(
        Leelaz previousEngine,
        Board previousBoard,
        LizzieFrame previousFrame,
        Config previousConfig,
        GtpConsolePane previousGtpConsole,
        RecordingRestoreLeelaz engine,
        RecordingRestoreBoard board,
        ByteArrayOutputStream output,
        Leelaz.ForegroundAnalysisLease owner) {
      this.previousEngine = previousEngine;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousConfig = previousConfig;
      this.previousGtpConsole = previousGtpConsole;
      this.engine = engine;
      this.board = board;
      this.output = output;
      this.owner = owner;
    }

    private static RestoreHarness open(boolean wasPondering, boolean enterPlayMode)
        throws Exception {
      return open(wasPondering, enterPlayMode, false);
    }

    private static RestoreHarness open(
        boolean wasPondering, boolean enterPlayMode, boolean automatic) throws Exception {
      Leelaz previousEngine = Lizzie.leelaz;
      Board previousBoard = Lizzie.board;
      LizzieFrame previousFrame = Lizzie.frame;
      Config previousConfig = Lizzie.config;
      GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
      RecordingRestoreLeelaz engine = recordingRestoreEngine();
      installInput(engine, "");
      RecordingRestoreBoard board = allocate(RecordingRestoreBoard.class);
      BoardData snapshot = BoardData.empty(19, 19);
      snapshot.stones[0] = Stone.BLACK;
      board.restoreHistory = new BoardHistoryList(snapshot);
      ByteArrayOutputStream output = installOutput(engine);
      Lizzie.leelaz = engine;
      Lizzie.board = board;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      if (wasPondering) {
        engine.Pondering();
      }
      Leelaz.ForegroundAnalysisLeaseAcquisition acquisition =
          engine.acquireForegroundAnalysisLease(line -> {}, lease -> {}, lease -> {}, !automatic);
      assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, acquisition.availability());
      Leelaz.ForegroundAnalysisLease owner = acquisition.lease();
      dispatch(engine, "=800000000");
      assertTrue(dispatch(engine, ""));
      if (enterPlayMode) {
        Lizzie.frame.isPlayingAgainstLeelaz = true;
      }
      return new RestoreHarness(
          previousEngine,
          previousBoard,
          previousFrame,
          previousConfig,
          previousGtpConsole,
          engine,
          board,
          output,
          owner);
    }

    private void finishRestore() throws Exception {
      assertTrue(engine.endForegroundAnalysisLease(owner, completions::incrementAndGet));
      assertTrue(dispatch(engine, "=800000001"));
      assertTrue(dispatch(engine, ""));
      waitUntil(() -> board.resendCount == 1);
      completeForegroundRestore(engine);
      waitUntil(() -> completions.get() == 1);
    }

    @Override
    public void close() {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
    }
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super(null);
    }

    @Override
    public boolean isVisible() {
      return false;
    }

    @Override
    public void addLine(String line) {}
  }

  private static final class NoArgLifecycleReservationLeelaz extends Leelaz {
    private NoArgLifecycleReservationLeelaz() throws Exception {
      super("");
    }

    @Override
    public ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation() {
      return null;
    }
  }
}
