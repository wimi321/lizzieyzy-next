package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class LizzieFrameRulesRetirementTest {
  @Test
  void remoteRecoveryWaitsForTransportAndExclusiveRestoreButNotTerminalErrors() throws Exception {
    class Remote extends Leelaz {
      boolean recovering;
      boolean busy;

      Remote() throws Exception {
        super("");
      }

      @Override
      public boolean isRemoteSessionRecoveryRequested() {
        return recovering;
      }

      @Override
      public boolean hasExclusiveGtpWorkInProgress() {
        return busy;
      }
    }
    Remote engine = new Remote();
    engine.useRemoteCompute = true;
    assertFalse(LizzieFrame.remoteRulesSynchronizationMustWait(engine));
    engine.recovering = true;
    assertTrue(LizzieFrame.remoteRulesSynchronizationMustWait(engine));
    engine.recovering = false;
    engine.busy = true;
    assertTrue(LizzieFrame.remoteRulesSynchronizationMustWait(engine));
    engine.busy = false;
    engine.isDownWithError = true;
    assertFalse(LizzieFrame.remoteRulesSynchronizationMustWait(engine));
    engine.useRemoteCompute = false;
    engine.busy = true;
    assertFalse(LizzieFrame.remoteRulesSynchronizationMustWait(engine));
    assertFalse(LizzieFrame.remoteRulesSynchronizationMustWait(null));
  }

  @Test
  void remoteReconnectOnlyResubmitsTheSameImportAndPrimaryAuthority() throws Exception {
    Board previousBoard = Lizzie.board;
    Leelaz previousPrimary = Lizzie.leelaz;
    boolean previousCanGoAfterload = LizzieFrame.canGoAfterload;
    LizzieFrame frame = allocate(LizzieFrame.class);
    KifuEngineSyncCoordinator coordinator = null;
    try {
      Leelaz primary = new Leelaz("");
      primary.useRemoteCompute = true;
      Lizzie.leelaz = primary;
      Lizzie.board = new Board();
      BoardHistoryList history = Lizzie.board.getHistory();
      BoardHistoryNode root = history.getStart();
      BoardHistoryList.SessionRulesTarget target = history.publishExternalRules("Chinese");
      long generation = Lizzie.capturePrimaryEngineGeneration(primary);
      setField(frame, "pendingKifuEngineSyncRoot", root);
      Method resume =
          LizzieFrame.class.getDeclaredMethod(
              "resumeKifuSyncAfterRemoteReconnect",
              BoardHistoryNode.class,
              BoardHistoryList.SessionRulesTarget.class,
              Leelaz.class,
              long.class,
              Leelaz.class,
              int.class,
              Runnable.class);
      resume.setAccessible(true);
      assertEquals(true, resume.invoke(frame, root, target, primary, generation, null, 0, null));
      coordinator = (KifuEngineSyncCoordinator) getField(frame, "kifuEngineSyncCoordinator");
      assertEquals(
          false, resume.invoke(frame, root, target, primary, generation + 1, null, 0, null));
      assertEquals(
          false, resume.invoke(frame, root, target, primary, generation, new Leelaz(""), 0, null));
      primary.useRemoteCompute = false;
      assertEquals(false, resume.invoke(frame, root, target, primary, generation, null, 0, null));
      primary.useRemoteCompute = true;
      history.publishExternalRules("Japanese");
      assertEquals(false, resume.invoke(frame, root, target, primary, generation, null, 0, null));
      Lizzie.board = new Board();
      assertEquals(false, resume.invoke(frame, root, target, primary, generation, null, 0, null));
    } finally {
      if (coordinator != null) coordinator.close();
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousPrimary;
      LizzieFrame.canGoAfterload = previousCanGoAfterload;
    }
  }

  @Test
  void manualSelectionRetiresImportedRequestAndMatchingContinuePermit() throws Exception {
    Board previousBoard = Lizzie.board;
    Leelaz previousPrimary = Lizzie.leelaz;
    boolean previousCanGoAfterload = LizzieFrame.canGoAfterload;
    try {
      Leelaz primary = new Leelaz("");
      Lizzie.leelaz = primary;
      Board board = new Board();
      Lizzie.board = board;
      BoardHistoryList history = board.getHistory();
      BoardHistoryList.SessionRulesTarget target = history.publishExternalRules("Japanese");
      history.authorizeAnalysisWithCurrentRules(target, primary, 7L, null);

      LizzieFrame frame = allocate(LizzieFrame.class);
      BoardHistoryNode root = history.getStart();
      setField(frame, "pendingKifuEngineSyncRoot", root);
      setField(frame, "kifuAnalysisResumeGeneration", 11);
      LizzieFrame.canGoAfterload = false;

      frame.retireImportedRulesForManualSelection(history, target);

      assertNull(getField(frame, "pendingKifuEngineSyncRoot"));
      assertEquals(12, getField(frame, "kifuAnalysisResumeGeneration"));
      assertFalse(history.permitsAnalysisWithCurrentRules(target, primary, 7L, null));
    } finally {
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousPrimary;
      LizzieFrame.canGoAfterload = previousCanGoAfterload;
    }
  }

  @Test
  void nullEngineIncarnationFenceRemainsCurrentUntilAnEngineAppears() {
    assertTrue(LizzieFrame.exactEngineIncarnationsRemainCurrent(null, null, null, null));
  }

  @Test
  void rulesCapabilityRetryStopsAtDeadlineOrTerminalEngineState() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.started = true;

    assertTrue(
        LizzieFrame.rulesCapabilityDiscoveryMayStillComplete(
            engine, System.nanoTime() + 1_000_000_000L));
    assertFalse(
        LizzieFrame.rulesCapabilityDiscoveryMayStillComplete(engine, System.nanoTime() - 1L));
    engine.isDownWithError = true;
    assertFalse(
        LizzieFrame.rulesCapabilityDiscoveryMayStillComplete(
            engine, System.nanoTime() + 1_000_000_000L));
    assertTrue(
        LizzieFrame.exactEngineIncarnationsRemainCurrent(null, null, null, null),
        "a captured no-engine request remains current until an engine appears");
    assertFalse(
        LizzieFrame.exactEngineIncarnationsRemainCurrent(null, new Object(), null, null),
        "a synthetic incarnation cannot stand in for a captured no-engine request");
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = LizzieFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object getField(Object target, String name) throws Exception {
    Field field = LizzieFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }
}
