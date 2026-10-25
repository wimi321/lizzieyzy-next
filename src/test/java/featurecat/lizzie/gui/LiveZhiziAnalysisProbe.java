package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.remote.CredentialStore;
import featurecat.lizzie.analysis.remote.EngineTransport;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.analysis.remote.ZhiziApiClient;
import featurecat.lizzie.rules.BoardHistoryNode;
import io.socket.client.Socket;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.Console;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in live VIP acceptance main. Not a CI test; never accepts credentials in arguments. */
public final class LiveZhiziAnalysisProbe {
  private final Path work;
  private final String mode;
  private final int seconds;
  private final int moves;
  private final int visits;
  private final int repeats;
  private final Set<EngineTransport> transports = ConcurrentHashMap.newKeySet();
  private volatile String stage = "initialization";
  private volatile boolean finished;
  private long deadline;
  private String account = "";
  private String password = "";
  private String token = "";
  private Leelaz primary;

  private LiveZhiziAnalysisProbe(String[] args) {
    if (args.length != 6)
      throw new IllegalArgumentException("mode work seconds moves visits repeats");
    mode = args[0];
    if (!List.of("flash", "native-flash", "auto", "curve", "lifecycle", "all", "comparison")
        .contains(mode)) throw new IllegalArgumentException("Unknown probe mode");
    work = Path.of(args[1]).toAbsolutePath();
    seconds = bounded(args[2], 30, mode.equals("native-flash") ? 120 : 600);
    moves = bounded(args[3], 2, 40);
    visits = bounded(args[4], 2, 10000);
    repeats = bounded(args[5], 1, 5);
  }

  public static void main(String[] args) throws Exception {
    LiveZhiziAnalysisProbe probe = new LiveZhiziAnalysisProbe(args);
    probe.run();
  }

  private void run() throws Exception {
    if (Files.exists(work)) throw new IllegalArgumentException("Use a new output directory");
    Console console = System.console();
    if (console == null)
      throw new IllegalStateException("A private interactive terminal is required");
    char[] identifier = console.readPassword("Account (hidden): ");
    char[] secret = console.readPassword("Password (hidden): ");
    if (identifier == null || secret == null) throw new IllegalStateException("Input cancelled");
    account = new String(identifier);
    password = new String(secret);
    Arrays.fill(identifier, '\0');
    Arrays.fill(secret, '\0');
    Files.createDirectories(work);
    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    startDeadlineGuard();
    int exit = 1;
    try {
      configure();
      isolateCredentialStore();
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      await(() -> Lizzie.frame != null && Lizzie.frame.isShowing(), 25, "main-window");
      record("mode=" + mode + " moves=" + moves + " visits=" + visits + " repeats=" + repeats);
      record("allocation=VIP-only deadlineSeconds=" + seconds);
      stage = "api-login-and-membership";
      ZhiziApiClient api = new ZhiziApiClient();
      token = api.login(account, password);
      check(api.fetchAccount(token).membership, "Existing VIP membership required; no allocation");
      onEdt(
          () -> {
            // An empty identifier keeps the test account out of the non-secret config.
            RemoteComputeConfig.saveZhiziToken(
                token, false, RemoteComputeConfig.VIP_ZHIZI_ARGS, "");
            int index = RemoteComputeConfig.createOrUpdateZhiziEngine(true);
            Lizzie.engineManager.switchEngine(index, true);
            return null;
          });
      await(this::engineActive, 90, "vip-engine-active");
      primary = Lizzie.leelaz;
      rememberTransports();
      check(primary.useRemoteCompute, "Expected built-in remote primary");
      record(
          "engine-ready PASS model="
              + RemoteComputeConfig.kataWeightForArgs(RemoteComputeConfig.VIP_ZHIZI_ARGS));
      featurecat.lizzie.logging.LoggingRuntime.current()
          .orElseThrow()
          .startFullTrace(Set.of(featurecat.lizzie.logging.TraceScope.ENGINE_GTP));
      boolean comparisonPassed = true;
      if (mode.equals("comparison")) {
        try {
          flash(1);
        } catch (Throwable failure) {
          comparisonPassed = false;
          record("flash=FAIL stage=" + stage + " type=" + failure.getClass().getSimpleName());
          onEdt(
              () -> {
                for (Window window : Window.getWindows())
                  if (window instanceof JDialog) window.dispose();
                Lizzie.frame.destroyAnalysisEngine();
                return null;
              });
          await(
              () -> !requestActive() && !primary.hasExclusiveGtpWorkInProgress(),
              15,
              "baseline-flash-cleanup");
        }
        for (int run = 1; run <= repeats; run++) automatic(run);
        curve();
      }
      if (mode.equals("flash") || mode.equals("native-flash") || mode.equals("all")) {
        for (int run = 1; run <= repeats; run++) flash(run);
      }
      if (mode.equals("auto") || mode.equals("all")) {
        for (int run = 1; run <= repeats; run++) automatic(run);
      }
      if (mode.equals("curve") || mode.equals("all")) curve();
      if (mode.equals("lifecycle") || mode.equals("all")) lifecycle();
      record("checks=" + (comparisonPassed ? "PASS" : "FAIL"));
      exit = comparisonPassed ? 0 : 1;
    } catch (Throwable failure) {
      // Do not serialize exception messages, login UI, response bodies, or account identifiers.
      record("checks=FAIL stage=" + stage + " type=" + failure.getClass().getSimpleName());
    } finally {
      try {
        try {
          cleanup();
          scanSecrets();
        } catch (Throwable failure) {
          exit = 1;
          record("cleanup=FAIL type=" + failure.getClass().getSimpleName());
        }
        record("result=" + (exit == 0 ? "PASS" : "FAIL"));
      } catch (Throwable failure) {
        exit = 1;
        System.err.println("probe-finalization=FAIL type=" + failure.getClass().getSimpleName());
      } finally {
        finished = true;
        System.exit(exit);
      }
    }
  }

