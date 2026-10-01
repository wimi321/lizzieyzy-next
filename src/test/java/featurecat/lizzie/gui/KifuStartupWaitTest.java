package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class KifuStartupWaitTest {
  @Test
  void startupFailureReleasesImportWithoutStartingAnalysis() throws Exception {
    try (Fixture f = new Fixture()) {
      f.defer();
      f.transition("fail", new Class<?>[] {long.class, boolean.class, String.class},
          f.token, true, "missing engine");
      f.runPending();
      assertNull(field(f.frame, "pendingKifuEngineSyncRoot"));
      assertTrue(LizzieFrame.canGoAfterload);
      assertEquals(0, f.actions.get());
    }
  }

  @Test
  void replacementEngineDoesNotInheritOldImportContinuation() throws Exception {
    try (Fixture f = new Fixture()) {
      f.defer();
      f.begin();
      f.runPending();
      assertNull(field(f.frame, "pendingKifuEngineSyncRoot"));
      assertEquals(0, f.actions.get());
    }
  }

  @Test
  void changedGameIsNotClearedByRetiredStartup() throws Exception {
    try (Fixture f = new Fixture()) {
      f.defer();
      Lizzie.board = new Board();
      BoardHistoryNode next = Lizzie.board.getHistory().getStart();
      set(f.frame, "pendingKifuEngineSyncRoot", next);
      f.runPending();
      assertSame(next, field(f.frame, "pendingKifuEngineSyncRoot"));
      assertFalse(LizzieFrame.canGoAfterload);
      assertEquals(0, f.actions.get());
    }
  }

  @Test
  void changedRulesCannotResumeCapturedImport() throws Exception {
    try (Fixture f = new Fixture()) {
      f.defer();
      Lizzie.board.getHistory().publishExternalRules("Japanese");
      f.runPending();
      assertEquals(0, f.actions.get());
      assertFalse(LizzieFrame.canGoAfterload);
    }
  }

  @Test
  void shutdownRetiresQueuedWait() throws Exception {
    try (Fixture f = new Fixture()) {
      f.defer();
      f.frame.shutdownKifuEngineSyncCoordinator();
      f.runPending();
      assertNull(field(f.frame, "pendingKifuEngineSyncRoot"));
      assertEquals(0, f.actions.get());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final Board oldBoard = Lizzie.board;
    final Leelaz oldEngine = Lizzie.leelaz;
    final EngineManager oldManager = Lizzie.engineManager;
    final boolean oldCanGo = LizzieFrame.canGoAfterload;
    final LizzieFrame frame = allocate(LizzieFrame.class);
    final EngineManager manager = allocate(EngineManager.class);
    final CapturingExecutor executor = new CapturingExecutor();
    final AtomicInteger actions = new AtomicInteger();
    final Object tracker;
    final long token;

    Fixture() throws Exception {
      Lizzie.leelaz = new Leelaz("");
      Lizzie.board = new Board();
      Lizzie.engineManager = manager;
      var constructor = Class.forName(
          "featurecat.lizzie.analysis.EngineManager$EngineSwitchUiTracker")
          .getDeclaredConstructor();
      constructor.setAccessible(true);
      tracker = constructor.newInstance();
      set(manager, "engineSwitchUiTracker", tracker);
      begin();
      token = manager.engineSwitchUiSnapshot(true).token();
      set(frame, "kifuEngineSyncCoordinator", new KifuEngineSyncCoordinator(executor));
      set(frame, "pendingKifuEngineSyncRoot", Lizzie.board.getHistory().getStart());
      LizzieFrame.canGoAfterload = false;
    }

    void begin() throws Exception {
      transition("begin", new Class<?>[] {boolean.class, int.class, String.class,
          int.class, String.class}, true, -1, "", 0, "test");
    }

    void transition(String name, Class<?>[] types, Object... args) throws Exception {
      Method method = tracker.getClass().getDeclaredMethod(name, types);
      method.setAccessible(true);
      method.invoke(tracker, args);
    }

    void defer() throws Exception {
      Method method = LizzieFrame.class.getDeclaredMethod("deferKifuSyncUntilEngineSwitchSettles",
          BoardHistoryNode.class, BoardHistoryList.SessionRulesTarget.class,
          int.class, Runnable.class);
      method.setAccessible(true);
      assertTrue((Boolean) method.invoke(frame, Lizzie.board.getHistory().getStart(),
          Lizzie.board.getHistory().captureSessionRules(), 0, (Runnable) actions::incrementAndGet));
    }

    void runPending() throws Exception {
      executor.pending.run();
      SwingUtilities.invokeAndWait(() -> {});
    }

    @Override
    public void close() throws Exception {
      frame.shutdownKifuEngineSyncCoordinator();
      SwingUtilities.invokeAndWait(() -> {});
      Lizzie.board = oldBoard;
      Lizzie.leelaz = oldEngine;
      Lizzie.engineManager = oldManager;
      LizzieFrame.canGoAfterload = oldCanGo;
    }
  }

  private static final class CapturingExecutor extends ScheduledThreadPoolExecutor {
    Runnable pending;
    CapturingExecutor() { super(1); }
    @Override public void execute(Runnable command) { pending = command; }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
