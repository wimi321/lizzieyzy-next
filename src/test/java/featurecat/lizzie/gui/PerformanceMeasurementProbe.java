package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.PerformanceProbeEngineAccess;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Opt-in measurement entry point. Launched by measure_analysis.py in a fresh isolated directory.
 */
public final class PerformanceMeasurementProbe {
  private static final List<Double> UI_DELAYS = Collections.synchronizedList(new ArrayList<>());
  private static final AtomicBoolean MEASURING = new AtomicBoolean();
  private static final AtomicLong MEASUREMENT_EPOCH = new AtomicLong();

  private static void startSampling() {
    MEASURING.set(false);
    MEASUREMENT_EPOCH.incrementAndGet();
    UI_DELAYS.clear();
    MEASURING.set(true);
  }

  private static void stopSampling() throws Exception {
    // Drain events queued before completion; never mix pause/cancel latency into search latency.
    edt(
        () -> {
          MEASURING.set(false);
          return null;
        });
  }

  private static final class MeasuredAnalysisEngine extends AnalysisEngine {
    private volatile CountDownLatch cacheCleared;
    private String effectiveCommand;

    MeasuredAnalysisEngine(int visits) throws java.io.IOException {
      super(false, AnalysisEngine.Workload.WHOLE_GAME, visits);
    }

    @Override
    public void startEngine(String command) {
      effectiveCommand = command;
      super.startEngine(command);
    }

    @Override
    public void parseResult(String line) {
      JSONObject value = new JSONObject(line);
      if (value.optString("id").equals("measurement-clear")
          && value.optString("action").equals("clear_cache")) {
        cacheCleared.countDown();
      } else {
        super.parseResult(line);
      }
    }

    void clearCache() throws Exception {
      cacheCleared = new CountDownLatch(1);
      sendCommand(
          new JSONObject().put("id", "measurement-clear").put("action", "clear_cache").toString());
      if (!cacheCleared.await(30, TimeUnit.SECONDS))
        throw new AssertionError("Cache clear timeout");
    }
  }

