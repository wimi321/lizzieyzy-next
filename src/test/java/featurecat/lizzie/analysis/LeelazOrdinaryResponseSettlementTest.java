package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeelazOrdinaryResponseSettlementTest {

  @Test
  void settledOrdinaryAnalyzeAcceptsItsFirstInfoLine() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    featurecat.lizzie.gui.Menu previousMenu = LizzieFrame.menu;
    Leelaz engine = new Leelaz("");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try {
      engine.isLoaded = true;
      engine.started = true;
      engine.isKatago = true;
      engine.commandLists.addAll(List.of("stop", "kata-analyze"));
      setField(engine, "endGetCommandList", true);
      setField(engine, "outputStream", new BufferedOutputStream(output));
      setField(engine, "currentCmdNum", 15);
      setField(engine, "cmdNumber", 16);
      Lizzie.leelaz = engine;
      Lizzie.config = allocate(Config.class);
      Lizzie.config.enableLizzieCache = true;
      Lizzie.config.limitPlayout = true;
      Lizzie.config.limitPlayouts = 500;
      Lizzie.board = new Board();
      AcceptingInfoFrame frame = allocate(AcceptingInfoFrame.class);
      Lizzie.frame = frame;
      LizzieFrame.menu = allocate(SilentMenu.class);
      LizzieFrame.toolbar = allocate(BottomToolbar.class);

      engine.sendCommand("kata-analyze W 10");

      assertEquals("kata-analyze W 10\n", output.toString(StandardCharsets.UTF_8));
      assertFalse(engine.isResponseUpToDate());
      engine.setResponseUpToDate();
      String upstream;
      try (var input = getClass().getResourceAsStream("/analysis/katago-root-focus-info.txt")) {
        upstream = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
      }
      parseInfo(engine, upstream);
      assertEquals(100, Lizzie.board.getData().getPlayouts());
      assertEquals(100, Lizzie.board.getData().rootVisits);
      MoveData c3 = engine.getBestMoves().stream()
          .filter(move -> move.coordinate.equals("C3")).findFirst().orElseThrow();
      assertEquals(368, c3.playouts);
      assertEquals(0, c3.edgeVisits);
      assertEquals(10, c3.order);
      assertFalse(engine.isStopPonderingByLimit());

      parseInfo(engine, "info move D4 visits 40 winrate 0.51 scoreLead 2.5 prior 0.2 pv D4"
          + " rootInfo visits 100 winrate 0.9 scoreLead 20");
      assertEquals(51.0, Lizzie.board.getData().winrate, 0.001);
      assertEquals(2.5, Lizzie.board.getData().scoreMean, 0.001);
      parseInfo(engine, "info move D4 visits 40 winrate 0.66 scoreLead 3.5 prior 0.2 pv D4 C3"
          + " rootInfo visits 100 winrate 0.9 scoreLead 20");
      assertEquals(66.0, Lizzie.board.getData().winrate, 0.001);
      assertEquals(List.of("D4", "C3"), Lizzie.board.getData().bestMoves.get(0).variation);

      engine.sendCommand("kata-analyze W 11");
      engine.setResponseUpToDate();
      parseInfo(engine, "info move D4 visits 50000 winrate 0.8 pv D4 rootInfo visits 50");
      assertEquals(100, Lizzie.board.getData().getPlayouts());
      assertEquals(66.0, Lizzie.board.getData().winrate, 0.001);
      parseInfo(engine, "info move D4 visits 50000 winrate 0.8 pv D4 rootInfo visits 100");
      assertEquals(66.0, Lizzie.board.getData().winrate, 0.001);
      Lizzie.board.getData().isChanged = true;
      parseInfo(engine, "info move D4 visits 50000 winrate 0.8 pv D4 rootInfo visits 50");
      assertEquals(50, Lizzie.board.getData().rootVisits);
      assertEquals(80.0, Lizzie.board.getData().winrate, 0.001);
      parseInfo(engine, "info move D4 visits 50000 winrate 0.8 pv D4 rootInfo visits 501");
      org.junit.jupiter.api.Assertions.assertTrue(engine.isStopPonderingByLimit());
      org.junit.jupiter.api.Assertions.assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("stop\n"));
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
      LizzieFrame.menu = previousMenu;
    }
  }

  private static void parseInfo(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("parseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
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

  private static final class SilentMenu extends featurecat.lizzie.gui.Menu {
    @Override
    public void toggleEngineMenuStatus(boolean pondering, boolean thinking) {}
  }

  private static final class AcceptingInfoFrame extends LizzieFrame {
    private int analysisRefreshCount;

    @Override
    public void requestAnalysisRefresh() {
      analysisRefreshCount++;
    }

    @Override
    public void requestAnalysisTitleUpdate() {}
  }
}
