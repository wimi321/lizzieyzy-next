package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.ZhiziLoopbackAcceptanceServer;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.awt.Robot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Opt-in native app acceptance; the cloud protocol is real, the server/account are local fixtures.
 */
public class ZhiziQuickAnalysisAcceptanceIT {
  @Test
  void foxImportCompletesRemoteCurveAndHandsBackForeground() throws Exception {
    run("normal");
  }

  @Test
  void foxImportWhileRemoteIsConnectingStillCompletes() throws Exception {
    run("startup");
  }

  @Test
  void remoteReconnectDuringImportDoesNotStrandCurve() throws Exception {
    run("reconnect");
  }

  @RepeatedTest(3)
  void remoteReconnectDuringCurveCompletesRemainingMoves() throws Exception {
    run("running-reconnect");
  }

  @Test
  void explicitPauseDuringRemoteCurveIsPreserved() throws Exception {
    run("pause");
  }

  @Test
  void disabledAutomaticCurveDoesNotAcquireRemoteWorker() throws Exception {
    run("disabled");
  }

  private void run(String mode) throws Exception {
    assumeTrue(Boolean.getBoolean("lizzie.acceptance.zhiziLoopback"));
    DesktopProbeProcess.requireDisplay();
    String engine = System.getProperty("lizzie.acceptance.engine", "");
    String model = System.getProperty("lizzie.acceptance.model", "");
    assertTrue(Files.isRegularFile(Path.of(engine)));
    assertTrue(Files.isRegularFile(Path.of(model)));
    Path result =
        DesktopProbeProcess.run(
            getClass(), "zhizi-curve-" + mode, List.of(), List.of(mode, engine, model), 240);
    assertTrue(Files.readString(result).contains("result=PASS"));
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
      printFailureState();
      Files.writeString(result, "result=FAIL\nerror=" + failure + "\n");
      if (Lizzie.frame != null && Lizzie.frame.isShowing())
        ImageIO.write(
            new Robot().createScreenCapture(Lizzie.frame.getBounds()),
            "png",
            result.resolveSibling("failure.png").toFile());
    } finally {
      System.exit(exit);
    }
  }

  private static void printFailureState() {
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            Leelaz engine = Lizzie.leelaz;
            if (engine == null || Lizzie.frame == null) return;
            System.err.println(
                "Remote acceptance state: loaded="
                    + engine.isLoaded()
                    + " started="
                    + engine.isStarted()
                    + " failed="
                    + engine.isDownWithError
                    + " recovery="
                    + engine.isRemoteSessionRecoveryRequested()
                    + " pondering="
                    + engine.isPondering()
                    + " paused="
                    + Lizzie.frame.isUserAnalysisPaused()
                    + " readiness="
                    + Lizzie.frame.automaticQuickAnalysisReadiness()
                    + " analyzed="
                    + allMovesAnalyzed());
            try {
              var field = LizzieFrame.class.getDeclaredField("automaticQuickAnalysisTask");
              field.setAccessible(true);
              var task =
                  (featurecat.lizzie.analysis.AutomaticQuickAnalysisTask) field.get(Lizzie.frame);
              if (task != null) {
                System.err.println(
                    "Automatic task: active="
                        + task.isActive()
                        + " retrying="
                        + task.isRetrying()
                        + " restore="
                        + task.requiresForegroundRestore());
                task.whenSettled(result -> System.err.println("Settlement: " + result));
              }
            } catch (ReflectiveOperationException error) {
              error.printStackTrace();
            }
          });
    } catch (Exception error) {
      error.printStackTrace();
    }
  }

  private static void probe(String mode, Path engine, Path model, Path work, Path result)
      throws Exception {
    JSONObject entry =
        new JSONObject()
            .put("command", "remote-compute://zhizi")
            .put("name", "Zhizi protocol acceptance")
            .put("width", 19)
            .put("height", 19)
            .put("komi", 7.5);
    JSONObject ui =
        new JSONObject()
            .put("autoload-empty", true)
            .put("first-time-load", false)
            .put("use-language", 1)
            .put("auto-quick-analyze-on-load", !mode.equals("disabled"))
            .put("analysis-reuse-current-engine", false)
            .put("quick-analysis-lightweight-model-enabled", false)
            .put("analysis-engine-command", "")
            .put("analysis-engine-preload", false)
            .put("play-sound", false);
    Files.writeString(
        work.resolve("config.txt"),
        new JSONObject()
            .put("ui", ui)
            .put("leelaz", new JSONObject().put("engine-settings-list", new JSONArray().put(entry)))
            .toString());
    Path config = work.resolve("gtp.cfg");
    Files.writeString(
        config,
        "rules = chinese\nnumSearchThreads = 4\nnnMaxBatchSize = 4\n"
            + "nnCacheSizePowerOfTwo = 16\nnnMutexPoolSizePowerOfTwo = 12\nlogToStderr = true\n"
            + "logAllGTPCommunication = true\nlogSearchInfo = true\nlogSearchInfoForChosenMove = false\n"
            + "ponderingEnabled = false\nallowResignation = true\nresignThreshold = -0.90\nresignConsecTurns = 3\n");
    System.setProperty("lizzie.work.dir", work.toString());
    Lizzie.main(new String[0]);
    await(() -> Lizzie.frame != null && Lizzie.frame.isShowing(), 30, "main window");
    try (ZhiziLoopbackAcceptanceServer server =
        new ZhiziLoopbackAcceptanceServer(engine, model, config)) {
      if (mode.equals("startup")) server.readyDelayMillis = 3000;
      Leelaz[] primary = new Leelaz[1];
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              primary[0] = server.installPrimary();
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      if (!mode.equals("startup"))
        await(
            () ->
                primary[0].isLoaded()
                    && Lizzie.engineManager.engineSwitchUiSnapshot(true).phase()
                        == featurecat.lizzie.analysis.EngineManager.EngineSwitchUiPhase.ACTIVE,
            70,
            "remote ready and startup board restored");
      DesktopProbeProcess.phase(result, "fox-import");
      if (mode.equals("reconnect")) server.disconnect();
      loadFox("(;FF[4]GM[1]SZ[19]KM[7.5]RU[Chinese]GN[fox-fixture];B[pd];W[dd];B[qp];W[dq])");
      if (mode.equals("disabled")) {
        await(() -> primary[0].isPondering(), 30, "foreground without automatic curve");
        Thread.sleep(2500);
        assertFalse(allMovesAnalyzed());
        assertTrue(
            Lizzie.frame.analysisEngine == null
                || !Lizzie.frame.analysisEngine.hasRequestLifecycleInProgress());
        assertEquals(1, server.sessions.get());
        screenshot(result);
        Files.writeString(result, "result=PASS\nmode=disabled\n");
        primary[0].normalQuit();
        return;
      }
      if (mode.equals("running-reconnect") || mode.equals("pause")) {
        await(
            () ->
                Lizzie.frame.analysisEngine != null
                    && Lizzie.frame.analysisEngine.hasRequestLifecycleInProgress(),
            40,
            "remote curve running");
        if (mode.equals("running-reconnect")) server.disconnect();
        else {
          SwingUtilities.invokeAndWait(() -> Lizzie.frame.togglePonderMannul());
          await(
              () ->
                  Lizzie.frame.isUserAnalysisPaused()
                      && !primary[0].isPondering()
                      && (Lizzie.frame.analysisEngine == null
                          || !Lizzie.frame.analysisEngine.hasRequestLifecycleInProgress()),
              30,
              "pause releases lease");
          Thread.sleep(3000);
          assertTrue(Lizzie.frame.isUserAnalysisPaused());
          assertFalse(primary[0].isPondering());
          screenshot(result);
          Files.writeString(result, "result=PASS\nmode=pause\n");
          primary[0].normalQuit();
          return;
        }
      }
      await(
          () ->
              allMovesAnalyzed()
                  && primary[0].isPondering()
                  && (Lizzie.frame.analysisEngine == null
                      || !Lizzie.frame.analysisEngine.hasRequestLifecycleInProgress()),
          100,
          "automatic remote curve and foreground handback");
      assertSame(primary[0], Lizzie.leelaz);
      BoardHistoryNode current = Lizzie.board.getHistory().getCurrentHistoryNode();
      int visits = current.getData().getPlayouts();
      await(() -> current.getData().getPlayouts() > visits, 15, "foreground visits growing");
      if (mode.endsWith("reconnect"))
        assertTrue(server.tokens.get() >= 2, "Fresh token after reconnect");
      if (mode.equals("normal")) {
        loadFox(
            "(;FF[4]GM[1]SZ[19]KM[6.5]RU[Japanese]GN[second];B[dp];W[pp];B[dc];W[pc];B[jj];W[qj])");
        SwingUtilities.invokeAndWait(() -> Lizzie.board.goToMoveNumber(2));
        await(
            () -> allMovesAnalyzed() && primary[0].isPondering(),
            80,
            "second import after navigation");
        assertEquals(1, server.sessions.get(), "Do not create a second cloud worker for the curve");
      }
      screenshot(result);
      Files.writeString(result.resolveSibling("commands.txt"), String.join("\n", server.commands));
      Files.writeString(
          result, "result=PASS\nmode=" + mode + "\nsessions=" + server.sessions.get() + "\n");
      primary[0].normalQuit();
    }
  }

  private static void screenshot(Path result) throws Exception {
    ImageIO.write(
        new Robot().createScreenCapture(Lizzie.frame.getBounds()),
        "png",
        result.resolveSibling("window.png").toFile());
  }

  private static void loadFox(String sgf) throws Exception {
    AtomicBoolean loaded = new AtomicBoolean();
    SwingUtilities.invokeAndWait(
        () ->
            Lizzie.frame.loadDownloadedSgfString(
                sgf,
                0,
                true,
                false,
                null,
                success -> {
                  assertTrue(success);
                  loaded.set(true);
                }));
    await(loaded::get, 20, "Fox downloaded SGF parsing");
  }

  private static boolean allMovesAnalyzed() {
    BoardHistoryNode node = Lizzie.board.getHistory().getStart();
    int count = 0;
    while (node.next().isPresent()) {
      node = node.next().orElseThrow();
      if (!node.getData().hasDisplayablePrimaryAnalysis()) return false;
      count++;
    }
    return count > 0;
  }

  private static void await(BooleanSupplier condition, int seconds, String stage) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
    assertTrue(condition.getAsBoolean(), stage);
  }
}
