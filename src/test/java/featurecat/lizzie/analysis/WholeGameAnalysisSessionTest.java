package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WholeGameAnalysisSessionTest {
  private static final int BOARD_SIZE = 3;

  @Test
  void engineFailureTerminatesInsteadOfReusingAPotentiallyDirtyTransport() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      SessionFixture fixture = SessionFixture.create();
      fixture.engine.requestCount = 1;
      setField(fixture.session, "state", WholeGameAnalysisSession.State.BASELINE);
      setField(fixture.session, "engine", fixture.engine);
      setField(fixture.session, "activeDispatchGeneration", 1);

      invokeEngineFailure(fixture.session, 1);

      assertEquals(WholeGameAnalysisSession.State.FAILED, fixture.session.state());
      assertEquals(1, fixture.engine.requestCount);
      assertTrue(fixture.engine.shutdownRequested);
      assertTrue(fixture.engine.callbacksCleared);
      assertTrue(fixture.engine.quitCalled.await(2, TimeUnit.SECONDS));
      drainEdt();
    }
  }

  @Test
  void staleEngineFailureCannotTerminateANewerDispatch() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      SessionFixture fixture = SessionFixture.create();
      setField(fixture.session, "state", WholeGameAnalysisSession.State.DEEP);
      setField(fixture.session, "engine", fixture.engine);
      setField(fixture.session, "activeDispatchGeneration", 2);

      invokeEngineFailure(fixture.session, 1);

      assertEquals(WholeGameAnalysisSession.State.DEEP, fixture.session.state());
      assertFalse(fixture.engine.shutdownRequested);
      assertFalse(fixture.engine.callbacksCleared);
    }
  }

  @Test
  void cancelMarksTheEngineShutdownBeforeTheAsyncCloserRuns() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      SessionFixture fixture = SessionFixture.create();
      setField(fixture.session, "state", WholeGameAnalysisSession.State.BASELINE);
      setField(fixture.session, "engine", fixture.engine);
      setField(fixture.session, "activeDispatchGeneration", 1);

      fixture.session.cancel();

      assertEquals(WholeGameAnalysisSession.State.CANCELLED, fixture.session.state());
      assertTrue(fixture.engine.shutdownRequested);
      assertTrue(fixture.engine.callbacksCleared);
      assertTrue(fixture.engine.quitCalled.await(2, TimeUnit.SECONDS));
      drainEdt();
    }
  }

  @Test
  void pauseClosesTheActiveEngineAndKeepsTheSessionResumable() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      SessionFixture fixture = SessionFixture.create();
      setField(fixture.session, "state", WholeGameAnalysisSession.State.BASELINE);
      setField(fixture.session, "engine", fixture.engine);
      setField(fixture.session, "activeDispatchGeneration", 1);

      fixture.session.pause();

      assertTrue(fixture.engine.shutdownRequested);
      assertTrue(fixture.engine.callbacksCleared);
      assertTrue(fixture.engine.quitCalled.await(2, TimeUnit.SECONDS));
      waitForState(fixture.session, WholeGameAnalysisSession.State.PAUSED);
      assertTrue(fixture.session.isActive());
      assertTrue(fixture.session.isPaused());
      assertFalse(fixture.session.isTerminal());
    }
  }

  @Test
  void pauseWhilePreparingInvalidatesThePendingEngineStart() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      SessionFixture fixture = SessionFixture.create();
      setField(fixture.session, "state", WholeGameAnalysisSession.State.PREPARING);
      setField(fixture.session, "engineStartGeneration", 3);

      fixture.session.pause();

      assertEquals(WholeGameAnalysisSession.State.PAUSED, fixture.session.state());
      assertEquals(4, getIntField(fixture.session, "engineStartGeneration"));
    }
  }

  @Test
  void engineCreatedBeforePauseCannotAttachAfterTheSessionIsPaused() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      SessionFixture fixture = SessionFixture.create();
      setField(fixture.session, "state", WholeGameAnalysisSession.State.PREPARING);
      setField(fixture.session, "engineStartGeneration", 3);

      fixture.session.pause();
      invokeAcceptEngine(
          fixture.session, fixture.engine, 3, WholeGameAnalysisSession.State.BASELINE);

      assertTrue(fixture.engine.quitCalled.await(2, TimeUnit.SECONDS));
      assertEquals(WholeGameAnalysisSession.State.PAUSED, fixture.session.state());
      assertFalse(fixture.session.isTerminal());
    }
  }

  @Test
  void resumeCreatesAFreshEngineAndContinuesTheInterruptedStage() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      AtomicInteger creations = new AtomicInteger();
      SessionFixture fixture =
          SessionFixture.createWithFactory(
              () -> {
                creations.incrementAndGet();
                return SessionFixture.newEngine();
              });
      setField(fixture.session, "state", WholeGameAnalysisSession.State.PAUSED);
      setField(fixture.session, "resumeStage", WholeGameAnalysisSession.State.BASELINE);

      fixture.session.resume();

      waitForState(fixture.session, WholeGameAnalysisSession.State.BASELINE);
      assertEquals(1, creations.get());
      assertTrue(fixture.session.isActive());
      fixture.session.cancel();
      drainEdt();
    }
  }

  @Test
  void resumedDeepStageDispatchesOnlyPositionsWithoutCompletedResults() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode root = history.getStart();
      BoardHistoryNode first = root.add(new BoardHistoryNode(passData(Stone.BLACK, 1)));
      BoardHistoryNode second = first.add(new BoardHistoryNode(passData(Stone.WHITE, 2)));
      installCompleteAnalysis(root.getData(), 500);
      installCompleteAnalysis(first.getData(), 500);
      Board board = allocate(Board.class);
      board.setHistory(history);
      Lizzie.board = board;
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.frame = frame;
      WholeGameAnalysisPlan plan = WholeGameAnalysisPlan.create(root, 32, 500);
      SessionAnalysisEngine resumedEngine = SessionFixture.newEngine();
      WholeGameAnalysisSession session =
          new WholeGameAnalysisSession(frame, plan, snapshot -> {}, () -> resumedEngine);
      setField(session, "state", WholeGameAnalysisSession.State.PAUSED);
      setField(session, "resumeStage", WholeGameAnalysisSession.State.DEEP);

      session.resume();

      waitForState(session, WholeGameAnalysisSession.State.DEEP);
      waitForRequest(resumedEngine);
      assertEquals(List.of(second), resumedEngine.requestedNodes);
      assertEquals(500, resumedEngine.requestedVisits);
      session.cancel();
      drainEdt();
    }
  }

  @Test
  void competitiveTrackingDoesNotDelayWholeGameRequestDispatch() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisReuseCurrentEngine = true;
      SessionAnalysisEngine engine = SessionFixture.newEngine();
      CountDownLatch engineCreated = new CountDownLatch(1);
      SessionFixture fixture =
          SessionFixture.createWithFactory(
              () -> {
                engineCreated.countDown();
                return engine;
              });
      Leelaz previousLeelaz = Lizzie.leelaz;
      CompetitiveTrackingLeelaz foreground = new CompetitiveTrackingLeelaz();
      Lizzie.leelaz = foreground;

      try {
        fixture.session.start();

        assertTrue(
            foreground.previewed.await(2, TimeUnit.SECONDS),
            "whole-game session did not inspect the competitive tracking owner");
        assertTrue(
            engineCreated.await(2, TimeUnit.SECONDS),
            "whole-game session waited for competitive tracking to end");
        waitForState(fixture.session, WholeGameAnalysisSession.State.BASELINE);
        waitForRequest(engine);
      } finally {
        fixture.session.cancel();
        engine.quitCalled.await(2, TimeUnit.SECONDS);
        Lizzie.leelaz = previousLeelaz;
        drainEdt();
      }
      assertEquals(0L, engine.quitCalled.getCount(), "whole-game analysis closer did not finish");
    }
  }

  @Test
  void engineSwapCannotSplitExclusiveAndPreviewAcrossInstances() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisReuseCurrentEngine = true;
      SessionAnalysisEngine engine = SessionFixture.newEngine();
      CountDownLatch engineCreated = new CountDownLatch(1);
      SessionFixture fixture =
          SessionFixture.createWithFactory(
              () -> {
                engineCreated.countDown();
                return engine;
              });
      Leelaz previousLeelaz = Lizzie.leelaz;
      CompetitiveTrackingLeelaz replacement = new CompetitiveTrackingLeelaz();
      CompetitiveTrackingLeelaz foreground = new CompetitiveTrackingLeelaz(replacement);
      Lizzie.leelaz = foreground;

      try {
        fixture.session.start();

        assertTrue(
            foreground.previewed.await(2, TimeUnit.SECONDS),
            "whole-game session split one availability check across two engine instances");
        assertTrue(
            replacement.previewed.await(2, TimeUnit.SECONDS),
            "whole-game session used an availability result from a replaced engine");
        assertTrue(engineCreated.await(2, TimeUnit.SECONDS));
      } finally {
        fixture.session.cancel();
        engine.quitCalled.await(2, TimeUnit.SECONDS);
        Lizzie.leelaz = previousLeelaz;
        drainEdt();
      }
      assertEquals(0L, engine.quitCalled.getCount(), "whole-game analysis closer did not finish");
    }
  }

  @Test
  void komiChangeInvalidatesTheSessionSemanticSnapshot() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      SessionFixture fixture = SessionFixture.create();

      Lizzie.board
          .getHistory()
          .getGameInfo()
          .setKomi(Lizzie.board.getHistory().getGameInfo().getKomi() + 0.5);

      assertFalse(invokeCurrentGameMatches(fixture.session));
    }
  }

  @Test
  void analysisRulesChangeInvalidatesTheSessionSemanticSnapshot() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      SessionFixture fixture = SessionFixture.create();

      Lizzie.config.analysisSpecificRules = "{\"scoringRule\":\"AREA\"}";

      assertFalse(invokeCurrentGameMatches(fixture.session));
    }
  }

  @Test
  void startAdvancesThroughBaselineAndDeepThenCompletesAndReleasesTheEngine() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.leelaz = null;
      SessionAnalysisEngine engine = SessionFixture.newEngine();
      engine.completeByInstallingAnalysis = true;
      SessionFixture fixture = SessionFixture.createWithFactory(() -> engine);

      fixture.session.start();

      waitUntil(
          () ->
              fixture.session.state() == WholeGameAnalysisSession.State.COMPLETE
                  && !fixture.snapshots.isEmpty()
                  && lastSnapshot(fixture).state == WholeGameAnalysisSession.State.COMPLETE,
          "session should reach COMPLETE");
      assertTrue(engine.quitCalled.await(2, TimeUnit.SECONDS));
      drainEdt();

      List<WholeGameAnalysisSession.State> states = snapshotStates(fixture);
      assertEquals(WholeGameAnalysisSession.State.PREPARING, states.get(0));
      assertTrue(states.contains(WholeGameAnalysisSession.State.BASELINE));
      assertTrue(states.contains(WholeGameAnalysisSession.State.DEEP));
      assertEquals(WholeGameAnalysisSession.State.COMPLETE, states.get(states.size() - 1));
      WholeGameAnalysisSession.Snapshot complete = lastSnapshot(fixture);
      assertEquals(100, complete.overallPercent);
      assertEquals(complete.totalPositions, complete.completedPositions);
      assertEquals("WholeGameAnalysis.complete", complete.detailKey);
      assertEquals(0L, complete.estimatedRemainingMillis);
      assertTrue(engine.shutdownRequested);
      assertTrue(engine.callbacksCleared);
      assertEquals(1, fixture.frame.attachCount);
      assertEquals(1, fixture.frame.finishedCount);
      assertSame(engine, fixture.frame.lastFinishedEngine);
      assertTrue(fixture.session.isTerminal());
      assertFalse(fixture.session.isRunning());
      assertFalse(fixture.session.isActive());
      assertFalse(fixture.session.isPaused());
      assertNull(getObjectField(fixture.session, "gameGuardTimer"));
    }
  }

  @Test
  void alreadyAnalyzedGameCompletesWithoutDispatchingARequest() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.leelaz = null;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode root = history.getStart();
      BoardHistoryNode first = root.add(new BoardHistoryNode(passData(Stone.BLACK, 1)));
      installCompleteAnalysis(root.getData(), 500);
      installCompleteAnalysis(first.getData(), 500);
      SessionAnalysisEngine engine = SessionFixture.newEngine();
      SessionFixture fixture = SessionFixture.createWithHistory(history, () -> engine);

      fixture.session.start();

      waitUntil(
          () ->
              fixture.session.state() == WholeGameAnalysisSession.State.COMPLETE
                  && !fixture.snapshots.isEmpty()
                  && lastSnapshot(fixture).state == WholeGameAnalysisSession.State.COMPLETE,
          "pre-analyzed session should complete");
      drainEdt();

      assertEquals(0, engine.requestCount);
      assertEquals(WholeGameAnalysisSession.State.COMPLETE, fixture.session.state());
      assertEquals(1, fixture.frame.finishedCount);
      assertTrue(engine.quitCalled.await(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void cancelDuringBaselineEndsTheSessionWithoutCompletingRemainingPositions() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.leelaz = null;
      SessionAnalysisEngine engine = SessionFixture.newEngine();
      SessionFixture fixture = SessionFixture.createWithFactory(() -> engine);

      fixture.session.start();
      waitUntil(() -> engine.requestedNodes != null, "baseline request should dispatch");
      drainEdt();
      assertEquals(WholeGameAnalysisSession.State.BASELINE, fixture.session.state());
      assertTrue(fixture.session.isRunning());

      fixture.session.cancel();
      drainEdt();

      assertEquals(WholeGameAnalysisSession.State.CANCELLED, fixture.session.state());
      WholeGameAnalysisSession.Snapshot cancelled = lastSnapshot(fixture);
      assertEquals("WholeGameAnalysis.cancelled", cancelled.detailKey);
      assertTrue(cancelled.completedPositions < cancelled.totalPositions);
      assertTrue(engine.shutdownRequested);
      assertTrue(engine.callbacksCleared);
      assertTrue(engine.quitCalled.await(2, TimeUnit.SECONDS));
      assertEquals(1, fixture.frame.finishedCount);
      assertTrue(fixture.session.isTerminal());
      assertFalse(fixture.session.isRunning());
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = WholeGameAnalysisSession.State.class,
      names = {
        "PREPARING",
        "BASELINE",
        "DEEP",
        "PAUSING",
        "PAUSED",
        "COMPLETE",
        "CANCELLED",
        "FAILED"
      })
  void startIsANoOpUnlessTheSessionIsIdle(WholeGameAnalysisSession.State state) throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      SessionFixture fixture = SessionFixture.create();
      putInState(fixture.session, state);

      fixture.session.start();

      assertEquals(state, fixture.session.state());
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = WholeGameAnalysisSession.State.class,
      names = {"IDLE", "PAUSING", "PAUSED", "COMPLETE", "CANCELLED", "FAILED"})
  void pauseIsANoOpOutsidePreparingBaselineOrDeep(WholeGameAnalysisSession.State state)
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      SessionFixture fixture = SessionFixture.create();
      putInState(fixture.session, state);

      fixture.session.pause();

      assertEquals(state, fixture.session.state());
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = WholeGameAnalysisSession.State.class,
      names = {
        "IDLE",
        "PREPARING",
        "BASELINE",
        "DEEP",
        "PAUSING",
        "COMPLETE",
        "CANCELLED",
        "FAILED"
      })
  void resumeIsANoOpUnlessPaused(WholeGameAnalysisSession.State state) throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      SessionFixture fixture = SessionFixture.create();
      putInState(fixture.session, state);

      fixture.session.resume();

      assertEquals(state, fixture.session.state());
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = WholeGameAnalysisSession.State.class,
      names = {"COMPLETE", "CANCELLED", "FAILED"})
  void cancelDoesNotReplaceAnAlreadyTerminalState(WholeGameAnalysisSession.State state)
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      SessionFixture fixture = SessionFixture.create();
      putInState(fixture.session, state);

      fixture.session.cancel();

      assertEquals(state, fixture.session.state());
      assertTrue(fixture.session.isTerminal());
    }
  }

  @Test
  void cancelFromIdleMarksTheSessionCancelled() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      SessionFixture fixture = SessionFixture.create();

      fixture.session.cancel();
      drainEdt();

      assertEquals(WholeGameAnalysisSession.State.CANCELLED, fixture.session.state());
      assertTrue(fixture.session.isTerminal());
      assertEquals("WholeGameAnalysis.cancelled", lastSnapshot(fixture).detailKey);
    }
  }

  @Test
  void publishReadyIsIgnoredOnceTheSessionHasLeftIdle() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      SessionFixture fixture = SessionFixture.create();
      fixture.session.publishReady();
      drainEdt();
      assertEquals("WholeGameAnalysis.ready", lastSnapshot(fixture).detailKey);
      int readySnapshots = fixture.snapshots.size();
      putInState(fixture.session, WholeGameAnalysisSession.State.BASELINE);

      fixture.session.publishReady();

      assertEquals(readySnapshots, fixture.snapshots.size());
      assertEquals(WholeGameAnalysisSession.State.BASELINE, fixture.session.state());
    }
  }

  @Test
  void engineFactoryFailureFailsTheSessionWithoutAttachingAnEngine() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.leelaz = null;
      SessionFixture fixture =
          SessionFixture.createWithFactory(
              () -> {
                throw new IOException("engine missing");
              });

      fixture.session.start();

      waitUntil(
          () ->
              fixture.session.state() == WholeGameAnalysisSession.State.FAILED
                  && !fixture.snapshots.isEmpty()
                  && lastSnapshot(fixture).state == WholeGameAnalysisSession.State.FAILED,
          "factory failure should fail the session");
      drainEdt();

      assertEquals("WholeGameAnalysis.error.engine", lastSnapshot(fixture).detailKey);
      assertEquals(0, fixture.frame.attachCount);
      assertEquals(1, fixture.frame.finishedCount);
      assertNull(fixture.frame.lastFinishedEngine);
      assertTrue(fixture.session.isTerminal());
      assertFalse(fixture.session.isRunning());
      assertFalse(fixture.session.isActive());
    }
  }

  @Test
  void unloadedEngineFailsAndReleasesTheCreatedEngine() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.leelaz = null;
      SessionAnalysisEngine engine = SessionFixture.newEngine();
      engine.loaded = false;
      SessionFixture fixture = SessionFixture.createWithFactory(() -> engine);

      fixture.session.start();

      waitUntil(
          () -> fixture.session.state() == WholeGameAnalysisSession.State.FAILED,
          "unloaded engine should fail");
      assertTrue(engine.quitCalled.await(2, TimeUnit.SECONDS));
      drainEdt();

      assertEquals("WholeGameAnalysis.error.engine", lastSnapshot(fixture).detailKey);
      assertEquals(0, fixture.frame.attachCount);
      assertEquals(1, fixture.frame.finishedCount);
      assertTrue(fixture.session.isTerminal());
    }
  }

  @Test
  void negativeWholeGameRequestCountFailsImmediately() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.leelaz = null;
      SessionAnalysisEngine engine = SessionFixture.newEngine();
      engine.requestResultOverride = -1;
      SessionFixture fixture = SessionFixture.createWithFactory(() -> engine);

      fixture.session.start();

      waitUntil(
          () ->
              fixture.session.state() == WholeGameAnalysisSession.State.FAILED
                  && !fixture.snapshots.isEmpty()
                  && "WholeGameAnalysis.error.request".equals(lastSnapshot(fixture).detailKey),
          "negative request count should fail");
      drainEdt();

      assertEquals(1, engine.requestCount);
      assertEquals("WholeGameAnalysis.error.request", lastSnapshot(fixture).detailKey);
      assertTrue(engine.shutdownRequested);
      assertTrue(engine.callbacksCleared);
      assertTrue(engine.quitCalled.await(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void emptySuccessfulDispatchRetriesThenFailsWhenPositionsStayPending() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.leelaz = null;
      SessionAnalysisEngine engine = SessionFixture.newEngine();
      engine.requestResultOverride = 0;
      SessionFixture fixture = SessionFixture.createWithFactory(() -> engine);

      fixture.session.start();

      waitUntil(
          () ->
              fixture.session.state() == WholeGameAnalysisSession.State.FAILED
                  && engine.requestCount >= WholeGameAnalysisSession.MAX_STAGE_ATTEMPTS
                  && !fixture.snapshots.isEmpty()
                  && "WholeGameAnalysis.error.request".equals(lastSnapshot(fixture).detailKey),
          "exhausted stage attempts should fail");
      drainEdt();

      assertEquals(WholeGameAnalysisSession.MAX_STAGE_ATTEMPTS, engine.requestCount);
      assertEquals("WholeGameAnalysis.error.request", lastSnapshot(fixture).detailKey);
      assertTrue(fixture.session.isTerminal());
      assertTrue(engine.quitCalled.await(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void gameChangeDuringEngineAcceptFailsConsistentlyAndClosesTheEngine() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      SessionFixture fixture = SessionFixture.create();
      setField(fixture.session, "state", WholeGameAnalysisSession.State.PREPARING);
      setField(fixture.session, "engineStartGeneration", 1);
      Lizzie.board
          .getHistory()
          .getGameInfo()
          .setKomi(Lizzie.board.getHistory().getGameInfo().getKomi() + 1.0);

      invokeAcceptEngine(
          fixture.session, fixture.engine, 1, WholeGameAnalysisSession.State.BASELINE);
      drainEdt();

      assertEquals(WholeGameAnalysisSession.State.FAILED, fixture.session.state());
      assertEquals("WholeGameAnalysis.error.gameChanged", lastSnapshot(fixture).detailKey);
      assertEquals(0, fixture.frame.attachCount);
      assertTrue(fixture.engine.quitCalled.await(2, TimeUnit.SECONDS));
      assertTrue(fixture.session.isTerminal());
    }
  }

  @Test
  void failedSessionIgnoresALaterStartPauseAndResume() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      SessionFixture fixture = SessionFixture.create();
      putInState(fixture.session, WholeGameAnalysisSession.State.FAILED);
      int snapshots = fixture.snapshots.size();

      fixture.session.start();
      fixture.session.pause();
      fixture.session.resume();
      fixture.session.cancel();

      assertEquals(WholeGameAnalysisSession.State.FAILED, fixture.session.state());
      assertEquals(snapshots, fixture.snapshots.size());
    }
  }

  private static void invokeEngineFailure(WholeGameAnalysisSession session, int generation)
      throws Exception {
    Method method = WholeGameAnalysisSession.class.getDeclaredMethod("onEngineFailure", int.class);
    method.setAccessible(true);
    method.invoke(session, generation);
  }

  private static boolean invokeCurrentGameMatches(WholeGameAnalysisSession session)
      throws Exception {
    Method method = WholeGameAnalysisSession.class.getDeclaredMethod("currentGameMatches");
    method.setAccessible(true);
    return (boolean) method.invoke(session);
  }

  private static void invokeAcceptEngine(
      WholeGameAnalysisSession session,
      AnalysisEngine engine,
      int generation,
      WholeGameAnalysisSession.State stage)
      throws Exception {
    Method method =
        WholeGameAnalysisSession.class.getDeclaredMethod(
            "acceptEngine", AnalysisEngine.class, int.class, WholeGameAnalysisSession.State.class);
    method.setAccessible(true);
    method.invoke(session, engine, generation, stage);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = WholeGameAnalysisSession.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void putInState(
      WholeGameAnalysisSession session, WholeGameAnalysisSession.State state) throws Exception {
    boolean terminal =
        state == WholeGameAnalysisSession.State.COMPLETE
            || state == WholeGameAnalysisSession.State.CANCELLED
            || state == WholeGameAnalysisSession.State.FAILED;
    setField(session, "state", state);
    setField(session, "terminal", terminal);
  }

  private static List<WholeGameAnalysisSession.State> snapshotStates(SessionFixture fixture) {
    List<WholeGameAnalysisSession.State> states = new ArrayList<>();
    for (WholeGameAnalysisSession.Snapshot snapshot : fixture.snapshots) {
      states.add(snapshot.state);
    }
    return states;
  }

  private static WholeGameAnalysisSession.Snapshot lastSnapshot(SessionFixture fixture) {
    assertFalse(fixture.snapshots.isEmpty(), "expected at least one session snapshot");
    return fixture.snapshots.get(fixture.snapshots.size() - 1);
  }

  private static void waitUntil(BooleanSupplier condition, String message) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      drainEdt();
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.yield();
    }
    drainEdt();
    assertTrue(condition.getAsBoolean(), message);
  }

  private static Object getObjectField(Object target, String name) throws Exception {
    Field field = WholeGameAnalysisSession.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static int getIntField(Object target, String name) throws Exception {
    Field field = WholeGameAnalysisSession.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.getInt(target);
  }

  private static void waitForState(
      WholeGameAnalysisSession session, WholeGameAnalysisSession.State expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      drainEdt();
      if (session.state() == expected) {
        return;
      }
      Thread.sleep(10L);
    }
    assertEquals(expected, session.state());
  }

  private static void waitForRequest(SessionAnalysisEngine engine) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      if (engine.requestedNodes != null) {
        return;
      }
      Thread.sleep(10L);
    }
    assertTrue(engine.requestedNodes != null, "Expected a whole-game request");
  }

  private static BoardData passData(Stone color, int moveNumber) {
    Stone[] stones = new Stone[BOARD_SIZE * BOARD_SIZE];
    Arrays.fill(stones, Stone.EMPTY);
    return BoardData.pass(
        stones,
        color,
        color == Stone.WHITE,
        new Zobrist(),
        moveNumber,
        new int[BOARD_SIZE * BOARD_SIZE],
        0,
        0,
        50.0,
        0);
  }

  private static void installCompleteAnalysis(BoardData data, int visits) {
    MoveData move = new MoveData();
    move.coordinate = "B2";
    move.playouts = visits;
    move.winrate = 50.0;
    move.order = 0;
    move.variation = List.of("B2");
    data.setPlayouts(visits);
    data.bestMoves = List.of(move);
  }

  private static void drainEdt() throws Exception {
    SwingUtilities.invokeAndWait(() -> {});
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class SessionFixture {
    private final WholeGameAnalysisSession session;
    private final SessionAnalysisEngine engine;
    private final TrackingFrame frame;
    private final List<WholeGameAnalysisSession.Snapshot> snapshots;

    private SessionFixture(
        WholeGameAnalysisSession session,
        SessionAnalysisEngine engine,
        TrackingFrame frame,
        List<WholeGameAnalysisSession.Snapshot> snapshots) {
      this.session = session;
      this.engine = engine;
      this.frame = frame;
      this.snapshots = snapshots;
    }

    private static SessionFixture create() throws Exception {
      return createWithFactory(null);
    }

    private static SessionFixture createWithFactory(WholeGameAnalysisSession.EngineFactory factory)
        throws Exception {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      return createWithHistory(history, factory);
    }

    private static SessionFixture createWithHistory(
        BoardHistoryList history, WholeGameAnalysisSession.EngineFactory factory)
        throws Exception {
      Board board = allocate(Board.class);
      board.setHistory(history);
      Lizzie.board = board;
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.frame = frame;
      WholeGameAnalysisPlan plan = WholeGameAnalysisPlan.create(history.getStart(), 32, 500);
      SessionAnalysisEngine engine = newEngine();
      List<WholeGameAnalysisSession.Snapshot> snapshots = new CopyOnWriteArrayList<>();
      WholeGameAnalysisSession session =
          factory == null
              ? new WholeGameAnalysisSession(frame, plan, snapshots::add)
              : new WholeGameAnalysisSession(frame, plan, snapshots::add, factory);
      return new SessionFixture(session, engine, frame, snapshots);
    }

    private static SessionAnalysisEngine newEngine() {
      try {
        SessionAnalysisEngine engine = allocate(SessionAnalysisEngine.class);
        engine.quitCalled = new CountDownLatch(1);
        engine.loaded = true;
        return engine;
      } catch (Exception ex) {
        throw new IllegalStateException(ex);
      }
    }
  }

  private static final class SessionAnalysisEngine extends AnalysisEngine {
    private int requestCount;
    private boolean shutdownRequested;
    private boolean callbacksCleared;
    private CountDownLatch quitCalled;
    private volatile List<BoardHistoryNode> requestedNodes;
    private volatile int requestedVisits;
    private boolean completeByInstallingAnalysis;
    private Integer requestResultOverride;
    private boolean loaded = true;

    private SessionAnalysisEngine() throws IOException {
      super(true);
    }

    @Override
    void requestShutdown() {
      shutdownRequested = true;
    }

    @Override
    public void clearRequestCallbacks() {
      callbacksCleared = true;
    }

    @Override
    public void normalQuit() {
      quitCalled.countDown();
    }

    @Override
    public boolean isLoaded() {
      return loaded;
    }

    @Override
    public int startWholeGameRequest(
        List<BoardHistoryNode> requestedNodes, int targetVisits, boolean includeOwnership) {
      requestCount++;
      this.requestedNodes = List.copyOf(requestedNodes);
      requestedVisits = targetVisits;
      if (completeByInstallingAnalysis) {
        for (BoardHistoryNode node : requestedNodes) {
          installCompleteAnalysis(node.getData(), targetVisits);
        }
      }
      if (requestResultOverride != null) {
        return requestResultOverride;
      }
      if (completeByInstallingAnalysis) {
        return 0;
      }
      return requestedNodes.size();
    }
  }

  private static final class CompetitiveTrackingLeelaz extends Leelaz {
    private final CountDownLatch previewed = new CountDownLatch(1);
    private final Leelaz replacementOnBusyCheck;

    private CompetitiveTrackingLeelaz() throws IOException {
      this(null);
    }

    private CompetitiveTrackingLeelaz(Leelaz replacementOnBusyCheck) throws IOException {
      super("");
      this.replacementOnBusyCheck = replacementOnBusyCheck;
    }

    @Override
    public boolean hasExclusiveGtpWorkInProgress() {
      if (replacementOnBusyCheck != null) {
        Lizzie.leelaz = replacementOnBusyCheck;
      }
      return true;
    }

    @Override
    public ExclusiveGtpLeaseAvailability previewForegroundAnalysisLeaseAvailability() {
      previewed.countDown();
      return ExclusiveGtpLeaseAvailability.AVAILABLE;
    }
  }

  private static final class TrackingFrame extends LizzieFrame {
    private int attachCount;
    private int finishedCount;
    private AnalysisEngine lastFinishedEngine;

    private TrackingFrame() {}

    @Override
    public void onWholeGameAnalysisFinished(
        WholeGameAnalysisSession session,
        AnalysisEngine completedEngine,
        boolean resumeForegroundAnalysis) {
      finishedCount++;
      lastFinishedEngine = completedEngine;
    }

    @Override
    public void attachWholeGameAnalysisEngine(
        WholeGameAnalysisSession session, AnalysisEngine engine) {
      attachCount++;
    }
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final Leelaz previousLeelaz;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame,
        Leelaz previousLeelaz) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousLeelaz = previousLeelaz;
    }

    private static TestEnvironment open() {
      TestEnvironment environment =
          new TestEnvironment(
              Board.boardWidth,
              Board.boardHeight,
              Lizzie.config,
              Lizzie.board,
              Lizzie.frame,
              Lizzie.leelaz);
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      Config config;
      try {
        config = allocate(Config.class);
      } catch (Exception ex) {
        throw new IllegalStateException(ex);
      }
      config.analysisUseCurrentRules = false;
      config.analysisSpecificRules = "";
      config.currentKataGoRules = "";
      config.autoLoadKataRules = false;
      config.kataRules = "";
      Lizzie.config = config;
      return environment;
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousLeelaz;
    }
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
