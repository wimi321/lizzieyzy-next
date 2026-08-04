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
      Lizzie.board = new Board();
      AcceptingInfoFrame frame = allocate(AcceptingInfoFrame.class);
      Lizzie.frame = frame;
      LizzieFrame.toolbar = allocate(BottomToolbar.class);

      engine.sendCommand("kata-analyze W 10");

      assertEquals("kata-analyze W 10\n", output.toString(StandardCharsets.UTF_8));
      assertFalse(engine.isResponseUpToDate());
      engine.setResponseUpToDate();
      parseInfo(engine, "info move D4 visits 40 winrate 0.51 scoreLead 2.5 prior 0.2 pv D4");

      assertEquals(1, engine.getBestMoves().size());
      assertEquals("D4", engine.getBestMoves().get(0).coordinate);
      assertEquals(40, engine.getBestMoves().get(0).playouts);
      assertEquals(1, frame.analysisRefreshCount);
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
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
