package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.remote.CredentialStore;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.rules.BoardHistoryNode;
import io.socket.client.Socket;
import java.awt.image.BufferedImage;
import java.io.Console;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit, interactive real-service acceptance; never run by CI or with saved credentials. */
public final class LiveQuickAnalysisProbe {
  private static Path work;
  private static String stage = "initialization";
  private static String accountToken = "";
  private static String password = "";

  public static void main(String[] args) throws Exception {
    if (args.length != 4) {
      throw new IllegalArgumentException(
          "mode(remote/local-shared/local-dedicated) work engine model");
    }
    String mode = args[0];
    if (!mode.equals("remote") && !mode.equals("local-shared") && !mode.equals("local-dedicated")) {
      throw new IllegalArgumentException("Unknown mode");
    }
    work = Path.of(args[1]).toAbsolutePath();
    if (Files.exists(work)) throw new IllegalArgumentException("Use a new isolated directory");
    Files.createDirectories(work);
    String account = "";
    if (mode.equals("remote")) {
      Console console = System.console();
      if (console == null)
        throw new IllegalStateException("A private interactive terminal is required");
      char[] identifier = console.readPassword("Account (hidden): ");
      char[] secret = console.readPassword("Password (hidden): ");
      account = new String(identifier);
      password = new String(secret);
      Arrays.fill(identifier, '\0');
      Arrays.fill(secret, '\0');
    }
    int exit = 1;
    try {
      isolateCredentialStore();
      configure(mode, Path.of(args[2]), Path.of(args[3]));
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      await(() -> Lizzie.frame != null && Lizzie.frame.isShowing(), 30, "main-window");
      featurecat.lizzie.logging.LoggingRuntime.current()
          .orElseThrow()
          .startFullTrace(java.util.Set.of(featurecat.lizzie.logging.TraceScope.ENGINE_GTP));
      if (mode.equals("remote")) loginThroughDialog(account);
      await(LiveQuickAnalysisProbe::engineActive, 120, "engine-ready");
      Leelaz primary = Lizzie.leelaz;
      check(!mode.equals("remote") || primary.useRemoteCompute, "Expected real remote engine");
      String game = game(40, "Chinese", "7.5");
      load(game);
      await(LiveQuickAnalysisProbe::curveComplete, 150, "first-curve-and-handback");
      assertForegroundGrows(primary);
      screenshot("first-curve");
      record("first-curve PASS");

      load(game(36, "Japanese", "6.5"));
      edt(() -> Lizzie.board.goToMoveNumber(12));
      await(LiveQuickAnalysisProbe::curveComplete, 150, "changed-rules-navigation");
      check(
          Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber == 12,
          "Curve moved the user's selected node");
      assertForegroundGrows(primary);
      record("changed-rules-navigation PASS");

      if (mode.equals("remote")) {
        Object incarnation = primary.engineIncarnationToken();
        load(game(38, "Chinese", "7.5"));
        await(LiveQuickAnalysisProbe::quickRunning, 45, "curve-before-disconnect");
        Object transport = field(primary, "remoteTransport");
        Socket socket = (Socket) field(transport, "socket");
        socket.disconnect();
        await(
            () -> !primary.isCurrentEngineIncarnationToken(incarnation), 90, "old-session-retired");
        await(LiveQuickAnalysisProbe::curveComplete, 180, "reconnected-curve-and-handback");
        assertForegroundGrows(primary);
        screenshot("reconnected-curve");
        record("real-disconnect-reconnect PASS");
      }

      for (int i = 0; i < 3; i++) {
        load(game(41 + i, "Chinese", "7.5"));
        await(LiveQuickAnalysisProbe::curveComplete, 150, "repeated-import-handback-" + i);
        assertForegroundGrows(primary);
      }
      record("repeated-import-handback PASS count=3");

      load(game(44, "Chinese", "7.5"));
      await(LiveQuickAnalysisProbe::quickRunning, 45, "curve-before-replacement");
      load(game(32, "Japanese", "6.5"));
      await(LiveQuickAnalysisProbe::curveComplete, 150, "replacement-curve-and-handback");
      check(
          Lizzie.board.getHistory().getEnd().getData().moveNumber == 32,
          "Previous game returned after replacement");
      assertForegroundGrows(primary);
      record("replace-active-curve PASS");

      load(game(39, "Chinese", "7.5"));
      await(LiveQuickAnalysisProbe::quickRunning, 45, "curve-before-pause");
      edt(() -> Lizzie.frame.togglePonderMannul());
      await(
          () -> Lizzie.frame.isUserAnalysisPaused() && !quickRunning() && !primary.isPondering(),
          40,
          "explicit-pause");
      Thread.sleep(2500);
      check(!primary.isPondering(), "Pause was silently undone");
      record("explicit-pause PASS");
      edt(() -> Lizzie.frame.togglePonderMannul());
      await(primary::isPondering, 40, "manual-resume");

      edt(() -> Lizzie.config.autoQuickAnalyzeOnLoad = false);
      load(game(35, "Chinese", "7.5"));
      await(primary::isPondering, 40, "automatic-curve-disabled");
      Thread.sleep(2500);
      check(!quickRunning(), "Disabled quick analysis still started");
      check(!allAnalyzed(), "Disabled import unexpectedly analyzed the whole game");
      record("automatic-curve-disabled PASS");
      screenshot("disabled");
      record("result=PASS mode=" + mode);
      exit = 0;
    } catch (Throwable failure) {
      record("result=FAIL stage=" + stage + " type=" + failure.getClass().getSimpleName());
      diagnostics();
      // Do not serialize exception messages or login UI pixels from a real account.
      if (Lizzie.frame != null && Lizzie.frame.isShowing()) screenshot("failure-main-window");
    } finally {
      if (Lizzie.frame != null && Lizzie.frame.analysisEngine != null) {
        Lizzie.frame.analysisEngine.normalQuit();
      }
      if (Lizzie.leelaz != null) Lizzie.leelaz.normalQuit();
      if (mode.equals("remote") && Lizzie.config != null) {
        RemoteComputeConfig.clearZhiziToken();
        RemoteComputeConfig.State cleared = RemoteComputeConfig.load();
        cleared.zhiziIdentifier = "";
        RemoteComputeConfig.save(cleared);
      }
      scanSecrets();
      System.exit(exit);
    }
  }

