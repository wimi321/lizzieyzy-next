package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Zobrist;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

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

  private static void invokeEngineFailure(WholeGameAnalysisSession session, int generation)
      throws Exception {
    Method method =
        WholeGameAnalysisSession.class.getDeclaredMethod("onEngineFailure", int.class);
    method.setAccessible(true);
    method.invoke(session, generation);
  }

  private static boolean invokeCurrentGameMatches(WholeGameAnalysisSession session)
      throws Exception {
    Method method = WholeGameAnalysisSession.class.getDeclaredMethod("currentGameMatches");
    method.setAccessible(true);
    return (boolean) method.invoke(session);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = WholeGameAnalysisSession.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
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

    private SessionFixture(
        WholeGameAnalysisSession session, SessionAnalysisEngine engine) {
      this.session = session;
      this.engine = engine;
    }

    private static SessionFixture create() throws Exception {
      BoardHistoryList history =
          new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      Board board = allocate(Board.class);
      board.setHistory(history);
      Lizzie.board = board;
      TrackingFrame frame = allocate(TrackingFrame.class);
      Lizzie.frame = frame;
      WholeGameAnalysisPlan plan =
          WholeGameAnalysisPlan.create(history.getStart(), 32, 500);
      WholeGameAnalysisSession session =
          new WholeGameAnalysisSession(frame, plan, snapshot -> {});
      SessionAnalysisEngine engine = allocate(SessionAnalysisEngine.class);
      engine.quitCalled = new CountDownLatch(1);
      return new SessionFixture(session, engine);
    }
  }

  private static final class SessionAnalysisEngine extends AnalysisEngine {
    private int requestCount;
    private boolean shutdownRequested;
    private boolean callbacksCleared;
    private CountDownLatch quitCalled;

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
  }

  private static final class TrackingFrame extends LizzieFrame {
    private TrackingFrame() {}

    @Override
    public void onWholeGameAnalysisFinished(
        WholeGameAnalysisSession session,
        AnalysisEngine completedEngine,
        boolean resumeForegroundAnalysis) {}
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
    }

    private static TestEnvironment open() {
      TestEnvironment environment =
          new TestEnvironment(
              Board.boardWidth,
              Board.boardHeight,
              Lizzie.config,
              Lizzie.board,
              Lizzie.frame);
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
