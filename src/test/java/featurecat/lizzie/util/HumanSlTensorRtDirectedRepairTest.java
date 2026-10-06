package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.HumanSlAnalysisRunner;
import featurecat.lizzie.gui.KataGoAutoSetupDialog;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadSession;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtFailureKind;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtInstallStatus;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtRepairContext;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class HumanSlTensorRtDirectedRepairTest {
  private static final String OS_NAME_PROPERTY = "os.name";
  private static final String WINDOWS_OS_NAME = "Windows 11";
  private static final String EMPTY_FILE_SHA256 =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  @BeforeEach
  void acceptEmptyCompanionFixture() {
    KataGoRuntimeHelper.setHumanSlCompanionSha256ForTests(EMPTY_FILE_SHA256);
    KataGoRuntimeHelper.setKatagoExecutableSha256ForTests(EMPTY_FILE_SHA256);
    System.setProperty("lizzie.tensorrt.runtimeSearchPath", "");
  }

  @AfterEach
  void restoreProductionCompanionDigest() {
    KataGoRuntimeHelper.setHumanSlCompanionSha256ForTests(null);
    KataGoRuntimeHelper.setKatagoExecutableSha256ForTests(null);
    System.clearProperty("lizzie.tensorrt.runtimeSearchPath");
  }

  @Test
  void missingRuntimeHumanSlRunnerStartupProducesStructuredContext() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("humansl-tensorrt-runtime");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path enginePath = installManagedTensorRtEngine(runtimeWorkDirectory, true, true);
          Path modelPath = touch(tempRoot.resolve("human.bin.gz"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                String command = humanSlCommand(enginePath, modelPath);
                TensorRtRepairContext inspected =
                    KataGoRuntimeHelper.inspectHumanSlTensorRtStartupFailure(command);
                assertNotNull(inspected);
                assertEquals(enginePath.toRealPath(), inspected.failedExecutable);
                assertEquals(command, inspected.originalCommand);
                assertEquals(TensorRtFailureKind.MISSING_RUNTIME, inspected.failureKind);
                assertEquals(
                    List.of(TensorRtInstallStatus.MISSING_RUNTIME), inspected.missingItems);
                assertTrue(inspected.repairable);

                HumanSlAnalysisRunner runner = new HumanSlAnalysisRunner(command, modelPath);
                assertFalse(runner.start());
                TensorRtRepairContext context = runner.getTensorRtRepairContext();
                assertNotNull(context);
                assertEquals(enginePath.toRealPath(), context.failedExecutable);
                assertEquals(TensorRtFailureKind.MISSING_RUNTIME, context.failureKind);
                assertEquals(List.of(TensorRtInstallStatus.MISSING_RUNTIME), context.missingItems);
                assertTrue(context.repairable);
                assertSame(context, runner.getTensorRtRepairContext());
                assertTrue(
                    KataGoRuntimeHelper.offersTensorRtRepairAction(
                        new KataGoRuntimeHelper.TensorRtRuntimeException(context)));
              });
        });
  }

  @Test
  void missingCompanionHumanSlRunnerStartupProducesStructuredContext() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("humansl-tensorrt-companion");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path enginePath = installManagedTensorRtEngine(runtimeWorkDirectory, true, false);
          installReadyNvidiaRuntime(runtimeWorkDirectory);
          Path modelPath = touch(tempRoot.resolve("human.bin.gz"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                String command = humanSlCommand(enginePath, modelPath);
                TensorRtRepairContext inspected =
                    KataGoRuntimeHelper.inspectHumanSlTensorRtStartupFailure(command);
                assertEquals(TensorRtFailureKind.MISSING_COMPANION, inspected.failureKind);
                assertEquals(
                    List.of(TensorRtInstallStatus.MISSING_COMPANION), inspected.missingItems);
                assertTrue(inspected.repairable);
                assertEquals(enginePath.toRealPath(), inspected.failedExecutable);

                HumanSlAnalysisRunner runner = new HumanSlAnalysisRunner(command, modelPath);
                assertFalse(runner.start());
                TensorRtRepairContext context = runner.getTensorRtRepairContext();
                assertEquals(TensorRtFailureKind.MISSING_COMPANION, context.failureKind);
                assertEquals(
                    List.of(TensorRtInstallStatus.MISSING_COMPANION), context.missingItems);
                assertTrue(context.repairable);
              });
        });
  }

  @Test
  void incompleteMainEngineHumanSlRunnerStartupProducesStructuredContext() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("humansl-tensorrt-engine");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path enginePath = installManagedTensorRtEngine(runtimeWorkDirectory, false, true);
          installReadyNvidiaRuntime(runtimeWorkDirectory);
          Path modelPath = touch(tempRoot.resolve("human.bin.gz"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                String command = humanSlCommand(enginePath, modelPath);
                TensorRtRepairContext inspected =
                    KataGoRuntimeHelper.inspectHumanSlTensorRtStartupFailure(command);
                assertEquals(TensorRtFailureKind.MISSING_ENGINE, inspected.failureKind);
                assertEquals(
                    List.of(TensorRtInstallStatus.MISSING_ENGINE_STALE), inspected.missingItems);
                assertTrue(inspected.repairable);
                assertEquals(enginePath.toRealPath(), inspected.failedExecutable);

                HumanSlAnalysisRunner runner = new HumanSlAnalysisRunner(command, modelPath);
                assertFalse(runner.start());
                TensorRtRepairContext context = runner.getTensorRtRepairContext();
                assertEquals(TensorRtFailureKind.MISSING_ENGINE, context.failureKind);
                assertEquals(
                    List.of(TensorRtInstallStatus.MISSING_ENGINE_STALE), context.missingItems);
                assertTrue(context.repairable);
                assertFalse(context.displayMessage.isBlank());
              });
        });
  }

  @Test
  void emptyOrInvalidCommandAndCustomEngineDoNotGrantRepair() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("humansl-tensorrt-negative");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path custom = installCustomTensorRtEngine(tempRoot.resolve("custom").resolve("owner"));
          Path modelPath = touch(tempRoot.resolve("human.bin.gz"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                assertNull(KataGoRuntimeHelper.inspectHumanSlTensorRtStartupFailure(""));
                assertNull(KataGoRuntimeHelper.inspectHumanSlTensorRtStartupFailure(null));
                AnalysisEngineCommandHelper.Result empty =
                    AnalysisEngineCommandHelper.resolveHumanSlCommand("");
                assertFalse(empty.isSuccess());

                String customCommand = humanSlCommand(custom, modelPath);
                TensorRtRepairContext customContext =
                    KataGoRuntimeHelper.inspectHumanSlTensorRtStartupFailure(customCommand);
                assertNotNull(customContext);
                assertFalse(customContext.repairable);
                assertFalse(
                    KataGoRuntimeHelper.offersTensorRtRepairAction(
                        new KataGoRuntimeHelper.TensorRtRuntimeException(customContext)));
                assertFalse(KataGoAutoSetupDialog.openRequestForRepair(customContext).directed);

                HumanSlAnalysisRunner runner = new HumanSlAnalysisRunner(customCommand, modelPath);
                assertFalse(runner.start());
                assertFalse(
                    runner.getTensorRtRepairContext() != null
                        && runner.getTensorRtRepairContext().repairable);
              });
        });
  }

  @Test
  void aiCoachResolvedCommandUsesTheSameStructuredContext() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("humansl-tensorrt-aicoach");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path enginePath = installManagedTensorRtEngine(runtimeWorkDirectory, true, false);
          installReadyNvidiaRuntime(runtimeWorkDirectory);
          Path modelPath = touch(tempRoot.resolve("human.bin.gz"));
          Path analysisModel = touch(tempRoot.resolve("dummy.bin.gz"));
          Path analysisConfig = touch(tempRoot.resolve("analysis.cfg"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                String command =
                    enginePath.toAbsolutePath().normalize()
                        + " analysis -model "
                        + analysisModel.toAbsolutePath().normalize()
                        + " -config "
                        + analysisConfig.toAbsolutePath().normalize()
                        + " -human-model "
                        + modelPath.toAbsolutePath().normalize();
                Lizzie.config.analysisEngineCommand = command;
                AnalysisEngineCommandHelper.Result resolved =
                    AnalysisEngineCommandHelper.resolveHumanSlCommand(
                        Lizzie.config.analysisEngineCommand);
                assertTrue(resolved.isSuccess());
                TensorRtRepairContext context =
                    KataGoRuntimeHelper.inspectHumanSlTensorRtStartupFailure(resolved.getCommand());
                assertEquals(TensorRtFailureKind.MISSING_COMPANION, context.failureKind);
                assertTrue(context.repairable);
                KataGoAutoSetupDialog.OpenRequest request =
                    KataGoAutoSetupDialog.openRequestForRepair(context);
                assertSame(context, request.context);
                assertTrue(request.directed);
              });
        });
  }

  @Test
  void nonInteractiveHumanSlInspectHasNoUiSideEffects() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("humansl-tensorrt-headless");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path enginePath = installManagedTensorRtEngine(runtimeWorkDirectory, true, true);
          Path modelPath = touch(tempRoot.resolve("human.bin.gz"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                LizzieFrame previousFrame = Lizzie.frame;
                Lizzie.frame = null;
                try {
                  String command = humanSlCommand(enginePath, modelPath);
                  TensorRtRepairContext inspected =
                      KataGoRuntimeHelper.inspectHumanSlTensorRtStartupFailure(command);
                  HumanSlAnalysisRunner runner = new HumanSlAnalysisRunner(command, modelPath);
                  assertFalse(runner.start());
                  assertNotNull(inspected);
                  assertNotNull(runner.getTensorRtRepairContext());
                  assertTrue(runner.getTensorRtRepairContext().repairable);
                  assertNull(Lizzie.frame);
                } finally {
                  Lizzie.frame = previousFrame;
                }
              });
        });
  }

  @Test
  void humanSlCompanionRepairUsesTrustedAssetAndDoesNotWriteCudaProfile() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("humansl-tensorrt-repair");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);
          Path enginePath = installManagedTensorRtEngine(runtimeWorkDirectory, true, false);
          installReadyNvidiaRuntime(runtimeWorkDirectory);
          Path modelPath = touch(tempRoot.resolve("human.bin.gz"));
          RepairFixtures fixtures = createRepairFixtures(tempRoot);

          withRepairFixtures(
              fixtures,
              false,
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        seedDirectMlProfiles(snapshot);
                        String profilesBefore = profileFingerprint();
                        String command = humanSlCommand(enginePath, modelPath);
                        TensorRtRepairContext context =
                            KataGoRuntimeHelper.inspectHumanSlTensorRtStartupFailure(command);
                        assertEquals(TensorRtFailureKind.MISSING_COMPANION, context.failureKind);
                        assertTrue(context.repairable);

                        TensorRtInstallStatus repaired =
                            KataGoRuntimeHelper.repairTensorRtComponents(
                                snapshot, null, new DownloadSession(), context);

                        assertEquals(enginePath.toRealPath(), repaired.enginePath);
                        assertTrue(repaired.companionReady);
                        assertTrue(repaired.runtimeReady);
                        assertFalse(repaired.profileActive);
                        assertEquals(profilesBefore, profileFingerprint());
                        assertTrue(
                            Files.isRegularFile(
                                enginePath
                                    .getParent()
                                    .resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME)));
                        JSONArray engines =
                            Lizzie.config.leelazConfig.optJSONArray("engine-settings-list");
                        assertEquals(1, engines.length());
                        assertFalse(
                            engines
                                .optJSONObject(0)
                                .optString("command", "")
                                .toLowerCase(Locale.ROOT)
                                .contains("nvidia"));
                      }));
        });
  }

  @Test
  void cudaFallbackDoesNotSkipPackagedCompanionRepair() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("humansl-tensorrt-cuda-fallback");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);
          Path enginePath = installManagedTensorRtEngine(runtimeWorkDirectory, true, false);
          Path cudaEngine = installLegacyCudaEngine(runtimeWorkDirectory);
          installReadyNvidiaRuntime(runtimeWorkDirectory);
          Path modelPath = touch(tempRoot.resolve("human.bin.gz"));
          RepairFixtures fixtures = createRepairFixtures(tempRoot);

          withRepairFixtures(
              fixtures,
              false,
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        seedDirectMlProfiles(snapshot);
                        Lizzie.config
                            .leelazConfig
                            .optJSONArray("engine-settings-list")
                            .put(
                                new JSONObject()
                                    .put("name", "Legacy CUDA")
                                    .put("command", cudaEngine + " gtp")
                                    .put("preload", false));
                        String profilesBefore = profileFingerprint();
                        String command = humanSlCommand(enginePath, modelPath);
                        TensorRtRepairContext context =
                            KataGoRuntimeHelper.inspectHumanSlTensorRtStartupFailure(command);
                        assertEquals(TensorRtFailureKind.MISSING_COMPANION, context.failureKind);
                        assertTrue(context.repairable);

                        TensorRtInstallStatus repaired =
                            KataGoRuntimeHelper.repairTensorRtComponents(
                                snapshot, null, new DownloadSession(), context);

                        Path companion =
                            enginePath
                                .getParent()
                                .resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME);
                        assertTrue(Files.isRegularFile(companion));
                        assertTrue(repaired.companionReady);
                        assertEquals(profilesBefore, profileFingerprint());
                        assertNull(
                            KataGoRuntimeHelper.inspectHumanSlTensorRtStartupFailure(command));
                      }));
        });
  }

  private static Path installLegacyCudaEngine(Path runtimeWorkDirectory) throws IOException {
    Path targetDir =
        Files.createDirectories(
            runtimeWorkDirectory.resolve("engines").resolve("katago").resolve("windows-x64-nvidia"));
    Path enginePath = touch(targetDir.resolve("katago.exe"));
    Files.writeString(targetDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia\n");
    Files.writeString(
        targetDir.resolve("lizzieyzy-next-nvidia-runtime-manifest.txt"),
        "profile: cuda12.8-cudnn9\n");
    return enginePath.toAbsolutePath().normalize();
  }

  private static String humanSlCommand(Path enginePath, Path modelPath) {
    return enginePath.toAbsolutePath().normalize()
        + " analysis -model dummy.bin.gz -human-model "
        + modelPath.toAbsolutePath().normalize();
  }

  private static Path installManagedTensorRtEngine(
      Path runtimeWorkDirectory, boolean currentEngine, boolean companion) throws IOException {
    Path targetDir =
        Files.createDirectories(
            runtimeWorkDirectory
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64-nvidia-tensorrt"));
    Path enginePath = touch(targetDir.resolve("katago.exe"));
    touch(targetDir.resolve("libz.dll"));
    Files.writeString(targetDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt\n");
    String manifestText;
    if (currentEngine) {
      manifestText = KataGoRuntimeHelper.tensorRtEngineManifestText();
    } else {
      manifestText =
          "KataGo release: "
              + KataGoAssetCatalog.get().katagoReleaseTag()
              + "\nAsset SHA-256: "
              + "0000000000000000000000000000000000000000000000000000000000000000\n";
    }
    Files.writeString(
        targetDir.resolve("lizzieyzy-next-katago-engine-manifest.txt"), manifestText);
    if (companion) {
      touch(targetDir.resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME));
      Files.writeString(
          targetDir.resolve("lizzieyzy-next-katago-engine-manifest.txt"),
          "HumanSL companion: katago-human-sl-cuda.exe\n"
              + "HumanSL companion SHA-256: "
              + EMPTY_FILE_SHA256
              + "\n",
          StandardOpenOption.APPEND);
    }
    return enginePath.toAbsolutePath().normalize();
  }

  private static Path installCustomTensorRtEngine(Path ownerRoot) throws IOException {
    Path targetDir =
        Files.createDirectories(
            ownerRoot.resolve("engines").resolve("katago").resolve("windows-x64-nvidia-tensorrt"));
    Path enginePath = touch(targetDir.resolve("katago.exe"));
    Files.writeString(targetDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt\n");
    return enginePath.toAbsolutePath().normalize();
  }

  private static void installReadyNvidiaRuntime(Path runtimeWorkDirectory) throws IOException {
    Path runtimeDir = Files.createDirectories(runtimeWorkDirectory.resolve("nvidia-runtime"));
    String[] dlls = {
      "cudart64_12.dll",
      "cublas64_12.dll",
      "cublasLt64_12.dll",
      "cudnn64_9.dll",
      "nvJitLink64_12.dll",
      "nvrtc64_120_0.dll",
      "nvrtc-builtins64_128.dll",
      "nvinfer_10.dll",
      "nvonnxparser_10.dll",
      "nvinfer_plugin_10.dll",
      "z.dll"
    };
    for (String dll : dlls) {
      touch(runtimeDir.resolve(dll));
    }
    Files.writeString(
        runtimeDir.resolve("lizzieyzy-next-nvidia-runtime-manifest.txt"),
        "CUDA NVRTC: "
            + KataGoRuntimeHelper.CUDA_12_8_NVRTC_VERSION
            + "\nfixture\nSHA-256: "
            + KataGoRuntimeHelper.CUDA_12_8_NVRTC_SHA256
            + "\n");
  }

  private static Path touch(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    return Files.write(file, new byte[0]);
  }

  private static SetupSnapshot createDirectMlSnapshot(Path tempRoot) throws Exception {
    Path workingDir = Files.createDirectories(tempRoot.resolve("working"));
    Path appRoot = Files.createDirectories(tempRoot.resolve("app"));
    Path engineDir =
        Files.createDirectories(
            appRoot.resolve("engines").resolve("katago").resolve("windows-x64-directml"));
    Path enginePath = touch(engineDir.resolve("katago.exe"));
    Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "directml");
    Path configDir =
        Files.createDirectories(appRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfigPath = touch(configDir.resolve("gtp.cfg"));
    Path analysisConfigPath = touch(configDir.resolve("analysis.cfg"));
    Path weightPath = touch(workingDir.resolve("weights").resolve("default.bin.gz"));
    Constructor<SetupSnapshot> constructor =
        SetupSnapshot.class.getDeclaredConstructor(
            Path.class, Path.class, Path.class, Path.class, Path.class, Path.class, List.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        weightPath,
        Arrays.asList(weightPath));
  }

  private static void seedDirectMlProfiles(SetupSnapshot snapshot) {
    Lizzie.config.leelazConfig.put(
        "engine-settings-list",
        new JSONArray()
            .put(
                new JSONObject()
                    .put("name", "DirectML")
                    .put("command", snapshot.enginePath + " gtp")
                    .put("preload", false)));
    Lizzie.config.uiConfig.put("default-engine", 0);
    Lizzie.config.uiConfig.put("autoload-default", true);
    Lizzie.config.uiConfig.put("analysis-engine-command", "directml-analysis");
    Lizzie.config.analysisEngineCommand = "directml-analysis";
    Lizzie.config.analysisEngineCommandCustomized = true;
  }

  private static String profileFingerprint() {
    return Lizzie.config.leelazConfig.toString()
        + "|"
        + Lizzie.config.uiConfig.optInt("default-engine", -1)
        + "|"
        + Lizzie.config.uiConfig.optBoolean("autoload-default")
        + "|"
        + Lizzie.config.uiConfig.optString("analysis-engine-command")
        + "|"
        + String.valueOf(Lizzie.config.analysisEngineCommand);
  }

  private static RepairFixtures createRepairFixtures(Path tempRoot) throws Exception {
    Path fixtureDir = Files.createDirectories(tempRoot.resolve("fixture"));
    Path engineZip =
        writeZip(
            fixtureDir.resolve("katago-trt.zip"),
            "katago.exe",
            "fake-katago",
            "libz.dll",
            "fake-libz");
    Path companionZip = writeZip(fixtureDir.resolve("windows-nvidia.zip"), "katago.exe", "");
    Path runtimeZip = writeRuntimeFixtureZip(fixtureDir.resolve("nvidia-runtime.zip"));
    return new RepairFixtures(
        engineZip,
        sha256(engineZip),
        Files.size(engineZip),
        companionZip,
        sha256(companionZip),
        Files.size(companionZip),
        runtimeZip,
        sha256(runtimeZip),
        Files.size(runtimeZip));
  }

  private static Path writeRuntimeFixtureZip(Path zipPath) throws IOException {
    String[] dlls = {
      "cudart64_12.dll",
      "cublas64_12.dll",
      "cublasLt64_12.dll",
      "cudnn64_9.dll",
      "nvJitLink64_12.dll",
      "nvrtc64_120_0.dll",
      "nvrtc-builtins64_128.dll",
      "nvinfer_10.dll",
      "nvonnxparser_10.dll",
      "nvinfer_plugin_10.dll",
      "z.dll"
    };
    Files.createDirectories(zipPath.getParent());
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipPath))) {
      for (String dll : dlls) {
        output.putNextEntry(new ZipEntry(dll));
        output.closeEntry();
      }
    }
    return zipPath;
  }

  private static Path writeZip(Path zipPath, String... namesAndContents) throws IOException {
    Files.createDirectories(zipPath.getParent());
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipPath))) {
      for (int index = 0; index < namesAndContents.length; index += 2) {
        output.putNextEntry(new ZipEntry(namesAndContents[index]));
        output.write(namesAndContents[index + 1].getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
    return zipPath;
  }

  private static void withRepairFixtures(
      RepairFixtures fixtures, boolean includeRuntime, ThrowingRunnable action) throws Exception {
    String previousKatagoUrl = System.getProperty("lizzie.tensorrt.katago.url");
    String previousKatagoSha = System.getProperty("lizzie.tensorrt.katago.sha256");
    String previousKatagoSize = System.getProperty("lizzie.tensorrt.katago.size");
    String previousSkip = System.getProperty("lizzie.tensorrt.skipRuntimePackagesForTests");
    String previousCompanionUrl = System.getProperty("lizzie.tensorrt.companion.url");
    String previousCompanionSha = System.getProperty("lizzie.tensorrt.companion.sha256");
    String previousCompanionSize = System.getProperty("lizzie.tensorrt.companion.size");
    try {
      System.setProperty("lizzie.tensorrt.katago.url", fixtures.engineZip.toUri().toString());
      System.setProperty("lizzie.tensorrt.katago.sha256", fixtures.engineSha256);
      System.setProperty("lizzie.tensorrt.katago.size", Long.toString(fixtures.engineSize));
      System.setProperty("lizzie.tensorrt.skipRuntimePackagesForTests", "true");
      System.setProperty("lizzie.tensorrt.companion.url", fixtures.companionZip.toUri().toString());
      System.setProperty("lizzie.tensorrt.companion.sha256", fixtures.companionSha256);
      System.setProperty("lizzie.tensorrt.companion.size", Long.toString(fixtures.companionSize));
      action.run();
    } finally {
      restoreProperty("lizzie.tensorrt.katago.url", previousKatagoUrl);
      restoreProperty("lizzie.tensorrt.katago.sha256", previousKatagoSha);
      restoreProperty("lizzie.tensorrt.katago.size", previousKatagoSize);
      restoreProperty("lizzie.tensorrt.skipRuntimePackagesForTests", previousSkip);
      restoreProperty("lizzie.tensorrt.companion.url", previousCompanionUrl);
      restoreProperty("lizzie.tensorrt.companion.sha256", previousCompanionSha);
      restoreProperty("lizzie.tensorrt.companion.size", previousCompanionSize);
    }
  }

  private static void restoreProperty(String key, String previousValue) {
    if (previousValue == null) {
      System.clearProperty(key);
      return;
    }
    System.setProperty(key, previousValue);
  }

  private static String sha256(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(Files.readAllBytes(path));
    StringBuilder builder = new StringBuilder();
    for (byte value : hash) {
      builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
    }
    return builder.toString();
  }

  private static void withConfig(Path runtimeWorkDirectory, ThrowingRunnable action)
      throws Exception {
    Config previousConfig = Lizzie.config;
    String previousUserDir = System.getProperty("user.dir");
    try {
      System.setProperty("user.dir", runtimeWorkDirectory.toString());
      Lizzie.config = createTestConfig(runtimeWorkDirectory);
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

  private static Config createTestConfig(Path runtimeWorkDirectory) {
    Config config = ConfigTestHelper.createForTests(runtimeWorkDirectory);
    config.config = new JSONObject();
    config.leelazConfig = new JSONObject();
    config.uiConfig = new JSONObject();
    config.config.put("leelaz", config.leelazConfig);
    config.config.put("ui", config.uiConfig);
    return config;
  }

  private static void withOsName(String osName, ThrowingRunnable action) throws Exception {
    String previousOsName = System.getProperty(OS_NAME_PROPERTY);
    try {
      System.setProperty(OS_NAME_PROPERTY, osName);
      action.run();
    } finally {
      if (previousOsName == null) {
        System.clearProperty(OS_NAME_PROPERTY);
      } else {
        System.setProperty(OS_NAME_PROPERTY, previousOsName);
      }
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static final class RepairFixtures {
    final Path engineZip;
    final String engineSha256;
    final long engineSize;
    final Path companionZip;
    final String companionSha256;
    final long companionSize;
    final Path runtimeZip;
    final String runtimeSha256;
    final long runtimeSize;

    private RepairFixtures(
        Path engineZip,
        String engineSha256,
        long engineSize,
        Path companionZip,
        String companionSha256,
        long companionSize,
        Path runtimeZip,
        String runtimeSha256,
        long runtimeSize) {
      this.engineZip = engineZip;
      this.engineSha256 = engineSha256;
      this.engineSize = engineSize;
      this.companionZip = companionZip;
      this.companionSha256 = companionSha256;
      this.companionSize = companionSize;
      this.runtimeZip = runtimeZip;
      this.runtimeSha256 = runtimeSha256;
      this.runtimeSize = runtimeSize;
    }
  }
}