  private static void configure(String mode, Path engine, Path model) throws Exception {
    JSONObject ui =
        new JSONObject()
            .put("autoload-empty", mode.equals("remote"))
            .put("autoload-default", !mode.equals("remote"))
            .put("first-time-load", false)
            .put("use-language", 1)
            .put("auto-quick-analyze-on-load", true)
            .put("analysis-reuse-current-engine", mode.equals("local-shared"))
            .put("quick-analysis-lightweight-model-enabled", false)
            .put("analysis-engine-preload", false)
            .put("play-sound", false);
    JSONArray engines = new JSONArray();
    if (!mode.equals("remote")) {
      check(
          Files.isRegularFile(engine) && Files.isRegularFile(model), "Local engine/model missing");
      Path cfg = work.resolve("gtp.cfg");
      Files.writeString(
          cfg,
          "rules = chinese\nnumSearchThreads = 4\nnnMaxBatchSize = 4\n"
              + "nnCacheSizePowerOfTwo = 16\nnnMutexPoolSizePowerOfTwo = 12\n"
              + "logToStderr = true\nlogAllGTPCommunication = true\nlogSearchInfo = true\nlogSearchInfoForChosenMove = false\n"
              + "ponderingEnabled = false\nallowResignation = true\n"
              + "resignThreshold = -0.90\nresignConsecTurns = 3\n");
      String suffix = " -model " + quote(model) + " -config " + quote(cfg);
      engines.put(
          new JSONObject()
              .put("command", quote(engine) + " gtp" + suffix)
              .put("name", "Local acceptance")
              .put("isDefault", true)
              .put("width", 19)
              .put("height", 19)
              .put("komi", 7.5));
      ui.put("analysis-engine-command", quote(engine) + " analysis" + suffix);
    } else ui.put("analysis-engine-command", "");
    Files.writeString(
        work.resolve("config.txt"),
        new JSONObject()
            .put("ui", ui)
            .put("leelaz", new JSONObject().put("engine-settings-list", engines))
            .toString());
  }

