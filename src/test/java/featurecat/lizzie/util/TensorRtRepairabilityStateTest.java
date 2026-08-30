package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtInstallStatus;
import featurecat.lizzie.util.NvidiaGpuDetector.DetectionResult;
import featurecat.lizzie.util.NvidiaGpuDetector.GpuInfo;
import featurecat.lizzie.util.NvidiaGpuDetector.TensorRtRecommendation;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TensorRtRepairabilityStateTest {
  private static final String OS_NAME_PROPERTY = "os.name";
  private static final String WINDOWS_OS_NAME = "Windows 11";
  private static final String EMPTY_FILE_SHA256 =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  private static final String RTX_3060_ALLOWED_TEXT =
      "Optional: RTX 30 series and earlier may try TensorRT.";
  private static final String NOT_RECOMMENDED_TEXT =
      "Use CUDA: RTX 40/50 series run the unified CUDA package by default.";
  private static final String UNKNOWN_TEXT = "Could not confirm Compute Capability.";

  @BeforeEach
  void acceptEmptyCompanionFixture() {
    KataGoRuntimeHelper.setHumanSlCompanionSha256ForTests(EMPTY_FILE_SHA256);
    System.setProperty("lizzie.tensorrt.runtimeSearchPath", "");
  }

  @AfterEach
  void restoreProductionCompanionDigest() {
    KataGoRuntimeHelper.setHumanSlCompanionSha256ForTests(null);
    System.clearProperty("lizzie.tensorrt.runtimeSearchPath");
  }

  @Test
  void directMlRtx3060KeepsAllowedRecommendationAndIsRepairable() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-state-directml-rtx3060");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                TensorRtInstallStatus status =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, rtx3060Allowed());

                assertEquals(TensorRtRecommendation.ALLOWED, status.gpuRecommendation);
                assertEquals(RTX_3060_ALLOWED_TEXT, status.gpuRecommendationText);
                assertTrue(status.platformSupported);
                assertTrue(status.managedTargetAvailable);
                assertTrue(status.repairable);
                assertFalse(
                    status.applicable,
                    "Legacy applicable must stay false so the unmigrated install button stays off.");
                assertFalse(KataGoRuntimeHelper.canInstallTensorRt(snapshot));
              });
        });
  }

  @Test
  void cudaSnapshotKeepsLegacyApplicableWhileReportingRepairable() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-state-cuda-applicable");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createSnapshot(tempRoot, "windows-x64-nvidia", "nvidia");

          withConfig(
              runtimeWorkDirectory,
              () -> {
                TensorRtInstallStatus status =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, rtx3060Allowed());
                assertTrue(status.applicable);
                assertTrue(status.repairable);
                assertEquals(TensorRtRecommendation.ALLOWED, status.gpuRecommendation);
                assertTrue(KataGoRuntimeHelper.canInstallTensorRt(snapshot));
              });
        });
  }


  @Test
  void gpuEligibilityBlocksMissingAndOldHardwareWithoutBlockingModernOrUnknownNvidia()
      throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-state-gpu-advice");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                TensorRtInstallStatus pending =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, null);
                DetectionResult noGpuDetection =
                    detection(null, TensorRtRecommendation.UNKNOWN, UNKNOWN_TEXT);
                TensorRtInstallStatus noGpu =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, noGpuDetection);
                TensorRtInstallStatus notRecommended =
                    KataGoRuntimeHelper.inspectTensorRtInstall(
                        snapshot,
                        detection(
                            new GpuInfo("NVIDIA GeForce RTX 4090", 8, 9, "570.65", 24576L, "test"),
                            TensorRtRecommendation.NOT_RECOMMENDED,
                            NOT_RECOMMENDED_TEXT));
                DetectionResult unknownComputeDetection =
                    detection(
                        new GpuInfo("NVIDIA GPU", 0, 0, "570.65", 8192L, "test"),
                        TensorRtRecommendation.UNKNOWN,
                        UNKNOWN_TEXT);
                TensorRtInstallStatus unknownCompute =
                    KataGoRuntimeHelper.inspectTensorRtInstall(
                        snapshot, unknownComputeDetection);
                DetectionResult oldGpuDetection =
                    detection(
                        new GpuInfo("NVIDIA GeForce GTX 1080", 6, 1, "570.65", 8192L, "test"),
                        TensorRtRecommendation.NOT_RECOMMENDED,
                        "Unsupported TensorRT hardware");
                TensorRtInstallStatus oldGpu =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, oldGpuDetection);

                assertEquals(TensorRtRecommendation.UNKNOWN, pending.gpuRecommendation);
                assertFalse(pending.gpuRecommendationText.isBlank());
                assertEquals(NOT_RECOMMENDED_TEXT, notRecommended.gpuRecommendationText);
                assertEquals(UNKNOWN_TEXT, unknownCompute.gpuRecommendationText);
                assertNotEquals(pending.gpuRecommendationText, notRecommended.gpuRecommendationText);
                assertNotEquals(pending.gpuRecommendationText, unknownCompute.gpuRecommendationText);

                assertFalse(pending.gpuDetectionComplete);
                assertFalse(pending.gpuDetected);
                assertFalse(pending.hardwareEligible);
                assertTrue(pending.repairable);
                assertTrue(noGpu.gpuDetectionComplete);
                assertFalse(noGpu.gpuDetected);
                assertFalse(noGpu.hardwareEligible);
                assertTrue(
                    noGpu.activationMissingItems.contains(
                        TensorRtInstallStatus.MISSING_NVIDIA_GPU));

                assertTrue(notRecommended.gpuDetectionComplete);
                assertTrue(notRecommended.gpuDetected);
                assertTrue(notRecommended.hardwareEligible);
                assertTrue(notRecommended.repairable);
                assertTrue(unknownCompute.gpuDetected);
                assertTrue(unknownCompute.hardwareEligible);
                assertTrue(unknownCompute.repairable);
                assertTrue(oldGpu.gpuDetected);
                assertFalse(oldGpu.hardwareEligible);

                assertFalse(
                    KataGoRuntimeHelper.canRepairTensorRt(snapshot, noGpuDetection, null));
                assertTrue(
                    KataGoRuntimeHelper.canRepairTensorRt(
                        snapshot,
                        detection(
                            new GpuInfo(
                                "NVIDIA GeForce RTX 4090", 8, 9, "570.65", 24576L, "test"),
                            TensorRtRecommendation.NOT_RECOMMENDED,
                            NOT_RECOMMENDED_TEXT),
                        null));
                assertTrue(
                    KataGoRuntimeHelper.canRepairTensorRt(
                        snapshot, unknownComputeDetection, null));
                assertFalse(
                    KataGoRuntimeHelper.canRepairTensorRt(snapshot, oldGpuDetection, null));
                assertFalse(pending.applicable);
                assertFalse(KataGoRuntimeHelper.canInstallTensorRt(snapshot));
              });
        });
  }

  @Test
  void stateMatrixReportsIndependentComponentDimensions() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-state-matrix");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);
          DetectionResult gpu = rtx3060Allowed();

          withConfig(
              runtimeWorkDirectory,
              () -> {
                TensorRtInstallStatus runtimeMissing =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, gpu);
                assertIndependentFlags(
                    runtimeMissing, false, false, false, false, false, false, true);
                assertTrue(
                    runtimeMissing.activationMissingItems.contains(
                        TensorRtInstallStatus.MISSING_RUNTIME));
                assertTrue(
                    runtimeMissing.activationMissingItems.contains(
                        TensorRtInstallStatus.MISSING_ENGINE));
                assertFalse(
                    runtimeMissing.activationMissingItems.contains(
                        TensorRtInstallStatus.MISSING_ENGINE_STALE));

                installCurrentTensorRtEngine(runtimeWorkDirectory, false);
                installReadyRuntime(runtimeWorkDirectory);
                TensorRtInstallStatus companionMissing =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, gpu);
                assertIndependentFlags(
                    companionMissing, true, false, true, true, false, false, true);
                assertTrue(
                    companionMissing.activationMissingItems.contains(
                        TensorRtInstallStatus.MISSING_COMPANION));
                assertFalse(
                    companionMissing.activationMissingItems.contains(
                        TensorRtInstallStatus.MISSING_RUNTIME));

                Files.deleteIfExists(
                    tensorRtEngineDir(runtimeWorkDirectory).resolve("katago.exe"));
                TensorRtInstallStatus engineMissing =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, gpu);
                assertTrue(engineMissing.runtimeReady);
                assertFalse(engineMissing.enginePresent);
                assertFalse(engineMissing.engineCurrent);
                assertTrue(
                    engineMissing.activationMissingItems.contains(
                        TensorRtInstallStatus.MISSING_ENGINE));
                assertFalse(
                    engineMissing.activationMissingItems.contains(
                        TensorRtInstallStatus.MISSING_ENGINE_STALE));

                installCurrentTensorRtEngine(runtimeWorkDirectory, true);
                writeStaleTensorRtEngineManifest(tensorRtEngineDir(runtimeWorkDirectory));
                TensorRtInstallStatus engineStale =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, gpu);
                assertIndependentFlags(engineStale, true, true, true, false, false, false, true);
                assertTrue(
                    engineStale.activationMissingItems.contains(
                        TensorRtInstallStatus.MISSING_ENGINE_STALE));
                assertFalse(
                    engineStale.activationMissingItems.contains(
                        TensorRtInstallStatus.MISSING_ENGINE));

                writeCurrentTensorRtEngineManifest(tensorRtEngineDir(runtimeWorkDirectory));
                TensorRtInstallStatus installedInactive =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, gpu);
                assertIndependentFlags(installedInactive, true, true, true, true, false, true, true);
                assertTrue(installedInactive.installed);
                assertFalse(installedInactive.active);
                assertTrue(installedInactive.activationMissingItems.isEmpty());

                SetupSnapshot tensorRtSnapshot =
                    setupSnapshot(
                        snapshot.workingDir,
                        snapshot.appRoot,
                        tensorRtEngineDir(runtimeWorkDirectory).resolve("katago.exe"),
                        snapshot.gtpConfigPath,
                        snapshot.analysisConfigPath,
                        snapshot.activeWeightPath);
                TensorRtInstallStatus installedActive =
                    KataGoRuntimeHelper.inspectTensorRtInstall(tensorRtSnapshot, gpu);
                assertIndependentFlags(
                    installedActive, true, true, true, true, true, true, true);
                assertTrue(installedActive.installed);
                assertTrue(installedActive.active);
                assertTrue(installedActive.applicable);
              });
        });
  }

  @Test
  void activatableRequiresComponentsWeightAndGtpConfig() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-state-activatable");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot complete = createDirectMlSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                installReadyRuntime(runtimeWorkDirectory);
                installCurrentTensorRtEngine(runtimeWorkDirectory, true);
                writeCurrentTensorRtEngineManifest(tensorRtEngineDir(runtimeWorkDirectory));

                TensorRtInstallStatus ready =
                    KataGoRuntimeHelper.inspectTensorRtInstall(complete, rtx3060Allowed());
                assertTrue(ready.activatable);
                assertTrue(ready.activationMissingItems.isEmpty());
                assertTrue(ready.repairable);

                SetupSnapshot missingWeight =
                    setupSnapshot(
                        complete.workingDir,
                        complete.appRoot,
                        complete.enginePath,
                        complete.gtpConfigPath,
                        complete.analysisConfigPath,
                        complete.workingDir.resolve("weights").resolve("absent.bin.gz"));
                TensorRtInstallStatus noWeight =
                    KataGoRuntimeHelper.inspectTensorRtInstall(missingWeight, rtx3060Allowed());
                assertFalse(noWeight.activatable);
                assertEquals(
                    List.of(TensorRtInstallStatus.MISSING_WEIGHT), noWeight.activationMissingItems);
                assertTrue(noWeight.repairable);

                SetupSnapshot missingGtp =
                    setupSnapshot(
                        complete.workingDir,
                        complete.appRoot,
                        complete.enginePath,
                        complete.workingDir.resolve("missing-gtp.cfg"),
                        complete.analysisConfigPath,
                        complete.activeWeightPath);
                TensorRtInstallStatus noGtp =
                    KataGoRuntimeHelper.inspectTensorRtInstall(missingGtp, rtx3060Allowed());
                assertFalse(noGtp.activatable);
                assertEquals(
                    List.of(TensorRtInstallStatus.MISSING_GTP_CONFIG),
                    noGtp.activationMissingItems);
                assertTrue(noGtp.repairable);
              });
        });
  }

  @Test
  void nonWindowsPlatformRemainsUnrepairable() throws Exception {
    withOsName(
        "Linux",
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-state-linux");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                TensorRtInstallStatus status =
                    KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, rtx3060Allowed());
                assertFalse(status.platformSupported);
                assertFalse(status.repairable);
                assertFalse(status.applicable);
                assertEquals(TensorRtRecommendation.ALLOWED, status.gpuRecommendation);
              });
        });
  }

  @Test
  void inspectDoesNotDownloadReplaceFilesOrMutateProfiles() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("tensorrt-state-no-side-effects");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createDirectMlSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                Lizzie.config.leelazConfig.put(
                    "engine-settings-list",
                    new JSONArray()
                        .put(
                            new JSONObject()
                                .put("command", snapshot.enginePath + " gtp")
                                .put("name", "DirectML")));
                String enginesBefore = Lizzie.config.leelazConfig.toString();
                TreeSet<String> filesBefore = listRelativeFiles(tempRoot);

                KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, rtx3060Allowed());

                assertEquals(enginesBefore, Lizzie.config.leelazConfig.toString());
                assertEquals(filesBefore, listRelativeFiles(tempRoot));
              });
        });
  }

  private static void assertIndependentFlags(
      TensorRtInstallStatus status,
      boolean runtimeReady,
      boolean companionReady,
      boolean enginePresent,
      boolean engineCurrent,
      boolean profileActive,
      boolean activatable,
      boolean repairable) {
    assertEquals(runtimeReady, status.runtimeReady, "runtimeReady");
    assertEquals(companionReady, status.companionReady, "companionReady");
    assertEquals(enginePresent, status.enginePresent, "enginePresent");
    assertEquals(engineCurrent, status.engineCurrent, "engineCurrent");
    assertEquals(profileActive, status.profileActive, "profileActive");
    assertEquals(activatable, status.activatable, "activatable");
    assertEquals(repairable, status.repairable, "repairable");
  }

  private static DetectionResult rtx3060Allowed() {
    return detection(
        new GpuInfo("NVIDIA GeForce RTX 3060", 8, 6, "570.65", 12288L, "test"),
        TensorRtRecommendation.ALLOWED,
        RTX_3060_ALLOWED_TEXT);
  }

  private static DetectionResult detection(
      GpuInfo gpu, TensorRtRecommendation recommendation, String detailText) {
    try {
      Constructor<DetectionResult> constructor =
          DetectionResult.class.getDeclaredConstructor(
              boolean.class,
              List.class,
              GpuInfo.class,
              TensorRtRecommendation.class,
              String.class,
              String.class);
      constructor.setAccessible(true);
      List<GpuInfo> gpus = gpu == null ? List.of() : List.of(gpu);
      return constructor.newInstance(
          gpu != null, gpus, gpu, recommendation, detailText, detailText);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static SetupSnapshot createDirectMlSnapshot(Path tempRoot) throws Exception {
    return createSnapshot(tempRoot, "windows-x64-directml", "directml");
  }

  private static SetupSnapshot createSnapshot(
      Path tempRoot, String engineDirName, String backendMarker) throws Exception {
    Path workingDir = Files.createDirectories(tempRoot.resolve("working"));
    Path appRoot = Files.createDirectories(tempRoot.resolve("app"));
    Path engineDir =
        Files.createDirectories(appRoot.resolve("engines").resolve("katago").resolve(engineDirName));
    Path enginePath = touch(engineDir.resolve("katago.exe"));
    Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), backendMarker);
    Path configDir =
        Files.createDirectories(appRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfigPath = touch(configDir.resolve("gtp.cfg"));
    Path analysisConfigPath = touch(configDir.resolve("analysis.cfg"));
    Path weightPath = touch(workingDir.resolve("weights").resolve("default.bin.gz"));
    return setupSnapshot(
        workingDir, appRoot, enginePath, gtpConfigPath, analysisConfigPath, weightPath);
  }

  private static SetupSnapshot setupSnapshot(
      Path workingDir,
      Path appRoot,
      Path enginePath,
      Path gtpConfigPath,
      Path analysisConfigPath,
      Path weightPath)
      throws Exception {
    Constructor<SetupSnapshot> constructor =
        SetupSnapshot.class.getDeclaredConstructor(
            Path.class, Path.class, Path.class, Path.class, Path.class, Path.class, List.class);
    constructor.setAccessible(true);
    List<Path> weights = weightPath == null ? List.of() : Arrays.asList(weightPath);
    return constructor.newInstance(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        weightPath,
        weights);
  }

  private static Path tensorRtEngineDir(Path runtimeWorkDirectory) {
    return runtimeWorkDirectory
        .resolve("engines")
        .resolve("katago")
        .resolve("windows-x64-nvidia-tensorrt");
  }

  private static void installReadyRuntime(Path runtimeWorkDirectory) throws IOException {
    Path runtimeDir = Files.createDirectories(runtimeWorkDirectory.resolve("nvidia-runtime"));
    touchRequiredCuda12_8Dlls(runtimeDir);
    touch(runtimeDir.resolve("nvinfer_10.dll"));
    touch(runtimeDir.resolve("nvinfer_plugin_10.dll"));
  }

  private static void installCurrentTensorRtEngine(Path runtimeWorkDirectory, boolean companion)
      throws IOException {
    Path targetDir = Files.createDirectories(tensorRtEngineDir(runtimeWorkDirectory));
    touch(targetDir.resolve("katago.exe"));
    touch(targetDir.resolve("libz.dll"));
    Files.writeString(targetDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt\n");
    if (companion) {
      touch(targetDir.resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME));
    }
    writeCurrentTensorRtEngineManifestWithoutCompanion(targetDir);
  }

  private static void writeCurrentTensorRtEngineManifest(Path directory) throws IOException {
    touch(directory.resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME));
    writeCurrentTensorRtEngineManifestWithoutCompanion(directory);
    Files.writeString(
        directory.resolve("lizzieyzy-next-katago-engine-manifest.txt"),
        "HumanSL companion: katago-human-sl-cuda.exe\n"
            + "HumanSL companion SHA-256: "
            + EMPTY_FILE_SHA256
            + "\n",
        StandardOpenOption.APPEND);
  }

  private static void writeCurrentTensorRtEngineManifestWithoutCompanion(Path directory)
      throws IOException {
    Files.writeString(
        directory.resolve("lizzieyzy-next-katago-engine-manifest.txt"),
        "KataGo release: v1.18.1\n"
            + "Asset: katago-v1.18.1-trt10.9.0-cuda12.8-windows-x64.zip\n"
            + "Asset SHA-256: "
            + "49b7229803b2ccee5205cc9d1f7b1a37790469405324de5e5acaafe7a8a9172a\n");
  }

  private static void writeStaleTensorRtEngineManifest(Path directory) throws IOException {
    touch(directory.resolve(KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME));
    Files.writeString(
        directory.resolve("lizzieyzy-next-katago-engine-manifest.txt"),
        "KataGo release: v1.0.0\n"
            + "Asset: stale-tensorrt.zip\n"
            + "Asset SHA-256: "
            + EMPTY_FILE_SHA256
            + "\n"
            + "HumanSL companion: katago-human-sl-cuda.exe\n"
            + "HumanSL companion SHA-256: "
            + EMPTY_FILE_SHA256
            + "\n");
  }

  private static void touchRequiredCuda12_8Dlls(Path directory) throws IOException {
    touch(directory.resolve("cudart64_12.dll"));
    touch(directory.resolve("cublas64_12.dll"));
    touch(directory.resolve("cublasLt64_12.dll"));
    touch(directory.resolve("nvJitLink64_12.dll"));
    touch(directory.resolve("nvrtc64_120_0.dll"));
    touch(directory.resolve("nvrtc-builtins64_128.dll"));
    touch(directory.resolve("cudnn64_9.dll"));
    touch(directory.resolve("z.dll"));
    Files.writeString(
        directory.resolve("lizzieyzy-next-nvidia-runtime-manifest.txt"),
        "Profile: cuda12.8-cudnn9\n"
            + "- CUDA NVRTC: "
            + KataGoRuntimeHelper.CUDA_12_8_NVRTC_VERSION
            + " | fixture | sha256="
            + KataGoRuntimeHelper.CUDA_12_8_NVRTC_SHA256
            + "\n");
  }

  private static Path touch(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    return Files.write(file, new byte[0]);
  }

  private static TreeSet<String> listRelativeFiles(Path root) throws IOException {
    TreeSet<String> files = new TreeSet<String>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            files.add(root.relativize(file).toString().replace('\\', '/'));
            return FileVisitResult.CONTINUE;
          }
        });
    return files;
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
}
