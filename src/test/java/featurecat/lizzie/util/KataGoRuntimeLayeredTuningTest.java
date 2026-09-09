package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.katago.tuning.AppleSiliconHardwareProbe;
import featurecat.lizzie.util.katago.tuning.KataGoCommandSpec;
import featurecat.lizzie.util.katago.tuning.KataGoTuningCandidate;
import featurecat.lizzie.util.katago.tuning.KataGoTuningFingerprint;
import featurecat.lizzie.util.katago.tuning.KataGoTuningProfile;
import featurecat.lizzie.util.katago.tuning.KataGoTuningStore;
import java.io.IOException;
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
      assertFalse(overrides.containsKey("numSearchThreads"), alias);
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
  void entryLaunchPolicyHonorsCfgBenchmarkAndExplicitThreadPriority() throws Exception {
    Config previousConfig = Lizzie.config;
    try {
      Config config =
          ConfigTestHelper.createForTests(
              Files.createDirectories(temporaryDirectory.resolve("entry-priority-config")));
      Lizzie.config = config;
      initializeConfigJson(config);
      SetupSnapshot snapshot = createSnapshot();
      List<String> command =
          List.of(
              snapshot.enginePath.toString(),
              "gtp",
              "-config",
              snapshot.gtpConfigPath.toString(),
              "-model",
              snapshot.activeWeightPath.toString());
      JSONObject policy =
          new JSONObject()
              .put("source", "CFG")
              .put("sourceRevision", 0L)
              .put("katago-benchmark-threads", 7);
      EngineData entry = saveEntry(command, policy);

      assertEquals(
          command, KataGoRuntimeHelper.applyEntryLaunchPolicy(command, snapshot.enginePath, entry));

      entry = EngineThreadPolicy.saveSource(entry.id, EngineThreadPolicy.Source.BENCHMARK);
      KataGoCommandSpec benchmark =
          KataGoCommandSpec.parse(
              KataGoRuntimeHelper.applyEntryLaunchPolicy(command, snapshot.enginePath, entry));
      assertEquals("7", benchmark.overrideValue("numSearchThreads").orElseThrow());

      List<String> explicit =
          List.of(
              snapshot.enginePath.toString(),
              "gtp",
              "-config",
              snapshot.gtpConfigPath.toString(),
              "-model",
              snapshot.activeWeightPath.toString(),
              "--override-config",
              "numSearchThreads=11,userSetting=keep");
      KataGoCommandSpec explicitResult =
          KataGoCommandSpec.parse(
              KataGoRuntimeHelper.applyEntryLaunchPolicy(explicit, snapshot.enginePath, entry));
      assertEquals("11", explicitResult.overrideValue("numSearchThreads").orElseThrow());
      assertEquals("keep", explicitResult.overrideValue("userSetting").orElseThrow());
    } finally {
      Lizzie.config = previousConfig;
    }
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
      JSONObject policy =
          new JSONObject()
              .put("source", "BENCHMARK")
              .put("sourceRevision", 0L)
              .put("katago-benchmark-threads", 7);
      new KataGoTuningStore(policy)
          .save(
              KataGoTuningProfile.officialThreads(
                  fingerprint,
                  7,
                  new KataGoTuningProfile.Metrics(3, 3, 120.0, 100.0, 25.0, 4.0),
                  "Metal",
                  123L));
      EngineData entry = saveEntry(sourceCommand, policy);

      KataGoCommandSpec applied =
          KataGoCommandSpec.parse(
              KataGoRuntimeHelper.applyEntryLaunchPolicy(sourceCommand, engine, entry));

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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void staleAppleHardwareProfileKeepsValidEntryThreadsButSuppressesHardware(
      boolean changeAdditionalConfig) throws Exception {
    Config previousConfig = Lizzie.config;
    String previousOsName = System.getProperty("os.name");
    String previousOsArch = System.getProperty("os.arch");
    try {
      System.setProperty("os.name", "Mac OS X");
      System.setProperty("os.arch", "aarch64");
      Config config =
          ConfigTestHelper.createForTests(
              Files.createDirectories(temporaryDirectory.resolve("stale-apple-config")));
      Lizzie.config = config;
      initializeConfigJson(config);
      SetupSnapshot snapshot = createBundledAppleSnapshot("stale-apple-app");
      Path additionalConfig =
          Files.writeString(temporaryDirectory.resolve("device.cfg"), "nnMaxBatchSize=8");
      List<String> command =
          List.of(
              snapshot.enginePath.toString(),
              "gtp",
              "-config",
              snapshot.gtpConfigPath.toString(),
              "-model",
              snapshot.activeWeightPath.toString(),
              "-config",
              additionalConfig.toString());
      KataGoTuningFingerprint fingerprint =
          KataGoTuningFingerprint.create(
              snapshot.enginePath,
              snapshot.activeWeightPath,
              snapshot.gtpConfigPath,
              new AppleSiliconHardwareProbe().probe(),
              KataGoRuntimeHelper.tuningCommandSemantics(command));
      KataGoTuningProfile profile =
          new KataGoTuningProfile(
              fingerprint,
              List.of(0, 100),
              2,
              7,
              new KataGoTuningProfile.Metrics(3, 3, 120.0, 100.0, 25.0, 4.0),
              "Metal",
              123L);
      JSONObject policy =
          new JSONObject()
              .put("source", "BENCHMARK")
              .put("sourceRevision", 0L)
              .put("katago-benchmark-threads", 7);
      new KataGoTuningStore(policy).save(profile);
      EngineData entry = saveEntry(command, policy);

      KataGoCommandSpec current =
          KataGoCommandSpec.parse(
              KataGoRuntimeHelper.applyEntryLaunchPolicy(command, snapshot.enginePath, entry));
      assertEquals("7", current.overrideValue("numSearchThreads").orElseThrow());
      assertEquals("2", current.overrideValue("numNNServerThreadsPerModel").orElseThrow());
      assertEquals("0", current.overrideValue("metalDeviceToUseModel0Thread0").orElseThrow());

      Files.writeString(
          changeAdditionalConfig ? additionalConfig : snapshot.activeWeightPath,
          changeAdditionalConfig ? "nnMaxBatchSize=16" : "changed-model-content");
      KataGoCommandSpec stale =
          KataGoCommandSpec.parse(
              KataGoRuntimeHelper.applyEntryLaunchPolicy(command, snapshot.enginePath, entry));
      assertEquals("7", stale.overrideValue("numSearchThreads").orElseThrow());
      assertTrue(stale.overrideValue("numNNServerThreadsPerModel").isEmpty());
      assertTrue(stale.overrideValue("metalDeviceToUseModel0Thread0").isEmpty());
      assertTrue(stale.overrideValue("nnMaxBatchSize").isEmpty());
    } finally {
      restoreProperty("os.name", previousOsName);
      restoreProperty("os.arch", previousOsArch);
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void currentOfficialGpuRecommendationsAreAppliedButStaleModelResultsAreIgnored()
      throws Exception {
    Config previousConfig = Lizzie.config;
    String previousOsArch = System.getProperty("os.arch");
    try {
      // CUDA recommendations intentionally do not apply on Apple Silicon hosts.
      System.setProperty("os.arch", "amd64");
      Config config =
          ConfigTestHelper.createForTests(
              Files.createDirectories(temporaryDirectory.resolve("gpu-recommendation-config")));
      Lizzie.config = config;
      initializeConfigJson(config);
      SetupSnapshot snapshot = createSnapshot();
      JSONObject policy =
          new JSONObject()
              .put("source", "CFG")
              .put("sourceRevision", 0L)
              .put("katago-benchmark-threads", 8)
              .put("katago-benchmark-current-threads", 4)
              .put("katago-benchmark-backend", "CUDA")
              .put("katago-benchmark-nn-server-threads", 2)
              .put("katago-benchmark-batch-size", 4);
      List<String> command =
          List.of(
              snapshot.enginePath.toString(),
              "gtp",
              "-config",
              snapshot.gtpConfigPath.toString(),
              "-model",
              snapshot.activeWeightPath.toString());

      EngineData entry = saveEntry(command, policy);
      List<String> applied =
          KataGoRuntimeHelper.applyEntryLaunchPolicy(command, snapshot.enginePath, entry);
      KataGoCommandSpec appliedSpec = KataGoCommandSpec.parse(applied);
      assertEquals("2", appliedSpec.overrideValue("numNNServerThreadsPerModel").orElseThrow());
      assertEquals("4", appliedSpec.overrideValue("nnMaxBatchSize").orElseThrow());

      Files.writeString(snapshot.activeWeightPath, "changed-model-content");
      List<String> stale =
          KataGoRuntimeHelper.applyEntryLaunchPolicy(command, snapshot.enginePath, entry);
      assertTrue(KataGoCommandSpec.parse(stale).effectiveOverrides().isEmpty());
    } finally {
      restoreProperty("os.arch", previousOsArch);
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void explicitGpuTuningOverridesWinOverStoredOfficialRecommendations() throws Exception {
    Config previousConfig = Lizzie.config;
    try {
      Config config =
          ConfigTestHelper.createForTests(
              Files.createDirectories(temporaryDirectory.resolve("gpu-override-config")));
      Lizzie.config = config;
      initializeConfigJson(config);
      SetupSnapshot snapshot = createSnapshot();
      JSONObject policy =
          new JSONObject()
              .put("source", "CFG")
              .put("sourceRevision", 0L)
              .put("katago-benchmark-threads", 8)
              .put("katago-benchmark-nn-server-threads", 2)
              .put("katago-benchmark-batch-size", 4);
      List<String> command =
          List.of(
              snapshot.enginePath.toString(),
              "gtp",
              "-config",
              snapshot.gtpConfigPath.toString(),
              "-model",
              snapshot.activeWeightPath.toString(),
              "-override-config",
              "numNNServerThreadsPerModel=1,nnMaxBatchSize=9");

      EngineData entry = saveEntry(command, policy);
      List<String> applied =
          KataGoRuntimeHelper.applyEntryLaunchPolicy(command, snapshot.enginePath, entry);
      KataGoCommandSpec appliedSpec = KataGoCommandSpec.parse(applied);
      assertEquals("1", appliedSpec.overrideValue("numNNServerThreadsPerModel").orElseThrow());
      assertEquals("9", appliedSpec.overrideValue("nnMaxBatchSize").orElseThrow());
    } finally {
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void gtpOnlyAppleEntryRemainsEligibleForItsBenchmarkModes() throws Exception {
    Config previousConfig = Lizzie.config;
    String previousOsName = System.getProperty("os.name");
    String previousOsArch = System.getProperty("os.arch");
    try {
      System.setProperty("os.name", "Mac OS X");
      System.setProperty("os.arch", "aarch64");
      Lizzie.config = ConfigTestHelper.createForTests(temporaryDirectory);
      initializeConfigJson(Lizzie.config);
      SetupSnapshot snapshot = createBundledAppleSnapshot("gtp-only-apple");
      Files.delete(snapshot.analysisConfigPath);
      assertTrue(KataGoRuntimeHelper.isExperimentalAppleSiliconTuningAvailable(snapshot));
      assertFalse(snapshot.hasConfigs());
      assertFalse(Files.exists(snapshot.analysisConfigPath));
    } finally {
      restoreProperty("os.name", previousOsName);
      restoreProperty("os.arch", previousOsArch);
      Lizzie.config = previousConfig;
    }
  }

  private EngineData saveEntry(List<String> command, JSONObject policy) throws IOException {
    EngineData entry = new EngineData();
    entry.commands = String.join(" ", command);
    entry.name = "Saved KataGo";
    entry.width = entry.height = 19;
    entry.threadPolicy = policy;
    Utils.saveEngineSettings(new java.util.ArrayList<>(List.of(entry)));
    SetupSnapshot saved = KataGoAutoSetupHelper.inspectSavedEngine(entry);
    entry.threadPolicy.put("environment", EngineThreadPolicy.environment(saved));
    Utils.saveEngineSettings(new java.util.ArrayList<>(List.of(entry)));
    return entry;
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
