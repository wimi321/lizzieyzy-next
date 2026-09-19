package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import featurecat.lizzie.AppLocale;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.Utils;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.JTextComponent;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Native Windows acceptance for the controlled TensorRT repair and explicit-enable flow. */
public final class TensorRtRepairAcceptanceTest {
  private static final Duration PARENT_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration UI_TIMEOUT = Duration.ofSeconds(45);
  private static final Set<String> CASES =
      Set.of("CT-01", "CT-02", "CT-03", "CT-04", "CT-05");
  private static final String DIRECTML = "DirectML controlled";
  private static final String TENSORRT = "TensorRT managed missing";
  private static final String CUSTOM_ANALYSIS = "custom-analysis-command --preserve";
  private static final String EMPTY_SHA256 =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  @Test
  void englishRepairFlow() throws Exception {
    runLocale("en_US", 2, "en", "US");
  }

  @Test
  void chineseRepairFlow() throws Exception {
    runLocale("zh_CN", 1, "zh", "CN");
  }

  private static void runLocale(
      String locale, int languageConfig, String language, String country) throws Exception {
    DesktopProbeProcess.requireDisplay();
    requireNativeWindowsJdk21();
    Path evidenceRoot =
        Path.of(System.getProperty("lizzie.desktop.evidence.dir", "target/tensorrt-ui/probes"))
            .toAbsolutePath()
            .normalize();
    Files.createDirectories(evidenceRoot);
    Path fixtureRoot = Files.createTempDirectory("lizzie-tensorrt-native-" + locale + "-");
    Path fixtureManifest = evidenceRoot.resolve("fixture-manifest-" + locale + ".txt");
    NativeFixtures fixtures = null;
    try {
      fixtures = NativeFixtures.create(fixtureRoot, fixtureManifest);
      Map<String, String> rows = new LinkedHashMap<>();
      for (Scenario scenario : Scenario.values()) {
        Path gtpRoot = Files.createDirectories(fixtureRoot.resolve("gtp-" + scenario.argument));
        Path nvidiaLedger = fixtureRoot.resolve("nvidia-smi-" + scenario.argument + ".log");
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(
            "PATH",
            fixtures.nvidiaImage.toString()
                + java.io.File.pathSeparator
                + System.getenv().getOrDefault("PATH", ""));
        environment.put("LIZZIE_CONTROLLED_GTP_ROOT", gtpRoot.toString());
        environment.put("LIZZIE_NVIDIA_SMI_LEDGER", nvidiaLedger.toString());
        Path result =
            DesktopProbeProcess.run(
                TensorRtRepairAcceptanceTest.class,
                "tensorrt-" + locale + "-" + scenario.argument,
                List.of("-Duser.language=" + language, "-Duser.country=" + country),
                List.of(
                    "probe",
                    locale,
                    Integer.toString(languageConfig),
                    scenario.argument,
                    fixtures.directMlImage.toString(),
                    fixtures.tensorRtImage.toString(),
                    gtpRoot.toString(),
                    nvidiaLedger.toString()),
                environment,
                PARENT_TIMEOUT.toSeconds(),
                fixtureRoot,
                fixtures.nvidiaImage);
        Map<String, String> child = parseStrictResult(result);
        assertEquals("PASS", child.get("result"), child.toString());
        assertEquals(locale, child.get("locale"), child.toString());
        assertEquals(scenario.argument, child.get("scenario"), child.toString());
        child.forEach(
            (key, value) -> {
              if (!key.startsWith("CT-")) return;
              if (rows.putIfAbsent(key, value) != null) {
                throw new AssertionError("Duplicate controlled result row: " + key);
              }
            });
        Path cleanup = Path.of(required(child, "cleanup.evidence"));
        assertTrue(Files.isRegularFile(Path.of(required(child, "http.ledger"))));
        assertTrue(Files.isRegularFile(cleanup));
        Map<String, String> cleanupState = parseStrictResult(cleanup);
        assertEquals("false", cleanupState.get("workers"), cleanupState.toString());
        assertEquals("0", cleanupState.get("requests"), cleanupState.toString());
        assertEquals("available", cleanupState.get("lock"), cleanupState.toString());
        long partialFiles = Long.parseLong(required(cleanupState, "partialFiles"));
        if (scenario == Scenario.ACTIVE_CANCEL) {
          assertTrue(partialFiles > 0, cleanupState.toString());
        } else {
          assertEquals(0, partialFiles, cleanupState.toString());
        }
        assertEquals("parent-owned", cleanupState.get("fixtureRoot"), cleanupState.toString());
        if (scenario == Scenario.COMPLETE_ENABLE) {
          Map<String, JSONObject> snapshots = new LinkedHashMap<>();
          for (String snapshot :
              List.of(
                  "pre-error-directml", "settled-repair-entry", "post-repair", "post-enable")) {
            Path path = Path.of(required(child, "snapshot." + snapshot));
            assertTrue(Files.isRegularFile(path), path.toString());
            assertTrue(Files.isRegularFile(path.resolveSibling(snapshot + ".png")));
            snapshots.put(
                snapshot,
                new JSONObject(Files.readString(path, StandardCharsets.UTF_8)));
          }
          validateSnapshotEvidence(
              locale,
              languageConfig,
              snapshots,
              Path.of(required(child, "http.ledger")),
              cleanup);
          assertEquals("PASS", child.get("config-delta"), child.toString());
          assertEquals("controlled-reload", child.get("reload-kind"), child.toString());
        }
      }
      assertEquals(CASES, rows.keySet(), rows.toString());
      for (String id : CASES) {
        assertEquals("PASS", rows.get(id), rows.toString());
      }
    } finally {
      deleteRecursively(fixtureRoot);
      Files.writeString(
          fixtureManifest,
          "cleanup.fixtureRoot=" + !Files.exists(fixtureRoot) + "\n",
          StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
    }
    assertFalse(Files.exists(fixtureRoot), "native fixture root survived cleanup");
  }

  private static void validateSnapshotEvidence(
      String locale,
      int languageConfig,
      Map<String, JSONObject> snapshots,
      Path httpLedger,
      Path cleanupEvidence) {
    JSONObject expectedBoard = null;
    for (Map.Entry<String, JSONObject> entry : snapshots.entrySet()) {
      JSONObject snapshot = entry.getValue();
      assertEquals(entry.getKey(), snapshot.getString("name"));
      assertEquals(TensorRtRepairAcceptanceTest.class.getName(), snapshot.getString("source"));
      assertTrue(snapshot.getBoolean("controlled"));
      assertTrue(snapshot.getLong("childPid") > 0);
      assertTrue(snapshot.getLong("pid") > 0);
      assertTrue(snapshot.getInt("engineIncarnation") != 0);
      assertEquals(locale.replace('_', '-'), snapshot.getString("locale"));
      assertEquals(languageConfig, snapshot.getInt("languageConfig"));
      assertFalse(snapshot.getString("workRoot").isBlank());
      assertFalse(snapshot.getString("timestamp").isBlank());
      assertTrue(Files.isRegularFile(Path.of(snapshot.getString("screenshot"))));
      assertEquals(httpLedger.toString(), snapshot.getString("httpLedger"));
      assertEquals(cleanupEvidence.toString(), snapshot.getString("cleanupEvidence"));
      JSONObject cleanup = snapshot.getJSONObject("cleanup");
      assertEquals("false", cleanup.getString("workers"));
      assertEquals("0", cleanup.getString("requests"));
      assertEquals("available", cleanup.getString("lock"));
      assertEquals("0", cleanup.getString("partialFiles"));
      assertEquals("parent-owned", cleanup.getString("fixtureRoot"));
      JSONObject board = snapshot.getJSONObject("board");
      if (expectedBoard == null) expectedBoard = board;
      else assertTrue(expectedBoard.similar(board), "board/rules/current-node snapshot changed");
      assertTrue(snapshot.getJSONObject("uiState").has("accelerationCardVisible"));
    }
    JSONObject settled = snapshots.get("settled-repair-entry");
    JSONObject settledContext = settled.getJSONObject("repairContext");
    assertTrue(settledContext.getBoolean("repairable"));
    assertTrue(settledContext.getString("failedExecutable").contains("nvidia-tensorrt"));
    JSONObject settledUi = settled.getJSONObject("uiState");
    assertTrue(settledUi.getBoolean("accelerationCardVisible"));
    assertTrue(settledUi.getBoolean("repairShown"));
    assertTrue(settledUi.getBoolean("directedTargetVisible"));
    assertTrue(
        settledUi
            .getString("directedTargetText")
            .contains(settledContext.getString("failedExecutable")));
    JSONObject repairedUi = snapshots.get("post-repair").getJSONObject("uiState");
    assertTrue(repairedUi.getBoolean("runtimeReady"));
    assertTrue(repairedUi.getBoolean("companionReady"));
    assertTrue(repairedUi.getBoolean("engineReady"));
    assertTrue(repairedUi.getBoolean("profileInactive"));
    assertTrue(repairedUi.getBoolean("enableShown"));
    assertTrue(repairedUi.getBoolean("enableEnabled"));
  }

  /** Child entry point. The parent starts it through {@link DesktopProbeProcess}. */
  public static void main(String[] args) throws Exception {
    if (args.length != 10 || !"probe".equals(args[0])) {
      throw new IllegalArgumentException(
          "expected probe, locale, language config, scenario, two images, GTP root, ledger, work, result");
    }
    String locale = args[1];
    int languageConfig = Integer.parseInt(args[2]);
    Scenario scenario = Scenario.parse(args[3]);
    Path directMlImage = Path.of(args[4]).toAbsolutePath().normalize();
    Path tensorRtImage = Path.of(args[5]).toAbsolutePath().normalize();
    Path gtpRoot = Path.of(args[6]).toAbsolutePath().normalize();
    Path nvidiaLedger = Path.of(args[7]).toAbsolutePath().normalize();
    Path work = Path.of(args[8]).toAbsolutePath().normalize();
    Path result = Path.of(args[9]).toAbsolutePath().normalize();
    Map<String, String> records = new LinkedHashMap<>();
    records.put("locale", locale);
    records.put("scenario", scenario.argument);
    int exit = 1;
    FixtureServer server = null;
    try {
      DesktopProbeProcess.phase(result, "fixture-setup");
      FixtureLayout layout = FixtureLayout.install(work, directMlImage, tensorRtImage, languageConfig);
      System.setProperty("lizzie.work.dir", work.toString());
      server = FixtureServer.start(layout, scenario);
      configureFixtureProperties(server, scenario);
      setCompanionDigestForTests(sha256(Files.readAllBytes(layout.directMlEngine())));
      DesktopProbeProcess.phase(result, "production-startup");
      Lizzie.main(new String[0]);
      var repairContext =
          KataGoRuntimeHelper.inspectTensorRtStartupFailure(
              layout.tensorRtEngine(), layout.tensorRtEngine().toString());
      assertTrue(repairContext != null, "controlled TensorRT fixture did not produce a repair context");
      assertTrue(
          repairContext.repairable,
          "controlled TensorRT fixture is outside the managed repair root: "
              + repairContext.failedExecutable);
      await(() -> Lizzie.frame != null && Lizzie.frame.isShowing(), "real main window", UI_TIMEOUT);
      awaitControlledAnalysis(gtpRoot.resolve("directml"), 0, "initial DirectML analysis");

      JSONObject initialConfig = readConfig(work);
      Snapshot baseline =
          snapshot(
              "pre-error-directml", result, gtpRoot.resolve("directml"), initialConfig, server);
      EngineFailedMessage failure = selectTensorRtThroughVisibleMenu();
      awaitControlledAnalysis(gtpRoot.resolve("directml"), 0, "settled DirectML rollback");
      clickShowing(failure.tensorRtRepairButton());
      KataGoAutoSetupDialog setup =
          awaitWindow(KataGoAutoSetupDialog.class, "directed TensorRT setup", UI_TIMEOUT);
      assertTrue(setup.hasDirectedRepairContext(failure.repairContext()));
      String directedTargetText =
          String.format(
              Lizzie.resourceBundle.getString("AutoSetup.tensorRtDirectedTarget"),
              failure.repairContext().failedExecutable);
      assertTrue(
          hasShowingText(setup, directedTargetText),
          "directed TensorRT target banner is not visibly showing");
      Snapshot settled =
          snapshot(
              "settled-repair-entry",
              result,
              gtpRoot.resolve("directml"),
              readConfig(work),
              server,
              failure.repairContext());
      if (scenario == Scenario.CONFIRM_CANCEL) records.put("CT-01", "PASS");

      if (scenario == Scenario.CONFIRM_CANCEL) {
        runConfirmationCancel(setup, initialConfig, settled, server);
        records.put("CT-02", "PASS");
      } else if (scenario == Scenario.ACTIVE_CANCEL) {
        runActiveCancel(setup, initialConfig, settled, server, layout);
        records.put("CT-03", "PASS");
      } else {
        runCompletionAndEnable(
            setup, result, gtpRoot, nvidiaLedger, initialConfig, baseline, settled, server, records);
        records.put("CT-04", "PASS");
        records.put("CT-05", "PASS");
      }
      Path httpLedger = result.resolveSibling("http-ledger.txt");
      server.writeLedger(httpLedger);
      Path cleanup = result.resolveSibling("cleanup.txt");
      cleanupProduction(server, layout, cleanup);
      if (scenario == Scenario.COMPLETE_ENABLE) {
        attachFinalEvidence(result, httpLedger, cleanup);
      }
      records.put("http.ledger", httpLedger.toString());
      records.put("cleanup.evidence", cleanup.toString());
      records.put("result", "PASS");
      writeResult(result, records);
      DesktopProbeProcess.phase(result, "controlled-flow-complete");
      exit = 0;
    } catch (Throwable failure) {
      records.put("failure", clean(failure));
      records.put("result", "FAIL");
      writeResult(result, records);
      failure.printStackTrace(System.err);
    } finally {
      if (server != null) server.close();
      closeProduction();
      setCompanionDigestForTests(null);
      System.exit(exit);
    }
  }

  private static void runConfirmationCancel(
      KataGoAutoSetupDialog setup,
      JSONObject initialConfig,
      Snapshot settled,
      FixtureServer server)
      throws Exception {
    int requestsBefore = server.requests.get();
    JDialog confirmation =
        openOptionDialogThroughVisibleButton(
            button(setup, "AutoSetup.repairTensorRt"), "repair confirmation");
    activateShowingButton(cancelButton(confirmation));
    await(() -> confirmation.isVisible() == false, "repair confirmation cancellation", UI_TIMEOUT);
    Thread.sleep(250);
    assertEquals(requestsBefore, server.requests.get(), "confirmation cancel sent a request");
    assertEquals(initialConfig.toString(), readConfig(setupWork()).toString());
    Snapshot after = currentSnapshot(settled.name, settled.gtpRoot, readConfig(setupWork()), server);
    settled.assertSameForeground(after, true);
    assertTrue(setup.isShowing(), "setup dialog became unusable after confirmation cancel");
  }

  private static void runActiveCancel(
      KataGoAutoSetupDialog setup,
      JSONObject initialConfig,
      Snapshot settled,
      FixtureServer server,
      FixtureLayout layout)
      throws Exception {
    JDialog confirmation =
        openOptionDialogThroughVisibleButton(
            button(setup, "AutoSetup.repairTensorRt"), "repair confirmation");
    activateShowingButton(optionButton(confirmation, "OptionPane.okButtonText", "OK"));
    await(() -> server.activeRequests.get() > 0, "slow loopback request", UI_TIMEOUT);
    AbstractButton stop = button(setup, "AutoSetup.stopDownload");
    await(() -> stop.isShowing() && stop.isEnabled(), "shown Stop action", UI_TIMEOUT);
    clickShowing(stop);
    server.releaseSlowResponse();
    await(
        () -> !workerAlive("katago-repair-tensorrt") && server.activeRequests.get() == 0,
        "cancelled repair worker and request cleanup",
        UI_TIMEOUT);
    await(
        () -> hasShowingText(setup, Lizzie.resourceBundle.getString("AutoSetup.downloadCancelled")),
        "localized cancelled status",
        UI_TIMEOUT);
    assertEquals(initialConfig.toString(), readConfig(setupWork()).toString());
    assertFalse(Files.isRegularFile(layout.companionTarget));
    assertFalse(runtimeReady(layout.runtimeRoot));
    Snapshot after = currentSnapshot(settled.name, settled.gtpRoot, readConfig(setupWork()), server);
    settled.assertSameForeground(after, true);
    assertLockAvailable(layout.runtimeRoot.resolve("nvidia-runtime/tensorrt-install.lock"));
  }

  private static void runCompletionAndEnable(
      KataGoAutoSetupDialog setup,
      Path result,
      Path gtpRoot,
      Path nvidiaLedger,
      JSONObject initialConfig,
      Snapshot baseline,
      Snapshot settled,
      FixtureServer server,
      Map<String, String> records)
      throws Exception {
    JDialog confirmation =
        openOptionDialogThroughVisibleButton(
            button(setup, "AutoSetup.repairTensorRt"), "repair confirmation");
    activateShowingButton(optionButton(confirmation, "OptionPane.okButtonText", "OK"));
    JDialog summary = awaitOptionDialog("repair summary", UI_TIMEOUT);
    assertEquals(
        Lizzie.resourceBundle.getString("AutoSetup.tensorRtRepairDone"),
        summary.getTitle(),
        "repair completion opened an unexpected dialog");
    for (String key :
        List.of(
            "AutoSetup.tensorRtRuntimeReady",
            "AutoSetup.tensorRtCompanionReady",
            "AutoSetup.tensorRtEngineReady",
            "AutoSetup.tensorRtProfileInactive")) {
      String expected = Lizzie.resourceBundle.getString(key);
      await(() -> hasShowingText(setup, expected), "visible status " + key, UI_TIMEOUT);
    }
    AbstractButton enable = button(setup, "AutoSetup.enableTensorRt");
    assertTrue(enable.isShowing() && enable.isEnabled(), "TensorRT enable action is not available");
    Snapshot repaired =
        snapshot("post-repair", result, gtpRoot.resolve("directml"), readConfig(setupWork()), server);
    settled.assertSameForeground(repaired, true);
    assertNotEquals(0, server.requests.get(), "successful repair made no loopback requests");
    assertTrue(nvidiaQueriesObserved(nvidiaLedger), "production GPU detector did not use fixture");
    activateShowingButton(optionButton(summary, "OptionPane.okButtonText", "OK"));
    await(() -> !summary.isShowing(), "repair summary dismissal", UI_TIMEOUT);

    JSONObject beforeEnable = readConfig(setupWork());
    clickShowing(enable);
    awaitControlledAnalysis(gtpRoot.resolve("tensorrt"), 1, "controlled TensorRT reload");
    JSONObject afterEnable = readConfig(setupWork());
    validateEnableDelta(beforeEnable, afterEnable);
    Snapshot enabled =
        snapshot("post-enable", result, gtpRoot.resolve("tensorrt"), afterEnable, server);
    assertNotEquals(settled.pid, enabled.pid, "explicit enable did not replace foreground process");
    assertEquals(1, enabled.engineIndex);
    assertTrue(enabled.visits > 0);
    assertEquals(CUSTOM_ANALYSIS, afterEnable.getJSONObject("ui").getString("analysis-engine-command"));
    assertTrue(afterEnable.getJSONObject("ui").getBoolean("analysis-engine-command-customized"));
    assertTrue(afterEnable.getJSONObject("ui").getBoolean("autoload-default"));
    assertFalse(afterEnable.getJSONObject("ui").getBoolean("autoload-empty"));
    assertFalse(afterEnable.getJSONObject("ui").getBoolean("autoload-last"));
    records.put("snapshot.pre-error-directml", baseline.path.toString());
    records.put("snapshot.settled-repair-entry", settled.path.toString());
    records.put("snapshot.post-repair", repaired.path.toString());
    records.put("snapshot.post-enable", enabled.path.toString());
    records.put("config-delta", "PASS");
    records.put("reload-kind", "controlled-reload");
    records.put("config.before-enable", writeJson(result.resolveSibling("config-before-enable.json"), beforeEnable).toString());
    records.put("config.after-enable", writeJson(result.resolveSibling("config-after-enable.json"), afterEnable).toString());
    assertEquals(initialConfig.getJSONObject("ui").getString("analysis-engine-command"), CUSTOM_ANALYSIS);
  }

  private static EngineFailedMessage selectTensorRtThroughVisibleMenu() throws Exception {
    Leelaz failedEngine = Lizzie.engineManager.engineList.get(1);
    assertTrue(
        KataGoRuntimeHelper.isBundledTensorRtCommand(failedEngine.getEngineCommand()),
        "second engine is not classified as bundled TensorRT");
    assertFalse(
        KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed(),
        "benchmark isolation suppressed the controlled TensorRT switch");
    JMenuItem target = Menu.engine[1];
    assertTrue(
        target.getText().contains(TENSORRT),
        "unexpected second engine menu item: " + target.getText());
    openMenuThroughVisibleButton(
        buttonContaining(Lizzie.frame.windowMenuStrip, DIRECTML), target);
    AtomicInteger targetActions = new AtomicInteger();
    target.addActionListener(event -> targetActions.incrementAndGet());
    clickShowing(target);
    await(() -> targetActions.get() == 1, "TensorRT menu action", UI_TIMEOUT);
    EngineFailedMessage failure =
        awaitWindow(EngineFailedMessage.class, "repairable engine failure", UI_TIMEOUT);
    assertTrue(
        failure.repairContext() != null && failure.repairContext().repairable,
        "visible engine failure did not retain a repairable TensorRT context");
    assertTrue(failure.offersTensorRtRepair());
    assertTrue(failure.tensorRtRepairButton().isShowing());
    return failure;
  }

  private static Snapshot snapshot(
      String name, Path result, Path gtpRoot, JSONObject config, FixtureServer server)
      throws Exception {
    return snapshot(name, result, gtpRoot, config, server, null);
  }

  private static Snapshot snapshot(
      String name,
      Path result,
      Path gtpRoot,
      JSONObject config,
      FixtureServer server,
      KataGoRuntimeHelper.TensorRtRepairContext repairContext)
      throws Exception {
    Snapshot snapshot = currentSnapshot(name, gtpRoot, config, server);
    Path path = result.resolveSibling(name + ".json");
    Path image = result.resolveSibling(name + ".png");
    Object incarnation = Lizzie.leelaz == null ? null : Lizzie.leelaz.engineIncarnationToken();
    JSONObject json = snapshot.toJson();
    json.put("source", TensorRtRepairAcceptanceTest.class.getName());
    json.put("controlled", true);
    json.put("childPid", ProcessHandle.current().pid());
    json.put("workRoot", setupWork().toString());
    int languageConfig = Lizzie.config.uiConfig.optInt("use-language");
    json.put("locale", AppLocale.fromConfigValue(languageConfig).locale().toLanguageTag());
    json.put("languageConfig", languageConfig);
    json.put("timestamp", Instant.now().toString());
    json.put("window", visibleWindowTitles());
    json.put("uiState", visibleTensorRtUiState(repairContext));
    json.put("board", boardState());
    json.put(
        "engineIncarnation",
        incarnation == null ? JSONObject.NULL : System.identityHashCode(incarnation));
    json.put("repairContext", repairContextState(repairContext));
    json.put("httpRequests", server.requests.get());
    json.put("httpLedger", result.resolveSibling("http-ledger.txt").toString());
    json.put("screenshot", image.toString());
    writeJson(path, json);
    capture(image);
    return snapshot.withPath(path);
  }

  private static JSONObject boardState() {
    var history = Lizzie.board.getHistory();
    var node = history.getCurrentHistoryNode();
    return new JSONObject()
        .put("boardIdentity", System.identityHashCode(Lizzie.board))
        .put("historyIdentity", System.identityHashCode(history))
        .put("nodeIdentity", System.identityHashCode(node))
        .put("width", Board.boardWidth)
        .put("height", Board.boardHeight)
        .put("moveNumber", history.getMoveNumber())
        .put("komi", history.getGameInfo().getKomi())
        .put("rules", Lizzie.config.kataRules);
  }

  private static JSONObject visibleTensorRtUiState(
      KataGoRuntimeHelper.TensorRtRepairContext repairContext) {
    KataGoAutoSetupDialog setup = null;
    for (Window window : Window.getWindows()) {
      if (window instanceof KataGoAutoSetupDialog candidate && candidate.isShowing()) {
        setup = candidate;
        break;
      }
    }
    JSONObject state = new JSONObject();
    if (setup == null) {
      return state
          .put("accelerationCardVisible", false)
          .put("repairShown", false)
          .put("repairEnabled", false)
          .put("enableShown", false)
          .put("enableEnabled", false)
          .put("runtimeReady", false)
          .put("companionReady", false)
          .put("engineReady", false)
          .put("profileInactive", false)
          .put("directedTargetVisible", false)
          .put("directedTargetText", "");
    }
    AbstractButton repair = button(setup, "AutoSetup.repairTensorRt");
    AbstractButton enable = button(setup, "AutoSetup.enableTensorRt");
    String directedTargetText =
        repairContext == null || repairContext.failedExecutable == null
            ? ""
            : String.format(
                Lizzie.resourceBundle.getString("AutoSetup.tensorRtDirectedTarget"),
                repairContext.failedExecutable);
    return state
        .put("accelerationCardVisible", repair.isShowing() && enable.isShowing())
        .put(
            "directedTargetVisible",
            !directedTargetText.isBlank() && hasShowingText(setup, directedTargetText))
        .put("directedTargetText", directedTargetText)
        .put("repairShown", repair.isShowing())
        .put("repairEnabled", repair.isEnabled())
        .put("enableShown", enable.isShowing())
        .put("enableEnabled", enable.isEnabled())
        .put(
            "runtimeReady",
            hasShowingText(setup, Lizzie.resourceBundle.getString("AutoSetup.tensorRtRuntimeReady")))
        .put(
            "companionReady",
            hasShowingText(setup, Lizzie.resourceBundle.getString("AutoSetup.tensorRtCompanionReady")))
        .put(
            "engineReady",
            hasShowingText(setup, Lizzie.resourceBundle.getString("AutoSetup.tensorRtEngineReady")))
        .put(
            "profileInactive",
            hasShowingText(setup, Lizzie.resourceBundle.getString("AutoSetup.tensorRtProfileInactive")));
  }

  private static Object repairContextState(
      KataGoRuntimeHelper.TensorRtRepairContext repairContext) {
    if (repairContext == null) return JSONObject.NULL;
    return new JSONObject()
        .put("failedExecutable", repairContext.failedExecutable.toString())
        .put("originalCommand", repairContext.originalCommand)
        .put("failureKind", repairContext.failureKind.name())
        .put("missingItems", new JSONArray(repairContext.missingItems))
        .put("repairable", repairContext.repairable)
        .put("displayMessage", repairContext.displayMessage);
  }

  private static void attachFinalEvidence(Path result, Path httpLedger, Path cleanupEvidence)
      throws IOException {
    JSONObject cleanup = new JSONObject(parseStrictResult(cleanupEvidence));
    for (String name :
        List.of("pre-error-directml", "settled-repair-entry", "post-repair", "post-enable")) {
      Path path = result.resolveSibling(name + ".json");
      JSONObject snapshot = new JSONObject(Files.readString(path, StandardCharsets.UTF_8));
      snapshot.put("httpLedger", httpLedger.toString());
      snapshot.put("cleanupEvidence", cleanupEvidence.toString());
      snapshot.put("cleanup", cleanup);
      writeJson(path, snapshot);
    }
  }

  private static Snapshot currentSnapshot(
      String name, Path gtpRoot, JSONObject config, FixtureServer server) throws Exception {
    Leelaz engine = Lizzie.leelaz;
    int index = EngineManager.currentEngineNo;
    EngineData entry = Utils.getEngineData().get(index);
    long pid = readLong(gtpRoot.resolve("peer.pid"));
    int visits = currentVisits();
    String backend =
        Files.readString(
                entryPath(entry)
                    .getParent()
                    .resolve("lizzieyzy-next-engine-backend.txt"))
            .trim();
    return new Snapshot(
        name,
        null,
        gtpRoot,
        pid,
        System.identityHashCode(engine),
        index,
        entry.name,
        entry.commands,
        backend,
        visits,
        config.toString(),
        server.requests.get());
  }

  private static void awaitControlledAnalysis(Path peerRoot, int engineIndex, String description)
      throws Exception {
    Path pid = peerRoot.resolve("peer.pid");
    Path state = peerRoot.resolve("peer-state.txt");
    await(
        () ->
            EngineManager.currentEngineNo == engineIndex
                && Lizzie.leelaz != null
                && Lizzie.leelaz.isLoaded()
                && Files.isRegularFile(pid)
                && Files.isRegularFile(state)
                && peerAnalysisCount(state) > 0
                && currentVisits() > 0,
        description,
        UI_TIMEOUT);
  }

  private static int peerAnalysisCount(Path state) {
    try {
      return Files.readAllLines(state).stream()
          .filter(line -> line.startsWith("analysis.count="))
          .map(line -> line.substring("analysis.count=".length()))
          .mapToInt(Integer::parseInt)
          .findFirst()
          .orElse(0);
    } catch (Exception ignored) {
      return 0;
    }
  }

  private static int currentVisits() {
    try {
      return Lizzie.board.getHistory().getCurrentHistoryNode().getData().getPlayouts();
    } catch (RuntimeException ignored) {
      return 0;
    }
  }

  private static Path entryPath(EngineData entry) {
    List<String> command = Utils.splitCommand(entry.commands);
    if (command.isEmpty()) throw new AssertionError("empty engine command");
    return Path.of(command.get(0)).toAbsolutePath().normalize();
  }

  static void validateEnableDelta(JSONObject before, JSONObject after) {
    JSONObject beforeCopy = new JSONObject(before.toString());
    JSONObject afterCopy = new JSONObject(after.toString());
    JSONObject beforeUi = beforeCopy.getJSONObject("ui");
    JSONObject afterUi = afterCopy.getJSONObject("ui");
    for (String key :
        List.of(
            "default-engine",
            "katago-preferred-weight-path",
            "katago-auto-setup-weight-name",
            "katago-auto-setup-weight-path",
            "katago-auto-setup-engine-path",
            "katago-auto-setup-gtp-config-path",
            "katago-auto-setup-analysis-config-path",
            "katago-auto-setup-updated-at")) {
      beforeUi.remove(key);
      afterUi.remove(key);
    }

    JSONObject beforeLeelaz = beforeCopy.getJSONObject("leelaz");
    JSONObject afterLeelaz = afterCopy.getJSONObject("leelaz");
    JSONArray beforeEngines =
        beforeLeelaz.remove("engine-settings-list") instanceof JSONArray value ? value : null;
    JSONArray afterEngines =
        afterLeelaz.remove("engine-settings-list") instanceof JSONArray value ? value : null;
    assertEquals(2, beforeEngines.length());
    assertEquals(2, afterEngines.length());

    JSONObject directBefore = new JSONObject(beforeEngines.getJSONObject(0).toString());
    JSONObject directAfter = new JSONObject(afterEngines.getJSONObject(0).toString());
    directBefore.remove("isDefault");
    directAfter.remove("isDefault");
    assertTrue(directBefore.similar(directAfter), "DirectML entry changed unexpectedly");
    assertFalse(afterEngines.getJSONObject(0).getBoolean("isDefault"));

    JSONObject tensorBefore = new JSONObject(beforeEngines.getJSONObject(1).toString());
    JSONObject tensorAfter = new JSONObject(afterEngines.getJSONObject(1).toString());
    for (String key :
        List.of(
            "index",
            "name",
            "command",
            "isDefault",
            "useJavaSSH",
            "ip",
            "port",
            "userName",
            "password",
            "useKeyGen",
            "keyGenPath")) {
      tensorBefore.remove(key);
      tensorAfter.remove(key);
    }
    assertTrue(tensorBefore.similar(tensorAfter), "retained TensorRT entry fields changed");
    assertTrue(beforeCopy.similar(afterCopy), "unrelated full configuration delta");

    JSONObject tensor = afterEngines.getJSONObject(1);
    assertEquals("KataGo TensorRT", tensor.getString("name"));
    assertTrue(tensor.getString("command").contains("windows-x64-nvidia-tensorrt"));
    assertTrue(tensor.getBoolean("isDefault"));
    assertFalse(tensor.getBoolean("useJavaSSH"));
    assertFalse(tensor.getBoolean("useKeyGen"));
    for (String key : List.of("ip", "port", "userName", "password", "keyGenPath")) {
      assertEquals("", tensor.getString(key), key);
    }
  }

  private static AbstractButton button(Container root, String resourceKey) {
    String text = Lizzie.resourceBundle.getString(resourceKey);
    List<AbstractButton> matches = new ArrayList<>();
    collectButtons(root, text, matches);
    if (matches.size() != 1) {
      throw new AssertionError("expected one shown button for " + resourceKey + ", found " + matches.size());
    }
    return matches.get(0);
  }

  private static AbstractButton buttonContaining(Container root, String text) {
    List<AbstractButton> matches = new ArrayList<>();
    collectButtonsContaining(root, text, matches);
    if (matches.size() != 1) {
      throw new AssertionError(
          "expected one shown button containing " + text + ", found " + matches.size());
    }
    return matches.get(0);
  }

  private static void collectButtonsContaining(
      Component root, String text, List<AbstractButton> matches) {
    if (root instanceof AbstractButton button
        && button.isShowing()
        && button.getText() != null
        && button.getText().contains(text)) {
      matches.add(button);
    }
    if (root instanceof Container container) {
      for (Component child : container.getComponents()) {
        collectButtonsContaining(child, text, matches);
      }
    }
  }

  private static void collectButtons(Component root, String text, List<AbstractButton> matches) {
    if (root instanceof AbstractButton button
        && button.isShowing()
        && text.equals(button.getText())) {
      matches.add(button);
    }
    if (root instanceof Container container) {
      for (Component child : container.getComponents()) collectButtons(child, text, matches);
    }
  }
  private static boolean hasShowingText(Component root, String expected) {
    AtomicReference<Boolean> found = new AtomicReference<>(false);
    try {
      SwingUtilities.invokeAndWait(() -> found.set(hasShowingTextOnEdt(root, expected)));
    } catch (Exception failure) {
      throw new AssertionError("failed to inspect visible status text", failure);
    }
    return found.get();
  }

  private static boolean hasShowingTextOnEdt(Component root, String expected) {
    if (root == null || expected == null) return false;
    String text = null;
    if (root instanceof JLabel label) text = label.getText();
    if (root instanceof AbstractButton button) text = button.getText();
    if (root instanceof JTextComponent component) text = component.getText();
    if (root.isShowing() && expected.equals(text)) return true;
    if (root instanceof Container container) {
      for (Component child : container.getComponents()) {
        if (hasShowingTextOnEdt(child, expected)) return true;
      }
    }
    return false;
  }

  private static JDialog awaitOptionDialog(String description, Duration timeout) throws Exception {
    return awaitWindow(JDialog.class, description, timeout, true);
  }

  private static JDialog openOptionDialogThroughVisibleButton(
      AbstractButton trigger, String description) throws Exception {
    long deadline = System.nanoTime() + UI_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      clickShowing(trigger);
      long attemptDeadline =
          Math.min(deadline, System.nanoTime() + Duration.ofSeconds(2).toNanos());
      while (System.nanoTime() < attemptDeadline) {
        JDialog dialog = showingOptionDialog();
        if (dialog != null) return dialog;
        Thread.sleep(50);
      }
    }
    throw new AssertionError("timed out waiting for " + description);
  }

