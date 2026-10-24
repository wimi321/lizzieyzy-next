package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises the ordinary GTP parser-to-automatic-analysis action boundary. */
class LeelazAutomaticAnalysisOutputTest {
  @Test
  void exactRootBudgetDoesNotAdvanceEarlyForLargeDagChildCounts() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.info("info move D4 visits 5000 winrate 0.6 pv D4 rootInfo visits 99");
      assertEquals(0, fixture.engine.advances);
      assertEquals(99, fixture.engine.getBestMovesPlayouts());

      fixture.info("info move D4 visits 5001 winrate 0.6 pv D4 rootInfo visits 100");
      assertEquals(1, fixture.engine.advances);
      assertTrue(fixture.engine.lastMove);
    }
  }

  @Test
  void rootlessCloudOutputAdvancesUsingAllNonSymmetricReportedCandidates() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.info(
          "info move D4 visits 40 winrate 0.6 order 0 pv D4"
              + " info move Q16 visits 39 winrate 0.5 order 1 pv Q16"
              + " info move Q4 visits 5000 winrate 0.6 isSymmetryOf D4 order 2 pv Q4");
      assertEquals(0, fixture.engine.advances);
      assertEquals(79, fixture.engine.getBestMovesPlayouts());

      fixture.info(
          "info move D4 visits 60 winrate 0.6 order 0 pv D4"
              + " info move Q16 visits 40 winrate 0.5 order 1 pv Q16");
      assertEquals(1, fixture.engine.advances);
      assertEquals(100, fixture.engine.getBestMovesPlayouts());
    }
  }

  @Test
  void firstCandidateBudgetRemainsIndependentOfRootBudget() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Lizzie.config.autoAnaPlayouts = -1;
      Lizzie.config.autoAnaFirstPlayouts = 100;
      fixture.info("info move D4 visits 99 winrate 0.6 pv D4 rootInfo visits 5000");
      assertEquals(0, fixture.engine.advances);
      fixture.info("info move D4 visits 100 winrate 0.6 pv D4 rootInfo visits 5001");
      assertEquals(1, fixture.engine.advances);
    }
  }

  @Test
  void cancellationAfterInfoAdmissionDoesNotAdvanceTheGame() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.engine.beforeAction = () -> Lizzie.config.isAutoAna = false;
      fixture.info("info move D4 visits 100 winrate 0.6 pv D4 rootInfo visits 100");
      assertEquals(0, fixture.engine.advances);
    }
  }

  @Test
  void cancellationAfterInfoAdmissionDoesNotMarkBranchAnalysisComplete() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Lizzie.config.analyzeAllBranch = true;
      fixture.engine.beforeAction = () -> Lizzie.config.isAutoAna = false;
      fixture.info("info move D4 visits 100 winrate 0.6 pv D4 rootInfo visits 100");
      assertFalse(Lizzie.board.getHistory().getCurrentHistoryNode().analyzed);
    }
  }

  @Test
  void cancellationAfterInfoAdmissionDoesNotMarkDifferenceAnalysisComplete() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Lizzie.frame.isAutoAnalyzingDiffNode = true;
      Lizzie.config.autoAnaDiffPlayouts = 100;
      fixture.engine.beforeAction = () -> Lizzie.config.isAutoAna = false;
      fixture.info("info move D4 visits 100 winrate 0.6 pv D4 rootInfo visits 100");
      assertFalse(Lizzie.board.getHistory().getCurrentHistoryNode().diffAnalyzed);
    }
  }

  @Test
  void activeBranchAnalysisMarksCompletionOnlyAtItsBudget() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Lizzie.config.analyzeAllBranch = true;
      fixture.info("info move D4 visits 5000 winrate 0.6 pv D4 rootInfo visits 99");
      assertFalse(Lizzie.board.getHistory().getCurrentHistoryNode().analyzed);
      fixture.info("info move D4 visits 5001 winrate 0.6 pv D4 rootInfo visits 100");
      assertTrue(Lizzie.board.getHistory().getCurrentHistoryNode().analyzed);
      assertEquals(0, fixture.engine.advances);
    }
  }

  @Test
  void activeDifferenceAnalysisMarksCompletionOnlyAtItsOwnBudget() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Lizzie.frame.isAutoAnalyzingDiffNode = true;
      Lizzie.config.autoAnaDiffPlayouts = 200;
      fixture.info("info move D4 visits 5000 winrate 0.6 pv D4 rootInfo visits 199");
      assertFalse(Lizzie.board.getHistory().getCurrentHistoryNode().diffAnalyzed);
      fixture.info("info move D4 visits 5001 winrate 0.6 pv D4 rootInfo visits 200");
      assertTrue(Lizzie.board.getHistory().getCurrentHistoryNode().diffAnalyzed);
      assertEquals(0, fixture.engine.advances);
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final Leelaz previousEngine = Lizzie.leelaz;
    private final Board previousBoard = Lizzie.board;
    private final Config previousConfig = Lizzie.config;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final BottomToolbar previousToolbar = LizzieFrame.toolbar;
    private final featurecat.lizzie.gui.Menu previousMenu = LizzieFrame.menu;
    private final RecordingEngine engine = new RecordingEngine();

    private Fixture() throws Exception {
      engine.isLoaded = true;
      engine.started = true;
      engine.isKatago = true;
      engine.useRemoteCompute = true;
      engine.commandLists.addAll(List.of("stop", "kata-analyze"));
      setField(engine, "endGetCommandList", true);
      setField(engine, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));
      Lizzie.leelaz = engine;
      Lizzie.config = allocate(Config.class);
      Lizzie.config.isAutoAna = true;
      Lizzie.config.anaBlack = true;
      Lizzie.config.anaWhite = true;
      Lizzie.config.autoAnaEndMove = -1;
      Lizzie.config.autoAnaPlayouts = 100;
      Lizzie.config.autoAnaFirstPlayouts = -1;
      Lizzie.config.autoAnaTime = -1;
      Lizzie.board = new Board();
      Lizzie.frame = allocate(SilentFrame.class);
      LizzieFrame.toolbar = allocate(BottomToolbar.class);
      LizzieFrame.menu = allocate(SilentMenu.class);
      engine.sendCommand("kata-analyze B 10 rootInfo true");
      engine.setResponseUpToDate();
    }

    private void info(String line) throws Exception {
      Method method = Leelaz.class.getDeclaredMethod("parseLine", String.class);
      method.setAccessible(true);
      method.invoke(engine, line);
    }

    @Override
    public void close() {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      LizzieFrame.menu = previousMenu;
    }
  }

  private static final class RecordingEngine extends Leelaz {
    private int advances;
    private boolean lastMove;
    private Runnable beforeAction;

    private RecordingEngine() throws java.io.IOException {
      super("");
    }

    @Override
    void beforeAnalysisOutputActionForTest(boolean exactEngineGame) {
      if (!exactEngineGame && beforeAction != null) beforeAction.run();
    }

    @Override
    public void analyzeNextMove(boolean isLastMove) {
      advances++;
      lastMove = isLastMove;
    }
  }

  private static final class SilentFrame extends LizzieFrame {
    @Override
    public void requestAnalysisRefresh() {}

    @Override
    public void requestAnalysisTitleUpdate() {}
  }

  private static final class SilentMenu extends featurecat.lizzie.gui.Menu {
    @Override
    public void toggleEngineMenuStatus(boolean pondering, boolean thinking) {}
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }
}