  private static void loginThroughDialog(String account) throws Exception {
    RemoteComputeDialog[] dialog = new RemoteComputeDialog[1];
    edt(
        () -> {
          dialog[0] = new RemoteComputeDialog(Lizzie.frame);
          dialog[0].setVisible(true);
          ((AbstractButton) field(dialog[0], "rememberToken")).setSelected(false);
          ((AbstractButton) field(dialog[0], "rememberPassword")).setSelected(false);
          ((JTextField) field(dialog[0], "accountField")).setText(account);
          ((JPasswordField) field(dialog[0], "passwordField")).setText(password);
          ((AbstractButton) field(dialog[0], "loginButton")).doClick();
        });
    await(() -> !RemoteComputeConfig.load().zhiziAccountToken.isBlank(), 45, "real-password-login");
    accountToken = RemoteComputeConfig.load().zhiziAccountToken;
    record("real-password-login PASS");
    edt(
        () -> {
          check(
              ((JPasswordField) field(dialog[0], "passwordField")).getPassword().length == 0,
              "Login retained password in UI");
          ((AbstractButton) field(dialog[0], "useZhiziButton")).doClick();
        });
    await(LiveQuickAnalysisProbe::engineActive, 120, "real-vip-enable");
    edt(() -> dialog[0].dispose());
    record("real-vip-enable PASS");
  }

  private static void isolateCredentialStore() throws Exception {
    Field override = RemoteComputeConfig.class.getDeclaredField("credentialStoreOverride");
    override.setAccessible(true);
    override.set(
        null,
        new CredentialStore() {
          public String backendName() {
            return "acceptance-session-only";
          }

          public boolean isAvailable() {
            return false;
          }

          public Optional<String> read(Kind kind, String account) {
            return Optional.empty();
          }

          public void write(Kind kind, String account, String secret) {
            throw new AssertionError("No persistence");
          }

          public void delete(Kind kind, String account) {}
        });
  }

  private static String game(int moves, String rules, String komi) throws Exception {
    String fixture =
        Files.readString(
            Path.of("src/test/resources/featurecat/lizzie/rules/issue223-reporter-205-moves.sgf"));
    Matcher matcher = Pattern.compile(";[BW]\\[[a-s]{0,2}\\]").matcher(fixture);
    StringBuilder sgf =
        new StringBuilder("(;FF[4]GM[1]SZ[19]KM[")
            .append(komi)
            .append("]RU[")
            .append(rules)
            .append("]GN[Acceptance]");
    for (int i = 0; i < moves; i++) {
      check(matcher.find(), "Fixture too short");
      sgf.append(matcher.group());
    }
    return sgf.append(')').toString();
  }

  private static void load(String sgf) throws Exception {
    java.util.concurrent.atomic.AtomicBoolean loaded =
        new java.util.concurrent.atomic.AtomicBoolean();
    edt(() -> Lizzie.frame.loadDownloadedSgfString(sgf, 0, true, false, null, loaded::set));
    await(loaded::get, 25, "downloaded-sgf-import");
  }

  private static boolean engineActive() {
    return Lizzie.leelaz != null
        && Lizzie.leelaz.isLoaded()
        && Lizzie.engineManager.engineSwitchUiSnapshot(true).phase()
            == EngineManager.EngineSwitchUiPhase.ACTIVE;
  }

  private static boolean quickRunning() {
    return Lizzie.frame.analysisEngine != null
        && Lizzie.frame.analysisEngine.hasRequestLifecycleInProgress();
  }

  private static boolean curveComplete() {
    return allAnalyzed() && Lizzie.leelaz.isPondering() && !quickRunning();
  }

  private static boolean allAnalyzed() {
    BoardHistoryNode node = Lizzie.board.getHistory().getStart();
    int count = 0;
    while (node.next().isPresent()) {
      node = node.next().orElseThrow();
      if (!node.getData().hasDisplayablePrimaryAnalysis()) return false;
      count++;
    }
    return count > 0;
  }

