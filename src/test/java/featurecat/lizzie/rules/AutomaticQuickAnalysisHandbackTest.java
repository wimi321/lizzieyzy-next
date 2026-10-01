package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class AutomaticQuickAnalysisHandbackTest {
  @Test
  void setupCompletionPreservesRootAndReturnsPrimaryForFurtherAnalysis() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(5)) {
      Lizzie.board.setHistory(
          SGFParser.parseSgf("(;SZ[5]KM[7.5];AB[aa]AW[ee]PL[B];B[bb];W[])", true));
      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      BoardHistoryNode setup = root.next().orElseThrow();
      BoardHistoryNode move = setup.next().orElseThrow();
      BoardHistoryNode pass = move.next().orElseThrow();
      assertEquals(BoardNodeKind.SNAPSHOT, setup.getData().getNodeKind());
      assertEquals(0, setup.getData().moveNumber);
      Lizzie.frame = RulesLayerTestHarness.allocate(QuietFrame.class);
      var previousConsole = Lizzie.gtpConsole;
      Lizzie.gtpConsole = RulesLayerTestHarness.allocate(GtpConsolePane.class);
      var previousToolbar = LizzieFrame.toolbar;
      LizzieFrame.toolbar = RulesLayerTestHarness.allocate(BottomToolbar.class);
      Lizzie.config.analysisReuseCurrentEngine = true;
      Lizzie.config.analysisMaxVisits = 1;
      Leelaz foreground = new Leelaz("");
      foreground.isLoaded = true;
      foreground.started = true;
      foreground.isKatago = true;
      foreground.commandLists.addAll(
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
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      field(foreground, Leelaz.class, "outputStream", new BufferedOutputStream(output));
      field(foreground, Leelaz.class, "endGetCommandList", true);
      Lizzie.setPrimaryEngine(foreground);
      EngineManager.isEmpty = false;
      AnalysisEngine analysis = AnalysisEngine.createAutomaticQuickAnalysis();
      AtomicInteger completed = new AtomicInteger();
      analysis.setCompletionCallback(completed::incrementAndGet);
      try {
        assertEquals(2, analysis.startRequestMissingMainline(false));
        Peer peer = new Peer(foreground, output);
        peer.until(() -> completed.get() == 1);
        assertSame(root, Lizzie.board.getHistory().getCurrentHistoryNode());
        assertEquals(64, move.getData().getPlayouts());
        assertEquals(64, pass.getData().getPlayouts());
        assertEquals("C3", move.getData().bestMoves.get(0).coordinate);
        assertFalse(foreground.hasForegroundAnalysisLeaseWorkInProgress());
        assertTrue(foreground.started);
        var navigation = new FutureTask<Boolean>(() -> Lizzie.board.nextMove(true));
        Thread navigationThread = new Thread(navigation, "handback-test-navigation");
        navigationThread.setDaemon(true);
        navigationThread.start();
        peer.until(navigation::isDone);
        assertTrue(navigation.get());
        assertSame(setup, Lizzie.board.getHistory().getCurrentHistoryNode());
        foreground.ponder();
        peer.until(() -> setup.getData().getPlayouts() == 64);
        assertEquals("C3", setup.getData().bestMoves.get(0).coordinate);
        assertEquals(62.0, setup.getData().winrate, 0.001);
      } finally {
        analysis.normalQuit();
        Lizzie.gtpConsole = previousConsole;
        LizzieFrame.toolbar = previousToolbar;
      }
    }
  }

  private static void field(Object target, Class<?> owner, String name, Object value)
      throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static final class Peer {
    private final Leelaz engine;
    private final ByteArrayOutputStream output;
    private final Method parse;
    private final Method exclusive;
    private int consumed;

    Peer(Leelaz engine, ByteArrayOutputStream output) throws Exception {
      this.engine = engine;
      this.output = output;
      parse = Leelaz.class.getDeclaredMethod("dispatchReaderLineForTest", String.class);
      parse.setAccessible(true);
      exclusive = Leelaz.class.getDeclaredMethod("dispatchExclusiveGtpLine", String.class);
      exclusive.setAccessible(true);
    }

    private void receive(String line) throws Exception {
      if (!(boolean) exclusive.invoke(engine, line)) parse.invoke(engine, line);
    }

    void until(BooleanSupplier done) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!done.getAsBoolean() && System.nanoTime() < deadline) {
        String[] lines = output.toString(StandardCharsets.UTF_8).split("\n");
        if (consumed < lines.length && !lines[consumed].isEmpty()) {
          String line = lines[consumed++];
          int separator = line.indexOf(' ');
          boolean numbered = separator > 0 && Character.isDigit(line.charAt(0));
          String id = numbered ? line.substring(0, separator) : "";
          String command = numbered ? line.substring(separator + 1) : line;
          receive(
              "="
                  + id
                  + (command.equals("kata-get-rules")
                      ? " {\"koRule\":\"POSITIONAL\",\"scoringRule\":\"AREA\",\"taxRule\":\"NONE\"}"
                      : command.equals("name") ? " KataGo" : ""));
          if (command.startsWith("kata-analyze")) {
            receive(
                "info move C3 visits 64 winrate 0.62 scoreMean 1.5 scoreStdev 1 prior 0.5 lcb 0.6 utility 0.2 order 0 pv C3 rootInfo visits 64 winrate 0.62 scoreLead 1.5");
          }
          receive("");
        } else {
          Thread.sleep(10);
        }
        SwingUtilities.invokeAndWait(() -> {});
      }
      assertTrue(done.getAsBoolean(), output.toString(StandardCharsets.UTF_8));
    }
  }

  static class QuietFrame extends LizzieFrame {
    @Override
    public void refresh() {}

    @Override
    public void requestAnalysisRefresh() {}

    @Override
    public void requestProblemListRefresh() {}

    @Override
    public void renderVarTree(int x, int y, boolean a, boolean b) {}
  }
}