  private void configure() throws Exception {
    JSONObject ui =
        new JSONObject()
            .put("autoload-empty", true)
            .put("first-time-load", false)
            .put("use-language", 1)
            .put("auto-quick-analyze-on-load", false)
            .put("analysis-reuse-current-engine", false)
            .put("analysis-max-visits", visits - 1)
            .put("quick-analysis-lightweight-model-enabled", false)
            .put("analysis-engine-command", "")
            .put("analysis-engine-preload", false)
            .put("play-sound", false);
    Files.writeString(
        work.resolve("config.txt"),
        new JSONObject()
            .put("ui", ui)
            .put("leelaz", new JSONObject().put("engine-settings-list", new JSONArray()))
            .toString());
  }

  private void flash(int run) throws Exception {
    load(false);
    onEdt(
        () -> {
          Lizzie.board.goToMoveNumber(Math.min(4, moves));
          return null;
        });
    long start = System.nanoTime();
    long startedEpochMillis = System.currentTimeMillis();
    boolean nativeClick = mode.equals("native-flash");
    if (nativeClick) record("ready-for-native-flash");
    else SwingUtilities.invokeLater(() -> Lizzie.frame.flashAnalyzeGame(true, false));
    await(
        () -> requestActive() || modalErrorVisible() || complete(visits, false),
        nativeClick ? 60 : 20,
        "flash-start-" + run);
    if (nativeClick) {
      start = System.nanoTime();
      startedEpochMillis = System.currentTimeMillis();
      record("native-flash timing=first-observed-active operator-wait-excluded");
    }
    check(!onEdt(this::modalErrorVisible), "Flash analysis opened a modal error");
    await(() -> complete(visits, false) && !requestActive(), 180, "flash-complete-" + run);
    check(
        onEdt(() -> Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber)
            == Math.min(4, moves),
        "Flash changed selection");
    long completedEpochMillis = System.currentTimeMillis();
    long completeElapsedMillis = elapsed(start);
    int[] actualVisits =
        onEdt(
            () -> {
              int minimum = Integer.MAX_VALUE;
              int maximum = 0;
              BoardHistoryNode node = Lizzie.board.getHistory().getStart();
              for (int index = 0; index < moves; index++) {
                node = node.next().orElseThrow();
                int count = node.getData().getPlayouts();
                minimum = Math.min(minimum, count);
                maximum = Math.max(maximum, count);
              }
              return new int[] {minimum, maximum};
            });
    record(
        "flash run="
            + run
            + " elapsedMs="
            + completeElapsedMillis
            + " analyzed="
            + moves
            + " targetVisits="
            + visits);
    assertForeground();
    JSONObject metrics =
        new JSONObject()
            .put("scenario", "flash")
            .put("run", run)
            .put("positions", moves)
            .put("targetVisits", visits)
            .put("model", RemoteComputeConfig.kataWeightForArgs(RemoteComputeConfig.VIP_ZHIZI_ARGS))
            .put("startedEpochMillis", startedEpochMillis)
            .put("completedEpochMillis", completedEpochMillis)
            .put("foregroundReadyEpochMillis", System.currentTimeMillis())
            .put("elapsedMillis", completeElapsedMillis)
            .put("foregroundReadyElapsedMillis", elapsed(start))
            .put("minimumActualVisits", actualVisits[0])
            .put("maximumActualVisits", actualVisits[1]);
    Files.writeString(
        work.resolve("metrics.jsonl"),
        metrics + "\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  private void automatic(int run) throws Exception {
    load(false);
    long start = System.nanoTime();
    onEdt(
        () -> {
          BottomToolbar toolbar = LizzieFrame.toolbar;
          toolbar.chkAnaTime.setSelected(false);
          toolbar.chkAnaFirstPlayouts.setSelected(false);
          toolbar.chkAnaPlayouts.setSelected(true);
          toolbar.txtAnaPlayouts.setText(Integer.toString(visits));
          toolbar.chkAnaBlack.setSelected(true);
          toolbar.chkAnaWhite.setSelected(true);
          toolbar.chkAnaAutoSave.setSelected(false);
          toolbar.txtFirstAnaMove.setText("1");
          toolbar.txtLastAnaMove.setText(Integer.toString(moves));
          Lizzie.config.analyzeAllBranch = false;
          Lizzie.config.autoAnaDiffEnable = false;
          toolbar.startAutoAna();
          return null;
        });
    await(() -> Lizzie.config.isAutoAna, 20, "auto-start-" + run);
    await(() -> !Lizzie.config.isAutoAna, 180, "auto-complete-" + run);
    // The legacy workflow starts at the root and also analyzes the final position.
    check(
        onEdt(() -> complete(visits, true)),
        "Auto ended before all requested positions had visits");
    record(
        "auto run="
            + run
            + " elapsedMs="
            + elapsed(start)
            + " analyzed="
            + (moves + 1)
            + " targetVisits="
            + visits);
  }

  private void curve() throws Exception {
    long start = System.nanoTime();
    load(true);
    int curveVisits = silentCurveVisits();
    await(
        () -> complete(curveVisits, false) && !requestActive() && primary.isPondering(),
        180,
        "import-curve-complete");
    record(
        "curve elapsedMs="
            + elapsed(start)
            + " analyzed="
            + moves
            + " targetVisits="
            + curveVisits);
    assertForeground();
  }

  private void lifecycle() throws Exception {
    load(true);
    await(this::requestActive, 25, "curve-running-before-pause");
    long pauseStart = System.nanoTime();
    onEdt(
        () -> {
          Lizzie.frame.togglePonderMannul();
          return null;
        });
    await(
        () -> Lizzie.frame.isUserAnalysisPaused() && !requestActive() && !primary.isPondering(),
        20,
        "pause-restored");
    record("pause elapsedMs=" + elapsed(pauseStart));
    onEdt(
        () -> {
          Lizzie.frame.togglePonderMannul();
          return null;
        });
    await(primary::isPondering, 20, "manual-resume");
    assertForeground();
    if (remainingSeconds() < 90) {
      record("reconnect=NOT_RUN remaining-budget-below-90s");
      throw new AssertionError("Insufficient budget for reconnect acceptance");
    }
    Object incarnation = primary.engineIncarnationToken();
    EngineTransport transport = (EngineTransport) field(primary, "remoteTransport");
    Socket socket = (Socket) field(transport, "socket");
    long reconnectStart = System.nanoTime();
    socket.disconnect();
    await(
        () -> !primary.isCurrentEngineIncarnationToken(incarnation) && engineActive(),
        75,
        "reconnect");
    rememberTransports();
    assertForeground();
    record("reconnect elapsedMs=" + elapsed(reconnectStart));
  }

  private void load(boolean automaticCurve) throws Exception {
    String game = game();
    record(
        "fixtureSha256="
            + HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(game.getBytes(StandardCharsets.UTF_8))));
    java.util.concurrent.atomic.AtomicBoolean loaded =
        new java.util.concurrent.atomic.AtomicBoolean();
    onEdt(
        () -> {
          Lizzie.config.autoQuickAnalyzeOnLoad = automaticCurve;
          Lizzie.frame.loadDownloadedSgfString(game, 0, true, false, null, loaded::set);
          return null;
        });
    await(loaded::get, 15, "fresh-fixture-loaded");
  }