  private static <T> T edt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(30, TimeUnit.SECONDS);
  }

  private static void await(BooleanSupplier ready, int timeout) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
    while (!ready.getAsBoolean()) {
      if (System.nanoTime() > deadline) throw new AssertionError("Measurement timeout");
      Thread.sleep(5);
    }
  }

  private static double seconds(long start) {
    return (System.nanoTime() - start) / 1e9;
  }

  private static void clearAnalysis() {
    BoardHistoryNode node = Lizzie.board.getHistory().getStart();
    do {
      node.getData().clearAnalysisPayloadState();
      node = node.next().orElse(null);
    } while (node != null);
  }

  private static String sgf(JSONObject fixture) {
    String columns = "ABCDEFGHJKLMNOPQRST";
    int size = fixture.getInt("boardSize");
    StringBuilder sgf =
        new StringBuilder(
            "(;FF[4]GM[1]SZ["
                + size
                + "]KM["
                + fixture.getDouble("komi")
                + "]RU["
                + fixture.getString("rules")
                + "]");
    for (Object item : fixture.getJSONArray("moves")) {
      JSONArray move = (JSONArray) item;
      String vertex = move.getString(1);
      if (vertex.equalsIgnoreCase("pass")) sgf.append(';').append(move.getString(0)).append("[]");
      else
        sgf.append(';')
            .append(move.getString(0))
            .append('[')
            .append((char) ('a' + columns.indexOf(vertex.charAt(0))))
            .append((char) ('a' + size - Integer.parseInt(vertex.substring(1))))
            .append(']');
    }
    return sgf.append(')').toString();
  }

  private static void verifyFixture(JSONObject fixture) {
    if (featurecat.lizzie.rules.Board.boardWidth != fixture.getInt("boardSize")
        || featurecat.lizzie.rules.Board.boardHeight != fixture.getInt("boardSize")
        || Double.compare(
                Lizzie.board.getHistory().getGameInfo().getKomi(), fixture.getDouble("komi"))
            != 0) throw new AssertionError("Fixture board size or komi differs");
    String columns = "ABCDEFGHJKLMNOPQRST";
    BoardHistoryNode node = Lizzie.board.getHistory().getStart();
    for (Object item : fixture.getJSONArray("moves")) {
      JSONArray move = (JSONArray) item;
      node = node.next().orElseThrow(() -> new AssertionError("Fixture move missing"));
      String vertex = move.getString(1);
      boolean black = move.getString(0).equals("B");
      if (node.getData().lastMoveColor.isBlack() != black || node.getData().blackToPlay == black)
        throw new AssertionError("Fixture player differs");
      if (vertex.equalsIgnoreCase("pass")) {
        if (node.getData().lastMove.isPresent()) throw new AssertionError("Expected pass");
      } else {
        int[] coordinate = node.getData().lastMove.orElseThrow();
        if (coordinate[0] != columns.indexOf(vertex.charAt(0))
            || coordinate[1] != fixture.getInt("boardSize") - Integer.parseInt(vertex.substring(1)))
          throw new AssertionError("Fixture move differs");
      }
    }
    if (node.next().isPresent()) throw new AssertionError("Unexpected fixture continuation");
  }

  private static JSONObject realtime(Path game, JSONObject fixture, int visits) throws Exception {
    Leelaz primary = Lizzie.leelaz;
    edt(
        () -> {
          if (primary.isPondering()) Lizzie.frame.togglePonderMannul();
          Lizzie.frame.loadFile(game.toFile(), false, true);
          return null;
        });
    // Loading schedules board synchronization on the next EDT turn; wait for it to settle.
    await(() -> Lizzie.frame.canGoAfterload, 30);
    edt(
        () -> {
          verifyFixture(fixture);
          if (primary.isPondering()) Lizzie.frame.togglePonderMannul();
          Lizzie.board.goToMoveNumber(Math.max(0, fixture.getJSONArray("moves").length() - 1));
          clearAnalysis();
          if (!primary.isPondering()) Lizzie.frame.togglePonderMannul();
          return null;
        });
    PerformanceProbeEngineAccess.barrier(primary, "clear_cache");
    startSampling();
    long start = System.nanoTime();
    edt(
        () -> {
          JSONArray moves = fixture.getJSONArray("moves");
          if (!moves.isEmpty())
            Lizzie.board.place(moves.getJSONArray(moves.length() - 1).getString(1));
          else primary.ponder();
          return null;
        });
    await(() -> Lizzie.frame.getDisplayNode().getData().rootVisits > 0, 120);
    double first = seconds(start);
    await(() -> Lizzie.frame.getDisplayNode().getData().rootVisits >= visits, 120);
    double elapsed = seconds(start);
    stopSampling();
    int observed = Lizzie.frame.getDisplayNode().getData().rootVisits;
    if (Lizzie.frame.getDisplayNode().getData().moveNumber
        != fixture.getJSONArray("moves").length())
      throw new AssertionError("Wrong realtime fixture position");
    long pause = System.nanoTime();
    edt(
        () -> {
          Lizzie.frame.togglePonderMannul();
          return null;
        });
    PerformanceProbeEngineAccess.barrier(primary, "name");
    return new JSONObject()
        .put("seconds", elapsed)
        .put("firstResultSeconds", first)
        .put("observedRootVisits", observed)
        .put("visitsPerSecond", observed / elapsed)
        .put("pauseAckSeconds", seconds(pause))
        .put("effectiveCommand", primary.engineCommand());
  }

  private static JSONObject wholeGame(
      Path game, JSONObject fixture, int visits, String command, boolean warm) throws Exception {
    edt(
        () -> {
          Lizzie.frame.loadFile(game.toFile(), false, true);
          verifyFixture(fixture);
          clearAnalysis();
          return null;
        });
    Lizzie.config.analysisEngineCommand = command;
    Lizzie.config.analysisReuseCurrentEngine = false;
    MeasuredAnalysisEngine engine = new MeasuredAnalysisEngine(visits);
    Lizzie.frame.analysisEngine = engine;
    await(engine::isLoaded, 120);
    List<BoardHistoryNode> nodes =
        edt(
            () -> {
              List<BoardHistoryNode> all = new ArrayList<>();
              BoardHistoryNode node = Lizzie.board.getHistory().getStart();
              do {
                all.add(node);
                node = node.next().orElse(null);
              } while (node != null);
              return all;
            });
    try {
      JSONObject result = null;
      for (int repetition = 0; repetition < (warm ? 2 : 1); repetition++) {
        edt(
            () -> {
              clearAnalysis();
              return null;
            });
        engine.clearCache();
        engine.setKeepAliveAfterCurrentRequest(true);
        AtomicBoolean complete = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        engine.setCompletionCallback(() -> complete.set(true));
        engine.setFailureCallback(() -> failed.set(true));
        startSampling();
        long start = System.nanoTime();
        int count = edt(() -> engine.startWholeGameRequest(nodes, visits, true));
        if (count != nodes.size())
          throw new AssertionError("Not all fixture positions submitted: " + count);
        await(() -> complete.get() || failed.get(), 180);
        if (failed.get()) throw new AssertionError("Whole-game request failed");
        double elapsed = seconds(start);
        stopSampling();
        JSONArray observed = new JSONArray();
        for (BoardHistoryNode node : nodes) {
          if (!node.getData().hasCompletePrimaryAnalysis(visits, true))
            throw new AssertionError("Incomplete analysis budget or ownership");
          observed.put(node.getData().getPlayouts());
        }
        result =
            new JSONObject()
                .put("seconds", elapsed)
                .put("positions", count)
                .put("positionsPerSecond", count / elapsed)
                .put("observedVisits", observed)
                .put("effectiveCommand", engine.effectiveCommand);
      }
      // Cancellation is a separate workload and never substitutes for a budget-complete sample.
      edt(() -> engine.startWholeGameRequest(nodes, 1000000000, true));
      Thread.sleep(100);
      long cancel = System.nanoTime();
      engine.normalQuit();
      if (!engine.process.waitFor(10, TimeUnit.SECONDS))
        throw new AssertionError("Cancellation left engine alive");
      result
          .put("cancelCompleteSeconds", seconds(cancel))
          .put("cancellationMethod", "production-worker-process-exit");
      return result;
    } finally {
      engine.normalQuit();
    }
  }

  public static void main(String[] args) throws Exception {
    Path requestPath = Path.of(args[0]).toAbsolutePath();
    Path directory = requestPath.getParent();
    Path output = directory.resolve("app-result.json");
    JSONObject request = new JSONObject(Files.readString(requestPath));
    boolean realtime = request.getString("scene").equals("realtime");
    String command = request.getString("command");
    int visits = request.getInt("visits");
    System.setProperty("lizzie.work.dir", directory.toString());
    System.setProperty("lizzie.analysis.diagnostics", "true");
    System.setProperty(
        "lizzie.analysis.diagnostics.path", directory.resolve("diagnostics.jsonl").toString());
    JSONObject ui =
        new JSONObject()
            .put("autoload-default", realtime)
            .put("first-time-load", false)
            .put("use-language", 2)
            .put("auto-quick-analyze-on-load", false)
            .put("analysis-reuse-current-engine", false)
            .put("show-katago-estimate", true)
            .put("show-pv-visits", true)
            .put("use-moves-ownership", true)
            .put("quick-analysis-lightweight-model-enabled", false)
            .put("confirm-exit", false);
    JSONArray entries = new JSONArray();
    if (realtime)
      entries.put(
          new JSONObject()
              .put("command", command)
              .put("name", "Measurement")
              .put("isDefault", true)
              .put("width", 19)
              .put("height", 19)
              .put("komi", 7.5));
    Files.writeString(
        directory.resolve("config.txt"),
        new JSONObject()
            .put("ui", ui)
            .put("leelaz", new JSONObject().put("engine-settings-list", entries))
            .toString(2));
    Path game = directory.resolve("fixture.sgf");
    Files.writeString(game, sgf(request.getJSONObject("fixture")));
    AtomicBoolean sampling = new AtomicBoolean(true);
    List<Double> uiDelays = UI_DELAYS;
    AtomicReference<Exception> samplerFailure = new AtomicReference<>();
    Thread eventProbe =
        new Thread(
            () -> {
              while (sampling.get()) {
                long sent = System.nanoTime();
                long epoch = MEASUREMENT_EPOCH.get();
                boolean measuring = MEASURING.get();
                try {
                  edt(
                      () -> {
                        if (measuring && MEASURING.get() && epoch == MEASUREMENT_EPOCH.get())
                          uiDelays.add(seconds(sent));
                        return null;
                      });
                  Thread.sleep(20);
                } catch (Exception error) {
                  samplerFailure.set(error);
                  return;
                }
              }
            },
            "measurement-edt-latency");
    eventProbe.setDaemon(true);
    int exit = 1;
    try {
      long startup = System.nanoTime();
      Lizzie.main(new String[0]);
      await(
          () ->
              Lizzie.frame != null
                  && Lizzie.frame.isShowing()
                  && (!realtime
                      || (Lizzie.leelaz != null
                          && Lizzie.leelaz.isLoaded()
                          && (Lizzie.leelaz.moveFocusCapability()
                                  == Leelaz.MoveFocusCapability.SUPPORTED
                              || Lizzie.leelaz.moveFocusCapability()
                                  == Leelaz.MoveFocusCapability.UNSUPPORTED))),
          120);
      double startupSeconds = seconds(startup);
      if (request.getBoolean("warm")) {
        if (realtime) realtime(game, request.getJSONObject("fixture"), visits);
      }
      eventProbe.start();
      JSONObject result =
          realtime
              ? realtime(game, request.getJSONObject("fixture"), visits)
              : wholeGame(
                  game,
                  request.getJSONObject("fixture"),
                  visits,
                  command,
                  request.getBoolean("warm"));
      sampling.set(false);
      eventProbe.join(1000);
      List<Double> ordered = new ArrayList<>(uiDelays);
      if (samplerFailure.get() != null)
        throw new AssertionError("EDT sampling failed", samplerFailure.get());
      if (ordered.isEmpty()) throw new AssertionError("No measured EDT samples");
      Collections.sort(ordered);
      result
          .put("status", "PASS")
          .put("startupSeconds", startupSeconds)
          .put("edtSamples", ordered.size())
          .put("edtLatencySeconds", new JSONArray(ordered))
          .put(
              "javaHeapUsedBytes",
              java.lang.management.ManagementFactory.getMemoryMXBean()
                  .getHeapMemoryUsage()
                  .getUsed());
      Files.writeString(output, result.toString(2));
      exit = 0;
    } catch (Throwable failure) {
      Files.writeString(
          output,
          new JSONObject().put("status", "FAIL").put("error", failure.toString()).toString(2));
      failure.printStackTrace();
    } finally {
      sampling.set(false);
      List<ProcessHandle> owned = ProcessHandle.current().descendants().toList();
      owned.forEach(ProcessHandle::destroy);
      long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (owned.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < cleanupDeadline)
        Thread.sleep(25);
      owned.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
      cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (owned.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < cleanupDeadline)
        Thread.sleep(25);
      if (owned.stream().anyMatch(ProcessHandle::isAlive)) exit = 1;
      System.exit(exit);
    }
  }
}