  private static void assertForegroundGrows(Leelaz primary) throws Exception {
    check(Lizzie.leelaz == primary, "Primary was replaced unexpectedly");
    BoardHistoryNode node = Lizzie.board.getHistory().getCurrentHistoryNode();
    int visits = node.getData().getPlayouts();
    await(() -> node.getData().getPlayouts() > visits, 20, "foreground-visits-growing");
  }

  private static void await(BooleanSupplier condition, int seconds, String label) throws Exception {
    stage = label;
    record("stage=" + label);
    long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
    check(condition.getAsBoolean(), label);
  }

  private static void screenshot(String name) throws Exception {
    edt(
        () -> {
          BufferedImage image =
              new BufferedImage(
                  Lizzie.frame.getWidth(), Lizzie.frame.getHeight(), BufferedImage.TYPE_INT_RGB);
          var graphics = image.createGraphics();
          try {
            Lizzie.frame.printAll(graphics);
          } finally {
            graphics.dispose();
          }
          ImageIO.write(image, "png", work.resolve(name + ".png").toFile());
        });
  }

  private static void diagnostics() throws Exception {
    if (Lizzie.leelaz == null || Lizzie.board == null) return;
    Leelaz engine = Lizzie.leelaz;
    record(
        "diagnostic ponder="
            + engine.isPondering()
            + " quick="
            + quickRunning()
            + " exclusive="
            + engine.hasExclusiveGtpWorkInProgress()
            + " recovery="
            + engine.isRemoteSessionRecoveryRequested()
            + " visits="
            + engine.getBestMovesPlayouts()
            + " node="
            + Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber
            + " nodeVisits="
            + Lizzie.board.getHistory().getCurrentHistoryNode().getData().getPlayouts()
            + " rootVisits="
            + Lizzie.board.getHistory().getCurrentHistoryNode().getData().rootVisits
            + " engineRoot="
            + field(engine, "currentRootVisits")
            + " childVisits="
            + featurecat.lizzie.analysis.MoveData.getPlayouts(engine.getBestMoves()));
    record(
        "display-errors="
            + ((java.util.Collection<?>) field(engine, "recentStderrLines"))
                .stream()
                    .map(String::valueOf)
                    .filter(line -> line.startsWith("Ignored analysis display"))
                    .toList());
    if (Lizzie.frame.analysisEngine != null) {
      var auxiliary = Lizzie.frame.analysisEngine;
      record(
          "auxiliary responses="
              + field(auxiliary, "responseCount")
              + " results="
              + field(auxiliary, "resultCount")
              + " dispatched="
              + field(auxiliary, "requestDispatchComplete")
              + " jobs="
              + ((java.util.Map<?, ?>) field(auxiliary, "analyzeMap")).size());
    }
    edt(
        () -> {
          String console = Lizzie.gtpConsole.console.getText();
          String commands =
              console
                  .lines()
                  .filter(
                      line ->
                          line.contains("> ")
                              && !line.contains("DEBUG")
                              && !line.contains("token")
                              && !line.contains("password"))
                  .reduce("", (left, right) -> left + right + "\n");
          Files.writeString(work.resolve("commands.txt"), commands);
        });
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void edt(CheckedAction action) throws Exception {
    FutureTask<Void> task =
        new FutureTask<>(
            () -> {
              action.run();
              return null;
            });
    SwingUtilities.invokeLater(task);
    task.get(30, TimeUnit.SECONDS);
  }

  private static void record(String text) throws Exception {
    System.out.println(text);
    Files.writeString(
        work.resolve("result.txt"),
        text + "\n",
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND);
  }

  private static void scanSecrets() throws Exception {
    try (var files = Files.walk(work)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        if (file.toString().endsWith(".png")) continue;
        String content =
            new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
        check(password.isEmpty() || !content.contains(password), "Password persisted");
        check(accountToken.isEmpty() || !content.contains(accountToken), "Account token persisted");
      }
    }
    record("credential-scan PASS");
  }

  private static String quote(Path path) {
    return "\"" + path.toAbsolutePath() + "\"";
  }

  private static void check(boolean value, String label) {
    if (!value) throw new AssertionError(label);
  }

  private interface CheckedAction {
    void run() throws Exception;
  }
}