  private String game() throws Exception {
    String source =
        Files.readString(
            Path.of("src/test/resources/featurecat/lizzie/rules/issue223-reporter-205-moves.sgf"));
    Matcher matcher = Pattern.compile(";[BW]\\[[a-s]{0,2}\\]").matcher(source);
    StringBuilder sgf =
        new StringBuilder("(;FF[4]GM[1]SZ[19]KM[7.5]RU[Chinese]GN[Cloud acceptance]");
    for (int i = 0; i < moves; i++) {
      check(matcher.find(), "Fixture too short");
      sgf.append(matcher.group());
    }
    return sgf.append(')').toString();
  }

  private boolean complete(int targetVisits, boolean beforeMoves) {
    BoardHistoryNode node = Lizzie.board.getHistory().getStart();
    if (!beforeMoves) node = node.next().orElse(null);
    for (int i = 0; i < (beforeMoves ? moves + 1 : moves); i++) {
      if (node == null || !node.getData().hasCompletePrimaryAnalysis(targetVisits, false))
        return false;
      node = node.next().orElse(null);
    }
    return true;
  }

  private void assertForeground() throws Exception {
    check(primary == Lizzie.leelaz, "Unexpected primary replacement");
    await(primary::isPondering, 20, "foreground-handback");
    BoardHistoryNode node = onEdt(() -> Lizzie.board.getHistory().getCurrentHistoryNode());
    int before = onEdt(() -> node.getData().getPlayouts());
    await(() -> node.getData().getPlayouts() > before, 20, "foreground-progress");
    record("foreground-progress PASS");
  }

