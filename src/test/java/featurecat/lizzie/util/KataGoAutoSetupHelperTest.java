package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

public class KataGoAutoSetupHelperTest {
  @Test
  void weightDisplayNameKeepsUserModelNameAndHidesTrainingHashes() {
    assertEquals(
        "zhizi 28B muonfd2",
        KataGoAutoSetupHelper.resolveWeightDisplayName(
            "kata1-zhizi-b28c512nbt-s12763923712-d5805955894-muonfd2.bin.gz"));
    assertEquals(
        "zhizi 40B",
        KataGoAutoSetupHelper.resolveWeightDisplayName("kata1-zhizi-b40c768nbt-fdx6d.bin.gz"));
    assertEquals(
        "28B",
        KataGoAutoSetupHelper.resolveWeightDisplayName(
            "kata1-b28c512nbt-s12763923712-d5805955894.bin.gz"));
  }

  @Test
  void importWeightCopiesToLocalWeightsWithoutChangingPreferredWeight() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-import-weight");
    Path source = Files.write(tempRoot.resolve("custom.bin.gz"), new byte[] {1, 2, 3, 4});

    withUserDirAndConfig(
        tempRoot,
        () -> {
          Lizzie.config.uiConfig.put("katago-preferred-weight-path", "old.bin.gz");

          Path imported = KataGoAutoSetupHelper.importWeight(source);

          assertTrue(imported.startsWith(tempRoot.resolve("weights")));
          assertTrue(Files.isRegularFile(imported));
          assertEquals(
              "old.bin.gz", Lizzie.config.uiConfig.optString("katago-preferred-weight-path"));
          assertFalse(imported.equals(source.toAbsolutePath().normalize()));
        });
  }

  @Test
  void importHumanSlModelCopiesToSeparateDirectoryAndDoesNotChangeActiveWeight() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-import-humansl");
    Path source =
        Files.write(tempRoot.resolve("custom-human.bin.gz"), new byte[2 * 1024 * 1024]);
    Path weight = touch(tempRoot.resolve("weights").resolve("default.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          Lizzie.config.uiConfig.put("katago-preferred-weight-path", weight.toString());

          Path imported = KataGoAutoSetupHelper.importHumanSlModel(source);
          KataGoAutoSetupHelper.SetupSnapshot snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
          KataGoAutoSetupHelper.HumanSlModelStatus status =
              KataGoAutoSetupHelper.inspectHumanSlModel();

          assertTrue(imported.startsWith(tempRoot.resolve("human-sl-models")));
          assertTrue(Files.isRegularFile(imported));
          assertEquals(weight, snapshot.activeWeightPath);
          assertEquals(
              weight.toString(), Lizzie.config.uiConfig.optString("katago-preferred-weight-path"));
          assertEquals(
              imported.toString(), Lizzie.config.uiConfig.optString("katago-human-sl-model-path"));
          assertTrue(status.isInstalled());
          assertEquals(imported, status.modelPath);
        });
  }

  @Test
  void inspectHumanSlModelUsesRememberedPathBeforeDirectoryScan() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-status");
    Path first = touchModel(tempRoot.resolve("human-sl-models").resolve("first-human.bin.gz"));
    Path remembered = touchModel(tempRoot.resolve("external").resolve("remembered-human.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          Lizzie.config.uiConfig.put("katago-human-sl-model-path", remembered.toString());

          KataGoAutoSetupHelper.HumanSlModelStatus status =
              KataGoAutoSetupHelper.inspectHumanSlModel();

          assertTrue(status.isInstalled());
          assertEquals(remembered, status.modelPath);
          assertEquals(remembered, status.candidates.get(0));
          assertTrue(status.candidates.contains(first));
        });
  }

  @Test
  void inspectHumanSlModelPrefersBundledDefaultFile() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-bundled");
    Path olderCustom =
        touchModel(tempRoot.resolve("human-sl-models").resolve("custom-human.bin.gz"));
    byte[] officialBytes = repeatedBytes(4096, (byte) 3);
    Path bundled =
        writeModel(
            tempRoot
                .resolve("human-sl-models")
                .resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME),
            officialBytes);

    withUserDirAndConfig(
        tempRoot,
        () ->
            withHumanSlDownloadProperties(
                "http://127.0.0.1/model.bin.gz",
                sha256(officialBytes),
                officialBytes.length,
                () -> {
                  KataGoAutoSetupHelper.HumanSlModelStatus status =
                      KataGoAutoSetupHelper.inspectHumanSlModel();

                  assertTrue(status.isInstalled());
                  assertEquals(bundled, status.modelPath);
                  assertEquals(bundled, status.candidates.get(0));
                  assertTrue(status.candidates.contains(olderCustom));
                }));
  }

  @Test
  void inspectHumanSlModelFindsBundledFileFromAppRootWithoutEngine() throws Exception {
    Path appRoot = Files.createTempDirectory("katago-humansl-app-root");
    Path workDir = Files.createDirectories(appRoot.resolve("user-data"));
    Path processDir = Files.createDirectories(workDir.resolve("cwd"));
    byte[] officialBytes = repeatedBytes(4096, (byte) 5);
    Path bundled =
        writeModel(
            appRoot
                .resolve("human-sl-models")
                .resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME),
            officialBytes);

    withProcessDirAndConfig(
        processDir,
        workDir,
        () ->
            withHumanSlDownloadProperties(
                "http://127.0.0.1/model.bin.gz",
                sha256(officialBytes),
                officialBytes.length,
                () -> {
                  KataGoAutoSetupHelper.HumanSlModelStatus status =
                      KataGoAutoSetupHelper.inspectHumanSlModel();

                  assertTrue(status.isInstalled());
                  assertEquals(bundled, status.modelPath);
                  assertEquals(bundled, status.candidates.get(0));
                }));
  }

  @Test
  void downloadHumanSlModelVerifiesChecksumAndRemembersPath() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-download");
    byte[] modelBytes = repeatedBytes(4096, (byte) 7);
    try (FixtureServer server = FixtureServer.start(modelBytes)) {
      withUserDirAndConfig(
          tempRoot,
          () ->
              withHumanSlDownloadProperties(
                  server.url(),
                  sha256(modelBytes),
                  modelBytes.length,
                  () -> {
                    Path downloaded = KataGoAutoSetupHelper.downloadHumanSlModel(null);
                    KataGoAutoSetupHelper.HumanSlModelStatus status =
                        KataGoAutoSetupHelper.inspectHumanSlModel();

                    assertEquals(modelBytes.length, Files.size(downloaded));
                    assertEquals(
                        downloaded.toString(),
                        Lizzie.config.uiConfig.optString("katago-human-sl-model-path"));
                    assertTrue(status.isInstalled());
                    assertEquals(downloaded, status.modelPath);
                  }));
    }
  }

  @Test
  void downloadHumanSlModelRejectsChecksumMismatchAndDeletesPartialFile() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-download-bad");
    byte[] modelBytes = repeatedBytes(4096, (byte) 9);
    try (FixtureServer server = FixtureServer.start(modelBytes)) {
      withUserDirAndConfig(
          tempRoot,
          () ->
              withHumanSlDownloadProperties(
                  server.url(),
                  "0000000000000000000000000000000000000000000000000000000000000000",
                  modelBytes.length,
                  () -> {
                    assertThrows(IOException.class, () -> KataGoAutoSetupHelper.downloadHumanSlModel(null));

                    Path modelsDir = tempRoot.resolve("human-sl-models");
                    assertFalse(
                        Files.exists(
                            modelsDir.resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME)));
                    assertFalse(
                        Files.exists(
                            modelsDir.resolve(
                                KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME + ".part")));
                    assertFalse(KataGoAutoSetupHelper.inspectHumanSlModel().isInstalled());
                  }));
    }
  }

  @Test
  void inspectHumanSlModelRejectsTruncatedOfficialModel() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-humansl-truncated");
    Files.createDirectories(tempRoot.resolve("human-sl-models"));
    Files.write(
        tempRoot.resolve("human-sl-models").resolve(KataGoAutoSetupHelper.HUMAN_SL_MODEL_FILE_NAME),
        new byte[] {1, 2, 3, 4});

    withUserDirAndConfig(
        tempRoot, () -> assertFalse(KataGoAutoSetupHelper.inspectHumanSlModel().isInstalled()));
  }

  @Test
  void inspectLocalSetupUsesConfiguredWorkDirectoryInsteadOfProcessDirectory() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-configured-workdir");
    Path workDir = Files.createDirectories(tempRoot.resolve("portable").resolve("user-data"));
    Path processDir = Files.createDirectories(tempRoot.resolve("outside-process-dir"));
    Path engine =
        touch(
            workDir
                .resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configs =
        Files.createDirectories(workDir.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfig = touch(configs.resolve("gtp.cfg"));
    Path weight = touch(workDir.resolve("weights").resolve("default.bin.gz"));

    withProcessDirAndConfig(
        processDir,
        workDir,
        () -> {
          KataGoAutoSetupHelper.SetupSnapshot snapshot = KataGoAutoSetupHelper.inspectLocalSetup();

          assertEquals(workDir.toAbsolutePath().normalize(), snapshot.workingDir);
          assertEquals(engine, snapshot.enginePath);
          assertEquals(gtpConfig, snapshot.gtpConfigPath);
          assertEquals(weight, snapshot.activeWeightPath);
        });
  }

  @Test
  void startupRepairDoesNotRewriteTensorRtProfileToCuda() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-tensorrt-repair");
    Path cudaEngine =
        touch(
            tempRoot
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64")
                .resolve("katago.exe"));
    Path tensorRtEngine =
        touch(
            tempRoot
                .resolve("runtime")
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64-nvidia-tensorrt")
                .resolve("katago.exe"));
    Path configDir =
        Files.createDirectories(tempRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfig = touch(configDir.resolve("gtp.cfg"));
    Path analysisConfig = touch(configDir.resolve("analysis.cfg"));
    Path estimateConfig = touch(configDir.resolve("estimate.cfg"));
    Path weight = touch(tempRoot.resolve("weights").resolve("default.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          ArrayList<EngineData> engines = new ArrayList<>();
          engines.add(engineData("KataGo Bundled", cudaEngine, gtpConfig, weight, false));
          engines.add(engineData("KataGo TensorRT", tensorRtEngine, gtpConfig, weight, true));
          Utils.saveEngineSettings(engines);
          Lizzie.config.uiConfig.put("default-engine", 1);
          Lizzie.config.uiConfig.put(
              "analysis-engine-command",
              quote(tensorRtEngine)
                  + " analysis -model "
                  + quote(weight)
                  + " -config "
                  + quote(analysisConfig)
                  + " -quit-without-waiting");
          Lizzie.config.uiConfig.put(
              "estimate-command",
              quote(tensorRtEngine)
                  + " gtp -model "
                  + quote(weight)
                  + " -config "
                  + quote(estimateConfig));

          assertFalse(KataGoAutoSetupHelper.repairBrokenBundledCommandsIfNeeded());
          assertFalse(KataGoAutoSetupHelper.repairBrokenStartupEngineIfNeeded());

          ArrayList<EngineData> repairedEngines = Utils.getEngineData();
          assertEquals("KataGo TensorRT", repairedEngines.get(1).name);
          assertTrue(repairedEngines.get(1).isDefault);
          assertTrue(repairedEngines.get(1).commands.contains("windows-x64-nvidia-tensorrt"));
          assertEquals(1, Lizzie.config.uiConfig.optInt("default-engine"));
          assertTrue(
              Lizzie.config
                  .uiConfig
                  .optString("analysis-engine-command")
                  .contains("windows-x64-nvidia-tensorrt"));
        });
  }

  @Test
  void autoSetupRefreshPreservesUserEngineKomiAndSettings() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-preserve-engine-settings");
    Path engine =
        touch(
            tempRoot
                .resolve("engines")
                .resolve("katago")
                .resolve(detectTestPlatformDir())
                .resolve(testKataGoBinaryName()));
    Path configDir =
        Files.createDirectories(tempRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfig = touch(configDir.resolve("gtp.cfg"));
    touch(configDir.resolve("analysis.cfg"));
    touch(configDir.resolve("estimate.cfg"));
    Path weight = touch(tempRoot.resolve("weights").resolve("default.bin.gz"));

    withUserDirAndConfig(
        tempRoot,
        () -> {
          ArrayList<EngineData> engines = new ArrayList<>();
          EngineData autoSetupEngine =
              engineData(
                  KataGoAutoSetupHelper.getAutoSetupEngineName(), engine, gtpConfig, weight, false);
          autoSetupEngine.komi = 6.5F;
          autoSetupEngine.preload = true;
          autoSetupEngine.width = 13;
          autoSetupEngine.height = 13;
          autoSetupEngine.initialCommand = "kata-set-rules chinese";
          engines.add(autoSetupEngine);
          Utils.saveEngineSettings(engines);

          KataGoAutoSetupHelper.applyAutoSetup(KataGoAutoSetupHelper.inspectLocalSetup(), false);

          ArrayList<EngineData> refreshedEngines = Utils.getEngineData();
          EngineData refreshed = refreshedEngines.get(0);
          assertEquals(6.5F, refreshed.komi);
          assertTrue(refreshed.preload);
          assertEquals(13, refreshed.width);
          assertEquals(13, refreshed.height);
          assertEquals("kata-set-rules chinese", refreshed.initialCommand);
          assertFalse(refreshed.isDefault);
        });
  }

  private static EngineData engineData(
      String name, Path enginePath, Path configPath, Path weightPath, boolean isDefault) {
    EngineData data = new EngineData();
    data.name = name;
    data.commands =
        quote(enginePath) + " gtp -model " + quote(weightPath) + " -config " + quote(configPath);
    data.preload = false;
    data.komi = 7.5F;
    data.width = 19;
    data.height = 19;
    data.isDefault = isDefault;
    data.useJavaSSH = false;
    data.ip = "";
    data.port = "";
    data.userName = "";
    data.password = "";
    data.useKeyGen = false;
    data.keyGenPath = "";
    data.initialCommand = "";
    return data;
  }

  private static Path touch(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    return Files.write(path, new byte[0]).toAbsolutePath().normalize();
  }

  private static Path touchModel(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    return Files.write(path, new byte[2 * 1024 * 1024]).toAbsolutePath().normalize();
  }

  private static Path writeModel(Path path, byte[] bytes) throws Exception {
    Files.createDirectories(path.getParent());
    return Files.write(path, bytes).toAbsolutePath().normalize();
  }

  private static byte[] repeatedBytes(int size, byte value) {
    byte[] bytes = new byte[size];
    java.util.Arrays.fill(bytes, value);
    return bytes;
  }

  private static String quote(Path path) {
    return "\"" + path.toAbsolutePath().normalize().toString() + "\"";
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

  private static void withUserDirAndConfig(Path userDir, ThrowingRunnable action) throws Exception {
    withProcessDirAndConfig(userDir, userDir, action);
  }

  private static void withProcessDirAndConfig(
      Path processDir, Path configDir, ThrowingRunnable action) throws Exception {
    String previousUserDir = System.getProperty("user.dir");
    Config previousConfig = Lizzie.config;
    try {
      System.setProperty("user.dir", processDir.toString());
      Lizzie.config = ConfigTestHelper.createForTests(configDir);
      Lizzie.config.config = new org.json.JSONObject();
      Lizzie.config.leelazConfig = new org.json.JSONObject();
      Lizzie.config.uiConfig = new org.json.JSONObject();
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

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static void withHumanSlDownloadProperties(
      String url, String sha256, long size, ThrowingRunnable action) throws Exception {
    String previousUrl = System.getProperty("lizzie.humansl.model.url");
    String previousSha = System.getProperty("lizzie.humansl.model.sha256");
    String previousSize = System.getProperty("lizzie.humansl.model.size");
    try {
      System.setProperty("lizzie.humansl.model.url", url);
      System.setProperty("lizzie.humansl.model.sha256", sha256);
      System.setProperty("lizzie.humansl.model.size", Long.toString(size));
      action.run();
    } finally {
      restoreProperty("lizzie.humansl.model.url", previousUrl);
      restoreProperty("lizzie.humansl.model.sha256", previousSha);
      restoreProperty("lizzie.humansl.model.size", previousSize);
    }
  }

  private static void restoreProperty(String key, String previousValue) {
    if (previousValue == null) {
      System.clearProperty(key);
      return;
    }
    System.setProperty(key, previousValue);
  }

  private static String sha256(byte[] bytes) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(bytes);
    StringBuilder builder = new StringBuilder();
    for (byte value : hash) {
      builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
    }
    return builder.toString();
  }

  private static final class FixtureServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;

    private FixtureServer(HttpServer server, ExecutorService executor) {
      this.server = server;
      this.executor = executor;
    }

    private static FixtureServer start(byte[] bytes) throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      server.createContext(
          "/model.bin.gz",
          exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
              body.write(bytes);
            }
          });
      server.setExecutor(executor);
      server.start();
      return new FixtureServer(server, executor);
    }

    private String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/model.bin.gz";
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
