package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.katago.tuning.AppleSiliconHardwareProbe;
import featurecat.lizzie.util.katago.tuning.KataGoCommandSpec;
import featurecat.lizzie.util.katago.tuning.KataGoTuningCandidate;
import featurecat.lizzie.util.katago.tuning.KataGoTuningFingerprint;
import featurecat.lizzie.util.katago.tuning.KataGoTuningProfile;
import featurecat.lizzie.util.katago.tuning.KataGoTuningStore;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KataGoRuntimeLayeredTuningTest {
  @TempDir Path temporaryDirectory;

  @Test
  void layeredBenchmarkFixesTopologyAndBatchWhileLeavingThreadsToKataGo() throws IOException {
    SetupSnapshot snapshot = createSnapshot();
    KataGoTuningCandidate candidate = new KataGoTuningCandidate("GGA", List.of(0, 0, 100), 3);

    List<String> command =
        KataGoRuntimeHelper.buildLayeredBenchmarkCommand(snapshot, candidate, 0, 3, 600);
    KataGoCommandSpec spec = KataGoCommandSpec.parse(command);

    assertTrue(command.contains("-s"));
    assertFalse(command.contains("-t"));
    assertEquals("3", optionValue(command, "-fixed-batch-size"));
    assertEquals("19", optionValue(command, "-boardsize"));
    assertEquals("3", spec.overrideValue("numNNServerThreadsPerModel").orElseThrow());
    assertEquals("0", spec.overrideValue("metalDeviceToUseModel0Thread0").orElseThrow());
    assertEquals("0", spec.overrideValue("metalDeviceToUseModel0Thread1").orElseThrow());
    assertEquals("100", spec.overrideValue("metalDeviceToUseModel0Thread2").orElseThrow());
    assertEquals("true", spec.overrideValue("metalUseFP16-0").orElseThrow());
    assertTrue(spec.overrideValue("nnMaxBatchSize").isEmpty());
    assertTrue(spec.overrideValue("numSearchThreads").isEmpty());
  }

  @Test
  void smokeBenchmarkUsesAnExplicitCommonThreadCount() throws IOException {
    SetupSnapshot snapshot = createSnapshot();
    KataGoTuningCandidate candidate = new KataGoTuningCandidate("GA", List.of(0, 100), 2);

    List<String> command =
        KataGoRuntimeHelper.buildLayeredBenchmarkCommand(snapshot, candidate, 6, 1, 200);

    assertEquals("6", optionValue(command, "-t"));
    assertFalse(command.contains("-s"));
    assertEquals("2", optionValue(command, "-fixed-batch-size"));
  }

  @Test
  void explicitThreadsBlockOnlyTheStoredProfileThreadGroup() {
    KataGoTuningCandidate candidate = new KataGoTuningCandidate("GA", List.of(0, 100), 2);

    List<String> merged =
        KataGoRuntimeHelper.mergeStoredAppleTuningProfile(
            List.of("katago", "gtp", "--override-config", "userSetting=keep,numSearchThreads=11"),
            candidate,
            7);
    KataGoCommandSpec spec = KataGoCommandSpec.parse(merged);

    assertEquals("11", spec.overrideValue("numSearchThreads").orElseThrow());
    assertEquals("2", spec.overrideValue("numNNServerThreadsPerModel").orElseThrow());
    assertEquals("0", spec.overrideValue("metalDeviceToUseModel0Thread0").orElseThrow());
    assertEquals("100", spec.overrideValue("metalDeviceToUseModel0Thread1").orElseThrow());
    assertEquals("2", spec.overrideValue("nnMaxBatchSize").orElseThrow());
    assertEquals("keep", spec.overrideValue("userSetting").orElseThrow());
  }

  @Test
  void everyKataGoMetalAliasMakesTheStoredTopologyAtomic() {
    List<String> aliases =
        List.of(
            "numNNServerThreadsPerModel",
            "metalDeviceToUseThread0",
            "metalGpuToUseModel0Thread0",
            "deviceToUseThread0",
            "gpuToUse",
            "metalUseFP16",
            "useFP16Model0");
    KataGoTuningCandidate candidate = new KataGoTuningCandidate("GGA", List.of(0, 0, 100), 3);

    for (String alias : aliases) {
      KataGoCommandSpec spec =
          KataGoCommandSpec.parse(
              KataGoRuntimeHelper.mergeStoredAppleTuningProfile(
                  List.of("katago", "gtp", "-override-config", alias + "=explicit"), candidate, 7));
      Map<String, String> overrides = spec.effectiveOverrides();

      assertEquals("explicit", overrides.get(alias), alias);
      if (!"numNNServerThreadsPerModel".equals(alias)) {
        assertFalse(overrides.containsKey("numNNServerThreadsPerModel"), alias);
      }
      assertFalse(overrides.containsKey("metalDeviceToUseModel0Thread0"), alias);
      assertFalse(overrides.containsKey("metalDeviceToUseModel0Thread1"), alias);
      assertFalse(overrides.containsKey("metalDeviceToUseModel0Thread2"), alias);
      assertFalse(overrides.containsKey("metalUseFP16-0"), alias);
      assertEquals("3", overrides.get("nnMaxBatchSize"), alias);
      assertEquals("7", overrides.get("numSearchThreads"), alias);
    }
  }

  @Test
  void effectiveLaunchThreadDetectionHandlesLongOptionAndCase() {
    assertTrue(
        KataGoRuntimeHelper.hasEffectiveNumSearchThreadsOverride(
            List.of("katago", "gtp", "--override-config", "other=keep,NumSearchThreads=9")));
    assertFalse(
        KataGoRuntimeHelper.hasEffectiveNumSearchThreadsOverride(
            List.of("katago", "gtp", "-override-config", "nnMaxBatchSize=3")));
  }

  @Test
  void officialThreadProfileAddsOnlyThreadsAndKeepsCurrentHardwareSettings() {
    List<String> command =
        List.of(
            "katago",
            "gtp",
            "-override-config",
            "numNNServerThreadsPerModel=2,metalDeviceToUseModel0Thread0=0,"
                + "metalDeviceToUseModel0Thread1=100,nnMaxBatchSize=4,userSetting=keep");

    KataGoCommandSpec merged =
        KataGoCommandSpec.parse(KataGoRuntimeHelper.mergeStoredAppleThreadProfile(command, 7));

    assertEquals("7", merged.overrideValue("numSearchThreads").orElseThrow());
    assertEquals("2", merged.overrideValue("numNNServerThreadsPerModel").orElseThrow());
    assertEquals("0", merged.overrideValue("metalDeviceToUseModel0Thread0").orElseThrow());
    assertEquals("100", merged.overrideValue("metalDeviceToUseModel0Thread1").orElseThrow());
    assertEquals("4", merged.overrideValue("nnMaxBatchSize").orElseThrow());
    assertEquals("keep", merged.overrideValue("userSetting").orElseThrow());
  }

  @Test
  void explicitThreadOverrideWinsOverOfficialStoredRecommendation() {
    List<String> command =
        List.of(
            "katago",
            "gtp",
            "--override-config",
            "numSearchThreads=11,nnMaxBatchSize=4,userSetting=keep");

    List<String> merged = KataGoRuntimeHelper.mergeStoredAppleThreadProfile(command, 7);

    assertEquals(command, merged);
  }

  @Test
  void officialBenchmarkInheritsTopologyButRemovesThreadAndProcessOverrides() {
    Map<String, String> overrides =
        KataGoRuntimeHelper.officialBenchmarkOverrides(
            List.of(
                "katago",
                "gtp",
                "-override-config",
                "numSearchThreads=9,numAnalysisThreads=3,"
                    + "numSearchThreadsPerAnalysisThread=2,homeDataDir=/tmp/katago,"
                    + "numNNServerThreadsPerModel=2,metalDeviceToUseModel0Thread0=0,"
                    + "metalDeviceToUseModel0Thread1=100,nnMaxBatchSize=4,userSetting=keep"));

    assertFalse(overrides.containsKey("numSearchThreads"));
    assertFalse(overrides.containsKey("numAnalysisThreads"));
    assertFalse(overrides.containsKey("numSearchThreadsPerAnalysisThread"));
    assertFalse(overrides.containsKey("homeDataDir"));
    assertEquals("2", overrides.get("numNNServerThreadsPerModel"));
    assertEquals("0", overrides.get("metalDeviceToUseModel0Thread0"));
    assertEquals("100", overrides.get("metalDeviceToUseModel0Thread1"));
    assertEquals("4", overrides.get("nnMaxBatchSize"));
    assertEquals("keep", overrides.get("userSetting"));
  }

  @Test
  void officialFingerprintTracksHardwareButIgnoresManagedThreadAndProcessNoise() {
    List<String> first =
        List.of(
            "katago",
            "gtp",
            "-override-config",
            "numSearchThreads=3,homeDataDir=/one,analysisPVLen=15,logToStderr=true,"
                + "numNNServerThreadsPerModel=2,metalDeviceToUseModel0Thread0=0,"
                + "metalDeviceToUseModel0Thread1=100,nnMaxBatchSize=4,userSetting=keep");
    List<String> processOnlyChanges =
        List.of(
            "katago",
            "gtp",
            "--override-config",
            "numSearchThreads=19,homeDataDir=/two,analysisPVLen=99,logToStderr=false,"
                + "numNNServerThreadsPerModel=2,metalDeviceToUseModel0Thread0=0,"
                + "metalDeviceToUseModel0Thread1=100,nnMaxBatchSize=4,userSetting=keep");
    List<String> hardwareChange =
        List.of(
            "katago",
            "gtp",
            "-override-config",
            "numNNServerThreadsPerModel=1,metalDeviceToUseModel0Thread0=0,"
                + "nnMaxBatchSize=1,userSetting=keep");

    String official = KataGoRuntimeHelper.officialTuningCommandSemantics(first);

    assertEquals(official, KataGoRuntimeHelper.officialTuningCommandSemantics(processOnlyChanges));
    assertNotEquals(official, KataGoRuntimeHelper.officialTuningCommandSemantics(hardwareChange));
    assertNotEquals(official, KataGoRuntimeHelper.tuningCommandSemantics(first));
    assertTrue(official.contains("benchmarkMode=officialThreads"));
  }

  @Test
  void officialProfileFingerprintMatchesLaunchAndInjectsOnlyThreads() throws Exception {
    Config previousConfig = Lizzie.config;
    String previousOsName = System.getProperty("os.name");
    String previousOsArch = System.getProperty("os.arch");
    try {
      System.setProperty("os.name", "Mac OS X");
      System.setProperty("os.arch", "aarch64");
      Config config =
          ConfigTestHelper.createForTests(
              Files.createDirectories(temporaryDirectory.resolve("official-launch-config")));
      Lizzie.config = config;
      initializeConfigJson(config);
      Path engine = Files.writeString(temporaryDirectory.resolve("official-katago"), "engine");
      Path model = Files.writeString(temporaryDirectory.resolve("official-model.bin.gz"), "model");
      Path gtp = Files.writeString(temporaryDirectory.resolve("official-gtp.cfg"), "config");
      List<String> sourceCommand =
          List.of(
              engine.toString(),
              "gtp",
              "-model",
              model.toString(),
              "-config",
              gtp.toString(),
              "-override-config",
              "numNNServerThreadsPerModel=2,metalDeviceToUseModel0Thread0=0,"
                  + "metalDeviceToUseModel0Thread1=100,nnMaxBatchSize=4,userSetting=keep");
      KataGoTuningFingerprint fingerprint =
          KataGoTuningFingerprint.create(
              engine,
              model,
              gtp,
              new AppleSiliconHardwareProbe().probe(),
              KataGoRuntimeHelper.officialTuningCommandSemantics(sourceCommand));
      new KataGoTuningStore(config.uiConfig)
          .save(
              KataGoTuningProfile.officialThreads(
                  fingerprint,
                  7,
                  new KataGoTuningProfile.Metrics(3, 3, 120.0, 100.0, 25.0, 4.0),
                  "Metal",
                  123L));

      KataGoCommandSpec applied =
          KataGoCommandSpec.parse(
              KataGoRuntimeHelper.applyStoredAppleTuningProfile(sourceCommand, engine));

      assertEquals("7", applied.overrideValue("numSearchThreads").orElseThrow());
      assertEquals("2", applied.overrideValue("numNNServerThreadsPerModel").orElseThrow());
      assertEquals("0", applied.overrideValue("metalDeviceToUseModel0Thread0").orElseThrow());
      assertEquals("100", applied.overrideValue("metalDeviceToUseModel0Thread1").orElseThrow());
      assertEquals("4", applied.overrideValue("nnMaxBatchSize").orElseThrow());
      assertEquals("keep", applied.overrideValue("userSetting").orElseThrow());
    } finally {
      restoreProperty("os.name", previousOsName);
      restoreProperty("os.arch", previousOsArch);
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void layeredResultDoesNotEnableGlobalThreadControl() throws Exception {
    Config previousConfig = Lizzie.config;
    try {
      Config config =
          ConfigTestHelper.createForTests(
              Files.createDirectories(temporaryDirectory.resolve("layered-config")));
      Lizzie.config = config;
      initializeConfigJson(config);
      config.chkKataEngineThreads = false;
      config.autoLoadKataEngineThreads = false;
      config.txtKataEngineThreads = "13";
      config.uiConfig.put("chk-kata-engine-threads", false);
      config.uiConfig.put("autoload-kata-engine-threads", false);
      config.uiConfig.put("txt-kata-engine-threads", "13");

      KataGoTuningProfile profile =
          new KataGoTuningProfile(
              "test-fingerprint",
              List.of(0, 100),
              2,
              7,
              new KataGoTuningProfile.Metrics(3, 3, 120.0, 100.0, 25.0, 4.0),
              "Metal",
              123L);
      KataGoRuntimeHelper.applyBenchmarkResult(layeredResult(profile));

      assertFalse(config.chkKataEngineThreads);
      assertFalse(config.autoLoadKataEngineThreads);
      assertEquals("13", config.txtKataEngineThreads);
      assertFalse(config.uiConfig.getBoolean("chk-kata-engine-threads"));
      assertFalse(config.uiConfig.getBoolean("autoload-kata-engine-threads"));
      assertEquals("13", config.uiConfig.getString("txt-kata-engine-threads"));
      assertEquals(7, config.uiConfig.getInt("katago-benchmark-threads"));
      assertTrue(new KataGoTuningStore(config.uiConfig).hasStoredProfile());
    } finally {
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void officialAppleResultDoesNotEnableGlobalThreadControlOrOwnHardware() throws Exception {
    Config previousConfig = Lizzie.config;
    try {
      Config config =
          ConfigTestHelper.createForTests(
              Files.createDirectories(temporaryDirectory.resolve("official-config")));
      Lizzie.config = config;
      initializeConfigJson(config);
      config.chkKataEngineThreads = false;
      config.autoLoadKataEngineThreads = false;
      config.txtKataEngineThreads = "13";
      config.uiConfig.put("chk-kata-engine-threads", false);
      config.uiConfig.put("autoload-kata-engine-threads", false);
      config.uiConfig.put("txt-kata-engine-threads", "13");
      config.uiConfig.put("katago-benchmark-topology", "old-experimental-topology");
      config.uiConfig.put("katago-benchmark-batch-size", 8);
      KataGoTuningProfile profile =
          KataGoTuningProfile.officialThreads(
              "official-fingerprint",
              7,
              new KataGoTuningProfile.Metrics(3, 3, 120.0, 100.0, 25.0, 4.0),
              "Metal",
              123L);

      KataGoRuntimeHelper.applyBenchmarkResult(officialResult(profile));

      assertFalse(config.chkKataEngineThreads);
      assertFalse(config.autoLoadKataEngineThreads);
      assertEquals("13", config.txtKataEngineThreads);
      KataGoTuningProfile stored =
          KataGoTuningProfile.fromJson(config.uiConfig.getJSONObject(KataGoTuningStore.KEY))
              .orElseThrow();
      assertFalse(stored.managesHardwareSettings());
      assertTrue(stored.devices().isEmpty());
      assertEquals(0, stored.batch());
      assertEquals(7, stored.threads());
      assertEquals("", config.uiConfig.getString("katago-benchmark-topology"));
      assertEquals(0, config.uiConfig.getInt("katago-benchmark-batch-size"));
    } finally {
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void benchmarkDisplayHidesAppleResultAfterSelectedModelChanges() throws Exception {
    Config previousConfig = Lizzie.config;
    String previousOsName = System.getProperty("os.name");
    String previousOsArch = System.getProperty("os.arch");
    try {
      System.setProperty("os.name", "Mac OS X");
      System.setProperty("os.arch", "aarch64");
      Config config =
          ConfigTestHelper.createForTests(
              Files.createDirectories(temporaryDirectory.resolve("display-signature-config")));
      Lizzie.config = config;
      initializeConfigJson(config);
      SetupSnapshot snapshot = createBundledAppleSnapshot("display-signature-app");
      config.uiConfig.put("katago-benchmark-threads", 7);
      config.uiConfig.put("katago-benchmark-current-threads", 1);
      config.uiConfig.put("katago-benchmark-backend", "Metal");
      config.uiConfig.put("katago-apple-auto-optimize-version", 5);
      config.uiConfig.put(
          "katago-benchmark-signature", KataGoRuntimeHelper.buildBenchmarkSignature(snapshot));
      KataGoTuningFingerprint fingerprint =
          KataGoTuningFingerprint.create(
              snapshot.enginePath,
              snapshot.activeWeightPath,
              snapshot.gtpConfigPath,
              new AppleSiliconHardwareProbe().probe(),
              KataGoRuntimeHelper.officialTuningCommandSemantics(List.of()));
      new KataGoTuningStore(config.uiConfig)
          .save(
              KataGoTuningProfile.officialThreads(
                  fingerprint,
                  7,
                  new KataGoTuningProfile.Metrics(3, 3, 120.0, 100.0, 25.0, 4.0),
                  "Metal",
                  123L));

      assertNotNull(KataGoRuntimeHelper.getStoredBenchmarkResult(snapshot));

      Files.writeString(snapshot.activeWeightPath, "changed-model-content");

      assertNull(KataGoRuntimeHelper.getStoredBenchmarkResult(snapshot));
      assertNotNull(
          KataGoRuntimeHelper.getStoredBenchmarkResult(),
          "The result remains stored but must not be presented as current.");
    } finally {
      restoreProperty("os.name", previousOsName);
      restoreProperty("os.arch", previousOsArch);
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void benchmarkDisplayHidesAppleResultAfterSourceTopologyChanges() throws Exception {
    Config previousConfig = Lizzie.config;
    String previousOsName = System.getProperty("os.name");
    String previousOsArch = System.getProperty("os.arch");
    try {
      System.setProperty("os.name", "Mac OS X");
      System.setProperty("os.arch", "aarch64");
      Config config =
          ConfigTestHelper.createForTests(
              Files.createDirectories(temporaryDirectory.resolve("display-topology-config")));
      Lizzie.config = config;
      initializeConfigJson(config);
      Path app = Files.createDirectories(temporaryDirectory.resolve("display-topology-app"));
      Path engine =
          Files.writeString(
              Files.createDirectories(app.resolve("engines/katago/macos-arm64")).resolve("katago"),
              "engine");
      Path configs = Files.createDirectories(app.resolve("engines/katago/configs"));
      Path gtp = Files.writeString(configs.resolve("gtp.cfg"), "config");
      Path analysis = Files.writeString(configs.resolve("analysis.cfg"), "analysis");
      Path model = Files.writeString(app.resolve("model.bin.gz"), "model");
      String originalCommand =
          engine
              + " analysis -model "
              + model
              + " -config "
              + analysis
              + " -override-config metalDeviceToUseModel0Thread0=0,nnMaxBatchSize=1";
      config.uiConfig.put("analysis-engine-command", originalCommand);
      SetupSnapshot originalSnapshot = KataGoAutoSetupHelper.inspectLocalKataGo().toSnapshot();
      config.uiConfig.put("katago-benchmark-threads", 7);
      config.uiConfig.put("katago-benchmark-current-threads", 1);
      config.uiConfig.put("katago-benchmark-backend", "Metal");
      config.uiConfig.put("katago-apple-auto-optimize-version", 5);
      config.uiConfig.put(
          "katago-benchmark-signature",
          KataGoRuntimeHelper.buildBenchmarkSignature(originalSnapshot));
      KataGoTuningFingerprint fingerprint =
          KataGoTuningFingerprint.create(
              engine,
              model,
              gtp,
              new AppleSiliconHardwareProbe().probe(),
              KataGoRuntimeHelper.officialTuningCommandSemantics(
                  Utils.splitCommand(originalCommand)));
      new KataGoTuningStore(config.uiConfig)
          .save(
              KataGoTuningProfile.officialThreads(
                  fingerprint,
                  7,
                  new KataGoTuningProfile.Metrics(3, 3, 120.0, 100.0, 25.0, 4.0),
                  "Metal",
                  123L));

      assertNotNull(KataGoRuntimeHelper.getStoredBenchmarkResult(originalSnapshot));

      config.uiConfig.put(
          "analysis-engine-command",
          originalCommand.replace("nnMaxBatchSize=1", "nnMaxBatchSize=2"));
      SetupSnapshot changedSnapshot = KataGoAutoSetupHelper.inspectLocalKataGo().toSnapshot();

      assertEquals(
          KataGoRuntimeHelper.buildBenchmarkSignature(originalSnapshot),
          KataGoRuntimeHelper.buildBenchmarkSignature(changedSnapshot),
          "The file signature intentionally stays stable; command semantics must invalidate it.");
      assertNull(KataGoRuntimeHelper.getStoredBenchmarkResult(changedSnapshot));
    } finally {
      restoreProperty("os.name", previousOsName);
      restoreProperty("os.arch", previousOsArch);
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void failedConfigSaveLeavesPreviousBenchmarkProfileAndMemoryUntouched() throws Exception {
    Config previousConfig = Lizzie.config;
    try {
      Config config =
          ConfigTestHelper.createForTests(
              Files.createDirectories(temporaryDirectory.resolve("failed-save-config")));
      Lizzie.config = config;
      initializeConfigJson(config);
      config.chkKataEngineThreads = false;
      config.autoLoadKataEngineThreads = false;
      config.txtKataEngineThreads = "13";
      config.uiConfig.put("sentinel", "keep");
      KataGoTuningProfile previousProfile =
          KataGoTuningProfile.officialThreads(
              "previous-fingerprint",
              3,
              new KataGoTuningProfile.Metrics(3, 3, 80.0, 70.0, 20.0, 3.5),
              "Metal",
              100L);
      new KataGoTuningStore(config.uiConfig).save(previousProfile);
      Path nonEmptyDirectory =
          Files.createDirectories(temporaryDirectory.resolve("config-target-is-directory"));
      Files.writeString(nonEmptyDirectory.resolve("block-replacement"), "keep");
      Field configFilename = Config.class.getDeclaredField("configFilename");
      configFilename.setAccessible(true);
      configFilename.set(config, nonEmptyDirectory.toString());
      KataGoTuningProfile replacement =
          KataGoTuningProfile.officialThreads(
              "replacement-fingerprint",
              7,
              new KataGoTuningProfile.Metrics(3, 3, 120.0, 100.0, 25.0, 4.0),
              "Metal",
              123L);

      assertThrows(
          IOException.class,
          () -> KataGoRuntimeHelper.applyBenchmarkResult(officialResult(replacement)));

      assertEquals("keep", config.uiConfig.getString("sentinel"));
      assertEquals(
          previousProfile,
          KataGoTuningProfile.fromJson(config.uiConfig.getJSONObject(KataGoTuningStore.KEY))
              .orElseThrow());
      assertFalse(config.uiConfig.has("katago-benchmark-threads"));
      assertFalse(config.chkKataEngineThreads);
      assertFalse(config.autoLoadKataEngineThreads);
      assertEquals("13", config.txtKataEngineThreads);
    } finally {
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void legacyResultStillEnablesGlobalThreadControl() throws Exception {
    Config previousConfig = Lizzie.config;
    try {
      Config config =
          ConfigTestHelper.createForTests(
              Files.createDirectories(temporaryDirectory.resolve("legacy-config")));
      Lizzie.config = config;
      initializeConfigJson(config);
      config.chkKataEngineThreads = false;
      config.autoLoadKataEngineThreads = false;
      config.txtKataEngineThreads = "";

      KataGoRuntimeHelper.applyBenchmarkResult(legacyResult(5));

      assertTrue(config.chkKataEngineThreads);
      assertTrue(config.autoLoadKataEngineThreads);
      assertEquals("5", config.txtKataEngineThreads);
      assertTrue(config.uiConfig.getBoolean("chk-kata-engine-threads"));
      assertTrue(config.uiConfig.getBoolean("autoload-kata-engine-threads"));
      assertEquals("5", config.uiConfig.getString("txt-kata-engine-threads"));
    } finally {
      Lizzie.config = previousConfig;
    }
  }

  private SetupSnapshot createSnapshot() throws IOException {
    Path engine = Files.writeString(temporaryDirectory.resolve("katago"), "engine");
    Path gtp = Files.writeString(temporaryDirectory.resolve("gtp.cfg"), "numSearchThreads=6");
    Files.writeString(temporaryDirectory.resolve("analysis.cfg"), "numAnalysisThreads=2");
    Path model = Files.writeString(temporaryDirectory.resolve("model.bin.gz"), "model");
    return KataGoAutoSetupHelper.inspectSelectedLocalKataGo(engine, gtp, model).toSnapshot();
  }

  private SetupSnapshot createBundledAppleSnapshot(String rootName) throws IOException {
    Path app = Files.createDirectories(temporaryDirectory.resolve(rootName));
    Path engine =
        Files.writeString(
            Files.createDirectories(app.resolve("engines/katago/macos-arm64")).resolve("katago"),
            "engine");
    Path configs = Files.createDirectories(app.resolve("engines/katago/configs"));
    Path gtp = Files.writeString(configs.resolve("gtp.cfg"), "config");
    Files.writeString(configs.resolve("analysis.cfg"), "analysis");
    Path model = Files.writeString(app.resolve("model.bin.gz"), "model");
    return KataGoAutoSetupHelper.inspectSelectedLocalKataGo(engine, gtp, model).toSnapshot();
  }

  private static String optionValue(List<String> command, String option) {
    int index = command.indexOf(option);
    assertTrue(index >= 0 && index + 1 < command.size(), "Missing option " + option);
    return command.get(index + 1);
  }

  private static KataGoRuntimeHelper.BenchmarkResult layeredResult(KataGoTuningProfile profile)
      throws Exception {
    return profileResult(profile, "GA", 2);
  }

  private static KataGoRuntimeHelper.BenchmarkResult officialResult(KataGoTuningProfile profile)
      throws Exception {
    return profileResult(profile, "", 0);
  }

  private static KataGoRuntimeHelper.BenchmarkResult profileResult(
      KataGoTuningProfile profile, String topology, int batch) throws Exception {
    Constructor<KataGoRuntimeHelper.BenchmarkResult> constructor =
        KataGoRuntimeHelper.BenchmarkResult.class.getDeclaredConstructor(
            int.class,
            int.class,
            String.class,
            String.class,
            long.class,
            String.class,
            int.class,
            double.class,
            double.class,
            double.class,
            KataGoTuningProfile.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        profile.threads(),
        1,
        "Metal",
        "profile",
        123L,
        topology,
        batch,
        120.0,
        100.0,
        4.0,
        profile);
  }

  private static KataGoRuntimeHelper.BenchmarkResult legacyResult(int threads) throws Exception {
    Constructor<KataGoRuntimeHelper.BenchmarkResult> constructor =
        KataGoRuntimeHelper.BenchmarkResult.class.getDeclaredConstructor(
            int.class, int.class, String.class, String.class, long.class);
    constructor.setAccessible(true);
    return constructor.newInstance(threads, 1, "Metal", "legacy", 123L);
  }

  private static void initializeConfigJson(Config config) {
    config.uiConfig = new JSONObject();
    config.config = new JSONObject();
    config.leelazConfig = new JSONObject();
  }

  private static void restoreProperty(String name, String previousValue) {
    if (previousValue == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, previousValue);
    }
  }
}