  private boolean requestActive() {
    return Lizzie.frame.analysisEngine != null
        && Lizzie.frame.analysisEngine.hasRequestLifecycleInProgress();
  }

  private boolean engineActive() {
    return Lizzie.leelaz != null
        && Lizzie.leelaz.isLoaded()
        && Lizzie.engineManager.engineSwitchUiSnapshot(true).phase()
            == EngineManager.EngineSwitchUiPhase.ACTIVE;
  }

  private boolean modalErrorVisible() {
    for (Window window : Window.getWindows()) {
      if (window instanceof JDialog && window.isShowing() && containsOptionPane(window))
        return true;
    }
    return false;
  }

  private static boolean containsOptionPane(Component component) {
    if (component instanceof JOptionPane) return true;
    if (component instanceof Container) {
      for (Component child : ((Container) component).getComponents())
        if (containsOptionPane(child)) return true;
    }
    return false;
  }

  private void await(BooleanSupplier condition, int timeoutSeconds, String label) throws Exception {
    stage = label;
    record("stage=" + label);
    long localDeadline =
        Math.min(
            deadline - TimeUnit.SECONDS.toNanos(8),
            System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds));
    while (System.nanoTime() < localDeadline) {
      rememberTransports();
      if (onEdt(condition::getAsBoolean)) return;
      Thread.sleep(50);
    }
    throw new AssertionError("Acceptance stage did not complete");
  }

  private void rememberTransports() {
    for (Object engine :
        new Object[] {Lizzie.leelaz, Lizzie.frame == null ? null : Lizzie.frame.analysisEngine}) {
      if (engine == null) continue;
      try {
        Object transport = field(engine, "remoteTransport");
        if (transport instanceof EngineTransport) transports.add((EngineTransport) transport);
      } catch (ReflectiveOperationException ignored) {
      }
    }
  }

  private void cleanup() throws Exception {
    stage = "cleanup";
    Process clockHelper =
        onEdt(
            () -> {
              Process ownedClockHelper = null;
              if (Lizzie.frame != null) {
                Object helper = field(Lizzie.frame, "processClockHelper");
                if (helper instanceof Process) ownedClockHelper = (Process) helper;
                Lizzie.frame.shutdownClockHelper();
                for (Window window : Window.getWindows())
                  if (window instanceof JDialog) window.dispose();
                if (Lizzie.config.isAutoAna) LizzieFrame.toolbar.stopAutoAna(false, true);
                AnalysisEngine secondary = Lizzie.frame.analysisEngine;
                if (secondary != null) secondary.normalQuit();
              }
              if (Lizzie.leelaz != null) Lizzie.leelaz.normalQuit();
              if (Lizzie.config != null) RemoteComputeConfig.clearZhiziToken();
              return ownedClockHelper;
            });
    rememberTransports();
    for (EngineTransport transport : transports) transport.abort();
    check(transports.stream().noneMatch(EngineTransport::isOpen), "Cloud transport still open");
    featurecat.lizzie.logging.LoggingRuntime.current()
        .ifPresent(runtime -> runtime.stopFullTrace());
    featurecat.lizzie.logging.LoggingRuntime.current().ifPresent(runtime -> runtime.shutdown());
    record("cloud-transports-closed PASS");
    check(
        clockHelper == null || clockHelper.waitFor(2, TimeUnit.SECONDS),
        "Clock helper survived cleanup");
    record("clock-helper-closed PASS");
  }

  private void startDeadlineGuard() {
    Thread guard =
        new Thread(
            () -> {
              try {
                TimeUnit.SECONDS.sleep(seconds - 5L);
                if (finished) return;
                record("deadline=EXCEEDED stage=" + stage);
                rememberTransports();
                for (EngineTransport transport : transports) {
                  Thread abort = new Thread(transport::abort, "live-probe-deadline-abort");
                  abort.setDaemon(true);
                  abort.start();
                }
                TimeUnit.SECONDS.sleep(5);
                if (!finished) Runtime.getRuntime().halt(124);
              } catch (Exception ignored) {
                Runtime.getRuntime().halt(124);
              }
            },
            "live-probe-hard-deadline");
    guard.setDaemon(true);
    guard.start();
  }

  private void isolateCredentialStore() throws Exception {
    Field override = RemoteComputeConfig.class.getDeclaredField("credentialStoreOverride");
    override.setAccessible(true);
    override.set(
        null,
        new CredentialStore() {
          public String backendName() {
            return "live-probe-session-only";
          }

          public boolean isAvailable() {
            return false;
          }

          public Optional<String> read(Kind kind, String identifier) {
            return Optional.empty();
          }

          public void write(Kind kind, String identifier, String secret) {
            throw new AssertionError("Persistence forbidden");
          }

          public void delete(Kind kind, String identifier) {}
        });
  }

  private void scanSecrets() throws Exception {
    try (var paths = Files.walk(work)) {
      for (Path file : paths.filter(Files::isRegularFile).toList()) {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        for (String secret : List.of(account, password, token))
          check(secret.isEmpty() || !text.contains(secret), "Sensitive data persisted");
      }
    }
    record("credential-scan PASS");
  }

  private <T> T onEdt(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    return task.get(Math.max(1, Math.min(10, remainingSeconds())), TimeUnit.SECONDS);
  }

  private synchronized void record(String text) throws Exception {
    System.out.println(text);
    Files.writeString(
        work.resolve("result.txt"),
        text + "\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  private long remainingSeconds() {
    return Math.max(0, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()));
  }

  private static long elapsed(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  private static int bounded(String value, int min, int max) {
    int parsed = Integer.parseInt(value);
    if (parsed < min || parsed > max)
      throw new IllegalArgumentException("Argument outside supported bounds");
    return parsed;
  }

  private static Object field(Object target, String name) throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static int silentCurveVisits() throws ReflectiveOperationException {
    Field field = AnalysisEngine.class.getDeclaredField("REMOTE_GTP_SILENT_MAX_VISITS");
    field.setAccessible(true);
    return field.getInt(null);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
