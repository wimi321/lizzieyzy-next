package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadCancelledException;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadSession;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupResult;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

public class KataGoRuntimeHelperTest {
  private static final String OS_NAME_PROPERTY = "os.name";
  private static final String OS_ARCH_PROPERTY = "os.arch";
  private static final String PATH_SEPARATOR = System.getProperty("path.separator");
  private static final String WINDOWS_OS_NAME = "Windows 11";

  @Test
  void externalEngineKeepsOriginalDirectory() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-helper-external");
    Path enginePath = touch(tempRoot.resolve("external-engine").resolve("katago.exe"));
    Path originalDirectory = Files.createDirectories(tempRoot.resolve("working-dir"));
    Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
    String originalPath = String.join(PATH_SEPARATOR, Arrays.asList("alpha", "beta"));
    ProcessBuilder processBuilder = createProcessBuilder(originalDirectory, originalPath);

    withConfig(
        runtimeWorkDirectory,
        () -> KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, enginePath));

    assertEquals(
        normalize(originalDirectory),
        normalize(processBuilder.directory().toPath()),
        "External engine should keep its directory.");
    assertEquals(
        originalPath,
        processBuilder.environment().get("PATH"),
        "External engine should keep PATH unchanged.");
  }

  @Test
  void bundledOpenclEngineUsesRuntimeDirectory() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-helper-bundled-opencl");
    Path enginePath =
        touch(
            tempRoot
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64-opencl")
                .resolve("katago.exe"));
    Path originalDirectory = Files.createDirectories(tempRoot.resolve("working-dir"));
    Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
    String originalPath = String.join(PATH_SEPARATOR, Arrays.asList("alpha", "beta"));
    ProcessBuilder processBuilder = createProcessBuilder(originalDirectory, originalPath);

    withConfig(
        runtimeWorkDirectory,
        () -> KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, enginePath));

    assertEquals(
        normalize(runtimeWorkDirectory),
        normalize(processBuilder.directory().toPath()),
        "Bundled OpenCL engine should use runtime directory.");
    assertEquals(
        normalize(enginePath.getParent()),
        firstPathEntry(processBuilder.environment().get("PATH")),
        "Bundled OpenCL engine should prepend its engine directory.");
  }

  @Test
  void bundledNvidiaEnginePrependsRuntimePath() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-bundled-nvidia");
          Path enginePath =
              touch(
                  tempRoot
                      .resolve("engines")
                      .resolve("katago")
                      .resolve("windows-x64-nvidia")
                      .resolve("katago.exe"));
          Path originalDirectory = Files.createDirectories(tempRoot.resolve("working-dir"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path runtimeDir = Files.createDirectories(runtimeWorkDirectory.resolve("nvidia-runtime"));
          String originalPath = String.join(PATH_SEPARATOR, Arrays.asList("alpha", "beta"));
          ProcessBuilder processBuilder = createProcessBuilder(originalDirectory, originalPath);

          withConfig(
              runtimeWorkDirectory,
              () -> KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, enginePath));

          assertEquals(
              normalize(runtimeWorkDirectory),
              normalize(processBuilder.directory().toPath()),
              "Bundled NVIDIA engine should use runtime directory.");
          assertEquals(
              normalize(runtimeDir),
              firstPathEntry(processBuilder.environment().get("PATH")),
              "Bundled NVIDIA engine should prepend runtime directory first.");
          assertEquals(
              normalize(enginePath.getParent()),
              secondPathEntry(processBuilder.environment().get("PATH")),
              "Bundled NVIDIA engine should keep engine directory after runtime directory.");
        });
  }

  @Test
  void nvidia50MarkerPrependsRuntimePath() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-bundled-nvidia50");
          Path engineDir =
              Files.createDirectories(
                  tempRoot.resolve("engines").resolve("katago").resolve("windows-x64"));
          Files.writeString(
              engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia50-cuda");
          Path enginePath = touch(engineDir.resolve("katago.exe"));
          Path originalDirectory = Files.createDirectories(tempRoot.resolve("working-dir"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          Path runtimeDir = Files.createDirectories(runtimeWorkDirectory.resolve("nvidia-runtime"));
          String originalPath = String.join(PATH_SEPARATOR, Arrays.asList("alpha", "beta"));
          ProcessBuilder processBuilder = createProcessBuilder(originalDirectory, originalPath);

          withConfig(
              runtimeWorkDirectory,
              () -> KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, enginePath));

          assertEquals(
              normalize(runtimeDir),
              firstPathEntry(processBuilder.environment().get("PATH")),
              "RTX 50 NVIDIA package should prepend the runtime directory.");
          assertEquals(
              normalize(enginePath.getParent()),
              secondPathEntry(processBuilder.environment().get("PATH")),
              "RTX 50 NVIDIA package should keep the engine directory after runtime.");
        });
  }

  @Test
  void nvidia50CudaRuntimeAcceptsCudnn9() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-nvidia50-cuda-runtime");
          Path engineDir =
              Files.createDirectories(
                  tempRoot.resolve("engines").resolve("katago").resolve("windows-x64"));
          Files.writeString(
              engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia50-cuda");
          Path enginePath = touch(engineDir.resolve("katago.exe"));
          touchRequiredCuda12_8Dlls(engineDir);
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.NvidiaRuntimeStatus status =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(enginePath);

                assertTrue(status.applicable, "RTX 50 CUDA package should need NVIDIA runtime.");
                assertTrue(status.ready, "CUDA 12.8/cuDNN 9 runtime should satisfy RTX 50 CUDA.");
                assertEquals(0, status.missingDlls.size());
              });
        });
  }

  @Test
  void nvidia50CudaRuntimeRejectsOldCudnn8OnlyBundle() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-nvidia50-cuda-old-cudnn");
          Path engineDir =
              Files.createDirectories(
                  tempRoot.resolve("engines").resolve("katago").resolve("windows-x64"));
          Files.writeString(
              engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia50-cuda");
          Path enginePath = touch(engineDir.resolve("katago.exe"));
          touchCommonCuda12Dlls(engineDir);
          touch(engineDir.resolve("cudnn64_8.dll"));
          touch(engineDir.resolve("libz.dll"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.NvidiaRuntimeStatus status =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(enginePath);

                assertTrue(status.applicable);
                assertEquals(false, status.ready);
                assertTrue(
                    status.missingDlls.contains("cudnn64_9.dll"),
                    "RTX 50 CUDA package must require cuDNN 9.");
              });
        });
  }

  @Test
  void nvidia50TensorRtRuntimeRequiresTensorRtDlls() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-nvidia50-trt-runtime");
          Path engineDir =
              Files.createDirectories(
                  tempRoot.resolve("engines").resolve("katago").resolve("windows-x64"));
          Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia50-trt");
          Path enginePath = touch(engineDir.resolve("katago.exe"));
          touchRequiredCuda12_8Dlls(engineDir);
          touch(engineDir.resolve("nvinfer_10.dll"));
          touch(engineDir.resolve("nvinfer_plugin_10.dll"));
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.NvidiaRuntimeStatus status =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(enginePath);

                assertTrue(status.applicable, "TensorRT package should need NVIDIA runtime.");
                assertTrue(status.ready, "TensorRT runtime DLLs should satisfy the package.");
              });
        });
  }

  @Test
  void tensorRtInstallSpecUsesOfficialKataGoAssetAndWritableRuntimeTarget() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-spec");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createNvidia50Snapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                KataGoRuntimeHelper.TensorRtInstallSpec spec =
                    KataGoRuntimeHelper.buildTensorRtInstallSpec(snapshot);

                assertTrue(
                    spec.katagoUrl.endsWith("/katago-v1.16.4-trt10.9.0-cuda12.8-windows-x64.zip"));
                assertEquals(
                    "1dea0b507c6331c9a7cf4f0ed2eeee5384b880d60f1db7fe876506daee55830f",
                    spec.katagoSha256);
                assertEquals(5, spec.runtimePackageCount);
                assertTrue(spec.totalDownloadBytes > 3_000_000_000L);
                assertEquals(
                    normalize(
                        runtimeWorkDirectory
                            .resolve("engines")
                            .resolve("katago")
                            .resolve("windows-x64-nvidia50-trt")),
                    normalize(spec.targetEngineDir));
                assertEquals(
                    normalize(spec.targetEngineDir.resolve("katago.exe")),
                    normalize(spec.targetEnginePath));
              });
        });
  }

  @Test
  void tensorRtInstallCreatesSeparateEngineProfileFromFixture() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-install");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createNvidia50Snapshot(tempRoot);
          Path fixtureZip =
              createTensorRtFixtureZip(tempRoot.resolve("fixture").resolve("katago-trt.zip"));
          withTensorRtFixtureProperties(
              fixtureZip.toUri().toString(),
              sha256(fixtureZip),
              Files.size(fixtureZip),
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        SetupResult result =
                            KataGoRuntimeHelper.downloadAndInstallTensorRt(
                                snapshot, null, new DownloadSession());
                        Path targetDir =
                            runtimeWorkDirectory
                                .resolve("engines")
                                .resolve("katago")
                                .resolve("windows-x64-nvidia50-trt");

                        assertEquals("KataGo TensorRT RTX 50 Experimental", result.engineName);
                        assertTrue(Files.isRegularFile(targetDir.resolve("katago.exe")));
                        assertTrue(Files.isRegularFile(targetDir.resolve("libz.dll")));
                        assertEquals(
                            "nvidia50-trt",
                            Files.readString(targetDir.resolve("lizzieyzy-next-engine-backend.txt"))
                                .trim());
                        List<EngineData> engines = Utils.getEngineData();
                        assertTrue(
                            engines.stream()
                                .anyMatch(
                                    engine ->
                                        "KataGo TensorRT RTX 50 Experimental".equals(engine.name)
                                            && engine.isDefault
                                            && engine.commands.contains(
                                                "windows-x64-nvidia50-trt")));
                      }));
        });
  }

  @Test
  void cancelledTensorRtInstallLeavesEngineProfileUnchanged() throws Exception {
    withOsName(
        WINDOWS_OS_NAME,
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-tensorrt-cancel");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createNvidia50Snapshot(tempRoot);
          DownloadSession session = new DownloadSession();
          session.cancel();

          withTensorRtFixtureProperties(
              "http://127.0.0.1:9/never.zip",
              "",
              1L,
              () ->
                  withConfig(
                      runtimeWorkDirectory,
                      () -> {
                        assertThrows(
                            DownloadCancelledException.class,
                            () ->
                                KataGoRuntimeHelper.downloadAndInstallTensorRt(
                                    snapshot, null, session));
                        assertFalse(
                            Files.exists(
                                runtimeWorkDirectory
                                    .resolve("engines")
                                    .resolve("katago")
                                    .resolve("windows-x64-nvidia50-trt")));
                        assertFalse(
                            Utils.getEngineData().stream()
                                .anyMatch(
                                    engine ->
                                        "KataGo TensorRT RTX 50 Experimental".equals(engine.name)));
                      }));
        });
  }

  @Test
  void smartOptimizeUsesBoundedOfficialBenchmarkArguments() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-helper-benchmark-command");
    Path enginePath = touch(tempRoot.resolve("external-engine").resolve("katago"));
    Path gtpConfigPath = touch(tempRoot.resolve("configs").resolve("gtp.cfg"));
    Path analysisConfigPath = touch(tempRoot.resolve("configs").resolve("analysis.cfg"));
    Path estimateConfigPath = touch(tempRoot.resolve("configs").resolve("estimate.cfg"));
    Path weightPath = touch(tempRoot.resolve("weights").resolve("default.bin.gz"));
    Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
    SetupSnapshot snapshot =
        setupSnapshot(
            tempRoot,
            tempRoot,
            enginePath,
            gtpConfigPath,
            analysisConfigPath,
            estimateConfigPath,
            weightPath);

    withConfig(
        runtimeWorkDirectory,
        () -> {
          Lizzie.config.maxGameThinkingTimeSeconds = 12;
          List<String> command = KataGoRuntimeHelper.buildBenchmarkCommand(snapshot);

          assertEquals(normalize(enginePath).toString(), command.get(0));
          assertEquals("benchmark", command.get(1));
          assertTrue(command.contains("-s"), "Smart Optimize should keep KataGo official tuning.");
          assertOptionValue(command, "-n", "6");
          assertOptionValue(command, "-v", "800");
          assertOptionValue(command, "-time", "12");
        });
  }

  @Test
  void bundledLaunchCommandAddsHomeDataDirAndPvLengthOverride() throws Exception {
    Path tempRoot = Files.createTempDirectory("katago-helper-bundled-command");
    Path enginePath =
        touch(
            tempRoot
                .resolve("engines")
                .resolve("katago")
                .resolve("windows-x64")
                .resolve("katago.exe"));
    Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));

    withConfig(
        runtimeWorkDirectory,
        () -> {
          Lizzie.config.limitBranchLength = 32;
          List<String> command =
              KataGoRuntimeHelper.prepareBundledLaunchCommand(
                  Arrays.asList(enginePath.toString(), "gtp", "-config", "gtp.cfg"), enginePath);

          assertTrue(command.contains("-override-config"));
          String overrides = command.get(command.indexOf("-override-config") + 1);
          assertTrue(overrides.contains("homeDataDir="));
          assertTrue(overrides.contains("analysisPVLen=32"));
        });
  }

  @Test
  void katagoAnalysisCommandReceivesPvLengthOverride() throws Exception {
    Path runtimeWorkDirectory = Files.createTempDirectory("katago-helper-pvlen");
    withConfig(
        runtimeWorkDirectory,
        () -> {
          Lizzie.config.limitBranchLength = 28;
          String command =
              KataGoRuntimeHelper.optimizeAnalysisEngineCommand(
                  "katago analysis -model model.bin.gz -config analysis.cfg", 100, false);

          assertTrue(command.contains("analysisPVLen=28"));
        });
  }

  @Test
  void nonKataGoAnalysisCommandKeepsOriginalText() throws Exception {
    Path runtimeWorkDirectory = Files.createTempDirectory("katago-helper-nonkatago");
    withConfig(
        runtimeWorkDirectory,
        () ->
            assertEquals(
                "leelaz --gtp",
                KataGoRuntimeHelper.optimizeAnalysisEngineCommand("leelaz --gtp", 100, false)));
  }

  @Test
  void benchmarkHeartbeatSmoothsLateSilentProgress() {
    int smoothed = KataGoRuntimeHelper.smoothSilentBenchmarkProgress(880, 12000L);

    assertTrue(smoothed > 880, "Silent official benchmark output should still feel alive.");
    assertTrue(smoothed <= 970, "Heartbeat should leave room for the real final summary.");
  }

  @Test
  void appleSiliconStartupBenchmarkRespectsUserSwitch() throws Exception {
    withOsNameAndArch(
        "Mac OS X",
        "aarch64",
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-apple-switch");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createAppleSiliconSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                Lizzie.config.enableStartupBenchmark = false;
                assertFalse(
                    KataGoRuntimeHelper.shouldRunAppleSiliconAutoBenchmark(snapshot),
                    "Apple Silicon auto benchmark must respect the startup benchmark switch.");

                Lizzie.config.enableStartupBenchmark = true;
                assertTrue(
                    KataGoRuntimeHelper.shouldRunAppleSiliconAutoBenchmark(snapshot),
                    "Fresh Apple Silicon setup should still offer the first auto benchmark.");
              });
        });
  }

  @Test
  void dismissedStartupBenchmarkDoesNotReappearForSameEngineAndWeight() throws Exception {
    withOsNameAndArch(
        "Mac OS X",
        "aarch64",
        () -> {
          Path tempRoot = Files.createTempDirectory("katago-helper-apple-dismiss");
          Path runtimeWorkDirectory = Files.createDirectories(tempRoot.resolve("runtime-root"));
          SetupSnapshot snapshot = createAppleSiliconSnapshot(tempRoot);

          withConfig(
              runtimeWorkDirectory,
              () -> {
                Lizzie.config.enableStartupBenchmark = true;
                assertTrue(KataGoRuntimeHelper.shouldRunAppleSiliconAutoBenchmark(snapshot));

                KataGoRuntimeHelper.rememberStartupBenchmarkDismissal(snapshot);

                assertTrue(KataGoRuntimeHelper.isStartupBenchmarkDismissed(snapshot));
                assertFalse(
                    KataGoRuntimeHelper.shouldRunAppleSiliconAutoBenchmark(snapshot),
                    "Closing the auto benchmark notice should not nag again for the same setup.");
              });
        });
  }

  private static ProcessBuilder createProcessBuilder(Path directory, String pathValue) {
    ProcessBuilder processBuilder = new ProcessBuilder("echo");
    processBuilder.directory(directory.toFile());
    processBuilder.environment().put("PATH", pathValue);
    return processBuilder;
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
      restoreOsName(previousOsName);
    }
  }

  private static void withOsNameAndArch(String osName, String osArch, ThrowingRunnable action)
      throws Exception {
    String previousOsName = System.getProperty(OS_NAME_PROPERTY);
    String previousOsArch = System.getProperty(OS_ARCH_PROPERTY);
    try {
      System.setProperty(OS_NAME_PROPERTY, osName);
      System.setProperty(OS_ARCH_PROPERTY, osArch);
      action.run();
    } finally {
      restoreOsName(previousOsName);
      restoreProperty(OS_ARCH_PROPERTY, previousOsArch);
    }
  }

  private static void restoreOsName(String previousOsName) {
    if (previousOsName == null) {
      System.clearProperty(OS_NAME_PROPERTY);
      return;
    }
    System.setProperty(OS_NAME_PROPERTY, previousOsName);
  }

  private static Path firstPathEntry(String pathValue) {
    return Path.of(pathValue.split(java.util.regex.Pattern.quote(PATH_SEPARATOR))[0])
        .toAbsolutePath()
        .normalize();
  }

  private static Path secondPathEntry(String pathValue) {
    return Path.of(pathValue.split(java.util.regex.Pattern.quote(PATH_SEPARATOR))[1])
        .toAbsolutePath()
        .normalize();
  }

  private static Path touch(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    return Files.write(file, new byte[0]);
  }

  private static void touchCommonCuda12Dlls(Path directory) throws IOException {
    touch(directory.resolve("cudart64_12.dll"));
    touch(directory.resolve("cublas64_12.dll"));
    touch(directory.resolve("cublasLt64_12.dll"));
    touch(directory.resolve("nvJitLink64_12.dll"));
  }

  private static void touchRequiredCuda12_8Dlls(Path directory) throws IOException {
    touchCommonCuda12Dlls(directory);
    touch(directory.resolve("cudnn64_9.dll"));
    touch(directory.resolve("libz.dll"));
  }

  private static SetupSnapshot createNvidia50Snapshot(Path tempRoot) throws Exception {
    Path workingDir = Files.createDirectories(tempRoot.resolve("working"));
    Path appRoot = Files.createDirectories(tempRoot.resolve("app"));
    Path engineDir =
        Files.createDirectories(
            appRoot.resolve("engines").resolve("katago").resolve("windows-x64-nvidia50-cuda"));
    Path enginePath = touch(engineDir.resolve("katago.exe"));
    Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia50-cuda");
    Path configDir =
        Files.createDirectories(appRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfigPath = touch(configDir.resolve("gtp.cfg"));
    Path analysisConfigPath = touch(configDir.resolve("analysis.cfg"));
    Path estimateConfigPath = touch(configDir.resolve("estimate.cfg"));
    Path weightPath = touch(workingDir.resolve("weights").resolve("default.bin.gz"));
    return setupSnapshot(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        estimateConfigPath,
        weightPath);
  }

  private static SetupSnapshot createAppleSiliconSnapshot(Path tempRoot) throws Exception {
    Path workingDir = Files.createDirectories(tempRoot.resolve("working"));
    Path appRoot = Files.createDirectories(tempRoot.resolve("app"));
    Path engineDir =
        Files.createDirectories(
            appRoot.resolve("engines").resolve("katago").resolve("macos-arm64"));
    Path enginePath = touch(engineDir.resolve("katago"));
    Path configDir =
        Files.createDirectories(appRoot.resolve("engines").resolve("katago").resolve("configs"));
    Path gtpConfigPath = touch(configDir.resolve("gtp.cfg"));
    Path analysisConfigPath = touch(configDir.resolve("analysis.cfg"));
    Path estimateConfigPath = touch(configDir.resolve("estimate.cfg"));
    Path weightPath = touch(workingDir.resolve("weights").resolve("default.bin.gz"));
    return setupSnapshot(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        estimateConfigPath,
        weightPath);
  }

  private static Path createTensorRtFixtureZip(Path zipPath) throws IOException {
    Files.createDirectories(zipPath.getParent());
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipPath))) {
      writeZipEntry(output, "katago.exe", "fake-katago");
      writeZipEntry(output, "libz.dll", "fake-libz");
      writeZipEntry(output, "cacert.pem", "fake-cert");
      writeZipEntry(output, "default_gtp.cfg", "ignored");
    }
    return zipPath;
  }

  private static void writeZipEntry(ZipOutputStream output, String name, String content)
      throws IOException {
    output.putNextEntry(new ZipEntry(name));
    output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    output.closeEntry();
  }

  private static void withTensorRtFixtureProperties(
      String url, String sha256, long size, ThrowingRunnable action) throws Exception {
    String previousUrl = System.getProperty("lizzie.tensorrt.katago.url");
    String previousSha = System.getProperty("lizzie.tensorrt.katago.sha256");
    String previousSize = System.getProperty("lizzie.tensorrt.katago.size");
    String previousSkip = System.getProperty("lizzie.tensorrt.skipRuntimePackagesForTests");
    try {
      System.setProperty("lizzie.tensorrt.katago.url", url);
      System.setProperty("lizzie.tensorrt.katago.sha256", sha256);
      System.setProperty("lizzie.tensorrt.katago.size", Long.toString(size));
      System.setProperty("lizzie.tensorrt.skipRuntimePackagesForTests", "true");
      action.run();
    } finally {
      restoreProperty("lizzie.tensorrt.katago.url", previousUrl);
      restoreProperty("lizzie.tensorrt.katago.sha256", previousSha);
      restoreProperty("lizzie.tensorrt.katago.size", previousSize);
      restoreProperty("lizzie.tensorrt.skipRuntimePackagesForTests", previousSkip);
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
    byte[] bytes = Files.readAllBytes(path);
    byte[] hash = digest.digest(bytes);
    StringBuilder builder = new StringBuilder();
    for (byte value : hash) {
      builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
    }
    return builder.toString();
  }

  private static Path normalize(Path path) {
    return path.toAbsolutePath().normalize();
  }

  private static SetupSnapshot setupSnapshot(
      Path workingDir,
      Path appRoot,
      Path enginePath,
      Path gtpConfigPath,
      Path analysisConfigPath,
      Path estimateConfigPath,
      Path weightPath,
      List<Path> weightCandidates)
      throws Exception {
    Constructor<SetupSnapshot> constructor =
        SetupSnapshot.class.getDeclaredConstructor(
            Path.class,
            Path.class,
            Path.class,
            Path.class,
            Path.class,
            Path.class,
            Path.class,
            List.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        estimateConfigPath,
        weightPath,
        weightCandidates);
  }

  private static SetupSnapshot setupSnapshot(
      Path workingDir,
      Path appRoot,
      Path enginePath,
      Path gtpConfigPath,
      Path analysisConfigPath,
      Path estimateConfigPath,
      Path weightPath)
      throws Exception {
    return setupSnapshot(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        estimateConfigPath,
        weightPath,
        Arrays.asList(weightPath));
  }

  private static void assertOptionValue(List<String> command, String option, String expectedValue) {
    int index = command.indexOf(option);
    assertTrue(index >= 0, "Expected benchmark option " + option);
    assertTrue(index + 1 < command.size(), "Expected a value after benchmark option " + option);
    assertEquals(expectedValue, command.get(index + 1));
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
