package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.SyncDiagnosticsExportSnapshot;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.logging.DiagnosticBundleExporter;
import featurecat.lizzie.logging.DiagnosticBundleRequest;
import featurecat.lizzie.logging.LogCategories;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.TraceScope;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class AnalysisEngineCommandHelperTest {
  @TempDir Path tempDir;

  @BeforeAll
  static void loadRuntimeClasses() throws ClassNotFoundException {
    Class.forName(KataGoAutoSetupHelper.class.getName());
    Class.forName(KataGoRuntimeHelper.class.getName());
    Class.forName(Lizzie.class.getName());
  }

  @Test
  void convertsSavedKataGoEngineAndCreatesMissingAnalysisConfig() throws Exception {
    Path gtpConfig = tempDir.resolve("katago_configs").resolve("default_gtp.cfg");
    Path analysisConfig = gtpConfig.resolveSibling("analysis.cfg");
    Files.createDirectories(gtpConfig.getParent());
    Files.writeString(gtpConfig, "gtp config", StandardCharsets.UTF_8);
    EngineData engine =
        engine(
            "KataGo",
            quote(tempDir.resolve("katago.exe"))
                + " gtp -model "
                + quote(tempDir.resolve("weights").resolve("model.bin.gz"))
                + " -config "
                + quote(gtpConfig));

    AnalysisEngineCommandHelper.Result result = AnalysisEngineCommandHelper.fromSavedEngine(engine);

    assertTrue(result.isSuccess(), result.getMessage());
    List<String> parts = Utils.splitCommand(result.getCommand());
    assertEquals("analysis", parts.get(1));
    assertEquals(analysisConfig.toString(), parts.get(parts.indexOf("-config") + 1));
    assertTrue(parts.contains("-quit-without-waiting"));
    assertTrue(result.generatedConfig());
    assertEquals(analysisConfig, result.getAnalysisConfigPath());
    assertTrue(Files.exists(analysisConfig));
    assertTrue(
        Files.readString(analysisConfig, StandardCharsets.UTF_8)
            .contains("Config for KataGo C++ Analysis engine"));
    assertTrue(result.getMessage().contains("analysis.cfg"));
    assertTrue(result.getMessage().contains(analysisConfig.toString()));
  }

  @Test
  void missingEngineDirectoryDoesNotCreatePartialAnalysisConfigTree() {
    Path gtpConfig = tempDir.resolve("missing-engine").resolve("configs").resolve("gtp.cfg");
    Path analysisConfig = gtpConfig.resolveSibling("analysis.cfg");
    EngineData engine =
        engine(
            "Missing KataGo",
            quote(tempDir.resolve("missing-engine").resolve("katago.exe"))
                + " gtp -model model.bin.gz -config "
                + quote(gtpConfig));

    AnalysisEngineCommandHelper.Result result =
        AnalysisEngineCommandHelper.fromSavedEngine(engine);

    assertFalse(result.isSuccess());
    assertFalse(Files.exists(analysisConfig));
    assertFalse(Files.exists(gtpConfig.getParent()));
  }

  @Test
  void doesNotReplaceGtpInsidePathsAndDoesNotDuplicateQuitFlag() throws Exception {
    Path executable = tempDir.resolve("tools with gtp").resolve("katago.exe");
    Path gtpConfig = tempDir.resolve("katago_configs").resolve("gtp.cfg");
    Path analysisConfig = gtpConfig.resolveSibling("analysis.cfg");
    Files.createDirectories(executable.getParent());
    Files.createDirectories(gtpConfig.getParent());
    Files.writeString(analysisConfig, "existing analysis config", StandardCharsets.UTF_8);
    EngineData engine =
        engine(
            "KataGo",
            quote(executable)
                + " gtp -model model.bin.gz -config "
                + quote(gtpConfig)
                + " -quit-without-waiting");

    AnalysisEngineCommandHelper.Result result = AnalysisEngineCommandHelper.fromSavedEngine(engine);

    assertTrue(result.isSuccess(), result.getMessage());
    List<String> parts = Utils.splitCommand(result.getCommand());
    assertEquals(executable.toString(), parts.get(0));
    assertEquals("analysis", parts.get(1));
    assertEquals(analysisConfig.toString(), parts.get(parts.indexOf("-config") + 1));
    assertEquals(1, parts.stream().filter("-quit-without-waiting"::equals).count());
    assertFalse(result.generatedConfig());
    assertEquals(
        "existing analysis config", Files.readString(analysisConfig, StandardCharsets.UTF_8));
  }

  @Test
  void rejectsRemoteSavedEngines() {
    EngineData engine = engine("Remote", "katago gtp -model model.bin.gz -config gtp.cfg");
    engine.useJavaSSH = true;

    AnalysisEngineCommandHelper.Result result = AnalysisEngineCommandHelper.fromSavedEngine(engine);

    assertFalse(result.isSuccess());
    String message = result.getMessage().toLowerCase(java.util.Locale.ROOT);
    assertTrue(message.contains("remote") || message.contains("远程"));
  }

  @Test
  void rejectsCommandsWithoutStandaloneGtp() {
    AnalysisEngineCommandHelper.Result result =
        AnalysisEngineCommandHelper.fromSavedEngine(
            engine("No gtp", "katago analysis -model model.bin.gz -config analysis.cfg"));

    assertFalse(result.isSuccess());
    assertTrue(result.getMessage().contains("gtp"));
  }

  @Test
  void rejectsCommandsWithoutConfig() {
    AnalysisEngineCommandHelper.Result result =
        AnalysisEngineCommandHelper.fromSavedEngine(
            engine("No config", "katago gtp -model model.bin.gz"));

    assertFalse(result.isSuccess());
    assertTrue(result.getMessage().contains("config"));
  }

  @Test
  void convertsCurrentDefaultEngineWhenFlashCommandIsNotCustomized() throws Exception {
    Path firstConfig = tempDir.resolve("first").resolve("gtp.cfg");
    Path defaultConfig = tempDir.resolve("default").resolve("gtp.cfg");
    Files.createDirectories(firstConfig.getParent());
    Files.createDirectories(defaultConfig.getParent());
    ArrayList<EngineData> engines = new ArrayList<>();
    engines.add(engine("first", "katago gtp -model first.bin.gz -config " + quote(firstConfig)));
    engines.add(
        engine("default", "katago gtp -model default.bin.gz -config " + quote(defaultConfig)));
    engines.get(1).isDefault = true;

    AnalysisEngineCommandHelper.Result result =
        AnalysisEngineCommandHelper.fromDefaultEngine(engines);

    assertTrue(result.isSuccess(), result.getMessage());
    List<String> parts = Utils.splitCommand(result.getCommand());
    assertEquals("default.bin.gz", parts.get(parts.indexOf("-model") + 1));
    assertEquals(
        defaultConfig.resolveSibling("analysis.cfg").toString(),
        parts.get(parts.indexOf("-config") + 1));
  }

  @Test
  void convertsCurrentEngineBeforeDefaultEngineWhenFlashCommandIsNotCustomized()
      throws Exception {
    Path currentConfig = tempDir.resolve("current").resolve("gtp.cfg");
    Path defaultConfig = tempDir.resolve("default-current").resolve("gtp.cfg");
    Files.createDirectories(currentConfig.getParent());
    Files.createDirectories(defaultConfig.getParent());
    ArrayList<EngineData> engines = new ArrayList<>();
    engines.add(
        engine("current", "katago gtp -model current.bin.gz -config " + quote(currentConfig)));
    engines.add(
        engine("default", "katago gtp -model default.bin.gz -config " + quote(defaultConfig)));
    engines.get(1).isDefault = true;

    AnalysisEngineCommandHelper.Result result =
        AnalysisEngineCommandHelper.fromCurrentEngine(engines, 0);

    assertTrue(result.isSuccess(), result.getMessage());
    List<String> parts = Utils.splitCommand(result.getCommand());
    assertEquals("current.bin.gz", parts.get(parts.indexOf("-model") + 1));
    assertEquals(
        currentConfig.resolveSibling("analysis.cfg").toString(),
        parts.get(parts.indexOf("-config") + 1));
  }

  @Test
  void fallsBackToDefaultEngineWhenNoCurrentEngineIsLoaded() throws Exception {
    Path defaultConfig = tempDir.resolve("fallback-default").resolve("gtp.cfg");
    Files.createDirectories(defaultConfig.getParent());
    ArrayList<EngineData> engines = new ArrayList<>();
    engines.add(engine("first", "katago gtp -model first.bin.gz -config first.cfg"));
    engines.add(
        engine("default", "katago gtp -model default.bin.gz -config " + quote(defaultConfig)));
    engines.get(1).isDefault = true;

    AnalysisEngineCommandHelper.Result result =
        AnalysisEngineCommandHelper.fromCurrentEngine(engines, -1);

    assertTrue(result.isSuccess(), result.getMessage());
    List<String> parts = Utils.splitCommand(result.getCommand());
    assertEquals("default.bin.gz", parts.get(parts.indexOf("-model") + 1));
    assertEquals(
        defaultConfig.resolveSibling("analysis.cfg").toString(),
        parts.get(parts.indexOf("-config") + 1));
  }

  @Test
  void detectsLegacyCustomizedAnalysisCommandsConservatively() {
    assertFalse(AnalysisEngineCommandHelper.isLegacyAnalysisCommandCustomized(""));
    assertFalse(
        AnalysisEngineCommandHelper.isLegacyAnalysisCommandCustomized(
            "katago analysis -model model.bin.gz -config analysis.cfg -quit-without-waiting"));
    assertTrue(
        AnalysisEngineCommandHelper.isLegacyAnalysisCommandCustomized(
            "katago analysis -model custom.bin.gz -config analysis.cfg"));
    assertFalse(AnalysisEngineCommandHelper.isAnalysisCommandCustomized(true, false, "custom"));
    assertTrue(AnalysisEngineCommandHelper.isAnalysisCommandCustomized(true, true, ""));
  }

  @Test
  void humanSlRebasesAStaleBundledCommandToTheCurrentInstallation() throws Exception {
    Path currentRoot = tempDir.resolve("当前 LizzieYzy Next.app").resolve("Contents").resolve("app");
    Path engine = writeFile(currentRoot.resolve("engines/katago/macos-arm64/katago"));
    Path config = writeFile(currentRoot.resolve("engines/katago/configs/analysis.cfg"));
    Path weight = writeFile(currentRoot.resolve("weights/default.bin.gz"));
    Path staleRoot = tempDir.resolve("old build").resolve("LizzieYzy Next.app/Contents/app");
    String staleCommand =
        quote(staleRoot.resolve("engines/katago/macos-arm64/katago"))
            + " analysis -model "
            + quote(staleRoot.resolve("weights/default.bin.gz"))
            + " -config "
            + quote(staleRoot.resolve("engines/katago/configs/analysis.cfg"))
            + " -analysis-threads 3";

    AnalysisEngineCommandHelper.Result result =
        AnalysisEngineCommandHelper.resolveHumanSlCommand(staleCommand, engine, config, weight);

    assertTrue(result.isSuccess(), result.getMessage());
    List<String> parts = Utils.splitCommand(result.getCommand());
    assertEquals(engine.toString(), parts.get(0));
    assertEquals("analysis", parts.get(1));
    assertEquals(weight.toString(), parts.get(parts.indexOf("-model") + 1));
    assertEquals(config.toString(), parts.get(parts.indexOf("-config") + 1));
    assertTrue(parts.contains("-analysis-threads"));
    assertTrue(parts.contains("-quit-without-waiting"));
  }

  @Test
  void humanSlNeverMixesAStaleEngineAndConfigWithTheCurrentBundledWeight() throws Exception {
    Path currentRoot = tempDir.resolve("installed app");
    Path engine = writeFile(currentRoot.resolve("engines/katago/macos-arm64/katago"));
    Path config = writeFile(currentRoot.resolve("engines/katago/configs/analysis.cfg"));
    Path weight = writeFile(currentRoot.resolve("weights/default.bin.gz"));
    Path staleRoot = tempDir.resolve("deleted developer app image");
    String mixedCommand =
        quote(staleRoot.resolve("engines/katago/macos-arm64/katago"))
            + " analysis -model "
            + quote(weight)
            + " -config "
            + quote(staleRoot.resolve("engines/katago/configs/analysis.cfg"))
            + " -quit-without-waiting";

    AnalysisEngineCommandHelper.Result result =
        AnalysisEngineCommandHelper.resolveHumanSlCommand(mixedCommand, engine, config, weight);

    assertTrue(result.isSuccess(), result.getMessage());
    List<String> parts = Utils.splitCommand(result.getCommand());
    assertEquals(engine.toString(), parts.get(0));
    assertEquals(weight.toString(), parts.get(parts.indexOf("-model") + 1));
    assertEquals(config.toString(), parts.get(parts.indexOf("-config") + 1));
    assertFalse(result.getCommand().contains(staleRoot.toString()));
  }

  @Test
  void humanSlKeepsAWorkingExternalAnalysisCommandUntouched() throws Exception {
    Path engine = writeFile(tempDir.resolve("自定义引擎/katago"));
    Path config = writeFile(tempDir.resolve("自定义引擎/analysis.cfg"));
    Path weight = writeFile(tempDir.resolve("自定义权重/model.bin.gz"));
    String customCommand =
        quote(engine)
            + " analysis -model "
            + quote(weight)
            + " -config "
            + quote(config)
            + " -custom-option enabled";

    AnalysisEngineCommandHelper.Result result =
        AnalysisEngineCommandHelper.resolveHumanSlCommand(
            customCommand,
            tempDir.resolve("unused/engines/katago/katago"),
            tempDir.resolve("unused/analysis.cfg"),
            tempDir.resolve("unused/default.bin.gz"));

    assertTrue(result.isSuccess(), result.getMessage());
    assertEquals(customCommand, result.getCommand());
  }

  @Test
  void humanSlDoesNotReplaceAUserExternalCommandThatCannotBeFound() {
    String customCommand =
        quote(tempDir.resolve("missing custom/katago"))
            + " analysis -model missing.bin.gz -config missing.cfg";

    AnalysisEngineCommandHelper.Result result =
        AnalysisEngineCommandHelper.resolveHumanSlCommand(
            customCommand,
            tempDir.resolve("current/engines/katago/katago"),
            tempDir.resolve("current/analysis.cfg"),
            tempDir.resolve("current/default.bin.gz"));

    assertTrue(result.isSuccess(), result.getMessage());
    assertEquals(customCommand, result.getCommand());
  }

  @Test
  void humanSlReportsNoEngineWhenBundledRecoveryIsIncomplete() {
    String staleCommand =
        quote(tempDir.resolve("old/engines/katago/macos-arm64/katago"))
            + " analysis -model "
            + quote(tempDir.resolve("old/weights/default.bin.gz"))
            + " -config "
            + quote(tempDir.resolve("old/engines/katago/configs/analysis.cfg"));

    AnalysisEngineCommandHelper.Result result =
        AnalysisEngineCommandHelper.resolveHumanSlCommand(
            staleCommand,
            tempDir.resolve("current/engines/katago/katago"),
            tempDir.resolve("current/analysis.cfg"),
            tempDir.resolve("current/default.bin.gz"));

    assertFalse(result.isSuccess());
    assertTrue(result.getCommand().isEmpty());
  }

  @Test
  void humanSlFailedResolveLogsSourceFlavorAndMissingComponentsWithoutFullPaths()
      throws Exception {
    Path root = tempDir.resolve("secret-katago-home-368");
    Path engine = writeIncompleteOpenClBundleMissingAnalysisConfig(root);
    ListAppender<ILoggingEvent> events = attachEngineLog();

    withUserDirAndConfig(
        root,
        () -> {
          AnalysisEngineCommandHelper.Result result =
              AnalysisEngineCommandHelper.resolveHumanSlCommand("");

          assertFalse(result.isSuccess());
          assertTrue(result.getCommand().isEmpty());
          List<String> messages =
              events.list.stream()
                  .map(ILoggingEvent::getFormattedMessage)
                  .filter(
                      message ->
                          message.contains("HumanSL analysis engine resolution failed:"))
                  .toList();
          assertEquals(1, messages.size(), events.list.toString());
          String message = messages.get(0);
          assertTrue(message.contains("HumanSL analysis engine resolution failed:"), message);
          assertTrue(message.contains("configuredCommandPresent=false"), message);
          assertTrue(message.contains("source=BUNDLED_PACKAGE"), message);
          assertTrue(message.contains("packageFlavor=INCOMPLETE_BUNDLE"), message);
          assertTrue(message.contains("engine=true"), message);
          assertTrue(message.contains("analysisConfig=false"), message);
          assertTrue(message.contains("weight=true"), message);
          assertTrue(message.contains("missingComponents=[ANALYSIS_CONFIG]"), message);
          assertTrue(message.contains("diagnostics="), message);
          assertFalse(message.contains(root.toAbsolutePath().normalize().toString()), message);
          assertFalse(message.contains(engine.toString()), message);
        });
  }

  @Test
  void humanSlFailedResolveDiagnosticAppearsInDiagnosticPackage() throws Exception {
    Path logHome = tempDir.resolve("logging-home");
    Path root = tempDir.resolve("secret-katago-home-368-package");
    Path engine = writeIncompleteOpenClBundleMissingAnalysisConfig(root);
    Files.createDirectories(logHome);
    ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
    LoggingRuntime runtime = null;
    try {
      LoggingRuntime.resetForTests();
      runtime =
          LoggingRuntime.initialize(
              new WorkDirectoryResolution(logHome, List.of()),
              new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));

      LoggingRuntime loggingRuntime = runtime;
      withUserDirAndConfig(
          root,
          () -> {
            AnalysisEngineCommandHelper.Result result =
                AnalysisEngineCommandHelper.resolveHumanSlCommand("");
            assertFalse(result.isSuccess());
            loggingRuntime.shutdown();

            Path zip =
                new DiagnosticBundleExporter(
                        DiagnosticBundleExporter.defaultOutputDirectory(logHome))
                    .export(
                        new DiagnosticBundleRequest(
                            loggingRuntime,
                            EnumSet.noneOf(TraceScope.class),
                            new JSONObject(),
                            emptySnapshot(),
                            "next-dev"));
            Map<String, String> entries = unzipTextEntries(zip);
            String appLog = entries.getOrDefault("logs/lizzie/app.log", "");
            String diagnosticLine = humanSlResolutionFailureLine(appLog);
            assertFalse(diagnosticLine.isEmpty(), appLog);
            assertTrue(diagnosticLine.contains("source=BUNDLED_PACKAGE"), diagnosticLine);
            assertTrue(
                diagnosticLine.contains("packageFlavor=INCOMPLETE_BUNDLE"), diagnosticLine);
            assertTrue(
                diagnosticLine.contains("missingComponents=[ANALYSIS_CONFIG]"), diagnosticLine);
            assertTrue(diagnosticLine.contains("engine=true"), diagnosticLine);
            assertTrue(diagnosticLine.contains("analysisConfig=false"), diagnosticLine);
            assertTrue(diagnosticLine.contains("weight=true"), diagnosticLine);
            assertFalse(
                diagnosticLine.contains(root.toAbsolutePath().normalize().toString()),
                diagnosticLine);
            assertFalse(diagnosticLine.contains(engine.toString()), diagnosticLine);
          });
    } finally {
      if (runtime != null) {
        runtime.shutdown();
      }
      LoggingRuntime.resetForTests();
      Thread.currentThread().setContextClassLoader(previousLoader);
    }
  }

  @Test
  void humanSlSuccessfulResolveDoesNotLogResolutionFailure() throws Exception {
    Path engine = writeFile(tempDir.resolve("自定义引擎/katago"));
    Path config = writeFile(tempDir.resolve("自定义引擎/analysis.cfg"));
    Path weight = writeFile(tempDir.resolve("自定义权重/model.bin.gz"));
    String customCommand =
        quote(engine)
            + " analysis -model "
            + quote(weight)
            + " -config "
            + quote(config);
    ListAppender<ILoggingEvent> events = attachEngineLog();

    withUserDirAndConfig(
        tempDir.resolve("unused-bundle"),
        () -> {
          AnalysisEngineCommandHelper.Result result =
              AnalysisEngineCommandHelper.resolveHumanSlCommand(customCommand);

          assertTrue(result.isSuccess(), result.getMessage());
          assertEquals(customCommand, result.getCommand());
          assertTrue(
              events.list.stream()
                  .noneMatch(
                      event ->
                          event.getFormattedMessage()
                              .contains("HumanSL analysis engine resolution failed:")),
              events.list.toString());
        });
  }

  @Test
  void bundledAnalysisConfigTemplateIsAvailable() throws Exception {
    assertNotNull(
        AnalysisEngineCommandHelperTest.class
            .getClassLoader()
            .getResource("katago/analysis_example.cfg"));
  }

  private static EngineData engine(String name, String command) {
    EngineData engine = new EngineData();
    engine.name = name;
    engine.commands = command;
    return engine;
  }

  private static String quote(Path path) {
    return "\"" + path.toString() + "\"";
  }

  private static Path writeFile(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    return Files.write(path, new byte[] {1});
  }

  private static Path writeIncompleteOpenClBundleMissingAnalysisConfig(Path root) throws Exception {
    Files.createDirectories(root);
    Files.writeString(
        root.resolve("lizzieyzy-next-installed-manifest.json"),
        "{\"platform\":\"linux\",\"flavor\":\"opencl\"}");
    Path engine =
        writeFile(
            root.resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Files.createDirectories(root.resolve("engines").resolve("katago").resolve("configs"));
    writeFile(root.resolve("engines").resolve("katago").resolve("configs").resolve("gtp.cfg"));
    writeFile(root.resolve("weights").resolve("default.bin.gz"));
    return engine.toAbsolutePath().normalize();
  }

  private static ListAppender<ILoggingEvent> attachEngineLog() {
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    engine.setLevel(Level.INFO);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    engine.addAppender(appender);
    return appender;
  }

  private static void withUserDirAndConfig(Path userDir, ThrowingRunnable action) throws Exception {
    Files.createDirectories(userDir);
    String previousUserDir = System.getProperty("user.dir");
    Config previousConfig = Lizzie.config;
    try {
      System.setProperty("user.dir", userDir.toString());
      Lizzie.config = ConfigTestHelper.createForTests(userDir);
      Lizzie.config.config = new JSONObject();
      Lizzie.config.leelazConfig = new JSONObject();
      Lizzie.config.uiConfig = new JSONObject();
      action.run();
    } finally {
      if (previousUserDir == null) {
        System.clearProperty("user.dir");
      } else {
        System.setProperty("user.dir", previousUserDir);
      }
      Lizzie.config = previousConfig;
    }
  }

  private static String detectTestPlatformDir() {
    String osName = System.getProperty("os.name", "").toLowerCase();
    String arch = System.getProperty("os.arch", "").toLowerCase();
    boolean isArm = arch.contains("aarch64") || arch.contains("arm64");
    boolean is64 = arch.contains("64");
    if (osName.contains("win")) {
      return is64 ? "windows-x64" : "windows-x86";
    }
    if (osName.contains("mac") || osName.contains("darwin")) {
      return isArm ? "macos-arm64" : "macos-amd64";
    }
    return is64 ? "linux-x64" : "linux-x86";
  }

  private static String testKataGoBinaryName() {
    return System.getProperty("os.name", "").toLowerCase().contains("win")
        ? "katago.exe"
        : "katago";
  }

  private static SyncDiagnosticsExportSnapshot emptySnapshot() {
    return new SyncDiagnosticsExportSnapshot(
        1L, null, List.of(), List.of(), List.of(), null);
  }

  private static String humanSlResolutionFailureLine(String appLog) {
    if (appLog == null || appLog.isEmpty()) {
      return "";
    }
    for (String line : appLog.split("\\R")) {
      if (line.contains("HumanSL analysis engine resolution failed:")) {
        return line;
      }
    }
    return "";
  }

  private static Map<String, String> unzipTextEntries(Path zip) throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        entries.put(entry.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
