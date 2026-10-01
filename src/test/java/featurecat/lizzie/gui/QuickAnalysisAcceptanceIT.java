package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.awt.Dialog;
import java.awt.Robot;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Explicit local acceptance with a real KataGo executable and model, not a default unit test. */
public class QuickAnalysisAcceptanceIT {
  @Test
  public void resumesForegroundAfterImportedGame() throws Exception {
    run("complete");
  }

  @Test
  public void analyzesGamePassedAtProcessStartup() throws Exception {
    run("startup");
  }

  @Test
  public void preservesExplicitPauseDuringQuickAnalysis() throws Exception {
    run("pause");
  }

  @Test
  public void ordinaryBatchAnalyzesEachFileAndRetriesAfterLoadFailure() throws Exception {
    run("batch");
  }

  private void run(String mode) throws Exception {
    DesktopProbeProcess.requireDisplay();
    String engine = System.getProperty("lizzie.acceptance.engine", "");
    String model = System.getProperty("lizzie.acceptance.model", "");
    assertTrue(Files.isRegularFile(Path.of(engine)), "Real engine path is required");
    assertTrue(Files.isRegularFile(Path.of(model)), "Real model path is required");
    Path result =
        DesktopProbeProcess.run(
            QuickAnalysisAcceptanceIT.class,
            "quick-analysis-" + mode,
            List.of(),
            List.of(
                mode,
                Path.of(engine).toAbsolutePath().toString(),
                Path.of(model).toAbsolutePath().toString()),
            180);
    assertTrue(Files.readString(result).contains("result=PASS\n"));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[3]);
    Path result = Path.of(args[4]);
    int exit = 1;
    try {
      probe(args[0], Path.of(args[1]), Path.of(args[2]), work, result);
      exit = 0;
    } catch (Throwable failure) {
      failure.printStackTrace();
      Files.writeString(result, "result=FAIL\nerror=" + failure + "\n");
    } finally {
      System.exit(exit);
    }
  }

  private static void probe(String mode, Path engine, Path model, Path work, Path result)
      throws Exception {
    Path config = work.resolve("gtp.cfg");
    Files.writeString(
        config,
        "rules = tromp-taylor\nnumSearchThreads = 4\n"
            + "nnMaxBatchSize = 4\nnnCacheSizePowerOfTwo = 16\n"
            + "nnMutexPoolSizePowerOfTwo = 12\nlogToStderr = true\n"
            + "logAllGTPCommunication = true\nlogSearchInfo = true\n"
            + "logSearchInfoForChosenMove = false\nponderingEnabled = false\n"
            + "allowResignation = true\nresignThreshold = -0.90\nresignConsecTurns = 3\n");
    String command = quote(engine) + " gtp -model " + quote(model) + " -config " + quote(config);
    JSONObject entry =
        new JSONObject()
            .put("command", command)
            .put("name", "Acceptance CPU")
            .put("isDefault", true)
            .put("width", 19)
            .put("height", 19)
            .put("komi", 7.5);
    JSONObject ui =
        new JSONObject()
            .put("autoload-default", true)
            .put("first-time-load", false)
            .put("use-language", 2)
            .put("auto-quick-analyze-on-load", true)
            .put("analysis-reuse-current-engine", true)
            .put("analysis-max-visits", 1)
            .put("quick-analysis-lightweight-model-enabled", false);
    Files.writeString(
        work.resolve("config.txt"),
        new JSONObject()
            .put("leelaz", new JSONObject().put("engine-settings-list", new JSONArray().put(entry)))
            .put("ui", ui)
            .toString(2));
    Path fixture = work.resolve("import-game.sgf");
    Files.writeString(fixture, "(;FF[4]GM[1]SZ[19]KM[7.5];B[pd];W[dd];B[qp];W[dq])");
    System.setProperty("lizzie.work.dir", work.toString());
    DesktopProbeProcess.phase(result, "production-startup");
    Lizzie.main(mode.equals("startup") ? new String[] {fixture.toString()} : new String[0]);
    await(
        () ->
            Lizzie.frame != null
                && Lizzie.frame.isShowing()
                && Lizzie.leelaz != null
                && Lizzie.leelaz.isLoaded(),
        70,
        "real engine startup");
    edt(
        () -> {
          for (Window window : Window.getWindows()) {
            if (window instanceof Dialog) window.dispose();
          }
        });
    Leelaz primary = Lizzie.leelaz;
    if (mode.equals("startup")) {
      await(
          () -> allMovesAnalyzed()
              && primary.isPondering()
              && (Lizzie.frame.analysisEngine == null
                  || !Lizzie.frame.analysisEngine.hasRequestLifecycleInProgress()),
          45,
          "startup SGF quick analysis and foreground handback");
      ImageIO.write(new Robot().createScreenCapture(Lizzie.frame.getBounds()), "png",
          result.getParent().resolve("window.png").toFile());
      primary.normalQuit();
      Files.writeString(result, "result=PASS\nmode=startup\n");
      return;
    }
    await(
        () ->
            primary.isPondering()
                && Lizzie.board.getHistory().getCurrentHistoryNode().getData().getPlayouts() > 0,
        30,
        "initial foreground analysis usable");
    if (mode.equals("batch")) {
      probeBatch(work, result);
      ImageIO.write(new Robot().createScreenCapture(Lizzie.frame.getBounds()), "png",
          result.getParent().resolve("window.png").toFile());
      primary.normalQuit();
      Files.writeString(result, "result=PASS\nmode=batch\n");
      return;
    }
    DesktopProbeProcess.phase(result, "import-sgf");
    edt(
        () ->
            assertTrue(
                Lizzie.frame.loadFile(fixture.toFile(), false, true), "Production SGF import"));
    await(
        () ->
            Lizzie.frame.analysisEngine != null
                && Lizzie.frame.analysisEngine.usesSharedForegroundEngine()
                && Lizzie.frame.analysisEngine.hasRequestLifecycleInProgress(),
        40,
        "quick analysis owns engine");
    DesktopProbeProcess.phase(result, "quick-analysis-observed");
    if (mode.equals("pause")) {
      edt(() -> Lizzie.frame.togglePonderMannul());
      await(
          () ->
              Lizzie.frame.isUserAnalysisPaused()
                  && !primary.isPondering()
                  && (Lizzie.frame.analysisEngine == null
                      || !Lizzie.frame.analysisEngine.hasRequestLifecycleInProgress()),
          30,
          "pause cleanup");
      long quietUntil = System.nanoTime() + Duration.ofSeconds(3).toNanos();
      while (System.nanoTime() < quietUntil) {
        assertTrue(Lizzie.frame.isUserAnalysisPaused(), "Explicit pause remains authoritative");
        assertFalse(primary.isPondering(), "Cleanup must not resume pondering");
        Thread.sleep(100);
      }
      DesktopProbeProcess.phase(result, "explicit-pause-preserved");
    } else {
      await(
          () ->
              allMovesAnalyzed()
                  && primary.isPondering()
                  && (Lizzie.frame.analysisEngine == null
                      || !Lizzie.frame.analysisEngine.hasRequestLifecycleInProgress()),
          70,
          "completion and handback");
      assertSame(primary, Lizzie.leelaz, "Foreground engine reused");
      BoardHistoryNode target = Lizzie.board.getHistory().getCurrentHistoryNode();
      int before = target.getData().getPlayouts();
      await(
          () -> target.getData().getPlayouts() > before,
          15,
          "ordinary analysis visits grow after handback");
      Files.writeString(
          result.getParent().resolve("visits.txt"),
          before + " -> " + target.getData().getPlayouts() + "\n");
      DesktopProbeProcess.phase(result, "foreground-visits-growing");
    }
    ImageIO.write(
        new Robot().createScreenCapture(Lizzie.frame.getBounds()),
        "png",
        result.getParent().resolve("window.png").toFile());
    primary.normalQuit();
    Files.writeString(result, "result=PASS\nmode=" + mode + "\n");
  }

  private static void probeBatch(Path work, Path result) throws Exception {
    Path first = work.resolve("batch-first.sgf");
    Path second = work.resolve("batch-second.sgf");
    Files.writeString(first, "(;FF[4]GM[1]SZ[19]KM[7.5]GN[first];B[pd];W[dd])");
    Files.writeString(second, "(;FF[4]GM[1]SZ[19]KM[7.5]GN[second];B[qp];W[dq])");
    SwingUtilities.invokeLater(() -> Lizzie.frame.openOrdinaryBatchAnalysis(List.of(first.toFile()), false));
    await(() -> batchDialog() != null, 30, "ordinary settings cancellation");
    edt(() -> {
      StartAnaDialog dialog = batchDialog();
      dialog.dispatchEvent(new java.awt.event.WindowEvent(dialog, java.awt.event.WindowEvent.WINDOW_CLOSING));
      assertFalse(Lizzie.frame.isBatchAna, "Window close releases ordinary batch");
      assertTrue(Lizzie.frame.Batchfiles.isEmpty(), "Window close clears ordinary queue");
      assertSame(LizzieFrame.toolbar.anaPanel, LizzieFrame.toolbar.txtFirstAnaMove.getParent(),
          "Window close returns the shared controls to the toolbar");
      Lizzie.frame.Batchfiles = new java.util.ArrayList<>(List.of(first.toFile()));
      Lizzie.frame.isBatchAna = true;
      StartAnaDialog flash = new StartAnaDialog(true, Lizzie.frame);
      flash.stop();
      assertFalse(Lizzie.frame.isBatchAna, "Flash settings stop releases its preparation");
      assertSame(LizzieFrame.toolbar.anaPanel, LizzieFrame.toolbar.txtFirstAnaMove.getParent());
    });
    DesktopProbeProcess.phase(result, "ordinary-close-and-flash-cancel-restored");
    runBatch(List.of(first), result, "single");
    assertSavedBatchGame(work, "batch-first", "GN[first]", "B[pd]");
    try (var saved = Files.list(work)) {
      for (Path path : saved.filter(p -> p.getFileName().toString().startsWith("batch-first_")).toList()) {
        Files.delete(path);
      }
    }
    runBatch(List.of(first, second), result, "multiple");
    assertSavedBatchGame(work, "batch-first", "GN[first]", "B[pd]");
    assertSavedBatchGame(work, "batch-second", "GN[second]", "B[qp]");
    DesktopProbeProcess.phase(result, "batch-load-failure");
    edt(() -> Lizzie.frame.openOrdinaryBatchAnalysis(
        List.of(work.resolve("missing.sgf").toFile()), false));
    await(() -> !Lizzie.frame.isBatchAna && !Lizzie.frame.isManualAutoAnalysisStarting(),
        20, "failed load releases batch");
    assertTrue(Lizzie.frame.Batchfiles.isEmpty(), "Failed load clears queue");
    edt(() -> {
      for (Window window : Window.getWindows()) if (window instanceof Dialog) window.dispose();
    });
    runBatch(List.of(second), result, "retry");
    assertFalse(Lizzie.frame.isManualAutoAnalysisStarting());
    Path flashFile = work.resolve("batch-flash.sgf");
    Files.writeString(flashFile, "(;FF[4]GM[1]SZ[19]KM[7.5]GN[flash];B[pd];W[dd])");
    edt(() -> {
      Lizzie.frame.Batchfiles = new java.util.ArrayList<>(List.of(flashFile.toFile()));
      Lizzie.frame.BatchAnaNum = 0;
      Lizzie.frame.isBatchAna = true;
      assertTrue(Lizzie.frame.loadFile(flashFile.toFile(), false, true));
    });
    edt(() -> {});
    await(() -> LizzieFrame.canGoAfterload, 30, "flash file engine alignment");
    edt(() -> {
      Lizzie.config.batchAnalysisPlayouts = 2;
      StartAnaDialog flash = new StartAnaDialog(true, Lizzie.frame);
      flash.apply();
    });
    await(() -> !Lizzie.frame.isBatchAna && !Lizzie.frame.isBatchAnalysisMode, 45,
        "flash batch completion");
    assertSavedBatchGame(work, "batch-flash", "GN[flash]", "B[pd]");
    DesktopProbeProcess.phase(result, "flash-batch-complete");
  }

  private static void runBatch(List<Path> files, Path result, String phase) throws Exception {
    DesktopProbeProcess.phase(result, "batch-" + phase + "-open");
    SwingUtilities.invokeLater(() -> Lizzie.frame.openOrdinaryBatchAnalysis(
        files.stream().map(Path::toFile).toList(), false));
    await(() -> batchDialog() != null, 30, "batch settings " + phase);
    edt(() -> {
      StartAnaDialog dialog = batchDialog();
      LizzieFrame.toolbar.chkAnaBlack.setSelected(true);
      LizzieFrame.toolbar.chkAnaWhite.setSelected(true);
      LizzieFrame.toolbar.txtAnaTime.setText("");
      LizzieFrame.toolbar.txtAnaFirstPlayouts.setText("");
      LizzieFrame.toolbar.txtAnaPlayouts.setText("2");
      LizzieFrame.toolbar.txtFirstAnaMove.setText("");
      LizzieFrame.toolbar.txtLastAnaMove.setText("");
      dialog.apply();
    });
    await(() -> !Lizzie.frame.isBatchAna && !Lizzie.config.isAutoAna
            && !Lizzie.frame.isManualAutoAnalysisStarting(),
        45, "batch completion " + phase);
    assertTrue(Lizzie.frame.Batchfiles.isEmpty(), "Completed batch clears queue");
    assertTrue(LizzieFrame.toolbar.start.isEnabled(), "Start control is restored");
    DesktopProbeProcess.phase(result, "batch-" + phase + "-complete");
  }

  private static StartAnaDialog batchDialog() {
    for (Window window : Window.getWindows()) {
      if (window instanceof StartAnaDialog && window.isShowing()) return (StartAnaDialog) window;
    }
    return null;
  }

  private static void assertSavedBatchGame(Path work, String prefix, String name, String move)
      throws Exception {
    List<Path> outputs;
    try (var paths = Files.list(work)) {
      outputs = paths.filter(p -> p.getFileName().toString().startsWith(prefix + "_")).toList();
    }
    assertTrue(outputs.size() == 1, "Exactly one saved result for " + prefix + ": " + outputs);
    String sgf = Files.readString(outputs.get(0));
    assertTrue(sgf.contains(name) && sgf.contains(move), "Saved result belongs to " + prefix);
    assertTrue(sgf.contains("LZ["), "Saved result contains engine analysis for " + prefix);
  }

  private static boolean allMovesAnalyzed() {
    BoardHistoryNode node = Lizzie.board.getHistory().getStart();
    int moves = 0;
    while (node.next().isPresent()) {
      node = node.next().get();
      if (!node.getData().hasDisplayablePrimaryAnalysis()) return false;
      moves++;
    }
    return moves == 4;
  }

  private static String quote(Path path) {
    return "\"" + path.toAbsolutePath() + "\"";
  }

  private static void edt(Runnable action) throws Exception {
    FutureTask<Void> task = new FutureTask<>(action, null);
    SwingUtilities.invokeLater(task);
    task.get(15, TimeUnit.SECONDS);
  }

  private static void await(BooleanSupplier condition, int seconds, String description)
      throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
    while (System.nanoTime() < until) {
      if (condition.getAsBoolean()) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Timed out: " + description);
  }
}