  private static JDialog showingOptionDialog() {
    for (Window window : Window.getWindows()) {
      if (!(window instanceof JDialog dialog) || !dialog.isShowing()) continue;
      if (dialog instanceof KataGoAutoSetupDialog || dialog instanceof EngineFailedMessage) continue;
      if (dialog.isModal()) return dialog;
    }
    return null;
  }

  private static JButton optionButton(JDialog dialog, String textKey, String description) {
    String localized = UIManager.getString(textKey, Locale.getDefault());
    List<AbstractButton> matches = new ArrayList<>();
    collectButtons(dialog, localized, matches);
    if (matches.size() != 1) {
      throw new AssertionError(
          "shown option dialog has no unique " + description + " button: " + localized);
    }
    return (JButton) matches.get(0);
  }

  private static JButton cancelButton(JDialog dialog) {
    return optionButton(dialog, "OptionPane.cancelButtonText", "Cancel");
  }

  private static void activateShowingButton(AbstractButton button) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          if (button == null || !button.isShowing() || !button.isEnabled()) {
            throw new AssertionError("option dialog button is not visible and enabled");
          }
          button.doClick();
        });
  }

  private static void openMenuThroughVisibleButton(AbstractButton selector, JMenuItem target)
      throws Exception {
    long deadline = System.nanoTime() + UI_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      clickShowing(selector);
      long attemptDeadline =
          Math.min(deadline, System.nanoTime() + Duration.ofSeconds(2).toNanos());
      while (System.nanoTime() < attemptDeadline) {
        if (target.isShowing()) return;
        Thread.sleep(50);
      }
    }
    throw new AssertionError("timed out waiting for visible TensorRT engine menu item");
  }

  private static void clickShowing(Component component) throws Exception {
    await(
        () -> component != null && component.isShowing() && component.isEnabled(),
        "shown click target",
        UI_TIMEOUT);
    Window owner = SwingUtilities.getWindowAncestor(component);
    if (owner == null) owner = Lizzie.frame;
    if (owner != null && !(component instanceof JMenuItem)) activateWindow(owner);
    java.awt.Point[] center = new java.awt.Point[1];
    SwingUtilities.invokeAndWait(
        () -> {
          if (!component.isShowing()
              || !component.isEnabled()
              || component.getWidth() <= 0
              || component.getHeight() <= 0) {
            throw new AssertionError("component is not showing for Robot click");
          }
          java.awt.Point location = component.getLocationOnScreen();
          center[0] =
              new java.awt.Point(
                  location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
        });
    Robot robot = new Robot();
    robot.mouseMove(center[0].x, center[0].y);
    robot.delay(150);
    robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
    robot.delay(150);
    robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
    robot.delay(150);
  }

  private static void activateWindow(Window window) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          window.toFront();
          window.requestFocus();
        });
    new Robot().delay(250);
  }

  private static <T extends Window> T awaitWindow(
      Class<T> type, String description, Duration timeout) throws Exception {
    return awaitWindow(type, description, timeout, false);
  }

  private static <T extends Window> T awaitWindow(
      Class<T> type, String description, Duration timeout, boolean excludeSetup) throws Exception {
    final Window[] found = new Window[1];
    await(
        () -> {
          for (Window window : Window.getWindows()) {
            if (!type.isInstance(window) || !window.isShowing()) continue;
            if (excludeSetup && window instanceof KataGoAutoSetupDialog) continue;
            if (excludeSetup && window instanceof EngineFailedMessage) continue;
            if (excludeSetup && !(window instanceof Dialog dialog && dialog.isModal())) continue;
            found[0] = window;
            return true;
          }
          return false;
        },
        description,
        timeout);
    return type.cast(found[0]);
  }

  private static boolean workerAlive(String name) {
    return Thread.getAllStackTraces().keySet().stream()
        .anyMatch(thread -> name.equals(thread.getName()) && thread.isAlive());
  }

  private static boolean runtimeReady(Path runtimeRoot) {
    return Files.isRegularFile(runtimeRoot.resolve("nvidia-runtime/nvinfer_10.dll"));
  }

  private static void assertLockAvailable(Path lockPath) throws Exception {
    Files.createDirectories(lockPath.getParent());
    try (java.nio.channels.FileChannel channel =
            java.nio.channels.FileChannel.open(
                lockPath,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE);
        java.nio.channels.FileLock ignored = channel.tryLock()) {
      assertTrue(ignored != null, "TensorRT repair lock remained held");
    }
  }

  private static boolean nvidiaQueriesObserved(Path ledger) {
    try {
      String text = Files.readString(ledger);
      return text.contains("--query-gpu=name,compute_cap,driver_version,memory.total")
          && text.contains("--format=csv,noheader,nounits");
    } catch (IOException ignored) {
      return false;
    }
  }

  private static JSONObject readConfig(Path work) throws IOException {
    return new JSONObject(Files.readString(work.resolve("config.txt"), StandardCharsets.UTF_8));
  }

  private static Path setupWork() {
    return Lizzie.config.getWorkDirectory().toPath().toAbsolutePath().normalize();
  }

  private static Path writeJson(Path path, JSONObject value) throws IOException {
    Files.writeString(path, value.toString(2) + "\n", StandardCharsets.UTF_8);
    return path;
  }

  private static void capture(Path path) {
    try {
      List<Window> visible =
          Arrays.stream(Window.getWindows()).filter(Window::isShowing).toList();
      Window preferred =
          visible.stream()
              .filter(Window::isActive)
              .findFirst()
              .orElseGet(
                  () ->
                      visible.stream()
                          .filter(Dialog.class::isInstance)
                          .reduce((first, second) -> second)
                          .orElse(Lizzie.frame));
      if (preferred != null) activateWindow(preferred);
      Rectangle bounds = null;
      for (Window window : visible) {
        bounds = bounds == null ? window.getBounds() : bounds.union(window.getBounds());
      }
      if (bounds == null || bounds.isEmpty()) {
        throw new AssertionError("no visible application window to capture");
      }
      BufferedImage image = new Robot().createScreenCapture(bounds);
      ImageIO.write(image, "png", path.toFile());
    } catch (Exception failure) {
      throw new AssertionError("failed to capture " + path, failure);
    }
  }

  private static JSONArray visibleWindowTitles() {
    JSONArray titles = new JSONArray();
    for (Window window : Window.getWindows()) {
      if (!window.isShowing()) continue;
      titles.put(window instanceof Dialog dialog ? dialog.getTitle() : window.getName());
    }
    return titles;
  }

  private static void cleanupProduction(
      FixtureServer server, FixtureLayout layout, Path evidence) throws Exception {
    if (Lizzie.engineManager != null) Lizzie.engineManager.forceKillAllEngines();
    await(
        () ->
            !workerAlive("katago-repair-tensorrt")
                && !workerAlive("katago-weight-engine-reload-1")
                && server.activeRequests.get() == 0,
        "owned cleanup",
        UI_TIMEOUT);
    assertLockAvailable(layout.runtimeRoot.resolve("nvidia-runtime/tensorrt-install.lock"));
    long partialFiles;
    try (var paths = Files.walk(layout.runtimeRoot)) {
      partialFiles =
          paths
              .filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().endsWith(".part"))
              .count();
    }
    Files.writeString(
        evidence,
        "workers=false\nrequests="
            + server.activeRequests.get()
            + "\nlock=available\npartialFiles="
            + partialFiles
            + "\nfixtureRoot=parent-owned\n",
        StandardCharsets.UTF_8);
  }

  private static void closeProduction() {
    try {
      if (Lizzie.engineManager != null) Lizzie.engineManager.forceKillAllEngines();
      SwingUtilities.invokeAndWait(
          () -> Arrays.stream(Window.getWindows()).forEach(Window::dispose));
    } catch (Exception ignored) {
      // DesktopProbeProcess remains the final process-tree owner.
    }
  }

  private static void configureFixtureProperties(FixtureServer server, Scenario scenario) {
    System.setProperty("lizzie.tensorrt.runtimeSearchPath", "");
    System.setProperty("lizzie.tensorrt.skipRuntimePackagesForTests", "true");
    System.setProperty("lizzie.tensorrt.katago.url", server.url("/fail-engine"));
    System.setProperty("lizzie.tensorrt.katago.sha256", EMPTY_SHA256);
    System.setProperty("lizzie.tensorrt.katago.size", "0");
    String companion = scenario == Scenario.ACTIVE_CANCEL ? "/slow-companion" : "/companion";
    System.setProperty("lizzie.tensorrt.companion.url", server.url(companion));
    System.setProperty("lizzie.tensorrt.companion.sha256", server.companionSha256);
    System.setProperty("lizzie.tensorrt.companion.size", Long.toString(server.companion.length));
    String runtime = scenario == Scenario.ACTIVE_CANCEL ? "/slow-runtime" : "/runtime";
    System.setProperty("lizzie.tensorrt.runtime.fixture.url", server.url(runtime));
    System.setProperty("lizzie.tensorrt.runtime.fixture.sha256", server.runtimeSha256);
    System.setProperty("lizzie.tensorrt.runtime.fixture.size", Long.toString(server.runtime.length));
  }

  private static void setCompanionDigestForTests(String sha256) throws Exception {
    var method =
        KataGoRuntimeHelper.class.getDeclaredMethod("setHumanSlCompanionSha256ForTests", String.class);
    method.setAccessible(true);
    method.invoke(null, sha256);
  }

  private static void requireNativeWindowsJdk21() throws Exception {
    if (!System.getProperty("os.name", "").startsWith("Windows")) {
      throw new AssertionError("TensorRT UI acceptance requires native Windows");
    }
    if (!System.getProperty("java.version", "").startsWith("21.")) {
      throw new AssertionError("TensorRT UI acceptance requires JDK 21");
    }
    Path jpackage = tool("jpackage");
    if (!Files.isRegularFile(jpackage)) {
      throw new AssertionError("TensorRT UI acceptance requires JDK 21 jpackage: " + jpackage);
    }
  }

  private static Path tool(String name) {
    return Path.of(System.getProperty("java.home"), "bin", name + ".exe");
  }

  private static long readLong(Path path) throws IOException {
    return Long.parseLong(Files.readString(path, StandardCharsets.UTF_8).trim());
  }

  private static void await(BooleanSupplier condition, String description, Duration timeout)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
      Thread.sleep(50);
    }
    throw new AssertionError("timed out waiting for " + description);
  }

  private static String clean(Throwable failure) {
    String message = failure.getMessage();
    return failure.getClass().getName() + ": " + (message == null ? "" : message.replace('\n', ' '));
  }

  private static void writeResult(Path result, Map<String, String> records) throws IOException {
    StringBuilder text = new StringBuilder();
    records.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
    Files.writeString(result, text.toString(), StandardCharsets.UTF_8);
  }

  private static Map<String, String> parseStrictResult(Path result) throws IOException {
    Map<String, String> records = new LinkedHashMap<>();
    for (String line : Files.readAllLines(result, StandardCharsets.UTF_8)) {
      int separator = line.indexOf('=');
      if (separator <= 0 || separator == line.length() - 1) {
        throw new AssertionError("Malformed child result row: " + line);
      }
      String key = line.substring(0, separator);
      String value = line.substring(separator + 1);
      if (records.putIfAbsent(key, value) != null) {
        throw new AssertionError("Duplicate child result key: " + key);
      }
    }
    return records;
  }

  private static String required(Map<String, String> records, String key) {
    String value = records.get(key);
    if (value == null || value.isBlank()) throw new AssertionError("Missing child result: " + key);
    return value;
  }

  private static void copyTree(Path source, Path target) throws IOException {
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(target.resolve(source.relativize(dir).toString()));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.copy(
                file,
                target.resolve(source.relativize(file).toString()),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (root == null || !Files.exists(root)) return;
    IOException lastFailure = null;
    for (int attempt = 1; attempt <= 50; attempt++) {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          DosFileAttributeView attributes =
              Files.getFileAttributeView(path, DosFileAttributeView.class);
          if (attributes != null && attributes.readAttributes().isReadOnly()) {
            attributes.setReadOnly(false);
          }
          Files.deleteIfExists(path);
        }
        return;
      } catch (AccessDeniedException | DirectoryNotEmptyException transientLock) {
        lastFailure = transientLock;
        try {
          Thread.sleep(100);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while deleting native fixtures", interrupted);
        }
      }
    }
    throw lastFailure;
  }

  private enum Scenario {
    CONFIRM_CANCEL("confirm-cancel"),
    ACTIVE_CANCEL("active-cancel"),
    COMPLETE_ENABLE("complete-enable");

    final String argument;

    Scenario(String argument) {
      this.argument = argument;
    }

    static Scenario parse(String value) {
      return Arrays.stream(values())
          .filter(item -> item.argument.equals(value))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("unknown scenario: " + value));
    }
  }

  private record Snapshot(
      String name,
      Path path,
      Path gtpRoot,
      long pid,
      int engineIdentity,
      int engineIndex,
      String engineName,
      String command,
      String backend,
      int visits,
      String profile,
      int requests) {
    Snapshot withPath(Path value) {
      return new Snapshot(
          name,
          value,
          gtpRoot,
          pid,
          engineIdentity,
          engineIndex,
          engineName,
          command,
          backend,
          visits,
          profile,
          requests);
    }

    void assertSameForeground(Snapshot other, boolean positiveVisits) {
      assertEquals(pid, other.pid, "foreground PID changed");
      assertEquals(engineIdentity, other.engineIdentity, "foreground engine object changed");
      assertEquals(engineIndex, other.engineIndex, "foreground index changed");
      assertEquals(engineName, other.engineName, "foreground name changed");
      assertEquals(command, other.command, "foreground command changed");
      assertEquals(profile, other.profile, "profile changed");
      if (positiveVisits) {
        assertTrue(visits > 0, "baseline visits were not positive");
        assertTrue(other.visits > 0, "later visits were not positive");
      }
    }

    JSONObject toJson() {
      return new JSONObject()
          .put("name", name)
          .put("pid", pid)
          .put("engineIdentity", engineIdentity)
          .put("engineIndex", engineIndex)
          .put("engineName", engineName)
          .put("command", command)
          .put("backend", backend)
          .put("visits", visits)
          .put("profile", new JSONObject(profile))
          .put("httpRequests", requests)
          .put("gtpRoot", gtpRoot.toString());
    }
  }

  private record FixtureLayout(
      Path runtimeRoot,
      Path directMlEngine,
      Path tensorRtEngine,
      Path companionTarget,
      Path gtpConfig,
      Path analysisConfig,
      Path weight) {
    static FixtureLayout install(
        Path work, Path directMlImage, Path tensorRtImage, int languageConfig) throws Exception {
      Path runtimeRoot = Files.createDirectories(work.resolve("runtime"));
      Path directDir =
          runtimeRoot.resolve("engines/katago/windows-x64-directml").toAbsolutePath().normalize();
      Path tensorDir =
          runtimeRoot
              .resolve("engines/katago/windows-x64-nvidia-tensorrt")
              .toAbsolutePath()
              .normalize();
      copyTree(directMlImage, directDir);
      copyTree(tensorRtImage, tensorDir);
      Path direct = directDir.resolve("katago.exe");
      Path tensor = tensorDir.resolve("katago.exe");
      Files.writeString(directDir.resolve("lizzieyzy-next-engine-backend.txt"), "directml\n");
      Files.writeString(
          tensorDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt\n");
      Files.writeString(
          tensorDir.resolve("lizzieyzy-next-katago-engine-manifest.txt"),
          "KataGo release: v1.18.1\n"
              + "Asset: katago-v1.18.1-trt10.9.0-cuda12.8-windows-x64.zip\n"
              + "Asset SHA-256: 49b7229803b2ccee5205cc9d1f7b1a37790469405324de5e5acaafe7a8a9172a\n");
      Path configs = Files.createDirectories(runtimeRoot.resolve("engines/katago/configs"));
      Path gtp = Files.writeString(configs.resolve("gtp.cfg"), "# controlled\n");
      Path analysis = Files.writeString(configs.resolve("analysis.cfg"), "# controlled\n");
      Path weight =
          Files.writeString(
              Files.createDirectories(runtimeRoot.resolve("weights")).resolve("controlled.bin.gz"),
              "controlled");
      JSONObject directEntry = engineEntry("directml-id", DIRECTML, command(direct, gtp, weight), true);
      JSONObject tensorEntry = engineEntry("tensorrt-id", TENSORRT, command(tensor, gtp, weight), false);
      JSONObject ui =
          new JSONObject()
              .put("autoload-default", true)
              .put("autoload-empty", false)
              .put("autoload-last", false)
              .put("default-engine", 0)
              .put("first-time-load", false)
              .put("enable-startup-benchmark", false)
              .put("use-language", languageConfig)
              .put("analysis-engine-command", CUSTOM_ANALYSIS)
              .put("analysis-engine-command-customized", true)
              .put("katago-auto-setup-engine-path", direct.toString())
              .put("katago-auto-setup-gtp-config-path", gtp.toString())
              .put("katago-auto-setup-analysis-config-path", analysis.toString())
              .put("katago-auto-setup-weight-path", weight.toString())
              .put("katago-preferred-weight-path", weight.toString())
              .put("katago-auto-setup-weight-name", weight.getFileName().toString());
      JSONObject root =
          new JSONObject()
              .put(
                  "leelaz",
                  new JSONObject()
                      .put("fast-engine-change", false)
                      .put("engine-settings-list", new JSONArray().put(directEntry).put(tensorEntry)))
              .put("ui", ui);
      Files.writeString(work.resolve("config.txt"), root.toString(2), StandardCharsets.UTF_8);
      return new FixtureLayout(
          runtimeRoot,
          direct,
          tensor,
          tensorDir.resolve("katago-human-sl-cuda.exe"),
          gtp,
          analysis,
          weight);
    }

    private static JSONObject engineEntry(
        String id, String name, String command, boolean isDefault) {
      return new JSONObject()
          .put("id", id)
          .put("threadPolicy", new JSONObject().put("source", "CFG").put("sourceRevision", 0))
          .put("command", command)
          .put("name", name)
          .put("preload", false)
          .put("komi", 7.5)
          .put("width", 19)
          .put("height", 19)
          .put("isDefault", isDefault)
          .put("useJavaSSH", false)
          .put("ip", "")
          .put("port", "")
          .put("userName", "")
          .put("password", "")
          .put("useKeyGen", false)
          .put("keyGenPath", "")
          .put("initialCommand", "");
    }

    private static String command(Path engine, Path config, Path weight) {
      return quote(engine) + " gtp -config " + quote(config) + " -model " + quote(weight);
    }

    private static String quote(Path path) {
      return "\"" + path.toAbsolutePath().normalize() + "\"";
    }
  }

  private static final class FixtureServer implements AutoCloseable {
    final HttpServer server;
    final ExecutorService executor;
    final byte[] companion;
    final byte[] runtime;
    final String companionSha256;
    final String runtimeSha256;
    final AtomicInteger requests = new AtomicInteger();
    final AtomicInteger activeRequests = new AtomicInteger();
    final CountDownLatch slowRelease = new CountDownLatch(1);
    final List<String> ledger = java.util.Collections.synchronizedList(new ArrayList<>());

    private FixtureServer(
        HttpServer server,
        ExecutorService executor,
        byte[] companion,
        byte[] runtime,
        String companionSha256,
        String runtimeSha256) {
      this.server = server;
      this.executor = executor;
      this.companion = companion;
      this.runtime = runtime;
      this.companionSha256 = companionSha256;
      this.runtimeSha256 = runtimeSha256;
    }

    static FixtureServer start(FixtureLayout layout, Scenario scenario) throws Exception {
      byte[] companion = zipSingle("katago.exe", Files.readAllBytes(layout.tensorRtEngine));
      byte[] runtime = runtimeZip();
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      ExecutorService executor = Executors.newCachedThreadPool();
      FixtureServer fixture =
          new FixtureServer(
              server,
              executor,
              companion,
              runtime,
              sha256(companion),
              sha256(runtime));
      server.createContext("/companion", exchange -> fixture.respond(exchange, companion, false));
      server.createContext("/slow-companion", exchange -> fixture.respond(exchange, companion, true));
      server.createContext("/runtime", exchange -> fixture.respond(exchange, runtime, false));
      server.createContext("/slow-runtime", exchange -> fixture.respond(exchange, runtime, true));
      server.createContext("/fail-engine", fixture::fail);
      server.setExecutor(executor);
      server.start();
      return fixture;
    }

    String url(String path) {
      return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    void writeLedger(Path path) throws IOException {
      synchronized (ledger) {
        Files.writeString(path, String.join("\n", ledger) + "\n", StandardCharsets.UTF_8);
      }
    }

    void releaseSlowResponse() {
      slowRelease.countDown();
    }

    private void respond(HttpExchange exchange, byte[] bytes, boolean slow) throws IOException {
      requests.incrementAndGet();
      activeRequests.incrementAndGet();
      ledger.add(Instant.now() + " " + exchange.getRequestURI() + " START");
      try {
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
          if (slow) {
            int first = Math.min(32, bytes.length);
            body.write(bytes, 0, first);
            body.flush();
            try {
              slowRelease.await();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
            if (first < bytes.length) body.write(bytes, first, bytes.length - first);
          } else {
            body.write(bytes);
          }
        }
      } catch (IOException cancelled) {
        ledger.add(Instant.now() + " " + exchange.getRequestURI() + " CANCELLED");
      } finally {
        activeRequests.decrementAndGet();
        ledger.add(Instant.now() + " " + exchange.getRequestURI() + " END");
        exchange.close();
      }
    }

    private void fail(HttpExchange exchange) throws IOException {
      requests.incrementAndGet();
      ledger.add(Instant.now() + " " + exchange.getRequestURI() + " FAIL");
      exchange.sendResponseHeaders(500, -1);
      exchange.close();
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }

    private static byte[] zipSingle(String name, byte[] bytes) throws IOException {
      java.io.ByteArrayOutputStream storage = new java.io.ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(storage)) {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
      }
      return storage.toByteArray();
    }

    private static byte[] runtimeZip() throws IOException {
      java.io.ByteArrayOutputStream storage = new java.io.ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(storage)) {
        for (String name :
            List.of(
                "cudart64_12.dll",
                "cublas64_12.dll",
                "cublasLt64_12.dll",
                "cudnn64_9.dll",
                "nvJitLink64_12.dll",
                "nvrtc64_120_0.dll",
                "nvrtc-builtins64_128.dll",
                "nvinfer_10.dll",
                "nvinfer_plugin_10.dll",
                "z.dll")) {
          zip.putNextEntry(new ZipEntry(name));
          zip.closeEntry();
        }
      }
      return storage.toByteArray();
    }
  }

  private record NativeFixtures(Path nvidiaImage, Path directMlImage, Path tensorRtImage) {
    static NativeFixtures create(Path root, Path manifestPath) throws Exception {
      Path input = Files.createDirectories(root.resolve("input"));
      Path jar = input.resolve("fixture.jar");
      buildFixtureJar(jar);
      Path nvidia = buildImage(root.resolve("nvidia"), input, "nvidia-smi", "nvidia", null);
      Path direct = buildImage(root.resolve("directml"), input, "katago", "gtp", "directml");
      Path tensor = buildImage(root.resolve("tensorrt"), input, "katago", "gtp", "tensorrt");
      Path nvidiaExe = nvidia.resolve("nvidia-smi.exe");
      String withCompute =
          runBounded(
              List.of(
                  nvidiaExe.toString(),
                  "--query-gpu=name,compute_cap,driver_version,memory.total",
                  "--format=csv,noheader,nounits"),
              root,
              Duration.ofSeconds(15));
      String withoutCompute =
          runBounded(
              List.of(
                  nvidiaExe.toString(),
                  "--query-gpu=name,driver_version,memory.total",
                  "--format=csv,noheader,nounits"),
              root,
              Duration.ofSeconds(15));
      if (!withCompute.contains(TensorRtNativeFixtureCli.NVIDIA_SENTINEL)
          || !withoutCompute.contains(TensorRtNativeFixtureCli.NVIDIA_SENTINEL)) {
        throw new AssertionError("nvidia-smi app image self-check failed");
      }

      Path selfCheckRoot = Files.createDirectories(root.resolve("gtp-self-check"));
      Map<String, String> selfCheckEnvironment =
          Map.of("LIZZIE_CONTROLLED_GTP_ROOT", selfCheckRoot.toString());
      Path directExe = direct.resolve("katago.exe");
      Path tensorExe = tensor.resolve("katago.exe");
      String directOutput =
          runBounded(
              List.of(directExe.toString()),
              root,
              selfCheckEnvironment,
              "name\nquit\n",
              Duration.ofSeconds(15));
      String tensorOutput =
          runBounded(
              List.of(tensorExe.toString()),
              root,
              selfCheckEnvironment,
              "name\nquit\n",
              Duration.ofSeconds(15));
      if (!directOutput.contains("KataGo")
          || !tensorOutput.contains("KataGo")
          || !Files.isRegularFile(selfCheckRoot.resolve("directml/peer.pid"))
          || !Files.isRegularFile(selfCheckRoot.resolve("tensorrt/peer.pid"))) {
        throw new AssertionError("native GTP app image self-check failed");
      }

      Files.writeString(
          manifestPath,
          "jpackage="
              + tool("jpackage")
              + "\nnvidia.exe="
              + nvidiaExe
              + "\nnvidia.sha256="
              + sha256(Files.readAllBytes(nvidiaExe))
              + "\nquery.compute="
              + oneLine(withCompute)
              + "\nquery.fallback="
              + oneLine(withoutCompute)
              + "\ndirectml.exe="
              + directExe
              + "\ndirectml.sha256="
              + sha256(Files.readAllBytes(directExe))
              + "\ndirectml.output="
              + oneLine(directOutput)
              + "\ntensorrt.exe="
              + tensorExe
              + "\ntensorrt.sha256="
              + sha256(Files.readAllBytes(tensorExe))
              + "\ntensorrt.output="
              + oneLine(tensorOutput)
              + "\n",
          StandardCharsets.UTF_8);
      return new NativeFixtures(nvidia, direct, tensor);
    }

    private static Path buildImage(
        Path output, Path input, String name, String mode, String backend) throws Exception {
      Files.createDirectories(output);
      List<String> command =
          new ArrayList<>(
              List.of(
                  tool("jpackage").toString(),
                  "--type",
                  "app-image",
                  "--dest",
                  output.toString(),
                  "--input",
                  input.toString(),
                  "--name",
                  name,
                  "--main-jar",
                  "fixture.jar",
                  "--main-class",
                  TensorRtNativeFixtureCli.class.getName(),
                  "--java-options",
                  "-Dfixture.mode=" + mode));
      if (backend != null) {
        command.add("--java-options");
        command.add("-Dfixture.backend=" + backend);
      }
      runBounded(command, output, Duration.ofMinutes(2));
      Path image = output.resolve(name);
      Path launcher = image.resolve(name + ".exe");
      if (!Files.isRegularFile(launcher)) {
        throw new AssertionError("jpackage omitted native launcher: " + launcher);
      }
      return image;
    }

    private static void buildFixtureJar(Path jar) throws Exception {
      Path classes = Path.of("target", "test-classes").toAbsolutePath().normalize();
      Path packageDir = classes.resolve("featurecat/lizzie/gui");
      Manifest manifest = new Manifest();
      manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
      manifest
          .getMainAttributes()
          .put(Attributes.Name.MAIN_CLASS, TensorRtNativeFixtureCli.class.getName());
      try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest);
          var files = Files.list(packageDir)) {
        for (Path file :
            files
                .filter(Files::isRegularFile)
                .filter(
                    path -> {
                      String name = path.getFileName().toString();
                      return name.startsWith("TensorRtNativeFixtureCli")
                          || name.startsWith("ControlledGtpPeer")
                          || name.startsWith("PeerEvidenceFiles");
                    })
                .toList()) {
          String entry = "featurecat/lizzie/gui/" + file.getFileName();
          output.putNextEntry(new JarEntry(entry));
          Files.copy(file, output);
          output.closeEntry();
        }
      }
    }
  }

  static String runBounded(List<String> command, Path directory, Duration timeout)
      throws Exception {
    return runBounded(command, directory, Map.of(), null, timeout);
  }

  private static String runBounded(
      List<String> command,
      Path directory,
      Map<String, String> environment,
      String standardInput,
      Duration timeout)
      throws Exception {
    Path stdout = Files.createTempFile(directory, "command-", ".out");
    Path stderr = Files.createTempFile(directory, "command-", ".err");
    Process process = null;
    Map<Long, ProcessHandle> owned = new LinkedHashMap<>();
    try {
      ProcessBuilder builder =
          new ProcessBuilder(command)
              .directory(directory.toFile())
              .redirectOutput(stdout.toFile())
              .redirectError(stderr.toFile());
      builder.environment().putAll(environment);
      process = builder.start();
      owned.put(process.pid(), process.toHandle());
      try (OutputStream input = process.getOutputStream()) {
        if (standardInput != null) input.write(standardInput.getBytes(StandardCharsets.UTF_8));
      }

      long deadline = System.nanoTime() + timeout.toNanos();
      while (process.isAlive() && System.nanoTime() < deadline) {
        rememberOwnedDescendants(process, owned);
        process.waitFor(100, TimeUnit.MILLISECONDS);
      }
      rememberOwnedDescendants(process, owned);
      if (process.isAlive()) throw new AssertionError("command timed out: " + command);

      String output = Files.readString(stdout, StandardCharsets.UTF_8);
      String error = Files.readString(stderr, StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        throw new AssertionError(
            "command exited " + process.exitValue() + ": " + command + "\n" + output + error);
      }
      return output;
    } finally {
      if (process != null) terminateOwnedProcessTree(process, owned, command);
    }
  }

  private static void rememberOwnedDescendants(
      Process process, Map<Long, ProcessHandle> owned) {
    process.descendants().forEach(handle -> owned.putIfAbsent(handle.pid(), handle));
  }

  private static void terminateOwnedProcessTree(
      Process process, Map<Long, ProcessHandle> owned, List<String> command) {
    rememberOwnedDescendants(process, owned);
    List<ProcessHandle> handles = new ArrayList<>(owned.values());
    handles.remove(process.toHandle());
    handles.add(process.toHandle());
    destroyAlive(handles, false);
    boolean interrupted = false;
    long gracefulDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
    while (handles.stream().anyMatch(ProcessHandle::isAlive)
        && System.nanoTime() < gracefulDeadline) {
      try {
        TimeUnit.MILLISECONDS.sleep(25);
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    destroyAlive(handles, true);
    long forcedDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (handles.stream().anyMatch(ProcessHandle::isAlive)
        && System.nanoTime() < forcedDeadline) {
      try {
        TimeUnit.MILLISECONDS.sleep(25);
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    List<Long> survivors =
        handles.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList();
    if (interrupted) Thread.currentThread().interrupt();
    if (!survivors.isEmpty()) {
      throw new AssertionError(
          "command process tree survived cleanup " + survivors + ": " + command);
    }
  }

  private static void destroyAlive(List<ProcessHandle> handles, boolean forcibly) {
    for (ProcessHandle handle : handles) {
      if (!handle.isAlive()) continue;
      try {
        if (forcibly) handle.destroyForcibly();
        else handle.destroy();
      } catch (RuntimeException ignored) {
        // The survivor check below remains authoritative.
      }
    }
  }

  private static String oneLine(String value) {
    return value.replace('\r', ' ').replace('\n', ' ').strip();
  }

  private static String sha256(byte[] bytes) throws Exception {
    byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder text = new StringBuilder();
    for (byte value : hash) text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
    return text.toString();
  }
}
